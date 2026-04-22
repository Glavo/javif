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

import java.util.Arrays;
import java.util.Objects;

/// One transform residual unit after the current coefficient-side syntax pass.
///
/// Coefficients are stored as signed transform-domain levels in natural raster order. The current
/// implementation fully decodes the modeled two-dimensional luma residual path, including
/// multi-coefficient larger-transform units that reuse the current simplified non-chroma token
/// contexts while chroma residual syntax remains out of scope.
@NotNullByDefault
public final class TransformResidualUnit {
    /// The tile-relative luma-grid origin of this transform residual unit.
    private final BlockPosition position;

    /// The transform size used by this residual unit.
    private final TransformSize size;

    /// The scan index of the last non-zero coefficient, or `-1` for all-zero units.
    private final int endOfBlockIndex;

    /// The signed transform-domain coefficients in natural raster order.
    private final int[] coefficients;

    /// The coefficient-context byte written back to neighbor state.
    private final int coefficientContextByte;

    /// Creates one transform residual unit.
    ///
    /// @param position the tile-relative luma-grid origin of this transform residual unit
    /// @param size the transform size used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            int endOfBlockIndex,
            int[] coefficients,
            int coefficientContextByte
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        if (endOfBlockIndex < -1 || endOfBlockIndex >= size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("endOfBlockIndex out of range: " + endOfBlockIndex);
        }
        this.endOfBlockIndex = endOfBlockIndex;
        this.coefficients = Arrays.copyOf(Objects.requireNonNull(coefficients, "coefficients"), coefficients.length);
        if (this.coefficients.length != size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("coefficients length does not match transform area");
        }
        if (endOfBlockIndex < 0) {
            for (int coefficient : this.coefficients) {
                if (coefficient != 0) {
                    throw new IllegalArgumentException("all-zero residual units must not carry coefficients");
                }
            }
        }
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
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
        return endOfBlockIndex < 0;
    }

    /// Returns the scan index of the last non-zero coefficient, or `-1` for all-zero units.
    ///
    /// @return the scan index of the last non-zero coefficient, or `-1` for all-zero units
    public int endOfBlockIndex() {
        return endOfBlockIndex;
    }

    /// Returns the signed transform-domain coefficients in natural raster order.
    ///
    /// @return the signed transform-domain coefficients in natural raster order
    public int[] coefficients() {
        return Arrays.copyOf(coefficients, coefficients.length);
    }

    /// Returns the decoded DC coefficient in natural raster order.
    ///
    /// @return the decoded DC coefficient in natural raster order
    public int dcCoefficient() {
        return coefficients[0];
    }

    /// Returns the coefficient-context byte written back to neighbor state.
    ///
    /// @return the coefficient-context byte written back to neighbor state
    public int coefficientContextByte() {
        return coefficientContextByte;
    }
}
