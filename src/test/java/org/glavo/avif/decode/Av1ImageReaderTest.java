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
package org.glavo.avif.decode;

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `Av1ImageReader`.
@NotNullByDefault
final class Av1ImageReaderTest {
    /// One fixed single-tile payload that stays inside the current first-pixel reconstruction subset.
    ///
    /// This payload decodes as one reduced still-picture key frame whose luma and chroma blocks are
    /// fully intra-predicted with zero residuals, so the current reader can return a stable opaque
    /// gray `ArgbIntFrame` without relying on runtime brute-force search.
    private static final byte[] SUPPORTED_SINGLE_TILE_PAYLOAD = new byte[]{
            (byte) 0x98, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /// The expected packed opaque gray pixel produced by the supported still-picture payload.
    private static final int OPAQUE_MID_GRAY = 0xFF808080;

    /// Verifies that an empty stream returns end-of-stream instead of failing.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void readFrameReturnsNullAtEndOfStream() throws IOException {
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that `close()` is idempotent.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void closeIsIdempotent() throws IOException {
        Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        );
        reader.close();
        reader.close();
    }

    /// Verifies that operations fail after the reader has been closed.
    ///
    /// @throws IOException if the reader cannot be closed
    @Test
    void readFrameFailsAfterClose() throws IOException {
        Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN))
        );
        reader.close();
        assertThrows(IOException.class, reader::readFrame);
    }

    /// Verifies that a stream containing only a parsed sequence header reaches end-of-stream.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void readFrameConsumesSequenceHeaderOnlyStream() throws IOException {
        byte[] stream = obu(1, reducedStillPicturePayload());
        assertAcrossBufferedInputs(stream, reader -> {
            assertNull(reader.readFrame());
            assertNull(reader.lastFrameSyntaxDecodeResult());
        });
    }

    /// Verifies that `readAllFrames()` returns an empty list when the stream only contains a sequence header.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void readAllFramesReturnsEmptyListForSequenceHeaderOnlyStream() throws IOException {
        byte[] stream = obu(1, reducedStillPicturePayload());
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            List<DecodedFrame> frames = reader.readAllFrames();
            assertTrue(frames.isEmpty());
            assertNull(reader.lastFrameSyntaxDecodeResult());
        }
    }

    /// Verifies that frame OBUs are rejected when no sequence header was seen first.
    @Test
    void readFrameRejectsFrameDataBeforeSequenceHeader() {
        byte[] stream = obu(3, new byte[]{0});
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.STATE_VIOLATION, exception.code());
    }

    /// Verifies that the legacy reduced still-picture combined fixture now reaches the current
    /// `filter_intra` reconstruction boundary.
    @Test
    void readFrameParsesCombinedFrameObuBeforeCurrentFilterIntraBoundary() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
        assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
        assertEquals("filter_intra reconstruction is not implemented yet", exception.getMessage());
    }

    /// Verifies that all buffered-input adapters report the same current `filter_intra` decode
    /// boundary for the legacy reduced still-picture fixture and still refresh reference-frame
    /// state first.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReportsSameCurrentBoundaryAcrossBufferedInputs() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );

        assertAcrossBufferedInputs(stream, reader -> {
            DecodeException exception = assertThrows(DecodeException.class, reader::readFrame);
            assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
            assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
            assertEquals("filter_intra reconstruction is not implemented yet", exception.getMessage());
            assertReferenceStateStoredForLastSyntaxResult(reader);
        });
    }

    /// Verifies that an incomplete standalone frame assembly is rejected at end-of-stream.
    @Test
    void readFrameRejectsEndOfStreamWithIncompleteFrameAssembly() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.INVALID_BITSTREAM, exception.code());
        assertEquals(DecodeStage.FRAME_ASSEMBLY, exception.stage());
    }

    /// Verifies that the legacy reduced still-picture standalone fixture now reaches the current
    /// `filter_intra` reconstruction boundary.
    @Test
    void readFrameParsesStandaloneTileGroupBeforeCurrentFilterIntraBoundary() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload()),
                obu(4, singleTileGroupPayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
        assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
        assertEquals("filter_intra reconstruction is not implemented yet", exception.getMessage());
    }

    /// Verifies that the public reader stores structural reference state before reporting the
    /// current `filter_intra` reconstruction boundary once frame syntax completed successfully.
    @Test
    void readFrameStoresReferenceStateBeforeCurrentFilterIntraBoundary() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            DecodeException exception = assertThrows(DecodeException.class, reader::readFrame);
            assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
            assertEquals("filter_intra reconstruction is not implemented yet", exception.getMessage());
            assertReferenceStateStoredForLastSyntaxResult(reader);
        }
    }

    /// Verifies that a new sequence header clears previously stored structural reference-frame
    /// state even when the earlier frame already reached the current decode boundary.
    @Test
    void readFrameClearsReferenceStateOnSequenceResetAfterCurrentDecodeBoundary() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload()),
                obu(1, reducedStillPicturePayload())
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertThrows(DecodeException.class, reader::readFrame);
            assertReferenceStateStoredForLastSyntaxResult(reader);

            assertNull(reader.readFrame());
            assertNull(reader.lastFrameSyntaxDecodeResult());
            for (int i = 0; i < 8; i++) {
                assertNull(reader.referenceFrameSyntaxResult(i));
            }
        }
    }

    /// Verifies that a supported reduced still-picture combined stream returns one opaque `ArgbIntFrame`.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForSupportedCombinedStillPictureStream() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD))
        );

        assertAcrossBufferedInputs(stream, reader -> {
            assertOpaqueGrayStillPictureFrame(reader.readFrame(), 0);
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that the same supported tile payload also succeeds through the standalone
    /// frame-header plus tile-group assembly path.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForSupportedStandaloneStillPictureStream() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload()),
                obu(4, SUPPORTED_SINGLE_TILE_PAYLOAD)
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueGrayStillPictureFrame(reader.readFrame(), 0);
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that tile-group OBUs are rejected when no standalone or combined frame header is active.
    @Test
    void readFrameRejectsTileGroupBeforeFrameHeader() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(4, singleTileGroupPayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.STATE_VIOLATION, exception.code());
        assertEquals(DecodeStage.FRAME_ASSEMBLY, exception.stage());
    }

    /// Verifies that a new sequence header is rejected while a standalone frame assembly is still waiting for tile groups.
    @Test
    void readFrameRejectsSequenceHeaderWhileFrameAssemblyIsPending() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload()),
                obu(1, reducedStillPicturePayload())
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.STATE_VIOLATION, exception.code());
        assertEquals(DecodeStage.FRAME_ASSEMBLY, exception.stage());
        assertEquals("Sequence header OBU appeared before the current frame was completed", exception.getMessage());
    }

    /// Verifies that frame-size limits are enforced before structural frame decode begins.
    @Test
    void readFrameRejectsFramesLargerThanConfiguredLimit() {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload())
        );
        Av1DecoderConfig config = Av1DecoderConfig.builder().frameSizeLimit(4095).build();
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)),
                    config
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.FRAME_SIZE_LIMIT_EXCEEDED, exception.code());
        assertEquals(DecodeStage.FRAME_HEADER_PARSE, exception.stage());
        assertEquals("Frame size exceeds the configured limit: 64x64", exception.getMessage());
    }

    /// Verifies that `show_existing_frame` cannot reference an empty reference slot.
    @Test
    void readFrameRejectsShowExistingFrameWithUnpopulatedSlot() {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload()),
                obu(3, showExistingFrameHeaderPayload(0))
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.STATE_VIOLATION, exception.code());
        assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
        assertEquals("show_existing_frame references a frame slot that has not been populated", exception.getMessage());
    }

    /// Verifies that combined `FRAME` OBUs reject trailing tile data when `show_existing_frame` is set.
    @Test
    void readFrameRejectsCombinedShowExistingFrameWithTrailingTileData() {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload()),
                obu(6, concat(showExistingFrameHeaderPayload(0), new byte[]{0x00}))
        );
        DecodeException exception = assertThrows(DecodeException.class, () -> {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                reader.readFrame();
            }
        });
        assertEquals(DecodeErrorCode.INVALID_BITSTREAM, exception.code());
        assertEquals(DecodeStage.FRAME_ASSEMBLY, exception.stage());
        assertEquals("Combined frame OBU must not carry tile data when show_existing_frame is set", exception.getMessage());
    }

    /// Verifies that the reader exposes the supplied immutable configuration.
    @Test
    void configReturnsSuppliedConfiguration() {
        Av1DecoderConfig config = Av1DecoderConfig.builder().threadCount(2).build();
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)),
                config
        )) {
            assertSame(config, reader.config());
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    /// Runs the supplied assertion across all currently supported buffered-input adapters.
    ///
    /// @param stream the raw test stream
    /// @param assertion the reader assertion to run for every adapter
    /// @throws IOException if one adapter cannot be opened or consumed
    private static void assertAcrossBufferedInputs(byte[] stream, ReaderAssertion assertion) throws IOException {
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertion.accept(reader);
        }
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfInputStream(new ByteArrayInputStream(stream))
        )) {
            assertion.accept(reader);
        }
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteChannel(Channels.newChannel(new ByteArrayInputStream(stream)))
        )) {
            assertion.accept(reader);
        }
    }

    /// Asserts that the reader returned one supported opaque gray still-picture frame.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertOpaqueGrayStillPictureFrame(@org.jetbrains.annotations.Nullable DecodedFrame decodedFrame, long expectedPresentationIndex) {
        assertNotNull(decodedFrame);
        assertTrue(decodedFrame instanceof ArgbIntFrame);
        ArgbIntFrame frame = (ArgbIntFrame) decodedFrame;
        assertDecodedStillPictureFrameMetadata(frame, expectedPresentationIndex);

        int[] pixels = frame.pixels();
        assertEquals(64 * 64, pixels.length);
        for (int pixel : pixels) {
            assertEquals(OPAQUE_MID_GRAY, pixel);
        }
    }

    /// Asserts the stable decoded-frame metadata shared by the current reduced still-picture fixtures.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertDecodedStillPictureFrameMetadata(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            long expectedPresentationIndex
    ) {
        assertNotNull(decodedFrame);
        assertEquals(64, decodedFrame.width());
        assertEquals(64, decodedFrame.height());
        assertEquals(8, decodedFrame.bitDepth());
        assertEquals(PixelFormat.I420, decodedFrame.pixelFormat());
        assertEquals(FrameType.KEY, decodedFrame.frameType());
        assertTrue(decodedFrame.visible());
        assertEquals(expectedPresentationIndex, decodedFrame.presentationIndex());
    }

    /// Asserts that every refreshed reference slot stores structural state for the same decoded frame.
    ///
    /// Reference snapshots are allowed to differ in stored CDF state from `lastFrameSyntaxDecodeResult()`,
    /// so this helper checks the shared frame assembly and tile count instead of object identity.
    ///
    /// @param reader the reader whose stored structural reference state should be validated
    private static void assertReferenceStateStoredForLastSyntaxResult(Av1ImageReader reader) {
        FrameSyntaxDecodeResult syntaxResult = reader.lastFrameSyntaxDecodeResult();
        assertNotNull(syntaxResult);
        for (int i = 0; i < 8; i++) {
            FrameSyntaxDecodeResult storedResult = reader.referenceFrameSyntaxResult(i);
            assertNotNull(storedResult);
            assertSame(syntaxResult.assembly(), storedResult.assembly());
            assertEquals(syntaxResult.tileCount(), storedResult.tileCount());
        }
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

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload() {
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
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates one full sequence header payload that enables `show_existing_frame`.
    ///
    /// @return one full sequence header payload that enables `show_existing_frame`
    private static byte[] fullSequenceHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(2, 3);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1000, 32);
        writer.writeBits(60_000, 32);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(4, 5);
        writer.writeBits(100, 32);
        writer.writeBits(3, 5);
        writer.writeBits(2, 5);
        writer.writeFlag(true);
        writer.writeBits(1, 5);

        writer.writeBits(0x101, 12);
        writer.writeBits(3, 3);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(17, 5);
        writer.writeBits(3, 5);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(2, 4);

        writer.writeBits(0, 12);
        writer.writeBits(1, 3);
        writer.writeBits(0, 2);
        writer.writeFlag(false);
        writer.writeFlag(false);

        writer.writeBits(11, 4);
        writer.writeBits(10, 4);
        writer.writeBits(2047, 12);
        writer.writeBits(1023, 11);
        writer.writeFlag(true);
        writer.writeBits(2, 4);
        writer.writeBits(1, 3);

        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(4, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);

        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 8);
        writer.writeBits(13, 8);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture key frame header payload.
    ///
    /// @return the reduced still-picture key frame header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a minimal standalone `show_existing_frame` header payload.
    ///
    /// @param existingFrameIndex the referenced frame slot
    /// @return a minimal standalone `show_existing_frame` header payload
    private static byte[] showExistingFrameHeaderPayload(int existingFrameIndex) {
        BitWriter writer = new BitWriter();
        writer.writeFlag(true);
        writer.writeBits(existingFrameIndex, 3);
        writer.writeBits(0, 3);
        writer.writeBits(0, 6);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture combined frame payload with a single tile group.
    ///
    /// @return the reduced still-picture combined frame payload
    private static byte[] reducedStillPictureCombinedFramePayload() {
        return reducedStillPictureCombinedFramePayload(singleTileGroupPayload());
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

    /// Creates a minimal single-tile tile-group payload.
    ///
    /// @return a minimal single-tile tile-group payload
    private static byte[] singleTileGroupPayload() {
        return new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
    }

    /// Writes the reduced still-picture key frame header syntax without standalone trailing bits.
    ///
    /// @param writer the destination bit writer
    private static void writeReducedStillPictureFrameHeaderBits(BitWriter writer) {
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
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

    /// Reader assertion used by buffered-input source-equivalence tests.
    @FunctionalInterface
    @NotNullByDefault
    private interface ReaderAssertion {
        /// Runs the assertion against one freshly opened reader.
        ///
        /// @param reader the freshly opened reader
        /// @throws IOException if the reader cannot be consumed
        void accept(Av1ImageReader reader) throws IOException;
    }
}
