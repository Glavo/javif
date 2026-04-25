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
package org.glavo.avif.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Shared helpers for immutable ARGB pixel buffers.
@NotNullByDefault
public final class PixelBuffers {
    /// Prevents instantiation.
    private PixelBuffers() {
    }

    /// Creates immutable storage for `int` pixel arrays.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static @Unmodifiable IntBuffer immutableIntPixels(int[] pixels) {
        return IntBuffer.wrap(Arrays.copyOf(pixels, pixels.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for `long` pixel arrays.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static @Unmodifiable LongBuffer immutableLongPixels(long[] pixels) {
        return LongBuffer.wrap(Arrays.copyOf(pixels, pixels.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for an `int` pixel buffer.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static @Unmodifiable IntBuffer immutableIntPixels(@Unmodifiable IntBuffer pixels) {
        return Objects.requireNonNull(pixels, "pixels").slice().asReadOnlyBuffer();
    }

    /// Creates immutable storage for a `long` pixel buffer.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    public static @Unmodifiable LongBuffer immutableLongPixels(@Unmodifiable LongBuffer pixels) {
        return Objects.requireNonNull(pixels, "pixels").slice().asReadOnlyBuffer();
    }

    /// Converts `int` ARGB pixels into `long` ARGB pixels.
    ///
    /// @param pixels the source pixels
    /// @return converted immutable pixel storage
    public static @Unmodifiable LongBuffer convertIntPixelsToLongPixels(@Unmodifiable IntBuffer pixels) {
        IntBuffer source = pixels.slice();
        long[] converted = new long[source.remaining()];
        for (int i = 0; i < converted.length; i++) {
            int pixel = source.get();
            converted[i] = (long) byteToWord((pixel >>> 24) & 0xFF) << 48
                    | (long) byteToWord((pixel >>> 16) & 0xFF) << 32
                    | (long) byteToWord((pixel >>> 8) & 0xFF) << 16
                    | byteToWord(pixel & 0xFF);
        }
        return LongBuffer.wrap(converted).asReadOnlyBuffer();
    }

    /// Converts `long` ARGB pixels into `int` ARGB pixels.
    ///
    /// @param pixels the source pixels
    /// @return converted immutable pixel storage
    public static @Unmodifiable IntBuffer convertLongPixelsToIntPixels(@Unmodifiable LongBuffer pixels) {
        LongBuffer source = pixels.slice();
        int[] converted = new int[source.remaining()];
        for (int i = 0; i < converted.length; i++) {
            long pixel = source.get();
            converted[i] = wordToByte((int) ((pixel >>> 48) & 0xFFFFL)) << 24
                    | wordToByte((int) ((pixel >>> 32) & 0xFFFFL)) << 16
                    | wordToByte((int) ((pixel >>> 16) & 0xFFFFL)) << 8
                    | wordToByte((int) (pixel & 0xFFFFL));
        }
        return IntBuffer.wrap(converted).asReadOnlyBuffer();
    }

    /// Expands an unsigned 8-bit channel to an unsigned 16-bit channel.
    ///
    /// @param value the unsigned 8-bit channel
    /// @return the unsigned 16-bit channel
    private static int byteToWord(int value) {
        return value * 257;
    }

    /// Reduces an unsigned 16-bit channel to an unsigned 8-bit channel with rounding.
    ///
    /// @param value the unsigned 16-bit channel
    /// @return the unsigned 8-bit channel
    private static int wordToByte(int value) {
        return (value * 255 + 32_767) / 65_535;
    }
}
