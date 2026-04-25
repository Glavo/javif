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
import org.glavo.avif.decode.PixelFormat;
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
        if (frameIndex != 0 && !container.isSequence()) {
            throw new AvifDecodeException(
                    AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Indexed AVIF frame decoding beyond the primary still image is not implemented in this slice",
                    null
            );
        }
        if (container.isSequence()) {
            return readSequenceFrame(frameIndex);
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
            AvifFrame rawFrame;
            if (alphaPayload != null) {
                rawFrame = adaptFrameWithAlpha(colorFrame, alphaPayload, frameIndex);
            } else {
                rawFrame = adaptFrame(colorFrame, frameIndex);
            }
            return applyTransforms(rawFrame);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Decodes a single frame from an image sequence.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrame(int frameIndex) throws IOException {
        @Unmodifiable ByteBuffer @Nullable [] payloads = container.samplePayloads();
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (frameIndex < sequenceAv1FrameIndex) {
            throw new AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE,
                    "Random-access sequence decoding is not implemented in this slice", null);
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
            return adaptFrame(decodedFrame, frameIndex);
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
        int cellCount = cellPayloads.length;
        DecodedFrame[] cellFrames = new DecodedFrame[cellCount];
        for (int i = 0; i < cellCount; i++) {
            ByteBuffer payload = cellPayloads[i];
            if (payload == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid cell payload is null: " + i, null);
            }
            try (Av1ImageReader cellReader = Av1ImageReader.open(
                    new BufferedInput.OfByteBuffer(payload),
                    config.av1DecoderConfig()
            )) {
                DecodedFrame cellFrame = cellReader.readFrame();
                if (cellFrame == null) {
                    throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid cell produced no frame: " + i, null);
                }
                cellFrames[i] = cellFrame;
            } catch (AvifDecodeException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
            }
        }
        AvifFrame rawFrame = composeGridFrames(cellFrames, container.gridRows(), container.gridColumns(),
                container.gridOutputWidth(), container.gridOutputHeight(), frameIndex);
        return applyTransforms(rawFrame);
    }

    /// Composes decoded grid cell frames into a single canvas.
    ///
    /// @param cellFrames the decoded cell frames in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @param frameIndex the zero-based frame index
    /// @return the composed frame
    private static AvifFrame composeGridFrames(
            DecodedFrame[] cellFrames, int rows, int columns,
            int outputWidth, int outputHeight, int frameIndex
    ) throws AvifDecodeException {
        if (cellFrames.length == 0) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Grid has no cells", null);
        }
        DecodedFrame firstCell = cellFrames[0];
        if (firstCell.bitDepth().isEightBit()) {
            return composeGridIntFrames(cellFrames, rows, columns, outputWidth, outputHeight, frameIndex);
        }
        if (firstCell.bitDepth().isHighBitDepth()) {
            throw unsupported("Grid composition for 10/12-bit frames is not implemented in this slice", null);
        }
        throw unsupported("Unsupported grid cell bit depth: " + firstCell.bitDepth(), null);
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
                    int destCol = cellX;
                    int srcRow = cy * cellWidth;
                    for (int cx = 0; cx < cellWidth; cx++) {
                        canvas[destRow * outputWidth + destCol + cx] = cellPixels.get(srcRow + cx);
                    }
                }
            }
            yOffset += maxCellHeight;
        }
        PixelFormat fmt = cellFrames[0].pixelFormat();
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
        if (frame.bitDepth().isEightBit()) {
            if (!container.hasClapCrop() && container.rotationCode() <= 0 && container.mirrorAxis() < 0) {
                return frame;
            }

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

    /// Adapts an AV1 decoded frame to the AVIF public frame model.
    ///
    /// @param frame the decoded AV1 frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return an AVIF public frame
    private static AvifFrame adaptFrame(DecodedFrame frame, int frameIndex) {
        if (frame.bitDepth().isEightBit()) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    frame.intPixelBuffer()
            );
        }
        if (frame.bitDepth().isHighBitDepth()) {
            return new AvifFrame(
                    frame.width(),
                    frame.height(),
                    frame.bitDepth(),
                    frame.pixelFormat(),
                    frameIndex,
                    frame.longPixelBuffer()
            );
        }
        throw new IllegalArgumentException("Unsupported decoded bit depth: " + frame.bitDepth());
    }

    /// Decodes an alpha auxiliary AV1 payload and combines it with a decoded color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaPayload the alpha auxiliary AV1 OBU payload
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws IOException if the alpha payload cannot be decoded
    private AvifFrame adaptFrameWithAlpha(
            DecodedFrame colorFrame, @Unmodifiable ByteBuffer alphaPayload, int frameIndex
    ) throws IOException {
        try (Av1ImageReader alphaReader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(alphaPayload),
                config.av1DecoderConfig()
        )) {
            DecodedFrame alphaFrame = alphaReader.readFrame();
            if (alphaFrame == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha auxiliary item produced no frame", null);
            }
            if (alphaFrame.width() != colorFrame.width() || alphaFrame.height() != colorFrame.height()) {
                throw unsupported("Alpha with different decoded dimensions than master image", null);
            }
            DecodedPlanes alphaPlanes = alphaReader.lastPlanes();
            if (alphaPlanes == null) {
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Alpha planes not available", null);
            }
            if (colorFrame.bitDepth().isEightBit()) {
                return combineIntPlaneAlpha(colorFrame, alphaPlanes, alphaFrame.pixelFormat(), frameIndex);
            }
            if (colorFrame.bitDepth().isHighBitDepth()) {
                return combineLongPlaneAlpha(colorFrame, alphaPlanes, alphaFrame.pixelFormat(), frameIndex);
            }
            throw unsupported("Unsupported alpha color frame bit depth: " + colorFrame.bitDepth(), null);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, exception.getMessage(), null, exception);
        }
    }

    /// Combines alpha from raw luma plane into an 8-bit color frame.
    private static AvifFrame combineIntPlaneAlpha(
            DecodedFrame color, DecodedPlanes alphaPlanes, PixelFormat alphaFmt, int frameIndex
    ) {
        IntBuffer colorPixels = color.intPixelBuffer();
        int width = color.width();
        int height = color.height();
        DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = color.bitDepth().maxSampleValue();
        int[] combined = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                int alpha8;
                if (color.bitDepth().isEightBit()) {
                    alpha8 = alphaSample;
                } else {
                    alpha8 = (alphaSample * 255 + maxSample / 2) / maxSample;
                }
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x00FFFFFF) | (alpha8 << 24);
            }
        }
        return new AvifFrame(width, height, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
    }

    /// Combines alpha from raw luma plane into a 10/12-bit color frame.
    private static AvifFrame combineLongPlaneAlpha(
            DecodedFrame color, DecodedPlanes alphaPlanes, PixelFormat alphaFmt, int frameIndex
    ) {
        LongBuffer colorPixels = color.longPixelBuffer();
        int width = color.width();
        int height = color.height();
        DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = color.bitDepth().maxSampleValue();
        long maxSampleL = maxSample;
        long[] combined = new long[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                long alpha16;
                if (color.bitDepth().isEightBit()) {
                    alpha16 = (alphaSample * maxSampleL + 128) / 255;
                } else {
                    alpha16 = alphaSample;
                }
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x0000FFFF_FFFFFFFFL) | ((alpha16 & maxSampleL) << 48);
            }
        }
        return new AvifFrame(width, height, color.bitDepth(), color.pixelFormat(), frameIndex, combined);
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
