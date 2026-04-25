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

import java.util.Objects;

/// Base class for decoded AVIF frame output.
@NotNullByDefault
public abstract sealed class AvifFrame permits AvifIntFrame, AvifLongFrame {
    /// The frame width in pixels.
    private final int width;
    /// The frame height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final int bitDepth;
    /// The decoded AV1 chroma sampling layout.
    private final PixelFormat pixelFormat;
    /// The zero-based frame index.
    private final int frameIndex;

    /// Creates a decoded AVIF frame descriptor.
    ///
    /// @param width the frame width in pixels
    /// @param height the frame height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param frameIndex the zero-based frame index
    protected AvifFrame(int width, int height, int bitDepth, PixelFormat pixelFormat, int frameIndex) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.frameIndex = frameIndex;
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
    public int bitDepth() {
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
}
