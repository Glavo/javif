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
    /// AVIF resources whose exact FFmpeg first-frame comparison currently passes.
    private static final String @Unmodifiable [] ENABLED_REFERENCE_RESOURCES = new String[]{
            "libavif-test-data/white_1x1.avif",
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
            disabledReference(
                    "libavif-test-data/color_grid_alpha_grid_tile_shared_in_dimg.avif",
                    "javif currently rejects the shared alpha-grid tile dependency as unsupported."
            ),
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
        if (!isEnabledReference(reference.resourceName())) {
            Assumptions.assumeTrue(false, "Pending exact FFmpeg first-frame parity for this fixture.");
        }
        assertFirstFrameMatchesFFmpegReferenceExactly(reference);
    }

    /// Asserts that javif and FFmpeg render the first frame of one AVIF resource identically.
    ///
    /// @param reference the FFmpeg reference case
    /// @throws IOException if a resource cannot be read or decoded
    /// @throws URISyntaxException if the FFmpeg reference resource cannot be resolved
    private static void assertFirstFrameMatchesFFmpegReferenceExactly(FFmpegReferenceCase reference)
            throws IOException, URISyntaxException {
        String resourceName = reference.resourceName();
        ArgbImage expected = FFmpegAvifReferenceDecoder.decodeFirstFrameArgb(resourceName);
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            assertImageInfoMatchesFFmpegMetadata(reader.info(), expected);
            AvifFrame actual = reader.readFrame();
            assertNotNull(actual);
            assertFrameMetadataMatchesFFmpegMetadata(actual, expected);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            assertPixelsMatch(resourceName, expected, actual.intPixelBuffer());
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

    /// Returns whether one FFmpeg reference case is currently enabled.
    ///
    /// @param resourceName the AVIF resource name
    /// @return whether exact FFmpeg first-frame comparison currently passes
    private static boolean isEnabledReference(String resourceName) {
        return Arrays.asList(ENABLED_REFERENCE_RESOURCES).contains(resourceName);
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

    /// FFmpeg reference expectation for one libavif AVIF fixture.
    ///
    /// @param resourceName the AVIF resource name
    /// @param disabledReason the disabled reason, or `null` when enabled
    @NotNullByDefault
    private record FFmpegReferenceCase(String resourceName, @Nullable String disabledReason) {
    }
}
