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
import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder.SourcePlanes;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelTolerance;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Tests that compare javif decoded AVIF frames against FFmpeg-decoded reference pixels.
@NotNullByDefault
final class LibavifFFmpegAvifReferenceTest {
    /// The copied libavif test data resource root.
    private static final String TEST_DATA_ROOT = "libavif-test-data";
    /// AVIF resources whose FFmpeg first-frame pixel comparison currently passes.
    private static final FFmpegPixelReference @Unmodifiable [] ENABLED_PIXEL_REFERENCE_RESOURCES = new FFmpegPixelReference[]{
            pixelReference("libavif-test-data/colors_sdr_srgb.avif", PixelTolerance.bounded(1, 0.003, 0.004, 0.06)),
            pixelReference("libavif-test-data/paris_icc_exif_xmp.avif", PixelTolerance.bounded(9, 0.0, 0.8, 1.0)),
            pixelReference("libavif-test-data/white_1x1.avif", PixelTolerance.perPixelDelta(0)),
    };
    /// AVIF resources whose raw decoded planes match FFmpeg source planes exactly.
    private static final String @Unmodifiable [] ENABLED_SOURCE_PLANE_REFERENCE_RESOURCES = new String[]{
            "libavif-test-data/abc_color_irot_alpha_NOirot.avif",
            "libavif-test-data/abc_color_irot_alpha_irot.avif",
            "libavif-test-data/colors-animated-8bpc-audio.avif",
            "libavif-test-data/colors-animated-8bpc.avif",
            "libavif-test-data/colors_sdr_srgb.avif",
            "libavif-test-data/colors_text_sdr_srgb.avif",
            "libavif-test-data/draw_points_idat.avif",
            "libavif-test-data/draw_points_idat_metasize0.avif",
            "libavif-test-data/extended_pixi.avif",
            "libavif-test-data/white_1x1.avif",
    };
    /// AVIF resources whose source metadata comparison against FFmpeg currently passes.
    private static final String @Unmodifiable [] ENABLED_METADATA_REFERENCE_RESOURCES = new String[]{
            "libavif-test-data/abc_color_irot_alpha_NOirot.avif",
            "libavif-test-data/abc_color_irot_alpha_irot.avif",
            "libavif-test-data/alpha_noispe.avif",
            "libavif-test-data/circle_custom_properties.avif",
            "libavif-test-data/clop_irot_imor.avif",
            "libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif",
            "libavif-test-data/color_grid_alpha_grid_tile_shared_in_dimg.avif",
            "libavif-test-data/color_grid_alpha_nogrid.avif",
            "libavif-test-data/color_grid_gainmap_different_grid.avif",
            "libavif-test-data/color_nogrid_alpha_nogrid_gainmap_grid.avif",
            "libavif-test-data/colors-animated-12bpc-keyframes-0-2-3.avif",
            "libavif-test-data/colors-animated-8bpc-alpha-exif-xmp.avif",
            "libavif-test-data/colors-animated-8bpc-audio.avif",
            "libavif-test-data/colors-animated-8bpc-depth-exif-xmp.avif",
            "libavif-test-data/colors-animated-8bpc.avif",
            "libavif-test-data/colors_hdr_p3.avif",
            "libavif-test-data/colors_hdr_rec2020.avif",
            "libavif-test-data/colors_hdr_srgb.avif",
            "libavif-test-data/colors_sdr_srgb.avif",
            "libavif-test-data/colors_text_hdr_p3.avif",
            "libavif-test-data/colors_text_hdr_rec2020.avif",
            "libavif-test-data/colors_text_hdr_srgb.avif",
            "libavif-test-data/colors_text_sdr_srgb.avif",
            "libavif-test-data/colors_text_wcg_hdr_rec2020.avif",
            "libavif-test-data/colors_text_wcg_sdr_rec2020.avif",
            "libavif-test-data/colors_wcg_hdr_rec2020.avif",
            "libavif-test-data/draw_points_idat.avif",
            "libavif-test-data/draw_points_idat_metasize0.avif",
            "libavif-test-data/extended_pixi.avif",
            "libavif-test-data/io/cosmos1650_yuv444_10bpc_p3pq.avif",
            "libavif-test-data/io/kodim03_yuv420_8bpc.avif",
            "libavif-test-data/io/kodim23_yuv420_8bpc.avif",
            "libavif-test-data/paris_icc_exif_xmp.avif",
            "libavif-test-data/seine_hdr_gainmap_small_srgb.avif",
            "libavif-test-data/seine_hdr_gainmap_srgb.avif",
            "libavif-test-data/seine_hdr_gainmap_wrongaltr.avif",
            "libavif-test-data/seine_hdr_rec2020.avif",
            "libavif-test-data/seine_hdr_srgb.avif",
            "libavif-test-data/seine_sdr_gainmap_big_srgb.avif",
            "libavif-test-data/seine_sdr_gainmap_notmapbrand.avif",
            "libavif-test-data/seine_sdr_gainmap_srgb.avif",
            "libavif-test-data/seine_sdr_gainmap_srgb_icc.avif",
            "libavif-test-data/sofa_grid1x5_420.avif",
            "libavif-test-data/sofa_grid1x5_420_reversed_dimg_order.avif",
            "libavif-test-data/unsupported_gainmap_minimum_version.avif",
            "libavif-test-data/unsupported_gainmap_version.avif",
            "libavif-test-data/unsupported_gainmap_writer_version_with_extra_bytes.avif",
            "libavif-test-data/weld_sato_12B_8B_q0.avif",
            "libavif-test-data/white_1x1.avif",
    };
    /// AVIF resources where FFmpeg exposes a derived tile or source image instead of javif's composed output size.
    private static final String @Unmodifiable [] FFMPEG_DERIVED_DIMENSION_REFERENCE_RESOURCES = new String[]{
            "libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif",
            "libavif-test-data/color_grid_gainmap_different_grid.avif",
            "libavif-test-data/sofa_grid1x5_420.avif",
            "libavif-test-data/sofa_grid1x5_420_reversed_dimg_order.avif",
    };

