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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Immutable standalone or grid-derived AV1 image source selected from an AVIF item graph.
///
/// Each payload retains the AV1 operating point selected by the matching `a1op` item property and
/// the spatial-layer selection from `lsel`. Standalone sources contain one payload; grid sources
/// contain one payload per cell in row-major order.
@NotNullByDefault
public final class AvifImageSource {
    /// Selects the highest output spatial layer in an AV1 image item.
    public static final int HIGHEST_SPATIAL_LAYER = -1;

    /// Immutable AV1 payloads in source order.
    private final AvifPayload @Unmodifiable [] payloads;
    /// Operating-point indices corresponding to the payloads.
    private final int @Unmodifiable [] operatingPoints;
    /// Selected spatial-layer identifiers corresponding to the payloads.
    private final int @Unmodifiable [] selectedSpatialLayers;
    /// `ispe` widths corresponding to the payloads.
    private final int @Unmodifiable [] itemWidths;
    /// `ispe` heights corresponding to the payloads.
    private final int @Unmodifiable [] itemHeights;
    /// Whether this source is a grid-derived image.
    private final boolean grid;
    /// The source row count.
    private final int rows;
    /// The source column count.
    private final int columns;
    /// The reconstructed output width in luma samples.
    private final int outputWidth;
    /// The reconstructed output height in luma samples.
    private final int outputHeight;

    /// Creates an immutable image source.
    ///
    /// @param payloads the AV1 payloads in source order
    /// @param operatingPoints the operating-point indices corresponding to the payloads
    /// @param selectedSpatialLayers the selected spatial-layer identifiers corresponding to the payloads
    /// @param itemWidths the `ispe` widths corresponding to the payloads
    /// @param itemHeights the `ispe` heights corresponding to the payloads
    /// @param grid whether the source is grid-derived
    /// @param rows the source row count
    /// @param columns the source column count
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    private AvifImageSource(
            AvifPayload @Unmodifiable [] payloads,
            int @Unmodifiable [] operatingPoints,
            int @Unmodifiable [] selectedSpatialLayers,
            int @Unmodifiable [] itemWidths,
            int @Unmodifiable [] itemHeights,
            boolean grid,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        Objects.requireNonNull(payloads, "payloads");
        Objects.requireNonNull(operatingPoints, "operatingPoints");
        Objects.requireNonNull(selectedSpatialLayers, "selectedSpatialLayers");
        Objects.requireNonNull(itemWidths, "itemWidths");
        Objects.requireNonNull(itemHeights, "itemHeights");
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException("Image source row and column counts must be positive");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("Image source output dimensions must be positive");
        }
        if ((long) rows * columns != payloads.length) {
            throw new IllegalArgumentException("Image source payload count does not match rows * columns");
        }
        if (operatingPoints.length != payloads.length) {
            throw new IllegalArgumentException("Operating-point count does not match payload count");
        }
        if (selectedSpatialLayers.length != payloads.length) {
            throw new IllegalArgumentException("Spatial-layer selection count does not match payload count");
        }
        if (itemWidths.length != payloads.length || itemHeights.length != payloads.length) {
            throw new IllegalArgumentException("Item dimension count does not match payload count");
        }
        if (!grid && (rows != 1 || columns != 1 || payloads.length != 1)) {
            throw new IllegalArgumentException("Standalone image sources must contain exactly one payload");
        }
        for (int i = 0; i < operatingPoints.length; i++) {
            Objects.requireNonNull(payloads[i], "payloads[" + i + "]");
            int operatingPoint = operatingPoints[i];
            if (operatingPoint < 0 || operatingPoint > 31) {
                throw new IllegalArgumentException("Operating point out of range at index " + i + ": " + operatingPoint);
            }
            int selectedSpatialLayer = selectedSpatialLayers[i];
            if (selectedSpatialLayer < HIGHEST_SPATIAL_LAYER || selectedSpatialLayer > 3) {
                throw new IllegalArgumentException(
                        "Selected spatial layer out of range at index " + i + ": " + selectedSpatialLayer
                );
            }
            if (itemWidths[i] <= 0 || itemHeights[i] <= 0) {
                throw new IllegalArgumentException(
                        "Item dimensions must be positive at index " + i + ": "
                                + itemWidths[i] + "x" + itemHeights[i]
                );
            }
        }
        this.payloads = payloads.clone();
        this.operatingPoints = operatingPoints.clone();
        this.selectedSpatialLayers = selectedSpatialLayers.clone();
        this.itemWidths = itemWidths.clone();
        this.itemHeights = itemHeights.clone();
        this.grid = grid;
        this.rows = rows;
        this.columns = columns;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    /// Creates a standalone AV1 item source.
    ///
    /// @param payload the AV1 item payload
    /// @param operatingPoint the selected AV1 operating-point index
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @return the immutable standalone source
    public static AvifImageSource item(byte[] payload, int operatingPoint, int outputWidth, int outputHeight) {
        return item(payload, operatingPoint, HIGHEST_SPATIAL_LAYER, outputWidth, outputHeight);
    }

