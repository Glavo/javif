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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the currently supported intra-prediction paths.
@NotNullByDefault
final class IntraPredictorTest {
    /// The predictor under test with storage isolated to this test instance.
    private final IntraPredictor predictor = new IntraPredictor();

    /// Verifies that lazily allocated directional references remain owned by the predictor across
    /// a sequential thread handoff.
    ///
    /// @throws InterruptedException if the test thread is interrupted while awaiting the worker
    /// @throws ExecutionException if directional prediction fails on the worker
    @Test
    void reusesDirectionalWorkspaceAfterSequentialThreadHandoff()
            throws InterruptedException, ExecutionException {
        int x = 3;
        int y = 3;
        int[] top = {21, 64, 93, 137, 82, 149, 205, 171};
        int[] left = {34, 58, 101, 88, 145, 179, 152, 214};
        MutablePlaneBuffer initialPlane = new MutablePlaneBuffer(12, 12, 8);
        seedDirectionalReferences(initialPlane, x, y, 77, top, left);
        int[][] expected = DirectionalIntraPredictionOracle.predictLuma(
                initialPlane,
                x,
                y,
                4,
                4,
                LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                1
        );
        predictor.predictLuma(
                initialPlane,
                x,
                y,
                4,
                4,
                LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                1
        );
        Object initialWorkspace = predictionWorkspace(predictor);
        FutureTask<MutablePlaneBuffer> workerTask = new FutureTask<>(() -> {
            MutablePlaneBuffer workerPlane = new MutablePlaneBuffer(12, 12, 8);
            seedDirectionalReferences(workerPlane, x, y, 77, top, left);
            predictor.predictLuma(
                    workerPlane,
                    x,
                    y,
                    4,
                    4,
                    LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                    1
            );
            return workerPlane;
        });
        Thread worker = new Thread(workerTask, "intra-prediction-workspace-handoff");

        worker.start();
        MutablePlaneBuffer workerPlane = workerTask.get();

        assertSame(initialWorkspace, predictionWorkspace(predictor));
        assertBlockEquals(initialPlane, x, y, expected);
        assertBlockEquals(workerPlane, x, y, expected);
    }

