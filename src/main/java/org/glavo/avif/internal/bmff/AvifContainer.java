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
package org.glavo.avif.internal.bmff;

import org.glavo.avif.AvifImageInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Parsed AVIF container data needed by the current reader.
@NotNullByDefault
public final class AvifContainer {
    /// The parsed image metadata.
    private final AvifImageInfo info;
    /// The primary image AV1 OBU payload, or `null` for grid images.
    private final byte @Nullable @Unmodifiable [] primaryItemPayload;
    /// The alpha auxiliary image AV1 OBU payload, or `null` when absent.
    private final byte @Nullable @Unmodifiable [] alphaItemPayload;
    /// Whether this is a grid derived image.
    private final boolean isGrid;
    /// Grid cell AV1 OBU payloads in row-major order, or `null`.
    private final byte @Nullable @Unmodifiable [] @Nullable @Unmodifiable [] gridCellPayloads;
    /// Grid row count.
    private final int gridRows;
    /// Grid column count.
    private final int gridColumns;
    /// Grid output width, or -1 if to be computed from cells.
    private final int gridOutputWidth;
    /// Grid output height, or -1 if to be computed from cells.
    private final int gridOutputHeight;

    /// The clean-aperture crop x offset, or -1.
    private final int clapCropX;
    /// The clean-aperture crop y offset, or -1.
    private final int clapCropY;
    /// The clean-aperture crop width, or -1.
    private final int clapCropWidth;
    /// The clean-aperture crop height, or -1.
    private final int clapCropHeight;
    /// The rotation code (0, 1, 2, 3), or -1.
    private final int rotationCode;
    /// The mirror axis (0 or 1), or -1.
    private final int mirrorAxis;

