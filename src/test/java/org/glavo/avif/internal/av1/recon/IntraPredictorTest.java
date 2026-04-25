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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the currently supported intra-prediction paths.
@NotNullByDefault
final class IntraPredictorTest {
    /// Verifies that DC prediction averages the available top and left reference samples.
    @Test
    void dcPredictionAveragesAvailableTopAndLeftNeighbors() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        plane.setSample(0, 0, 99);
        plane.setSample(1, 0, 10);
        plane.setSample(2, 0, 20);
        plane.setSample(0, 1, 30);
        plane.setSample(0, 2, 40);

        IntraPredictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.DC, 0);

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

    /// Verifies that vertical prediction copies the top edge into every output row.
    @Test
    void verticalPredictionRepeatsTopReferenceRow() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(6, 4, 8);
        plane.setSample(1, 0, 11);
        plane.setSample(2, 0, 22);
        plane.setSample(3, 0, 33);

        IntraPredictor.predictLuma(plane, 1, 1, 3, 2, LumaIntraPredictionMode.VERTICAL, 0);

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

        IntraPredictor.predictLuma(plane, 1, 0, 3, 2, LumaIntraPredictionMode.VERTICAL, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 2, 3, LumaIntraPredictionMode.HORIZONTAL, 0);

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

        IntraPredictor.predictLuma(plane, 0, 1, 2, 3, LumaIntraPredictionMode.HORIZONTAL, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.PAETH, 0);

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

        IntraPredictor.predictLuma(plane, 0, 1, 3, 2, LumaIntraPredictionMode.PAETH, 0);

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

        IntraPredictor.predictLuma(plane, 1, 0, 2, 2, LumaIntraPredictionMode.PAETH, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 2, 2, LumaIntraPredictionMode.SMOOTH, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH_VERTICAL, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 3, 3, LumaIntraPredictionMode.SMOOTH_HORIZONTAL, 0);

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

        IntraPredictor.predictLuma(plane, 1, 1, 128, 128, LumaIntraPredictionMode.SMOOTH, 0);

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

        IntraPredictor.predictFilterIntraLuma(plane, 1, 1, 4, 4, FilterIntraMode.DC);

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

        IntraPredictor.predictFilterIntraLuma(plane, 1, 1, 3, 3, FilterIntraMode.DC);

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

        IntraPredictor.predictFilterIntraLuma(plane, 1, 0, 2, 2, FilterIntraMode.DC);

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

        IntraPredictor.predictFilterIntraLuma(plane, 0, 1, 2, 2, FilterIntraMode.DC);

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
        IntraPredictor.predictChromaCflI420(chromaPlane, lumaPlane, 0, 0, 0, 0, 2, 2, 4);

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
        IntraPredictor.predictChromaCfl(chromaPlane, lumaPlane, 0, 0, 0, 0, 2, 4, 4, 1, 0);

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
        IntraPredictor.predictChromaCfl(chromaPlane, lumaPlane, 0, 0, 0, 0, 4, 4, 4, 0, 0);

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

        IntraPredictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, 1);

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

        IntraPredictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, -1);

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

        IntraPredictor.predictLuma(plane, x, y, 8, 8, LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT, 0, true, false);

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

        IntraPredictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.VERTICAL_LEFT, 0, true, false);

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

        IntraPredictor.predictLuma(plane, x, y, 4, 4, LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT, 0);

        assertBlockEquals(plane, x, y, expected);
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

        IntraPredictor.predictChroma(plane, x, y, 4, 4, UvIntraPredictionMode.HORIZONTAL_UP, -2);

        assertBlockEquals(plane, x, y, expected);
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