    /// FFmpeg reference expectations for every copied libavif AVIF resource.
    private static final FFmpegReferenceCase @Unmodifiable [] AVIF_REFERENCES = new FFmpegReferenceCase[]{
            reference("libavif-test-data/abc_color_irot_alpha_NOirot.avif"),
            reference("libavif-test-data/abc_color_irot_alpha_irot.avif"),
            reference("libavif-test-data/alpha_noispe.avif"),
            reference("libavif-test-data/arc_triomphe_extent1000_nullbyte_extent1310.avif"),
            reference("libavif-test-data/circle_custom_properties.avif"),
            disabledReference(
                    "libavif-test-data/clap_irot_imir_non_essential.avif",
                    "javif intentionally rejects the non-essential clap association before frame decode."
            ),
            reference("libavif-test-data/clop_irot_imor.avif"),
            reference("libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif"),
            reference("libavif-test-data/color_grid_alpha_grid_tile_shared_in_dimg.avif"),
            reference("libavif-test-data/color_grid_alpha_nogrid.avif"),
            reference("libavif-test-data/color_grid_gainmap_different_grid.avif"),
            reference("libavif-test-data/color_nogrid_alpha_nogrid_gainmap_grid.avif"),
            reference("libavif-test-data/colors-animated-12bpc-keyframes-0-2-3.avif"),
            reference("libavif-test-data/colors-animated-8bpc-alpha-exif-xmp.avif"),
            reference("libavif-test-data/colors-animated-8bpc-audio.avif"),
            reference("libavif-test-data/colors-animated-8bpc-depth-exif-xmp.avif"),
            reference("libavif-test-data/colors-animated-8bpc.avif"),
            reference("libavif-test-data/colors_hdr_p3.avif"),
            reference("libavif-test-data/colors_hdr_rec2020.avif"),
            reference("libavif-test-data/colors_hdr_srgb.avif"),
            reference("libavif-test-data/colors_sdr_srgb.avif"),
            reference("libavif-test-data/colors_text_hdr_p3.avif"),
            reference("libavif-test-data/colors_text_hdr_rec2020.avif"),
            reference("libavif-test-data/colors_text_hdr_srgb.avif"),
            reference("libavif-test-data/colors_text_sdr_srgb.avif"),
            reference("libavif-test-data/colors_text_wcg_hdr_rec2020.avif"),
            reference("libavif-test-data/colors_text_wcg_sdr_rec2020.avif"),
            reference("libavif-test-data/colors_wcg_hdr_rec2020.avif"),
            reference("libavif-test-data/draw_points_idat.avif"),
            reference("libavif-test-data/draw_points_idat_metasize0.avif"),
            reference("libavif-test-data/draw_points_idat_progressive.avif"),
            reference("libavif-test-data/draw_points_idat_progressive_metasize0.avif"),
            reference("libavif-test-data/extended_pixi.avif"),
            reference("libavif-test-data/io/cosmos1650_yuv444_10bpc_p3pq.avif"),
            reference("libavif-test-data/io/kodim03_yuv420_8bpc.avif"),
            reference("libavif-test-data/io/kodim23_yuv420_8bpc.avif"),
            reference("libavif-test-data/paris_icc_exif_xmp.avif"),
            reference("libavif-test-data/seine_hdr_gainmap_small_srgb.avif"),
            reference("libavif-test-data/seine_hdr_gainmap_srgb.avif"),
            reference("libavif-test-data/seine_hdr_gainmap_wrongaltr.avif"),
            reference("libavif-test-data/seine_hdr_rec2020.avif"),
            reference("libavif-test-data/seine_hdr_srgb.avif"),
            reference("libavif-test-data/seine_sdr_gainmap_big_srgb.avif"),
            disabledReference(
                    "libavif-test-data/seine_sdr_gainmap_gammazero.avif",
                    "javif currently rejects the invalid gain-map gamma metadata before frame decode."
            ),
            reference("libavif-test-data/seine_sdr_gainmap_notmapbrand.avif"),
            reference("libavif-test-data/seine_sdr_gainmap_srgb.avif"),
            reference("libavif-test-data/seine_sdr_gainmap_srgb_icc.avif"),
            reference("libavif-test-data/sofa_grid1x5_420.avif"),
            disabledReference(
                    "libavif-test-data/sofa_grid1x5_420_dimg_repeat.avif",
                    "javif currently rejects the repeated dimg grid dependency before frame decode."
            ),
            reference("libavif-test-data/sofa_grid1x5_420_reversed_dimg_order.avif"),
            disabledReference(
                    "libavif-test-data/supported_gainmap_writer_version_with_extra_bytes.avif",
                    "javif currently rejects the gain-map metadata with trailing bytes before frame decode."
            ),
            reference("libavif-test-data/unsupported_gainmap_minimum_version.avif"),
            reference("libavif-test-data/unsupported_gainmap_version.avif"),
            reference("libavif-test-data/unsupported_gainmap_writer_version_with_extra_bytes.avif"),
            reference("libavif-test-data/weld_sato_12B_8B_q0.avif"),
            reference("libavif-test-data/white_1x1.avif"),
    };

