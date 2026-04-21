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

/// The AV1 single-reference inter prediction modes in bitstream order.
@NotNullByDefault
public enum SingleInterPredictionMode {
    /// The nearest-motion-vector mode.
    NEARESTMV,
    /// The near-motion-vector mode.
    NEARMV,
    /// The global-motion mode.
    GLOBALMV,
    /// The new-motion-vector mode.
    NEWMV;

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Maps one decoded bitstream symbol index back to a single-reference inter prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the single-reference inter prediction mode for the supplied symbol index
    public static SingleInterPredictionMode fromSymbolIndex(int symbolIndex) {
        SingleInterPredictionMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }
}
