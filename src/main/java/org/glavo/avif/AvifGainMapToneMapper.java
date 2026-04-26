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

import org.glavo.avif.internal.av1.output.YuvToRgbTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Applies parsed AVIF gain-map metadata to decoded RGB frame output.
///
/// This implementation follows the ISO gain-map equation over normalized RGB samples and uses
/// sRGB transfer functions for the current pure-Java output path. ICC transforms and cross-primary
/// conversion remain outside this helper because production code is restricted to `java.base`.
@NotNullByDefault
final class AvifGainMapToneMapper {
    /// The maximum normalized channel value.
    private static final double CHANNEL_MAX = 1.0;

    /// The sRGB linear-to-gamma threshold.
    private static final double SRGB_LINEAR_THRESHOLD = 0.0031308;

    /// The sRGB gamma-to-linear threshold.
    private static final double SRGB_GAMMA_THRESHOLD = 0.04045;

    /// Prevents instantiation of this utility class.
    private AvifGainMapToneMapper() {
    }

    /// Applies one gain map to a decoded base frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param gainMapPlanes the decoded gain-map planes
    /// @param metadata the parsed gain-map metadata
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @return the tone-mapped frame, or the original frame when the gain-map weight is zero
    static AvifFrame apply(
            AvifFrame baseFrame,
            AvifPlanes gainMapPlanes,
            AvifGainMapMetadata metadata,
            double hdrHeadroom
    ) {
        AvifFrame checkedBaseFrame = Objects.requireNonNull(baseFrame, "baseFrame");
        AvifPlanes checkedGainMapPlanes = Objects.requireNonNull(gainMapPlanes, "gainMapPlanes");
        GainMapMath math = new GainMapMath(Objects.requireNonNull(metadata, "metadata"), hdrHeadroom);
        if (math.weight() == 0.0) {
            return checkedBaseFrame;
        }
        GainMapSampler sampler = new GainMapSampler(
                checkedGainMapPlanes,
                checkedBaseFrame.width(),
                checkedBaseFrame.height()
        );
        if (checkedBaseFrame.rgbOutputMode() == AvifRgbOutputMode.ARGB_8888) {
            return applyToIntFrame(checkedBaseFrame, sampler, math);
        }
        if (checkedBaseFrame.rgbOutputMode() == AvifRgbOutputMode.ARGB_16161616) {
            return applyToLongFrame(checkedBaseFrame, sampler, math);
        }
        throw new IllegalArgumentException("Unsupported RGB output mode: " + checkedBaseFrame.rgbOutputMode());
    }

