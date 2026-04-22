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
/// The current implementation covers luma `txb_skip`, end-of-block prefix decoding, and DC-only
/// coefficient decoding. AC token trees are still added incrementally on top of this foundation.
@NotNullByDefault
public final class TileResidualSyntaxReader {
    /// The AV1 coefficient-context byte written for all-zero transform blocks.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

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
            TransformResidualUnit residualUnit = nonNullHeader.skip()
                    ? createAllZeroUnit(transformUnit)
                    : readLumaResidualUnit(nonNullHeader, transformUnit, nonNullNeighborContext);
            residualUnits[i] = residualUnit;
            nonNullNeighborContext.updateLumaCoefficientContext(transformUnit, residualUnit.coefficientContextByte());
        }
        return new ResidualLayout(nonNullTransformLayout.position(), nonNullTransformLayout.blockSize(), residualUnits);
    }

    /// Decodes one non-skipped luma transform residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformUnit the luma transform unit to decode
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @return the decoded luma transform residual unit
    private TransformResidualUnit readLumaResidualUnit(
            TileBlockHeaderReader.BlockHeader header,
            TransformUnit transformUnit,
            BlockNeighborContext neighborContext
    ) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        if (syntaxReader.readCoefficientSkipFlag(
                nonNullTransformUnit.size(),
                nonNullNeighborContext.lumaCoefficientSkipContext(Objects.requireNonNull(header, "header").size(), nonNullTransformUnit)
        )) {
            return createAllZeroUnit(nonNullTransformUnit);
        }

        int endOfBlockIndex = syntaxReader.readEndOfBlockIndex(nonNullTransformUnit.size(), false, false);
        if (endOfBlockIndex != 0) {
            throw new IllegalStateException("AC coefficient decoding is not implemented yet");
        }

        int dcToken = syntaxReader.readEndOfBlockBaseToken(nonNullTransformUnit.size(), false, 0);
        if (dcToken == 3) {
            dcToken = syntaxReader.readDcHighToken(nonNullTransformUnit.size(), false);
        }
        if (dcToken == 15) {
            dcToken += syntaxReader.readCoefficientGolomb();
        }
        boolean negative = syntaxReader.readDcSignFlag(false, nonNullNeighborContext.lumaDcSignContext(nonNullTransformUnit));
        int signedDcLevel = negative ? -dcToken : dcToken;
        int coefficientContextByte = createNonZeroCoefficientContextByte(signedDcLevel);
        int[] coefficients = new int[nonNullTransformUnit.size().widthPixels() * nonNullTransformUnit.size().heightPixels()];
        coefficients[0] = signedDcLevel;
        return new TransformResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                0,
                coefficients,
                coefficientContextByte
        );
    }

    /// Creates one all-zero transform residual unit.
    ///
    /// @param transformUnit the transform unit whose residual syntax is all-zero
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroUnit(TransformUnit transformUnit) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        return new TransformResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                -1,
                new int[nonNullTransformUnit.size().widthPixels() * nonNullTransformUnit.size().heightPixels()],
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
        );
    }

    /// Creates the stored coefficient-context byte for one non-zero DC coefficient.
    ///
    /// @param signedDcLevel the signed DC coefficient level
    /// @return the stored coefficient-context byte for one non-zero DC coefficient
    private static int createNonZeroCoefficientContextByte(int signedDcLevel) {
        int magnitude = Math.min(Math.abs(signedDcLevel), 63);
        return magnitude | (signedDcLevel > 0 ? 0x80 : 0);
    }
}
