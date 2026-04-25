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
package org.glavo.avif.internal.bmff;

import org.glavo.avif.AvifDecodeException;
import org.glavo.avif.AvifErrorCode;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/// Bounded big-endian byte reader for BMFF boxes.
@NotNullByDefault
public final class BoxInput {
    /// The complete source bytes.
    private final byte[] source;
    /// The inclusive lower bound for this view.
    private final int start;
    /// The exclusive upper bound for this view.
    private final int end;
    /// The current absolute read offset.
    private int offset;

    /// Creates a bounded input over a byte array.
    ///
    /// @param source the complete source bytes
    public BoxInput(byte[] source) {
        this(source, 0, Objects.requireNonNull(source, "source").length);
    }

    /// Creates a bounded input over one source slice.
    ///
    /// @param source the complete source bytes
    /// @param start the inclusive lower bound
    /// @param end the exclusive upper bound
    public BoxInput(byte[] source, int start, int end) {
        this.source = Objects.requireNonNull(source, "source");
        if (start < 0 || end < start || end > source.length) {
            throw new IllegalArgumentException("invalid input bounds: " + start + ".." + end);
        }
        this.start = start;
        this.end = end;
        this.offset = start;
    }

    /// Returns the current absolute read offset.
    ///
    /// @return the current absolute read offset
    public int offset() {
        return offset;
    }

    /// Returns the exclusive upper bound for this view.
    ///
    /// @return the exclusive upper bound for this view
    public int end() {
        return end;
    }

    /// Returns the remaining byte count.
    ///
    /// @return the remaining byte count
    public int remaining() {
        return end - offset;
    }

    /// Returns whether unread bytes remain.
    ///
    /// @return whether unread bytes remain
    public boolean hasRemaining() {
        return offset < end;
    }

