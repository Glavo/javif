// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

/// Parsed AV1 tile-group header state for a single OBU.
@NotNullByDefault
public final class TileGroupHeader {
    /// Whether explicit tile start and end positions were signaled.
    private final boolean explicitTilePositions;
    /// The first tile index covered by this tile group.
    private final int startTileIndex;
    /// The last tile index covered by this tile group.
    private final int endTileIndex;
    /// The total tile count declared by the frame header.
    private final int totalTileCount;

    /// Creates parsed tile-group header state.
    ///
    /// @param explicitTilePositions whether explicit tile positions were signaled
    /// @param startTileIndex the first tile index covered by this tile group
    /// @param endTileIndex the last tile index covered by this tile group
    /// @param totalTileCount the total tile count declared by the frame header
    public TileGroupHeader(
            boolean explicitTilePositions,
            int startTileIndex,
            int endTileIndex,
            int totalTileCount
    ) {
        if (totalTileCount <= 0) {
            throw new IllegalArgumentException("totalTileCount <= 0: " + totalTileCount);
        }
        if (startTileIndex < 0 || startTileIndex >= totalTileCount) {
            throw new IllegalArgumentException("startTileIndex out of range: " + startTileIndex);
        }
        if (endTileIndex < startTileIndex || endTileIndex >= totalTileCount) {
            throw new IllegalArgumentException("endTileIndex out of range: " + endTileIndex);
        }
        this.explicitTilePositions = explicitTilePositions;
        this.startTileIndex = startTileIndex;
        this.endTileIndex = endTileIndex;
        this.totalTileCount = totalTileCount;
    }

    /// Returns whether explicit tile start and end positions were signaled.
    ///
    /// @return whether explicit tile positions were signaled
    public boolean explicitTilePositions() {
        return explicitTilePositions;
    }

    /// Returns the first tile index covered by this tile group.
    ///
    /// @return the first tile index covered by this tile group
    public int startTileIndex() {
        return startTileIndex;
    }

    /// Returns the last tile index covered by this tile group.
    ///
    /// @return the last tile index covered by this tile group
    public int endTileIndex() {
        return endTileIndex;
    }

    /// Returns the total tile count declared by the frame header.
    ///
    /// @return the total tile count declared by the frame header
    public int totalTileCount() {
        return totalTileCount;
    }

    /// Returns the number of tiles covered by this tile group.
    ///
    /// @return the number of tiles covered by this tile group
    public int tileCount() {
        return endTileIndex - startTileIndex + 1;
    }
}
