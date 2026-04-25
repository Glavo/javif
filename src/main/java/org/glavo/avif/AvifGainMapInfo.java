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

/// Immutable metadata for one AVIF tone-mapped gain map association.
///
/// The descriptor exposes container-level `tmap` and gain-map image item metadata. It does not
/// imply that gain-map pixels have been decoded or applied to the base image.
@NotNullByDefault
public final class AvifGainMapInfo {
    /// The `tmap` derived image item id.
    private final int toneMappedImageItemId;
    /// The base image item id referenced by the `tmap` item.
    private final int baseImageItemId;
    /// The gain-map image item id referenced by the `tmap` item.
    private final int gainMapImageItemId;
    /// The BMFF item type for the tone-mapped item.
    private final String toneMappedItemType;
    /// The BMFF item type for the gain-map image item.
    private final String gainMapItemType;
    /// The tone-mapped image width in pixels, or -1 when unknown.
    private final int toneMappedWidth;
    /// The tone-mapped image height in pixels, or -1 when unknown.
    private final int toneMappedHeight;
    /// The gain-map image width in pixels, or -1 when unknown.
    private final int gainMapWidth;
    /// The gain-map image height in pixels, or -1 when unknown.
    private final int gainMapHeight;
    /// The gain-map AV1 bit depth, or `null` when unknown.
    private final @Nullable AvifBitDepth gainMapBitDepth;
    /// The gain-map AV1 chroma sampling layout, or `null` when unknown.
    private final @Nullable AvifPixelFormat gainMapPixelFormat;
    /// The gain-map metadata version field from the `tmap` payload.
    private final int metadataVersion;
    /// The minimum metadata version required by the `tmap` payload.
    private final int metadataMinimumVersion;
    /// The writer metadata version from the `tmap` payload.
    private final int metadataWriterVersion;
    /// Whether this implementation can parse the advertised gain-map metadata version.
    private final boolean metadataSupported;

    /// Creates gain-map metadata.
    ///
    /// @param toneMappedImageItemId the `tmap` derived image item id
    /// @param baseImageItemId the base image item id referenced by the `tmap` item
    /// @param gainMapImageItemId the gain-map image item id referenced by the `tmap` item
    /// @param toneMappedItemType the BMFF item type for the tone-mapped item
    /// @param gainMapItemType the BMFF item type for the gain-map image item
    /// @param toneMappedWidth the tone-mapped image width in pixels, or -1 when unknown
    /// @param toneMappedHeight the tone-mapped image height in pixels, or -1 when unknown
    /// @param gainMapWidth the gain-map image width in pixels, or -1 when unknown
    /// @param gainMapHeight the gain-map image height in pixels, or -1 when unknown
    /// @param gainMapBitDepth the gain-map AV1 bit depth, or `null` when unknown
    /// @param gainMapPixelFormat the gain-map AV1 chroma sampling layout, or `null` when unknown
    /// @param metadataVersion the gain-map metadata version field from the `tmap` payload
    /// @param metadataMinimumVersion the minimum metadata version required by the `tmap` payload
    /// @param metadataWriterVersion the writer metadata version from the `tmap` payload
    /// @param metadataSupported whether this implementation can parse the advertised metadata version
    public AvifGainMapInfo(
            int toneMappedImageItemId,
            int baseImageItemId,
            int gainMapImageItemId,
            String toneMappedItemType,
            String gainMapItemType,
            int toneMappedWidth,
            int toneMappedHeight,
            int gainMapWidth,
            int gainMapHeight,
            @Nullable AvifBitDepth gainMapBitDepth,
            @Nullable AvifPixelFormat gainMapPixelFormat,
            int metadataVersion,
            int metadataMinimumVersion,
            int metadataWriterVersion,
            boolean metadataSupported
    ) {
        if (toneMappedImageItemId <= 0) {
            throw new IllegalArgumentException("toneMappedImageItemId <= 0: " + toneMappedImageItemId);
        }
        if (baseImageItemId <= 0) {
            throw new IllegalArgumentException("baseImageItemId <= 0: " + baseImageItemId);
        }
        if (gainMapImageItemId <= 0) {
            throw new IllegalArgumentException("gainMapImageItemId <= 0: " + gainMapImageItemId);
        }
        if (!isKnownSize(toneMappedWidth, toneMappedHeight) && !isUnknownSize(toneMappedWidth, toneMappedHeight)) {
            throw new IllegalArgumentException("toneMappedWidth and toneMappedHeight must both be positive or both be -1");
        }
        if (!isKnownSize(gainMapWidth, gainMapHeight) && !isUnknownSize(gainMapWidth, gainMapHeight)) {
            throw new IllegalArgumentException("gainMapWidth and gainMapHeight must both be positive or both be -1");
        }
        if ((gainMapBitDepth == null) != (gainMapPixelFormat == null)) {
            throw new IllegalArgumentException("gainMapBitDepth and gainMapPixelFormat must both be present or both be null");
        }
        if (metadataVersion < 0 || metadataMinimumVersion < 0 || metadataWriterVersion < 0) {
            throw new IllegalArgumentException("metadata version fields must be non-negative");
        }

        this.toneMappedImageItemId = toneMappedImageItemId;
        this.baseImageItemId = baseImageItemId;
        this.gainMapImageItemId = gainMapImageItemId;
        this.toneMappedItemType = Objects.requireNonNull(toneMappedItemType, "toneMappedItemType");
        this.gainMapItemType = Objects.requireNonNull(gainMapItemType, "gainMapItemType");
        this.toneMappedWidth = toneMappedWidth;
        this.toneMappedHeight = toneMappedHeight;
        this.gainMapWidth = gainMapWidth;
        this.gainMapHeight = gainMapHeight;
        this.gainMapBitDepth = gainMapBitDepth;
        this.gainMapPixelFormat = gainMapPixelFormat;
        this.metadataVersion = metadataVersion;
        this.metadataMinimumVersion = metadataMinimumVersion;
        this.metadataWriterVersion = metadataWriterVersion;
        this.metadataSupported = metadataSupported;
    }

