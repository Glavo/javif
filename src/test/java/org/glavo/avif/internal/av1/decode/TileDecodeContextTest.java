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
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests for `TileDecodeContext`.
@NotNullByDefault
final class TileDecodeContextTest {
    /// Verifies that tile-local state derives the expected geometry and copies the supplied base CDF context.
    @Test
    void createsContextWithExpectedGeometryAndIndependentCdfState() {
        CdfContext baseContext = CdfContext.createDefault();
        FrameAssembly assembly = testAssembly(
                true,
                false,
                350,
                300,
                new int[]{0, 1, 3},
                new int[]{0, 1, 3},
                new byte[][]{
                        {0x00},
                        {0x01},
                        {0x02},
                        {0x12, 0x34, 0x56, 0x78, (byte) 0x9A}
                }
        );

        TileDecodeContext context = TileDecodeContext.create(assembly, 3, baseContext);

        assertEquals(3, context.tileIndex());
        assertEquals(1, context.tileRow());
        assertEquals(1, context.tileColumn());
        assertEquals(128, context.superblockSize());
        assertEquals(1, context.columnStartSuperblock());
        assertEquals(3, context.columnEndSuperblock());
        assertEquals(1, context.rowStartSuperblock());
        assertEquals(3, context.rowEndSuperblock());
        assertEquals(128, context.startX());
        assertEquals(350, context.endX());
        assertEquals(128, context.startY());
        assertEquals(300, context.endY());
        assertEquals(222, context.width());
        assertEquals(172, context.height());
        assertEquals(3, context.tileBitstream().tileIndex());
        assertTrue(context.msacDecoder().allowCdfUpdate());
        assertFalse(context.msacDecoder().decodeBooleanEqui());

        assertNotSame(baseContext, context.cdfContext());
        assertNotSame(baseContext.mutableSkipCdf(0), context.cdfContext().mutableSkipCdf(0));
        context.cdfContext().mutableSkipCdf(0)[0] = 42;
        assertEquals(1097, baseContext.mutableSkipCdf(0)[0]);
    }

    /// Verifies that frame-level `disable_cdf_update` propagates into the tile arithmetic decoder and 64x64 geometry.
    @Test
    void usesFrameDisableCdfUpdateAnd64x64Superblocks() {
        FrameAssembly assembly = testAssembly(
                false,
                true,
                250,
                190,
                new int[]{0, 2, 4},
                new int[]{0, 1, 3},
                new byte[][]{
                        {0x00},
                        {0x01},
                        {(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18},
                        {0x03}
                }
        );

        TileDecodeContext context = TileDecodeContext.create(assembly, 2);

        assertEquals(64, context.superblockSize());
        assertEquals(1, context.tileRow());
        assertEquals(0, context.tileColumn());
        assertEquals(0, context.startX());
        assertEquals(128, context.endX());
        assertEquals(64, context.startY());
        assertEquals(190, context.endY());
        assertFalse(context.msacDecoder().allowCdfUpdate());
        assertTrue(context.msacDecoder().decodeBooleanEqui());
    }

    /// Verifies that custom temporal motion fields are preserved and validated against tile geometry.
    @Test
    void createsContextWithCustomTemporalMotionField() {
        FrameAssembly assembly = testAssembly(
                false,
                false,
                128,
                128,
                new int[]{0, 2},
                new int[]{0, 2},
                new byte[][]{
                        {0x00}
                }
        );
        TileDecodeContext.TemporalMotionField temporalMotionField = new TileDecodeContext.TemporalMotionField(16, 16);
        TileDecodeContext.TemporalMotionBlock temporalBlock = TileDecodeContext.TemporalMotionBlock.singleReference(
                0,
                InterMotionVector.resolved(new MotionVector(12, -4))
        );
        temporalMotionField.setBlock(3, 4, temporalBlock);

        TileDecodeContext context = TileDecodeContext.create(assembly, 0, temporalMotionField);

        assertSame(temporalMotionField, context.temporalMotionField());
        assertNotSame(temporalMotionField, context.decodedTemporalMotionField());
        assertSame(temporalBlock, context.temporalMotionField().block(3, 4));
        assertEquals(temporalMotionField.width8(), context.decodedTemporalMotionField().width8());
        assertEquals(temporalMotionField.height8(), context.decodedTemporalMotionField().height8());
        assertTrue(context.decodedTemporalMotionField().block(3, 4) == null);
    }

    /// Creates a synthetic assembled frame with one tile group that already covers all tiles.
    ///
    /// @param use128x128Superblocks whether the synthetic sequence uses 128x128 superblocks
    /// @param disableCdfUpdate whether the synthetic frame disables CDF updates
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param columnStarts the tile-column start superblock coordinates
    /// @param rowStarts the tile-row start superblock coordinates
    /// @param tilePayloads the per-tile entropy payload bytes in frame order
    /// @return a synthetic assembled frame with one tile group that already covers all tiles
    private static FrameAssembly testAssembly(
            boolean use128x128Superblocks,
            boolean disableCdfUpdate,
            int codedWidth,
            int codedHeight,
            int[] columnStarts,
            int[] rowStarts,
            byte[][] tilePayloads
    ) {
        SequenceHeader sequenceHeader = sequenceHeader(use128x128Superblocks, codedWidth, codedHeight);
        FrameHeader frameHeader = frameHeader(disableCdfUpdate, codedWidth, codedHeight, columnStarts, rowStarts);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        TileBitstream[] tiles = new TileBitstream[tilePayloads.length];
        for (int i = 0; i < tilePayloads.length; i++) {
            tiles[i] = new TileBitstream(i, tilePayloads[i], 0, tilePayloads[i].length);
        }
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, tilePayloads.length - 1, tilePayloads.length),
                0,
                0,
                tiles
        );
        return assembly;
    }

    /// Creates a minimal sequence header for tile-geometry tests.
    ///
    /// @param use128x128Superblocks whether 128x128 superblocks are enabled
    /// @param maxWidth the sequence maximum coded width
    /// @param maxHeight the sequence maximum coded height
    /// @return a minimal sequence header for tile-geometry tests
    private static SequenceHeader sequenceHeader(boolean use128x128Superblocks, int maxWidth, int maxHeight) {
        return new SequenceHeader(
                0,
                maxWidth,
                maxHeight,
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
                        use128x128Superblocks,
                        false,
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
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        AvifPixelFormat.I420,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates a minimal frame header for tile-geometry tests.
    ///
    /// @param disableCdfUpdate whether the synthetic frame disables CDF updates
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param columnStarts the tile-column start superblock coordinates
    /// @param rowStarts the tile-row start superblock coordinates
    /// @return a minimal frame header for tile-geometry tests
    private static FrameHeader frameHeader(
            boolean disableCdfUpdate,
            int codedWidth,
            int codedHeight,
            int[] columnStarts,
            int[] rowStarts
    ) {
        int columns = columnStarts.length - 1;
        int rows = rowStarts.length - 1;
        int log2Cols = columns == 1 ? 0 : 1;
        int log2Rows = rows == 1 ? 0 : 1;

        return new FrameHeader(
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
                disableCdfUpdate,
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
                        log2Cols,
                        log2Cols,
                        log2Cols,
                        columns,
                        log2Rows,
                        log2Rows,
                        log2Rows,
                        rows,
                        columnStarts,
                        rowStarts,
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