    /// Reads one unsigned 8-bit integer.
    ///
    /// @return one unsigned 8-bit integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU8() throws AvifDecodeException {
        ensureAvailable(1);
        return Byte.toUnsignedInt(source[offset++]);
    }

    /// Reads one unsigned 16-bit big-endian integer.
    ///
    /// @return one unsigned 16-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU16() throws AvifDecodeException {
        ensureAvailable(2);
        int value = (Byte.toUnsignedInt(source[offset]) << 8)
                | Byte.toUnsignedInt(source[offset + 1]);
        offset += 2;
        return value;
    }

    /// Reads one unsigned 24-bit big-endian integer.
    ///
    /// @return one unsigned 24-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public int readU24() throws AvifDecodeException {
        ensureAvailable(3);
        int value = (Byte.toUnsignedInt(source[offset]) << 16)
                | (Byte.toUnsignedInt(source[offset + 1]) << 8)
                | Byte.toUnsignedInt(source[offset + 2]);
        offset += 3;
        return value;
    }

    /// Reads one unsigned 32-bit big-endian integer.
    ///
    /// @return one unsigned 32-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated
    public long readU32() throws AvifDecodeException {
        ensureAvailable(4);
        long value = ((long) Byte.toUnsignedInt(source[offset]) << 24)
                | ((long) Byte.toUnsignedInt(source[offset + 1]) << 16)
                | ((long) Byte.toUnsignedInt(source[offset + 2]) << 8)
                | Byte.toUnsignedInt(source[offset + 3]);
        offset += 4;
        return value;
    }

    /// Reads one unsigned 64-bit big-endian integer that fits in `long`.
    ///
    /// @return one unsigned 64-bit big-endian integer
    /// @throws AvifDecodeException if the input is truncated or the value exceeds `Long.MAX_VALUE`
    public long readU64() throws AvifDecodeException {
        ensureAvailable(8);
        long high = readU32();
        long low = readU32();
        if ((high & 0x8000_0000L) != 0) {
            throw parseFailed("64-bit box value exceeds supported range", offset - 8);
        }
        return (high << 32) | low;
    }

    /// Reads one fixed-length four-character code.
    ///
    /// @return one fixed-length four-character code
    /// @throws AvifDecodeException if the input is truncated
    public String readFourCc() throws AvifDecodeException {
        ensureAvailable(4);
        String value = new String(source, offset, 4, StandardCharsets.ISO_8859_1);
        offset += 4;
        return value;
    }

    /// Reads one byte array.
    ///
    /// @param length the byte count to read
    /// @return one byte array
    /// @throws AvifDecodeException if the input is truncated
    public byte[] readBytes(int length) throws AvifDecodeException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        ensureAvailable(length);
        byte[] result = Arrays.copyOfRange(source, offset, offset + length);
        offset += length;
        return result;
    }

    /// Skips bytes.
    ///
    /// @param length the byte count to skip
    /// @throws AvifDecodeException if the input is truncated
    public void skip(int length) throws AvifDecodeException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        ensureAvailable(length);
        offset += length;
    }

    /// Creates a child input over one absolute byte range.
    ///
    /// @param absoluteStart the absolute child start offset
    /// @param size the child byte size
    /// @return a child input over one absolute byte range
    /// @throws AvifDecodeException if the range is outside this view
    public BoxInput slice(int absoluteStart, int size) throws AvifDecodeException {
        if (size < 0 || absoluteStart < start || absoluteStart > end || size > end - absoluteStart) {
            throw truncated("child box exceeds parent bounds", absoluteStart);
        }
        return new BoxInput(source, absoluteStart, absoluteStart + size);
    }

    /// Reads the next BMFF box header.
    ///
    /// @return the next BMFF box header
    /// @throws AvifDecodeException if the header is malformed or truncated
    public BoxHeader readBoxHeader() throws AvifDecodeException {
        int boxOffset = offset;
        ensureAvailable(8);
        long smallSize = readU32();
        String type = readFourCc();

        long boxSize = smallSize;
        if (smallSize == 1) {
            boxSize = readU64();
        } else if (smallSize == 0) {
            boxSize = end - boxOffset;
        }

        int headerSize = offset - boxOffset;
        if (boxSize < headerSize || boxSize > Integer.MAX_VALUE) {
            throw parseFailed("invalid BMFF box size for " + type + ": " + boxSize, boxOffset);
        }
        int payloadSize = (int) boxSize - headerSize;
        if (payloadSize > end - offset) {
            throw truncated("BMFF box payload exceeds parent bounds: " + type, boxOffset);
        }
        return new BoxHeader(type, boxOffset, offset, payloadSize);
    }

    /// Moves to the end of one parsed box.
    ///
    /// @param header the parsed box header
    /// @throws AvifDecodeException if the box end is outside this view
    public void skipBoxPayload(BoxHeader header) throws AvifDecodeException {
        int endOffset = header.endOffset();
        if (endOffset < offset || endOffset > end) {
            throw truncated("BMFF box payload exceeds parent bounds: " + header.type(), header.offset());
        }
        offset = endOffset;
    }

    /// Fails if the requested byte count is not available.
    ///
    /// @param count the requested byte count
    /// @throws AvifDecodeException if the input is truncated
    private void ensureAvailable(int count) throws AvifDecodeException {
        if (count < 0 || count > end - offset) {
            throw truncated("Unexpected end of BMFF input", offset);
        }
    }

    /// Creates a BMFF parse failure.
    ///
    /// @param message the failure message
    /// @param byteOffset the associated byte offset
    /// @return a BMFF parse failure
    private static AvifDecodeException parseFailed(String message, long byteOffset) {
        return new AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, byteOffset);
    }

    /// Creates a truncation failure.
    ///
    /// @param message the failure message
    /// @param byteOffset the associated byte offset
    /// @return a truncation failure
    private static AvifDecodeException truncated(String message, long byteOffset) {
        return new AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, message, byteOffset);
    }
}
