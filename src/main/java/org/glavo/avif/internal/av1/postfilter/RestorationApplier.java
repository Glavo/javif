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

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.RestorationUnit;
import org.glavo.avif.internal.av1.decode.RestorationUnitMap;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Applies the loop-restoration stage of the postfilter pipeline.
///
/// Inactive restoration preserves samples exactly. Active restoration consumes decoded
/// restoration-unit syntax and applies per-unit Wiener or self-guided filtering. Wiener filtering
/// uses the AV1 two-stage horizontal/vertical rounding model, while self-guided filtering currently
/// uses the local projection model implemented below.
@NotNullByDefault
public final class RestorationApplier {
    /// The AV1 Wiener coefficient precision.
    private static final int FILTER_BITS = 7;

    /// The AV1 Wiener filter tap count.
    private static final int WIENER_TAP_COUNT = 7;

    /// The signed source-sample offset of the first Wiener tap.
    private static final int WIENER_TAP_OFFSET = 3;

    /// The self-guided projection coefficient precision.
    private static final int SELF_GUIDED_PROJECTION_BITS = 7;

    /// The extra restoration precision used before self-guided projection.
    private static final int SELF_GUIDED_RESTORATION_BITS = 4;

    /// The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order.
    private static final int @Unmodifiable [] @Unmodifiable [] SELF_GUIDED_PARAMS = {
            {2, 140, 1, 3236},
            {2, 112, 1, 2158},
            {2, 93, 1, 1618},
            {2, 80, 1, 1438},
            {2, 70, 1, 1295},
            {2, 58, 1, 1177},
            {2, 47, 1, 1079},
            {2, 37, 1, 996},
            {2, 30, 1, 925},
            {2, 25, 1, 863},
            {0, -1, 1, 2589},
            {0, -1, 1, 1618},
            {0, -1, 1, 1177},
            {0, -1, 1, 925},
            {2, 56, 0, -1},
            {2, 22, 0, -1}
    };

