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

import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Utility methods for reading unsigned LEB128 values from AV1 bitstreams.
@NotNullByDefault
public final class Leb128 {
    /// The maximum supported unsigned value.
    private static final long MAX_UNSIGNED_VALUE = 0xFFFF_FFFFL;

    /// Prevents instantiation of this utility class.
    private Leb128() {
    }

    /// Reads an unsigned LEB128 value from a buffered byte source.
    ///
    /// @param input the buffered byte source
    /// @param maxBytes the maximum number of bytes to consume
    /// @return the decoded LEB128 value and byte count
    /// @throws IOException if the source is truncated or unreadable
    public static ReadResult readUnsigned(BufferedInput input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes <= 0: " + maxBytes);
        }

        long value = 0;
        int shift = 0;

        for (int byteCount = 1; byteCount <= maxBytes; byteCount++) {
            int current = input.readUnsignedByte();
            value |= (long) (current & 0x7F) << shift;

            if ((current & 0x80) == 0) {
                if (value > MAX_UNSIGNED_VALUE) {
                    throw new IOException("LEB128 value exceeds 32-bit unsigned range: " + value);
                }
                return new ReadResult(value, byteCount);
            }

            shift += 7;
        }

        throw new EOFException("LEB128 value exceeds the supported byte length");
    }

    /// Decoded unsigned LEB128 value and encoded byte count.
    @NotNullByDefault
    public static final class ReadResult {
        /// The decoded unsigned value.
        private final long value;
        /// The number of bytes consumed from the source.
        private final int byteCount;

        /// Creates a decoded unsigned LEB128 result.
        ///
        /// @param value the decoded unsigned value
        /// @param byteCount the number of bytes consumed
        public ReadResult(long value, int byteCount) {
            if (value < 0) {
                throw new IllegalArgumentException("value < 0: " + value);
            }
            if (byteCount <= 0) {
                throw new IllegalArgumentException("byteCount <= 0: " + byteCount);
            }
            this.value = value;
            this.byteCount = byteCount;
        }

        /// Returns the decoded unsigned value.
        ///
        /// @return the decoded unsigned value
        public long value() {
            return value;
        }

        /// Returns the number of bytes consumed from the source.
        ///
        /// @return the number of bytes consumed
        public int byteCount() {
            return byteCount;
        }
    }
}
