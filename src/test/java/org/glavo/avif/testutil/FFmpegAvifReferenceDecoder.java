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
package org.glavo.avif.testutil;

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.AvifPixelFormat;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avformat.AVStreamGroup;
import org.bytedeco.ffmpeg.avformat.AVStreamGroupTileGrid;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT;
import static org.bytedeco.ffmpeg.global.avformat.AV_STREAM_GROUP_PARAMS_TILE_GRID;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_CL;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_NCL;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_FCC;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M;
import static org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE240M;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_get_pix_fmt_name;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_BT2020;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_DEFAULT;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_FCC;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU624;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_SMPTE170M;
import static org.bytedeco.ffmpeg.global.swscale.SWS_CS_SMPTE240M;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCoefficients;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.bytedeco.ffmpeg.global.swscale.sws_setColorspaceDetails;

/// FFmpeg-backed AVIF reference decoder used by tests.
@NotNullByDefault
public final class FFmpegAvifReferenceDecoder {
    /// FFmpeg's AVERROR(EAGAIN) value on supported test platforms.
    private static final int AVERROR_EAGAIN = -11;
    /// The number of bytes in one 8-bit RGBA pixel.
    private static final int RGBA_BYTES_PER_PIXEL = 4;

    /// Prevents instantiation.
    private FFmpegAvifReferenceDecoder() {
    }

    /// Decodes the first video frame from one classpath AVIF resource with FFmpeg.
    ///
    /// The returned pixels are packed as non-premultiplied `0xAARRGGBB` values after FFmpeg converts
    /// the decoded frame to 8-bit RGBA.
    ///
    /// @param resourceName the classpath AVIF resource name
    /// @return the decoded first-frame pixels
    /// @throws IOException if FFmpeg or resource resolution fails
    /// @throws URISyntaxException if the classpath resource URL cannot be converted to a path
    public static ArgbImage decodeFirstFrameArgb(String resourceName) throws IOException, URISyntaxException {
        AVFormatContext formatContext = avformat_alloc_context();
        if (formatContext == null) {
            throw new IOException("Failed to allocate FFmpeg format context");
        }

        try {
            Path path = resourcePath(resourceName);
            check(avformat_open_input(formatContext, path.toString(), null, null), "open input: " + resourceName);
            check(avformat_find_stream_info(formatContext, (PointerPointer<?>) null), "find stream info: " + resourceName);

            @Nullable TileGridReference tileGrid = firstTileGridReference(formatContext, resourceName);
            if (tileGrid != null) {
                return decodeTileGridArgb(path, tileGrid, resourceName);
            }

            int videoStreamIndex = findVideoStream(formatContext);
            if (videoStreamIndex < 0) {
                throw new IOException("FFmpeg found no video stream: " + resourceName);
            }
            return decodeFirstFrameArgb(formatContext, videoStreamIndex, resourceName);
        } finally {
            avformat_close_input(formatContext);
        }
    }

    /// Decodes the first video frame from one classpath AVIF resource with FFmpeg and copies its
    /// decoded 8-bit source planes before RGB conversion.
    ///
    /// @param resourceName the classpath AVIF resource name
    /// @return the decoded first-frame source planes
    /// @throws IOException if FFmpeg or resource resolution fails
    /// @throws URISyntaxException if the classpath resource URL cannot be converted to a path
    public static SourcePlanes decodeFirstFrameSourcePlanes(String resourceName) throws IOException, URISyntaxException {
        AVFormatContext formatContext = avformat_alloc_context();
        if (formatContext == null) {
            throw new IOException("Failed to allocate FFmpeg format context");
        }

        try {
            Path path = resourcePath(resourceName);
            check(avformat_open_input(formatContext, path.toString(), null, null), "open input: " + resourceName);
            check(avformat_find_stream_info(formatContext, (PointerPointer<?>) null), "find stream info: " + resourceName);

            @Nullable TileGridReference tileGrid = firstTileGridReference(formatContext, resourceName);
            if (tileGrid != null) {
                return decodeTileGridSourcePlanes(path, tileGrid, resourceName);
            }

            int videoStreamIndex = findVideoStream(formatContext);
            if (videoStreamIndex < 0) {
                throw new IOException("FFmpeg found no video stream: " + resourceName);
            }
            return decodeFirstFrameSourcePlanes(formatContext, videoStreamIndex, resourceName);
        } finally {
            avformat_close_input(formatContext);
        }
    }

    /// Decodes the first frame from one selected FFmpeg stream.
    ///
    /// @param formatContext the opened format context
    /// @param videoStreamIndex the selected video stream index
    /// @param label the diagnostic label
    /// @return the decoded first-frame pixels
    /// @throws IOException if FFmpeg decoding fails
    private static ArgbImage decodeFirstFrameArgb(
            AVFormatContext formatContext,
            int videoStreamIndex,
            String label
    ) throws IOException {
        AVCodecParameters parameters = formatContext.streams(videoStreamIndex).codecpar();
        AVCodec codec = avcodec_find_decoder(parameters.codec_id());
        if (codec == null) {
            throw new IOException("FFmpeg found no decoder for video stream: " + label);
        }

        AVCodecContext codecContext = avcodec_alloc_context3(codec);
        if (codecContext == null) {
            throw new IOException("Failed to allocate FFmpeg codec context: " + label);
        }

        AVPacket packet = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        if (packet == null || frame == null) {
            av_packet_free(packet);
            av_frame_free(frame);
            avcodec_free_context(codecContext);
            throw new IOException("Failed to allocate FFmpeg decode buffers: " + label);
        }

        try {
            check(avcodec_parameters_to_context(codecContext, parameters), "copy codec parameters: " + label);
            check(avcodec_open2(codecContext, codec, (PointerPointer<?>) null), "open decoder: " + label);

            while (av_read_frame(formatContext, packet) >= 0) {
                try {
                    if (packet.stream_index() == videoStreamIndex) {
                        check(avcodec_send_packet(codecContext, packet), "send packet: " + label);
                        ArgbImage image = receiveFrame(codecContext, frame, label);
                        if (image != null) {
                            return image;
                        }
                    }
                } finally {
                    av_packet_unref(packet);
                }
            }

            check(avcodec_send_packet(codecContext, null), "flush decoder: " + label);
            ArgbImage image = receiveFrame(codecContext, frame, label);
            if (image != null) {
                return image;
            }
            throw new IOException("FFmpeg produced no decoded frame: " + label);
        } finally {
            av_packet_free(packet);
            av_frame_free(frame);
            avcodec_free_context(codecContext);
        }
    }

