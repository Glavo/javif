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

import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `SequenceHeaderParser`.
@NotNullByDefault
final class SequenceHeaderParserTest {
    /// Verifies that a reduced still-picture sequence header can be parsed.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesReducedStillPictureHeader() throws IOException {
        SequenceHeader header = new SequenceHeaderParser().parse(sequenceHeaderObu(reducedStillPicturePayload()), false);

        assertEquals(0, header.profile());
        assertTrue(header.stillPicture());
        assertTrue(header.reducedStillPictureHeader());
        assertEquals(640, header.maxWidth());
        assertEquals(360, header.maxHeight());
        assertEquals(10, header.widthBits());
        assertEquals(9, header.heightBits());
        assertEquals(1, header.operatingPointCount());
        assertEquals(5, header.operatingPoint(0).majorLevel());
        assertEquals(1, header.operatingPoint(0).minorLevel());
        assertEquals(10, header.operatingPoint(0).initialDisplayDelay());

        SequenceHeader.FeatureConfig features = header.features();
        assertFalse(features.use128x128Superblocks());
        assertTrue(features.filterIntra());
        assertTrue(features.intraEdgeFilter());
        assertEquals(SequenceHeader.AdaptiveBoolean.ADAPTIVE, features.screenContentTools());
        assertEquals(SequenceHeader.AdaptiveBoolean.ADAPTIVE, features.forceIntegerMotionVectors());
        assertFalse(features.superResolution());
        assertTrue(features.cdef());
        assertTrue(features.restoration());
        assertFalse(features.filmGrainPresent());

