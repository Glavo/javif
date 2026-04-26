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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests CICP transfer functions and primary conversion helpers used by AVIF gain maps.
@NotNullByDefault
public final class AvifCicpColorTransformsTest {
    /// Verifies that the sRGB transfer function matches a known midpoint.
    @Test
    void srgbTransferMatchesReferenceMidpoint() {
        AvifColorInfo srgb = new AvifColorInfo(1, 13, 6, true);
        double linear = AvifCicpColorTransforms.gammaToLinear(0.5, srgb);

        assertEquals(0.21404114048223255, linear, 1.0e-15);
        assertEquals(0.5, AvifCicpColorTransforms.linearToGamma(linear, srgb), 1.0e-15);
    }

    /// Verifies that BT.709 transfer signaling does not collapse to the sRGB curve.
    @Test
    void bt709TransferDiffersFromSrgb() {
        AvifColorInfo bt709 = new AvifColorInfo(1, 1, 1, true);
        AvifColorInfo srgb = new AvifColorInfo(1, 13, 1, true);

        assertTrue(AvifCicpColorTransforms.gammaToLinear(0.5, bt709)
                > AvifCicpColorTransforms.gammaToLinear(0.5, srgb));
    }

    /// Verifies that Display P3 red is converted into the BT.709/sRGB primary set.
    @Test
    void displayP3ToSrgbPrimariesExpandsRed() {
        AvifColorInfo displayP3 = new AvifColorInfo(12, 13, 1, true);
        AvifColorInfo srgb = new AvifColorInfo(1, 13, 1, true);
        AvifCicpColorTransforms.RgbMatrix matrix = AvifCicpColorTransforms.conversionMatrix(displayP3, srgb);
        double[] rgb = { 1.0, 0.0, 0.0 };

        matrix.apply(rgb);

        assertTrue(rgb[0] > 1.0);
        assertTrue(rgb[1] < 0.0);
        assertTrue(rgb[2] < 0.0);
    }

    /// Verifies that unsupported primary codes use the identity fallback.
    @Test
    void unsupportedPrimariesUseIdentityFallback() {
        AvifColorInfo unsupported = new AvifColorInfo(99, 13, 1, true);
        AvifColorInfo srgb = new AvifColorInfo(1, 13, 1, true);
        AvifCicpColorTransforms.RgbMatrix matrix = AvifCicpColorTransforms.conversionMatrix(unsupported, srgb);
        double[] rgb = { 0.25, 0.5, 0.75 };

        matrix.apply(rgb);

        assertEquals(0.25, rgb[0], 0.0);
        assertEquals(0.5, rgb[1], 0.0);
        assertEquals(0.75, rgb[2], 0.0);
    }
}
