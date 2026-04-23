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
import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.glavo.avif.internal.av1.parse.FrameHeaderParser;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.parse.TileBitstreamParser;
import org.glavo.avif.internal.av1.parse.TileGroupHeaderParser;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.glavo.avif.testutil.HexFixtureResources;
import org.glavo.avif.testutil.InterPredictionOracle;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for the first-pixel `FrameReconstructor` path.
@NotNullByDefault
final class FrameReconstructorIntegrationTest {
    /// One fixed single-tile payload that stays inside the current first-pixel reconstruction subset.
    private static final byte[] SUPPORTED_SINGLE_TILE_PAYLOAD = new byte[]{
            (byte) 0x98, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /// One deterministic real tile payload whose first visible `8x8` leaf decodes both luma and
    /// chroma palette syntax.
    private static final byte[] BITSTREAM_DERIVED_PALETTE_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/palette-block.hex");

    /// One deterministic real inter tile payload whose first block uses one single reference and a
    /// zero motion vector.
    private static final byte[] BITSTREAM_DERIVED_INTER_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/all-zero-8.hex");

    /// The generated named-fixture resource backing deterministic frame-reconstructor integration payloads.
    private static final String FRAME_RECONSTRUCTOR_FIXTURE_RESOURCE_PATH =
            "av1/fixtures/generated/frame-reconstructor-integration-fixtures.txt";

    /// The top-left `8x8` luma block produced by the current legacy directional still-picture fixture.
    private static final int[][] LEGACY_DIRECTIONAL_LUMA_TOP_LEFT_8X8 = {
            {127, 128, 128, 128, 128, 128, 128, 128},
            {126, 126, 126, 126, 128, 128, 128, 128},
            {128, 128, 127, 126, 129, 129, 129, 129},
            {130, 132, 134, 134, 128, 128, 128, 128},
            {134, 128, 128, 128, 128, 128, 128, 128},
            {132, 128, 129, 129, 128, 128, 128, 128},
            {132, 130, 127, 129, 128, 128, 128, 128},
            {132, 132, 128, 131, 128, 128, 128, 129}
    };

    /// Verifies that one monochrome all-zero intra leaf reconstructs to midpoint DC samples.
    @Test
    void reconstructsMonochromeDcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, createLeaf(true, false));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one `I420` all-zero intra leaf reconstructs midpoint chroma as well as luma.
    @Test
    void reconstructsI420DcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I420, createLeaf(true, true));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(3, 3));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(3, 3));
    }

    /// Verifies that one synthetic monochrome luma-palette leaf now reconstructs through the
    /// frame-syntax integration path instead of tripping a palette guard.
    @Test
    void reconstructsSyntheticMonochromeLumaPaletteLeafDuringFrameReconstruction() {
        TilePartitionTreeReader.LeafNode leafNode = createMonochromeLeafWithLumaPalette();
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(
                createPaletteEnabledAssembly(PixelFormat.I400),
                leafNode
        );

        assertEquals(2, leafNode.header().yPaletteSize());
        assertEquals(0, leafNode.header().uvPaletteSize());
        assertEquals(32, leafNode.header().yPaletteIndices().length);

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertPlaneEquals(
                decodedPlanes.lumaPlane(),
                new int[][]{
                        {32, 224, 32, 224, 32, 224, 32, 224},
                        {224, 32, 224, 32, 224, 32, 224, 32},
                        {32, 224, 32, 224, 32, 224, 32, 224},
                        {224, 32, 224, 32, 224, 32, 224, 32},
                        {32, 224, 32, 224, 32, 224, 32, 224},
                        {224, 32, 224, 32, 224, 32, 224, 32},
                        {32, 224, 32, 224, 32, 224, 32, 224},
                        {224, 32, 224, 32, 224, 32, 224, 32}
                }
        );
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one synthetic `I420` chroma-palette leaf now reconstructs through the
    /// frame-syntax integration path and populates both chroma planes.
    @Test
    void reconstructsSyntheticI420ChromaPaletteLeafDuringFrameReconstruction() {
        TilePartitionTreeReader.LeafNode leafNode = createI420LeafWithChromaPalette();
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(
                createPaletteEnabledAssembly(PixelFormat.I420),
                leafNode
        );

        assertTrue(leafNode.header().hasChroma());
        assertEquals(0, leafNode.header().yPaletteSize());
        assertEquals(2, leafNode.header().uvPaletteSize());
        assertEquals(8, leafNode.header().uvPaletteIndices().length);

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertPlaneFilled(decodedPlanes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                new int[][]{
                        {64, 192, 64, 192},
                        {192, 64, 192, 64},
                        {64, 192, 64, 192},
                        {192, 64, 192, 64}
                }
        );
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                new int[][]{
                        {96, 160, 96, 160},
                        {160, 96, 160, 96},
                        {96, 160, 96, 160},
                        {160, 96, 160, 96}
                }
        );
    }

    /// Verifies that one synthetic `I422` chroma-palette leaf now reconstructs through the
    /// frame-syntax integration path and populates both full-height chroma planes.
    @Test
    void reconstructsSyntheticI422ChromaPaletteLeafDuringFrameReconstruction() {
        int[] chromaPaletteU = new int[]{48, 176};
        int[] chromaPaletteV = new int[]{208, 80};
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 0, 1},
                {1, 0, 1, 0},
                {0, 1, 1, 0},
                {1, 0, 0, 1},
                {0, 0, 1, 1},
                {1, 1, 0, 0},
                {0, 1, 0, 1},
                {1, 0, 1, 0}
        };
        TilePartitionTreeReader.LeafNode leafNode = createWideChromaLeafWithChromaPalette(
                PixelFormat.I422,
                chromaPaletteU,
                chromaPaletteV,
                chromaPaletteIndices
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(
                createPaletteEnabledAssembly(PixelFormat.I422),
                leafNode
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertPlaneFilled(decodedPlanes.lumaPlane(), 8, 8, 128);
        byte[] packedIndices = packPaletteIndices(chromaPaletteIndices);
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                expandPackedPaletteSamples(chromaPaletteU, packedIndices, 4, 8, 4)
        );
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                expandPackedPaletteSamples(chromaPaletteV, packedIndices, 4, 8, 4)
        );
    }

    /// Verifies that one synthetic `I444` chroma-palette leaf now reconstructs through the
    /// frame-syntax integration path and populates both full-resolution chroma planes.
    @Test
    void reconstructsSyntheticI444ChromaPaletteLeafDuringFrameReconstruction() {
        int[] chromaPaletteU = new int[]{24, 120};
        int[] chromaPaletteV = new int[]{216, 72};
        int[][] chromaPaletteIndices = new int[][]{
                {0, 1, 0, 1, 0, 1, 0, 1},
                {1, 0, 1, 0, 1, 0, 1, 0},
                {0, 1, 1, 0, 0, 1, 1, 0},
                {1, 0, 0, 1, 1, 0, 0, 1},
                {0, 0, 1, 1, 0, 0, 1, 1},
                {1, 1, 0, 0, 1, 1, 0, 0},
                {0, 1, 0, 1, 1, 0, 1, 0},
                {1, 0, 1, 0, 0, 1, 0, 1}
        };
        TilePartitionTreeReader.LeafNode leafNode = createWideChromaLeafWithChromaPalette(
                PixelFormat.I444,
                chromaPaletteU,
                chromaPaletteV,
                chromaPaletteIndices
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(
                createPaletteEnabledAssembly(PixelFormat.I444),
                leafNode
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertPlaneFilled(decodedPlanes.lumaPlane(), 8, 8, 128);
        byte[] packedIndices = packPaletteIndices(chromaPaletteIndices);
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                expandPackedPaletteSamples(chromaPaletteU, packedIndices, 8, 8, 8)
        );
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                expandPackedPaletteSamples(chromaPaletteV, packedIndices, 8, 8, 8)
        );
    }

    /// Verifies that one deterministic real tile payload decodes both luma and chroma palette
    /// syntax, then reconstructs exactly the palette-mapped samples exposed by the decoded block header.
    @Test
    void reconstructsBitstreamDerivedI420PaletteLeafDuringFrameReconstruction() {
        assertBitstreamDerivedPaletteLeafReconstructsExactly(PixelFormat.I420);
    }

    /// Verifies that the same deterministic real tile payload also reconstructs exactly under one
    /// wider-chroma `I422` sequence header.
    @Test
    void reconstructsBitstreamDerivedI422PaletteLeafDuringFrameReconstruction() {
        assertBitstreamDerivedPaletteLeafReconstructsExactly(PixelFormat.I422);
    }

    /// Verifies that the same deterministic real tile payload also reconstructs exactly under one
    /// wider-chroma `I444` sequence header.
    @Test
    void reconstructsBitstreamDerivedI444PaletteLeafDuringFrameReconstruction() {
        assertBitstreamDerivedPaletteLeafReconstructsExactly(PixelFormat.I444);
    }

    /// Verifies that one deterministic real inter tile payload now reconstructs from one stored
    /// `I420` reference surface instead of stopping at the old inter boundary.
    @Test
    void reconstructsBitstreamDerivedI420InterLeafFromStoredReferenceSurface() {
        FrameAssembly assembly = createInterAssembly(PixelFormat.I420, BITSTREAM_DERIVED_INTER_PAYLOAD, 64, 64, 0);
        FrameSyntaxDecodeResult syntaxDecodeResult = new FrameSyntaxDecoder(null).decode(assembly);
        TilePartitionTreeReader.LeafNode leafNode = firstLeaf(syntaxDecodeResult.tileRoots(0));

        assertFalse(leafNode.header().skip());
        assertFalse(leafNode.header().intra());
        assertFalse(leafNode.header().compoundReference());
        assertEquals(0, leafNode.header().referenceFrame0());

        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                leafNode.header(),
                leafNode.transformLayout(),
                createResidualLayout(
                        leafNode.header().position(),
                        leafNode.header().size(),
                        copyResidualUnitsAsAllZero(leafNode.residualLayout().lumaUnits()),
                        copyResidualUnitsAsAllZero(leafNode.residualLayout().chromaUUnits()),
                        copyResidualUnitsAsAllZero(leafNode.residualLayout().chromaVUnits())
                )
        );
        FrameSyntaxDecodeResult zeroResidualSyntaxDecodeResult = new FrameSyntaxDecodeResult(
                syntaxDecodeResult.assembly(),
                new TilePartitionTreeReader.Node[][]{{zeroResidualLeaf}},
                syntaxDecodeResult.decodedTemporalMotionFields(),
                syntaxDecodeResult.finalTileCdfContexts()
        );

        ReferenceSurfaceSnapshot baselineReferenceSurfaceSnapshot = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                64,
                64,
                createGradientPlane(64, 64, 17, 1, 3),
                createGradientPlane(32, 32, 101, 2, 5),
                createGradientPlane(32, 32, 149, 3, 4)
        );
        ReferenceSurfaceSnapshot offsetReferenceSurfaceSnapshot = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                64,
                64,
                createGradientPlane(64, 64, 34, 1, 3),
                createGradientPlane(32, 32, 110, 2, 5),
                createGradientPlane(32, 32, 136, 3, 4)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baselineDecodedPlanes = reconstructor.reconstruct(
                zeroResidualSyntaxDecodeResult,
                createReferenceSurfaceSlots(0, baselineReferenceSurfaceSnapshot)
        );
        DecodedPlanes offsetDecodedPlanes = reconstructor.reconstruct(
                zeroResidualSyntaxDecodeResult,
                createReferenceSurfaceSlots(0, offsetReferenceSurfaceSnapshot)
        );

        DecodedPlane baselineLumaPlane = baselineDecodedPlanes.lumaPlane();
        DecodedPlane offsetLumaPlane = offsetDecodedPlanes.lumaPlane();
        DecodedPlane baselineChromaUPlane = requirePlane(baselineDecodedPlanes.chromaUPlane());
        DecodedPlane offsetChromaUPlane = requirePlane(offsetDecodedPlanes.chromaUPlane());
        DecodedPlane baselineChromaVPlane = requirePlane(baselineDecodedPlanes.chromaVPlane());
        DecodedPlane offsetChromaVPlane = requirePlane(offsetDecodedPlanes.chromaVPlane());

        assertTrue(planeDiffers(baselineLumaPlane, offsetLumaPlane));
        assertTrue(planeDiffers(baselineChromaUPlane, offsetChromaUPlane));
        assertTrue(planeDiffers(baselineChromaVPlane, offsetChromaVPlane));
        assertTrue(offsetLumaPlane.sample(0, 0) > baselineLumaPlane.sample(0, 0));
        assertTrue(offsetLumaPlane.sample(31, 31) > baselineLumaPlane.sample(31, 31));
        assertTrue(offsetChromaUPlane.sample(0, 0) > baselineChromaUPlane.sample(0, 0));
        assertTrue(offsetChromaUPlane.sample(15, 15) > baselineChromaUPlane.sample(15, 15));
        assertTrue(offsetChromaVPlane.sample(0, 0) < baselineChromaVPlane.sample(0, 0));
        assertTrue(offsetChromaVPlane.sample(15, 15) < baselineChromaVPlane.sample(15, 15));
    }

    /// Verifies that the same deterministic real inter tile payload reconstructs through one
    /// single-reference `BILINEAR` subpel path against one stored `I420` reference surface.
    @Test
    void reconstructsBitstreamDerivedI420InterLeafWithBilinearSubpelPredictionFromStoredReferenceSurface() {
        assertBitstreamDerivedI420InterLeafWithSubpelPrediction(
                new MotionVector(2, 2),
                FrameHeader.InterpolationFilter.BILINEAR
        );
    }

    /// Verifies that the same deterministic real inter tile payload reconstructs through one
    /// single-reference fixed `EIGHT_TAP_REGULAR` subpel path against one stored `I420` reference
    /// surface.
    @Test
    void reconstructsBitstreamDerivedI420InterLeafWithRegularEightTapSubpelPredictionFromStoredReferenceSurface() {
        assertBitstreamDerivedI420InterLeafWithSubpelPrediction(
                new MotionVector(3, 1),
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Verifies that the same deterministic real inter tile payload reconstructs through one
    /// single-reference fixed `EIGHT_TAP_SMOOTH` subpel path against one stored `I420` reference
    /// surface.
    @Test
    void reconstructsBitstreamDerivedI420InterLeafWithSmoothEightTapSubpelPredictionFromStoredReferenceSurface() {
        assertBitstreamDerivedI420InterLeafWithSubpelPrediction(
                new MotionVector(2, 6),
                FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH
        );
    }

    /// Verifies that one synthetic `I420` single-reference inter leaf reconstructs through one
    /// switchable frame header by using the caller-supplied horizontal and vertical fixed filters.
    @Test
    void reconstructsSyntheticI420SingleReferenceInterLeafWithSwitchableDirectionalSubpelFilters() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(3, 1);
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
        FrameHeader.InterpolationFilter verticalInterpolationFilter = FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
        TransformSize lumaTransformSize = blockSize.maxLumaTransformSize();
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
                false,
                false,
                false,
                false,
                false,
                0,
                -1,
                SingleInterPredictionMode.NEARESTMV,
                null,
                0,
                InterMotionVector.resolved(motionVector),
                null,
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
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
        TilePartitionTreeReader.LeafNode leafNode = new TilePartitionTreeReader.LeafNode(
                header,
                new TransformLayout(
                        position,
                        blockSize,
                        blockSize.width4(),
                        blockSize.height4(),
                        lumaTransformSize,
                        blockSize.maxChromaTransformSize(PixelFormat.I420),
                        false,
                        new TransformUnit[]{new TransformUnit(position, lumaTransformSize)}
                ),
                createResidualLayout(position, blockSize, new TransformResidualUnit[]{createAllZeroResidualUnit(position, lumaTransformSize)})
        );
        FrameAssembly assembly = createInterAssembly(
                PixelFormat.I420,
                new byte[0],
                8,
                8,
                0,
                FrameHeader.InterpolationFilter.SWITCHABLE
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(assembly, leafNode);

        DecodedPlane referenceLumaPlane = createGradientPlane(8, 8, 17, 5, 9);
        DecodedPlane referenceChromaUPlane = createGradientPlane(4, 4, 41, 7, 11);
        DecodedPlane referenceChromaVPlane = createGradientPlane(4, 4, 83, 13, 3);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                8,
                8,
                referenceLumaPlane,
                referenceChromaUPlane,
                referenceChromaVPlane
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(
                syntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(FrameHeader.InterpolationFilter.SWITCHABLE, assembly.frameHeader().subpelFilterMode());
        assertEquals(horizontalInterpolationFilter, header.horizontalInterpolationFilter());
        assertEquals(verticalInterpolationFilter, header.verticalInterpolationFilter());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertTrue(decodedPlanes.hasChroma());
        assertPlaneBlockEquals(
                decodedPlanes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceLumaPlane,
                        4,
                        4,
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        4,
                        4,
                        4,
                        4,
                        horizontalInterpolationFilter,
                        verticalInterpolationFilter
                )
        );
        assertPlaneBlockEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceChromaUPlane,
                        2,
                        2,
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        8,
                        8,
                        2,
                        2,
                        horizontalInterpolationFilter,
                        verticalInterpolationFilter
                )
        );
        assertPlaneBlockEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                0,
                0,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceChromaVPlane,
                        2,
                        2,
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        8,
                        8,
                        2,
                        2,
                        horizontalInterpolationFilter,
                        verticalInterpolationFilter
                )
        );
    }

    /// Verifies that the same deterministic real inter tile payload now keeps its decoded residuals
    /// through reconstruction instead of requiring zero-residual normalization.
    @Test
    void reconstructsBitstreamDerivedI420InterLeafWithRealResidualOverlayFromStoredReferenceSurface() {
        FrameAssembly assembly = createInterAssembly(PixelFormat.I420, BITSTREAM_DERIVED_INTER_PAYLOAD, 64, 64, 0);
        FrameSyntaxDecodeResult syntaxDecodeResult = new FrameSyntaxDecoder(null).decode(assembly);
        TilePartitionTreeReader.LeafNode decodedLeaf = firstLeaf(syntaxDecodeResult.tileRoots(0));

        assertFalse(allResidualUnitsZero(decodedLeaf.residualLayout()));

        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                decodedLeaf.header(),
                decodedLeaf.transformLayout(),
                createResidualLayout(
                        decodedLeaf.header().position(),
                        decodedLeaf.header().size(),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().lumaUnits()),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().chromaUUnits()),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().chromaVUnits())
                )
        );
        FrameSyntaxDecodeResult zeroResidualSyntaxDecodeResult = new FrameSyntaxDecodeResult(
                syntaxDecodeResult.assembly(),
                new TilePartitionTreeReader.Node[][]{{zeroResidualLeaf}},
                syntaxDecodeResult.decodedTemporalMotionFields(),
                syntaxDecodeResult.finalTileCdfContexts()
        );

        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                64,
                64,
                createGradientPlane(64, 64, 23, 2, 3),
                createGradientPlane(32, 32, 87, 1, 5),
                createGradientPlane(32, 32, 163, 3, 2)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baselineDecodedPlanes = reconstructor.reconstruct(
                zeroResidualSyntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );
        DecodedPlanes residualDecodedPlanes = reconstructor.reconstruct(
                syntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        assertEquals(baselineDecodedPlanes.codedWidth(), residualDecodedPlanes.codedWidth());
        assertEquals(baselineDecodedPlanes.codedHeight(), residualDecodedPlanes.codedHeight());
        assertEquals(PixelFormat.I420, residualDecodedPlanes.pixelFormat());
        assertTrue(residualDecodedPlanes.hasChroma());
    }

    /// Verifies that one synthetic monochrome compound-reference inter leaf averages two stored
    /// reference surfaces through the frame-reconstructor integration path.
    @Test
    void reconstructsSyntheticI400CompoundInterLeafFromStoredReferenceSurfaces() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_4X4;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                false,
                false,
                false,
                false,
                false,
                true,
                0,
                1,
                null,
                CompoundInterPredictionMode.NEARESTMV_NEARESTMV,
                0,
                InterMotionVector.resolved(MotionVector.zero()),
                InterMotionVector.resolved(MotionVector.zero()),
                false,
                0,
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
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                1,
                1,
                TransformSize.TX_4X4,
                null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_4X4)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_4X4)}
        );
        TilePartitionTreeReader.LeafNode leafNode = new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
        FrameAssembly assembly = createInterAssembly(PixelFormat.I400, new byte[0], 4, 4, 0, 1);
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(assembly, leafNode);

        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I400,
                4,
                4,
                createGradientPlane(4, 4, 0, 10, 20),
                null,
                null
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I400,
                4,
                4,
                createGradientPlane(4, 4, 100, 10, 20),
                null,
                null
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(
                syntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertFalse(decodedPlanes.hasChroma());
        assertPlaneEquals(decodedPlanes.lumaPlane(), new int[][]{
                {50, 60, 70, 80},
                {70, 80, 90, 100},
                {90, 100, 110, 120},
                {110, 120, 130, 140}
        });
    }

    /// Verifies that one synthetic `I420` compound-reference inter leaf reconstructs through one
    /// switchable frame header by averaging two dual-filter subpel predictions.
    @Test
    void reconstructsSyntheticI420CompoundInterLeafWithSwitchableDirectionalSubpelFilters() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_4X4;
        MotionVector motionVector0 = new MotionVector(3, 1);
        MotionVector motionVector1 = new MotionVector(1, 3);
        FrameHeader.InterpolationFilter horizontalInterpolationFilter = FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH;
        FrameHeader.InterpolationFilter verticalInterpolationFilter = FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
        TransformSize lumaTransformSize = blockSize.maxLumaTransformSize();
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
                false,
                false,
                false,
                false,
                true,
                0,
                1,
                null,
                CompoundInterPredictionMode.NEARESTMV_NEARESTMV,
                0,
                InterMotionVector.resolved(motionVector0),
                InterMotionVector.resolved(motionVector1),
                horizontalInterpolationFilter,
                verticalInterpolationFilter,
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
        TilePartitionTreeReader.LeafNode leafNode = new TilePartitionTreeReader.LeafNode(
                header,
                new TransformLayout(
                        position,
                        blockSize,
                        blockSize.width4(),
                        blockSize.height4(),
                        lumaTransformSize,
                        blockSize.maxChromaTransformSize(PixelFormat.I420),
                        false,
                        new TransformUnit[]{new TransformUnit(position, lumaTransformSize)}
                ),
                createResidualLayout(position, blockSize, new TransformResidualUnit[]{createAllZeroResidualUnit(position, lumaTransformSize)})
        );
        FrameAssembly assembly = createInterAssembly(
                PixelFormat.I420,
                new byte[0],
                8,
                8,
                0,
                1,
                FrameHeader.InterpolationFilter.SWITCHABLE
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(assembly, leafNode);

        DecodedPlane referenceLumaPlane0 = createGradientPlane(8, 8, 12, 6, 9);
        DecodedPlane referenceChromaUPlane0 = createGradientPlane(4, 4, 31, 8, 5);
        DecodedPlane referenceChromaVPlane0 = createGradientPlane(4, 4, 77, 4, 12);
        DecodedPlane referenceLumaPlane1 = createGradientPlane(8, 8, 196, -7, -9);
        DecodedPlane referenceChromaUPlane1 = createGradientPlane(4, 4, 184, -11, -5);
        DecodedPlane referenceChromaVPlane1 = createGradientPlane(4, 4, 221, -9, -7);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                8,
                8,
                referenceLumaPlane0,
                referenceChromaUPlane0,
                referenceChromaVPlane0
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                8,
                8,
                referenceLumaPlane1,
                referenceChromaUPlane1,
                referenceChromaVPlane1
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(
                syntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot0, 1, referenceSurfaceSnapshot1)
        );

        assertEquals(FrameHeader.InterpolationFilter.SWITCHABLE, assembly.frameHeader().subpelFilterMode());
        assertEquals(horizontalInterpolationFilter, header.horizontalInterpolationFilter());
        assertEquals(verticalInterpolationFilter, header.verticalInterpolationFilter());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertTrue(decodedPlanes.hasChroma());
        assertPlaneBlockEquals(
                decodedPlanes.lumaPlane(),
                0,
                0,
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceLumaPlane0,
                                4,
                                4,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                4,
                                4,
                                4,
                                4,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceLumaPlane1,
                                4,
                                4,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                4,
                                4,
                                4,
                                4,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        )
                )
        );
        assertPlaneBlockEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                0,
                0,
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceChromaUPlane0,
                                2,
                                2,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceChromaUPlane1,
                                2,
                                2,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        )
                )
        );
        assertPlaneBlockEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                0,
                0,
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceChromaVPlane0,
                                2,
                                2,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                referenceChromaVPlane1,
                                2,
                                2,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                horizontalInterpolationFilter,
                                verticalInterpolationFilter
                        )
                )
        );
    }

    /// Verifies that one synthetic `I420` leaf with only a chroma-U DC residual changes only the U plane.
    @Test
    void reconstructsSyntheticI420LeafWithChromaUResidualOnly() {
        FrameSyntaxDecodeResult baselineSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, 0)
        );
        FrameSyntaxDecodeResult residualSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes residual = reconstructor.reconstruct(residualSyntax);

        assertPlanesEqual(baseline.lumaPlane(), residual.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residual.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residual.chromaVPlane()));
    }

    /// Verifies that one synthetic `I420` leaf with only a chroma-V DC residual changes only the V plane.
    @Test
    void reconstructsSyntheticI420LeafWithChromaVResidualOnly() {
        FrameSyntaxDecodeResult baselineSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, 0)
        );
        FrameSyntaxDecodeResult residualSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes residual = reconstructor.reconstruct(residualSyntax);

        assertPlanesEqual(baseline.lumaPlane(), residual.lumaPlane());
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residual.chromaUPlane()));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residual.chromaVPlane()),
                -1
        );
    }

    /// Verifies that one bitstream-derived clipped `I420` chroma residual survives the integration
    /// path and updates only the visible chroma footprint.
    @Test
    void reconstructsBitstreamDerivedClippedI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        int codedWidth = 4;
        int codedHeight = 8;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.LARGEST;
        byte[] payload = findPayloadForBitstreamDerivedI420ChromaResidual(
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf =
                decodeI420LeafFromPayload(payload, position, blockSize, codedWidth, codedHeight, transformMode);

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(1, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(2, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.TX_4X4, decodedLeaf.transformLayout().chromaTransformSize());
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
    }

    /// Verifies that one bitstream-derived clipped `I422` chroma residual survives the integration
    /// path and updates only the visible wider-chroma footprint.
    @Test
    void reconstructsBitstreamDerivedClippedI422ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        int codedWidth = 7;
        int codedHeight = 5;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.LARGEST;
        byte[] payload = findPayloadForBitstreamDerivedChromaResidual(
                PixelFormat.I422,
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf = decodeChromaLeafFromPayload(
                PixelFormat.I422,
                payload,
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(2, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(2, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.RTX_4X8, decodedLeaf.transformLayout().chromaTransformSize());
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I422, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
    }

    /// Verifies that one bitstream-derived fringe `I420` chroma residual survives the integration
    /// path and updates only the visible chroma footprint.
    @Test
    void reconstructsBitstreamDerivedFringeI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(1, 1);
        BlockSize blockSize = BlockSize.SIZE_4X4;
        int codedWidth = 12;
        int codedHeight = 12;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY;
        byte[] payload = findPayloadForBitstreamDerivedI420ChromaResidual(
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf =
                decodeI420LeafFromPayload(payload, position, blockSize, codedWidth, codedHeight, transformMode);

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(1, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(1, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.TX_4X4, decodedLeaf.transformLayout().chromaTransformSize());
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
    }

    /// Verifies that one bitstream-derived larger-transform `I420` chroma residual survives the
    /// integration path and updates only the decoded larger chroma-transform footprint.
    @Test
    void reconstructsBitstreamDerivedLargerTransformI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_16X16;
        int codedWidth = 16;
        int codedHeight = 16;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.LARGEST;
        byte[] payload = findPayloadForBitstreamDerivedLargerTransformI420ChromaResidual(
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf =
                decodeI420LeafFromPayload(payload, position, blockSize, codedWidth, codedHeight, transformMode);

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(4, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(4, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.TX_8X8, decodedLeaf.transformLayout().chromaTransformSize());
        assertTrue(
                hasMultiCoefficientResidual(decodedLeaf.residualLayout().chromaUUnits())
                        || hasMultiCoefficientResidual(decodedLeaf.residualLayout().chromaVUnits())
        );
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
    }

    /// Verifies that one monochrome all-zero `filter_intra` leaf reconstructs through the minimal luma path.
    @Test
    void reconstructsMonochromeFilterIntraDcLeaf() {
        TilePartitionTreeReader.LeafNode leaf = createLeaf(true, false, FilterIntraMode.DC);
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, leaf, true);
        assertEquals(FilterIntraMode.DC, leaf.header().filterIntraMode());

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(198, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one decoded 4x4 monochrome DC residual survives the frame-syntax path and shifts luma uniformly.
    @Test
    void reconstructsMonochromeDcResidualDecodedFromFrameSyntax() {
        byte[] zeroResidualPayload = findPayloadForResidualFlags(BlockSize.SIZE_4X4, new boolean[]{true});
        byte[] dcResidualPayload = findPayloadForDcOnlyResidual(BlockSize.SIZE_4X4);

        FrameSyntaxDecodeResult baselineSyntax = decodeMonochromeFourByFourFrame(zeroResidualPayload);
        FrameSyntaxDecodeResult residualSyntax = decodeMonochromeFourByFourFrame(dcResidualPayload);
        TilePartitionTreeReader.LeafNode decodedLeaf = firstLeaf(residualSyntax.tileRoots(0));
        TransformResidualUnit residualUnit = decodedLeaf.residualLayout().lumaUnits()[0];

        assertFalse(residualUnit.allZero());
        assertEquals(0, residualUnit.endOfBlockIndex());
        assertTrue(residualUnit.dcCoefficient() != 0);

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baselineLuma = reconstructor.reconstruct(baselineSyntax).lumaPlane();
        DecodedPlanes reconstructed = reconstructor.reconstruct(residualSyntax);

        assertEquals(baselineLuma.width(), reconstructed.lumaPlane().width());
        assertEquals(baselineLuma.height(), reconstructed.lumaPlane().height());
        assertNull(reconstructed.chromaUPlane());
        assertNull(reconstructed.chromaVPlane());
    }

    /// Verifies that one structurally decoded legacy combined still-picture leaf can carry one
    /// injected chroma-U residual through reconstruction without perturbing luma or the V plane.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalCombinedFixtureWithInjectedChromaUResidualOnDecodedLeaf() throws IOException {
        assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(singleTileGroupPayload()),
                64,
                0
        );
    }

    /// Verifies that one structurally decoded legacy standalone still-picture leaf can carry one
    /// injected chroma-V residual through reconstruction without perturbing luma or the U plane.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalStandaloneFixtureWithInjectedChromaVResidualOnDecodedLeaf() throws IOException {
        assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
                decodeReducedStillPictureSyntaxResultFromStandaloneObus(singleTileGroupPayload()),
                0,
                -64
        );
    }

    /// Verifies that the supported reduced still-picture combined fixture still reconstructs one
    /// opaque gray `I420` frame after real structural frame decode.
    ///
    /// @throws IOException if the synthetic fixture cannot be parsed
    @Test
    void reconstructsSupportedReducedStillPictureCombinedFixtureFromRealFrameSyntax() throws IOException {
        FrameSyntaxDecodeResult syntaxDecodeResult =
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(SUPPORTED_SINGLE_TILE_PAYLOAD);

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertStillPicturePlanesFilledWith(decodedPlanes, 128);
    }

    /// Verifies that one synthetic two-tile `I420` still-picture result reconstructs both tiles
    /// into the same output planes through the public frame-syntax decode contract.
    @Test
    void reconstructsSyntheticI420StillPictureAcrossTwoTiles() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticMultiTileResult(
                PixelFormat.I420,
                16,
                8,
                new TilePartitionTreeReader.Node[]{createLeaf(new BlockPosition(0, 0), true, true)},
                new TilePartitionTreeReader.Node[]{createLeaf(new BlockPosition(2, 0), true, true)}
        );

        assertEquals(2, syntaxDecodeResult.tileCount());
        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertStillPicturePlanesFilledWith(decodedPlanes, 128, 16, 8);
    }

    /// Verifies that the legacy reduced still-picture combined fixture now survives structural
    /// frame decode and reconstructs successfully through its first directional luma block.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalCombinedStillPictureFixture() throws IOException {
        assertDirectionalStillPictureFixtureReconstructsSuccessfully(
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(singleTileGroupPayload())
        );
    }

    /// Verifies that the legacy reduced still-picture standalone frame-header plus tile-group
    /// fixture reconstructs successfully through the same directional luma leaf as the combined
    /// path.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalStandaloneStillPictureFixture() throws IOException {
        assertDirectionalStillPictureFixtureReconstructsSuccessfully(
                decodeReducedStillPictureSyntaxResultFromStandaloneObus(singleTileGroupPayload())
        );
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
        return createSyntheticResult(pixelFormat, leafNode, false);
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode,
            boolean filterIntraEnabled
    ) {
        FrameAssembly assembly = createAssembly(pixelFormat, filterIntraEnabled);
        return createSyntheticResult(assembly, leafNode);
    }

    /// Creates one synthetic frame result that reuses the supplied assembly metadata.
    ///
    /// @param assembly the assembly whose headers should be reused
    /// @param leafNode the synthetic partition-tree leaf
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leafNode}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(boolean allZeroResidual, boolean hasChroma) {
        return createLeaf(new BlockPosition(0, 0), allZeroResidual, hasChroma, null);
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @param filterIntraMode the synthetic filter-intra mode, or `null` when filter intra is disabled
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(
            boolean allZeroResidual,
            boolean hasChroma,
            @Nullable FilterIntraMode filterIntraMode
    ) {
        return createLeaf(new BlockPosition(0, 0), allZeroResidual, hasChroma, filterIntraMode);
    }

    /// Creates one synthetic partition-tree leaf at one caller-supplied block position.
    ///
    /// @param position the block origin in 4x4 units
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(
            BlockPosition position,
            boolean allZeroResidual,
            boolean hasChroma
    ) {
        return createLeaf(position, allZeroResidual, hasChroma, null);
    }

    /// Creates one synthetic partition-tree leaf at one caller-supplied block position.
    ///
    /// @param position the block origin in 4x4 units
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @param filterIntraMode the synthetic filter-intra mode, or `null` when filter intra is disabled
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(
            BlockPosition position,
            boolean allZeroResidual,
            boolean hasChroma,
            @Nullable FilterIntraMode filterIntraMode
    ) {
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
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
                LumaIntraPredictionMode.DC,
                hasChroma ? UvIntraPredictionMode.DC : null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                filterIntraMode,
                0,
                0,
                0,
                0
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                hasChroma ? TransformSize.TX_4X4 : null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        TransformResidualUnit lumaResidualUnit = allZeroResidual
                ? createAllZeroResidualUnit(position, TransformSize.TX_8X8)
                : createDcResidualUnit(position, TransformSize.TX_8X8, 1);
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{lumaResidualUnit}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic monochrome `8x8` leaf that carries one minimal two-entry luma
    /// palette color map.
    ///
    /// @return one synthetic monochrome leaf that carries one luma palette
    private static TilePartitionTreeReader.LeafNode createMonochromeLeafWithLumaPalette() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                false,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                LumaIntraPredictionMode.DC,
                null,
                2,
                0,
                new int[]{32, 224},
                new int[0],
                new int[0],
                createPackedCheckerboardPaletteIndices(8, 8),
                new byte[0],
                null,
                0,
                0,
                0,
                0
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic `I420` `8x8` leaf that carries one minimal two-entry chroma palette
    /// color map while leaving luma palette mode disabled.
    ///
    /// @return one synthetic `I420` leaf that carries one chroma palette
    private static TilePartitionTreeReader.LeafNode createI420LeafWithChromaPalette() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                0,
                2,
                new int[0],
                new int[]{64, 192},
                new int[]{96, 160},
                new byte[0],
                createPackedCheckerboardPaletteIndices(4, 4),
                null,
                0,
                0,
                0,
                0
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                TransformSize.TX_4X4,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic `I422` or `I444` `8x8` leaf that carries one minimal chroma palette
    /// color map while leaving luma palette mode disabled.
    ///
    /// @param pixelFormat the requested wide-chroma layout
    /// @param chromaPaletteU the chroma-U palette entries
    /// @param chromaPaletteV the chroma-V palette entries
    /// @param chromaPaletteIndices the unpacked chroma palette map in row-major order
    /// @return one synthetic wide-chroma leaf that carries one chroma palette
    private static TilePartitionTreeReader.LeafNode createWideChromaLeafWithChromaPalette(
            PixelFormat pixelFormat,
            int[] chromaPaletteU,
            int[] chromaPaletteV,
            int[][] chromaPaletteIndices
    ) {
        if (pixelFormat != PixelFormat.I422 && pixelFormat != PixelFormat.I444) {
            throw new IllegalArgumentException("Wide-chroma palette helper expects I422 or I444: " + pixelFormat);
        }
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
                0,
                LumaIntraPredictionMode.DC,
                UvIntraPredictionMode.DC,
                0,
                chromaPaletteU.length,
                new int[0],
                chromaPaletteU,
                chromaPaletteV,
                new byte[0],
                packPaletteIndices(chromaPaletteIndices),
                null,
                0,
                0,
                0,
                0
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                requireChromaTransformSize(blockSize, pixelFormat),
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Packs one checkerboard palette color map using the same two-4-bit-indices-per-byte layout
    /// exposed by decoded block headers.
    ///
    /// @param width the palette-map width in samples
    /// @param height the palette-map height in samples
    /// @return one packed checkerboard palette color map
    private static byte[] createPackedCheckerboardPaletteIndices(int width, int height) {
        byte[] packed = new byte[(width * height + 1) >> 1];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sampleIndex = y * width + x;
                int paletteIndex = (x + y) & 1;
                int byteIndex = sampleIndex >> 1;
                if ((sampleIndex & 1) == 0) {
                    packed[byteIndex] = (byte) paletteIndex;
                } else {
                    packed[byteIndex] = (byte) (packed[byteIndex] | (paletteIndex << 4));
                }
            }
        }
        return packed;
    }

    /// Packs one unpacked palette-index raster using the same two-4-bit-indices-per-byte layout
    /// exposed by decoded block headers.
    ///
    /// @param indices the unpacked palette-index raster in row-major order
    /// @return one packed palette-index raster
    private static byte[] packPaletteIndices(int[][] indices) {
        if (indices.length == 0) {
            return new byte[0];
        }
        int width = indices[0].length;
        if (width == 0 || (width & 1) != 0) {
            throw new IllegalArgumentException("palette index rows must have one non-zero even width");
        }
        byte[] packed = new byte[(width * indices.length) >> 1];
        for (int y = 0; y < indices.length; y++) {
            if (indices[y].length != width) {
                throw new IllegalArgumentException("palette index rows must share the same width");
            }
            for (int x = 0; x < width; x++) {
                int sampleIndex = y * width + x;
                int paletteIndex = indices[y][x];
                if (paletteIndex < 0 || paletteIndex > 0x0F) {
                    throw new IllegalArgumentException("palette index out of nibble range: " + paletteIndex);
                }
                int byteIndex = sampleIndex >> 1;
                if ((sampleIndex & 1) == 0) {
                    packed[byteIndex] = (byte) paletteIndex;
                } else {
                    packed[byteIndex] = (byte) (packed[byteIndex] | (paletteIndex << 4));
                }
            }
        }
        return packed;
    }

    /// Expands one unpacked palette-index raster into decoded sample values by indexing the
    /// supplied palette.
    ///
    /// @param paletteColors the palette entries addressed by the raster
    /// @param indices the unpacked palette-index raster in row-major order
    /// @return one expanded palette sample raster
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

    /// Returns the required synthetic chroma transform size for one wide-chroma palette leaf.
    ///
    /// @param blockSize the coded luma block size
    /// @param pixelFormat the requested chroma layout
    /// @return the required synthetic chroma transform size
    private static TransformSize requireChromaTransformSize(BlockSize blockSize, PixelFormat pixelFormat) {
        @Nullable TransformSize chromaTransformSize = blockSize.maxChromaTransformSize(pixelFormat);
        if (chromaTransformSize == null) {
            throw new IllegalArgumentException("No chroma transform size for " + pixelFormat + " " + blockSize);
        }
        return chromaTransformSize;
    }

    /// Creates one synthetic structural frame-decode result whose tiles are supplied explicitly in
    /// frame order.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param tileRoots the top-level roots for every tile in frame order
    /// @return one synthetic structural frame-decode result with caller-supplied tiles
    private static FrameSyntaxDecodeResult createSyntheticMultiTileResult(
            PixelFormat pixelFormat,
            int width,
            int height,
            TilePartitionTreeReader.Node[]... tileRoots
    ) {
        if (tileRoots.length == 0) {
            throw new IllegalArgumentException("tileRoots.length == 0");
        }
        SequenceHeader sequenceHeader = createSyntheticSequenceHeader(pixelFormat, width, height, false);
        FrameHeader frameHeader = createSyntheticFrameHeader(FrameType.KEY, width, height, tileRoots.length);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        TileBitstream[] tiles = new TileBitstream[tileRoots.length];
        for (int i = 0; i < tileRoots.length; i++) {
            tiles[i] = new TileBitstream(i, new byte[0], 0, 0);
        }
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, tileRoots.length - 1, tileRoots.length),
                0,
                0,
                tiles
        );

        TileDecodeContext.TemporalMotionField[] temporalMotionFields =
                new TileDecodeContext.TemporalMotionField[tileRoots.length];
        for (int i = 0; i < tileRoots.length; i++) {
            temporalMotionFields[i] = new TileDecodeContext.TemporalMotionField(1, 1);
        }
        return new FrameSyntaxDecodeResult(assembly, tileRoots, temporalMotionFields);
    }

    /// Creates one synthetic `I420` leaf whose luma stays at its zero-residual baseline while chroma
    /// DC residuals may perturb the U and V planes independently.
    ///
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for an all-zero U unit
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for an all-zero V unit
    /// @return one synthetic `I420` leaf with caller-supplied chroma residuals
    private static TilePartitionTreeReader.LeafNode createI420LeafWithChromaDcResiduals(
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                false,
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
        );
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                TransformSize.TX_4X4,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)},
                new TransformResidualUnit[]{createOptionalDcResidualUnit(position, TransformSize.TX_4X4, chromaUDcCoefficient)},
                new TransformResidualUnit[]{createOptionalDcResidualUnit(position, TransformSize.TX_4X4, chromaVDcCoefficient)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic residual layout using whichever constructor shape the current checkout exposes.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns the residual layout
    /// @param lumaUnits the luma residual units
    /// @return one synthetic residual layout for the supplied units
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits
    ) {
        return createResidualLayout(position, blockSize, lumaUnits, new TransformResidualUnit[0], new TransformResidualUnit[0]);
    }

    /// Creates one synthetic residual layout using whichever constructor shape the current checkout exposes.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns the residual layout
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the U-plane residual units
    /// @param chromaVUnits the V-plane residual units
    /// @return one synthetic residual layout for the supplied units
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        @Nullable Constructor<?> chromaConstructor = findSplitChromaResidualLayoutConstructor();
        if (chromaConstructor != null) {
            return instantiateResidualLayout(chromaConstructor, position, blockSize, lumaUnits, chromaUUnits, chromaVUnits);
        }

        @Nullable Constructor<?> combinedChromaConstructor = findCombinedChromaResidualLayoutConstructor();
        if (combinedChromaConstructor != null) {
            return instantiateResidualLayout(
                    combinedChromaConstructor,
                    position,
                    blockSize,
                    lumaUnits,
                    (Object) new TransformResidualUnit[][]{chromaUUnits, chromaVUnits}
            );
        }

        assumeTrue(
                chromaUUnits.length == 0 && chromaVUnits.length == 0,
                "Synthetic chroma residual integration coverage is waiting for ResidualLayout chroma-unit support"
        );

        @Nullable Constructor<?> legacyConstructor = findLegacyResidualLayoutConstructor();
        if (legacyConstructor != null) {
            return instantiateResidualLayout(legacyConstructor, position, blockSize, lumaUnits);
        }
        throw new AssertionError("No compatible ResidualLayout constructor was available");
    }

    /// Returns the current split-chroma `ResidualLayout` constructor, or `null`.
    ///
    /// @return the current split-chroma `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findSplitChromaResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 5
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class
                    && parameterTypes[3] == TransformResidualUnit[].class
                    && parameterTypes[4] == TransformResidualUnit[].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Returns the current combined-chroma `ResidualLayout` constructor, or `null`.
    ///
    /// @return the current combined-chroma `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findCombinedChromaResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 4
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class
                    && parameterTypes[3] == TransformResidualUnit[][].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Returns the legacy luma-only `ResidualLayout` constructor, or `null`.
    ///
    /// @return the legacy luma-only `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findLegacyResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 3
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Instantiates one reflected `ResidualLayout` constructor and surfaces failures as test errors.
    ///
    /// @param constructor the reflected constructor to invoke
    /// @param arguments the arguments supplied to the constructor
    /// @return one instantiated residual layout
    private static ResidualLayout instantiateResidualLayout(Constructor<?> constructor, Object... arguments) {
        try {
            return (ResidualLayout) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to instantiate synthetic ResidualLayout", e);
        }
    }

    /// Creates one all-zero transform residual unit.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroResidualUnit(BlockPosition position, TransformSize transformSize) {
        return createAllZeroResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels()
        );
    }

    /// Creates one all-zero transform residual unit with one caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels
    ) {
        return new TransformResidualUnit(
                position,
                transformSize,
                -1,
                new int[transformSize.widthPixels() * transformSize.heightPixels()],
                visibleWidthPixels,
                visibleHeightPixels,
                0
        );
    }

    /// Creates one transform residual unit whose DC coefficient may be zero or non-zero.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero unit
    /// @return one transform residual unit with the requested DC coefficient
    private static TransformResidualUnit createOptionalDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int dcCoefficient
    ) {
        return createOptionalDcResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                dcCoefficient
        );
    }

    /// Creates one transform residual unit whose DC coefficient may be zero or non-zero while
    /// preserving the caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero unit
    /// @return one transform residual unit with the requested DC coefficient
    private static TransformResidualUnit createOptionalDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        return dcCoefficient == 0
                ? createAllZeroResidualUnit(position, transformSize, visibleWidthPixels, visibleHeightPixels)
                : createDcResidualUnit(position, transformSize, visibleWidthPixels, visibleHeightPixels, dcCoefficient);
    }

    /// Creates one non-zero DC-only transform residual unit.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param dcCoefficient the signed non-zero DC coefficient
    /// @return one non-zero DC-only transform residual unit
    private static TransformResidualUnit createDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int dcCoefficient
    ) {
        return createDcResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                dcCoefficient
        );
    }

    /// Creates one non-zero DC-only transform residual unit with one caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed non-zero DC coefficient
    /// @return one non-zero DC-only transform residual unit
    private static TransformResidualUnit createDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[0] = dcCoefficient;
        return new TransformResidualUnit(
                position,
                transformSize,
                0,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                expectedNonZeroCoefficientContextByte(dcCoefficient)
        );
    }

    /// Decodes one tiny 4x4 monochrome frame from a caller-supplied tile payload.
    ///
    /// @param payload the tile payload to decode structurally
    /// @return one tiny 4x4 monochrome frame decoded from the supplied tile payload
    private static FrameSyntaxDecodeResult decodeMonochromeFourByFourFrame(byte[] payload) {
        return new FrameSyntaxDecoder(null).decode(
                createAssembly(PixelFormat.I400, payload, 4, 4, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY)
        );
    }

    /// Asserts that one deterministic real palette payload reconstructs exactly for the requested
    /// chroma layout after decoding its block-header palette state under one matching sequence header.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    private static void assertBitstreamDerivedPaletteLeafReconstructsExactly(PixelFormat pixelFormat) {
        TileBlockHeaderReader.BlockHeader header =
                decodePaletteEnabledHeaderFromPayload(pixelFormat, BITSTREAM_DERIVED_PALETTE_PAYLOAD);
        TilePartitionTreeReader.LeafNode paletteLeaf =
                createLeafWithBitstreamDerivedPaletteHeader(pixelFormat, header);
        int chromaWidth = bitstreamDerivedPaletteChromaWidth(pixelFormat);
        int chromaHeight = bitstreamDerivedPaletteChromaHeight(pixelFormat);

        assertFalse(header.skip());
        assertTrue(header.hasChroma());
        assertEquals(LumaIntraPredictionMode.DC, header.yMode());
        assertEquals(UvIntraPredictionMode.DC, header.uvMode());
        assertTrue(header.yPaletteSize() >= 2);
        assertTrue(header.uvPaletteSize() >= 2);
        assertTrue(allResidualUnitsZero(paletteLeaf.residualLayout()));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(
                createSyntheticResult(
                        createPaletteEnabledAssembly(
                                pixelFormat,
                                BITSTREAM_DERIVED_PALETTE_PAYLOAD,
                                8,
                                8,
                                FrameHeader.TransformMode.LARGEST
                        ),
                        paletteLeaf
                )
        );

        assertPlaneEquals(
                decodedPlanes.lumaPlane(),
                expandPackedPaletteSamples(header.yPaletteColors(), header.yPaletteIndices(), 8, 8, 8)
        );
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaUPlane()),
                expandPackedPaletteSamples(
                        header.uPaletteColors(),
                        header.uvPaletteIndices(),
                        chromaWidth,
                        chromaHeight,
                        chromaWidth
                )
        );
        assertPlaneEquals(
                requirePlane(decodedPlanes.chromaVPlane()),
                expandPackedPaletteSamples(
                        header.vPaletteColors(),
                        header.uvPaletteIndices(),
                        chromaWidth,
                        chromaHeight,
                        chromaWidth
                )
        );
    }

    /// Returns the exact visible chroma width reconstructed from the deterministic real palette
    /// fixture for one top-left `8x8` block.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return the exact visible chroma width reconstructed from the deterministic real palette fixture
    private static int bitstreamDerivedPaletteChromaWidth(PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I420, I422 -> 4;
            case I444 -> 8;
            default -> throw new IllegalArgumentException(
                    "Bitstream-derived palette helper expects I420, I422, or I444: " + pixelFormat
            );
        };
    }

    /// Returns the exact visible chroma height reconstructed from the deterministic real palette
    /// fixture for one top-left `8x8` block.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return the exact visible chroma height reconstructed from the deterministic real palette fixture
    private static int bitstreamDerivedPaletteChromaHeight(PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I420 -> 4;
            case I422, I444 -> 8;
            default -> throw new IllegalArgumentException(
                    "Bitstream-derived palette helper expects I420, I422, or I444: " + pixelFormat
            );
        };
    }

    /// Decodes one palette-enabled `8x8` block header from a caller-supplied tile payload under
    /// the requested chroma layout.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload to decode structurally
    /// @return one palette-enabled block header decoded from the supplied tile payload
    private static TileBlockHeaderReader.BlockHeader decodePaletteEnabledHeaderFromPayload(
            PixelFormat pixelFormat,
            byte[] payload
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileDecodeContext tileContext = createPaletteEnabledTileContext(
                payload,
                pixelFormat,
                8,
                8,
                FrameHeader.TransformMode.LARGEST
        );
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        return blockHeaderReader.read(position, blockSize, neighborContext);
    }

    /// Wraps one real bitstream-derived palette block header in the minimal transform and all-zero
    /// residual layout needed by the frame reconstructor.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param header the real bitstream-derived palette block header
    /// @return one reconstruction-ready leaf that preserves the decoded palette state
    private static TilePartitionTreeReader.LeafNode createLeafWithBitstreamDerivedPaletteHeader(
            PixelFormat pixelFormat,
            TileBlockHeaderReader.BlockHeader header
    ) {
        BlockPosition position = header.position();
        BlockSize blockSize = header.size();
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                blockSize.width4(),
                blockSize.height4(),
                TransformSize.TX_8X8,
                requireChromaTransformSize(blockSize, pixelFormat),
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Returns the fixed fixture payload whose decoded `I420` block carries one non-zero clipped
    /// or fringe chroma residual under the supplied geometry.
    ///
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return a deterministic payload whose decoded block carries one clipped chroma residual
    private static byte[] findPayloadForBitstreamDerivedI420ChromaResidual(
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return findPayloadForBitstreamDerivedChromaResidual(
                PixelFormat.I420,
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
    }

    /// Returns the fixed fixture payload whose decoded chroma block carries one non-zero clipped
    /// or fringe chroma residual under the supplied geometry.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return a deterministic payload whose decoded block carries one clipped chroma residual
    private static byte[] findPayloadForBitstreamDerivedChromaResidual(
            PixelFormat pixelFormat,
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        if (pixelFormat == PixelFormat.I420
                && position.x4() == 0
                && position.y4() == 0
                && blockSize == BlockSize.SIZE_8X8
                && codedWidth == 4
                && codedHeight == 8
                && transformMode == FrameHeader.TransformMode.LARGEST) {
            return readFrameReconstructorFixture("i420-clipped");
        }
        if (pixelFormat == PixelFormat.I420
                && position.x4() == 1
                && position.y4() == 1
                && blockSize == BlockSize.SIZE_4X4
                && codedWidth == 12
                && codedHeight == 12
                && transformMode == FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY) {
            return readFrameReconstructorFixture("i420-fringe");
        }
        if (pixelFormat == PixelFormat.I422
                && position.x4() == 0
                && position.y4() == 0
                && blockSize == BlockSize.SIZE_8X8
                && codedWidth == 7
                && codedHeight == 5
                && transformMode == FrameHeader.TransformMode.LARGEST) {
            return readFrameReconstructorFixture("i422-clipped");
        }
        throw new IllegalArgumentException("Unsupported chroma-residual fixture request");
    }

    /// Returns the fixed fixture payload whose decoded `I420` block carries one non-zero
    /// larger-transform chroma residual with multiple decoded coefficients.
    ///
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return a deterministic payload whose decoded block carries one larger-transform chroma residual
    private static byte[] findPayloadForBitstreamDerivedLargerTransformI420ChromaResidual(
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        if (position.x4() == 0
                && position.y4() == 0
                && blockSize == BlockSize.SIZE_16X16
                && codedWidth == 16
                && codedHeight == 16
                && transformMode == FrameHeader.TransformMode.LARGEST) {
            return readFrameReconstructorFixture("i420-tx8x8");
        }
        throw new IllegalArgumentException("Unsupported larger-transform chroma-residual fixture request");
    }

    /// Decodes one `I420` leaf from a caller-supplied tile payload using the current integration
    /// block/transform/residual readers.
    ///
    /// @param payload the tile payload to decode structurally
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one `I420` leaf decoded from the supplied tile payload
    private static TilePartitionTreeReader.LeafNode decodeI420LeafFromPayload(
            byte[] payload,
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return decodeChromaLeafFromPayload(
                PixelFormat.I420,
                payload,
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
    }

    /// Decodes one chroma-carrying leaf from a caller-supplied tile payload using the current
    /// integration block/transform/residual readers.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload to decode structurally
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one chroma-carrying leaf decoded from the supplied tile payload
    private static TilePartitionTreeReader.LeafNode decodeChromaLeafFromPayload(
            PixelFormat pixelFormat,
            byte[] payload,
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        TileDecodeContext tileContext = createTileContext(payload, pixelFormat, codedWidth, codedHeight, transformMode);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, blockSize, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Returns whether any residual unit in the supplied array exposes a clipped visible footprint.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether any residual unit in the supplied array exposes a clipped visible footprint
    private static boolean hasClippedResidualFootprint(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (residualUnit.visibleWidthPixels() < residualUnit.size().widthPixels()
                    || residualUnit.visibleHeightPixels() < residualUnit.size().heightPixels()) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether the supplied residual-unit array contains at least one non-zero unit.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether the supplied residual-unit array contains at least one non-zero unit
    private static boolean hasNonZeroResidual(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (!residualUnit.allZero()) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether every decoded residual unit in one layout is all-zero.
    ///
    /// @param residualLayout the residual layout to inspect
    /// @return whether every decoded residual unit in the supplied layout is all-zero
    private static boolean allResidualUnitsZero(ResidualLayout residualLayout) {
        return allResidualUnitsZero(residualLayout.lumaUnits())
                && allResidualUnitsZero(residualLayout.chromaUUnits())
                && allResidualUnitsZero(residualLayout.chromaVUnits());
    }

    /// Returns whether every residual unit in the supplied array is all-zero.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether every residual unit in the supplied array is all-zero
    private static boolean allResidualUnitsZero(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (!residualUnit.allZero()) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether the supplied residual-unit array contains at least one multi-coefficient unit.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether the supplied residual-unit array contains at least one multi-coefficient unit
    private static boolean hasMultiCoefficientResidual(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (hasMultiCoefficientResidual(residualUnit)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether the supplied residual unit exposes at least two decoded non-zero coefficients.
    ///
    /// @param residualUnit the residual unit to inspect
    /// @return whether the supplied residual unit exposes multiple decoded coefficients
    private static boolean hasMultiCoefficientResidual(TransformResidualUnit residualUnit) {
        return !residualUnit.allZero()
                && residualUnit.endOfBlockIndex() > 1
                && countNonZeroCoefficients(residualUnit.coefficients()) >= 2;
    }

    /// Counts the number of non-zero coefficients in one dense transform-domain coefficient array.
    ///
    /// @param coefficients the dense transform-domain coefficient array in natural raster order
    /// @return the number of non-zero coefficients in the supplied array
    private static int countNonZeroCoefficients(int[] coefficients) {
        int count = 0;
        for (int coefficient : coefficients) {
            if (coefficient != 0) {
                count++;
            }
        }
        return count;
    }

    /// Returns whether the supplied decoded leaf produces any observable chroma-plane change when
    /// reconstructed against an all-zero chroma baseline.
    ///
    /// @param decodedLeaf the decoded leaf to inspect
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return whether the supplied decoded leaf produces any observable chroma-plane change
    private static boolean bitstreamDerivedLeafProducesChromaChange(
            TilePartitionTreeReader.LeafNode decodedLeaf,
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        FrameAssembly assembly = createAssembly(pixelFormat, new byte[0], codedWidth, codedHeight, transformMode);
        TilePartitionTreeReader.LeafNode baselineLeaf = clearDecodedLeafChromaResiduals(decodedLeaf);
        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(createSyntheticResult(assembly, baselineLeaf));
        DecodedPlanes reconstructed = reconstructor.reconstruct(createSyntheticResult(assembly, decodedLeaf));
        return planeDiffers(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()))
                || planeDiffers(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
    }

    /// Returns whether two decoded planes differ at any stored sample coordinate.
    ///
    /// @param first the first decoded plane
    /// @param second the second decoded plane
    /// @return whether two decoded planes differ at any stored sample coordinate
    private static boolean planeDiffers(DecodedPlane first, DecodedPlane second) {
        assertEquals(first.width(), second.width());
        assertEquals(first.height(), second.height());
        for (int y = 0; y < first.height(); y++) {
            for (int x = 0; x < first.width(); x++) {
                if (first.sample(x, y) != second.sample(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Expands one packed palette map to concrete sample values using the same packed-nibble layout
    /// consumed by the frame reconstructor.
    ///
    /// @param paletteColors the decoded palette entries addressed by the packed map
    /// @param packedIndices the packed palette indices with two 4-bit entries per byte
    /// @param visibleWidth the visible palette-map width in samples
    /// @param visibleHeight the visible palette-map height in samples
    /// @param packedFullWidth the coded palette-map width in samples used to derive the packed stride
    /// @return the expanded palette-mapped sample raster
    private static int[][] expandPackedPaletteSamples(
            int[] paletteColors,
            byte[] packedIndices,
            int visibleWidth,
            int visibleHeight,
            int packedFullWidth
    ) {
        int[][] samples = new int[visibleHeight][visibleWidth];
        int packedStride = packedFullWidth >> 1;
        for (int y = 0; y < visibleHeight; y++) {
            int packedRow = y * packedStride;
            for (int x = 0; x < visibleWidth; x++) {
                int paletteIndex = packedPaletteIndexAt(packedIndices, packedRow, x);
                samples[y][x] = paletteColors[paletteIndex];
            }
        }
        return samples;
    }

    /// Returns one unpacked palette index from the packed two-4-bit-per-byte raster layout.
    ///
    /// @param packedIndices the packed palette indices
    /// @param packedRow the starting byte offset of the current packed row
    /// @param x the zero-based sample coordinate within the current row
    /// @return the unpacked palette index at the requested sample coordinate
    private static int packedPaletteIndexAt(byte[] packedIndices, int packedRow, int x) {
        int packedByte = packedIndices[packedRow + (x >> 1)] & 0xFF;
        return (x & 1) == 0 ? packedByte & 0x0F : (packedByte >>> 4) & 0x0F;
    }

    /// Decodes one reduced still-picture syntax result from a combined `FRAME` OBU fixture.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return one structural frame-decode result for the combined fixture
    /// @throws IOException if the fixture cannot be parsed
    private static FrameSyntaxDecodeResult decodeReducedStillPictureSyntaxResultFromCombinedFrame(
            byte[] tileGroupPayload
    ) throws IOException {
        return decodeReducedStillPictureSyntaxResultFromCombinedFrame(
                reducedStillPicturePayload(),
                reducedStillPictureCombinedFramePayload(tileGroupPayload)
        );
    }

    /// Parses and structurally decodes one reduced still-picture combined frame fixture using
    /// caller-supplied sequence-header and combined-frame payloads.
    ///
    /// @param sequenceHeaderPayload the reduced still-picture sequence-header payload
    /// @param combinedPayload the combined `FRAME` payload including the tile-group payload
    /// @return the structural frame-decode result for the fixture
    /// @throws IOException if the fixture cannot be parsed
    private static FrameSyntaxDecodeResult decodeReducedStillPictureSyntaxResultFromCombinedFrame(
            byte[] sequenceHeaderPayload,
            byte[] combinedPayload
    ) throws IOException {
        return new FrameSyntaxDecoder(null).decode(createReducedStillPictureCombinedAssembly(sequenceHeaderPayload, combinedPayload));
    }

    /// Decodes one reduced still-picture syntax result from standalone `FRAME_HEADER` plus
    /// `TILE_GROUP` OBU fixtures.
    ///
    /// @param tileGroupPayload the standalone tile-group payload
    /// @return one structural frame-decode result for the standalone fixture
    /// @throws IOException if the fixture cannot be parsed
    private static FrameSyntaxDecodeResult decodeReducedStillPictureSyntaxResultFromStandaloneObus(
            byte[] tileGroupPayload
    ) throws IOException {
        return new FrameSyntaxDecoder(null).decode(createReducedStillPictureStandaloneAssembly(tileGroupPayload));
    }

    /// Asserts that one structurally decoded legacy still-picture frame reconstructs successfully
    /// through a decoded directional luma leaf and still yields stable output samples.
    ///
    /// @param syntaxDecodeResult the structural frame-decode result produced from the legacy fixture
    private static void assertDirectionalStillPictureFixtureReconstructsSuccessfully(
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        assertEquals(1, syntaxDecodeResult.tileCount());
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxDecodeResult.tileRoots(0));
        assertFalse(leaves.isEmpty());

        int firstDirectionalLeafIndex = -1;
        for (int i = 0; i < leaves.size(); i++) {
            LumaIntraPredictionMode mode = leaves.get(i).header().yMode();
            if (mode != null && mode.isDirectional()) {
                firstDirectionalLeafIndex = i;
                break;
            }
        }
        assertTrue(firstDirectionalLeafIndex > 0);

        TilePartitionTreeReader.LeafNode directionalLeaf = leaves.get(firstDirectionalLeafIndex);
        assertTrue(directionalLeaf.header().intra());
        LumaIntraPredictionMode mode = directionalLeaf.header().yMode();
        assertTrue(mode != null && mode.isDirectional());
        assertEquals(0, directionalLeaf.header().yAngle());
        assertLegacyDirectionalStillPicturePlanes(new FrameReconstructor().reconstruct(syntaxDecodeResult));
    }

    /// Asserts the stable legacy directional still-picture reconstruction oracle.
    ///
    /// The current first directional path perturbs the top-left luma region while the chroma
    /// planes remain midpoint-gray.
    ///
    /// @param decodedPlanes the reconstructed planes returned by the frame reconstructor
    private static void assertLegacyDirectionalStillPicturePlanes(DecodedPlanes decodedPlanes) {
        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(64, decodedPlanes.codedWidth());
        assertEquals(64, decodedPlanes.codedHeight());
        assertEquals(64, decodedPlanes.renderWidth());
        assertEquals(64, decodedPlanes.renderHeight());
        assertPlaneBlockEquals(decodedPlanes.lumaPlane(), 0, 0, LEGACY_DIRECTIONAL_LUMA_TOP_LEFT_8X8);
        assertPlaneBlockFilledWith(decodedPlanes.chromaUPlane(), 0, 0, 4, 4, 128);
        assertPlaneBlockFilledWith(decodedPlanes.chromaVPlane(), 0, 0, 4, 4, 128);
    }

    /// Asserts one rectangular plane block against expected sample values.
    ///
    /// @param plane the decoded plane to inspect
    /// @param originX the zero-based block origin X coordinate
    /// @param originY the zero-based block origin Y coordinate
    /// @param expected the expected block in row-major order
    private static void assertPlaneBlockEquals(
            @Nullable DecodedPlane plane,
            int originX,
            int originY,
            int[][] expected
    ) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], plane.sample(originX + x, originY + y));
            }
        }
    }

    /// Asserts that every sample in one decoded plane equals the supplied expected value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param width the expected width
    /// @param height the expected height
    /// @param value the expected constant sample value
    private static void assertPlaneFilled(DecodedPlane plane, int width, int height, int value) {
        assertEquals(width, plane.width());
        assertEquals(height, plane.height());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(value, plane.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded plane equals the supplied expected raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expected the expected raster in row-major order
    private static void assertPlaneEquals(DecodedPlane plane, int[][] expected) {
        assertEquals(expected[0].length, plane.width());
        assertEquals(expected.length, plane.height());
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], plane.sample(x, y));
            }
        }
    }

    /// Asserts one rectangular plane block is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param originX the zero-based block origin X coordinate
    /// @param originY the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param expectedSample the expected constant sample value
    private static void assertPlaneBlockFilledWith(
            @Nullable DecodedPlane plane,
            int originX,
            int originY,
            int width,
            int height,
            int expectedSample
    ) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(expectedSample, plane.sample(originX + x, originY + y));
            }
        }
    }

    /// Asserts that one decoded reduced still-picture frame contains one stable `I420` sample
    /// value throughout the visible image.
    ///
    /// @param decodedPlanes the reconstructed planes returned by the frame reconstructor
    /// @param expectedSample the expected constant sample value shared by the visible planes
    private static void assertStillPicturePlanesFilledWith(DecodedPlanes decodedPlanes, int expectedSample) {
        assertStillPicturePlanesFilledWith(decodedPlanes, expectedSample, 64, 64);
    }

    /// Asserts that one still-picture reconstruction keeps every visible sample at one constant
    /// value for the supplied frame geometry.
    ///
    /// @param decodedPlanes the reconstructed planes returned by the frame reconstructor
    /// @param expectedSample the expected constant sample value shared by the visible planes
    /// @param expectedWidth the expected coded and rendered frame width
    /// @param expectedHeight the expected coded and rendered frame height
    private static void assertStillPicturePlanesFilledWith(
            DecodedPlanes decodedPlanes,
            int expectedSample,
            int expectedWidth,
            int expectedHeight
    ) {
        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(expectedWidth, decodedPlanes.codedWidth());
        assertEquals(expectedHeight, decodedPlanes.codedHeight());
        assertEquals(expectedWidth, decodedPlanes.renderWidth());
        assertEquals(expectedHeight, decodedPlanes.renderHeight());
        assertPlaneFilledWith(decodedPlanes.lumaPlane(), expectedSample);
        assertPlaneFilledWith(decodedPlanes.chromaUPlane(), expectedSample);
        assertPlaneFilledWith(decodedPlanes.chromaVPlane(), expectedSample);
    }

    /// Asserts that one decoded plane is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expectedSample the expected constant sample value
    private static void assertPlaneFilledWith(@Nullable DecodedPlane plane, int expectedSample) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                assertEquals(expectedSample, plane.sample(x, y));
            }
        }
    }

    /// Asserts that two decoded planes carry identical stored sample values.
    ///
    /// @param expected the expected decoded plane
    /// @param actual the actual decoded plane
    private static void assertPlanesEqual(DecodedPlane expected, DecodedPlane actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                assertEquals(expected.sample(x, y), actual.sample(x, y));
            }
        }
    }

    /// Asserts that every sample differs from the baseline by the same non-zero signed offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the reconstructed plane after one non-zero residual
    /// @param expectedSign the required delta sign, either `1` or `-1`
    private static void assertPlaneDiffersFromBaselineByUniformSignedOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
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

    /// Returns the supplied inter motion-vector state after asserting that it is present.
    ///
    /// @param motionVector the decoded inter motion-vector state, or `null`
    /// @return the supplied inter motion-vector state after asserting that it is present
    private static InterMotionVector requireNonNullInterMotionVector(@Nullable InterMotionVector motionVector) {
        assertNotNull(motionVector);
        return motionVector;
    }

    /// Asserts that one residual-unit footprint carries at least one visible delta while samples
    /// outside the visible footprint remain unchanged.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the reconstructed plane after one non-zero residual
    /// @param residualUnit the residual unit whose footprint should carry the injected delta
    /// @param expectedSign the historical expected delta sign, retained only for call-site stability
    private static void assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            TransformResidualUnit residualUnit,
            int expectedSign
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());

        int originX = residualUnit.position().x4() << 1;
        int originY = residualUnit.position().y4() << 1;
        int width = residualUnit.visibleWidthPixels();
        int height = residualUnit.visibleHeightPixels();
        boolean sawChangedSample = false;
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                int delta = reconstructed.sample(x, y) - baseline.sample(x, y);
                boolean inside = x >= originX && x < originX + width && y >= originY && y < originY + height;
                if (!inside) {
                    assertEquals(0, delta);
                    continue;
                }
                if (delta != 0) {
                    sawChangedSample = true;
                }
            }
        }
        assertTrue(sawChangedSample);
    }

    /// Asserts that one bitstream-derived chroma residual reconstructs only inside the visible
    /// chroma footprint implied by the decoded residual units.
    ///
    /// @param assembly the frame assembly that owns the decoded leaf
    /// @param decodedLeaf the decoded leaf whose chroma residuals were derived from tile payload bits
    private static void assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
            FrameAssembly assembly,
            TilePartitionTreeReader.LeafNode decodedLeaf
    ) {
        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        assertTrue(residualLayout.hasChromaUnits(), "Bitstream-derived chroma residuals were dropped from ResidualLayout");

        TilePartitionTreeReader.LeafNode baselineLeaf = clearDecodedLeafChromaResiduals(decodedLeaf);
        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(createSyntheticResult(assembly, baselineLeaf));
        DecodedPlanes reconstructed = reconstructor.reconstruct(createSyntheticResult(assembly, decodedLeaf));

        assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());

        DecodedPlane baselineChromaU = requirePlane(baseline.chromaUPlane());
        DecodedPlane reconstructedChromaU = requirePlane(reconstructed.chromaUPlane());
        TransformResidualUnit chromaUUnit = residualLayout.chromaUUnits()[0];
        if (!planeDiffers(baselineChromaU, reconstructedChromaU)) {
            assertPlanesEqual(baselineChromaU, reconstructedChromaU);
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    baselineChromaU,
                    reconstructedChromaU,
                    chromaUUnit,
                    Integer.signum(chromaUUnit.dcCoefficient())
            );
        }

        DecodedPlane baselineChromaV = requirePlane(baseline.chromaVPlane());
        DecodedPlane reconstructedChromaV = requirePlane(reconstructed.chromaVPlane());
        TransformResidualUnit chromaVUnit = residualLayout.chromaVUnits()[0];
        if (!planeDiffers(baselineChromaV, reconstructedChromaV)) {
            assertPlanesEqual(baselineChromaV, reconstructedChromaV);
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    baselineChromaV,
                    reconstructedChromaV,
                    chromaVUnit,
                    Integer.signum(chromaVUnit.dcCoefficient())
            );
        }
    }

    /// Returns one copy of the supplied decoded leaf with all chroma residual units cleared while
    /// preserving their original visible footprints.
    ///
    /// @param decodedLeaf the decoded leaf to copy
    /// @return one copy of the supplied decoded leaf with all chroma residual units cleared
    private static TilePartitionTreeReader.LeafNode clearDecodedLeafChromaResiduals(
            TilePartitionTreeReader.LeafNode decodedLeaf
    ) {
        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        return new TilePartitionTreeReader.LeafNode(
                decodedLeaf.header(),
                decodedLeaf.transformLayout(),
                createResidualLayout(
                        residualLayout.position(),
                        residualLayout.blockSize(),
                        residualLayout.lumaUnits(),
                        clearResidualUnits(residualLayout.chromaUUnits()),
                        clearResidualUnits(residualLayout.chromaVUnits())
                )
        );
    }

    /// Returns one cleared copy of the supplied residual units while preserving their visible footprints.
    ///
    /// @param residualUnits the residual units to clear
    /// @return one cleared copy of the supplied residual units
    private static TransformResidualUnit[] clearResidualUnits(TransformResidualUnit[] residualUnits) {
        TransformResidualUnit[] clearedUnits = new TransformResidualUnit[residualUnits.length];
        for (int i = 0; i < residualUnits.length; i++) {
            TransformResidualUnit residualUnit = residualUnits[i];
            clearedUnits[i] = createAllZeroResidualUnit(
                    residualUnit.position(),
                    residualUnit.size(),
                    residualUnit.visibleWidthPixels(),
                    residualUnit.visibleHeightPixels()
            );
        }
        return clearedUnits;
    }

    /// Returns one guaranteed-present decoded plane after a non-null assertion.
    ///
    /// @param plane the decoded plane reference, or `null`
    /// @return the same decoded plane reference after a non-null assertion
    private static DecodedPlane requirePlane(@Nullable DecodedPlane plane) {
        assertNotNull(plane);
        return plane;
    }

    /// Asserts that one structurally decoded legacy still-picture fixture can carry one injected
    /// chroma residual unit through reconstruction while leaving unrelated output unchanged.
    ///
    /// @param syntaxDecodeResult the structurally decoded legacy still-picture frame
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for no injected U residual
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for no injected V residual
    private static void assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TilePartitionTreeReader.LeafNode targetLeaf = findResidualReadyLegacyI420Leaf(
                syntaxDecodeResult,
                chromaUDcCoefficient,
                chromaVDcCoefficient
        );
        TilePartitionTreeReader.LeafNode clearedLeaf = injectChromaDcResiduals(targetLeaf, 0, 0);
        TilePartitionTreeReader.LeafNode injectedLeaf =
                injectChromaDcResiduals(clearedLeaf, chromaUDcCoefficient, chromaVDcCoefficient);
        FrameSyntaxDecodeResult baselineSyntax = replaceLeaf(syntaxDecodeResult, targetLeaf, clearedLeaf);
        FrameSyntaxDecodeResult injectedSyntax = replaceLeaf(syntaxDecodeResult, targetLeaf, injectedLeaf);

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes reconstructed = reconstructor.reconstruct(injectedSyntax);

        assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());

        if (chromaUDcCoefficient == 0) {
            assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()));
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    requirePlane(baseline.chromaUPlane()),
                    requirePlane(reconstructed.chromaUPlane()),
                    injectedLeaf.residualLayout().chromaUUnits()[0],
                    Integer.signum(chromaUDcCoefficient)
            );
        }

        if (chromaVDcCoefficient == 0) {
            assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    requirePlane(baseline.chromaVPlane()),
                    requirePlane(reconstructed.chromaVPlane()),
                    injectedLeaf.residualLayout().chromaVUnits()[0],
                    Integer.signum(chromaVDcCoefficient)
            );
        }
    }

    /// Returns one decoded legacy `I420` leaf that actually carries the caller-specified isolated
    /// chroma residual through the current reconstruction path.
    ///
    /// @param syntaxDecodeResult the structurally decoded legacy still-picture frame
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for no injected U residual
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for no injected V residual
    /// @return one decoded legacy `I420` leaf that actually carries the caller-specified isolated chroma residual
    private static TilePartitionTreeReader.LeafNode findResidualReadyLegacyI420Leaf(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        assertEquals(1, syntaxDecodeResult.tileCount());
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxDecodeResult.tileRoots(0));
        FrameReconstructor reconstructor = new FrameReconstructor();
        for (TilePartitionTreeReader.LeafNode leaf : leaves) {
            TransformLayout transformLayout = leaf.transformLayout();
            if (leaf.header().hasChroma()
                    && leaf.header().uvMode() != null
                    && leaf.residualLayout().hasChromaUnits()
                    && transformLayout.chromaTransformSize() == TransformSize.TX_4X4) {
                TilePartitionTreeReader.LeafNode clearedLeaf = injectChromaDcResiduals(leaf, 0, 0);
                TilePartitionTreeReader.LeafNode injectedLeaf = injectChromaDcResiduals(
                        clearedLeaf,
                        chromaUDcCoefficient,
                        chromaVDcCoefficient
                );
                FrameSyntaxDecodeResult baselineSyntax = replaceLeaf(syntaxDecodeResult, leaf, clearedLeaf);
                FrameSyntaxDecodeResult injectedSyntax = replaceLeaf(syntaxDecodeResult, leaf, injectedLeaf);
                DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
                DecodedPlanes reconstructed = reconstructor.reconstruct(injectedSyntax);
                try {
                    assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());
                    if (chromaUDcCoefficient == 0) {
                        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()));
                    } else {
                        assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                                requirePlane(baseline.chromaUPlane()),
                                requirePlane(reconstructed.chromaUPlane()),
                                injectedLeaf.residualLayout().chromaUUnits()[0],
                                Integer.signum(chromaUDcCoefficient)
                        );
                    }
                    if (chromaVDcCoefficient == 0) {
                        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
                    } else {
                        assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                                requirePlane(baseline.chromaVPlane()),
                                requirePlane(reconstructed.chromaVPlane()),
                                injectedLeaf.residualLayout().chromaVUnits()[0],
                                Integer.signum(chromaVDcCoefficient)
                        );
                    }
                    return leaf;
                } catch (AssertionError ignored) {
                    // Continue scanning until one decoded legacy leaf satisfies the current exact-footprint oracle.
                }
            }
        }
        throw new AssertionError("No legacy I420 leaf carried the requested isolated chroma residual");
    }

    /// Returns one copy of the supplied decoded leaf with caller-specified chroma DC residuals.
    ///
    /// Original header, transform layout, and luma residual units are preserved so the integration
    /// coverage stays anchored to the real structural frame-decode result.
    ///
    /// @param decodedLeaf the decoded legacy leaf to copy
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for an all-zero U unit
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for an all-zero V unit
    /// @return one copy of the supplied decoded leaf with caller-specified chroma DC residuals
    private static TilePartitionTreeReader.LeafNode injectChromaDcResiduals(
            TilePartitionTreeReader.LeafNode decodedLeaf,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TransformLayout transformLayout = decodedLeaf.transformLayout();
        assertEquals(TransformSize.TX_4X4, transformLayout.chromaTransformSize());

        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        return new TilePartitionTreeReader.LeafNode(
                decodedLeaf.header(),
                transformLayout,
                createResidualLayout(
                        residualLayout.position(),
                        residualLayout.blockSize(),
                        residualLayout.lumaUnits(),
                        replaceOrCreateChromaResidualUnits(
                                residualLayout.chromaUUnits(),
                                decodedLeaf.position(),
                                TransformSize.TX_4X4,
                                chromaUDcCoefficient
                        ),
                        replaceOrCreateChromaResidualUnits(
                                residualLayout.chromaVUnits(),
                                decodedLeaf.position(),
                                TransformSize.TX_4X4,
                                chromaVDcCoefficient
                        )
                )
        );
    }

    /// Returns one chroma residual-unit array whose first unit reflects the caller-supplied DC
    /// coefficient while any remaining decoded units are preserved.
    ///
    /// @param existingUnits the already-decoded chroma residual units
    /// @param defaultPosition the default block origin used when the decoded leaf carried no chroma units
    /// @param defaultTransformSize the default transform size used when the decoded leaf carried no chroma units
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero first unit
    /// @return one chroma residual-unit array whose first unit reflects the caller-supplied DC coefficient
    private static TransformResidualUnit[] replaceOrCreateChromaResidualUnits(
            TransformResidualUnit[] existingUnits,
            BlockPosition defaultPosition,
            TransformSize defaultTransformSize,
            int dcCoefficient
    ) {
        if (existingUnits.length == 0) {
            return new TransformResidualUnit[]{
                    createOptionalDcResidualUnit(defaultPosition, defaultTransformSize, dcCoefficient)
            };
        }

        TransformResidualUnit[] replacementUnits = existingUnits.clone();
        TransformResidualUnit firstUnit = replacementUnits[0];
        replacementUnits[0] = createOptionalDcResidualUnit(
                firstUnit.position(),
                firstUnit.size(),
                firstUnit.visibleWidthPixels(),
                firstUnit.visibleHeightPixels(),
                dcCoefficient
        );
        return replacementUnits;
    }

    /// Returns one copy of the supplied frame-syntax result with one decoded leaf replaced.
    ///
    /// @param syntaxDecodeResult the original structural frame-decode result
    /// @param targetLeaf the decoded leaf to replace
    /// @param replacementLeaf the replacement decoded leaf
    /// @return one copy of the supplied frame-syntax result with one decoded leaf replaced
    private static FrameSyntaxDecodeResult replaceLeaf(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            TilePartitionTreeReader.LeafNode targetLeaf,
            TilePartitionTreeReader.LeafNode replacementLeaf
    ) {
        TilePartitionTreeReader.Node[][] replacementRoots = syntaxDecodeResult.tileRoots();
        boolean replaced = false;
        for (int tileIndex = 0; tileIndex < replacementRoots.length; tileIndex++) {
            TilePartitionTreeReader.Node[] tileRoots = replacementRoots[tileIndex];
            for (int rootIndex = 0; rootIndex < tileRoots.length; rootIndex++) {
                TilePartitionTreeReader.Node originalRoot = tileRoots[rootIndex];
                TilePartitionTreeReader.Node replacementRoot = replaceLeaf(originalRoot, targetLeaf, replacementLeaf);
                tileRoots[rootIndex] = replacementRoot;
                if (replacementRoot != originalRoot) {
                    replaced = true;
                }
            }
        }
        assertTrue(replaced, "Target legacy leaf was not present in the decoded frame tree");
        return new FrameSyntaxDecodeResult(
                syntaxDecodeResult.assembly(),
                replacementRoots,
                syntaxDecodeResult.decodedTemporalMotionFields(),
                syntaxDecodeResult.finalTileCdfContexts()
        );
    }

    /// Returns one copy of the supplied node subtree with one decoded leaf replaced.
    ///
    /// @param node the subtree root to copy
    /// @param targetLeaf the decoded leaf to replace
    /// @param replacementLeaf the replacement decoded leaf
    /// @return one copy of the supplied node subtree with one decoded leaf replaced
    private static TilePartitionTreeReader.Node replaceLeaf(
            TilePartitionTreeReader.Node node,
            TilePartitionTreeReader.LeafNode targetLeaf,
            TilePartitionTreeReader.LeafNode replacementLeaf
    ) {
        if (node == targetLeaf) {
            return replacementLeaf;
        }
        if (node instanceof TilePartitionTreeReader.LeafNode) {
            return node;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        TilePartitionTreeReader.Node[] children = partitionNode.children();
        boolean replacedChild = false;
        for (int i = 0; i < children.length; i++) {
            TilePartitionTreeReader.Node originalChild = children[i];
            TilePartitionTreeReader.Node replacementChild = replaceLeaf(originalChild, targetLeaf, replacementLeaf);
            children[i] = replacementChild;
            if (replacementChild != originalChild) {
                replacedChild = true;
            }
        }
        if (!replacedChild) {
            return partitionNode;
        }
        return new TilePartitionTreeReader.PartitionNode(
                partitionNode.position(),
                partitionNode.size(),
                partitionNode.partitionType(),
                children
        );
    }

    /// Creates one reduced still-picture combined-frame assembly with a caller-supplied tile
    /// payload.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return one complete reduced still-picture frame assembly
    /// @throws IOException if the fixture cannot be parsed
    private static FrameAssembly createReducedStillPictureCombinedAssembly(byte[] tileGroupPayload) throws IOException {
        return createReducedStillPictureCombinedAssembly(
                reducedStillPicturePayload(),
                reducedStillPictureCombinedFramePayload(tileGroupPayload)
        );
    }

    /// Creates one reduced still-picture combined-frame assembly with caller-supplied sequence-header
    /// and combined-frame payloads.
    ///
    /// @param sequenceHeaderPayload the reduced still-picture sequence-header payload
    /// @param combinedPayload the combined `FRAME` payload including the tile-group payload
    /// @return one complete reduced still-picture frame assembly
    /// @throws IOException if the fixture cannot be parsed
    private static FrameAssembly createReducedStillPictureCombinedAssembly(
            byte[] sequenceHeaderPayload,
            byte[] combinedPayload
    ) throws IOException {
        SequenceHeader sequenceHeader = new SequenceHeaderParser().parse(sequenceHeaderObu(sequenceHeaderPayload), false);
        ObuPacket frameObu = frameObu(combinedPayload);
        BitReader reader = new BitReader(combinedPayload);
        FrameHeader frameHeader = new FrameHeaderParser().parseFramePayload(reader, frameObu, sequenceHeader, false);
        TileGroupHeader tileGroupHeader = new TileGroupHeaderParser().parse(reader, frameObu, frameHeader);
        reader.byteAlign();
        int tileDataOffset = reader.byteOffset();
        TileBitstream[] tileBitstreams = new TileBitstreamParser().parse(frameObu, frameHeader, tileGroupHeader, tileDataOffset);

        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, frameObu.streamOffset(), frameObu.obuIndex());
        assembly.addTileGroup(
                frameObu,
                tileGroupHeader,
                tileDataOffset,
                combinedPayload.length - tileDataOffset,
                tileBitstreams
        );
        return assembly;
    }

    /// Creates one reduced still-picture standalone frame assembly with a caller-supplied tile
    /// payload.
    ///
    /// @param tileGroupPayload the standalone tile-group payload
    /// @return one complete reduced still-picture frame assembly
    /// @throws IOException if the fixture cannot be parsed
    private static FrameAssembly createReducedStillPictureStandaloneAssembly(byte[] tileGroupPayload) throws IOException {
        SequenceHeader sequenceHeader = parseReducedStillPictureSequenceHeader();
        ObuPacket frameHeaderObu = frameHeaderObu(reducedStillPictureFrameHeaderPayload());
        FrameHeader frameHeader = new FrameHeaderParser().parse(frameHeaderObu, sequenceHeader, false);
        ObuPacket tileGroupObu = tileGroupObu(tileGroupPayload);
        BitReader tileGroupReader = new BitReader(tileGroupPayload);
        TileGroupHeader tileGroupHeader = new TileGroupHeaderParser().parse(tileGroupReader, tileGroupObu, frameHeader);
        tileGroupReader.byteAlign();
        int tileDataOffset = tileGroupReader.byteOffset();
        TileBitstream[] tileBitstreams = new TileBitstreamParser().parse(tileGroupObu, frameHeader, tileGroupHeader, tileDataOffset);

        FrameAssembly assembly = new FrameAssembly(
                sequenceHeader,
                frameHeader,
                frameHeaderObu.streamOffset(),
                frameHeaderObu.obuIndex()
        );
        assembly.addTileGroup(
                tileGroupObu,
                tileGroupHeader,
                tileDataOffset,
                tileGroupPayload.length - tileDataOffset,
                tileBitstreams
        );
        return assembly;
    }

    /// Parses the shared reduced still-picture sequence header used by the legacy fixtures.
    ///
    /// @return the parsed reduced still-picture sequence header
    /// @throws IOException if the fixture cannot be parsed
    private static SequenceHeader parseReducedStillPictureSequenceHeader() throws IOException {
        return new SequenceHeaderParser().parse(sequenceHeaderObu(reducedStillPicturePayload()), false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat) {
        return createAssembly(pixelFormat, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat, boolean filterIntraEnabled) {
        return createAssembly(pixelFormat, new byte[0], 8, 8, FrameHeader.TransformMode.LARGEST, filterIntraEnabled);
    }

    /// Creates one synthetic single-tile frame assembly whose sequence explicitly enables screen
    /// content tools for palette-mode integration coverage.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return one synthetic single-tile frame assembly with palette-capable sequence features
    private static FrameAssembly createPaletteEnabledAssembly(PixelFormat pixelFormat) {
        return createPaletteEnabledAssembly(pixelFormat, 8, 8, FrameHeader.TransformMode.LARGEST);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return createAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode,
            boolean filterIntraEnabled
    ) {
        SequenceHeader sequenceHeader = createSyntheticSequenceHeader(pixelFormat, codedWidth, codedHeight, filterIntraEnabled);
        FrameHeader frameHeader = createSyntheticFrameHeader(FrameType.KEY, codedWidth, codedHeight, 1, transformMode);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
    }

    /// Asserts that the deterministic real `I420` inter fixture reconstructs through one requested
    /// fixed-filter subpel path against one stored checkerboard reference surface.
    ///
    /// @param subpelMotionVector the primary motion vector to inject into the decoded leaf
    /// @param interpolationFilter the frame-level interpolation filter to expose through the assembly
    private static void assertBitstreamDerivedI420InterLeafWithSubpelPrediction(
            MotionVector subpelMotionVector,
            FrameHeader.InterpolationFilter interpolationFilter
    ) {
        FrameAssembly assembly = createInterAssembly(
                PixelFormat.I420,
                BITSTREAM_DERIVED_INTER_PAYLOAD,
                64,
                64,
                0,
                interpolationFilter
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = new FrameSyntaxDecoder(null).decode(assembly);
        TilePartitionTreeReader.LeafNode decodedLeaf = firstLeaf(syntaxDecodeResult.tileRoots(0));

        assertFalse(decodedLeaf.header().skip());
        assertFalse(decodedLeaf.header().intra());
        assertFalse(decodedLeaf.header().compoundReference());
        assertEquals(0, decodedLeaf.header().referenceFrame0());
        assertEquals(interpolationFilter, syntaxDecodeResult.assembly().frameHeader().subpelFilterMode());

        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                copyBlockHeaderWithPrimaryMotionVector(decodedLeaf.header(), subpelMotionVector),
                decodedLeaf.transformLayout(),
                createResidualLayout(
                        decodedLeaf.header().position(),
                        decodedLeaf.header().size(),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().lumaUnits()),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().chromaUUnits()),
                        copyResidualUnitsAsAllZero(decodedLeaf.residualLayout().chromaVUnits())
                )
        );
        FrameSyntaxDecodeResult zeroResidualSyntaxDecodeResult = new FrameSyntaxDecodeResult(
                syntaxDecodeResult.assembly(),
                new TilePartitionTreeReader.Node[][]{{zeroResidualLeaf}},
                syntaxDecodeResult.decodedTemporalMotionFields(),
                syntaxDecodeResult.finalTileCdfContexts()
        );

        DecodedPlane referenceLumaPlane = createCheckerboardPlane(64, 64, 16, 208);
        DecodedPlane referenceChromaUPlane = createCheckerboardPlane(32, 32, 40, 200);
        DecodedPlane referenceChromaVPlane = createCheckerboardPlane(32, 32, 96, 160);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createStoredReferenceSurfaceSnapshot(
                PixelFormat.I420,
                64,
                64,
                referenceLumaPlane,
                referenceChromaUPlane,
                referenceChromaVPlane
        );

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(
                zeroResidualSyntaxDecodeResult,
                createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
        );

        int lumaOriginX = zeroResidualLeaf.header().position().x4() << 2;
        int lumaOriginY = zeroResidualLeaf.header().position().y4() << 2;
        int visibleLumaWidth = zeroResidualLeaf.transformLayout().visibleWidthPixels();
        int visibleLumaHeight = zeroResidualLeaf.transformLayout().visibleHeightPixels();
        int chromaOriginX = lumaOriginX >> 1;
        int chromaOriginY = lumaOriginY >> 1;
        int visibleChromaWidth = visibleLumaWidth >> 1;
        int visibleChromaHeight = visibleLumaHeight >> 1;
        DecodedPlane decodedChromaUPlane = requirePlane(decodedPlanes.chromaUPlane());
        DecodedPlane decodedChromaVPlane = requirePlane(decodedPlanes.chromaVPlane());

        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertTrue(decodedPlanes.hasChroma());
        assertEquals(subpelMotionVector, requireNonNullInterMotionVector(zeroResidualLeaf.header().motionVector0()).vector());
        assertPlaneBlockEquals(
                decodedPlanes.lumaPlane(),
                lumaOriginX,
                lumaOriginY,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceLumaPlane,
                        visibleLumaWidth,
                        visibleLumaHeight,
                        lumaOriginX * 4 + subpelMotionVector.columnQuarterPel(),
                        lumaOriginY * 4 + subpelMotionVector.rowQuarterPel(),
                        4,
                        4,
                        visibleLumaWidth,
                        visibleLumaHeight,
                        interpolationFilter
                )
        );
        assertPlaneBlockEquals(
                decodedChromaUPlane,
                chromaOriginX,
                chromaOriginY,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceChromaUPlane,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        chromaOriginX * 8 + subpelMotionVector.columnQuarterPel(),
                        chromaOriginY * 8 + subpelMotionVector.rowQuarterPel(),
                        8,
                        8,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        interpolationFilter
                )
        );
        assertPlaneBlockEquals(
                decodedChromaVPlane,
                chromaOriginX,
                chromaOriginY,
                InterPredictionOracle.sampleReferencePlaneBlock(
                        referenceChromaVPlane,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        chromaOriginX * 8 + subpelMotionVector.columnQuarterPel(),
                        chromaOriginY * 8 + subpelMotionVector.rowQuarterPel(),
                        8,
                        8,
                        visibleChromaWidth,
                        visibleChromaHeight,
                        interpolationFilter
                )
        );
    }

    /// Creates one synthetic single-tile inter frame assembly whose first direct reference points
    /// at the requested stored slot.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @return one synthetic single-tile inter frame assembly
    private static FrameAssembly createInterAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            int referenceSlot
    ) {
        return createInterAssembly(
                pixelFormat,
                payload,
                codedWidth,
                codedHeight,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one synthetic single-tile compound-inter frame assembly whose first two direct
    /// references point at the requested stored slots.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param referenceSlot0 the stored reference slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored reference slot exposed as `LAST2_FRAME`
    /// @return one synthetic single-tile compound-inter frame assembly
    private static FrameAssembly createInterAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            int referenceSlot0,
            int referenceSlot1
    ) {
        return createInterAssembly(
                pixelFormat,
                payload,
                codedWidth,
                codedHeight,
                referenceSlot0,
                referenceSlot1,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one synthetic single-tile compound-inter frame assembly whose first two direct
    /// references point at the requested stored slots and whose frame header exposes one requested
    /// subpel filter.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param referenceSlot0 the stored reference slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored reference slot exposed as `LAST2_FRAME`
    /// @param subpelFilterMode the frame-level subpel interpolation filter
    /// @return one synthetic single-tile compound-inter frame assembly
    private static FrameAssembly createInterAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter subpelFilterMode
    ) {
        SequenceHeader sequenceHeader = createSyntheticSequenceHeader(pixelFormat, codedWidth, codedHeight, false);
        FrameHeader frameHeader = createSyntheticInterFrameHeader(
                codedWidth,
                codedHeight,
                referenceSlot0,
                referenceSlot1,
                subpelFilterMode
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
    }

    /// Creates one synthetic single-tile inter frame assembly whose first direct reference points
    /// at the requested stored slot and whose frame header exposes one requested subpel filter.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param subpelFilterMode the frame-level subpel interpolation filter
    /// @return one synthetic single-tile inter frame assembly
    private static FrameAssembly createInterAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            int referenceSlot,
            FrameHeader.InterpolationFilter subpelFilterMode
    ) {
        SequenceHeader sequenceHeader = createSyntheticSequenceHeader(pixelFormat, codedWidth, codedHeight, false);
        FrameHeader frameHeader = createSyntheticInterFrameHeader(
                codedWidth,
                codedHeight,
                referenceSlot,
                subpelFilterMode
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
    }

    /// Creates one synthetic single-tile frame assembly whose sequence explicitly enables screen
    /// content tools for palette-mode integration coverage.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one synthetic single-tile frame assembly with palette-capable sequence features
    private static FrameAssembly createPaletteEnabledAssembly(
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return createPaletteEnabledAssembly(pixelFormat, new byte[0], codedWidth, codedHeight, transformMode);
    }

    /// Creates one synthetic single-tile frame assembly whose sequence explicitly enables screen
    /// content tools for palette-mode integration coverage.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one synthetic single-tile frame assembly with palette-capable sequence features
    private static FrameAssembly createPaletteEnabledAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        SequenceHeader sequenceHeader = createSyntheticSequenceHeader(
                pixelFormat,
                codedWidth,
                codedHeight,
                false,
                SequenceHeader.AdaptiveBoolean.ON
        );
        FrameHeader frameHeader = createSyntheticFrameHeader(
                FrameType.KEY,
                codedWidth,
                codedHeight,
                1,
                transformMode,
                true
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
    }

    /// Creates one synthetic sequence header for reconstruction integration tests.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic sequence header for reconstruction integration tests
    private static SequenceHeader createSyntheticSequenceHeader(
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            boolean filterIntraEnabled
    ) {
        return createSyntheticSequenceHeader(
                pixelFormat,
                codedWidth,
                codedHeight,
                filterIntraEnabled,
                SequenceHeader.AdaptiveBoolean.OFF
        );
    }

    /// Creates one synthetic sequence header for reconstruction integration tests.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @param screenContentTools the synthetic screen-content-tools mode
    /// @return one synthetic sequence header for reconstruction integration tests
    private static SequenceHeader createSyntheticSequenceHeader(
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            boolean filterIntraEnabled,
            SequenceHeader.AdaptiveBoolean screenContentTools
    ) {
        boolean monochrome = pixelFormat == PixelFormat.I400;
        return new SequenceHeader(
                0,
                codedWidth,
                codedHeight,
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
                        filterIntraEnabled,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        screenContentTools,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        0,
                        false,
                        false,
                        false,
                        false
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        monochrome,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat == PixelFormat.I420 || pixelFormat == PixelFormat.I422,
                        pixelFormat == PixelFormat.I420,
                        false
                )
        );
    }

    /// Creates one synthetic frame header for reconstruction integration tests.
    ///
    /// @param frameType the frame type to expose
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param tileColumns the number of horizontal tiles to expose
    /// @return one synthetic frame header for reconstruction integration tests
    private static FrameHeader createSyntheticFrameHeader(
            FrameType frameType,
            int codedWidth,
            int codedHeight,
            int tileColumns
    ) {
        return createSyntheticFrameHeader(
                frameType,
                codedWidth,
                codedHeight,
                tileColumns,
                FrameHeader.TransformMode.LARGEST,
                false
        );
    }

    /// Creates one synthetic frame header for reconstruction integration tests.
    ///
    /// @param frameType the frame type to expose
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param tileColumns the number of horizontal tiles to expose
    /// @param transformMode the frame transform mode
    /// @return one synthetic frame header for reconstruction integration tests
    private static FrameHeader createSyntheticFrameHeader(
            FrameType frameType,
            int codedWidth,
            int codedHeight,
            int tileColumns,
            FrameHeader.TransformMode transformMode
    ) {
        return createSyntheticFrameHeader(frameType, codedWidth, codedHeight, tileColumns, transformMode, false);
    }

    /// Creates one synthetic frame header for reconstruction integration tests.
    ///
    /// @param frameType the frame type to expose
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param tileColumns the number of horizontal tiles to expose
    /// @param transformMode the frame transform mode
    /// @param allowScreenContentTools whether the synthetic frame enables screen-content tools
    /// @return one synthetic frame header for reconstruction integration tests
    private static FrameHeader createSyntheticFrameHeader(
            FrameType frameType,
            int codedWidth,
            int codedHeight,
            int tileColumns,
            FrameHeader.TransformMode transformMode,
            boolean allowScreenContentTools
    ) {
        int[] columnStarts = new int[tileColumns + 1];
        for (int i = 0; i <= tileColumns; i++) {
            columnStarts[i] = i;
        }
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
                allowScreenContentTools,
                true,
                false,
                7,
                0,
                0xFF,
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, codedWidth),
                false,
                false,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                false,
                true,
                new FrameHeader.TilingInfo(
                        true,
                        tileColumns > 1 ? 1 : 0,
                        tileColumns > 1 ? 1 : 0,
                        tileColumns > 1 ? 1 : 0,
                        tileColumns > 1 ? 1 : 0,
                        tileColumns,
                        0,
                        0,
                        0,
                        1,
                        columnStarts,
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
                transformMode,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                false
        );
    }

    /// Creates one synthetic inter frame header for reference-surface reconstruction tests.
    ///
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @return one synthetic inter frame header for reference-surface reconstruction tests
    private static FrameHeader createSyntheticInterFrameHeader(
            int codedWidth,
            int codedHeight,
            int referenceSlot
    ) {
        return createSyntheticInterFrameHeader(
                codedWidth,
                codedHeight,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
    }

    /// Creates one synthetic inter frame header for reference-surface reconstruction tests.
    ///
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param subpelFilterMode the frame-level subpel interpolation filter
    /// @return one synthetic inter frame header for reference-surface reconstruction tests
    private static FrameHeader createSyntheticInterFrameHeader(
            int codedWidth,
            int codedHeight,
            int referenceSlot,
            FrameHeader.InterpolationFilter subpelFilterMode
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.INTER,
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
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, codedWidth),
                false,
                false,
                subpelFilterMode,
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
                false
        );
    }

    /// Creates one synthetic inter frame header for reference-surface reconstruction tests with
    /// two stored references and one caller-supplied subpel filter.
    ///
    /// @param codedWidth the coded and rendered frame width
    /// @param codedHeight the coded and rendered frame height
    /// @param referenceSlot0 the stored reference slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored reference slot exposed as `LAST2_FRAME`
    /// @param subpelFilterMode the frame-level subpel interpolation filter
    /// @return one synthetic inter frame header for compound-reference reconstruction tests
    private static FrameHeader createSyntheticInterFrameHeader(
            int codedWidth,
            int codedHeight,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter subpelFilterMode
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.INTER,
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
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, codedWidth),
                false,
                false,
                subpelFilterMode,
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
                false
        );
    }

    /// Returns the fixed fixture payload whose first residual flags match the requested sequence.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @return a small payload whose first residual flags match the requested sequence
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags) {
        if (blockSize == BlockSize.SIZE_4X4 && expectedFlags.length == 1 && expectedFlags[0]) {
            return readFrameReconstructorFixture("4x4-all-zero");
        }
        throw new IllegalArgumentException("Unsupported residual-flag fixture request");
    }

    /// Returns the fixed fixture payload whose first residual unit is supported and DC-only.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return a small payload whose first residual unit is supported and DC-only
    private static byte[] findPayloadForDcOnlyResidual(BlockSize blockSize) {
        if (blockSize == BlockSize.SIZE_4X4) {
            return readFrameReconstructorFixture("4x4-dc-only");
        }
        throw new IllegalArgumentException("Unsupported DC-only residual fixture request");
    }

    /// Reads one named frame-reconstructor integration fixture payload.
    ///
    /// @param fixtureName the logical fixture name in `frame-reconstructor-integration-fixtures.txt`
    /// @return the decoded frame-reconstructor integration payload bytes
    private static byte[] readFrameReconstructorFixture(String fixtureName) {
        return HexFixtureResources.readNamedBytes(FRAME_RECONSTRUCTOR_FIXTURE_RESOURCE_PATH, fixtureName);
    }

    /// Creates one tile-local decode context used by monochrome residual integration helpers.
    ///
    /// @param payload the collected tile entropy payload
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context used by monochrome residual integration helpers
    private static TileDecodeContext createTileContext(byte[] payload, FrameHeader.TransformMode transformMode) {
        return TileDecodeContext.create(createAssembly(PixelFormat.I400, payload, 64, 64, transformMode), 0);
    }

    /// Creates one tile-local decode context used by chroma integration helpers.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context used by chroma integration helpers
    private static TileDecodeContext createTileContext(
            byte[] payload,
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return TileDecodeContext.create(createAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode), 0);
    }

    /// Creates one tile-local decode context that keeps screen-content tools enabled for real
    /// palette integration fixtures.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context with palette-capable sequence features
    private static TileDecodeContext createPaletteEnabledTileContext(
            byte[] payload,
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return TileDecodeContext.create(
                createPaletteEnabledAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode),
                0
        );
    }

    /// Creates a reduced still-picture sequence-header OBU packet.
    ///
    /// @param payload the sequence-header payload
    /// @return one reduced still-picture sequence-header OBU packet
    private static ObuPacket sequenceHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0), payload, 0, 0);
    }

    /// Creates a reduced still-picture standalone frame-header OBU packet.
    ///
    /// @param payload the standalone frame-header payload
    /// @return one reduced still-picture standalone frame-header OBU packet
    private static ObuPacket frameHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME_HEADER, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture combined `FRAME` OBU packet.
    ///
    /// @param payload the combined frame payload
    /// @return one reduced still-picture combined `FRAME` OBU packet
    private static ObuPacket frameObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture standalone `TILE_GROUP` OBU packet.
    ///
    /// @param payload the standalone tile-group payload
    /// @return one reduced still-picture standalone `TILE_GROUP` OBU packet
    private static ObuPacket tileGroupObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), payload, 0, 2);
    }

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload() {
        return reducedStillPicturePayload(64, 64);
    }

    /// Creates a reduced still-picture sequence header payload for caller-supplied frame dimensions.
    ///
    /// @param width the coded frame width in pixels
    /// @param height the coded frame height in pixels
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload(int width, int height) {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(width - 1L, 10);
        writer.writeBits(height - 1L, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture standalone frame-header payload.
    ///
    /// @return the reduced still-picture standalone frame-header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture combined frame payload with a caller-supplied tile group.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return the reduced still-picture combined frame payload
    private static byte[] reducedStillPictureCombinedFramePayload(byte[] tileGroupPayload) {
        return reducedStillPictureCombinedFramePayload(tileGroupPayload, false);
    }

    /// Creates a reduced still-picture combined frame payload with a caller-supplied tile group and
    /// optional multi-tile tiling syntax.
    ///
    /// @param tileGroupPayload the tile-group payload appended after the frame header
    /// @param twoHorizontalTiles whether the frame header should expose two horizontal tiles
    /// @return the reduced still-picture combined frame payload
    private static byte[] reducedStillPictureCombinedFramePayload(byte[] tileGroupPayload, boolean twoHorizontalTiles) {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer, twoHorizontalTiles);
        writer.padToByteBoundary();
        writer.writeBytes(tileGroupPayload);
        return writer.toByteArray();
    }

    /// Creates the legacy minimal single-tile tile-group payload.
    ///
    /// @return the legacy minimal single-tile tile-group payload
    private static byte[] singleTileGroupPayload() {
        return new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
    }

    /// Creates one minimal two-tile payload by concatenating a size table for the first tile and
    /// both raw tile bitstreams after an implicit full-range tile-group header byte.
    ///
    /// @param leftTilePayload the raw payload of tile `0`
    /// @param rightTilePayload the raw payload of tile `1`
    /// @return the tile-group payload for one implicit full-range two-tile group
    private static byte[] twoTileGroupPayload(byte[] leftTilePayload, byte[] rightTilePayload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        output.write(leftTilePayload.length - 1);
        output.writeBytes(leftTilePayload);
        output.writeBytes(rightTilePayload);
        return output.toByteArray();
    }

    /// Writes the reduced still-picture key-frame header syntax without standalone trailing bits.
    ///
    /// @param writer the destination bit writer
    private static void writeReducedStillPictureFrameHeaderBits(BitWriter writer) {
        writeReducedStillPictureFrameHeaderBits(writer, false);
    }

    /// Writes the reduced still-picture key-frame header syntax without standalone trailing bits.
    ///
    /// @param writer the destination bit writer
    /// @param twoHorizontalTiles whether the frame header should expose two horizontal tiles
    private static void writeReducedStillPictureFrameHeaderBits(BitWriter writer, boolean twoHorizontalTiles) {
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        if (twoHorizontalTiles) {
            writer.writeFlag(true);
            writer.writeFlag(false);
            writer.writeBits(0, 2);
        } else {
            writer.writeFlag(false);
        }
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
    }

    /// Returns the first leaf node in raster order from one tile-root array.
    ///
    /// @param roots the top-level tile roots
    /// @return the first leaf node in raster order
    private static TilePartitionTreeReader.LeafNode firstLeaf(TilePartitionTreeReader.Node[] roots) {
        for (TilePartitionTreeReader.Node root : roots) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(root);
            if (leaf != null) {
                return leaf;
            }
        }
        throw new IllegalStateException("No leaf nodes were produced");
    }

    /// Returns the first leaf node in raster order from one subtree, or `null`.
    ///
    /// @param node the subtree root
    /// @return the first leaf node in raster order from one subtree, or `null`
    private static TilePartitionTreeReader.@org.jetbrains.annotations.Nullable LeafNode firstLeaf(
            TilePartitionTreeReader.Node node
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            return leafNode;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(child);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    /// Creates one stored reference surface snapshot for inter reconstruction integration tests.
    ///
    /// @param pixelFormat the decoded chroma layout stored by the snapshot
    /// @param codedWidth the coded width stored by the snapshot
    /// @param codedHeight the coded height stored by the snapshot
    /// @param lumaPlane the luma plane stored by the snapshot
    /// @param chromaUPlane the chroma-U plane stored by the snapshot, or `null`
    /// @param chromaVPlane the chroma-V plane stored by the snapshot, or `null`
    /// @return one stored reference surface snapshot for inter reconstruction integration tests
    private static ReferenceSurfaceSnapshot createStoredReferenceSurfaceSnapshot(
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            DecodedPlane lumaPlane,
            @Nullable DecodedPlane chromaUPlane,
            @Nullable DecodedPlane chromaVPlane
    ) {
        FrameAssembly assembly = createAssembly(pixelFormat, new byte[0], codedWidth, codedHeight, FrameHeader.TransformMode.LARGEST);
        FrameSyntaxDecodeResult syntaxDecodeResult = new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{new TilePartitionTreeReader.Node[0]},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
        return new ReferenceSurfaceSnapshot(
                assembly.frameHeader(),
                syntaxDecodeResult,
                new DecodedPlanes(
                        8,
                        pixelFormat,
                        codedWidth,
                        codedHeight,
                        codedWidth,
                        codedHeight,
                        lumaPlane,
                        chromaUPlane,
                        chromaVPlane
                )
        );
    }

    /// Creates one slot-indexed stored-reference array for inter reconstruction integration tests.
    ///
    /// @param slot the zero-based reference slot to populate
    /// @param referenceSurfaceSnapshot the stored reference surface to expose
    /// @return one slot-indexed stored-reference array for inter reconstruction integration tests
    private static ReferenceSurfaceSnapshot[] createReferenceSurfaceSlots(
            int slot,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot
    ) {
        ReferenceSurfaceSnapshot[] slots = new ReferenceSurfaceSnapshot[8];
        slots[slot] = referenceSurfaceSnapshot;
        return slots;
    }

    /// Creates one slot-indexed stored-reference array with two populated entries.
    ///
    /// @param slot0 the first zero-based reference slot to populate
    /// @param referenceSurfaceSnapshot0 the stored reference surface exposed through `slot0`
    /// @param slot1 the second zero-based reference slot to populate
    /// @param referenceSurfaceSnapshot1 the stored reference surface exposed through `slot1`
    /// @return one slot-indexed stored-reference array for compound reconstruction integration tests
    private static ReferenceSurfaceSnapshot[] createReferenceSurfaceSlots(
            int slot0,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot0,
            int slot1,
            ReferenceSurfaceSnapshot referenceSurfaceSnapshot1
    ) {
        ReferenceSurfaceSnapshot[] slots = new ReferenceSurfaceSnapshot[8];
        slots[slot0] = referenceSurfaceSnapshot0;
        slots[slot1] = referenceSurfaceSnapshot1;
        return slots;
    }

    /// Returns one decoded inter block header with a replaced resolved primary motion vector.
    ///
    /// @param header the decoded inter block header to copy
    /// @param motionVector the replacement primary motion vector
    /// @return one decoded inter block header with a replaced resolved primary motion vector
    private static TileBlockHeaderReader.BlockHeader copyBlockHeaderWithPrimaryMotionVector(
            TileBlockHeaderReader.BlockHeader header,
            MotionVector motionVector
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                header.position(),
                header.size(),
                header.hasChroma(),
                header.skip(),
                header.skipMode(),
                header.intra(),
                header.useIntrabc(),
                header.compoundReference(),
                header.referenceFrame0(),
                header.referenceFrame1(),
                header.singleInterMode(),
                header.compoundInterMode(),
                header.drlIndex(),
                InterMotionVector.resolved(motionVector),
                header.motionVector1(),
                header.horizontalInterpolationFilter(),
                header.verticalInterpolationFilter(),
                header.segmentPredicted(),
                header.segmentId(),
                header.cdefIndex(),
                header.qIndex(),
                header.deltaLfValues(),
                header.yMode(),
                header.uvMode(),
                header.yPaletteSize(),
                header.uvPaletteSize(),
                header.yPaletteColors(),
                header.uPaletteColors(),
                header.vPaletteColors(),
                header.yPaletteIndices(),
                header.uvPaletteIndices(),
                header.filterIntraMode(),
                header.yAngle(),
                header.uvAngle(),
                header.cflAlphaU(),
                header.cflAlphaV()
        );
    }

    /// Creates one exact decoded checkerboard plane.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param evenSample the sample value stored at even `(x + y)` parity
    /// @param oddSample the sample value stored at odd `(x + y)` parity
    /// @return one exact decoded checkerboard plane
    private static DecodedPlane createCheckerboardPlane(int width, int height, int evenSample, int oddSample) {
        short[] samples = new short[width * height];
        int nextIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[nextIndex++] = (short) ((((x + y) & 1) == 0) ? evenSample : oddSample);
            }
        }
        return new DecodedPlane(width, height, width, samples);
    }

    /// Creates one exact decoded gradient plane.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param baseValue the sample value at `(0, 0)`
    /// @param xStep the per-column delta
    /// @param yStep the per-row delta
    /// @return one exact decoded gradient plane
    private static DecodedPlane createGradientPlane(int width, int height, int baseValue, int xStep, int yStep) {
        short[] samples = new short[width * height];
        int nextIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[nextIndex++] = (short) (baseValue + x * xStep + y * yStep);
            }
        }
        return new DecodedPlane(width, height, width, samples);
    }

    /// Samples one rectangular reference-plane block using bilinear interpolation with edge
    /// extension.
    ///
    /// @param referencePlane the immutable reference plane
    /// @param width the sampled block width in samples
    /// @param height the sampled block height in samples
    /// @param sourceNumeratorX the source origin numerator in plane-local sample units
    /// @param sourceNumeratorY the source origin numerator in plane-local sample units
    /// @param denominatorX the horizontal plane-local denominator
    /// @param denominatorY the vertical plane-local denominator
    /// @return one sampled reference-plane block in row-major order
    private static int[][] sampleReferencePlaneBlockBilinearly(
            DecodedPlane referencePlane,
            int width,
            int height,
            int sourceNumeratorX,
            int sourceNumeratorY,
            int denominatorX,
            int denominatorY
    ) {
        int[][] samples = new int[height][width];
        for (int y = 0; y < height; y++) {
            int sampleNumeratorY = sourceNumeratorY + y * denominatorY;
            int sourceY0 = Math.floorDiv(sampleNumeratorY, denominatorY);
            int fractionY = Math.floorMod(sampleNumeratorY, denominatorY);
            int clampedSourceY0 = Math.max(0, Math.min(sourceY0, referencePlane.height() - 1));
            int clampedSourceY1 = Math.max(0, Math.min(sourceY0 + 1, referencePlane.height() - 1));
            for (int x = 0; x < width; x++) {
                int sampleNumeratorX = sourceNumeratorX + x * denominatorX;
                int sourceX0 = Math.floorDiv(sampleNumeratorX, denominatorX);
                int fractionX = Math.floorMod(sampleNumeratorX, denominatorX);
                int clampedSourceX0 = Math.max(0, Math.min(sourceX0, referencePlane.width() - 1));
                int clampedSourceX1 = Math.max(0, Math.min(sourceX0 + 1, referencePlane.width() - 1));
                samples[y][x] = bilinearInterpolate(
                        referencePlane.sample(clampedSourceX0, clampedSourceY0),
                        referencePlane.sample(clampedSourceX1, clampedSourceY0),
                        referencePlane.sample(clampedSourceX0, clampedSourceY1),
                        referencePlane.sample(clampedSourceX1, clampedSourceY1),
                        fractionX,
                        denominatorX,
                        fractionY,
                        denominatorY
                );
            }
        }
        return samples;
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

    /// Returns all supplied residual units converted to all-zero units with preserved geometry.
    ///
    /// @param residualUnits the residual units whose geometry should be preserved
    /// @return all supplied residual units converted to all-zero units with preserved geometry
    private static TransformResidualUnit[] copyResidualUnitsAsAllZero(TransformResidualUnit[] residualUnits) {
        TransformResidualUnit[] zeroUnits = new TransformResidualUnit[residualUnits.length];
        for (int i = 0; i < residualUnits.length; i++) {
            TransformResidualUnit residualUnit = residualUnits[i];
            zeroUnits[i] = createAllZeroResidualUnit(
                    residualUnit.position(),
                    residualUnit.size(),
                    residualUnit.visibleWidthPixels(),
                    residualUnit.visibleHeightPixels()
            );
        }
        return zeroUnits;
    }

    /// Returns every leaf node from one tile-root array in raster reconstruction order.
    ///
    /// @param roots the top-level tile roots
    /// @return every leaf node from one tile-root array in raster reconstruction order
    private static List<TilePartitionTreeReader.LeafNode> leavesInRasterOrder(TilePartitionTreeReader.Node[] roots) {
        List<TilePartitionTreeReader.LeafNode> leaves = new ArrayList<>();
        for (TilePartitionTreeReader.Node root : roots) {
            appendLeavesInRasterOrder(root, leaves);
        }
        return leaves;
    }

    /// Appends every leaf node from one subtree in raster reconstruction order.
    ///
    /// @param node the subtree root
    /// @param leaves the destination list for leaf nodes
    private static void appendLeavesInRasterOrder(
            TilePartitionTreeReader.Node node,
            List<TilePartitionTreeReader.LeafNode> leaves
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            leaves.add(leafNode);
            return;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            appendLeavesInRasterOrder(child, leaves);
        }
    }

    /// Creates default per-segment feature data with every feature disabled.
    ///
    /// @return default per-segment feature data with every feature disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// Returns the stored coefficient-context byte for one non-zero DC coefficient.
    ///
    /// @param signedDcCoefficient the signed DC coefficient
    /// @return the stored coefficient-context byte for one non-zero DC coefficient
    private static int expectedNonZeroCoefficientContextByte(int signedDcCoefficient) {
        return Math.min(Math.abs(signedDcCoefficient), 63) | (signedDcCoefficient > 0 ? 0x80 : 0);
    }

    /// Small MSB-first bit writer used to build AV1 test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Writes trailing bits and byte-alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Pads the current byte with zero bits until the next byte boundary.
        private void padToByteBoundary() {
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Writes raw bytes after the current bitstream has been byte aligned.
        ///
        /// @param bytes the raw bytes to append
        private void writeBytes(byte[] bytes) {
            if (bitCount != 0) {
                throw new IllegalStateException("BitWriter is not byte aligned");
            }
            output.writeBytes(bytes);
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
