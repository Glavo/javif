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
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Reader for the first coefficient-side syntax elements inside one tile bitstream.
///
/// The current implementation fully decodes the currently modeled two-dimensional luma residual
/// path and the chroma-U/chroma-V residual unit arrays exposed by `TransformLayout`, including
/// pixel-level clipped chroma footprints along the frame fringe.
/// `TX_4X4` keeps its dedicated context helpers, while larger transform sizes reuse the existing
/// simplified token-context path instead of failing once multiple coefficients are present. That
/// same larger-transform fallback also covers the current single-unit wider-chroma rollout,
/// including unsubsampled `I444` shapes such as `TX_8X8`.
@NotNullByDefault
public final class TileResidualSyntaxReader {
    /// The AV1 coefficient-context byte written for all-zero transform blocks.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

    /// The shared fallback `coeff_base` context used by the current larger-transform path.
    private static final int GENERIC_BASE_TOKEN_CONTEXT = 0;

    /// The shared fallback `br_tok` context used by the current larger-transform path.
    private static final int GENERIC_HIGH_TOKEN_CONTEXT = 7;

    /// The chroma-U plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_U = 0;

    /// The chroma-V plane index used by chroma coefficient-context helpers.
    private static final int CHROMA_PLANE_V = 1;

    /// The `dav1d` natural-order scan for two-dimensional `TX_4X4` transforms.
    private static final int @Unmodifiable [] FOUR_BY_FOUR_SCAN = {
            0, 4, 1, 2,
            5, 8, 12, 9,
            6, 3, 7, 10,
            13, 14, 11, 15
    };

    /// The default two-dimensional scan tables for every modeled transform size.
    private static final int @Unmodifiable [] @Unmodifiable [] DEFAULT_SCANS = createDefaultScans();

    /// The `dav1d` low-token context offsets for square transforms.
    private static final int @Unmodifiable [] @Unmodifiable [] FOUR_BY_FOUR_LEVEL_CONTEXT_OFFSETS = {
            {0, 1, 6, 6, 21},
            {1, 6, 6, 21, 21},
            {6, 6, 21, 21, 21},
            {6, 21, 21, 21, 21},
            {21, 21, 21, 21, 21}
    };

