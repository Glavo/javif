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

import org.glavo.avif.AvifPixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for immutable decoded-plane contracts.
@NotNullByDefault
final class DecodedPlanesTest {
    /// Verifies that one decoded plane reads unsigned samples and defensively copies stored data.
    @Test
    void decodedPlaneReadsUnsignedSamplesAndCopiesStorage() {
        short[] source = new short[]{1, (short) 0xFFFF, 3, 4};
        DecodedPlane plane = new DecodedPlane(2, 2, 2, source);

        source[0] = 9;

        assertEquals(1, plane.sample(0, 0));
        assertEquals(0xFFFF, plane.sample(1, 0));
        short[] exported = plane.samples();
        exported[1] = 0;
        assertArrayEquals(new short[]{1, (short) 0xFFFF, 3, 4}, plane.samples());
    }

    /// Verifies that monochrome decoded planes reject unexpected chroma storage.
    @Test
    void monochromeDecodedPlanesRejectUnexpectedChroma() {
        DecodedPlane luma = new DecodedPlane(4, 4, 4, filledSamples(16, (short) 7));
        DecodedPlane chroma = new DecodedPlane(2, 2, 2, filledSamples(4, (short) 3));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodedPlanes(8, AvifPixelFormat.I400, 4, 4, 4, 4, luma, chroma, null)
        );
    }

    /// Verifies that `I420` decoded planes validate subsampled chroma dimensions.
    @Test
    void i420DecodedPlanesValidateChromaDimensions() {
        DecodedPlane luma = new DecodedPlane(5, 3, 5, filledSamples(15, (short) 1));
        DecodedPlane chromaU = new DecodedPlane(3, 2, 3, filledSamples(6, (short) 2));
        DecodedPlane chromaV = new DecodedPlane(3, 2, 3, filledSamples(6, (short) 3));

        DecodedPlanes planes = new DecodedPlanes(8, AvifPixelFormat.I420, 5, 3, 5, 3, luma, chromaU, chromaV);

        assertTrue(planes.hasChroma());
        assertEquals(5, planes.codedWidth());
        assertEquals(3, planes.codedHeight());
        assertEquals(5, planes.renderWidth());
        assertEquals(3, planes.renderHeight());
        assertEquals(3, planes.chromaUPlane().width());
        assertEquals(2, planes.chromaUPlane().height());

        DecodedPlane wrongChroma = new DecodedPlane(2, 2, 2, filledSamples(4, (short) 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodedPlanes(8, AvifPixelFormat.I420, 5, 3, 5, 3, luma, wrongChroma, chromaV)
        );
    }

    /// Verifies that monochrome decoded planes report the expected chroma absence.
    @Test
    void monochromeDecodedPlanesDoNotReportChroma() {
        DecodedPlane luma = new DecodedPlane(4, 4, 4, filledSamples(16, (short) 5));

        DecodedPlanes planes = new DecodedPlanes(10, AvifPixelFormat.I400, 4, 4, 4, 4, luma, null, null);

        assertFalse(planes.hasChroma());
        assertEquals(AvifPixelFormat.I400, planes.pixelFormat());
        assertEquals(10, planes.bitDepth());
    }

    /// Creates a filled sample array for tests.
    ///
    /// @param length the required sample-array length
    /// @param value the repeated sample value
    /// @return a filled sample array for tests
    private static short[] filledSamples(int length, short value) {
        short[] samples = new short[length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = value;
        }
        return samples;
    }
}
