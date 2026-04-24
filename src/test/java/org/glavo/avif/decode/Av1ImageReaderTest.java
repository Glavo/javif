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
import org.glavo.avif.internal.av1.decode.BlockNeighborContext;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TileResidualSyntaxReader;
import org.glavo.avif.internal.av1.decode.TileTransformLayoutReader;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.output.ArgbOutput;
import org.glavo.avif.internal.av1.postfilter.FilmGrainSynthesizer;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.glavo.avif.internal.io.BufferedInput;
import org.glavo.avif.testutil.HexFixtureResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /// One deterministic real tile payload whose first visible `8x8` key-frame leaf uses both luma
    /// and chroma palettes.
    private static final byte[] PALETTE_BLOCK_TILE_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/palette-block.hex");

    /// One deterministic real inter tile payload whose first decoded block uses one stored
    /// reference surface with a zero motion vector.
    private static final byte[] INTER_BLOCK_TILE_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/all-zero-8.hex");

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

    /// Verifies that the same supported real tile payload also decodes through parsed `I422` and
    /// `I444` reduced still-picture combined streams.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForSupportedCombinedStillPictureStreamsWithRealI422AndI444SequenceHeaders()
            throws IOException {
        assertSupportedStillPictureRoundTripWithAdditionalChromaLayout(PixelFormat.I422, true);
        assertSupportedStillPictureRoundTripWithAdditionalChromaLayout(PixelFormat.I444, true);
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

    /// Verifies that the same supported real tile payload also decodes through parsed `I422` and
    /// `I444` reduced still-picture standalone streams.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForSupportedStandaloneStillPictureStreamsWithRealI422AndI444SequenceHeaders()
            throws IOException {
        assertSupportedStillPictureRoundTripWithAdditionalChromaLayout(PixelFormat.I422, false);
        assertSupportedStillPictureRoundTripWithAdditionalChromaLayout(PixelFormat.I444, false);
    }

    /// Verifies that one real parsed `I422` or `I444` still-picture decode can immediately refresh
    /// a reference slot and then round-trip through one standalone `show_existing_frame` header.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForShowExistingFrameBackedByRealParsedStillPicturesWithAdditionalChromaLayouts()
            throws IOException {
        assertRealParsedStillPictureShowExistingFrameRoundTripWithAdditionalChromaLayout(PixelFormat.I422, false);
        assertRealParsedStillPictureShowExistingFrameRoundTripWithAdditionalChromaLayout(PixelFormat.I444, false);
    }

    /// Verifies that one real parsed `I422` or `I444` still-picture decode can immediately refresh
    /// a reference slot and then round-trip through one combined `FRAME` `show_existing_frame` OBU.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForCombinedShowExistingFrameBackedByRealParsedStillPicturesWithAdditionalChromaLayouts()
            throws IOException {
        assertRealParsedStillPictureShowExistingFrameRoundTripWithAdditionalChromaLayout(PixelFormat.I422, true);
        assertRealParsedStillPictureShowExistingFrameRoundTripWithAdditionalChromaLayout(PixelFormat.I444, true);
    }

    /// Verifies that one standalone `show_existing_frame` header can expose a reconstructed palette
    /// surface whose stored syntax/result state comes from a deterministic real bitstream fixture
    /// rather than synthetic leaf injection.
    ///
    /// @throws Exception if the real palette fixture cannot be decoded or injected
    @Test
    void readFrameReturnsArgbIntFrameForShowExistingFrameBackedByRealBitstreamDerivedPaletteReferenceSurface() throws Exception {
        InjectedReferenceState referenceState = createRealPaletteReferenceStateFromBitstreamFixture();
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = Objects.requireNonNull(referenceState.referenceSurfaceSnapshot());
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject real palette reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that one combined `FRAME` `show_existing_frame` OBU also reuses a real
    /// bitstream-derived palette surface through the public reader.
    ///
    /// @throws Exception if the real palette fixture cannot be decoded or injected
    @Test
    void readFrameReturnsStoredReferenceSurfaceForCombinedShowExistingFrameBackedByRealBitstreamDerivedPaletteState() throws Exception {
        InjectedReferenceState referenceState = createRealPaletteReferenceStateFromBitstreamFixture();
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = Objects.requireNonNull(referenceState.referenceSurfaceSnapshot());
        byte[] stream = obu(6, showExistingFrameHeaderPayload(0));

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            injectShowExistingReferenceState(reader, referenceState);

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        }
    }

    /// Verifies that one standalone `show_existing_frame` header can expose a reconstructed real
    /// bitstream-derived palette surface through the widened `I422` / `I444` public layouts.
    ///
    /// @throws Exception if the real palette fixture cannot be decoded or injected
    @Test
    void readFrameReturnsArgbIntFrameForShowExistingFrameBackedByRealBitstreamDerivedPaletteReferenceSurfaceWithAdditionalChromaLayouts()
            throws Exception {
        assertRealBitstreamDerivedPaletteReferenceSurfaceShowExistingFrameRoundTripWithAdditionalChromaLayout(
                PixelFormat.I422,
                false
        );
        assertRealBitstreamDerivedPaletteReferenceSurfaceShowExistingFrameRoundTripWithAdditionalChromaLayout(
                PixelFormat.I444,
                false
        );
    }

    /// Verifies that one combined `FRAME` `show_existing_frame` OBU can also expose a reconstructed
    /// real bitstream-derived palette surface through the widened `I422` / `I444` public layouts.
    ///
    /// @throws Exception if the real palette fixture cannot be decoded or injected
    @Test
    void readFrameReturnsArgbIntFrameForCombinedShowExistingFrameBackedByRealBitstreamDerivedPaletteReferenceSurfaceWithAdditionalChromaLayouts()
            throws Exception {
        assertRealBitstreamDerivedPaletteReferenceSurfaceShowExistingFrameRoundTripWithAdditionalChromaLayout(
                PixelFormat.I422,
                true
        );
        assertRealBitstreamDerivedPaletteReferenceSurfaceShowExistingFrameRoundTripWithAdditionalChromaLayout(
                PixelFormat.I444,
                true
        );
    }

    /// Verifies that one standalone real parsed inter frame reconstructs through the public reader
    /// once slot `0` already exposes one injected stored reference surface.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForStandaloneRealParsedInterFrameBackedByInjectedStoredReferenceSurface()
            throws IOException {
        assertRealParsedInterFrameRoundTripWithInjectedStoredReferenceSurface(false);
    }

    /// Verifies that one combined real parsed inter frame reconstructs through the public reader
    /// once slot `0` already exposes one injected stored reference surface.
    ///
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    @Test
    void readFrameReturnsArgbIntFrameForCombinedRealParsedInterFrameBackedByInjectedStoredReferenceSurface()
            throws IOException {
        assertRealParsedInterFrameRoundTripWithInjectedStoredReferenceSurface(true);
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

    /// Verifies that one standalone `show_existing_frame` header can expose synthetic stored
    /// reference surfaces for `I422` and `I444` once ARGB conversion supports those layouts.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsArgbIntFrameForShowExistingFrameBackedBySyntheticStoredReferenceSurfacesWithAdditionalChromaLayouts() throws Exception {
        assertSyntheticStoredReferenceSurfaceShowExistingFrameRoundTrip(PixelFormat.I422);
        assertSyntheticStoredReferenceSurfaceShowExistingFrameRoundTrip(PixelFormat.I444);
    }

    /// Verifies that combined `FRAME` `show_existing_frame` OBUs can expose synthetic stored
    /// reference surfaces for `I422` and `I444` once ARGB conversion supports those layouts.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsStoredReferenceSurfaceForCombinedShowExistingFrameBackedBySyntheticStoredReferenceSurfacesWithAdditionalChromaLayouts() throws Exception {
        assertSyntheticStoredReferenceSurfaceCombinedShowExistingFrameRoundTrip(PixelFormat.I422);
        assertSyntheticStoredReferenceSurfaceCombinedShowExistingFrameRoundTrip(PixelFormat.I444);
    }

    /// Verifies that one standalone `show_existing_frame` header can expose one synthetic stored
    /// inter reference surface whose frame header keeps super-resolution enabled while the stored
    /// surface already lives in the post-upscale domain.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsStoredInterSuperResolvedReferenceSurfaceForStandaloneShowExistingFrame() throws Exception {
        assertSyntheticStoredInterSuperResolvedReferenceSurfaceShowExistingFrameRoundTrip(FrameType.INTER, false);
    }

    /// Verifies that one combined `FRAME` `show_existing_frame` OBU can expose one synthetic stored
    /// switch-frame reference surface whose frame header keeps super-resolution enabled while the
    /// stored surface already lives in the post-upscale domain.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsStoredSwitchSuperResolvedReferenceSurfaceForCombinedShowExistingFrame() throws Exception {
        assertSyntheticStoredInterSuperResolvedReferenceSurfaceShowExistingFrameRoundTrip(FrameType.SWITCH, true);
    }

    /// Verifies that disabling film grain synthesis still exposes one stored reconstructed
    /// reference surface through `show_existing_frame` when the referenced frame carries film grain.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsStoredReferenceSurfaceForFilmGrainBearingShowExistingFrameWhenFilmGrainIsDisabled()
            throws Exception {
        InjectedReferenceState referenceState = createFilmGrainBearingReferenceStateFromSupportedStillPicture();
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));
        Av1DecoderConfig config = Av1DecoderConfig.builder().applyFilmGrain(false).build();

        assertTrue(referenceState.frameHeader().filmGrain().applyGrain());
        assertAcrossBufferedInputs(stream, config, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject film-grain-bearing reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that enabling film grain synthesis now exposes one grain-applied
    /// `show_existing_frame` output when the referenced slot carries film grain.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsFilmGrainBearingShowExistingFrameWhenFilmGrainIsEnabled() throws Exception {
        InjectedReferenceState referenceState = createFilmGrainBearingReferenceStateFromSupportedStillPicture();
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));
        Av1DecoderConfig config = Av1DecoderConfig.builder().applyFilmGrain(true).build();

        assertTrue(referenceState.frameHeader().filmGrain().applyGrain());
        assertAcrossBufferedInputs(stream, config, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject film-grain-bearing reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesGrainAppliedReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that one stored high-bit-depth reference surface is exposed as an `ArgbLongFrame`
    /// through the public `show_existing_frame` path.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameReturnsArgbLongFrameForStoredHighBitDepthReferenceSurface() throws Exception {
        InjectedReferenceState referenceState =
                createSyntheticStoredReferenceStateWithHighBitDepthSurface(PixelFormat.I444, 12, 2048);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject synthetic high-bit-depth reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertTrue(decodedFrame instanceof ArgbLongFrame);
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Verifies that `decodeFrameType = KEY` suppresses one stored non-key surface on the public
    /// `show_existing_frame` path.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameSuppressesStoredNonKeySurfaceWhenDecodeFrameTypeIsKey() throws Exception {
        InjectedReferenceState referenceState = createSyntheticStoredReferenceStateForDecodeFilter(FrameType.INTER, 0xFF);
        Av1DecoderConfig config = Av1DecoderConfig.builder().decodeFrameType(DecodeFrameType.KEY).build();
        assertStoredShowExistingFrameIsSuppressedByDecodeFrameType(referenceState, config);
    }

    /// Verifies that `decodeFrameType = REFERENCE` suppresses one stored non-reference surface on
    /// the public `show_existing_frame` path.
    ///
    /// @throws Exception if synthetic reference-state injection fails
    @Test
    void readFrameSuppressesStoredNonReferenceSurfaceWhenDecodeFrameTypeIsReference() throws Exception {
        InjectedReferenceState referenceState = createSyntheticStoredReferenceStateForDecodeFilter(FrameType.INTRA, 0);
        Av1DecoderConfig config = Av1DecoderConfig.builder().decodeFrameType(DecodeFrameType.REFERENCE).build();
        assertStoredShowExistingFrameIsSuppressedByDecodeFrameType(referenceState, config);
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
        assertAcrossBufferedInputs(stream, Av1DecoderConfig.builder().build(), assertion);
    }

    /// Runs the supplied assertion across all currently supported buffered-input adapters with one
    /// explicit immutable decoder configuration.
    ///
    /// @param stream the raw test stream
    /// @param config the decoder configuration used for every reader
    /// @param assertion the reader assertion to run for every adapter
    /// @throws IOException if one adapter cannot be opened or consumed
    private static void assertAcrossBufferedInputs(
            byte[] stream,
            Av1DecoderConfig config,
            ReaderAssertion assertion
    ) throws IOException {
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)),
                config
        )) {
            assertion.accept(reader);
        }
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfInputStream(new ByteArrayInputStream(stream)),
                config
        )) {
            assertion.accept(reader);
        }
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteChannel(Channels.newChannel(new ByteArrayInputStream(stream))),
                config
        )) {
            assertion.accept(reader);
        }
    }

    /// Asserts that one stored `show_existing_frame` surface is parsed but suppressed by the
    /// configured public decode-frame-type filter across every buffered-input adapter.
    ///
    /// @param referenceState the stored reference state that should be parsed but not exposed
    /// @param config the decoder configuration whose frame-type filter should suppress output
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    private static void assertStoredShowExistingFrameIsSuppressedByDecodeFrameType(
            InjectedReferenceState referenceState,
            Av1DecoderConfig config
    ) throws IOException {
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));
        assertAcrossBufferedInputs(stream, config, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject decode-filter reference state", exception);
            }

            assertTrue(reader.readAllFrames().isEmpty());
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertSame(referenceState.frameHeader(), reader.referenceFrameHeader(0));
            assertSame(referenceState.syntaxResult(), reader.referenceFrameSyntaxResult(0));
            assertSame(referenceState.referenceSurfaceSnapshot(), reader.referenceSurfaceSnapshot(0));
        });
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
            return new InjectedReferenceState(
                    parseFullSequenceHeader(),
                    Objects.requireNonNull(reader.referenceFrameHeader(0), "frame header"),
                    Objects.requireNonNull(reader.referenceFrameSyntaxResult(0), "syntax result"),
                    Objects.requireNonNull(reader.referenceSurfaceSnapshot(0), "reference surface")
            );
        }
    }

    /// Decodes one deterministic real palette tile payload into structural and reconstructed
    /// reference-slot state for public-reader `show_existing_frame` coverage.
    ///
    /// @return one reconstructed reference-slot state derived from the real palette fixture
    /// @throws IOException if the real fixture cannot be structurally decoded or reconstructed
    private static InjectedReferenceState createRealPaletteReferenceStateFromBitstreamFixture() throws IOException {
        return createRealPaletteReferenceStateFromBitstreamFixture(PixelFormat.I420);
    }

    /// Decodes one deterministic real palette tile payload into structural and reconstructed
    /// reference-slot state for the requested public chroma layout.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @return one reconstructed reference-slot state derived from the real palette fixture
    /// @throws IOException if the real fixture cannot be structurally decoded or reconstructed
    private static InjectedReferenceState createRealPaletteReferenceStateFromBitstreamFixture(
            PixelFormat pixelFormat
    ) throws IOException {
        FrameAssembly assembly = createRealPaletteSingleTileAssemblyFromFixture(pixelFormat);
        TileDecodeContext tileContext = TileDecodeContext.create(assembly, 0);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        TileBlockHeaderReader.BlockHeader blockHeader =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext);
        TransformLayout transformLayout = new TileTransformLayoutReader(tileContext).read(blockHeader, neighborContext);
        ResidualLayout residualLayout = new TileResidualSyntaxReader(tileContext).read(blockHeader, transformLayout, neighborContext);
        TilePartitionTreeReader.LeafNode paletteLeaf =
                new TilePartitionTreeReader.LeafNode(blockHeader, transformLayout, residualLayout);
        FrameSyntaxDecodeResult syntaxResult = createSingleLeafSyntaxResult(assembly, paletteLeaf);
        requireFirstPaletteLeaf(syntaxResult);
        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxResult);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                new ReferenceSurfaceSnapshot(assembly.frameHeader(), syntaxResult, decodedPlanes);

        return new InjectedReferenceState(
                parseFullSequenceHeader(pixelFormat),
                assembly.frameHeader(),
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Creates injected reference-slot state for the real parsed inter-frame success path.
    ///
    /// Slot `0` carries one populated reconstructed surface while the remaining parser-visible
    /// slots provide only the order-hint and frame-size metadata needed by the parsed inter frame.
    ///
    /// @return injected reference-slot state for the real parsed inter-frame success path
    private static InjectedReferenceState[] createInterReferenceStatesForRealParsedInterFrame() {
        SequenceHeader sequenceHeader = createFullInterSequenceHeader();
        FrameHeader[] referenceFrameHeaders = createInterReferenceFrameHeaders();
        InjectedReferenceState[] referenceStates = new InjectedReferenceState[referenceFrameHeaders.length];
        for (int i = 0; i < referenceFrameHeaders.length; i++) {
            FrameHeader referenceFrameHeader = referenceFrameHeaders[i];
            FrameSyntaxDecodeResult syntaxResult =
                    createSyntheticStoredReferenceSyntaxResult(sequenceHeader, referenceFrameHeader);
            @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot = null;
            if (i == 0) {
                referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                        referenceFrameHeader,
                        syntaxResult,
                        createGradientInterReferenceDecodedPlanes(referenceFrameHeader)
                );
            }
            referenceStates[i] = new InjectedReferenceState(
                    sequenceHeader,
                    referenceFrameHeader,
                    syntaxResult,
                    referenceSurfaceSnapshot
            );
        }
        return referenceStates;
    }

    /// Injects every reference-slot state required by the real parsed inter-frame fixture.
    ///
    /// @param reader the fresh reader that should expose the injected reference slots
    /// @param referenceStates the parser-compatible reference-slot states
    private static void injectInterReferenceStates(
            Av1ImageReader reader,
            InjectedReferenceState[] referenceStates
    ) {
        for (int i = 0; i < referenceStates.length; i++) {
            InjectedReferenceState referenceState = referenceStates[i];
            reader.injectReferenceStateForTest(
                    i,
                    referenceState.sequenceHeader(),
                    referenceState.frameHeader(),
                    referenceState.syntaxResult(),
                    referenceState.referenceSurfaceSnapshot()
            );
        }
    }

    /// Creates one synthetic non-reduced `I420` sequence header compatible with parsed inter-frame
    /// public-reader coverage.
    ///
    /// @return one synthetic non-reduced `I420` sequence header compatible with parsed inter frames
    private static SequenceHeader createFullInterSequenceHeader() {
        return new SequenceHeader(
                0,
                1024,
                512,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(2, 0, 10, 0, false, false, false, null)
                },
                false,
                false,
                10,
                9,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        4,
                        true,
                        false,
                        false,
                        false
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        false,
                        PixelFormat.I420,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates parser-visible refreshed reference headers with deterministic `64x64` geometry and
    /// order hints on both sides of the current parsed inter frame.
    ///
    /// @return parser-visible refreshed reference headers for the real parsed inter-frame fixture
    private static FrameHeader[] createInterReferenceFrameHeaders() {
        return new FrameHeader[]{
                createInterReferenceFrameHeader(9),
                createInterReferenceFrameHeader(8),
                createInterReferenceFrameHeader(7),
                createInterReferenceFrameHeader(11),
                createInterReferenceFrameHeader(12),
                createInterReferenceFrameHeader(13),
                createInterReferenceFrameHeader(14)
        };
    }

    /// Creates one parser-visible refreshed inter reference header with deterministic `64x64`
    /// geometry and default inherited syntax state.
    ///
    /// @param frameOffset the order hint stored by the refreshed reference frame
    /// @return one parser-visible refreshed inter reference header
    private static FrameHeader createInterReferenceFrameHeader(int frameOffset) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.INTER,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                7,
                frameOffset,
                0,
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        1,
                        new int[]{0, 1},
                        new int[]{0, 1},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                true,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        true,
                        true,
                        new int[]{1, 0, 0, 0, -1, 0, -1, -1},
                        new int[]{0, 0}
                ),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one complete single-tile frame assembly whose tile bytes come from the deterministic
    /// real palette fixture used by block-header tests.
    ///
    /// @return one complete single-tile frame assembly whose tile bytes come from the real palette fixture
    private static FrameAssembly createRealPaletteSingleTileAssemblyFromFixture(PixelFormat pixelFormat) {
        if (pixelFormat == PixelFormat.I400) {
            throw new IllegalArgumentException("Real palette fixture expects I420, I422, or I444: " + pixelFormat);
        }

        SequenceHeader sequenceHeader = new SequenceHeader(
                0,
                64,
                64,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(2, 0, 10, 0, false, false, false, null)
                },
                true,
                true,
                15,
                15,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        0,
                        false,
                        false,
                        false,
                        false
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat == PixelFormat.I420 || pixelFormat == PixelFormat.I422,
                        pixelFormat == PixelFormat.I420,
                        false
                )
        );
        FrameHeader frameHeader = new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.KEY,
                true,
                false,
                true,
                false,
                true,
                true,
                false,
                7,
                0,
                0xFF,
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                false,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                false,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        1,
                        new int[]{0, 1},
                        new int[]{0, 1},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                true,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        true,
                        true,
                        new int[]{1, 0, 0, 0, -1, 0, -1, -1},
                        new int[]{0, 0}
                ),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                false
        );

        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), PALETTE_BLOCK_TILE_PAYLOAD, 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                PALETTE_BLOCK_TILE_PAYLOAD.length,
                new TileBitstream[]{new TileBitstream(0, PALETTE_BLOCK_TILE_PAYLOAD, 0, PALETTE_BLOCK_TILE_PAYLOAD.length)}
        );
        return assembly;
    }

    /// Wraps one real bitstream-derived leaf in a minimal single-tile syntax result.
    ///
    /// @param assembly the frame assembly that owns the decoded leaf
    /// @param leafNode the real bitstream-derived leaf node
    /// @return one minimal single-tile syntax result that exposes the supplied leaf
    private static FrameSyntaxDecodeResult createSingleLeafSyntaxResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leafNode}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates the default per-segment data used by deterministic single-frame fixture assemblies.
    ///
    /// @return the default per-segment data used by deterministic single-frame fixture assemblies
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
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

    /// Creates one synthetic stored reference slot that exposes one neutral-gray `I422` or `I444`
    /// surface through the public `show_existing_frame` path.
    ///
    /// @param pixelFormat the synthetic chroma layout to expose
    /// @return one synthetic stored reference slot for the requested chroma layout
    /// @throws Exception if the base still-picture metadata cannot be captured
    private static InjectedReferenceState createSyntheticStoredReferenceStateWithAdditionalChromaLayout(
            PixelFormat pixelFormat
    ) throws Exception {
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        SequenceHeader sequenceHeader =
                copySequenceHeaderWithPixelFormat(baseReferenceState.sequenceHeader(), pixelFormat);
        FrameHeader frameHeader = baseReferenceState.frameHeader();
        FrameSyntaxDecodeResult syntaxResult = createSyntheticStoredReferenceSyntaxResult(sequenceHeader, frameHeader);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                frameHeader,
                syntaxResult,
                createNeutralGrayDecodedPlanes(frameHeader, pixelFormat)
        );

        return new InjectedReferenceState(
                sequenceHeader,
                frameHeader,
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Creates one synthetic stored reference slot whose reconstructed surface uses the requested
    /// high bit depth and chroma layout.
    ///
    /// @param pixelFormat the synthetic chroma layout to expose
    /// @param bitDepth the requested stored-surface bit depth
    /// @param sampleValue the constant unsigned sample value stored in every plane sample
    /// @return one synthetic stored reference slot for the requested high-bit-depth surface
    /// @throws Exception if the base still-picture metadata cannot be captured
    private static InjectedReferenceState createSyntheticStoredReferenceStateWithHighBitDepthSurface(
            PixelFormat pixelFormat,
            int bitDepth,
            int sampleValue
    ) throws Exception {
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        SequenceHeader sequenceHeader =
                copySequenceHeaderWithPixelFormatAndBitDepth(baseReferenceState.sequenceHeader(), pixelFormat, bitDepth);
        FrameHeader frameHeader = baseReferenceState.frameHeader();
        FrameSyntaxDecodeResult syntaxResult = createSyntheticStoredReferenceSyntaxResult(sequenceHeader, frameHeader);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                frameHeader,
                syntaxResult,
                createFilledDecodedPlanes(frameHeader, pixelFormat, bitDepth, sampleValue)
        );
        return new InjectedReferenceState(
                sequenceHeader,
                frameHeader,
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Creates one synthetic stored reference slot whose header is modified only for
    /// decode-frame-type filtering tests.
    ///
    /// @param frameType the replacement frame type
    /// @param refreshFrameFlags the replacement refresh flags
    /// @return one synthetic stored reference slot with the requested filter-relevant header fields
    /// @throws Exception if the base still-picture metadata cannot be captured
    private static InjectedReferenceState createSyntheticStoredReferenceStateForDecodeFilter(
            FrameType frameType,
            int refreshFrameFlags
    ) throws Exception {
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        FrameHeader filteredFrameHeader =
                copyFrameHeaderWithFrameTypeAndRefreshFlags(baseReferenceState.frameHeader(), frameType, refreshFrameFlags);
        FrameSyntaxDecodeResult syntaxResult =
                createSyntheticStoredReferenceSyntaxResult(baseReferenceState.sequenceHeader(), filteredFrameHeader);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                filteredFrameHeader,
                syntaxResult,
                Objects.requireNonNull(baseReferenceState.referenceSurfaceSnapshot(), "reference surface").decodedPlanes()
        );
        return new InjectedReferenceState(
                baseReferenceState.sequenceHeader(),
                filteredFrameHeader,
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Creates one synthetic stored reference slot whose frame header exposes one inter-like frame
    /// with super-resolution enabled while the stored surface remains in the post-upscale domain.
    ///
    /// @param frameType the inter-like frame type to expose
    /// @return one synthetic stored reference slot for inter super-resolution public reuse tests
    /// @throws Exception if the base still-picture metadata cannot be captured
    private static InjectedReferenceState createSyntheticStoredReferenceStateWithInterSuperResolution(
            FrameType frameType
    ) throws Exception {
        if (frameType != FrameType.INTER && frameType != FrameType.SWITCH) {
            throw new IllegalArgumentException("Inter super-resolution helper expects INTER or SWITCH: " + frameType);
        }
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        ReferenceSurfaceSnapshot baseSurfaceSnapshot =
                Objects.requireNonNull(baseReferenceState.referenceSurfaceSnapshot(), "base reference surface");
        FrameHeader superResolvedFrameHeader = copyFrameHeaderWithFrameTypeAndSuperResolution(
                baseReferenceState.frameHeader(),
                frameType,
                32,
                64,
                64
        );
        FrameSyntaxDecodeResult syntaxResult = createSyntheticStoredReferenceSyntaxResult(
                baseReferenceState.sequenceHeader(),
                superResolvedFrameHeader
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                superResolvedFrameHeader,
                syntaxResult,
                baseSurfaceSnapshot.decodedPlanes()
        );
        return new InjectedReferenceState(
                baseReferenceState.sequenceHeader(),
                superResolvedFrameHeader,
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Creates one synthetic stored reference slot whose frame header carries minimal explicit film
    /// grain while reusing the current supported still-picture surface.
    ///
    /// @return one synthetic stored reference slot whose referenced frame carries film grain
    /// @throws Exception if the base still-picture metadata cannot be captured
    private static InjectedReferenceState createFilmGrainBearingReferenceStateFromSupportedStillPicture() throws Exception {
        InjectedReferenceState baseReferenceState = captureReferenceStateFromSupportedStillPicture();
        ReferenceSurfaceSnapshot baseSurfaceSnapshot =
                Objects.requireNonNull(baseReferenceState.referenceSurfaceSnapshot(), "base reference surface");
        FrameHeader filmGrainFrameHeader = copyFrameHeaderWithFilmGrain(
                baseReferenceState.frameHeader(),
                minimalFilmGrainParams(0x1234)
        );
        FrameSyntaxDecodeResult syntaxResult =
                createSyntheticStoredReferenceSyntaxResult(baseReferenceState.sequenceHeader(), filmGrainFrameHeader);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = new ReferenceSurfaceSnapshot(
                filmGrainFrameHeader,
                syntaxResult,
                baseSurfaceSnapshot.decodedPlanes()
        );

        return new InjectedReferenceState(
                baseReferenceState.sequenceHeader(),
                filmGrainFrameHeader,
                syntaxResult,
                referenceSurfaceSnapshot
        );
    }

    /// Asserts that the supported minimal real tile payload round-trips through the public reader
    /// with one parsed `I422` or `I444` reduced still-picture sequence header.
    ///
    /// @param pixelFormat the parsed chroma layout to expose
    /// @param combined whether to use one combined `FRAME` OBU instead of standalone frame assembly
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    private static void assertSupportedStillPictureRoundTripWithAdditionalChromaLayout(
            PixelFormat pixelFormat,
            boolean combined
    ) throws IOException {
        byte[] stream = combined
                ? concat(
                        obu(1, reducedStillPicturePayload(pixelFormat)),
                        obu(6, reducedStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD))
                )
                : concat(
                        obu(1, reducedStillPicturePayload(pixelFormat)),
                        obu(3, reducedStillPictureFrameHeaderPayload()),
                        obu(4, SUPPORTED_SINGLE_TILE_PAYLOAD)
                );

        assertAcrossBufferedInputs(stream, reader -> {
            assertOpaqueGrayStillPictureFrame(reader.readFrame(), pixelFormat, 0);
            assertFirstDecodedLeafIsIntra(reader.lastFrameSyntaxDecodeResult());
            assertReferenceStateStoredForLastSyntaxResult(reader);
            assertNull(reader.readFrame());
        });
    }

    /// Asserts that one real parsed still-picture decode in the requested additional chroma layout
    /// immediately refreshes a reference slot and then round-trips through `show_existing_frame`.
    ///
    /// @param pixelFormat the parsed chroma layout to expose
    /// @param combinedShowExisting whether the follow-up `show_existing_frame` uses one combined `FRAME` OBU
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    private static void assertRealParsedStillPictureShowExistingFrameRoundTripWithAdditionalChromaLayout(
            PixelFormat pixelFormat,
            boolean combinedShowExisting
    ) throws IOException {
        byte[] stream = concat(
                obu(1, fullSequenceHeaderPayload(pixelFormat)),
                obu(6, fullStillPictureCombinedFramePayload(SUPPORTED_SINGLE_TILE_PAYLOAD)),
                obu(combinedShowExisting ? 6 : 3, showExistingFrameHeaderPayload(0))
        );

        assertAcrossBufferedInputs(stream, reader -> {
            DecodedFrame firstFrame = reader.readFrame();
            assertOpaqueGrayStillPictureFrame(firstFrame, pixelFormat, 0);
            assertReferenceStateStoredForLastSyntaxResult(reader);

            DecodedFrame reusedFrame = reader.readFrame();
            assertOpaqueGrayStillPictureFrame(reusedFrame, pixelFormat, 1);
            assertNotNull(firstFrame);
            assertNotNull(reusedFrame);
            assertTrue(firstFrame instanceof ArgbIntFrame);
            assertTrue(reusedFrame instanceof ArgbIntFrame);
            assertArrayEquals(((ArgbIntFrame) firstFrame).pixels(), ((ArgbIntFrame) reusedFrame).pixels());
            assertNull(reader.readFrame());
        });
    }

    /// Asserts that one real bitstream-derived palette reference surface round-trips through the
    /// public `show_existing_frame` path in the requested additional chroma layout.
    ///
    /// @param pixelFormat the parsed chroma layout to expose
    /// @param combinedShowExisting whether the follow-up `show_existing_frame` uses one combined `FRAME` OBU
    /// @throws Exception if the real palette fixture cannot be decoded or injected
    private static void assertRealBitstreamDerivedPaletteReferenceSurfaceShowExistingFrameRoundTripWithAdditionalChromaLayout(
            PixelFormat pixelFormat,
            boolean combinedShowExisting
    ) throws Exception {
        InjectedReferenceState referenceState = createRealPaletteReferenceStateFromBitstreamFixture(pixelFormat);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = Objects.requireNonNull(
                referenceState.referenceSurfaceSnapshot(),
                "reference surface"
        );
        byte[] stream = obu(combinedShowExisting ? 6 : 3, showExistingFrameHeaderPayload(0));

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject real " + pixelFormat + " palette reference state", exception);
            }

            DecodedFrame reusedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(reusedFrame, referenceSurfaceSnapshot, 0);
            assertSame(reader.referenceFrameSyntaxResult(0), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Asserts that one real parsed inter frame reconstructs through the public reader once the
    /// required reference-frame parser state and slot `0` surface have been injected ahead of time.
    ///
    /// @param combined whether the inter frame is carried by one combined `FRAME` OBU
    /// @throws IOException if one buffered-input adapter cannot consume the test stream
    private static void assertRealParsedInterFrameRoundTripWithInjectedStoredReferenceSurface(
            boolean combined
    ) throws IOException {
        InjectedReferenceState[] referenceStates = createInterReferenceStatesForRealParsedInterFrame();
        InjectedReferenceState primaryReferenceState = referenceStates[0];
        ReferenceSurfaceSnapshot primaryReferenceSurfaceSnapshot = Objects.requireNonNull(
                primaryReferenceState.referenceSurfaceSnapshot(),
                "reference surface"
        );
        ReferenceSurfaceSnapshot[] referenceSurfaceSlots = new ReferenceSurfaceSnapshot[8];
        referenceSurfaceSlots[0] = primaryReferenceSurfaceSnapshot;
        byte[] stream = combined
                ? obu(6, combinedInterFramePayload(INTER_BLOCK_TILE_PAYLOAD))
                : concat(
                        obu(3, interFrameHeaderPayload()),
                        obu(4, INTER_BLOCK_TILE_PAYLOAD)
                );

        assertAcrossBufferedInputs(stream, reader -> {
            injectInterReferenceStates(reader, referenceStates);

            DecodedFrame decodedFrame = reader.readFrame();
            FrameSyntaxDecodeResult syntaxResult =
                    Objects.requireNonNull(reader.lastFrameSyntaxDecodeResult(), "syntax result");
            ReferenceSurfaceSnapshot expectedOutputSnapshot = new ReferenceSurfaceSnapshot(
                    syntaxResult.assembly().frameHeader(),
                    syntaxResult,
                    new FrameReconstructor().reconstruct(syntaxResult, referenceSurfaceSlots)
            );

            assertFirstDecodedLeafIsInter(syntaxResult);
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, expectedOutputSnapshot, 0);
            assertSame(primaryReferenceState.frameHeader(), reader.referenceFrameHeader(0));
            assertSame(primaryReferenceState.syntaxResult(), reader.referenceFrameSyntaxResult(0));
            assertSame(primaryReferenceSurfaceSnapshot, reader.referenceSurfaceSnapshot(0));
            assertNull(reader.readFrame());
        });
    }


    /// Asserts that one standalone `show_existing_frame` header exposes the requested synthetic
    /// stored reference surface through every buffered-input adapter.
    ///
    /// @param pixelFormat the synthetic chroma layout to expose
    /// @throws Exception if synthetic reference-state injection fails
    private static void assertSyntheticStoredReferenceSurfaceShowExistingFrameRoundTrip(
            PixelFormat pixelFormat
    ) throws Exception {
        InjectedReferenceState referenceState =
                createSyntheticStoredReferenceStateWithAdditionalChromaLayout(pixelFormat);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(3, showExistingFrameHeaderPayload(0));

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject synthetic " + pixelFormat + " reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
    }

    /// Asserts that one combined `FRAME` `show_existing_frame` OBU exposes the requested synthetic
    /// stored reference surface through the public reader.
    ///
    /// @param pixelFormat the synthetic chroma layout to expose
    /// @throws Exception if synthetic reference-state injection fails
    private static void assertSyntheticStoredReferenceSurfaceCombinedShowExistingFrameRoundTrip(
            PixelFormat pixelFormat
    ) throws Exception {
        InjectedReferenceState referenceState =
                createSyntheticStoredReferenceStateWithAdditionalChromaLayout(pixelFormat);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(6, showExistingFrameHeaderPayload(0));

        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
        )) {
            injectShowExistingReferenceState(reader, referenceState);

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        }
    }

    /// Asserts that one synthetic stored inter or switch reference surface with super-resolution
    /// enabled round-trips through the public `show_existing_frame` path.
    ///
    /// @param frameType the inter-like frame type exposed by the stored frame header
    /// @param combinedShowExisting whether the follow-up `show_existing_frame` uses one combined `FRAME` OBU
    /// @throws Exception if synthetic reference-state injection fails
    private static void assertSyntheticStoredInterSuperResolvedReferenceSurfaceShowExistingFrameRoundTrip(
            FrameType frameType,
            boolean combinedShowExisting
    ) throws Exception {
        InjectedReferenceState referenceState = createSyntheticStoredReferenceStateWithInterSuperResolution(frameType);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                Objects.requireNonNull(referenceState.referenceSurfaceSnapshot(), "reference surface");
        byte[] stream = obu(combinedShowExisting ? 6 : 3, showExistingFrameHeaderPayload(0));

        assertTrue(referenceState.frameHeader().superResolution().enabled());
        assertEquals(32, referenceState.frameHeader().frameSize().codedWidth());
        assertEquals(64, referenceState.frameHeader().frameSize().upscaledWidth());
        assertEquals(64, referenceSurfaceSnapshot.decodedPlanes().codedWidth());

        if (combinedShowExisting) {
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN))
            )) {
                injectShowExistingReferenceState(reader, referenceState);

                DecodedFrame decodedFrame = reader.readFrame();
                assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
                assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
                assertNull(reader.readFrame());
            }
            return;
        }

        assertAcrossBufferedInputs(stream, reader -> {
            try {
                injectShowExistingReferenceState(reader, referenceState);
            } catch (Exception exception) {
                throw new IOException("Failed to inject synthetic inter super-resolution reference state", exception);
            }

            DecodedFrame decodedFrame = reader.readFrame();
            assertStillPictureFrameMatchesReferenceSurface(decodedFrame, referenceSurfaceSnapshot, 0);
            assertSame(referenceState.syntaxResult(), reader.lastFrameSyntaxDecodeResult());
            assertNull(reader.readFrame());
        });
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
        return createSyntheticStoredReferenceSyntaxResult(sequenceHeader, frameHeader);
    }

    /// Creates one synthetic stored-reference syntax result that matches the supplied frame layout.
    ///
    /// @param sequenceHeader the sequence header associated with the stored frame
    /// @param frameHeader the frame header whose tile layout should be exposed
    /// @return one synthetic structural decode result whose tile count matches the supplied layout
    private static FrameSyntaxDecodeResult createSyntheticStoredReferenceSyntaxResult(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) {
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        int tileCount = assembly.totalTiles();
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] temporalMotionFields = new TileDecodeContext.TemporalMotionField[tileCount];
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int columns = tiling.columns();
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        for (int i = 0; i < tileCount; i++) {
            tileRoots[i] = new TilePartitionTreeReader.Node[0];
            int tileRow = i / columns;
            int tileColumn = i % columns;
            int startX = tiling.columnStartSuperblocks()[tileColumn] * superblockSize;
            int endX = Math.min(frameHeader.frameSize().codedWidth(), tiling.columnStartSuperblocks()[tileColumn + 1] * superblockSize);
            int startY = tiling.rowStartSuperblocks()[tileRow] * superblockSize;
            int endY = Math.min(frameHeader.frameSize().height(), tiling.rowStartSuperblocks()[tileRow + 1] * superblockSize);
            int width8 = (endX - startX + 7) >> 3;
            int height8 = (endY - startY + 7) >> 3;
            temporalMotionFields[i] = new TileDecodeContext.TemporalMotionField(width8, height8);
        }
        return new FrameSyntaxDecodeResult(assembly, tileRoots, temporalMotionFields);
    }

    /// Copies one full-sequence-header fixture while replacing only the exposed chroma layout.
    ///
    /// @param baseSequenceHeader the full sequence header to copy
    /// @param pixelFormat the replacement chroma layout
    /// @return one copied full sequence header with the requested chroma layout
    private static SequenceHeader copySequenceHeaderWithPixelFormat(
            SequenceHeader baseSequenceHeader,
            PixelFormat pixelFormat
    ) {
        return copySequenceHeaderWithPixelFormatAndBitDepth(
                baseSequenceHeader,
                pixelFormat,
                baseSequenceHeader.colorConfig().bitDepth()
        );
    }

    /// Copies one full-sequence-header fixture while replacing the exposed chroma layout and
    /// decoded bit depth.
    ///
    /// @param baseSequenceHeader the full sequence header to copy
    /// @param pixelFormat the replacement chroma layout
    /// @param bitDepth the replacement decoded bit depth
    /// @return one copied full sequence header with the requested chroma layout and bit depth
    private static SequenceHeader copySequenceHeaderWithPixelFormatAndBitDepth(
            SequenceHeader baseSequenceHeader,
            PixelFormat pixelFormat,
            int bitDepth
    ) {
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported synthetic stored reference bit depth: " + bitDepth);
        }
        SequenceHeader.ColorConfig baseColorConfig = baseSequenceHeader.colorConfig();
        int profile = switch (pixelFormat) {
            case I422 -> 2;
            case I444 -> bitDepth == 12 ? 2 : 1;
            case I400, I420 -> throw new IllegalArgumentException(
                    "Synthetic stored reference helper expects I422 or I444: " + pixelFormat
            );
        };
        boolean chromaSubsamplingX = switch (pixelFormat) {
            case I422 -> true;
            case I444 -> false;
            case I400, I420 -> throw new IllegalArgumentException(
                    "Synthetic stored reference helper expects I422 or I444: " + pixelFormat
            );
        };
        boolean chromaSubsamplingY = switch (pixelFormat) {
            case I422, I444 -> false;
            case I400, I420 -> throw new IllegalArgumentException(
                    "Synthetic stored reference helper expects I422 or I444: " + pixelFormat
            );
        };

        return new SequenceHeader(
                profile,
                baseSequenceHeader.maxWidth(),
                baseSequenceHeader.maxHeight(),
                baseSequenceHeader.timingInfo(),
                baseSequenceHeader.operatingPoints(),
                baseSequenceHeader.stillPicture(),
                baseSequenceHeader.reducedStillPictureHeader(),
                baseSequenceHeader.widthBits(),
                baseSequenceHeader.heightBits(),
                baseSequenceHeader.frameIdNumbersPresent(),
                baseSequenceHeader.deltaFrameIdBits(),
                baseSequenceHeader.frameIdBits(),
                baseSequenceHeader.features(),
                new SequenceHeader.ColorConfig(
                        bitDepth,
                        baseColorConfig.monochrome(),
                        baseColorConfig.colorDescriptionPresent(),
                        baseColorConfig.colorPrimaries(),
                        baseColorConfig.transferCharacteristics(),
                        baseColorConfig.matrixCoefficients(),
                        baseColorConfig.colorRange(),
                        pixelFormat,
                        baseColorConfig.chromaSamplePosition(),
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        baseColorConfig.separateUvDeltaQ()
                )
        );
    }

    /// Creates one neutral-gray decoded-plane snapshot for a synthetic stored reference surface.
    ///
    /// @param frameHeader the frame header whose coded and render dimensions should be used
    /// @param pixelFormat the chroma layout to expose
    /// @return one neutral-gray decoded-plane snapshot for the requested chroma layout
    private static DecodedPlanes createNeutralGrayDecodedPlanes(FrameHeader frameHeader, PixelFormat pixelFormat) {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        int chromaWidth = switch (pixelFormat) {
            case I422 -> (frameSize.codedWidth() + 1) / 2;
            case I444 -> frameSize.codedWidth();
            case I400, I420 -> throw new IllegalArgumentException(
                    "Synthetic stored reference helper expects I422 or I444: " + pixelFormat
            );
        };
        int chromaHeight = switch (pixelFormat) {
            case I422, I444 -> frameSize.height();
            case I400, I420 -> throw new IllegalArgumentException(
                    "Synthetic stored reference helper expects I422 or I444: " + pixelFormat
            );
        };

        return new DecodedPlanes(
                8,
                pixelFormat,
                frameSize.codedWidth(),
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                createFilledPlane(frameSize.codedWidth(), frameSize.height(), 128),
                createFilledPlane(chromaWidth, chromaHeight, 128),
                createFilledPlane(chromaWidth, chromaHeight, 128)
        );
    }

    /// Creates one decoded-plane snapshot filled with one constant sample value in the requested
    /// bit depth and chroma layout.
    ///
    /// @param frameHeader the frame header whose coded and render dimensions should be used
    /// @param pixelFormat the chroma layout to expose
    /// @param bitDepth the requested decoded bit depth
    /// @param sampleValue the constant unsigned sample value to store
    /// @return one decoded-plane snapshot filled with the requested sample value
    private static DecodedPlanes createFilledDecodedPlanes(
            FrameHeader frameHeader,
            PixelFormat pixelFormat,
            int bitDepth,
            int sampleValue
    ) {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        int chromaWidth = switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (frameSize.codedWidth() + 1) / 2;
            case I444 -> frameSize.codedWidth();
        };
        int chromaHeight = switch (pixelFormat) {
            case I400 -> 0;
            case I420 -> (frameSize.height() + 1) / 2;
            case I422, I444 -> frameSize.height();
        };
        return new DecodedPlanes(
                bitDepth,
                pixelFormat,
                frameSize.codedWidth(),
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                createFilledPlane(frameSize.codedWidth(), frameSize.height(), sampleValue),
                pixelFormat == PixelFormat.I400 ? null : createFilledPlane(chromaWidth, chromaHeight, sampleValue),
                pixelFormat == PixelFormat.I400 ? null : createFilledPlane(chromaWidth, chromaHeight, sampleValue)
        );
    }

    /// Creates one exact `I420` gradient surface for the injected stored inter reference slot.
    ///
    /// @param frameHeader the frame header whose coded and render dimensions should be used
    /// @return one exact `I420` gradient surface for the injected stored inter reference slot
    private static DecodedPlanes createGradientInterReferenceDecodedPlanes(FrameHeader frameHeader) {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        int chromaWidth = (frameSize.codedWidth() + 1) / 2;
        int chromaHeight = (frameSize.height() + 1) / 2;
        return new DecodedPlanes(
                8,
                PixelFormat.I420,
                frameSize.codedWidth(),
                frameSize.height(),
                frameSize.renderWidth(),
                frameSize.renderHeight(),
                createGradientPlane(frameSize.codedWidth(), frameSize.height(), 16, 1, 2),
                createGradientPlane(chromaWidth, chromaHeight, 64, 2, 3),
                createGradientPlane(chromaWidth, chromaHeight, 160, 1, 2)
        );
    }

    /// Creates one decoded plane filled with one constant sample value.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param sampleValue the constant unsigned sample value to store
    /// @return one decoded plane filled with the requested sample value
    private static DecodedPlane createFilledPlane(int width, int height, int sampleValue) {
        short[] samples = new short[width * height];
        Arrays.fill(samples, (short) sampleValue);
        return new DecodedPlane(width, height, width, samples);
    }

    /// Creates one decoded plane filled with one exact integer gradient.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param baseValue the sample value at `(0, 0)`
    /// @param xStep the per-column delta
    /// @param yStep the per-row delta
    /// @return one decoded plane filled with one exact integer gradient
    private static DecodedPlane createGradientPlane(int width, int height, int baseValue, int xStep, int yStep) {
        short[] samples = new short[width * height];
        int nextIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[nextIndex++] = (short) (baseValue + x * xStep + y * yStep);
            }
        }
        return new DecodedPlane(width, height, width, samples);
    }

    /// Copies one real supported still-picture frame header while replacing only its normalized film
    /// grain state.
    ///
    /// @param baseFrameHeader the real supported still-picture frame header to copy
    /// @param filmGrain the replacement normalized film grain state
    /// @return one copied frame header whose film grain state matches the requested value
    private static FrameHeader copyFrameHeaderWithFilmGrain(
            FrameHeader baseFrameHeader,
            FrameHeader.FilmGrainParams filmGrain
    ) {
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
                baseFrameHeader.tiling(),
                baseFrameHeader.quantization(),
                baseFrameHeader.segmentation(),
                baseFrameHeader.delta(),
                baseFrameHeader.allLossless(),
                baseFrameHeader.loopFilter(),
                baseFrameHeader.cdef(),
                baseFrameHeader.restoration(),
                baseFrameHeader.transformMode(),
                baseFrameHeader.reducedTransformSet(),
                filmGrain
        );
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

    /// Copies one real supported still-picture frame header while replacing only its frame type and
    /// refresh flags.
    ///
    /// @param baseFrameHeader the real supported still-picture frame header to copy
    /// @param frameType the replacement frame type
    /// @param refreshFrameFlags the replacement refresh flags
    /// @return one copied frame header whose frame type and refresh flags match the requested values
    private static FrameHeader copyFrameHeaderWithFrameTypeAndRefreshFlags(
            FrameHeader baseFrameHeader,
            FrameType frameType,
            int refreshFrameFlags
    ) {
        return new FrameHeader(
                baseFrameHeader.temporalId(),
                baseFrameHeader.spatialId(),
                baseFrameHeader.showExistingFrame(),
                baseFrameHeader.existingFrameIndex(),
                baseFrameHeader.frameId(),
                baseFrameHeader.framePresentationDelay(),
                frameType,
                baseFrameHeader.showFrame(),
                baseFrameHeader.showableFrame(),
                baseFrameHeader.errorResilientMode(),
                baseFrameHeader.disableCdfUpdate(),
                baseFrameHeader.allowScreenContentTools(),
                baseFrameHeader.forceIntegerMotionVectors(),
                baseFrameHeader.frameSizeOverride(),
                baseFrameHeader.primaryRefFrame(),
                baseFrameHeader.frameOffset(),
                refreshFrameFlags,
                baseFrameHeader.frameSize(),
                baseFrameHeader.superResolution(),
                baseFrameHeader.allowIntrabc(),
                baseFrameHeader.refreshContext(),
                baseFrameHeader.tiling(),
                baseFrameHeader.quantization(),
                baseFrameHeader.segmentation(),
                baseFrameHeader.delta(),
                baseFrameHeader.allLossless(),
                baseFrameHeader.loopFilter(),
                baseFrameHeader.cdef(),
                baseFrameHeader.restoration(),
                baseFrameHeader.transformMode(),
                baseFrameHeader.reducedTransformSet(),
                baseFrameHeader.filmGrain()
        );
    }

    /// Copies one real supported still-picture frame header while replacing its frame type and
    /// normalized super-resolution geometry.
    ///
    /// @param baseFrameHeader the real supported still-picture frame header to copy
    /// @param frameType the replacement inter-like frame type
    /// @param codedWidth the coded width before horizontal super-resolution
    /// @param upscaledWidth the stored/output width after horizontal super-resolution
    /// @param height the coded and rendered frame height
    /// @return one copied frame header whose frame type and super-resolution state match the request
    private static FrameHeader copyFrameHeaderWithFrameTypeAndSuperResolution(
            FrameHeader baseFrameHeader,
            FrameType frameType,
            int codedWidth,
            int upscaledWidth,
            int height
    ) {
        return new FrameHeader(
                baseFrameHeader.temporalId(),
                baseFrameHeader.spatialId(),
                baseFrameHeader.showExistingFrame(),
                baseFrameHeader.existingFrameIndex(),
                baseFrameHeader.frameId(),
                baseFrameHeader.framePresentationDelay(),
                frameType,
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
                new FrameHeader.FrameSize(codedWidth, upscaledWidth, height, upscaledWidth, height),
                new FrameHeader.SuperResolutionInfo(true, 9),
                baseFrameHeader.allowIntrabc(),
                baseFrameHeader.refreshContext(),
                baseFrameHeader.tiling(),
                baseFrameHeader.quantization(),
                baseFrameHeader.segmentation(),
                baseFrameHeader.delta(),
                baseFrameHeader.allLossless(),
                baseFrameHeader.loopFilter(),
                baseFrameHeader.cdef(),
                baseFrameHeader.restoration(),
                baseFrameHeader.transformMode(),
                baseFrameHeader.reducedTransformSet(),
                baseFrameHeader.filmGrain()
        );
    }

    /// Injects one synthetic `show_existing_frame` state into a fresh reader.
    ///
    /// @param reader the fresh reader that should expose the injected state
    /// @param referenceState the reference state to inject into slot `0`
    private static void injectShowExistingReferenceState(
            Av1ImageReader reader,
            InjectedReferenceState referenceState
    ) {
        reader.injectReferenceStateForTest(
                0,
                referenceState.sequenceHeader(),
                referenceState.frameHeader(),
                referenceState.syntaxResult(),
                referenceState.referenceSurfaceSnapshot()
        );
    }

    /// Parses the test-only full sequence header that enables `show_existing_frame`.
    ///
    /// @return the parsed test-only full sequence header that enables `show_existing_frame`
    private static SequenceHeader parseFullSequenceHeader() throws IOException {
        return parseFullSequenceHeader(PixelFormat.I420);
    }

    /// Parses the test-only full sequence header that enables `show_existing_frame` while exposing
    /// the requested public chroma layout.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @return the parsed test-only full sequence header that enables `show_existing_frame`
    private static SequenceHeader parseFullSequenceHeader(PixelFormat pixelFormat) throws IOException {
        return new SequenceHeaderParser().parse(
                new ObuPacket(
                        new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0),
                        fullSequenceHeaderPayload(pixelFormat),
                        0,
                        0
                ),
                false
        );
    }

    /// Asserts that the reader returned one supported opaque gray still-picture frame.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertOpaqueGrayStillPictureFrame(@org.jetbrains.annotations.Nullable DecodedFrame decodedFrame, long expectedPresentationIndex) {
        assertOpaqueGrayStillPictureFrame(decodedFrame, PixelFormat.I420, expectedPresentationIndex);
    }

    /// Asserts that the reader returned one supported opaque gray still-picture frame with the
    /// requested public chroma layout.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPixelFormat the expected chroma layout exposed by the public frame
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertOpaqueGrayStillPictureFrame(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            PixelFormat expectedPixelFormat,
            long expectedPresentationIndex
    ) {
        assertStillPictureFrameFilledWith(decodedFrame, expectedPixelFormat, expectedPresentationIndex, OPAQUE_MID_GRAY);
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
        assertDecodedStillPictureFrameMetadata(frame, PixelFormat.I420, expectedPresentationIndex);
        assertArgbBlockEquals(frame, 0, 0, LEGACY_DIRECTIONAL_ARGB_TOP_LEFT_8X8);
    }

    /// Asserts that one decoded still-picture frame exactly matches one stored reconstructed
    /// reference surface exposed through the public `show_existing_frame` path.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param referenceSurfaceSnapshot the stored reconstructed reference surface that should be exposed
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertStillPictureFrameMatchesReferenceSurface(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot,
            long expectedPresentationIndex
    ) {
        assertNotNull(decodedFrame);
        DecodedPlanes decodedPlanes = referenceSurfaceSnapshot.decodedPlanes();
        assertDecodedStillPictureFrameMetadata(
                decodedFrame,
                decodedPlanes.bitDepth(),
                decodedPlanes.pixelFormat(),
                referenceSurfaceSnapshot.frameHeader().frameType(),
                expectedPresentationIndex
        );
        if (decodedPlanes.bitDepth() == 8) {
            assertTrue(decodedFrame instanceof ArgbIntFrame);
            assertArrayEquals(ArgbOutput.toOpaqueArgbPixels(decodedPlanes), ((ArgbIntFrame) decodedFrame).pixels());
        } else {
            assertTrue(decodedFrame instanceof ArgbLongFrame);
            assertArrayEquals(ArgbOutput.toOpaqueArgbLongPixels(decodedPlanes), ((ArgbLongFrame) decodedFrame).pixels());
        }
    }

    /// Asserts that one decoded still-picture frame exactly matches one stored reconstructed
    /// reference surface after deterministic film-grain synthesis.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param referenceSurfaceSnapshot the stored reconstructed reference surface that should be grain-applied
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertStillPictureFrameMatchesGrainAppliedReferenceSurface(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot,
            long expectedPresentationIndex
    ) {
        assertNotNull(decodedFrame);
        DecodedPlanes synthesizedPlanes = new FilmGrainSynthesizer().apply(
                referenceSurfaceSnapshot.decodedPlanes(),
                referenceSurfaceSnapshot.frameHeader()
        );
        assertDecodedStillPictureFrameMetadata(
                decodedFrame,
                synthesizedPlanes.bitDepth(),
                synthesizedPlanes.pixelFormat(),
                referenceSurfaceSnapshot.frameHeader().frameType(),
                expectedPresentationIndex
        );
        if (synthesizedPlanes.bitDepth() == 8) {
            assertTrue(decodedFrame instanceof ArgbIntFrame);
            assertArrayEquals(ArgbOutput.toOpaqueArgbPixels(synthesizedPlanes), ((ArgbIntFrame) decodedFrame).pixels());
        } else {
            assertTrue(decodedFrame instanceof ArgbLongFrame);
            assertArrayEquals(ArgbOutput.toOpaqueArgbLongPixels(synthesizedPlanes), ((ArgbLongFrame) decodedFrame).pixels());
        }
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
        assertStillPictureFrameFilledWith(decodedFrame, PixelFormat.I420, expectedPresentationIndex, expectedPixel);
    }

    /// Asserts that the reader returned one still-picture frame filled with one constant pixel and
    /// exposing the requested public chroma layout.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPixelFormat the expected chroma layout exposed by the public frame
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    /// @param expectedPixel the expected constant packed ARGB pixel value
    private static void assertStillPictureFrameFilledWith(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            PixelFormat expectedPixelFormat,
            long expectedPresentationIndex,
            int expectedPixel
    ) {
        assertNotNull(decodedFrame);
        assertTrue(decodedFrame instanceof ArgbIntFrame);
        ArgbIntFrame frame = (ArgbIntFrame) decodedFrame;
        assertDecodedStillPictureFrameMetadata(frame, expectedPixelFormat, expectedPresentationIndex);

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

    /// Asserts the stable decoded-frame metadata shared by the current `64x64` still-picture fixtures.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedPixelFormat the expected chroma layout exposed by the public frame
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertDecodedStillPictureFrameMetadata(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            PixelFormat expectedPixelFormat,
            long expectedPresentationIndex
    ) {
        assertDecodedStillPictureFrameMetadata(
                decodedFrame,
                8,
                expectedPixelFormat,
                FrameType.KEY,
                expectedPresentationIndex
        );
    }

    /// Asserts the stable decoded-frame metadata shared by the current `64x64` still-picture
    /// fixtures for one explicit bit depth and frame type.
    ///
    /// @param decodedFrame the decoded frame returned by the public reader
    /// @param expectedBitDepth the expected decoded bit depth exposed by the public frame
    /// @param expectedPixelFormat the expected chroma layout exposed by the public frame
    /// @param expectedFrameType the expected AV1 frame type exposed by the public frame
    /// @param expectedPresentationIndex the zero-based presentation index expected for the frame
    private static void assertDecodedStillPictureFrameMetadata(
            @org.jetbrains.annotations.Nullable DecodedFrame decodedFrame,
            int expectedBitDepth,
            PixelFormat expectedPixelFormat,
            FrameType expectedFrameType,
            long expectedPresentationIndex
    ) {
        assertNotNull(decodedFrame);
        assertEquals(64, decodedFrame.width());
        assertEquals(64, decodedFrame.height());
        assertEquals(expectedBitDepth, decodedFrame.bitDepth());
        assertEquals(expectedPixelFormat, decodedFrame.pixelFormat());
        assertEquals(expectedFrameType, decodedFrame.frameType());
        assertTrue(decodedFrame.visible());
        assertEquals(expectedPresentationIndex, decodedFrame.presentationIndex());
    }

    /// Returns the first raster-order leaf whose decoded block header carries luma or chroma palette state.
    ///
    /// @param syntaxResult the structural decode result produced by the public reader
    /// @return the first raster-order leaf whose decoded block header carries palette state
    private static TilePartitionTreeReader.LeafNode requireFirstPaletteLeaf(FrameSyntaxDecodeResult syntaxResult) {
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxResult.tileRoots(0));
        for (TilePartitionTreeReader.LeafNode leaf : leaves) {
            if (leaf.header().yPaletteSize() > 0 || leaf.header().uvPaletteSize() > 0) {
                assertTrue(leaf.header().yPaletteSize() > 0);
                assertTrue(leaf.header().uvPaletteSize() > 0);
                return leaf;
            }
        }
        throw new AssertionError("No palette leaf decoded from the real bitstream-derived fixture");
    }

    /// Asserts that the first raster-order decoded leaf is intra-coded.
    ///
    /// @param syntaxResult the structural decode result produced by the public reader
    private static void assertFirstDecodedLeafIsIntra(
            @org.jetbrains.annotations.Nullable FrameSyntaxDecodeResult syntaxResult
    ) {
        assertNotNull(syntaxResult);
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxResult.tileRoots(0));
        assertFalse(leaves.isEmpty());
        assertTrue(leaves.get(0).header().intra());
    }

    /// Asserts that the first raster-order decoded leaf is inter-coded against `LAST_FRAME`.
    ///
    /// @param syntaxResult the structural decode result produced by the public reader
    private static void assertFirstDecodedLeafIsInter(
            @org.jetbrains.annotations.Nullable FrameSyntaxDecodeResult syntaxResult
    ) {
        assertNotNull(syntaxResult);
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxResult.tileRoots(0));
        assertFalse(leaves.isEmpty());
        TilePartitionTreeReader.LeafNode firstLeaf = leaves.get(0);
        assertFalse(firstLeaf.header().skip());
        assertFalse(firstLeaf.header().intra());
        assertFalse(firstLeaf.header().compoundReference());
        assertEquals(0, firstLeaf.header().referenceFrame0());
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
        return reducedStillPicturePayload(PixelFormat.I420);
    }

    /// Creates a reduced still-picture sequence header payload for one requested public chroma
    /// layout.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload(PixelFormat pixelFormat) {
        BitWriter writer = new BitWriter();
        writer.writeBits(reducedStillPictureProfile(pixelFormat), 3);
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
        writeReducedStillPictureColorConfig(writer, pixelFormat, false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Returns the AV1 sequence-profile value used by the reduced still-picture fixture for the
    /// requested public chroma layout.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @return the reduced still-picture sequence-profile value
    private static int reducedStillPictureProfile(PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I420 -> 0;
            case I422 -> 2;
            case I444 -> 1;
            case I400 -> throw new IllegalArgumentException(
                    "Reduced still-picture fixture expects I420, I422, or I444: " + pixelFormat
            );
        };
    }

    /// Writes the reduced still-picture color-configuration bits for the requested public chroma
    /// layout and one explicit film grain capability flag.
    ///
    /// @param writer the destination bit writer
    /// @param pixelFormat the requested public chroma layout
    /// @param filmGrainPresent whether the sequence header should advertise film grain support
    private static void writeReducedStillPictureColorConfig(
            BitWriter writer,
            PixelFormat pixelFormat,
            boolean filmGrainPresent
    ) {
        switch (pixelFormat) {
            case I420 -> {
                writer.writeFlag(false);
                writer.writeFlag(false);
                writer.writeFlag(false);
                writer.writeFlag(true);
                writer.writeBits(1, 2);
                writer.writeFlag(true);
                writer.writeFlag(filmGrainPresent);
            }
            case I422 -> {
                writer.writeFlag(false);
                writer.writeFlag(false);
                writer.writeFlag(false);
                writer.writeFlag(true);
                writer.writeFlag(true);
                writer.writeFlag(filmGrainPresent);
            }
            case I444 -> {
                writer.writeFlag(false);
                writer.writeFlag(false);
                writer.writeFlag(true);
                writer.writeFlag(true);
                writer.writeFlag(filmGrainPresent);
            }
            case I400 -> throw new IllegalArgumentException(
                    "Reduced still-picture fixture expects I420, I422, or I444: " + pixelFormat
            );
        }
    }

    /// Creates one non-reduced still-picture-compatible sequence header payload that enables
    /// `show_existing_frame` while keeping the current `64x64` `8-bit I420` fixture geometry.
    ///
    /// @return one non-reduced still-picture-compatible sequence header payload
    private static byte[] fullSequenceHeaderPayload() {
        return fullSequenceHeaderPayload(PixelFormat.I420);
    }

    /// Creates one non-reduced still-picture-compatible sequence header payload that enables
    /// `show_existing_frame` while exposing the requested `8-bit` public chroma layout.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @return one non-reduced still-picture-compatible sequence header payload
    private static byte[] fullSequenceHeaderPayload(PixelFormat pixelFormat) {
        return fullSequenceHeaderPayload(pixelFormat, false);
    }

    /// Creates one non-reduced still-picture-compatible sequence header payload that enables
    /// `show_existing_frame` while exposing the requested `8-bit` public chroma layout and
    /// caller-selected film grain capability.
    ///
    /// @param pixelFormat the requested public chroma layout
    /// @param filmGrainPresent whether frame headers in the stream may signal film grain
    /// @return one non-reduced still-picture-compatible sequence header payload
    private static byte[] fullSequenceHeaderPayload(PixelFormat pixelFormat, boolean filmGrainPresent) {
        BitWriter writer = new BitWriter();
        writer.writeBits(reducedStillPictureProfile(pixelFormat), 3);
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
        writeReducedStillPictureColorConfig(writer, pixelFormat, filmGrainPresent);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }


    /// Creates one non-reduced still-picture-compatible `I420` sequence header payload that
    /// enables both `show_existing_frame` and explicit film grain signaling.
    ///
    /// @return one non-reduced still-picture-compatible `I420` sequence header payload with film grain enabled
    private static byte[] fullSequenceHeaderPayloadForFilmGrainStillPicture() {
        BitWriter writer = new BitWriter();
        writer.writeBits(reducedStillPictureProfile(PixelFormat.I420), 3);
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
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
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

    /// Creates one standalone inter frame header payload compatible with the current deterministic
    /// real inter tile fixture.
    ///
    /// @return one standalone inter frame header payload
    private static byte[] interFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeInterFrameHeaderBits(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates one combined inter frame payload whose frame-header bits precede the caller-supplied
    /// single-tile payload.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the inter frame header bits
    /// @return one combined inter frame payload
    private static byte[] combinedInterFramePayload(byte[] tileGroupPayload) {
        BitWriter writer = new BitWriter();
        writeInterFrameHeaderBits(writer);
        writer.padToByteBoundary();
        writer.writeBytes(tileGroupPayload);
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

    /// Creates one non-reduced still-picture-compatible combined frame payload whose direct output
    /// frame carries explicit film grain.
    ///
    /// @param tileGroupPayload the tile-group payload appended after the frame header
    /// @param filmGrain the normalized film grain state to encode in the frame header
    /// @return one non-reduced still-picture-compatible combined frame payload with film grain
    private static byte[] fullStillPictureCombinedFramePayloadWithFilmGrain(
            byte[] tileGroupPayload,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        BitWriter writer = new BitWriter();
        writeFullStillPictureFrameHeaderBitsBeforeFilmGrain(writer);
        writeSupportedStillPictureFilmGrainBits(writer, filmGrain);
        writer.padToByteBoundary();
        writer.writeBytes(tileGroupPayload);
        return writer.toByteArray();
    }

    /// Creates one supported still-picture stream whose direct combined-frame output carries one
    /// deterministic visible film grain configuration.
    ///
    /// @return one supported still-picture stream with deterministic visible film grain
    private static byte[] supportedStillPictureFilmGrainDirectStream() {
        return concat(
                obu(1, fullSequenceHeaderPayloadForFilmGrainStillPicture()),
                obu(6, fullStillPictureCombinedFramePayloadWithFilmGrain(
                        SUPPORTED_SINGLE_TILE_PAYLOAD,
                        minimalFilmGrainParams(0x1234)
                ))
        );
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

    /// Writes one inter frame-header syntax block without standalone trailing bits.
    ///
    /// This matches the deterministic standalone inter header used by parser and integration tests
    /// while keeping the first decoded block compatible with the current real `all-zero-8` tile
    /// fixture.
    ///
    /// @param writer the destination bit writer
    private static void writeInterFrameHeaderBits(BitWriter writer) {
        writer.writeFlag(false);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(10, 4);
        writer.writeBits(7, 3);
        writer.writeBits(0x12, 8);
        writer.writeFlag(false);
        writer.writeBits(0, 3);
        writer.writeBits(1, 3);
        writer.writeBits(2, 3);
        writer.writeBits(3, 3);
        writer.writeBits(4, 3);
        writer.writeBits(5, 3);
        writer.writeBits(6, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
    }

    /// Writes one non-reduced still-picture-compatible key frame header syntax without standalone
    /// trailing bits.
    ///
    /// @param writer the destination bit writer
    private static void writeFullStillPictureFrameHeaderBits(BitWriter writer) {
        writeFullStillPictureFrameHeaderBitsBeforeFilmGrain(writer);
        writer.writeFlag(false);
    }

    /// Writes one non-reduced still-picture-compatible key frame header syntax up to but not
    /// including the final `apply_grain` flag.
    ///
    /// @param writer the destination bit writer
    private static void writeFullStillPictureFrameHeaderBitsBeforeFilmGrain(BitWriter writer) {
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
    }

    /// Writes one explicit still-picture film grain syntax block compatible with the current
    /// `8-bit I420` combined-frame fixture.
    ///
    /// @param writer the destination bit writer
    /// @param filmGrain the normalized film grain state to encode
    private static void writeSupportedStillPictureFilmGrainBits(
            BitWriter writer,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        writer.writeFlag(filmGrain.applyGrain());
        if (!filmGrain.applyGrain()) {
            return;
        }
        if (!filmGrain.updated()) {
            throw new IllegalArgumentException("Still-picture film grain fixture requires one explicit updated parameter set");
        }

        writer.writeBits(filmGrain.grainSeed(), 16);

        FrameHeader.FilmGrainPoint[] yPoints = filmGrain.yPoints();
        writer.writeBits(yPoints.length, 4);
        writeFilmGrainPoints(writer, yPoints);
        writer.writeFlag(filmGrain.chromaScalingFromLuma());
        if (!filmGrain.chromaScalingFromLuma() && yPoints.length > 0) {
            FrameHeader.FilmGrainPoint[] cbPoints = filmGrain.cbPoints();
            FrameHeader.FilmGrainPoint[] crPoints = filmGrain.crPoints();
            writer.writeBits(cbPoints.length, 4);
            writeFilmGrainPoints(writer, cbPoints);
            writer.writeBits(crPoints.length, 4);
            writeFilmGrainPoints(writer, crPoints);
        }

        writer.writeBits(filmGrain.scalingShift() - 8, 2);
        writer.writeBits(filmGrain.arCoeffLag(), 2);
        writeFilmGrainCoefficients(writer, filmGrain.arCoefficientsY());
        if (filmGrain.chromaScalingFromLuma() || filmGrain.cbPoints().length > 0) {
            writeFilmGrainCoefficients(writer, filmGrain.arCoefficientsCb());
        }
        if (filmGrain.chromaScalingFromLuma() || filmGrain.crPoints().length > 0) {
            writeFilmGrainCoefficients(writer, filmGrain.arCoefficientsCr());
        }
        writer.writeBits(filmGrain.arCoeffShift() - 6, 2);
        writer.writeBits(filmGrain.grainScaleShift(), 2);
        if (filmGrain.cbPoints().length > 0) {
            writer.writeBits(filmGrain.cbMult() + 128L, 8);
            writer.writeBits(filmGrain.cbLumaMult() + 128L, 8);
            writer.writeBits(filmGrain.cbOffset() + 256L, 9);
        }
        if (filmGrain.crPoints().length > 0) {
            writer.writeBits(filmGrain.crMult() + 128L, 8);
            writer.writeBits(filmGrain.crLumaMult() + 128L, 8);
            writer.writeBits(filmGrain.crOffset() + 256L, 9);
        }
        writer.writeFlag(filmGrain.overlapEnabled());
        writer.writeFlag(filmGrain.clipToRestrictedRange());
    }

    /// Writes one normalized film grain scaling-point array.
    ///
    /// @param writer the destination bit writer
    /// @param points the normalized film grain scaling points to encode
    private static void writeFilmGrainPoints(BitWriter writer, FrameHeader.FilmGrainPoint[] points) {
        for (FrameHeader.FilmGrainPoint point : points) {
            writer.writeBits(point.value(), 8);
            writer.writeBits(point.scaling(), 8);
        }
    }

    /// Writes one normalized film grain coefficient array with the AV1 `+128` storage bias
    /// restored.
    ///
    /// @param writer the destination bit writer
    /// @param coefficients the normalized coefficient array to encode
    private static void writeFilmGrainCoefficients(BitWriter writer, int[] coefficients) {
        for (int coefficient : coefficients) {
            writer.writeBits(coefficient + 128L, 8);
        }
    }

    /// Creates one minimal normalized film grain state that matches
    /// the current output-path contract tests.
    ///
    /// @param grainSeed the pseudo-random seed stored in the normalized state
    /// @return one minimal normalized film grain state
    private static FrameHeader.FilmGrainParams minimalFilmGrainParams(int grainSeed) {
        return new FrameHeader.FilmGrainParams(
                true,
                grainSeed,
                true,
                -1,
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(0, 24),
                        new FrameHeader.FilmGrainPoint(255, 96)
                },
                true,
                new FrameHeader.FilmGrainPoint[0],
                new FrameHeader.FilmGrainPoint[0],
                8,
                0,
                new int[0],
                new int[]{0},
                new int[]{0},
                6,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false
        );
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
