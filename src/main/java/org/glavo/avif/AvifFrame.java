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
import org.glavo.avif.internal.PixelBuffers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Decoded AVIF frame output.
@NotNullByDefault
public final class AvifFrame {
    /// The frame width in pixels.
    private final int width;
    /// The frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The decoded AV1 chroma sampling layout.
    private final PixelFormat pixelFormat;
    /// The zero-based frame index.
    private final int frameIndex;
    /// Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted.
    private @Nullable @Unmodifiable IntBuffer intPixels;
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted.
    private @Nullable @Unmodifiable LongBuffer longPixels;

    /// Creates an AVIF frame from packed `int` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, PixelFormat pixelFormat, int frameIndex, int[] pixels) {
        this(width, height, bitDepth, pixelFormat, frameIndex,
                PixelBuffers.immutableIntPixels(Objects.requireNonNull(pixels, "pixels")), null);
    }

    /// Creates an AVIF frame from packed `long` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, PixelFormat pixelFormat, int frameIndex, long[] pixels) {
        this(width, height, bitDepth, pixelFormat, frameIndex,
                null, PixelBuffers.immutableLongPixels(Objects.requireNonNull(pixels, "pixels")));
    }

    /// Creates an AVIF frame from a packed `int` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            int frameIndex,
            @Unmodifiable IntBuffer pixels
    ) {
        this(width, height, bitDepth, pixelFormat, frameIndex, PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates an AVIF frame from a packed `long` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            int frameIndex,
            @Unmodifiable LongBuffer pixels
    ) {
        this(width, height, bitDepth, pixelFormat, frameIndex, null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates a decoded AVIF frame descriptor with one available pixel representation.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param intPixels   packed `int` pixels, or `null`
    /// @param longPixels  packed `long` pixels, or `null`
    private AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            int frameIndex,
            @Nullable @Unmodifiable IntBuffer intPixels,
            @Nullable @Unmodifiable LongBuffer longPixels
    ) {
        if (intPixels == null && longPixels == null) {
            throw new IllegalArgumentException("At least one pixel representation is required");
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.frameIndex = frameIndex;
        this.intPixels = intPixels;
        this.longPixels = longPixels;
    }

    /// Returns the frame width in pixels.
    ///
    /// @return the frame width in pixels
    public int width() {
        return width;
    }

    /// Returns the frame height in pixels.
    ///
    /// @return the frame height in pixels
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the decoded AV1 chroma sampling layout.
    ///
    /// @return the decoded AV1 chroma sampling layout
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns the zero-based frame index.
    ///
    /// @return the zero-based frame index
    public int frameIndex() {
        return frameIndex;
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the returned data is created lazily by
    /// reducing each unsigned 16-bit channel to 8 bits with rounding.
    ///
    /// @return packed non-premultiplied ARGB pixels
    public int[] intPixels() {
        IntBuffer buffer = intPixelBuffer();
        int[] result = new int[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the buffer is created lazily and cached.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public @UnmodifiableView IntBuffer intPixelBuffer() {
        IntBuffer pixels = intPixels;
        if (pixels == null) {
            intPixels = pixels = PixelBuffers.convertLongPixelsToIntPixels(requireLongPixels());
        }
        return pixels.slice();
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// If this frame was constructed from `int` pixels, the returned data is created lazily by
    /// expanding each unsigned 8-bit channel to 16 bits.
    ///
    /// @return packed non-premultiplied ARGB pixels
    public long[] longPixels() {
        LongBuffer buffer = longPixelBuffer();
        long[] result = new long[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// If this frame was constructed from `int` pixels, the buffer is created lazily and cached.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public @UnmodifiableView LongBuffer longPixelBuffer() {
        LongBuffer pixels = longPixels;
        if (pixels == null) {
            longPixels = pixels = PixelBuffers.convertIntPixelsToLongPixels(requireIntPixels());
        }
        return pixels.slice();
    }

    /// Returns the cached `int` pixels or throws if they are unavailable.
    ///
    /// @return the cached `int` pixels
    private @Unmodifiable IntBuffer requireIntPixels() {
        IntBuffer pixels = intPixels;
        if (pixels == null) {
            throw new IllegalStateException("Int pixels are unavailable");
        }
        return pixels;
    }

    /// Returns the cached `long` pixels or throws if they are unavailable.
    ///
    /// @return the cached `long` pixels
    private @Unmodifiable LongBuffer requireLongPixels() {
        LongBuffer pixels = longPixels;
        if (pixels == null) {
            throw new IllegalStateException("Long pixels are unavailable");
        }
        return pixels;
    }
}
