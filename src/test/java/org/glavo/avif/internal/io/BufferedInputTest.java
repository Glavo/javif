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
package org.glavo.avif.internal.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `BufferedInput` and its built-in source adapters.
@NotNullByDefault
final class BufferedInputTest {
    /// Verifies that little-endian primitive reads work for `InputStream` sources.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsLittleEndianPrimitivesFromInputStream() throws IOException {
        try (BufferedInput input = new BufferedInput.OfInputStream(new ChunkedInputStream(sampleBytes(), 2))) {
            assertPrimitiveReads(input);
        }
    }

    /// Verifies that little-endian primitive reads work for `ReadableByteChannel` sources.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsLittleEndianPrimitivesFromByteChannel() throws IOException {
        try (BufferedInput input = new BufferedInput.OfByteChannel(new ChunkedReadableByteChannel(sampleBytes(), 3))) {
            assertPrimitiveReads(input);
        }
    }

    /// Verifies that little-endian primitive reads work for `ByteBuffer` sources.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsLittleEndianPrimitivesFromByteBuffer() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(sampleBytes()).order(ByteOrder.LITTLE_ENDIAN);
        try (BufferedInput input = new BufferedInput.OfByteBuffer(buffer)) {
            assertPrimitiveReads(input);
        }
    }

    /// Verifies that little-endian primitive reads cross `ByteBuffer` source boundaries.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsLittleEndianPrimitivesFromByteBuffers() throws IOException {
        byte[] bytes = sampleBytes();
        ByteBuffer[] buffers = new ByteBuffer[]{
                readOnlyLittleEndian(Arrays.copyOfRange(bytes, 0, 1)),
                readOnlyLittleEndian(Arrays.copyOfRange(bytes, 1, 7)),
                readOnlyLittleEndian(Arrays.copyOfRange(bytes, 7, 10)),
                readOnlyLittleEndian(Arrays.copyOfRange(bytes, 10, bytes.length))
        };

        try (BufferedInput input = new BufferedInput.OfByteBuffers(buffers)) {
            assertPrimitiveReads(input);
        }
    }

    /// Verifies that `readByteArray(int)` refills across internal buffer boundaries.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readByteArrayCrossesInternalBufferBoundary() throws IOException {
        byte[] bytes = new byte[9000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }

        try (BufferedInput input = new BufferedInput.OfInputStream(new ChunkedInputStream(bytes, 3))) {
            assertArrayEquals(bytes, input.readByteArray(bytes.length));
        }
    }

    /// Verifies that `skip(long)` consumes exactly the requested number of bytes.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void skipConsumesExactlyRequestedBytes() throws IOException {
        byte[] bytes = new byte[]{10, 11, 12, 13, 14, 15};

        try (BufferedInput input = new BufferedInput.OfByteChannel(new MemorySeekableByteChannel(bytes))) {
            input.skip(3);
            assertEquals(13, input.readUnsignedByte());
        }
    }

    /// Verifies that `skip(long)` fails when the source does not have enough bytes.
    @Test
    void skipFailsOnTruncatedInput() {
        byte[] bytes = new byte[]{1, 2, 3};
        assertThrows(EOFException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfInputStream(new ChunkedInputStream(bytes, 1))) {
                input.skip(4);
            }
        });
    }

    /// Verifies that `ensureBufferRemaining(int)` reports unexpected EOF.
    @Test
    void ensureBufferRemainingFailsOnUnexpectedEof() {
        byte[] bytes = new byte[]{1, 2, 3};
        assertThrows(EOFException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))) {
                input.ensureBufferRemaining(4);
            }
        });
    }

    /// Verifies that `close()` prevents further reads.
    ///
    /// @throws IOException if closing the source fails
    @Test
    void closePreventsFurtherReads() throws IOException {
        BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(sampleBytes()).order(ByteOrder.LITTLE_ENDIAN));
        input.close();

        assertThrows(IOException.class, input::readByte);
        assertThrows(IOException.class, () -> input.ensureBufferRemaining(1));
    }

    /// Verifies that wrapping a `ByteBuffer` does not mutate the caller-visible position or limit.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void byteBufferWrapperDoesNotMutateSourceBufferPositionOrLimit() throws IOException {
        ByteBuffer source = ByteBuffer.wrap(sampleBytes()).order(ByteOrder.LITTLE_ENDIAN);
        source.position(2);
        source.limit(10);

        int positionBefore = source.position();
        int limitBefore = source.limit();

        try (BufferedInput input = new BufferedInput.OfByteBuffer(source)) {
            input.readByteArray(4);
        }

        assertEquals(positionBefore, source.position());
        assertEquals(limitBefore, source.limit());
    }

    /// Verifies that wrapping multiple `ByteBuffer` instances does not mutate caller-visible state.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void byteBuffersWrapperDoesNotMutateSourceBufferPositionsOrLimits() throws IOException {
        ByteBuffer first = ByteBuffer.wrap(new byte[]{1, 2, 3, 4}).order(ByteOrder.LITTLE_ENDIAN);
        first.position(1);
        first.limit(3);
        ByteBuffer second = ByteBuffer.wrap(new byte[]{5, 6, 7, 8}).order(ByteOrder.LITTLE_ENDIAN);
        second.position(0);
        second.limit(2);

        int firstPositionBefore = first.position();
        int firstLimitBefore = first.limit();
        int secondPositionBefore = second.position();
        int secondLimitBefore = second.limit();

        try (BufferedInput input = new BufferedInput.OfByteBuffers(new ByteBuffer[]{first, second})) {
            assertArrayEquals(new byte[]{2, 3, 5, 6}, input.readByteArray(4));
        }

        assertEquals(firstPositionBefore, first.position());
        assertEquals(firstLimitBefore, first.limit());
        assertEquals(secondPositionBefore, second.position());
        assertEquals(secondLimitBefore, second.limit());
    }

    /// Verifies that zero-length operations are accepted.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void zeroLengthOperationsAreAccepted() throws IOException {
        try (BufferedInput input = new BufferedInput.OfInputStream(new ChunkedInputStream(sampleBytes(), 2))) {
            assertArrayEquals(new byte[0], input.readByteArray(0));
            input.skip(0);
            input.ensureBufferRemaining(0);
        }
    }

    /// Verifies that negative arguments are rejected.
    @Test
    void negativeArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(sampleBytes()).order(ByteOrder.LITTLE_ENDIAN))) {
                input.skip(-1);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(sampleBytes()).order(ByteOrder.LITTLE_ENDIAN))) {
                input.ensureBufferRemaining(-1);
            }
        });
    }

    /// Returns a fixed byte sequence used by primitive-read tests.
    ///
    /// @return the fixed byte sequence
    private static byte[] sampleBytes() {
        byte[] bytes = new byte[18];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }

    /// Creates a read-only little-endian buffer over the supplied bytes.
    ///
    /// @param bytes the source bytes
    /// @return a read-only little-endian buffer
    private static ByteBuffer readOnlyLittleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Asserts the primitive values read from the supplied input.
    ///
    /// @param input the buffered input under test
    /// @throws IOException if the test input cannot be read
    private static void assertPrimitiveReads(BufferedInput input) throws IOException {
        assertEquals(1, input.readUnsignedByte());
        assertEquals(0x0302, input.readUnsignedShortLE());
        assertEquals(0x060504, input.readUnsignedInt24LE());
        assertEquals(0x0A090807L, input.readUnsignedIntLE());
        assertEquals(0x1211100F0E0D0C0BL, input.readLongLE());
    }

    /// Input stream that exposes a fixed chunk size for each read operation.
    @NotNullByDefault
    private static final class ChunkedInputStream extends InputStream {
        /// The immutable source bytes.
        private final byte[] bytes;
        /// The maximum chunk size returned per read call.
        private final int chunkSize;
        /// The next unread byte offset.
        private int offset;
        /// Whether the stream is still open.
        private boolean open = true;

        /// Creates a chunked input stream.
        ///
        /// @param bytes the immutable source bytes
        /// @param chunkSize the maximum chunk size returned per read call
        private ChunkedInputStream(byte[] bytes, int chunkSize) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
            this.chunkSize = chunkSize;
        }

        /// Reads a single unsigned byte.
        ///
        /// @return the next unsigned byte, or `-1` at end-of-stream
        /// @throws IOException if the stream has been closed
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (offset >= bytes.length) {
                return -1;
            }
            return Byte.toUnsignedInt(bytes[offset++]);
        }

        /// Reads up to the configured chunk size into the destination array.
        ///
        /// @param destination the destination byte array
        /// @param destinationOffset the destination offset
        /// @param length the requested byte count
        /// @return the number of bytes read, or `-1` at end-of-stream
        /// @throws IOException if the stream has been closed
        @Override
        public int read(byte[] destination, int destinationOffset, int length) throws IOException {
            ensureOpen();
            if (offset >= bytes.length) {
                return -1;
            }
            int read = Math.min(Math.min(length, chunkSize), bytes.length - offset);
            System.arraycopy(bytes, offset, destination, destinationOffset, read);
            offset += read;
            return read;
        }

        /// Closes this input stream.
        @Override
        public void close() {
            open = false;
        }

        /// Ensures that the stream is still open.
        ///
        /// @throws IOException if the stream has been closed
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("stream is closed");
            }
        }
    }

    /// Readable byte channel that exposes a fixed chunk size for each read operation.
    @NotNullByDefault
    private static final class ChunkedReadableByteChannel implements ReadableByteChannel {
        /// The immutable source bytes.
        private final byte[] bytes;
        /// The maximum chunk size returned per read call.
        private final int chunkSize;
        /// The next unread byte offset.
        private int offset;
        /// Whether the channel is still open.
        private boolean open = true;

        /// Creates a chunked readable byte channel.
        ///
        /// @param bytes the immutable source bytes
        /// @param chunkSize the maximum chunk size returned per read call
        private ChunkedReadableByteChannel(byte[] bytes, int chunkSize) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
            this.chunkSize = chunkSize;
        }

        /// Reads bytes into the supplied destination buffer.
        ///
        /// @param destination the destination buffer
        /// @return the number of bytes read, or `-1` at end-of-stream
        /// @throws IOException if the channel has been closed
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (offset >= bytes.length) {
                return -1;
            }
            int read = Math.min(Math.min(destination.remaining(), chunkSize), bytes.length - offset);
            destination.put(bytes, offset, read);
            offset += read;
            return read;
        }

        /// Returns whether the channel is still open.
        ///
        /// @return whether the channel is still open
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Ensures that the channel is still open.
        ///
        /// @throws IOException if the channel has been closed
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Minimal seekable byte channel backed by an immutable byte array.
    @NotNullByDefault
    private static final class MemorySeekableByteChannel implements SeekableByteChannel {
        /// The immutable source bytes.
        private final byte[] bytes;
        /// The current read position.
        private int position;
        /// Whether the channel is still open.
        private boolean open = true;

        /// Creates a memory-backed seekable byte channel.
        ///
        /// @param bytes the immutable source bytes
        private MemorySeekableByteChannel(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        /// Reads bytes into the supplied destination buffer.
        ///
        /// @param destination the destination buffer
        /// @return the number of bytes read, or `-1` at end-of-stream
        /// @throws IOException if the channel has been closed
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (position >= bytes.length) {
                return -1;
            }
            int read = Math.min(destination.remaining(), bytes.length - position);
            destination.put(bytes, position, read);
            position += read;
            return read;
        }

        /// Rejects write attempts because the channel is read-only.
        ///
        /// @param source the ignored source buffer
        /// @return this method never returns normally
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the current read position.
        ///
        /// @return the current read position
        /// @throws IOException if the channel has been closed
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Moves the current read position.
        ///
        /// @param newPosition the new read position
        /// @return this channel
        /// @throws IOException if the channel has been closed
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > bytes.length) {
                throw new IllegalArgumentException("newPosition out of range: " + newPosition);
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the total byte length of the channel.
        ///
        /// @return the total byte length of the channel
        /// @throws IOException if the channel has been closed
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because the channel is immutable.
        ///
        /// @param size the ignored target size
        /// @return this method never returns normally
        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        /// Returns whether the channel is still open.
        ///
        /// @return whether the channel is still open
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Ensures that the channel is still open.
        ///
        /// @throws IOException if the channel has been closed
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
