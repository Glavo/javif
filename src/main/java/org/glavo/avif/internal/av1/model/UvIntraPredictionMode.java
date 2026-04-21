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

/// The AV1 chroma intra prediction modes in bitstream order.
@NotNullByDefault
public enum UvIntraPredictionMode {
    /// DC prediction.
    DC,
    /// Vertical prediction.
    VERTICAL,
    /// Horizontal prediction.
    HORIZONTAL,
    /// Diagonal-down-left prediction.
    DIAGONAL_DOWN_LEFT,
    /// Diagonal-down-right prediction.
    DIAGONAL_DOWN_RIGHT,
    /// Vertical-right prediction.
    VERTICAL_RIGHT,
    /// Horizontal-down prediction.
    HORIZONTAL_DOWN,
    /// Horizontal-up prediction.
    HORIZONTAL_UP,
    /// Vertical-left prediction.
    VERTICAL_LEFT,
    /// Smooth prediction.
    SMOOTH,
    /// Smooth vertical prediction.
    SMOOTH_VERTICAL,
    /// Smooth horizontal prediction.
    SMOOTH_HORIZONTAL,
    /// Paeth prediction.
    PAETH,
    /// Chroma-from-luma prediction.
    CFL;

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Returns whether this mode uses the directional angle-delta syntax element.
    ///
    /// @return whether this mode uses the directional angle-delta syntax element
    public boolean isDirectional() {
        return ordinal() >= VERTICAL.ordinal() && ordinal() <= VERTICAL_LEFT.ordinal();
    }

    /// Returns the zero-based directional angle-delta CDF index for this mode.
    ///
    /// @return the zero-based directional angle-delta CDF index for this mode
    public int angleDeltaContextIndex() {
        if (!isDirectional()) {
            throw new IllegalStateException("Mode does not use angle_delta: " + this);
        }
        return ordinal() - VERTICAL.ordinal();
    }

    /// Maps a decoded bitstream symbol index back to a chroma intra prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the chroma intra prediction mode for the supplied symbol index
    public static UvIntraPredictionMode fromSymbolIndex(int symbolIndex) {
        UvIntraPredictionMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }
}