    /// Decodes one selected video stream from a fresh FFmpeg context.
    ///
    /// @param path the AVIF file path
    /// @param videoStreamIndex the selected video stream index
    /// @param label the diagnostic label
    /// @return the decoded first-frame pixels
    /// @throws IOException if FFmpeg decoding fails
    private static ArgbImage decodeFirstFrameArgb(Path path, int videoStreamIndex, String label) throws IOException {
        AVFormatContext formatContext = avformat_alloc_context();
        if (formatContext == null) {
            throw new IOException("Failed to allocate FFmpeg format context");
        }

        try {
            check(avformat_open_input(formatContext, path.toString(), null, null), "open input: " + label);
            check(avformat_find_stream_info(formatContext, (PointerPointer<?>) null), "find stream info: " + label);
            return decodeFirstFrameArgb(formatContext, videoStreamIndex, label);
        } finally {
            avformat_close_input(formatContext);
        }
    }

    /// Decodes the first frame from one selected FFmpeg stream and copies source planes.
    ///
    /// @param formatContext the opened format context
    /// @param videoStreamIndex the selected video stream index
    /// @param label the diagnostic label
    /// @return the decoded first-frame source planes
    /// @throws IOException if FFmpeg decoding fails
    private static SourcePlanes decodeFirstFrameSourcePlanes(
            AVFormatContext formatContext,
            int videoStreamIndex,
            String label
    ) throws IOException {
        AVCodecParameters parameters = formatContext.streams(videoStreamIndex).codecpar();
        AVCodec codec = avcodec_find_decoder(parameters.codec_id());
        if (codec == null) {
            throw new IOException("FFmpeg found no decoder for video stream: " + label);
        }

        AVCodecContext codecContext = avcodec_alloc_context3(codec);
        if (codecContext == null) {
            throw new IOException("Failed to allocate FFmpeg codec context: " + label);
        }

        AVPacket packet = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        if (packet == null || frame == null) {
            av_packet_free(packet);
            av_frame_free(frame);
            avcodec_free_context(codecContext);
            throw new IOException("Failed to allocate FFmpeg decode buffers: " + label);
        }

        try {
            check(avcodec_parameters_to_context(codecContext, parameters), "copy codec parameters: " + label);
            check(avcodec_open2(codecContext, codec, (PointerPointer<?>) null), "open decoder: " + label);

            while (av_read_frame(formatContext, packet) >= 0) {
                try {
                    if (packet.stream_index() == videoStreamIndex) {
                        check(avcodec_send_packet(codecContext, packet), "send packet: " + label);
                        SourcePlanes planes = receiveFrameSourcePlanes(codecContext, frame, label);
                        if (planes != null) {
                            return planes;
                        }
                    }
                } finally {
                    av_packet_unref(packet);
                }
            }

            check(avcodec_send_packet(codecContext, null), "flush decoder: " + label);
            SourcePlanes planes = receiveFrameSourcePlanes(codecContext, frame, label);
            if (planes != null) {
                return planes;
            }
            throw new IOException("FFmpeg produced no decoded frame: " + label);
        } finally {
            av_packet_free(packet);
            av_frame_free(frame);
            avcodec_free_context(codecContext);
        }
    }

    /// Decodes one selected video stream from a fresh FFmpeg context and copies source planes.
    ///
    /// @param path the AVIF file path
    /// @param videoStreamIndex the selected video stream index
    /// @param label the diagnostic label
    /// @return the decoded first-frame source planes
    /// @throws IOException if FFmpeg decoding fails
    private static SourcePlanes decodeFirstFrameSourcePlanes(Path path, int videoStreamIndex, String label)
            throws IOException {
        AVFormatContext formatContext = avformat_alloc_context();
        if (formatContext == null) {
            throw new IOException("Failed to allocate FFmpeg format context");
        }

        try {
            check(avformat_open_input(formatContext, path.toString(), null, null), "open input: " + label);
            check(avformat_find_stream_info(formatContext, (PointerPointer<?>) null), "find stream info: " + label);
            return decodeFirstFrameSourcePlanes(formatContext, videoStreamIndex, label);
        } finally {
            avformat_close_input(formatContext);
        }
    }

    /// Receives at most one decoded frame from FFmpeg.
    ///
    /// @param codecContext the opened codec context
    /// @param frame the reusable destination frame
    /// @param label the diagnostic label
    /// @return the decoded image, or `null` when FFmpeg needs more packet data
    /// @throws IOException if FFmpeg decoding or conversion fails
    private static ArgbImage receiveFrame(AVCodecContext codecContext, AVFrame frame, String label) throws IOException {
        int result = avcodec_receive_frame(codecContext, frame);
        if (result == 0) {
            try {
                return convertFrameToArgb(frame, label);
            } finally {
                av_frame_unref(frame);
            }
        }
        if (result == AVERROR_EAGAIN || result == AVERROR_EOF) {
            return null;
        }
        throw ffmpegException(result, "receive frame: " + label);
    }

    /// Receives at most one decoded frame from FFmpeg and copies its source planes.
    ///
    /// @param codecContext the opened codec context
    /// @param frame the reusable destination frame
    /// @param label the diagnostic label
    /// @return the decoded source planes, or `null` when FFmpeg needs more packet data
    /// @throws IOException if FFmpeg decoding or plane copying fails
    private static @Nullable SourcePlanes receiveFrameSourcePlanes(
            AVCodecContext codecContext,
            AVFrame frame,
            String label
    ) throws IOException {
        int result = avcodec_receive_frame(codecContext, frame);
        if (result == 0) {
            try {
                return copyFrameToSourcePlanes(frame, label);
            } finally {
                av_frame_unref(frame);
            }
        }
        if (result == AVERROR_EAGAIN || result == AVERROR_EOF) {
            return null;
        }
        throw ffmpegException(result, "receive frame: " + label);
    }

    /// Copies one decoded FFmpeg planar source frame.
    ///
    /// @param sourceFrame the decoded source frame
    /// @param label the diagnostic label
    /// @return the copied source planes
    /// @throws IOException if the frame format is unsupported
    private static SourcePlanes copyFrameToSourcePlanes(AVFrame sourceFrame, String label) throws IOException {
        int width = sourceFrame.width();
        int height = sourceFrame.height();
        if (width <= 0 || height <= 0) {
            throw new IOException("FFmpeg returned invalid frame dimensions for " + label + ": " + width + "x" + height);
        }

        FrameMetadata sourceMetadata = frameMetadata(sourceFrame.format());
        short[] luma = copyPlane(sourceFrame.data(0), sourceFrame.linesize(0), width, height, sourceMetadata);
        short @Nullable [] chromaU = null;
        short @Nullable [] chromaV = null;
        if (sourceMetadata.pixelFormat() != AvifPixelFormat.I400) {
            int chromaWidth = chromaWidth(width, sourceMetadata.pixelFormat());
            int chromaHeight = chromaHeight(height, sourceMetadata.pixelFormat());
            chromaU = copyPlane(sourceFrame.data(1), sourceFrame.linesize(1), chromaWidth, chromaHeight, sourceMetadata);
            chromaV = copyPlane(sourceFrame.data(2), sourceFrame.linesize(2), chromaWidth, chromaHeight, sourceMetadata);
        }
        return new SourcePlanes(width, height, sourceMetadata, luma, chromaU, chromaV);
    }

