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
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Parsed AVIF container data needed by the current reader.
@NotNullByDefault
public final class AvifContainer {
    /// The parsed image metadata.
    private final AvifImageInfo info;
    /// The primary image AV1 OBU payload, or `null` for grid images.
    private final @Nullable @Unmodifiable ByteBuffer primaryItemPayload;
    /// The alpha auxiliary image AV1 OBU payload, or `null` when absent.
    private final @Nullable @Unmodifiable ByteBuffer alphaItemPayload;
    /// Whether this is a grid derived image.
    private final boolean isGrid;
    /// Grid cell AV1 OBU payloads in row-major order, or `null`.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] gridCellPayloads;
    /// Alpha grid cell AV1 OBU payloads in row-major order, or `null`.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] gridAlphaCellPayloads;
    /// Grid row count.
    private final int gridRows;
    /// Grid column count.
    private final int gridColumns;
    /// Grid output width, or -1 if to be computed from cells.
    private final int gridOutputWidth;
    /// Grid output height, or -1 if to be computed from cells.
    private final int gridOutputHeight;
    /// Alpha grid row count.
    private final int gridAlphaRows;
    /// Alpha grid column count.
    private final int gridAlphaColumns;
    /// Alpha grid output width.
    private final int gridAlphaOutputWidth;
    /// Alpha grid output height.
    private final int gridAlphaOutputHeight;
    /// The gain-map AV1 OBU payload, or `null` when absent or grid-derived.
    private final @Nullable @Unmodifiable ByteBuffer gainMapItemPayload;
    /// Gain-map grid cell AV1 OBU payloads in row-major order, or `null`.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] gainMapGridCellPayloads;
    /// Gain-map grid row count.
    private final int gainMapGridRows;
    /// Gain-map grid column count.
    private final int gainMapGridColumns;
    /// Gain-map grid output width.
    private final int gainMapGridOutputWidth;
    /// Gain-map grid output height.
    private final int gainMapGridOutputHeight;

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

