// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.io;

import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifErrorCode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests memory, file-backed, and bounded progressive data sources.
@NotNullByDefault
final class AvifDataSourceTest {
    /// Verifies positional scalar and bulk reads over borrowed array memory.
    ///
    /// @throws IOException if the source cannot be read or closed
    @Test
    void readsBorrowedBytesAndRejectsReadsAfterClose() throws IOException {
        AvifDataSource source = AvifDataSource.ofBytes(new byte[]{1, 2, 3, 4, 5});
        assertEquals(5L, source.limit());
        assertTrue(source.isSeekable());
        assertEquals(3, source.readByte(2));

        ByteBuffer destination = ByteBuffer.allocate(3);
        source.readFully(1, destination);
        assertArrayEquals(new byte[]{2, 3, 4}, destination.array());

        source.close();
        assertThrows(IOException.class, () -> source.readByte(0));
    }

    /// Verifies a direct buffer's captured region is read without changing caller-visible bounds.
    ///
    /// @throws IOException if the source cannot be read or closed
    @Test
    void readsBorrowedDirectByteBufferRegionWithoutChangingCallerState() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(6);
        buffer.put(new byte[]{9, 1, 2, 3, 8, 7});
        buffer.position(1);
        buffer.limit(4);
        AvifDataSource source = AvifDataSource.ofByteBuffer(buffer);

        assertEquals(3L, source.limit());
        assertTrue(source.isSeekable());
        assertEquals(1, source.readByte(0));
        assertArrayEquals(new byte[]{2, 3}, source.readBytes(1, 2));
        assertEquals(1, buffer.position());
        assertEquals(4, buffer.limit());

        source.close();
        buffer.put(1, (byte) 6);
        assertEquals(6, buffer.get(1));
    }

    /// Verifies file-backed reads and release of the owned file handle.
    ///
    /// @throws IOException if the fixture cannot be created, read, closed, or deleted
    @Test
    void closesPersistentFileHandle() throws IOException {
        Path path = workspaceTempPath("persistent");
        Files.write(path, new byte[]{9, 8, 7, 6});
        AvifDataSource source = AvifDataSource.open(path);
        assertEquals(8, source.readByte(1));
        assertArrayEquals(new byte[]{7, 6}, source.readBytes(2, 2));

        source.close();
        Files.delete(path);
        assertFalse(Files.exists(path));
    }

    /// Verifies progressive reads retain only a bounded recent window and never close the stream.
    ///
    /// @throws IOException if the source cannot be read or closed
    @Test
    void readsProgressivelyAndRejectsDiscardedPrefixesWithoutClosingStream() throws IOException {
        byte[] bytes = new byte[80 * 1024];
        bytes[0] = 11;
        bytes[70 * 1024] = 22;
        TrackingInputStream input = new TrackingInputStream(bytes);
        AvifDataSource source = AvifDataSource.progressive(Channels.newChannel(input), bytes.length);

        assertFalse(source.isSeekable());
        assertEquals(11, source.readByte(0));
        assertEquals(22, source.readByte(70 * 1024L));
        AvifDecodeException exception = assertThrows(AvifDecodeException.class, () -> source.readByte(0));
        assertEquals(AvifErrorCode.SEEKABLE_SOURCE_REQUIRED, exception.code());

        source.close();
        assertFalse(input.closed());
    }

    /// Verifies that a progressive source enforces its configured maximum position.
    @Test
    void progressiveSourceEnforcesMaximumSize() {
        AvifDataSource source = AvifDataSource.progressive(
                Channels.newChannel(new ByteArrayInputStream(new byte[16])),
                8
        );

        AvifDecodeException exception = assertThrows(AvifDecodeException.class, () -> source.readByte(8));
        assertEquals(AvifErrorCode.INPUT_TOO_LARGE, exception.code());
    }

    /// Byte-array stream that records whether it was closed.
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Whether [#close()] was invoked.
        private boolean closed;

        /// Creates a stream over the supplied bytes.
        ///
        /// @param bytes the stream contents
        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Records closure while retaining normal byte-array stream behavior.
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether [#close()] was invoked.
        ///
        /// @return whether the stream was closed
        private boolean closed() {
            return closed;
        }
    }

    /// Creates a unique test path under the workspace-local build directory.
    ///
    /// @param name the logical test name
    /// @return the path, which does not yet exist
    /// @throws IOException if the parent directory cannot be created
    private static Path workspaceTempPath(String name) throws IOException {
        Path directory = Path.of("build", "tmp", "test", "AvifDataSourceTest");
        Files.createDirectories(directory);
        return directory.resolve(name + "-" + System.nanoTime() + ".bin");
    }
}
