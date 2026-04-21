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
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/// Tile-local neighbor context state used to derive block-level syntax contexts while scanning a tile.
@NotNullByDefault
public final class BlockNeighborContext {
    /// The tile width rounded up to 4x4 units.
    private final int tileWidth4;

    /// The tile height rounded up to 4x4 units.
    private final int tileHeight4;

    /// The above-edge intra flags indexed in 4x4 units.
    private final byte[] aboveIntra;

    /// The left-edge intra flags indexed in 4x4 units.
    private final byte[] leftIntra;

    /// The above-edge skip flags indexed in 4x4 units.
    private final byte[] aboveSkip;

    /// The left-edge skip flags indexed in 4x4 units.
    private final byte[] leftSkip;

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

    /// The above-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] aboveMode;

    /// The left-edge luma modes indexed in 4x4 units.
    private final LumaIntraPredictionMode[] leftMode;

    /// The above-edge partition context state indexed in 8x8 units.
    private final byte[] abovePartition;

    /// The left-edge partition context state indexed in 8x8 units.
    private final byte[] leftPartition;

    /// Creates tile-local neighbor context state.
    ///
    /// @param tileWidth4 the tile width rounded up to 4x4 units
    /// @param tileHeight4 the tile height rounded up to 4x4 units
    /// @param aboveIntra the above-edge intra flags indexed in 4x4 units
    /// @param leftIntra the left-edge intra flags indexed in 4x4 units
    /// @param aboveSkip the above-edge skip flags indexed in 4x4 units
    /// @param leftSkip the left-edge skip flags indexed in 4x4 units
    /// @param aboveSegmentPredicted the above-edge segmentation-prediction flags indexed in 4x4 units
    /// @param leftSegmentPredicted the left-edge segmentation-prediction flags indexed in 4x4 units
    /// @param aboveSegmentId the above-edge segment identifiers indexed in 4x4 units
    /// @param leftSegmentId the left-edge segment identifiers indexed in 4x4 units
    /// @param abovePaletteSize the above-edge luma palette sizes indexed in 4x4 units
    /// @param leftPaletteSize the left-edge luma palette sizes indexed in 4x4 units
    /// @param aboveMode the above-edge luma modes indexed in 4x4 units
    /// @param leftMode the left-edge luma modes indexed in 4x4 units
    /// @param abovePartition the above-edge partition context state indexed in 8x8 units
    /// @param leftPartition the left-edge partition context state indexed in 8x8 units
    private BlockNeighborContext(
            int tileWidth4,
            int tileHeight4,
            byte[] aboveIntra,
            byte[] leftIntra,
            byte[] aboveSkip,
            byte[] leftSkip,
            byte[] aboveSegmentPredicted,
            byte[] leftSegmentPredicted,
            byte[] aboveSegmentId,
            byte[] leftSegmentId,
            byte[] abovePaletteSize,
            byte[] leftPaletteSize,
            LumaIntraPredictionMode[] aboveMode,
            LumaIntraPredictionMode[] leftMode,
            byte[] abovePartition,
            byte[] leftPartition
    ) {
        this.tileWidth4 = tileWidth4;
        this.tileHeight4 = tileHeight4;
        this.aboveIntra = Objects.requireNonNull(aboveIntra, "aboveIntra");
        this.leftIntra = Objects.requireNonNull(leftIntra, "leftIntra");
        this.aboveSkip = Objects.requireNonNull(aboveSkip, "aboveSkip");
        this.leftSkip = Objects.requireNonNull(leftSkip, "leftSkip");
        this.aboveSegmentPredicted = Objects.requireNonNull(aboveSegmentPredicted, "aboveSegmentPredicted");
        this.leftSegmentPredicted = Objects.requireNonNull(leftSegmentPredicted, "leftSegmentPredicted");
        this.aboveSegmentId = Objects.requireNonNull(aboveSegmentId, "aboveSegmentId");
        this.leftSegmentId = Objects.requireNonNull(leftSegmentId, "leftSegmentId");
        this.abovePaletteSize = Objects.requireNonNull(abovePaletteSize, "abovePaletteSize");
        this.leftPaletteSize = Objects.requireNonNull(leftPaletteSize, "leftPaletteSize");
        this.aboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        this.leftMode = Objects.requireNonNull(leftMode, "leftMode");
        this.abovePartition = Objects.requireNonNull(abovePartition, "abovePartition");
        this.leftPartition = Objects.requireNonNull(leftPartition, "leftPartition");
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
        LumaIntraPredictionMode[] aboveMode = new LumaIntraPredictionMode[tileWidth4];
        LumaIntraPredictionMode[] leftMode = new LumaIntraPredictionMode[tileHeight4];
        Arrays.fill(aboveMode, LumaIntraPredictionMode.DC);
        Arrays.fill(leftMode, LumaIntraPredictionMode.DC);
        if (keyFrame) {
            Arrays.fill(aboveIntra, (byte) 1);
            Arrays.fill(leftIntra, (byte) 1);
        }

        return new BlockNeighborContext(
                tileWidth4,
                tileHeight4,
                aboveIntra,
                leftIntra,
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                new byte[tileWidth4],
                new byte[tileHeight4],
                aboveMode,
                leftMode,
                new byte[tileWidth8],
                new byte[tileHeight8]
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

    /// Updates the neighbor state after decoding one block header.
    ///
    /// @param header the decoded block header that should become the new above/left edge state
    public void updateFromBlockHeader(TileBlockHeaderReader.BlockHeader header) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        BlockPosition position = nonNullHeader.position();
        BlockSize size = nonNullHeader.size();
        byte intra = (byte) (nonNullHeader.intra() ? 1 : 0);
        byte skip = (byte) (nonNullHeader.skip() ? 1 : 0);
        byte segmentPredicted = (byte) (nonNullHeader.segmentPredicted() ? 1 : 0);
        byte segmentId = (byte) nonNullHeader.segmentId();
        byte paletteSize = (byte) nonNullHeader.yPaletteSize();
        LumaIntraPredictionMode mode = nonNullHeader.intra() ? nonNullHeader.yMode() : LumaIntraPredictionMode.DC;
        int endX4 = Math.min(tileWidth4, position.x4() + size.width4());
        int endY4 = Math.min(tileHeight4, position.y4() + size.height4());
        for (int x4 = position.x4(); x4 < endX4; x4++) {
            aboveIntra[x4] = intra;
            aboveSkip[x4] = skip;
            aboveSegmentPredicted[x4] = segmentPredicted;
            aboveSegmentId[x4] = segmentId;
            abovePaletteSize[x4] = paletteSize;
            aboveMode[x4] = mode;
        }
        for (int y4 = position.y4(); y4 < endY4; y4++) {
            leftIntra[y4] = intra;
            leftSkip[y4] = skip;
            leftSegmentPredicted[y4] = segmentPredicted;
            leftSegmentId[y4] = segmentId;
            leftPaletteSize[y4] = paletteSize;
            leftMode[y4] = mode;
        }
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
