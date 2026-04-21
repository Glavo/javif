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
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TilePartitionTreeReader`.
@NotNullByDefault
final class TilePartitionTreeReaderTest {
    /// Verifies that one 64x64 tile can be expanded into a non-empty partition tree with in-bounds leaves.
    @Test
    void readsTilePartitionTree() {
        TilePartitionTreeReader reader = new TilePartitionTreeReader(
                createTileContext(FrameType.KEY, new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A})
        );

        TilePartitionTreeReader.Node[] roots = reader.readTile();

        assertEquals(1, roots.length);
        assertEquals(new BlockPosition(0, 0).x4(), roots[0].position().x4());
        assertEquals(new BlockPosition(0, 0).y4(), roots[0].position().y4());
        assertEquals(BlockSize.SIZE_64X64, roots[0].size());
        assertTrue(countLeaves(roots[0]) > 0);
        assertTrue(allLeavesInBounds(roots[0], 16, 16));
    }

    /// Verifies that clipped tiles can still be expanded into a partition tree without emitting out-of-bounds leaves.
    @Test
    void readsClippedTilePartitionTree() {
        TilePartitionTreeReader reader = new TilePartitionTreeReader(
                createClippedTileContext(new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18})
        );

        TilePartitionTreeReader.Node[] roots = reader.readTile();

        assertEquals(1, roots.length);
        assertFalse(hasInvalidLeafOrigin(roots[0], 10, 7));
    }

    /// Counts the number of leaf nodes in one partition tree.
    ///
    /// @param node the tree node to count
    /// @return the number of leaf nodes in one partition tree
    private static int countLeaves(TilePartitionTreeReader.Node node) {
        if (node instanceof TilePartitionTreeReader.LeafNode) {
            return 1;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        int count = 0;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            count += countLeaves(child);
        }
        return count;
    }

    /// Returns whether every leaf node starts within the supplied tile bounds.
    ///
    /// @param node the tree node to validate
    /// @param tileWidth4 the tile width in 4x4 units
    /// @param tileHeight4 the tile height in 4x4 units
    /// @return whether every leaf node starts within the supplied tile bounds
    private static boolean allLeavesInBounds(TilePartitionTreeReader.Node node, int tileWidth4, int tileHeight4) {
        return !hasInvalidLeafOrigin(node, tileWidth4, tileHeight4);
    }

    /// Returns whether any leaf node starts outside the supplied tile bounds.
    ///
    /// @param node the tree node to validate
    /// @param tileWidth4 the tile width in 4x4 units
    /// @param tileHeight4 the tile height in 4x4 units
    /// @return whether any leaf node starts outside the supplied tile bounds
    private static boolean hasInvalidLeafOrigin(TilePartitionTreeReader.Node node, int tileWidth4, int tileHeight4) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            return leafNode.header().position().x4() >= tileWidth4
                    || leafNode.header().position().y4() >= tileHeight4;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            if (hasInvalidLeafOrigin(child, tileWidth4, tileHeight4)) {
                return true;
            }
        }
        return false;
    }

    /// Creates a simple 64x64 tile context used by partition-tree tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param payload the collected tile entropy payload
    /// @return a simple 64x64 tile context used by partition-tree tests
    private static TileDecodeContext createTileContext(FrameType frameType, byte[] payload) {
        return createContext(frameType, 64, 64, payload);
    }

    /// Creates a clipped tile context whose coded size is smaller than one full 64x64 superblock.
    ///
    /// @param payload the collected tile entropy payload
    /// @return a clipped tile context whose coded size is smaller than one full 64x64 superblock
    private static TileDecodeContext createClippedTileContext(byte[] payload) {
        return createContext(FrameType.KEY, 40, 28, payload);
    }

    /// Creates a synthetic tile context used by partition-tree tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param payload the collected tile entropy payload
    /// @return a synthetic tile context used by partition-tree tests
    private static TileDecodeContext createContext(FrameType frameType, int codedWidth, int codedHeight, byte[] payload) {
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
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        PixelFormat.I420,
                        0,
                        true,
                        true,
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
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, 8),
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
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
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

    /// Creates default per-segment data with all features disabled.
    ///
    /// @return default per-segment data with all features disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
