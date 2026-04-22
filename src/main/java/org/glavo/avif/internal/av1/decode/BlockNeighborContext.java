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
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/// Tile-local neighbor context state used to derive block-level syntax contexts while scanning a tile.
@NotNullByDefault
public final class BlockNeighborContext {
    /// The maximum number of provisional motion-vector candidate entries retained locally.
    private static final int PROVISIONAL_CANDIDATE_CAPACITY = 12;

    /// The AV1 coefficient-context byte that marks one transform block as all-zero.
    private static final int ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40;

    /// The `dav1d` luma coefficient skip-context lookup table indexed by merged top and left counts.
    private static final int[][] LUMA_COEFFICIENT_SKIP_CONTEXTS = {
            {1, 2, 2, 2, 3},
            {2, 4, 4, 4, 5},
            {2, 4, 4, 4, 5},
            {2, 4, 4, 4, 5},
            {3, 5, 5, 5, 6}
    };

    /// The weight penalty applied to secondary spatial candidates relative to direct-edge candidates.
    private static final int SECONDARY_SPATIAL_WEIGHT_PENALTY = 192;

    /// The extra penalty applied for each farther odd-aligned secondary spatial offset layer.
    private static final int SECONDARY_SPATIAL_WEIGHT_PENALTY_STEP = 64;

    /// The weight contributed by one temporal motion-field sample.
    private static final int TEMPORAL_MOTION_WEIGHT = 64;

    /// The weight penalty applied to top-right spatial candidates relative to direct-edge candidates.
    private static final int TOP_RIGHT_SPATIAL_WEIGHT_PENALTY = 64;

    /// The weight penalty applied to top-left spatial candidates relative to direct-edge candidates.
    private static final int TOP_LEFT_SPATIAL_WEIGHT_PENALTY = 320;

    /// The tile width rounded up to 4x4 units.
    private final int tileWidth4;

    /// The tile height rounded up to 4x4 units.
    private final int tileHeight4;

    /// Whether reference-frame motion vectors are enabled for the active frame.
    private final boolean useReferenceFrameMotionVectors;

    /// The tile-local temporal motion field sampled from refreshed reference frames.
    private final TileDecodeContext.TemporalMotionField temporalMotionField;

    /// The tile-local temporal motion field produced while decoding the current frame.
    private final TileDecodeContext.TemporalMotionField decodedTemporalMotionField;

    /// The stored decoded block map indexed in tile-relative 4x4 units.
    private final StoredBlock[] storedBlocks;

    /// The above-edge intra flags indexed in 4x4 units.
    private final byte[] aboveIntra;

    /// The left-edge intra flags indexed in 4x4 units.
    private final byte[] leftIntra;

    /// The above-edge skip flags indexed in 4x4 units.
    private final byte[] aboveSkip;

    /// The left-edge skip flags indexed in 4x4 units.
    private final byte[] leftSkip;

    /// The above-edge skip-mode flags indexed in 4x4 units.
    private final byte[] aboveSkipMode;

    /// The left-edge skip-mode flags indexed in 4x4 units.
    private final byte[] leftSkipMode;

    /// The above-edge compound-reference flags indexed in 4x4 units.
    private final byte[] aboveCompoundReference;

    /// The left-edge compound-reference flags indexed in 4x4 units.
    private final byte[] leftCompoundReference;

    /// The above-edge primary reference-frame indices indexed in 4x4 units.
    private final byte[] aboveReferenceFrame0;

    /// The left-edge primary reference-frame indices indexed in 4x4 units.
    private final byte[] leftReferenceFrame0;

    /// The above-edge secondary reference-frame indices indexed in 4x4 units.
    private final byte[] aboveReferenceFrame1;

    /// The left-edge secondary reference-frame indices indexed in 4x4 units.
    private final byte[] leftReferenceFrame1;

    /// The above-edge primary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] aboveMotionVector0;

    /// The left-edge primary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] leftMotionVector0;

    /// The above-edge secondary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] aboveMotionVector1;

    /// The left-edge secondary motion vectors indexed in 4x4 units.
    private final InterMotionVector[] leftMotionVector1;

    /// The above-edge `NEWMV` usage flags indexed in 4x4 units.
    private final byte[] aboveUsesNewMotionVector;

    /// The left-edge `NEWMV` usage flags indexed in 4x4 units.
    private final byte[] leftUsesNewMotionVector;

    /// The above-edge segmentation-prediction flags indexed in 4x4 units.
    private final byte[] aboveSegmentPredicted;

    /// The left-edge segmentation-prediction flags indexed in 4x4 units.
    private final byte[] leftSegmentPredicted;

    /// The above-edge segment identifiers indexed in 4x4 units.
    private final byte[] aboveSegmentId;

    /// The left-edge segment identifiers indexed in 4x4 units.
    private final byte[] leftSegmentId;

    /// The above-edge luma palette sizes indexed in 4x4 units.
    private final byte[] abovePaletteSize;

    /// The left-edge luma palette sizes indexed in 4x4 units.
    private final byte[] leftPaletteSize;

    /// The above-edge chroma palette sizes indexed in 4x4 units.
    private final byte[] aboveChromaPaletteSize;

    /// The left-edge chroma palette sizes indexed in 4x4 units.
    private final byte[] leftChromaPaletteSize;

    /// The above-edge palette entries indexed as plane/x4/palette index.
    private final int[][][] abovePaletteEntries;

    /// The left-edge palette entries indexed as plane/y4/palette index.
    private final int[][][] leftPaletteEntries;

    /// The above-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] aboveMode;

    /// The left-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] leftMode;

    /// The above-edge partition context state indexed in 8x8 units.
    private final byte[] abovePartition;

    /// The left-edge partition context state indexed in 8x8 units.
    private final byte[] leftPartition;

    /// The above-edge transform-context widths indexed in 4x4 units.
    private final byte[] aboveTransformWidthLog2;

    /// The left-edge transform-context heights indexed in 4x4 units.
    private final byte[] leftTransformHeightLog2;

    /// The above-edge inter var-tx widths indexed in 4x4 units.
    private final byte[] aboveInterTransformWidthLog2;

    /// The left-edge inter var-tx heights indexed in 4x4 units.
    private final byte[] leftInterTransformHeightLog2;

    /// The above-edge luma coefficient-context bytes indexed in 4x4 units.
    private final byte[] aboveLumaCoefficientContext;

    /// The left-edge luma coefficient-context bytes indexed in 4x4 units.
    private final byte[] leftLumaCoefficientContext;

    /// Creates tile-local neighbor context state.
    ///
    /// @param tileWidth4 the tile width rounded up to 4x4 units
    /// @param tileHeight4 the tile height rounded up to 4x4 units
    /// @param useReferenceFrameMotionVectors whether reference-frame motion vectors are enabled
    /// @param temporalMotionField the tile-local temporal motion field sampled from refreshed reference frames
    /// @param decodedTemporalMotionField the tile-local temporal motion field produced while decoding the current frame
    /// @param storedBlocks the stored decoded block map indexed in tile-relative 4x4 units
    /// @param aboveIntra the above-edge intra flags indexed in 4x4 units
    /// @param leftIntra the left-edge intra flags indexed in 4x4 units
    /// @param aboveSkip the above-edge skip flags indexed in 4x4 units
    /// @param leftSkip the left-edge skip flags indexed in 4x4 units
    /// @param aboveSkipMode the above-edge skip-mode flags indexed in 4x4 units
    /// @param leftSkipMode the left-edge skip-mode flags indexed in 4x4 units
    /// @param aboveCompoundReference the above-edge compound-reference flags indexed in 4x4 units
    /// @param leftCompoundReference the left-edge compound-reference flags indexed in 4x4 units
    /// @param aboveReferenceFrame0 the above-edge primary reference-frame indices indexed in 4x4 units
    /// @param leftReferenceFrame0 the left-edge primary reference-frame indices indexed in 4x4 units
    /// @param aboveReferenceFrame1 the above-edge secondary reference-frame indices indexed in 4x4 units
    /// @param leftReferenceFrame1 the left-edge secondary reference-frame indices indexed in 4x4 units
    /// @param aboveMotionVector0 the above-edge primary motion vectors indexed in 4x4 units
    /// @param leftMotionVector0 the left-edge primary motion vectors indexed in 4x4 units
    /// @param aboveMotionVector1 the above-edge secondary motion vectors indexed in 4x4 units
    /// @param leftMotionVector1 the left-edge secondary motion vectors indexed in 4x4 units
    /// @param aboveUsesNewMotionVector the above-edge `NEWMV` usage flags indexed in 4x4 units
    /// @param leftUsesNewMotionVector the left-edge `NEWMV` usage flags indexed in 4x4 units
    /// @param aboveSegmentPredicted the above-edge segmentation-prediction flags indexed in 4x4 units
    /// @param leftSegmentPredicted the left-edge segmentation-prediction flags indexed in 4x4 units
    /// @param aboveSegmentId the above-edge segment identifiers indexed in 4x4 units
    /// @param leftSegmentId the left-edge segment identifiers indexed in 4x4 units
    /// @param abovePaletteSize the above-edge luma palette sizes indexed in 4x4 units
    /// @param leftPaletteSize the left-edge luma palette sizes indexed in 4x4 units
    /// @param aboveChromaPaletteSize the above-edge chroma palette sizes indexed in 4x4 units
    /// @param leftChromaPaletteSize the left-edge chroma palette sizes indexed in 4x4 units
    /// @param abovePaletteEntries the above-edge palette entries indexed as plane/x4/palette index
    /// @param leftPaletteEntries the left-edge palette entries indexed as plane/y4/palette index
    /// @param aboveMode the above-edge luma modes indexed in 4x4 units
    /// @param leftMode the left-edge luma modes indexed in 4x4 units
    /// @param abovePartition the above-edge partition context state indexed in 8x8 units
    /// @param leftPartition the left-edge partition context state indexed in 8x8 units
    /// @param aboveTransformWidthLog2 the above-edge transform-context widths indexed in 4x4 units
    /// @param leftTransformHeightLog2 the left-edge transform-context heights indexed in 4x4 units
    /// @param aboveInterTransformWidthLog2 the above-edge inter var-tx widths indexed in 4x4 units
    /// @param leftInterTransformHeightLog2 the left-edge inter var-tx heights indexed in 4x4 units
    /// @param aboveLumaCoefficientContext the above-edge luma coefficient-context bytes indexed in 4x4 units
    /// @param leftLumaCoefficientContext the left-edge luma coefficient-context bytes indexed in 4x4 units
    private BlockNeighborContext(
            int tileWidth4,
            int tileHeight4,
            boolean useReferenceFrameMotionVectors,
            TileDecodeContext.TemporalMotionField temporalMotionField,
            TileDecodeContext.TemporalMotionField decodedTemporalMotionField,
            StoredBlock[] storedBlocks,
            byte[] aboveIntra,
            byte[] leftIntra,
            byte[] aboveSkip,
            byte[] leftSkip,
            byte[] aboveSkipMode,
            byte[] leftSkipMode,
            byte[] aboveCompoundReference,
            byte[] leftCompoundReference,
            byte[] aboveReferenceFrame0,
            byte[] leftReferenceFrame0,
            byte[] aboveReferenceFrame1,
            byte[] leftReferenceFrame1,
            InterMotionVector[] aboveMotionVector0,
            InterMotionVector[] leftMotionVector0,
            InterMotionVector[] aboveMotionVector1,
            InterMotionVector[] leftMotionVector1,
            byte[] aboveUsesNewMotionVector,
            byte[] leftUsesNewMotionVector,
            byte[] aboveSegmentPredicted,
            byte[] leftSegmentPredicted,
            byte[] aboveSegmentId,
            byte[] leftSegmentId,
            byte[] abovePaletteSize,
            byte[] leftPaletteSize,
            byte[] aboveChromaPaletteSize,
            byte[] leftChromaPaletteSize,
            int[][][] abovePaletteEntries,
            int[][][] leftPaletteEntries,
            LumaIntraPredictionMode[] aboveMode,
            LumaIntraPredictionMode[] leftMode,
            byte[] abovePartition,
            byte[] leftPartition,
            byte[] aboveTransformWidthLog2,
            byte[] leftTransformHeightLog2,
            byte[] aboveInterTransformWidthLog2,
            byte[] leftInterTransformHeightLog2,
            byte[] aboveLumaCoefficientContext,
            byte[] leftLumaCoefficientContext
    ) {
        this.tileWidth4 = tileWidth4;
        this.tileHeight4 = tileHeight4;
        this.useReferenceFrameMotionVectors = useReferenceFrameMotionVectors;
        this.temporalMotionField = Objects.requireNonNull(temporalMotionField, "temporalMotionField");
        this.decodedTemporalMotionField = Objects.requireNonNull(decodedTemporalMotionField, "decodedTemporalMotionField");
        this.storedBlocks = Objects.requireNonNull(storedBlocks, "storedBlocks");
        this.aboveIntra = Objects.requireNonNull(aboveIntra, "aboveIntra");
        this.leftIntra = Objects.requireNonNull(leftIntra, "leftIntra");
        this.aboveSkip = Objects.requireNonNull(aboveSkip, "aboveSkip");
        this.leftSkip = Objects.requireNonNull(leftSkip, "leftSkip");
        this.aboveSkipMode = Objects.requireNonNull(aboveSkipMode, "aboveSkipMode");
        this.leftSkipMode = Objects.requireNonNull(leftSkipMode, "leftSkipMode");
        this.aboveCompoundReference = Objects.requireNonNull(aboveCompoundReference, "aboveCompoundReference");
        this.leftCompoundReference = Objects.requireNonNull(leftCompoundReference, "leftCompoundReference");
        this.aboveReferenceFrame0 = Objects.requireNonNull(aboveReferenceFrame0, "aboveReferenceFrame0");
        this.leftReferenceFrame0 = Objects.requireNonNull(leftReferenceFrame0, "leftReferenceFrame0");
        this.aboveReferenceFrame1 = Objects.requireNonNull(aboveReferenceFrame1, "aboveReferenceFrame1");
        this.leftReferenceFrame1 = Objects.requireNonNull(leftReferenceFrame1, "leftReferenceFrame1");
        this.aboveMotionVector0 = Objects.requireNonNull(aboveMotionVector0, "aboveMotionVector0");
        this.leftMotionVector0 = Objects.requireNonNull(leftMotionVector0, "leftMotionVector0");
        this.aboveMotionVector1 = Objects.requireNonNull(aboveMotionVector1, "aboveMotionVector1");
        this.leftMotionVector1 = Objects.requireNonNull(leftMotionVector1, "leftMotionVector1");
        this.aboveUsesNewMotionVector = Objects.requireNonNull(aboveUsesNewMotionVector, "aboveUsesNewMotionVector");
        this.leftUsesNewMotionVector = Objects.requireNonNull(leftUsesNewMotionVector, "leftUsesNewMotionVector");
        this.aboveSegmentPredicted = Objects.requireNonNull(aboveSegmentPredicted, "aboveSegmentPredicted");
        this.leftSegmentPredicted = Objects.requireNonNull(leftSegmentPredicted, "leftSegmentPredicted");
        this.aboveSegmentId = Objects.requireNonNull(aboveSegmentId, "aboveSegmentId");
        this.leftSegmentId = Objects.requireNonNull(leftSegmentId, "leftSegmentId");
        this.abovePaletteSize = Objects.requireNonNull(abovePaletteSize, "abovePaletteSize");
        this.leftPaletteSize = Objects.requireNonNull(leftPaletteSize, "leftPaletteSize");
        this.aboveChromaPaletteSize = Objects.requireNonNull(aboveChromaPaletteSize, "aboveChromaPaletteSize");
        this.leftChromaPaletteSize = Objects.requireNonNull(leftChromaPaletteSize, "leftChromaPaletteSize");
        this.abovePaletteEntries = Objects.requireNonNull(abovePaletteEntries, "abovePaletteEntries");
        this.leftPaletteEntries = Objects.requireNonNull(leftPaletteEntries, "leftPaletteEntries");
        this.aboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        this.leftMode = Objects.requireNonNull(leftMode, "leftMode");
        this.abovePartition = Objects.requireNonNull(abovePartition, "abovePartition");
        this.leftPartition = Objects.requireNonNull(leftPartition, "leftPartition");
        this.aboveTransformWidthLog2 = Objects.requireNonNull(aboveTransformWidthLog2, "aboveTransformWidthLog2");
        this.leftTransformHeightLog2 = Objects.requireNonNull(leftTransformHeightLog2, "leftTransformHeightLog2");
        this.aboveInterTransformWidthLog2 = Objects.requireNonNull(
                aboveInterTransformWidthLog2,
                "aboveInterTransformWidthLog2"
        );
        this.leftInterTransformHeightLog2 = Objects.requireNonNull(
                leftInterTransformHeightLog2,
                "leftInterTransformHeightLog2"
        );
        this.aboveLumaCoefficientContext = Objects.requireNonNull(
                aboveLumaCoefficientContext,
                "aboveLumaCoefficientContext"
        );
        this.leftLumaCoefficientContext = Objects.requireNonNull(
                leftLumaCoefficientContext,
                "leftLumaCoefficientContext"
        );
    }

