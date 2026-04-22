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

import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

/// Minimal intra predictor used by the first reconstruction-capable decode path.
///
/// The current implementation supports only non-directional AV1 intra modes and uses frame-edge
/// midpoint samples when top or left neighbors are unavailable.
@NotNullByDefault
final class IntraPredictor {
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
        boolean haveTop = y > 0;
        boolean haveLeft = x > 0;
        int value;
        if (haveTop && haveLeft) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            for (int sample : left) {
                sum += sample;
            }
            value = (sum + ((width + height) >> 1)) / (width + height);
        } else if (haveTop) {
            int sum = 0;
            for (int sample : top) {
                sum += sample;
            }
            value = (sum + (width >> 1)) / width;
        } else if (haveLeft) {
            int sum = 0;
            for (int sample : left) {
                sum += sample;
            }
            value = (sum + (height >> 1)) / height;
        } else {
            value = defaultSample;
        }
        fillBlock(plane, x, y, width, height, value);
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
