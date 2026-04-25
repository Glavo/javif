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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests that use Java ImageIO to read libavif PNG/JPEG reference resources.
@NotNullByDefault
final class LibavifImageIoReferenceTest {
    /// Image resources copied from libavif's test data and their expected dimensions.
    private static final ImageResource @Unmodifiable [] IMAGE_RESOURCES = new ImageResource[]{
            new ImageResource("libavif-test-data/abc.png", 512, 256, true),
            new ImageResource("libavif-test-data/apple_gainmap_new.jpg", 384, 512, false),
            new ImageResource("libavif-test-data/apple_gainmap_old.jpg", 384, 512, false),
            new ImageResource("libavif-test-data/ArcTriomphe-cHRM-orig.png", 64, 64, false),
            new ImageResource("libavif-test-data/ArcTriomphe-cHRM-red-green-swap-reference.png", 64, 64, false),
            new ImageResource("libavif-test-data/ArcTriomphe-cHRM-red-green-swap.png", 64, 64, false),
            new ImageResource("libavif-test-data/circle-trns-after-plte.png", 100, 60, true),
            new ImageResource("libavif-test-data/circle-trns-before-plte.png", 100, 60, false),
            new ImageResource("libavif-test-data/dog_exif_extended_xmp_icc.jpg", 4032, 3024, false),
            new ImageResource("libavif-test-data/draw_points.png", 33, 11, true),
            new ImageResource("libavif-test-data/ffffcc-gamma1.6.png", 48, 48, false),
            new ImageResource("libavif-test-data/ffffcc-gamma2.2.png", 48, 48, false),
            new ImageResource("libavif-test-data/ffffcc-srgb.png", 48, 48, false),
            new ImageResource("libavif-test-data/ffffff-gamma1.6.png", 48, 48, false),
            new ImageResource("libavif-test-data/ffffff-gamma2.2.png", 48, 48, false),
            new ImageResource("libavif-test-data/kodim03_grayscale_cicp.png", 768, 512, false),
            new ImageResource("libavif-test-data/kodim03_grayscale_gamma1.6-reference.png", 768, 512, false),
            new ImageResource("libavif-test-data/kodim03_grayscale_gamma1.6.png", 768, 512, false),
            new ImageResource("libavif-test-data/paris_exif_orientation_5.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_exif_xmp_gainmap_bigendian.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_exif_xmp_gainmap_littleendian.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_exif_xmp_icc_gainmap_bigendian.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_exif_xmp_icc.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_exif_xmp_modified_icc.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_extended_xmp.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/paris_icc_exif_xmp_at_end.png", 403, 302, false),
            new ImageResource("libavif-test-data/paris_icc_exif_xmp.png", 403, 302, false),
            new ImageResource("libavif-test-data/paris_xmp_trailing_null.jpg", 403, 302, false),
            new ImageResource("libavif-test-data/seine_sdr_different_gainmap_srgb.jpg", 400, 300, false),
            new ImageResource("libavif-test-data/seine_sdr_gainmap_srgb.jpg", 400, 300, false),
            new ImageResource("libavif-test-data/weld_16bit.png", 1024, 684, false),
    };

