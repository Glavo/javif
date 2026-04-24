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

/// One AV1 transform type expressed as separable horizontal and vertical kernels.
///
/// Coefficients are stored in natural raster order where the horizontal kernel consumes the
/// coefficient column index and the vertical kernel consumes the coefficient row index.
@NotNullByDefault
public enum TransformType {
    /// A two-dimensional `DCT_DCT` transform.
    DCT_DCT(TransformKernel.DCT, TransformKernel.DCT),
    /// A vertical `ADST` and horizontal `DCT` transform.
    ADST_DCT(TransformKernel.DCT, TransformKernel.ADST),
    /// A vertical `DCT` and horizontal `ADST` transform.
    DCT_ADST(TransformKernel.ADST, TransformKernel.DCT),
    /// A two-dimensional `ADST_ADST` transform.
    ADST_ADST(TransformKernel.ADST, TransformKernel.ADST),
    /// A vertical `FLIPADST` and horizontal `DCT` transform.
    FLIPADST_DCT(TransformKernel.DCT, TransformKernel.FLIPADST),
    /// A vertical `DCT` and horizontal `FLIPADST` transform.
    DCT_FLIPADST(TransformKernel.FLIPADST, TransformKernel.DCT),
    /// A two-dimensional `FLIPADST_FLIPADST` transform.
    FLIPADST_FLIPADST(TransformKernel.FLIPADST, TransformKernel.FLIPADST),
    /// A vertical `ADST` and horizontal `FLIPADST` transform.
    ADST_FLIPADST(TransformKernel.FLIPADST, TransformKernel.ADST),
    /// A vertical `FLIPADST` and horizontal `ADST` transform.
    FLIPADST_ADST(TransformKernel.ADST, TransformKernel.FLIPADST),
    /// A two-dimensional identity transform.
    IDTX(TransformKernel.IDENTITY, TransformKernel.IDENTITY),
    /// A vertical `DCT` and horizontal identity transform.
    V_DCT(TransformKernel.IDENTITY, TransformKernel.DCT),
    /// A vertical identity and horizontal `DCT` transform.
    H_DCT(TransformKernel.DCT, TransformKernel.IDENTITY),
    /// A vertical `ADST` and horizontal identity transform.
    V_ADST(TransformKernel.IDENTITY, TransformKernel.ADST),
    /// A vertical identity and horizontal `ADST` transform.
    H_ADST(TransformKernel.ADST, TransformKernel.IDENTITY),
    /// A vertical `FLIPADST` and horizontal identity transform.
    V_FLIPADST(TransformKernel.IDENTITY, TransformKernel.FLIPADST),
    /// A vertical identity and horizontal `FLIPADST` transform.
    H_FLIPADST(TransformKernel.FLIPADST, TransformKernel.IDENTITY);

    /// The horizontal one-dimensional kernel.
    private final TransformKernel horizontalKernel;

    /// The vertical one-dimensional kernel.
    private final TransformKernel verticalKernel;

    /// Creates one transform type.
    ///
    /// @param horizontalKernel the horizontal one-dimensional kernel
    /// @param verticalKernel the vertical one-dimensional kernel
    TransformType(TransformKernel horizontalKernel, TransformKernel verticalKernel) {
        this.horizontalKernel = horizontalKernel;
        this.verticalKernel = verticalKernel;
    }

    /// Returns the horizontal one-dimensional kernel.
    ///
    /// @return the horizontal one-dimensional kernel
    public TransformKernel horizontalKernel() {
        return horizontalKernel;
    }

    /// Returns the vertical one-dimensional kernel.
    ///
    /// @return the vertical one-dimensional kernel
    public TransformKernel verticalKernel() {
        return verticalKernel;
    }

    /// Returns whether this transform belongs to the horizontal or vertical one-dimensional class.
    ///
    /// `IDTX` is intentionally not reported as one-dimensional because it has identity kernels in
    /// both axes rather than one transformed axis and one identity axis.
    ///
    /// @return whether this transform belongs to the horizontal or vertical one-dimensional class
    public boolean oneDimensional() {
        return (horizontalKernel == TransformKernel.IDENTITY) != (verticalKernel == TransformKernel.IDENTITY);
    }
}
