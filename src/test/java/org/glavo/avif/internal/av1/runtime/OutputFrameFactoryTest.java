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
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for runtime frame-output factory dispatch.
@NotNullByDefault
final class OutputFrameFactoryTest {
    /// Verifies that 8-bit decoded planes produce one public `DecodedFrame`.
    @Test
    void createFrameReturnsDecodedFrameForEightBitPlanes() {
        DecodedPlanes decodedPlanes = RuntimeTestFixtures.createDecodedPlanes(8, 73);
        FrameHeader frameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, true, 0x01);

        DecodedFrame frame = OutputFrameFactory.createFrame(decodedPlanes, frameHeader, false, 5L);

        assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
        assertEquals(1, frame.intPixels().length);
        assertTrue(frame.intPixelBuffer().isReadOnly());
        assertFrameMetadata(frame, 8, PixelFormat.I400, FrameType.KEY, false, 5L);
    }

    /// Verifies that 10-bit and 12-bit decoded planes both produce one public `DecodedFrame`.
    @Test
    void createFrameReturnsDecodedFrameForHighBitDepthPlanes() {
        FrameHeader intraFrameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.INTRA, true, 0x00);

        DecodedFrame tenBitFrame = OutputFrameFactory.createFrame(
                RuntimeTestFixtures.createDecodedPlanes(10, 512),
                intraFrameHeader,
                true,
                8L
        );
        DecodedFrame twelveBitFrame = OutputFrameFactory.createFrame(
                RuntimeTestFixtures.createDecodedPlanes(12, 2048),
                intraFrameHeader,
                false,
                9L
        );

        assertTrue(tenBitFrame.bitDepth().isHighBitDepth());
        assertEquals(1, tenBitFrame.longPixels().length);
        assertTrue(tenBitFrame.longPixelBuffer().isReadOnly());
        assertFrameMetadata(tenBitFrame, 10, PixelFormat.I400, FrameType.INTRA, true, 8L);

        assertTrue(twelveBitFrame.bitDepth().isHighBitDepth());
        assertEquals(1, twelveBitFrame.longPixels().length);
        assertTrue(twelveBitFrame.longPixelBuffer().isReadOnly());
        assertFrameMetadata(twelveBitFrame, 12, PixelFormat.I400, FrameType.INTRA, false, 9L);
    }

    /// Verifies that stored reference surfaces are exposed through the existing-frame path as visible output.
    @Test
    void createExistingFrameReturnsVisibleFrameBackedByStoredSurfaceBitDepth() {
        FrameHeader referencedFrameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.SWITCH, false, 0x20);
        ReferenceSurfaceSnapshot surfaceSnapshot = RuntimeTestFixtures.createReferenceSurfaceSnapshot(
                referencedFrameHeader,
                12,
                3072
        );

        DecodedFrame frame = OutputFrameFactory.createExistingFrame(surfaceSnapshot, 12L);

        assertTrue(frame.bitDepth().isHighBitDepth());
        assertEquals(1, frame.longPixels().length);
        assertTrue(frame.longPixelBuffer().isReadOnly());
        assertFrameMetadata(frame, 12, PixelFormat.I400, FrameType.SWITCH, true, 12L);
    }

    /// Asserts public frame metadata on one runtime-created decoded frame.
    ///
    /// @param frame the runtime-created decoded frame
    /// @param bitDepth the expected decoded bit depth
    /// @param pixelFormat the expected public chroma layout
    /// @param frameType the expected AV1 frame type
    /// @param visible the expected visibility flag
    /// @param presentationIndex the expected zero-based presentation index
    private static void assertFrameMetadata(
            DecodedFrame frame,
            int bitDepth,
            PixelFormat pixelFormat,
            FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        assertEquals(1, frame.width());
        assertEquals(1, frame.height());
        assertEquals(AvifBitDepth.fromBits(bitDepth), frame.bitDepth());
        assertEquals(pixelFormat, frame.pixelFormat());
        assertEquals(frameType, frame.frameType());
        assertEquals(visible, frame.visible());
        assertEquals(presentationIndex, frame.presentationIndex());
    }
}
