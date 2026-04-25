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
package org.glavo.avif.testutil;

import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Test oracle for AV1 normative horizontal super-resolution upscaling.
@NotNullByDefault
public final class SuperResolutionOracle {
    /// The number of coefficients in one normative super-resolution filter.
    private static final int FILTER_TAP_COUNT = 8;

    /// The AV1 normative super-resolution filter normalization shift.
    private static final int FILTER_BITS = 7;

    /// The number of AV1 super-resolution fractional phases.
    private static final int SUBPEL_BITS = 6;

    /// The mask for one AV1 super-resolution fractional phase.
    private static final int SUBPEL_MASK = (1 << SUBPEL_BITS) - 1;

    /// The fixed-point precision used by AV1 super-resolution coordinate stepping.
    private static final int SCALE_SUBPEL_BITS = 14;

    /// The mask for one AV1 super-resolution fixed-point coordinate.
    private static final int SCALE_SUBPEL_MASK = (1 << SCALE_SUBPEL_BITS) - 1;

    /// The extra fixed-point bits above the normative phase precision.
    private static final int SCALE_EXTRA_BITS = SCALE_SUBPEL_BITS - SUBPEL_BITS;

    /// The AV1 half-phase offset used for the first output sample.
    private static final int SCALE_EXTRA_OFFSET = 1 << (SCALE_EXTRA_BITS - 1);

    /// The signed source-sample offset of the first normative super-resolution tap.
    private static final int FILTER_START_OFFSET = FILTER_TAP_COUNT / 2 - 1;

    /// The AV1 normative 64-phase horizontal super-resolution filters.
    private static final int @Unmodifiable [] @Unmodifiable [] FILTERS = {
            {0, 0, 0, 128, 0, 0, 0, 0},
            {0, 0, -1, 128, 2, -1, 0, 0},
            {0, 1, -3, 127, 4, -2, 1, 0},
            {0, 1, -4, 127, 6, -3, 1, 0},
            {0, 2, -6, 126, 8, -3, 1, 0},
            {0, 2, -7, 125, 11, -4, 1, 0},
            {-1, 2, -8, 125, 13, -5, 2, 0},
            {-1, 3, -9, 124, 15, -6, 2, 0},
            {-1, 3, -10, 123, 18, -6, 2, -1},
            {-1, 3, -11, 122, 20, -7, 3, -1},
            {-1, 4, -12, 121, 22, -8, 3, -1},
            {-1, 4, -13, 120, 25, -9, 3, -1},
            {-1, 4, -14, 118, 28, -9, 3, -1},
            {-1, 4, -15, 117, 30, -10, 4, -1},
            {-1, 5, -16, 116, 32, -11, 4, -1},
            {-1, 5, -16, 114, 35, -12, 4, -1},
            {-1, 5, -17, 112, 38, -12, 4, -1},
            {-1, 5, -18, 111, 40, -13, 5, -1},
            {-1, 5, -18, 109, 43, -14, 5, -1},
            {-1, 6, -19, 107, 45, -14, 5, -1},
            {-1, 6, -19, 105, 48, -15, 5, -1},
            {-1, 6, -19, 103, 51, -16, 5, -1},
            {-1, 6, -20, 101, 53, -16, 6, -1},
            {-1, 6, -20, 99, 56, -17, 6, -1},
            {-1, 6, -20, 97, 58, -17, 6, -1},
            {-1, 6, -20, 95, 61, -18, 6, -1},
            {-2, 7, -20, 93, 64, -18, 6, -2},
            {-2, 7, -20, 91, 66, -19, 6, -1},
            {-2, 7, -20, 88, 69, -19, 6, -1},
            {-2, 7, -20, 86, 71, -19, 6, -1},
            {-2, 7, -20, 84, 74, -20, 7, -2},
            {-2, 7, -20, 81, 76, -20, 7, -1},
            {-2, 7, -20, 79, 79, -20, 7, -2},
            {-1, 7, -20, 76, 81, -20, 7, -2},
            {-2, 7, -20, 74, 84, -20, 7, -2},
            {-1, 6, -19, 71, 86, -20, 7, -2},
            {-1, 6, -19, 69, 88, -20, 7, -2},
            {-1, 6, -19, 66, 91, -20, 7, -2},
            {-2, 6, -18, 64, 93, -20, 7, -2},
            {-1, 6, -18, 61, 95, -20, 6, -1},
            {-1, 6, -17, 58, 97, -20, 6, -1},
            {-1, 6, -17, 56, 99, -20, 6, -1},
            {-1, 6, -16, 53, 101, -20, 6, -1},
            {-1, 5, -16, 51, 103, -19, 6, -1},
            {-1, 5, -15, 48, 105, -19, 6, -1},
            {-1, 5, -14, 45, 107, -19, 6, -1},
            {-1, 5, -14, 43, 109, -18, 5, -1},
            {-1, 5, -13, 40, 111, -18, 5, -1},
            {-1, 4, -12, 38, 112, -17, 5, -1},
            {-1, 4, -12, 35, 114, -16, 5, -1},
            {-1, 4, -11, 32, 116, -16, 5, -1},
            {-1, 4, -10, 30, 117, -15, 4, -1},
            {-1, 3, -9, 28, 118, -14, 4, -1},
            {-1, 3, -9, 25, 120, -13, 4, -1},
            {-1, 3, -8, 22, 121, -12, 4, -1},
            {-1, 3, -7, 20, 122, -11, 3, -1},
            {-1, 2, -6, 18, 123, -10, 3, -1},
            {0, 2, -6, 15, 124, -9, 3, -1},
            {0, 2, -5, 13, 125, -8, 2, -1},
            {0, 1, -4, 11, 125, -7, 2, 0},
            {0, 1, -3, 8, 126, -6, 2, 0},
            {0, 1, -3, 6, 127, -4, 1, 0},
            {0, 1, -2, 4, 127, -3, 1, 0},
            {0, 0, -1, 2, 128, -1, 0, 0}
    };

