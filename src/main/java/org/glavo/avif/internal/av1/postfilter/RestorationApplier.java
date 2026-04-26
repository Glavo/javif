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
/// uses the AV1 two-stage horizontal/vertical rounding model, while self-guided filtering uses the
/// AV1 self-guided A/B projection model.
@NotNullByDefault
public final class RestorationApplier {
    /// The AV1 Wiener coefficient precision.
    private static final int FILTER_BITS = 7;

    /// The AV1 Wiener filter tap count.
    private static final int WIENER_TAP_COUNT = 7;

    /// The signed source-sample offset of the first Wiener tap.
    private static final int WIENER_TAP_OFFSET = 3;

    /// The final self-guided projection rounding shift used by dav1d.
    private static final int SELF_GUIDED_WEIGHT_BITS = 11;

    /// The self-guided 3x3 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_3_BITS = 9;

    /// The self-guided paired 5x5 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_5_PAIR_BITS = 9;

    /// The self-guided single-row 5x5 A/B finish rounding shift.
    private static final int SELF_GUIDED_FILTER_5_SINGLE_BITS = 8;

    /// The maximum self-guided variance table index.
    private static final int SELF_GUIDED_MAX_Z = 255;

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
        int @Unmodifiable [] params = SELF_GUIDED_PARAMS[unit.selfGuidedSet()];
        int @Unmodifiable [] projection = unit.selfGuidedProjectionCoefficients();
        @Nullable SelfGuidedIntermediate radius2Filter = params[0] != 0
                ? SelfGuidedIntermediate.create(source, 2, params[1])
                : null;
        @Nullable SelfGuidedIntermediate radius1Filter = params[2] != 0
                ? SelfGuidedIntermediate.create(source, 1, params[3])
                : null;
        int weight0 = radius2Filter != null ? projection[0] : 0;
        int weight1 = radius1Filter != null ? 128 - projection[0] - projection[1] : 0;
        for (int y = startY; y < endY; y++) {
            int localY = y - startY;
            for (int x = startX; x < endX; x++) {
                int base = source.sample(x, y);
                int adjustment = 0;
                if (radius2Filter != null && weight0 != 0) {
                    adjustment += weight0 * radius2Filter.residual5(x, y, localY, base);
                }
                if (radius1Filter != null && weight1 != 0) {
                    adjustment += weight1 * radius1Filter.residual3(x, y, base);
                }
                destination.setSample(x, y, base + round2(adjustment, SELF_GUIDED_WEIGHT_BITS));
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

    /// Self-guided A/B projection fields for one filter radius.
    @NotNullByDefault
    private static final class SelfGuidedIntermediate {
        /// The source plane width in samples.
        private final int width;

        /// The source plane height in samples.
        private final int height;

        /// The inverted A field used by the dav1d finish equations.
        private final int @Unmodifiable [] a;

        /// The inverted B field used by the dav1d finish equations.
        private final int @Unmodifiable [] b;

        /// Creates one self-guided intermediate field.
        ///
        /// @param width the source plane width in samples
        /// @param height the source plane height in samples
        /// @param a the inverted A field
        /// @param b the inverted B field
        private SelfGuidedIntermediate(int width, int height, int @Unmodifiable [] a, int @Unmodifiable [] b) {
            this.width = width;
            this.height = height;
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
        }

        /// Computes one self-guided intermediate field.
        ///
        /// @param source the source plane
        /// @param radius the self-guided filter radius
        /// @param strength the self-guided strength value from the AV1 parameter table
        /// @return one self-guided intermediate field
        public static SelfGuidedIntermediate create(PlaneBuffer source, int radius, int strength) {
            if (radius != 1 && radius != 2) {
                throw new IllegalArgumentException("radius must be 1 or 2: " + radius);
            }
            int width = source.width();
            int height = source.height();
            int[] a = new int[(width + 2) * (height + 2)];
            int[] b = new int[(width + 2) * (height + 2)];
            int count = radius == 1 ? 9 : 25;
            int oneByX = radius == 1 ? 455 : 164;
            int bitDepthShift = source.bitDepth() - 8;
            for (int y = -1; y <= height; y++) {
                for (int x = -1; x <= width; x++) {
                    BoxSum box = boxSum(source, x, y, radius);
                    Projection projection = projection(box, count, strength, oneByX, bitDepthShift);
                    int index = (y + 1) * (width + 2) + x + 1;
                    a[index] = projection.a();
                    b[index] = projection.b();
                }
            }
            return new SelfGuidedIntermediate(width, height, a, b);
        }

        /// Returns one 3x3 residual from the inverted A/B fields.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param sourceSample the original source sample
        /// @return one 3x3 residual
        public int residual3(int x, int y, int sourceSample) {
            int weightedB = eightNeighborWeight(b, x, y);
            int weightedA = eightNeighborWeight(a, x, y);
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_3_BITS);
        }

        /// Returns one 5x5 residual from the inverted A/B fields.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param localY the sample Y coordinate relative to the restoration unit
        /// @param sourceSample the original source sample
        /// @return one 5x5 residual
        public int residual5(int x, int y, int localY, int sourceSample) {
            if ((localY & 1) == 0) {
                int weightedB = sixNeighborPairWeight(b, x, y);
                int weightedA = sixNeighborPairWeight(a, x, y);
                return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_PAIR_BITS);
            }
            int weightedB = sixNeighborSingleWeight(b, x, y);
            int weightedA = sixNeighborSingleWeight(a, x, y);
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_SINGLE_BITS);
        }