    /// Verifies that DC prediction averages the available top and left reference samples.
    @Test
    void dcPredictionAveragesAvailableTopAndLeftNeighbors() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 99);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(0, 1, 30);
        plane.setSample(0, 2, 40);

        predictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.DC, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {25, 25},
                        {25, 25}
                }
        );
    }

    /// Verifies that DC prediction does not read a left neighbor across a tile boundary.
    @Test
    void dcPredictionTreatsTileLeftBoundaryAsUnavailable() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(6, 4, 8);
        plane.setSample(1, 1, 5);
        plane.setSample(2, 0, 50);
        plane.setSample(3, 0, 70);

        predictor.predictLuma(
                plane,
                2,
                1,
                2,
                2,
                LumaIntraPredictionMode.DC,
                0,
                false,
                false,
                -1,
                -1,
                2,
                0,
                6,
                4
        );

        assertBlockEquals(
                plane,
                2,
                1,
                new int[][]{
                        {60, 60},
                        {60, 60}
                }
        );
    }

    /// Verifies that DC prediction does not read a top neighbor across a tile boundary.
    @Test
    void dcPredictionTreatsTileTopBoundaryAsUnavailable() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 6, 8);
        plane.setSample(1, 1, 5);
        plane.setSample(0, 2, 80);
        plane.setSample(0, 3, 100);

        predictor.predictLuma(
                plane,
                1,
                2,
                2,
                2,
                LumaIntraPredictionMode.DC,
                0,
                false,
                false,
                -1,
                -1,
                0,
                2,
                4,
                6
        );

        assertBlockEquals(
                plane,
                1,
                2,
                new int[][]{
                        {90, 90},
                        {90, 90}
                }
        );
    }

    /// Verifies that filter-intra recursion edge-extends references for clipped right-edge blocks.
    @Test
    void filterIntraPredictionClampsRecursiveReferencesAtRightEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(6, 4, 8);
        plane.setSample(2, 0, 64);
        plane.setSample(3, 0, 96);
        plane.setSample(4, 0, 128);
        plane.setSample(5, 0, 160);

        assertDoesNotThrow(() -> predictor.predictFilterIntraLuma(
                plane,
                3,
                1,
                5,
                2,
                FilterIntraMode.VERTICAL,
                0,
                0,
                6,
                4
        ));
    }

    /// Verifies that filter-intra keeps consuming its recursively generated rows after the
    /// external left edge reaches the bottom frame boundary.
    @Test
    void filterIntraPredictionKeepsRecursiveRowsPastBottomBoundary() {
        MutablePlaneBuffer clippedBoundaryPlane = new MutablePlaneBuffer(12, 12, 8);
        MutablePlaneBuffer extendedBoundaryPlane = new MutablePlaneBuffer(12, 12, 8);
        int[] top = {31, 67, 109, 151, 193, 227, 173, 89};
        int[] left = {211, 167, 103, 43, 43, 43, 43, 43};
        seedDirectionalReferences(clippedBoundaryPlane, 1, 1, 127, top, left);
        seedDirectionalReferences(extendedBoundaryPlane, 1, 1, 127, top, left);

        predictor.predictFilterIntraLuma(
                clippedBoundaryPlane,
                1,
                1,
                8,
                8,
                FilterIntraMode.PAETH,
                0,
                0,
                12,
                5
        );
        predictor.predictFilterIntraLuma(
                extendedBoundaryPlane,
                1,
                1,
                8,
                8,
                FilterIntraMode.PAETH,
                0,
                0,
                12,
                12
        );

        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                assertEquals(
                        extendedBoundaryPlane.sample(1 + column, 1 + row),
                        clippedBoundaryPlane.sample(1 + column, 1 + row),
                        "Mismatch at relative row " + row + ", column " + column
                );
            }
        }
    }

    /// Verifies that vertical prediction copies the top edge into every output row.
    @Test
    void verticalPredictionRepeatsTopReferenceRow() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(6, 4, 8);
        plane.setSample(1, 0, 11);
        plane.setSample(2, 0, 22);
        plane.setSample(3, 0, 33);

        predictor.predictLuma(plane, 1, 1, 3, 2, LumaIntraPredictionMode.VERTICAL, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {11, 22, 33},
                        {11, 22, 33}
                }
        );
    }

    /// Verifies that top-frame-edge vertical prediction uses the available left reference sample.
    @Test
    void verticalPredictionUsesLeftReferenceOnTopFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 51);

        predictor.predictLuma(plane, 1, 0, 3, 2, LumaIntraPredictionMode.VERTICAL, 0);

        assertBlockEquals(
                plane,
                1,
                0,
                new int[][]{
                        {51, 51, 51},
                        {51, 51, 51}
                }
        );
    }

    /// Verifies that horizontal prediction copies the left edge into every output column.
    @Test
    void horizontalPredictionRepeatsLeftReferenceColumn() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 6, 8);
        plane.setSample(0, 1, 7);
        plane.setSample(0, 2, 9);
        plane.setSample(0, 3, 11);

        predictor.predictLuma(plane, 1, 1, 2, 3, LumaIntraPredictionMode.HORIZONTAL, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {7, 7},
                        {9, 9},
                        {11, 11}
                }
        );
    }

    /// Verifies that left-frame-edge horizontal prediction uses the available top reference sample.
    @Test
    void horizontalPredictionUsesTopReferenceOnLeftFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 76);

        predictor.predictLuma(plane, 0, 1, 2, 3, LumaIntraPredictionMode.HORIZONTAL, 0);

        assertBlockEquals(
                plane,
                0,
                1,
                new int[][]{
                        {76, 76},
                        {76, 76},
                        {76, 76}
                }
        );
    }

    /// Verifies that Paeth prediction selects among left, top, and top-left references per sample.
    @Test
    void paethPredictionChoosesClosestReference() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 50);
        plane.setSample(1, 0, 100);
        plane.setSample(2, 0, 60);
        plane.setSample(0, 1, 0);
        plane.setSample(0, 2, 80);

        predictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.PAETH, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {50, 0},
                        {100, 80}
                }
        );
    }

    /// Verifies that Paeth prediction on the left frame edge uses the available top references.
    @Test
    void paethPredictionUsesTopReferencesOnLeftFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 76);
        plane.setSample(1, 0, 51);
        plane.setSample(2, 0, 99);

        predictor.predictLuma(plane, 0, 1, 3, 2, LumaIntraPredictionMode.PAETH, 0);

        assertBlockEquals(
                plane,
                0,
                1,
                new int[][]{
                        {76, 51, 99},
                        {76, 51, 99}
                }
        );
    }

    /// Verifies that Paeth prediction on the top frame edge uses the available left references.
    @Test
    void paethPredictionUsesLeftReferencesOnTopFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 90);
        plane.setSample(0, 1, 80);

        predictor.predictLuma(plane, 1, 0, 2, 2, LumaIntraPredictionMode.PAETH, 0);

        assertBlockEquals(
                plane,
                1,
                0,
                new int[][]{
                        {90, 90},
                        {80, 80}
                }
        );
    }

    /// Verifies that smooth prediction blends top, left, right, and bottom references with AV1 weights.
    @Test
    void smoothPredictionInterpolatesReferenceEdges() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(1, 0, 100);
        plane.setSample(2, 0, 200);
        plane.setSample(0, 1, 50);
        plane.setSample(0, 2, 150);

        predictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.SMOOTH, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {75, 162},
                        {138, 175}
                }
        );
    }

    /// Verifies that smooth prediction uses the next legal coded axis for a clipped visible block.
    @Test
    void smoothPredictionClipsRightAndBottomEdges() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(3, 0, 30);
        plane.setSample(0, 1, 60);
        plane.setSample(0, 2, 70);
        plane.setSample(0, 3, 80);

        predictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {35, 34, 35},
                        {55, 49, 47},
                        {68, 60, 55}
                }
        );
    }

    /// Verifies that smooth vertical prediction uses the next legal coded vertical axis when the
    /// bottom edge is clipped.
    @Test
    void smoothVerticalPredictionClipsBottomEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(3, 0, 30);
        plane.setSample(0, 1, 60);
        plane.setSample(0, 2, 70);
        plane.setSample(0, 3, 80);

        predictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH_VERTICAL, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {10, 20, 30},
                        {39, 45, 51},
                        {57, 60, 63}
                }
        );
    }

    /// Verifies that smooth horizontal prediction uses the next legal coded horizontal axis when
    /// the right edge is clipped.
    @Test
    void smoothHorizontalPredictionClipsRightEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(3, 0, 30);
        plane.setSample(0, 1, 60);
        plane.setSample(0, 2, 70);
        plane.setSample(0, 3, 80);

        predictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH_HORIZONTAL, 0);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {60, 47, 40},
                        {70, 53, 43},
                        {80, 59, 47}
                }
        );
    }

    /// Verifies that large smooth blocks are predicted through 64x64 sub-kernel regions.
    @Test
    void smoothPredictionSplitsLargeBlocksIntoSupportedKernelRegions() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(129, 129, 8);
        for (int i = 1; i < 129; i++) {
            plane.setSample(i, 0, 96);
            plane.setSample(0, i, 160);
        }

        predictor.predictLuma(plane, 1, 1, 128, 128, LumaIntraPredictionMode.SMOOTH, 0);

        assertEquals(128, plane.sample(1, 1));
        assertTrue(plane.sample(64, 1) > 0);
        assertTrue(plane.sample(65, 65) > 0);
        assertTrue(plane.sample(128, 128) > 0);
    }

    /// Verifies that filter-intra prediction applies the recursive 4x2 tap tables in raster order.
    @Test
    void filterIntraPredictionUsesRecursiveTapTables() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(5, 5, 8);
        plane.setSample(0, 0, 50);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(3, 0, 30);
        plane.setSample(4, 0, 40);
        plane.setSample(0, 1, 60);
        plane.setSample(0, 2, 70);
        plane.setSample(0, 3, 80);
        plane.setSample(0, 4, 90);

        predictor.predictFilterIntraLuma(plane, 1, 1, 4, 4, FilterIntraMode.DC);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {33, 32, 38, 40},
                        {51, 46, 44, 46},
                        {66, 58, 55, 52},
                        {79, 71, 65, 62}
                }
        );
    }

    /// Verifies that filter-intra prediction accepts a visible edge footprint that does not align
    /// to the internal recursive 4x2 prediction unit.
    @Test
    void filterIntraPredictionClipsRightAndBottomEdges() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 50);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(3, 0, 30);
        plane.setSample(0, 1, 60);
        plane.setSample(0, 2, 70);
        plane.setSample(0, 3, 80);

        predictor.predictFilterIntraLuma(plane, 1, 1, 3, 3, FilterIntraMode.DC);

        assertBlockEquals(
                plane,
                1,
                1,
                new int[][]{
                        {33, 32, 38},
                        {51, 46, 44},
                        {66, 58, 55}
                }
        );
    }

    /// Verifies that filter-intra prediction uses the left edge when top references are missing.
    @Test
    void filterIntraPredictionUsesLeftReferencesOnTopFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 51);
        plane.setSample(0, 1, 51);

        predictor.predictFilterIntraLuma(plane, 1, 0, 2, 2, FilterIntraMode.DC);

        assertBlockEquals(
                plane,
                1,
                0,
                new int[][]{
                        {51, 51},
                        {51, 51}
                }
        );
    }

    /// Verifies that filter-intra prediction uses the top edge when left references are missing.
    @Test
    void filterIntraPredictionUsesTopReferencesOnLeftFrameEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 76);
        plane.setSample(1, 0, 76);

        predictor.predictFilterIntraLuma(plane, 0, 1, 2, 2, FilterIntraMode.DC);

        assertBlockEquals(
                plane,
                0,
                1,
                new int[][]{
                        {76, 76},
                        {76, 76}
                }
        );
    }

    /// Verifies that recursive filter-intra units use the preceding top or left reference instead
    /// of restarting from the block's original top-left sample.
    @Test
    void filterIntraPredictionAdvancesTopLeftReferenceBetweenRecursiveUnits() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(8, 4, 8);

        predictor.predictFilterIntraLuma(plane, 0, 0, 8, 4, FilterIntraMode.VERTICAL);

        assertBlockEquals(
                plane,
                0,
                0,
                new int[][]{
                        {128, 127, 127, 127, 127, 127, 127, 127},
                        {128, 127, 127, 127, 127, 127, 127, 127},
                        {128, 127, 127, 127, 127, 127, 127, 127},
                        {128, 127, 127, 127, 127, 127, 127, 127}
                }
        );
    }

    /// Verifies that `I420` CFL prediction derives signed AC from reconstructed luma and applies alpha.
    @Test
    void chromaCflPredictionUsesDownsampledLumaAc() {
        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(4, 4, 8);
        int[][] lumaSamples = {
                {10, 20, 30, 40},
                {50, 60, 70, 80},
                {90, 100, 110, 120},
                {130, 140, 150, 160}
        };
        for (int row = 0; row < lumaSamples.length; row++) {
            for (int column = 0; column < lumaSamples[row].length; column++) {
                lumaPlane.setSample(column, row, lumaSamples[row][column]);
            }
        }

        MutablePlaneBuffer chromaPlane = new MutablePlaneBuffer(2, 2, 8);
        predictor.predictChromaCflI420(chromaPlane, lumaPlane, 0, 0, 0, 0, 2, 2, 4);

        assertBlockEquals(
                chromaPlane,
                0,
                0,
                new int[][]{
                        {103, 113},
                        {143, 153}
                }
        );
    }

    /// Verifies that CFL pads the subsampled edge values instead of reading beyond the MI grid.
    @Test
    void chromaCflPredictionPadsSubsampledValuesAtPartialFrameEdge() {
        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(8, 4, 8);
        int[][] lumaSamples = {
                {10, 10, 20, 20, 30, 30, 100, 100},
                {10, 10, 20, 20, 30, 30, 100, 100},
                {200, 200, 200, 200, 200, 200, 200, 200},
                {200, 200, 200, 200, 200, 200, 200, 200}
        };
        for (int row = 0; row < lumaSamples.length; row++) {
            for (int column = 0; column < lumaSamples[row].length; column++) {
                lumaPlane.setSample(column, row, lumaSamples[row][column]);
            }
        }

        MutablePlaneBuffer chromaPlane = new MutablePlaneBuffer(4, 2, 8);
        predictor.predictChromaCfl(
                chromaPlane,
                lumaPlane,
                0,
                0,
                0,
                0,
                4,
                2,
                8,
                1,
                1,
                3,
                1,
                0,
                0,
                3,
                1
        );

        assertBlockEquals(
                chromaPlane,
                0,
                0,
                new int[][]{
                        {115, 125, 136, 136},
                        {115, 125, 136, 136}
                }
        );
    }

    /// Verifies that generalized `I422` CFL prediction derives signed AC from horizontally
    /// subsampled reconstructed luma.
    @Test
    void chromaCflPredictionUsesHorizontallySubsampledLumaAc() {
        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(4, 4, 8);
        int[][] lumaSamples = {
                {128, 136, 144, 152},
                {64, 96, 120, 144},
                {132, 147, 164, 164},
                {68, 101, 133, 155}
        };
        for (int row = 0; row < lumaSamples.length; row++) {
            for (int column = 0; column < lumaSamples[row].length; column++) {
                lumaPlane.setSample(column, row, lumaSamples[row][column]);
            }
        }

        MutablePlaneBuffer chromaPlane = new MutablePlaneBuffer(2, 4, 8);
        predictor.predictChromaCfl(chromaPlane, lumaPlane, 0, 0, 0, 0, 2, 4, 4, 1, 0);

        assertBlockEquals(
                chromaPlane,
                0,
                0,
                new int[][]{
                        {130, 138},
                        {104, 130},
                        {134, 146},
                        {106, 136}
                }
        );
    }

    /// Verifies that generalized `I444` CFL prediction derives signed AC from full-resolution
    /// reconstructed luma.
    @Test
    void chromaCflPredictionUsesFullResolutionLumaAc() {
        MutablePlaneBuffer lumaPlane = new MutablePlaneBuffer(4, 4, 8);
        int[][] lumaSamples = {
                {128, 136, 144, 152},
                {64, 96, 120, 144},
                {132, 147, 164, 164},
                {68, 101, 133, 155}
        };
        for (int row = 0; row < lumaSamples.length; row++) {
            for (int column = 0; column < lumaSamples[row].length; column++) {
                lumaPlane.setSample(column, row, lumaSamples[row][column]);
            }
        }

        MutablePlaneBuffer chromaPlane = new MutablePlaneBuffer(4, 4, 8);
        predictor.predictChromaCfl(chromaPlane, lumaPlane, 0, 0, 0, 0, 4, 4, 4, 0, 0);

        assertBlockEquals(
                chromaPlane,
                0,
                0,
                new int[][]{
                        {128, 132, 136, 140},
                        {96, 112, 124, 136},
                        {130, 138, 146, 146},
                        {98, 114, 131, 142}
                }
        );
    }

    /// Verifies that directional luma prediction interpolates along the top edge in the shallow-angle zone.
    @Test
    void directionalLumaPredictionInterpolatesFromTopEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                77,
                new int[]{21, 64, 93, 137, 82, 149, 205, 171},
                new int[]{34, 58, 101, 88, 145, 179, 152, 214}
        );
        int[][] expected = DirectionalIntraPredictionOracle.predictLuma(
                plane,
                x,
                y,
                4,
                4,
                LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                1
        );

        predictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, 1);

        assertBlockEquals(plane, x, y, expected);
    }

    /// Verifies that directional luma prediction also supports negative angle deltas in the
    /// shallow-angle zone.
    @Test
    void directionalLumaPredictionSupportsNegativeAngleDelta() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                77,
                new int[]{21, 64, 93, 137, 82, 149, 205, 171},
                new int[]{34, 58, 101, 88, 145, 179, 152, 214}
        );
        int[][] expected = DirectionalIntraPredictionOracle.predictLuma(
                plane,
                x,
                y,
                4,
                4,
                LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                -1
        );

        predictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, -1);

        assertBlockEquals(plane, x, y, expected);
    }

    /// Verifies that sequence-enabled intra-edge filtering pre-filters directional top references.
    @Test
    void directionalLumaPredictionAppliesIntraEdgeFiltering() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(20, 12, 8);
        int x = 2;
        int y = 2;
        seedDirectionalReferences(
                plane,
                x,
                y,
                200,
                new int[]{10, 100, 30, 160, 70, 180, 90, 210, 120, 220, 130, 230, 140, 240, 150, 250},
                new int[]{34, 58, 101, 88, 145, 179, 152, 214}
        );

        predictor.predictLuma(plane, x, y, 8, 8, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, 0, true, false);

        assertEquals(60, plane.sample(x, y));
        assertEquals(80, plane.sample(x + 1, y));
        assertEquals(80, plane.sample(x, y + 1));
    }

    /// Verifies that sequence-enabled intra-edge upsampling inserts half-edge samples before
    /// directional interpolation on small shallow-angle blocks.
    @Test
    void directionalLumaPredictionAppliesIntraEdgeUpsampling() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                20,
                new int[]{40, 200, 80, 120, 90, 130, 100, 140},
                new int[]{34, 58, 101, 88, 145, 179, 152, 214}
        );

        predictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.VERTICAL_LEFT, 0, true, false);

        assertEquals(115, plane.sample(x, y));
    }

    /// Verifies that directional luma prediction crosses from top references into left references in the mid-angle zone.
    @Test
    void directionalLumaPredictionTransitionsFromTopToLeftReferences() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                77,
                new int[]{21, 64, 93, 137, 82, 149, 205, 171},
                new int[]{34, 58, 101, 88, 145, 179, 152, 214}
        );
        int[][] expected = DirectionalIntraPredictionOracle.predictLuma(
                plane,
                x,
                y,
                4,
                4,
                LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT,
                0
        );

        predictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT, 0);

        assertBlockEquals(plane, x, y, expected);
    }

    /// Verifies that zone-2 edge filtering excludes top references beyond the frame boundary.
    @Test
    void directionalZoneTwoFilteringIgnoresUnavailableTopReferences() {
        MutablePlaneBuffer firstPlane = new MutablePlaneBuffer(40, 40, 8);
        MutablePlaneBuffer secondPlane = new MutablePlaneBuffer(40, 40, 8);
        int x = 4;
        int y = 4;
        firstPlane.setSample(x - 1, y - 1, 128);
        secondPlane.setSample(x - 1, y - 1, 128);
        for (int index = 0; index < 32; index++) {
            int topSample = index < 8 ? 40 + index * 7 : 16 + index;
            firstPlane.setSample(x + index, y - 1, topSample);
            secondPlane.setSample(x + index, y - 1, index < 8 ? topSample : 240 - index);
            int leftSample = 64 + index * 3;
            firstPlane.setSample(x - 1, y + index, leftSample);
            secondPlane.setSample(x - 1, y + index, leftSample);
        }

        predictor.predictLuma(
                firstPlane,
                x,
                y,
                32,
                32,
                LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT,
                -1,
                true,
                false,
                8,
                32
        );
        predictor.predictLuma(
                secondPlane,
                x,
                y,
                32,
                32,
                LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT,
                -1,
                true,
                false,
                8,
                32
        );

        for (int row = 0; row < 32; row++) {
            for (int column = 0; column < 32; column++) {
                assertEquals(
                        firstPlane.sample(x + column, y + row),
                        secondPlane.sample(x + column, y + row),
                        "Mismatch at relative row " + row + ", column " + column
                );
            }
        }
    }

    /// Verifies that zone-2 edge filtering excludes left references beyond the frame boundary.
    @Test
    void directionalZoneTwoFilteringIgnoresUnavailableLeftReferences() {
        MutablePlaneBuffer firstPlane = new MutablePlaneBuffer(40, 40, 8);
        MutablePlaneBuffer secondPlane = new MutablePlaneBuffer(40, 40, 8);
        int x = 4;
        int y = 4;
        firstPlane.setSample(x - 1, y - 1, 128);
        secondPlane.setSample(x - 1, y - 1, 128);
        for (int index = 0; index < 32; index++) {
            int topSample = 64 + index * 3;
            firstPlane.setSample(x + index, y - 1, topSample);
            secondPlane.setSample(x + index, y - 1, topSample);
            int leftSample = index < 8 ? 40 + index * 7 : 16 + index;
            firstPlane.setSample(x - 1, y + index, leftSample);
            secondPlane.setSample(x - 1, y + index, index < 8 ? leftSample : 240 - index);
        }

        predictor.predictLuma(
                firstPlane,
                x,
                y,
                32,
                32,
                LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT,
                1,
                true,
                false,
                32,
                8
        );
        predictor.predictLuma(
                secondPlane,
                x,
                y,
                32,
                32,
                LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT,
                1,
                true,
                false,
                32,
                8
        );

        for (int row = 0; row < 32; row++) {
            for (int column = 0; column < 32; column++) {
                assertEquals(
                        firstPlane.sample(x + column, y + row),
                        secondPlane.sample(x + column, y + row),
                        "Mismatch at relative row " + row + ", column " + column
                );
            }
        }
    }

    /// Verifies that zone-2 directional prediction samples the top-left/above edge when the
    /// projected top-edge base reaches `-1`.
    @Test
    void directionalChromaPredictionUsesTopLeftAtZoneTwoBoundary() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                80,
                new int[]{10, 50, 90, 130, 170, 210, 230, 240},
                new int[]{200, 180, 160, 140, 120, 100, 80, 60}
        );

        predictor.predictChroma(plane, x, y, 4, 4, UvIntraPredictionMode.VERTICAL_RIGHT, 0);

        assertBlockEquals(
                plane,
                x,
                y,
                new int[][]{
                        {41, 33, 73, 113},
                        {69, 16, 56, 96},
                        {155, 30, 39, 79},
                        {188, 58, 23, 63}
                }
        );
    }

    /// Verifies that directional chroma prediction interpolates from the left edge in the steep-angle zone.
    @Test
    void directionalChromaPredictionInterpolatesFromLeftEdge() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 12, 8);
        int x = 3;
        int y = 3;
        seedDirectionalReferences(
                plane,
                x,
                y,
                71,
                new int[]{28, 52, 76, 109, 131, 158, 187, 213},
                new int[]{40, 69, 95, 121, 148, 176, 205, 233}
        );
        int[][] expected = DirectionalIntraPredictionOracle.predictChroma(
                plane,
                x,
                y,
                4,
                4,
                UvIntraPredictionMode.HORIZONTAL_UP,
                -2
        );

        predictor.predictChroma(plane, x, y, 4, 4, UvIntraPredictionMode.HORIZONTAL_UP, -2);

        assertBlockEquals(plane, x, y, expected);
    }

    /// Verifies that AV1 zone-2 directional prediction applies the corner filter before
    /// interpolation when edge filtering is enabled.
    @Test
    void directionalZoneTwoPredictionAppliesCornerFiltering() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(24, 24, 10);
        int x = 4;
        int y = 4;
        seedDirectionalReferences(
                plane,
                x,
                y,
                287,
                new int[]{291, 296, 297, 303, 305, 307, 310, 313},
                new int[]{287, 287, 287, 287, 280, 287, 287, 287, 286, 287, 287, 287, 286, 287, 287, 287}
        );

        predictor.predictLuma(plane, x, y, 8, 16, LumaIntraPredictionMode.VERTICAL, 1, true, false);

        assertEquals(291, plane.sample(x, y + 2));
    }

    /// Seeds the top-left, top-row, and left-column references used by one directional prediction test.
    ///
    /// @param plane the mutable plane to populate
    /// @param x the zero-based block origin X coordinate
    /// @param y the zero-based block origin Y coordinate
    /// @param topLeft the top-left reference sample
    /// @param top the top-row reference samples
    /// @param left the left-column reference samples
    private static void seedDirectionalReferences(
            MutablePlaneBuffer plane,
            int x,
            int y,
            int topLeft,
            int[] top,
            int[] left
    ) {
        plane.setSample(x - 1, y - 1, topLeft);
        for (int i = 0; i < top.length; i++) {
            plane.setSample(x + i, y - 1, top[i]);
        }
        for (int i = 0; i < left.length; i++) {
            plane.setSample(x - 1, y + i, left[i]);
        }
    }

    /// Returns the lazily allocated reference workspace of one predictor.
    ///
    /// @param predictor the predictor whose workspace should already be initialized
    /// @return the predictor-owned reference workspace
    private static Object predictionWorkspace(IntraPredictor predictor) {
        try {
            Field field = IntraPredictor.class.getDeclaredField("predictionWorkspace");
            field.setAccessible(true);
            return Objects.requireNonNull(field.get(predictor), "prediction workspace");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect the predictor workspace", exception);
        }
    }

    /// Asserts one rectangular block against expected sample values.
    ///
    /// @param plane the predicted plane
    /// @param x the block origin X coordinate in pixels
    /// @param y the block origin Y coordinate in pixels
    /// @param expected the expected samples in row-major order
    private static void assertBlockEquals(MutablePlaneBuffer plane, int x, int y, int[][] expected) {
        for (int row = 0; row < expected.length; row++) {
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(
                        expected[row][column],
                        plane.sample(x + column, y + row),
                        "Mismatch at relative row " + row + ", column " + column
                );
            }
        }
    }
}
