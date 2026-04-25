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

import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuStreamReader;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecoder;
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.parse.FrameHeaderParser;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.parse.TileBitstreamParser;
import org.glavo.avif.internal.av1.parse.TileGroupHeaderParser;
import org.glavo.avif.internal.av1.postfilter.FilmGrainSynthesizer;
import org.glavo.avif.internal.av1.postfilter.FramePostprocessor;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.glavo.avif.internal.av1.runtime.FrameOutputPolicy;
import org.glavo.avif.internal.av1.runtime.OutputFrameFactory;
import org.glavo.avif.internal.av1.runtime.RuntimeReferenceSlot;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// High-level sequential reader for raw AV1 OBU streams.
@NotNullByDefault
public final class Av1ImageReader implements AutoCloseable {
    /// The forward-only buffered byte source.
    private final BufferedInput source;
    /// The immutable decoder configuration.
    private final Av1DecoderConfig config;
    /// The sequential OBU reader used by this image reader.
    private final ObuStreamReader obuReader;
    /// The parser used for sequence header OBUs.
    private final SequenceHeaderParser sequenceHeaderParser;
    /// The parser used for standalone frame header OBUs.
    private final FrameHeaderParser frameHeaderParser;
    /// The parser used for tile-group headers.
    private final TileGroupHeaderParser tileGroupHeaderParser;
    /// The parser used for per-tile bitstream views inside tile groups.
    private final TileBitstreamParser tileBitstreamParser;
    /// The most recently parsed sequence header.
    private @Nullable SequenceHeader sequenceHeader;
    /// The runtime reference slots used for syntax inheritance and stored-surface reuse.
    private final RuntimeReferenceSlot[] referenceSlots;
    /// The currently assembled frame when tile groups span multiple OBUs.
    private @Nullable FrameAssembly pendingFrameAssembly;
    /// The most recently completed structural frame-decode result.
    private @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult;
    /// The minimal pixel reconstructor used by the first output-producing path.
    private final FrameReconstructor frameReconstructor;
    /// The postfilter pipeline used before storing reference surfaces.
    private final FramePostprocessor framePostprocessor;
    /// The deterministic film-grain synthesizer used only at presentation time.
    private final FilmGrainSynthesizer filmGrainSynthesizer;
    /// The zero-based presentation index assigned to the next returned frame.
    private long nextPresentationIndex;
    /// Whether this reader has already been closed.
    private boolean closed;

    /// Creates a sequential image reader.
    ///
    /// @param source the forward-only buffered byte source
    /// @param config the immutable decoder configuration
    private Av1ImageReader(BufferedInput source, Av1DecoderConfig config) {
        this.source = Objects.requireNonNull(source, "source");
        this.config = Objects.requireNonNull(config, "config");
        this.obuReader = new ObuStreamReader(source);
        this.sequenceHeaderParser = new SequenceHeaderParser();
        this.frameHeaderParser = new FrameHeaderParser();
        this.tileGroupHeaderParser = new TileGroupHeaderParser();
        this.tileBitstreamParser = new TileBitstreamParser();
        this.referenceSlots = createReferenceSlots(8);
        this.frameReconstructor = new FrameReconstructor();
        this.framePostprocessor = new FramePostprocessor();
        this.filmGrainSynthesizer = new FilmGrainSynthesizer();
    }

    /// Opens an AV1 image reader using the default decoder configuration.
    ///
    /// @param source the forward-only buffered byte source
    /// @return the new AV1 image reader
    public static Av1ImageReader open(BufferedInput source) {
        return open(source, Av1DecoderConfig.DEFAULT);
    }

    /// Opens an AV1 image reader using the supplied decoder configuration.
    ///
    /// @param source the forward-only buffered byte source
    /// @param config the immutable decoder configuration
    /// @return the new AV1 image reader
    public static Av1ImageReader open(BufferedInput source, Av1DecoderConfig config) {
        return new Av1ImageReader(source, config);
    }

