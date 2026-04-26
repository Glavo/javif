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

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifPixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.LongBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
