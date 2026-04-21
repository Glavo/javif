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

import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuStreamReader;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.parse.FrameHeaderParser;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
    /// The most recently parsed sequence header.
    private @Nullable SequenceHeader sequenceHeader;
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
                return null;
            }

            ObuType type = packet.header().type();
            if (type == ObuType.SEQUENCE_HEADER) {
                sequenceHeader = sequenceHeaderParser.parse(packet, config.strictStdCompliance());
                continue;
            }
            if (type == ObuType.FRAME_HEADER) {
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
                FrameHeader frameHeader = frameHeaderParser.parse(packet, sequenceHeader, config.strictStdCompliance());
                enforceFrameSizeLimit(frameHeader, packet);
                throw new DecodeException(
                        DecodeErrorCode.NOT_IMPLEMENTED,
                        DecodeStage.FRAME_DECODE,
                        "AV1 frame decoding is not implemented yet",
                        packet.streamOffset(),
                        packet.obuIndex(),
                        null
                );
            }
            if (type == ObuType.FRAME || type == ObuType.TILE_GROUP) {
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
                throw new DecodeException(
                        DecodeErrorCode.NOT_IMPLEMENTED,
                        DecodeStage.FRAME_DECODE,
                        "Combined frame OBUs and tile group decoding are not implemented yet",
                        packet.streamOffset(),
                        packet.obuIndex(),
                        null
                );
            }
        }
    }

    /// Reads all decoded frames from the source until end-of-stream.
    ///
    /// @return all decoded frames from the source
    /// @throws IOException if the source is unreadable or the bitstream is malformed
    public List<DecodedFrame> readAllFrames() throws IOException {
        ensureOpen();

        List<DecodedFrame> frames = new ArrayList<>();
        while (true) {
            DecodedFrame frame = readFrame();
            if (frame == null) {
                return frames;
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

    /// Ensures that this reader has not already been closed.
    ///
    /// @throws IOException if this reader has already been closed
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Av1ImageReader is closed");
        }
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
}
