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

import java.util.Objects;

/// Minimal inverse-transform helper for the first residual-producing reconstruction path.
///
/// The current implementation assumes the rollout's fixed luma `DCT_DCT` transform type and
/// supports only square `TX_4X4` and `TX_8X8` blocks. It exposes one method that reconstructs a
/// residual sample block and one method that adds that block into an already predicted plane.
@NotNullByDefault
final class InverseTransformer {
    /// The AV1 inverse-transform cosine precision.
    private static final int INV_COS_BIT = 12;

    /// The `cospi[8]` constant at inverse-transform precision `12`.
    private static final int COSPI_8 = 4017;

    /// The `cospi[16]` constant at inverse-transform precision `12`.
    private static final int COSPI_16 = 3784;

    /// The `cospi[24]` constant at inverse-transform precision `12`.
    private static final int COSPI_24 = 3406;

    /// The `cospi[32]` constant at inverse-transform precision `12`.
    private static final int COSPI_32 = 2896;

    /// The `cospi[40]` constant at inverse-transform precision `12`.
    private static final int COSPI_40 = 2276;

    /// The `cospi[48]` constant at inverse-transform precision `12`.
    private static final int COSPI_48 = 1567;

    /// The `cospi[56]` constant at inverse-transform precision `12`.
    private static final int COSPI_56 = 799;

    /// Prevents instantiation of this utility class.
    private InverseTransformer() {
    }

    /// Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
    ///
    /// Input and output both use natural raster order. The returned samples are signed residuals
    /// that are ready to add to a predictor plane of the same dimensions.
    ///
    /// @param dequantizedCoefficients the dequantized `DCT_DCT` coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @return one signed residual sample block in natural raster order
    static int[] reconstructResidualBlock(int[] dequantizedCoefficients, TransformSize transformSize) {
        int[] nonNullCoefficients = Objects.requireNonNull(dequantizedCoefficients, "dequantizedCoefficients");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int transformArea = checkedTransformArea(nonNullTransformSize);
        if (nonNullCoefficients.length != transformArea) {
            throw new IllegalArgumentException("dequantizedCoefficients length does not match transform area");
        }

        return switch (nonNullTransformSize) {
            case TX_4X4 -> reconstructFourByFour(nonNullCoefficients);
            case TX_8X8 -> reconstructEightByEight(nonNullCoefficients);
            default -> throw unsupportedTransformSize(nonNullTransformSize);
        };
    }

