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
import org.jetbrains.annotations.Unmodifiable;

/// Minimal intra predictor used by the first reconstruction-capable decode path.
///
/// The current implementation supports the AV1 non-directional intra modes, directional intra
/// prediction with signed `angle_delta`, luma filter-intra, and the current `I420` / `I422` /
/// `I444` CFL chroma subset. It uses frame-edge midpoint samples when top or left neighbors are
/// unavailable.
@NotNullByDefault
final class IntraPredictor {
    /// The AV1 directional base angles in `VERTICAL`-through-`VERTICAL_LEFT` order.
    private static final int @Unmodifiable [] DIRECTIONAL_BASE_ANGLES = {
            90, 180, 45, 135, 113, 157, 203, 67
    };

    /// The maximum axis length passed to one AV1 intra-prediction kernel invocation.
    private static final int MAX_INTRA_PREDICTION_AXIS_SIZE = 64;

    /// The AV1 directional derivative table indexed by half-angle.
    private static final int @Unmodifiable [] DIRECTIONAL_DERIVATIVES = {
            0,
            1023, 0,
            547,
            372, 0, 0,
            273,
            215, 0,
            178,
            151, 0,
            132,
            116, 0,
            102, 0,
            90,
            80, 0,
            71,
            64, 0,
            57,
            51, 0,
            45, 0,
            40,
            35, 0,
            31,
            27, 0,
            23,
            19, 0,
            15, 0,
            11, 0,
            7,
            3
    };

    /// The AV1 filter-intra tap sets in `FilterIntraMode` order.
    ///
    /// Each mode stores eight per-pixel tap vectors for the 4x2 recursive prediction unit in
    /// raster order. Every tap vector multiplies:
    /// 1. the current unit top-left reference
    /// 2. the four top reference samples
    /// 3. the two left reference samples
    private static final int @Unmodifiable [] @Unmodifiable [] @Unmodifiable [] FILTER_INTRA_TAPS = {
            {
                    {-6, 10, 0, 0, 0, 12, 0},
                    {-5, 2, 10, 0, 0, 9, 0},
                    {-3, 1, 1, 10, 0, 7, 0},
                    {-3, 1, 1, 2, 10, 5, 0},
                    {-4, 6, 0, 0, 0, 2, 12},
                    {-3, 2, 6, 0, 0, 2, 9},
                    {-3, 2, 2, 6, 0, 2, 7},
                    {-3, 1, 2, 2, 6, 3, 5}
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
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_1 = {255};

    /// The smooth predictor weights for a width or height of two samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_2 = {255, 128};

    /// The smooth predictor weights for a width or height of four samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_4 = {255, 149, 85, 64};

    /// The smooth predictor weights for a width or height of eight samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_8 = {255, 197, 146, 105, 73, 50, 37, 32};

    /// The smooth predictor weights for a width or height of sixteen samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_16 = {
            255, 225, 196, 170, 145, 123, 102, 84,
            68, 54, 43, 33, 26, 20, 17, 16
    };

    /// The smooth predictor weights for a width or height of thirty-two samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_32 = {
            255, 240, 225, 210, 196, 182, 169, 157,
            145, 133, 122, 111, 101, 92, 83, 74,
            66, 59, 52, 45, 39, 34, 29, 25,
            21, 17, 14, 12, 10, 9, 8, 8
    };

