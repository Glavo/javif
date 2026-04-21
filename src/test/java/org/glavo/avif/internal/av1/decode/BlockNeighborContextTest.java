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
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `BlockNeighborContext`.
@NotNullByDefault
final class BlockNeighborContextTest {
    /// Verifies key-frame initialization and neighbor-state updates from one decoded leaf header.
    @Test
    void initializesAndUpdatesKeyFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.KEY));
        BlockPosition position = new BlockPosition(0, 0);

        assertEquals(16, context.tileWidth4());
        assertEquals(16, context.tileHeight4());
        assertEquals(0, context.intraContext(position));
        assertEquals(0, context.skipContext(position));
        assertEquals(LumaIntraPredictionMode.DC, context.aboveMode(0));
        assertEquals(LumaIntraPredictionMode.DC, context.leftMode(0));

        context.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                position,
                BlockSize.SIZE_16X16,
                true,
                true,
                true,
                false,
                true,
                3,
                LumaIntraPredictionMode.VERTICAL,
                UvIntraPredictionMode.PAETH,
                4,
                0,
                new int[]{10, 20, 30, 40},
                new int[0],
                new int[0],
                null,
                0,
                0,
                0,
                0
        ));

        assertEquals(1, context.skipContext(new BlockPosition(0, 4)));
        assertEquals(2, context.intraContext(new BlockPosition(0, 4)));
        assertEquals(LumaIntraPredictionMode.VERTICAL, context.aboveMode(0));
        assertEquals(LumaIntraPredictionMode.VERTICAL, context.leftMode(0));
        assertEquals(2, context.segmentPredictionContext(new BlockPosition(2, 2)));
        BlockNeighborContext.SegmentPrediction prediction = context.currentSegmentPrediction(new BlockPosition(2, 2));
        assertEquals(3, prediction.predictedSegmentId());
        assertEquals(2, prediction.context());
        assertEquals(4, context.abovePaletteSize(0));
        assertEquals(4, context.leftPaletteSize(0));
        assertEquals(0, context.aboveChromaPaletteSize(0));
        assertEquals(20, context.abovePaletteEntry(0, 0, 1));
        assertEquals(30, context.leftPaletteEntry(0, 0, 2));

        context.updatePartition(position, 8, 0x10, 0x18);
        assertEquals(2, context.partitionContext(3, new BlockPosition(0, 8)));
    }

    /// Verifies inter-frame initialization starts with non-intra neighbors.
    @Test
    void initializesInterFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));

        assertEquals(0, context.intraContext(new BlockPosition(4, 4)));
        assertEquals(0, context.skipContext(new BlockPosition(4, 4)));
        assertTrue(context.hasTopNeighbor(new BlockPosition(4, 4)));
        assertTrue(context.hasLeftNeighbor(new BlockPosition(4, 4)));
    }

    /// Creates a simple tile context used by neighbor-context tests.
    ///
    /// @param frameType the synthetic frame type
    /// @return a simple tile context used by neighbor-context tests
    private static TileDecodeContext testTileContext(FrameType frameType) {
        SequenceHeader sequenceHeader = new SequenceHeader(
                0,
                64,
                64,
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
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
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
                new TileBitstream[]{new TileBitstream(0, new byte[]{0x00}, 0, 1)}
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
