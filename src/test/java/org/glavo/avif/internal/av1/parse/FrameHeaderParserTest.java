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
package org.glavo.avif.internal.av1.parse;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrameHeaderParser`.
@NotNullByDefault
final class FrameHeaderParserTest {
    /// Verifies that a reduced still-picture key frame header can be parsed.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesReducedStillPictureKeyFrameHeader() throws IOException {
        SequenceHeader sequenceHeader = new SequenceHeaderParser().parse(sequenceHeaderObu(reducedStillPictureSequencePayload()), false);
        FrameHeader header = new FrameHeaderParser().parse(frameHeaderObu(reducedStillPictureFrameHeaderPayload()), sequenceHeader, false);

        assertFalse(header.showExistingFrame());
        assertEquals(FrameType.KEY, header.frameType());
        assertTrue(header.showFrame());
        assertFalse(header.showableFrame());
        assertTrue(header.errorResilientMode());
        assertTrue(header.disableCdfUpdate());
        assertFalse(header.allowScreenContentTools());
        assertTrue(header.forceIntegerMotionVectors());
        assertFalse(header.frameSizeOverride());
        assertEquals(7, header.primaryRefFrame());
        assertEquals(0xFF, header.refreshFrameFlags());
        assertEquals(640, header.frameSize().codedWidth());
        assertEquals(640, header.frameSize().upscaledWidth());
        assertEquals(360, header.frameSize().height());
        assertEquals(640, header.frameSize().renderWidth());
        assertEquals(360, header.frameSize().renderHeight());
        assertFalse(header.superResolution().enabled());
        assertEquals(8, header.superResolution().widthScaleDenominator());
        assertFalse(header.allowIntrabc());
        assertTrue(header.refreshContext());

        assertTrue(header.tiling().uniform());
        assertEquals(1, header.tiling().columns());
        assertEquals(1, header.tiling().rows());
        assertEquals(0, header.tiling().log2Cols());
        assertEquals(0, header.tiling().log2Rows());
        assertEquals(10, header.tiling().columnStartSuperblocks()[1]);
        assertEquals(6, header.tiling().rowStartSuperblocks()[1]);

        assertEquals(0, header.quantization().baseQIndex());
        assertFalse(header.segmentation().enabled());
        assertFalse(header.delta().deltaQPresent());
        assertTrue(header.allLossless());
        assertTrue(header.loopFilter().modeRefDeltaEnabled());
        assertEquals(FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY, header.transformMode());
        assertFalse(header.reducedTransformSet());
        assertFalse(header.filmGrainPresent());
    }

    /// Wraps a payload in a synthetic sequence header OBU packet.
    ///
    /// @param payload the raw sequence header payload
    /// @return the synthetic sequence header OBU packet
    private static ObuPacket sequenceHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0), payload, 0, 0);
    }

    /// Wraps a payload in a synthetic frame header OBU packet.
    ///
    /// @param payload the raw frame header payload
    /// @return the synthetic frame header OBU packet
    private static ObuPacket frameHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME_HEADER, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPictureSequencePayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(639, 10);
        writer.writeBits(359, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture key frame header payload.
    ///
    /// @return the reduced still-picture key frame header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Small MSB-first bit writer used to build AV1 test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Writes trailing bits and byte alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