    /// Creates initialized neighbor context state for one tile.
    ///
    /// @param tileContext the tile-local decode context
    /// @return initialized neighbor context state for one tile
    public static BlockNeighborContext create(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        int tileWidth4 = (nonNullTileContext.width() + 3) >> 2;
        int tileHeight4 = (nonNullTileContext.height() + 3) >> 2;
        int tileWidth8 = (nonNullTileContext.width() + 7) >> 3;
        int tileHeight8 = (nonNullTileContext.height() + 7) >> 3;
        boolean keyFrame = nonNullTileContext.frameHeader().frameType() == FrameType.KEY;

        byte[] aboveIntra = new byte[tileWidth4];
        byte[] leftIntra = new byte[tileHeight4];
        byte[] aboveReferenceFrame0 = new byte[tileWidth4];
        byte[] leftReferenceFrame0 = new byte[tileHeight4];
        byte[] aboveReferenceFrame1 = new byte[tileWidth4];
        byte[] leftReferenceFrame1 = new byte[tileHeight4];
        byte[] aboveTransformWidthLog2 = new byte[tileWidth4];
        byte[] leftTransformHeightLog2 = new byte[tileHeight4];
        byte[] aboveInterTransformWidthLog2 = new byte[tileWidth4];
        byte[] leftInterTransformHeightLog2 = new byte[tileHeight4];
        byte[] aboveLumaCoefficientContext = new byte[tileWidth4];
        byte[] leftLumaCoefficientContext = new byte[tileHeight4];
        InterMotionVector[] aboveMotionVector0 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector0 = new InterMotionVector[tileHeight4];
        InterMotionVector[] aboveMotionVector1 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector1 = new InterMotionVector[tileHeight4];
        LumaIntraPredictionMode[] aboveMode = new LumaIntraPredictionMode[tileWidth4];
        LumaIntraPredictionMode[] leftMode = new LumaIntraPredictionMode[tileHeight4];
        InterMotionVector defaultMotionVector = InterMotionVector.predicted(MotionVector.zero());
        Arrays.fill(aboveTransformWidthLog2, (byte) -1);
        Arrays.fill(leftTransformHeightLog2, (byte) -1);
        Arrays.fill(aboveInterTransformWidthLog2, (byte) -1);
        Arrays.fill(leftInterTransformHeightLog2, (byte) -1);
        Arrays.fill(aboveLumaCoefficientContext, (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(leftLumaCoefficientContext, (byte) ALL_ZERO_COEFFICIENT_CONTEXT_BYTE);
        Arrays.fill(aboveReferenceFrame0, (byte) -1);
        Arrays.fill(leftReferenceFrame0, (byte) -1);
        Arrays.fill(aboveReferenceFrame1, (byte) -1);
        Arrays.fill(leftReferenceFrame1, (byte) -1);
        Arrays.fill(aboveMotionVector0, defaultMotionVector);
        Arrays.fill(leftMotionVector0, defaultMotionVector);
        Arrays.fill(aboveMotionVector1, defaultMotionVector);
        Arrays.fill(leftMotionVector1, defaultMotionVector);
        Arrays.fill(aboveMode, LumaIntraPredictionMode.DC);
        Arrays.fill(leftMode, LumaIntraPredictionMode.DC);
        if (keyFrame) {
            Arrays.fill(aboveIntra, (byte) 1);
            Arrays.fill(leftIntra, (byte) 1);
        }

        return new BlockNeighborContext(
                tileWidth4,
                tileHeight4,
                nonNullTileContext.frameHeader().useReferenceFrameMotionVectors(),
                nonNullTileContext.temporalMotionField(),
                nonNullTileContext.decodedTemporalMotionField(),
                new StoredBlock[tileWidth4 * tileHeight4],
                aboveIntra,
                leftIntra,
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                aboveReferenceFrame0,
                leftReferenceFrame0,
                aboveReferenceFrame1,
                leftReferenceFrame1,
                aboveMotionVector0,
                leftMotionVector0,
                aboveMotionVector1,
                leftMotionVector1,
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new int[3][tileWidth4][8],
                new int[3][tileHeight4][8],
                aboveMode,
                leftMode,
                new byte[tileWidth8],
                new byte[tileHeight8],
                aboveTransformWidthLog2,
                leftTransformHeightLog2,
                aboveInterTransformWidthLog2,
                leftInterTransformHeightLog2,
                aboveLumaCoefficientContext,
                leftLumaCoefficientContext
        );
    }

    /// Returns the tile width rounded up to 4x4 units.
    ///
    /// @return the tile width rounded up to 4x4 units
    public int tileWidth4() {
        return tileWidth4;
    }

    /// Returns the tile height rounded up to 4x4 units.
    ///
    /// @return the tile height rounded up to 4x4 units
    public int tileHeight4() {
        return tileHeight4;
    }

    /// Returns whether the supplied block position has a top neighbor inside the tile.
    ///
    /// @param position the current block position
    /// @return whether the supplied block position has a top neighbor inside the tile
    public boolean hasTopNeighbor(BlockPosition position) {
        return Objects.requireNonNull(position, "position").y4() > 0;
    }

    /// Returns whether the supplied block position has a left neighbor inside the tile.
    ///
    /// @param position the current block position
    /// @return whether the supplied block position has a left neighbor inside the tile
    public boolean hasLeftNeighbor(BlockPosition position) {
        return Objects.requireNonNull(position, "position").x4() > 0;
    }

    /// Returns the intra/inter context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the intra/inter context for the supplied block position
    public int intraContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveLeft) {
            if (haveTop) {
                int context = leftIntra[y4] + aboveIntra[x4];
                return context + (context == 2 ? 1 : 0);
            }
            return leftIntra[y4] * 2;
        }
        return haveTop ? aboveIntra[x4] * 2 : 0;
    }

    /// Returns the skip context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the skip context for the supplied block position
    public int skipContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkip[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkip[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the skip-mode context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the skip-mode context for the supplied block position
    public int skipModeContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkipMode[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkipMode[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the compound-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the compound-reference context for the supplied block position
    public int compoundReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveTop) {
            if (haveLeft) {
                if (aboveCompoundReference[x4] != 0) {
                    if (leftCompoundReference[y4] != 0) {
                        return 4;
                    }
                    return 2 + ((leftReferenceFrame0[y4] & 0xFF) >= 4 ? 1 : 0);
                }
                if (leftCompoundReference[y4] != 0) {
                    return 2 + ((aboveReferenceFrame0[x4] & 0xFF) >= 4 ? 1 : 0);
                }
                return (((leftReferenceFrame0[y4] & 0xFF) >= 4) ? 1 : 0)
                        ^ (((aboveReferenceFrame0[x4] & 0xFF) >= 4) ? 1 : 0);
            }
            return aboveCompoundReference[x4] != 0 ? 3 : ((aboveReferenceFrame0[x4] & 0xFF) >= 4 ? 1 : 0);
        }
        if (haveLeft) {
            return leftCompoundReference[y4] != 0 ? 3 : ((leftReferenceFrame0[y4] & 0xFF) >= 4 ? 1 : 0);
        }
        return 1;
    }

    /// Returns the compound-direction context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the compound-direction context for the supplied block position
    public int compoundDirectionContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveTop && haveLeft) {
            int aboveIntra = this.aboveIntra[x4];
            int leftIntra = this.leftIntra[y4];
            if (aboveIntra != 0 && leftIntra != 0) {
                return 2;
            }
            if (aboveIntra != 0 || leftIntra != 0) {
                boolean useLeft = aboveIntra != 0;
                int off = useLeft ? y4 : x4;
                if ((useLeft ? leftCompoundReference[off] : aboveCompoundReference[off]) == 0) {
                    return 2;
                }
                return 1 + 2 * (hasUnidirectionalCompoundReference(useLeft, off) ? 1 : 0);
            }

            boolean aboveCompound = aboveCompoundReference[x4] != 0;
            boolean leftCompound = leftCompoundReference[y4] != 0;
            int aboveRef0 = aboveReferenceFrame0[x4];
            int leftRef0 = leftReferenceFrame0[y4];
            if (!aboveCompound && !leftCompound) {
                return 1 + (((aboveRef0 >= 4) == (leftRef0 >= 4)) ? 2 : 0);
            }
            if (!aboveCompound || !leftCompound) {
                boolean useAbove = aboveCompound;
                int off = useAbove ? x4 : y4;
                if (!hasUnidirectionalCompoundReference(!useAbove, off)) {
                    return 1;
                }
                return 3 + ((((aboveRef0 >= 4) == (leftRef0 >= 4)) ? 1 : 0));
            }
            boolean aboveUni = hasUnidirectionalCompoundReference(false, x4);
            boolean leftUni = hasUnidirectionalCompoundReference(true, y4);
            if (!aboveUni && !leftUni) {
                return 0;
            }
            if (!aboveUni || !leftUni) {
                return 2;
            }
            return 3 + (((aboveRef0 == 4) == (leftRef0 == 4)) ? 1 : 0);
        }
        if (haveTop || haveLeft) {
            boolean useLeft = haveLeft;
            int off = useLeft ? nonNullPosition.y4() : nonNullPosition.x4();
            if ((useLeft ? leftIntra[off] : aboveIntra[off]) != 0) {
                return 2;
            }
            if ((useLeft ? leftCompoundReference[off] : aboveCompoundReference[off]) == 0) {
                return 2;
            }
            return hasUnidirectionalCompoundReference(useLeft, off) ? 4 : 0;
        }
        return 2;
    }

