// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /// Verifies the SMPTE 240M transfer function at its exact H.273 join point.
    @Test
    void smpte240TransferIsContinuousAtJoinPoint() {
        AvifColorInfo smpte240 = new AvifColorInfo(7, 7, 7, true);
        double linearThreshold = 0.022821585529445;
        double encodedThreshold = 4.0 * linearThreshold;

        assertEquals(
                linearThreshold,
                AvifCicpColorTransforms.gammaToLinear(encodedThreshold, smpte240),
                1.0e-14
        );
        assertEquals(
                encodedThreshold,
                AvifCicpColorTransforms.linearToGamma(linearThreshold, smpte240),
                1.0e-14
        );
    }

    /// Verifies both H.273 logarithmic transfer functions at exact decimal stops.
    @Test
    void logarithmicTransfersMatchReferenceStops() {
        AvifColorInfo log100 = new AvifColorInfo(1, 9, 1, true);
        AvifColorInfo log316 = new AvifColorInfo(1, 10, 1, true);

        assertEquals(0.1, AvifCicpColorTransforms.gammaToLinear(0.5, log100), 1.0e-15);
        assertEquals(0.5, AvifCicpColorTransforms.linearToGamma(0.1, log100), 1.0e-15);
        assertEquals(0.1, AvifCicpColorTransforms.gammaToLinear(0.6, log316), 1.0e-15);
        assertEquals(0.6, AvifCicpColorTransforms.linearToGamma(0.1, log316), 1.0e-15);
        assertEquals(0.0, AvifCicpColorTransforms.gammaToLinear(0.0, log100), 0.0);
        assertEquals(0.0, AvifCicpColorTransforms.linearToGamma(0.005, log100), 0.0);
    }

    /// Verifies extended-gamut transfer codes use their BT.709 positive-domain branch.
    @Test
    void extendedGamutTransfersMatchBt709ForNormalizedChannels() {
        AvifColorInfo bt709 = new AvifColorInfo(1, 1, 1, true);
        double expected = AvifCicpColorTransforms.gammaToLinear(0.5, bt709);

        assertEquals(
                expected,
                AvifCicpColorTransforms.gammaToLinear(0.5, new AvifColorInfo(1, 11, 1, true)),
                0.0
        );
        assertEquals(
                expected,
                AvifCicpColorTransforms.gammaToLinear(0.5, new AvifColorInfo(1, 12, 1, true)),
                0.0
        );
    }

    /// Verifies the SMPTE ST 428 normalization specified by H.273.
    @Test
    void smpte428TransferUsesReferenceOutputScale() {
        AvifColorInfo smpte428 = new AvifColorInfo(10, 17, 0, true);
        double encodedReferenceWhite = Math.pow(48.0 / 52.37, 1.0 / 2.6);

        assertEquals(
                encodedReferenceWhite,
                AvifCicpColorTransforms.linearToGamma(1.0, smpte428),
                1.0e-15
        );
        assertEquals(
                1.0,
                AvifCicpColorTransforms.gammaToLinear(encodedReferenceWhite, smpte428),
                1.0e-15
        );
    }

    /// Verifies every defined non-unspecified transfer code accepts normalized channels.
    @Test
    void everyDefinedTransferCharacteristicIsSupported() {
        int[] transferCharacteristics = { 1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18 };

        for (int transferCharacteristic : transferCharacteristics) {
            AvifColorInfo colorInfo = new AvifColorInfo(1, transferCharacteristic, 1, true);
            double linear = AvifCicpColorTransforms.gammaToLinear(0.5, colorInfo);
            assertTrue(Double.isFinite(linear), "transfer characteristic " + transferCharacteristic);
            assertTrue(
                    Double.isFinite(AvifCicpColorTransforms.linearToGamma(0.5, colorInfo)),
                    "transfer characteristic " + transferCharacteristic
            );
        }
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

    /// Verifies SMPTE ST 428 primary code 10 is interpreted as direct CIE XYZ coordinates.
    @Test
    void xyzPrimariesConvertDirectlyToAndFromSrgb() {
        AvifColorInfo xyz = new AvifColorInfo(10, 17, 0, true);
        AvifColorInfo srgb = new AvifColorInfo(1, 13, 0, true);
        double whiteX = 0.3127 / 0.3290;
        double whiteZ = (1.0 - 0.3127 - 0.3290) / 0.3290;

        double[] xyzWhite = { whiteX, 1.0, whiteZ };
        AvifCicpColorTransforms.conversionMatrix(xyz, srgb).apply(xyzWhite);
        assertEquals(1.0, xyzWhite[0], 1.0e-12);
        assertEquals(1.0, xyzWhite[1], 1.0e-12);
        assertEquals(1.0, xyzWhite[2], 1.0e-12);

        double[] rgbWhite = { 1.0, 1.0, 1.0 };
        AvifCicpColorTransforms.conversionMatrix(srgb, xyz).apply(rgbWhite);
        assertEquals(whiteX, rgbWhite[0], 1.0e-12);
        assertEquals(1.0, rgbWhite[1], 1.0e-12);
        assertEquals(whiteZ, rgbWhite[2], 1.0e-12);
    }

    /// Verifies that missing and explicitly unspecified signaling share BT.709/sRGB defaults.
    @Test
    void unspecifiedColorInfoUsesDefaultRgbColorSpace() {
        AvifColorInfo unspecified = new AvifColorInfo(2, 2, 1, true);
        double expectedLinear = AvifCicpColorTransforms.gammaToLinear(0.5, null);

        assertEquals(expectedLinear, AvifCicpColorTransforms.gammaToLinear(0.5, unspecified), 0.0);

        AvifCicpColorTransforms.RgbMatrix matrix =
                AvifCicpColorTransforms.conversionMatrix(unspecified, null);
        double[] rgb = { 0.25, 0.5, 0.75 };
        matrix.apply(rgb);
        assertEquals(0.25, rgb[0], 0.0);
        assertEquals(0.5, rgb[1], 0.0);
        assertEquals(0.75, rgb[2], 0.0);
    }

    /// Verifies that unsupported explicit primary codes are rejected even when both sides match.
    @Test
    void unsupportedPrimariesAreRejected() {
        AvifColorInfo unsupported = new AvifColorInfo(99, 13, 1, true);
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> AvifCicpColorTransforms.conversionMatrix(unsupported, unsupported)
        );

        assertEquals("Unsupported CICP color primaries: 99", exception.getMessage());
    }

    /// Verifies that unsupported explicit transfer characteristics are rejected in both directions.
    @Test
    void unsupportedTransferCharacteristicsAreRejected() {
        AvifColorInfo unsupported = new AvifColorInfo(1, 99, 1, true);

        UnsupportedOperationException decodeException = assertThrows(
                UnsupportedOperationException.class,
                () -> AvifCicpColorTransforms.gammaToLinear(0.5, unsupported)
        );
        UnsupportedOperationException encodeException = assertThrows(
                UnsupportedOperationException.class,
                () -> AvifCicpColorTransforms.linearToGamma(0.5, unsupported)
        );

        assertEquals("Unsupported CICP transfer characteristics: 99", decodeException.getMessage());
        assertEquals("Unsupported CICP transfer characteristics: 99", encodeException.getMessage());
    }
}
