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

import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformType;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Reader for the first coefficient-side syntax elements inside one tile bitstream.
///
/// The current implementation fully decodes the currently modeled two-dimensional luma residual
/// path and the chroma-U/chroma-V residual unit arrays exposed by `TransformLayout`, including
/// pixel-level clipped chroma footprints along the frame fringe.
/// `TX_4X4` keeps its dedicated context helpers, while larger transform sizes use the same
/// coefficient-neighbor token contexts so multi-coefficient blocks do not fall back to fixed
/// probabilities once AC residuals are present.
@NotNullByDefault
public final class TileResidualSyntaxReader {
    /// The AV1 coefficient-context byte written for all-zero transform blocks.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0;

    /// The chroma-U plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_U = 0;

    /// The chroma-V plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_V = 1;

    /// The `dav1d` two-dimensional `TX_4X4` scan converted into row-major coefficient storage.
    private static final int @Unmodifiable [] FOUR_BY_FOUR_SCAN = {
            0, 1, 4, 8,
            5, 2, 3, 6,
            9, 12, 13, 10,
            7, 11, 14, 15
    };

    /// The `dav1d`-compatible two-dimensional scan tables for every modeled transform size.
    private static final int @Unmodifiable [] @Unmodifiable [] DEFAULT_SCANS = createDefaultScans();

    /// The `dav1d` transform types inferred from chroma intra prediction modes.
    private static final TransformType @Unmodifiable [] UV_INTRA_TRANSFORM_TYPES = {
            TransformType.DCT_DCT,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.DCT_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.DCT_ADST,
            TransformType.ADST_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.ADST_ADST,
            TransformType.DCT_DCT
    };

    /// The `dav1d` luma modes used as transform-type context for filter-intra blocks.
    private static final LumaIntraPredictionMode @Unmodifiable [] FILTER_INTRA_TRANSFORM_MODES = {
            LumaIntraPredictionMode.DC,
            LumaIntraPredictionMode.VERTICAL,
            LumaIntraPredictionMode.HORIZONTAL,
            LumaIntraPredictionMode.HORIZONTAL_DOWN,
            LumaIntraPredictionMode.DC
    };

    /// The `dav1d` low-token context offsets for square, wide, and tall 2D transforms.
    private static final int @Unmodifiable [] @Unmodifiable [] @Unmodifiable [] LEVEL_CONTEXT_OFFSETS = {
            {
                    {0, 1, 6, 6, 21},
                    {1, 6, 6, 21, 21},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            },
            {
                    {0, 16, 6, 6, 21},
                    {16, 16, 6, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21}
            },
            {
                    {0, 11, 11, 11, 11},
                    {11, 11, 11, 11, 11},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            }
    };

    /// The padded `levels` grid size used by the `TX_4X4` token-context helpers.
    private static final int FOUR_BY_FOUR_LEVEL_GRID_SIZE = 8;

    /// The tile-local decode state that owns the active sequence and frame headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used to consume coefficient-side entropy symbols.
    private final TileSyntaxReader syntaxReader;

