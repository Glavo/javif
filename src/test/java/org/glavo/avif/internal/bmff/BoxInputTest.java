// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff;

import org.glavo.avif.AvifDecodeException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests signed big-endian reads used by variable-width BMFF payloads.
@NotNullByDefault
final class BoxInputTest {
    /// Verifies signed reads preserve the two's-complement value at every supported width.
    @Test
    void readsSignedIntegersAtEveryWidth() throws AvifDecodeException {
        BoxInput input = new BoxInput(new byte[]{
                (byte) 0x80,
                (byte) 0x80, 0x00,
                (byte) 0x80, 0x00, 0x00, 0x00,
                (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        });

        assertEquals(Byte.MIN_VALUE, input.readI8());
        assertEquals(Short.MIN_VALUE, input.readI16());
        assertEquals(Integer.MIN_VALUE, input.readI32());
        assertEquals(Long.MIN_VALUE, input.readI64());
        assertEquals(0, input.remaining());
    }
}
