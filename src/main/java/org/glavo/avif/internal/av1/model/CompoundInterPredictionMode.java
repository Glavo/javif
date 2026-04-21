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

/// The AV1 compound inter prediction modes in bitstream order.
@NotNullByDefault
public enum CompoundInterPredictionMode {
    /// The nearest/nearest compound mode.
    NEARESTMV_NEARESTMV,
    /// The near/near compound mode.
    NEARMV_NEARMV,
    /// The nearest/new compound mode.
    NEARESTMV_NEWMV,
    /// The new/nearest compound mode.
    NEWMV_NEARESTMV,
    /// The near/new compound mode.
    NEARMV_NEWMV,
    /// The new/near compound mode.
    NEWMV_NEARMV,
    /// The global/global compound mode.
    GLOBALMV_GLOBALMV,
    /// The new/new compound mode.
    NEWMV_NEWMV;

    /// Returns the bitstream symbol index of this mode.
    ///
    /// @return the bitstream symbol index of this mode
    public int symbolIndex() {
        return ordinal();
    }

    /// Returns whether this compound mode carries a near-motion-vector component.
    ///
    /// @return whether this compound mode carries a near-motion-vector component
    public boolean usesNearMotionVector() {
        return this == NEARMV_NEARMV || this == NEARMV_NEWMV || this == NEWMV_NEARMV;
    }

    /// Maps one decoded bitstream symbol index back to a compound inter prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the compound inter prediction mode for the supplied symbol index
    public static CompoundInterPredictionMode fromSymbolIndex(int symbolIndex) {
        CompoundInterPredictionMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }
}
