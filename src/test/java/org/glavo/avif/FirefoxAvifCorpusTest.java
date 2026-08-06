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

import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compatibility tests derived from Firefox's AVIF decoder gtests and crash tests.
///
/// The pinned external resources are downloaded and verified by the Gradle `firefoxAvifTest` task.
@Tag("firefox-corpus")
@NotNullByDefault
final class FirefoxAvifCorpusTest {
    /// The classpath root containing selected Firefox decoder fixtures.
    private static final String GTEST_ROOT = "firefox-avif-test-data/gtest/";

    /// The classpath root containing selected Firefox AVIF crash fixtures.
    private static final String CRASH_ROOT = "firefox-avif-test-data/crashtests/";

    /// Bit shifts for the alpha, red, green, and blue channels in packed ARGB pixels.
    private static final int @Unmodifiable [] CHANNEL_SHIFTS = {24, 16, 8, 0};

    /// Firefox fixtures that exercise successful non-synthetic decoding paths.
    private static final @Unmodifiable List<ValidFixture> VALID_FIXTURES = List.of(
            new ValidFixture("green.avif", 100, 100, 1),
            new ValidFixture("hdlr-nonzero-reserved-bug-1727033.avif", 1, 1, 1),
            new ValidFixture("valid-avif-colr-nclx-and-prof.avif", 1, 1, 1),
            new ValidFixture("transparent.avif", 100, 100, 1),
            new ValidFixture("first-frame-green.avif", 100, 100, 2),
            new ValidFixture("blend.avif", 100, 100, 2),
            new ValidFixture("downscaled.avif", 100, 100, 1),
            new ValidFixture("multilayer.avif", 1280, 720, 1),
            new ValidFixture("large.avif", 1200, 660, 1),
            new ValidFixture("stackcheck.avif", 4096, 2924, 1)
    );

    /// Firefox crash-test resources whose required contract is termination without an unchecked failure.
    private static final @Unmodifiable List<String> CRASH_FIXTURES = List.of(
            "1814553.avif",
            "1814561.avif",
            "1814677.avif",
            "1814708.avif",
            "1814741.avif",
            "1814774.avif",
            "1817108.avif",
            "1848717-1.avif",
            "1910211-1.avif"
    );

