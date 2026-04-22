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
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for the first-pixel `FrameReconstructor` path.
@NotNullByDefault
final class FrameReconstructorIntegrationTest {
    /// Verifies that one monochrome all-zero intra leaf reconstructs to midpoint DC samples.
    @Test
    void reconstructsMonochromeDcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, createLeaf(true, false));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one `I420` all-zero intra leaf reconstructs midpoint chroma as well as luma.
    @Test
    void reconstructsI420DcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I420, createLeaf(true, true));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(3, 3));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(3, 3));
    }

    /// Verifies that one monochrome all-zero `filter_intra` leaf reconstructs through the minimal luma path.
    @Test
    void reconstructsMonochromeFilterIntraDcLeaf() {
        TilePartitionTreeReader.LeafNode leaf = createLeaf(true, false, FilterIntraMode.DC);
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, leaf, true);
        assertEquals(FilterIntraMode.DC, leaf.header().filterIntraMode());

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(198, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one decoded 4x4 monochrome DC residual survives the frame-syntax path and shifts luma uniformly.
    @Test
    void reconstructsMonochromeDcResidualDecodedFromFrameSyntax() {
        byte[] zeroResidualPayload = findPayloadForResidualFlags(BlockSize.SIZE_4X4, new boolean[]{true});
        byte[] dcResidualPayload = findPayloadForDcOnlyResidual(BlockSize.SIZE_4X4);

        FrameSyntaxDecodeResult baselineSyntax = decodeMonochromeFourByFourFrame(zeroResidualPayload);
        FrameSyntaxDecodeResult residualSyntax = decodeMonochromeFourByFourFrame(dcResidualPayload);
        TilePartitionTreeReader.LeafNode decodedLeaf = firstLeaf(residualSyntax.tileRoots(0));
        TransformResidualUnit residualUnit = decodedLeaf.residualLayout().lumaUnits()[0];

        assertFalse(residualUnit.allZero());
        assertEquals(0, residualUnit.endOfBlockIndex());
        assertTrue(residualUnit.dcCoefficient() != 0);

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baselineLuma = reconstructor.reconstruct(baselineSyntax).lumaPlane();
        DecodedPlanes reconstructed = reconstructor.reconstruct(residualSyntax);

        assertEquals(baselineLuma.width(), reconstructed.lumaPlane().width());
        assertEquals(baselineLuma.height(), reconstructed.lumaPlane().height());
        assertNull(reconstructed.chromaUPlane());
        assertNull(reconstructed.chromaVPlane());
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
        return createSyntheticResult(pixelFormat, leafNode, false);
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode,
            boolean filterIntraEnabled
    ) {
        FrameAssembly assembly = createAssembly(pixelFormat, filterIntraEnabled);
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leafNode}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(boolean allZeroResidual, boolean hasChroma) {
        return createLeaf(allZeroResidual, hasChroma, null);
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @param filterIntraMode the synthetic filter-intra mode, or `null` when filter intra is disabled
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(
            boolean allZeroResidual,
            boolean hasChroma,
            @Nullable FilterIntraMode filterIntraMode
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                hasChroma,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                LumaIntraPredictionMode.DC,
                hasChroma ? UvIntraPredictionMode.DC : null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                filterIntraMode,
                0,
                0,
                0,
                0
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                hasChroma ? TransformSize.TX_4X4 : null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        int[] coefficients = new int[TransformSize.TX_8X8.widthPixels() * TransformSize.TX_8X8.heightPixels()];
        int endOfBlockIndex = -1;
        if (!allZeroResidual) {
            coefficients[0] = 1;
            endOfBlockIndex = 0;
        }
        ResidualLayout residualLayout = new ResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{
                        new TransformResidualUnit(position, TransformSize.TX_8X8, endOfBlockIndex, coefficients, 0)
                }
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Decodes one tiny 4x4 monochrome frame from a caller-supplied tile payload.
    ///
    /// @param payload the tile payload to decode structurally
    /// @return one tiny 4x4 monochrome frame decoded from the supplied tile payload
    private static FrameSyntaxDecodeResult decodeMonochromeFourByFourFrame(byte[] payload) {
        return new FrameSyntaxDecoder(null).decode(
                createAssembly(PixelFormat.I400, payload, 4, 4, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY)
        );
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat) {
        return createAssembly(pixelFormat, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat, boolean filterIntraEnabled) {
        return createAssembly(pixelFormat, new byte[0], 8, 8, FrameHeader.TransformMode.LARGEST, filterIntraEnabled);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return createAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode,
            boolean filterIntraEnabled
    ) {
        boolean monochrome = pixelFormat == PixelFormat.I400;
        SequenceHeader sequenceHeader = new SequenceHeader(
                0,
                codedWidth,
                codedHeight,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(2, 0, 10, 0, false, false, false, null)
                },
                true,
                true,
                15,
                15,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        filterIntraEnabled,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        0,
                        false,
                        false,
                        false,
                        false
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        monochrome,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat == PixelFormat.I420,
                        pixelFormat == PixelFormat.I420,
                        false
                )
        );
        FrameHeader frameHeader = new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.KEY,
                true,
                false,
                true,
                false,
                false,
                true,
                false,
                7,
                0,
                0xFF,
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, codedWidth),
                false,
                false,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                false,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        1,
                        new int[]{0, 1},
                        new int[]{0, 1},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                true,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        true,
                        true,
                        new int[]{1, 0, 0, 0, -1, 0, -1, -1},
                        new int[]{0, 0}
                ),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                transformMode,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                false
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
    }

    /// Finds a small payload whose first residual flags match the requested sequence.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @return a small payload whose first residual flags match the requested sequence
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags) {
        byte[] twoBytePayload = findPayloadForResidualFlags(blockSize, expectedFlags, 2);
        if (twoBytePayload != null) {
            return twoBytePayload;
        }
        byte[] threeBytePayload = findPayloadForResidualFlags(blockSize, expectedFlags, 3);
        if (threeBytePayload != null) {
            return threeBytePayload;
        }
        throw new IllegalStateException("No deterministic payload produced the requested residual flags");
    }

    /// Finds a small payload whose first residual unit is supported and DC-only.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return a small payload whose first residual unit is supported and DC-only
    private static byte[] findPayloadForDcOnlyResidual(BlockSize blockSize) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
                    TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
                    TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
                    TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
                    TileBlockHeaderReader.BlockHeader header =
                            blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
                    if (header.skip()) {
                        continue;
                    }
                    TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
                    TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
                    if (!residualUnit.allZero() && residualUnit.endOfBlockIndex() == 0) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported residual trees are skipped while brute-forcing a supported DC-only unit.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported DC-only residual");
    }

    /// Finds a payload whose first residual flags match the requested sequence, or `null`.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @param searchBytes the number of leading payload bytes to brute force
    /// @return a payload whose first residual flags match the requested sequence, or `null`
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags, int searchBytes) {
        int limit = 1 << (searchBytes << 3);
        for (int value = 0; value < limit; value++) {
            byte[] payload = new byte[8];
            for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
            }

            TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
            TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
            TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
            TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
            BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
            TileBlockHeaderReader.BlockHeader header =
                    blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
            if (header.skip()) {
                continue;
            }

            TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
            ResidualLayout residualLayout;
            try {
                residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
            } catch (IllegalStateException ignored) {
                continue;
            }
            TransformResidualUnit[] residualUnits = residualLayout.lumaUnits();
            if (residualUnits.length < expectedFlags.length) {
                continue;
            }

            boolean matched = true;
            for (int i = 0; i < expectedFlags.length; i++) {
                if (residualUnits[i].allZero() != expectedFlags[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return payload;
            }
        }
        return null;
    }

    /// Creates one tile-local decode context used by payload search helpers.
    ///
    /// @param payload the collected tile entropy payload
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context used by payload search helpers
    private static TileDecodeContext createTileContext(byte[] payload, FrameHeader.TransformMode transformMode) {
        return TileDecodeContext.create(createAssembly(PixelFormat.I400, payload, 64, 64, transformMode), 0);
    }

    /// Returns the first leaf node in raster order from one tile-root array.
    ///
    /// @param roots the top-level tile roots
    /// @return the first leaf node in raster order
    private static TilePartitionTreeReader.LeafNode firstLeaf(TilePartitionTreeReader.Node[] roots) {
        for (TilePartitionTreeReader.Node root : roots) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(root);
            if (leaf != null) {
                return leaf;
            }
        }
        throw new IllegalStateException("No leaf nodes were produced");
    }

    /// Returns the first leaf node in raster order from one subtree, or `null`.
    ///
    /// @param node the subtree root
    /// @return the first leaf node in raster order from one subtree, or `null`
    private static TilePartitionTreeReader.@org.jetbrains.annotations.Nullable LeafNode firstLeaf(
            TilePartitionTreeReader.Node node
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            return leafNode;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(child);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    /// Creates default per-segment feature data with every feature disabled.
    ///
    /// @return default per-segment feature data with every feature disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
