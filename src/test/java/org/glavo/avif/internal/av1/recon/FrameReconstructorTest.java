// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.av1.Av1ColorConfig;
import org.glavo.avif.internal.av1.image.PaddedPlane;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.CompoundPredictionType;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.MotionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformType;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.glavo.avif.internal.av1.postfilter.FramePostprocessor;
import org.glavo.avif.testutil.InterPredictionOracle;
import org.glavo.avif.testutil.SuperResolutionOracle;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for AV1 frame reconstruction.
@NotNullByDefault
final class FrameReconstructorTest {
    /// Verifies that a synthetic single-tile 8-bit `MONOCHROME` key frame reconstructs into one opaque luma plane.
    @Test
    void reconstructsSingleTileI400KeyFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(Av1ChromaFormat.MONOCHROME, planes.chromaFormat());
        assertFalse(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
    }

    /// Verifies 10-bit `MONOCHROME` key-frame reconstruction with zero residuals.
    @Test
    void reconstructsSingleTileTenBitI400KeyFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 10, 4, 4);
        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(sequenceHeader, createFrameHeader(Av1FrameType.KEY, 4, 4), leaf)
        );

        assertEquals(10, planes.bitDepth());
        assertEquals(Av1ChromaFormat.MONOCHROME, planes.chromaFormat());
        assertFalse(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 512);
    }

    /// Verifies that a synthetic single-tile 8-bit `YUV420` intra frame reconstructs luma and chroma planes.
    @Test
    void reconstructsSingleTileI420IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(Av1ChromaFormat.YUV420, planes.chromaFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        PaddedPlane chromaU = planes.chromaUPlane();
        PaddedPlane chromaV = planes.chromaVPlane();
        assertNotNull(chromaU);
        assertNotNull(chromaV);
        assertPlaneFilled(chromaU, 2, 2, 128);
        assertPlaneFilled(chromaV, 2, 2, 128);
    }

    /// Verifies that smooth chroma context is read from the leaf owning an adjacent shared `YUV420`
    /// chroma cell rather than from a non-chroma luma leaf inside that cell.
    @Test
    void reconstructsSubsampledDirectionalChromaWithSmoothNeighborEdgeFiltering() {
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.Node[] roots = new TilePartitionTreeReader.Node[16];
        int rootIndex = 0;
        for (int y4 = 0; y4 < 4; y4++) {
            for (int x4 = 0; x4 < 4; x4++) {
                BlockPosition position = new BlockPosition(x4, y4);
                boolean hasChroma = (x4 & 1) != 0 && (y4 & 1) != 0;
                @Nullable UvIntraPredictionMode uvMode = null;
                int[] uPaletteColors = new int[0];
                int[] vPaletteColors = new int[0];
                byte[] uvPaletteIndices = new byte[0];
                if (hasChroma) {
                    if (x4 == 3 && y4 == 1) {
                        uvMode = UvIntraPredictionMode.SMOOTH;
                    } else if (x4 == 1 && y4 == 3) {
                        uvMode = UvIntraPredictionMode.DC;
                        uPaletteColors = new int[]{64, 65};
                        vPaletteColors = new int[]{64, 65};
                        uvPaletteIndices = new byte[8];
                    } else if (x4 == 3 && y4 == 3) {
                        uvMode = UvIntraPredictionMode.DIAGONAL_DOWN_RIGHT;
                    } else {
                        uvMode = UvIntraPredictionMode.DC;
                    }
                }

                TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                        position,
                        size,
                        hasChroma,
                        LumaIntraPredictionMode.DC,
                        uvMode,
                        null,
                        0,
                        0,
                        new int[0],
                        uPaletteColors,
                        vPaletteColors,
                        new byte[0],
                        uvPaletteIndices,
                        0,
                        0
                );
                TransformSize lumaTransformSize = size.maxLumaTransformSize();
                @Nullable TransformSize chromaTransformSize = null;
                TransformUnit[] chromaUnits = new TransformUnit[0];
                if (hasChroma) {
                    TransformSize sharedChromaTransformSize = Objects.requireNonNull(
                            size.maxChromaTransformSize(Av1ChromaFormat.YUV420),
                            "chromaTransformSize"
                    );
                    chromaTransformSize = sharedChromaTransformSize;
                    chromaUnits = new TransformUnit[]{new TransformUnit(
                            new BlockPosition(x4 & ~1, y4 & ~1),
                            sharedChromaTransformSize
                    )};
                }
                TransformLayout transformLayout = new TransformLayout(
                        position,
                        size,
                        size.width4(),
                        size.height4(),
                        size.widthPixels(),
                        size.heightPixels(),
                        lumaTransformSize,
                        chromaTransformSize,
                        false,
                        new TransformUnit[]{new TransformUnit(position, lumaTransformSize)},
                        chromaUnits
                );
                roots[rootIndex++] = new TilePartitionTreeReader.LeafNode(
                        header,
                        transformLayout,
                        createResidualLayout(position, size, true)
                );
            }
        }

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 16, 16, true);
        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(sequenceHeader, createFrameHeader(Av1FrameType.INTRA, 16, 16), roots)
        );

        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {64, 64, 64, 64, 128, 128, 128, 128},
                        {64, 64, 64, 64, 80, 128, 128, 128},
                        {64, 64, 64, 64, 64, 80, 128, 128},
                        {64, 64, 64, 64, 64, 64, 80, 128}
                }
        );
    }

    /// Verifies that a synthetic single-tile 8-bit `YUV422` intra frame reconstructs luma and full-height
    /// half-width chroma planes.
    @Test
    void reconstructsSingleTileI422IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV422),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, Av1FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(Av1ChromaFormat.YUV422, planes.chromaFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 2, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 2, 4, 128);
    }

    /// Verifies that a synthetic single-tile 8-bit `YUV444` intra frame reconstructs luma and full-size
    /// chroma planes.
    @Test
    void reconstructsSingleTileI444IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, Av1FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(Av1ChromaFormat.YUV444, planes.chromaFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 4, 4, 128);
    }

    /// Verifies 12-bit `YUV444` intra-frame reconstruction with zero residuals.
    @Test
    void reconstructsSingleTileTwelveBitI444IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV444, 12, 4, 4);
        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(sequenceHeader, createFrameHeader(Av1FrameType.INTRA, 4, 4), leaf)
        );

        assertEquals(12, planes.bitDepth());
        assertEquals(Av1ChromaFormat.YUV444, planes.chromaFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 2048);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 4, 4, 2048);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 4, 4, 2048);
    }

    /// Verifies one non-linear AV1 super-resolution row with MI-grid right padding.
    @Test
    void superResolutionOracleUsesNormativeEightTapFilter() {
        assertArrayEquals(
                new int[]{38, 20, 14, 70, 174, 255, 255, 167},
                SuperResolutionOracle.upscaleRow(
                        new int[]{32, 32, 224, 224, 0, 0, 0, 0},
                        4,
                        8,
                        8
                )
        );
    }

    /// Verifies that the super-resolution path normatively upsamples one reconstructed luma plane
    /// from the coded width to the upscaled width.
    @Test
    void reconstructsSuperResolvedI400KeyFrameByHorizontallyUpscalingLuma() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        int[] lumaPaletteColors = new int[]{32, 224};
        int[][] lumaPaletteIndices = new int[][]{
                {0, 0, 1, 1},
                {0, 0, 1, 1},
                {0, 0, 1, 1},
                {0, 0, 1, 1}
        };
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        false,
                        LumaIntraPredictionMode.DC,
                        null,
                        null,
                        0,
                        0,
                        lumaPaletteColors,
                        new int[0],
                        new int[0],
                        packPaletteIndices(lumaPaletteIndices),
                        new byte[0],
                        0,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 8, 4);
        FrameHeader frameHeader = createSuperResolvedFrameHeader(Av1FrameType.KEY, 4, 8, 4);
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf)),
                frameHeader
        );

        assertFalse(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(new int[]{32, 32, 224, 224}, 8),
                        expectedHorizontallyUpscaledRow(new int[]{32, 32, 224, 224}, 8),
                        expectedHorizontallyUpscaledRow(new int[]{32, 32, 224, 224}, 8),
                        expectedHorizontallyUpscaledRow(new int[]{32, 32, 224, 224}, 8)
                }
        );
    }

    /// Verifies normative super-resolution upscaling of `YUV420` chroma planes.
    @Test
    void reconstructsSuperResolvedI420IntraFrameByHorizontallyUpscalingChromaPlanes() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 4);
        FrameHeader frameHeader = createSuperResolvedFrameHeader(Av1FrameType.INTRA, 4, 8, 4);
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf)),
                frameHeader
        );

        assertTrue(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneFilled(planes.lumaPlane(), 8, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 4, 2, 128);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 4, 2, 128);
    }

    /// Verifies that the inter super-resolution path normatively upsamples one stored-reference
    /// luma prediction after the coded-domain inter copy has completed.
    @Test
    void reconstructsSuperResolvedI400InterFrameByHorizontallyUpscalingPredictedLuma() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        int[][] referenceLuma = {
                {8, 32, 96, 160},
                {16, 48, 112, 176},
                {24, 64, 128, 192},
                {32, 80, 144, 208}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot =
                createReferenceSurfaceSnapshot(Av1ChromaFormat.MONOCHROME, referenceLuma, null, null);
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(4, 8, 4, 0);
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        assertFalse(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(referenceLuma[0], 8),
                        expectedHorizontallyUpscaledRow(referenceLuma[1], 8),
                        expectedHorizontallyUpscaledRow(referenceLuma[2], 8),
                        expectedHorizontallyUpscaledRow(referenceLuma[3], 8)
                }
        );
    }

    /// Verifies that the inter super-resolution path samples one wider stored
    /// post-super-resolution luma surface in the coded domain before horizontally upscaling it.
    @Test
    void reconstructsSuperResolvedI400InterFrameFromPostSuperResolvedStoredReferenceSurface() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = MotionVector.zero();
        int[][] referenceLuma = {
                {4, 36, 128, 232, 176, 104, 44, 12},
                {12, 56, 152, 220, 184, 116, 64, 20},
                {24, 80, 176, 240, 196, 132, 84, 36},
                {40, 104, 196, 248, 212, 148, 104, 52}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createSuperResolvedReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                4,
                8,
                referenceLuma,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        assertTrue(referenceSurfaceSnapshot.frameHeader().superResolution().enabled());
        assertTrue(frameHeader.superResolution().enabled());
        assertFalse(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                expectedSuperResolvedInterPlaneFromMappedReference(
                        referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                        4,
                        4,
                        8,
                        motionVector,
                        8,
                        8,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.BILINEAR
                )
        );
    }

    /// Verifies that the inter super-resolution path normatively upsamples both luma and chroma
    /// after one bilinear single-reference `YUV420` prediction.
    @Test
    void reconstructsSuperResolvedI420InterFrameByHorizontallyUpscalingPredictedPlanes() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(4, 4);
        int[][] referenceLuma = {
                {10, 40, 90, 140},
                {30, 60, 110, 160},
                {50, 80, 130, 180},
                {70, 100, 150, 200}
        };
        int[][] referenceChromaU = {
                {90, 150},
                {130, 210}
        };
        int[][] referenceChromaV = {
                {180, 120},
                {140, 80}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                referenceLuma,
                referenceChromaU,
                referenceChromaV
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        int[][] codedLuma = InterPredictionOracle.sampleReferencePlaneBlock(
                referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                4,
                4,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                8,
                8,
                4,
                4,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        int[][] codedChromaU = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                2,
                2,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                16,
                16,
                2,
                2,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        int[][] codedChromaV = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaVPlane()),
                2,
                2,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                16,
                16,
                2,
                2,
                FrameHeader.InterpolationFilter.BILINEAR
        );

        assertTrue(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedLuma[0], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[1], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[2], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[3], 8)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaU[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaU[1], 4)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaV[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaV[1], 4)
                }
        );
    }

    /// Verifies that inter prediction samples a wider post-super-resolution `YUV420` reference
    /// surface before upscaling the coded-domain result into the current output domain.
    @Test
    void reconstructsSuperResolvedI420InterFrameFromPostSuperResolvedStoredReferenceSurface() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(2, 0);
        int[][] referenceLuma = {
                {10, 48, 132, 220, 164, 92, 40, 8},
                {26, 68, 150, 210, 176, 112, 60, 18},
                {44, 88, 168, 224, 192, 132, 82, 34},
                {62, 108, 184, 236, 208, 150, 104, 50}
        };
        int[][] referenceChromaU = {
                {40, 196, 52, 220},
                {60, 188, 72, 240}
        };
        int[][] referenceChromaV = {
                {220, 64, 180, 24},
                {200, 84, 160, 44}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createSuperResolvedReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                4,
                8,
                referenceLuma,
                referenceChromaU,
                referenceChromaV
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        assertTrue(referenceSurfaceSnapshot.frameHeader().superResolution().enabled());
        assertTrue(frameHeader.superResolution().enabled());
        assertTrue(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                expectedSuperResolvedInterPlaneFromMappedReference(
                        referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                        4,
                        4,
                        8,
                        motionVector,
                        8,
                        8,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.BILINEAR
                )
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                expectedSuperResolvedInterPlaneFromMappedReference(
                        requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                        2,
                        2,
                        4,
                        motionVector,
                        16,
                        16,
                        2,
                        2,
                        FrameHeader.InterpolationFilter.BILINEAR
                )
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                expectedSuperResolvedInterPlaneFromMappedReference(
                        requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaVPlane()),
                        2,
                        2,
                        4,
                        motionVector,
                        16,
                        16,
                        2,
                        2,
                        FrameHeader.InterpolationFilter.BILINEAR
                )
        );
    }

    /// Verifies that the inter super-resolution path also accepts `SWITCH` frames and normatively
    /// upsamples one bilinear `YUV420` inter prediction on both luma and chroma planes.
    @Test
    void reconstructsSuperResolvedI420SwitchFrameByHorizontallyUpscalingPredictedPlanes() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(4, 12);
        int[][] referenceLuma = {
                {0, 1, 2, 3},
                {10, 11, 12, 13},
                {20, 21, 22, 23},
                {30, 31, 32, 33}
        };
        int[][] referenceChromaU = {
                {100, 101},
                {110, 111}
        };
        int[][] referenceChromaV = {
                {150, 151},
                {160, 161}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                referenceLuma,
                referenceChromaU,
                referenceChromaV
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                Av1FrameType.SWITCH,
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        int[][] codedLuma = InterPredictionOracle.sampleReferencePlaneBlock(
                referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                4,
                4,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                8,
                8,
                4,
                4,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        int[][] codedChromaU = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                2,
                2,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                16,
                16,
                2,
                2,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        int[][] codedChromaV = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaVPlane()),
                2,
                2,
                motionVector.columnEighthPel(),
                motionVector.rowEighthPel(),
                16,
                16,
                2,
                2,
                FrameHeader.InterpolationFilter.BILINEAR
        );

        assertTrue(planes.hasChroma());
        assertEquals(8, planes.codedWidth());
        assertEquals(8, planes.renderWidth());
        assertEquals(4, planes.codedHeight());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedLuma[0], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[1], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[2], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[3], 8)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaU[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaU[1], 4)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaV[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaV[1], 4)
                }
        );
    }

    /// Verifies that the inter super-resolution path can sample one stored
    /// reference surface that already lives in the post-super-resolution domain when the active
    /// frame reconstructs at a smaller coded width.
    @Test
    void reconstructsSuperResolvedI400InterFrameFromPostUpscaleStoredReferenceSurface() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {0, 16, 32, 48, 64, 80, 96, 112},
                        {8, 24, 40, 56, 72, 88, 104, 120},
                        {16, 32, 48, 64, 80, 96, 112, 128},
                        {24, 40, 56, 72, 88, 104, 120, 136}
                },
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                Av1FrameType.INTER,
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        int[][] codedLuma = expectedScaledInterPlaneBilinearly(
                referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                4,
                4,
                0,
                0,
                8,
                8
        );
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedLuma[0], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[1], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[2], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[3], 8)
                }
        );
    }

    /// Verifies that the inter super-resolution path also remaps chroma planes
    /// from one stored post-super-resolution `YUV420` reference surface before the horizontal
    /// upscaling pass.
    @Test
    void reconstructsSuperResolvedI420InterFrameFromPostUpscaleStoredReferenceSurface() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {0, 16, 32, 48, 64, 80, 96, 112},
                        {8, 24, 40, 56, 72, 88, 104, 120},
                        {16, 32, 48, 64, 80, 96, 112, 128},
                        {24, 40, 56, 72, 88, 104, 120, 136}
                },
                new int[][]{
                        {80, 96, 112, 128},
                        {88, 104, 120, 136}
                },
                new int[][]{
                        {160, 144, 128, 112},
                        {152, 136, 120, 104}
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.YUV420, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(
                Av1FrameType.INTER,
                4,
                8,
                4,
                0,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        DecodedSurface planes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, leaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        int[][] codedLuma = expectedScaledInterPlaneBilinearly(
                referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                4,
                4,
                0,
                0,
                8,
                8
        );
        int[][] codedChromaU = expectedScaledInterPlaneBilinearly(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                2,
                2,
                0,
                0,
                16,
                16
        );
        int[][] codedChromaV = expectedScaledInterPlaneBilinearly(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaVPlane()),
                2,
                2,
                0,
                0,
                16,
                16
        );

        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedLuma[0], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[1], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[2], 8),
                        expectedHorizontallyUpscaledRow(codedLuma[3], 8)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaU[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaU[1], 4)
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        expectedHorizontallyUpscaledRow(codedChromaV[0], 4),
                        expectedHorizontallyUpscaledRow(codedChromaV[1], 4)
                }
        );
    }

    /// Verifies that the inter super-resolution path still overlays supported
    /// residuals before the horizontal upscaling pass.
    @Test
    void reconstructsSuperResolvedI400InterFrameWithResidualOverlay() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10}
                },
                null,
                null
        );
        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 8, 4);
        FrameHeader frameHeader = createSuperResolvedInterFrameHeader(Av1FrameType.INTER, 4, 8, 4, 0);
        TilePartitionTreeReader.LeafNode baselineLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        DecodedSurface baselinePlanes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, baselineLeaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );
        DecodedSurface reconstructedPlanes = postprocess(
                new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, residualLeaf),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                ),
                frameHeader
        );

        assertPlaneContainsPositiveButNoNegativeDifferences(
                baselinePlanes.lumaPlane(),
                reconstructedPlanes.lumaPlane()
        );
    }

    /// Verifies that one `MONOCHROME` luma palette block reconstructs the stored palette raster without
    /// requiring any chroma planes.
    @Test
    void reconstructsSingleTileI400KeyFrameWithLumaPalettePrediction() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] lumaPaletteColors = new int[]{32, 208};
        int[][] lumaPaletteIndices = new int[][]{
                {0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0},
                {0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0},
                {0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0},
                {0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0}
        };
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        false,
                        LumaIntraPredictionMode.DC,
                        null,
                        null,
                        0,
                        0,
                        lumaPaletteColors,
                        new int[0],
                        new int[0],
                        packPaletteIndices(lumaPaletteIndices),
                        new byte[0],
                        0,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 8, 8, leaf)
        );

        assertFalse(planes.hasChroma());
        assertPlaneEquals(planes.lumaPlane(), expandPaletteRaster(lumaPaletteColors, lumaPaletteIndices));
    }

    /// Verifies that one `YUV420` chroma palette block reconstructs the stored chroma palette rasters
    /// while leaving the luma prediction plane untouched.
    @Test
    void reconstructsSingleTileI420KeyFrameWithChromaPaletteWithoutPerturbingLuma() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaPaletteU = new int[]{40, 200};
        int[] chromaPaletteV = new int[]{180, 60};
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 1, 0},
                {1, 0, 0, 1},
                {0, 0, 1, 1},
                {1, 1, 0, 0}
        };
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[0],
                        chromaPaletteU,
                        chromaPaletteV,
                        new byte[0],
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
    }

    /// Verifies that one `YUV422` chroma palette block reconstructs the stored full-height chroma
    /// palette rasters while leaving the luma prediction plane untouched.
    @Test
    void reconstructsSingleTileI422KeyFrameWithChromaPaletteWithoutPerturbingLuma() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaPaletteU = new int[]{24, 144, 220};
        int[] chromaPaletteV = new int[]{200, 80, 32};
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 2, 0},
                {1, 2, 0, 1},
                {2, 0, 1, 2},
                {0, 2, 1, 0},
                {1, 0, 2, 1},
                {2, 1, 0, 2},
                {0, 1, 0, 2},
                {2, 0, 2, 1}
        };
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[0],
                        chromaPaletteU,
                        chromaPaletteV,
                        new byte[0],
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV422),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, Av1FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
    }

    /// Verifies that one `YUV444` chroma palette block reconstructs the stored full-resolution
    /// chroma palette rasters while leaving the luma prediction plane untouched.
    @Test
    void reconstructsSingleTileI444KeyFrameWithChromaPaletteWithoutPerturbingLuma() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaPaletteU = new int[]{16, 128, 240};
        int[] chromaPaletteV = new int[]{220, 96, 40};
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 2, 0, 1, 2, 0, 1},
                {1, 2, 0, 1, 2, 0, 1, 2},
                {2, 0, 1, 2, 0, 1, 2, 0},
                {0, 2, 1, 0, 2, 1, 0, 2},
                {1, 0, 2, 1, 0, 2, 1, 0},
                {2, 1, 0, 2, 1, 0, 2, 1},
                {0, 1, 0, 2, 0, 1, 0, 2},
                {2, 0, 2, 1, 2, 0, 2, 1}
        };
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[0],
                        chromaPaletteU,
                        chromaPaletteV,
                        new byte[0],
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, Av1FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
    }

    /// Verifies that clipped palette reconstruction retains the complete luma palette raster in
    /// padded storage while exposing cropped luma and chroma planes.
    @Test
    void reconstructsClippedKeyFramesWithLumaAndChromaPalettePredictionForAllChromaLayouts() {
        assertClippedPalettePrediction(Av1ChromaFormat.YUV420);
        assertClippedPalettePrediction(Av1ChromaFormat.YUV422);
        assertClippedPalettePrediction(Av1ChromaFormat.YUV444);
    }

    /// Asserts clipped luma and chroma palette reconstruction for one non-monochrome layout.
    ///
    /// @param chromaFormat the decoded chroma layout to test
    private static void assertClippedPalettePrediction(Av1ChromaFormat chromaFormat) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] lumaPaletteColors = new int[]{24, 128, 232};
        int[] chromaPaletteU = new int[]{40, 184};
        int[] chromaPaletteV = new int[]{208, 72};
        int frameWidth = 6;
        int frameHeight = 6;
        int[][] lumaPaletteIndices =
                repeatingPaletteIndices(size.widthPixels(), size.heightPixels(), lumaPaletteColors.length);
        int[][] chromaPaletteIndices = repeatingPaletteIndices(
                chromaSampleWidth(size.widthPixels(), chromaFormat),
                chromaSampleHeight(size.heightPixels(), chromaFormat),
                chromaPaletteU.length
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        lumaPaletteColors,
                        chromaPaletteU,
                        chromaPaletteV,
                        packPaletteIndices(lumaPaletteIndices),
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(position, size, chromaFormat),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(chromaFormat, Av1FrameType.KEY, frameWidth, frameHeight, leaf)
        );

        assertPlaneEquals(
                planes.lumaPlane(),
                cropRaster(expandPaletteRaster(lumaPaletteColors, lumaPaletteIndices), frameWidth, frameHeight)
        );
        assertStoredPlaneRegionEquals(
                planes.lumaPlane(),
                expandPaletteRaster(lumaPaletteColors, lumaPaletteIndices)
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                cropRaster(
                        expandPaletteRaster(chromaPaletteU, chromaPaletteIndices),
                        chromaSampleWidth(frameWidth, chromaFormat),
                        chromaSampleHeight(frameHeight, chromaFormat)
                )
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                cropRaster(
                        expandPaletteRaster(chromaPaletteV, chromaPaletteIndices),
                        chromaSampleWidth(frameWidth, chromaFormat),
                        chromaSampleHeight(frameHeight, chromaFormat)
                )
        );
    }

    /// Verifies chroma palette prediction followed by a residual overlay for `YUV420`, `YUV422`, and
    /// `YUV444`.
    @Test
    void reconstructsSingleTileWiderChromaKeyFramesWithChromaPaletteAndResidualOverlay() {
        assertChromaPaletteResidualOverlay(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {0, 1, 2, 0},
                        {1, 2, 0, 1},
                        {2, 0, 1, 2},
                        {0, 2, 1, 0}
                }
        );
        assertChromaPaletteResidualOverlay(
                Av1ChromaFormat.YUV422,
                new int[][]{
                        {0, 1, 2, 0},
                        {1, 2, 0, 1},
                        {2, 0, 1, 2},
                        {0, 2, 1, 0},
                        {1, 0, 2, 1},
                        {2, 1, 0, 2},
                        {0, 1, 0, 2},
                        {2, 0, 2, 1}
                }
        );
        assertChromaPaletteResidualOverlay(
                Av1ChromaFormat.YUV444,
                new int[][]{
                        {0, 1, 2, 0, 1, 2, 0, 1},
                        {1, 2, 0, 1, 2, 0, 1, 2},
                        {2, 0, 1, 2, 0, 1, 2, 0},
                        {0, 2, 1, 0, 2, 1, 0, 2},
                        {1, 0, 2, 1, 0, 2, 1, 0},
                        {2, 1, 0, 2, 1, 0, 2, 1},
                        {0, 1, 0, 2, 0, 1, 0, 2},
                        {2, 0, 2, 1, 2, 0, 2, 1}
                }
        );
    }

    /// Verifies that one non-zero luma residual is added on top of the reconstructed luma palette
    /// prediction instead of replacing it.
    @Test
    void reconstructsSingleTileI400KeyFrameWithLumaPaletteAndPositiveResidualOffset() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] lumaPaletteColors = new int[]{64, 160};
        int[][] lumaPaletteIndices = new int[][]{
                {0, 0, 1, 1, 0, 0, 1, 1},
                {0, 0, 1, 1, 0, 0, 1, 1},
                {1, 1, 0, 0, 1, 1, 0, 0},
                {1, 1, 0, 0, 1, 1, 0, 0},
                {0, 0, 1, 1, 0, 0, 1, 1},
                {0, 0, 1, 1, 0, 0, 1, 1},
                {1, 1, 0, 0, 1, 1, 0, 0},
                {1, 1, 0, 0, 1, 1, 0, 0}
        };
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                false,
                LumaIntraPredictionMode.DC,
                null,
                null,
                0,
                0,
                lumaPaletteColors,
                new int[0],
                new int[0],
                packPaletteIndices(lumaPaletteIndices),
                new byte[0],
                0,
                0
        );
        TilePartitionTreeReader.LeafNode baselineLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 8, 8, baselineLeaf)
        );
        DecodedSurface residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 8, 8, residualLeaf)
        );

        assertFalse(baseline.hasChroma());
        assertFalse(residual.hasChroma());
        assertPlaneEquals(baseline.lumaPlane(), expandPaletteRaster(lumaPaletteColors, lumaPaletteIndices));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline.lumaPlane(), residual.lumaPlane(), 1);
    }

    /// Verifies that a positive monochrome DC residual shifts every luma sample above the zero-residual baseline.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 4, 4, zeroResidualLeaf)
        ).lumaPlane();
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 4, 4, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that a transform clipped by the visible frame edge still writes its complete
    /// residual into the MI-grid padding retained by the decoded plane.
    @Test
    void reconstructsClippedLumaResidualIntoStoredRightAndBottomPadding() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TransformSize transformSize = size.maxLumaTransformSize();
        TransformLayout transformLayout = createTransformLayout(
                position,
                size,
                Av1ChromaFormat.MONOCHROME,
                6,
                6
        );
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                false,
                LumaIntraPredictionMode.DC,
                null,
                null,
                0,
                0,
                0,
                0
        );
        TilePartitionTreeReader.LeafNode baselineLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                transformLayout,
                new ResidualLayout(
                        position,
                        size,
                        new TransformResidualUnit[]{createResidualUnit(position, transformSize, 6, 6, 0)}
                )
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                transformLayout,
                new ResidualLayout(
                        position,
                        size,
                        new TransformResidualUnit[]{createResidualUnit(position, transformSize, 6, 6, 64)}
                )
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 6, 6, baselineLeaf)
        ).lumaPlane();
        PaddedPlane reconstructed = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 6, 6, residualLeaf)
        ).lumaPlane();

        assertTrue(baseline.stride() >= transformSize.widthPixels());
        assertTrue(baseline.storageHeight() >= transformSize.heightPixels());
        short[] baselineSamples = baseline.samples();
        short[] reconstructedSamples = reconstructed.samples();
        int expectedDelta = (reconstructedSamples[0] & 0xFFFF) - (baselineSamples[0] & 0xFFFF);
        assertTrue(expectedDelta > 0);
        for (int y = 0; y < transformSize.heightPixels(); y++) {
            for (int x = 0; x < transformSize.widthPixels(); x++) {
                int index = y * baseline.stride() + x;
                assertEquals(
                        expectedDelta,
                        (reconstructedSamples[index] & 0xFFFF) - (baselineSamples[index] & 0xFFFF),
                        "stored sample at (" + x + ", " + y + ")"
                );
            }
        }
    }

    /// Verifies that frame reconstruction consumes the explicit transform type stored on each
    /// residual unit instead of treating every unit as `DCT_DCT`.
    @Test
    void reconstructsSingleTileI400KeyFrameWithIdentityTransformResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TransformSize transformSize = size.maxLumaTransformSize();
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[5] = 64;
        TransformResidualUnit residualUnit = new TransformResidualUnit(
                position,
                transformSize,
                TransformType.IDTX,
                5,
                coefficients,
                0x40 | 63
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                new ResidualLayout(position, size, new TransformResidualUnit[]{residualUnit})
        );

        PaddedPlane reconstructed = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 4, 4, residualLeaf)
        ).lumaPlane();
        int[] dequantizedCoefficients = LumaDequantizer.dequantize(
                residualUnit,
                new LumaDequantizer.Context(0, 0, 8)
        );
        int[] expectedResidual = new InverseTransformer().reconstructResidualBlock(
                dequantizedCoefficients,
                transformSize,
                TransformType.IDTX
        );
        int[][] expectedSamples = new int[4][4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                expectedSamples[y][x] = clamp(128 + expectedResidual[(y << 2) + x], 0, 255);
            }
        }

        assertPlaneEquals(reconstructed, expectedSamples);
        assertEquals(128, reconstructed.sample(0, 0));
        assertTrue(reconstructed.sample(1, 1) > 128);
    }

    /// Verifies that a positive monochrome DC residual produces a uniform 10-bit luma plane.
    @Test
    void reconstructsSingleTileTenBitI400KeyFrameWithPositiveDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        SequenceHeader sequenceHeader = createSequenceHeader(Av1ChromaFormat.MONOCHROME, 10, 4, 4);
        FrameSyntaxDecodeResult baselineSyntax =
                createFrameSyntaxDecodeResult(sequenceHeader, createFrameHeader(Av1FrameType.KEY, 4, 4), zeroResidualLeaf);
        FrameSyntaxDecodeResult residualSyntax =
                createFrameSyntaxDecodeResult(sequenceHeader, createFrameHeader(Av1FrameType.KEY, 4, 4), positiveResidualLeaf);

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(baselineSyntax).lumaPlane();
        PaddedPlane residual = reconstructor.reconstruct(residualSyntax).lumaPlane();

        int residualSample = residual.sample(0, 0);
        assertTrue(residualSample > baseline.sample(0, 0));
        assertPlaneFilled(residual, 4, 4, residualSample);
    }

    /// Verifies that a negative `YUV420` luma DC residual shifts only the luma plane below the zero-residual baseline.
    @Test
    void reconstructsSingleTileI420IntraFrameWithNegativeLumaDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode negativeResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.INTRA, 4, 4, negativeResidualLeaf)
        );

        assertTrue(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline.lumaPlane(), residualPlanes.lumaPlane(), -1);
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residualPlanes.chromaUPlane()));
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one positive `YUV422` chroma-U DC residual shifts only the U plane while keeping
    /// luma and chroma-V unchanged.
    @Test
    void reconstructsSingleTileI422IntraFrameWithPositiveChromaUDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                true,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                null,
                0,
                0,
                0,
                0
        );
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV422),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV422),
                createChromaDcResidualLayout(position, size, Av1ChromaFormat.YUV422, 64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, Av1FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, Av1FrameType.INTRA, 4, 4, positiveChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one negative `YUV444` chroma-V DC residual shifts only the V plane while keeping
    /// luma and chroma-U unchanged.
    @Test
    void reconstructsSingleTileI444IntraFrameWithNegativeChromaVDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                true,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                null,
                0,
                0,
                0,
                0
        );
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode negativeChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createChromaDcResidualLayout(position, size, Av1ChromaFormat.YUV444, 0, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, Av1FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, Av1FrameType.INTRA, 4, 4, negativeChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residualPlanes.chromaUPlane()));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residualPlanes.chromaVPlane()),
                -1
        );
    }

    /// Verifies that one `TX_16X16` luma DC residual reconstructs successfully.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveSixteenBySixteenDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X16;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 16, 16, zeroResidualLeaf)
        ).lumaPlane();
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 16, 16, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one rectangular `RTX_16X8` luma DC residual reconstructs successfully.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveRectangularDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X8;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 16, 8, zeroResidualLeaf)
        ).lumaPlane();
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 16, 8, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one `TX_32X32` luma DC residual reconstructs successfully.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveThirtyTwoByThirtyTwoDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_32X32;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 32, 32, zeroResidualLeaf)
        ).lumaPlane();
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 32, 32, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one `TX_64X64` luma DC residual reconstructs successfully after the large
    /// transform dequantization shift.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveLargeSixtyFourBySixtyFourDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_64X64;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 1024)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        PaddedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 64, 64, zeroResidualLeaf)
        ).lumaPlane();
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, Av1FrameType.KEY, 64, 64, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that a larger `YUV420` chroma residual does not perturb luma or the V plane.
    @Test
    void reconstructsSingleTileI420KeyFrameWithPositiveThirtyTwoByThirtyTwoChromaResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_64X64;
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                true,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                null,
                0,
                0,
                0,
                0
        );
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createChromaDcResidualLayout(position, size, Av1ChromaFormat.YUV420, 64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.INTRA, 64, 64, zeroResidualLeaf)
        );
        DecodedSurface residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.INTRA, 64, 64, positiveChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies filter-intra luma reconstruction with `YUV420` CFL chroma prediction.
    @Test
    void reconstructsI420BlockWithFilterIntraAndCflPrediction() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.CFL,
                        FilterIntraMode.DC,
                        0,
                        0,
                        4,
                        -4
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 128, 128, 127},
                        {129, 128, 128, 128},
                        {129, 128, 128, 128},
                        {129, 129, 129, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {128, 128},
                        {128, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {128, 128},
                        {128, 128}
                }
        );
    }

    /// Verifies that `YUV422` CFL derives chroma AC from horizontally subsampled reconstructed luma.
    @Test
    void reconstructsI422BlockWithFilterIntraAndCflPrediction() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.CFL,
                        FilterIntraMode.DC,
                        0,
                        0,
                        4,
                        -4
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV422),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, Av1FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 128, 128, 127},
                        {129, 128, 128, 128},
                        {129, 128, 128, 128},
                        {129, 129, 129, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {128, 128},
                        {128, 128},
                        {128, 128},
                        {128, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {128, 128},
                        {128, 128},
                        {128, 128},
                        {128, 128}
                }
        );
    }

    /// Verifies that `YUV444` CFL derives chroma AC from full-resolution reconstructed luma.
    @Test
    void reconstructsI444BlockWithFilterIntraAndCflPrediction() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        position,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.CFL,
                        FilterIntraMode.DC,
                        0,
                        0,
                        4,
                        -4
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, Av1FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 128, 128, 127},
                        {129, 128, 128, 128},
                        {129, 128, 128, 128},
                        {129, 129, 129, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {128, 128, 128, 127},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {128, 128, 128, 129},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128}
                }
        );
    }

    /// Verifies that one synthetic directional `YUV420` leaf reconstructs successfully after top neighbors are already available.
    @Test
    void reconstructsDirectionalI420LeafFromReconstructedTopNeighbors() {
        TilePartitionTreeReader.LeafNode topLeftLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 0),
                        BlockSize.SIZE_4X4,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.CFL,
                        FilterIntraMode.DC,
                        0,
                        0,
                        0,
                        0,
                        4,
                        -4
                ),
                createTransformLayout(new BlockPosition(0, 0), BlockSize.SIZE_4X4, Av1ChromaFormat.YUV420),
                createResidualLayout(new BlockPosition(0, 0), BlockSize.SIZE_4X4, true)
        );
        TilePartitionTreeReader.LeafNode topRightLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(1, 0),
                        BlockSize.SIZE_4X4,
                        true,
                        LumaIntraPredictionMode.HORIZONTAL,
                        UvIntraPredictionMode.HORIZONTAL,
                        null,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(1, 0), BlockSize.SIZE_4X4, Av1ChromaFormat.YUV420),
                createResidualLayout(new BlockPosition(1, 0), BlockSize.SIZE_4X4, true)
        );
        TilePartitionTreeReader.LeafNode bottomLeftDirectionalLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 1),
                        BlockSize.SIZE_4X4,
                        true,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        UvIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        null,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(0, 1), BlockSize.SIZE_4X4, Av1ChromaFormat.YUV420),
                createResidualLayout(new BlockPosition(0, 1), BlockSize.SIZE_4X4, true)
        );

        DecodedSurface baseline = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, Av1FrameType.KEY, 8, 8, topLeftLeaf, topRightLeaf)
        );
        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        Av1FrameType.KEY,
                        8,
                        8,
                        topLeftLeaf,
                        topRightLeaf,
                        bottomLeftDirectionalLeaf
                )
        );

        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                4,
                DirectionalIntraPredictionOracle.predictLuma(
                        baseline.lumaPlane(),
                        8,
                        0,
                        4,
                        4,
                        4,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        0
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                0,
                2,
                DirectionalIntraPredictionOracle.predictChroma(
                        requirePlane(baseline.chromaUPlane()),
                        8,
                        0,
                        2,
                        2,
                        2,
                        UvIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        0
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                0,
                2,
                DirectionalIntraPredictionOracle.predictChroma(
                        requirePlane(baseline.chromaVPlane()),
                        8,
                        0,
                        2,
                        2,
                        2,
                        UvIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        0
                )
        );
    }

    /// Verifies that a lower transform in a 128-wide `YUV422` intra block does not consume
    /// top-right samples across AV1's causal 64-luma-sample region boundary.
    @Test
    void wideI422DirectionalTransformDoesNotUseUnavailableTopRightSamples() {
        DecodedSurface baseline = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV422,
                        Av1FrameType.KEY,
                        128,
                        64,
                        createWideDirectionalI422Leaf(0)
                )
        );
        DecodedSurface changedTopRight = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV422,
                        Av1FrameType.KEY,
                        128,
                        64,
                        createWideDirectionalI422Leaf(4096)
                )
        );

        PaddedPlane baselineU = requirePlane(baseline.chromaUPlane());
        PaddedPlane baselineV = requirePlane(baseline.chromaVPlane());
        PaddedPlane changedU = requirePlane(changedTopRight.chromaUPlane());
        PaddedPlane changedV = requirePlane(changedTopRight.chromaVPlane());
        boolean topRightChanged = false;
        for (int y = 0; y < 32; y++) {
            for (int x = 32; x < 64; x++) {
                topRightChanged |= baselineU.sample(x, y) != changedU.sample(x, y);
                topRightChanged |= baselineV.sample(x, y) != changedV.sample(x, y);
            }
        }
        assertTrue(topRightChanged, "The test stimulus must change the top-right transform");

        for (int y = 32; y < 64; y++) {
            for (int x = 0; x < 32; x++) {
                assertEquals(baselineU.sample(x, y), changedU.sample(x, y), "U sample at " + x + "," + y);
                assertEquals(baselineV.sample(x, y), changedV.sample(x, y), "V sample at " + x + "," + y);
            }
        }
    }

    /// Verifies that one non-zero directional luma leaf adds its residual after directional
    /// prediction without perturbing the already reconstructed reference row.
    @Test
    void reconstructsDirectionalI400LeafWithPositiveResidualOffset() {
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode topLeftLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(0, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(0, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 0), size, 64)
        );
        TilePartitionTreeReader.LeafNode topMiddleLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(1, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(1, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(1, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode topRightLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(2, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(2, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(2, 0), size, -64)
        );
        TilePartitionTreeReader.LeafNode baselineDirectionalLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 1),
                        size,
                        false,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        null,
                        null,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(0, 1), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 1), size, true)
        );
        TilePartitionTreeReader.LeafNode positiveDirectionalLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 1),
                        size,
                        false,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        null,
                        null,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(0, 1), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 1), size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        Av1FrameType.KEY,
                        12,
                        8,
                        topLeftLeaf,
                        topMiddleLeaf,
                        topRightLeaf,
                        baselineDirectionalLeaf
                )
        );
        DecodedSurface residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        Av1FrameType.KEY,
                        12,
                        8,
                        topLeftLeaf,
                        topMiddleLeaf,
                        topRightLeaf,
                        positiveDirectionalLeaf
                )
        );

        assertFalse(baseline.hasChroma());
        assertFalse(residual.hasChroma());
        assertPlaneRegionEquals(baseline.lumaPlane(), residual.lumaPlane(), 0, 0, 12, 4);
        assertPlaneRegionDiffersFromBaselineByUniformSignedOffset(
                baseline.lumaPlane(),
                residual.lumaPlane(),
                0,
                4,
                4,
                4,
                1
        );
    }

    /// Verifies that one monochrome single-reference inter block copies samples from one stored
    /// reference surface using an integer-pel motion vector.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithIntegerMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {0, 1, 2, 3, 4, 5, 6, 7},
                        {10, 11, 12, 13, 14, 15, 16, 17},
                        {20, 21, 22, 23, 24, 25, 26, 27},
                        {30, 31, 32, 33, 34, 35, 36, 37},
                        {40, 41, 42, 43, 44, 45, 46, 47},
                        {50, 51, 52, 53, 54, 55, 56, 57},
                        {60, 61, 62, 63, 64, 65, 66, 67},
                        {70, 71, 72, 73, 74, 75, 76, 77}
                },
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, new MotionVector(8, 8)),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 8, 8, 0, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertFalse(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                new int[][]{
                        {11, 12, 13, 14},
                        {21, 22, 23, 24},
                        {31, 32, 33, 34},
                        {41, 42, 43, 44}
                }
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
    }

    /// Verifies that one `YUV420` single-reference inter block copies both luma and chroma samples
    /// from one stored reference surface when the motion vector stays chroma-aligned.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithZeroMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {10, 11, 12, 13},
                        {20, 21, 22, 23},
                        {30, 31, 32, 33},
                        {40, 41, 42, 43}
                },
                new int[][]{
                        {100, 101},
                        {110, 111}
                },
                new int[][]{
                        {150, 151},
                        {160, 161}
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 4, 4, 0, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneEquals(planes.lumaPlane(), new int[][]{
                {10, 11, 12, 13},
                {20, 21, 22, 23},
                {30, 31, 32, 33},
                {40, 41, 42, 43}
        });
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), new int[][]{
                {100, 101},
                {110, 111}
        });
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), new int[][]{
                {150, 151},
                {160, 161}
        });
    }

    /// Verifies that every inter-intra mode blends the sampled inter and secondary intra predictors.
    @Test
    void reconstructsSingleReferenceI400InterIntraBlendBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] referenceLuma = new int[][]{
                {0, 8, 16, 24, 32, 40, 48, 56},
                {10, 18, 26, 34, 42, 50, 58, 66},
                {20, 28, 36, 44, 52, 60, 68, 76},
                {30, 38, 46, 54, 62, 70, 78, 86},
                {40, 48, 56, 64, 72, 80, 88, 96},
                {50, 58, 66, 74, 82, 90, 98, 106},
                {60, 68, 76, 84, 92, 100, 108, 116},
                {70, 78, 86, 94, 102, 110, 118, 126}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                referenceLuma,
                null,
                null
        );
        for (InterIntraPredictionMode mode : InterIntraPredictionMode.values()) {
            TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                    createSingleReferenceInterIntraBlockHeader(
                            position,
                            size,
                            false,
                            0,
                            MotionVector.zero(),
                            mode,
                            false,
                            -1
                    ),
                    createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                    createResidualLayout(position, size, true)
            );

            DecodedSurface planes = new FrameReconstructor().reconstruct(
                    createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 8, 8, 0, leaf),
                    createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
            );

            assertFalse(planes.hasChroma());
            assertPlaneEquals(
                    planes.lumaPlane(),
                    expectedInterIntraBlend(referenceLuma, mode, false, -1, size, 0, 0)
            );
        }
    }

    /// Verifies that one `YUV420` single-reference inter-intra block applies wedge blending to luma
    /// and chroma planes with the AV1 chroma-subsampled mask.
    @Test
    void reconstructsSingleReferenceI420InterIntraWedgeBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] referenceLuma = new int[][]{
                {20, 25, 30, 35, 40, 45, 50, 55},
                {28, 33, 38, 43, 48, 53, 58, 63},
                {36, 41, 46, 51, 56, 61, 66, 71},
                {44, 49, 54, 59, 64, 69, 74, 79},
                {52, 57, 62, 67, 72, 77, 82, 87},
                {60, 65, 70, 75, 80, 85, 90, 95},
                {68, 73, 78, 83, 88, 93, 98, 103},
                {76, 81, 86, 91, 96, 101, 106, 111}
        };
        int[][] referenceChromaU = new int[][]{
                {90, 100, 110, 120},
                {94, 104, 114, 124},
                {98, 108, 118, 128},
                {102, 112, 122, 132}
        };
        int[][] referenceChromaV = new int[][]{
                {160, 150, 140, 130},
                {156, 146, 136, 126},
                {152, 142, 132, 122},
                {148, 138, 128, 118}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                referenceLuma,
                referenceChromaU,
                referenceChromaV
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterIntraBlockHeader(
                        position,
                        size,
                        true,
                        0,
                        MotionVector.zero(),
                        InterIntraPredictionMode.DC,
                        true,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 8, 8, 0, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneEquals(
                planes.lumaPlane(),
                expectedInterIntraBlend(referenceLuma, InterIntraPredictionMode.DC, true, 0, size, 0, 0)
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                expectedInterIntraBlend(referenceChromaU, InterIntraPredictionMode.DC, true, 0, size, 1, 1)
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                expectedInterIntraBlend(referenceChromaV, InterIntraPredictionMode.DC, true, 0, size, 1, 1)
        );
    }

    /// Verifies that integer-aligned `intrabc` copies luma from an earlier same-frame block.
    @Test
    void reconstructsIntrabcI400BlockFromPreviouslyDecodedSamples() {
        BlockSize size = BlockSize.SIZE_4X4;
        int[][] lumaPaletteIndices = new int[][]{
                {0, 1, 0, 1},
                {1, 0, 1, 0},
                {0, 1, 0, 1},
                {1, 0, 1, 0}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 0),
                        size,
                        false,
                        LumaIntraPredictionMode.DC,
                        null,
                        null,
                        0,
                        0,
                        new int[]{32, 224},
                        new int[0],
                        new int[0],
                        packPaletteIndices(lumaPaletteIndices),
                        new byte[0],
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(0, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(new BlockPosition(1, 0), size, false, new MotionVector(0, -32)),
                createTransformLayout(new BlockPosition(1, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(1, 0), size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 8, 4, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        int[][] expectedSourceSamples = expandPaletteRaster(new int[]{32, 224}, lumaPaletteIndices);
        assertPlaneBlockEquals(planes.lumaPlane(), 0, 0, expectedSourceSamples);
        assertPlaneBlockEquals(planes.lumaPlane(), 4, 0, expectedSourceSamples);
    }

    /// Verifies that `YUV420` `intrabc` copies chroma between sub-8 block footprints.
    @Test
    void reconstructsIntrabcI420BlockFromPreviouslyDecodedSamples() {
        BlockSize size = BlockSize.SIZE_4X4;
        BlockPosition sourcePosition = new BlockPosition(1, 1);
        BlockPosition intrabcPosition = new BlockPosition(3, 1);
        MotionVector motionVector = new MotionVector(0, -64);
        int[][] lumaPaletteIndices = new int[][]{
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0}
        };
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 1, 0},
                {1, 0, 0, 1},
                {1, 1, 0, 0},
                {0, 0, 1, 1}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        sourcePosition,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[]{128},
                        new int[]{40, 180},
                        new int[]{200, 60},
                        packPaletteIndices(lumaPaletteIndices),
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(sourcePosition, size, Av1ChromaFormat.YUV420),
                createResidualLayout(sourcePosition, size, true)
        );
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(intrabcPosition, size, true, motionVector),
                createTransformLayout(intrabcPosition, size, Av1ChromaFormat.YUV420),
                createResidualLayout(intrabcPosition, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 16, 8, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        assertPlaneBlockFilled(planes.lumaPlane(), 4, 4, 4, 4, 128);
        assertPlaneBlockFilled(planes.lumaPlane(), 12, 4, 4, 4, 128);
        int[][] expectedChromaU = expandPaletteRaster(new int[]{40, 180}, chromaPaletteIndices);
        int[][] expectedChromaV = expandPaletteRaster(new int[]{200, 60}, chromaPaletteIndices);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 0, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 4, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 0, 0, expectedChromaV);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 4, 0, expectedChromaV);
    }

    /// Verifies that integer-aligned `YUV444` `intrabc` copies full-resolution chroma.
    @Test
    void reconstructsIntrabcI444BlockFromPreviouslyDecodedSamples() {
        BlockSize size = BlockSize.SIZE_4X4;
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 0, 1},
                {1, 0, 1, 0},
                {0, 0, 1, 1},
                {1, 1, 0, 0}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        new BlockPosition(0, 0),
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[0],
                        new int[]{24, 176},
                        new int[]{220, 60},
                        new byte[0],
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(new BlockPosition(0, 0), size, Av1ChromaFormat.YUV444),
                createResidualLayout(new BlockPosition(0, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(new BlockPosition(1, 0), size, true, new MotionVector(0, -32)),
                createTransformLayout(new BlockPosition(1, 0), size, Av1ChromaFormat.YUV444),
                createResidualLayout(new BlockPosition(1, 0), size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, 8, 4, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        assertPlaneBlockFilled(planes.lumaPlane(), 0, 0, 4, 4, 128);
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 4, 128);
        int[][] expectedChromaU = expandPaletteRaster(new int[]{24, 176}, chromaPaletteIndices);
        int[][] expectedChromaV = expandPaletteRaster(new int[]{220, 60}, chromaPaletteIndices);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 0, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 4, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 0, 0, expectedChromaV);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 4, 0, expectedChromaV);
    }

    /// Verifies that `YUV422` `intrabc` copies half-width, full-height chroma between sub-8 blocks.
    @Test
    void reconstructsIntrabcI422BlockFromPreviouslyDecodedSamples() {
        BlockSize size = BlockSize.SIZE_4X4;
        BlockPosition sourcePosition = new BlockPosition(1, 0);
        BlockPosition intrabcPosition = new BlockPosition(3, 0);
        MotionVector motionVector = new MotionVector(0, -64);
        int[][] lumaPaletteIndices = new int[][]{
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0}
        };
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 1, 1},
                {1, 0, 0, 0},
                {0, 0, 0, 0},
                {1, 1, 1, 1}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        sourcePosition,
                        size,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[]{128},
                        new int[]{36, 180},
                        new int[]{208, 72},
                        packPaletteIndices(lumaPaletteIndices),
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(sourcePosition, size, Av1ChromaFormat.YUV422),
                createResidualLayout(sourcePosition, size, true)
        );
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(intrabcPosition, size, true, motionVector),
                createTransformLayout(intrabcPosition, size, Av1ChromaFormat.YUV422),
                createResidualLayout(intrabcPosition, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV422, 16, 4, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 4, 128);
        assertPlaneBlockFilled(planes.lumaPlane(), 12, 0, 4, 4, 128);
        int[][] expectedChromaU = expandPaletteRaster(new int[]{36, 180}, chromaPaletteIndices);
        int[][] expectedChromaV = expandPaletteRaster(new int[]{208, 72}, chromaPaletteIndices);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 0, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 4, 0, expectedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 0, 0, expectedChromaV);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 4, 0, expectedChromaV);
    }

    /// Verifies reconstructed monochrome luma sampling at a non-block-aligned integer offset.
    @Test
    void reconstructsIntrabcI400BlockWithIntegerPixelMotionVector() {
        BlockPosition sourcePosition = new BlockPosition(0, 0);
        BlockSize sourceSize = BlockSize.SIZE_16X8;
        int[][] sourcePaletteIndices = new int[][]{
                {0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0},
                {0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1},
                {1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0},
                {0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0},
                {1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1},
                {0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0},
                {1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        sourcePosition,
                        sourceSize,
                        false,
                        LumaIntraPredictionMode.DC,
                        null,
                        null,
                        0,
                        0,
                        new int[]{24, 220},
                        new int[0],
                        new int[0],
                        packPaletteIndices(sourcePaletteIndices),
                        new byte[0],
                        0,
                        0
                ),
                createTransformLayout(sourcePosition, sourceSize, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(sourcePosition, sourceSize, true)
        );
        BlockPosition intrabcPosition = new BlockPosition(4, 0);
        MotionVector motionVector = new MotionVector(0, -120);
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(intrabcPosition, BlockSize.SIZE_8X8, false, motionVector),
                createTransformLayout(intrabcPosition, BlockSize.SIZE_8X8, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(intrabcPosition, BlockSize.SIZE_8X8, true)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baselinePlanes = reconstructor.reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 24, 8, 0, sourceLeaf),
                new ReferenceSurfaceSnapshot[0]
        );
        DecodedSurface planes = reconstructor.reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 24, 8, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        int[][] expectedSourceSamples = expandPaletteRaster(new int[]{24, 220}, sourcePaletteIndices);
        int[][] expectedCopiedSamples = InterPredictionOracle.sampleReferencePlaneBlock(
                baselinePlanes.lumaPlane(),
                8,
                8,
                (intrabcPosition.x4() << 5) + motionVector.columnEighthPel(),
                (intrabcPosition.y4() << 5) + motionVector.rowEighthPel(),
                8,
                8,
                8,
                8,
                FrameHeader.InterpolationFilter.BILINEAR
        );

        assertPlaneBlockEquals(planes.lumaPlane(), 0, 0, expectedSourceSamples);
        assertPlaneBlockEquals(planes.lumaPlane(), intrabcPosition.x4() << 2, intrabcPosition.y4() << 2, expectedCopiedSamples);
    }

    /// Verifies bilinear `YUV420` chroma sampling from an integer luma displacement.
    @Test
    void reconstructsIntrabcI420BlockWithFractionalChromaOffset() {
        BlockPosition sourcePosition = new BlockPosition(0, 0);
        BlockSize sourceSize = BlockSize.SIZE_16X8;
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 1, 0, 0, 1, 1, 0},
                {1, 0, 0, 1, 1, 0, 0, 1},
                {0, 0, 1, 1, 0, 0, 1, 1},
                {1, 1, 0, 0, 1, 1, 0, 0}
        };
        TilePartitionTreeReader.LeafNode sourceLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        sourcePosition,
                        sourceSize,
                        true,
                        LumaIntraPredictionMode.DC,
                        UvIntraPredictionMode.DC,
                        null,
                        0,
                        0,
                        new int[0],
                        new int[]{36, 180},
                        new int[]{208, 72},
                        new byte[0],
                        packPaletteIndices(chromaPaletteIndices),
                        0,
                        0
                ),
                createTransformLayout(sourcePosition, sourceSize, Av1ChromaFormat.YUV420),
                createResidualLayout(sourcePosition, sourceSize, true)
        );
        BlockPosition intrabcPosition = new BlockPosition(4, 0);
        MotionVector motionVector = new MotionVector(8, -120);
        TilePartitionTreeReader.LeafNode intrabcLeaf = new TilePartitionTreeReader.LeafNode(
                createIntrabcBlockHeader(intrabcPosition, BlockSize.SIZE_8X8, true, motionVector),
                createTransformLayout(intrabcPosition, BlockSize.SIZE_8X8, Av1ChromaFormat.YUV420),
                createResidualLayout(intrabcPosition, BlockSize.SIZE_8X8, true)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baselinePlanes = reconstructor.reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 24, 8, 0, sourceLeaf),
                new ReferenceSurfaceSnapshot[0]
        );
        DecodedSurface planes = reconstructor.reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 24, 8, 0, sourceLeaf, intrabcLeaf),
                new ReferenceSurfaceSnapshot[0]
        );

        int[][] expectedSourceChromaU = expandPaletteRaster(new int[]{36, 180}, chromaPaletteIndices);
        int[][] expectedSourceChromaV = expandPaletteRaster(new int[]{208, 72}, chromaPaletteIndices);
        int chromaX = intrabcPosition.x4() << 1;
        int chromaY = intrabcPosition.y4() << 1;
        int[][] expectedCopiedChromaU = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(baselinePlanes.chromaUPlane()),
                4,
                4,
                chromaX * 16 + motionVector.columnEighthPel(),
                chromaY * 16 + motionVector.rowEighthPel(),
                16,
                16,
                4,
                4,
                FrameHeader.InterpolationFilter.BILINEAR
        );
        int[][] expectedCopiedChromaV = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(baselinePlanes.chromaVPlane()),
                4,
                4,
                chromaX * 16 + motionVector.columnEighthPel(),
                chromaY * 16 + motionVector.rowEighthPel(),
                16,
                16,
                4,
                4,
                FrameHeader.InterpolationFilter.BILINEAR
        );

        assertPlaneFilled(planes.lumaPlane(), 24, 8, 128);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 0, 0, expectedSourceChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 0, 0, expectedSourceChromaV);
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), chromaX, chromaY, expectedCopiedChromaU);
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), chromaX, chromaY, expectedCopiedChromaV);
    }

    /// Verifies that one monochrome compound-reference inter block averages two stored reference
    /// surfaces when both motion vectors stay integer-aligned.
    @Test
    void reconstructsCompoundReferenceI400InterBlockFromStoredSurfacesWithIntegerMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {0, 2, 4, 6},
                        {20, 22, 24, 26},
                        {40, 42, 44, 46},
                        {60, 62, 64, 66}
                },
                null,
                null
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {10, 12, 14, 16},
                        {30, 32, 34, 36},
                        {50, 52, 54, 56},
                        {70, 72, 74, 76}
                },
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        1,
                        MotionVector.zero(),
                        MotionVector.zero()
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 4, 4, 0, 1, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertFalse(planes.hasChroma());
        assertPlaneEquals(planes.lumaPlane(), new int[][]{
                {5, 7, 9, 11},
                {25, 27, 29, 31},
                {45, 47, 49, 51},
                {65, 67, 69, 71}
        });
    }

    /// Verifies that scaled compound prediction handles non-integral Q10 coordinates whose
    /// quantized horizontal and vertical filter phases are both zero.
    @Test
    void reconstructsScaledCompoundReferenceWithZeroQuantizedFilterPhases() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                rampSamples(65, 65, 20, 0, 0),
                null,
                null
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                rampSamples(65, 65, 60, 0, 0),
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        1,
                        MotionVector.zero(),
                        MotionVector.zero()
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 64, 64, 0, 1, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertFalse(planes.hasChroma());
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 0, 8, 8, 40);
    }

    /// Verifies that one monochrome compound-reference inter block applies the decoded wedge
    /// compound mask to two integer-aligned stored reference surfaces.
    @Test
    void reconstructsCompoundReferenceI400WedgeMaskBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] reference0 = rampSamples(8, 8, 10, 3, 17);
        int[][] reference1 = rampSamples(8, 8, 180, -2, -9);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 =
                createReferenceSurfaceSnapshot(Av1ChromaFormat.MONOCHROME, reference0, null, null);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 =
                createReferenceSurfaceSnapshot(Av1ChromaFormat.MONOCHROME, reference1, null, null);
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        1,
                        MotionVector.zero(),
                        MotionVector.zero(),
                        CompoundPredictionType.WEDGE,
                        false,
                        0
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 8, 8, 0, 1, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertPlaneEquals(planes.lumaPlane(), expectedCompoundWedgeBlend(reference0, reference1, size, false, 0));
    }

    /// Verifies that one monochrome compound-reference inter block derives and applies a segment
    /// compound mask from the difference between both stored reference predictors.
    @Test
    void reconstructsCompoundReferenceI400SegmentMaskBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] reference0 = rampSamples(8, 8, 20, 7, 11);
        int[][] reference1 = rampSamples(8, 8, 220, -5, -13);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 =
                createReferenceSurfaceSnapshot(Av1ChromaFormat.MONOCHROME, reference0, null, null);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 =
                createReferenceSurfaceSnapshot(Av1ChromaFormat.MONOCHROME, reference1, null, null);
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        1,
                        MotionVector.zero(),
                        MotionVector.zero(),
                        CompoundPredictionType.SEGMENT,
                        true,
                        -1
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 8, 8, 0, 1, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertPlaneEquals(planes.lumaPlane(), expectedCompoundSegmentBlend(reference0, reference1, true, 8));
    }

    /// Verifies that `YUV444` chroma segment-compound prediction reuses the luma-derived mask for
    /// both mask signs instead of deriving independent masks from the chroma predictors.
    @Test
    void reconstructsCompoundReferenceI444SegmentMaskBlockFromLumaMask() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] luma0 = rampSamples(8, 8, 18, 3, 2);
        int[][] luma1 = rampSamples(8, 8, 220, -2, -1);
        int[][] chromaU0 = rampSamples(8, 8, 40, 1, 1);
        int[][] chromaU1 = rampSamples(8, 8, 100, -1, 0);
        int[][] chromaV0 = rampSamples(8, 8, 180, -2, -1);
        int[][] chromaV1 = rampSamples(8, 8, 30, 2, 1);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV444,
                luma0,
                chromaU0,
                chromaV0
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV444,
                luma1,
                chromaU1,
                chromaV1
        );

        for (boolean maskSign : new boolean[]{false, true}) {
            TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                    createCompoundReferenceInterBlockHeader(
                            position,
                            size,
                            true,
                            0,
                            1,
                            MotionVector.zero(),
                            MotionVector.zero(),
                            CompoundPredictionType.SEGMENT,
                            maskSign,
                            -1
                    ),
                    createTransformLayout(position, size, Av1ChromaFormat.YUV444),
                    createResidualLayout(position, size, true)
            );

            DecodedSurface planes = new FrameReconstructor().reconstruct(
                    createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV444, 8, 8, 0, 1, leaf),
                    createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
            );

            assertPlaneEquals(
                    planes.lumaPlane(),
                    expectedCompoundSegmentBlend(luma0, luma1, maskSign, 8)
            );
            assertPlaneEquals(
                    requirePlane(planes.chromaUPlane()),
                    expectedCompoundSegmentBlend(chromaU0, chromaU1, luma0, luma1, maskSign, 8)
            );
            assertPlaneEquals(
                    requirePlane(planes.chromaVPlane()),
                    expectedCompoundSegmentBlend(chromaV0, chromaV1, luma0, luma1, maskSign, 8)
            );
        }
    }

    /// Verifies that one monochrome single-reference inter block bilinearly samples one
    /// fractional luma footprint from one stored reference surface when the frame filter is
    /// `BILINEAR`.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithBilinearSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {0, 1, 2, 3, 4, 5, 6, 7},
                        {10, 11, 12, 13, 14, 15, 16, 17},
                        {20, 21, 22, 23, 24, 25, 26, 27},
                        {30, 31, 32, 33, 34, 35, 36, 37},
                        {40, 41, 42, 43, 44, 45, 46, 47},
                        {50, 51, 52, 53, 54, 55, 56, 57},
                        {60, 61, 62, 63, 64, 65, 66, 67},
                        {70, 71, 72, 73, 74, 75, 76, 77}
                },
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, new MotionVector(4, 12)),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertFalse(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                new int[][]{
                        {7, 8, 9, 10},
                        {17, 18, 19, 20},
                        {27, 28, 29, 30},
                        {37, 38, 39, 40}
                }
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
    }

    /// Verifies that one `YUV420` single-reference inter block bilinearly samples both luma and
    /// chroma footprints from one stored reference surface when the frame filter is `BILINEAR`.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithBilinearSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {0, 1, 2, 3, 4, 5, 6, 7},
                        {10, 11, 12, 13, 14, 15, 16, 17},
                        {20, 21, 22, 23, 24, 25, 26, 27},
                        {30, 31, 32, 33, 34, 35, 36, 37},
                        {40, 41, 42, 43, 44, 45, 46, 47},
                        {50, 51, 52, 53, 54, 55, 56, 57},
                        {60, 61, 62, 63, 64, 65, 66, 67},
                        {70, 71, 72, 73, 74, 75, 76, 77}
                },
                new int[][]{
                        {100, 101, 102, 103},
                        {110, 111, 112, 113},
                        {120, 121, 122, 123},
                        {130, 131, 132, 133}
                },
                new int[][]{
                        {150, 151, 152, 153},
                        {160, 161, 162, 163},
                        {170, 171, 172, 173},
                        {180, 181, 182, 183}
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, new MotionVector(4, 12)),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertTrue(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                new int[][]{
                        {7, 8, 9, 10},
                        {17, 18, 19, 20},
                        {27, 28, 29, 30},
                        {37, 38, 39, 40}
                }
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                0,
                0,
                new int[][]{
                        {103, 104},
                        {113, 114}
                }
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                0,
                0,
                new int[][]{
                        {153, 154},
                        {163, 164}
                }
        );
        assertPlaneBlockFilled(requirePlane(planes.chromaUPlane()), 2, 0, 2, 4, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaUPlane()), 0, 2, 4, 2, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaVPlane()), 2, 0, 2, 4, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaVPlane()), 0, 2, 4, 2, 0);
    }

    /// Verifies that one monochrome OBMC block blends its top edge against an already decoded
    /// above inter neighbor.
    @Test
    void reconstructsI400ObmcBlockFromAboveNeighbor() {
        BlockSize size = BlockSize.SIZE_16X16;
        int[][] lumaSamples = rampSamples(16, 32, 0, 1, 5);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode aboveLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        new BlockPosition(0, 0),
                        size,
                        false,
                        0,
                        new MotionVector(-32, 0)
                ),
                createTransformLayout(new BlockPosition(0, 0), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode obmcLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        new BlockPosition(0, 4),
                        size,
                        false,
                        0,
                        MotionVector.zero(),
                        MotionMode.OBMC
                ),
                createTransformLayout(new BlockPosition(0, 4), size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(new BlockPosition(0, 4), size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 16, 32, 0, aboveLeaf, obmcLeaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                16,
                expectedObmcAboveBlend(
                        cropSamples(lumaSamples, 0, 16, 16, 16),
                        cropSamples(lumaSamples, 0, 12, 16, 8),
                        8
                )
        );
    }

    /// Verifies that a clipped chroma OBMC region selects its interpolation filter from the
    /// complete above-neighbor predictor width rather than the narrower written footprint.
    @Test
    void reconstructsClippedI420ObmcRegionWithCompleteNeighborFilterWidth() {
        int frameWidth = 24;
        int frameHeight = 48;
        BlockPosition abovePosition = new BlockPosition(4, 0);
        BlockPosition currentPosition = new BlockPosition(4, 4);
        BlockSize aboveSize = BlockSize.SIZE_16X16;
        BlockSize currentSize = BlockSize.SIZE_16X32;
        MotionVector aboveMotionVector = new MotionVector(0, 8);
        int[][] lumaSamples = rampSamples(frameWidth, frameHeight, 0, 1, 3);
        int[][] chromaUSamples = new int[frameHeight >> 1][frameWidth >> 1];
        for (int y = 0; y < chromaUSamples.length; y++) {
            for (int x = 0; x < chromaUSamples[y].length; x++) {
                chromaUSamples[y][x] = (17 * x * x + 29 * y + 11 * x * y + 71 * (x % 3)) & 0xFF;
            }
        }
        int[][] chromaVSamples = rampSamples(frameWidth >> 1, frameHeight >> 1, 8, 2, 5);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples,
                chromaUSamples,
                chromaVSamples
        );
        TilePartitionTreeReader.LeafNode aboveLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        abovePosition,
                        aboveSize,
                        true,
                        0,
                        aboveMotionVector
                ),
                createTransformLayout(abovePosition, aboveSize, Av1ChromaFormat.YUV420, 8, 16),
                createResidualLayout(abovePosition, aboveSize, true)
        );
        TilePartitionTreeReader.LeafNode obmcLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        currentPosition,
                        currentSize,
                        true,
                        0,
                        MotionVector.zero(),
                        MotionMode.OBMC
                ),
                createTransformLayout(currentPosition, currentSize, Av1ChromaFormat.YUV420, 8, 32),
                createResidualLayout(currentPosition, currentSize, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        frameWidth,
                        frameHeight,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP,
                        aboveLeaf,
                        obmcLeaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        int chromaX = currentPosition.x4() << 1;
        int chromaY = currentPosition.y4() << 1;
        int visibleWidth = 4;
        int overlap = 8;
        int[][] currentPredictor = cropSamples(chromaUSamples, chromaX, chromaY, visibleWidth, 16);
        int[][] neighborPredictor = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                visibleWidth,
                overlap,
                chromaX * 16 + aboveMotionVector.columnEighthPel(),
                chromaY * 16 + aboveMotionVector.rowEighthPel(),
                16,
                16,
                8,
                overlap,
                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
        );
        int[][] incorrectlyClippedPredictor = InterPredictionOracle.sampleReferencePlaneBlock(
                requirePlane(referenceSurfaceSnapshot.decodedPlanes().chromaUPlane()),
                visibleWidth,
                overlap,
                chromaX * 16 + aboveMotionVector.columnEighthPel(),
                chromaY * 16 + aboveMotionVector.rowEighthPel(),
                16,
                16,
                visibleWidth,
                overlap,
                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
        );

        assertFalse(Arrays.deepEquals(neighborPredictor, incorrectlyClippedPredictor));
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                chromaX,
                chromaY,
                expectedObmcAboveBlend(currentPredictor, neighborPredictor, overlap)
        );
    }

    /// Verifies that one chroma-bearing OBMC block blends luma and chroma left edges against an
    /// already decoded left inter neighbor.
    @Test
    void reconstructsI420ObmcBlockFromLeftNeighbor() {
        BlockSize size = BlockSize.SIZE_16X16;
        int[][] lumaSamples = rampSamples(32, 16, 0, 1, 5);
        int[][] chromaUSamples = rampSamples(16, 8, 40, 3, 7);
        int[][] chromaVSamples = rampSamples(16, 8, 200, -2, -5);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples,
                chromaUSamples,
                chromaVSamples
        );
        TilePartitionTreeReader.LeafNode leftLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        new BlockPosition(0, 0),
                        size,
                        true,
                        0,
                        new MotionVector(0, -32)
                ),
                createTransformLayout(new BlockPosition(0, 0), size, Av1ChromaFormat.YUV420),
                createResidualLayout(new BlockPosition(0, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode obmcLeaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        new BlockPosition(4, 0),
                        size,
                        true,
                        0,
                        MotionVector.zero(),
                        MotionMode.OBMC
                ),
                createTransformLayout(new BlockPosition(4, 0), size, Av1ChromaFormat.YUV420),
                createResidualLayout(new BlockPosition(4, 0), size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.YUV420, 32, 16, 0, leftLeaf, obmcLeaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneBlockEquals(
                planes.lumaPlane(),
                16,
                0,
                expectedObmcLeftBlend(
                        cropSamples(lumaSamples, 16, 0, 16, 16),
                        cropSamples(lumaSamples, 12, 0, 8, 16),
                        8
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                8,
                0,
                expectedObmcLeftBlend(
                        cropSamples(chromaUSamples, 8, 0, 8, 8),
                        cropSamples(chromaUSamples, 6, 0, 4, 8),
                        4
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                8,
                0,
                expectedObmcLeftBlend(
                        cropSamples(chromaVSamples, 8, 0, 8, 8),
                        cropSamples(chromaVSamples, 6, 0, 4, 8),
                        4
                )
        );
    }

    /// Verifies that one monochrome local-warped block estimates an affine predictor from causal
    /// same-reference motion samples.
    @Test
    void reconstructsLocalWarpedI400InterBlockFromCausalMotionSamples() {
        BlockSize size = BlockSize.SIZE_8X8;
        BlockPosition abovePosition = new BlockPosition(2, 0);
        BlockPosition leftPosition = new BlockPosition(0, 2);
        BlockPosition currentPosition = new BlockPosition(2, 2);
        int[][] lumaSamples = rampSamples(16, 16, 3, 5, 8);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TileBlockHeaderReader.BlockHeader aboveHeader = createSingleReferenceInterBlockHeader(
                abovePosition,
                size,
                false,
                0,
                new MotionVector(0, 8)
        );
        TileBlockHeaderReader.BlockHeader leftHeader = createSingleReferenceInterBlockHeader(
                leftPosition,
                size,
                false,
                0,
                new MotionVector(8, 0)
        );
        TileBlockHeaderReader.BlockHeader currentHeader = createSingleReferenceInterBlockHeader(
                currentPosition,
                size,
                false,
                0,
                MotionVector.zero(),
                MotionMode.LOCAL_WARPED
        );
        TilePartitionTreeReader.LeafNode aboveLeaf = new TilePartitionTreeReader.LeafNode(
                aboveHeader,
                createTransformLayout(abovePosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(abovePosition, size, true)
        );
        TilePartitionTreeReader.LeafNode leftLeaf = new TilePartitionTreeReader.LeafNode(
                leftHeader,
                createTransformLayout(leftPosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(leftPosition, size, true)
        );
        TilePartitionTreeReader.LeafNode currentLeaf = new TilePartitionTreeReader.LeafNode(
                currentHeader,
                createTransformLayout(currentPosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(currentPosition, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        16,
                        16,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        aboveLeaf,
                        leftLeaf,
                        currentLeaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertFalse(planes.hasChroma());
        WarpedMotion.Model reportedModel = WarpedMotion.derive(
                new WarpedMotion.Sample[]{
                        new WarpedMotion.Sample(24, -40, 32, -40),
                        new WarpedMotion.Sample(-40, 24, -40, 32)
                },
                2,
                2,
                2,
                MotionVector.zero(),
                2,
                2
        );
        assertTrue(reportedModel.affine());
        assertEquals(96206, reportedModel.matrix(0));
        assertEquals(96206, reportedModel.matrix(1));
        assertEquals(64981, reportedModel.matrix(2));
        assertEquals(-8191, reportedModel.matrix(3));
        assertEquals(-8191, reportedModel.matrix(4));
        assertEquals(64981, reportedModel.matrix(5));
        assertEquals(-576, reportedModel.alpha());
        assertEquals(-8192, reportedModel.beta());
        assertEquals(-8256, reportedModel.gamma());
        assertEquals(-1600, reportedModel.delta());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                8,
                8,
                new int[][]{
                        {112, 116, 120, 124, 128, 132, 136, 139},
                        {119, 123, 127, 131, 135, 139, 143, 147},
                        {126, 130, 134, 138, 142, 146, 150, 154},
                        {133, 137, 141, 146, 149, 153, 158, 162},
                        {140, 145, 149, 153, 157, 161, 165, 169},
                        {147, 152, 156, 160, 164, 168, 172, 177},
                        {155, 159, 163, 167, 171, 175, 179, 183},
                        {160, 165, 170, 175, 179, 183, 187, 192}
                }
        );
    }

    /// Verifies that an inter-intra neighbor is not used as a local-warp projection sample and
    /// that a model with only one remaining sample falls back to translational prediction.
    @Test
    void excludesInterIntraNeighborsFromLocalWarpSamples() {
        BlockSize size = BlockSize.SIZE_8X8;
        BlockPosition abovePosition = new BlockPosition(2, 0);
        BlockPosition leftPosition = new BlockPosition(0, 2);
        BlockPosition currentPosition = new BlockPosition(2, 2);
        int[][] lumaSamples = rampSamples(16, 16, 3, 5, 8);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TileBlockHeaderReader.BlockHeader aboveHeader = createSingleReferenceInterIntraBlockHeader(
                abovePosition,
                size,
                false,
                0,
                new MotionVector(0, 8),
                InterIntraPredictionMode.VERTICAL,
                false,
                -1
        );
        TileBlockHeaderReader.BlockHeader leftHeader = createSingleReferenceInterBlockHeader(
                leftPosition,
                size,
                false,
                0,
                new MotionVector(300, 300)
        );
        TileBlockHeaderReader.BlockHeader currentHeader = createSingleReferenceInterBlockHeader(
                currentPosition,
                size,
                false,
                0,
                MotionVector.zero(),
                MotionMode.LOCAL_WARPED
        );
        TilePartitionTreeReader.LeafNode aboveLeaf = new TilePartitionTreeReader.LeafNode(
                aboveHeader,
                createTransformLayout(abovePosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(abovePosition, size, true)
        );
        TilePartitionTreeReader.LeafNode leftLeaf = new TilePartitionTreeReader.LeafNode(
                leftHeader,
                createTransformLayout(leftPosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(leftPosition, size, true)
        );
        TilePartitionTreeReader.LeafNode currentLeaf = new TilePartitionTreeReader.LeafNode(
                currentHeader,
                createTransformLayout(currentPosition, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(currentPosition, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        16,
                        16,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        aboveLeaf,
                        leftLeaf,
                        currentLeaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneBlockEquals(
                planes.lumaPlane(),
                8,
                8,
                cropSamples(lumaSamples, 8, 8, 8, 8)
        );
    }

    /// Verifies that local warped prediction falls back to translation for a subsampled chroma
    /// plane whose block width or height is smaller than eight samples.
    @Test
    void reconstructsSmallI420ChromaBlockWithTranslationDuringLocalWarpedPrediction() {
        BlockSize size = BlockSize.SIZE_8X8;
        BlockPosition abovePosition = new BlockPosition(2, 0);
        BlockPosition leftPosition = new BlockPosition(0, 2);
        BlockPosition currentPosition = new BlockPosition(2, 2);
        int[][] lumaSamples = rampSamples(16, 16, 3, 5, 8);
        int[][] chromaUSamples = rampSamples(8, 8, 7, 11, 8);
        int[][] chromaVSamples = rampSamples(8, 8, 9, 13, 8);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples,
                chromaUSamples,
                chromaVSamples
        );
        TileBlockHeaderReader.BlockHeader aboveHeader = createSingleReferenceInterBlockHeader(
                abovePosition,
                size,
                true,
                0,
                new MotionVector(0, 8)
        );
        TileBlockHeaderReader.BlockHeader leftHeader = createSingleReferenceInterBlockHeader(
                leftPosition,
                size,
                true,
                0,
                new MotionVector(8, 0)
        );
        TileBlockHeaderReader.BlockHeader currentHeader = createSingleReferenceInterBlockHeader(
                currentPosition,
                size,
                true,
                0,
                MotionVector.zero(),
                MotionMode.LOCAL_WARPED
        );
        TilePartitionTreeReader.LeafNode aboveLeaf = new TilePartitionTreeReader.LeafNode(
                aboveHeader,
                createTransformLayout(abovePosition, size, Av1ChromaFormat.YUV420),
                createResidualLayout(abovePosition, size, true)
        );
        TilePartitionTreeReader.LeafNode leftLeaf = new TilePartitionTreeReader.LeafNode(
                leftHeader,
                createTransformLayout(leftPosition, size, Av1ChromaFormat.YUV420),
                createResidualLayout(leftPosition, size, true)
        );
        TilePartitionTreeReader.LeafNode currentLeaf = new TilePartitionTreeReader.LeafNode(
                currentHeader,
                createTransformLayout(currentPosition, size, Av1ChromaFormat.YUV420),
                createResidualLayout(currentPosition, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        16,
                        16,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        aboveLeaf,
                        leftLeaf,
                        currentLeaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertTrue(
                planes.lumaPlane().sample(8, 8) != lumaSamples[8][8],
                "The luma plane should retain local warped prediction"
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                4,
                4,
                cropSamples(chromaUSamples, 4, 4, 4, 4)
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                4,
                4,
                cropSamples(chromaVSamples, 4, 4, 4, 4)
        );
    }

    /// Verifies that a `GLOBALMV` block with non-translation frame motion uses affine warped
    /// prediction instead of the block-center translation vector.
    @Test
    void reconstructsGlobalAffineI400InterBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] lumaSamples = rampSamples(16, 16, 3, 5, 8);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        FrameHeader.GlobalMotionParams affineMotion = new FrameHeader.GlobalMotionParams(
                FrameHeader.GlobalMotionType.ROTATION_ZOOM,
                new int[]{0, 0, 1 << 16, 1 << 11, -(1 << 11), 1 << 16}
        );
        FrameHeader.GlobalMotionParams identityMotion = FrameHeader.GlobalMotionParams.identity();
        FrameHeader frameHeader = createInterFrameHeader(16, 16, 0).withGlobalMotionParameters(
                new FrameHeader.GlobalMotionParams[]{
                        affineMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        MotionVector.zero(),
                        SingleInterPredictionMode.GLOBALMV
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        createSequenceHeader(Av1ChromaFormat.MONOCHROME, 16, 16),
                        frameHeader,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );
        WarpedMotion.Model model = WarpedMotion.fromGlobalMotion(affineMotion);
        MutablePlaneBuffer expectedPlane = new MutablePlaneBuffer(16, 16, 8);
        WarpedMotion.predictPlane(
                expectedPlane,
                referenceSurfaceSnapshot.decodedPlanes().lumaPlane(),
                0,
                0,
                8,
                8,
                8,
                8,
                0,
                0,
                0,
                0,
                model
        );

        assertTrue(model.affine());
        assertPlanesEqual(expectedPlane.toDecodedPlane(), planes.lumaPlane());
        assertTrue(
                planes.lumaPlane().sample(7, 7) != lumaSamples[7][7],
                "Affine global prediction should differ from zero-vector translation"
        );
    }

    /// Verifies that each reference of a `GLOBALMV_GLOBALMV` block independently uses its affine
    /// global model before compound averaging.
    @Test
    void reconstructsCompoundGlobalAffineI400InterBlock() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[][] primarySamples = rampSamples(16, 16, 3, 3, 5);
        int[][] secondarySamples = rampSamples(16, 16, 20, 7, 4);
        ReferenceSurfaceSnapshot primaryReference = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                primarySamples,
                null,
                null
        );
        ReferenceSurfaceSnapshot secondaryReference = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                secondarySamples,
                null,
                null
        );
        FrameHeader.GlobalMotionParams identityMotion = FrameHeader.GlobalMotionParams.identity();
        FrameHeader.GlobalMotionParams affineMotion = new FrameHeader.GlobalMotionParams(
                FrameHeader.GlobalMotionType.ROTATION_ZOOM,
                new int[]{0, 0, 1 << 16, 1 << 11, -(1 << 11), 1 << 16}
        );
        FrameHeader frameHeader = createInterFrameHeader(
                16,
                16,
                0,
                1,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        ).withGlobalMotionParameters(
                new FrameHeader.GlobalMotionParams[]{
                        identityMotion,
                        affineMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion,
                        identityMotion
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        false,
                        0,
                        1,
                        MotionVector.zero(),
                        MotionVector.zero(),
                        CompoundInterPredictionMode.GLOBALMV_GLOBALMV
                ),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        createSequenceHeader(Av1ChromaFormat.MONOCHROME, 16, 16),
                        frameHeader,
                        leaf
                ),
                createReferenceSurfaceSlots(0, primaryReference, 1, secondaryReference)
        );
        WarpedMotion.Model model = WarpedMotion.fromGlobalMotion(affineMotion);
        int[] warpedSecondary = WarpedMotion.predictCompoundPlane(
                secondaryReference.decodedPlanes().lumaPlane(),
                8,
                8,
                8,
                8,
                0,
                0,
                0,
                0,
                8,
                model
        );
        int[][] expected = new int[8][8];
        int[][] translationOnly = new int[8][8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                expected[y][x] = ((primarySamples[y][x] << 4) + warpedSecondary[y * 8 + x] + 16) >> 5;
                translationOnly[y][x] = (primarySamples[y][x] + secondarySamples[y][x] + 1) / 2;
            }
        }

        assertTrue(model.affine());
        assertPlaneBlockEquals(planes.lumaPlane(), 0, 0, expected);
        assertFalse(
                Arrays.deepEquals(expected, translationOnly),
                "Compound affine prediction should differ from two zero-vector translations"
        );
    }

    /// Verifies regular eight-tap sampling for one monochrome single-reference inter block.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithRegularEightTapSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(6, 2);
        int[][] lumaSamples = {
                {0, 1, 2, 3, 4, 5, 6, 7},
                {10, 11, 12, 13, 14, 15, 16, 17},
                {20, 21, 22, 23, 24, 25, 26, 27},
                {30, 31, 32, 33, 34, 35, 36, 37},
                {40, 41, 42, 43, 44, 45, 46, 47},
                {50, 51, 52, 53, 54, 55, 56, 57},
                {60, 61, 62, 63, 64, 65, 66, 67},
                {70, 71, 72, 73, 74, 75, 76, 77}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertFalse(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(lumaSamples),
                        4,
                        4,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        8,
                        8,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
                )
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
    }

    /// Verifies that two-axis fixed-filter prediction rounds after each normative filtering stage.
    @Test
    void reconstructsFixedFilterInterPredictionWithNormativeTwoStageRounding() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        MotionVector motionVector = new MotionVector(29, 26);
        int[][] lumaSamples = {
                {60, 117, 48, 189, 183, 30, 52, 120},
                {134, 178, 180, 27, 145, 201, 118, 114},
                {3, 87, 251, 107, 232, 170, 189, 99},
                {63, 38, 46, 37, 18, 94, 132, 85},
                {76, 150, 201, 120, 82, 208, 198, 219},
                {176, 221, 182, 237, 74, 41, 227, 234},
                {227, 57, 133, 225, 0, 3, 159, 111},
                {89, 156, 166, 198, 116, 247, 37, 196}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(48, planes.lumaPlane().sample(0, 0));
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(lumaSamples),
                        8,
                        8,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        8,
                        8,
                        8,
                        8,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
                )
        );
    }

    /// Verifies that sharp inter filtering uses the normative reduced-width regular kernel when the
    /// sampled axis is no wider than four pixels.
    @Test
    void reconstructsNarrowSharpInterPredictionWithReducedWidthRegularFilter() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(0, 25);
        int[][] lumaSamples = {
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128},
                {128, 255, 128, 128, 128, 128, 128, 128}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(128, planes.lumaPlane().sample(0, 0));
    }

    /// Verifies that fixed-filter inter prediction preserves `10-bit` samples instead of clipping
    /// the subpel result to the `8-bit` range.
    @Test
    void reconstructsTenBitSingleReferenceI400InterBlockWithRegularEightTapSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(6, 2);
        int[][] lumaSamples = {
                {320, 344, 368, 392, 416, 440, 464, 488},
                {376, 400, 424, 448, 472, 496, 520, 544},
                {432, 456, 480, 504, 528, 552, 576, 600},
                {488, 512, 536, 560, 584, 608, 632, 656},
                {544, 568, 592, 616, 640, 664, 688, 712},
                {600, 624, 648, 672, 696, 720, 744, 768},
                {656, 680, 704, 728, 752, 776, 800, 824},
                {712, 736, 760, 784, 808, 832, 856, 880}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                10,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createHighBitInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        10,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(10, planes.bitDepth());
        assertFalse(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(lumaSamples),
                        4,
                        4,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        8,
                        8,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                        1023
                )
        );
        assertTrue(planes.lumaPlane().sample(0, 0) > 255);
    }

    /// Verifies that 12-bit bilinear prediction preserves the normative horizontal intermediate
    /// precision before vertical filtering.
    @Test
    void reconstructsTwelveBitBilinearInterPredictionWithNormativeTwoStageRounding() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(6, 5);
        int[][] lumaSamples = {
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 1, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                12,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createHighBitInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.MONOCHROME,
                        12,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(1, planes.lumaPlane().sample(0, 0));
    }

    /// Verifies smooth eight-tap luma and chroma sampling for one `YUV420` inter block.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithSmoothEightTapSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(4, 12);
        int[][] lumaSamples = {
                {0, 1, 2, 3, 4, 5, 6, 7},
                {10, 11, 12, 13, 14, 15, 16, 17},
                {20, 21, 22, 23, 24, 25, 26, 27},
                {30, 31, 32, 33, 34, 35, 36, 37},
                {40, 41, 42, 43, 44, 45, 46, 47},
                {50, 51, 52, 53, 54, 55, 56, 57},
                {60, 61, 62, 63, 64, 65, 66, 67},
                {70, 71, 72, 73, 74, 75, 76, 77}
        };
        int[][] chromaUSamples = {
                {100, 101, 102, 103},
                {110, 111, 112, 113},
                {120, 121, 122, 123},
                {130, 131, 132, 133}
        };
        int[][] chromaVSamples = {
                {150, 151, 152, 153},
                {160, 161, 162, 163},
                {170, 171, 172, 173},
                {180, 181, 182, 183}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples,
                chromaUSamples,
                chromaVSamples
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, motionVector),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        8,
                        8,
                        0,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertTrue(planes.hasChroma());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(lumaSamples),
                        4,
                        4,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        8,
                        8,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(chromaUSamples),
                        2,
                        2,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        16,
                        16,
                        2,
                        2,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        createDecodedPlane(chromaVSamples),
                        2,
                        2,
                        motionVector.columnEighthPel(),
                        motionVector.rowEighthPel(),
                        16,
                        16,
                        2,
                        2,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
                )
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaUPlane()), 2, 0, 2, 4, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaUPlane()), 0, 2, 4, 2, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaVPlane()), 2, 0, 2, 4, 0);
        assertPlaneBlockFilled(requirePlane(planes.chromaVPlane()), 0, 2, 4, 2, 0);
    }

    /// Verifies that one `YUV420` compound-reference inter block averages two bilinearly sampled
    /// reference surfaces on both luma and chroma planes.
    @Test
    void reconstructsCompoundReferenceI420InterBlockFromStoredSurfacesWithBilinearSubpelMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(4, 4);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {0, 8, 16, 24, 32, 40, 48, 56},
                        {16, 24, 32, 40, 48, 56, 64, 72},
                        {32, 40, 48, 56, 64, 72, 80, 88},
                        {48, 56, 64, 72, 80, 88, 96, 104},
                        {64, 72, 80, 88, 96, 104, 112, 120},
                        {80, 88, 96, 104, 112, 120, 128, 136},
                        {96, 104, 112, 120, 128, 136, 144, 152},
                        {112, 120, 128, 136, 144, 152, 160, 168}
                },
                new int[][]{
                        {100, 132, 164, 196},
                        {116, 148, 180, 212},
                        {132, 164, 196, 228},
                        {148, 180, 212, 244}
                },
                new int[][]{
                        {80, 112, 144, 176},
                        {96, 128, 160, 192},
                        {112, 144, 176, 208},
                        {128, 160, 192, 224}
                }
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {200, 192, 184, 176, 168, 160, 152, 144},
                        {184, 176, 168, 160, 152, 144, 136, 128},
                        {168, 160, 152, 144, 136, 128, 120, 112},
                        {152, 144, 136, 128, 120, 112, 104, 96},
                        {136, 128, 120, 112, 104, 96, 88, 80},
                        {120, 112, 104, 96, 88, 80, 72, 64},
                        {104, 96, 88, 80, 72, 64, 56, 48},
                        {88, 80, 72, 64, 56, 48, 40, 32}
                },
                new int[][]{
                        {200, 168, 136, 104},
                        {184, 152, 120, 88},
                        {168, 136, 104, 72},
                        {152, 120, 88, 56}
                },
                new int[][]{
                        {220, 188, 156, 124},
                        {204, 172, 140, 108},
                        {188, 156, 124, 92},
                        {172, 140, 108, 76}
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        true,
                        0,
                        1,
                        motionVector,
                        motionVector
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        8,
                        8,
                        0,
                        1,
                        FrameHeader.InterpolationFilter.BILINEAR,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertPlaneBlockEquals(planes.lumaPlane(), 0, 0, new int[][]{
                {100, 100, 100, 100},
                {100, 100, 100, 100},
                {100, 100, 100, 100},
                {100, 100, 100, 100}
        });
        assertPlaneBlockEquals(requirePlane(planes.chromaUPlane()), 0, 0, new int[][]{
                {150, 150},
                {150, 150}
        });
        assertPlaneBlockEquals(requirePlane(planes.chromaVPlane()), 0, 0, new int[][]{
                {150, 150},
                {150, 150}
        });
    }

    /// Verifies that one `YUV420` compound-reference inter block averages two fixed
    /// `EIGHT_TAP_SHARP` subpel predictions on both luma and chroma planes.
    @Test
    void reconstructsCompoundReferenceI420InterBlockFromStoredSurfacesWithSharpEightTapSubpelMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector0 = new MotionVector(6, 2);
        MotionVector motionVector1 = new MotionVector(2, 6);
        int[][] lumaSamples0 = {
                {0, 8, 16, 24, 32, 40, 48, 56},
                {16, 24, 32, 40, 48, 56, 64, 72},
                {32, 40, 48, 56, 64, 72, 80, 88},
                {48, 56, 64, 72, 80, 88, 96, 104},
                {64, 72, 80, 88, 96, 104, 112, 120},
                {80, 88, 96, 104, 112, 120, 128, 136},
                {96, 104, 112, 120, 128, 136, 144, 152},
                {112, 120, 128, 136, 144, 152, 160, 168}
        };
        int[][] chromaUSamples0 = {
                {100, 132, 164, 196},
                {116, 148, 180, 212},
                {132, 164, 196, 228},
                {148, 180, 212, 244}
        };
        int[][] chromaVSamples0 = {
                {80, 112, 144, 176},
                {96, 128, 160, 192},
                {112, 144, 176, 208},
                {128, 160, 192, 224}
        };
        int[][] lumaSamples1 = {
                {200, 192, 184, 176, 168, 160, 152, 144},
                {184, 176, 168, 160, 152, 144, 136, 128},
                {168, 160, 152, 144, 136, 128, 120, 112},
                {152, 144, 136, 128, 120, 112, 104, 96},
                {136, 128, 120, 112, 104, 96, 88, 80},
                {120, 112, 104, 96, 88, 80, 72, 64},
                {104, 96, 88, 80, 72, 64, 56, 48},
                {88, 80, 72, 64, 56, 48, 40, 32}
        };
        int[][] chromaUSamples1 = {
                {200, 168, 136, 104},
                {184, 152, 120, 88},
                {168, 136, 104, 72},
                {152, 120, 88, 56}
        };
        int[][] chromaVSamples1 = {
                {220, 188, 156, 124},
                {204, 172, 140, 108},
                {188, 156, 124, 92},
                {172, 140, 108, 76}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples0,
                chromaUSamples0,
                chromaVSamples0
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                lumaSamples1,
                chromaUSamples1,
                chromaVSamples1
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        true,
                        0,
                        1,
                        motionVector0,
                        motionVector1
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        8,
                        8,
                        0,
                        1,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.averageCompoundBlocks(
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(lumaSamples0),
                                4,
                                4,
                                motionVector0.columnEighthPel(),
                                motionVector0.rowEighthPel(),
                                8,
                                8,
                                4,
                                4,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(lumaSamples1),
                                4,
                                4,
                                motionVector1.columnEighthPel(),
                                motionVector1.rowEighthPel(),
                                8,
                                8,
                                4,
                                4,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        )
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                0,
                0,
                InterPredictionOracle.averageCompoundBlocks(
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(chromaUSamples0),
                                2,
                                2,
                                motionVector0.columnEighthPel(),
                                motionVector0.rowEighthPel(),
                                16,
                                16,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(chromaUSamples1),
                                2,
                                2,
                                motionVector1.columnEighthPel(),
                                motionVector1.rowEighthPel(),
                                16,
                                16,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        )
                )
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                0,
                0,
                InterPredictionOracle.averageCompoundBlocks(
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(chromaVSamples0),
                                2,
                                2,
                                motionVector0.columnEighthPel(),
                                motionVector0.rowEighthPel(),
                                16,
                                16,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleCompoundReferencePlaneBlock(
                                createDecodedPlane(chromaVSamples1),
                                2,
                                2,
                                motionVector1.columnEighthPel(),
                                motionVector1.rowEighthPel(),
                                16,
                                16,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        )
                )
        );
    }

    /// Verifies that fixed-filter compound inter prediction retains the `12-bit` post-filter
    /// precision until after the two reference predictors are averaged.
    @Test
    void reconstructsTwelveBitCompoundReferenceI420InterBlockWithSharpEightTapSubpelMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector0 = new MotionVector(6, 2);
        MotionVector motionVector1 = new MotionVector(2, 6);
        int[][] lumaSamples0 = {
                {1024, 1056, 1088, 1120, 1152, 1184, 1216, 1248},
                {1088, 1120, 1152, 1184, 1216, 1248, 1280, 1312},
                {1152, 1184, 1216, 1248, 1280, 1312, 1344, 1376},
                {1216, 1248, 1280, 1312, 1344, 1376, 1408, 1440},
                {1280, 1312, 1344, 1376, 1408, 1440, 1472, 1504},
                {1344, 1376, 1408, 1440, 1472, 1504, 1536, 1568},
                {1408, 1440, 1472, 1504, 1536, 1568, 1600, 1632},
                {1472, 1504, 1536, 1568, 1600, 1632, 1664, 1696}
        };
        int[][] chromaUSamples0 = {
                {1200, 1232, 1264, 1296},
                {1248, 1280, 1312, 1344},
                {1296, 1328, 1360, 1392},
                {1344, 1376, 1408, 1440}
        };
        int[][] chromaVSamples0 = {
                {1500, 1532, 1564, 1596},
                {1548, 1580, 1612, 1644},
                {1596, 1628, 1660, 1692},
                {1644, 1676, 1708, 1740}
        };
        int[][] lumaSamples1 = {
                {2500, 2468, 2436, 2404, 2372, 2340, 2308, 2276},
                {2436, 2404, 2372, 2340, 2308, 2276, 2244, 2212},
                {2372, 2340, 2308, 2276, 2244, 2212, 2180, 2148},
                {2308, 2276, 2244, 2212, 2180, 2148, 2116, 2084},
                {2244, 2212, 2180, 2148, 2116, 2084, 2052, 2020},
                {2180, 2148, 2116, 2084, 2052, 2020, 1988, 1956},
                {2116, 2084, 2052, 2020, 1988, 1956, 1924, 1892},
                {2052, 2020, 1988, 1956, 1924, 1892, 1860, 1828}
        };
        int[][] chromaUSamples1 = {
                {2200, 2168, 2136, 2104},
                {2152, 2120, 2088, 2056},
                {2104, 2072, 2040, 2008},
                {2056, 2024, 1992, 1960}
        };
        int[][] chromaVSamples1 = {
                {2600, 2568, 2536, 2504},
                {2552, 2520, 2488, 2456},
                {2504, 2472, 2440, 2408},
                {2456, 2424, 2392, 2360}
        };
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                12,
                lumaSamples0,
                chromaUSamples0,
                chromaVSamples0
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                12,
                lumaSamples1,
                chromaUSamples1,
                chromaVSamples1
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createCompoundReferenceInterBlockHeader(
                        position,
                        size,
                        true,
                        0,
                        1,
                        motionVector0,
                        motionVector1
                ),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        Av1ChromaFormat.YUV420,
                        12,
                        8,
                        8,
                        0,
                        1,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP,
                        leaf
                ),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertEquals(12, planes.bitDepth());
        assertPlaneBlockEquals(
                planes.lumaPlane(),
                0,
                0,
                new int[][]{
                        {1770, 1771, 1771, 1771},
                        {1769, 1770, 1770, 1770},
                        {1769, 1770, 1770, 1770},
                        {1769, 1770, 1770, 1770}
                }
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaUPlane()),
                0,
                0,
                new int[][]{
                        {1702, 1701},
                        {1703, 1702}
                }
        );
        assertPlaneBlockEquals(
                requirePlane(planes.chromaVPlane()),
                0,
                0,
                new int[][]{
                        {2052, 2051},
                        {2053, 2052}
                }
        );
        assertTrue(planes.lumaPlane().sample(0, 0) > 255);
        assertTrue(requirePlane(planes.chromaUPlane()).sample(0, 0) > 255);
        assertTrue(requirePlane(planes.chromaVPlane()).sample(0, 0) > 255);
    }

    /// Verifies that one monochrome inter block still applies supported residuals on top of the
    /// copied reference prediction.
    @Test
    void reconstructsSingleReferenceI400InterBlockWithResidualOverlay() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.MONOCHROME,
                new int[][]{
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10}
                },
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, 64)
        );

        DecodedSurface planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 4, 4, 0, leaf),
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                createDecodedPlane(new int[][]{
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10},
                        {10, 10, 10, 10}
                }),
                planes.lumaPlane(),
                1
        );
    }

    /// Verifies that an inter block still fails fast when no stored reference surface is available.
    @Test
    void rejectsInterBlocksWithoutStoredReferenceSurface() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, MotionVector.zero()),
                createTransformLayout(position, size, Av1ChromaFormat.MONOCHROME),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createInterFrameSyntaxDecodeResult(Av1ChromaFormat.MONOCHROME, 4, 4, 0, leaf)
                )
        );

        assertEquals("Inter reconstruction requires one populated stored reference surface", exception.getMessage());
    }

    /// Verifies that one switchable inter block rejects a missing horizontal block filter.
    @Test
    void rejectsI420InterBlocksWithSwitchableFrameFilterWithoutDecodedBlockFilters() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                Av1ChromaFormat.YUV420,
                new int[][]{
                        {0, 1, 2, 3, 4, 5, 6, 7},
                        {10, 11, 12, 13, 14, 15, 16, 17},
                        {20, 21, 22, 23, 24, 25, 26, 27},
                        {30, 31, 32, 33, 34, 35, 36, 37},
                        {40, 41, 42, 43, 44, 45, 46, 47},
                        {50, 51, 52, 53, 54, 55, 56, 57},
                        {60, 61, 62, 63, 64, 65, 66, 67},
                        {70, 71, 72, 73, 74, 75, 76, 77}
                },
                new int[][]{
                        {100, 101, 102, 103},
                        {110, 111, 112, 113},
                        {120, 121, 122, 123},
                        {130, 131, 132, 133}
                },
                new int[][]{
                        {150, 151, 152, 153},
                        {160, 161, 162, 163},
                        {170, 171, 172, 173},
                        {180, 181, 182, 183}
                }
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, new MotionVector(4, 12)),
                createTransformLayout(position, size, Av1ChromaFormat.YUV420),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createInterFrameSyntaxDecodeResult(
                                Av1ChromaFormat.YUV420,
                                8,
                                8,
                                0,
                                FrameHeader.InterpolationFilter.SWITCHABLE,
                                leaf
                        ),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                )
        );

        assertEquals("Inter reconstruction requires a resolved horizontal interpolation filter", exception.getMessage());
    }

    /// Creates one synthetic structural frame-decode result for reconstruction tests.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param frameType the frame type to expose through the frame header
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural frame-decode result
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            Av1FrameType frameType,
            int width,
            int height,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, width, height);
        FrameHeader frameHeader = createFrameHeader(frameType, width, height);
        return createFrameSyntaxDecodeResult(sequenceHeader, frameHeader, roots);
    }

    /// Creates one synthetic structural frame-decode result for reconstruction tests with explicit
    /// sequence and frame headers.
    ///
    /// @param sequenceHeader the sequence header exposed through the frame assembly
    /// @param frameHeader the frame header exposed through the frame assembly
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural frame-decode result
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            TilePartitionTreeReader.Node... roots
    ) {
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic structural inter frame-decode result for reconstruction tests.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int width,
            int height,
            int referenceSlot,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(
                width,
                height,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic structural compound-inter frame-decode result for reconstruction
    /// tests with two stored reference slots.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot exposed as `LAST2_FRAME`
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural compound-inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            TilePartitionTreeReader.Node... roots
    ) {
        return createInterFrameSyntaxDecodeResult(
                chromaFormat,
                width,
                height,
                referenceSlot0,
                referenceSlot1,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                roots
        );
    }

    /// Creates one synthetic structural compound-inter frame-decode result for reconstruction
    /// tests with two stored reference slots and one caller-supplied interpolation filter.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot exposed as `LAST2_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural compound-inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot0, referenceSlot1, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic structural inter frame-decode result for reconstruction tests with one
    /// caller-supplied frame-level interpolation filter.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter used for inter prediction
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int width,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic structural inter frame-decode result with an explicit decoded bit
    /// depth and caller-supplied frame-level interpolation filter.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param bitDepth the decoded sample bit depth
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter used for inter prediction
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural inter frame-decode result
    private static FrameSyntaxDecodeResult createHighBitInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int bitDepth,
            int width,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, bitDepth, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic structural compound-inter frame-decode result with an explicit
    /// decoded bit depth and two stored reference slots.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param bitDepth the decoded sample bit depth
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot exposed as `LAST2_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural compound-inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            Av1ChromaFormat chromaFormat,
            int bitDepth,
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, bitDepth, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot0, referenceSlot1, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new TileGroupHeader(false, 0, 0, 1),
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{roots},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one minimal reduced-still-picture sequence header for reconstruction tests.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param width the frame width
    /// @param height the frame height
    /// @return one minimal reduced-still-picture sequence header
    private static SequenceHeader createSequenceHeader(Av1ChromaFormat chromaFormat, int width, int height) {
        return createSequenceHeader(chromaFormat, 8, width, height);
    }

    /// Creates one minimal reduced-still-picture sequence header for reconstruction tests with one
    /// explicit decoded bit depth.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param bitDepth the decoded sample bit depth
    /// @param width the frame width
    /// @param height the frame height
    /// @return one minimal reduced-still-picture sequence header
    private static SequenceHeader createSequenceHeader(Av1ChromaFormat chromaFormat, int bitDepth, int width, int height) {
        return createSequenceHeader(chromaFormat, bitDepth, width, height, false);
    }

    /// Creates one minimal reduced-still-picture sequence header for reconstruction tests with an
    /// explicit decoded bit depth and intra-edge-filter setting.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param bitDepth the decoded sample bit depth
    /// @param width the frame width
    /// @param height the frame height
    /// @param intraEdgeFilter whether directional intra-edge filtering is enabled
    /// @return one minimal reduced-still-picture sequence header
    private static SequenceHeader createSequenceHeader(
            Av1ChromaFormat chromaFormat,
            int bitDepth,
            int width,
            int height,
            boolean intraEdgeFilter
    ) {
        return new SequenceHeader(
                sequenceProfile(chromaFormat, bitDepth),
                width,
                height,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(0, 0, 0, 0, false, false, false, null)
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
                        intraEdgeFilter,
                        false,
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
                        bitDepth,
                        chromaFormat == Av1ChromaFormat.MONOCHROME,
                        false,
                        2,
                        2,
                        2,
                        true,
                        chromaFormat,
                        0,
                        chromaFormat != Av1ChromaFormat.YUV444,
                        chromaFormat == Av1ChromaFormat.MONOCHROME || chromaFormat == Av1ChromaFormat.YUV420,
                        false
                )
        );
    }

    /// Returns one reduced-still-picture-compatible AV1 profile for the requested chroma layout and
    /// decoded bit depth.
    ///
    /// @param chromaFormat the decoded chroma layout
    /// @param bitDepth the decoded sample bit depth
    /// @return one reduced-still-picture-compatible AV1 profile
    private static int sequenceProfile(Av1ChromaFormat chromaFormat, int bitDepth) {
        return switch (chromaFormat) {
            case MONOCHROME, YUV420 -> bitDepth == 12 ? 2 : 0;
            case YUV422 -> 2;
            case YUV444 -> bitDepth == 12 ? 2 : 1;
        };
    }

    /// Creates one minimal frame header for reconstruction tests.
    ///
    /// @param frameType the frame type to expose
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @return one minimal frame header
    private static FrameHeader createFrameHeader(Av1FrameType frameType, int width, int height) {
        return new FrameHeader(
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
                false,
                false,
                7,
                0,
                0xFF,
                new FrameHeader.FrameSize(width, width, height, width, height),
                new FrameHeader.SuperResolutionInfo(false, width),
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
                FrameHeader.TransformMode.LARGEST,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one minimal key/intra frame header that enables horizontal super-resolution.
    ///
    /// @param frameType the frame type to expose
    /// @param codedWidth the coded width after downscaling
    /// @param upscaledWidth the stored width after super-resolution upscaling
    /// @param height the coded frame height
    /// @return one minimal key/intra frame header with super-resolution enabled
    private static FrameHeader createSuperResolvedFrameHeader(
            Av1FrameType frameType,
            int codedWidth,
            int upscaledWidth,
            int height
    ) {
        return new FrameHeader(
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
                false,
                false,
                7,
                0,
                0xFF,
                new FrameHeader.FrameSize(codedWidth, upscaledWidth, height, upscaledWidth, height),
                new FrameHeader.SuperResolutionInfo(true, 9),
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
                FrameHeader.TransformMode.LARGEST,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one minimal single-reference inter frame header that enables horizontal
    /// super-resolution.
    ///
    /// @param codedWidth the coded frame width before upscaling
    /// @param upscaledWidth the stored width after super-resolution upscaling
    /// @param height the coded frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @return one minimal single-reference inter frame header with super-resolution enabled
    private static FrameHeader createSuperResolvedInterFrameHeader(
            int codedWidth,
            int upscaledWidth,
            int height,
            int referenceSlot
    ) {
        return createSuperResolvedInterFrameHeader(
                Av1FrameType.INTER,
                codedWidth,
                upscaledWidth,
                height,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one minimal single-reference inter frame header that enables horizontal
    /// super-resolution with one caller-supplied frame-level interpolation filter.
    ///
    /// @param codedWidth the coded frame width before upscaling
    /// @param upscaledWidth the stored width after super-resolution upscaling
    /// @param height the coded frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @return one minimal single-reference inter frame header with super-resolution enabled
    private static FrameHeader createSuperResolvedInterFrameHeader(
            int codedWidth,
            int upscaledWidth,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter
    ) {
        return createSuperResolvedInterFrameHeader(
                Av1FrameType.INTER,
                codedWidth,
                upscaledWidth,
                height,
                referenceSlot,
                interpolationFilter
        );
    }

    /// Creates one minimal single-reference inter-compatible frame header that enables horizontal
    /// super-resolution.
    ///
    /// @param frameType the inter-compatible frame type to expose
    /// @param codedWidth the coded frame width before upscaling
    /// @param upscaledWidth the stored width after super-resolution upscaling
    /// @param height the coded frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @return one minimal single-reference inter-compatible frame header with super-resolution enabled
    private static FrameHeader createSuperResolvedInterFrameHeader(
            Av1FrameType frameType,
            int codedWidth,
            int upscaledWidth,
            int height,
            int referenceSlot
    ) {
        return createSuperResolvedInterFrameHeader(
                frameType,
                codedWidth,
                upscaledWidth,
                height,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one minimal single-reference inter-compatible frame header that enables horizontal
    /// super-resolution with one caller-supplied frame-level interpolation filter.
    ///
    /// @param frameType the inter-compatible frame type to expose
    /// @param codedWidth the coded frame width before upscaling
    /// @param upscaledWidth the stored width after super-resolution upscaling
    /// @param height the coded frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @return one minimal single-reference inter-compatible frame header with super-resolution enabled
    private static FrameHeader createSuperResolvedInterFrameHeader(
            Av1FrameType frameType,
            int codedWidth,
            int upscaledWidth,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                frameType,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                7,
                0,
                0,
                false,
                new int[]{referenceSlot, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(codedWidth, upscaledWidth, height, upscaledWidth, height),
                new FrameHeader.SuperResolutionInfo(true, 9),
                false,
                false,
                interpolationFilter,
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
                FrameHeader.TransformMode.LARGEST,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one minimal inter frame header for reconstruction tests.
    ///
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @return one minimal inter frame header
    private static FrameHeader createInterFrameHeader(int width, int height, int referenceSlot) {
        return createInterFrameHeader(
                width,
                height,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one minimal inter frame header for reconstruction tests with one caller-supplied
    /// frame-level interpolation filter.
    ///
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot to expose as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @return one minimal inter frame header
    private static FrameHeader createInterFrameHeader(
            int width,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                Av1FrameType.INTER,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                7,
                0,
                0,
                false,
                new int[]{referenceSlot, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(width, width, height, width, height),
                new FrameHeader.SuperResolutionInfo(false, width),
                false,
                false,
                interpolationFilter,
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
                FrameHeader.TransformMode.LARGEST,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one minimal compound-inter frame header for reconstruction tests with two stored
    /// reference slots and one caller-supplied interpolation filter.
    ///
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot to expose as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot to expose as `LAST2_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @return one minimal compound-inter frame header
    private static FrameHeader createInterFrameHeader(
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter interpolationFilter
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                Av1FrameType.INTER,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                7,
                0,
                0,
                false,
                new int[]{referenceSlot0, referenceSlot1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(width, width, height, width, height),
                new FrameHeader.SuperResolutionInfo(false, width),
                false,
                false,
                interpolationFilter,
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
                FrameHeader.TransformMode.LARGEST,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one supported intra block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param yMode the luma intra mode
    /// @param uvMode the chroma intra mode, or `null`
    /// @param filterIntraMode the filter-intra mode, or `null`
    /// @param yAngle the signed luma directional angle delta
    /// @param uvAngle the signed chroma directional angle delta
    /// @param yPaletteSize the luma palette size
    /// @param uvPaletteSize the chroma palette size
    /// @param cflAlphaU the signed CFL alpha for chroma U
    /// @param cflAlphaV the signed CFL alpha for chroma V
    /// @return one supported intra block header
    private static TileBlockHeaderReader.BlockHeader createIntraBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            LumaIntraPredictionMode yMode,
            @Nullable UvIntraPredictionMode uvMode,
            @Nullable FilterIntraMode filterIntraMode,
            int yAngle,
            int uvAngle,
            int yPaletteSize,
            int uvPaletteSize,
            int cflAlphaU,
            int cflAlphaV
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                yMode,
                uvMode,
                yPaletteSize,
                uvPaletteSize,
                new int[yPaletteSize],
                new int[uvPaletteSize],
                new int[uvPaletteSize],
                new byte[0],
                new byte[0],
                filterIntraMode,
                yAngle,
                uvAngle,
                cflAlphaU,
                cflAlphaV
        );
    }

    /// Creates one supported intra block header with caller-supplied palette entries and packed
    /// palette index maps.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param yMode the luma intra mode
    /// @param uvMode the chroma intra mode, or `null`
    /// @param filterIntraMode the filter-intra mode, or `null`
    /// @param yAngle the signed luma directional angle delta
    /// @param uvAngle the signed chroma directional angle delta
    /// @param yPaletteColors the caller-supplied luma palette entries
    /// @param uPaletteColors the caller-supplied chroma-U palette entries
    /// @param vPaletteColors the caller-supplied chroma-V palette entries
    /// @param yPaletteIndices the packed luma palette indices
    /// @param uvPaletteIndices the packed chroma palette indices
    /// @param cflAlphaU the signed CFL alpha for chroma U
    /// @param cflAlphaV the signed CFL alpha for chroma V
    /// @return one supported intra block header backed by the supplied palette data
    private static TileBlockHeaderReader.BlockHeader createIntraBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            LumaIntraPredictionMode yMode,
            @Nullable UvIntraPredictionMode uvMode,
            @Nullable FilterIntraMode filterIntraMode,
            int yAngle,
            int uvAngle,
            int[] yPaletteColors,
            int[] uPaletteColors,
            int[] vPaletteColors,
            byte[] yPaletteIndices,
            byte[] uvPaletteIndices,
            int cflAlphaU,
            int cflAlphaV
    ) {
        if (uPaletteColors.length != vPaletteColors.length) {
            throw new IllegalArgumentException("uPaletteColors length must match vPaletteColors length");
        }
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                yMode,
                uvMode,
                yPaletteColors.length,
                uPaletteColors.length,
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

    /// Creates one supported intra block header with zero directional angle deltas.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param yMode the luma intra mode
    /// @param uvMode the chroma intra mode, or `null`
    /// @param filterIntraMode the filter-intra mode, or `null`
    /// @param yPaletteSize the luma palette size
    /// @param uvPaletteSize the chroma palette size
    /// @param cflAlphaU the signed CFL alpha for chroma U
    /// @param cflAlphaV the signed CFL alpha for chroma V
    /// @return one supported intra block header
    private static TileBlockHeaderReader.BlockHeader createIntraBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            LumaIntraPredictionMode yMode,
            @Nullable UvIntraPredictionMode uvMode,
            @Nullable FilterIntraMode filterIntraMode,
            int yPaletteSize,
            int uvPaletteSize,
            int cflAlphaU,
            int cflAlphaV
    ) {
        return createIntraBlockHeader(
                position,
                size,
                hasChroma,
                yMode,
                uvMode,
                filterIntraMode,
                0,
                0,
                yPaletteSize,
                uvPaletteSize,
                cflAlphaU,
                cflAlphaV
        );
    }

    /// Creates one single-reference inter block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param motionVector the resolved primary motion vector
    /// @return one single-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createSingleReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            MotionVector motionVector
    ) {
        return createSingleReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                motionVector,
                SingleInterPredictionMode.NEARESTMV,
                MotionMode.SIMPLE
        );
    }

    /// Creates one single-reference inter block header with an explicit prediction mode.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param motionVector the resolved primary motion vector
    /// @param singleInterMode the decoded single-reference prediction mode
    /// @return one single-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createSingleReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            MotionVector motionVector,
            SingleInterPredictionMode singleInterMode
    ) {
        return createSingleReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                motionVector,
                singleInterMode,
                MotionMode.SIMPLE
        );
    }

    /// Creates one single-reference inter block header with an explicit motion mode.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param motionVector the resolved primary motion vector
    /// @param motionMode the decoded inter motion-compensation mode
    /// @return one single-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createSingleReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            MotionVector motionVector,
            MotionMode motionMode
    ) {
        return createSingleReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                motionVector,
                SingleInterPredictionMode.NEARESTMV,
                motionMode
        );
    }

    /// Creates one single-reference inter block header with explicit prediction and motion modes.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param motionVector the resolved primary motion vector
    /// @param singleInterMode the decoded single-reference prediction mode
    /// @param motionMode the decoded inter motion-compensation mode
    /// @return one single-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createSingleReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            MotionVector motionVector,
            SingleInterPredictionMode singleInterMode,
            MotionMode motionMode
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
                false,
                false,
                false,
                false,
                false,
                referenceFrame0,
                -1,
                singleInterMode,
                null,
                0,
                InterMotionVector.resolved(motionVector),
                null,
                motionMode,
                null,
                null,
                null,
                false,
                -1,
                false,
                null,
                false,
                -1,
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

    /// Creates one single-reference inter-intra block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param motionVector the resolved primary motion vector
    /// @param interIntraMode the decoded inter-intra prediction mode
    /// @param interIntraWedge whether the block uses an inter-intra wedge mask
    /// @param interIntraWedgeIndex the decoded wedge index, or `-1`
    /// @return one single-reference inter-intra block header
    private static TileBlockHeaderReader.BlockHeader createSingleReferenceInterIntraBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            MotionVector motionVector,
            InterIntraPredictionMode interIntraMode,
            boolean interIntraWedge,
            int interIntraWedgeIndex
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
                false,
                false,
                false,
                false,
                false,
                referenceFrame0,
                -1,
                SingleInterPredictionMode.NEARESTMV,
                null,
                0,
                InterMotionVector.resolved(motionVector),
                null,
                MotionMode.SIMPLE,
                null,
                null,
                null,
                false,
                -1,
                true,
                interIntraMode,
                interIntraWedge,
                interIntraWedgeIndex,
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

    /// Creates one `intrabc` block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param motionVector the resolved same-frame copy motion vector
    /// @return one `intrabc` block header
    private static TileBlockHeaderReader.BlockHeader createIntrabcBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            MotionVector motionVector
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
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
                false,
                0,
                LumaIntraPredictionMode.DC,
                hasChroma ? UvIntraPredictionMode.DC : null,
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

    /// Creates one compound-reference inter block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in LAST..ALTREF order
    /// @param motionVector0 the resolved primary motion vector
    /// @param motionVector1 the resolved secondary motion vector
    /// @return one compound-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createCompoundReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector motionVector0,
            MotionVector motionVector1
    ) {
        return createCompoundReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                referenceFrame1,
                motionVector0,
                motionVector1,
                CompoundPredictionType.AVERAGE,
                false,
                -1
        );
    }

    /// Creates one compound-reference inter block header with an explicit inter prediction mode.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in LAST..ALTREF order
    /// @param motionVector0 the resolved primary motion vector
    /// @param motionVector1 the resolved secondary motion vector
    /// @param compoundInterMode the decoded compound inter prediction mode
    /// @return one compound-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createCompoundReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector motionVector0,
            MotionVector motionVector1,
            CompoundInterPredictionMode compoundInterMode
    ) {
        return createCompoundReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                referenceFrame1,
                motionVector0,
                motionVector1,
                compoundInterMode,
                CompoundPredictionType.AVERAGE,
                false,
                -1
        );
    }

    /// Creates one compound-reference inter block header with an explicit compound prediction type.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in LAST..ALTREF order
    /// @param motionVector0 the resolved primary motion vector
    /// @param motionVector1 the resolved secondary motion vector
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param compoundMaskSign whether the decoded segment or wedge compound mask uses inverted source order
    /// @param compoundWedgeIndex the decoded compound wedge index, or `-1`
    /// @return one compound-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createCompoundReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector motionVector0,
            MotionVector motionVector1,
            CompoundPredictionType compoundPredictionType,
            boolean compoundMaskSign,
            int compoundWedgeIndex
    ) {
        return createCompoundReferenceInterBlockHeader(
                position,
                size,
                hasChroma,
                referenceFrame0,
                referenceFrame1,
                motionVector0,
                motionVector1,
                CompoundInterPredictionMode.NEARESTMV_NEARESTMV,
                compoundPredictionType,
                compoundMaskSign,
                compoundWedgeIndex
        );
    }

    /// Creates one compound-reference inter block header with explicit inter and blend modes.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param referenceFrame0 the primary inter reference in LAST..ALTREF order
    /// @param referenceFrame1 the secondary inter reference in LAST..ALTREF order
    /// @param motionVector0 the resolved primary motion vector
    /// @param motionVector1 the resolved secondary motion vector
    /// @param compoundInterMode the decoded compound inter prediction mode
    /// @param compoundPredictionType the decoded compound prediction blend type
    /// @param compoundMaskSign whether the decoded segment or wedge compound mask uses inverted source order
    /// @param compoundWedgeIndex the decoded compound wedge index, or `-1`
    /// @return one compound-reference inter block header
    private static TileBlockHeaderReader.BlockHeader createCompoundReferenceInterBlockHeader(
            BlockPosition position,
            BlockSize size,
            boolean hasChroma,
            int referenceFrame0,
            int referenceFrame1,
            MotionVector motionVector0,
            MotionVector motionVector1,
            CompoundInterPredictionMode compoundInterMode,
            CompoundPredictionType compoundPredictionType,
            boolean compoundMaskSign,
            int compoundWedgeIndex
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                hasChroma,
                false,
                false,
                false,
                false,
                true,
                referenceFrame0,
                referenceFrame1,
                null,
                compoundInterMode,
                0,
                InterMotionVector.resolved(motionVector0),
                InterMotionVector.resolved(motionVector1),
                MotionMode.SIMPLE,
                null,
                null,
                compoundPredictionType,
                compoundMaskSign,
                compoundWedgeIndex,
                false,
                null,
                false,
                -1,
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

    /// Creates one stored reference surface snapshot for synthetic inter reconstruction tests.
    ///
    /// @param chromaFormat the decoded chroma layout stored by the snapshot
    /// @param lumaSamples the luma sample raster
    /// @param chromaUSamples the chroma-U sample raster, or `null`
    /// @param chromaVSamples the chroma-V sample raster, or `null`
    /// @return one stored reference surface snapshot for synthetic inter reconstruction tests
    private static ReferenceSurfaceSnapshot createReferenceSurfaceSnapshot(
            Av1ChromaFormat chromaFormat,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        return createReferenceSurfaceSnapshot(chromaFormat, 8, lumaSamples, chromaUSamples, chromaVSamples);
    }

    /// Creates one stored reference surface snapshot for synthetic inter reconstruction tests with
    /// an explicit decoded bit depth.
    ///
    /// @param chromaFormat the decoded chroma layout stored by the snapshot
    /// @param bitDepth the decoded sample bit depth stored by the snapshot
    /// @param lumaSamples the luma sample raster
    /// @param chromaUSamples the chroma-U sample raster, or `null`
    /// @param chromaVSamples the chroma-V sample raster, or `null`
    /// @return one stored reference surface snapshot for synthetic inter reconstruction tests
    private static ReferenceSurfaceSnapshot createReferenceSurfaceSnapshot(
            Av1ChromaFormat chromaFormat,
            int bitDepth,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        int width = lumaSamples[0].length;
        int height = lumaSamples.length;
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, bitDepth, width, height);
        FrameHeader frameHeader = createFrameHeader(Av1FrameType.KEY, width, height);
        FrameSyntaxDecodeResult syntaxDecodeResult = createFrameSyntaxDecodeResult(sequenceHeader, frameHeader);
        return new ReferenceSurfaceSnapshot(
                syntaxDecodeResult.assembly().frameHeader(),
                syntaxDecodeResult,
                new DecodedSurface(
                        bitDepth,
                        chromaFormat,
                        width,
                        height,
                        width,
                        height,
                        createDecodedPlane(lumaSamples),
                        chromaUSamples != null ? createDecodedPlane(chromaUSamples) : null,
                        chromaVSamples != null ? createDecodedPlane(chromaVSamples) : null
                )
        );
    }

    /// Creates one stored reference surface snapshot whose frame header exposes super-resolution
    /// while the decoded planes already live in the post-upscale domain.
    ///
    /// @param chromaFormat the decoded chroma layout stored by the snapshot
    /// @param codedWidth the stored frame coded width before super-resolution upscaling
    /// @param upscaledWidth the stored frame width after super-resolution upscaling
    /// @param lumaSamples the post-upscale luma sample raster
    /// @param chromaUSamples the post-upscale chroma-U sample raster, or `null`
    /// @param chromaVSamples the post-upscale chroma-V sample raster, or `null`
    /// @return one stored reference surface snapshot with super-resolution metadata
    private static ReferenceSurfaceSnapshot createSuperResolvedReferenceSurfaceSnapshot(
            Av1ChromaFormat chromaFormat,
            int codedWidth,
            int upscaledWidth,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        int storedWidth = lumaSamples[0].length;
        if (storedWidth != upscaledWidth) {
            throw new IllegalArgumentException("lumaSamples width must match upscaledWidth");
        }
        int height = lumaSamples.length;
        SequenceHeader sequenceHeader = createSequenceHeader(chromaFormat, upscaledWidth, height);
        FrameHeader frameHeader = createSuperResolvedFrameHeader(Av1FrameType.KEY, codedWidth, upscaledWidth, height);
        FrameSyntaxDecodeResult syntaxDecodeResult = createFrameSyntaxDecodeResult(sequenceHeader, frameHeader);
        return new ReferenceSurfaceSnapshot(
                frameHeader,
                syntaxDecodeResult,
                new DecodedSurface(
                        8,
                        chromaFormat,
                        upscaledWidth,
                        height,
                        upscaledWidth,
                        height,
                        createDecodedPlane(lumaSamples),
                        chromaUSamples != null ? createDecodedPlane(chromaUSamples) : null,
                        chromaVSamples != null ? createDecodedPlane(chromaVSamples) : null
                )
        );
    }

    /// Creates one slot-indexed reference-surface array for reconstruction tests.
    ///
    /// @param slot the zero-based reference slot to populate
    /// @param snapshot the stored reference surface to expose through the populated slot
    /// @return one slot-indexed reference-surface array for reconstruction tests
    private static @Nullable ReferenceSurfaceSnapshot[] createReferenceSurfaceSlots(
            int slot,
            ReferenceSurfaceSnapshot snapshot
    ) {
        ReferenceSurfaceSnapshot[] slots = new ReferenceSurfaceSnapshot[8];
        slots[slot] = snapshot;
        return slots;
    }

    /// Creates one slot-indexed reference-surface array with two populated entries.
    ///
    /// @param slot0 the first zero-based reference slot to populate
    /// @param snapshot0 the stored reference surface exposed through `slot0`
    /// @param slot1 the second zero-based reference slot to populate
    /// @param snapshot1 the stored reference surface exposed through `slot1`
    /// @return one slot-indexed reference-surface array for compound reconstruction tests
    private static @Nullable ReferenceSurfaceSnapshot[] createReferenceSurfaceSlots(
            int slot0,
            ReferenceSurfaceSnapshot snapshot0,
            int slot1,
            ReferenceSurfaceSnapshot snapshot1
    ) {
        ReferenceSurfaceSnapshot[] slots = new ReferenceSurfaceSnapshot[8];
        slots[slot0] = snapshot0;
        slots[slot1] = snapshot1;
        return slots;
    }

    /// Creates one decoded plane from an exact sample raster.
    ///
    /// @param samples the exact sample raster in row-major order
    /// @return one decoded plane from the supplied exact sample raster
    private static PaddedPlane createDecodedPlane(int[][] samples) {
        int height = samples.length;
        int width = samples[0].length;
        short[] storage = new short[width * height];
        int nextIndex = 0;
        for (int y = 0; y < height; y++) {
            if (samples[y].length != width) {
                throw new IllegalArgumentException("sample rows must share the same width");
            }
            for (int x = 0; x < width; x++) {
                storage[nextIndex++] = (short) samples[y][x];
            }
        }
        return new PaddedPlane(width, height, width, storage);
    }

    /// Creates one deterministic sample ramp for reconstruction tests.
    ///
    /// @param width the raster width
    /// @param height the raster height
    /// @param base the sample value at the origin
    /// @param stepX the sample increment per X coordinate
    /// @param stepY the sample increment per Y coordinate
    /// @return one deterministic sample ramp
    private static int[][] rampSamples(int width, int height, int base, int stepX, int stepY) {
        int[][] samples = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y][x] = base + x * stepX + y * stepY;
            }
        }
        return samples;
    }

    /// Returns one rectangular crop from a larger sample raster.
    ///
    /// @param samples the source sample raster
    /// @param x the source crop origin X coordinate
    /// @param y the source crop origin Y coordinate
    /// @param width the crop width in samples
    /// @param height the crop height in samples
    /// @return one rectangular crop from the source raster
    private static int[][] cropSamples(int[][] samples, int x, int y, int width, int height) {
        int[][] cropped = new int[height][width];
        for (int row = 0; row < height; row++) {
            System.arraycopy(samples[y + row], x, cropped[row], 0, width);
        }
        return cropped;
    }

    /// Computes the expected OBMC blend for an above-neighbor overlap.
    ///
    /// @param currentPredictor the already written current-block predictor
    /// @param neighborPredictor the above-neighbor predictor covering the overlap rows
    /// @param overlap the overlap height in samples
    /// @return the expected OBMC-blended current-block predictor
    private static int[][] expectedObmcAboveBlend(int[][] currentPredictor, int[][] neighborPredictor, int overlap) {
        int[][] expected = copySamples(currentPredictor);
        int[] mask = obmcMask(overlap);
        for (int y = 0; y < overlap; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                expected[y][x] = blendObmcSamples(neighborPredictor[y][x], currentPredictor[y][x], mask[y]);
            }
        }
        return expected;
    }

    /// Computes the expected OBMC blend for a left-neighbor overlap.
    ///
    /// @param currentPredictor the already written current-block predictor
    /// @param neighborPredictor the left-neighbor predictor covering the overlap columns
    /// @param overlap the overlap width in samples
    /// @return the expected OBMC-blended current-block predictor
    private static int[][] expectedObmcLeftBlend(int[][] currentPredictor, int[][] neighborPredictor, int overlap) {
        int[][] expected = copySamples(currentPredictor);
        int[] mask = obmcMask(overlap);
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < overlap; x++) {
                expected[y][x] = blendObmcSamples(neighborPredictor[y][x], currentPredictor[y][x], mask[x]);
            }
        }
        return expected;
    }

    /// Returns a deep copy of one sample raster.
    ///
    /// @param samples the source sample raster
    /// @return a deep copy of the supplied sample raster
    private static int[][] copySamples(int[][] samples) {
        int[][] copy = new int[samples.length][];
        for (int y = 0; y < samples.length; y++) {
            copy[y] = new int[samples[y].length];
            System.arraycopy(samples[y], 0, copy[y], 0, samples[y].length);
        }
        return copy;
    }

    /// Returns one OBMC masked blend sample with the current predictor as the secondary source.
    ///
    /// @param neighborSample the neighbor predictor sample
    /// @param currentSample the current predictor sample
    /// @param currentWeight the current predictor weight in `[0, 64]`
    /// @return one OBMC masked blend sample
    private static int blendObmcSamples(int neighborSample, int currentSample, int currentWeight) {
        return (neighborSample * (64 - currentWeight) + currentSample * currentWeight + 32) >> 6;
    }

    /// Returns the AV1 OBMC mask used by the tested overlap length.
    ///
    /// @param overlap the overlap length in samples
    /// @return the AV1 OBMC mask used by the tested overlap length
    private static int[] obmcMask(int overlap) {
        return switch (overlap) {
            case 4 -> new int[]{39, 50, 59, 64};
            case 8 -> new int[]{36, 42, 48, 53, 57, 61, 64, 64};
            default -> throw new IllegalArgumentException("Unsupported test OBMC overlap: " + overlap);
        };
    }

    /// Computes the expected compound wedge blend for two direct predictors.
    ///
    /// @param primarySamples the primary predictor samples
    /// @param secondarySamples the secondary predictor samples
    /// @param size the luma block size that owns the prediction
    /// @param maskSign whether the decoded wedge mask uses inverted source order
    /// @param wedgeIndex the decoded compound wedge index
    /// @return the expected compound wedge blend
    private static int[][] expectedCompoundWedgeBlend(
            int[][] primarySamples,
            int[][] secondarySamples,
            BlockSize size,
            boolean maskSign,
            int wedgeIndex
    ) {
        int height = primarySamples.length;
        int width = primarySamples[0].length;
        int[][] expected = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mask = InterIntraMasks.compoundWedgeMaskValue(size, wedgeIndex, maskSign, x, y, 0, 0);
                expected[y][x] = (primarySamples[y][x] * (64 - mask) + secondarySamples[y][x] * mask + 32) >> 6;
            }
        }
        return expected;
    }

    /// Computes the expected compound segment blend for two direct predictors.
    ///
    /// @param primarySamples the primary predictor samples
    /// @param secondarySamples the secondary predictor samples
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @param bitDepth the decoded sample bit depth
    /// @return the expected compound segment blend
    private static int[][] expectedCompoundSegmentBlend(
            int[][] primarySamples,
            int[][] secondarySamples,
            boolean maskSign,
            int bitDepth
    ) {
        return expectedCompoundSegmentBlend(
                primarySamples,
                secondarySamples,
                primarySamples,
                secondarySamples,
                maskSign,
                bitDepth
        );
    }

    /// Computes the expected compound segment blend using a mask derived from separate luma
    /// predictors.
    ///
    /// @param primarySamples the primary plane predictor samples to blend
    /// @param secondarySamples the secondary plane predictor samples to blend
    /// @param primaryLumaSamples the primary luma predictor samples used to derive the mask
    /// @param secondaryLumaSamples the secondary luma predictor samples used to derive the mask
    /// @param maskSign whether the decoded segment mask uses inverted source order
    /// @param bitDepth the decoded sample bit depth
    /// @return the expected blended samples
    private static int[][] expectedCompoundSegmentBlend(
            int[][] primarySamples,
            int[][] secondarySamples,
            int[][] primaryLumaSamples,
            int[][] secondaryLumaSamples,
            boolean maskSign,
            int bitDepth
    ) {
        int height = primarySamples.length;
        int width = primarySamples[0].length;
        int[][] expected = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mask = segmentCompoundMask(primaryLumaSamples[y][x], secondaryLumaSamples[y][x], bitDepth);
                int secondaryWeight = maskSign ? mask : 64 - mask;
                expected[y][x] = (primarySamples[y][x] * (64 - secondaryWeight)
                        + secondarySamples[y][x] * secondaryWeight + 32) >> 6;
            }
        }
        return expected;
    }

    /// Returns one luma-domain segment-compound mask value.
    ///
    /// @param primarySample the primary predicted sample
    /// @param secondarySample the secondary predicted sample
    /// @param bitDepth the decoded sample bit depth
    /// @return one luma-domain segment-compound mask value
    private static int segmentCompoundMask(int primarySample, int secondarySample, int bitDepth) {
        int intermediateBits = 4;
        int maskShift = bitDepth + intermediateBits - 4;
        int maskRound = 1 << (maskShift - 5);
        int scaledDifference = ((Math.abs(primarySample - secondarySample) << intermediateBits) + maskRound) >> maskShift;
        return Math.min(38 + scaledDifference, 64);
    }

    /// Computes the expected inter-intra blend against the default edge-derived intra predictor.
    ///
    /// @param interSamples the inter predictor samples
    /// @param mode the decoded inter-intra prediction mode
    /// @param wedge whether the mask is a wedge mask
    /// @param wedgeIndex the decoded wedge index, or `-1`
    /// @param size the luma block size that owns the prediction
    /// @param subsamplingX the horizontal chroma subsampling shift for the plane
    /// @param subsamplingY the vertical chroma subsampling shift for the plane
    /// @return the expected blended sample raster
    private static int[][] expectedInterIntraBlend(
            int[][] interSamples,
            InterIntraPredictionMode mode,
            boolean wedge,
            int wedgeIndex,
            BlockSize size,
            int subsamplingX,
            int subsamplingY
    ) {
        int height = interSamples.length;
        int width = interSamples[0].length;
        MutablePlaneBuffer intraPlane = new MutablePlaneBuffer(width, height, 8);
        for (int y = 0; y < height; y++) {
            if (interSamples[y].length != width) {
                throw new IllegalArgumentException("inter sample rows must share the same width");
            }
            for (int x = 0; x < width; x++) {
                intraPlane.setSample(x, y, interSamples[y][x]);
            }
        }
        if (subsamplingX == 0 && subsamplingY == 0) {
            new IntraPredictor().predictLuma(intraPlane, 0, 0, width, height, mode.toLumaPredictionMode(), 0);
        } else {
            new IntraPredictor().predictChroma(intraPlane, 0, 0, width, height, mode.toUvPredictionMode(), 0);
        }
        int[][] expected = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mask = InterIntraMasks.maskValue(mode, wedge, wedgeIndex, size, x, y, subsamplingX, subsamplingY);
                expected[y][x] = (interSamples[y][x] * (64 - mask) + intraPlane.sample(x, y) * mask + 32) >> 6;
            }
        }
        return expected;
    }

    /// Creates one transform layout that exactly covers one leaf block with one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param chromaFormat the active decoded chroma layout
    /// @return one transform layout that exactly covers one leaf block
    private static TransformLayout createTransformLayout(BlockPosition position, BlockSize size, Av1ChromaFormat chromaFormat) {
        return createTransformLayout(
                position,
                size,
                chromaFormat,
                size.widthPixels(),
                size.heightPixels()
        );
    }

    /// Creates one transform layout with an exact visible footprint and one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param chromaFormat the active decoded chroma layout
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @return one transform layout with the supplied visible footprint
    private static TransformLayout createTransformLayout(
            BlockPosition position,
            BlockSize size,
            Av1ChromaFormat chromaFormat,
            int visibleWidthPixels,
            int visibleHeightPixels
    ) {
        TransformSize transformSize = size.maxLumaTransformSize();
        return new TransformLayout(
                position,
                size,
                (visibleWidthPixels + 3) >> 2,
                (visibleHeightPixels + 3) >> 2,
                visibleWidthPixels,
                visibleHeightPixels,
                transformSize,
                size.maxChromaTransformSize(chromaFormat),
                false,
                new TransformUnit[]{new TransformUnit(position, transformSize)}
        );
    }

    /// Creates one 128x64 directional `YUV422` leaf whose top-right chroma transform carries the
    /// supplied DC coefficient.
    ///
    /// @param topRightChromaDcCoefficient the DC coefficient stored in both top-right chroma planes
    /// @return one synthetic wide directional leaf
    private static TilePartitionTreeReader.LeafNode createWideDirectionalI422Leaf(
            int topRightChromaDcCoefficient
    ) {
        BlockPosition origin = new BlockPosition(0, 0);
        BlockPosition lumaRight = origin.offset(16, 0);
        BlockPosition chromaBottomLeft = origin.offset(0, 8);
        BlockPosition chromaBottomRight = origin.offset(16, 8);
        BlockSize blockSize = BlockSize.SIZE_128X64;
        TransformSize lumaTransformSize = TransformSize.TX_64X64;
        TransformSize chromaTransformSize = TransformSize.TX_32X32;
        TransformUnit[] lumaTransformUnits = {
                new TransformUnit(origin, lumaTransformSize),
                new TransformUnit(lumaRight, lumaTransformSize)
        };
        TransformUnit[] chromaTransformUnits = {
                new TransformUnit(origin, chromaTransformSize),
                new TransformUnit(lumaRight, chromaTransformSize),
                new TransformUnit(chromaBottomLeft, chromaTransformSize),
                new TransformUnit(chromaBottomRight, chromaTransformSize)
        };
        TransformLayout transformLayout = new TransformLayout(
                origin,
                blockSize,
                blockSize.width4(),
                blockSize.height4(),
                blockSize.widthPixels(),
                blockSize.heightPixels(),
                lumaTransformSize,
                chromaTransformSize,
                false,
                lumaTransformUnits,
                chromaTransformUnits
        );
        TransformResidualUnit[] lumaResidualUnits = {
                createResidualUnit(origin, lumaTransformSize, 64, 64, 0),
                createResidualUnit(lumaRight, lumaTransformSize, 64, 64, 0)
        };
        TransformResidualUnit[] chromaUResidualUnits = {
                createResidualUnit(origin, chromaTransformSize, 32, 32, 0),
                createResidualUnit(lumaRight, chromaTransformSize, 32, 32, topRightChromaDcCoefficient),
                createResidualUnit(chromaBottomLeft, chromaTransformSize, 32, 32, 0),
                createResidualUnit(chromaBottomRight, chromaTransformSize, 32, 32, 0)
        };
        TransformResidualUnit[] chromaVResidualUnits = {
                createResidualUnit(origin, chromaTransformSize, 32, 32, 0),
                createResidualUnit(lumaRight, chromaTransformSize, 32, 32, -topRightChromaDcCoefficient),
                createResidualUnit(chromaBottomLeft, chromaTransformSize, 32, 32, 0),
                createResidualUnit(chromaBottomRight, chromaTransformSize, 32, 32, 0)
        };
        ResidualLayout residualLayout = new ResidualLayout(
                origin,
                blockSize,
                lumaResidualUnits,
                chromaUResidualUnits,
                chromaVResidualUnits
        );
        return new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(
                        origin,
                        blockSize,
                        true,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        UvIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        null,
                        0,
                        0,
                        0,
                        0
                ),
                transformLayout,
                residualLayout
        );
    }

    /// Creates one residual layout with either all-zero or non-zero coefficients.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param allZero whether the synthetic residual unit should be all zero
    /// @return one residual layout with either all-zero or non-zero coefficients
    private static ResidualLayout createResidualLayout(BlockPosition position, BlockSize size, boolean allZero) {
        TransformSize transformSize = size.maxLumaTransformSize();
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        int endOfBlockIndex = -1;
        int coefficientContextByte = 0;
        if (!allZero) {
            coefficients[0] = 1;
            endOfBlockIndex = 0;
            coefficientContextByte = 1;
        }
        return new ResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{
                        new TransformResidualUnit(
                                position,
                                transformSize,
                                TransformType.DCT_DCT,
                                endOfBlockIndex,
                                coefficients,
                                coefficientContextByte
                        )
                }
        );
    }

    /// Creates one residual layout with one caller-supplied luma DC coefficient.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param dcCoefficient the signed luma DC coefficient to store
    /// @return one residual layout with one caller-supplied luma DC coefficient
    private static ResidualLayout createResidualLayout(BlockPosition position, BlockSize size, int dcCoefficient) {
        TransformSize transformSize = size.maxLumaTransformSize();
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[0] = dcCoefficient;
        return new ResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{
                        new TransformResidualUnit(
                                position,
                                transformSize,
                                TransformType.DCT_DCT,
                                0,
                                coefficients,
                                expectedNonZeroCoefficientContextByte(dcCoefficient)
                        )
                }
        );
    }

    /// Creates one residual layout whose luma units stay all-zero while one or both chroma planes
    /// carry a caller-supplied DC coefficient.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param chromaFormat the active decoded chroma layout
    /// @param chromaUDcCoefficient the signed chroma-U DC coefficient to store
    /// @param chromaVDcCoefficient the signed chroma-V DC coefficient to store
    /// @return one residual layout with all-zero luma units and caller-supplied chroma DC coefficients
    private static ResidualLayout createChromaDcResidualLayout(
            BlockPosition position,
            BlockSize size,
            Av1ChromaFormat chromaFormat,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TransformSize lumaTransformSize = size.maxLumaTransformSize();
        TransformResidualUnit lumaUnit = new TransformResidualUnit(
                position,
                lumaTransformSize,
                TransformType.DCT_DCT,
                -1,
                new int[lumaTransformSize.widthPixels() * lumaTransformSize.heightPixels()],
                0
        );

        @Nullable TransformSize chromaTransformSize = size.maxChromaTransformSize(chromaFormat);
        if (chromaTransformSize == null) {
            throw new AssertionError("Expected chroma transform size for " + chromaFormat);
        }

        int chromaVisibleWidth = switch (chromaFormat) {
            case MONOCHROME -> throw new AssertionError("Expected chroma pixel format");
            case YUV420, YUV422 -> (size.widthPixels() + 1) >> 1;
            case YUV444 -> size.widthPixels();
        };
        int chromaVisibleHeight = switch (chromaFormat) {
            case MONOCHROME -> throw new AssertionError("Expected chroma pixel format");
            case YUV420 -> (size.heightPixels() + 1) >> 1;
            case YUV422, YUV444 -> size.heightPixels();
        };

        return new ResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{lumaUnit},
                new TransformResidualUnit[]{createResidualUnit(position, chromaTransformSize, chromaVisibleWidth, chromaVisibleHeight, chromaUDcCoefficient)},
                new TransformResidualUnit[]{createResidualUnit(position, chromaTransformSize, chromaVisibleWidth, chromaVisibleHeight, chromaVDcCoefficient)}
        );
    }

    /// Creates one residual unit with one caller-supplied DC coefficient and exact visible
    /// footprint metadata.
    ///
    /// @param position the residual-unit origin in luma 4x4 units
    /// @param transformSize the transform size stored in the residual unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed DC coefficient to store
    /// @return one residual unit with one caller-supplied DC coefficient
    private static TransformResidualUnit createResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        int endOfBlockIndex = -1;
        int coefficientContextByte = 0;
        if (dcCoefficient != 0) {
            coefficients[0] = dcCoefficient;
            endOfBlockIndex = 0;
            coefficientContextByte = expectedNonZeroCoefficientContextByte(dcCoefficient);
        }
        return new TransformResidualUnit(
                position,
                transformSize,
                TransformType.DCT_DCT,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
        );
    }

    /// Asserts that one chroma palette block can be reconstructed first, then shifted by chroma
    /// residual units without changing luma or crossing U/V planes.
    ///
    /// @param chromaFormat the non-monochrome decoded chroma layout to test
    /// @param chromaPaletteIndices the unpacked chroma palette index raster for the tested layout
    private static void assertChromaPaletteResidualOverlay(Av1ChromaFormat chromaFormat, int[][] chromaPaletteIndices) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaPaletteU = new int[]{48, 144, 216};
        int[] chromaPaletteV = new int[]{208, 104, 32};
        TileBlockHeaderReader.BlockHeader header = createIntraBlockHeader(
                position,
                size,
                true,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                null,
                0,
                0,
                new int[0],
                chromaPaletteU,
                chromaPaletteV,
                new byte[0],
                packPaletteIndices(chromaPaletteIndices),
                0,
                0
        );
        TilePartitionTreeReader.LeafNode baselineLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, chromaFormat),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, chromaFormat),
                createChromaDcResidualLayout(position, size, chromaFormat, 64, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedSurface baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(chromaFormat, Av1FrameType.KEY, 8, 8, baselineLeaf)
        );
        DecodedSurface residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(chromaFormat, Av1FrameType.KEY, 8, 8, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residual.lumaPlane());
        assertPlaneEquals(requirePlane(baseline.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(baseline.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residual.chromaUPlane()),
                1
        );
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residual.chromaVPlane()),
                -1
        );
    }

    /// Packs one unpacked palette-index raster to the same two-4-bit-per-byte layout used by the
    /// bitstream-side palette model.
    ///
    /// @param indices the unpacked palette-index raster in natural row-major order
    /// @return the packed palette-index bytes
    private static byte[] packPaletteIndices(int[][] indices) {
        if (indices.length == 0) {
            return new byte[0];
        }
        int width = indices[0].length;
        if (width == 0 || (width & 1) != 0) {
            throw new IllegalArgumentException("palette index rows must have one non-zero even width");
        }
        byte[] packed = new byte[(width >> 1) * indices.length];
        int nextIndex = 0;
        for (int y = 0; y < indices.length; y++) {
            if (indices[y].length != width) {
                throw new IllegalArgumentException("palette index rows must share the same width");
            }
            for (int x = 0; x < width; x += 2) {
                int low = requirePaletteIndex(indices[y][x]);
                int high = requirePaletteIndex(indices[y][x + 1]);
                packed[nextIndex++] = (byte) (low | (high << 4));
            }
        }
        return packed;
    }

    /// Expands one palette-index raster into decoded sample values by indexing the supplied palette.
    ///
    /// @param paletteColors the palette entries addressed by the raster
    /// @param indices the unpacked palette-index raster in natural row-major order
    /// @return the expanded sample raster
    private static int[][] expandPaletteRaster(int[] paletteColors, int[][] indices) {
        int[][] samples = new int[indices.length][];
        for (int y = 0; y < indices.length; y++) {
            samples[y] = new int[indices[y].length];
            for (int x = 0; x < indices[y].length; x++) {
                int paletteIndex = indices[y][x];
                if (paletteIndex < 0 || paletteIndex >= paletteColors.length) {
                    throw new IllegalArgumentException("palette index out of range: " + paletteIndex);
                }
                samples[y][x] = paletteColors[paletteIndex];
            }
        }
        return samples;
    }

    /// Creates one deterministic palette-index raster with all entries in range.
    ///
    /// @param width the raster width in samples
    /// @param height the raster height in samples
    /// @param paletteSize the number of available palette entries
    /// @return one deterministic palette-index raster
    private static int[][] repeatingPaletteIndices(int width, int height, int paletteSize) {
        int[][] indices = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                indices[y][x] = (x + y + (x >> 1)) % paletteSize;
            }
        }
        return indices;
    }

    /// Returns the top-left visible crop of one expected sample raster.
    ///
    /// @param samples the full expected sample raster
    /// @param width the visible crop width
    /// @param height the visible crop height
    /// @return the top-left visible crop of the supplied sample raster
    private static int[][] cropRaster(int[][] samples, int width, int height) {
        int[][] cropped = new int[height][width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(samples[y], 0, cropped[y], 0, width);
        }
        return cropped;
    }

    /// Returns the chroma width corresponding to one luma width in the supplied layout.
    ///
    /// @param lumaWidth the luma width in samples
    /// @param chromaFormat the decoded chroma layout
    /// @return the corresponding chroma width in samples
    private static int chromaSampleWidth(int lumaWidth, Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME -> throw new AssertionError("Expected chroma pixel format");
            case YUV420, YUV422 -> (lumaWidth + 1) >> 1;
            case YUV444 -> lumaWidth;
        };
    }

    /// Returns the chroma height corresponding to one luma height in the supplied layout.
    ///
    /// @param lumaHeight the luma height in samples
    /// @param chromaFormat the decoded chroma layout
    /// @return the corresponding chroma height in samples
    private static int chromaSampleHeight(int lumaHeight, Av1ChromaFormat chromaFormat) {
        return switch (chromaFormat) {
            case MONOCHROME -> throw new AssertionError("Expected chroma pixel format");
            case YUV420 -> (lumaHeight + 1) >> 1;
            case YUV422, YUV444 -> lumaHeight;
        };
    }

    /// Validates one packed-palette nibble value.
    ///
    /// @param index the unpacked palette index
    /// @return the same unpacked palette index after validation
    private static int requirePaletteIndex(int index) {
        if (index < 0 || index > 0x0F) {
            throw new IllegalArgumentException("palette index out of nibble range: " + index);
        }
        return index;
    }

    /// Asserts that one decoded plane is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param width the expected plane width
    /// @param height the expected plane height
    /// @param expectedSample the expected sample value at every coordinate
    private static void assertPlaneFilled(PaddedPlane plane, int width, int height, int expectedSample) {
        assertEquals(width, plane.width());
        assertEquals(height, plane.height());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(expectedSample, plane.sample(x, y), "sample at (" + x + ", " + y + ")");
            }
        }
    }

    /// Asserts that one rectangular block is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param width the block width
    /// @param height the block height
    /// @param expectedSample the expected sample value throughout the block
    private static void assertPlaneBlockFilled(
            PaddedPlane plane,
            int x,
            int y,
            int width,
            int height,
            int expectedSample
    ) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                assertEquals(expectedSample, plane.sample(x + column, y + row));
            }
        }
    }

    /// Asserts that one decoded plane matches the supplied expected raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expected the expected sample raster
    private static void assertPlaneEquals(PaddedPlane plane, int[][] expected) {
        assertEquals(expected[0].length, plane.width());
        assertEquals(expected.length, plane.height());
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], plane.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded plane's top-left stored region matches the supplied samples.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expected the expected stored sample region, including relevant right and bottom padding
    private static void assertStoredPlaneRegionEquals(PaddedPlane plane, int[][] expected) {
        assertTrue(plane.stride() >= expected[0].length);
        assertTrue(plane.storageHeight() >= expected.length);
        short[] samples = plane.samples();
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], samples[y * plane.stride() + x] & 0xFFFF);
            }
        }
    }

    /// Asserts that one decoded sub-block matches the supplied expected raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param expected the expected sample raster
    private static void assertPlaneBlockEquals(PaddedPlane plane, int x, int y, int[][] expected) {
        for (int row = 0; row < expected.length; row++) {
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(expected[row][column], plane.sample(x + column, y + row));
            }
        }
    }

    /// Asserts that every sample differs from the baseline by the same non-zero signed offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the non-zero residual plane
    /// @param expectedSign the required sign of the uniform delta, either `1` or `-1`
    private static void assertPlaneDiffersFromBaselineByUniformSignedOffset(
            PaddedPlane baseline,
            PaddedPlane reconstructed,
            int expectedSign
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());

        int firstDelta = reconstructed.sample(0, 0) - baseline.sample(0, 0);
        assertEquals(expectedSign, Integer.signum(firstDelta));
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                assertEquals(firstDelta, reconstructed.sample(x, y) - baseline.sample(x, y));
            }
        }
    }

    /// Asserts that one plane contains at least one positive difference and no negative differences.
    ///
    /// @param baseline the baseline plane
    /// @param reconstructed the plane expected to contain a positive contribution
    private static void assertPlaneContainsPositiveButNoNegativeDifferences(
            PaddedPlane baseline,
            PaddedPlane reconstructed
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        boolean positiveDifference = false;
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                int difference = reconstructed.sample(x, y) - baseline.sample(x, y);
                assertTrue(difference >= 0, "negative difference at (" + x + ", " + y + ")");
                positiveDifference |= difference > 0;
            }
        }
        assertTrue(positiveDifference, "expected at least one positive sample difference");
    }

    /// Asserts that two decoded planes carry the same stored sample values.
    ///
    /// @param expected the expected decoded plane
    /// @param actual the actual decoded plane
    private static void assertPlanesEqual(PaddedPlane expected, PaddedPlane actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                assertEquals(expected.sample(x, y), actual.sample(x, y));
            }
        }
    }

    /// Asserts that two decoded planes carry identical samples throughout one rectangular region.
    ///
    /// @param expected the expected decoded plane
    /// @param actual the actual decoded plane
    /// @param x the region origin X coordinate
    /// @param y the region origin Y coordinate
    /// @param width the region width
    /// @param height the region height
    private static void assertPlaneRegionEquals(
            PaddedPlane expected,
            PaddedPlane actual,
            int x,
            int y,
            int width,
            int height
    ) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                assertEquals(expected.sample(x + column, y + row), actual.sample(x + column, y + row));
            }
        }
    }

    /// Asserts that one rectangular region differs from the baseline by one uniform signed offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the non-zero residual plane
    /// @param x the region origin X coordinate
    /// @param y the region origin Y coordinate
    /// @param width the region width
    /// @param height the region height
    /// @param expectedSign the required sign of the uniform delta, either `1` or `-1`
    private static void assertPlaneRegionDiffersFromBaselineByUniformSignedOffset(
            PaddedPlane baseline,
            PaddedPlane reconstructed,
            int x,
            int y,
            int width,
            int height,
            int expectedSign
    ) {
        int firstDelta = reconstructed.sample(x, y) - baseline.sample(x, y);
        assertEquals(expectedSign, Integer.signum(firstDelta));
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                assertEquals(firstDelta, reconstructed.sample(x + column, y + row) - baseline.sample(x + column, y + row));
            }
        }
    }

    /// Returns the expected post-super-resolution plane produced by first sampling one stored
    /// reference plane through scaled inter mapping in the coded domain and then
    /// horizontally upscaling each coded row.
    ///
    /// @param referencePlane the stored post-upscale reference plane
    /// @param codedWidth the coded-domain destination width before super-resolution
    /// @param codedHeight the coded-domain destination height before super-resolution
    /// @param upscaledWidth the final post-super-resolution plane width
    /// @param motionVector the block motion vector expressed in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator
    /// @param denominatorY the plane-local vertical denominator
    /// @param widthForFilterSelection the sampled block width used for reduced-width filter selection
    /// @param heightForFilterSelection the sampled block height used for reduced-width filter selection
    /// @param filterMode the interpolation filter used for both directions
    /// @return the expected post-super-resolution plane raster
    private static int[][] expectedSuperResolvedInterPlaneFromMappedReference(
            PaddedPlane referencePlane,
            int codedWidth,
            int codedHeight,
            int upscaledWidth,
            MotionVector motionVector,
            int denominatorX,
            int denominatorY,
            int widthForFilterSelection,
            int heightForFilterSelection,
            FrameHeader.InterpolationFilter filterMode
    ) {
        int[][] codedSamples = new int[codedHeight][codedWidth];
        boolean scaled = referencePlane.width() != codedWidth || referencePlane.height() != codedHeight;
        int horizontalScale = expectedReferenceScaleFactor(referencePlane.width(), codedWidth);
        int verticalScale = expectedReferenceScaleFactor(referencePlane.height(), codedHeight);
        for (int y = 0; y < codedHeight; y++) {
            for (int x = 0; x < codedWidth; x++) {
                int sourceNumeratorX = scaled
                        ? expectedScaledReferenceNumerator(
                        0,
                        x,
                        motionVector.columnEighthPel(),
                        denominatorX,
                        horizontalScale
                )
                        : x * denominatorX + motionVector.columnEighthPel();
                int sourceNumeratorY = scaled
                        ? expectedScaledReferenceNumerator(
                        0,
                        y,
                        motionVector.rowEighthPel(),
                        denominatorY,
                        verticalScale
                )
                        : y * denominatorY + motionVector.rowEighthPel();
                int interpolationDenominatorX = scaled ? 1 << 10 : denominatorX;
                int interpolationDenominatorY = scaled ? 1 << 10 : denominatorY;
                codedSamples[y][x] = InterPredictionOracle.sampleReferencePlaneBlock(
                        referencePlane,
                        1,
                        1,
                        sourceNumeratorX,
                        sourceNumeratorY,
                        interpolationDenominatorX,
                        interpolationDenominatorY,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        filterMode
                )[0][0];
            }
        }

        int[][] expected = new int[codedHeight][upscaledWidth];
        for (int y = 0; y < codedHeight; y++) {
            expected[y] = expectedHorizontallyUpscaledRow(codedSamples[y], upscaledWidth);
        }
        return expected;
    }

    /// Returns the expected Q10 source coordinate for AV1 scaled inter prediction.
    ///
    /// @param destinationOrigin the block origin in plane samples
    /// @param sampleOffset the block-local sample offset
    /// @param motionVectorEighthPel the motion-vector component in luma eighth-pel units
    /// @param planeDenominator the plane-local denominator in luma eighth-pel units
    /// @param scaleFactor the Q14 reference scale factor
    /// @return the expected Q10 source coordinate
    private static int expectedScaledReferenceNumerator(
            int destinationOrigin,
            int sampleOffset,
            int motionVectorEighthPel,
            int planeDenominator,
            int scaleFactor
    ) {
        long originalPosition = (long) destinationOrigin * 16
                + (long) motionVectorEighthPel * (16 / planeDenominator);
        long scaledPosition = originalPosition * scaleFactor + (long) (scaleFactor - (1 << 14)) * 8;
        int blockStart = expectedSignedRoundShift(scaledPosition, 8) + 32;
        int step = (scaleFactor + 8) >> 4;
        return Math.toIntExact((long) blockStart + (long) sampleOffset * step);
    }

    /// Returns the expected Q14 reference scale factor.
    ///
    /// @param referenceExtent the stored reference extent
    /// @param currentExtent the current coded-frame extent
    /// @return the expected Q14 scale factor
    private static int expectedReferenceScaleFactor(int referenceExtent, int currentExtent) {
        return Math.toIntExact((((long) referenceExtent << 14) + (currentExtent >> 1)) / currentExtent);
    }

    /// Rounds one signed test-oracle value by an arithmetic right shift.
    ///
    /// @param value the signed value
    /// @param bits the number of low bits to discard
    /// @return the symmetrically rounded value
    private static int expectedSignedRoundShift(long value, int bits) {
        long offset = 1L << (bits - 1);
        return value >= 0
                ? Math.toIntExact((value + offset) >> bits)
                : Math.toIntExact(-(((-value) + offset) >> bits));
    }

    /// Returns the expected horizontally upscaled row produced by the normative super-resolution helper.
    ///
    /// @param sourceRow the coded-width source row
    /// @param targetWidth the upscaled row width
    /// @return the expected horizontally upscaled row
    private static int[] expectedHorizontallyUpscaledRow(int[] sourceRow, int targetWidth) {
        int processingAlignment = targetWidth <= 4 ? 4 : 8;
        int processingWidth = (sourceRow.length + processingAlignment - 1) & -processingAlignment;
        return SuperResolutionOracle.upscaleRow(
                Arrays.copyOf(sourceRow, processingWidth),
                sourceRow.length,
                targetWidth,
                8
        );
    }

    /// Returns one coded-domain plane predicted from a differently sized stored reference with
    /// AV1 scaled bilinear interpolation.
    ///
    /// @param referencePlane the stored reference plane in the post-super-resolution domain
    /// @param codedWidth the coded-domain destination width
    /// @param codedHeight the coded-domain destination height
    /// @param sourceOffsetEighthPelX the horizontal motion-vector offset in luma eighth-pel units
    /// @param sourceOffsetEighthPelY the vertical motion-vector offset in luma eighth-pel units
    /// @param denominatorX the plane-local horizontal denominator
    /// @param denominatorY the plane-local vertical denominator
    /// @return the predicted coded-domain plane raster
    private static int[][] expectedScaledInterPlaneBilinearly(
            PaddedPlane referencePlane,
            int codedWidth,
            int codedHeight,
            int sourceOffsetEighthPelX,
            int sourceOffsetEighthPelY,
            int denominatorX,
            int denominatorY
    ) {
        int[][] predicted = new int[codedHeight][codedWidth];
        boolean scaled = referencePlane.width() != codedWidth || referencePlane.height() != codedHeight;
        int horizontalScale = expectedReferenceScaleFactor(referencePlane.width(), codedWidth);
        int verticalScale = expectedReferenceScaleFactor(referencePlane.height(), codedHeight);
        for (int y = 0; y < codedHeight; y++) {
            for (int x = 0; x < codedWidth; x++) {
                int sourceNumeratorX = scaled
                        ? expectedScaledReferenceNumerator(
                        0,
                        x,
                        sourceOffsetEighthPelX,
                        denominatorX,
                        horizontalScale
                )
                        : x * denominatorX + sourceOffsetEighthPelX;
                int sourceNumeratorY = scaled
                        ? expectedScaledReferenceNumerator(
                        0,
                        y,
                        sourceOffsetEighthPelY,
                        denominatorY,
                        verticalScale
                )
                        : y * denominatorY + sourceOffsetEighthPelY;
                predicted[y][x] = bilinearInterpolatePlaneAt(
                        referencePlane,
                        sourceNumeratorX,
                        sourceNumeratorY,
                        scaled ? 1 << 10 : denominatorX,
                        scaled ? 1 << 10 : denominatorY
                );
            }
        }
        return predicted;
    }

    /// Bilinearly samples one stored reference plane at the supplied plane-local numerator
    /// position.
    ///
    /// @param referencePlane the immutable stored reference plane
    /// @param sourceNumeratorX the source horizontal numerator in plane-local sample units
    /// @param sourceNumeratorY the source vertical numerator in plane-local sample units
    /// @param denominatorX the horizontal interpolation denominator
    /// @param denominatorY the vertical interpolation denominator
    /// @return the bilinearly interpolated sample value
    private static int bilinearInterpolatePlaneAt(
            PaddedPlane referencePlane,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY
    ) {
        int sourceY0 = Math.floorDiv(sourceNumeratorY, denominatorY);
        int fractionY = Math.multiplyExact(Math.floorMod(sourceNumeratorY, denominatorY), 16) / denominatorY;
        int clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1);
        int clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1);
        int sourceX0 = Math.floorDiv(sourceNumeratorX, denominatorX);
        int fractionX = Math.multiplyExact(Math.floorMod(sourceNumeratorX, denominatorX), 16) / denominatorX;
        int clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1);
        int clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1);
        return bilinearInterpolate(
                referencePlane.sample(clampedSourceX0, clampedSourceY0),
                referencePlane.sample(clampedSourceX1, clampedSourceY0),
                referencePlane.sample(clampedSourceX0, clampedSourceY1),
                referencePlane.sample(clampedSourceX1, clampedSourceY1),
                fractionX,
                16,
                fractionY,
                16
        );
    }

    /// Returns one bilinearly interpolated unsigned sample.
    ///
    /// @param topLeft the top-left source sample
    /// @param topRight the top-right source sample
    /// @param bottomLeft the bottom-left source sample
    /// @param bottomRight the bottom-right source sample
    /// @param fractionX the horizontal source fraction in `[0, denominatorX)`
    /// @param denominatorX the horizontal interpolation denominator
    /// @param fractionY the vertical source fraction in `[0, denominatorY)`
    /// @param denominatorY the vertical interpolation denominator
    /// @return one bilinearly interpolated unsigned sample
    private static int bilinearInterpolate(
            int topLeft,
            int topRight,
            int bottomLeft,
            int bottomRight,
            int fractionX,
            int denominatorX,
            int fractionY,
            int denominatorY
    ) {
        int inverseFractionX = denominatorX - fractionX;
        int inverseFractionY = denominatorY - fractionY;
        long denominator = (long) denominatorX * denominatorY;
        long weightedSum = (long) inverseFractionX * inverseFractionY * topLeft
                + (long) fractionX * inverseFractionY * topRight
                + (long) inverseFractionX * fractionY * bottomLeft
                + (long) fractionX * fractionY * bottomRight;
        return (int) ((weightedSum + (denominator >> 1)) / denominator);
    }

    /// Clamps one integer into the inclusive requested bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Runs the frame-level stages that follow coded-domain reconstruction.
    ///
    /// @param reconstructedPlanes the reconstructed coded-domain planes
    /// @param frameHeader the frame header that owns the planes
    /// @return the postprocessed planes
    private static DecodedSurface postprocess(DecodedSurface reconstructedPlanes, FrameHeader frameHeader) {
        return FramePostprocessor.postprocess(reconstructedPlanes, frameHeader);
    }

    /// Returns one guaranteed-present decoded plane after a non-null assertion.
    ///
    /// @param plane the decoded plane reference, or `null`
    /// @return the same decoded plane reference after a non-null assertion
    private static PaddedPlane requirePlane(@Nullable PaddedPlane plane) {
        assertNotNull(plane);
        return plane;
    }

    /// Returns the stored coefficient-context byte for one non-zero DC coefficient.
    ///
    /// @param signedDcCoefficient the signed DC coefficient
    /// @return the stored coefficient-context byte for one non-zero DC coefficient
    private static int expectedNonZeroCoefficientContextByte(int signedDcCoefficient) {
        return Math.min(Math.abs(signedDcCoefficient), 63) | (signedDcCoefficient > 0 ? 0x80 : 0);
    }

    /// Creates default per-segment data with all optional features disabled.
    ///
    /// @return default per-segment data with all optional features disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
