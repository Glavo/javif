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
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/// One decoded block-level luma and chroma transform layout produced before coefficient syntax is read.
@NotNullByDefault
public final class TransformLayout {
    /// The local tile-relative luma-grid origin of the owning block.
    private final BlockPosition position;

    /// The coded block size that owns this transform layout.
    private final BlockSize blockSize;

    /// The visible block width in 4x4 units after clipping against tile bounds.
    private final int visibleWidth4;

    /// The visible block height in 4x4 units after clipping against tile bounds.
    private final int visibleHeight4;

    /// The exact visible block width in pixels after clipping against tile bounds.
    private final int visibleWidthPixels;

    /// The exact visible block height in pixels after clipping against tile bounds.
    private final int visibleHeightPixels;

    /// The largest luma transform size allowed by the current block and frame layout.
    private final TransformSize maxLumaTransformSize;

    /// The largest chroma transform size allowed by the current block and frame layout, or `null`.
    private final @Nullable TransformSize chromaTransformSize;

    /// Whether this layout came from a variable luma transform tree.
    private final boolean variableLumaTransformTree;

    /// The luma transform units in bitstream order.
    private final TransformUnit[] lumaUnits;

    /// The shared chroma transform units for the U and V planes in bitstream order.
    private final TransformUnit[] chromaUnits;

