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
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TransformType`.
@NotNullByDefault
final class TransformTypeTest {
    /// Verifies representative two-dimensional transform kernel mappings.
    @Test
    void exposesTwoDimensionalTransformKernels() {
        assertEquals(TransformKernel.DCT, TransformType.DCT_DCT.horizontalKernel());
        assertEquals(TransformKernel.DCT, TransformType.DCT_DCT.verticalKernel());
        assertEquals(TransformKernel.FLIPADST, TransformType.FLIPADST_ADST.verticalKernel());
        assertEquals(TransformKernel.ADST, TransformType.FLIPADST_ADST.horizontalKernel());
        assertFalse(TransformType.DCT_DCT.oneDimensional());
        assertFalse(TransformType.FLIPADST_ADST.oneDimensional());
    }

    /// Verifies that the horizontal and vertical one-dimensional transform classes are exposed.
    @Test
    void exposesOneDimensionalTransformClasses() {
        assertEquals(TransformKernel.DCT, TransformType.H_DCT.horizontalKernel());
        assertEquals(TransformKernel.IDENTITY, TransformType.H_DCT.verticalKernel());
        assertEquals(TransformKernel.IDENTITY, TransformType.V_ADST.horizontalKernel());
        assertEquals(TransformKernel.ADST, TransformType.V_ADST.verticalKernel());
        assertTrue(TransformType.H_DCT.oneDimensional());
        assertTrue(TransformType.V_ADST.oneDimensional());
        assertFalse(TransformType.IDTX.oneDimensional());
    }
}
