// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.av1.Av1ColorConfig;
import org.glavo.avif.AvifColorInfo;
import org.glavo.avif.Av1ChromaFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for AV1 color-configuration driven YUV-to-RGB transform selection.
@NotNullByDefault
final class YuvToRgbTransformTest {
    /// Verifies that limited-range luma endpoints map to black and white.
    @Test
    void limitedRangeTransformExpandsNominalLumaRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(6, false, Av1ChromaFormat.YUV420));

        assertEquals(0xFF00_0000, transform.toOpaqueArgb(16, 128, 128));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueArgb(235, 128, 128));
    }

    /// Verifies that unspecified matrix coefficients still preserve range for monochrome streams.
    @Test
    void unspecifiedMonochromeTransformPreservesRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(2, false, Av1ChromaFormat.MONOCHROME));

        assertEquals(0xFF00_0000, transform.toOpaqueGrayArgb(16));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueGrayArgb(235));
    }

    /// Verifies that unspecified chroma matrix coefficients still preserve limited-range signaling.
    @Test
    void unspecifiedChromaTransformPreservesRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(2, false, Av1ChromaFormat.YUV420));

        assertEquals(0xFF00_0000, transform.toOpaqueArgb(16, 128, 128));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueArgb(235, 128, 128));
    }

    /// Verifies that unsupported explicit chroma matrices are not silently rendered as `BT.601`.
    @Test
    void unsupportedExplicitChromaMatrixIsRejected() {
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> YuvToRgbTransform.fromColorConfig(colorConfig(14, true, Av1ChromaFormat.YUV420))
        );

        assertEquals("Unsupported CICP matrix coefficients: 14", exception.getMessage());
    }

    /// Verifies that matrix-family support does not affect range conversion for monochrome planes.
    @Test
    void unsupportedExplicitMatrixIsIgnoredForMonochromePlanes() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(14, false, Av1ChromaFormat.MONOCHROME));

        assertEquals(0xFF00_0000, transform.toOpaqueGrayArgb(16));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueGrayArgb(235));
    }

    /// Verifies that matrix coefficients affect chroma conversion.
    @Test
    void matrixCoefficientsSelectDifferentTransforms() {
        YuvToRgbTransform bt601 = YuvToRgbTransform.fromColorConfig(colorConfig(6, true, Av1ChromaFormat.YUV420));
        YuvToRgbTransform bt709 = YuvToRgbTransform.fromColorConfig(colorConfig(1, true, Av1ChromaFormat.YUV420));

        assertNotEquals(bt601.toOpaqueArgb(100, 90, 200), bt709.toOpaqueArgb(100, 90, 200));
    }

    /// Verifies FCC matrix signaling selects its standardized luma coefficients.
    @Test
    void fccMatrixUsesStandardizedLumaCoefficients() {
        YuvToRgbTransform transform =
                YuvToRgbTransform.fromColorConfig(colorConfig(4, true, Av1ChromaFormat.YUV444));

        assertEquals(YuvToRgbTransform.FCC_FULL_RANGE.redCoefficientV(), transform.redCoefficientV());
        assertEquals(YuvToRgbTransform.FCC_FULL_RANGE.greenCoefficientU(), transform.greenCoefficientU());
        assertEquals(YuvToRgbTransform.FCC_FULL_RANGE.greenCoefficientV(), transform.greenCoefficientV());
        assertEquals(YuvToRgbTransform.FCC_FULL_RANGE.blueCoefficientU(), transform.blueCoefficientU());
    }

    /// Verifies a Display-P3 chromaticity-derived matrix uses coefficients derived from its white point.
    @Test
    void chromaticityDerivedMatrixUsesSignaledDisplayP3Primaries() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(
                colorConfig(12, 12, true, Av1ChromaFormat.YUV444)
        );

        assertEquals(101_060, transform.redCoefficientV());
        assertEquals(-13_832, transform.greenCoefficientU());
        assertEquals(-33_452, transform.greenCoefficientV());
        assertEquals(120_680, transform.blueCoefficientU());
    }

    /// Verifies the equal-depth `YCgCo` inverse reconstructs all three RGB channels.
    @Test
    void ycgcoMatrixReconstructsFullRangeRgb() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(
                colorConfig(8, true, Av1ChromaFormat.YUV444)
        );

        assertEquals(0xFFC8_6428, transform.toOpaqueArgb(110, 118, 208));
        assertEquals(-65_536, transform.redCoefficientU());
        assertEquals(65_536, transform.redCoefficientV());
        assertEquals(-65_536, transform.blueCoefficientU());
        assertEquals(-65_536, transform.blueCoefficientV());
    }

    /// Verifies limited-range `YCgCo` expands reconstructed component endpoints.
    @Test
    void ycgcoLimitedRangeExpandsComponentRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(
                colorConfig(8, false, Av1ChromaFormat.YUV444)
        );

        assertEquals(0xFF00_0000, transform.toOpaqueArgb(16, 128, 128));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueArgb(235, 128, 128));
    }

    /// Verifies high-bit-depth `YCgCo` uses the same reversible component relationships.
    @Test
    void ycgcoMatrixReconstructsHighBitDepthRgb() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(
                colorConfig(8, true, Av1ChromaFormat.YUV444)
        );

        long pixel = transform.toOpaqueArgb64(440, 472, 832, 10);
        assertEquals(51_249, (pixel >>> 32) & 0xFFFFL);
        assertEquals(25_625, (pixel >>> 16) & 0xFFFFL);
        assertEquals(10_250, pixel & 0xFFFFL);
    }

    /// Verifies AV1 identity-matrix RGB signaling maps planes directly to RGB channels.
    @Test
    void identityMatrixMapsPlanesAsRgbSamples() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(0, true, Av1ChromaFormat.YUV444));

        assertEquals(0xFF11_2233, transform.toOpaqueArgb(0x22, 0x33, 0x11));
    }

    /// Verifies that high-bit-depth limited-range grayscale endpoints are expanded to 16-bit output.
    @Test
    void limitedRangeHighBitDepthExpandsNominalLumaRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(6, false, Av1ChromaFormat.YUV420));

        assertEquals(0xFFFF_0000_0000_0000L, transform.toOpaqueArgb64(64, 512, 512, 10));
        assertEquals(0xFFFF_FFFF_FFFF_FFFFL, transform.toOpaqueArgb64(940, 512, 512, 10));
    }

    /// Verifies AVIF container `nclx` matrix and range signaling selects the output transform.
    @Test
    void colorInfoSelectsMatrixAndRangeTransform() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorInfo(
                new AvifColorInfo(1, 13, 1, false),
                false
        );

        assertEquals(0xFF00_0000, transform.toOpaqueArgb(16, 128, 128));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueArgb(235, 128, 128));
        assertNotEquals(
                YuvToRgbTransform.BT601_LIMITED_RANGE.toOpaqueArgb(100, 90, 200),
                transform.toOpaqueArgb(100, 90, 200)
        );
    }

    /// Creates one color configuration for transform-selection tests.
    ///
    /// @param matrixCoefficients the AV1 matrix coefficients code
    /// @param fullRange whether samples are full-range
    /// @param chromaFormat the decoded chroma layout
    /// @return one AV1 color configuration
    private static Av1ColorConfig colorConfig(
            int matrixCoefficients,
            boolean fullRange,
            Av1ChromaFormat chromaFormat
    ) {
        return colorConfig(1, matrixCoefficients, fullRange, chromaFormat);
    }

    /// Creates one color configuration with explicit primary and matrix codes.
    ///
    /// @param colorPrimaries the AV1 color-primary code
    /// @param matrixCoefficients the AV1 matrix coefficients code
    /// @param fullRange whether samples are full-range
    /// @param chromaFormat the decoded chroma layout
    /// @return one AV1 color configuration
    private static Av1ColorConfig colorConfig(
            int colorPrimaries,
            int matrixCoefficients,
            boolean fullRange,
            Av1ChromaFormat chromaFormat
    ) {
        return new Av1ColorConfig(
                8,
                chromaFormat == Av1ChromaFormat.MONOCHROME,
                true,
                colorPrimaries,
                13,
                matrixCoefficients,
                fullRange,
                chromaFormat,
                0,
                chromaFormat == Av1ChromaFormat.MONOCHROME
                        || chromaFormat == Av1ChromaFormat.YUV420
                        || chromaFormat == Av1ChromaFormat.YUV422,
                chromaFormat == Av1ChromaFormat.MONOCHROME || chromaFormat == Av1ChromaFormat.YUV420,
                false
        );
    }
}
