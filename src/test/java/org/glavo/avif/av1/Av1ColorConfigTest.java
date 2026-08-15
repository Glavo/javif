// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1;

import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.AvifBitDepth;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for validated AV1 color configuration values.
@NotNullByDefault
final class Av1ColorConfigTest {
    /// Verifies that numeric AV1 bit depth is normalized to the shared enum.
    @Test
    void numericBitDepthIsNormalized() {
        Av1ColorConfig config = monochromeConfig(10);
        assertEquals(AvifBitDepth.TEN_BITS, config.bitDepth());
    }

    /// Verifies that inconsistent chroma and subsampling state is rejected.
    @Test
    void constructorRejectsInconsistentChromaState() {
        assertThrows(IllegalArgumentException.class, () -> new Av1ColorConfig(
                8, false, false, 2, 2, 2, true,
                Av1ChromaFormat.MONOCHROME, 0, true, true, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new Av1ColorConfig(
                8, false, false, 2, 2, 2, true,
                Av1ChromaFormat.YUV420, 0, false, true, false
        ));
    }

    /// Creates one valid monochrome configuration.
    ///
    /// @param bitDepth the numeric AV1 bit depth
    /// @return the color configuration
    private static Av1ColorConfig monochromeConfig(int bitDepth) {
        return new Av1ColorConfig(
                bitDepth, true, false, 2, 2, 2, true,
                Av1ChromaFormat.MONOCHROME, 0, true, true, false
        );
    }
}
