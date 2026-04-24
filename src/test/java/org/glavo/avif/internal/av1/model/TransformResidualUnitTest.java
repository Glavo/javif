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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for `TransformResidualUnit`.
@NotNullByDefault
final class TransformResidualUnitTest {
    /// Verifies that legacy constructor shapes keep the historical `DCT_DCT` default.
    @Test
    void defaultsToDctDctTransformType() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;
        TransformResidualUnit residualUnit = new TransformResidualUnit(
                new BlockPosition(0, 0),
                TransformSize.TX_4X4,
                0,
                coefficients,
                0x01
        );

        assertEquals(TransformType.DCT_DCT, residualUnit.transformType());
    }

    /// Verifies that explicit transform types are retained by residual units.
    @Test
    void storesExplicitTransformType() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;
        TransformResidualUnit residualUnit = new TransformResidualUnit(
                new BlockPosition(0, 0),
                TransformSize.TX_4X4,
                TransformType.H_ADST,
                0,
                coefficients,
                0x01
        );

        assertEquals(TransformType.H_ADST, residualUnit.transformType());
    }
}
