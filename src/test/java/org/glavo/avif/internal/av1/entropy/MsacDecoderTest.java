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
package org.glavo.avif.internal.av1.entropy;

import org.glavo.avif.internal.av1.model.TileBitstream;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `MsacDecoder`.
@NotNullByDefault
final class MsacDecoderTest {
    /// Verifies a reference entropy-decoding vector that exercises false branches and symbol `3`.
    @Test
    void decodesReferenceVectorWithFalseBranches() {
        MsacDecoder decoder = new MsacDecoder(new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A}, 0, 5, false);
        int[] boolCdf = new int[]{20000, 5};
        int[] symbolCdf = new int[]{6000, 18000, 26000, 7};
        int[] hiTokenCdf = new int[]{4000, 12000, 24000, 10};

        assertFalse(decoder.decodeBooleanEqui());
        assertFalse(decoder.decodeBooleanEqui());
        assertFalse(decoder.decodeBoolean(20000));
        assertEquals(23, decoder.decodeBools(5));
        assertEquals(0, decoder.decodeUniform(5));
        assertFalse(decoder.decodeBooleanAdapt(boolCdf));
        assertArrayEquals(new int[]{18750, 6}, boolCdf);
        assertEquals(3, decoder.decodeSymbolAdapt(symbolCdf, 3));
        assertArrayEquals(new int[]{6836, 18461, 26211, 8}, symbolCdf);

        MsacDecoder highTokenDecoder = new MsacDecoder(new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A}, 0, 5, false);
        assertEquals(3, highTokenDecoder.decodeHighToken(hiTokenCdf));
        assertArrayEquals(new int[]{3875, 11625, 23250, 11}, hiTokenCdf);

        MsacDecoder subexpDecoder = new MsacDecoder(new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A}, 0, 5, false);
        assertEquals(4, subexpDecoder.decodeSubexp(5, 40, 3));
    }

    /// Verifies a reference entropy-decoding vector that exercises true branches and higher subexp values.
    @Test
    void decodesReferenceVectorWithTrueBranches() {
        MsacDecoder decoder = new MsacDecoder(new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18}, 0, 6, false);
        int[] boolCdf = new int[]{20000, 5};
        int[] symbolCdf = new int[]{6000, 18000, 26000, 7};
        int[] hiTokenCdf = new int[]{4000, 12000, 24000, 10};

        assertTrue(decoder.decodeBooleanEqui());
        assertTrue(decoder.decodeBooleanEqui());
        assertTrue(decoder.decodeBoolean(20000));
        assertEquals(6, decoder.decodeBools(5));
        assertEquals(2, decoder.decodeUniform(5));
        assertFalse(decoder.decodeBooleanAdapt(boolCdf));
        assertArrayEquals(new int[]{18750, 6}, boolCdf);
        assertEquals(0, decoder.decodeSymbolAdapt(symbolCdf, 3));
        assertArrayEquals(new int[]{5813, 17438, 25188, 8}, symbolCdf);

        MsacDecoder highTokenDecoder = new MsacDecoder(new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18}, 0, 6, false);
        assertEquals(6, highTokenDecoder.decodeHighToken(hiTokenCdf));
        assertArrayEquals(new int[]{4746, 12254, 23516, 12}, hiTokenCdf);

        MsacDecoder subexpDecoder = new MsacDecoder(new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18}, 0, 6, false);
        assertEquals(33, subexpDecoder.decodeSubexp(5, 40, 3));
    }

    /// Verifies that `TileBitstream` opens a slice-bounded arithmetic decoder without copying unrelated bytes.
    @Test
    void tileBitstreamOpensSliceBoundedDecoder() {
        byte[] payload = new byte[]{0x7A, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, 0x55};
        TileBitstream bitstream = new TileBitstream(0, payload, 1, 5);
        MsacDecoder decoder = bitstream.openMsacDecoder(false);

        assertFalse(decoder.decodeBooleanEqui());
        assertFalse(decoder.decodeBooleanEqui());
        assertFalse(decoder.decodeBoolean(20000));
    }

    /// Verifies that disabling CDF updates leaves the input arrays unchanged.
    @Test
    void disabledCdfUpdatesLeaveArraysUntouched() {
        MsacDecoder decoder = new MsacDecoder(new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A}, 0, 5, true);
        int[] boolCdf = new int[]{20000, 5};
        int[] symbolCdf = new int[]{6000, 18000, 26000, 7};

        assertFalse(decoder.decodeBooleanAdapt(boolCdf));
        assertArrayEquals(new int[]{20000, 5}, boolCdf);
        assertEquals(0, decoder.decodeSymbolAdapt(symbolCdf, 3));
        assertArrayEquals(new int[]{6000, 18000, 26000, 7}, symbolCdf);
    }
}
