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
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Minimal luma dequantizer for the first residual-producing reconstruction path.
///
/// The current implementation matches the AV1 `8-bit`, `10-bit`, and `12-bit` QTX lookup tables
/// for luma coefficients, including the transform-size dequantization shift applied by dav1d for
/// large transform contexts. It exposes only the data the current rollout already has available at
/// reconstruction time: block-local `qindex`, frame-level luma DC delta, and bit depth.
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
        dequantizedCoefficients[0] = scaledCoefficient(quantizedCoefficients[0], dcQuantizer, dequantizationShift);
        for (int coefficientIndex = 1; coefficientIndex < quantizedCoefficients.length; coefficientIndex++) {
            dequantizedCoefficients[coefficientIndex] = scaledCoefficient(
                    quantizedCoefficients[coefficientIndex],
                    acQuantizer,
                    dequantizationShift
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

    /// Multiplies one quantized coefficient by one dequantizer with saturation to `int`.
    ///
    /// @param coefficient the quantized transform coefficient
    /// @param quantizer the active dequantizer
    /// @param dequantizationShift the transform-size dequantization shift
    /// @return the scaled transform coefficient
    private static int scaledCoefficient(int coefficient, int quantizer, int dequantizationShift) {
        long magnitude = coefficient < 0 ? -(long) coefficient : coefficient;
        long scaled = (magnitude * quantizer) >> dequantizationShift;
        if (coefficient < 0) {
            scaled = -scaled;
        }
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scaled;
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

        /// Creates one luma dequantization context.
        ///
        /// @param qIndex the block-local luma AC quantizer index in `[0, 255]`
        /// @param yDcDelta the frame-level luma DC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        Context(int qIndex, int yDcDelta, int bitDepth) {
            if (qIndex < 0 || qIndex > 255) {
                throw new IllegalArgumentException("qIndex out of range: " + qIndex);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth <= 0: " + bitDepth);
            }
            this.qIndex = qIndex;
            this.yDcDelta = yDcDelta;
            this.bitDepth = bitDepth;
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
    }
}
