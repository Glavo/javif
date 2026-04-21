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

/// The thirteen AV1 luma intra prediction modes in bitstream order.
@NotNullByDefault
public enum LumaIntraPredictionMode {
    /// DC prediction.
    DC(0),
    /// Vertical prediction.
    VERTICAL(1),
    /// Horizontal prediction.
    HORIZONTAL(2),
    /// Diagonal-down-left prediction.
    DIAGONAL_DOWN_LEFT(3),
    /// Diagonal-down-right prediction.
    DIAGONAL_DOWN_RIGHT(4),
    /// Vertical-right prediction.
    VERTICAL_RIGHT(4),
    /// Horizontal-down prediction.
    HORIZONTAL_DOWN(4),
    /// Horizontal-up prediction.
    HORIZONTAL_UP(4),
    /// Vertical-left prediction.
    VERTICAL_LEFT(3),
    /// Smooth prediction.
    SMOOTH(0),
    /// Smooth vertical prediction.
    SMOOTH_VERTICAL(1),
    /// Smooth horizontal prediction.
    SMOOTH_HORIZONTAL(2),
    /// Paeth prediction.
    PAETH(0);

    /// The coarsened intra-mode context class used by key-frame Y-mode CDFs.
    private final int contextIndex;

    /// Creates one luma intra prediction mode entry.
    ///
    /// @param contextIndex the coarsened intra-mode context class used by key-frame Y-mode CDFs
    LumaIntraPredictionMode(int contextIndex) {
        this.contextIndex = contextIndex;
    }

    /// Returns the coarsened intra-mode context class used by key-frame Y-mode CDFs.
    ///
    /// @return the coarsened intra-mode context class used by key-frame Y-mode CDFs
    public int contextIndex() {
        return contextIndex;
    }

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a luma intra prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the luma intra prediction mode for the supplied symbol index
    public static LumaIntraPredictionMode fromSymbolIndex(int symbolIndex) {
        LumaIntraPredictionMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }
}
