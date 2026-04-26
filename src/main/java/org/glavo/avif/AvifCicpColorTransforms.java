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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Pure-Java CICP transfer-function and RGB-primary conversion helpers.
///
/// The helper intentionally covers the common AVIF signaling used by libavif fixtures while
/// keeping ICC handling out of runtime transforms. Unsupported or unspecified primaries fall back
/// to BT.709/sRGB because the current public RGB frame API does not carry an output color space.
@NotNullByDefault
final class AvifCicpColorTransforms {
    /// BT.709/sRGB color primaries.
    private static final int PRIMARIES_BT709 = 1;
    /// Unspecified CICP color primaries.
    private static final int PRIMARIES_UNSPECIFIED = 2;
    /// BT.470 System M color primaries.
    private static final int PRIMARIES_BT470M = 4;
    /// BT.470 System B/G color primaries.
    private static final int PRIMARIES_BT470BG = 5;
    /// SMPTE 170M color primaries.
    private static final int PRIMARIES_SMPTE170M = 6;
    /// SMPTE 240M color primaries.
    private static final int PRIMARIES_SMPTE240M = 7;
    /// BT.2020 color primaries.
    private static final int PRIMARIES_BT2020 = 9;
    /// SMPTE RP 431-2 P3 color primaries.
    private static final int PRIMARIES_SMPTE431 = 11;
    /// SMPTE EG 432-1 Display P3 color primaries.
    private static final int PRIMARIES_SMPTE432 = 12;
    /// Unspecified CICP transfer characteristics.
    private static final int TRANSFER_UNSPECIFIED = 2;
    /// sRGB transfer characteristics.
    private static final int TRANSFER_SRGB = 13;
    /// Linear transfer characteristics.
    private static final int TRANSFER_LINEAR = 8;
    /// SMPTE ST 2084 perceptual quantizer transfer characteristics.
    private static final int TRANSFER_PQ = 16;
    /// ARIB STD-B67 hybrid log-gamma transfer characteristics.
    private static final int TRANSFER_HLG = 18;
    /// sRGB linear-to-gamma threshold.
    private static final double SRGB_LINEAR_THRESHOLD = 0.0031308;
    /// sRGB gamma-to-linear threshold.
    private static final double SRGB_GAMMA_THRESHOLD = 0.04045;
    /// HLG transfer function constant `a`.
    private static final double HLG_A = 0.17883277;
    /// HLG transfer function constant `b`.
    private static final double HLG_B = 0.28466892;
    /// HLG transfer function constant `c`.
    private static final double HLG_C = 0.55991073;
    /// PQ transfer function constant `m1`.
    private static final double PQ_M1 = 2610.0 / 16384.0;
    /// PQ transfer function constant `m2`.
    private static final double PQ_M2 = 2523.0 / 32.0;
    /// PQ transfer function constant `c1`.
    private static final double PQ_C1 = 3424.0 / 4096.0;
    /// PQ transfer function constant `c2`.
    private static final double PQ_C2 = 2413.0 / 128.0;
    /// PQ transfer function constant `c3`.
    private static final double PQ_C3 = 2392.0 / 128.0;
    /// Identity RGB conversion matrix.
    private static final RgbMatrix IDENTITY = new RgbMatrix(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
    );

    /// Prevents instantiation of this utility class.
    private AvifCicpColorTransforms() {
    }

    /// Converts one gamma-encoded RGB channel to linear light.
    ///
    /// @param value the normalized gamma-encoded value
    /// @param colorInfo the CICP color information, or `null` for sRGB fallback
    /// @return the normalized linear-light value
    static double gammaToLinear(double value, @Nullable AvifColorInfo colorInfo) {
        double clamped = clampUnit(value);
        return switch (transferCharacteristics(colorInfo)) {
            case TRANSFER_LINEAR -> clamped;
            case 1, 6, 14, 15 -> bt709ToLinear(clamped);
            case 4 -> Math.pow(clamped, 2.2);
            case 5 -> Math.pow(clamped, 2.8);
            case TRANSFER_PQ -> pqToLinear(clamped);
            case TRANSFER_HLG -> hlgToLinear(clamped);
            case TRANSFER_SRGB, TRANSFER_UNSPECIFIED -> srgbToLinear(clamped);
            default -> srgbToLinear(clamped);
        };
    }

