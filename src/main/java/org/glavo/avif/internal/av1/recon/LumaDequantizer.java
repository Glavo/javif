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
/// The current implementation matches the AV1 8-bit QTX lookup tables for luma coefficients and
/// exposes only the data the current rollout already has available at reconstruction time:
/// block-local `qindex`, frame-level luma DC delta, and bit depth.
@NotNullByDefault
final class LumaDequantizer {
    /// The 8-bit AV1 QTX lookup table for luma DC coefficients.
    private static final int[] DC_QLOOKUP_8_QTX = {
            4, 8, 8, 9, 10, 11, 12, 12, 13, 14, 15, 16, 17, 18,
            19, 19, 20, 21, 22, 23, 24, 25, 26, 26, 27, 28, 29, 30,
            31, 32, 32, 33, 34, 35, 36, 37, 38, 38, 39, 40, 41, 42,
            43, 43, 44, 45, 46, 47, 48, 48, 49, 50, 51, 52, 53, 53,
            54, 55, 56, 57, 57, 58, 59, 60, 61, 62, 62, 63, 64, 65,
            66, 66, 67, 68, 69, 70, 70, 71, 72, 73, 74, 74, 75, 76,
            77, 78, 78, 79, 80, 81, 81, 82, 83, 84, 85, 85, 87, 88,
            90, 92, 93, 95, 96, 98, 99, 101, 102, 104, 105, 107, 108, 110,
            111, 113, 114, 116, 117, 118, 120, 121, 123, 125, 127, 129, 131, 134,
            136, 138, 140, 142, 144, 146, 148, 150, 152, 154, 156, 158, 161, 164,
            166, 169, 172, 174, 177, 180, 182, 185, 187, 190, 192, 195, 199, 202,
            205, 208, 211, 214, 217, 220, 223, 226, 230, 233, 237, 240, 243, 247,
            250, 253, 257, 261, 265, 269, 272, 276, 280, 284, 288, 292, 296, 300,
            304, 309, 313, 317, 322, 326, 330, 335, 340, 344, 349, 354, 359, 364,
            369, 374, 379, 384, 389, 395, 400, 406, 411, 417, 423, 429, 435, 441,
            447, 454, 461, 467, 475, 482, 489, 497, 505, 513, 522, 530, 539, 549,
            559, 569, 579, 590, 602, 614, 626, 640, 654, 668, 684, 700, 717, 736,
            755, 775, 796, 819, 843, 869, 896, 925, 955, 988, 1022, 1058, 1098, 1139,
            1184, 1232, 1282, 1336
    };

    /// The 8-bit AV1 QTX lookup table for luma AC coefficients.
    private static final int[] AC_QLOOKUP_8_QTX = {
            4, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
            33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58,
            59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71,
            72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84,
            85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97,
            98, 99, 100, 101, 102, 104, 106, 108, 110, 112, 114, 116, 118,
            120, 122, 124, 126, 128, 130, 132, 134, 136, 138, 140, 142, 144,
            146, 148, 150, 152, 155, 158, 161, 164, 167, 170, 173, 176, 179,
            182, 185, 188, 191, 194, 197, 200, 203, 207, 211, 215, 219, 223,
            227, 231, 235, 239, 243, 247, 251, 255, 260, 265, 270, 275, 280,
            285, 290, 295, 300, 305, 311, 317, 323, 329, 335, 341, 347, 353,
            359, 366, 373, 380, 387, 394, 401, 408, 416, 424, 432, 440, 448,
            456, 465, 474, 483, 492, 501, 510, 520, 530, 540, 550, 560, 571,
            582, 593, 604, 615, 627, 639, 651, 663, 676, 689, 702, 715, 729,
            743, 757, 771, 786, 801, 816, 832, 848, 864, 881, 898, 915, 933,
            951, 969, 988, 1007, 1026, 1046, 1066, 1087, 1108, 1129, 1151, 1173, 1196,
            1219, 1243, 1267, 1292, 1317, 1343, 1369, 1396, 1423, 1451, 1479, 1508, 1537,
            1567, 1597, 1628, 1660, 1692, 1725, 1759, 1793, 1828
    };

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
        if (nonNullContext.bitDepth() != 8) {
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
        dequantizedCoefficients[0] = scaledCoefficient(quantizedCoefficients[0], dcQuantizer);
        for (int coefficientIndex = 1; coefficientIndex < quantizedCoefficients.length; coefficientIndex++) {
            dequantizedCoefficients[coefficientIndex] = scaledCoefficient(quantizedCoefficients[coefficientIndex], acQuantizer);
        }
        return dequantizedCoefficients;
    }

    /// Returns the active 8-bit luma DC quantizer after clamping the derived qindex.
    ///
    /// @param context the block-local dequantization context
    /// @return the active 8-bit luma DC quantizer
    private static int lumaDcQuantizer(Context context) {
        return DC_QLOOKUP_8_QTX[clampedQIndex(context.qIndex() + context.yDcDelta())];
    }

    /// Returns the active 8-bit luma AC quantizer for the block-local qindex.
    ///
    /// @param context the block-local dequantization context
    /// @return the active 8-bit luma AC quantizer
    private static int lumaAcQuantizer(Context context) {
        return AC_QLOOKUP_8_QTX[context.qIndex()];
    }

    /// Clamps one derived quantizer index into the legal AV1 lookup-table range.
    ///
    /// @param qIndex the derived quantizer index to clamp
    /// @return the legal lookup-table index
    private static int clampedQIndex(int qIndex) {
        if (qIndex <= 0) {
            return 0;
        }
        if (qIndex >= 255) {
            return 255;
        }
        return qIndex;
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

    /// Minimal luma dequantization context used by the current reconstruction path.
    ///
    /// The block-local `qindex` already includes any superblock-level delta-q updates. The luma DC
    /// delta remains frame-level state. Bit depth is carried explicitly so callers can wire the same
    /// shape through broader reconstruction code even though the current implementation only accepts
    /// 8-bit inputs.
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
