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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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

    /// Creates tile-local neighbor context state.
    ///
    /// @param tileWidth4 the tile width rounded up to 4x4 units
    /// @param tileHeight4 the tile height rounded up to 4x4 units
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
    private BlockNeighborContext(
            int tileWidth4,
            int tileHeight4,
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
            byte[] leftPartition
    ) {
        this.tileWidth4 = tileWidth4;
        this.tileHeight4 = tileHeight4;
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
        InterMotionVector[] aboveMotionVector0 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector0 = new InterMotionVector[tileHeight4];
        InterMotionVector[] aboveMotionVector1 = new InterMotionVector[tileWidth4];
        InterMotionVector[] leftMotionVector1 = new InterMotionVector[tileHeight4];
        LumaIntraPredictionMode[] aboveMode = new LumaIntraPredictionMode[tileWidth4];
        LumaIntraPredictionMode[] leftMode = new LumaIntraPredictionMode[tileHeight4];
        InterMotionVector defaultMotionVector = InterMotionVector.predicted(MotionVector.zero());
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
                new int[3][tileWidth4][8],
                new int[3][tileHeight4][8],
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

    /// Builds a provisional inter-mode syntax context from already-decoded top and left neighbors.
    ///
    /// This helper intentionally does not implement the full AV1 `refmvs` stack. Instead it
    /// derives stable, bounded syntax contexts and a small provisional candidate-weight stack from
    /// the currently available neighbor references, which is sufficient for early block-header
    /// parsing before full motion-vector candidate derivation is implemented.
    ///
    /// @param position the current block position
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
    /// @return a provisional inter-mode syntax context derived from already-decoded neighbors
    public ProvisionalInterModeContext provisionalInterModeContext(
            BlockPosition position,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
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
        int exactMatches = 0;
        int partialMatches = 0;
        int interNeighborCount = 0;
        int compoundNeighborCount = 0;
        ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[] candidates =
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate[]{
                new ProvisionalInterModeContext.ProvisionalMotionVectorCandidate(
                        640,
                        InterMotionVector.predicted(MotionVector.zero()),
                        compoundReference ? InterMotionVector.predicted(MotionVector.zero()) : null,
                        true
                ),
                null,
                null,
                null
        };
        int candidateCount = 1;

        if (hasTopNeighbor(nonNullPosition) && aboveIntra[x4] == 0) {
            int neighborReferenceFrame0 = aboveReferenceFrame0[x4];
            int neighborReferenceFrame1 = aboveReferenceFrame1[x4];
            boolean neighborCompound = aboveCompoundReference[x4] != 0;
            int baseWeight = provisionalNeighborWeight(
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    neighborCompound,
                    neighborReferenceFrame0,
                    neighborReferenceFrame1
            );
            candidateCount = appendProvisionalCandidateWeights(
                    candidates,
                    candidateCount,
                    provisionalMotionVectorCandidate(
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1,
                            neighborCompound,
                            neighborReferenceFrame0,
                            neighborReferenceFrame1,
                            aboveMotionVector0[x4],
                            aboveMotionVector1[x4],
                            baseWeight
                    )
            );
            interNeighborCount++;
            if (neighborCompound) {
                compoundNeighborCount++;
            }
            if (baseWeight >= 640) {
                exactMatches++;
            } else if (baseWeight >= 384) {
                partialMatches++;
            }
        }
        if (hasLeftNeighbor(nonNullPosition) && leftIntra[y4] == 0) {
            int neighborReferenceFrame0 = leftReferenceFrame0[y4];
            int neighborReferenceFrame1 = leftReferenceFrame1[y4];
            boolean neighborCompound = leftCompoundReference[y4] != 0;
            int baseWeight = provisionalNeighborWeight(
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    neighborCompound,
                    neighborReferenceFrame0,
                    neighborReferenceFrame1
            );
            candidateCount = appendProvisionalCandidateWeights(
                    candidates,
                    candidateCount,
                    provisionalMotionVectorCandidate(
                            compoundReference,
                            referenceFrame0,
                            referenceFrame1,
                            neighborCompound,
                            neighborReferenceFrame0,
                            neighborReferenceFrame1,
                            leftMotionVector0[y4],
                            leftMotionVector1[y4],
                            baseWeight
                    )
            );
            interNeighborCount++;
            if (neighborCompound) {
                compoundNeighborCount++;
            }
            if (baseWeight >= 640) {
                exactMatches++;
            } else if (baseWeight >= 384) {
                partialMatches++;
            }
        }

        sortDescending(candidates, candidateCount);

        int singleNewMvContext;
        if (exactMatches >= 2) {
            singleNewMvContext = 0;
        } else if (exactMatches == 1) {
            singleNewMvContext = partialMatches > 0 ? 1 : 2;
        } else if (partialMatches >= 2) {
            singleNewMvContext = 3;
        } else if (partialMatches == 1) {
            singleNewMvContext = 4;
        } else {
            singleNewMvContext = 5;
        }

        int singleGlobalMvContext = exactMatches > 0 ? 0 : 1;

        int singleReferenceMvContext;
        if (exactMatches >= 2) {
            singleReferenceMvContext = 0;
        } else if (exactMatches == 1) {
            singleReferenceMvContext = 1;
        } else if (partialMatches >= 2) {
            singleReferenceMvContext = 2;
        } else if (partialMatches == 1) {
            singleReferenceMvContext = 3;
        } else if (interNeighborCount > 0) {
            singleReferenceMvContext = 4;
        } else {
            singleReferenceMvContext = 5;
        }

        int compoundInterModeContext;
        if (exactMatches >= 2) {
            compoundInterModeContext = 0;
        } else if (exactMatches == 1 && partialMatches > 0) {
            compoundInterModeContext = 1;
        } else if (exactMatches == 1) {
            compoundInterModeContext = 2;
        } else if (compoundNeighborCount >= 2) {
            compoundInterModeContext = 3;
        } else if (compoundNeighborCount == 1 && partialMatches > 0) {
            compoundInterModeContext = 4;
        } else if (partialMatches >= 2) {
            compoundInterModeContext = 5;
        } else if (partialMatches == 1 || interNeighborCount > 0) {
            compoundInterModeContext = 6;
        } else {
            compoundInterModeContext = 7;
        }

        return new ProvisionalInterModeContext(
                singleNewMvContext,
                singleGlobalMvContext,
                singleReferenceMvContext,
                compoundInterModeContext,
                Arrays.copyOf(candidates, candidateCount)
        );
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
        LumaIntraPredictionMode mode = nonNullHeader.intra() ? nonNullHeader.yMode() : LumaIntraPredictionMode.DC;
        int endX4 = Math.min(tileWidth4, position.x4() + size.width4());
        int endY4 = Math.min(tileHeight4, position.y4() + size.height4());
        for (int x4 = position.x4(); x4 < endX4; x4++) {
            aboveIntra[x4] = intra;
            aboveSkip[x4] = skip;
            aboveSkipMode[x4] = skipMode;
            aboveCompoundReference[x4] = compoundReference;
            aboveReferenceFrame0[x4] = referenceFrame0;
            aboveReferenceFrame1[x4] = referenceFrame1;
            aboveMotionVector0[x4] = motionVector0;
            aboveMotionVector1[x4] = motionVector1;
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
    }

    /// Returns one stored edge motion vector, falling back to a provisional zero vector.
    ///
    /// @param motionVector the stored motion vector, or `null`
    /// @return one stored edge motion vector, falling back to a provisional zero vector
    private static InterMotionVector fallbackMotionVector(@Nullable InterMotionVector motionVector) {
        return motionVector != null ? motionVector : InterMotionVector.predicted(MotionVector.zero());
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
        } else if (nonNullCandidate.weight() >= 384) {
            secondaryWeight = 256;
        } else {
            secondaryWeight = -1;
        }
        if (secondaryWeight > 0 && count < destination.length) {
            destination[count++] = nonNullCandidate.withWeight(secondaryWeight);
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
