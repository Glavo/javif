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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests for generated AV1 quantization matrix tables.
@NotNullByDefault
final class QuantizationMatrixTablesTest {
    /// Verifies the luma 4x4 matrix generated from dav1d's triangular 32x32 base table.
    @Test
    void generatesNaturalOrderLumaFourByFourMatrix() {
        byte @Nullable @Unmodifiable [] matrix =
                QuantizationMatrixTables.matrix(0, false, TransformSize.TX_4X4);

        int[] values = unsignedValues(matrix);

        assertArrayEquals(
                new int[]{
                        32, 43, 73, 97,
                        43, 67, 94, 110,
                        73, 94, 137, 150,
                        97, 110, 150, 200
                },
                values
        );
    }

    /// Verifies that rectangular matrix generation keeps natural orientation for this decoder.
    @Test
    void generatesNaturalOrderRectangularMatrix() {
        byte @Nullable @Unmodifiable [] matrix =
                QuantizationMatrixTables.matrix(0, false, TransformSize.RTX_32X16);

        int[] firstRow = new int[16];
        for (int i = 0; i < firstRow.length; i++) {
            firstRow[i] = matrix[i] & 0xFF;
        }

        assertArrayEquals(
                new int[]{32, 31, 31, 31, 32, 32, 34, 35, 36, 39, 44, 46, 48, 53, 58, 61},
                firstRow
        );
    }

    /// Verifies that matrix index 15 maps to disabled matrix scaling.
    @Test
    void returnsNullForDisabledMatrixIndex() {
        assertNull(QuantizationMatrixTables.matrix(15, false, TransformSize.TX_4X4));
        assertNull(QuantizationMatrixTables.matrix(15, true, TransformSize.TX_4X4));
    }

    /// Converts a byte matrix to unsigned integer values.
    ///
    /// @param matrix the matrix to convert
    /// @return unsigned matrix values
    private static int[] unsignedValues(byte @Nullable @Unmodifiable [] matrix) {
        if (matrix == null) {
            throw new AssertionError("matrix is null");
        }
        int[] values = new int[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            values[i] = matrix[i] & 0xFF;
        }
        return values;
    }
}