    /// Applies restoration to one decoded frame.
    ///
    /// @param decodedPlanes the decoded planes after CDEF
    /// @param restoration the normalized frame-level restoration state
    /// @return the post-restoration planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.RestorationInfo restoration) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.RestorationInfo checkedRestoration = Objects.requireNonNull(restoration, "restoration");
        if (hasActiveRestoration(checkedRestoration, checkedDecodedPlanes.hasChroma())) {
            throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
        }
        return checkedDecodedPlanes;
    }

    /// Applies restoration to one decoded frame using decoded restoration-unit syntax.
    ///
    /// @param decodedPlanes the decoded planes after CDEF
    /// @param restoration the normalized frame-level restoration state
    /// @param syntaxDecodeResult the decoded frame syntax that carries restoration units, or `null`
    /// @return the post-restoration planes
    public DecodedPlanes apply(
            DecodedPlanes decodedPlanes,
            FrameHeader.RestorationInfo restoration,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.RestorationInfo checkedRestoration = Objects.requireNonNull(restoration, "restoration");
        if (!hasActiveRestoration(checkedRestoration, checkedDecodedPlanes.hasChroma())) {
            return checkedDecodedPlanes;
        }
        if (syntaxDecodeResult == null) {
            throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
        }

        RestorationUnitMap unitMap = syntaxDecodeResult.restorationUnitMap();
        DecodedPlane lumaPlane = applyPlane(
                checkedDecodedPlanes.lumaPlane(),
                checkedDecodedPlanes.bitDepth(),
                checkedRestoration,
                unitMap,
                0
        );

        @Nullable DecodedPlane chromaUPlane = checkedDecodedPlanes.chromaUPlane();
        @Nullable DecodedPlane chromaVPlane = checkedDecodedPlanes.chromaVPlane();
        if (checkedDecodedPlanes.hasChroma()) {
            chromaUPlane = applyPlane(
                    Objects.requireNonNull(chromaUPlane, "chromaUPlane"),
                    checkedDecodedPlanes.bitDepth(),
                    checkedRestoration,
                    unitMap,
                    1
            );
            chromaVPlane = applyPlane(
                    Objects.requireNonNull(chromaVPlane, "chromaVPlane"),
                    checkedDecodedPlanes.bitDepth(),
                    checkedRestoration,
                    unitMap,
                    2
            );
        }

        if (lumaPlane == checkedDecodedPlanes.lumaPlane()
                && chromaUPlane == checkedDecodedPlanes.chromaUPlane()
                && chromaVPlane == checkedDecodedPlanes.chromaVPlane()) {
            return checkedDecodedPlanes;
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

    /// Returns whether any plane has active frame-level restoration.
    ///
    /// @param restoration the frame-level restoration state
    /// @param hasChroma whether chroma planes are present
    /// @return whether any plane has active frame-level restoration
    private static boolean hasActiveRestoration(FrameHeader.RestorationInfo restoration, boolean hasChroma) {
        FrameHeader.RestorationType[] types = restoration.types();
        return types[0] != FrameHeader.RestorationType.NONE
                || (hasChroma && (types[1] != FrameHeader.RestorationType.NONE
                || types[2] != FrameHeader.RestorationType.NONE));
    }

    /// Applies restoration to one plane.
    ///
    /// @param plane the source plane
    /// @param bitDepth the decoded bit depth
    /// @param restoration the frame-level restoration state
    /// @param unitMap the decoded restoration-unit map
    /// @param planeIndex the plane index
    /// @return the restored plane, or the original plane when all selected units are disabled
    private static DecodedPlane applyPlane(
            DecodedPlane plane,
            int bitDepth,
            FrameHeader.RestorationInfo restoration,
            RestorationUnitMap unitMap,
            int planeIndex
    ) {
        FrameHeader.RestorationType frameType = restoration.types()[planeIndex];
        if (frameType == FrameHeader.RestorationType.NONE) {
            return plane;
        }

        int unitSize = 1 << (planeIndex == 0 ? restoration.unitSizeLog2Y() : restoration.unitSizeLog2Uv());
        PlaneBuffer source = PlaneBuffer.create(plane, bitDepth);
        PlaneBuffer destination = PlaneBuffer.create(plane, bitDepth);
        boolean changed = false;
        int rows = unitMap.rows(planeIndex);
        int columns = unitMap.columns(planeIndex);
        if (rows == 0 || columns == 0) {
            throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
        }
        for (int unitRow = 0; unitRow < rows; unitRow++) {
            for (int unitColumn = 0; unitColumn < columns; unitColumn++) {
                @Nullable RestorationUnit unit = unitMap.unit(planeIndex, unitRow, unitColumn);
                if (unit == null) {
                    throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
                }
                if (unit.type() == FrameHeader.RestorationType.NONE) {
                    continue;
                }
                int startX = unitColumn * unitSize;
                int startY = unitRow * unitSize;
                int endX = Math.min(plane.width(), startX + unitSize);
                int endY = Math.min(plane.height(), startY + unitSize);
                if (unit.type() == FrameHeader.RestorationType.WIENER) {
                    applyWienerUnit(source, destination, unit, startX, startY, endX, endY);
                } else if (unit.type() == FrameHeader.RestorationType.SELF_GUIDED) {
                    applySelfGuidedUnit(source, destination, unit, startX, startY, endX, endY);
                } else {
                    throw new IllegalStateException("Unsupported restoration unit type: " + unit.type());
                }
                changed = true;
            }
        }
        return changed ? destination.toDecodedPlane() : plane;
    }

    /// Applies one Wiener restoration unit.
    ///
    /// @param source the immutable source plane view
    /// @param destination the mutable destination plane view
    /// @param unit the decoded Wiener unit
    /// @param startX the inclusive unit start X
    /// @param startY the inclusive unit start Y
    /// @param endX the exclusive unit end X
    /// @param endY the exclusive unit end Y
    private static void applyWienerUnit(
            PlaneBuffer source,
            PlaneBuffer destination,
            RestorationUnit unit,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        int[][] coefficients = unit.wienerCoefficients();
        int @Unmodifiable [] horizontalKernel = wienerKernel(coefficients[0]);
        int @Unmodifiable [] verticalKernel = wienerKernel(coefficients[1]);
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                destination.setSample(x, y, wienerSample(source, horizontalKernel, verticalKernel, x, y));
            }
        }
    }

    /// Returns one AV1 Wiener-restored sample using separable two-stage rounding.
    ///
    /// @param source the immutable source plane view
    /// @param horizontalKernel the seven-tap horizontal Wiener kernel
    /// @param verticalKernel the seven-tap vertical Wiener kernel
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one restored sample before final bit-depth clipping
    private static int wienerSample(
            PlaneBuffer source,
            int @Unmodifiable [] horizontalKernel,
            int @Unmodifiable [] verticalKernel,
            int x,
            int y
    ) {
        int bitDepth = source.bitDepth();
        int roundBitsV = 11 - (bitDepth == 12 ? 2 : 0);
        int roundingOffsetV = 1 << (roundBitsV - 1);
        int roundOffset = 1 << (bitDepth + roundBitsV - 1);
        int sum = -roundOffset;
        for (int tap = 0; tap < WIENER_TAP_COUNT; tap++) {
            int sourceY = clamp(y + tap - WIENER_TAP_OFFSET, 0, source.height() - 1);
            sum += verticalKernel[tap] * wienerHorizontalSample(source, horizontalKernel, x, sourceY);
        }
        return (sum + roundingOffsetV) >> roundBitsV;
    }

    /// Returns one horizontally filtered AV1 Wiener intermediate sample.
    ///
    /// @param source the immutable source plane view
    /// @param horizontalKernel the seven-tap horizontal Wiener kernel
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one clipped horizontal Wiener intermediate sample
    private static int wienerHorizontalSample(
            PlaneBuffer source,
            int @Unmodifiable [] horizontalKernel,
            int x,
            int y
    ) {
        int bitDepth = source.bitDepth();
        int roundBitsH = 3 + (bitDepth == 12 ? 2 : 0);
        int roundingOffsetH = 1 << (roundBitsH - 1);
        int clipLimit = 1 << (bitDepth + 1 + FILTER_BITS - roundBitsH);
        int sum = 1 << (bitDepth + 6);
        for (int tap = 0; tap < WIENER_TAP_COUNT; tap++) {
            int sourceX = clamp(x + tap - WIENER_TAP_OFFSET, 0, source.width() - 1);
            sum += horizontalKernel[tap] * source.sample(sourceX, y);
        }
        return clamp((sum + roundingOffsetH) >> roundBitsH, 0, clipLimit - 1);
    }

    /// Applies one self-guided restoration unit.
    ///
    /// @param source the immutable source plane view
    /// @param destination the mutable destination plane view
    /// @param unit the decoded self-guided unit
    /// @param startX the inclusive unit start X
    /// @param startY the inclusive unit start Y
    /// @param endX the exclusive unit end X
    /// @param endY the exclusive unit end Y
    private static void applySelfGuidedUnit(
            PlaneBuffer source,
            PlaneBuffer destination,
            RestorationUnit unit,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        int[] params = SELF_GUIDED_PARAMS[unit.selfGuidedSet()];
        int[] projection = unit.selfGuidedProjectionCoefficients();
        int shift = SELF_GUIDED_PROJECTION_BITS + SELF_GUIDED_RESTORATION_BITS;
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int base = source.sample(x, y);
                int baseRestoration = base << SELF_GUIDED_RESTORATION_BITS;
                int adjustment = 0;
                for (int filter = 0; filter < 2; filter++) {
                    int radius = params[filter * 2];
                    if (radius == 0 || projection[filter] == 0) {
                        continue;
                    }
                    int guided = boxAverage(source, x, y, radius) << SELF_GUIDED_RESTORATION_BITS;
                    adjustment += projection[filter] * (guided - baseRestoration);
                }
                destination.setSample(x, y, base + round2(adjustment, shift));
            }
        }
    }

    /// Builds one symmetric seven-tap Wiener filter kernel.
    ///
    /// @param coefficients the three coded Wiener coefficients
    /// @return one symmetric seven-tap Wiener filter kernel
    private static int @Unmodifiable [] wienerKernel(int @Unmodifiable [] coefficients) {
        int c0 = coefficients[0];
        int c1 = coefficients[1];
        int c2 = coefficients[2];
        return new int[]{
                c0,
                c1,
                c2,
                (1 << FILTER_BITS) - 2 * (c0 + c1 + c2),
                c2,
                c1,
                c0
        };
    }

    /// Computes one clamped square box average.
    ///
    /// @param source the source plane
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @param radius the box radius
    /// @return one clamped square box average
    private static int boxAverage(PlaneBuffer source, int x, int y, int radius) {
        int sum = 0;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int sampleY = clamp(y + dy, 0, source.height() - 1);
            for (int dx = -radius; dx <= radius; dx++) {
                sum += source.sample(clamp(x + dx, 0, source.width() - 1), sampleY);
                count++;
            }
        }
        return (sum + (count >> 1)) / count;
    }

    /// Clips one integer into inclusive bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive minimum
    /// @param maximum the inclusive maximum
    /// @return the clipped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Rounds one integer right shift by adding half the divisor.
    ///
    /// @param value the value to round
    /// @param bits the number of bits to discard
    /// @return the rounded shifted value
    private static int round2(int value, int bits) {
        if (bits == 0) {
            return value;
        }
        return (value + (1 << (bits - 1))) >> bits;
    }

    /// Mutable plane storage used by restoration filtering.
    @NotNullByDefault
    private static final class PlaneBuffer {
        /// The plane width in samples.
        private final int width;

        /// The plane height in samples.
        private final int height;

        /// The sample stride of one plane row.
        private final int stride;

        /// The decoded bit depth.
        private final int bitDepth;

        /// The maximum legal sample value.
        private final int maxSampleValue;

        /// The mutable sample storage in row-major order.
        private final short[] samples;

        /// Creates one mutable plane buffer.
        ///
        /// @param width the plane width in samples
        /// @param height the plane height in samples
        /// @param stride the sample stride of one plane row
        /// @param bitDepth the decoded bit depth
        /// @param samples the mutable sample storage
        private PlaneBuffer(int width, int height, int stride, int bitDepth, short[] samples) {
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.bitDepth = bitDepth;
            this.maxSampleValue = (1 << bitDepth) - 1;
            this.samples = Objects.requireNonNull(samples, "samples");
        }

        /// Creates a mutable copy of one decoded plane.
        ///
        /// @param plane the decoded plane
        /// @param bitDepth the decoded bit depth
        /// @return a mutable copy of one decoded plane
        public static PlaneBuffer create(DecodedPlane plane, int bitDepth) {
            DecodedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
            return new PlaneBuffer(
                    checkedPlane.width(),
                    checkedPlane.height(),
                    checkedPlane.stride(),
                    bitDepth,
                    checkedPlane.samples()
            );
        }

        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        public int width() {
            return width;
        }

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        public int height() {
            return height;
        }

        /// Returns the decoded bit depth.
        ///
        /// @return the decoded bit depth
        public int bitDepth() {
            return bitDepth;
        }

        /// Returns one sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one sample
        public int sample(int x, int y) {
            return samples[y * stride + x] & 0xFFFF;
        }

        /// Stores one clipped sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param value the replacement value
        public void setSample(int x, int y, int value) {
            samples[y * stride + x] = (short) clamp(value, 0, maxSampleValue);
        }

        /// Returns one immutable decoded plane from the current samples.
        ///
        /// @return one immutable decoded plane from the current samples
        public DecodedPlane toDecodedPlane() {
            return new DecodedPlane(width, height, stride, samples);
        }
    }
}
