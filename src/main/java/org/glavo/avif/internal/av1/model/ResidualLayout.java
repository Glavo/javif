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

/// One block-level residual layout produced after the current coefficient-side syntax pass.
///
/// The current implementation covers all-zero units plus the current two-dimensional luma
/// coefficient syntax supported by `TileResidualSyntaxReader`. Chroma residual syntax is still
/// introduced incrementally on top of this structure, but reconstruction-side callers may already
/// populate chroma transform units through the stable block-level contract exposed here.
@NotNullByDefault
public final class ResidualLayout {
    /// The local tile-relative luma-grid origin of the owning block.
    private final BlockPosition position;

    /// The coded block size that owns this residual layout.
    private final BlockSize blockSize;

    /// The luma transform residual units in bitstream order.
    private final TransformResidualUnit @Unmodifiable [] lumaUnits;

    /// The chroma U transform residual units in bitstream order.
    private final TransformResidualUnit @Unmodifiable [] chromaUUnits;

    /// The chroma V transform residual units in bitstream order.
    private final TransformResidualUnit @Unmodifiable [] chromaVUnits;

    /// Creates one block-level residual layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the luma transform residual units in bitstream order
    public ResidualLayout(BlockPosition position, BlockSize blockSize, TransformResidualUnit[] lumaUnits) {
        this(position, blockSize, lumaUnits, new TransformResidualUnit[0], new TransformResidualUnit[0]);
    }

    /// Creates one block-level residual layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the luma transform residual units in bitstream order
    /// @param chromaUUnits the chroma U transform residual units in bitstream order
    /// @param chromaVUnits the chroma V transform residual units in bitstream order
    public ResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        this.lumaUnits = Arrays.copyOf(Objects.requireNonNull(lumaUnits, "lumaUnits"), lumaUnits.length);
        if (this.lumaUnits.length == 0) {
            throw new IllegalArgumentException("lumaUnits must not be empty");
        }
        this.chromaUUnits = Arrays.copyOf(Objects.requireNonNull(chromaUUnits, "chromaUUnits"), chromaUUnits.length);
        this.chromaVUnits = Arrays.copyOf(Objects.requireNonNull(chromaVUnits, "chromaVUnits"), chromaVUnits.length);
        if (this.chromaUUnits.length != this.chromaVUnits.length) {
            throw new IllegalArgumentException("chromaUUnits and chromaVUnits must have the same length");
        }
    }

    /// Returns the local tile-relative luma-grid origin of the owning block.
    ///
    /// @return the local tile-relative luma-grid origin of the owning block
    public BlockPosition position() {
        return position;
    }

    /// Returns a copy of this residual layout offset by the supplied 4x4-unit delta.
    ///
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return a copy of this residual layout offset by the supplied 4x4-unit delta
    public ResidualLayout withOffset(int deltaX4, int deltaY4) {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this;
        }
        return new ResidualLayout(
                position.offset(deltaX4, deltaY4),
                blockSize,
                offsetUnits(lumaUnits, deltaX4, deltaY4),
                offsetUnits(chromaUUnits, deltaX4, deltaY4),
                offsetUnits(chromaVUnits, deltaX4, deltaY4)
        );
    }

    /// Returns the coded block size that owns this residual layout.
    ///
    /// @return the coded block size that owns this residual layout
    public BlockSize blockSize() {
        return blockSize;
    }

    /// Returns the luma transform residual units in bitstream order.
    ///
    /// @return the luma transform residual units in bitstream order
    public TransformResidualUnit[] lumaUnits() {
        return Arrays.copyOf(lumaUnits, lumaUnits.length);
    }

    /// Returns the chroma U transform residual units in bitstream order.
    ///
    /// @return the chroma U transform residual units in bitstream order
    public TransformResidualUnit[] chromaUUnits() {
        return Arrays.copyOf(chromaUUnits, chromaUUnits.length);
    }

    /// Returns the chroma V transform residual units in bitstream order.
    ///
    /// @return the chroma V transform residual units in bitstream order
    public TransformResidualUnit[] chromaVUnits() {
        return Arrays.copyOf(chromaVUnits, chromaVUnits.length);
    }

    /// Returns whether this residual layout carries any modeled chroma residual units.
    ///
    /// @return whether this residual layout carries any modeled chroma residual units
    public boolean hasChromaUnits() {
        return chromaUUnits.length != 0;
    }

    /// Returns residual units offset by the supplied 4x4-unit delta.
    ///
    /// @param units the source residual units
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return residual units offset by the supplied 4x4-unit delta
    private static TransformResidualUnit[] offsetUnits(
            TransformResidualUnit[] units,
            int deltaX4,
            int deltaY4
    ) {
        TransformResidualUnit[] offsetUnits = new TransformResidualUnit[units.length];
        for (int i = 0; i < units.length; i++) {
            TransformResidualUnit unit = units[i];
            offsetUnits[i] = unit.withPosition(unit.position().offset(deltaX4, deltaY4));
        }
        return offsetUnits;
    }
}
