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
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

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
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, 0, 0, 0, 0),
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
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.DC, 0, 0, 0, 0),
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

    /// Verifies that inter blocks still fail fast in the first reconstruction subset.
    @Test
    void rejectsInterBlocks() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createInterBlockHeader(position, size),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 4, 4, leaf)
                )
        );

        assertEquals("Inter block reconstruction is not implemented yet", exception.getMessage());
    }

    /// Verifies that non-zero luma residual units remain explicitly unsupported.
    @Test
    void rejectsNonZeroResidualUnits() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, false, LumaIntraPredictionMode.DC, null, 0, 0, 0, 0),
                createTransformLayout(position, size, PixelFormat.I400),
                createResidualLayout(position, size, false)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(PixelFormat.I400, FrameType.KEY, 4, 4, leaf)
                )
        );

        assertEquals("Non-zero residual reconstruction is not implemented yet", exception.getMessage());
    }

    /// Verifies that CFL-coded chroma blocks remain unsupported in the first `I420` reconstruction path.
    @Test
    void rejectsCflChromaBlocks() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize size = BlockSize.SIZE_4X4;
        TilePartitionTreeReader.LeafNode leaf = new TilePartitionTreeReader.LeafNode(
                createIntraBlockHeader(position, size, true, LumaIntraPredictionMode.DC, UvIntraPredictionMode.CFL, 0, 0, 1, -1),
                createTransformLayout(position, size, PixelFormat.I420),
                createResidualLayout(position, size, true)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(
                        createFrameSyntaxDecodeResult(PixelFormat.I420, FrameType.KEY, 4, 4, leaf)
                )
        );

        assertEquals("CFL reconstruction is not implemented yet", exception.getMessage());
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

    /// Creates one supported intra block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @param hasChroma whether the block carries chroma samples
    /// @param yMode the luma intra mode
    /// @param uvMode the chroma intra mode, or `null`
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
                null,
                0,
                0,
                cflAlphaU,
                cflAlphaV
        );
    }

    /// Creates one unsupported inter block header for reconstruction tests.
    ///
    /// @param position the block origin in 4x4 units
    /// @param size the coded block size
    /// @return one unsupported inter block header
    private static TileBlockHeaderReader.BlockHeader createInterBlockHeader(BlockPosition position, BlockSize size) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                -1,
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