    /// Source-image to encoded-AVIF dimension relationships documented by libavif test data.
    private static final SourceAvifPair @Unmodifiable [] SOURCE_AVIF_PAIRS = new SourceAvifPair[]{
            new SourceAvifPair("libavif-test-data/abc.png", "libavif-test-data/abc_color_irot_alpha_irot.avif"),
            new SourceAvifPair("libavif-test-data/abc.png", "libavif-test-data/abc_color_irot_alpha_NOirot.avif"),
            new SourceAvifPair("libavif-test-data/ArcTriomphe-cHRM-orig.png", "libavif-test-data/arc_triomphe_extent1000_nullbyte_extent1310.avif"),
            new SourceAvifPair("libavif-test-data/circle-trns-after-plte.png", "libavif-test-data/circle_custom_properties.avif"),
            new SourceAvifPair("libavif-test-data/draw_points.png", "libavif-test-data/draw_points_idat.avif"),
            new SourceAvifPair("libavif-test-data/draw_points.png", "libavif-test-data/draw_points_idat_metasize0.avif"),
            new SourceAvifPair("libavif-test-data/draw_points.png", "libavif-test-data/draw_points_idat_progressive.avif"),
            new SourceAvifPair("libavif-test-data/draw_points.png", "libavif-test-data/draw_points_idat_progressive_metasize0.avif"),
            new SourceAvifPair("libavif-test-data/paris_icc_exif_xmp.png", "libavif-test-data/paris_icc_exif_xmp.avif"),
            new SourceAvifPair("libavif-test-data/weld_16bit.png", "libavif-test-data/weld_sato_12B_8B_q0.avif"),
    };

    /// Verifies that ImageIO can read every copied libavif PNG/JPEG reference resource.
    ///
    /// @return the dynamic image resource tests
    @TestFactory
    Stream<DynamicTest> imageIoReadsLibavifImageResources() {
        return Arrays.stream(IMAGE_RESOURCES)
                .map(resource -> DynamicTest.dynamicTest(resource.resourceName, () -> assertImageResource(resource)));
    }

    /// Verifies dimensions shared by documented source images and encoded AVIF fixtures.
    ///
    /// @return the dynamic source relationship tests
    @TestFactory
    Stream<DynamicTest> imageIoSourceDimensionsMatchEncodedAvifMetadata() {
        return Arrays.stream(SOURCE_AVIF_PAIRS)
                .map(pair -> DynamicTest.dynamicTest(pair.avifResource, () -> assertSourceAvifPair(pair)));
    }

