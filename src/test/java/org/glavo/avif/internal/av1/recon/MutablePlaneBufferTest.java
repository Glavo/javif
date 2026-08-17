// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.image.PaddedPlane;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        PaddedPlane snapshot = plane.toDecodedPlane();
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

        PaddedPlane retained = plane.takeStoredDecodedPlane(3, 4);
        assertEquals(3, retained.width());
        assertEquals(4, retained.height());
        assertEquals(11, retained.sample(0, 0));
        assertEquals(22, retained.sample(2, 3));
    }

    /// Verifies clipped constant block addition within coordinate-preserving subregion storage.
    @Test
    void addsConstantBlockWithinRetainedSubregion() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 10, 8, 4, 2, 4, 3);
        for (int y = 2; y < 5; y++) {
            for (int x = 4; x < 8; x++) {
                plane.setSample(x, y, x == 6 ? 250 : 20);
            }
        }

        plane.addConstantBlock(5, 3, 2, 2, 10);

        assertEquals(20, plane.sample(4, 3));
        assertEquals(30, plane.sample(5, 3));
        assertEquals(255, plane.sample(6, 3));
        assertEquals(30, plane.sample(5, 4));
        assertTrue(plane.hasWrittenSample(6, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> plane.addConstantBlock(7, 3, 2, 1, 1));

        MutablePlaneBuffer widePlane = new MutablePlaneBuffer(80, 1, 8);
        widePlane.addConstantBlock(5, 0, 70, 1, 10);
        assertFalse(widePlane.hasWrittenSample(4, 0));
        assertTrue(widePlane.hasWrittenSample(5, 0));
        assertTrue(widePlane.hasWrittenSample(63, 0));
        assertTrue(widePlane.hasWrittenSample(64, 0));
        assertTrue(widePlane.hasWrittenSample(74, 0));
        assertFalse(widePlane.hasWrittenSample(75, 0));
        assertEquals(10, widePlane.sample(74, 0));
    }

    /// Verifies constant fills clip at visible edges while retaining compact-storage boundaries.
    @Test
    void fillsVisiblePlaneEdgeWithinRetainedSubregion() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(12, 10, 8, 8, 7, 4, 3);

        plane.fillBlock(9, 8, 8, 4, 300);

        assertEquals(255, plane.sample(9, 8));
        assertEquals(255, plane.sample(11, 9));
        assertTrue(plane.hasWrittenSample(9, 8));
        assertTrue(plane.hasWrittenSample(11, 9));
        assertFalse(plane.hasWrittenSample(8, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> plane.fillBlock(7, 8, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> plane.fillBlock(9, 8, 0, 1, 1));
    }

    /// Verifies edge-extended block copies write compact retained storage and written-state ranges.
    @Test
    void copiesExtendedReferenceBlockIntoRetainedSubregion() {
        PaddedPlane source = new PaddedPlane(3, 2, 3, new short[]{10, 11, 12, 20, 21, 22});
        MutablePlaneBuffer destination = new MutablePlaneBuffer(12, 10, 8, 4, 2, 4, 3);

        destination.copyExtendedBlockFrom(source, 5, 3, -1, 1, 3, 2);

        assertEquals(20, destination.sample(5, 3));
        assertEquals(20, destination.sample(6, 3));
        assertEquals(21, destination.sample(7, 3));
        assertEquals(20, destination.sample(5, 4));
        assertEquals(21, destination.sample(7, 4));
        assertTrue(destination.hasWrittenSample(5, 3));
        assertTrue(destination.hasWrittenSample(7, 4));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> destination.copyExtendedBlockFrom(source, 7, 3, 0, 0, 2, 1)
        );

        MutablePlaneBuffer wideDestination = new MutablePlaneBuffer(80, 2, 8);
        PaddedPlane wideSource = new PaddedPlane(70, 1, 70, new short[70]);
        wideDestination.copyExtendedBlockFrom(wideSource, 5, 0, 0, 0, 70, 1);
        assertFalse(wideDestination.hasWrittenSample(4, 0));
        assertTrue(wideDestination.hasWrittenSample(5, 0));
        assertTrue(wideDestination.hasWrittenSample(63, 0));
        assertTrue(wideDestination.hasWrittenSample(64, 0));
        assertTrue(wideDestination.hasWrittenSample(74, 0));
        assertFalse(wideDestination.hasWrittenSample(75, 0));
    }

    /// Verifies written-state tracking and copying across packed-word boundaries.
    @Test
    void tracksWrittenSamplesAcrossPackedWordBoundaries() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(65, 2, 8);

        plane.setSample(63, 0, 11);
        plane.setSample(64, 0, 22);
        plane.setSample(64, 1, 33);

        assertFalse(plane.hasWrittenSample(62, 0));
        assertTrue(plane.hasWrittenSample(63, 0));
        assertTrue(plane.hasWrittenSample(64, 0));
        assertFalse(plane.hasWrittenSample(0, 1));
        assertTrue(plane.hasWrittenSample(64, 1));

        MutablePlaneBuffer copy = plane.copy();
        assertTrue(copy.hasWrittenSample(63, 0));
        assertTrue(copy.hasWrittenSample(64, 0));
        assertTrue(copy.hasWrittenSample(64, 1));
        assertEquals(33, copy.sample(64, 1));
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

    /// Verifies overlay read-through and writes across packed-word boundaries.
    @Test
    void blockOverlayTracksWritesAcrossPackedWordBoundaries() {
        MutablePlaneBuffer basePlane = new MutablePlaneBuffer(9, 8, 8);
        for (int y = 0; y < basePlane.height(); y++) {
            for (int x = 0; x < basePlane.width(); x++) {
                basePlane.setSample(x, y, 10);
            }
        }
        BlockOverlayPlane overlay = new BlockOverlayPlane(basePlane, 0, 0, 9, 8);

        overlay.setSample(0, 7, 63);
        overlay.setSample(1, 7, 64);

        assertEquals(10, overlay.sample(8, 6));
        assertEquals(63, overlay.sample(0, 7));
        assertEquals(64, overlay.sample(1, 7));
        assertEquals(10, overlay.sample(2, 7));
    }

    /// Verifies overlay fills clip to the containing plane and isolate their packed write ranges.
    @Test
    void blockOverlayFillsVisibleEdgeWithoutChangingBasePlane() {
        MutablePlaneBuffer basePlane = new MutablePlaneBuffer(9, 8, 8);
        basePlane.fillBlock(0, 0, 9, 8, 10);
        BlockOverlayPlane overlay = new BlockOverlayPlane(basePlane, 0, 0, 9, 8);

        overlay.fillBlock(1, 6, 20, 4, -3);

        assertEquals(10, overlay.sample(0, 6));
        assertEquals(0, overlay.sample(1, 6));
        assertEquals(0, overlay.sample(8, 7));
        assertEquals(10, basePlane.sample(1, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> overlay.fillBlock(9, 7, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> overlay.fillBlock(1, 6, 1, -1, 1));
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

        new IntraPredictor().predictFilterIntraLuma(expectedPlane, 2, 2, 4, 4, FilterIntraMode.PAETH);
        new IntraPredictor().predictFilterIntraLuma(overlay, 2, 2, 4, 4, FilterIntraMode.PAETH);

        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                assertEquals(expectedPlane.sample(x, y), overlay.sample(x, y));
                assertEquals(20 + y * 9 + x * 3, basePlane.sample(x, y));
            }
        }
    }
}
