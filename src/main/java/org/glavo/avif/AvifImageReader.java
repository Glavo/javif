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

import org.glavo.avif.decode.ArgbIntFrame;
import org.glavo.avif.decode.ArgbLongFrame;
import org.glavo.avif.decode.Av1ImageReader;
import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.internal.bmff.AvifContainer;
import org.glavo.avif.internal.bmff.AvifContainerParser;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// High-level reader for AVIF images.
@NotNullByDefault
public final class AvifImageReader implements AutoCloseable {
    /// The immutable decoder configuration.
    private final AvifDecoderConfig config;
    /// The parsed container data.
    private final AvifContainer container;
    /// The next frame index for sequential reads.
    private int nextFrameIndex;
    /// Whether this reader has been closed.
    private boolean closed;

    /// Creates an AVIF image reader.
    ///
    /// @param source the complete AVIF source bytes
    /// @param config the immutable decoder configuration
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    private AvifImageReader(byte[] source, AvifDecoderConfig config) throws AvifDecodeException {
        this.config = Objects.requireNonNull(config, "config");
        this.container = AvifContainerParser.parse(Objects.requireNonNull(source, "source"));
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// @param source the complete AVIF source bytes
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(byte[] source) throws AvifDecodeException {
        return open(source, AvifDecoderConfig.DEFAULT);
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// @param source the complete AVIF source bytes
    /// @param config the immutable decoder configuration
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(byte[] source, AvifDecoderConfig config) throws AvifDecodeException {
        return new AvifImageReader(Objects.requireNonNull(source, "source").clone(), config);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// @param source the source byte buffer, read from its current position to its limit
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(ByteBuffer source) throws AvifDecodeException {
        return open(source, AvifDecoderConfig.DEFAULT);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// @param source the source byte buffer, read from its current position to its limit
    /// @param config the immutable decoder configuration
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(ByteBuffer source, AvifDecoderConfig config) throws AvifDecodeException {
        ByteBuffer copy = Objects.requireNonNull(source, "source").slice();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return open(bytes, config);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// @param source the source input stream
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(InputStream source) throws IOException {
        return open(source, AvifDecoderConfig.DEFAULT);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// @param source the source input stream
    /// @param config the immutable decoder configuration
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(InputStream source, AvifDecoderConfig config) throws IOException {
        return open(Objects.requireNonNull(source, "source").readAllBytes(), config);
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// @param source the source byte channel
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(ReadableByteChannel source) throws IOException {
        return open(source, AvifDecoderConfig.DEFAULT);
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// @param source the source byte channel
    /// @param config the immutable decoder configuration
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(ReadableByteChannel source, AvifDecoderConfig config) throws IOException {
        ReadableByteChannel checkedSource = Objects.requireNonNull(source, "source");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (true) {
            int read = checkedSource.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("ReadableByteChannel made no progress while reading AVIF input");
            }
            buffer.flip();
            output.writeBytes(bufferToArray(buffer));
            buffer.clear();
        }
        return open(output.toByteArray(), config);
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// @param source the source file path
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(Path source) throws IOException {
        return open(source, AvifDecoderConfig.DEFAULT);
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// @param source the source file path
    /// @param config the immutable decoder configuration
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(Path source, AvifDecoderConfig config) throws IOException {
        return open(Files.readAllBytes(Objects.requireNonNull(source, "source")), config);
    }

    /// Returns immutable image metadata parsed from the container.
    ///
    /// @return immutable image metadata parsed from the container
    /// @throws AvifDecodeException if this reader is closed
    public AvifImageInfo info() throws AvifDecodeException {
        ensureOpen();
        return container.info();
    }

    /// Reads the next decoded frame.
    ///
    /// @return the next decoded frame, or `null` at end-of-stream
    /// @throws IOException if the frame cannot be decoded
    public @Nullable AvifFrame readFrame() throws IOException {
        ensureOpen();
        if (nextFrameIndex >= container.info().frameCount()) {
            return null;
        }
        AvifFrame frame = readFrame(nextFrameIndex);
        nextFrameIndex++;
        return frame;
    }

    /// Reads the decoded frame at the supplied index.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if the frame cannot be decoded
    public AvifFrame readFrame(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (frameIndex != 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF frame decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        try (Av1ImageReader colorReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(container.primaryItemPayload()).order(ByteOrder.LITTLE_ENDIAN)),
                config.av1DecoderConfig()
        )) {
            DecodedFrame colorFrame = colorReader.readFrame();
            if (colorFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item produced no frame", null);
            }
            byte @Nullable [] alphaPayload = container.alphaItemPayload();
            if (alphaPayload != null) {
                return adaptFrameWithAlpha(colorFrame, alphaPayload, frameIndex);
            }
            return adaptFrame(colorFrame, frameIndex);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Reads all decoded frames.
    ///
    /// @return all decoded frames
    /// @throws IOException if a frame cannot be decoded
    public @Unmodifiable List<AvifFrame> readAllFrames() throws IOException {
        ensureOpen();
        ArrayList<AvifFrame> frames = new ArrayList<>();
        while (true) {
            AvifFrame frame = readFrame();
            if (frame == null) {
                return List.copyOf(frames);
            }
            frames.add(frame);
        }
    }

    /// Closes this reader.
    @Override
    public void close() {
        closed = true;
    }

    /// Ensures that this reader is open.
    ///
    /// @throws AvifDecodeException if this reader is closed
    private void ensureOpen() throws AvifDecodeException {
        if (closed) {
            throw new AvifDecodeException(AvifErrorCode.CLOSED, "AvifImageReader is closed", null);
        }
    }

    /// Adapts an AV1 decoded frame to the AVIF public frame model.
    ///
    /// @param frame the decoded AV1 frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return an AVIF public frame
    private static AvifFrame adaptFrame(DecodedFrame frame, int frameIndex) {
        if (frame instanceof ArgbIntFrame intFrame) {
            return new AvifIntFrame(
                    intFrame.width(),
                    intFrame.height(),
                    intFrame.bitDepth(),
                    intFrame.pixelFormat(),
                    frameIndex,
                    intFrame.pixels()
            );
        }
        if (frame instanceof ArgbLongFrame longFrame) {
            return new AvifLongFrame(
                    longFrame.width(),
                    longFrame.height(),
                    longFrame.bitDepth(),
                    longFrame.pixelFormat(),
                    frameIndex,
                    longFrame.pixels()
            );
        }
        throw new IllegalArgumentException("Unsupported decoded frame class: " + frame.getClass().getName());
    }

    /// Decodes an alpha auxiliary AV1 payload and combines it with a decoded color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaPayload the alpha auxiliary AV1 OBU payload
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws IOException if the alpha payload cannot be decoded
    private AvifFrame adaptFrameWithAlpha(DecodedFrame colorFrame, byte[] alphaPayload, int frameIndex) throws IOException {
        try (Av1ImageReader alphaReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(ByteBuffer.wrap(alphaPayload).order(ByteOrder.LITTLE_ENDIAN)),
                config.av1DecoderConfig()
        )) {
            DecodedFrame alphaFrame = alphaReader.readFrame();
            if (alphaFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha auxiliary item produced no frame", null);
            }
            if (alphaFrame.width() != colorFrame.width() || alphaFrame.height() != colorFrame.height()) {
                throw unsupported("Alpha with different decoded dimensions than master image", null);
            }
            if (colorFrame instanceof ArgbIntFrame colorInt && alphaFrame instanceof ArgbIntFrame alphaInt) {
                return combineIntFrames(colorInt, alphaInt, frameIndex);
            }
            if (colorFrame instanceof ArgbLongFrame colorLong && alphaFrame instanceof ArgbLongFrame alphaLong) {
                return combineLongFrames(colorLong, alphaLong, frameIndex);
            }
            throw unsupported("Alpha and color frame pixel formats do not match", null);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Combines an 8-bit alpha frame into an 8-bit color frame yielding non-premultiplied ARGB.
    ///
    /// @param color the decoded color frame
    /// @param alpha the decoded alpha frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined frame
    private static AvifIntFrame combineIntFrames(ArgbIntFrame color, ArgbIntFrame alpha, int frameIndex) {
        int[] colorPixels = color.pixels();
        int[] alphaPixels = alpha.pixels();
        int pixelCount = colorPixels.length;
        if (alphaPixels.length != pixelCount) {
            throw new IllegalArgumentException("Alpha and color pixel counts do not match");
        }
        int[] combined = new int[pixelCount];
        for (int i = 0; i < pixelCount; i++) {
            int alphaLuma = alphaPixels[i] & 0xFF;
            combined[i] = (colorPixels[i] & 0x00FFFFFF) | (alphaLuma << 24);
        }
        return new AvifIntFrame(
                color.width(), color.height(), color.bitDepth(), color.pixelFormat(),
                frameIndex, combined
        );
    }

    /// Combines a 10/12-bit alpha frame into a 10/12-bit color frame yielding non-premultiplied ARGB.
    ///
    /// @param color the decoded color frame
    /// @param alpha the decoded alpha frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined frame
    private static AvifLongFrame combineLongFrames(ArgbLongFrame color, ArgbLongFrame alpha, int frameIndex) {
        long[] colorPixels = color.pixels();
        long[] alphaPixels = alpha.pixels();
        int pixelCount = colorPixels.length;
        if (alphaPixels.length != pixelCount) {
            throw new IllegalArgumentException("Alpha and color pixel counts do not match");
        }
        long[] combined = new long[pixelCount];
        for (int i = 0; i < pixelCount; i++) {
            long alphaLuma = alphaPixels[i] & 0xFFFFL;
            combined[i] = (colorPixels[i] & 0x0000FFFF_FFFFFFFFL) | (alphaLuma << 48);
        }
        return new AvifLongFrame(
                color.width(), color.height(), color.bitDepth(), color.pixelFormat(),
                frameIndex, combined
        );
    }

    /// Creates an unsupported-feature exception.
    ///
    /// @param message the failure message
    /// @param offset the byte offset or `null`
    /// @return an unsupported-feature exception
    private static AvifDecodeException unsupported(String message, @Nullable Long offset) {
        return new AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE, message, offset);
    }

    /// Copies remaining bytes from a buffer into a byte array.
    ///
    /// @param buffer the source buffer
    /// @return a byte array containing the buffer's remaining bytes
    private static byte[] bufferToArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
