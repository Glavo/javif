// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif;

import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for eagerly decoded [AvifImage] content.
@NotNullByDefault
final class AvifImageTest {
    /// A one-pixel still-image fixture from libavif.
    private static final String STILL_FIXTURE = "libavif-test-data/white_1x1.avif";
    /// An animated fixture from libavif.
    private static final String ANIMATED_FIXTURE = "libavif-test-data/colors-animated-8bpc.avif";
    /// A high-bit-depth fixture from libavif.
    private static final String HIGH_BIT_DEPTH_FIXTURE = "libavif-test-data/colors_hdr_srgb.avif";

    /// Verifies that file input is fully materialized and its owned handle is released.
    ///
    /// @throws IOException if the fixture cannot be written or decoded
    @Test
    void readsFileAndReleasesOwnedHandle() throws IOException {
        Path directory = Path.of("build", "tmp", "test", "AvifImageTest");
        Files.createDirectories(directory);
        Path path = directory.resolve("still-" + System.nanoTime() + ".avif");
        Files.write(path, TestResources.readBytes(STILL_FIXTURE));

        AvifImage image = AvifImage.read(path);

        assertEquals(1, image.info().width());
        assertEquals(1, image.info().height());
        assertEquals(1, image.info().frameCount());
        assertEquals(1, image.frames().size());
        assertSame(image.frames().get(0), image.firstFrame());
        assertThrows(UnsupportedOperationException.class, () -> image.frames().clear());

        Files.delete(path);
        assertFalse(Files.exists(path));
    }

    /// Verifies that array and buffer inputs are borrowed without changing buffer bounds.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsBorrowedArrayAndBufferRegion() throws IOException {
        byte[] bytes = TestResources.readBytes(STILL_FIXTURE);

        AvifImage arrayImage = AvifImage.read(bytes);

        byte[] envelope = new byte[bytes.length + 2];
        System.arraycopy(bytes, 0, envelope, 1, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        buffer.position(1);
        buffer.limit(1 + bytes.length);
        int position = buffer.position();
        int limit = buffer.limit();

        AvifImage bufferImage = AvifImage.read(buffer);

        assertEquals(1, arrayImage.info().width());
        assertEquals(1, arrayImage.info().height());
        assertEquals(arrayImage.info().width(), bufferImage.info().width());
        assertEquals(arrayImage.info().height(), bufferImage.info().height());
        assertEquals(arrayImage.info().frameCount(), bufferImage.info().frameCount());
        assertEquals(position, buffer.position());
        assertEquals(limit, buffer.limit());
    }

    /// Verifies that stream input materializes every sequence frame without taking ownership.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsAnimatedStreamWithoutClosingBorrowedInput() throws IOException {
        CloseTrackingInputStream input = new CloseTrackingInputStream(TestResources.readBytes(ANIMATED_FIXTURE));

        AvifImage image = AvifImage.read(input);

        assertFalse(input.closed());
        assertTrue(image.info().animated());
        assertEquals(image.info().frameCount(), image.frames().size());
        for (int i = 0; i < image.frames().size(); i++) {
            assertEquals(i, image.frames().get(i).frameIndex());
        }
    }

    /// Verifies that eager decoding honors options from a supplied reader factory.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void suppliedFactoryControlsDecodedPixelFormat() throws IOException {
        AvifImageReaderFactory factory = AvifImageReaderFactory.DEFAULT
                .withOutputPixelFormat(AvifPixelFormat.ARGB_8888);
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                TestResources.readBytes(HIGH_BIT_DEPTH_FIXTURE)
        );

        AvifImage image = AvifImage.read(input, factory);

        assertEquals(AvifPixelFormat.ARGB_8888, image.firstFrame().pixelFormat());
        assertFalse(input.closed());
    }

    /// Verifies that channel input remains open and honors a supplied reader factory.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void readsChannelWithoutClosingBorrowedInput() throws IOException {
        AvifImageReaderFactory factory = AvifImageReaderFactory.DEFAULT
                .withOutputPixelFormat(AvifPixelFormat.ARGB_8888);
        ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(
                TestResources.readBytes(HIGH_BIT_DEPTH_FIXTURE)
        ));
        try {
            AvifImage image = AvifImage.read(channel, factory);

            assertEquals(AvifPixelFormat.ARGB_8888, image.firstFrame().pixelFormat());
            assertTrue(channel.isOpen());
        } finally {
            channel.close();
        }
    }

    /// Verifies that a parsing failure does not close a borrowed stream.
    @Test
    void failedReadDoesNotCloseBorrowedInput() {
        CloseTrackingInputStream input = new CloseTrackingInputStream(new byte[]{0, 1, 2, 3});

        assertThrows(AvifDecodeException.class, () -> AvifImage.read(input));
        assertFalse(input.closed());
    }

    /// Byte-array stream that records whether ownership was taken.
    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        /// Whether [#close()] was invoked.
        private boolean closed;

        /// Creates a tracking stream over the supplied bytes.
        ///
        /// @param bytes the source bytes
        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Records closure before delegating to the byte-array stream.
        ///
        /// @throws IOException if the superclass rejects closure
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        /// Returns whether the stream was closed.
        ///
        /// @return whether the stream was closed
        private boolean closed() {
            return closed;
        }
    }
}
