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
import org.jetbrains.annotations.Unmodifiable;

import java.util.function.IntBinaryOperator;

/// Minimal directional intra-prediction oracle used by recon-layer tests.
///
/// The oracle intentionally mirrors the planned first directional path only:
/// angle-based interpolation is enabled, while edge filtering and edge upsampling remain disabled.
@NotNullByDefault
final class DirectionalIntraPredictionOracle {
    /// The number of degrees represented by one signaled angle-delta step.
    private static final int ANGLE_STEP = 3;

    /// The AV1 directional intra derivative table used by the no-upsample prediction path.
    private static final int @Unmodifiable [] DR_INTRA_DERIVATIVE = {
            0, 0, 0,
            1023, 0, 0,
            547, 0, 0,
            372, 0, 0, 0, 0,
            273, 0, 0,
            215, 0, 0,
            178, 0, 0,
            151, 0, 0,
            132, 0, 0,
            116, 0, 0,
            102, 0, 0, 0,
            90, 0, 0,
            80, 0, 0,
            71, 0, 0,
            64, 0, 0,
            57, 0, 0,
            51, 0, 0,
            45, 0, 0, 0,
            40, 0, 0,
            35, 0, 0,
            31, 0, 0,
            27, 0, 0,
            23, 0, 0,
            19, 0, 0,
            15, 0, 0, 0, 0,
            11, 0, 0,
            7, 0, 0,
            3, 0, 0
    };

    /// Prevents instantiation of this utility class.
    private DirectionalIntraPredictionOracle() {
    }

    /// Returns the expected luma block predicted from one mutable reconstruction plane.
    ///
    /// @param plane the mutable plane that supplies already reconstructed reference samples
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional luma intra mode
    /// @param angleDelta the signed luma angle delta
    /// @return the expected luma prediction block in row-major form
    static int[][] predictLuma(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta
    ) {
        return predict(
                plane::sample,
                plane.width(),
                plane.height(),
                plane.bitDepth(),
                x,
                y,
                width,
                height,
                predictionAngle(mode, angleDelta)
        );
    }

    /// Returns the expected luma block predicted from one immutable decoded plane.
    ///
    /// @param plane the decoded plane that supplies already reconstructed reference samples
    /// @param bitDepth the decoded sample bit depth
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional luma intra mode
    /// @param angleDelta the signed luma angle delta
    /// @return the expected luma prediction block in row-major form
    static int[][] predictLuma(
            DecodedPlane plane,
            int bitDepth,
            int x,
            int y,
            int width,
            int height,
            LumaIntraPredictionMode mode,
            int angleDelta
    ) {
        return predict(
                plane::sample,
                plane.width(),
                plane.height(),
                bitDepth,
                x,
                y,
                width,
                height,
                predictionAngle(mode, angleDelta)
        );
    }

    /// Returns the expected chroma block predicted from one mutable reconstruction plane.
    ///
    /// @param plane the mutable plane that supplies already reconstructed reference samples
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional chroma intra mode
    /// @param angleDelta the signed chroma angle delta
    /// @return the expected chroma prediction block in row-major form
    static int[][] predictChroma(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta
    ) {
        return predict(
                plane::sample,
                plane.width(),
                plane.height(),
                plane.bitDepth(),
                x,
                y,
                width,
                height,
                predictionAngle(mode, angleDelta)
        );
    }

    /// Returns the expected chroma block predicted from one immutable decoded plane.
    ///
    /// @param plane the decoded plane that supplies already reconstructed reference samples
    /// @param bitDepth the decoded sample bit depth
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param mode the directional chroma intra mode
    /// @param angleDelta the signed chroma angle delta
    /// @return the expected chroma prediction block in row-major form
    static int[][] predictChroma(
            DecodedPlane plane,
            int bitDepth,
            int x,
            int y,
            int width,
            int height,
            UvIntraPredictionMode mode,
            int angleDelta
    ) {
        return predict(
                plane::sample,
                plane.width(),
                plane.height(),
                bitDepth,
                x,
                y,
                width,
                height,
                predictionAngle(mode, angleDelta)
        );
    }

    /// Returns the prediction angle in degrees for one directional luma mode.
    ///
    /// @param mode the directional luma mode
    /// @param angleDelta the signed directional angle delta
    /// @return the final prediction angle in degrees
    private static int predictionAngle(LumaIntraPredictionMode mode, int angleDelta) {
        if (!mode.isDirectional()) {
            throw new IllegalArgumentException("Mode is not directional: " + mode);
        }
        return basePredictionAngle(mode) + angleDelta * ANGLE_STEP;
    }

