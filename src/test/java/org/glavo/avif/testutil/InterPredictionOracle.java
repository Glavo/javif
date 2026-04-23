package org.glavo.avif.testutil;

import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.jetbrains.annotations.NotNullByDefault;

/// Test-only oracle for the current inter-prediction subset supported by the reconstruction core.
///
/// The implementation intentionally mirrors the current production contract without calling package-
/// private reconstruction helpers, so unit and integration tests can assert exact pixel values for
/// integer-copy, `BILINEAR`, and fixed `EIGHT_TAP_*` subpel prediction.
@NotNullByDefault
public final class InterPredictionOracle {

    /// The number of taps in the current AV1 fixed inter filter.
    private static final int INTER_FILTER_TAP_COUNT = 8;

    /// The center offset used by the current AV1 fixed inter filter.
    private static final int INTER_FILTER_START_OFFSET = 3;

    /// The filter coefficient normalization shift.
    private static final int INTER_FILTER_BITS = 6;

    /// The number of AV1 fixed-filter phases.
    private static final int INTER_FILTER_PHASES = 16;

    /// The current regular eight-tap fixed-filter coefficients indexed by phase.
    private static final int[][] REGULAR_SUBPEL_FILTERS = {
            {0, 1, -3, 63, 4, -1, 0, 0},
            {0, 1, -5, 61, 9, -2, 0, 0},
            {0, 1, -6, 58, 14, -4, 1, 0},
            {0, 1, -7, 55, 19, -5, 1, 0},
            {0, 1, -7, 51, 24, -6, 1, 0},
            {0, 1, -8, 47, 29, -6, 1, 0},
            {0, 1, -7, 42, 33, -6, 1, 0},
            {0, 1, -7, 38, 38, -7, 1, 0},
            {0, 1, -6, 33, 42, -7, 1, 0},
            {0, 1, -6, 29, 47, -8, 1, 0},
            {0, 1, -6, 24, 51, -7, 1, 0},
            {0, 1, -5, 19, 55, -7, 1, 0},
            {0, 1, -4, 14, 58, -6, 1, 0},
            {0, 0, -2, 9, 61, -5, 1, 0},
            {0, 0, -1, 4, 63, -3, 1, 0}
    };

    /// The current smooth eight-tap fixed-filter coefficients indexed by phase.
    private static final int[][] SMOOTH_SUBPEL_FILTERS = {
            {0, 1, 14, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, -1, 8, 27, 24, 6, 0, 0},
            {0, -1, 7, 26, 26, 7, -1, 0},
            {0, 0, 6, 24, 27, 8, -1, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 14, 1, 0}
    };

    /// The current sharp eight-tap fixed-filter coefficients indexed by phase.
    private static final int[][] SHARP_SUBPEL_FILTERS = {
            {-1, 1, -3, 63, 4, -1, 1, 0},
            {-1, 3, -6, 62, 8, -3, 2, -1},
            {-1, 4, -9, 60, 13, -5, 3, -1},
            {-2, 5, -11, 58, 19, -7, 3, -1},
            {-2, 5, -11, 54, 24, -9, 4, -1},
            {-2, 5, -12, 50, 30, -10, 4, -1},
            {-2, 5, -12, 45, 35, -11, 5, -1},
            {-2, 6, -12, 40, 40, -12, 6, -2},
            {-1, 5, -11, 35, 45, -12, 5, -2},
            {-1, 4, -10, 30, 50, -12, 5, -2},
            {-1, 4, -9, 24, 54, -11, 5, -2},
            {-1, 3, -7, 19, 58, -11, 5, -2},
            {-1, 3, -5, 13, 60, -9, 4, -1},
            {-1, 2, -3, 8, 62, -6, 3, -1},
            {0, 1, -1, 4, 63, -3, 1, -1}
    };

