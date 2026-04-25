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

import java.util.Objects;

/// Immutable metadata for one auxiliary image associated with an AVIF primary image.
@NotNullByDefault
public final class AvifAuxiliaryImageInfo {
    /// The AVIF auxiliary image type string used for alpha images.
    public static final String ALPHA_TYPE = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha";

    /// The BMFF item id for the auxiliary image.
    private final int itemId;
    /// The `auxC` auxiliary image type string.
    private final String auxiliaryType;
    /// The BMFF item type for the auxiliary item.
    private final String itemType;
    /// The auxiliary image width in pixels, or -1 when unknown.
    private final int width;
    /// The auxiliary image height in pixels, or -1 when unknown.
    private final int height;
    /// The decoded AV1 bit depth, or `null` when the auxiliary item is not an AV1 image.
    private final @Nullable AvifBitDepth bitDepth;
    /// The AV1 chroma sampling layout, or `null` when the auxiliary item is not an AV1 image.
    private final @Nullable AvifPixelFormat pixelFormat;

    /// Creates auxiliary image metadata.
    ///
    /// @param itemId the BMFF item id for the auxiliary image
    /// @param auxiliaryType the `auxC` auxiliary image type string
    /// @param itemType the BMFF item type for the auxiliary item
    /// @param width the auxiliary image width in pixels, or -1 when unknown
    /// @param height the auxiliary image height in pixels, or -1 when unknown
    /// @param bitDepth the decoded AV1 bit depth, or `null` when the auxiliary item is not an AV1 image
    /// @param pixelFormat the AV1 chroma sampling layout, or `null` when the auxiliary item is not an AV1 image
    public AvifAuxiliaryImageInfo(
            int itemId,
            String auxiliaryType,
            String itemType,
            int width,
            int height,
            @Nullable AvifBitDepth bitDepth,
            @Nullable AvifPixelFormat pixelFormat
    ) {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId <= 0: " + itemId);
        }
        if (!isKnownSize(width, height) && !isUnknownSize(width, height)) {
            throw new IllegalArgumentException("width and height must both be positive or both be -1");
        }
        if ((bitDepth == null) != (pixelFormat == null)) {
            throw new IllegalArgumentException("bitDepth and pixelFormat must both be present or both be null");
        }

        this.itemId = itemId;
        this.auxiliaryType = Objects.requireNonNull(auxiliaryType, "auxiliaryType");
        this.itemType = Objects.requireNonNull(itemType, "itemType");
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.pixelFormat = pixelFormat;
    }

    /// Returns the BMFF item id for the auxiliary image.
    ///
    /// @return the BMFF item id
    public int itemId() {
        return itemId;
    }

    /// Returns the `auxC` auxiliary image type string.
    ///
    /// @return the auxiliary image type string
    public String auxiliaryType() {
        return auxiliaryType;
    }

    /// Returns the BMFF item type for the auxiliary item.
    ///
    /// @return the BMFF item type
    public String itemType() {
        return itemType;
    }

    /// Returns the auxiliary image width in pixels.
    ///
    /// A value of -1 means the width is unknown.
    ///
    /// @return the auxiliary image width in pixels, or -1 when unknown
    public int width() {
        return width;
    }

    /// Returns the auxiliary image height in pixels.
    ///
    /// A value of -1 means the height is unknown.
    ///
    /// @return the auxiliary image height in pixels, or -1 when unknown
    public int height() {
        return height;
    }

    /// Returns the decoded AV1 bit depth.
    ///
    /// A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
    ///
    /// @return the decoded AV1 bit depth, or `null`
    public @Nullable AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the AV1 chroma sampling layout.
    ///
    /// A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
    ///
    /// @return the AV1 chroma sampling layout, or `null`
    public @Nullable AvifPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns whether this auxiliary image is an alpha image.
    ///
    /// @return whether this auxiliary image is an alpha image
    public boolean isAlpha() {
        return ALPHA_TYPE.equals(auxiliaryType);
    }

    /// Returns whether the supplied dimensions represent a known image size.
    ///
    /// @param width the width in pixels
    /// @param height the height in pixels
    /// @return whether both dimensions are positive
    private static boolean isKnownSize(int width, int height) {
        return width > 0 && height > 0;
    }

    /// Returns whether the supplied dimensions represent an unknown image size.
    ///
    /// @param width the width in pixels
    /// @param height the height in pixels
    /// @return whether both dimensions are -1
    private static boolean isUnknownSize(int width, int height) {
        return width == -1 && height == -1;
    }
}
