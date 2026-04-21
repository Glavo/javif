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

/// The AV1 block sizes currently needed by the early partition tree reader.
@NotNullByDefault
public enum BlockSize {
    /// A 128x128 block.
    SIZE_128X128(32, 32, 3),
    /// A 128x64 block.
    SIZE_128X64(32, 16, 3),
    /// A 64x128 block.
    SIZE_64X128(16, 32, 3),
    /// A 64x64 block.
    SIZE_64X64(16, 16, 3),
    /// A 64x32 block.
    SIZE_64X32(16, 8, 3),
    /// A 64x16 block.
    SIZE_64X16(16, 4, 2),
    /// A 32x64 block.
    SIZE_32X64(8, 16, 3),
    /// A 32x32 block.
    SIZE_32X32(8, 8, 3),
    /// A 32x16 block.
    SIZE_32X16(8, 4, 2),
    /// A 32x8 block.
    SIZE_32X8(8, 2, 1),
    /// A 16x64 block.
    SIZE_16X64(4, 16, 2),
    /// A 16x32 block.
    SIZE_16X32(4, 8, 2),
    /// A 16x16 block.
    SIZE_16X16(4, 4, 2),
    /// A 16x8 block.
    SIZE_16X8(4, 2, 1),
    /// A 16x4 block.
    SIZE_16X4(4, 1, 0),
    /// A 8x32 block.
    SIZE_8X32(2, 8, 1),
    /// A 8x16 block.
    SIZE_8X16(2, 4, 1),
    /// A 8x8 block.
    SIZE_8X8(2, 2, 1),
    /// A 8x4 block.
    SIZE_8X4(2, 1, 0),
    /// A 4x16 block.
    SIZE_4X16(1, 4, 0),
    /// A 4x8 block.
    SIZE_4X8(1, 2, 0),
    /// A 4x4 block.
    SIZE_4X4(1, 1, 0);

    /// The block width in 4x4 units.
    private final int width4;

    /// The block height in 4x4 units.
    private final int height4;

    /// The Y-mode size context used by `TileSyntaxReader`.
    private final int yModeSizeContext;

    /// Creates one AV1 block size entry.
    ///
    /// @param width4 the block width in 4x4 units
    /// @param height4 the block height in 4x4 units
    /// @param yModeSizeContext the Y-mode size context used by `TileSyntaxReader`
    BlockSize(int width4, int height4, int yModeSizeContext) {
        this.width4 = width4;
        this.height4 = height4;
        this.yModeSizeContext = yModeSizeContext;
    }

    /// Returns the block width in 4x4 units.
    ///
    /// @return the block width in 4x4 units
    public int width4() {
        return width4;
    }

    /// Returns the block height in 4x4 units.
    ///
    /// @return the block height in 4x4 units
    public int height4() {
        return height4;
    }

    /// Returns the block width in pixels.
    ///
    /// @return the block width in pixels
    public int widthPixels() {
        return width4 << 2;
    }

    /// Returns the block height in pixels.
    ///
    /// @return the block height in pixels
    public int heightPixels() {
        return height4 << 2;
    }

    /// Returns the Y-mode size context used by `TileSyntaxReader`.
    ///
    /// @return the Y-mode size context used by `TileSyntaxReader`
    public int yModeSizeContext() {
        return yModeSizeContext;
    }

    /// Returns whether the block is square.
    ///
    /// @return whether the block is square
    public boolean isSquare() {
        return width4 == height4;
    }
}
