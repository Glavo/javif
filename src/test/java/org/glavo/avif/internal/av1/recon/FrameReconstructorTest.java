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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
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
import org.glavo.avif.testutil.InterPredictionOracle;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the first reconstruction-capable frame path.
@NotNullByDefault
final class FrameReconstructorTest {
    /// Verifies that a synthetic single-tile 8-bit `I400` key frame reconstructs into one opaque luma plane.
    @Test
    void reconstructsSingleTileI400KeyFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(PixelFormat.I400, planes.pixelFormat());
        assertFalse(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
    }

    /// Verifies that a synthetic single-tile 8-bit `I420` intra frame reconstructs luma and chroma planes.
    @Test
    void reconstructsSingleTileI420IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(PixelFormat.I420, planes.pixelFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        DecodedPlane chromaU = planes.chromaUPlane();
        DecodedPlane chromaV = planes.chromaVPlane();
        assertNotNull(chromaU);
        assertNotNull(chromaV);
        assertPlaneFilled(chromaU, 2, 2, 128);
        assertPlaneFilled(chromaV, 2, 2, 128);
    }

    /// Verifies that a synthetic single-tile 8-bit `I422` intra frame reconstructs luma and full-height
    /// half-width chroma planes.
    @Test
    void reconstructsSingleTileI422IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I422),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I422, FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(PixelFormat.I422, planes.pixelFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 2, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 2, 4, 128);
    }

    /// Verifies that a synthetic single-tile 8-bit `I444` intra frame reconstructs luma and full-size
    /// chroma planes.
    @Test
    void reconstructsSingleTileI444IntraFrameWithZeroResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I444),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I444, FrameType.INTRA, 4, 4, leaf)
        );

        assertEquals(8, planes.bitDepth());
        assertEquals(PixelFormat.I444, planes.pixelFormat());
        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 4, 4, 128);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 4, 4, 128);
    }

    /// Verifies that one `I400` luma palette block reconstructs the stored palette raster without
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 8, 8, leaf)
        );

        assertFalse(planes.hasChroma());
        assertPlaneEquals(planes.lumaPlane(), expandPaletteRaster(lumaPaletteColors, lumaPaletteIndices));
    }

    /// Verifies that one `I420` chroma palette block reconstructs the stored chroma palette rasters
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
    }

    /// Verifies that one `I422` chroma palette block reconstructs the stored full-height chroma
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
                createTransformLayout(position, size, PixelFormat.I422),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I422, FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
    }

    /// Verifies that one `I444` chroma palette block reconstructs the stored full-resolution
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
                createTransformLayout(position, size, PixelFormat.I444),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I444, FrameType.KEY, 8, 8, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneFilled(planes.lumaPlane(), 8, 8, 128);
        assertPlaneEquals(requirePlane(planes.chromaUPlane()), expandPaletteRaster(chromaPaletteU, chromaPaletteIndices));
        assertPlaneEquals(requirePlane(planes.chromaVPlane()), expandPaletteRaster(chromaPaletteV, chromaPaletteIndices));
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode residualLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 8, 8, baselineLeaf)
        );
        DecodedPlanes residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 8, 8, residualLeaf)
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 4, 4, zeroResidualLeaf)
        ).lumaPlane();
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 4, 4, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that a negative `I420` luma DC residual shifts only the luma plane below the zero-residual baseline.
    @Test
    void reconstructsSingleTileI420IntraFrameWithNegativeLumaDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode negativeResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.INTRA, 4, 4, negativeResidualLeaf)
        );

        assertTrue(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline.lumaPlane(), residualPlanes.lumaPlane(), -1);
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residualPlanes.chromaUPlane()));
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one positive `I422` chroma-U DC residual shifts only the U plane while keeping
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
                createTransformLayout(position, size, PixelFormat.I422),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, PixelFormat.I422),
                createChromaDcResidualLayout(position, size, PixelFormat.I422, 64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I422, FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I422, FrameType.INTRA, 4, 4, positiveChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one negative `I444` chroma-V DC residual shifts only the V plane while keeping
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
                createTransformLayout(position, size, PixelFormat.I444),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode negativeChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, PixelFormat.I444),
                createChromaDcResidualLayout(position, size, PixelFormat.I444, 0, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I444, FrameType.INTRA, 4, 4, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I444, FrameType.INTRA, 4, 4, negativeChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residualPlanes.chromaUPlane()));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residualPlanes.chromaVPlane()),
                -1
        );
    }

    /// Verifies that one `TX_16X16` luma DC residual now reconstructs successfully through the
    /// first-pixel path.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveSixteenBySixteenDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X16;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 16, 16, zeroResidualLeaf)
        ).lumaPlane();
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 16, 16, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one rectangular `RTX_16X8` luma DC residual now reconstructs successfully
    /// through the first-pixel path.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveRectangularDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X8;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 16, 8, zeroResidualLeaf)
        ).lumaPlane();
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 16, 8, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one `TX_32X32` luma DC residual now reconstructs successfully through the
    /// first-pixel path.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveThirtyTwoByThirtyTwoDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_32X32;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 32, 32, zeroResidualLeaf)
        ).lumaPlane();
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 32, 32, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one `TX_64X64` luma DC residual now reconstructs successfully through the
    /// first-pixel path.
    @Test
    void reconstructsSingleTileI400KeyFrameWithPositiveSixtyFourBySixtyFourDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_64X64;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveResidualLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 64, 64, zeroResidualLeaf)
        ).lumaPlane();
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 64, 64, positiveResidualLeaf)
        );

        assertFalse(residualPlanes.hasChroma());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(baseline, residualPlanes.lumaPlane(), 1);
    }

    /// Verifies that one larger `I420` chroma residual now reconstructs through the current
    /// wider-transform chroma path without perturbing luma or the V plane.
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );
        TilePartitionTreeReader.LeafNode positiveChromaLeaf = new TilePartitionTreeReader.LeafNode(
                header,
                createTransformLayout(position, size, PixelFormat.I420),
                createChromaDcResidualLayout(position, size, PixelFormat.I420, 64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.INTRA, 64, 64, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.INTRA, 64, 64, positiveChromaLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that the first reconstruction path now supports filter-intra luma and `I420` CFL chroma.
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 136, 144, 152},
                        {64, 96, 120, 144},
                        {132, 147, 164, 164},
                        {68, 101, 133, 155}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {117, 134},
                        {120, 141}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {139, 122},
                        {136, 115}
                }
        );
    }

    /// Verifies that generalized CFL reconstruction for the current `I422` subset derives chroma
    /// AC from horizontally subsampled reconstructed luma.
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
                createTransformLayout(position, size, PixelFormat.I422),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I422, FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 136, 144, 152},
                        {64, 96, 120, 144},
                        {132, 147, 164, 164},
                        {68, 101, 133, 155}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {130, 138},
                        {104, 130},
                        {134, 146},
                        {106, 136}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {126, 118},
                        {152, 126},
                        {122, 110},
                        {150, 120}
                }
        );
    }

    /// Verifies that generalized CFL reconstruction for the current `I444` subset derives chroma
    /// AC from full-resolution reconstructed luma.
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
                createTransformLayout(position, size, PixelFormat.I444),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I444, FrameType.KEY, 4, 4, leaf)
        );

        assertTrue(planes.hasChroma());
        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {128, 136, 144, 152},
                        {64, 96, 120, 144},
                        {132, 147, 164, 164},
                        {68, 101, 133, 155}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaUPlane()),
                new int[][]{
                        {128, 132, 136, 140},
                        {96, 112, 124, 136},
                        {130, 138, 146, 146},
                        {98, 114, 131, 142}
                }
        );
        assertPlaneEquals(
                requirePlane(planes.chromaVPlane()),
                new int[][]{
                        {128, 124, 120, 116},
                        {160, 144, 132, 120},
                        {126, 118, 110, 110},
                        {158, 142, 125, 114}
                }
        );
    }

    /// Verifies that one synthetic directional `I420` leaf reconstructs successfully after top neighbors are already available.
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
                createTransformLayout(new BlockPosition(0, 0), BlockSize.SIZE_4X4, PixelFormat.I420),
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
                createTransformLayout(new BlockPosition(1, 0), BlockSize.SIZE_4X4, PixelFormat.I420),
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
                createTransformLayout(new BlockPosition(0, 1), BlockSize.SIZE_4X4, PixelFormat.I420),
                createResidualLayout(new BlockPosition(0, 1), BlockSize.SIZE_4X4, true)
        );

        DecodedPlanes baseline = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.KEY, 8, 8, topLeftLeaf, topRightLeaf)
        );
        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(
                        PixelFormat.I420,
                        FrameType.KEY,
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

    /// Verifies that one non-zero directional luma leaf adds its residual after directional
    /// prediction without perturbing the already reconstructed reference row.
    @Test
    void reconstructsDirectionalI400LeafWithPositiveResidualOffset() {
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode topLeftLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(0, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(0, 0), size, PixelFormat.I400),
                createResidualLayout(new BlockPosition(0, 0), size, 64)
        );
        TilePartitionTreeReader.LeafNode topMiddleLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(1, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(1, 0), size, PixelFormat.I400),
                createResidualLayout(new BlockPosition(1, 0), size, true)
        );
        TilePartitionTreeReader.LeafNode topRightLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(2, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(2, 0), size, PixelFormat.I400),
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
                createTransformLayout(new BlockPosition(0, 1), size, PixelFormat.I400),
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
                createTransformLayout(new BlockPosition(0, 1), size, PixelFormat.I400),
                createResidualLayout(new BlockPosition(0, 1), size, 64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        PixelFormat.I400,
                        FrameType.KEY,
                        12,
                        8,
                        topLeftLeaf,
                        topMiddleLeaf,
                        topRightLeaf,
                        baselineDirectionalLeaf
                )
        );
        DecodedPlanes residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        PixelFormat.I400,
                        FrameType.KEY,
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

    /// Verifies that two synthetic tile-root arrays can reconstruct into one shared luma plane
    /// without the later tile overwriting the earlier tile's completed region.
    @Test
    void reconstructsSyntheticMultiTileRootsIntoSharedLumaPlaneWithoutCrossTileOverwrite() {
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leftTileLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(0, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(0, 0), size, PixelFormat.I400),
                createResidualLayout(new BlockPosition(0, 0), size, 64)
        );
        TilePartitionTreeReader.LeafNode rightTileLeaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(new BlockPosition(1, 0), size, false, LumaIntraPredictionMode.DC, null, null, 0, 0, 0, 0),
                createTransformLayout(new BlockPosition(1, 0), size, PixelFormat.I400),
                createResidualLayout(new BlockPosition(1, 0), size, -64)
        );
        TilePartitionTreeReader.Node[] tileZeroRoots = new TilePartitionTreeReader.Node[]{leftTileLeaf};
        TilePartitionTreeReader.Node[] tileOneRoots = new TilePartitionTreeReader.Node[]{rightTileLeaf};

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane expectedAfterTileZero = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 8, 4, leftTileLeaf)
        ).lumaPlane();
        DecodedPlane expectedAfterBothTiles = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 8, 4, leftTileLeaf, rightTileLeaf)
        ).lumaPlane();

        MutablePlaneBuffer sharedLumaPlane = new MutablePlaneBuffer(8, 4, 8);
        FrameHeader frameHeader = createFrameHeader(FrameType.KEY, 8, 4);

        reconstructSyntheticTileRootsIntoSharedLumaPlane(sharedLumaPlane, PixelFormat.I400, frameHeader, tileZeroRoots);
        DecodedPlane afterTileZero = sharedLumaPlane.toDecodedPlane();

        assertPlanesEqual(expectedAfterTileZero, afterTileZero);
        assertPlaneBlockFilled(afterTileZero, 4, 0, 4, 4, 0);

        reconstructSyntheticTileRootsIntoSharedLumaPlane(sharedLumaPlane, PixelFormat.I400, frameHeader, tileOneRoots);
        DecodedPlane afterBothTiles = sharedLumaPlane.toDecodedPlane();

        assertPlanesEqual(expectedAfterBothTiles, afterBothTiles);
        assertPlaneRegionEquals(afterTileZero, afterBothTiles, 0, 0, 4, 4);
    }

    /// Verifies that one monochrome single-reference inter block copies samples from one stored
    /// reference surface using an integer-pel motion vector.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithIntegerMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I400,
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
                createSingleReferenceInterBlockHeader(position, size, false, 0, new MotionVector(4, 4)),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(PixelFormat.I400, 8, 8, 0, leaf),
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

    /// Verifies that one `I420` single-reference inter block copies both luma and chroma samples
    /// from one stored reference surface when the motion vector stays chroma-aligned.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithZeroMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I420,
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(PixelFormat.I420, 4, 4, 0, leaf),
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

    /// Verifies that one monochrome compound-reference inter block averages two stored reference
    /// surfaces when both motion vectors stay integer-aligned.
    @Test
    void reconstructsCompoundReferenceI400InterBlockFromStoredSurfacesWithIntegerMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                PixelFormat.I400,
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
                PixelFormat.I400,
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(PixelFormat.I400, 4, 4, 0, 1, leaf),
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

    /// Verifies that one monochrome single-reference inter block bilinearly samples one
    /// fractional luma footprint from one stored reference surface when the frame filter is
    /// `BILINEAR`.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithBilinearSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I400,
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
                createSingleReferenceInterBlockHeader(position, size, false, 0, new MotionVector(2, 6)),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I400,
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

    /// Verifies that one `I420` single-reference inter block bilinearly samples both luma and
    /// chroma footprints from one stored reference surface when the frame filter is `BILINEAR`.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithBilinearSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I420,
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
                createSingleReferenceInterBlockHeader(position, size, true, 0, new MotionVector(2, 6)),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I420,
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

    /// Verifies that one monochrome single-reference inter block samples one stored reference
    /// surface through the current fixed `EIGHT_TAP_REGULAR` subpel path.
    @Test
    void reconstructsSingleReferenceI400InterBlockFromStoredSurfaceWithRegularEightTapSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(3, 1);
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
                PixelFormat.I400,
                lumaSamples,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, false, 0, motionVector),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I400,
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
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        4,
                        4,
                        4,
                        4,
                        FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
                )
        );
        assertPlaneBlockFilled(planes.lumaPlane(), 4, 0, 4, 8, 0);
        assertPlaneBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, 0);
    }

    /// Verifies that one `I420` single-reference inter block samples both luma and chroma
    /// footprints through the current fixed `EIGHT_TAP_SMOOTH` subpel path.
    @Test
    void reconstructsSingleReferenceI420InterBlockFromStoredSurfaceWithSmoothEightTapSubpelMotionVector() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(2, 6);
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
                PixelFormat.I420,
                lumaSamples,
                chromaUSamples,
                chromaVSamples
        );
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createSingleReferenceInterBlockHeader(position, size, true, 0, motionVector),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I420,
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
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        4,
                        4,
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
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        8,
                        8,
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
                        motionVector.columnQuarterPel(),
                        motionVector.rowQuarterPel(),
                        8,
                        8,
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

    /// Verifies that one `I420` compound-reference inter block averages two bilinearly sampled
    /// reference surfaces on both luma and chroma planes.
    @Test
    void reconstructsCompoundReferenceI420InterBlockFromStoredSurfacesWithBilinearSubpelMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector = new MotionVector(2, 2);
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot0 = createReferenceSurfaceSnapshot(
                PixelFormat.I420,
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
                PixelFormat.I420,
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I420,
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

    /// Verifies that one `I420` compound-reference inter block averages two fixed
    /// `EIGHT_TAP_SHARP` subpel predictions on both luma and chroma planes.
    @Test
    void reconstructsCompoundReferenceI420InterBlockFromStoredSurfacesWithSharpEightTapSubpelMotionVectors() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        MotionVector motionVector0 = new MotionVector(3, 1);
        MotionVector motionVector1 = new MotionVector(1, 3);
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
                PixelFormat.I420,
                lumaSamples0,
                chromaUSamples0,
                chromaVSamples0
        );
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot1 = createReferenceSurfaceSnapshot(
                PixelFormat.I420,
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
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(
                        PixelFormat.I420,
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
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(lumaSamples0),
                                4,
                                4,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                4,
                                4,
                                4,
                                4,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(lumaSamples1),
                                4,
                                4,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                4,
                                4,
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
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(chromaUSamples0),
                                2,
                                2,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(chromaUSamples1),
                                2,
                                2,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                8,
                                8,
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
                InterPredictionOracle.averageBlocks(
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(chromaVSamples0),
                                2,
                                2,
                                motionVector0.columnQuarterPel(),
                                motionVector0.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        ),
                        InterPredictionOracle.sampleReferencePlaneBlock(
                                createDecodedPlane(chromaVSamples1),
                                2,
                                2,
                                motionVector1.columnQuarterPel(),
                                motionVector1.rowQuarterPel(),
                                8,
                                8,
                                2,
                                2,
                                FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP
                        )
                )
        );
    }

    /// Verifies that one monochrome inter block still applies supported residuals on top of the
    /// copied reference prediction.
    @Test
    void reconstructsSingleReferenceI400InterBlockWithResidualOverlay() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I400,
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, 64)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createInterFrameSyntaxDecodeResult(PixelFormat.I400, 4, 4, 0, leaf),
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
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createInterFrameSyntaxDecodeResult(PixelFormat.I400, 4, 4, 0, leaf)
                )
        );

        assertEquals("Inter reconstruction requires one populated stored reference surface", exception.getMessage());
    }

    /// Verifies that one chroma-bearing switchable inter block now rejects missing decoded
    /// block-level interpolation filters before evaluating fractional-motion-vector support.
    @Test
    void rejectsI420InterBlocksWithSwitchableFrameFilterWithoutDecodedBlockFilters() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        ReferenceSurfaceSnapshot referenceSurfaceSnapshot = createReferenceSurfaceSnapshot(
                PixelFormat.I420,
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
                createSingleReferenceInterBlockHeader(position, size, true, 0, new MotionVector(2, 6)),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createInterFrameSyntaxDecodeResult(
                                PixelFormat.I420,
                                8,
                                8,
                                0,
                                FrameHeader.InterpolationFilter.SWITCHABLE,
                                leaf
                        ),
                        createReferenceSurfaceSlots(0, referenceSurfaceSnapshot)
                )
        );

        assertEquals("Inter reconstruction requires decoded switchable interpolation filters", exception.getMessage());
    }

    /// Creates one synthetic structural frame-decode result for reconstruction tests.
    ///
    /// @param pixelFormat the decoded chroma layout
    /// @param frameType the frame type to expose through the frame header
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural frame-decode result
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(
            PixelFormat pixelFormat,
            FrameType frameType,
            int width,
            int height,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(pixelFormat, width, height);
        FrameHeader frameHeader = createFrameHeader(frameType, width, height);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
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
    /// @param pixelFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            PixelFormat pixelFormat,
            int width,
            int height,
            int referenceSlot,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(pixelFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(
                width,
                height,
                referenceSlot,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
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
    /// @param pixelFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot exposed as `LAST2_FRAME`
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural compound-inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            PixelFormat pixelFormat,
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            TilePartitionTreeReader.Node... roots
    ) {
        return createInterFrameSyntaxDecodeResult(
                pixelFormat,
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
    /// @param pixelFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot0 the stored slot exposed as `LAST_FRAME`
    /// @param referenceSlot1 the stored slot exposed as `LAST2_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural compound-inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            PixelFormat pixelFormat,
            int width,
            int height,
            int referenceSlot0,
            int referenceSlot1,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(pixelFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot0, referenceSlot1, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
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
    /// @param pixelFormat the decoded chroma layout
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param referenceSlot the stored reference slot exposed as `LAST_FRAME`
    /// @param interpolationFilter the frame-level interpolation filter used for inter prediction
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic structural inter frame-decode result
    private static FrameSyntaxDecodeResult createInterFrameSyntaxDecodeResult(
            PixelFormat pixelFormat,
            int width,
            int height,
            int referenceSlot,
            FrameHeader.InterpolationFilter interpolationFilter,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(pixelFormat, width, height);
        FrameHeader frameHeader = createInterFrameHeader(width, height, referenceSlot, interpolationFilter);
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
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
    /// @param pixelFormat the decoded chroma layout
    /// @param width the frame width
    /// @param height the frame height
    /// @return one minimal reduced-still-picture sequence header
    private static SequenceHeader createSequenceHeader(PixelFormat pixelFormat, int width, int height) {
        return new SequenceHeader(
                0,
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
                        false,
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
                new SequenceHeader.ColorConfig(
                        8,
                        pixelFormat == PixelFormat.I400,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat != PixelFormat.I444,
                        pixelFormat == PixelFormat.I420,
                        false
                )
        );
    }

    /// Creates one minimal frame header for reconstruction tests.
    ///
    /// @param frameType the frame type to expose
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @return one minimal frame header
    private static FrameHeader createFrameHeader(FrameType frameType, int width, int height) {
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
                false
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
                false
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
                false
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
                CompoundInterPredictionMode.NEARESTMV_NEARESTMV,
                0,
                InterMotionVector.resolved(motionVector0),
                InterMotionVector.resolved(motionVector1),
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
    }

    /// Creates one stored reference surface snapshot for synthetic inter reconstruction tests.
    ///
    /// @param pixelFormat the decoded chroma layout stored by the snapshot
    /// @param lumaSamples the luma sample raster
    /// @param chromaUSamples the chroma-U sample raster, or `null`
    /// @param chromaVSamples the chroma-V sample raster, or `null`
    /// @return one stored reference surface snapshot for synthetic inter reconstruction tests
    private static ReferenceSurfaceSnapshot createReferenceSurfaceSnapshot(
            PixelFormat pixelFormat,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        int width = lumaSamples[0].length;
        int height = lumaSamples.length;
        FrameSyntaxDecodeResult syntaxDecodeResult = createFrameSyntaxDecodeResult(pixelFormat, FrameType.KEY, width, height);
        return new ReferenceSurfaceSnapshot(
                syntaxDecodeResult.assembly().frameHeader(),
                syntaxDecodeResult,
                new DecodedPlanes(
                        8,
                        pixelFormat,
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
    private static DecodedPlane createDecodedPlane(int[][] samples) {
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
        return new DecodedPlane(width, height, width, storage);
    }

    /// Creates one transform layout that exactly covers one leaf block with one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param pixelFormat the active decoded chroma layout
    /// @return one transform layout that exactly covers one leaf block
    private static TransformLayout createTransformLayout(BlockPosition position, BlockSize size, PixelFormat pixelFormat) {
        TransformSize transformSize = size.maxLumaTransformSize();
        return new TransformLayout(
                position,
                size,
                size.width4(),
                size.height4(),
                transformSize,
                size.maxChromaTransformSize(pixelFormat),
                false,
                new TransformUnit[]{new TransformUnit(position, transformSize)}
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
                        new TransformResidualUnit(position, transformSize, endOfBlockIndex, coefficients, coefficientContextByte)
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
    /// @param pixelFormat the active decoded chroma layout
    /// @param chromaUDcCoefficient the signed chroma-U DC coefficient to store
    /// @param chromaVDcCoefficient the signed chroma-V DC coefficient to store
    /// @return one residual layout with all-zero luma units and caller-supplied chroma DC coefficients
    private static ResidualLayout createChromaDcResidualLayout(
            BlockPosition position,
            BlockSize size,
            PixelFormat pixelFormat,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TransformSize lumaTransformSize = size.maxLumaTransformSize();
        TransformResidualUnit lumaUnit = new TransformResidualUnit(
                position,
                lumaTransformSize,
                -1,
                new int[lumaTransformSize.widthPixels() * lumaTransformSize.heightPixels()],
                0
        );

        @Nullable TransformSize chromaTransformSize = size.maxChromaTransformSize(pixelFormat);
        if (chromaTransformSize == null) {
            throw new AssertionError("Expected chroma transform size for " + pixelFormat);
        }

        int chromaVisibleWidth = switch (pixelFormat) {
            case I400 -> throw new AssertionError("Expected chroma pixel format");
            case I420, I422 -> (size.widthPixels() + 1) >> 1;
            case I444 -> size.widthPixels();
        };
        int chromaVisibleHeight = switch (pixelFormat) {
            case I400 -> throw new AssertionError("Expected chroma pixel format");
            case I420 -> (size.heightPixels() + 1) >> 1;
            case I422, I444 -> size.heightPixels();
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
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
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

    /// Reconstructs one tile-root array into one already allocated shared luma plane.
    ///
    /// This helper reflects into the current private node-reconstruction path so tests can model
    /// multi-tile composition without widening the public reconstruction surface prematurely.
    ///
    /// @param sharedLumaPlane the shared luma destination plane
    /// @param pixelFormat the decoded chroma layout to expose to reconstruction
    /// @param frameHeader the frame header that owns the active quantization state
    /// @param roots the top-level roots for one synthetic tile
    private static void reconstructSyntheticTileRootsIntoSharedLumaPlane(
            MutablePlaneBuffer sharedLumaPlane,
            PixelFormat pixelFormat,
            FrameHeader frameHeader,
            TilePartitionTreeReader.Node[] roots
    ) {
        try {
            Method reconstructNode = FrameReconstructor.class.getDeclaredMethod(
                    "reconstructNode",
                    TilePartitionTreeReader.Node.class,
                    MutablePlaneBuffer.class,
                    MutablePlaneBuffer.class,
                    MutablePlaneBuffer.class,
                    PixelFormat.class,
                    FrameHeader.class,
                    ReferenceSurfaceSnapshot[].class
            );
            reconstructNode.setAccessible(true);
            for (TilePartitionTreeReader.Node root : roots) {
                reconstructNode.invoke(null, root, sharedLumaPlane, null, null, pixelFormat, frameHeader, null);
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to reconstruct synthetic tile roots into one shared plane", exception);
        }
    }

    /// Asserts that one decoded plane is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param width the expected plane width
    /// @param height the expected plane height
    /// @param expectedSample the expected sample value at every coordinate
    private static void assertPlaneFilled(DecodedPlane plane, int width, int height, int expectedSample) {
        assertEquals(width, plane.width());
        assertEquals(height, plane.height());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(expectedSample, plane.sample(x, y));
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
            DecodedPlane plane,
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
    private static void assertPlaneEquals(DecodedPlane plane, int[][] expected) {
        assertEquals(expected[0].length, plane.width());
        assertEquals(expected.length, plane.height());
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], plane.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded sub-block matches the supplied expected raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param expected the expected sample raster
    private static void assertPlaneBlockEquals(DecodedPlane plane, int x, int y, int[][] expected) {
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

    /// Asserts that two decoded planes carry the same stored sample values.
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

    /// Asserts that two decoded planes carry identical samples throughout one rectangular region.
    ///
    /// @param expected the expected decoded plane
    /// @param actual the actual decoded plane
    /// @param x the region origin X coordinate
    /// @param y the region origin Y coordinate
    /// @param width the region width
    /// @param height the region height
    private static void assertPlaneRegionEquals(
            DecodedPlane expected,
            DecodedPlane actual,
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
            DecodedPlane baseline,
            DecodedPlane reconstructed,
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

    /// Returns one guaranteed-present decoded plane after a non-null assertion.
    ///
    /// @param plane the decoded plane reference, or `null`
    /// @return the same decoded plane reference after a non-null assertion
    private static DecodedPlane requirePlane(@Nullable DecodedPlane plane) {
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
