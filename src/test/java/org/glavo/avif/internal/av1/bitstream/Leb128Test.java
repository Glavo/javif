// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream;

import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for `Leb128`.
@NotNullByDefault
final class Leb128Test {
    /// Verifies that a single-byte value can be decoded.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsSingleByteValue() throws IOException {
        try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(new byte[]{0x2A}).order(ByteOrder.LITTLE_ENDIAN))) {
            Leb128.ReadResult value = Leb128.readUnsigned(input, 8);
            assertEquals(42, value.value());
            assertEquals(1, value.byteCount());
        }
    }

    /// Verifies that a multi-byte value can be decoded.
    ///
    /// @throws IOException if the test input cannot be read
    @Test
    void readsMultiByteValue() throws IOException {
        try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(new byte[]{(byte) 0xE5, (byte) 0x8E, 0x26}).order(ByteOrder.LITTLE_ENDIAN))) {
            Leb128.ReadResult value = Leb128.readUnsigned(input, 8);
            assertEquals(624485, value.value());
            assertEquals(3, value.byteCount());
        }
    }

    /// Verifies that overlong values are rejected.
    @Test
    void rejectsOverlongValue() {
        byte[] bytes = new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };

        assertThrows(EOFException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))) {
                Leb128.readUnsigned(input, 8);
            }
        });
    }

    /// Verifies that values outside the supported 32-bit unsigned range are rejected.
    @Test
    void rejectsValueOutsideSupportedRange() {
        byte[] bytes = new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10};

        assertThrows(IOException.class, () -> {
            try (BufferedInput input = new BufferedInput.OfByteBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))) {
                Leb128.readUnsigned(input, 8);
            }
        });
    }
}
