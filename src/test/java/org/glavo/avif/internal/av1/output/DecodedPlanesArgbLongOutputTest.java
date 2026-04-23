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
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.decode.ArgbLongFrame;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Contract tests for high-bit-depth ARGB output built on `DecodedPlanes`.
///
/// These tests validate the completed Track E long-output path directly through `ArgbOutput`,
/// covering exact `0xAAAA_RRRR_GGGG_BBBB` packing, `I444` per-pixel chroma sampling, public
/// frame metadata materialization, and the `8-bit` contract for long-output entry points.
@NotNullByDefault
final class DecodedPlanesArgbLongOutputTest {
    /// The fixed transform contract used by the convenience long-output overloads.
    private static final YuvToRgbTransform DEFAULT_TRANSFORM = YuvToRgbTransform.BT601_FULL_RANGE;

    /// The test frame type supplied to frame-returning long-output converters.
    private static final FrameType TEST_FRAME_TYPE = FrameType.SWITCH;

    /// The test visibility flag supplied to frame-returning long-output converters.
    private static final boolean TEST_VISIBLE = false;

    /// The test presentation index supplied to frame-returning long-output converters.
    private static final long TEST_PRESENTATION_INDEX = 19L;

    /// Verifies that `10-bit` monochrome samples become opaque grayscale `0xAAAA_RRRR_GGGG_BBBB`
    /// pixels and ignore stride padding.
    @Test
    void convertsTenBitI400SamplesIntoOpaqueArgbLongPixels() {
        DecodedPlanes planes = new DecodedPlanes(
                10,
                PixelFormat.I400,
                3,
                2,
                3,
                2,
                plane(3, 2, 4, 0, 257, 1023, 91, 64, 768, 511, 123),
                null,
                null
        );

        long[] pixels = ArgbOutput.toOpaqueArgbLongPixels(planes);

        assertArrayEquals(
                new long[]{
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(0, 10),
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(257, 10),
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(1023, 10),
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(64, 10),
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(768, 10),
                        DEFAULT_TRANSFORM.toOpaqueGrayArgb64(511, 10)
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Verifies that `12-bit I444` output uses one chroma pair per luma sample with no subsampling.
    ///
    /// Every visible pixel uses a different YUV triplet while stride padding stays outside the
    /// render rectangle. Exact packed pixels ensure the long-output path preserves the intended
    /// `0xAAAA_RRRR_GGGG_BBBB` lane order and per-pixel chroma sampling.
    @Test
    void convertsTwelveBitI444SamplesUsingPerPixelChromaIntoOpaqueArgbLongPixels() {
        DecodedPlanes planes = new DecodedPlanes(
                12,
                PixelFormat.I444,
                4,
                2,
                4,
                2,
                plane(4, 2, 5, 256, 1024, 2048, 3584, 7, 384, 1536, 2816, 4095, 9),
                plane(4, 2, 5, 2048, 3072, 1024, 256, 11, 2304, 1792, 3584, 512, 13),
                plane(4, 2, 5, 2048, 1024, 3072, 3840, 15, 1536, 2560, 768, 2048, 17)
        );

        long[] pixels = ArgbOutput.toOpaqueArgbLongPixels(planes);

        assertArrayEquals(
                new long[]{
                        DEFAULT_TRANSFORM.toOpaqueArgb64(256, 2048, 2048, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(1024, 3072, 1024, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(2048, 1024, 3072, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(3584, 256, 3840, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(384, 2304, 1536, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(1536, 1792, 2560, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(2816, 3584, 768, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(4095, 512, 2048, 12)
                },
                pixels
        );
        assertOpaquePixels(pixels);
    }

    /// Verifies that one frame-returning long-output overload preserves public frame metadata on
    /// `ArgbLongFrame`.
    @Test
    void returnsArgbLongFrameMetadataForTwelveBitI444Output() {
        DecodedPlanes planes = new DecodedPlanes(
                12,
                PixelFormat.I444,
                2,
                1,
                2,
                1,
                plane(2, 1, 3, 1536, 3072, 1),
                plane(2, 1, 3, 2048, 1024, 2),
                plane(2, 1, 3, 2048, 3072, 3)
        );

        ArgbLongFrame frame = ArgbOutput.toOpaqueArgbLongFrame(
                planes,
                TEST_FRAME_TYPE,
                TEST_VISIBLE,
                TEST_PRESENTATION_INDEX
        );

        assertArrayEquals(
                new long[]{
                        DEFAULT_TRANSFORM.toOpaqueArgb64(1536, 2048, 2048, 12),
                        DEFAULT_TRANSFORM.toOpaqueArgb64(3072, 1024, 3072, 12)
                },
                frame.pixels()
        );
        assertFrameMetadata(frame, planes);
    }

    /// Verifies the intended `8-bit` contract for long-output entry points.
    ///
    /// Implementations may either reject `8-bit` decoded planes because long output is reserved for
    /// high-bit-depth content, or they may explicitly support `8-bit` and expand it into 16-bit
    /// lanes. This test accepts either behavior while requiring the pixel-returning and
    /// frame-returning entry points to stay consistent with one another.
    @Test
    void rejectsEightBitDecodedPlanesForLongOutputOnlyEntryPointsOrSupportsThemExplicitly() {
        DecodedPlanes planes = new DecodedPlanes(
                8,
                PixelFormat.I400,
                3,
                1,
                3,
                1,
                plane(3, 1, 4, 0, 96, 255, 33),
                null,
                null
        );
        long[] expectedPixels = new long[]{
                DEFAULT_TRANSFORM.toOpaqueGrayArgb64(0, 8),
                DEFAULT_TRANSFORM.toOpaqueGrayArgb64(96, 8),
                DEFAULT_TRANSFORM.toOpaqueGrayArgb64(255, 8)
        };

        boolean pixelEntryPointSupportsEightBit = assertEightBitPixelEntryPointContract(planes, expectedPixels);
        boolean frameEntryPointSupportsEightBit = assertEightBitFrameEntryPointContract(planes, expectedPixels);

        assertEquals(
                pixelEntryPointSupportsEightBit,
                frameEntryPointSupportsEightBit,
                "Long pixel and frame entry points should agree on 8-bit support"
        );
    }

    /// Asserts the `8-bit` contract for the long pixel-returning entry point.
    ///
    /// @param planes the `8-bit` decoded planes
    /// @param expectedPixels the expected expanded 64-bit ARGB pixels if the entry point supports `8-bit`
    /// @return whether the entry point supports `8-bit`
    private static boolean assertEightBitPixelEntryPointContract(DecodedPlanes planes, long[] expectedPixels) {
        try {
            long[] pixels = ArgbOutput.toOpaqueArgbLongPixels(planes);
            assertArrayEquals(expectedPixels, pixels);
            assertOpaquePixels(pixels);
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            assertEightBitRejection(exception);
            return false;
        }
    }

    /// Asserts the `8-bit` contract for the frame-returning long-output entry point.
    ///
    /// @param planes the `8-bit` decoded planes
    /// @param expectedPixels the expected expanded 64-bit ARGB pixels if the entry point supports `8-bit`
    /// @return whether the entry point supports `8-bit`
    private static boolean assertEightBitFrameEntryPointContract(DecodedPlanes planes, long[] expectedPixels) {
        try {
            ArgbLongFrame frame = ArgbOutput.toOpaqueArgbLongFrame(
                    planes,
                    TEST_FRAME_TYPE,
                    TEST_VISIBLE,
                    TEST_PRESENTATION_INDEX
            );
            assertArrayEquals(expectedPixels, frame.pixels());
            assertOpaquePixels(frame.pixels());
            assertFrameMetadata(frame, planes);
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            assertEightBitRejection(exception);
            return false;
        }
    }

    /// Asserts that one `8-bit` rejection explains the long-output bit-depth contract.
    ///
    /// @param exception the rejection raised by the long-output entry point
    private static void assertEightBitRejection(RuntimeException exception) {
        String message = Objects.toString(exception.getMessage(), "").toLowerCase(Locale.ROOT);
        assertTrue(
                message.contains("8")
                        || message.contains("bitdepth")
                        || message.contains("bit depth")
                        || message.contains("high-bit")
                        || message.contains("high bit"),
                "Unexpected 8-bit rejection message: " + exception.getMessage()
        );
    }

    /// Asserts public frame metadata on one `ArgbLongFrame`.
    ///
    /// @param frame the frame to validate
    /// @param planes the source decoded planes
    private static void assertFrameMetadata(ArgbLongFrame frame, DecodedPlanes planes) {
        assertEquals(planes.renderWidth(), frame.width());
        assertEquals(planes.renderHeight(), frame.height());
        assertEquals(planes.bitDepth(), frame.bitDepth());
        assertEquals(planes.pixelFormat(), frame.pixelFormat());
        assertEquals(TEST_FRAME_TYPE, frame.frameType());
        assertEquals(TEST_VISIBLE, frame.visible());
        assertEquals(TEST_PRESENTATION_INDEX, frame.presentationIndex());
    }

    /// Asserts fully opaque `0xFFFF` alpha for every packed 64-bit ARGB pixel.
    ///
    /// @param pixels the packed ARGB pixels to validate
    private static void assertOpaquePixels(long[] pixels) {
        for (long pixel : pixels) {
            assertEquals(0xFFFF, alpha(pixel));
        }
    }

    /// Creates one immutable decoded plane from unsigned integer sample values.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the plane stride in samples
    /// @param values the unsigned sample values in row-major order
    /// @return one immutable decoded plane
    private static DecodedPlane plane(int width, int height, int stride, int... values) {
        short[] samples = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            samples[i] = (short) values[i];
        }
        return new DecodedPlane(width, height, stride, samples);
    }

    /// Returns the packed alpha lane from one `0xAAAA_RRRR_GGGG_BBBB` pixel.
    ///
    /// @param pixel the packed ARGB pixel
    /// @return the packed alpha lane
    private static int alpha(long pixel) {
        return (int) ((pixel >>> 48) & 0xFFFFL);
    }
}
