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
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Describes one parsed AV1 Large Scale Tile list OBU.
///
/// @param outputTileColumns the output-frame width in tiles
/// @param outputTileRows the output-frame height in tiles
/// @param entries the coded tiles in output raster order
@NotNullByDefault
public record TileList(
        int outputTileColumns,
        int outputTileRows,
        @Unmodifiable List<Entry> entries
) {
    /// Creates a validated immutable tile list.
    public TileList {
        if (outputTileColumns <= 0 || outputTileColumns > 256) {
            throw new IllegalArgumentException("outputTileColumns out of range: " + outputTileColumns);
        }
        if (outputTileRows <= 0 || outputTileRows > 256) {
            throw new IllegalArgumentException("outputTileRows out of range: " + outputTileRows);
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty() || entries.size() > (long) outputTileColumns * outputTileRows) {
            throw new IllegalArgumentException("Tile-list entry count exceeds its output tile grid");
        }
    }

    /// Describes one coded tile and the external anchor frame used for its inter prediction.
    ///
    /// @param anchorFrameIndex the zero-based external anchor-frame index
    /// @param tileRow the source tile row in the common camera frame
    /// @param tileColumn the source tile column in the common camera frame
    /// @param bitstream the coded tile payload
    @NotNullByDefault
    public record Entry(
            int anchorFrameIndex,
            int tileRow,
            int tileColumn,
            TileBitstream bitstream
    ) {
        /// Creates one validated tile-list entry.
        public Entry {
            if (anchorFrameIndex < 0 || anchorFrameIndex >= 128) {
                throw new IllegalArgumentException("anchorFrameIndex out of range: " + anchorFrameIndex);
            }
            if (tileRow < 0 || tileRow >= 64) {
                throw new IllegalArgumentException("tileRow out of range: " + tileRow);
            }
            if (tileColumn < 0 || tileColumn >= 64) {
                throw new IllegalArgumentException("tileColumn out of range: " + tileColumn);
            }
            Objects.requireNonNull(bitstream, "bitstream");
        }
    }
}
