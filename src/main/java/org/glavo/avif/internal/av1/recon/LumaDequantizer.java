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

import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Minimal luma dequantizer for the first residual-producing reconstruction path.
///
/// The current implementation matches the AV1 `8-bit`, `10-bit`, and `12-bit` QTX lookup tables
/// for luma coefficients, optional frame-level quantization matrices, the transform-size
/// dequantization shift, and the coefficient saturation behavior used by dav1d.
@NotNullByDefault
final class LumaDequantizer {
    /// Prevents instantiation of this utility class.
    private LumaDequantizer() {
    }

    /// Dequantizes one luma transform residual unit into transform-domain coefficients.
    ///
    /// DC uses the frame-level luma DC delta while AC uses the block-local `qindex` unchanged.
    /// All-zero units return a fresh all-zero array of the matching transform area.
    ///
    /// @param residualUnit the luma residual unit to dequantize
    /// @param context the block-local dequantization context
    /// @return one dequantized transform-domain coefficient block in natural raster order
    static int[] dequantize(TransformResidualUnit residualUnit, Context context) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        Context nonNullContext = Objects.requireNonNull(context, "context");
        if (nonNullContext.bitDepth() != 8
                && nonNullContext.bitDepth() != 10
                && nonNullContext.bitDepth() != 12) {
            throw new IllegalStateException("Unsupported luma dequantization bit depth: " + nonNullContext.bitDepth());
        }

        int coefficientCount = nonNullResidualUnit.size().widthPixels() * nonNullResidualUnit.size().heightPixels();
        if (nonNullResidualUnit.allZero()) {
            return new int[coefficientCount];
        }

