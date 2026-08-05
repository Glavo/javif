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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for mutable reconstruction-plane storage.
@NotNullByDefault
final class MutablePlaneBufferTest {
    /// Verifies that mutable plane storage clips writes, serves fallbacks, and snapshots immutably.
    @Test
    void mutablePlaneBufferClipsWritesAndSnapshotsIntoDecodedPlane() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(2, 2, 8);

        plane.setSample(0, 0, -5);
        plane.setSample(1, 0, 999);
        plane.setSample(0, 1, 42);
        plane.setSample(1, 1, 13);

        assertEquals(0, plane.sample(0, 0));
        assertEquals(255, plane.sample(1, 0));
        assertEquals(42, plane.sample(0, 1));
        assertEquals(77, plane.sampleOrFallback(-1, 0, 77));
        assertEquals(77, plane.sampleOrFallback(0, 3, 77));

        DecodedPlane snapshot = plane.toDecodedPlane();
        plane.setSample(0, 1, 1);

        assertEquals(2, snapshot.width());
        assertEquals(2, snapshot.height());
        assertEquals(42, snapshot.sample(0, 1));
        assertEquals(13, snapshot.sample(1, 1));
    }

    /// Verifies frame-relative access to compact retained subregion storage.
    @Test
    void retainsCoordinatePreservingSubregion() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 10, 8, 4, 2, 3, 4);

        assertEquals(12, plane.width());
        assertEquals(10, plane.height());
        plane.setSample(4, 2, 11);
        plane.setSample(6, 5, 22);

        assertEquals(11, plane.sample(4, 2));
        assertEquals(22, plane.sample(6, 5));
        assertEquals(77, plane.sampleOrFallback(3, 2, 77));
        assertThrows(IndexOutOfBoundsException.class, () -> plane.sample(7, 2));
        assertThrows(IllegalStateException.class, plane::toDecodedPlane);

        DecodedPlane retained = plane.takeStoredDecodedPlane(3, 4);
        assertEquals(3, retained.width());
        assertEquals(4, retained.height());
        assertEquals(11, retained.sample(0, 0));
        assertEquals(22, retained.sample(2, 3));
    }

    /// Verifies that a block overlay reads through unwritten samples and isolates compact writes.
    @Test
    void blockOverlayReadsThroughBasePlaneAndStoresOnlyItsBlock() {
        MutablePlaneBuffer basePlane = new MutablePlaneBuffer(4, 4, 8);
        for (int y = 0; y < basePlane.height(); y++) {
            for (int x = 0; x < basePlane.width(); x++) {
                basePlane.setSample(x, y, y * 10 + x);
            }
        }
        BlockOverlayPlane overlay = new BlockOverlayPlane(basePlane, 1, 1, 2, 2);

        assertEquals(0, overlay.sample(0, 0));
        assertEquals(11, overlay.sample(1, 1));
        overlay.setSample(1, 1, 300);
        overlay.setSample(2, 1, overlay.sample(1, 1) - 200);

        assertEquals(255, overlay.sample(1, 1));
        assertEquals(55, overlay.sample(2, 1));
        assertEquals(11, basePlane.sample(1, 1));
        assertEquals(12, basePlane.sample(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> overlay.setSample(0, 1, 1));

        BlockOverlayPlane clippedEdgeOverlay = new BlockOverlayPlane(basePlane, 3, 3, 4, 4);
        clippedEdgeOverlay.setSample(3, 3, 77);
        assertEquals(77, clippedEdgeOverlay.sample(3, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> clippedEdgeOverlay.setSample(4, 3, 1));
    }

    /// Verifies that recursive filter-intra reads samples already written into a block overlay.
    @Test
    void blockOverlaySupportsRecursiveFilterIntraPrediction() {
        MutablePlaneBuffer basePlane = new MutablePlaneBuffer(8, 8, 8);
        for (int y = 0; y < basePlane.height(); y++) {
            for (int x = 0; x < basePlane.width(); x++) {
                basePlane.setSample(x, y, 20 + y * 9 + x * 3);
            }
        }
        MutablePlaneBuffer expectedPlane = basePlane.copy();
        BlockOverlayPlane overlay = new BlockOverlayPlane(basePlane, 2, 2, 4, 4);

        IntraPredictor.predictFilterIntraLuma(expectedPlane, 2, 2, 4, 4, FilterIntraMode.PAETH);
        IntraPredictor.predictFilterIntraLuma(overlay, 2, 2, 4, 4, FilterIntraMode.PAETH);

        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                assertEquals(expectedPlane.sample(x, y), overlay.sample(x, y));
                assertEquals(20 + y * 9 + x * 3, basePlane.sample(x, y));
            }
        }
    }
}
