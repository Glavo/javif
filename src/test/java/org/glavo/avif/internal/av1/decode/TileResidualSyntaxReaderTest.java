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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileResidualSyntaxReader`.
@NotNullByDefault
final class TileResidualSyntaxReaderTest {
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

    /// Creates one synthetic tile-local decode context used by residual-syntax tests.
    ///
    /// @param payload the collected tile entropy payload
    /// @return one synthetic tile-local decode context used by residual-syntax tests
    private static TileDecodeContext createTileContext(byte[] payload) {
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
                        false,
                        false,
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