    /// Creates parsed AVIF container data without an alpha image or grid.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    public AvifContainer(AvifImageInfo info, byte[] primaryItemPayload) {
        this(info, primaryItemPayload, null, -1, -1, -1, -1, -1, -1);
    }

    /// Creates parsed AVIF container data with an optional alpha image and transforms.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    /// @param alphaItemPayload the alpha auxiliary image AV1 OBU payload, or `null`
    public AvifContainer(AvifImageInfo info, byte[] primaryItemPayload, byte @Nullable [] alphaItemPayload) {
        this(info, primaryItemPayload, alphaItemPayload, -1, -1, -1, -1, -1, -1);
    }

    /// Creates parsed AVIF container data with an optional alpha image and transforms.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    /// @param alphaItemPayload the alpha auxiliary image AV1 OBU payload, or `null`
    /// @param clapCropX the clean-aperture x offset, or -1
    /// @param clapCropY the clean-aperture y offset, or -1
    /// @param clapCropWidth the clean-aperture width, or -1
    /// @param clapCropHeight the clean-aperture height, or -1
    /// @param rotationCode the rotation code, or -1
    /// @param mirrorAxis the mirror axis, or -1
    public AvifContainer(
            AvifImageInfo info,
            byte[] primaryItemPayload,
            byte @Nullable [] alphaItemPayload,
            int clapCropX, int clapCropY,
            int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis
    ) {
        this.info = Objects.requireNonNull(info, "info");
        this.primaryItemPayload = Arrays.copyOf(
                Objects.requireNonNull(primaryItemPayload, "primaryItemPayload"),
                primaryItemPayload.length
        );
        this.alphaItemPayload = alphaItemPayload != null
                ? Arrays.copyOf(alphaItemPayload, alphaItemPayload.length)
                : null;
        this.isGrid = false;
        this.gridCellPayloads = null;
        this.gridRows = 0;
        this.gridColumns = 0;
        this.gridOutputWidth = 0;
        this.gridOutputHeight = 0;
        this.clapCropX = clapCropX;
        this.clapCropY = clapCropY;
        this.clapCropWidth = clapCropWidth;
        this.clapCropHeight = clapCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
    }

    /// Creates parsed AVIF container data for a grid derived image.
    ///
    /// @param info the parsed image metadata
    /// @param gridCellPayloads the grid cell AV1 OBU payloads in row-major order
    /// @param gridRows the grid row count
    /// @param gridColumns the grid column count
    /// @param gridOutputWidth the grid output width, or -1
    /// @param gridOutputHeight the grid output height, or -1
    public AvifContainer(
            AvifImageInfo info,
            byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            int gridRows,
            int gridColumns,
            int gridOutputWidth,
            int gridOutputHeight
    ) {
        this(info, gridCellPayloads, gridRows, gridColumns, gridOutputWidth, gridOutputHeight,
                -1, -1, -1, -1, -1, -1);
    }

    /// Creates parsed AVIF container data for a grid derived image with transforms.
    ///
    /// @param info the parsed image metadata
    /// @param gridCellPayloads the grid cell AV1 OBU payloads in row-major order
    /// @param gridRows the grid row count
    /// @param gridColumns the grid column count
    /// @param gridOutputWidth the grid output width, or -1
    /// @param gridOutputHeight the grid output height, or -1
    /// @param clapCropX the clean-aperture x offset, or -1
    /// @param clapCropY the clean-aperture y offset, or -1
    /// @param clapCropWidth the clean-aperture width, or -1
    /// @param clapCropHeight the clean-aperture height, or -1
    /// @param rotationCode the rotation code, or -1
    /// @param mirrorAxis the mirror axis, or -1
    public AvifContainer(
            AvifImageInfo info,
            byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            int gridRows,
            int gridColumns,
            int gridOutputWidth,
            int gridOutputHeight,
            int clapCropX, int clapCropY,
            int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis
    ) {
        this.info = Objects.requireNonNull(info, "info");
        Objects.requireNonNull(gridCellPayloads, "gridCellPayloads");
        if (gridRows <= 0) {
            throw new IllegalArgumentException("gridRows <= 0: " + gridRows);
        }
        if (gridColumns <= 0) {
            throw new IllegalArgumentException("gridColumns <= 0: " + gridColumns);
        }
        this.primaryItemPayload = null;
        this.alphaItemPayload = null;
        this.isGrid = true;
        this.gridCellPayloads = gridCellPayloads.clone();
        this.gridRows = gridRows;
        this.gridColumns = gridColumns;
        this.gridOutputWidth = gridOutputWidth;
        this.gridOutputHeight = gridOutputHeight;
        this.clapCropX = clapCropX;
        this.clapCropY = clapCropY;
        this.clapCropWidth = clapCropWidth;
        this.clapCropHeight = clapCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
    }

    /// Returns the parsed image metadata.
    ///
    /// @return the parsed image metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns the primary image AV1 OBU payload.
    ///
    /// @return the primary image AV1 OBU payload, or `null` for grid images
    public byte @Nullable [] primaryItemPayload() {
        if (primaryItemPayload == null) {
            return null;
        }
        return Arrays.copyOf(primaryItemPayload, primaryItemPayload.length);
    }

    /// Returns the alpha auxiliary image AV1 OBU payload.
    ///
    /// @return the alpha auxiliary image AV1 OBU payload, or `null`
    public byte @Nullable [] alphaItemPayload() {
        if (alphaItemPayload == null) {
            return null;
        }
        return Arrays.copyOf(alphaItemPayload, alphaItemPayload.length);
    }

    /// Returns whether this is a grid derived image.
    ///
    /// @return whether this is a grid derived image
    public boolean isGrid() {
        return isGrid;
    }

    /// Returns the grid cell AV1 OBU payloads in row-major order.
    ///
    /// @return the grid cell AV1 OBU payloads, or `null`
    public byte @Nullable @Unmodifiable [] @Nullable [] gridCellPayloads() {
        if (gridCellPayloads == null) {
            return null;
        }
        return gridCellPayloads.clone();
    }

    /// Returns the grid row count.
    ///
    /// @return the grid row count
    public int gridRows() {
        return gridRows;
    }

    /// Returns the grid column count.
    ///
    /// @return the grid column count
    public int gridColumns() {
        return gridColumns;
    }

    /// Returns the grid output width.
    ///
    /// @return the grid output width, or -1 if to be computed
    public int gridOutputWidth() {
        return gridOutputWidth;
    }

    /// Returns the grid output height.
    ///
    /// @return the grid output height, or -1 if to be computed
    public int gridOutputHeight() {
        return gridOutputHeight;
    }

    /// Returns whether a clean-aperture crop is present.
    ///
    /// @return whether a clean-aperture crop is present
    public boolean hasClapCrop() {
        return clapCropX >= 0;
    }

    /// Returns the clean-aperture crop x offset.
    ///
    /// @return the clean-aperture crop x offset
    public int clapCropX() {
        return clapCropX;
    }

    /// Returns the clean-aperture crop y offset.
    ///
    /// @return the clean-aperture crop y offset
    public int clapCropY() {
        return clapCropY;
    }

    /// Returns the clean-aperture crop width.
    ///
    /// @return the clean-aperture crop width
    public int clapCropWidth() {
        return clapCropWidth;
    }

    /// Returns the clean-aperture crop height.
    ///
    /// @return the clean-aperture crop height
    public int clapCropHeight() {
        return clapCropHeight;
    }

    /// Returns the rotation code.
    ///
    /// @return the rotation code (0, 1, 2, 3), or -1
    public int rotationCode() {
        return rotationCode;
    }

    /// Returns the mirror axis.
    ///
    /// @return the mirror axis (0 or 1), or -1
    public int mirrorAxis() {
        return mirrorAxis;
    }
}
