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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ShortBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the public visible-plane model.
@NotNullByDefault
final class DecodedPlanesTest {
    /// Verifies immutable unsigned sample access without exposing decoder padding operations.
    @Test
    void decodedPlaneExposesOnlyVisibleImmutableSamples() {
        short[] source = new short[]{1, (short) 0xFFFF, 3, 4, 5, 6};
        DecodedPlane plane = new DecodedPlane(2, 2, 3, source);
        source[0] = 9;

        assertEquals(1, plane.sample(0, 0));
        assertEquals(0xFFFF, plane.sample(1, 0));
        assertEquals(6, plane.sampleBuffer().remaining());
        assertArrayEquals(new short[]{1, (short) 0xFFFF, 3, 4, 5, 6}, plane.samples());
        assertFalse(Arrays.stream(DecodedPlane.class.getMethods())
                .anyMatch(method -> method.getName().equals("storedSample")
                        || method.getName().equals("storageHeight")));
    }

    /// Verifies typed bit depth and chroma-plane dimension validation.
    @Test
    void decodedPlanesValidateTypedPlaneLayout() {
        DecodedPlane luma = filledPlane(5, 3, (short) 1);
        DecodedPlane chromaU = filledPlane(3, 2, (short) 2);
        DecodedPlane chromaV = filledPlane(3, 2, (short) 3);
        DecodedPlanes planes = new DecodedPlanes(
                AvifBitDepth.TEN_BITS,
                Av1ChromaFormat.YUV420,
                5,
                3,
                5,
                3,
                luma,
                chromaU,
                chromaV
        );

        assertEquals(AvifBitDepth.TEN_BITS, planes.bitDepth());
        assertTrue(planes.hasChroma());
        assertThrows(IllegalArgumentException.class, () -> new DecodedPlanes(
                AvifBitDepth.TEN_BITS,
                Av1ChromaFormat.YUV420,
                5,
                3,
                5,
                3,
                luma,
                filledPlane(2, 2, (short) 2),
                chromaV
        ));
    }

    /// Creates one tightly packed plane filled with a repeated value.
    ///
    /// @param width the plane width
    /// @param height the plane height
    /// @param value the repeated sample value
    /// @return the filled plane
    private static DecodedPlane filledPlane(int width, int height, short value) {
        short[] samples = new short[width * height];
        Arrays.fill(samples, value);
        return new DecodedPlane(width, height, width, ShortBuffer.wrap(samples).asReadOnlyBuffer());
    }
}