    /// The reduced-width regular eight-tap fixed-filter coefficients indexed by phase.
    private static final int[][] SMALL_REGULAR_SUBPEL_FILTERS = {
            {0, 0, -2, 63, 4, -1, 0, 0},
            {0, 0, -4, 61, 9, -2, 0, 0},
            {0, 0, -5, 58, 14, -3, 0, 0},
            {0, 0, -6, 55, 19, -4, 0, 0},
            {0, 0, -6, 51, 24, -5, 0, 0},
            {0, 0, -7, 47, 29, -5, 0, 0},
            {0, 0, -6, 42, 33, -5, 0, 0},
            {0, 0, -6, 38, 38, -6, 0, 0},
            {0, 0, -5, 33, 42, -6, 0, 0},
            {0, 0, -5, 29, 47, -7, 0, 0},
            {0, 0, -5, 24, 51, -6, 0, 0},
            {0, 0, -4, 19, 55, -6, 0, 0},
            {0, 0, -3, 14, 58, -5, 0, 0},
            {0, 0, -2, 9, 61, -4, 0, 0},
            {0, 0, -1, 4, 63, -2, 0, 0}
    };

    /// The reduced-width smooth eight-tap fixed-filter coefficients indexed by phase.
    private static final int[][] SMALL_SMOOTH_SUBPEL_FILTERS = {
            {0, 0, 15, 31, 17, 1, 0, 0},
            {0, 0, 13, 31, 18, 2, 0, 0},
            {0, 0, 11, 31, 20, 2, 0, 0},
            {0, 0, 10, 30, 21, 3, 0, 0},
            {0, 0, 9, 29, 22, 4, 0, 0},
            {0, 0, 8, 28, 23, 5, 0, 0},
            {0, 0, 7, 27, 24, 6, 0, 0},
            {0, 0, 6, 26, 26, 6, 0, 0},
            {0, 0, 6, 24, 27, 7, 0, 0},
            {0, 0, 5, 23, 28, 8, 0, 0},
            {0, 0, 4, 22, 29, 9, 0, 0},
            {0, 0, 3, 21, 30, 10, 0, 0},
            {0, 0, 2, 20, 31, 11, 0, 0},
            {0, 0, 2, 18, 31, 13, 0, 0},
            {0, 0, 1, 17, 31, 15, 0, 0}
    };

    /// Prevents instantiation of the utility holder.
    private InterPredictionOracle() {
    }

