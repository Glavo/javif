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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.LongBuffer;

/// Decoded frame backed by packed `long` ARGB pixels.
@NotNullByDefault
public final class ArgbLongFrame extends DecodedFrame {
    /// Creates a decoded `long`-backed ARGB frame.
    ///
    /// @param width the output frame width in pixels
    /// @param height the output frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the chroma layout
    /// @param frameType the AV1 frame type
    /// @param visible whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels the packed non-premultiplied ARGB pixels
    public ArgbLongFrame(
            int width,
            int height,
            int bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
            long[] pixels
    ) {
        super(width, height, bitDepth, pixelFormat, frameType, visible, presentationIndex, pixels);
    }

    /// Creates a decoded `long`-backed ARGB frame from immutable pixel storage.
    ///
    /// The pixel buffer is stored as a read-only slice without copying. Callers must only pass
    /// immutable storage or storage they will never mutate after construction.
    ///
    /// @param width the output frame width in pixels
    /// @param height the output frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the chroma layout
    /// @param frameType the AV1 frame type
    /// @param visible whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param pixels the packed non-premultiplied ARGB pixels
    public ArgbLongFrame(
            int width,
            int height,
            int bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
            @Unmodifiable LongBuffer pixels
    ) {
        super(width, height, bitDepth, pixelFormat, frameType, visible, presentationIndex, pixels);
    }

    /// Returns packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ///
    /// @return the packed ARGB pixels
    public long[] pixels() {
        return longPixels();
    }

    /// Returns a read-only view of packed non-premultiplied ARGB pixels.
    ///
    /// @return a read-only view of packed non-premultiplied ARGB pixels
    public @UnmodifiableView LongBuffer pixelBuffer() {
        return longPixelBuffer();
    }
}