    /// Applies gain-map metadata to an 8-bit ARGB frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param sampler the gain-map sampler
    /// @param math the precomputed gain-map math state
    /// @return the tone-mapped frame
    private static AvifFrame applyToIntFrame(AvifFrame baseFrame, GainMapSampler sampler, GainMapMath math) {
        IntBuffer basePixels = baseFrame.intPixelBuffer();
        int width = baseFrame.width();
        int height = baseFrame.height();
        int[] toneMapped = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = basePixels.get(row + x);
                int alpha = pixel & 0xFF00_0000;
                int red = toneMapByteChannel((pixel >>> 16) & 0xFF, sampler.channel(x, y, 0), 0, math);
                int green = toneMapByteChannel((pixel >>> 8) & 0xFF, sampler.channel(x, y, 1), 1, math);
                int blue = toneMapByteChannel(pixel & 0xFF, sampler.channel(x, y, 2), 2, math);
                toneMapped[row + x] = alpha | (red << 16) | (green << 8) | blue;
            }
        }
        return new AvifFrame(
                width,
                height,
                baseFrame.bitDepth(),
                baseFrame.pixelFormat(),
                baseFrame.frameIndex(),
                toneMapped
        );
    }

    /// Applies gain-map metadata to a 16-bit-per-channel ARGB frame.
    ///
    /// @param baseFrame the decoded base frame
    /// @param sampler the gain-map sampler
    /// @param math the precomputed gain-map math state
    /// @return the tone-mapped frame
    private static AvifFrame applyToLongFrame(AvifFrame baseFrame, GainMapSampler sampler, GainMapMath math) {
        LongBuffer basePixels = baseFrame.longPixelBuffer();
        int width = baseFrame.width();
        int height = baseFrame.height();
        long[] toneMapped = new long[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                long pixel = basePixels.get(row + x);
                long alpha = pixel & 0xFFFF_0000_0000_0000L;
                long red = toneMapWordChannel((int) ((pixel >>> 32) & 0xFFFFL), sampler.channel(x, y, 0), 0, math);
                long green = toneMapWordChannel((int) ((pixel >>> 16) & 0xFFFFL), sampler.channel(x, y, 1), 1, math);
                long blue = toneMapWordChannel((int) (pixel & 0xFFFFL), sampler.channel(x, y, 2), 2, math);
                toneMapped[row + x] = alpha | (red << 32) | (green << 16) | blue;
            }
        }
        return new AvifFrame(
                width,
                height,
                baseFrame.bitDepth(),
                baseFrame.pixelFormat(),
                baseFrame.frameIndex(),
                toneMapped
        );
    }

    /// Applies gain-map math to one 8-bit channel.
    ///
    /// @param sample the base gamma-encoded sample
    /// @param gainMapSample the normalized gain-map sample
    /// @param channel the RGB channel index
    /// @param math the gain-map math state
    /// @return the tone-mapped gamma-encoded 8-bit sample
    private static int toneMapByteChannel(int sample, double gainMapSample, int channel, GainMapMath math) {
        double baseLinear = srgbToLinear(sample / 255.0);
        double mappedLinear = math.apply(channel, baseLinear, gainMapSample);
        return linearToByte(mappedLinear);
    }

    /// Applies gain-map math to one 16-bit channel.
    ///
    /// @param sample the base gamma-encoded sample
    /// @param gainMapSample the normalized gain-map sample
    /// @param channel the RGB channel index
    /// @param math the gain-map math state
    /// @return the tone-mapped gamma-encoded 16-bit sample
    private static long toneMapWordChannel(int sample, double gainMapSample, int channel, GainMapMath math) {
        double baseLinear = srgbToLinear(sample / 65_535.0);
        double mappedLinear = math.apply(channel, baseLinear, gainMapSample);
        return linearToWord(mappedLinear);
    }

    /// Converts one sRGB gamma-encoded sample to linear light.
    ///
    /// @param value the normalized gamma-encoded sample
    /// @return the normalized linear-light sample
    private static double srgbToLinear(double value) {
        double clamped = clampUnit(value);
        if (clamped <= SRGB_GAMMA_THRESHOLD) {
            return clamped / 12.92;
        }
        return Math.pow((clamped + 0.055) / 1.055, 2.4);
    }

    /// Converts one linear-light sample to an 8-bit sRGB sample.
    ///
    /// @param value the normalized linear-light sample
    /// @return the gamma-encoded 8-bit sample
    private static int linearToByte(double value) {
        return (int) Math.round(linearToSrgb(value) * 255.0);
    }

    /// Converts one linear-light sample to a 16-bit sRGB sample.
    ///
    /// @param value the normalized linear-light sample
    /// @return the gamma-encoded 16-bit sample
    private static long linearToWord(double value) {
        return Math.round(linearToSrgb(value) * 65_535.0);
    }

    /// Converts one linear-light sample to normalized sRGB.
    ///
    /// @param value the normalized linear-light sample
    /// @return the normalized gamma-encoded sample
    private static double linearToSrgb(double value) {
        double clamped = clampUnit(value);
        if (clamped <= SRGB_LINEAR_THRESHOLD) {
            return clamped * 12.92;
        }
        return 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
    }

    /// Clamps a normalized sample to the closed unit interval.
    ///
    /// @param value the sample value
    /// @return the clamped value
    private static double clampUnit(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= CHANNEL_MAX) {
            return CHANNEL_MAX;
        }
        return value;
    }

    /// Precomputed gain-map equation state.
    @NotNullByDefault
    private static final class GainMapMath {
        /// The gain-map application weight.
        private final double weight;
        /// Per-channel minimum gain-map values in log2 space.
        private final double @Unmodifiable [] gainMapMin;
        /// Per-channel maximum gain-map values in log2 space.
        private final double @Unmodifiable [] gainMapMax;
        /// Per-channel inverse gamma values.
        private final double @Unmodifiable [] gammaInverse;
        /// Per-channel base image offsets.
        private final double @Unmodifiable [] baseOffset;
        /// Per-channel alternate image offsets.
        private final double @Unmodifiable [] alternateOffset;

        /// Creates precomputed gain-map equation state.
        ///
        /// @param metadata the parsed gain-map metadata
        /// @param hdrHeadroom the requested display HDR headroom in log2 space
        private GainMapMath(AvifGainMapMetadata metadata, double hdrHeadroom) {
            if (!Double.isFinite(hdrHeadroom) || hdrHeadroom < 0.0) {
                throw new IllegalArgumentException("hdrHeadroom must be a finite non-negative value");
            }
            this.weight = computeWeight(hdrHeadroom, metadata.baseHdrHeadroom(), metadata.alternateHdrHeadroom());
            this.gainMapMin = signedFractionsToDoubles(metadata.gainMapMin());
            this.gainMapMax = signedFractionsToDoubles(metadata.gainMapMax());
            this.gammaInverse = inverseUnsignedFractions(metadata.gainMapGamma());
            this.baseOffset = signedFractionsToDoubles(metadata.baseOffset());
            this.alternateOffset = signedFractionsToDoubles(metadata.alternateOffset());
        }

        /// Returns the gain-map application weight.
        ///
        /// @return the gain-map application weight
        private double weight() {
            return weight;
        }

        /// Applies gain-map math to one linear-light channel.
        ///
        /// @param channel the RGB channel index
        /// @param baseLinear the normalized linear-light base value
        /// @param gainMapSample the normalized gain-map sample
        /// @return the normalized linear-light tone-mapped value
        private double apply(int channel, double baseLinear, double gainMapSample) {
            double gainMapEncoded = clampUnit(gainMapSample);
            double gainMapLog2 = lerp(
                    gainMapMin[channel],
                    gainMapMax[channel],
                    Math.pow(gainMapEncoded, gammaInverse[channel])
            );
            return (baseLinear + baseOffset[channel]) * Math.pow(2.0, gainMapLog2 * weight)
                    - alternateOffset[channel];
        }

        /// Computes the gain-map application weight for one display headroom.
        ///
        /// @param hdrHeadroom the requested display HDR headroom in log2 space
        /// @param baseHdrHeadroom the base image HDR headroom
        /// @param alternateHdrHeadroom the alternate image HDR headroom
        /// @return the gain-map application weight
        private static double computeWeight(
                double hdrHeadroom,
                AvifUnsignedFraction baseHdrHeadroom,
                AvifUnsignedFraction alternateHdrHeadroom
        ) {
            double base = baseHdrHeadroom.toDouble();
            double alternate = alternateHdrHeadroom.toDouble();
            if (base == alternate) {
                return 0.0;
            }
            double weight = clampUnit((hdrHeadroom - base) / (alternate - base));
            return alternate < base ? -weight : weight;
        }

        /// Linearly interpolates between two values.
        ///
        /// @param a the first value
        /// @param b the second value
        /// @param weight the interpolation weight
        /// @return the interpolated value
        private static double lerp(double a, double b, double weight) {
            return (1.0 - weight) * a + weight * b;
        }

        /// Converts signed fractions to `double` values.
        ///
        /// @param fractions the source fractions
        /// @return converted values
        private static double @Unmodifiable [] signedFractionsToDoubles(
                AvifSignedFraction @Unmodifiable [] fractions
        ) {
            double[] result = new double[fractions.length];
            for (int i = 0; i < fractions.length; i++) {
                result[i] = fractions[i].toDouble();
            }
            return result;
        }

        /// Converts unsigned gamma fractions to inverse `double` values.
        ///
        /// @param fractions the source gamma fractions
        /// @return inverse gamma values
        private static double @Unmodifiable [] inverseUnsignedFractions(
                AvifUnsignedFraction @Unmodifiable [] fractions
        ) {
            double[] result = new double[fractions.length];
            for (int i = 0; i < fractions.length; i++) {
                result[i] = 1.0 / fractions[i].toDouble();
            }
            return result;
        }
    }

    /// Samples normalized RGB gain-map values at base-frame coordinates.
    @NotNullByDefault
    private static final class GainMapSampler {
        /// The decoded gain-map planes.
        private final AvifPlanes planes;
        /// The base frame width in pixels.
        private final int baseWidth;
        /// The base frame height in pixels.
        private final int baseHeight;
        /// The maximum gain-map sample value.
        private final int maxSample;
        /// The cached RGB pixel for the last sampled gain-map coordinate.
        private int cachedRgb;
        /// The last sampled gain-map x coordinate, or -1 before the first sample.
        private int cachedX = -1;
        /// The last sampled gain-map y coordinate, or -1 before the first sample.
        private int cachedY = -1;

        /// Creates a gain-map sampler.
        ///
        /// @param planes the decoded gain-map planes
        /// @param baseWidth the base frame width in pixels
        /// @param baseHeight the base frame height in pixels
        private GainMapSampler(AvifPlanes planes, int baseWidth, int baseHeight) {
            if (baseWidth <= 0 || baseHeight <= 0) {
                throw new IllegalArgumentException("base frame dimensions must be positive");
            }
            this.planes = Objects.requireNonNull(planes, "planes");
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            this.maxSample = planes.bitDepth().maxSampleValue();
        }

        /// Returns a normalized gain-map RGB channel for one base pixel coordinate.
        ///
        /// @param baseX the base frame x coordinate
        /// @param baseY the base frame y coordinate
        /// @param channel the RGB channel index
        /// @return the normalized gain-map channel
        private double channel(int baseX, int baseY, int channel) {
            int x = mapCoordinate(baseX, baseWidth, planes.renderWidth());
            int y = mapCoordinate(baseY, baseHeight, planes.renderHeight());
            if (planes.pixelFormat() == AvifPixelFormat.I400) {
                return sampleToUnit(planes.lumaPlane().sample(x, y));
            }
            int rgb = rgbAt(x, y);
            return switch (channel) {
                case 0 -> ((rgb >>> 16) & 0xFF) / 255.0;
                case 1 -> ((rgb >>> 8) & 0xFF) / 255.0;
                case 2 -> (rgb & 0xFF) / 255.0;
                default -> throw new IllegalArgumentException("channel out of range: " + channel);
            };
        }

        /// Converts one gain-map YUV sample location to an 8-bit RGB pixel.
        ///
        /// @param x the gain-map luma x coordinate
        /// @param y the gain-map luma y coordinate
        /// @return an opaque ARGB pixel
        private int rgbAt(int x, int y) {
            if (x == cachedX && y == cachedY) {
                return cachedRgb;
            }
            @Nullable AvifPlane chromaUPlane = planes.chromaUPlane();
            @Nullable AvifPlane chromaVPlane = planes.chromaVPlane();
            if (chromaUPlane == null || chromaVPlane == null) {
                throw new IllegalArgumentException("Gain-map chroma planes are missing");
            }
            int chromaX = chromaX(x);
            int chromaY = chromaY(y);
            cachedRgb = YuvToRgbTransform.BT601_FULL_RANGE.toOpaqueArgb(
                    planes.lumaPlane().sample(x, y),
                    chromaUPlane.sample(chromaX, chromaY),
                    chromaVPlane.sample(chromaX, chromaY),
                    planes.bitDepth().bits()
            );
            cachedX = x;
            cachedY = y;
            return cachedRgb;
        }

        /// Maps a base-frame coordinate into the gain-map coordinate space.
        ///
        /// @param value the base-frame coordinate
        /// @param sourceSize the base-frame dimension
        /// @param targetSize the gain-map dimension
        /// @return the mapped gain-map coordinate
        private static int mapCoordinate(int value, int sourceSize, int targetSize) {
            long mapped = (long) value * targetSize / sourceSize;
            if (mapped >= targetSize) {
                return targetSize - 1;
            }
            return (int) mapped;
        }

        /// Converts one decoded gain-map sample to the normalized unit interval.
        ///
        /// @param sample the decoded sample
        /// @return the normalized sample
        private double sampleToUnit(int sample) {
            return clampUnit((double) sample / (double) maxSample);
        }

        /// Returns the chroma x coordinate for one luma x coordinate.
        ///
        /// @param x the luma x coordinate
        /// @return the chroma x coordinate
        private int chromaX(int x) {
            return switch (planes.pixelFormat()) {
                case I400, I444 -> x;
                case I420, I422 -> x >> 1;
            };
        }

        /// Returns the chroma y coordinate for one luma y coordinate.
        ///
        /// @param y the luma y coordinate
        /// @return the chroma y coordinate
        private int chromaY(int y) {
            return switch (planes.pixelFormat()) {
                case I400, I422, I444 -> y;
                case I420 -> y >> 1;
            };
        }
    }
}
