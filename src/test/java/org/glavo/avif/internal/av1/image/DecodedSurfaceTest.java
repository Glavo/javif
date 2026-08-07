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
package org.glavo.avif.internal.av1.image;

import org.glavo.avif.Av1ChromaFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for immutable internal padded-surface contracts.
@NotNullByDefault
final class DecodedSurfaceTest {
    /// Verifies that one decoded plane reads unsigned samples and defensively copies stored data.
    @Test
    void decodedPlaneReadsUnsignedSamplesAndCopiesStorage() {
        short[] source = new short[]{1, (short) 0xFFFF, 3, 4};
        PaddedPlane plane = new PaddedPlane(2, 2, 2, source);

        source[0] = 9;

        assertEquals(1, plane.sample(0, 0));
        assertEquals(0xFFFF, plane.sample(1, 0));
        short[] exported = plane.samples();
        exported[1] = 0;
        assertArrayEquals(new short[]{1, (short) 0xFFFF, 3, 4}, plane.samples());
    }

    /// Verifies that stored-plane reads use the row stride across right and bottom padding.
    @Test
    void decodedPlaneReadsPaddedStorageByStride() {
        PaddedPlane plane = new PaddedPlane(
                2,
                2,
                3,
                new short[]{10, 11, 90, 20, 21, 91, 30, 31, 92}
        );

        assertEquals(3, plane.storageHeight());
        assertEquals(20, plane.storedSample(0, 1));
        assertEquals(91, plane.storedSample(2, 1));
        assertEquals(92, plane.storedSample(2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> plane.storedSample(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> plane.storedSample(0, 3));
    }

    /// Verifies that monochrome decoded planes reject unexpected chroma storage.
    @Test
    void monochromeDecodedPlanesRejectUnexpectedChroma() {
        PaddedPlane luma = new PaddedPlane(4, 4, 4, filledSamples(16, (short) 7));
        PaddedPlane chroma = new PaddedPlane(2, 2, 2, filledSamples(4, (short) 3));

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodedSurface(8, Av1ChromaFormat.MONOCHROME, 4, 4, 4, 4, luma, chroma, null)
        );
    }

    /// Verifies that `YUV420` decoded planes validate subsampled chroma dimensions.
    @Test
    void i420DecodedPlanesValidateChromaDimensions() {
        PaddedPlane luma = new PaddedPlane(5, 3, 5, filledSamples(15, (short) 1));
        PaddedPlane chromaU = new PaddedPlane(3, 2, 3, filledSamples(6, (short) 2));
        PaddedPlane chromaV = new PaddedPlane(3, 2, 3, filledSamples(6, (short) 3));

        DecodedSurface planes = new DecodedSurface(8, Av1ChromaFormat.YUV420, 5, 3, 5, 3, luma, chromaU, chromaV);

        assertTrue(planes.hasChroma());
        assertEquals(5, planes.codedWidth());
        assertEquals(3, planes.codedHeight());
        assertEquals(5, planes.renderWidth());
        assertEquals(3, planes.renderHeight());
        assertEquals(3, planes.chromaUPlane().width());
        assertEquals(2, planes.chromaUPlane().height());

        PaddedPlane wrongChroma = new PaddedPlane(2, 2, 2, filledSamples(4, (short) 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecodedSurface(8, Av1ChromaFormat.YUV420, 5, 3, 5, 3, luma, wrongChroma, chromaV)
        );
    }

    /// Verifies that monochrome decoded planes report the expected chroma absence.
    @Test
    void monochromeDecodedPlanesDoNotReportChroma() {
        PaddedPlane luma = new PaddedPlane(4, 4, 4, filledSamples(16, (short) 5));

        DecodedSurface planes = new DecodedSurface(10, Av1ChromaFormat.MONOCHROME, 4, 4, 4, 4, luma, null, null);

        assertFalse(planes.hasChroma());
        assertEquals(Av1ChromaFormat.MONOCHROME, planes.chromaFormat());
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
