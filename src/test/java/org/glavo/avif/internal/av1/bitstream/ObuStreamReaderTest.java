// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream;

import org.glavo.avif.av1.Av1DecodeErrorCode;
import org.glavo.avif.av1.Av1DecodeException;
import org.glavo.avif.av1.Av1DecodeStage;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `ObuStreamReader`.
@NotNullByDefault
final class ObuStreamReaderTest {
    /// Verifies that multiple self-delimited OBUs can be read from the same stream.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsMultipleObus() throws IOException {
        byte[] sequenceHeader = obu(ObuType.SEQUENCE_HEADER, false, 0, 0, new byte[]{1, 2, 3});
        byte[] metadata = obu(ObuType.METADATA, true, 5, 2, new byte[]{4, 5});
        byte[] stream = concat(sequenceHeader, metadata);

        try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
            ObuStreamReader reader = new ObuStreamReader(input);

            ObuPacket first = reader.readObu();
            assertNotNull(first);
            assertEquals(ObuType.SEQUENCE_HEADER, first.header().type());
            assertEquals(0, first.streamOffset());
            assertEquals(0, first.obuIndex());
            assertArrayEquals(new byte[]{1, 2, 3}, first.payload());

            ObuPacket second = reader.readObu();
            assertNotNull(second);
            assertEquals(ObuType.METADATA, second.header().type());
            assertEquals(5, second.header().temporalId());
            assertEquals(2, second.header().spatialId());
            assertEquals(sequenceHeader.length, second.streamOffset());
            assertEquals(1, second.obuIndex());
            assertArrayEquals(new byte[]{4, 5}, second.payload());

