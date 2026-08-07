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
