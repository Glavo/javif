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
}
