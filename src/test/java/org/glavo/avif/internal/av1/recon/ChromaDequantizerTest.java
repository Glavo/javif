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

/// Tests for minimal chroma dequantization.
@NotNullByDefault
final class ChromaDequantizerTest {
    /// Verifies that `10-bit` chroma dequantization applies plane-local DC and AC deltas before
    /// looking up the QTX tables.
    @Test
    void dequantizesCoefficientsWithTenBitLookupTables() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 1, coefficients, 0x11),
                new ChromaDequantizer.Context(1, 1, 2, 10)
        );

        int[] expected = new int[16];
        expected[0] = 20;
        expected[1] = -39;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that `12-bit` chroma dequantization applies plane-local DC and AC deltas before
    /// looking up the QTX tables.
    @Test
    void dequantizesCoefficientsWithTwelveBitLookupTables() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 1, coefficients, 0x11),
                new ChromaDequantizer.Context(1, 1, 2, 12)
        );

        int[] expected = new int[16];
        expected[0] = 36;
        expected[1] = -81;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that unsupported chroma bit depths still fail fast.
    @Test
    void rejectsUnsupportedBitDepths() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ChromaDequantizer.dequantize(
                        new TransformResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 0, coefficients, 0x01),
                        new ChromaDequantizer.Context(0, 0, 0, 11)
                )
        );

        assertEquals("Unsupported chroma dequantization bit depth: 11", exception.getMessage());
    }
}
