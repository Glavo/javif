// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
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

/// Tests for the public AV1 visible-plane model.
@NotNullByDefault
final class Av1DecodedPlanesTest {
    /// Verifies immutable unsigned sample access without exposing decoder padding operations.
    @Test
    void decodedPlaneExposesOnlyVisibleImmutableSamples() {
        short[] source = new short[]{1, (short) 0xFFFF, 3, 4, 5, 6};
        Av1DecodedPlane plane = new Av1DecodedPlane(2, 2, 3, source);
        source[0] = 9;

        assertEquals(1, plane.sample(0, 0));
        assertEquals(0xFFFF, plane.sample(1, 0));
        assertEquals(6, plane.sampleBuffer().remaining());
        assertArrayEquals(new short[]{1, (short) 0xFFFF, 3, 4, 5, 6}, plane.samples());
        assertFalse(Arrays.stream(Av1DecodedPlane.class.getMethods())
                .anyMatch(method -> method.getName().equals("storedSample")
                        || method.getName().equals("storageHeight")));
    }

    /// Verifies typed bit depth and chroma-plane dimension validation.
    @Test
    void decodedPlanesValidateTypedPlaneLayout() {
        Av1DecodedPlane luma = filledPlane(5, 3, (short) 1);
        Av1DecodedPlane chromaU = filledPlane(3, 2, (short) 2);
        Av1DecodedPlane chromaV = filledPlane(3, 2, (short) 3);
        Av1DecodedPlanes planes = new Av1DecodedPlanes(
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
        assertThrows(IllegalArgumentException.class, () -> new Av1DecodedPlanes(
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
    private static Av1DecodedPlane filledPlane(int width, int height, short value) {
        short[] samples = new short[width * height];
        Arrays.fill(samples, value);
        return new Av1DecodedPlane(width, height, width, ShortBuffer.wrap(samples).asReadOnlyBuffer());
    }
}
