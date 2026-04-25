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
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Applies the current deterministic film-grain synthesis subset.
///
/// The implementation is deliberately narrow but stable: it derives one deterministic grain delta
/// per visible sample from the normalized grain parameters, the frame seed, plane identity, and
/// sample coordinates; it keeps stored reference surfaces pre-grain and applies synthesis only at
/// presentation time.
@NotNullByDefault
public final class FilmGrainSynthesizer {
    /// The luma plane identifier used by the deterministic grain hash.
    private static final int PLANE_LUMA = 0;

    /// The chroma-U plane identifier used by the deterministic grain hash.
    private static final int PLANE_CHROMA_U = 1;

    /// The chroma-V plane identifier used by the deterministic grain hash.
    private static final int PLANE_CHROMA_V = 2;

    /// Applies deterministic film grain to one post-filter decoded frame.
    ///
    /// @param decodedPlanes the post-filter, pre-grain planes to present
    /// @param frameHeader the normalized frame header that owns the grain parameters
    /// @return the grain-applied presentation planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader frameHeader) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        FrameHeader.FilmGrainParams filmGrain = checkedFrameHeader.filmGrain();
        if (!filmGrain.applyGrain()) {
            return checkedDecodedPlanes;
        }

        DecodedPlane lumaPlane = applyLumaGrain(checkedDecodedPlanes.lumaPlane(), checkedDecodedPlanes.bitDepth(), filmGrain);
        @Nullable DecodedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        @Nullable DecodedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            chromaUPlane = applyChromaGrain(
                    Objects.requireNonNull(chromaUPlane, "chromaUPlane"),
                    checkedDecodedPlanes.lumaPlane(),
                    checkedDecodedPlanes.pixelFormat(),
                    checkedDecodedPlanes.bitDepth(),
                    filmGrain,
                    true
            );
            chromaVPlane = applyChromaGrain(
                    Objects.requireNonNull(chromaVPlane, "chromaVPlane"),
                    checkedDecodedPlanes.lumaPlane(),
                    checkedDecodedPlanes.pixelFormat(),
                    checkedDecodedPlanes.bitDepth(),
                    filmGrain,
                    false
            );
        }

        return new DecodedPlanes(
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.pixelFormat(),
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                checkedDecodedPlanes.renderWidth(),
                checkedDecodedPlanes.renderHeight(),
                lumaPlane,
                chromaUPlane,
                chromaVPlane
        );
    }

    /// Applies deterministic luma grain to one plane.
    ///
    /// @param lumaPlane the post-filter luma plane
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @return the grain-applied luma plane
    private static DecodedPlane applyLumaGrain(
            DecodedPlane lumaPlane,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        short[] outputSamples = lumaPlane.samples();
        int maximumSample = (1 << bitDepth) - 1;
        int minimumSample = restrictedRangeMinimum(bitDepth, true, filmGrain.clipToRestrictedRange());
        int maximumClippedSample = restrictedRangeMaximum(bitDepth, true, filmGrain.clipToRestrictedRange());
        for (int y = 0; y < lumaPlane.height(); y++) {
            for (int x = 0; x < lumaPlane.width(); x++) {
                int sample = lumaPlane.sample(x, y);
                int delta = grainDelta(
                        filmGrain,
                        PLANE_LUMA,
                        x,
                        y,
                        scalingForPoints(filmGrain.yPoints(), toEightBitDomain(sample, bitDepth)),
                        sample,
                        sample,
                        maximumSample
                );
                outputSamples[y * lumaPlane.stride() + x] = (short) clamp(sample + delta, minimumSample, maximumClippedSample);
            }
        }
        return new DecodedPlane(lumaPlane.width(), lumaPlane.height(), lumaPlane.stride(), outputSamples);
    }

    /// Applies deterministic chroma grain to one plane.
    ///
    /// @param chromaPlane the post-filter chroma plane
    /// @param lumaPlane the post-filter luma plane
    /// @param pixelFormat the active pixel format
    /// @param bitDepth the decoded bit depth
    /// @param filmGrain the normalized film-grain parameters
    /// @param cbPlane whether the destination plane is Cb instead of Cr
    /// @return the grain-applied chroma plane
    private static DecodedPlane applyChromaGrain(
            DecodedPlane chromaPlane,
            DecodedPlane lumaPlane,
            AvifPixelFormat pixelFormat,
            int bitDepth,
            FrameHeader.FilmGrainParams filmGrain,
            boolean cbPlane
    ) {
        short[] outputSamples = chromaPlane.samples();
        int maximumSample = (1 << bitDepth) - 1;
        int minimumSample = restrictedRangeMinimum(bitDepth, false, filmGrain.clipToRestrictedRange());
        int maximumClippedSample = restrictedRangeMaximum(bitDepth, false, filmGrain.clipToRestrictedRange());
        int subSamplingX = pixelFormat == AvifPixelFormat.I420 || pixelFormat == AvifPixelFormat.I422 ? 1 : 0;
        int subSamplingY = pixelFormat == AvifPixelFormat.I420 ? 1 : 0;
        for (int y = 0; y < chromaPlane.height(); y++) {
            for (int x = 0; x < chromaPlane.width(); x++) {
                int chromaSample = chromaPlane.sample(x, y);
                int lumaSample = lumaPlane.sample(
                        Math.min(lumaPlane.width() - 1, x << subSamplingX),
                        Math.min(lumaPlane.height() - 1, y << subSamplingY)
                );
                int scaling = chromaScaling(filmGrain, cbPlane, chromaSample, lumaSample, bitDepth);
                int delta = grainDelta(
                        filmGrain,
                        cbPlane ? PLANE_CHROMA_U : PLANE_CHROMA_V,
                        x,
                        y,
                        scaling,
                        chromaSample,
                        lumaSample,
                        maximumSample
                );
                outputSamples[y * chromaPlane.stride() + x] = (short) clamp(chromaSample + delta, minimumSample, maximumClippedSample);
            }
        }
        return new DecodedPlane(chromaPlane.width(), chromaPlane.height(), chromaPlane.stride(), outputSamples);
    }

    /// Returns one deterministic grain delta.
    ///
    /// @param filmGrain the normalized film-grain parameters
    /// @param plane the logical plane identifier
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param scaling the grain scaling value in `0..255`
    /// @param planeSample the current plane sample
    /// @param lumaSample the colocated luma sample
    /// @param maximumSample the inclusive maximum sample value for the bit depth
    /// @return one signed deterministic grain delta
    private static int grainDelta(
            FrameHeader.FilmGrainParams filmGrain,
            int plane,
            int x,
            int y,
            int scaling,
            int planeSample,
            int lumaSample,
            int maximumSample
    ) {
        if (scaling == 0) {
            return 0;
        }
        int hash = mixedHash(filmGrain.grainSeed(), plane, x, y, planeSample, lumaSample);
        int signedNoise = (hash & 0x1FF) - 256;
        int amplitude = Math.max(1, scaling >> filmGrain.grainScaleShift());
        return (signedNoise * amplitude + 128) >> 8;
    }

    /// Returns one chroma scaling value in the normalized `0..255` domain.
    ///
    /// @param filmGrain the normalized film-grain parameters
    /// @param cbPlane whether the destination plane is Cb instead of Cr
    /// @param chromaSample the current chroma sample
    /// @param lumaSample the colocated luma sample
    /// @param bitDepth the decoded bit depth
    /// @return one chroma scaling value in `0..255`
    private static int chromaScaling(
            FrameHeader.FilmGrainParams filmGrain,
            boolean cbPlane,
            int chromaSample,
            int lumaSample,
            int bitDepth
    ) {
        if (filmGrain.chromaScalingFromLuma()) {
            return scalingForPoints(filmGrain.yPoints(), toEightBitDomain(lumaSample, bitDepth));
        }
        FrameHeader.FilmGrainPoint[] points = cbPlane ? filmGrain.cbPoints() : filmGrain.crPoints();
        if (points.length != 0) {
            return scalingForPoints(points, toEightBitDomain(chromaSample, bitDepth));
        }
        int chromaEightBit = toEightBitDomain(chromaSample, bitDepth);
        int lumaEightBit = toEightBitDomain(lumaSample, bitDepth);
        int mixedInput = cbPlane
                ? ((filmGrain.cbMult() * chromaEightBit + filmGrain.cbLumaMult() * lumaEightBit) >> 6) + filmGrain.cbOffset()
                : ((filmGrain.crMult() * chromaEightBit + filmGrain.crLumaMult() * lumaEightBit) >> 6) + filmGrain.crOffset();
        return clamp(mixedInput, 0, 255);
    }

    /// Evaluates one piecewise-linear scaling function.
    ///
    /// @param points the ordered scaling points
    /// @param value the input value in the normalized `0..255` domain
    /// @return the interpolated scaling value in `0..255`
    private static int scalingForPoints(FrameHeader.FilmGrainPoint[] points, int value) {
        if (points.length == 0) {
            return 0;
        }
        if (value <= points[0].value()) {
            return points[0].scaling();
        }
        for (int index = 1; index < points.length; index++) {
            FrameHeader.FilmGrainPoint previous = points[index - 1];
            FrameHeader.FilmGrainPoint current = points[index];
            if (value <= current.value()) {
                int deltaX = current.value() - previous.value();
                if (deltaX == 0) {
                    return current.scaling();
                }
                int numerator = (value - previous.value()) * (current.scaling() - previous.scaling());
                return previous.scaling() + Math.floorDiv(numerator + (deltaX >> 1), deltaX);
            }
        }
        return points[points.length - 1].scaling();
    }

    /// Converts one sample into the normalized 8-bit grain domain.
    ///
    /// @param sample the decoded sample
    /// @param bitDepth the decoded bit depth
    /// @return the normalized 8-bit grain-domain sample
    private static int toEightBitDomain(int sample, int bitDepth) {
        return bitDepth == 8 ? sample : sample >> (bitDepth - 8);
    }

    /// Returns one deterministic mixed hash for the requested grain sample.
    ///
    /// @param grainSeed the frame grain seed
    /// @param plane the logical plane identifier
    /// @param x the horizontal sample coordinate
    /// @param y the vertical sample coordinate
    /// @param planeSample the current plane sample
    /// @param lumaSample the colocated luma sample
    /// @return one deterministic mixed hash
    private static int mixedHash(int grainSeed, int plane, int x, int y, int planeSample, int lumaSample) {
        int hash = grainSeed;
        hash = hash * 0x45d9f3b + plane;
        hash = hash * 0x45d9f3b + x;
        hash = hash * 0x45d9f3b + y;
        hash = hash * 0x45d9f3b + planeSample;
        hash = hash * 0x45d9f3b + lumaSample;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return hash;
    }

    /// Returns the inclusive minimum sample after optional restricted-range clipping.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param lumaPlane whether the target plane is luma instead of chroma
    /// @param restrictedRange whether restricted-range clipping is enabled
    /// @return the inclusive minimum sample after optional restricted-range clipping
    private static int restrictedRangeMinimum(int bitDepth, boolean lumaPlane, boolean restrictedRange) {
        if (!restrictedRange) {
            return 0;
        }
        return 16 << (bitDepth - 8);
    }

    /// Returns the inclusive maximum sample after optional restricted-range clipping.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param lumaPlane whether the target plane is luma instead of chroma
    /// @param restrictedRange whether restricted-range clipping is enabled
    /// @return the inclusive maximum sample after optional restricted-range clipping
    private static int restrictedRangeMaximum(int bitDepth, boolean lumaPlane, boolean restrictedRange) {
        if (!restrictedRange) {
            return (1 << bitDepth) - 1;
        }
        return (lumaPlane ? 235 : 240) << (bitDepth - 8);
    }

    /// Clamps one signed value into one inclusive integer range.
    ///
    /// @param value the candidate value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