    /// Reads the next decoded frame from the source.
    ///
    /// @return the next decoded frame, or `null` at end-of-stream
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    public @Nullable DecodedFrame readFrame() throws IOException {
        ensureOpen();

        while (true) {
            ObuPacket packet = obuReader.readObu();
            if (packet == null) {
                if (pendingFrameAssembly != null) {
                    throw incompleteFrameAtEndOfStream(pendingFrameAssembly);
                }
                return null;
            }

            ObuType type = packet.header().type();
            if (type == ObuType.SEQUENCE_HEADER) {
                ensureNoPendingFrameAssembly(packet, "Sequence header OBU appeared before the current frame was completed");
                sequenceHeader = sequenceHeaderParser.parse(packet, config.strictStdCompliance());
                clearReferenceSlots();
                lastFrameSyntaxDecodeResult = null;
                continue;
            }
            if (type == ObuType.FRAME_HEADER) {
                DecodedFrame frame = startStandaloneFrameAssembly(packet);
                if (frame != null) {
                    return frame;
                }
                continue;
            }
            if (type == ObuType.FRAME) {
                CombinedFrameStart start = startCombinedFrameAssembly(packet);
                if (start.resolvedImmediately()) {
                    return start.immediateOutput();
                }
                FrameAssembly assembly = start.frameAssembly();
                if (assembly.isComplete()) {
                    pendingFrameAssembly = null;
                    DecodedFrame frame = completeFrameAssembly(assembly, packet);
                    if (frame != null) {
                        return frame;
                    }
                } else {
                    pendingFrameAssembly = assembly;
                }
                continue;
            }
            if (type == ObuType.TILE_GROUP) {
                FrameAssembly assembly = appendStandaloneTileGroup(packet);
                if (assembly.isComplete()) {
                    pendingFrameAssembly = null;
                    DecodedFrame frame = completeFrameAssembly(assembly, packet);
                    if (frame != null) {
                        return frame;
                    }
                }
                continue;
            }
        }
    }

    /// Reads all decoded frames from the source until end-of-stream.
    ///
    /// @return all decoded frames from the source
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    public @Unmodifiable List<DecodedFrame> readAllFrames() throws IOException {
        ensureOpen();

        List<DecodedFrame> frames = new ArrayList<>();
        while (true) {
            DecodedFrame frame = readFrame();
            if (frame == null) {
                return List.copyOf(frames);
            }
            frames.add(frame);
        }
    }

