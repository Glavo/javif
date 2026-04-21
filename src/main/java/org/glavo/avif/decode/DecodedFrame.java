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

import java.util.Objects;

/// Base class for decoded AV1 frames exposed by the public API.
@NotNullByDefault
public abstract sealed class DecodedFrame permits ArgbIntFrame, ArgbLongFrame {
    /// The output frame width in pixels.
    private final int width;
    /// The output frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final int bitDepth;
    /// The chroma layout of the decoded frame.
    private final PixelFormat pixelFormat;
    /// The AV1 frame type.
    private final FrameType frameType;
    /// Whether the frame is visible.
    private final boolean visible;
    /// The zero-based presentation index of the frame.
    private final long presentationIndex;

    /// Creates a decoded frame descriptor.
    ///
    /// @param width the output frame width in pixels
    /// @param height the output frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the chroma layout
    /// @param frameType the AV1 frame type
    /// @param visible whether the frame is visible
    /// @param presentationIndex the zero-based presentation index
    protected DecodedFrame(
            int width,
            int height,
            int bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
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
    public int bitDepth() {
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
}
