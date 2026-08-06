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
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.testutil.HexFixtureResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileTransformLayoutReader`.
@NotNullByDefault
final class TileTransformLayoutReaderTest {
    /// One fixed key-frame payload whose first switchable transform-depth symbol decodes to `2`.
    private static final byte @Unmodifiable [] KEY_FRAME_DEPTH_TWO_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/key-transform-depth-2.hex");

    /// One fixed transform-only payload that splits an 8x8 inter block into four 4x4 units.
    private static final byte @Unmodifiable [] INTER_8X8_SPLIT_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/inter-transform-8x8-split.hex");

    /// One fixed transform-only payload that splits a 16x16 inter block into four 8x8 units.
    private static final byte @Unmodifiable [] INTER_16X16_SPLIT_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/inter-transform-16x16-split.hex");

    /// Creates one non-skipped single-reference inter header for a transform-layout test.
    ///
    /// @param blockSize the tested block size
    /// @return one synthetic inter block header with no preceding entropy syntax
    private static TileBlockHeaderReader.BlockHeader interBlockHeader(BlockSize blockSize) {
        return interBlockHeader(new BlockPosition(0, 0), blockSize, false);
    }

    /// Creates one synthetic single-reference inter header for a transform-layout test.
    ///
    /// @param position the tested block position
    /// @param blockSize the tested block size
    /// @param skip whether the synthetic block is skipped
    /// @return one synthetic inter block header with no preceding entropy syntax
    private static TileBlockHeaderReader.BlockHeader interBlockHeader(
            BlockPosition position,
            BlockSize blockSize,
            boolean skip
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                false,
                skip,
                false,
                false,
                false,
                false,
                0,
                -1,
                false,
                0,
                null,
                null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                null,
                0,
                0,
                0,
                0
        );
    }

    /// Verifies that switchable key-frame transform syntax selects a smaller repeated luma transform size.
    @Test
    void readsSwitchableKeyFrameTransformLayout() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.SWITCHABLE,
                false,
                KEY_FRAME_DEPTH_TWO_PAYLOAD,
                64,
                64
        );
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_32X32, neighborContext, false);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);
        neighborContext.updateFromBlockHeader(header);
        neighborContext.updateDefaultTransformContext(position, BlockSize.SIZE_32X32);
        TransformSize uniformLumaTransformSize = Objects.requireNonNull(layout.uniformLumaTransformSize(), "uniformLumaTransformSize");
        neighborContext.updateIntraTransformContext(position, BlockSize.SIZE_32X32, uniformLumaTransformSize);

        assertEquals(TransformSize.TX_8X8, uniformLumaTransformSize);
        assertNull(layout.chromaTransformSize());
        assertEquals(16, layout.lumaUnits().length);
        assertEquals(0, layout.lumaUnits()[0].position().x4());
        assertEquals(0, layout.lumaUnits()[0].position().y4());
        assertEquals(6, layout.lumaUnits()[15].position().x4());
        assertEquals(6, layout.lumaUnits()[15].position().y4());
        assertEquals(TransformSize.TX_8X8, layout.lumaUnits()[15].size());
        assertEquals(0, neighborContext.transformSizeContext(new BlockPosition(8, 0), TransformSize.TX_32X32));
    }

    /// Verifies that lossless blocks expand to repeated 4x4 luma units and 4x4 chroma transforms.
    @Test
    void buildsLosslessTransformLayoutFromRepeatedFourByFourUnits() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                Av1ChromaFormat.YUV420,
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                true,
                new byte[8],
                64,
                64
        );
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext, false);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);

        assertEquals(TransformSize.TX_4X4, layout.uniformLumaTransformSize());
        assertEquals(TransformSize.TX_4X4, layout.chromaTransformSize());
        assertEquals(16, layout.lumaUnits().length);
        assertEquals(4, layout.chromaUnits().length);
        assertEquals(0, layout.chromaUnits()[0].position().x4());
        assertEquals(0, layout.chromaUnits()[0].position().y4());
        assertEquals(2, layout.chromaUnits()[3].position().x4());
        assertEquals(2, layout.chromaUnits()[3].position().y4());
        assertEquals(TransformSize.TX_4X4, layout.chromaUnits()[3].size());
    }

    /// Verifies that a 128-pixel-wide lossless block completes its left 64x64 transform region
    /// before advancing to the right region.
    @Test
    void buildsWideLosslessTransformUnitsInSixtyFourSampleRegionOrder() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                true,
                new byte[8],
                128,
                64
        );
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TransformLayout layout = transformLayoutReader.read(
                interBlockHeader(BlockSize.SIZE_128X64),
                neighborContext
        );
        TransformUnit[] units = layout.lumaUnits();

        assertEquals(512, units.length);
        assertEquals(new BlockPosition(0, 0), units[0].position());
        assertEquals(new BlockPosition(15, 0), units[15].position());
        assertEquals(new BlockPosition(0, 1), units[16].position());
        assertEquals(new BlockPosition(15, 15), units[255].position());
        assertEquals(new BlockPosition(16, 0), units[256].position());
        assertEquals(new BlockPosition(31, 15), units[511].position());
    }

    /// Verifies that clipped `YUV422` layouts expose exact chroma transform units on the wider
    /// chroma plane.
    @Test
    void buildsClippedI422ChromaTransformUnits() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                Av1ChromaFormat.YUV422,
                FrameHeader.TransformMode.LARGEST,
                false,
                new byte[8],
                7,
                5
        );
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);
        TransformUnit[] chromaUnits = layout.chromaUnits();

        assertEquals(2, layout.visibleWidth4());
        assertEquals(2, layout.visibleHeight4());
        assertEquals(8, layout.visibleWidthPixels());
        assertEquals(8, layout.visibleHeightPixels());
        assertEquals(TransformSize.RTX_4X8, layout.chromaTransformSize());
        assertEquals(1, chromaUnits.length);
        assertEquals(0, chromaUnits[0].position().x4());
        assertEquals(0, chromaUnits[0].position().y4());
        assertEquals(TransformSize.RTX_4X8, chromaUnits[0].size());
    }

    /// Verifies that a smaller lossless `YUV444` chroma transform layout tiles the unsubsampled
    /// chroma plane in raster order.
    @Test
    void buildsLosslessI444ChromaTransformUnitsInRasterOrder() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                Av1ChromaFormat.YUV444,
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                true,
                new byte[8],
                8,
                8
        );
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);
        TransformUnit[] chromaUnits = layout.chromaUnits();

        assertEquals(TransformSize.TX_4X4, layout.chromaTransformSize());
        assertEquals(4, chromaUnits.length);
        assertEquals(0, chromaUnits[0].position().x4());
        assertEquals(0, chromaUnits[0].position().y4());
        assertEquals(1, chromaUnits[1].position().x4());
        assertEquals(0, chromaUnits[1].position().y4());
        assertEquals(0, chromaUnits[2].position().x4());
        assertEquals(1, chromaUnits[2].position().y4());
        assertEquals(1, chromaUnits[3].position().x4());
        assertEquals(1, chromaUnits[3].position().y4());
    }

    /// Verifies that switchable inter var-tx can split an 8x8 block into repeated 4x4 luma units.
    @Test
    void readsSwitchableInterEightByEightTransformTree() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.SWITCHABLE,
                false,
                INTER_8X8_SPLIT_PAYLOAD,
                64,
                64
        );
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header = interBlockHeader(BlockSize.SIZE_8X8);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);

        assertTrue(layout.variableLumaTransformTree());
        assertEquals(4, layout.lumaUnits().length);
        assertEquals(TransformSize.TX_4X4, layout.lumaUnits()[0].size());
        assertEquals(0, layout.lumaUnits()[0].position().x4());
        assertEquals(0, layout.lumaUnits()[0].position().y4());
        assertEquals(1, layout.lumaUnits()[1].position().x4());
        assertEquals(0, layout.lumaUnits()[1].position().y4());
        assertEquals(0, layout.lumaUnits()[2].position().x4());
        assertEquals(1, layout.lumaUnits()[2].position().y4());
        assertEquals(1, layout.lumaUnits()[3].position().x4());
        assertEquals(1, layout.lumaUnits()[3].position().y4());
    }

    /// Verifies that switchable inter var-tx can split a 16x16 block to four 8x8 luma units.
    @Test
    void readsSwitchableInterSixteenBySixteenTransformTree() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.SWITCHABLE,
                false,
                INTER_16X16_SPLIT_PAYLOAD,
                64,
                64
        );
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header = interBlockHeader(BlockSize.SIZE_16X16);
        TransformLayout layout = transformLayoutReader.read(header, neighborContext);

        assertTrue(layout.variableLumaTransformTree());
        assertEquals(4, layout.lumaUnits().length);
        assertEquals(TransformSize.TX_8X8, layout.lumaUnits()[0].size());
        assertEquals(0, layout.lumaUnits()[0].position().x4());
        assertEquals(0, layout.lumaUnits()[0].position().y4());
        assertEquals(2, layout.lumaUnits()[1].position().x4());
        assertEquals(0, layout.lumaUnits()[1].position().y4());
        assertEquals(0, layout.lumaUnits()[2].position().x4());
        assertEquals(2, layout.lumaUnits()[2].position().y4());
        assertEquals(2, layout.lumaUnits()[3].position().x4());
        assertEquals(2, layout.lumaUnits()[3].position().y4());
    }

    /// Verifies that a skipped lossless inter block stores its full block dimensions in the
    /// neighboring var-tx context while retaining a 4x4 transform layout.
    @Test
    void storesCodedDimensionsForSkippedLosslessInterBlock() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.SWITCHABLE,
                true,
                new byte[8],
                64,
                64
        );
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        neighborContext.updateInterTransformContext(
                new BlockPosition(2, 0),
                2,
                2,
                TransformSize.TX_4X4
        );

        TransformLayout layout = transformLayoutReader.read(
                interBlockHeader(new BlockPosition(0, 0), BlockSize.SIZE_8X16, true),
                neighborContext
        );

        assertEquals(TransformSize.TX_4X4, layout.uniformLumaTransformSize());
        assertEquals(8, layout.lumaUnits().length);
        assertEquals(1, neighborContext.interTransformSplitContext(
                new BlockPosition(2, 0),
                TransformSize.TX_8X8
        ));
    }

    /// Verifies that partition-tree leaves carry the decoded transform layout.
    @Test
    void partitionTreeLeafCarriesTransformLayout() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                Av1ChromaFormat.MONOCHROME,
                FrameHeader.TransformMode.LARGEST,
                false,
                new byte[8],
                64,
                64
        );
        TilePartitionTreeReader treeReader = new TilePartitionTreeReader(tileContext);

        TilePartitionTreeReader.Node[] roots = treeReader.readTile();
        TilePartitionTreeReader.LeafNode leafNode = firstLeaf(roots);
        TransformLayout transformLayout = leafNode.transformLayout();
        org.glavo.avif.internal.av1.model.ResidualLayout residualLayout = leafNode.residualLayout();
        TransformUnit[] lumaUnits = transformLayout.lumaUnits();

        assertNotNull(transformLayout);
        assertNotNull(residualLayout);
        assertEquals(TransformSize.TX_64X64, transformLayout.uniformLumaTransformSize());
        assertEquals(1, lumaUnits.length);
        assertEquals(1, residualLayout.lumaUnits().length);
        assertEquals(TransformSize.TX_64X64, residualLayout.lumaUnits()[0].size());
        assertEquals(0, lumaUnits[0].position().x4());
        assertEquals(0, lumaUnits[0].position().y4());
    }

    /// Creates one synthetic tile-local decode context used by transform-layout tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param chromaFormat the synthetic sequence pixel format
    /// @param transformMode the synthetic frame transform mode
    /// @param allLossless whether all segments are lossless
    /// @param payload the collected tile entropy payload
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @return one synthetic tile-local decode context used by transform-layout tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            Av1ChromaFormat chromaFormat,
            FrameHeader.TransformMode transformMode,
            boolean allLossless,
            byte[] payload,
            int codedWidth,
            int codedHeight
    ) {
        boolean chromaSubsamplingX = chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422;
        boolean chromaSubsamplingY = chromaFormat == Av1ChromaFormat.YUV420;
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
                        false,
                        false,
                        true,
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
                        chromaFormat == Av1ChromaFormat.MONOCHROME,
                        false,
                        2,
                        2,
                        2,
                        true,
                        chromaFormat,
                        0,
                        chromaSubsamplingX,
                        chromaSubsamplingY,
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
                frameType,
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
                new FrameHeader.SuperResolutionInfo(false, 8),
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
                        new int[]{0, (codedWidth + 63) / 64},
                        new int[]{0, (codedHeight + 63) / 64},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                allLossless ? defaultLosslessSegmentation() : defaultSegmentation(),
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                allLossless,
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
        return TileDecodeContext.create(assembly, 0);
    }

    /// Returns disabled segmentation info used by transform-layout tests.
    ///
    /// @return disabled segmentation info used by transform-layout tests
    private static FrameHeader.SegmentationInfo defaultSegmentation() {
        return new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]);
    }

    /// Returns lossless segmentation info used by transform-layout tests.
    ///
    /// @return lossless segmentation info used by transform-layout tests
    private static FrameHeader.SegmentationInfo defaultLosslessSegmentation() {
        boolean[] lossless = new boolean[8];
        int[] qIndex = new int[8];
        lossless[0] = true;
        return new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), lossless, qIndex);
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
}
