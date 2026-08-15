// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.javafx;

import javafx.animation.Animation;
import javafx.animation.Timeline;
import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImage;
import org.glavo.avif.AvifImageReader;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.AvifSequenceInfo;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Tests for converting decoded AVIF frames into JavaFX images.
@NotNullByDefault
final class AvifFXImageTest {
    /// Verifies that fully decoded animated content supplies frames and timing directly.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void createsAnimatedJavaFxImageFromAvifImage() throws IOException {
        AvifImage decoded = AvifImage.read(new ByteArrayInputStream(
                TestResources.readBytes("libavif-test-data/colors-animated-8bpc.avif")
        ));

        AvifFXImage image = new AvifFXImage(decoded, false);
        @Nullable Timeline timeline = image.getAnimation();

        assertEquals(decoded.info().width(), (int) image.getWidth());
        assertEquals(decoded.info().height(), (int) image.getHeight());
        assertNotNull(timeline);
        assertEquals(decoded.frames().size() + 1, timeline.getKeyFrames().size());

        AvifSequenceInfo sequenceInfo = decoded.info().sequenceInfo();
        assertNotNull(sequenceInfo);
        int[] frameDurations = sequenceInfo.frameDurations();
        double currentTimeMillis = 0.0;
        for (int i = 0; i < frameDurations.length; i++) {
            assertEquals(currentTimeMillis, timeline.getKeyFrames().get(i).getTime().toMillis(), 0.000_001);
            currentTimeMillis += frameDurations[i] * 1000.0 / sequenceInfo.mediaTimescale();
        }
        assertEquals(currentTimeMillis, timeline.getKeyFrames().get(frameDurations.length).getTime().toMillis(),
                0.000_001);

        int repetitionCount = sequenceInfo.repetitionCount();
        int expectedCycleCount = repetitionCount == AvifSequenceInfo.REPETITION_COUNT_UNKNOWN
                || repetitionCount == AvifSequenceInfo.REPETITION_COUNT_INFINITE
                ? Animation.INDEFINITE
                : repetitionCount + 1;
        assertEquals(expectedCycleCount, timeline.getCycleCount());
    }

    /// Verifies that high-bit-depth frames are reduced to JavaFX-compatible ARGB pixels.
    @Test
    void rendersHighBitDepthFrameAsJavaFxArgbPixels() {
        AvifFrame frame = new AvifFrame(
                1,
                1,
                AvifBitDepth.TEN_BITS,
                Av1ChromaFormat.YUV444,
                0,
                LongBuffer.wrap(new long[]{0xFFFF_8080_4040_0000L}).asReadOnlyBuffer()
        );

        AvifFXImage image = new AvifFXImage(frame);

        assertEquals(1, (int) image.getWidth());
        assertEquals(1, (int) image.getHeight());
        assertEquals(0xFF80_4000, image.getPixelReader().getArgb(0, 0));
    }

    /// Verifies that the high-bit-depth `clop_irot_imor` fixture paints real JavaFX pixels.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    @Test
    void rendersClopIrotImorFixturePixels() throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes("libavif-test-data/clop_irot_imor.avif"))) {
            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);

            AvifFXImage image = new AvifFXImage(frame);

            assertEquals(34, (int) image.getWidth());
            assertEquals(12, (int) image.getHeight());
            assertNotEquals(0, image.getPixelReader().getArgb(0, 0));
        }
    }

}