    /// Samples one rectangular reference-plane footprint using the current supported inter filter
    /// subset.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param width the sampled block width in samples
    /// @param height the sampled block height in samples
    /// @param sourceNumeratorX the source origin numerator in plane-local sample units
    /// @param sourceNumeratorY the source origin numerator in plane-local sample units
    /// @param denominatorX the horizontal plane-local denominator
    /// @param denominatorY the vertical plane-local denominator
    /// @param widthForFilterSelection the sampled block width used for reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height used for reduced-width filter selection
    /// @param filterMode the frame-level interpolation filter
    /// @return one sampled reference-plane block in row-major order
    public static int[][] sampleReferencePlaneBlock(
            DecodedPlane referencePlane,
            int width,
            int height,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter filterMode
    ) {
        int[][] samples = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y][x] = sampleInterPlaneValue(
                        referencePlane,
                        sourceNumeratorX + x * denominatorX,
                        sourceNumeratorY + y * denominatorY,
                        denominatorX,
                        denominatorY,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        filterMode
                );
            }
        }
        return samples;
    }

    /// Averages two sampled inter-prediction blocks with the same dimensions.
    ///
    /// @param first the primary sampled block in row-major order
    /// @param second the secondary sampled block in row-major order
    /// @return one averaged compound block
    public static int[][] averageBlocks(int[][] first, int[][] second) {
        int height = first.length;
        int width = height == 0 ? 0 : first[0].length;
        int[][] averaged = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                averaged[y][x] = (first[y][x] + second[y][x] + 1) >> 1;
            }
        }
        return averaged;
    }

    /// Samples one plane-local inter value at one source numerator.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal plane-local denominator
    /// @param denominatorY the vertical plane-local denominator
    /// @param widthForFilterSelection the sampled block width used for reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height used for reduced-width filter selection
    /// @param filterMode the frame-level interpolation filter
    /// @return one sampled inter value
    private static int sampleInterPlaneValue(
            DecodedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter filterMode
    ) {
        if (Math.floorMod(sourceNumeratorX, denominatorX) == 0 && Math.floorMod(sourceNumeratorY, denominatorY) == 0) {
            return referencePlane.sample(
                    clamp(Math.floorDiv(sourceNumeratorX, denominatorX), 0, referencePlane.width() - 1),
                    clamp(Math.floorDiv(sourceNumeratorY, denominatorY), 0, referencePlane.height() - 1)
            );
        }
        if (filterMode == FrameHeader.InterpolationFilter.BILINEAR) {
            return bilinearInterpolateAt(referencePlane, sourceNumeratorX, sourceNumeratorY, denominatorX, denominatorY);
        }
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int phaseY = interpolationPhase(Math.floorMod(sourceNumeratorY, denominatorY), denominatorY);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int phaseX = interpolationPhase(Math.floorMod(sourceNumeratorX, denominatorX), denominatorX);
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                    clamp(sourceX0, 0, referencePlane.width() - 1),
                    clamp(sourceY0, 0, referencePlane.height() - 1)
            );
        }

        int @org.jetbrains.annotations.Nullable [] horizontalFilter = phaseX == 0
                ? null
                : selectSubpelFilter(filterMode, phaseX, widthForFilterSelection);
        int @org.jetbrains.annotations.Nullable [] verticalFilter = phaseY == 0
                ? null
                : selectSubpelFilter(filterMode, phaseY, heightForFilterSelection);

        if (verticalFilter == null) {
            long filtered = horizontalInterpolate(referencePlane, sourceX0, sourceY0, horizontalFilter);
            return clamp(roundShiftSigned(filtered, INTER_FILTER_BITS), 0, 255);
        }
        if (horizontalFilter == null) {
            long filtered = verticalInterpolate(referencePlane, sourceX0, sourceY0, verticalFilter);
            return clamp(roundShiftSigned(filtered, INTER_FILTER_BITS), 0, 255);
        }

        long[] horizontallyFilteredRows = new long[INTER_FILTER_TAP_COUNT];
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            horizontallyFilteredRows[tapIndex] = horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter);
        }
        long combined = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            combined += (long) verticalFilter[tapIndex] * horizontallyFilteredRows[tapIndex];
        }
        return clamp(roundShiftSigned(combined, INTER_FILTER_BITS * 2), 0, 255);
    }

    /// Applies one horizontal AV1 fixed filter at one source location.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX0 the unfiltered horizontal sample origin
    /// @param sourceY the row to sample
    /// @param filter the selected horizontal filter taps
    /// @return one horizontally filtered signed sample
    private static long horizontalInterpolate(
            DecodedPlane referencePlane,
            int sourceX0,
            int sourceY,
            int[] filter
    ) {
        long filtered = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceX = clamp(sourceX0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.width() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, sourceY);
        }
        return filtered;
    }

    /// Applies one vertical AV1 fixed filter at one source location.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceX the column to sample
    /// @param sourceY0 the unfiltered vertical sample origin
    /// @param filter the selected vertical filter taps
    /// @return one vertically filtered signed sample
    private static long verticalInterpolate(
            DecodedPlane referencePlane,
            int sourceX,
            int sourceY0,
            int[] filter
    ) {
        long filtered = 0;
        for (int tapIndex = 0; tapIndex < INTER_FILTER_TAP_COUNT; tapIndex++) {
            int sourceY = clamp(sourceY0 + tapIndex - INTER_FILTER_START_OFFSET, 0, referencePlane.height() - 1);
            filtered += (long) filter[tapIndex] * referencePlane.sample(sourceX, sourceY);
        }
        return filtered;
    }

    /// Selects one AV1 fixed inter filter for one phase and sampled axis size.
    ///
    /// @param filterMode the frame-level interpolation filter
    /// @param phase the normalized AV1 fractional phase in `[1, 15]`
    /// @param axisSize the sampled axis size in pixels
    /// @return the selected AV1 fixed-filter taps
    private static int[] selectSubpelFilter(
            FrameHeader.InterpolationFilter filterMode,
            int phase,
            int axisSize
    ) {
        if (phase <= 0 || phase >= INTER_FILTER_PHASES) {
            throw new IllegalArgumentException("AV1 fixed-filter phase out of range: " + phase);
        }
        return switch (filterMode) {
            case EIGHT_TAP_REGULAR -> (axisSize <= 4 ? SMALL_REGULAR_SUBPEL_FILTERS : REGULAR_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SMOOTH -> (axisSize <= 4 ? SMALL_SMOOTH_SUBPEL_FILTERS : SMOOTH_SUBPEL_FILTERS)[phase - 1];
            case EIGHT_TAP_SHARP -> SHARP_SUBPEL_FILTERS[phase - 1];
            default -> throw new IllegalArgumentException(
                    "InterPredictionOracle supports only BILINEAR and fixed EIGHT_TAP_* filters"
            );
        };
    }

    /// Converts one plane-local fractional numerator into one AV1 fixed-filter phase.
    ///
    /// @param fraction the positive remainder below the local denominator
    /// @param denominator the local denominator in plane-local sample units
    /// @return the normalized AV1 phase in `[0, 15]`
    private static int interpolationPhase(int fraction, int denominator) {
        if (fraction == 0) {
            return 0;
        }
        return Math.multiplyExact(fraction, INTER_FILTER_PHASES) / denominator;
    }

    /// Rounds one signed filtered value by one arithmetic right shift.
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

    /// Returns one bilinearly interpolated unsigned sample at one plane-local source numerator.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @return one bilinearly interpolated unsigned sample
    private static int bilinearInterpolateAt(
            DecodedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY
    ) {
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionY = Math.floorMod(sourceNumeratorY, denominatorY);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int fractionX = Math.floorMod(sourceNumeratorX, denominatorX);
        int clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1);
        int clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1);
        int clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1);
        return bilinearInterpolate(
                referencePlane.sample(clampedSourceX0, clampedSourceY0),
                referencePlane.sample(clampedSourceX1, clampedSourceY0),
                referencePlane.sample(clampedSourceX0, clampedSourceY1),
                referencePlane.sample(clampedSourceX1, clampedSourceY1),
                fractionX,
                denominatorX,
                fractionY,
                denominatorY
        );
    }

    /// Returns one bilinearly interpolated unsigned sample.
    ///
    /// @param topLeft the top-left source sample
    /// @param topRight the top-right source sample
    /// @param bottomLeft the bottom-left source sample
    /// @param bottomRight the bottom-right source sample
    /// @param fractionX the horizontal source fraction in `[0, denominatorX)`
    /// @param denominatorX the horizontal interpolation denominator
    /// @param fractionY the vertical source fraction in `[0, denominatorY)`
    /// @param denominatorY the vertical interpolation denominator
    /// @return one bilinearly interpolated unsigned sample
    private static int bilinearInterpolate(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            int fractionX,
            int denominatorX,
            int fractionY,
            int denominatorY
    ) {
        int inverseFractionX = denominatorX - fractionX;
        int inverseFractionY = denominatorY - fractionY;
        long denominator = (long) denominatorX * denominatorY;
        long weightedSum = (long) inverseFractionX * inverseFractionY * topLeft
                + (long) fractionX * inverseFractionY * topRight
                + (long) inverseFractionX * fractionY * bottomLeft
                + (long) fractionX * fractionY * bottomRight;
        return (int) ((weightedSum + (denominator >> 1)) / denominator);
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
