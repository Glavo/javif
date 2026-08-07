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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Provides bounded positional reads over owned memory or an open file.
///
/// Instances are not thread-safe. A file-backed source owns its channel and, when temporary,
/// deletes its file when closed. An array-backed source takes ownership of its array without
/// copying it.
@NotNullByDefault
public final class RandomAccessDataSource implements Closeable {
    /// The byte count cached for scalar file-backed reads.
    private static final int FILE_CACHE_SIZE = 64 * 1024;

    /// The owned array, or `null` for a file-backed source.
    private final byte @Nullable @Unmodifiable [] bytes;
    /// The owned file channel, or `null` for an array-backed source.
    private final @Nullable FileChannel channel;
    /// The temporary file to delete on close, or `null` for persistent inputs.
    private final @Nullable Path temporaryFile;
    /// The fixed source size in bytes.
    private final long size;
    /// The reusable scalar-read cache for a file-backed source.
    private final byte @Nullable [] fileCache;

    /// The absolute source offset corresponding to the first cached byte.
    private long fileCacheOffset;
    /// The number of valid bytes in [#fileCache].
    private int fileCacheLength;
    /// Whether this source has been closed.
    private boolean closed;

    /// Creates an array-backed source that takes ownership of the supplied bytes.
    ///
    /// @param bytes the bytes whose ownership is transferred to the source
    private RandomAccessDataSource(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.channel = null;
        this.temporaryFile = null;
        this.size = bytes.length;
        this.fileCache = null;
    }

    /// Creates a file-backed source that owns the supplied channel.
    ///
    /// @param channel the channel positioned independently by positional reads
    /// @param temporaryFile the file to delete on close, or `null`
    /// @throws IOException if the channel size cannot be queried
    private RandomAccessDataSource(FileChannel channel, @Nullable Path temporaryFile) throws IOException {
        this.bytes = null;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.temporaryFile = temporaryFile;
        this.size = channel.size();
        this.fileCache = new byte[FILE_CACHE_SIZE];
    }

    /// Creates a source that takes ownership of a byte array.
    ///
    /// The caller must not access or modify the array after this method returns.
    ///
    /// @param bytes the bytes whose ownership is transferred
    /// @return the owned random-access source
    public static RandomAccessDataSource ofOwnedBytes(byte[] bytes) {
        return new RandomAccessDataSource(bytes);
    }

    /// Opens a persistent file for positional reads.
    ///
    /// @param path the file to open
    /// @return a source that owns an open read-only channel
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    public static RandomAccessDataSource open(Path path) throws IOException {
        return openFile(Objects.requireNonNull(path, "path"), null);
    }

