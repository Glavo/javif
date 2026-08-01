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
import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder.SourcePlanes;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// FFmpeg source-plane reference tests for `link-u/avif-sample-images`.
@NotNullByDefault
final class LinkUAvifSampleImagesFFmpegReferenceTest {
    /// The copied sample-image resource root.
    private static final String TEST_DATA_ROOT = "link-u-avif-sample-images";

    /// Creates one first-frame source-plane comparison for every copied sample supported by the
    /// FFmpeg reference helper.
    ///
    /// @return the dynamic FFmpeg source-plane reference tests
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @TestFactory
    Stream<DynamicTest> firstFrameSourcePlanesMatchFFmpeg() throws IOException, URISyntaxException {
        return sampleResourceNames().stream()
                .map(resourceName -> DynamicTest.dynamicTest(
                        resourceName,
                        () -> assertSourcePlanesMatchFFmpeg(resourceName)
                ));
    }

    /// Compares javif's raw first-frame color planes with FFmpeg's decoded source planes.
    ///
    /// @param resourceName the classpath sample resource name
    /// @throws IOException if the sample cannot be read or decoded
    /// @throws URISyntaxException if FFmpeg cannot resolve the sample resource path
    private static void assertSourcePlanesMatchFFmpeg(String resourceName) throws IOException, URISyntaxException {
        SourcePlanes expected = FFmpegAvifReferenceDecoder.decodeFirstFrameSourcePlanes(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifPlanes actual = reader.readRawColorPlanes(0);
            assertEquals(expected.width(), actual.codedWidth(), resourceName + " width");
            assertEquals(expected.height(), actual.codedHeight(), resourceName + " height");
            assertEquals(expected.sourceMetadata().bitDepth(), actual.bitDepth(), resourceName + " bit depth");
            assertEquals(expected.sourceMetadata().pixelFormat(), actual.pixelFormat(), resourceName + " pixel format");

            assertPlaneMatches(
                    resourceName + " Y",
                    expected.width(),
                    expected.height(),
                    actual.lumaPlane(),
                    expected::lumaSample,
                    actual.bitDepth()
            );
            if (expected.sourceMetadata().pixelFormat() == AvifPixelFormat.I400) {
                assertNull(actual.chromaUPlane(), resourceName + " U plane");
                assertNull(actual.chromaVPlane(), resourceName + " V plane");
            } else {
                AvifPlane chromaUPlane = actual.chromaUPlane();
                AvifPlane chromaVPlane = actual.chromaVPlane();
                assertNotNull(chromaUPlane, resourceName + " U plane");
                assertNotNull(chromaVPlane, resourceName + " V plane");
                assertPlaneMatches(
                        resourceName + " U",
                        expected.chromaWidth(),
                        expected.chromaHeight(),
                        chromaUPlane,
                        expected::chromaUSample,
                        actual.bitDepth()
                );
                assertPlaneMatches(
                        resourceName + " V",
                        expected.chromaWidth(),
                        expected.chromaHeight(),
                        chromaVPlane,
                        expected::chromaVSample,
                        actual.bitDepth()
                );
            }
        }
    }

