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

/// One block-level residual layout produced after the current coefficient-side syntax pass.
///
/// The current implementation covers all-zero units and DC-only luma coefficient decoding. Higher
/// AC token trees are still introduced incrementally on top of this structure.
@NotNullByDefault
public final class ResidualLayout {
    /// The local tile-relative luma-grid origin of the owning block.
    private final BlockPosition position;

    /// The coded block size that owns this residual layout.
    private final BlockSize blockSize;

    /// The luma transform residual units in bitstream order.
    private final TransformResidualUnit[] lumaUnits;

    /// Creates one block-level residual layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this residual layout
    /// @param lumaUnits the luma transform residual units in bitstream order
    public ResidualLayout(BlockPosition position, BlockSize blockSize, TransformResidualUnit[] lumaUnits) {
        this.position = Objects.requireNonNull(position, "position");
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        this.lumaUnits = Arrays.copyOf(Objects.requireNonNull(lumaUnits, "lumaUnits"), lumaUnits.length);
        if (this.lumaUnits.length == 0) {
            throw new IllegalArgumentException("lumaUnits must not be empty");
        }
    }

    /// Returns the local tile-relative luma-grid origin of the owning block.
    ///
    /// @return the local tile-relative luma-grid origin of the owning block
    public BlockPosition position() {
        return position;
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
}