    /// Opens a temporary file for positional reads and deletion on close.
    ///
    /// If opening fails, this method attempts to delete the temporary file before propagating the
    /// failure.
    ///
    /// @param path the temporary file to open
    /// @return a source that owns the file and its open read-only channel
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    public static RandomAccessDataSource openTemporary(Path path) throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        try {
            return openFile(checkedPath, checkedPath);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(checkedPath);
            } catch (IOException deleteException) {
                exception.addSuppressed(deleteException);
            }
            throw exception;
        }
    }

    /// Opens a file channel and closes it if source construction fails.
    ///
    /// @param path the file to open
    /// @param temporaryFile the file to delete on close, or `null`
    /// @return the file-backed source
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    private static RandomAccessDataSource openFile(Path path, @Nullable Path temporaryFile) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new RandomAccessDataSource(channel, temporaryFile);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Returns the fixed source size.
    ///
    /// @return the source size in bytes
    public long size() {
        return size;
    }

    /// Reads one byte at an absolute source offset.
    ///
    /// File-backed scalar reads use a reusable cache. Bulk reads performed through
    /// [#readFully(long, ByteBuffer)] do not disturb this cache.
    ///
    /// @param position the zero-based absolute byte offset
    /// @return the byte at the supplied offset
    /// @throws IOException if the source is closed, the offset is outside the source, or the file
    ///                     cannot be read
    public byte readByte(long position) throws IOException {
        ensureRange(position, 1L);
        if (bytes != null) {
            return bytes[(int) position];
        }

        byte[] cache = Objects.requireNonNull(fileCache, "fileCache");
        if (position < fileCacheOffset || position >= fileCacheOffset + fileCacheLength) {
            refillFileCache(position);
        }
        return cache[(int) (position - fileCacheOffset)];
    }

    /// Reads bytes from an absolute source offset into a destination buffer.
    ///
    /// The destination's position advances by its original remaining byte count. Its limit is not
    /// changed. A zero-length read still validates that the source is open and permits a position
    /// equal to the source size.
    ///
    /// @param position the zero-based absolute source offset
    /// @param destination the destination buffer
    /// @throws IOException if the source is closed, the requested range is outside the source, or
    ///                     the file cannot be read completely
    public void readFully(long position, ByteBuffer destination) throws IOException {
        ByteBuffer checkedDestination = Objects.requireNonNull(destination, "destination");
        int length = checkedDestination.remaining();
        ensureRange(position, length);
        if (length == 0) {
            return;
        }
        if (bytes != null) {
            checkedDestination.put(bytes, (int) position, length);
            return;
        }

        byte[] cache = Objects.requireNonNull(fileCache, "fileCache");
        if (position >= fileCacheOffset && position + length <= fileCacheOffset + fileCacheLength) {
            checkedDestination.put(cache, (int) (position - fileCacheOffset), length);
            return;
        }

        FileChannel checkedChannel = Objects.requireNonNull(channel, "channel");
        long readPosition = position;
        while (checkedDestination.hasRemaining()) {
            int read = checkedChannel.read(checkedDestination, readPosition);
            if (read < 0) {
                throw new EOFException("Unexpected end of random-access source");
            }
            if (read == 0) {
                throw new IOException("FileChannel made no progress while reading AVIF input");
            }
            readPosition += read;
        }
    }

    /// Reads one source range into a newly allocated array.
    ///
    /// @param position the zero-based absolute source offset
    /// @param length the requested byte count
    /// @return the copied source bytes
    /// @throws IOException if the source is closed, the range is invalid, or the source cannot be
    ///                     read completely
    public byte[] readBytes(long position, int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        byte[] result = new byte[length];
        readFully(position, ByteBuffer.wrap(result));
        return result;
    }

    /// Refills the scalar-read cache beginning at the supplied absolute position.
    ///
    /// @param position the first byte to cache
    /// @throws IOException if the channel cannot be read
    private void refillFileCache(long position) throws IOException {
        FileChannel checkedChannel = Objects.requireNonNull(channel, "channel");
        byte[] cache = Objects.requireNonNull(fileCache, "fileCache");
        int requested = (int) Math.min(cache.length, size - position);
        ByteBuffer target = ByteBuffer.wrap(cache, 0, requested);
        long readPosition = position;
        while (target.hasRemaining()) {
            int read = checkedChannel.read(target, readPosition);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("FileChannel made no progress while caching AVIF input");
            }
            readPosition += read;
        }
        fileCacheOffset = position;
        fileCacheLength = target.position();
        if (fileCacheLength == 0) {
            throw new EOFException("Unexpected end of random-access source");
        }
    }

    /// Validates an absolute source range and the open state.
    ///
    /// @param position the zero-based absolute source offset
    /// @param length the requested byte count
    /// @throws IOException if the source is closed or the range is outside the source
    private void ensureRange(long position, long length) throws IOException {
        if (closed) {
            throw new IOException("Random-access source is closed");
        }
        if (position < 0 || length < 0 || position > size || length > size - position) {
            throw new EOFException("Random-access range outside source: " + position + " + " + length);
        }
    }

    /// Closes the owned file channel and deletes an owned temporary file.
    ///
    /// Closing an array-backed source has no external effect. Repeated calls have no effect. If
    /// both closing and deletion fail, the deletion failure is suppressed on the close failure.
    ///
    /// @throws IOException if an owned channel cannot be closed or a temporary file cannot be
    ///                     deleted
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        @Nullable IOException failure = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (temporaryFile != null) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