    /// Converts one linear-light RGB channel to gamma encoding.
    ///
    /// @param value the normalized linear-light value
    /// @param colorInfo the CICP color information, or `null` for sRGB fallback
    /// @return the normalized gamma-encoded value
    static double linearToGamma(double value, @Nullable AvifColorInfo colorInfo) {
        double clamped = clampUnit(value);
        return switch (transferCharacteristics(colorInfo)) {
            case TRANSFER_LINEAR -> clamped;
            case 1, 6, 14, 15 -> linearToBt709(clamped);
            case 4 -> Math.pow(clamped, 1.0 / 2.2);
            case 5 -> Math.pow(clamped, 1.0 / 2.8);
            case TRANSFER_PQ -> linearToPq(clamped);
            case TRANSFER_HLG -> linearToHlg(clamped);
            case TRANSFER_SRGB, TRANSFER_UNSPECIFIED -> linearToSrgb(clamped);
            default -> linearToSrgb(clamped);
        };
    }

    /// Builds a linear-light RGB conversion matrix between two CICP primary sets.
    ///
    /// @param sourceInfo the source color information, or `null` for BT.709/sRGB fallback
    /// @param targetInfo the target color information, or `null` for BT.709/sRGB fallback
    /// @return an RGB conversion matrix
    static RgbMatrix conversionMatrix(@Nullable AvifColorInfo sourceInfo, @Nullable AvifColorInfo targetInfo) {
        int sourcePrimaries = colorPrimaries(sourceInfo);
        int targetPrimaries = colorPrimaries(targetInfo);
        if (sourcePrimaries == targetPrimaries) {
            return IDENTITY;
        }
        @Nullable Primaries source = primaries(sourcePrimaries);
        @Nullable Primaries target = primaries(targetPrimaries);
        if (source == null || target == null) {
            return IDENTITY;
        }
        @Nullable RgbMatrix sourceToXyz = source.rgbToXyzMatrix();
        @Nullable RgbMatrix targetToXyz = target.rgbToXyzMatrix();
        if (sourceToXyz == null || targetToXyz == null) {
            return IDENTITY;
        }
        @Nullable RgbMatrix xyzToTarget = targetToXyz.inverse();
        return xyzToTarget != null ? xyzToTarget.multiply(sourceToXyz) : IDENTITY;
    }

    /// Returns whether two CICP descriptors have the same effective RGB color space.
    ///
    /// RGB frame output is affected by color primaries and transfer characteristics. Matrix
    /// coefficients and sample range describe YUV storage and are intentionally ignored here.
    ///
    /// @param first the first color information, or `null`
    /// @param second the second color information, or `null`
    /// @return whether both descriptors resolve to the same effective RGB color space
    static boolean sameEffectiveRgbColorSpace(@Nullable AvifColorInfo first, @Nullable AvifColorInfo second) {
        return colorPrimaries(first) == colorPrimaries(second)
                && transferCharacteristics(first) == transferCharacteristics(second);
    }

    /// Clamps a sample to the closed unit interval.
    ///
    /// @param value the sample value
    /// @return the clamped value
    static double clampUnit(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    /// Returns the CICP color primaries with a BT.709 fallback.
    ///
    /// @param colorInfo the color information, or `null`
    /// @return the effective color primaries
    private static int colorPrimaries(@Nullable AvifColorInfo colorInfo) {
        if (colorInfo == null || colorInfo.colorPrimaries() == PRIMARIES_UNSPECIFIED) {
            return PRIMARIES_BT709;
        }
        return colorInfo.colorPrimaries();
    }

    /// Returns the CICP transfer characteristics with an sRGB fallback.
    ///
    /// @param colorInfo the color information, or `null`
    /// @return the effective transfer characteristics
    private static int transferCharacteristics(@Nullable AvifColorInfo colorInfo) {
        if (colorInfo == null || colorInfo.transferCharacteristics() == TRANSFER_UNSPECIFIED) {
            return TRANSFER_SRGB;
        }
        return colorInfo.transferCharacteristics();
    }

    /// Returns primaries chromaticities for one CICP code.
    ///
    /// @param colorPrimaries the CICP color primaries value
    /// @return the primaries definition, or `null` when unsupported
    private static @Nullable Primaries primaries(int colorPrimaries) {
        return switch (colorPrimaries) {
            case PRIMARIES_BT709 -> new Primaries(0.640, 0.330, 0.300, 0.600, 0.150, 0.060, 0.3127, 0.3290);
            case PRIMARIES_BT470M -> new Primaries(0.670, 0.330, 0.210, 0.710, 0.140, 0.080, 0.3100, 0.3160);
            case PRIMARIES_BT470BG -> new Primaries(0.640, 0.330, 0.290, 0.600, 0.150, 0.060, 0.3127, 0.3290);
            case PRIMARIES_SMPTE170M -> new Primaries(0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290);
            case PRIMARIES_SMPTE240M -> new Primaries(0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290);
            case PRIMARIES_BT2020 -> new Primaries(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290);
            case PRIMARIES_SMPTE431 -> new Primaries(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3140, 0.3510);
            case PRIMARIES_SMPTE432 -> new Primaries(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290);
            default -> null;
        };
    }

    /// Converts an sRGB gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double srgbToLinear(double value) {
        if (value <= SRGB_GAMMA_THRESHOLD) {
            return value / 12.92;
        }
        return Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /// Converts a linear-light sample to sRGB gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToSrgb(double value) {
        if (value <= SRGB_LINEAR_THRESHOLD) {
            return value * 12.92;
        }
        return 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
    }

