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
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.decode.FrameType;
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

    /// Verifies temporal projection rejects intra sources and incompatible 4x4 frame grids.
    @Test
    void requiresInterSourceWithMatchingMotionFieldGrid() {
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.KEY,
                460,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.INTRA,
                460,
                2892,
                460,
                2892
        ));
        assertTrue(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.INTER,
                459,
                2891,
                460,
                2892
        ));
        assertTrue(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.SWITCH,
                460,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.INTER,
                461,
                2892,
                460,
                2892
        ));
        assertFalse(ReferenceMotionVectorProjection.canProjectSourceMotionField(
                FrameType.INTER,
                460,
                2893,
                460,
                2892
        ));
    }
}
