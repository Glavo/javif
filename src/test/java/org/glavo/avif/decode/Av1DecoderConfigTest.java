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
package org.glavo.avif.decode;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `Av1DecoderConfig`.
@NotNullByDefault
final class Av1DecoderConfigTest {
    /// Verifies the default builder settings.
    @Test
    void builderUsesExpectedDefaults() {
        Av1DecoderConfig config = Av1DecoderConfig.builder().build();

        assertTrue(config.applyFilmGrain());
        assertFalse(config.strictStdCompliance());
        assertFalse(config.outputInvisibleFrames());
        assertEquals(DecodeFrameType.ALL, config.decodeFrameType());
        assertEquals(0, config.operatingPoint());
        assertTrue(config.allLayers());
        assertEquals(0, config.frameSizeLimit());
        assertEquals(1, config.threadCount());
    }

    /// Verifies that invalid operating points are rejected.
    @Test
    void builderRejectsInvalidOperatingPoint() {
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.builder().operatingPoint(-1).build());
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.builder().operatingPoint(32).build());
    }

    /// Verifies that negative frame size limits are rejected.
    @Test
    void builderRejectsNegativeFrameSizeLimit() {
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.builder().frameSizeLimit(-1).build());
    }

    /// Verifies that non-positive thread counts are rejected.
    @Test
    void builderRejectsNonPositiveThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.builder().threadCount(0).build());
    }
}
