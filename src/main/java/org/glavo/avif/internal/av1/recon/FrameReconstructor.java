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
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.decode.FrameLocalPartitionTrees;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundPredictionType;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.MotionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Minimal frame reconstructor used by the first pixel-producing AV1 decode path.
///
/// The current implementation is intentionally narrow. It reconstructs only the current `8-bit`,
/// `10-bit`, and `12-bit` key/intra subset plus a first single-reference, inter-intra, average-compound, and same-frame
/// `intrabc` subset with the current fixed-filter subpel prediction path over the current serial tile traversal,
/// `I400`, `I420`, `I422`, or `I444` chroma layout,
/// non-directional and directional intra prediction, filter-intra luma prediction, the current
/// `I420` / `I422` / `I444` CFL chroma subset, parsed and synthetic luma/chroma palette paths, a
/// normative horizontal super-resolution upscaling path for key/intra frames plus the current
/// inter/reference prediction subset including OBMC and local warped single-reference prediction,
/// and a minimal luma/chroma residual subset including clipped frame-fringe chroma footprints,
/// explicit transform-type residual reconstruction for the currently modeled square and
/// rectangular transform sizes whose axes stay within `64` samples, and bit-depth preserving inter
/// subpel filtering for stored reference surfaces.
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

    /// The AV1 OBMC blend masks indexed by overlap length `1, 2, 4, 8, 16, 32, 64`.
    private static final int @Unmodifiable [] @Unmodifiable [] OBMC_MASKS = {
            {64},
            {45, 64},
            {39, 50, 59, 64},
            {36, 42, 48, 53, 57, 61, 64, 64},
            {34, 37, 40, 43, 46, 49, 52, 54, 56, 58, 60, 61, 64, 64, 64, 64},
            {
                    33, 35, 36, 38, 40, 41, 43, 44, 45, 47, 48, 50, 51, 52, 53, 55,
                    56, 57, 58, 59, 60, 60, 61, 62, 64, 64, 64, 64, 64, 64, 64, 64
            },
            {
                    33, 34, 35, 35, 36, 37, 38, 39, 40, 40, 41, 42, 43, 44, 44, 44,
                    45, 46, 47, 47, 48, 49, 50, 51, 51, 51, 52, 52, 53, 54, 55, 56,
                    56, 56, 57, 57, 58, 58, 59, 60, 60, 60, 60, 60, 61, 62, 62, 62,
                    62, 62, 63, 63, 63, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64
            }
    };

    /// The maximum number of causal motion-vector samples used for one local warped model.
    private static final int LOCAL_WARP_SAMPLE_CAPACITY = 8;

    /// The default AV1 regular 8-tap subpel filters in `dav1d_mc_subpel_filters` order.
    private static final int @Unmodifiable [] @Unmodifiable [] REGULAR_SUBPEL_FILTERS = {
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
    private static final int @Unmodifiable [] @Unmodifiable [] SMOOTH_SUBPEL_FILTERS = {
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
    private static final int @Unmodifiable [] @Unmodifiable [] SHARP_SUBPEL_FILTERS = {
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
    private static final int @Unmodifiable [] @Unmodifiable [] SMALL_REGULAR_SUBPEL_FILTERS = {
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
    private static final int @Unmodifiable [] @Unmodifiable [] SMALL_SMOOTH_SUBPEL_FILTERS = {
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
    private static final int @Unmodifiable [] @Unmodifiable [] SUPERRES_FILTERS = {
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
        AvifPixelFormat pixelFormat = sequenceHeader.colorConfig().pixelFormat();

        requireSupportedSequence(sequenceHeader, frameHeader, checkedSyntaxDecodeResult);

        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(
                frameSize.codedWidth(),
                frameSize.height(),
                sequenceHeader.colorConfig().bitDepth()
        );
        @Nullable MutablePlaneBuffer chromaUPlane = createChromaPlane(pixelFormat, frameSize, sequenceHeader.colorConfig().bitDepth());
        @Nullable MutablePlaneBuffer chromaVPlane = createChromaPlane(pixelFormat, frameSize, sequenceHeader.colorConfig().bitDepth());

        TilePartitionTreeReader.Node[][] tileRootsByTile = FrameLocalPartitionTrees.create(
                assembly,
                checkedSyntaxDecodeResult.tileRoots()
        );
        DecodedBlockMap decodedBlockMap = DecodedBlockMap.create(tileRootsByTile, frameSize.codedWidth(), frameSize.height());
        for (TilePartitionTreeReader.Node[] tileRoots : tileRootsByTile) {
            for (TilePartitionTreeReader.Node root : tileRoots) {
                reconstructNode(
                        root,
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        pixelFormat,
                        frameHeader,
                        sequenceHeader.features().orderHintBits(),
                        sequenceHeader.features().intraEdgeFilter(),
                        checkedReferenceSurfaceSnapshots,
                        decodedBlockMap
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
        if (sequenceHeader.colorConfig().pixelFormat() != AvifPixelFormat.I400
                && sequenceHeader.colorConfig().pixelFormat() != AvifPixelFormat.I420
                && sequenceHeader.colorConfig().pixelFormat() != AvifPixelFormat.I422
                && sequenceHeader.colorConfig().pixelFormat() != AvifPixelFormat.I444) {
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
            AvifPixelFormat pixelFormat,
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
            AvifPixelFormat pixelFormat,
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
    private static int upscaledChromaWidth(AvifPixelFormat pixelFormat, int upscaledLumaWidth) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (upscaledLumaWidth + 1) >> 1;
            case I444 -> upscaledLumaWidth;
        };
    }

    /// Chroma plane selector used when reusing the shared OBMC prediction path.
    private enum ChromaPlane {
        /// Chroma U plane.
        U,
        /// Chroma V plane.
        V
    }

    /// Frame-local leaf lookup table indexed in 4x4 units.
    @NotNullByDefault
    private static final class DecodedBlockMap {
        /// The frame width rounded up to 4x4 units.
        private final int width4;

        /// The frame height rounded up to 4x4 units.
        private final int height4;

        /// The leaf nodes indexed by 4x4 position.
        private final TilePartitionTreeReader.LeafNode[] leaves;

        /// Creates one decoded block map.
        ///
        /// @param width4 the frame width rounded up to 4x4 units
        /// @param height4 the frame height rounded up to 4x4 units
        private DecodedBlockMap(int width4, int height4) {
            if (width4 <= 0) {
                throw new IllegalArgumentException("width4 <= 0: " + width4);
            }
            if (height4 <= 0) {
                throw new IllegalArgumentException("height4 <= 0: " + height4);
            }
            this.width4 = width4;
            this.height4 = height4;
            this.leaves = new TilePartitionTreeReader.LeafNode[Math.multiplyExact(width4, height4)];
        }

        /// Creates one decoded block map from decoded tile partition roots.
        ///
        /// @param tileRootsByTile the decoded partition roots grouped by tile
        /// @param frameWidth the coded frame width in pixels
        /// @param frameHeight the coded frame height in pixels
        /// @return one decoded block map from decoded tile partition roots
        public static DecodedBlockMap create(
                TilePartitionTreeReader.Node[][] tileRootsByTile,
                int frameWidth,
                int frameHeight
        ) {
            DecodedBlockMap map = new DecodedBlockMap((frameWidth + 3) >> 2, (frameHeight + 3) >> 2);
            for (TilePartitionTreeReader.Node[] tileRoots : Objects.requireNonNull(tileRootsByTile, "tileRootsByTile")) {
                for (TilePartitionTreeReader.Node root : tileRoots) {
                    map.addNode(root);
                }
            }
            return map;
        }

        /// Adds one decoded partition node and all descendant leaves to this map.
        ///
        /// @param node the decoded partition node
        private void addNode(TilePartitionTreeReader.Node node) {
            TilePartitionTreeReader.Node nonNullNode = Objects.requireNonNull(node, "node");
            if (nonNullNode instanceof TilePartitionTreeReader.LeafNode leafNode) {
                addLeaf(leafNode);
                return;
            }
            TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) nonNullNode;
            for (TilePartitionTreeReader.Node child : partitionNode.children()) {
                addNode(child);
            }
        }

        /// Adds one decoded leaf to every 4x4 position it covers.
        ///
        /// @param leafNode the decoded partition leaf
        private void addLeaf(TilePartitionTreeReader.LeafNode leafNode) {
            TileBlockHeaderReader.BlockHeader header = Objects.requireNonNull(leafNode, "leafNode").header();
            int endX4 = Math.min(width4, header.position().x4() + header.size().width4());
            int endY4 = Math.min(height4, header.position().y4() + header.size().height4());
            for (int y4 = Math.max(0, header.position().y4()); y4 < endY4; y4++) {
                for (int x4 = Math.max(0, header.position().x4()); x4 < endX4; x4++) {
                    leaves[y4 * width4 + x4] = leafNode;
                }
            }
        }

        /// Returns the decoded leaf that covers one 4x4 position.
        ///
        /// @param x4 the horizontal 4x4 coordinate
        /// @param y4 the vertical 4x4 coordinate
        /// @return the decoded leaf that covers the position, or `null`
        public @Nullable TilePartitionTreeReader.LeafNode leafAt(int x4, int y4) {
            if (x4 < 0 || y4 < 0 || x4 >= width4 || y4 >= height4) {
                return null;
            }
            return leaves[y4 * width4 + x4];
        }
    }

    /// One causal neighbor sample used to estimate a local warped affine motion model.
    @NotNullByDefault
    private static final class LocalWarpSample {
        /// The decoded neighbor block that produced this sample.
        private final TileBlockHeaderReader.BlockHeader header;

        /// The sample X position in luma destination coordinates.
        private final double destinationX;

        /// The sample Y position in luma destination coordinates.
        private final double destinationY;

        /// The sample horizontal motion delta relative to the current block, in quarter-pel units.
        private final int columnDeltaQuarterPel;

        /// The sample vertical motion delta relative to the current block, in quarter-pel units.
        private final int rowDeltaQuarterPel;

        /// Creates one causal neighbor sample used for local warped motion estimation.
        ///
        /// @param header the decoded neighbor block that produced this sample
        /// @param destinationX the sample X position in luma destination coordinates
        /// @param destinationY the sample Y position in luma destination coordinates
        /// @param columnDeltaQuarterPel the horizontal motion delta relative to the current block
        /// @param rowDeltaQuarterPel the vertical motion delta relative to the current block
        private LocalWarpSample(
                TileBlockHeaderReader.BlockHeader header,
                double destinationX,
                double destinationY,
                int columnDeltaQuarterPel,
                int rowDeltaQuarterPel
        ) {
            this.header = Objects.requireNonNull(header, "header");
            this.destinationX = destinationX;
            this.destinationY = destinationY;
            this.columnDeltaQuarterPel = columnDeltaQuarterPel;
            this.rowDeltaQuarterPel = rowDeltaQuarterPel;
        }
    }

    /// One affine local warped motion model estimated from causal same-reference motion samples.
    @NotNullByDefault
    private static final class LocalWarpModel {
        /// The current block center X in luma destination coordinates.
        private final double centerX;

        /// The current block center Y in luma destination coordinates.
        private final double centerY;

        /// The base horizontal motion-vector component in quarter-pel units.
        private final int baseColumnQuarterPel;

        /// The base vertical motion-vector component in quarter-pel units.
        private final int baseRowQuarterPel;

        /// The horizontal motion derivative by luma X coordinate, in quarter-pel units per pixel.
        private final double columnDx;

        /// The horizontal motion derivative by luma Y coordinate, in quarter-pel units per pixel.
        private final double columnDy;

        /// The vertical motion derivative by luma X coordinate, in quarter-pel units per pixel.
        private final double rowDx;

        /// The vertical motion derivative by luma Y coordinate, in quarter-pel units per pixel.
        private final double rowDy;

        /// Creates one affine local warped motion model.
        ///
        /// @param centerX the current block center X in luma destination coordinates
        /// @param centerY the current block center Y in luma destination coordinates
        /// @param baseColumnQuarterPel the base horizontal motion-vector component
        /// @param baseRowQuarterPel the base vertical motion-vector component
        /// @param columnDx the horizontal motion derivative by luma X coordinate
        /// @param columnDy the horizontal motion derivative by luma Y coordinate
        /// @param rowDx the vertical motion derivative by luma X coordinate
        /// @param rowDy the vertical motion derivative by luma Y coordinate
        private LocalWarpModel(
                double centerX,
                double centerY,
                int baseColumnQuarterPel,
                int baseRowQuarterPel,
                double columnDx,
                double columnDy,
                double rowDx,
                double rowDy
        ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.baseColumnQuarterPel = baseColumnQuarterPel;
            this.baseRowQuarterPel = baseRowQuarterPel;
            this.columnDx = columnDx;
            this.columnDy = columnDy;
            this.rowDx = rowDx;
            this.rowDy = rowDy;
        }

        /// Returns the horizontal source offset for one luma-domain destination sample.
        ///
        /// @param lumaX the destination X coordinate in luma samples
        /// @param lumaY the destination Y coordinate in luma samples
        /// @return the horizontal source offset in quarter-pel units
        public int columnOffsetQuarterPel(double lumaX, double lumaY) {
            double dx = lumaX - centerX;
            double dy = lumaY - centerY;
            return baseColumnQuarterPel + (int) Math.round(dx * columnDx + dy * columnDy);
        }

        /// Returns the vertical source offset for one luma-domain destination sample.
        ///
        /// @param lumaX the destination X coordinate in luma samples
        /// @param lumaY the destination Y coordinate in luma samples
        /// @return the vertical source offset in quarter-pel units
        public int rowOffsetQuarterPel(double lumaX, double lumaY) {
            double dx = lumaX - centerX;
            double dy = lumaY - centerY;
            return baseRowQuarterPel + (int) Math.round(dx * rowDx + dy * rowDy);
        }
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
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void reconstructNode(
            TilePartitionTreeReader.Node node,
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            boolean intraEdgeFilterEnabled,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        reconstructNode(
                node,
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                pixelFormat,
                frameHeader,
                orderHintBits,
                intraEdgeFilterEnabled,
                referenceSurfaceSnapshots,
                DecodedBlockMap.create(new TilePartitionTreeReader.Node[][]{{node}}, lumaPlane.width(), lumaPlane.height())
        );
    }

    /// Recursively reconstructs one partition-tree node using the supplied decoded-block map.
    ///
    /// @param node the partition-tree node to reconstruct
    /// @param lumaPlane the mutable luma plane
    /// @param chromaUPlane the mutable chroma U plane, or `null`
    /// @param chromaVPlane the mutable chroma V plane, or `null`
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    private static void reconstructNode(
            TilePartitionTreeReader.Node node,
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            boolean intraEdgeFilterEnabled,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            reconstructLeaf(
                    leafNode,
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    pixelFormat,
                    frameHeader,
                    orderHintBits,
                    intraEdgeFilterEnabled,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
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
                    orderHintBits,
                    intraEdgeFilterEnabled,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
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
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    private static void reconstructLeaf(
            TilePartitionTreeReader.LeafNode leafNode,
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            boolean intraEdgeFilterEnabled,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
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
        int codedLumaWidth = header.size().widthPixels();
        int codedLumaHeight = header.size().heightPixels();

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
                        codedLumaWidth,
                        codedLumaHeight,
                        header.filterIntraMode()
                );
            } else {
                IntraPredictor.predictLuma(
                        lumaPlane,
                        lumaX,
                        lumaY,
                        codedLumaWidth,
                        codedLumaHeight,
                        Objects.requireNonNull(header.yMode(), "header.yMode()"),
                        header.yAngle(),
                        intraEdgeFilterEnabled,
                        lumaSmoothEdgeReferences(header, decodedBlockMap)
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
                    orderHintBits,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
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
            int codedChromaWidth = chromaDimension(codedLumaWidth, chromaSubsamplingX);
            int codedChromaHeight = chromaDimension(codedLumaHeight, chromaSubsamplingY);
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
                            codedChromaWidth,
                            codedChromaHeight,
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
                            codedChromaWidth,
                            codedChromaHeight,
                            header.cflAlphaV(),
                            chromaSubsamplingX,
                            chromaSubsamplingY
                    );
                } else {
                    IntraPredictor.predictChroma(
                            chromaUPlane,
                            chromaX,
                            chromaY,
                            codedChromaWidth,
                            codedChromaHeight,
                            Objects.requireNonNull(header.uvMode(), "header.uvMode()"),
                            header.uvAngle(),
                            intraEdgeFilterEnabled,
                            chromaSmoothEdgeReferences(header, decodedBlockMap)
                    );
                    IntraPredictor.predictChroma(
                            chromaVPlane,
                            chromaX,
                            chromaY,
                            codedChromaWidth,
                            codedChromaHeight,
                            Objects.requireNonNull(header.uvMode(), "header.uvMode()"),
                            header.uvAngle(),
                            intraEdgeFilterEnabled,
                            chromaSmoothEdgeReferences(header, decodedBlockMap)
                    );
                }
            }

            reconstructChromaResiduals(chromaUPlane, chromaVPlane, residualLayout, frameHeader, pixelFormat, header.qIndex());
        }
    }

    /// Returns whether the luma intra-edge references adjacent to one block are marked smooth.
    ///
    /// @param header the current decoded block header
    /// @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
    /// @return whether an adjacent top or left luma edge comes from a smooth intra predictor
    private static boolean lumaSmoothEdgeReferences(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap
    ) {
        int x4 = header.position().x4();
        int y4 = header.position().y4();
        return hasSmoothLumaMode(decodedBlockMap.leafAt(x4, y4 - 1))
                || hasSmoothLumaMode(decodedBlockMap.leafAt(x4 - 1, y4));
    }

    /// Returns whether the chroma intra-edge references adjacent to one block are marked smooth.
    ///
    /// @param header the current decoded block header
    /// @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
    /// @return whether an adjacent top or left chroma edge comes from a smooth intra predictor
    private static boolean chromaSmoothEdgeReferences(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap
    ) {
        int x4 = header.position().x4();
        int y4 = header.position().y4();
        return hasSmoothChromaMode(decodedBlockMap.leafAt(x4, y4 - 1))
                || hasSmoothChromaMode(decodedBlockMap.leafAt(x4 - 1, y4));
    }

    /// Returns whether one decoded leaf used a smooth luma intra mode.
    ///
    /// @param leafNode the decoded neighbor leaf, or `null`
    /// @return whether the leaf used a smooth luma intra mode
    private static boolean hasSmoothLumaMode(@Nullable TilePartitionTreeReader.LeafNode leafNode) {
        if (leafNode == null || !leafNode.header().intra()) {
            return false;
        }
        return isSmoothLumaMode(leafNode.header().yMode());
    }

    /// Returns whether one decoded leaf used a smooth chroma intra mode.
    ///
    /// @param leafNode the decoded neighbor leaf, or `null`
    /// @return whether the leaf used a smooth chroma intra mode
    private static boolean hasSmoothChromaMode(@Nullable TilePartitionTreeReader.LeafNode leafNode) {
        if (leafNode == null || !leafNode.header().intra() || !leafNode.header().hasChroma()) {
            return false;
        }
        return isSmoothChromaMode(leafNode.header().uvMode());
    }

    /// Returns whether one luma intra mode is a smooth predictor.
    ///
    /// @param mode the luma intra mode, or `null`
    /// @return whether the mode is smooth
    private static boolean isSmoothLumaMode(@Nullable LumaIntraPredictionMode mode) {
        return mode == LumaIntraPredictionMode.SMOOTH
                || mode == LumaIntraPredictionMode.SMOOTH_VERTICAL
                || mode == LumaIntraPredictionMode.SMOOTH_HORIZONTAL;
    }

    /// Returns whether one chroma intra mode is a smooth predictor.
    ///
    /// @param mode the chroma intra mode, or `null`
    /// @return whether the mode is smooth
    private static boolean isSmoothChromaMode(@Nullable UvIntraPredictionMode mode) {
        return mode == UvIntraPredictionMode.SMOOTH
                || mode == UvIntraPredictionMode.SMOOTH_VERTICAL
                || mode == UvIntraPredictionMode.SMOOTH_HORIZONTAL;
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
            AvifPixelFormat pixelFormat,
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
        if (header.hasChroma() && pixelFormat == AvifPixelFormat.I400) {
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
            if (header.interIntra() && !InterIntraMasks.supportsInterIntra(header.size())) {
                throw new IllegalStateException("Inter-intra reconstruction encountered an unsupported block size");
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
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
    private static void reconstructInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            int orderHintBits,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
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
                    orderHintBits,
                    referenceSurfaceSnapshots
            );
        } else {
            if (header.motionMode() == MotionMode.LOCAL_WARPED) {
                reconstructLocalWarpedInterPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        pixelFormat,
                        frameHeader,
                        referenceSurfaceSnapshots,
                        decodedBlockMap
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
            if (header.interIntra()) {
                applyInterIntraPrediction(lumaPlane, chromaUPlane, chromaVPlane, header, transformLayout, pixelFormat);
            }
            if (header.motionMode() == MotionMode.OBMC) {
                applyObmcPrediction(
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        transformLayout,
                        pixelFormat,
                        frameHeader,
                        referenceSurfaceSnapshots,
                        decodedBlockMap
                );
            }
        }
    }

    /// Applies OBMC blending to a single-reference inter predictor already stored in the output planes.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the OBMC state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    private static void applyObmcPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        applyObmcAboveNeighbors(
                lumaPlane,
                null,
                header,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
        );
        applyObmcLeftNeighbors(
                lumaPlane,
                null,
                header,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
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
        applyObmcAboveNeighbors(
                chromaUPlane,
                ChromaPlane.U,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
        );
        applyObmcLeftNeighbors(
                chromaUPlane,
                ChromaPlane.U,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
        );
        applyObmcAboveNeighbors(
                chromaVPlane,
                ChromaPlane.V,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
        );
        applyObmcLeftNeighbors(
                chromaVPlane,
                ChromaPlane.V,
                header,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                frameHeader,
                pixelFormat,
                referenceSurfaceSnapshots,
                decodedBlockMap
        );
    }

    /// Applies OBMC blending from already-decoded above neighbors.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param header the decoded block header that owns the OBMC state
    /// @param destinationX the plane-local block origin X
    /// @param destinationY the plane-local block origin Y
    /// @param visibleWidth the visible block width in plane samples
    /// @param visibleHeight the visible block height in plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param frameHeader the frame header that owns the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    private static void applyObmcAboveNeighbors(
            MutablePlaneBuffer destinationPlane,
            @Nullable ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int subsamplingX,
            int subsamplingY,
            FrameHeader frameHeader,
            AvifPixelFormat pixelFormat,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockY4 <= 0 || visibleWidth <= 0 || visibleHeight <= 0) {
            return;
        }
        int lumaOverlap = Math.min(header.size().heightPixels(), 64) >> 1;
        int fullOverlap = Math.max(1, lumaOverlap >> subsamplingY);
        int overlap = Math.min(visibleHeight, fullOverlap);
        int[] mask = obmcMask(fullOverlap);
        int maxNeighbors = maximumObmcNeighbors(header.size().width4());
        int processed = 0;
        int endX4 = blockX4 + header.size().width4();
        int scanX4 = blockX4;
        while (scanX4 < endX4 && processed < maxNeighbors) {
            @Nullable TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(scanX4, blockY4 - 1);
            if (neighbor == null) {
                scanX4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int nextX4 = Math.min(endX4, neighborHeader.position().x4() + neighborHeader.size().width4());
            if (isObmcNeighbor(neighborHeader)) {
                int relativeLumaX = Math.max(0, scanX4 - blockX4) << 2;
                int planeRelativeX = relativeLumaX >> subsamplingX;
                int planeWidth = Math.min(visibleWidth - planeRelativeX, Math.max(1, ((nextX4 - scanX4) << 2) >> subsamplingX));
                if (planeWidth > 0) {
                    blendObmcRegion(
                            destinationPlane,
                            chromaPlane,
                            neighborHeader,
                            destinationX + planeRelativeX,
                            destinationY,
                            planeWidth,
                            overlap,
                            mask,
                            true,
                            frameHeader,
                            pixelFormat,
                            referenceSurfaceSnapshots
                    );
                    processed++;
                }
            }
            scanX4 = Math.max(scanX4 + 1, nextX4);
        }
    }

    /// Applies OBMC blending from already-decoded left neighbors.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param header the decoded block header that owns the OBMC state
    /// @param destinationX the plane-local block origin X
    /// @param destinationY the plane-local block origin Y
    /// @param visibleWidth the visible block width in plane samples
    /// @param visibleHeight the visible block height in plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param frameHeader the frame header that owns the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal neighbors
    private static void applyObmcLeftNeighbors(
            MutablePlaneBuffer destinationPlane,
            @Nullable ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader header,
            int destinationX,
            int destinationY,
            int visibleWidth,
            int visibleHeight,
            int subsamplingX,
            int subsamplingY,
            FrameHeader frameHeader,
            AvifPixelFormat pixelFormat,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockX4 <= 0 || visibleWidth <= 0 || visibleHeight <= 0) {
            return;
        }
        int lumaOverlap = Math.min(header.size().widthPixels(), 64) >> 1;
        int fullOverlap = Math.max(1, lumaOverlap >> subsamplingX);
        int overlap = Math.min(visibleWidth, fullOverlap);
        int[] mask = obmcMask(fullOverlap);
        int maxNeighbors = maximumObmcNeighbors(header.size().height4());
        int processed = 0;
        int endY4 = blockY4 + header.size().height4();
        int scanY4 = blockY4;
        while (scanY4 < endY4 && processed < maxNeighbors) {
            @Nullable TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(blockX4 - 1, scanY4);
            if (neighbor == null) {
                scanY4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int nextY4 = Math.min(endY4, neighborHeader.position().y4() + neighborHeader.size().height4());
            if (isObmcNeighbor(neighborHeader)) {
                int relativeLumaY = Math.max(0, scanY4 - blockY4) << 2;
                int planeRelativeY = relativeLumaY >> subsamplingY;
                int planeHeight = Math.min(visibleHeight - planeRelativeY, Math.max(1, ((nextY4 - scanY4) << 2) >> subsamplingY));
                if (planeHeight > 0) {
                    blendObmcRegion(
                            destinationPlane,
                            chromaPlane,
                            neighborHeader,
                            destinationX,
                            destinationY + planeRelativeY,
                            overlap,
                            planeHeight,
                            mask,
                            false,
                            frameHeader,
                            pixelFormat,
                            referenceSurfaceSnapshots
                    );
                    processed++;
                }
            }
            scanY4 = Math.max(scanY4 + 1, nextY4);
        }
    }

    /// Blends one OBMC neighbor predictor into a destination region.
    ///
    /// @param destinationPlane the mutable destination plane containing the current predictor
    /// @param chromaPlane the chroma plane selector, or `null` for luma
    /// @param neighborHeader the decoded neighbor block header supplying the primary predictor
    /// @param destinationX the plane-local region origin X
    /// @param destinationY the plane-local region origin Y
    /// @param width the region width in plane samples
    /// @param height the region height in plane samples
    /// @param mask the OBMC mask for the varying axis
    /// @param above whether the region is blended from an above neighbor
    /// @param frameHeader the frame header that owns the current block
    /// @param pixelFormat the active decoded chroma layout
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void blendObmcRegion(
            MutablePlaneBuffer destinationPlane,
            @Nullable ChromaPlane chromaPlane,
            TileBlockHeaderReader.BlockHeader neighborHeader,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int[] mask,
            boolean above,
            FrameHeader frameHeader,
            AvifPixelFormat pixelFormat,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                pixelFormat,
                destinationPlane.bitDepth(),
                neighborHeader.referenceFrame0()
        );
        DecodedPlane referencePlane;
        if (chromaPlane == null) {
            referencePlane = referenceSurfaceSnapshot.decodedPlanes().lumaPlane();
        } else if (chromaPlane == ChromaPlane.U) {
            referencePlane = Objects.requireNonNull(
                    referenceSurfaceSnapshot.decodedPlanes().chromaUPlane(),
                    "referencePlanes.chromaUPlane()"
            );
        } else {
            referencePlane = Objects.requireNonNull(
                    referenceSurfaceSnapshot.decodedPlanes().chromaVPlane(),
                    "referencePlanes.chromaVPlane()"
            );
        }
        MotionVector motionVector = Objects.requireNonNull(neighborHeader.motionVector0(), "neighborHeader.motionVector0()").vector();
        int denominatorX = chromaPlane == null ? 4 : 4 << chromaSubsamplingX(pixelFormat);
        int denominatorY = chromaPlane == null ? 4 : 4 << chromaSubsamplingY(pixelFormat);
        FrameHeader.InterpolationFilter horizontalFilter = resolveHorizontalInterpolationFilter(neighborHeader, frameHeader);
        FrameHeader.InterpolationFilter verticalFilter = resolveVerticalInterpolationFilter(neighborHeader, frameHeader);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int predictor = sampleInterPlaneValue(
                        referencePlane,
                        destinationX + x,
                        destinationY + y,
                        destinationPlane.width(),
                        destinationPlane.height(),
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        denominatorX,
                        denominatorY,
                        width,
                        height,
                        horizontalFilter,
                        verticalFilter,
                        destinationPlane.maxSampleValue()
                );
                int current = destinationPlane.sample(destinationX + x, destinationY + y);
                int currentWeight = mask[above ? y : x];
                destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        blendMaskedCompoundSamples(predictor, current, currentWeight)
                );
            }
        }
    }

    /// Returns whether one decoded neighbor can provide an OBMC predictor.
    ///
    /// @param header the decoded neighbor block header
    /// @return whether the neighbor can provide an OBMC predictor
    private static boolean isObmcNeighbor(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        return !nonNullHeader.intra()
                && !nonNullHeader.useIntrabc()
                && nonNullHeader.referenceFrame0() >= 0
                && nonNullHeader.motionVector0() != null;
    }

    /// Returns the AV1 OBMC neighbor limit for one axis measured in 4x4 units.
    ///
    /// @param size4 the current block axis size in 4x4 units
    /// @return the maximum number of causal neighbors to blend on that axis
    private static int maximumObmcNeighbors(int size4) {
        if (size4 <= 2) {
            return 1;
        }
        if (size4 <= 4) {
            return 2;
        }
        if (size4 <= 8) {
            return 3;
        }
        return 4;
    }

    /// Returns the AV1 OBMC mask for the supplied overlap length.
    ///
    /// @param length the overlap length in samples
    /// @return the AV1 OBMC mask for the supplied overlap length
    private static int[] obmcMask(int length) {
        return switch (length) {
            case 1 -> OBMC_MASKS[0];
            case 2 -> OBMC_MASKS[1];
            case 4 -> OBMC_MASKS[2];
            case 8 -> OBMC_MASKS[3];
            case 16 -> OBMC_MASKS[4];
            case 32 -> OBMC_MASKS[5];
            case 64 -> OBMC_MASKS[6];
            default -> throw new IllegalStateException("Unsupported OBMC overlap length: " + length);
        };
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
            AvifPixelFormat pixelFormat
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
            AvifPixelFormat pixelFormat,
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

    /// Reconstructs a single-reference inter predictor using an affine local warped motion model.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    private static void reconstructLocalWarpedInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            DecodedBlockMap decodedBlockMap
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
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        LocalWarpModel model = estimateLocalWarpModel(header, decodedBlockMap);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        reconstructLocalWarpedPlanePrediction(
                lumaPlane,
                referencePlanes.lumaPlane(),
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0,
                4,
                4,
                visibleLumaWidth,
                visibleLumaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                model
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

        reconstructLocalWarpedPlanePrediction(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes.chromaUPlane(), "referencePlanes.chromaUPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                model
        );
        reconstructLocalWarpedPlanePrediction(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes.chromaVPlane(), "referencePlanes.chromaVPlane()"),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaDenominatorX,
                chromaDenominatorY,
                visibleChromaWidth,
                visibleChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                model
        );
    }

    /// Reconstructs one plane through a local warped affine motion model.
    ///
    /// @param destinationPlane the mutable destination plane
    /// @param referencePlane the immutable reference plane
    /// @param destinationX the zero-based horizontal destination coordinate
    /// @param destinationY the zero-based vertical destination coordinate
    /// @param width the predicted width in plane samples
    /// @param height the predicted height in plane samples
    /// @param subsamplingX the horizontal luma-to-plane subsampling shift
    /// @param subsamplingY the vertical luma-to-plane subsampling shift
    /// @param denominatorX the plane-local horizontal denominator expressed in luma quarter-pel units
    /// @param denominatorY the plane-local vertical denominator expressed in luma quarter-pel units
    /// @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
    /// @param horizontalFilterMode the effective horizontal interpolation filter mode
    /// @param verticalFilterMode the effective vertical interpolation filter mode
    /// @param model the affine local warped motion model
    private static void reconstructLocalWarpedPlanePrediction(
            MutablePlaneBuffer destinationPlane,
            DecodedPlane referencePlane,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int subsamplingX,
            int subsamplingY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter horizontalFilterMode,
            FrameHeader.InterpolationFilter verticalFilterMode,
            LocalWarpModel model
    ) {
        for (int y = 0; y < height; y++) {
            int planeY = destinationY + y;
            int lumaY = planeY << subsamplingY;
            for (int x = 0; x < width; x++) {
                int planeX = destinationX + x;
                int lumaX = planeX << subsamplingX;
                int sourceNumeratorX = mapDestinationCoordinateToReferenceNumerator(
                        planeX,
                        destinationPlane.width(),
                        referencePlane.width(),
                        denominatorX
                ) + model.columnOffsetQuarterPel(lumaX, lumaY);
                int sourceNumeratorY = mapDestinationCoordinateToReferenceNumerator(
                        planeY,
                        destinationPlane.height(),
                        referencePlane.height(),
                        denominatorY
                ) + model.rowOffsetQuarterPel(lumaX, lumaY);
                int sample;
                if (Math.floorMod(sourceNumeratorX, denominatorX) == 0
                        && Math.floorMod(sourceNumeratorY, denominatorY) == 0) {
                    sample = referencePlane.sample(
                            clamp(sourceNumeratorX / denominatorX, 0, referencePlane.width() - 1),
                            clamp(sourceNumeratorY / denominatorY, 0, referencePlane.height() - 1)
                    );
                } else {
                    sample = filteredInterpolateAt(
                            referencePlane,
                            sourceNumeratorX,
                            sourceNumeratorY,
                            denominatorX,
                            denominatorY,
                            widthForFilterSelection,
                            heightForFilterSelection,
                            horizontalFilterMode,
                            verticalFilterMode,
                            destinationPlane.maxSampleValue()
                    );
                }
                destinationPlane.setSample(planeX, planeY, sample);
            }
        }
    }

    /// Estimates one local warped affine motion model from causal same-reference neighbors.
    ///
    /// @param header the decoded current block header
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    /// @return one local warped affine motion model for the current block
    private static LocalWarpModel estimateLocalWarpModel(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap
    ) {
        MotionVector baseMotionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        double centerX = blockCenterCoordinate(header.position().x4(), header.size().widthPixels());
        double centerY = blockCenterCoordinate(header.position().y4(), header.size().heightPixels());
        LocalWarpSample[] samples = new LocalWarpSample[LOCAL_WARP_SAMPLE_CAPACITY];
        int sampleCount = collectLocalWarpAboveSamples(header, decodedBlockMap, baseMotionVector, samples, 0);
        sampleCount = collectLocalWarpLeftSamples(header, decodedBlockMap, baseMotionVector, samples, sampleCount);

        double a00 = 0.0;
        double a01 = 0.0;
        double a11 = 0.0;
        double columnB0 = 0.0;
        double columnB1 = 0.0;
        double rowB0 = 0.0;
        double rowB1 = 0.0;
        for (int i = 0; i < sampleCount; i++) {
            LocalWarpSample sample = Objects.requireNonNull(samples[i], "samples[i]");
            double dx = sample.destinationX - centerX;
            double dy = sample.destinationY - centerY;
            a00 += dx * dx;
            a01 += dx * dy;
            a11 += dy * dy;
            columnB0 += dx * sample.columnDeltaQuarterPel;
            columnB1 += dy * sample.columnDeltaQuarterPel;
            rowB0 += dx * sample.rowDeltaQuarterPel;
            rowB1 += dy * sample.rowDeltaQuarterPel;
        }

        double columnDx = 0.0;
        double columnDy = 0.0;
        double rowDx = 0.0;
        double rowDy = 0.0;
        double determinant = a00 * a11 - a01 * a01;
        if (Math.abs(determinant) > 0.000001) {
            columnDx = (columnB0 * a11 - columnB1 * a01) / determinant;
            columnDy = (a00 * columnB1 - a01 * columnB0) / determinant;
            rowDx = (rowB0 * a11 - rowB1 * a01) / determinant;
            rowDy = (a00 * rowB1 - a01 * rowB0) / determinant;
        } else {
            double denominator = a00 + a11;
            if (denominator > 0.000001) {
                columnDx = columnB0 / denominator;
                columnDy = columnB1 / denominator;
                rowDx = rowB0 / denominator;
                rowDy = rowB1 / denominator;
            }
        }

        return new LocalWarpModel(
                centerX,
                centerY,
                baseMotionVector.columnQuarterPel(),
                baseMotionVector.rowQuarterPel(),
                columnDx,
                columnDy,
                rowDx,
                rowDy
        );
    }

    /// Collects compatible above-neighbor samples for local warped motion estimation.
    ///
    /// @param header the decoded current block header
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    /// @param baseMotionVector the current block primary motion vector
    /// @param samples the destination local-warp sample array
    /// @param sampleCount the number of valid samples already present
    /// @return the updated number of valid samples
    private static int collectLocalWarpAboveSamples(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap,
            MotionVector baseMotionVector,
            LocalWarpSample[] samples,
            int sampleCount
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockY4 <= 0) {
            return sampleCount;
        }
        int endX4 = blockX4 + header.size().width4();
        int scanX4 = blockX4;
        while (scanX4 < endX4 && sampleCount < samples.length) {
            @Nullable TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(scanX4, blockY4 - 1);
            if (neighbor == null) {
                scanX4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int nextX4 = Math.min(endX4, neighborHeader.position().x4() + neighborHeader.size().width4());
            sampleCount = appendLocalWarpSample(header, neighborHeader, baseMotionVector, samples, sampleCount);
            scanX4 = Math.max(scanX4 + 1, nextX4);
        }
        return sampleCount;
    }

    /// Collects compatible left-neighbor samples for local warped motion estimation.
    ///
    /// @param header the decoded current block header
    /// @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
    /// @param baseMotionVector the current block primary motion vector
    /// @param samples the destination local-warp sample array
    /// @param sampleCount the number of valid samples already present
    /// @return the updated number of valid samples
    private static int collectLocalWarpLeftSamples(
            TileBlockHeaderReader.BlockHeader header,
            DecodedBlockMap decodedBlockMap,
            MotionVector baseMotionVector,
            LocalWarpSample[] samples,
            int sampleCount
    ) {
        int blockX4 = header.position().x4();
        int blockY4 = header.position().y4();
        if (blockX4 <= 0) {
            return sampleCount;
        }
        int endY4 = blockY4 + header.size().height4();
        int scanY4 = blockY4;
        while (scanY4 < endY4 && sampleCount < samples.length) {
            @Nullable TilePartitionTreeReader.LeafNode neighbor = decodedBlockMap.leafAt(blockX4 - 1, scanY4);
            if (neighbor == null) {
                scanY4 += 2;
                continue;
            }
            TileBlockHeaderReader.BlockHeader neighborHeader = neighbor.header();
            int nextY4 = Math.min(endY4, neighborHeader.position().y4() + neighborHeader.size().height4());
            sampleCount = appendLocalWarpSample(header, neighborHeader, baseMotionVector, samples, sampleCount);
            scanY4 = Math.max(scanY4 + 1, nextY4);
        }
        return sampleCount;
    }

    /// Appends one compatible local-warp sample if the neighbor is eligible and not already used.
    ///
    /// @param currentHeader the decoded current block header
    /// @param neighborHeader the decoded causal neighbor block header
    /// @param baseMotionVector the current block primary motion vector
    /// @param samples the destination local-warp sample array
    /// @param sampleCount the number of valid samples already present
    /// @return the updated number of valid samples
    private static int appendLocalWarpSample(
            TileBlockHeaderReader.BlockHeader currentHeader,
            TileBlockHeaderReader.BlockHeader neighborHeader,
            MotionVector baseMotionVector,
            LocalWarpSample[] samples,
            int sampleCount
    ) {
        if (sampleCount >= samples.length
                || !isLocalWarpReferenceNeighbor(currentHeader, neighborHeader)
                || containsLocalWarpSample(samples, sampleCount, neighborHeader)) {
            return sampleCount;
        }
        MotionVector neighborMotionVector =
                Objects.requireNonNull(neighborHeader.motionVector0(), "neighborHeader.motionVector0()").vector();
        samples[sampleCount] = new LocalWarpSample(
                neighborHeader,
                blockCenterCoordinate(neighborHeader.position().x4(), neighborHeader.size().widthPixels()),
                blockCenterCoordinate(neighborHeader.position().y4(), neighborHeader.size().heightPixels()),
                neighborMotionVector.columnQuarterPel() - baseMotionVector.columnQuarterPel(),
                neighborMotionVector.rowQuarterPel() - baseMotionVector.rowQuarterPel()
        );
        return sampleCount + 1;
    }

    /// Returns whether the local-warp sample array already contains one neighbor header.
    ///
    /// @param samples the local-warp sample array
    /// @param sampleCount the number of valid samples already present
    /// @param header the neighbor header to search for
    /// @return whether the local-warp sample array already contains one neighbor header
    private static boolean containsLocalWarpSample(
            LocalWarpSample[] samples,
            int sampleCount,
            TileBlockHeaderReader.BlockHeader header
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        for (int i = 0; i < sampleCount; i++) {
            if (Objects.requireNonNull(samples[i], "samples[i]").header == nonNullHeader) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether one neighbor can provide a local-warp motion sample for the current block.
    ///
    /// @param currentHeader the decoded current block header
    /// @param neighborHeader the decoded causal neighbor block header
    /// @return whether one neighbor can provide a local-warp motion sample for the current block
    private static boolean isLocalWarpReferenceNeighbor(
            TileBlockHeaderReader.BlockHeader currentHeader,
            TileBlockHeaderReader.BlockHeader neighborHeader
    ) {
        TileBlockHeaderReader.BlockHeader nonNullNeighborHeader = Objects.requireNonNull(neighborHeader, "neighborHeader");
        return !nonNullNeighborHeader.intra()
                && !nonNullNeighborHeader.useIntrabc()
                && !nonNullNeighborHeader.compoundReference()
                && nonNullNeighborHeader.referenceFrame0() == currentHeader.referenceFrame0()
                && nonNullNeighborHeader.motionVector0() != null
                && nonNullNeighborHeader.motionVector0().resolved();
    }

    /// Returns the luma-domain center coordinate for one block axis.
    ///
    /// @param origin4 the block origin in 4x4 units
    /// @param sizePixels the block size in luma samples on the same axis
    /// @return the luma-domain center coordinate for one block axis
    private static double blockCenterCoordinate(int origin4, int sizePixels) {
        return (origin4 << 2) + sizePixels * 0.5 - 0.5;
    }

    /// Applies AV1 inter-intra blending to the already built single-reference inter predictor.
    ///
    /// @param lumaPlane the mutable luma destination plane containing the inter predictor
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter-intra state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    private static void applyInterIntraPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            AvifPixelFormat pixelFormat
    ) {
        InterIntraPredictionMode mode = Objects.requireNonNull(header.interIntraMode(), "header.interIntraMode()");
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();

        MutablePlaneBuffer lumaIntraPlane = lumaPlane.copy();
        IntraPredictor.predictLuma(
                lumaIntraPlane,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                mode.toLumaPredictionMode(),
                0
        );
        blendInterIntraPlane(
                lumaPlane,
                lumaIntraPlane,
                header,
                mode,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                0,
                0
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

        MutablePlaneBuffer chromaUIntraPlane = chromaUPlane.copy();
        IntraPredictor.predictChroma(
                chromaUIntraPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                mode.toUvPredictionMode(),
                0
        );
        blendInterIntraPlane(
                chromaUPlane,
                chromaUIntraPlane,
                header,
                mode,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY
        );

        MutablePlaneBuffer chromaVIntraPlane = chromaVPlane.copy();
        IntraPredictor.predictChroma(
                chromaVIntraPlane,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                mode.toUvPredictionMode(),
                0
        );
        blendInterIntraPlane(
                chromaVPlane,
                chromaVIntraPlane,
                header,
                mode,
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                chromaSubsamplingX,
                chromaSubsamplingY
        );
    }

    /// Blends one inter predictor plane with its intra predictor using an AV1 inter-intra mask.
    ///
    /// @param destinationPlane the mutable destination plane containing the inter predictor
    /// @param intraPlane the mutable intra predictor plane
    /// @param header the decoded block header that owns the inter-intra state
    /// @param mode the decoded inter-intra prediction mode
    /// @param originX the destination-plane block origin X
    /// @param originY the destination-plane block origin Y
    /// @param width the visible block width in destination-plane samples
    /// @param height the visible block height in destination-plane samples
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    private static void blendInterIntraPlane(
            MutablePlaneBuffer destinationPlane,
            MutablePlaneBuffer intraPlane,
            TileBlockHeaderReader.BlockHeader header,
            InterIntraPredictionMode mode,
            int originX,
            int originY,
            int width,
            int height,
            int subsamplingX,
            int subsamplingY
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mask = InterIntraMasks.maskValue(
                        mode,
                        header.interIntraWedge(),
                        header.interIntraWedgeIndex(),
                        header.size(),
                        x,
                        y,
                        subsamplingX,
                        subsamplingY
                );
                int interSample = destinationPlane.sample(originX + x, originY + y);
                int intraSample = intraPlane.sample(originX + x, originY + y);
                destinationPlane.setSample(
                        originX + x,
                        originY + y,
                        (interSample * (64 - mask) + intraSample * mask + 32) >> 6
                );
            }
        }
    }

    /// Reconstructs the currently supported compound-reference inter prediction subset by averaging
    /// or masked-blending the two predicted reference surfaces after each one has been sampled
    /// with the current integer-copy or fixed-filter subpel path.
    ///
    /// @param lumaPlane the mutable luma destination plane
    /// @param chromaUPlane the mutable chroma U destination plane, or `null`
    /// @param chromaVPlane the mutable chroma V destination plane, or `null`
    /// @param header the decoded block header that owns the inter state
    /// @param transformLayout the decoded transform layout for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param frameHeader the frame header that owns the block
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    private static void reconstructCompoundInterPrediction(
            MutablePlaneBuffer lumaPlane,
            @Nullable MutablePlaneBuffer chromaUPlane,
            @Nullable MutablePlaneBuffer chromaVPlane,
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            AvifPixelFormat pixelFormat,
            FrameHeader frameHeader,
            int orderHintBits,
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
        CompoundPredictionType compoundPredictionType =
                Objects.requireNonNull(header.compoundPredictionType(), "header.compoundPredictionType()");
        int jointWeight = compoundPredictionType == CompoundPredictionType.WEIGHTED_AVERAGE
                ? jointCompoundWeight(
                frameHeader,
                referenceSurfaceSnapshot0.frameHeader(),
                referenceSurfaceSnapshot1.frameHeader(),
                orderHintBits
        )
                : 8;
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = resolveHorizontalInterpolationFilter(header, frameHeader);
        FrameHeader.InterpolationFilter verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader);
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        @Nullable int[] segmentMask = compoundPredictionType == CompoundPredictionType.SEGMENT
                ? new int[visibleLumaWidth * visibleLumaHeight]
                : null;

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
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                0,
                0,
                lumaPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
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
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaUPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
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
                verticalInterpolationFilter,
                compoundPredictionType,
                header.compoundMaskSign(),
                header.compoundWedgeIndex(),
                header.size(),
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaVPlane.bitDepth(),
                jointWeight,
                segmentMask,
                visibleLumaWidth,
                visibleLumaHeight
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

    /// Reconstructs one compound inter-predicted plane by blending two independently predicted
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
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param maskSign whether the decoded segment or wedge mask uses inverted source order
    /// @param wedgeIndex the decoded compound wedge index, or `-1`
    /// @param blockSize the luma block size that owns this plane prediction
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param bitDepth the decoded sample bit depth
    /// @param jointWeight the decoded joint compound weight for weighted average prediction
    /// @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
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
            FrameHeader.InterpolationFilter verticalFilterMode,
            CompoundPredictionType compoundPredictionType,
            boolean maskSign,
            int wedgeIndex,
            BlockSize blockSize,
            int subsamplingX,
            int subsamplingY,
            int bitDepth,
            int jointWeight,
            @Nullable int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight
    ) {
        CompoundPredictionType nonNullCompoundPredictionType =
                Objects.requireNonNull(compoundPredictionType, "compoundPredictionType");
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
                int sample = switch (nonNullCompoundPredictionType) {
                    case AVERAGE -> averageCompoundSamples(sample0, sample1);
                    case WEIGHTED_AVERAGE -> weightedAverageCompoundSamples(sample0, sample1, jointWeight);
                    case WEDGE -> {
                        int mask = InterIntraMasks.compoundWedgeMaskValue(
                                blockSize,
                                wedgeIndex,
                                maskSign,
                                x,
                                y,
                                subsamplingX,
                                subsamplingY
                        );
                        yield blendMaskedCompoundSamples(sample0, sample1, mask);
                    }
                    case SEGMENT -> {
                        int mask = segmentCompoundMaskValue(
                                sample0,
                                sample1,
                                bitDepth,
                                maskSign,
                                x,
                                y,
                                subsamplingX,
                                subsamplingY,
                                segmentMask,
                                segmentMaskWidth,
                                segmentMaskHeight
                        );
                        yield blendMaskedCompoundSamples(sample0, sample1, mask);
                    }
                };
                destinationPlane.setSample(destinationX + x, destinationY + y, sample);
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
        int clampedY = clamp(sourceY, 0, referencePlane.height() - 1);
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceX = clamp(sourceX0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.width() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, clampedY);
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
        int clampedX = clamp(sourceX, 0, referencePlane.width() - 1);
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(clampedX, sourceY);
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

    /// Returns one weighted average-compound sample.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param primaryWeight the primary predictor weight in sixteenths
    /// @return one weighted average-compound sample
    private static int weightedAverageCompoundSamples(int primarySample, int secondarySample, int primaryWeight) {
        return (primarySample * primaryWeight + secondarySample * (16 - primaryWeight) + 8) >> 4;
    }

    /// Returns one masked compound sample using the supplied secondary-source weight.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param secondaryWeight the secondary predictor weight in `[0, 64]`
    /// @return one masked compound sample
    private static int blendMaskedCompoundSamples(int primarySample, int secondarySample, int secondaryWeight) {
        return (primarySample * (64 - secondaryWeight) + secondarySample * secondaryWeight + 32) >> 6;
    }

    /// Returns one segment-compound mask value as the effective secondary-source blend weight.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param bitDepth the decoded sample bit depth
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @param x the plane-local sample X coordinate inside the block
    /// @param y the plane-local sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift for this plane
    /// @param subsamplingY the vertical chroma subsampling shift for this plane
    /// @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
    /// @return one segment-compound mask value as the effective secondary-source blend weight
    private static int segmentCompoundMaskValue(
            int primarySample,
            int secondarySample,
            int bitDepth,
            boolean maskSign,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY,
            @Nullable int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight
    ) {
        int mask;
        if (subsamplingX == 0 && subsamplingY == 0) {
            mask = lumaSegmentCompoundMaskValue(primarySample, secondarySample, bitDepth);
            if (segmentMask != null && x < segmentMaskWidth && y < segmentMaskHeight) {
                segmentMask[y * segmentMaskWidth + x] = mask;
            }
        } else {
            if (segmentMask == null) {
                throw new IllegalStateException("Chroma segment compound prediction requires a luma segment mask");
            }
            mask = chromaSegmentCompoundMaskValue(
                    segmentMask,
                    segmentMaskWidth,
                    segmentMaskHeight,
                    x,
                    y,
                    subsamplingX,
                    subsamplingY,
                    maskSign
            );
        }
        return maskSign ? mask : 64 - mask;
    }

    /// Returns one luma-domain segment-compound mask value.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param bitDepth the decoded sample bit depth
    /// @return one luma-domain segment-compound mask value
    private static int lumaSegmentCompoundMaskValue(int primarySample, int secondarySample, int bitDepth) {
        int intermediateBits = 4;
        int maskShift = bitDepth + intermediateBits - 4;
        int maskRound = 1 << (maskShift - 5);
        int scaledDifference = ((Math.abs(primarySample - secondarySample) << intermediateBits) + maskRound) >> maskShift;
        return Math.min(38 + scaledDifference, 64);
    }

    /// Returns one chroma segment-compound mask value derived from the luma-domain segment mask.
    ///
    /// @param segmentMask the luma-domain segment mask
    /// @param segmentMaskWidth the luma-domain segment mask width
    /// @param segmentMaskHeight the luma-domain segment mask height
    /// @param x the chroma-plane sample X coordinate inside the block
    /// @param y the chroma-plane sample Y coordinate inside the block
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @return one chroma segment-compound mask value
    private static int chromaSegmentCompoundMaskValue(
            int[] segmentMask,
            int segmentMaskWidth,
            int segmentMaskHeight,
            int x,
            int y,
            int subsamplingX,
            int subsamplingY,
            boolean maskSign
    ) {
        int lumaX = x << subsamplingX;
        int lumaY = y << subsamplingY;
        int horizontalSpan = 1 << subsamplingX;
        int verticalSpan = 1 << subsamplingY;
        int sum = 0;
        for (int yy = 0; yy < verticalSpan; yy++) {
            int sampleY = Math.min(segmentMaskHeight - 1, lumaY + yy);
            for (int xx = 0; xx < horizontalSpan; xx++) {
                int sampleX = Math.min(segmentMaskWidth - 1, lumaX + xx);
                sum += segmentMask[sampleY * segmentMaskWidth + sampleX];
            }
            if (subsamplingX != 0) {
                sum++;
            }
        }
        return (sum - (maskSign ? 1 : 0)) >> (subsamplingX + subsamplingY);
    }

    /// Returns the joint compound primary weight for one decoded reference pair.
    ///
    /// @param frameHeader the current frame header
    /// @param referenceHeader0 the primary reference frame header
    /// @param referenceHeader1 the secondary reference frame header
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @return the joint compound primary weight for one decoded reference pair
    private static int jointCompoundWeight(
            FrameHeader frameHeader,
            FrameHeader referenceHeader0,
            FrameHeader referenceHeader1,
            int orderHintBits
    ) {
        int distance1 = Math.min(
                Math.abs(orderHintDifference(orderHintBits, referenceHeader0.frameOffset(), frameHeader.frameOffset())),
                31
        );
        int distance0 = Math.min(
                Math.abs(orderHintDifference(orderHintBits, referenceHeader1.frameOffset(), frameHeader.frameOffset())),
                31
        );
        boolean order = distance0 <= distance1;
        int[][] quantDistanceWeight = {{2, 3}, {2, 5}, {2, 7}};
        int[][] quantDistanceLookup = {{9, 7}, {11, 5}, {12, 4}, {13, 3}};
        int k;
        for (k = 0; k < quantDistanceWeight.length; k++) {
            int c0 = quantDistanceWeight[k][order ? 1 : 0];
            int c1 = quantDistanceWeight[k][order ? 0 : 1];
            int distance0Scaled = distance0 * c0;
            int distance1Scaled = distance1 * c1;
            if ((distance0 > distance1 && distance0Scaled < distance1Scaled)
                    || (distance0 <= distance1 && distance0Scaled > distance1Scaled)) {
                break;
            }
        }
        return quantDistanceLookup[k][order ? 1 : 0];
    }

    /// Returns the wrapped order-hint difference `poc0 - poc1`.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param poc0 the minuend order hint
    /// @param poc1 the subtrahend order hint
    /// @return the wrapped order-hint difference `poc0 - poc1`
    private static int orderHintDifference(int orderHintBits, int poc0, int poc1) {
        if (orderHintBits == 0) {
            return 0;
        }
        int mask = 1 << (orderHintBits - 1);
        int diff = poc0 - poc1;
        return (diff & (mask - 1)) - (diff & mask);
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
            AvifPixelFormat pixelFormat,
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
            AvifPixelFormat pixelFormat,
            boolean hasChroma,
            FrameHeader.InterpolationFilter horizontalInterpolationFilter,
            FrameHeader.InterpolationFilter verticalInterpolationFilter
    ) {
        MotionVector checkedMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        boolean lumaFractional = (checkedMotionVector.rowQuarterPel() & 0x03) != 0
                || (checkedMotionVector.columnQuarterPel() & 0x03) != 0;
        if (!hasChroma || pixelFormat == AvifPixelFormat.I400) {
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
            AvifPixelFormat pixelFormat,
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
            AvifPixelFormat pixelFormat,
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

    /// Reconstructs decoded luma residuals into the destination plane.
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
                            lumaPlane.bitDepth(),
                            frameHeader.quantization().useQuantizationMatrices(),
                            frameHeader.quantization().quantizationMatrixY()
                    )
            );
            int[] residualSamples = InverseTransformer.reconstructResidualBlock(
                    dequantizedCoefficients,
                    residualUnit.size(),
                    residualUnit.transformType()
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

    /// Reconstructs decoded chroma residuals into the destination planes.
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
            AvifPixelFormat pixelFormat,
            int qIndex
    ) {
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        reconstructChromaPlaneResiduals(
                chromaUPlane,
                residualLayout.chromaUUnits(),
                new ChromaDequantizer.Context(
                        qIndex,
                        quantization.uDcDelta(),
                        quantization.uAcDelta(),
                        chromaUPlane.bitDepth(),
                        quantization.useQuantizationMatrices(),
                        quantization.quantizationMatrixU()
                ),
                pixelFormat
        );
        reconstructChromaPlaneResiduals(
                chromaVPlane,
                residualLayout.chromaVUnits(),
                new ChromaDequantizer.Context(
                        qIndex,
                        quantization.vDcDelta(),
                        quantization.vAcDelta(),
                        chromaVPlane.bitDepth(),
                        quantization.useQuantizationMatrices(),
                        quantization.quantizationMatrixV()
                ),
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
            AvifPixelFormat pixelFormat
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
                    residualUnit.size(),
                    residualUnit.transformType()
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
    private static int chromaSubsamplingX(AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @return the vertical chroma subsampling shift for one decoded pixel format
    private static int chromaSubsamplingY(AvifPixelFormat pixelFormat) {
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
