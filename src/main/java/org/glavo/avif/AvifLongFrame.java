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

import org.glavo.avif.decode.PixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Decoded AVIF frame backed by packed `long` ARGB pixels.
@NotNullByDefault
public final class AvifLongFrame extends AvifFrame {
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    private final @Unmodifiable LongBuffer pixels;

    /// Creates a `long`-backed AVIF frame.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifLongFrame(int width, int height, int bitDepth, PixelFormat pixelFormat, int frameIndex, long[] pixels) {
        super(width, height, bitDepth, pixelFormat, frameIndex);
        this.pixels = immutablePixels(Objects.requireNonNull(pixels, "pixels"));
    }

    /// Creates a `long`-backed AVIF frame from immutable pixel storage.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    AvifLongFrame(
            int width,
            int height,
            int bitDepth,
            PixelFormat pixelFormat,
            int frameIndex,
            @Unmodifiable LongBuffer pixels
    ) {
        super(width, height, bitDepth, pixelFormat, frameIndex);
        this.pixels = Objects.requireNonNull(pixels, "pixels").slice().asReadOnlyBuffer();
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// @return packed non-premultiplied ARGB pixels
    public long[] pixels() {
        LongBuffer buffer = pixelBuffer();
        long[] result = new long[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public @UnmodifiableView LongBuffer pixelBuffer() {
        return pixels.slice();
    }

    /// Creates immutable storage for pixel arrays.
    ///
    /// @param pixels the source pixels
    /// @return immutable pixel storage
    private static @Unmodifiable LongBuffer immutablePixels(long[] pixels) {
        return LongBuffer.wrap(Arrays.copyOf(pixels, pixels.length)).asReadOnlyBuffer();
    }
}
