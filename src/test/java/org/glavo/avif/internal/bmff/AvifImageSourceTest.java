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
package org.glavo.avif.internal.bmff;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests normalized AVIF image-source storage and geometry validation.
@NotNullByDefault
final class AvifImageSourceTest {
    /// Verifies that a standalone source owns its payload and returns independent read-only views.
    @Test
    void itemDefensivelyCopiesPayload() throws IOException {
        byte[] payload = {1, 2, 3};
        AvifImageSource source = AvifImageSource.item(payload, 7, 16, 9);
        payload[0] = 99;

        ByteBuffer firstView = source.payload(0).readBuffer();
        assertTrue(firstView.isReadOnly());
        assertEquals(ByteOrder.LITTLE_ENDIAN, firstView.order());
        assertEquals(1, firstView.get(0));
        assertThrows(ReadOnlyBufferException.class, () -> firstView.put(0, (byte) 4));

        firstView.position(2);
        assertEquals(0, source.payload(0).readBuffer().position());
        assertFalse(source.isGrid());
        assertEquals(1, source.payloadCount());
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
        byte[][] payloads = {{1}, {2}, {3}, {4}};
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
        payloads[1][0] = 99;
        operatingPoints[1] = 17;
        selectedSpatialLayers[1] = 3;
        cellWidths[1] = 99;
        cellHeights[1] = 99;

        assertTrue(source.isGrid());
        assertEquals(4, source.payloadCount());
        assertEquals(2, source.payload(1).readBuffer().get(0));
        assertEquals(1, source.operatingPoint(1));
        assertArrayEquals(new int[]{0, 1, 2, 3}, source.operatingPoints());
        assertEquals(0, source.selectedSpatialLayer(1));
        assertArrayEquals(new int[]{-1, 0, 1, 3}, source.selectedSpatialLayers());
        assertEquals(16, source.itemWidth(1));
        assertEquals(9, source.itemHeight(1));
        assertArrayEquals(new int[]{16, 16, 16, 16}, source.itemWidths());
        assertArrayEquals(new int[]{9, 9, 9, 9}, source.itemHeights());
        assertEquals(2, source.rows());
        assertEquals(2, source.columns());
        assertEquals(31, source.outputWidth());
        assertEquals(17, source.outputHeight());

        AvifPayload[] views = source.payloads();
        views[0] = AvifPayload.copyOf(new byte[0]);
        assertEquals(1, source.payload(0).length());
    }

    /// Verifies rejection of inconsistent geometry, payload counts, and operating-point values.
    @Test
    void rejectsInvalidSourceDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(new byte[]{1}, -1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(new byte[]{1}, 32, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(new byte[]{1}, 0, -2, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(new byte[]{1}, 0, 4, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.item(new byte[]{1}, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AvifImageSource.grid(
                        new byte[][]{{1}},
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
                        new byte[][]{{1}, {2}},
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
                        new byte[][]{{1}, {2}},
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
                        new byte[][]{{1}},
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
}