    /// Creates a standalone AV1 item source with an explicit spatial-layer selection.
    ///
    /// @param payload the AV1 item payload
    /// @param operatingPoint the selected AV1 operating-point index
    /// @param selectedSpatialLayer the selected spatial-layer identifier, or [#HIGHEST_SPATIAL_LAYER]
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @return the immutable standalone source
    public static AvifImageSource item(
            byte[] payload,
            int operatingPoint,
            int selectedSpatialLayer,
            int outputWidth,
            int outputHeight
    ) {
        return new AvifImageSource(
                new AvifPayload[]{AvifPayload.copyOf(Objects.requireNonNull(payload, "payload"))},
                new int[]{operatingPoint},
                new int[]{selectedSpatialLayer},
                new int[]{outputWidth},
                new int[]{outputHeight},
                false,
                1,
                1,
                outputWidth,
                outputHeight
        );
    }

    /// Creates a standalone AV1 item source over an existing payload descriptor.
    ///
    /// @param payload the AV1 item payload descriptor
    /// @param operatingPoint the selected AV1 operating-point index
    /// @param selectedSpatialLayer the selected spatial-layer identifier, or [#HIGHEST_SPATIAL_LAYER]
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @return the immutable standalone source
    public static AvifImageSource item(
            AvifPayload payload,
            int operatingPoint,
            int selectedSpatialLayer,
            int outputWidth,
            int outputHeight
    ) {
        return new AvifImageSource(
                new AvifPayload[]{Objects.requireNonNull(payload, "payload")},
                new int[]{operatingPoint},
                new int[]{selectedSpatialLayer},
                new int[]{outputWidth},
                new int[]{outputHeight},
                false,
                1,
                1,
                outputWidth,
                outputHeight
        );
    }

    /// Creates a grid-derived AV1 image source with per-cell spatial-layer selections.
    ///
    /// @param cellPayloads the cell AV1 payloads in row-major order
    /// @param operatingPoints the selected operating point for each cell
    /// @param selectedSpatialLayers the selected spatial layer for each cell, using
    ///        [#HIGHEST_SPATIAL_LAYER] where the highest output layer should be used
    /// @param cellWidths the `ispe` width for each cell
    /// @param cellHeights the `ispe` height for each cell
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @return the immutable grid source
    public static AvifImageSource grid(
            byte @Unmodifiable [] @Unmodifiable [] cellPayloads,
            int @Unmodifiable [] operatingPoints,
            int @Unmodifiable [] selectedSpatialLayers,
            int @Unmodifiable [] cellWidths,
            int @Unmodifiable [] cellHeights,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        Objects.requireNonNull(cellPayloads, "cellPayloads");
        AvifPayload[] payloads = new AvifPayload[cellPayloads.length];
        for (int i = 0; i < cellPayloads.length; i++) {
            payloads[i] = AvifPayload.copyOf(Objects.requireNonNull(cellPayloads[i], "cellPayloads[" + i + "]"));
        }
        return grid(
                payloads,
                operatingPoints,
                selectedSpatialLayers,
                cellWidths,
                cellHeights,
                rows,
                columns,
                outputWidth,
                outputHeight
        );
    }

