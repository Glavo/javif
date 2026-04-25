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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable metadata for one parsed AVIF image.
@NotNullByDefault
public final class AvifImageInfo {
    /// The display width in pixels.
    private final int width;
    /// The display height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The AV1 chroma sampling layout.
    private final AvifPixelFormat pixelFormat;
    /// Whether an alpha auxiliary image is present.
    private final boolean alphaPresent;
    /// Whether the input is an animated image sequence.
    private final boolean animated;
    /// The number of frames advertised by the container.
    private final int frameCount;
    /// The parsed color information, or `null`.
    private final @Nullable AvifColorInfo colorInfo;
    /// The embedded ICC profile payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer iccProfile;
    /// The embedded Exif metadata payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer exif;
    /// The embedded XMP metadata payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer xmp;

    /// Creates image metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo
    ) {
        this(width, height, bitDepth, pixelFormat, alphaPresent, animated, frameCount, colorInfo, null, null, null);
    }

    /// Creates image metadata with embedded ICC, Exif, and XMP payloads.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount <= 0: " + frameCount);
        }

        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.alphaPresent = alphaPresent;
        this.animated = animated;
        this.frameCount = frameCount;
        this.colorInfo = colorInfo;
        this.iccProfile = immutableBytes(iccProfile);
        this.exif = immutableBytes(exif);
        this.xmp = immutableBytes(xmp);
    }

    /// Returns the display width in pixels.
    ///
    /// @return the display width in pixels
    public int width() {
        return width;
    }

    /// Returns the display height in pixels.
    ///
    /// @return the display height in pixels
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the AV1 chroma sampling layout.
    ///
    /// @return the AV1 chroma sampling layout
    public AvifPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns whether an alpha auxiliary image is present.
    ///
    /// @return whether an alpha auxiliary image is present
    public boolean alphaPresent() {
        return alphaPresent;
    }

    /// Returns whether the input is an animated image sequence.
    ///
    /// @return whether the input is an animated image sequence
    public boolean animated() {
        return animated;
    }

    /// Returns the number of frames advertised by the container.
    ///
    /// @return the number of frames advertised by the container
    public int frameCount() {
        return frameCount;
    }

    /// Returns the parsed color information.
    ///
    /// @return the parsed color information, or `null`
    public @Nullable AvifColorInfo colorInfo() {
        return colorInfo;
    }

    /// Returns the embedded ICC profile payload.
    ///
    /// @return a read-only view of the embedded ICC profile payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer iccProfile() {
        return byteView(iccProfile);
    }

    /// Returns the embedded Exif metadata payload.
    ///
    /// The returned payload excludes the AVIF `exif_tiff_header_offset` field
    /// and matches the Exif byte sequence exposed by libavif.
    ///
    /// @return a read-only view of the embedded Exif metadata payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer exif() {
        return byteView(exif);
    }

    /// Returns the embedded XMP metadata payload.
    ///
    /// @return a read-only view of the embedded XMP metadata payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer xmp() {
        return byteView(xmp);
    }

    /// Creates immutable byte-buffer storage for one optional payload.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte-buffer storage, or `null`
    private static @Nullable @Unmodifiable ByteBuffer immutableBytes(byte @Nullable [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length)).asReadOnlyBuffer();
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static @Nullable @UnmodifiableView ByteBuffer byteView(@Nullable @Unmodifiable ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
