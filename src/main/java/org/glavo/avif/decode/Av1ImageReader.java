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
import org.glavo.avif.internal.av1.decode.InvalidFrameSyntaxException;
import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TileList;
import org.glavo.avif.internal.av1.parse.FrameHeaderParser;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.parse.TileBitstreamParser;
import org.glavo.avif.internal.av1.parse.TileGroupHeaderParser;
import org.glavo.avif.internal.av1.parse.TileListParser;
import org.glavo.avif.internal.av1.postfilter.FilmGrainSynthesizer;
import org.glavo.avif.internal.av1.postfilter.FramePostprocessor;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.av1.recon.InvalidFrameReconstructionException;
import org.glavo.avif.internal.av1.recon.LargeScaleTileOutputBuilder;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.glavo.avif.internal.av1.runtime.FrameOutputPolicy;
import org.glavo.avif.internal.av1.runtime.OutputFrameFactory;
import org.glavo.avif.internal.av1.runtime.RuntimeReferenceSlot;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// High-level sequential reader for raw AV1 low-overhead and Annex B streams.
@NotNullByDefault
public final class Av1ImageReader implements AutoCloseable {
    /// Maximum number of decoded Large Scale Tile syntax trees retained for near-term reuse.
    private static final int LARGE_SCALE_TILE_SYNTAX_CACHE_SIZE = 8;

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
    /// The parser used for Large Scale Tile list OBUs.
    private final TileListParser tileListParser;
    /// The most recently parsed sequence header.
    private @Nullable SequenceHeader sequenceHeader;
    /// The runtime reference slots used for syntax inheritance and stored-surface reuse.
    private final RuntimeReferenceSlot[] referenceSlots;
    /// The currently assembled frame when tile groups span multiple OBUs.
    private @Nullable FrameAssembly pendingFrameAssembly;
    /// The common camera frame retained for Large Scale Tile list decoding.
    private @Nullable FrameAssembly largeScaleTileCameraAssembly;
    /// The internal syntax references captured when the common camera header was parsed.
    private final @Nullable ReferenceFrameSyntaxState[] largeScaleTileCameraReferenceSyntaxStates;
    /// The internal primary-reference syntax state inherited by the common camera frame.
    private @Nullable ReferenceFrameSyntaxState largeScaleTileCameraCdfReferenceState;
    /// The externally indexed anchor frames collected before Large Scale Tile output begins.
    private final List<ReferenceSurfaceSnapshot> largeScaleTileAnchorFrames;
    /// The last selected spatial-layer output retained for the current temporal unit.
    private @Nullable PendingOutput pendingLayeredOutput;
    /// The most recently completed structural frame-decode result.
    private @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult;
    /// The AV1 pixel reconstructor used for decoded frame output.
    private final FrameReconstructor frameReconstructor;
    /// The postfilter pipeline used before storing reference surfaces.
    private final FramePostprocessor framePostprocessor;
    /// The deterministic film-grain synthesizer used only at presentation time.
    private final FilmGrainSynthesizer filmGrainSynthesizer;
    /// The zero-based presentation index assigned to the next returned frame.
    private long nextPresentationIndex;
    /// Whether this reader has already been closed.
    private boolean closed;
    /// The postprocessed decoded planes from the most recent frame, or `null`.
    private @Nullable DecodedPlanes lastPlanes;

    /// Creates a sequential image reader.
    ///
    /// @param source the forward-only buffered byte source
    /// @param config the immutable decoder configuration
    /// @param annexB whether the source uses Annex B temporal-unit and frame-unit framing
    private Av1ImageReader(BufferedInput source, Av1DecoderConfig config, boolean annexB) {
        this.source = Objects.requireNonNull(source, "source");
        this.config = Objects.requireNonNull(config, "config");
        this.obuReader = annexB ? ObuStreamReader.forAnnexB(source) : new ObuStreamReader(source);
        this.sequenceHeaderParser = new SequenceHeaderParser();
        this.frameHeaderParser = new FrameHeaderParser();
        this.tileGroupHeaderParser = new TileGroupHeaderParser();
        this.tileBitstreamParser = new TileBitstreamParser();
        this.tileListParser = new TileListParser();
        this.referenceSlots = createReferenceSlots(8);
        this.largeScaleTileCameraReferenceSyntaxStates = new ReferenceFrameSyntaxState[8];
        this.largeScaleTileAnchorFrames = new ArrayList<>();
        this.frameReconstructor = new FrameReconstructor();
        this.framePostprocessor = new FramePostprocessor();
        this.filmGrainSynthesizer = new FilmGrainSynthesizer();
    }

    /// Opens a low-overhead AV1 image reader over a byte channel.
    ///
    /// The returned reader owns and closes `source`.
    ///
    /// @param source the channel to read
    /// @return the new AV1 image reader
    public static Av1ImageReader open(ReadableByteChannel source) {
        return open(source, Av1DecoderConfig.DEFAULT);
    }

    /// Opens a low-overhead AV1 image reader over a byte channel.
    ///
    /// The returned reader owns and closes `source`.
    ///
    /// @param source the channel to read
    /// @param config the immutable decoder configuration
    /// @return the new AV1 image reader
    public static Av1ImageReader open(ReadableByteChannel source, Av1DecoderConfig config) {
        return new Av1ImageReader(bufferedInput(source), config, false);
    }

    /// Opens a low-overhead AV1 image reader over a byte buffer.
    ///
    /// The reader starts at the buffer's current position without changing its position or limit.
    /// It reads the remaining content without copying, so that content must not be modified until
    /// the reader is closed.
    ///
    /// @param source the buffer to read
    /// @return the new AV1 image reader
    public static Av1ImageReader open(ByteBuffer source) {
        return open(source, Av1DecoderConfig.DEFAULT);
    }

    /// Opens a low-overhead AV1 image reader over a byte buffer.
    ///
    /// The reader starts at the buffer's current position without changing its position or limit.
    /// It reads the remaining content without copying, so that content must not be modified until
    /// the reader is closed.
    ///
    /// @param source the buffer to read
    /// @param config the immutable decoder configuration
    /// @return the new AV1 image reader
    public static Av1ImageReader open(ByteBuffer source, Av1DecoderConfig config) {
        return new Av1ImageReader(new BufferedInput.OfByteBuffer(source), config, false);
    }

    /// Opens an Annex B AV1 image reader using the default decoder configuration.
    ///
    /// The source must contain temporal-unit, frame-unit, and OBU length fields as specified by
    /// Annex B of the AV1 bitstream specification. The returned reader owns and closes `source`.
    ///
    /// @param source the channel to read
    /// @return the new Annex B AV1 image reader
    public static Av1ImageReader openAnnexB(ReadableByteChannel source) {
        return openAnnexB(source, Av1DecoderConfig.DEFAULT);
    }

    /// Opens an Annex B AV1 image reader using the supplied decoder configuration.
    ///
    /// The source must contain temporal-unit, frame-unit, and OBU length fields as specified by
    /// Annex B of the AV1 bitstream specification. The returned reader owns and closes `source`.
    ///
    /// @param source the channel to read
    /// @param config the immutable decoder configuration
    /// @return the new Annex B AV1 image reader
    public static Av1ImageReader openAnnexB(ReadableByteChannel source, Av1DecoderConfig config) {
        return new Av1ImageReader(bufferedInput(source), config, true);
    }