    /// Creates one decoded block-level transform layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param visibleWidthPixels the exact visible block width in pixels after clipping against tile bounds
    /// @param visibleHeightPixels the exact visible block height in pixels after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    /// @param chromaUnits the shared chroma transform units for the U and V planes in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            @Nullable TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits,
            TransformUnit[] chromaUnits
    ) {
        this.position = Objects.requireNonNull(position, "position");
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        if (visibleWidth4 <= 0 || visibleWidth4 > blockSize.width4()) {
            throw new IllegalArgumentException("visibleWidth4 out of range: " + visibleWidth4);
        }
        if (visibleHeight4 <= 0 || visibleHeight4 > blockSize.height4()) {
            throw new IllegalArgumentException("visibleHeight4 out of range: " + visibleHeight4);
        }
        this.visibleWidth4 = visibleWidth4;
        this.visibleHeight4 = visibleHeight4;
        if (visibleWidthPixels <= 0 || visibleWidthPixels > blockSize.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > blockSize.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }
        if (visibleWidthPixels > (visibleWidth4 << 2)) {
            throw new IllegalArgumentException("visibleWidthPixels exceeds visibleWidth4 coverage");
        }
        if (visibleHeightPixels > (visibleHeight4 << 2)) {
            throw new IllegalArgumentException("visibleHeightPixels exceeds visibleHeight4 coverage");
        }
        this.visibleWidthPixels = visibleWidthPixels;
        this.visibleHeightPixels = visibleHeightPixels;
        this.maxLumaTransformSize = Objects.requireNonNull(maxLumaTransformSize, "maxLumaTransformSize");
        this.chromaTransformSize = chromaTransformSize;
        this.variableLumaTransformTree = variableLumaTransformTree;
        this.lumaUnits = Arrays.copyOf(Objects.requireNonNull(lumaUnits, "lumaUnits"), lumaUnits.length);
        if (this.lumaUnits.length == 0) {
            throw new IllegalArgumentException("lumaUnits must not be empty");
        }
        this.chromaUnits = Arrays.copyOf(Objects.requireNonNull(chromaUnits, "chromaUnits"), chromaUnits.length);
        if (chromaTransformSize == null && this.chromaUnits.length != 0) {
            throw new IllegalArgumentException("chromaUnits must be empty when chromaTransformSize is null");
        }
        if (chromaTransformSize != null && this.chromaUnits.length == 0) {
            throw new IllegalArgumentException("chromaUnits must not be empty when chromaTransformSize is present");
        }
        for (TransformUnit chromaUnit : this.chromaUnits) {
            if (chromaUnit.size() != chromaTransformSize) {
                throw new IllegalArgumentException("chroma unit size does not match chromaTransformSize");
            }
        }
    }

    /// Creates one decoded block-level transform layout.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param visibleWidthPixels the exact visible block width in pixels after clipping against tile bounds
    /// @param visibleHeightPixels the exact visible block height in pixels after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize maxLumaTransformSize,
            @Nullable TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits
    ) {
        this(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                defaultChromaUnits(position, chromaTransformSize)
        );
    }

    /// Creates one decoded block-level transform layout whose exact visible pixel dimensions match
    /// the current 4x4-grid coverage.
    ///
    /// This overload keeps older synthetic tests stable while the runtime decode path supplies
    /// exact pixel-level clipping through the main constructor.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns this transform layout
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
    /// @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
    /// @param variableLumaTransformTree whether this layout came from a variable luma transform tree
    /// @param lumaUnits the luma transform units in bitstream order
    public TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            TransformSize maxLumaTransformSize,
            @Nullable TransformSize chromaTransformSize,
            boolean variableLumaTransformTree,
            TransformUnit[] lumaUnits
    ) {
        this(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidth4 << 2,
                visibleHeight4 << 2,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                defaultChromaUnits(position, chromaTransformSize)
        );
    }

    /// Returns the local tile-relative luma-grid origin of the owning block.
    ///
    /// @return the local tile-relative luma-grid origin of the owning block
    public BlockPosition position() {
        return position;
    }

    /// Returns the coded block size that owns this transform layout.
    ///
    /// @return the coded block size that owns this transform layout
    public BlockSize blockSize() {
        return blockSize;
    }

    /// Returns the visible block width in 4x4 units after clipping against tile bounds.
    ///
    /// @return the visible block width in 4x4 units after clipping against tile bounds
    public int visibleWidth4() {
        return visibleWidth4;
    }

    /// Returns the visible block height in 4x4 units after clipping against tile bounds.
    ///
    /// @return the visible block height in 4x4 units after clipping against tile bounds
    public int visibleHeight4() {
        return visibleHeight4;
    }

    /// Returns the exact visible block width in pixels after clipping against tile bounds.
    ///
    /// @return the exact visible block width in pixels after clipping against tile bounds
    public int visibleWidthPixels() {
        return visibleWidthPixels;
    }

    /// Returns the exact visible block height in pixels after clipping against tile bounds.
    ///
    /// @return the exact visible block height in pixels after clipping against tile bounds
    public int visibleHeightPixels() {
        return visibleHeightPixels;
    }

    /// Returns the largest luma transform size allowed by the current block and frame layout.
    ///
    /// @return the largest luma transform size allowed by the current block and frame layout
    public TransformSize maxLumaTransformSize() {
        return maxLumaTransformSize;
    }

    /// Returns the largest chroma transform size allowed by the current block and frame layout, or `null`.
    ///
    /// @return the largest chroma transform size allowed by the current block and frame layout, or `null`
    public @Nullable TransformSize chromaTransformSize() {
        return chromaTransformSize;
    }

    /// Returns whether this layout came from a variable luma transform tree.
    ///
    /// @return whether this layout came from a variable luma transform tree
    public boolean variableLumaTransformTree() {
        return variableLumaTransformTree;
    }

    /// Returns the luma transform units in bitstream order.
    ///
    /// @return the luma transform units in bitstream order
    public TransformUnit[] lumaUnits() {
        return Arrays.copyOf(lumaUnits, lumaUnits.length);
    }

    /// Returns the shared chroma transform units for the U and V planes in bitstream order.
    ///
    /// @return the shared chroma transform units for the U and V planes in bitstream order
    public TransformUnit[] chromaUnits() {
        return Arrays.copyOf(chromaUnits, chromaUnits.length);
    }

    /// Returns the uniform luma transform size, or `null` when the layout mixes sizes.
    ///
    /// @return the uniform luma transform size, or `null` when the layout mixes sizes
    public @Nullable TransformSize uniformLumaTransformSize() {
        TransformSize uniformSize = lumaUnits[0].size();
        for (int i = 1; i < lumaUnits.length; i++) {
            if (lumaUnits[i].size() != uniformSize) {
                return null;
            }
        }
        return uniformSize;
    }

    /// Returns one legacy single-unit chroma layout for callers that have not yet supplied explicit
    /// chroma units.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param chromaTransformSize the chroma transform size, or `null`
    /// @return one legacy single-unit chroma layout
    private static TransformUnit[] defaultChromaUnits(
            BlockPosition position,
            @Nullable TransformSize chromaTransformSize
    ) {
        if (chromaTransformSize == null) {
            return new TransformUnit[0];
        }
        return new TransformUnit[]{new TransformUnit(position, chromaTransformSize)};
    }
}
