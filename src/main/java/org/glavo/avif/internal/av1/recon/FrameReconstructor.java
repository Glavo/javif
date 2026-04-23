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
/// The current implementation is intentionally narrow. It reconstructs only 8-bit key/intra frames
/// plus a first single-reference copy-based inter subset with the current serial tile traversal,
/// `I400`, `I420`, `I422`, or `I444` chroma layout,
/// non-directional and directional intra prediction, filter-intra luma prediction, the current
/// `I420` / `I422` / `I444` CFL chroma subset, the current minimal luma/chroma palette paths, and
/// a minimal luma/chroma residual subset including clipped frame-fringe chroma footprints and the
/// currently supported square and rectangular `DCT_DCT` transform sizes whose axes stay within
/// `64` samples.
@NotNullByDefault
public final class FrameReconstructor {
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

        return new DecodedPlanes(
                sequenceHeader.colorConfig().bitDepth(),
                pixelFormat,
                frameSize.codedWidth(),
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                lumaPlane.toDecodedPlane(),
                chromaUPlane != null ? chromaUPlane.toDecodedPlane() : null,
                chromaVPlane != null ? chromaVPlane.toDecodedPlane() : null
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
        if (sequenceHeader.colorConfig().bitDepth() != 8) {
            throw new IllegalStateException(
                    "Pixel reconstruction currently requires 8-bit samples: " + sequenceHeader.colorConfig().bitDepth()
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
        if (frameHeader.superResolution().enabled()) {
            throw new IllegalStateException("Super-resolution reconstruction is not implemented yet");
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
                frameHeader,
                referenceSurfaceSnapshots
        );

        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();

        if (header.intra()) {
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
    private static void requireSupportedLeaf(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            ResidualLayout residualLayout,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots
    ) {
        if (header.useIntrabc()) {
            throw new IllegalStateException("intrabc reconstruction is not implemented yet");
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
            if (header.uvPaletteSize() != 0 && residualLayout.hasChromaUnits()) {
                throw new IllegalStateException("Palette chroma reconstruction currently expects no chroma residual units");
            }
        }
        if (transformLayout.visibleWidth4() <= 0 || transformLayout.visibleHeight4() <= 0) {
            throw new IllegalStateException("Empty transform layout is not reconstructable");
        }
        if (!header.intra()) {
            if (header.compoundReference()) {
                throw new IllegalStateException("Compound inter reconstruction is not implemented yet");
            }
            if (header.motionVector0() == null) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (!header.motionVector0().resolved()) {
                throw new IllegalStateException("Inter reconstruction requires one resolved primary motion vector");
            }
            if (header.yPaletteSize() != 0 || header.uvPaletteSize() != 0) {
                throw new IllegalStateException("Inter palette reconstruction is not implemented yet");
            }
            requireReferenceSurfaceSnapshot(referenceSurfaceSnapshots, frameHeader, header, pixelFormat);
            requireSupportedInterMotionVector(header.motionVector0().vector(), pixelFormat, header.hasChroma());
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
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                requireReferenceSurfaceSnapshot(referenceSurfaceSnapshots, frameHeader, header, pixelFormat);
        DecodedPlanes referencePlanes = referenceSurfaceSnapshot.decodedPlanes();
        MotionVector motionVector = Objects.requireNonNull(header.motionVector0(), "header.motionVector0()").vector();
        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();
        int lumaSourceX = lumaX + (motionVector.columnQuarterPel() >> 2);
        int lumaSourceY = lumaY + (motionVector.rowQuarterPel() >> 2);

        copyReferencePlaneBlock(
                lumaPlane,
                referencePlanes.lumaPlane(),
                lumaX,
                lumaY,
                lumaSourceX,
                lumaSourceY,
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
        int chromaSourceX = chromaX + (motionVector.columnQuarterPel() >> (2 + chromaSubsamplingX));
        int chromaSourceY = chromaY + (motionVector.rowQuarterPel() >> (2 + chromaSubsamplingY));

        copyReferencePlaneBlock(
                chromaUPlane,
                Objects.requireNonNull(referencePlanes.chromaUPlane(), "referencePlanes.chromaUPlane()"),
                chromaX,
                chromaY,
                chromaSourceX,
                chromaSourceY,
                visibleChromaWidth,
                visibleChromaHeight
        );
        copyReferencePlaneBlock(
                chromaVPlane,
                Objects.requireNonNull(referencePlanes.chromaVPlane(), "referencePlanes.chromaVPlane()"),
                chromaX,
                chromaY,
                chromaSourceX,
                chromaSourceY,
                visibleChromaWidth,
                visibleChromaHeight
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

    /// Returns one compatible stored reference surface for the supplied block.
    ///
    /// @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
    /// @param frameHeader the frame header that owns the block
    /// @param header the decoded block header that references the stored surface
    /// @param pixelFormat the active decoded chroma layout
    /// @return one compatible stored reference surface for the supplied block
    private static ReferenceSurfaceSnapshot requireReferenceSurfaceSnapshot(
            @Nullable ReferenceSurfaceSnapshot[] referenceSurfaceSnapshots,
            FrameHeader frameHeader,
            TileBlockHeaderReader.BlockHeader header,
            PixelFormat pixelFormat
    ) {
        int referenceFramePosition = header.referenceFrame0();
        if (referenceFramePosition < 0 || referenceFramePosition >= 7) {
            throw new IllegalStateException("Inter reconstruction requires one valid primary reference-frame position");
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
        if (referencePlanes.bitDepth() != 8) {
            throw new IllegalStateException("Inter reconstruction currently requires one 8-bit stored reference surface");
        }
        if (referencePlanes.pixelFormat() != pixelFormat) {
            throw new IllegalStateException(
                    "Inter reconstruction currently requires matching reference pixel format: " + pixelFormat
            );
        }
        if (referencePlanes.codedWidth() != frameHeader.frameSize().codedWidth()
                || referencePlanes.codedHeight() != frameHeader.frameSize().height()) {
            throw new IllegalStateException("Inter reconstruction currently requires matching reference frame dimensions");
        }
        return referenceSurfaceSnapshot;
    }

    /// Validates that one inter motion vector stays inside the current copy-based reconstruction
    /// subset.
    ///
    /// @param motionVector the inter motion vector chosen for the block
    /// @param pixelFormat the active decoded chroma layout
    /// @param hasChroma whether the block carries chroma samples
    private static void requireSupportedInterMotionVector(
            MotionVector motionVector,
            PixelFormat pixelFormat,
            boolean hasChroma
    ) {
        MotionVector checkedMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        if ((checkedMotionVector.rowQuarterPel() & 0x03) != 0 || (checkedMotionVector.columnQuarterPel() & 0x03) != 0) {
            throw new IllegalStateException("Inter reconstruction currently requires integer-pel luma motion vectors");
        }
        if (!hasChroma || pixelFormat == PixelFormat.I400) {
            return;
        }

        int chromaHorizontalAlignment = 4 << chromaSubsamplingX(pixelFormat);
        int chromaVerticalAlignment = 4 << chromaSubsamplingY(pixelFormat);
        if (Math.floorMod(checkedMotionVector.columnQuarterPel(), chromaHorizontalAlignment) != 0
                || Math.floorMod(checkedMotionVector.rowQuarterPel(), chromaVerticalAlignment) != 0) {
            throw new IllegalStateException(
                    "Inter reconstruction currently requires chroma-aligned integer motion vectors for " + pixelFormat
            );
        }
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
    /// @param visibleWidth the exact visible width in pixels
    /// @param visibleHeight the exact visible height in pixels
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

        for (int y = 0; y < visibleHeight; y++) {
            int packedRow = y * packedStride;
            for (int x = 0; x < visibleWidth; x++) {
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
