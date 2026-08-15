// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

/// Block-level AV1 inter motion compensation mode.
@NotNullByDefault
public enum MotionMode {
    /// Plain translational or global-motion prediction.
    SIMPLE,
    /// Overlapped block motion compensation using causal neighbor predictors.
    OBMC,
    /// Local warped motion compensation.
    LOCAL_WARPED;

    /// Maps one entropy-coded AV1 motion-mode symbol to the internal mode.
    ///
    /// @param symbol the zero-based motion-mode symbol
    /// @return the matching motion mode
    public static MotionMode fromSymbolIndex(int symbol) {
        return switch (symbol) {
            case 0 -> SIMPLE;
            case 1 -> OBMC;
            case 2 -> LOCAL_WARPED;
            default -> throw new IllegalArgumentException("Motion mode symbol out of range: " + symbol);
        };
    }
}
