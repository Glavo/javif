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
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/// Reader for one leaf block header inside a tile partition tree.
///
/// This implementation intentionally covers only the syntax elements already backed by
/// `TileSyntaxReader`, including skip mode, CDEF and delta-q/delta-lf side syntax, provisional
/// inter prediction modes, palette size signaling, `intrabc`, directional angle deltas, and
/// block-size-aware CFL gating.
@NotNullByDefault
public final class TileBlockHeaderReader {
    /// Sentinel used when a block does not carry an inter reference frame.
    private static final int NO_REFERENCE_FRAME = -1;

    /// The AV1 segment reference-frame code that forces intra block syntax.
    private static final int SEGMENT_REFERENCE_INTRA_FRAME = 0;

    /// The AV1 LAST_FRAME index in internal LAST..ALTREF order.
    private static final int LAST_FRAME = 0;

    /// The AV1 LAST2_FRAME index in internal LAST..ALTREF order.
    private static final int LAST2_FRAME = 1;

    /// The AV1 LAST3_FRAME index in internal LAST..ALTREF order.
    private static final int LAST3_FRAME = 2;

    /// The AV1 GOLDEN_FRAME index in internal LAST..ALTREF order.
    private static final int GOLDEN_FRAME = 3;

    /// The AV1 BWDREF_FRAME index in internal LAST..ALTREF order.
    private static final int BWDREF_FRAME = 4;

    /// The AV1 ALTREF2_FRAME index in internal LAST..ALTREF order.
    private static final int ALTREF2_FRAME = 5;

    /// The AV1 ALTREF_FRAME index in internal LAST..ALTREF order.
    private static final int ALTREF_FRAME = 6;

    /// The tile-local decode state that owns the active frame and sequence headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used to consume entropy-coded syntax elements.
    private final TileSyntaxReader syntaxReader;

    /// Creates one leaf block header reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active frame and sequence headers
    public TileBlockHeaderReader(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.tileContext = nonNullTileContext;
        this.syntaxReader = new TileSyntaxReader(nonNullTileContext);
    }

    /// Returns the tile-local decode state that owns this block header reader.
    ///
    /// @return the tile-local decode state that owns this block header reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes one leaf block header and updates the supplied neighbor context.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to decode
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @return the decoded leaf block header
    public BlockHeader read(BlockPosition position, BlockSize size, BlockNeighborContext neighborContext) {
        return read(position, size, neighborContext, true);
    }

    /// Decodes one leaf block header and optionally updates the supplied neighbor context.
    ///
    /// Callers that need to decode follow-up syntax using the pre-block neighbor state can disable
    /// the automatic update and commit the header after later syntax stages finish.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to decode
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param updateNeighborContext whether the neighbor context should be updated before returning
    /// @return the decoded leaf block header
    public BlockHeader read(
            BlockPosition position,
            BlockSize size,
            BlockNeighborContext neighborContext,
            boolean updateNeighborContext
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        TileDecodeContext.BlockSyntaxState blockSyntaxState = tileContext.blockSyntaxState();
        blockSyntaxState.enterSuperblock(nonNullPosition, tileContext.superblockSize());
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        FrameType frameType = tileContext.frameHeader().frameType();
        boolean hasChroma = hasChroma(nonNullPosition, nonNullSize);
        int segmentId = 0;
        boolean segmentPredicted = false;
        if (segmentation.enabled() && segmentation.updateMap() && segmentation.preskip()) {
            SegmentReadResult segmentReadResult = readSegmentIdBeforeSkip(nonNullPosition, nonNullNeighborContext);
            segmentId = segmentReadResult.segmentId();
            segmentPredicted = segmentReadResult.segmentPredicted();
        }

        @Nullable FrameHeader.SegmentData segmentDataBeforeSkip =
                segmentation.enabled() && segmentation.updateMap() && !segmentation.preskip()
                        ? null
                        : segmentation.segment(segmentId);
        boolean skipMode = false;
        if ((frameType == FrameType.INTER || frameType == FrameType.SWITCH)
                && canDecodeSkipMode(nonNullSize, segmentDataBeforeSkip)) {
            skipMode = syntaxReader.readSkipModeFlag(nonNullNeighborContext.skipModeContext(nonNullPosition));
        }
        boolean skip = skipMode || (segmentDataBeforeSkip != null && segmentDataBeforeSkip.skip());
        if (!skip) {
            skip = syntaxReader.readSkipFlag(nonNullNeighborContext.skipContext(nonNullPosition));
        }
        FrameHeader.SegmentData segmentData;
        if (segmentation.enabled() && segmentation.updateMap() && !segmentation.preskip()) {
            SegmentReadResult segmentReadResult = readSegmentIdAfterSkip(nonNullPosition, nonNullNeighborContext, skip);
            segmentId = segmentReadResult.segmentId();
            segmentPredicted = segmentReadResult.segmentPredicted();
            segmentData = segmentation.segment(segmentId);
        } else {
            if (segmentDataBeforeSkip == null) {
                throw new IllegalStateException("Segment data must be known before skip when postskip decoding is disabled");
            }
            segmentData = segmentDataBeforeSkip;
        }

        int cdefIndex = resolveCdefIndex(nonNullPosition, nonNullSize, skip, blockSyntaxState);
        int qIndex = applyDeltaSyntax(nonNullPosition, nonNullSize, skip, hasChroma, blockSyntaxState);
        int[] deltaLfValues = blockSyntaxState.currentDeltaLfValues();

        boolean useIntrabc = false;
        boolean intra;
        if ((frameType == FrameType.INTER || frameType == FrameType.SWITCH) && skip) {
            intra = false;
        } else if (frameType == FrameType.INTER || frameType == FrameType.SWITCH) {
            int segmentReferenceFrame = segmentData.referenceFrame();
            if (segmentReferenceFrame >= 0) {
                intra = segmentReferenceFrame == SEGMENT_REFERENCE_INTRA_FRAME;
            } else {
                intra = syntaxReader.readIntraBlockFlag(nonNullNeighborContext.intraContext(nonNullPosition));
            }
        } else if (tileContext.frameHeader().allowIntrabc()) {
            useIntrabc = syntaxReader.readUseIntrabcFlag();
            intra = !useIntrabc;
        } else {
            intra = true;
        }

        boolean compoundReference = false;
        int referenceFrame0 = NO_REFERENCE_FRAME;
        int referenceFrame1 = NO_REFERENCE_FRAME;
        @Nullable SingleInterPredictionMode singleInterMode = null;
        @Nullable CompoundInterPredictionMode compoundInterMode = null;
        int drlIndex = -1;
        @Nullable InterMotionVector motionVector0 = null;
        @Nullable InterMotionVector motionVector1 = null;
        if (!intra && !useIntrabc) {
            InterReferenceSelection selection = readInterReferenceSelection(
                    nonNullPosition,
                    nonNullSize,
                    nonNullNeighborContext,
                    segmentData,
                    skipMode
            );
            compoundReference = selection.compoundReference();
            referenceFrame0 = selection.referenceFrame0();
            referenceFrame1 = selection.referenceFrame1();
            if (skipMode) {
                compoundInterMode = CompoundInterPredictionMode.NEARESTMV_NEARESTMV;
                drlIndex = 0;
                BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                        nonNullNeighborContext.provisionalInterModeContext(
                                nonNullPosition,
                                nonNullSize,
                                compoundReference,
                                referenceFrame0,
                                referenceFrame1
                        );
                BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                        selectReferenceMotionVectorCandidate(provisionalContext, 0);
                motionVector0 = resolveCompoundMotionVector0(compoundInterMode, candidate);
                motionVector1 = resolveCompoundMotionVector1(compoundInterMode, candidate);
            } else {
                InterModeSelection interModeSelection = readInterModeSelection(
                        nonNullPosition,
                        nonNullSize,
                        nonNullNeighborContext,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        segmentData
                );
                singleInterMode = interModeSelection.singleInterMode();
                compoundInterMode = interModeSelection.compoundInterMode();
                drlIndex = interModeSelection.drlIndex();
                motionVector0 = interModeSelection.motionVector0();
                motionVector1 = interModeSelection.motionVector1();
            }
        }

        @Nullable LumaIntraPredictionMode yMode = null;
        @Nullable UvIntraPredictionMode uvMode = null;
        int yPaletteSize = 0;
        int uvPaletteSize = 0;
        int[] yPaletteColors = new int[0];
        int[] uPaletteColors = new int[0];
        int[] vPaletteColors = new int[0];
        byte[] yPaletteIndices = new byte[0];
        byte[] uvPaletteIndices = new byte[0];
        @Nullable FilterIntraMode filterIntraMode = null;
        int yAngle = 0;
        int uvAngle = 0;
        int cflAlphaU = 0;
        int cflAlphaV = 0;
        if (useIntrabc) {
            yMode = LumaIntraPredictionMode.DC;
            if (hasChroma) {
                uvMode = UvIntraPredictionMode.DC;
            }
        } else if (intra) {
            if (frameType == FrameType.INTER || frameType == FrameType.SWITCH) {
                yMode = syntaxReader.readYMode(nonNullSize.yModeSizeContext());
            } else {
                yMode = syntaxReader.readKeyFrameYMode(
                        nonNullNeighborContext.aboveMode(nonNullPosition.x4()),
                        nonNullNeighborContext.leftMode(nonNullPosition.y4())
                );
            }
            if (supportsAngleDelta(nonNullSize) && yMode.isDirectional()) {
                yAngle = syntaxReader.readYAngleDelta(yMode);
            }
            if (hasChroma) {
                boolean cflAllowed = isCflAllowed(nonNullSize, segmentId);
                uvMode = syntaxReader.readUvMode(yMode, cflAllowed);
                if (uvMode == UvIntraPredictionMode.CFL) {
                    TileSyntaxReader.CflAlpha cflAlpha = syntaxReader.readCflAlpha();
                    cflAlphaU = cflAlpha.alphaU();
                    cflAlphaV = cflAlpha.alphaV();
                } else if (supportsAngleDelta(nonNullSize) && uvMode.isDirectional()) {
                    uvAngle = syntaxReader.readUvAngleDelta(uvMode);
                }
            }
            if (allowsPalette(nonNullSize)) {
                int paletteSizeContext = nonNullSize.paletteSizeContext();
                if (yMode == LumaIntraPredictionMode.DC) {
                    int paletteContext = (nonNullNeighborContext.abovePaletteSize(nonNullPosition.x4()) > 0 ? 1 : 0)
                            + (nonNullNeighborContext.leftPaletteSize(nonNullPosition.y4()) > 0 ? 1 : 0);
                    if (syntaxReader.readUseLumaPalette(paletteSizeContext, paletteContext)) {
                        yPaletteSize = syntaxReader.readPaletteSize(0, paletteSizeContext);
                        yPaletteColors = readPalettePlane(0, yPaletteSize, nonNullPosition, nonNullNeighborContext);
                    }
                }
                if (hasChroma && uvMode == UvIntraPredictionMode.DC
                        && syntaxReader.readUseChromaPalette(yPaletteSize > 0 ? 1 : 0)) {
                    uvPaletteSize = syntaxReader.readPaletteSize(1, paletteSizeContext);
                    uPaletteColors = readPalettePlane(1, uvPaletteSize, nonNullPosition, nonNullNeighborContext);
                    vPaletteColors = readChromaVPalette(uvPaletteSize);
                }
            }
            if (yPaletteSize == 0 && allowsFilterIntra(nonNullSize, yMode) && syntaxReader.readUseFilterIntra(nonNullSize)) {
                filterIntraMode = syntaxReader.readFilterIntraMode();
            }
        }

        if (yPaletteSize > 0) {
            yPaletteIndices = readPaletteIndices(0, yPaletteSize, nonNullPosition, nonNullSize);
        }
        if (uvPaletteSize > 0) {
            uvPaletteIndices = readPaletteIndices(1, uvPaletteSize, nonNullPosition, nonNullSize);
        }

        BlockHeader header = new BlockHeader(
                nonNullPosition,
                nonNullSize,
                hasChroma,
                skip,
                skipMode,
                intra,
                useIntrabc,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                singleInterMode,
                compoundInterMode,
                drlIndex,
                motionVector0,
                motionVector1,
                segmentPredicted,
                segmentId,
                cdefIndex,
                qIndex,
                deltaLfValues,
                yMode,
                uvMode,
                yPaletteSize,
                uvPaletteSize,
                yPaletteColors,
                uPaletteColors,
                vPaletteColors,
                yPaletteIndices,
                uvPaletteIndices,
                filterIntraMode,
                yAngle,
                uvAngle,
                cflAlphaU,
                cflAlphaV
        );
        if (updateNeighborContext) {
            nonNullNeighborContext.updateFromBlockHeader(header);
            nonNullNeighborContext.updateDefaultTransformContext(nonNullPosition, nonNullSize);
        }
        return header;
    }