    /// Converts a BT.709-family gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double bt709ToLinear(double value) {
        if (value < 0.081) {
            return value / 4.5;
        }
        return Math.pow((value + 0.099) / 1.099, 1.0 / 0.45);
    }

    /// Converts a linear-light sample to BT.709-family gamma encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToBt709(double value) {
        if (value < 0.018) {
            return value * 4.5;
        }
        return 1.099 * Math.pow(value, 0.45) - 0.099;
    }

    /// Converts a PQ-encoded sample to normalized linear light.
    ///
    /// @param value the normalized PQ-encoded sample
    /// @return the normalized linear-light sample
    private static double pqToLinear(double value) {
        double powered = Math.pow(value, 1.0 / PQ_M2);
        double numerator = Math.max(powered - PQ_C1, 0.0);
        double denominator = PQ_C2 - PQ_C3 * powered;
        if (denominator <= 0.0) {
            return 1.0;
        }
        return Math.pow(numerator / denominator, 1.0 / PQ_M1);
    }

    /// Converts a normalized linear-light sample to PQ encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized PQ-encoded sample
    private static double linearToPq(double value) {
        double powered = Math.pow(value, PQ_M1);
        return Math.pow((PQ_C1 + PQ_C2 * powered) / (1.0 + PQ_C3 * powered), PQ_M2);
    }

    /// Converts an HLG-encoded sample to normalized linear light.
    ///
    /// @param value the normalized HLG-encoded sample
    /// @return the normalized linear-light sample
    private static double hlgToLinear(double value) {
        if (value <= 0.5) {
            return value * value / 3.0;
        }
        return (Math.exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0;
    }

    /// Converts a normalized linear-light sample to HLG encoding.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized HLG-encoded sample
    private static double linearToHlg(double value) {
        if (value <= 1.0 / 12.0) {
            return Math.sqrt(3.0 * value);
        }
        return HLG_A * Math.log(12.0 * value - HLG_B) + HLG_C;
    }

    /// A 3x3 RGB conversion matrix.
    @NotNullByDefault
    static final class RgbMatrix {
        /// Matrix entry at row 0, column 0.
        private final double m00;
        /// Matrix entry at row 0, column 1.
        private final double m01;
        /// Matrix entry at row 0, column 2.
        private final double m02;
        /// Matrix entry at row 1, column 0.
        private final double m10;
        /// Matrix entry at row 1, column 1.
        private final double m11;
        /// Matrix entry at row 1, column 2.
        private final double m12;
        /// Matrix entry at row 2, column 0.
        private final double m20;
        /// Matrix entry at row 2, column 1.
        private final double m21;
        /// Matrix entry at row 2, column 2.
        private final double m22;

        /// Creates a 3x3 RGB conversion matrix.
        ///
        /// @param m00 matrix entry at row 0, column 0
        /// @param m01 matrix entry at row 0, column 1
        /// @param m02 matrix entry at row 0, column 2
        /// @param m10 matrix entry at row 1, column 0
        /// @param m11 matrix entry at row 1, column 1
        /// @param m12 matrix entry at row 1, column 2
        /// @param m20 matrix entry at row 2, column 0
        /// @param m21 matrix entry at row 2, column 1
        /// @param m22 matrix entry at row 2, column 2
        private RgbMatrix(
                double m00,
                double m01,
                double m02,
                double m10,
                double m11,
                double m12,
                double m20,
                double m21,
                double m22
        ) {
            this.m00 = m00;
            this.m01 = m01;
            this.m02 = m02;
            this.m10 = m10;
            this.m11 = m11;
            this.m12 = m12;
            this.m20 = m20;
            this.m21 = m21;
            this.m22 = m22;
        }

        /// Applies this matrix in place to an RGB triplet.
        ///
        /// @param rgb the mutable RGB triplet
        void apply(double[] rgb) {
            double red = rgb[0];
            double green = rgb[1];
            double blue = rgb[2];
            rgb[0] = m00 * red + m01 * green + m02 * blue;
            rgb[1] = m10 * red + m11 * green + m12 * blue;
            rgb[2] = m20 * red + m21 * green + m22 * blue;
        }

        /// Multiplies this matrix by another matrix.
        ///
        /// @param other the right-hand matrix
        /// @return the matrix product
        private RgbMatrix multiply(RgbMatrix other) {
            return new RgbMatrix(
                    m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
                    m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
                    m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
                    m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
                    m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
                    m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
                    m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
                    m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
                    m20 * other.m02 + m21 * other.m12 + m22 * other.m22
            );
        }

        /// Computes the inverse matrix.
        ///
        /// @return the inverse matrix, or `null` for singular matrices
        private @Nullable RgbMatrix inverse() {
            double c00 = m11 * m22 - m12 * m21;
            double c01 = m12 * m20 - m10 * m22;
            double c02 = m10 * m21 - m11 * m20;
            double determinant = m00 * c00 + m01 * c01 + m02 * c02;
            if (Math.abs(determinant) < 1.0e-12) {
                return null;
            }
            double scale = 1.0 / determinant;
            return new RgbMatrix(
                    c00 * scale,
                    (m02 * m21 - m01 * m22) * scale,
                    (m01 * m12 - m02 * m11) * scale,
                    c01 * scale,
                    (m00 * m22 - m02 * m20) * scale,
                    (m02 * m10 - m00 * m12) * scale,
                    c02 * scale,
                    (m01 * m20 - m00 * m21) * scale,
                    (m00 * m11 - m01 * m10) * scale
            );
        }
    }

    /// RGB primary chromaticities and white point.
    ///
    /// @param redX red primary x chromaticity
    /// @param redY red primary y chromaticity
    /// @param greenX green primary x chromaticity
    /// @param greenY green primary y chromaticity
    /// @param blueX blue primary x chromaticity
    /// @param blueY blue primary y chromaticity
    /// @param whiteX white point x chromaticity
    /// @param whiteY white point y chromaticity
    private record Primaries(
            double redX,
            double redY,
            double greenX,
            double greenY,
            double blueX,
            double blueY,
            double whiteX,
            double whiteY
    ) {
        /// Computes the RGB-to-XYZ matrix for these primaries.
        ///
        /// @return the RGB-to-XYZ matrix, or `null` when chromaticities are singular
        private @Nullable RgbMatrix rgbToXyzMatrix() {
            double redZ = 1.0 - redX - redY;
            double greenZ = 1.0 - greenX - greenY;
            double blueZ = 1.0 - blueX - blueY;
            double whiteZ = 1.0 - whiteX - whiteY;
            if (redY == 0.0 || greenY == 0.0 || blueY == 0.0 || whiteY == 0.0) {
                return null;
            }
            RgbMatrix matrix = new RgbMatrix(
                    redX / redY, greenX / greenY, blueX / blueY,
                    1.0, 1.0, 1.0,
                    redZ / redY, greenZ / greenY, blueZ / blueY
            );
            @Nullable RgbMatrix inverse = matrix.inverse();
            if (inverse == null) {
                return null;
            }
            double[] white = { whiteX / whiteY, 1.0, whiteZ / whiteY };
            inverse.apply(white);
            double redScale = white[0];
            double greenScale = white[1];
            double blueScale = white[2];
            return new RgbMatrix(
                    redScale * redX / redY, greenScale * greenX / greenY, blueScale * blueX / blueY,
                    redScale, greenScale, blueScale,
                    redScale * redZ / redY, greenScale * greenZ / greenY, blueScale * blueZ / blueY
            );
        }
    }
}
