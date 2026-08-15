// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

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

    /// The mode constants cached in bitstream symbol order.
    private static final CompoundInterPredictionMode @Unmodifiable [] VALUES = values();

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
        if (symbolIndex < 0 || symbolIndex >= VALUES.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return VALUES[symbolIndex];
    }
}
