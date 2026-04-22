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
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Reader for the first coefficient-side syntax elements inside one tile bitstream.
///
/// The current implementation covers only luma `txb_skip` / all-zero signaling. When a transform
/// block is not all-zero, the reader stores a provisional non-zero coefficient-context byte so the
/// neighbor state can already influence subsequent `txb_skip` contexts before full token decoding
/// exists.
@NotNullByDefault
public final class TileResidualSyntaxReader {
    /// The AV1 coefficient-context byte written for all-zero transform blocks.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

    /// The provisional coefficient-context byte written for non-zero transform blocks.
    private static final int NON_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x01;

    /// The typed syntax reader used to consume coefficient-side entropy symbols.
    private final TileSyntaxReader syntaxReader;

    /// Creates one tile-local residual syntax reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    public TileResidualSyntaxReader(TileDecodeContext tileContext) {
        this.syntaxReader = new TileSyntaxReader(Objects.requireNonNull(tileContext, "tileContext"));
    }

    /// Decodes the first residual syntax pass for one already-decoded block.
    ///
    /// @param header the decoded leaf block header
    /// @param transformLayout the decoded transform layout for the same block
    /// @param neighborContext the mutable neighbor context that supplies coefficient skip contexts
    /// @return the decoded first-pass residual layout for the supplied block
    public ResidualLayout read(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        if (!nonNullHeader.position().equals(nonNullTransformLayout.position())
                || nonNullHeader.size() != nonNullTransformLayout.blockSize()) {
            throw new IllegalArgumentException("Header and transform layout must describe the same block");
        }

        TransformUnit[] transformUnits = nonNullTransformLayout.lumaUnits();
        TransformResidualUnit[] residualUnits = new TransformResidualUnit[transformUnits.length];
        for (int i = 0; i < transformUnits.length; i++) {
            TransformUnit transformUnit = transformUnits[i];
            boolean allZero;
            if (nonNullHeader.skip()) {
                allZero = true;
            } else {
                allZero = syntaxReader.readCoefficientSkipFlag(
                        transformUnit.size(),
                        nonNullNeighborContext.lumaCoefficientSkipContext(nonNullHeader.size(), transformUnit)
                );
            }
            int coefficientContextByte = allZero
                    ? ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
                    : NON_ZERO_COEFFICIENT_CONTEXT_BYTE;
            residualUnits[i] = new TransformResidualUnit(
                    transformUnit.position(),
                    transformUnit.size(),
                    allZero,
                    coefficientContextByte
            );
            nonNullNeighborContext.updateLumaCoefficientContext(transformUnit, coefficientContextByte);
        }
        return new ResidualLayout(nonNullTransformLayout.position(), nonNullTransformLayout.blockSize(), residualUnits);
    }
}
