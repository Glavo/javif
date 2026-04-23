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

import org.glavo.avif.internal.av1.model.TransformSize;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests for minimal inverse-transform reconstruction.
@NotNullByDefault
final class InverseTransformerTest {
    /// The side length in pixels of one `TX_16X16` block.
    private static final int TX_16X16_SIDE = 16;

    /// The sample count of one `TX_16X16` block.
    private static final int TX_16X16_AREA = TX_16X16_SIDE * TX_16X16_SIDE;

    /// The stable unsupported-size failure used before `TX_16X16` support lands.
    private static final String UNSUPPORTED_TX_16X16_MESSAGE = "Unsupported inverse transform size: TX_16X16";

    /// Verifies that `TX_4X4` DC-only `DCT_DCT` reconstruction yields one constant residual block.
    @Test
    void reconstructsFourByFourDcOnlyResidualBlock() {
        int[] coefficients = new int[16];
        coefficients[0] = 128;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_4X4);

        assertArrayEquals(
                new int[]{
                        4, 4, 4, 4,
                        4, 4, 4, 4,
                        4, 4, 4, 4,
                        4, 4, 4, 4
                },
                residual
        );
    }

    /// Verifies that `TX_8X8` reconstruction produces the expected two-dimensional signed pattern.
    @Test
    void reconstructsEightByEightTwoDimensionalResidualPattern() {
        int[] coefficients = new int[64];
        coefficients[9] = 256;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_8X8);

        assertArrayEquals(
                new int[]{
                        8, 7, 4, 2, -2, -4, -7, -8,
                        7, 6, 4, 1, -1, -4, -6, -7,
                        4, 4, 3, 1, -1, -3, -4, -4,
                        2, 1, 1, 0, 0, -1, -1, -2,
                        -2, -1, -1, 0, 0, 1, 1, 2,
                        -4, -4, -3, -1, 1, 3, 4, 4,
                        -7, -6, -4, -1, 1, 4, 6, 7,
                        -8, -7, -4, -2, 2, 4, 7, 8
                },
                residual
        );
    }

    /// Verifies that residual addition clips through the mutable plane buffer.
    @Test
    void addsResidualBlockIntoPredictorPlaneWithClipping() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                plane.setSample(x, y, 2);
            }
        }

        InverseTransformer.addResidualBlock(
                plane,
                0,
                0,
                TransformSize.TX_4X4,
                new int[]{
                        3, 1, -1, -3,
                        3, 1, -1, -3,
                        3, 1, -1, -3,
                        3, 1, -1, -3
                }
        );

        assertEquals(5, plane.sample(0, 0));
        assertEquals(3, plane.sample(1, 0));
        assertEquals(1, plane.sample(2, 0));
        assertEquals(0, plane.sample(3, 0));
        assertEquals(5, plane.sample(0, 3));
        assertEquals(0, plane.sample(3, 3));
    }

    /// Verifies that clipped residual addition only updates the visible residual footprint.
    @Test
    void addsResidualBlockOnlyInsideVisibleFootprint() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                plane.setSample(x, y, 100);
            }
        }

        InverseTransformer.addResidualBlock(
                plane,
                0,
                0,
                TransformSize.TX_4X4,
                3,
                2,
                new int[]{
                        5, 6, 7, 8,
                        4, 3, 2, 1,
                        9, 9, 9, 9,
                        9, 9, 9, 9
                }
        );

        assertEquals(105, plane.sample(0, 0));
        assertEquals(106, plane.sample(1, 0));
        assertEquals(107, plane.sample(2, 0));
        assertEquals(100, plane.sample(3, 0));
        assertEquals(104, plane.sample(0, 1));
        assertEquals(103, plane.sample(1, 1));
        assertEquals(102, plane.sample(2, 1));
        assertEquals(100, plane.sample(3, 1));
        assertEquals(100, plane.sample(0, 2));
        assertEquals(100, plane.sample(3, 3));
    }

    /// Verifies that `TX_16X16` either still reports the current unsupported boundary or, once
    /// wired, reconstructs one zero residual block for zeroed `DCT_DCT` input.
    @Test
    void keepsTx16x16SupportBoundaryStable() {
        int[] coefficients = new int[TX_16X16_AREA];

        try {
            int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_16X16);
            assertArrayEquals(new int[TX_16X16_AREA], residual);
        } catch (IllegalStateException exception) {
            assertEquals(UNSUPPORTED_TX_16X16_MESSAGE, exception.getMessage());
        }
    }

    /// Verifies that `TX_16X16` DC-only `DCT_DCT` reconstruction yields one constant residual
    /// block once support is available.
    @Test
    void reconstructsSixteenBySixteenDcOnlyResidualBlockWhenSupported() {
        assumeTx16x16SupportAvailable();

        int[] coefficients = new int[TX_16X16_AREA];
        coefficients[0] = 512;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_16X16);

        assertArrayEquals(filledSamples(TX_16X16_AREA, 4), residual);
    }

    /// Verifies that the first horizontal `TX_16X16` `DCT_DCT` AC basis stays row-constant and
    /// left-right antisymmetric once support is available.
    @Test
    void reconstructsSixteenBySixteenHorizontalAcResidualPatternWhenSupported() {
        assumeTx16x16SupportAvailable();

        int[] coefficients = new int[TX_16X16_AREA];
        coefficients[1] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_16X16);

        assertRowsMatchFirstRow(residual, TX_16X16_SIDE);
        assertHorizontalAntisymmetry(residual, TX_16X16_SIDE);
        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[TX_16X16_SIDE - 1] < 0, "Mirrored horizontal basis sample should stay negative");
    }

    /// Skips the calling test until `TX_16X16` inverse-transform support is wired in.
    private static void assumeTx16x16SupportAvailable() {
        assumeTrue(
                supportsTx16x16InverseTransform(),
                "TX_16X16 DCT_DCT inverse-transform support is not wired into InverseTransformer yet"
        );
    }

    /// Returns whether `InverseTransformer` already accepts one zeroed `TX_16X16` block.
    ///
    /// @return whether `TX_16X16` reconstruction is available
    private static boolean supportsTx16x16InverseTransform() {
        try {
            InverseTransformer.reconstructResidualBlock(new int[TX_16X16_AREA], TransformSize.TX_16X16);
            return true;
        } catch (IllegalStateException exception) {
            if (UNSUPPORTED_TX_16X16_MESSAGE.equals(exception.getMessage())) {
                return false;
            }
            throw exception;
        }
    }

    /// Creates one filled residual block with one repeated sample value.
    ///
    /// @param sampleCount the number of samples to create
    /// @param value the repeated sample value
    /// @return one filled residual block
    private static int[] filledSamples(int sampleCount, int value) {
        int[] samples = new int[sampleCount];
        Arrays.fill(samples, value);
        return samples;
    }

    /// Verifies that every row in one square residual block equals the first row.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param sideLength the square block side length in samples
    private static void assertRowsMatchFirstRow(int[] residual, int sideLength) {
        for (int row = 1; row < sideLength; row++) {
            int rowOffset = row * sideLength;
            for (int column = 0; column < sideLength; column++) {
                assertEquals(
                        residual[column],
                        residual[rowOffset + column],
                        "Residual row mismatch at row " + row + ", column " + column
                );
            }
        }
    }

    /// Verifies that each row in one square residual block is horizontally antisymmetric.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param sideLength the square block side length in samples
    private static void assertHorizontalAntisymmetry(int[] residual, int sideLength) {
        for (int row = 0; row < sideLength; row++) {
            int rowOffset = row * sideLength;
            for (int column = 0; column < sideLength / 2; column++) {
                int mirroredColumn = sideLength - 1 - column;
                assertEquals(
                        residual[rowOffset + column],
                        -residual[rowOffset + mirroredColumn],
                        "Residual row is not horizontally antisymmetric at row " + row
                );
            }
        }
    }
}
