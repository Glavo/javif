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
package org.glavo.avif.internal.av1.parse;

import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TileDataEntry;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for `TileDataParser`.
@NotNullByDefault
final class TileDataParserTest {
    /// Verifies that a single tile consumes the full tile-data payload.
    @Test
    void parsesSingleTilePayload() throws DecodeException {
        TileDataParser parser = new TileDataParser();
        ObuPacket obu = tileGroupObu(new byte[]{0x12, 0x34});
        FrameHeader frameHeader = tileGroupFrameHeader(1, 1, 0, 0, 0);
        TileGroupHeader tileGroupHeader = new TileGroupHeader(false, 0, 0, 1);

        TileDataEntry[] entries = parser.parse(obu, frameHeader, tileGroupHeader, 0);

        assertEquals(1, entries.length);
        assertEquals(0, entries[0].tileIndex());
        assertEquals(0, entries[0].dataOffset());
        assertEquals(2, entries[0].dataLength());
    }

    /// Verifies that multi-tile groups use the frame-header `sizeBytes` table for all but the last tile.
    @Test
    void parsesMultipleTilesWithSizeTable() throws DecodeException {
        TileDataParser parser = new TileDataParser();
        ObuPacket obu = tileGroupObu(new byte[]{
                0x01,
                0x10, 0x11,
                0x00,
                0x20,
                0x30, 0x31, 0x32
        });
        FrameHeader frameHeader = tileGroupFrameHeader(2, 2, 1, 1, 1);
        TileGroupHeader tileGroupHeader = new TileGroupHeader(true, 0, 2, 4);

        TileDataEntry[] entries = parser.parse(obu, frameHeader, tileGroupHeader, 0);

        assertEquals(3, entries.length);
        assertEquals(0, entries[0].tileIndex());
        assertEquals(1, entries[0].dataOffset());
        assertEquals(2, entries[0].dataLength());
        assertEquals(1, entries[1].tileIndex());
        assertEquals(4, entries[1].dataOffset());
        assertEquals(1, entries[1].dataLength());
        assertEquals(2, entries[2].tileIndex());
        assertEquals(5, entries[2].dataOffset());
        assertEquals(3, entries[2].dataLength());
    }

    /// Verifies that tile size entries cannot point past the end of the OBU payload.
    @Test
    void rejectsOverlongTilePayload() {
        TileDataParser parser = new TileDataParser();
        ObuPacket obu = tileGroupObu(new byte[]{0x03, 0x10, 0x11});
        FrameHeader frameHeader = tileGroupFrameHeader(2, 1, 1, 0, 1);
        TileGroupHeader tileGroupHeader = new TileGroupHeader(true, 0, 1, 2);

        assertThrows(DecodeException.class, () -> parser.parse(obu, frameHeader, tileGroupHeader, 0));
    }

    /// Wraps raw bytes in a synthetic tile-group OBU.
    ///
    /// @param payload the raw tile-group payload bytes
    /// @return the synthetic tile-group OBU packet
    private static ObuPacket tileGroupObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), payload, 0, 0);
    }

    /// Creates a minimal frame header with the supplied tile layout.
    ///
    /// @param columns the tile column count
    /// @param rows the tile row count
    /// @param log2Cols the tile column log2
    /// @param log2Rows the tile row log2
    /// @param sizeBytes the number of bytes used for tile sizes
    /// @return a minimal frame header with the supplied tile layout
    private static FrameHeader tileGroupFrameHeader(int columns, int rows, int log2Cols, int log2Rows, int sizeBytes) {
        int[] columnStarts = new int[columns + 1];
        int[] rowStarts = new int[rows + 1];
        columnStarts[columns] = columns;
        rowStarts[rows] = rows;

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
                true,
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
                        sizeBytes,
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
