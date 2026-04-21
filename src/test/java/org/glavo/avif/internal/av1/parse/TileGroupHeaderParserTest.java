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

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileGroupHeaderParser`.
@NotNullByDefault
final class TileGroupHeaderParserTest {
    /// Verifies that a single-tile frame uses the implicit full-frame tile-group range.
    ///
    /// @throws IOException if the synthetic payload cannot be parsed
    @Test
    void parsesImplicitSingleTileRange() throws IOException {
        TileGroupHeaderParser parser = new TileGroupHeaderParser();
        FrameHeader frameHeader = tileGroupFrameHeader(1, 1, 0, 0);
        ObuPacket obu = tileGroupObu(new byte[0]);
        BitReader reader = new BitReader(obu.payload());

        TileGroupHeader header = parser.parse(reader, obu, frameHeader);

        assertFalse(header.explicitTilePositions());
        assertEquals(0, header.startTileIndex());
        assertEquals(0, header.endTileIndex());
        assertEquals(1, header.tileCount());
        assertTrue(reader.isByteAligned());
        assertEquals(0, reader.byteOffset());
    }

    /// Verifies that explicit tile positions can be parsed for multi-tile frames.
    ///
    /// @throws IOException if the synthetic payload cannot be parsed
    @Test
    void parsesExplicitTileRange() throws IOException {
        TileGroupHeaderParser parser = new TileGroupHeaderParser();
        FrameHeader frameHeader = tileGroupFrameHeader(2, 2, 1, 1);
        byte[] payload = tileGroupHeaderPayload(true, 1, 2, 2);
        ObuPacket obu = tileGroupObu(payload);
        BitReader reader = new BitReader(obu.payload());

        TileGroupHeader header = parser.parse(reader, obu, frameHeader);

        assertTrue(header.explicitTilePositions());
        assertEquals(1, header.startTileIndex());
        assertEquals(2, header.endTileIndex());
        assertEquals(2, header.tileCount());
        assertEquals(5, reader.bitOffset());
        reader.byteAlign();
        assertEquals(1, reader.byteOffset());
    }

    /// Wraps payload bytes in a synthetic tile-group OBU.
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
    /// @return a minimal frame header with the supplied tile layout
    private static FrameHeader tileGroupFrameHeader(int columns, int rows, int log2Cols, int log2Rows) {
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

    /// Creates a synthetic tile-group header payload.
    ///
    /// @param explicitTilePositions whether explicit tile positions are present
    /// @param startTileIndex the first covered tile index
    /// @param endTileIndex the last covered tile index
    /// @param indexBitCount the number of bits used for tile indices
    /// @return the encoded tile-group header payload
    private static byte[] tileGroupHeaderPayload(
            boolean explicitTilePositions,
            int startTileIndex,
            int endTileIndex,
            int indexBitCount
    ) {
        BitWriter writer = new BitWriter();
        writer.writeFlag(explicitTilePositions);
        if (explicitTilePositions) {
            writer.writeBits(startTileIndex, indexBitCount);
            writer.writeBits(endTileIndex, indexBitCount);
        }
        writer.padToByteBoundary();
        return writer.toByteArray();
    }

    /// Small MSB-first bit writer used to build tile-group test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Pads the current byte with zero bits until the next byte boundary.
        private void padToByteBoundary() {
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