    /// Returns the prediction angle in degrees for one directional chroma mode.
    ///
    /// @param mode the directional chroma mode
    /// @param angleDelta the signed directional angle delta
    /// @return the final prediction angle in degrees
    private static int predictionAngle(UvIntraPredictionMode mode, int angleDelta) {
        if (!mode.isDirectional()) {
            throw new IllegalArgumentException("Mode is not directional: " + mode);
        }
        return basePredictionAngle(mode) + angleDelta * ANGLE_STEP;
    }

    /// Returns the base directional angle for one luma intra mode.
    ///
    /// @param mode the luma intra mode
    /// @return the base directional angle in degrees
    private static int basePredictionAngle(LumaIntraPredictionMode mode) {
        return switch (mode) {
            case VERTICAL -> 90;
            case HORIZONTAL -> 180;
            case DIAGONAL_DOWN_LEFT -> 45;
            case DIAGONAL_DOWN_RIGHT -> 135;
            case VERTICAL_RIGHT -> 113;
            case HORIZONTAL_DOWN -> 157;
            case HORIZONTAL_UP -> 203;
            case VERTICAL_LEFT -> 67;
            case DC, SMOOTH, SMOOTH_VERTICAL, SMOOTH_HORIZONTAL, PAETH ->
                    throw new IllegalArgumentException("Mode is not directional: " + mode);
        };
    }

    /// Returns the base directional angle for one chroma intra mode.
    ///
    /// @param mode the chroma intra mode
    /// @return the base directional angle in degrees
    private static int basePredictionAngle(UvIntraPredictionMode mode) {
        return switch (mode) {
            case VERTICAL -> 90;
            case HORIZONTAL -> 180;
            case DIAGONAL_DOWN_LEFT -> 45;
            case DIAGONAL_DOWN_RIGHT -> 135;
            case VERTICAL_RIGHT -> 113;
            case HORIZONTAL_DOWN -> 157;
            case HORIZONTAL_UP -> 203;
            case VERTICAL_LEFT -> 67;
            case DC, SMOOTH, SMOOTH_VERTICAL, SMOOTH_HORIZONTAL, PAETH, CFL ->
                    throw new IllegalArgumentException("Mode is not directional: " + mode);
        };
    }

