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

/// One one-dimensional kernel used by a separable AV1 transform type.
@NotNullByDefault
public enum TransformKernel {
    /// The inverse discrete cosine transform kernel.
    DCT,
    /// The inverse asymmetric discrete sine transform kernel.
    ADST,
    /// The inverse asymmetric discrete sine transform kernel with flipped spatial output.
    FLIPADST,
    /// The inverse Walsh-Hadamard transform kernel used by AV1 lossless 4x4 blocks.
    WHT,
    /// The identity transform kernel.
    IDENTITY
}
