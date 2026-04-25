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
package org.glavo.avif.decode;

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.internal.PixelBuffers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Decoded AV1 frame output exposed by the public API.
@NotNullByDefault
public final class DecodedFrame {
    /// The output frame width in pixels.
    private final int width;
    /// The output frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The chroma layout of the decoded frame.
    private final PixelFormat pixelFormat;
    /// The AV1 frame type.
    private final FrameType frameType;
    /// Whether the frame is visible.
    private final boolean visible;
    /// The zero-based presentation index of the frame.
    private final long presentationIndex;
    /// Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted.
    private @Nullable @Unmodifiable IntBuffer intPixels;
    /// Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted.
    private @Nullable @Unmodifiable LongBuffer longPixels;

    /// Creates a decoded frame from a packed `int` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param pixelFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
            @Unmodifiable IntBuffer pixels
    ) {
        this(width, height, bitDepth, pixelFormat, frameType, visible, presentationIndex,
                PixelBuffers.immutableIntPixels(pixels), null);
    }

    /// Creates a decoded frame from a packed `long` ARGB pixel buffer.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param pixelFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels            the packed non-premultiplied ARGB pixels
    public DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
            @Unmodifiable LongBuffer pixels
    ) {
        this(width, height, bitDepth, pixelFormat, frameType, visible, presentationIndex,
                null, PixelBuffers.immutableLongPixels(pixels));
    }

    /// Creates a decoded frame descriptor with one available pixel representation.
    ///
    /// @param width             the output frame width in pixels
    /// @param height            the output frame height in pixels
    /// @param bitDepth          the decoded bit depth
    /// @param pixelFormat       the chroma layout
    /// @param frameType         the AV1 frame type
    /// @param visible           whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param intPixels         packed `int` pixels, or `null`
    /// @param longPixels        packed `long` pixels, or `null`
    private DecodedFrame(
            int width,
            int height,
            AvifBitDepth bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
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
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
        this.intPixels = intPixels;
        this.longPixels = longPixels;
    }

    /// Returns the output frame width in pixels.
    ///
    /// @return the output frame width
    public int width() {
        return width;
    }

    /// Returns the output frame height in pixels.
    ///
    /// @return the output frame height
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the chroma layout of the decoded frame.
    ///
    /// @return the chroma layout
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns the AV1 frame type.
    ///
    /// @return the AV1 frame type
    public FrameType frameType() {
        return frameType;
    }

    /// Returns whether the frame is visible.
    ///
    /// @return whether the frame is visible
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index of the frame.
    ///
    /// @return the presentation index
    public long presentationIndex() {
        return presentationIndex;
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
    ///
    /// If this frame was constructed from `long` pixels, the returned data is created lazily by
    /// reducing each unsigned 16-bit channel to 8 bits with rounding.
    ///
    /// @return the packed ARGB pixels
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
    /// @return the packed ARGB pixels
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
