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
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.RestorationUnit;
import org.glavo.avif.internal.av1.decode.RestorationUnitMap;
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
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Shared lightweight fixtures for postfilter-package unit tests.
@NotNullByDefault
final class PostfilterTestFixtures {
    /// Prevents instantiation.
    private PostfilterTestFixtures() {
    }

    /// Creates one decoded-plane snapshot from row-major sample matrices.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @param lumaSamples the luma sample raster
    /// @param chromaUSamples the chroma-U sample raster, or `null`
    /// @param chromaVSamples the chroma-V sample raster, or `null`
    /// @return one immutable decoded-plane snapshot
    static DecodedPlanes createDecodedPlanes(
            AvifPixelFormat pixelFormat,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        int width = lumaSamples[0].length;
        int height = lumaSamples.length;
        return new DecodedPlanes(
                8,
                pixelFormat,
                width,
                height,
                width,
                height,
                createPlane(lumaSamples),
                chromaUSamples != null ? createPlane(chromaUSamples) : null,
                chromaVSamples != null ? createPlane(chromaVSamples) : null
        );
    }

    /// Creates one minimal frame header with caller-supplied postfilter and grain state.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @param loopFilter the loop-filter state
    /// @param cdef the CDEF state
    /// @param restoration the restoration state
    /// @param filmGrain the normalized film-grain state
    /// @return one minimal frame header with caller-supplied postfilter and grain state
    static FrameHeader createFrameHeader(
            AvifPixelFormat pixelFormat,
            FrameHeader.LoopFilterInfo loopFilter,
            FrameHeader.CdefInfo cdef,
            FrameHeader.RestorationInfo restoration,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.KEY,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                7,
                0,
                0xFF,
                new FrameHeader.FrameSize(8, 8, 8, 8, 8),
                new FrameHeader.SuperResolutionInfo(false, 8),
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
                loopFilter,
                cdef,
                restoration,
                FrameHeader.TransformMode.LARGEST,
                false,
                filmGrain
        );
    }

    /// Creates one normalized film-grain state with visible effect for tests.
    ///
    /// @param grainSeed the deterministic grain seed
    /// @param restrictedRange whether output should be clipped to the restricted range
    /// @return one normalized film-grain state with visible effect for tests
    static FrameHeader.FilmGrainParams createFilmGrainParams(int grainSeed, boolean restrictedRange) {
        return new FrameHeader.FilmGrainParams(
                true,
                grainSeed,
                true,
                -1,
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(0, 32),
                        new FrameHeader.FilmGrainPoint(255, 96)
                },
                true,
                new FrameHeader.FilmGrainPoint[0],
                new FrameHeader.FilmGrainPoint[0],
                8,
                0,
                new int[0],
                new int[]{0},
                new int[]{0},
                6,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                restrictedRange
        );
    }

    /// Creates one disabled normalized film-grain state.
    ///
    /// @return one disabled normalized film-grain state
    static FrameHeader.FilmGrainParams disabledFilmGrain() {
        return FrameHeader.FilmGrainParams.disabled();
    }

    /// Creates one single-leaf syntax result with the supplied decoded CDEF index.
    ///
    /// @param frameHeader the frame header that owns the syntax result
    /// @param cdefIndex the decoded CDEF index to expose from the leaf
    /// @return one single-leaf syntax result with the supplied decoded CDEF index
    static FrameSyntaxDecodeResult createSingleLeafSyntaxResult(FrameHeader frameHeader, int cdefIndex) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader blockHeader = new TileBlockHeaderReader.BlockHeader(
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
                null,
                null,
                -1,
                null,
                null,
                false,
                0,
                cdefIndex,
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
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                8,
                8,
                TransformSize.TX_8X8,
                null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        TransformResidualUnit lumaResidualUnit = new TransformResidualUnit(
                position,
                TransformSize.TX_8X8,
                -1,
                new int[TransformSize.TX_8X8.widthPixels() * TransformSize.TX_8X8.heightPixels()],
                0
        );
        TilePartitionTreeReader.LeafNode leafNode = new TilePartitionTreeReader.LeafNode(
                blockHeader,
                transformLayout,
                new ResidualLayout(position, blockSize, new TransformResidualUnit[]{lumaResidualUnit})
        );
        FrameAssembly assembly = new FrameAssembly(createSequenceHeader(), frameHeader, 0, 0);
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leafNode}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one single-leaf syntax result with one decoded luma restoration unit.
    ///
    /// @param frameHeader the frame header that owns the syntax result
    /// @param cdefIndex the decoded CDEF index to expose from the leaf
    /// @param restorationUnit the decoded luma restoration unit
    /// @return one single-leaf syntax result with one decoded luma restoration unit
    static FrameSyntaxDecodeResult createSingleLeafSyntaxResult(
            FrameHeader frameHeader,
            int cdefIndex,
            RestorationUnit restorationUnit
    ) {
        FrameSyntaxDecodeResult baseResult = createSingleLeafSyntaxResult(frameHeader, cdefIndex);
        RestorationUnitMap restorationUnitMap = RestorationUnitMap.createEmpty(baseResult.assembly());
        restorationUnitMap.setUnit(0, 0, 0, restorationUnit);
        return new FrameSyntaxDecodeResult(
                baseResult.assembly(),
                baseResult.tileRoots(),
                baseResult.decodedTemporalMotionFields(),
                restorationUnitMap,
                baseResult.finalTileCdfContexts()
        );
    }

    /// Creates a two-leaf syntax result split on the vertical 4x4 boundary.
    ///
    /// @param frameHeader the frame header that owns the syntax result
    /// @param leftCdefIndex the decoded CDEF index for the left leaf
    /// @param rightCdefIndex the decoded CDEF index for the right leaf
    /// @return a two-leaf syntax result split on the vertical 4x4 boundary
    static FrameSyntaxDecodeResult createVerticalSplitLeafSyntaxResult(
            FrameHeader frameHeader,
            int leftCdefIndex,
            int rightCdefIndex
    ) {
        TilePartitionTreeReader.LeafNode leftLeaf = createLeaf(
                new BlockPosition(0, 0),
                BlockSize.SIZE_4X8,
                leftCdefIndex,
                TransformSize.RTX_4X8,
                new TransformUnit[]{
                        new TransformUnit(new BlockPosition(0, 0), TransformSize.TX_4X4),
                        new TransformUnit(new BlockPosition(0, 1), TransformSize.TX_4X4)
                }
        );
        TilePartitionTreeReader.LeafNode rightLeaf = createLeaf(
                new BlockPosition(1, 0),
                BlockSize.SIZE_4X8,
                rightCdefIndex,
                TransformSize.RTX_4X8,
                new TransformUnit[]{
                        new TransformUnit(new BlockPosition(1, 0), TransformSize.TX_4X4),
                        new TransformUnit(new BlockPosition(1, 1), TransformSize.TX_4X4)
                }
        );
        FrameAssembly assembly = new FrameAssembly(createSequenceHeader(), frameHeader, 0, 0);
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leftLeaf, rightLeaf}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one intra leaf with caller-supplied transform coverage.
    ///
    /// @param position the leaf position in luma 4x4 units
    /// @param blockSize the decoded block size
    /// @param cdefIndex the decoded CDEF index
    /// @param maxLumaTransformSize the maximum luma transform size for the leaf
    /// @param lumaUnits the luma transform units that cover the leaf
    /// @return one intra leaf with caller-supplied transform coverage
    private static TilePartitionTreeReader.LeafNode createLeaf(
            BlockPosition position,
            BlockSize blockSize,
            int cdefIndex,
            TransformSize maxLumaTransformSize,
            TransformUnit[] lumaUnits
    ) {
        TileBlockHeaderReader.BlockHeader blockHeader = createIntraBlockHeader(position, blockSize, cdefIndex);
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                blockSize.width4(),
                blockSize.height4(),
                blockSize.widthPixels(),
                blockSize.heightPixels(),
                maxLumaTransformSize,
                null,
                lumaUnits.length > 1,
                lumaUnits
        );
        return new TilePartitionTreeReader.LeafNode(
                blockHeader,
                transformLayout,
                new ResidualLayout(position, blockSize, createResidualUnits(lumaUnits))
        );
    }

    /// Creates one decoded intra block header for postfilter tests.
    ///
    /// @param position the block position in luma 4x4 units
    /// @param blockSize the decoded block size
    /// @param cdefIndex the decoded CDEF index
    /// @return one decoded intra block header for postfilter tests
    private static TileBlockHeaderReader.BlockHeader createIntraBlockHeader(
            BlockPosition position,
            BlockSize blockSize,
            int cdefIndex
    ) {
        return new TileBlockHeaderReader.BlockHeader(
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
                null,
                null,
                -1,
                null,
                null,
                false,
                0,
                cdefIndex,
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

    /// Creates zeroed residual units that match decoded transform coverage.
    ///
    /// @param transformUnits the transform units to mirror
    /// @return zeroed residual units that match decoded transform coverage
    private static TransformResidualUnit[] createResidualUnits(TransformUnit[] transformUnits) {
        TransformResidualUnit[] residualUnits = new TransformResidualUnit[transformUnits.length];
        for (int index = 0; index < transformUnits.length; index++) {
            TransformUnit transformUnit = transformUnits[index];
            TransformSize transformSize = transformUnit.size();
            residualUnits[index] = new TransformResidualUnit(
                    transformUnit.position(),
                    transformSize,
                    -1,
                    new int[transformSize.widthPixels() * transformSize.heightPixels()],
                    0
            );
        }
        return residualUnits;
    }

    /// Creates one immutable decoded plane from row-major integer samples.
    ///
    /// @param samples the row-major integer samples
    /// @return one immutable decoded plane
    private static DecodedPlane createPlane(int[][] samples) {
        int height = samples.length;
        int width = samples[0].length;
        short[] packed = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                packed[y * width + x] = (short) samples[y][x];
            }
        }
        return new DecodedPlane(width, height, width, packed);
    }

    /// Creates one minimal reduced-still-picture sequence header for syntax-result fixtures.
    ///
    /// @return one minimal reduced-still-picture sequence header for syntax-result fixtures
    private static SequenceHeader createSequenceHeader() {
        return new SequenceHeader(
                0,
                8,
                8,
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
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        AvifPixelFormat.I400,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates one default segmentation-feature table.
    ///
    /// @return one default segmentation-feature table
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int segment = 0; segment < segments.length; segment++) {
            segments[segment] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