        /// Returns one clamped field value.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one clamped field value
        private int value(int @Unmodifiable [] values, int x, int y) {
            int clampedX = clamp(x, -1, width);
            int clampedY = clamp(y, -1, height);
            return values[(clampedY + 1) * (width + 2) + clampedX + 1];
        }

        /// Returns the dav1d eight-neighbor weighted sum for one field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int eightNeighborWeight(int @Unmodifiable [] values, int x, int y) {
            return (value(values, x, y)
                    + value(values, x - 1, y)
                    + value(values, x + 1, y)
                    + value(values, x, y - 1)
                    + value(values, x, y + 1)) * 4
                    + (value(values, x - 1, y - 1)
                    + value(values, x + 1, y - 1)
                    + value(values, x - 1, y + 1)
                    + value(values, x + 1, y + 1)) * 3;
        }

        /// Returns the dav1d paired-row weighted sum for one 5x5 field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int sixNeighborPairWeight(int @Unmodifiable [] values, int x, int y) {
            int topY = y - 1;
            int bottomY = y + 1;
            return (value(values, x, topY) + value(values, x, bottomY)) * 6
                    + (value(values, x - 1, topY)
                    + value(values, x + 1, topY)
                    + value(values, x - 1, bottomY)
                    + value(values, x + 1, bottomY)) * 5;
        }

        /// Returns the dav1d single-row weighted sum for one 5x5 field.
        ///
        /// @param values the field storage
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return the weighted field sum
        private int sixNeighborSingleWeight(int @Unmodifiable [] values, int x, int y) {
            return value(values, x, y) * 6
                    + (value(values, x - 1, y) + value(values, x + 1, y)) * 5;
        }

        /// Computes one clamped box sum and squared sum.
        ///
        /// @param source the source plane
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param radius the self-guided filter radius
        /// @return the box sums
        private static BoxSum boxSum(PlaneBuffer source, int x, int y, int radius) {
            int sum = 0;
            int sumSquares = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                int sampleY = clamp(y + dy, 0, source.height() - 1);
                for (int dx = -radius; dx <= radius; dx++) {
                    int sample = source.sample(clamp(x + dx, 0, source.width() - 1), sampleY);
                    sum += sample;
                    sumSquares += sample * sample;
                }
            }
            return new BoxSum(sum, sumSquares);
        }

        /// Computes the inverted dav1d A/B projection values for one box.
        ///
        /// @param box the source box sums
        /// @param count the number of samples in the box
        /// @param strength the self-guided strength value
        /// @param oneByX the reciprocal normalization constant
        /// @param bitDepthShift the decoded bit depth minus eight
        /// @return the inverted A/B projection values
        private static Projection projection(BoxSum box, int count, int strength, int oneByX, int bitDepthShift) {
            int scaledSumSquares = roundForBitDepth(box.sumSquares(), bitDepthShift * 2);
            int scaledSum = roundForBitDepth(box.sum(), bitDepthShift);
            long variance = Math.max((long) scaledSumSquares * count - (long) scaledSum * scaledSum, 0L);
            int z = (int) Math.min(SELF_GUIDED_MAX_Z, (variance * strength + (1 << 19)) >> 20);
            int xByX = selfGuidedXByX(z);
            int a = (int) (((long) xByX * box.sum() * oneByX + (1 << 11)) >> 12);
            return new Projection(a, xByX);
        }

        /// Rounds one box statistic down to the normalized 8-bit domain.
        ///
        /// @param value the source statistic
        /// @param bits the number of bits to remove
        /// @return the rounded statistic
        private static int roundForBitDepth(int value, int bits) {
            if (bits == 0) {
                return value;
            }
            return (value + ((1 << bits) >> 1)) >> bits;
        }

        /// Returns one entry from dav1d's `sgr_x_by_x` table.
        ///
        /// @param z the clamped variance index
        /// @return the self-guided reciprocal table value
        private static int selfGuidedXByX(int z) {
            if (z == SELF_GUIDED_MAX_Z) {
                return 0;
            }
            return Math.min(255, (256 + (z >> 1)) / (z + 1));
        }
    }

    /// Box sums used by self-guided restoration.
    ///
    /// @param sum the sample sum
    /// @param sumSquares the squared sample sum
    @NotNullByDefault
    private record BoxSum(int sum, int sumSquares) {
    }

    /// Inverted self-guided projection values.
    ///
    /// @param a the inverted A value
    /// @param b the inverted B value
    @NotNullByDefault
    private record Projection(int a, int b) {
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