    /// Verifies that every copied libavif AVIF resource has an explicit FFmpeg reference expectation.
    ///
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @Test
    void allAvifResourcesHaveFFmpegReferenceExpectations() throws IOException, URISyntaxException {
        List<String> expected = Arrays.stream(AVIF_REFERENCES)
                .map(FFmpegReferenceCase::resourceName)
                .sorted()
                .toList();
        assertEquals(expected, avifResourceNames());
    }

    /// Verifies enabled libavif AVIF fixtures against FFmpeg first-frame output.
    ///
    /// @return the dynamic FFmpeg reference tests
    @TestFactory
    Stream<DynamicTest> libavifAvifResourcesMatchFFmpegFirstFrames() {
        return Arrays.stream(AVIF_REFERENCES)
                .map(reference -> DynamicTest.dynamicTest(
                        reference.resourceName(),
                        () -> assertFFmpegReferenceCase(reference)
                ));
    }

    /// Verifies enabled libavif AVIF fixtures against FFmpeg source metadata.
    ///
    /// @return the dynamic FFmpeg metadata reference tests
    @TestFactory
    Stream<DynamicTest> libavifAvifResourcesMatchFFmpegSourceMetadata() {
        return Arrays.stream(AVIF_REFERENCES)
                .map(reference -> DynamicTest.dynamicTest(
                        reference.resourceName(),
                        () -> assertFFmpegMetadataReferenceCase(reference)
                ));
    }