    /// Prevents construction.
    private SuperResolutionOracle() {
    }

    /// Returns the expected horizontally upscaled raster for one decoded plane.
    ///
    /// @param sourcePlane the coded-domain source plane
    /// @param targetWidth the post-upscale plane width
    /// @param bitDepth the decoded sample bit depth
    /// @return the expected horizontally upscaled raster
    public static int[][] upscalePlane(DecodedPlane sourcePlane, int targetWidth, int bitDepth) {
        int[][] expected = new int[sourcePlane.height()][targetWidth];
        for (int y = 0; y < sourcePlane.height(); y++) {
            int[] sourceRow = new int[sourcePlane.width()];
            for (int x = 0; x < sourcePlane.width(); x++) {
                sourceRow[x] = sourcePlane.sample(x, y);
            }
            expected[y] = upscaleRow(sourceRow, targetWidth, bitDepth);
        }
        return expected;
    }

    /// Returns the expected horizontally upscaled raster for one caller-supplied coded-domain block.
    ///
    /// @param sourceBlock the coded-domain source block in row-major order
    /// @param targetWidth the post-upscale row width
    /// @param bitDepth the decoded sample bit depth
    /// @return the expected horizontally upscaled raster
    public static int[][] upscaleBlock(int[][] sourceBlock, int targetWidth, int bitDepth) {
        int[][] expected = new int[sourceBlock.length][targetWidth];
        for (int y = 0; y < sourceBlock.length; y++) {
            expected[y] = upscaleRow(sourceBlock[y], targetWidth, bitDepth);
        }
        return expected;
    }

    /// Returns the expected horizontally upscaled row.
    ///
    /// @param sourceRow the coded-domain source row
    /// @param targetWidth the post-upscale row width
    /// @param bitDepth the decoded sample bit depth
    /// @return the expected horizontally upscaled row
    public static int[] upscaleRow(int[] sourceRow, int targetWidth, int bitDepth) {
        if (sourceRow.length == 0) {
            throw new IllegalArgumentException("sourceRow must not be empty");
        }
        if (targetWidth <= 0) {
            throw new IllegalArgumentException("targetWidth must be positive");
        }
        if (targetWidth < sourceRow.length) {
            throw new IllegalArgumentException("targetWidth must not be smaller than source width");
        }
        int[] upscaledRow = new int[targetWidth];
        if (targetWidth == sourceRow.length) {
            System.arraycopy(sourceRow, 0, upscaledRow, 0, sourceRow.length);
            return upscaledRow;
        }

        int maximumSample = (1 << bitDepth) - 1;
        int step = upscaleConvolveStep(sourceRow.length, targetWidth);
        int position = upscaleConvolveInitialPosition(sourceRow.length, targetWidth, step);
        for (int x = 0; x < targetWidth; x++) {
            int integerPosition = position >> SCALE_SUBPEL_BITS;
            int filterIndex = (position & SCALE_SUBPEL_MASK) >> SCALE_EXTRA_BITS;
            int[] filter = FILTERS[filterIndex];
            long sum = 0;
            for (int tap = 0; tap < FILTER_TAP_COUNT; tap++) {
                int sourceX = clamp(integerPosition + tap - FILTER_START_OFFSET, 0, sourceRow.length - 1);
                sum += (long) sourceRow[sourceX] * filter[tap];
            }
            upscaledRow[x] = clamp(roundShiftSigned(sum, FILTER_BITS), 0, maximumSample);
            position += step;
        }
        return upscaledRow;
    }

    /// Returns the AV1 super-resolution fixed-point step for one source and target width.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @return the fixed-point horizontal step
    private static int upscaleConvolveStep(int sourceWidth, int targetWidth) {
        return (int) ((((long) sourceWidth << SCALE_SUBPEL_BITS) + (targetWidth >> 1)) / targetWidth);
    }

    /// Returns the AV1 super-resolution fixed-point position for the first output sample.
    ///
    /// @param sourceWidth the downscaled coded-domain source width
    /// @param targetWidth the post-super-resolution target width
    /// @param step the fixed-point horizontal step
    /// @return the initial fixed-point horizontal position
    private static int upscaleConvolveInitialPosition(int sourceWidth, int targetWidth, int step) {
        long error = (long) targetWidth * step - ((long) sourceWidth << SCALE_SUBPEL_BITS);
        int position = (int) (((-((long) targetWidth - sourceWidth) << (SCALE_SUBPEL_BITS - 1))
                + (targetWidth >> 1)) / targetWidth)
                + SCALE_EXTRA_OFFSET
                - (int) (error >> 1);
        return position & SCALE_SUBPEL_MASK;
    }

    /// Rounds one signed integer by the requested arithmetic right shift.
    ///
    /// @param value the signed value to round
    /// @param bits the number of low bits to discard
    /// @return the rounded signed value
    private static int roundShiftSigned(long value, int bits) {
        long roundingOffset = 1L << (bits - 1);
        if (value >= 0) {
            return (int) ((value + roundingOffset) >> bits);
        }
        return (int) -(((-value) + roundingOffset) >> bits);
    }

    /// Clamps one integer value to the supplied inclusive bounds.
    ///
    /// @param value the value to clamp
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
