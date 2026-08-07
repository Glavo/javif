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

import org.glavo.avif.av1.Av1ColorConfig;
import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.av1.Av1DecodedFrame;
import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for runtime frame-output factory dispatch.
@NotNullByDefault
final class OutputFrameFactoryTest {
    /// Verifies that 8-bit decoded planes produce one public `Av1DecodedFrame`.
    @Test
    void createFrameReturnsDecodedFrameForEightBitPlanes() {
        DecodedSurface decodedPlanes = RuntimeTestFixtures.createDecodedPlanes(8, 73);
        FrameHeader frameHeader = RuntimeTestFixtures.createFrameHeader(3, 2, Av1FrameType.KEY, true, 0x01);

        Av1DecodedFrame frame = OutputFrameFactory.createFrame(decodedPlanes, frameHeader, false, 5L);

        assertEquals(AvifBitDepth.EIGHT_BITS, frame.bitDepth());
        assertEquals(1, frame.intPixels().length);
        assertTrue(frame.intPixelBuffer().isReadOnly());
        assertEquals(3, frame.temporalId());
        assertEquals(2, frame.spatialId());
        assertFrameMetadata(frame, 8, Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, false, 5L);
    }

    /// Verifies that 10-bit and 12-bit decoded planes both produce one public `Av1DecodedFrame`.
    @Test
    void createFrameReturnsDecodedFrameForHighBitDepthPlanes() {
        FrameHeader intraFrameHeader = RuntimeTestFixtures.createFrameHeader(Av1FrameType.INTRA, true, 0x00);

        Av1DecodedFrame tenBitFrame = OutputFrameFactory.createFrame(
                RuntimeTestFixtures.createDecodedPlanes(10, 512),
                intraFrameHeader,
                true,
                8L
        );
        Av1DecodedFrame twelveBitFrame = OutputFrameFactory.createFrame(
                RuntimeTestFixtures.createDecodedPlanes(12, 2048),
                intraFrameHeader,
                false,
                9L
        );

        assertTrue(tenBitFrame.bitDepth().isHighBitDepth());
        assertEquals(1, tenBitFrame.longPixels().length);
        assertTrue(tenBitFrame.longPixelBuffer().isReadOnly());
        assertFrameMetadata(tenBitFrame, 10, Av1ChromaFormat.MONOCHROME, Av1FrameType.INTRA, true, 8L);

        assertTrue(twelveBitFrame.bitDepth().isHighBitDepth());
        assertEquals(1, twelveBitFrame.longPixels().length);
        assertTrue(twelveBitFrame.longPixelBuffer().isReadOnly());
        assertFrameMetadata(twelveBitFrame, 12, Av1ChromaFormat.MONOCHROME, Av1FrameType.INTRA, false, 9L);
    }

    /// Verifies that sequence color range metadata is used when creating public frames.
    @Test
    void createFrameUsesSequenceColorConfigForOutputTransform() {
        DecodedSurface decodedPlanes = RuntimeTestFixtures.createDecodedPlanes(8, 16);
        Av1ColorConfig colorConfig = new Av1ColorConfig(
                8,
                true,
                true,
                1,
                13,
                6,
                false,
                Av1ChromaFormat.MONOCHROME,
                0,
                true,
                true,
                false
        );
        FrameHeader frameHeader = RuntimeTestFixtures.createFrameHeader(Av1FrameType.KEY, true, 0x00);

        Av1DecodedFrame frame = OutputFrameFactory.createFrame(decodedPlanes, colorConfig, frameHeader, true, 0L);

        assertEquals(0xFF00_0000, frame.intPixels()[0]);
    }

    /// Verifies that stored reference surfaces are exposed through the existing-frame path as visible output.
    @Test
    void createExistingFrameReturnsVisibleFrameBackedByStoredSurfaceBitDepth() {
        FrameHeader referencedFrameHeader = RuntimeTestFixtures.createFrameHeader(Av1FrameType.SWITCH, false, 0x20);
        ReferenceSurfaceSnapshot surfaceSnapshot = RuntimeTestFixtures.createReferenceSurfaceSnapshot(
                referencedFrameHeader,
                12,
                3072
        );
        FrameHeader outputRequestHeader = RuntimeTestFixtures.createFrameHeader(
                5,
                3,
                Av1FrameType.INTER,
                true,
                0
        );

        Av1DecodedFrame frame = OutputFrameFactory.createExistingFrame(
                surfaceSnapshot.decodedPlanes(),
                surfaceSnapshot,
                outputRequestHeader,
                12L
        );

        assertTrue(frame.bitDepth().isHighBitDepth());
        assertEquals(1, frame.longPixels().length);
        assertTrue(frame.longPixelBuffer().isReadOnly());
        assertEquals(5, frame.temporalId());
        assertEquals(3, frame.spatialId());
        assertFrameMetadata(frame, 12, Av1ChromaFormat.MONOCHROME, Av1FrameType.SWITCH, true, 12L);
    }

    /// Asserts public frame metadata on one runtime-created decoded frame.
    ///
    /// @param frame the runtime-created decoded frame
    /// @param bitDepth the expected decoded bit depth
    /// @param chromaFormat the expected public chroma layout
    /// @param frameType the expected AV1 frame type
    /// @param visible the expected visibility flag
    /// @param presentationIndex the expected zero-based presentation index
    private static void assertFrameMetadata(
            Av1DecodedFrame frame,
            int bitDepth,
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        assertEquals(1, frame.width());
        assertEquals(1, frame.height());
        assertEquals(AvifBitDepth.fromBits(bitDepth), frame.bitDepth());
        assertEquals(chromaFormat, frame.chromaFormat());
        assertEquals(frameType, frame.frameType());
        assertEquals(visible, frame.visible());
        assertEquals(presentationIndex, frame.presentationIndex());
    }
}
