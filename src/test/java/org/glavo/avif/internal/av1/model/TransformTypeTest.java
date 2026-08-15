// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
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