    /// Verifies Firefox's solid-gray matrix and range fixtures.
    ///
    /// @return one dynamic test for each bit-depth, range, and matrix combination
    @TestFactory
    Stream<DynamicTest> grayFixturesMatchFirefoxExpectedColors() {
        return grayFixtures().stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertGrayFixture(testCase)
        ));
    }

    /// Verifies Firefox's alpha and chroma-subsampling fixtures.
    ///
    /// @return one dynamic test for each bit-depth and chroma-format combination
    @TestFactory
    Stream<DynamicTest> transparentGreenFixturesMatchFirefoxExpectedColors() {
        return transparentGreenFixtures().stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertTransparentGreenFixture(testCase)
        ));
    }

    /// Decodes Firefox's valid regression fixtures and verifies their advertised layout.
    ///
    /// @return one dynamic test for each selected valid regression fixture
    @TestFactory
    Stream<DynamicTest> validRegressionFixturesDecodeCompletely() {
        return VALID_FIXTURES.stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertValidFixture(testCase)
        ));
    }

    /// Verifies the representative colors used by Firefox's ordinary decoder tests.
    ///
    /// @throws IOException if a fixture cannot be decoded
    @Test
    void ordinaryFixturesMatchFirefoxExpectedColors() throws IOException {
        assertSolidColor("green.avif", 0xff00ff00, 0);
        assertSolidColor("transparent.avif", 0x8000ff00, 1);
        assertSolidColor("hdlr-nonzero-reserved-bug-1727033.avif", 0xff000000, 0);
        assertSolidColor("valid-avif-colr-nclx-and-prof.avif", 0xff000000, 0);
    }

    /// Verifies Firefox's two-frame green animation metadata and first frame.
    ///
    /// @throws IOException if the fixture cannot be decoded
    @Test
    void firstFrameGreenAnimationHasTwoFrames() throws IOException {
        try (AvifImageReader reader = open("first-frame-green.avif")) {
            AvifImageInfo info = reader.info();
            assertTrue(info.animated());
            assertEquals(2, info.frameCount());
            AvifFrame first = reader.readFrame(0);
            assertEquals(0xff00ff00, first.intPixelBuffer().get(0));
            assertEquals(2, reader.readAllFrames().size());
        }
    }

    /// Verifies that the valid multiple-`colr` fixture retains both standardized and ICC metadata.
    ///
    /// @throws IOException if the fixture cannot be parsed
    @Test
    void multipleColrFixtureExposesColorMetadata() throws IOException {
        try (AvifImageReader reader = open("valid-avif-colr-nclx-and-prof.avif")) {
            AvifImageInfo info = reader.info();
            assertNotNull(info.colorInfo());
            assertNotNull(info.iccProfile());
        }
    }

    /// Verifies that Firefox's known corrupt AVIF is rejected.
    @Test
    void corruptFixtureIsRejected() {
        assertThrows(IOException.class, () -> {
            try (AvifImageReader reader = open("bug-1655846.avif")) {
                reader.readAllFrames();
            }
        });
    }

    /// Exercises every selected Firefox crash-test input without accepting unchecked failures.
    ///
    /// A checked decode failure is a valid outcome because Firefox crash tests specify process
    /// safety, not successful decoding of malformed input.
    ///
    /// @return one dynamic test for each selected Firefox crash-test fixture
    @TestFactory
    Stream<DynamicTest> malformedFixturesDoNotCauseUncheckedFailures() {
        return CRASH_FIXTURES.stream().map(resourceName -> DynamicTest.dynamicTest(
                resourceName,
                () -> assertNoUncheckedFailure(resourceName)
        ));
    }

    /// Verifies one Firefox solid-gray fixture.
    ///
    /// @param testCase the expected fixture properties
    /// @throws IOException if the fixture cannot be decoded
    private static void assertGrayFixture(GrayFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(100, info.width());
            assertEquals(100, info.height());
            assertEquals(AvifBitDepth.fromBits(testCase.bitDepth()), info.bitDepth());
            assertEquals(testCase.monochrome() ? Av1ChromaFormat.MONOCHROME : Av1ChromaFormat.YUV420,
                    info.chromaFormat());
            AvifFrame frame = reader.readFrame(0);
            int expectedChannel = testCase.fullRange() || testCase.bitDepth() == 8 ? 235 : 234;
            assertEveryPixelNear(frame, argb(255, expectedChannel, expectedChannel, expectedChannel), 1);
        }
    }

    /// Verifies one Firefox transparent-green fixture.
    ///
    /// @param testCase the expected fixture properties
    /// @throws IOException if the fixture cannot be decoded
    private static void assertTransparentGreenFixture(TransparentFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(100, info.width());
            assertEquals(100, info.height());
            assertEquals(AvifBitDepth.fromBits(testCase.bitDepth()), info.bitDepth());
            assertEquals(testCase.chromaFormat(), info.chromaFormat());
            assertTrue(info.alphaPresent());
            AvifFrame frame = reader.readFrame(0);
            int expectedBlue = testCase.bitDepth() == 8 ? 2 : 0;
            assertEveryPixelNear(frame, argb(128, 0, 255, expectedBlue), 1);
        }
    }

    /// Decodes every frame in one valid Firefox fixture.
    ///
    /// @param testCase the expected fixture layout
    /// @throws IOException if the fixture cannot be decoded
    private static void assertValidFixture(ValidFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(testCase.width(), info.width());
            assertEquals(testCase.height(), info.height());
            assertEquals(testCase.frameCount(), info.frameCount());
            assertEquals(testCase.frameCount() > 1, info.animated());
            assertEquals(testCase.frameCount(), reader.readAllFrames().size());
        }
    }

    /// Decodes or rejects one malformed Firefox fixture using only checked failure paths.
    ///
    /// @param resourceName the crash-test resource name
    private static void assertNoUncheckedFailure(String resourceName) {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(CRASH_ROOT + resourceName))) {
            reader.readAllFrames();
        } catch (IOException expected) {
            // Rejection is an explicitly permitted crash-test outcome.
        }
    }

    /// Verifies all pixels in one ordinary Firefox solid-color fixture.
    ///
    /// @param resourceName the gtest resource name
    /// @param expectedArgb the expected packed ARGB color
    /// @param tolerance the permitted difference in each channel
    /// @throws IOException if the fixture cannot be decoded
    private static void assertSolidColor(String resourceName, int expectedArgb, int tolerance) throws IOException {
        try (AvifImageReader reader = open(resourceName)) {
            assertEveryPixelNear(reader.readFrame(0), expectedArgb, tolerance);
        }
    }

    /// Verifies every packed pixel against an expected color.
    ///
    /// @param frame the decoded frame
    /// @param expectedArgb the expected packed ARGB color
    /// @param tolerance the permitted difference in each channel
    private static void assertEveryPixelNear(AvifFrame frame, int expectedArgb, int tolerance) {
        int[] pixels = frame.intPixels();
        assertEquals(Math.multiplyExact(frame.width(), frame.height()), pixels.length);
        for (int index = 0; index < pixels.length; index++) {
            assertPixelNear(expectedArgb, pixels[index], tolerance, "pixel " + index);
        }
    }

    /// Verifies each channel of one packed pixel.
    ///
    /// @param expectedArgb the expected packed ARGB color
    /// @param actualArgb the actual packed ARGB color
    /// @param tolerance the permitted difference in each channel
    /// @param message the assertion context
    private static void assertPixelNear(int expectedArgb, int actualArgb, int tolerance, String message) {
        for (int shift : CHANNEL_SHIFTS) {
            int expected = expectedArgb >>> shift & 0xff;
            int actual = actualArgb >>> shift & 0xff;
            assertTrue(Math.abs(expected - actual) <= tolerance,
                    () -> message + " channel " + shift + ": expected " + expected + " but was " + actual);
        }
    }

    /// Opens one Firefox gtest fixture.
    ///
    /// @param resourceName the gtest resource name
    /// @return a reader for the fixture
    /// @throws AvifDecodeException if the fixture container cannot be parsed
    /// @throws IOException if the fixture cannot be read
    private static AvifImageReader open(String resourceName) throws IOException {
        return AvifImageReader.open(TestResources.readBytes(GTEST_ROOT + resourceName));
    }

    /// Creates the matrix of Firefox gray fixtures.
    ///
    /// @return the immutable gray-fixture matrix
    private static @Unmodifiable List<GrayFixture> grayFixtures() {
        List<GrayFixture> fixtures = new ArrayList<>();
        for (int bitDepth : new int[]{8, 10, 12}) {
            for (String range : List.of("full", "limited")) {
                for (String matrix : List.of("bt601", "bt709", "bt2020", "grayscale")) {
                    fixtures.add(new GrayFixture(
                            "gray-235-" + bitDepth + "bit-" + range + "-range-" + matrix + ".avif",
                            bitDepth,
                            range.equals("full"),
                            matrix.equals("grayscale")
                    ));
                }
            }
        }
        return List.copyOf(fixtures);
    }

    /// Creates the matrix of Firefox transparent-green fixtures.
    ///
    /// @return the immutable transparent-fixture matrix
    private static @Unmodifiable List<TransparentFixture> transparentGreenFixtures() {
        List<TransparentFixture> fixtures = new ArrayList<>();
        for (int bitDepth : new int[]{8, 10, 12}) {
            for (Av1ChromaFormat chromaFormat : List.of(
                    Av1ChromaFormat.YUV420,
                    Av1ChromaFormat.YUV422,
                    Av1ChromaFormat.YUV444
            )) {
                String chroma = chromaFormat.name().toLowerCase();
                fixtures.add(new TransparentFixture(
                        "transparent-green-50pct-" + bitDepth + "bit-" + chroma + ".avif",
                        bitDepth,
                        chromaFormat
                ));
            }
        }
        return List.copyOf(fixtures);
    }

    /// Packs four unsigned channels into an ARGB pixel.
    ///
    /// @param alpha the alpha channel
    /// @param red the red channel
    /// @param green the green channel
    /// @param blue the blue channel
    /// @return the packed ARGB pixel
    private static int argb(int alpha, int red, int green, int blue) {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /// Describes one solid-gray Firefox fixture.
    ///
    /// @param resourceName the gtest resource name
    /// @param bitDepth the encoded sample bit depth
    /// @param fullRange whether the fixture uses full-range samples
    /// @param monochrome whether the fixture has no chroma planes
    private record GrayFixture(String resourceName, int bitDepth, boolean fullRange, boolean monochrome) {
    }

    /// Describes one transparent-green Firefox fixture.
    ///
    /// @param resourceName the gtest resource name
    /// @param bitDepth the encoded sample bit depth
    /// @param chromaFormat the encoded chroma layout
    private record TransparentFixture(String resourceName, int bitDepth, Av1ChromaFormat chromaFormat) {
    }

    /// Describes one valid Firefox regression fixture.
    ///
    /// @param resourceName the gtest resource name
    /// @param width the expected display width
    /// @param height the expected display height
    /// @param frameCount the expected frame count
    private record ValidFixture(String resourceName, int width, int height, int frameCount) {
    }
}
