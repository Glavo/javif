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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;

/// Supported AVIF decoded sample bit depths.
@NotNullByDefault
public enum AvifBitDepth {
    /// Eight bits per decoded sample.
    EIGHT_BITS(8),
    /// Ten bits per decoded sample.
    TEN_BITS(10),
    /// Twelve bits per decoded sample.
    TWELVE_BITS(12);

    /// The decoded sample bit count.
    private final int bits;

    /// Creates a supported decoded sample bit depth.
    ///
    /// @param bits the decoded sample bit count
    AvifBitDepth(int bits) {
        this.bits = bits;
    }

    /// Returns the decoded sample bit count.
    ///
    /// @return the decoded sample bit count
    public int bits() {
        return bits;
    }

    /// Returns the largest sample value representable by this bit depth.
    ///
    /// @return the largest sample value
    public int maxSampleValue() {
        return (1 << bits) - 1;
    }

    /// Returns whether this is the 8-bit output path.
    ///
    /// @return `true` for 8-bit output
    public boolean isEightBit() {
        return this == EIGHT_BITS;
    }

    /// Returns whether this is a high-bit-depth output path.
    ///
    /// @return `true` for 10-bit or 12-bit output
    public boolean isHighBitDepth() {
        return this == TEN_BITS || this == TWELVE_BITS;
    }

    /// Maps a numeric bit count to a supported decoded sample bit depth.
    ///
    /// @param bits the decoded sample bit count
    /// @return the matching bit depth
    public static AvifBitDepth fromBits(int bits) {
        return switch (bits) {
            case 8 -> EIGHT_BITS;
            case 10 -> TEN_BITS;
            case 12 -> TWELVE_BITS;
            default -> throw new IllegalArgumentException("Unsupported bit depth: " + bits);
        };
    }

    /// Returns the numeric bit depth text.
    ///
    /// @return the numeric bit depth text
    @Override
    public String toString() {
        return Integer.toString(bits);
    }
}
