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
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
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
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, FrameHeader.TransformMode.LARGEST);
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
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, FrameHeader.TransformMode.LARGEST);
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

    /// Verifies that minimal `I420` chroma residual decoding still works for odd visible frame dimensions.
    @Test
    void readsDcOnlyChromaResidualUnitsForMinimalI420BlockWithOddFrameDimensions() {
        byte[] payload = findPayloadForDcOnlyMinimalI420ChromaResidual();
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, FrameHeader.TransformMode.LARGEST, 7, 7);
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
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, FrameHeader.TransformMode.LARGEST, 7, 5);
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

    /// Verifies that one skipped synthetic `I420` block can expose multiple whole chroma residual
    /// units when the transform layout carries a smaller chroma transform size than the visible
    /// block-level chroma footprint.
    @Test
    void readsAllZeroMultiUnitChromaResidualsForSyntheticI420Leaf() {
        TileDecodeContext tileContext = createTileContext(new byte[0], PixelFormat.I420, FrameHeader.TransformMode.LARGEST, 16, 16);
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
        TileDecodeContext tileContext = createTileContext(new byte[0], PixelFormat.I420, FrameHeader.TransformMode.LARGEST, 14, 14);
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

    /// Finds a small payload whose first residual flags match the requested sequence.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @return a small payload whose residual flags match the requested prefix
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags) {
        byte[] twoBytePayload = findPayloadForResidualFlags(blockSize, expectedFlags, 2);
        if (twoBytePayload != null) {
            return twoBytePayload;
        }
        byte[] threeBytePayload = findPayloadForResidualFlags(blockSize, expectedFlags, 3);
        if (threeBytePayload != null) {
            return threeBytePayload;
        }
        throw new IllegalStateException("No deterministic payload produced the requested residual flags");
    }

    /// Finds a small payload whose first residual unit is supported and DC-only.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return a small payload whose first residual unit is supported and DC-only
    private static byte[] findPayloadForDcOnlyResidual(BlockSize blockSize) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    TileDecodeContext tileContext = createTileContext(payload);
                    TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
                    TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
                    TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
                    TileBlockHeaderReader.BlockHeader header =
                            blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
                    if (header.skip()) {
                        continue;
                    }
                    TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
                    TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
                    if (!residualUnit.allZero() && residualUnit.endOfBlockIndex() == 0) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported AC paths are skipped while brute-forcing a supported DC-only unit.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported DC-only residual");
    }

    /// Finds a small payload whose first residual unit exposes the first scanned AC coefficient.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return a small payload whose first residual unit exposes the first scanned AC coefficient
    private static byte[] findPayloadForSingleAcResidual(BlockSize blockSize) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    TileDecodeContext tileContext = createTileContext(payload);
                    TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
                    TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
                    TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
                    TileBlockHeaderReader.BlockHeader header =
                            blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
                    if (header.skip()) {
                        continue;
                    }
                    TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
                    TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
                    if (residualUnit.endOfBlockIndex() == 1) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported residual trees are skipped while brute-forcing a supported first-AC unit.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported first-AC residual");
    }

    /// Finds a small payload whose first residual unit exposes a supported multi-coefficient `TX_4X4` residual.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @return a small payload whose first residual unit exposes a supported multi-coefficient residual
    private static byte[] findPayloadForMultiCoefficientResidual(BlockSize blockSize) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    TileDecodeContext tileContext = createTileContext(payload);
                    TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
                    TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
                    TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
                    TileBlockHeaderReader.BlockHeader header =
                            blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
                    if (header.skip()) {
                        continue;
                    }
                    TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
                    TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
                    if (residualUnit.endOfBlockIndex() > 1 && countNonZeroCoefficients(residualUnit.coefficients()) >= 2) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported residual trees are skipped while brute-forcing a supported multi-coefficient unit.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported multi-coefficient residual");
    }

    /// Finds a payload whose first residual unit exposes a supported multi-coefficient larger transform.
    ///
    /// @param blockSize the coded block size whose largest-transform residual should be decoded
    /// @return a payload whose first residual unit exposes a supported multi-coefficient larger transform
    private static byte[] findPayloadForLargestTransformMultiCoefficientResidual(BlockSize blockSize) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }

                TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.LARGEST);
                TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
                TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
                TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
                BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
                TileBlockHeaderReader.BlockHeader header =
                        blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
                if (header.skip()) {
                    continue;
                }
                TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
                if (transformLayout.lumaUnits().length != 1 || transformLayout.lumaUnits()[0].size() == TransformSize.TX_4X4) {
                    continue;
                }
                TransformResidualUnit residualUnit = residualSyntaxReader.read(header, transformLayout, neighborContext).lumaUnits()[0];
                if (residualUnit.endOfBlockIndex() > 1 && countNonZeroCoefficients(residualUnit.coefficients()) >= 2) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported larger-transform residual");
    }

    /// Finds a payload whose minimal `I420` chroma residuals are both all-zero.
    ///
    /// @return a payload whose minimal `I420` chroma residuals are both all-zero
    private static byte[] findPayloadForAllZeroMinimalI420ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalI420ChromaResidual(true, true);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalI420ChromaResidual(false, true);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No deterministic payload produced minimal all-zero I420 chroma residuals");
    }

    /// Finds a payload whose minimal `I420` chroma residuals are both DC-only and non-zero.
    ///
    /// @return a payload whose minimal `I420` chroma residuals are both DC-only and non-zero
    private static byte[] findPayloadForDcOnlyMinimalI420ChromaResidual() {
        byte[] isolatedPayload = findPayloadForMinimalI420ChromaResidual(true, false);
        if (isolatedPayload != null) {
            return isolatedPayload;
        }
        byte[] fallbackPayload = findPayloadForMinimalI420ChromaResidual(false, false);
        if (fallbackPayload != null) {
            return fallbackPayload;
        }
        throw new IllegalStateException("No deterministic payload produced minimal non-zero I420 chroma residuals");
    }

    /// Finds a payload whose minimal `I420` chroma residuals match the requested mode, or `null`.
    ///
    /// @param requireAllZeroLuma whether the leading luma unit should stay all-zero to isolate chroma
    /// @param requireAllZeroChroma whether both chroma units must be all-zero instead of DC-only
    /// @return a payload whose minimal `I420` chroma residuals match the requested mode, or `null`
    private static byte[] findPayloadForMinimalI420ChromaResidual(boolean requireAllZeroLuma, boolean requireAllZeroChroma) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    ResidualLayout expectedResidualLayout = decodeExpectedMinimalI420ResidualLayout(payload);
                    TransformResidualUnit lumaResidualUnit = expectedResidualLayout.lumaUnits()[0];
                    TransformResidualUnit chromaUResidualUnit = expectedResidualLayout.chromaUUnits()[0];
                    TransformResidualUnit chromaVResidualUnit = expectedResidualLayout.chromaVUnits()[0];
                    if (requireAllZeroLuma && !lumaResidualUnit.allZero()) {
                        continue;
                    }
                    if (requireAllZeroChroma) {
                        if (chromaUResidualUnit.allZero() && chromaVResidualUnit.allZero()) {
                            return payload;
                        }
                        continue;
                    }
                    if (!chromaUResidualUnit.allZero()
                            && !chromaVResidualUnit.allZero()
                            && chromaUResidualUnit.endOfBlockIndex() == 0
                            && chromaVResidualUnit.endOfBlockIndex() == 0) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported larger luma paths or non-minimal chroma paths are skipped while brute-forcing.
                }
            }
        }
        return null;
    }

    /// Finds a payload whose first residual flags match the requested sequence, or `null`.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @param searchBytes the number of leading payload bytes to brute force
    /// @return a payload whose first residual flags match the requested sequence, or `null`
    private static byte[] findPayloadForResidualFlags(BlockSize blockSize, boolean[] expectedFlags, int searchBytes) {
        int limit = 1 << (searchBytes << 3);
        for (int value = 0; value < limit; value++) {
            byte[] payload = new byte[8];
            for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
            }

            TileDecodeContext tileContext = createTileContext(payload);
            TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
            TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
            TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
            BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
            TileBlockHeaderReader.BlockHeader header =
                    blockHeaderReader.read(new BlockPosition(0, 0), blockSize, neighborContext, false);
            if (header.skip()) {
                continue;
            }

            TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
            ResidualLayout residualLayout;
            try {
                residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
            } catch (IllegalStateException ignored) {
                continue;
            }
            TransformResidualUnit[] residualUnits = residualLayout.lumaUnits();
            if (residualUnits.length < expectedFlags.length) {
                continue;
            }

            boolean matched = true;
            for (int i = 0; i < expectedFlags.length; i++) {
                if (residualUnits[i].allZero() != expectedFlags[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return payload;
            }
        }
        return null;
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

    /// Builds the diagonal default scan used by the larger-transform residual test oracle.
    ///
    /// @param transformSize the active transform size
    /// @return the diagonal default scan for the supplied transform size
    private static int[] expectedScan(TransformSize transformSize) {
        int width = transformSize.widthPixels();
        int height = transformSize.heightPixels();
        int[] scan = new int[width * height];
        boolean wide = width > height;
        int nextIndex = 0;
        for (int diagonal = 0; diagonal < width + height - 1; diagonal++) {
            int rowStart = Math.max(0, diagonal - (width - 1));
            int rowEnd = Math.min(height - 1, diagonal);
            boolean descendingRows = (((diagonal & 1) == 1) ^ wide);
            if (descendingRows) {
                for (int row = rowEnd; row >= rowStart; row--) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * width + column;
                }
            } else {
                for (int row = rowStart; row <= rowEnd; row++) {
                    int column = diagonal - row;
                    scan[nextIndex++] = row * width + column;
                }
            }
        }
        return scan;
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
        return decodeExpectedMinimalI420ResidualLayout(payload, DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT);
    }

    /// Decodes the expected minimal `I420` residual layout using a luma-only advance plus a chroma oracle.
    ///
    /// @param payload the collected tile entropy payload
    /// @param codedWidth the synthetic coded frame width in pixels
    /// @param codedHeight the synthetic coded frame height in pixels
    /// @return the expected minimal `I420` residual layout
    private static ResidualLayout decodeExpectedMinimalI420ResidualLayout(byte[] payload, int codedWidth, int codedHeight) {
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, FrameHeader.TransformMode.LARGEST, codedWidth, codedHeight);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, BlockSize.SIZE_8X8, neighborContext, false);
        if (!header.hasChroma()) {
            throw new IllegalStateException("Synthetic minimal I420 block did not expose chroma");
        }
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        TransformSize chromaTransformSize = Objects.requireNonNull(transformLayout.chromaTransformSize(), "chromaTransformSize");
        int visibleChromaWidthPixels = exactVisibleChromaWidthPixels(tileContext, position, BlockSize.SIZE_8X8);
        int visibleChromaHeightPixels = exactVisibleChromaHeightPixels(tileContext, position, BlockSize.SIZE_8X8);
        ResidualLayout lumaResidualLayout = residualSyntaxReader.read(header, lumaOnlyTransformLayout(transformLayout), neighborContext);
        if (header.skip()) {
            return new ResidualLayout(
                    position,
                    BlockSize.SIZE_8X8,
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
                BlockSize.SIZE_8X8,
                lumaResidualLayout.lumaUnits(),
                new TransformResidualUnit[]{readMinimalChromaResidualUnit(
                        syntaxReader,
                        position,
                        chromaTransformSize,
                        visibleChromaWidthPixels,
                        visibleChromaHeightPixels
                )},
                new TransformResidualUnit[]{readMinimalChromaResidualUnit(
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

    /// Decodes one minimal chroma residual unit that is either all-zero or DC-only.
    ///
    /// @param syntaxReader the syntax reader positioned at the start of one chroma unit
    /// @param position the tile-relative origin of the owning block
    /// @param transformSize the modeled chroma transform size
    /// @return the decoded minimal chroma residual unit
    private static TransformResidualUnit readMinimalChromaResidualUnit(
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
        if (endOfBlockIndex != 0) {
            throw new IllegalStateException("Minimal chroma oracle only supports DC-only residual units");
        }

        int dcToken = syntaxReader.readEndOfBlockBaseToken(transformSize, true, 0);
        if (dcToken == 3) {
            dcToken = syntaxReader.readDcHighToken(transformSize, true);
        }
        if (dcToken == 15) {
            dcToken += syntaxReader.readCoefficientGolomb();
        }

        boolean negative = syntaxReader.readDcSignFlag(true, 0);
        int signedDcCoefficient = negative ? -dcToken : dcToken;
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[0] = signedDcCoefficient;
        return new TransformResidualUnit(
                position,
                transformSize,
                0,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                expectedNonZeroCoefficientContextByte(signedDcCoefficient)
        );
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
                new TransformUnit[]{new TransformUnit(position, lumaTransformSize)}
        );
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
        return createTileContext(payload, PixelFormat.I400, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @param transformMode the synthetic frame transform mode
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(byte[] payload, FrameHeader.TransformMode transformMode) {
        return createTileContext(payload, PixelFormat.I400, transformMode);
    }

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic sequence pixel format
    /// @param transformMode the synthetic frame transform mode
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(
            byte[] payload,
            PixelFormat pixelFormat,
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
            PixelFormat pixelFormat,
            FrameHeader.TransformMode transformMode,
            int codedWidth,
            int codedHeight
    ) {
        boolean chromaSubsamplingX = pixelFormat == PixelFormat.I420 || pixelFormat == PixelFormat.I422;
        boolean chromaSubsamplingY = pixelFormat == PixelFormat.I420;
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
                        pixelFormat == PixelFormat.I400,
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
