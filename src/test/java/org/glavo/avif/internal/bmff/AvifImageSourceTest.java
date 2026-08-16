// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff;

import org.glavo.avif.internal.io.AvifDataSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests normalized AVIF image-source storage and geometry validation.
@NotNullByDefault
final class AvifImageSourceTest {
    /// Verifies that a standalone source retains its immutable payload descriptor and geometry.
    @Test
    void itemRetainsPayloadDescriptor() throws IOException {
        AvifPayload payload = payload(1, 2, 3);
        AvifImageSource source = AvifImageSource.item(
                payload,
                7,
                AvifImageSource.HIGHEST_SPATIAL_LAYER,
                16,
                9
        );

        assertSame(payload, source.payload(0));
        assertArrayEquals(new byte[]{1, 2, 3}, source.payload(0).readBytes());
        assertFalse(source.isGrid());
        assertEquals(7, source.operatingPoint(0));
        assertEquals(AvifImageSource.HIGHEST_SPATIAL_LAYER, source.selectedSpatialLayer(0));
        assertEquals(1, source.rows());
        assertEquals(1, source.columns());
        assertEquals(16, source.outputWidth());
        assertEquals(9, source.outputHeight());
    }

    /// Verifies that a grid source retains per-cell selections without aliasing input arrays.
    @Test
    void gridDefensivelyCopiesPayloadsAndOperatingPoints() throws IOException {
        AvifPayload[] payloads = {payload(1), payload(2), payload(3), payload(4)};
        AvifPayload secondPayload = payloads[1];
        int[] operatingPoints = {0, 1, 2, 3};
        int[] selectedSpatialLayers = {
                AvifImageSource.HIGHEST_SPATIAL_LAYER,
                0,
                1,
                3
        };
        int[] cellWidths = {16, 16, 16, 16};
        int[] cellHeights = {9, 9, 9, 9};
        AvifImageSource source = AvifImageSource.grid(
                payloads,
                operatingPoints,
                selectedSpatialLayers,
                cellWidths,
                cellHeights,
                2,
                2,
                31,
                17
        );
        payloads[1] = payload(99);
        operatingPoints[1] = 17;
        selectedSpatialLayers[1] = 3;
        cellWidths[1] = 99;
        cellHeights[1] = 99;

        assertTrue(source.isGrid());
        assertSame(secondPayload, source.payload(1));
        assertArrayEquals(new byte[]{2}, source.payload(1).readBytes());
        assertEquals(1, source.operatingPoint(1));
        assertEquals(0, source.selectedSpatialLayer(1));
        assertEquals(16, source.itemWidth(1));
        assertEquals(9, source.itemHeight(1));
        int[] expectedSpatialLayers = {-1, 0, 1, 3};
        for (int index = 0; index < 4; index++) {
            assertEquals(index, source.operatingPoint(index));
            assertEquals(expectedSpatialLayers[index], source.selectedSpatialLayer(index));
            assertEquals(16, source.itemWidth(index));
            assertEquals(9, source.itemHeight(index));
        }
        assertEquals(2, source.rows());
        assertEquals(2, source.columns());
        assertEquals(31, source.outputWidth());
        assertEquals(17, source.outputHeight());

        AvifPayload[] views = source.payloads();
        views[0] = payload();
        assertEquals(1, source.payload(0).length());
    }

    /// Verifies rejection of inconsistent geometry, payload counts, and operating-point values.
    @Test
    void rejectsInvalidSourceDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(payload(1), -1, AvifImageSource.HIGHEST_SPATIAL_LAYER, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(payload(1), 32, AvifImageSource.HIGHEST_SPATIAL_LAYER, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(payload(1), 0, -2, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(payload(1), 0, 4, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(payload(1), 0, AvifImageSource.HIGHEST_SPATIAL_LAYER, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.grid(
                        new AvifPayload[]{payload(1)},
                        new int[]{0},
                        new int[]{0},
                        new int[]{1},
                        new int[]{1},
                        1,
                        2,
                        1,
                        1
                ));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.grid(
                        new AvifPayload[]{payload(1), payload(2)},
                        new int[]{0},
                        new int[]{0, 0},
                        new int[]{1, 1},
                        new int[]{1, 1},
                        1,
                        2,
                        2,
                        1
                ));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.grid(
                        new AvifPayload[]{payload(1), payload(2)},
                        new int[]{0, 0},
                        new int[]{0},
                        new int[]{1, 1},
                        new int[]{1, 1},
                        1,
                        2,
                        2,
                        1
                ));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.grid(
                        new AvifPayload[]{payload(1)},
                        new int[]{0},
                        new int[]{0},
                        new int[]{0},
                        new int[]{1},
                        1,
                        1,
                        1,
                        1
                ));
    }

    /// Creates one immutable payload descriptor backed by test-owned memory.
    ///
    /// @param values the unsigned byte values
    /// @return the payload descriptor
    private static AvifPayload payload(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return AvifPayload.ofRanges(AvifDataSource.ofBytes(bytes), new long[]{0L}, new int[]{bytes.length});
    }
}
