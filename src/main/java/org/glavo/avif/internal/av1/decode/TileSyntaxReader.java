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
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Typed reader for the first block-level AV1 syntax elements inside one tile bitstream.
///
/// This reader is intentionally small and currently covers only syntax elements already backed by
/// `CdfContext`: partitioning, skip, skip mode, intra/inter, compound and single-reference
/// selection, inter prediction-mode symbols, `intrabc`, Y/UV intra prediction modes, palette
/// presence and size signaling, motion-vector residuals, filter intra, angle deltas, and CFL alpha.
@NotNullByDefault
public final class TileSyntaxReader {
    /// The `mv_joint` symbol that leaves both motion-vector components unchanged.
    private static final int MOTION_VECTOR_JOINT_NONE = 0;

    /// The `mv_joint` bit that signals a horizontal motion-vector residual.
    private static final int MOTION_VECTOR_JOINT_HORIZONTAL = 1;

    /// The `mv_joint` bit that signals a vertical motion-vector residual.
    private static final int MOTION_VECTOR_JOINT_VERTICAL = 2;

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

    /// Decodes one compound-reference decision for inter and switch frames.
    ///
    /// @param context the zero-based compound-reference context index in `[0, 5)`
    /// @return whether the block uses compound references
    public boolean readCompoundReferenceFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundReferenceCdf(context));
    }

    /// Decodes one compound-reference direction decision.
    ///
    /// @param context the zero-based compound-direction context index in `[0, 5)`
    /// @return whether the compound block uses bi-directional references
    public boolean readCompoundDirectionFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundDirectionCdf(context));
    }

    /// Decodes one single-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based single-reference table index in `[0, 6)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded single-reference selection flag
    public boolean readSingleReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound forward-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound forward-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound forward-reference selection flag
    public boolean readCompoundForwardReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundForwardReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound backward-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound backward-reference table index in `[0, 2)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound backward-reference selection flag
    public boolean readCompoundBackwardReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundBackwardReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound unidirectional-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound unidirectional-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound unidirectional-reference selection flag
    public boolean readCompoundUnidirectionalReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundUnidirectionalReferenceCdf(tableIndex, context));
    }

    /// Decodes one single-reference inter-mode `newmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `newmv` context index in `[0, 6)`
    /// @return whether the block follows the global/ref-mv branch instead of the new-mv branch
    public boolean readSingleInterNewMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterNewMvCdf(context));
    }

    /// Decodes one single-reference inter-mode `globalmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `globalmv` context index in `[0, 2)`
    /// @return whether the block uses nearest/ref-mv instead of global motion
    public boolean readSingleInterGlobalMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterGlobalMvCdf(context));
    }

    /// Decodes one single-reference inter-mode `refmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `refmv` context index in `[0, 6)`
    /// @return whether the block uses near motion vectors instead of the nearest candidate
    public boolean readSingleInterReferenceMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterReferenceMvCdf(context));
    }

    /// Decodes one dynamic-reference-list selection bit from the supplied context index.
    ///
    /// @param context the zero-based dynamic-reference-list context index in `[0, 3)`
    /// @return the decoded dynamic-reference-list selection bit
    public boolean readDrlBit(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(context));
    }

    /// Decodes one compound inter prediction mode from the supplied context index.
    ///
    /// @param context the zero-based compound inter-mode context index in `[0, 8)`
    /// @return the decoded compound inter prediction mode
    public CompoundInterPredictionMode readCompoundInterMode(int context) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCompoundInterModeCdf(context), 7);
        return CompoundInterPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one `NEWMV` residual around the supplied predictor.
    ///
    /// The active frame header supplies the motion-vector precision mode. The returned vector is
    /// the fully decoded motion vector in quarter-pel units.
    ///
    /// @param referenceMotionVector the predictor that the residual is added to
    /// @return the fully decoded motion vector in quarter-pel units
    public MotionVector readMotionVectorResidual(MotionVector referenceMotionVector) {
        MotionVector nonNullReferenceMotionVector = Objects.requireNonNull(referenceMotionVector, "referenceMotionVector");
        FrameType frameType = tileContext.frameHeader().frameType();
        if (frameType != FrameType.INTER && frameType != FrameType.SWITCH) {
            throw new IllegalStateException("Motion-vector residuals are only available in inter and switch frames");
        }

        int motionVectorPrecision = (tileContext.frameHeader().allowHighPrecisionMotionVectors() ? 1 : 0)
                - (tileContext.frameHeader().forceIntegerMotionVectors() ? 1 : 0);
        int motionVectorJoint = msacDecoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorJointCdf(), 3);
        if (motionVectorJoint == MOTION_VECTOR_JOINT_NONE) {
            return nonNullReferenceMotionVector;
        }

        int rowQuarterPel = nonNullReferenceMotionVector.rowQuarterPel();
        int columnQuarterPel = nonNullReferenceMotionVector.columnQuarterPel();
        if ((motionVectorJoint & MOTION_VECTOR_JOINT_VERTICAL) != 0) {
            rowQuarterPel += readMotionVectorComponentDiff(0, motionVectorPrecision);
        }
        if ((motionVectorJoint & MOTION_VECTOR_JOINT_HORIZONTAL) != 0) {
            columnQuarterPel += readMotionVectorComponentDiff(1, motionVectorPrecision);
        }
        return new MotionVector(rowQuarterPel, columnQuarterPel);
    }

    /// Decodes one single-reference inter prediction mode from the supplied contexts and already-forced segment flags.
    ///
    /// This method covers only the mode symbol itself. Motion-vector candidate lookup and residual
    /// decoding remain the responsibility of higher decode layers.
    ///
    /// @param newMvContext the zero-based `newmv` context index in `[0, 6)`
    /// @param globalMvContext the zero-based `globalmv` context index in `[0, 2)`
    /// @param referenceMvContext the zero-based `refmv` context index in `[0, 6)`
    /// @param segmentGlobalMotion whether segment features force `GLOBALMV`
    /// @param segmentSkip whether segment features force the global/nearest-ref branch
    /// @return the decoded single-reference inter prediction mode
    public SingleInterPredictionMode readSingleInterMode(
            int newMvContext,
            int globalMvContext,
            int referenceMvContext,
            boolean segmentGlobalMotion,
            boolean segmentSkip
    ) {
        if (segmentGlobalMotion || segmentSkip || readSingleInterNewMvFlag(newMvContext)) {
            if (segmentGlobalMotion || segmentSkip || !readSingleInterGlobalMvFlag(globalMvContext)) {
                return SingleInterPredictionMode.GLOBALMV;
            }
            return readSingleInterReferenceMvFlag(referenceMvContext)
                    ? SingleInterPredictionMode.NEARMV
                    : SingleInterPredictionMode.NEARESTMV;
        }
        return SingleInterPredictionMode.NEWMV;
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

    /// Decodes one signed motion-vector component residual.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @param motionVectorPrecision the active motion-vector precision mode: `-1` forces integer,
    ///                              `0` disables high precision, and `1` allows high precision
    /// @return the signed motion-vector component residual in quarter-pel units
    private int readMotionVectorComponentDiff(int component, int motionVectorPrecision) {
        boolean negative = msacDecoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorSignCdf(component));
        int motionVectorClass = msacDecoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorClassCdf(component), 10);
        int integerMagnitude;
        int fractionalPart = 3;
        int highPrecisionBit = 1;

        if (motionVectorClass == 0) {
            integerMagnitude = msacDecoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClass0Cdf(component)) ? 1 : 0;
            if (motionVectorPrecision >= 0) {
                fractionalPart = msacDecoder.decodeSymbolAdapt(
                        cdfContext.mutableMotionVectorClass0FpCdf(component, integerMagnitude),
                        3
                );
                if (motionVectorPrecision > 0) {
                    highPrecisionBit = msacDecoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClass0HpCdf(component)) ? 1 : 0;
                }
            }
        } else {
            integerMagnitude = 1 << motionVectorClass;
            for (int bitIndex = 0; bitIndex < motionVectorClass; bitIndex++) {
                if (msacDecoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClassNCdf(component, bitIndex))) {
                    integerMagnitude |= 1 << bitIndex;
                }
            }
            if (motionVectorPrecision >= 0) {
                fractionalPart = msacDecoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorClassNFpCdf(component), 3);
                if (motionVectorPrecision > 0) {
                    highPrecisionBit = msacDecoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClassNHpCdf(component)) ? 1 : 0;
                }
            }
        }

        int diff = ((integerMagnitude << 3) | (fractionalPart << 1) | highPrecisionBit) + 1;
        return negative ? -diff : diff;
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
