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

import org.glavo.avif.decode.Av1ImageReader;
import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.internal.av1.output.ArgbOutput;
import org.glavo.avif.internal.av1.output.YuvToRgbTransform;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
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
import java.nio.IntBuffer;
import java.nio.LongBuffer;
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
    /// A persistent AV1 reader for image sequences, or `null`.
    private @Nullable Av1ImageReader sequenceAv1Reader;
    /// The expected next frame index from the persistent sequence reader.
    private int sequenceAv1FrameIndex;
    /// A persistent AV1 reader for image-sequence alpha samples, or `null`.
    private @Nullable Av1ImageReader sequenceAlphaAv1Reader;
    /// The expected next frame index from the persistent sequence alpha reader.
    private int sequenceAlphaAv1FrameIndex;

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
        AvifFrame frame = container.isSequence()
                ? readSequenceFrameSequential(nextFrameIndex)
                : readFrame(nextFrameIndex);
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
        if (frameIndex != 0 && !container.isSequence()) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF frame decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        if (container.isSequence()) {
            return readSequenceFrameRandomAccess(frameIndex);
        }
        if (container.isGrid()) {
            return readGridFrame(frameIndex);
        }
        ByteBuffer primaryPayload = container.primaryItemPayload();
        if (primaryPayload == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        try (Av1ImageReader colorReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(primaryPayload),
                config.av1DecoderConfig()
        )) {
            DecodedFrame colorFrame = colorReader.readFrame();
            if (colorFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item produced no frame", null);
            }
            ByteBuffer alphaPayload = container.alphaItemPayload();
            AvifFrame rawFrame = adaptFrame(
                    colorFrame,
                    colorReader.lastPlanes(),
                    container.info().colorInfo(),
                    frameIndex,
                    config.rgbOutputMode()
            );
            if (alphaPayload != null) {
                rawFrame = adaptFrameWithAlpha(
                        rawFrame,
                        alphaPayload,
                        frameIndex,
                        container.info().alphaPremultiplied()
                );
            }
            return applyTransforms(rawFrame);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Reads raw decoded color planes for the frame at the supplied index.
    ///
    /// The returned planes expose the decoded AV1 color image before AVIF auxiliary alpha
    /// composition and before AVIF item transforms such as `clap`, `irot`, and `imir`.
    /// Grid-derived still images are returned as composed raw planes.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded color planes
    /// @throws IOException if the frame cannot be decoded
    public AvifPlanes readRawColorPlanes(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (container.isSequence()) {
            return readSequenceRawColorPlanes(frameIndex);
        }
        if (container.isGrid()) {
            return readGridRawColorPlanes();
        }
        if (frameIndex != 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF raw plane decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        ByteBuffer primaryPayload = container.primaryItemPayload();
        if (primaryPayload == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        return decodeRawColorPlanes(primaryPayload, "Primary AV1 item");
    }

    /// Reads raw decoded alpha auxiliary planes for the frame at the supplied index.
    ///
    /// The returned planes expose an alpha auxiliary AV1 image before AVIF item transforms are
    /// applied. A `null` return value means the frame has no alpha auxiliary image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded alpha auxiliary planes, or `null` when no alpha auxiliary image is present
    /// @throws IOException if the alpha auxiliary image cannot be decoded
    public @Nullable AvifPlanes readRawAlphaPlanes(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (!container.info().alphaPresent()) {
            return null;
        }
        if (container.isSequence()) {
            @Unmodifiable ByteBuffer @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
            if (alphaPayloads == null) {
                return null;
            }
            return alphaPlanesFromDecodedImage(readSequenceRawAuxiliaryPlanes(
                    frameIndex,
                    alphaPayloads,
                    "Alpha sequence frame"
            ));
        }
        if (frameIndex != 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF alpha plane decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        ByteBuffer alphaPayload = container.alphaItemPayload();
        if (alphaPayload != null) {
            return alphaPlanesFromDecodedImage(decodeRawColorPlanes(alphaPayload, "Alpha auxiliary AV1 item"));
        }
        @Unmodifiable ByteBuffer @Nullable [] alphaCellPayloads = container.gridAlphaCellPayloads();
        if (alphaCellPayloads != null) {
            return readGridRawAlphaPlanes(alphaCellPayloads);
        }
        return null;
    }

    /// Reads raw decoded gain-map planes for the frame at the supplied index.
    ///
    /// The returned planes expose the AV1 image referenced by the AVIF `tmap` gain-map
    /// association. A `null` return value means the frame has no gain-map image. The returned
    /// planes are not tone-mapped or applied to the base image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded gain-map planes, or `null` when no gain-map image is present
    /// @throws IOException if the gain-map image cannot be decoded
    public @Nullable AvifPlanes readRawGainMapPlanes(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        AvifGainMapInfo gainMapInfo = container.info().gainMapInfo();
        if (gainMapInfo == null) {
            return null;
        }
        if (container.isSequence()) {
            throw unsupported("Raw gain-map planes for AVIS sequences are not implemented", null);
        }
        if (frameIndex != 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF gain-map plane decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        ByteBuffer gainMapPayload = container.gainMapItemPayload();
        if (gainMapPayload != null) {
            return decodeRawColorPlanes(gainMapPayload, "Gain-map AV1 item");
        }
        @Unmodifiable ByteBuffer @Nullable [] gainMapCellPayloads = container.gainMapGridCellPayloads();
        if (gainMapCellPayloads != null) {
            return composeGridRawColorPlanes(
                    decodeGridRawColorPlanes(gainMapCellPayloads),
                    container.gainMapGridRows(),
                    container.gainMapGridColumns(),
                    container.gainMapGridOutputWidth(),
                    container.gainMapGridOutputHeight()
            );
        }
        throw unsupported("Gain-map item type is not decodable as AV1 planes: " + gainMapInfo.gainMapItemType(), null);
    }

    /// Reads raw decoded depth auxiliary planes for the frame at the supplied index.
    ///
    /// The returned planes expose a depth auxiliary AV1 image before AVIF item transforms are
    /// applied. A `null` return value means the frame has no depth auxiliary image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded depth auxiliary planes, or `null` when no depth auxiliary image is present
    /// @throws IOException if the depth auxiliary image cannot be decoded
    public @Nullable AvifPlanes readRawDepthPlanes(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (container.isSequence()) {
            @Unmodifiable ByteBuffer @Nullable [] depthPayloads = container.sequenceDepthSamplePayloads();
            if (depthPayloads == null) {
                return null;
            }
            return readSequenceRawAuxiliaryPlanes(frameIndex, depthPayloads, "Depth sequence frame");
        }
        if (frameIndex != 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF depth plane decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        ByteBuffer depthPayload = container.depthItemPayload();
        if (depthPayload != null) {
            return decodeRawColorPlanes(depthPayload, "Depth auxiliary AV1 item");
        }
        @Unmodifiable ByteBuffer @Nullable [] depthCellPayloads = container.gridDepthCellPayloads();
        if (depthCellPayloads != null) {
            return composeGridRawColorPlanes(
                    decodeGridRawColorPlanes(depthCellPayloads),
                    container.gridDepthRows(),
                    container.gridDepthColumns(),
                    container.gridDepthOutputWidth(),
                    container.gridDepthOutputHeight()
            );
        }
        if (!hasAuxiliaryType(container.info(), AvifAuxiliaryImageInfo.DEPTH_TYPE)) {
            return null;
        }
        throw unsupported("Depth auxiliary item type is not decodable as AV1 planes", null);
    }

    /// Decodes the next frame from an image sequence using the persistent sequential AV1 reader.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrameSequential(int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] payloads = container.samplePayloads();
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (frameIndex != sequenceAv1FrameIndex && sequenceAv1Reader != null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequential AVIF sequence reader is out of sync at frame " + frameIndex,
                    null
            );
        }
        if (sequenceAv1Reader == null) {
            sequenceAv1Reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffers(payloads),
                    config.av1DecoderConfig()
            );
            sequenceAv1FrameIndex = 0;
        }
        while (sequenceAv1FrameIndex < frameIndex) {
            DecodedFrame skipped = sequenceAv1Reader.readFrame();
            if (skipped == null)
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Sequence ended before frame " + frameIndex, null);
            sequenceAv1FrameIndex++;
        }
        try {
            DecodedFrame decodedFrame = sequenceAv1Reader.readFrame();
            if (decodedFrame == null)
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Sequence produced no frame: " + frameIndex, null);
            sequenceAv1FrameIndex++;
            AvifFrame rawFrame = adaptFrame(
                    decodedFrame,
                    sequenceAv1Reader.lastPlanes(),
                    container.info().colorInfo(),
                    frameIndex,
                    config.rgbOutputMode()
            );
            return combineFrameWithSequenceAlphaSequential(rawFrame, frameIndex);
        } catch (AvifDecodeException e) {
            throw e;
        } catch (IOException e) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, e.getMessage(), null, e);
        }
    }

    /// Decodes raw color planes for one image-sequence frame without mutating sequential playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded color planes
    /// @throws IOException if decoding fails
    private AvifPlanes readSequenceRawColorPlanes(int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] payloads = container.samplePayloads();
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        return readSequenceRawPlanes(frameIndex, payloads, "Sequence frame");
    }

    /// Decodes raw planes for one image-sequence auxiliary frame without mutating playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param payloads the auxiliary sample payloads
    /// @param label the diagnostic label for failures
    /// @return raw decoded auxiliary planes
    /// @throws IOException if decoding fails
    private AvifPlanes readSequenceRawAuxiliaryPlanes(
            int frameIndex,
            @Unmodifiable ByteBuffer @Unmodifiable [] payloads,
            String label
    ) throws IOException {
        if (frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        return readSequenceRawPlanes(frameIndex, payloads, label);
    }

    /// Decodes raw planes for one image-sequence frame without mutating playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param payloads the sample payloads
    /// @param label the diagnostic label for failures
    /// @return raw decoded planes
    /// @throws IOException if decoding fails
    private AvifPlanes readSequenceRawPlanes(
            int frameIndex,
            @Unmodifiable ByteBuffer @Unmodifiable [] payloads,
            String label
    ) throws IOException {
        try (Av1ImageReader rawReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffers(payloads),
                config.av1DecoderConfig()
        )) {
            for (int index = 0; index <= frameIndex; index++) {
                DecodedFrame decodedFrame = rawReader.readFrame();
                if (decodedFrame == null) {
                    throw new AvifDecodeException(
                            AvifErrorCode.AV1_DECODE_FAILED,
                            "Sequence ended before frame " + frameIndex,
                            null
                    );
                }
            }
            return lastRawColorPlanes(rawReader, label);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Decodes raw color planes for one grid-derived still image.
    ///
    /// @return raw decoded color planes composed from grid cells
    /// @throws IOException if decoding fails
    private AvifPlanes readGridRawColorPlanes() throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] cellPayloads = container.gridCellPayloads();
        if (cellPayloads == null || cellPayloads.length == 0) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid has no cell payloads", null);
        }
        AvifPlanes[] cellPlanes = decodeGridRawColorPlanes(cellPayloads);
        return composeGridRawColorPlanes(
                cellPlanes,
                container.gridRows(),
                container.gridColumns(),
                container.gridOutputWidth(),
                container.gridOutputHeight()
        );
    }

    /// Decodes grid cell payloads into raw color planes.
    ///
    /// @param cellPayloads the cell payloads
    /// @return decoded raw color planes for each cell
    /// @throws IOException if one cell cannot be decoded
    private AvifPlanes[] decodeGridRawColorPlanes(@Unmodifiable ByteBuffer @Unmodifiable [] cellPayloads)
            throws IOException {
        AvifPlanes[] cellPlanes = new AvifPlanes[cellPayloads.length];
        for (int i = 0; i < cellPayloads.length; i++) {
            ByteBuffer payload = cellPayloads[i];
            if (payload == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid cell payload is null: " + i, null);
            }
            cellPlanes[i] = decodeRawColorPlanes(payload, "Grid cell " + i);
        }
        return cellPlanes;
    }

    /// Decodes and composes raw alpha planes for an alpha grid.
    ///
    /// @param alphaCellPayloads the alpha grid cell payloads
    /// @return composed raw alpha planes
    /// @throws IOException if one alpha grid cell cannot be decoded
    private AvifPlanes readGridRawAlphaPlanes(@Unmodifiable ByteBuffer @Unmodifiable [] alphaCellPayloads)
            throws IOException {
        AvifPlanes[] cellPlanes = decodeGridRawColorPlanes(alphaCellPayloads);
        AvifBitDepth bitDepth = cellPlanes[0].bitDepth();
        validateGridRawAlphaPlaneCells(cellPlanes, bitDepth);
        AvifPlane lumaPlane = composeGridPlane(
                lumaPlanes(cellPlanes),
                container.gridAlphaRows(),
                container.gridAlphaColumns(),
                container.gridAlphaOutputWidth(),
                container.gridAlphaOutputHeight()
        );
        return new AvifPlanes(
                bitDepth,
                AvifPixelFormat.I400,
                container.gridAlphaOutputWidth(),
                container.gridAlphaOutputHeight(),
                container.gridAlphaOutputWidth(),
                container.gridAlphaOutputHeight(),
                lumaPlane,
                null,
                null
        );
    }

    /// Decodes one AV1 payload and returns raw color planes.
    ///
    /// @param payload the AV1 payload to decode
    /// @param label the diagnostic label for failures
    /// @return raw decoded color planes
    /// @throws IOException if decoding fails
    private AvifPlanes decodeRawColorPlanes(@Unmodifiable ByteBuffer payload, String label) throws IOException {
        try (Av1ImageReader rawReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(payload),
                config.av1DecoderConfig()
        )) {
            DecodedFrame decodedFrame = rawReader.readFrame();
            if (decodedFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, label + " produced no frame", null);
            }
            return lastRawColorPlanes(rawReader, label);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Returns the last decoded raw color planes from one AV1 reader.
    ///
    /// @param reader the AV1 reader
    /// @param label the diagnostic label for failures
    /// @return raw decoded color planes
    /// @throws AvifDecodeException if the reader has no decoded plane snapshot
    private static AvifPlanes lastRawColorPlanes(Av1ImageReader reader, String label) throws AvifDecodeException {
        DecodedPlanes planes = reader.lastPlanes();
        if (planes == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, label + " planes are not available", null);
        }
        return AvifPlanes.fromDecodedPlanes(planes);
    }

    /// Creates alpha-only public planes from a decoded auxiliary image.
    ///
    /// @param planes the decoded auxiliary image planes
    /// @return alpha-only public planes exposing only the luma plane
    private static AvifPlanes alphaPlanesFromDecodedImage(AvifPlanes planes) {
        return new AvifPlanes(
                planes.bitDepth(),
                AvifPixelFormat.I400,
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                planes.lumaPlane(),
                null,
                null
        );
    }

    /// Returns whether image metadata contains one auxiliary image type.
    ///
    /// @param info the image metadata
    /// @param auxiliaryType the auxiliary image type
    /// @return whether the auxiliary type is present
    private static boolean hasAuxiliaryType(AvifImageInfo info, String auxiliaryType) {
        for (String type : info.auxiliaryImageTypes()) {
            if (auxiliaryType.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /// Composes decoded grid cell raw planes into one canvas.
    ///
    /// @param cellPlanes the decoded cell planes in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output luma width
    /// @param outputHeight the output luma height
    /// @return composed raw color planes
    private static AvifPlanes composeGridRawColorPlanes(
            AvifPlanes[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        if (cellPlanes.length != rows * columns) {
            throw new IllegalArgumentException("grid cell count does not match rows * columns");
        }
        AvifPlanes firstCell = cellPlanes[0];
        AvifBitDepth bitDepth = firstCell.bitDepth();
        AvifPixelFormat pixelFormat = firstCell.pixelFormat();
        validateGridRawPlaneCells(cellPlanes, bitDepth, pixelFormat);

        AvifPlane lumaPlane = composeGridPlane(lumaPlanes(cellPlanes), rows, columns, outputWidth, outputHeight);
        if (pixelFormat == AvifPixelFormat.I400) {
            return new AvifPlanes(bitDepth, pixelFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                    lumaPlane, null, null);
        }

        int chromaWidth = expectedChromaWidth(pixelFormat, outputWidth);
        int chromaHeight = expectedChromaHeight(pixelFormat, outputHeight);
        AvifPlane chromaUPlane = composeGridPlane(chromaUPlanes(cellPlanes), rows, columns, chromaWidth, chromaHeight);
        AvifPlane chromaVPlane = composeGridPlane(chromaVPlanes(cellPlanes), rows, columns, chromaWidth, chromaHeight);
        return new AvifPlanes(bitDepth, pixelFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                lumaPlane, chromaUPlane, chromaVPlane);
    }

    /// Validates that all grid cells share the same raw-plane format.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @param bitDepth the expected bit depth
    /// @param pixelFormat the expected pixel format
    private static void validateGridRawPlaneCells(
            AvifPlanes[] cellPlanes,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat
    ) {
        for (AvifPlanes cellPlane : cellPlanes) {
            if (cellPlane.bitDepth() != bitDepth) {
                throw new IllegalArgumentException("grid cell bit depth mismatch");
            }
            if (cellPlane.pixelFormat() != pixelFormat) {
                throw new IllegalArgumentException("grid cell pixel format mismatch");
            }
        }
    }

    /// Validates that all alpha grid cells share the same bit depth.
    ///
    /// @param cellPlanes the decoded alpha cell planes
    /// @param bitDepth the expected bit depth
    private static void validateGridRawAlphaPlaneCells(AvifPlanes[] cellPlanes, AvifBitDepth bitDepth) {
        for (AvifPlanes cellPlane : cellPlanes) {
            if (cellPlane.bitDepth() != bitDepth) {
                throw new IllegalArgumentException("alpha grid cell bit depth mismatch");
            }
        }
    }

    /// Returns luma planes for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return luma planes in row-major order
    private static AvifPlane[] lumaPlanes(AvifPlanes[] cellPlanes) {
        AvifPlane[] result = new AvifPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            result[i] = cellPlanes[i].lumaPlane();
        }
        return result;
    }

    /// Returns chroma U planes for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return chroma U planes in row-major order
    private static AvifPlane[] chromaUPlanes(AvifPlanes[] cellPlanes) {
        AvifPlane[] result = new AvifPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            AvifPlane plane = cellPlanes[i].chromaUPlane();
            if (plane == null) {
                throw new IllegalArgumentException("grid cell chroma U plane is missing");
            }
            result[i] = plane;
        }
        return result;
    }

    /// Returns chroma V planes for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return chroma V planes in row-major order
    private static AvifPlane[] chromaVPlanes(AvifPlanes[] cellPlanes) {
        AvifPlane[] result = new AvifPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            AvifPlane plane = cellPlanes[i].chromaVPlane();
            if (plane == null) {
                throw new IllegalArgumentException("grid cell chroma V plane is missing");
            }
            result[i] = plane;
        }
        return result;
    }

    /// Composes one plane from row-major grid cell planes.
    ///
    /// @param cellPlanes the cell planes
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output plane width
    /// @param outputHeight the output plane height
    /// @return composed plane
    private static AvifPlane composeGridPlane(
            AvifPlane[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        short[] samples = new short[outputWidth * outputHeight];
        int yOffset = 0;
        for (int row = 0; row < rows; row++) {
            int maxCellHeight = 0;
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                AvifPlane cellPlane = cellPlanes[cellIndex];
                maxCellHeight = Math.max(maxCellHeight, cellPlane.height());
                int cellX = gridPlaneCellX(cellPlanes, row, col, columns);
                copyGridPlaneCell(samples, outputWidth, outputHeight, cellPlane, cellX, yOffset);
            }
            yOffset += maxCellHeight;
        }
        return new AvifPlane(outputWidth, outputHeight, outputWidth, samples);
    }

    /// Copies one grid cell plane into the destination plane canvas.
    ///
    /// @param destination the destination samples
    /// @param outputWidth the output plane width
    /// @param outputHeight the output plane height
    /// @param cellPlane the source cell plane
    /// @param xOffset the destination x offset
    /// @param yOffset the destination y offset
    private static void copyGridPlaneCell(
            short[] destination,
            int outputWidth,
            int outputHeight,
            AvifPlane cellPlane,
            int xOffset,
            int yOffset
    ) {
        if (xOffset >= outputWidth || yOffset >= outputHeight) {
            return;
        }
        int copyWidth = Math.min(cellPlane.width(), outputWidth - xOffset);
        int copyHeight = Math.min(cellPlane.height(), outputHeight - yOffset);
        for (int y = 0; y < copyHeight; y++) {
            int destinationBase = (yOffset + y) * outputWidth + xOffset;
            for (int x = 0; x < copyWidth; x++) {
                destination[destinationBase + x] = (short) cellPlane.sample(x, y);
            }
        }
    }

    /// Returns the x offset of one grid cell plane.
    ///
    /// @param cellPlanes the row-major cell planes
    /// @param row the grid row
    /// @param col the grid column
    /// @param columns the grid column count
    /// @return the plane x offset
    private static int gridPlaneCellX(AvifPlane[] cellPlanes, int row, int col, int columns) {
        int cellX = 0;
        for (int prevCol = 0; prevCol < col; prevCol++) {
            cellX += cellPlanes[row * columns + prevCol].width();
        }
        return cellX;
    }

    /// Returns the expected chroma width for one pixel format.
    ///
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width
    private static int expectedChromaWidth(AvifPixelFormat pixelFormat, int codedWidth) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420, I422 -> (codedWidth + 1) / 2;
            case I444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one pixel format.
    ///
    /// @param pixelFormat the decoded AV1 chroma sampling layout
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height
    private static int expectedChromaHeight(AvifPixelFormat pixelFormat, int codedHeight) {
        return switch (pixelFormat) {
            case I400 -> 0;
            case I420 -> (codedHeight + 1) / 2;
            case I422, I444 -> codedHeight;
        };
    }

    /// Decodes one image-sequence frame without mutating the persistent sequential AV1 reader.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrameRandomAccess(int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] payloads = container.samplePayloads();
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        try (Av1ImageReader randomAccessReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffers(payloads),
                config.av1DecoderConfig()
        )) {
            DecodedFrame decodedFrame = null;
            for (int index = 0; index <= frameIndex; index++) {
                decodedFrame = randomAccessReader.readFrame();
                if (decodedFrame == null) {
                    throw new AvifDecodeException(
                            AvifErrorCode.AV1_DECODE_FAILED,
                            "Sequence ended before frame " + frameIndex,
                            null
                    );
                }
            }
            AvifFrame rawFrame = adaptFrame(
                    decodedFrame,
                    randomAccessReader.lastPlanes(),
                    container.info().colorInfo(),
                    frameIndex,
                    config.rgbOutputMode()
            );
            return combineFrameWithSequenceAlphaRandomAccess(rawFrame, frameIndex);
        } catch (AvifDecodeException e) {
            throw e;
        } catch (IOException e) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, e.getMessage(), null, e);
        }
    }

    /// Decodes grid cell items and composes the final canvas.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the composed frame
    /// @throws IOException if decoding fails
    private AvifFrame readGridFrame(int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] cellPayloads = container.gridCellPayloads();
        if (cellPayloads == null || cellPayloads.length == 0) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid has no cell payloads", null);
        }
        DecodedFrame[] cellFrames = decodeGridFrames(cellPayloads, "Grid cell");
        AvifFrame rawFrame = composeGridFrames(cellFrames, container.gridRows(), container.gridColumns(),
                container.gridOutputWidth(), container.gridOutputHeight(), frameIndex, config.rgbOutputMode());

        ByteBuffer alphaPayload = container.alphaItemPayload();
        if (alphaPayload != null) {
            rawFrame = adaptFrameWithAlpha(
                    rawFrame,
                    alphaPayload,
                    frameIndex,
                    container.info().alphaPremultiplied()
            );
        }
        @Unmodifiable ByteBuffer @Nullable [] alphaCellPayloads = container.gridAlphaCellPayloads();
        if (alphaCellPayloads != null) {
            rawFrame = combineFrameWithAlphaGrid(rawFrame, alphaCellPayloads,
                    container.gridAlphaRows(), container.gridAlphaColumns(),
                    container.gridAlphaOutputWidth(), container.gridAlphaOutputHeight(), frameIndex,
                    container.info().alphaPremultiplied());
        }
        return applyTransforms(rawFrame);
    }

    /// Decodes grid cell payloads into AV1 frames.
    ///
    /// @param cellPayloads the cell payloads
    /// @param label the diagnostic label for failures
    /// @return decoded cell frames
    /// @throws IOException if one cell cannot be decoded
    private DecodedFrame[] decodeGridFrames(@Unmodifiable ByteBuffer @Unmodifiable [] cellPayloads, String label)
            throws IOException {
        int cellCount = cellPayloads.length;
        DecodedFrame[] cellFrames = new DecodedFrame[cellCount];
        for (int i = 0; i < cellCount; i++) {
            ByteBuffer payload = cellPayloads[i];
            if (payload == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, label + " payload is null: " + i, null);
            }
            try (Av1ImageReader cellReader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(payload),
                    config.av1DecoderConfig()
            )) {
                DecodedFrame cellFrame = cellReader.readFrame();
                if (cellFrame == null) {
                    throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED,
                            label + " produced no frame: " + i, null);
                }
                cellFrames[i] = cellFrame;
            } catch (AvifDecodeException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
            }
        }
        return cellFrames;
    }

    /// Composes decoded grid cell frames into a single canvas.
    ///
    /// @param cellFrames the decoded cell frames in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @param frameIndex the zero-based frame index
    /// @param outputMode the requested packed RGB output mode
    /// @return the composed frame
    private static AvifFrame composeGridFrames(
            DecodedFrame[] cellFrames, int rows, int columns,
            int outputWidth, int outputHeight, int frameIndex,
            AvifRgbOutputMode outputMode
    ) throws AvifDecodeException {
        if (cellFrames.length == 0) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid has no cells", null);
        }
        DecodedFrame firstCell = cellFrames[0];
        AvifRgbOutputMode resolvedMode = Objects.requireNonNull(outputMode, "outputMode").resolve(firstCell.bitDepth());
        if (resolvedMode == AvifRgbOutputMode.ARGB_8888) {
            return composeGridIntFrames(cellFrames, rows, columns, outputWidth, outputHeight, frameIndex);
        }
        if (resolvedMode == AvifRgbOutputMode.ARGB_16161616) {
            return composeGridLongFrames(cellFrames, rows, columns, outputWidth, outputHeight, frameIndex);
        }
        throw unsupported("Unsupported grid RGB output mode: " + resolvedMode, null);
    }

    /// Composes 8-bit grid cell frames into a single canvas.
    ///
    /// @param cellFrames the decoded 8-bit cell frames
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @param frameIndex the zero-based frame index
    /// @return the composed frame
    private static AvifFrame composeGridIntFrames(
            DecodedFrame[] cellFrames, int rows, int columns,
            int outputWidth, int outputHeight, int frameIndex
    ) {
        int[] canvas = new int[outputWidth * outputHeight];
        int yOffset = 0;
        for (int row = 0; row < rows; row++) {
            int maxCellHeight = 0;
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                DecodedFrame cellFrame = cellFrames[cellIndex];
                IntBuffer cellPixels = cellFrame.intPixelBuffer();
                int cellWidth = cellFrame.width();
                int cellHeight = cellFrame.height();
                maxCellHeight = Math.max(maxCellHeight, cellHeight);
                int cellX = 0;
                for (int prevCol = 0; prevCol < col; prevCol++) {
                    DecodedFrame prevFrame = cellFrames[row * columns + prevCol];
                    cellX += prevFrame.width();
                }
                for (int cy = 0; cy < cellHeight; cy++) {
                    int destRow = yOffset + cy;
                    if (destRow >= outputHeight) {
                        break;
                    }
                    int destCol = cellX;
                    if (destCol >= outputWidth) {
                        break;
                    }
                    int srcRow = cy * cellWidth;
                    int copyWidth = Math.min(cellWidth, outputWidth - destCol);
                    for (int cx = 0; cx < copyWidth; cx++) {
                        canvas[destRow * outputWidth + destCol + cx] = cellPixels.get(srcRow + cx);
                    }
                }
            }
            yOffset += maxCellHeight;
        }
        AvifPixelFormat fmt = cellFrames[0].pixelFormat();
        return new AvifFrame(outputWidth, outputHeight,
                cellFrames[0].bitDepth(), fmt, frameIndex, canvas);
    }

    /// Composes 10/12-bit grid cell frames into a single canvas.
    ///
    /// @param cellFrames the decoded high-bit-depth cell frames
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @param frameIndex the zero-based frame index
    /// @return the composed frame
    private static AvifFrame composeGridLongFrames(
            DecodedFrame[] cellFrames, int rows, int columns,
            int outputWidth, int outputHeight, int frameIndex
    ) {
        long[] canvas = new long[outputWidth * outputHeight];
        int yOffset = 0;
        for (int row = 0; row < rows; row++) {
            int maxCellHeight = 0;
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                DecodedFrame cellFrame = cellFrames[cellIndex];
                LongBuffer cellPixels = cellFrame.longPixelBuffer();
                int cellWidth = cellFrame.width();
                int cellHeight = cellFrame.height();
                maxCellHeight = Math.max(maxCellHeight, cellHeight);
                int cellX = 0;
                for (int prevCol = 0; prevCol < col; prevCol++) {
                    DecodedFrame prevFrame = cellFrames[row * columns + prevCol];
                    cellX += prevFrame.width();
                }
                for (int cy = 0; cy < cellHeight; cy++) {
                    int destRow = yOffset + cy;
                    if (destRow >= outputHeight) {
                        break;
                    }
                    int destCol = cellX;
                    if (destCol >= outputWidth) {
                        break;
                    }
                    int srcRow = cy * cellWidth;
                    int copyWidth = Math.min(cellWidth, outputWidth - destCol);
                    for (int cx = 0; cx < copyWidth; cx++) {
                        canvas[destRow * outputWidth + destCol + cx] = cellPixels.get(srcRow + cx);
                    }
                }
            }
            yOffset += maxCellHeight;
        }
        AvifPixelFormat fmt = cellFrames[0].pixelFormat();
        return new AvifFrame(outputWidth, outputHeight,
                cellFrames[0].bitDepth(), fmt, frameIndex, canvas);
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
        if (sequenceAv1Reader != null) {
            try { sequenceAv1Reader.close(); } catch (IOException ignored) {}
            sequenceAv1Reader = null;
        }
        if (sequenceAlphaAv1Reader != null) {
            try { sequenceAlphaAv1Reader.close(); } catch (IOException ignored) {}
            sequenceAlphaAv1Reader = null;
        }
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

    /// Applies container-level transforms (clap, irot, imir) to a decoded frame.
    ///
    /// @param frame the raw decoded frame
    /// @return the transformed frame, or the same frame when no transforms are present
    private AvifFrame applyTransforms(AvifFrame frame) {
        if (!container.hasClapCrop() && container.rotationCode() <= 0 && container.mirrorAxis() < 0) {
            return frame;
        }
        if (frame.rgbOutputMode() == AvifRgbOutputMode.ARGB_8888) {
            int[] pixels = intBufferToArray(frame.intPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (container.hasClapCrop()) {
                int[] cropped = applyClapCropInt(pixels, width, height,
                        container.clapCropX(), container.clapCropY(),
                        container.clapCropWidth(), container.clapCropHeight());
                pixels = cropped;
                width = container.clapCropWidth();
                height = container.clapCropHeight();
            }

            int rotation = container.rotationCode();
            if (rotation > 0) {
                int[] rotated = applyRotationInt(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = container.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorInt(pixels, width, height, mirror);
            }

            return new AvifFrame(width, height, frame.bitDepth(),
                    frame.pixelFormat(), frame.frameIndex(), pixels);
        }
        if (frame.rgbOutputMode() == AvifRgbOutputMode.ARGB_16161616) {
            long[] pixels = longBufferToArray(frame.longPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (container.hasClapCrop()) {
                long[] cropped = applyClapCropLong(pixels, width, height,
                        container.clapCropX(), container.clapCropY(),
                        container.clapCropWidth(), container.clapCropHeight());
                pixels = cropped;
                width = container.clapCropWidth();
                height = container.clapCropHeight();
            }

            int rotation = container.rotationCode();
            if (rotation > 0) {
                long[] rotated = applyRotationLong(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = container.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorLong(pixels, width, height, mirror);
            }

            return new AvifFrame(width, height, frame.bitDepth(),
                    frame.pixelFormat(), frame.frameIndex(), pixels);
        }
        return frame;
    }

    /// Applies a clean-aperture crop to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param srcWidth the source width
    /// @param srcHeight the source height
    /// @param cropX the crop x offset
    /// @param cropY the crop y offset
    /// @param cropWidth the crop width
    /// @param cropHeight the crop height
    /// @return the cropped pixel array
    private static int[] applyClapCropInt(
            int[] pixels, int srcWidth, int srcHeight,
            int cropX, int cropY, int cropWidth, int cropHeight
    ) {
        int[] result = new int[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            int srcRow = (cropY + y) * srcWidth + cropX;
            int destRow = y * cropWidth;
            System.arraycopy(pixels, srcRow, result, destRow, cropWidth);
        }
        return result;
    }

    /// Applies a clean-aperture crop to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param srcWidth the source width
    /// @param srcHeight the source height
    /// @param cropX the crop x offset
    /// @param cropY the crop y offset
    /// @param cropWidth the crop width
    /// @param cropHeight the crop height
    /// @return the cropped pixel array
    private static long[] applyClapCropLong(
            long[] pixels, int srcWidth, int srcHeight,
            int cropX, int cropY, int cropWidth, int cropHeight
    ) {
        long[] result = new long[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            int srcRow = (cropY + y) * srcWidth + cropX;
            int destRow = y * cropWidth;
            System.arraycopy(pixels, srcRow, result, destRow, cropWidth);
        }
        return result;
    }

    /// Applies rotation to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param rotation the rotation code (1=90° CW, 2=180°, 3=270° CW)
    /// @return the rotated pixel array
    private static int[] applyRotationInt(int[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
                int newWidth = height;
                int newHeight = width;
                int[] result = new int[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
                    }
                }
                yield result;
            }
            case 2 -> {
                int[] result = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        result[y * width + x] = pixels[(height - 1 - y) * width + (width - 1 - x)];
                    }
                }
                yield result;
            }
            case 3 -> {
                int newWidth = height;
                int newHeight = width;
                int[] result = new int[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[x * width + (width - 1 - y)];
                    }
                }
                yield result;
            }
            default -> pixels;
        };
    }

    /// Applies rotation to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param rotation the rotation code (1=90° CW, 2=180°, 3=270° CW)
    /// @return the rotated pixel array
    private static long[] applyRotationLong(long[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
                int newWidth = height;
                int newHeight = width;
                long[] result = new long[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
                    }
                }
                yield result;
            }
            case 2 -> {
                long[] result = new long[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        result[y * width + x] = pixels[(height - 1 - y) * width + (width - 1 - x)];
                    }
                }
                yield result;
            }
            case 3 -> {
                int newWidth = height;
                int newHeight = width;
                long[] result = new long[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[x * width + (width - 1 - y)];
                    }
                }
                yield result;
            }
            default -> pixels;
        };
    }

    /// Applies mirroring to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param axis the mirror axis (0=vertical, 1=horizontal)
    /// @return the mirrored pixel array
    private static int[] applyMirrorInt(int[] pixels, int width, int height, int axis) {
        int[] result = new int[width * height];
        if (axis == 0) {
            for (int y = 0; y < height; y++) {
                int srcRow = (height - 1 - y) * width;
                int destRow = y * width;
                System.arraycopy(pixels, srcRow, result, destRow, width);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowBase = y * width;
                for (int x = 0; x < width; x++) {
                    result[rowBase + x] = pixels[rowBase + (width - 1 - x)];
                }
            }
        }
        return result;
    }

    /// Applies mirroring to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param axis the mirror axis (0=vertical, 1=horizontal)
    /// @return the mirrored pixel array
    private static long[] applyMirrorLong(long[] pixels, int width, int height, int axis) {
        long[] result = new long[width * height];
        if (axis == 0) {
            for (int y = 0; y < height; y++) {
                int srcRow = (height - 1 - y) * width;
                int destRow = y * width;
                System.arraycopy(pixels, srcRow, result, destRow, width);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowBase = y * width;
                for (int x = 0; x < width; x++) {
                    result[rowBase + x] = pixels[rowBase + (width - 1 - x)];
                }
            }
        }
        return result;
    }

    /// Adapts an AV1 decoded frame to the AVIF public frame model.
    ///
    /// @param frame the decoded AV1 frame
    /// @param planes the decoded AV1 planes, or `null`
    /// @param colorInfo the AVIF `nclx` color metadata, or `null`
    /// @param frameIndex the zero-based AVIF frame index
    /// @param outputMode the requested packed RGB output mode
    /// @return an AVIF public frame
    private static AvifFrame adaptFrame(
            DecodedFrame frame,
            @Nullable DecodedPlanes planes,
            @Nullable AvifColorInfo colorInfo,
            int frameIndex,
            AvifRgbOutputMode outputMode
    ) {
        AvifRgbOutputMode resolvedMode = Objects.requireNonNull(outputMode, "outputMode").resolve(frame.bitDepth());
        if (colorInfo != null && planes != null) {
            return adaptFrameFromPlanes(frame, planes, colorInfo, frameIndex, resolvedMode);
        }
        if (resolvedMode == AvifRgbOutputMode.ARGB_8888) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    frame.intPixelBuffer()
            );
        }
        if (resolvedMode == AvifRgbOutputMode.ARGB_16161616) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    frame.longPixelBuffer()
            );
        }
        throw new IllegalArgumentException("Unsupported RGB output mode: " + resolvedMode);
    }

    /// Adapts decoded AV1 planes to the AVIF public frame model using container color metadata.
    ///
    /// @param frame the decoded AV1 frame metadata
    /// @param planes the decoded AV1 planes to render
    /// @param colorInfo the AVIF `nclx` color metadata
    /// @param frameIndex the zero-based AVIF frame index
    /// @param resolvedOutputMode the concrete packed RGB output mode
    /// @return an AVIF public frame rendered with the container-selected YUV transform
    private static AvifFrame adaptFrameFromPlanes(
            DecodedFrame frame,
            DecodedPlanes planes,
            AvifColorInfo colorInfo,
            int frameIndex,
            AvifRgbOutputMode resolvedOutputMode
    ) {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorInfo(
                colorInfo,
                frame.pixelFormat() == AvifPixelFormat.I400
        );
        if (resolvedOutputMode == AvifRgbOutputMode.ARGB_8888) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    ArgbOutput.toOpaqueArgbPixels(planes, transform)
            );
        }
        if (resolvedOutputMode == AvifRgbOutputMode.ARGB_16161616) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    ArgbOutput.toOpaqueArgbLongPixels(planes, transform)
            );
        }
        throw new IllegalArgumentException("Unsupported RGB output mode: " + resolvedOutputMode);
    }

    /// Combines a sequentially read sequence frame with its matching alpha sample when present.
    ///
    /// @param colorFrame the decoded color frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the color frame with sequence alpha applied, or the original frame
    /// @throws IOException if the alpha sample cannot be decoded
    private AvifFrame combineFrameWithSequenceAlphaSequential(AvifFrame colorFrame, int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
        if (alphaPayloads == null) {
            return colorFrame;
        }
        if (frameIndex != sequenceAlphaAv1FrameIndex && sequenceAlphaAv1Reader != null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequential AVIF alpha reader is out of sync at frame " + frameIndex,
                    null
            );
        }
        if (sequenceAlphaAv1Reader == null) {
            sequenceAlphaAv1Reader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffers(alphaPayloads),
                    config.av1DecoderConfig()
            );
            sequenceAlphaAv1FrameIndex = 0;
        }
        while (sequenceAlphaAv1FrameIndex < frameIndex) {
            DecodedFrame skipped = sequenceAlphaAv1Reader.readFrame();
            if (skipped == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Sequence alpha ended before frame " + frameIndex,
                        null
                );
            }
            sequenceAlphaAv1FrameIndex++;
        }
        DecodedFrame alphaFrame = sequenceAlphaAv1Reader.readFrame();
        if (alphaFrame == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequence alpha produced no frame: " + frameIndex,
                    null
            );
        }
        sequenceAlphaAv1FrameIndex++;
        return combineFrameWithDecodedAlpha(
                colorFrame, alphaFrame, sequenceAlphaAv1Reader.lastPlanes(), frameIndex
        );
    }

    /// Combines a randomly accessed sequence frame with its matching alpha sample when present.
    ///
    /// @param colorFrame the decoded color frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the color frame with sequence alpha applied, or the original frame
    /// @throws IOException if the alpha sample cannot be decoded
    private AvifFrame combineFrameWithSequenceAlphaRandomAccess(AvifFrame colorFrame, int frameIndex)
            throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
        if (alphaPayloads == null) {
            return colorFrame;
        }
        try (Av1ImageReader alphaReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffers(alphaPayloads),
                config.av1DecoderConfig()
        )) {
            DecodedFrame alphaFrame = null;
            for (int index = 0; index <= frameIndex; index++) {
                alphaFrame = alphaReader.readFrame();
                if (alphaFrame == null) {
                    throw new AvifDecodeException(
                            AvifErrorCode.AV1_DECODE_FAILED,
                            "Sequence alpha ended before frame " + frameIndex,
                            null
                    );
                }
            }
            return combineFrameWithDecodedAlpha(colorFrame, alphaFrame, alphaReader.lastPlanes(), frameIndex);
        }
    }

    /// Decodes an alpha auxiliary AV1 payload and combines it with a decoded color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaPayload the alpha auxiliary AV1 OBU payload
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws IOException if the alpha payload cannot be decoded
    private AvifFrame adaptFrameWithAlpha(
            AvifFrame colorFrame,
            @Unmodifiable ByteBuffer alphaPayload,
            int frameIndex,
            boolean alphaPremultiplied
    ) throws IOException {
        try (Av1ImageReader alphaReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(alphaPayload),
                config.av1DecoderConfig()
        )) {
            DecodedFrame alphaFrame = alphaReader.readFrame();
            if (alphaFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha auxiliary item produced no frame", null);
            }
            DecodedPlanes alphaPlanes = alphaReader.lastPlanes();
            return combineFrameWithDecodedAlpha(colorFrame, alphaFrame, alphaPlanes, frameIndex, alphaPremultiplied);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Combines one decoded alpha frame with a color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaFrame the decoded alpha frame metadata
    /// @param alphaPlanes the decoded alpha planes, or `null`
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws AvifDecodeException if alpha planes are unavailable or dimensions differ
    private AvifFrame combineFrameWithDecodedAlpha(
            AvifFrame colorFrame,
            DecodedFrame alphaFrame,
            @Nullable DecodedPlanes alphaPlanes,
            int frameIndex
    ) throws AvifDecodeException {
        return combineFrameWithDecodedAlpha(
                colorFrame,
                alphaFrame,
                alphaPlanes,
                frameIndex,
                container.info().alphaPremultiplied()
        );
    }

    /// Combines one decoded alpha frame with a color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaFrame the decoded alpha frame metadata
    /// @param alphaPlanes the decoded alpha planes, or `null`
    /// @param frameIndex the zero-based AVIF frame index
    /// @param alphaPremultiplied whether color samples are premultiplied by alpha
    /// @return the combined AVIF frame
    /// @throws AvifDecodeException if alpha planes are unavailable or dimensions differ
    private static AvifFrame combineFrameWithDecodedAlpha(
            AvifFrame colorFrame,
            DecodedFrame alphaFrame,
            @Nullable DecodedPlanes alphaPlanes,
            int frameIndex,
            boolean alphaPremultiplied
    ) throws AvifDecodeException {
        if (alphaFrame.width() != colorFrame.width() || alphaFrame.height() != colorFrame.height()) {
            throw unsupported("Alpha with different decoded dimensions than master image", null);
        }
        if (alphaPlanes == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha planes not available", null);
        }
        return combineFrameWithAlphaPlane(
                colorFrame,
                alphaPlanes,
                alphaFrame.bitDepth(),
                frameIndex,
                alphaPremultiplied
        );
    }

    /// Decodes alpha grid cells and combines their luma planes into a color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaCellPayloads the alpha grid cell AV1 OBU payloads
    /// @param rows the alpha grid row count
    /// @param columns the alpha grid column count
    /// @param outputWidth the alpha grid output width
    /// @param outputHeight the alpha grid output height
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws IOException if an alpha cell cannot be decoded
    private AvifFrame combineFrameWithAlphaGrid(
            AvifFrame colorFrame,
            @Unmodifiable ByteBuffer @Unmodifiable [] alphaCellPayloads,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            int frameIndex,
            boolean alphaPremultiplied
    ) throws IOException {
        if (outputWidth != colorFrame.width() || outputHeight != colorFrame.height()) {
            throw unsupported("Alpha grid with different decoded dimensions than master image", null);
        }
        DecodedAlphaCell[] alphaCells = decodeAlphaGridCells(alphaCellPayloads);
        if (colorFrame.rgbOutputMode() == AvifRgbOutputMode.ARGB_8888) {
            return combineIntGridAlpha(
                    colorFrame,
                    alphaCells,
                    rows,
                    columns,
                    outputWidth,
                    outputHeight,
                    frameIndex,
                    alphaPremultiplied
            );
        }
        if (colorFrame.rgbOutputMode() == AvifRgbOutputMode.ARGB_16161616) {
            return combineLongGridAlpha(
                    colorFrame,
                    alphaCells,
                    rows,
                    columns,
                    outputWidth,
                    outputHeight,
                    frameIndex,
                    alphaPremultiplied
            );
        }
        throw unsupported("Unsupported alpha color frame RGB output mode: " + colorFrame.rgbOutputMode(), null);
    }

    /// Decodes alpha grid cells and retains their raw luma planes.
    ///
    /// @param alphaCellPayloads the alpha grid cell AV1 OBU payloads
    /// @return decoded alpha cells
    /// @throws IOException if one alpha cell cannot be decoded
    private DecodedAlphaCell[] decodeAlphaGridCells(@Unmodifiable ByteBuffer @Unmodifiable [] alphaCellPayloads)
            throws IOException {
        DecodedAlphaCell[] alphaCells = new DecodedAlphaCell[alphaCellPayloads.length];
        for (int i = 0; i < alphaCellPayloads.length; i++) {
            ByteBuffer payload = alphaCellPayloads[i];
            if (payload == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha grid cell payload is null: " + i, null);
            }
            try (Av1ImageReader cellReader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(payload),
                    config.av1DecoderConfig()
            )) {
                DecodedFrame frame = cellReader.readFrame();
                if (frame == null) {
                    throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED,
                            "Alpha grid cell produced no frame: " + i, null);
                }
                DecodedPlanes planes = cellReader.lastPlanes();
                if (planes == null) {
                    throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED,
                            "Alpha grid cell planes not available: " + i, null);
                }
                alphaCells[i] = new DecodedAlphaCell(frame, planes);
            } catch (AvifDecodeException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
            }
        }
        return alphaCells;
    }

    /// Combines alpha from one raw luma plane into a color frame.
    ///
    /// @param color the decoded color frame
    /// @param alphaPlanes the decoded alpha planes
    /// @param alphaBitDepth the alpha plane bit depth
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined frame
    private static AvifFrame combineFrameWithAlphaPlane(
            AvifFrame color,
            DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        if (color.rgbOutputMode() == AvifRgbOutputMode.ARGB_8888) {
            return combineIntPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        if (color.rgbOutputMode() == AvifRgbOutputMode.ARGB_16161616) {
            return combineLongPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        throw new IllegalArgumentException("Unsupported alpha color frame RGB output mode: " + color.rgbOutputMode());
    }

    /// Combines alpha from raw luma plane into an 8-bit color frame.
    private static AvifFrame combineIntPlaneAlpha(
            AvifFrame color,
            DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        IntBuffer colorPixels = color.intPixelBuffer();
        int width = color.width();
        int height = color.height();
        DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = alphaBitDepth.maxSampleValue();
        int[] combined = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                int alpha8 = scaleSampleToByte(alphaSample, maxSample);
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x00FFFFFF) | (alpha8 << 24);
            }
        }
        if (alphaPremultiplied) {
            unpremultiplyIntPixels(combined);
        }
        return new AvifFrame(width, height, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
    }

    /// Combines alpha from raw luma plane into a 10/12-bit color frame.
    private static AvifFrame combineLongPlaneAlpha(
            AvifFrame color,
            DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        LongBuffer colorPixels = color.longPixelBuffer();
        int width = color.width();
        int height = color.height();
        DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = alphaBitDepth.maxSampleValue();
        long[] combined = new long[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                long alpha16 = scaleSampleToWord(alphaSample, maxSample);
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x0000FFFF_FFFFFFFFL) | ((alpha16 & 0xFFFFL) << 48);
            }
        }
        if (alphaPremultiplied) {
            unpremultiplyLongPixels(combined);
        }
        return new AvifFrame(width, height, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
    }

    /// Combines alpha grid cells into an 8-bit color frame.
    private static AvifFrame combineIntGridAlpha(
            AvifFrame color,
            DecodedAlphaCell[] alphaCells,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        int[] combined = intBufferToArray(color.intPixelBuffer());
        copyGridAlphaToIntPixels(combined, alphaCells, rows, columns, outputWidth, outputHeight);
        if (alphaPremultiplied) {
            unpremultiplyIntPixels(combined);
        }
        return new AvifFrame(outputWidth, outputHeight, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
    }

    /// Combines alpha grid cells into a 10/12-bit color frame.
    private static AvifFrame combineLongGridAlpha(
            AvifFrame color,
            DecodedAlphaCell[] alphaCells,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        long[] combined = longBufferToArray(color.longPixelBuffer());
        copyGridAlphaToLongPixels(combined, alphaCells, rows, columns, outputWidth, outputHeight);
        if (alphaPremultiplied) {
            unpremultiplyLongPixels(combined);
        }
        return new AvifFrame(outputWidth, outputHeight, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
    }

    /// Copies alpha grid samples into packed 8-bit pixels.
    private static void copyGridAlphaToIntPixels(
            int[] pixels,
            DecodedAlphaCell[] alphaCells,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        int yOffset = 0;
        for (int row = 0; row < rows; row++) {
            int maxCellHeight = 0;
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                DecodedAlphaCell alphaCell = alphaCells[cellIndex];
                DecodedFrame alphaFrame = alphaCell.frame;
                DecodedPlane alphaPlane = alphaCell.planes.lumaPlane();
                int cellWidth = alphaFrame.width();
                int cellHeight = alphaFrame.height();
                maxCellHeight = Math.max(maxCellHeight, cellHeight);
                int cellX = alphaGridCellX(alphaCells, row, col, columns);
                int maxSample = alphaFrame.bitDepth().maxSampleValue();
                for (int cy = 0; cy < cellHeight; cy++) {
                    int destRow = yOffset + cy;
                    if (destRow >= outputHeight) {
                        break;
                    }
                    if (cellX >= outputWidth) {
                        break;
                    }
                    int copyWidth = Math.min(cellWidth, outputWidth - cellX);
                    int destBase = destRow * outputWidth + cellX;
                    for (int cx = 0; cx < copyWidth; cx++) {
                        int alpha8 = scaleSampleToByte(alphaPlane.sample(cx, cy), maxSample);
                        pixels[destBase + cx] = (pixels[destBase + cx] & 0x00FFFFFF) | (alpha8 << 24);
                    }
                }
            }
            yOffset += maxCellHeight;
        }
    }

    /// Copies alpha grid samples into packed 16-bit-per-channel pixels.
    private static void copyGridAlphaToLongPixels(
            long[] pixels,
            DecodedAlphaCell[] alphaCells,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        int yOffset = 0;
        for (int row = 0; row < rows; row++) {
            int maxCellHeight = 0;
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                DecodedAlphaCell alphaCell = alphaCells[cellIndex];
                DecodedFrame alphaFrame = alphaCell.frame;
                DecodedPlane alphaPlane = alphaCell.planes.lumaPlane();
                int cellWidth = alphaFrame.width();
                int cellHeight = alphaFrame.height();
                maxCellHeight = Math.max(maxCellHeight, cellHeight);
                int cellX = alphaGridCellX(alphaCells, row, col, columns);
                int maxSample = alphaFrame.bitDepth().maxSampleValue();
                for (int cy = 0; cy < cellHeight; cy++) {
                    int destRow = yOffset + cy;
                    if (destRow >= outputHeight) {
                        break;
                    }
                    if (cellX >= outputWidth) {
                        break;
                    }
                    int copyWidth = Math.min(cellWidth, outputWidth - cellX);
                    int destBase = destRow * outputWidth + cellX;
                    for (int cx = 0; cx < copyWidth; cx++) {
                        long alpha16 = scaleSampleToWord(alphaPlane.sample(cx, cy), maxSample);
                        pixels[destBase + cx] = (pixels[destBase + cx] & 0x0000FFFF_FFFFFFFFL)
                                | ((alpha16 & 0xFFFFL) << 48);
                    }
                }
            }
            yOffset += maxCellHeight;
        }
    }

    /// Converts packed 8-bit ARGB pixels from premultiplied to straight alpha in place.
    ///
    /// @param pixels the pixels to convert
    private static void unpremultiplyIntPixels(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = pixel >>> 24;
            if (alpha == 0) {
                pixels[i] = 0;
                continue;
            }
            if (alpha == 255) {
                continue;
            }
            int red = unpremultiplyChannel((pixel >>> 16) & 0xFF, alpha, 255);
            int green = unpremultiplyChannel((pixel >>> 8) & 0xFF, alpha, 255);
            int blue = unpremultiplyChannel(pixel & 0xFF, alpha, 255);
            pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }

    /// Converts packed 16-bit-per-channel ARGB pixels from premultiplied to straight alpha in place.
    ///
    /// @param pixels the pixels to convert
    private static void unpremultiplyLongPixels(long[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            long pixel = pixels[i];
            int alpha = (int) ((pixel >>> 48) & 0xFFFFL);
            if (alpha == 0) {
                pixels[i] = 0L;
                continue;
            }
            if (alpha == 65_535) {
                continue;
            }
            long red = unpremultiplyChannel((int) ((pixel >>> 32) & 0xFFFFL), alpha, 65_535);
            long green = unpremultiplyChannel((int) ((pixel >>> 16) & 0xFFFFL), alpha, 65_535);
            long blue = unpremultiplyChannel((int) (pixel & 0xFFFFL), alpha, 65_535);
            pixels[i] = ((long) alpha << 48) | (red << 32) | (green << 16) | blue;
        }
    }

    /// Converts one premultiplied channel to straight alpha.
    ///
    /// @param sample the premultiplied color sample
    /// @param alpha the alpha sample
    /// @param maxSample the maximum channel sample value
    /// @return the straight-alpha channel sample
    private static int unpremultiplyChannel(int sample, int alpha, int maxSample) {
        long value = ((long) sample * maxSample + alpha / 2L) / alpha;
        return value > maxSample ? maxSample : (int) value;
    }

    /// Returns the x offset of one alpha grid cell.
    ///
    /// @param alphaCells the decoded alpha cells
    /// @param row the grid row
    /// @param col the grid column
    /// @param columns the grid column count
    /// @return the x offset in pixels
    private static int alphaGridCellX(DecodedAlphaCell[] alphaCells, int row, int col, int columns) {
        int cellX = 0;
        for (int prevCol = 0; prevCol < col; prevCol++) {
            cellX += alphaCells[row * columns + prevCol].frame.width();
        }
        return cellX;
    }

    /// Scales a decoded alpha sample to an unsigned 8-bit channel.
    ///
    /// @param sample the decoded alpha sample
    /// @param maxSample the maximum alpha sample value
    /// @return the scaled 8-bit alpha channel
    private static int scaleSampleToByte(int sample, int maxSample) {
        if (maxSample == 255) {
            return sample;
        }
        return (sample * 255 + maxSample / 2) / maxSample;
    }

    /// Scales a decoded alpha sample to an unsigned 16-bit channel.
    ///
    /// @param sample the decoded alpha sample
    /// @param maxSample the maximum alpha sample value
    /// @return the scaled 16-bit alpha channel
    private static long scaleSampleToWord(int sample, int maxSample) {
        if (maxSample == 65_535) {
            return sample;
        }
        return ((long) sample * 65_535 + maxSample / 2) / maxSample;
    }

    /// Decoded alpha cell with both converted frame metadata and raw decoded planes.
    @NotNullByDefault
    private static final class DecodedAlphaCell {
        /// The decoded alpha frame metadata.
        private final DecodedFrame frame;
        /// The decoded alpha planes.
        private final DecodedPlanes planes;

        /// Creates one decoded alpha cell.
        ///
        /// @param frame the decoded alpha frame metadata
        /// @param planes the decoded alpha planes
        private DecodedAlphaCell(DecodedFrame frame, DecodedPlanes planes) {
            this.frame = Objects.requireNonNull(frame, "frame");
            this.planes = Objects.requireNonNull(planes, "planes");
        }
    }

    /// Creates an unsupported-feature exception.
    ///
    /// @param message the failure message
    /// @param offset the byte offset or `null`
    /// @return an unsupported-feature exception
    private static AvifDecodeException unsupported(String message, @Nullable Long offset) {
        return new AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE, message, offset);
    }

    /// Copies remaining integers from a buffer into an array.
    ///
    /// @param buffer the source buffer
    /// @return an array containing the buffer's remaining integers
    private static int[] intBufferToArray(IntBuffer buffer) {
        IntBuffer source = buffer.slice();
        int[] result = new int[source.remaining()];
        source.get(result);
        return result;
    }

    /// Copies remaining longs from a buffer into an array.
    ///
    /// @param buffer the source buffer
    /// @return an array containing the buffer's remaining longs
    private static long[] longBufferToArray(LongBuffer buffer) {
        LongBuffer source = buffer.slice();
        long[] result = new long[source.remaining()];
        source.get(result);
        return result;
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