        SequenceHeader.ColorConfig color = header.colorConfig();
        assertEquals(8, color.bitDepth());
        assertFalse(color.monochrome());
        assertEquals(AvifPixelFormat.I420, color.pixelFormat());
        assertTrue(color.colorRange());
        assertEquals(1, color.chromaSamplePosition());
        assertTrue(color.chromaSubsamplingX());
        assertTrue(color.chromaSubsamplingY());
        assertTrue(color.separateUvDeltaQ());
    }

    /// Verifies that a full sequence header with timing information and operating points can be parsed.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesFullSequenceHeader() throws IOException {
        SequenceHeader header = new SequenceHeaderParser().parse(sequenceHeaderObu(fullSequenceHeaderPayload()), true);

        assertEquals(2, header.profile());
        assertFalse(header.stillPicture());
        assertFalse(header.reducedStillPictureHeader());
        assertEquals(2048, header.maxWidth());
        assertEquals(1024, header.maxHeight());
        assertTrue(header.frameIdNumbersPresent());
        assertEquals(4, header.deltaFrameIdBits());
        assertEquals(6, header.frameIdBits());

        SequenceHeader.TimingInfo timing = header.timingInfo();
        assertTrue(timing.present());
        assertEquals(1000, timing.numUnitsInTick());
        assertEquals(60_000, timing.timeScale());
        assertFalse(timing.equalPictureInterval());
        assertTrue(timing.decoderModelInfoPresent());
        assertEquals(5, timing.encoderDecoderBufferDelayLength());
        assertEquals(100, timing.numUnitsInDecodingTick());
        assertEquals(4, timing.bufferRemovalDelayLength());
        assertEquals(3, timing.framePresentationDelayLength());
        assertTrue(timing.displayModelInfoPresent());

        assertEquals(2, header.operatingPointCount());
        SequenceHeader.OperatingPoint op0 = header.operatingPoint(0);
        assertEquals(5, op0.majorLevel());
        assertEquals(1, op0.minorLevel());
        assertEquals(0x101, op0.idc());
        assertTrue(op0.tier());
        assertTrue(op0.decoderModelParamPresent());
        assertTrue(op0.displayModelParamPresent());
        assertEquals(3, op0.initialDisplayDelay());
        assertEquals(17, op0.operatingParameterInfo().decoderBufferDelay());
        assertEquals(3, op0.operatingParameterInfo().encoderBufferDelay());
        assertTrue(op0.operatingParameterInfo().lowDelayMode());

        SequenceHeader.OperatingPoint op1 = header.operatingPoint(1);
        assertEquals(3, op1.majorLevel());
        assertEquals(0, op1.idc());
        assertFalse(op1.tier());
        assertFalse(op1.decoderModelParamPresent());
        assertFalse(op1.displayModelParamPresent());
        assertEquals(10, op1.initialDisplayDelay());

        SequenceHeader.FeatureConfig features = header.features();
        assertTrue(features.use128x128Superblocks());
        assertTrue(features.filterIntra());
        assertFalse(features.intraEdgeFilter());
        assertTrue(features.interIntra());
        assertTrue(features.maskedCompound());
        assertFalse(features.warpedMotion());
        assertTrue(features.dualFilter());
        assertTrue(features.orderHint());
        assertTrue(features.jointCompound());
        assertTrue(features.refFrameMotionVectors());
        assertEquals(SequenceHeader.AdaptiveBoolean.ON, features.screenContentTools());
        assertEquals(SequenceHeader.AdaptiveBoolean.ADAPTIVE, features.forceIntegerMotionVectors());
        assertEquals(5, features.orderHintBits());
        assertTrue(features.superResolution());
        assertTrue(features.cdef());
        assertFalse(features.restoration());
        assertTrue(features.filmGrainPresent());

        SequenceHeader.ColorConfig color = header.colorConfig();
        assertEquals(12, color.bitDepth());
        assertFalse(color.monochrome());
        assertTrue(color.colorDescriptionPresent());
        assertEquals(1, color.colorPrimaries());
        assertEquals(13, color.transferCharacteristics());
        assertEquals(0, color.matrixCoefficients());
        assertEquals(AvifPixelFormat.I444, color.pixelFormat());
        assertTrue(color.colorRange());
        assertFalse(color.chromaSubsamplingX());
        assertFalse(color.chromaSubsamplingY());
        assertFalse(color.separateUvDeltaQ());
    }

    /// Verifies that strict mode rejects an identity matrix with subsampled chroma.
    @Test
    void strictModeRejectsIdentityMatrixWithSubsampledChroma() {
        assertThrows(DecodeException.class, () ->
                new SequenceHeaderParser().parse(sequenceHeaderObu(invalidStrictIdentityPayload()), true)
        );
    }

    /// Wraps a payload in a synthetic sequence header OBU packet.
    ///
    /// @param payload the raw sequence header payload
    /// @return the synthetic sequence header OBU packet
    private static ObuPacket sequenceHeaderObu(byte[] payload) {
        return new ObuPacket(
                new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0),
                payload,
                0,
                0
        );
    }

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload() {
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

    /// Creates a full sequence header payload that exercises timing info, operating points, and RGB identity signaling.
    ///
    /// @return the full sequence header payload
    private static byte[] fullSequenceHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(2, 3);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1000, 32);
        writer.writeBits(60_000, 32);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(4, 5);
        writer.writeBits(100, 32);
        writer.writeBits(3, 5);
        writer.writeBits(2, 5);
        writer.writeFlag(true);
        writer.writeBits(1, 5);

        writer.writeBits(0x101, 12);
        writer.writeBits(3, 3);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(17, 5);
        writer.writeBits(3, 5);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(2, 4);

        writer.writeBits(0, 12);
        writer.writeBits(1, 3);
        writer.writeBits(0, 2);
        writer.writeFlag(false);
        writer.writeFlag(false);

        writer.writeBits(11, 4);
        writer.writeBits(10, 4);
        writer.writeBits(2047, 12);
        writer.writeBits(1023, 11);
        writer.writeFlag(true);
        writer.writeBits(2, 4);
        writer.writeBits(1, 3);

        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(4, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);

        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 8);
        writer.writeBits(13, 8);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a sequence header payload that should fail strict identity-matrix validation.
    ///
    /// @return the invalid sequence header payload
    private static byte[] invalidStrictIdentityPayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(4, 3);
        writer.writeBits(0, 2);
        writer.writeBits(7, 4);
        writer.writeBits(7, 4);
        writer.writeBits(127, 8);
        writer.writeBits(127, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 8);
        writer.writeBits(1, 8);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeBits(2, 2);
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
