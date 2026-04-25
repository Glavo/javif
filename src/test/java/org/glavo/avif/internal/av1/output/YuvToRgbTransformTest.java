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
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.AvifColorInfo;
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Tests for AV1 color-configuration driven YUV-to-RGB transform selection.
@NotNullByDefault
final class YuvToRgbTransformTest {
    /// Verifies that limited-range luma endpoints map to black and white.
    @Test
    void limitedRangeTransformExpandsNominalLumaRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(6, false, AvifPixelFormat.I420));

        assertEquals(0xFF00_0000, transform.toOpaqueArgb(16, 128, 128));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueArgb(235, 128, 128));
    }

    /// Verifies that unspecified matrix coefficients still preserve range for monochrome streams.
    @Test
    void unspecifiedMonochromeTransformPreservesRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(2, false, AvifPixelFormat.I400));

        assertEquals(0xFF00_0000, transform.toOpaqueGrayArgb(16));
        assertEquals(0xFFFF_FFFF, transform.toOpaqueGrayArgb(235));
    }

    /// Verifies that matrix coefficients affect chroma conversion.
    @Test
    void matrixCoefficientsSelectDifferentTransforms() {
        YuvToRgbTransform bt601 = YuvToRgbTransform.fromColorConfig(colorConfig(6, true, AvifPixelFormat.I420));
        YuvToRgbTransform bt709 = YuvToRgbTransform.fromColorConfig(colorConfig(1, true, AvifPixelFormat.I420));

        assertNotEquals(bt601.toOpaqueArgb(100, 90, 200), bt709.toOpaqueArgb(100, 90, 200));
    }

    /// Verifies AV1 identity-matrix RGB signaling maps planes directly to RGB channels.
    @Test
    void identityMatrixMapsPlanesAsRgbSamples() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(0, true, AvifPixelFormat.I444));

        assertEquals(0xFF11_2233, transform.toOpaqueArgb(0x22, 0x33, 0x11));
    }

    /// Verifies that high-bit-depth limited-range grayscale endpoints are expanded to 16-bit output.
    @Test
    void limitedRangeHighBitDepthExpandsNominalLumaRange() {
        YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig(6, false, AvifPixelFormat.I420));

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
    /// @param pixelFormat the decoded chroma layout
    /// @return one AV1 color configuration
    private static SequenceHeader.ColorConfig colorConfig(
            int matrixCoefficients,
            boolean fullRange,
            AvifPixelFormat pixelFormat
    ) {
        return new SequenceHeader.ColorConfig(
                8,
                pixelFormat == AvifPixelFormat.I400,
                true,
                1,
                13,
                matrixCoefficients,
                fullRange,
                pixelFormat,
                0,
                pixelFormat == AvifPixelFormat.I400
                        || pixelFormat == AvifPixelFormat.I420
                        || pixelFormat == AvifPixelFormat.I422,
                pixelFormat == AvifPixelFormat.I400 || pixelFormat == AvifPixelFormat.I420,
                false
        );
    }
}