    /// Returns the `tmap` derived image item id.
    ///
    /// @return the `tmap` derived image item id
    public int toneMappedImageItemId() {
        return toneMappedImageItemId;
    }

    /// Returns the base image item id referenced by the `tmap` item.
    ///
    /// @return the base image item id
    public int baseImageItemId() {
        return baseImageItemId;
    }

    /// Returns the gain-map image item id referenced by the `tmap` item.
    ///
    /// @return the gain-map image item id
    public int gainMapImageItemId() {
        return gainMapImageItemId;
    }

    /// Returns the BMFF item type for the tone-mapped item.
    ///
    /// @return the BMFF item type for the tone-mapped item
    public String toneMappedItemType() {
        return toneMappedItemType;
    }

    /// Returns the BMFF item type for the gain-map image item.
    ///
    /// @return the BMFF item type for the gain-map image item
    public String gainMapItemType() {
        return gainMapItemType;
    }

    /// Returns the tone-mapped image width in pixels.
    ///
    /// @return the tone-mapped image width in pixels, or -1 when unknown
    public int toneMappedWidth() {
        return toneMappedWidth;
    }

    /// Returns the tone-mapped image height in pixels.
    ///
    /// @return the tone-mapped image height in pixels, or -1 when unknown
    public int toneMappedHeight() {
        return toneMappedHeight;
    }

    /// Returns the gain-map image width in pixels.
    ///
    /// @return the gain-map image width in pixels, or -1 when unknown
    public int gainMapWidth() {
        return gainMapWidth;
    }

    /// Returns the gain-map image height in pixels.
    ///
    /// @return the gain-map image height in pixels, or -1 when unknown
    public int gainMapHeight() {
        return gainMapHeight;
    }

    /// Returns the gain-map AV1 bit depth.
    ///
    /// @return the gain-map AV1 bit depth, or `null` when unknown
    public @Nullable AvifBitDepth gainMapBitDepth() {
        return gainMapBitDepth;
    }

    /// Returns the gain-map AV1 chroma sampling layout.
    ///
    /// @return the gain-map AV1 chroma sampling layout, or `null` when unknown
    public @Nullable AvifPixelFormat gainMapPixelFormat() {
        return gainMapPixelFormat;
    }

    /// Returns the gain-map metadata version field from the `tmap` payload.
    ///
    /// @return the gain-map metadata version field
    public int metadataVersion() {
        return metadataVersion;
    }

    /// Returns the minimum metadata version required by the `tmap` payload.
    ///
    /// @return the minimum metadata version required by the `tmap` payload
    public int metadataMinimumVersion() {
        return metadataMinimumVersion;
    }

    /// Returns the writer metadata version from the `tmap` payload.
    ///
    /// @return the writer metadata version from the `tmap` payload
    public int metadataWriterVersion() {
        return metadataWriterVersion;
    }

    /// Returns whether this implementation can parse the advertised gain-map metadata version.
    ///
    /// @return whether the gain-map metadata version is supported
    public boolean metadataSupported() {
        return metadataSupported;
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
