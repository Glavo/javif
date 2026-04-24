/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Minimal frame reconstructor used by the first pixel-producing AV1 decode path.
///
/// The current implementation is intentionally narrow. It reconstructs only the current `8-bit`,
/// `10-bit`, and `12-bit` key/intra subset plus a first single-reference, average-compound, and same-frame `intrabc` subset with
/// the current fixed-filter subpel prediction path over the current serial tile traversal,
/// `I400`, `I420`, `I422`, or `I444` chroma layout,
/// non-directional and directional intra prediction, filter-intra luma prediction, the current
/// `I420` / `I422` / `I444` CFL chroma subset, parsed and synthetic luma/chroma palette paths, a
/// normative horizontal super-resolution upscaling path for key/intra frames plus the current
/// inter/reference prediction subset, and a minimal luma/chroma residual subset including clipped
/// frame-fringe chroma footprints and the currently supported square and rectangular `DCT_DCT`
/// transform sizes whose axes stay within `64` samples, and bit-depth preserving inter subpel
/// filtering for stored reference surfaces.
@NotNullByDefault
public final class FrameReconstructor {
    /// The number of coefficients in one 8-tap interpolation kernel.
    private static final int INTER_FILTER_TAP_COUNT = 8;

    /// The signed source-sample offset of the first tap relative to the integer source position.
    private static final int INTER_FILTER_START_OFFSET = 3;

    /// The AV1 fixed-filter normalization shift.
    private static final int INTER_FILTER_BITS = 6;

    /// The AV1 fixed-filter normalization factor.
    private static final int INTER_FILTER_SCALE = 1 << INTER_FILTER_BITS;

    /// The supported AV1 fractional phases for fixed interpolation filters.
    private static final int INTER_FILTER_PHASES = 16;

    /// The number of coefficients in one normative super-resolution filter.
    private static final int SUPERRES_FILTER_TAP_COUNT = 8;

    /// The AV1 normative super-resolution filter normalization shift.
    private static final int SUPERRES_FILTER_BITS = 7;

    /// The number of AV1 super-resolution fractional phases.
    private static final int SUPERRES_SUBPEL_BITS = 6;

    /// The mask for one AV1 super-resolution fractional phase.
    private static final int SUPERRES_SUBPEL_MASK = (1 << SUPERRES_SUBPEL_BITS) - 1;

    /// The fixed-point precision used by AV1 super-resolution coordinate stepping.
    private static final int SUPERRES_SCALE_SUBPEL_BITS = 14;

    /// The mask for one AV1 super-resolution fixed-point coordinate.
    private static final int SUPERRES_SCALE_SUBPEL_MASK = (1 << SUPERRES_SCALE_SUBPEL_BITS) - 1;

    /// The extra fixed-point bits above the normative super-resolution phase precision.
    private static final int SUPERRES_SCALE_EXTRA_BITS = SUPERRES_SCALE_SUBPEL_BITS - SUPERRES_SUBPEL_BITS;

    /// The AV1 half-phase offset used when deriving the first super-resolution sample position.
    private static final int SUPERRES_SCALE_EXTRA_OFFSET = 1 << (SUPERRES_SCALE_EXTRA_BITS - 1);

    /// The signed source-sample offset of the first normative super-resolution tap.
    private static final int SUPERRES_FILTER_START_OFFSET = SUPERRES_FILTER_TAP_COUNT / 2 - 1;