    /// Copies one byte-addressed FFmpeg plane into tightly packed row-major sample storage.
    ///
    /// @param data the source plane pointer
    /// @param lineSize the source plane line size in bytes
    /// @param width the visible plane width in samples
    /// @param height the visible plane height in samples
    /// @param sourceMetadata the normalized source frame metadata
    /// @return a tightly packed copy of the plane
    /// @throws IOException if the source plane is unavailable
    private static short[] copyPlane(
            BytePointer data,
            int lineSize,
            int width,
            int height,
            FrameMetadata sourceMetadata
    ) throws IOException {
        if (data == null) {
            throw new IOException("FFmpeg returned a null source plane");
        }
        int bytesPerSample = sourceMetadata.bitDepth().isEightBit() ? 1 : 2;
        boolean bigEndian = sourceMetadata.pixelFormatName().endsWith("be");
        short[] copy = new short[width * height];
        for (int y = 0; y < height; y++) {
            long sourceRow = (long) y * lineSize;
            int destinationRow = y * width;
            for (int x = 0; x < width; x++) {
                long sourceOffset = sourceRow + (long) x * bytesPerSample;
                if (bytesPerSample == 1) {
                    copy[destinationRow + x] = (short) (data.get(sourceOffset) & 0xFF);
                } else {
                    int first = data.get(sourceOffset) & 0xFF;
                    int second = data.get(sourceOffset + 1) & 0xFF;
                    copy[destinationRow + x] = (short) (bigEndian ? (first << 8) | second : first | (second << 8));
                }
            }
        }
        return copy;
    }

    /// Converts one decoded FFmpeg frame to packed 8-bit ARGB pixels.
    ///
    /// @param sourceFrame the decoded source frame
    /// @param label the diagnostic label
    /// @return the packed ARGB image
    /// @throws IOException if FFmpeg scaling fails
    private static ArgbImage convertFrameToArgb(AVFrame sourceFrame, String label) throws IOException {
        int width = sourceFrame.width();
        int height = sourceFrame.height();
        if (width <= 0 || height <= 0) {
            throw new IOException("FFmpeg returned invalid frame dimensions for " + label + ": " + width + "x" + height);
        }

        AVFrame rgbaFrame = av_frame_alloc();
        if (rgbaFrame == null) {
            throw new IOException("Failed to allocate FFmpeg RGBA frame: " + label);
        }

        SwsContext swsContext = null;
        try {
            rgbaFrame.format(AV_PIX_FMT_RGBA);
            rgbaFrame.width(width);
            rgbaFrame.height(height);
            check(av_frame_get_buffer(rgbaFrame, 1), "allocate RGBA frame: " + label);

            swsContext = sws_getContext(
                    width,
                    height,
                    sourceFrame.format(),
                    width,
                    height,
                    AV_PIX_FMT_RGBA,
                    SWS_BILINEAR,
                    null,
                    null,
                    (DoublePointer) null
            );
            if (swsContext == null) {
                throw new IOException("Failed to create FFmpeg swscale context: " + label);
            }
            int sourceRange = sourceFrame.color_range() == AVCOL_RANGE_JPEG ? 1 : 0;
            check(sws_setColorspaceDetails(
                    swsContext,
                    sws_getCoefficients(swsColorspace(sourceFrame.colorspace())),
                    sourceRange,
                    sws_getCoefficients(SWS_CS_DEFAULT),
                    1,
                    0,
                    1 << 16,
                    1 << 16
            ), "configure RGBA colorspace: " + label);

            int scaledRows = sws_scale(
                    swsContext,
                    sourceFrame.data(),
                    sourceFrame.linesize(),
                    0,
                    height,
                    rgbaFrame.data(),
                    rgbaFrame.linesize()
            );
            if (scaledRows != height) {
                throw new IOException("FFmpeg scaled " + scaledRows + " rows instead of " + height + ": " + label);
            }
            return copyRgbaFrameToArgb(rgbaFrame, width, height, frameMetadata(sourceFrame.format()));
        } finally {
            if (swsContext != null) {
                sws_freeContext(swsContext);
            }
            av_frame_free(rgbaFrame);
        }
    }

