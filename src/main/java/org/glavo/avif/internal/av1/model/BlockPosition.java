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

/// A block origin expressed in local tile-relative 4x4 units.
@NotNullByDefault
public final class BlockPosition {
    /// The local tile-relative X coordinate in 4x4 units.
    private final int x4;

    /// The local tile-relative Y coordinate in 4x4 units.
    private final int y4;

    /// Creates a block origin expressed in local tile-relative 4x4 units.
    ///
    /// @param x4 the local tile-relative X coordinate in 4x4 units
    /// @param y4 the local tile-relative Y coordinate in 4x4 units
    public BlockPosition(int x4, int y4) {
        if (x4 < 0) {
            throw new IllegalArgumentException("x4 < 0: " + x4);
        }
        if (y4 < 0) {
            throw new IllegalArgumentException("y4 < 0: " + y4);
        }
        this.x4 = x4;
        this.y4 = y4;
    }

    /// Returns the local tile-relative X coordinate in 4x4 units.
    ///
    /// @return the local tile-relative X coordinate in 4x4 units
    public int x4() {
        return x4;
    }

    /// Returns the local tile-relative Y coordinate in 4x4 units.
    ///
    /// @return the local tile-relative Y coordinate in 4x4 units
    public int y4() {
        return y4;
    }

    /// Returns the local tile-relative X coordinate in 8x8 units.
    ///
    /// @return the local tile-relative X coordinate in 8x8 units
    public int x8() {
        return x4 >> 1;
    }

    /// Returns the local tile-relative Y coordinate in 8x8 units.
    ///
    /// @return the local tile-relative Y coordinate in 8x8 units
    public int y8() {
        return y4 >> 1;
    }

    /// Returns a new block position offset by the supplied 4x4-unit delta.
    ///
    /// @param deltaX4 the X-axis offset in 4x4 units
    /// @param deltaY4 the Y-axis offset in 4x4 units
    /// @return a new block position offset by the supplied 4x4-unit delta
    public BlockPosition offset(int deltaX4, int deltaY4) {
        return new BlockPosition(x4 + deltaX4, y4 + deltaY4);
    }
}
