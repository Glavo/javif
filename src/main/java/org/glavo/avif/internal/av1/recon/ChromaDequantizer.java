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

/// Minimal chroma dequantizer for the first residual-producing reconstruction path.
///
/// The current implementation matches the AV1 `8-bit`, `10-bit`, and `12-bit` QTX lookup tables
/// shared by luma and chroma coefficients. Plane-specific behavior is expressed only through the
/// caller-supplied DC/AC delta quantizers.
@NotNullByDefault
final class ChromaDequantizer {
    /// Prevents instantiation of this utility class.
    private ChromaDequantizer() {
    }

    /// Dequantizes one chroma transform residual unit into transform-domain coefficients.
    ///
    /// DC and AC both apply the caller-provided plane-specific delta quantizers on top of the
    /// block-local `qindex`. All-zero units return a fresh all-zero array of the matching
    /// transform area.
    ///
    /// @param residualUnit the chroma residual unit to dequantize
    /// @param context the block-local dequantization context
    /// @return one dequantized transform-domain coefficient block in natural raster order
    static int[] dequantize(TransformResidualUnit residualUnit, Context context) {
        TransformResidualUnit nonNullResidualUnit = Objects.requireNonNull(residualUnit, "residualUnit");
        Context nonNullContext = Objects.requireNonNull(context, "context");
        if (nonNullContext.bitDepth() != 8
                && nonNullContext.bitDepth() != 10
                && nonNullContext.bitDepth() != 12) {
            throw new IllegalStateException("Unsupported chroma dequantization bit depth: " + nonNullContext.bitDepth());
        }

        int coefficientCount = nonNullResidualUnit.size().widthPixels() * nonNullResidualUnit.size().heightPixels();
        if (nonNullResidualUnit.allZero()) {
            return new int[coefficientCount];
        }

        int[] quantizedCoefficients = nonNullResidualUnit.coefficients();
        int[] dequantizedCoefficients = new int[quantizedCoefficients.length];
        int dcQuantizer = QuantizerTables.dcQuantizer(
                nonNullContext.qIndex() + nonNullContext.dcDelta(),
                nonNullContext.bitDepth()
        );
        int acQuantizer = QuantizerTables.acQuantizer(
                nonNullContext.qIndex() + nonNullContext.acDelta(),
                nonNullContext.bitDepth()
        );
        dequantizedCoefficients[0] = scaledCoefficient(quantizedCoefficients[0], dcQuantizer);
        for (int coefficientIndex = 1; coefficientIndex < quantizedCoefficients.length; coefficientIndex++) {
            dequantizedCoefficients[coefficientIndex] = scaledCoefficient(quantizedCoefficients[coefficientIndex], acQuantizer);
        }
        return dequantizedCoefficients;
    }

    /// Multiplies one quantized coefficient by one dequantizer with saturation to `int`.
    ///
    /// @param coefficient the quantized transform coefficient
    /// @param quantizer the active dequantizer
    /// @return the scaled transform coefficient
    private static int scaledCoefficient(int coefficient, int quantizer) {
        long scaled = (long) coefficient * quantizer;
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scaled;
    }

    /// Minimal chroma dequantization context used by the current reconstruction path.
    ///
    /// The block-local `qindex` already includes any superblock-level delta-q updates. Plane-local
    /// DC and AC deltas are carried explicitly so U and V can reuse the same logic with different
    /// quantizer adjustments.
    @NotNullByDefault
    static final class Context {
        /// The block-local chroma AC quantizer index in `[0, 255]`.
        private final int qIndex;

        /// The plane-local DC delta quantizer.
        private final int dcDelta;

        /// The plane-local AC delta quantizer.
        private final int acDelta;

        /// The decoded sample bit depth.
        private final int bitDepth;

        /// Creates one chroma dequantization context.
        ///
        /// @param qIndex the block-local chroma AC quantizer index in `[0, 255]`
        /// @param dcDelta the plane-local DC delta quantizer
        /// @param acDelta the plane-local AC delta quantizer
        /// @param bitDepth the decoded sample bit depth
        Context(int qIndex, int dcDelta, int acDelta, int bitDepth) {
            if (qIndex < 0 || qIndex > 255) {
                throw new IllegalArgumentException("qIndex out of range: " + qIndex);
            }
            if (bitDepth <= 0) {
                throw new IllegalArgumentException("bitDepth <= 0: " + bitDepth);
            }
            this.qIndex = qIndex;
            this.dcDelta = dcDelta;
            this.acDelta = acDelta;
            this.bitDepth = bitDepth;
        }

        /// Returns the block-local chroma AC quantizer index in `[0, 255]`.
        ///
        /// @return the block-local chroma AC quantizer index in `[0, 255]`
        int qIndex() {
            return qIndex;
        }

        /// Returns the plane-local DC delta quantizer.
        ///
        /// @return the plane-local DC delta quantizer
        int dcDelta() {
            return dcDelta;
        }

        /// Returns the plane-local AC delta quantizer.
        ///
        /// @return the plane-local AC delta quantizer
        int acDelta() {
            return acDelta;
        }

        /// Returns the decoded sample bit depth.
        ///
        /// @return the decoded sample bit depth
        int bitDepth() {
            return bitDepth;
        }
    }
}