    /// Returns the single-reference primary context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the single-reference primary context for the supplied block position
    public int singleReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[2];
        accumulateForwardBackwardCounts(count, false, nonNullPosition);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the forward-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the forward-reference context for the supplied block position
    public int forwardReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[4];
        accumulateReferenceCounts(count, nonNullPosition, 0, 4);
        count[0] += count[1];
        count[2] += count[3];
        return count[0] == count[2] ? 1 : count[0] < count[2] ? 0 : 2;
    }

    /// Returns the LAST-vs-LAST2 reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST-vs-LAST2 reference context for the supplied block position
    public int forwardReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[2];
        accumulateReferenceCounts(count, nonNullPosition, 0, 2);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the LAST3-vs-GOLDEN reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST3-vs-GOLDEN reference context for the supplied block position
    public int forwardReference2Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[2];
        accumulateReferenceCounts(count, nonNullPosition, 2, 2);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the backward-reference primary context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the backward-reference primary context for the supplied block position
    public int backwardReferenceContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[3];
        accumulateReferenceCounts(count, nonNullPosition, 4, 3);
        count[1] += count[0];
        return count[2] == count[1] ? 1 : count[1] < count[2] ? 0 : 2;
    }

    /// Returns the BWDREF-vs-ALTREF2 reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the BWDREF-vs-ALTREF2 reference context for the supplied block position
    public int backwardReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[3];
        accumulateReferenceCounts(count, nonNullPosition, 4, 3);
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Returns the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position
    public int unidirectionalReference1Context(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int[] count = new int[3];
        accumulateReferenceCounts(count, nonNullPosition, 1, 3);
        count[1] += count[2];
        return count[0] == count[1] ? 1 : count[0] < count[1] ? 0 : 2;
    }

    /// Builds a provisional inter-mode syntax context from already-decoded spatial neighbors.
    ///
    /// This helper scans the direct top row and left column across the full current-block span,
    /// then augments that with temporal motion-field samples, odd-aligned secondary
    /// 8x8-resolution row/column offsets, and dedicated top-right and top-left spatial
    /// candidates. It still remains an incomplete `refmvs` implementation.
    ///
    /// @param position the current block position
    /// @param size the current block size
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
    /// @return a provisional inter-mode syntax context derived from already-decoded neighbors
    public ProvisionalInterModeContext provisionalInterModeContext(
            BlockPosition position,
            BlockSize size,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        if (referenceFrame0 < 0) {
            throw new IllegalArgumentException("referenceFrame0 < 0");
        }
        if (!compoundReference && referenceFrame1 >= 0) {
            throw new IllegalArgumentException("Single-reference blocks must not carry referenceFrame1");
        }
        if (compoundReference && referenceFrame1 < 0) {
            throw new IllegalArgumentException("Compound-reference blocks must carry referenceFrame1");
        }

        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        int endX4 = Math.min(tileWidth4, x4 + nonNullSize.width4());
        int endY4 = Math.min(tileHeight4, y4 + nonNullSize.height4());
        boolean directRowMatch = false;
        boolean directColumnMatch = false;
        boolean rowReferenceMatch = false;
        boolean columnReferenceMatch = false;
        boolean haveNewMotionVectorMatch = false;
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] candidates =
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[PROVISIONAL_CANDIDATE_CAPACITY];
        candidates[0] =
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                        640,
                        InterMotionVector.predicted(MotionVector.zero()),
                        compoundReference ? InterMotionVector.predicted(MotionVector.zero()) : null,
                        true
                );
        int candidateCount = 1;

        if (hasTopNeighbor(nonNullPosition)) {
            SpatialScanResult directTopScan = scanStoredBlocksAlongSpan(
                    true,
                    y4 - 1,
                    x4,
                    endX4,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    0,
                    candidates,
                    candidateCount
            );
            candidateCount = directTopScan.candidateCount();
            directRowMatch = directTopScan.referenceMatch();
            rowReferenceMatch = directTopScan.referenceMatch();
            haveNewMotionVectorMatch |= directTopScan.haveNewMotionVectorMatch();
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            SpatialScanResult directLeftScan = scanStoredBlocksAlongSpan(
                    false,
                    x4 - 1,
                    y4,
                    endY4,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    0,
                    candidates,
                    candidateCount
            );
            candidateCount = directLeftScan.candidateCount();
            directColumnMatch = directLeftScan.referenceMatch();
            columnReferenceMatch = directLeftScan.referenceMatch();
            haveNewMotionVectorMatch |= directLeftScan.haveNewMotionVectorMatch();
        }
        TemporalScanResult temporalScan = scanTemporalMotionField(
                nonNullPosition,
                nonNullSize,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                candidates,
                candidateCount
        );
        candidateCount = temporalScan.candidateCount();

        int secondaryTopSpanStart4 = secondaryScanSpanStart(x4, endX4);
        int secondaryLeftSpanStart4 = secondaryScanSpanStart(y4, endY4);
        int maxSecondaryTopOffsets = Math.min((y4 + 1) >> 1, 2 + (nonNullSize.height4() > 1 ? 1 : 0));
        for (int secondaryOffset = 2; secondaryOffset <= maxSecondaryTopOffsets; secondaryOffset++) {
            int secondaryRowY4 = secondarySpatialOffsetCoordinate(y4, secondaryOffset);
            if (secondaryRowY4 < 0) {
                break;
            }
            SpatialScanResult secondaryTopScan = scanStoredBlocksAlongSpan(
                    true,
                    secondaryRowY4,
                    secondaryTopSpanStart4,
                    endX4,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    secondarySpatialWeightPenalty(secondaryOffset),
                    candidates,
                    candidateCount
            );
            candidateCount = secondaryTopScan.candidateCount();
            rowReferenceMatch |= secondaryTopScan.referenceMatch();
            haveNewMotionVectorMatch |= secondaryTopScan.haveNewMotionVectorMatch();
        }
        int maxSecondaryLeftOffsets = Math.min((x4 + 1) >> 1, 2 + (nonNullSize.width4() > 1 ? 1 : 0));
        for (int secondaryOffset = 2; secondaryOffset <= maxSecondaryLeftOffsets; secondaryOffset++) {
            int secondaryColumnX4 = secondarySpatialOffsetCoordinate(x4, secondaryOffset);
            if (secondaryColumnX4 < 0) {
                break;
            }
            SpatialScanResult secondaryLeftScan = scanStoredBlocksAlongSpan(
                    false,
                    secondaryColumnX4,
                    secondaryLeftSpanStart4,
                    endY4,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    secondarySpatialWeightPenalty(secondaryOffset),
                    candidates,
                    candidateCount
            );
            candidateCount = secondaryLeftScan.candidateCount();
            columnReferenceMatch |= secondaryLeftScan.referenceMatch();
            haveNewMotionVectorMatch |= secondaryLeftScan.haveNewMotionVectorMatch();
        }
        if (y4 > 0 && endX4 < tileWidth4) {
            SpatialScanResult topRightScan = scanStoredBlocksAlongSpan(
                    true,
                    y4 - 1,
                    endX4,
                    Math.min(tileWidth4, endX4 + 1),
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    TOP_RIGHT_SPATIAL_WEIGHT_PENALTY,
                    candidates,
                    candidateCount
            );
            candidateCount = topRightScan.candidateCount();
            rowReferenceMatch |= topRightScan.referenceMatch();
            haveNewMotionVectorMatch |= topRightScan.haveNewMotionVectorMatch();
        }
        if (x4 > 0 && y4 > 0) {
            SpatialScanResult topLeftScan = scanStoredBlockCoordinate(
                    x4 - 1,
                    y4 - 1,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    TOP_LEFT_SPATIAL_WEIGHT_PENALTY,
                    candidates,
                    candidateCount
            );
            candidateCount = topLeftScan.candidateCount();
            rowReferenceMatch |= topLeftScan.referenceMatch();
            haveNewMotionVectorMatch |= topLeftScan.haveNewMotionVectorMatch();
        }

        sortDescending(candidates, candidateCount);
        RefMvsContextSummary refMvsContextSummary = summarizeDirectRefMvsContexts(
                (directRowMatch ? 1 : 0) + (directColumnMatch ? 1 : 0),
                (rowReferenceMatch ? 1 : 0) + (columnReferenceMatch ? 1 : 0),
                haveNewMotionVectorMatch
        );

        return new ProvisionalInterModeContext(
                refMvsContextSummary.singleNewMvContext(),
                temporalScan.globalMotionContext(),
                refMvsContextSummary.singleReferenceMvContext(),
                refMvsContextSummary.compoundInterModeContext(),
                Arrays.copyOf(candidates, candidateCount)
        );
    }

    /// Returns the odd-aligned coordinate used for one secondary spatial offset layer.
    ///
    /// @param coordinate4 the current block start coordinate in 4x4 units
    /// @param secondaryOffset the one-based secondary offset layer index starting at `2`
    /// @return the odd-aligned secondary scan coordinate in 4x4 units
    private static int secondarySpatialOffsetCoordinate(int coordinate4, int secondaryOffset) {
        return (coordinate4 - (secondaryOffset << 1) + 1) | 1;
    }

    /// Returns the start coordinate used when scanning secondary 8x8-resolution spans.
    ///
    /// The secondary spatial scan in `dav1d` samples odd-aligned coordinates inside the current
    /// block footprint. This helper keeps that odd alignment while still guaranteeing that narrow
    /// blocks contribute at least one sampled coordinate.
    ///
    /// @param spanStart4 the inclusive start of the current block span in 4x4 units
    /// @param spanEnd4 the exclusive end of the current block span in 4x4 units
    /// @return the odd-aligned start coordinate used by secondary spatial scans
    private static int secondaryScanSpanStart(int spanStart4, int spanEnd4) {
        return Math.min(spanEnd4 - 1, spanStart4 | 1);
    }

    /// Returns the weight penalty applied to one secondary spatial offset layer.
    ///
    /// @param secondaryOffset the one-based secondary offset layer index starting at `2`
    /// @return the weight penalty applied to the requested secondary spatial offset layer
    private static int secondarySpatialWeightPenalty(int secondaryOffset) {
        return SECONDARY_SPATIAL_WEIGHT_PENALTY
                + (secondaryOffset - 2) * SECONDARY_SPATIAL_WEIGHT_PENALTY_STEP;
    }

    /// Scans the tile-local temporal motion field for samples overlapping the current block footprint.
    ///
    /// This is still a reduced approximation of AV1 `refmvs`: it samples the tile-local temporal
    /// field in 8x8 units, includes the small-block fringe probes around the current footprint, and
    /// feeds matching candidates into the provisional motion-vector stack. It still does not yet
    /// perform full previous-frame projection.
    ///
    /// @param position the current block position
    /// @param size the current block size
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates already stored in `destination`
    /// @return the result of scanning the tile-local temporal motion field
    private TemporalScanResult scanTemporalMotionField(
            BlockPosition position,
            BlockSize size,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        if (!useReferenceFrameMotionVectors) {
            return new TemporalScanResult(count, 0);
        }
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        int startX8 = nonNullPosition.x8();
        int startY8 = nonNullPosition.y8();
        int endX8 = Math.min(temporalMotionField.width8(), (Math.min(tileWidth4, nonNullPosition.x4() + nonNullSize.width4()) + 1) >> 1);
        int endY8 = Math.min(temporalMotionField.height8(), (Math.min(tileHeight4, nonNullPosition.y4() + nonNullSize.height4()) + 1) >> 1);
        if (startX8 >= endX8 || startY8 >= endY8) {
            return new TemporalScanResult(count, 0);
        }

        int stepX8 = nonNullSize.width4() >= 16 ? 2 : 1;
        int stepY8 = nonNullSize.height4() >= 16 ? 2 : 1;
        TileDecodeContext.TemporalMotionBlock[] visitedBlocks =
                new TileDecodeContext.TemporalMotionBlock[Math.max(1, (endX8 - startX8) * (endY8 - startY8))];
        int visitedCount = 0;
        boolean nonZeroMotionVectorCandidate = false;
        for (int y8 = startY8; y8 < endY8; y8 += stepY8) {
            for (int x8 = startX8; x8 < endX8; x8 += stepX8) {
                TemporalSampleResult sample = sampleTemporalMotionFieldCoordinate(
                        x8,
                        y8,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        visitedBlocks,
                        visitedCount,
                        destination,
                        count
                );
                visitedCount = sample.visitedCount();
                count = sample.candidateCount();
                nonZeroMotionVectorCandidate |= sample.nonZeroMotionVectorCandidate();
            }
        }
        if (Math.min(nonNullSize.width4(), nonNullSize.height4()) >= 2
                && Math.max(nonNullSize.width4(), nonNullSize.height4()) < 16) {
            boolean hasBottom = endY8 < temporalMotionField.height8();
            if (hasBottom && startX8 > 0) {
                TemporalSampleResult bottomLeftSample = sampleTemporalMotionFieldCoordinate(
                        startX8 - 1,
                        endY8,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        visitedBlocks,
                        visitedCount,
                        destination,
                        count
                );
                visitedCount = bottomLeftSample.visitedCount();
                count = bottomLeftSample.candidateCount();
                nonZeroMotionVectorCandidate |= bottomLeftSample.nonZeroMotionVectorCandidate();
            }
            if (endX8 < temporalMotionField.width8()) {
                if (hasBottom) {
                    TemporalSampleResult bottomRightSample = sampleTemporalMotionFieldCoordinate(
                            endX8,
                            endY8,
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1,
                            visitedBlocks,
                            visitedCount,
                            destination,
                            count
                    );
                    visitedCount = bottomRightSample.visitedCount();
                    count = bottomRightSample.candidateCount();
                    nonZeroMotionVectorCandidate |= bottomRightSample.nonZeroMotionVectorCandidate();
                }
                TemporalSampleResult rightEdgeSample = sampleTemporalMotionFieldCoordinate(
                        endX8,
                        endY8 - 1,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        visitedBlocks,
                        visitedCount,
                        destination,
                        count
                );
                visitedCount = rightEdgeSample.visitedCount();
                count = rightEdgeSample.candidateCount();
                nonZeroMotionVectorCandidate |= rightEdgeSample.nonZeroMotionVectorCandidate();
            }
        }
        return new TemporalScanResult(count, nonZeroMotionVectorCandidate ? 1 : 0);
    }

    /// Samples one tile-local temporal motion-field coordinate and appends any matching candidate.
    ///
    /// @param x8 the tile-relative X coordinate in 8x8 units
    /// @param y8 the tile-relative Y coordinate in 8x8 units
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param visitedBlocks the temporal blocks that were already sampled for the current block
    /// @param visitedCount the number of active entries in `visitedBlocks`
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates already stored in `destination`
    /// @return the result of sampling one tile-local temporal motion-field coordinate
    private TemporalSampleResult sampleTemporalMotionFieldCoordinate(
            int x8,
            int y8,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            TileDecodeContext.TemporalMotionBlock[] visitedBlocks,
            int visitedCount,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        @Nullable TileDecodeContext.TemporalMotionBlock temporalBlock = temporalMotionField.block(x8, y8);
        if (temporalBlock == null
                || containsTemporalMotionBlock(visitedBlocks, visitedCount, temporalBlock)
                || !sharesAnyReference(
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                temporalBlock.compoundReference(),
                temporalBlock.referenceFrame0(),
                temporalBlock.referenceFrame1()
        )) {
            return new TemporalSampleResult(count, visitedCount, false);
        }
        visitedBlocks[visitedCount++] = temporalBlock;
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                provisionalMotionVectorCandidate(
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        temporalBlock.compoundReference(),
                        temporalBlock.referenceFrame0(),
                        temporalBlock.referenceFrame1(),
                        temporalBlock.motionVector0(),
                        temporalBlock.motionVector1() != null
                                ? temporalBlock.motionVector1()
                                : InterMotionVector.predicted(MotionVector.zero()),
                        TEMPORAL_MOTION_WEIGHT
                );
        count = appendOrAccumulateTemporalCandidate(destination, count, candidate);
        boolean nonZeroMotionVectorCandidate =
                !candidate.motionVector0().vector().equals(MotionVector.zero())
                        || candidate.motionVector1() != null && !candidate.motionVector1().vector().equals(MotionVector.zero());
        return new TemporalSampleResult(count, visitedCount, nonZeroMotionVectorCandidate);
    }

    /// Returns the temporal segmentation-prediction context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the temporal segmentation-prediction context for the supplied block position
    public int segmentPredictionContext(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int context = 0;
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSegmentPredicted[nonNullPosition.x4()];
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSegmentPredicted[nonNullPosition.y4()];
        }
        return context;
    }

    /// Returns the current-frame predicted segment identifier and context for the supplied block position.
    ///
    /// @param position the current block position
    /// @return the current-frame predicted segment identifier and context
    public SegmentPrediction currentSegmentPrediction(BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        boolean haveTop = hasTopNeighbor(nonNullPosition);
        boolean haveLeft = hasLeftNeighbor(nonNullPosition);
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        if (haveLeft && haveTop) {
            int left = leftSegmentId[y4] & 0xFF;
            int above = aboveSegmentId[x4] & 0xFF;
            int aboveLeft = aboveSegmentId[x4 - 1] & 0xFF;
            int context;
            if (left == above && aboveLeft == left) {
                context = 2;
            } else if (left == above || aboveLeft == left || above == aboveLeft) {
                context = 1;
            } else {
                context = 0;
            }
            return new SegmentPrediction(above == aboveLeft ? above : left, context);
        }

        int predictedSegmentId = haveLeft
                ? leftSegmentId[y4] & 0xFF
                : haveTop
                ? aboveSegmentId[x4] & 0xFF
                : 0;
        return new SegmentPrediction(predictedSegmentId, 0);
    }

    /// Returns the above-edge luma palette size for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge luma palette size for the supplied X coordinate in 4x4 units
    public int abovePaletteSize(int x4) {
        return abovePaletteSize[x4] & 0xFF;
    }

    /// Returns the left-edge luma palette size for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge luma palette size for the supplied Y coordinate in 4x4 units
    public int leftPaletteSize(int y4) {
        return leftPaletteSize[y4] & 0xFF;
    }

    /// Returns the above-edge chroma palette size for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge chroma palette size for the supplied X coordinate in 4x4 units
    public int aboveChromaPaletteSize(int x4) {
        return aboveChromaPaletteSize[x4] & 0xFF;
    }

    /// Returns the left-edge chroma palette size for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge chroma palette size for the supplied Y coordinate in 4x4 units
    public int leftChromaPaletteSize(int y4) {
        return leftChromaPaletteSize[y4] & 0xFF;
    }

    /// Returns one above-edge palette entry for the supplied plane, X coordinate, and palette index.
    ///
    /// @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
    /// @param x4 the X coordinate in 4x4 units
    /// @param index the zero-based palette entry index in `[0, 8)`
    /// @return one above-edge palette entry for the supplied plane and coordinate
    public int abovePaletteEntry(int plane, int x4, int index) {
        return abovePaletteEntries[Objects.checkIndex(plane, abovePaletteEntries.length)][x4][Objects.checkIndex(index, 8)];
    }

    /// Returns one left-edge palette entry for the supplied plane, Y coordinate, and palette index.
    ///
    /// @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
    /// @param y4 the Y coordinate in 4x4 units
    /// @param index the zero-based palette entry index in `[0, 8)`
    /// @return one left-edge palette entry for the supplied plane and coordinate
    public int leftPaletteEntry(int plane, int y4, int index) {
        return leftPaletteEntries[Objects.checkIndex(plane, leftPaletteEntries.length)][y4][Objects.checkIndex(index, 8)];
    }

    /// Returns the above-edge luma mode for the supplied X coordinate in 4x4 units.
    ///
    /// @param x4 the X coordinate in 4x4 units
    /// @return the above-edge luma mode for the supplied X coordinate in 4x4 units
    public LumaIntraPredictionMode aboveMode(int x4) {
        return aboveMode[x4];
    }

    /// Returns the left-edge luma mode for the supplied Y coordinate in 4x4 units.
    ///
    /// @param y4 the Y coordinate in 4x4 units
    /// @return the left-edge luma mode for the supplied Y coordinate in 4x4 units
    public LumaIntraPredictionMode leftMode(int y4) {
        return leftMode[y4];
    }

    /// Returns the partition context for the supplied block-level shift and block position.
    ///
    /// @param partitionShift the partition-bit shift derived from the current square block level
    /// @param position the current block position
    /// @return the partition context for the supplied block-level shift and block position
    public int partitionContext(int partitionShift, BlockPosition position) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int x8 = nonNullPosition.x8();
        int y8 = nonNullPosition.y8();
        return ((abovePartition[x8] >> partitionShift) & 1) + (((leftPartition[y8] >> partitionShift) & 1) << 1);
    }

    /// Returns the transform-size context for one intra-like block and maximum luma transform size.
    ///
    /// This matches the `dav1d` `get_tx_ctx()` rule that compares the stored top and left
    /// transform-context dimensions against the currently allowed maximum transform size.
    ///
    /// @param position the current block position
    /// @param maxTransformSize the largest luma transform size allowed for the current block
    /// @return the transform-size context for one intra-like block
    public int transformSizeContext(BlockPosition position, TransformSize maxTransformSize) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullMaxTransformSize = Objects.requireNonNull(maxTransformSize, "maxTransformSize");
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        return (leftTransformHeightLog2[y4] >= nonNullMaxTransformSize.log2Height4() ? 1 : 0)
                + (aboveTransformWidthLog2[x4] >= nonNullMaxTransformSize.log2Width4() ? 1 : 0);
    }

    /// Returns the inter var-tx split context for one transform region and maximum transform size.
    ///
    /// This matches `dav1d`'s `read_tx_tree()` rule that compares the current top and left inter
    /// transform-context dimensions against the transform width and height being split.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param transformSize the transform size currently being considered for splitting
    /// @return the inter var-tx split context in `[0, 3)`
    public int interTransformSplitContext(BlockPosition position, TransformSize transformSize) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int x4 = nonNullPosition.x4();
        int y4 = nonNullPosition.y4();
        return (aboveInterTransformWidthLog2[x4] < nonNullTransformSize.log2Width4() ? 1 : 0)
                + (leftInterTransformHeightLog2[y4] < nonNullTransformSize.log2Height4() ? 1 : 0);
    }

    /// Returns the luma coefficient skip-context for one transform unit.
    ///
    /// This matches the luma-only `dav1d` `get_skip_ctx()` path by merging the already stored top
    /// and left coefficient-context bytes across the current transform span and mapping the merged
    /// counts through the `dav1d_skip_ctx` table. Chroma-specific contexts are still out of scope.
    ///
    /// @param blockSize the coded block size that owns the current transform unit
    /// @param transformUnit the current luma transform unit
    /// @return the luma coefficient skip-context in `[0, 7)`
    public int lumaCoefficientSkipContext(BlockSize blockSize, TransformUnit transformUnit) {
        BlockSize nonNullBlockSize = Objects.requireNonNull(blockSize, "blockSize");
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        TransformSize transformSize = nonNullTransformUnit.size();
        if (nonNullBlockSize.width4() == transformSize.width4() && nonNullBlockSize.height4() == transformSize.height4()) {
            return 0;
        }

        int aboveContext = mergeCoefficientContext(
                aboveLumaCoefficientContext,
                nonNullTransformUnit.position().x4(),
                transformSize.width4()
        );
        int leftContext = mergeCoefficientContext(
                leftLumaCoefficientContext,
                nonNullTransformUnit.position().y4(),
                transformSize.height4()
        );
        return LUMA_COEFFICIENT_SKIP_CONTEXTS[Math.min(aboveContext & 0x3F, 4)][Math.min(leftContext & 0x3F, 4)];
    }

    /// Returns the luma DC-sign context for one transform unit.
    ///
    /// This follows `dav1d`'s `get_dc_sign_ctx()` rule by merging the sign classes stored in the
    /// current top and left coefficient-context bytes across the visible transform span.
    ///
    /// @param transformUnit the current luma transform unit
    /// @return the luma DC-sign context in `[0, 3)`
    public int lumaDcSignContext(TransformUnit transformUnit) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        TransformSize transformSize = nonNullTransformUnit.size();
        int signBalance = sumDcSignClasses(
                aboveLumaCoefficientContext,
                nonNullTransformUnit.position().x4(),
                transformSize.width4()
        ) + sumDcSignClasses(
                leftLumaCoefficientContext,
                nonNullTransformUnit.position().y4(),
                transformSize.height4()
        );
        return (signBalance != 0 ? 1 : 0) + (signBalance > 0 ? 1 : 0);
    }

    /// Updates the default transform-context dimensions after one block header is decoded.
    ///
    /// Inter blocks use their coded block dimensions for subsequent transform-size contexts, which
    /// matches `dav1d`'s `tx_intra` edge-state updates. Intra blocks may later override this with
    /// the chosen luma transform size once transform syntax has been read.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    public void updateDefaultTransformContext(BlockPosition position, BlockSize size) {
        updateTransformContext(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(size, "size"),
                size.log2Width4(),
                size.log2Height4()
        );
    }

    /// Updates the transform-size context after one intra-like block chooses its luma transform size.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    /// @param transformSize the chosen luma transform size
    public void updateIntraTransformContext(BlockPosition position, BlockSize size, TransformSize transformSize) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        updateTransformContext(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(size, "size"),
                nonNullTransformSize.log2Width4(),
                nonNullTransformSize.log2Height4()
        );
    }

    /// Updates the inter var-tx context for one transform region.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param width4 the transform-region width in 4x4 units
    /// @param height4 the transform-region height in 4x4 units
    /// @param transformSize the chosen transform size for the current region
    public void updateInterTransformContext(
            BlockPosition position,
            int width4,
            int height4,
            TransformSize transformSize
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        updateInterTransformContext(
                Objects.requireNonNull(position, "position"),
                width4,
                height4,
                nonNullTransformSize.log2Width4(),
                nonNullTransformSize.log2Height4()
        );
    }

    /// Updates the inter var-tx context for one region using raw width/height log2 values.
    ///
    /// This variant is used when switchable inter transform mode stores the coded block dimensions
    /// rather than a legal transform enum, such as 128x128 superblocks.
    ///
    /// @param position the local tile-relative origin of the current region
    /// @param width4 the region width in 4x4 units
    /// @param height4 the region height in 4x4 units
    /// @param widthLog2 the stored width in `log2(4x4 units)`
    /// @param heightLog2 the stored height in `log2(4x4 units)`
    public void updateInterTransformContext(
            BlockPosition position,
            int width4,
            int height4,
            int widthLog2,
            int heightLog2
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int endX4 = Math.min(tileWidth4, nonNullPosition.x4() + width4);
        int endY4 = Math.min(tileHeight4, nonNullPosition.y4() + height4);
        for (int x4 = nonNullPosition.x4(); x4 < endX4; x4++) {
            aboveInterTransformWidthLog2[x4] = (byte) widthLog2;
        }
        for (int y4 = nonNullPosition.y4(); y4 < endY4; y4++) {
            leftInterTransformHeightLog2[y4] = (byte) heightLog2;
        }
    }

    /// Updates the luma coefficient-context state after one transform residual unit is decoded.
    ///
    /// @param transformUnit the decoded luma transform residual unit
    /// @param coefficientContextByte the coefficient-context byte written back for the decoded unit
    public void updateLumaCoefficientContext(TransformUnit transformUnit, int coefficientContextByte) {
        TransformUnit nonNullTransformUnit = Objects.requireNonNull(transformUnit, "transformUnit");
        if (coefficientContextByte < 0 || coefficientContextByte > 0xFF) {
            throw new IllegalArgumentException("coefficientContextByte out of range: " + coefficientContextByte);
        }
        BlockPosition position = nonNullTransformUnit.position();
        TransformSize transformSize = nonNullTransformUnit.size();
        int endX4 = Math.min(tileWidth4, position.x4() + transformSize.width4());
        int endY4 = Math.min(tileHeight4, position.y4() + transformSize.height4());
        byte storedValue = (byte) coefficientContextByte;
        for (int x4 = position.x4(); x4 < endX4; x4++) {
            aboveLumaCoefficientContext[x4] = storedValue;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            leftLumaCoefficientContext[y4] = storedValue;
        }
    }

    /// Writes one transform-context width/height pair across the visible edges of one block span.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded coded block size
    /// @param widthLog2 the transform-context width in `log2(4x4 units)`
    /// @param heightLog2 the transform-context height in `log2(4x4 units)`
    private void updateTransformContext(BlockPosition position, BlockSize size, int widthLog2, int heightLog2) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        int endX4 = Math.min(tileWidth4, nonNullPosition.x4() + nonNullSize.width4());
        int endY4 = Math.min(tileHeight4, nonNullPosition.y4() + nonNullSize.height4());
        for (int x4 = nonNullPosition.x4(); x4 < endX4; x4++) {
            aboveTransformWidthLog2[x4] = (byte) widthLog2;
        }
        for (int y4 = nonNullPosition.y4(); y4 < endY4; y4++) {
            leftTransformHeightLog2[y4] = (byte) heightLog2;
        }
    }

    /// Merges one coefficient-context span by OR-ing the stored bytes across the visible edge range.
    ///
    /// @param contexts the stored coefficient-context edge bytes
    /// @param start the inclusive start coordinate in 4x4 units
    /// @param span the requested span length in 4x4 units
    /// @return the merged coefficient-context byte across the requested visible edge range
    private static int mergeCoefficientContext(byte[] contexts, int start, int span) {
        int end = Math.min(contexts.length, start + span);
        int merged = ALL_ZERO_COEFFICIENT_CONTEXT_BYTE;
        for (int index = start; index < end; index++) {
            merged |= contexts[index] & 0xFF;
        }
        return merged;
    }

    /// Sums the stored DC-sign classes across one visible coefficient-context edge span.
    ///
    /// Stored coefficient-context bytes encode negative values as class `0`, all-zero as class `1`,
    /// and positive values as class `2`. This helper converts the stored bytes back into the
    /// signed balance used by `dav1d`'s `get_dc_sign_ctx()`.
    ///
    /// @param contexts the stored coefficient-context edge bytes
    /// @param start the inclusive start coordinate in 4x4 units
    /// @param span the requested span length in 4x4 units
    /// @return the signed DC-sign balance across the requested visible edge range
    private static int sumDcSignClasses(byte[] contexts, int start, int span) {
        int end = Math.min(contexts.length, start + span);
        int sum = 0;
        for (int index = start; index < end; index++) {
            sum += ((contexts[index] & 0xFF) >>> 6) - 1;
        }
        return sum;
    }

    /// Updates the neighbor state after decoding one block header.
    ///
    /// @param header the decoded block header that should become the new above/left edge state
    public void updateFromBlockHeader(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        BlockPosition position = nonNullHeader.position();
        BlockSize size = nonNullHeader.size();
        byte intra = (byte) ((nonNullHeader.intra() || nonNullHeader.useIntrabc()) ? 1 : 0);
        byte skip = (byte) (nonNullHeader.skip() ? 1 : 0);
        byte skipMode = (byte) (nonNullHeader.skipMode() ? 1 : 0);
        byte compoundReference = (byte) (nonNullHeader.compoundReference() ? 1 : 0);
        byte segmentPredicted = (byte) (nonNullHeader.segmentPredicted() ? 1 : 0);
        byte segmentId = (byte) nonNullHeader.segmentId();
        byte paletteSize = (byte) nonNullHeader.yPaletteSize();
        byte chromaPaletteSize = (byte) nonNullHeader.uvPaletteSize();
        int[] yPaletteColors = nonNullHeader.yPaletteColors();
        int[] uPaletteColors = nonNullHeader.uPaletteColors();
        int[] vPaletteColors = nonNullHeader.vPaletteColors();
        byte referenceFrame0 = (byte) nonNullHeader.referenceFrame0();
        byte referenceFrame1 = (byte) nonNullHeader.referenceFrame1();
        InterMotionVector motionVector0 = fallbackMotionVector(nonNullHeader.motionVector0());
        InterMotionVector motionVector1 = fallbackMotionVector(nonNullHeader.motionVector1());
        byte usesNewMotionVector = (byte) (usesNewMotionVector(nonNullHeader) ? 1 : 0);
        LumaIntraPredictionMode mode = nonNullHeader.intra() ? nonNullHeader.yMode() : LumaIntraPredictionMode.DC;
        int endX4 = Math.min(tileWidth4, position.x4() + size.width4());
        int endY4 = Math.min(tileHeight4, position.y4() + size.height4());
        StoredBlock storedBlock = new StoredBlock(
                position.x4(),
                position.y4(),
                size.width4(),
                size.height4(),
                intra != 0,
                compoundReference != 0,
                referenceFrame0,
                referenceFrame1,
                motionVector0,
                motionVector1,
                usesNewMotionVector != 0
        );
        for (int x4 = position.x4(); x4 < endX4; x4++) {
            aboveIntra[x4] = intra;
            aboveSkip[x4] = skip;
            aboveSkipMode[x4] = skipMode;
            aboveCompoundReference[x4] = compoundReference;
            aboveReferenceFrame0[x4] = referenceFrame0;
            aboveReferenceFrame1[x4] = referenceFrame1;
            aboveMotionVector0[x4] = motionVector0;
            aboveMotionVector1[x4] = motionVector1;
            aboveUsesNewMotionVector[x4] = usesNewMotionVector;
            aboveSegmentPredicted[x4] = segmentPredicted;
            aboveSegmentId[x4] = segmentId;
            abovePaletteSize[x4] = paletteSize;
            aboveChromaPaletteSize[x4] = chromaPaletteSize;
            Arrays.fill(abovePaletteEntries[0][x4], 0);
            Arrays.fill(abovePaletteEntries[1][x4], 0);
            Arrays.fill(abovePaletteEntries[2][x4], 0);
            System.arraycopy(yPaletteColors, 0, abovePaletteEntries[0][x4], 0, yPaletteColors.length);
            System.arraycopy(uPaletteColors, 0, abovePaletteEntries[1][x4], 0, uPaletteColors.length);
            System.arraycopy(vPaletteColors, 0, abovePaletteEntries[2][x4], 0, vPaletteColors.length);
            aboveMode[x4] = mode;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            leftIntra[y4] = intra;
            leftSkip[y4] = skip;
            leftSkipMode[y4] = skipMode;
            leftCompoundReference[y4] = compoundReference;
            leftReferenceFrame0[y4] = referenceFrame0;
            leftReferenceFrame1[y4] = referenceFrame1;
            leftMotionVector0[y4] = motionVector0;
            leftMotionVector1[y4] = motionVector1;
            leftUsesNewMotionVector[y4] = usesNewMotionVector;
            leftSegmentPredicted[y4] = segmentPredicted;
            leftSegmentId[y4] = segmentId;
            leftPaletteSize[y4] = paletteSize;
            leftChromaPaletteSize[y4] = chromaPaletteSize;
            Arrays.fill(leftPaletteEntries[0][y4], 0);
            Arrays.fill(leftPaletteEntries[1][y4], 0);
            Arrays.fill(leftPaletteEntries[2][y4], 0);
            System.arraycopy(yPaletteColors, 0, leftPaletteEntries[0][y4], 0, yPaletteColors.length);
            System.arraycopy(uPaletteColors, 0, leftPaletteEntries[1][y4], 0, uPaletteColors.length);
            System.arraycopy(vPaletteColors, 0, leftPaletteEntries[2][y4], 0, vPaletteColors.length);
            leftMode[y4] = mode;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            for (int x4 = position.x4(); x4 < endX4; x4++) {
                storedBlocks[blockIndex(x4, y4)] = storedBlock;
            }
        }
        updateDecodedTemporalMotionField(nonNullHeader, endX4, endY4, motionVector0, motionVector1);
    }

    /// Updates the current-frame temporal motion field with one decoded block header.
    ///
    /// This write-back path intentionally stays separate from the reference temporal field used for
    /// the current frame's `refmvs` lookup. The stored samples therefore become available only to
    /// later pipeline stages or future frames, instead of feeding back into the same frame.
    ///
    /// @param header the decoded block header that should be projected into the current-frame temporal field
    /// @param endX4 the exclusive end X coordinate of the decoded block in 4x4 units
    /// @param endY4 the exclusive end Y coordinate of the decoded block in 4x4 units
    /// @param motionVector0 the normalized primary motion-vector state chosen for the block
    /// @param motionVector1 the normalized secondary motion-vector state chosen for the block
    private void updateDecodedTemporalMotionField(
            TileBlockHeaderReader.BlockHeader header,
            int endX4,
            int endY4,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        int startX8 = nonNullHeader.position().x8();
        int startY8 = nonNullHeader.position().y8();
        int endX8 = Math.min(decodedTemporalMotionField.width8(), (endX4 + 1) >> 1);
        int endY8 = Math.min(decodedTemporalMotionField.height8(), (endY4 + 1) >> 1);
        if (startX8 >= endX8 || startY8 >= endY8) {
            return;
        }

        @Nullable TileDecodeContext.TemporalMotionBlock temporalMotionBlock = createDecodedTemporalMotionBlock(
                nonNullHeader,
                motionVector0,
                motionVector1
        );
        for (int y8 = startY8; y8 < endY8; y8++) {
            for (int x8 = startX8; x8 < endX8; x8++) {
                if (temporalMotionBlock == null) {
                    decodedTemporalMotionField.clearBlock(x8, y8);
                } else {
                    decodedTemporalMotionField.setBlock(x8, y8, temporalMotionBlock);
                }
            }
        }
    }

    /// Creates the temporal motion-field sample contributed by one decoded block header, or `null`.
    ///
    /// Intra and `intrabc` blocks do not contribute temporal motion samples. Inter samples are
    /// stored with the already normalized motion-vector states carried by the block header.
    ///
    /// @param header the decoded block header
    /// @param motionVector0 the normalized primary motion-vector state chosen for the block
    /// @param motionVector1 the normalized secondary motion-vector state chosen for the block
    /// @return the temporal motion-field sample contributed by the block, or `null`
    private static @Nullable TileDecodeContext.TemporalMotionBlock createDecodedTemporalMotionBlock(
            TileBlockHeaderReader.BlockHeader header,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        if (nonNullHeader.intra() || nonNullHeader.useIntrabc() || nonNullHeader.referenceFrame0() < 0) {
            return null;
        }
        if (nonNullHeader.compoundReference()) {
            if (nonNullHeader.referenceFrame1() < 0) {
                return null;
            }
            return TileDecodeContext.TemporalMotionBlock.compoundReference(
                    nonNullHeader.referenceFrame0(),
                    nonNullHeader.referenceFrame1(),
                    Objects.requireNonNull(motionVector0, "motionVector0"),
                    Objects.requireNonNull(motionVector1, "motionVector1")
            );
        }
        return TileDecodeContext.TemporalMotionBlock.singleReference(
                nonNullHeader.referenceFrame0(),
                Objects.requireNonNull(motionVector0, "motionVector0")
        );
    }

    /// Returns the flattened stored-block index for one tile-relative 4x4 coordinate.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @return the flattened stored-block index for one tile-relative 4x4 coordinate
    private int blockIndex(int x4, int y4) {
        return y4 * tileWidth4 + x4;
    }

    /// Returns the stored decoded block covering one tile-relative 4x4 coordinate, or `null`.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @return the stored decoded block covering one tile-relative 4x4 coordinate, or `null`
    private @Nullable StoredBlock storedBlockAt(int x4, int y4) {
        return storedBlocks[blockIndex(x4, y4)];
    }

    /// Returns one stored edge motion vector, falling back to a provisional zero vector.
    ///
    /// @param motionVector the stored motion vector, or `null`
    /// @return one stored edge motion vector, falling back to a provisional zero vector
    private static InterMotionVector fallbackMotionVector(@Nullable InterMotionVector motionVector) {
        return motionVector != null ? motionVector : InterMotionVector.predicted(MotionVector.zero());
    }

    /// Returns whether one decoded block used any `NEWMV`-carrying inter mode.
    ///
    /// @param header the decoded block header
    /// @return whether the decoded block used any `NEWMV`-carrying inter mode
    private static boolean usesNewMotionVector(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        if (nonNullHeader.singleInterMode() != null) {
            return nonNullHeader.singleInterMode() == org.glavo.avif.internal.av1.model.SingleInterPredictionMode.NEWMV;
        }
        if (nonNullHeader.compoundInterMode() == null) {
            return false;
        }
        return switch (nonNullHeader.compoundInterMode()) {
            case NEARESTMV_NEARESTMV, NEARMV_NEARMV, GLOBALMV_GLOBALMV -> false;
            case NEARESTMV_NEWMV, NEWMV_NEARESTMV, NEARMV_NEWMV, NEWMV_NEARMV, NEWMV_NEWMV -> true;
        };
    }

    /// Returns whether one stored neighbor uses a unidirectional compound reference pair.
    ///
    /// @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
    /// @param index the edge index in 4x4 units
    /// @return whether one stored neighbor uses a unidirectional compound reference pair
    private boolean hasUnidirectionalCompoundReference(boolean leftEdge, int index) {
        int referenceFrame0 = leftEdge ? leftReferenceFrame0[index] : aboveReferenceFrame0[index];
        int referenceFrame1 = leftEdge ? leftReferenceFrame1[index] : aboveReferenceFrame1[index];
        return (referenceFrame0 < 4) == (referenceFrame1 < 4);
    }

    /// Accumulates forward-vs-backward reference counts from already-decoded neighbors.
    ///
    /// @param count the two-entry destination array for forward and backward reference counts
    /// @param includeIntra whether intra-coded neighbors should also contribute
    /// @param position the current block position
    private void accumulateForwardBackwardCounts(int[] count, boolean includeIntra, BlockPosition position) {
        int x4 = position.x4();
        int y4 = position.y4();
        if (hasTopNeighbor(position) && (includeIntra || aboveIntra[x4] == 0)) {
            count[(aboveReferenceFrame0[x4] & 0xFF) >= 4 ? 1 : 0]++;
            if (aboveCompoundReference[x4] != 0) {
                count[(aboveReferenceFrame1[x4] & 0xFF) >= 4 ? 1 : 0]++;
            }
        }
        if (hasLeftNeighbor(position) && (includeIntra || leftIntra[y4] == 0)) {
            count[(leftReferenceFrame0[y4] & 0xFF) >= 4 ? 1 : 0]++;
            if (leftCompoundReference[y4] != 0) {
                count[(leftReferenceFrame1[y4] & 0xFF) >= 4 ? 1 : 0]++;
            }
        }
    }

    /// Accumulates reference counts for one contiguous range of reference-frame indices.
    ///
    /// @param count the destination count array whose length equals the tracked range length
    /// @param position the current block position
    /// @param startReference the inclusive first tracked reference-frame index
    /// @param length the number of tracked reference-frame indices
    private void accumulateReferenceCounts(int[] count, BlockPosition position, int startReference, int length) {
        int x4 = position.x4();
        int y4 = position.y4();
        if (hasTopNeighbor(position) && aboveIntra[x4] == 0) {
            incrementReferenceCount(count, aboveReferenceFrame0[x4], startReference, length);
            if (aboveCompoundReference[x4] != 0) {
                incrementReferenceCount(count, aboveReferenceFrame1[x4], startReference, length);
            }
        }
        if (hasLeftNeighbor(position) && leftIntra[y4] == 0) {
            incrementReferenceCount(count, leftReferenceFrame0[y4], startReference, length);
            if (leftCompoundReference[y4] != 0) {
                incrementReferenceCount(count, leftReferenceFrame1[y4], startReference, length);
            }
        }
    }

    /// Increments one counted reference bucket when the supplied stored reference falls in range.
    ///
    /// @param count the destination count array
    /// @param referenceFrame the stored reference-frame index
    /// @param startReference the inclusive first tracked reference-frame index
    /// @param length the number of tracked reference-frame indices
    private static void incrementReferenceCount(int[] count, int referenceFrame, int startReference, int length) {
        int index = referenceFrame - startReference;
        if (index >= 0 && index < length) {
            count[index]++;
        }
    }

    /// Scans one bounded spatial row or column in the stored block map.
    ///
    /// The scan is bounded to one far row or one far column and therefore remains smaller than a
    /// full AV1 `refmvs` walk. Each unique stored block is visited at most once per scan.
    ///
    /// @param rowScan whether the scan walks a fixed row instead of a fixed column
    /// @param fixedCoordinate4 the fixed row or column coordinate in 4x4 units
    /// @param spanStart4 the inclusive start of the scanned span on the varying axis
    /// @param spanEnd4 the exclusive end of the scanned span on the varying axis
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates already stored in `destination`
    /// @param weightPenalty the candidate-weight penalty applied to the scanned span
    /// @return the result of scanning one bounded spatial row or column
    private SpatialScanResult scanStoredBlocksAlongSpan(
            boolean rowScan,
            int fixedCoordinate4,
            int spanStart4,
            int spanEnd4,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            int weightPenalty,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        StoredBlock[] visitedBlocks = new StoredBlock[Math.max(1, spanEnd4 - spanStart4)];
        int visitedCount = 0;
        boolean referenceMatch = false;
        boolean haveNewMotionVectorMatch = false;
        for (int varyingCoordinate4 = spanStart4; varyingCoordinate4 < spanEnd4; varyingCoordinate4++) {
            @Nullable StoredBlock storedBlock = rowScan
                    ? storedBlockAt(varyingCoordinate4, fixedCoordinate4)
                    : storedBlockAt(fixedCoordinate4, varyingCoordinate4);
            if (storedBlock == null || storedBlock.intra() || containsStoredBlock(visitedBlocks, visitedCount, storedBlock)) {
                continue;
            }
            visitedBlocks[visitedCount++] = storedBlock;
            boolean blockReferenceMatch = sharesAnyReference(
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    storedBlock.compoundReference(),
                    storedBlock.referenceFrame0(),
                    storedBlock.referenceFrame1()
            );
            int baseWeight = provisionalNeighborWeight(
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    storedBlock.compoundReference(),
                    storedBlock.referenceFrame0(),
                    storedBlock.referenceFrame1()
            );
            int weight = Math.max(128, baseWeight - weightPenalty);
            count = appendProvisionalCandidateWeights(
                    destination,
                    count,
                    provisionalMotionVectorCandidate(
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1,
                            storedBlock.compoundReference(),
                            storedBlock.referenceFrame0(),
                            storedBlock.referenceFrame1(),
                            storedBlock.motionVector0(),
                            storedBlock.motionVector1(),
                            weight
                    )
            );
            referenceMatch |= blockReferenceMatch;
            if (blockReferenceMatch) {
                haveNewMotionVectorMatch |= storedBlock.usesNewMotionVector();
            }
        }
        return new SpatialScanResult(count, referenceMatch, haveNewMotionVectorMatch);
    }

    /// Scans one stored block at a single tile-relative 4x4 coordinate.
    ///
    /// @param x4 the tile-relative X coordinate in 4x4 units
    /// @param y4 the tile-relative Y coordinate in 4x4 units
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param weightPenalty the candidate-weight penalty applied to the scanned block
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates already stored in `destination`
    /// @return the result of scanning one stored block at a single tile-relative 4x4 coordinate
    private SpatialScanResult scanStoredBlockCoordinate(
            int x4,
            int y4,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            int weightPenalty,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count
    ) {
        @Nullable StoredBlock storedBlock = storedBlockAt(x4, y4);
        if (storedBlock == null || storedBlock.intra()) {
            return new SpatialScanResult(count, false, false);
        }
        boolean referenceMatch = sharesAnyReference(
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                storedBlock.compoundReference(),
                storedBlock.referenceFrame0(),
                storedBlock.referenceFrame1()
        );
        int baseWeight = provisionalNeighborWeight(
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                storedBlock.compoundReference(),
                storedBlock.referenceFrame0(),
                storedBlock.referenceFrame1()
        );
        int weight = Math.max(128, baseWeight - weightPenalty);
        count = appendProvisionalCandidateWeights(
                destination,
                count,
                provisionalMotionVectorCandidate(
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        storedBlock.compoundReference(),
                        storedBlock.referenceFrame0(),
                        storedBlock.referenceFrame1(),
                        storedBlock.motionVector0(),
                        storedBlock.motionVector1(),
                        weight
                )
        );
        return new SpatialScanResult(count, referenceMatch, referenceMatch && storedBlock.usesNewMotionVector());
    }

    /// Returns whether one stored-block list prefix already contains the supplied block instance.
    ///
    /// @param values the scanned stored-block list prefix
    /// @param count the number of active stored blocks at the front of `values`
    /// @param expected the stored block to search for
    /// @return whether one stored-block list prefix already contains the supplied block instance
    private static boolean containsStoredBlock(StoredBlock[] values, int count, StoredBlock expected) {
        StoredBlock nonNullExpected = Objects.requireNonNull(expected, "expected");
        for (int i = 0; i < count; i++) {
            if (values[i] == nonNullExpected) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether one temporal-motion-block list prefix already contains the supplied block instance.
    ///
    /// @param values the scanned temporal-motion-block list prefix
    /// @param count the number of active temporal motion blocks at the front of `values`
    /// @param expected the temporal motion block to search for
    /// @return whether one temporal-motion-block list prefix already contains the supplied block instance
    private static boolean containsTemporalMotionBlock(
            TileDecodeContext.TemporalMotionBlock[] values,
            int count,
            TileDecodeContext.TemporalMotionBlock expected
    ) {
        TileDecodeContext.TemporalMotionBlock nonNullExpected = Objects.requireNonNull(expected, "expected");
        for (int i = 0; i < count; i++) {
            if (values[i] == nonNullExpected) {
                return true;
            }
        }
        return false;
    }

    /// Computes one provisional neighbor weight for inter-mode context derivation.
    ///
    /// Exact reference matches receive the highest weight, partial reference overlap receives a
    /// medium weight, matching forward/backward direction receives a weaker weight, and unrelated
    /// inter neighbors receive the weakest retained weight.
    ///
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param neighborCompound whether the stored neighbor uses compound references
    /// @param neighborReferenceFrame0 the neighbor primary reference
    /// @param neighborReferenceFrame1 the neighbor secondary reference, or `-1`
    /// @return the provisional neighbor weight
    private static int provisionalNeighborWeight(
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            boolean neighborCompound,
            int neighborReferenceFrame0,
            int neighborReferenceFrame1
    ) {
        if (compoundReference == neighborCompound
                && referenceFrame0 == neighborReferenceFrame0
                && referenceFrame1 == neighborReferenceFrame1) {
            return 640;
        }
        if (sharesAnyReference(
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                neighborCompound,
                neighborReferenceFrame0,
                neighborReferenceFrame1
        )) {
            return compoundReference == neighborCompound ? 512 : 448;
        }
        return referenceDirection(referenceFrame0) == referenceDirection(neighborReferenceFrame0) ? 384 : 256;
    }

    /// Builds one provisional motion-vector candidate from one stored neighbor.
    ///
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param neighborCompound whether the stored neighbor uses compound references
    /// @param neighborReferenceFrame0 the neighbor primary reference
    /// @param neighborReferenceFrame1 the neighbor secondary reference, or `-1`
    /// @param neighborMotionVector0 the stored neighbor primary motion vector
    /// @param neighborMotionVector1 the stored neighbor secondary motion vector
    /// @param weight the provisional candidate weight to assign
    /// @return one provisional motion-vector candidate from one stored neighbor
    private static ProvisionalInterModeContext.ProvisionalMotionVectorCandidate provisionalMotionVectorCandidate(
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            boolean neighborCompound,
            int neighborReferenceFrame0,
            int neighborReferenceFrame1,
            InterMotionVector neighborMotionVector0,
            InterMotionVector neighborMotionVector1,
            int weight
    ) {
        InterMotionVector motionVector0 = matchingMotionVector(
                referenceFrame0,
                neighborCompound,
                neighborReferenceFrame0,
                neighborReferenceFrame1,
                Objects.requireNonNull(neighborMotionVector0, "neighborMotionVector0"),
                Objects.requireNonNull(neighborMotionVector1, "neighborMotionVector1")
        );
        @Nullable InterMotionVector motionVector1 = compoundReference
                ? matchingMotionVector(
                referenceFrame1,
                neighborCompound,
                neighborReferenceFrame0,
                neighborReferenceFrame1,
                neighborMotionVector0,
                neighborMotionVector1
        )
                : null;
        return new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(weight, motionVector0, motionVector1, false);
    }

    /// Returns the best provisional motion vector contributed by one stored neighbor for one reference.
    ///
    /// @param referenceFrame the requested current-block reference-frame index
    /// @param neighborCompound whether the stored neighbor uses compound references
    /// @param neighborReferenceFrame0 the neighbor primary reference-frame index
    /// @param neighborReferenceFrame1 the neighbor secondary reference-frame index, or `-1`
    /// @param neighborMotionVector0 the stored neighbor primary motion vector
    /// @param neighborMotionVector1 the stored neighbor secondary motion vector
    /// @return the best provisional motion vector contributed by one stored neighbor for one reference
    private static InterMotionVector matchingMotionVector(
            int referenceFrame,
            boolean neighborCompound,
            int neighborReferenceFrame0,
            int neighborReferenceFrame1,
            InterMotionVector neighborMotionVector0,
            InterMotionVector neighborMotionVector1
    ) {
        if (referenceFrame == neighborReferenceFrame0) {
            return neighborMotionVector0;
        }
        if (neighborCompound && referenceFrame == neighborReferenceFrame1) {
            return neighborMotionVector1;
        }
        return InterMotionVector.predicted(MotionVector.zero());
    }

    /// Appends one provisional motion-vector candidate and an optional weaker companion candidate.
    ///
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates currently stored in `destination`
    /// @param candidate the base candidate to append
    /// @return the updated candidate count after appending the provisional candidate entries
    private static int appendProvisionalCandidateWeights(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate = Objects.requireNonNull(candidate, "candidate");
        if (count < destination.length) {
            destination[count++] = nonNullCandidate;
        }
        int secondaryWeight;
        if (nonNullCandidate.weight() >= 640) {
            secondaryWeight = 512;
        } else if (nonNullCandidate.weight() >= 512) {
            secondaryWeight = 384;
        } else {
            secondaryWeight = -1;
        }
        if (secondaryWeight > 0 && count < destination.length) {
            destination[count++] = nonNullCandidate.withWeight(secondaryWeight);
        }
        return count;
    }

    /// Appends one temporal provisional candidate or accumulates its weight into an equivalent real candidate.
    ///
    /// Temporal motion-field samples should not duplicate the synthetic zero baseline. When a real
    /// candidate with the same motion-vector payload is already present, this helper increases its
    /// weight instead of appending another entry.
    ///
    /// @param destination the destination candidate array
    /// @param count the number of valid candidates currently stored in `destination`
    /// @param candidate the temporal provisional candidate to append or accumulate
    /// @return the updated candidate count after processing the temporal candidate
    private static int appendOrAccumulateTemporalCandidate(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] destination,
            int count,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullCandidate = Objects.requireNonNull(candidate, "candidate");
        for (int i = 0; i < count; i++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate existingCandidate = destination[i];
            if (!existingCandidate.synthetic() && equivalentMotionVectorCandidate(existingCandidate, nonNullCandidate)) {
                destination[i] = existingCandidate.withWeight(existingCandidate.weight() + nonNullCandidate.weight());
                return count;
            }
        }
        if (count < destination.length) {
            destination[count++] = nonNullCandidate;
        }
        return count;
    }

    /// Sorts a prefix of the supplied provisional candidate array in descending weight order.
    ///
    /// Real neighbor-derived candidates win ties over the synthetic zero baseline.
    ///
    /// @param values the provisional candidate array to sort
    /// @param count the number of active candidates at the front of the array
    private static void sortDescending(ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] values, int count) {
        for (int i = 1; i < count; i++) {
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate current = values[i];
            int j = i - 1;
            while (j >= 0 && compareCandidates(current, values[j]) < 0) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = current;
        }
    }

    /// Compares two provisional motion-vector candidates for descending sort order.
    ///
    /// @param left the first candidate
    /// @param right the second candidate
    /// @return the comparison result used for descending sort order
    private static int compareCandidates(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate left,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate right
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullLeft = Objects.requireNonNull(left, "left");
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullRight = Objects.requireNonNull(right, "right");
        int weightCompare = Integer.compare(nonNullRight.weight(), nonNullLeft.weight());
        if (weightCompare != 0) {
            return weightCompare;
        }
        if (nonNullLeft.synthetic() != nonNullRight.synthetic()) {
            return nonNullLeft.synthetic() ? 1 : -1;
        }
        return 0;
    }

    /// Returns whether two provisional motion-vector candidates carry the same motion-vector payload.
    ///
    /// @param left the first provisional motion-vector candidate
    /// @param right the second provisional motion-vector candidate
    /// @return whether the two provisional motion-vector candidates carry the same motion-vector payload
    private static boolean equivalentMotionVectorCandidate(
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate left,
            ProvisionalInterModeContext.ProvisionalMotionVectorCandidate right
    ) {
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullLeft = Objects.requireNonNull(left, "left");
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate nonNullRight = Objects.requireNonNull(right, "right");
        return nonNullLeft.motionVector0().equals(nonNullRight.motionVector0())
                && Objects.equals(nonNullLeft.motionVector1(), nonNullRight.motionVector1());
    }

    /// Returns whether two reference selections share at least one reference-frame index.
    ///
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @param neighborCompound whether the stored neighbor uses compound references
    /// @param neighborReferenceFrame0 the neighbor primary reference
    /// @param neighborReferenceFrame1 the neighbor secondary reference, or `-1`
    /// @return whether the two reference selections share at least one reference-frame index
    private static boolean sharesAnyReference(
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1,
            boolean neighborCompound,
            int neighborReferenceFrame0,
            int neighborReferenceFrame1
    ) {
        if (referenceFrame0 == neighborReferenceFrame0 || referenceFrame0 == neighborReferenceFrame1) {
            return true;
        }
        return compoundReference
                && (referenceFrame1 == neighborReferenceFrame0 || referenceFrame1 == neighborReferenceFrame1)
                && neighborCompound;
    }

    /// Returns the coarse forward/backward reference direction for one reference-frame index.
    ///
    /// @param referenceFrame the reference-frame index in internal LAST..ALTREF order
    /// @return `0` for forward references and `1` for backward references
    private static int referenceDirection(int referenceFrame) {
        return referenceFrame >= 4 ? 1 : 0;
    }

    /// Summarizes the currently implemented spatial subset of AV1 `refmvs` syntax contexts.
    ///
    /// This summary mirrors `dav1d_refmvs_find()`'s `nearest_match` / `ref_match_count` /
    /// `have_newmv` handling for the currently implemented direct-edge and bounded secondary
    /// spatial scans, while still omitting temporal candidates.
    ///
    /// @param nearestMatchCount the number of direct top/left matches in `[0, 2]`
    /// @param referenceMatchCount the number of spatial row/column match groups in `[0, 2]`
    /// @param haveNewMotionVectorMatch whether any matching spatial neighbor used a `NEWMV`-carrying mode
    /// @return the summarized spatial subset of AV1 `refmvs` syntax contexts
    private static RefMvsContextSummary summarizeDirectRefMvsContexts(
            int nearestMatchCount,
            int referenceMatchCount,
            boolean haveNewMotionVectorMatch
    ) {
        int refmvContext;
        int newmvContext;
        switch (nearestMatchCount) {
            case 0 -> {
                refmvContext = Math.min(2, referenceMatchCount);
                newmvContext = referenceMatchCount > 0 ? 1 : 0;
            }
            case 1 -> {
                refmvContext = Math.min(referenceMatchCount * 3, 4);
                newmvContext = 3 - (haveNewMotionVectorMatch ? 1 : 0);
            }
            default -> {
                refmvContext = 5;
                newmvContext = 5 - (haveNewMotionVectorMatch ? 1 : 0);
            }
        }

        int compoundInterModeContext = switch (refmvContext >> 1) {
            case 0 -> Math.min(newmvContext, 1);
            case 1 -> 1 + Math.min(newmvContext, 3);
            default -> Math.max(4, Math.min(7, 3 + newmvContext));
        };
        return new RefMvsContextSummary(newmvContext, 0, refmvContext, compoundInterModeContext);
    }

    /// Updates the partition edge state after a non-deferred partition decision.
    ///
    /// @param position the current square block position
    /// @param span8 the edge span to update in 8x8 units
    /// @param aboveValue the value to store on the above edge
    /// @param leftValue the value to store on the left edge
    public void updatePartition(BlockPosition position, int span8, int aboveValue, int leftValue) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        int startX8 = nonNullPosition.x8();
        int startY8 = nonNullPosition.y8();
        int endX8 = Math.min(abovePartition.length, startX8 + span8);
        int endY8 = Math.min(leftPartition.length, startY8 + span8);
        for (int x8 = startX8; x8 < endX8; x8++) {
            abovePartition[x8] = (byte) aboveValue;
        }
        for (int y8 = startY8; y8 < endY8; y8++) {
            leftPartition[y8] = (byte) leftValue;
        }
    }

    /// A provisional inter-mode syntax context derived only from already-decoded neighbors.
    @NotNullByDefault
    public static final class ProvisionalInterModeContext {
        /// The zero-based provisional `newmv` context index in `[0, 6)`.
        private final int singleNewMvContext;

        /// The zero-based provisional `globalmv` context index in `[0, 2)`.
        private final int singleGlobalMvContext;

        /// The zero-based provisional `refmv` context index in `[0, 6)`.
        private final int singleReferenceMvContext;

        /// The zero-based provisional compound inter-mode context index in `[0, 8)`.
        private final int compoundInterModeContext;

        /// The provisional motion-vector candidates sorted in descending weight order.
        private final ProvisionalMotionVectorCandidate[] candidates;

        /// The de-duplicated spatial motion-vector candidates used for nearest/near/new prediction.
        private final ProvisionalMotionVectorCandidate[] referenceMotionVectorCandidates;

        /// Creates one provisional inter-mode syntax context.
        ///
        /// @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
        /// @param singleGlobalMvContext the zero-based provisional `globalmv` context index in `[0, 2)`
        /// @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
        /// @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
        /// @param candidates the provisional motion-vector candidates sorted in descending weight order
        public ProvisionalInterModeContext(
                int singleNewMvContext,
                int singleGlobalMvContext,
                int singleReferenceMvContext,
                int compoundInterModeContext,
                ProvisionalMotionVectorCandidate[] candidates
        ) {
            this.singleNewMvContext = singleNewMvContext;
            this.singleGlobalMvContext = singleGlobalMvContext;
            this.singleReferenceMvContext = singleReferenceMvContext;
            this.compoundInterModeContext = compoundInterModeContext;
            this.candidates = Arrays.copyOf(Objects.requireNonNull(candidates, "candidates"), candidates.length);
            this.referenceMotionVectorCandidates = buildReferenceMotionVectorCandidates(this.candidates);
        }

        /// Returns the zero-based provisional `newmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based provisional `newmv` context index in `[0, 6)`
        public int singleNewMvContext() {
            return singleNewMvContext;
        }

        /// Returns the zero-based provisional `globalmv` context index in `[0, 2)`.
        ///
        /// @return the zero-based provisional `globalmv` context index in `[0, 2)`
        public int singleGlobalMvContext() {
            return singleGlobalMvContext;
        }

        /// Returns the zero-based provisional `refmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based provisional `refmv` context index in `[0, 6)`
        public int singleReferenceMvContext() {
            return singleReferenceMvContext;
        }

        /// Returns the zero-based provisional compound inter-mode context index in `[0, 8)`.
        ///
        /// @return the zero-based provisional compound inter-mode context index in `[0, 8)`
        public int compoundInterModeContext() {
            return compoundInterModeContext;
        }

        /// Returns the number of provisional motion-vector candidates currently available.
        ///
        /// @return the number of provisional motion-vector candidates currently available
        public int candidateCount() {
            return candidates.length;
        }

        /// Returns one provisional candidate weight by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional candidate weight by index
        public int candidateWeight(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).weight();
        }

        /// Returns one provisional primary motion vector by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional primary motion vector by index
        public InterMotionVector candidateMotionVector0(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).motionVector0();
        }

        /// Returns one provisional secondary motion vector by index, or `null`.
        ///
        /// @param index the zero-based candidate index
        /// @return one provisional secondary motion vector by index, or `null`
        public @Nullable InterMotionVector candidateMotionVector1(int index) {
            return candidate(Objects.checkIndex(index, candidates.length)).motionVector1();
        }

        /// Returns the provisional dynamic-reference-list context for one candidate boundary.
        ///
        /// This follows `dav1d`'s threshold rule over the locally stored provisional candidate
        /// weights instead of a full `refmvs` stack.
        ///
        /// @param referenceIndex the zero-based candidate boundary index
        /// @return the zero-based provisional dynamic-reference-list context in `[0, 3)`
        public int drlContext(int referenceIndex) {
            int index = Objects.checkIndex(referenceIndex + 1, candidates.length) - 1;
            if (candidates[index].weight() >= 640) {
                return candidates[index + 1].weight() < 640 ? 1 : 0;
            }
            return candidates[index + 1].weight() < 640 ? 2 : 0;
        }

        /// Returns one stored provisional motion-vector candidate by index.
        ///
        /// @param index the zero-based candidate index
        /// @return one stored provisional motion-vector candidate by index
        public ProvisionalMotionVectorCandidate candidate(int index) {
            return candidates[Objects.checkIndex(index, candidates.length)];
        }

        /// Returns the number of de-duplicated spatial motion-vector candidates currently available.
        ///
        /// The returned stack keeps real neighbor-derived candidates first and appends at most one
        /// synthetic zero fallback at the end.
        ///
        /// @return the number of de-duplicated spatial motion-vector candidates currently available
        public int motionVectorCandidateCount() {
            return referenceMotionVectorCandidates.length;
        }

        /// Returns one de-duplicated spatial motion-vector candidate by index.
        ///
        /// @param index the zero-based spatial motion-vector candidate index
        /// @return one de-duplicated spatial motion-vector candidate by index
        public ProvisionalMotionVectorCandidate motionVectorCandidate(int index) {
            return referenceMotionVectorCandidates[Objects.checkIndex(index, referenceMotionVectorCandidates.length)];
        }

        /// Builds the de-duplicated spatial motion-vector candidate stack used by motion prediction.
        ///
        /// @param weightedCandidates the full weighted candidate list used by syntax contexts
        /// @return the de-duplicated spatial motion-vector candidate stack used by motion prediction
        private static ProvisionalMotionVectorCandidate[] buildReferenceMotionVectorCandidates(
                ProvisionalMotionVectorCandidate[] weightedCandidates
        ) {
            ProvisionalMotionVectorCandidate[] realCandidates = new ProvisionalMotionVectorCandidate[weightedCandidates.length];
            int realCount = 0;
            @Nullable ProvisionalMotionVectorCandidate syntheticCandidate = null;
            for (ProvisionalMotionVectorCandidate candidate : weightedCandidates) {
                if (candidate.synthetic()) {
                    if (syntheticCandidate == null) {
                        syntheticCandidate = candidate;
                    }
                    continue;
                }
                if (!containsEquivalentMotionVectorCandidate(realCandidates, realCount, candidate)) {
                    realCandidates[realCount++] = candidate;
                }
            }
            ProvisionalMotionVectorCandidate[] result =
                    new ProvisionalMotionVectorCandidate[realCount + (syntheticCandidate != null ? 1 : 0)];
            System.arraycopy(realCandidates, 0, result, 0, realCount);
            if (syntheticCandidate != null) {
                result[realCount] = syntheticCandidate;
            }
            return result;
        }

        /// Returns whether one candidate list prefix already contains an equivalent motion-vector candidate.
        ///
        /// Two candidates are considered equivalent when they carry the same primary and secondary
        /// motion vectors, regardless of weight.
        ///
        /// @param candidates the candidate array prefix to scan
        /// @param count the number of active candidates at the front of the array
        /// @param expected the candidate to search for
        /// @return whether the candidate list prefix already contains an equivalent motion-vector candidate
        private static boolean containsEquivalentMotionVectorCandidate(
                ProvisionalMotionVectorCandidate[] candidates,
                int count,
                ProvisionalMotionVectorCandidate expected
        ) {
            for (int i = 0; i < count; i++) {
                ProvisionalMotionVectorCandidate candidate = candidates[i];
                if (candidate.motionVector0().equals(expected.motionVector0())
                        && Objects.equals(candidate.motionVector1(), expected.motionVector1())) {
                    return true;
                }
            }
            return false;
        }

        /// One provisional motion-vector candidate derived from one bounded neighbor source.
        @NotNullByDefault
        public static final class ProvisionalMotionVectorCandidate {
            /// The candidate weight used for DRL-context derivation.
            private final int weight;

            /// The primary provisional motion vector carried by this candidate.
            private final InterMotionVector motionVector0;

            /// The secondary provisional motion vector carried by this candidate, or `null`.
            private final @Nullable InterMotionVector motionVector1;

            /// Whether this candidate is the synthetic zero baseline instead of a real neighbor.
            private final boolean synthetic;

            /// Creates one provisional motion-vector candidate.
            ///
            /// @param weight the candidate weight used for DRL-context derivation
            /// @param motionVector0 the primary provisional motion vector carried by this candidate
            /// @param motionVector1 the secondary provisional motion vector carried by this candidate, or `null`
            /// @param synthetic whether this candidate is the synthetic zero baseline
            private ProvisionalMotionVectorCandidate(
                    int weight,
                    InterMotionVector motionVector0,
                    @Nullable InterMotionVector motionVector1,
                    boolean synthetic
            ) {
                this.weight = weight;
                this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
                this.motionVector1 = motionVector1;
                this.synthetic = synthetic;
            }

            /// Returns the candidate weight used for DRL-context derivation.
            ///
            /// @return the candidate weight used for DRL-context derivation
            public int weight() {
                return weight;
            }

            /// Returns the primary provisional motion vector carried by this candidate.
            ///
            /// @return the primary provisional motion vector carried by this candidate
            public InterMotionVector motionVector0() {
                return motionVector0;
            }

            /// Returns the secondary provisional motion vector carried by this candidate, or `null`.
            ///
            /// @return the secondary provisional motion vector carried by this candidate, or `null`
            public @Nullable InterMotionVector motionVector1() {
                return motionVector1;
            }

            /// Returns whether this candidate is the synthetic zero baseline.
            ///
            /// @return whether this candidate is the synthetic zero baseline
            public boolean synthetic() {
                return synthetic;
            }

            /// Returns this candidate copied with a different weight.
            ///
            /// @param newWeight the replacement candidate weight
            /// @return this candidate copied with a different weight
            private ProvisionalMotionVectorCandidate withWeight(int newWeight) {
                return new ProvisionalMotionVectorCandidate(newWeight, motionVector0, motionVector1, synthetic);
            }
        }
    }

    /// One stored decoded block reused by the bounded spatial scan.
    @NotNullByDefault
    private static final class StoredBlock {
        /// The block origin X coordinate in tile-relative 4x4 units.
        private final int originX4;

        /// The block origin Y coordinate in tile-relative 4x4 units.
        private final int originY4;

        /// The block width in 4x4 units.
        private final int width4;

        /// The block height in 4x4 units.
        private final int height4;

        /// Whether the stored block is intra-coded.
        private final boolean intra;

        /// Whether the stored block uses compound inter references.
        private final boolean compoundReference;

        /// The stored primary inter reference in internal LAST..ALTREF order.
        private final int referenceFrame0;

        /// The stored secondary inter reference in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame1;

        /// The stored primary motion-vector state.
        private final InterMotionVector motionVector0;

        /// The stored secondary motion-vector state.
        private final InterMotionVector motionVector1;

        /// Whether the stored block used any `NEWMV`-carrying inter mode.
        private final boolean usesNewMotionVector;

        /// Creates one stored decoded block reused by the bounded spatial scan.
        ///
        /// @param originX4 the block origin X coordinate in tile-relative 4x4 units
        /// @param originY4 the block origin Y coordinate in tile-relative 4x4 units
        /// @param width4 the block width in 4x4 units
        /// @param height4 the block height in 4x4 units
        /// @param intra whether the stored block is intra-coded
        /// @param compoundReference whether the stored block uses compound inter references
        /// @param referenceFrame0 the stored primary inter reference in internal LAST..ALTREF order
        /// @param referenceFrame1 the stored secondary inter reference in internal LAST..ALTREF order, or `-1`
        /// @param motionVector0 the stored primary motion-vector state
        /// @param motionVector1 the stored secondary motion-vector state
        /// @param usesNewMotionVector whether the stored block used any `NEWMV`-carrying inter mode
        private StoredBlock(
                int originX4,
                int originY4,
                int width4,
                int height4,
                boolean intra,
                boolean compoundReference,
                int referenceFrame0,
                int referenceFrame1,
                InterMotionVector motionVector0,
                InterMotionVector motionVector1,
                boolean usesNewMotionVector
        ) {
            this.originX4 = originX4;
            this.originY4 = originY4;
            this.width4 = width4;
            this.height4 = height4;
            this.intra = intra;
            this.compoundReference = compoundReference;
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
            this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
            this.motionVector1 = Objects.requireNonNull(motionVector1, "motionVector1");
            this.usesNewMotionVector = usesNewMotionVector;
        }

        /// Returns the block origin X coordinate in tile-relative 4x4 units.
        ///
        /// @return the block origin X coordinate in tile-relative 4x4 units
        public int originX4() {
            return originX4;
        }

        /// Returns the block origin Y coordinate in tile-relative 4x4 units.
        ///
        /// @return the block origin Y coordinate in tile-relative 4x4 units
        public int originY4() {
            return originY4;
        }

        /// Returns the block width in 4x4 units.
        ///
        /// @return the block width in 4x4 units
        public int width4() {
            return width4;
        }

        /// Returns the block height in 4x4 units.
        ///
        /// @return the block height in 4x4 units
        public int height4() {
            return height4;
        }

        /// Returns whether the stored block is intra-coded.
        ///
        /// @return whether the stored block is intra-coded
        public boolean intra() {
            return intra;
        }

        /// Returns whether the stored block uses compound inter references.
        ///
        /// @return whether the stored block uses compound inter references
        public boolean compoundReference() {
            return compoundReference;
        }

        /// Returns the stored primary inter reference in internal LAST..ALTREF order.
        ///
        /// @return the stored primary inter reference in internal LAST..ALTREF order
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the stored secondary inter reference in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the stored secondary inter reference in internal LAST..ALTREF order, or `-1`
        public int referenceFrame1() {
            return referenceFrame1;
        }

        /// Returns the stored primary motion-vector state.
        ///
        /// @return the stored primary motion-vector state
        public InterMotionVector motionVector0() {
            return motionVector0;
        }

        /// Returns the stored secondary motion-vector state.
        ///
        /// @return the stored secondary motion-vector state
        public InterMotionVector motionVector1() {
            return motionVector1;
        }

        /// Returns whether the stored block used any `NEWMV`-carrying inter mode.
        ///
        /// @return whether the stored block used any `NEWMV`-carrying inter mode
        public boolean usesNewMotionVector() {
            return usesNewMotionVector;
        }
    }

    /// The result of scanning one bounded spatial row or column.
    @NotNullByDefault
    private static final class SpatialScanResult {
        /// The updated number of valid weighted candidates.
        private final int candidateCount;

        /// Whether the scanned row or column found any matching reference.
        private final boolean referenceMatch;

        /// Whether any matching scanned block used a `NEWMV`-carrying mode.
        private final boolean haveNewMotionVectorMatch;

        /// Creates one bounded spatial scan result.
        ///
        /// @param candidateCount the updated number of valid weighted candidates
        /// @param referenceMatch whether the scanned row or column found any matching reference
        /// @param haveNewMotionVectorMatch whether any matching scanned block used a `NEWMV`-carrying mode
        private SpatialScanResult(int candidateCount, boolean referenceMatch, boolean haveNewMotionVectorMatch) {
            this.candidateCount = candidateCount;
            this.referenceMatch = referenceMatch;
            this.haveNewMotionVectorMatch = haveNewMotionVectorMatch;
        }

        /// Returns the updated number of valid weighted candidates.
        ///
        /// @return the updated number of valid weighted candidates
        public int candidateCount() {
            return candidateCount;
        }

        /// Returns whether the scanned row or column found any matching reference.
        ///
        /// @return whether the scanned row or column found any matching reference
        public boolean referenceMatch() {
            return referenceMatch;
        }

        /// Returns whether any matching scanned block used a `NEWMV`-carrying mode.
        ///
        /// @return whether any matching scanned block used a `NEWMV`-carrying mode
        public boolean haveNewMotionVectorMatch() {
            return haveNewMotionVectorMatch;
        }
    }

    /// The result of sampling the tile-local temporal motion field.
    @NotNullByDefault
    private static final class TemporalScanResult {
        /// The updated number of valid weighted candidates.
        private final int candidateCount;

        /// The zero-based provisional `globalmv` context index in `[0, 2)`.
        private final int globalMotionContext;

        /// Creates one temporal motion-field scan result.
        ///
        /// @param candidateCount the updated number of valid weighted candidates
        /// @param globalMotionContext the zero-based provisional `globalmv` context index in `[0, 2)`
        private TemporalScanResult(int candidateCount, int globalMotionContext) {
            this.candidateCount = candidateCount;
            this.globalMotionContext = globalMotionContext;
        }

        /// Returns the updated number of valid weighted candidates.
        ///
        /// @return the updated number of valid weighted candidates
        public int candidateCount() {
            return candidateCount;
        }

        /// Returns the zero-based provisional `globalmv` context index in `[0, 2)`.
        ///
        /// @return the zero-based provisional `globalmv` context index in `[0, 2)`
        public int globalMotionContext() {
            return globalMotionContext;
        }
    }

    /// The result of sampling one temporal motion-field coordinate.
    @NotNullByDefault
    private static final class TemporalSampleResult {
        /// The updated number of valid weighted candidates.
        private final int candidateCount;

        /// The updated number of visited temporal motion blocks.
        private final int visitedCount;

        /// Whether the sampled temporal candidate contributes a non-zero motion vector.
        private final boolean nonZeroMotionVectorCandidate;

        /// Creates one temporal motion-field sample result.
        ///
        /// @param candidateCount the updated number of valid weighted candidates
        /// @param visitedCount the updated number of visited temporal motion blocks
        /// @param nonZeroMotionVectorCandidate whether the sampled temporal candidate contributes a non-zero motion vector
        private TemporalSampleResult(int candidateCount, int visitedCount, boolean nonZeroMotionVectorCandidate) {
            this.candidateCount = candidateCount;
            this.visitedCount = visitedCount;
            this.nonZeroMotionVectorCandidate = nonZeroMotionVectorCandidate;
        }

        /// Returns the updated number of valid weighted candidates.
        ///
        /// @return the updated number of valid weighted candidates
        public int candidateCount() {
            return candidateCount;
        }

        /// Returns the updated number of visited temporal motion blocks.
        ///
        /// @return the updated number of visited temporal motion blocks
        public int visitedCount() {
            return visitedCount;
        }

        /// Returns whether the sampled temporal candidate contributes a non-zero motion vector.
        ///
        /// @return whether the sampled temporal candidate contributes a non-zero motion vector
        public boolean nonZeroMotionVectorCandidate() {
            return nonZeroMotionVectorCandidate;
        }
    }

    /// The direct-neighbor subset of AV1 `refmvs` syntax contexts.
    @NotNullByDefault
    private static final class RefMvsContextSummary {
        /// The zero-based `newmv` context index in `[0, 6)`.
        private final int singleNewMvContext;

        /// The zero-based `globalmv` context index in `[0, 2)`.
        private final int singleGlobalMvContext;

        /// The zero-based `refmv` context index in `[0, 6)`.
        private final int singleReferenceMvContext;

        /// The zero-based compound inter-mode context index in `[0, 8)`.
        private final int compoundInterModeContext;

        /// Creates one direct-neighbor `refmvs` syntax context summary.
        ///
        /// @param singleNewMvContext the zero-based `newmv` context index in `[0, 6)`
        /// @param singleGlobalMvContext the zero-based `globalmv` context index in `[0, 2)`
        /// @param singleReferenceMvContext the zero-based `refmv` context index in `[0, 6)`
        /// @param compoundInterModeContext the zero-based compound inter-mode context index in `[0, 8)`
        private RefMvsContextSummary(
                int singleNewMvContext,
                int singleGlobalMvContext,
                int singleReferenceMvContext,
                int compoundInterModeContext
        ) {
            this.singleNewMvContext = singleNewMvContext;
            this.singleGlobalMvContext = singleGlobalMvContext;
            this.singleReferenceMvContext = singleReferenceMvContext;
            this.compoundInterModeContext = compoundInterModeContext;
        }

        /// Returns the zero-based `newmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based `newmv` context index in `[0, 6)`
        public int singleNewMvContext() {
            return singleNewMvContext;
        }

        /// Returns the zero-based `globalmv` context index in `[0, 2)`.
        ///
        /// @return the zero-based `globalmv` context index in `[0, 2)`
        public int singleGlobalMvContext() {
            return singleGlobalMvContext;
        }

        /// Returns the zero-based `refmv` context index in `[0, 6)`.
        ///
        /// @return the zero-based `refmv` context index in `[0, 6)`
        public int singleReferenceMvContext() {
            return singleReferenceMvContext;
        }

        /// Returns the zero-based compound inter-mode context index in `[0, 8)`.
        ///
        /// @return the zero-based compound inter-mode context index in `[0, 8)`
        public int compoundInterModeContext() {
            return compoundInterModeContext;
        }
    }

    /// The current-frame segment prediction for one block position.
    @NotNullByDefault
    public static final class SegmentPrediction {
        /// The predicted segment identifier derived from already-decoded neighbors.
        private final int predictedSegmentId;

        /// The zero-based segment-id context derived from already-decoded neighbors.
        private final int context;

        /// Creates one current-frame segment prediction.
        ///
        /// @param predictedSegmentId the predicted segment identifier derived from already-decoded neighbors
        /// @param context the zero-based segment-id context derived from already-decoded neighbors
        public SegmentPrediction(int predictedSegmentId, int context) {
            this.predictedSegmentId = predictedSegmentId;
            this.context = context;
        }

        /// Returns the predicted segment identifier derived from already-decoded neighbors.
        ///
        /// @return the predicted segment identifier derived from already-decoded neighbors
        public int predictedSegmentId() {
            return predictedSegmentId;
        }

        /// Returns the zero-based segment-id context derived from already-decoded neighbors.
        ///
        /// @return the zero-based segment-id context derived from already-decoded neighbors
        public int context() {
            return context;
        }
    }
}
