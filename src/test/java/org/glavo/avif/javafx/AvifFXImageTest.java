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
package org.glavo.avif.javafx;

import javafx.animation.Timeline;
import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImageReader;
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.AvifSequenceInfo;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.LongBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for converting decoded AVIF frames into JavaFX images.
@NotNullByDefault
final class AvifFXImageTest {
    /// Verifies that high-bit-depth frames are reduced to JavaFX-compatible ARGB pixels.
    @Test
    void rendersHighBitDepthFrameAsJavaFxArgbPixels() {
        AvifFrame frame = new AvifFrame(
                1,
                1,
                AvifBitDepth.TEN_BITS,
                AvifPixelFormat.I444,
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

    /// Verifies that AVIS timing and repetition metadata configure the JavaFX timeline.
    @Test
    void usesSequenceFrameDurationsAndRepetitionCount() {
        @Unmodifiable List<AvifFrame> frames = List.of(frame(0, 0xFF00_0000), frame(1, 0xFFFF_FFFF));
        AvifSequenceInfo sequenceInfo = new AvifSequenceInfo(2, 1_000, 350, 2, new int[]{100, 250});

        AvifFXImage image = new AvifFXImage(frames, sequenceInfo, false);
        @Nullable Timeline timeline = image.getAnimation();
        assertNotNull(timeline);

        assertEquals(3, timeline.getCycleCount());
        assertEquals(3, timeline.getKeyFrames().size());
        assertEquals(0.0, timeline.getKeyFrames().get(0).getTime().toMillis(), 0.000_001);
        assertEquals(100.0, timeline.getKeyFrames().get(1).getTime().toMillis(), 0.000_001);
        assertEquals(350.0, timeline.getKeyFrames().get(2).getTime().toMillis(), 0.000_001);
    }

    /// Verifies that sequence metadata cannot silently omit decoded frames.
    @Test
    void rejectsMismatchedSequenceFrameCount() {
        @Unmodifiable List<AvifFrame> frames = List.of(frame(0, 0xFF00_0000), frame(1, 0xFFFF_FFFF));
        AvifSequenceInfo sequenceInfo = new AvifSequenceInfo(3, 1_000, 300, 0, new int[]{100, 100, 100});

        assertThrows(IllegalArgumentException.class, () -> new AvifFXImage(frames, sequenceInfo, false));
    }

    /// Verifies that one JavaFX image cannot combine frames with different dimensions.
    @Test
    void rejectsMismatchedFrameDimensions() {
        AvifFrame firstFrame = frame(0, 0xFF00_0000);
        AvifFrame secondFrame = new AvifFrame(
                2,
                1,
                AvifBitDepth.EIGHT_BITS,
                AvifPixelFormat.I444,
                1,
                new int[]{0xFFFF_FFFF, 0xFFFF_FFFF}
        );

        assertThrows(IllegalArgumentException.class, () -> new AvifFXImage(List.of(firstFrame, secondFrame), false));
    }

    /// Creates one single-pixel frame for animation tests.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param pixel the packed ARGB pixel
    /// @return the decoded frame
    private static AvifFrame frame(int frameIndex, int pixel) {
        return new AvifFrame(
                1,
                1,
                AvifBitDepth.EIGHT_BITS,
                AvifPixelFormat.I444,
                frameIndex,
                new int[]{pixel}
        );
    }
}
