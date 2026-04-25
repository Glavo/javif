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
import org.glavo.avif.internal.av1.model.TransformType;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.glavo.avif.testutil.HexFixtureResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileResidualSyntaxReader`.
@NotNullByDefault
final class TileResidualSyntaxReaderTest {
    /// The default synthetic frame width used by residual-syntax tests.
    private static final int DEFAULT_FRAME_WIDTH = 64;

    /// The default synthetic frame height used by residual-syntax tests.
    private static final int DEFAULT_FRAME_HEIGHT = 64;

    /// The padded `levels` grid size used by the mirrored `TX_4X4` chroma oracle.
    private static final int FOUR_BY_FOUR_LEVEL_GRID_SIZE = 8;

    /// The generated named-fixture resource backing deterministic residual payload tests.
    private static final String TILE_RESIDUAL_FIXTURE_RESOURCE_PATH =
            "av1/fixtures/generated/tile-residual-fixtures.txt";

    /// The `dav1d` low-token context offsets for square, wide, and tall 2D transforms.
    private static final int @Unmodifiable [] @Unmodifiable [] @Unmodifiable [] LEVEL_CONTEXT_OFFSETS = {
            {
                    {0, 1, 6, 6, 21},
                    {1, 6, 6, 21, 21},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            },
            {
                    {0, 16, 6, 6, 21},
                    {16, 16, 6, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21},
                    {16, 16, 21, 21, 21}
            },
            {
                    {0, 11, 11, 11, 11},
                    {11, 11, 11, 11, 11},
                    {6, 6, 21, 21, 21},
                    {6, 21, 21, 21, 21},
                    {21, 21, 21, 21, 21}
            }
    };

    /// Verifies that a single 4x4 transform block can decode `txb_skip = true` as an all-zero residual unit.
    @Test
    void readsAllZeroResidualForSingleTransformBlock() {
        byte[] payload = findPayloadForResidualFlags(BlockSize.SIZE_4X4, new boolean[]{true});
        TileDecodeContext tileContext = createTileContext(payload);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_4X4, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformUnit transformUnit = transformLayout.lumaUnits()[0];
        int coefficientSkipContext = neighborContext.lumaCoefficientSkipContext(BlockSize.SIZE_4X4, transformUnit);

        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        TransformResidualUnit residualUnit = residualLayout.lumaUnits()[0];

        assertFalse(header.skip());
        assertEquals(0, coefficientSkipContext);
        assertEquals(TransformSize.TX_4X4, transformUnit.size());
        assertTrue(residualUnit.allZero());
        assertEquals(-1, residualUnit.endOfBlockIndex());
        assertArrayEquals(new int[16], residualUnit.coefficients());
        assertEquals(0x40, residualUnit.coefficientContextByte());
    }

    /// Verifies that a supported non-zero transform block decodes a real DC coefficient.
    @Test
    void readsDcOnlyResidualCoefficientForSingleTransformBlock() {
        byte[] payload = findPayloadForDcOnlyResidual(BlockSize.SIZE_4X4);
        TileDecodeContext tileContext = createTileContext(payload);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_4X4, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];

        assertFalse(residualUnit.allZero());
        assertEquals(0, residualUnit.endOfBlockIndex());
        assertTrue(Math.abs(residualUnit.dcCoefficient()) >= 1);
        assertEquals(expectedNonZeroCoefficientContextByte(residualUnit.dcCoefficient()), residualUnit.coefficientContextByte());
        assertEquals(residualUnit.dcCoefficient(), residualUnit.coefficients()[0]);
    }

    /// Verifies that the residual reader supports the first scanned AC coefficient in addition to DC.
    @Test
    void readsResidualWithFirstScannedAcCoefficientForSingleTransformBlock() {
        byte[] payload = findPayloadForSingleAcResidual(BlockSize.SIZE_4X4);
        TileDecodeContext tileContext = createTileContext(payload);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_4X4, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
        int[] coefficients = residualUnit.coefficients();
        int firstAcIndex = expectedFirstAcCoefficientIndex(transformLayout.lumaUnits()[0].size());

        assertFalse(residualUnit.allZero());
        assertEquals(1, residualUnit.endOfBlockIndex());
        assertTrue(coefficients[firstAcIndex] != 0);
        assertEquals(expectedCoefficientContextByte(coefficients), residualUnit.coefficientContextByte());
    }

    /// Verifies that the `TX_4X4` residual reader now supports multiple scanned coefficients.
    @Test
    void readsMultiCoefficientResidualForSingleTransformBlock() {
        byte[] payload = findPayloadForMultiCoefficientResidual(BlockSize.SIZE_4X4);
        TileDecodeContext tileContext = createTileContext(payload);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_4X4, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
        int[] coefficients = residualUnit.coefficients();

        assertFalse(residualUnit.allZero());
        assertTrue(residualUnit.endOfBlockIndex() > 1);
        assertTrue(coefficients[expectedFourByFourOutputIndex(residualUnit.endOfBlockIndex())] != 0);
        assertTrue(countNonZeroCoefficients(coefficients) >= 2);
        assertEquals(expectedCoefficientContextByte(coefficients), residualUnit.coefficientContextByte());
    }

    /// Verifies that the larger-transform residual path now supports multi-coefficient `TX_8X8` units.
    @Test
    void readsMultiCoefficientResidualForLargestEightByEightTransformBlock() {
        byte[] payload = findPayloadForLargestTransformMultiCoefficientResidual(BlockSize.SIZE_8X8);
        TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header =
                blockHeaderReader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
        int[] coefficients = residualUnit.coefficients();

        assertEquals(TransformSize.TX_8X8, transformLayout.uniformLumaTransformSize());
        assertEquals(TransformSize.TX_8X8, residualUnit.size());
        assertFalse(residualUnit.allZero());
        assertTrue(residualUnit.endOfBlockIndex() > 1);
        assertTrue(coefficients[expectedOutputIndex(residualUnit.size(), residualUnit.endOfBlockIndex())] != 0);
        assertTrue(countNonZeroCoefficients(coefficients) >= 2);
        assertEquals(expectedCoefficientContextByte(coefficients), residualUnit.coefficientContextByte());
    }

    /// Verifies that a minimal `I420` block produces stable all-zero chroma residual units.
    @Test
    void readsAllZeroChromaResidualUnitsForMinimalI420Block() {
        byte[] payload = findPayloadForAllZeroMinimalI420ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload);

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_8X8, transformLayout.uniformLumaTransformSize());
        assertEquals(TransformSize.TX_4X4, transformLayout.chromaTransformSize());
        assertTrue(expectedResidualLayout.chromaUUnits()[0].allZero());
        assertTrue(expectedResidualLayout.chromaVUnits()[0].allZero());
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a minimal `I420` block produces stable chroma DC-only residual units.
    @Test
    void readsDcOnlyChromaResidualUnitsForMinimalI420Block() {
        byte[] payload = findPayloadForDcOnlyMinimalI420ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];
        TransformResidualUnit expectedChromaV = expectedResidualLayout.chromaVUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_4X4, transformLayout.chromaTransformSize());
        assertFalse(expectedChromaU.allZero());
        assertFalse(expectedChromaV.allZero());
        assertEquals(0, expectedChromaU.endOfBlockIndex());
        assertEquals(0, expectedChromaV.endOfBlockIndex());
        assertTrue(Math.abs(expectedChromaU.dcCoefficient()) >= 1);
        assertTrue(Math.abs(expectedChromaV.dcCoefficient()) >= 1);
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a minimal `I420` block now supports one bitstream-derived multi-coefficient chroma-U residual unit.
    @Test
    void readsMultiCoefficientChromaUResidualUnitForMinimalI420Block() {
        byte[] payload = findPayloadForMultiCoefficientMinimalI420ChromaUResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_4X4, transformLayout.chromaTransformSize());
        assertTrue(hasMultiCoefficientResidual(expectedChromaU));
        assertTrue(expectedChromaU.coefficients()[expectedFourByFourOutputIndex(expectedChromaU.endOfBlockIndex())] != 0);
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a larger `I420` block can expose one bitstream-derived multi-coefficient
    /// `TX_8X8` chroma-U residual unit.
    @Test
    void readsMultiCoefficientChromaUResidualUnitForLargerI420Block() {
        byte[] payload = findPayloadForMultiCoefficientLargerTransformI420ChromaUResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST, 16, 16);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_16X16, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedI420ResidualLayout(payload, BlockSize.SIZE_16X16, 16, 16);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_16X16, transformLayout.uniformLumaTransformSize());
        assertEquals(TransformSize.TX_8X8, transformLayout.chromaTransformSize());
        assertEquals(1, residualLayout.chromaUUnits().length);
        assertEquals(1, residualLayout.chromaVUnits().length);
        assertEquals(TransformSize.TX_8X8, expectedChromaU.size());
        assertTrue(hasMultiCoefficientResidual(expectedChromaU));
        assertTrue(expectedChromaU.coefficients()[expectedOutputIndex(expectedChromaU.size(), expectedChromaU.endOfBlockIndex())] != 0);
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a minimal `I422` block produces stable chroma DC-only residual units.
    @Test
    void readsDcOnlyChromaResidualUnitsForMinimalI422Block() {
        byte[] payload = findPayloadForDcOnlyMinimalI422ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I422, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalChromaResidualLayout(payload, AvifPixelFormat.I422);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];
        TransformResidualUnit expectedChromaV = expectedResidualLayout.chromaVUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.RTX_4X8, transformLayout.chromaTransformSize());
        assertFalse(expectedChromaU.allZero());
        assertFalse(expectedChromaV.allZero());
        assertEquals(0, expectedChromaU.endOfBlockIndex());
        assertEquals(0, expectedChromaV.endOfBlockIndex());
        assertTrue(Math.abs(expectedChromaU.dcCoefficient()) >= 1);
        assertTrue(Math.abs(expectedChromaV.dcCoefficient()) >= 1);
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a larger `I422` block can expose one bitstream-derived multi-coefficient
    /// `RTX_8X16` chroma-U residual unit.
    @Test
    void readsMultiCoefficientChromaUResidualUnitForLargerI422Block() {
        byte[] payload = findPayloadForMultiCoefficientLargerTransformI422ChromaUResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I422, FrameHeader.TransformMode.LARGEST, 16, 16);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_16X16, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout =
                decodeExpectedChromaResidualLayout(payload, AvifPixelFormat.I422, BlockSize.SIZE_16X16, 16, 16);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_16X16, transformLayout.uniformLumaTransformSize());
        assertEquals(TransformSize.RTX_8X16, transformLayout.chromaTransformSize());
        assertEquals(1, residualLayout.chromaUUnits().length);
        assertEquals(1, residualLayout.chromaVUnits().length);
        assertEquals(TransformSize.RTX_8X16, expectedChromaU.size());
        assertTrue(hasMultiCoefficientResidual(expectedChromaU));
        assertTrue(expectedChromaU.coefficients()[expectedOutputIndex(expectedChromaU.size(), expectedChromaU.endOfBlockIndex())] != 0);
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that a minimal `I444` block can expose bitstream-derived non-zero larger-transform
    /// chroma residual units with no chroma subsampling.
    @Test
    void readsNonZeroChromaResidualUnitsForMinimalI444Block() {
        byte[] payload = findPayloadForNonZeroMinimalI444ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I444, FrameHeader.TransformMode.LARGEST);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalChromaResidualLayout(payload, AvifPixelFormat.I444);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];
        TransformResidualUnit expectedChromaV = expectedResidualLayout.chromaVUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(TransformSize.TX_8X8, transformLayout.chromaTransformSize());
        assertEquals(1, residualLayout.chromaUUnits().length);
        assertEquals(1, residualLayout.chromaVUnits().length);
        assertFalse(expectedChromaU.allZero());
        assertFalse(expectedChromaV.allZero());
        assertTrue(hasMultiCoefficientResidual(expectedChromaU) || hasMultiCoefficientResidual(expectedChromaV));
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that minimal `I420` chroma residual decoding still works for odd visible frame dimensions.
    @Test
    void readsDcOnlyChromaResidualUnitsForMinimalI420BlockWithOddFrameDimensions() {
        byte[] payload = findPayloadForDcOnlyMinimalI420ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST, 7, 7);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload, 7, 7);
        TransformResidualUnit expectedChromaU = expectedResidualLayout.chromaUUnits()[0];
        TransformResidualUnit expectedChromaV = expectedResidualLayout.chromaVUnits()[0];

        assertTrue(header.hasChroma());
        assertEquals(2, transformLayout.visibleWidth4());
        assertEquals(2, transformLayout.visibleHeight4());
        assertEquals(4, exactVisibleChromaWidthPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertEquals(4, exactVisibleChromaHeightPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertTrue(residualLayout.hasChromaUnits());
        assertFalse(expectedChromaU.allZero());
        assertFalse(expectedChromaV.allZero());
        assertEquals(0, expectedChromaU.endOfBlockIndex());
        assertEquals(0, expectedChromaV.endOfBlockIndex());
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that clipped odd-height visible chroma footprints still expose one modeled chroma unit
    /// whose exact visible height is clipped.
    @Test
    void readsClippedChromaResidualUnitsForMinimalI420BlockWithClippedVisibleChromaFootprint() {
        byte[] payload = findPayloadForDcOnlyMinimalI420ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST, 7, 5);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload, 7, 5);

        assertTrue(header.hasChroma());
        assertEquals(2, transformLayout.visibleWidth4());
        assertEquals(2, transformLayout.visibleHeight4());
        assertEquals(4, exactVisibleChromaWidthPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertEquals(3, exactVisibleChromaHeightPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertTrue(expectedResidualLayout.hasChromaUnits());
        assertTrue(residualLayout.hasChromaUnits());
        assertEquals(4, residualLayout.chromaUUnits()[0].visibleWidthPixels());
        assertEquals(3, residualLayout.chromaUUnits()[0].visibleHeightPixels());
        assertEquals(4, residualLayout.chromaVUnits()[0].visibleWidthPixels());
        assertEquals(3, residualLayout.chromaVUnits()[0].visibleHeightPixels());
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that clipped odd-height visible `I422` chroma footprints still expose one modeled
    /// chroma unit whose exact visible height is clipped while the wider chroma height remains
    /// un-subsampled.
    @Test
    void readsClippedChromaResidualUnitsForMinimalI422BlockWithClippedVisibleChromaFootprint() {
        byte[] payload = findPayloadForDcOnlyMinimalI422ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, AvifPixelFormat.I422, FrameHeader.TransformMode.LARGEST, 7, 5);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        ResidualLayout expectedResidualLayout = decodeExpectedMinimalChromaResidualLayout(payload, AvifPixelFormat.I422, 7, 5);

        assertTrue(header.hasChroma());
        assertEquals(2, transformLayout.visibleWidth4());
        assertEquals(2, transformLayout.visibleHeight4());
        assertEquals(4, exactVisibleChromaWidthPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertEquals(5, exactVisibleChromaHeightPixels(tileContext, position, BlockSize.SIZE_8X8));
        assertEquals(TransformSize.RTX_4X8, transformLayout.chromaTransformSize());
        assertTrue(expectedResidualLayout.hasChromaUnits());
        assertTrue(residualLayout.hasChromaUnits());
        assertEquals(4, residualLayout.chromaUUnits()[0].visibleWidthPixels());
        assertEquals(5, residualLayout.chromaUUnits()[0].visibleHeightPixels());
        assertEquals(4, residualLayout.chromaVUnits()[0].visibleWidthPixels());
        assertEquals(5, residualLayout.chromaVUnits()[0].visibleHeightPixels());
        assertResidualLayoutEquals(expectedResidualLayout, residualLayout);
    }

    /// Verifies that one skipped synthetic `I420` block can expose multiple whole chroma residual
    /// units when the transform layout carries a smaller chroma transform size than the visible
    /// block-level chroma footprint.
    @Test
    void readsAllZeroMultiUnitChromaResidualsForSyntheticI420Leaf() {
        TileDecodeContext tileContext = createTileContext(new byte[0], AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST, 16, 16);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);
        TileBlockHeaderReader.BlockHeader header = createSkippedSyntheticI420Header(position, BlockSize.SIZE_16X16);
        TransformLayout transformLayout = createSyntheticI420TransformLayout(
                position,
                BlockSize.SIZE_16X16,
                4,
                4,
                16,
                16,
                TransformSize.TX_4X4
        );

        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);

        assertEquals(4, residualLayout.chromaUUnits().length);
        assertEquals(4, residualLayout.chromaVUnits().length);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4), residualLayout.chromaUUnits()[0]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 4, 4), residualLayout.chromaUUnits()[1]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 4), residualLayout.chromaUUnits()[2]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 4, 4), residualLayout.chromaUUnits()[3]);
        assertResidualLayoutEquals(
                new ResidualLayout(
                        position,
                        BlockSize.SIZE_16X16,
                        residualLayout.lumaUnits(),
                        residualLayout.chromaUUnits(),
                        residualLayout.chromaVUnits()
                ),
                residualLayout
        );
    }

    /// Verifies that one skipped synthetic `I420` block clips the last chroma residual units to
    /// their exact visible fringe footprints.
    @Test
    void readsClippedMultiUnitChromaResidualsForSyntheticI420Leaf() {
        TileDecodeContext tileContext = createTileContext(new byte[0], AvifPixelFormat.I420, FrameHeader.TransformMode.LARGEST, 14, 14);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);
        TileBlockHeaderReader.BlockHeader header = createSkippedSyntheticI420Header(position, BlockSize.SIZE_16X16);
        TransformLayout transformLayout = createSyntheticI420TransformLayout(
                position,
                BlockSize.SIZE_16X16,
                4,
                4,
                14,
                14,
                TransformSize.TX_4X4
        );

        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);

        assertEquals(4, residualLayout.chromaUUnits().length);
        assertEquals(4, residualLayout.chromaVUnits().length);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4), residualLayout.chromaUUnits()[0]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 3, 4), residualLayout.chromaUUnits()[1]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 3), residualLayout.chromaUUnits()[2]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 3, 3), residualLayout.chromaUUnits()[3]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 0), TransformSize.TX_4X4, 4, 4), residualLayout.chromaVUnits()[0]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 0), TransformSize.TX_4X4, 3, 4), residualLayout.chromaVUnits()[1]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(0, 2), TransformSize.TX_4X4, 4, 3), residualLayout.chromaVUnits()[2]);
        assertResidualUnitEquals(createAllZeroResidualUnit(new BlockPosition(2, 2), TransformSize.TX_4X4, 3, 3), residualLayout.chromaVUnits()[3]);
    }

    /// Verifies that non-zero coefficient state from one 4x4 unit affects the next unit's skip context.
    @Test
    void propagatesCoefficientSkipContextAcrossFourByFourUnits() {
        byte[] payload = findPayloadForResidualFlags(BlockSize.SIZE_8X8, new boolean[]{false, true});
        TileDecodeContext tileContext = createTileContext(payload);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        BlockNeighborContext decodeNeighborContext = BlockNeighborContext.create(tileContext);
        TileBlockHeaderReader.BlockHeader decodeHeader =
                blockHeaderReader.read(position, BlockSize.SIZE_8X8, decodeNeighborContext, false);
        TransformLayout decodeTransformLayout = transformLayoutReader.read(decodeHeader, decodeNeighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(decodeHeader, decodeTransformLayout, decodeNeighborContext);
        TransformResidualUnit[] residualUnits = residualLayout.lumaUnits();

        assertFalse(decodeHeader.skip());
        assertEquals(4, residualUnits.length);
        assertFalse(residualUnits[0].allZero());
        assertTrue(residualUnits[0].endOfBlockIndex() >= 0);
        assertEquals(expectedCoefficientContextByte(residualUnits[0].coefficients()), residualUnits[0].coefficientContextByte());
        assertTrue(residualUnits[1].allZero());
        assertEquals(0x40, residualUnits[1].coefficientContextByte());

        BlockNeighborContext oracleNeighborContext = BlockNeighborContext.create(tileContext);
        TileBlockHeaderReader.BlockHeader oracleHeader =
                blockHeaderReader.read(position, BlockSize.SIZE_8X8, oracleNeighborContext, false);
        TransformLayout oracleTransformLayout = transformLayoutReader.read(oracleHeader, oracleNeighborContext);
        TransformUnit[] transformUnits = oracleTransformLayout.lumaUnits();

        assertEquals(1, oracleNeighborContext.lumaCoefficientSkipContext(BlockSize.SIZE_8X8, transformUnits[0]));
        oracleNeighborContext.updateLumaCoefficientContext(transformUnits[0], residualUnits[0].coefficientContextByte());
        assertEquals(expectedSecondUnitSkipContext(residualUnits[0].coefficientContextByte()),
                oracleNeighborContext.lumaCoefficientSkipContext(BlockSize.SIZE_8X8, transformUnits[1]));
    }

    /// Returns the fixture-backed payload whose first residual flags match the requested sequence.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @return the fixture-backed payload whose residual flags match the requested prefix
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags) {
        Objects.requireNonNull(blockSize, "blockSize");
        Objects.requireNonNull(expectedFlags, "expectedFlags");
        if (blockSize == BlockSize.SIZE_4X4 && expectedFlags.length == 1 && expectedFlags[0]) {
            return readTileResidualFixture("4x4-all-zero");
        }
        if (blockSize == BlockSize.SIZE_8X8 && expectedFlags.length == 2 && !expectedFlags[0] && expectedFlags[1]) {
            return readTileResidualFixture("8x8-flag-false-true");
        }
        throw new IllegalArgumentException("Unsupported residual-flag fixture request for " + blockSize);
    }

    /// Returns the fixture-backed payload whose first residual unit is supported and DC-only.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return the fixture-backed payload whose first residual unit is supported and DC-only
    private static byte[] findPayloadForDcOnlyResidual(BlockSize blockSize) {
        if (blockSize == BlockSize.SIZE_4X4) {
            return readTileResidualFixture("4x4-dc-only");
        }
        throw new IllegalArgumentException("Unsupported DC-only residual fixture request for " + blockSize);
    }

    /// Returns the fixture-backed payload whose first residual unit exposes the first scanned AC coefficient.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return the fixture-backed payload whose first residual unit exposes the first scanned AC coefficient
    private static byte[] findPayloadForSingleAcResidual(BlockSize blockSize) {
        if (blockSize == BlockSize.SIZE_4X4) {
            return readTileResidualFixture("4x4-single-ac");
        }
        throw new IllegalArgumentException("Unsupported first-AC residual fixture request for " + blockSize);
    }

    /// Returns the fixture-backed payload whose first residual unit exposes a supported multi-coefficient `TX_4X4`
    /// residual.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return the fixture-backed payload whose first residual unit exposes a supported multi-coefficient residual
    private static byte[] findPayloadForMultiCoefficientResidual(BlockSize blockSize) {
        if (blockSize == BlockSize.SIZE_4X4) {
            return readTileResidualFixture("4x4-multi");
        }
        throw new IllegalArgumentException("Unsupported multi-coefficient residual fixture request for " + blockSize);
    }

    /// Returns the fixture-backed payload whose first residual unit exposes a supported multi-coefficient larger
    /// transform.
    ///
    /// @param blockSize the coded block size whose largest-transform residual should be decoded
    /// @return the fixture-backed payload whose first residual unit exposes a supported multi-coefficient
    /// larger transform
    private static byte[] findPayloadForLargestTransformMultiCoefficientResidual(BlockSize blockSize) {
        if (blockSize == BlockSize.SIZE_8X8) {
            return readTileResidualFixture("8x8-largest-multi");
        }
        throw new IllegalArgumentException("Unsupported larger-transform residual fixture request for " + blockSize);
    }

    /// Returns the fixture-backed payload whose minimal `I420` chroma residuals are both all-zero.
    ///
    /// @return the fixture-backed payload whose minimal `I420` chroma residuals are both all-zero
    private static byte[] findPayloadForAllZeroMinimalI420ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I420, true, true);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I420, false, true);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced minimal all-zero I420 chroma residuals");
    }

    /// Returns the fixture-backed payload whose minimal `I420` chroma residuals are both DC-only and non-zero.
    ///
    /// @return the fixture-backed payload whose minimal `I420` chroma residuals are both DC-only and non-zero
    private static byte[] findPayloadForDcOnlyMinimalI420ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I420, true, false);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I420, false, false);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced minimal non-zero I420 chroma residuals");
    }

    /// Returns the fixture-backed payload whose minimal `I422` chroma residuals are both DC-only and non-zero.
    ///
    /// @return the fixture-backed payload whose minimal `I422` chroma residuals are both DC-only and non-zero
    private static byte[] findPayloadForDcOnlyMinimalI422ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I422, true, false);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I422, false, false);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced minimal non-zero I422 chroma residuals");
    }

    /// Returns the fixture-backed payload whose minimal `I444` chroma residuals include non-zero
    /// larger-transform chroma tokens.
    ///
    /// @return the fixture-backed payload whose minimal `I444` chroma residuals include non-zero chroma tokens
    private static byte[] findPayloadForNonZeroMinimalI444ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I444, true, false);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalChromaResidual(AvifPixelFormat.I444, false, false);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced minimal non-zero I444 chroma residuals");
    }

    /// Returns the fixture-backed payload whose minimal `I420` chroma-U residual exposes one supported
    /// multi-coefficient `TX_4X4` unit.
    ///
    /// @return the fixture-backed payload whose minimal `I420` chroma-U residual exposes one multi-coefficient unit
    private static byte[] findPayloadForMultiCoefficientMinimalI420ChromaUResidual() {
        byte[] isolatedPayload = findPayloadForMultiCoefficientMinimalChromaUResidual(AvifPixelFormat.I420, true);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMultiCoefficientMinimalChromaUResidual(AvifPixelFormat.I420, false);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced a minimal multi-coefficient I420 chroma-U residual");
    }

    /// Returns the fixture-backed payload whose larger-transform `I420` chroma-U residual exposes one supported
    /// multi-coefficient unit.
    ///
    /// @return the fixture-backed payload whose larger-transform `I420` chroma-U residual exposes one
    /// multi-coefficient unit
    private static byte[] findPayloadForMultiCoefficientLargerTransformI420ChromaUResidual() {
        byte[] isolatedPayload = findPayloadForMultiCoefficientLargerTransformChromaUResidual(
                AvifPixelFormat.I420,
                BlockSize.SIZE_16X16,
                16,
                16,
                true
        );
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMultiCoefficientLargerTransformChromaUResidual(
                AvifPixelFormat.I420,
                BlockSize.SIZE_16X16,
                16,
                16,
                false
        );
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced a larger-transform multi-coefficient I420 chroma-U residual");
    }

    /// Returns the fixture-backed payload whose larger-transform `I422` chroma-U residual exposes one supported
    /// multi-coefficient unit.
    ///
    /// @return the fixture-backed payload whose larger-transform `I422` chroma-U residual exposes one
    /// multi-coefficient unit
    private static byte[] findPayloadForMultiCoefficientLargerTransformI422ChromaUResidual() {
        byte[] isolatedPayload = findPayloadForMultiCoefficientLargerTransformChromaUResidual(
                AvifPixelFormat.I422,
                BlockSize.SIZE_16X16,
                16,
                16,
                true
        );
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMultiCoefficientLargerTransformChromaUResidual(
                AvifPixelFormat.I422,
                BlockSize.SIZE_16X16,
                16,
                16,
                false
        );
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No fixture-backed payload produced a larger-transform multi-coefficient I422 chroma-U residual");
    }

    /// Returns the fixture-backed payload whose minimal chroma residuals match the requested mode, or `null`.
    ///
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param requireAllZeroLuma whether the leading luma unit should stay all-zero to isolate chroma
    /// @param requireAllZeroChroma whether both chroma units must be all-zero instead of DC-only
    /// @return the fixture-backed payload whose minimal chroma residuals match the requested mode, or `null`
    private static byte[] findPayloadForMinimalChromaResidual(
            AvifPixelFormat pixelFormat,
            boolean requireAllZeroLuma,
            boolean requireAllZeroChroma
    ) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        if (pixelFormat == AvifPixelFormat.I420) {
            if (requireAllZeroChroma) {
                return readTileResidualFixture(requireAllZeroLuma
                        ? "i420-minimal-all-zero-isolated"
                        : "i420-minimal-all-zero-fallback");
            }
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i420-minimal-dc-isolated"
                    : "i420-minimal-dc-fallback");
        }
        if (pixelFormat == AvifPixelFormat.I422 && !requireAllZeroChroma) {
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i422-minimal-dc-isolated"
                    : "i422-minimal-dc-fallback");
        }
        if (pixelFormat == AvifPixelFormat.I444 && !requireAllZeroChroma) {
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i444-minimal-nonzero-isolated"
                    : "i444-minimal-nonzero-fallback");
        }
        return null;
    }

    /// Returns the fixture-backed payload whose minimal chroma-U residual exposes one supported multi-coefficient
    /// transform unit, or `null`.
    ///
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param requireAllZeroLuma whether the leading luma unit should stay all-zero to isolate chroma
    /// @return the fixture-backed payload whose minimal chroma-U residual exposes one multi-coefficient unit,
    /// or `null`
    private static byte[] findPayloadForMultiCoefficientMinimalChromaUResidual(
            AvifPixelFormat pixelFormat,
            boolean requireAllZeroLuma
    ) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        if (pixelFormat == AvifPixelFormat.I420) {
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i420-minimal-multi-u-isolated"
                    : "i420-minimal-multi-u-fallback");
        }
        return null;
    }

    /// Returns the fixture-backed payload whose larger-transform chroma-U residual exposes one supported
    /// multi-coefficient unit, or `null`.
    ///
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param blockSize the block size to decode
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @param requireAllZeroLuma whether the leading luma residual unit should stay all-zero
    /// @return the fixture-backed payload whose larger-transform chroma-U residual exposes one multi-coefficient
    /// unit, or `null`
    private static byte[] findPayloadForMultiCoefficientLargerTransformChromaUResidual(
            AvifPixelFormat pixelFormat,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            boolean requireAllZeroLuma
    ) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        Objects.requireNonNull(blockSize, "blockSize");
        if (blockSize != BlockSize.SIZE_16X16 || codedWidth != 16 || codedHeight != 16) {
            return null;
        }
        if (pixelFormat == AvifPixelFormat.I420) {
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i420-tx8x8-multi-u-isolated"
                    : "i420-tx8x8-multi-u-fallback");
        }
        if (pixelFormat == AvifPixelFormat.I422) {
            return readTileResidualFixture(requireAllZeroLuma
                    ? "i422-rtx8x16-multi-u-isolated"
                    : "i422-rtx8x16-multi-u-fallback");
        }
        return null;
    }

    /// Reads one named residual payload fixture from the generated fixture resource.
    ///
    /// @param fixtureName the logical fixture name inside the generated resource
    /// @return the decoded payload bytes for the supplied fixture
    private static byte[] readTileResidualFixture(String fixtureName) {
        return HexFixtureResources.readNamedBytes(TILE_RESIDUAL_FIXTURE_RESOURCE_PATH, fixtureName);
    }

    /// Returns the stored coefficient-context byte expected for one non-zero DC coefficient.
    ///
    /// @param signedDcCoefficient the decoded signed DC coefficient
    /// @return the stored coefficient-context byte expected for one non-zero DC coefficient
    private static int expectedNonZeroCoefficientContextByte(int signedDcCoefficient) {
        return Math.min(Math.abs(signedDcCoefficient), 63) | (signedDcCoefficient > 0 ? 0x80 : 0);
    }

    /// Returns the expected stored coefficient-context byte for one dense residual coefficient array.
    ///
    /// @param coefficients the dense transform-domain coefficient array in natural raster order
    /// @return the expected stored coefficient-context byte
    private static int expectedCoefficientContextByte(int[] coefficients) {
        int cumulativeLevel = 0;
        for (int coefficient : coefficients) {
            cumulativeLevel += Math.abs(coefficient);
        }
        int magnitude = Math.min(cumulativeLevel, 63);
        int dcCoefficient = coefficients[0];
        if (dcCoefficient == 0) {
            return magnitude | 0x40;
        }
        return magnitude | (dcCoefficient > 0 ? 0x80 : 0);
    }

    /// Returns the expected natural-raster index of the first scanned AC coefficient.
    ///
    /// @param transformSize the active transform size
    /// @return the expected natural-raster index of the first scanned AC coefficient
    private static int expectedFirstAcCoefficientIndex(TransformSize transformSize) {
        return transformSize.widthPixels() > transformSize.heightPixels() ? 1 : transformSize.widthPixels();
    }

    /// Returns the natural-raster output index for one `TX_4X4` scan position.
    ///
    /// @param scanIndex the zero-based `TX_4X4` scan index
    /// @return the natural-raster output index for the supplied `TX_4X4` scan position
    private static int expectedFourByFourOutputIndex(int scanIndex) {
        int[] scan = {0, 4, 1, 2, 5, 8, 12, 9, 6, 3, 7, 10, 13, 14, 11, 15};
        return scan[scanIndex];
    }

    /// Returns the natural-raster output index for one larger-transform scan position.
    ///
    /// @param transformSize the active transform size
    /// @param scanIndex the zero-based scan index
    /// @return the natural-raster output index for the supplied scan position
    private static int expectedOutputIndex(TransformSize transformSize, int scanIndex) {
        if (transformSize == TransformSize.TX_4X4) {
            return expectedFourByFourOutputIndex(scanIndex);
        }
        return expectedScan(transformSize)[scanIndex];
    }

    /// Builds the `dav1d`-compatible scan used by the larger-transform residual test oracle.
    ///
    /// @param transformSize the active transform size
    /// @return the `dav1d`-compatible scan for the supplied transform size
    private static int[] expectedScan(TransformSize transformSize) {
        int codedWidth = clippedCoefficientWidth(transformSize);
        int codedHeight = clippedCoefficientHeight(transformSize);
        int outputWidth = transformSize.widthPixels();
        int[] scan = new int[codedWidth * codedHeight];
        int nextIndex = 0;
        for (int diagonal = 0; diagonal < codedWidth + codedHeight - 1; diagonal++) {
            int rowStart = Math.max(0, diagonal - (codedWidth - 1));
            int rowEnd = Math.min(codedHeight - 1, diagonal);
            boolean descendingRows;
            if (codedWidth == codedHeight) {
                descendingRows = (diagonal & 1) == 1;
            } else {
                descendingRows = codedWidth > codedHeight;
            }
            if (descendingRows) {
                for (int row = rowEnd; row >= rowStart; row--) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * outputWidth + column;
                }
            } else {
                for (int row = rowStart; row <= rowEnd; row++) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * outputWidth + column;
                }
            }
        }
        return scan;
    }

    /// Creates the padded larger-transform `levels` grid used by coefficient token contexts.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @return a padded zero-filled level grid
    private static int[][] createGenericLevelGrid(TransformSize transformSize, TransformType transformType) {
        int width;
        int height;
        if (!transformType.oneDimensional()) {
            width = clippedCoefficientWidth(transformSize);
            height = clippedCoefficientHeight(transformSize);
        } else if (verticalOneDimensional(transformType)) {
            width = 4 << Math.min(transformSize.log2Width4(), 3);
            height = coefficientCount(transformSize) / width;
        } else {
            width = 4 << Math.min(transformSize.log2Height4(), 3);
            height = coefficientCount(transformSize) / width;
        }
        return new int[width + 5][height + 5];
    }

    /// Returns the coefficient-context grid X coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid X coordinate
    private static int genericLevelX(TransformSize transformSize, TransformType transformType, int scanIndex) {
        if (!transformType.oneDimensional()) {
            return expectedOutputIndex(transformSize, scanIndex) & (transformSize.widthPixels() - 1);
        }
        int log2Primary = verticalOneDimensional(transformType)
                ? Math.min(transformSize.log2Width4(), 3)
                : Math.min(transformSize.log2Height4(), 3);
        return scanIndex & ((4 << log2Primary) - 1);
    }

    /// Returns the coefficient-context grid Y coordinate for one larger-transform scan index.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param scanIndex the zero-based scan index
    /// @return the coefficient-context grid Y coordinate
    private static int genericLevelY(TransformSize transformSize, TransformType transformType, int scanIndex) {
        if (!transformType.oneDimensional()) {
            return expectedOutputIndex(transformSize, scanIndex) >> (transformSize.log2Width4() + 2);
        }
        int log2Primary = verticalOneDimensional(transformType)
                ? Math.min(transformSize.log2Width4(), 3)
                : Math.min(transformSize.log2Height4(), 3);
        return scanIndex >> (log2Primary + 2);
    }

    /// Returns the base-token context for one larger-transform coefficient.
    ///
    /// @param transformSize the active transform size
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the base-token context for the supplied coefficient
    private static int genericBaseTokenContext(
            TransformSize transformSize,
            TransformType transformType,
            int[][] levelBytes,
            int x,
            int y
    ) {
        int magnitude = genericHighMagnitude(transformType, levelBytes, x, y);
        int offset;
        if (transformType.oneDimensional()) {
            magnitude += levelBytes[x][y + 3] + levelBytes[x][y + 4];
            offset = 26 + (y > 1 ? 10 : y * 5);
        } else {
            magnitude += levelBytes[x][y + 2] + levelBytes[x + 2][y];
            offset = LEVEL_CONTEXT_OFFSETS[levelContextOffsetIndex(transformSize)]
                    [Math.min(y, 4)][Math.min(x, 4)];
        }
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one larger-transform non-DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied coefficient
    private static int genericHighTokenContext(TransformType transformType, int[][] levelBytes, int x, int y) {
        int magnitude = genericHighMagnitude(transformType, levelBytes, x, y) & 0x3F;
        int baseContext = genericEndOfBlockHighTokenContext(transformType, x, y);
        return baseContext + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the high-token context for one larger-transform end-of-block coefficient.
    ///
    /// @param transformType the active transform type
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-token context for the supplied end-of-block coefficient
    private static int genericEndOfBlockHighTokenContext(TransformType transformType, int x, int y) {
        if (transformType.oneDimensional()) {
            return y != 0 ? 14 : 7;
        }
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the high-token context for one larger-transform DC coefficient.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @return the high-token context for the DC coefficient
    private static int genericDcHighTokenContext(TransformType transformType, int[][] levelBytes) {
        int magnitude = transformType.oneDimensional()
                ? genericHighMagnitude(transformType, levelBytes, 0, 0)
                : levelBytes[0][1] + levelBytes[1][0] + levelBytes[1][1];
        magnitude &= 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the high-magnitude accumulator for one larger-transform coefficient position.
    ///
    /// @param transformType the active transform type
    /// @param levelBytes the padded local `levels` grid
    /// @param x the coefficient-context grid X coordinate
    /// @param y the coefficient-context grid Y coordinate
    /// @return the high-magnitude accumulator for the supplied coefficient position
    private static int genericHighMagnitude(TransformType transformType, int[][] levelBytes, int x, int y) {
        int magnitude = levelBytes[x][y + 1] + levelBytes[x + 1][y];
        if (!transformType.oneDimensional()) {
            magnitude += levelBytes[x + 1][y + 1];
        } else {
            magnitude += levelBytes[x][y + 2];
        }
        return magnitude;
    }

    /// Returns the low-context offset table index for one two-dimensional transform size.
    ///
    /// @param transformSize the active transform size
    /// @return `0` for square, `1` for wide, or `2` for tall
    private static int levelContextOffsetIndex(TransformSize transformSize) {
        if (transformSize.widthPixels() == transformSize.heightPixels()) {
            return 0;
        }
        return transformSize.widthPixels() > transformSize.heightPixels() ? 1 : 2;
    }

    /// Returns whether a transform type belongs to the vertical one-dimensional class.
    ///
    /// @param transformType the transform type to test
    /// @return whether the supplied transform type belongs to the vertical one-dimensional class
    private static boolean verticalOneDimensional(TransformType transformType) {
        return switch (transformType) {
            case V_DCT, V_ADST, V_FLIPADST -> true;
            default -> false;
        };
    }

    /// Returns the coefficient count modeled by entropy syntax for the supplied transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the modeled coefficient count
    private static int coefficientCount(TransformSize transformSize) {
        return clippedCoefficientWidth(transformSize) * clippedCoefficientHeight(transformSize);
    }

    /// Returns the entropy-coded coefficient width for the supplied transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient width
    private static int clippedCoefficientWidth(TransformSize transformSize) {
        return 4 << Math.min(transformSize.log2Width4(), 3);
    }

    /// Returns the entropy-coded coefficient height for the supplied transform size.
    ///
    /// @param transformSize the active transform size
    /// @return the entropy-coded coefficient height
    private static int clippedCoefficientHeight(TransformSize transformSize) {
        return 4 << Math.min(transformSize.log2Height4(), 3);
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

    /// Returns whether one residual unit exposes at least two decoded non-zero coefficients.
    ///
    /// @param residualUnit the residual unit to inspect
    /// @return whether the supplied residual unit exposes multiple decoded coefficients
    private static boolean hasMultiCoefficientResidual(TransformResidualUnit residualUnit) {
        return !residualUnit.allZero()
                && residualUnit.endOfBlockIndex() > 1
                && countNonZeroCoefficients(residualUnit.coefficients()) >= 2;
    }

    /// Returns the expected skip context for the second 4x4 unit in a `FOUR_BY_FOUR_ONLY` 8x8 block.
    ///
    /// @param firstCoefficientContextByte the stored coefficient-context byte of the first 4x4 unit
    /// @return the expected skip context for the second 4x4 unit
    private static int expectedSecondUnitSkipContext(int firstCoefficientContextByte) {
        return new int[]{1, 2, 2, 2, 3}[Math.min(firstCoefficientContextByte & 0x3F, 4)];
    }

    /// Decodes the expected minimal `I420` residual layout using a luma-only advance plus a chroma oracle.
    ///
    /// @param payload the collected tile entropy payload
    /// @return the expected minimal `I420` residual layout
    private static ResidualLayout decodeExpectedMinimalI420ResidualLayout(byte[] payload) {
        return decodeExpectedMinimalChromaResidualLayout(payload, AvifPixelFormat.I420);
    }

    /// Decodes the expected minimal `I420` residual layout using a luma-only advance plus a chroma oracle.
    ///
    /// @param payload the collected tile entropy payload
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return the expected minimal `I420` residual layout
    private static ResidualLayout decodeExpectedMinimalI420ResidualLayout(byte[] payload, int codedWidth, int codedHeight) {
        return decodeExpectedMinimalChromaResidualLayout(payload, AvifPixelFormat.I420, codedWidth, codedHeight);
    }

    /// Decodes one expected currently supported `I420` residual layout for the supplied block size.
    ///
    /// @param payload the collected tile entropy payload
    /// @param blockSize the block size to decode
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return the expected currently supported `I420` residual layout
    private static ResidualLayout decodeExpectedI420ResidualLayout(
            byte[] payload,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight
    ) {
        return decodeExpectedChromaResidualLayout(payload, AvifPixelFormat.I420, blockSize, codedWidth, codedHeight);
    }

    /// Decodes the expected minimal chroma residual layout using a luma-only advance plus a
    /// chroma oracle.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @return the expected minimal chroma residual layout
    private static ResidualLayout decodeExpectedMinimalChromaResidualLayout(byte[] payload, AvifPixelFormat pixelFormat) {
        return decodeExpectedMinimalChromaResidualLayout(payload, pixelFormat, DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT);
    }

    /// Decodes the expected minimal chroma residual layout using a luma-only advance plus a
    /// chroma oracle.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return the expected minimal chroma residual layout
    private static ResidualLayout decodeExpectedMinimalChromaResidualLayout(
            byte[] payload,
            AvifPixelFormat pixelFormat,
            int codedWidth,
            int codedHeight
    ) {
        return decodeExpectedChromaResidualLayout(payload, pixelFormat, BlockSize.SIZE_8X8, codedWidth, codedHeight);
    }

    /// Decodes one expected currently supported chroma residual layout for the supplied block size.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param blockSize the block size to decode
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return the expected currently supported chroma residual layout
    private static ResidualLayout decodeExpectedChromaResidualLayout(
            byte[] payload,
            AvifPixelFormat pixelFormat,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight
    ) {
        TileDecodeContext tileContext = createTileContext(payload, pixelFormat, FrameHeader.TransformMode.LARGEST, codedWidth, codedHeight);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, blockSize, neighborContext, false);
        if (!header.hasChroma()) {
            throw new IllegalStateException("Synthetic " + pixelFormat + " block did not expose chroma");
        }
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformSize chromaTransformSize = Objects.requireNonNull(transformLayout.chromaTransformSize(), "chromaTransformSize");
        int visibleChromaWidthPixels = exactVisibleChromaWidthPixels(tileContext, position, blockSize);
        int visibleChromaHeightPixels = exactVisibleChromaHeightPixels(tileContext, position, blockSize);
        ResidualLayout lumaResidualLayout = residualSyntaxReader.read(header, lumaOnlyTransformLayout(transformLayout), neighborContext);
        if (header.skip()) {
            return new ResidualLayout(
                    position,
                    blockSize,
                    lumaResidualLayout.lumaUnits(),
                    new TransformResidualUnit[]{createAllZeroResidualUnit(
                            position,
                            chromaTransformSize,
                            visibleChromaWidthPixels,
                            visibleChromaHeightPixels
                    )},
                    new TransformResidualUnit[]{createAllZeroResidualUnit(
                            position,
                            chromaTransformSize,
                            visibleChromaWidthPixels,
                            visibleChromaHeightPixels
                    )}
            );
        }
        TileSyntaxReader syntaxReader = new TileSyntaxReader(tileContext);

        return new ResidualLayout(
                position,
                blockSize,
                lumaResidualLayout.lumaUnits(),
                new TransformResidualUnit[]{readExpectedChromaResidualUnit(
                        syntaxReader,
                        position,
                        chromaTransformSize,
                        visibleChromaWidthPixels,
                        visibleChromaHeightPixels
                )},
                new TransformResidualUnit[]{readExpectedChromaResidualUnit(
                        syntaxReader,
                        position,
                        chromaTransformSize,
                        visibleChromaWidthPixels,
                        visibleChromaHeightPixels
                )}
        );
    }

    /// Returns the exact visible chroma width in pixels for one luma-aligned block.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @param position the tile-relative origin of the owning block
    /// @param blockSize the owning block size
    /// @return the exact visible chroma width in pixels for the supplied block
    private static int exactVisibleChromaWidthPixels(
            TileDecodeContext tileContext,
            BlockPosition position,
            BlockSize blockSize
    ) {
        return exactVisibleChromaSpanPixels(
                position.x4() << 2,
                exactVisibleLumaWidthPixels(tileContext, position, blockSize),
                chromaSubsamplingX(tileContext)
        );
    }

    /// Returns the exact visible chroma height in pixels for one luma-aligned block.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @param position the tile-relative origin of the owning block
    /// @param blockSize the owning block size
    /// @return the exact visible chroma height in pixels for the supplied block
    private static int exactVisibleChromaHeightPixels(
            TileDecodeContext tileContext,
            BlockPosition position,
            BlockSize blockSize
    ) {
        return exactVisibleChromaSpanPixels(
                position.y4() << 2,
                exactVisibleLumaHeightPixels(tileContext, position, blockSize),
                chromaSubsamplingY(tileContext)
        );
    }

    /// Returns the exact visible luma width in pixels for one block clipped against the synthetic frame.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @param position the tile-relative origin of the owning block
    /// @param blockSize the owning block size
    /// @return the exact visible luma width in pixels for the supplied block
    private static int exactVisibleLumaWidthPixels(
            TileDecodeContext tileContext,
            BlockPosition position,
            BlockSize blockSize
    ) {
        return Math.max(0, Math.min(blockSize.widthPixels(), tileContext.width() - (position.x4() << 2)));
    }

    /// Returns the exact visible luma height in pixels for one block clipped against the synthetic frame.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @param position the tile-relative origin of the owning block
    /// @param blockSize the owning block size
    /// @return the exact visible luma height in pixels for the supplied block
    private static int exactVisibleLumaHeightPixels(
            TileDecodeContext tileContext,
            BlockPosition position,
            BlockSize blockSize
    ) {
        return Math.max(0, Math.min(blockSize.heightPixels(), tileContext.height() - (position.y4() << 2)));
    }

    /// Returns the exact visible chroma span that covers one visible luma span under subsampling.
    ///
    /// @param startPixels the luma-grid start coordinate in pixels
    /// @param visibleLumaPixels the visible luma span in pixels
    /// @param subsamplingShift the chroma subsampling shift for the relevant axis
    /// @return the exact visible chroma span in pixels
    private static int exactVisibleChromaSpanPixels(int startPixels, int visibleLumaPixels, int subsamplingShift) {
        int chromaStart = startPixels >> subsamplingShift;
        int chromaEnd = (startPixels + visibleLumaPixels + (1 << subsamplingShift) - 1) >> subsamplingShift;
        return chromaEnd - chromaStart;
    }

    /// Returns the horizontal chroma subsampling shift for the active pixel format.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @return the horizontal chroma subsampling shift for the active pixel format
    private static int chromaSubsamplingX(TileDecodeContext tileContext) {
        return tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
    }

    /// Returns the vertical chroma subsampling shift for the active pixel format.
    ///
    /// @param tileContext the synthetic tile-local decode context
    /// @return the vertical chroma subsampling shift for the active pixel format
    private static int chromaSubsamplingY(TileDecodeContext tileContext) {
        return tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
    }

    /// Decodes one expected minimal-block chroma residual unit using a mirrored `TX_4X4` oracle.
    ///
    /// @param syntaxReader the syntax reader positioned at the start of one chroma unit
    /// @param position the tile-relative origin of the owning block
    /// @param transformSize the modeled chroma transform size
    /// @return the decoded expected chroma residual unit
    private static TransformResidualUnit readExpectedChromaResidualUnit(
            TileSyntaxReader syntaxReader,
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels
    ) {
        if (syntaxReader.readCoefficientSkipFlag(transformSize, 0)) {
            return createAllZeroResidualUnit(position, transformSize, visibleWidthPixels, visibleHeightPixels);
        }

        int endOfBlockIndex = syntaxReader.readEndOfBlockIndex(transformSize, true, false);
        if (transformSize != TransformSize.TX_4X4) {
            return readExpectedGenericChromaResidualUnit(
                    syntaxReader,
                    position,
                    transformSize,
                    visibleWidthPixels,
                    visibleHeightPixels,
                    endOfBlockIndex
            );
        }
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        int[][] levelBytes = new int[FOUR_BY_FOUR_LEVEL_GRID_SIZE][FOUR_BY_FOUR_LEVEL_GRID_SIZE];

        if (endOfBlockIndex > 0) {
            int lastCoefficientIndex = expectedFourByFourOutputIndex(endOfBlockIndex);
            int lastX = lastCoefficientIndex & 3;
            int lastY = lastCoefficientIndex >> 2;
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    TransformSize.TX_4X4,
                    true,
                    endOfBlockTokenContext(endOfBlockIndex, TransformSize.TX_4X4)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        TransformSize.TX_4X4,
                        true,
                        fourByFourEndOfBlockHighTokenContext(lastX, lastY)
                );
            }
            coefficientTokens[endOfBlockIndex] = lastToken;
            levelBytes[lastX][lastY] = coefficientLevelByte(lastToken);

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int coefficientIndex = expectedFourByFourOutputIndex(scanIndex);
                int x = coefficientIndex & 3;
                int y = coefficientIndex >> 2;
                int token = syntaxReader.readBaseToken(
                        TransformSize.TX_4X4,
                        true,
                        fourByFourBaseTokenContext(levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            TransformSize.TX_4X4,
                            true,
                            fourByFourHighTokenContext(levelBytes, x, y)
                    );
                }
                coefficientTokens[scanIndex] = token;
                levelBytes[x][y] = coefficientLevelByte(token);
            }
        }

        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(TransformSize.TX_4X4, true, 0)
                : syntaxReader.readBaseToken(TransformSize.TX_4X4, true, 0);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(TransformSize.TX_4X4, true, fourByFourDcHighTokenContext(levelBytes));
        }

        int signedDcCoefficient = 0;
        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(true, 0);
            if (dcToken == 15) {
                dcToken += syntaxReader.readCoefficientGolomb();
            }
            signedDcCoefficient = negative ? -dcToken : dcToken;
            coefficients[0] = signedDcCoefficient;
        }

        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int token = coefficientTokens[scanIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token += syntaxReader.readCoefficientGolomb();
            }
            coefficients[expectedFourByFourOutputIndex(scanIndex)] = negative ? -token : token;
        }

        return new TransformResidualUnit(
                position,
                transformSize,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                expectedCoefficientContextByte(coefficients)
        );
    }

    /// Decodes one current larger-transform chroma residual unit using the mirrored generic token path.
    ///
    /// @param syntaxReader the syntax reader positioned at the start of one chroma unit
    /// @param position the tile-relative origin of the owning block
    /// @param transformSize the modeled chroma transform size
    /// @param visibleWidthPixels the exact visible chroma width in pixels
    /// @param visibleHeightPixels the exact visible chroma height in pixels
    /// @param endOfBlockIndex the already-decoded end-of-block scan index
    /// @return the decoded expected chroma residual unit
    private static TransformResidualUnit readExpectedGenericChromaResidualUnit(
            TileSyntaxReader syntaxReader,
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int endOfBlockIndex
    ) {
        TransformType transformType = TransformType.DCT_DCT;
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        int[] coefficientTokens = new int[Math.max(endOfBlockIndex + 1, 0)];
        int[][] levelBytes = createGenericLevelGrid(transformSize, transformType);
        if (endOfBlockIndex > 0) {
            int lastX = genericLevelX(transformSize, transformType, endOfBlockIndex);
            int lastY = genericLevelY(transformSize, transformType, endOfBlockIndex);
            int lastToken = syntaxReader.readEndOfBlockBaseToken(
                    transformSize,
                    true,
                    endOfBlockTokenContext(endOfBlockIndex, transformSize)
            );
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                        transformSize,
                        true,
                        genericEndOfBlockHighTokenContext(transformType, lastX, lastY)
                );
            }
            coefficientTokens[endOfBlockIndex] = lastToken;
            levelBytes[lastX][lastY] = coefficientLevelByte(lastToken);

            for (int scanIndex = endOfBlockIndex - 1; scanIndex > 0; scanIndex--) {
                int x = genericLevelX(transformSize, transformType, scanIndex);
                int y = genericLevelY(transformSize, transformType, scanIndex);
                int token = syntaxReader.readBaseToken(
                        transformSize,
                        true,
                        genericBaseTokenContext(transformSize, transformType, levelBytes, x, y)
                );
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                            transformSize,
                            true,
                            genericHighTokenContext(transformType, levelBytes, x, y)
                    );
                }
                coefficientTokens[scanIndex] = token;
                levelBytes[x][y] = coefficientLevelByte(token);
            }
        }

        int dcBaseContext = endOfBlockIndex > 0 && transformType.oneDimensional()
                ? genericBaseTokenContext(transformSize, transformType, levelBytes, 0, 0)
                : 0;
        int dcToken = endOfBlockIndex == 0
                ? syntaxReader.readEndOfBlockBaseToken(transformSize, true, 0)
                : syntaxReader.readBaseToken(transformSize, true, dcBaseContext);
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(transformSize, true, genericDcHighTokenContext(transformType, levelBytes));
        }

        if (dcToken != 0) {
            boolean negative = syntaxReader.readDcSignFlag(true, 0);
            if (dcToken == 15) {
                dcToken += syntaxReader.readCoefficientGolomb();
            }
            coefficients[0] = negative ? -dcToken : dcToken;
        }

        int[] scan = expectedScan(transformSize);
        for (int scanIndex = 1; scanIndex <= endOfBlockIndex; scanIndex++) {
            int token = coefficientTokens[scanIndex];
            if (token == 0) {
                continue;
            }
            boolean negative = syntaxReader.readCoefficientSignFlag();
            if (token == 15) {
                token += syntaxReader.readCoefficientGolomb();
            }
            coefficients[scan[scanIndex]] = negative ? -token : token;
        }

        return new TransformResidualUnit(
                position,
                transformSize,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                expectedCoefficientContextByte(coefficients)
        );
    }

    /// Returns the end-of-block base-token context for one supported end-of-block index.
    ///
    /// @param endOfBlockIndex the supported end-of-block index
    /// @param transformSize the active transform size
    /// @return the end-of-block base-token context for the supplied index
    private static int endOfBlockTokenContext(int endOfBlockIndex, TransformSize transformSize) {
        if (endOfBlockIndex < 0) {
            throw new IllegalArgumentException("endOfBlockIndex < 0: " + endOfBlockIndex);
        }
        int tx2dSizeContext = Math.min(transformSize.log2Width4(), 3) + Math.min(transformSize.log2Height4(), 3);
        return 1 + (endOfBlockIndex > (2 << tx2dSizeContext) ? 1 : 0)
                + (endOfBlockIndex > (4 << tx2dSizeContext) ? 1 : 0);
    }

    /// Returns the stored level byte written into the mirrored `levels` grid for one coefficient token.
    ///
    /// @param token the decoded coefficient token before the optional Golomb extension
    /// @return the stored level byte written into the local `levels` grid
    private static int coefficientLevelByte(int token) {
        if (token == 0) {
            return 0;
        }
        if (token < 3) {
            return token * 0x41;
        }
        return token + (3 << 6);
    }

    /// Returns the `br_tok` high-token context for the last non-zero `TX_4X4` coefficient.
    ///
    /// @param x the zero-based coefficient row in `[0, 4)`
    /// @param y the zero-based coefficient column in `[0, 4)`
    /// @return the `br_tok` high-token context for the supplied coefficient
    private static int fourByFourEndOfBlockHighTokenContext(int x, int y) {
        return ((x | y) > 1) ? 14 : 7;
    }

    /// Returns the base-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient row in `[0, 4)`
    /// @param y the zero-based coefficient column in `[0, 4)`
    /// @return the base-token context for the supplied coefficient
    private static int fourByFourBaseTokenContext(int[][] levelBytes, int x, int y) {
        int magnitude = fourByFourHighMagnitude(levelBytes, x, y) + levelBytes[x][y + 2] + levelBytes[x + 2][y];
        int offset = LEVEL_CONTEXT_OFFSETS[0][Math.min(y, 4)][Math.min(x, 4)];
        return offset + (magnitude > 512 ? 4 : (magnitude + 64) >> 7);
    }

    /// Returns the high-token context for one non-EOB `TX_4X4` coefficient.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient row in `[0, 4)`
    /// @param y the zero-based coefficient column in `[0, 4)`
    /// @return the high-token context for the supplied coefficient
    private static int fourByFourHighTokenContext(int[][] levelBytes, int x, int y) {
        int magnitude = fourByFourHighMagnitude(levelBytes, x, y) & 0x3F;
        return (((x | y) > 1) ? 14 : 7) + (magnitude > 12 ? 6 : (magnitude + 1) >> 1);
    }

    /// Returns the DC high-token context for one `TX_4X4` residual block.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @return the DC high-token context for the current residual block
    private static int fourByFourDcHighTokenContext(int[][] levelBytes) {
        int magnitude = fourByFourHighMagnitude(levelBytes, 0, 0) & 0x3F;
        return magnitude > 12 ? 6 : (magnitude + 1) >> 1;
    }

    /// Returns the `dav1d` high-magnitude accumulator for one `TX_4X4` coefficient position.
    ///
    /// @param levelBytes the padded local `levels` grid
    /// @param x the zero-based coefficient row in `[0, 4)`
    /// @param y the zero-based coefficient column in `[0, 4)`
    /// @return the `dav1d` high-magnitude accumulator for the supplied coefficient position
    private static int fourByFourHighMagnitude(int[][] levelBytes, int x, int y) {
        return levelBytes[x][y + 1] + levelBytes[x + 1][y] + levelBytes[x + 1][y + 1];
    }

    /// Creates one all-zero residual unit for the supplied position and transform size.
    ///
    /// @param position the tile-relative origin of the residual unit
    /// @param transformSize the transform size carried by the residual unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @return one all-zero residual unit for the supplied position and transform size
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
                0x40
        );
    }

    /// Returns a copy of the supplied transform layout with chroma disabled.
    ///
    /// @param transformLayout the original transform layout
    /// @return a copy of the supplied transform layout with chroma disabled
    private static TransformLayout lumaOnlyTransformLayout(TransformLayout transformLayout) {
        return new TransformLayout(
                transformLayout.position(),
                transformLayout.blockSize(),
                transformLayout.visibleWidth4(),
                transformLayout.visibleHeight4(),
                transformLayout.visibleWidthPixels(),
                transformLayout.visibleHeightPixels(),
                transformLayout.maxLumaTransformSize(),
                null,
                transformLayout.variableLumaTransformTree(),
                transformLayout.lumaUnits()
        );
    }

    /// Creates one skipped synthetic `I420` intra header with no palette, angle, or CFL state.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param size the coded block size
    /// @return one skipped synthetic `I420` intra header
    private static TileBlockHeaderReader.BlockHeader createSkippedSyntheticI420Header(
            BlockPosition position,
            BlockSize size
    ) {
        return new TileBlockHeaderReader.BlockHeader(
                position,
                size,
                true,
                true,
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

    /// Creates one synthetic `I420` transform layout with caller-supplied exact visible bounds and
    /// one uniform chroma transform size.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param blockSize the coded block size
    /// @param visibleWidth4 the visible block width in luma 4x4 units
    /// @param visibleHeight4 the visible block height in luma 4x4 units
    /// @param visibleWidthPixels the exact visible block width in pixels
    /// @param visibleHeightPixels the exact visible block height in pixels
    /// @param chromaTransformSize the synthetic chroma transform size
    /// @return one synthetic `I420` transform layout
    private static TransformLayout createSyntheticI420TransformLayout(
            BlockPosition position,
            BlockSize blockSize,
            int visibleWidth4,
            int visibleHeight4,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize chromaTransformSize
    ) {
        TransformSize lumaTransformSize = blockSize.maxLumaTransformSize();
        return new TransformLayout(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                lumaTransformSize,
                chromaTransformSize,
                false,
                new TransformUnit[]{new TransformUnit(position, lumaTransformSize)},
                createSyntheticI420ChromaUnits(position, visibleWidthPixels, visibleHeightPixels, chromaTransformSize)
        );
    }

    /// Creates synthetic `I420` chroma transform units for one visible luma footprint.
    ///
    /// @param position the block origin in luma 4x4 units
    /// @param visibleWidthPixels the exact visible luma width in pixels
    /// @param visibleHeightPixels the exact visible luma height in pixels
    /// @param chromaTransformSize the synthetic chroma transform size
    /// @return synthetic `I420` chroma transform units in bitstream order
    private static TransformUnit[] createSyntheticI420ChromaUnits(
            BlockPosition position,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize chromaTransformSize
    ) {
        int visibleChromaWidthPixels = (visibleWidthPixels + 1) >> 1;
        int visibleChromaHeightPixels = (visibleHeightPixels + 1) >> 1;
        int unitsWide = (visibleChromaWidthPixels + chromaTransformSize.widthPixels() - 1)
                / chromaTransformSize.widthPixels();
        int unitsHigh = (visibleChromaHeightPixels + chromaTransformSize.heightPixels() - 1)
                / chromaTransformSize.heightPixels();
        TransformUnit[] units = new TransformUnit[unitsWide * unitsHigh];
        int nextIndex = 0;
        for (int unitY = 0; unitY < unitsHigh; unitY++) {
            for (int unitX = 0; unitX < unitsWide; unitX++) {
                units[nextIndex++] = new TransformUnit(
                        position.offset(
                                (unitX * chromaTransformSize.width4()) << 1,
                                (unitY * chromaTransformSize.height4()) << 1
                        ),
                        chromaTransformSize
                );
            }
        }
        return units;
    }

    /// Asserts that two residual layouts are equal across luma and chroma transform units.
    ///
    /// @param expected the expected residual layout
    /// @param actual the actual residual layout
    private static void assertResidualLayoutEquals(ResidualLayout expected, ResidualLayout actual) {
        assertBlockPositionEquals(expected.position(), actual.position());
        assertEquals(expected.blockSize(), actual.blockSize());
        assertEquals(expected.hasChromaUnits(), actual.hasChromaUnits());

        TransformResidualUnit[] expectedLumaUnits = expected.lumaUnits();
        TransformResidualUnit[] actualLumaUnits = actual.lumaUnits();
        assertEquals(expectedLumaUnits.length, actualLumaUnits.length);
        for (int i = 0; i < expectedLumaUnits.length; i++) {
            assertResidualUnitEquals(expectedLumaUnits[i], actualLumaUnits[i]);
        }

        TransformResidualUnit[] expectedChromaUUnits = expected.chromaUUnits();
        TransformResidualUnit[] actualChromaUUnits = actual.chromaUUnits();
        assertEquals(expectedChromaUUnits.length, actualChromaUUnits.length);
        for (int i = 0; i < expectedChromaUUnits.length; i++) {
            assertResidualUnitEquals(expectedChromaUUnits[i], actualChromaUUnits[i]);
        }

        TransformResidualUnit[] expectedChromaVUnits = expected.chromaVUnits();
        TransformResidualUnit[] actualChromaVUnits = actual.chromaVUnits();
        assertEquals(expectedChromaVUnits.length, actualChromaVUnits.length);
        for (int i = 0; i < expectedChromaVUnits.length; i++) {
            assertResidualUnitEquals(expectedChromaVUnits[i], actualChromaVUnits[i]);
        }
    }

    /// Asserts that two transform residual units are equal.
    ///
    /// @param expected the expected transform residual unit
    /// @param actual the actual transform residual unit
    private static void assertResidualUnitEquals(TransformResidualUnit expected, TransformResidualUnit actual) {
        assertBlockPositionEquals(expected.position(), actual.position());
        assertEquals(expected.size(), actual.size());
        assertEquals(expected.allZero(), actual.allZero());
        assertEquals(expected.endOfBlockIndex(), actual.endOfBlockIndex());
        assertEquals(expected.visibleWidthPixels(), actual.visibleWidthPixels());
        assertEquals(expected.visibleHeightPixels(), actual.visibleHeightPixels());
        assertEquals(expected.coefficientContextByte(), actual.coefficientContextByte());
        assertArrayEquals(expected.coefficients(), actual.coefficients());
    }

    /// Asserts that two block positions describe the same 4x4-grid coordinates.
    ///
    /// @param expected the expected block position
    /// @param actual the actual block position
    private static void assertBlockPositionEquals(BlockPosition expected, BlockPosition actual) {
        assertEquals(expected.x4(), actual.x4());
        assertEquals(expected.y4(), actual.y4());
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(byte[] payload) {
        return createTileContext(payload, AvifPixelFormat.I400, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @param transformMode the synthetic frame transform mode
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(byte[] payload, FrameHeader.TransformMode transformMode) {
        return createTileContext(payload, AvifPixelFormat.I400, transformMode);
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param transformMode the synthetic frame transform mode
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(
            byte[] payload,
            AvifPixelFormat pixelFormat,
            FrameHeader.TransformMode transformMode
    ) {
        return createTileContext(payload, pixelFormat, transformMode, DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT);
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param transformMode the synthetic frame transform mode
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(
            byte[] payload,
            AvifPixelFormat pixelFormat,
            FrameHeader.TransformMode transformMode,
            int codedWidth,
            int codedHeight
    ) {
        boolean chromaSubsamplingX = pixelFormat == AvifPixelFormat.I420 || pixelFormat == AvifPixelFormat.I422;
        boolean chromaSubsamplingY = pixelFormat == AvifPixelFormat.I420;
        SequenceHeader sequenceHeader = new SequenceHeader(
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
                        pixelFormat == AvifPixelFormat.I400,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        chromaSubsamplingX,
                        chromaSubsamplingY,
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
                FrameType.KEY,
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
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
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
                transformMode,
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
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return TileDecodeContext.create(assembly, 0);
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
}
