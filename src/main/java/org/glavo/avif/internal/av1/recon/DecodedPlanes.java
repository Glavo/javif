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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.decode.PixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable decoded-plane snapshot produced after reconstruction and post-filtering.
///
/// This contract sits between reconstruction and public output conversion. Stored planes represent
/// the post-filter, post-super-resolution, pre-grain image state in the current stored-surface
/// domain.
@NotNullByDefault
public final class DecodedPlanes {
    /// The decoded bit depth.
    private final int bitDepth;

    /// The chroma layout of the decoded planes.
    private final PixelFormat pixelFormat;

    /// The stored luma width in samples.
    private final int codedWidth;

    /// The stored luma height in samples.
    private final int codedHeight;

    /// The presentation render width.
    private final int renderWidth;

    /// The presentation render height.
    private final int renderHeight;

    /// The decoded luma plane.
    private final DecodedPlane lumaPlane;

    /// The decoded chroma U plane, or `null` for monochrome output.
    private final @Nullable DecodedPlane chromaUPlane;

    /// The decoded chroma V plane, or `null` for monochrome output.
    private final @Nullable DecodedPlane chromaVPlane;

    /// Creates one immutable decoded-plane snapshot.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the chroma layout of the decoded planes
    /// @param codedWidth the stored luma width in samples
    /// @param codedHeight the stored luma height in samples
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane, or `null` for monochrome output
    /// @param chromaVPlane the decoded chroma V plane, or `null` for monochrome output
    public DecodedPlanes(
            int bitDepth,
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            int renderWidth,
            int renderHeight,
            DecodedPlane lumaPlane,
            @Nullable DecodedPlane chromaUPlane,
            @Nullable DecodedPlane chromaVPlane
    ) {
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        if (codedWidth <= 0) {
            throw new IllegalArgumentException("codedWidth <= 0: " + codedWidth);
        }
        if (codedHeight <= 0) {
            throw new IllegalArgumentException("codedHeight <= 0: " + codedHeight);
        }
        if (renderWidth <= 0) {
            throw new IllegalArgumentException("renderWidth <= 0: " + renderWidth);
        }
        if (renderHeight <= 0) {
            throw new IllegalArgumentException("renderHeight <= 0: " + renderHeight);
        }

        this.bitDepth = bitDepth;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.lumaPlane = Objects.requireNonNull(lumaPlane, "lumaPlane");
        this.chromaUPlane = chromaUPlane;
        this.chromaVPlane = chromaVPlane;

        if (lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight) {
            throw new IllegalArgumentException("lumaPlane dimensions do not match coded luma dimensions");
        }

        validateChromaPlanes();
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public int bitDepth() {
        return bitDepth;
    }

    /// Returns the chroma layout of the decoded planes.
    ///
    /// @return the chroma layout of the decoded planes
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns the stored luma width in samples.
    ///
    /// @return the stored luma width in samples
    public int codedWidth() {
        return codedWidth;
    }

    /// Returns the stored luma height in samples.
    ///
    /// @return the stored luma height in samples
    public int codedHeight() {
        return codedHeight;
    }

    /// Returns the presentation render width.
    ///
    /// @return the presentation render width
    public int renderWidth() {
        return renderWidth;
    }

    /// Returns the presentation render height.
    ///
    /// @return the presentation render height
    public int renderHeight() {
        return renderHeight;
    }

    /// Returns the decoded luma plane.
    ///
    /// @return the decoded luma plane
    public DecodedPlane lumaPlane() {
        return lumaPlane;
    }

    /// Returns the decoded chroma U plane, or `null` for monochrome output.
    ///
    /// @return the decoded chroma U plane, or `null` for monochrome output
    public @Nullable DecodedPlane chromaUPlane() {
        return chromaUPlane;
    }

    /// Returns the decoded chroma V plane, or `null` for monochrome output.
    ///
    /// @return the decoded chroma V plane, or `null` for monochrome output
    public @Nullable DecodedPlane chromaVPlane() {
        return chromaVPlane;
    }

    /// Returns whether this decoded snapshot contains chroma planes.
    ///
    /// @return whether this decoded snapshot contains chroma planes
    public boolean hasChroma() {
        return chromaUPlane != null && chromaVPlane != null;
    }

    /// Validates the stored chroma-plane arrangement against the pixel format.
    private void validateChromaPlanes() {
        if (pixelFormat == PixelFormat.I400) {
            if (chromaUPlane != null || chromaVPlane != null) {
                throw new IllegalArgumentException("I400 output must not carry chroma planes");
            }
            return;
        }

        if (chromaUPlane == null || chromaVPlane == null) {
            throw new IllegalArgumentException("Chroma planes are required for " + pixelFormat);
        }

        int expectedWidth = expectedChromaWidth(pixelFormat, codedWidth);
        int expectedHeight = expectedChromaHeight(pixelFormat, codedHeight);
        if (chromaUPlane.width() != expectedWidth || chromaUPlane.height() != expectedHeight) {
            throw new IllegalArgumentException("chromaUPlane dimensions do not match pixel format");
        }
        if (chromaVPlane.width() != expectedWidth || chromaVPlane.height() != expectedHeight) {
            throw new IllegalArgumentException("chromaVPlane dimensions do not match pixel format");
        }
    }

    /// Returns the expected chroma width for one pixel format.
    ///
    /// @param pixelFormat the chroma layout of the decoded planes
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width for one pixel format
    private static int expectedChromaWidth(PixelFormat pixelFormat, int codedWidth) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (codedWidth + 1) / 2;
            case I444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one pixel format.
    ///
    /// @param pixelFormat the chroma layout of the decoded planes
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height for one pixel format
    private static int expectedChromaHeight(PixelFormat pixelFormat, int codedHeight) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420 -> (codedHeight + 1) / 2;
            case I422, I444 -> codedHeight;
        };
    }
}
