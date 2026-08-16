// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for `TransformResidualUnit`.
@NotNullByDefault
final class TransformResidualUnitTest {
    /// Verifies that the all-zero factory exposes the full logical coefficient area without storing it.
    @Test
    void createsAllZeroUnitWithoutCallerCoefficientArray() {
        TransformResidualUnit residualUnit = TransformResidualUnit.allZero(
                new BlockPosition(1, 2),
                TransformSize.TX_8X8,
                7,
                6,
                0
        );

        assertEquals(-1, residualUnit.endOfBlockIndex());
        assertEquals(64, residualUnit.coefficients().length);
        assertArrayEquals(new int[64], residualUnit.coefficients());
        assertEquals(7, residualUnit.visibleWidthPixels());
        assertEquals(6, residualUnit.visibleHeightPixels());
    }

    /// Verifies that compact DC-only units expose the complete logical coefficient block.
    @Test
    void createsDcOnlyUnitWithoutCallerCoefficientArray() {
        TransformResidualUnit residualUnit = TransformResidualUnit.dcOnly(
                new BlockPosition(1, 2),
                TransformSize.TX_8X8,
                TransformType.ADST_DCT,
                -17,
                7,
                6,
                0x21
        );

        int[] expectedCoefficients = new int[64];
        expectedCoefficients[0] = -17;
        assertEquals(0, residualUnit.endOfBlockIndex());
        assertEquals(-17, residualUnit.dcCoefficient());
        assertEquals(-17, residualUnit.coefficient(0));
        assertEquals(0, residualUnit.coefficient(63));
        assertArrayEquals(expectedCoefficients, residualUnit.coefficients());
        assertEquals(TransformType.ADST_DCT, residualUnit.transformType());
        assertEquals(0x21, residualUnit.coefficientContextByte());
    }

    /// Verifies that one residual unit retains its explicitly supplied transform type.
    @Test
    void retainsDctDctTransformType() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;
        TransformResidualUnit residualUnit = new TransformResidualUnit(
                new BlockPosition(0, 0),
                TransformSize.TX_4X4,
                TransformType.DCT_DCT,
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