    /// Verifies the two Paris PNG metadata-placement variants decode to identical pixels through ImageIO.
    ///
    /// @throws IOException if either PNG cannot be read
    @Test
    void imageIoReadsParisPngMetadataPlacementVariantsWithEqualPixels() throws IOException {
        BufferedImage first = readImage("libavif-test-data/paris_icc_exif_xmp.png");
        BufferedImage second = readImage("libavif-test-data/paris_icc_exif_xmp_at_end.png");

        assertEquals(first.getWidth(), second.getWidth());
        assertEquals(first.getHeight(), second.getHeight());
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                assertEquals(first.getRGB(x, y), second.getRGB(x, y));
            }
        }
    }

    /// Verifies that AVIF rotation transforms swap decoded frame dimensions relative to `abc.png`.
    ///
    /// @throws IOException if a resource cannot be read or decoded
    @Test
    void abcIrotFixturesDecodeWithRotatedFrameDimensions() throws IOException {
        BufferedImage source = readImage("libavif-test-data/abc.png");
        assertRotatedFrameSize(source, "libavif-test-data/abc_color_irot_alpha_irot.avif");
        assertRotatedFrameSize(source, "libavif-test-data/abc_color_irot_alpha_NOirot.avif");
    }

    /// Verifies that high-bit-depth rotation transforms are applied to decoded frame dimensions.
    ///
    /// @throws IOException if the AVIF resource cannot be decoded
    @Test
    void highBitDepthIrotFixtureDecodesWithRotatedFrameDimensions() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes("libavif-test-data/clop_irot_imor.avif"))) {
            AvifImageInfo info = reader.info();
            assertEquals(12, info.width());
            assertEquals(34, info.height());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.TEN_BITS, frame.bitDepth());
            assertEquals(34, frame.width());
            assertEquals(12, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that the clipped left-edge Paeth block in `draw_points_idat.avif` remains red.
    ///
    /// @throws IOException if the AVIF resource cannot be decoded
    @Test
    void drawPointsIdatKeepsLeftEdgePaethRowsRed() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes("libavif-test-data/draw_points_idat.avif"))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(33, frame.width());
            assertEquals(11, frame.height());
            IntBuffer pixels = frame.intPixelBuffer();
            for (int y = 8; y < 11; y++) {
                for (int x = 0; x < 11; x++) {
                    int argb = pixels.get(y * frame.width() + x);
                    assertTrue(((argb >>> 24) & 0xFF) >= 240);
                    assertTrue(((argb >>> 16) & 0xFF) >= 240);
                    assertTrue(((argb >>> 8) & 0xFF) <= 8);
                    assertTrue((argb & 0xFF) <= 8);
                }
            }
            assertNull(reader.readFrame());
        }
    }

    /// Asserts one ImageIO-readable image resource.
    ///
    /// @param resource the expected image resource
    /// @throws IOException if the resource cannot be read
    private static void assertImageResource(ImageResource resource) throws IOException {
        BufferedImage image = readImage(resource.resourceName);
        assertEquals(resource.width, image.getWidth());
        assertEquals(resource.height, image.getHeight());
        if (resource.alphaExpected) {
            assertTrue(image.getColorModel().hasAlpha());
        }
    }

    /// Asserts one source-image to AVIF metadata relationship.
    ///
    /// @param pair the source and AVIF resource names
    /// @throws IOException if a resource cannot be read or parsed
    private static void assertSourceAvifPair(SourceAvifPair pair) throws IOException {
        BufferedImage source = readImage(pair.sourceResource);
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(pair.avifResource))) {
            AvifImageInfo info = reader.info();
            assertEquals(source.getWidth(), info.width());
            assertEquals(source.getHeight(), info.height());
        }
    }

    /// Asserts that one rotated AVIF fixture decodes with source dimensions swapped.
    ///
    /// @param source the source image
    /// @param avifResource the AVIF resource name
    /// @throws IOException if the AVIF resource cannot be decoded
    private static void assertRotatedFrameSize(BufferedImage source, String avifResource) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(avifResource))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(source.getHeight(), frame.width());
            assertEquals(source.getWidth(), frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Reads one image resource through ImageIO.
    ///
    /// @param resourceName the classpath resource name
    /// @return the decoded buffered image
    /// @throws IOException if the resource cannot be read
    private static BufferedImage readImage(String resourceName) throws IOException {
        try (InputStream input = LibavifImageIoReferenceTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "ImageIO could not decode: " + resourceName);
            return image;
        }
    }

    /// Loads one binary resource.
    ///
    /// @param resourceName the classpath resource name
    /// @return the resource bytes
    /// @throws IOException if the resource cannot be read
    private static byte[] testResourceBytes(String resourceName) throws IOException {
        try (InputStream input = LibavifImageIoReferenceTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /// Expected ImageIO-readable image resource metadata.
    @NotNullByDefault
    private static final class ImageResource {
        /// The classpath resource name.
        private final String resourceName;
        /// The expected image width.
        private final int width;
        /// The expected image height.
        private final int height;
        /// Whether ImageIO is expected to expose alpha.
        private final boolean alphaExpected;

        /// Creates expected image resource metadata.
        ///
        /// @param resourceName the classpath resource name
        /// @param width the expected image width
        /// @param height the expected image height
        /// @param alphaExpected whether ImageIO is expected to expose alpha
        private ImageResource(String resourceName, int width, int height, boolean alphaExpected) {
            this.resourceName = resourceName;
            this.width = width;
            this.height = height;
            this.alphaExpected = alphaExpected;
        }
    }

    /// Source-image to encoded-AVIF resource relationship.
    @NotNullByDefault
    private static final class SourceAvifPair {
        /// The source image classpath resource name.
        private final String sourceResource;
        /// The encoded AVIF classpath resource name.
        private final String avifResource;

        /// Creates a source-image to encoded-AVIF relationship.
        ///
        /// @param sourceResource the source image classpath resource name
        /// @param avifResource the encoded AVIF classpath resource name
        private SourceAvifPair(String sourceResource, String avifResource) {
            this.sourceResource = sourceResource;
            this.avifResource = avifResource;
        }
    }
}