    /// Resolves the effective CDEF index for one block, decoding and caching it when needed.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the current block size
    /// @param skip whether the current block is skipped
    /// @param blockSyntaxState the mutable tile-local block syntax state
    /// @return the effective CDEF index for the current block, or `-1` when not yet known
    private int resolveCdefIndex(
            BlockPosition position,
            BlockSize size,
            boolean skip,
            TileDecodeContext.BlockSyntaxState blockSyntaxState
    ) {
        FrameHeader.CdefInfo cdef = tileContext.frameHeader().cdef();
        int quadrantIndex = cdefQuadrantIndex(position);
        int cachedIndex = blockSyntaxState.cdefIndex(quadrantIndex);
        if (skip) {
            return cachedIndex;
        }
        if (cachedIndex >= 0) {
            return cachedIndex;
        }

        int cdefIndex = cdef.bits() == 0 ? 0 : syntaxReader.readUnsignedBits(cdef.bits());
        fillCdefIndices(blockSyntaxState, quadrantIndex, size, cdefIndex);
        return cdefIndex;
    }

    /// Applies superblock-level delta-q and delta-lf syntax when the current block starts a superblock.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the current block size
    /// @param skip whether the current block is skipped
    /// @param hasChroma whether the current block has chroma samples in the active frame layout
    /// @param blockSyntaxState the mutable tile-local block syntax state
    /// @return the current luma AC quantizer index after any delta syntax was applied
    private int applyDeltaSyntax(
            BlockPosition position,
            BlockSize size,
            boolean skip,
            boolean hasChroma,
            TileDecodeContext.BlockSyntaxState blockSyntaxState
    ) {
        if (!isSuperblockOrigin(position)) {
            return blockSyntaxState.currentQIndex();
        }

        FrameHeader.DeltaInfo delta = tileContext.frameHeader().delta();
        boolean haveDeltaQ = delta.deltaQPresent()
                && (size.widthPixels() != tileContext.superblockSize()
                || size.heightPixels() != tileContext.superblockSize()
                || !skip);
        if (!haveDeltaQ) {
            return blockSyntaxState.currentQIndex();
        }

        int currentQIndex = clip(blockSyntaxState.currentQIndex() + syntaxReader.readDeltaQValue(delta.deltaQResLog2()), 1, 255);
        blockSyntaxState.setCurrentQIndex(currentQIndex);
        if (delta.deltaLfPresent()) {
            int deltaLfCount = delta.deltaLfMulti()
                    ? (hasChroma ? 4 : 2)
                    : 1;
            int contextOffset = delta.deltaLfMulti() ? 1 : 0;
            for (int i = 0; i < deltaLfCount; i++) {
                int slot = contextOffset == 0 ? 0 : i;
                int updatedValue = clip(
                        blockSyntaxState.currentDeltaLfValue(slot)
                                + syntaxReader.readDeltaLfValue(i + contextOffset, delta.deltaLfResLog2()),
                        -63,
                        63
                );
                blockSyntaxState.setCurrentDeltaLfValue(slot, updatedValue);
            }
        }
        return currentQIndex;
    }

    /// Returns whether the supplied block origin starts a new superblock.
    ///
    /// @param position the local tile-relative block origin
    /// @return whether the supplied block origin starts a new superblock
    private boolean isSuperblockOrigin(BlockPosition position) {
        int superblockSize4 = tileContext.superblockSize() >> 2;
        return position.x4() % superblockSize4 == 0 && position.y4() % superblockSize4 == 0;
    }

    /// Returns the current CDEF quadrant index inside the active superblock.
    ///
    /// @param position the local tile-relative block origin
    /// @return the current CDEF quadrant index inside the active superblock
    private int cdefQuadrantIndex(BlockPosition position) {
        if (tileContext.superblockSize() == 64) {
            return 0;
        }
        return ((position.x4() & 16) >> 4) + ((position.y4() & 16) >> 3);
    }

    /// Fills the cached CDEF indices covered by the supplied block.
    ///
    /// @param blockSyntaxState the mutable tile-local block syntax state
    /// @param quadrantIndex the zero-based starting CDEF quadrant index
    /// @param size the current block size
    /// @param cdefIndex the decoded CDEF index
    private void fillCdefIndices(
            TileDecodeContext.BlockSyntaxState blockSyntaxState,
            int quadrantIndex,
            BlockSize size,
            int cdefIndex
    ) {
        blockSyntaxState.setCdefIndex(quadrantIndex, cdefIndex);
        if (tileContext.superblockSize() == 64) {
            return;
        }
        if (size.width4() > 16 && quadrantIndex + 1 < 4) {
            blockSyntaxState.setCdefIndex(quadrantIndex + 1, cdefIndex);
        }
        if (size.height4() > 16 && quadrantIndex + 2 < 4) {
            blockSyntaxState.setCdefIndex(quadrantIndex + 2, cdefIndex);
        }
        if (size.width4() == 32 && size.height4() == 32) {
            blockSyntaxState.setCdefIndex(3, cdefIndex);
        }
    }

