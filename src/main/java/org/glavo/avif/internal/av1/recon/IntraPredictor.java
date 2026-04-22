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

import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

/// Minimal intra predictor used by the first reconstruction-capable decode path.
///
/// The current implementation supports the non-directional AV1 intra modes, luma filter-intra,
/// and `I420` CFL chroma prediction. It uses frame-edge midpoint samples when top or left
/// neighbors are unavailable.
@NotNullByDefault
final class IntraPredictor {
    /// The AV1 filter-intra tap sets in `FilterIntraMode` order.
    ///
    /// Each mode stores eight per-pixel tap vectors for the 4x2 recursive prediction unit in
    /// raster order. Every tap vector multiplies:
    /// 1. the current unit top-left reference
    /// 2. the four top reference samples
    /// 3. the two left reference samples
    private static final int[][][] FILTER_INTRA_TAPS = {
            {
                    {0, -6, 10, 0, 0, 0, 12},
                    {1, -5, 2, 10, 0, 0, 9},
                    {2, -3, 1, 1, 10, 0, 7},
                    {3, -3, 1, 1, 2, 10, 5},
                    {4, -4, 6, 0, 0, 0, 2},
                    {5, -3, 2, 6, 0, 0, 2},
                    {6, -3, 2, 2, 6, 0, 2},
                    {7, -3, 1, 2, 2, 6, 3}
            },
            {
                    {-10, 16, 0, 0, 0, 10, 0},
                    {-6, 0, 16, 0, 0, 6, 0},
                    {-4, 0, 0, 16, 0, 4, 0},
                    {-2, 0, 0, 0, 16, 2, 0},
                    {-10, 16, 0, 0, 0, 0, 10},
                    {-6, 0, 16, 0, 0, 0, 6},
                    {-4, 0, 0, 16, 0, 0, 4},
                    {-2, 0, 0, 0, 16, 0, 2}
            },
            {
                    {-8, 8, 0, 0, 0, 16, 0},
                    {-8, 0, 8, 0, 0, 16, 0},
                    {-8, 0, 0, 8, 0, 16, 0},
                    {-8, 0, 0, 0, 8, 16, 0},
                    {-4, 4, 0, 0, 0, 0, 16},
                    {-4, 0, 4, 0, 0, 0, 16},
                    {-4, 0, 0, 4, 0, 0, 16},
                    {-4, 0, 0, 0, 4, 0, 16}
            },
            {
                    {-2, 8, 0, 0, 0, 10, 0},
                    {-1, 3, 8, 0, 0, 6, 0},
                    {-1, 2, 3, 8, 0, 4, 0},
                    {0, 1, 2, 3, 8, 2, 0},
                    {-1, 4, 0, 0, 0, 3, 10},
                    {-1, 3, 4, 0, 0, 4, 6},
                    {-1, 2, 3, 4, 0, 4, 4},
                    {-1, 2, 2, 3, 4, 3, 3}
            },
            {
                    {-12, 14, 0, 0, 0, 14, 0},
                    {-10, 0, 14, 0, 0, 12, 0},
                    {-9, 0, 0, 14, 0, 11, 0},
                    {-8, 0, 0, 0, 14, 10, 0},
                    {-10, 12, 0, 0, 0, 0, 14},
                    {-9, 1, 12, 0, 0, 0, 12},
                    {-8, 0, 0, 12, 0, 1, 11},
                    {-7, 0, 0, 1, 12, 1, 9}
            }
    };

    /// The smooth predictor weights for a width or height of one sample.
    private static final int[] SMOOTH_WEIGHTS_1 = {255};

    /// The smooth predictor weights for a width or height of two samples.
    private static final int[] SMOOTH_WEIGHTS_2 = {255, 128};

    /// The smooth predictor weights for a width or height of four samples.
    private static final int[] SMOOTH_WEIGHTS_4 = {255, 149, 85, 64};

    /// The smooth predictor weights for a width or height of eight samples.
    private static final int[] SMOOTH_WEIGHTS_8 = {255, 197, 146, 105, 73, 50, 37, 32};

    /// The smooth predictor weights for a width or height of sixteen samples.
    private static final int[] SMOOTH_WEIGHTS_16 = {
            255, 225, 196, 170, 145, 123, 102, 84,
            68, 54, 43, 33, 26, 20, 17, 16
    };