    /// The padded `levels` grid size used by the `TX_4X4` token-context helpers.
    private static final int FOUR_BY_FOUR_LEVEL_GRID_SIZE = 6;

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
                    CHROMA_PLANE_U,
                    nonNullNeighborContext
            );
            chromaVUnits = readChromaResidualUnits(
                    nonNullHeader,
                    nonNullTransformLayout,
                    chromaUnits,
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
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        return readResidualUnit(
                nonNullTransformUnit.position(),
                nonNullTransformUnit.size(),
                visibleLumaWidthPixels(nonNullTransformLayout, nonNullTransformUnit),
                visibleLumaHeightPixels(nonNullTransformLayout, nonNullTransformUnit),
                false,
                nonNullNeighborContext.lumaCoefficientSkipContext(
                        Objects.requireNonNull(header, "header").size(),
                        nonNullTransformUnit
                ),
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
    /// @param plane the chroma plane index, where `0` is U and `1` is V
    /// @param neighborContext the mutable neighbor context that supplies entropy contexts
    /// @return the decoded chroma transform residual units in bitstream order
    private TransformResidualUnit[] readChromaResidualUnits(
            TileBlockHeaderReader.BlockHeader header,
            TransformLayout transformLayout,
            TransformUnit[] transformUnits,
            int plane,
            BlockNeighborContext neighborContext
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        TransformLayout nonNullTransformLayout = Objects.requireNonNull(transformLayout, "transformLayout");
        TransformUnit[] nonNullTransformUnits = Objects.requireNonNull(transformUnits, "transformUnits");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");

        TransformResidualUnit[] residualUnits = new TransformResidualUnit[nonNullTransformUnits.length];
        for (int i = 0; i < nonNullTransformUnits.length; i++) {
            TransformUnit transformUnit = nonNullTransformUnits[i];
            BlockPosition unitPosition = transformUnit.position();
            TransformSize unitSize = transformUnit.size();
            int visibleUnitWidthPixels = visibleChromaUnitWidthPixels(nonNullTransformLayout, transformUnit);
            int visibleUnitHeightPixels = visibleChromaUnitHeightPixels(nonNullTransformLayout, transformUnit);
            TransformResidualUnit residualUnit = nonNullHeader.skip()
                    ? createAllZeroUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels
                    )
                    : readResidualUnit(
                            unitPosition,
                            unitSize,
                            visibleUnitWidthPixels,
                            visibleUnitHeightPixels,
                            true,
                            nonNullNeighborContext.chromaCoefficientSkipContext(
                                    plane,
                                    nonNullHeader.size(),
                                    unitPosition,
                                    unitSize
                            ),
                            nonNullNeighborContext.chromaDcSignContext(
                                    plane,
                                    unitPosition,
                                    unitSize
                            )
                    );
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

    /// Decodes one transform residual unit using the supplied plane-specific contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param coefficientSkipContext the coefficient skip-context for the current unit
    /// @param dcSignContext the DC-sign context for the current unit
    /// @return the decoded transform residual unit
    private TransformResidualUnit readResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            int coefficientSkipContext,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        if (syntaxReader.readCoefficientSkipFlag(nonNullTransformSize, coefficientSkipContext)) {
            return createAllZeroUnit(nonNullPosition, nonNullTransformSize, visibleWidthPixels, visibleHeightPixels);
        }

        int endOfBlockIndex = syntaxReader.readEndOfBlockIndex(nonNullTransformSize, chroma, false);
        if (nonNullTransformSize == TransformSize.TX_4X4) {
            return readFourByFourResidualUnit(
                    nonNullPosition,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    chroma,
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
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded `TX_4X4` residual unit
    private TransformResidualUnit readFourByFourResidualUnit(
            BlockPosition position,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] coefficients = new int[16];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        int[][] levelBytes = new int[FOUR_BY_FOUR_LEVEL_GRID_SIZE][FOUR_BY_FOUR_LEVEL_GRID_SIZE];

        if (endOfBlockIndex > 0) {
            int lastCoefficientIndex = FOUR_BY_FOUR_SCAN[endOfBlockIndex];
            int lastX = lastCoefficientIndex & 3;
            int lastY = lastCoefficientIndex >> 2;
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    TransformSize.TX_4X4,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, TransformSize.TX_4X4)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourEndOfBlockHighTokenContext(lastX, lastY)
                );
            }
            coefficientTokens[endOfBlockIndex] = lastToken;
            levelBytes[lastX][lastY] = coefficientLevelByte(lastToken);

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int coefficientIndex = FOUR_BY_FOUR_SCAN[scanIndex];
                int x = coefficientIndex & 3;
                int y = coefficientIndex >> 2;
                int token = syntaxReader.readBaseToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourBaseTokenContext(levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            TransformSize.TX_4X4,
                            chroma,
                            fourByFourHighTokenContext(levelBytes, x, y)
                    );
                }
                coefficientTokens[scanIndex] = token;
                levelBytes[x][y] = coefficientLevelByte(token);
            }
        }

        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(TransformSize.TX_4X4, chroma, 0)
                : syntaxReader.readBaseToken(TransformSize.TX_4X4, chroma, 0);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(TransformSize.TX_4X4, chroma, fourByFourDcHighTokenContext(levelBytes));
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
            coefficients[FOUR_BY_FOUR_SCAN[scanIndex]] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return new TransformResidualUnit(
                nonNullPosition,
                TransformSize.TX_4X4,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                createNonZeroCoefficientContextByte(cumulativeLevel, signedDcLevel)
        );
    }

    /// Decodes one non-`TX_4X4` residual unit with the shared larger-transform token contexts.
    ///
    /// @param position the transform origin in the current block-position grid
    /// @param transformSize the active transform size
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @param dcSignContext the plane-specific DC-sign context
    /// @return the decoded larger-transform residual unit
    private TransformResidualUnit readGenericResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            boolean chroma,
            int endOfBlockIndex,
            int dcSignContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int[] coefficients = new int[nonNullTransformSize.widthPixels() * nonNullTransformSize.heightPixels()];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        if (endOfBlockIndex > 0) {
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    nonNullTransformSize,
                    chroma,
                    endOfBlockTokenContext(endOfBlockIndex, nonNullTransformSize)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(nonNullTransformSize, chroma, GENERIC_HIGH_TOKEN_CONTEXT);
            }
            coefficientTokens[endOfBlockIndex] = lastToken;

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int token = syntaxReader.readBaseToken(nonNullTransformSize, chroma, GENERIC_BASE_TOKEN_CONTEXT);
                if (token == 3) {
                    token = syntaxReader.readHighToken(nonNullTransformSize, chroma, GENERIC_HIGH_TOKEN_CONTEXT);
                }
                coefficientTokens[scanIndex] = token;
            }
        }

        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(nonNullTransformSize, chroma, GENERIC_BASE_TOKEN_CONTEXT)
                : syntaxReader.readBaseToken(nonNullTransformSize, chroma, GENERIC_BASE_TOKEN_CONTEXT);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(nonNullTransformSize, chroma, GENERIC_BASE_TOKEN_CONTEXT);
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

        int[] scan = DEFAULT_SCANS[nonNullTransformSize.ordinal()];
        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int token = coefficientTokens[scanIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token += syntaxReader.readCoefficientGolomb();
            }
            coefficients[scan[scanIndex]] = negative ? -token : token;
            cumulativeLevel += token;
        }

        return new TransformResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
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
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `br_tok` high-token context for the supplied coefficient
    private static int fourByFourEndOfBlockHighTokenContext(int x, int y) {
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the base-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the base-token context for the supplied coefficient
    private static int fourByFourBaseTokenContext(int[][] levelBytes, int x, int y) {
        int magnitude = fourByFourHighMagnitude(levelBytes, x, y) + levelBytes[x][y + 2] + levelBytes[x + 2][y];
        int offset = FOUR_BY_FOUR_LEVEL_CONTEXT_OFFSETS[Math.min(y, 4)][Math.min(x, 4)];
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the high-token context for the supplied coefficient
    private static int fourByFourHighTokenContext(int[][] levelBytes, int x, int y) {
        int magnitude = fourByFourHighMagnitude(levelBytes, x, y) & 0x3F;
        return (((x | y) > 1) ? 14 : 7) + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the DC high-token context for one `TX_4X4` residual block.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @return the DC high-token context for the current residual block
    private static int fourByFourDcHighTokenContext(int[][] levelBytes) {
        int magnitude = fourByFourHighMagnitude(levelBytes, 0, 0) & 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one `TX_4X4` coefficient position.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient column in `[0, 4)`
    /// @param y the zero-based coefficient row in `[0, 4)`
    /// @return the `dav1d` high-magnitude accumulator for the supplied coefficient position
    private static int fourByFourHighMagnitude(int[][] levelBytes, int x, int y) {
        return levelBytes[x][y + 1] + levelBytes[x + 1][y] + levelBytes[x + 1][y + 1];
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

    /// Creates the default two-dimensional scan table for every modeled transform size.
    ///
    /// @return the default two-dimensional scan table for every modeled transform size
    private static int[][] createDefaultScans() {
        TransformSize[] transformSizes = TransformSize.values();
        int[][] scans = new int[transformSizes.length][];
        for (TransformSize transformSize : transformSizes) {
            scans[transformSize.ordinal()] = createDefaultScan(transformSize);
        }
        return scans;
    }

    /// Creates the default two-dimensional scan table for one modeled transform size.
    ///
    /// The current scan matches the `TX_4X4` `dav1d` table and extends the same diagonal-walk rule
    /// to the larger rectangular transforms already represented by `TransformSize`.
    ///
    /// @param transformSize the modeled transform size whose scan should be created
    /// @return the default two-dimensional scan table for the supplied transform size
    private static int[] createDefaultScan(TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int width = nonNullTransformSize.widthPixels();
        int height = nonNullTransformSize.heightPixels();
        int[] scan = new int[width * height];
        boolean wide = width > height;
        int nextIndex = 0;
        for (int diagonal = 0; diagonal < width + height - 1; diagonal++) {
            int rowStart = Math.max(0, diagonal - (width - 1));
            int rowEnd = Math.min(height - 1, diagonal);
            boolean descendingRows = (((diagonal & 1) == 1) ^ wide);
            if (descendingRows) {
                for (int row = rowEnd; row >= rowStart; row--) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * width + column;
                }
            } else {
                for (int row = rowStart; row <= rowEnd; row++) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * width + column;
                }
            }
        }
        return scan;
    }
}