    /// Clips one integer into the supplied inclusive range.
    ///
    /// @param value the value to clip
    /// @param min the inclusive lower bound
    /// @param max the inclusive upper bound
    /// @return the clipped value
    private static int clip(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /// Returns whether the supplied block has chroma samples in the active frame layout.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to test
    /// @return whether the supplied block has chroma samples in the active frame layout
    private boolean hasChroma(BlockPosition position, BlockSize size) {
        PixelFormat pixelFormat = tileContext.sequenceHeader().colorConfig().pixelFormat();
        if (pixelFormat == PixelFormat.I400) {
            return false;
        }
        int subsamplingX = tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
        int subsamplingY = tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
        return (size.width4() > subsamplingX || (position.x4() & 1) != 0)
                && (size.height4() > subsamplingY || (position.y4() & 1) != 0);
    }

    /// Returns whether CFL syntax is available for the supplied block size in the active frame.
    ///
    /// In lossless mode AV1 only allows CFL when the corresponding chroma block is 4x4. For
    /// non-lossless blocks CFL is limited to luma partitions up to 32x32.
    ///
    /// @param size the block size to test
    /// @param segmentId the decoded segment identifier for the current block
    /// @return whether CFL syntax is available for the supplied block size in the active frame
    private boolean isCflAllowed(BlockSize size, int segmentId) {
        if (!tileContext.frameHeader().segmentation().lossless(segmentId)) {
            return size.widthPixels() <= 32 && size.heightPixels() <= 32;
        }

        int subsamplingX = tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
        int subsamplingY = tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
        int chromaWidth4 = Math.max(1, size.width4() >> subsamplingX);
        int chromaHeight4 = Math.max(1, size.height4() >> subsamplingY);
        return chromaWidth4 == 1 && chromaHeight4 == 1;
    }

    /// Returns whether compound-reference syntax is available for the supplied block and segment state.
    ///
    /// @param size the block size to test
    /// @param segmentData the final segment data for the current block
    /// @return whether compound-reference syntax is available for the supplied block and segment state
    private boolean canDecodeCompoundReference(BlockSize size, FrameHeader.SegmentData segmentData) {
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        FrameHeader.SegmentData nonNullSegmentData = Objects.requireNonNull(segmentData, "segmentData");
        return tileContext.frameHeader().switchableCompoundReferences()
                && Math.min(nonNullSize.width4(), nonNullSize.height4()) > 1
                && nonNullSegmentData.referenceFrame() < 0
                && !nonNullSegmentData.globalMotion()
                && !nonNullSegmentData.skip();
    }

    /// Returns whether skip-mode syntax is available for the supplied block and segment state.
    ///
    /// Postskip segmentation does not know the final segment features yet, so a `null` segment
    /// state means the skip-mode gate should rely only on frame-level constraints.
    ///
    /// @param size the block size to test
    /// @param segmentData the already-known segment data, or `null` when postskip segmentation has
    ///                    not decoded the final segment id yet
    /// @return whether skip-mode syntax is available for the supplied block and segment state
    private boolean canDecodeSkipMode(BlockSize size, @Nullable FrameHeader.SegmentData segmentData) {
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        return tileContext.frameHeader().skipModeEnabled()
                && Math.min(nonNullSize.width4(), nonNullSize.height4()) > 1
                && (segmentData == null
                || (!segmentData.globalMotion() && segmentData.referenceFrame() < 0 && !segmentData.skip()));
    }

    /// Decodes the inter reference selection for one non-intra block.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param segmentData the final segment data for the current block
    /// @param skipMode whether skip mode is active for the current block
    /// @return the decoded inter reference selection for one non-intra block
    private InterReferenceSelection readInterReferenceSelection(
            BlockPosition position,
            BlockSize size,
            BlockNeighborContext neighborContext,
            FrameHeader.SegmentData segmentData,
            boolean skipMode
    ) {
        if (skipMode) {
            return new InterReferenceSelection(
                    true,
                    tileContext.frameHeader().skipModeReferenceIndex(0),
                    tileContext.frameHeader().skipModeReferenceIndex(1)
            );
        }

        boolean compoundReference = canDecodeCompoundReference(size, segmentData)
                && syntaxReader.readCompoundReferenceFlag(neighborContext.compoundReferenceContext(position));
        if (compoundReference) {
            return readCompoundReferenceSelection(position, neighborContext);
        }
        return new InterReferenceSelection(false, readSingleReference(position, neighborContext, segmentData), NO_REFERENCE_FRAME);
    }

    /// Decodes the inter prediction mode and provisional dynamic-reference-list index for one block.
    ///
    /// This stage intentionally stops at syntax parsing. Full motion-vector candidate lookup and
    /// residual decoding are deferred to a later refmvs-backed implementation. The returned motion
    /// vectors therefore mirror the same bounded provisional candidate stack used for `inter_mode`
    /// and `drl` decoding instead of a full AV1 `refmvs` walk.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
    /// @param segmentData the final segment data for the current block
    /// @return the decoded inter prediction mode, dynamic-reference-list index, and motion-vector state
    private InterModeSelection readInterModeSelection(
            BlockPosition position,
            BlockSize size,
            BlockNeighborContext neighborContext,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            FrameHeader.SegmentData segmentData
    ) {
        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                neighborContext.provisionalInterModeContext(position, size, compoundReference, referenceFrame0, referenceFrame1);
        if (compoundReference) {
            CompoundInterPredictionMode compoundInterMode =
                    syntaxReader.readCompoundInterMode(provisionalContext.compoundInterModeContext());
            int drlIndex = 0;
            if (compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                if (provisionalContext.candidateCount() > 1) {
                    drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(0)) ? 1 : 0;
                    if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
                        drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(1)) ? 1 : 0;
                    }
                }
            } else if (compoundInterMode.usesNearMotionVector()) {
                drlIndex = 1;
                if (provisionalContext.candidateCount() > 2) {
                    drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(1)) ? 1 : 0;
                    if (drlIndex == 2 && provisionalContext.candidateCount() > 3) {
                        drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(2)) ? 1 : 0;
                    }
                }
            }
            BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                    selectReferenceMotionVectorCandidate(provisionalContext, motionVectorCandidateIndex(compoundInterMode, drlIndex));
            InterMotionVector motionVector0 = resolveCompoundMotionVector0(compoundInterMode, candidate);
            InterMotionVector motionVector1 = resolveCompoundMotionVector1(compoundInterMode, candidate);
            if (compoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV
                    || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEARMV
                    || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                motionVector0 = decodeNewMotionVectorResidual(motionVector0);
            }
            if (compoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV
                    || compoundInterMode == CompoundInterPredictionMode.NEARMV_NEWMV
                    || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                motionVector1 = decodeNewMotionVectorResidual(motionVector1);
            }
            return new InterModeSelection(
                    null,
                    compoundInterMode,
                    drlIndex,
                    motionVector0,
                    motionVector1
            );
        }

        if (segmentData.globalMotion() || segmentData.skip()) {
            InterMotionVector zeroMotionVector = InterMotionVector.resolved(MotionVector.zero());
            return new InterModeSelection(SingleInterPredictionMode.GLOBALMV, null, 0, zeroMotionVector, null);
        }

        SingleInterPredictionMode singleInterMode = syntaxReader.readSingleInterMode(
                provisionalContext.singleNewMvContext(),
                provisionalContext.singleGlobalMvContext(),
                provisionalContext.singleReferenceMvContext(),
                false,
                false
        );
        int drlIndex = switch (singleInterMode) {
            case GLOBALMV, NEARESTMV -> 0;
            case NEARMV -> readNearDrlIndex(provisionalContext);
            case NEWMV -> readNewDrlIndex(provisionalContext);
        };
        InterMotionVector motionVector0 = resolveSingleMotionVector(
                singleInterMode,
                selectReferenceMotionVectorCandidate(provisionalContext, motionVectorCandidateIndex(singleInterMode, drlIndex))
        );
        if (singleInterMode == SingleInterPredictionMode.NEWMV) {
            motionVector0 = decodeNewMotionVectorResidual(motionVector0);
        }
        return new InterModeSelection(singleInterMode, null, drlIndex, motionVector0, null);
    }

    /// Returns the provisional motion-vector candidate index used by one single-reference mode.
    ///
    /// @param singleInterMode the decoded single-reference inter mode
    /// @param drlIndex the decoded provisional dynamic-reference-list index
    /// @return the provisional motion-vector candidate index used by one single-reference mode
    private static int motionVectorCandidateIndex(SingleInterPredictionMode singleInterMode, int drlIndex) {
        return switch (Objects.requireNonNull(singleInterMode, "singleInterMode")) {
            case GLOBALMV, NEARESTMV -> 0;
            case NEARMV, NEWMV -> drlIndex;
        };
    }

    /// Returns the provisional motion-vector candidate index used by one compound mode.
    ///
    /// @param compoundInterMode the decoded compound inter mode
    /// @param drlIndex the decoded provisional dynamic-reference-list index
    /// @return the provisional motion-vector candidate index used by one compound mode
    private static int motionVectorCandidateIndex(CompoundInterPredictionMode compoundInterMode, int drlIndex) {
        CompoundInterPredictionMode nonNullCompoundInterMode = Objects.requireNonNull(compoundInterMode, "compoundInterMode");
        if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEARESTMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV) {
            return 0;
        }
        return drlIndex;
    }

    /// Returns one provisional motion-vector candidate by index, clamped to the available range.
    ///
    /// @param provisionalContext the provisional inter-mode context derived from neighbors
    /// @param index the preferred zero-based candidate index
    /// @return one provisional motion-vector candidate by index, clamped to the available range
    private static BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate selectReferenceMotionVectorCandidate(
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext,
            int index
    ) {
        BlockNeighborContext.ProvisionalInterModeContext nonNullProvisionalContext =
                Objects.requireNonNull(provisionalContext, "provisionalContext");
        int candidateCount = nonNullProvisionalContext.motionVectorCandidateCount();
        if (candidateCount == 0) {
            throw new IllegalStateException("Provisional inter-mode contexts must expose at least one motion-vector candidate");
        }
        return nonNullProvisionalContext.motionVectorCandidate(Math.min(index, candidateCount - 1));
    }

    /// Resolves the single-reference motion-vector predictor chosen for one decoded single inter mode.
    ///
    /// @param singleInterMode the decoded single-reference inter mode
    /// @param candidate the provisional motion-vector candidate selected for that mode
    /// @return the single-reference motion-vector predictor chosen for the decoded mode
    private static InterMotionVector resolveSingleMotionVector(
            SingleInterPredictionMode singleInterMode,
            BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        SingleInterPredictionMode nonNullSingleInterMode = Objects.requireNonNull(singleInterMode, "singleInterMode");
        BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate =
                Objects.requireNonNull(candidate, "candidate");
        return switch (nonNullSingleInterMode) {
            case GLOBALMV -> InterMotionVector.resolved(MotionVector.zero());
            case NEARESTMV, NEARMV -> nonNullCandidate.motionVector0();
            case NEWMV -> nonNullCandidate.motionVector0().asPredicted();
        };
    }

    /// Resolves the first compound-reference motion-vector predictor chosen for one decoded compound mode.
    ///
    /// @param compoundInterMode the decoded compound inter mode
    /// @param candidate the provisional motion-vector candidate selected for that mode
    /// @return the first compound-reference motion-vector predictor chosen for the decoded mode
    private static InterMotionVector resolveCompoundMotionVector0(
            CompoundInterPredictionMode compoundInterMode,
            BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        CompoundInterPredictionMode nonNullCompoundInterMode = Objects.requireNonNull(compoundInterMode, "compoundInterMode");
        BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate =
                Objects.requireNonNull(candidate, "candidate");
        if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
            return InterMotionVector.resolved(MotionVector.zero());
        }
        if (nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
            return nonNullCandidate.motionVector0().asPredicted();
        }
        return nonNullCandidate.motionVector0();
    }

    /// Resolves the second compound-reference motion-vector predictor chosen for one decoded compound mode.
    ///
    /// @param compoundInterMode the decoded compound inter mode
    /// @param candidate the provisional motion-vector candidate selected for that mode
    /// @return the second compound-reference motion-vector predictor chosen for the decoded mode
    private static InterMotionVector resolveCompoundMotionVector1(
            CompoundInterPredictionMode compoundInterMode,
            BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        CompoundInterPredictionMode nonNullCompoundInterMode = Objects.requireNonNull(compoundInterMode, "compoundInterMode");
        BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate =
                Objects.requireNonNull(candidate, "candidate");
        if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
            return InterMotionVector.resolved(MotionVector.zero());
        }
        @Nullable InterMotionVector motionVector1 = nonNullCandidate.motionVector1();
        if (motionVector1 == null) {
            throw new IllegalStateException("Compound provisional motion-vector candidates must carry a secondary vector");
        }
        if (nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARMV_NEWMV
                || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
            return motionVector1.asPredicted();
        }
        return motionVector1;
    }

    /// Decodes one `NEWMV` residual around the supplied provisional motion-vector predictor.
    ///
    /// @param predictor the provisional motion-vector predictor selected for the block
    /// @return the fully decoded motion-vector state for the block
    private InterMotionVector decodeNewMotionVectorResidual(InterMotionVector predictor) {
        InterMotionVector nonNullPredictor = Objects.requireNonNull(predictor, "predictor");
        MotionVector decodedMotionVector = syntaxReader.readMotionVectorResidual(nonNullPredictor.vector());
        return InterMotionVector.resolved(decodedMotionVector);
    }

    /// Decodes the provisional dynamic-reference-list index for a `NEWMV` single-reference block.
    ///
    /// @param provisionalContext the provisional inter-mode context derived from neighbors
    /// @return the decoded provisional dynamic-reference-list index
    private int readNewDrlIndex(BlockNeighborContext.ProvisionalInterModeContext provisionalContext) {
        if (provisionalContext.candidateCount() <= 1) {
            return 0;
        }
        int drlIndex = syntaxReader.readDrlBit(provisionalContext.drlContext(0)) ? 1 : 0;
        if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
            drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(1)) ? 1 : 0;
        }
        return drlIndex;
    }

    /// Decodes the provisional dynamic-reference-list index for a `NEARMV` single-reference block.
    ///
    /// @param provisionalContext the provisional inter-mode context derived from neighbors
    /// @return the decoded provisional dynamic-reference-list index
    private int readNearDrlIndex(BlockNeighborContext.ProvisionalInterModeContext provisionalContext) {
        int drlIndex = 1;
        if (provisionalContext.candidateCount() > 2) {
            drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(1)) ? 1 : 0;
            if (drlIndex == 2 && provisionalContext.candidateCount() > 3) {
                drlIndex += syntaxReader.readDrlBit(provisionalContext.drlContext(2)) ? 1 : 0;
            }
        }
        return drlIndex;
    }

    /// Decodes one compound inter reference pair.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @return the decoded compound inter reference pair
    private InterReferenceSelection readCompoundReferenceSelection(
            BlockPosition position,
            BlockNeighborContext neighborContext
    ) {
        if (syntaxReader.readCompoundDirectionFlag(neighborContext.compoundDirectionContext(position))) {
            int referenceFrame0;
            if (syntaxReader.readCompoundForwardReferenceFlag(0, neighborContext.forwardReferenceContext(position))) {
                referenceFrame0 = LAST3_FRAME
                        + (syntaxReader.readCompoundForwardReferenceFlag(2, neighborContext.forwardReference2Context(position)) ? 1 : 0);
            } else {
                referenceFrame0 = syntaxReader.readCompoundForwardReferenceFlag(1, neighborContext.forwardReference1Context(position))
                        ? LAST2_FRAME
                        : LAST_FRAME;
            }

            int referenceFrame1;
            if (syntaxReader.readCompoundBackwardReferenceFlag(0, neighborContext.backwardReferenceContext(position))) {
                referenceFrame1 = ALTREF_FRAME;
            } else {
                referenceFrame1 = BWDREF_FRAME
                        + (syntaxReader.readCompoundBackwardReferenceFlag(1, neighborContext.backwardReference1Context(position)) ? 1 : 0);
            }
            return new InterReferenceSelection(true, referenceFrame0, referenceFrame1);
        }

        if (syntaxReader.readCompoundUnidirectionalReferenceFlag(0, neighborContext.singleReferenceContext(position))) {
            return new InterReferenceSelection(true, BWDREF_FRAME, ALTREF_FRAME);
        }

        int referenceFrame1 = LAST2_FRAME
                + (syntaxReader.readCompoundUnidirectionalReferenceFlag(1, neighborContext.unidirectionalReference1Context(position)) ? 1 : 0);
        if (referenceFrame1 == LAST3_FRAME) {
            referenceFrame1 += syntaxReader.readCompoundUnidirectionalReferenceFlag(2, neighborContext.forwardReference2Context(position)) ? 1 : 0;
        }
        return new InterReferenceSelection(true, LAST_FRAME, referenceFrame1);
    }

    /// Decodes one single inter reference.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param segmentData the final segment data for the current block
    /// @return the decoded single inter reference in internal LAST..ALTREF order
    private int readSingleReference(
            BlockPosition position,
            BlockNeighborContext neighborContext,
            FrameHeader.SegmentData segmentData
    ) {
        int segmentReferenceFrame = segmentData.referenceFrame();
        if (segmentReferenceFrame >= 0) {
            return segmentReferenceFrame - 1;
        }
        if (segmentData.globalMotion() || segmentData.skip()) {
            return LAST_FRAME;
        }

        if (syntaxReader.readSingleReferenceFlag(0, neighborContext.singleReferenceContext(position))) {
            if (syntaxReader.readSingleReferenceFlag(1, neighborContext.backwardReferenceContext(position))) {
                return ALTREF_FRAME;
            }
            return BWDREF_FRAME
                    + (syntaxReader.readSingleReferenceFlag(5, neighborContext.backwardReference1Context(position)) ? 1 : 0);
        }
        if (syntaxReader.readSingleReferenceFlag(2, neighborContext.forwardReferenceContext(position))) {
            return LAST3_FRAME
                    + (syntaxReader.readSingleReferenceFlag(4, neighborContext.forwardReference2Context(position)) ? 1 : 0);
        }
        return syntaxReader.readSingleReferenceFlag(3, neighborContext.forwardReference1Context(position))
                ? LAST2_FRAME
                : LAST_FRAME;
    }

    /// Returns whether palette syntax is available for the supplied block size.
    ///
    /// AV1 palette mode is limited to screen-content frames and blocks up to 64x64, excluding
    /// the smallest 4x4 and 4x8 style blocks whose 4x4 dimensions sum to less than four.
    ///
    /// @param size the block size to test
    /// @return whether palette syntax is available for the supplied block size
    private boolean allowsPalette(BlockSize size) {
        return tileContext.frameHeader().allowScreenContentTools()
                && Math.max(size.width4(), size.height4()) <= 16
                && size.width4() + size.height4() >= 4;
    }

    /// Decodes one sorted palette plane using the current above/left palette cache.
    ///
    /// AV1 palette entries for luma and U chroma reuse cached sorted entries from left and above
    /// neighbors, then merge any newly coded values into a final sorted palette.
    ///
    /// @param plane the palette plane index, where `0` is Y and `1` is U
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies palette caches
    /// @return the decoded sorted palette entries
    private int[] readPalettePlane(
            int plane,
            int paletteSize,
            BlockPosition position,
            BlockNeighborContext neighborContext
    ) {
        int x4 = position.x4();
        int y4 = position.y4();
        int leftCacheSize = plane == 0 ? neighborContext.leftPaletteSize(y4) : neighborContext.leftChromaPaletteSize(y4);
        int aboveCacheSize = canReuseAbovePaletteCache(position)
                ? plane == 0 ? neighborContext.abovePaletteSize(x4) : neighborContext.aboveChromaPaletteSize(x4)
                : 0;
        int[] cache = new int[16];
        int cacheSize = mergePaletteCache(cache, plane, x4, y4, leftCacheSize, aboveCacheSize, neighborContext);
        int[] usedCache = new int[8];
        int usedCacheSize = 0;
        for (int i = 0; i < cacheSize && usedCacheSize < paletteSize; i++) {
            if (tileContext.msacDecoder().decodeBooleanEqui()) {
                usedCache[usedCacheSize++] = cache[i];
            }
        }

        int[] palette = new int[paletteSize];
        int insertIndex = usedCacheSize;
        if (insertIndex < paletteSize) {
            int bitDepth = tileContext.sequenceHeader().colorConfig().bitDepth();
            int max = (1 << bitDepth) - 1;
            int step = plane == 0 ? 1 : 0;
            int previous = tileContext.msacDecoder().decodeBools(bitDepth);
            palette[insertIndex++] = previous;
            if (insertIndex < paletteSize) {
                int bits = bitDepth - 3 + tileContext.msacDecoder().decodeBools(2);
                while (insertIndex < paletteSize) {
                    int delta = tileContext.msacDecoder().decodeBools(bits);
                    previous = Math.min(previous + delta + step, max);
                    palette[insertIndex++] = previous;
                    if (previous + step >= max) {
                        Arrays.fill(palette, insertIndex, paletteSize, max);
                        break;
                    }
                    bits = Math.min(bits, bitWidth(max - previous - step));
                }
            }
        }

        if (usedCacheSize == 0) {
            return palette;
        }

        if (usedCacheSize == paletteSize) {
            return Arrays.copyOf(usedCache, usedCacheSize);
        }

        int[] merged = new int[paletteSize];
        int usedIndex = 0;
        int newIndex = usedCacheSize;
        for (int i = 0; i < paletteSize; i++) {
            if (usedIndex < usedCacheSize && (newIndex >= paletteSize || usedCache[usedIndex] <= palette[newIndex])) {
                merged[i] = usedCache[usedIndex++];
            } else {
                merged[i] = palette[newIndex++];
            }
        }
        return merged;
    }

    /// Decodes one V chroma palette using the AV1 explicit chroma-V coding rules.
    ///
    /// @param paletteSize the decoded chroma palette size in `[2, 8]`
    /// @return the decoded V chroma palette entries
    private int[] readChromaVPalette(int paletteSize) {
        int[] palette = new int[paletteSize];
        int bitDepth = tileContext.sequenceHeader().colorConfig().bitDepth();
        int max = (1 << bitDepth) - 1;
        if (tileContext.msacDecoder().decodeBooleanEqui()) {
            int bits = bitDepth - 4 + tileContext.msacDecoder().decodeBools(2);
            int previous = tileContext.msacDecoder().decodeBools(bitDepth);
            palette[0] = previous;
            for (int i = 1; i < paletteSize; i++) {
                int delta = tileContext.msacDecoder().decodeBools(bits);
                if (delta != 0 && tileContext.msacDecoder().decodeBooleanEqui()) {
                    delta = -delta;
                }
                previous = (previous + delta) & max;
                palette[i] = previous;
            }
        } else {
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = tileContext.msacDecoder().decodeBools(bitDepth);
            }
        }
        return palette;
    }

    /// Decodes one packed palette index map for luma or chroma.
    ///
    /// AV1 signals palette indices in wave-front diagonal order, then packs the visible map to
    /// two 4-bit entries per output byte while replicating invisible right and bottom edges.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param position the local tile-relative block origin
    /// @param size the block size that owns the palette
    /// @return the packed palette index map, or an empty array when palette mode is disabled
    private byte[] readPaletteIndices(int plane, int paletteSize, BlockPosition position, BlockSize size) {
        int visibleWidth4 = visibleWidth4(position, size);
        int visibleHeight4 = visibleHeight4(position, size);
        int fullWidth4 = size.width4();
        int fullHeight4 = size.height4();
        if (plane != 0) {
            int subsamplingX = tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
            int subsamplingY = tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
            visibleWidth4 = chromaSpan4(position.x4(), visibleWidth4, subsamplingX);
            visibleHeight4 = chromaSpan4(position.y4(), visibleHeight4, subsamplingY);
            fullWidth4 = chromaSpan4(position.x4(), fullWidth4, subsamplingX);
            fullHeight4 = chromaSpan4(position.y4(), fullHeight4, subsamplingY);
        }

        return readPaletteIndices(plane, paletteSize, visibleWidth4 << 2, visibleHeight4 << 2, fullWidth4 << 2, fullHeight4 << 2);
    }

    /// Decodes one packed palette index map for the supplied visible and coded dimensions.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param visibleWidth the visible palette width in pixels
    /// @param visibleHeight the visible palette height in pixels
    /// @param fullWidth the coded palette width in pixels
    /// @param fullHeight the coded palette height in pixels
    /// @return the packed palette index map
    private byte[] readPaletteIndices(
            int plane,
            int paletteSize,
            int visibleWidth,
            int visibleHeight,
            int fullWidth,
            int fullHeight
    ) {
        byte[] unpacked = new byte[fullWidth * fullHeight];
        unpacked[0] = (byte) syntaxReader.readPaletteInitialIndex(paletteSize);
        int[] order = new int[8];
        for (int i = 1; i < visibleWidth + visibleHeight - 1; i++) {
            int first = Math.min(i, visibleWidth - 1);
            int last = Math.max(0, i - visibleHeight + 1);
            for (int x = first; x >= last; x--) {
                int y = i - x;
                int context = buildPaletteOrder(unpacked, fullWidth, x, y, order);
                int colorIndex = syntaxReader.readPaletteColorMapSymbol(plane, paletteSize, context);
                unpacked[y * fullWidth + x] = (byte) order[colorIndex];
            }
        }
        return finishPaletteIndices(unpacked, fullWidth, fullHeight, visibleWidth, visibleHeight);
    }

    /// Returns the visible block width in 4x4 units after clipping against the tile bounds.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to clip
    /// @return the visible block width in 4x4 units after clipping against the tile bounds
    private int visibleWidth4(BlockPosition position, BlockSize size) {
        int tileWidth4 = (tileContext.width() + 3) >> 2;
        return Math.min(size.width4(), tileWidth4 - position.x4());
    }

    /// Returns the visible block height in 4x4 units after clipping against the tile bounds.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to clip
    /// @return the visible block height in 4x4 units after clipping against the tile bounds
    private int visibleHeight4(BlockPosition position, BlockSize size) {
        int tileHeight4 = (tileContext.height() + 3) >> 2;
        return Math.min(size.height4(), tileHeight4 - position.y4());
    }

    /// Returns the covered chroma span in 4x4 units for one luma-aligned block span.
    ///
    /// @param start4 the luma start coordinate in 4x4 units
    /// @param span4 the luma span in 4x4 units
    /// @param subsampling the chroma subsampling shift for the axis
    /// @return the covered chroma span in 4x4 units
    private static int chromaSpan4(int start4, int span4, int subsampling) {
        int chromaStart = start4 >> subsampling;
        int chromaEnd = (start4 + span4 + (1 << subsampling) - 1) >> subsampling;
        return Math.max(1, chromaEnd - chromaStart);
    }

    /// Builds the palette-order permutation and context for one palette color-map sample.
    ///
    /// @param indices the unpacked palette indices decoded so far
    /// @param stride the unpacked palette row stride in pixels
    /// @param x the current X coordinate in pixels
    /// @param y the current Y coordinate in pixels
    /// @param order the reusable destination array that receives the palette-order permutation
    /// @return the zero-based palette color-map context in `[0, 5)`
    private static int buildPaletteOrder(byte[] indices, int stride, int x, int y, int[] order) {
        int count = 0;
        int mask = 0;
        int context;
        if (x == 0) {
            int top = indices[(y - 1) * stride] & 0xFF;
            order[count++] = top;
            mask |= 1 << top;
            context = 0;
        } else if (y == 0) {
            int left = indices[x - 1] & 0xFF;
            order[count++] = left;
            mask |= 1 << left;
            context = 0;
        } else {
            int left = indices[y * stride + x - 1] & 0xFF;
            int top = indices[(y - 1) * stride + x] & 0xFF;
            int topLeft = indices[(y - 1) * stride + x - 1] & 0xFF;
            boolean sameTopLeft = top == left;
            boolean sameTopTopLeft = top == topLeft;
            boolean sameLeftTopLeft = left == topLeft;
            if (sameTopLeft && sameTopTopLeft) {
                order[count++] = top;
                mask |= 1 << top;
                context = 4;
            } else if (sameTopLeft) {
                order[count++] = top;
                order[count++] = topLeft;
                mask |= 1 << top;
                mask |= 1 << topLeft;
                context = 3;
            } else if (sameTopTopLeft || sameLeftTopLeft) {
                order[count++] = topLeft;
                order[count++] = sameTopTopLeft ? left : top;
                mask |= 1 << topLeft;
                mask |= 1 << (sameTopTopLeft ? left : top);
                context = 2;
            } else {
                int first = Math.min(top, left);
                int second = Math.max(top, left);
                order[count++] = first;
                order[count++] = second;
                order[count++] = topLeft;
                mask |= 1 << first;
                mask |= 1 << second;
                mask |= 1 << topLeft;
                context = 1;
            }
        }

        for (int value = 0; value < 8; value++) {
            if ((mask & (1 << value)) == 0) {
                order[count++] = value;
            }
        }
        return context;
    }

    /// Packs one unpacked palette map to two 4-bit entries per byte and fills invisible edges.
    ///
    /// @param unpacked the unpacked palette map in raster order
    /// @param fullWidth the coded palette width in pixels
    /// @param fullHeight the coded palette height in pixels
    /// @param visibleWidth the visible palette width in pixels
    /// @param visibleHeight the visible palette height in pixels
    /// @return the packed palette map with invisible edges replicated
    private static byte[] finishPaletteIndices(
            byte[] unpacked,
            int fullWidth,
            int fullHeight,
            int visibleWidth,
            int visibleHeight
    ) {
        int packedStride = fullWidth >> 1;
        int visiblePackedWidth = visibleWidth >> 1;
        byte[] packed = new byte[packedStride * fullHeight];
        for (int y = 0; y < visibleHeight; y++) {
            int sourceRow = y * fullWidth;
            int packedRow = y * packedStride;
            for (int x = 0; x < visiblePackedWidth; x++) {
                packed[packedRow + x] = (byte) ((unpacked[sourceRow + (x << 1)] & 0x0F)
                        | ((unpacked[sourceRow + (x << 1) + 1] & 0x0F) << 4));
            }
            if (visiblePackedWidth < packedStride) {
                Arrays.fill(
                        packed,
                        packedRow + visiblePackedWidth,
                        packedRow + packedStride,
                        (byte) ((unpacked[sourceRow + visibleWidth - 1] & 0xFF) * 0x11)
                );
            }
        }
        if (visibleHeight < fullHeight) {
            int lastVisibleRow = (visibleHeight - 1) * packedStride;
            for (int y = visibleHeight; y < fullHeight; y++) {
                System.arraycopy(packed, lastVisibleRow, packed, y * packedStride, packedStride);
            }
        }
        return packed;
    }

    /// Returns whether the above palette cache may be reused for the supplied block position.
    ///
    /// AV1 does not reuse the above palette cache across 64x64 superblock row boundaries.
    ///
    /// @param position the local tile-relative block origin
    /// @return whether the above palette cache may be reused for the supplied block position
    private boolean canReuseAbovePaletteCache(BlockPosition position) {
        int frameY4 = (tileContext.startY() >> 2) + position.y4();
        return (frameY4 & 15) != 0;
    }

    /// Merges the current left and above palette caches into one sorted unique cache.
    ///
    /// @param destination the destination array that receives the merged cache
    /// @param plane the palette plane index, where `0` is Y and `1` is U
    /// @param x4 the local X coordinate in 4x4 units
    /// @param y4 the local Y coordinate in 4x4 units
    /// @param leftCacheSize the left-edge palette size
    /// @param aboveCacheSize the above-edge palette size
    /// @param neighborContext the neighbor context that owns the palette caches
    /// @return the merged cache size written into `destination`
    private static int mergePaletteCache(
            int[] destination,
            int plane,
            int x4,
            int y4,
            int leftCacheSize,
            int aboveCacheSize,
            BlockNeighborContext neighborContext
    ) {
        int leftIndex = 0;
        int aboveIndex = 0;
        int cacheSize = 0;
        while (leftIndex < leftCacheSize && aboveIndex < aboveCacheSize) {
            int left = neighborContext.leftPaletteEntry(plane, y4, leftIndex);
            int above = neighborContext.abovePaletteEntry(plane, x4, aboveIndex);
            if (left < above) {
                cacheSize = appendUnique(destination, cacheSize, left);
                leftIndex++;
            } else {
                if (above == left) {
                    leftIndex++;
                }
                cacheSize = appendUnique(destination, cacheSize, above);
                aboveIndex++;
            }
        }
        while (leftIndex < leftCacheSize) {
            cacheSize = appendUnique(destination, cacheSize, neighborContext.leftPaletteEntry(plane, y4, leftIndex++));
        }
        while (aboveIndex < aboveCacheSize) {
            cacheSize = appendUnique(destination, cacheSize, neighborContext.abovePaletteEntry(plane, x4, aboveIndex++));
        }
        return cacheSize;
    }

    /// Appends one value to a sorted unique cache when it differs from the previous entry.
    ///
    /// @param destination the destination sorted unique cache
    /// @param size the current number of written entries
    /// @param value the next candidate palette value
    /// @return the updated number of written entries
    private static int appendUnique(int[] destination, int size, int value) {
        if (size == 0 || destination[size - 1] != value) {
            destination[size] = value;
            return size + 1;
        }
        return size;
    }

    /// Returns the minimum bit width needed to code a positive unsigned integer range.
    ///
    /// @param value the positive unsigned integer range
    /// @return the minimum bit width needed to code the supplied range
    private static int bitWidth(int value) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(value);
    }

    /// Returns whether filter-intra syntax is available for the supplied block.
    ///
    /// Filter intra is only signaled for DC-predicted luma blocks whose longest side does not
    /// exceed 32 pixels when the active sequence enables the tool.
    ///
    /// @param size the block size to test
    /// @param yMode the decoded luma intra prediction mode
    /// @return whether filter-intra syntax is available for the supplied block
    private boolean allowsFilterIntra(BlockSize size, LumaIntraPredictionMode yMode) {
        return tileContext.sequenceHeader().features().filterIntra()
                && yMode == LumaIntraPredictionMode.DC
                && Math.max(size.widthPixels(), size.heightPixels()) <= 32;
    }

    /// Reads a segment identifier from the preskip portion of the block header.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @return the decoded preskip segment identifier
    private SegmentReadResult readSegmentIdBeforeSkip(BlockPosition position, BlockNeighborContext neighborContext) {
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        if (!segmentation.enabled() || !segmentation.updateMap()) {
            return new SegmentReadResult(false, 0);
        }
        BlockNeighborContext.SegmentPrediction prediction = neighborContext.currentSegmentPrediction(position);
        if (segmentation.temporalUpdate()) {
            boolean segmentPredicted = syntaxReader.readSegmentPredictionFlag(neighborContext.segmentPredictionContext(position));
            if (segmentPredicted) {
                return new SegmentReadResult(true, prediction.predictedSegmentId());
            }
        }

        int segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId());
        return new SegmentReadResult(false, segmentId);
    }

    /// Reads a segment identifier from the postskip portion of the block header.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param skip whether the current block already decoded as skipped
    /// @return the decoded postskip segment identifier
    private SegmentReadResult readSegmentIdAfterSkip(
            BlockPosition position,
            BlockNeighborContext neighborContext,
            boolean skip
    ) {
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        if (!segmentation.enabled() || !segmentation.updateMap()) {
            return new SegmentReadResult(false, 0);
        }
        BlockNeighborContext.SegmentPrediction prediction = neighborContext.currentSegmentPrediction(position);
        if (skip) {
            return new SegmentReadResult(false, prediction.predictedSegmentId());
        }
        if (segmentation.temporalUpdate()) {
            boolean segmentPredicted = syntaxReader.readSegmentPredictionFlag(neighborContext.segmentPredictionContext(position));
            if (segmentPredicted) {
                return new SegmentReadResult(true, prediction.predictedSegmentId());
            }
        }
        int segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId());
        return new SegmentReadResult(false, segmentId);
    }

    /// Decodes one segment identifier from a predicted segment and segment-id context.
    ///
    /// @param prediction the current-frame segment prediction derived from already-decoded neighbors
    /// @param lastActiveSegmentId the highest active segment identifier, or `-1`
    /// @return the decoded segment identifier
    private int decodeSegmentId(BlockNeighborContext.SegmentPrediction prediction, int lastActiveSegmentId) {
        int diff = syntaxReader.readSegmentId(prediction.context());
        int segmentId = negDeinterleave(diff, prediction.predictedSegmentId(), lastActiveSegmentId + 1);
        if (segmentId > lastActiveSegmentId || segmentId >= 8) {
            return 0;
        }
        return segmentId;
    }

    /// Applies the AV1 `neg_deinterleave` mapping used by segment-id decoding.
    ///
    /// @param diff the decoded diff symbol
    /// @param reference the predicted segment identifier
    /// @param max the exclusive upper bound passed by the bitstream syntax
    /// @return the deinterleaved segment identifier candidate
    private static int negDeinterleave(int diff, int reference, int max) {
        if (reference == 0) {
            return diff;
        }
        if (reference >= max - 1) {
            return max - diff - 1;
        }
        if (reference * 2 < max) {
            if (diff <= reference * 2) {
                return (diff & 1) != 0
                        ? reference + ((diff + 1) >> 1)
                        : reference - (diff >> 1);
            }
            return diff;
        }
        if (diff <= (max - reference - 1) * 2) {
            return (diff & 1) != 0
                    ? reference + ((diff + 1) >> 1)
                    : reference - (diff >> 1);
        }
        return max - diff - 1;
    }

    /// Returns whether the supplied block size supports directional angle-delta syntax.
    ///
    /// @param size the block size to test
    /// @return whether the supplied block size supports directional angle-delta syntax
    private static boolean supportsAngleDelta(BlockSize size) {
        return size.width4() * size.height4() >= 4;
    }

    /// One decoded leaf block header.
    @NotNullByDefault
    public static final class BlockHeader {
        /// The local tile-relative block origin.
        private final BlockPosition position;

        /// The decoded block size.
        private final BlockSize size;

        /// Whether the block has chroma samples in the active frame layout.
        private final boolean hasChroma;

        /// The decoded skip flag.
        private final boolean skip;

        /// The decoded skip-mode flag.
        private final boolean skipMode;

        /// Whether the block is intra-coded.
        private final boolean intra;

        /// Whether the block uses `intrabc`.
        private final boolean useIntrabc;

        /// Whether the block uses compound inter references.
        private final boolean compoundReference;

        /// The primary inter reference in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame0;

        /// The secondary inter reference in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame1;

        /// The decoded single-reference inter mode, or `null` when not available.
        private final @Nullable SingleInterPredictionMode singleInterMode;

        /// The decoded compound inter mode, or `null` when not available.
        private final @Nullable CompoundInterPredictionMode compoundInterMode;

        /// The decoded dynamic-reference-list index, or `-1` when not available.
        private final int drlIndex;

        /// The primary motion vector chosen for the block, or `null` when not available.
        private final @Nullable InterMotionVector motionVector0;

        /// The secondary motion vector chosen for the block, or `null` when not available.
        private final @Nullable InterMotionVector motionVector1;

        /// Whether the block used temporal segmentation prediction.
        private final boolean segmentPredicted;

        /// The decoded segment identifier for the block.
        private final int segmentId;

        /// The decoded CDEF index for the current superblock quadrant, or `-1` when still unknown.
        private final int cdefIndex;

        /// The current luma AC quantizer index after any superblock-level delta-q update.
        private final int qIndex;

        /// The current delta-lf runtime slots after any superblock-level updates.
        private final int[] deltaLfValues;

        /// The decoded luma intra prediction mode, or `null` for non-intra blocks.
        private final @Nullable LumaIntraPredictionMode yMode;

        /// The decoded chroma intra prediction mode, or `null` when not present.
        private final @Nullable UvIntraPredictionMode uvMode;

        /// The decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        private final int yPaletteSize;

        /// The decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        private final int uvPaletteSize;

        /// The decoded luma palette entries, or an empty array when palette mode is disabled.
        private final int[] yPaletteColors;

        /// The decoded U chroma palette entries, or an empty array when palette mode is disabled.
        private final int[] uPaletteColors;

        /// The decoded V chroma palette entries, or an empty array when palette mode is disabled.
        private final int[] vPaletteColors;

        /// The packed luma palette indices with two 4-bit entries per byte.
        private final byte[] yPaletteIndices;

        /// The packed chroma palette indices with two 4-bit entries per byte.
        private final byte[] uvPaletteIndices;

        /// The decoded filter-intra mode, or `null` when filter intra is disabled.
        private final @Nullable FilterIntraMode filterIntraMode;

        /// The decoded signed luma angle delta in `[-3, 3]`.
        private final int yAngle;

        /// The decoded signed chroma angle delta in `[-3, 3]`.
        private final int uvAngle;

        /// The decoded signed CFL alpha for chroma U.
        private final int cflAlphaU;

        /// The decoded signed CFL alpha for chroma V.
        private final int cflAlphaV;

        /// Creates one decoded leaf block header.
        ///
        /// @param position the local tile-relative block origin
        /// @param size the decoded block size
        /// @param hasChroma whether the block has chroma samples in the active frame layout
        /// @param skip the decoded skip flag
        /// @param skipMode the decoded skip-mode flag
        /// @param intra whether the block is intra-coded
        /// @param useIntrabc whether the block uses `intrabc`
        /// @param compoundReference whether the block uses compound inter references
        /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param singleInterMode the decoded single-reference inter mode, or `null`
        /// @param compoundInterMode the decoded compound inter mode, or `null`
        /// @param drlIndex the decoded dynamic-reference-list index, or `-1`
        /// @param motionVector0 the primary motion vector chosen for the block, or `null`
        /// @param motionVector1 the secondary motion vector chosen for the block, or `null`
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier for the block
        /// @param cdefIndex the decoded CDEF index for the current superblock quadrant, or `-1`
        /// @param qIndex the current luma AC quantizer index after any superblock-level delta-q update
        /// @param deltaLfValues the current delta-lf runtime slots after any superblock-level updates
        /// @param yMode the decoded luma intra prediction mode, or `null`
        /// @param uvMode the decoded chroma intra prediction mode, or `null`
        /// @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
        /// @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
        /// @param yPaletteColors the decoded luma palette entries, or an empty array
        /// @param uPaletteColors the decoded U chroma palette entries, or an empty array
        /// @param vPaletteColors the decoded V chroma palette entries, or an empty array
        /// @param yPaletteIndices the packed luma palette indices, or an empty array
        /// @param uvPaletteIndices the packed chroma palette indices, or an empty array
        /// @param filterIntraMode the decoded filter-intra mode, or `null`
        /// @param yAngle the decoded signed luma angle delta in `[-3, 3]`
        /// @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
        /// @param cflAlphaU the decoded signed CFL alpha for chroma U
        /// @param cflAlphaV the decoded signed CFL alpha for chroma V
        public BlockHeader(
                BlockPosition position,
                BlockSize size,
                boolean hasChroma,
                boolean skip,
                boolean skipMode,
                boolean intra,
                boolean useIntrabc,
                boolean compoundReference,
                int referenceFrame0,
                int referenceFrame1,
                @Nullable SingleInterPredictionMode singleInterMode,
                @Nullable CompoundInterPredictionMode compoundInterMode,
                int drlIndex,
                @Nullable InterMotionVector motionVector0,
                @Nullable InterMotionVector motionVector1,
                boolean segmentPredicted,
                int segmentId,
                int cdefIndex,
                int qIndex,
                int[] deltaLfValues,
                @Nullable LumaIntraPredictionMode yMode,
                @Nullable UvIntraPredictionMode uvMode,
                int yPaletteSize,
                int uvPaletteSize,
                int[] yPaletteColors,
                int[] uPaletteColors,
                int[] vPaletteColors,
                byte[] yPaletteIndices,
                byte[] uvPaletteIndices,
                @Nullable FilterIntraMode filterIntraMode,
                int yAngle,
                int uvAngle,
                int cflAlphaU,
                int cflAlphaV
        ) {
            this.position = Objects.requireNonNull(position, "position");
            this.size = Objects.requireNonNull(size, "size");
            this.hasChroma = hasChroma;
            this.skip = skip;
            this.skipMode = skipMode;
            this.intra = intra;
            this.useIntrabc = useIntrabc;
            this.compoundReference = compoundReference;
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
            this.singleInterMode = singleInterMode;
            this.compoundInterMode = compoundInterMode;
            this.drlIndex = drlIndex;
            this.motionVector0 = motionVector0;
            this.motionVector1 = motionVector1;
            this.segmentPredicted = segmentPredicted;
            this.segmentId = segmentId;
            this.cdefIndex = cdefIndex;
            this.qIndex = qIndex;
            this.deltaLfValues = Arrays.copyOf(Objects.requireNonNull(deltaLfValues, "deltaLfValues"), deltaLfValues.length);
            this.yMode = yMode;
            this.uvMode = uvMode;
            this.yPaletteSize = yPaletteSize;
            this.uvPaletteSize = uvPaletteSize;
            this.yPaletteColors = Arrays.copyOf(Objects.requireNonNull(yPaletteColors, "yPaletteColors"), yPaletteColors.length);
            this.uPaletteColors = Arrays.copyOf(Objects.requireNonNull(uPaletteColors, "uPaletteColors"), uPaletteColors.length);
            this.vPaletteColors = Arrays.copyOf(Objects.requireNonNull(vPaletteColors, "vPaletteColors"), vPaletteColors.length);
            this.yPaletteIndices = Arrays.copyOf(Objects.requireNonNull(yPaletteIndices, "yPaletteIndices"), yPaletteIndices.length);
            this.uvPaletteIndices = Arrays.copyOf(Objects.requireNonNull(uvPaletteIndices, "uvPaletteIndices"), uvPaletteIndices.length);
            this.filterIntraMode = filterIntraMode;
            this.yAngle = yAngle;
            this.uvAngle = uvAngle;
            this.cflAlphaU = cflAlphaU;
            this.cflAlphaV = cflAlphaV;
            if (this.yPaletteColors.length != yPaletteSize) {
                throw new IllegalArgumentException("yPaletteColors length does not match yPaletteSize");
            }
            if (this.uPaletteColors.length != uvPaletteSize) {
                throw new IllegalArgumentException("uPaletteColors length does not match uvPaletteSize");
            }
            if (this.vPaletteColors.length != uvPaletteSize) {
                throw new IllegalArgumentException("vPaletteColors length does not match uvPaletteSize");
            }
            if (yPaletteSize == 0 && this.yPaletteIndices.length != 0) {
                throw new IllegalArgumentException("yPaletteIndices must be empty when yPaletteSize == 0");
            }
            if (uvPaletteSize == 0 && this.uvPaletteIndices.length != 0) {
                throw new IllegalArgumentException("uvPaletteIndices must be empty when uvPaletteSize == 0");
            }
            if (cdefIndex < -1) {
                throw new IllegalArgumentException("cdefIndex < -1: " + cdefIndex);
            }
            if (qIndex < 0 || qIndex > 255) {
                throw new IllegalArgumentException("qIndex out of range: " + qIndex);
            }
            if (this.deltaLfValues.length != 4) {
                throw new IllegalArgumentException("deltaLfValues length must be 4");
            }
            if (drlIndex < -1 || drlIndex > 3) {
                throw new IllegalArgumentException("drlIndex out of range: " + drlIndex);
            }
            if ((intra || useIntrabc) && (referenceFrame0 != NO_REFERENCE_FRAME || referenceFrame1 != NO_REFERENCE_FRAME)) {
                throw new IllegalArgumentException("Intra and intrabc blocks must not carry inter references");
            }
            if ((intra || useIntrabc)
                    && (singleInterMode != null || compoundInterMode != null || drlIndex != -1
                    || motionVector0 != null || motionVector1 != null)) {
                throw new IllegalArgumentException("Intra and intrabc blocks must not carry inter-mode or motion-vector state");
            }
            if (!intra && !useIntrabc) {
                if (referenceFrame0 == NO_REFERENCE_FRAME) {
                    throw new IllegalArgumentException("Inter blocks must carry a primary reference");
                }
                if (compoundReference) {
                    if (referenceFrame1 == NO_REFERENCE_FRAME) {
                        throw new IllegalArgumentException("Compound-reference blocks must carry a secondary reference");
                    }
                    if (singleInterMode != null) {
                        throw new IllegalArgumentException("Compound-reference blocks must not carry a single inter mode");
                    }
                } else if (referenceFrame1 != NO_REFERENCE_FRAME) {
                    throw new IllegalArgumentException("Single-reference blocks must not carry a secondary reference");
                }
                if (!compoundReference && compoundInterMode != null) {
                    throw new IllegalArgumentException("Single-reference blocks must not carry a compound inter mode");
                }
                if (!compoundReference && motionVector1 != null) {
                    throw new IllegalArgumentException("Single-reference blocks must not carry a secondary motion vector");
                }
                if (compoundReference && compoundInterMode == null && drlIndex != -1) {
                    throw new IllegalArgumentException("Compound-reference blocks with DRL state must carry a compound inter mode");
                }
                if (!compoundReference && singleInterMode == null && drlIndex != -1) {
                    throw new IllegalArgumentException("Single-reference blocks with DRL state must carry a single inter mode");
                }
                if (motionVector1 != null && referenceFrame1 == NO_REFERENCE_FRAME) {
                    throw new IllegalArgumentException("Blocks without a secondary reference must not carry a secondary motion vector");
                }
            }
        }

        /// Creates one decoded leaf block header with runtime delta state defaulted to unavailable.
        ///
        /// @param position the local tile-relative block origin
        /// @param size the decoded block size
        /// @param hasChroma whether the block has chroma samples in the active frame layout
        /// @param skip the decoded skip flag
        /// @param skipMode the decoded skip-mode flag
        /// @param intra whether the block is intra-coded
        /// @param useIntrabc whether the block uses `intrabc`
        /// @param compoundReference whether the block uses compound inter references
        /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param singleInterMode the decoded single-reference inter mode, or `null`
        /// @param compoundInterMode the decoded compound inter mode, or `null`
        /// @param drlIndex the decoded dynamic-reference-list index, or `-1`
        /// @param motionVector0 the primary motion vector chosen for the block, or `null`
        /// @param motionVector1 the secondary motion vector chosen for the block, or `null`
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier for the block
        /// @param yMode the decoded luma intra prediction mode, or `null`
        /// @param uvMode the decoded chroma intra prediction mode, or `null`
        /// @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
        /// @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
        /// @param yPaletteColors the decoded luma palette entries, or an empty array
        /// @param uPaletteColors the decoded U chroma palette entries, or an empty array
        /// @param vPaletteColors the decoded V chroma palette entries, or an empty array
        /// @param yPaletteIndices the packed luma palette indices, or an empty array
        /// @param uvPaletteIndices the packed chroma palette indices, or an empty array
        /// @param filterIntraMode the decoded filter-intra mode, or `null`
        /// @param yAngle the decoded signed luma angle delta in `[-3, 3]`
        /// @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
        /// @param cflAlphaU the decoded signed CFL alpha for chroma U
        /// @param cflAlphaV the decoded signed CFL alpha for chroma V
        public BlockHeader(
                BlockPosition position,
                BlockSize size,
                boolean hasChroma,
                boolean skip,
                boolean skipMode,
                boolean intra,
                boolean useIntrabc,
                boolean compoundReference,
                int referenceFrame0,
                int referenceFrame1,
                @Nullable SingleInterPredictionMode singleInterMode,
                @Nullable CompoundInterPredictionMode compoundInterMode,
                int drlIndex,
                @Nullable InterMotionVector motionVector0,
                @Nullable InterMotionVector motionVector1,
                boolean segmentPredicted,
                int segmentId,
                @Nullable LumaIntraPredictionMode yMode,
                @Nullable UvIntraPredictionMode uvMode,
                int yPaletteSize,
                int uvPaletteSize,
                int[] yPaletteColors,
                int[] uPaletteColors,
                int[] vPaletteColors,
                byte[] yPaletteIndices,
                byte[] uvPaletteIndices,
                @Nullable FilterIntraMode filterIntraMode,
                int yAngle,
                int uvAngle,
                int cflAlphaU,
                int cflAlphaV
        ) {
            this(
                    position,
                    size,
                    hasChroma,
                    skip,
                    skipMode,
                    intra,
                    useIntrabc,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    singleInterMode,
                    compoundInterMode,
                    drlIndex,
                    motionVector0,
                    motionVector1,
                    segmentPredicted,
                    segmentId,
                    -1,
                    0,
                    new int[4],
                    yMode,
                    uvMode,
                    yPaletteSize,
                    uvPaletteSize,
                    yPaletteColors,
                    uPaletteColors,
                    vPaletteColors,
                    yPaletteIndices,
                    uvPaletteIndices,
                    filterIntraMode,
                    yAngle,
                    uvAngle,
                    cflAlphaU,
                    cflAlphaV
            );
        }

        /// Creates one decoded leaf block header with inter-mode state defaulted to unavailable.
        ///
        /// @param position the local tile-relative block origin
        /// @param size the decoded block size
        /// @param hasChroma whether the block has chroma samples in the active frame layout
        /// @param skip the decoded skip flag
        /// @param skipMode the decoded skip-mode flag
        /// @param intra whether the block is intra-coded
        /// @param useIntrabc whether the block uses `intrabc`
        /// @param compoundReference whether the block uses compound inter references
        /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier for the block
        /// @param yMode the decoded luma intra prediction mode, or `null`
        /// @param uvMode the decoded chroma intra prediction mode, or `null`
        /// @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
        /// @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
        /// @param yPaletteColors the decoded luma palette entries, or an empty array
        /// @param uPaletteColors the decoded U chroma palette entries, or an empty array
        /// @param vPaletteColors the decoded V chroma palette entries, or an empty array
        /// @param yPaletteIndices the packed luma palette indices, or an empty array
        /// @param uvPaletteIndices the packed chroma palette indices, or an empty array
        /// @param filterIntraMode the decoded filter-intra mode, or `null`
        /// @param yAngle the decoded signed luma angle delta in `[-3, 3]`
        /// @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
        /// @param cflAlphaU the decoded signed CFL alpha for chroma U
        /// @param cflAlphaV the decoded signed CFL alpha for chroma V
        public BlockHeader(
                BlockPosition position,
                BlockSize size,
                boolean hasChroma,
                boolean skip,
                boolean skipMode,
                boolean intra,
                boolean useIntrabc,
                boolean compoundReference,
                int referenceFrame0,
                int referenceFrame1,
                boolean segmentPredicted,
                int segmentId,
                @Nullable LumaIntraPredictionMode yMode,
                @Nullable UvIntraPredictionMode uvMode,
                int yPaletteSize,
                int uvPaletteSize,
                int[] yPaletteColors,
                int[] uPaletteColors,
                int[] vPaletteColors,
                byte[] yPaletteIndices,
                byte[] uvPaletteIndices,
                @Nullable FilterIntraMode filterIntraMode,
                int yAngle,
                int uvAngle,
                int cflAlphaU,
                int cflAlphaV
        ) {
            this(
                    position,
                    size,
                    hasChroma,
                    skip,
                    skipMode,
                    intra,
                    useIntrabc,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    null,
                    null,
                    -1,
                    null,
                    null,
                    segmentPredicted,
                    segmentId,
                    -1,
                    0,
                    new int[4],
                    yMode,
                    uvMode,
                    yPaletteSize,
                    uvPaletteSize,
                    yPaletteColors,
                    uPaletteColors,
                    vPaletteColors,
                    yPaletteIndices,
                    uvPaletteIndices,
                    filterIntraMode,
                    yAngle,
                    uvAngle,
                    cflAlphaU,
                    cflAlphaV
            );
        }

        /// Returns the local tile-relative block origin.
        ///
        /// @return the local tile-relative block origin
        public BlockPosition position() {
            return position;
        }

        /// Returns the decoded block size.
        ///
        /// @return the decoded block size
        public BlockSize size() {
            return size;
        }

        /// Returns whether the block has chroma samples in the active frame layout.
        ///
        /// @return whether the block has chroma samples in the active frame layout
        public boolean hasChroma() {
            return hasChroma;
        }

        /// Returns the decoded skip flag.
        ///
        /// @return the decoded skip flag
        public boolean skip() {
            return skip;
        }

        /// Returns the decoded skip-mode flag.
        ///
        /// @return the decoded skip-mode flag
        public boolean skipMode() {
            return skipMode;
        }

        /// Returns whether the block is intra-coded.
        ///
        /// @return whether the block is intra-coded
        public boolean intra() {
            return intra;
        }

        /// Returns whether the block uses `intrabc`.
        ///
        /// @return whether the block uses `intrabc`
        public boolean useIntrabc() {
            return useIntrabc;
        }

        /// Returns whether the block uses compound inter references.
        ///
        /// @return whether the block uses compound inter references
        public boolean compoundReference() {
            return compoundReference;
        }

        /// Returns the primary inter reference in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the primary inter reference in internal LAST..ALTREF order, or `-1`
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the secondary inter reference in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the secondary inter reference in internal LAST..ALTREF order, or `-1`
        public int referenceFrame1() {
            return referenceFrame1;
        }

        /// Returns the decoded single-reference inter mode, or `null` when not available.
        ///
        /// @return the decoded single-reference inter mode, or `null`
        public @Nullable SingleInterPredictionMode singleInterMode() {
            return singleInterMode;
        }

        /// Returns the decoded compound inter mode, or `null` when not available.
        ///
        /// @return the decoded compound inter mode, or `null`
        public @Nullable CompoundInterPredictionMode compoundInterMode() {
            return compoundInterMode;
        }

        /// Returns the decoded dynamic-reference-list index, or `-1` when not available.
        ///
        /// @return the decoded dynamic-reference-list index, or `-1`
        public int drlIndex() {
            return drlIndex;
        }

        /// Returns the primary motion vector chosen for the block, or `null` when not available.
        ///
        /// @return the primary motion vector chosen for the block, or `null`
        public @Nullable InterMotionVector motionVector0() {
            return motionVector0;
        }

        /// Returns the secondary motion vector chosen for the block, or `null` when not available.
        ///
        /// @return the secondary motion vector chosen for the block, or `null`
        public @Nullable InterMotionVector motionVector1() {
            return motionVector1;
        }

        /// Returns whether the block used temporal segmentation prediction.
        ///
        /// @return whether the block used temporal segmentation prediction
        public boolean segmentPredicted() {
            return segmentPredicted;
        }

        /// Returns the decoded segment identifier for the block.
        ///
        /// @return the decoded segment identifier for the block
        public int segmentId() {
            return segmentId;
        }

        /// Returns the decoded CDEF index for the current superblock quadrant, or `-1` when still unknown.
        ///
        /// @return the decoded CDEF index for the current superblock quadrant, or `-1`
        public int cdefIndex() {
            return cdefIndex;
        }

        /// Returns the current luma AC quantizer index after any superblock-level delta-q update.
        ///
        /// @return the current luma AC quantizer index after any superblock-level delta-q update
        public int qIndex() {
            return qIndex;
        }

        /// Returns a copy of the current delta-lf runtime slots.
        ///
        /// When multi-component delta-lf is disabled, only slot `0` carries meaningful state.
        ///
        /// @return a copy of the current delta-lf runtime slots
        public int[] deltaLfValues() {
            return Arrays.copyOf(deltaLfValues, deltaLfValues.length);
        }

        /// Returns the decoded luma intra prediction mode, or `null` for non-intra blocks.
        ///
        /// @return the decoded luma intra prediction mode, or `null`
        public @Nullable LumaIntraPredictionMode yMode() {
            return yMode;
        }

        /// Returns the decoded chroma intra prediction mode, or `null` when not present.
        ///
        /// @return the decoded chroma intra prediction mode, or `null`
        public @Nullable UvIntraPredictionMode uvMode() {
            return uvMode;
        }

        /// Returns the decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        ///
        /// @return the decoded luma palette size in `[0, 8]`, or `0`
        public int yPaletteSize() {
            return yPaletteSize;
        }

        /// Returns the decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        ///
        /// @return the decoded chroma palette size in `[0, 8]`, or `0`
        public int uvPaletteSize() {
            return uvPaletteSize;
        }

        /// Returns the decoded luma palette entries, or an empty array when palette mode is disabled.
        ///
        /// @return the decoded luma palette entries, or an empty array
        public int[] yPaletteColors() {
            return Arrays.copyOf(yPaletteColors, yPaletteColors.length);
        }

        /// Returns the decoded U chroma palette entries, or an empty array when palette mode is disabled.
        ///
        /// @return the decoded U chroma palette entries, or an empty array
        public int[] uPaletteColors() {
            return Arrays.copyOf(uPaletteColors, uPaletteColors.length);
        }

        /// Returns the decoded V chroma palette entries, or an empty array when palette mode is disabled.
        ///
        /// @return the decoded V chroma palette entries, or an empty array
        public int[] vPaletteColors() {
            return Arrays.copyOf(vPaletteColors, vPaletteColors.length);
        }

        /// Returns the packed luma palette indices with two 4-bit entries per byte.
        ///
        /// Invisible right and bottom edges are already replicated to the coded block extent.
        ///
        /// @return the packed luma palette indices with two 4-bit entries per byte
        public byte[] yPaletteIndices() {
            return Arrays.copyOf(yPaletteIndices, yPaletteIndices.length);
        }

        /// Returns the packed chroma palette indices with two 4-bit entries per byte.
        ///
        /// Invisible right and bottom edges are already replicated to the coded block extent.
        ///
        /// @return the packed chroma palette indices with two 4-bit entries per byte
        public byte[] uvPaletteIndices() {
            return Arrays.copyOf(uvPaletteIndices, uvPaletteIndices.length);
        }

        /// Returns the decoded filter-intra mode, or `null` when filter intra is disabled.
        ///
        /// @return the decoded filter-intra mode, or `null`
        public @Nullable FilterIntraMode filterIntraMode() {
            return filterIntraMode;
        }

        /// Returns the decoded signed luma angle delta in `[-3, 3]`.
        ///
        /// @return the decoded signed luma angle delta in `[-3, 3]`
        public int yAngle() {
            return yAngle;
        }

        /// Returns the decoded signed chroma angle delta in `[-3, 3]`.
        ///
        /// @return the decoded signed chroma angle delta in `[-3, 3]`
        public int uvAngle() {
            return uvAngle;
        }

        /// Returns the decoded signed CFL alpha for chroma U.
        ///
        /// @return the decoded signed CFL alpha for chroma U
        public int cflAlphaU() {
            return cflAlphaU;
        }

        /// Returns the decoded signed CFL alpha for chroma V.
        ///
        /// @return the decoded signed CFL alpha for chroma V
        public int cflAlphaV() {
            return cflAlphaV;
        }
    }

    /// The decoded result of one segment-id read pass.
    @NotNullByDefault
    private static final class SegmentReadResult {
        /// Whether the block used temporal segmentation prediction.
        private final boolean segmentPredicted;

        /// The decoded segment identifier.
        private final int segmentId;

        /// Creates one decoded segment-id read result.
        ///
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier
        private SegmentReadResult(boolean segmentPredicted, int segmentId) {
            this.segmentPredicted = segmentPredicted;
            this.segmentId = segmentId;
        }

        /// Returns whether the block used temporal segmentation prediction.
        ///
        /// @return whether the block used temporal segmentation prediction
        public boolean segmentPredicted() {
            return segmentPredicted;
        }

        /// Returns the decoded segment identifier.
        ///
        /// @return the decoded segment identifier
        public int segmentId() {
            return segmentId;
        }
    }

    /// The decoded inter reference selection for one block.
    @NotNullByDefault
    private static final class InterReferenceSelection {
        /// Whether the block uses compound inter references.
        private final boolean compoundReference;

        /// The primary inter reference in internal LAST..ALTREF order.
        private final int referenceFrame0;

        /// The secondary inter reference in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame1;

        /// Creates one decoded inter reference selection.
        ///
        /// @param compoundReference whether the block uses compound inter references
        /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
        /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
        private InterReferenceSelection(boolean compoundReference, int referenceFrame0, int referenceFrame1) {
            this.compoundReference = compoundReference;
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
        }

        /// Returns whether the block uses compound inter references.
        ///
        /// @return whether the block uses compound inter references
        public boolean compoundReference() {
            return compoundReference;
        }

        /// Returns the primary inter reference in internal LAST..ALTREF order.
        ///
        /// @return the primary inter reference in internal LAST..ALTREF order
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the secondary inter reference in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the secondary inter reference in internal LAST..ALTREF order, or `-1`
        public int referenceFrame1() {
            return referenceFrame1;
        }
    }

    /// The decoded inter prediction mode and provisional dynamic-reference-list index for one block.
    @NotNullByDefault
    private static final class InterModeSelection {
        /// The decoded single-reference inter mode, or `null` when the block is compound.
        private final @Nullable SingleInterPredictionMode singleInterMode;

        /// The decoded compound inter mode, or `null` when the block is single-reference.
        private final @Nullable CompoundInterPredictionMode compoundInterMode;

        /// The decoded provisional dynamic-reference-list index.
        private final int drlIndex;

        /// The decoded primary motion-vector state chosen for the block.
        private final InterMotionVector motionVector0;

        /// The decoded secondary motion-vector state chosen for the block, or `null`.
        private final @Nullable InterMotionVector motionVector1;

        /// Creates one decoded inter prediction-mode selection.
        ///
        /// @param singleInterMode the decoded single-reference inter mode, or `null`
        /// @param compoundInterMode the decoded compound inter mode, or `null`
        /// @param drlIndex the decoded provisional dynamic-reference-list index
        /// @param motionVector0 the decoded primary motion-vector state chosen for the block
        /// @param motionVector1 the decoded secondary motion-vector state chosen for the block, or `null`
        private InterModeSelection(
                @Nullable SingleInterPredictionMode singleInterMode,
                @Nullable CompoundInterPredictionMode compoundInterMode,
                int drlIndex,
                InterMotionVector motionVector0,
                @Nullable InterMotionVector motionVector1
        ) {
            this.singleInterMode = singleInterMode;
            this.compoundInterMode = compoundInterMode;
            this.drlIndex = drlIndex;
            this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
            this.motionVector1 = motionVector1;
        }

        /// Returns the decoded single-reference inter mode, or `null` when the block is compound.
        ///
        /// @return the decoded single-reference inter mode, or `null`
        public @Nullable SingleInterPredictionMode singleInterMode() {
            return singleInterMode;
        }

        /// Returns the decoded compound inter mode, or `null` when the block is single-reference.
        ///
        /// @return the decoded compound inter mode, or `null`
        public @Nullable CompoundInterPredictionMode compoundInterMode() {
            return compoundInterMode;
        }

        /// Returns the decoded provisional dynamic-reference-list index.
        ///
        /// @return the decoded provisional dynamic-reference-list index
        public int drlIndex() {
            return drlIndex;
        }

        /// Returns the decoded primary motion-vector state chosen for the block.
        ///
        /// @return the decoded primary motion-vector state chosen for the block
        public InterMotionVector motionVector0() {
            return motionVector0;
        }

        /// Returns the decoded secondary motion-vector state chosen for the block, or `null`.
        ///
        /// @return the decoded secondary motion-vector state chosen for the block, or `null`
        public @Nullable InterMotionVector motionVector1() {
            return motionVector1;
        }
    }
}
