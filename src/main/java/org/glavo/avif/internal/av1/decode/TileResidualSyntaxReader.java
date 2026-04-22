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
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Reader for the first coefficient-side syntax elements inside one tile bitstream.
///
/// The current implementation covers luma `txb_skip`, end-of-block prefix decoding, DC-only
/// coefficient decoding, and the first scanned AC coefficient. Higher-order AC token trees are
/// still added incrementally on top of this foundation.
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
        if (endOfBlockIndex > 1) {
            throw new IllegalStateException("AC coefficient decoding is not implemented yet");
        }

        int[] coefficients = new int[nonNullTransformUnit.size().widthPixels() * nonNullTransformUnit.size().heightPixels()];
        int cumulativeLevel = 0;

        if (endOfBlockIndex == 1) {
            int coefficientIndex = naturalCoefficientIndex(nonNullTransformUnit.size(), endOfBlockIndex);
            int acToken = syntaxReader.readEndOfBlockBaseToken(
                    nonNullTransformUnit.size(),
                    false,
                    endOfBlockTokenContext(endOfBlockIndex, nonNullTransformUnit.size())
            );
            if (acToken == 3) {
                acToken = syntaxReader.readHighToken(nonNullTransformUnit.size(), false, 7);
            }
            if (acToken == 15) {
                acToken += syntaxReader.readCoefficientGolomb();
            }
            coefficients[coefficientIndex] = syntaxReader.readCoefficientSignFlag() ? -acToken : acToken;
            cumulativeLevel += acToken;
        }

        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(nonNullTransformUnit.size(), false, 0)
                : syntaxReader.readBaseToken(nonNullTransformUnit.size(), false, 0);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(nonNullTransformUnit.size(), false, 0);
        }
        if (dcToken == 15) {
            dcToken += syntaxReader.readCoefficientGolomb();
        }

        int signedDcLevel = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(false, nonNullNeighborContext.lumaDcSignContext(nonNullTransformUnit));
            signedDcLevel = negative ? -dcToken : dcToken;
            coefficients[0] = signedDcLevel;
            cumulativeLevel += dcToken;
        }

        int coefficientContextByte = createNonZeroCoefficientContextByte(cumulativeLevel, signedDcLevel);
        return new TransformResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                endOfBlockIndex,
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

    /// Returns the natural-raster coefficient index for one supported scan index.
    ///
    /// The current implementation supports only scan indices `0` and `1`. For 2D transforms the
    /// first scanned AC coefficient follows the dominant transform axis, which matches the first
    /// `dav1d` scan entry for each supported transform size.
    ///
    /// @param transformSize the active transform size
    /// @param endOfBlockIndex the supported scan index
    /// @return the natural-raster coefficient index for the supplied scan index
    private static int naturalCoefficientIndex(TransformSize transformSize, int endOfBlockIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (endOfBlockIndex == 0) {
            return 0;
        }
        if (endOfBlockIndex != 1) {
            throw new IllegalArgumentException("Unsupported scan index: " + endOfBlockIndex);
        }
        return nonNullTransformSize.widthPixels() > nonNullTransformSize.heightPixels()
                ? 1
                : nonNullTransformSize.widthPixels();
    }

    /// Returns the end-of-block base-token context for one supported end-of-block index.
    ///
    /// @param endOfBlockIndex the supported end-of-block index
    /// @param transformSize the active transform size
    /// @return the end-of-block base-token context for the supplied index
    private static int endOfBlockTokenContext(int endOfBlockIndex, TransformSize transformSize) {
        if (endOfBlockIndex < 0) {
            throw new IllegalArgumentException("endOfBlockIndex < 0: " + endOfBlockIndex);
        }
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int tx2dSizeContext = Math.min(nonNullTransformSize.log2Width4(), 3)
                + Math.min(nonNullTransformSize.log2Height4(), 3);
        return 1 + (endOfBlockIndex > (2 << tx2dSizeContext) ? 1 : 0)
                + (endOfBlockIndex > (4 << tx2dSizeContext) ? 1 : 0);
    }

    /// Creates the stored coefficient-context byte for one non-zero transform unit.
    ///
    /// @param cumulativeLevel the sum of decoded absolute coefficient levels, clamped later to six bits
    /// @param signedDcLevel the signed DC coefficient level, or zero when the transform block has only AC
    /// @return the stored coefficient-context byte for one non-zero transform unit
    private static int createNonZeroCoefficientContextByte(int cumulativeLevel, int signedDcLevel) {
        int magnitude = Math.min(cumulativeLevel, 63);
        if (signedDcLevel == 0) {
            return magnitude | 0x40;
        }
        return magnitude | (signedDcLevel > 0 ? 0x80 : 0);
    }
}
