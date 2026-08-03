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

import java.util.Arrays;
import java.util.Objects;

/// One decoded transform residual unit.
///
/// Coefficients are stored as signed transform-domain levels in natural raster order under the
/// explicit transform type carried by this unit. The metadata also retains the visible write-back
/// rectangle and coefficient-neighbor context produced by AV1 entropy decoding.
@NotNullByDefault
public final class TransformResidualUnit {
    /// Shared internal coefficient storage for transform units signaled as all-zero.
    private static final int @Unmodifiable [] EMPTY_COEFFICIENTS = new int[0];

    /// The block origin of this transform residual unit in luma 4x4 units.
    private final BlockPosition position;

    /// The transform size used by this residual unit.
    private final TransformSize size;

    /// The transform type used by this residual unit.
    private final TransformType transformType;

    /// The scan index of the last non-zero coefficient, or `-1` for all-zero units.
    private final int endOfBlockIndex;

    /// The signed transform-domain coefficients in natural raster order, or an empty internal
    /// representation when the unit is signaled as all-zero.
    private final int @Unmodifiable [] coefficients;

    /// The exact portion of the residual width that lies inside the coded frame or tile boundary.
    private final int visibleWidthPixels;

    /// The exact portion of the residual height that lies inside the coded frame or tile boundary.
    private final int visibleHeightPixels;

    /// The coefficient-context byte written back to neighbor state.
    private final int coefficientContextByte;

    /// Creates one transform residual unit.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param transformType the transform type used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
    /// @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.size = Objects.requireNonNull(size, "size");
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        if (endOfBlockIndex < -1 || endOfBlockIndex >= size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("endOfBlockIndex out of range: " + endOfBlockIndex);
        }
        this.endOfBlockIndex = endOfBlockIndex;
        int[] checkedCoefficients = Objects.requireNonNull(coefficients, "coefficients");
        if (checkedCoefficients.length != size.widthPixels() * size.heightPixels()) {
            throw new IllegalArgumentException("coefficients length does not match transform area");
        }
        if (visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        if (endOfBlockIndex < 0) {
            for (int coefficient : checkedCoefficients) {
                if (coefficient != 0) {
                    throw new IllegalArgumentException("all-zero residual units must not carry coefficients");
                }
            }
        }
        this.coefficients = endOfBlockIndex < 0
                ? EMPTY_COEFFICIENTS
                : Arrays.copyOf(checkedCoefficients, checkedCoefficients.length);
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        this.coefficientContextByte = coefficientContextByte;
    }

    /// Creates one `DCT_DCT` transform residual unit.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
    /// @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            int endOfBlockIndex,
            int[] coefficients,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int coefficientContextByte
    ) {
        this(
                position,
                size,
                TransformType.DCT_DCT,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
        );
    }

    /// Creates a position-adjusted unit that shares the source unit's immutable coefficients.
    ///
    /// @param position the replacement residual-unit position
    /// @param source the residual unit whose non-position state is retained
    private TransformResidualUnit(BlockPosition position, TransformResidualUnit source) {
        this.position = Objects.requireNonNull(position, "position");
        TransformResidualUnit nonNullSource = Objects.requireNonNull(source, "source");
        this.size = nonNullSource.size;
        this.transformType = nonNullSource.transformType;
        this.endOfBlockIndex = nonNullSource.endOfBlockIndex;
        this.coefficients = nonNullSource.coefficients;
        this.visibleWidthPixels = nonNullSource.visibleWidthPixels;
        this.visibleHeightPixels = nonNullSource.visibleHeightPixels;
        this.coefficientContextByte = nonNullSource.coefficientContextByte;
    }

    /// Creates one transform residual unit whose exact visible footprint matches the coded
    /// transform size.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
    /// @param size the transform size used by this residual unit
    /// @param transformType the transform type used by this residual unit
    /// @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
    /// @param coefficients the signed transform-domain coefficients in natural raster order
    /// @param coefficientContextByte the coefficient-context byte written back to neighbor state
    public TransformResidualUnit(
            BlockPosition position,
            TransformSize size,
            TransformType transformType,
            int endOfBlockIndex,
            int[] coefficients,
            int coefficientContextByte
    ) {
        this(
                position,
                size,
                transformType,
                endOfBlockIndex,
                coefficients,
                size.widthPixels(),
                size.heightPixels(),
                coefficientContextByte
        );
    }

    /// Creates one `DCT_DCT` transform residual unit whose exact visible footprint matches the
    /// coded transform size.
    ///
    /// @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
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
        this(
                position,
                size,
                TransformType.DCT_DCT,
                endOfBlockIndex,
                coefficients,
                coefficientContextByte
        );
    }

    /// Returns the block origin of this transform residual unit in luma 4x4 units.
    ///
    /// @return the block origin of this transform residual unit in luma 4x4 units
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this residual unit with a replaced position.
    ///
    /// @param position the replacement residual-unit position
    /// @return a copy of this residual unit with a replaced position
    public TransformResidualUnit withPosition(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        if (this.position.x4() == nonNullPosition.x4() && this.position.y4() == nonNullPosition.y4()) {
            return this;
        }
        return new TransformResidualUnit(nonNullPosition, this);
    }

    /// Returns the transform size used by this residual unit.
    ///
    /// @return the transform size used by this residual unit
    public TransformSize size() {
        return size;
    }

    /// Returns the transform type used by this residual unit.
    ///
    /// @return the transform type used by this residual unit
    public TransformType transformType() {
        return transformType;
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
        return allZero()
                ? new int[size.widthPixels() * size.heightPixels()]
                : Arrays.copyOf(coefficients, coefficients.length);
    }

    /// Returns the exact portion of the residual width inside the coded frame or tile boundary.
    ///
    /// @return the exact visible residual width in pixels
    public int visibleWidthPixels() {
        return visibleWidthPixels;
    }

    /// Returns the exact portion of the residual height inside the coded frame or tile boundary.
    ///
    /// @return the exact visible residual height in pixels
    public int visibleHeightPixels() {
        return visibleHeightPixels;
    }

    /// Returns the decoded DC coefficient in natural raster order.
    ///
    /// @return the decoded DC coefficient in natural raster order
    public int dcCoefficient() {
        return allZero() ? 0 : coefficients[0];
    }

    /// Returns the coefficient-context byte written back to neighbor state.
    ///
    /// @return the coefficient-context byte written back to neighbor state
    public int coefficientContextByte() {
        return coefficientContextByte;
    }
}