    /// Adds one already reconstructed residual block into the supplied predictor plane.
    ///
    /// Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
    /// residual values directly.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the residual block size
    /// @param residualSamples the signed residual sample block in natural raster order
    static void addResidualBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            int[] residualSamples
    ) {
        addResidualBlock(
                plane,
                x,
                y,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                residualSamples
        );
    }

    /// Adds one already reconstructed residual block into the supplied predictor plane while
    /// clipping writes to the visible residual footprint.
    ///
    /// Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
    /// residual values directly.
    ///
    /// @param plane the destination predictor plane
    /// @param x the zero-based destination x coordinate
    /// @param y the zero-based destination y coordinate
    /// @param transformSize the coded residual block size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param residualSamples the signed residual sample block in natural raster order
    static void addResidualBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int[] residualSamples
    ) {
        MutablePlaneBuffer nonNullPlane = Objects.requireNonNull(plane, "plane");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int[] nonNullResidualSamples = Objects.requireNonNull(residualSamples, "residualSamples");
        int transformArea = checkedTransformArea(nonNullTransformSize);
        if (nonNullResidualSamples.length != transformArea) {
            throw new IllegalArgumentException("residualSamples length does not match transform area");
        }
        if (visibleWidthPixels <= 0 || visibleWidthPixels > nonNullTransformSize.widthPixels()) {
            throw new IllegalArgumentException("visibleWidthPixels out of range: " + visibleWidthPixels);
        }
        if (visibleHeightPixels <= 0 || visibleHeightPixels > nonNullTransformSize.heightPixels()) {
            throw new IllegalArgumentException("visibleHeightPixels out of range: " + visibleHeightPixels);
        }

        int transformWidth = nonNullTransformSize.widthPixels();
        for (int row = 0; row < visibleHeightPixels; row++) {
            for (int column = 0; column < visibleWidthPixels; column++) {
                int sampleIndex = row * transformWidth + column;
                int predicted = nonNullPlane.sample(x + column, y + row);
                nonNullPlane.setSample(x + column, y + row, predicted + nonNullResidualSamples[sampleIndex]);
            }
        }
    }

    /// Reconstructs one `TX_4X4` `DCT_DCT` residual block.
    ///
    /// @param coefficients the dequantized `TX_4X4` coefficients in natural raster order
    /// @return one signed `TX_4X4` residual sample block
    private static int[] reconstructFourByFour(int[] coefficients) {
        int[] buffer = new int[16];
        int[] output = new int[16];
        int[] scratchIn = new int[4];
        int[] scratchOut = new int[4];
        for (int row = 0; row < 4; row++) {
            int rowOffset = row << 2;
            scratchIn[0] = coefficients[rowOffset];
            scratchIn[1] = coefficients[rowOffset + 1];
            scratchIn[2] = coefficients[rowOffset + 2];
            scratchIn[3] = coefficients[rowOffset + 3];
            inverseDct4(scratchIn, scratchOut);
            buffer[rowOffset] = scratchOut[0];
            buffer[rowOffset + 1] = scratchOut[1];
            buffer[rowOffset + 2] = scratchOut[2];
            buffer[rowOffset + 3] = scratchOut[3];
        }

        for (int column = 0; column < 4; column++) {
            scratchIn[0] = buffer[column];
            scratchIn[1] = buffer[4 + column];
            scratchIn[2] = buffer[8 + column];
            scratchIn[3] = buffer[12 + column];
            inverseDct4(scratchIn, scratchOut);
            output[column] = roundShift(scratchOut[0], 4);
            output[4 + column] = roundShift(scratchOut[1], 4);
            output[8 + column] = roundShift(scratchOut[2], 4);
            output[12 + column] = roundShift(scratchOut[3], 4);
        }
        return output;
    }

    /// Reconstructs one `TX_8X8` `DCT_DCT` residual block.
    ///
    /// @param coefficients the dequantized `TX_8X8` coefficients in natural raster order
    /// @return one signed `TX_8X8` residual sample block
    private static int[] reconstructEightByEight(int[] coefficients) {
        int[] buffer = new int[64];
        int[] output = new int[64];
        int[] scratchIn = new int[8];
        int[] scratchOut = new int[8];
        for (int row = 0; row < 8; row++) {
            int rowOffset = row << 3;
            for (int column = 0; column < 8; column++) {
                scratchIn[column] = coefficients[rowOffset + column];
            }
            inverseDct8(scratchIn, scratchOut);
            for (int column = 0; column < 8; column++) {
                buffer[rowOffset + column] = roundShift(scratchOut[column], 1);
            }
        }

        for (int column = 0; column < 8; column++) {
            for (int row = 0; row < 8; row++) {
                scratchIn[row] = buffer[(row << 3) + column];
            }
            inverseDct8(scratchIn, scratchOut);
            for (int row = 0; row < 8; row++) {
                output[(row << 3) + column] = roundShift(scratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one one-dimensional `DCT_4` vector.
    ///
    /// @param input the dequantized `DCT_4` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct4(int[] input, int[] output) {
        int stage1_0 = input[0];
        int stage1_1 = input[2];
        int stage1_2 = input[1];
        int stage1_3 = input[3];

        int stage2_0 = halfBtf(COSPI_32, stage1_0, COSPI_32, stage1_1);
        int stage2_1 = halfBtf(COSPI_32, stage1_0, -COSPI_32, stage1_1);
        int stage2_2 = halfBtf(COSPI_48, stage1_2, -COSPI_16, stage1_3);
        int stage2_3 = halfBtf(COSPI_16, stage1_2, COSPI_48, stage1_3);

        output[0] = stage2_0 + stage2_3;
        output[1] = stage2_1 + stage2_2;
        output[2] = stage2_1 - stage2_2;
        output[3] = stage2_0 - stage2_3;
    }

    /// Reconstructs one one-dimensional `DCT_8` vector.
    ///
    /// @param input the dequantized `DCT_8` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct8(int[] input, int[] output) {
        int stage1_0 = input[0];
        int stage1_1 = input[4];
        int stage1_2 = input[2];
        int stage1_3 = input[6];
        int stage1_4 = input[1];
        int stage1_5 = input[5];
        int stage1_6 = input[3];
        int stage1_7 = input[7];

        int stage2_0 = stage1_0;
        int stage2_1 = stage1_1;
        int stage2_2 = stage1_2;
        int stage2_3 = stage1_3;
        int stage2_4 = halfBtf(COSPI_56, stage1_4, -COSPI_8, stage1_7);
        int stage2_5 = halfBtf(COSPI_24, stage1_5, -COSPI_40, stage1_6);
        int stage2_6 = halfBtf(COSPI_40, stage1_5, COSPI_24, stage1_6);
        int stage2_7 = halfBtf(COSPI_8, stage1_4, COSPI_56, stage1_7);

        int stage3_0 = halfBtf(COSPI_32, stage2_0, COSPI_32, stage2_1);
        int stage3_1 = halfBtf(COSPI_32, stage2_0, -COSPI_32, stage2_1);
        int stage3_2 = halfBtf(COSPI_48, stage2_2, -COSPI_16, stage2_3);
        int stage3_3 = halfBtf(COSPI_16, stage2_2, COSPI_48, stage2_3);
        int stage3_4 = stage2_4 + stage2_5;
        int stage3_5 = stage2_4 - stage2_5;
        int stage3_6 = -stage2_6 + stage2_7;
        int stage3_7 = stage2_6 + stage2_7;

        int stage4_0 = stage3_0 + stage3_3;
        int stage4_1 = stage3_1 + stage3_2;
        int stage4_2 = stage3_1 - stage3_2;
        int stage4_3 = stage3_0 - stage3_3;
        int stage4_4 = stage3_4;
        int stage4_5 = halfBtf(-COSPI_32, stage3_5, COSPI_32, stage3_6);
        int stage4_6 = halfBtf(COSPI_32, stage3_5, COSPI_32, stage3_6);
        int stage4_7 = stage3_7;

        output[0] = stage4_0 + stage4_7;
        output[1] = stage4_1 + stage4_6;
        output[2] = stage4_2 + stage4_5;
        output[3] = stage4_3 + stage4_4;
        output[4] = stage4_3 - stage4_4;
        output[5] = stage4_2 - stage4_5;
        output[6] = stage4_1 - stage4_6;
        output[7] = stage4_0 - stage4_7;
    }

    /// Applies one AV1 half-butterfly operation with inverse-transform rounding.
    ///
    /// @param weight0 the first cosine weight
    /// @param value0 the first source value
    /// @param weight1 the second cosine weight
    /// @param value1 the second source value
    /// @return the rounded half-butterfly result
    private static int halfBtf(int weight0, int value0, int weight1, int value1) {
        return roundShift((long) weight0 * value0 + (long) weight1 * value1, INV_COS_BIT);
    }

    /// Applies one AV1 signed round shift.
    ///
    /// AV1 rounds ties away from zero by adding the sign bit before the arithmetic shift.
    ///
    /// @param value the signed value to shift
    /// @param bitCount the positive number of bits to shift away, or `0`
    /// @return the rounded signed result
    private static int roundShift(long value, int bitCount) {
        if (bitCount < 0) {
            throw new IllegalArgumentException("bitCount < 0: " + bitCount);
        }
        if (bitCount == 0) {
            return saturatedInt(value);
        }
        long rounded = (value + (1L << (bitCount - 1)) + (value < 0 ? -1L : 0L)) >> bitCount;
        return saturatedInt(rounded);
    }

    /// Saturates one `long` into the signed `int` range.
    ///
    /// @param value the value to saturate
    /// @return the saturated `int`
    private static int saturatedInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /// Returns the checked transform area for the current supported subset.
    ///
    /// @param transformSize the transform size to validate
    /// @return the transform area in samples
    private static int checkedTransformArea(TransformSize transformSize) {
        return switch (transformSize) {
            case TX_4X4 -> 16;
            case TX_8X8 -> 64;
            default -> throw unsupportedTransformSize(transformSize);
        };
    }

    /// Creates one stable unsupported-transform failure.
    ///
    /// @param transformSize the unsupported transform size
    /// @return one stable unsupported-transform failure
    private static IllegalStateException unsupportedTransformSize(TransformSize transformSize) {
        return new IllegalStateException("Unsupported inverse transform size: " + transformSize);
    }
}
