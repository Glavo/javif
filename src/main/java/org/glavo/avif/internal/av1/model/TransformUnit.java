// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// One transform unit inside a decoded block-level transform layout.
@NotNullByDefault
public final class TransformUnit {
    /// The tile-relative shared luma-grid origin of this transform unit.
    private final BlockPosition position;

    /// The transform size used by this transform unit.
    private final TransformSize size;

    /// Creates one transform unit.
    ///
    /// @param position the tile-relative shared luma-grid origin of this transform unit
    /// @param size the transform size used by this transform unit
    public TransformUnit(BlockPosition position, TransformSize size) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
    }

    /// Returns the tile-relative shared luma-grid origin of this transform unit.
    ///
    /// @return the tile-relative shared luma-grid origin of this transform unit
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this transform unit with a replaced position.
    ///
    /// @param position the replacement transform-unit position
    /// @return a copy of this transform unit with a replaced position
    public TransformUnit withPosition(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        if (this.position.x4() == nonNullPosition.x4() && this.position.y4() == nonNullPosition.y4()) {
            return this;
        }
        return new TransformUnit(nonNullPosition, size);
    }

    /// Returns the transform size used by this transform unit.
    ///
    /// @return the transform size used by this transform unit
    public TransformSize size() {
        return size;
    }
}
