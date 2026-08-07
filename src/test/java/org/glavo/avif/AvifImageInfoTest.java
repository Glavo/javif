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

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the composed `AvifImageInfo` metadata model.
@NotNullByDefault
final class AvifImageInfoTest {
    /// Verifies still-image defaults and defensive copying of embedded payloads.
    @Test
    void stillImageUsesCanonicalDefaultsAndCopiesPayloads() {
        byte[] iccProfile = new byte[]{1, 2, 3};
        byte[] exif = new byte[]{4, 5};
        byte[] xmp = new byte[]{6};
        AvifImageInfo info = new AvifImageInfo(
                2,
                3,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.YUV420,
                null,
                null,
                null,
                null,
                false,
                true,
                null,
                null,
                iccProfile,
                exif,
                xmp,
                null
        );
        iccProfile[0] = 10;
        exif[0] = 11;
        xmp[0] = 12;

        assertEquals(2, info.width());
        assertEquals(3, info.height());
        assertFalse(info.animated());
        assertEquals(1, info.frameCount());
        assertEquals(0, info.mediaTimescale());
        assertEquals(0, info.mediaDuration());
        assertEquals(AvifImageInfo.REPETITION_COUNT_UNKNOWN, info.repetitionCount());
        assertArrayEquals(new int[0], info.frameDurations());
        assertNull(info.sequenceInfo());
        assertNull(info.transformInfo());
        assertFalse(info.alphaPresent());
        assertFalse(info.alphaPremultiplied());
        ByteBuffer returnedIccProfile = info.iccProfile();
        ByteBuffer returnedExif = info.exif();
        ByteBuffer returnedXmp = info.xmp();
        assertNotNull(returnedIccProfile);
        assertNotNull(returnedExif);
        assertNotNull(returnedXmp);
        assertArrayEquals(new byte[]{1, 2, 3}, bytes(returnedIccProfile));
        assertArrayEquals(new byte[]{4, 5}, bytes(returnedExif));
        assertArrayEquals(new byte[]{6}, bytes(returnedXmp));
    }

    /// Verifies that legacy sequence queries delegate to the typed sequence descriptor.
    @Test
    void sequenceAccessorsDelegateToSequenceDescriptor() {
        int[] durations = new int[]{20, 30};
        AvifSequenceInfo sequenceInfo = new AvifSequenceInfo(2, 1_000, 50, 3, durations);
        AvifImageInfo info = new AvifImageInfo(
                4,
                5,
                AvifBitDepth.TEN_BITS,
                Av1ChromaFormat.YUV444,
                sequenceInfo,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
        durations[0] = 99;

        assertTrue(info.animated());
        assertSame(sequenceInfo, info.sequenceInfo());
        assertEquals(2, info.frameCount());
        assertEquals(1_000, info.mediaTimescale());
        assertEquals(50, info.mediaDuration());
        assertEquals(3, info.repetitionCount());
        int[] returnedDurations = info.frameDurations();
        assertArrayEquals(new int[]{20, 30}, returnedDurations);
        returnedDurations[0] = 100;
        assertArrayEquals(new int[]{20, 30}, info.frameDurations());
    }

    /// Verifies alpha state that cannot be represented by a direct auxiliary image descriptor.
    @Test
    void explicitAlphaPresenceDoesNotRequireAuxiliaryDescriptor() {
        AvifImageInfo info = new AvifImageInfo(
                2,
                3,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.YUV420,
                null,
                null,
                null,
                null,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertTrue(info.alphaPresent());
        assertTrue(info.alphaPremultiplied());
        assertArrayEquals(new String[0], info.auxiliaryImageTypes());
        assertArrayEquals(new AvifAuxiliaryImageInfo[0], info.auxiliaryImages());
    }

    /// Verifies typed transform delegation and alpha detection from auxiliary descriptors.
    @Test
    void transformAndAuxiliaryAccessorsDelegateToTypedDescriptors() {
        AvifImageTransformInfo transformInfo = new AvifImageTransformInfo(1, 2, 3, 4, 1, 0);
        AvifAuxiliaryImageInfo alphaInfo = new AvifAuxiliaryImageInfo(
                7,
                AvifAuxiliaryImageInfo.ALPHA_TYPE,
                "av01",
                8,
                9,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.MONOCHROME
        );
        AvifAuxiliaryImageInfo[] auxiliaryImages = new AvifAuxiliaryImageInfo[]{alphaInfo};
        AvifImageInfo info = new AvifImageInfo(
                3,
                4,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.YUV420,
                null,
                transformInfo,
                null,
                auxiliaryImages,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                null
        );
        auxiliaryImages[0] = null;

        assertSame(transformInfo, info.transformInfo());
        assertTrue(info.hasCleanApertureCrop());
        assertEquals(1, info.cleanApertureCropX());
        assertEquals(2, info.cleanApertureCropY());
        assertEquals(3, info.cleanApertureCropWidth());
        assertEquals(4, info.cleanApertureCropHeight());
        assertEquals(1, info.rotationCode());
        assertEquals(0, info.mirrorAxis());
        assertTrue(info.alphaPresent());
        assertTrue(info.alphaPremultiplied());
        assertArrayEquals(new String[]{AvifAuxiliaryImageInfo.ALPHA_TYPE}, info.auxiliaryImageTypes());
        assertArrayEquals(new AvifAuxiliaryImageInfo[]{alphaInfo}, info.auxiliaryImages());
    }

    /// Verifies that invalid display dimensions are rejected by the composed constructor.
    @Test
    void constructorRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> imageInfo(0, 1));
        assertThrows(IllegalArgumentException.class, () -> imageInfo(1, 0));
    }

    /// Creates minimal still-image metadata for dimension validation.
    ///
    /// @param width the display width
    /// @param height the display height
    /// @return the created image metadata
    private static AvifImageInfo imageInfo(int width, int height) {
        return new AvifImageInfo(
                width,
                height,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.YUV420,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /// Copies the remaining bytes from one metadata payload view.
    ///
    /// @param buffer the payload view
    /// @return the remaining payload bytes
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
