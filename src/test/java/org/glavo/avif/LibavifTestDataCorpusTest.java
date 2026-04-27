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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Corpus tests for AVIF files copied from libavif's `tests/data` directory.
@NotNullByDefault
final class LibavifTestDataCorpusTest {
    /// The copied libavif test data resource root.
    private static final String TEST_DATA_ROOT = "libavif-test-data";

    /// The expected behavior for every AVIF fixture copied from libavif's test data.
    private static final CorpusCase @Unmodifiable [] CASES = new CorpusCase[]{
            decode("libavif-test-data/abc_color_irot_alpha_NOirot.avif", 512, 256, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/abc_color_irot_alpha_irot.avif", 512, 256, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/alpha_noispe.avif", 80, 80, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/arc_triomphe_extent1000_nullbyte_extent1310.avif", 64, 64, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/circle_custom_properties.avif", 100, 60, 8, AvifPixelFormat.I444, true, false, 1),
            parseFailure(
                    "libavif-test-data/clap_irot_imir_non_essential.avif",
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "has a clap property association which must be marked essential, but is not"
            ),
            decode("libavif-test-data/clop_irot_imor.avif", 12, 34, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif", 512, 600, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/color_grid_alpha_grid_tile_shared_in_dimg.avif", 80, 80, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/color_grid_alpha_nogrid.avif", 80, 80, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/color_grid_gainmap_different_grid.avif", 512, 600, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/color_nogrid_alpha_nogrid_gainmap_grid.avif", 128, 200, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/colors-animated-12bpc-keyframes-0-2-3.avif", 64, 64, 12, AvifPixelFormat.I422, true, true, 5),
            decode("libavif-test-data/colors-animated-8bpc-alpha-exif-xmp.avif", 150, 150, 8, AvifPixelFormat.I420, true, true, 5),
            decode("libavif-test-data/colors-animated-8bpc-audio.avif", 150, 150, 8, AvifPixelFormat.I420, false, true, 5),
            decode("libavif-test-data/colors-animated-8bpc-depth-exif-xmp.avif", 150, 150, 8, AvifPixelFormat.I420, false, true, 5),
            decode("libavif-test-data/colors-animated-8bpc.avif", 150, 150, 8, AvifPixelFormat.I420, false, true, 5),
            decode("libavif-test-data/colors_hdr_p3.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_hdr_rec2020.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_hdr_srgb.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_sdr_srgb.avif", 200, 200, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_hdr_p3.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_hdr_rec2020.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_hdr_srgb.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_sdr_srgb.avif", 200, 200, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_wcg_hdr_rec2020.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_text_wcg_sdr_rec2020.avif", 200, 200, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/colors_wcg_hdr_rec2020.avif", 200, 200, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/draw_points_idat.avif", 33, 11, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/draw_points_idat_metasize0.avif", 33, 11, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/draw_points_idat_progressive.avif", 33, 11, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/draw_points_idat_progressive_metasize0.avif", 33, 11, 8, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/extended_pixi.avif", 4, 4, 8, AvifPixelFormat.I420, false, false, 1),
            decode("libavif-test-data/io/cosmos1650_yuv444_10bpc_p3pq.avif", 1024, 428, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/io/kodim03_yuv420_8bpc.avif", 768, 512, 8, AvifPixelFormat.I420, false, false, 1),
            decode("libavif-test-data/io/kodim23_yuv420_8bpc.avif", 768, 512, 8, AvifPixelFormat.I420, false, false, 1),
            decode("libavif-test-data/paris_icc_exif_xmp.avif", 403, 302, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_hdr_gainmap_small_srgb.avif", 400, 300, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_hdr_gainmap_srgb.avif", 400, 300, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_hdr_gainmap_wrongaltr.avif", 400, 300, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_hdr_rec2020.avif", 400, 300, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_hdr_srgb.avif", 400, 300, 10, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_sdr_gainmap_big_srgb.avif", 400, 300, 8, AvifPixelFormat.I444, false, false, 1),
            parseFailure("libavif-test-data/seine_sdr_gainmap_gammazero.avif", AvifErrorCode.BMFF_PARSE_FAILED),
            decode("libavif-test-data/seine_sdr_gainmap_notmapbrand.avif", 400, 300, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_sdr_gainmap_srgb.avif", 400, 300, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/seine_sdr_gainmap_srgb_icc.avif", 400, 300, 8, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/sofa_grid1x5_420.avif", 1024, 770, 8, AvifPixelFormat.I420, false, false, 1),
            parseFailure("libavif-test-data/sofa_grid1x5_420_dimg_repeat.avif", AvifErrorCode.BMFF_PARSE_FAILED),
            decode("libavif-test-data/sofa_grid1x5_420_reversed_dimg_order.avif", 1024, 770, 8, AvifPixelFormat.I420, false, false, 1),
            parseFailure("libavif-test-data/supported_gainmap_writer_version_with_extra_bytes.avif", AvifErrorCode.BMFF_PARSE_FAILED),
            decode("libavif-test-data/unsupported_gainmap_minimum_version.avif", 100, 100, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/unsupported_gainmap_version.avif", 100, 100, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/unsupported_gainmap_writer_version_with_extra_bytes.avif", 100, 100, 10, AvifPixelFormat.I444, true, false, 1),
            decode("libavif-test-data/weld_sato_12B_8B_q0.avif", 1024, 684, 12, AvifPixelFormat.I444, false, false, 1),
            decode("libavif-test-data/white_1x1.avif", 1, 1, 8, AvifPixelFormat.I444, false, false, 1),
    };

    /// Verifies that every copied libavif AVIF resource has an explicit test expectation.
    ///
    /// @throws IOException        if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @Test
    void allAvifResourcesHaveExplicitExpectations() throws IOException, URISyntaxException {
        List<String> expected = Arrays.stream(CASES)
                .map(testCase -> testCase.resourceName)
                .sorted()
                .toList();
        assertEquals(expected, avifResourceNames());
    }

    /// Creates one dynamic test per copied libavif AVIF resource.
    ///
    /// @return the dynamic corpus tests
    @TestFactory
    Stream<DynamicTest> libavifAvifResourcesMatchExpectedReaderBehavior() {
        return Arrays.stream(CASES)
                .map(testCase -> DynamicTest.dynamicTest(testCase.resourceName, () -> assertCorpusCase(testCase)));
    }

    /// Verifies grid alpha resources produce non-opaque decoded pixels.
    ///
    /// @throws IOException if a resource cannot be read or decoded
    @Test
    void gridAlphaFixturesProduceAlphaPixels() throws IOException {
        assertHasNonOpaqueAlpha("libavif-test-data/color_grid_alpha_nogrid.avif");
        assertHasNonOpaqueAlpha("libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif");
        assertHasNonOpaqueAlpha("libavif-test-data/color_grid_gainmap_different_grid.avif");
    }

    /// Asserts one corpus case.
    ///
    /// @param testCase the expected behavior to assert
    /// @throws IOException if the resource cannot be read or decoded
    private static void assertCorpusCase(CorpusCase testCase) throws IOException {
        byte[] bytes = testResourceBytes(testCase.resourceName);
        if (testCase.parseFailureCode != null) {
            AvifDecodeException exception = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(bytes));
            assertEquals(testCase.parseFailureCode, exception.code());
            if (testCase.parseFailureMessageFragment != null) {
                assertTrue(exception.getMessage().contains(testCase.parseFailureMessageFragment), exception.getMessage());
            }
            return;
        }

        ExpectedInfo expectedInfo = Objects.requireNonNull(testCase.expectedInfo, "expectedInfo");
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            assertInfo(expectedInfo, reader.info());
            if (testCase.decodeFailureCode != null) {
                AvifDecodeException exception = assertThrows(AvifDecodeException.class, reader::readFrame);
                assertEquals(testCase.decodeFailureCode, exception.code());
            } else if (expectedInfo.animated) {
                List<AvifFrame> frames = reader.readAllFrames();
                assertEquals(expectedInfo.frameCount, frames.size());
                for (int i = 0; i < frames.size(); i++) {
                    assertFrame(expectedInfo, i, frames.get(i));
                }
                assertNull(reader.readFrame());
            } else {
                AvifFrame frame = reader.readFrame();
                assertNotNull(frame);
                assertFrame(expectedInfo, 0, frame);
                assertNull(reader.readFrame());
            }
        }
    }

