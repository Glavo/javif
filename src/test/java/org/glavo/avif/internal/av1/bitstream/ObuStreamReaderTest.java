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
package org.glavo.avif.internal.av1.bitstream;

import org.glavo.avif.decode.DecodeErrorCode;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /// Verifies that malformed OBU headers are rejected.
    @Test
    void rejectsInvalidHeader() {
        byte[] stream = new byte[]{(byte) 0x80};

        assertThrows(DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
                new ObuStreamReader(input).readObu();
            }
        });
    }

    /// Verifies that streams without OBU size fields are rejected.
    @Test
    void rejectsObuWithoutSizeField() {
        byte[] stream = new byte[]{0b0001_1000};

        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
                new ObuStreamReader(input).readObu();
            }
        });

        assertEquals(DecodeErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that truncated payloads report unexpected EOF.
    @Test
    void reportsUnexpectedEofForTruncatedPayload() {
        byte[] stream = new byte[]{0b0000_1010, 0x02, 0x01};

        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))) {
                new ObuStreamReader(input).readObu();
            }
        });

        assertEquals(DecodeErrorCode.UNEXPECTED_EOF, exception.code());
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