    /// Closes this reader and the underlying byte source.
    ///
    /// @throws IOException if the underlying source fails to close
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        source.close();
    }

    /// Returns the immutable decoder configuration for this reader.
    ///
    /// @return the immutable decoder configuration
    public Av1DecoderConfig config() {
        return config;
    }

    /// Returns the most recently completed structural frame-decode result, or `null`.
    ///
    /// This accessor exists for same-package tests while the public pixel output API is still
    /// incomplete.
    ///
    /// @return the most recently completed structural frame-decode result, or `null`
    @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult() {
        return lastFrameSyntaxDecodeResult;
    }

    /// Returns one refreshed reference-frame structural decode result, or `null`.
    ///
    /// This accessor exists for same-package tests while the public pixel output API is still
    /// incomplete.
    ///
    /// @param slot the zero-based reference-frame slot
    /// @return one refreshed reference-frame structural decode result, or `null`
    @Nullable FrameSyntaxDecodeResult referenceFrameSyntaxResult(int slot) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }
        return referenceSlots[slot].syntaxResult();
    }

    /// Returns one refreshed reference-frame header, or `null`.
    ///
    /// This accessor exists for same-package tests while the public output/runtime surface API is
    /// still intentionally narrow.
    ///
    /// @param slot the zero-based reference-frame slot
    /// @return one refreshed reference-frame header, or `null`
    @Nullable FrameHeader referenceFrameHeader(int slot) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }
        return referenceSlots[slot].frameHeader();
    }

    /// Returns one refreshed reference-surface snapshot, or `null`.
    ///
    /// This accessor exists for same-package tests while the public output/runtime surface API is
    /// still intentionally narrow.
    ///
    /// @param slot the zero-based reference-frame slot
    /// @return one refreshed reference-surface snapshot, or `null`
    @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot(int slot) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }
        return referenceSlots[slot].surfaceSnapshot();
    }

    /// Injects one full reference-slot state for same-package tests.
    ///
    /// This helper exists only so package-level tests can exercise public runtime behavior without
    /// reflecting into private slot storage.
    ///
    /// @param slot the zero-based reference slot to refresh
    /// @param sequenceHeader the active sequence header to associate with the injected slot
    /// @param frameHeader the stored frame header for the injected slot
    /// @param syntaxResult the stored structural syntax result for the injected slot
    /// @param referenceSurfaceSnapshot the stored reconstructed reference surface snapshot, or `null`
    void injectReferenceStateForTest(
            int slot,
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxResult,
            @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }

        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        RuntimeReferenceSlot referenceSlot = referenceSlots[slot];
        referenceSlot.clear();
        referenceSlot.refreshSyntax(
                Objects.requireNonNull(frameHeader, "frameHeader"),
                Objects.requireNonNull(syntaxResult, "syntaxResult")
        );
        if (referenceSurfaceSnapshot != null) {
            referenceSlot.refreshSurface(referenceSurfaceSnapshot);
        }
    }

    /// Ensures that this reader has not already been closed.
    ///
    /// @throws IOException if this reader has already been closed
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Av1ImageReader is closed");
        }
    }

    /// Starts a new frame assembly from a standalone frame-header OBU.
    ///
    /// @param packet the standalone frame-header OBU
    /// @throws IOException if the OBU is unreadable, malformed, or out of order
    private @Nullable DecodedFrame startStandaloneFrameAssembly(ObuPacket packet) throws IOException {
        SequenceHeader activeSequenceHeader = requireSequenceHeader(packet);
        ensureNoPendingFrameAssembly(packet, "Standalone frame header OBU appeared before the previous frame was completed");

        FrameHeader frameHeader = frameHeaderParser.parse(
                packet,
                activeSequenceHeader,
                config.strictStdCompliance(),
                referenceFrameHeadersForParsing()
        );
        enforceFrameSizeLimit(frameHeader, packet);
        if (frameHeader.showExistingFrame()) {
            return outputExistingFrame(packet, frameHeader.existingFrameIndex());
        }

        pendingFrameAssembly = new FrameAssembly(
                activeSequenceHeader,
                frameHeader,
                referenceFrameHeadersForParsing(),
                packet.streamOffset(),
                packet.obuIndex()
        );
        return null;
    }

    /// Starts a new frame assembly from a combined `FRAME` OBU.
    ///
    /// @param packet the combined frame OBU
    /// @return the started frame assembly, including the first tile group
    /// @throws IOException if the OBU is unreadable, malformed, or out of order
    private CombinedFrameStart startCombinedFrameAssembly(ObuPacket packet) throws IOException {
        SequenceHeader activeSequenceHeader = requireSequenceHeader(packet);
        ensureNoPendingFrameAssembly(packet, "Combined frame OBU appeared before the previous frame was completed");

        BitReader reader = new BitReader(packet.payload());
        FrameHeader frameHeader = frameHeaderParser.parseFramePayload(
                reader,
                packet,
                activeSequenceHeader,
                config.strictStdCompliance(),
                referenceFrameHeadersForParsing()
        );
        enforceFrameSizeLimit(frameHeader, packet);
        if (frameHeader.showExistingFrame()) {
            reader.byteAlign();
            if (reader.byteOffset() != packet.payload().length) {
                throw invalidBitstream(
                        packet,
                        "Combined frame OBU must not carry tile data when show_existing_frame is set"
                );
            }
            return CombinedFrameStart.immediateOutput(outputExistingFrame(packet, frameHeader.existingFrameIndex()));
        }

        FrameAssembly assembly = new FrameAssembly(
                activeSequenceHeader,
                frameHeader,
                referenceFrameHeadersForParsing(),
                packet.streamOffset(),
                packet.obuIndex()
        );
        reader.byteAlign();
        TileGroupHeader tileGroupHeader = tileGroupHeaderParser.parse(reader, packet, frameHeader);
        reader.byteAlign();
        appendTileGroup(assembly, packet, tileGroupHeader, reader.byteOffset());
        return CombinedFrameStart.frameAssembly(assembly);
    }

    /// Finalizes a completed frame assembly by refreshing reference slots and optionally returning
    /// one decoded public frame.
    ///
    /// @param assembly the completed frame assembly
    /// @param packet the OBU that completed the frame assembly
    /// @return the decoded public frame, or `null` when current output filtering suppresses it
    private @Nullable DecodedFrame completeFrameAssembly(FrameAssembly assembly, ObuPacket packet) throws DecodeException {
        FrameHeader frameHeader = assembly.frameHeader();
        @Nullable FrameSyntaxDecodeResult cdfReferenceResult = selectCdfReferenceFrameSyntaxResult(frameHeader);
        @Nullable FrameSyntaxDecodeResult temporalReferenceResult = selectTemporalReferenceFrameSyntaxResult(frameHeader);
        FrameSyntaxDecodeResult syntaxDecodeResult;
        try {
            syntaxDecodeResult = new FrameSyntaxDecoder(
                    cdfReferenceResult,
                    temporalReferenceResult
            ).decode(assembly);
        } catch (IllegalStateException exception) {
            throw new DecodeException(
                    DecodeErrorCode.NOT_IMPLEMENTED,
                    DecodeStage.FRAME_DECODE,
                    exception.getMessage() != null ? exception.getMessage() : "AV1 frame decoding is not implemented yet",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null,
                    exception
            );
        }
        lastFrameSyntaxDecodeResult = syntaxDecodeResult;
        FrameSyntaxDecodeResult storedSyntaxDecodeResult =
                storedReferenceFrameSyntaxResult(frameHeader, syntaxDecodeResult, cdfReferenceResult);
        refreshReferenceFrameSyntaxState(frameHeader, storedSyntaxDecodeResult);
        boolean shouldOutput = FrameOutputPolicy.shouldOutputFrame(frameHeader, config);
        boolean needsSurfaceSnapshot = frameHeader.refreshFrameFlags() != 0;

        @Nullable DecodedPlanes decodedPlanes = null;
        if (shouldOutput || needsSurfaceSnapshot) {
            try {
                decodedPlanes = frameReconstructor.reconstruct(syntaxDecodeResult, currentReferenceSurfaceSnapshots());
            } catch (IllegalStateException exception) {
                throw new DecodeException(
                        DecodeErrorCode.NOT_IMPLEMENTED,
                        DecodeStage.FRAME_DECODE,
                        exception.getMessage() != null ? exception.getMessage() : "AV1 frame decoding is not implemented yet",
                        packet.streamOffset(),
                        packet.obuIndex(),
                        null,
                        exception
                );
            }
        }

        @Nullable DecodedPlanes postprocessedPlanes = null;
        if (decodedPlanes != null) {
            try {
                postprocessedPlanes = framePostprocessor.postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);
            } catch (IllegalStateException exception) {
                throw new DecodeException(
                        DecodeErrorCode.NOT_IMPLEMENTED,
                        DecodeStage.FRAME_DECODE,
                        exception.getMessage() != null ? exception.getMessage() : "AV1 postfiltering is not implemented yet",
                        packet.streamOffset(),
                        packet.obuIndex(),
                        null,
                        exception
                );
            }
            refreshReferenceSurfaceState(
                    frameHeader,
                    new ReferenceSurfaceSnapshot(frameHeader, storedSyntaxDecodeResult, postprocessedPlanes)
            );
        }
        if (!shouldOutput) {
            return null;
        }
        DecodedPlanes presentationPlanes = applyPresentationFilters(
                Objects.requireNonNull(postprocessedPlanes, "postprocessedPlanes"),
                frameHeader
        );
        return OutputFrameFactory.createFrame(
                presentationPlanes,
                frameHeader,
                frameHeader.showFrame(),
                nextPresentationIndex++
        );
    }

    /// Parses and appends a standalone tile-group OBU to the current frame assembly.
    ///
    /// @param packet the standalone tile-group OBU
    /// @return the updated in-progress frame assembly
    /// @throws IOException if the OBU is unreadable, malformed, or out of order
    private FrameAssembly appendStandaloneTileGroup(ObuPacket packet) throws IOException {
        requireSequenceHeader(packet);
        FrameAssembly assembly = requirePendingFrameAssembly(packet);

        BitReader reader = new BitReader(packet.payload());
        TileGroupHeader tileGroupHeader = tileGroupHeaderParser.parse(reader, packet, assembly.frameHeader());
        reader.byteAlign();
        appendTileGroup(assembly, packet, tileGroupHeader, reader.byteOffset());
        return assembly;
    }

    /// Appends parsed tile-group metadata to the supplied frame assembly.
    ///
    /// @param assembly the in-progress frame assembly
    /// @param packet the source OBU that carried the tile group
    /// @param tileGroupHeader the parsed tile-group header
    /// @param tileDataOffset the byte offset of the tile data inside the OBU payload
    /// @throws DecodeException if the tile group is out of order or inconsistent with the frame layout
    private void appendTileGroup(
            FrameAssembly assembly,
            ObuPacket packet,
            TileGroupHeader tileGroupHeader,
            int tileDataOffset
    ) throws DecodeException {
        if (tileGroupHeader.totalTileCount() != assembly.totalTiles()) {
            throw invalidBitstream(packet, "Tile-group header does not match the active frame tile layout");
        }
        if (tileGroupHeader.startTileIndex() != assembly.nextTileIndex()) {
            throw invalidBitstream(
                    packet,
                    "Tile groups are out of order: expected tile " + assembly.nextTileIndex()
                            + " but received " + tileGroupHeader.startTileIndex()
            );
        }

        int tileDataLength = packet.payload().length - tileDataOffset;
        TileBitstream[] tiles = tileBitstreamParser.parse(packet, assembly.frameHeader(), tileGroupHeader, tileDataOffset);
        assembly.addTileGroup(packet, tileGroupHeader, tileDataOffset, tileDataLength, tiles);
    }

    /// Returns the active sequence header or throws a contextual state violation.
    ///
    /// @param packet the source OBU packet
    /// @return the active sequence header
    /// @throws DecodeException if no sequence header has been seen yet
    private SequenceHeader requireSequenceHeader(ObuPacket packet) throws DecodeException {
        if (sequenceHeader == null) {
            throw new DecodeException(
                    DecodeErrorCode.STATE_VIOLATION,
                    DecodeStage.FRAME_ASSEMBLY,
                    "Frame data appeared before a sequence header OBU",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
        return sequenceHeader;
    }

    /// Returns the current frame assembly or throws a contextual state violation.
    ///
    /// @param packet the source OBU packet
    /// @return the current in-progress frame assembly
    /// @throws DecodeException if no frame header has started a frame assembly yet
    private FrameAssembly requirePendingFrameAssembly(ObuPacket packet) throws DecodeException {
        if (pendingFrameAssembly == null) {
            throw new DecodeException(
                    DecodeErrorCode.STATE_VIOLATION,
                    DecodeStage.FRAME_ASSEMBLY,
                    "Tile-group OBU appeared before a frame header OBU",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
        return pendingFrameAssembly;
    }

    /// Ensures that no previous frame assembly is still waiting for tile groups.
    ///
    /// @param packet the source OBU packet
    /// @param message the detailed state-violation message
    /// @throws DecodeException if a previous frame assembly is still in progress
    private void ensureNoPendingFrameAssembly(ObuPacket packet, String message) throws DecodeException {
        if (pendingFrameAssembly != null) {
            throw new DecodeException(
                    DecodeErrorCode.STATE_VIOLATION,
                    DecodeStage.FRAME_ASSEMBLY,
                    message,
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
    }

    /// Ensures that a referenced `show_existing_frame` slot already contains a decoded frame state.
    ///
    /// @param packet the source OBU packet
    /// @param existingFrameIndex the referenced frame slot
    /// @throws DecodeException if the referenced frame slot has not been populated yet
    private void requireExistingFrameState(ObuPacket packet, int existingFrameIndex) throws DecodeException {
        if (existingFrameIndex < 0 || existingFrameIndex >= referenceSlots.length) {
            throw new DecodeException(
                    DecodeErrorCode.STATE_VIOLATION,
                    DecodeStage.FRAME_DECODE,
                    "show_existing_frame references an invalid frame slot",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
        if (!referenceSlots[existingFrameIndex].hasSyntaxState()) {
            throw new DecodeException(
                    DecodeErrorCode.STATE_VIOLATION,
                    DecodeStage.FRAME_DECODE,
                    "show_existing_frame references a frame slot that has not been populated",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
    }

    /// Returns one decoded `show_existing_frame` output from the requested reference slot, or
    /// `null` when current public filtering suppresses presentation.
    ///
    /// This first implementation reuses the already stored reconstructed reference surface
    /// directly and therefore requires a populated `ReferenceSurfaceSnapshot`.
    ///
    /// @param packet the source OBU packet that requested `show_existing_frame`
    /// @param existingFrameIndex the referenced frame slot
    /// @return one decoded `show_existing_frame` output, or `null` when output filtering suppresses it
    /// @throws DecodeException if the referenced slot is invalid, missing state, or would require
    ///                         unsupported presentation work such as film grain synthesis
    private @Nullable DecodedFrame outputExistingFrame(ObuPacket packet, int existingFrameIndex) throws DecodeException {
        requireExistingFrameState(packet, existingFrameIndex);
        RuntimeReferenceSlot slot = referenceSlots[existingFrameIndex];
        lastFrameSyntaxDecodeResult = slot.syntaxResult();
        FrameHeader referencedFrameHeader = Objects.requireNonNull(slot.frameHeader(), "referencedFrameHeader");
        if (!FrameOutputPolicy.shouldOutputExistingFrame(referencedFrameHeader, config)) {
            return null;
        }
        @Nullable ReferenceSurfaceSnapshot referenceSurfaceSnapshot = slot.surfaceSnapshot();
        if (referenceSurfaceSnapshot == null) {
            throw new DecodeException(
                    DecodeErrorCode.NOT_IMPLEMENTED,
                    DecodeStage.FRAME_DECODE,
                    "show_existing_frame output currently requires a reconstructed reference surface",
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
        DecodedPlanes presentationPlanes = applyPresentationFilters(referenceSurfaceSnapshot.decodedPlanes(), referencedFrameHeader);
        return OutputFrameFactory.createFrame(presentationPlanes, referencedFrameHeader, true, nextPresentationIndex++);
    }

    /// Applies presentation-only output filters such as film grain.
    ///
    /// Stored reference surfaces remain post-filter, post-super-resolution, and pre-grain.
    /// Presentation output may use a grain-applied copy when the current decoder configuration
    /// requests it.
    ///
    /// @param decodedPlanes the post-filter, post-super-resolution, pre-grain planes
    /// @param frameHeader the normalized frame header that owns the output
    /// @return the presentation planes after output-only processing
    private DecodedPlanes applyPresentationFilters(DecodedPlanes decodedPlanes, FrameHeader frameHeader) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        if (FrameOutputPolicy.requiresFilmGrainSynthesis(checkedFrameHeader, config)) {
            return filmGrainSynthesizer.apply(checkedDecodedPlanes, checkedFrameHeader);
        }
        return checkedDecodedPlanes;
    }

    /// Creates a contextual invalid-bitstream exception for frame-assembly errors.
    ///
    /// @param packet the source OBU packet
    /// @param message the detailed validation message
    /// @return the contextual invalid-bitstream exception
    private static DecodeException invalidBitstream(ObuPacket packet, String message) {
        return new DecodeException(
                DecodeErrorCode.INVALID_BITSTREAM,
                DecodeStage.FRAME_ASSEMBLY,
                message,
                packet.streamOffset(),
                packet.obuIndex(),
                null
        );
    }

    /// Creates an error for end-of-stream while a frame assembly is still incomplete.
    ///
    /// @param assembly the incomplete frame assembly
    /// @return the contextual invalid-bitstream exception
    private static DecodeException incompleteFrameAtEndOfStream(FrameAssembly assembly) {
        return new DecodeException(
                DecodeErrorCode.INVALID_BITSTREAM,
                DecodeStage.FRAME_ASSEMBLY,
                "End of stream was reached before the current frame tile groups were completed",
                assembly.streamOffset(),
                assembly.obuIndex(),
                null
        );
    }

    /// Enforces the configured frame size limit against a parsed frame header.
    ///
    /// @param frameHeader the parsed frame header
    /// @param packet the source OBU packet
    /// @throws DecodeException if the configured frame size limit is exceeded
    private void enforceFrameSizeLimit(FrameHeader frameHeader, ObuPacket packet) throws DecodeException {
        long frameSizeLimit = config.frameSizeLimit();
        if (frameSizeLimit == 0 || frameHeader.showExistingFrame()) {
            return;
        }

        long pixelCount = (long) frameHeader.frameSize().upscaledWidth() * frameHeader.frameSize().height();
        if (pixelCount > frameSizeLimit) {
            throw new DecodeException(
                    DecodeErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                    DecodeStage.FRAME_HEADER_PARSE,
                    "Frame size exceeds the configured limit: " + frameHeader.frameSize().upscaledWidth()
                            + "x" + frameHeader.frameSize().height(),
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
    }

    /// Returns the structural decode result whose final tile-local CDF contexts should seed the next frame.
    ///
    /// AV1 inherits entropy state from `primary_ref_frame` when it is present. When
    /// `primary_ref_frame == PRIMARY_REF_NONE`, no reference CDF state is inherited.
    ///
    /// @param frameHeader the parsed frame header for the next frame
    /// @return the structural decode result whose final tile-local CDF contexts should seed the next frame, or `null`
    private @Nullable FrameSyntaxDecodeResult selectCdfReferenceFrameSyntaxResult(FrameHeader frameHeader) {
        int primaryRefFrame = frameHeader.primaryRefFrame();
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null;
        }

        int primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame);
        if (primarySlot < 0 || primarySlot >= referenceSlots.length) {
            return null;
        }
        return referenceSlots[primarySlot].syntaxResult();
    }

    /// Returns the structural decode result whose temporal motion field should seed the next frame.
    ///
    /// The current implementation prefers `primary_ref_frame` when available, then falls back to
    /// the first populated reference in LAST..ALTREF order.
    ///
    /// @param frameHeader the parsed frame header for the next frame
    /// @return the structural decode result whose temporal motion field should seed the next frame, or `null`
    private @Nullable FrameSyntaxDecodeResult selectTemporalReferenceFrameSyntaxResult(FrameHeader frameHeader) {
        if (!frameHeader.useReferenceFrameMotionVectors()) {
            return null;
        }

        @Nullable FrameSyntaxDecodeResult primaryResult = selectCdfReferenceFrameSyntaxResult(frameHeader);
        if (primaryResult != null) {
            return primaryResult;
        }

        int[] referenceFrameIndices = frameHeader.referenceFrameIndices();
        for (int referenceSlot : referenceFrameIndices) {
            if (referenceSlot >= 0 && referenceSlot < referenceSlots.length) {
                @Nullable FrameSyntaxDecodeResult candidate = referenceSlots[referenceSlot].syntaxResult();
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /// Returns the structural decode result that should be stored in refreshed reference slots.
    ///
    /// When `refresh_context` is disabled the refreshed slots keep their inherited entropy state,
    /// while still receiving the current frame's partition trees and temporal motion fields.
    ///
    /// @param frameHeader the parsed frame header whose refresh flags will be applied
    /// @param syntaxDecodeResult the structural frame-decode result produced for the current frame
    /// @param cdfReferenceResult the inherited CDF reference snapshot, or `null`
    /// @return the structural decode result that should be stored in refreshed reference slots
    private FrameSyntaxDecodeResult storedReferenceFrameSyntaxResult(
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult,
            @Nullable FrameSyntaxDecodeResult cdfReferenceResult
    ) {
        if (frameHeader.refreshContext()) {
            return syntaxDecodeResult;
        }

        CdfContext[] storedTileCdfContexts = cdfReferenceResult != null
                ? cdfReferenceResult.finalTileCdfContexts()
                : defaultTileCdfContexts(syntaxDecodeResult.tileCount());
        return syntaxDecodeResult.withFinalTileCdfContexts(storedTileCdfContexts);
    }

    /// Refreshes any reference-frame slots targeted by the parsed frame header.
    ///
    /// @param frameHeader the parsed frame header whose refresh flags should be applied
    /// @param syntaxDecodeResult the structural frame-decode result to store in refreshed slots
    private void refreshReferenceFrameSyntaxState(FrameHeader frameHeader, FrameSyntaxDecodeResult syntaxDecodeResult) {
        int refreshFrameFlags = frameHeader.refreshFrameFlags();
        for (int i = 0; i < referenceSlots.length; i++) {
            if ((refreshFrameFlags & (1 << i)) != 0) {
                referenceSlots[i].refreshSyntax(frameHeader, syntaxDecodeResult);
            }
        }
    }

    /// Refreshes any reference-surface slots targeted by the parsed frame header.
    ///
    /// Only successfully reconstructed frames populate these slots. Structural syntax refresh is
    /// handled separately so unsupported pixel paths still preserve reference syntax state.
    ///
    /// @param frameHeader the parsed frame header whose refresh flags should be applied
    /// @param referenceSurfaceSnapshot the reconstructed reference surface to store
    private void refreshReferenceSurfaceState(
            FrameHeader frameHeader,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        int refreshFrameFlags = frameHeader.refreshFrameFlags();
        for (int i = 0; i < referenceSlots.length; i++) {
            if ((refreshFrameFlags & (1 << i)) != 0) {
                referenceSlots[i].refreshSurface(referenceSurfaceSnapshot);
            }
        }
    }

    /// Creates default tile-local CDF contexts for the supplied tile count.
    ///
    /// @param tileCount the number of tiles in the frame
    /// @return default tile-local CDF contexts for the supplied tile count
    private static CdfContext[] defaultTileCdfContexts(int tileCount) {
        CdfContext[] contexts = new CdfContext[tileCount];
        for (int i = 0; i < tileCount; i++) {
            contexts[i] = CdfContext.createDefault();
        }
        return contexts;
    }

    /// Creates one fixed-size array of empty runtime reference slots.
    ///
    /// @param slotCount the number of reference slots to allocate
    /// @return one fixed-size array of empty runtime reference slots
    private static RuntimeReferenceSlot[] createReferenceSlots(int slotCount) {
        RuntimeReferenceSlot[] slots = new RuntimeReferenceSlot[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slots[i] = new RuntimeReferenceSlot();
        }
        return slots;
    }

    /// Clears all runtime reference slots.
    private void clearReferenceSlots() {
        for (RuntimeReferenceSlot referenceSlot : referenceSlots) {
            referenceSlot.clear();
        }
    }

    /// Returns the current reference-frame headers as one parser-facing slot array snapshot.
    ///
    /// @return the current reference-frame headers as one parser-facing slot array snapshot
    private FrameHeader[] referenceFrameHeadersForParsing() {
        FrameHeader[] headers = new FrameHeader[referenceSlots.length];
        for (int i = 0; i < referenceSlots.length; i++) {
            headers[i] = referenceSlots[i].frameHeader();
        }
        return headers;
    }

    /// Returns the current stored reference surfaces as one slot-indexed snapshot array.
    ///
    /// @return the current stored reference surfaces as one slot-indexed snapshot array
    private @Nullable ReferenceSurfaceSnapshot[] currentReferenceSurfaceSnapshots() {
        ReferenceSurfaceSnapshot[] snapshots = new ReferenceSurfaceSnapshot[referenceSlots.length];
        for (int i = 0; i < referenceSlots.length; i++) {
            snapshots[i] = referenceSlots[i].surfaceSnapshot();
        }
        return snapshots;
    }

    /// The immediate result of parsing one combined `FRAME` OBU.
    ///
    /// Combined frames either start a normal `FrameAssembly` or resolve immediately through the
    /// `show_existing_frame` output path.
    @NotNullByDefault
    private static final class CombinedFrameStart {
        /// The started frame assembly, or `null` when output resolved immediately.
        private final @Nullable FrameAssembly frameAssembly;

        /// The immediate output frame, or `null` when normal frame assembly should continue.
        private final @Nullable DecodedFrame immediateOutput;

        /// Whether this combined frame resolved immediately through `show_existing_frame`.
        private final boolean resolvedImmediately;

        /// Creates one combined-frame start result.
        ///
        /// @param frameAssembly the started frame assembly, or `null`
        /// @param immediateOutput the immediate output frame, or `null`
        /// @param resolvedImmediately whether this combined frame resolved immediately
        private CombinedFrameStart(
                @Nullable FrameAssembly frameAssembly,
                @Nullable DecodedFrame immediateOutput,
                boolean resolvedImmediately
        ) {
            this.frameAssembly = frameAssembly;
            this.immediateOutput = immediateOutput;
            this.resolvedImmediately = resolvedImmediately;
        }

        /// Creates one result that continues with normal frame assembly.
        ///
        /// @param frameAssembly the started frame assembly
        /// @return one result that continues with normal frame assembly
        private static CombinedFrameStart frameAssembly(FrameAssembly frameAssembly) {
            return new CombinedFrameStart(Objects.requireNonNull(frameAssembly, "frameAssembly"), null, false);
        }

        /// Creates one result that resolves immediately to output.
        ///
        /// @param immediateOutput the immediate output frame, or `null` when filtering suppresses output
        /// @return one result that resolves immediately to output
        private static CombinedFrameStart immediateOutput(@Nullable DecodedFrame immediateOutput) {
            return new CombinedFrameStart(null, immediateOutput, true);
        }

        /// Returns whether this combined frame resolved immediately through `show_existing_frame`.
        ///
        /// @return whether this combined frame resolved immediately through `show_existing_frame`
        private boolean resolvedImmediately() {
            return resolvedImmediately;
        }

        /// Returns the started frame assembly.
        ///
        /// @return the started frame assembly
        private FrameAssembly frameAssembly() {
            if (frameAssembly == null) {
                throw new IllegalStateException("Combined frame start resolved without a frame assembly");
            }
            return frameAssembly;
        }

        /// Returns the immediate output frame, or `null`.
        ///
        /// @return the immediate output frame, or `null`
        private @Nullable DecodedFrame immediateOutput() {
            return immediateOutput;
        }
    }
}