    /// Whether this is an AVIS image sequence.
    private final boolean isSequence;
    /// The AV1 OBU payloads for each sample in order.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] samplePayloads;
    /// The frame duration deltas in media timescale units.
    private final int @Unmodifiable [] frameDeltas;
    /// The number of samples.
    private final int sampleCount;
    /// The media timescale.
    private final int mediaTimescale;
    /// The total media duration in timescale units.
    private final long mediaDuration;

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
        this(info, primaryItemPayload, alphaItemPayload, null, null, 0, 0, 0, 0,
                clapCropX, clapCropY, clapCropWidth, clapCropHeight, rotationCode, mirrorAxis);
    }

    /// Creates parsed AVIF container data with optional alpha, gain-map payloads, and transforms.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    /// @param alphaItemPayload the alpha auxiliary image AV1 OBU payload, or `null`
    /// @param gainMapItemPayload the gain-map AV1 OBU payload, or `null`
    /// @param gainMapGridCellPayloads the gain-map grid cell AV1 OBU payloads, or `null`
    /// @param gainMapGridRows the gain-map grid row count
    /// @param gainMapGridColumns the gain-map grid column count
    /// @param gainMapGridOutputWidth the gain-map grid output width
    /// @param gainMapGridOutputHeight the gain-map grid output height
    /// @param clapCropX the clean-aperture x offset, or -1
    /// @param clapCropY the clean-aperture y offset, or -1
    /// @param clapCropWidth the clean-aperture width, or -1
    /// @param clapCropHeight the clean-aperture height, or -1
    /// @param rotationCode the rotation code, or -1
    /// @param mirrorAxis the mirror axis, or -1
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifContainer(
            AvifImageInfo info,
            byte[] primaryItemPayload,
            byte @Nullable [] alphaItemPayload,
            byte @Nullable [] gainMapItemPayload,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] gainMapGridCellPayloads,
            int gainMapGridRows, int gainMapGridColumns,
            int gainMapGridOutputWidth, int gainMapGridOutputHeight,
            int clapCropX, int clapCropY,
            int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis
    ) {
        this.info = Objects.requireNonNull(info, "info");
        this.primaryItemPayload = immutablePayload(Objects.requireNonNull(primaryItemPayload, "primaryItemPayload"));
        this.alphaItemPayload = alphaItemPayload != null
                ? immutablePayload(alphaItemPayload)
                : null;
        if (gainMapItemPayload != null && gainMapGridCellPayloads != null) {
            throw new IllegalArgumentException("gainMapItemPayload and gainMapGridCellPayloads are mutually exclusive");
        }
        if (gainMapGridCellPayloads != null) {
            if (gainMapGridRows <= 0) throw new IllegalArgumentException("gainMapGridRows <= 0: " + gainMapGridRows);
            if (gainMapGridColumns <= 0) throw new IllegalArgumentException("gainMapGridColumns <= 0: " + gainMapGridColumns);
        }
        this.isGrid = false;
        this.gridCellPayloads = null;
        this.gridAlphaCellPayloads = null;
        this.gridRows = 0;
        this.gridColumns = 0;
        this.gridOutputWidth = 0;
        this.gridOutputHeight = 0;
        this.gridAlphaRows = 0;
        this.gridAlphaColumns = 0;
        this.gridAlphaOutputWidth = 0;
        this.gridAlphaOutputHeight = 0;
        this.gainMapItemPayload = gainMapItemPayload != null
                ? immutablePayload(gainMapItemPayload)
                : null;
        this.gainMapGridCellPayloads = gainMapGridCellPayloads != null
                ? immutablePayloads(gainMapGridCellPayloads)
                : null;
        this.gainMapGridRows = gainMapGridRows;
        this.gainMapGridColumns = gainMapGridColumns;
        this.gainMapGridOutputWidth = gainMapGridOutputWidth;
        this.gainMapGridOutputHeight = gainMapGridOutputHeight;
        this.clapCropX = clapCropX;
        this.clapCropY = clapCropY;
        this.clapCropWidth = clapCropWidth;
        this.clapCropHeight = clapCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
        this.isSequence = false;
        this.samplePayloads = null;
        this.frameDeltas = new int[0];
        this.sampleCount = 0;
        this.mediaTimescale = 0;
        this.mediaDuration = 0;
    }

    /// Creates parsed AVIF container data for a grid derived image.
    public AvifContainer(AvifImageInfo info, byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            int gridRows, int gridColumns, int gridOutputWidth, int gridOutputHeight) {
        this(info, gridCellPayloads, gridRows, gridColumns, gridOutputWidth, gridOutputHeight,
                -1, -1, -1, -1, -1, -1);
    }

    /// Creates parsed AVIF container data for a grid derived image with transforms.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifContainer(AvifImageInfo info, byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            int gridRows, int gridColumns, int gridOutputWidth, int gridOutputHeight,
            int clapCropX, int clapCropY, int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis) {
        this(info, gridCellPayloads, null, null,
                gridRows, gridColumns, gridOutputWidth, gridOutputHeight,
                0, 0, 0, 0,
                clapCropX, clapCropY, clapCropWidth, clapCropHeight, rotationCode, mirrorAxis);
    }

    /// Creates parsed AVIF container data for a grid derived image with optional alpha payloads.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifContainer(
            AvifImageInfo info,
            byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            byte @Nullable [] alphaItemPayload,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] gridAlphaCellPayloads,
            int gridRows, int gridColumns, int gridOutputWidth, int gridOutputHeight,
            int gridAlphaRows, int gridAlphaColumns, int gridAlphaOutputWidth, int gridAlphaOutputHeight,
            int clapCropX, int clapCropY, int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis
    ) {
        this(info, gridCellPayloads, alphaItemPayload, gridAlphaCellPayloads,
                null, null, 0, 0, 0, 0,
                gridRows, gridColumns, gridOutputWidth, gridOutputHeight,
                gridAlphaRows, gridAlphaColumns, gridAlphaOutputWidth, gridAlphaOutputHeight,
                clapCropX, clapCropY, clapCropWidth, clapCropHeight, rotationCode, mirrorAxis);
    }

    /// Creates parsed AVIF container data for a grid derived image with optional alpha and gain-map payloads.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifContainer(
            AvifImageInfo info,
            byte @Unmodifiable [] @Unmodifiable [] gridCellPayloads,
            byte @Nullable [] alphaItemPayload,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] gridAlphaCellPayloads,
            byte @Nullable [] gainMapItemPayload,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] gainMapGridCellPayloads,
            int gainMapGridRows, int gainMapGridColumns,
            int gainMapGridOutputWidth, int gainMapGridOutputHeight,
            int gridRows, int gridColumns, int gridOutputWidth, int gridOutputHeight,
            int gridAlphaRows, int gridAlphaColumns, int gridAlphaOutputWidth, int gridAlphaOutputHeight,
            int clapCropX, int clapCropY, int clapCropWidth, int clapCropHeight,
            int rotationCode, int mirrorAxis
    ) {
        this.info = Objects.requireNonNull(info, "info");
        Objects.requireNonNull(gridCellPayloads, "gridCellPayloads");
        if (gridRows <= 0) throw new IllegalArgumentException("gridRows <= 0: " + gridRows);
        if (gridColumns <= 0) throw new IllegalArgumentException("gridColumns <= 0: " + gridColumns);
        if (gridAlphaCellPayloads != null) {
            if (gridAlphaRows <= 0) throw new IllegalArgumentException("gridAlphaRows <= 0: " + gridAlphaRows);
            if (gridAlphaColumns <= 0) throw new IllegalArgumentException("gridAlphaColumns <= 0: " + gridAlphaColumns);
        }
        if (gainMapItemPayload != null && gainMapGridCellPayloads != null) {
            throw new IllegalArgumentException("gainMapItemPayload and gainMapGridCellPayloads are mutually exclusive");
        }
        if (gainMapGridCellPayloads != null) {
            if (gainMapGridRows <= 0) throw new IllegalArgumentException("gainMapGridRows <= 0: " + gainMapGridRows);
            if (gainMapGridColumns <= 0) throw new IllegalArgumentException("gainMapGridColumns <= 0: " + gainMapGridColumns);
        }
        this.primaryItemPayload = null;
        this.alphaItemPayload = alphaItemPayload != null
                ? immutablePayload(alphaItemPayload)
                : null;
        this.isGrid = true;
        this.gridCellPayloads = immutablePayloads(gridCellPayloads);
        this.gridAlphaCellPayloads = gridAlphaCellPayloads != null
                ? immutablePayloads(gridAlphaCellPayloads)
                : null;
        this.gridRows = gridRows;
        this.gridColumns = gridColumns;
        this.gridOutputWidth = gridOutputWidth;
        this.gridOutputHeight = gridOutputHeight;
        this.gridAlphaRows = gridAlphaRows;
        this.gridAlphaColumns = gridAlphaColumns;
        this.gridAlphaOutputWidth = gridAlphaOutputWidth;
        this.gridAlphaOutputHeight = gridAlphaOutputHeight;
        this.gainMapItemPayload = gainMapItemPayload != null
                ? immutablePayload(gainMapItemPayload)
                : null;
        this.gainMapGridCellPayloads = gainMapGridCellPayloads != null
                ? immutablePayloads(gainMapGridCellPayloads)
                : null;
        this.gainMapGridRows = gainMapGridRows;
        this.gainMapGridColumns = gainMapGridColumns;
        this.gainMapGridOutputWidth = gainMapGridOutputWidth;
        this.gainMapGridOutputHeight = gainMapGridOutputHeight;
        this.clapCropX = clapCropX;
        this.clapCropY = clapCropY;
        this.clapCropWidth = clapCropWidth;
        this.clapCropHeight = clapCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
        this.isSequence = false;
        this.samplePayloads = null;
        this.frameDeltas = new int[0];
        this.sampleCount = 0;
        this.mediaTimescale = 0;
        this.mediaDuration = 0;
    }

    /// Creates parsed AVIF container data for an image sequence.
    public AvifContainer(AvifImageInfo info, byte @Unmodifiable [] @Unmodifiable [] samplePayloads,
            int @Unmodifiable [] frameDeltas, int sampleCount, int mediaTimescale, long mediaDuration) {
        this.info = Objects.requireNonNull(info, "info");
        Objects.requireNonNull(samplePayloads, "samplePayloads");
        Objects.requireNonNull(frameDeltas, "frameDeltas");
        this.primaryItemPayload = null;
        this.alphaItemPayload = null;
        this.isGrid = false;
        this.gridCellPayloads = null;
        this.gridAlphaCellPayloads = null;
        this.gridRows = 0;
        this.gridColumns = 0;
        this.gridOutputWidth = 0;
        this.gridOutputHeight = 0;
        this.gridAlphaRows = 0;
        this.gridAlphaColumns = 0;
        this.gridAlphaOutputWidth = 0;
        this.gridAlphaOutputHeight = 0;
        this.gainMapItemPayload = null;
        this.gainMapGridCellPayloads = null;
        this.gainMapGridRows = 0;
        this.gainMapGridColumns = 0;
        this.gainMapGridOutputWidth = 0;
        this.gainMapGridOutputHeight = 0;
        this.clapCropX = -1;
        this.clapCropY = -1;
        this.clapCropWidth = -1;
        this.clapCropHeight = -1;
        this.rotationCode = -1;
        this.mirrorAxis = -1;
        this.isSequence = true;
        this.samplePayloads = immutablePayloads(samplePayloads);
        this.frameDeltas = frameDeltas.clone();
        this.sampleCount = sampleCount;
        this.mediaTimescale = mediaTimescale;
        this.mediaDuration = mediaDuration;
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
    public @Nullable @UnmodifiableView ByteBuffer primaryItemPayload() {
        if (primaryItemPayload == null) {
            return null;
        }
        return payloadView(primaryItemPayload);
    }

    /// Returns the alpha auxiliary image AV1 OBU payload.
    ///
    /// @return the alpha auxiliary image AV1 OBU payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer alphaItemPayload() {
        if (alphaItemPayload == null) {
            return null;
        }
        return payloadView(alphaItemPayload);
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
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] gridCellPayloads() {
        if (gridCellPayloads == null) {
            return null;
        }
        return payloadViews(gridCellPayloads);
    }

    /// Returns the alpha grid cell AV1 OBU payloads in row-major order.
    ///
    /// @return the alpha grid cell AV1 OBU payloads, or `null`
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] gridAlphaCellPayloads() {
        if (gridAlphaCellPayloads == null) {
            return null;
        }
        return payloadViews(gridAlphaCellPayloads);
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

    /// Returns the alpha grid row count.
    ///
    /// @return the alpha grid row count
    public int gridAlphaRows() {
        return gridAlphaRows;
    }

    /// Returns the alpha grid column count.
    ///
    /// @return the alpha grid column count
    public int gridAlphaColumns() {
        return gridAlphaColumns;
    }

    /// Returns the alpha grid output width.
    ///
    /// @return the alpha grid output width
    public int gridAlphaOutputWidth() {
        return gridAlphaOutputWidth;
    }

    /// Returns the alpha grid output height.
    ///
    /// @return the alpha grid output height
    public int gridAlphaOutputHeight() {
        return gridAlphaOutputHeight;
    }

    /// Returns the gain-map AV1 OBU payload.
    ///
    /// @return the gain-map AV1 OBU payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer gainMapItemPayload() {
        if (gainMapItemPayload == null) {
            return null;
        }
        return payloadView(gainMapItemPayload);
    }

    /// Returns the gain-map grid cell AV1 OBU payloads in row-major order.
    ///
    /// @return the gain-map grid cell AV1 OBU payloads, or `null`
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] gainMapGridCellPayloads() {
        if (gainMapGridCellPayloads == null) {
            return null;
        }
        return payloadViews(gainMapGridCellPayloads);
    }

    /// Returns the gain-map grid row count.
    ///
    /// @return the gain-map grid row count
    public int gainMapGridRows() {
        return gainMapGridRows;
    }

    /// Returns the gain-map grid column count.
    ///
    /// @return the gain-map grid column count
    public int gainMapGridColumns() {
        return gainMapGridColumns;
    }

    /// Returns the gain-map grid output width.
    ///
    /// @return the gain-map grid output width
    public int gainMapGridOutputWidth() {
        return gainMapGridOutputWidth;
    }

    /// Returns the gain-map grid output height.
    ///
    /// @return the gain-map grid output height
    public int gainMapGridOutputHeight() {
        return gainMapGridOutputHeight;
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

    /// Returns whether this is an AVIS image sequence.
    ///
    /// @return whether this is an AVIS image sequence
    public boolean isSequence() {
        return isSequence;
    }

    /// Returns the AV1 OBU payloads for each sample in order.
    ///
    /// @return the AV1 OBU payloads, or `null`
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] samplePayloads() {
        if (samplePayloads == null) {
            return null;
        }
        return payloadViews(samplePayloads);
    }

    /// Returns the number of samples.
    ///
    /// @return the number of samples
    public int sampleCount() {
        return sampleCount;
    }

    /// Returns the frame duration deltas in media timescale units.
    ///
    /// @return the frame duration deltas
    public int @Unmodifiable [] frameDeltas() {
        return frameDeltas.clone();
    }

    /// Creates immutable storage for one payload.
    ///
    /// @param payload the source payload bytes
    /// @return immutable little-endian payload storage
    private static @Unmodifiable ByteBuffer immutablePayload(byte[] payload) {
        return ByteBuffer
                .wrap(Arrays.copyOf(payload, payload.length))
                .asReadOnlyBuffer()
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Creates immutable storage for payload arrays.
    ///
    /// @param payloads the source payload arrays
    /// @return immutable little-endian payload storage
    private static @Unmodifiable ByteBuffer @Unmodifiable [] immutablePayloads(
            byte @Unmodifiable [] @Unmodifiable [] payloads
    ) {
        ByteBuffer[] result = new ByteBuffer[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            result[i] = immutablePayload(Objects.requireNonNull(payloads[i], "payloads[" + i + "]"));
        }
        return result;
    }

    /// Returns an immutable view over one stored payload.
    ///
    /// @param payload the stored payload
    /// @return a little-endian payload view
    private static @UnmodifiableView ByteBuffer payloadView(@Unmodifiable ByteBuffer payload) {
        return payload.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Returns immutable views over stored payloads.
    ///
    /// @param payloads the stored payload buffers
    /// @return little-endian payload views
    private static @UnmodifiableView ByteBuffer @Unmodifiable [] payloadViews(
            @Unmodifiable ByteBuffer @Unmodifiable [] payloads
    ) {
        ByteBuffer[] result = new ByteBuffer[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            result[i] = payloadView(payloads[i]);
        }
        return result;
    }
}
