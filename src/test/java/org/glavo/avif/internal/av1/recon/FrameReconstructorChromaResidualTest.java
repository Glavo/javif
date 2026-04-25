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
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Synthetic `FrameReconstructor` tests for the minimal `I420` chroma residual rollout.
///
/// These tests construct tiny structural frames directly and pin the intended U/V residual
/// behavior without relying on the integration reader path.
@NotNullByDefault
final class FrameReconstructorChromaResidualTest {
    /// Verifies that a positive chroma-U DC residual shifts only the U plane above the
    /// zero-residual `I420` baseline.
    @Test
    void reconstructsSingleTileI420IntraFrameWithPositiveChromaUDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode positiveChromaULeaf = createI420Leaf(
                position,
                size,
                null,
                dcOnlyCoefficients(requireI420ChromaTransformSize(size), 32),
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, positiveChromaULeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                4
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that a negative chroma-V DC residual shifts only the V plane below the
    /// zero-residual `I420` baseline.
    @Test
    void reconstructsSingleTileI420IntraFrameWithNegativeChromaVDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TilePartitionTreeReader.LeafNode zeroResidualLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode negativeChromaVLeaf = createI420Leaf(
                position,
                size,
                null,
                null,
                dcOnlyCoefficients(requireI420ChromaTransformSize(size), -32)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, negativeChromaVLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residualPlanes.chromaUPlane()));
        assertPlaneDiffersFromBaselineByUniformOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residualPlanes.chromaVPlane()),
                -4
        );
    }

    /// Verifies that one non-uniform `TX_4X4` chroma-U residual matches the monochrome residual
    /// oracle exactly while leaving luma and chroma-V untouched.
    @Test
    void reconstructsSingleTileI420IntraFrameWithTx4x4ChromaUMultiCoefficientResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaCoefficients = multiCoefficientTx4x4Coefficients();
        TilePartitionTreeReader.LeafNode baselineLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode residualLeaf = createI420Leaf(
                position,
                size,
                null,
                chromaCoefficients,
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, baselineLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByRaster(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                deriveMonochromeResidualDeltaRaster(BlockSize.SIZE_4X4, 4, 4, chromaCoefficients)
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one clipped non-uniform `TX_4X4` chroma-U residual preserves the monochrome
    /// residual oracle inside the visible chroma footprint and leaves other planes untouched.
    @Test
    void reconstructsClippedI420Tx4x4ChromaUMultiCoefficientResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        int[] chromaCoefficients = multiCoefficientTx4x4Coefficients();
        TilePartitionTreeReader.LeafNode baselineLeaf = createI420Leaf(
                position,
                size,
                2,
                2,
                7,
                5,
                null,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode residualLeaf = createI420Leaf(
                position,
                size,
                2,
                2,
                7,
                5,
                null,
                chromaCoefficients,
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 7, 5, baselineLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 7, 5, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByRaster(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                deriveMonochromeResidualDeltaRaster(BlockSize.SIZE_4X4, 4, 3, chromaCoefficients)
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one synthetic `I420` block can carry non-zero luma and chroma residuals at
    /// the same time without any cross-plane writes.
    @Test
    void reconstructsSingleTileI420IntraFrameWithIndependentLumaAndChromaResiduals() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TilePartitionTreeReader.LeafNode residualLeaf = createI420Leaf(
                position,
                size,
                twoDimensionalTx8x8Coefficients(),
                dcOnlyCoefficients(requireI420ChromaTransformSize(size), 32),
                dcOnlyCoefficients(requireI420ChromaTransformSize(size), -32)
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 8, 8, residualLeaf)
        );

        assertPlaneEquals(
                planes.lumaPlane(),
                new int[][]{
                        {136, 135, 132, 130, 126, 124, 121, 120},
                        {135, 134, 132, 129, 127, 124, 122, 121},
                        {132, 132, 131, 129, 127, 125, 124, 124},
                        {130, 129, 129, 128, 128, 127, 127, 126},
                        {126, 127, 127, 128, 128, 129, 129, 130},
                        {124, 124, 125, 127, 129, 131, 132, 132},
                        {121, 122, 124, 127, 129, 132, 134, 135},
                        {120, 121, 124, 126, 130, 132, 135, 136}
                }
        );
        assertPlaneFilled(requirePlane(planes.chromaUPlane()), 4, 4, 132);
        assertPlaneFilled(requirePlane(planes.chromaVPlane()), 4, 4, 124);
    }

    /// Verifies that one clipped `I420` chroma residual updates only the visible chroma footprint.
    @Test
    void reconstructsClippedI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(2, 0);
        BlockSize size = BlockSize.SIZE_8X8;
        TilePartitionTreeReader.LeafNode baselineLeaf = createI420Leaf(
                position,
                size,
                1,
                2,
                2,
                8,
                null,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode residualLeaf = createI420Leaf(
                position,
                size,
                1,
                2,
                2,
                8,
                null,
                dcOnlyCoefficients(requireI420ChromaTransformSize(size), 32),
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 10, 8, baselineLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 10, 8, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineOnlyWithinRectByUniformOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                4,
                0,
                1,
                4,
                4
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that multiple `I420` chroma residual units update separate U/V footprints
    /// independently inside one synthetic leaf.
    @Test
    void reconstructsMultiUnitI420ChromaResidualsIndependently() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X16;
        TilePartitionTreeReader.LeafNode baselineLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode residualLeaf = createI420LeafWithResidualUnits(
                position,
                size,
                new TransformResidualUnit[]{
                        createResidualUnit(position, size.maxLumaTransformSize(), size.widthPixels(), size.heightPixels(), null)
                },
                new TransformResidualUnit[]{
                        createDcOnlyResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4, 32),
                        createDcOnlyResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 4, 4, -32)
                },
                new TransformResidualUnit[]{
                        createDcOnlyResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 4, -32),
                        createDcOnlyResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 4, 4, 32)
                }
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 16, 16, baselineLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 16, 16, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByRaster(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                new int[][]{
                        {4, 4, 4, 4, -4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4, -4},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0}
                }
        );
        assertPlaneDiffersFromBaselineByRaster(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residualPlanes.chromaVPlane()),
                new int[][]{
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {0, 0, 0, 0, 0, 0, 0, 0},
                        {-4, -4, -4, -4, 4, 4, 4, 4},
                        {-4, -4, -4, -4, 4, 4, 4, 4},
                        {-4, -4, -4, -4, 4, 4, 4, 4},
                        {-4, -4, -4, -4, 4, 4, 4, 4}
                }
        );
    }

    /// Verifies that multiple clipped `I420` chroma residual units each respect their own visible
    /// footprint on one frame fringe.
    @Test
    void reconstructsClippedMultiUnitI420ChromaResidualsIndependently() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X16;
        TilePartitionTreeReader.LeafNode baselineLeaf = createI420Leaf(
                position,
                size,
                4,
                4,
                14,
                14,
                null,
                null,
                null
        );
        TilePartitionTreeReader.LeafNode residualLeaf = createI420LeafWithResidualUnits(
                position,
                size,
                4,
                4,
                14,
                14,
                new TransformResidualUnit[]{
                        createResidualUnit(position, size.maxLumaTransformSize(), 14, 14, null)
                },
                new TransformResidualUnit[]{
                        createDcOnlyResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4, 32),
                        createDcOnlyResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 3, 4, -32),
                        createDcOnlyResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 3, -32),
                        createDcOnlyResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 3, 3, 32)
                },
                new TransformResidualUnit[]{
                        createResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4, null),
                        createResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 3, 4, null),
                        createResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 3, null),
                        createResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 3, 3, null)
                }
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 14, 14, baselineLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 14, 14, residualLeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByRaster(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane()),
                new int[][]{
                        {4, 4, 4, 4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4},
                        {4, 4, 4, 4, -4, -4, -4},
                        {-4, -4, -4, -4, 4, 4, 4},
                        {-4, -4, -4, -4, 4, 4, 4},
                        {-4, -4, -4, -4, 4, 4, 4}
                }
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one full-plane `TX_16X16` chroma-U DC residual updates the entire chroma-U
    /// plane above the zero-residual `I420` baseline without affecting luma or chroma-V.
    @Test
    void reconstructsSingleTileI420IntraFrameWithPositiveTx16x16ChromaUDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_32X32;
        TransformSize chromaTransformSize = requireI420ChromaTransformSize(size);
        assertEquals(TransformSize.TX_16X16, chromaTransformSize);

        TilePartitionTreeReader.LeafNode zeroResidualLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode positiveChromaULeaf = createI420Leaf(
                position,
                size,
                null,
                dcOnlyCoefficients(chromaTransformSize, 32),
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 32, 32, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 32, 32, positiveChromaULeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformPositiveOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane())
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Verifies that one rectangular `RTX_8X4` chroma-U DC residual updates the entire chroma-U
    /// plane above the zero-residual `I420` baseline without affecting luma or chroma-V.
    @Test
    void reconstructsSingleTileI420IntraFrameWithPositiveRectangularChromaUDcResidual() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_16X8;
        TransformSize chromaTransformSize = requireI420ChromaTransformSize(size);
        assertEquals(TransformSize.RTX_8X4, chromaTransformSize);

        TilePartitionTreeReader.LeafNode zeroResidualLeaf = createI420Leaf(position, size, null, null, null);
        TilePartitionTreeReader.LeafNode positiveChromaULeaf = createI420Leaf(
                position,
                size,
                null,
                dcOnlyCoefficients(chromaTransformSize, 64),
                null
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 16, 8, zeroResidualLeaf)
        );
        DecodedPlanes residualPlanes = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(AvifPixelFormat.I420, FrameType.INTRA, 16, 8, positiveChromaULeaf)
        );

        assertPlanesEqual(baseline.lumaPlane(), residualPlanes.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformPositiveOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residualPlanes.chromaUPlane())
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residualPlanes.chromaVPlane()));
    }

    /// Creates one synthetic `I420` intra leaf with optional luma/chroma residual coefficients.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param lumaCoefficients the optional luma coefficients, or `null`
    /// @param chromaUCoefficients the optional chroma-U coefficients, or `null`
    /// @param chromaVCoefficients the optional chroma-V coefficients, or `null`
    /// @return one synthetic `I420` intra leaf
    private static TilePartitionTreeReader.LeafNode createI420Leaf(
            BlockPosition position,
            BlockSize size,
            @Nullable int[] lumaCoefficients,
            @Nullable int[] chromaUCoefficients,
            @Nullable int[] chromaVCoefficients
    ) {
        return createI420Leaf(
                position,
                size,
                size.width4(),
                size.height4(),
                size.widthPixels(),
                size.heightPixels(),
                lumaCoefficients,
                chromaUCoefficients,
                chromaVCoefficients
        );
    }

    /// Creates one synthetic clipped monochrome intra leaf with one optional luma residual block.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @param lumaCoefficients the optional luma coefficients, or `null`
    /// @return one synthetic clipped monochrome intra leaf
    private static TilePartitionTreeReader.LeafNode createI400Leaf(
            BlockPosition position,
            BlockSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            @Nullable int[] lumaCoefficients
    ) {
        return new TilePartitionTreeReader.LeafNode(
                createIntraI400BlockHeader(position, size),
                createTransformLayout(
                        position,
                        size,
                        (visibleWidthPixels + 3) >> 2,
                        (visibleHeightPixels + 3) >> 2,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        AvifPixelFormat.I400
                ),
                createMonochromeResidualLayout(
                        position,
                        size,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        lumaCoefficients
                )
        );
    }

    /// Creates one synthetic `I420` intra leaf with caller-supplied residual-unit arrays.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the chroma-U residual units
    /// @param chromaVUnits the chroma-V residual units
    /// @return one synthetic `I420` intra leaf backed by the supplied residual units
    private static TilePartitionTreeReader.LeafNode createI420LeafWithResidualUnits(
            BlockPosition position,
            BlockSize size,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        return createI420LeafWithResidualUnits(
                position,
                size,
                size.width4(),
                size.height4(),
                size.widthPixels(),
                size.heightPixels(),
                lumaUnits,
                chromaUUnits,
                chromaVUnits
        );
    }

    /// Creates one synthetic clipped `I420` intra leaf with optional luma/chroma residual coefficients.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @param lumaCoefficients the optional luma coefficients, or `null`
    /// @param chromaUCoefficients the optional chroma-U coefficients, or `null`
    /// @param chromaVCoefficients the optional chroma-V coefficients, or `null`
    /// @return one synthetic clipped `I420` intra leaf
    private static TilePartitionTreeReader.LeafNode createI420Leaf(
            BlockPosition position,
            BlockSize size,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            @Nullable int[] lumaCoefficients,
            @Nullable int[] chromaUCoefficients,
            @Nullable int[] chromaVCoefficients
    ) {
        return new TilePartitionTreeReader.LeafNode(
                createIntraI420BlockHeader(position, size),
                createTransformLayout(
                        position,
                        size,
                        visibleWidth4,
                        visibleHeight4,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        AvifPixelFormat.I420
                ),
                createResidualLayout(
                        position,
                        size,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        lumaCoefficients,
                        chromaUCoefficients,
                        chromaVCoefficients
                )
        );
    }

    /// Creates one synthetic clipped `I420` intra leaf with caller-supplied residual-unit arrays.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the chroma-U residual units
    /// @param chromaVUnits the chroma-V residual units
    /// @return one synthetic clipped `I420` intra leaf backed by the supplied residual units
    private static TilePartitionTreeReader.LeafNode createI420LeafWithResidualUnits(
            BlockPosition position,
            BlockSize size,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        return new TilePartitionTreeReader.LeafNode(
                createIntraI420BlockHeader(position, size),
                createTransformLayout(
                        position,
                        size,
                        visibleWidth4,
                        visibleHeight4,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        AvifPixelFormat.I420
                ),
                createResidualLayout(position, size, lumaUnits, chromaUUnits, chromaVUnits)
        );
    }

    /// Creates one minimal monochrome residual layout with one optional luma residual unit and no
    /// chroma units.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidthPixels the exact visible luma block width in pixels
    /// @param visibleHeightPixels the exact visible luma block height in pixels
    /// @param lumaCoefficients the optional luma coefficients, or `null`
    /// @return one monochrome residual layout
    private static ResidualLayout createMonochromeResidualLayout(
            BlockPosition position,
            BlockSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            @Nullable int[] lumaCoefficients
    ) {
        TransformSize lumaTransformSize = size.maxLumaTransformSize();
        return createResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{createResidualUnit(
                        position,
                        lumaTransformSize,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        lumaCoefficients
                )},
                new TransformResidualUnit[0],
                new TransformResidualUnit[0]
        );
    }

    /// Creates one minimal structural frame result backed by one synthetic tile tree.
    ///
    /// @param pixelFormat the decoded chroma layout
    /// @param frameType the frame type to expose
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param roots the synthetic single-tile partition roots
    /// @return one structural frame result
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(
            AvifPixelFormat pixelFormat,
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

    /// Creates one minimal reduced-still-picture sequence header for reconstruction tests.
    ///
    /// @param pixelFormat the decoded chroma layout
    /// @param width the frame width
    /// @param height the frame height
    /// @return one minimal reduced-still-picture sequence header
    private static SequenceHeader createSequenceHeader(AvifPixelFormat pixelFormat, int width, int height) {
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
                        pixelFormat == AvifPixelFormat.I400,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat != AvifPixelFormat.I444,
                        pixelFormat == AvifPixelFormat.I420,
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

    /// Creates one supported `I420` intra block header with zero directional and palette state.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @return one supported `I420` intra block header
    private static TileBlockHeaderReader.BlockHeader createIntraI420BlockHeader(BlockPosition position, BlockSize size) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
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
    }

    /// Creates one supported monochrome intra block header with zero directional and palette state.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @return one supported monochrome intra block header
    private static TileBlockHeaderReader.BlockHeader createIntraI400BlockHeader(BlockPosition position, BlockSize size) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
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

    /// Creates one transform layout that exactly covers one leaf block with one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param pixelFormat the active decoded chroma layout
    /// @return one transform layout that exactly covers one leaf block
    private static TransformLayout createTransformLayout(BlockPosition position, BlockSize size, AvifPixelFormat pixelFormat) {
        return createTransformLayout(
                position,
                size,
                size.width4(),
                size.height4(),
                size.widthPixels(),
                size.heightPixels(),
                pixelFormat
        );
    }

    /// Creates one transform layout that covers the supplied visible leaf span with one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @param pixelFormat the active decoded chroma layout
    /// @return one transform layout that covers the supplied visible leaf span
    private static TransformLayout createTransformLayout(
            BlockPosition position,
            BlockSize size,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            AvifPixelFormat pixelFormat
    ) {
        TransformSize transformSize = size.maxLumaTransformSize();
        return new TransformLayout(
                position,
                size,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                transformSize,
                size.maxChromaTransformSize(pixelFormat),
                false,
                new TransformUnit[]{new TransformUnit(position, transformSize)}
        );
    }

    /// Creates one residual layout with optional luma/chroma transform units.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param visibleWidthPixels the exact visible luma block width in pixels
    /// @param visibleHeightPixels the exact visible luma block height in pixels
    /// @param lumaCoefficients the optional luma coefficients, or `null`
    /// @param chromaUCoefficients the optional chroma-U coefficients, or `null`
    /// @param chromaVCoefficients the optional chroma-V coefficients, or `null`
    /// @return one residual layout with optional luma/chroma transform units
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            @Nullable int[] lumaCoefficients,
            @Nullable int[] chromaUCoefficients,
            @Nullable int[] chromaVCoefficients
    ) {
        TransformSize lumaTransformSize = size.maxLumaTransformSize();
        TransformSize chromaTransformSize = requireI420ChromaTransformSize(size);
        int visibleChromaWidthPixels = (visibleWidthPixels + 1) >> 1;
        int visibleChromaHeightPixels = (visibleHeightPixels + 1) >> 1;
        return new ResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{createResidualUnit(
                        position,
                        lumaTransformSize,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        lumaCoefficients
                )},
                new TransformResidualUnit[]{createResidualUnit(
                        position,
                        chromaTransformSize,
                        visibleChromaWidthPixels,
                        visibleChromaHeightPixels,
                        chromaUCoefficients
                )},
                new TransformResidualUnit[]{createResidualUnit(
                        position,
                        chromaTransformSize,
                        visibleChromaWidthPixels,
                        visibleChromaHeightPixels,
                        chromaVCoefficients
                )}
        );
    }

    /// Creates one residual layout from caller-supplied residual-unit arrays.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param size the coded block size
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the chroma-U residual units
    /// @param chromaVUnits the chroma-V residual units
    /// @return one residual layout backed by the supplied unit arrays
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize size,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        return new ResidualLayout(position, size, lumaUnits, chromaUUnits, chromaVUnits);
    }

    /// Creates one residual unit from one optional caller-supplied coefficient array.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param transformSize the transform size carried by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param coefficients the optional coefficients, or `null`
    /// @return one residual unit from the supplied coefficients
    private static TransformResidualUnit createResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            @Nullable int[] coefficients
    ) {
        int[] resolvedCoefficients = coefficients != null
                ? coefficients
                : new int[transformSize.widthPixels() * transformSize.heightPixels()];
        return new TransformResidualUnit(
                position,
                transformSize,
                lastNonZeroIndex(resolvedCoefficients),
                resolvedCoefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                hasNonZeroCoefficient(resolvedCoefficients) ? 1 : 0
        );
    }

    /// Creates one DC-only residual unit with one caller-supplied visible footprint.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param transformSize the transform size carried by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed DC coefficient
    /// @return one DC-only residual unit
    private static TransformResidualUnit createDcOnlyResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        return createResidualUnit(
                position,
                transformSize,
                visibleWidthPixels,
                visibleHeightPixels,
                dcOnlyCoefficients(transformSize, dcCoefficient)
        );
    }

    /// Returns the guaranteed-present `I420` chroma transform size for one block size.
    ///
    /// @param size the coded block size
    /// @return the guaranteed-present `I420` chroma transform size
    private static TransformSize requireI420ChromaTransformSize(BlockSize size) {
        TransformSize chromaTransformSize = size.maxChromaTransformSize(AvifPixelFormat.I420);
        assertNotNull(chromaTransformSize);
        return chromaTransformSize;
    }

    /// Returns one DC-only coefficient array for the supplied transform size.
    ///
    /// @param transformSize the transform size the coefficients belong to
    /// @param dcCoefficient the signed DC coefficient
    /// @return one DC-only coefficient array
    private static int[] dcOnlyCoefficients(TransformSize transformSize, int dcCoefficient) {
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[0] = dcCoefficient;
        return coefficients;
    }

    /// Returns one deterministic `TX_8X8` coefficient block whose luma reconstruction matches the
    /// existing two-dimensional residual oracle.
    ///
    /// @return one deterministic `TX_8X8` coefficient block
    private static int[] twoDimensionalTx8x8Coefficients() {
        int[] coefficients = new int[TransformSize.TX_8X8.widthPixels() * TransformSize.TX_8X8.heightPixels()];
        coefficients[9] = 64;
        return coefficients;
    }

    /// Returns one deterministic non-uniform `TX_4X4` coefficient block for chroma oracle tests.
    ///
    /// @return one deterministic non-uniform `TX_4X4` coefficient block
    private static int[] multiCoefficientTx4x4Coefficients() {
        int[] coefficients = new int[TransformSize.TX_4X4.widthPixels() * TransformSize.TX_4X4.heightPixels()];
        coefficients[0] = 32;
        coefficients[1] = -16;
        coefficients[4] = 24;
        coefficients[5] = 48;
        return coefficients;
    }

    /// Returns whether one coefficient array contains any non-zero coefficient.
    ///
    /// @param coefficients the coefficient array to inspect
    /// @return whether one coefficient array contains any non-zero coefficient
    private static boolean hasNonZeroCoefficient(int[] coefficients) {
        for (int coefficient : coefficients) {
            if (coefficient != 0) {
                return true;
            }
        }
        return false;
    }

    /// Returns the last non-zero coefficient index in one coefficient array, or `-1` when all
    /// coefficients are zero.
    ///
    /// @param coefficients the coefficient array to inspect
    /// @return the last non-zero coefficient index, or `-1`
    private static int lastNonZeroIndex(int[] coefficients) {
        for (int i = coefficients.length - 1; i >= 0; i--) {
            if (coefficients[i] != 0) {
                return i;
            }
        }
        return -1;
    }

    /// Derives one expected delta raster by reconstructing the same residual coefficients through a
    /// monochrome luma path with matching transform geometry.
    ///
    /// @param size the coded monochrome block size used by the oracle
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param coefficients the non-zero residual coefficients to reconstruct
    /// @return one expected residual delta raster
    private static int[][] deriveMonochromeResidualDeltaRaster(
            BlockSize size,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int[] coefficients
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        TilePartitionTreeReader.LeafNode baselineLeaf = createI400Leaf(
                position,
                size,
                visibleWidthPixels,
                visibleHeightPixels,
                null
        );
        TilePartitionTreeReader.LeafNode residualLeaf = createI400Leaf(
                position,
                size,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficients
        );
        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baseline = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        AvifPixelFormat.I400,
                        FrameType.INTRA,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        baselineLeaf
                )
        ).lumaPlane();
        DecodedPlane residual = reconstructor.reconstruct(
                createFrameSyntaxDecodeResult(
                        AvifPixelFormat.I400,
                        FrameType.INTRA,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        residualLeaf
                )
        ).lumaPlane();
        int[][] deltaRaster = deltaRaster(baseline, residual);
        assertTrue(hasNonZeroDelta(deltaRaster));
        return deltaRaster;
    }

    /// Returns one signed per-sample delta raster between two decoded planes.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the residual-applied plane
    /// @return one signed per-sample delta raster
    private static int[][] deltaRaster(DecodedPlane baseline, DecodedPlane reconstructed) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        int[][] deltaRaster = new int[baseline.height()][baseline.width()];
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                deltaRaster[y][x] = reconstructed.sample(x, y) - baseline.sample(x, y);
            }
        }
        return deltaRaster;
    }

    /// Returns whether one signed delta raster contains any visible non-zero change.
    ///
    /// @param deltaRaster the signed delta raster to inspect
    /// @return whether the raster contains any non-zero delta
    private static boolean hasNonZeroDelta(int[][] deltaRaster) {
        for (int[] row : deltaRaster) {
            for (int delta : row) {
                if (delta != 0) {
                    return true;
                }
            }
        }
        return false;
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

    /// Asserts that one decoded plane differs from the baseline by one exact uniform offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the residual-applied plane
    /// @param expectedDelta the exact uniform delta required at every sample
    private static void assertPlaneDiffersFromBaselineByUniformOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            int expectedDelta
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                assertEquals(expectedDelta, reconstructed.sample(x, y) - baseline.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded plane differs from the baseline by one exact uniform positive
    /// offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the residual-applied plane
    private static void assertPlaneDiffersFromBaselineByUniformPositiveOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        int expectedDelta = reconstructed.sample(0, 0) - baseline.sample(0, 0);
        assertTrue(expectedDelta > 0);
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                assertEquals(expectedDelta, reconstructed.sample(x, y) - baseline.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded plane differs from its baseline only inside one visible rectangle.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the residual-applied plane
    /// @param originX the zero-based rectangle origin on the X axis
    /// @param originY the zero-based rectangle origin on the Y axis
    /// @param width the rectangle width in samples
    /// @param height the rectangle height in samples
    /// @param expectedDelta the exact uniform delta required inside the visible rectangle
    private static void assertPlaneDiffersFromBaselineOnlyWithinRectByUniformOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            int originX,
            int originY,
            int width,
            int height,
            int expectedDelta
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                int expected = x >= originX && x < originX + width && y >= originY && y < originY + height
                        ? expectedDelta
                        : 0;
                assertEquals(expected, reconstructed.sample(x, y) - baseline.sample(x, y));
            }
        }
    }

    /// Asserts that one decoded plane differs from its baseline by one caller-supplied per-sample
    /// delta raster.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the residual-applied plane
    /// @param expectedDelta the expected signed delta raster
    private static void assertPlaneDiffersFromBaselineByRaster(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            int[][] expectedDelta
    ) {
        assertEquals(expectedDelta[0].length, baseline.width());
        assertEquals(expectedDelta.length, baseline.height());
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());
        for (int y = 0; y < expectedDelta.length; y++) {
            for (int x = 0; x < expectedDelta[y].length; x++) {
                assertEquals(expectedDelta[y][x], reconstructed.sample(x, y) - baseline.sample(x, y));
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

    /// Returns one guaranteed-present decoded plane after a non-null assertion.
    ///
    /// @param plane the decoded plane reference, or `null`
    /// @return the same decoded plane reference after a non-null assertion
    private static DecodedPlane requirePlane(@Nullable DecodedPlane plane) {
        assertNotNull(plane);
        return plane;
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
