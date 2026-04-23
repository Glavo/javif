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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.model.TransformSize;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for minimal inverse-transform reconstruction.
@NotNullByDefault
final class InverseTransformerTest {
    /// Verifies that `TX_4X4` DC-only `DCT_DCT` reconstruction yields one constant residual block.
    @Test
    void reconstructsFourByFourDcOnlyResidualBlock() {
        int[] coefficients = new int[16];
        coefficients[0] = 128;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_4X4);

        assertArrayEquals(
                new int[]{
                        4, 4, 4, 4,
                        4, 4, 4, 4,
                        4, 4, 4, 4,
                        4, 4, 4, 4
                },
                residual
        );
    }

    /// Verifies that `TX_8X8` reconstruction produces the expected two-dimensional signed pattern.
    @Test
    void reconstructsEightByEightTwoDimensionalResidualPattern() {
        int[] coefficients = new int[64];
        coefficients[9] = 256;

        int[] residual = InverseTransformer.reconstructResidualBlock(coefficients, TransformSize.TX_8X8);

        assertArrayEquals(
                new int[]{
                        8, 7, 4, 2, -2, -4, -7, -8,
                        7, 6, 4, 1, -1, -4, -6, -7,
                        4, 4, 3, 1, -1, -3, -4, -4,
                        2, 1, 1, 0, 0, -1, -1, -2,
                        -2, -1, -1, 0, 0, 1, 1, 2,
                        -4, -4, -3, -1, 1, 3, 4, 4,
                        -7, -6, -4, -1, 1, 4, 6, 7,
                        -8, -7, -4, -2, 2, 4, 7, 8
                },
                residual
        );
    }

    /// Verifies that residual addition clips through the mutable plane buffer.
    @Test
    void addsResidualBlockIntoPredictorPlaneWithClipping() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                plane.setSample(x, y, 2);
            }
        }

        InverseTransformer.addResidualBlock(
                plane,
                0,
                0,
                TransformSize.TX_4X4,
                new int[]{
                        3, 1, -1, -3,
                        3, 1, -1, -3,
                        3, 1, -1, -3,
                        3, 1, -1, -3
                }
        );

        assertEquals(5, plane.sample(0, 0));
        assertEquals(3, plane.sample(1, 0));
        assertEquals(1, plane.sample(2, 0));
        assertEquals(0, plane.sample(3, 0));
        assertEquals(5, plane.sample(0, 3));
        assertEquals(0, plane.sample(3, 3));
    }

    /// Verifies that clipped residual addition only updates the visible residual footprint.
    @Test
    void addsResidualBlockOnlyInsideVisibleFootprint() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(4, 4, 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                plane.setSample(x, y, 100);
            }
        }

        InverseTransformer.addResidualBlock(
                plane,
                0,
                0,
                TransformSize.TX_4X4,
                3,
                2,
                new int[]{
                        5, 6, 7, 8,
                        4, 3, 2, 1,
                        9, 9, 9, 9,
                        9, 9, 9, 9
                }
        );

        assertEquals(105, plane.sample(0, 0));
        assertEquals(106, plane.sample(1, 0));
        assertEquals(107, plane.sample(2, 0));
        assertEquals(100, plane.sample(3, 0));
        assertEquals(104, plane.sample(0, 1));
        assertEquals(103, plane.sample(1, 1));
        assertEquals(102, plane.sample(2, 1));
        assertEquals(100, plane.sample(3, 1));
        assertEquals(100, plane.sample(0, 2));
        assertEquals(100, plane.sample(3, 3));
    }

    /// Verifies that unsupported transform sizes still fail fast in the current minimal subset.
    @Test
    void rejectsUnsupportedTransformSizes() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> InverseTransformer.reconstructResidualBlock(new int[256], TransformSize.TX_16X16)
        );

        assertEquals("Unsupported inverse transform size: TX_16X16", exception.getMessage());
    }
}