    /// The smooth predictor weights for a width or height of sixty-four samples.
    private static final int @Unmodifiable [] SMOOTH_WEIGHTS_64 = {
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
    /// full syntax-legal size range up to `32x32`, including visible right and bottom edge
    /// clipping that leaves the final 4x2 prediction unit partially written.
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
        if (width > 32 || height > 32) {
            throw new IllegalStateException("filter_intra currently supports sizes up to 32x32: " + width + "x" + height);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int[][] taps = FILTER_INTRA_TAPS[mode.symbolIndex()];
        int predictionWidth = (width + 3) & ~3;
        int predictionHeight = (height + 1) & ~1;
        for (int stripeY = 0; stripeY < predictionHeight; stripeY += 2) {
            int topReferenceY = y + stripeY - 1;
            int currentY = y + stripeY;
            for (int blockX = 0; blockX < predictionWidth; blockX += 4) {
                int currentX = x + blockX;
                int leftReferenceX = currentX - 1;
                int p0 = filterIntraTopLeftReference(plane, x, y, currentX, currentY, topReferenceY, defaultSample);
                int p1 = filterIntraTopReference(plane, x, y, currentX, topReferenceY, defaultSample);
                int p2 = filterIntraTopReference(plane, x, y, currentX + 1, topReferenceY, defaultSample);
                int p3 = filterIntraTopReference(plane, x, y, currentX + 2, topReferenceY, defaultSample);
                int p4 = filterIntraTopReference(plane, x, y, currentX + 3, topReferenceY, defaultSample);
                int p5 = filterIntraLeftReference(plane, x, y, leftReferenceX, currentY, defaultSample);
                int p6 = filterIntraLeftReference(plane, x, y, leftReferenceX, currentY + 1, defaultSample);
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
                        if (blockX + xx < width && stripeY + yy < height) {
                            plane.setSample(currentX + xx, currentY + yy, predicted);
                        }
                    }
                }
            }
        }
    }

    /// Returns one filter-intra top-left reference sample.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param currentX the current recursive unit origin X coordinate
    /// @param currentY the current recursive unit origin Y coordinate
    /// @param topReferenceY the top reference row for this recursive unit
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra top-left reference sample
    private static int filterIntraTopLeftReference(
            MutablePlaneBuffer plane,
            int blockX,
            int blockY,
            int currentX,
            int currentY,
            int topReferenceY,
            int defaultSample
    ) {
        if (topReferenceY >= 0 && currentX > 0) {
            return plane.sample(currentX - 1, topReferenceY);
        }
        return defaultTopLeft(plane, blockX, blockY, defaultSample);
    }

    /// Returns one filter-intra top reference sample with AV1 frame-edge fallback.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param sampleX the top reference X coordinate
    /// @param topReferenceY the top reference row for this recursive unit
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra top reference sample
    private static int filterIntraTopReference(
            MutablePlaneBuffer plane,
            int blockX,
            int blockY,
            int sampleX,
            int topReferenceY,
            int defaultSample
    ) {
        if (topReferenceY < 0) {
            return blockX > 0 ? plane.sample(blockX - 1, blockY) : defaultSample - 1;
        }
        return plane.sample(Math.min(sampleX, plane.width() - 1), topReferenceY);
    }

    /// Returns one filter-intra left reference sample with AV1 frame-edge fallback.
    ///
    /// @param plane the mutable destination plane
    /// @param blockX the block origin X coordinate
    /// @param blockY the block origin Y coordinate
    /// @param leftReferenceX the left reference column for this recursive unit
    /// @param sampleY the left reference Y coordinate
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one filter-intra left reference sample
    private static int filterIntraLeftReference(
            MutablePlaneBuffer plane,
            int blockX,
            int blockY,
            int leftReferenceX,
            int sampleY,
            int defaultSample
    ) {
        if (leftReferenceX < 0) {
            return blockY > 0 ? plane.sample(blockX, blockY - 1) : defaultSample + 1;
        }
        return plane.sample(leftReferenceX, Math.min(sampleY, plane.height() - 1));
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

    /// Reconstructs one CFL chroma block directly into the destination plane.
    ///
    /// The current implementation consumes already reconstructed luma samples, including any luma
    /// residuals that were applied before chroma prediction begins, and supports the current
    /// `I420`, `I422`, and `I444` subsampling patterns through explicit horizontal and vertical
    /// subsampling shifts.
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
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    static void predictChromaCfl(
            MutablePlaneBuffer chromaPlane,
            MutablePlaneBuffer lumaPlane,
            int chromaX,
            int chromaY,
            int lumaX,
            int lumaY,
            int width,
            int height,
            int alpha,
            int subsamplingX,
            int subsamplingY
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (subsamplingX < 0 || subsamplingX > 1) {
            throw new IllegalArgumentException("subsamplingX must be 0 or 1: " + subsamplingX);
        }
        if (subsamplingY < 0 || subsamplingY > 1) {
            throw new IllegalArgumentException("subsamplingY must be 0 or 1: " + subsamplingY);
        }
        int dc = dcPredictionValue(chromaPlane, chromaX, chromaY, width, height);
        int[] ac = cflAc(lumaPlane, lumaX, lumaY, width, height, subsamplingX, subsamplingY);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int diff = alpha * ac[row * width + column];
                int predicted = dc + applySign((Math.abs(diff) + 32) >> 6, diff);
                chromaPlane.setSample(chromaX + column, chromaY + row, predicted);
            }
        }
    }

    /// Reconstructs one `I420` CFL chroma block directly into the destination plane.
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
        predictChromaCfl(chromaPlane, lumaPlane, chromaX, chromaY, lumaX, lumaY, width, height, alpha, 1, 1);
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
            case DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT;
            case DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT;
            case VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT;
            case HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN;
            case HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP;
            case VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
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
            case DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT;
            case DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT;
            case VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT;
            case HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN;
            case HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP;
            case VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT;
            case SMOOTH -> PredictionMode.SMOOTH;
            case SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL;
            case SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL;
            case PAETH -> PredictionMode.PAETH;
            case CFL -> throw new IllegalStateException("CFL chroma prediction is not implemented yet");
        };
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
        if (width > MAX_INTRA_PREDICTION_AXIS_SIZE || height > MAX_INTRA_PREDICTION_AXIS_SIZE) {
            predictLargeBlock(plane, x, y, width, height, mode, angleDelta);
            return;
        }

        if (mode.usesDirectionalPrediction(angleDelta)) {
            predictDirectional(plane, x, y, width, height, mode, angleDelta);
            return;
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        int referenceWidth = mode.usesHorizontalSmoothReference() ? smoothWeightAxisSize(width) : width;
        int referenceHeight = mode.usesVerticalSmoothReference() ? smoothWeightAxisSize(height) : height;
        int[] top = topReferenceSamples(plane, x, y, referenceWidth, defaultSample);
        int[] left = leftReferenceSamples(plane, x, y, referenceHeight, defaultSample);

        int topLeft = defaultTopLeft(plane, x, y, defaultSample);
        switch (mode) {
            case DC -> predictDc(plane, x, y, width, height, top, left, defaultSample);
            case VERTICAL -> predictVertical(plane, x, y, width, height, top);
            case HORIZONTAL -> predictHorizontal(plane, x, y, width, height, left);
            case PAETH -> {
                if (x <= 0 && y <= 0) {
                    fillBlock(plane, x, y, width, height, defaultSample);
                } else if (x <= 0) {
                    predictVertical(plane, x, y, width, height, top);
                } else if (y <= 0) {
                    predictHorizontal(plane, x, y, width, height, left);
                } else {
                    predictPaeth(plane, x, y, width, height, top, left, topLeft);
                }
            }
            case SMOOTH -> predictSmooth(plane, x, y, width, height, top, left);
            case SMOOTH_VERTICAL -> predictSmoothVertical(plane, x, y, width, height, top, left);
            case SMOOTH_HORIZONTAL -> predictSmoothHorizontal(plane, x, y, width, height, top, left);
        }
    }

    /// Reconstructs a large intra block through 64x64-or-smaller prediction-kernel regions.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the supported internal prediction mode
    /// @param angleDelta the signed directional angle delta
    private static void predictLargeBlock(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta
    ) {
        for (int offsetY = 0; offsetY < height; offsetY += MAX_INTRA_PREDICTION_AXIS_SIZE) {
            int subHeight = Math.min(MAX_INTRA_PREDICTION_AXIS_SIZE, height - offsetY);
            for (int offsetX = 0; offsetX < width; offsetX += MAX_INTRA_PREDICTION_AXIS_SIZE) {
                int subWidth = Math.min(MAX_INTRA_PREDICTION_AXIS_SIZE, width - offsetX);
                predict(plane, x + offsetX, y + offsetY, subWidth, subHeight, mode, angleDelta);
            }
        }
    }

    /// Reconstructs one directional intra-predicted block.
    ///
    /// The current implementation follows the AV1 zone-1/2/3 directional interpolation model
    /// without intra-edge filtering or upsampling. This is sufficient for the current
    /// reconstruction-capable subset and advances the reader beyond the legacy directional
    /// boundary.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional-capable prediction mode
    /// @param angleDelta the signed directional angle delta
    private static void predictDirectional(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            PredictionMode mode,
            int angleDelta
    ) {
        int angle = mode.directionalBaseAngle() + 3 * angleDelta;
        if (angle < 0 || angle > 270) {
            throw new IllegalStateException("Directional angle is out of range: " + angle);
        }

        int defaultSample = 1 << (plane.bitDepth() - 1);
        if (angle == 90 || (angle < 90 && y <= 0)) {
            predictVertical(plane, x, y, width, height, topDirectionalReferences(plane, x, y, width, height, defaultSample, width));
            return;
        }
        if (angle == 180 || (angle > 180 && x <= 0)) {
            predictHorizontal(plane, x, y, width, height, leftDirectionalReferences(plane, x, y, width, height, defaultSample, height));
            return;
        }
        if (angle < 90) {
            predictDirectionalZone1(plane, x, y, width, height, angle, defaultSample);
            return;
        }
        if (angle < 180) {
            predictDirectionalZone2(plane, x, y, width, height, angle, defaultSample);
            return;
        }
        predictDirectionalZone3(plane, x, y, width, height, angle, defaultSample);
    }

    /// Reconstructs one zone-1 directional block that projects from the top edge.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    private static void predictDirectionalZone1(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample
    ) {
        int[] top = topDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                width + Math.min(width, height)
        );
        int dx = directionalDerivative(angle >> 1);
        int maxBase = top.length - 1;
        for (int row = 0, xpos = dx; row < height; row++, xpos += dx) {
            int frac = xpos & 0x3E;
            for (int column = 0, base = xpos >> 6; column < width; column++, base++) {
                if (base < maxBase) {
                    plane.setSample(x + column, y + row, interpolate(top[base], top[base + 1], frac));
                } else {
                    for (int remaining = column; remaining < width; remaining++) {
                        plane.setSample(x + remaining, y + row, top[maxBase]);
                    }
                    break;
                }
            }
        }
    }

    /// Reconstructs one zone-2 directional block that blends the top and left edges.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    private static void predictDirectionalZone2(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample
    ) {
        int[] top = topDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                width + Math.min(width, height)
        );
        int[] left = leftDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                height + Math.min(width, height)
        );
        int topLeft = defaultTopLeft(plane, x, y, defaultSample);
        int dy = directionalDerivative((angle - 90) >> 1);
        int dx = directionalDerivative((180 - angle) >> 1);
        for (int row = 0, xpos = 64 - dx; row < height; row++, xpos -= dx) {
            int baseX = xpos >> 6;
            int fracX = xpos & 0x3E;
            for (int column = 0, ypos = (row << 6) - dy; column < width; column++, baseX++, ypos -= dy) {
                if (baseX >= 0) {
                    int sample0 = zone2Reference(topLeft, top, left, baseX);
                    int sample1 = zone2Reference(topLeft, top, left, baseX + 1);
                    plane.setSample(x + column, y + row, interpolate(sample0, sample1, fracX));
                } else {
                    int baseY = ypos >> 6;
                    int fracY = ypos & 0x3E;
                    int sample0 = zone2Reference(topLeft, top, left, -baseY - 1);
                    int sample1 = zone2Reference(topLeft, top, left, -baseY - 2);
                    plane.setSample(x + column, y + row, interpolate(sample0, sample1, fracY));
                }
            }
        }
    }

    /// Reconstructs one zone-3 directional block that projects from the left edge.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param angle the absolute AV1 intra prediction angle
    /// @param defaultSample the frame-edge default sample
    private static void predictDirectionalZone3(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int angle,
            int defaultSample
    ) {
        int[] left = leftDirectionalReferences(
                plane,
                x,
                y,
                width,
                height,
                defaultSample,
                height + Math.min(width, height)
        );
        int dy = directionalDerivative((270 - angle) >> 1);
        int maxBase = left.length - 1;
        for (int column = 0, ypos = dy; column < width; column++, ypos += dy) {
            int frac = ypos & 0x3E;
            for (int row = 0, base = ypos >> 6; row < height; row++, base++) {
                if (base < maxBase) {
                    plane.setSample(x + column, y + row, interpolate(left[base], left[base + 1], frac));
                } else {
                    for (int remaining = row; remaining < height; remaining++) {
                        plane.setSample(x + column, y + remaining, left[maxBase]);
                    }
                    break;
                }
            }
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
        if (x > 0) {
            return y > 0 ? plane.sample(x - 1, y - 1) : plane.sample(x - 1, y);
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
    /// @param top the top reference samples, including the smooth right-edge reference when clipped
    /// @param left the left reference samples, including the smooth bottom-edge reference when clipped
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
        int[] top = topReferenceSamples(plane, x, y, width, defaultSample);
        int[] left = leftReferenceSamples(plane, x, y, height, defaultSample);
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

    /// Returns the signed CFL AC buffer for one subsampled chroma block.
    ///
    /// The returned buffer has one entry per chroma sample in raster order. The current
    /// implementation supports the same `I420`, `I422`, and `I444` subsampling patterns exposed by
    /// the first reconstruction path.
    ///
    /// @param lumaPlane the already reconstructed luma plane
    /// @param lumaX the zero-based horizontal luma sample coordinate
    /// @param lumaY the zero-based vertical luma sample coordinate
    /// @param chromaWidth the chroma block width in samples
    /// @param chromaHeight the chroma block height in samples
    /// @param subsamplingX the horizontal chroma subsampling shift
    /// @param subsamplingY the vertical chroma subsampling shift
    /// @return the signed CFL AC buffer for one subsampled chroma block
    private static int[] cflAc(
            MutablePlaneBuffer lumaPlane,
            int lumaX,
            int lumaY,
            int chromaWidth,
            int chromaHeight,
            int subsamplingX,
            int subsamplingY
    ) {
        int[] ac = new int[chromaWidth * chromaHeight];
        int sum = 1 << (Integer.numberOfTrailingZeros(chromaWidth) + Integer.numberOfTrailingZeros(chromaHeight) - 1);
        int horizontalSpan = 1 << subsamplingX;
        int verticalSpan = 1 << subsamplingY;
        int valueShift = 3 - subsamplingX - subsamplingY;
        for (int row = 0; row < chromaHeight; row++) {
            int rowOffset = row * chromaWidth;
            int sourceY = lumaY + (row << subsamplingY);
            for (int column = 0; column < chromaWidth; column++) {
                int sourceX = lumaX + (column << subsamplingX);
                int acSum = 0;
                for (int sampleY = 0; sampleY < verticalSpan; sampleY++) {
                    for (int sampleX = 0; sampleX < horizontalSpan; sampleX++) {
                        acSum += lumaPlane.sample(sourceX + sampleX, sourceY + sampleY);
                    }
                }
                int value = acSum << valueShift;
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
        int[] horizontalWeights = smoothWeights(top.length);
        int[] verticalWeights = smoothWeights(left.length);
        int right = top[horizontalWeights.length - 1];
        int bottom = left[verticalWeights.length - 1];
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
    /// @param left the left reference samples, including the smooth bottom-edge reference when clipped
    private static void predictSmoothVertical(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int[] top,
            int[] left
    ) {
        int[] verticalWeights = smoothWeights(left.length);
        int bottom = left[verticalWeights.length - 1];
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
    /// @param top the top reference samples, including the smooth right-edge reference when clipped
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
        int[] horizontalWeights = smoothWeights(top.length);
        int right = top[horizontalWeights.length - 1];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int predicted = horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right;
                plane.setSample(x + column, y + row, (predicted + 128) >> 8);
            }
        }
    }

    /// Returns one top-edge directional reference buffer with top-right extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param defaultSample the frame-edge default sample
    /// @param length the required reference-buffer length
    /// @return one top-edge directional reference buffer with top-right extension
    private static int[] topDirectionalReferences(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int defaultSample,
            int length
    ) {
        return topReferenceSamples(plane, x, y, length, defaultSample);
    }

    /// Returns one left-edge directional reference buffer with bottom-left extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param defaultSample the frame-edge default sample
    /// @param length the required reference-buffer length
    /// @return one left-edge directional reference buffer with bottom-left extension
    private static int[] leftDirectionalReferences(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            int defaultSample,
            int length
    ) {
        return leftReferenceSamples(plane, x, y, length, defaultSample);
    }

    /// Returns one top-edge reference buffer with AV1 frame-edge fallback and right extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param length the required reference-buffer length
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one top-edge reference buffer with AV1 frame-edge fallback and right extension
    private static int[] topReferenceSamples(MutablePlaneBuffer plane, int x, int y, int length, int defaultSample) {
        int[] references = new int[length];
        if (y <= 0) {
            int sample = x > 0 ? plane.sample(x - 1, y) : defaultSample - 1;
            fillReferences(references, sample);
            return references;
        }
        int maxX = plane.width() - 1;
        for (int i = 0; i < references.length; i++) {
            int sampleX = x + i;
            if (sampleX > maxX) {
                sampleX = maxX;
            }
            references[i] = plane.sample(sampleX, y - 1);
        }
        return references;
    }

    /// Returns one left-edge reference buffer with AV1 frame-edge fallback and bottom extension.
    ///
    /// @param plane the mutable destination plane
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param length the required reference-buffer length
    /// @param defaultSample the midpoint frame-edge default sample
    /// @return one left-edge reference buffer with AV1 frame-edge fallback and bottom extension
    private static int[] leftReferenceSamples(MutablePlaneBuffer plane, int x, int y, int length, int defaultSample) {
        int[] references = new int[length];
        if (x <= 0) {
            int sample = y > 0 ? plane.sample(x, y - 1) : defaultSample + 1;
            fillReferences(references, sample);
            return references;
        }
        int maxY = plane.height() - 1;
        for (int i = 0; i < references.length; i++) {
            int sampleY = y + i;
            if (sampleY > maxY) {
                sampleY = maxY;
            }
            references[i] = plane.sample(x - 1, sampleY);
        }
        return references;
    }

    /// Fills a reference buffer with one sample.
    ///
    /// @param references the reference buffer to fill
    /// @param sample the sample value to store
    private static void fillReferences(int[] references, int sample) {
        for (int i = 0; i < references.length; i++) {
            references[i] = sample;
        }
    }

    /// Returns one conceptual zone-2 edge sample addressed by signed edge index.
    ///
    /// Index `0` addresses the top-left sample, positive indices address the top edge, and
    /// negative indices address the left edge.
    ///
    /// @param topLeft the top-left reference sample
    /// @param top the top-edge directional reference buffer
    /// @param left the left-edge directional reference buffer
    /// @param index the conceptual signed edge index
    /// @return one conceptual zone-2 edge sample addressed by signed edge index
    private static int zone2Reference(int topLeft, int[] top, int[] left, int index) {
        if (index == 0) {
            return topLeft;
        }
        if (index > 0) {
            int topIndex = index - 1;
            if (topIndex >= top.length) {
                return top[top.length - 1];
            }
            return top[topIndex];
        }
        int leftIndex = -index - 1;
        if (leftIndex >= left.length) {
            return left[left.length - 1];
        }
        return left[leftIndex];
    }

    /// Returns one directional interpolation result between two edge samples.
    ///
    /// @param sample0 the first edge sample
    /// @param sample1 the second edge sample
    /// @param fraction the AV1 fractional interpolation position in `[0, 62]`
    /// @return one directional interpolation result between two edge samples
    private static int interpolate(int sample0, int sample1, int fraction) {
        return (sample0 * (64 - fraction) + sample1 * fraction + 32) >> 6;
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

    /// Returns the coded smooth-prediction reference axis for one visible edge length.
    ///
    /// Smooth prediction weights are tabled by coded block axis. A clipped right or bottom edge may
    /// expose fewer visible samples, so reconstruction uses the next legal smooth axis and writes
    /// only the visible footprint.
    ///
    /// @param visibleSize the visible edge length in samples
    /// @return the smooth-prediction axis used to select weights and the far-edge reference
    private static int smoothWeightAxisSize(int visibleSize) {
        if (visibleSize <= 1) {
            return 1;
        }
        if (visibleSize <= 2) {
            return 2;
        }
        if (visibleSize <= 4) {
            return 4;
        }
        if (visibleSize <= 8) {
            return 8;
        }
        if (visibleSize <= 16) {
            return 16;
        }
        if (visibleSize <= 32) {
            return 32;
        }
        if (visibleSize <= 64) {
            return 64;
        }
        throw new IllegalStateException("Unsupported smooth predictor size: " + visibleSize);
    }

    /// Returns one AV1 directional derivative table entry.
    ///
    /// @param halfAngleIndex the zero-based half-angle table index
    /// @return one AV1 directional derivative table entry
    private static int directionalDerivative(int halfAngleIndex) {
        if (halfAngleIndex < 0 || halfAngleIndex >= DIRECTIONAL_DERIVATIVES.length) {
            throw new IllegalStateException("Directional derivative index out of range: " + halfAngleIndex);
        }
        return DIRECTIONAL_DERIVATIVES[halfAngleIndex];
    }

    /// The internal prediction modes supported by the first reconstruction path.
    @NotNullByDefault
    private enum PredictionMode {
        /// DC prediction.
        DC,

        /// Vertical prediction.
        VERTICAL,

        /// Horizontal prediction.
        HORIZONTAL,

        /// Diagonal-down-left directional prediction.
        DIAGONAL_DOWN_LEFT,

        /// Diagonal-down-right directional prediction.
        DIAGONAL_DOWN_RIGHT,

        /// Vertical-right directional prediction.
        VERTICAL_RIGHT,

        /// Horizontal-down directional prediction.
        HORIZONTAL_DOWN,

        /// Horizontal-up directional prediction.
        HORIZONTAL_UP,

        /// Vertical-left directional prediction.
        VERTICAL_LEFT,

        /// Paeth prediction.
        PAETH,

        /// Smooth prediction.
        SMOOTH,

        /// Smooth vertical prediction.
        SMOOTH_VERTICAL,

        /// Smooth horizontal prediction.
        SMOOTH_HORIZONTAL;

        /// Returns whether this prediction mode currently routes through the directional predictor.
        ///
        /// @param angleDelta the signed directional angle delta
        /// @return whether this prediction mode currently routes through the directional predictor
        private boolean usesDirectionalPrediction(int angleDelta) {
            if (angleDelta != 0 && (this == VERTICAL || this == HORIZONTAL)) {
                return true;
            }
            return switch (this) {
                case DIAGONAL_DOWN_LEFT,
                        DIAGONAL_DOWN_RIGHT,
                        VERTICAL_RIGHT,
                        HORIZONTAL_DOWN,
                        HORIZONTAL_UP,
                        VERTICAL_LEFT -> true;
                default -> false;
            };
        }

        /// Returns whether this mode needs a smooth horizontal axis for clipped-edge prediction.
        ///
        /// @return whether this mode needs a smooth horizontal axis for clipped-edge prediction
        private boolean usesHorizontalSmoothReference() {
            return this == SMOOTH || this == SMOOTH_HORIZONTAL;
        }

        /// Returns whether this mode needs a smooth vertical axis for clipped-edge prediction.
        ///
        /// @return whether this mode needs a smooth vertical axis for clipped-edge prediction
        private boolean usesVerticalSmoothReference() {
            return this == SMOOTH || this == SMOOTH_VERTICAL;
        }

        /// Returns the AV1 directional base angle for one directional-capable mode.
        ///
        /// @return the AV1 directional base angle for one directional-capable mode
        private int directionalBaseAngle() {
            return switch (this) {
                case VERTICAL -> DIRECTIONAL_BASE_ANGLES[0];
                case HORIZONTAL -> DIRECTIONAL_BASE_ANGLES[1];
                case DIAGONAL_DOWN_LEFT -> DIRECTIONAL_BASE_ANGLES[2];
                case DIAGONAL_DOWN_RIGHT -> DIRECTIONAL_BASE_ANGLES[3];
                case VERTICAL_RIGHT -> DIRECTIONAL_BASE_ANGLES[4];
                case HORIZONTAL_DOWN -> DIRECTIONAL_BASE_ANGLES[5];
                case HORIZONTAL_UP -> DIRECTIONAL_BASE_ANGLES[6];
                case VERTICAL_LEFT -> DIRECTIONAL_BASE_ANGLES[7];
                default -> throw new IllegalStateException("Mode does not use directional prediction: " + this);
            };
        }
    }
}
