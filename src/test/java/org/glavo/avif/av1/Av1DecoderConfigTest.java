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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `Av1DecoderConfig`.
@NotNullByDefault
final class Av1DecoderConfigTest {
    /// Verifies the default settings.
    @Test
    void defaultUsesExpectedSettings() {
        Av1DecoderConfig config = Av1DecoderConfig.DEFAULT;

        assertTrue(config.applyFilmGrain());
        assertFalse(config.strictStdCompliance());
        assertFalse(config.outputInvisibleFrames());
        assertFalse(config.outputAllLayers());
        assertFalse(config.largeScaleTileMode());
        assertEquals(Av1FrameSelection.ALL, config.frameSelection());
        assertEquals(0, config.operatingPoint());
        assertEquals(8192L * 8192L, config.frameSizeLimit());
    }

    /// Verifies that invalid operating points are rejected.
    @Test
    void withOperatingPointRejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.DEFAULT.withOperatingPoint(-1));
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.DEFAULT.withOperatingPoint(32));
    }

    /// Verifies that changing only the operating point retains every other decoder option.
    @Test
    void withOperatingPointRetainsOtherSettings() {
        Av1DecoderConfig config = Av1DecoderConfig.DEFAULT
                .withApplyFilmGrain(false)
                .withStrictStdCompliance(true)
                .withOutputInvisibleFrames(true)
                .withOutputAllLayers(true)
                .withLargeScaleTileMode(true)
                .withFrameSelection(Av1FrameSelection.REFERENCE)
                .withOperatingPoint(2)
                .withFrameSizeLimit(1234);

        assertSame(config, config.withOperatingPoint(2));
        Av1DecoderConfig changed = config.withOperatingPoint(7);
        assertFalse(changed.applyFilmGrain());
        assertTrue(changed.strictStdCompliance());
        assertTrue(changed.outputInvisibleFrames());
        assertTrue(changed.outputAllLayers());
        assertTrue(changed.largeScaleTileMode());
        assertEquals(Av1FrameSelection.REFERENCE, changed.frameSelection());
        assertEquals(7, changed.operatingPoint());
        assertEquals(1234, changed.frameSizeLimit());
        assertThrows(IllegalArgumentException.class, () -> config.withOperatingPoint(32));
    }

    /// Verifies that negative frame size limits are rejected.
    @Test
    void withFrameSizeLimitRejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> Av1DecoderConfig.DEFAULT.withFrameSizeLimit(-1));
    }
}
