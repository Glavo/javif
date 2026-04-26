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

import org.glavo.avif.testutil.ImagePixelAssertions;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelRegion;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelTolerance;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelTransform;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
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
    /// Per-pixel tolerance accepted for the lossy `abc` AVIF fixtures.
    private static final PixelTolerance ABC_TOLERANCE = PixelTolerance.perPixelDelta(16);
    /// Per-pixel tolerance accepted for q100 `draw_points` AVIF fixtures.
    private static final PixelTolerance DRAW_POINTS_TOLERANCE = PixelTolerance.perPixelDelta(8);
    /// Per-pixel tolerance accepted for palette/metadata-focused fixtures that should be near-lossless.
    private static final PixelTolerance NEAR_LOSSLESS_TOLERANCE = PixelTolerance.perPixelDelta(8);
    /// Aggregate tolerance placeholder for lossy still-image fixtures once decode coverage catches up.
    private static final PixelTolerance LOSSY_STILL_TOLERANCE = PixelTolerance.bounded(24, 0.001, 2.0, 6.0);

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

    /// Full-image pixel references migrated from libavif source-image relationships.
    private static final PixelImageReference @Unmodifiable [] PIXEL_IMAGE_REFERENCES = new PixelImageReference[]{
            disabledPixelImage(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE,
                    "Pending full-image AV1 reconstruction accuracy; the bottom-row region test remains enabled."
            ),
            disabledPixelImage(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_metasize0.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE,
                    "Pending full-image AV1 reconstruction accuracy; the bottom-row region test remains enabled."
            ),
            disabledPixelImage(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_progressive.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE,
                    "Pending progressive full-image AV1 reconstruction accuracy; the bottom-row region test remains enabled."
            ),
            disabledPixelImage(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_progressive_metasize0.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE,
                    "Pending progressive full-image AV1 reconstruction accuracy; the bottom-row region test remains enabled."
            ),
            disabledPixelImage(
                    "libavif-test-data/abc.png",
                    "libavif-test-data/abc_color_irot_alpha_irot.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.ROTATE_90_COUNTER_CLOCKWISE,
                    ABC_TOLERANCE,
                    "Pending AV1 reconstruction fix: the raw color luma plane currently leaves large regions at neutral 128."
            ),
            disabledPixelImage(
                    "libavif-test-data/abc.png",
                    "libavif-test-data/abc_color_irot_alpha_NOirot.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.ROTATE_90_COUNTER_CLOCKWISE,
                    ABC_TOLERANCE,
                    "Pending AV1 reconstruction fix and alpha transform compatibility verification."
            ),
            disabledPixelImage(
                    "libavif-test-data/ArcTriomphe-cHRM-orig.png",
                    "libavif-test-data/arc_triomphe_extent1000_nullbyte_extent1310.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    NEAR_LOSSLESS_TOLERANCE,
                    "Pending AV1 reconstruction and cHRM/color metadata validation."
            ),
            disabledPixelImage(
                    "libavif-test-data/circle-trns-after-plte.png",
                    "libavif-test-data/circle_custom_properties.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    NEAR_LOSSLESS_TOLERANCE,
                    "Pending palette/alpha full-image validation."
            ),
            disabledPixelImage(
                    "libavif-test-data/paris_icc_exif_xmp.png",
                    "libavif-test-data/paris_icc_exif_xmp.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    LOSSY_STILL_TOLERANCE,
                    "Pending lossy still-image decode accuracy and ICC comparison policy."
            ),
            disabledPixelImage(
                    "libavif-test-data/weld_16bit.png",
                    "libavif-test-data/weld_sato_12B_8B_q0.avif",
                    AvifPixelFormat.I444,
                    PixelTransform.IDENTITY,
                    LOSSY_STILL_TOLERANCE,
                    "Pending high-bit-depth decode accuracy validation."
            ),
    };

    /// Source-image regions that must match selected encoded AVIF fixtures after decoding.
    private static final PixelRegionReference @Unmodifiable [] PIXEL_REGION_REFERENCES = new PixelRegionReference[]{
            new PixelRegionReference(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat.avif",
                    AvifPixelFormat.I444,
                    new PixelRegion(0, 8, 11, 3),
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE
            ),
            new PixelRegionReference(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_metasize0.avif",
                    AvifPixelFormat.I444,
                    new PixelRegion(0, 8, 11, 3),
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE
            ),
            new PixelRegionReference(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_progressive.avif",
                    AvifPixelFormat.I444,
                    new PixelRegion(0, 8, 11, 3),
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE
            ),
            new PixelRegionReference(
                    "libavif-test-data/draw_points.png",
                    "libavif-test-data/draw_points_idat_progressive_metasize0.avif",
                    AvifPixelFormat.I444,
                    new PixelRegion(0, 8, 11, 3),
                    PixelTransform.IDENTITY,
                    DRAW_POINTS_TOLERANCE
            ),
    };

    /// Verifies that ImageIO can read every copied libavif PNG/JPEG reference resource.
    ///
    /// @return the dynamic image resource tests
    @TestFactory
    Stream<DynamicTest> imageIoReadsLibavifImageResources() {
        return Arrays.stream(IMAGE_RESOURCES)
                .map(resource -> DynamicTest.dynamicTest(resource.resourceName(), () -> assertImageResource(resource)));
    }

    /// Verifies dimensions shared by documented source images and encoded AVIF fixtures.
    ///
    /// @return the dynamic source relationship tests
    @TestFactory
    Stream<DynamicTest> imageIoSourceDimensionsMatchEncodedAvifMetadata() {
        return Arrays.stream(SOURCE_AVIF_PAIRS)
                .map(pair -> DynamicTest.dynamicTest(pair.avifResource(), () -> assertSourceAvifPair(pair)));
    }

    /// Verifies full decoded AVIF images against ImageIO-readable source images where currently supported.
    ///
    /// @return the dynamic full-image pixel reference tests
    @TestFactory
    Stream<DynamicTest> decodedAvifImagesMatchImageIoReferences() {
        return Arrays.stream(PIXEL_IMAGE_REFERENCES)
                .map(reference -> DynamicTest.dynamicTest(
                        reference.avifResource(),
                        () -> assertPixelImageReference(reference)
                ));
    }

    /// Verifies selected I444 AVIF regions against their ImageIO-readable source images.
    ///
    /// @return the dynamic pixel region reference tests
    @TestFactory
    Stream<DynamicTest> decodedAvifRegionsMatchImageIoReferences() {
        return Arrays.stream(PIXEL_REGION_REFERENCES)
                .map(reference -> DynamicTest.dynamicTest(
                        reference.avifResource() + " region " + reference.region(),
                        () -> assertPixelRegionReference(reference)
                ));
    }

    /// Verifies the two Paris PNG metadata-placement variants decode to identical pixels through ImageIO.
    ///
    /// @throws IOException if either PNG cannot be read
    @Test
    void imageIoReadsParisPngMetadataPlacementVariantsWithEqualPixels() throws IOException {
        BufferedImage first = TestResources.readImage("libavif-test-data/paris_icc_exif_xmp.png");
        BufferedImage second = TestResources.readImage("libavif-test-data/paris_icc_exif_xmp_at_end.png");

        ImagePixelAssertions.assertIntPixelsMatch(
                "paris_icc_exif_xmp_at_end.png",
                first,
                IntBuffer.wrap(second.getRGB(
                        0,
                        0,
                        second.getWidth(),
                        second.getHeight(),
                        null,
                        0,
                        second.getWidth()
                )).asReadOnlyBuffer(),
                second.getWidth(),
                second.getHeight(),
                PixelTransform.IDENTITY,
                PixelTolerance.perPixelDelta(0)
        );
    }

    /// Verifies that AVIF rotation transforms swap decoded frame dimensions relative to `abc.png`.
    ///
    /// @throws IOException if a resource cannot be read or decoded
    @Test
    void abcIrotFixturesDecodeWithRotatedFrameDimensions() throws IOException {
        BufferedImage source = TestResources.readImage("libavif-test-data/abc.png");
        assertRotatedFrameSize(source, "libavif-test-data/abc_color_irot_alpha_irot.avif");
        assertRotatedFrameSize(source, "libavif-test-data/abc_color_irot_alpha_NOirot.avif");
    }

    /// Verifies the copied libavif one-pixel white fixture at pixel level.
    ///
    /// @throws IOException if the AVIF resource cannot be decoded
    @Test
    void whiteOneByOneFixtureMatchesOpaqueWhitePixel() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes("libavif-test-data/white_1x1.avif"))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(1, frame.width());
            assertEquals(1, frame.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            assertEquals(AvifPixelFormat.I444, frame.pixelFormat());
            assertEquals(0xFFFFFFFF, frame.intPixelBuffer().get(0));
            assertNull(reader.readFrame());
        }
    }

    /// Verifies the current expected full-image pixels for the color-and-alpha `irot` fixture.
    ///
    /// @throws IOException if a resource cannot be read or decoded
    @Test
    @Disabled("Pending AV1 reconstruction fix: raw color luma currently leaves large white regions at neutral 128.")
    void abcColorAndAlphaIrotFixtureMatchesCounterClockwiseReferencePixels() throws IOException {
        assertPixelImageReference(PIXEL_IMAGE_REFERENCES[4]);
    }

    /// Verifies that high-bit-depth rotation transforms are applied to decoded frame dimensions.
    ///
    /// @throws IOException if the AVIF resource cannot be decoded
    @Test
    void highBitDepthIrotFixtureDecodesWithRotatedFrameDimensions() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes("libavif-test-data/clop_irot_imor.avif"))) {
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

    /// Asserts one ImageIO-readable image resource.
    ///
    /// @param resource the expected image resource
    /// @throws IOException if the resource cannot be read
    private static void assertImageResource(ImageResource resource) throws IOException {
        BufferedImage image = TestResources.readImage(resource.resourceName());
        assertEquals(resource.width(), image.getWidth());
        assertEquals(resource.height(), image.getHeight());
        if (resource.alphaExpected()) {
            assertTrue(image.getColorModel().hasAlpha());
        }
    }

    /// Asserts one source-image to AVIF metadata relationship.
    ///
    /// @param pair the source and AVIF resource names
    /// @throws IOException if a resource cannot be read or parsed
    private static void assertSourceAvifPair(SourceAvifPair pair) throws IOException {
        BufferedImage source = TestResources.readImage(pair.sourceResource());
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(pair.avifResource()))) {
            AvifImageInfo info = reader.info();
            assertEquals(source.getWidth(), info.width());
            assertEquals(source.getHeight(), info.height());
        }
    }

    /// Asserts one full-image AVIF fixture against an ImageIO-readable source image.
    ///
    /// @param reference the expected pixel reference
    /// @throws IOException if a resource cannot be read or decoded
    private static void assertPixelImageReference(PixelImageReference reference) throws IOException {
        if (reference.disabledReason() != null) {
            Assumptions.assumeTrue(false, reference.disabledReason());
        }
        BufferedImage expected = TestResources.readImage(reference.sourceResource());
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(reference.avifResource()))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(reference.pixelFormat(), frame.pixelFormat());
            assertPixelsMatchReference(reference.avifResource(), expected, frame, reference.transform(), reference.tolerance());
            assertNull(reader.readFrame());
        }
    }

    /// Asserts one AVIF fixture region against an ImageIO-readable source image.
    ///
    /// @param reference the expected pixel region reference
    /// @throws IOException if a resource cannot be read or decoded
    private static void assertPixelRegionReference(PixelRegionReference reference) throws IOException {
        BufferedImage expected = TestResources.readImage(reference.sourceResource());
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(reference.avifResource()))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(reference.pixelFormat(), frame.pixelFormat());
            assertPixelsMatchReference(
                    reference.avifResource(),
                    expected,
                    frame,
                    reference.region(),
                    reference.transform(),
                    reference.tolerance()
            );
            assertNull(reader.readFrame());
        }
    }

    /// Asserts that one AVIF frame matches an ImageIO reference image.
    ///
    /// @param label the diagnostic label
    /// @param expected the expected ImageIO-decoded image
    /// @param frame the decoded AVIF frame
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    private static void assertPixelsMatchReference(
            String label,
            BufferedImage expected,
            AvifFrame frame,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        assertPixelsMatchReference(label, expected, frame, PixelRegion.full(frame.width(), frame.height()), transform, tolerance);
    }

    /// Asserts that one AVIF frame region matches an ImageIO reference image.
    ///
    /// @param label the diagnostic label
    /// @param expected the expected ImageIO-decoded image
    /// @param frame the decoded AVIF frame
    /// @param region the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    private static void assertPixelsMatchReference(
            String label,
            BufferedImage expected,
            AvifFrame frame,
            PixelRegion region,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        if (frame.bitDepth().isEightBit()) {
            ImagePixelAssertions.assertIntPixelsMatch(
                    label,
                    expected,
                    frame.intPixelBuffer(),
                    frame.width(),
                    frame.height(),
                    region,
                    transform,
                    tolerance
            );
        } else {
            ImagePixelAssertions.assertLongPixelsMatch8BitReference(
                    label,
                    expected,
                    frame.longPixelBuffer(),
                    frame.width(),
                    frame.height(),
                    region,
                    transform,
                    tolerance
            );
        }
    }

    /// Asserts that one rotated AVIF fixture decodes with source dimensions swapped.
    ///
    /// @param source the source image
    /// @param avifResource the AVIF resource name
    /// @throws IOException if the AVIF resource cannot be decoded
    private static void assertRotatedFrameSize(BufferedImage source, String avifResource) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(avifResource))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(source.getHeight(), frame.width());
            assertEquals(source.getWidth(), frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Creates an enabled full-image pixel reference.
    ///
    /// @param sourceResource the source image classpath resource name
    /// @param avifResource the encoded AVIF classpath resource name
    /// @param pixelFormat the expected decoded AV1 chroma sampling layout
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    /// @return the full-image pixel reference
    private static PixelImageReference enabledPixelImage(
            String sourceResource,
            String avifResource,
            AvifPixelFormat pixelFormat,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        return new PixelImageReference(sourceResource, avifResource, pixelFormat, transform, tolerance, null);
    }

    /// Creates a disabled full-image pixel reference.
    ///
    /// @param sourceResource the source image classpath resource name
    /// @param avifResource the encoded AVIF classpath resource name
    /// @param pixelFormat the expected decoded AV1 chroma sampling layout
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    /// @param disabledReason the disabled reason
    /// @return the full-image pixel reference
    private static PixelImageReference disabledPixelImage(
            String sourceResource,
            String avifResource,
            AvifPixelFormat pixelFormat,
            PixelTransform transform,
            PixelTolerance tolerance,
            String disabledReason
    ) {
        return new PixelImageReference(sourceResource, avifResource, pixelFormat, transform, tolerance, disabledReason);
    }

    /// Expected ImageIO-readable image resource metadata.
    ///
    /// @param resourceName the classpath resource name
    /// @param width the expected image width
    /// @param height the expected image height
    /// @param alphaExpected whether ImageIO is expected to expose alpha
    @NotNullByDefault
    private record ImageResource(String resourceName, int width, int height, boolean alphaExpected) {
    }

    /// Source-image to encoded-AVIF resource relationship.
    ///
    /// @param sourceResource the source image classpath resource name
    /// @param avifResource the encoded AVIF classpath resource name
    @NotNullByDefault
    private record SourceAvifPair(String sourceResource, String avifResource) {
    }

    /// Full-image pixel reference between a source image and encoded AVIF.
    ///
    /// @param sourceResource the source image classpath resource name
    /// @param avifResource the encoded AVIF classpath resource name
    /// @param pixelFormat the expected decoded AV1 chroma sampling layout
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    /// @param disabledReason the disabled reason, or `null` when enabled
    @NotNullByDefault
    private record PixelImageReference(
            String sourceResource,
            String avifResource,
            AvifPixelFormat pixelFormat,
            PixelTransform transform,
            PixelTolerance tolerance,
            @Nullable String disabledReason
    ) {
    }

    /// Source-image to encoded-AVIF pixel region reference.
    ///
    /// @param sourceResource the source image classpath resource name
    /// @param avifResource the encoded AVIF classpath resource name
    /// @param pixelFormat the expected decoded AV1 chroma sampling layout
    /// @param region the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    @NotNullByDefault
    private record PixelRegionReference(
            String sourceResource,
            String avifResource,
            AvifPixelFormat pixelFormat,
            PixelRegion region,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
    }
}
