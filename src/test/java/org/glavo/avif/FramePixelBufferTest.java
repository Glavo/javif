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

import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.decode.FrameType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for shared parent-frame pixel-buffer storage and lazy conversion.
@NotNullByDefault
final class FramePixelBufferTest {
    /// Verifies that `AvifFrame` lazily expands `int` pixels into `long` pixels.
    @Test
    void avifFrameLazilyConvertsIntPixelsToLongPixels() {
        AvifFrame frame = new AvifFrame(
                1,
                1,
                AvifBitDepth.EIGHT_BITS,
                AvifPixelFormat.I400,
                0,
                IntBuffer.wrap(new int[]{0x8040_2010}).asReadOnlyBuffer()
        );

        IntBuffer intPixels = frame.intPixelBuffer();
        LongBuffer longPixels = frame.longPixelBuffer();

        assertTrue(intPixels.isReadOnly());
        assertTrue(longPixels.isReadOnly());
        assertEquals(0x8040_2010, intPixels.get(0));
        assertEquals(0x8080_4040_2020_1010L, longPixels.get(0));
        assertArrayEquals(new int[]{0x8040_2010}, frame.intPixels());
        assertArrayEquals(new long[]{0x8080_4040_2020_1010L}, frame.longPixels());
    }

    /// Verifies that `AvifFrame` lazily reduces `long` pixels into `int` pixels.
    @Test
    void avifFrameLazilyConvertsLongPixelsToIntPixels() {
        AvifFrame frame = new AvifFrame(
                1,
                1,
                AvifBitDepth.TEN_BITS,
                AvifPixelFormat.I400,
                0,
                LongBuffer.wrap(new long[]{0xFFFF_8080_4040_0000L}).asReadOnlyBuffer()
        );

        LongBuffer longPixels = frame.longPixelBuffer();
        IntBuffer intPixels = frame.intPixelBuffer();

        assertTrue(longPixels.isReadOnly());
        assertTrue(intPixels.isReadOnly());
        assertEquals(0xFFFF_8080_4040_0000L, longPixels.get(0));
        assertEquals(0xFF80_4000, intPixels.get(0));
        assertArrayEquals(new long[]{0xFFFF_8080_4040_0000L}, frame.longPixels());
        assertArrayEquals(new int[]{0xFF80_4000}, frame.intPixels());
    }

    /// Verifies that `DecodedFrame` exposes the same shared lazy-conversion contract.
    @Test
    void decodedFrameLazilyConvertsBothPixelRepresentations() {
        DecodedFrame intFrame = new DecodedFrame(
                1,
                1,
                AvifBitDepth.EIGHT_BITS,
                AvifPixelFormat.I400,
                FrameType.KEY,
                true,
                3L,
                IntBuffer.wrap(new int[]{0xFF00_80FF}).asReadOnlyBuffer()
        );
        DecodedFrame longFrame = new DecodedFrame(
                1,
                1,
                AvifBitDepth.TWELVE_BITS,
                AvifPixelFormat.I400,
                FrameType.KEY,
                true,
                4L,
                LongBuffer.wrap(new long[]{0x8000_FFFF_4040_2020L}).asReadOnlyBuffer()
        );

        assertEquals(0xFFFF_0000_8080_FFFFL, intFrame.longPixelBuffer().get(0));
        assertEquals(0x80FF_4020, longFrame.intPixelBuffer().get(0));
    }
}
