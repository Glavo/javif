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
package org.glavo.avif.internal.av1.bitstream;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `BitReader`.
@NotNullByDefault
final class BitReaderTest {
    /// Verifies that bits can be read across byte boundaries.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsBitsAcrossByteBoundaries() throws IOException {
        BitReader reader = new BitReader(new byte[]{(byte) 0b1011_0010, (byte) 0b0110_1100});

        assertEquals(0b101, reader.readBits(3));
        assertEquals(0b10010, reader.readBits(5));
        assertEquals(0b0110, reader.readBits(4));
        assertEquals(0b1100, reader.readBits(4));
        assertEquals(0, reader.bitsRemaining());
    }

    /// Verifies that a sliced reader only sees the selected byte range.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsBitsFromByteSlice() throws IOException {
        BitReader reader = new BitReader(new byte[]{0x55, (byte) 0xD2, 0x33}, 1, 1);

        assertEquals(0b110, reader.readBits(3));
        assertEquals(0b10010, reader.readBits(5));
        assertEquals(0, reader.bitsRemaining());
    }

    /// Verifies that byte alignment skips to the next whole-byte boundary.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void byteAlignSkipsToNextBoundary() throws IOException {
        BitReader reader = new BitReader(new byte[]{(byte) 0xFF, 0x55});

        assertEquals(0b111, reader.readBits(3));
        assertEquals(3, reader.bitOffset());
        assertFalse(reader.isByteAligned());
        reader.byteAlign();
        assertTrue(reader.isByteAligned());
        assertEquals(1, reader.byteOffset());
        assertEquals(0x55, reader.readBits(8));
    }

    /// Verifies that UVLC values can be decoded.
    ///
    /// @throws IOException if the test payload cannot be read
    @Test
    void readsUvlcValue() throws IOException {
        BitReader reader = new BitReader(new byte[]{0x30});
        assertEquals(5, reader.readUvlc());
    }

    /// Verifies that truncated payloads report EOF.
    @Test
    void reportsUnexpectedEof() {
        BitReader reader = new BitReader(new byte[]{0x00});
        assertThrows(EOFException.class, () -> reader.readBits(9));
    }
}
