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
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Minimal frame reconstructor used by the first pixel-producing AV1 decode path.
///
/// The current implementation is intentionally narrow. It reconstructs only 8-bit key/intra frames
/// with one tile, `I400` or `I420` chroma layout, non-directional intra prediction, no palette,
/// no filter-intra, no CFL, and all-zero transform residuals.
@NotNullByDefault
public final class FrameReconstructor {
    /// Reconstructs one supported structural frame result into decoded planes.
    ///
    /// @param syntaxDecodeResult the structural frame result to reconstruct
    /// @return one decoded-plane snapshot
    public DecodedPlanes reconstruct(FrameSyntaxDecodeResult syntaxDecodeResult) {
        FrameSyntaxDecodeResult checkedSyntaxDecodeResult = Objects.requireNonNull(syntaxDecodeResult, "syntaxDecodeResult");
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

        for (TilePartitionTreeReader.Node root : checkedSyntaxDecodeResult.tileRoots(0)) {
            reconstructNode(root, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat);
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
                && sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I420) {
            throw new IllegalStateException(
                    "Pixel reconstruction currently supports only I400/I420: "
                            + sequenceHeader.colorConfig().pixelFormat()
            );
        }
        if (frameHeader.frameType() != FrameType.KEY && frameHeader.frameType() != FrameType.INTRA) {
            throw new IllegalStateException(
                    "Pixel reconstruction currently supports only key/intra frames: " + frameHeader.frameType()
            );
        }
        if (frameHeader.superResolution().enabled()) {
            throw new IllegalStateException("Super-resolution reconstruction is not implemented yet");
        }
        if (syntaxDecodeResult.tileCount() != 1) {
            throw new IllegalStateException("Pixel reconstruction currently supports only single-tile frames");
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
            case I422, I444 -> throw new IllegalStateException("Unsupported pixel format: " + pixelFormat);
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
            PixelFormat pixelFormat
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            reconstructLeaf(leafNode, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat);
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            reconstructNode(child, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat);
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
            PixelFormat pixelFormat
    ) {
        TileBlockHeaderReader.BlockHeader header = leafNode.header();
        TransformLayout transformLayout = leafNode.transformLayout();
        ResidualLayout residualLayout = leafNode.residualLayout();
        requireSupportedLeaf(header, transformLayout, residualLayout, pixelFormat);

        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidth4() << 2;
        int visibleLumaHeight = transformLayout.visibleHeight4() << 2;

        IntraPredictor.predictLuma(
                lumaPlane,
                lumaX,
                lumaY,
                visibleLumaWidth,
                visibleLumaHeight,
                Objects.requireNonNull(header.yMode(), "header.yMode()"),
                header.yAngle()
        );

        if (header.hasChroma() && chromaUPlane != null && chromaVPlane != null) {
            int chromaX = lumaX >> 1;
            int chromaY = lumaY >> 1;
            int visibleChromaWidth = (visibleLumaWidth + 1) >> 1;
            int visibleChromaHeight = (visibleLumaHeight + 1) >> 1;
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
            PixelFormat pixelFormat
    ) {
        if (!header.intra()) {
            throw new IllegalStateException("Inter block reconstruction is not implemented yet");
        }
        if (header.useIntrabc()) {
            throw new IllegalStateException("intrabc reconstruction is not implemented yet");
        }
        if (header.filterIntraMode() != null) {
            throw new IllegalStateException("filter_intra reconstruction is not implemented yet");
        }
        if (header.yPaletteSize() != 0 || header.uvPaletteSize() != 0) {
            throw new IllegalStateException("Palette reconstruction is not implemented yet");
        }
        if (header.hasChroma() && pixelFormat == PixelFormat.I400) {
            throw new IllegalStateException("Monochrome reconstruction encountered a block with chroma samples");
        }
        if (header.hasChroma()) {
            if (header.uvMode() == null) {
                throw new IllegalStateException("Chroma reconstruction requires uvMode");
            }
            if (header.cflAlphaU() != 0 || header.cflAlphaV() != 0) {
                throw new IllegalStateException("CFL reconstruction is not implemented yet");
            }
        }
        if (transformLayout.visibleWidth4() <= 0 || transformLayout.visibleHeight4() <= 0) {
            throw new IllegalStateException("Empty transform layout is not reconstructable");
        }
        for (TransformResidualUnit residualUnit : residualLayout.lumaUnits()) {
            if (!residualUnit.allZero()) {
                throw new IllegalStateException("Non-zero residual reconstruction is not implemented yet");
            }
        }
    }
}
