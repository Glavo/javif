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

import org.glavo.avif.internal.PixelBuffers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Decoded AVIF frame output.
///
/// After construction, instances are safe for concurrent read access, subject to the immutable
/// storage requirement of the buffer constructors. Each returned pixel buffer has independent
/// position and limit state.
@NotNullByDefault
public final class AvifFrame {
    /// The frame width in pixels.
    private final int width;
    /// The frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The decoded AV1 chroma sampling layout.
    private final Av1ChromaFormat chromaFormat;
    /// The zero-based frame index.
    private final int frameIndex;
    /// The packed ARGB pixel format used by this frame's native storage.
    private final AvifPixelFormat pixelFormat;
    /// Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted.
    private volatile @Nullable @Unmodifiable IntBuffer intPixels;
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted.
    private volatile @Nullable @Unmodifiable LongBuffer longPixels;

    /// Creates an AVIF frame from packed `int` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, Av1ChromaFormat chromaFormat, int frameIndex, int[] pixels) {
        this(width, height, bitDepth, chromaFormat, frameIndex,
                PixelBuffers.immutableIntPixels(Objects.requireNonNull(pixels, "pixels")), null);
    }

    /// Creates an AVIF frame from packed `long` ARGB pixels.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(int width, int height, AvifBitDepth bitDepth, Av1ChromaFormat chromaFormat, int frameIndex, long[] pixels) {
        this(width, height, bitDepth, chromaFormat, frameIndex,
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
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAARRGGBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            @Unmodifiable IntBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameIndex, PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates an AVIF frame from a packed `long` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param pixels      packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            @Unmodifiable LongBuffer pixels
    ) {
        this(width, height, bitDepth, chromaFormat, frameIndex, null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates an AVIF frame by taking exclusive ownership of packed `int` ARGB pixels.
    ///
    /// The caller must not retain or mutate `pixels` after this method returns.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels exclusively owned packed non-premultiplied ARGB pixels
    /// @return a frame backed directly by `pixels`
    static AvifFrame fromOwnedPixels(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            int @Unmodifiable [] pixels
    ) {
        IntBuffer storage = IntBuffer.wrap(Objects.requireNonNull(pixels, "pixels")).asReadOnlyBuffer();
        return new AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, storage, null);
    }

    /// Creates an AVIF frame by taking exclusive ownership of packed `long` ARGB pixels.
    ///
    /// The caller must not retain or mutate `pixels` after this method returns.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    /// @param pixels exclusively owned packed non-premultiplied ARGB pixels
    /// @return a frame backed directly by `pixels`
    static AvifFrame fromOwnedPixels(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            long @Unmodifiable [] pixels
    ) {
        LongBuffer storage = LongBuffer.wrap(Objects.requireNonNull(pixels, "pixels")).asReadOnlyBuffer();
        return new AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, null, storage);
    }

    /// Creates a decoded AVIF frame descriptor with one available pixel representation.
    ///
    /// @param width       the frame width in pixels
    /// @param height      the frame height in pixels
    /// @param bitDepth    the decoded bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex  the zero-based frame index
    /// @param intPixels   packed `int` pixels, or `null`
    /// @param longPixels  packed `long` pixels, or `null`
    private AvifFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int frameIndex,
            @Nullable @Unmodifiable IntBuffer intPixels,
            @Nullable @Unmodifiable LongBuffer longPixels
    ) {
        if (intPixels == null && longPixels == null) {
            throw new IllegalArgumentException("At least one pixel representation is required");
        }
        int pixelCount = checkedPixelCount(width, height);
        if (intPixels != null && intPixels.remaining() != pixelCount) {
            throw new IllegalArgumentException(
                    "Int pixel count does not match dimensions: " + intPixels.remaining()
            );
        }
        if (longPixels != null && longPixels.remaining() != pixelCount) {
            throw new IllegalArgumentException(
                    "Long pixel count does not match dimensions: " + longPixels.remaining()
            );
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex < 0: " + frameIndex);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.frameIndex = frameIndex;
        this.pixelFormat = intPixels != null ? AvifPixelFormat.ARGB_8888 : AvifPixelFormat.ARGB_16161616;
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
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns the zero-based frame index.
    ///
    /// @return the zero-based frame index
    public int frameIndex() {
        return frameIndex;
    }

    /// Returns the packed ARGB pixel format used by this frame's native storage.
    ///
    /// @return the packed ARGB pixel format
    public AvifPixelFormat pixelFormat() {
        return pixelFormat;
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
            synchronized (this) {
                pixels = intPixels;
                if (pixels == null) {
                    pixels = PixelBuffers.convertLongPixelsToIntPixels(requireLongPixels());
                    intPixels = pixels;
                }
            }
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
            synchronized (this) {
                pixels = longPixels;
                if (pixels == null) {
                    pixels = PixelBuffers.convertIntPixelsToLongPixels(requireIntPixels());
                    longPixels = pixels;
                }
            }
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

    /// Returns the exact pixel count for validated positive dimensions.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @return the pixel count
    private static int checkedPixelCount(int width, int height) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        long pixelCount = (long) width * height;
        if (pixelCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Frame dimensions are too large: " + width + "x" + height);
        }
        return (int) pixelCount;
    }
}
