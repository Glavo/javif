// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

/// The AV1 compound prediction blend types stored by the block syntax.
@NotNullByDefault
public enum CompoundPredictionType {
    /// Joint-distance weighted compound averaging.
    WEIGHTED_AVERAGE(1),
    /// Simple compound averaging.
    AVERAGE(2),
    /// Difference-derived segment mask compound blending.
    SEGMENT(3),
    /// Wedge-mask compound blending.
    WEDGE(4);

    /// The AV1 neighbor-context value corresponding to this compound prediction type.
    private final int contextValue;

    /// Creates one compound prediction type.
    ///
    /// @param contextValue the AV1 neighbor-context value corresponding to this type
    CompoundPredictionType(int contextValue) {
        this.contextValue = contextValue;
    }

    /// Returns the AV1 neighbor-context value corresponding to this compound prediction type.
    ///
    /// @return the AV1 neighbor-context value corresponding to this compound prediction type
    public int contextValue() {
        return contextValue;
    }
}
