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

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacpp.BytePointer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.av_get_pix_fmt_name;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests that use JavaCPP FFmpeg bindings to read libavif Y4M video resources.
@NotNullByDefault
final class LibavifFFmpegY4mTest {
    /// Y4M resources copied from libavif's test data and their expected stream metadata.
    private static final Y4mResource @Unmodifiable [] Y4M_RESOURCES = new Y4mResource[]{
            new Y4mResource("libavif-test-data/cosmos1650_yuv444_10bpc_p3pq.y4m", 1024, 428, "yuv444p10le", 1),
            new Y4mResource("libavif-test-data/kodim03_yuv420_8bpc.y4m", 768, 512, "yuv420p", 1),
            new Y4mResource("libavif-test-data/kodim23_yuv420_8bpc.y4m", 768, 512, "yuv420p", 1),
            new Y4mResource("libavif-test-data/webp_logo_animated.y4m", 80, 80, "yuv444p", 19),
    };

    /// Verifies that FFmpeg can open every copied libavif Y4M reference resource.
    ///
    /// @return the dynamic Y4M resource tests
    @TestFactory
    Stream<DynamicTest> ffmpegReadsLibavifY4mResources() {
        return Arrays.stream(Y4M_RESOURCES)
                .map(resource -> DynamicTest.dynamicTest(resource.resourceName, () -> assertY4mResource(resource)));
    }

    /// Asserts one Y4M resource through FFmpeg.
    ///
    /// @param resource the expected Y4M resource metadata
    /// @throws IOException if the resource cannot be resolved
    /// @throws URISyntaxException if the resource URL cannot be converted to a path
    private static void assertY4mResource(Y4mResource resource) throws IOException, URISyntaxException {
        AVFormatContext formatContext = avformat_alloc_context();
        assertNotNull(formatContext);
        try {
            Path path = resourcePath(resource.resourceName);
            assertEquals(0, avformat_open_input(formatContext, path.toString(), null, null));
            assertEquals(0, avformat_find_stream_info(formatContext, (org.bytedeco.javacpp.PointerPointer<?>) null));

            int videoStreamIndex = findVideoStream(formatContext);
            assertTrue(videoStreamIndex >= 0);
            AVCodecParameters parameters = formatContext.streams(videoStreamIndex).codecpar();
            assertEquals(resource.width, parameters.width());
            assertEquals(resource.height, parameters.height());
            assertEquals(resource.pixelFormatName, pixelFormatName(parameters.format()));
            assertEquals(resource.frameCount, countVideoPackets(formatContext, videoStreamIndex));
        } finally {
            avformat_close_input(formatContext);
        }
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

    /// Counts packets belonging to the selected video stream.
    ///
    /// @param formatContext the opened format context
    /// @param videoStreamIndex the selected video stream index
    /// @return the number of video packets
    private static int countVideoPackets(AVFormatContext formatContext, int videoStreamIndex) {
        AVPacket packet = av_packet_alloc();
        assertNotNull(packet);
        int count = 0;
        try {
            while (av_read_frame(formatContext, packet) >= 0) {
                if (packet.stream_index() == videoStreamIndex) {
                    count++;
                }
                av_packet_unref(packet);
            }
        } finally {
            av_packet_free(packet);
        }
        return count;
    }

    /// Returns the FFmpeg pixel format name for a pixel format id.
    ///
    /// @param pixelFormat the FFmpeg pixel format id
    /// @return the pixel format name
    private static String pixelFormatName(int pixelFormat) {
        BytePointer name = av_get_pix_fmt_name(pixelFormat);
        assertNotNull(name);
        return name.getString();
    }

    /// Resolves one classpath resource to a filesystem path.
    ///
    /// @param resourceName the classpath resource name
    /// @return the resource path
    /// @throws IOException if the resource is not found
    /// @throws URISyntaxException if the resource URL cannot be converted to a path
    private static Path resourcePath(String resourceName) throws IOException, URISyntaxException {
        URL resource = LibavifFFmpegY4mTest.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new AssertionError("Missing test resource: " + resourceName);
        }
        return Path.of(resource.toURI());
    }

    /// Expected Y4M resource metadata.
    @NotNullByDefault
    private static final class Y4mResource {
        /// The classpath resource name.
        private final String resourceName;
        /// The expected video width.
        private final int width;
        /// The expected video height.
        private final int height;
        /// The expected FFmpeg pixel format name.
        private final String pixelFormatName;
        /// The expected video packet count.
        private final int frameCount;

        /// Creates expected Y4M resource metadata.
        ///
        /// @param resourceName the classpath resource name
        /// @param width the expected video width
        /// @param height the expected video height
        /// @param pixelFormatName the expected FFmpeg pixel format name
        /// @param frameCount the expected video packet count
        private Y4mResource(String resourceName, int width, int height, String pixelFormatName, int frameCount) {
            this.resourceName = resourceName;
            this.width = width;
            this.height = height;
            this.pixelFormatName = pixelFormatName;
            this.frameCount = frameCount;
        }
    }
}