    /// Copies one FFmpeg RGBA frame into packed ARGB storage.
    ///
    /// @param rgbaFrame the FFmpeg RGBA frame
    /// @param width the frame width
    /// @param height the frame height
    /// @param sourceMetadata the source FFmpeg frame metadata before RGBA conversion
    /// @return the packed ARGB image
    private static ArgbImage copyRgbaFrameToArgb(
            AVFrame rgbaFrame,
            int width,
            int height,
            FrameMetadata sourceMetadata
    ) {
        int[] pixels = new int[width * height];
        BytePointer data = rgbaFrame.data(0);
        int lineSize = rgbaFrame.linesize(0);
        for (int y = 0; y < height; y++) {
            long rowOffset = (long) y * lineSize;
            for (int x = 0; x < width; x++) {
                long offset = rowOffset + (long) x * RGBA_BYTES_PER_PIXEL;
                int r = data.get(offset) & 0xFF;
                int g = data.get(offset + 1) & 0xFF;
                int b = data.get(offset + 2) & 0xFF;
                int a = data.get(offset + 3) & 0xFF;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return new ArgbImage(width, height, sourceMetadata, pixels);
    }

    /// Returns normalized metadata for a source FFmpeg pixel format id.
    ///
    /// @param pixelFormat the FFmpeg pixel format id
    /// @return the normalized frame metadata
    /// @throws IOException if the pixel format cannot be mapped to javif metadata
    private static FrameMetadata frameMetadata(int pixelFormat) throws IOException {
        String pixelFormatName = pixelFormatName(pixelFormat);
        if (pixelFormatName.startsWith("unknown(")) {
            throw new IOException("Cannot map unknown FFmpeg pixel format: " + pixelFormatName);
        }
        return new FrameMetadata(
                pixelFormatName,
                avifBitDepth(pixelFormatName),
                avifPixelFormat(pixelFormatName)
        );
    }

    /// Returns the FFmpeg pixel format name for a pixel format id.
    ///
    /// @param pixelFormat the FFmpeg pixel format id
    /// @return the pixel format name
    private static String pixelFormatName(int pixelFormat) {
        @Nullable BytePointer name = av_get_pix_fmt_name(pixelFormat);
        return name == null ? "unknown(" + pixelFormat + ")" : name.getString();
    }

    /// Maps one FFmpeg pixel format name to an AVIF bit depth.
    ///
    /// @param pixelFormatName the FFmpeg pixel format name
    /// @return the matching AVIF bit depth
    /// @throws IOException if the bit depth cannot be mapped
    private static AvifBitDepth avifBitDepth(String pixelFormatName) throws IOException {
        if (pixelFormatName.contains("12")) {
            return AvifBitDepth.TWELVE_BITS;
        }
        if (pixelFormatName.contains("10")) {
            return AvifBitDepth.TEN_BITS;
        }
        if (pixelFormatName.startsWith("gray")
                || pixelFormatName.startsWith("yuv")
                || pixelFormatName.startsWith("yuva")
                || pixelFormatName.startsWith("yuvj")) {
            return AvifBitDepth.EIGHT_BITS;
        }
        throw new IOException("Cannot map FFmpeg pixel format to AVIF bit depth: " + pixelFormatName);
    }

    /// Maps one FFmpeg pixel format name to an AVIF pixel format.
    ///
    /// @param pixelFormatName the FFmpeg pixel format name
    /// @return the matching AVIF pixel format
    /// @throws IOException if the chroma layout cannot be mapped
    private static AvifPixelFormat avifPixelFormat(String pixelFormatName) throws IOException {
        if (pixelFormatName.startsWith("gray")) {
            return AvifPixelFormat.I400;
        }
        if (pixelFormatName.startsWith("yuv420") || pixelFormatName.startsWith("yuva420")
                || pixelFormatName.startsWith("yuvj420")) {
            return AvifPixelFormat.I420;
        }
        if (pixelFormatName.startsWith("yuv422") || pixelFormatName.startsWith("yuva422")
                || pixelFormatName.startsWith("yuvj422")) {
            return AvifPixelFormat.I422;
        }
        if (pixelFormatName.startsWith("yuv444") || pixelFormatName.startsWith("yuva444")
                || pixelFormatName.startsWith("yuvj444")) {
            return AvifPixelFormat.I444;
        }
        throw new IOException("Cannot map FFmpeg pixel format to AVIF chroma layout: " + pixelFormatName);
    }

    /// Maps one FFmpeg color-space id to the matching swscale matrix id.
    ///
    /// @param colorSpace the FFmpeg `AVCOL_SPC_*` id
    /// @return the swscale color-space id
    private static int swsColorspace(int colorSpace) {
        if (colorSpace == AVCOL_SPC_BT709) {
            return SWS_CS_ITU709;
        }
        if (colorSpace == AVCOL_SPC_FCC) {
            return SWS_CS_FCC;
        }
        if (colorSpace == AVCOL_SPC_BT470BG) {
            return SWS_CS_ITU624;
        }
        if (colorSpace == AVCOL_SPC_SMPTE170M) {
            return SWS_CS_SMPTE170M;
        }
        if (colorSpace == AVCOL_SPC_SMPTE240M) {
            return SWS_CS_SMPTE240M;
        }
        if (colorSpace == AVCOL_SPC_BT2020_NCL || colorSpace == AVCOL_SPC_BT2020_CL) {
            return SWS_CS_BT2020;
        }
        return SWS_CS_DEFAULT;
    }

    /// Returns the first tile-grid stream group exposed by FFmpeg, if present.
    ///
    /// @param formatContext the opened format context
    /// @param label the diagnostic label
    /// @return the tile-grid reference, or `null`
    /// @throws IOException if FFmpeg exposes malformed tile-grid metadata
    private static @Nullable TileGridReference firstTileGridReference(AVFormatContext formatContext, String label)
            throws IOException {
        int streamGroupCount = formatContext.nb_stream_groups();
        for (int i = 0; i < streamGroupCount; i++) {
            AVStreamGroup group = formatContext.stream_groups(i);
            if (group == null
                    || group.isNull()
                    || group.type() != AV_STREAM_GROUP_PARAMS_TILE_GRID
                    || (group.disposition() & AV_DISPOSITION_DEFAULT) == 0) {
                continue;
            }
            AVStreamGroupTileGrid tileGrid = group.params_tile_grid();
            if (tileGrid == null || tileGrid.isNull()) {
                throw new IOException("FFmpeg tile-grid group has no parameters: " + label);
            }
            return tileGridReference(group, tileGrid, label);
        }
        return null;
    }

    /// Copies FFmpeg tile-grid metadata into Java arrays.
    ///
    /// @param group the FFmpeg stream group
    /// @param tileGrid the FFmpeg tile-grid parameters
    /// @param label the diagnostic label
    /// @return the copied tile-grid reference
    /// @throws IOException if the tile-grid metadata is malformed
    private static @Nullable TileGridReference tileGridReference(
            AVStreamGroup group,
            AVStreamGroupTileGrid tileGrid,
            String label
    ) throws IOException {
        int tileCount = tileGrid.nb_tiles();
        int width = tileGrid.width();
        int height = tileGrid.height();
        if (tileCount <= 0 || width <= 0 || height <= 0) {
            throw new IOException("FFmpeg returned invalid tile-grid dimensions for " + label);
        }

        int[] streamIndices = new int[tileCount];
        int[] horizontalOffsets = new int[tileCount];
        int[] verticalOffsets = new int[tileCount];
        for (int tile = 0; tile < tileCount; tile++) {
            int groupStreamIndex = tileGrid.offsets_idx(tile);
            if (groupStreamIndex < 0 || groupStreamIndex >= group.nb_streams()) {
                throw new IOException("FFmpeg tile-grid references invalid stream index for " + label);
            }
            AVStream stream = group.streams(groupStreamIndex);
            if (stream == null || stream.isNull()) {
                throw new IOException("FFmpeg tile-grid references a missing stream for " + label);
            }
            streamIndices[tile] = stream.index();
            horizontalOffsets[tile] = tileGrid.offsets_horizontal(tile) - tileGrid.horizontal_offset();
            verticalOffsets[tile] = tileGrid.offsets_vertical(tile) - tileGrid.vertical_offset();
        }
        return new TileGridReference(width, height, streamIndices, horizontalOffsets, verticalOffsets);
    }

    /// Decodes and composes a FFmpeg tile-grid stream group into one ARGB image.
    ///
    /// @param path the AVIF file path
    /// @param tileGrid the tile-grid metadata
    /// @param label the diagnostic label
    /// @return the composed tile-grid ARGB image
    /// @throws IOException if one tile cannot be decoded or composed
    private static ArgbImage decodeTileGridArgb(Path path, TileGridReference tileGrid, String label)
            throws IOException {
        int[] pixels = new int[tileGrid.width * tileGrid.height];
        @Nullable FrameMetadata sourceMetadata = null;
        for (int tile = 0; tile < tileGrid.tileCount(); tile++) {
            ArgbImage tileImage = decodeFirstFrameArgb(path, tileGrid.streamIndex(tile), label + " tile " + tile);
            sourceMetadata = compatibleTileGridMetadata(sourceMetadata, tileImage.sourceMetadata(), label);
            copyArgbTile(
                    tileImage,
                    pixels,
                    tileGrid.width,
                    tileGrid.height,
                    tileGrid.horizontalOffset(tile),
                    tileGrid.verticalOffset(tile)
            );
        }
        return new ArgbImage(
                tileGrid.width,
                tileGrid.height,
                Objects.requireNonNull(sourceMetadata, "sourceMetadata"),
                pixels
        );
    }

    /// Decodes and composes a FFmpeg tile-grid stream group into one source-plane image.
    ///
    /// @param path the AVIF file path
    /// @param tileGrid the tile-grid metadata
    /// @param label the diagnostic label
    /// @return the composed tile-grid source planes
    /// @throws IOException if one tile cannot be decoded or composed
    private static SourcePlanes decodeTileGridSourcePlanes(Path path, TileGridReference tileGrid, String label)
            throws IOException {
        short[] luma = new short[tileGrid.width * tileGrid.height];
        short @Nullable [] chromaU = null;
        short @Nullable [] chromaV = null;
        @Nullable FrameMetadata sourceMetadata = null;

        for (int tile = 0; tile < tileGrid.tileCount(); tile++) {
            SourcePlanes tilePlanes = decodeFirstFrameSourcePlanes(
                    path,
                    tileGrid.streamIndex(tile),
                    label + " tile " + tile
            );
            sourceMetadata = compatibleTileGridMetadata(sourceMetadata, tilePlanes.sourceMetadata(), label);
            copyPlaneTile(
                    tilePlanes.luma,
                    tilePlanes.width,
                    tilePlanes.height,
                    luma,
                    tileGrid.width,
                    tileGrid.height,
                    tileGrid.horizontalOffset(tile),
                    tileGrid.verticalOffset(tile)
            );

            if (sourceMetadata.pixelFormat() != AvifPixelFormat.I400) {
                if (chromaU == null || chromaV == null) {
                    int chromaSize = chromaWidth(tileGrid.width, sourceMetadata.pixelFormat())
                            * chromaHeight(tileGrid.height, sourceMetadata.pixelFormat());
                    chromaU = new short[chromaSize];
                    chromaV = new short[chromaSize];
                }
                copyChromaPlaneTile(tilePlanes, tileGrid, tile, chromaU, sourceMetadata.pixelFormat(), true);
                copyChromaPlaneTile(tilePlanes, tileGrid, tile, chromaV, sourceMetadata.pixelFormat(), false);
            }
        }

        return new SourcePlanes(
                tileGrid.width,
                tileGrid.height,
                Objects.requireNonNull(sourceMetadata, "sourceMetadata"),
                luma,
                chromaU,
                chromaV
        );
    }

    /// Returns compatible tile-grid metadata, or throws when a tile differs incompatibly.
    ///
    /// @param expected the existing metadata, or `null` for the first tile
    /// @param actual the current tile metadata
    /// @param label the diagnostic label
    /// @return the metadata to use for the composed grid
    /// @throws IOException if tile metadata is incompatible
    private static FrameMetadata compatibleTileGridMetadata(
            @Nullable FrameMetadata expected,
            FrameMetadata actual,
            String label
    ) throws IOException {
        if (expected == null) {
            return actual;
        }
        if (expected.equals(actual)) {
            return expected;
        }
        if (expected.bitDepth() == actual.bitDepth()) {
            if (expected.pixelFormat() == AvifPixelFormat.I400 && actual.pixelFormat() != AvifPixelFormat.I400) {
                return actual;
            }
            if (expected.pixelFormat() != AvifPixelFormat.I400 && actual.pixelFormat() == AvifPixelFormat.I400) {
                return expected;
            }
        }
        throw new IOException("FFmpeg tile-grid source metadata mismatch: " + label);
    }

    /// Copies one ARGB tile into a composed grid canvas.
    ///
    /// @param tile the decoded tile image
    /// @param pixels the destination packed pixel canvas
    /// @param width the destination width
    /// @param height the destination height
    /// @param offsetX the tile X offset
    /// @param offsetY the tile Y offset
    private static void copyArgbTile(
            ArgbImage tile,
            int[] pixels,
            int width,
            int height,
            int offsetX,
            int offsetY
    ) {
        int copy = clippedCopyWidth(tile.width, width, offsetX);
        int rows = clippedCopyHeight(tile.height, height, offsetY);
        if (copy <= 0 || rows <= 0) {
            return;
        }
        int srcX = Math.max(0, -offsetX);
        int srcY = Math.max(0, -offsetY);
        int destX = Math.max(0, offsetX);
        int destY = Math.max(0, offsetY);
        for (int y = 0; y < rows; y++) {
            System.arraycopy(
                    tile.pixels,
                    (srcY + y) * tile.width + srcX,
                    pixels,
                    (destY + y) * width + destX,
                    copy
            );
        }
    }

    /// Copies one chroma tile plane into a composed grid canvas.
    ///
    /// @param tilePlanes the decoded tile source planes
    /// @param tileGrid the tile-grid metadata
    /// @param tile the tile index
    /// @param destination the destination chroma plane
    /// @param pixelFormat the composed grid pixel format
    /// @param chromaU whether to copy chroma U instead of chroma V
    private static void copyChromaPlaneTile(
            SourcePlanes tilePlanes,
            TileGridReference tileGrid,
            int tile,
            short[] destination,
            AvifPixelFormat pixelFormat,
            boolean chromaU
    ) {
        int destWidth = chromaWidth(tileGrid.width, pixelFormat);
        int destHeight = chromaHeight(tileGrid.height, pixelFormat);
        int sourceWidth = chromaWidth(tilePlanes.width, pixelFormat);
        int sourceHeight = chromaHeight(tilePlanes.height, pixelFormat);
        int offsetX = chromaHorizontalOffset(tileGrid.horizontalOffset(tile), pixelFormat);
        int offsetY = chromaVerticalOffset(tileGrid.verticalOffset(tile), pixelFormat);
        short @Nullable [] source = chromaU ? tilePlanes.chromaU : tilePlanes.chromaV;
        if (source == null) {
            fillPlaneTile(
                    1 << (tilePlanes.sourceMetadata().bitDepth().bits() - 1),
                    sourceWidth,
                    sourceHeight,
                    destination,
                    destWidth,
                    destHeight,
                    offsetX,
                    offsetY
            );
        } else {
            copyPlaneTile(source, sourceWidth, sourceHeight, destination, destWidth, destHeight, offsetX, offsetY);
        }
    }

    /// Copies one planar tile into a composed grid plane.
    ///
    /// @param source the source tile plane
    /// @param sourceWidth the source tile plane width
    /// @param sourceHeight the source tile plane height
    /// @param destination the destination composed plane
    /// @param destinationWidth the destination plane width
    /// @param destinationHeight the destination plane height
    /// @param offsetX the tile X offset
    /// @param offsetY the tile Y offset
    private static void copyPlaneTile(
            short[] source,
            int sourceWidth,
            int sourceHeight,
            short[] destination,
            int destinationWidth,
            int destinationHeight,
            int offsetX,
            int offsetY
    ) {
        int copy = clippedCopyWidth(sourceWidth, destinationWidth, offsetX);
        int rows = clippedCopyHeight(sourceHeight, destinationHeight, offsetY);
        if (copy <= 0 || rows <= 0) {
            return;
        }
        int srcX = Math.max(0, -offsetX);
        int srcY = Math.max(0, -offsetY);
        int destX = Math.max(0, offsetX);
        int destY = Math.max(0, offsetY);
        for (int y = 0; y < rows; y++) {
            System.arraycopy(
                    source,
                    (srcY + y) * sourceWidth + srcX,
                    destination,
                    (destY + y) * destinationWidth + destX,
                    copy
            );
        }
    }

    /// Fills one clipped planar tile in a composed grid plane.
    ///
    /// @param value the unsigned sample value to fill
    /// @param sourceWidth the source tile plane width
    /// @param sourceHeight the source tile plane height
    /// @param destination the destination composed plane
    /// @param destinationWidth the destination plane width
    /// @param destinationHeight the destination plane height
    /// @param offsetX the tile X offset
    /// @param offsetY the tile Y offset
    private static void fillPlaneTile(
            int value,
            int sourceWidth,
            int sourceHeight,
            short[] destination,
            int destinationWidth,
            int destinationHeight,
            int offsetX,
            int offsetY
    ) {
        int copy = clippedCopyWidth(sourceWidth, destinationWidth, offsetX);
        int rows = clippedCopyHeight(sourceHeight, destinationHeight, offsetY);
        if (copy <= 0 || rows <= 0) {
            return;
        }
        int destX = Math.max(0, offsetX);
        int destY = Math.max(0, offsetY);
        for (int y = 0; y < rows; y++) {
            Arrays.fill(
                    destination,
                    (destY + y) * destinationWidth + destX,
                    (destY + y) * destinationWidth + destX + copy,
                    (short) value
            );
        }
    }

    /// Returns the clipped horizontal copy length for one tile.
    ///
    /// @param sourceWidth the source tile width
    /// @param destinationWidth the destination plane width
    /// @param offsetX the tile X offset
    /// @return the clipped copy width
    private static int clippedCopyWidth(int sourceWidth, int destinationWidth, int offsetX) {
        int srcX = Math.max(0, -offsetX);
        int destX = Math.max(0, offsetX);
        return Math.min(sourceWidth - srcX, destinationWidth - destX);
    }

    /// Returns the clipped vertical copy length for one tile.
    ///
    /// @param sourceHeight the source tile height
    /// @param destinationHeight the destination plane height
    /// @param offsetY the tile Y offset
    /// @return the clipped copy height
    private static int clippedCopyHeight(int sourceHeight, int destinationHeight, int offsetY) {
        int srcY = Math.max(0, -offsetY);
        int destY = Math.max(0, offsetY);
        return Math.min(sourceHeight - srcY, destinationHeight - destY);
    }

    /// Returns a chroma-plane X offset for one luma-plane tile offset.
    ///
    /// @param lumaOffset the luma-plane X offset
    /// @param pixelFormat the source pixel format
    /// @return the chroma-plane X offset
    private static int chromaHorizontalOffset(int lumaOffset, AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I444 -> lumaOffset;
            case I420, I422 -> Math.floorDiv(lumaOffset, 2);
        };
    }

    /// Returns a chroma-plane Y offset for one luma-plane tile offset.
    ///
    /// @param lumaOffset the luma-plane Y offset
    /// @param pixelFormat the source pixel format
    /// @return the chroma-plane Y offset
    private static int chromaVerticalOffset(int lumaOffset, AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I422, I444 -> lumaOffset;
            case I420 -> Math.floorDiv(lumaOffset, 2);
        };
    }

