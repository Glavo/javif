// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.image.PaddedPlane;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract tests for converting decoded surfaces to 8-bit ARGB output.
///
/// These tests validate deterministic `MONOCHROME`, `YUV420`, `YUV422`, and `YUV444` pixel
/// packing behavior.
@NotNullByDefault
final class Av1DecodedPlanesArgbIntOutputTest {
    /// Verifies that 8-bit monochrome planes become opaque grayscale ARGB pixels and ignore stride padding.
    @Test
    void convertsEightBitI400SamplesIntoOpaqueArgbPixels() {
        DecodedSurface planes = new DecodedSurface(
                8,
                Av1ChromaFormat.MONOCHROME,
                3,
                2,
                3,
                2,
                plane(3, 2, 4, 0, 64, 255, 9, 12, 128, 200, 7),
                null,
                null
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertArrayEquals(
                new int[]{
                        0xFF000000,
                        0xFF404040,
                        0xFFFFFFFF,
                        0xFF0C0C0C,
                        0xFF808080,
                        0xFFC8C8C8
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Verifies that AV1 render hints neither crop nor resample decoded output pixels.
    @Test
    void ignoresRenderSizeHintWhenConvertingDecodedPlanes() {
        DecodedSurface planes = new DecodedSurface(
                8,
                Av1ChromaFormat.MONOCHROME,
                3,
                2,
                7,
                5,
                plane(3, 2, 3, 0, 64, 255, 12, 128, 200),
                null,
                null
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertEquals(6, pixels.length);
        assertArrayEquals(
                new int[]{
                        0xFF000000,
                        0xFF404040,
                        0xFFFFFFFF,
                        0xFF0C0C0C,
                        0xFF808080,
                        0xFFC8C8C8
                },
                pixels
        );
    }

    /// Verifies that high-bit-depth planes can be reduced directly into opaque 8-bit ARGB output.
    @Test
    void convertsTenBitI400SamplesIntoOpaqueArgbPixels() {
        DecodedSurface planes = new DecodedSurface(
                10,
                Av1ChromaFormat.MONOCHROME,
                3,
                1,
                3,
                1,
                plane(3, 1, 4, 0, 512, 1023, 7),
                null,
                null
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertArrayEquals(
                new int[]{
                        0xFF000000,
                        0xFF808080,
                        0xFFFFFFFF
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Verifies that 8-bit `YUV420` output reuses one chroma sample for each 2x2 luma block and packs `AARRGGBB`.
    ///
    /// The left block uses neutral chroma so its pixels must stay grayscale. The right block uses strongly
    /// blue-biased chroma so channel extraction can validate the non-premultiplied ARGB byte order without
    /// depending on one exact rounding formula.
    @Test
    void convertsEightBitI420SamplesUsingSharedChromaIntoOpaqueArgbPixels() {
        DecodedSurface planes = new DecodedSurface(
                8,
                Av1ChromaFormat.YUV420,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 100, 100, 100, 100, 3, 100, 100, 100, 100, 4),
                plane(2, 1, 3, 128, 255, 5),
                plane(2, 1, 3, 128, 0, 6)
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertEquals(8, pixels.length);
        assertEquals(0xFF646464, pixels[0]);
        assertEquals(0xFF646464, pixels[1]);
        assertEquals(0xFF646464, pixels[4]);
        assertEquals(0xFF646464, pixels[5]);

        assertEquals(pixels[2], pixels[3]);
        assertEquals(pixels[2], pixels[6]);
        assertEquals(pixels[2], pixels[7]);
        assertNotEquals(0xFF646464, pixels[2]);
        assertEquals(0xFF, alpha(pixels[2]));
        assertTrue(blue(pixels[2]) > green(pixels[2]));
        assertTrue(green(pixels[2]) > red(pixels[2]));

        assertOpaquePixels(pixels);
    }

    /// Verifies that 8-bit `YUV422` output shares chroma horizontally within each row but not across rows.
    ///
    /// The top row uses neutral chroma for the left pair and blue-biased chroma for the right pair. The
    /// bottom row switches to two different chroma pairs so the expected pixels also catch accidental
    /// `YUV420`-style vertical chroma reuse.
    @Test
    void convertsEightBitI422SamplesUsingRowSpecificHorizontallySharedChromaIntoOpaqueArgbPixels() {
        DecodedSurface planes = new DecodedSurface(
                8,
                Av1ChromaFormat.YUV422,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 40, 90, 140, 190, 1, 60, 110, 160, 210, 2),
                plane(2, 2, 3, 128, 180, 3, 90, 150, 4),
                plane(2, 2, 3, 128, 70, 5, 220, 160, 6)
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertArrayEquals(
                new int[]{
                        0xFF282828,
                        0xFF5A5A5A,
                        0xFF3BA4E8,
                        0xFF6DD6FF,
                        0xFFBD0700,
                        0xFFEF392B,
                        0xFFCD82C7,
                        0xFFFFB4F9
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Verifies that 8-bit `YUV444` output uses one chroma pair per luma sample with no subsampling.
    ///
    /// Every visible pixel uses a different YUV triplet, while stride padding stays outside the render
    /// rectangle. Exact packed pixels ensure the converter preserves the intended `AARRGGBB` byte order.
    @Test
    void convertsEightBitI444SamplesUsingPerPixelChromaIntoOpaqueArgbPixels() {
        DecodedSurface planes = new DecodedSurface(
                8,
                Av1ChromaFormat.YUV444,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 20, 80, 140, 200, 1, 35, 95, 155, 215, 2),
                plane(4, 2, 5, 128, 160, 96, 200, 3, 140, 110, 180, 70, 4),
                plane(4, 2, 5, 128, 90, 210, 40, 5, 150, 70, 100, 220, 6)
        );

        int[] pixels = ArgbOutput.toOpaqueArgbPixels(planes);

        assertArrayEquals(
                new int[]{
                        0xFF141414,
                        0xFF1B6089,
                        0xFFFF5C53,
                        0xFF4DEEFF,
                        0xFF420F38,
                        0xFF0E8F3F,
                        0xFF749DF7,
                        0xFFFFA970
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Asserts opaque `0xFF` alpha for every packed ARGB pixel.
    ///
    /// @param pixels the packed ARGB pixels to validate
    private static void assertOpaquePixels(int[] pixels) {
        for (int pixel : pixels) {
            assertEquals(0xFF, alpha(pixel));
        }
    }

    /// Creates one immutable decoded plane from unsigned integer sample values.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the plane stride in samples
    /// @param values the unsigned sample values in row-major order
    /// @return one immutable decoded plane
    private static PaddedPlane plane(int width, int height, int stride, int... values) {
        short[] samples = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            samples[i] = (short) values[i];
        }
        return new PaddedPlane(width, height, stride, samples);
    }

    /// Returns the packed alpha component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed alpha component
    private static int alpha(int pixel) {
        return (pixel >>> 24) & 0xFF;
    }

    /// Returns the packed red component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed red component
    private static int red(int pixel) {
        return (pixel >>> 16) & 0xFF;
    }

    /// Returns the packed green component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed green component
    private static int green(int pixel) {
        return (pixel >>> 8) & 0xFF;
    }

    /// Returns the packed blue component from one `0xAARRGGBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed blue component
    private static int blue(int pixel) {
        return pixel & 0xFF;
    }
}
