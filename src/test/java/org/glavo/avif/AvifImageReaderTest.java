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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `AvifImageReader`.
///
/// Known gap: AV1 `I444` pixel-accuracy is tracked separately from AVIF container coverage.
/// Some I444 images may produce slightly different pixel values compared to a reference decoder.
@NotNullByDefault
final class AvifImageReaderTest {
    /// One fixed single-tile payload that decodes as opaque mid-gray in the current AV1 decoder.
    private static final byte @Unmodifiable [] SUPPORTED_SINGLE_TILE_PAYLOAD = new byte[]{
            (byte) 0x98, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /// The expected packed opaque gray pixel produced by the supported still-picture payload.
    private static final int OPAQUE_MID_GRAY = 0xFF808080;

    /// The alpha auxiliary image type URN used by AVIF alpha items.
    private static final String AUXILIARY_ALPHA_TYPE = AvifAuxiliaryImageInfo.ALPHA_TYPE;
    /// The depth auxiliary image type URN used by AVIF depth items.
    private static final String AUXILIARY_DEPTH_TYPE = AvifAuxiliaryImageInfo.DEPTH_TYPE;

    /// The smallest still-image fixture copied from libavif's test data into test resources.
    private static final String LIBAVIF_WHITE_1X1_FIXTURE = "libavif-test-data/white_1x1.avif";

    /// A still-image fixture copied from libavif's test data that uses an extended `pixi` property.
    private static final String LIBAVIF_EXTENDED_PIXI_FIXTURE = "libavif-test-data/extended_pixi.avif";

    /// A basic SDR sRGB still-image fixture copied from libavif's test data.
    private static final String LIBAVIF_COLORS_SDR_SRGB_FIXTURE = "libavif-test-data/colors_sdr_srgb.avif";

    /// A 10bpc HDR sRGB still-image fixture copied from libavif's test data.
    private static final String LIBAVIF_COLORS_HDR_SRGB_FIXTURE = "libavif-test-data/colors_hdr_srgb.avif";

    /// A still-image fixture copied from libavif's test data with embedded ICC, Exif, and XMP metadata.
    private static final String LIBAVIF_PARIS_ICC_EXIF_XMP_FIXTURE =
            "libavif-test-data/paris_icc_exif_xmp.avif";

    /// A fixture copied from libavif's test data with a color item and an alpha auxiliary item.
    private static final String LIBAVIF_ALPHA_NO_IROT_FIXTURE =
            "libavif-test-data/abc_color_irot_alpha_NOirot.avif";

    /// A fixture copied from libavif's test data with an alpha auxiliary item missing ispe.
    private static final String LIBAVIF_ALPHA_NOISPE_FIXTURE = "libavif-test-data/alpha_noispe.avif";

    /// A 1x5 grid image fixture copied from libavif's test data.
    private static final String LIBAVIF_SOFA_GRID_1X5_FIXTURE = "libavif-test-data/sofa_grid1x5_420.avif";

    /// A grid image fixture copied from libavif's test data with an alpha grid.
    private static final String LIBAVIF_COLOR_GRID_ALPHA_GRID_GAINMAP_FIXTURE =
            "libavif-test-data/color_grid_alpha_grid_gainmap_nogrid.avif";

    /// A progressive still-image fixture copied from libavif's test data using idat.
    private static final String LIBAVIF_PROGRESSIVE_FIXTURE =
            "libavif-test-data/draw_points_idat_progressive.avif";

    /// An animated 8bpc AVIS image sequence fixture copied from libavif's test data.
    private static final String LIBAVIF_ANIMATED_FIXTURE = "libavif-test-data/colors-animated-8bpc.avif";

    /// An animated 8bpc AVIS image sequence fixture with an alpha auxiliary track.
    private static final String LIBAVIF_ANIMATED_ALPHA_FIXTURE =
            "libavif-test-data/colors-animated-8bpc-alpha-exif-xmp.avif";

    /// An animated 8bpc AVIS image sequence fixture with a depth auxiliary track.
    private static final String LIBAVIF_ANIMATED_DEPTH_FIXTURE =
            "libavif-test-data/colors-animated-8bpc-depth-exif-xmp.avif";

    /// A gain-map fixture copied from libavif's test data.
    private static final String LIBAVIF_GAINMAP_FIXTURE = "libavif-test-data/seine_sdr_gainmap_srgb.avif";

    /// A gain-map fixture copied from libavif's test data with ICC color profiles.
    private static final String LIBAVIF_GAINMAP_ICC_FIXTURE = "libavif-test-data/seine_sdr_gainmap_srgb_icc.avif";

    /// A gain-map grid fixture copied from libavif's test data.
    private static final String LIBAVIF_GAINMAP_GRID_FIXTURE =
            "libavif-test-data/color_nogrid_alpha_nogrid_gainmap_grid.avif";

    /// A gain-map fixture without the `tmap` compatible brand.
    private static final String LIBAVIF_GAINMAP_NO_TMAP_BRAND_FIXTURE =
            "libavif-test-data/seine_sdr_gainmap_notmapbrand.avif";

    /// A gain-map fixture where the `altr` group does not make the `tmap` item preferred.
    private static final String LIBAVIF_GAINMAP_WRONG_ALTR_FIXTURE =
            "libavif-test-data/seine_hdr_gainmap_wrongaltr.avif";

    /// A gain-map fixture with an unsupported `tmap` metadata version.
    private static final String LIBAVIF_UNSUPPORTED_GAINMAP_VERSION_FIXTURE =
            "libavif-test-data/unsupported_gainmap_version.avif";

    /// A gain-map fixture with an unsupported `tmap` minimum metadata version.
    private static final String LIBAVIF_UNSUPPORTED_GAINMAP_MINIMUM_VERSION_FIXTURE =
            "libavif-test-data/unsupported_gainmap_minimum_version.avif";

    /// A gain-map fixture with an unsupported writer version and extra trailing metadata bytes.
    private static final String LIBAVIF_UNSUPPORTED_GAINMAP_WRITER_EXTRA_BYTES_FIXTURE =
            "libavif-test-data/unsupported_gainmap_writer_version_with_extra_bytes.avif";

    /// A gain-map fixture with a supported writer version and invalid extra trailing metadata bytes.
    private static final String LIBAVIF_SUPPORTED_GAINMAP_WRITER_EXTRA_BYTES_FIXTURE =
            "libavif-test-data/supported_gainmap_writer_version_with_extra_bytes.avif";

    /// A gain-map fixture with invalid zero gamma metadata.
    private static final String LIBAVIF_GAINMAP_ZERO_GAMMA_FIXTURE =
            "libavif-test-data/seine_sdr_gainmap_gammazero.avif";

    /// Loads a test resource into a byte array.
    ///
    /// @param resourceName the classpath resource name
    /// @return the full resource contents
    /// @throws IOException if the resource cannot be read
    private static byte[] testResourceBytes(String resourceName) throws IOException {
        try (InputStream input = AvifImageReaderTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /// Copies all remaining bytes from a buffer into an array without changing the source position.
    ///
    /// @param buffer the source buffer
    /// @return the remaining bytes
    private static byte[] remainingBytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.slice();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    /// Returns whether two ARGB buffers contain at least one RGB difference while preserving alpha.
    ///
    /// @param basePixels the base frame pixels
    /// @param toneMappedPixels the tone-mapped frame pixels
    /// @return whether at least one RGB channel differs and every alpha channel matches
    private static boolean hasRgbDifferenceWithSameAlpha(IntBuffer basePixels, IntBuffer toneMappedPixels) {
        assertEquals(basePixels.remaining(), toneMappedPixels.remaining());
        boolean hasRgbDifference = false;
        while (basePixels.hasRemaining()) {
            int basePixel = basePixels.get();
            int toneMappedPixel = toneMappedPixels.get();
            assertEquals(basePixel & 0xFF00_0000, toneMappedPixel & 0xFF00_0000);
            if ((basePixel & 0x00FF_FFFF) != (toneMappedPixel & 0x00FF_FFFF)) {
                hasRgbDifference = true;
            }
        }
        return hasRgbDifference;
    }

    /// Creates a depth auxiliary fixture by replacing the alpha auxiliary type with the equal-length depth type.
    ///
    /// @return an AVIF fixture with a depth auxiliary item
    /// @throws IOException if the source fixture cannot be read
    private static byte[] alphaFixtureWithDepthAuxiliaryType() throws IOException {
        return fixtureWithRewrittenAuxiliaryType(LIBAVIF_ALPHA_NO_IROT_FIXTURE, AUXILIARY_DEPTH_TYPE);
    }

    /// Creates a depth auxiliary grid fixture by replacing the alpha auxiliary type with the equal-length depth type.
    ///
    /// @return an AVIF fixture with a depth auxiliary grid
    /// @throws IOException if the source fixture cannot be read
    private static byte[] alphaGridFixtureWithDepthAuxiliaryType() throws IOException {
        return fixtureWithRewrittenAuxiliaryType(
                LIBAVIF_COLOR_GRID_ALPHA_GRID_GAINMAP_FIXTURE,
                AUXILIARY_DEPTH_TYPE
        );
    }

    /// Rewrites the first alpha auxiliary type string in one fixture.
    ///
    /// @param resourceName the source fixture resource name
    /// @param replacementType the replacement auxiliary type string
    /// @return a mutated AVIF fixture
    /// @throws IOException if the source fixture cannot be read
    private static byte[] fixtureWithRewrittenAuxiliaryType(String resourceName, String replacementType)
            throws IOException {
        byte[] bytes = testResourceBytes(resourceName);
        byte[] alphaType = AUXILIARY_ALPHA_TYPE.getBytes(StandardCharsets.ISO_8859_1);
        byte[] replacementBytes = replacementType.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(alphaType.length, replacementBytes.length);
        int offset = indexOf(bytes, alphaType);
        assertTrue(offset >= 0);
        System.arraycopy(replacementBytes, 0, bytes, offset, replacementBytes.length);
        return bytes;
    }

    /// Verifies that negative configured input-size limits are rejected.
    @Test
    void decoderConfigRejectsNegativeInputSizeLimit() {
        assertThrows(IllegalArgumentException.class, () -> AvifDecoderConfig.builder().inputSizeLimit(-1));
    }

    /// Verifies that every public input type rejects encoded data above the configured limit.
    ///
    /// @throws IOException if the temporary fixture cannot be written
    @Test
    void openRejectsInputsAboveConfiguredInputSizeLimit() throws IOException {
        byte[] bytes = minimalAvifStillImage();
        AvifDecoderConfig config = AvifDecoderConfig.builder()
                .inputSizeLimit(bytes.length - 1L)
                .build();

        assertInputTooLarge(() -> AvifImageReader.open(bytes, config));
        assertInputTooLarge(() -> AvifImageReader.open(ByteBuffer.wrap(bytes), config));
        assertInputTooLarge(() -> AvifImageReader.open(new ByteArrayInputStream(bytes), config));
        assertInputTooLarge(() -> AvifImageReader.open(Channels.newChannel(new ByteArrayInputStream(bytes)), config));

        Path path = writeWorkspaceTempFile("oversized", bytes);
        assertInputTooLarge(() -> AvifImageReader.open(path, config));
    }

    /// Verifies that the configured input-size limit is inclusive.
    ///
    /// @throws IOException if the reader cannot parse the test input
    @Test
    void openAcceptsInputsAtConfiguredInputSizeLimit() throws IOException {
        byte[] bytes = minimalAvifStillImage();
        AvifDecoderConfig config = AvifDecoderConfig.builder()
                .inputSizeLimit(bytes.length)
                .build();

        try (AvifImageReader reader = AvifImageReader.open(bytes, config)) {
            assertEquals(64, reader.info().width());
        }
        try (AvifImageReader reader = AvifImageReader.open(ByteBuffer.wrap(bytes), config)) {
            assertEquals(64, reader.info().width());
        }
        try (AvifImageReader reader = AvifImageReader.open(new ByteArrayInputStream(bytes), config)) {
            assertEquals(64, reader.info().width());
        }
        try (AvifImageReader reader = AvifImageReader.open(
                Channels.newChannel(new ByteArrayInputStream(bytes)),
                config
        )) {
            assertEquals(64, reader.info().width());
        }

        Path path = writeWorkspaceTempFile("exact-limit", bytes);
        try (AvifImageReader reader = AvifImageReader.open(path, config)) {
            assertEquals(64, reader.info().width());
        }
    }

    /// Writes bytes to a temporary path under the workspace-local build directory.
    ///
    /// @param name the logical fixture name
    /// @param bytes the file contents
    /// @return the written path
    /// @throws IOException if the fixture cannot be written
    private static Path writeWorkspaceTempFile(String name, byte[] bytes) throws IOException {
        Path directory = Path.of("build", "tmp", "test", "AvifImageReaderTest");
        Files.createDirectories(directory);
        Path path = directory.resolve(name + "-" + System.nanoTime() + ".avif");
        Files.write(path, bytes);
        return path;
    }

    /// Asserts that an input-open operation fails with `INPUT_TOO_LARGE`.
    ///
    /// @param operation the operation expected to fail
    private static void assertInputTooLarge(ThrowingOpen operation) {
        AvifDecodeException exception = assertThrows(AvifDecodeException.class, operation::open);
        assertEquals(AvifErrorCode.INPUT_TOO_LARGE, exception.code());
    }

    /// Open operation that may fail with `IOException`.
    @FunctionalInterface
    private interface ThrowingOpen {
        /// Runs the open operation.
        ///
        /// @throws IOException if opening fails
        void open() throws IOException;
    }

    /// Verifies that the primary AV1 item payload is decoded through the migrated AV1 decoder.
    ///
    /// @throws IOException if the reader cannot decode the test stream
    @Test
    void readFrameDecodesMinimalStillImage() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifStillImage())) {
            AvifFrame frame = reader.readFrame();

            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            AvifFrame intFrame = frame;
            assertEquals(64, intFrame.width());
            assertEquals(64, intFrame.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, intFrame.bitDepth());
            assertEquals(AvifPixelFormat.I420, intFrame.pixelFormat());
            assertEquals(0, intFrame.frameIndex());
            assertEquals(AvifRgbOutputMode.ARGB_8888, intFrame.rgbOutputMode());
            assertTrue(intFrame.hasIntPixelBuffer());
            assertFalse(intFrame.hasLongPixelBuffer());

            IntBuffer pixelBuffer = intFrame.intPixelBuffer();
            assertTrue(pixelBuffer.isReadOnly());
            assertEquals(64 * 64, pixelBuffer.remaining());
            assertEquals(OPAQUE_MID_GRAY, pixelBuffer.get(0));
            assertEquals(OPAQUE_MID_GRAY, pixelBuffer.get(pixelBuffer.limit() - 1));

            int[] pixels = intFrame.intPixels();
            assertEquals(64 * 64, pixels.length);
            assertEquals(OPAQUE_MID_GRAY, pixels[0]);
            assertEquals(OPAQUE_MID_GRAY, pixels[pixels.length - 1]);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that an 8-bit still image can be exposed primarily as 16-bit-per-channel ARGB.
    ///
    /// @throws IOException if the reader cannot decode the test stream
    @Test
    void readFrameCanForceLongRgbOutputForEightBitStillImage() throws IOException {
        AvifDecoderConfig config = AvifDecoderConfig.builder()
                .rgbOutputMode(AvifRgbOutputMode.ARGB_16161616)
                .build();
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifStillImage(), config)) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);

            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            assertEquals(AvifRgbOutputMode.ARGB_16161616, frame.rgbOutputMode());
            assertFalse(frame.hasIntPixelBuffer());
            assertTrue(frame.hasLongPixelBuffer());

