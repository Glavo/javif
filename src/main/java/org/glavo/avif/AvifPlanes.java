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

import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable raw decoded AVIF color planes.
///
/// These planes expose the decoded AV1 color image before AVIF auxiliary alpha composition and
/// AVIF item transforms such as `clap`, `irot`, and `imir` are applied.
@NotNullByDefault
public final class AvifPlanes {
    /// The decoded sample bit depth.
    private final AvifBitDepth bitDepth;
    /// The decoded AV1 chroma sampling layout.
    private final AvifPixelFormat pixelFormat;
    /// The stored luma width in samples.
    private final int codedWidth;
    /// The stored luma height in samples.
    private final int codedHeight;
    /// The presentation render width before AVIF item transforms.
    private final int renderWidth;
    /// The presentation render height before AVIF item transforms.
    private final int renderHeight;
    /// The decoded luma plane.
    private final AvifPlane lumaPlane;
    /// The decoded chroma U plane, or `null` for monochrome images.
    private final @Nullable AvifPlane chromaUPlane;
    /// The decoded chroma V plane, or `null` for monochrome images.
    private final @Nullable AvifPlane chromaVPlane;

    /// Creates raw decoded AVIF color planes.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the stored luma width in samples
    /// @param codedHeight the stored luma height in samples
    /// @param renderWidth the presentation render width before AVIF item transforms
    /// @param renderHeight the presentation render height before AVIF item transforms
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane, or `null` for monochrome images
    /// @param chromaVPlane the decoded chroma V plane, or `null` for monochrome images
    public AvifPlanes(
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            int renderWidth,
            int renderHeight,
            AvifPlane lumaPlane,
            @Nullable AvifPlane chromaUPlane,
            @Nullable AvifPlane chromaVPlane
    ) {
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

        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.lumaPlane = Objects.requireNonNull(lumaPlane, "lumaPlane");
        this.chromaUPlane = chromaUPlane;
        this.chromaVPlane = chromaVPlane;
        validatePlanes();
    }

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the decoded AV1 chroma sampling layout.
    ///
    /// @return the decoded AV1 chroma sampling layout
    public AvifPixelFormat pixelFormat() {
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

    /// Returns the presentation render width before AVIF item transforms.
    ///
    /// @return the presentation render width before AVIF item transforms
    public int renderWidth() {
        return renderWidth;
    }

    /// Returns the presentation render height before AVIF item transforms.
    ///
    /// @return the presentation render height before AVIF item transforms
    public int renderHeight() {
        return renderHeight;
    }

    /// Returns the decoded luma plane.
    ///
    /// @return the decoded luma plane
    public AvifPlane lumaPlane() {
        return lumaPlane;
    }

    /// Returns the decoded chroma U plane.
    ///
    /// @return the decoded chroma U plane, or `null` for monochrome images
    public @Nullable AvifPlane chromaUPlane() {
        return chromaUPlane;
    }

    /// Returns the decoded chroma V plane.
    ///
    /// @return the decoded chroma V plane, or `null` for monochrome images
    public @Nullable AvifPlane chromaVPlane() {
        return chromaVPlane;
    }

    /// Returns whether chroma planes are present.
    ///
    /// @return whether chroma planes are present
    public boolean hasChroma() {
        return chromaUPlane != null && chromaVPlane != null;
    }

    /// Creates public raw planes from internal decoded planes.
    ///
    /// @param planes the internal decoded planes
    /// @return public raw decoded planes
    static AvifPlanes fromDecodedPlanes(DecodedPlanes planes) {
        DecodedPlanes checkedPlanes = Objects.requireNonNull(planes, "planes");
        return new AvifPlanes(
                AvifBitDepth.fromBits(checkedPlanes.bitDepth()),
                checkedPlanes.pixelFormat(),
                checkedPlanes.codedWidth(),
                checkedPlanes.codedHeight(),
                checkedPlanes.renderWidth(),
                checkedPlanes.renderHeight(),
                fromDecodedPlane(checkedPlanes.lumaPlane()),
                fromNullableDecodedPlane(checkedPlanes.chromaUPlane()),
                fromNullableDecodedPlane(checkedPlanes.chromaVPlane())
        );
    }

    /// Creates a public plane from one internal decoded plane.
    ///
    /// @param plane the internal decoded plane
    /// @return the public plane
    private static AvifPlane fromDecodedPlane(DecodedPlane plane) {
        DecodedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
        return new AvifPlane(
                checkedPlane.width(),
                checkedPlane.height(),
                checkedPlane.stride(),
                checkedPlane.sampleBuffer()
        );
    }

    /// Creates a public plane from one optional internal decoded plane.
    ///
    /// @param plane the internal decoded plane, or `null`
    /// @return the public plane, or `null`
    private static @Nullable AvifPlane fromNullableDecodedPlane(@Nullable DecodedPlane plane) {
        return plane != null ? fromDecodedPlane(plane) : null;
    }

    /// Validates the plane layout against the chroma format.
    private void validatePlanes() {
        if (lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight) {
            throw new IllegalArgumentException("lumaPlane dimensions do not match coded luma dimensions");
        }
        if (pixelFormat == AvifPixelFormat.I400) {
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
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width
    private static int expectedChromaWidth(AvifPixelFormat pixelFormat, int codedWidth) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (codedWidth + 1) / 2;
            case I444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one pixel format.
    ///
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height
    private static int expectedChromaHeight(AvifPixelFormat pixelFormat, int codedHeight) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420 -> (codedHeight + 1) / 2;
            case I422, I444 -> codedHeight;
        };
    }
}
