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

/// The AV1 recursive filter-intra modes in bitstream order.
@NotNullByDefault
public enum FilterIntraMode {
    /// Recursive DC filter intra prediction.
    DC,
    /// Recursive vertical filter intra prediction.
    VERTICAL,
    /// Recursive horizontal filter intra prediction.
    HORIZONTAL,
    /// Recursive diagonal-157 filter intra prediction.
    DIAGONAL_157,
    /// Recursive Paeth-style filter intra prediction.
    PAETH;

    /// Returns the bitstream symbol index of this filter intra mode.
    ///
    /// @return the bitstream symbol index of this filter intra mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a filter intra mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the filter intra mode for the supplied symbol index
    public static FilterIntraMode fromSymbolIndex(int symbolIndex) {
        FilterIntraMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }
}
