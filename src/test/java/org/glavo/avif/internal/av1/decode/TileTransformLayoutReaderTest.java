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
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests for `TileTransformLayoutReader`.
@NotNullByDefault
final class TileTransformLayoutReaderTest {
    /// Verifies that switchable key-frame transform syntax selects a smaller repeated luma transform size.
    @Test
    void readsSwitchableKeyFrameTransformLayout() {
        byte[] payload = findPayloadForKeyFrameTransformDepth(2);
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                PixelFormat.I400,
                FrameHeader.TransformMode.SWITCHABLE,
                false,
                payload,
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
        assertEquals(0, neighborContext.transformSizeContext(new BlockPosition(8, 0), TransformSize.TX_32X32));
    }

    /// Verifies that lossless blocks expand to repeated 4x4 luma units and 4x4 chroma transforms.
    @Test
    void buildsLosslessTransformLayoutFromRepeatedFourByFourUnits() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                PixelFormat.I420,
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
    }

    /// Verifies that partition-tree leaves carry the decoded transform layout.
    @Test
    void partitionTreeLeafCarriesTransformLayout() {
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                PixelFormat.I400,
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
        TransformUnit[] lumaUnits = transformLayout.lumaUnits();

        assertNotNull(transformLayout);
        assertEquals(TransformSize.TX_64X64, transformLayout.uniformLumaTransformSize());
        assertEquals(1, lumaUnits.length);
        assertEquals(0, lumaUnits[0].position().x4());
        assertEquals(0, lumaUnits[0].position().y4());
    }

    /// Finds a small payload whose first key-frame transform-depth symbol matches the requested depth.
    ///
    /// @param expectedDepth the requested transform depth
    /// @return a small payload whose first key-frame transform-depth symbol matches the requested depth
    private static byte[] findPayloadForKeyFrameTransformDepth(int expectedDepth) {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
                    LumaIntraPredictionMode yMode = LumaIntraPredictionMode.fromSymbolIndex(
                            oracleDecoder.decodeSymbolAdapt(
                                    oracleCdf.mutableKeyFrameYModeCdf(
                                            LumaIntraPredictionMode.DC.contextIndex(),
                                            LumaIntraPredictionMode.DC.contextIndex()
                                    ),
                                    12
                            )
                    );
                    if (yMode.isDirectional()) {
                        oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(yMode.angleDeltaContextIndex()), 6);
                    }
                    int depth = oracleDecoder.decodeSymbolAdapt(
                            oracleCdf.mutableTransformSizeCdf(TransformSize.TX_32X32.maxSquareLevel() - 1, 0),
                            Math.min(TransformSize.TX_32X32.maxSquareLevel(), 2)
                    );
                    if (depth == expectedDepth) {
                        return payload;
                    }
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced the requested key-frame transform depth");
    }

    /// Creates one synthetic tile-local decode context used by transform-layout tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param transformMode the synthetic frame transform mode
    /// @param allLossless whether all segments are lossless
    /// @param payload the collected tile entropy payload
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @return one synthetic tile-local decode context used by transform-layout tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            PixelFormat pixelFormat,
            FrameHeader.TransformMode transformMode,
            boolean allLossless,
            byte[] payload,
            int codedWidth,
            int codedHeight
    ) {
        boolean chromaSubsamplingX = pixelFormat == PixelFormat.I420 || pixelFormat == PixelFormat.I422;
        boolean chromaSubsamplingY = pixelFormat == PixelFormat.I420;
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
                        pixelFormat == PixelFormat.I400,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
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
                        new int[]{0, 1},
                        new int[]{0, 1},
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