    /// Creates a grid-derived AV1 image source over existing payload descriptors.
    ///
    /// @param cellPayloads the cell AV1 payload descriptors in row-major order
    /// @param operatingPoints the selected operating point for each cell
    /// @param selectedSpatialLayers the selected spatial layer for each cell, using
    ///        [#HIGHEST_SPATIAL_LAYER] where the highest output layer should be used
    /// @param cellWidths the `ispe` width for each cell
    /// @param cellHeights the `ispe` height for each cell
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the reconstructed output width
    /// @param outputHeight the reconstructed output height
    /// @return the immutable grid source
    public static AvifImageSource grid(
            AvifPayload @Unmodifiable [] cellPayloads,
            int @Unmodifiable [] operatingPoints,
            int @Unmodifiable [] selectedSpatialLayers,
            int @Unmodifiable [] cellWidths,
            int @Unmodifiable [] cellHeights,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        return new AvifImageSource(
                Objects.requireNonNull(cellPayloads, "cellPayloads"),
                Objects.requireNonNull(operatingPoints, "operatingPoints"),
                Objects.requireNonNull(selectedSpatialLayers, "selectedSpatialLayers"),
                Objects.requireNonNull(cellWidths, "cellWidths"),
                Objects.requireNonNull(cellHeights, "cellHeights"),
                true,
                rows,
                columns,
                outputWidth,
                outputHeight
        );
    }

    /// Returns whether this source is grid-derived.
    ///
    /// @return whether this source is grid-derived
    public boolean isGrid() {
        return grid;
    }

    /// Returns the number of AV1 payloads.
    ///
    /// @return one for a standalone item or the grid cell count
    public int payloadCount() {
        return payloads.length;
    }

    /// Returns one AV1 payload descriptor.
    ///
    /// @param index the zero-based payload or cell index
    /// @return the immutable payload descriptor
    public AvifPayload payload(int index) {
        return payloads[index];
    }

    /// Returns all AV1 payload descriptors.
    ///
    /// @return a shallow copy of the immutable payload descriptors
    public AvifPayload @Unmodifiable [] payloads() {
        return payloads.clone();
    }

    /// Returns the operating-point index for one payload.
    ///
    /// @param index the zero-based payload or cell index
    /// @return the selected AV1 operating-point index
    public int operatingPoint(int index) {
        return operatingPoints[index];
    }

    /// Returns all operating-point indices.
    ///
    /// @return a copy of the operating-point indices
    public int @Unmodifiable [] operatingPoints() {
        return operatingPoints.clone();
    }

    /// Returns the selected spatial layer for one payload.
    ///
    /// @param index the zero-based payload or cell index
    /// @return the spatial-layer identifier, or [#HIGHEST_SPATIAL_LAYER]
    public int selectedSpatialLayer(int index) {
        return selectedSpatialLayers[index];
    }

    /// Returns all spatial-layer selections.
    ///
    /// @return a copy of the spatial-layer selections
    public int @Unmodifiable [] selectedSpatialLayers() {
        return selectedSpatialLayers.clone();
    }

    /// Returns the `ispe` width associated with one payload.
    ///
    /// @param index the zero-based payload or cell index
    /// @return the item width in luma samples
    public int itemWidth(int index) {
        return itemWidths[index];
    }

    /// Returns all `ispe` widths.
    ///
    /// @return a copy of the item widths
    public int @Unmodifiable [] itemWidths() {
        return itemWidths.clone();
    }

    /// Returns the `ispe` height associated with one payload.
    ///
    /// @param index the zero-based payload or cell index
    /// @return the item height in luma samples
    public int itemHeight(int index) {
        return itemHeights[index];
    }

    /// Returns all `ispe` heights.
    ///
    /// @return a copy of the item heights
    public int @Unmodifiable [] itemHeights() {
        return itemHeights.clone();
    }

    /// Returns the source row count.
    ///
    /// @return one for a standalone item or the grid row count
    public int rows() {
        return rows;
    }

    /// Returns the source column count.
    ///
    /// @return one for a standalone item or the grid column count
    public int columns() {
        return columns;
    }

    /// Returns the reconstructed output width.
    ///
    /// @return the output width in luma samples
    public int outputWidth() {
        return outputWidth;
    }

    /// Returns the reconstructed output height.
    ///
    /// @return the output height in luma samples
    public int outputHeight() {
        return outputHeight;
    }

}
