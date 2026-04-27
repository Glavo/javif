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

import org.glavo.avif.internal.av1.model.TransformKernel;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Inverse-transform helper for the residual-producing reconstruction path.
///
/// Exact staged integer kernels are used for the most common transform axes, while larger
/// `DCT_DCT` axes still use cached separable synthesis matrices rescaled to the AV1 integer
/// transform gain. It exposes one method that reconstructs a residual sample block and one method
/// that adds that block into an already predicted plane.
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

    /// The AV1 inverse-transform cosine table at precision `12`.
    private static final int @Unmodifiable [] COSPI = new int[]{
            4096, 4095, 4091, 4085, 4076, 4065, 4052, 4036,
            4017, 3996, 3973, 3948, 3920, 3889, 3857, 3822,
            3784, 3745, 3703, 3659, 3612, 3564, 3513, 3461,
            3406, 3349, 3290, 3229, 3166, 3102, 3035, 2967,
            2896, 2824, 2751, 2675, 2598, 2520, 2440, 2359,
            2276, 2191, 2106, 2019, 1931, 1842, 1751, 1660,
            1567, 1474, 1380, 1285, 1189, 1092, 995, 897,
            799, 700, 601, 501, 401, 301, 201, 101,
    };

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

    /// The cached orthonormal inverse-DCT basis for one `4`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_DCT_BASIS_4 = createInverseDctBasis(4);

    /// The cached orthonormal inverse-DCT basis for one `8`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_DCT_BASIS_8 = createInverseDctBasis(8);

    /// The cached orthonormal inverse-DCT basis for one `16`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_DCT_BASIS_16 = createInverseDctBasis(16);

    /// The cached orthonormal inverse-DCT basis for one `32`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_DCT_BASIS_32 = createInverseDctBasis(32);

    /// The cached orthonormal inverse-DCT basis for one `64`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_DCT_BASIS_64 = createInverseDctBasis(64);

    /// The cached orthonormal inverse-ADST basis for one `4`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_ADST_BASIS_4 = createInverseAdstBasis(4);

    /// The cached orthonormal inverse-ADST basis for one `8`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_ADST_BASIS_8 = createInverseAdstBasis(8);

    /// The cached orthonormal inverse-ADST basis for one `16`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_ADST_BASIS_16 = createInverseAdstBasis(16);

    /// The cached orthonormal inverse-ADST basis for one `32`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_ADST_BASIS_32 = createInverseAdstBasis(32);

    /// The cached orthonormal inverse-ADST basis for one `64`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_ADST_BASIS_64 = createInverseAdstBasis(64);

    /// The cached flipped inverse-ADST basis for one `4`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_FLIPADST_BASIS_4 = createFlippedBasis(INVERSE_ADST_BASIS_4);

    /// The cached flipped inverse-ADST basis for one `8`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_FLIPADST_BASIS_8 = createFlippedBasis(INVERSE_ADST_BASIS_8);

    /// The cached flipped inverse-ADST basis for one `16`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_FLIPADST_BASIS_16 = createFlippedBasis(INVERSE_ADST_BASIS_16);

    /// The cached flipped inverse-ADST basis for one `32`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_FLIPADST_BASIS_32 = createFlippedBasis(INVERSE_ADST_BASIS_32);

    /// The cached flipped inverse-ADST basis for one `64`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] INVERSE_FLIPADST_BASIS_64 = createFlippedBasis(INVERSE_ADST_BASIS_64);

    /// The cached identity basis for one `4`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] IDENTITY_BASIS_4 = createIdentityBasis(4);

    /// The cached identity basis for one `8`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] IDENTITY_BASIS_8 = createIdentityBasis(8);

    /// The cached identity basis for one `16`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] IDENTITY_BASIS_16 = createIdentityBasis(16);

    /// The cached identity basis for one `32`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] IDENTITY_BASIS_32 = createIdentityBasis(32);

    /// The cached identity basis for one `64`-point vector.
    private static final double @Unmodifiable [] @Unmodifiable [] IDENTITY_BASIS_64 = createIdentityBasis(64);

    /// The unrestricted clip range used by helper tests that do not supply bit-depth context.
    private static final ClipRange FULL_INT_CLIP_RANGE = new ClipRange(Integer.MIN_VALUE, Integer.MAX_VALUE);

    /// The active stage-local clip range used by the exact inverse-transform kernels.
    private static final ThreadLocal<ClipRange> ACTIVE_CLIP_RANGE = ThreadLocal.withInitial(() -> FULL_INT_CLIP_RANGE);

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
        return reconstructResidualBlock(dequantizedCoefficients, transformSize, TransformType.DCT_DCT, 8);
    }

    /// Reconstructs one residual sample block from dequantized transform coefficients.
    ///
    /// Input and output both use natural raster order. The returned samples are signed residuals
    /// that are ready to add to a predictor plane of the same dimensions.
    ///
    /// @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @return one signed residual sample block in natural raster order
    static int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType
    ) {
        return reconstructResidualBlock(dequantizedCoefficients, transformSize, transformType, 8);
    }

    /// Reconstructs one residual sample block from dequantized transform coefficients while using
    /// the active frame bit depth to mirror dav1d's stage-local clip ranges.
    ///
    /// @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the transform type to reconstruct
    /// @param bitDepth the decoded sample bit depth
    /// @return one signed residual sample block in natural raster order
    static int[] reconstructResidualBlock(
            int[] dequantizedCoefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth
    ) {
        int[] nonNullCoefficients = Objects.requireNonNull(dequantizedCoefficients, "dequantizedCoefficients");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        int transformArea = checkedTransformArea(nonNullTransformSize);
        if (nonNullCoefficients.length != transformArea) {
            throw new IllegalArgumentException("dequantizedCoefficients length does not match transform area");
        }

        if (nonNullTransformType == TransformType.WHT_WHT) {
            return reconstructWalshHadamard(nonNullCoefficients, nonNullTransformSize);
        }

        if (nonNullTransformType != TransformType.DCT_DCT) {
            return reconstructGenericTransform(
                    nonNullCoefficients,
                    nonNullTransformSize,
                    nonNullTransformType,
                    bitDepth
            );
        }

        return reconstructDctDct(nonNullCoefficients, nonNullTransformSize, bitDepth);
    }

    /// Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
    ///
    /// @param coefficients the dequantized `DCT_DCT` coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @return one signed residual sample block in natural raster order
    private static int[] reconstructDctDct(int[] coefficients, TransformSize transformSize, int bitDepth) {
        ClipRange rowClipRange = rowClipRange(bitDepth);
        ClipRange columnClipRange = columnClipRange(bitDepth);
        return switch (transformSize) {
            case TX_4X4 -> reconstructFourByFour(coefficients, rowClipRange, columnClipRange);
            case TX_8X8 -> reconstructEightByEight(coefficients, rowClipRange, columnClipRange);
            case TX_16X16 -> reconstructSixteenBySixteen(coefficients, rowClipRange, columnClipRange);
            case RTX_4X8 -> reconstructRectangularDctDct(coefficients, 4, 8, 0, rowClipRange, columnClipRange);
            case RTX_8X4 -> reconstructRectangularDctDct(coefficients, 8, 4, 0, rowClipRange, columnClipRange);
            case RTX_4X16 -> reconstructRectangularDctDct(coefficients, 4, 16, 1, rowClipRange, columnClipRange);
            case RTX_16X4 -> reconstructRectangularDctDct(coefficients, 16, 4, 1, rowClipRange, columnClipRange);
            case RTX_8X16 -> reconstructRectangularDctDct(coefficients, 8, 16, 1, rowClipRange, columnClipRange);
            case RTX_16X8 -> reconstructRectangularDctDct(coefficients, 16, 8, 1, rowClipRange, columnClipRange);
            case TX_32X32 -> reconstructRectangularDctDct(coefficients, 32, 32, 2, rowClipRange, columnClipRange);
            case TX_64X64 -> reconstructRectangularDctDct(coefficients, 64, 64, 2, rowClipRange, columnClipRange);
            case RTX_16X32 -> reconstructRectangularDctDct(coefficients, 16, 32, 1, rowClipRange, columnClipRange);
            case RTX_32X16 -> reconstructRectangularDctDct(coefficients, 32, 16, 1, rowClipRange, columnClipRange);
            case RTX_32X64 -> reconstructGenericLargeDctDct(coefficients, 32, 64, 1);
            case RTX_64X32 -> reconstructGenericLargeDctDct(coefficients, 64, 32, 1);
            case RTX_8X32 -> reconstructRectangularDctDct(coefficients, 8, 32, 2, rowClipRange, columnClipRange);
            case RTX_32X8 -> reconstructRectangularDctDct(coefficients, 32, 8, 2, rowClipRange, columnClipRange);
            case RTX_16X64 -> reconstructGenericLargeDctDct(coefficients, 16, 64, 2);
            case RTX_64X16 -> reconstructGenericLargeDctDct(coefficients, 64, 16, 2);
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
    private static int[] reconstructFourByFour(int[] coefficients, ClipRange rowClipRange, ClipRange columnClipRange) {
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
            withActiveClipRange(rowClipRange, () -> inverseDct4(scratchIn, scratchOut));
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
            withActiveClipRange(columnClipRange, () -> inverseDct4(scratchIn, scratchOut));
            output[column] = positiveRoundShift(scratchOut[0], 4);
            output[4 + column] = positiveRoundShift(scratchOut[1], 4);
            output[8 + column] = positiveRoundShift(scratchOut[2], 4);
            output[12 + column] = positiveRoundShift(scratchOut[3], 4);
        }
        return output;
    }

    /// Reconstructs one `TX_8X8` `DCT_DCT` residual block.
    ///
    /// @param coefficients the dequantized `TX_8X8` coefficients in natural raster order
    /// @return one signed `TX_8X8` residual sample block
    private static int[] reconstructEightByEight(int[] coefficients, ClipRange rowClipRange, ClipRange columnClipRange) {
        int[] buffer = new int[64];
        int[] output = new int[64];
        int[] scratchIn = new int[8];
        int[] scratchOut = new int[8];
        for (int row = 0; row < 8; row++) {
            int rowOffset = row << 3;
            for (int column = 0; column < 8; column++) {
                scratchIn[column] = coefficients[rowOffset + column];
            }
            withActiveClipRange(rowClipRange, () -> inverseDct8(scratchIn, scratchOut));
            for (int column = 0; column < 8; column++) {
                buffer[rowOffset + column] = clipToRange(positiveRoundShift(scratchOut[column], 1), columnClipRange);
            }
        }

        for (int column = 0; column < 8; column++) {
            for (int row = 0; row < 8; row++) {
                scratchIn[row] = buffer[(row << 3) + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct8(scratchIn, scratchOut));
            for (int row = 0; row < 8; row++) {
                output[(row << 3) + column] = positiveRoundShift(scratchOut[row], 4);
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
    private static int[] reconstructSixteenBySixteen(int[] coefficients, ClipRange rowClipRange, ClipRange columnClipRange) {
        int[] buffer = new int[256];
        int[] output = new int[256];
        int[] scratchIn = new int[16];
        int[] scratchOut = new int[16];
        for (int row = 0; row < 16; row++) {
            int rowOffset = row << 4;
            for (int column = 0; column < 16; column++) {
                scratchIn[column] = coefficients[rowOffset + column];
            }
            withActiveClipRange(rowClipRange, () -> inverseDct16(scratchIn, scratchOut));
            for (int column = 0; column < 16; column++) {
                buffer[rowOffset + column] = clipToRange(positiveRoundShift(scratchOut[column], 2), columnClipRange);
            }
        }

        for (int column = 0; column < 16; column++) {
            for (int row = 0; row < 16; row++) {
                scratchIn[row] = buffer[(row << 4) + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct16(scratchIn, scratchOut));
            for (int row = 0; row < 16; row++) {
                output[(row << 4) + column] = positiveRoundShift(scratchOut[row], 4);
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
            int intermediateShift,
            ClipRange rowClipRange,
            ClipRange columnClipRange
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
                        ? positiveRoundShift((long) coefficient * 181, 8)
                        : coefficient;
            }
            withActiveClipRange(rowClipRange, () -> inverseDct(rowScratchIn, rowScratchOut, width));
            for (int column = 0; column < width; column++) {
                buffer[rowOffset + column] = clipToRange(
                        positiveRoundShift(rowScratchOut[column], intermediateShift),
                        columnClipRange
                );
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                columnScratchIn[row] = buffer[row * width + column];
            }
            withActiveClipRange(columnClipRange, () -> inverseDct(columnScratchIn, columnScratchOut, height));
            for (int row = 0; row < height; row++) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one larger square or rectangular `DCT_DCT` residual block through cached
    /// orthonormal inverse-DCT basis matrices.
    ///
    /// The current rollout uses this slower path only for transform sizes whose larger axis has not
    /// yet been hand-lowered into one staged integer kernel. The orthonormal synthesis result is
    /// rescaled to the same DC-domain gain as dav1d's integer path for the transform-size-specific
    /// intermediate shift.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param width the transform width in samples
    /// @param height the transform height in samples
    /// @param intermediateShift the dav1d intermediate shift for this large transform size
    /// @return one signed residual sample block in natural raster order
    private static int[] reconstructGenericLargeDctDct(
            int[] coefficients,
            int width,
            int height,
            int intermediateShift
    ) {
        double[][] rowBasis = inverseDctBasis(width);
        double[][] columnBasis = inverseDctBasis(height);
        double[] rowBuffer = new double[width * height];
        int[] output = new int[width * height];
        double outputScale = largeDctOutputScale(width, height, intermediateShift);

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
                output[row * width + column] = saturatedInt(Math.round((sum * outputScale) / 8.0));
            }
        }
        return output;
    }

    /// Returns the scale that maps orthonormal large-DCT output to dav1d's integer-transform gain.
    ///
    /// The scalar dav1d DC path applies one `181/256` pass, the transform-size intermediate shift,
    /// and one final `181/4096` pass. Rectangular 2:1 transforms apply one extra `181/256` prescale.
    ///
    /// @param width the transform width in samples
    /// @param height the transform height in samples
    /// @param intermediateShift the dav1d intermediate shift for this transform size
    /// @return the multiplicative correction for an orthonormal 2-D DCT followed by `/ 8`
    private static double largeDctOutputScale(int width, int height, int intermediateShift) {
        double orthonormalDenominator = Math.sqrt((double) width * height) * 8.0;
        double dav1dDenominator = 32.0 * (1 << intermediateShift);
        if (width * 2 == height || height * 2 == width) {
            dav1dDenominator *= Math.sqrt(2.0);
        }
        return orthonormalDenominator / dav1dDenominator;
    }

    /// Reconstructs one non-`DCT_DCT` residual block through staged one-dimensional kernels.
    ///
    /// @param coefficients the dequantized coefficients in natural raster order
    /// @param transformSize the transform size to reconstruct
    /// @param transformType the explicit transform type to reconstruct
    /// @return one signed residual sample block in natural raster order
    private static int[] reconstructGenericTransform(
            int[] coefficients,
            TransformSize transformSize,
            TransformType transformType,
            int bitDepth
    ) {
        int width = transformSize.widthPixels();
        int height = transformSize.heightPixels();
        int intermediateShift = intermediateTransformShift(transformSize);
        boolean requiresRectangularPrescale = width * 2 == height || height * 2 == width;
        ClipRange rowClipRange = rowClipRange(bitDepth);
        ClipRange columnClipRange = columnClipRange(bitDepth);
        int[] buffer = new int[width * height];
        int[] output = new int[width * height];
        int[] rowScratchIn = new int[width];
        int[] rowScratchOut = new int[width];
        int[] columnScratchIn = new int[height];
        int[] columnScratchOut = new int[height];

        for (int row = 0; row < height; row++) {
            int rowOffset = row * width;
            for (int column = 0; column < width; column++) {
                int coefficient = coefficients[rowOffset + column];
                rowScratchIn[column] = requiresRectangularPrescale
                        ? positiveRoundShift((long) coefficient * 181, 8)
                        : coefficient;
            }
            withActiveClipRange(
                    rowClipRange,
                    () -> inverseKernel(transformType.horizontalKernel(), rowScratchIn, rowScratchOut, width)
            );
            for (int column = 0; column < width; column++) {
                buffer[rowOffset + column] = clipToRange(
                        positiveRoundShift(rowScratchOut[column], intermediateShift),
                        columnClipRange
                );
            }
        }

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                columnScratchIn[row] = buffer[row * width + column];
            }
            withActiveClipRange(
                    columnClipRange,
                    () -> inverseKernel(transformType.verticalKernel(), columnScratchIn, columnScratchOut, height)
            );
            for (int row = 0; row < height; row++) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row], 4);
            }
        }
        return output;
    }

    /// Reconstructs one AV1 lossless `WHT_WHT` residual block.
    ///
    /// AV1 lossless blocks always use a coded `TX_4X4` transform. The initial `>> 2` matches the
    /// scalar `dav1d` path; the input is already converted into this decoder's natural row-major
    /// coefficient order before reaching the inverse transform.
    ///
    /// @param coefficients the dequantized lossless coefficients in natural raster order
    /// @param transformSize the active transform size
    /// @return one signed `TX_4X4` residual sample block
    private static int[] reconstructWalshHadamard(int[] coefficients, TransformSize transformSize) {
        if (transformSize != TransformSize.TX_4X4) {
            throw new IllegalStateException("WHT_WHT is only valid for TX_4X4 lossless blocks");
        }

        int[] tmp = new int[16];
        int[] output = new int[16];
        int[] scratch = new int[4];
        for (int y = 0; y < 4; y++) {
            int rowOffset = y << 2;
            for (int x = 0; x < 4; x++) {
                scratch[x] = coefficients[rowOffset + x] >> 2;
            }
            inverseWalshHadamard4(scratch);
            for (int x = 0; x < 4; x++) {
                tmp[(y << 2) + x] = scratch[x];
            }
        }

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                scratch[y] = tmp[(y << 2) + x];
            }
            inverseWalshHadamard4(scratch);
            for (int y = 0; y < 4; y++) {
                output[(y << 2) + x] = scratch[y];
            }
        }
        return output;
    }

    /// Applies one in-place AV1 inverse Walsh-Hadamard transform to four samples.
    ///
    /// @param values the four-sample vector to transform in place
    private static void inverseWalshHadamard4(int[] values) {
        int in0 = values[0];
        int in1 = values[1];
        int in2 = values[2];
        int in3 = values[3];

        int t0 = in0 + in1;
        int t2 = in2 - in3;
        int t4 = (t0 - t2) >> 1;
        int t3 = t4 - in3;
        int t1 = t4 - in1;

        values[0] = t0 - t3;
        values[1] = t3;
        values[2] = t1;
        values[3] = t2 + t1;
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
            case 32 -> inverseDct32Dav1d(input, output);
            case 64 -> inverseDct64Aom(input, output);
            default -> throw new IllegalStateException("Unsupported inverse DCT length: " + length);
        }
    }

    /// Reconstructs one supported one-dimensional inverse transform vector.
    ///
    /// @param kernel the one-dimensional kernel to apply
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private static void inverseKernel(TransformKernel kernel, int[] input, int[] output, int length) {
        switch (kernel) {
            case DCT -> inverseDct(input, output, length);
            case ADST -> inverseAdst(input, output, length);
            case FLIPADST -> inverseFlipAdst(input, output, length);
            case IDENTITY -> inverseIdentity(input, output, length);
            case WHT -> throw new IllegalStateException("WHT uses the dedicated lossless transform path");
        }
    }

    /// Returns the size-specific intermediate inverse-transform shift used between the two 1-D
    /// transform passes.
    ///
    /// @param transformSize the transform size
    /// @return the intermediate shift in bits
    private static int intermediateTransformShift(TransformSize transformSize) {
        return switch (transformSize) {
            case TX_4X4, RTX_4X8, RTX_8X4 -> 0;
            case RTX_4X16, TX_8X8, RTX_8X16, RTX_16X4, RTX_16X8, RTX_16X32, RTX_32X16,
                 RTX_32X64, RTX_64X32 -> 1;
            case RTX_8X32, TX_16X16, TX_32X32, TX_64X64, RTX_32X8, RTX_16X64, RTX_64X16 -> 2;
        };
    }

    /// Reconstructs one one-dimensional `DCT_4` vector.
    ///
    /// This follows the libaom staged integer `av1_idct4` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_4` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct4(int[] input, int[] output) {
        int[] step = new int[4];

        output[0] = input[0];
        output[1] = input[2];
        output[2] = input[1];
        output[3] = input[3];

        step[0] = halfBtf(COSPI[32], output[0], COSPI[32], output[1]);
        step[1] = halfBtf(COSPI[32], output[0], -COSPI[32], output[1]);
        step[2] = halfBtf(COSPI[48], output[2], -COSPI[16], output[3]);
        step[3] = halfBtf(COSPI[16], output[2], COSPI[48], output[3]);

        output[0] = clip((long) step[0] + step[3]);
        output[1] = clip((long) step[1] + step[2]);
        output[2] = clip((long) step[1] - step[2]);
        output[3] = clip((long) step[0] - step[3]);
    }

    /// Reconstructs one one-dimensional `DCT_8` vector.
    ///
    /// This follows the libaom staged integer `av1_idct8` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_8` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct8(int[] input, int[] output) {
        int[] step = new int[8];

        output[0] = input[0];
        output[1] = input[4];
        output[2] = input[2];
        output[3] = input[6];
        output[4] = input[1];
        output[5] = input[5];
        output[6] = input[3];
        output[7] = input[7];

        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = halfBtf(COSPI[56], output[4], -COSPI[8], output[7]);
        step[5] = halfBtf(COSPI[24], output[5], -COSPI[40], output[6]);
        step[6] = halfBtf(COSPI[40], output[5], COSPI[24], output[6]);
        step[7] = halfBtf(COSPI[8], output[4], COSPI[56], output[7]);

        output[0] = halfBtf(COSPI[32], step[0], COSPI[32], step[1]);
        output[1] = halfBtf(COSPI[32], step[0], -COSPI[32], step[1]);
        output[2] = halfBtf(COSPI[48], step[2], -COSPI[16], step[3]);
        output[3] = halfBtf(COSPI[16], step[2], COSPI[48], step[3]);
        output[4] = clip((long) step[4] + step[5]);
        output[5] = clip((long) step[4] - step[5]);
        output[6] = clip(-(long) step[6] + step[7]);
        output[7] = clip((long) step[6] + step[7]);

        step[0] = clip((long) output[0] + output[3]);
        step[1] = clip((long) output[1] + output[2]);
        step[2] = clip((long) output[1] - output[2]);
        step[3] = clip((long) output[0] - output[3]);
        step[4] = output[4];
        step[5] = halfBtf(-COSPI[32], output[5], COSPI[32], output[6]);
        step[6] = halfBtf(COSPI[32], output[5], COSPI[32], output[6]);
        step[7] = output[7];

        output[0] = clip((long) step[0] + step[7]);
        output[1] = clip((long) step[1] + step[6]);
        output[2] = clip((long) step[2] + step[5]);
        output[3] = clip((long) step[3] + step[4]);
        output[4] = clip((long) step[3] - step[4]);
        output[5] = clip((long) step[2] - step[5]);
        output[6] = clip((long) step[1] - step[6]);
        output[7] = clip((long) step[0] - step[7]);
    }

    /// Reconstructs one one-dimensional `DCT_16` vector.
    ///
    /// This follows the libaom staged integer `av1_idct16` schedule and preserves the same
    /// clamp locations between half-butterfly stages.
    ///
    /// @param input the dequantized `DCT_16` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct16(int[] input, int[] output) {
        int[] step = new int[16];

        output[0] = input[0];
        output[1] = input[8];
        output[2] = input[4];
        output[3] = input[12];
        output[4] = input[2];
        output[5] = input[10];
        output[6] = input[6];
        output[7] = input[14];
        output[8] = input[1];
        output[9] = input[9];
        output[10] = input[5];
        output[11] = input[13];
        output[12] = input[3];
        output[13] = input[11];
        output[14] = input[7];
        output[15] = input[15];

        step[0] = output[0];
        step[1] = output[1];
        step[2] = output[2];
        step[3] = output[3];
        step[4] = output[4];
        step[5] = output[5];
        step[6] = output[6];
        step[7] = output[7];
        step[8] = halfBtf(COSPI[60], output[8], -COSPI[4], output[15]);
        step[9] = halfBtf(COSPI[28], output[9], -COSPI[36], output[14]);
        step[10] = halfBtf(COSPI[44], output[10], -COSPI[20], output[13]);
        step[11] = halfBtf(COSPI[12], output[11], -COSPI[52], output[12]);
        step[12] = halfBtf(COSPI[52], output[11], COSPI[12], output[12]);
        step[13] = halfBtf(COSPI[20], output[10], COSPI[44], output[13]);
        step[14] = halfBtf(COSPI[36], output[9], COSPI[28], output[14]);
        step[15] = halfBtf(COSPI[4], output[8], COSPI[60], output[15]);

        output[0] = step[0];
        output[1] = step[1];
        output[2] = step[2];
        output[3] = step[3];
        output[4] = halfBtf(COSPI[56], step[4], -COSPI[8], step[7]);
        output[5] = halfBtf(COSPI[24], step[5], -COSPI[40], step[6]);
        output[6] = halfBtf(COSPI[40], step[5], COSPI[24], step[6]);
        output[7] = halfBtf(COSPI[8], step[4], COSPI[56], step[7]);
        output[8] = clip((long) step[8] + step[9]);
        output[9] = clip((long) step[8] - step[9]);
        output[10] = clip(-(long) step[10] + step[11]);
        output[11] = clip((long) step[10] + step[11]);
        output[12] = clip((long) step[12] + step[13]);
        output[13] = clip((long) step[12] - step[13]);
        output[14] = clip(-(long) step[14] + step[15]);
        output[15] = clip((long) step[14] + step[15]);

        step[0] = halfBtf(COSPI[32], output[0], COSPI[32], output[1]);
        step[1] = halfBtf(COSPI[32], output[0], -COSPI[32], output[1]);
        step[2] = halfBtf(COSPI[48], output[2], -COSPI[16], output[3]);
        step[3] = halfBtf(COSPI[16], output[2], COSPI[48], output[3]);
        step[4] = clip((long) output[4] + output[5]);
        step[5] = clip((long) output[4] - output[5]);
        step[6] = clip(-(long) output[6] + output[7]);
        step[7] = clip((long) output[6] + output[7]);
        step[8] = output[8];
        step[9] = halfBtf(-COSPI[16], output[9], COSPI[48], output[14]);
        step[10] = halfBtf(-COSPI[48], output[10], -COSPI[16], output[13]);
        step[11] = output[11];
        step[12] = output[12];
        step[13] = halfBtf(-COSPI[16], output[10], COSPI[48], output[13]);
        step[14] = halfBtf(COSPI[48], output[9], COSPI[16], output[14]);
        step[15] = output[15];

        output[0] = clip((long) step[0] + step[3]);
        output[1] = clip((long) step[1] + step[2]);
        output[2] = clip((long) step[1] - step[2]);
        output[3] = clip((long) step[0] - step[3]);
        output[4] = step[4];
        output[5] = halfBtf(-COSPI[32], step[5], COSPI[32], step[6]);
        output[6] = halfBtf(COSPI[32], step[5], COSPI[32], step[6]);
        output[7] = step[7];
        output[8] = clip((long) step[8] + step[11]);
        output[9] = clip((long) step[9] + step[10]);
        output[10] = clip((long) step[9] - step[10]);
        output[11] = clip((long) step[8] - step[11]);
        output[12] = clip(-(long) step[12] + step[15]);
        output[13] = clip(-(long) step[13] + step[14]);
        output[14] = clip((long) step[13] + step[14]);
        output[15] = clip((long) step[12] + step[15]);

        step[0] = clip((long) output[0] + output[7]);
        step[1] = clip((long) output[1] + output[6]);
        step[2] = clip((long) output[2] + output[5]);
        step[3] = clip((long) output[3] + output[4]);
        step[4] = clip((long) output[3] - output[4]);
        step[5] = clip((long) output[2] - output[5]);
        step[6] = clip((long) output[1] - output[6]);
        step[7] = clip((long) output[0] - output[7]);
        step[8] = output[8];
        step[9] = output[9];
        step[10] = halfBtf(-COSPI[32], output[10], COSPI[32], output[13]);
        step[11] = halfBtf(-COSPI[32], output[11], COSPI[32], output[12]);
        step[12] = halfBtf(COSPI[32], output[11], COSPI[32], output[12]);
        step[13] = halfBtf(COSPI[32], output[10], COSPI[32], output[13]);
        step[14] = output[14];
        step[15] = output[15];

        output[0] = clip((long) step[0] + step[15]);
        output[1] = clip((long) step[1] + step[14]);
        output[2] = clip((long) step[2] + step[13]);
        output[3] = clip((long) step[3] + step[12]);
        output[4] = clip((long) step[4] + step[11]);
        output[5] = clip((long) step[5] + step[10]);
        output[6] = clip((long) step[6] + step[9]);
        output[7] = clip((long) step[7] + step[8]);
        output[8] = clip((long) step[7] - step[8]);
        output[9] = clip((long) step[6] - step[9]);
        output[10] = clip((long) step[5] - step[10]);
        output[11] = clip((long) step[4] - step[11]);
        output[12] = clip((long) step[3] - step[12]);
        output[13] = clip((long) step[2] - step[13]);
        output[14] = clip((long) step[1] - step[14]);
        output[15] = clip((long) step[0] - step[15]);
    }

    /// Reconstructs one one-dimensional `DCT_32` vector.
    ///
    /// This follows the libaom staged integer `av1_idct32` schedule and keeps the same
    /// half-butterfly rounding points. Stage-range checks from libaom are omitted because
    /// conforming streams already keep these intermediates within range.
    ///
    /// @param input the dequantized `DCT_32` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct32(int[] input, int[] output) {
        int[] step = new int[32];

        output[0] = input[0];
        output[1] = input[16];
        output[2] = input[8];
        output[3] = input[24];
        output[4] = input[4];
        output[5] = input[20];
        output[6] = input[12];
        output[7] = input[28];
        output[8] = input[2];
        output[9] = input[18];
        output[10] = input[10];
        output[11] = input[26];
        output[12] = input[6];
        output[13] = input[22];
        output[14] = input[14];
        output[15] = input[30];
        output[16] = input[1];
        output[17] = input[17];
        output[18] = input[9];
        output[19] = input[25];
        output[20] = input[5];
        output[21] = input[21];
        output[22] = input[13];
        output[23] = input[29];
        output[24] = input[3];
        output[25] = input[19];
        output[26] = input[11];
        output[27] = input[27];
        output[28] = input[7];
        output[29] = input[23];
        output[30] = input[15];
        output[31] = input[31];

        System.arraycopy(output, 0, step, 0, 16);
        step[16] = halfBtf(COSPI[62], output[16], -COSPI[2], output[31]);
        step[17] = halfBtf(COSPI[30], output[17], -COSPI[34], output[30]);
        step[18] = halfBtf(COSPI[46], output[18], -COSPI[18], output[29]);
        step[19] = halfBtf(COSPI[14], output[19], -COSPI[50], output[28]);
        step[20] = halfBtf(COSPI[54], output[20], -COSPI[10], output[27]);
        step[21] = halfBtf(COSPI[22], output[21], -COSPI[42], output[26]);
        step[22] = halfBtf(COSPI[38], output[22], -COSPI[26], output[25]);
        step[23] = halfBtf(COSPI[6], output[23], -COSPI[58], output[24]);
        step[24] = halfBtf(COSPI[58], output[23], COSPI[6], output[24]);
        step[25] = halfBtf(COSPI[26], output[22], COSPI[38], output[25]);
        step[26] = halfBtf(COSPI[42], output[21], COSPI[22], output[26]);
        step[27] = halfBtf(COSPI[10], output[20], COSPI[54], output[27]);
        step[28] = halfBtf(COSPI[50], output[19], COSPI[14], output[28]);
        step[29] = halfBtf(COSPI[18], output[18], COSPI[46], output[29]);
        step[30] = halfBtf(COSPI[34], output[17], COSPI[30], output[30]);
        step[31] = halfBtf(COSPI[2], output[16], COSPI[62], output[31]);

        System.arraycopy(step, 0, output, 0, 8);
        output[8] = halfBtf(COSPI[60], step[8], -COSPI[4], step[15]);
        output[9] = halfBtf(COSPI[28], step[9], -COSPI[36], step[14]);
        output[10] = halfBtf(COSPI[44], step[10], -COSPI[20], step[13]);
        output[11] = halfBtf(COSPI[12], step[11], -COSPI[52], step[12]);
        output[12] = halfBtf(COSPI[52], step[11], COSPI[12], step[12]);
        output[13] = halfBtf(COSPI[20], step[10], COSPI[44], step[13]);
        output[14] = halfBtf(COSPI[36], step[9], COSPI[28], step[14]);
        output[15] = halfBtf(COSPI[4], step[8], COSPI[60], step[15]);
        output[16] = clip((long) step[16] + step[17]);
        output[17] = clip((long) step[16] - step[17]);
        output[18] = clip(-(long) step[18] + step[19]);
        output[19] = clip((long) step[18] + step[19]);
        output[20] = clip((long) step[20] + step[21]);
        output[21] = clip((long) step[20] - step[21]);
        output[22] = clip(-(long) step[22] + step[23]);
        output[23] = clip((long) step[22] + step[23]);
        output[24] = clip((long) step[24] + step[25]);
        output[25] = clip((long) step[24] - step[25]);
        output[26] = clip(-(long) step[26] + step[27]);
        output[27] = clip((long) step[26] + step[27]);
        output[28] = clip((long) step[28] + step[29]);
        output[29] = clip((long) step[28] - step[29]);
        output[30] = clip(-(long) step[30] + step[31]);
        output[31] = clip((long) step[30] + step[31]);

        System.arraycopy(output, 0, step, 0, 4);
        step[4] = halfBtf(COSPI[56], output[4], -COSPI[8], output[7]);
        step[5] = halfBtf(COSPI[24], output[5], -COSPI[40], output[6]);
        step[6] = halfBtf(COSPI[40], output[5], COSPI[24], output[6]);
        step[7] = halfBtf(COSPI[8], output[4], COSPI[56], output[7]);
        step[8] = clip((long) output[8] + output[9]);
        step[9] = clip((long) output[8] - output[9]);
        step[10] = clip(-(long) output[10] + output[11]);
        step[11] = clip((long) output[10] + output[11]);
        step[12] = clip((long) output[12] + output[13]);
        step[13] = clip((long) output[12] - output[13]);
        step[14] = clip(-(long) output[14] + output[15]);
        step[15] = clip((long) output[14] + output[15]);
        step[16] = output[16];
        step[17] = halfBtf(-COSPI[8], output[17], COSPI[56], output[30]);
        step[18] = halfBtf(-COSPI[56], output[18], -COSPI[8], output[29]);
        step[19] = output[19];
        step[20] = output[20];
        step[21] = halfBtf(-COSPI[40], output[21], COSPI[24], output[26]);
        step[22] = halfBtf(-COSPI[24], output[22], -COSPI[40], output[25]);
        step[23] = output[23];
        step[24] = output[24];
        step[25] = halfBtf(-COSPI[40], output[22], COSPI[24], output[25]);
        step[26] = halfBtf(COSPI[24], output[21], COSPI[40], output[26]);
        step[27] = output[27];
        step[28] = output[28];
        step[29] = halfBtf(-COSPI[8], output[18], COSPI[56], output[29]);
        step[30] = halfBtf(COSPI[56], output[17], COSPI[8], output[30]);
        step[31] = output[31];

        output[0] = halfBtf(COSPI[32], step[0], COSPI[32], step[1]);
        output[1] = halfBtf(COSPI[32], step[0], -COSPI[32], step[1]);
        output[2] = halfBtf(COSPI[48], step[2], -COSPI[16], step[3]);
        output[3] = halfBtf(COSPI[16], step[2], COSPI[48], step[3]);
        output[4] = clip((long) step[4] + step[5]);
        output[5] = clip((long) step[4] - step[5]);
        output[6] = clip(-(long) step[6] + step[7]);
        output[7] = clip((long) step[6] + step[7]);
        output[8] = step[8];
        output[9] = halfBtf(-COSPI[16], step[9], COSPI[48], step[14]);
        output[10] = halfBtf(-COSPI[48], step[10], -COSPI[16], step[13]);
        output[11] = step[11];
        output[12] = step[12];
        output[13] = halfBtf(-COSPI[16], step[10], COSPI[48], step[13]);
        output[14] = halfBtf(COSPI[48], step[9], COSPI[16], step[14]);
        output[15] = step[15];
        output[16] = clip((long) step[16] + step[19]);
        output[17] = clip((long) step[17] + step[18]);
        output[18] = clip((long) step[17] - step[18]);
        output[19] = clip((long) step[16] - step[19]);
        output[20] = clip(-(long) step[20] + step[23]);
        output[21] = clip(-(long) step[21] + step[22]);
        output[22] = clip((long) step[21] + step[22]);
        output[23] = clip((long) step[20] + step[23]);
        output[24] = clip((long) step[24] + step[27]);
        output[25] = clip((long) step[25] + step[26]);
        output[26] = clip((long) step[25] - step[26]);
        output[27] = clip((long) step[24] - step[27]);
        output[28] = clip(-(long) step[28] + step[31]);
        output[29] = clip(-(long) step[29] + step[30]);
        output[30] = clip((long) step[29] + step[30]);
        output[31] = clip((long) step[28] + step[31]);

        step[0] = clip((long) output[0] + output[3]);
        step[1] = clip((long) output[1] + output[2]);
        step[2] = clip((long) output[1] - output[2]);
        step[3] = clip((long) output[0] - output[3]);
        step[4] = output[4];
        step[5] = halfBtf(-COSPI[32], output[5], COSPI[32], output[6]);
        step[6] = halfBtf(COSPI[32], output[5], COSPI[32], output[6]);
        step[7] = output[7];
        step[8] = clip((long) output[8] + output[11]);
        step[9] = clip((long) output[9] + output[10]);
        step[10] = clip((long) output[9] - output[10]);
        step[11] = clip((long) output[8] - output[11]);
        step[12] = clip(-(long) output[12] + output[15]);
        step[13] = clip(-(long) output[13] + output[14]);
        step[14] = clip((long) output[13] + output[14]);
        step[15] = clip((long) output[12] + output[15]);
        step[16] = output[16];
        step[17] = output[17];
        step[18] = halfBtf(-COSPI[16], output[18], COSPI[48], output[29]);
        step[19] = halfBtf(-COSPI[16], output[19], COSPI[48], output[28]);
        step[20] = halfBtf(-COSPI[48], output[20], -COSPI[16], output[27]);
        step[21] = halfBtf(-COSPI[48], output[21], -COSPI[16], output[26]);
        step[22] = output[22];
        step[23] = output[23];
        step[24] = output[24];
        step[25] = output[25];
        step[26] = halfBtf(-COSPI[16], output[21], COSPI[48], output[26]);
        step[27] = halfBtf(-COSPI[16], output[20], COSPI[48], output[27]);
        step[28] = halfBtf(COSPI[48], output[19], COSPI[16], output[28]);
        step[29] = halfBtf(COSPI[48], output[18], COSPI[16], output[29]);
        step[30] = output[30];
        step[31] = output[31];

        output[0] = clip((long) step[0] + step[7]);
        output[1] = clip((long) step[1] + step[6]);
        output[2] = clip((long) step[2] + step[5]);
        output[3] = clip((long) step[3] + step[4]);
        output[4] = clip((long) step[3] - step[4]);
        output[5] = clip((long) step[2] - step[5]);
        output[6] = clip((long) step[1] - step[6]);
        output[7] = clip((long) step[0] - step[7]);
        output[8] = step[8];
        output[9] = step[9];
        output[10] = halfBtf(-COSPI[32], step[10], COSPI[32], step[13]);
        output[11] = halfBtf(-COSPI[32], step[11], COSPI[32], step[12]);
        output[12] = halfBtf(COSPI[32], step[11], COSPI[32], step[12]);
        output[13] = halfBtf(COSPI[32], step[10], COSPI[32], step[13]);
        output[14] = step[14];
        output[15] = step[15];
        output[16] = clip((long) step[16] + step[23]);
        output[17] = clip((long) step[17] + step[22]);
        output[18] = clip((long) step[18] + step[21]);
        output[19] = clip((long) step[19] + step[20]);
        output[20] = clip((long) step[19] - step[20]);
        output[21] = clip((long) step[18] - step[21]);
        output[22] = clip((long) step[17] - step[22]);
        output[23] = clip((long) step[16] - step[23]);
        output[24] = clip(-(long) step[24] + step[31]);
        output[25] = clip(-(long) step[25] + step[30]);
        output[26] = clip(-(long) step[26] + step[29]);
        output[27] = clip(-(long) step[27] + step[28]);
        output[28] = clip((long) step[27] + step[28]);
        output[29] = clip((long) step[26] + step[29]);
        output[30] = clip((long) step[25] + step[30]);
        output[31] = clip((long) step[24] + step[31]);

        step[0] = clip((long) output[0] + output[15]);
        step[1] = clip((long) output[1] + output[14]);
        step[2] = clip((long) output[2] + output[13]);
        step[3] = clip((long) output[3] + output[12]);
        step[4] = clip((long) output[4] + output[11]);
        step[5] = clip((long) output[5] + output[10]);
        step[6] = clip((long) output[6] + output[9]);
        step[7] = clip((long) output[7] + output[8]);
        step[8] = clip((long) output[7] - output[8]);
        step[9] = clip((long) output[6] - output[9]);
        step[10] = clip((long) output[5] - output[10]);
        step[11] = clip((long) output[4] - output[11]);
        step[12] = clip((long) output[3] - output[12]);
        step[13] = clip((long) output[2] - output[13]);
        step[14] = clip((long) output[1] - output[14]);
        step[15] = clip((long) output[0] - output[15]);
        step[16] = output[16];
        step[17] = output[17];
        step[18] = output[18];
        step[19] = output[19];
        step[20] = halfBtf(-COSPI[32], output[20], COSPI[32], output[27]);
        step[21] = halfBtf(-COSPI[32], output[21], COSPI[32], output[26]);
        step[22] = halfBtf(-COSPI[32], output[22], COSPI[32], output[25]);
        step[23] = halfBtf(-COSPI[32], output[23], COSPI[32], output[24]);
        step[24] = halfBtf(COSPI[32], output[23], COSPI[32], output[24]);
        step[25] = halfBtf(COSPI[32], output[22], COSPI[32], output[25]);
        step[26] = halfBtf(COSPI[32], output[21], COSPI[32], output[26]);
        step[27] = halfBtf(COSPI[32], output[20], COSPI[32], output[27]);
        step[28] = output[28];
        step[29] = output[29];
        step[30] = output[30];
        step[31] = output[31];

        output[0] = clip((long) step[0] + step[31]);
        output[1] = clip((long) step[1] + step[30]);
        output[2] = clip((long) step[2] + step[29]);
        output[3] = clip((long) step[3] + step[28]);
        output[4] = clip((long) step[4] + step[27]);
        output[5] = clip((long) step[5] + step[26]);
        output[6] = clip((long) step[6] + step[25]);
        output[7] = clip((long) step[7] + step[24]);
        output[8] = clip((long) step[8] + step[23]);
        output[9] = clip((long) step[9] + step[22]);
        output[10] = clip((long) step[10] + step[21]);
        output[11] = clip((long) step[11] + step[20]);
        output[12] = clip((long) step[12] + step[19]);
        output[13] = clip((long) step[13] + step[18]);
        output[14] = clip((long) step[14] + step[17]);
        output[15] = clip((long) step[15] + step[16]);
        output[16] = clip((long) step[15] - step[16]);
        output[17] = clip((long) step[14] - step[17]);
        output[18] = clip((long) step[13] - step[18]);
        output[19] = clip((long) step[12] - step[19]);
        output[20] = clip((long) step[11] - step[20]);
        output[21] = clip((long) step[10] - step[21]);
        output[22] = clip((long) step[9] - step[22]);
        output[23] = clip((long) step[8] - step[23]);
        output[24] = clip((long) step[7] - step[24]);
        output[25] = clip((long) step[6] - step[25]);
        output[26] = clip((long) step[5] - step[26]);
        output[27] = clip((long) step[4] - step[27]);
        output[28] = clip((long) step[3] - step[28]);
        output[29] = clip((long) step[2] - step[29]);
        output[30] = clip((long) step[1] - step[30]);
        output[31] = clip((long) step[0] - step[31]);
    }

    /// Reconstructs one one-dimensional `DCT_32` vector using the scalar `dav1d` schedule.
    ///
    /// This keeps the same even/odd split and rounding points as `inv_dct32_1d_internal_c` for the
    /// non-`tx64` case. Matching that path avoids coefficient-dependent drift in large `DCT_DCT`
    /// residuals.
    ///
    /// @param input the dequantized `DCT_32` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct32Dav1d(int[] input, int[] output) {
        int[] evenInput = new int[16];
        int[] evenOutput = new int[16];
        for (int i = 0; i < 16; i++) {
            evenInput[i] = input[i << 1];
        }
        inverseDct16(evenInput, evenOutput);

        int in1 = input[1];
        int in3 = input[3];
        int in5 = input[5];
        int in7 = input[7];
        int in9 = input[9];
        int in11 = input[11];
        int in13 = input[13];
        int in15 = input[15];
        int in17 = input[17];
        int in19 = input[19];
        int in21 = input[21];
        int in23 = input[23];
        int in25 = input[25];
        int in27 = input[27];
        int in29 = input[29];
        int in31 = input[31];

        int t16a = positiveRoundShift((long) in1 * 201 - (long) in31 * (4091 - 4096), 12) - in31;
        int t17a = positiveRoundShift((long) in17 * (3035 - 4096) - (long) in15 * 2751, 12) + in17;
        int t18a = positiveRoundShift((long) in9 * 1751 - (long) in23 * (3703 - 4096), 12) - in23;
        int t19a = positiveRoundShift((long) in25 * (3857 - 4096) - (long) in7 * 1380, 12) + in25;
        int t20a = positiveRoundShift((long) in5 * 995 - (long) in27 * (3973 - 4096), 12) - in27;
        int t21a = positiveRoundShift((long) in21 * (3513 - 4096) - (long) in11 * 2106, 12) + in21;
        int t22a = positiveRoundShift((long) in13 * 1220 - (long) in19 * 1645, 11);
        int t23a = positiveRoundShift((long) in29 * (4052 - 4096) - (long) in3 * 601, 12) + in29;
        int t24a = positiveRoundShift((long) in29 * 601 + (long) in3 * (4052 - 4096), 12) + in3;
        int t25a = positiveRoundShift((long) in13 * 1645 + (long) in19 * 1220, 11);
        int t26a = positiveRoundShift((long) in21 * 2106 + (long) in11 * (3513 - 4096), 12) + in11;
        int t27a = positiveRoundShift((long) in5 * (3973 - 4096) + (long) in27 * 995, 12) + in5;
        int t28a = positiveRoundShift((long) in25 * 1380 + (long) in7 * (3857 - 4096), 12) + in7;
        int t29a = positiveRoundShift((long) in9 * (3703 - 4096) + (long) in23 * 1751, 12) + in9;
        int t30a = positiveRoundShift((long) in17 * 2751 + (long) in15 * (3035 - 4096), 12) + in15;
        int t31a = positiveRoundShift((long) in1 * (4091 - 4096) + (long) in31 * 201, 12) + in1;

        int t16 = clip((long) t16a + t17a);
        int t17 = clip((long) t16a - t17a);
        int t18 = clip((long) t19a - t18a);
        int t19 = clip((long) t19a + t18a);
        int t20 = clip((long) t20a + t21a);
        int t21 = clip((long) t20a - t21a);
        int t22 = clip((long) t23a - t22a);
        int t23 = clip((long) t23a + t22a);
        int t24 = clip((long) t24a + t25a);
        int t25 = clip((long) t24a - t25a);
        int t26 = clip((long) t27a - t26a);
        int t27 = clip((long) t27a + t26a);
        int t28 = clip((long) t28a + t29a);
        int t29 = clip((long) t28a - t29a);
        int t30 = clip((long) t31a - t30a);
        int t31 = clip((long) t31a + t30a);

        t17a = positiveRoundShift((long) t30 * 799 - (long) t17 * (4017 - 4096), 12) - t17;
        t30a = positiveRoundShift((long) t30 * (4017 - 4096) + (long) t17 * 799, 12) + t30;
        t18a = positiveRoundShift(-((long) t29 * (4017 - 4096) + (long) t18 * 799), 12) - t29;
        t29a = positiveRoundShift((long) t29 * 799 - (long) t18 * (4017 - 4096), 12) - t18;
        t21a = positiveRoundShift((long) t26 * 1703 - (long) t21 * 1138, 11);
        t26a = positiveRoundShift((long) t26 * 1138 + (long) t21 * 1703, 11);
        t22a = positiveRoundShift(-((long) t25 * 1138 + (long) t22 * 1703), 11);
        t25a = positiveRoundShift((long) t25 * 1703 - (long) t22 * 1138, 11);

        t16a = clip((long) t16 + t19);
        t17 = clip((long) t17a + t18a);
        t18 = clip((long) t17a - t18a);
        t19a = clip((long) t16 - t19);
        t20a = clip((long) t23 - t20);
        t21 = clip((long) t22a - t21a);
        t22 = clip((long) t22a + t21a);
        t23a = clip((long) t23 + t20);
        t24a = clip((long) t24 + t27);
        t25 = clip((long) t25a + t26a);
        t26 = clip((long) t25a - t26a);
        t27a = clip((long) t24 - t27);
        t28a = clip((long) t31 - t28);
        t29 = clip((long) t30a - t29a);
        t30 = clip((long) t30a + t29a);
        t31a = clip((long) t31 + t28);

        t18a = positiveRoundShift((long) t29 * 1567 - (long) t18 * (3784 - 4096), 12) - t18;
        t29a = positiveRoundShift((long) t29 * (3784 - 4096) + (long) t18 * 1567, 12) + t29;
        t19 = positiveRoundShift((long) t28a * 1567 - (long) t19a * (3784 - 4096), 12) - t19a;
        t28 = positiveRoundShift((long) t28a * (3784 - 4096) + (long) t19a * 1567, 12) + t28a;
        t20 = positiveRoundShift(-((long) t27a * (3784 - 4096) + (long) t20a * 1567), 12) - t27a;
        t27 = positiveRoundShift((long) t27a * 1567 - (long) t20a * (3784 - 4096), 12) - t20a;
        t21a = positiveRoundShift(-((long) t26 * (3784 - 4096) + (long) t21 * 1567), 12) - t26;
        t26a = positiveRoundShift((long) t26 * 1567 - (long) t21 * (3784 - 4096), 12) - t21;

        t16 = clip((long) t16a + t23a);
        t17a = clip((long) t17 + t22);
        t18 = clip((long) t18a + t21a);
        t19a = clip((long) t19 + t20);
        t20a = clip((long) t19 - t20);
        t21 = clip((long) t18a - t21a);
        t22a = clip((long) t17 - t22);
        t23 = clip((long) t16a - t23a);
        t24 = clip((long) t31a - t24a);
        t25a = clip((long) t30 - t25);
        t26 = clip((long) t29a - t26a);
        t27a = clip((long) t28 - t27);
        t28a = clip((long) t28 + t27);
        t29 = clip((long) t29a + t26a);
        t30a = clip((long) t30 + t25);
        t31 = clip((long) t31a + t24a);

        t20 = positiveRoundShift((long) (t27a - t20a) * 181, 8);
        t27 = positiveRoundShift((long) (t27a + t20a) * 181, 8);
        t21a = positiveRoundShift((long) (t26 - t21) * 181, 8);
        t26a = positiveRoundShift((long) (t26 + t21) * 181, 8);
        t22 = positiveRoundShift((long) (t25a - t22a) * 181, 8);
        t25 = positiveRoundShift((long) (t25a + t22a) * 181, 8);
        t23a = positiveRoundShift((long) (t24 - t23) * 181, 8);
        t24a = positiveRoundShift((long) (t24 + t23) * 181, 8);

        output[0] = clip((long) evenOutput[0] + t31);
        output[1] = clip((long) evenOutput[1] + t30a);
        output[2] = clip((long) evenOutput[2] + t29);
        output[3] = clip((long) evenOutput[3] + t28a);
        output[4] = clip((long) evenOutput[4] + t27);
        output[5] = clip((long) evenOutput[5] + t26a);
        output[6] = clip((long) evenOutput[6] + t25);
        output[7] = clip((long) evenOutput[7] + t24a);
        output[8] = clip((long) evenOutput[8] + t23a);
        output[9] = clip((long) evenOutput[9] + t22);
        output[10] = clip((long) evenOutput[10] + t21a);
        output[11] = clip((long) evenOutput[11] + t20);
        output[12] = clip((long) evenOutput[12] + t19a);
        output[13] = clip((long) evenOutput[13] + t18);
        output[14] = clip((long) evenOutput[14] + t17a);
        output[15] = clip((long) evenOutput[15] + t16);
        output[16] = clip((long) evenOutput[15] - t16);
        output[17] = clip((long) evenOutput[14] - t17a);
        output[18] = clip((long) evenOutput[13] - t18);
        output[19] = clip((long) evenOutput[12] - t19a);
        output[20] = clip((long) evenOutput[11] - t20);
        output[21] = clip((long) evenOutput[10] - t21a);
        output[22] = clip((long) evenOutput[9] - t22);
        output[23] = clip((long) evenOutput[8] - t23a);
        output[24] = clip((long) evenOutput[7] - t24a);
        output[25] = clip((long) evenOutput[6] - t25);
        output[26] = clip((long) evenOutput[5] - t26a);
        output[27] = clip((long) evenOutput[4] - t27);
        output[28] = clip((long) evenOutput[3] - t28a);
        output[29] = clip((long) evenOutput[2] - t29);
        output[30] = clip((long) evenOutput[1] - t30a);
        output[31] = clip((long) evenOutput[0] - t31);
    }

    /// Reconstructs one one-dimensional `DCT_64` vector using the scalar `dav1d` `tx64` schedule.
    ///
    /// The `64`-point inverse DCT reuses the dedicated `tx64` odd-stage kernels all the way down to
    /// the `4`-point subtree. Matching that chain removes the residual drift that remained when this
    /// decoder approximated `tx64` through orthonormal synthesis.
    ///
    /// @param input the dequantized `DCT_64` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct64Dav1d(int[] input, int[] output) {
        System.arraycopy(input, 0, output, 0, 64);
        inverseDct32Dav1dTx64InPlace(output, 2);

        int in1 = output[1];
        int in3 = output[3];
        int in5 = output[5];
        int in7 = output[7];
        int in9 = output[9];
        int in11 = output[11];
        int in13 = output[13];
        int in15 = output[15];
        int in17 = output[17];
        int in19 = output[19];
        int in21 = output[21];
        int in23 = output[23];
        int in25 = output[25];
        int in27 = output[27];
        int in29 = output[29];
        int in31 = output[31];

        int t32a = positiveRoundShift((long) in1 * 101, 12);
        int t33a = positiveRoundShift((long) in31 * -2824, 12);
        int t34a = positiveRoundShift((long) in17 * 1660, 12);
        int t35a = positiveRoundShift((long) in15 * -1474, 12);
        int t36a = positiveRoundShift((long) in9 * 897, 12);
        int t37a = positiveRoundShift((long) in23 * -2191, 12);
        int t38a = positiveRoundShift((long) in25 * 2359, 12);
        int t39a = positiveRoundShift((long) in7 * -700, 12);
        int t40a = positiveRoundShift((long) in5 * 501, 12);
        int t41a = positiveRoundShift((long) in27 * -2520, 12);
        int t42a = positiveRoundShift((long) in21 * 2019, 12);
        int t43a = positiveRoundShift((long) in11 * -1092, 12);
        int t44a = positiveRoundShift((long) in13 * 1285, 12);
        int t45a = positiveRoundShift((long) in19 * -1842, 12);
        int t46a = positiveRoundShift((long) in29 * 2675, 12);
        int t47a = positiveRoundShift((long) in3 * -301, 12);
        int t48a = positiveRoundShift((long) in3 * 4085, 12);
        int t49a = positiveRoundShift((long) in29 * 3102, 12);
        int t50a = positiveRoundShift((long) in19 * 3659, 12);
        int t51a = positiveRoundShift((long) in13 * 3889, 12);
        int t52a = positiveRoundShift((long) in11 * 3948, 12);
        int t53a = positiveRoundShift((long) in21 * 3564, 12);
        int t54a = positiveRoundShift((long) in27 * 3229, 12);
        int t55a = positiveRoundShift((long) in5 * 4065, 12);
        int t56a = positiveRoundShift((long) in7 * 4036, 12);
        int t57a = positiveRoundShift((long) in25 * 3349, 12);
        int t58a = positiveRoundShift((long) in23 * 3461, 12);
        int t59a = positiveRoundShift((long) in9 * 3996, 12);
        int t60a = positiveRoundShift((long) in15 * 3822, 12);
        int t61a = positiveRoundShift((long) in17 * 3745, 12);
        int t62a = positiveRoundShift((long) in31 * 2967, 12);
        int t63a = positiveRoundShift((long) in1 * 4095, 12);

        int t32 = clip((long) t32a + t33a);
        int t33 = clip((long) t32a - t33a);
        int t34 = clip((long) t35a - t34a);
        int t35 = clip((long) t35a + t34a);
        int t36 = clip((long) t36a + t37a);
        int t37 = clip((long) t36a - t37a);
        int t38 = clip((long) t39a - t38a);
        int t39 = clip((long) t39a + t38a);
        int t40 = clip((long) t40a + t41a);
        int t41 = clip((long) t40a - t41a);
        int t42 = clip((long) t43a - t42a);
        int t43 = clip((long) t43a + t42a);
        int t44 = clip((long) t44a + t45a);
        int t45 = clip((long) t44a - t45a);
        int t46 = clip((long) t47a - t46a);
        int t47 = clip((long) t47a + t46a);
        int t48 = clip((long) t48a + t49a);
        int t49 = clip((long) t48a - t49a);
        int t50 = clip((long) t51a - t50a);
        int t51 = clip((long) t51a + t50a);
        int t52 = clip((long) t52a + t53a);
        int t53 = clip((long) t52a - t53a);
        int t54 = clip((long) t55a - t54a);
        int t55 = clip((long) t55a + t54a);
        int t56 = clip((long) t56a + t57a);
        int t57 = clip((long) t56a - t57a);
        int t58 = clip((long) t59a - t58a);
        int t59 = clip((long) t59a + t58a);
        int t60 = clip((long) t60a + t61a);
        int t61 = clip((long) t60a - t61a);
        int t62 = clip((long) t63a - t62a);
        int t63 = clip((long) t63a + t62a);

        t33a = positiveRoundShift((long) t33 * (4096 - 4076) + (long) t62 * 401, 12) - t33;
        t34a = positiveRoundShift((long) t34 * -401 + (long) t61 * (4096 - 4076), 12) - t61;
        t37a = positiveRoundShift((long) t37 * -1299 + (long) t58 * 1583, 11);
        t38a = positiveRoundShift((long) t38 * -1583 + (long) t57 * -1299, 11);
        t41a = positiveRoundShift((long) t41 * (4096 - 3612) + (long) t54 * 1931, 12) - t41;
        t42a = positiveRoundShift((long) t42 * -1931 + (long) t53 * (4096 - 3612), 12) - t53;
        t45a = positiveRoundShift((long) t45 * -1189 + (long) t50 * (3920 - 4096), 12) + t50;
        t46a = positiveRoundShift((long) t46 * (4096 - 3920) + (long) t49 * -1189, 12) - t46;
        t49a = positiveRoundShift((long) t46 * -1189 + (long) t49 * (3920 - 4096), 12) + t49;
        t50a = positiveRoundShift((long) t45 * (3920 - 4096) + (long) t50 * 1189, 12) + t45;
        t53a = positiveRoundShift((long) t42 * (4096 - 3612) + (long) t53 * 1931, 12) - t42;
        t54a = positiveRoundShift((long) t41 * 1931 + (long) t54 * (3612 - 4096), 12) + t54;
        t57a = positiveRoundShift((long) t38 * -1299 + (long) t57 * 1583, 11);
        t58a = positiveRoundShift((long) t37 * 1583 + (long) t58 * 1299, 11);
        t61a = positiveRoundShift((long) t34 * (4096 - 4076) + (long) t61 * 401, 12) - t34;
        t62a = positiveRoundShift((long) t33 * 401 + (long) t62 * (4076 - 4096), 12) + t62;

        t32a = clip((long) t32 + t35);
        t33 = clip((long) t33a + t34a);
        t34 = clip((long) t33a - t34a);
        t35a = clip((long) t32 - t35);
        t36a = clip((long) t39 - t36);
        t37 = clip((long) t38a - t37a);
        t38 = clip((long) t38a + t37a);
        t39a = clip((long) t39 + t36);
        t40a = clip((long) t40 + t43);
        t41 = clip((long) t41a + t42a);
        t42 = clip((long) t41a - t42a);
        t43a = clip((long) t40 - t43);
        t44a = clip((long) t47 - t44);
        t45 = clip((long) t46a - t45a);
        t46 = clip((long) t46a + t45a);
        t47a = clip((long) t47 + t44);
        t48a = clip((long) t48 + t51);
        t49 = clip((long) t49a + t50a);
        t50 = clip((long) t49a - t50a);
        t51 = clip((long) t48 - t51);
        t52 = clip((long) t55 - t52);
        t53 = clip((long) t54a - t53a);
        t54 = clip((long) t54a + t53a);
        t55a = clip((long) t55 + t52);
        int t56b = clip((long) t56 + t59);
        t57 = clip((long) t57a + t58a);
        t58 = clip((long) t57a - t58a);
        int t59b = clip((long) t56 - t59);
        int t60b = clip((long) t63 - t60);
        t61 = clip((long) t62a - t61a);
        t62 = clip((long) t62a + t61a);
        t63a = clip((long) t63 + t60);

        t34a = positiveRoundShift((long) t34 * (4096 - 4017) + (long) t61 * 799, 12) - t34;
        t35 = positiveRoundShift((long) t35a * (4096 - 4017) + (long) t60b * 799, 12) - t35a;
        t36 = positiveRoundShift((long) t36a * -799 + (long) t59b * (4096 - 4017), 12) - t59b;
        t37a = positiveRoundShift((long) t37 * -799 + (long) t58 * (4096 - 4017), 12) - t58;
        t42a = positiveRoundShift((long) t42 * -1138 + (long) t53 * 1703, 11);
        t43 = positiveRoundShift((long) t43a * -1138 + (long) t52 * 1703, 11);
        t44 = positiveRoundShift((long) t44a * -1703 + (long) t51 * -1138, 11);
        t45a = positiveRoundShift((long) t45 * -1703 + (long) t50 * -1138, 11);
        t50a = positiveRoundShift((long) t45 * -1138 + (long) t50 * 1703, 11);
        t51 = positiveRoundShift((long) t44a * -1138 + (long) t51 * 1703, 11);
        t52 = positiveRoundShift((long) t43a * 1703 + (long) t52 * 1138, 11);
        t53a = positiveRoundShift((long) t42 * 1703 + (long) t53 * 1138, 11);
        t58a = positiveRoundShift((long) t37 * (4096 - 4017) + (long) t58 * 799, 12) - t37;
        t59 = positiveRoundShift((long) t36a * (4096 - 4017) + (long) t59b * 799, 12) - t36a;
        t60 = positiveRoundShift((long) t35a * 799 + (long) t60b * (4017 - 4096), 12) + t60b;
        t61a = positiveRoundShift((long) t34 * 799 + (long) t61 * (4017 - 4096), 12) + t61;

        t32 = clip((long) t32a + t39a);
        t33a = clip((long) t33 + t38);
        t34 = clip((long) t34a + t37a);
        t35a = clip((long) t35 + t36);
        t36a = clip((long) t35 - t36);
        t37 = clip((long) t34a - t37a);
        t38a = clip((long) t33 - t38);
        t39 = clip((long) t32a - t39a);
        t40 = clip((long) t47a - t40a);
        t41a = clip((long) t46 - t41);
        t42 = clip((long) t45a - t42a);
        t43a = clip((long) t44 - t43);
        t44a = clip((long) t44 + t43);
        t45 = clip((long) t45a + t42a);
        t46a = clip((long) t46 + t41);
        t47 = clip((long) t47a + t40a);
        t48 = clip((long) t48a + t55a);
        t49a = clip((long) t49 + t54);
        t50 = clip((long) t50a + t53a);
        int t51b = clip((long) t51 + t52);
        int t52b = clip((long) t51 - t52);
        t53 = clip((long) t50a - t53a);
        t54a = clip((long) t49 - t54);
        t55 = clip((long) t48a - t55a);
        t56 = clip((long) t63a - t56b);
        t57a = clip((long) t62 - t57);
        t58 = clip((long) t61a - t58a);
        t59a = clip((long) t60 - t59);
        t60a = clip((long) t60 + t59);
        t61 = clip((long) t61a + t58a);
        t62a = clip((long) t62 + t57);
        t63 = clip((long) t63a + t56b);

        t36 = positiveRoundShift((long) t36a * (4096 - 3784) + (long) t59a * 1567, 12) - t36a;
        t37a = positiveRoundShift((long) t37 * (4096 - 3784) + (long) t58 * 1567, 12) - t37;
        t38 = positiveRoundShift((long) t38a * (4096 - 3784) + (long) t57a * 1567, 12) - t38a;
        t39a = positiveRoundShift((long) t39 * (4096 - 3784) + (long) t56 * 1567, 12) - t39;
        t40a = positiveRoundShift((long) t40 * -1567 + (long) t55 * (4096 - 3784), 12) - t55;
        t41 = positiveRoundShift((long) t41a * -1567 + (long) t54a * (4096 - 3784), 12) - t54a;
        t42a = positiveRoundShift((long) t42 * -1567 + (long) t53 * (4096 - 3784), 12) - t53;
        t43 = positiveRoundShift((long) t43a * -1567 + (long) t52b * (4096 - 3784), 12) - t52b;
        int t52c = positiveRoundShift((long) t43a * (4096 - 3784) + (long) t52b * 1567, 12) - t43a;
        t53a = positiveRoundShift((long) t42 * (4096 - 3784) + (long) t53 * 1567, 12) - t42;
        t54 = positiveRoundShift((long) t41a * (4096 - 3784) + (long) t54a * 1567, 12) - t41a;
        t55a = positiveRoundShift((long) t40 * (4096 - 3784) + (long) t55 * 1567, 12) - t40;
        int t56c = positiveRoundShift((long) t39 * 1567 + (long) t56 * (3784 - 4096), 12) + t56;
        t57 = positiveRoundShift((long) t38a * 1567 + (long) t57a * (3784 - 4096), 12) + t57a;
        t58a = positiveRoundShift((long) t37 * 1567 + (long) t58 * (3784 - 4096), 12) + t58;
        t59 = positiveRoundShift((long) t36a * 1567 + (long) t59a * (3784 - 4096), 12) + t59a;

        t32a = clip((long) t32 + t47);
        t33 = clip((long) t33a + t46a);
        t34a = clip((long) t34 + t45);
        t35 = clip((long) t35a + t44a);
        t36a = clip((long) t36 + t43);
        t37 = clip((long) t37a + t42a);
        t38a = clip((long) t38 + t41);
        t39 = clip((long) t39a + t40a);
        t40 = clip((long) t39a - t40a);
        t41a = clip((long) t38 - t41);
        t42 = clip((long) t37a - t42a);
        t43a = clip((long) t36 - t43);
        t44 = clip((long) t35a - t44a);
        t45a = clip((long) t34 - t45);
        t46 = clip((long) t33a - t46a);
        t47a = clip((long) t32 - t47);
        t48a = clip((long) t63 - t48);
        t49 = clip((long) t62a - t49a);
        t50a = clip((long) t61 - t50);
        int t51c = clip((long) t60a - t51b);
        int t52d = clip((long) t59 - t52c);
        t53 = clip((long) t58a - t53a);
        t54a = clip((long) t57 - t54);
        t55 = clip((long) t56c - t55a);
        int t56d = clip((long) t56c + t55a);
        t57a = clip((long) t57 + t54);
        t58 = clip((long) t58a + t53a);
        t59a = clip((long) t59 + t52c);
        t60 = clip((long) t60a + t51b);
        t61a = clip((long) t61 + t50);
        t62 = clip((long) t62a + t49a);
        t63a = clip((long) t63 + t48);

        t40a = positiveRoundShift((long) (t55 - t40) * 181, 8);
        t41 = positiveRoundShift((long) (t54a - t41a) * 181, 8);
        t42a = positiveRoundShift((long) (t53 - t42) * 181, 8);
        t43 = positiveRoundShift((long) (t52d - t43a) * 181, 8);
        t44a = positiveRoundShift((long) (t51c - t44) * 181, 8);
        t45 = positiveRoundShift((long) (t50a - t45a) * 181, 8);
        t46a = positiveRoundShift((long) (t49 - t46) * 181, 8);
        t47 = positiveRoundShift((long) (t48a - t47a) * 181, 8);
        t48 = positiveRoundShift((long) (t47a + t48a) * 181, 8);
        t49a = positiveRoundShift((long) (t46 + t49) * 181, 8);
        t50 = positiveRoundShift((long) (t45a + t50a) * 181, 8);
        int t51d = positiveRoundShift((long) (t44 + t51c) * 181, 8);
        int t52e = positiveRoundShift((long) (t43a + t52d) * 181, 8);
        t53a = positiveRoundShift((long) (t42 + t53) * 181, 8);
        t54 = positiveRoundShift((long) (t41a + t54a) * 181, 8);
        t55a = positiveRoundShift((long) (t40 + t55) * 181, 8);

        int t0 = output[0];
        int t1 = output[2];
        int t2 = output[4];
        int t3 = output[6];
        int t4 = output[8];
        int t5 = output[10];
        int t6 = output[12];
        int t7 = output[14];
        int t8 = output[16];
        int t9 = output[18];
        int t10 = output[20];
        int t11 = output[22];
        int t12 = output[24];
        int t13 = output[26];
        int t14 = output[28];
        int t15 = output[30];
        int t16 = output[32];
        int t17 = output[34];
        int t18 = output[36];
        int t19 = output[38];
        int t20 = output[40];
        int t21 = output[42];
        int t22 = output[44];
        int t23 = output[46];
        int t24 = output[48];
        int t25 = output[50];
        int t26 = output[52];
        int t27 = output[54];
        int t28 = output[56];
        int t29 = output[58];
        int t30 = output[60];
        int t31 = output[62];

        output[0] = clip((long) t0 + t63a);
        output[1] = clip((long) t1 + t62);
        output[2] = clip((long) t2 + t61a);
        output[3] = clip((long) t3 + t60);
        output[4] = clip((long) t4 + t59a);
        output[5] = clip((long) t5 + t58);
        output[6] = clip((long) t6 + t57a);
        output[7] = clip((long) t7 + t56d);
        output[8] = clip((long) t8 + t55a);
        output[9] = clip((long) t9 + t54);
        output[10] = clip((long) t10 + t53a);
        output[11] = clip((long) t11 + t52e);
        output[12] = clip((long) t12 + t51d);
        output[13] = clip((long) t13 + t50);
        output[14] = clip((long) t14 + t49a);
        output[15] = clip((long) t15 + t48);
        output[16] = clip((long) t16 + t47);
        output[17] = clip((long) t17 + t46a);
        output[18] = clip((long) t18 + t45);
        output[19] = clip((long) t19 + t44a);
        output[20] = clip((long) t20 + t43);
        output[21] = clip((long) t21 + t42a);
        output[22] = clip((long) t22 + t41);
        output[23] = clip((long) t23 + t40a);
        output[24] = clip((long) t24 + t39);
        output[25] = clip((long) t25 + t38a);
        output[26] = clip((long) t26 + t37);
        output[27] = clip((long) t27 + t36a);
        output[28] = clip((long) t28 + t35);
        output[29] = clip((long) t29 + t34a);
        output[30] = clip((long) t30 + t33);
        output[31] = clip((long) t31 + t32a);
        output[32] = clip((long) t31 - t32a);
        output[33] = clip((long) t30 - t33);
        output[34] = clip((long) t29 - t34a);
        output[35] = clip((long) t28 - t35);
        output[36] = clip((long) t27 - t36a);
        output[37] = clip((long) t26 - t37);
        output[38] = clip((long) t25 - t38a);
        output[39] = clip((long) t24 - t39);
        output[40] = clip((long) t23 - t40a);
        output[41] = clip((long) t22 - t41);
        output[42] = clip((long) t21 - t42a);
        output[43] = clip((long) t20 - t43);
        output[44] = clip((long) t19 - t44a);
        output[45] = clip((long) t18 - t45);
        output[46] = clip((long) t17 - t46a);
        output[47] = clip((long) t16 - t47);
        output[48] = clip((long) t15 - t48);
        output[49] = clip((long) t14 - t49a);
        output[50] = clip((long) t13 - t50);
        output[51] = clip((long) t12 - t51d);
        output[52] = clip((long) t11 - t52e);
        output[53] = clip((long) t10 - t53a);
        output[54] = clip((long) t9 - t54);
        output[55] = clip((long) t8 - t55a);
        output[56] = clip((long) t7 - t56d);
        output[57] = clip((long) t6 - t57a);
        output[58] = clip((long) t5 - t58);
        output[59] = clip((long) t4 - t59a);
        output[60] = clip((long) t3 - t60);
        output[61] = clip((long) t2 - t61a);
        output[62] = clip((long) t1 - t62);
        output[63] = clip((long) t0 - t63a);
    }

    /// Reconstructs one one-dimensional `DCT_64` vector with the staged AOM reference schedule.
    ///
    /// This mirrors `av1_idct64()` stage-for-stage so the `TX_64X64` path matches the FFmpeg and
    /// libaom residual reconstruction pipeline instead of relying on the earlier incomplete
    /// odd-branch port.
    ///
    /// @param input the dequantized `DCT_64` input vector
    /// @param output the reconstructed output vector
    private static void inverseDct64Aom(int[] input, int[] output) {
        int[] step = new int[64];
        int[] bf0;
        int[] bf1;

        bf1 = output;
        bf1[0] = input[0];
        bf1[1] = input[32];
        bf1[2] = input[16];
        bf1[3] = input[48];
        bf1[4] = input[8];
        bf1[5] = input[40];
        bf1[6] = input[24];
        bf1[7] = input[56];
        bf1[8] = input[4];
        bf1[9] = input[36];
        bf1[10] = input[20];
        bf1[11] = input[52];
        bf1[12] = input[12];
        bf1[13] = input[44];
        bf1[14] = input[28];
        bf1[15] = input[60];
        bf1[16] = input[2];
        bf1[17] = input[34];
        bf1[18] = input[18];
        bf1[19] = input[50];
        bf1[20] = input[10];
        bf1[21] = input[42];
        bf1[22] = input[26];
        bf1[23] = input[58];
        bf1[24] = input[6];
        bf1[25] = input[38];
        bf1[26] = input[22];
        bf1[27] = input[54];
        bf1[28] = input[14];
        bf1[29] = input[46];
        bf1[30] = input[30];
        bf1[31] = input[62];
        bf1[32] = input[1];
        bf1[33] = input[33];
        bf1[34] = input[17];
        bf1[35] = input[49];
        bf1[36] = input[9];
        bf1[37] = input[41];
        bf1[38] = input[25];
        bf1[39] = input[57];
        bf1[40] = input[5];
        bf1[41] = input[37];
        bf1[42] = input[21];
        bf1[43] = input[53];
        bf1[44] = input[13];
        bf1[45] = input[45];
        bf1[46] = input[29];
        bf1[47] = input[61];
        bf1[48] = input[3];
        bf1[49] = input[35];
        bf1[50] = input[19];
        bf1[51] = input[51];
        bf1[52] = input[11];
        bf1[53] = input[43];
        bf1[54] = input[27];
        bf1[55] = input[59];
        bf1[56] = input[7];
        bf1[57] = input[39];
        bf1[58] = input[23];
        bf1[59] = input[55];
        bf1[60] = input[15];
        bf1[61] = input[47];
        bf1[62] = input[31];
        bf1[63] = input[63];

        bf0 = output;
        bf1 = step;
        System.arraycopy(bf0, 0, bf1, 0, 32);
        bf1[32] = halfBtf(COSPI[63], bf0[32], -COSPI[1], bf0[63]);
        bf1[33] = halfBtf(COSPI[31], bf0[33], -COSPI[33], bf0[62]);
        bf1[34] = halfBtf(COSPI[47], bf0[34], -COSPI[17], bf0[61]);
        bf1[35] = halfBtf(COSPI[15], bf0[35], -COSPI[49], bf0[60]);
        bf1[36] = halfBtf(COSPI[55], bf0[36], -COSPI[9], bf0[59]);
        bf1[37] = halfBtf(COSPI[23], bf0[37], -COSPI[41], bf0[58]);
        bf1[38] = halfBtf(COSPI[39], bf0[38], -COSPI[25], bf0[57]);
        bf1[39] = halfBtf(COSPI[7], bf0[39], -COSPI[57], bf0[56]);
        bf1[40] = halfBtf(COSPI[59], bf0[40], -COSPI[5], bf0[55]);
        bf1[41] = halfBtf(COSPI[27], bf0[41], -COSPI[37], bf0[54]);
        bf1[42] = halfBtf(COSPI[43], bf0[42], -COSPI[21], bf0[53]);
        bf1[43] = halfBtf(COSPI[11], bf0[43], -COSPI[53], bf0[52]);
        bf1[44] = halfBtf(COSPI[51], bf0[44], -COSPI[13], bf0[51]);
        bf1[45] = halfBtf(COSPI[19], bf0[45], -COSPI[45], bf0[50]);
        bf1[46] = halfBtf(COSPI[35], bf0[46], -COSPI[29], bf0[49]);
        bf1[47] = halfBtf(COSPI[3], bf0[47], -COSPI[61], bf0[48]);
        bf1[48] = halfBtf(COSPI[61], bf0[47], COSPI[3], bf0[48]);
        bf1[49] = halfBtf(COSPI[29], bf0[46], COSPI[35], bf0[49]);
        bf1[50] = halfBtf(COSPI[45], bf0[45], COSPI[19], bf0[50]);
        bf1[51] = halfBtf(COSPI[13], bf0[44], COSPI[51], bf0[51]);
        bf1[52] = halfBtf(COSPI[53], bf0[43], COSPI[11], bf0[52]);
        bf1[53] = halfBtf(COSPI[21], bf0[42], COSPI[43], bf0[53]);
        bf1[54] = halfBtf(COSPI[37], bf0[41], COSPI[27], bf0[54]);
        bf1[55] = halfBtf(COSPI[5], bf0[40], COSPI[59], bf0[55]);
        bf1[56] = halfBtf(COSPI[57], bf0[39], COSPI[7], bf0[56]);
        bf1[57] = halfBtf(COSPI[25], bf0[38], COSPI[39], bf0[57]);
        bf1[58] = halfBtf(COSPI[41], bf0[37], COSPI[23], bf0[58]);
        bf1[59] = halfBtf(COSPI[9], bf0[36], COSPI[55], bf0[59]);
        bf1[60] = halfBtf(COSPI[49], bf0[35], COSPI[15], bf0[60]);
        bf1[61] = halfBtf(COSPI[17], bf0[34], COSPI[47], bf0[61]);
        bf1[62] = halfBtf(COSPI[33], bf0[33], COSPI[31], bf0[62]);
        bf1[63] = halfBtf(COSPI[1], bf0[32], COSPI[63], bf0[63]);

        bf0 = step;
        bf1 = output;
        System.arraycopy(bf0, 0, bf1, 0, 16);
        bf1[16] = halfBtf(COSPI[62], bf0[16], -COSPI[2], bf0[31]);
        bf1[17] = halfBtf(COSPI[30], bf0[17], -COSPI[34], bf0[30]);
        bf1[18] = halfBtf(COSPI[46], bf0[18], -COSPI[18], bf0[29]);
        bf1[19] = halfBtf(COSPI[14], bf0[19], -COSPI[50], bf0[28]);
        bf1[20] = halfBtf(COSPI[54], bf0[20], -COSPI[10], bf0[27]);
        bf1[21] = halfBtf(COSPI[22], bf0[21], -COSPI[42], bf0[26]);
        bf1[22] = halfBtf(COSPI[38], bf0[22], -COSPI[26], bf0[25]);
        bf1[23] = halfBtf(COSPI[6], bf0[23], -COSPI[58], bf0[24]);
        bf1[24] = halfBtf(COSPI[58], bf0[23], COSPI[6], bf0[24]);
        bf1[25] = halfBtf(COSPI[26], bf0[22], COSPI[38], bf0[25]);
        bf1[26] = halfBtf(COSPI[42], bf0[21], COSPI[22], bf0[26]);
        bf1[27] = halfBtf(COSPI[10], bf0[20], COSPI[54], bf0[27]);
        bf1[28] = halfBtf(COSPI[50], bf0[19], COSPI[14], bf0[28]);
        bf1[29] = halfBtf(COSPI[18], bf0[18], COSPI[46], bf0[29]);
        bf1[30] = halfBtf(COSPI[34], bf0[17], COSPI[30], bf0[30]);
        bf1[31] = halfBtf(COSPI[2], bf0[16], COSPI[62], bf0[31]);
        bf1[32] = clip((long) bf0[32] + bf0[33]);
        bf1[33] = clip((long) bf0[32] - bf0[33]);
        bf1[34] = clip(-(long) bf0[34] + bf0[35]);
        bf1[35] = clip((long) bf0[34] + bf0[35]);
        bf1[36] = clip((long) bf0[36] + bf0[37]);
        bf1[37] = clip((long) bf0[36] - bf0[37]);
        bf1[38] = clip(-(long) bf0[38] + bf0[39]);
        bf1[39] = clip((long) bf0[38] + bf0[39]);
        bf1[40] = clip((long) bf0[40] + bf0[41]);
        bf1[41] = clip((long) bf0[40] - bf0[41]);
        bf1[42] = clip(-(long) bf0[42] + bf0[43]);
        bf1[43] = clip((long) bf0[42] + bf0[43]);
        bf1[44] = clip((long) bf0[44] + bf0[45]);
        bf1[45] = clip((long) bf0[44] - bf0[45]);
        bf1[46] = clip(-(long) bf0[46] + bf0[47]);
        bf1[47] = clip((long) bf0[46] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[49]);
        bf1[49] = clip((long) bf0[48] - bf0[49]);
        bf1[50] = clip(-(long) bf0[50] + bf0[51]);
        bf1[51] = clip((long) bf0[50] + bf0[51]);
        bf1[52] = clip((long) bf0[52] + bf0[53]);
        bf1[53] = clip((long) bf0[52] - bf0[53]);
        bf1[54] = clip(-(long) bf0[54] + bf0[55]);
        bf1[55] = clip((long) bf0[54] + bf0[55]);
        bf1[56] = clip((long) bf0[56] + bf0[57]);
        bf1[57] = clip((long) bf0[56] - bf0[57]);
        bf1[58] = clip(-(long) bf0[58] + bf0[59]);
        bf1[59] = clip((long) bf0[58] + bf0[59]);
        bf1[60] = clip((long) bf0[60] + bf0[61]);
        bf1[61] = clip((long) bf0[60] - bf0[61]);
        bf1[62] = clip(-(long) bf0[62] + bf0[63]);
        bf1[63] = clip((long) bf0[62] + bf0[63]);

        bf0 = output;
        bf1 = step;
        System.arraycopy(bf0, 0, bf1, 0, 8);
        bf1[8] = halfBtf(COSPI[60], bf0[8], -COSPI[4], bf0[15]);
        bf1[9] = halfBtf(COSPI[28], bf0[9], -COSPI[36], bf0[14]);
        bf1[10] = halfBtf(COSPI[44], bf0[10], -COSPI[20], bf0[13]);
        bf1[11] = halfBtf(COSPI[12], bf0[11], -COSPI[52], bf0[12]);
        bf1[12] = halfBtf(COSPI[52], bf0[11], COSPI[12], bf0[12]);
        bf1[13] = halfBtf(COSPI[20], bf0[10], COSPI[44], bf0[13]);
        bf1[14] = halfBtf(COSPI[36], bf0[9], COSPI[28], bf0[14]);
        bf1[15] = halfBtf(COSPI[4], bf0[8], COSPI[60], bf0[15]);
        bf1[16] = clip((long) bf0[16] + bf0[17]);
        bf1[17] = clip((long) bf0[16] - bf0[17]);
        bf1[18] = clip(-(long) bf0[18] + bf0[19]);
        bf1[19] = clip((long) bf0[18] + bf0[19]);
        bf1[20] = clip((long) bf0[20] + bf0[21]);
        bf1[21] = clip((long) bf0[20] - bf0[21]);
        bf1[22] = clip(-(long) bf0[22] + bf0[23]);
        bf1[23] = clip((long) bf0[22] + bf0[23]);
        bf1[24] = clip((long) bf0[24] + bf0[25]);
        bf1[25] = clip((long) bf0[24] - bf0[25]);
        bf1[26] = clip(-(long) bf0[26] + bf0[27]);
        bf1[27] = clip((long) bf0[26] + bf0[27]);
        bf1[28] = clip((long) bf0[28] + bf0[29]);
        bf1[29] = clip((long) bf0[28] - bf0[29]);
        bf1[30] = clip(-(long) bf0[30] + bf0[31]);
        bf1[31] = clip((long) bf0[30] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = halfBtf(-COSPI[4], bf0[33], COSPI[60], bf0[62]);
        bf1[34] = halfBtf(-COSPI[60], bf0[34], -COSPI[4], bf0[61]);
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = halfBtf(-COSPI[36], bf0[37], COSPI[28], bf0[58]);
        bf1[38] = halfBtf(-COSPI[28], bf0[38], -COSPI[36], bf0[57]);
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = halfBtf(-COSPI[20], bf0[41], COSPI[44], bf0[54]);
        bf1[42] = halfBtf(-COSPI[44], bf0[42], -COSPI[20], bf0[53]);
        bf1[43] = bf0[43];
        bf1[44] = bf0[44];
        bf1[45] = halfBtf(-COSPI[52], bf0[45], COSPI[12], bf0[50]);
        bf1[46] = halfBtf(-COSPI[12], bf0[46], -COSPI[52], bf0[49]);
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = halfBtf(-COSPI[52], bf0[46], COSPI[12], bf0[49]);
        bf1[50] = halfBtf(COSPI[12], bf0[45], COSPI[52], bf0[50]);
        bf1[51] = bf0[51];
        bf1[52] = bf0[52];
        bf1[53] = halfBtf(-COSPI[20], bf0[42], COSPI[44], bf0[53]);
        bf1[54] = halfBtf(COSPI[44], bf0[41], COSPI[20], bf0[54]);
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = halfBtf(-COSPI[36], bf0[38], COSPI[28], bf0[57]);
        bf1[58] = halfBtf(COSPI[28], bf0[37], COSPI[36], bf0[58]);
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = halfBtf(-COSPI[4], bf0[34], COSPI[60], bf0[61]);
        bf1[62] = halfBtf(COSPI[60], bf0[33], COSPI[4], bf0[62]);
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        System.arraycopy(bf0, 0, bf1, 0, 4);
        bf1[4] = halfBtf(COSPI[56], bf0[4], -COSPI[8], bf0[7]);
        bf1[5] = halfBtf(COSPI[24], bf0[5], -COSPI[40], bf0[6]);
        bf1[6] = halfBtf(COSPI[40], bf0[5], COSPI[24], bf0[6]);
        bf1[7] = halfBtf(COSPI[8], bf0[4], COSPI[56], bf0[7]);
        bf1[8] = clip((long) bf0[8] + bf0[9]);
        bf1[9] = clip((long) bf0[8] - bf0[9]);
        bf1[10] = clip(-(long) bf0[10] + bf0[11]);
        bf1[11] = clip((long) bf0[10] + bf0[11]);
        bf1[12] = clip((long) bf0[12] + bf0[13]);
        bf1[13] = clip((long) bf0[12] - bf0[13]);
        bf1[14] = clip(-(long) bf0[14] + bf0[15]);
        bf1[15] = clip((long) bf0[14] + bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = halfBtf(-COSPI[8], bf0[17], COSPI[56], bf0[30]);
        bf1[18] = halfBtf(-COSPI[56], bf0[18], -COSPI[8], bf0[29]);
        bf1[19] = bf0[19];
        bf1[20] = bf0[20];
        bf1[21] = halfBtf(-COSPI[40], bf0[21], COSPI[24], bf0[26]);
        bf1[22] = halfBtf(-COSPI[24], bf0[22], -COSPI[40], bf0[25]);
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = halfBtf(-COSPI[40], bf0[22], COSPI[24], bf0[25]);
        bf1[26] = halfBtf(COSPI[24], bf0[21], COSPI[40], bf0[26]);
        bf1[27] = bf0[27];
        bf1[28] = bf0[28];
        bf1[29] = halfBtf(-COSPI[8], bf0[18], COSPI[56], bf0[29]);
        bf1[30] = halfBtf(COSPI[56], bf0[17], COSPI[8], bf0[30]);
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[35]);
        bf1[33] = clip((long) bf0[33] + bf0[34]);
        bf1[34] = clip((long) bf0[33] - bf0[34]);
        bf1[35] = clip((long) bf0[32] - bf0[35]);
        bf1[36] = clip(-(long) bf0[36] + bf0[39]);
        bf1[37] = clip(-(long) bf0[37] + bf0[38]);
        bf1[38] = clip((long) bf0[37] + bf0[38]);
        bf1[39] = clip((long) bf0[36] + bf0[39]);
        bf1[40] = clip((long) bf0[40] + bf0[43]);
        bf1[41] = clip((long) bf0[41] + bf0[42]);
        bf1[42] = clip((long) bf0[41] - bf0[42]);
        bf1[43] = clip((long) bf0[40] - bf0[43]);
        bf1[44] = clip(-(long) bf0[44] + bf0[47]);
        bf1[45] = clip(-(long) bf0[45] + bf0[46]);
        bf1[46] = clip((long) bf0[45] + bf0[46]);
        bf1[47] = clip((long) bf0[44] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[51]);
        bf1[49] = clip((long) bf0[49] + bf0[50]);
        bf1[50] = clip((long) bf0[49] - bf0[50]);
        bf1[51] = clip((long) bf0[48] - bf0[51]);
        bf1[52] = clip(-(long) bf0[52] + bf0[55]);
        bf1[53] = clip(-(long) bf0[53] + bf0[54]);
        bf1[54] = clip((long) bf0[53] + bf0[54]);
        bf1[55] = clip((long) bf0[52] + bf0[55]);
        bf1[56] = clip((long) bf0[56] + bf0[59]);
        bf1[57] = clip((long) bf0[57] + bf0[58]);
        bf1[58] = clip((long) bf0[57] - bf0[58]);
        bf1[59] = clip((long) bf0[56] - bf0[59]);
        bf1[60] = clip(-(long) bf0[60] + bf0[63]);
        bf1[61] = clip(-(long) bf0[61] + bf0[62]);
        bf1[62] = clip((long) bf0[61] + bf0[62]);
        bf1[63] = clip((long) bf0[60] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = halfBtf(COSPI[32], bf0[0], COSPI[32], bf0[1]);
        bf1[1] = halfBtf(COSPI[32], bf0[0], -COSPI[32], bf0[1]);
        bf1[2] = halfBtf(COSPI[48], bf0[2], -COSPI[16], bf0[3]);
        bf1[3] = halfBtf(COSPI[16], bf0[2], COSPI[48], bf0[3]);
        bf1[4] = clip((long) bf0[4] + bf0[5]);
        bf1[5] = clip((long) bf0[4] - bf0[5]);
        bf1[6] = clip(-(long) bf0[6] + bf0[7]);
        bf1[7] = clip((long) bf0[6] + bf0[7]);
        bf1[8] = bf0[8];
        bf1[9] = halfBtf(-COSPI[16], bf0[9], COSPI[48], bf0[14]);
        bf1[10] = halfBtf(-COSPI[48], bf0[10], -COSPI[16], bf0[13]);
        bf1[11] = bf0[11];
        bf1[12] = bf0[12];
        bf1[13] = halfBtf(-COSPI[16], bf0[10], COSPI[48], bf0[13]);
        bf1[14] = halfBtf(COSPI[48], bf0[9], COSPI[16], bf0[14]);
        bf1[15] = bf0[15];
        bf1[16] = clip((long) bf0[16] + bf0[19]);
        bf1[17] = clip((long) bf0[17] + bf0[18]);
        bf1[18] = clip((long) bf0[17] - bf0[18]);
        bf1[19] = clip((long) bf0[16] - bf0[19]);
        bf1[20] = clip(-(long) bf0[20] + bf0[23]);
        bf1[21] = clip(-(long) bf0[21] + bf0[22]);
        bf1[22] = clip((long) bf0[21] + bf0[22]);
        bf1[23] = clip((long) bf0[20] + bf0[23]);
        bf1[24] = clip((long) bf0[24] + bf0[27]);
        bf1[25] = clip((long) bf0[25] + bf0[26]);
        bf1[26] = clip((long) bf0[25] - bf0[26]);
        bf1[27] = clip((long) bf0[24] - bf0[27]);
        bf1[28] = clip(-(long) bf0[28] + bf0[31]);
        bf1[29] = clip(-(long) bf0[29] + bf0[30]);
        bf1[30] = clip((long) bf0[29] + bf0[30]);
        bf1[31] = clip((long) bf0[28] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = halfBtf(-COSPI[8], bf0[34], COSPI[56], bf0[61]);
        bf1[35] = halfBtf(-COSPI[8], bf0[35], COSPI[56], bf0[60]);
        bf1[36] = halfBtf(-COSPI[56], bf0[36], -COSPI[8], bf0[59]);
        bf1[37] = halfBtf(-COSPI[56], bf0[37], -COSPI[8], bf0[58]);
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = bf0[40];
        bf1[41] = bf0[41];
        bf1[42] = halfBtf(-COSPI[40], bf0[42], COSPI[24], bf0[53]);
        bf1[43] = halfBtf(-COSPI[40], bf0[43], COSPI[24], bf0[52]);
        bf1[44] = halfBtf(-COSPI[24], bf0[44], -COSPI[40], bf0[51]);
        bf1[45] = halfBtf(-COSPI[24], bf0[45], -COSPI[40], bf0[50]);
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = halfBtf(-COSPI[40], bf0[45], COSPI[24], bf0[50]);
        bf1[51] = halfBtf(-COSPI[40], bf0[44], COSPI[24], bf0[51]);
        bf1[52] = halfBtf(COSPI[24], bf0[43], COSPI[40], bf0[52]);
        bf1[53] = halfBtf(COSPI[24], bf0[42], COSPI[40], bf0[53]);
        bf1[54] = bf0[54];
        bf1[55] = bf0[55];
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = halfBtf(-COSPI[8], bf0[37], COSPI[56], bf0[58]);
        bf1[59] = halfBtf(-COSPI[8], bf0[36], COSPI[56], bf0[59]);
        bf1[60] = halfBtf(COSPI[56], bf0[35], COSPI[8], bf0[60]);
        bf1[61] = halfBtf(COSPI[56], bf0[34], COSPI[8], bf0[61]);
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[3]);
        bf1[1] = clip((long) bf0[1] + bf0[2]);
        bf1[2] = clip((long) bf0[1] - bf0[2]);
        bf1[3] = clip((long) bf0[0] - bf0[3]);
        bf1[4] = bf0[4];
        bf1[5] = halfBtf(-COSPI[32], bf0[5], COSPI[32], bf0[6]);
        bf1[6] = halfBtf(COSPI[32], bf0[5], COSPI[32], bf0[6]);
        bf1[7] = bf0[7];
        bf1[8] = clip((long) bf0[8] + bf0[11]);
        bf1[9] = clip((long) bf0[9] + bf0[10]);
        bf1[10] = clip((long) bf0[9] - bf0[10]);
        bf1[11] = clip((long) bf0[8] - bf0[11]);
        bf1[12] = clip(-(long) bf0[12] + bf0[15]);
        bf1[13] = clip(-(long) bf0[13] + bf0[14]);
        bf1[14] = clip((long) bf0[13] + bf0[14]);
        bf1[15] = clip((long) bf0[12] + bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = halfBtf(-COSPI[16], bf0[18], COSPI[48], bf0[29]);
        bf1[19] = halfBtf(-COSPI[16], bf0[19], COSPI[48], bf0[28]);
        bf1[20] = halfBtf(-COSPI[48], bf0[20], -COSPI[16], bf0[27]);
        bf1[21] = halfBtf(-COSPI[48], bf0[21], -COSPI[16], bf0[26]);
        bf1[22] = bf0[22];
        bf1[23] = bf0[23];
        bf1[24] = bf0[24];
        bf1[25] = bf0[25];
        bf1[26] = halfBtf(-COSPI[16], bf0[21], COSPI[48], bf0[26]);
        bf1[27] = halfBtf(-COSPI[16], bf0[20], COSPI[48], bf0[27]);
        bf1[28] = halfBtf(COSPI[48], bf0[19], COSPI[16], bf0[28]);
        bf1[29] = halfBtf(COSPI[48], bf0[18], COSPI[16], bf0[29]);
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[39]);
        bf1[33] = clip((long) bf0[33] + bf0[38]);
        bf1[34] = clip((long) bf0[34] + bf0[37]);
        bf1[35] = clip((long) bf0[35] + bf0[36]);
        bf1[36] = clip((long) bf0[35] - bf0[36]);
        bf1[37] = clip((long) bf0[34] - bf0[37]);
        bf1[38] = clip((long) bf0[33] - bf0[38]);
        bf1[39] = clip((long) bf0[32] - bf0[39]);
        bf1[40] = clip(-(long) bf0[40] + bf0[47]);
        bf1[41] = clip(-(long) bf0[41] + bf0[46]);
        bf1[42] = clip(-(long) bf0[42] + bf0[45]);
        bf1[43] = clip(-(long) bf0[43] + bf0[44]);
        bf1[44] = clip((long) bf0[43] + bf0[44]);
        bf1[45] = clip((long) bf0[42] + bf0[45]);
        bf1[46] = clip((long) bf0[41] + bf0[46]);
        bf1[47] = clip((long) bf0[40] + bf0[47]);
        bf1[48] = clip((long) bf0[48] + bf0[55]);
        bf1[49] = clip((long) bf0[49] + bf0[54]);
        bf1[50] = clip((long) bf0[50] + bf0[53]);
        bf1[51] = clip((long) bf0[51] + bf0[52]);
        bf1[52] = clip((long) bf0[51] - bf0[52]);
        bf1[53] = clip((long) bf0[50] - bf0[53]);
        bf1[54] = clip((long) bf0[49] - bf0[54]);
        bf1[55] = clip((long) bf0[48] - bf0[55]);
        bf1[56] = clip(-(long) bf0[56] + bf0[63]);
        bf1[57] = clip(-(long) bf0[57] + bf0[62]);
        bf1[58] = clip(-(long) bf0[58] + bf0[61]);
        bf1[59] = clip(-(long) bf0[59] + bf0[60]);
        bf1[60] = clip((long) bf0[59] + bf0[60]);
        bf1[61] = clip((long) bf0[58] + bf0[61]);
        bf1[62] = clip((long) bf0[57] + bf0[62]);
        bf1[63] = clip((long) bf0[56] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = clip((long) bf0[0] + bf0[7]);
        bf1[1] = clip((long) bf0[1] + bf0[6]);
        bf1[2] = clip((long) bf0[2] + bf0[5]);
        bf1[3] = clip((long) bf0[3] + bf0[4]);
        bf1[4] = clip((long) bf0[3] - bf0[4]);
        bf1[5] = clip((long) bf0[2] - bf0[5]);
        bf1[6] = clip((long) bf0[1] - bf0[6]);
        bf1[7] = clip((long) bf0[0] - bf0[7]);
        bf1[8] = bf0[8];
        bf1[9] = bf0[9];
        bf1[10] = halfBtf(-COSPI[32], bf0[10], COSPI[32], bf0[13]);
        bf1[11] = halfBtf(-COSPI[32], bf0[11], COSPI[32], bf0[12]);
        bf1[12] = halfBtf(COSPI[32], bf0[11], COSPI[32], bf0[12]);
        bf1[13] = halfBtf(COSPI[32], bf0[10], COSPI[32], bf0[13]);
        bf1[14] = bf0[14];
        bf1[15] = bf0[15];
        bf1[16] = clip((long) bf0[16] + bf0[23]);
        bf1[17] = clip((long) bf0[17] + bf0[22]);
        bf1[18] = clip((long) bf0[18] + bf0[21]);
        bf1[19] = clip((long) bf0[19] + bf0[20]);
        bf1[20] = clip((long) bf0[19] - bf0[20]);
        bf1[21] = clip((long) bf0[18] - bf0[21]);
        bf1[22] = clip((long) bf0[17] - bf0[22]);
        bf1[23] = clip((long) bf0[16] - bf0[23]);
        bf1[24] = clip(-(long) bf0[24] + bf0[31]);
        bf1[25] = clip(-(long) bf0[25] + bf0[30]);
        bf1[26] = clip(-(long) bf0[26] + bf0[29]);
        bf1[27] = clip(-(long) bf0[27] + bf0[28]);
        bf1[28] = clip((long) bf0[27] + bf0[28]);
        bf1[29] = clip((long) bf0[26] + bf0[29]);
        bf1[30] = clip((long) bf0[25] + bf0[30]);
        bf1[31] = clip((long) bf0[24] + bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = halfBtf(-COSPI[16], bf0[36], COSPI[48], bf0[59]);
        bf1[37] = halfBtf(-COSPI[16], bf0[37], COSPI[48], bf0[58]);
        bf1[38] = halfBtf(-COSPI[16], bf0[38], COSPI[48], bf0[57]);
        bf1[39] = halfBtf(-COSPI[16], bf0[39], COSPI[48], bf0[56]);
        bf1[40] = halfBtf(-COSPI[48], bf0[40], -COSPI[16], bf0[55]);
        bf1[41] = halfBtf(-COSPI[48], bf0[41], -COSPI[16], bf0[54]);
        bf1[42] = halfBtf(-COSPI[48], bf0[42], -COSPI[16], bf0[53]);
        bf1[43] = halfBtf(-COSPI[48], bf0[43], -COSPI[16], bf0[52]);
        bf1[44] = bf0[44];
        bf1[45] = bf0[45];
        bf1[46] = bf0[46];
        bf1[47] = bf0[47];
        bf1[48] = bf0[48];
        bf1[49] = bf0[49];
        bf1[50] = bf0[50];
        bf1[51] = bf0[51];
        bf1[52] = halfBtf(-COSPI[16], bf0[43], COSPI[48], bf0[52]);
        bf1[53] = halfBtf(-COSPI[16], bf0[42], COSPI[48], bf0[53]);
        bf1[54] = halfBtf(-COSPI[16], bf0[41], COSPI[48], bf0[54]);
        bf1[55] = halfBtf(-COSPI[16], bf0[40], COSPI[48], bf0[55]);
        bf1[56] = halfBtf(COSPI[48], bf0[39], COSPI[16], bf0[56]);
        bf1[57] = halfBtf(COSPI[48], bf0[38], COSPI[16], bf0[57]);
        bf1[58] = halfBtf(COSPI[48], bf0[37], COSPI[16], bf0[58]);
        bf1[59] = halfBtf(COSPI[48], bf0[36], COSPI[16], bf0[59]);
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[15]);
        bf1[1] = clip((long) bf0[1] + bf0[14]);
        bf1[2] = clip((long) bf0[2] + bf0[13]);
        bf1[3] = clip((long) bf0[3] + bf0[12]);
        bf1[4] = clip((long) bf0[4] + bf0[11]);
        bf1[5] = clip((long) bf0[5] + bf0[10]);
        bf1[6] = clip((long) bf0[6] + bf0[9]);
        bf1[7] = clip((long) bf0[7] + bf0[8]);
        bf1[8] = clip((long) bf0[7] - bf0[8]);
        bf1[9] = clip((long) bf0[6] - bf0[9]);
        bf1[10] = clip((long) bf0[5] - bf0[10]);
        bf1[11] = clip((long) bf0[4] - bf0[11]);
        bf1[12] = clip((long) bf0[3] - bf0[12]);
        bf1[13] = clip((long) bf0[2] - bf0[13]);
        bf1[14] = clip((long) bf0[1] - bf0[14]);
        bf1[15] = clip((long) bf0[0] - bf0[15]);
        bf1[16] = bf0[16];
        bf1[17] = bf0[17];
        bf1[18] = bf0[18];
        bf1[19] = bf0[19];
        bf1[20] = halfBtf(-COSPI[32], bf0[20], COSPI[32], bf0[27]);
        bf1[21] = halfBtf(-COSPI[32], bf0[21], COSPI[32], bf0[26]);
        bf1[22] = halfBtf(-COSPI[32], bf0[22], COSPI[32], bf0[25]);
        bf1[23] = halfBtf(-COSPI[32], bf0[23], COSPI[32], bf0[24]);
        bf1[24] = halfBtf(COSPI[32], bf0[23], COSPI[32], bf0[24]);
        bf1[25] = halfBtf(COSPI[32], bf0[22], COSPI[32], bf0[25]);
        bf1[26] = halfBtf(COSPI[32], bf0[21], COSPI[32], bf0[26]);
        bf1[27] = halfBtf(COSPI[32], bf0[20], COSPI[32], bf0[27]);
        bf1[28] = bf0[28];
        bf1[29] = bf0[29];
        bf1[30] = bf0[30];
        bf1[31] = bf0[31];
        bf1[32] = clip((long) bf0[32] + bf0[47]);
        bf1[33] = clip((long) bf0[33] + bf0[46]);
        bf1[34] = clip((long) bf0[34] + bf0[45]);
        bf1[35] = clip((long) bf0[35] + bf0[44]);
        bf1[36] = clip((long) bf0[36] + bf0[43]);
        bf1[37] = clip((long) bf0[37] + bf0[42]);
        bf1[38] = clip((long) bf0[38] + bf0[41]);
        bf1[39] = clip((long) bf0[39] + bf0[40]);
        bf1[40] = clip((long) bf0[39] - bf0[40]);
        bf1[41] = clip((long) bf0[38] - bf0[41]);
        bf1[42] = clip((long) bf0[37] - bf0[42]);
        bf1[43] = clip((long) bf0[36] - bf0[43]);
        bf1[44] = clip((long) bf0[35] - bf0[44]);
        bf1[45] = clip((long) bf0[34] - bf0[45]);
        bf1[46] = clip((long) bf0[33] - bf0[46]);
        bf1[47] = clip((long) bf0[32] - bf0[47]);
        bf1[48] = clip(-(long) bf0[48] + bf0[63]);
        bf1[49] = clip(-(long) bf0[49] + bf0[62]);
        bf1[50] = clip(-(long) bf0[50] + bf0[61]);
        bf1[51] = clip(-(long) bf0[51] + bf0[60]);
        bf1[52] = clip(-(long) bf0[52] + bf0[59]);
        bf1[53] = clip(-(long) bf0[53] + bf0[58]);
        bf1[54] = clip(-(long) bf0[54] + bf0[57]);
        bf1[55] = clip(-(long) bf0[55] + bf0[56]);
        bf1[56] = clip((long) bf0[55] + bf0[56]);
        bf1[57] = clip((long) bf0[54] + bf0[57]);
        bf1[58] = clip((long) bf0[53] + bf0[58]);
        bf1[59] = clip((long) bf0[52] + bf0[59]);
        bf1[60] = clip((long) bf0[51] + bf0[60]);
        bf1[61] = clip((long) bf0[50] + bf0[61]);
        bf1[62] = clip((long) bf0[49] + bf0[62]);
        bf1[63] = clip((long) bf0[48] + bf0[63]);

        bf0 = output;
        bf1 = step;
        bf1[0] = clip((long) bf0[0] + bf0[31]);
        bf1[1] = clip((long) bf0[1] + bf0[30]);
        bf1[2] = clip((long) bf0[2] + bf0[29]);
        bf1[3] = clip((long) bf0[3] + bf0[28]);
        bf1[4] = clip((long) bf0[4] + bf0[27]);
        bf1[5] = clip((long) bf0[5] + bf0[26]);
        bf1[6] = clip((long) bf0[6] + bf0[25]);
        bf1[7] = clip((long) bf0[7] + bf0[24]);
        bf1[8] = clip((long) bf0[8] + bf0[23]);
        bf1[9] = clip((long) bf0[9] + bf0[22]);
        bf1[10] = clip((long) bf0[10] + bf0[21]);
        bf1[11] = clip((long) bf0[11] + bf0[20]);
        bf1[12] = clip((long) bf0[12] + bf0[19]);
        bf1[13] = clip((long) bf0[13] + bf0[18]);
        bf1[14] = clip((long) bf0[14] + bf0[17]);
        bf1[15] = clip((long) bf0[15] + bf0[16]);
        bf1[16] = clip((long) bf0[15] - bf0[16]);
        bf1[17] = clip((long) bf0[14] - bf0[17]);
        bf1[18] = clip((long) bf0[13] - bf0[18]);
        bf1[19] = clip((long) bf0[12] - bf0[19]);
        bf1[20] = clip((long) bf0[11] - bf0[20]);
        bf1[21] = clip((long) bf0[10] - bf0[21]);
        bf1[22] = clip((long) bf0[9] - bf0[22]);
        bf1[23] = clip((long) bf0[8] - bf0[23]);
        bf1[24] = clip((long) bf0[7] - bf0[24]);
        bf1[25] = clip((long) bf0[6] - bf0[25]);
        bf1[26] = clip((long) bf0[5] - bf0[26]);
        bf1[27] = clip((long) bf0[4] - bf0[27]);
        bf1[28] = clip((long) bf0[3] - bf0[28]);
        bf1[29] = clip((long) bf0[2] - bf0[29]);
        bf1[30] = clip((long) bf0[1] - bf0[30]);
        bf1[31] = clip((long) bf0[0] - bf0[31]);
        bf1[32] = bf0[32];
        bf1[33] = bf0[33];
        bf1[34] = bf0[34];
        bf1[35] = bf0[35];
        bf1[36] = bf0[36];
        bf1[37] = bf0[37];
        bf1[38] = bf0[38];
        bf1[39] = bf0[39];
        bf1[40] = halfBtf(-COSPI[32], bf0[40], COSPI[32], bf0[55]);
        bf1[41] = halfBtf(-COSPI[32], bf0[41], COSPI[32], bf0[54]);
        bf1[42] = halfBtf(-COSPI[32], bf0[42], COSPI[32], bf0[53]);
        bf1[43] = halfBtf(-COSPI[32], bf0[43], COSPI[32], bf0[52]);
        bf1[44] = halfBtf(-COSPI[32], bf0[44], COSPI[32], bf0[51]);
        bf1[45] = halfBtf(-COSPI[32], bf0[45], COSPI[32], bf0[50]);
        bf1[46] = halfBtf(-COSPI[32], bf0[46], COSPI[32], bf0[49]);
        bf1[47] = halfBtf(-COSPI[32], bf0[47], COSPI[32], bf0[48]);
        bf1[48] = halfBtf(COSPI[32], bf0[47], COSPI[32], bf0[48]);
        bf1[49] = halfBtf(COSPI[32], bf0[46], COSPI[32], bf0[49]);
        bf1[50] = halfBtf(COSPI[32], bf0[45], COSPI[32], bf0[50]);
        bf1[51] = halfBtf(COSPI[32], bf0[44], COSPI[32], bf0[51]);
        bf1[52] = halfBtf(COSPI[32], bf0[43], COSPI[32], bf0[52]);
        bf1[53] = halfBtf(COSPI[32], bf0[42], COSPI[32], bf0[53]);
        bf1[54] = halfBtf(COSPI[32], bf0[41], COSPI[32], bf0[54]);
        bf1[55] = halfBtf(COSPI[32], bf0[40], COSPI[32], bf0[55]);
        bf1[56] = bf0[56];
        bf1[57] = bf0[57];
        bf1[58] = bf0[58];
        bf1[59] = bf0[59];
        bf1[60] = bf0[60];
        bf1[61] = bf0[61];
        bf1[62] = bf0[62];
        bf1[63] = bf0[63];

        bf0 = step;
        bf1 = output;
        bf1[0] = clip((long) bf0[0] + bf0[63]);
        bf1[1] = clip((long) bf0[1] + bf0[62]);
        bf1[2] = clip((long) bf0[2] + bf0[61]);
        bf1[3] = clip((long) bf0[3] + bf0[60]);
        bf1[4] = clip((long) bf0[4] + bf0[59]);
        bf1[5] = clip((long) bf0[5] + bf0[58]);
        bf1[6] = clip((long) bf0[6] + bf0[57]);
        bf1[7] = clip((long) bf0[7] + bf0[56]);
        bf1[8] = clip((long) bf0[8] + bf0[55]);
        bf1[9] = clip((long) bf0[9] + bf0[54]);
        bf1[10] = clip((long) bf0[10] + bf0[53]);
        bf1[11] = clip((long) bf0[11] + bf0[52]);
        bf1[12] = clip((long) bf0[12] + bf0[51]);
        bf1[13] = clip((long) bf0[13] + bf0[50]);
        bf1[14] = clip((long) bf0[14] + bf0[49]);
        bf1[15] = clip((long) bf0[15] + bf0[48]);
        bf1[16] = clip((long) bf0[16] + bf0[47]);
        bf1[17] = clip((long) bf0[17] + bf0[46]);
        bf1[18] = clip((long) bf0[18] + bf0[45]);
        bf1[19] = clip((long) bf0[19] + bf0[44]);
        bf1[20] = clip((long) bf0[20] + bf0[43]);
        bf1[21] = clip((long) bf0[21] + bf0[42]);
        bf1[22] = clip((long) bf0[22] + bf0[41]);
        bf1[23] = clip((long) bf0[23] + bf0[40]);
        bf1[24] = clip((long) bf0[24] + bf0[39]);
        bf1[25] = clip((long) bf0[25] + bf0[38]);
        bf1[26] = clip((long) bf0[26] + bf0[37]);
        bf1[27] = clip((long) bf0[27] + bf0[36]);
        bf1[28] = clip((long) bf0[28] + bf0[35]);
        bf1[29] = clip((long) bf0[29] + bf0[34]);
        bf1[30] = clip((long) bf0[30] + bf0[33]);
        bf1[31] = clip((long) bf0[31] + bf0[32]);
        bf1[32] = clip((long) bf0[31] - bf0[32]);
        bf1[33] = clip((long) bf0[30] - bf0[33]);
        bf1[34] = clip((long) bf0[29] - bf0[34]);
        bf1[35] = clip((long) bf0[28] - bf0[35]);
        bf1[36] = clip((long) bf0[27] - bf0[36]);
        bf1[37] = clip((long) bf0[26] - bf0[37]);
        bf1[38] = clip((long) bf0[25] - bf0[38]);
        bf1[39] = clip((long) bf0[24] - bf0[39]);
        bf1[40] = clip((long) bf0[23] - bf0[40]);
        bf1[41] = clip((long) bf0[22] - bf0[41]);
        bf1[42] = clip((long) bf0[21] - bf0[42]);
        bf1[43] = clip((long) bf0[20] - bf0[43]);
        bf1[44] = clip((long) bf0[19] - bf0[44]);
        bf1[45] = clip((long) bf0[18] - bf0[45]);
        bf1[46] = clip((long) bf0[17] - bf0[46]);
        bf1[47] = clip((long) bf0[16] - bf0[47]);
        bf1[48] = clip((long) bf0[15] - bf0[48]);
        bf1[49] = clip((long) bf0[14] - bf0[49]);
        bf1[50] = clip((long) bf0[13] - bf0[50]);
        bf1[51] = clip((long) bf0[12] - bf0[51]);
        bf1[52] = clip((long) bf0[11] - bf0[52]);
        bf1[53] = clip((long) bf0[10] - bf0[53]);
        bf1[54] = clip((long) bf0[9] - bf0[54]);
        bf1[55] = clip((long) bf0[8] - bf0[55]);
        bf1[56] = clip((long) bf0[7] - bf0[56]);
        bf1[57] = clip((long) bf0[6] - bf0[57]);
        bf1[58] = clip((long) bf0[5] - bf0[58]);
        bf1[59] = clip((long) bf0[4] - bf0[59]);
        bf1[60] = clip((long) bf0[3] - bf0[60]);
        bf1[61] = clip((long) bf0[2] - bf0[61]);
        bf1[62] = clip((long) bf0[1] - bf0[62]);
        bf1[63] = clip((long) bf0[0] - bf0[63]);
    }

    /// Reconstructs the `tx64`-specific `DCT_32` even subtree in place.
    ///
    /// @param values the strided transform buffer to update in place
    /// @param stride the element stride between logical samples
    private static void inverseDct32Dav1dTx64InPlace(int[] values, int stride) {
        inverseDct16Dav1dTx64InPlace(values, stride << 1);

        int in1 = values[stride];
        int in3 = values[stride * 3];
        int in5 = values[stride * 5];
        int in7 = values[stride * 7];
        int in9 = values[stride * 9];
        int in11 = values[stride * 11];
        int in13 = values[stride * 13];
        int in15 = values[stride * 15];

        int t16a = positiveRoundShift((long) in1 * 201, 12);
        int t17a = positiveRoundShift((long) in15 * -2751, 12);
        int t18a = positiveRoundShift((long) in9 * 1751, 12);
        int t19a = positiveRoundShift((long) in7 * -1380, 12);
        int t20a = positiveRoundShift((long) in5 * 995, 12);
        int t21a = positiveRoundShift((long) in11 * -2106, 12);
        int t22a = positiveRoundShift((long) in13 * 2440, 12);
        int t23a = positiveRoundShift((long) in3 * -601, 12);
        int t24a = positiveRoundShift((long) in3 * 4052, 12);
        int t25a = positiveRoundShift((long) in13 * 3290, 12);
        int t26a = positiveRoundShift((long) in11 * 3513, 12);
        int t27a = positiveRoundShift((long) in5 * 3973, 12);
        int t28a = positiveRoundShift((long) in7 * 3857, 12);
        int t29a = positiveRoundShift((long) in9 * 3703, 12);
        int t30a = positiveRoundShift((long) in15 * 3035, 12);
        int t31a = positiveRoundShift((long) in1 * 4091, 12);

        int t16 = clip((long) t16a + t17a);
        int t17 = clip((long) t16a - t17a);
        int t18 = clip((long) t19a - t18a);
        int t19 = clip((long) t19a + t18a);
        int t20 = clip((long) t20a + t21a);
        int t21 = clip((long) t20a - t21a);
        int t22 = clip((long) t23a - t22a);
        int t23 = clip((long) t23a + t22a);
        int t24 = clip((long) t24a + t25a);
        int t25 = clip((long) t24a - t25a);
        int t26 = clip((long) t27a - t26a);
        int t27 = clip((long) t27a + t26a);
        int t28 = clip((long) t28a + t29a);
        int t29 = clip((long) t28a - t29a);
        int t30 = clip((long) t31a - t30a);
        int t31 = clip((long) t31a + t30a);

        t17a = positiveRoundShift((long) t30 * 799 - (long) t17 * (4017 - 4096), 12) - t17;
        t30a = positiveRoundShift((long) t30 * (4017 - 4096) + (long) t17 * 799, 12) + t30;
        t18a = positiveRoundShift(-((long) t29 * (4017 - 4096) + (long) t18 * 799), 12) - t29;
        t29a = positiveRoundShift((long) t29 * 799 - (long) t18 * (4017 - 4096), 12) - t18;
        t21a = positiveRoundShift((long) t26 * 1703 - (long) t21 * 1138, 11);
        t26a = positiveRoundShift((long) t26 * 1138 + (long) t21 * 1703, 11);
        t22a = positiveRoundShift(-((long) t25 * 1138 + (long) t22 * 1703), 11);
        t25a = positiveRoundShift((long) t25 * 1703 - (long) t22 * 1138, 11);

        t16a = clip((long) t16 + t19);
        t17 = clip((long) t17a + t18a);
        t18 = clip((long) t17a - t18a);
        int t19b = clip((long) t16 - t19);
        int t20b = clip((long) t23 - t20);
        t21 = clip((long) t22a - t21a);
        t22 = clip((long) t22a + t21a);
        int t23b = clip((long) t23 + t20);
        int t24b = clip((long) t24 + t27);
        t25 = clip((long) t25a + t26a);
        t26 = clip((long) t25a - t26a);
        int t27b = clip((long) t24 - t27);
        int t28b = clip((long) t31 - t28);
        t29 = clip((long) t30a - t29a);
        t30 = clip((long) t30a + t29a);
        int t31b = clip((long) t31 + t28);

        t18a = positiveRoundShift((long) t29 * 1567 - (long) t18 * (3784 - 4096), 12) - t18;
        t29a = positiveRoundShift((long) t29 * (3784 - 4096) + (long) t18 * 1567, 12) + t29;
        t19 = positiveRoundShift((long) t28b * 1567 - (long) t19b * (3784 - 4096), 12) - t19b;
        t28 = positiveRoundShift((long) t28b * (3784 - 4096) + (long) t19b * 1567, 12) + t28b;
        t20 = positiveRoundShift(-((long) t27b * (3784 - 4096) + (long) t20b * 1567), 12) - t27b;
        t27 = positiveRoundShift((long) t27b * 1567 - (long) t20b * (3784 - 4096), 12) - t20b;
        t21a = positiveRoundShift(-((long) t26 * (3784 - 4096) + (long) t21 * 1567), 12) - t26;
        t26a = positiveRoundShift((long) t26 * 1567 - (long) t21 * (3784 - 4096), 12) - t21;

        t16 = clip((long) t16a + t23b);
        t17a = clip((long) t17 + t22);
        t18 = clip((long) t18a + t21a);
        t19b = clip((long) t19 + t20);
        t20b = clip((long) t19 - t20);
        t21 = clip((long) t18a - t21a);
        t22a = clip((long) t17 - t22);
        t23 = clip((long) t16a - t23b);
        t24 = clip((long) t31b - t24b);
        t25a = clip((long) t30 - t25);
        t26 = clip((long) t29a - t26a);
        t27b = clip((long) t28 - t27);
        t28b = clip((long) t28 + t27);
        t29 = clip((long) t29a + t26a);
        t30a = clip((long) t30 + t25);
        t31 = clip((long) t31b + t24b);

        t20 = positiveRoundShift((long) (t27b - t20b) * 181, 8);
        t27 = positiveRoundShift((long) (t27b + t20b) * 181, 8);
        t21a = positiveRoundShift((long) (t26 - t21) * 181, 8);
        t26a = positiveRoundShift((long) (t26 + t21) * 181, 8);
        t22 = positiveRoundShift((long) (t25a - t22a) * 181, 8);
        t25 = positiveRoundShift((long) (t25a + t22a) * 181, 8);
        t23a = positiveRoundShift((long) (t24 - t23) * 181, 8);
        t24a = positiveRoundShift((long) (t24 + t23) * 181, 8);

        int t0 = values[0];
        int t1 = values[stride * 2];
        int t2 = values[stride * 4];
        int t3 = values[stride * 6];
        int t4 = values[stride * 8];
        int t5 = values[stride * 10];
        int t6 = values[stride * 12];
        int t7 = values[stride * 14];
        int t8 = values[stride * 16];
        int t9 = values[stride * 18];
        int t10 = values[stride * 20];
        int t11 = values[stride * 22];
        int t12 = values[stride * 24];
        int t13 = values[stride * 26];
        int t14 = values[stride * 28];
        int t15 = values[stride * 30];

        values[0] = clip((long) t0 + t31);
        values[stride] = clip((long) t1 + t30a);
        values[stride * 2] = clip((long) t2 + t29);
        values[stride * 3] = clip((long) t3 + t28b);
        values[stride * 4] = clip((long) t4 + t27);
        values[stride * 5] = clip((long) t5 + t26a);
        values[stride * 6] = clip((long) t6 + t25);
        values[stride * 7] = clip((long) t7 + t24a);
        values[stride * 8] = clip((long) t8 + t23a);
        values[stride * 9] = clip((long) t9 + t22);
        values[stride * 10] = clip((long) t10 + t21a);
        values[stride * 11] = clip((long) t11 + t20);
        values[stride * 12] = clip((long) t12 + t19b);
        values[stride * 13] = clip((long) t13 + t18);
        values[stride * 14] = clip((long) t14 + t17a);
        values[stride * 15] = clip((long) t15 + t16);
        values[stride * 16] = clip((long) t15 - t16);
        values[stride * 17] = clip((long) t14 - t17a);
        values[stride * 18] = clip((long) t13 - t18);
        values[stride * 19] = clip((long) t12 - t19b);
        values[stride * 20] = clip((long) t11 - t20);
        values[stride * 21] = clip((long) t10 - t21a);
        values[stride * 22] = clip((long) t9 - t22);
        values[stride * 23] = clip((long) t8 - t23a);
        values[stride * 24] = clip((long) t7 - t24a);
        values[stride * 25] = clip((long) t6 - t25);
        values[stride * 26] = clip((long) t5 - t26a);
        values[stride * 27] = clip((long) t4 - t27);
        values[stride * 28] = clip((long) t3 - t28b);
        values[stride * 29] = clip((long) t2 - t29);
        values[stride * 30] = clip((long) t1 - t30a);
        values[stride * 31] = clip((long) t0 - t31);
    }

    /// Reconstructs the `tx64`-specific `DCT_16` even subtree in place.
    ///
    /// @param values the strided transform buffer to update in place
    /// @param stride the element stride between logical samples
    private static void inverseDct16Dav1dTx64InPlace(int[] values, int stride) {
        inverseDct8Dav1dTx64InPlace(values, stride << 1);

        int in1 = values[stride];
        int in3 = values[stride * 3];
        int in5 = values[stride * 5];
        int in7 = values[stride * 7];

        int t8a = positiveRoundShift((long) in1 * 401, 12);
        int t9a = positiveRoundShift((long) in7 * -2598, 12);
        int t10a = positiveRoundShift((long) in5 * 1931, 12);
        int t11a = positiveRoundShift((long) in3 * -1189, 12);
        int t12a = positiveRoundShift((long) in3 * 3920, 12);
        int t13a = positiveRoundShift((long) in5 * 3612, 12);
        int t14a = positiveRoundShift((long) in7 * 3166, 12);
        int t15a = positiveRoundShift((long) in1 * 4076, 12);

        int t8 = clip((long) t8a + t9a);
        int t9 = clip((long) t8a - t9a);
        int t10 = clip((long) t11a - t10a);
        int t11 = clip((long) t11a + t10a);
        int t12 = clip((long) t12a + t13a);
        int t13 = clip((long) t12a - t13a);
        int t14 = clip((long) t15a - t14a);
        int t15 = clip((long) t15a + t14a);

        t9a = positiveRoundShift((long) t14 * 1567 - (long) t9 * (3784 - 4096), 12) - t9;
        t14a = positiveRoundShift((long) t14 * (3784 - 4096) + (long) t9 * 1567, 12) + t14;
        t10a = positiveRoundShift(-((long) t13 * (3784 - 4096) + (long) t10 * 1567), 12) - t13;
        t13a = positiveRoundShift((long) t13 * 1567 - (long) t10 * (3784 - 4096), 12) - t10;

        t8a = clip((long) t8 + t11);
        t9 = clip((long) t9a + t10a);
        t10 = clip((long) t9a - t10a);
        int t11b = clip((long) t8 - t11);
        int t12b = clip((long) t15 - t12);
        t13 = clip((long) t14a - t13a);
        t14 = clip((long) t14a + t13a);
        int t15b = clip((long) t15 + t12);

        t10a = positiveRoundShift((long) (t13 - t10) * 181, 8);
        t13a = positiveRoundShift((long) (t13 + t10) * 181, 8);
        int t11c = positiveRoundShift((long) (t12b - t11b) * 181, 8);
        int t12c = positiveRoundShift((long) (t12b + t11b) * 181, 8);

        int t0 = values[0];
        int t1 = values[stride * 2];
        int t2 = values[stride * 4];
        int t3 = values[stride * 6];
        int t4 = values[stride * 8];
        int t5 = values[stride * 10];
        int t6 = values[stride * 12];
        int t7 = values[stride * 14];

        values[0] = clip((long) t0 + t15b);
        values[stride] = clip((long) t1 + t14);
        values[stride * 2] = clip((long) t2 + t13a);
        values[stride * 3] = clip((long) t3 + t12c);
        values[stride * 4] = clip((long) t4 + t11c);
        values[stride * 5] = clip((long) t5 + t10a);
        values[stride * 6] = clip((long) t6 + t9);
        values[stride * 7] = clip((long) t7 + t8a);
        values[stride * 8] = clip((long) t7 - t8a);
        values[stride * 9] = clip((long) t6 - t9);
        values[stride * 10] = clip((long) t5 - t10a);
        values[stride * 11] = clip((long) t4 - t11c);
        values[stride * 12] = clip((long) t3 - t12c);
        values[stride * 13] = clip((long) t2 - t13a);
        values[stride * 14] = clip((long) t1 - t14);
        values[stride * 15] = clip((long) t0 - t15b);
    }

    /// Reconstructs the `tx64`-specific `DCT_8` even subtree in place.
    ///
    /// @param values the strided transform buffer to update in place
    /// @param stride the element stride between logical samples
    private static void inverseDct8Dav1dTx64InPlace(int[] values, int stride) {
        inverseDct4Dav1dTx64InPlace(values, stride << 1);

        int in1 = values[stride];
        int in3 = values[stride * 3];

        int t4a = positiveRoundShift((long) in1 * 799, 12);
        int t5a = positiveRoundShift((long) in3 * -2276, 12);
        int t6a = positiveRoundShift((long) in3 * 3406, 12);
        int t7a = positiveRoundShift((long) in1 * 4017, 12);

        int t4 = clip((long) t4a + t5a);
        t5a = clip((long) t4a - t5a);
        int t7 = clip((long) t7a + t6a);
        t6a = clip((long) t7a - t6a);

        int t5 = positiveRoundShift((long) (t6a - t5a) * 181, 8);
        int t6 = positiveRoundShift((long) (t6a + t5a) * 181, 8);

        int t0 = values[0];
        int t1 = values[stride * 2];
        int t2 = values[stride * 4];
        int t3 = values[stride * 6];

        values[0] = clip((long) t0 + t7);
        values[stride] = clip((long) t1 + t6);
        values[stride * 2] = clip((long) t2 + t5);
        values[stride * 3] = clip((long) t3 + t4);
        values[stride * 4] = clip((long) t3 - t4);
        values[stride * 5] = clip((long) t2 - t5);
        values[stride * 6] = clip((long) t1 - t6);
        values[stride * 7] = clip((long) t0 - t7);
    }

    /// Reconstructs the `tx64`-specific `DCT_4` even subtree in place.
    ///
    /// @param values the strided transform buffer to update in place
    /// @param stride the element stride between logical samples
    private static void inverseDct4Dav1dTx64InPlace(int[] values, int stride) {
        int in0 = values[0];
        int in1 = values[stride];

        int t0 = positiveRoundShift((long) in0 * 181, 8);
        int t1 = t0;
        int t2 = positiveRoundShift((long) in1 * 1567, 12);
        int t3 = positiveRoundShift((long) in1 * 3784, 12);

        values[0] = clip((long) t0 + t3);
        values[stride] = clip((long) t1 + t2);
        values[stride * 2] = clip((long) t1 - t2);
        values[stride * 3] = clip((long) t0 - t3);
    }

    /// Reconstructs one fallback one-dimensional larger `DCT_N` vector through cached orthonormal
    /// cosine basis rows.
    ///
    /// This path remains available for any future transform lengths that have not yet been lowered
    /// into explicit integer kernels.
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

    /// Reconstructs one supported one-dimensional inverse ADST vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private static void inverseAdst(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> inverseAdst4(input, output);
            case 8 -> inverseAdst8(input, output);
            case 16 -> inverseAdst16(input, output);
            default -> throw new IllegalStateException("Unsupported inverse ADST length: " + length);
        }
    }

    /// Reconstructs one supported one-dimensional inverse FLIPADST vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private static void inverseFlipAdst(int[] input, int[] output, int length) {
        int[] scratch = new int[length];
        inverseAdst(input, scratch, length);
        for (int i = 0; i < length; i++) {
            output[i] = scratch[length - 1 - i];
        }
    }

    /// Reconstructs one one-dimensional `ADST_4` vector.
    ///
    /// The arithmetic matches the AOM `av1_iadst4(..., cos_bit = 12)` path.
    ///
    /// @param input the dequantized `ADST_4` input vector
    /// @param output the reconstructed output vector
    private static void inverseAdst4(int[] input, int[] output) {
        int x0 = input[0];
        int x1 = input[1];
        int x2 = input[2];
        int x3 = input[3];

        if ((x0 | x1 | x2 | x3) == 0) {
            output[0] = 0;
            output[1] = 0;
            output[2] = 0;
            output[3] = 0;
            return;
        }

        long s0 = 1321L * x0;
        long s1 = 2482L * x0;
        long s2 = 3344L * x1;
        long s3 = 3803L * x2;
        long s4 = 1321L * x2;
        long s5 = 2482L * x3;
        long s6 = 3803L * x3;
        int s7 = saturatedInt((long) (x0 - x2) + x3);

        s0 += s3;
        s1 -= s4;
        s3 = s2;
        s2 = 3344L * s7;

        s0 += s5;
        s1 -= s6;

        long y0 = s0 + s3;
        long y1 = s1 + s3;
        long y2 = s2;
        long y3 = s0 + s1 - s3;

        output[0] = positiveRoundShift(y0, 12);
        output[1] = positiveRoundShift(y1, 12);
        output[2] = positiveRoundShift(y2, 12);
        output[3] = positiveRoundShift(y3, 12);
    }

    /// Reconstructs one one-dimensional `ADST_8` vector.
    ///
    /// The arithmetic matches the AOM `av1_iadst8(..., cos_bit = 12)` path.
    ///
    /// @param input the dequantized `ADST_8` input vector
    /// @param output the reconstructed output vector
    private static void inverseAdst8(int[] input, int[] output) {
        int b0 = input[7];
        int b1 = input[0];
        int b2 = input[5];
        int b3 = input[2];
        int b4 = input[3];
        int b5 = input[4];
        int b6 = input[1];
        int b7 = input[6];

        int s0 = halfBtf(COSPI[4], b0, COSPI[60], b1);
        int s1 = halfBtf(COSPI[60], b0, -COSPI[4], b1);
        int s2 = halfBtf(COSPI[20], b2, COSPI[44], b3);
        int s3 = halfBtf(COSPI[44], b2, -COSPI[20], b3);
        int s4 = halfBtf(COSPI[36], b4, COSPI[28], b5);
        int s5 = halfBtf(COSPI[28], b4, -COSPI[36], b5);
        int s6 = halfBtf(COSPI[52], b6, COSPI[12], b7);
        int s7 = halfBtf(COSPI[12], b6, -COSPI[52], b7);

        int t0 = clip((long) s0 + s4);
        int t1 = clip((long) s1 + s5);
        int t2 = clip((long) s2 + s6);
        int t3 = clip((long) s3 + s7);
        int t4 = clip((long) s0 - s4);
        int t5 = clip((long) s1 - s5);
        int t6 = clip((long) s2 - s6);
        int t7 = clip((long) s3 - s7);

        int u4 = halfBtf(COSPI[16], t4, COSPI[48], t5);
        int u5 = halfBtf(COSPI[48], t4, -COSPI[16], t5);
        int u6 = halfBtf(-COSPI[48], t6, COSPI[16], t7);
        int u7 = halfBtf(COSPI[16], t6, COSPI[48], t7);

        int v0 = clip((long) t0 + t2);
        int v1 = clip((long) t1 + t3);
        int v2 = clip((long) t0 - t2);
        int v3 = clip((long) t1 - t3);
        int v4 = clip((long) u4 + u6);
        int v5 = clip((long) u5 + u7);
        int v6 = clip((long) u4 - u6);
        int v7 = clip((long) u5 - u7);

        int w2 = halfBtf(COSPI[32], v2, COSPI[32], v3);
        int w3 = halfBtf(COSPI[32], v2, -COSPI[32], v3);
        int w6 = halfBtf(COSPI[32], v6, COSPI[32], v7);
        int w7 = halfBtf(COSPI[32], v6, -COSPI[32], v7);

        output[0] = v0;
        output[1] = clip(-(long) v4);
        output[2] = w6;
        output[3] = clip(-(long) w2);
        output[4] = w3;
        output[5] = clip(-(long) w7);
        output[6] = v5;
        output[7] = clip(-(long) v1);
    }

    /// Reconstructs one one-dimensional `ADST_16` vector.
    ///
    /// @param input the dequantized `ADST_16` input vector
    /// @param output the reconstructed output vector
    private static void inverseAdst16(int[] input, int[] output) {
        int in0 = input[0];
        int in1 = input[1];
        int in2 = input[2];
        int in3 = input[3];
        int in4 = input[4];
        int in5 = input[5];
        int in6 = input[6];
        int in7 = input[7];
        int in8 = input[8];
        int in9 = input[9];
        int in10 = input[10];
        int in11 = input[11];
        int in12 = input[12];
        int in13 = input[13];
        int in14 = input[14];
        int in15 = input[15];

        int t0 = positiveRoundShift((long) in15 * (4091 - 4096) + (long) in0 * 201, 12) + in15;
        int t1 = positiveRoundShift((long) in15 * 201 - (long) in0 * (4091 - 4096), 12) - in0;
        int t2 = positiveRoundShift((long) in13 * (3973 - 4096) + (long) in2 * 995, 12) + in13;
        int t3 = positiveRoundShift((long) in13 * 995 - (long) in2 * (3973 - 4096), 12) - in2;
        int t4 = positiveRoundShift((long) in11 * (3703 - 4096) + (long) in4 * 1751, 12) + in11;
        int t5 = positiveRoundShift((long) in11 * 1751 - (long) in4 * (3703 - 4096), 12) - in4;
        int t6 = positiveRoundShift((long) in9 * 1645 + (long) in6 * 1220, 11);
        int t7 = positiveRoundShift((long) in9 * 1220 - (long) in6 * 1645, 11);
        int t8 = positiveRoundShift((long) in7 * 2751 + (long) in8 * (3035 - 4096), 12) + in8;
        int t9 = positiveRoundShift((long) in7 * (3035 - 4096) - (long) in8 * 2751, 12) + in7;
        int t10 = positiveRoundShift((long) in5 * 2106 + (long) in10 * (3513 - 4096), 12) + in10;
        int t11 = positiveRoundShift((long) in5 * (3513 - 4096) - (long) in10 * 2106, 12) + in5;
        int t12 = positiveRoundShift((long) in3 * 1380 + (long) in12 * (3857 - 4096), 12) + in12;
        int t13 = positiveRoundShift((long) in3 * (3857 - 4096) - (long) in12 * 1380, 12) + in3;
        int t14 = positiveRoundShift((long) in1 * 601 + (long) in14 * (4052 - 4096), 12) + in14;
        int t15 = positiveRoundShift((long) in1 * (4052 - 4096) - (long) in14 * 601, 12) + in1;

        int t0a = clip((long) t0 + t8);
        int t1a = clip((long) t1 + t9);
        int t2a = clip((long) t2 + t10);
        int t3a = clip((long) t3 + t11);
        int t4a = clip((long) t4 + t12);
        int t5a = clip((long) t5 + t13);
        int t6a = clip((long) t6 + t14);
        int t7a = clip((long) t7 + t15);
        int t8a = clip((long) t0 - t8);
        int t9a = clip((long) t1 - t9);
        int t10a = clip((long) t2 - t10);
        int t11a = clip((long) t3 - t11);
        int t12a = clip((long) t4 - t12);
        int t13a = clip((long) t5 - t13);
        int t14a = clip((long) t6 - t14);
        int t15a = clip((long) t7 - t15);

        t8 = positiveRoundShift((long) t8a * (4017 - 4096) + (long) t9a * 799, 12) + t8a;
        t9 = positiveRoundShift((long) t8a * 799 - (long) t9a * (4017 - 4096), 12) - t9a;
        t10 = positiveRoundShift((long) t10a * 2276 + (long) t11a * (3406 - 4096), 12) + t11a;
        t11 = positiveRoundShift((long) t10a * (3406 - 4096) - (long) t11a * 2276, 12) + t10a;
        t12 = positiveRoundShift((long) t13a * (4017 - 4096) - (long) t12a * 799, 12) + t13a;
        t13 = positiveRoundShift((long) t13a * 799 + (long) t12a * (4017 - 4096), 12) + t12a;
        t14 = positiveRoundShift((long) t15a * 2276 - (long) t14a * (3406 - 4096), 12) - t14a;
        t15 = positiveRoundShift((long) t15a * (3406 - 4096) + (long) t14a * 2276, 12) + t15a;

        t0 = clip((long) t0a + t4a);
        t1 = clip((long) t1a + t5a);
        t2 = clip((long) t2a + t6a);
        t3 = clip((long) t3a + t7a);
        t4 = clip((long) t0a - t4a);
        t5 = clip((long) t1a - t5a);
        t6 = clip((long) t2a - t6a);
        t7 = clip((long) t3a - t7a);
        t8a = clip((long) t8 + t12);
        t9a = clip((long) t9 + t13);
        t10a = clip((long) t10 + t14);
        t11a = clip((long) t11 + t15);
        t12a = clip((long) t8 - t12);
        t13a = clip((long) t9 - t13);
        t14a = clip((long) t10 - t14);
        t15a = clip((long) t11 - t15);

        t4a = positiveRoundShift((long) t4 * (3784 - 4096) + (long) t5 * 1567, 12) + t4;
        t5a = positiveRoundShift((long) t4 * 1567 - (long) t5 * (3784 - 4096), 12) - t5;
        t6a = positiveRoundShift((long) t7 * (3784 - 4096) - (long) t6 * 1567, 12) + t7;
        t7a = positiveRoundShift((long) t7 * 1567 + (long) t6 * (3784 - 4096), 12) + t6;
        t12 = positiveRoundShift((long) t12a * (3784 - 4096) + (long) t13a * 1567, 12) + t12a;
        t13 = positiveRoundShift((long) t12a * 1567 - (long) t13a * (3784 - 4096), 12) - t13a;
        t14 = positiveRoundShift((long) t15a * (3784 - 4096) - (long) t14a * 1567, 12) + t15a;
        t15 = positiveRoundShift((long) t15a * 1567 + (long) t14a * (3784 - 4096), 12) + t14a;

        output[0] = clip((long) t0 + t2);
        output[15] = clip(-(long) clip((long) t1 + t3));
        t2a = clip((long) t0 - t2);
        t3a = clip((long) t1 - t3);
        output[3] = clip(-(long) clip((long) t4a + t6a));
        output[12] = clip((long) t5a + t7a);
        t6 = clip((long) t4a - t6a);
        t7 = clip((long) t5a - t7a);
        output[1] = clip(-(long) clip((long) t8a + t10a));
        output[14] = clip((long) t9a + t11a);
        t10 = clip((long) t8a - t10a);
        t11 = clip((long) t9a - t11a);
        output[2] = clip((long) t12 + t14);
        output[13] = clip(-(long) clip((long) t13 + t15));
        t14a = clip((long) t12 - t14);
        t15a = clip((long) t13 - t15);

        output[7] = clip(-(long) positiveRoundShift((long) (t2a + t3a) * 181, 8));
        output[8] = positiveRoundShift((long) (t2a - t3a) * 181, 8);
        output[4] = positiveRoundShift((long) (t6 + t7) * 181, 8);
        output[11] = clip(-(long) positiveRoundShift((long) (t6 - t7) * 181, 8));
        output[6] = positiveRoundShift((long) (t10 + t11) * 181, 8);
        output[9] = clip(-(long) positiveRoundShift((long) (t10 - t11) * 181, 8));
        output[5] = clip(-(long) positiveRoundShift((long) (t14a + t15a) * 181, 8));
        output[10] = positiveRoundShift((long) (t14a - t15a) * 181, 8);
    }

    /// Reconstructs one supported one-dimensional inverse identity vector.
    ///
    /// @param input the dequantized input vector
    /// @param output the reconstructed output vector
    /// @param length the vector length in samples
    private static void inverseIdentity(int[] input, int[] output, int length) {
        switch (length) {
            case 4 -> {
                for (int i = 0; i < 4; i++) {
                    int value = input[i];
                    output[i] = clip((long) value + positiveRoundShift((long) value * 1697, 12));
                }
            }
            case 8 -> {
                for (int i = 0; i < 8; i++) {
                    output[i] = clip((long) input[i] * 2);
                }
            }
            case 16 -> {
                for (int i = 0; i < 16; i++) {
                    int value = input[i];
                    output[i] = clip((long) value * 2 + positiveRoundShift((long) value * 1697, 11));
                }
            }
            case 32 -> {
                for (int i = 0; i < 32; i++) {
                    output[i] = clip((long) input[i] * 4);
                }
            }
            default -> throw new IllegalStateException("Unsupported inverse identity length: " + length);
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
            case 4 -> INVERSE_DCT_BASIS_4;
            case 8 -> INVERSE_DCT_BASIS_8;
            case 16 -> INVERSE_DCT_BASIS_16;
            case 32 -> INVERSE_DCT_BASIS_32;
            case 64 -> INVERSE_DCT_BASIS_64;
            default -> throw new IllegalStateException("Unsupported inverse DCT basis length: " + length);
        };
    }

    /// Returns one cached inverse basis matrix for the supplied one-dimensional kernel.
    ///
    /// @param kernel the transform kernel to select
    /// @param length the requested vector length
    /// @return one cached inverse basis matrix for the supplied kernel and length
    private static double[][] inverseBasis(TransformKernel kernel, int length) {
        return switch (kernel) {
            case DCT -> inverseDctBasis(length);
            case ADST -> inverseAdstBasis(length);
            case FLIPADST -> inverseFlipAdstBasis(length);
            case WHT -> throw new IllegalStateException("WHT uses the dedicated lossless transform path");
            case IDENTITY -> identityBasis(length);
        };
    }

    /// Returns one cached orthonormal inverse-ADST basis matrix for the requested vector length.
    ///
    /// @param length the requested vector length
    /// @return one cached orthonormal inverse-ADST basis matrix
    private static double[][] inverseAdstBasis(int length) {
        return switch (length) {
            case 4 -> INVERSE_ADST_BASIS_4;
            case 8 -> INVERSE_ADST_BASIS_8;
            case 16 -> INVERSE_ADST_BASIS_16;
            case 32 -> INVERSE_ADST_BASIS_32;
            case 64 -> INVERSE_ADST_BASIS_64;
            default -> throw new IllegalStateException("Unsupported inverse ADST basis length: " + length);
        };
    }

    /// Returns one cached flipped inverse-ADST basis matrix for the requested vector length.
    ///
    /// @param length the requested vector length
    /// @return one cached flipped inverse-ADST basis matrix
    private static double[][] inverseFlipAdstBasis(int length) {
        return switch (length) {
            case 4 -> INVERSE_FLIPADST_BASIS_4;
            case 8 -> INVERSE_FLIPADST_BASIS_8;
            case 16 -> INVERSE_FLIPADST_BASIS_16;
            case 32 -> INVERSE_FLIPADST_BASIS_32;
            case 64 -> INVERSE_FLIPADST_BASIS_64;
            default -> throw new IllegalStateException("Unsupported inverse FLIPADST basis length: " + length);
        };
    }

    /// Returns one cached identity basis matrix for the requested vector length.
    ///
    /// @param length the requested vector length
    /// @return one cached identity basis matrix
    private static double[][] identityBasis(int length) {
        return switch (length) {
            case 4 -> IDENTITY_BASIS_4;
            case 8 -> IDENTITY_BASIS_8;
            case 16 -> IDENTITY_BASIS_16;
            case 32 -> IDENTITY_BASIS_32;
            case 64 -> IDENTITY_BASIS_64;
            default -> throw new IllegalStateException("Unsupported identity basis length: " + length);
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

    /// Creates one orthonormal inverse-ADST basis matrix for the supplied vector length.
    ///
    /// The generated rows use the asymmetric sine basis with denominator `2 * n + 1`.
    /// `FLIPADST` reuses this matrix with reversed spatial rows.
    ///
    /// @param length the vector length in samples
    /// @return one orthonormal inverse-ADST basis matrix for the supplied vector length
    private static double[][] createInverseAdstBasis(int length) {
        double[][] basis = new double[length][length];
        double scale = Math.sqrt(4.0 / (2.0 * length + 1.0));
        double denominator = 2.0 * length + 1.0;
        for (int row = 0; row < length; row++) {
            for (int frequency = 0; frequency < length; frequency++) {
                basis[row][frequency] = scale
                        * Math.sin(((2.0 * row + 1.0) * (frequency + 1.0) * Math.PI) / denominator);
            }
        }
        return basis;
    }

    /// Creates one flipped basis matrix by reversing the spatial rows of an existing basis.
    ///
    /// @param basis the basis whose spatial rows should be flipped
    /// @return one flipped basis matrix
    private static double[][] createFlippedBasis(double[][] basis) {
        int length = basis.length;
        double[][] flipped = new double[length][length];
        for (int row = 0; row < length; row++) {
            System.arraycopy(basis[length - 1 - row], 0, flipped[row], 0, length);
        }
        return flipped;
    }

    /// Creates one identity basis matrix for the supplied vector length.
    ///
    /// @param length the vector length in samples
    /// @return one identity basis matrix for the supplied vector length
    private static double[][] createIdentityBasis(int length) {
        double[][] basis = new double[length][length];
        for (int i = 0; i < length; i++) {
            basis[i][i] = 1.0;
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
        return positiveRoundShift((long) weight0 * value0 + (long) weight1 * value1, INV_COS_BIT);
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

    /// Applies the positive-bias arithmetic shifts used by `dav1d` inverse-transform kernels.
    ///
    /// @param value the signed value to shift
    /// @param bitCount the positive number of bits to shift away, or `0`
    /// @return the rounded signed result
    private static int positiveRoundShift(long value, int bitCount) {
        if (bitCount < 0) {
            throw new IllegalArgumentException("bitCount < 0: " + bitCount);
        }
        if (bitCount == 0) {
            return saturatedInt(value);
        }
        return saturatedInt((value + (1L << (bitCount - 1))) >> bitCount);
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
        ClipRange clipRange = ACTIVE_CLIP_RANGE.get();
        return clipToRange(saturatedInt(value), clipRange);
    }

    /// Clamps one intermediate value into one explicit inverse-transform clip range.
    ///
    /// @param value the value to clamp
    /// @param clipRange the active clip range
    /// @return the clamped value
    private static int clipToRange(int value, ClipRange clipRange) {
        return Math.max(clipRange.minimum(), Math.min(clipRange.maximum(), value));
    }

    /// Runs one exact inverse-transform stage while overriding the active clip range.
    ///
    /// @param clipRange the temporary clip range
    /// @param action the stage body to execute
    private static void withActiveClipRange(ClipRange clipRange, ClipRangeAction action) {
        ClipRange previous = ACTIVE_CLIP_RANGE.get();
        ACTIVE_CLIP_RANGE.set(clipRange);
        try {
            action.run();
        } finally {
            ACTIVE_CLIP_RANGE.set(previous);
        }
    }

    /// Returns the dav1d row-pass clip range for one supported decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the stage-local row-pass clip range
    private static ClipRange rowClipRange(int bitDepth) {
        int minimum = -(1 << (bitDepth + 7));
        return new ClipRange(minimum, -minimum - 1);
    }

    /// Returns the dav1d column-domain clip range for one supported decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the stage-local column-domain clip range
    private static ClipRange columnClipRange(int bitDepth) {
        if (bitDepth == 8) {
            return new ClipRange(Short.MIN_VALUE, Short.MAX_VALUE);
        }
        int minimum = -(1 << (bitDepth + 5));
        return new ClipRange(minimum, -minimum - 1);
    }

    /// Returns the checked transform area for the current supported subset.
    ///
    /// @param transformSize the transform size to validate
    /// @return the transform area in samples
    private static int checkedTransformArea(TransformSize transformSize) {
        return transformSize.widthPixels() * transformSize.heightPixels();
    }

    /// One clip range used by the exact inverse-transform kernels.
    ///
    /// @param minimum the inclusive minimum value
    /// @param maximum the inclusive maximum value
    private record ClipRange(int minimum, int maximum) {
    }

    /// Functional interface for one clip-range-scoped inverse-transform stage.
    @FunctionalInterface
    private interface ClipRangeAction {
        /// Runs one exact inverse-transform stage.
        void run();
    }
}