            assertNull(reader.readObu());
        }
    }

    /// Verifies that Annex B temporal units, frame units, and externally sized OBUs are traversed
    /// without exposing their length fields as OBU bytes.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsAnnexBTemporalAndFrameUnits() throws IOException {
        byte[] delimiter = annexBObu(ObuType.TEMPORAL_DELIMITER, false, 0, 0, new byte[0]);
        byte[] sequenceHeader = annexBObu(ObuType.SEQUENCE_HEADER, false, 0, 0, new byte[]{1, 2, 3});
        byte[] firstMetadata = annexBObu(ObuType.METADATA, true, 5, 2, new byte[]{4, 5});
        byte[] secondMetadata = annexBObu(ObuType.METADATA, false, 0, 0, new byte[]{6});
        byte[] stream = concat(
                annexBTemporalUnit(
                        annexBFrameUnit(delimiter, sequenceHeader),
                        annexBFrameUnit(firstMetadata)
                ),
                annexBTemporalUnit(annexBFrameUnit(secondMetadata))
        );

        try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
            ObuStreamReader reader = ObuStreamReader.forAnnexB(input);

            ObuPacket first = reader.readObu();
            assertNotNull(first);
            assertEquals(ObuType.TEMPORAL_DELIMITER, first.header().type());
            assertFalse(reader.atTemporalUnitBoundary());

            ObuPacket second = reader.readObu();
            assertNotNull(second);
            assertEquals(ObuType.SEQUENCE_HEADER, second.header().type());
            assertArrayEquals(new byte[]{1, 2, 3}, second.payload());
            assertFalse(reader.atTemporalUnitBoundary());

            ObuPacket third = reader.readObu();
            assertNotNull(third);
            assertEquals(ObuType.METADATA, third.header().type());
            assertEquals(5, third.header().temporalId());
            assertEquals(2, third.header().spatialId());
            assertArrayEquals(new byte[]{4, 5}, third.payload());
            assertEquals(2, third.obuIndex());
            assertTrue(reader.atTemporalUnitBoundary());

            ObuPacket fourth = reader.readObu();
            assertNotNull(fourth);
            assertArrayEquals(new byte[]{6}, fourth.payload());
            assertTrue(reader.atTemporalUnitBoundary());
            assertNull(reader.readObu());
        }
    }

    /// Verifies that Annex B accepts an enclosed OBU that redundantly carries its own payload size.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsAnnexBObuWithInternalSizeField() throws IOException {
        byte[] sizedObu = obu(ObuType.METADATA, false, 0, 0, new byte[]{7, 8});
        byte[] stream = annexBTemporalUnit(annexBFrameUnit(sizedObu));

        try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
            ObuPacket packet = ObuStreamReader.forAnnexB(input).readObu();

            assertNotNull(packet);
            assertEquals(ObuType.METADATA, packet.header().type());
            assertTrue(packet.header().hasSizeField());
            assertArrayEquals(new byte[]{7, 8}, packet.payload());
        }
    }

    /// Verifies that Annex B rejects an OBU whose external length crosses its frame-unit boundary.
    @Test
    void rejectsAnnexBObuThatCrossesFrameUnit() {
        byte[] malformedFrame = lengthDelimited(concat(new byte[]{4}, new byte[]{0x28}));
        byte[] stream = annexBTemporalUnit(malformedFrame);

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
                ObuStreamReader.forAnnexB(input).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.INVALID_BITSTREAM, exception.code());
    }

    /// Verifies that an Annex B external OBU length must exactly match a redundant internal size.
    @Test
    void rejectsAnnexBObuWithMismatchedInternalSize() {
        byte[] sizedObu = obu(ObuType.METADATA, false, 0, 0, new byte[]{7});
        byte[] stream = annexBTemporalUnit(annexBFrameUnit(concat(sizedObu, new byte[]{8})));

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(
                    ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
            )) {
                ObuStreamReader.forAnnexB(input).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.INVALID_BITSTREAM, exception.code());
    }

    /// Verifies that physical EOF inside an otherwise consistent Annex B nesting is reported as
    /// truncation rather than a clean temporal-unit boundary.
    @Test
    void reportsUnexpectedEofForTruncatedAnnexBPayload() {
        byte[] stream = new byte[]{4, 3, 2, 0x28};

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
                ObuStreamReader.forAnnexB(input).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.UNEXPECTED_EOF, exception.code());
    }

    /// Verifies that malformed OBU headers are rejected.
    @Test
    void rejectsInvalidHeader() {
        byte[] stream = new byte[]{(byte) 0x80};

        assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
                new ObuStreamReader(input).readObu();
            }
        });
    }

    /// Verifies that an OBU without a size field consumes the remainder of its bounded unit.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsFinalObuWithoutSizeFieldFromBoundedUnit() throws IOException {
        byte[] stream = new byte[]{0b0001_1000, 1, 2, 3};

        try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
            ObuStreamReader reader = new ObuStreamReader(input);
            ObuPacket packet = reader.readObu();

            assertNotNull(packet);
            assertEquals(ObuType.FRAME_HEADER, packet.header().type());
            assertFalse(packet.header().hasSizeField());
            assertArrayEquals(new byte[]{1, 2, 3}, packet.payload());
            assertNull(reader.readObu());
        }
    }

    /// Verifies that size-less final OBUs honor each boundary in a sequence of input units.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsFinalObuWithoutSizeFieldFromEachBoundedUnit() throws IOException {
        ByteBuffer first = ByteBuffer.wrap(new byte[]{0b0001_1000}).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer second = ByteBuffer.wrap(new byte[]{0b0010_1010, 1, 3}).order(ByteOrder.LITTLE_ENDIAN);

        try (BufferedInput input = new BufferedInput.OfByteBuffers(new ByteBuffer[]{first, second})) {
            ObuStreamReader reader = new ObuStreamReader(input);
            ObuPacket firstPacket = reader.readObu();
            ObuPacket secondPacket = reader.readObu();

            assertNotNull(firstPacket);
            assertEquals(ObuType.FRAME_HEADER, firstPacket.header().type());
            assertArrayEquals(new byte[0], firstPacket.payload());
            assertNotNull(secondPacket);
            assertEquals(ObuType.METADATA, secondPacket.header().type());
            assertArrayEquals(new byte[]{3}, secondPacket.payload());
            assertNull(reader.readObu());
        }
    }

    /// Verifies that EOF delimits a final OBU without a size field when no unit length is known.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsFinalObuWithoutSizeFieldFromUnboundedInput() throws IOException {
        byte[] stream = new byte[]{0b0001_1000, 1};

        try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
            ObuStreamReader reader = new ObuStreamReader(input);
            ObuPacket packet = reader.readObu();

            assertNotNull(packet);
            assertEquals(ObuType.FRAME_HEADER, packet.header().type());
            assertFalse(packet.header().hasSizeField());
            assertArrayEquals(new byte[]{1}, packet.payload());
            assertNull(reader.readObu());
        }
    }

    /// Verifies that a sized OBU cannot consume bytes from a following bounded unit.
    @Test
    void rejectsSizedObuThatCrossesBoundedUnit() {
        ByteBuffer first = ByteBuffer.wrap(new byte[]{0b0001_1010, 2, 1}).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer second = ByteBuffer.wrap(new byte[]{2}).order(ByteOrder.LITTLE_ENDIAN);

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffers(new ByteBuffer[]{first, second})) {
                new ObuStreamReader(input).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.UNEXPECTED_EOF, exception.code());
    }

    /// Verifies that truncated payloads report unexpected EOF.
    @Test
    void reportsUnexpectedEofForTruncatedPayload() {
        byte[] stream = new byte[]{0b0000_1010, 0x02, 0x01};

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
                new ObuStreamReader(input).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.UNEXPECTED_EOF, exception.code());
    }

    /// Verifies that a declared payload is rejected before allocating or reading past the limit.
    @Test
    void rejectsDeclaredPayloadBeforeAllocationWhenItExceedsLimit() {
        byte[] stream = new byte[]{0b0000_1010, 5};

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
                new ObuStreamReader(input, 4).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED, exception.code());
        assertEquals(Av1DecodeStage.OBU_READ, exception.stage());
    }

    /// Verifies that a size-less final OBU stops at the configured payload limit.
    @Test
    void rejectsSizeLessPayloadWhenItExceedsLimit() {
        byte[] stream = new byte[]{0b0001_1000, 1, 2};

        Av1DecodeException exception = assertThrows(Av1DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))) {
                new ObuStreamReader(input, 1).readObu();
            }
        });

        assertEquals(Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED, exception.code());
        assertEquals(Av1DecodeStage.OBU_READ, exception.stage());
    }

    /// Encodes a single self-delimited OBU.
    ///
    /// @param type the OBU type
    /// @param extensionFlag whether to emit an extension header
    /// @param temporalId the temporal layer identifier
    /// @param spatialId the spatial layer identifier
    /// @param payload the raw payload bytes
    /// @return the encoded OBU bytes
    private static byte[] obu(
            ObuType type,
            boolean extensionFlag,
            int temporalId,
            int spatialId,
            byte[] payload
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int header = type.id() << 3;
        if (extensionFlag) {
            header |= 1 << 2;
        }
        header |= 1 << 1;
        output.write(header);

        if (extensionFlag) {
            output.write((temporalId << 5) | (spatialId << 3));
        }

        writeLeb128(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Encodes one Annex B OBU without an internal payload-size field.
    ///
    /// @param type the OBU type
    /// @param extensionFlag whether to emit an extension header
    /// @param temporalId the temporal layer identifier
    /// @param spatialId the spatial layer identifier
    /// @param payload the raw payload bytes
    /// @return the OBU header and payload without its external length
    private static byte[] annexBObu(
            ObuType type,
            boolean extensionFlag,
            int temporalId,
            int spatialId,
            byte[] payload
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int header = type.id() << 3;
        if (extensionFlag) {
            header |= 1 << 2;
        }
        output.write(header);
        if (extensionFlag) {
            output.write((temporalId << 5) | (spatialId << 3));
        }
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Encodes one Annex B frame unit containing the supplied OBUs.
    ///
    /// @param obus the complete OBUs without external lengths
    /// @return the encoded frame unit including its length field
    private static byte[] annexBFrameUnit(byte[]... obus) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (byte[] obu : obus) {
            writeLeb128(payload, obu.length);
            payload.writeBytes(obu);
        }
        return lengthDelimited(payload.toByteArray());
    }

    /// Encodes one Annex B temporal unit containing the supplied frame units.
    ///
    /// @param frameUnits the complete frame units including their length fields
    /// @return the encoded temporal unit including its length field
    private static byte[] annexBTemporalUnit(byte[]... frameUnits) {
        return lengthDelimited(concat(frameUnits));
    }

    /// Prefixes one byte sequence with its unsigned LEB128 length.
    ///
    /// @param payload the bytes to delimit
    /// @return the length-delimited byte sequence
    private static byte[] lengthDelimited(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeLeb128(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Concatenates multiple byte arrays.
    ///
    /// @param arrays the byte arrays to concatenate
    /// @return the concatenated bytes
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    /// Writes an unsigned LEB128 value.
    ///
    /// @param output the destination stream
    /// @param value the unsigned value to encode
    private static void writeLeb128(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while (true) {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                output.write(next | 0x80);
            } else {
                output.write(next);
                return;
            }
        }
    }
}