    /// Opens an Annex B AV1 image reader over a byte buffer.
    ///
    /// The reader starts at the buffer's current position without changing its position or limit.
    /// It reads the remaining content without copying, so that content must not be modified until
    /// the reader is closed.
    ///
    /// @param source the buffer to read
    /// @return the new Annex B AV1 image reader
    public static Av1ImageReader openAnnexB(ByteBuffer source) {
        return openAnnexB(source, Av1DecoderConfig.DEFAULT);
    }

    /// Opens an Annex B AV1 image reader over a byte buffer.
    ///
    /// The reader starts at the buffer's current position without changing its position or limit.
    /// It reads the remaining content without copying, so that content must not be modified until
    /// the reader is closed.
    ///
    /// @param source the buffer to read
    /// @param config the immutable decoder configuration
    /// @return the new Annex B AV1 image reader
    public static Av1ImageReader openAnnexB(ByteBuffer source, Av1DecoderConfig config) {
        return new Av1ImageReader(new BufferedInput.OfByteBuffer(source), config, true);
    }

    /// Returns a buffered decoder input over a channel.
    ///
    /// Internal channel implementations are retained so container-provided unit boundaries remain
    /// visible to the low-overhead OBU reader.
    ///
    /// @param source the channel to adapt
    /// @return a buffered decoder input
    private static BufferedInput bufferedInput(ReadableByteChannel source) {
        ReadableByteChannel checkedSource = Objects.requireNonNull(source, "source");
        return checkedSource instanceof BufferedInput bufferedInput
                ? bufferedInput
                : new BufferedInput.OfByteChannel(checkedSource);
    }

    /// Reads the next decoded frame from the source.
    ///
    /// @return the next decoded frame, or `null` at end-of-stream
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    public @Nullable DecodedFrame readFrame() throws IOException {
        @Nullable PendingOutput output = readNextOutput();
        if (output == null) {
            return null;
        }
        DecodedFrame frame;
        try {
            frame = output.createFrame();
        } catch (UnsupportedOperationException exception) {
            throw unsupportedOutputConversion(output.packet(), exception);
        }
        nextPresentationIndex++;
        return frame;
    }

    /// Reads the next output frame as postprocessed YUV planes without RGB conversion.
    ///
    /// This method applies the same operating-point, frame-output, and film-grain policies as
    /// [#readFrame()]. It may be interleaved with `readFrame`; each successful call consumes one
    /// presentation frame. The returned immutable snapshot retains its own sample storage and does
    /// not depend on subsequent reader operations.
    ///
    /// @return the next decoded plane snapshot, or `null` at end-of-stream
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    public @Nullable DecodedPlanes readPlanes() throws IOException {
        @Nullable PendingOutput output = readNextOutput();
        if (output == null) {
            return null;
        }
        nextPresentationIndex++;
        return output.planes();
    }

