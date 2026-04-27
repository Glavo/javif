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

import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder;
import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder.ArgbImage;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.IntBuffer;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests that compare javif decoded AVIF frames against FFmpeg-decoded reference pixels.
@NotNullByDefault
final class LibavifFFmpegAvifReferenceTest {
    /// Verifies that the one-pixel libavif fixture matches FFmpeg's rendered RGBA output exactly.
    ///
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    @Test
    void whiteOneByOneFixtureMatchesFFmpegReferenceExactly() throws IOException, URISyntaxException {
        assertFirstFrameMatchesFFmpegReferenceExactly("libavif-test-data/white_1x1.avif");
    }

    /// Asserts that javif and FFmpeg render the first frame of one AVIF resource identically.
    ///
    /// @param resourceName the classpath AVIF resource name
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFirstFrameMatchesFFmpegReferenceExactly(String resourceName)
            throws IOException, URISyntaxException {
        ArgbImage expected = FFmpegAvifReferenceDecoder.decodeFirstFrameArgb(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifFrame actual = reader.readFrame();
            assertNotNull(actual);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            assertPixelsMatch(resourceName, expected, actual.intPixelBuffer());
            assertNull(reader.readFrame());
        }
    }

    /// Asserts that packed ARGB pixels match an FFmpeg reference image exactly.
    ///
    /// @param label the diagnostic label
    /// @param expected the expected FFmpeg image
    /// @param actualPixels the actual packed ARGB pixels
    private static void assertPixelsMatch(String label, ArgbImage expected, IntBuffer actualPixels) {
        assertEquals(expected.width() * expected.height(), actualPixels.remaining());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                int expectedArgb = expected.pixel(x, y);
                int actualArgb = actualPixels.get(y * expected.width() + x);
                if (expectedArgb != actualArgb) {
                    throw new AssertionError(label + " pixel mismatch at (" + x + "," + y + "): expected "
                            + argbText(expectedArgb) + ", actual " + argbText(actualArgb));
                }
            }
        }
    }

    /// Formats one packed ARGB pixel.
    ///
    /// @param argb the packed pixel
    /// @return the formatted pixel
    private static String argbText(int argb) {
        return String.format(Locale.ROOT, "0x%08X", argb);
    }
}