    /// The default AV1 regular 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int[][] REGULAR_SUBPEL_FILTERS = {
            {0, 1, -3, 63, 4, -1, 0, 0},
            {0, 1, -5, 61, 9, -2, 0, 0},
            {0, 1, -6, 58, 14, -4, 1, 0},
            {0, 1, -7, 55, 19, -5, 1, 0},
            {0, 1, -7, 51, 24, -6, 1, 0},
            {0, 1, -8, 47, 29, -6, 1, 0},
            {0, 1, -7, 42, 33, -6, 1, 0},
            {0, 1, -7, 38, 38, -7, 1, 0},
            {0, 1, -6, 33, 42, -7, 1, 0},
            {0, 1, -6, 29, 47, -8, 1, 0},
            {0, 1, -6, 24, 51, -7, 1, 0},
            {0, 1, -5, 19, 55, -7, 1, 0},
            {0, 1, -4, 14, 58, -6, 1, 0},
            {0, 0, -2, 9, 61, -5, 1, 0},
            {0, 0, -1, 4, 63, -3, 1, 0}
    };

    /// The default AV1 smooth 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int[][] SMOOTH_SUBPEL_FILTERS = {
            {0, 1, 14, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, -1, 8, 27, 24, 6, 0, 0},
            {0, -1, 7, 26, 26, 7, -1, 0},
            {0, 0, 6, 24, 27, 8, -1, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 14, 1, 0}
    };

    /// The default AV1 sharp 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int[][] SHARP_SUBPEL_FILTERS = {
            {-1, 1, -3, 63, 4, -1, 1, 0},
            {-1, 3, -6, 62, 8, -3, 2, -1},
            {-1, 4, -9, 60, 13, -5, 3, -1},
            {-2, 5, -11, 58, 19, -7, 3, -1},
            {-2, 5, -11, 54, 24, -9, 4, -1},
            {-2, 5, -12, 50, 30, -10, 4, -1},
            {-2, 5, -12, 45, 35, -11, 5, -1},
            {-2, 6, -12, 40, 40, -12, 6, -2},
            {-1, 5, -11, 35, 45, -12, 5, -2},
            {-1, 4, -10, 30, 50, -12, 5, -2},
            {-1, 4, -9, 24, 54, -11, 5, -2},
            {-1, 3, -7, 19, 58, -11, 5, -2},
            {-1, 3, -5, 13, 60, -9, 4, -1},
            {-1, 2, -3, 8, 62, -6, 3, -1},
            {0, 1, -1, 4, 63, -3, 1, -1}
    };

    /// The reduced-width AV1 regular 8-tap subpel filters used when the sampled axis is at most
    /// four samples wide.
    private static final int[][] SMALL_REGULAR_SUBPEL_FILTERS = {
            {0, 0, -2, 63, 4, -1, 0, 0},
            {0, 0, -4, 61, 9, -2, 0, 0},
            {0, 0, -5, 58, 14, -3, 0, 0},
            {0, 0, -6, 55, 19, -4, 0, 0},
            {0, 0, -6, 51, 24, -5, 0, 0},
            {0, 0, -7, 47, 29, -5, 0, 0},
            {0, 0, -6, 42, 33, -5, 0, 0},
            {0, 0, -6, 38, 38, -6, 0, 0},
            {0, 0, -5, 33, 42, -6, 0, 0},
            {0, 0, -5, 29, 47, -7, 0, 0},
            {0, 0, -5, 24, 51, -6, 0, 0},
            {0, 0, -4, 19, 55, -6, 0, 0},
            {0, 0, -3, 14, 58, -5, 0, 0},
            {0, 0, -2, 9, 61, -4, 0, 0},
            {0, 0, -1, 4, 63, -2, 0, 0}
    };

    /// The reduced-width AV1 smooth 8-tap subpel filters used when the sampled axis is at most
    /// four samples wide.
    private static final int[][] SMALL_SMOOTH_SUBPEL_FILTERS = {
            {0, 0, 15, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, 0, 7, 27, 24, 6, 0, 0},
            {0, 0, 6, 26, 26, 6, 0, 0},
            {0, 0, 6, 24, 27, 7, 0, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 15, 0, 0}
    };

    /// The AV1 normative 64-phase horizontal super-resolution filters.
    private static final int[][] SUPERRES_FILTERS = {
            {0, 0, 0, 128, 0, 0, 0, 0},
            {0, 0, -1, 128, 2, -1, 0, 0},
            {0, 1, -3, 127, 4, -2, 1, 0},
            {0, 1, -4, 127, 6, -3, 1, 0},
            {0, 2, -6, 126, 8, -3, 1, 0},
            {0, 2, -7, 125, 11, -4, 1, 0},
            {-1, 2, -8, 125, 13, -5, 2, 0},
            {-1, 3, -9, 124, 15, -6, 2, 0},
            {-1, 3, -10, 123, 18, -6, 2, -1},
            {-1, 3, -11, 122, 20, -7, 3, -1},
            {-1, 4, -12, 121, 22, -8, 3, -1},
            {-1, 4, -13, 120, 25, -9, 3, -1},
            {-1, 4, -14, 118, 28, -9, 3, -1},
            {-1, 4, -15, 117, 30, -10, 4, -1},
            {-1, 5, -16, 116, 32, -11, 4, -1},
            {-1, 5, -16, 114, 35, -12, 4, -1},
            {-1, 5, -17, 112, 38, -12, 4, -1},
            {-1, 5, -18, 111, 40, -13, 5, -1},
            {-1, 5, -18, 109, 43, -14, 5, -1},
            {-1, 6, -19, 107, 45, -14, 5, -1},
            {-1, 6, -19, 105, 48, -15, 5, -1},
            {-1, 6, -19, 103, 51, -16, 5, -1},
            {-1, 6, -20, 101, 53, -16, 6, -1},
            {-1, 6, -20, 99, 56, -17, 6, -1},
            {-1, 6, -20, 97, 58, -17, 6, -1},
            {-1, 6, -20, 95, 61, -18, 6, -1},
            {-2, 7, -20, 93, 64, -18, 6, -2},
            {-2, 7, -20, 91, 66, -19, 6, -1},
            {-2, 7, -20, 88, 69, -19, 6, -1},
            {-2, 7, -20, 86, 71, -19, 6, -1},
            {-2, 7, -20, 84, 74, -20, 7, -2},
            {-2, 7, -20, 81, 76, -20, 7, -1},
            {-2, 7, -20, 79, 79, -20, 7, -2},
            {-1, 7, -20, 76, 81, -20, 7, -2},
            {-2, 7, -20, 74, 84, -20, 7, -2},
            {-1, 6, -19, 71, 86, -20, 7, -2},
            {-1, 6, -19, 69, 88, -20, 7, -2},
            {-1, 6, -19, 66, 91, -20, 7, -2},
            {-2, 6, -18, 64, 93, -20, 7, -2},
            {-1, 6, -18, 61, 95, -20, 6, -1},
            {-1, 6, -17, 58, 97, -20, 6, -1},
            {-1, 6, -17, 56, 99, -20, 6, -1},
            {-1, 6, -16, 53, 101, -20, 6, -1},
            {-1, 5, -16, 51, 103, -19, 6, -1},
            {-1, 5, -15, 48, 105, -19, 6, -1},
            {-1, 5, -14, 45, 107, -19, 6, -1},
            {-1, 5, -14, 43, 109, -18, 5, -1},
            {-1, 5, -13, 40, 111, -18, 5, -1},
            {-1, 4, -12, 38, 112, -17, 5, -1},
            {-1, 4, -12, 35, 114, -16, 5, -1},
            {-1, 4, -11, 32, 116, -16, 5, -1},
            {-1, 4, -10, 30, 117, -15, 4, -1},
            {-1, 3, -9, 28, 118, -14, 4, -1},
            {-1, 3, -9, 25, 120, -13, 4, -1},
            {-1, 3, -8, 22, 121, -12, 4, -1},
            {-1, 3, -7, 20, 122, -11, 3, -1},
            {-1, 2, -6, 18, 123, -10, 3, -1},
            {0, 2, -6, 15, 124, -9, 3, -1},
            {0, 2, -5, 13, 125, -8, 2, -1},
            {0, 1, -4, 11, 125, -7, 2, 0},
            {0, 1, -3, 8, 126, -6, 2, 0},
            {0, 1, -3, 6, 127, -4, 1, 0},
            {0, 1, -2, 4, 127, -3, 1, 0},
            {0, 0, -1, 2, 128, -1, 0, 0}
    };

    /// Reconstructs one supported structural frame result into decoded planes.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @return one decoded-plane snapshot
    public DecodedPlanes reconstruct(FrameSyntaxDecodeResult syntaxDecodeResult) {
        return reconstruct(syntaxDecodeResult, new ReferenceSurfaceSnapshot[0]);
    }

    /// Reconstructs one supported structural frame result into decoded planes using the supplied
    /// stored reference surfaces for inter prediction.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @return one decoded-plane snapshot
    public DecodedPlanes reconstruct(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        FrameSyntaxDecodeResult checkedSyntaxDecodeResult = Objects.requireNonNull(syntaxDecodeResult, "syntaxDecodeResult");
        @Nullable ReferenceSurfaceSnapshot[] checkedReferenceSurfaceSnapshots =
                Objects.requireNonNull(referenceSurfaceSnapshots, "referenceSurfaceSnapshots");
        FrameAssembly assembly = checkedSyntaxDecodeResult.assembly();
        SequenceHeader sequenceHeader = assembly.sequenceHeader();
        FrameHeader frameHeader = assembly.frameHeader();
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        PixelFormat pixelFormat = sequenceHeader.colorConfig().pixelFormat();

        requireSupportedSequence(sequenceHeader, frameHeader, checkedSyntaxDecodeResult);

        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(
                frameSize.codedWidth(),
                frameSize.height(),
                sequenceHeader.colorConfig().bitDepth()
        );
        @Nullable MutablePlaneBuffer chromaUPlane = createChromaPlane(pixelFormat, frameSize, sequenceHeader.colorConfig().bitDepth());
        @Nullable MutablePlaneBuffer chromaVPlane = createChromaPlane(pixelFormat, frameSize, sequenceHeader.colorConfig().bitDepth());

        for (TilePartitionTreeReader.Node[] tileRoots : checkedSyntaxDecodeResult.tileRoots()) {
            for (TilePartitionTreeReader.Node root : tileRoots) {
                reconstructNode(
                        root,
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        pixelFormat,
                        frameHeader,
                        checkedReferenceSurfaceSnapshots
                );
            }
        }

        DecodedPlane decodedLumaPlane = lumaPlane.toDecodedPlane();
        @Nullable DecodedPlane decodedChromaUPlane = chromaUPlane != null ? chromaUPlane.toDecodedPlane() : null;
        @Nullable DecodedPlane decodedChromaVPlane = chromaVPlane != null ? chromaVPlane.toDecodedPlane() : null;

        if (frameHeader.superResolution().enabled()) {
            return applySuperResolution(
                    sequenceHeader.colorConfig().bitDepth(),
                    pixelFormat,
                    frameSize,
                    decodedLumaPlane,
                    decodedChromaUPlane,
                    decodedChromaVPlane
            );
        }

        return new DecodedPlanes(
                sequenceHeader.colorConfig().bitDepth(),
                pixelFormat,
                frameSize.codedWidth(),
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                decodedLumaPlane,
                decodedChromaUPlane,
                decodedChromaVPlane
        );
    }

    /// Validates that the sequence and frame stay within the current reconstruction subset.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the active frame header
    /// @param syntaxDecodeResult the structural frame result being reconstructed
    private static void requireSupportedSequence(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        if (sequenceHeader.colorConfig().bitDepth() != 8
                && sequenceHeader.colorConfig().bitDepth() != 10
                && sequenceHeader.colorConfig().bitDepth() != 12) {
            throw new IllegalStateException(
                    "Pixel reconstruction currently requires 8-bit, 10-bit, or 12-bit samples: "
                            + sequenceHeader.colorConfig().bitDepth()
            );
        }
        if (sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I400
                && sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I420
                && sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I422
                && sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I444) {
            throw new IllegalStateException(
                    "Pixel reconstruction currently supports only I400/I420/I422/I444: "
                            + sequenceHeader.colorConfig().pixelFormat()
            );
        }
        if (frameHeader.frameType() != FrameType.KEY && frameHeader.frameType() != FrameType.INTRA) {
            if (frameHeader.frameType() == FrameType.INTER || frameHeader.frameType() == FrameType.SWITCH) {
                return;
            }
            throw new IllegalStateException(
                    "Pixel reconstruction currently supports only key/intra/inter/switch frames: "
                            + frameHeader.frameType()
            );
        }
    }

    /// Creates one mutable chroma plane for the current supported pixel format, or `null`.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameSize the frame dimensions and render size
    /// @param bitDepth the decoded sample bit depth
    /// @return one mutable chroma plane for the current supported pixel format, or `null`
    private static @Nullable MutablePlaneBuffer createChromaPlane(
            PixelFormat pixelFormat,
            FrameHeader.FrameSize frameSize,
            int bitDepth
    ) {
        return switch (pixelFormat) {
            case I400 -> null;
            case I420 -> new MutablePlaneBuffer(
                    (frameSize.codedWidth() + 1) >> 1,
                    (frameSize.height() + 1) >> 1,
                    bitDepth
            );
            case I422 -> new MutablePlaneBuffer(
                    (frameSize.codedWidth() + 1) >> 1,
                    frameSize.height(),
                    bitDepth
            );
            case I444 -> new MutablePlaneBuffer(
                    frameSize.codedWidth(),
                    frameSize.height(),
                    bitDepth
            );
        };
    }

    /// Applies normative AV1 horizontal super-resolution to one reconstructed frame.
    ///
    /// The pass runs after reconstruction has populated coded-domain planes. The returned
    /// `DecodedPlanes` expose the post-super-resolution plane widths as their coded dimensions so
    /// stored reference surfaces live in the AV1 post-super-resolution domain.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameSize the frame dimensions and render size
    /// @param lumaPlane the reconstructed coded-width luma plane
    /// @param chromaUPlane the reconstructed coded-width chroma U plane, or `null`
    /// @param chromaVPlane the reconstructed coded-width chroma V plane, or `null`
    /// @return one decoded-plane snapshot in the post-super-resolution domain
    private static DecodedPlanes applySuperResolution(
            int bitDepth,
            PixelFormat pixelFormat,
            FrameHeader.FrameSize frameSize,
            DecodedPlane lumaPlane,
            @Nullable DecodedPlane chromaUPlane,
            @Nullable DecodedPlane chromaVPlane
    ) {
        int upscaledWidth = frameSize.upscaledWidth();
        DecodedPlane upscaledLumaPlane = upscalePlaneHorizontally(lumaPlane, upscaledWidth, bitDepth);
        @Nullable DecodedPlane upscaledChromaUPlane = chromaUPlane != null
                ? upscalePlaneHorizontally(chromaUPlane, upscaledChromaWidth(pixelFormat, upscaledWidth), bitDepth)
                : null;
        @Nullable DecodedPlane upscaledChromaVPlane = chromaVPlane != null
                ? upscalePlaneHorizontally(chromaVPlane, upscaledChromaWidth(pixelFormat, upscaledWidth), bitDepth)
                : null;
        return new DecodedPlanes(
                bitDepth,
                pixelFormat,
                upscaledWidth,
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                upscaledLumaPlane,
                upscaledChromaUPlane,
                upscaledChromaVPlane
        );
    }

    /// Returns the post-super-resolution chroma width for one pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @param upscaledLumaWidth the post-super-resolution luma width
    /// @return the post-super-resolution chroma width for one pixel format
    private static int upscaledChromaWidth(PixelFormat pixelFormat, int upscaledLumaWidth) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (upscaledLumaWidth + 1) >> 1;
            case I444 -> upscaledLumaWidth;
        };
    }

    /// Upscales one decoded plane horizontally with the normative AV1 super-resolution filter.
    ///
    /// @param plane the decoded plane to upscale
    /// @param targetWidth the post-upscale plane width
    /// @param bitDepth the decoded sample bit depth
    /// @return one horizontally upscaled decoded plane
    private static DecodedPlane upscalePlaneHorizontally(
            DecodedPlane plane,
            int targetWidth,
            int bitDepth
    ) {
        DecodedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
        if (targetWidth <= 0) {
            throw new IllegalArgumentException("targetWidth <= 0: " + targetWidth);
        }
        if (targetWidth == checkedPlane.width()) {
            return checkedPlane;
        }
        if (targetWidth < checkedPlane.width()) {
            throw new IllegalArgumentException(
                    "targetWidth is smaller than the source width: " + targetWidth + " < " + checkedPlane.width()
            );
        }

        short[] sourceSamples = checkedPlane.samples();
        short[] upscaledSamples = new short[targetWidth * checkedPlane.height()];
        int sourceWidth = checkedPlane.width();
        int sourceStride = checkedPlane.stride();
        int maximumSample = (1 << bitDepth) - 1;
        int step = upscaleConvolveStep(sourceWidth, targetWidth);
        int firstPosition = upscaleConvolveInitialPosition(sourceWidth, targetWidth, step);

        for (int y = 0; y < checkedPlane.height(); y++) {
            int sourceRowOffset = y * sourceStride;
            int upscaledRowOffset = y * targetWidth;
            int position = firstPosition;
            for (int x = 0; x < targetWidth; x++) {
                int integerPosition = position >> SUPERRES_SCALE_SUBPEL_BITS;
                int filterIndex = (position & SUPERRES_SCALE_SUBPEL_MASK) >> SUPERRES_SCALE_EXTRA_BITS;
                int[] filter = SUPERRES_FILTERS[filterIndex];
                long sum = 0;
                for (int tap = 0; tap < SUPERRES_FILTER_TAP_COUNT; tap++) {
                    int sourceX = clamp(
                            integerPosition + tap - SUPERRES_FILTER_START_OFFSET,
                            0,
                            sourceWidth - 1
                    );
                    int sourceSample = sourceSamples[sourceRowOffset + sourceX] & 0xFFFF;
                    sum += (long) sourceSample * filter[tap];
                }
                int filteredSample = roundShiftSigned(sum, SUPERRES_FILTER_BITS);
                upscaledSamples[upscaledRowOffset + x] = (short) clamp(filteredSample, 0, maximumSample);
                position += step;
            }
        }

        return new DecodedPlane(targetWidth, checkedPlane.height(), targetWidth, upscaledSamples);
    }

    /// Returns the AV1 super-resolution fixed-point step for one source and target width.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @return the fixed-point horizontal step
    private static int upscaleConvolveStep(int sourceWidth, int targetWidth) {
        return (int) ((((long) sourceWidth << SUPERRES_SCALE_SUBPEL_BITS) + (targetWidth >> 1)) / targetWidth);
    }

    /// Returns the AV1 super-resolution fixed-point position for the first output sample.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @param step the fixed-point horizontal step
    /// @return the initial fixed-point horizontal position
    private static int upscaleConvolveInitialPosition(int sourceWidth, int targetWidth, int step) {
        long error = (long) targetWidth * step - ((long) sourceWidth << SUPERRES_SCALE_SUBPEL_BITS);
        int position = (int) (((-((long) targetWidth - sourceWidth) << (SUPERRES_SCALE_SUBPEL_BITS - 1))
                + (targetWidth >> 1)) / targetWidth)
                + SUPERRES_SCALE_EXTRA_OFFSET
                - (int) (error >> 1);
        return position & SUPERRES_SCALE_SUBPEL_MASK;
    }

    /// Recursively reconstructs one partition-tree node.
    ///
    /// @param node the partition-tree node to reconstruct
    /// @param lumaPlane the mutable luma plane
    /// @param chromaUPlane the mutable chroma U plane, or `null`
    /// @param chromaVPlane the mutable chroma V plane, or `null`
    /// @param pixelFormat the active decoded chroma layout
    private static void reconstructNode(
            TilePartitionTreeReader.Node node,
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            reconstructLeaf(
                    leafNode,
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    pixelFormat,
                    frameHeader,
                    referenceSurfaceSnapshots
            );
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            reconstructNode(
                    child,
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    pixelFormat,
                    frameHeader,
                    referenceSurfaceSnapshots
            );
        }
    }

    /// Reconstructs one supported partition-tree leaf.
    ///
    /// @param leafNode the partition-tree leaf to reconstruct
    /// @param lumaPlane the mutable luma plane
    /// @param chromaUPlane the mutable chroma U plane, or `null`
    /// @param chromaVPlane the mutable chroma V plane, or `null`
    /// @param pixelFormat the active decoded chroma layout
    private static void reconstructLeaf(
            TilePartitionTreeReader.LeafNode leafNode,
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        TileBlockHeaderReader.BlockHeader header = leafNode.header();
        TransformLayout transformLayout = leafNode.transformLayout();
        ResidualLayout residualLayout = leafNode.residualLayout();
        requireSupportedLeaf(
                header,
                transformLayout,
                residualLayout,
                pixelFormat,
                lumaPlane.bitDepth(),
                frameHeader,
                referenceSurfaceSnapshots
        );

        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();

        if (header.useIntrabc()) {
            reconstructIntrabcPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    pixelFormat
            );
        } else if (header.intra()) {
            if (header.yPaletteSize() != 0) {
                reconstructLumaPalette(
                        lumaPlane,
                        header,
                        lumaX,
                        lumaY,
                        visibleLumaWidth,
                        visibleLumaHeight
                );
            } else if (header.filterIntraMode() != null) {
                IntraPredictor.predictFilterIntraLuma(
                        lumaPlane,
                        lumaX,
                        lumaY,
                        visibleLumaWidth,
                        visibleLumaHeight,
                        header.filterIntraMode()
                );
            } else {
                IntraPredictor.predictLuma(
                        lumaPlane,
                        lumaX,
                        lumaY,
                        visibleLumaWidth,
                        visibleLumaHeight,
                        Objects.requireNonNull(header.yMode(), "header.yMode()"),
                        header.yAngle()
                );
            }
        } else {
            reconstructInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    pixelFormat,
                    frameHeader,
                    referenceSurfaceSnapshots
            );
        }

        reconstructLumaResiduals(lumaPlane, residualLayout, header, frameHeader);

        if (header.hasChroma() && chromaUPlane != null && chromaVPlane != null) {
            int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
            int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
            int chromaX = lumaX >> chromaSubsamplingX;
            int chromaY = lumaY >> chromaSubsamplingY;
            int visibleChromaWidth = chromaDimension(visibleLumaWidth, chromaSubsamplingX);
            int visibleChromaHeight = chromaDimension(visibleLumaHeight, chromaSubsamplingY);
            if (header.intra()) {
                if (header.uvPaletteSize() != 0) {
                    reconstructChromaPalette(
                            chromaUPlane,
                            chromaVPlane,
                            header,
                            pixelFormat,
                            visibleChromaWidth,
                            visibleChromaHeight
                    );
                } else if (header.uvMode() == UvIntraPredictionMode.CFL) {
                    IntraPredictor.predictChromaCfl(
                            chromaUPlane,
                            lumaPlane,
                            chromaX,
                            chromaY,
                            lumaX,
                            lumaY,
                            visibleChromaWidth,
                            visibleChromaHeight,
                            header.cflAlphaU(),
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    );
                    IntraPredictor.predictChromaCfl(
                            chromaVPlane,
                            lumaPlane,
                            chromaX,
                            chromaY,
                            lumaX,
                            lumaY,
                            visibleChromaWidth,
                            visibleChromaHeight,
                            header.cflAlphaV(),
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    );
                } else {
                    IntraPredictor.predictChroma(
                            chromaUPlane,
                            chromaX,
                            chromaY,
                            visibleChromaWidth,
                            visibleChromaHeight,
                            Objects.requireNonNull(header.uvMode(), "header.uvMode()"),
                            header.uvAngle()
                    );
                    IntraPredictor.predictChroma(
                            chromaVPlane,
                            chromaX,
                            chromaY,
                            visibleChromaWidth,
                            visibleChromaHeight,
                            Objects.requireNonNull(header.uvMode(), "header.uvMode()"),
                            header.uvAngle()
                    );
                }
            }

            reconstructChromaResiduals(chromaUPlane, chromaVPlane, residualLayout, frameHeader, pixelFormat, header.qIndex());
        }
    }

    /// Validates that one partition-tree leaf lies inside the current reconstruction subset.
    ///
    /// @param header the decoded block header
    /// @param transformLayout the decoded block transform layout
    /// @param residualLayout the decoded block residual layout
    /// @param pixelFormat the active decoded chroma layout
    /// @param bitDepth the decoded sample bit depth of the current frame
    private static void requireSupportedLeaf(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            ResidualLayout residualLayout,
            PixelFormat pixelFormat,
            int bitDepth,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        if (header.useIntrabc()) {
            if (header.motionVector0() == null || !header.motionVector0().resolved()) {
                throw new IllegalStateException("intrabc reconstruction requires one resolved motion vector");
            }
            if (header.compoundReference()) {
                throw new IllegalStateException("intrabc reconstruction does not support compound references");
            }
            requireSupportedIntrabcMotionVector(
                    header.motionVector0().vector(),
                    pixelFormat,
                    header.hasChroma()
            );
        }
        if (header.hasChroma() && pixelFormat == PixelFormat.I400) {
            throw new IllegalStateException("Monochrome reconstruction encountered a block with chroma samples");
        }
        if (!header.hasChroma() && residualLayout.hasChromaUnits()) {
            throw new IllegalStateException("Chroma residuals require a block with chroma samples");
        }
        if (header.hasChroma()) {
            if (header.intra() && header.uvPaletteSize() == 0 && header.uvMode() == null) {
                throw new IllegalStateException("Chroma reconstruction requires uvMode");
            }
            if (residualLayout.hasChromaUnits() && transformLayout.chromaTransformSize() == null) {
                throw new IllegalStateException("Chroma residuals require a chroma transform size");
            }
        }
        if (transformLayout.visibleWidth4() <= 0 || transformLayout.visibleHeight4() <= 0) {
            throw new IllegalStateException("Empty transform layout is not reconstructable");
        }
        if (!header.intra() && !header.useIntrabc()) {
            if (header.motionVector0() == null) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (!header.motionVector0().resolved()) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (header.compoundReference()) {
                if (header.motionVector1() == null) {
                    throw new IllegalStateException("Compound inter reconstruction requires one resolved secondary motion vector");
                }
                if (!header.motionVector1().resolved()) {
                    throw new IllegalStateException("Compound inter reconstruction requires one resolved secondary motion vector");
                }
            }
            if (header.yPaletteSize() != 0 || header.uvPaletteSize() != 0) {
                throw new IllegalStateException("Inter palette reconstruction is not implemented yet");
            }
            requireReferenceSurfaceSnapshot(
                    referenceSurfaceSnapshots,
                    frameHeader,
                    pixelFormat,
                    bitDepth,
                    header.referenceFrame0()
            );
            if (header.compoundReference()) {
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        pixelFormat,
                        bitDepth,
                        header.referenceFrame1()
                );
            }
            requireSupportedInterMotionVector(
                    header.motionVector0().vector(),
                    pixelFormat,
                    header.hasChroma(),
                    resolveHorizontalInterpolationFilter(header, frameHeader),
                    resolveVerticalInterpolationFilter(header, frameHeader)
            );
            if (header.compoundReference()) {
                requireSupportedInterMotionVector(
                        Objects.requireNonNull(header.motionVector1(), "header.motionVector1()").vector(),
                        pixelFormat,
                        header.hasChroma(),
                        resolveHorizontalInterpolationFilter(header, frameHeader),
                        resolveVerticalInterpolationFilter(header, frameHeader)
                );
            }
        }
        for (TransformResidualUnit residualUnit : residualLayout.lumaUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current modeled DCT_DCT luma units: "
                                + residualUnit.size()
                );
            }
        }
        for (TransformResidualUnit residualUnit : residualLayout.chromaUUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current modeled DCT_DCT chroma units: "
                                + residualUnit.size()
                );
            }
        }
        for (TransformResidualUnit residualUnit : residualLayout.chromaVUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current modeled DCT_DCT chroma units: "
                                + residualUnit.size()
                );
            }
        }
    }

    /// Reconstructs the currently supported inter prediction subset.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void reconstructInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        if (header.compoundReference()) {
            reconstructCompoundInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    pixelFormat,
                    frameHeader,
                    referenceSurfaceSnapshots
            );
        } else {
            reconstructSingleReferenceInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    pixelFormat,
                    frameHeader,
                    referenceSurfaceSnapshots
            );
        }
    }

    /// Reconstructs the current minimal `intrabc` subset by sampling already reconstructed samples
    /// from the current frame with one resolved motion vector.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the intrabc state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    private static void reconstructIntrabcPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            PixelFormat pixelFormat
    ) {
        MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        DecodedPlane lumaSnapshot = lumaPlane.toDecodedPlane();
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        reconstructInterPlanePrediction(
                lumaPlane,
                lumaSnapshot,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                4,
                4,
                visibleLumaWidth,
                visibleLumaHeight,
                FrameHeader.InterpolationFilter.BILINEAR,
                FrameHeader.InterpolationFilter.BILINEAR
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
        int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
        int chromaX = lumaX >> chromaSubsamplingX;
        int chromaY = lumaY >> chromaSubsamplingY;
        int visibleChromaWidth = chromaDimension(visibleLumaWidth, chromaSubsamplingX);
        int visibleChromaHeight = chromaDimension(visibleLumaHeight, chromaSubsamplingY);
        int chromaDenominatorX = 4 << chromaSubsamplingX;
        int chromaDenominatorY = 4 << chromaSubsamplingY;
        DecodedPlane chromaUSnapshot = chromaUPlane.toDecodedPlane();
        DecodedPlane chromaVSnapshot = chromaVPlane.toDecodedPlane();
        reconstructInterPlanePrediction(
                chromaUPlane,
                chromaUSnapshot,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                FrameHeader.InterpolationFilter.BILINEAR,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        reconstructInterPlanePrediction(
                chromaVPlane,
                chromaVSnapshot,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                FrameHeader.InterpolationFilter.BILINEAR,
                FrameHeader.InterpolationFilter.BILINEAR
        );
    }

    /// Reconstructs the currently supported single-reference inter prediction subset.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void reconstructSingleReferenceInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        pixelFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame0()
                );
        DecodedPlanes referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        reconstructInterPlanePrediction(
                lumaPlane,
                referencePlanes.lumaPlane(),
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                4,
                4,
                visibleLumaWidth,
                visibleLumaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
        int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
        int chromaX = lumaX >> chromaSubsamplingX;
        int chromaY = lumaY >> chromaSubsamplingY;
        int visibleChromaWidth = chromaDimension(visibleLumaWidth, chromaSubsamplingX);
        int visibleChromaHeight = chromaDimension(visibleLumaHeight, chromaSubsamplingY);
        int chromaDenominatorX = 4 << chromaSubsamplingX;
        int chromaDenominatorY = 4 << chromaSubsamplingY;

        reconstructInterPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes.chromaUPlane(), "referencePlanes.chromaUPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
        reconstructInterPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes.chromaVPlane(), "referencePlanes.chromaVPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnQuarterPel(),
                motionVector.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
    }

    /// Reconstructs the currently supported compound-reference inter prediction subset by averaging
    /// the two predicted reference surfaces after each one has been sampled with the current
    /// integer-copy or fixed-filter subpel path.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void reconstructCompoundInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        pixelFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame0()
                );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 =
                requireReferenceSurfaceSnapshot(
                        referenceSurfaceSnapshots,
                        frameHeader,
                        pixelFormat,
                        lumaPlane.bitDepth(),
                        header.referenceFrame1()
                );
        DecodedPlanes referencePlanes0 = referenceSurfaceSnapshot0.decodedPlanes();
        DecodedPlanes referencePlanes1 = referenceSurfaceSnapshot1.decodedPlanes();
        MotionVector motionVector0 = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        MotionVector motionVector1 = Objects.requireNonNull(header.motionVector1(), "header.motionVector1()").vector();
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();

        reconstructCompoundInterPlanePrediction(
                lumaPlane,
                referencePlanes0.lumaPlane(),
                referencePlanes1.lumaPlane(),
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                motionVector0.columnQuarterPel(),
                motionVector0.rowQuarterPel(),
                motionVector1.columnQuarterPel(),
                motionVector1.rowQuarterPel(),
                4,
                4,
                visibleLumaWidth,
                visibleLumaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return;
        }

        int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
        int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
        int chromaX = lumaX >> chromaSubsamplingX;
        int chromaY = lumaY >> chromaSubsamplingY;
        int visibleChromaWidth = chromaDimension(visibleLumaWidth, chromaSubsamplingX);
        int visibleChromaHeight = chromaDimension(visibleLumaHeight, chromaSubsamplingY);
        int chromaDenominatorX = 4 << chromaSubsamplingX;
        int chromaDenominatorY = 4 << chromaSubsamplingY;

        reconstructCompoundInterPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes0.chromaUPlane(), "referencePlanes0.chromaUPlane()"),
                Objects.requireNonNull(referencePlanes1.chromaUPlane(), "referencePlanes1.chromaUPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector0.columnQuarterPel(),
                motionVector0.rowQuarterPel(),
                motionVector1.columnQuarterPel(),
                motionVector1.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
        reconstructCompoundInterPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes0.chromaVPlane(), "referencePlanes0.chromaVPlane()"),
                Objects.requireNonNull(referencePlanes1.chromaVPlane(), "referencePlanes1.chromaVPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector0.columnQuarterPel(),
                motionVector0.rowQuarterPel(),
                motionVector1.columnQuarterPel(),
                motionVector1.rowQuarterPel(),
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
    }

    /// Reconstructs one inter-predicted plane using either integer-copy or the current fixed AV1
    /// subpel filter depending on the supplied motion-vector alignment.
    ///
    /// When the stored reference plane already lives in a different post-super-resolution domain,
    /// the current implementation first maps destination-plane sample coordinates into the
    /// reference-plane domain with one deterministic linear endpoint-preserving transform, then
    /// applies the existing integer-copy or fixed-filter sampling path.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    /// @param sourceOffsetQuarterPelX the signed horizontal motion-vector component in luma quarter-pel units
    /// @param sourceOffsetQuarterPelY the signed vertical motion-vector component in luma quarter-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma quarter-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma quarter-pel units
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    private static void reconstructInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            DecodedPlane referencePlane,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetQuarterPelX,
            int sourceOffsetQuarterPelY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode
    ) {
        if (destinationPlane.width() == referencePlane.width()
                && destinationPlane.height() == referencePlane.height()
                && Math.floorMod(sourceOffsetQuarterPelX, denominatorX) == 0
                && Math.floorMod(sourceOffsetQuarterPelY, denominatorY) == 0) {
            copyReferencePlaneBlock(
                    destinationPlane,
                    referencePlane,
                    destinationX,
                    destinationY,
                    destinationX + sourceOffsetQuarterPelX / denominatorX,
                    destinationY + sourceOffsetQuarterPelY / denominatorY,
                    width,
                    height
            );
            return;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        sampleInterPlaneValue(
                                referencePlane,
                                destinationX + x,
                                destinationY + y,
                                destinationPlane.width(),
                                destinationPlane.height(),
                                sourceOffsetQuarterPelX,
                                sourceOffsetQuarterPelY,
                                denominatorX,
                                denominatorY,
                                widthForFilterSelection,
                                heightForFilterSelection,
                                horizontalFilterMode,
                                verticalFilterMode,
                                destinationPlane.maxSampleValue()
                        )
                );
            }
        }
    }

    /// Reconstructs one compound inter-predicted plane by averaging two independently predicted
    /// reference planes.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane0 the primary immutable reference plane
    /// @param referencePlane1 the secondary immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    /// @param sourceOffsetQuarterPelX0 the primary signed horizontal motion-vector component in luma quarter-pel units
    /// @param sourceOffsetQuarterPelY0 the primary signed vertical motion-vector component in luma quarter-pel units
    /// @param sourceOffsetQuarterPelX1 the secondary signed horizontal motion-vector component in luma quarter-pel units
    /// @param sourceOffsetQuarterPelY1 the secondary signed vertical motion-vector component in luma quarter-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma quarter-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma quarter-pel units
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    private static void reconstructCompoundInterPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            DecodedPlane referencePlane0,
            DecodedPlane referencePlane1,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceOffsetQuarterPelX0,
            int sourceOffsetQuarterPelY0,
            int sourceOffsetQuarterPelX1,
            int sourceOffsetQuarterPelY1,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sample0 = sampleInterPlaneValue(
                        referencePlane0,
                        destinationX + x,
                        destinationY + y,
                        destinationPlane.width(),
                        destinationPlane.height(),
                        sourceOffsetQuarterPelX0,
                        sourceOffsetQuarterPelY0,
                        denominatorX,
                        denominatorY,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        destinationPlane.maxSampleValue()
                );
                int sample1 = sampleInterPlaneValue(
                        referencePlane1,
                        destinationX + x,
                        destinationY + y,
                        destinationPlane.width(),
                        destinationPlane.height(),
                        sourceOffsetQuarterPelX1,
                        sourceOffsetQuarterPelY1,
                        denominatorX,
                        denominatorY,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        destinationPlane.maxSampleValue()
                );
                destinationPlane.setSample(destinationX + x, destinationY + y, averageCompoundSamples(sample0, sample1));
            }
        }
    }

    /// Returns one inter-predicted plane sample using either integer-copy or the current fixed AV1
    /// subpel filter depending on the supplied motion-vector alignment.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param sourceOffsetQuarterPelX the signed horizontal motion-vector component in luma quarter-pel units
    /// @param sourceOffsetQuarterPelY the signed vertical motion-vector component in luma quarter-pel units
    /// @param denominatorX the plane-local horizontal denominator expressed in luma quarter-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma quarter-pel units
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    /// @return one predicted plane sample
    private static int sampleInterPlaneValue(
            DecodedPlane referencePlane,
            int destinationX,
            int destinationY,
            int destinationPlaneWidth,
            int destinationPlaneHeight,
            int sourceOffsetQuarterPelX,
            int sourceOffsetQuarterPelY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        int sourceNumeratorX = mapDestinationCoordinateToReferenceNumerator(
                destinationX,
                destinationPlaneWidth,
                referencePlane.width(),
                denominatorX
        ) + sourceOffsetQuarterPelX;
        int sourceNumeratorY = mapDestinationCoordinateToReferenceNumerator(
                destinationY,
                destinationPlaneHeight,
                referencePlane.height(),
                denominatorY
        ) + sourceOffsetQuarterPelY;
        if (Math.floorMod(sourceNumeratorX, denominatorX) == 0
                && Math.floorMod(sourceNumeratorY, denominatorY) == 0) {
            return referencePlane.sample(
                    clamp(sourceNumeratorX / denominatorX, 0, referencePlane.width() - 1),
                    clamp(sourceNumeratorY / denominatorY, 0, referencePlane.height() - 1)
            );
        }
        return filteredInterpolateAt(
                referencePlane,
                sourceNumeratorX,
                sourceNumeratorY,
                denominatorX,
                denominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode,
                maximumSampleValue
        );
    }

    /// Copies one rectangular reference-plane footprint into the destination plane with edge
    /// extension.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param sourceX the zero-based horizontal source coordinate
    /// @param sourceY the zero-based vertical source coordinate
    /// @param width the copied width in samples
    /// @param height the copied height in samples
    private static void copyReferencePlaneBlock(
            MutablePlaneBuffer destinationPlane,
            DecodedPlane referencePlane,
            int destinationX,
            int destinationY,
            int sourceX,
            int sourceY,
            int width,
            int height
    ) {
        for (int y = 0; y < height; y++) {
            int clampedSourceY = clamp(sourceY + y, 0, referencePlane.height() - 1);
            for (int x = 0; x < width; x++) {
                int clampedSourceX = clamp(sourceX + x, 0, referencePlane.width() - 1);
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        referencePlane.sample(clampedSourceX, clampedSourceY)
                );
            }
        }
    }

    /// Samples one rectangular reference-plane footprint into the destination plane using the
    /// current fixed AV1 subpel filter with edge extension.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the sampled width in samples
    /// @param height the sampled height in samples
    /// @param sourceNumeratorX the source origin numerator in plane-local sample units
    /// @param sourceNumeratorY the source origin numerator in plane-local sample units
    /// @param denominatorX the horizontal plane-local denominator
    /// @param denominatorY the vertical plane-local denominator
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    private static void filteredReferencePlaneBlock(
            MutablePlaneBuffer destinationPlane,
            DecodedPlane referencePlane,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        filteredInterpolateAt(
                                referencePlane,
                                sourceNumeratorX + x * denominatorX,
                                sourceNumeratorY + y * denominatorY,
                                denominatorX,
                                denominatorY,
                                widthForFilterSelection,
                                heightForFilterSelection,
                                horizontalFilterMode,
                                verticalFilterMode,
                                maximumSampleValue
                        )
                );
            }
        }
    }

    /// Returns one fixed-filter interpolated unsigned sample at the supplied plane-local source
    /// numerator coordinates.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @param widthForFilterSelection the sampled block width in pixels
    /// @param heightForFilterSelection the sampled block height in pixels
    /// @param horizontalFilterMode the effective horizontal interpolation filter
    /// @param verticalFilterMode the effective vertical interpolation filter
    /// @param maximumSampleValue the maximum legal output sample value for the destination bit depth
    /// @return one fixed-filter interpolated unsigned sample
    private static int filteredInterpolateAt(
            DecodedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            int maximumSampleValue
    ) {
        if (horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                && verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            return bilinearInterpolateAt(referencePlane, sourceNumeratorX, sourceNumeratorY, denominatorX, denominatorY);
        }
        if (!supportsFixedFractionalInterFilter(horizontalFilterMode)
                || !supportsFixedFractionalInterFilter(verticalFilterMode)
                || horizontalFilterMode == FrameHeader.InterpolationFilter.BILINEAR
                || verticalFilterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            throw new IllegalStateException(
                    "Inter reconstruction currently supports fractional motion vectors only with fixed BILINEAR or EIGHT_TAP_* filters"
            );
        }

        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int phaseY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int phaseX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                    clamp(sourceX0, 0, referencePlane.width() - 1),
                    clamp(sourceY0, 0, referencePlane.height() - 1)
            );
        }

        @Nullable int[] horizontalFilter =
                phaseX == 0 ? null : selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection);
        @Nullable int[] verticalFilter =
                phaseY == 0 ? null : selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection);
        if (verticalFilter == null) {
            long filtered = horizontalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    Objects.requireNonNull(horizontalFilter, "horizontalFilter")
            );
            return clamp(roundShiftSigned(filtered, INTER_FILTER_BITS), 0, maximumSampleValue);
        }
        if (horizontalFilter == null) {
            long filtered = verticalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    Objects.requireNonNull(verticalFilter, "verticalFilter")
            );
            return clamp(roundShiftSigned(filtered, INTER_FILTER_BITS), 0, maximumSampleValue);
        }

        long[] horizontallyFilteredRows = new long[INTER_FILTER_TAP_COUNT];
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            horizontallyFilteredRows[tapIndex] = horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter);
        }

        long combined = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            combined += (long) Objects.requireNonNull(verticalFilter, "verticalFilter")[tapIndex] * horizontallyFilteredRows[tapIndex];
        }
        return clamp(roundShiftSigned(combined, INTER_FILTER_BITS * 2), 0, maximumSampleValue);
    }

    /// Returns one bilinearly interpolated unsigned sample at the supplied plane-local source
    /// numerator coordinates.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @return one bilinearly interpolated unsigned sample
    private static int bilinearInterpolateAt(
            DecodedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY
    ) {
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionY = Math.floorMod(sourceNumeratorY, denominatorY);
        int clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int fractionX = Math.floorMod(sourceNumeratorX, denominatorX);
        int clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1);
        int clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1);
        return bilinearInterpolate(
                referencePlane.sample(clampedSourceX0, clampedSourceY0),
                referencePlane.sample(clampedSourceX1, clampedSourceY0),
                referencePlane.sample(clampedSourceX0, clampedSourceY1),
                referencePlane.sample(clampedSourceX1, clampedSourceY1),
                fractionX,
                denominatorX,
                fractionY,
                denominatorY
        );
    }

    /// Returns one filtered horizontal interpolation sum before normalization.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX0 the integer horizontal source position
    /// @param sourceY the integer vertical source position
    /// @param filter the selected AV1 subpel filter taps
    /// @return one filtered horizontal interpolation sum before normalization
    private static long horizontalInterpolate(
            DecodedPlane referencePlane,
            int sourceX0,
            int sourceY,
            int[] filter
    ) {
        long filtered = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceX = clamp(sourceX0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.width() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, sourceY);
        }
        return filtered;
    }

    /// Returns one filtered vertical interpolation sum before normalization.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX the integer horizontal source position
    /// @param sourceY0 the integer vertical source position
    /// @param filter the selected AV1 subpel filter taps
    /// @return one filtered vertical interpolation sum before normalization
    private static long verticalInterpolate(
            DecodedPlane referencePlane,
            int sourceX,
            int sourceY0,
            int[] filter
    ) {
        long filtered = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, sourceY);
        }
        return filtered;
    }

    /// Returns the selected AV1 fixed-filter taps for one fractional phase and sampled axis size.
    ///
    /// @param filterMode the requested interpolation filter mode
    /// @param phase the normalized AV1 fractional phase in `[1, 15]`
    /// @param axisSize the sampled axis size in pixels
    /// @return the selected AV1 fixed-filter taps
    private static int[] selectSubpelFilter(
            FrameHeader.InterpolationFilter filterMode,
            int phase,
            int axisSize
    ) {
        if (phase <= 0 || phase >= INTER_FILTER_PHASES) {
            throw new IllegalStateException("AV1 fixed-filter phase out of range: " + phase);
        }
        return switch (filterMode) {
            case EIGHT_TAP_REGULAR -> (axisSize <= 4 ? SMALL_REGULAR_SUBPEL_FILTERS : REGULAR_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SMOOTH -> (axisSize <= 4 ? SMALL_SMOOTH_SUBPEL_FILTERS : SMOOTH_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SHARP -> SHARP_SUBPEL_FILTERS[phase - 1];
            default -> throw new IllegalStateException(
                    "Inter reconstruction currently supports fractional motion vectors only with fixed BILINEAR or EIGHT_TAP_* filters"
            );
        };
    }

    /// Maps one destination-plane sample coordinate into the current reference-plane numerator
    /// domain.
    ///
    /// The mapping preserves both plane endpoints and uses one rounded linear transform before the
    /// destination frame's normative super-resolution pass runs.
    ///
    /// @param destinationCoordinate the zero-based destination-plane coordinate
    /// @param destinationExtent the destination-plane extent in samples
    /// @param referenceExtent the reference-plane extent in samples
    /// @param denominator the plane-local interpolation denominator
    /// @return the mapped source numerator in plane-local sample units
    private static int mapDestinationCoordinateToReferenceNumerator(
            int destinationCoordinate,
            int destinationExtent,
            int referenceExtent,
            int denominator
    ) {
        if (destinationExtent <= 0) {
            throw new IllegalStateException("Destination plane extent must be positive");
        }
        if (referenceExtent <= 0) {
            throw new IllegalStateException("Reference plane extent must be positive");
        }
        if (destinationCoordinate < 0 || destinationCoordinate >= destinationExtent) {
            throw new IllegalStateException("Destination coordinate lies outside the current plane extent");
        }
        if (destinationExtent == referenceExtent) {
            return destinationCoordinate * denominator;
        }
        if (destinationExtent == 1 || referenceExtent == 1) {
            return 0;
        }
        long numerator = (long) destinationCoordinate * (referenceExtent - 1) * denominator;
        long divisor = destinationExtent - 1L;
        return (int) ((numerator + (divisor >> 1)) / divisor);
    }

    /// Returns the normalized AV1 subpel phase for the supplied plane-local fraction.
    ///
    /// @param fraction the plane-local source fraction
    /// @param denominator the plane-local interpolation denominator
    /// @return the normalized AV1 subpel phase in `[0, 15]`
    private static int interpolationPhase(int fraction, int denominator) {
        if (fraction == 0) {
            return 0;
        }
        return Math.multiplyExact(fraction, INTER_FILTER_PHASES) / denominator;
    }

    /// Rounds one signed integer by the requested arithmetic right shift.
    ///
    /// @param value the signed value to round
    /// @param bits the number of low bits to discard
    /// @return the rounded signed value
    private static int roundShiftSigned(long value, int bits) {
        long roundingOffset = 1L << (bits - 1);
        if (value >= 0) {
            return (int) ((value + roundingOffset) >> bits);
        }
        return (int) -(((-value) + roundingOffset) >> bits);
    }

    /// Returns whether one frame-level interpolation filter is currently supported for fractional
    /// inter reconstruction.
    ///
    /// @param filterMode the frame-level interpolation filter mode
    /// @return whether one frame-level interpolation filter is currently supported for fractional inter reconstruction
    private static boolean supportsFixedFractionalInterFilter(FrameHeader.InterpolationFilter filterMode) {
        return filterMode == FrameHeader.InterpolationFilter.BILINEAR
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
                || filterMode == FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
    }

    /// Returns one bilinearly interpolated unsigned sample.
    ///
    /// @param topLeft the top-left source sample
    /// @param topRight the top-right source sample
    /// @param bottomLeft the bottom-left source sample
    /// @param bottomRight the bottom-right source sample
    /// @param fractionX the horizontal source fraction in `[0, denominatorX)`
    /// @param denominatorX the horizontal interpolation denominator
    /// @param fractionY the vertical source fraction in `[0, denominatorY)`
    /// @param denominatorY the vertical interpolation denominator
    /// @return one bilinearly interpolated unsigned sample
    private static int bilinearInterpolate(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            int fractionX,
            int denominatorX,
            int fractionY,
            int denominatorY
    ) {
        int inverseFractionX = denominatorX - fractionX;
        int inverseFractionY = denominatorY - fractionY;
        long denominator = (long) denominatorX * denominatorY;
        long weightedSum = (long) inverseFractionX * inverseFractionY * topLeft
                + (long) fractionX * inverseFractionY * topRight
                + (long) inverseFractionX * fractionY * bottomLeft
                + (long) fractionX * fractionY * bottomRight;
        return (int) ((weightedSum + (denominator >> 1)) / denominator);
    }

    /// Returns the current simple average-compound sample.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @return the current simple average-compound sample
    private static int averageCompoundSamples(int primarySample, int secondarySample) {
        return (primarySample + secondarySample + 1) >> 1;
    }

    /// Resolves the effective horizontal interpolation filter for one inter block.
    ///
    /// @param header the decoded block header that owns the inter state
    /// @param frameHeader the frame header that owns the block
    /// @return the effective horizontal interpolation filter
    private static FrameHeader.InterpolationFilter resolveHorizontalInterpolationFilter(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        if (frameHeader.subpelFilterMode() != FrameHeader.InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode();
        }
        @Nullable FrameHeader.InterpolationFilter interpolationFilter = header.horizontalInterpolationFilter();
        if (interpolationFilter == null) {
            throw new IllegalStateException("Inter reconstruction requires decoded switchable interpolation filters");
        }
        return interpolationFilter;
    }

    /// Resolves the effective vertical interpolation filter for one inter block.
    ///
    /// @param header the decoded block header that owns the inter state
    /// @param frameHeader the frame header that owns the block
    /// @return the effective vertical interpolation filter
    private static FrameHeader.InterpolationFilter resolveVerticalInterpolationFilter(
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        if (frameHeader.subpelFilterMode() != FrameHeader.InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode();
        }
        @Nullable FrameHeader.InterpolationFilter interpolationFilter = header.verticalInterpolationFilter();
        if (interpolationFilter == null) {
            throw new IllegalStateException("Inter reconstruction requires decoded switchable interpolation filters");
        }
        return interpolationFilter;
    }

    /// Returns one compatible stored reference surface for the supplied internal LAST..ALTREF
    /// reference position.
    ///
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param frameHeader the frame header that owns the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param bitDepth the decoded sample bit depth of the current frame
    /// @param referenceFramePosition the internal LAST..ALTREF reference position
    /// @return one compatible stored reference surface for the supplied reference position
    private static ReferenceSurfaceSnapshot requireReferenceSurfaceSnapshot(
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            FrameHeader frameHeader,
            PixelFormat pixelFormat,
            int bitDepth,
            int referenceFramePosition
    ) {
        if (referenceFramePosition < 0 || referenceFramePosition >= 7) {
            throw new IllegalStateException("Inter reconstruction requires one valid reference-frame position");
        }
        int referenceSlot = frameHeader.referenceFrameIndex(referenceFramePosition);
        if (referenceSlot < 0 || referenceSlot >= referenceSurfaceSnapshots.length) {
            throw new IllegalStateException("Inter reconstruction requires one populated stored reference surface");
        }

        @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot = referenceSurfaceSnapshots[referenceSlot];
        if (referenceSurfaceSnapshot == null) {
            throw new IllegalStateException("Inter reconstruction requires one populated stored reference surface");
        }

        DecodedPlanes referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        if (referencePlanes.bitDepth() != bitDepth) {
            throw new IllegalStateException(
                    "Inter reconstruction currently requires one stored reference surface whose bit depth matches the current frame"
            );
        }
        if (referencePlanes.pixelFormat() != pixelFormat) {
            throw new IllegalStateException(
                    "Inter reconstruction currently requires matching reference pixel format: " + pixelFormat
            );
        }
        return referenceSurfaceSnapshot;
    }

    /// Validates that one inter motion vector stays inside the current single-reference
    /// reconstruction subset.
    ///
    /// @param motionVector the inter motion vector chosen for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param hasChroma whether the block carries chroma samples
    /// @param horizontalInterpolationFilter the effective horizontal interpolation filter mode
    /// @param verticalInterpolationFilter the effective vertical interpolation filter mode
    private static void requireSupportedInterMotionVector(
            MotionVector motionVector,
            PixelFormat pixelFormat,
            boolean hasChroma,
            FrameHeader.InterpolationFilter horizontalInterpolationFilter,
            FrameHeader.InterpolationFilter verticalInterpolationFilter
    ) {
        MotionVector checkedMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        boolean lumaFractional = (checkedMotionVector.rowQuarterPel() & 0x03) != 0
                || (checkedMotionVector.columnQuarterPel() & 0x03) != 0;
        if (!hasChroma || pixelFormat == PixelFormat.I400) {
            if (lumaFractional
                    && (!supportsFixedFractionalInterFilter(horizontalInterpolationFilter)
                    || !supportsFixedFractionalInterFilter(verticalInterpolationFilter))) {
                throw new IllegalStateException(
                        "Inter reconstruction currently supports fractional luma motion vectors only with fixed BILINEAR or EIGHT_TAP_* filters"
                );
            }
            return;
        }

        int chromaHorizontalAlignment = 4 << chromaSubsamplingX(pixelFormat);
        int chromaVerticalAlignment = 4 << chromaSubsamplingY(pixelFormat);
        boolean chromaFractional = Math.floorMod(checkedMotionVector.columnQuarterPel(), chromaHorizontalAlignment) != 0
                || Math.floorMod(checkedMotionVector.rowQuarterPel(), chromaVerticalAlignment) != 0;
        if ((lumaFractional || chromaFractional)
                && (!supportsFixedFractionalInterFilter(horizontalInterpolationFilter)
                || !supportsFixedFractionalInterFilter(verticalInterpolationFilter))) {
            throw new IllegalStateException(
                    "Inter reconstruction currently supports fractional motion vectors only with fixed BILINEAR or EIGHT_TAP_* filters for "
                            + pixelFormat
            );
        }
    }

    /// Validates that one `intrabc` motion vector stays inside the current same-frame copy subset.
    ///
    /// The current implementation now uses the same-frame `BILINEAR` subset for luma and chroma,
    /// so any plane-local quarter-pel numerator is accepted as long as the motion vector exists.
    ///
    /// @param motionVector the resolved `intrabc` motion vector chosen for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param hasChroma whether the block carries chroma samples
    private static void requireSupportedIntrabcMotionVector(
            MotionVector motionVector,
            PixelFormat pixelFormat,
            boolean hasChroma
    ) {
        Objects.requireNonNull(motionVector, "motionVector");
    }

    /// Reconstructs one luma palette block directly into the destination plane.
    ///
    /// Palette indices are stored as two packed 4-bit entries per byte in raster order with the
    /// invisible right and bottom edges already replicated by the syntax layer.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param header the decoded block header that owns the luma palette state
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param visibleLumaWidth the exact visible luma width in pixels
    /// @param visibleLumaHeight the exact visible luma height in pixels
    private static void reconstructLumaPalette(
            MutablePlaneBuffer lumaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int lumaX,
            int lumaY,
            int visibleLumaWidth,
            int visibleLumaHeight
    ) {
        reconstructPalettePlane(
                lumaPlane,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                header.size().widthPixels(),
                header.yPaletteColors(),
                header.yPaletteIndices()
        );
    }

    /// Reconstructs one chroma palette block directly into the destination planes.
    ///
    /// The current reconstruction subset mirrors the packed chroma palette-map geometry exposed by
    /// `TileBlockHeaderReader`, then writes only the exact visible chroma footprint into the output
    /// planes for the current `I420`, `I422`, and `I444` palette paths.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param header the decoded block header that owns the chroma palette state
    /// @param visibleChromaWidth the exact visible chroma width in pixels
    /// @param visibleChromaHeight the exact visible chroma height in pixels
    private static void reconstructChromaPalette(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            PixelFormat pixelFormat,
            int visibleChromaWidth,
            int visibleChromaHeight
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
        int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
        int chromaX = header.position().x4() << (2 - chromaSubsamplingX);
        int chromaY = header.position().y4() << (2 - chromaSubsamplingY);
        int fullChromaWidth = chromaSpan4(
                header.position().x4(),
                header.size().width4(),
                chromaSubsamplingX
        ) << 2;
        reconstructPalettePlane(
                chromaUPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                fullChromaWidth,
                header.uPaletteColors(),
                header.uvPaletteIndices()
        );
        reconstructPalettePlane(
                chromaVPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                fullChromaWidth,
                header.vPaletteColors(),
                header.uvPaletteIndices()
        );
    }

    /// Reconstructs one palette-mapped sample plane directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param startX the zero-based horizontal destination coordinate
    /// @param startY the zero-based vertical destination coordinate
    /// @param visibleWidth the visible block width in pixels before destination-frame clipping
    /// @param visibleHeight the visible block height in pixels before destination-frame clipping
    /// @param packedFullWidth the coded palette-map width in pixels used to compute the packed stride
    /// @param paletteColors the decoded palette entries for the destination plane
    /// @param packedIndices the packed palette indices with one 4-bit entry per sample
    private static void reconstructPalettePlane(
            MutablePlaneBuffer plane,
            int startX,
            int startY,
            int visibleWidth,
            int visibleHeight,
            int packedFullWidth,
            int[] paletteColors,
            byte[] packedIndices
    ) {
        int[] nonNullPaletteColors = Objects.requireNonNull(paletteColors, "paletteColors");
        byte[] nonNullPackedIndices = Objects.requireNonNull(packedIndices, "packedIndices");
        if (packedFullWidth <= 0 || (packedFullWidth & 1) != 0) {
            throw new IllegalStateException("Palette map width must be a positive even value: " + packedFullWidth);
        }
        int packedStride = packedFullWidth >> 1;
        if (nonNullPackedIndices.length < packedStride * visibleHeight) {
            throw new IllegalStateException("Packed palette index map is shorter than the visible footprint");
        }

        int clippedVisibleWidth = Math.min(visibleWidth, plane.width() - startX);
        int clippedVisibleHeight = Math.min(visibleHeight, plane.height() - startY);
        if (clippedVisibleWidth <= 0 || clippedVisibleHeight <= 0) {
            return;
        }

        for (int y = 0; y < clippedVisibleHeight; y++) {
            int packedRow = y * packedStride;
            for (int x = 0; x < clippedVisibleWidth; x++) {
                int paletteIndex = paletteIndexAt(nonNullPackedIndices, packedRow, x);
                if (paletteIndex < 0 || paletteIndex >= nonNullPaletteColors.length) {
                    throw new IllegalStateException("Palette index out of range: " + paletteIndex);
                }
                plane.setSample(startX + x, startY + y, nonNullPaletteColors[paletteIndex]);
            }
        }
    }

    /// Returns one unpacked palette entry from a packed palette row.
    ///
    /// @param packedIndices the packed palette map
    /// @param packedRowStart the row start offset in the packed palette map
    /// @param x the zero-based sample coordinate inside the unpacked row
    /// @return one unpacked palette entry from the supplied packed row
    private static int paletteIndexAt(byte[] packedIndices, int packedRowStart, int x) {
        int packedByte = packedIndices[packedRowStart + (x >> 1)] & 0xFF;
        return (packedByte >> ((x & 1) << 2)) & 0x0F;
    }

    /// Clamps one integer value to the supplied inclusive bounds.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Returns the covered chroma span in 4x4 units for one luma-aligned block span.
    ///
    /// @param start4 the luma start coordinate in 4x4 units
    /// @param span4 the luma span in 4x4 units
    /// @param subsampling the chroma subsampling shift for the axis
    /// @return the covered chroma span in 4x4 units
    private static int chromaSpan4(int start4, int span4, int subsampling) {
        int chromaStart = start4 >> subsampling;
        int chromaEnd = (start4 + span4 + (1 << subsampling) - 1) >> subsampling;
        return Math.max(1, chromaEnd - chromaStart);
    }

    /// Returns whether one transform size currently supports non-zero residual reconstruction.
    ///
    /// @param transformSize the transform size to inspect
    /// @return whether one transform size currently supports non-zero residual reconstruction
    private static boolean isSupportedNonZeroResidualSize(TransformSize transformSize) {
        return switch (transformSize) {
            case TX_4X4,
                    TX_8X8,
                    TX_16X16,
                    TX_32X32,
                    TX_64X64,
                    RTX_4X8,
                    RTX_8X4,
                    RTX_4X16,
                    RTX_16X4,
                    RTX_8X16,
                    RTX_16X8,
                    RTX_16X32,
                    RTX_32X16,
                    RTX_32X64,
                    RTX_64X32,
                    RTX_8X32,
                    RTX_32X8,
                    RTX_16X64,
                    RTX_64X16 -> true;
            default -> false;
        };
    }

    /// Reconstructs the currently supported luma residual subset into the destination plane.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param residualLayout the decoded luma residual layout
    /// @param header the decoded block header that owns the residuals
    /// @param frameHeader the frame header that owns the active quantization state
    private static void reconstructLumaResiduals(
            MutablePlaneBuffer lumaPlane,
            ResidualLayout residualLayout,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader
    ) {
        for (TransformResidualUnit residualUnit : residualLayout.lumaUnits()) {
            if (residualUnit.allZero()) {
                continue;
            }
            int[] dequantizedCoefficients = LumaDequantizer.dequantize(
                    residualUnit,
                    new LumaDequantizer.Context(
                            header.qIndex(),
                            frameHeader.quantization().yDcDelta(),
                            lumaPlane.bitDepth()
                    )
            );
            int[] residualSamples = InverseTransformer.reconstructResidualBlock(
                    dequantizedCoefficients,
                    residualUnit.size()
            );
            InverseTransformer.addResidualBlock(
                    lumaPlane,
                residualUnit.position().x4() << 2,
                residualUnit.position().y4() << 2,
                residualUnit.size(),
                residualUnit.visibleWidthPixels(),
                residualUnit.visibleHeightPixels(),
                residualSamples
            );
        }
    }

    /// Reconstructs the currently supported chroma residual subset into the destination planes.
    ///
    /// @param chromaUPlane the mutable chroma U destination plane
    /// @param chromaVPlane the mutable chroma V destination plane
    /// @param residualLayout the decoded residual layout
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param pixelFormat the active decoded chroma layout
    /// @param qIndex the block-local quantizer index after delta-q updates
    private static void reconstructChromaResiduals(
            MutablePlaneBuffer chromaUPlane,
            MutablePlaneBuffer chromaVPlane,
            ResidualLayout residualLayout,
            FrameHeader frameHeader,
            PixelFormat pixelFormat,
            int qIndex
    ) {
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        reconstructChromaPlaneResiduals(
                chromaUPlane,
                residualLayout.chromaUUnits(),
                new ChromaDequantizer.Context(qIndex, quantization.uDcDelta(), quantization.uAcDelta(), chromaUPlane.bitDepth()),
                pixelFormat
        );
        reconstructChromaPlaneResiduals(
                chromaVPlane,
                residualLayout.chromaVUnits(),
                new ChromaDequantizer.Context(qIndex, quantization.vDcDelta(), quantization.vAcDelta(), chromaVPlane.bitDepth()),
                pixelFormat
        );
    }

    /// Reconstructs one chroma-plane residual array into the supplied destination plane.
    ///
    /// @param chromaPlane the mutable destination chroma plane
    /// @param residualUnits the decoded transform residual units in bitstream order
    /// @param dequantizationContext the plane-local chroma dequantization context
    private static void reconstructChromaPlaneResiduals(
            MutablePlaneBuffer chromaPlane,
            TransformResidualUnit[] residualUnits,
            ChromaDequantizer.Context dequantizationContext,
            PixelFormat pixelFormat
    ) {
        int chromaSubsamplingX = chromaSubsamplingX(pixelFormat);
        int chromaSubsamplingY = chromaSubsamplingY(pixelFormat);
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (residualUnit.allZero()) {
                continue;
            }
            int[] dequantizedCoefficients = ChromaDequantizer.dequantize(residualUnit, dequantizationContext);
            int[] residualSamples = InverseTransformer.reconstructResidualBlock(
                    dequantizedCoefficients,
                    residualUnit.size()
            );
            InverseTransformer.addResidualBlock(
                    chromaPlane,
                    residualUnit.position().x4() << (2 - chromaSubsamplingX),
                    residualUnit.position().y4() << (2 - chromaSubsamplingY),
                    residualUnit.size(),
                    residualUnit.visibleWidthPixels(),
                    residualUnit.visibleHeightPixels(),
                    residualSamples
            );
        }
    }

    /// Returns the horizontal chroma subsampling shift for one decoded pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @return the horizontal chroma subsampling shift for one decoded pixel format
    private static int chromaSubsamplingX(PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @return the vertical chroma subsampling shift for one decoded pixel format
    private static int chromaSubsamplingY(PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I422, I444 -> 0;
            case I420 -> 1;
        };
    }

    /// Returns the chroma-plane dimension corresponding to one visible luma span.
    ///
    /// @param lumaDimension the visible luma span in pixels
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the corresponding chroma-plane dimension
    private static int chromaDimension(int lumaDimension, int subsamplingShift) {
        return (lumaDimension + (1 << subsamplingShift) - 1) >> subsamplingShift;
    }
}