    /// Decodes packets until one presentation output is available.
    ///
    /// @return the next pending presentation output, or `null` at end-of-stream
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    private @Nullable PendingOutput readNextOutput() throws IOException {
        ensureOpen();

        while (true) {
            if (obuReader.atTemporalUnitBoundary() && pendingLayeredOutput != null) {
                PendingOutput output = pendingLayeredOutput;
                pendingLayeredOutput = null;
                return output;
            }
            ObuPacket packet = obuReader.readObu();
            if (packet == null) {
                if (pendingFrameAssembly != null) {
                    throw incompleteFrameAtEndOfStream(pendingFrameAssembly);
                }
                if (pendingLayeredOutput != null) {
                    PendingOutput output = pendingLayeredOutput;
                    pendingLayeredOutput = null;
                    return output;
                }
                return null;
            }

            ObuType type = packet.header().type();
            if (config.strictStdCompliance() && type == ObuType.METADATA) {
                validateMetadataType(packet);
            }
            if (type != ObuType.SEQUENCE_HEADER) {
                validateRequiredObuExtension(packet);
            }
            if (type == ObuType.TEMPORAL_DELIMITER) {
                retainLargeScaleTileCameraHeaderAtBoundary(packet);
                if (pendingLayeredOutput != null) {
                    PendingOutput output = pendingLayeredOutput;
                    pendingLayeredOutput = null;
                    return output;
                }
                continue;
            }
            if (type == ObuType.SEQUENCE_HEADER) {
                ensureNoPendingFrameAssembly(packet, "Sequence header OBU appeared before the current frame was completed");
                SequenceHeader parsedSequenceHeader = sequenceHeaderParser.parse(packet, config.strictStdCompliance());
                validateSelectedOperatingPoint(parsedSequenceHeader, packet);
                sequenceHeader = parsedSequenceHeader;
                continue;
            }
            if (type == ObuType.TILE_LIST) {
                if (!config.largeScaleTileMode()) {
                    throw invalidBitstream(packet, "Tile-list OBU requires Large Scale Tile decoder mode");
                }
                retainLargeScaleTileCameraHeader(packet);
                return decodeLargeScaleTileList(packet);
            }
            if (!matchesSelectedOperatingPoint(packet)) {
                continue;
            }
            if (type == ObuType.FRAME_HEADER) {
                @Nullable PendingOutput output = startStandaloneFrameAssembly(packet);
                if (output != null) {
                    @Nullable PendingOutput readyOutput = retainLayeredOutput(output);
                    if (readyOutput != null) {
                        return readyOutput;
                    }
                }
                continue;
            }
            if (type == ObuType.FRAME) {
                CombinedFrameStart start = startCombinedFrameAssembly(packet);
                if (start.resolvedImmediately()) {
                    @Nullable PendingOutput output = start.immediateOutput();
                    if (output != null) {
                        @Nullable PendingOutput readyOutput = retainLayeredOutput(output);
                        if (readyOutput != null) {
                            return readyOutput;
                        }
                    }
                    continue;
                }
                FrameAssembly assembly = start.frameAssembly();
                if (assembly.isComplete()) {
                    pendingFrameAssembly = null;
                    @Nullable PendingOutput output = completeFrameAssembly(assembly, packet);
                    if (output != null) {
                        @Nullable PendingOutput readyOutput = retainLayeredOutput(output);
                        if (readyOutput != null) {
                            return readyOutput;
                        }
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
                    @Nullable PendingOutput output = completeFrameAssembly(assembly, packet);
                    if (output != null) {
                        @Nullable PendingOutput readyOutput = retainLayeredOutput(output);
                        if (readyOutput != null) {
                            return readyOutput;
                        }
                    }
                }
                continue;
            }
        }
    }

    /// Retains a spatial-layer output until its temporal unit ends when layer collapsing is active.
    ///
    /// @param output the newly prepared presentation output
    /// @return the output for immediate presentation, or `null` when it was retained
    private @Nullable PendingOutput retainLayeredOutput(PendingOutput output) {
        SequenceHeader activeSequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        SequenceHeader.OperatingPoint operatingPoint = activeSequenceHeader.operatingPoint(config.operatingPoint());
        int selectedSpatialLayers = Integer.bitCount((operatingPoint.idc() >>> 8) & 0x0F);
        if (config.outputAllLayers() || selectedSpatialLayers <= 1) {
            return output;
        }
        pendingLayeredOutput = output;
        return null;
    }

    /// Returns the postprocessed planes from the most recently prepared presentation output.
    ///
    /// The value is updated before packed-pixel conversion, so it may be non-null after
    /// [#readFrame()] fails because the active color configuration cannot be converted.
    ///
    /// @return the postprocessed planes, or `null` if no presentation output has been prepared yet
    public @Nullable DecodedPlanes lastPlanes() {
        return lastPlanes;
    }

    /// Returns the color configuration from the active AV1 sequence header.
    ///
    /// @return the active color configuration, or `null` before a sequence header is parsed
    public @Nullable Av1ColorConfig lastColorConfig() {
        SequenceHeader activeSequenceHeader = sequenceHeader;
        return activeSequenceHeader != null ? activeSequenceHeader.colorConfig() : null;
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
    /// This package-private accessor exposes structural state for decoder conformance tests.
    ///
    /// @return the most recently completed structural frame-decode result, or `null`
    @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult() {
        return lastFrameSyntaxDecodeResult;
    }

    /// Returns one refreshed compact reference-frame syntax state, or `null`.
    ///
    /// This package-private accessor exposes reference-slot state for decoder conformance tests.
    ///
    /// @param slot the zero-based reference-frame slot
    /// @return one refreshed compact reference-frame syntax state, or `null`
    @Nullable ReferenceFrameSyntaxState referenceFrameSyntaxState(int slot) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }
        return referenceSlots[slot].syntaxState();
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
    /// @param referenceSurfaceSnapshot the complete reconstructed reference state
    void injectReferenceStateForTest(
            int slot,
            SequenceHeader sequenceHeader,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        if (slot < 0 || slot >= referenceSlots.length) {
            throw new IndexOutOfBoundsException("slot out of range: " + slot);
        }

        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        RuntimeReferenceSlot referenceSlot = referenceSlots[slot];
        referenceSlot.clear();
        referenceSlot.refresh(Objects.requireNonNull(referenceSurfaceSnapshot, "referenceSurfaceSnapshot"));
    }

    /// Ensures that this reader has not already been closed.
    ///
    /// @throws IOException if this reader has already been closed
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Av1ImageReader is closed");
        }
    }

    /// Validates that the configured operating point is declared by a parsed sequence header.
    ///
    /// @param parsedSequenceHeader the parsed sequence header
    /// @param packet the packet carrying the sequence header
    /// @throws DecodeException if the configured operating point is absent
    private void validateSelectedOperatingPoint(SequenceHeader parsedSequenceHeader, ObuPacket packet)
            throws DecodeException {
        selectedOperatingPoint(parsedSequenceHeader, packet);
    }

    /// Returns whether one OBU belongs to the configured operating point.
    ///
    /// @param packet the OBU packet to test
    /// @return whether the packet should be decoded for the configured operating point
    /// @throws DecodeException if the configured operating point is absent from the active sequence
    private boolean matchesSelectedOperatingPoint(ObuPacket packet) throws DecodeException {
        SequenceHeader activeSequenceHeader = sequenceHeader;
        if (activeSequenceHeader == null) {
            return true;
        }

        SequenceHeader.OperatingPoint operatingPoint = selectedOperatingPoint(activeSequenceHeader, packet);
        int idc = operatingPoint.idc();
        if (idc == 0) {
            return true;
        }

        int temporalBit = 1 << packet.header().temporalId();
        int spatialBit = 1 << (packet.header().spatialId() + 8);
        return (idc & temporalBit) != 0 && (idc & spatialBit) != 0;
    }

    /// Validates the extension-header requirement imposed by non-zero operating-point masks.
    ///
    /// @param packet the current OBU packet
    /// @throws DecodeException if strict mode requires an extension header that is absent
    private void validateRequiredObuExtension(ObuPacket packet) throws DecodeException {
        SequenceHeader activeSequenceHeader = sequenceHeader;
        if (!config.strictStdCompliance() || activeSequenceHeader == null) {
            return;
        }
        boolean hasLayeredOperatingPoint = false;
        for (int index = 0; index < activeSequenceHeader.operatingPointCount(); index++) {
            if (activeSequenceHeader.operatingPoint(index).idc() != 0) {
                hasLayeredOperatingPoint = true;
                break;
            }
        }
        if (!hasLayeredOperatingPoint) {
            if (packet.header().extensionFlag()) {
                throw invalidBitstream(
                        packet,
                        "OBU extension headers are forbidden when every operating point IDC is zero"
                );
            }
            return;
        }
        if (packet.header().extensionFlag()) {
            return;
        }
        ObuType type = packet.header().type();
        if (type != ObuType.FRAME_HEADER
                && type != ObuType.FRAME
                && type != ObuType.REDUNDANT_FRAME_HEADER
                && type != ObuType.TILE_GROUP) {
            return;
        }
        throw invalidBitstream(
                packet,
                "Frame and tile OBUs require extension headers when an operating point IDC is non-zero"
        );
    }

    /// Validates the leading LEB128 metadata type in one metadata OBU.
    ///
    /// @param packet the metadata OBU packet
    /// @throws DecodeException if the type is truncated, wider than 32 bits, or uses a ninth byte
    private static void validateMetadataType(ObuPacket packet) throws DecodeException {
        byte[] payload = packet.payload();
        long value = 0;
        for (int index = 0; index < 8; index++) {
            if (index >= payload.length) {
                throw invalidBitstream(packet, "Metadata OBU has a truncated metadata_type LEB128 value");
            }
            int currentByte = payload[index] & 0xFF;
            value |= (long) (currentByte & 0x7F) << (index * 7);
            if ((currentByte & 0x80) == 0) {
                if (value > 0xFFFF_FFFFL) {
                    throw invalidBitstream(packet, "Metadata type exceeds the unsigned 32-bit range");
                }
                return;
            }
        }
        throw invalidBitstream(packet, "Metadata type LEB128 value continues beyond eight bytes");
    }

    /// Returns the configured operating point from one sequence header.
    ///
    /// @param activeSequenceHeader the active sequence header
    /// @param packet the packet used for error context
    /// @return the configured operating point
    /// @throws DecodeException if the configured operating point is absent
    private SequenceHeader.OperatingPoint selectedOperatingPoint(
            SequenceHeader activeSequenceHeader,
            ObuPacket packet
    ) throws DecodeException {
        int operatingPoint = config.operatingPoint();
        if (operatingPoint >= activeSequenceHeader.operatingPointCount()) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.SEQUENCE_HEADER_PARSE,
                    "Configured operating point is not declared by the sequence header: " + operatingPoint,
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
        return activeSequenceHeader.operatingPoint(operatingPoint);
    }

    /// Starts a new frame assembly from a standalone frame-header OBU.
    ///
    /// @param packet the standalone frame-header OBU
    /// @return an immediate `show_existing_frame` output, or `null` when assembly should continue
    ///         or the requested output is suppressed by the decoder configuration
    /// @throws IOException if the OBU is unreadable, malformed, or out of order
    private @Nullable PendingOutput startStandaloneFrameAssembly(ObuPacket packet) throws IOException {
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
            return outputExistingFrame(packet, frameHeader);
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
    /// @return either the started frame assembly including its first tile group or an immediate
    ///         `show_existing_frame` result
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
            return CombinedFrameStart.immediateOutput(outputExistingFrame(packet, frameHeader));
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
        if (config.strictStdCompliance() && tileGroupHeader.explicitTilePositions()) {
            throw invalidBitstream(packet, "Combined frame OBU must not signal explicit tile positions");
        }
        reader.byteAlign();
        appendTileGroup(assembly, packet, tileGroupHeader, reader.byteOffset());
        return CombinedFrameStart.frameAssembly(assembly);
    }

    /// Finalizes a completed frame assembly by refreshing reference slots and optionally preparing
    /// one presentation output.
    ///
    /// @param assembly the completed frame assembly
    /// @param packet the OBU that completed the frame assembly
    /// @return the pending presentation output, or `null` when current output filtering suppresses it
    private @Nullable PendingOutput completeFrameAssembly(FrameAssembly assembly, ObuPacket packet) throws DecodeException {
        FrameHeader frameHeader = assembly.frameHeader();
        if (config.strictStdCompliance()) {
            validateReferenceState(assembly, packet);
        }
        @Nullable ReferenceFrameSyntaxState cdfReferenceState = selectCdfReferenceFrameSyntaxState(frameHeader);
        FrameSyntaxDecodeResult syntaxDecodeResult;
        try {
            syntaxDecodeResult = new FrameSyntaxDecoder(
                    cdfReferenceState,
                    referenceFrameSyntaxStatesForDecoding(),
                    config.strictStdCompliance()
            ).decode(assembly);
        } catch (InvalidFrameSyntaxException exception) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.FRAME_DECODE,
                    exception.getMessage(),
                    assembly.streamOffset(),
                    assembly.obuIndex(),
                    null,
                    exception
            );
        }
        lastFrameSyntaxDecodeResult = syntaxDecodeResult;
        boolean shouldOutput = !config.largeScaleTileMode()
                && FrameOutputPolicy.shouldOutputFrame(frameHeader, config);
        boolean needsSurfaceSnapshot = frameHeader.refreshFrameFlags() != 0;
        boolean needsAnchorSnapshot = config.largeScaleTileMode() && frameHeader.showFrame();
        @Nullable ReferenceFrameSyntaxState storedSyntaxState = needsSurfaceSnapshot || needsAnchorSnapshot
                ? storedReferenceFrameSyntaxState(frameHeader, syntaxDecodeResult, cdfReferenceState)
                : null;

        @Nullable DecodedPlanes decodedPlanes = null;
        if (shouldOutput || needsSurfaceSnapshot || needsAnchorSnapshot) {
            try {
                decodedPlanes = frameReconstructor.reconstruct(
                        syntaxDecodeResult,
                        currentReferenceSurfaceSnapshots(),
                        config.strictStdCompliance()
                );
            } catch (InvalidFrameReconstructionException exception) {
                throw new DecodeException(
                        DecodeErrorCode.INVALID_BITSTREAM,
                        DecodeStage.FRAME_DECODE,
                        exception.getMessage(),
                        assembly.streamOffset(),
                        assembly.obuIndex(),
                        null,
                        exception
                );
            }
        }

        @Nullable DecodedPlanes postprocessedPlanes = null;
        if (decodedPlanes != null) {
            postprocessedPlanes = framePostprocessor.postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);
            if (needsSurfaceSnapshot || needsAnchorSnapshot) {
                ReferenceSurfaceSnapshot snapshot = new ReferenceSurfaceSnapshot(
                        frameHeader,
                        Objects.requireNonNull(storedSyntaxState, "storedSyntaxState"),
                        postprocessedPlanes
                );
                if (needsSurfaceSnapshot) {
                    refreshReferenceState(frameHeader, snapshot);
                }
                if (needsAnchorSnapshot) {
                    addLargeScaleTileAnchorFrame(snapshot, packet);
                }
            }
        }
        if (!shouldOutput) {
            return null;
        }
        DecodedPlanes presentationPlanes = applyPresentationFilters(
                Objects.requireNonNull(postprocessedPlanes, "postprocessedPlanes"),
                frameHeader,
                assembly.sequenceHeader().colorConfig()
        );
        lastPlanes = presentationPlanes;
        return PendingOutput.normal(
                presentationPlanes,
                assembly.sequenceHeader().colorConfig(),
                frameHeader,
                frameHeader.showFrame(),
                nextPresentationIndex,
                packet
        );
    }

    /// Validates that every selected reference frame is populated and sequence-compatible.
    ///
    /// @param assembly the completed current frame assembly
    /// @param packet the OBU that completed the frame assembly
    /// @throws DecodeException if a selected reference is unavailable or incompatible with the current sequence
    private void validateReferenceState(FrameAssembly assembly, ObuPacket packet)
            throws DecodeException {
        SequenceHeader currentSequence = assembly.sequenceHeader();
        FrameHeader frameHeader = assembly.frameHeader();
        if (frameHeader.frameType() != FrameType.INTER && frameHeader.frameType() != FrameType.SWITCH) {
            return;
        }
        for (int referenceIndex = 0; referenceIndex < 7; referenceIndex++) {
            int slotIndex = frameHeader.referenceFrameIndex(referenceIndex);
            if (slotIndex < 0 || slotIndex >= referenceSlots.length) {
                throw invalidBitstream(packet, "Selected reference frame does not identify a valid slot");
            }
            @Nullable ReferenceFrameSyntaxState storedState = referenceSlots[slotIndex].syntaxState();
            if (storedState == null) {
                throw invalidBitstream(packet, "Selected reference frame slot is not populated");
            }
            SequenceHeader storedSequence = storedState.sequenceHeader();
            if (storedSequence.profile() != currentSequence.profile()) {
                throw invalidBitstream(packet, "Selected reference frame profile differs from the current sequence");
            }
            if (!compatibleReferenceColorConfig(storedSequence.colorConfig(), currentSequence.colorConfig())) {
                throw invalidBitstream(
                        packet,
                        "Selected reference frame bit depth or color configuration differs from the current sequence"
                );
            }
        }
    }

    /// Returns whether two sequence color configurations may share reference frames.
    ///
    /// @param stored the color configuration used by the stored reference frame
    /// @param current the current sequence color configuration
    /// @return whether every reference-relevant color property matches
    private static boolean compatibleReferenceColorConfig(
            Av1ColorConfig stored,
            Av1ColorConfig current
    ) {
        return stored.bitDepth() == current.bitDepth()
                && stored.monochrome() == current.monochrome()
                && stored.colorPrimaries() == current.colorPrimaries()
                && stored.transferCharacteristics() == current.transferCharacteristics()
                && stored.matrixCoefficients() == current.matrixCoefficients()
                && stored.colorRange() == current.colorRange()
                && stored.chromaFormat() == current.chromaFormat()
                && stored.chromaSamplePosition() == current.chromaSamplePosition();
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

    /// Converts a header-only pending frame into the common Large Scale Tile camera frame at a
    /// temporal-unit boundary.
    ///
    /// @param packet the temporal delimiter that ended the camera-header temporal unit
    /// @throws DecodeException if a partially tiled frame reached the boundary
    private void retainLargeScaleTileCameraHeaderAtBoundary(ObuPacket packet) throws DecodeException {
        if (pendingFrameAssembly == null) {
            return;
        }
        if (config.largeScaleTileMode() && pendingFrameAssembly.tileGroupCount() == 0) {
            retainLargeScaleTileCameraAssembly(pendingFrameAssembly, packet);
            pendingFrameAssembly = null;
            return;
        }
        ensureNoPendingFrameAssembly(
                packet,
                "Temporal delimiter appeared before the current frame was completed"
        );
    }

    /// Retains a header-only pending camera frame immediately before a tile-list OBU.
    ///
    /// @param packet the tile-list OBU requiring the camera frame
    /// @throws DecodeException if the pending frame contains tile data or no camera header exists
    private void retainLargeScaleTileCameraHeader(ObuPacket packet) throws DecodeException {
        if (pendingFrameAssembly != null) {
            if (pendingFrameAssembly.tileGroupCount() != 0) {
                throw invalidBitstream(packet, "Tile-list OBU appeared while a tiled frame was incomplete");
            }
            retainLargeScaleTileCameraAssembly(pendingFrameAssembly, packet);
            pendingFrameAssembly = null;
        }
        if (largeScaleTileCameraAssembly == null) {
            throw invalidBitstream(packet, "Tile-list OBU appeared before a common camera frame header");
        }
    }

    /// Validates and retains one common Large Scale Tile camera frame.
    ///
    /// @param assembly the header-only camera-frame assembly
    /// @param packet the OBU used for error context
    /// @throws DecodeException if strict conformance validation rejects the camera frame
    private void retainLargeScaleTileCameraAssembly(
            FrameAssembly assembly,
            ObuPacket packet
    ) throws DecodeException {
        tileListParser.validateCameraFrame(
                packet,
                assembly.sequenceHeader(),
                assembly.frameHeader(),
                config.strictStdCompliance()
        );
        largeScaleTileCameraAssembly = assembly;
        ReferenceFrameSyntaxState[] referenceSyntaxStates = referenceFrameSyntaxStatesForDecoding();
        System.arraycopy(
                referenceSyntaxStates,
                0,
                largeScaleTileCameraReferenceSyntaxStates,
                0,
                referenceSyntaxStates.length
        );
        largeScaleTileCameraCdfReferenceState = selectCdfReferenceFrameSyntaxState(assembly.frameHeader());
    }

    /// Decodes and assembles one Large Scale Tile list output.
    ///
    /// @param packet the tile-list OBU
    /// @return the assembled presentation output
    /// @throws DecodeException if the list references unavailable anchors or contains invalid tile syntax
    private PendingOutput decodeLargeScaleTileList(ObuPacket packet) throws DecodeException {
        FrameAssembly cameraAssembly = Objects.requireNonNull(
                largeScaleTileCameraAssembly,
                "largeScaleTileCameraAssembly"
        );
        SequenceHeader cameraSequenceHeader = cameraAssembly.sequenceHeader();
        FrameHeader cameraFrameHeader = cameraAssembly.frameHeader();
        TileList tileList = tileListParser.parse(
                packet,
                cameraSequenceHeader,
                cameraFrameHeader,
                config.strictStdCompliance()
        );

        FrameHeader.TilingInfo tiling = cameraFrameHeader.tiling();
        int superblockSize = cameraSequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int tileWidth = (tiling.columnStartSuperblocks()[1] - tiling.columnStartSuperblocks()[0])
                * superblockSize;
        int tileHeight = (tiling.rowStartSuperblocks()[1] - tiling.rowStartSuperblocks()[0])
                * superblockSize;
        int outputWidth;
        int outputHeight;
        try {
            outputWidth = Math.multiplyExact(tileWidth, tileList.outputTileColumns());
            outputHeight = Math.multiplyExact(tileHeight, tileList.outputTileRows());
        } catch (ArithmeticException exception) {
            throw invalidBitstream(packet, "Large Scale Tile output dimensions overflow");
        }
        enforceFrameSizeLimit(outputWidth, outputHeight, packet);

        LargeScaleTileOutputBuilder outputBuilder = new LargeScaleTileOutputBuilder(
                cameraSequenceHeader.colorConfig().bitDepth(),
                cameraSequenceHeader.colorConfig().chromaFormat(),
                tileWidth,
                tileHeight,
                tileList.outputTileColumns(),
                tileList.outputTileRows()
        );
        int lastReferenceSlot = cameraFrameHeader.referenceFrameIndex(0);
        if (lastReferenceSlot < 0 || lastReferenceSlot >= referenceSlots.length) {
            throw invalidBitstream(packet, "Large Scale Tile camera frame does not select a valid LAST slot");
        }

        List<TileList.Entry> entries = tileList.entries();
        List<LargeScaleTileSyntaxKey> syntaxKeys = new ArrayList<>(entries.size());
        Map<LargeScaleTileSyntaxKey, Integer> remainingSyntaxUses = new HashMap<>();
        for (TileList.Entry entry : entries) {
            LargeScaleTileSyntaxKey syntaxKey = new LargeScaleTileSyntaxKey(entry.bitstream());
            syntaxKeys.add(syntaxKey);
            remainingSyntaxUses.merge(syntaxKey, 1, Integer::sum);
        }
        Map<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult> decodedTileSyntax = new LinkedHashMap<>();
        Map<LargeScaleTileDecodeKey, Integer> decodedTileOutputIndices = new HashMap<>();
        for (int outputTileIndex = 0; outputTileIndex < entries.size(); outputTileIndex++) {
            TileList.Entry entry = entries.get(outputTileIndex);
            if (entry.anchorFrameIndex() >= largeScaleTileAnchorFrames.size()) {
                throw invalidBitstream(
                        packet,
                        "Tile-list entry references unavailable anchor frame " + entry.anchorFrameIndex()
                );
            }
            LargeScaleTileSyntaxKey syntaxKey = syntaxKeys.get(outputTileIndex);
            int remainingSyntaxUseCount = Objects.requireNonNull(
                    remainingSyntaxUses.get(syntaxKey),
                    "remainingSyntaxUses"
            );
            LargeScaleTileDecodeKey decodeKey = new LargeScaleTileDecodeKey(
                    entry.anchorFrameIndex(),
                    syntaxKey
            );
            @Nullable Integer decodedOutputTileIndex = decodedTileOutputIndices.get(decodeKey);
            if (decodedOutputTileIndex != null) {
                outputBuilder.copyOutputTile(decodedOutputTileIndex, outputTileIndex);
                finishLargeScaleTileSyntaxUse(
                        syntaxKey,
                        remainingSyntaxUseCount,
                        remainingSyntaxUses,
                        decodedTileSyntax
                );
                continue;
            }
            ReferenceSurfaceSnapshot anchor = largeScaleTileAnchorFrames.get(entry.anchorFrameIndex());
            ReferenceSurfaceSnapshot[] referenceSurfaces = new ReferenceSurfaceSnapshot[referenceSlots.length];
            referenceSurfaces[lastReferenceSlot] = anchor;

            int sourceTileIndex = entry.bitstream().tileIndex();
            @Nullable FrameSyntaxDecodeResult cachedSyntaxDecodeResult = decodedTileSyntax.get(syntaxKey);
            FrameSyntaxDecodeResult syntaxDecodeResult;
            if (cachedSyntaxDecodeResult != null) {
                syntaxDecodeResult = cachedSyntaxDecodeResult;
            } else {
                FrameAssembly tileAssembly = new FrameAssembly(
                        cameraSequenceHeader,
                        cameraFrameHeader,
                        cameraReferenceFrameHeaders(cameraAssembly),
                        packet.streamOffset(),
                        packet.obuIndex()
                );
                TileGroupHeader tileHeader = new TileGroupHeader(
                        false,
                        sourceTileIndex,
                        sourceTileIndex,
                        tileAssembly.totalTiles()
                );
                tileAssembly.addTileGroup(
                        packet,
                        tileHeader,
                        entry.bitstream().dataOffset(),
                        entry.bitstream().dataLength(),
                        new TileBitstream[]{entry.bitstream()}
                );

                ReferenceFrameSyntaxState[] referenceSyntaxStates = Arrays.copyOf(
                        largeScaleTileCameraReferenceSyntaxStates,
                        largeScaleTileCameraReferenceSyntaxStates.length
                );
                try {
                    syntaxDecodeResult = new FrameSyntaxDecoder(
                            largeScaleTileCameraCdfReferenceState,
                            referenceSyntaxStates,
                            config.strictStdCompliance()
                    ).decodeTile(tileAssembly, sourceTileIndex);
                } catch (InvalidFrameSyntaxException exception) {
                    throw new DecodeException(
                            DecodeErrorCode.INVALID_BITSTREAM,
                            DecodeStage.FRAME_DECODE,
                            exception.getMessage(),
                            packet.streamOffset(),
                            packet.obuIndex(),
                            null,
                            exception
                    );
                }
                if (config.strictStdCompliance()) {
                    validateLargeScaleTileReferences(syntaxDecodeResult, sourceTileIndex, packet);
                }
                if (remainingSyntaxUseCount > 1) {
                    cacheLargeScaleTileSyntax(decodedTileSyntax, syntaxKey, syntaxDecodeResult);
                }
            }
            lastFrameSyntaxDecodeResult = syntaxDecodeResult;
            finishLargeScaleTileSyntaxUse(
                    syntaxKey,
                    remainingSyntaxUseCount,
                    remainingSyntaxUses,
                    decodedTileSyntax
            );

            DecodedPlanes reconstructed;
            try {
                reconstructed = frameReconstructor.reconstructTile(
                        syntaxDecodeResult,
                        referenceSurfaces,
                        sourceTileIndex,
                        config.strictStdCompliance()
                );
            } catch (InvalidFrameReconstructionException exception) {
                throw new DecodeException(
                        DecodeErrorCode.INVALID_BITSTREAM,
                        DecodeStage.FRAME_DECODE,
                        exception.getMessage(),
                        packet.streamOffset(),
                        packet.obuIndex(),
                        null,
                        exception
                );
            }
            outputBuilder.copyTile(
                    reconstructed,
                    0,
                    0,
                    outputTileIndex
            );
            decodedTileOutputIndices.put(decodeKey, outputTileIndex);
        }

        DecodedPlanes outputPlanes = outputBuilder.build();
        lastPlanes = outputPlanes;
        return PendingOutput.normal(
                outputPlanes,
                cameraSequenceHeader.colorConfig(),
                cameraFrameHeader,
                true,
                nextPresentationIndex,
                packet
        );
    }

    /// Retains one decoded syntax tree in the bounded insertion-order reuse cache.
    ///
    /// @param cache the mutable syntax cache
    /// @param syntaxKey the exact compressed tile syntax identity
    /// @param syntaxDecodeResult the decoded syntax tree to retain
    private static void cacheLargeScaleTileSyntax(
            Map<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult> cache,
            LargeScaleTileSyntaxKey syntaxKey,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        if (cache.size() >= LARGE_SCALE_TILE_SYNTAX_CACHE_SIZE) {
            LargeScaleTileSyntaxKey oldestKey = cache.keySet().iterator().next();
            cache.remove(oldestKey);
        }
        cache.put(syntaxKey, syntaxDecodeResult);
    }

    /// Records one syntax-key use and releases state after its final occurrence.
    ///
    /// @param syntaxKey the exact compressed tile syntax identity
    /// @param remainingUseCount the use count before consuming the current entry
    /// @param remainingUses the mutable remaining-use counts
    /// @param cache the mutable decoded syntax cache
    private static void finishLargeScaleTileSyntaxUse(
            LargeScaleTileSyntaxKey syntaxKey,
            int remainingUseCount,
            Map<LargeScaleTileSyntaxKey, Integer> remainingUses,
            Map<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult> cache
    ) {
        if (remainingUseCount <= 1) {
            remainingUses.remove(syntaxKey);
            cache.remove(syntaxKey);
        } else {
            remainingUses.put(syntaxKey, remainingUseCount - 1);
        }
    }

    /// Verifies that every inter block in one Large Scale Tile uses `LAST_FRAME`.
    ///
    /// @param syntaxDecodeResult the decoded camera-tile syntax
    /// @param tileIndex the decoded source tile index
    /// @param packet the source tile-list OBU
    /// @throws DecodeException if a block selects another reference frame
    private static void validateLargeScaleTileReferences(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int tileIndex,
            ObuPacket packet
    ) throws DecodeException {
        for (TilePartitionTreeReader.Node root : syntaxDecodeResult.tileRoots(tileIndex)) {
            validateLargeScaleTileReferences(root, packet);
        }
    }

    /// Verifies the reference selection in one decoded partition subtree.
    ///
    /// @param node the decoded partition node
    /// @param packet the source tile-list OBU
    /// @throws DecodeException if an inter leaf does not use `LAST_FRAME`
    private static void validateLargeScaleTileReferences(
            TilePartitionTreeReader.Node node,
            ObuPacket packet
    ) throws DecodeException {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            int referenceFrame = leafNode.header().referenceFrame0();
            if (referenceFrame >= 0 && referenceFrame != 0) {
                throw invalidBitstream(packet, "Large Scale Tile blocks must use LAST_FRAME");
            }
            return;
        }
        TilePartitionTreeReader.PartitionNode partitionNode =
                (TilePartitionTreeReader.PartitionNode) node;
        for (int childIndex = 0; childIndex < partitionNode.childCount(); childIndex++) {
            validateLargeScaleTileReferences(partitionNode.child(childIndex), packet);
        }
    }

    /// Reconstructs the slot-indexed reference-header snapshot captured by one camera assembly.
    ///
    /// @param cameraAssembly the retained common camera-frame assembly
    /// @return the selected reference headers indexed by runtime slot
    private FrameHeader[] cameraReferenceFrameHeaders(FrameAssembly cameraAssembly) {
        FrameHeader cameraFrameHeader = cameraAssembly.frameHeader();
        FrameHeader[] headers = new FrameHeader[referenceSlots.length];
        for (int referenceFrame = 0; referenceFrame < 7; referenceFrame++) {
            int slot = cameraFrameHeader.referenceFrameIndex(referenceFrame);
            if (slot >= 0 && slot < headers.length) {
                headers[slot] = cameraAssembly.referenceFrameHeader(referenceFrame);
            }
        }
        return headers;
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
        if (!referenceSlots[existingFrameIndex].isPopulated()) {
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

    /// Prepares one `show_existing_frame` output from the requested reference slot, or `null` when
    /// current public filtering suppresses presentation.
    ///
    /// The output reuses the reconstructed surface atomically stored with the slot's syntax state.
    /// Showing a stored key frame refreshes every reference slot before presentation filtering.
    ///
    /// @param packet the source OBU packet that requested `show_existing_frame`
    /// @param outputRequestHeader the current show-existing-frame request header
    /// @return one pending `show_existing_frame` output, or `null` when output filtering suppresses it
    /// @throws DecodeException if the referenced slot is invalid or missing complete reference state
    private @Nullable PendingOutput outputExistingFrame(
            ObuPacket packet,
            FrameHeader outputRequestHeader
    ) throws DecodeException {
        int existingFrameIndex = outputRequestHeader.existingFrameIndex();
        requireExistingFrameState(packet, existingFrameIndex);
        RuntimeReferenceSlot slot = referenceSlots[existingFrameIndex];
        FrameHeader referencedFrameHeader = Objects.requireNonNull(slot.frameHeader(), "referencedFrameHeader");
        if (config.strictStdCompliance() && !referencedFrameHeader.showableFrame()) {
            throw invalidBitstream(packet, "show_existing_frame references a frame that is not showable");
        }
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = Objects.requireNonNull(
                slot.surfaceSnapshot(),
                "populated reference slot"
        );
        if (config.largeScaleTileMode()) {
            addLargeScaleTileAnchorFrame(referenceSurfaceSnapshot, packet);
        }
        refreshReferenceState(outputRequestHeader, referenceSurfaceSnapshot);
        if (config.largeScaleTileMode()) {
            return null;
        }
        if (!FrameOutputPolicy.shouldOutputExistingFrame(referencedFrameHeader, config)) {
            return null;
        }
        DecodedPlanes presentationPlanes = applyPresentationFilters(
                referenceSurfaceSnapshot.decodedPlanes(),
                referencedFrameHeader,
                referenceSurfaceSnapshot.frameSyntaxState().sequenceHeader().colorConfig()
        );
        lastPlanes = presentationPlanes;
        return PendingOutput.existing(
                presentationPlanes,
                referenceSurfaceSnapshot,
                outputRequestHeader,
                nextPresentationIndex,
                packet
        );
    }

    /// Appends one externally indexed anchor surface for later tile-list decoding.
    ///
    /// @param snapshot the immutable anchor surface
    /// @param packet the OBU that produced or showed the anchor
    /// @throws DecodeException if the AV1 maximum of 128 anchor frames is exceeded
    private void addLargeScaleTileAnchorFrame(
            ReferenceSurfaceSnapshot snapshot,
            ObuPacket packet
    ) throws DecodeException {
        if (largeScaleTileAnchorFrames.size() >= 128) {
            throw invalidBitstream(packet, "Large Scale Tile anchor-frame count exceeds 128");
        }
        largeScaleTileAnchorFrames.add(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /// Applies presentation-only output filters such as film grain.
    ///
    /// Stored reference surfaces remain post-filter, post-super-resolution, and pre-grain.
    /// Presentation output may use a grain-applied copy when the current decoder configuration
    /// requests it.
    ///
    /// @param decodedPlanes the post-filter, post-super-resolution, pre-grain planes
    /// @param frameHeader the normalized frame header that owns the output
    /// @param colorConfig the sequence color configuration associated with the output surface
    /// @return the presentation planes after output-only processing
    private DecodedPlanes applyPresentationFilters(
            DecodedPlanes decodedPlanes,
            FrameHeader frameHeader,
            Av1ColorConfig colorConfig
    ) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        Av1ColorConfig checkedColorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
        if (FrameOutputPolicy.requiresFilmGrainSynthesis(checkedFrameHeader, config)) {
            return filmGrainSynthesizer.apply(checkedDecodedPlanes, checkedFrameHeader, checkedColorConfig);
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

    /// Returns a contextual checked failure for an unsupported output color conversion.
    ///
    /// @param packet the OBU whose frame reached output conversion
    /// @param exception the unsupported conversion failure
    /// @return the contextual unsupported-feature exception
    private static DecodeException unsupportedOutputConversion(
            ObuPacket packet,
            UnsupportedOperationException exception
    ) {
        return new DecodeException(
                DecodeErrorCode.UNSUPPORTED_FEATURE,
                DecodeStage.OUTPUT_CONVERSION,
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "AV1 output uses an unsupported color conversion",
                packet.streamOffset(),
                packet.obuIndex(),
                null,
                exception
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
        enforceFrameSizeLimit(
                frameHeader.frameSize().upscaledWidth(),
                frameHeader.frameSize().height(),
                packet
        );
    }

    /// Enforces the configured frame size limit against explicit output dimensions.
    ///
    /// @param width the output luma width
    /// @param height the output luma height
    /// @param packet the source OBU packet
    /// @throws DecodeException if the configured frame size limit is exceeded
    private void enforceFrameSizeLimit(int width, int height, ObuPacket packet) throws DecodeException {
        long frameSizeLimit = config.frameSizeLimit();
        if (frameSizeLimit == 0) {
            return;
        }
        long pixelCount = (long) width * height;
        if (pixelCount > frameSizeLimit) {
            throw new DecodeException(
                    DecodeErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                    DecodeStage.FRAME_HEADER_PARSE,
                    "Frame size exceeds the configured limit: " + width + "x" + height,
                    packet.streamOffset(),
                    packet.obuIndex(),
                    null
            );
        }
    }

    /// Returns the compact reference state whose saved CDF should seed the next frame.
    ///
    /// AV1 inherits entropy state from `primary_ref_frame` when it is present. When
    /// `primary_ref_frame == PRIMARY_REF_NONE`, no reference CDF state is inherited.
    ///
    /// @param frameHeader the parsed frame header for the next frame
    /// @return the compact reference state that should seed the next frame, or `null`
    private @Nullable ReferenceFrameSyntaxState selectCdfReferenceFrameSyntaxState(FrameHeader frameHeader) {
        int primaryRefFrame = frameHeader.primaryRefFrame();
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null;
        }

        int primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame);
        if (primarySlot < 0 || primarySlot >= referenceSlots.length) {
            return null;
        }
        return referenceSlots[primarySlot].syntaxState();
    }

    /// Creates the compact syntax state stored in refreshed reference slots.
    ///
    /// When `refresh_context` is disabled the refreshed slots keep their inherited entropy state,
    /// while still receiving the current frame's segment identifiers and temporal motion field.
    ///
    /// @param frameHeader the parsed frame header whose refresh flags will be applied
    /// @param syntaxDecodeResult the structural frame-decode result produced for the current frame
    /// @param cdfReferenceState the inherited compact reference state, or `null`
    /// @return the compact syntax state to store in refreshed reference slots
    private ReferenceFrameSyntaxState storedReferenceFrameSyntaxState(
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult,
            @Nullable ReferenceFrameSyntaxState cdfReferenceState
    ) {
        CdfContext storedFrameCdfContext = frameHeader.refreshContext()
                ? syntaxDecodeResult.savedFrameCdfContext()
                : cdfReferenceState != null
                ? cdfReferenceState.savedFrameCdfContext()
                : CdfContext.createDefault(frameHeader.quantization().baseQIndex());
        return ReferenceFrameSyntaxState.from(syntaxDecodeResult, storedFrameCdfContext);
    }

    /// Atomically refreshes any reference slots targeted by the parsed frame header.
    ///
    /// Only successfully reconstructed and postprocessed frames populate these slots, so a failed
    /// frame never leaves partially updated parser or surface state.
    ///
    /// @param frameHeader the parsed frame header whose refresh flags should be applied
    /// @param referenceSurfaceSnapshot the reconstructed reference surface to store
    private void refreshReferenceState(
            FrameHeader frameHeader,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        int refreshFrameFlags = frameHeader.refreshFrameFlags();
        for (int i = 0; i < referenceSlots.length; i++) {
            if ((refreshFrameFlags & (1 << i)) != 0) {
                referenceSlots[i].refresh(referenceSurfaceSnapshot);
            }
        }
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

    /// Returns the current compact reference syntax states as one slot-indexed array.
    ///
    /// @return the current compact reference syntax states as one slot-indexed array
    private ReferenceFrameSyntaxState[] referenceFrameSyntaxStatesForDecoding() {
        ReferenceFrameSyntaxState[] states = new ReferenceFrameSyntaxState[referenceSlots.length];
        for (int i = 0; i < referenceSlots.length; i++) {
            states[i] = referenceSlots[i].syntaxState();
        }
        return states;
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

    /// Identifies one exactly reusable decoded tile syntax result within a single Tile List OBU.
    ///
    /// Syntax decoding depends on the selected camera tile and compressed payload, but not on the
    /// anchor's pixel surface. Reconstruction applies that surface separately.
    @NotNullByDefault
    private static final class LargeScaleTileSyntaxKey {
        /// The immutable compressed camera-frame tile payload view.
        private final TileBitstream bitstream;
        /// The precomputed content hash code.
        private final int hashCode;

        /// Creates an exact tile-syntax reuse key.
        ///
        /// @param bitstream the compressed camera-frame tile payload
        private LargeScaleTileSyntaxKey(TileBitstream bitstream) {
            this.bitstream = Objects.requireNonNull(bitstream, "bitstream");
            this.hashCode = 31 * (31 + bitstream.tileIndex()) + bitstream.payloadHashCode();
        }

        /// Returns whether another key selects the same source tile and payload bytes.
        ///
        /// @param other the object to compare, or `null`
        /// @return whether the keys describe the same syntax decoding operation
        @Override
        public boolean equals(@Nullable Object other) {
            return this == other
                    || other instanceof LargeScaleTileSyntaxKey key
                    && bitstream.tileIndex() == key.bitstream.tileIndex()
                    && bitstream.hasSamePayload(key.bitstream);
        }

        /// Returns the precomputed content hash code.
        ///
        /// @return the content hash code
        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    /// Identifies one exactly reusable reconstructed tile within a single Tile List OBU.
    ///
    /// The key adds the anchor selection to an exact syntax key, so reuse cannot change either the
    /// compressed tile syntax or its reference surface.
    @NotNullByDefault
    private static final class LargeScaleTileDecodeKey {
        /// The externally indexed anchor frame used as `LAST_FRAME`.
        private final int anchorFrameIndex;
        /// The exact compressed tile syntax identity.
        private final LargeScaleTileSyntaxKey syntaxKey;
        /// The precomputed content hash code.
        private final int hashCode;

        /// Creates an exact decoded-tile reuse key.
        ///
        /// @param anchorFrameIndex the externally indexed anchor frame
        /// @param syntaxKey the exact compressed tile syntax identity
        private LargeScaleTileDecodeKey(int anchorFrameIndex, LargeScaleTileSyntaxKey syntaxKey) {
            this.anchorFrameIndex = anchorFrameIndex;
            this.syntaxKey = Objects.requireNonNull(syntaxKey, "syntaxKey");
            this.hashCode = 31 * (31 + anchorFrameIndex) + syntaxKey.hashCode();
        }

        /// Returns whether another key selects the same anchor, source tile, and payload bytes.
        ///
        /// @param other the object to compare, or `null`
        /// @return whether the keys describe the same decoding operation
        @Override
        public boolean equals(@Nullable Object other) {
            return this == other
                    || other instanceof LargeScaleTileDecodeKey key
                    && anchorFrameIndex == key.anchorFrameIndex
                    && syntaxKey.equals(key.syntaxKey);
        }

        /// Returns the precomputed content hash code.
        ///
        /// @return the content hash code
        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    /// Holds one decoded presentation output before optional RGB conversion.
    ///
    /// @param planes the postprocessed presentation planes
    /// @param colorConfig the sequence color configuration for a newly decoded frame, or `null` for
    ///                    `show_existing_frame`
    /// @param frameHeader the decoded frame header or current `show_existing_frame` request
    /// @param showFrame whether a newly decoded frame was directly displayable
    /// @param presentationIndex the zero-based presentation index
    /// @param packet the OBU that completed or requested the output
    /// @param existingSurface the referenced surface for `show_existing_frame`, or `null` for a
    ///                        newly decoded frame
    @NotNullByDefault
    private record PendingOutput(
            DecodedPlanes planes,
            @Nullable Av1ColorConfig colorConfig,
            FrameHeader frameHeader,
            boolean showFrame,
            long presentationIndex,
            ObuPacket packet,
            @Nullable ReferenceSurfaceSnapshot existingSurface
    ) {
        /// Creates one output for a newly decoded frame.
        ///
        /// @param planes the postprocessed presentation planes
        /// @param colorConfig the active sequence color configuration
        /// @param frameHeader the decoded frame header
        /// @param showFrame whether the frame was directly displayable
        /// @param presentationIndex the zero-based presentation index
        /// @param packet the OBU that completed the output
        /// @return one pending output for a newly decoded frame
        private static PendingOutput normal(
                DecodedPlanes planes,
                Av1ColorConfig colorConfig,
                FrameHeader frameHeader,
                boolean showFrame,
                long presentationIndex,
                ObuPacket packet
        ) {
            return new PendingOutput(
                    Objects.requireNonNull(planes, "planes"),
                    Objects.requireNonNull(colorConfig, "colorConfig"),
                    Objects.requireNonNull(frameHeader, "frameHeader"),
                    showFrame,
                    presentationIndex,
                    Objects.requireNonNull(packet, "packet"),
                    null
            );
        }

        /// Creates one output that presents a stored reference surface.
        ///
        /// @param planes the postprocessed presentation planes
        /// @param existingSurface the stored reference surface
        /// @param outputRequestHeader the current `show_existing_frame` request
        /// @param presentationIndex the zero-based presentation index
        /// @param packet the OBU that requested the output
        /// @return one pending output for a stored reference surface
        private static PendingOutput existing(
                DecodedPlanes planes,
                ReferenceSurfaceSnapshot existingSurface,
                FrameHeader outputRequestHeader,
                long presentationIndex,
                ObuPacket packet
        ) {
            return new PendingOutput(
                    Objects.requireNonNull(planes, "planes"),
                    null,
                    Objects.requireNonNull(outputRequestHeader, "outputRequestHeader"),
                    false,
                    presentationIndex,
                    Objects.requireNonNull(packet, "packet"),
                    Objects.requireNonNull(existingSurface, "existingSurface")
            );
        }

        /// Converts this pending output to the public packed-pixel frame representation.
        ///
        /// @return the converted public frame
        /// @throws UnsupportedOperationException if the color configuration cannot be converted
        private DecodedFrame createFrame() {
            if (existingSurface != null) {
                return OutputFrameFactory.createExistingFrame(
                        planes,
                        existingSurface,
                        frameHeader,
                        presentationIndex
                );
            }
            return OutputFrameFactory.createFrame(
                    planes,
                    Objects.requireNonNull(colorConfig, "colorConfig"),
                    frameHeader,
                    showFrame,
                    presentationIndex
            );
        }
    }

    /// The immediate result of parsing one combined `FRAME` OBU.
    ///
    /// Combined frames either start a normal `FrameAssembly` or resolve immediately through the
    /// `show_existing_frame` output path.
    @NotNullByDefault
    private static final class CombinedFrameStart {
        /// The started frame assembly, or `null` when output resolved immediately.
        private final @Nullable FrameAssembly frameAssembly;

        /// The immediate output, or `null` when normal frame assembly should continue.
        private final @Nullable PendingOutput immediateOutput;

        /// Whether this combined frame resolved immediately through `show_existing_frame`.
        private final boolean resolvedImmediately;

        /// Creates one combined-frame start result.
        ///
        /// @param frameAssembly the started frame assembly, or `null`
        /// @param immediateOutput the immediate output frame, or `null`
        /// @param resolvedImmediately whether this combined frame resolved immediately
        private CombinedFrameStart(
                @Nullable FrameAssembly frameAssembly,
                @Nullable PendingOutput immediateOutput,
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
        /// @param immediateOutput the immediate output, or `null` when filtering suppresses output
        /// @return one result that resolves immediately to output
        private static CombinedFrameStart immediateOutput(@Nullable PendingOutput immediateOutput) {
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

        /// Returns the immediate output, or `null`.
        ///
        /// @return the immediate output, or `null`
        private @Nullable PendingOutput immediateOutput() {
            return immediateOutput;
        }
    }
}