    /// Verifies enabled libavif AVIF fixtures against FFmpeg decoded source planes.
    ///
    /// @return the dynamic FFmpeg source-plane reference tests
    @TestFactory
    Stream<DynamicTest> libavifAvifResourcesMatchFFmpegSourcePlanes() {
        return Arrays.stream(AVIF_REFERENCES)
                .map(reference -> DynamicTest.dynamicTest(
                        reference.resourceName(),
                        () -> assertFFmpegSourcePlaneReferenceCase(reference)
                ));
    }

    /// Asserts one FFmpeg reference case.
    ///
    /// @param reference the reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFFmpegReferenceCase(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        if (reference.disabledReason() != null) {
            Assumptions.assumeTrue(false, reference.disabledReason());
        }
        @Nullable PixelTolerance tolerance = pixelTolerance(reference.resourceName());
        if (tolerance == null) {
            Assumptions.assumeTrue(false, "Pending FFmpeg first-frame pixel parity for this fixture.");
        }
        assertFirstFrameMatchesFFmpegReference(reference, tolerance);
    }

    /// Asserts one FFmpeg source metadata reference case.
    ///
    /// @param reference the reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFFmpegMetadataReferenceCase(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        if (reference.disabledReason() != null) {
            Assumptions.assumeTrue(false, reference.disabledReason());
        }
        if (!isEnabledMetadataReference(reference.resourceName())) {
            Assumptions.assumeTrue(false, "Pending FFmpeg source metadata parity for this fixture.");
        }
        assertFirstFrameMetadataMatchesFFmpegReference(reference);
    }

    /// Asserts one FFmpeg source-plane reference case.
    ///
    /// @param reference the reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFFmpegSourcePlaneReferenceCase(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        if (reference.disabledReason() != null) {
            Assumptions.assumeTrue(false, reference.disabledReason());
        }
        if (!isEnabledSourcePlaneReference(reference.resourceName())) {
            Assumptions.assumeTrue(false, "Pending FFmpeg source-plane parity for this fixture.");
        }
        assertFirstFrameSourcePlanesMatchFFmpegReference(reference);
    }

    /// Asserts that javif and FFmpeg render the first frame of one AVIF resource within tolerance.
    ///
    /// @param reference the FFmpeg reference case
    /// @param tolerance the accepted FFmpeg pixel tolerance
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFirstFrameMatchesFFmpegReference(
            FFmpegReferenceCase reference,
            PixelTolerance tolerance
    )
            throws IOException, URISyntaxException {
        String resourceName = reference.resourceName();
        ArgbImage rawExpected = FFmpegAvifReferenceDecoder.decodeFirstFrameArgb(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            ArgbImage expected = rawExpected.transformed(reader.info().rotationCode(), reader.info().mirrorAxis());
            assertImageInfoMatchesFFmpegMetadata(reader.info(), expected);
            AvifFrame actual = reader.readFrame();
            assertNotNull(actual);
            assertFrameMetadataMatchesFFmpegMetadata(actual, expected);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            assertPixelsMatch(resourceName, expected, actual.intPixelBuffer(), tolerance);
        }
    }

    /// Asserts that javif and FFmpeg agree on source metadata for the first frame of one AVIF resource.
    ///
    /// @param reference the FFmpeg reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFirstFrameMetadataMatchesFFmpegReference(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        String resourceName = reference.resourceName();
        ArgbImage rawExpected = FFmpegAvifReferenceDecoder.decodeFirstFrameArgb(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            boolean dimensionsComparable = !hasDerivedFFmpegDimensions(resourceName);
            if (dimensionsComparable) {
                assertEquals(rawExpected.width(), reader.info().width());
                assertEquals(rawExpected.height(), reader.info().height());
            }
            assertImageInfoMatchesFFmpegMetadata(reader.info(), rawExpected);

            AvifFrame actual = reader.readFrame();
            assertNotNull(actual);
            ArgbImage transformedExpected =
                    rawExpected.transformed(reader.info().rotationCode(), reader.info().mirrorAxis());
            assertFrameMetadataMatchesFFmpegMetadata(actual, transformedExpected);
            if (dimensionsComparable) {
                assertEquals(transformedExpected.width(), actual.width());
                assertEquals(transformedExpected.height(), actual.height());
            }
        }
    }

    /// Asserts that javif raw decoded color planes match FFmpeg source planes exactly.
    ///
    /// @param reference the FFmpeg reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFirstFrameSourcePlanesMatchFFmpegReference(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        String resourceName = reference.resourceName();
        SourcePlanes expected = FFmpegAvifReferenceDecoder.decodeFirstFrameSourcePlanes(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifPlanes actual = reader.readRawColorPlanes(0);
            assertEquals(expected.width(), actual.codedWidth());
            assertEquals(expected.height(), actual.codedHeight());
            assertEquals(expected.sourceMetadata().bitDepth(), actual.bitDepth(), ffmpegSourcePlaneMessage(expected));
            assertEquals(expected.sourceMetadata().pixelFormat(), actual.pixelFormat(), ffmpegSourcePlaneMessage(expected));

            assertPlaneMatches(
                    resourceName + " Y",
                    expected.width(),
                    expected.height(),
                    actual.lumaPlane(),
                    expected::lumaSample
            );
            if (expected.sourceMetadata().pixelFormat() != AvifPixelFormat.I400) {
                AvifPlane chromaUPlane = actual.chromaUPlane();
                AvifPlane chromaVPlane = actual.chromaVPlane();
                assertNotNull(chromaUPlane);
                assertNotNull(chromaVPlane);
                assertPlaneMatches(
                        resourceName + " U",
                        expected.chromaWidth(),
                        expected.chromaHeight(),
                        chromaUPlane,
                        expected::chromaUSample
                );
                assertPlaneMatches(
                        resourceName + " V",
                        expected.chromaWidth(),
                        expected.chromaHeight(),
                        chromaVPlane,
                        expected::chromaVSample
                );
            }
        }
    }

    /// Asserts parsed javif metadata against normalized source FFmpeg metadata.
    ///
    /// @param actual the parsed javif image metadata
    /// @param expected the FFmpeg reference image
    private static void assertImageInfoMatchesFFmpegMetadata(AvifImageInfo actual, ArgbImage expected) {
        assertEquals(expected.sourceMetadata().bitDepth(), actual.bitDepth(), ffmpegPixelFormatMessage(expected));
        assertEquals(expected.sourceMetadata().pixelFormat(), actual.pixelFormat(), ffmpegPixelFormatMessage(expected));
    }

    /// Asserts decoded javif frame metadata against normalized source FFmpeg metadata.
    ///
    /// @param actual the decoded javif frame
    /// @param expected the FFmpeg reference image
    private static void assertFrameMetadataMatchesFFmpegMetadata(AvifFrame actual, ArgbImage expected) {
        assertEquals(expected.sourceMetadata().bitDepth(), actual.bitDepth(), ffmpegPixelFormatMessage(expected));
        assertEquals(expected.sourceMetadata().pixelFormat(), actual.pixelFormat(), ffmpegPixelFormatMessage(expected));
    }

    /// Returns a diagnostic label for FFmpeg source pixel format comparisons.
    ///
    /// @param expected the FFmpeg reference image
    /// @return the diagnostic label
    private static String ffmpegPixelFormatMessage(ArgbImage expected) {
        return "FFmpeg source pixel format: " + expected.sourceMetadata().pixelFormatName();
    }

    /// Returns a diagnostic label for FFmpeg source-plane comparisons.
    ///
    /// @param expected the FFmpeg source planes
    /// @return the diagnostic label
    private static String ffmpegSourcePlaneMessage(SourcePlanes expected) {
        return "FFmpeg source pixel format: " + expected.sourceMetadata().pixelFormatName();
    }

    /// Lists copied libavif AVIF resources.
    ///
    /// @return sorted AVIF resource names
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static List<String> avifResourceNames() throws IOException, URISyntaxException {
        URL resource = LibavifFFmpegAvifReferenceTest.class.getClassLoader().getResource(TEST_DATA_ROOT);
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
                    .sorted()
                    .toList();
        }
    }

    /// Asserts that one javif decoded plane matches one FFmpeg source plane exactly.
    ///
    /// @param label the diagnostic label
    /// @param expectedWidth the expected plane width
    /// @param expectedHeight the expected plane height
    /// @param actual the javif decoded plane
    /// @param expectedSample the FFmpeg source sample supplier
    private static void assertPlaneMatches(
            String label,
            int expectedWidth,
            int expectedHeight,
            AvifPlane actual,
            SourceSample expectedSample
    ) {
        assertEquals(expectedWidth, actual.width(), label + " width");
        assertEquals(expectedHeight, actual.height(), label + " height");
        for (int y = 0; y < expectedHeight; y++) {
            for (int x = 0; x < expectedWidth; x++) {
                int expected = expectedSample.sample(x, y);
                int sample = actual.sample(x, y);
                if (sample != expected) {
                    throw new AssertionError(label + " mismatch at (" + x + "," + y + "): expected "
                            + expected + ", actual " + sample);
                }
            }
        }
    }

    /// Asserts that packed ARGB pixels match an FFmpeg reference image within tolerance.
    ///
    /// @param label the diagnostic label
    /// @param expected the expected FFmpeg image
    /// @param actualPixels the actual packed ARGB pixels
    /// @param tolerance the accepted pixel tolerance
    private static void assertPixelsMatch(
            String label,
            ArgbImage expected,
            IntBuffer actualPixels,
            PixelTolerance tolerance
    ) {
        assertEquals(expected.width() * expected.height(), actualPixels.remaining());
        int largestChannelDelta = 0;
        int failingPixels = 0;
        long sumDelta = 0L;
        long sumSquaredDelta = 0L;
        @Nullable String firstMismatch = null;
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                int expectedArgb = expected.pixel(x, y);
                int actualArgb = actualPixels.get(y * expected.width() + x);
                int delta = displayRelevantChannelDelta(expectedArgb, actualArgb);
                largestChannelDelta = Math.max(largestChannelDelta, delta);
                sumDelta += delta;
                sumSquaredDelta += (long) delta * delta;
                if (delta > tolerance.maxChannelDelta()) {
                    failingPixels++;
                    if (firstMismatch == null) {
                        firstMismatch = "first mismatch at (" + x + "," + y + "): expected "
                                + argbText(expectedArgb) + ", actual " + argbText(actualArgb)
                                + ", max channel delta " + delta;
                    }
                }
            }
        }

        int pixelCount = expected.width() * expected.height();
        double failingRatio = (double) failingPixels / pixelCount;
        double meanDelta = (double) sumDelta / pixelCount;
        double rootMeanSquareDelta = Math.sqrt((double) sumSquaredDelta / pixelCount);
        if (failingRatio > tolerance.maxFailingPixelRatio()
                || meanDelta > tolerance.maxMeanChannelDelta()
                || rootMeanSquareDelta > tolerance.maxRootMeanSquareChannelDelta()) {
            throw new AssertionError(label + " pixel comparison failed: " + firstMismatch
                    + "; compared=" + pixelCount
                    + ", failing=" + failingPixels
                    + String.format(Locale.ROOT, " (%.6f)", failingRatio)
                    + ", largestDelta=" + largestChannelDelta
                    + String.format(Locale.ROOT, ", meanDelta=%.3f", meanDelta)
                    + String.format(Locale.ROOT, ", rmsDelta=%.3f", rootMeanSquareDelta)
                    + ", tolerance=maxDelta " + tolerance.maxChannelDelta()
                    + String.format(Locale.ROOT, ", maxFailingRatio %.6f", tolerance.maxFailingPixelRatio())
                    + String.format(Locale.ROOT, ", maxMean %.3f", tolerance.maxMeanChannelDelta())
                    + String.format(Locale.ROOT, ", maxRms %.3f", tolerance.maxRootMeanSquareChannelDelta()));
        }
    }

    /// Returns the largest display-relevant unsigned 8-bit channel delta between two packed ARGB pixels.
    ///
    /// @param expectedArgb the expected packed ARGB pixel
    /// @param actualArgb the actual packed ARGB pixel
    /// @return the largest display-relevant channel delta
    private static int displayRelevantChannelDelta(int expectedArgb, int actualArgb) {
        int alphaDelta = channelDelta(expectedArgb, actualArgb, 24);
        if (((expectedArgb >>> 24) & 0xFF) == 0 && ((actualArgb >>> 24) & 0xFF) == 0) {
            return alphaDelta;
        }

        return Math.max(
                Math.max(alphaDelta, channelDelta(expectedArgb, actualArgb, 16)),
                Math.max(channelDelta(expectedArgb, actualArgb, 8), channelDelta(expectedArgb, actualArgb, 0))
        );
    }

    /// Returns one unsigned 8-bit channel delta between two packed ARGB pixels.
    ///
    /// @param expectedArgb the expected packed ARGB pixel
    /// @param actualArgb the actual packed ARGB pixel
    /// @param shift the channel bit shift
    /// @return the unsigned 8-bit channel delta
    private static int channelDelta(int expectedArgb, int actualArgb, int shift) {
        return Math.abs(((expectedArgb >>> shift) & 0xFF) - ((actualArgb >>> shift) & 0xFF));
    }

    /// Formats one packed ARGB pixel.
    ///
    /// @param argb the packed pixel
    /// @return the formatted pixel
    private static String argbText(int argb) {
        return String.format(Locale.ROOT, "0x%08X", argb);
    }

    /// Returns the configured FFmpeg pixel tolerance for one reference case.
    ///
    /// @param resourceName the AVIF resource name
    /// @return the accepted pixel tolerance, or `null` when the fixture is not enabled for pixel comparison
    private static @Nullable PixelTolerance pixelTolerance(String resourceName) {
        for (FFmpegPixelReference reference : ENABLED_PIXEL_REFERENCE_RESOURCES) {
            if (reference.resourceName().equals(resourceName)) {
                return reference.tolerance();
            }
        }
        return null;
    }

    /// Returns whether one FFmpeg source metadata reference case is currently enabled.
    ///
    /// @param resourceName the AVIF resource name
    /// @return whether FFmpeg source metadata comparison currently passes
    private static boolean isEnabledMetadataReference(String resourceName) {
        return Arrays.asList(ENABLED_METADATA_REFERENCE_RESOURCES).contains(resourceName);
    }

    /// Returns whether one FFmpeg source-plane reference case is currently enabled.
    ///
    /// @param resourceName the AVIF resource name
    /// @return whether FFmpeg source-plane comparison currently passes
    private static boolean isEnabledSourcePlaneReference(String resourceName) {
        return Arrays.asList(ENABLED_SOURCE_PLANE_REFERENCE_RESOURCES).contains(resourceName);
    }

    /// Returns whether FFmpeg exposes derived input dimensions instead of javif composed output dimensions.
    ///
    /// @param resourceName the AVIF resource name
    /// @return whether FFmpeg dimensions are not comparable to javif output dimensions
    private static boolean hasDerivedFFmpegDimensions(String resourceName) {
        return Arrays.asList(FFMPEG_DERIVED_DIMENSION_REFERENCE_RESOURCES).contains(resourceName);
    }

    /// Creates an FFmpeg reference case.
    ///
    /// @param resourceName the AVIF resource name
    /// @return the reference case
    private static FFmpegReferenceCase reference(String resourceName) {
        return new FFmpegReferenceCase(resourceName, null);
    }

    /// Creates a disabled FFmpeg reference case.
    ///
    /// @param resourceName the AVIF resource name
    /// @param disabledReason the disabled reason
    /// @return the reference case
    private static FFmpegReferenceCase disabledReference(String resourceName, String disabledReason) {
        return new FFmpegReferenceCase(resourceName, disabledReason);
    }

    /// Creates an enabled FFmpeg pixel reference case.
    ///
    /// @param resourceName the AVIF resource name
    /// @param tolerance the accepted FFmpeg pixel tolerance
    /// @return the pixel reference case
    private static FFmpegPixelReference pixelReference(String resourceName, PixelTolerance tolerance) {
        return new FFmpegPixelReference(resourceName, tolerance);
    }

    /// Enabled FFmpeg pixel comparison settings for one fixture.
    ///
    /// @param resourceName the AVIF resource name
    /// @param tolerance the accepted FFmpeg pixel tolerance
    @NotNullByDefault
    private record FFmpegPixelReference(String resourceName, PixelTolerance tolerance) {
    }

    /// Supplies one expected FFmpeg source-plane sample.
    @FunctionalInterface
    private interface SourceSample {
        /// Returns one expected sample.
        ///
        /// @param x the zero-based sample X coordinate
        /// @param y the zero-based sample Y coordinate
        /// @return the expected unsigned sample value
        int sample(int x, int y);
    }

    /// FFmpeg reference expectation for one libavif AVIF fixture.
    ///
    /// @param resourceName the AVIF resource name
    /// @param disabledReason the disabled reason, or `null` when enabled
    @NotNullByDefault
    private record FFmpegReferenceCase(String resourceName, @Nullable String disabledReason) {
    }
}
