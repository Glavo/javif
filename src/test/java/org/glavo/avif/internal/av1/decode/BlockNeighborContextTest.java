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
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundPredictionType;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.MotionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `BlockNeighborContext`.
@NotNullByDefault
final class BlockNeighborContextTest {
    /// Verifies key-frame initialization and neighbor-state updates from one decoded leaf header.
    @Test
    void initializesAndUpdatesKeyFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.KEY));
        BlockPosition position = new BlockPosition(0, 0);

        assertEquals(16, context.tileWidth4());
        assertEquals(16, context.tileHeight4());
        assertEquals(0, context.intraContext(position));
        assertEquals(0, context.skipContext(position));
        assertEquals(LumaIntraPredictionMode.DC, context.aboveMode(0));
        assertEquals(LumaIntraPredictionMode.DC, context.leftMode(0));

        context.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                position,
                BlockSize.SIZE_16X16,
                true,
                true,
                true,
                true,
                false,
                false,
                -1,
                -1,
                true,
                3,
                LumaIntraPredictionMode.VERTICAL,
                UvIntraPredictionMode.PAETH,
                4,
                0,
                new int[]{10, 20, 30, 40},
                new int[0],
                new int[0],
                new byte[128],
                new byte[0],
                null,
                0,
                0,
                0,
                0
        ));

        assertEquals(1, context.skipContext(new BlockPosition(0, 4)));
        assertEquals(2, context.skipModeContext(new BlockPosition(2, 2)));
        assertEquals(2, context.intraContext(new BlockPosition(0, 4)));
        assertEquals(LumaIntraPredictionMode.VERTICAL, context.aboveMode(0));
        assertEquals(LumaIntraPredictionMode.VERTICAL, context.leftMode(0));
        assertEquals(2, context.segmentPredictionContext(new BlockPosition(2, 2)));
        BlockNeighborContext.SegmentPrediction prediction = context.currentSegmentPrediction(new BlockPosition(2, 2));
        assertEquals(3, prediction.predictedSegmentId());
        assertEquals(2, prediction.context());
        assertEquals(4, context.abovePaletteSize(0));
        assertEquals(4, context.leftPaletteSize(0));
        assertEquals(0, context.aboveChromaPaletteSize(0));
        assertEquals(20, context.abovePaletteEntry(0, 0, 1));
        assertEquals(30, context.leftPaletteEntry(0, 0, 2));

        context.updatePartition(position, 8, 0x10, 0x18);
        assertEquals(2, context.partitionContext(3, new BlockPosition(0, 8)));
    }

    /// Verifies that stored coefficient-context bytes derive the same three-way DC-sign context
    /// classes used by AV1 coefficient coding.
    @Test
    void derivesDcSignContextsFromStoredCoefficientState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.KEY));
        context.updateLumaCoefficientContext(
                new TransformUnit(new BlockPosition(1, 0), TransformSize.TX_4X4),
                0x83
        );
        context.updateLumaCoefficientContext(
                new TransformUnit(new BlockPosition(0, 1), TransformSize.TX_4X4),
                0x03
        );

        assertEquals(
                0,
                context.lumaDcSignContext(new TransformUnit(new BlockPosition(1, 1), TransformSize.TX_4X4))
        );

        context.updateLumaCoefficientContext(
                new TransformUnit(new BlockPosition(0, 1), TransformSize.TX_4X4),
                0x82
        );
        assertEquals(
                2,
                context.lumaDcSignContext(new TransformUnit(new BlockPosition(1, 1), TransformSize.TX_4X4))
        );
    }

    /// Verifies that inter-reference contexts track compound and single-reference neighbors.
    @Test
    void derivesInterReferenceContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));

        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.predicted(new MotionVector(-8, 16))
        ));

        BlockPosition position = new BlockPosition(4, 4);
        assertEquals(2, context.compoundReferenceContext(position));
        assertEquals(1, context.compoundDirectionContext(position));
        assertEquals(2, context.singleReferenceContext(position));
        assertEquals(2, context.forwardReferenceContext(position));
        assertEquals(2, context.forwardReference1Context(position));
        assertEquals(1, context.forwardReference2Context(position));
        assertEquals(2, context.backwardReferenceContext(position));
        assertEquals(2, context.backwardReference1Context(position));
        assertEquals(1, context.unidirectionalReference1Context(position));
    }

    /// Verifies that compound blend-type contexts track masked and joint compound neighbor state.
    @Test
    void derivesCompoundBlendTypeContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));

        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                6,
                null,
                InterMotionVector.resolved(MotionVector.zero())
        ));
        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                4,
                null,
                InterMotionVector.resolved(MotionVector.zero()),
                InterMotionVector.resolved(MotionVector.zero()),
                CompoundPredictionType.SEGMENT
        ));

        BlockPosition position = new BlockPosition(4, 4);
        assertEquals(4, context.maskedCompoundContext(position));
        assertEquals(5, context.jointCompoundContext(position, 10, 8, 12, 4));
    }

    /// Verifies that switchable interpolation-filter contexts merge matching neighbor filters and sentinels.
    @Test
    void derivesInterpolationFilterContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        BlockPosition position = new BlockPosition(4, 4);

        assertEquals(3, context.interpolationFilterContext(position, 0, -1, 0));
        assertEquals(3, context.interpolationFilterContext(position, 0, -1, 1));
        assertEquals(7, context.interpolationFilterContext(position, 0, 4, 0));
        assertEquals(7, context.interpolationFilterContext(position, 0, 4, 1));

        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4)),
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
        ));
        assertEquals(0, context.interpolationFilterContext(position, 0, -1, 0));
        assertEquals(1, context.interpolationFilterContext(position, 0, -1, 1));
        assertEquals(4, context.interpolationFilterContext(position, 0, 4, 0));
        assertEquals(5, context.interpolationFilterContext(position, 0, 4, 1));

        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                4,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.predicted(new MotionVector(-8, 16)),
                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        ));
        assertEquals(3, context.interpolationFilterContext(position, 0, -1, 0));
        assertEquals(3, context.interpolationFilterContext(position, 0, -1, 1));
        assertEquals(7, context.interpolationFilterContext(position, 0, 4, 0));
        assertEquals(7, context.interpolationFilterContext(position, 0, 4, 1));

        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4)),
                FrameHeader.InterpolationFilter.BILINEAR,
                FrameHeader.InterpolationFilter.SWITCHABLE
        ));
        assertEquals(2, context.interpolationFilterContext(position, 4, -1, 0));
        assertEquals(0, context.interpolationFilterContext(position, 4, -1, 1));
        assertEquals(6, context.interpolationFilterContext(position, 4, 0, 0));
        assertEquals(4, context.interpolationFilterContext(position, 4, 0, 1));
    }

    /// Verifies provisional inter-mode contexts derive stable mode and DRL contexts from neighbors.
    @Test
    void derivesProvisionalInterModeContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));

        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.predicted(new MotionVector(-8, 16))
        ));

        BlockPosition position = new BlockPosition(4, 4);
        BlockNeighborContext.ProvisionalInterModeContext singleContext =
                context.provisionalInterModeContext(position, BlockSize.SIZE_16X16, false, 0, -1);
        assertEquals(5, singleContext.singleNewMvContext());
        assertEquals(0, singleContext.singleGlobalMvContext());
        assertEquals(5, singleContext.singleReferenceMvContext());
        assertEquals(7, singleContext.compoundInterModeContext());
        assertEquals(4, singleContext.candidateCount());
        assertEquals(640, singleContext.candidateWeight(0));
        assertEquals(640, singleContext.candidateWeight(1));
        assertEquals(512, singleContext.candidateWeight(2));
        assertEquals(448, singleContext.candidateWeight(3));
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.candidateMotionVector0(0));
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), singleContext.candidateMotionVector0(1));
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.candidateMotionVector0(2));
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), singleContext.candidateMotionVector0(3));
        assertNull(singleContext.candidateMotionVector1(0));
        assertEquals(3, singleContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), singleContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), singleContext.motionVectorCandidate(2).motionVector0());
        assertEquals(0, singleContext.drlContext(0));
        assertEquals(1, singleContext.drlContext(1));
        assertEquals(2, singleContext.drlContext(2));

        BlockNeighborContext.ProvisionalInterModeContext compoundContext =
                context.provisionalInterModeContext(position, BlockSize.SIZE_16X16, true, 0, 4);
        assertEquals(5, compoundContext.singleNewMvContext());
        assertEquals(0, compoundContext.singleGlobalMvContext());
        assertEquals(5, compoundContext.singleReferenceMvContext());
        assertEquals(7, compoundContext.compoundInterModeContext());
        assertEquals(4, compoundContext.candidateCount());
        assertEquals(640, compoundContext.candidateWeight(0));
        assertEquals(640, compoundContext.candidateWeight(1));
        assertEquals(512, compoundContext.candidateWeight(2));
        assertEquals(448, compoundContext.candidateWeight(3));
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), compoundContext.candidateMotionVector0(0));
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.candidateMotionVector1(0));
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), compoundContext.candidateMotionVector0(1));
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), compoundContext.candidateMotionVector1(1));
        assertEquals(3, compoundContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), compoundContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.motionVectorCandidate(0).motionVector1());
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), compoundContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), compoundContext.motionVectorCandidate(1).motionVector1());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), compoundContext.motionVectorCandidate(2).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), compoundContext.motionVectorCandidate(2).motionVector1());
        assertEquals(0, compoundContext.drlContext(0));
        assertEquals(1, compoundContext.drlContext(1));
        assertEquals(2, compoundContext.drlContext(2));
    }

    /// Verifies that direct matching neighbors carrying `NEWMV` lower the `newmv` syntax context.
    @Test
    void provisionalInterModeContextsTrackNewMvMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.predicted(new MotionVector(-8, 16))
        ));

        BlockNeighborContext.ProvisionalInterModeContext singleContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(4, singleContext.singleNewMvContext());
        assertEquals(5, singleContext.singleReferenceMvContext());
        assertEquals(7, singleContext.compoundInterModeContext());
    }

    /// Verifies that direct row and column scans cover the full current-block span instead of only its first cell.
    @Test
    void provisionalInterModeContextsScanDirectSpans() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(-12, -4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(6, 2),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(20, -8))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(-8, 16))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 6),
                BlockSize.SIZE_8X8,
                0,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(new MotionVector(24, 12))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(4, provisionalContext.singleNewMvContext());
        assertEquals(5, provisionalContext.singleReferenceMvContext());
        assertEquals(7, provisionalContext.compoundInterModeContext());
        assertEquals(7, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(640, provisionalContext.candidateWeight(1));
        assertEquals(640, provisionalContext.candidateWeight(2));
        assertEquals(512, provisionalContext.candidateWeight(3));
        assertEquals(512, provisionalContext.candidateWeight(4));
        assertEquals(256, provisionalContext.candidateWeight(5));
        assertEquals(256, provisionalContext.candidateWeight(6));
        assertEquals(4, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(20, -8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(24, 12)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(2).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(3).motionVector0());
    }

    /// Verifies that bounded secondary spatial scans contribute `refmvs` contexts even after direct edges diverge.
    @Test
    void provisionalInterModeContextsIncludeSecondarySpatialMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 0),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(0, 4),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(-16, 8))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X8,
                4,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(new MotionVector(-12, -8))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(2, provisionalContext.singleReferenceMvContext());
        assertEquals(2, provisionalContext.compoundInterModeContext());
        assertEquals(5, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(448, provisionalContext.candidateWeight(1));
        assertEquals(448, provisionalContext.candidateWeight(2));
        assertEquals(256, provisionalContext.candidateWeight(3));
        assertEquals(256, provisionalContext.candidateWeight(4));
        assertEquals(4, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(2).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(3).motionVector0());
    }

    /// Verifies that top-right spatial neighbors contribute to the provisional `refmvs` stack.
    @Test
    void provisionalInterModeContextsIncludeTopRightMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 0),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(0, 4),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(6, 2),
                BlockSize.SIZE_8X8,
                0,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(new MotionVector(-20, 8))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(1, provisionalContext.singleReferenceMvContext());
        assertEquals(1, provisionalContext.compoundInterModeContext());
        assertEquals(5, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(576, provisionalContext.candidateWeight(1));
        assertEquals(384, provisionalContext.candidateWeight(2));
        assertEquals(128, provisionalContext.candidateWeight(3));
        assertEquals(128, provisionalContext.candidateWeight(4));
        assertEquals(3, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(-20, 8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(2).motionVector0());
    }

    /// Verifies that top-left spatial neighbors contribute to the provisional `refmvs` stack.
    @Test
    void provisionalInterModeContextsIncludeTopLeftMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 2),
                BlockSize.SIZE_8X8,
                0,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(new MotionVector(-20, 8))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(1, provisionalContext.singleReferenceMvContext());
        assertEquals(1, provisionalContext.compoundInterModeContext());
        assertEquals(4, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(320, provisionalContext.candidateWeight(1));
        assertEquals(256, provisionalContext.candidateWeight(2));
        assertEquals(256, provisionalContext.candidateWeight(3));
        assertEquals(3, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(-20, 8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(2).motionVector0());
    }

    /// Verifies that farther odd-aligned secondary row/column offsets contribute when large blocks
    /// have no direct matching neighbors.
    @Test
    void provisionalInterModeContextsIncludeFarSecondaryOffsetMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(-12, -4))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(-8, 16))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 0),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(20, -8))
        ));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(0, 4),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(24, 12))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(2, provisionalContext.singleReferenceMvContext());
        assertEquals(2, provisionalContext.compoundInterModeContext());
        assertEquals(5, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(448, provisionalContext.candidateWeight(1));
        assertEquals(448, provisionalContext.candidateWeight(2));
        assertEquals(256, provisionalContext.candidateWeight(3));
        assertEquals(256, provisionalContext.candidateWeight(4));
        assertEquals(4, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(20, -8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(24, 12)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(2).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(3).motionVector0());
    }

    /// Verifies that compound blocks can reuse a single-reference neighbor that matches only the
    /// compound block's secondary reference.
    @Test
    void provisionalInterModeContextsMatchCompoundSecondReferenceAgainstSingleNeighbor() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        InterMotionVector neighborMotionVector = InterMotionVector.resolved(new MotionVector(18, -6));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                4,
                SingleInterPredictionMode.NEWMV,
                neighborMotionVector
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_8X8, true, 0, 4);

        assertEquals(2, provisionalContext.singleNewMvContext());
        assertEquals(3, provisionalContext.singleReferenceMvContext());
        assertEquals(3, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(448, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(neighborMotionVector, provisionalContext.motionVectorCandidate(0).motionVector1());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector1());
    }

    /// Verifies that temporal motion-field samples feed the provisional motion-vector stack and
    /// global-motion context when reference-frame motion vectors are enabled.
    @Test
    void provisionalInterModeContextsIncludeTemporalMotionFieldCandidates() {
        TileDecodeContext.TemporalMotionField temporalMotionField = new TileDecodeContext.TemporalMotionField(8, 8);
        TileDecodeContext.TemporalMotionBlock temporalBlock = TileDecodeContext.TemporalMotionBlock.singleReference(
                0,
                InterMotionVector.resolved(new MotionVector(28, -12))
        );
        temporalMotionField.setBlock(2, 2, temporalBlock);
        temporalMotionField.setBlock(3, 2, temporalBlock);
        BlockNeighborContext context = BlockNeighborContext.create(
                testTileContext(FrameType.INTER, true, temporalMotionField)
        );

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(0, provisionalContext.singleNewMvContext());
        assertEquals(1, provisionalContext.singleGlobalMvContext());
        assertEquals(0, provisionalContext.singleReferenceMvContext());
        assertEquals(0, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(64, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(28, -12)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
    }

    /// Verifies that small-block temporal fringe probes contribute candidates even when the main
    /// temporal sampling footprint does not contain any matching sample.
    @Test
    void provisionalInterModeContextsIncludeTemporalFringeCandidates() {
        TileDecodeContext.TemporalMotionField temporalMotionField = new TileDecodeContext.TemporalMotionField(8, 8);
        temporalMotionField.setBlock(
                1,
                0,
                TileDecodeContext.TemporalMotionBlock.singleReference(
                        0,
                        InterMotionVector.resolved(new MotionVector(16, 20))
                )
        );
        BlockNeighborContext context = BlockNeighborContext.create(
                testTileContext(FrameType.INTER, true, temporalMotionField)
        );

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(0, 0), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(0, provisionalContext.singleNewMvContext());
        assertEquals(1, provisionalContext.singleGlobalMvContext());
        assertEquals(0, provisionalContext.singleReferenceMvContext());
        assertEquals(0, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(64, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(16, 20)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
    }

    /// Verifies that small-block temporal fringe probes can append several distinct samples after
    /// the main temporal footprint has already produced a candidate.
    @Test
    void provisionalInterModeContextsIncludeMultipleTemporalFringeCandidates() {
        TileDecodeContext.TemporalMotionField temporalMotionField = new TileDecodeContext.TemporalMotionField(8, 8);
        InterMotionVector mainMotionVector = InterMotionVector.resolved(new MotionVector(8, 0));
        InterMotionVector bottomRightMotionVector = InterMotionVector.resolved(new MotionVector(16, 0));
        InterMotionVector rightEdgeMotionVector = InterMotionVector.resolved(new MotionVector(24, 0));
        temporalMotionField.setBlock(
                0,
                0,
                TileDecodeContext.TemporalMotionBlock.singleReference(0, mainMotionVector)
        );
        temporalMotionField.setBlock(
                1,
                1,
                TileDecodeContext.TemporalMotionBlock.singleReference(0, bottomRightMotionVector)
        );
        temporalMotionField.setBlock(
                1,
                0,
                TileDecodeContext.TemporalMotionBlock.singleReference(0, rightEdgeMotionVector)
        );
        BlockNeighborContext context = BlockNeighborContext.create(
                testTileContext(FrameType.INTER, true, temporalMotionField)
        );

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(new BlockPosition(0, 0), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleGlobalMvContext());
        assertEquals(4, provisionalContext.candidateCount());
        assertEquals(640, provisionalContext.candidateWeight(0));
        assertEquals(64, provisionalContext.candidateWeight(1));
        assertEquals(64, provisionalContext.candidateWeight(2));
        assertEquals(64, provisionalContext.candidateWeight(3));
        assertEquals(4, provisionalContext.motionVectorCandidateCount());
        assertEquals(mainMotionVector, provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(bottomRightMotionVector, provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(rightEdgeMotionVector, provisionalContext.motionVectorCandidate(2).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(3).motionVector0());
    }

    /// Verifies that decoded inter blocks are projected into the current-frame temporal motion
    /// field without mutating the reference temporal field consumed by the same frame.
    @Test
    void updateFromBlockHeaderWritesDecodedTemporalMotionField() {
        TileDecodeContext.TemporalMotionField sourceTemporalMotionField = new TileDecodeContext.TemporalMotionField(8, 8);
        TileDecodeContext tileContext = testTileContext(FrameType.INTER, true, sourceTemporalMotionField);
        BlockNeighborContext context = BlockNeighborContext.create(tileContext);

        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(4, 4),
                BlockSize.SIZE_16X16,
                0,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, -8)),
                InterMotionVector.predicted(new MotionVector(-4, 20))
        ));

        assertNull(tileContext.temporalMotionField().block(2, 2));
        TileDecodeContext.TemporalMotionBlock temporalBlock = tileContext.decodedTemporalMotionField().block(2, 2);
        assertTrue(temporalBlock != null);
        assertTrue(temporalBlock.compoundReference());
        assertEquals(0, temporalBlock.referenceFrame0());
        assertEquals(4, temporalBlock.referenceFrame1());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, -8)), temporalBlock.motionVector0());
        assertEquals(InterMotionVector.predicted(new MotionVector(-4, 20)), temporalBlock.motionVector1());
        assertEquals(temporalBlock, tileContext.decodedTemporalMotionField().block(3, 2));
        assertEquals(temporalBlock, tileContext.decodedTemporalMotionField().block(2, 3));
        assertEquals(temporalBlock, tileContext.decodedTemporalMotionField().block(3, 3));
    }

    /// Verifies inter-frame initialization starts with non-intra neighbors.
    @Test
    void initializesInterFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(FrameType.INTER));

        assertEquals(0, context.intraContext(new BlockPosition(4, 4)));
        assertEquals(0, context.skipContext(new BlockPosition(4, 4)));
        assertTrue(context.hasTopNeighbor(new BlockPosition(4, 4)));
        assertTrue(context.hasLeftNeighbor(new BlockPosition(4, 4)));
    }

    /// Verifies that OBMC candidate detection only accepts decoded causal inter neighbors.
    @Test
    void detectsOverlappableCandidatesFromCausalInterEdges() {
        BlockPosition currentPosition = new BlockPosition(4, 4);
        BlockSize currentSize = BlockSize.SIZE_16X16;

        BlockNeighborContext contextWithAbove = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        assertFalse(contextWithAbove.hasOverlappableCandidates(currentPosition, currentSize));
        contextWithAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertTrue(contextWithAbove.hasOverlappableCandidates(currentPosition, currentSize));

        BlockNeighborContext contextWithLeft = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        assertFalse(contextWithLeft.hasOverlappableCandidates(currentPosition, currentSize));
        contextWithLeft.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4))
        ));
        assertTrue(contextWithLeft.hasOverlappableCandidates(currentPosition, currentSize));

        BlockNeighborContext contextWithIntra = BlockNeighborContext.create(testTileContext(FrameType.KEY));
        contextWithIntra.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                true,
                0,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                null,
                0,
                0,
                0,
                0
        ));
        assertFalse(contextWithIntra.hasOverlappableCandidates(currentPosition, currentSize));
    }

    /// Verifies that local warped motion only accepts compatible same-reference causal samples.
    @Test
    void detectsLocalWarpSamplesFromSameReferenceCausalEdges() {
        BlockPosition currentPosition = new BlockPosition(4, 4);
        BlockSize currentSize = BlockSize.SIZE_16X16;

        BlockNeighborContext emptyContext = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        assertFalse(emptyContext.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithAbove = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        contextWithAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertTrue(contextWithAbove.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithDifferentReference = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        contextWithDifferentReference.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                1,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertFalse(contextWithDifferentReference.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithCompoundLeft = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        contextWithCompoundLeft.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                4,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.resolved(new MotionVector(-4, 20))
        ));
        assertFalse(contextWithCompoundLeft.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithProvisionalAbove = BlockNeighborContext.create(testTileContext(FrameType.INTER));
        contextWithProvisionalAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.predicted(new MotionVector(8, -4))
        ));
        assertFalse(contextWithProvisionalAbove.hasLocalWarpSamples(currentPosition, currentSize, 0));
    }

    /// Creates a simple tile context used by neighbor-context tests.
    ///
    /// @param frameType the synthetic frame type
    /// @return a simple tile context used by neighbor-context tests
    private static TileDecodeContext testTileContext(FrameType frameType) {
        return testTileContext(frameType, false, null);
    }

    /// Creates a simple tile context used by neighbor-context tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param useReferenceFrameMotionVectors whether reference-frame motion vectors are enabled
    /// @param temporalMotionField the synthetic tile-local temporal motion field, or `null`
    /// @return a simple tile context used by neighbor-context tests
    private static TileDecodeContext testTileContext(
            FrameType frameType,
            boolean useReferenceFrameMotionVectors,
            @org.jetbrains.annotations.Nullable TileDecodeContext.TemporalMotionField temporalMotionField
    ) {
        SequenceHeader sequenceHeader = new SequenceHeader(
                0,
                64,
                64,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(2, 0, 10, 0, false, false, false, null)
                },
                true,
                true,
                15,
                15,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        0,
                        false,
                        false,
                        false,
                        false
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        AvifPixelFormat.I420,
                        0,
                        true,
                        true,
                        false
                )
        );
        FrameHeader frameHeader = new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                frameType,
                true,
                false,
                true,
                false,
                false,
                true,
                false,
                7,
                0,
                0xFF,
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                false,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                useReferenceFrameMotionVectors,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        1,
                        new int[]{0, 1},
                        new int[]{0, 1},
                        0
                ),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                true,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        true,
                        true,
                        new int[]{1, 0, 0, 0, -1, 0, -1, -1},
                        new int[]{0, 0}
                ),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                false
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, new byte[]{0x00}, 0, 1)}
        );
        return temporalMotionField == null
                ? TileDecodeContext.create(assembly, 0)
                : TileDecodeContext.create(assembly, 0, temporalMotionField);
    }

    /// Creates default per-segment data with all features disabled.
    ///
    /// @return default per-segment data with all features disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// Creates one compact single-reference inter block header used by neighbor-context tests.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param singleInterMode the decoded single-reference inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @return one compact single-reference inter block header used by neighbor-context tests
    private static TileBlockHeaderReader.BlockHeader singleReferenceInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            @org.jetbrains.annotations.Nullable SingleInterPredictionMode singleInterMode,
            InterMotionVector motionVector0
    ) {
        return singleReferenceInterBlock(position, size, referenceFrame0, singleInterMode, motionVector0, null, null);
    }

    /// Creates one compact single-reference inter block header used by neighbor-context tests with explicit interpolation filters.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param singleInterMode the decoded single-reference inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @param horizontalInterpolationFilter the decoded horizontal interpolation filter, or `null`
    /// @param verticalInterpolationFilter the decoded vertical interpolation filter, or `null`
    /// @return one compact single-reference inter block header used by neighbor-context tests with explicit interpolation filters
    private static TileBlockHeaderReader.BlockHeader singleReferenceInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            @org.jetbrains.annotations.Nullable SingleInterPredictionMode singleInterMode,
            InterMotionVector motionVector0,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter horizontalInterpolationFilter,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter verticalInterpolationFilter
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                true,
                false,
                false,
                false,
                false,
                false,
                referenceFrame0,
                -1,
                singleInterMode,
                null,
                -1,
                motionVector0,
                null,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                false,
                0,
                -1,
                0,
                new int[4],
                null,
                null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                null,
                0,
                0,
                0,
                0
        );
    }

    /// Creates one compact compound-reference inter block header used by neighbor-context tests.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
    /// @param compoundInterMode the decoded compound inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @param motionVector1 the secondary motion-vector state chosen for the block
    /// @return one compact compound-reference inter block header used by neighbor-context tests
    private static TileBlockHeaderReader.BlockHeader compoundInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            int referenceFrame1,
            @org.jetbrains.annotations.Nullable org.glavo.avif.internal.av1.model.CompoundInterPredictionMode compoundInterMode,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1
    ) {
        return compoundInterBlock(
                position,
                size,
                referenceFrame0,
                referenceFrame1,
                compoundInterMode,
                motionVector0,
                motionVector1,
                CompoundPredictionType.AVERAGE
        );
    }

    /// Creates one compact compound-reference inter block header with an explicit compound prediction type.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
    /// @param compoundInterMode the decoded compound inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @param motionVector1 the secondary motion-vector state chosen for the block
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @return one compact compound-reference inter block header with an explicit compound prediction type
    private static TileBlockHeaderReader.BlockHeader compoundInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            int referenceFrame1,
            @org.jetbrains.annotations.Nullable org.glavo.avif.internal.av1.model.CompoundInterPredictionMode compoundInterMode,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1,
            CompoundPredictionType compoundPredictionType
    ) {
        return compoundInterBlock(
                position,
                size,
                referenceFrame0,
                referenceFrame1,
                compoundInterMode,
                motionVector0,
                motionVector1,
                compoundPredictionType,
                null,
                null
        );
    }

    /// Creates one compact compound-reference inter block header used by neighbor-context tests with explicit interpolation filters.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
    /// @param compoundInterMode the decoded compound inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @param motionVector1 the secondary motion-vector state chosen for the block
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param horizontalInterpolationFilter the decoded horizontal interpolation filter, or `null`
    /// @param verticalInterpolationFilter the decoded vertical interpolation filter, or `null`
    /// @return one compact compound-reference inter block header used by neighbor-context tests with explicit interpolation filters
    private static TileBlockHeaderReader.BlockHeader compoundInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            int referenceFrame1,
            @org.jetbrains.annotations.Nullable org.glavo.avif.internal.av1.model.CompoundInterPredictionMode compoundInterMode,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter horizontalInterpolationFilter,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter verticalInterpolationFilter
    ) {
        return compoundInterBlock(
                position,
                size,
                referenceFrame0,
                referenceFrame1,
                compoundInterMode,
                motionVector0,
                motionVector1,
                CompoundPredictionType.AVERAGE,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
        );
    }

    /// Creates one compact compound-reference inter block header used by neighbor-context tests with explicit interpolation filters.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
    /// @param compoundInterMode the decoded compound inter mode, or `null`
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @param motionVector1 the secondary motion-vector state chosen for the block
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param horizontalInterpolationFilter the decoded horizontal interpolation filter, or `null`
    /// @param verticalInterpolationFilter the decoded vertical interpolation filter, or `null`
    /// @return one compact compound-reference inter block header used by neighbor-context tests with explicit interpolation filters
    private static TileBlockHeaderReader.BlockHeader compoundInterBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            int referenceFrame1,
            @org.jetbrains.annotations.Nullable org.glavo.avif.internal.av1.model.CompoundInterPredictionMode compoundInterMode,
            InterMotionVector motionVector0,
            InterMotionVector motionVector1,
            CompoundPredictionType compoundPredictionType,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter horizontalInterpolationFilter,
            @org.jetbrains.annotations.Nullable FrameHeader.InterpolationFilter verticalInterpolationFilter
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                true,
                false,
                false,
                false,
                false,
                true,
                referenceFrame0,
                referenceFrame1,
                null,
                compoundInterMode,
                -1,
                motionVector0,
                motionVector1,
                MotionMode.SIMPLE,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
                compoundPredictionType,
                false,
                -1,
                false,
                null,
                false,
                -1,
                false,
                0,
                0,
                0,
                new int[4],
                null,
                null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                null,
                0,
                0,
                0,
                0
        );
    }
}