    /// Creates one tile-local residual syntax reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active tile bitstream
    public TileResidualSyntaxReader(TileDecodeContext tileContext) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.syntaxReader = new TileSyntaxReader(this.tileContext);
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
            int visibleWidthPixels = visibleLumaWidthPixels(nonNullTransformLayout, transformUnit);
            int visibleHeightPixels = visibleLumaHeightPixels(nonNullTransformLayout, transformUnit);
            TransformResidualUnit residualUnit = nonNullHeader.skip()
                    ? createAllZeroUnit(transformUnit.position(), transformUnit.size(), visibleWidthPixels, visibleHeightPixels)
                    : readLumaResidualUnit(
                            nonNullHeader,
                            nonNullTransformLayout,
                            transformUnit,
                            nonNullNeighborContext
                    );
            residualUnits[i] = residualUnit;
            nonNullNeighborContext.updateLumaCoefficientContext(transformUnit, residualUnit.coefficientContextByte());
        }

        TransformResidualUnit[] chromaUUnits = new TransformResidualUnit[0];
        TransformResidualUnit[] chromaVUnits = new TransformResidualUnit[0];
        TransformUnit[] chromaUnits = nonNullTransformLayout.chromaUnits();
        if (nonNullHeader.hasChroma()
                && nonNullHeader.uvPaletteSize() == 0
                && chromaUnits.length != 0) {
            chromaUUnits = readChromaResidualUnits(
                    nonNullHeader,
                    nonNullTransformLayout,
                    chromaUnits,
                    residualUnits,
                    CHROMA_PLANE_U,
                    nonNullNeighborContext
            );
            chromaVUnits = readChromaResidualUnits(
                    nonNullHeader,
                    nonNullTransformLayout,
                    chromaUnits,
                    residualUnits,
                    CHROMA_PLANE_V,
                    nonNullNeighborContext
            );
        }
        return new ResidualLayout(
                nonNullTransformLayout.position(),
                nonNullTransformLayout.blockSize(),
                residualUnits,
                chromaUUnits,
                chromaVUnits
        );
    }

    /// Returns the horizontal chroma subsampling shift used by the active sequence pixel format.
    ///
    /// @return the horizontal chroma subsampling shift used by the active sequence pixel format
    private int chromaSubsamplingX() {
        return switch (tileContext.sequenceHeader().colorConfig().pixelFormat()) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift used by the active sequence pixel format.
    ///
    /// @return the vertical chroma subsampling shift used by the active sequence pixel format
    private int chromaSubsamplingY() {
        return switch (tileContext.sequenceHeader().colorConfig().pixelFormat()) {
            case I400, I422, I444 -> 0;
            case I420 -> 1;
        };
    }

    /// Decodes one non-skipped luma transform residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the luma transform unit to decode
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @return the decoded luma transform residual unit
    private TransformResidualUnit readLumaResidualUnit(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformUnit transformUnit,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");

        int visibleWidthPixels = visibleLumaWidthPixels(nonNullTransformLayout, nonNullTransformUnit);
        int visibleHeightPixels = visibleLumaHeightPixels(nonNullTransformLayout, nonNullTransformUnit);
        int coefficientSkipContext = nonNullNeighborContext.lumaCoefficientSkipContext(
                nonNullHeader.size(),
                nonNullTransformUnit
        );
        if (syntaxReader.readCoefficientSkipFlag(nonNullTransformUnit.size(), coefficientSkipContext)) {
            return createAllZeroUnit(
                    nonNullTransformUnit.position(),
                    nonNullTransformUnit.size(),
                    visibleWidthPixels,
                    visibleHeightPixels
            );
        }

        return readNonSkippedResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                visibleWidthPixels,
                visibleHeightPixels,
                false,
                lumaTransformType(nonNullHeader, nonNullTransformUnit.size()),
                nonNullNeighborContext.lumaDcSignContext(nonNullTransformUnit)
        );
    }

    /// Decodes the currently modeled chroma transform residual units for one chroma plane.
    ///
    /// Each explicit chroma unit follows the same token readers as luma, only switching to the
    /// shared chroma coefficient CDF tables and per-plane neighbor contexts.
    ///
    /// @param header the owning leaf block header
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnits the decoded chroma transform units in bitstream order
    /// @param lumaResidualUnits the decoded luma residual units used for inter transform-type inference
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @return the decoded chroma transform residual units in bitstream order
    private TransformResidualUnit[] readChromaResidualUnits(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformUnit[] transformUnits,
            TransformResidualUnit[] lumaResidualUnits,
            int plane,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit[] nonNullTransformUnits = Objects.requireNonNull(transformUnits, "transformUnits");
        TransformResidualUnit[] nonNullLumaResidualUnits = Objects.requireNonNull(lumaResidualUnits, "lumaResidualUnits");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");

        TransformResidualUnit[] residualUnits = new TransformResidualUnit[nonNullTransformUnits.length];
        for (int i = 0; i < nonNullTransformUnits.length; i++) {
            TransformUnit transformUnit = nonNullTransformUnits[i];
            BlockPosition unitPosition = transformUnit.position();
            TransformSize unitSize = transformUnit.size();
            int visibleUnitWidthPixels = visibleChromaUnitWidthPixels(nonNullTransformLayout, transformUnit);
            int visibleUnitHeightPixels = visibleChromaUnitHeightPixels(nonNullTransformLayout, transformUnit);
            TransformResidualUnit residualUnit;
            if (nonNullHeader.skip()) {
                residualUnit = createAllZeroUnit(
                        unitPosition,
                        unitSize,
                        visibleUnitWidthPixels,
                        visibleUnitHeightPixels
                );
            } else {
                int coefficientSkipContext = nonNullNeighborContext.chromaCoefficientSkipContext(
                        plane,
                        nonNullHeader.size(),
                        unitPosition,
                        unitSize
                );
                if (syntaxReader.readCoefficientSkipFlag(unitSize, coefficientSkipContext)) {
                    residualUnit = createAllZeroUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels
                    );
                } else {
                    residualUnit = readNonSkippedResidualUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels,
                            true,
                            chromaTransformType(
                                    nonNullHeader,
                                    unitSize,
                                    lumaTransformTypeAt(nonNullLumaResidualUnits, transformUnit)
                            ),
                            nonNullNeighborContext.chromaDcSignContext(
                                    plane,
                                    unitPosition,
                                    unitSize
                            )
                    );
                }
            }
            nonNullNeighborContext.updateChromaCoefficientContext(
                    plane,
                    unitPosition,
                    unitSize,
                    residualUnit.coefficientContextByte()
            );
            residualUnits[i] = residualUnit;
        }
        return residualUnits;
    }

    /// Decodes one non-skipped transform residual unit using the supplied plane-specific contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param dcSignContext the DC-sign context for the current unit
    /// @return the decoded non-skipped transform residual unit
    private TransformResidualUnit readNonSkippedResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");

        int endOfBlockIndex = syntaxReader.readEndOfBlockIndex(
                nonNullTransformSize,
                chroma,
                nonNullTransformType.oneDimensional()
        );
        if (nonNullTransformSize == TransformSize.TX_4X4) {
            return readFourByFourResidualUnit(
                    nonNullPosition,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    chroma,
                    nonNullTransformType,
                    endOfBlockIndex,
                    dcSignContext
            );
        }
        return readGenericResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
                visibleWidthPixels,
                visibleHeightPixels,
                chroma,
                nonNullTransformType,
                endOfBlockIndex,
                dcSignContext
        );
    }

    /// Decodes the fully supported two-dimensional `TX_4X4` residual syntax for one plane.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded `TX_4X4` residual unit
    private TransformResidualUnit readFourByFourResidualUnit(
            BlockPosition position,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int[] coefficients = new int[16];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        int[][] levelBytes = new int[FOUR_BY_FOUR_LEVEL_GRID_SIZE][FOUR_BY_FOUR_LEVEL_GRID_SIZE];

        if (endOfBlockIndex > 0) {
            int lastX = fourByFourLevelX(nonNullTransformType, endOfBlockIndex);
            int lastY = fourByFourLevelY(nonNullTransformType, endOfBlockIndex);
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    TransformSize.TX_4X4,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, TransformSize.TX_4X4)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                );
            }
            coefficientTokens[endOfBlockIndex] = lastToken;
            levelBytes[lastX][lastY] = coefficientLevelByte(lastToken);

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int x = fourByFourLevelX(nonNullTransformType, scanIndex);
                int y = fourByFourLevelY(nonNullTransformType, scanIndex);
                int token = syntaxReader.readBaseToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourBaseTokenContext(nonNullTransformType, levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            TransformSize.TX_4X4,
                            chroma,
                            fourByFourHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    );
                }
                coefficientTokens[scanIndex] = token;
                levelBytes[x][y] = coefficientLevelByte(token);
            }
        }

        int dcBaseContext = nonNullTransformType.oneDimensional()
                ? fourByFourBaseTokenContext(nonNullTransformType, levelBytes, 0, 0)
                : 0;
        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(TransformSize.TX_4X4, chroma, 0)
                : syntaxReader.readBaseToken(TransformSize.TX_4X4, chroma, dcBaseContext);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                    TransformSize.TX_4X4,
                    chroma,
                    fourByFourDcHighTokenContext(nonNullTransformType, levelBytes)
            );
        }

        int cumulativeLevel = 0;
        int signedDcLevel = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(chroma, dcSignContext);
            if (dcToken == 15) {
                dcToken += syntaxReader.readCoefficientGolomb();
            }
            signedDcLevel = negative ? -dcToken : dcToken;
            coefficients[0] = signedDcLevel;
            cumulativeLevel += dcToken;
        }

        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int token = coefficientTokens[scanIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token += syntaxReader.readCoefficientGolomb();
            }
            coefficients[fourByFourCoefficientIndex(nonNullTransformType, scanIndex)] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return new TransformResidualUnit(
                nonNullPosition,
                TransformSize.TX_4X4,
                nonNullTransformType,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(cumulativeLevel, signedDcLevel)
        );
    }

    /// Decodes one non-`TX_4X4` residual unit with coefficient-neighbor token contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param transformType the active transform type
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded larger-transform residual unit
    private TransformResidualUnit readGenericResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            TransformType transformType,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int[] coefficients = new int[nonNullTransformSize.widthPixels() * nonNullTransformSize.heightPixels()];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        int[][] levelBytes = createGenericLevelGrid(nonNullTransformSize, nonNullTransformType);
        if (endOfBlockIndex > 0) {
            int lastX = genericLevelX(nonNullTransformSize, nonNullTransformType, endOfBlockIndex);
            int lastY = genericLevelY(nonNullTransformSize, nonNullTransformType, endOfBlockIndex);
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    nonNullTransformSize,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, nonNullTransformSize)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        nonNullTransformSize,
                        chroma,
                        genericEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                );
            }
            coefficientTokens[endOfBlockIndex] = lastToken;
            levelBytes[lastX][lastY] = coefficientLevelByte(lastToken);

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int x = genericLevelX(nonNullTransformSize, nonNullTransformType, scanIndex);
                int y = genericLevelY(nonNullTransformSize, nonNullTransformType, scanIndex);
                int token = syntaxReader.readBaseToken(
                        nonNullTransformSize,
                        chroma,
                        genericBaseTokenContext(nonNullTransformSize, nonNullTransformType, levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            nonNullTransformSize,
                            chroma,
                            genericHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    );
                }
                coefficientTokens[scanIndex] = token;
                levelBytes[x][y] = coefficientLevelByte(token);
            }
        }

        int dcBaseContext = endOfBlockIndex > 0 && nonNullTransformType.oneDimensional()
                ? genericBaseTokenContext(nonNullTransformSize, nonNullTransformType, levelBytes, 0, 0)
                : 0;
        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(nonNullTransformSize, chroma, 0)
                : syntaxReader.readBaseToken(nonNullTransformSize, chroma, dcBaseContext);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                    nonNullTransformSize,
                    chroma,
                    genericDcHighTokenContext(nonNullTransformType, levelBytes)
            );
        }

        int cumulativeLevel = 0;
        int signedDcLevel = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(chroma, dcSignContext);
            if (dcToken == 15) {
                dcToken += syntaxReader.readCoefficientGolomb();
            }
            signedDcLevel = negative ? -dcToken : dcToken;
            coefficients[0] = signedDcLevel;
            cumulativeLevel += dcToken;
        }

        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int token = coefficientTokens[scanIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token += syntaxReader.readCoefficientGolomb();
            }
            coefficients[coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex)] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return new TransformResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
                nonNullTransformType,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(cumulativeLevel, signedDcLevel)
        );
    }

    /// Creates one all-zero transform residual unit.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the transform size whose residual syntax is all-zero
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return new TransformResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
                -1,
                new int[nonNullTransformSize.widthPixels() * nonNullTransformSize.heightPixels()],
                visibleWidthPixels,
                visibleHeightPixels,
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
        );
    }

    /// Returns the exact visible luma width in pixels for one transform unit inside the current
    /// block-level transform layout.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the transform unit whose visible width should be measured
    /// @return the exact visible luma width in pixels for the supplied transform unit
    private static int visibleLumaWidthPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int relativeX = (nonNullTransformUnit.position().x4() - nonNullTransformLayout.position().x4()) << 2;
        return Math.min(
                nonNullTransformUnit.size().widthPixels(),
                Math.max(0, nonNullTransformLayout.visibleWidthPixels() - relativeX)
        );
    }

    /// Returns the exact visible luma height in pixels for one transform unit inside the current
    /// block-level transform layout.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the transform unit whose visible height should be measured
    /// @return the exact visible luma height in pixels for the supplied transform unit
    private static int visibleLumaHeightPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int relativeY = (nonNullTransformUnit.position().y4() - nonNullTransformLayout.position().y4()) << 2;
        return Math.min(
                nonNullTransformUnit.size().heightPixels(),
                Math.max(0, nonNullTransformLayout.visibleHeightPixels() - relativeY)
        );
    }

    /// Returns the exact visible chroma width in pixels for one chroma transform unit.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the chroma transform unit whose visible width should be measured
    /// @return the exact visible chroma width in pixels for the supplied unit
    private int visibleChromaUnitWidthPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int shift = chromaSubsamplingX();
        int visibleBlockWidthPixels = (nonNullTransformLayout.visibleWidthPixels() + (1 << shift) - 1) >> shift;
        int relativeLumaX = (nonNullTransformUnit.position().x4() - nonNullTransformLayout.position().x4()) << 2;
        int relativeChromaX = relativeLumaX >> shift;
        return Math.min(
                nonNullTransformUnit.size().widthPixels(),
                Math.max(0, visibleBlockWidthPixels - relativeChromaX)
        );
    }

    /// Returns the exact visible chroma height in pixels for one chroma transform unit.
    ///
    /// @param transformLayout the owning block-level transform layout
    /// @param transformUnit the chroma transform unit whose visible height should be measured
    /// @return the exact visible chroma height in pixels for the supplied unit
    private int visibleChromaUnitHeightPixels(TransformLayout transformLayout, TransformUnit transformUnit) {
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        int shift = chromaSubsamplingY();
        int visibleBlockHeightPixels = (nonNullTransformLayout.visibleHeightPixels() + (1 << shift) - 1) >> shift;
        int relativeLumaY = (nonNullTransformUnit.position().y4() - nonNullTransformLayout.position().y4()) << 2;
        int relativeChromaY = relativeLumaY >> shift;
        return Math.min(
                nonNullTransformUnit.size().heightPixels(),
                Math.max(0, visibleBlockHeightPixels - relativeChromaY)
        );
    }

    /// Selects the luma transform type for one non-skipped luma residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformSize the active luma transform size
    /// @return the luma transform type for the supplied residual unit
    private TransformType lumaTransformType(TileBlockHeaderReader.BlockHeader header, TransformSize transformSize) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        boolean intraTransform = intraTransformType(nonNullHeader);
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT;
        }
        if (nonNullTransformSize.maxSquareLevel() + (intraTransform ? 1 : 0)
                >= TransformSize.TX_64X64.maxSquareLevel()) {
            return TransformType.DCT_DCT;
        }
        if (nonNullHeader.qIndex() == 0) {
            return TransformType.DCT_DCT;
        }
        if (intraTransform) {
            return syntaxReader.readIntraTransformType(
                    nonNullTransformSize,
                    lumaModeForTransformType(nonNullHeader),
                    tileContext.frameHeader().reducedTransformSet()
            );
        }
        return syntaxReader.readInterTransformType(
                nonNullTransformSize,
                tileContext.frameHeader().reducedTransformSet()
        );
    }

    /// Selects the chroma transform type for one non-skipped chroma residual unit.
    ///
    /// @param header the owning leaf block header
    /// @param transformSize the active chroma transform size
    /// @param lumaTransformType the colocated luma transform type for inter-derived chroma transforms
    /// @return the chroma transform type for the supplied residual unit
    private TransformType chromaTransformType(
            TileBlockHeaderReader.BlockHeader header,
            TransformSize transformSize,
            TransformType lumaTransformType
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullLumaTransformType = Objects.requireNonNull(lumaTransformType, "lumaTransformType");
        boolean intraTransform = intraTransformType(nonNullHeader);
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT;
        }
        if (nonNullTransformSize.maxSquareLevel() + (intraTransform ? 1 : 0)
                >= TransformSize.TX_64X64.maxSquareLevel()) {
            return TransformType.DCT_DCT;
        }
        if (intraTransform) {
            return uvIntraTransformType(nonNullHeader.uvMode());
        }
        return chromaInterTransformType(nonNullTransformSize, nonNullLumaTransformType);
    }

    /// Returns whether this block follows the intra transform-type branch.
    ///
    /// @param header the owning leaf block header
    /// @return whether this block follows the intra transform-type branch
    private static boolean intraTransformType(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        return nonNullHeader.intra() && !nonNullHeader.useIntrabc();
    }

    /// Returns the luma intra mode used as transform-type context.
    ///
    /// @param header the owning leaf block header
    /// @return the luma intra mode used as transform-type context
    private static LumaIntraPredictionMode lumaModeForTransformType(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        FilterIntraMode filterIntraMode = nonNullHeader.filterIntraMode();
        if (filterIntraMode != null) {
            return FILTER_INTRA_TRANSFORM_MODES[filterIntraMode.symbolIndex()];
        }
        LumaIntraPredictionMode yMode = nonNullHeader.yMode();
        return yMode != null ? yMode : LumaIntraPredictionMode.DC;
    }

    /// Returns the transform type inferred from one chroma intra prediction mode.
    ///
    /// @param uvMode the chroma intra prediction mode, or `null`
    /// @return the transform type inferred from the supplied chroma intra prediction mode
    private static TransformType uvIntraTransformType(UvIntraPredictionMode uvMode) {
        if (uvMode == null) {
            return TransformType.DCT_DCT;
        }
        return UV_INTRA_TRANSFORM_TYPES[uvMode.symbolIndex()];
    }

    /// Returns the chroma inter transform type inferred from the colocated luma transform type.
    ///
    /// @param transformSize the active chroma transform size
    /// @param lumaTransformType the colocated luma transform type
    /// @return the chroma inter transform type inferred from the supplied luma transform type
    private static TransformType chromaInterTransformType(TransformSize transformSize, TransformType lumaTransformType) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullLumaTransformType = Objects.requireNonNull(lumaTransformType, "lumaTransformType");
        if (nonNullTransformSize.maxSquareLevel() == TransformSize.TX_32X32.maxSquareLevel()) {
            return nonNullLumaTransformType == TransformType.IDTX ? TransformType.IDTX : TransformType.DCT_DCT;
        }
        if (nonNullTransformSize.minSquareLevel() == TransformSize.TX_16X16.minSquareLevel()
                && isLargeChromaInterTransformExcluded(nonNullLumaTransformType)) {
            return TransformType.DCT_DCT;
        }
        return nonNullLumaTransformType;
    }

    /// Returns whether a luma transform type is excluded from 16x16 chroma inter transforms.
    ///
    /// @param transformType the luma transform type
    /// @return whether the supplied transform type is excluded from 16x16 chroma inter transforms
    private static boolean isLargeChromaInterTransformExcluded(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case V_ADST, H_ADST, V_FLIPADST, H_FLIPADST -> true;
            default -> false;
        };
    }

    /// Returns the transform type of the luma residual unit colocated with one chroma transform unit.
    ///
    /// @param lumaResidualUnits the already decoded luma residual units
    /// @param chromaUnit the chroma transform unit whose colocated luma type should be found
    /// @return the transform type of the colocated luma residual unit
    private static TransformType lumaTransformTypeAt(
            TransformResidualUnit[] lumaResidualUnits,
            TransformUnit chromaUnit
    ) {
        TransformResidualUnit[] nonNullLumaResidualUnits = Objects.requireNonNull(lumaResidualUnits, "lumaResidualUnits");
        TransformUnit nonNullChromaUnit = Objects.requireNonNull(chromaUnit, "chromaUnit");
        int x4 = nonNullChromaUnit.position().x4();
        int y4 = nonNullChromaUnit.position().y4();
        for (TransformResidualUnit lumaResidualUnit : nonNullLumaResidualUnits) {
            BlockPosition lumaPosition = lumaResidualUnit.position();
            TransformSize lumaSize = lumaResidualUnit.size();
            if (x4 >= lumaPosition.x4()
                    && x4 < lumaPosition.x4() + lumaSize.width4()
                    && y4 >= lumaPosition.y4()
                    && y4 < lumaPosition.y4() + lumaSize.height4()) {
                return lumaResidualUnit.transformType();
            }
        }
        return TransformType.DCT_DCT;
    }

    /// Returns the natural coefficient index addressed by one scan index and transform class.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the natural coefficient index addressed by the supplied scan index
    private static int coefficientIndex(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return DEFAULT_SCANS[nonNullTransformSize.ordinal()][scanIndex];
        }
        if (!verticalOneDimensional(nonNullTransformType)) {
            return scanIndex;
        }
        int x = scanIndex & (nonNullTransformSize.widthPixels() - 1);
        int y = scanIndex >> (nonNullTransformSize.log2Width4() + 2);
        return (x << (nonNullTransformSize.log2Height4() + 2)) | y;
    }

    /// Creates the padded larger-transform `levels` grid used by coefficient token contexts.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @return a padded zero-filled level grid
    private static int[][] createGenericLevelGrid(TransformSize transformSize, TransformType transformType) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int width;
        int height;
        if (!nonNullTransformType.oneDimensional()) {
            width = clippedCoefficientWidth(nonNullTransformSize);
            height = clippedCoefficientHeight(nonNullTransformSize);
        } else if (verticalOneDimensional(nonNullTransformType)) {
            width = 4 << Math.min(nonNullTransformSize.log2Width4(), 3);
            height = coefficientCount(nonNullTransformSize) / width;
        } else {
            width = 4 << Math.min(nonNullTransformSize.log2Height4(), 3);
            height = coefficientCount(nonNullTransformSize) / width;
        }
        return new int[width + 5][height + 5];
    }

    /// Returns the coefficient-context grid X coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid X coordinate
    private static int genericLevelX(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex)
                    & (nonNullTransformSize.widthPixels() - 1);
        }
        int log2Primary = verticalOneDimensional(nonNullTransformType)
                ? Math.min(nonNullTransformSize.log2Width4(), 3)
                : Math.min(nonNullTransformSize.log2Height4(), 3);
        return scanIndex & ((4 << log2Primary) - 1);
    }

    /// Returns the coefficient-context grid Y coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid Y coordinate
    private static int genericLevelY(TransformSize transformSize, TransformType transformType, int scanIndex) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex)
                    >> (nonNullTransformSize.log2Width4() + 2);
        }
        int log2Primary = verticalOneDimensional(nonNullTransformType)
                ? Math.min(nonNullTransformSize.log2Width4(), 3)
                : Math.min(nonNullTransformSize.log2Height4(), 3);
        return scanIndex >> (log2Primary + 2);
    }

    /// Returns the base-token context for one larger-transform coefficient.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the base-token context for the supplied coefficient
    private static int genericBaseTokenContext(
            TransformSize transformSize,
            TransformType transformType,
            int[][] levelBytes,
            int x,
            int y
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = genericHighMagnitude(nonNullTransformType, levelBytes, x, y);
        int offset;
        if (nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes[x][y + 3] + levelBytes[x][y + 4];
            offset = 26 + (y > 1 ? 10 : y * 5);
        } else {
            magnitude += levelBytes[x][y + 2] + levelBytes[x + 2][y];
            offset = LEVEL_CONTEXT_OFFSETS[levelContextOffsetIndex(nonNullTransformSize)]
                    [Math.min(y, 4)][Math.min(x, 4)];
        }
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one larger-transform non-DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied coefficient
    private static int genericHighTokenContext(TransformType transformType, int[][] levelBytes, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = genericHighMagnitude(nonNullTransformType, levelBytes, x, y) & 0x3F;
        int baseContext = genericEndOfBlockHighTokenContext(nonNullTransformType, x, y);
        return baseContext + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the high-token context for one larger-transform end-of-block coefficient.
    ///
    /// @param transformType the active transform type
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied end-of-block coefficient
    private static int genericEndOfBlockHighTokenContext(TransformType transformType, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return y != 0 ? 14 : 7;
        }
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the high-token context for one larger-transform DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @return the high-token context for the DC coefficient
    private static int genericDcHighTokenContext(TransformType transformType, int[][] levelBytes) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = nonNullTransformType.oneDimensional()
                ? genericHighMagnitude(nonNullTransformType, levelBytes, 0, 0)
                : levelBytes[0][1] + levelBytes[1][0] + levelBytes[1][1];
        magnitude &= 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one larger-transform coefficient position.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-magnitude accumulator for the supplied coefficient position
    private static int genericHighMagnitude(TransformType transformType, int[][] levelBytes, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = levelBytes[x][y + 1] + levelBytes[x + 1][y];
        if (!nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes[x + 1][y + 1];
        } else {
            magnitude += levelBytes[x][y + 2];
        }
        return magnitude;
    }

    /// Returns the `dav1d` low-context offset table index for one two-dimensional transform size.
    ///
    /// @param transformSize the active transform size
    /// @return `0` for square, `1` for wide, or `2` for tall
    private static int levelContextOffsetIndex(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (nonNullTransformSize.widthPixels() == nonNullTransformSize.heightPixels()) {
            return 0;
        }
        return nonNullTransformSize.widthPixels() > nonNullTransformSize.heightPixels() ? 1 : 2;
    }

    /// Returns the coefficient count modeled by entropy syntax for the supplied transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the modeled coefficient count
    private static int coefficientCount(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return clippedCoefficientWidth(nonNullTransformSize) * clippedCoefficientHeight(nonNullTransformSize);
    }

    /// Returns the natural `TX_4X4` coefficient index addressed by one scan index and transform class.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the natural `TX_4X4` coefficient index addressed by the supplied scan index
    private static int fourByFourCoefficientIndex(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (!nonNullTransformType.oneDimensional()) {
            return FOUR_BY_FOUR_SCAN[scanIndex];
        }
        if (verticalOneDimensional(nonNullTransformType)) {
            int x = scanIndex & 3;
            int y = scanIndex >> 2;
            return (x << 2) | y;
        }
        return scanIndex;
    }

    /// Returns the `levels` grid X coordinate for one `TX_4X4` scan index.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the `levels` grid X coordinate for the supplied scan index
    private static int fourByFourLevelX(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return scanIndex & 3;
        }
        return FOUR_BY_FOUR_SCAN[scanIndex] & 3;
    }

    /// Returns the `levels` grid Y coordinate for one `TX_4X4` scan index.
    ///
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the `levels` grid Y coordinate for the supplied scan index
    private static int fourByFourLevelY(TransformType transformType, int scanIndex) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return scanIndex >> 2;
        }
        return FOUR_BY_FOUR_SCAN[scanIndex] >> 2;
    }

    /// Returns whether a transform type belongs to the vertical one-dimensional class.
    ///
    /// @param transformType the transform type to test
    /// @return whether the supplied transform type belongs to the vertical one-dimensional class
    private static boolean verticalOneDimensional(TransformType transformType) {
        return switch (Objects.requireNonNull(transformType, "transformType")) {
            case V_DCT, V_ADST, V_FLIPADST -> true;
            default -> false;
        };
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

    /// Returns the stored level byte written into the local `levels` grid for one coefficient token.
    ///
    /// @param token the decoded coefficient token before the optional Golomb extension
    /// @return the stored level byte written into the local `levels` grid
    private static int coefficientLevelByte(int token) {
        if (token == 0) {
            return 0;
        }
        if (token < 3) {
            return token * 0x41;
        }
        return token + (3 << 6);
    }

    /// Returns the `br_tok` high-token context for the last non-zero `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `br_tok` high-token context for the supplied coefficient
    private static int fourByFourEndOfBlockHighTokenContext(TransformType transformType, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        if (nonNullTransformType.oneDimensional()) {
            return y != 0 ? 14 : 7;
        }
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the base-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the base-token context for the supplied coefficient
    private static int fourByFourBaseTokenContext(TransformType transformType, int[][] levelBytes, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y);
        int offset;
        if (nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes[x][y + 3] + levelBytes[x][y + 4];
            offset = 26 + (y > 1 ? 10 : y * 5);
        } else {
            magnitude += levelBytes[x][y + 2] + levelBytes[x + 2][y];
            offset = LEVEL_CONTEXT_OFFSETS[0][Math.min(y, 4)][Math.min(x, 4)];
        }
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the high-token context for the supplied coefficient
    private static int fourByFourHighTokenContext(TransformType transformType, int[][] levelBytes, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y) & 0x3F;
        int baseContext = nonNullTransformType.oneDimensional()
                ? (y > 0 ? 14 : 7)
                : (((x | y) > 1) ? 14 : 7);
        return baseContext + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the DC high-token context for one `TX_4X4` residual block.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @return the DC high-token context for the current residual block
    private static int fourByFourDcHighTokenContext(TransformType transformType, int[][] levelBytes) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = fourByFourHighMagnitude(nonNullTransformType, levelBytes, 0, 0) & 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one `TX_4X4` coefficient position.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `dav1d` high-magnitude accumulator for the supplied coefficient position
    private static int fourByFourHighMagnitude(TransformType transformType, int[][] levelBytes, int x, int y) {
        TransformType nonNullTransformType = Objects.requireNonNull(transformType, "transformType");
        int magnitude = levelBytes[x][y + 1] + levelBytes[x + 1][y];
        if (!nonNullTransformType.oneDimensional()) {
            magnitude += levelBytes[x + 1][y + 1];
        } else {
            magnitude += levelBytes[x][y + 2];
        }
        return magnitude;
    }

    /// Creates the stored coefficient-context byte for one non-zero transform unit.
    ///
    /// @param cumulativeLevel the sum of decoded absolute coefficient levels, clamped later to six bits
    /// @param signedDcLevel the signed DC coefficient level, or zero when the transform block has only AC
    /// @return the stored coefficient-context byte for one non-zero transform unit
    private static int createNonZeroCoefficientContextByte(int cumulativeLevel, int signedDcLevel) {
        int magnitude = Math.min(cumulativeLevel, 63);
        if (signedDcLevel < 0) {
            return magnitude | 0x40;
        }
        if (signedDcLevel > 0) {
            return magnitude | 0x80;
        }
        return magnitude;
    }

    /// Returns the entropy-coded coefficient width for the supplied transform size.
    ///
    /// AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
    /// transform sizes. The remaining inverse-transform coefficients stay zero.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient width
    private static int clippedCoefficientWidth(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return 4 << Math.min(nonNullTransformSize.log2Width4(), 3);
    }

    /// Returns the entropy-coded coefficient height for the supplied transform size.
    ///
    /// AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
    /// transform sizes. The remaining inverse-transform coefficients stay zero.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient height
    private static int clippedCoefficientHeight(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return 4 << Math.min(nonNullTransformSize.log2Height4(), 3);
    }

    /// Creates the `dav1d`-compatible two-dimensional scan table for every modeled transform size.
    ///
    /// @return the `dav1d`-compatible two-dimensional scan table for every modeled transform size
    private static int[][] createDefaultScans() {
        TransformSize[] transformSizes = TransformSize.values();
        int[][] scans = new int[transformSizes.length][];
        for (TransformSize transformSize : transformSizes) {
            scans[transformSize.ordinal()] = createDefaultScan(transformSize);
        }
        return scans;
    }

    /// Creates the `dav1d`-compatible two-dimensional scan table for one modeled transform size.
    ///
    /// `dav1d` stores two-dimensional coefficient scan entries in the transform scratch layout
    /// addressed as `coeff[y + x * sh]`. This method emits the same scan order converted into this
    /// project's row-major natural coefficient layout. Rectangular scan generation already visits
    /// row-major coordinates in dav1d order; square scans need an explicit axis swap because the
    /// dav1d scratch stride equals the row-major width and would otherwise look valid while still
    /// transposing horizontal and vertical AC coefficients.
    ///
    /// @param transformSize the modeled transform size whose scan should be created
    /// @return the `dav1d`-compatible two-dimensional scan table for the supplied transform size
    private static int[] createDefaultScan(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int codedWidth = clippedCoefficientWidth(nonNullTransformSize);
        int codedHeight = clippedCoefficientHeight(nonNullTransformSize);
        int outputWidth = nonNullTransformSize.widthPixels();
        int[] scan = new int[codedWidth * codedHeight];
        int nextIndex = 0;
        for (int diagonal = 0; diagonal < codedWidth + codedHeight - 1; diagonal++) {
            int rowStart = Math.max(0, diagonal - (codedWidth - 1));
            int rowEnd = Math.min(codedHeight - 1, diagonal);
            boolean descendingRows;
            if (codedWidth == codedHeight) {
                descendingRows = (diagonal & 1) == 1;
            } else {
                descendingRows = codedWidth > codedHeight;
            }
            if (descendingRows) {
                for (int row = rowEnd; row >= rowStart; row--) {
                    int column = diagonal - row;
                    scan[nextIndex++] = scanIndex(nonNullTransformSize, row, column, outputWidth);
                }
            } else {
                for (int row = rowStart; row <= rowEnd; row++) {
                    int column = diagonal - row;
                    scan[nextIndex++] = scanIndex(nonNullTransformSize, row, column, outputWidth);
                }
            }
        }
        return scan;
    }

    /// Converts one generated scan coordinate into this decoder's row-major coefficient index.
    ///
    /// @param transformSize the modeled transform size
    /// @param row the generated diagonal row coordinate
    /// @param column the generated diagonal column coordinate
    /// @param outputWidth the row-major coefficient row stride
    /// @return the row-major coefficient index
    private static int scanIndex(TransformSize transformSize, int row, int column, int outputWidth) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (nonNullTransformSize.widthPixels() == nonNullTransformSize.heightPixels()) {
            return column * outputWidth + row;
        }
        return row * outputWidth + column;
    }
}
