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

import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifErrorCode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Provides bounded positional reads over borrowed memory, an open file, or a borrowed forward-only stream.
///
/// Instances are not thread-safe. A file-backed source owns its channel. A memory-backed source
/// retains its array or buffer region without copying its contents. A forward-only source borrows
/// its stream and never closes it; reads behind its bounded cache fail with
/// [AvifErrorCode#SEEKABLE_SOURCE_REQUIRED].
@NotNullByDefault
public final class RandomAccessDataSource implements Closeable {
    /// The byte count retained for cached and forward-only reads.
    private static final int READ_CACHE_SIZE = 64 * 1024;

    /// The borrowed array, or `null` when the source is not array-backed.
    private final byte @Nullable [] bytes;
    /// The borrowed read-only buffer region, or `null` when the source is not buffer-backed.
    private final @Nullable @UnmodifiableView ByteBuffer memoryBuffer;
    /// The owned file channel, or `null` when the source is not file-backed.
    private final @Nullable FileChannel channel;
    /// The borrowed forward-only stream, or `null` for a random-access source.
    private final @Nullable InputStream stream;
    /// The fixed source size or maximum permitted forward-only position in bytes.
    private final long size;
    /// The reusable read cache for file-backed and forward-only sources.
    private final byte @Nullable [] readCache;

    /// The absolute source offset corresponding to the first cached byte.
    private long readCacheOffset;
    /// The number of valid bytes in [#readCache].
    private int readCacheLength;
    /// The absolute position of the next unread byte in a forward-only stream.
    private long streamPosition;
    /// Whether this source has been closed.
    private boolean closed;

    /// Creates an array-backed source that retains the supplied bytes.
    ///
    /// @param bytes the borrowed source bytes
    private RandomAccessDataSource(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.memoryBuffer = null;
        this.channel = null;
        this.stream = null;
        this.size = bytes.length;
        this.readCache = null;
    }

    /// Creates a buffer-backed source that retains the supplied buffer's remaining region.
    ///
    /// @param buffer the borrowed source buffer
    private RandomAccessDataSource(ByteBuffer buffer) {
        this.bytes = null;
        this.memoryBuffer = Objects.requireNonNull(buffer, "buffer").slice().asReadOnlyBuffer();
        this.channel = null;
        this.stream = null;
        this.size = memoryBuffer.remaining();
        this.readCache = null;
    }

    /// Creates a file-backed source that owns the supplied channel.
    ///
    /// @param channel the channel positioned independently by positional reads
    /// @throws IOException if the channel size cannot be queried
    private RandomAccessDataSource(FileChannel channel) throws IOException {
        this.bytes = null;
        this.memoryBuffer = null;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.stream = null;
        this.size = channel.size();
        this.readCache = new byte[READ_CACHE_SIZE];
    }

