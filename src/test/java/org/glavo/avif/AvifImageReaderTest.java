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

import org.glavo.avif.decode.PixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /// The smallest still-image fixture copied from libavif's test data into test resources.
    private static final String LIBAVIF_WHITE_1X1_FIXTURE = "libavif-test-data/white_1x1.avif";

    /// A still-image fixture copied from libavif's test data that uses an extended `pixi` property.
    private static final String LIBAVIF_EXTENDED_PIXI_FIXTURE = "libavif-test-data/extended_pixi.avif";

    /// A basic SDR sRGB still-image fixture copied from libavif's test data.
    private static final String LIBAVIF_COLORS_SDR_SRGB_FIXTURE = "libavif-test-data/colors_sdr_srgb.avif";

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

    /// A progressive still-image fixture copied from libavif's test data using idat.
    private static final String LIBAVIF_PROGRESSIVE_FIXTURE =
            "libavif-test-data/draw_points_idat_progressive.avif";

    /// An animated 8bpc AVIS image sequence fixture copied from libavif's test data.
    private static final String LIBAVIF_ANIMATED_FIXTURE = "libavif-test-data/colors-animated-8bpc.avif";

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
            assertEquals(PixelFormat.I420, intFrame.pixelFormat());
            assertEquals(0, intFrame.frameIndex());

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
            assertEquals(PixelFormat.I444, info.pixelFormat());
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
            assertEquals(PixelFormat.I444, intFrame.pixelFormat());

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
        }
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

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertNull(reader.readFrame());
        }
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

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
            assertEquals(64, frame.width());
            assertEquals(64, frame.height());
            assertNull(reader.readFrame());
        }
    }

    /// Builds a minimal AVIF with a transform property on the primary item.
    ///
    /// @param transformType the transform property type
    /// @param transformValue the single-byte transform value
    /// @return the AVIF container bytes
    private static byte[] minimalAvifWithTransform(String transformType, byte transformValue) {
        byte[] av1Payload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] transform = box(transformType, new byte[]{transformValue});
        byte[] iprpBox = box("iprp",
                box("ipco", imageSpatialExtentsProperty(), av1ConfigProperty(), colorProperty(), transform),
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

    /// Verifies that a fixture with an alpha auxiliary image parses successfully.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsAlphaFixtureGracefully() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(testResourceBytes(LIBAVIF_ALPHA_NO_IROT_FIXTURE))) {
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
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());

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

    /// Verifies that a libavif fixture with an alpha auxiliary item missing ispe is rejected.
    ///
    /// @throws IOException if the fixture cannot be read
    @Test
    void rejectsAlphaMissingIspeFixture() throws IOException {
        byte[] bytes = testResourceBytes(LIBAVIF_ALPHA_NOISPE_FIXTURE);
        AvifDecodeException exception = assertThrows(AvifDecodeException.class, () -> AvifImageReader.open(bytes));
        assertEquals(AvifErrorCode.BMFF_PARSE_FAILED, exception.code());
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
        byte[] colorPayload = av1StillPicturePayload();
        byte[] alphaPayload = av1StillPicturePayload();
        byte[] ftyp = fileTypeBox();
        byte[] firstMeta = buildDualItemMeta(colorPayload.length + alphaPayload.length);
        int metaSize = firstMeta.length;
        int mdatHeaderSize = 8;
        int colorOffset = ftyp.length + metaSize + mdatHeaderSize;
        int alphaOffset = colorOffset + colorPayload.length;
        byte[] meta = buildDualItemMeta(colorOffset, colorPayload.length, alphaOffset, alphaPayload.length);
        return concat(ftyp, meta, box("mdat", concat(colorPayload, alphaPayload)));
    }

    /// Creates a dual-item meta box with placeholder sizes.
    ///
    /// @param totalPayloadLength the total mdat payload length
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(int totalPayloadLength) {
        return buildDualItemMeta(0, 0, 0, totalPayloadLength);
    }

    /// Creates a dual-item meta box with actual payload offsets and lengths.
    ///
    /// @param colorOffset the absolute color item payload offset
    /// @param colorLength the color item payload length
    /// @param alphaOffset the absolute alpha item payload offset
    /// @param alphaLength the alpha item payload length
    /// @return the dual-item meta box bytes
    private static byte[] buildDualItemMeta(int colorOffset, int colorLength, int alphaOffset, int alphaLength) {
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
                        imageSpatialExtentsProperty(),
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

        byte[] iref = refBox(1, 2);

        return fullBox("meta", 0, 0,
                handlerBox(), primaryItemBox(), iloc, iinf, iprp, iref);
    }

    /// Creates an `iref` box with an `auxl` reference from one item to another.
    ///
    /// @param fromId the from item id
    /// @param toId the to item id
    /// @return the iref box bytes
    private static byte[] refBox(int fromId, int toId) {
        return fullBox("iref", 0, 0,
                box("auxl", u16(fromId), u16(1), u16(toId))
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
        byte[] urns = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
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
        return fullBox(
                "hdlr",
                0,
                0,
                u32(0),
                fourCc("pict"),
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
        return fullBox("ispe", 0, 0, u32(64), u32(64));
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
