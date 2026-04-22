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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Focused luma-prediction tests that pin the success-path behavior future filter-intra
/// reconstruction will build on.
@NotNullByDefault
final class FilterIntraLumaReconstructionPathTest {
    /// Verifies that top-edge DC prediction averages only left reference samples when no top row is available.
    @Test
    void predictLumaDcAtTopEdgeUsesOnlyLeftReferenceSamples() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(6, 3, 8);
        plane.setSample(0, 0, 60);
        plane.setSample(0, 1, 100);

        IntraPredictor.predictLuma(plane, 1, 0, 3, 2, LumaIntraPredictionMode.DC, 0);

        assertMutableBlockFilled(plane, 1, 0, 3, 2, 80);
    }

    /// Verifies that left-edge DC prediction averages only top reference samples when no left column is available.
    @Test
    void predictLumaDcAtLeftEdgeUsesOnlyTopReferenceSamples() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(3, 6, 8);
        plane.setSample(0, 0, 30);
        plane.setSample(1, 0, 70);

        IntraPredictor.predictLuma(plane, 0, 1, 2, 3, LumaIntraPredictionMode.DC, 0);

        assertMutableBlockFilled(plane, 0, 1, 2, 3, 50);
    }

    /// Verifies that a synthetic right-hand leaf reuses the already reconstructed left block for horizontal luma prediction.
    @Test
    void reconstructsSyntheticHorizontalLeafFromReconstructedLeftBlock() {
        TilePartitionTreeReader.LeafNode leftLeaf = createLumaLeaf(new BlockPosition(0, 0), LumaIntraPredictionMode.DC, 64);
        TilePartitionTreeReader.LeafNode rightLeaf = createAllZeroLumaLeaf(
                new BlockPosition(1, 0),
                LumaIntraPredictionMode.HORIZONTAL
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(8, 4, leftLeaf, rightLeaf)
        );

        assertFalse(planes.hasChroma());
        int leftReference = planes.lumaPlane().sample(3, 0);
        assertTrue(leftReference > 128);
        assertDecodedBlockFilled(planes.lumaPlane(), 4, 0, 4, 4, leftReference);
    }

    /// Verifies that a synthetic lower leaf reuses the already reconstructed top block for vertical luma prediction.
    @Test
    void reconstructsSyntheticVerticalLeafFromReconstructedTopBlock() {
        TilePartitionTreeReader.LeafNode topLeaf = createLumaLeaf(new BlockPosition(0, 0), LumaIntraPredictionMode.DC, -64);
        TilePartitionTreeReader.LeafNode bottomLeaf = createAllZeroLumaLeaf(
                new BlockPosition(0, 1),
                LumaIntraPredictionMode.VERTICAL
        );

        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(4, 8, topLeaf, bottomLeaf)
        );

        assertFalse(planes.hasChroma());
        int topReference = planes.lumaPlane().sample(0, 3);
        assertTrue(topReference < 128);
        assertDecodedBlockFilled(planes.lumaPlane(), 0, 4, 4, 4, topReference);
    }

    /// Verifies that one all-zero directional leaf reuses the already reconstructed top row and
    /// top-right samples from earlier leaves.
    @Test
    void reconstructsSyntheticDirectionalLeafFromReconstructedTopRow() {
        TilePartitionTreeReader.LeafNode topLeftLeaf = createLumaLeaf(new BlockPosition(0, 0), LumaIntraPredictionMode.DC, 64);
        TilePartitionTreeReader.LeafNode topMiddleLeaf = createAllZeroLumaLeaf(
                new BlockPosition(1, 0),
                LumaIntraPredictionMode.DC
        );
        TilePartitionTreeReader.LeafNode topRightLeaf = createLumaLeaf(new BlockPosition(2, 0), LumaIntraPredictionMode.DC, -64);
        TilePartitionTreeReader.LeafNode bottomDirectionalLeaf = createAllZeroLumaLeaf(
                new BlockPosition(0, 1),
                LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT
        );

        DecodedPlane baseline = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(12, 4, topLeftLeaf, topMiddleLeaf, topRightLeaf)
        ).lumaPlane();
        DecodedPlanes planes = new FrameReconstructor().reconstruct(
                createFrameSyntaxDecodeResult(12, 8, topLeftLeaf, topMiddleLeaf, topRightLeaf, bottomDirectionalLeaf)
        );

        assertFalse(planes.hasChroma());
        assertDecodedBlockEquals(
                planes.lumaPlane(),
                0,
                4,
                DirectionalIntraPredictionOracle.predictLuma(
                        baseline,
                        8,
                        0,
                        4,
                        4,
                        4,
                        LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT,
                        0
                )
        );
    }

    /// Creates one synthetic monochrome intra leaf with one signed-DC luma residual.
    ///
    /// @param position the leaf origin in 4x4 units
    /// @param mode the luma intra mode
    /// @param dcCoefficient the signed DC coefficient stored in the leaf residual unit
    /// @return one synthetic monochrome intra leaf with one signed-DC luma residual
    private static TilePartitionTreeReader.LeafNode createLumaLeaf(
            BlockPosition position,
            LumaIntraPredictionMode mode,
            int dcCoefficient
    ) {
        return createLumaLeaf(position, mode, createDcResidualLayout(position, BlockSize.SIZE_4X4, dcCoefficient));
    }

    /// Creates one synthetic monochrome intra leaf with one all-zero luma residual.
    ///
    /// @param position the leaf origin in 4x4 units
    /// @param mode the luma intra mode
    /// @return one synthetic monochrome intra leaf with one all-zero luma residual
    private static TilePartitionTreeReader.LeafNode createAllZeroLumaLeaf(
            BlockPosition position,
            LumaIntraPredictionMode mode
    ) {
        return createLumaLeaf(position, mode, createAllZeroResidualLayout(position, BlockSize.SIZE_4X4));
    }

    /// Creates one synthetic monochrome intra leaf with the supplied luma residual layout.
    ///
    /// @param position the leaf origin in 4x4 units
    /// @param mode the luma intra mode
    /// @param residualLayout the luma residual layout to attach to the leaf
    /// @return one synthetic monochrome intra leaf
    private static TilePartitionTreeReader.LeafNode createLumaLeaf(
            BlockPosition position,
            LumaIntraPredictionMode mode,
            ResidualLayout residualLayout
    ) {
        BlockSize size = BlockSize.SIZE_4X4;
        return new TilePartitionTreeReader.LeafNode(
                new TileBlockHeaderReader.BlockHeader(
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
                        mode,
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
                ),
                createTransformLayout(position, size),
                residualLayout
        );
    }

    /// Creates one synthetic monochrome structural frame result with the supplied root nodes.
    ///
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @param roots the top-level tile roots for tile `0`
    /// @return one synthetic monochrome structural frame result
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(
            int width,
            int height,
            TilePartitionTreeReader.Node... roots
    ) {
        SequenceHeader sequenceHeader = createSequenceHeader(width, height);
        FrameHeader frameHeader = createFrameHeader(width, height);
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

    /// Creates one minimal reduced-still-picture monochrome sequence header for reconstruction tests.
    ///
    /// @param width the frame width
    /// @param height the frame height
    /// @return one minimal reduced-still-picture monochrome sequence header
    private static SequenceHeader createSequenceHeader(int width, int height) {
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
                        true,
                        false,
                        2,
                        2,
                        2,
                        true,
                        PixelFormat.I400,
                        0,
                        true,
                        false,
                        false
                )
        );
    }

    /// Creates one minimal monochrome key-frame header for reconstruction tests.
    ///
    /// @param width the coded and rendered frame width
    /// @param height the coded and rendered frame height
    /// @return one minimal monochrome key-frame header
    private static FrameHeader createFrameHeader(int width, int height) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.KEY,
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
                new FrameHeader.SegmentationInfo(
                        false,
                        false,
                        false,
                        false,
                        defaultSegments(),
                        new boolean[8],
                        new int[8]
                ),
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

    /// Creates one transform layout that exactly covers one 4x4 leaf with one transform unit.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @return one transform layout that exactly covers one 4x4 leaf
    private static TransformLayout createTransformLayout(BlockPosition position, BlockSize size) {
        TransformSize transformSize = size.maxLumaTransformSize();
        return new TransformLayout(
                position,
                size,
                size.width4(),
                size.height4(),
                transformSize,
                null,
                false,
                new TransformUnit[]{new TransformUnit(position, transformSize)}
        );
    }

    /// Creates one all-zero luma residual layout for one 4x4 leaf.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @return one all-zero luma residual layout
    private static ResidualLayout createAllZeroResidualLayout(BlockPosition position, BlockSize size) {
        TransformSize transformSize = size.maxLumaTransformSize();
        return new ResidualLayout(
                position,
                size,
                new TransformResidualUnit[]{
                        new TransformResidualUnit(
                                position,
                                transformSize,
                                -1,
                                new int[transformSize.widthPixels() * transformSize.heightPixels()],
                                0
                        )
                }
        );
    }

    /// Creates one signed-DC-only luma residual layout for one 4x4 leaf.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param dcCoefficient the signed luma DC coefficient to store
    /// @return one signed-DC-only luma residual layout
    private static ResidualLayout createDcResidualLayout(BlockPosition position, BlockSize size, int dcCoefficient) {
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

    /// Asserts that one mutable plane block is filled with a single sample value.
    ///
    /// @param plane the mutable plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param width the expected block width
    /// @param height the expected block height
    /// @param expectedSample the expected sample value at every coordinate
    private static void assertMutableBlockFilled(
            MutablePlaneBuffer plane,
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

    /// Asserts that one decoded plane block is filled with a single sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param width the expected block width
    /// @param height the expected block height
    /// @param expectedSample the expected sample value at every coordinate
    private static void assertDecodedBlockFilled(
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

    /// Asserts that one decoded plane block matches the supplied expected raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param x the block origin X coordinate
    /// @param y the block origin Y coordinate
    /// @param expected the expected sample raster
    private static void assertDecodedBlockEquals(DecodedPlane plane, int x, int y, int[][] expected) {
        for (int row = 0; row < expected.length; row++) {
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(expected[row][column], plane.sample(x + column, y + row));
            }
        }
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