    /// Returns the expected directional prediction block for one plane and angle.
    ///
    /// @param sampleReader the plane sample accessor
    /// @param planeWidth the plane width in samples
    /// @param planeHeight the plane height in samples
    /// @param bitDepth the decoded sample bit depth
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param predictionAngle the final prediction angle in degrees
    /// @return the expected prediction block in row-major form
    private static int[][] predict(
            IntBinaryOperator sampleReader,
            int planeWidth,
            int planeHeight,
            int bitDepth,
            int x,
            int y,
            int width,
            int height,
            int predictionAngle
    ) {
        int defaultSample = 1 << (bitDepth - 1);
        int[] aboveRow = new int[width + height];
        int[] leftColumn = new int[width + height];
        for (int i = 0; i < aboveRow.length; i++) {
            aboveRow[i] = sampleOrFallback(sampleReader, planeWidth, planeHeight, x + i, y - 1, defaultSample);
            leftColumn[i] = sampleOrFallback(sampleReader, planeWidth, planeHeight, x - 1, y + i, defaultSample);
        }
        int topLeft = defaultTopLeft(sampleReader, planeWidth, planeHeight, x, y, defaultSample);

        int[][] predicted = new int[height][width];
        if (predictionAngle < 90) {
            int dx = DR_INTRA_DERIVATIVE[predictionAngle];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int idx = (row + 1) * dx;
                    int base = (idx >> 6) + column;
                    int shift = (idx >> 1) & 0x1F;
                    int maxBaseX = width + height - 1;
                    predicted[row][column] = base < maxBaseX
                            ? round5(aboveSample(aboveRow, topLeft, base) * (32 - shift)
                            + aboveSample(aboveRow, topLeft, base + 1) * shift)
                            : aboveSample(aboveRow, topLeft, maxBaseX);
                }
            }
            return predicted;
        }

        if (predictionAngle < 180) {
            int dx = DR_INTRA_DERIVATIVE[180 - predictionAngle];
            int dy = DR_INTRA_DERIVATIVE[predictionAngle - 90];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int idx = (column << 6) - (row + 1) * dx;
                    int base = idx >> 6;
                    if (base >= -1) {
                        int shift = (idx >> 1) & 0x1F;
                        predicted[row][column] = round5(
                                aboveSample(aboveRow, topLeft, base) * (32 - shift)
                                        + aboveSample(aboveRow, topLeft, base + 1) * shift
                        );
                    } else {
                        idx = (row << 6) - (column + 1) * dy;
                        base = idx >> 6;
                        int shift = (idx >> 1) & 0x1F;
                        predicted[row][column] = round5(
                                leftSample(leftColumn, topLeft, base) * (32 - shift)
                                        + leftSample(leftColumn, topLeft, base + 1) * shift
                        );
                    }
                }
            }
            return predicted;
        }

        if (predictionAngle > 180) {
            int dy = DR_INTRA_DERIVATIVE[270 - predictionAngle];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int idx = (column + 1) * dy;
                    int base = (idx >> 6) + row;
                    int shift = (idx >> 1) & 0x1F;
                    predicted[row][column] = round5(
                            leftSample(leftColumn, topLeft, base) * (32 - shift)
                                    + leftSample(leftColumn, topLeft, base + 1) * shift
                    );
                }
            }
            return predicted;
        }

        if (predictionAngle == 90) {
            for (int row = 0; row < height; row++) {
                System.arraycopy(aboveRow, 0, predicted[row], 0, width);
            }
            return predicted;
        }

        if (predictionAngle == 180) {
            for (int row = 0; row < height; row++) {
                int value = leftColumn[row];
                for (int column = 0; column < width; column++) {
                    predicted[row][column] = value;
                }
            }
            return predicted;
        }

        throw new IllegalStateException("Unsupported prediction angle: " + predictionAngle);
    }

    /// Returns one above-row reference sample, including the top-left slot at index `-1`.
    ///
    /// @param aboveRow the non-negative above-row reference samples
    /// @param topLeft the shared top-left reference sample
    /// @param index the conceptual above-row index
    /// @return the requested above-row reference sample
    private static int aboveSample(int[] aboveRow, int topLeft, int index) {
        return index < 0 ? topLeft : aboveRow[index];
    }

    /// Returns one left-column reference sample, including the top-left slot at index `-1`.
    ///
    /// @param leftColumn the non-negative left-column reference samples
    /// @param topLeft the shared top-left reference sample
    /// @param index the conceptual left-column index
    /// @return the requested left-column reference sample
    private static int leftSample(int[] leftColumn, int topLeft, int index) {
        return index < 0 ? topLeft : leftColumn[index];
    }

    /// Returns the predictor top-left sample using the same fallback precedence as the recon buffer.
    ///
    /// @param sampleReader the plane sample accessor
    /// @param planeWidth the plane width in samples
    /// @param planeHeight the plane height in samples
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param defaultSample the midpoint fallback sample
    /// @return the top-left predictor sample
    private static int defaultTopLeft(
            IntBinaryOperator sampleReader,
            int planeWidth,
            int planeHeight,
            int x,
            int y,
            int defaultSample
    ) {
        if (x > 0 && y > 0) {
            return sampleReader.applyAsInt(x - 1, y - 1);
        }
        if (x > 0) {
            return sampleOrFallback(sampleReader, planeWidth, planeHeight, x - 1, y, defaultSample);
        }
        if (y > 0) {
            return sampleOrFallback(sampleReader, planeWidth, planeHeight, x, y - 1, defaultSample);
        }
        return defaultSample;
    }

    /// Returns one in-range reference sample or the supplied fallback value.
    ///
    /// @param sampleReader the plane sample accessor
    /// @param planeWidth the plane width in samples
    /// @param planeHeight the plane height in samples
    /// @param x the zero-based sample X coordinate
    /// @param y the zero-based sample Y coordinate
    /// @param fallbackValue the fallback value returned when the coordinate is out of range
    /// @return the in-range sample or the fallback value
    private static int sampleOrFallback(
            IntBinaryOperator sampleReader,
            int planeWidth,
            int planeHeight,
            int x,
            int y,
            int fallbackValue
    ) {
        if (x < 0 || x >= planeWidth || y < 0 || y >= planeHeight) {
            return fallbackValue;
        }
        return sampleReader.applyAsInt(x, y);
    }

    /// Rounds one weighted predictor sum by five fractional bits.
    ///
    /// @param value the weighted predictor sum
    /// @return the rounded predictor sample
    private static int round5(int value) {
        return (value + 16) >> 5;
    }
}
