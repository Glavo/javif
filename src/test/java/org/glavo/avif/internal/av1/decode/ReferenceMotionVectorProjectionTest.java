// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.av1.Av1FrameType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for [ReferenceMotionVectorProjection].
@NotNullByDefault
final class ReferenceMotionVectorProjectionTest {
    /// Creates a reference motion-vector projection test instance.
    ReferenceMotionVectorProjectionTest() {
    }

    /// Verifies sign bias uses the directed modular difference at the half-range boundary.
    @Test
    void derivesSignBiasFromDirectedOrderHintDifference() {
        assertFalse(ReferenceMotionVectorProjection.hasFutureSignBias(1, 1, 0));
        assertFalse(ReferenceMotionVectorProjection.hasFutureSignBias(1, 0, 1));
        assertTrue(ReferenceMotionVectorProjection.hasFutureSignBias(2, 1, 0));
        assertFalse(ReferenceMotionVectorProjection.hasFutureSignBias(2, 0, 1));
    }

    /// Verifies saved temporal components use reference-to-source direction at the half-range boundary.
    @Test
    void preservesHalfRangeReferenceSideStorageSemantics() {
        assertTrue(ReferenceMotionVectorProjection.isSavedTemporalReference(3, 4, 0));
        assertTrue(ReferenceMotionVectorProjection.isSavedTemporalReference(3, 4, 2));
        assertFalse(ReferenceMotionVectorProjection.isSavedTemporalReference(3, 4, 6));
        assertFalse(ReferenceMotionVectorProjection.isSavedTemporalReference(3, 4, 4));
    }

    /// Verifies temporal projection rejects intra sources and incompatible 8x8-aligned motion-field grids.
    @Test
    void requiresInterSourceWithMatchingMotionFieldGrid() {
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.KEY,
                460,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.INTRA,
                460,
                2892,
                460,
                2892
        ));
        assertTrue(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.INTER,
                23,
                595,
                19,
                595
        ));
        assertTrue(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.SWITCH,
                460,
                2892,
                460,
                2892
        ));
        assertTrue(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.INTER,
                461,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.INTER,
                465,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                Av1FrameType.INTER,
                460,
                2897,
                460,
                2892
        ));
    }
}
