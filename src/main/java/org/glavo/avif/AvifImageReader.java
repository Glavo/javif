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
import org.glavo.avif.decode.DecodeErrorCode;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.internal.av1.output.ArgbOutput;
import org.glavo.avif.internal.av1.output.YuvToRgbTransform;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.bmff.AvifContainer;
import org.glavo.avif.internal.bmff.AvifContainerParser;
import org.glavo.avif.internal.bmff.AvifImageSource;
import org.glavo.avif.internal.bmff.AvifPayload;
import org.glavo.avif.internal.bmff.SampleTransform;
import org.glavo.avif.internal.io.BufferedInput;
import org.glavo.avif.internal.io.RandomAccessDataSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// High-level reader for AVIF images.
@NotNullByDefault
public final class AvifImageReader implements AutoCloseable {
    /// The immutable factory options used to create this reader.
    private final AvifImageReaderFactory factory;
    /// The owned random-access container source.
    private final RandomAccessDataSource source;
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
    /// @param source the owned random-access AVIF source
    /// @param factory the immutable factory that owns the decoding options
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    AvifImageReader(RandomAccessDataSource source, AvifImageReaderFactory factory) throws AvifDecodeException {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.source = Objects.requireNonNull(source, "source");
        try {
            this.container = AvifContainerParser.parse(source);
        } catch (AvifDecodeException | RuntimeException | Error exception) {
            try {
                source.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    ///
    /// @param source the complete AVIF source bytes
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(byte[] source) throws AvifDecodeException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    ///
    /// @param source the source byte buffer, read from its current position to its limit
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(ByteBuffer source) throws AvifDecodeException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// It consumes the stream through end-of-stream without closing it.
    ///
    /// @param source the source input stream
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(InputStream source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// It consumes the channel through end-of-stream without closing it.
    ///
    /// @param source the source byte channel
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(ReadableByteChannel source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// The returned reader owns an open read-only file handle; the file must not be modified until
    /// the reader is closed.
    ///
    /// @param source the source file path
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(Path source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
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
        if (container.isSequence()) {
            return readSequenceFrameRandomAccess(frameIndex);
        }
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return readSampleTransformedFrame(frameIndex, sampleTransform);
        }
        AvifImageSource primarySource = container.primarySource();
        if (primarySource == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        DecodedRawImage decodedColor = decodeImageSource(primarySource, "Primary AV1 image");
        AvifFrame rawFrame = adaptRawPlanes(
                decodedColor.planes(),
                decodedColor.colorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
        );
        AvifImageSource alphaSource = container.alphaSource();
        if (alphaSource != null) {
            AvifPlanes alphaPlanes = alphaPlanesFromDecodedImage(
                    decodeImageSource(alphaSource, "Alpha auxiliary AV1 image").planes()
            );
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Alpha dimensions differ from color dimensions",
                        null
                );
            }
            rawFrame = combineFrameWithAlphaPlane(
                    rawFrame,
                    toDecodedPlanes(alphaPlanes),
                    alphaPlanes.bitDepth(),
                    frameIndex,
                    container.info().alphaPremultiplied()
            );
        }
        return applyTransforms(rawFrame);
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
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, false).planes();
        }
        AvifImageSource primarySource = container.primarySource();
        if (primarySource == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        return decodeImageSource(primarySource, "Primary AV1 image").planes();
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
            AvifPayload @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
            if (alphaPayloads == null) {
                return null;
            }
            return alphaPlanesFromDecodedImage(readSequenceRawAuxiliaryPlanes(
                    frameIndex,
                    alphaPayloads,
                    "Alpha sequence frame"
            ));
        }
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, true).planes();
        }
        AvifImageSource alphaSource = container.alphaSource();
        return alphaSource != null
                ? alphaPlanesFromDecodedImage(decodeImageSource(alphaSource, "Alpha auxiliary AV1 image").planes())
                : null;
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
        AvifImageSource gainMapSource = container.gainMapSource();
        if (gainMapSource != null) {
            return decodeImageSource(gainMapSource, "Gain-map AV1 image").planes();
        }
        throw unsupported("Gain-map item type is not decodable as AV1 planes: " + gainMapInfo.gainMapItemType(), null);
    }

    /// Reads the decoded frame at the supplied index with its AVIF gain map applied.
    ///
    /// The returned frame preserves the regular `readFrame(int)` alpha composition, item
    /// transforms, frame index, and packed ARGB pixel format. A `null` return value means the frame
    /// has no supported gain-map association.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    public @Nullable AvifFrame readToneMappedFrame(int frameIndex, double hdrHeadroom) throws IOException {
        return readToneMappedFrameInternal(frameIndex, hdrHeadroom, null);
    }

    /// Reads the decoded frame at the supplied index with its AVIF gain map applied.
    ///
    /// The returned frame preserves the regular `readFrame(int)` alpha composition, item
    /// transforms, frame index, and packed ARGB pixel format. RGB channels are converted into the
    /// requested CICP output color space after gain-map application. ICC profile application
    /// remains metadata-only.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @param outputColorInfo the requested output CICP color information
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    public @Nullable AvifFrame readToneMappedFrame(
            int frameIndex,
            double hdrHeadroom,
            AvifColorInfo outputColorInfo
    ) throws IOException {
        return readToneMappedFrameInternal(
                frameIndex,
                hdrHeadroom,
                Objects.requireNonNull(outputColorInfo, "outputColorInfo")
        );
    }

    /// Reads the decoded frame at the supplied index with optional output color conversion.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @param outputColorInfo the requested output CICP color information, or `null` for base color space
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    private @Nullable AvifFrame readToneMappedFrameInternal(
            int frameIndex,
            double hdrHeadroom,
            @Nullable AvifColorInfo outputColorInfo
    ) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (!Double.isFinite(hdrHeadroom) || hdrHeadroom < 0.0) {
            throw new IllegalArgumentException("hdrHeadroom must be a finite non-negative value");
        }
        AvifGainMapInfo gainMapInfo = container.info().gainMapInfo();
        if (gainMapInfo == null) {
            return null;
        }
        AvifGainMapMetadata metadata = gainMapInfo.metadata();
        if (metadata == null) {
            return null;
        }
        @Nullable AvifPlanes gainMapPlanes = null;
        if (AvifGainMapToneMapper.requiresGainMap(metadata, hdrHeadroom)) {
            gainMapPlanes = readRawGainMapPlanes(frameIndex);
            if (gainMapPlanes == null) {
                return null;
            }
        }
        try {
            return AvifGainMapToneMapper.apply(
                    readFrame(frameIndex),
                    gainMapPlanes,
                    metadata,
                    container.info().colorInfo(),
                    gainMapInfo.toneMappedColorInfo(),
                    gainMapInfo.gainMapColorInfo(),
                    outputColorInfo,
                    hdrHeadroom
            );
        } catch (UnsupportedOperationException exception) {
            throw unsupportedColorConversion(exception);
        }
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
            AvifPayload @Nullable [] depthPayloads = container.sequenceDepthSamplePayloads();
            if (depthPayloads == null) {
                return null;
            }
            return readSequenceRawAuxiliaryPlanes(frameIndex, depthPayloads, "Depth sequence frame");
        }
        AvifImageSource depthSource = container.depthSource();
        if (depthSource != null) {
            return decodeImageSource(depthSource, "Depth auxiliary AV1 image").planes();
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
        AvifPayload @Nullable [] payloads = container.samplePayloads();
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
                    AvifPayload.openInput(payloads),
                    factory.av1DecoderConfig()
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
                    factory.outputPixelFormat()
            );
            return combineFrameWithSequenceAlphaSequential(rawFrame, frameIndex);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Decodes raw color planes for one image-sequence frame without mutating sequential playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded color planes
    /// @throws IOException if decoding fails
    private AvifPlanes readSequenceRawColorPlanes(int frameIndex) throws IOException {
        AvifPayload @Nullable [] payloads = container.samplePayloads();
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
            AvifPayload @Unmodifiable [] payloads,
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
            AvifPayload @Unmodifiable [] payloads,
            String label
    ) throws IOException {
        try (Av1ImageReader rawReader = Av1ImageReader.open(
                AvifPayload.openInput(payloads),
                factory.av1DecoderConfig()
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
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Decodes one AV1 item payload and selects its requested output spatial layer.
    ///
    /// @param payload the AV1 payload to decode
    /// @param label the diagnostic label for failures
    /// @param operatingPoint the selected AV1 operating-point index
    /// @param selectedSpatialLayer the selected spatial-layer identifier, or
    ///        [AvifImageSource#HIGHEST_SPATIAL_LAYER]
    /// @return decoded raw planes and their AV1 color configuration
    /// @throws IOException if decoding fails
    private DecodedRawImage decodeRawImage(
            AvifPayload payload,
            String label,
            int operatingPoint,
            int selectedSpatialLayer
    ) throws IOException {
        try (Av1ImageReader rawReader = Av1ImageReader.open(
                payload.openInput(),
                factory.av1DecoderConfig().withOperatingPoint(operatingPoint)
        )) {
            @Nullable DecodedRawImage selectedImage = null;
            int highestSpatialId = -1;
            while (true) {
                DecodedFrame decodedFrame = rawReader.readFrame();
                if (decodedFrame == null) {
                    break;
                }
                boolean matchesSelection = selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER
                        ? decodedFrame.spatialId() >= highestSpatialId
                        : decodedFrame.spatialId() == selectedSpatialLayer;
                if (!matchesSelection) {
                    continue;
                }
                SequenceHeader.ColorConfig colorConfig = rawReader.lastColorConfig();
                if (colorConfig == null) {
                    throw new AvifDecodeException(
                            AvifErrorCode.AV1_DECODE_FAILED,
                            label + " has no active AV1 color configuration",
                            null
                    );
                }
                selectedImage = new DecodedRawImage(lastRawColorPlanes(rawReader, label), colorConfig);
                highestSpatialId = decodedFrame.spatialId();
                if (selectedSpatialLayer != AvifImageSource.HIGHEST_SPATIAL_LAYER) {
                    break;
                }
            }
            if (selectedImage == null) {
                String message = selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER
                        ? label + " produced no frame"
                        : label + " produced no output for selected spatial layer " + selectedSpatialLayer;
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, message, null);
            }
            return selectedImage;
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Decodes and applies one parsed Sample Transform.
    ///
    /// @param sampleTransform the parsed Sample Transform
    /// @param alpha whether to reconstruct alpha rather than color planes
    /// @return the reconstructed planes and primary-input AV1 color configuration
    /// @throws IOException if one input image cannot be decoded
    private DecodedSampleTransform decodeSampleTransform(SampleTransform sampleTransform, boolean alpha)
            throws IOException {
        AvifPlanes[] inputPlanes = new AvifPlanes[sampleTransform.inputCount()];
        @Nullable SequenceHeader.ColorConfig primaryColorConfig = null;
        for (int inputIndex = 0; inputIndex < sampleTransform.inputCount(); inputIndex++) {
            SampleTransform.Input input = sampleTransform.input(inputIndex);
            @Nullable AvifImageSource source = alpha ? input.alphaSource() : input.colorSource();
            if (source == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Sample Transform alpha input is missing: " + inputIndex,
                        null
                );
            }
            String label = "Sample Transform " + (alpha ? "alpha " : "color ") + "input " + inputIndex;
            DecodedRawImage decoded = decodeImageSource(source, label);
            inputPlanes[inputIndex] = alpha ? alphaPlanesFromDecodedImage(decoded.planes()) : decoded.planes();
            if (inputIndex == sampleTransform.primaryInputIndex()) {
                primaryColorConfig = decoded.colorConfig();
            }
        }
        if (primaryColorConfig == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Sample Transform primary input is missing",
                    null
            );
        }
        try {
            AvifPlanes reconstructed = alpha
                    ? sampleTransform.applyAlpha(inputPlanes)
                    : sampleTransform.apply(inputPlanes);
            return new DecodedSampleTransform(reconstructed, primaryColorConfig);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sample Transform input planes cannot be reconstructed: " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Decodes one standalone or grid-derived AV1 image source.
    ///
    /// @param source the image source
    /// @param label the diagnostic label for failures
    /// @return decoded raw planes and their AV1 color configuration
    /// @throws IOException if the image source cannot be decoded
    private DecodedRawImage decodeImageSource(AvifImageSource source, String label) throws IOException {
        if (!source.isGrid()) {
            DecodedRawImage decoded = decodeRawImage(
                    source.payload(0),
                    label,
                    source.operatingPoint(0),
                    source.selectedSpatialLayer(0)
            );
            validateDecodedItemDimensions(source, 0, decoded.planes(), label);
            return decoded;
        }
        enforceGridFrameSizeLimit(source, label);
        AvifPayload @Unmodifiable [] cellPayloads = source.payloads();
        if (cellPayloads.length == 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " grid has no cells",
                    null
            );
        }
        AvifPlanes[] cellPlanes = new AvifPlanes[cellPayloads.length];
        @Nullable SequenceHeader.ColorConfig colorConfig = null;
        for (int cellIndex = 0; cellIndex < cellPayloads.length; cellIndex++) {
            DecodedRawImage decoded = decodeRawImage(
                    cellPayloads[cellIndex],
                    label + " grid cell " + cellIndex,
                    source.operatingPoint(cellIndex),
                    source.selectedSpatialLayer(cellIndex)
            );
            validateDecodedItemDimensions(
                    source,
                    cellIndex,
                    decoded.planes(),
                    label + " grid cell " + cellIndex
            );
            cellPlanes[cellIndex] = decoded.planes();
            if (colorConfig == null) {
                colorConfig = decoded.colorConfig();
            }
        }
        validateGridGeometry(
                cellPlanes,
                source.rows(),
                source.columns(),
                source.outputWidth(),
                source.outputHeight(),
                label
        );
        assert colorConfig != null;
        AvifPlanes composed = composeGridRawColorPlanes(
                cellPlanes,
                source.rows(),
                source.columns(),
                source.outputWidth(),
                source.outputHeight()
        );
        return new DecodedRawImage(composed, colorConfig);
    }

    /// Enforces the configured frame-size limit against a derived grid canvas.
    ///
    /// Individual AV1 cells are checked by `Av1ImageReader`; this additional check prevents a
    /// collection of individually valid cells from producing an oversized composed image.
    ///
    /// @param source the normalized grid image source
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the grid canvas exceeds the configured frame-size limit
    private void enforceGridFrameSizeLimit(AvifImageSource source, String label) throws AvifDecodeException {
        long frameSizeLimit = factory.av1DecoderConfig().frameSizeLimit();
        long pixelCount = (long) source.outputWidth() * source.outputHeight();
        if (frameSizeLimit != 0 && pixelCount > frameSizeLimit) {
            throw new AvifDecodeException(
                    AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                    label + " grid size exceeds the configured limit: "
                            + source.outputWidth() + "x" + source.outputHeight(),
                    null
            );
        }
    }

    /// Validates a selected AV1 output frame against its associated `ispe` dimensions.
    ///
    /// @param source the normalized image source
    /// @param itemIndex the zero-based payload or grid-cell index
    /// @param planes the selected final decoded planes
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the decoded dimensions differ from `ispe`
    private static void validateDecodedItemDimensions(
            AvifImageSource source,
            int itemIndex,
            AvifPlanes planes,
            String label
    ) throws AvifDecodeException {
        int expectedWidth = source.itemWidth(itemIndex);
        int expectedHeight = source.itemHeight(itemIndex);
        if (planes.codedWidth() != expectedWidth || planes.codedHeight() != expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.ISPE_SIZE_MISMATCH,
                    label + " decoded dimensions " + planes.codedWidth() + "x" + planes.codedHeight()
                            + " do not match ispe " + expectedWidth + "x" + expectedHeight,
                    null
            );
        }
    }

    /// Decodes and renders one preferred Sample Transform still image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param sampleTransform the parsed Sample Transform
    /// @return the reconstructed and transformed AVIF frame
    /// @throws IOException if an input image cannot be decoded
    private AvifFrame readSampleTransformedFrame(int frameIndex, SampleTransform sampleTransform) throws IOException {
        DecodedSampleTransform decodedColor = decodeSampleTransform(sampleTransform, false);
        AvifFrame rawFrame = adaptRawPlanes(
                decodedColor.planes(),
                decodedColor.primaryColorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
        );
        if (container.info().alphaPresent()) {
            AvifPlanes alphaPlanes = decodeSampleTransform(sampleTransform, true).planes();
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Sample Transform alpha dimensions differ from color dimensions",
                        null
                );
            }
            DecodedPlanes decodedAlphaPlanes = toDecodedPlanes(alphaPlanes);
            validateAlphaLumaPlane(
                    decodedAlphaPlanes.lumaPlane(),
                    rawFrame.width(),
                    rawFrame.height(),
                    "Sample Transform alpha"
            );
            rawFrame = combineFrameWithAlphaPlane(
                    rawFrame,
                    decodedAlphaPlanes,
                    alphaPlanes.bitDepth(),
                    frameIndex,
                    container.info().alphaPremultiplied()
            );
        }
        return applyTransforms(rawFrame);
    }

    /// Renders reconstructed color planes into the requested packed ARGB format.
    ///
    /// Container `nclx` metadata takes precedence over the primary input's AV1 sequence-header
    /// color configuration, matching the normal still-image path.
    ///
    /// @param planes the reconstructed color planes
    /// @param av1ColorConfig the primary input's AV1 color configuration
    /// @param colorInfo the AVIF container color information, or `null`
    /// @param frameIndex the zero-based frame index
    /// @param outputPixelFormat the configured packed pixel format, or `null` to select by source bit depth
    /// @return the rendered AVIF frame
    /// @throws AvifDecodeException if the selected color conversion is unsupported
    private static AvifFrame adaptRawPlanes(
            AvifPlanes planes,
            SequenceHeader.ColorConfig av1ColorConfig,
            @Nullable AvifColorInfo colorInfo,
            int frameIndex,
            @Nullable AvifPixelFormat outputPixelFormat
    ) throws AvifDecodeException {
        AvifPixelFormat pixelFormat = outputPixelFormat != null
                ? outputPixelFormat
                : AvifPixelFormat.defaultFor(planes.bitDepth());
        try {
            YuvToRgbTransform transform = colorInfo != null
                    ? YuvToRgbTransform.fromColorInfo(colorInfo, planes.chromaFormat() == Av1ChromaFormat.MONOCHROME)
                    : YuvToRgbTransform.fromColorConfig(av1ColorConfig);
            DecodedPlanes decodedPlanes = toDecodedPlanes(planes);
            if (pixelFormat == AvifPixelFormat.ARGB_8888) {
                return new AvifFrame(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbPixels(decodedPlanes, transform)
                );
            }
            if (pixelFormat == AvifPixelFormat.ARGB_16161616) {
                return new AvifFrame(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbLongPixels(decodedPlanes, transform)
                );
            }
            throw new IllegalArgumentException("Unsupported pixel format: " + pixelFormat);
        } catch (UnsupportedOperationException exception) {
            throw unsupportedColorConversion(exception);
        }
    }

    /// Converts public raw planes back to the internal output-conversion representation.
    ///
    /// @param planes the public raw planes
    /// @return equivalent internal decoded planes
    private static DecodedPlanes toDecodedPlanes(AvifPlanes planes) {
        return new DecodedPlanes(
                planes.bitDepth().bits(),
                planes.chromaFormat(),
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                toDecodedPlane(planes.lumaPlane()),
                toNullableDecodedPlane(planes.chromaUPlane()),
                toNullableDecodedPlane(planes.chromaVPlane())
        );
    }

    /// Converts one public plane to the internal output-conversion representation.
    ///
    /// @param plane the public plane
    /// @return the equivalent internal plane
    private static DecodedPlane toDecodedPlane(AvifPlane plane) {
        return new DecodedPlane(plane.width(), plane.height(), plane.stride(), plane.samples());
    }

    /// Converts one optional public plane to the internal output-conversion representation.
    ///
    /// @param plane the public plane, or `null`
    /// @return the equivalent internal plane, or `null`
    private static @Nullable DecodedPlane toNullableDecodedPlane(@Nullable AvifPlane plane) {
        return plane != null ? toDecodedPlane(plane) : null;
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
                Av1ChromaFormat.MONOCHROME,
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
        Av1ChromaFormat chromaFormat = gridRawPlaneChromaFormat(cellPlanes);
        validateGridRawPlaneCells(cellPlanes, bitDepth, chromaFormat);

        AvifPlane lumaPlane = composeGridPlane(lumaPlanes(cellPlanes), rows, columns, outputWidth, outputHeight);
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            return new AvifPlanes(bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                    lumaPlane, null, null);
        }

        int chromaWidth = expectedChromaWidth(chromaFormat, outputWidth);
        int chromaHeight = expectedChromaHeight(chromaFormat, outputHeight);
        AvifPlane chromaUPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, true),
                rows,
                columns,
                chromaWidth,
                chromaHeight
        );
        AvifPlane chromaVPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, false),
                rows,
                columns,
                chromaWidth,
                chromaHeight
        );
        return new AvifPlanes(bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                lumaPlane, chromaUPlane, chromaVPlane);
    }

    /// Validates decoded grid-cell consistency and canvas coverage.
    ///
    /// @param cellPlanes the decoded cells in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output luma width
    /// @param outputHeight the output luma height
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the cells or canvas violate grid requirements
    private static void validateGridGeometry(
            AvifPlanes[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            String label
    ) throws AvifDecodeException {
        if (cellPlanes.length != rows * columns || cellPlanes.length == 0) {
            throw invalidImageGrid(label + " grid cell count does not match its row and column counts");
        }
        AvifPlanes firstCell = cellPlanes[0];
        int tileWidth = firstCell.codedWidth();
        int tileHeight = firstCell.codedHeight();
        for (int cellIndex = 1; cellIndex < cellPlanes.length; cellIndex++) {
            AvifPlanes cell = cellPlanes[cellIndex];
            if (cell.codedWidth() != tileWidth || cell.codedHeight() != tileHeight) {
                throw invalidImageGrid(
                        label + " grid cell " + cellIndex + " dimensions "
                                + cell.codedWidth() + "x" + cell.codedHeight()
                                + " differ from the first cell " + tileWidth + "x" + tileHeight
                );
            }
        }
        if (tileWidth < 64 || tileHeight < 64) {
            throw invalidImageGrid(label + " grid cells must be at least 64x64 samples");
        }
        if ((long) tileWidth * columns < outputWidth || (long) tileHeight * rows < outputHeight) {
            throw invalidImageGrid(label + " grid cells do not cover the output canvas");
        }
        if ((long) tileWidth * (columns - 1) >= outputWidth
                || (long) tileHeight * (rows - 1) >= outputHeight) {
            throw invalidImageGrid(label + " rightmost or bottommost grid cells do not overlap the output canvas");
        }

        Av1ChromaFormat chromaFormat = gridRawPlaneChromaFormat(cellPlanes);
        if ((chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422)
                && ((tileWidth & 1) != 0 || (outputWidth & 1) != 0)) {
            throw invalidImageGrid(label + " horizontally subsampled grid widths must be even");
        }
        if (chromaFormat == Av1ChromaFormat.YUV420
                && ((tileHeight & 1) != 0 || (outputHeight & 1) != 0)) {
            throw invalidImageGrid(label + " vertically subsampled grid heights must be even");
        }
        if ((long) outputWidth * outputHeight > Integer.MAX_VALUE) {
            throw unsupported(label + " grid output contains too many samples for a Java array", null);
        }
    }

    /// Returns the common raw grid chroma format, allowing monochrome cells in a chroma grid.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return the grid chroma format
    private static Av1ChromaFormat gridRawPlaneChromaFormat(AvifPlanes[] cellPlanes) {
        Av1ChromaFormat chromaFormat = Av1ChromaFormat.MONOCHROME;
        for (AvifPlanes cellPlane : cellPlanes) {
            Av1ChromaFormat cellChromaFormat = cellPlane.chromaFormat();
            if (cellChromaFormat == Av1ChromaFormat.MONOCHROME) {
                continue;
            }
            if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
                chromaFormat = cellChromaFormat;
            } else if (cellChromaFormat != chromaFormat) {
                throw new IllegalArgumentException("grid cell chroma format mismatch");
            }
        }
        return chromaFormat;
    }

    /// Validates that all grid cells share the same raw-plane format.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @param bitDepth the expected bit depth
    /// @param chromaFormat the expected output chroma format
    private static void validateGridRawPlaneCells(
            AvifPlanes[] cellPlanes,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat
    ) {
        for (AvifPlanes cellPlane : cellPlanes) {
            if (cellPlane.bitDepth() != bitDepth) {
                throw new IllegalArgumentException("grid cell bit depth mismatch");
            }
            Av1ChromaFormat cellChromaFormat = cellPlane.chromaFormat();
            if (cellChromaFormat != chromaFormat && cellChromaFormat != Av1ChromaFormat.MONOCHROME) {
                throw new IllegalArgumentException("grid cell chroma format mismatch");
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

    /// Returns one chroma plane for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @param chromaFormat the output chroma format
    /// @param bitDepth the output bit depth
    /// @param chromaU whether to return U instead of V
    /// @return chroma planes in row-major order
    private static AvifPlane[] chromaPlanes(
            AvifPlanes[] cellPlanes,
            Av1ChromaFormat chromaFormat,
            AvifBitDepth bitDepth,
            boolean chromaU
    ) {
        AvifPlane[] result = new AvifPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            AvifPlanes cellPlane = cellPlanes[i];
            AvifPlane plane = chromaU ? cellPlane.chromaUPlane() : cellPlane.chromaVPlane();
            result[i] = plane != null ? plane : neutralChromaPlane(cellPlane, chromaFormat, bitDepth);
        }
        return result;
    }

    /// Creates a neutral chroma plane for a monochrome grid cell.
    ///
    /// @param cellPlane the monochrome cell planes
    /// @param chromaFormat the output chroma format
    /// @param bitDepth the output bit depth
    /// @return a neutral chroma plane matching the cell's target chroma dimensions
    private static AvifPlane neutralChromaPlane(
            AvifPlanes cellPlane,
            Av1ChromaFormat chromaFormat,
            AvifBitDepth bitDepth
    ) {
        int width = expectedChromaWidth(chromaFormat, cellPlane.codedWidth());
        int height = expectedChromaHeight(chromaFormat, cellPlane.codedHeight());
        short[] samples = new short[width * height];
        Arrays.fill(samples, (short) (1 << (bitDepth.bits() - 1)));
        return new AvifPlane(width, height, width, samples);
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
        short[] samples = new short[Math.multiplyExact(outputWidth, outputHeight)];
        int cellWidth = cellPlanes[0].width();
        int cellHeight = cellPlanes[0].height();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                AvifPlane cellPlane = cellPlanes[cellIndex];
                if (cellPlane.width() != cellWidth || cellPlane.height() != cellHeight) {
                    throw new IllegalArgumentException("grid cell plane dimensions mismatch");
                }
                copyGridPlaneCell(
                        samples,
                        outputWidth,
                        outputHeight,
                        cellPlane,
                        col * cellWidth,
                        row * cellHeight
                );
            }
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

    /// Returns the expected chroma width for one chroma format.
    ///
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width
    private static int expectedChromaWidth(Av1ChromaFormat chromaFormat, int codedWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (codedWidth + 1) / 2;
            case YUV444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one chroma format.
    ///
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height
    private static int expectedChromaHeight(Av1ChromaFormat chromaFormat, int codedHeight) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420 -> (codedHeight + 1) / 2;
            case YUV422, YUV444 -> codedHeight;
        };
    }

    /// Decodes one image-sequence frame without mutating the persistent sequential AV1 reader.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrameRandomAccess(int frameIndex) throws IOException {
        AvifPayload @Nullable [] payloads = container.samplePayloads();
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        try (Av1ImageReader randomAccessReader = Av1ImageReader.open(
                AvifPayload.openInput(payloads),
                factory.av1DecoderConfig()
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
                    factory.outputPixelFormat()
            );
            return combineFrameWithSequenceAlphaRandomAccess(rawFrame, frameIndex);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
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

    /// Closes this reader and its owned container source.
    ///
    /// Repeated calls have no effect. The reader is closed even if releasing an AV1 decoder, file
    /// handle, or temporary spool file fails. When multiple releases fail, later failures are
    /// suppressed on the first.
    ///
    /// @throws IOException if an owned decoder or source cannot be released
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        @Nullable IOException failure = null;
        if (sequenceAv1Reader != null) {
            try {
                sequenceAv1Reader.close();
            } catch (IOException exception) {
                failure = exception;
            }
            sequenceAv1Reader = null;
        }
        if (sequenceAlphaAv1Reader != null) {
            try {
                sequenceAlphaAv1Reader.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            sequenceAlphaAv1Reader = null;
        }
        try {
            source.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
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
        AvifImageInfo info = container.info();
        if (!info.hasCleanApertureCrop() && info.rotationCode() <= 0 && info.mirrorAxis() < 0) {
            return frame;
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            int[] pixels = intBufferToArray(frame.intPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (info.hasCleanApertureCrop()) {
                int[] cropped = applyClapCropInt(pixels, width, height,
                        info.cleanApertureCropX(), info.cleanApertureCropY(),
                        info.cleanApertureCropWidth(), info.cleanApertureCropHeight());
                pixels = cropped;
                width = info.cleanApertureCropWidth();
                height = info.cleanApertureCropHeight();
            }

            int rotation = info.rotationCode();
            if (rotation > 0) {
                int[] rotated = applyRotationInt(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = info.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorInt(pixels, width, height, mirror);
            }

            return new AvifFrame(width, height, frame.bitDepth(),
                    frame.chromaFormat(), frame.frameIndex(), pixels);
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            long[] pixels = longBufferToArray(frame.longPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (info.hasCleanApertureCrop()) {
                long[] cropped = applyClapCropLong(pixels, width, height,
                        info.cleanApertureCropX(), info.cleanApertureCropY(),
                        info.cleanApertureCropWidth(), info.cleanApertureCropHeight());
                pixels = cropped;
                width = info.cleanApertureCropWidth();
                height = info.cleanApertureCropHeight();
            }

            int rotation = info.rotationCode();
            if (rotation > 0) {
                long[] rotated = applyRotationLong(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = info.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorLong(pixels, width, height, mirror);
            }

            return new AvifFrame(width, height, frame.bitDepth(),
                    frame.chromaFormat(), frame.frameIndex(), pixels);
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
    /// @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
    /// @return the rotated pixel array
    private static int[] applyRotationInt(int[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
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
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
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
    /// @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
    /// @return the rotated pixel array
    private static long[] applyRotationLong(long[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
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
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
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
    /// @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
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
    /// @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
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
    /// @param outputPixelFormat the configured packed pixel format, or `null` to select by source bit depth
    /// @return an AVIF public frame
    /// @throws AvifDecodeException if the container selects an unsupported color conversion
    private static AvifFrame adaptFrame(
            DecodedFrame frame,
            @Nullable DecodedPlanes planes,
            @Nullable AvifColorInfo colorInfo,
            int frameIndex,
            @Nullable AvifPixelFormat outputPixelFormat
    ) throws AvifDecodeException {
        AvifPixelFormat pixelFormat = outputPixelFormat != null
                ? outputPixelFormat
                : AvifPixelFormat.defaultFor(frame.bitDepth());
        if (colorInfo != null && planes != null) {
            try {
                return adaptFrameFromPlanes(frame, planes, colorInfo, frameIndex, pixelFormat);
            } catch (UnsupportedOperationException exception) {
                throw unsupportedColorConversion(exception);
            }
        }
        if (pixelFormat == AvifPixelFormat.ARGB_8888) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.chromaFormat(),
                    frameIndex,
                    frame.intPixelBuffer()
            );
        }
        if (pixelFormat == AvifPixelFormat.ARGB_16161616) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.chromaFormat(),
                    frameIndex,
                    frame.longPixelBuffer()
            );
        }
        throw new IllegalArgumentException("Unsupported pixel format: " + pixelFormat);
    }

    /// Adapts decoded AV1 planes to the AVIF public frame model using container color metadata.
    ///
    /// @param frame the decoded AV1 frame metadata
    /// @param planes the decoded AV1 planes to render
    /// @param colorInfo the AVIF `nclx` color metadata
    /// @param frameIndex the zero-based AVIF frame index
    /// @param pixelFormat the concrete packed pixel format
    /// @return an AVIF public frame rendered with the container-selected YUV transform
    private static AvifFrame adaptFrameFromPlanes(
            DecodedFrame frame,
            DecodedPlanes planes,
            AvifColorInfo colorInfo,
            int frameIndex,
            AvifPixelFormat pixelFormat
    ) {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorInfo(
                colorInfo,
                frame.chromaFormat() == Av1ChromaFormat.MONOCHROME
        );
        if (pixelFormat == AvifPixelFormat.ARGB_8888) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.chromaFormat(),
                    frameIndex,
                    ArgbOutput.toOpaqueArgbPixels(planes, transform)
            );
        }
        if (pixelFormat == AvifPixelFormat.ARGB_16161616) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.chromaFormat(),
                    frameIndex,
                    ArgbOutput.toOpaqueArgbLongPixels(planes, transform)
            );
        }
        throw new IllegalArgumentException("Unsupported pixel format: " + pixelFormat);
    }

    /// Combines a sequentially read sequence frame with its matching alpha sample when present.
    ///
    /// @param colorFrame the decoded color frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the color frame with sequence alpha applied, or the original frame
    /// @throws IOException if the alpha sample cannot be decoded
    private AvifFrame combineFrameWithSequenceAlphaSequential(AvifFrame colorFrame, int frameIndex) throws IOException {
        AvifPayload @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
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
                    AvifPayload.openInput(alphaPayloads),
                    factory.av1DecoderConfig()
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
        AvifPayload @Nullable [] alphaPayloads = container.sequenceAlphaSamplePayloads();
        if (alphaPayloads == null) {
            return colorFrame;
        }
        try (Av1ImageReader alphaReader = Av1ImageReader.open(
                AvifPayload.openInput(alphaPayloads),
                factory.av1DecoderConfig()
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
        validateDecodedAlphaFrame(
                colorFrame.width(),
                colorFrame.height(),
                alphaFrame,
                alphaPlanes,
                "Alpha"
        );
        return combineFrameWithAlphaPlane(
                colorFrame,
                alphaPlanes,
                alphaFrame.bitDepth(),
                frameIndex,
                alphaPremultiplied
        );
    }

    /// Validates one decoded alpha frame before composition.
    ///
    /// @param expectedWidth the expected alpha width
    /// @param expectedHeight the expected alpha height
    /// @param alphaFrame the decoded alpha frame metadata
    /// @param alphaPlanes the decoded alpha planes, or `null`
    /// @param label the diagnostic alpha source label
    /// @throws AvifDecodeException if the alpha frame is incompatible with the color frame
    private static void validateDecodedAlphaFrame(
            int expectedWidth,
            int expectedHeight,
            DecodedFrame alphaFrame,
            @Nullable DecodedPlanes alphaPlanes,
            String label
    ) throws AvifDecodeException {
        if (alphaFrame.width() != expectedWidth || alphaFrame.height() != expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    label + " with different decoded dimensions than master image",
                    null
            );
        }
        if (alphaPlanes == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, label + " planes not available", null);
        }
        validateAlphaLumaPlane(alphaPlanes.lumaPlane(), alphaFrame.width(), alphaFrame.height(), label);
    }

    /// Validates one alpha luma plane against the expected decoded dimensions.
    ///
    /// @param lumaPlane the decoded luma plane used as alpha
    /// @param expectedWidth the expected luma width
    /// @param expectedHeight the expected luma height
    /// @param label the diagnostic alpha source label
    /// @throws AvifDecodeException if the luma plane cannot cover the expected alpha image
    private static void validateAlphaLumaPlane(
            DecodedPlane lumaPlane,
            int expectedWidth,
            int expectedHeight,
            String label
    ) throws AvifDecodeException {
        if (lumaPlane.width() < expectedWidth || lumaPlane.height() < expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    label + " luma plane is smaller than the decoded alpha frame",
                    null
            );
        }
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
        if (color.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            return combineIntPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        if (color.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            return combineLongPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        throw new IllegalArgumentException("Unsupported alpha color frame pixel format: " + color.pixelFormat());
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
        return new AvifFrame(width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined);
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
        return new AvifFrame(width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined);
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

    /// Raw decoded image planes and the active AV1 color configuration.
    ///
    /// @param planes the decoded raw planes
    /// @param colorConfig the active AV1 sequence-header color configuration
    @NotNullByDefault
    private record DecodedRawImage(AvifPlanes planes, SequenceHeader.ColorConfig colorConfig) {
    }

    /// Reconstructed Sample Transform planes and the primary input's AV1 color configuration.
    ///
    /// @param planes the reconstructed planes
    /// @param primaryColorConfig the primary input's AV1 sequence-header color configuration
    @NotNullByDefault
    private record DecodedSampleTransform(
            AvifPlanes planes,
            SequenceHeader.ColorConfig primaryColorConfig
    ) {
    }

    /// Creates an unsupported-feature exception.
    ///
    /// @param message the failure message
    /// @param offset the byte offset or `null`
    /// @return an unsupported-feature exception
    private static AvifDecodeException unsupported(String message, @Nullable Long offset) {
        return new AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE, message, offset);
    }

    /// Creates an invalid-grid exception.
    ///
    /// @param message the failure message
    /// @return an invalid-grid exception
    private static AvifDecodeException invalidImageGrid(String message) {
        return new AvifDecodeException(AvifErrorCode.INVALID_IMAGE_GRID, message, null);
    }

    /// Creates an unsupported-feature exception for an unavailable CICP color conversion.
    ///
    /// @param exception the unsupported color conversion failure
    /// @return an unsupported-feature exception retaining the underlying cause
    private static AvifDecodeException unsupportedColorConversion(UnsupportedOperationException exception) {
        return new AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "AVIF output uses an unsupported color conversion",
                null,
                exception
        );
    }

    /// Wraps one low-level AV1 decoding failure while preserving caller-relevant classification.
    ///
    /// @param exception the low-level decoding failure
    /// @return the corresponding AVIF decoding failure
    private static AvifDecodeException wrapAv1DecodeFailure(IOException exception) {
        AvifErrorCode code = AvifErrorCode.AV1_DECODE_FAILED;
        if (exception instanceof DecodeException decodeException) {
            code = switch (decodeException.code()) {
                case UNSUPPORTED_FEATURE -> AvifErrorCode.UNSUPPORTED_FEATURE;
                case FRAME_SIZE_LIMIT_EXCEEDED -> AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED;
                default -> AvifErrorCode.AV1_DECODE_FAILED;
            };
        }
        return new AvifDecodeException(
                code,
                exception.getMessage() != null ? exception.getMessage() : "AV1 decoding failed",
                null,
                exception
        );
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

}
