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
package org.glavo.avif.internal.av1.output;

import org.jetbrains.annotations.NotNullByDefault;

/// Fixed-point YUV-to-RGB transform for opaque ARGB output.
///
/// Coefficients are stored in the nominal 8-bit sample domain and use 16 fractional bits. The
/// transform can emit either packed `0xAARRGGBB` pixels for 8-bit output or packed
/// `0xAAAA_RRRR_GGGG_BBBB` pixels for higher-bit-depth output. The transform does not model
/// alpha; callers always receive non-premultiplied opaque pixels.
@NotNullByDefault
public final class YuvToRgbTransform {
    /// The fixed-point coefficient scaling factor.
    private static final int FRACTION_BITS = 16;

    /// The rounding term applied before shifting back to 8-bit RGB.
    private static final int ROUNDING = 1 << (FRACTION_BITS - 1);

    /// Full-range `BT.601` coefficients for 8-bit YUV samples.
    public static final YuvToRgbTransform BT601_FULL_RANGE = new YuvToRgbTransform(
            0,
            128,
            65_536,
            91_881,
            -22_554,
            -46_802,
            116_130
    );

    /// The luma offset removed before matrix multiplication.
    private final int lumaOffset;

    /// The neutral chroma sample value.
    private final int chromaCenter;

    /// The fixed-point luma coefficient shared by every output channel.
    private final int lumaCoefficient;

    /// The fixed-point red-from-V coefficient.
    private final int redCoefficientV;

    /// The fixed-point green-from-U coefficient.
    private final int greenCoefficientU;

    /// The fixed-point green-from-V coefficient.
    private final int greenCoefficientV;

    /// The fixed-point blue-from-U coefficient.
    private final int blueCoefficientU;

    /// Creates one fixed-point YUV-to-RGB transform for 8-bit sample domains.
    ///
    /// @param lumaOffset the luma offset removed before matrix multiplication
    /// @param chromaCenter the neutral chroma sample value
    /// @param lumaCoefficient the fixed-point luma coefficient shared by every output channel
    /// @param redCoefficientV the fixed-point red-from-V coefficient
    /// @param greenCoefficientU the fixed-point green-from-U coefficient
    /// @param greenCoefficientV the fixed-point green-from-V coefficient
    /// @param blueCoefficientU the fixed-point blue-from-U coefficient
    public YuvToRgbTransform(
            int lumaOffset,
            int chromaCenter,
            int lumaCoefficient,
            int redCoefficientV,
            int greenCoefficientU,
            int greenCoefficientV,
            int blueCoefficientU
    ) {
        if (lumaOffset < 0) {
            throw new IllegalArgumentException("lumaOffset < 0: " + lumaOffset);
        }
        if (chromaCenter < 0) {
            throw new IllegalArgumentException("chromaCenter < 0: " + chromaCenter);
        }
        if (lumaCoefficient <= 0) {
            throw new IllegalArgumentException("lumaCoefficient <= 0: " + lumaCoefficient);
        }
        this.lumaOffset = lumaOffset;
        this.chromaCenter = chromaCenter;
        this.lumaCoefficient = lumaCoefficient;
        this.redCoefficientV = redCoefficientV;
        this.greenCoefficientU = greenCoefficientU;
        this.greenCoefficientV = greenCoefficientV;
        this.blueCoefficientU = blueCoefficientU;
    }

    /// Returns the luma offset removed before matrix multiplication.
    ///
    /// @return the luma offset removed before matrix multiplication
    public int lumaOffset() {
        return lumaOffset;
    }

    /// Returns the neutral chroma sample value.
    ///
    /// @return the neutral chroma sample value
    public int chromaCenter() {
        return chromaCenter;
    }

    /// Returns the fixed-point luma coefficient shared by every output channel.
    ///
    /// @return the fixed-point luma coefficient shared by every output channel
    public int lumaCoefficient() {
        return lumaCoefficient;
    }

    /// Returns the fixed-point red-from-V coefficient.
    ///
    /// @return the fixed-point red-from-V coefficient
    public int redCoefficientV() {
        return redCoefficientV;
    }

    /// Returns the fixed-point green-from-U coefficient.
    ///
    /// @return the fixed-point green-from-U coefficient
    public int greenCoefficientU() {
        return greenCoefficientU;
    }

    /// Returns the fixed-point green-from-V coefficient.
    ///
    /// @return the fixed-point green-from-V coefficient
    public int greenCoefficientV() {
        return greenCoefficientV;
    }

    /// Returns the fixed-point blue-from-U coefficient.
    ///
    /// @return the fixed-point blue-from-U coefficient
    public int blueCoefficientU() {
        return blueCoefficientU;
    }

    /// Converts one luma sample with neutral chroma into an opaque grayscale ARGB pixel.
    ///
    /// @param ySample the 8-bit luma sample
    /// @return the packed opaque grayscale ARGB pixel
    public int toOpaqueGrayArgb(int ySample) {
        return toOpaqueArgb(ySample, chromaCenter, chromaCenter);
    }

