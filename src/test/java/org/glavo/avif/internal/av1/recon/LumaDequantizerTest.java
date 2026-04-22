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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for minimal luma dequantization.
@NotNullByDefault
final class LumaDequantizerTest {
    /// Verifies that luma dequantization uses separate DC and AC lookup tables.
    @Test
    void dequantizesDcAndAcCoefficientsWithIndependentLookupTables() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = LumaDequantizer.dequantize(
                new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 1, coefficients, 0x11),
                new LumaDequantizer.Context(2, 0, 8)
        );

        int[] expected = new int[16];
        expected[0] = 16;
        expected[1] = -27;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that the derived DC qindex clamps before the lookup-table access.
    @Test
    void clampsDerivedDcQIndexBeforeLookup() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = 3;

        int[] dequantized = LumaDequantizer.dequantize(
                new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 1, coefficients, 0x11),
                new LumaDequantizer.Context(1, -10, 8)
        );

        int[] expected = new int[16];
        expected[0] = 8;
        expected[1] = 24;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that unsupported bit depths still fail fast in the current minimal subset.
    @Test
    void rejectsUnsupportedBitDepths() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> LumaDequantizer.dequantize(
                        new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 0, coefficients, 0x01),
                        new LumaDequantizer.Context(0, 0, 10)
                )
        );

        assertEquals("Unsupported luma dequantization bit depth: 10", exception.getMessage());
    }
}