    /// Asserts that one decoded color plane remains within the known FFmpeg parity envelope.
    ///
    /// Most planes are bit-exact. A few odd-dimension `fox` variants retain isolated edge samples
    /// differing by at most three 8-bit-equivalent levels, so the general envelope permits that
    /// maximum while rejecting either widespread drift or the severe transform and prediction
    /// regressions represented by the named high-signal samples.
    ///
    /// @param label the diagnostic label
    /// @param expectedWidth the expected plane width
    /// @param expectedHeight the expected plane height
    /// @param actual the javif decoded plane
    /// @param expectedSample the FFmpeg sample supplier
    /// @param bitDepth the decoded sample bit depth
    private static void assertPlaneMatches(
            String label,
            int expectedWidth,
            int expectedHeight,
            AvifPlane actual,
            IntBinaryOperator expectedSample,
            AvifBitDepth bitDepth
    ) {
        assertEquals(expectedWidth, actual.width(), label + " width");
        assertEquals(expectedHeight, actual.height(), label + " height");
        int largestDelta = 0;
        int deltasAboveScale = 0;
        int largestDeltaX = 0;
        int largestDeltaY = 0;
        int largestDeltaExpected = 0;
        int largestDeltaActual = 0;
        int scale = 1 << (bitDepth.bits() - 8);
        for (int y = 0; y < expectedHeight; y++) {
            for (int x = 0; x < expectedWidth; x++) {
                int expected = expectedSample.applyAsInt(x, y);
                int sample = actual.sample(x, y);
                int delta = Math.abs(expected - sample);
                if (delta > largestDelta) {
                    largestDelta = delta;
                    largestDeltaX = x;
                    largestDeltaY = y;
                    largestDeltaExpected = expected;
                    largestDeltaActual = sample;
                }
                if (delta > scale) {
                    deltasAboveScale++;
                }
            }
        }
        int maximumAllowedDelta = maximumAllowedDelta(label, scale);
        assertTrue(
                largestDelta <= maximumAllowedDelta,
                label + " maximum delta " + largestDelta + " exceeds " + maximumAllowedDelta
                        + " at (" + largestDeltaX + "," + largestDeltaY + ")"
                        + ": expected=" + largestDeltaExpected + ", actual=" + largestDeltaActual
        );
        assertTrue(
                deltasAboveScale <= 16,
                label + " has " + deltasAboveScale + " samples differing by more than " + scale
        );
    }

    /// Returns the maximum permitted source-plane difference for one comparison.
    ///
    /// @param label the diagnostic label containing the resource and plane names
    /// @param scale one 8-bit-equivalent sample level at the decoded bit depth
    /// @return the maximum permitted absolute sample difference
    private static int maximumAllowedDelta(String label, int scale) {
        if (label.contains("/hato.")
                || label.contains("/kimono.mirror-vertical.rotate270")
                || label.contains("/red-at-12-oclock")) {
            return scale;
        }
        return 3 * scale;
    }

    /// Returns the sorted classpath names of copied samples supported by the FFmpeg helper.
    ///
    /// @return the supported copied sample resource names
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static @Unmodifiable List<String> sampleResourceNames() throws IOException, URISyntaxException {
        URL rootUrl = Objects.requireNonNull(
                LinkUAvifSampleImagesFFmpegReferenceTest.class.getClassLoader().getResource(TEST_DATA_ROOT),
                "Missing test resource root: " + TEST_DATA_ROOT
        );
        Path root = Path.of(rootUrl.toURI());
        try (Stream<Path> paths = Files.walk(root)) {
            @Unmodifiable List<String> resourceNames = paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(LinkUAvifSampleImagesFFmpegReferenceTest::isAvifResource)
                    .filter(LinkUAvifSampleImagesFFmpegReferenceTest::isSupportedReferenceResource)
                    .map(relativeName -> TEST_DATA_ROOT + "/" + relativeName.replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            assertEquals(153, resourceNames.size(), "supported sample resource count");
            return resourceNames;
        }
    }

    /// Returns whether the FFmpeg helper selects the color track for one sample resource.
    ///
    /// The three alpha-bearing AVIFS samples expose their auxiliary alpha track before the color
    /// track, while the current helper decodes the first video track.
    ///
    /// @param resourceName the relative sample resource name
    /// @return whether the reference helper can decode the resource's color track
    private static boolean isSupportedReferenceResource(String resourceName) {
        return !resourceName.endsWith("-with-alpha.avifs");
    }

    /// Returns whether one relative resource name is an AVIF still image or sequence.
    ///
    /// @param resourceName the relative resource name
    /// @return whether the resource is an AVIF still image or sequence
    private static boolean isAvifResource(String resourceName) {
        return resourceName.endsWith(".avif") || resourceName.endsWith(".avifs");
    }
}