    /// Creates a forward-only source that borrows an input stream.
    ///
    /// @param stream the stream to borrow without closing
    /// @param maximumSize the maximum accepted absolute input size
    private RandomAccessDataSource(InputStream stream, long maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize <= 0: " + maximumSize);
        }
        this.bytes = null;
        this.memoryBuffer = null;
        this.channel = null;
        this.stream = Objects.requireNonNull(stream, "stream");
        this.size = maximumSize;
        this.readCache = new byte[READ_CACHE_SIZE];
    }

    /// Creates a source that borrows a byte array without copying it.
    ///
    /// The caller must not modify the array while the source is in use.
    ///
    /// @param bytes the bytes to retain
    /// @return the borrowed memory source
    public static RandomAccessDataSource ofBytes(byte[] bytes) {
        return new RandomAccessDataSource(bytes);
    }

    /// Creates a source that borrows a byte buffer's remaining region without copying it.
    ///
    /// The captured region starts at the buffer's current position and ends at its current limit.
    /// Creating and reading the source does not change the supplied buffer's position or limit.
    /// The caller must not modify the region through the buffer, its backing storage, or another
    /// alias while the source is in use.
    ///
    /// @param buffer the buffer whose remaining region is retained
    /// @return the borrowed memory source
    public static RandomAccessDataSource ofByteBuffer(ByteBuffer buffer) {
        return new RandomAccessDataSource(buffer);
    }

    /// Opens a persistent file for positional reads.
    ///
    /// @param path the file to open
    /// @return a source that owns an open read-only channel
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    public static RandomAccessDataSource open(Path path) throws IOException {
        return openFile(Objects.requireNonNull(path, "path"));
    }

    /// Creates a source that reads progressively from a borrowed stream.
    ///
    /// The source retains at most one fixed-size read window and never closes the stream. Attempts
    /// to revisit bytes outside that window fail instead of buffering or spooling the input.
    ///
    /// @param stream the stream to borrow
    /// @param maximumSize the maximum accepted input size
    /// @return the forward-only source
    public static RandomAccessDataSource progressive(InputStream stream, long maximumSize) {
        return new RandomAccessDataSource(stream, maximumSize);
    }

    /// Opens a file channel and closes it if source construction fails.
    ///
    /// @param path the file to open
    /// @return the file-backed source
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    private static RandomAccessDataSource openFile(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new RandomAccessDataSource(channel);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Returns the fixed source size or the maximum permitted progressive position.
    ///
    /// @return the exact random-access source size or the progressive input limit in bytes
    public long size() {
        return size;
    }

    /// Returns whether this source can only move forward outside its bounded read cache.
    ///
    /// @return whether the source is backed by a non-seekable stream
    public boolean forwardOnly() {
        return stream != null;
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
        if (memoryBuffer != null) {
            return memoryBuffer.get((int) position);
        }

        byte[] cache = Objects.requireNonNull(readCache, "readCache");
        if (position < readCacheOffset || position >= readCacheOffset + readCacheLength) {
            if (stream != null) {
                refillProgressiveCache(position);
            } else {
                refillFileCache(position);
            }
        }
        return cache[(int) (position - readCacheOffset)];
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
        if (memoryBuffer != null) {
            int start = (int) position;
            memoryBuffer.limit(start + length).position(start);
            try {
                checkedDestination.put(memoryBuffer);
            } finally {
                memoryBuffer.clear();
            }
            return;
        }

        byte[] cache = Objects.requireNonNull(readCache, "readCache");
        if (position >= readCacheOffset && position + length <= readCacheOffset + readCacheLength) {
            checkedDestination.put(cache, (int) (position - readCacheOffset), length);
            return;
        }

        if (stream != null) {
            readProgressively(position, checkedDestination);
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
        byte[] cache = Objects.requireNonNull(readCache, "readCache");
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
        readCacheOffset = position;
        readCacheLength = target.position();
        if (readCacheLength == 0) {
            throw new EOFException("Unexpected end of random-access source");
        }
    }

    /// Reads a requested range progressively through the bounded cache.
    ///
    /// @param position the absolute first requested position
    /// @param destination the destination buffer
    /// @throws IOException if the stream is truncated, exceeds its limit, or would require seeking
    private void readProgressively(long position, ByteBuffer destination) throws IOException {
        long readPosition = position;
        while (destination.hasRemaining()) {
            if (readPosition < readCacheOffset || readPosition >= readCacheOffset + readCacheLength) {
                refillProgressiveCache(readPosition);
            }
            int cacheIndex = (int) (readPosition - readCacheOffset);
            int chunk = Math.min(destination.remaining(), readCacheLength - cacheIndex);
            destination.put(Objects.requireNonNull(readCache, "readCache"), cacheIndex, chunk);
            readPosition += chunk;
        }
    }

    /// Refills the bounded cache at a forward stream position.
    ///
    /// Any gap between the underlying stream position and `position` is consumed and discarded.
    /// A position behind both the stream cursor and the current cache requires a seekable source.
    ///
    /// @param position the absolute first byte to cache
    /// @throws IOException if the stream is truncated, exceeds its limit, or would require seeking
    private void refillProgressiveCache(long position) throws IOException {
        InputStream checkedStream = Objects.requireNonNull(stream, "stream");
        byte[] cache = Objects.requireNonNull(readCache, "readCache");
        if (position < streamPosition) {
            throw seekableSourceRequired(position);
        }
        while (streamPosition < position) {
            int chunk = (int) Math.min(cache.length, position - streamPosition);
            int read = checkedStream.read(cache, 0, chunk);
            if (read < 0) {
                throw new EOFException("Unexpected end of progressive AVIF input");
            }
            if (read == 0) {
                throw new IOException("InputStream made no progress while advancing AVIF input");
            }
            readCacheOffset = streamPosition;
            readCacheLength = read;
            streamPosition += read;
        }

        int requested = (int) Math.min(cache.length, size - streamPosition);
        int read = checkedStream.read(cache, 0, requested);
        if (read < 0) {
            throw new EOFException("Unexpected end of progressive AVIF input");
        }
        if (read == 0) {
            throw new IOException("InputStream made no progress while reading AVIF input");
        }
        readCacheOffset = streamPosition;
        readCacheLength = read;
        streamPosition += read;
    }

    /// Creates a failure indicating that a discarded stream prefix is required again.
    ///
    /// @param position the requested absolute source position
    /// @return the seekability failure
    private static AvifDecodeException seekableSourceRequired(long position) {
        return new AvifDecodeException(
                AvifErrorCode.SEEKABLE_SOURCE_REQUIRED,
                "AVIF input requires backward access at byte offset " + position
                        + "; use Path, byte[], or ByteBuffer input",
                position
        );
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
        if (position < 0 || length < 0) {
            throw new EOFException("Invalid random-access range: " + position + " + " + length);
        }
        if (position > size || length > size - position) {
            if (stream != null) {
                throw new AvifDecodeException(
                        AvifErrorCode.INPUT_TOO_LARGE,
                        "AVIF input exceeds supported size limit: " + size + " bytes",
                        position
                );
            }
            throw new EOFException("Random-access range outside source: " + position + " + " + length);
        }
    }

    /// Closes an owned file channel.
    ///
    /// Closing a memory-backed or borrowed-stream source has no external effect. Repeated calls
    /// have no effect.
    ///
    /// @throws IOException if an owned channel cannot be closed
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        if (channel != null) {
            channel.close();
        }
    }
}
