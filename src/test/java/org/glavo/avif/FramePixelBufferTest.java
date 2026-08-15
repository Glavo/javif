// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif;

import org.glavo.avif.av1.Av1DecodedFrame;
import org.glavo.avif.av1.Av1FrameType;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for shared parent-frame pixel-buffer storage and lazy conversion.
@NotNullByDefault
final class FramePixelBufferTest {
    /// Verifies that public array constructors retain defensive-copy semantics.
    @Test
    void avifFrameArrayConstructorsCopyCallerStorage() {
        int[] intPixels = {0xFF00_0000};
        long[] longPixels = {0xFFFF_0000_0000_0000L};
        AvifFrame intFrame = new AvifFrame(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME, 0, intPixels
        );
        AvifFrame longFrame = new AvifFrame(
                1, 1, AvifBitDepth.TEN_BITS, Av1ChromaFormat.MONOCHROME, 0, longPixels
        );

        intPixels[0] = 0xFFFF_FFFF;
        longPixels[0] = 0xFFFF_FFFF_FFFF_FFFFL;

        assertEquals(0xFF00_0000, intFrame.intPixelBuffer().get(0));
        assertEquals(0xFFFF_0000_0000_0000L, longFrame.longPixelBuffer().get(0));
    }

    /// Verifies that package-internal owned-pixel factories retain transferred storage directly.
    @Test
    void avifFrameOwnedPixelFactoriesAvoidDefensiveCopies() {
        int[] intPixels = {0xFF00_0000};
        long[] longPixels = {0xFFFF_0000_0000_0000L};
        AvifFrame intFrame = AvifFrame.fromOwnedPixels(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME, 0, intPixels
        );
        AvifFrame longFrame = AvifFrame.fromOwnedPixels(
                1, 1, AvifBitDepth.TEN_BITS, Av1ChromaFormat.MONOCHROME, 0, longPixels
        );

        // Deliberately inspect the transferred arrays to guard the internal no-copy contract.
        intPixels[0] = 0xFFFF_FFFF;
        longPixels[0] = 0xFFFF_FFFF_FFFF_FFFFL;

        assertEquals(0xFFFF_FFFF, intFrame.intPixelBuffer().get(0));
        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, longFrame.longPixelBuffer().get(0));
        assertTrue(intFrame.intPixelBuffer().isReadOnly());
        assertTrue(longFrame.longPixelBuffer().isReadOnly());
    }

    /// Verifies that `AvifFrame` lazily expands `int` pixels into `long` pixels.
    @Test
    void avifFrameLazilyConvertsIntPixelsToLongPixels() {
        AvifFrame frame = new AvifFrame(
                1,
                1,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.MONOCHROME,
                0,
                IntBuffer.wrap(new int[]{0x8040_2010}).asReadOnlyBuffer()
        );

        IntBuffer intPixels = frame.intPixelBuffer();
        LongBuffer longPixels = frame.longPixelBuffer();

        assertEquals(AvifPixelFormat.ARGB_8888, frame.pixelFormat());
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
                Av1ChromaFormat.MONOCHROME,
                0,
                LongBuffer.wrap(new long[]{0xFFFF_8080_4040_0000L}).asReadOnlyBuffer()
        );

        LongBuffer longPixels = frame.longPixelBuffer();
        IntBuffer intPixels = frame.intPixelBuffer();

        assertEquals(AvifPixelFormat.ARGB_16161616, frame.pixelFormat());
        assertTrue(longPixels.isReadOnly());
        assertTrue(intPixels.isReadOnly());
        assertEquals(0xFFFF_8080_4040_0000L, longPixels.get(0));
        assertEquals(0xFF80_4000, intPixels.get(0));
        assertArrayEquals(new long[]{0xFFFF_8080_4040_0000L}, frame.longPixels());
        assertArrayEquals(new int[]{0xFF80_4000}, frame.intPixels());
    }

    /// Verifies that `Av1DecodedFrame` exposes the same shared lazy-conversion contract.
    @Test
    void decodedFrameLazilyConvertsBothPixelRepresentations() {
        Av1DecodedFrame intFrame = new Av1DecodedFrame(
                1,
                1,
                AvifBitDepth.EIGHT_BITS,
                Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY,
                true,
                3L,
                IntBuffer.wrap(new int[]{0xFF00_80FF}).asReadOnlyBuffer()
        );
        Av1DecodedFrame longFrame = new Av1DecodedFrame(
                1,
                1,
                AvifBitDepth.TWELVE_BITS,
                Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY,
                true,
                4L,
                LongBuffer.wrap(new long[]{0x8000_FFFF_4040_2020L}).asReadOnlyBuffer()
        );

        assertEquals(0xFFFF_0000_8080_FFFFL, intFrame.longPixelBuffer().get(0));
        assertEquals(0x80FF_4020, longFrame.intPixelBuffer().get(0));
        assertEquals(AvifPixelFormat.ARGB_8888, intFrame.pixelFormat());
        assertEquals(AvifPixelFormat.ARGB_16161616, longFrame.pixelFormat());
    }

    /// Verifies that AVIF frames reject dimensions, pixel counts, and frame indexes that cannot
    /// describe one complete image.
    @Test
    void avifFrameRejectsInvalidImageState() {
        assertThrows(IllegalArgumentException.class, () -> new AvifFrame(
                0, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME, 0, new int[0]
        ));
        assertThrows(IllegalArgumentException.class, () -> new AvifFrame(
                2, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME, 0, new int[1]
        ));
        assertThrows(IllegalArgumentException.class, () -> new AvifFrame(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME, -1, new int[1]
        ));
    }

    /// Verifies that raw AV1 frames reject invalid presentation and layer state.
    @Test
    void decodedFrameRejectsInvalidPresentationState() {
        IntBuffer pixel = IntBuffer.wrap(new int[1]).asReadOnlyBuffer();
        assertThrows(IllegalArgumentException.class, () -> new Av1DecodedFrame(
                1, 0, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY, true, 0, pixel
        ));
        assertThrows(IllegalArgumentException.class, () -> new Av1DecodedFrame(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY, true, -1, pixel
        ));
        assertThrows(IllegalArgumentException.class, () -> new Av1DecodedFrame(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY, true, 0, 8, 0, pixel
        ));
        assertThrows(IllegalArgumentException.class, () -> new Av1DecodedFrame(
                1, 1, AvifBitDepth.EIGHT_BITS, Av1ChromaFormat.MONOCHROME,
                Av1FrameType.KEY, true, 0, 0, 4, pixel
        ));
    }
}
