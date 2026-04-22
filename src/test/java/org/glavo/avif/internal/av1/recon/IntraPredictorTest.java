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
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for the first supported intra-prediction subset.
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
                        {61, 61, 63, 75},
                        {26, 36, 47, 66},
                        {80, 81, 90, 100},
                        {36, 50, 68, 89}
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

    /// Verifies that directional chroma modes remain unsupported outside the newly added CFL path.
    @Test
    void chromaPredictionStillRejectsDirectionalModes() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(2, 2, 8);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> IntraPredictor.predictChroma(plane, 0, 0, 2, 2, UvIntraPredictionMode.VERTICAL_LEFT, 0)
        );

        assertEquals("Directional intra prediction is not implemented yet: VERTICAL_LEFT angle_delta=0", exception.getMessage());
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
                assertEquals(expected[row][column], plane.sample(x + column, y + row));
            }
        }
    }
}