        int[] quantizedCoefficients = nonNullResidualUnit.coefficients();
        int[] dequantizedCoefficients = new int[quantizedCoefficients.length];
        int dcQuantizer = lumaDcQuantizer(nonNullContext);
        int acQuantizer = lumaAcQuantizer(nonNullContext);
        int dequantizationShift = QuantizerTables.dequantizationShift(nonNullResidualUnit.size());
        byte @Nullable @Unmodifiable [] quantizationMatrix = quantizationMatrix(nonNullResidualUnit, nonNullContext);
        dequantizedCoefficients[0] = scaledCoefficient(
                quantizedCoefficients[0],
                dcQuantizer,
                dequantizationShift,
                nonNullContext.bitDepth(),
                matrixValue(quantizationMatrix, nonNullResidualUnit.size(), 0)
        );
        for (int coefficientIndex = 1; coefficientIndex < quantizedCoefficients.length; coefficientIndex++) {
            dequantizedCoefficients[coefficientIndex] = scaledCoefficient(
                    quantizedCoefficients[coefficientIndex],
                    acQuantizer,
                    dequantizationShift,
                    nonNullContext.bitDepth(),
                    matrixValue(quantizationMatrix, nonNullResidualUnit.size(), coefficientIndex)
            );
        }
        return dequantizedCoefficients;
    }

    /// Returns the active luma DC quantizer after clamping the derived qindex.
    ///
    /// @param context the block-local dequantization context
    /// @return the active luma DC quantizer
    private static int lumaDcQuantizer(Context context) {
        return QuantizerTables.dcQuantizer(context.qIndex() + context.yDcDelta(), context.bitDepth());
    }

    /// Returns the active luma AC quantizer for the block-local qindex.
    ///
    /// @param context the block-local dequantization context
    /// @return the active luma AC quantizer
    private static int lumaAcQuantizer(Context context) {
        return QuantizerTables.acQuantizer(context.qIndex(), context.bitDepth());
    }

    /// Returns the active quantization matrix for one residual unit, or `null` when no matrix applies.
    ///
    /// @param residualUnit the residual unit to dequantize
    /// @param context the block-local dequantization context
    /// @return the active quantization matrix, or `null` when no matrix applies
    private static byte @Nullable @Unmodifiable [] quantizationMatrix(
            TransformResidualUnit residualUnit,
            Context context
    ) {
        if (!context.useQuantizationMatrices() || !usesQuantizationMatrix(residualUnit.transformType())) {
            return null;
        }
        return QuantizationMatrixTables.matrix(context.quantizationMatrix(), false, residualUnit.size());
    }

    /// Returns whether one transform type applies frame-level quantization matrices.
    ///
    /// @param transformType the transform type to test
    /// @return whether the transform type applies frame-level quantization matrices
    private static boolean usesQuantizationMatrix(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case DCT_DCT,
                 ADST_DCT,
                 DCT_ADST,
                 ADST_ADST,
                 FLIPADST_DCT,
                 DCT_FLIPADST,
                 FLIPADST_FLIPADST,
                 ADST_FLIPADST,
                 FLIPADST_ADST -> true;
            case IDTX,
                 V_DCT,
                 H_DCT,
                 V_ADST,
                 H_ADST,
                 V_FLIPADST,
                 H_FLIPADST,
                 WHT_WHT -> false;
        };
    }

    /// Returns the matrix scale at one natural coefficient index.
    ///
    /// A neutral scale is returned when no matrix applies or when a 64-wide/high transform index is
    /// outside the entropy-coded 32-coefficient span.
    ///
    /// @param quantizationMatrix the active quantization matrix, or `null`
    /// @param transformSize the active transform size
    /// @param coefficientIndex the natural raster coefficient index
    /// @return the quantization-matrix scale, or `32` for neutral scaling
    private static int matrixValue(
            byte @Nullable @Unmodifiable [] quantizationMatrix,
            TransformSize transformSize,
            int coefficientIndex
    ) {
        if (quantizationMatrix == null) {
            return 32;
        }
        int transformWidth = transformSize.widthPixels();
        int matrixWidth = QuantizationMatrixTables.matrixWidth(transformSize);
        int matrixHeight = QuantizationMatrixTables.matrixHeight(transformSize);
        int x = coefficientIndex % transformWidth;
        int y = coefficientIndex / transformWidth;
        if (x >= matrixWidth || y >= matrixHeight) {
            return 32;
        }
        return quantizationMatrix[y * matrixWidth + x] & 0xFF;
    }

    /// Multiplies one quantized coefficient by one dequantizer with AV1 coefficient saturation.
    ///
    /// @param coefficient the quantized transform coefficient
    /// @param quantizer the active dequantizer
    /// @param dequantizationShift the transform-size dequantization shift
    /// @param bitDepth the decoded sample bit depth
    /// @param matrixValue the active quantization-matrix scale, or `32` for neutral scaling
    /// @return the scaled transform coefficient
    private static int scaledCoefficient(
            int coefficient,
            int quantizer,
            int dequantizationShift,
            int bitDepth,
            int matrixValue
    ) {
        long magnitude = coefficient < 0 ? -(long) coefficient : coefficient;
        boolean extendedToken = magnitude >= 15;
        if (extendedToken) {
            magnitude &= 0xFFFFF;
        }
        int effectiveQuantizer = (quantizer * matrixValue + 16) >> 5;
        long scaled = effectiveQuantizer * magnitude;
        if (extendedToken) {
            scaled &= 0xFFFFFFL;
        }
        scaled >>= dequantizationShift;
        scaled = Math.min(scaled, coefficientMaximum(bitDepth) + (coefficient < 0 ? 1 : 0));
        if (coefficient < 0) {
            scaled = -scaled;
        }
        return (int) scaled;
    }

    /// Returns the maximum positive transform coefficient for one AV1 bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the maximum positive transform coefficient
    private static int coefficientMaximum(int bitDepth) {
        return (128 << bitDepth) - 1;
    }

    /// Minimal luma dequantization context used by the current reconstruction path.
    ///
    /// The block-local `qindex` already includes any superblock-level delta-q updates. The luma DC
    /// delta remains frame-level state. Bit depth is carried explicitly so callers can wire the same
    /// shape through broader reconstruction code even though the current implementation only accepts
    /// `8-bit`, `10-bit`, and `12-bit` inputs.
    @NotNullByDefault
    static final class Context {
        /// The block-local luma AC quantizer index in `[0, 255]`.
        private final int qIndex;

        /// The frame-level luma DC delta quantizer.
        private final int yDcDelta;

        /// The decoded sample bit depth.
        private final int bitDepth;

        /// Whether frame-level quantization matrices are enabled.
        private final boolean useQuantizationMatrices;

        /// The luma quantization matrix index in `[0, 15]`.
        private final int quantizationMatrix;

        /// Creates one luma dequantization context.
        ///
        /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
        /// @param yDcDelta the frame-level luma DC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        Context(int qIndex, int yDcDelta, int bitDepth) {
            this(qIndex, yDcDelta, bitDepth, false, 0);
        }

        /// Creates one luma dequantization context.
        ///
        /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
        /// @param yDcDelta the frame-level luma DC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        /// @param useQuantizationMatrices whether frame-level quantization matrices are enabled
        /// @param quantizationMatrix the luma quantization matrix index in `[0, 15]`
        Context(
                int qIndex,
                int yDcDelta,
                int bitDepth,
                boolean useQuantizationMatrices,
                int quantizationMatrix
        ) {
            if (qIndex < 0 || qIndex > 255) {
                throw new IllegalArgumentException("qIndex out of range: " + qIndex);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth <= 0: " + bitDepth);
            }
            if (quantizationMatrix < 0 || quantizationMatrix > 15) {
                throw new IllegalArgumentException("quantizationMatrix out of range: " + quantizationMatrix);
            }
            this.qIndex = qIndex;
            this.yDcDelta = yDcDelta;
            this.bitDepth = bitDepth;
            this.useQuantizationMatrices = useQuantizationMatrices;
            this.quantizationMatrix = quantizationMatrix;
        }

        /// Returns the block-local luma AC quantizer index in `[0, 255]`.
        ///
        /// @return the block-local luma AC quantizer index in `[0, 255]`
        int qIndex() {
            return qIndex;
        }

        /// Returns the frame-level luma DC delta quantizer.
        ///
        /// @return the frame-level luma DC delta quantizer
        int yDcDelta() {
            return yDcDelta;
        }

        /// Returns the decoded sample bit depth.
        ///
        /// @return the decoded sample bit depth
        int bitDepth() {
            return bitDepth;
        }

        /// Returns whether frame-level quantization matrices are enabled.
        ///
        /// @return whether frame-level quantization matrices are enabled
        boolean useQuantizationMatrices() {
            return useQuantizationMatrices;
        }

        /// Returns the luma quantization matrix index in `[0, 15]`.
        ///
        /// @return the luma quantization matrix index in `[0, 15]`
        int quantizationMatrix() {
            return quantizationMatrix;
        }
    }
}