    /// Asserts parsed AVIF metadata.
    ///
    /// @param expected the expected metadata
    /// @param actual   the actual metadata
    private static void assertInfo(ExpectedInfo expected, AvifImageInfo actual) {
        assertEquals(expected.width, actual.width());
        assertEquals(expected.height, actual.height());
        assertEquals(AvifBitDepth.fromBits(expected.bitDepth), actual.bitDepth());
        assertEquals(expected.pixelFormat, actual.pixelFormat());
        assertEquals(expected.alphaPresent, actual.alphaPresent());
        assertEquals(expected.animated, actual.animated());
        assertEquals(expected.frameCount, actual.frameCount());
    }

    /// Asserts a decoded frame against expected image metadata.
    ///
    /// @param expected   the expected metadata
    /// @param frameIndex the expected frame index
    /// @param actual     the decoded frame
    private static void assertFrame(ExpectedInfo expected, int frameIndex, AvifFrame actual) {
        assertTrue(actual.width() > 0);
        assertTrue(actual.height() > 0);
        assertEquals(AvifBitDepth.fromBits(expected.bitDepth), actual.bitDepth());
        assertEquals(expected.pixelFormat, actual.pixelFormat());
        assertEquals(frameIndex, actual.frameIndex());
    }

    /// Asserts that a decoded fixture contains at least one non-opaque alpha sample.
    ///
    /// @param resourceName the classpath resource name
    /// @throws IOException if the resource cannot be read or decoded
    private static void assertHasNonOpaqueAlpha(String resourceName) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(resourceName))) {
            assertTrue(reader.info().alphaPresent());
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            if (frame.bitDepth().isEightBit()) {
                assertTrue(hasNonOpaqueIntAlpha(frame));
            } else {
                assertTrue(hasNonOpaqueLongAlpha(frame));
                assertTrue(hasWideAlphaRange(frame));
            }
        }
    }

    /// Returns whether an 8-bit frame has at least one non-opaque alpha sample.
    ///
    /// @param frame the decoded frame
    /// @return whether a non-opaque alpha sample is present
    private static boolean hasNonOpaqueIntAlpha(AvifFrame frame) {
        var pixels = frame.intPixelBuffer();
        while (pixels.hasRemaining()) {
            if ((pixels.get() >>> 24) != 0xFF) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether a high-bit-depth frame has at least one non-opaque alpha sample.
    ///
    /// @param frame the decoded frame
    /// @return whether a non-opaque alpha sample is present
    private static boolean hasNonOpaqueLongAlpha(AvifFrame frame) {
        var pixels = frame.longPixelBuffer();
        while (pixels.hasRemaining()) {
            if (((pixels.get() >>> 48) & 0xFFFFL) != 0xFFFFL) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether high-bit-depth alpha uses the full 16-bit output channel domain.
    ///
    /// @param frame the decoded frame
    /// @return whether alpha includes a value near full opacity
    private static boolean hasWideAlphaRange(AvifFrame frame) {
        var pixels = frame.longPixelBuffer();
        long maxAlpha = 0;
        while (pixels.hasRemaining()) {
            maxAlpha = Math.max(maxAlpha, (pixels.get() >>> 48) & 0xFFFFL);
        }
        return maxAlpha > 60_000L;
    }

    /// Lists copied libavif AVIF resources.
    ///
    /// @return sorted AVIF resource names
    /// @throws IOException        if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static List<String> avifResourceNames() throws IOException, URISyntaxException {
        URL resource = LibavifTestDataCorpusTest.class.getClassLoader().getResource(TEST_DATA_ROOT);
        if (resource == null) {
            throw new AssertionError("Missing test resource directory: " + TEST_DATA_ROOT);
        }
        Path root = Path.of(resource.toURI());
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".avif"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .map(path -> TEST_DATA_ROOT + "/" + root.relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
    }

    /// Loads one test resource.
    ///
    /// @param resourceName the classpath resource name
    /// @return the resource bytes
    /// @throws IOException if the resource cannot be read
    private static byte[] testResourceBytes(String resourceName) throws IOException {
        try (InputStream input = LibavifTestDataCorpusTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /// Creates a corpus case that must parse and decode successfully.
    ///
    /// @param resourceName the classpath resource name
    /// @param width        the expected width
    /// @param height       the expected height
    /// @param bitDepth     the expected bit depth
    /// @param pixelFormat  the expected pixel format
    /// @param alphaPresent whether alpha is expected
    /// @param animated     whether animation is expected
    /// @param frameCount   the expected frame count
    /// @return the corpus case
    private static CorpusCase decode(
            String resourceName,
            int width,
            int height,
            int bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount
    ) {
        return new CorpusCase(
                resourceName,
                new ExpectedInfo(width, height, bitDepth, pixelFormat, alphaPresent, animated, frameCount),
                null,
                null,
                null
        );
    }

    /// Creates a corpus case that must parse but fail during frame decode.
    ///
    /// @param resourceName      the classpath resource name
    /// @param width             the expected width
    /// @param height            the expected height
    /// @param bitDepth          the expected bit depth
    /// @param pixelFormat       the expected pixel format
    /// @param alphaPresent      whether alpha is expected
    /// @param animated          whether animation is expected
    /// @param frameCount        the expected frame count
    /// @param decodeFailureCode the expected decode failure code
    /// @return the corpus case
    private static CorpusCase decodeFailure(
            String resourceName,
            int width,
            int height,
            int bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            AvifErrorCode decodeFailureCode
    ) {
        return new CorpusCase(
                resourceName,
                new ExpectedInfo(width, height, bitDepth, pixelFormat, alphaPresent, animated, frameCount),
                null,
                null,
                decodeFailureCode
        );
    }

    /// Creates a corpus case that must fail during container parsing.
    ///
    /// @param resourceName     the classpath resource name
    /// @param parseFailureCode the expected parse failure code
    /// @return the corpus case
    private static CorpusCase parseFailure(String resourceName, AvifErrorCode parseFailureCode) {
        return parseFailure(resourceName, parseFailureCode, null);
    }

    /// Creates a corpus case that must fail during container parsing with a diagnostic fragment.
    ///
    /// @param resourceName                the classpath resource name
    /// @param parseFailureCode            the expected parse failure code
    /// @param parseFailureMessageFragment the expected diagnostic message fragment
    /// @return the corpus case
    private static CorpusCase parseFailure(
            String resourceName,
            AvifErrorCode parseFailureCode,
            @Nullable String parseFailureMessageFragment
    ) {
        return new CorpusCase(resourceName, null, parseFailureCode, parseFailureMessageFragment, null);
    }

    /// Expected behavior for one libavif AVIF fixture.
    ///
    /// @param resourceName      The classpath resource name.
    /// @param expectedInfo      The expected parsed image info, or `null` when parsing must fail.
    /// @param parseFailureCode  The expected parse failure code, or `null` when parsing must succeed.
    /// @param parseFailureMessageFragment The expected parse-failure diagnostic fragment, or `null`.
    /// @param decodeFailureCode           The expected frame decode failure code, or `null` when frame decoding must succeed.
    @NotNullByDefault
    private record CorpusCase(String resourceName, @Nullable ExpectedInfo expectedInfo,
                              @Nullable AvifErrorCode parseFailureCode,
                              @Nullable String parseFailureMessageFragment,
                              @Nullable AvifErrorCode decodeFailureCode) {

    }

    /// Expected parsed image metadata for one libavif AVIF fixture.
    ///
    /// @param width        The expected width.
    /// @param height       The expected height.
    /// @param bitDepth     The expected bit depth.
    /// @param pixelFormat  The expected pixel format.
    /// @param alphaPresent Whether alpha is expected.
    /// @param animated     Whether animation is expected.
    /// @param frameCount   The expected frame count.
    @NotNullByDefault
    private record ExpectedInfo(int width, int height, int bitDepth, AvifPixelFormat pixelFormat, boolean alphaPresent,
                                boolean animated, int frameCount) {
    }
}
