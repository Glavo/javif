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
        LumaIntraPredictionMode[] aboveMode = new LumaIntraPredictionMode[tileWidth4];
        LumaIntraPredictionMode[] leftMode = new LumaIntraPredictionMode[tileHeight4];
        Arrays.fill(aboveReferenceFrame0, (byte) -1);
        Arrays.fill(leftReferenceFrame0, (byte) -1);
        Arrays.fill(aboveReferenceFrame1, (byte) -1);
        Arrays.fill(leftReferenceFrame1, (byte) -1);
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
