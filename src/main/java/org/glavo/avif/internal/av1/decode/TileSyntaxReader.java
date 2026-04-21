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

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Typed reader for the first block-level AV1 syntax elements inside one tile bitstream.
///
/// This reader is intentionally small and currently covers only syntax elements already backed by
/// `CdfContext`: partitioning, skip, skip mode, intra/inter, `intrabc`, Y/UV intra prediction
/// modes, palette presence and size signaling, filter intra, angle deltas, and CFL alpha.
@NotNullByDefault
public final class TileSyntaxReader {
    /// The tile-local decode state that owns the mutable decoder and CDF context.
    private final TileDecodeContext tileContext;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// Creates a typed syntax reader over one tile-local decode context.
    ///
    /// @param tileContext the tile-local decode context that owns the mutable decoder and CDF context
    public TileSyntaxReader(TileDecodeContext tileContext) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.msacDecoder = tileContext.msacDecoder();
        this.cdfContext = tileContext.cdfContext();
    }

    /// Returns the tile-local decode context that owns this syntax reader.
    ///
    /// @return the tile-local decode context that owns this syntax reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes one skip flag using the supplied skip-context index.
    ///
    /// @param context the zero-based skip-context index in `[0, 3)`
    /// @return the decoded skip flag
    public boolean readSkipFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipCdf(context));
    }

    /// Decodes one skip-mode flag using the supplied skip-mode context index.
    ///
    /// @param context the zero-based skip-mode context index in `[0, 3)`
    /// @return the decoded skip-mode flag
    public boolean readSkipModeFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipModeCdf(context));
    }

    /// Decodes one intra/inter decision for inter and switch frames.
    ///
    /// For key and intra-only frames the bitstream does not signal this decision, so this method
    /// returns `true` without consuming entropy-coded bits.
    ///
    /// @param context the zero-based intra-context index in `[0, 4)`
    /// @return `true` when the block is intra-coded, otherwise `false`
    public boolean readIntraBlockFlag(int context) {
        FrameType frameType = tileContext.frameHeader().frameType();
        if (frameType == FrameType.KEY || frameType == FrameType.INTRA) {
            return true;
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntraCdf(context));
    }

    /// Decodes one `use_intrabc` flag when the active frame allows it.
    ///
    /// When the frame does not allow `intrabc`, this method returns `false` without consuming
    /// entropy-coded bits.
    ///
    /// @return the decoded `use_intrabc` flag
    public boolean readUseIntrabcFlag() {
        if (!tileContext.frameHeader().allowIntrabc()) {
            return false;
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntrabcCdf());
    }

    /// Decodes one inter-frame Y intra prediction mode using the supplied size-context index.
    ///
    /// @param sizeContext the zero-based Y-mode size context in `[0, 4)`
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readYMode(int sizeContext) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableYModeCdf(sizeContext), 12);
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one key-frame Y intra prediction mode using the supplied above and left luma modes.
    ///
    /// @param aboveMode the already-decoded luma mode above the current block
    /// @param leftMode the already-decoded luma mode left of the current block
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readKeyFrameYMode(
            LumaIntraPredictionMode aboveMode,
            LumaIntraPredictionMode leftMode
    ) {
        LumaIntraPredictionMode nonNullAboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        LumaIntraPredictionMode nonNullLeftMode = Objects.requireNonNull(leftMode, "leftMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableKeyFrameYModeCdf(nonNullAboveMode.contextIndex(), nonNullLeftMode.contextIndex()),
                12
        );
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one UV intra prediction mode using the supplied Y mode and CFL availability.
    ///
    /// @param yMode the already-decoded luma intra prediction mode for the current block
    /// @param cflAllowed whether CFL is allowed for this block
    /// @return the decoded chroma intra prediction mode
    public UvIntraPredictionMode readUvMode(LumaIntraPredictionMode yMode, boolean cflAllowed) {
        LumaIntraPredictionMode nonNullYMode = Objects.requireNonNull(yMode, "yMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableUvModeCdf(cflAllowed, nonNullYMode.symbolIndex()),
                cflAllowed ? 13 : 12
        );
        return UvIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one `use_filter_intra` flag for the supplied block size.
    ///
    /// @param size the current block size
    /// @return whether filter intra is enabled for the current block
    public boolean readUseFilterIntra(BlockSize size) {
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableUseFilterIntraCdf(nonNullSize.cdfIndex()));
    }

    /// Decodes one temporal segmentation-prediction flag.
    ///
    /// @param context the zero-based segmentation-prediction context index in `[0, 3)`
    /// @return whether the block uses temporal segmentation prediction
    public boolean readSegmentPredictionFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSegmentPredictionCdf(context));
    }

    /// Decodes one segment-id diff symbol for the supplied segment context.
    ///
    /// @param context the zero-based segment-id context index in `[0, 3)`
    /// @return the decoded segment-id diff symbol
    public int readSegmentId(int context) {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutableSegmentIdCdf(context), 7);
    }

    /// Decodes one luma palette-use flag for the supplied size and above/left palette contexts.
    ///
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @param paletteContext the zero-based above/left palette context in `[0, 3)`
    /// @return whether the current block uses a luma palette
    public boolean readUseLumaPalette(int sizeContext, int paletteContext) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableLumaPaletteCdf(sizeContext, paletteContext));
    }

    /// Decodes one palette size for the supplied plane and size context.
    ///
    /// AV1 codes palette sizes as `palette_size_minus_2`, so this method returns the decoded
    /// palette size in `[2, 8]`.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @return the decoded palette size in `[2, 8]`
    public int readPaletteSize(int plane, int sizeContext) {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutablePaletteSizeCdf(plane, sizeContext), 6) + 2;
    }

    /// Decodes one chroma palette-use flag for the supplied luma-palette context.
    ///
    /// @param paletteContext the zero-based chroma palette context in `[0, 2)`
    /// @return whether the current block uses a chroma palette
    public boolean readUseChromaPalette(int paletteContext) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableChromaPaletteCdf(paletteContext));
    }

    /// Decodes the first palette index in one color-map using the AV1 uniform coding rule.
    ///
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @return the decoded first palette index in `[0, paletteSize)`
    public int readPaletteInitialIndex(int paletteSize) {
        return msacDecoder.decodeUniform(paletteSize);
    }

    /// Decodes one palette color-map symbol for the supplied plane, palette size, and context.
    ///
    /// The returned symbol indexes the local palette-order permutation for the current pixel.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param context the zero-based color-map context in `[0, 5)`
    /// @return the decoded palette color-map symbol in `[0, paletteSize)`
    public int readPaletteColorMapSymbol(int plane, int paletteSize, int context) {
        return msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableColorMapCdf(plane, paletteSize - 2, context),
                paletteSize - 1
        );
    }

    /// Decodes one filter-intra mode after `use_filter_intra` signaled `true`.
    ///
    /// @return the decoded filter-intra mode
    public FilterIntraMode readFilterIntraMode() {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableFilterIntraCdf(), 4);
        return FilterIntraMode.fromSymbolIndex(symbol);
    }

    /// Decodes one partition symbol using the supplied partition block level and context index.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return the decoded partition type
    public PartitionType readPartition(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context),
                nonNullBlockLevel.symbolLimit()
        );
        return PartitionType.fromSymbolIndex(symbol);
    }

    /// Decodes one directional angle delta for the supplied luma intra prediction mode.
    ///
    /// @param mode the already-decoded directional luma intra prediction mode
    /// @return the decoded signed angle delta in `[-3, 3]`
    public int readYAngleDelta(LumaIntraPredictionMode mode) {
        LumaIntraPredictionMode nonNullMode = Objects.requireNonNull(mode, "mode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
                6
        );
        return symbol - 3;
    }

    /// Decodes one directional angle delta for the supplied chroma intra prediction mode.
    ///
    /// @param mode the already-decoded directional chroma intra prediction mode
    /// @return the decoded signed angle delta in `[-3, 3]`
    public int readUvAngleDelta(UvIntraPredictionMode mode) {
        UvIntraPredictionMode nonNullMode = Objects.requireNonNull(mode, "mode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
                6
        );
        return symbol - 3;
    }

    /// Decodes the signed CFL alpha pair for one block.
    ///
    /// @return the decoded signed CFL alpha pair for one block
    public CflAlpha readCflAlpha() {
        int signSymbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflSignCdf(), 7) + 1;
        int signU = signSymbol / 3;
        int signV = signSymbol - signU * 3;
        int alphaU = signU == 0 ? 0 : decodeSignedCflAlpha((signU == 2 ? 3 : 0) + signV, signU == 2);
        int alphaV = signV == 0 ? 0 : decodeSignedCflAlpha((signV == 2 ? 3 : 0) + signU, signV == 2);
        return new CflAlpha(alphaU, alphaV);
    }

    /// Decodes one signed CFL alpha value from the supplied sign context.
    ///
    /// @param context the zero-based CFL-alpha sign context
    /// @param positive whether the decoded alpha should be positive
    /// @return the decoded signed CFL alpha value
    private int decodeSignedCflAlpha(int context, boolean positive) {
        int alpha = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflAlphaCdf(context), 15) + 1;
        return positive ? alpha : -alpha;
    }

    /// Decodes a partition decision when only a horizontal split or no split is allowed.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return `true` when the block splits into two square children, otherwise `false`
    public boolean readHorizontalSplitOnly(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int[] cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context);
        return msacDecoder.decodeBoolean(gatherTopPartitionProbability(cdf, nonNullBlockLevel));
    }

    /// Decodes a partition decision when only a vertical split or no split is allowed.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return `true` when the block splits into two square children, otherwise `false`
    public boolean readVerticalSplitOnly(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int[] cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context);
        return msacDecoder.decodeBoolean(gatherLeftPartitionProbability(cdf, nonNullBlockLevel));
    }

    /// Gathers the effective split probability from a partition CDF when only horizontal splitting is legal.
    ///
    /// @param cdf the active partition CDF
    /// @param blockLevel the partition block level that constrains the symbol set
    /// @return the effective split probability in AV1 Q15 form
    private static int gatherTopPartitionProbability(int[] cdf, PartitionBlockLevel blockLevel) {
        int probability = cdf[PartitionType.VERTICAL.symbolIndex() - 1] - cdf[PartitionType.T_TOP_SPLIT.symbolIndex()];
        probability += cdf[PartitionType.T_LEFT_SPLIT.symbolIndex() - 1];
        if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
            probability += cdf[PartitionType.VERTICAL_4.symbolIndex() - 1] - cdf[PartitionType.T_RIGHT_SPLIT.symbolIndex()];
        }
        return probability;
    }

    /// Gathers the effective split probability from a partition CDF when only vertical splitting is legal.
    ///
    /// @param cdf the active partition CDF
    /// @param blockLevel the partition block level that constrains the symbol set
    /// @return the effective split probability in AV1 Q15 form
    private static int gatherLeftPartitionProbability(int[] cdf, PartitionBlockLevel blockLevel) {
        int probability = cdf[PartitionType.HORIZONTAL.symbolIndex() - 1] - cdf[PartitionType.HORIZONTAL.symbolIndex()];
        probability += cdf[PartitionType.SPLIT.symbolIndex() - 1] - cdf[PartitionType.T_LEFT_SPLIT.symbolIndex()];
        if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
            probability += cdf[PartitionType.HORIZONTAL_4.symbolIndex() - 1] - cdf[PartitionType.HORIZONTAL_4.symbolIndex()];
        }
        return probability;
    }

    /// Partition block levels that select one of the five AV1 partition CDF groups.
    @NotNullByDefault
    public enum PartitionBlockLevel {
        /// Partition decisions for 128x128 blocks.
        BLOCK_128X128(0, 7),
        /// Partition decisions for 64x64 blocks.
        BLOCK_64X64(1, 9),
        /// Partition decisions for 32x32 blocks.
        BLOCK_32X32(2, 9),
        /// Partition decisions for 16x16 blocks.
        BLOCK_16X16(3, 9),
        /// Partition decisions for 8x8 blocks.
        BLOCK_8X8(4, 3);

        /// The CDF-table index inside `CdfContext`.
        private final int cdfIndex;

        /// The maximum decoded symbol value for this partition level.
        private final int symbolLimit;

        /// Creates one partition block level entry.
        ///
        /// @param cdfIndex the CDF-table index inside `CdfContext`
        /// @param symbolLimit the maximum decoded symbol value for this partition level
        PartitionBlockLevel(int cdfIndex, int symbolLimit) {
            this.cdfIndex = cdfIndex;
            this.symbolLimit = symbolLimit;
        }

        /// Returns the CDF-table index inside `CdfContext`.
        ///
        /// @return the CDF-table index inside `CdfContext`
        public int cdfIndex() {
            return cdfIndex;
        }

        /// Returns the maximum decoded symbol value for this partition level.
        ///
        /// @return the maximum decoded symbol value for this partition level
        public int symbolLimit() {
            return symbolLimit;
        }
    }

    /// The signed CFL alpha pair decoded for one block.
    @NotNullByDefault
    public static final class CflAlpha {
        /// The signed CFL alpha for chroma U.
        private final int alphaU;

        /// The signed CFL alpha for chroma V.
        private final int alphaV;

        /// Creates one signed CFL alpha pair.
        ///
        /// @param alphaU the signed CFL alpha for chroma U
        /// @param alphaV the signed CFL alpha for chroma V
        public CflAlpha(int alphaU, int alphaV) {
            this.alphaU = alphaU;
            this.alphaV = alphaV;
        }

        /// Returns the signed CFL alpha for chroma U.
        ///
        /// @return the signed CFL alpha for chroma U
        public int alphaU() {
            return alphaU;
        }

        /// Returns the signed CFL alpha for chroma V.
        ///
        /// @return the signed CFL alpha for chroma V
        public int alphaV() {
            return alphaV;
        }
    }
}
