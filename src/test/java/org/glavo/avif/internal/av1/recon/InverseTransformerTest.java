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
import org.glavo.avif.internal.av1.model.TransformType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for minimal inverse-transform reconstruction.
@NotNullByDefault
final class InverseTransformerTest {
    /// The side length in pixels of one `TX_16X16` block.
    private static final int TX_16X16_SIDE = 16;

    /// The sample count of one `TX_16X16` block.
    private static final int TX_16X16_AREA = TX_16X16_SIDE * TX_16X16_SIDE;

    /// The side length in pixels of one `TX_32X32` block.
    private static final int TX_32X32_SIDE = 32;

    /// The sample count of one `TX_32X32` block.
    private static final int TX_32X32_AREA = TX_32X32_SIDE * TX_32X32_SIDE;

    /// The side length in pixels of one `TX_64X64` block.
    private static final int TX_64X64_SIDE = 64;

    /// The sample count of one `TX_64X64` block.
    private static final int TX_64X64_AREA = TX_64X64_SIDE * TX_64X64_SIDE;

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

        assertArrayAlmostEquals(
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

    /// Verifies that rectangular `RTX_4X8` DC-only `DCT_DCT` reconstruction yields one constant
    /// residual block.
    @Test
    void reconstructsFourByEightDcOnlyResidualBlock() {
        int[] coefficients = new int[32];
        coefficients[0] = 256;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_4X8);

        assertBlockFilledWithPositiveValue(residual, 4, 8);
    }

    /// Verifies that one horizontal rectangular basis for `RTX_8X4` stays row-constant and
    /// left-right antisymmetric.
    @Test
    void reconstructsEightByFourHorizontalAcResidualPattern() {
        int[] coefficients = new int[32];
        coefficients[1] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_8X4);