    /// Finds the first video stream index in an opened FFmpeg format context.
    ///
    /// @param formatContext the opened format context
    /// @return the zero-based video stream index, or -1
    private static int findVideoStream(AVFormatContext formatContext) {
        int streamCount = formatContext.nb_streams();
        for (int i = 0; i < streamCount; i++) {
            AVStream stream = formatContext.streams(i);
            if (stream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                return i;
            }
        }
        return -1;
    }

    /// Returns the chroma plane width for one luma width and AVIF pixel format.
    ///
    /// @param lumaWidth the luma width in samples
    /// @param pixelFormat the AVIF pixel format
    /// @return the chroma plane width in samples
    private static int chromaWidth(int lumaWidth, AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I444 -> lumaWidth;
            case I420, I422 -> (lumaWidth + 1) >> 1;
        };
    }

    /// Returns the chroma plane height for one luma height and AVIF pixel format.
    ///
    /// @param lumaHeight the luma height in samples
    /// @param pixelFormat the AVIF pixel format
    /// @return the chroma plane height in samples
    private static int chromaHeight(int lumaHeight, AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I422, I444 -> lumaHeight;
            case I420 -> (lumaHeight + 1) >> 1;
        };
    }

    /// Resolves one classpath resource to a filesystem path.
    ///
    /// @param resourceName the classpath resource name
    /// @return the resource path
    /// @throws IOException if the resource is missing
    /// @throws URISyntaxException if the resource URL cannot be converted to a path
    private static Path resourcePath(String resourceName) throws IOException, URISyntaxException {
        URL resource = FFmpegAvifReferenceDecoder.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IOException("Missing test resource: " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    /// Checks one FFmpeg return code.
    ///
    /// @param result the FFmpeg return code
    /// @param action the attempted action
    /// @throws IOException if the return code is negative
    private static void check(int result, String action) throws IOException {
        if (result < 0) {
            throw ffmpegException(result, action);
        }
    }

    /// Creates an `IOException` for one FFmpeg error code.
    ///
    /// @param errorCode the FFmpeg error code
    /// @param action the attempted action
    /// @return the exception
    private static IOException ffmpegException(int errorCode, String action) {
        byte[] buffer = new byte[256];
        int result = av_strerror(errorCode, buffer, buffer.length);
        String message = result < 0 ? "unknown FFmpeg error " + errorCode : cString(buffer);
        return new IOException("FFmpeg failed to " + action + ": " + message);
    }

    /// Converts one null-terminated byte buffer to a Java string.
    ///
    /// @param buffer the byte buffer
    /// @return the string up to the first null byte
    private static String cString(byte[] buffer) {
        int length = 0;
        while (length < buffer.length && buffer[length] != 0) {
            length++;
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    /// Copied FFmpeg tile-grid stream-group metadata.
    ///
    /// @param width the composed grid width
    /// @param height the composed grid height
    /// @param streamIndices the tile stream indices
    /// @param horizontalOffsets the tile horizontal offsets
    /// @param verticalOffsets the tile vertical offsets
    @NotNullByDefault
    private record TileGridReference(
            int width,
            int height,
            int @Unmodifiable [] streamIndices,
            int @Unmodifiable [] horizontalOffsets,
            int @Unmodifiable [] verticalOffsets
    ) {
        /// Creates copied tile-grid metadata.
        ///
        /// @param width the composed grid width
        /// @param height the composed grid height
        /// @param streamIndices the tile stream indices
        /// @param horizontalOffsets the tile horizontal offsets
        /// @param verticalOffsets the tile vertical offsets
        private TileGridReference {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Tile-grid dimensions must be positive");
            }
            if (streamIndices.length == 0
                    || horizontalOffsets.length != streamIndices.length
                    || verticalOffsets.length != streamIndices.length) {
                throw new IllegalArgumentException("Tile-grid arrays must have matching non-empty lengths");
            }
            streamIndices = Arrays.copyOf(streamIndices, streamIndices.length);
            horizontalOffsets = Arrays.copyOf(horizontalOffsets, horizontalOffsets.length);
            verticalOffsets = Arrays.copyOf(verticalOffsets, verticalOffsets.length);
        }

        /// Returns the number of tiles.
        ///
        /// @return the tile count
        private int tileCount() {
            return streamIndices.length;
        }

        /// Returns one tile stream index.
        ///
        /// @param tile the tile index
        /// @return the stream index
        private int streamIndex(int tile) {
            return streamIndices[tile];
        }

        /// Returns one tile horizontal offset.
        ///
        /// @param tile the tile index
        /// @return the horizontal offset
        private int horizontalOffset(int tile) {
            return horizontalOffsets[tile];
        }

        /// Returns one tile vertical offset.
        ///
        /// @param tile the tile index
        /// @return the vertical offset
        private int verticalOffset(int tile) {
            return verticalOffsets[tile];
        }
    }

    /// Packed ARGB image decoded by FFmpeg.
    @NotNullByDefault
    public static final class ArgbImage {
        /// The image width.
        private final int width;
        /// The image height.
        private final int height;
        /// The normalized source FFmpeg frame metadata before RGBA conversion.
        private final FrameMetadata sourceMetadata;
        /// The packed immutable `0xAARRGGBB` pixel array.
        private final int @Unmodifiable [] pixels;

        /// Creates one packed ARGB image.
        ///
        /// @param width the image width
        /// @param height the image height
        /// @param sourceMetadata the normalized source FFmpeg frame metadata before RGBA conversion
        /// @param pixels the packed `0xAARRGGBB` pixels
        private ArgbImage(int width, int height, FrameMetadata sourceMetadata, int[] pixels) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Image dimensions must be positive");
            }
            if (pixels.length != width * height) {
                throw new IllegalArgumentException("Pixel count does not match image dimensions");
            }
            this.width = width;
            this.height = height;
            this.sourceMetadata = sourceMetadata;
            this.pixels = Arrays.copyOf(pixels, pixels.length);
        }

        /// Returns the image width.
        ///
        /// @return the image width
        public int width() {
            return width;
        }

        /// Returns the image height.
        ///
        /// @return the image height
        public int height() {
            return height;
        }

        /// Returns the normalized source FFmpeg frame metadata before RGBA conversion.
        ///
        /// @return the normalized source FFmpeg frame metadata
        public FrameMetadata sourceMetadata() {
            return sourceMetadata;
        }

        /// Returns a copy with AVIF rotation and mirror transforms applied to the packed pixels.
        ///
        /// @param rotationCode the AVIF `irot` rotation code, or -1 when absent
        /// @param mirrorAxis the AVIF `imir` mirror axis, or -1 when absent
        /// @return the transformed image, or this image when no transforms are present
        public ArgbImage transformed(int rotationCode, int mirrorAxis) {
            if (rotationCode <= 0 && mirrorAxis < 0) {
                return this;
            }

            int transformedWidth = width;
            int transformedHeight = height;
            int[] transformedPixels = Arrays.copyOf(pixels, pixels.length);

            if (rotationCode > 0) {
                transformedPixels = rotate(transformedPixels, transformedWidth, transformedHeight, rotationCode);
                if (rotationCode == 1 || rotationCode == 3) {
                    int oldWidth = transformedWidth;
                    transformedWidth = transformedHeight;
                    transformedHeight = oldWidth;
                }
            }
            if (mirrorAxis >= 0) {
                transformedPixels = mirror(transformedPixels, transformedWidth, transformedHeight, mirrorAxis);
            }

            return new ArgbImage(transformedWidth, transformedHeight, sourceMetadata, transformedPixels);
        }

        /// Returns one packed `0xAARRGGBB` pixel.
        ///
        /// @param x the pixel X coordinate
        /// @param y the pixel Y coordinate
        /// @return the packed pixel
        public int pixel(int x, int y) {
            return pixels[y * width + x];
        }

        /// Applies an AVIF `irot` transform to packed pixels.
        ///
        /// @param sourcePixels the source pixels
        /// @param sourceWidth the source width
        /// @param sourceHeight the source height
        /// @param rotationCode the AVIF `irot` rotation code
        /// @return the rotated pixels
        private static int[] rotate(int @Unmodifiable [] sourcePixels, int sourceWidth, int sourceHeight, int rotationCode) {
            return switch (rotationCode) {
                case 1 -> {
                    int newWidth = sourceHeight;
                    int newHeight = sourceWidth;
                    int[] result = new int[newWidth * newHeight];
                    for (int y = 0; y < newHeight; y++) {
                        for (int x = 0; x < newWidth; x++) {
                            result[y * newWidth + x] = sourcePixels[x * sourceWidth + (sourceWidth - 1 - y)];
                        }
                    }
                    yield result;
                }
                case 2 -> {
                    int[] result = new int[sourceWidth * sourceHeight];
                    for (int y = 0; y < sourceHeight; y++) {
                        for (int x = 0; x < sourceWidth; x++) {
                            result[y * sourceWidth + x] =
                                    sourcePixels[(sourceHeight - 1 - y) * sourceWidth + (sourceWidth - 1 - x)];
                        }
                    }
                    yield result;
                }
                case 3 -> {
                    int newWidth = sourceHeight;
                    int newHeight = sourceWidth;
                    int[] result = new int[newWidth * newHeight];
                    for (int y = 0; y < newHeight; y++) {
                        for (int x = 0; x < newWidth; x++) {
                            result[y * newWidth + x] = sourcePixels[(sourceHeight - 1 - x) * sourceWidth + y];
                        }
                    }
                    yield result;
                }
                default -> Arrays.copyOf(sourcePixels, sourcePixels.length);
            };
        }

        /// Applies an AVIF `imir` transform to packed pixels.
        ///
        /// @param sourcePixels the source pixels
        /// @param width the image width
        /// @param height the image height
        /// @param mirrorAxis the AVIF `imir` mirror axis
        /// @return the mirrored pixels
        private static int[] mirror(int @Unmodifiable [] sourcePixels, int width, int height, int mirrorAxis) {
            int[] result = new int[width * height];
            if (mirrorAxis == 0) {
                for (int y = 0; y < height; y++) {
                    int srcRow = (height - 1 - y) * width;
                    int destRow = y * width;
                    System.arraycopy(sourcePixels, srcRow, result, destRow, width);
                }
            } else {
                for (int y = 0; y < height; y++) {
                    int rowBase = y * width;
                    for (int x = 0; x < width; x++) {
                        result[rowBase + x] = sourcePixels[rowBase + (width - 1 - x)];
                    }
                }
            }
            return result;
        }
    }

    /// FFmpeg source planes decoded before RGB conversion.
    @NotNullByDefault
    public static final class SourcePlanes {
        /// The luma plane width.
        private final int width;
        /// The luma plane height.
        private final int height;
        /// The normalized source FFmpeg frame metadata.
        private final FrameMetadata sourceMetadata;
        /// The tightly packed luma plane.
        private final short[] luma;
        /// The tightly packed chroma U plane, or `null` for monochrome frames.
        private final short @Nullable [] chromaU;
        /// The tightly packed chroma V plane, or `null` for monochrome frames.
        private final short @Nullable [] chromaV;

        /// Creates copied source planes.
        ///
        /// @param width the luma plane width
        /// @param height the luma plane height
        /// @param sourceMetadata the normalized source FFmpeg frame metadata
        /// @param luma the tightly packed luma plane
        /// @param chromaU the tightly packed chroma U plane, or `null`
        /// @param chromaV the tightly packed chroma V plane, or `null`
        private SourcePlanes(
                int width,
                int height,
                FrameMetadata sourceMetadata,
                short[] luma,
                short @Nullable [] chromaU,
                short @Nullable [] chromaV
        ) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Plane dimensions must be positive");
            }
            if (luma.length != width * height) {
                throw new IllegalArgumentException("Luma sample count does not match image dimensions");
            }
            this.width = width;
            this.height = height;
            this.sourceMetadata = Objects.requireNonNull(sourceMetadata, "sourceMetadata");
            this.luma = Arrays.copyOf(Objects.requireNonNull(luma, "luma"), luma.length);
            this.chromaU = chromaU != null ? Arrays.copyOf(chromaU, chromaU.length) : null;
            this.chromaV = chromaV != null ? Arrays.copyOf(chromaV, chromaV.length) : null;
        }

        /// Returns the luma plane width.
        ///
        /// @return the luma plane width
        public int width() {
            return width;
        }

        /// Returns the luma plane height.
        ///
        /// @return the luma plane height
        public int height() {
            return height;
        }

        /// Returns the normalized source FFmpeg frame metadata.
        ///
        /// @return the normalized source FFmpeg frame metadata
        public FrameMetadata sourceMetadata() {
            return sourceMetadata;
        }

        /// Returns one luma sample.
        ///
        /// @param x the zero-based luma X coordinate
        /// @param y the zero-based luma Y coordinate
        /// @return the unsigned luma sample
        public int lumaSample(int x, int y) {
            return luma[y * width + x] & 0xFFFF;
        }

        /// Returns one chroma U sample.
        ///
        /// @param x the zero-based chroma X coordinate
        /// @param y the zero-based chroma Y coordinate
        /// @return the unsigned chroma U sample
        public int chromaUSample(int x, int y) {
            short[] plane = Objects.requireNonNull(chromaU, "chromaU");
            return plane[y * chromaWidth() + x] & 0xFFFF;
        }

        /// Returns one chroma V sample.
        ///
        /// @param x the zero-based chroma X coordinate
        /// @param y the zero-based chroma Y coordinate
        /// @return the unsigned chroma V sample
        public int chromaVSample(int x, int y) {
            short[] plane = Objects.requireNonNull(chromaV, "chromaV");
            return plane[y * chromaWidth() + x] & 0xFFFF;
        }

        /// Returns the chroma plane width.
        ///
        /// @return the chroma plane width
        public int chromaWidth() {
            return FFmpegAvifReferenceDecoder.chromaWidth(width, sourceMetadata.pixelFormat());
        }

        /// Returns the chroma plane height.
        ///
        /// @return the chroma plane height
        public int chromaHeight() {
            return FFmpegAvifReferenceDecoder.chromaHeight(height, sourceMetadata.pixelFormat());
        }
    }

    /// Normalized metadata from one FFmpeg source frame before RGBA conversion.
    ///
    /// @param pixelFormatName the FFmpeg source pixel format name
    /// @param bitDepth the normalized AVIF bit depth
    /// @param pixelFormat the normalized AVIF pixel format
    @NotNullByDefault
    public record FrameMetadata(String pixelFormatName, AvifBitDepth bitDepth, AvifPixelFormat pixelFormat) {
    }
}
