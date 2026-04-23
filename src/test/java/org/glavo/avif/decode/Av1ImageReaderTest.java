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

import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// The stable top-left `8x8` ARGB block produced by the current legacy directional
    /// still-picture payload.
    private static final int[][] LEGACY_DIRECTIONAL_ARGB_TOP_LEFT_8X8 = {
            {0xFF7F7F7F, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF7E7E7E, 0xFF7E7E7E, 0xFF7E7E7E, 0xFF7E7E7E, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF808080, 0xFF808080, 0xFF7F7F7F, 0xFF7E7E7E, 0xFF818181, 0xFF818181, 0xFF818181, 0xFF818181},
            {0xFF828282, 0xFF848484, 0xFF868686, 0xFF868686, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF868686, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF848484, 0xFF808080, 0xFF818181, 0xFF818181, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF848484, 0xFF828282, 0xFF7F7F7F, 0xFF818181, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080},
            {0xFF848484, 0xFF848484, 0xFF808080, 0xFF838383, 0xFF808080, 0xFF808080, 0xFF808080, 0xFF818181}
    };

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

    /// Verifies that the legacy reduced still-picture combined fixture now reconstructs
    /// successfully through the directional-intra path.
    @Test
    void readFrameReturnsArgbIntFrameForLegacyDirectionalCombinedStillPictureStream() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that all buffered-input adapters reconstruct the same legacy directional
    /// still-picture fixture and still refresh reference-frame state first.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReconstructsLegacyDirectionalStillPictureAcrossBufferedInputs() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );

        assertAcrossBufferedInputs(stream, reader -> {
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
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

    /// Verifies that the legacy reduced still-picture standalone fixture now reconstructs
    /// successfully through the directional-intra path.
    @Test
    void readFrameReturnsArgbIntFrameForLegacyDirectionalStandaloneStillPictureStream() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(3, reducedStillPictureFrameHeaderPayload()),
                obu(4, singleTileGroupPayload())
        );
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that the public reader stores structural reference state before returning the
    /// decoded legacy directional still-picture frame.
    @Test
    void readFrameStoresReferenceStateForLegacyDirectionalStillPictureDecode() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload())
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
        }
    }

    /// Verifies that a new sequence header clears previously stored structural reference-frame
    /// state even when the earlier frame already reconstructed successfully.
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
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
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

    /// Verifies that `readAllFrames()` preserves the current supported first-pixel combined
    /// still-picture success path across all buffered-input adapters.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readAllFramesPreservesSupportedCombinedStillPictureSuccessPath() throws IOException {
        byte[] stream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD))
        );

        assertAcrossBufferedInputs(stream, reader -> {
            List<DecodedFrame> frames = reader.readAllFrames();
            assertEquals(1, frames.size());
            assertOpaqueGrayStillPictureFrame(frames.get(0), 0);
            assertReferenceStateStoredForLastSyntaxResult(reader);
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

    /// Verifies that one standalone `show_existing_frame` header reuses the currently supported
    /// opaque gray still-picture fixture through the public reader.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsStoredReferenceSurfaceForShowExistingFrame() throws IOException {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload()),
                obu(6, fullStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD)),
                obu(3, showExistingFrameHeaderPayload(0))
        );

        assertAcrossBufferedInputs(stream, reader -> {
            assertOpaqueGrayStillPictureFrame(reader.readFrame(), 0);
            FrameSyntaxDecodeResult firstSyntaxResult = reader.lastFrameSyntaxDecodeResult();
            assertNotNull(firstSyntaxResult);
            assertReferenceStateStoredForLastSyntaxResult(reader);

            assertOpaqueGrayStillPictureFrame(reader.readFrame(), 1);
            assertSame(firstSyntaxResult, reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that one combined `FRAME` `show_existing_frame` also reuses the currently
    /// supported legacy directional still-picture fixture through the public reader.
    ///
    /// @throws IOException if the reader cannot consume the test stream
    @Test
    void readFrameReturnsStoredReferenceSurfaceForCombinedShowExistingFrame() throws IOException {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload()),
                obu(3, fullStillPictureFrameHeaderPayload()),
                obu(4, singleTileGroupPayload()),
                obu(6, showExistingFrameHeaderPayload(0))
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 0);
            FrameSyntaxDecodeResult firstSyntaxResult = reader.lastFrameSyntaxDecodeResult();
            assertNotNull(firstSyntaxResult);
            assertLegacyDirectionalLeafDecoded(firstSyntaxResult);
            assertReferenceStateStoredForLastSyntaxResult(reader);

            assertOpaqueDirectionalStillPictureFrame(reader.readFrame(), 1);
            assertSame(firstSyntaxResult, reader.lastFrameSyntaxDecodeResult());
            assertLegacyDirectionalLeafDecoded(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that `readAllFrames()` includes the reused `show_existing_frame` output across all
    /// buffered-input adapters.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readAllFramesIncludesShowExistingFrameOutputAcrossBufferedInputs() throws IOException {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload()),
                obu(6, fullStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD)),
                obu(6, showExistingFrameHeaderPayload(0))
        );

        assertAcrossBufferedInputs(stream, reader -> {
            List<DecodedFrame> frames = reader.readAllFrames();
            assertEquals(2, frames.size());
            assertOpaqueGrayStillPictureFrame(frames.get(0), 0);
            assertOpaqueGrayStillPictureFrame(frames.get(1), 1);
            assertNotNull(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
        });
    }

    /// Verifies that one standalone `show_existing_frame` header can still expose the current
    /// supported opaque gray still-picture surface when the stored syntax state is widened to a
    /// synthetic two-tile layout.
    ///
    /// The decoded surface itself still comes from the real supported single-tile fixture, so this
    /// covers the public-reader success path under multi-tile stored state without mutating main
    /// code.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsArgbIntFrameForShowExistingFrameBackedBySyntheticMultiTileReferenceSurface() throws Exception {
        InjectedReferenceState referenceState = createSyntheticMultiTileReferenceStateFromSupportedStillPicture();
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject synthetic multi-tile reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertOpaqueGrayStillPictureFrame(decodedFrame, 0);
            ArgbIntFrame frame = (ArgbIntFrame) decodedFrame;
            int secondTileFirstPixelIndex = frame.width() / referenceState.frameHeader().tiling().columns();
            assertEquals(OPAQUE_MID_GRAY, frame.pixels()[secondTileFirstPixelIndex]);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertEquals(2, reader.lastFrameSyntaxDecodeResult().tileCount());
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that `show_existing_frame` fails with one stable not-implemented boundary when the
    /// referenced slot has syntax state but no reconstructed reference surface yet.
    @Test
    void readFrameRejectsShowExistingFrameWithoutReconstructedReferenceSurface() throws Exception {
        InjectedReferenceState referenceState = captureReferenceStateFromSupportedStillPicture();
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            injectShowExistingReferenceState(
                    reader,
                    new InjectedReferenceState(
                            referenceState.sequenceHeader(),
                            referenceState.frameHeader(),
                            referenceState.syntaxResult(),
                            null
                    )
            );

            DecodeException exception = assertThrows(DecodeException.class, reader::readFrame);
            assertEquals(DecodeErrorCode.NOT_IMPLEMENTED, exception.code());
            assertEquals(DecodeStage.FRAME_DECODE, exception.stage());
            assertEquals(
                    "show_existing_frame output currently requires a reconstructed reference surface",
                    exception.getMessage()
            );
        }
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

    /// Captures one populated supported-still-picture reference slot for later `show_existing_frame`
    /// reuse tests.
    ///
    /// @return one populated supported-still-picture reference slot for later reuse tests
    private static InjectedReferenceState captureReferenceStateFromSupportedStillPicture() throws Exception {
        byte[] sourceStream = concat(
                obu(1, reducedStillPicturePayload()),
                obu(6, reducedStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD))
        );

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(sourceStream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            assertOpaqueGrayStillPictureFrame(reader.readFrame(), 0);
            FrameHeader[] frameHeaders = getPrivateField(reader, "referenceFrameHeaders", FrameHeader[].class);
            FrameSyntaxDecodeResult[] syntaxResults =
                    getPrivateField(reader, "referenceFrameSyntaxResults", FrameSyntaxDecodeResult[].class);
            ReferenceSurfaceSnapshot[] surfaceSnapshots =
                    getPrivateField(reader, "referenceSurfaceSnapshots", ReferenceSurfaceSnapshot[].class);

            return new InjectedReferenceState(
                    parseFullSequenceHeader(),
                    frameHeaders[0],
                    syntaxResults[0],
                    surfaceSnapshots[0]
            );
        }
    }

    /// Creates one synthetic two-tile stored reference slot that reuses the real current supported
    /// opaque gray still-picture surface.
    ///
    /// The widened syntax result keeps the decoded surface unchanged while exercising public-reader
    /// output against `tileCount == 2`.
    ///
    /// @return one synthetic two-tile stored reference slot
    /// @throws Exception if the real supported fixture cannot be captured
    private static InjectedReferenceState createSyntheticMultiTileReferenceStateFromSupportedStillPicture() throws Exception {
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        ReferenceSurfaceSnapshot baseSurfaceSnapshot =
                Objects.requireNonNull(baseReferenceState.referenceSurfaceSnapshot(), "base reference surface");
        FrameHeader multiTileFrameHeader = copyFrameHeaderWithSyntheticTwoColumnTiling(baseReferenceState.frameHeader());
        FrameSyntaxDecodeResult multiTileSyntaxResult =
                createSyntheticMultiTileSyntaxResult(baseReferenceState.sequenceHeader(), multiTileFrameHeader);
        ReferenceSurfaceSnapshot multiTileSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                multiTileFrameHeader,
                multiTileSyntaxResult,
                baseSurfaceSnapshot.decodedPlanes()
        );

        return new InjectedReferenceState(
                baseReferenceState.sequenceHeader(),
                multiTileFrameHeader,
                multiTileSyntaxResult,
                multiTileSurfaceSnapshot
        );
    }

    /// Creates one synthetic two-tile syntax result that reuses the supplied frame metadata.
    ///
    /// @param sequenceHeader the sequence header associated with the stored frame
    /// @param frameHeader the frame header whose tile layout should be exposed
    /// @return one synthetic structural decode result whose tile count matches the widened layout
    private static FrameSyntaxDecodeResult createSyntheticMultiTileSyntaxResult(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) {
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        int tileCount = assembly.totalTiles();
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] temporalMotionFields = new TileDecodeContext.TemporalMotionField[tileCount];
        for (int i = 0; i < tileCount; i++) {
            tileRoots[i] = new TilePartitionTreeReader.Node[0];
            temporalMotionFields[i] = new TileDecodeContext.TemporalMotionField(1, 1);
        }
        return new FrameSyntaxDecodeResult(assembly, tileRoots, temporalMotionFields);
    }

    /// Copies one real supported still-picture frame header while widening its stored tile layout
    /// to two equal columns for public-reader reuse tests.
    ///
    /// @param baseFrameHeader the real supported still-picture frame header to copy
    /// @return one copied frame header whose stored tile layout exposes two columns
    private static FrameHeader copyFrameHeaderWithSyntheticTwoColumnTiling(FrameHeader baseFrameHeader) {
        return new FrameHeader(
                baseFrameHeader.temporalId(),
                baseFrameHeader.spatialId(),
                baseFrameHeader.showExistingFrame(),
                baseFrameHeader.existingFrameIndex(),
                baseFrameHeader.frameId(),
                baseFrameHeader.framePresentationDelay(),
                baseFrameHeader.frameType(),
                baseFrameHeader.showFrame(),
                baseFrameHeader.showableFrame(),
                baseFrameHeader.errorResilientMode(),
                baseFrameHeader.disableCdfUpdate(),
                baseFrameHeader.allowScreenContentTools(),
                baseFrameHeader.forceIntegerMotionVectors(),
                baseFrameHeader.frameSizeOverride(),
                baseFrameHeader.primaryRefFrame(),
                baseFrameHeader.frameOffset(),
                baseFrameHeader.refreshFrameFlags(),
                baseFrameHeader.frameSize(),
                baseFrameHeader.superResolution(),
                baseFrameHeader.allowIntrabc(),
                baseFrameHeader.refreshContext(),
                new FrameHeader.TilingInfo(
                        true,
                        1,
                        1,
                        1,
                        1,
                        2,
                        0,
                        0,
                        0,
                        1,
                        new int[]{0, 1, 2},
                        new int[]{0, 1},
                        0
                ),
                baseFrameHeader.quantization(),
                baseFrameHeader.segmentation(),
                baseFrameHeader.delta(),
                baseFrameHeader.allLossless(),
                baseFrameHeader.loopFilter(),
                baseFrameHeader.cdef(),
                baseFrameHeader.restoration(),
                baseFrameHeader.transformMode(),
                baseFrameHeader.reducedTransformSet(),
                baseFrameHeader.filmGrain().applyGrain()
        );
    }

    /// Injects one synthetic `show_existing_frame` state into a fresh reader.
    ///
    /// @param reader the fresh reader that should expose the injected state
    /// @param referenceState the reference state to inject into slot `0`
    /// @throws Exception if reflection-based field access fails
    private static void injectShowExistingReferenceState(
            Av1ImageReader reader,
            InjectedReferenceState referenceState
    ) throws Exception {
        setPrivateField(reader, "sequenceHeader", referenceState.sequenceHeader());
        FrameHeader[] frameHeaders = getPrivateField(reader, "referenceFrameHeaders", FrameHeader[].class);
        FrameSyntaxDecodeResult[] syntaxResults =
                getPrivateField(reader, "referenceFrameSyntaxResults", FrameSyntaxDecodeResult[].class);
        ReferenceSurfaceSnapshot[] surfaceSnapshots =
                getPrivateField(reader, "referenceSurfaceSnapshots", ReferenceSurfaceSnapshot[].class);
        frameHeaders[0] = referenceState.frameHeader();
        syntaxResults[0] = referenceState.syntaxResult();
        surfaceSnapshots[0] = referenceState.referenceSurfaceSnapshot();
    }

    /// Parses the test-only full sequence header that enables `show_existing_frame`.
    ///
    /// @return the parsed test-only full sequence header that enables `show_existing_frame`
    private static SequenceHeader parseFullSequenceHeader() throws IOException {
        return new SequenceHeaderParser().parse(
                new ObuPacket(
                        new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0),
                        fullSequenceHeaderPayload(),
                        0,
                        0
                ),
                false
        );
    }

    /// Reads one private field from the supplied object and casts it to the requested type.
    ///
    /// @param target the object whose field should be read
    /// @param fieldName the private field name
    /// @param fieldType the expected field type
    /// @param <T> the expected field type
    /// @return the private field value cast to the requested type
    /// @throws Exception if reflection-based field access fails
    private static <T> T getPrivateField(Object target, String fieldName, Class<T> fieldType) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(target));
    }

    /// Writes one private field on the supplied object.
    ///
    /// @param target the object whose field should be written
    /// @param fieldName the private field name
    /// @param value the value to assign
    /// @throws Exception if reflection-based field access fails
    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /// Asserts that the reader returned one supported opaque gray still-picture frame.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertOpaqueGrayStillPictureFrame(@org.jetbrains.annotations.Nullable DecodedFrame decodedFrame, long expectedPresentationIndex) {
        assertStillPictureFrameFilledWith(decodedFrame, expectedPresentationIndex, OPAQUE_MID_GRAY);
    }

    /// Asserts that the reader returned one legacy directional opaque gray still-picture frame.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertOpaqueDirectionalStillPictureFrame(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            long expectedPresentationIndex
    ) {
        assertNotNull(decodedFrame);
        assertTrue(decodedFrame instanceof ArgbIntFrame);
        ArgbIntFrame frame = (ArgbIntFrame) decodedFrame;
        assertDecodedStillPictureFrameMetadata(frame, expectedPresentationIndex);
        assertArgbBlockEquals(frame, 0, 0, LEGACY_DIRECTIONAL_ARGB_TOP_LEFT_8X8);
    }

    /// Asserts that the reader returned one still-picture frame filled with one constant pixel.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    /// @param expectedPixel the expected constant packed ARGB pixel value
    private static void assertStillPictureFrameFilledWith(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            long expectedPresentationIndex,
            int expectedPixel
    ) {
        assertNotNull(decodedFrame);
        assertTrue(decodedFrame instanceof ArgbIntFrame);
        ArgbIntFrame frame = (ArgbIntFrame) decodedFrame;
        assertDecodedStillPictureFrameMetadata(frame, expectedPresentationIndex);

        int[] pixels = frame.pixels();
        assertEquals(64 * 64, pixels.length);
        for (int pixel : pixels) {
            assertEquals(expectedPixel, pixel);
        }
    }

    /// Asserts one rectangular ARGB block against exact expected pixels.
    ///
    /// @param frame the decoded ARGB frame to inspect
    /// @param originX the zero-based horizontal block origin
    /// @param originY the zero-based vertical block origin
    /// @param expectedPixels the expected packed ARGB pixels in row-major order
    private static void assertArgbBlockEquals(ArgbIntFrame frame, int originX, int originY, int[][] expectedPixels) {
        int[] pixels = frame.pixels();
        int frameWidth = frame.width();
        for (int y = 0; y < expectedPixels.length; y++) {
            int rowOffset = (originY + y) * frameWidth + originX;
            for (int x = 0; x < expectedPixels[y].length; x++) {
                assertEquals(expectedPixels[y][x], pixels[rowOffset + x]);
            }
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

    /// Asserts that the legacy still-picture fixture decoded through at least one directional luma
    /// leaf after already decoding earlier non-directional blocks.
    ///
    /// @param syntaxResult the structural decode result produced for the legacy fixture
    private static void assertLegacyDirectionalLeafDecoded(
            @org.jetbrains.annotations.Nullable FrameSyntaxDecodeResult syntaxResult
    ) {
        assertNotNull(syntaxResult);
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxResult.tileRoots(0));
        assertFalse(leaves.isEmpty());

        int firstDirectionalLeafIndex = -1;
        for (int i = 0; i < leaves.size(); i++) {
            TilePartitionTreeReader.LeafNode leaf = leaves.get(i);
            if (leaf.header().yMode() != null && leaf.header().yMode().isDirectional()) {
                firstDirectionalLeafIndex = i;
                break;
            }
        }

        assertTrue(firstDirectionalLeafIndex > 0);
        TilePartitionTreeReader.LeafNode leafNode = leaves.get(firstDirectionalLeafIndex);
        assertNotNull(leafNode.header().yMode());
        assertTrue(leafNode.header().yMode().isDirectional());
    }

    /// Returns every leaf node in raster order from one tile-root array.
    ///
    /// @param roots the top-level tile roots
    /// @return every leaf node in raster order
    private static List<TilePartitionTreeReader.LeafNode> leavesInRasterOrder(TilePartitionTreeReader.Node[] roots) {
        List<TilePartitionTreeReader.LeafNode> leaves = new ArrayList<>();
        for (TilePartitionTreeReader.Node root : roots) {
            appendLeavesInRasterOrder(root, leaves);
        }
        return leaves;
    }

    /// Appends every leaf node in raster order from one subtree.
    ///
    /// @param node the subtree root
    /// @param leaves the destination list for leaf nodes
    private static void appendLeavesInRasterOrder(
            TilePartitionTreeReader.Node node,
            List<TilePartitionTreeReader.LeafNode> leaves
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            leaves.add(leafNode);
            return;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            appendLeavesInRasterOrder(child, leaves);
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

    /// Creates one non-reduced still-picture-compatible sequence header payload that enables
    /// `show_existing_frame` while keeping the current `64x64` `8-bit I420` fixture geometry.
    ///
    /// @return one non-reduced still-picture-compatible sequence header payload
    private static byte[] fullSequenceHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 5);
        writer.writeBits(0, 12);
        writer.writeBits(3, 3);
        writer.writeBits(1, 2);
        writer.writeFlag(false);
        writer.writeBits(5, 4);
        writer.writeBits(5, 4);
        writer.writeBits(63, 6);
        writer.writeBits(63, 6);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
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

    /// Creates a reduced still-picture key frame header payload.
    ///
    /// @return the reduced still-picture key frame header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates one non-reduced still-picture-compatible key frame header payload.
    ///
    /// @return one non-reduced still-picture-compatible key frame header payload
    private static byte[] fullStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeFullStillPictureFrameHeaderBits(writer);
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

    /// Creates one non-reduced still-picture-compatible combined frame payload with a caller-supplied
    /// tile group.
    ///
    /// @param tileGroupPayload the tile-group payload appended after the frame header
    /// @return one non-reduced still-picture-compatible combined frame payload
    private static byte[] fullStillPictureCombinedFramePayload(byte[] tileGroupPayload) {
        BitWriter writer = new BitWriter();
        writeFullStillPictureFrameHeaderBits(writer);
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

    /// Writes one non-reduced still-picture-compatible key frame header syntax without standalone
    /// trailing bits.
    ///
    /// @param writer the destination bit writer
    private static void writeFullStillPictureFrameHeaderBits(BitWriter writer) {
        writer.writeFlag(false);
        writer.writeBits(0, 2);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
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

    /// One injected reference-slot state used by `show_existing_frame` tests.
    @NotNullByDefault
    private static final class InjectedReferenceState {
        /// The sequence header that enables `show_existing_frame` parsing.
        private final SequenceHeader sequenceHeader;

        /// The stored frame header for the referenced slot.
        private final FrameHeader frameHeader;

        /// The stored structural syntax result for the referenced slot.
        private final FrameSyntaxDecodeResult syntaxResult;

        /// The stored reconstructed reference surface snapshot, or `null`.
        private final @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot;

        /// Creates one injected reference-slot state.
        ///
        /// @param sequenceHeader the sequence header that enables `show_existing_frame` parsing
        /// @param frameHeader the stored frame header for the referenced slot
        /// @param syntaxResult the stored structural syntax result for the referenced slot
        /// @param referenceSurfaceSnapshot the stored reconstructed reference surface snapshot, or `null`
        private InjectedReferenceState(
                SequenceHeader sequenceHeader,
                FrameHeader frameHeader,
                FrameSyntaxDecodeResult syntaxResult,
                @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot
        ) {
            this.sequenceHeader = sequenceHeader;
            this.frameHeader = frameHeader;
            this.syntaxResult = syntaxResult;
            this.referenceSurfaceSnapshot = referenceSurfaceSnapshot;
        }

        /// Returns the sequence header that enables `show_existing_frame` parsing.
        ///
        /// @return the sequence header that enables `show_existing_frame` parsing
        private SequenceHeader sequenceHeader() {
            return sequenceHeader;
        }

        /// Returns the stored frame header for the referenced slot.
        ///
        /// @return the stored frame header for the referenced slot
        private FrameHeader frameHeader() {
            return frameHeader;
        }

        /// Returns the stored structural syntax result for the referenced slot.
        ///
        /// @return the stored structural syntax result for the referenced slot
        private FrameSyntaxDecodeResult syntaxResult() {
            return syntaxResult;
        }

        /// Returns the stored reconstructed reference surface snapshot, or `null`.
        ///
        /// @return the stored reconstructed reference surface snapshot, or `null`
        private @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot() {
            return referenceSurfaceSnapshot;
        }
    }
}
