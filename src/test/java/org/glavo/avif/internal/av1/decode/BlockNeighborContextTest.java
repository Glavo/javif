// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.av1.Av1ColorConfig;
import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundPredictionType;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `BlockNeighborContext`.
@NotNullByDefault
final class BlockNeighborContextTest {
    /// Verifies key-frame initialization and neighbor-state updates from one decoded leaf header.
    @Test
    void initializesAndUpdatesKeyFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY));
        BlockPosition position = new BlockPosition(0, 0);

        assertEquals(16, context.tileWidth4());
        assertEquals(16, context.tileHeight4());
        assertEquals(0, context.intraContext(position));
        assertEquals(0, context.skipContext(position));
        assertEquals(LumaIntraPredictionMode.DC, context.aboveMode(0));
        assertEquals(LumaIntraPredictionMode.DC, context.leftMode(0));
        assertEquals(0, context.transformSizeContext(position, TransformSize.TX_32X32));
        assertEquals(0, context.interTransformSplitContext(position, TransformSize.TX_32X32));

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
        assertEquals(0, prediction.predictedSegmentId());
        assertEquals(2, prediction.context());
        assertEquals(4, context.abovePaletteSize(0));
        assertEquals(4, context.leftPaletteSize(0));
        assertEquals(0, context.aboveChromaPaletteSize(0));
        assertEquals(20, context.abovePaletteEntry(0, 0, 1));
        assertEquals(30, context.leftPaletteEntry(0, 0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> context.abovePaletteEntry(0, 0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> context.leftPaletteEntry(1, 0, 0));

        context.updatePartition(position, 8, 0x10, 0x18);
        assertEquals(2, context.partitionContext(3, new BlockPosition(0, 8)));
    }

    /// Verifies that stored coefficient-context bytes derive the same three-way DC-sign context
    /// classes used by AV1 coefficient coding.
    @Test
    void derivesDcSignContextsFromStoredCoefficientState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY));
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
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));

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

    /// Verifies that intra neighbors do not count as backward references for the compound flag context.
    @Test
    void excludesIntraNeighborsFromCompoundReferenceDirection() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        context.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
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
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                null,
                InterMotionVector.resolved(MotionVector.zero())
        ));

        assertEquals(0, context.compoundReferenceContext(new BlockPosition(4, 4)));
    }

    /// Verifies that compound blend-type contexts track masked and joint compound neighbor state.
    @Test
    void derivesCompoundBlendTypeContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));

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
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
        assertEquals(1, context.interpolationFilterContext(position, 0, -1, 0));
        assertEquals(0, context.interpolationFilterContext(position, 0, -1, 1));
        assertEquals(5, context.interpolationFilterContext(position, 0, 4, 0));
        assertEquals(4, context.interpolationFilterContext(position, 0, 4, 1));

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
        assertEquals(0, context.interpolationFilterContext(position, 4, -1, 0));
        assertEquals(2, context.interpolationFilterContext(position, 4, -1, 1));
        assertEquals(4, context.interpolationFilterContext(position, 4, 0, 0));
        assertEquals(6, context.interpolationFilterContext(position, 4, 0, 1));
    }

    /// Verifies provisional inter-mode contexts derive stable mode and DRL contexts from neighbors.
    @Test
    void derivesProvisionalInterModeContextsFromUpdatedNeighbors() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));

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
                zeroGlobalMotionContext(context, position, BlockSize.SIZE_16X16, false, 0, -1);
        assertEquals(5, singleContext.singleNewMvContext());
        assertEquals(0, singleContext.singleGlobalMvContext());
        assertEquals(5, singleContext.singleReferenceMvContext());
        assertEquals(7, singleContext.compoundInterModeContext());
        assertEquals(2, singleContext.candidateCount());
        assertEquals(648, singleContext.candidateWeight(0));
        assertEquals(648, singleContext.candidateWeight(1));
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.candidateMotionVector0(0));
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), singleContext.candidateMotionVector0(1));
        assertNull(singleContext.candidateMotionVector1(0));
        assertEquals(2, singleContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), singleContext.motionVectorCandidate(1).motionVector0());
        assertEquals(0, singleContext.drlContext(0));

        BlockNeighborContext.ProvisionalInterModeContext compoundContext =
                zeroGlobalMotionContext(context, position, BlockSize.SIZE_16X16, true, 0, 4);
        assertEquals(3, compoundContext.singleNewMvContext());
        assertEquals(0, compoundContext.singleGlobalMvContext());
        assertEquals(3, compoundContext.singleReferenceMvContext());
        assertEquals(4, compoundContext.compoundInterModeContext());
        assertEquals(2, compoundContext.candidateCount());
        assertEquals(648, compoundContext.candidateWeight(0));
        assertEquals(2, compoundContext.candidateWeight(1));
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), compoundContext.candidateMotionVector0(0));
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.candidateMotionVector1(0));
        assertEquals(InterMotionVector.predicted(new MotionVector(8, -4)), compoundContext.candidateMotionVector0(1));
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.candidateMotionVector1(1));
        assertEquals(2, compoundContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), compoundContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.motionVectorCandidate(0).motionVector1());
        assertEquals(InterMotionVector.predicted(new MotionVector(8, -4)), compoundContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(new MotionVector(-8, 16)), compoundContext.motionVectorCandidate(1).motionVector1());
        assertEquals(1, compoundContext.drlContext(0));

        assertEquals(648, singleContext.candidateWeight(0));
        assertEquals(648, singleContext.candidateWeight(1));
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), singleContext.candidateMotionVector0(0));
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), singleContext.candidateMotionVector0(1));
    }

    /// Verifies affine `GLOBALMV` neighbors contribute the current block's position-dependent
    /// global vector before their stored vector is reused by the extended candidate scan.
    @Test
    void provisionalInterModeContextsReevaluateGlobalWarpNeighborsAtCurrentBlock() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        MotionVector neighborGlobalMotion = new MotionVector(-154, 370);
        MotionVector currentGlobalMotion = new MotionVector(-158, 366);
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(0, 0),
                BlockSize.SIZE_8X16,
                2,
                SingleInterPredictionMode.GLOBALMV,
                InterMotionVector.resolved(neighborGlobalMotion)
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                context.provisionalInterModeContext(
                        new BlockPosition(2, 0),
                        BlockSize.SIZE_8X16,
                        false,
                        2,
                        -1,
                        currentGlobalMotion,
                        MotionVector.zero(),
                        FrameHeader.GlobalMotionType.AFFINE,
                        FrameHeader.GlobalMotionType.IDENTITY
                );

        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(648, provisionalContext.candidateWeight(0));
        assertEquals(2, provisionalContext.candidateWeight(1));
        assertEquals(
                InterMotionVector.resolved(currentGlobalMotion),
                provisionalContext.motionVectorCandidate(0).motionVector0()
        );
        assertEquals(
                InterMotionVector.predicted(neighborGlobalMotion),
                provisionalContext.motionVectorCandidate(1).motionVector0()
        );
        assertFalse(provisionalContext.motionVectorCandidate(1).synthetic());
        assertEquals(1, provisionalContext.drlContext(0));
    }

    /// Verifies a compound extended neighbor may append both of its components after one exact
    /// candidate, exposing the third candidate to `NEARMV` DRL syntax.
    @Test
    void provisionalInterModeContextsKeepBothComponentsFromOneExtendedNeighbor() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        MotionVector exactMotionVector = new MotionVector(192, 422);
        MotionVector extendedMotionVector0 = MotionVector.zero();
        MotionVector extendedMotionVector1 = new MotionVector(256, 394);
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                0,
                SingleInterPredictionMode.NEWMV,
                InterMotionVector.resolved(exactMotionVector)
        ));
        context.updateFromBlockHeader(compoundInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X8,
                1,
                6,
                org.glavo.avif.internal.av1.model.CompoundInterPredictionMode.NEARMV_NEARMV,
                InterMotionVector.resolved(extendedMotionVector0),
                InterMotionVector.resolved(extendedMotionVector1)
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                zeroGlobalMotionContext(
                        context,
                        new BlockPosition(4, 4),
                        BlockSize.SIZE_8X8,
                        false,
                        0,
                        -1
                );

        assertEquals(3, provisionalContext.candidateCount());
        assertEquals(644, provisionalContext.candidateWeight(0));
        assertEquals(2, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.candidateWeight(2));
        assertEquals(
                InterMotionVector.resolved(exactMotionVector),
                provisionalContext.motionVectorCandidate(0).motionVector0()
        );
        assertEquals(
                InterMotionVector.predicted(extendedMotionVector0),
                provisionalContext.motionVectorCandidate(1).motionVector0()
        );
        assertEquals(
                InterMotionVector.predicted(extendedMotionVector1),
                provisionalContext.motionVectorCandidate(2).motionVector0()
        );
        assertEquals(2, provisionalContext.drlContext(1));
    }

    /// Verifies that direct matching neighbors carrying `NEWMV` lower the `newmv` syntax context.
    @Test
    void provisionalInterModeContextsTrackNewMvMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(4, singleContext.singleNewMvContext());
        assertEquals(5, singleContext.singleReferenceMvContext());
        assertEquals(7, singleContext.compoundInterModeContext());
    }

    /// Verifies that direct row and column scans cover the full current-block span instead of only its first cell.
    @Test
    void provisionalInterModeContextsScanDirectSpans() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(4, provisionalContext.singleNewMvContext());
        assertEquals(5, provisionalContext.singleReferenceMvContext());
        assertEquals(7, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(644, provisionalContext.candidateWeight(0));
        assertEquals(644, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(20, -8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(24, 12)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(0, provisionalContext.drlContext(0));
    }

    /// Verifies that bounded secondary spatial scans contribute `refmvs` contexts even after direct edges diverge.
    @Test
    void provisionalInterModeContextsIncludeSecondarySpatialMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(2, provisionalContext.singleReferenceMvContext());
        assertEquals(2, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(4, provisionalContext.candidateWeight(0));
        assertEquals(4, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(8, -4)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(12, 4)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(2, provisionalContext.drlContext(0));
    }

    /// Verifies that top-right spatial neighbors contribute to the provisional `refmvs` stack.
    @Test
    void provisionalInterModeContextsIncludeTopRightMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(2, provisionalContext.singleNewMvContext());
        assertEquals(3, provisionalContext.singleReferenceMvContext());
        assertEquals(3, provisionalContext.compoundInterModeContext());
        assertEquals(1, provisionalContext.candidateCount());
        assertEquals(644, provisionalContext.candidateWeight(0));
        assertEquals(2, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(-20, 8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
    }

    /// Verifies real reference candidates are clamped to the extended coded-frame boundary.
    @Test
    void provisionalInterModeContextsClampReferenceCandidatesAtFrameBoundary() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(14, 12),
                BlockSize.SIZE_8X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(300, 300))
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                zeroGlobalMotionContext(
                        context,
                        new BlockPosition(14, 14),
                        BlockSize.SIZE_8X8,
                        false,
                        0,
                        -1
                );

        assertEquals(1, provisionalContext.candidateCount());
        assertEquals(
                InterMotionVector.resolved(new MotionVector(192, 192)),
                provisionalContext.motionVectorCandidate(0).motionVector0()
        );
        assertEquals(
                InterMotionVector.predicted(MotionVector.zero()),
                provisionalContext.motionVectorCandidate(1).motionVector0()
        );
    }

    /// Verifies that top-left spatial neighbors contribute to the provisional `refmvs` stack.
    @Test
    void provisionalInterModeContextsIncludeTopLeftMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_8X8, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(1, provisionalContext.singleReferenceMvContext());
        assertEquals(1, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(4, provisionalContext.candidateWeight(0));
        assertEquals(2, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(-20, 8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.predicted(new MotionVector(8, -4)), provisionalContext.motionVectorCandidate(1).motionVector0());
    }

    /// Verifies that farther odd-aligned secondary row/column offsets contribute when large blocks
    /// have no direct matching neighbors.
    @Test
    void provisionalInterModeContextsIncludeFarSecondaryOffsetMatches() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_16X16, false, 0, -1);

        assertEquals(1, provisionalContext.singleNewMvContext());
        assertEquals(2, provisionalContext.singleReferenceMvContext());
        assertEquals(2, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(4, provisionalContext.candidateWeight(0));
        assertEquals(4, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(InterMotionVector.resolved(new MotionVector(20, -8)), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(InterMotionVector.resolved(new MotionVector(24, 12)), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(2, provisionalContext.drlContext(0));
    }

    /// Verifies compound extended candidates reuse and sign-normalize a single-reference neighbor.
    @Test
    void provisionalInterModeContextsMatchCompoundSecondReferenceAgainstSingleNeighbor() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        InterMotionVector neighborMotionVector = InterMotionVector.resolved(new MotionVector(18, -6));
        context.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_8X8,
                4,
                SingleInterPredictionMode.NEWMV,
                neighborMotionVector
        ));

        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                zeroGlobalMotionContext(context, new BlockPosition(4, 4), BlockSize.SIZE_8X8, true, 0, 4);

        assertEquals(0, provisionalContext.singleNewMvContext());
        assertEquals(0, provisionalContext.singleReferenceMvContext());
        assertEquals(0, provisionalContext.compoundInterModeContext());
        assertEquals(2, provisionalContext.candidateCount());
        assertEquals(2, provisionalContext.candidateWeight(0));
        assertEquals(2, provisionalContext.candidateWeight(1));
        assertEquals(2, provisionalContext.motionVectorCandidateCount());
        assertEquals(neighborMotionVector.asPredicted(), provisionalContext.motionVectorCandidate(0).motionVector0());
        assertEquals(neighborMotionVector.asPredicted(), provisionalContext.motionVectorCandidate(0).motionVector1());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), provisionalContext.motionVectorCandidate(1).motionVector1());
    }

    /// Verifies that decoded inter blocks populate the current frame's saved motion-vector field.
    @Test
    void updateFromBlockHeaderWritesDecodedTemporalMotionField() {
        TileDecodeContext tileContext = testTileContext(Av1FrameType.INTER);
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

    /// Verifies that chroma coefficient-skip contexts use dav1d's dedicated chroma range.
    @Test
    void chromaCoefficientSkipContextUsesDedicatedChromaRange() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY, Av1ChromaFormat.YUV444));
        BlockPosition position = new BlockPosition(0, 0);

        assertEquals(7, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_32X32,
                position,
                TransformSize.TX_32X32
        ));
        assertEquals(10, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_16X16,
                position,
                TransformSize.TX_4X4
        ));

        context.updateChromaCoefficientContext(0, position, TransformSize.TX_4X4, 0x82);

        assertEquals(11, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_16X16,
                new BlockPosition(1, 0),
                TransformSize.TX_4X4
        ));
        assertEquals(11, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_16X16,
                new BlockPosition(0, 1),
                TransformSize.TX_4X4
        ));
        assertEquals(12, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_16X16,
                position,
                TransformSize.TX_4X4
        ));
        assertEquals(10, context.chromaCoefficientSkipContext(
                1,
                BlockSize.SIZE_16X16,
                position,
                TransformSize.TX_4X4
        ));
    }

    /// Verifies that chroma coefficient-skip contexts account for subsampled chroma block size.
    @Test
    void chromaCoefficientSkipContextAccountsForSubsampling() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY, Av1ChromaFormat.YUV420));

        assertEquals(7, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_8X8,
                new BlockPosition(0, 0),
                TransformSize.TX_4X4
        ));
        assertEquals(10, context.chromaCoefficientSkipContext(
                0,
                BlockSize.SIZE_16X16,
                new BlockPosition(0, 0),
                TransformSize.TX_4X4
        ));
    }

    /// Verifies inter-frame initialization starts with non-intra neighbors.
    @Test
    void initializesInterFrameNeighborState() {
        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));

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

        BlockNeighborContext contextWithAbove = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        assertFalse(contextWithAbove.hasOverlappableCandidates(currentPosition, currentSize));
        contextWithAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertTrue(contextWithAbove.hasOverlappableCandidates(currentPosition, currentSize));

        BlockNeighborContext contextWithLeft = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        assertFalse(contextWithLeft.hasOverlappableCandidates(currentPosition, currentSize));
        contextWithLeft.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(12, 4))
        ));
        assertTrue(contextWithLeft.hasOverlappableCandidates(currentPosition, currentSize));

        BlockNeighborContext contextWithIntra = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY));
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

        BlockNeighborContext emptyContext = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        assertFalse(emptyContext.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithAbove = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        contextWithAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertTrue(contextWithAbove.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithDifferentReference = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        contextWithDifferentReference.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                1,
                null,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertFalse(contextWithDifferentReference.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithCompoundLeft = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
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

        BlockNeighborContext contextWithInterIntraAbove = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        contextWithInterIntraAbove.updateFromBlockHeader(singleReferenceInterIntraBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                InterMotionVector.resolved(new MotionVector(8, -4))
        ));
        assertFalse(contextWithInterIntraAbove.hasLocalWarpSamples(currentPosition, currentSize, 0));

        BlockNeighborContext contextWithProvisionalAbove = BlockNeighborContext.create(testTileContext(Av1FrameType.INTER));
        contextWithProvisionalAbove.updateFromBlockHeader(singleReferenceInterBlock(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                0,
                null,
                InterMotionVector.predicted(new MotionVector(8, -4))
        ));
        assertFalse(contextWithProvisionalAbove.hasLocalWarpSamples(currentPosition, currentSize, 0));
    }

    /// Verifies that intrabc displacement prediction prefers a direct above candidate and falls
    /// back when no same-frame candidate is available.
    @Test
    void selectsIntrabcDisplacementVectorFromDirectAboveCandidate() {
        BlockPosition currentPosition = new BlockPosition(0, 4);
        BlockSize currentSize = BlockSize.SIZE_16X16;
        MotionVector fallback = new MotionVector(0, -2560);
        MotionVector aboveVector = new MotionVector(-64, 0);

        BlockNeighborContext emptyContext = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY));
        assertEquals(
                fallback,
                emptyContext.intrabcReferenceMotionVector(currentPosition, currentSize, fallback)
        );

        BlockNeighborContext context = BlockNeighborContext.create(testTileContext(Av1FrameType.KEY));
        context.updateFromBlockHeader(intrabcBlock(
                new BlockPosition(1, 3),
                BlockSize.SIZE_4X4,
                aboveVector
        ));
        assertEquals(
                aboveVector,
                context.intrabcReferenceMotionVector(currentPosition, currentSize, fallback)
        );
    }

    /// Builds a provisional inter-mode context with zero translation-only global motion.
    ///
    /// @param context the neighbor context under test
    /// @param position the current block position
    /// @param size the current block size
    /// @param compoundReference whether the current block uses compound references
    /// @param referenceFrame0 the primary current-block reference
    /// @param referenceFrame1 the secondary current-block reference, or `-1`
    /// @return the provisional inter-mode context
    private static BlockNeighborContext.ProvisionalInterModeContext zeroGlobalMotionContext(
            BlockNeighborContext context,
            BlockPosition position,
            BlockSize size,
            boolean compoundReference,
            int referenceFrame0,
            int referenceFrame1
    ) {
        return context.provisionalInterModeContext(
                position,
                size,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                MotionVector.zero(),
                MotionVector.zero(),
                FrameHeader.GlobalMotionType.TRANSLATION,
                FrameHeader.GlobalMotionType.TRANSLATION
        );
    }

    /// Creates a simple tile context used by neighbor-context tests.
    ///
    /// @param frameType the synthetic frame type
    /// @return a simple tile context used by neighbor-context tests
    private static TileDecodeContext testTileContext(Av1FrameType frameType) {
        return testTileContext(frameType, Av1ChromaFormat.YUV420);
    }

    /// Creates a simple tile context used by neighbor-context tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param chromaFormat the synthetic decoded pixel format
    /// @return a simple tile context used by neighbor-context tests
    private static TileDecodeContext testTileContext(Av1FrameType frameType, Av1ChromaFormat chromaFormat) {
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
                new Av1ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        chromaFormat,
                        0,
                        chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422,
                        chromaFormat == Av1ChromaFormat.YUV420,
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
                false,
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
                FrameHeader.FilmGrainParams.disabled()
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[]{0x00}, 0, 1)}
        );
        return TileDecodeContext.create(assembly, 0);
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

    /// Creates one compact intrabc block header used by neighbor-context tests.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param motionVector the resolved same-frame displacement vector
    /// @return one compact intrabc block header
    private static TileBlockHeaderReader.BlockHeader intrabcBlock(
            BlockPosition position,
            BlockSize size,
            MotionVector motionVector
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                false,
                false,
                false,
                false,
                true,
                false,
                -1,
                -1,
                null,
                null,
                -1,
                InterMotionVector.resolved(motionVector),
                null,
                null,
                null,
                false,
                0,
                -1,
                0,
                new int[4],
                LumaIntraPredictionMode.DC,
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

    /// Creates one compact single-reference inter-intra block header used by neighbor-context tests.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the decoded block size
    /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
    /// @param motionVector0 the primary motion-vector state chosen for the block
    /// @return one compact single-reference inter-intra block header used by neighbor-context tests
    private static TileBlockHeaderReader.BlockHeader singleReferenceInterIntraBlock(
            BlockPosition position,
            BlockSize size,
            int referenceFrame0,
            InterMotionVector motionVector0
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
                SingleInterPredictionMode.NEARESTMV,
                null,
                -1,
                motionVector0,
                null,
                MotionMode.SIMPLE,
                null,
                null,
                null,
                false,
                -1,
                true,
                InterIntraPredictionMode.DC,
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
