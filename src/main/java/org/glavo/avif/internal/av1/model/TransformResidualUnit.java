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

/// One transform residual unit after the first coefficient-side syntax pass.
///
/// The current implementation stops after `txb_skip` / all-zero signaling, so the stored
/// coefficient-context byte is still provisional for non-zero blocks and does not yet carry full
/// token statistics.
@NotNullByDefault
public final class TransformResidualUnit {
    /// The tile-relative luma-grid origin of this transform residual unit.
    private final BlockPosition position;

    /// The transform size used by this residual unit.
    private final TransformSize size;

    /// Whether the transform block is signaled as all-zero through `txb_skip`.
    private final boolean allZero;

    /// The provisional coefficient-context byte written back to neighbor state.
    private final int coefficientContextByte;

    /// Creates one transform residual unit.
    ///
    /// @param position the tile-relative luma-grid origin of this transform residual unit
    /// @param size the transform size used by this residual unit
    /// @param allZero whether the transform block is signaled as all-zero through `txb_skip`
    /// @param coefficientContextByte the provisional coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            boolean allZero,
            int coefficientContextByte
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        this.allZero = allZero;
        this.coefficientContextByte = coefficientContextByte;
    }

    /// Returns the tile-relative luma-grid origin of this transform residual unit.
    ///
    /// @return the tile-relative luma-grid origin of this transform residual unit
    public BlockPosition position() {
        return position;
    }

    /// Returns the transform size used by this residual unit.
    ///
    /// @return the transform size used by this residual unit
    public TransformSize size() {
        return size;
    }

    /// Returns whether the transform block is signaled as all-zero through `txb_skip`.
    ///
    /// @return whether the transform block is signaled as all-zero through `txb_skip`
    public boolean allZero() {
        return allZero;
    }

    /// Returns the provisional coefficient-context byte written back to neighbor state.
    ///
    /// @return the provisional coefficient-context byte written back to neighbor state
    public int coefficientContextByte() {
        return coefficientContextByte;
    }
}
