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
/// The current implementation assumes the rollout's fixed luma/chroma `DCT_DCT` transform type
/// and supports the currently needed square and rectangular transforms. Exact staged integer
/// kernels are used for the `4` / `8` / `16` subset, while larger `32` / `64` axes fall back to
/// orthonormal cosine synthesis with the same final AV1-style `>> 3` scaling. It exposes one
/// method that reconstructs a residual sample block and one method that adds that block into an
/// already predicted plane.
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

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_401 = 401;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_1189 = 1189;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_1567 = 1567;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_1583 = 1583;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_181 = 181;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_1931 = 1931;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_3612 = 3612;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_LITERAL_3920 = 3920;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_DELTA_4076 = 4076 - 4096;

    /// The `dav1d` literal used by the `TX_16X16` odd stages.
    private static final int COSPI_DELTA_3784 = 3784 - 4096;

    /// The cached orthonormal inverse-DCT basis for one `8`-point vector.
    private static final double[][] INVERSE_DCT_BASIS_8 = createInverseDctBasis(8);

    /// The cached orthonormal inverse-DCT basis for one `16`-point vector.
    private static final double[][] INVERSE_DCT_BASIS_16 = createInverseDctBasis(16);

    /// The cached orthonormal inverse-DCT basis for one `32`-point vector.
    private static final double[][] INVERSE_DCT_BASIS_32 = createInverseDctBasis(32);

    /// The cached orthonormal inverse-DCT basis for one `64`-point vector.
    private static final double[][] INVERSE_DCT_BASIS_64 = createInverseDctBasis(64);

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
            case TX_16X16 -> reconstructSixteenBySixteen(nonNullCoefficients);
            case RTX_4X8 -> reconstructRectangularDctDct(nonNullCoefficients, 4, 8, 0);
            case RTX_8X4 -> reconstructRectangularDctDct(nonNullCoefficients, 8, 4, 0);
            case RTX_4X16 -> reconstructRectangularDctDct(nonNullCoefficients, 4, 16, 1);
            case RTX_16X4 -> reconstructRectangularDctDct(nonNullCoefficients, 16, 4, 1);
            case RTX_8X16 -> reconstructRectangularDctDct(nonNullCoefficients, 8, 16, 1);
            case RTX_16X8 -> reconstructRectangularDctDct(nonNullCoefficients, 16, 8, 1);
            case TX_32X32 -> reconstructGenericLargeDctDct(nonNullCoefficients, 32, 32);
            case TX_64X64 -> reconstructGenericLargeDctDct(nonNullCoefficients, 64, 64);
            case RTX_16X32 -> reconstructGenericLargeDctDct(nonNullCoefficients, 16, 32);
            case RTX_32X16 -> reconstructGenericLargeDctDct(nonNullCoefficients, 32, 16);
            case RTX_32X64 -> reconstructGenericLargeDctDct(nonNullCoefficients, 32, 64);
            case RTX_64X32 -> reconstructGenericLargeDctDct(nonNullCoefficients, 64, 32);
            case RTX_8X32 -> reconstructGenericLargeDctDct(nonNullCoefficients, 8, 32);
            case RTX_32X8 -> reconstructGenericLargeDctDct(nonNullCoefficients, 32, 8);
            case RTX_16X64 -> reconstructGenericLargeDctDct(nonNullCoefficients, 16, 64);
            case RTX_64X16 -> reconstructGenericLargeDctDct(nonNullCoefficients, 64, 16);
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

    /// Reconstructs one `TX_16X16` `DCT_DCT` residual block.
    ///
    /// This follows the same staged integer transform and scaling schedule used by `dav1d` for the
    /// non-rectangular `16x16` inverse transform: one row pass, one intermediate shift by `2`,
    /// one column pass, and one final shift by `4`.
    ///
    /// @param coefficients the dequantized `TX_16X16` coefficients in natural raster order
    /// @return one signed `TX_16X16` residual sample block
    private static int[] reconstructSixteenBySixteen(int[] coefficients) {
        int[] buffer = new int[256];
        int[] output = new int[256];
        int[] scratchIn = new int[16];
        int[] scratchOut = new int[16];
        for (int row = 0; row < 16; row++) {
            int rowOffset = row << 4;
            for (int column = 0; column < 16; column++) {
                scratchIn[column] = coefficients[rowOffset + column];
            }
            inverseDct16(scratchIn, scratchOut);
            for (int column = 0; column < 16; column++) {
                buffer[rowOffset + column] = roundShift(scratchOut[column], 2);
            }
        }

        for (int column = 0; column < 16; column++) {
            for (int row = 0; row < 16; row++) {
                scratchIn[row] = buffer[(row << 4) + column];
            }
            inverseDct16(scratchIn, scratchOut);
            for (int row = 0; row < 16; row++) {
                output[(row << 4) + column] = roundShift(scratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one supported rectangular `DCT_DCT` residual block.
    ///
    /// This follows the same high-level schedule used by `dav1d` for rectangular transforms:
    /// apply one pre-scale for `2:1` shapes, run one row transform, apply the size-specific
    /// intermediate shift, run one column transform, then finish with the shared `>> 4`
    /// post-transform scaling.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param width the transform width in pixels
    /// @param height the transform height in pixels
    /// @param intermediateShift the AV1 size-specific intermediate shift applied after the first pass
    /// @return one signed rectangular residual sample block
    private static int[] reconstructRectangularDctDct(
            int[] coefficients,
            int width,
            int height,
            int intermediateShift
    ) {
        int[] buffer = new int[width * height];
        int[] output = new int[width * height];
        int[] rowScratchIn = new int[width];
        int[] rowScratchOut = new int[width];
        int[] columnScratchIn = new int[height];
        int[] columnScratchOut = new int[height];
        boolean requiresRectangularPrescale = width * 2 == height || height * 2 == width;

        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            for (int column = 0; column < width; column++) {
                int coefficient = coefficients[rowOffset + column];
                rowScratchIn[column] = requiresRectangularPrescale
                        ? roundShift((long) coefficient * 181, 8)
                        : coefficient;
            }
            inverseDct(rowScratchIn, rowScratchOut, width);
            for (int column = 0; column < width; column++) {
                buffer[rowOffset + column] = roundShift(rowScratchOut[column], intermediateShift);
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                columnScratchIn[row] = buffer[row * width + column];
            }
            inverseDct(columnScratchIn, columnScratchOut, height);
            for (int row = 0; row < height; row++) {
                output[row * width + column] = roundShift(columnScratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one larger square or rectangular `DCT_DCT` residual block through cached
    /// orthonormal inverse-DCT basis matrices.
    ///
    /// The current rollout uses this slower path only for transform sizes whose larger axis has not
    /// yet been hand-lowered into one staged integer kernel. Final output scaling still follows the
    /// same AV1 residual-domain convention as the smaller exact kernels by applying one final `/ 8`
    /// after the separable 2-D synthesis.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param width the transform width in samples
    /// @param height the transform height in samples
    /// @return one signed residual sample block in natural raster order
    private static int[] reconstructGenericLargeDctDct(int[] coefficients, int width, int height) {
        double[][] rowBasis = inverseDctBasis(width);
        double[][] columnBasis = inverseDctBasis(height);
        double[] rowBuffer = new double[width * height];
        int[] output = new int[width * height];

        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            for (int column = 0; column < width; column++) {
                double sum = 0.0;
                double[] columnBasisRow = rowBasis[column];
                for (int frequency = 0; frequency < width; frequency++) {
                    sum += columnBasisRow[frequency] * coefficients[rowOffset + frequency];
                }
                rowBuffer[rowOffset + column] = sum;
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                double sum = 0.0;
                double[] rowBasisRow = columnBasis[row];
                for (int frequency = 0; frequency < height; frequency++) {
                    sum += rowBasisRow[frequency] * rowBuffer[frequency * width + column];
                }
                output[row * width + column] = saturatedInt(Math.round(sum / 8.0));
            }
        }
        return output;
    }

    /// Reconstructs one supported one-dimensional inverse DCT vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private static void inverseDct(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> inverseDct4(input, output);
            case 8 -> inverseDct8(input, output);
            case 16 -> inverseDct16(input, output);
            case 32, 64 -> inverseDctLarge(input, output, length);
            default -> throw new IllegalStateException("Unsupported inverse DCT length: " + length);
        }
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

    /// Reconstructs one one-dimensional `DCT_16` vector.
    ///
    /// The arithmetic matches the `dav1d` `inv_dct16_1d_internal_c(..., tx64 = 0)` path but uses
    /// saturated Java `int` temporaries instead of the C helper macros.
    ///
    /// @param input the dequantized `DCT_16` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct16(int[] input, int[] output) {
        int[] evenInput = new int[8];
        int[] evenOutput = new int[8];
        for (int i = 0; i < 8; i++) {
            evenInput[i] = input[i << 1];
        }
        inverseDct8(evenInput, evenOutput);

        int in1 = input[1];
        int in3 = input[3];
        int in5 = input[5];
        int in7 = input[7];
        int in9 = input[9];
        int in11 = input[11];
        int in13 = input[13];
        int in15 = input[15];

        int t8a = clip((((long) in1 * COSPI_LITERAL_401 - (long) in15 * COSPI_DELTA_4076 + 2048L) >> 12) - in15);
        int t9a = clip(((long) in9 * COSPI_LITERAL_1583 - (long) in7 * 1299 + 1024L) >> 11);
        int t10a = clip((((long) in5 * COSPI_LITERAL_1931 - (long) in11 * (COSPI_LITERAL_3612 - 4096) + 2048L) >> 12) - in11);
        int t11a = clip((((long) in13 * (COSPI_LITERAL_3920 - 4096) - (long) in3 * COSPI_LITERAL_1189 + 2048L) >> 12) + in13);
        int t12a = clip((((long) in13 * COSPI_LITERAL_1189 + (long) in3 * (COSPI_LITERAL_3920 - 4096) + 2048L) >> 12) + in3);
        int t13a = clip((((long) in5 * (COSPI_LITERAL_3612 - 4096) + (long) in11 * COSPI_LITERAL_1931 + 2048L) >> 12) + in5);
        int t14a = clip(((long) in9 * 1299 + (long) in7 * COSPI_LITERAL_1583 + 1024L) >> 11);
        int t15a = clip((((long) in1 * COSPI_DELTA_4076 + (long) in15 * COSPI_LITERAL_401 + 2048L) >> 12) + in1);

        int t8 = clip((long) t8a + t9a);
        int t9 = clip((long) t8a - t9a);
        int t10 = clip((long) t11a - t10a);
        int t11 = clip((long) t11a + t10a);
        int t12 = clip((long) t12a + t13a);
        int t13 = clip((long) t12a - t13a);
        int t14 = clip((long) t15a - t14a);
        int t15 = clip((long) t15a + t14a);

        t9a = clip((((long) t14 * COSPI_LITERAL_1567 - (long) t9 * COSPI_DELTA_3784 + 2048L) >> 12) - t9);
        t14a = clip((((long) t14 * COSPI_DELTA_3784 + (long) t9 * COSPI_LITERAL_1567 + 2048L) >> 12) + t14);
        t10a = clip(((-(long) t13 * COSPI_DELTA_3784 - (long) t10 * COSPI_LITERAL_1567 + 2048L) >> 12) - t13);
        t13a = clip((((long) t13 * COSPI_LITERAL_1567 - (long) t10 * COSPI_DELTA_3784 + 2048L) >> 12) - t10);

        t8a = clip((long) t8 + t11);
        t9 = clip((long) t9a + t10a);
        t10 = clip((long) t9a - t10a);
        int t11a2 = clip((long) t8 - t11);
        int t12a2 = clip((long) t15 - t12);
        t13 = clip((long) t14a - t13a);
        t14 = clip((long) t14a + t13a);
        int t15a2 = clip((long) t15 + t12);

        t10a = clip((((long) (t13 - t10) * COSPI_LITERAL_181) + 128L) >> 8);
        t13a = clip((((long) (t13 + t10) * COSPI_LITERAL_181) + 128L) >> 8);
        int t11b = clip((((long) (t12a2 - t11a2) * COSPI_LITERAL_181) + 128L) >> 8);
        int t12b = clip((((long) (t12a2 + t11a2) * COSPI_LITERAL_181) + 128L) >> 8);

        output[0] = clip((long) evenOutput[0] + t15a2);
        output[1] = clip((long) evenOutput[1] + t14);
        output[2] = clip((long) evenOutput[2] + t13a);
        output[3] = clip((long) evenOutput[3] + t12b);
        output[4] = clip((long) evenOutput[4] + t11b);
        output[5] = clip((long) evenOutput[5] + t10a);
        output[6] = clip((long) evenOutput[6] + t9);
        output[7] = clip((long) evenOutput[7] + t8a);
        output[8] = clip((long) evenOutput[7] - t8a);
        output[9] = clip((long) evenOutput[6] - t9);
        output[10] = clip((long) evenOutput[5] - t10a);
        output[11] = clip((long) evenOutput[4] - t11b);
        output[12] = clip((long) evenOutput[3] - t12b);
        output[13] = clip((long) evenOutput[2] - t13a);
        output[14] = clip((long) evenOutput[1] - t14);
        output[15] = clip((long) evenOutput[0] - t15a2);
    }

    /// Reconstructs one one-dimensional larger `DCT_N` vector through cached orthonormal cosine
    /// basis rows.
    ///
    /// This path is currently used for `N = 32` and `N = 64`.
    ///
    /// @param input the dequantized `DCT_N` input vector
    /// @param output the reconstructed output vector
    /// @param length the transform length in samples
    private static void inverseDctLarge(int[] input, int[] output, int length) {
        double[][] basis = inverseDctBasis(length);
        for (int row = 0; row < length; row++) {
            double sum = 0.0;
            double[] basisRow = basis[row];
            for (int frequency = 0; frequency < length; frequency++) {
                sum += basisRow[frequency] * input[frequency];
            }
            output[row] = saturatedInt(Math.round(sum));
        }
    }

    /// Returns one cached orthonormal inverse-DCT basis matrix for the requested supported vector
    /// length.
    ///
    /// The first index selects the reconstructed spatial position; the second index selects the
    /// input frequency.
    ///
    /// @param length the requested vector length
    /// @return one cached orthonormal inverse-DCT basis matrix
    private static double[][] inverseDctBasis(int length) {
        return switch (length) {
            case 8 -> INVERSE_DCT_BASIS_8;
            case 16 -> INVERSE_DCT_BASIS_16;
            case 32 -> INVERSE_DCT_BASIS_32;
            case 64 -> INVERSE_DCT_BASIS_64;
            default -> throw new IllegalStateException("Unsupported inverse DCT basis length: " + length);
        };
    }

    /// Creates one orthonormal inverse-DCT basis matrix for the supplied vector length.
    ///
    /// The generated rows implement the standard DCT-III synthesis basis whose `u = 0` column uses
    /// the lower `1 / sqrt(n)` normalization while every other frequency uses `sqrt(2 / n)`.
    ///
    /// @param length the vector length in samples
    /// @return one orthonormal inverse-DCT basis matrix for the supplied vector length
    private static double[][] createInverseDctBasis(int length) {
        double[][] basis = new double[length][length];
        double inverseLength = 1.0 / length;
        double dcScale = Math.sqrt(inverseLength);
        double acScale = Math.sqrt(2.0 * inverseLength);
        for (int row = 0; row < length; row++) {
            for (int frequency = 0; frequency < length; frequency++) {
                double normalization = frequency == 0 ? dcScale : acScale;
                basis[row][frequency] = normalization
                        * Math.cos(((2.0 * row + 1.0) * frequency * Math.PI) / (2.0 * length));
            }
        }
        return basis;
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

    /// Saturates one intermediate transform value into the signed `int` range.
    ///
    /// @param value the intermediate transform value
    /// @return the saturated `int`
    private static int clip(long value) {
        return saturatedInt(value);
    }

    /// Returns the checked transform area for the current supported subset.
    ///
    /// @param transformSize the transform size to validate
    /// @return the transform area in samples
    private static int checkedTransformArea(TransformSize transformSize) {
        return transformSize.widthPixels() * transformSize.heightPixels();
    }

    /// Creates one stable unsupported-transform failure.
    ///
    /// @param transformSize the unsupported transform size
    /// @return one stable unsupported-transform failure
    private static IllegalStateException unsupportedTransformSize(TransformSize transformSize) {
        return new IllegalStateException("Unsupported inverse transform size: " + transformSize);
    }
}
