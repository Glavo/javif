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
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Minimal frame reconstructor used by the first pixel-producing AV1 decode path.
///
/// The current implementation is intentionally narrow. It reconstructs only 8-bit key/intra frames
/// with the current serial tile traversal, `I400` or `I420` chroma layout, non-directional and
/// directional intra prediction, filter-intra luma prediction, CFL chroma prediction for `I420`,
/// the current minimal luma/chroma palette paths, and a minimal luma/chroma residual subset
/// including clipped frame-fringe chroma footprints and the currently supported rectangular
/// `DCT_DCT` transform sizes.
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

        for (TilePartitionTreeReader.Node[] tileRoots : checkedSyntaxDecodeResult.tileRoots()) {
            for (TilePartitionTreeReader.Node root : tileRoots) {
                reconstructNode(root, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat, frameHeader);
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
            PixelFormat pixelFormat,
            FrameHeader frameHeader
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            reconstructLeaf(leafNode, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat, frameHeader);
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            reconstructNode(child, lumaPlane, chromaUPlane, chromaVPlane, pixelFormat, frameHeader);
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
            FrameHeader frameHeader
    ) {
        TileBlockHeaderReader.BlockHeader header = leafNode.header();
        TransformLayout transformLayout = leafNode.transformLayout();
        ResidualLayout residualLayout = leafNode.residualLayout();
        requireSupportedLeaf(header, transformLayout, residualLayout, pixelFormat);

        int lumaX = header.position().x4() << 2;
        int lumaY = header.position().y4() << 2;
        int visibleLumaWidth = transformLayout.visibleWidthPixels();
        int visibleLumaHeight = transformLayout.visibleHeightPixels();

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

        reconstructLumaResiduals(lumaPlane, residualLayout, header, frameHeader);

        if (header.hasChroma() && chromaUPlane != null && chromaVPlane != null) {
            int chromaX = lumaX >> 1;
            int chromaY = lumaY >> 1;
            int visibleChromaWidth = (visibleLumaWidth + 1) >> 1;
            int visibleChromaHeight = (visibleLumaHeight + 1) >> 1;
            if (header.uvPaletteSize() != 0) {
                reconstructChromaPalette(
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        visibleChromaWidth,
                        visibleChromaHeight
                );
            } else if (header.uvMode() == UvIntraPredictionMode.CFL) {
                IntraPredictor.predictChromaCflI420(
                        chromaUPlane,
                        lumaPlane,
                        chromaX,
                        chromaY,
                        lumaX,
                        lumaY,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        header.cflAlphaU()
                );
                IntraPredictor.predictChromaCflI420(
                        chromaVPlane,
                        lumaPlane,
                        chromaX,
                        chromaY,
                        lumaX,
                        lumaY,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        header.cflAlphaV()
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
            PixelFormat pixelFormat
    ) {
        if (!header.intra()) {
            throw new IllegalStateException("Inter block reconstruction is not implemented yet");
        }
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
            if (header.uvPaletteSize() == 0 && header.uvMode() == null) {
                throw new IllegalStateException("Chroma reconstruction requires uvMode");
            }
            if (header.uvPaletteSize() == 0
                    && header.uvMode() == UvIntraPredictionMode.CFL
                    && pixelFormat != PixelFormat.I420) {
                throw new IllegalStateException("CFL reconstruction currently supports only I420");
            }
            if (header.uvPaletteSize() != 0 && pixelFormat != PixelFormat.I420) {
                throw new IllegalStateException("Palette chroma reconstruction currently supports only I420");
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
        for (TransformResidualUnit residualUnit : residualLayout.lumaUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current 4/8/16-sized DCT_DCT luma units: "
                                + residualUnit.size()
                );
            }
        }
        for (TransformResidualUnit residualUnit : residualLayout.chromaUUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current 4/8/16-sized DCT_DCT chroma units: "
                                + residualUnit.size()
                );
            }
        }
        for (TransformResidualUnit residualUnit : residualLayout.chromaVUnits()) {
            if (!residualUnit.allZero() && !isSupportedNonZeroResidualSize(residualUnit.size())) {
                throw new IllegalStateException(
                        "Non-zero residual reconstruction currently supports only the current 4/8/16-sized DCT_DCT chroma units: "
                                + residualUnit.size()
                );
            }
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

    /// Reconstructs one `I420` chroma palette block directly into the destination planes.
    ///
    /// The current reconstruction subset mirrors the packed chroma palette-map geometry exposed by
    /// `TileBlockHeaderReader`, then writes only the exact visible chroma footprint into the output
    /// planes.
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
            int visibleChromaWidth,
            int visibleChromaHeight
    ) {
        int chromaX = header.position().x4() << 1;
        int chromaY = header.position().y4() << 1;
        int fullChromaWidth = chromaSpan4(header.position().x4(), header.size().width4(), 1) << 2;
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
                    RTX_4X8,
                    RTX_8X4,
                    TX_8X8,
                    RTX_4X16,
                    RTX_16X4,
                    RTX_8X16,
                    RTX_16X8,
                    TX_16X16 -> true;
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
        if (pixelFormat != PixelFormat.I420) {
            throw new IllegalStateException("Chroma residual reconstruction currently supports only I420");
        }

        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        reconstructChromaPlaneResiduals(
                chromaUPlane,
                residualLayout.chromaUUnits(),
                new ChromaDequantizer.Context(qIndex, quantization.uDcDelta(), quantization.uAcDelta(), chromaUPlane.bitDepth())
        );
        reconstructChromaPlaneResiduals(
                chromaVPlane,
                residualLayout.chromaVUnits(),
                new ChromaDequantizer.Context(qIndex, quantization.vDcDelta(), quantization.vAcDelta(), chromaVPlane.bitDepth())
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
            ChromaDequantizer.Context dequantizationContext
    ) {
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
                residualUnit.position().x4() << 1,
                residualUnit.position().y4() << 1,
                residualUnit.size(),
                residualUnit.visibleWidthPixels(),
                residualUnit.visibleHeightPixels(),
                residualSamples
            );
        }
    }
}