    /// The smooth predictor weights for a width or height of thirty-two samples.
    private static final int[] SMOOTH_WEIGHTS_32 = {
            255, 240, 225, 210, 196, 182, 169, 157,
            145, 133, 122, 111, 101, 92, 83, 74,
            66, 59, 52, 45, 39, 34, 29, 25,
            21, 17, 14, 12, 10, 9, 8, 8
    };

    /// The smooth predictor weights for a width or height of sixty-four samples.
    private static final int[] SMOOTH_WEIGHTS_64 = {
            255, 248, 240, 233, 225, 218, 210, 203,
            196, 189, 182, 176, 169, 163, 156, 150,
            144, 138, 133, 127, 121, 116, 111, 106,
            101, 96, 91, 86, 82, 77, 73, 69,
            65, 61, 57, 54, 50, 47, 44, 41,
            38, 35, 32, 29, 27, 25, 22, 20,
            18, 16, 15, 13, 12, 10, 9, 8,
            7, 6, 6, 5, 5, 4, 4, 4
    };

    /// Prevents instantiation of this utility class.
    private IntraPredictor() {
    }

    /// Reconstructs one luma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    static void predictLuma(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta
    ) {
        predict(
                plane,
                x,
                y,
                width,
                height,
                checkedPredictionMode(mode, angleDelta),
                angleDelta
        );
    }

    /// Reconstructs one luma filter-intra block directly into the destination plane.
    ///
    /// The current implementation supports the AV1 recursive 4x2 filter-intra algorithm for the
    /// full syntax-legal size range up to `32x32`.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the filter-intra mode
    static void predictFilterIntraLuma(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            FilterIntraMode mode
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if ((width & 3) != 0) {
            throw new IllegalStateException("filter_intra requires width aligned to 4 samples: " + width);
        }
        if ((height & 1) != 0) {
            throw new IllegalStateException("filter_intra requires height aligned to 2 samples: " + height);
        }
        if (width > 32 || height > 32) {
            throw new IllegalStateException("filter_intra currently supports sizes up to 32x32: " + width + "x" + height);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[][] taps = FILTER_INTRA_TAPS[mode.symbolIndex()];
        for (int stripeY = 0; stripeY < height; stripeY += 2) {
            int topReferenceY = y + stripeY - 1;
            int currentY = y + stripeY;
            for (int blockX = 0; blockX < width; blockX += 4) {
                int currentX = x + blockX;
                int leftReferenceX = currentX - 1;
                int p0 = plane.sampleOrFallback(leftReferenceX, topReferenceY, defaultSample);
                int p1 = plane.sampleOrFallback(currentX, topReferenceY, defaultSample);
                int p2 = plane.sampleOrFallback(currentX + 1, topReferenceY, defaultSample);
                int p3 = plane.sampleOrFallback(currentX + 2, topReferenceY, defaultSample);
                int p4 = plane.sampleOrFallback(currentX + 3, topReferenceY, defaultSample);
                int p5 = plane.sampleOrFallback(leftReferenceX, currentY, defaultSample);
                int p6 = plane.sampleOrFallback(leftReferenceX, currentY + 1, defaultSample);
                for (int yy = 0; yy < 2; yy++) {
                    for (int xx = 0; xx < 4; xx++) {
                        int[] tap = taps[(yy << 2) + xx];
                        int predicted = (tap[0] * p0
                                + tap[1] * p1
                                + tap[2] * p2
                                + tap[3] * p3
                                + tap[4] * p4
                                + tap[5] * p5
                                + tap[6] * p6
                                + 8) >> 4;
                        plane.setSample(currentX + xx, currentY + yy, predicted);
                    }
                }
            }
        }
    }

    /// Reconstructs one chroma intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    static void predictChroma(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta
    ) {
        predict(
                plane,
                x,
                y,
                width,
                height,
                checkedPredictionMode(mode, angleDelta),
                angleDelta
        );
    }

    /// Reconstructs one `I420` CFL chroma block directly into the destination plane.
    ///
    /// The current implementation consumes already reconstructed luma samples, including any luma
    /// residuals that were applied before chroma prediction begins.
    ///
    /// @param chromaPlane the mutable chroma destination plane
    /// @param lumaPlane the already reconstructed luma plane
    /// @param chromaX the zero-based horizontal chroma sample coordinate
    /// @param chromaY the zero-based vertical chroma sample coordinate
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param width the chroma block width in samples
    /// @param height the chroma block height in samples
    /// @param alpha the signed CFL alpha
    static void predictChromaCflI420(
            MutablePlaneBuffer chromaPlane,
            MutablePlaneBuffer lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        int dc = dcPredictionValue(chromaPlane, chromaX, chromaY, width, height);
        int[] ac = cflAcI420(lumaPlane, lumaX, lumaY, width, height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int diff = alpha * ac[row * width + column];
                int predicted = dc + applySign((Math.abs(diff) + 32) >> 6, diff);
                chromaPlane.setSample(chromaX + column, chromaY + row, predicted);
            }
        }
    }

    /// Validates one luma prediction mode and maps it into the internal supported subset.
    ///
    /// @param mode the luma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @return the internal supported prediction mode
    private static PredictionMode checkedPredictionMode(LumaIntraPredictionMode mode, int angleDelta) {
        return switch (mode) {
            case DC -> PredictionMode.DC;
            case VERTICAL -> PredictionMode.VERTICAL;
            case HORIZONTAL -> PredictionMode.HORIZONTAL;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
            case DIAGONAL_DOWN_LEFT,
                    DIAGONAL_DOWN_RIGHT,
                    VERTICAL_RIGHT,
                    HORIZONTAL_DOWN,
                    HORIZONTAL_UP,
                    VERTICAL_LEFT -> throw unsupportedDirectionalMode(mode.name(), angleDelta);
        };
    }

    /// Validates one chroma prediction mode and maps it into the internal supported subset.
    ///
    /// @param mode the chroma intra prediction mode
    /// @param angleDelta the signed directional angle delta
    /// @return the internal supported prediction mode
    private static PredictionMode checkedPredictionMode(UvIntraPredictionMode mode, int angleDelta) {
        return switch (mode) {
            case DC -> PredictionMode.DC;
            case VERTICAL -> PredictionMode.VERTICAL;
            case HORIZONTAL -> PredictionMode.HORIZONTAL;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
            case CFL -> throw new IllegalStateException("CFL chroma prediction is not implemented yet");
            case DIAGONAL_DOWN_LEFT,
                    DIAGONAL_DOWN_RIGHT,
                    VERTICAL_RIGHT,
                    HORIZONTAL_DOWN,
                    HORIZONTAL_UP,
                    VERTICAL_LEFT -> throw unsupportedDirectionalMode(mode.name(), angleDelta);
        };
    }

    /// Creates one stable unsupported-directional-mode failure.
    ///
    /// @param modeName the source prediction-mode name
    /// @param angleDelta the signed directional angle delta
    /// @return one stable unsupported-directional-mode failure
    private static IllegalStateException unsupportedDirectionalMode(String modeName, int angleDelta) {
        return new IllegalStateException(
                "Directional intra prediction is not implemented yet: " + modeName + " angle_delta=" + angleDelta
        );
    }

    /// Reconstructs one supported intra-predicted block directly into the destination plane.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the supported internal prediction mode
    /// @param angleDelta the signed directional angle delta
    private static void predict(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (angleDelta != 0) {
            throw new IllegalStateException("Directional angle-delta prediction is not implemented yet: " + angleDelta);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[] top = new int[width];
        int[] left = new int[height];
        for (int i = 0; i < width; i++) {
            top[i] = plane.sampleOrFallback(x + i, y - 1, defaultSample);
        }
        for (int i = 0; i < height; i++) {
            left[i] = plane.sampleOrFallback(x - 1, y + i, defaultSample);
        }

        int topLeft = defaultTopLeft(plane, x, y, defaultSample);
        switch (mode) {
            case DC -> predictDc(plane, x, y, width, height, top, left, defaultSample);
            case VERTICAL -> predictVertical(plane, x, y, width, height, top);
            case HORIZONTAL -> predictHorizontal(plane, x, y, width, height, left);
            case PAETH -> predictPaeth(plane, x, y, width, height, top, left, topLeft);
            case SMOOTH -> predictSmooth(plane, x, y, width, height, top, left);
            case SMOOTH_VERTICAL -> predictSmoothVertical(plane, x, y, width, height, top, left);
            case SMOOTH_HORIZONTAL -> predictSmoothHorizontal(plane, x, y, width, height, top, left);
        }
    }

    /// Returns the fallback top-left predictor sample for one block origin.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param defaultSample the frame-edge default sample
    /// @return the fallback top-left predictor sample for one block origin
    private static int defaultTopLeft(MutablePlaneBuffer plane, int x, int y, int defaultSample) {
        if (x > 0 && y > 0) {
            return plane.sample(x - 1, y - 1);
        }
        if (x > 0) {
            return plane.sample(x - 1, y);
        }
        if (y > 0) {
            return plane.sample(x, y - 1);
        }
        return defaultSample;
    }

    /// Reconstructs one DC-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    /// @param defaultSample the frame-edge default sample
    private static void predictDc(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left,
            int defaultSample
    ) {
        int value = dcPredictionValue(x, y, width, height, top, left, defaultSample);
        fillBlock(plane, x, y, width, height, value);
    }

    /// Returns the stable DC predictor value for one block.
    ///
    /// @param plane the destination plane that supplies already reconstructed neighbors
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @return the stable DC predictor value for one block
    private static int dcPredictionValue(MutablePlaneBuffer plane, int x, int y, int width, int height) {
        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[] top = new int[width];
        int[] left = new int[height];
        for (int i = 0; i < width; i++) {
            top[i] = plane.sampleOrFallback(x + i, y - 1, defaultSample);
        }
        for (int i = 0; i < height; i++) {
            left[i] = plane.sampleOrFallback(x - 1, y + i, defaultSample);
        }
        return dcPredictionValue(x, y, width, height, top, left, defaultSample);
    }

    /// Returns the stable DC predictor value for one block using caller-supplied edge samples.
    ///
    /// @param plane the destination plane that supplies already reconstructed neighbors
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    /// @param defaultSample the frame-edge default sample
    /// @return the stable DC predictor value for one block using caller-supplied edge samples
    private static int dcPredictionValue(int x, int y, int width, int height, int[] top, int[] left, int defaultSample) {
        boolean haveTop = y > 0;
        boolean haveLeft = x > 0;
        if (haveTop && haveLeft) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            for (int sample : left) {
                sum += sample;
            }
            return (sum + ((width + height) >> 1)) / (width + height);
        }
        if (haveTop) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            return (sum + (width >> 1)) / width;
        }
        if (haveLeft) {
            int sum = 0;
            for (int sample : left) {
                sum += sample;
            }
            return (sum + (height >> 1)) / height;
        }
        return defaultSample;
    }

    /// Returns the signed CFL AC buffer for one `I420` chroma block.
    ///
    /// The returned buffer has one entry per chroma sample in raster order.
    ///
    /// @param lumaPlane the already reconstructed luma plane
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param chromaWidth the chroma block width in samples
    /// @param chromaHeight the chroma block height in samples
    /// @return the signed CFL AC buffer for one `I420` chroma block
    private static int[] cflAcI420(
            MutablePlaneBuffer lumaPlane,
            int lumaX,
            int lumaY,
            int chromaWidth,
            int chromaHeight
    ) {
        int[] ac = new int[chromaWidth * chromaHeight];
        int sum = 1 << (Integer.numberOfTrailingZeros(chromaWidth) + Integer.numberOfTrailingZeros(chromaHeight) - 1);
        for (int row = 0; row < chromaHeight; row++) {
            int rowOffset = row * chromaWidth;
            int sourceY = lumaY + (row << 1);
            for (int column = 0; column < chromaWidth; column++) {
                int sourceX = lumaX + (column << 1);
                int acSum = lumaPlane.sample(sourceX, sourceY)
                        + lumaPlane.sample(sourceX + 1, sourceY)
                        + lumaPlane.sample(sourceX, sourceY + 1)
                        + lumaPlane.sample(sourceX + 1, sourceY + 1);
                int value = acSum << 1;
                ac[rowOffset + column] = value;
                sum += value;
            }
        }
        int shift = Integer.numberOfTrailingZeros(chromaWidth) + Integer.numberOfTrailingZeros(chromaHeight);
        int average = sum >> shift;
        for (int i = 0; i < ac.length; i++) {
            ac[i] -= average;
        }
        return ac;
    }

    /// Applies the sign of one source value to one magnitude.
    ///
    /// @param magnitude the unsigned magnitude
    /// @param signedSource the value whose sign should be copied
    /// @return the signed magnitude
    private static int applySign(int magnitude, int signedSource) {
        return signedSource < 0 ? -magnitude : magnitude;
    }

    /// Reconstructs one vertical-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    private static void predictVertical(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top
    ) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                plane.setSample(x + column, y + row, top[column]);
            }
        }
    }

    /// Reconstructs one horizontal-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param left the left reference samples
    private static void predictHorizontal(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] left
    ) {
        for (int row = 0; row < height; row++) {
            int value = left[row];
            for (int column = 0; column < width; column++) {
                plane.setSample(x + column, y + row, value);
            }
        }
    }

    /// Reconstructs one Paeth-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    /// @param topLeft the top-left reference sample
    private static void predictPaeth(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left,
            int topLeft
    ) {
        for (int row = 0; row < height; row++) {
            int leftValue = left[row];
            for (int column = 0; column < width; column++) {
                int topValue = top[column];
                int base = leftValue + topValue - topLeft;
                int leftDiff = Math.abs(leftValue - base);
                int topDiff = Math.abs(topValue - base);
                int topLeftDiff = Math.abs(topLeft - base);
                int predicted = leftDiff <= topDiff && leftDiff <= topLeftDiff
                        ? leftValue
                        : topDiff <= topLeftDiff ? topValue : topLeft;
                plane.setSample(x + column, y + row, predicted);
            }
        }
    }

    /// Reconstructs one smooth-predicted block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    private static void predictSmooth(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] horizontalWeights = smoothWeights(width);
        int[] verticalWeights = smoothWeights(height);
        int right = top[width - 1];
        int bottom = left[height - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = verticalWeights[row] * top[column]
                        + (256 - verticalWeights[row]) * bottom
                        + horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right;
                plane.setSample(x + column, y + row, (predicted + 256) >> 9);
            }
        }
    }

    /// Reconstructs one vertically smoothed block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    private static void predictSmoothVertical(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] verticalWeights = smoothWeights(height);
        int bottom = left[height - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = verticalWeights[row] * top[column]
                        + (256 - verticalWeights[row]) * bottom;
                plane.setSample(x + column, y + row, (predicted + 128) >> 8);
            }
        }
    }

    /// Reconstructs one horizontally smoothed block.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param top the top reference samples
    /// @param left the left reference samples
    private static void predictSmoothHorizontal(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] horizontalWeights = smoothWeights(width);
        int right = top[width - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right;
                plane.setSample(x + column, y + row, (predicted + 128) >> 8);
            }
        }
    }

    /// Fills one rectangular block with one constant sample value.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param value the constant sample value
    private static void fillBlock(MutablePlaneBuffer plane, int x, int y, int width, int height, int value) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                plane.setSample(x + column, y + row, value);
            }
        }
    }

    /// Returns the smooth-predictor weight array for one supported block dimension.
    ///
    /// @param size the block width or height in samples
    /// @return the smooth-predictor weight array for one supported block dimension
    private static int[] smoothWeights(int size) {
        return switch (size) {
            case 1 -> SMOOTH_WEIGHTS_1;
            case 2 -> SMOOTH_WEIGHTS_2;
            case 4 -> SMOOTH_WEIGHTS_4;
            case 8 -> SMOOTH_WEIGHTS_8;
            case 16 -> SMOOTH_WEIGHTS_16;
            case 32 -> SMOOTH_WEIGHTS_32;
            case 64 -> SMOOTH_WEIGHTS_64;
            default -> throw new IllegalStateException("Unsupported smooth predictor size: " + size);
        };
    }

    /// The internal non-directional prediction modes supported by the first reconstruction path.
    @NotNullByDefault
    private enum PredictionMode {
        /// DC prediction.
        DC,

        /// Vertical prediction.
        VERTICAL,

        /// Horizontal prediction.
        HORIZONTAL,

        /// Paeth prediction.
        PAETH,

        /// Smooth prediction.
        SMOOTH,

        /// Smooth vertical prediction.
        SMOOTH_VERTICAL,

        /// Smooth horizontal prediction.
        SMOOTH_HORIZONTAL
    }
}
