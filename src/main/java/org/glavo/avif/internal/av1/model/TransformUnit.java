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

    /// Returns the transform size used by this transform unit.
    ///
    /// @return the transform size used by this transform unit
    public TransformSize size() {
        return size;
    }
}
