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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `AvifImageReader`.
@NotNullByDefault
final class AvifImageReaderTest {
    /// One fixed single-tile payload that decodes as opaque mid-gray in the current AV1 decoder.
    private static final byte @Unmodifiable [] SUPPORTED_SINGLE_TILE_PAYLOAD = new byte[]{
            (byte) 0x98, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /// The expected packed opaque gray pixel produced by the supported still-picture payload.
    private static final int OPAQUE_MID_GRAY = 0xFF808080;

    /// The smallest still-image fixture from libavif's test data.
    private static final Path LIBAVIF_WHITE_1X1_FIXTURE =
            Path.of("external", "libavif", "tests", "data", "white_1x1.avif");

    /// Verifies that metadata can be parsed from a minimal AVIF primary image item.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void openParsesInfoForMinimalStillImage() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifStillImage())) {
            AvifImageInfo info = reader.info();

            assertEquals(64, info.width());
            assertEquals(64, info.height());
            assertEquals(8, info.bitDepth());
            assertEquals(PixelFormat.I420, info.pixelFormat());
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());

            AvifColorInfo colorInfo = info.colorInfo();
            assertNotNull(colorInfo);
            assertEquals(1, colorInfo.colorPrimaries());
            assertEquals(13, colorInfo.transferCharacteristics());
            assertEquals(6, colorInfo.matrixCoefficients());
            assertTrue(colorInfo.fullRange());
        }
    }

    /// Verifies that the primary AV1 item payload is decoded through the migrated AV1 decoder.
    ///
    /// @throws IOException if the reader cannot decode the test stream
    @Test
    void readFrameDecodesMinimalStillImage() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(minimalAvifStillImage())) {
            AvifFrame frame = reader.readFrame();

            assertTrue(frame instanceof AvifIntFrame);
            AvifIntFrame intFrame = (AvifIntFrame) frame;
            assertEquals(64, intFrame.width());
            assertEquals(64, intFrame.height());
            assertEquals(8, intFrame.bitDepth());
            assertEquals(PixelFormat.I420, intFrame.pixelFormat());
            assertEquals(0, intFrame.frameIndex());

            int[] pixels = intFrame.pixels();
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
        try (AvifImageReader reader = AvifImageReader.open(LIBAVIF_WHITE_1X1_FIXTURE)) {
            AvifImageInfo info = reader.info();

            assertEquals(1, info.width());
            assertEquals(1, info.height());
            assertEquals(8, info.bitDepth());
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
        try (AvifImageReader reader = AvifImageReader.open(LIBAVIF_WHITE_1X1_FIXTURE)) {
            AvifFrame frame = reader.readFrame();

            assertTrue(frame instanceof AvifIntFrame);
            AvifIntFrame intFrame = (AvifIntFrame) frame;
            assertEquals(1, intFrame.width());
            assertEquals(1, intFrame.height());
            assertEquals(8, intFrame.bitDepth());
            assertEquals(PixelFormat.I444, intFrame.pixelFormat());

            int[] pixels = intFrame.pixels();
            assertEquals(1, pixels.length);
            assertEquals(0xFF, pixels[0] >>> 24);
            assertNull(reader.readFrame());
        }
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
