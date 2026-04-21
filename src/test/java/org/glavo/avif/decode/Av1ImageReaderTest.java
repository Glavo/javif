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
package org.glavo.avif.decode;

import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for `Av1ImageReader`.
@NotNullByDefault
final class Av1ImageReaderTest {
    /// Verifies that an empty stream returns end-of-stream instead of failing.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void readFrameReturnsNullAtEndOfStream() throws IOException {
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that `close()` is idempotent.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void closeIsIdempotent() throws IOException {
        Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        );
        reader.close();
        reader.close();
    }

    /// Verifies that operations fail after the reader has been closed.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void readFrameFailsAfterClose() throws IOException {
        Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        );
        reader.close();
        assertThrows(IOException.class, reader::readFrame);
    }

    /// Verifies that a stream containing only a parsed sequence header reaches end-of-stream.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void readFrameConsumesSequenceHeaderOnlyStream() throws IOException {
        byte[] stream = obu(1, reducedStillPicturePayload());
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that frame OBUs are rejected when no sequence header was seen first.
    @Test
    void readFrameRejectsFrameDataBeforeSequenceHeader() {
        byte[] stream = obu(3, new byte[]{0});
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.STATE_VIOLATION, exception.code());
    }

    /// Verifies that frame OBUs still report `NOT_IMPLEMENTED` after a valid sequence header.
    @Test
    void readFrameReportsNotImplementedAfterSequenceHeader() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, new byte[]{0})
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
    }

    /// Verifies that a parsed standalone frame header still reaches the decode-not-implemented boundary.
    @Test
    void readFrameParsesStandaloneFrameHeaderBeforeReportingNotImplemented() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
        assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
    }

    /// Verifies that the reader exposes the supplied immutable configuration.
    @Test
    void configReturnsSuppliedConfiguration() {
        Av1DecoderConfig config = Av1DecoderConfig.builder().threadCount(2).build();
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)),
                config
        )) {
            assertSame(config, reader.config());
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    /// Encodes a single self-delimited OBU.
    ///
    /// @param typeId the numeric OBU type identifier
    /// @param payload the OBU payload
    /// @return the encoded OBU bytes
    private static byte[] obu(int typeId, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((typeId << 3) | (1 << 1));
        writeLeb128(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Concatenates multiple byte arrays.
    ///
    /// @param arrays the byte arrays to concatenate
    /// @return the concatenated byte array
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    /// Writes an unsigned LEB128 value.
    ///
    /// @param output the destination byte stream
    /// @param value the value to encode
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

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(639, 10);
        writer.writeBits(359, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture key frame header payload.
    ///
    /// @return the reduced still-picture key frame header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Small MSB-first bit writer used to build AV1 test payloads.
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

        /// Writes trailing bits and byte alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
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
