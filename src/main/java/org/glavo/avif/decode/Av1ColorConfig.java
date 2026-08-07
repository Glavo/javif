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
package org.glavo.avif.decode;

import org.glavo.avif.Av1ChromaFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Describes the color and chroma configuration declared by an AV1 sequence header.
///
/// Integer color-description values use the code points defined by the AV1 bitstream
/// specification. When [#colorDescriptionPresent()] is `false`, those values contain the AV1
/// defaults parsed for the sequence rather than externally supplied metadata.
///
/// @param bitDepth the decoded bit depth
/// @param monochrome whether the sequence is monochrome
/// @param colorDescriptionPresent whether explicit color description fields are present
/// @param colorPrimaries the AV1 color primaries code
/// @param transferCharacteristics the AV1 transfer characteristics code
/// @param matrixCoefficients the AV1 matrix coefficients code
/// @param colorRange whether full-range color samples are used
/// @param chromaFormat the decoded chroma layout
/// @param chromaSamplePosition the AV1 chroma sample position code
/// @param chromaSubsamplingX whether chroma is subsampled horizontally
/// @param chromaSubsamplingY whether chroma is subsampled vertically
/// @param separateUvDeltaQ whether separate UV delta quantization is enabled
@NotNullByDefault
public record Av1ColorConfig(
        int bitDepth,
        boolean monochrome,
        boolean colorDescriptionPresent,
        int colorPrimaries,
        int transferCharacteristics,
        int matrixCoefficients,
        boolean colorRange,
        Av1ChromaFormat chromaFormat,
        int chromaSamplePosition,
        boolean chromaSubsamplingX,
        boolean chromaSubsamplingY,
        boolean separateUvDeltaQ
) {
    /// Creates an AV1 color configuration.
    public Av1ColorConfig {
        Objects.requireNonNull(chromaFormat, "chromaFormat");
    }
}