    /// Converts one YUV sample triplet into a packed opaque ARGB pixel.
    ///
    /// @param ySample the 8-bit luma sample
    /// @param uSample the 8-bit chroma U sample
    /// @param vSample the 8-bit chroma V sample
    /// @return the packed opaque non-premultiplied ARGB pixel
    public int toOpaqueArgb(int ySample, int uSample, int vSample) {
        int scaledLuma = Math.max(0, ySample - lumaOffset) * lumaCoefficient;
        int centeredU = uSample - chromaCenter;
        int centeredV = vSample - chromaCenter;

        int red = clampToByte((scaledLuma + centeredV * redCoefficientV + ROUNDING) >> FRACTION_BITS);
        int green = clampToByte(
                (scaledLuma + centeredU * greenCoefficientU + centeredV * greenCoefficientV + ROUNDING) >> FRACTION_BITS
        );
        int blue = clampToByte((scaledLuma + centeredU * blueCoefficientU + ROUNDING) >> FRACTION_BITS);
        return 0xFF00_0000 | (red << 16) | (green << 8) | blue;
    }

    /// Converts one luma sample with neutral chroma into an opaque 16-bit-per-channel grayscale
    /// ARGB pixel.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque grayscale ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
    public long toOpaqueGrayArgb64(int ySample, int bitDepth) {
        int neutralChroma = scaleNominalCodeValue(chromaCenter, bitDepth);
        return toOpaqueArgb64(ySample, neutralChroma, neutralChroma, bitDepth);
    }

    /// Converts one YUV sample triplet into a packed opaque 16-bit-per-channel ARGB pixel.
    ///
    /// The YUV samples are interpreted in their original decoded bit depth. RGB channels are first
    /// computed in that same sample domain, then expanded to unsigned 16-bit output channels.
    ///
    /// @param ySample the luma sample in the caller-supplied bit depth
    /// @param uSample the chroma U sample in the caller-supplied bit depth
    /// @param vSample the chroma V sample in the caller-supplied bit depth
    /// @param bitDepth the decoded sample bit depth
    /// @return the packed opaque non-premultiplied ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
    public long toOpaqueArgb64(int ySample, int uSample, int vSample, int bitDepth) {
        int sampleMax = maxSample(bitDepth);
        int scaledLumaOffset = scaleNominalCodeValue(lumaOffset, bitDepth);
        int scaledChromaCenter = scaleNominalCodeValue(chromaCenter, bitDepth);

        long scaledLuma = (long) Math.max(0, ySample - scaledLumaOffset) * lumaCoefficient;
        int centeredU = uSample - scaledChromaCenter;
        int centeredV = vSample - scaledChromaCenter;

        int red = clampToSampleDepth(
                (int) ((scaledLuma + (long) centeredV * redCoefficientV + ROUNDING) >> FRACTION_BITS),
                sampleMax
        );
        int green = clampToSampleDepth(
                (int) ((scaledLuma
                        + (long) centeredU * greenCoefficientU
                        + (long) centeredV * greenCoefficientV
                        + ROUNDING) >> FRACTION_BITS),
                sampleMax
        );
        int blue = clampToSampleDepth(
                (int) ((scaledLuma + (long) centeredU * blueCoefficientU + ROUNDING) >> FRACTION_BITS),
                sampleMax
        );
        return packOpaqueArgb64(
                expandSampleToWord(red, bitDepth),
                expandSampleToWord(green, bitDepth),
                expandSampleToWord(blue, bitDepth)
        );
    }

    /// Clamps one integer channel value to the unsigned 8-bit range.
    ///
    /// @param value the channel value before clamping
    /// @return the clamped unsigned 8-bit channel value
    private static int clampToByte(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return value;
    }

    /// Clamps one integer channel value to the caller-supplied decoded sample range.
    ///
    /// @param value the channel value before clamping
    /// @param sampleMax the inclusive maximum sample value for the decoded bit depth
    /// @return the clamped channel value
    private static int clampToSampleDepth(int value, int sampleMax) {
        if (value <= 0) {
            return 0;
        }
        if (value >= sampleMax) {
            return sampleMax;
        }
        return value;
    }

    /// Packs three unsigned 16-bit channels into opaque `0xAAAA_RRRR_GGGG_BBBB` output.
    ///
    /// @param red the 16-bit red channel
    /// @param green the 16-bit green channel
    /// @param blue the 16-bit blue channel
    /// @return the packed opaque ARGB pixel
    private static long packOpaqueArgb64(int red, int green, int blue) {
        return 0xFFFF_0000_0000_0000L
                | ((long) red << 32)
                | ((long) green << 16)
                | (blue & 0xFFFFL);
    }

    /// Expands one decoded sample to an unsigned 16-bit output channel.
    ///
    /// @param sample the decoded sample value
    /// @param bitDepth the decoded sample bit depth
    /// @return the expanded unsigned 16-bit output channel
    private static int expandSampleToWord(int sample, int bitDepth) {
        int sampleMax = maxSample(bitDepth);
        return (int) (((long) sample * 65_535 + (sampleMax >>> 1)) / sampleMax);
    }

    /// Scales one nominal 8-bit code value into the caller-supplied decoded bit depth.
    ///
    /// @param nominalCodeValue the nominal 8-bit code value
    /// @param bitDepth the decoded sample bit depth
    /// @return the scaled code value in the decoded sample domain
    private static int scaleNominalCodeValue(int nominalCodeValue, int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        return nominalCodeValue << (bitDepth - 8);
    }

    /// Returns the inclusive maximum sample value for one decoded bit depth.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @return the inclusive maximum sample value
    private static int maxSample(int bitDepth) {
        if (bitDepth < 1 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        return (1 << bitDepth) - 1;
    }
}