        assertRowsMatchFirstRow(residual, 8, 4);
        assertHorizontalAntisymmetry(residual, 8, 4);
        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[7] < 0, "Mirrored horizontal basis sample should stay negative");
    }

    /// Verifies that one vertical rectangular basis for `RTX_4X8` stays column-constant and
    /// top-bottom antisymmetric.
    @Test
    void reconstructsFourByEightVerticalAcResidualPattern() {
        int[] coefficients = new int[32];
        coefficients[4] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_4X8);

        assertColumnsMatchFirstColumn(residual, 4, 8);
        assertVerticalAntisymmetry(residual, 4, 8);
        assertTrue(residual[0] > 0, "First vertical basis sample should stay positive");
        assertTrue(residual[28] < 0, "Mirrored vertical basis sample should stay negative");
    }

    /// Verifies that rectangular `RTX_16X8` DC-only `DCT_DCT` reconstruction yields one constant
    /// residual block.
    @Test
    void reconstructsSixteenByEightDcOnlyResidualBlock() {
        int[] coefficients = new int[128];
        coefficients[0] = 256;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_16X8);

        assertBlockFilledWithPositiveValue(residual, 16, 8);
    }

    /// Verifies that `IDTX` preserves coefficient positions instead of spreading energy through
    /// a cosine basis.
    @Test
    void reconstructsFourByFourIdentityTransformWithoutSpatialMixing() {
        int[] coefficients = new int[16];
        coefficients[5] = 64;

        int[] residual = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.IDTX
        );

        assertArrayEquals(
                new int[]{
                        0, 0, 0, 0,
                        0, 8, 0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0
                },
                residual
        );
    }

    /// Verifies that AV1 lossless `WHT_WHT` reconstruction follows the dedicated Walsh-Hadamard
    /// path instead of the regular `DCT_DCT` transform.
    @Test
    void reconstructsFourByFourWalshHadamardLosslessBlock() {
        int[] coefficients = new int[16];
        coefficients[0] = 4;

        int[] residual = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.WHT_WHT
        );

        assertArrayEquals(
                new int[]{
                        1, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0
                },
                residual
        );
    }

    /// Verifies that horizontal one-dimensional DCT transforms only the coefficient row selected
    /// by the vertical identity axis.
    @Test
    void reconstructsHorizontalDctTransformWithVerticalIdentity() {
        int[] coefficients = new int[16];
        coefficients[1] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.H_DCT
        );

        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[3] < 0, "Mirrored horizontal basis sample should stay negative");
        assertAlmostOpposite(residual[0], residual[3]);
        assertAlmostOpposite(residual[1], residual[2]);
        for (int index = 4; index < residual.length; index++) {
            assertEquals(0, residual[index]);
        }
    }

    /// Verifies that vertical one-dimensional ADST transforms only the coefficient column selected
    /// by the horizontal identity axis.
    @Test
    void reconstructsVerticalAdstTransformWithHorizontalIdentity() {
        int[] coefficients = new int[16];
        coefficients[4] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.V_ADST
        );

        assertTrue(residual[0] > 0, "First vertical ADST basis sample should stay positive");
        assertTrue(residual[12] != 0, "Last vertical ADST basis sample should stay non-zero");
        for (int row = 0; row < 4; row++) {
            assertEquals(0, residual[(row << 2) + 1]);
            assertEquals(0, residual[(row << 2) + 2]);
            assertEquals(0, residual[(row << 2) + 3]);
        }
    }

    /// Verifies that `FLIPADST` is reconstructed as the spatial mirror of `ADST` on the same axis.
    @Test
    void reconstructsFlipAdstAsMirroredAdst() {
        int[] coefficients = new int[16];
        coefficients[4] = 4096;

        int[] adst = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.V_ADST
        );
        int[] flipAdst = InverseTransformer.reconstructResidualBlock(
                coefficients,
                TransformSize.TX_4X4,
                TransformType.V_FLIPADST
        );

        for (int row = 0; row < 4; row++) {
            int mirroredRow = 3 - row;
            for (int column = 0; column < 4; column++) {
                assertEquals(adst[mirroredRow * 4 + column], flipAdst[row * 4 + column]);
            }
        }
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

    /// Verifies that every declared transform size accepts zeroed `DCT_DCT` input.
    @Test
    void reconstructsZeroResidualBlockForEveryTransformSize() {
        for (TransformSize transformSize : TransformSize.values()) {
            int area = transformSize.widthPixels() * transformSize.heightPixels();
            int[] residual = InverseTransformer.reconstructResidualBlock(new int[area], transformSize);
            assertArrayEquals(new int[area], residual, transformSize.name());
        }
    }

    /// Verifies that `TX_16X16` DC-only `DCT_DCT` reconstruction yields one constant residual block.
    @Test
    void reconstructsSixteenBySixteenDcOnlyResidualBlock() {
        int[] coefficients = new int[TX_16X16_AREA];
        coefficients[0] = 512;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_16X16);

        assertArrayEquals(filledSamples(TX_16X16_AREA, 4), residual);
    }

    /// Verifies that the first horizontal `TX_16X16` `DCT_DCT` AC basis stays row-constant and
    /// left-right antisymmetric.
    @Test
    void reconstructsSixteenBySixteenHorizontalAcResidualPattern() {
        int[] coefficients = new int[TX_16X16_AREA];
        coefficients[1] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_16X16);

        assertRowsMatchFirstRow(residual, TX_16X16_SIDE);
        assertHorizontalAntisymmetry(residual, TX_16X16_SIDE);
        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[TX_16X16_SIDE - 1] < 0, "Mirrored horizontal basis sample should stay negative");
    }

    /// Verifies that `TX_32X32` DC-only `DCT_DCT` reconstruction yields one constant residual
    /// block.
    @Test
    void reconstructsThirtyTwoByThirtyTwoDcOnlyResidualBlock() {
        int[] coefficients = new int[TX_32X32_AREA];
        coefficients[0] = 512;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_32X32);

        assertArrayEquals(filledSamples(TX_32X32_AREA, 4), residual);
    }

    /// Verifies that the first horizontal `TX_32X32` `DCT_DCT` AC basis stays row-constant and
    /// left-right antisymmetric.
    @Test
    void reconstructsThirtyTwoByThirtyTwoHorizontalAcResidualPattern() {
        int[] coefficients = new int[TX_32X32_AREA];
        coefficients[1] = 16384;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_32X32);

        assertRowsMatchFirstRow(residual, TX_32X32_SIDE);
        assertHorizontalAntisymmetry(residual, TX_32X32_SIDE);
        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[TX_32X32_SIDE - 1] < 0, "Mirrored horizontal basis sample should stay negative");
    }

    /// Verifies that `TX_64X64` DC-only `DCT_DCT` reconstruction yields one constant residual
    /// block.
    @Test
    void reconstructsSixtyFourBySixtyFourDcOnlyResidualBlock() {
        int[] coefficients = new int[TX_64X64_AREA];
        coefficients[0] = 512;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_64X64);

        assertArrayEquals(filledSamples(TX_64X64_AREA, 4), residual);
    }

    /// Verifies that larger rectangular `RTX_32X64` DC-only `DCT_DCT` reconstruction yields one
    /// constant residual block.
    @Test
    void reconstructsThirtyTwoBySixtyFourDcOnlyResidualBlock() {
        int[] coefficients = new int[32 * 64];
        coefficients[0] = 4096;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_32X64);

        assertBlockFilledWithPositiveValue(residual, 32, 64);
    }

    /// Verifies that one larger rectangular horizontal basis for `RTX_64X16` stays row-constant
    /// and left-right antisymmetric.
    @Test
    void reconstructsSixtyFourBySixteenHorizontalAcResidualPattern() {
        int[] coefficients = new int[64 * 16];
        coefficients[1] = 16384;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.RTX_64X16);

        assertRowsMatchFirstRow(residual, 64, 16);
        assertHorizontalAntisymmetry(residual, 64, 16);
        assertTrue(residual[0] > 0, "First horizontal basis sample should stay positive");
        assertTrue(residual[63] < 0, "Mirrored horizontal basis sample should stay negative");
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

    /// Verifies that every row in one rectangular residual block equals the first row.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param width the block width in samples
    /// @param height the block height in samples
    private static void assertRowsMatchFirstRow(int[] residual, int width, int height) {
        for (int row = 1; row < height; row++) {
            int rowOffset = row * width;
            for (int column = 0; column < width; column++) {
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
                assertAlmostOpposite(residual[rowOffset + column], residual[rowOffset + mirroredColumn]);
            }
        }
    }

    /// Verifies that two samples are opposite after integer-transform rounding.
    ///
    /// @param left the first sample
    /// @param right the mirrored sample
    private static void assertAlmostOpposite(int left, int right) {
        assertTrue(Math.abs(left + right) <= 1, "Samples are not opposite within one rounding unit");
    }

    /// Verifies that each row in one rectangular residual block is horizontally antisymmetric.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param width the block width in samples
    /// @param height the block height in samples
    private static void assertHorizontalAntisymmetry(int[] residual, int width, int height) {
        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            for (int column = 0; column < width / 2; column++) {
                int mirroredColumn = width - 1 - column;
                assertAlmostOpposite(residual[rowOffset + column], residual[rowOffset + mirroredColumn]);
            }
        }
    }

    /// Verifies that every sample matches the expected value within one integer rounding unit.
    ///
    /// @param expected the expected samples
    /// @param actual the actual samples
    private static void assertArrayAlmostEquals(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertTrue(
                    Math.abs(expected[i] - actual[i]) <= 1,
                    "Sample mismatch at index " + i + ": expected " + expected[i] + " but was " + actual[i]
            );
        }
    }

    /// Verifies that every column in one rectangular residual block equals the first column.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param width the block width in samples
    /// @param height the block height in samples
    private static void assertColumnsMatchFirstColumn(int[] residual, int width, int height) {
        for (int column = 1; column < width; column++) {
            for (int row = 0; row < height; row++) {
                assertEquals(
                        residual[row * width],
                        residual[row * width + column],
                        "Residual column mismatch at row " + row + ", column " + column
                );
            }
        }
    }

    /// Verifies that each column in one rectangular residual block is vertically antisymmetric.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param width the block width in samples
    /// @param height the block height in samples
    private static void assertVerticalAntisymmetry(int[] residual, int width, int height) {
        for (int row = 0; row < height / 2; row++) {
            int mirroredRow = height - 1 - row;
            for (int column = 0; column < width; column++) {
                assertEquals(
                        residual[row * width + column],
                        -residual[mirroredRow * width + column],
                        "Residual column is not vertically antisymmetric at column " + column
                );
            }
        }
    }

    /// Verifies that every sample in one rectangular residual block equals one shared positive
    /// constant.
    ///
    /// @param residual the residual samples in natural raster order
    /// @param width the block width in samples
    /// @param height the block height in samples
    private static void assertBlockFilledWithPositiveValue(int[] residual, int width, int height) {
        assertEquals(width * height, residual.length);
        int first = residual[0];
        assertTrue(first > 0, "DC-only rectangular residual should stay positive");
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                assertEquals(first, residual[row * width + column]);
            }
        }
    }
}
