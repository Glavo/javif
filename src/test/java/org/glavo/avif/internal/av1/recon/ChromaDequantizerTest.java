// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for minimal chroma dequantization.
@NotNullByDefault
final class ChromaDequantizerTest {
    /// Verifies that compact DC-only units can be dequantized without a coefficient block.
    @Test
    void dequantizesDcCoefficientWithoutCoefficientBlock() {
        TransformResidualUnit residualUnit = TransformResidualUnit.dcOnly(
                new BlockPosition(0, 0),
                TransformSize.TX_4X4,
                TransformType.DCT_DCT,
                2,
                4,
                4,
                0x11
        );

        assertEquals(20, ChromaDequantizer.dequantizeDcCoefficient(residualUnit, 1, 1, 10, false, 0));
    }

    /// Verifies that `10-bit` chroma dequantization applies plane-local DC and AC deltas before
    /// looking up the QTX tables.
    @Test
    void dequantizesCoefficientsWithTenBitLookupTables() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(
                        new BlockPosition(0, 0),
                        TransformSize.TX_4X4,
                        TransformType.DCT_DCT,
                        1,
                        coefficients,
                        0x11
                ),
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
                new TransformResidualUnit(
                        new BlockPosition(0, 0),
                        TransformSize.TX_4X4,
                        TransformType.DCT_DCT,
                        1,
                        coefficients,
                        0x11
                ),
                new ChromaDequantizer.Context(1, 1, 2, 12)
        );

        int[] expected = new int[16];
        expected[0] = 36;
        expected[1] = -81;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that 64-wide or 64-high transforms apply the larger AV1 dequantization shift.
    @Test
    void largestTransformAppliesDequantizationShiftBeforeSign() {
        int[] coefficients = new int[TransformSize.TX_64X64.widthPixels() * TransformSize.TX_64X64.heightPixels()];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(
                        new BlockPosition(0, 0),
                        TransformSize.TX_64X64,
                        TransformType.DCT_DCT,
                        1,
                        coefficients,
                        0x11
                ),
                new ChromaDequantizer.Context(1, 1, 2, 10)
        );

        int[] expected = new int[coefficients.length];
        expected[0] = 5;
        expected[1] = -9;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that chroma quantization matrices use the chroma matrix class.
    @Test
    void appliesChromaQuantizationMatrixScaling() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(
                        new BlockPosition(0, 0),
                        TransformSize.TX_4X4,
                        TransformType.DCT_DCT,
                        1,
                        coefficients,
                        0x11
                ),
                new ChromaDequantizer.Context(1, 1, 2, 10, true, 0)
        );

        int[] expected = new int[16];
        expected[0] = 22;
        expected[1] = -57;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that matrix index 15 preserves chroma's non-qmatrix dequantization path.
    @Test
    void disablesChromaQuantizationMatrixForIndexFifteen() {
        int[] coefficients = new int[16];
        coefficients[0] = 2;
        coefficients[1] = -3;

        int[] dequantized = ChromaDequantizer.dequantize(
                new TransformResidualUnit(
                        new BlockPosition(0, 0),
                        TransformSize.TX_4X4,
                        TransformType.DCT_DCT,
                        1,
                        coefficients,
                        0x11
                ),
                new ChromaDequantizer.Context(1, 1, 2, 10, true, 15)
        );

        int[] expected = new int[16];
        expected[0] = 20;
        expected[1] = -39;
        assertArrayEquals(expected, dequantized);
    }

    /// Verifies that the allocation-free scalar entry point matches context-based dequantization.
    @Test
    void scalarParametersMatchContextBasedDequantization() {
        int[] coefficients = new int[16];
        coefficients[0] = 7;
        coefficients[1] = -11;
        coefficients[5] = 19;
        TransformResidualUnit residualUnit = new TransformResidualUnit(
                new BlockPosition(0, 0),
                TransformSize.TX_4X4,
                TransformType.DCT_DCT,
                5,
                coefficients,
                0x31
        );
        int[] expected = ChromaDequantizer.dequantize(
                residualUnit,
                new ChromaDequantizer.Context(37, -3, 5, 10, true, 4)
        );
        int[] actual = new int[coefficients.length];

        ChromaDequantizer.dequantize(residualUnit, 37, -3, 5, 10, true, 4, actual);

        assertArrayEquals(expected, actual);
    }

    /// Verifies that unsupported chroma bit depths still fail fast.
    @Test
    void rejectsUnsupportedBitDepths() {
        int[] coefficients = new int[16];
        coefficients[0] = 1;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ChromaDequantizer.dequantize(
                        new TransformResidualUnit(
                                new BlockPosition(0, 0),
                                TransformSize.TX_4X4,
                                TransformType.DCT_DCT,
                                0,
                                coefficients,
                                0x01
                        ),
                        new ChromaDequantizer.Context(0, 0, 0, 11)
                )
        );

        assertEquals("Unsupported chroma dequantization bit depth: 11", exception.getMessage());
    }
}