            LongBuffer pixels = frame.longPixelBuffer();
            assertTrue(pixels.isReadOnly());
            assertEquals(64 * 64, pixels.remaining());
            assertEquals(0xFFFF_8080_8080_8080L, pixels.get(0));

            frame.intPixelBuffer();
            assertTrue(frame.hasIntPixelBuffer());
            assertTrue(frame.hasLongPixelBuffer());
        }
    }

    /// Verifies that a 10-bit still image can be exposed primarily as 8-bit ARGB.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameCanForceIntRgbOutputForTenBitStillImage() throws IOException {
        AvifDecoderConfig config = AvifDecoderConfig.builder()
                .rgbOutputMode(AvifRgbOutputMode.ARGB_8888)
                .build();
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_COLORS_HDR_SRGB_FIXTURE), config)) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);

            assertEquals(AvifBitDepth.TEN_BITS, frame.bitDepth());
            assertEquals(AvifRgbOutputMode.ARGB_8888, frame.rgbOutputMode());
            assertTrue(frame.hasIntPixelBuffer());
            assertFalse(frame.hasLongPixelBuffer());

            IntBuffer pixels = frame.intPixelBuffer();
            assertTrue(pixels.isReadOnly());
            assertEquals(frame.width() * frame.height(), pixels.remaining());

            frame.longPixelBuffer();
            assertTrue(frame.hasIntPixelBuffer());
            assertTrue(frame.hasLongPixelBuffer());
        }
    }

    /// Verifies that high-bit-depth grid color and alpha composition preserves forced 8-bit output.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameCanForceIntRgbOutputForHighBitDepthGridWithAlpha() throws IOException {
        AvifDecoderConfig config = AvifDecoderConfig.builder()
                .rgbOutputMode(AvifRgbOutputMode.ARGB_8888)
                .build();
        try (AvifImageReader reader = AvifImageReader.open(
                testResourceBytes(LIBAVIF_COLOR_GRID_ALPHA_GRID_GAINMAP_FIXTURE),
                config
        )) {
            AvifImageInfo info = reader.info();
            assertEquals(AvifBitDepth.TEN_BITS, info.bitDepth());
            assertTrue(info.alphaPresent());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.TEN_BITS, frame.bitDepth());
            assertEquals(AvifRgbOutputMode.ARGB_8888, frame.rgbOutputMode());
            assertTrue(frame.hasIntPixelBuffer());
            assertFalse(frame.hasLongPixelBuffer());
            assertEquals(frame.width() * frame.height(), frame.intPixelBuffer().remaining());
        }
    }

    /// Verifies that raw decoded color planes are exposed for a still image.
    ///
    /// @throws IOException if the reader cannot decode the test stream
    @Test
    void readRawColorPlanesExposesMinimalStillImagePlanes() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifStillImage())) {
            AvifPlanes planes = reader.readRawColorPlanes(0);

            assertEquals(AvifBitDepth.EIGHT_BITS, planes.bitDepth());
            assertEquals(AvifPixelFormat.I420, planes.pixelFormat());
            assertEquals(64, planes.codedWidth());
            assertEquals(64, planes.codedHeight());
            assertEquals(64, planes.renderWidth());
            assertEquals(64, planes.renderHeight());
            assertTrue(planes.hasChroma());

            AvifPlane luma = planes.lumaPlane();
            assertEquals(64, luma.width());
            assertEquals(64, luma.height());
            assertEquals(64, luma.stride());
            assertEquals(128, luma.sample(0, 0));
            assertEquals(128, luma.sample(63, 63));

            ShortBuffer lumaBuffer = luma.sampleBuffer();
            assertTrue(lumaBuffer.isReadOnly());
            assertEquals(64 * 64, lumaBuffer.remaining());
            assertEquals(128, lumaBuffer.get(0) & 0xFFFF);

            short[] lumaSamples = luma.samples();
            assertEquals(64 * 64, lumaSamples.length);
            lumaSamples[0] = 0;
            assertEquals(128, luma.sample(0, 0));

            AvifPlane chromaU = planes.chromaUPlane();
            AvifPlane chromaV = planes.chromaVPlane();
            assertNotNull(chromaU);
            assertNotNull(chromaV);
            assertEquals(32, chromaU.width());
            assertEquals(32, chromaU.height());
            assertEquals(32, chromaV.width());
            assertEquals(32, chromaV.height());
            assertNull(reader.readRawAlphaPlanes(0));
            assertNull(reader.readRawDepthPlanes(0));
        }
    }

    /// Verifies that a real libavif still-image fixture reaches the public metadata path.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesLibavifWhiteFixtureInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_WHITE_1X1_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertEquals(1, info.width());
            assertEquals(1, info.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, info.bitDepth());
            assertEquals(AvifPixelFormat.I444, info.pixelFormat());
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
        }
    }

    /// Verifies that a real libavif still-image fixture can be decoded through the public reader.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameDecodesLibavifWhiteFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_WHITE_1X1_FIXTURE))) {
            AvifFrame frame = reader.readFrame();

            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            AvifFrame intFrame = frame;
            assertEquals(1, intFrame.width());
            assertEquals(1, intFrame.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, intFrame.bitDepth());
            assertEquals(AvifPixelFormat.I444, intFrame.pixelFormat());

            int[] pixels = intFrame.intPixels();
            assertEquals(1, pixels.length);
            assertEquals(0xFF, pixels[0] >>> 24);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that a fixture with an extended `pixi` property parses metadata correctly.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesExtendedPixiFixtureInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_EXTENDED_PIXI_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertTrue(info.bitDepth() == AvifBitDepth.EIGHT_BITS
                    || info.bitDepth() == AvifBitDepth.TEN_BITS
                    || info.bitDepth() == AvifBitDepth.TWELVE_BITS);
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
        }
    }

    /// Verifies that an SDR sRGB fixture parses and decodes through the public reader.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameDecodesColorsSdrSrgbFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_COLORS_SDR_SRGB_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertTrue(info.bitDepth() == AvifBitDepth.EIGHT_BITS
                    || info.bitDepth() == AvifBitDepth.TEN_BITS
                    || info.bitDepth() == AvifBitDepth.TWELVE_BITS);
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(info.width(), frame.width());
            assertEquals(info.height(), frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that a fixture with ICC/Exif/XMP metadata parses correctly.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesParisIccExifXmpFixtureInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_PARIS_ICC_EXIF_XMP_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertTrue(info.bitDepth() == AvifBitDepth.EIGHT_BITS
                    || info.bitDepth() == AvifBitDepth.TEN_BITS
                    || info.bitDepth() == AvifBitDepth.TWELVE_BITS);
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());

            ByteBuffer iccProfile = info.iccProfile();
            assertNotNull(iccProfile);
            assertTrue(iccProfile.isReadOnly());
            assertTrue(iccProfile.remaining() > 128);

            ByteBuffer exif = info.exif();
            assertNotNull(exif);
            assertTrue(exif.isReadOnly());
            assertTrue(exif.remaining() > 0);

            ByteBuffer xmp = info.xmp();
            assertNotNull(xmp);
            assertTrue(xmp.isReadOnly());
            assertTrue(xmp.remaining() > 0);
            String xmpText = new String(remainingBytes(xmp), StandardCharsets.UTF_8);
            assertTrue(xmpText.contains("x:xmpmeta") || xmpText.contains("rdf:RDF"));
        }
    }

    /// Verifies that a libavif gain-map fixture exposes its `tmap` association metadata.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesGainMapFixtureInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_FIXTURE))) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();

            assertNotNull(gainMapInfo);
            assertEquals(2, gainMapInfo.toneMappedImageItemId());
            assertEquals(1, gainMapInfo.baseImageItemId());
            assertEquals(3, gainMapInfo.gainMapImageItemId());
            assertEquals("tmap", gainMapInfo.toneMappedItemType());
            assertEquals("av01", gainMapInfo.gainMapItemType());
            assertEquals(400, gainMapInfo.toneMappedWidth());
            assertEquals(300, gainMapInfo.toneMappedHeight());
            assertTrue(gainMapInfo.gainMapWidth() > 0);
            assertTrue(gainMapInfo.gainMapHeight() > 0);
            assertNotNull(gainMapInfo.gainMapBitDepth());
            assertNotNull(gainMapInfo.gainMapPixelFormat());
            AvifColorInfo toneMappedColorInfo = gainMapInfo.toneMappedColorInfo();
            assertNotNull(toneMappedColorInfo);
            assertEquals(1, toneMappedColorInfo.colorPrimaries());
            assertEquals(16, toneMappedColorInfo.transferCharacteristics());
            assertEquals(6, toneMappedColorInfo.matrixCoefficients());
            assertNull(gainMapInfo.toneMappedIccProfile());
            AvifColorInfo gainMapColorInfo = gainMapInfo.gainMapColorInfo();
            assertNotNull(gainMapColorInfo);
            assertEquals(6, gainMapColorInfo.matrixCoefficients());
            assertEquals(0, gainMapInfo.metadataVersion());
            assertEquals(0, gainMapInfo.metadataMinimumVersion());
            assertEquals(0, gainMapInfo.metadataWriterVersion());
            assertTrue(gainMapInfo.metadataSupported());
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);
            assertTrue(metadata.useBaseColorSpace());
            assertEquals(3, metadata.gainMapMin().length);
            assertEquals(3, metadata.gainMapMax().length);
            assertEquals(3, metadata.gainMapGamma().length);
            assertEquals(3, metadata.baseOffset().length);
            assertEquals(3, metadata.alternateOffset().length);
            for (AvifUnsignedFraction gamma : metadata.gainMapGamma()) {
                assertTrue(gamma.numerator() > 0);
                assertTrue(gamma.denominator() > 0);
            }
        }
    }

    /// Verifies that a gain-map fixture exposes alternate ICC metadata without applying it.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesGainMapIccFixtureInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_ICC_FIXTURE))) {
            assertNotNull(reader.info().iccProfile());
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);

            ByteBuffer toneMappedIccProfile = gainMapInfo.toneMappedIccProfile();
            assertNotNull(toneMappedIccProfile);
            assertTrue(toneMappedIccProfile.isReadOnly());
            assertTrue(toneMappedIccProfile.remaining() > 0);
        }
    }

    /// Verifies that a standalone AV1 gain-map item can be decoded as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawGainMapPlanesDecodesStandaloneGainMapFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_FIXTURE))) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);

            AvifPlanes gainMapPlanes = reader.readRawGainMapPlanes(0);
            assertNotNull(gainMapPlanes);
            assertEquals(gainMapInfo.gainMapBitDepth(), gainMapPlanes.bitDepth());
            assertEquals(gainMapInfo.gainMapPixelFormat(), gainMapPlanes.pixelFormat());
            assertEquals(gainMapInfo.gainMapWidth(), gainMapPlanes.codedWidth());
            assertEquals(gainMapInfo.gainMapHeight(), gainMapPlanes.codedHeight());
            assertTrue(gainMapPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Verifies that a gain-map grid can be decoded and composed as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawGainMapPlanesComposesGainMapGridFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_GRID_FIXTURE))) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            assertEquals("grid", gainMapInfo.gainMapItemType());

            AvifPlanes gainMapPlanes = reader.readRawGainMapPlanes(0);
            assertNotNull(gainMapPlanes);
            assertEquals(gainMapInfo.gainMapBitDepth(), gainMapPlanes.bitDepth());
            assertEquals(gainMapInfo.gainMapPixelFormat(), gainMapPlanes.pixelFormat());
            assertEquals(gainMapInfo.gainMapWidth(), gainMapPlanes.codedWidth());
            assertEquals(gainMapInfo.gainMapHeight(), gainMapPlanes.codedHeight());
            assertTrue(gainMapPlanes.lumaPlane().sampleBuffer().isReadOnly());
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);
            assertEquals(new AvifUnsignedFraction(6, 2), metadata.baseHdrHeadroom());
        }
    }

    /// Verifies that images without `tmap` metadata report no raw gain-map planes.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawGainMapPlanesReturnsNullWithoutGainMap() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_WHITE_1X1_FIXTURE))) {
            assertNull(reader.readRawGainMapPlanes(0));
        }
    }

    /// Verifies that tone-mapped output is absent when no gain map is present.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readToneMappedFrameReturnsNullWithoutGainMap() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_WHITE_1X1_FIXTURE))) {
            assertNull(reader.readToneMappedFrame(0, 1.0));
        }
    }

    /// Verifies that invalid tone-mapping headroom values are rejected before decoding.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void readToneMappedFrameRejectsInvalidHeadroom() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_FIXTURE))) {
            assertThrows(IllegalArgumentException.class, () -> reader.readToneMappedFrame(0, -1.0));
            assertThrows(IllegalArgumentException.class, () -> reader.readToneMappedFrame(0, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> reader.readToneMappedFrame(0, Double.POSITIVE_INFINITY));
            assertThrows(NullPointerException.class, () -> reader.readToneMappedFrame(0, 1.0, null));
        }
    }

    /// Verifies that applying a gain map at the base image headroom is a no-op.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readToneMappedFrameAtBaseHeadroomReturnsBasePixels() throws IOException {
        byte[] bytes = testResourceBytes(LIBAVIF_GAINMAP_FIXTURE);
        int[] basePixels;
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifFrame baseFrame = reader.readFrame(0);
            basePixels = baseFrame.intPixels();
        }
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);

            AvifFrame toneMappedFrame = reader.readToneMappedFrame(0, metadata.baseHdrHeadroom().toDouble());
            assertNotNull(toneMappedFrame);
            assertArrayEquals(basePixels, toneMappedFrame.intPixels());
        }
    }

    /// Verifies that base-headroom output can still be converted into a requested CICP color space.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readToneMappedFrameAtBaseHeadroomConvertsOutputColorSpace() throws IOException {
        byte[] bytes = testResourceBytes(LIBAVIF_GAINMAP_FIXTURE);
        AvifFrame baseFrame;
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            baseFrame = reader.readFrame(0);
        }
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);
            AvifColorInfo pqSrgbOutput = new AvifColorInfo(1, 16, 6, true);

            AvifFrame toneMappedFrame = reader.readToneMappedFrame(
                    0,
                    metadata.baseHdrHeadroom().toDouble(),
                    pqSrgbOutput
            );
            assertNotNull(toneMappedFrame);
            assertEquals(baseFrame.width(), toneMappedFrame.width());
            assertEquals(baseFrame.height(), toneMappedFrame.height());
            assertEquals(baseFrame.rgbOutputMode(), toneMappedFrame.rgbOutputMode());
            assertTrue(hasRgbDifferenceWithSameAlpha(baseFrame.intPixelBuffer(), toneMappedFrame.intPixelBuffer()));
        }
    }

    /// Verifies that applying a gain map at the alternate image headroom changes RGB while preserving alpha.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readToneMappedFrameAtAlternateHeadroomAppliesGainMap() throws IOException {
        byte[] bytes = testResourceBytes(LIBAVIF_GAINMAP_FIXTURE);
        AvifFrame baseFrame;
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            baseFrame = reader.readFrame(0);
        }
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);

            AvifFrame toneMappedFrame = reader.readToneMappedFrame(0, metadata.alternateHdrHeadroom().toDouble());
            assertNotNull(toneMappedFrame);
            assertEquals(baseFrame.width(), toneMappedFrame.width());
            assertEquals(baseFrame.height(), toneMappedFrame.height());
            assertEquals(baseFrame.bitDepth(), toneMappedFrame.bitDepth());
            assertEquals(baseFrame.pixelFormat(), toneMappedFrame.pixelFormat());
            assertEquals(baseFrame.rgbOutputMode(), toneMappedFrame.rgbOutputMode());
            assertTrue(hasRgbDifferenceWithSameAlpha(baseFrame.intPixelBuffer(), toneMappedFrame.intPixelBuffer()));
        }
    }

    /// Verifies that gain-map grid fixtures can be tone-mapped into frame output.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readToneMappedFrameComposesGainMapGridFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_GRID_FIXTURE))) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            AvifGainMapMetadata metadata = gainMapInfo.metadata();
            assertNotNull(metadata);

            AvifFrame toneMappedFrame = reader.readToneMappedFrame(0, metadata.alternateHdrHeadroom().toDouble());
            assertNotNull(toneMappedFrame);
            assertEquals(reader.info().width(), toneMappedFrame.width());
            assertEquals(reader.info().height(), toneMappedFrame.height());
            assertEquals(AvifRgbOutputMode.ARGB_16161616, toneMappedFrame.rgbOutputMode());
            assertTrue(toneMappedFrame.longPixelBuffer().isReadOnly());
        }
    }

    /// Verifies that a still-image depth auxiliary item can be decoded as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawDepthPlanesDecodesStillDepthAuxiliaryItem() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(alphaFixtureWithDepthAuxiliaryType())) {
            AvifImageInfo info = reader.info();
            assertFalse(info.alphaPresent());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_DEPTH_TYPE));
            assertNull(reader.readRawAlphaPlanes(0));
            AvifAuxiliaryImageInfo[] auxiliaryImages = info.auxiliaryImages();
            assertEquals(1, auxiliaryImages.length);
            AvifAuxiliaryImageInfo depthInfo = auxiliaryImages[0];
            assertEquals(AUXILIARY_DEPTH_TYPE, depthInfo.auxiliaryType());

            AvifPlanes depthPlanes = reader.readRawDepthPlanes(0);
            assertNotNull(depthPlanes);
            assertEquals(depthInfo.bitDepth(), depthPlanes.bitDepth());
            assertEquals(depthInfo.pixelFormat(), depthPlanes.pixelFormat());
            assertEquals(depthInfo.width(), depthPlanes.codedWidth());
            assertEquals(depthInfo.height(), depthPlanes.codedHeight());
            assertEquals(depthInfo.width(), depthPlanes.renderWidth());
            assertEquals(depthInfo.height(), depthPlanes.renderHeight());
            assertFalse(depthPlanes.hasChroma());
            assertTrue(depthPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Verifies that a `tmap` item is ignored when the `tmap` compatible brand is absent.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openIgnoresGainMapWithoutTmapBrand() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_NO_TMAP_BRAND_FIXTURE))) {
            assertNull(reader.info().gainMapInfo());
        }
    }

    /// Verifies that a `tmap` item is ignored when it is not a preferred alternative.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openIgnoresGainMapWithoutPreferredAlternative() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_WRONG_ALTR_FIXTURE))) {
            assertNull(reader.info().gainMapInfo());
        }
    }

    /// Verifies that unsupported gain-map metadata versions are ignored.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openIgnoresUnsupportedGainMapMetadataVersions() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_UNSUPPORTED_GAINMAP_VERSION_FIXTURE))) {
            assertNull(reader.info().gainMapInfo());
            assertNull(reader.readRawGainMapPlanes(0));
        }
        try (AvifImageReader reader = AvifImageReader.open(
                testResourceBytes(LIBAVIF_UNSUPPORTED_GAINMAP_MINIMUM_VERSION_FIXTURE)
        )) {
            assertNull(reader.info().gainMapInfo());
            assertNull(reader.readRawGainMapPlanes(0));
        }
    }

    /// Verifies that newer writer metadata may append unknown bytes when the minimum version is supported.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openAcceptsUnsupportedGainMapWriterVersionWithExtraBytes() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(
                testResourceBytes(LIBAVIF_UNSUPPORTED_GAINMAP_WRITER_EXTRA_BYTES_FIXTURE)
        )) {
            AvifGainMapInfo gainMapInfo = reader.info().gainMapInfo();
            assertNotNull(gainMapInfo);
            assertEquals(0, gainMapInfo.metadataVersion());
            assertEquals(0, gainMapInfo.metadataMinimumVersion());
            assertEquals(99, gainMapInfo.metadataWriterVersion());
            assertTrue(gainMapInfo.metadataSupported());
            assertNotNull(gainMapInfo.metadata());
            assertNotNull(reader.readRawGainMapPlanes(0));
        }
    }

    /// Verifies that supported writer metadata rejects unexpected trailing bytes.
    @Test
    void openRejectsSupportedGainMapWriterVersionWithExtraBytes() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(testResourceBytes(LIBAVIF_SUPPORTED_GAINMAP_WRITER_EXTRA_BYTES_FIXTURE))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that invalid gain-map gamma metadata is rejected.
    @Test
    void openRejectsGainMapZeroGamma() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(testResourceBytes(LIBAVIF_GAINMAP_ZERO_GAMMA_FIXTURE))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that a progressive idat fixture parses and decodes through the reader.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameDecodesProgressiveFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_PROGRESSIVE_FIXTURE))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            assertNull(info.sequenceInfo());
            assertNull(info.transformInfo());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that an explicit default `a1op` property is accepted as the normal operating point.
    ///
    /// @throws IOException if the synthetic image cannot be read
    @Test
    void openAcceptsPrimaryDefaultOperatingPoint() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifWithOperatingPoint(0))) {
            AvifFrame frame = reader.readFrame();

            assertNotNull(frame);
            assertEquals(64, frame.width());
            assertEquals(64, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that non-default AVIF operating-point selection has a stable unsupported boundary.
    ///
    /// @throws IOException if an unexpected I/O error occurs
    @Test
    void rejectsPrimaryNonDefaultOperatingPoint() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvifWithOperatingPoint(1))
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that invalid `a1op` property values are rejected during BMFF parsing.
    ///
    /// @throws IOException if an unexpected I/O error occurs
    @Test
    void rejectsInvalidOperatingPointProperty() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvifWithOperatingPoint(32))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that progressive dependencies cannot silently select a non-default AV1 operating point.
    ///
    /// @throws IOException if an unexpected I/O error occurs
    @Test
    void rejectsProgressiveDependencyNonDefaultOperatingPoint() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(syntheticProgressiveAvifWithDependencyOperatingPoint(1))
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that an animated AVIS sequence parses and decodes the first frame.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsAnimatedSequenceAllFrames() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ANIMATED_FIXTURE))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.animated());
            assertEquals(5, info.frameCount());
            assertTrue(info.mediaTimescale() > 0);
            assertTrue(info.mediaDuration() > 0);
            AvifSequenceInfo sequenceInfo = info.sequenceInfo();
            assertNotNull(sequenceInfo);
            assertEquals(info.frameCount(), sequenceInfo.frameCount());
            assertEquals(info.mediaTimescale(), sequenceInfo.mediaTimescale());
            assertEquals(info.mediaDuration(), sequenceInfo.mediaDuration());
            assertEquals(info.repetitionCount(), sequenceInfo.repetitionCount());
            int[] frameDurations = info.frameDurations();
            assertEquals(5, frameDurations.length);
            assertArrayEquals(frameDurations, sequenceInfo.frameDurations());
            assertTrue(allPositive(frameDurations));
            frameDurations[0] = 0;
            assertTrue(info.frameDurations()[0] > 0);
            assertTrue(sequenceInfo.frameDurations()[0] > 0);

            List<AvifFrame> frames = reader.readAllFrames();
            assertEquals(5, frames.size());
            assertNull(reader.readFrame());

            for (int i = 0; i < 5; i++) {
                AvifFrame f = frames.get(i);
                assertEquals(150, f.width());
                assertEquals(150, f.height());
                assertEquals(i, f.frameIndex());
            }
        }
    }

    /// Verifies that AVIS sample extraction follows the `stsc` sample-to-chunk table.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceWithSampleToChunkTable() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithSplitChunks(false));
    }

    /// Verifies that AVIS sample extraction supports `co64` chunk offsets.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceWithCo64ChunkOffsets() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithSplitChunks(true));
    }

    /// Verifies that non-image AVIS tracks are not selected as the primary sequence.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceAfterIgnoredNonImageTrack() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithIgnoredLeadingTrack("soun"));
    }

    /// Verifies that an `auxv` AVIS track without an auxiliary declaration is not selected as color.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceAfterIgnoredUndeclaredAuxiliaryTrack() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithIgnoredLeadingTrack("auxv"));
    }

    /// Verifies that AVIS auxiliary tracks with mismatched references are ignored.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceIgnoresMismatchedAuxiliaryReference() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvisSequenceWithReferencedAlphaTrack(false))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.animated());
            assertFalse(info.alphaPresent());
            assertEquals(0, info.auxiliaryImageTypes().length);
            assertNotNull(reader.readFrame());
        }
    }

    /// Verifies that AVIS auxiliary tracks are bound through matching `tref/auxl` references.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceUsesMatchingAuxiliaryReference() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvisSequenceWithReferencedAlphaTrack(true))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.animated());
            assertTrue(info.alphaPresent());
            assertFalse(info.alphaPremultiplied());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_ALPHA_TYPE));
            assertNotNull(reader.readRawAlphaPlanes(0));
            assertEquals(0x80, reader.readFrame().intPixelBuffer().get(0) >>> 24);
        }
    }

    /// Verifies that AVIS `tref/prem` references expose premultiplied alpha metadata.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceUsesPremultipliedAlphaReference() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvisSequenceWithReferencedAlphaTrack(true, true))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.animated());
            assertTrue(info.alphaPresent());
            assertTrue(info.alphaPremultiplied());
        }
    }

    /// Verifies that AVIS media duration falls back to summed frame durations when `mdhd` omits it.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceDurationFromFrameDeltasWhenMediaDurationIsMissing() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithSplitChunks(false, 3, 1, 0, 1));
    }

    /// Verifies that a non-repeating AVIS edit list is reflected in sequence metadata.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceWithNonRepeatingEditList() throws IOException {
        assertMinimalAvisSequence(
                minimalAvisSequenceWithEditList(3, false, 3),
                0
        );
    }

    /// Verifies that a repeating AVIS edit list is reflected in sequence metadata.
    ///
    /// @throws IOException if the synthetic sequence cannot be read or decoded
    @Test
    void readsAnimatedSequenceWithRepeatingEditList() throws IOException {
        assertMinimalAvisSequence(minimalAvisSequenceWithEditList(6, true, 3), 1);
    }

    /// Verifies that malformed AVIS timing tables are rejected.
    @Test
    void rejectsAnimatedSequenceWithIncompleteTimingTable() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithSplitChunks(false, 2, 1))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that AVIS media duration must match summed frame durations.
    @Test
    void rejectsAnimatedSequenceWithMismatchedMediaDuration() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithSplitChunks(false, 3, 1, 2, 1))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that zero AVIS frame durations are rejected.
    @Test
    void rejectsAnimatedSequenceWithZeroFrameDuration() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithSplitChunks(false, 3, 1, 3, 0))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that repeating AVIS edit-list duration must match summed frame durations.
    @Test
    void rejectsAnimatedSequenceWithMismatchedEditListDuration() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithEditList(6, true, 2))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that malformed AVIS sample-to-chunk tables are rejected.
    @Test
    void rejectsAnimatedSequenceWithSampleToChunkTableStartingAfterFirstChunk() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithSplitChunks(false, 3, 2))
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that duplicate AVIS media handler boxes are rejected.
    @Test
    void rejectsAnimatedSequenceWithDuplicateMediaHandler() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithDuplicateMediaHandler())
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that malformed AVIS track references are rejected.
    @Test
    void rejectsAnimatedSequenceWithMalformedTrackReference() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithMalformedTrackReference())
        );
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
    }

    /// Verifies that ambiguous AVIS color-track selection is rejected.
    @Test
    void rejectsAnimatedSequenceWithMultipleColorTracks() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithMultipleColorTracks())
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that unsupported AVIS track-reference policies are rejected on selected image tracks.
    @Test
    void rejectsAnimatedSequenceWithUnsupportedTrackReference() {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithUnsupportedTrackReference())
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that AVIS alpha auxiliary tracks are exposed and decoded as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesAnimatedAlphaAuxiliaryTrackInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ANIMATED_ALPHA_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.animated());
            assertTrue(info.alphaPresent());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_ALPHA_TYPE));
            assertNull(reader.readRawDepthPlanes(0));

            AvifPlanes alphaPlanes = reader.readRawAlphaPlanes(0);
            assertNotNull(alphaPlanes);
            assertEquals(AvifBitDepth.EIGHT_BITS, alphaPlanes.bitDepth());
            assertEquals(AvifPixelFormat.I400, alphaPlanes.pixelFormat());
            assertEquals(150, alphaPlanes.codedWidth());
            assertEquals(150, alphaPlanes.codedHeight());
            assertEquals(150, alphaPlanes.renderWidth());
            assertEquals(150, alphaPlanes.renderHeight());
            assertFalse(alphaPlanes.hasChroma());
            assertTrue(alphaPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Verifies that AVIS depth auxiliary tracks are exposed and decoded as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or parsed
    @Test
    void openParsesAnimatedDepthAuxiliaryTrackInfo() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ANIMATED_DEPTH_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.animated());
            assertFalse(info.alphaPresent());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_DEPTH_TYPE));
            assertNull(reader.readRawAlphaPlanes(0));

            AvifPlanes depthPlanes = reader.readRawDepthPlanes(0);
            assertNotNull(depthPlanes);
            assertEquals(AvifBitDepth.EIGHT_BITS, depthPlanes.bitDepth());
            assertEquals(AvifPixelFormat.I400, depthPlanes.pixelFormat());
            assertEquals(150, depthPlanes.codedWidth());
            assertEquals(150, depthPlanes.codedHeight());
            assertEquals(150, depthPlanes.renderWidth());
            assertEquals(150, depthPlanes.renderHeight());
            assertFalse(depthPlanes.hasChroma());
            assertTrue(depthPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Returns whether every duration is positive.
    ///
    /// @param durations the durations to inspect
    /// @return whether every duration is positive
    private static boolean allPositive(int[] durations) {
        for (int duration : durations) {
            if (duration <= 0) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether an array contains the expected value.
    ///
    /// @param values the array to search
    /// @param expected the expected value
    /// @return whether the array contains the expected value
    private static boolean contains(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /// Asserts the synthetic minimal AVIS sequence.
    ///
    /// @param bytes the sequence bytes
    /// @throws IOException if the sequence cannot be read or decoded
    private static void assertMinimalAvisSequence(byte[] bytes) throws IOException {
        assertMinimalAvisSequence(bytes, AvifImageInfo.REPETITION_COUNT_UNKNOWN);
    }

    /// Asserts the synthetic minimal AVIS sequence.
    ///
    /// @param bytes the sequence bytes
    /// @param expectedRepetitionCount the expected sequence repetition count
    /// @throws IOException if the sequence cannot be read or decoded
    private static void assertMinimalAvisSequence(byte[] bytes, int expectedRepetitionCount) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifImageInfo info = reader.info();
            assertTrue(info.animated());
            assertEquals(64, info.width());
            assertEquals(64, info.height());
            assertEquals(3, info.frameCount());
            assertEquals(30, info.mediaTimescale());
            assertEquals(3, info.mediaDuration());
            assertEquals(expectedRepetitionCount, info.repetitionCount());

            List<AvifFrame> frames = reader.readAllFrames();
            assertEquals(3, frames.size());
            for (int i = 0; i < frames.size(); i++) {
                AvifFrame frame = frames.get(i);
                assertEquals(64, frame.width());
                assertEquals(64, frame.height());
                assertEquals(i, frame.frameIndex());
                assertEquals(OPAQUE_MID_GRAY, frame.intPixelBuffer().get(0));
            }
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that indexed AVIS sequence reads do not disturb sequential playback state.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameRandomAccessDoesNotDisturbAnimatedSequencePlayback() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ANIMATED_FIXTURE))) {
            AvifFrame third = reader.readFrame(3);
            assertEquals(3, third.frameIndex());

            AvifFrame firstSequential = reader.readFrame();
            assertNotNull(firstSequential);
            assertEquals(0, firstSequential.frameIndex());

            AvifFrame secondRandom = reader.readFrame(1);
            assertEquals(1, secondRandom.frameIndex());

            AvifFrame secondSequential = reader.readFrame();
            assertNotNull(secondSequential);
            assertEquals(1, secondSequential.frameIndex());
        }
    }

    /// Verifies that indexed raw-plane AVIS sequence reads do not disturb sequential playback state.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawColorPlanesRandomAccessDoesNotDisturbAnimatedSequencePlayback() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ANIMATED_FIXTURE))) {
            AvifPlanes fourth = reader.readRawColorPlanes(3);
            assertEquals(AvifBitDepth.EIGHT_BITS, fourth.bitDepth());
            assertEquals(AvifPixelFormat.I420, fourth.pixelFormat());
            assertEquals(150, fourth.codedWidth());
            assertEquals(150, fourth.codedHeight());
            assertEquals(150, fourth.renderWidth());
            assertEquals(150, fourth.renderHeight());

            AvifFrame firstSequential = reader.readFrame();
            assertNotNull(firstSequential);
            assertEquals(0, firstSequential.frameIndex());

            AvifPlanes second = reader.readRawColorPlanes(1);
            assertEquals(150, second.lumaPlane().width());
            assertEquals(150, second.lumaPlane().height());

            AvifFrame secondSequential = reader.readFrame();
            assertNotNull(secondSequential);
            assertEquals(1, secondSequential.frameIndex());
        }
    }

    /// Verifies that an irot rotation transform is applied to the decoded frame.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameAppliesIrotTransform() throws IOException {
        byte[] bytes = minimalAvifWithTransform("irot", (byte) 1);
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifImageInfo info = reader.info();
            assertEquals(64, info.width());
            assertEquals(64, info.height());
            assertFalse(info.hasCleanApertureCrop());
            assertEquals(1, info.rotationCode());
            assertEquals(-1, info.mirrorAxis());
            assertEquals(0, info.auxiliaryImageTypes().length);
            AvifImageTransformInfo transformInfo = info.transformInfo();
            assertNotNull(transformInfo);
            assertFalse(transformInfo.hasCleanApertureCrop());
            assertTrue(transformInfo.hasRotation());
            assertEquals(1, transformInfo.rotationCode());
            assertEquals(270, transformInfo.rotationDegreesClockwise());
            assertEquals(90, transformInfo.rotationDegreesCounterClockwise());
            assertFalse(transformInfo.hasMirror());
            assertEquals(-1, transformInfo.mirrorAxis());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            assertEquals(64, frame.width());
            assertEquals(64, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that an imir mirror transform is exposed through public metadata.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameExposesImirTransformMetadata() throws IOException {
        byte[] bytes = minimalAvifWithTransform("imir", (byte) 1);
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifImageInfo info = reader.info();
            assertFalse(info.hasCleanApertureCrop());
            assertEquals(-1, info.rotationCode());
            assertEquals(1, info.mirrorAxis());
            AvifImageTransformInfo transformInfo = info.transformInfo();
            assertNotNull(transformInfo);
            assertFalse(transformInfo.hasCleanApertureCrop());
            assertFalse(transformInfo.hasRotation());
            assertEquals(-1, transformInfo.rotationDegreesClockwise());
            assertEquals(-1, transformInfo.rotationDegreesCounterClockwise());
            assertTrue(transformInfo.hasMirror());
            assertEquals(1, transformInfo.mirrorAxis());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(64, frame.width());
            assertEquals(64, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that a clap clean-aperture transform is exposed and applied.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameAppliesCleanApertureTransform() throws IOException {
        byte[] bytes = minimalAvifWithProperty("clap", cleanAperturePropertyPayload(32, 16, 8, 4));
        try (AvifImageReader reader = AvifImageReader.open(bytes)) {
            AvifImageInfo info = reader.info();
            assertTrue(info.hasCleanApertureCrop());
            assertEquals(8, info.cleanApertureCropX());
            assertEquals(4, info.cleanApertureCropY());
            assertEquals(32, info.cleanApertureCropWidth());
            assertEquals(16, info.cleanApertureCropHeight());
            assertEquals(-1, info.rotationCode());
            assertEquals(-1, info.mirrorAxis());
            AvifImageTransformInfo transformInfo = info.transformInfo();
            assertNotNull(transformInfo);
            assertTrue(transformInfo.hasCleanApertureCrop());
            assertEquals(8, transformInfo.cleanApertureCropX());
            assertEquals(4, transformInfo.cleanApertureCropY());
            assertEquals(32, transformInfo.cleanApertureCropWidth());
            assertEquals(16, transformInfo.cleanApertureCropHeight());
            assertFalse(transformInfo.hasRotation());
            assertFalse(transformInfo.hasMirror());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(32, frame.width());
            assertEquals(16, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Builds a minimal AVIF with a transform property on the primary item.
    ///
    /// @param transformType the transform property type
    /// @param transformValue the single-byte transform value
    /// @return the AVIF container bytes
    private static byte[] minimalAvifWithTransform(String transformType, byte transformValue) {
        return minimalAvifWithProperty(transformType, new byte[]{transformValue});
    }

    /// Builds a minimal AVIF with one property on the primary item.
    ///
    /// @param propertyType the property box type
    /// @param propertyPayload the property payload bytes
    /// @return the AVIF container bytes
    private static byte[] minimalAvifWithProperty(String propertyType, byte[] propertyPayload) {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] transform = box(propertyType, propertyPayload);
        byte[] iprpBox = box("iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty(), transform),
                fullBox("ipma", 0, 0,
                        u32(1), u16(1),
                        new byte[]{4, (byte) 0x81, (byte) 0x82, 0x03, (byte) 0x84}
                )
        );
        byte[] metaFull = fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), ilocPlaceholder(), itemInfoBox(), iprpBox
        );
        int itemPayloadOffset = ftyp.length + metaFull.length + 8;
        byte[] iloc = itemLocationBox(itemPayloadOffset, av1Payload.length);
        byte[] meta = fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), iloc, itemInfoBox(), iprpBox
        );
        return concat(ftyp, meta, box("mdat", av1Payload));
    }

    /// Builds a minimal AVIF whose primary item carries one `a1op` property.
    ///
    /// @param operatingPoint the operating point value to encode
    /// @return the AVIF container bytes
    private static byte[] minimalAvifWithOperatingPoint(int operatingPoint) {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] iprpBox = box("iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty(),
                        operatingPointProperty(operatingPoint)),
                fullBox("ipma", 0, 0,
                        u32(1), u16(1),
                        new byte[]{4, (byte) 0x81, (byte) 0x82, 0x03, 0x04}
                )
        );
        byte[] metaFull = fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), ilocPlaceholder(), itemInfoBox(), iprpBox
        );
        int itemPayloadOffset = ftyp.length + metaFull.length + 8;
        byte[] iloc = itemLocationBox(itemPayloadOffset, av1Payload.length);
        byte[] meta = fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), iloc, itemInfoBox(), iprpBox
        );
        return concat(ftyp, meta, box("mdat", av1Payload));
    }

    /// Creates a `clap` property payload.
    ///
    /// @param width the clean-aperture width
    /// @param height the clean-aperture height
    /// @param x the clean-aperture x coordinate
    /// @param y the clean-aperture y coordinate
    /// @return the `clap` property payload bytes
    private static byte[] cleanAperturePropertyPayload(int width, int height, int x, int y) {
        return concat(
                u32(width), u32(1),
                u32(height), u32(1),
                u32(x), u32(1),
                u32(y), u32(1)
        );
    }

    /// Creates an iloc box with placeholder (zero) offsets.
    ///
    /// @return the placeholder iloc box bytes
    private static byte[] ilocPlaceholder() {
        return fullBox("iloc", 0, 0,
                new byte[]{0x44, 0x40},
                u16(1), u16(1),
                u16(0), u32(0),
                u16(1), u32(0),
                u32(0)
        );
    }
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameDecodesGrid1x5Fixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_SOFA_GRID_1X5_FIXTURE))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertTrue(frame.width() > 0);
            assertTrue(frame.height() > 0);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that grid raw-plane composition exposes the composed raw canvas.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawColorPlanesComposesGridFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_SOFA_GRID_1X5_FIXTURE))) {
            AvifPlanes planes = reader.readRawColorPlanes(0);

            assertEquals(AvifBitDepth.EIGHT_BITS, planes.bitDepth());
            assertEquals(AvifPixelFormat.I420, planes.pixelFormat());
            assertEquals(1024, planes.codedWidth());
            assertEquals(770, planes.codedHeight());
            assertEquals(1024, planes.renderWidth());
            assertEquals(770, planes.renderHeight());
            assertEquals(1024, planes.lumaPlane().width());
            assertEquals(770, planes.lumaPlane().height());
            assertTrue(planes.lumaPlane().sampleBuffer().isReadOnly());

            AvifPlane chromaU = planes.chromaUPlane();
            AvifPlane chromaV = planes.chromaVPlane();
            assertNotNull(chromaU);
            assertNotNull(chromaV);
            assertEquals(512, chromaU.width());
            assertEquals(385, chromaU.height());
            assertEquals(512, chromaV.width());
            assertEquals(385, chromaV.height());
        }
    }

    /// Verifies that alpha-grid raw-plane composition exposes a single alpha canvas.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawAlphaPlanesComposesAlphaGridFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(
                testResourceBytes(LIBAVIF_COLOR_GRID_ALPHA_GRID_GAINMAP_FIXTURE)
        )) {
            AvifImageInfo info = reader.info();
            assertTrue(info.alphaPresent());

            AvifPlanes alphaPlanes = reader.readRawAlphaPlanes(0);
            assertNotNull(alphaPlanes);
            assertEquals(AvifPixelFormat.I400, alphaPlanes.pixelFormat());
            assertEquals(info.width(), alphaPlanes.codedWidth());
            assertEquals(info.height(), alphaPlanes.codedHeight());
            assertEquals(info.width(), alphaPlanes.renderWidth());
            assertEquals(info.height(), alphaPlanes.renderHeight());
            assertFalse(alphaPlanes.hasChroma());
            assertEquals(info.width(), alphaPlanes.lumaPlane().width());
            assertEquals(info.height(), alphaPlanes.lumaPlane().height());
            assertTrue(alphaPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Verifies that a depth grid can be decoded and composed as raw planes.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readRawDepthPlanesComposesDepthGridFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(alphaGridFixtureWithDepthAuxiliaryType())) {
            AvifImageInfo info = reader.info();
            assertFalse(info.alphaPresent());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_DEPTH_TYPE));
            assertNull(reader.readRawAlphaPlanes(0));

            AvifPlanes depthPlanes = reader.readRawDepthPlanes(0);
            assertNotNull(depthPlanes);
            assertEquals(AvifBitDepth.TEN_BITS, depthPlanes.bitDepth());
            assertEquals(AvifPixelFormat.I400, depthPlanes.pixelFormat());
            assertEquals(info.width(), depthPlanes.codedWidth());
            assertEquals(info.height(), depthPlanes.codedHeight());
            assertEquals(info.width(), depthPlanes.renderWidth());
            assertEquals(info.height(), depthPlanes.renderHeight());
            assertFalse(depthPlanes.hasChroma());
            assertTrue(depthPlanes.lumaPlane().sampleBuffer().isReadOnly());
        }
    }

    /// Verifies that a fixture with an alpha auxiliary image parses successfully.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsAlphaFixtureGracefully() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ALPHA_NO_IROT_FIXTURE))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertTrue(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            String[] auxiliaryImageTypes = info.auxiliaryImageTypes();
            assertTrue(contains(auxiliaryImageTypes, AUXILIARY_ALPHA_TYPE));
            auxiliaryImageTypes[0] = "mutated";
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_ALPHA_TYPE));
            AvifAuxiliaryImageInfo[] auxiliaryImages = info.auxiliaryImages();
            assertEquals(1, auxiliaryImages.length);
            AvifAuxiliaryImageInfo auxiliaryImage = auxiliaryImages[0];
            assertTrue(auxiliaryImage.itemId() > 0);
            assertEquals(AUXILIARY_ALPHA_TYPE, auxiliaryImage.auxiliaryType());
            assertEquals("av01", auxiliaryImage.itemType());
            assertTrue(auxiliaryImage.isAlpha());
            assertTrue(auxiliaryImage.width() > 0);
            assertTrue(auxiliaryImage.height() > 0);
            assertNotNull(auxiliaryImage.bitDepth());
            assertNotNull(auxiliaryImage.pixelFormat());
            auxiliaryImages[0] = new AvifAuxiliaryImageInfo(999, "test", "mime", -1, -1, null, null);
            assertEquals(auxiliaryImage.itemId(), info.auxiliaryImages()[0].itemId());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertTrue(frame.width() > 0);
            assertTrue(frame.height() > 0);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that a synthetic AVIF image with alpha decodes with non-opaque pixels.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readFrameDecodesSyntheticAlpha() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(syntheticAlphaAvif())) {
            AvifImageInfo info = reader.info();

            assertEquals(64, info.width());
            assertEquals(64, info.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, info.bitDepth());
            assertTrue(info.alphaPresent());
            assertFalse(info.alphaPremultiplied());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_ALPHA_TYPE));
            AvifAuxiliaryImageInfo[] auxiliaryImages = info.auxiliaryImages();
            assertEquals(1, auxiliaryImages.length);
            AvifAuxiliaryImageInfo auxiliaryImage = auxiliaryImages[0];
            assertEquals(2, auxiliaryImage.itemId());
            assertEquals(AUXILIARY_ALPHA_TYPE, auxiliaryImage.auxiliaryType());
            assertEquals("av01", auxiliaryImage.itemType());
            assertTrue(auxiliaryImage.isAlpha());
            assertEquals(64, auxiliaryImage.width());
            assertEquals(64, auxiliaryImage.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, auxiliaryImage.bitDepth());
            assertEquals(AvifPixelFormat.I400, auxiliaryImage.pixelFormat());

            AvifPlanes alphaPlanes = reader.readRawAlphaPlanes(0);
            assertNotNull(alphaPlanes);
            assertEquals(AvifBitDepth.EIGHT_BITS, alphaPlanes.bitDepth());
            assertEquals(AvifPixelFormat.I400, alphaPlanes.pixelFormat());
            assertEquals(64, alphaPlanes.codedWidth());
            assertEquals(64, alphaPlanes.codedHeight());
            assertFalse(alphaPlanes.hasChroma());
            assertEquals(64, alphaPlanes.lumaPlane().width());
            assertEquals(64, alphaPlanes.lumaPlane().height());
            assertTrue(alphaPlanes.lumaPlane().sample(0, 0) < 255);

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            AvifFrame intFrame = frame;

            int[] pixels = intFrame.intPixels();
            assertEquals(64 * 64, pixels.length);
            assertTrue((pixels[0] >>> 24) != 0xFF, "synthetic alpha should produce non-opaque pixels");
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that a still-image `prem` item reference exposes premultiplied alpha metadata.
    ///
    /// @throws IOException if the fixture cannot be read
    @Test
    void openParsesStillPremultipliedAlphaReference() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(syntheticAlphaAvif(true))) {
            AvifImageInfo info = reader.info();

            assertTrue(info.alphaPresent());
            assertTrue(info.alphaPremultiplied());
        }
    }

    /// Verifies that a still-image `prem` item reference must target the resolved alpha item.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsStillPremultipliedReferenceToNonAlphaItem() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(syntheticAlphaAvifWithPremTarget(99))
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that still-image alpha item dimensions are validated before decoding.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsStillAlphaDimensionMismatch() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(syntheticAlphaAvifWithAlphaDimensions(32, 64))
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that alpha auxiliary images cannot silently request a non-default AV1 operating point.
    ///
    /// @throws IOException if an unexpected I/O error occurs
    @Test
    void rejectsAlphaAuxiliaryNonDefaultOperatingPoint() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(syntheticAlphaAvifWithAlphaOperatingPoint(1))
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that an AVIS `prem` track reference must target the selected alpha track.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsAvisPremultipliedReferenceWithoutMatchingAlphaTrack() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithPremReferenceToMissingAlphaTrack())
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that AVIS alpha auxiliary track dimensions are validated during container parsing.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsAvisAlphaTrackDimensionMismatch() throws IOException {
        AvifDecodeException exception = assertThrows(
                AvifDecodeException.class,
                () -> AvifImageReader.open(minimalAvisSequenceWithMismatchedAlphaDimensions())
        );
        assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
    }

    /// Verifies that a libavif fixture with a legacy alpha auxiliary item missing `ispe` is accepted.
    ///
    /// @throws IOException if the fixture cannot be read
    @Test
    void readsAlphaMissingIspeFixture() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ALPHA_NOISPE_FIXTURE))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.alphaPresent());
            assertFalse(info.alphaPremultiplied());
            assertEquals(1, info.frameCount());
            assertTrue(contains(info.auxiliaryImageTypes(), AUXILIARY_ALPHA_TYPE));

            AvifAuxiliaryImageInfo[] auxiliaryImages = info.auxiliaryImages();
            assertEquals(1, auxiliaryImages.length);
            AvifAuxiliaryImageInfo alphaInfo = auxiliaryImages[0];
            assertEquals(AUXILIARY_ALPHA_TYPE, alphaInfo.auxiliaryType());
            assertEquals(info.width(), alphaInfo.width());
            assertEquals(info.height(), alphaInfo.height());
            assertEquals(AvifBitDepth.EIGHT_BITS, alphaInfo.bitDepth());
            assertEquals(AvifPixelFormat.I400, alphaInfo.pixelFormat());

            AvifPlanes alphaPlanes = reader.readRawAlphaPlanes(0);
            assertNotNull(alphaPlanes);
            assertEquals(info.width(), alphaPlanes.codedWidth());
            assertEquals(info.height(), alphaPlanes.codedHeight());
            assertFalse(alphaPlanes.hasChroma());

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(info.width(), frame.width());
            assertEquals(info.height(), frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that truncated ispe payload is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsTruncatedIspe() throws IOException {
        byte[] valid = minimalAvifStillImage();
        int ispeOffset = indexOf(valid, fourCc("ispe")) + 12;
        byte[] truncated = truncateAfter(valid, ispeOffset);
        AvifDecodeException ex = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(truncated));
        assertEquals(AvifErrorCode.TRUNCATED_DATA, ex.code());
    }

    /// Verifies that truncated av1C payload is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsTruncatedAv1C() throws IOException {
        byte[] valid = minimalAvifStillImage();
        int av1cOffset = indexOf(valid, fourCc("av1C")) + 8;
        byte[] truncated = truncateAfter(valid, av1cOffset);
        AvifDecodeException ex = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(truncated));
        assertEquals(AvifErrorCode.TRUNCATED_DATA, ex.code());
    }

    /// Verifies that missing ispe on the primary item is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsMissingIspe() throws IOException {
        byte[] bytes = minimalAvifWithoutProperty("ispe");
        AvifDecodeException ex = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(bytes));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, ex.code());
    }

    /// Verifies that missing av1C on the primary item is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsMissingAv1C() throws IOException {
        byte[] bytes = minimalAvifWithoutProperty("av1C");
        AvifDecodeException ex = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(bytes));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, ex.code());
    }

    /// Verifies that duplicate hdlr in meta is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsDuplicateHdlr() throws IOException {
        byte[] ftyp = fileTypeBox();
        byte[] placeholderMeta = fullBox("meta", 0, 0, handlerBox());
        int payloadOffset = ftyp.length + placeholderMeta.length + 8;
        byte[] iloc = itemLocationBox(payloadOffset, 0);
        byte[] meta = fullBox("meta", 0, 0, handlerBox(), handlerBox(), primaryItemBox(), iloc, itemInfoBox(), itemPropertiesBox());
        AvifDecodeException ex = assertThrows(AvifDecodeException.class,
                () -> AvifImageReader.open(concat(ftyp, meta)));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, ex.code());
    }

    /// Verifies that ipma referencing a missing property is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsIpmaWithMissingProperty() throws IOException {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] metaFirst = minimalMetaWithoutIpma(0, av1Payload.length);
        int itemPayloadOffset = ftyp.length + metaFirst.length + 8;
        byte[] ispe = imageSpatialExtentsProperty();
        // ipma with property index 123 (which does not exist in ipco)
        byte[] iprpBox = box(
                "iprp",
                box("ipco", ispe),
                fullBox("ipma", 0, 0, u32(1), u16(1), new byte[]{1, (byte) 0x80})
        );
        byte[] meta = fullBox("meta", 0, 0,
                handlerBox(),
                primaryItemBox(),
                itemLocationBox(itemPayloadOffset, av1Payload.length),
                itemInfoBox(),
                iprpBox
        );
        AvifDecodeException ex = assertThrows(AvifDecodeException.class,
                () -> AvifImageReader.open(concat(ftyp, meta, box("mdat", av1Payload))));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, ex.code());
    }

    /// Verifies that an essential unknown property on the primary item is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsEssentialUnknownProperty() throws IOException {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] metaFirst = minimalMetaWithoutIpma(0, av1Payload.length);
        int itemPayloadOffset = ftyp.length + metaFirst.length + 8;
        // "xxxx" is an unknown property type, marked essential (0x81)
        byte[] unknownProp = box("xxxx", new byte[]{1, 2, 3, 4});
        byte[] iprpBox = box(
                "iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty(), unknownProp),
                fullBox("ipma", 0, 0,
                        u32(1),
                        u16(1),
                        new byte[]{4, (byte) 0x81, (byte) 0x82, 0x03, (byte) 0x84}
                )
        );
        byte[] meta = fullBox("meta", 0, 0,
                handlerBox(),
                primaryItemBox(),
                itemLocationBox(itemPayloadOffset, av1Payload.length),
                itemInfoBox(),
                iprpBox
        );
        AvifDecodeException ex = assertThrows(AvifDecodeException.class,
                () -> AvifImageReader.open(concat(ftyp, meta, box("mdat", av1Payload))));
        assertEquals(AvifErrorCode.MISSING_IMAGE_ITEM, ex.code());
    }

    /// Verifies that an input without a valid ftyp box is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsMissingFtyp() throws IOException {
        byte[] bytes = box("xxxx", new byte[0]);
        AvifDecodeException ex = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(bytes));
        assertEquals(AvifErrorCode.INVALID_FTYP, ex.code());
    }

    /// Verifies that a meta box without hdlr is rejected.
    ///
    /// @throws IOException if an unexpected error occurs
    @Test
    void rejectsMetaWithoutHdlr() throws IOException {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] meta = fullBox("meta", 0, 0, primaryItemBox());
        AvifDecodeException ex = assertThrows(AvifDecodeException.class,
                () -> AvifImageReader.open(concat(ftyp, meta, box("mdat", av1Payload))));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, ex.code());
    }

    /// Builds a minimal AVIF with one property omitted.
    ///
    /// @param omitType the property type to omit
    /// @return the AVIF container bytes
    private static byte[] minimalAvifWithoutProperty(String omitType) {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] metaFirst = minimalMetaWithoutIpma(0, av1Payload.length);
        int itemPayloadOffset = ftyp.length + metaFirst.length + 8;
        ArrayList<byte[]> properties = new ArrayList<>();
        if (!"ispe".equals(omitType)) {
            properties.add(imageSpatialExtentsProperty());
        }
        if (!"av1C".equals(omitType)) {
            properties.add(av1ConfigProperty());
        }
        if (!"colr".equals(omitType)) {
            properties.add(colorProperty());
        }
        byte[] iprpBox = box("iprp", box("ipco", properties.toArray(byte[][]::new)),
                fullBox("ipma", 0, 0, u32(1), u16(1),
                        new byte[]{(byte) properties.size(),
                                (byte) 0x81,
                                properties.size() >= 2 ? (byte) 0x82 : (byte) 0x00,
                                properties.size() >= 3 ? (byte) 0x03 : (byte) 0x00}));
        byte[] meta = fullBox("meta", 0, 0,
                handlerBox(),
                primaryItemBox(),
                itemLocationBox(itemPayloadOffset, av1Payload.length),
                itemInfoBox(),
                iprpBox
        );
        return concat(ftyp, meta, box("mdat", av1Payload));
    }

    /// Builds a placeholder meta box used to measure iprp size.
    ///
    /// @param itemPayloadOffset ignored
    /// @param itemPayloadLength ignored
    /// @return the placeholder meta box bytes
    private static byte[] minimalMetaWithoutIpma(int itemPayloadOffset, int itemPayloadLength) {
        return fullBox("meta", 0, 0,
                handlerBox(),
                primaryItemBox(),
                itemLocationBox(itemPayloadOffset, itemPayloadLength),
                itemInfoBox()
        );
    }

    /// Returns the index of the first occurrence of `pattern` in `data`.
    ///
    /// @param data the byte array to search
    /// @param pattern the pattern to search for
    /// @return the index of the first occurrence, or -1
    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /// Returns a copy of `data` truncated after `length` bytes.
    ///
    /// @param data the source byte array
    /// @param length the new length
    /// @return the truncated byte array
    private static byte[] truncateAfter(byte[] data, int length) {
        return Arrays.copyOf(data, Math.min(length, data.length));
    }

    /// Creates a synthetic AVIF still image with a primary color item and an alpha auxiliary item.
    ///
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvif() {
        return syntheticAlphaAvif(false);
    }

    /// Creates a synthetic AVIF still image with a primary color item and an alpha auxiliary item.
    ///
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvif(boolean premultiplied) {
        return syntheticAlphaAvif(premultiplied, premultiplied ? 2 : 0, 64, 64);
    }

    /// Creates a synthetic AVIF still image with a `prem` reference to a custom target item.
    ///
    /// @param premTargetId the item ID referenced by the `prem` reference
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvifWithPremTarget(int premTargetId) {
        return syntheticAlphaAvif(true, premTargetId, 64, 64);
    }

    /// Creates a synthetic AVIF still image with custom alpha item dimensions.
    ///
    /// @param alphaWidth the alpha item width
    /// @param alphaHeight the alpha item height
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvifWithAlphaDimensions(int alphaWidth, int alphaHeight) {
        return syntheticAlphaAvif(false, 0, alphaWidth, alphaHeight);
    }

    /// Creates a synthetic AVIF still image with an alpha auxiliary item carrying `a1op`.
    ///
    /// @param operatingPoint the alpha item operating point
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvifWithAlphaOperatingPoint(int operatingPoint) {
        byte[] colorPayload = av1StillPicturePayload();
        byte[] alphaPayload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] firstMeta = buildDualItemMetaWithAlphaOperatingPoint(
                colorPayload.length + alphaPayload.length,
                operatingPoint
        );
        int colorOffset = ftyp.length + firstMeta.length + 8;
        int alphaOffset = colorOffset + colorPayload.length;
        byte[] meta = buildDualItemMetaWithAlphaOperatingPoint(
                colorOffset,
                colorPayload.length,
                alphaOffset,
                alphaPayload.length,
                operatingPoint
        );
        return concat(ftyp, meta, box("mdat", concat(colorPayload, alphaPayload)));
    }

    /// Creates a synthetic AVIF still image with configurable alpha metadata.
    ///
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @param premTargetId the item ID referenced by the `prem` reference, or `0`
    /// @param alphaWidth the alpha item width
    /// @param alphaHeight the alpha item height
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticAlphaAvif(
            boolean premultiplied,
            int premTargetId,
            int alphaWidth,
            int alphaHeight
    ) {
        byte[] colorPayload = av1StillPicturePayload();
        byte[] alphaPayload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] firstMeta = buildDualItemMeta(
                colorPayload.length + alphaPayload.length,
                premultiplied,
                premTargetId,
                alphaWidth,
                alphaHeight
        );
        int metaSize = firstMeta.length;
        int mdatHeaderSize = 8;
        int colorOffset = ftyp.length + metaSize + mdatHeaderSize;
        int alphaOffset = colorOffset + colorPayload.length;
        byte[] meta = buildDualItemMeta(
                colorOffset,
                colorPayload.length,
                alphaOffset,
                alphaPayload.length,
                premultiplied,
                premTargetId,
                alphaWidth,
                alphaHeight
        );
        return concat(ftyp, meta, box("mdat", concat(colorPayload, alphaPayload)));
    }

    /// Creates a dual-item meta box with placeholder sizes.
    ///
    /// @param totalPayloadLength the total mdat payload length
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(int totalPayloadLength, boolean premultiplied) {
        return buildDualItemMeta(0, 0, 0, totalPayloadLength, premultiplied);
    }

    /// Creates a dual-item meta box with placeholder sizes and custom alpha metadata.
    ///
    /// @param totalPayloadLength the total mdat payload length
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @param premTargetId the item ID referenced by the `prem` reference, or `0`
    /// @param alphaWidth the alpha item width
    /// @param alphaHeight the alpha item height
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(
            int totalPayloadLength,
            boolean premultiplied,
            int premTargetId,
            int alphaWidth,
            int alphaHeight
    ) {
        return buildDualItemMeta(0, 0, 0, totalPayloadLength, premultiplied, premTargetId, alphaWidth, alphaHeight);
    }

    /// Creates a dual-item meta box with actual payload offsets and lengths.
    ///
    /// @param colorOffset the absolute color item payload offset
    /// @param colorLength the color item payload length
    /// @param alphaOffset the absolute alpha item payload offset
    /// @param alphaLength the alpha item payload length
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(
            int colorOffset,
            int colorLength,
            int alphaOffset,
            int alphaLength,
            boolean premultiplied
    ) {
        return buildDualItemMeta(colorOffset, colorLength, alphaOffset, alphaLength,
                premultiplied, premultiplied ? 2 : 0, 64, 64);
    }

    /// Creates a dual-item meta box with actual payload offsets, lengths, and custom alpha metadata.
    ///
    /// @param colorOffset the absolute color item payload offset
    /// @param colorLength the color item payload length
    /// @param alphaOffset the absolute alpha item payload offset
    /// @param alphaLength the alpha item payload length
    /// @param premultiplied whether to add a `prem` item reference from color to alpha
    /// @param premTargetId the item ID referenced by the `prem` reference, or `0`
    /// @param alphaWidth the alpha item width
    /// @param alphaHeight the alpha item height
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(
            int colorOffset,
            int colorLength,
            int alphaOffset,
            int alphaLength,
            boolean premultiplied,
            int premTargetId,
            int alphaWidth,
            int alphaHeight
    ) {
        byte[] iloc = fullBox("iloc", 0, 0,
                new byte[]{0x44, 0x40},
                u16(2),
                u16(1),
                u16(0),
                u32(0),
                u16(1),
                u32(colorOffset),
                u32(colorLength),
                u16(2),
                u16(0),
                u32(0),
                u16(1),
                u32(alphaOffset),
                u32(alphaLength)
        );

        byte[] iinf = fullBox("iinf", 0, 0,
                u16(2),
                fullBox("infe", 2, 0, u16(1), u16(0), fourCc("av01"), new byte[]{0}),
                fullBox("infe", 2, 0, u16(2), u16(0), fourCc("av01"), new byte[]{0})
        );

        byte[] iprp = box("iprp",
                box("ipco",
                        imageSpatialExtentsProperty(),
                        av1ConfigProperty(),
                        colorProperty(),
                        imageSpatialExtentsProperty(alphaWidth, alphaHeight),
                        monochromeAv1ConfigProperty(),
                        auxCAlphaProperty()
                ),
                fullBox("ipma", 0, 0,
                        u32(2),
                        u16(1),
                        new byte[]{3, (byte) 0x81, (byte) 0x82, 0x03},
                        u16(2),
                        new byte[]{3, (byte) 0x84, (byte) 0x85, (byte) 0x86}
                )
        );

        byte[] iref = refBox(1, 2, premultiplied ? premTargetId : 0);

        return fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), iloc, iinf, iprp, iref);
    }

    /// Creates a dual-item meta box with placeholder sizes and alpha operating-point metadata.
    ///
    /// @param totalPayloadLength the total mdat payload length
    /// @param operatingPoint the alpha item operating point
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMetaWithAlphaOperatingPoint(int totalPayloadLength, int operatingPoint) {
        return buildDualItemMetaWithAlphaOperatingPoint(0, 0, 0, totalPayloadLength, operatingPoint);
    }

    /// Creates a dual-item meta box with actual payload offsets and alpha operating-point metadata.
    ///
    /// @param colorOffset the absolute color item payload offset
    /// @param colorLength the color item payload length
    /// @param alphaOffset the absolute alpha item payload offset
    /// @param alphaLength the alpha item payload length
    /// @param operatingPoint the alpha item operating point
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMetaWithAlphaOperatingPoint(
            int colorOffset,
            int colorLength,
            int alphaOffset,
            int alphaLength,
            int operatingPoint
    ) {
        byte[] iloc = fullBox("iloc", 0, 0,
                new byte[]{0x44, 0x40},
                u16(2),
                u16(1),
                u16(0),
                u32(0),
                u16(1),
                u32(colorOffset),
                u32(colorLength),
                u16(2),
                u16(0),
                u32(0),
                u16(1),
                u32(alphaOffset),
                u32(alphaLength)
        );

        byte[] iinf = fullBox("iinf", 0, 0,
                u16(2),
                fullBox("infe", 2, 0, u16(1), u16(0), fourCc("av01"), new byte[]{0}),
                fullBox("infe", 2, 0, u16(2), u16(0), fourCc("av01"), new byte[]{0})
        );

        byte[] iprp = box("iprp",
                box("ipco",
                        imageSpatialExtentsProperty(),
                        av1ConfigProperty(),
                        colorProperty(),
                        imageSpatialExtentsProperty(64, 64),
                        monochromeAv1ConfigProperty(),
                        auxCAlphaProperty(),
                        operatingPointProperty(operatingPoint)
                ),
                fullBox("ipma", 0, 0,
                        u32(2),
                        u16(1),
                        new byte[]{3, (byte) 0x81, (byte) 0x82, 0x03},
                        u16(2),
                        new byte[]{4, (byte) 0x84, (byte) 0x85, (byte) 0x86, 0x07}
                )
        );

        return fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), iloc, iinf, iprp, refBox(1, 2, false));
    }

    /// Creates a synthetic progressive AVIF whose dependency carries `a1op`.
    ///
    /// @param operatingPoint the dependency operating point
    /// @return the complete AVIF test file bytes
    private static byte[] syntheticProgressiveAvifWithDependencyOperatingPoint(int operatingPoint) {
        byte[] primaryPayload = av1StillPicturePayload();
        byte[] dependencyPayload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] firstMeta = buildProgressiveDependencyOperatingPointMeta(
                0,
                primaryPayload.length,
                0,
                dependencyPayload.length,
                operatingPoint
        );
        int primaryOffset = ftyp.length + firstMeta.length + 8;
        int dependencyOffset = primaryOffset + primaryPayload.length;
        byte[] meta = buildProgressiveDependencyOperatingPointMeta(
                primaryOffset,
                primaryPayload.length,
                dependencyOffset,
                dependencyPayload.length,
                operatingPoint
        );
        return concat(ftyp, meta, box("mdat", concat(primaryPayload, dependencyPayload)));
    }

    /// Creates a two-item progressive meta box whose dependency carries `a1op`.
    ///
    /// @param primaryOffset the absolute primary item payload offset
    /// @param primaryLength the primary item payload length
    /// @param dependencyOffset the absolute dependency item payload offset
    /// @param dependencyLength the dependency item payload length
    /// @param operatingPoint the dependency operating point
    /// @return the progressive meta box bytes
    private static byte[] buildProgressiveDependencyOperatingPointMeta(
            int primaryOffset,
            int primaryLength,
            int dependencyOffset,
            int dependencyLength,
            int operatingPoint
    ) {
        byte[] iloc = fullBox("iloc", 0, 0,
                new byte[]{0x44, 0x40},
                u16(2),
                u16(1),
                u16(0),
                u32(0),
                u16(1),
                u32(primaryOffset),
                u32(primaryLength),
                u16(2),
                u16(0),
                u32(0),
                u16(1),
                u32(dependencyOffset),
                u32(dependencyLength)
        );
        byte[] iinf = fullBox("iinf", 0, 0,
                u16(2),
                fullBox("infe", 2, 0, u16(1), u16(0), fourCc("av01"), new byte[]{0}),
                fullBox("infe", 2, 0, u16(2), u16(0), fourCc("av01"), new byte[]{0})
        );
        byte[] iprp = box("iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty(),
                        operatingPointProperty(operatingPoint)),
                fullBox("ipma", 0, 0,
                        u32(2),
                        u16(1),
                        new byte[]{3, (byte) 0x81, (byte) 0x82, 0x03},
                        u16(2),
                        new byte[]{4, (byte) 0x81, (byte) 0x82, 0x03, 0x04}
                )
        );
        byte[] iref = fullBox("iref", 0, 0, box("prog", u16(1), u16(1), u16(2)));
        return fullBox("meta", 0, 0, handlerBox(), primaryItemBox(), iloc, iinf, iprp, iref);
    }

    /// Creates an `iref` box with an `auxl` reference from one item to another.
    ///
    /// @param fromId the from item id
    /// @param toId the to item id
    /// @param premultiplied whether to include a `prem` reference
    /// @return the iref box bytes
    private static byte[] refBox(int fromId, int toId, boolean premultiplied) {
        return refBox(fromId, toId, premultiplied ? toId : 0);
    }

    /// Creates an `iref` box with an `auxl` reference and an optional custom `prem` target.
    ///
    /// @param fromId the from item id
    /// @param toId the auxiliary target item id
    /// @param premTargetId the premultiplied-alpha target item id, or `0`
    /// @return the iref box bytes
    private static byte[] refBox(int fromId, int toId, int premTargetId) {
        return fullBox("iref", 0, 0,
                box("auxl", u16(fromId), u16(1), u16(toId)),
                premTargetId > 0 ? box("prem", u16(fromId), u16(1), u16(premTargetId)) : new byte[0]
        );
    }

    /// Creates a monochrome `av1C` property matching the 8-bit test AV1 payload.
    ///
    /// @return the monochrome av1C property bytes
    private static byte[] monochromeAv1ConfigProperty() {
        return box("av1C", new byte[]{(byte) 0x81, 0x00, 0x1C, 0x00});
    }

    /// Creates an `auxC` property for alpha auxiliary images.
    ///
    /// @return the auxC property bytes
    private static byte[] auxCAlphaProperty() {
        byte[] urns = (AUXILIARY_ALPHA_TYPE + "\0").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return fullBox("auxC", 0, 0, urns);
    }

    /// Creates a minimal AVIF still image with one primary AV1 image item.
    ///
    /// @return the complete AVIF test file bytes
    private static byte[] minimalAvifStillImage() {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] firstMeta = metaBox(0, av1Payload.length);
        int itemPayloadOffset = ftyp.length + firstMeta.length + 8;
        byte[] meta = metaBox(itemPayloadOffset, av1Payload.length);
        return concat(ftyp, meta, box("mdat", av1Payload));
    }

    /// Creates a minimal AVIS sequence with two chunks and padding between them.
    ///
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithSplitChunks(boolean useCo64) {
        return minimalAvisSequenceWithSplitChunks(useCo64, 3, 1, 3, 1);
    }

    /// Creates a minimal AVIS sequence with configurable timing and sample-to-chunk tables.
    ///
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithSplitChunks(
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk
    ) {
        return minimalAvisSequenceWithSplitChunks(useCo64, timingSampleCount, firstStscChunk, 3, 1);
    }

    /// Creates a minimal AVIS sequence with configurable duration and sample tables.
    ///
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithSplitChunks(
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta
    ) {
        return minimalAvisSequenceWithSplitChunks(
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                mediaDuration,
                null
        );
    }

    /// Creates a minimal AVIS sequence with an edit list.
    ///
    /// @param trackDuration the duration written to `tkhd`
    /// @param repeating whether the edit list signals repetition semantics
    /// @param editListSegmentDuration the edit-list segment duration
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithEditList(
            int trackDuration,
            boolean repeating,
            int editListSegmentDuration
    ) {
        return minimalAvisSequenceWithSplitChunks(
                false,
                3,
                1,
                3,
                1,
                trackDuration,
                editListBox(repeating, editListSegmentDuration)
        );
    }

    /// Creates a minimal AVIS sequence with configurable track and edit-list metadata.
    ///
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param trackDuration the duration written to `tkhd`
    /// @param editList the optional `edts` box
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithSplitChunks(
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int trackDuration,
            byte @Nullable [] editList
    ) {
        byte[] sample0 = av1StillPicturePayload();
        byte[] sample1 = av1StillPicturePayload();
        byte[] sample2 = av1StillPicturePayload();
        byte[] chunkPadding = new byte[]{0x55, 0x66, 0x77, 0x00, 0x11};
        byte[] mdatPayload = concat(sample0, chunkPadding, sample1, sample2);

        byte[] ftyp = sequenceFileTypeBox();
        byte[] firstMoov = minimalAvisMoovBox(
                new int[]{0, 0},
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                trackDuration,
                editList,
                sample0.length,
                sample1.length,
                sample2.length
        );
        int chunk0Offset = ftyp.length + firstMoov.length + 8;
        int chunk1Offset = chunk0Offset + sample0.length + chunkPadding.length;
        byte[] moov = minimalAvisMoovBox(
                new int[]{chunk0Offset, chunk1Offset},
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                trackDuration,
                editList,
                sample0.length,
                sample1.length,
                sample2.length
        );
        return concat(ftyp, moov, box("mdat", mdatPayload));
    }

    /// Creates a minimal AVIS sequence where an ignored track appears before the image track.
    ///
    /// @param leadingHandlerType the ignored leading track handler type
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithIgnoredLeadingTrack(String leadingHandlerType) {
        byte[] sample0 = av1StillPicturePayload();
        byte[] sample1 = av1StillPicturePayload();
        byte[] sample2 = av1StillPicturePayload();
        byte[] chunkPadding = new byte[]{0x55, 0x66, 0x77, 0x00, 0x11};
        byte[] mdatPayload = concat(sample0, chunkPadding, sample1, sample2);
        byte[] ftyp = sequenceFileTypeBox();

        byte[] firstMoov = minimalAvisMoovBoxWithLeadingIgnoredTrack(
                leadingHandlerType,
                new int[]{0, 0},
                sample0.length,
                sample1.length,
                sample2.length
        );
        int chunk0Offset = ftyp.length + firstMoov.length + 8;
        int chunk1Offset = chunk0Offset + sample0.length + chunkPadding.length;
        byte[] moov = minimalAvisMoovBoxWithLeadingIgnoredTrack(
                leadingHandlerType,
                new int[]{chunk0Offset, chunk1Offset},
                sample0.length,
                sample1.length,
                sample2.length
        );
        return concat(ftyp, moov, box("mdat", mdatPayload));
    }

    /// Creates a minimal AVIS sequence with an optionally matching alpha track reference.
    ///
    /// @param includeMatchingAlpha whether to include a correctly referenced alpha track after a mismatched one
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithReferencedAlphaTrack(boolean includeMatchingAlpha) {
        return minimalAvisSequenceWithReferencedAlphaTrack(includeMatchingAlpha, false);
    }

    /// Creates a minimal AVIS sequence with an optionally matching alpha track reference.
    ///
    /// @param includeMatchingAlpha whether to include a correctly referenced alpha track after a mismatched one
    /// @param premultiplied whether to add a `prem` track reference from color to the matching alpha track
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithReferencedAlphaTrack(
            boolean includeMatchingAlpha,
            boolean premultiplied
    ) {
        byte[] sample0 = av1StillPicturePayload();
        byte[] sample1 = av1StillPicturePayload();
        byte[] sample2 = av1StillPicturePayload();
        byte[] chunkPadding = new byte[]{0x55, 0x66, 0x77, 0x00, 0x11};
        byte[] colorMdatPayload = concat(sample0, chunkPadding, sample1, sample2);
        byte[] alphaMdatPayload = includeMatchingAlpha
                ? concat(sample0, chunkPadding, sample1, sample2)
                : new byte[0];
        byte[] ftyp = sequenceFileTypeBox();

        byte[] firstMoov = minimalAvisMoovBoxWithReferencedAlphaTrack(
                new int[]{0, 0},
                new int[]{0, 0},
                includeMatchingAlpha,
                premultiplied,
                sample0.length,
                sample1.length,
                sample2.length
        );
        int colorChunk0Offset = ftyp.length + firstMoov.length + 8;
        int colorChunk1Offset = colorChunk0Offset + sample0.length + chunkPadding.length;
        int alphaChunk0Offset = colorChunk1Offset + sample1.length + sample2.length;
        int alphaChunk1Offset = alphaChunk0Offset + sample0.length + chunkPadding.length;
        byte[] moov = minimalAvisMoovBoxWithReferencedAlphaTrack(
                new int[]{colorChunk0Offset, colorChunk1Offset},
                new int[]{alphaChunk0Offset, alphaChunk1Offset},
                includeMatchingAlpha,
                premultiplied,
                sample0.length,
                sample1.length,
                sample2.length
        );
        return concat(ftyp, moov, box("mdat", concat(colorMdatPayload, alphaMdatPayload)));
    }

    /// Creates a minimal AVIS sequence whose color track has an unresolved `prem` reference.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithPremReferenceToMissingAlphaTrack() {
        byte[] sample = av1StillPicturePayload();
        byte[] colorTrack = avisTrackBox(
                1,
                "pict",
                trackReferenceBox("prem", 3),
                null,
                new int[]{0, 0},
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sample.length,
                sample.length,
                sample.length
        );
        byte[] mismatchedAlphaTrack = avisTrackBox(
                2,
                "auxv",
                trackReferenceBox("auxl", 99),
                AUXILIARY_ALPHA_TYPE,
                new int[]{0, 0},
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sample.length,
                sample.length,
                sample.length
        );
        return concat(sequenceFileTypeBox(), box("moov", colorTrack, mismatchedAlphaTrack));
    }

    /// Creates a minimal AVIS sequence with a matched alpha track that advertises different dimensions.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithMismatchedAlphaDimensions() {
        byte[] sample = av1StillPicturePayload();
        byte[] colorTrack = avisTrackBox(
                1,
                "pict",
                null,
                null,
                new int[]{0, 0},
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sample.length,
                sample.length,
                sample.length
        );
        byte[] alphaTrack = avisTrackBoxWithTrackDimensions(
                3,
                "auxv",
                trackReferenceBox("auxl", 1),
                AUXILIARY_ALPHA_TYPE,
                new int[]{0, 0},
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                32,
                64,
                sample.length,
                sample.length,
                sample.length
        );
        return concat(sequenceFileTypeBox(), box("moov", colorTrack, alphaTrack));
    }

    /// Creates a minimal AVIS sequence with duplicate `mdia` handler boxes.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithDuplicateMediaHandler() {
        byte[] sample = av1StillPicturePayload();
        return concat(
                sequenceFileTypeBox(),
                minimalAvisMoovBoxWithDuplicateMediaHandler(
                        new int[]{0, 0},
                        sample.length,
                        sample.length,
                        sample.length
                )
        );
    }

    /// Creates a minimal AVIS sequence with a malformed `tref/auxl` reference box.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithMalformedTrackReference() {
        byte[] sample = av1StillPicturePayload();
        return concat(
                sequenceFileTypeBox(),
                box(
                        "moov",
                        avisTrackBox(
                                2,
                                "auxv",
                                box("tref", box("auxl", new byte[]{0, 0, 0})),
                                AUXILIARY_ALPHA_TYPE,
                                new int[]{0, 0},
                                false,
                                2,
                                1,
                                2,
                                1,
                                2,
                                null,
                                sample.length,
                                sample.length
                        )
                )
        );
    }

    /// Creates a minimal AVIS sequence with two supported color image tracks.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithMultipleColorTracks() {
        byte[] sample = av1StillPicturePayload();
        return concat(
                sequenceFileTypeBox(),
                box(
                        "moov",
                        avisTrackBox(
                                1,
                                "pict",
                                null,
                                null,
                                new int[]{0, 0},
                                false,
                                3,
                                1,
                                3,
                                1,
                                3,
                                null,
                                sample.length,
                                sample.length,
                                sample.length
                        ),
                        avisTrackBox(
                                2,
                                "pict",
                                null,
                                null,
                                new int[]{0, 0},
                                false,
                                3,
                                1,
                                3,
                                1,
                                3,
                                null,
                                sample.length,
                                sample.length,
                                sample.length
                        )
                )
        );
    }

    /// Creates a minimal AVIS sequence with an unsupported track-reference type.
    ///
    /// @return the complete AVIS test file bytes
    private static byte[] minimalAvisSequenceWithUnsupportedTrackReference() {
        byte[] sample = av1StillPicturePayload();
        return concat(
                sequenceFileTypeBox(),
                box(
                        "moov",
                        avisTrackBox(
                                1,
                                "pict",
                                trackReferenceBox("hint", 2),
                                null,
                                new int[]{0, 0},
                                false,
                                3,
                                1,
                                3,
                                1,
                                3,
                                null,
                                sample.length,
                                sample.length,
                                sample.length
                        )
                )
        );
    }

    /// Creates a minimal AVIS `moov` box with one leading ignored track.
    ///
    /// @param leadingHandlerType the ignored leading track handler type
    /// @param chunkOffsets the absolute chunk offsets for the image track
    /// @param sampleSizes the image-track sample sizes
    /// @return the `moov` box bytes
    private static byte[] minimalAvisMoovBoxWithLeadingIgnoredTrack(
            String leadingHandlerType,
            int[] chunkOffsets,
            int... sampleSizes
    ) {
        byte[] ignoredTrack = avisTrackBox(
                leadingHandlerType,
                new int[]{0, 0},
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sampleSizes
        );
        byte[] imageTrack = avisTrackBox(
                null,
                chunkOffsets,
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sampleSizes
        );
        return box("moov", ignoredTrack, imageTrack);
    }

    /// Creates a minimal AVIS `moov` box with mismatched and optionally matching alpha tracks.
    ///
    /// @param colorChunkOffsets the absolute color track chunk offsets
    /// @param alphaChunkOffsets the absolute alpha track chunk offsets
    /// @param includeMatchingAlpha whether to include a matching alpha track
    /// @param premultiplied whether to add a `prem` track reference from color to the matching alpha track
    /// @param sampleSizes the color and matching alpha sample sizes
    /// @return the `moov` box bytes
    private static byte[] minimalAvisMoovBoxWithReferencedAlphaTrack(
            int[] colorChunkOffsets,
            int[] alphaChunkOffsets,
            boolean includeMatchingAlpha,
            boolean premultiplied,
            int... sampleSizes
    ) {
        byte[] colorTrack = avisTrackBox(
                1,
                "pict",
                includeMatchingAlpha && premultiplied ? trackReferenceBox("prem", 3) : null,
                null,
                colorChunkOffsets,
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sampleSizes
        );
        byte[] mismatchedAlphaTrack = avisTrackBox(
                2,
                "auxv",
                trackReferenceBox("auxl", 99),
                AUXILIARY_ALPHA_TYPE,
                new int[]{0, 0},
                false,
                2,
                1,
                2,
                1,
                2,
                null,
                sampleSizes[0],
                sampleSizes[1]
        );
        if (!includeMatchingAlpha) {
            return box("moov", colorTrack, mismatchedAlphaTrack);
        }
        byte[] matchingAlphaTrack = avisTrackBox(
                3,
                "auxv",
                trackReferenceBox("auxl", 1),
                AUXILIARY_ALPHA_TYPE,
                alphaChunkOffsets,
                false,
                3,
                1,
                3,
                1,
                3,
                null,
                sampleSizes
        );
        return box("moov", colorTrack, mismatchedAlphaTrack, matchingAlphaTrack);
    }

    /// Creates a minimal AVIS `moov` box with duplicate media handler boxes.
    ///
    /// @param chunkOffsets the absolute chunk offsets
    /// @param sampleSizes the sample sizes
    /// @return the `moov` box bytes
    private static byte[] minimalAvisMoovBoxWithDuplicateMediaHandler(int[] chunkOffsets, int... sampleSizes) {
        return box(
                "moov",
                box(
                        "trak",
                        trackHeaderBox(3),
                        box(
                                "mdia",
                                mediaHeaderBox(3),
                                handlerBox("pict"),
                                handlerBox("pict"),
                                box("minf", sampleTableBox(chunkOffsets, false, 3, 1, 1, sampleSizes))
                        )
                )
        );
    }

    /// Creates a minimal AVIS `moov` box.
    ///
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param trackDuration the duration written to `tkhd`
    /// @param editList the optional `edts` box
    /// @param sampleSizes the sample sizes
    /// @return the `moov` box bytes
    private static byte[] minimalAvisMoovBox(
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int trackDuration,
            byte @Nullable [] editList,
            int... sampleSizes
    ) {
        return box(
                "moov",
                avisTrackBox(
                        null,
                        chunkOffsets,
                        useCo64,
                        timingSampleCount,
                        firstStscChunk,
                        mediaDuration,
                        sampleDelta,
                        trackDuration,
                        editList,
                        sampleSizes
                )
        );
    }

    /// Creates a minimal AVIS track box.
    ///
    /// @param handlerType the optional media handler type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param trackDuration the duration written to `tkhd`
    /// @param editList the optional `edts` box
    /// @param sampleSizes the sample sizes
    /// @return the `trak` box bytes
    private static byte[] avisTrackBox(
            @Nullable String handlerType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int trackDuration,
            byte @Nullable [] editList,
            int... sampleSizes
    ) {
        return avisTrackBox(
                1,
                handlerType,
                null,
                null,
                chunkOffsets,
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                trackDuration,
                editList,
                sampleSizes
        );
    }

    /// Creates a minimal AVIS track box.
    ///
    /// @param trackId the track ID written to `tkhd`
    /// @param handlerType the optional media handler type
    /// @param trackReference the optional `tref` box
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param trackDuration the duration written to `tkhd`
    /// @param editList the optional `edts` box
    /// @param sampleSizes the sample sizes
    /// @return the `trak` box bytes
    private static byte[] avisTrackBox(
            int trackId,
            @Nullable String handlerType,
            byte @Nullable [] trackReference,
            @Nullable String auxiliaryType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int trackDuration,
            byte @Nullable [] editList,
            int... sampleSizes
    ) {
        return box(
                "trak",
                trackHeaderBox(trackId, trackDuration),
                trackReference == null ? new byte[0] : trackReference,
                editList == null ? new byte[0] : editList,
                mediaBox(
                        handlerType,
                        auxiliaryType,
                        chunkOffsets,
                        useCo64,
                        timingSampleCount,
                        firstStscChunk,
                        mediaDuration,
                        sampleDelta,
                        sampleSizes
                )
        );
    }

    /// Creates a minimal AVIS track box with custom `tkhd` dimensions.
    ///
    /// @param trackId the track ID written to `tkhd`
    /// @param handlerType the optional media handler type
    /// @param trackReference the optional `tref` box
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param trackDuration the duration written to `tkhd`
    /// @param editList the optional `edts` box
    /// @param trackWidth the track width written to `tkhd`
    /// @param trackHeight the track height written to `tkhd`
    /// @param sampleSizes the sample sizes
    /// @return the `trak` box bytes
    private static byte[] avisTrackBoxWithTrackDimensions(
            int trackId,
            @Nullable String handlerType,
            byte @Nullable [] trackReference,
            @Nullable String auxiliaryType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int trackDuration,
            byte @Nullable [] editList,
            int trackWidth,
            int trackHeight,
            int... sampleSizes
    ) {
        return box(
                "trak",
                trackHeaderBox(trackId, trackDuration, trackWidth, trackHeight),
                trackReference == null ? new byte[0] : trackReference,
                editList == null ? new byte[0] : editList,
                mediaBox(
                        handlerType,
                        auxiliaryType,
                        chunkOffsets,
                        useCo64,
                        timingSampleCount,
                        firstStscChunk,
                        mediaDuration,
                        sampleDelta,
                        sampleSizes
                )
        );
    }

    /// Creates a minimal AVIS track header.
    ///
    /// @param trackDuration the duration written to `tkhd`
    /// @return the `tkhd` box bytes
    private static byte[] trackHeaderBox(int trackDuration) {
        return trackHeaderBox(1, trackDuration);
    }

    /// Creates a minimal AVIS track header.
    ///
    /// @param trackId the track ID written to `tkhd`
    /// @param trackDuration the duration written to `tkhd`
    /// @return the `tkhd` box bytes
    private static byte[] trackHeaderBox(int trackId, int trackDuration) {
        return trackHeaderBox(trackId, trackDuration, 64, 64);
    }

    /// Creates a minimal AVIS track header with custom dimensions.
    ///
    /// @param trackId the track ID written to `tkhd`
    /// @param trackDuration the duration written to `tkhd`
    /// @param width the track width
    /// @param height the track height
    /// @return the `tkhd` box bytes
    private static byte[] trackHeaderBox(int trackId, int trackDuration, int width, int height) {
        return fullBox(
                "tkhd",
                0,
                7,
                new byte[8],
                u32(trackId),
                u32(0),
                u32(trackDuration),
                new byte[52],
                u32(width << 16),
                u32(height << 16)
        );
    }

    /// Creates a minimal AVIS edit list.
    ///
    /// @param repeating whether the edit list signals repetition semantics
    /// @param segmentDuration the edit-list segment duration
    /// @return the `edts` box bytes
    private static byte[] editListBox(boolean repeating, int segmentDuration) {
        return box(
                "edts",
                fullBox(
                        "elst",
                        0,
                        repeating ? 1 : 0,
                        u32(1),
                        u32(segmentDuration),
                        u32(0),
                        u16(1),
                        u16(0)
                )
        );
    }

    /// Creates a minimal AVIS media box.
    ///
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param sampleSizes the sample sizes
    /// @return the `mdia` box bytes
    private static byte[] mediaBox(
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int... sampleSizes
    ) {
        return mediaBox(
                null,
                chunkOffsets,
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                sampleSizes
        );
    }

    /// Creates a minimal AVIS media box.
    ///
    /// @param handlerType the optional media handler type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param sampleSizes the sample sizes
    /// @return the `mdia` box bytes
    private static byte[] mediaBox(
            @Nullable String handlerType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int... sampleSizes
    ) {
        return mediaBox(
                handlerType,
                null,
                chunkOffsets,
                useCo64,
                timingSampleCount,
                firstStscChunk,
                mediaDuration,
                sampleDelta,
                sampleSizes
        );
    }

    /// Creates a minimal AVIS media box.
    ///
    /// @param handlerType the optional media handler type
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param mediaDuration the duration written to `mdhd`
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param sampleSizes the sample sizes
    /// @return the `mdia` box bytes
    private static byte[] mediaBox(
            @Nullable String handlerType,
            @Nullable String auxiliaryType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int mediaDuration,
            int sampleDelta,
            int... sampleSizes
    ) {
        return box(
                "mdia",
                mediaHeaderBox(mediaDuration),
                handlerType == null ? new byte[0] : handlerBox(handlerType),
                box("minf", sampleTableBox(
                        auxiliaryType,
                        chunkOffsets,
                        useCo64,
                        timingSampleCount,
                        firstStscChunk,
                        sampleDelta,
                        sampleSizes
                ))
        );
    }

    /// Creates a minimal AVIS media header.
    ///
    /// @param mediaDuration the duration written to `mdhd`
    /// @return the `mdhd` box bytes
    private static byte[] mediaHeaderBox(int mediaDuration) {
        return fullBox("mdhd", 0, 0, u32(0), u32(0), u32(30), u32(mediaDuration), u16(0), u16(0));
    }

    /// Creates a minimal AVIS sample table.
    ///
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param sampleSizes the sample sizes
    /// @return the `stbl` box bytes
    private static byte[] sampleTableBox(
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int sampleDelta,
            int... sampleSizes
    ) {
        return sampleTableBox(null, chunkOffsets, useCo64, timingSampleCount, firstStscChunk, sampleDelta, sampleSizes);
    }

    /// Creates a minimal AVIS sample table.
    ///
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @param timingSampleCount the sample count covered by `stts`
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @param sampleDelta the constant sample delta written to `stts`
    /// @param sampleSizes the sample sizes
    /// @return the `stbl` box bytes
    private static byte[] sampleTableBox(
            @Nullable String auxiliaryType,
            int[] chunkOffsets,
            boolean useCo64,
            int timingSampleCount,
            int firstStscChunk,
            int sampleDelta,
            int... sampleSizes
    ) {
        return box(
                "stbl",
                sampleDescriptionBox(auxiliaryType),
                timeToSampleBox(timingSampleCount, sampleDelta),
                sampleToChunkBox(firstStscChunk),
                chunkOffsetBox(chunkOffsets, useCo64),
                sampleSizeBox(sampleSizes)
        );
    }

    /// Creates a minimal AVIS sample-description box.
    ///
    /// @return the `stsd` box bytes
    private static byte[] sampleDescriptionBox() {
        return sampleDescriptionBox(null);
    }

    /// Creates a minimal AVIS sample-description box.
    ///
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @return the `stsd` box bytes
    private static byte[] sampleDescriptionBox(@Nullable String auxiliaryType) {
        return fullBox("stsd", 0, 0, u32(1), av01SampleEntry(auxiliaryType));
    }

    /// Creates a minimal `av01` sample entry.
    ///
    /// @return the `av01` sample entry bytes
    private static byte[] av01SampleEntry() {
        return av01SampleEntry(null);
    }

    /// Creates a minimal `av01` sample entry.
    ///
    /// @param auxiliaryType the optional `auxi` auxiliary type
    /// @return the `av01` sample entry bytes
    private static byte[] av01SampleEntry(@Nullable String auxiliaryType) {
        return box(
                "av01",
                new byte[24],
                u16(64),
                u16(64),
                new byte[50],
                av1ConfigProperty(),
                colorProperty(),
                auxiliaryType == null ? new byte[0] : auxiliarySampleEntryBox(auxiliaryType)
        );
    }

    /// Creates an `auxi` sample-entry box.
    ///
    /// @param auxiliaryType the auxiliary image type string
    /// @return the `auxi` sample-entry box bytes
    private static byte[] auxiliarySampleEntryBox(String auxiliaryType) {
        byte[] urns = (auxiliaryType + "\0").getBytes(StandardCharsets.ISO_8859_1);
        return fullBox("auxi", 0, 0, urns);
    }

    /// Creates a track-reference box.
    ///
    /// @param referenceType the four-character reference type
    /// @param trackIds the referenced track IDs
    /// @return the `tref` box bytes
    private static byte[] trackReferenceBox(String referenceType, int... trackIds) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int trackId : trackIds) {
            payload.writeBytes(u32(trackId));
        }
        return box("tref", box(referenceType, payload.toByteArray()));
    }

    /// Creates a constant frame-duration sample table.
    ///
    /// @param sampleCount the sample count
    /// @param sampleDelta the constant sample delta
    /// @return the `stts` box bytes
    private static byte[] timeToSampleBox(int sampleCount, int sampleDelta) {
        return fullBox("stts", 0, 0, u32(1), u32(sampleCount), u32(sampleDelta));
    }

    /// Creates a sample-to-chunk table with one sample in the first chunk and two in the second.
    ///
    /// @param firstStscChunk the first chunk signaled by the first `stsc` entry
    /// @return the `stsc` box bytes
    private static byte[] sampleToChunkBox(int firstStscChunk) {
        return fullBox(
                "stsc",
                0,
                0,
                u32(2),
                u32(firstStscChunk), u32(1), u32(1),
                u32(2), u32(2), u32(1)
        );
    }

    /// Creates a 32-bit or 64-bit chunk-offset table.
    ///
    /// @param chunkOffsets the absolute chunk offsets
    /// @param useCo64 whether to encode chunk offsets with `co64`
    /// @return the chunk-offset box bytes
    private static byte[] chunkOffsetBox(int[] chunkOffsets, boolean useCo64) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(u32(chunkOffsets.length));
        for (int chunkOffset : chunkOffsets) {
            payload.writeBytes(useCo64 ? u64(chunkOffset) : u32(chunkOffset));
        }
        return fullBox(useCo64 ? "co64" : "stco", 0, 0, payload.toByteArray());
    }

    /// Creates a sample-size table.
    ///
    /// @param sampleSizes the sample sizes
    /// @return the `stsz` box bytes
    private static byte[] sampleSizeBox(int... sampleSizes) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(u32(0));
        payload.writeBytes(u32(sampleSizes.length));
        for (int sampleSize : sampleSizes) {
            payload.writeBytes(u32(sampleSize));
        }
        return fullBox("stsz", 0, 0, payload.toByteArray());
    }

    /// Creates the AVIF file type box used by the minimal test image.
    ///
    /// @return the file type box bytes
    private static byte[] fileTypeBox() {
        return box(
                "ftyp",
                fourCc("avif"),
                u32(0),
                fourCc("avif"),
                fourCc("mif1"),
                fourCc("miaf")
        );
    }

    /// Creates an AVIS file type box used by minimal sequence tests.
    ///
    /// @return the AVIS file type box bytes
    private static byte[] sequenceFileTypeBox() {
        return box(
                "ftyp",
                fourCc("avis"),
                u32(0),
                fourCc("avis"),
                fourCc("avif"),
                fourCc("msf1")
        );
    }

    /// Creates the AVIF meta box used by the minimal test image.
    ///
    /// @param itemPayloadOffset the absolute file offset of the AV1 item payload
    /// @param itemPayloadLength the AV1 item payload length
    /// @return the meta box bytes
    private static byte[] metaBox(int itemPayloadOffset, int itemPayloadLength) {
        return fullBox(
                "meta",
                0,
                0,
                handlerBox(),
                primaryItemBox(),
                itemLocationBox(itemPayloadOffset, itemPayloadLength),
                itemInfoBox(),
                itemPropertiesBox()
        );
    }

    /// Creates the picture handler box.
    ///
    /// @return the handler box bytes
    private static byte[] handlerBox() {
        return handlerBox("pict");
    }

    /// Creates a handler box.
    ///
    /// @param handlerType the four-character handler type
    /// @return the handler box bytes
    private static byte[] handlerBox(String handlerType) {
        return fullBox(
                "hdlr",
                0,
                0,
                u32(0),
                fourCc(handlerType),
                u32(0),
                u32(0),
                u32(0),
                new byte[]{0}
        );
    }

    /// Creates the primary item box.
    ///
    /// @return the primary item box bytes
    private static byte[] primaryItemBox() {
        return fullBox("pitm", 0, 0, u16(1));
    }

    /// Creates the item location box for the primary AV1 item.
    ///
    /// @param itemPayloadOffset the absolute file offset of the AV1 item payload
    /// @param itemPayloadLength the AV1 item payload length
    /// @return the item location box bytes
    private static byte[] itemLocationBox(int itemPayloadOffset, int itemPayloadLength) {
        return fullBox(
                "iloc",
                0,
                0,
                new byte[]{0x44, 0x40},
                u16(1),
                u16(1),
                u16(0),
                u32(0),
                u16(1),
                u32(itemPayloadOffset),
                u32(itemPayloadLength)
        );
    }

    /// Creates the item information box for the primary AV1 item.
    ///
    /// @return the item information box bytes
    private static byte[] itemInfoBox() {
        return fullBox(
                "iinf",
                0,
                0,
                u16(1),
                fullBox("infe", 2, 0, u16(1), u16(0), fourCc("av01"), new byte[]{0})
        );
    }

    /// Creates the item property box tree for the primary AV1 item.
    ///
    /// @return the item property box tree bytes
    private static byte[] itemPropertiesBox() {
        return box(
                "iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty()),
                fullBox(
                        "ipma",
                        0,
                        0,
                        u32(1),
                        u16(1),
                        new byte[]{3, (byte) 0x81, (byte) 0x82, 0x03}
                )
        );
    }

    /// Creates an `ispe` property for a `64x64` image.
    ///
    /// @return the image spatial extents property bytes
    private static byte[] imageSpatialExtentsProperty() {
        return imageSpatialExtentsProperty(64, 64);
    }

    /// Creates an `ispe` property with custom dimensions.
    ///
    /// @param width the image width
    /// @param height the image height
    /// @return the image spatial extents property bytes
    private static byte[] imageSpatialExtentsProperty(int width, int height) {
        return fullBox("ispe", 0, 0, u32(width), u32(height));
    }

    /// Creates an `av1C` property for the reduced `8-bit I420` AV1 fixture.
    ///
    /// @return the AV1 codec configuration property bytes
    private static byte[] av1ConfigProperty() {
        return box("av1C", new byte[]{(byte) 0x81, 0x00, 0x0C, 0x00});
    }

    /// Creates an `nclx` color property for the minimal test image.
    ///
    /// @return the color property bytes
    private static byte[] colorProperty() {
        return box("colr", fourCc("nclx"), u16(1), u16(13), u16(6), new byte[]{(byte) 0x80});
    }

    /// Creates an `a1op` operating-point property.
    ///
    /// @param operatingPoint the operating point value to encode
    /// @return the operating-point property bytes
    private static byte[] operatingPointProperty(int operatingPoint) {
        return fullBox("a1op", 0, 0, new byte[]{(byte) operatingPoint});
    }

    /// Creates a reduced still-picture AV1 stream with one supported frame OBU.
    ///
    /// @return the AV1 OBU stream bytes
    private static byte[] av1StillPicturePayload() {
        return concat(
                obu(1, reducedStillPictureSequenceHeaderPayload()),
                obu(6, reducedStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD))
        );
    }

    /// Encodes a single self-delimited OBU.
    ///
    /// @param typeId the numeric OBU type identifier
    /// @param payload the OBU payload
    /// @return the encoded OBU bytes
    private static byte[] obu(int typeId, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((typeId << 3) | (1 << 1));
        writeLeb128(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    /// Creates a reduced `64x64 8-bit I420` still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPictureSequenceHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(63, 10);
        writer.writeBits(63, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writeReducedStillPictureColorConfig(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Writes the reduced `8-bit I420` color configuration bits.
    ///
    /// @param writer the destination bit writer
    private static void writeReducedStillPictureColorConfig(BitWriter writer) {
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
    }

    /// Creates a reduced still-picture combined frame payload with a caller-supplied tile group.
    ///
    /// @param tileGroupPayload the tile-group payload appended after the frame header
    /// @return the reduced still-picture combined frame payload
    private static byte[] reducedStillPictureCombinedFramePayload(byte[] tileGroupPayload) {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.padToByteBoundary();
        writer.writeBytes(tileGroupPayload);
        return writer.toByteArray();
    }

    /// Writes the reduced still-picture key-frame header syntax for a single-tile image.
    ///
    /// @param writer the destination bit writer
    private static void writeReducedStillPictureFrameHeaderBits(BitWriter writer) {
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(0, 8);
        for (int i = 0; i < 9; i++) {
            writer.writeFlag(false);
        }
    }

    /// Creates one BMFF box.
    ///
    /// @param type the four-character box type
    /// @param payloads the payload byte arrays to concatenate
    /// @return the complete box bytes
    private static byte[] box(String type, byte[]... payloads) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = 8;
        for (byte[] payload : payloads) {
            size += payload.length;
        }
        writeU32(output, size);
        output.writeBytes(fourCc(type));
        for (byte[] payload : payloads) {
            output.writeBytes(payload);
        }
        return output.toByteArray();
    }

    /// Creates one BMFF full box.
    ///
    /// @param type the four-character box type
    /// @param version the full-box version
    /// @param flags the full-box flags
    /// @param payloads the payload byte arrays to concatenate after the full-box header
    /// @return the complete full-box bytes
    private static byte[] fullBox(String type, int version, int flags, byte[]... payloads) {
        return box(
                type,
                new byte[]{
                        (byte) version,
                        (byte) (flags >>> 16),
                        (byte) (flags >>> 8),
                        (byte) flags
                },
                concat(payloads)
        );
    }

    /// Concatenates multiple byte arrays.
    ///
    /// @param arrays the byte arrays to concatenate
    /// @return the concatenated byte array
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    /// Encodes a four-character code.
    ///
    /// @param value the four-character code
    /// @return the encoded bytes
    private static byte[] fourCc(String value) {
        if (value.length() != 4) {
            throw new IllegalArgumentException("FourCC must contain exactly four characters: " + value);
        }
        return new byte[]{
                (byte) value.charAt(0),
                (byte) value.charAt(1),
                (byte) value.charAt(2),
                (byte) value.charAt(3)
        };
    }

    /// Encodes an unsigned 16-bit integer.
    ///
    /// @param value the unsigned value
    /// @return the encoded bytes
    private static byte[] u16(int value) {
        return new byte[]{
                (byte) (value >>> 8),
                (byte) value
        };
    }

    /// Encodes an unsigned 32-bit integer.
    ///
    /// @param value the unsigned value
    /// @return the encoded bytes
    private static byte[] u32(int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU32(output, value);
        return output.toByteArray();
    }

    /// Encodes an unsigned 64-bit integer.
    ///
    /// @param value the unsigned value
    /// @return the encoded bytes
    private static byte[] u64(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU64(output, value);
        return output.toByteArray();
    }

    /// Writes an unsigned 32-bit integer.
    ///
    /// @param output the destination byte stream
    /// @param value the unsigned value
    private static void writeU32(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    /// Writes an unsigned 64-bit integer.
    ///
    /// @param output the destination byte stream
    /// @param value the unsigned value
    private static void writeU64(ByteArrayOutputStream output, long value) {
        output.write((int) (value >>> 56));
        output.write((int) (value >>> 48));
        output.write((int) (value >>> 40));
        output.write((int) (value >>> 32));
        output.write((int) (value >>> 24));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 8));
        output.write((int) value);
    }

    /// Writes an unsigned LEB128 value.
    ///
    /// @param output the destination byte stream
    /// @param value the value to encode
    private static void writeLeb128(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while (true) {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                output.write(next | 0x80);
            } else {
                output.write(next);
                return;
            }
        }
    }

    /// Small MSB-first bit writer used to build AV1 test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Writes trailing bits and byte alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Pads the current byte with zero bits until the next byte boundary.
        private void padToByteBoundary() {
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Writes raw bytes after the current bitstream has been byte aligned.
        ///
        /// @param bytes the raw bytes to append
        private void writeBytes(byte[] bytes) {
            if (bitCount != 0) {
                throw new IllegalStateException("BitWriter is not byte aligned");
            }
            output.writeBytes(bytes);
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
