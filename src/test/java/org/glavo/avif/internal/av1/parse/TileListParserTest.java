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

import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileList;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests AV1 tile-list syntax parsing and structural bounds.
@NotNullByDefault
final class TileListParserTest {
    /// Verifies big-endian entry fields, source tile indices, and exact coded-payload views.
    @Test
    void parsesTileListEntries() throws DecodeException {
        ObuPacket packet = tileListObu(new byte[]{
                0x01, 0x00, 0x00, 0x01,
                0x03, 0x01, 0x01, 0x00, 0x01, 0x55, 0x66,
                0x7f, 0x00, 0x00, 0x00, 0x00, 0x77
        });

        TileList tileList = new TileListParser().parse(packet, sequenceHeader(), cameraFrameHeader(), false);

        assertEquals(2, tileList.outputTileColumns());
        assertEquals(1, tileList.outputTileRows());
        assertEquals(2, tileList.entries().size());
        TileList.Entry first = tileList.entries().get(0);
        assertEquals(3, first.anchorFrameIndex());
        assertEquals(1, first.tileRow());
        assertEquals(1, first.tileColumn());
        assertEquals(3, first.bitstream().tileIndex());
        assertArrayEquals(new byte[]{0x55, 0x66}, first.bitstream().copyBytes());
        TileList.Entry second = tileList.entries().get(1);
        assertEquals(127, second.anchorFrameIndex());
        assertEquals(0, second.bitstream().tileIndex());
        assertArrayEquals(new byte[]{0x77}, second.bitstream().copyBytes());
    }

    /// Verifies that the entry count cannot exceed the rectangular output grid.
    @Test
    void rejectsEntryCountBeyondOutputGrid() {
        ObuPacket packet = tileListObu(new byte[]{0x00, 0x00, 0x00, 0x01});

        assertThrows(
                DecodeException.class,
                () -> new TileListParser().parse(packet, sequenceHeader(), cameraFrameHeader(), false)
        );
    }

    /// Verifies anchor, source-grid, payload-length, and trailing-byte validation.
    @Test
    void rejectsInvalidEntryFieldsAndPayloadBounds() {
        TileListParser parser = new TileListParser();
        SequenceHeader sequenceHeader = sequenceHeader();
        FrameHeader frameHeader = cameraFrameHeader();

        assertThrows(DecodeException.class, () -> parser.parse(
                tileListObu(new byte[]{0, 0, 0, 0, (byte) 128, 0, 0, 0, 0, 1}),
                sequenceHeader,
                frameHeader,
                false
        ));
        assertThrows(DecodeException.class, () -> parser.parse(
                tileListObu(new byte[]{0, 0, 0, 0, 0, 2, 0, 0, 0, 1}),
                sequenceHeader,
                frameHeader,
                false
        ));
        assertThrows(DecodeException.class, () -> parser.parse(
                tileListObu(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 1, 1}),
                sequenceHeader,
                frameHeader,
                false
        ));
        assertThrows(DecodeException.class, () -> parser.parse(
                tileListObu(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2}),
                sequenceHeader,
                frameHeader,
                false
        ));
    }

    /// Wraps raw bytes in one synthetic tile-list OBU.
    ///
    /// @param payload the raw tile-list payload
    /// @return the synthetic OBU packet
    private static ObuPacket tileListObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.TILE_LIST, false, true, 0, 0), payload, 11, 7);
    }

    /// Creates a minimal sequence accepted by non-strict tile-list parsing.
    ///
    /// @return the synthetic sequence header
    private static SequenceHeader sequenceHeader() {
        return new SequenceHeader(
                0,
                128,
                128,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(0, 0, 10, 0, false, false, false, null)
                },
                false,
                false,
                7,
                7,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        false,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        4,
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
                        false,
                        AvifPixelFormat.I420,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates a minimal 2x2-tile camera frame for structural parser tests.
    ///
    /// @return the synthetic camera frame header
    private static FrameHeader cameraFrameHeader() {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.INTER,
                true,
                false,
                true,
                true,
                false,
                true,
                false,
                7,
                0,
                0,
                new FrameHeader.FrameSize(128, 128, 128, 128, 128),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        0,
                        1,
                        1,
                        1,
                        2,
                        1,
                        1,
                        1,
                        2,
                        new int[]{0, 1, 2},
                        new int[]{0, 1, 2},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                new FrameHeader.SegmentationInfo(
                        false,
                        false,
                        false,
                        false,
                        defaultSegments(),
                        new boolean[8],
                        new int[8]
                ),
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

    /// Creates default segment state with every feature disabled.
    ///
    /// @return the default segment array
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
