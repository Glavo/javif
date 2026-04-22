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
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileBlockHeaderReader`.
@NotNullByDefault
final class TileBlockHeaderReaderTest {
    /// Verifies that a key-frame block header consumes skip and key-frame Y/UV modes with neighbor-aware contexts.
    @Test
    void readsKeyFrameBlockHeader() {
        byte[] payload = new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(0, 0);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        int expectedYAngle = expectedYMode.isDirectional() && BlockSize.SIZE_8X8.width4() * BlockSize.SIZE_8X8.height4() >= 4
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedYMode.angleDeltaContextIndex()), 6) - 3
                : 0;
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );
        int expectedUvAngle = 0;
        int expectedCflAlphaU = 0;
        int expectedCflAlphaV = 0;
        if (expectedUvMode == UvIntraPredictionMode.CFL) {
            int signSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCflSignCdf(), 7) + 1;
            int signU = signSymbol / 3;
            int signV = signSymbol - signU * 3;
            expectedCflAlphaU = signU == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signU == 2 ? 3 : 0) + signV, signU == 2);
            expectedCflAlphaV = signV == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signV == 2 ? 3 : 0) + signU, signV == 2);
        } else if (expectedUvMode.isDirectional() && BlockSize.SIZE_8X8.width4() * BlockSize.SIZE_8X8.height4() >= 4) {
            expectedUvAngle = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedUvMode.angleDeltaContextIndex()), 6) - 3;
        }

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_8X8, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertNull(header.filterIntraMode());
        assertEquals(expectedYAngle, header.yAngle());
        assertEquals(expectedUvAngle, header.uvAngle());
        assertEquals(expectedCflAlphaU, header.cflAlphaU());
        assertEquals(expectedCflAlphaV, header.cflAlphaV());
        assertTrue(header.hasChroma());
        assertEquals(3, neighborContext.intraContext(new BlockPosition(2, 2)));
    }

    /// Verifies that an inter block header follows the oracle-decoded intra decision and mode presence.
    @Test
    void readsInterBlockHeader() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedIntra = !expectedSkip && oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        LumaIntraPredictionMode expectedYMode = expectedIntra
                ? LumaIntraPredictionMode.fromSymbolIndex(oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableYModeCdf(BlockSize.SIZE_16X16.yModeSizeContext()), 12))
                : null;
        UvIntraPredictionMode expectedUvMode = expectedIntra && expectedYMode != null
                ? UvIntraPredictionMode.fromSymbolIndex(oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13))
                : null;

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertEquals(expectedIntra, header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertEquals(0, header.yAngle());
        assertEquals(0, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
        if (!expectedIntra) {
            assertNull(header.yMode());
            assertNull(header.uvMode());
        }
    }

    /// Verifies that a superblock-origin block decodes CDEF and delta-q/delta-lf side syntax.
    @Test
    void readsCdefIndexAndDeltaStateAtSuperblockStart() {
        byte[] payload = findPayloadForCdefAndDeltaSyntax();
        FrameHeader.DeltaInfo deltaInfo = new FrameHeader.DeltaInfo(true, 0, true, 0, false);
        FrameHeader.CdefInfo cdefInfo = new FrameHeader.CdefInfo(3, 2, new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0});
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                false,
                false,
                false,
                100,
                deltaInfo,
                cdefInfo
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        int expectedCdefIndex = oracleDecoder.decodeBools(2);
        int expectedDeltaQ = decodeSignedDeltaValue(oracleDecoder, oracleCdf.mutableDeltaQCdf(), 0);
        int expectedDeltaLf = decodeSignedDeltaValue(oracleDecoder, oracleCdf.mutableDeltaLfCdf(0), 0);

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertFalse(expectedSkip);
        assertFalse(header.skip());
        assertEquals(expectedCdefIndex, header.cdefIndex());
        assertEquals(clip(100 + expectedDeltaQ, 1, 255), header.qIndex());
        assertArrayEquals(new int[]{clip(expectedDeltaLf, -63, 63), 0, 0, 0}, header.deltaLfValues());
    }

    /// Verifies that CDEF and delta-q/delta-lf state persist across later blocks in the same superblock.
    @Test
    void carriesCdefAndDeltaStateAcrossBlocksWithinSuperblock() {
        byte[] payload = findPayloadForCdefAndDeltaSyntax();
        FrameHeader.DeltaInfo deltaInfo = new FrameHeader.DeltaInfo(true, 0, true, 0, false);
        FrameHeader.CdefInfo cdefInfo = new FrameHeader.CdefInfo(3, 2, new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0});
        TileDecodeContext tileContext = createTileContext(
                FrameType.KEY,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                false,
                false,
                false,
                100,
                deltaInfo,
                cdefInfo
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader first = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);
        TileBlockHeaderReader.BlockHeader second = reader.read(new BlockPosition(4, 0), BlockSize.SIZE_16X16, neighborContext);

        assertTrue(first.cdefIndex() > 0);
        assertTrue(first.qIndex() != 100);
        assertTrue(first.deltaLfValues()[0] != 0);
        assertEquals(first.cdefIndex(), second.cdefIndex());
        assertEquals(first.qIndex(), second.qIndex());
        assertArrayEquals(first.deltaLfValues(), second.deltaLfValues());
    }

    /// Verifies that skipped inter blocks do not consume an intra/inter decision or intra syntax.
    @Test
    void readsSkippedInterBlockWithoutIntraSyntax() {
        byte[] payload = findPayloadForSkippedInterBlock();
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean intraIfConsumed = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        assertTrue(expectedSkip);
        assertTrue(intraIfConsumed);

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertTrue(header.skip());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertNull(header.yMode());
        assertNull(header.uvMode());
        assertEquals(0, header.yAngle());
        assertEquals(0, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that skip mode short-circuits both skip-flag and intra/inter syntax in inter blocks.
    @Test
    void readsSkipModeBlockHeaderWithoutSkipSyntax() {
        byte[] payload = findPayloadForSkipModeInterBlock();
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                true,
                true
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkipMode = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipModeCdf(0));
        boolean skipIfConsumed = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean intraIfConsumed = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        assertTrue(expectedSkipMode);
        assertFalse(skipIfConsumed);
        assertTrue(intraIfConsumed);

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertTrue(header.skipMode());
        assertTrue(header.skip());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertTrue(header.compoundReference());
        assertEquals(0, header.referenceFrame0());
        assertEquals(1, header.referenceFrame1());
        assertNull(header.singleInterMode());
        assertEquals(CompoundInterPredictionMode.NEARESTMV_NEARESTMV, header.compoundInterMode());
        assertEquals(0, header.drlIndex());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), header.motionVector0());
        assertEquals(InterMotionVector.predicted(MotionVector.zero()), header.motionVector1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
        assertEquals(0, header.yAngle());
        assertEquals(0, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that switchable compound-reference syntax decodes a compound reference pair.
    @Test
    void readsCompoundReferenceBlockHeader() {
        byte[] payload = findPayloadForCompoundReferenceBlock();
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        assertEquals(2, neighborContext.compoundReferenceContext(position));
        assertEquals(1, neighborContext.compoundDirectionContext(position));

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedIntra = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        boolean expectedCompound = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2));
        assertFalse(expectedSkip);
        assertFalse(expectedIntra);
        assertTrue(expectedCompound);
        InterReferenceExpectation expectedReferences =
                decodeCompoundReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.skipMode());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertTrue(header.compoundReference());
        assertEquals(expectedReferences.referenceFrame0(), header.referenceFrame0());
        assertEquals(expectedReferences.referenceFrame1(), header.referenceFrame1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
    }

    /// Verifies that single-reference syntax decodes the primary inter reference when compound mode is disabled.
    @Test
    void readsSingleReferenceBlockHeader() {
        byte[] payload = findPayloadForSingleReferenceBlock();
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedIntra = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        boolean expectedCompound = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2));
        assertFalse(expectedSkip);
        assertFalse(expectedIntra);
        assertFalse(expectedCompound);
        int expectedReferenceFrame0 = decodeSingleReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.skipMode());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.compoundReference());
        assertEquals(expectedReferenceFrame0, header.referenceFrame0());
        assertEquals(-1, header.referenceFrame1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
    }

    /// Verifies that `segment_reference_frame = INTRA_FRAME` forces inter blocks down the intra syntax path.
    @Test
    void readsInterBlockForcedIntraBySegmentReference() {
        byte[] payload = findPayloadForSegmentReferenceForcedIntra();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[0] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, 0, false, false);
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                createFixedSegmentationInfo(segments),
                false,
                false
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleProbe = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleProbe.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean intraIfConsumed = oracleProbe.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        assertFalse(expectedSkip);
        assertFalse(intraIfConsumed);

        CdfContext forcedCdf = CdfContext.createDefault();
        MsacDecoder forcedDecoder = new MsacDecoder(payload, 0, payload.length, false);
        forcedDecoder.decodeBooleanAdapt(forcedCdf.mutableSkipCdf(0));
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                forcedDecoder.decodeSymbolAdapt(forcedCdf.mutableYModeCdf(BlockSize.SIZE_16X16.yModeSizeContext()), 12)
        );
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                forcedDecoder.decodeSymbolAdapt(forcedCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(0, header.segmentId());
        assertFalse(header.compoundReference());
        assertEquals(-1, header.referenceFrame0());
        assertEquals(-1, header.referenceFrame1());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
    }

    /// Verifies that inter segment reference-frame forcing suppresses the intra/inter syntax bit.
    @Test
    void readsInterBlockForcedInterBySegmentReference() {
        byte[] payload = findPayloadForSegmentReferenceForcedInter();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[0] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, 1, false, false);
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                createFixedSegmentationInfo(segments),
                false,
                false
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean intraIfConsumed = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        assertFalse(expectedSkip);
        assertTrue(intraIfConsumed);
        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                neighborContext.provisionalInterModeContext(new BlockPosition(0, 0), BlockSize.SIZE_16X16, false, 0, -1);
        InterModeExpectation expectedInterMode =
                decodeSingleInterModeExpectation(oracleDecoder, oracleCdf, provisionalContext);

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(0, header.segmentId());
        assertFalse(header.compoundReference());
        assertEquals(0, header.referenceFrame0());
        assertEquals(-1, header.referenceFrame1());
        assertEquals(expectedInterMode.singleInterMode(), header.singleInterMode());
        assertNull(header.compoundInterMode());
        assertEquals(expectedInterMode.drlIndex(), header.drlIndex());
        assertNull(header.yMode());
        assertNull(header.uvMode());
        assertEquals(0, header.yAngle());
        assertEquals(0, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that segment-level global motion exposes a fixed `GLOBALMV` single-reference mode.
    @Test
    void readsGlobalMotionSegmentBlockHeader() {
        byte[] payload = findPayloadForInterBlockWithoutSkipOrIntra();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[0] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, true);
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                createFixedSegmentationInfo(segments),
                false,
                false
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.skipMode());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.compoundReference());
        assertEquals(0, header.referenceFrame0());
        assertEquals(-1, header.referenceFrame1());
        assertEquals(SingleInterPredictionMode.GLOBALMV, header.singleInterMode());
        assertNull(header.compoundInterMode());
        assertEquals(0, header.drlIndex());
        assertEquals(InterMotionVector.resolved(MotionVector.zero()), header.motionVector0());
        assertNull(header.motionVector1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
    }

    /// Verifies that a `NEWMV` single-reference inter block decodes `inter_mode + drl + mv_residual`.
    @Test
    void readsSingleInterModeBlockHeader() {
        byte[] payload = findPayloadForSingleInterModeBlock();
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2));
        int expectedReferenceFrame0 = decodeSingleReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                neighborContext.provisionalInterModeContext(position, BlockSize.SIZE_16X16, false, expectedReferenceFrame0, -1);
        InterModeExpectation expectedInterMode =
                decodeSingleInterModeExpectation(oracleDecoder, oracleCdf, provisionalContext);
        assertEquals(SingleInterPredictionMode.NEWMV, expectedInterMode.singleInterMode());
        InterMotionVector expectedMotionVector = expectedSingleMotionVector(
                oracleDecoder,
                oracleCdf,
                tileContext.frameHeader(),
                provisionalContext,
                expectedInterMode
        );

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.skipMode());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.compoundReference());
        assertEquals(expectedReferenceFrame0, header.referenceFrame0());
        assertEquals(-1, header.referenceFrame1());
        assertEquals(expectedInterMode.singleInterMode(), header.singleInterMode());
        assertNull(header.compoundInterMode());
        assertEquals(expectedInterMode.drlIndex(), header.drlIndex());
        assertEquals(expectedMotionVector, header.motionVector0());
        assertNull(header.motionVector1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
    }

    /// Verifies that a `NEWMV_NEWMV` compound inter block decodes `comp_inter_mode + drl + mv_residual`.
    @Test
    void readsCompoundInterModeBlockHeader() {
        byte[] payload = findPayloadForCompoundInterModeBlock();
        TileDecodeContext tileContext = createTileContext(
                FrameType.INTER,
                false,
                payload,
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        );
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
        oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2));
        InterReferenceExpectation expectedReferences =
                decodeCompoundReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
        BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                neighborContext.provisionalInterModeContext(
                        position,
                        BlockSize.SIZE_16X16,
                        true,
                        expectedReferences.referenceFrame0(),
                        expectedReferences.referenceFrame1()
                );
        InterModeExpectation expectedInterMode =
                decodeCompoundInterModeExpectation(oracleDecoder, oracleCdf, provisionalContext);
        assertEquals(CompoundInterPredictionMode.NEWMV_NEWMV, expectedInterMode.compoundInterMode());
        InterMotionVector expectedMotionVector0 = expectedCompoundMotionVector0(
                oracleDecoder,
                oracleCdf,
                tileContext.frameHeader(),
                provisionalContext,
                expectedInterMode
        );
        InterMotionVector expectedMotionVector1 = expectedCompoundMotionVector1(
                oracleDecoder,
                oracleCdf,
                tileContext.frameHeader(),
                provisionalContext,
                expectedInterMode
        );

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertFalse(header.skipMode());
        assertFalse(header.intra());
        assertFalse(header.useIntrabc());
        assertTrue(header.compoundReference());
        assertEquals(expectedReferences.referenceFrame0(), header.referenceFrame0());
        assertEquals(expectedReferences.referenceFrame1(), header.referenceFrame1());
        assertNull(header.singleInterMode());
        assertEquals(expectedInterMode.compoundInterMode(), header.compoundInterMode());
        assertEquals(expectedInterMode.drlIndex(), header.drlIndex());
        assertEquals(expectedMotionVector0, header.motionVector0());
        assertEquals(expectedMotionVector1, header.motionVector1());
        assertNull(header.yMode());
        assertNull(header.uvMode());
    }

    /// Verifies that `intrabc` blocks keep their implicit DC/DC prediction modes.
    @Test
    void readsIntrabcBlockHeaderWithImplicitDcModes() {
        byte[] payload = findPayloadForIntrabc();
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, true, payload);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedUseIntrabc = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntrabcCdf());

        assertTrue(expectedUseIntrabc);
        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertFalse(header.intra());
        assertTrue(header.useIntrabc());
        assertEquals(LumaIntraPredictionMode.DC, header.yMode());
        assertEquals(UvIntraPredictionMode.DC, header.uvMode());
        assertNull(header.filterIntraMode());
        assertEquals(0, header.yAngle());
        assertEquals(0, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that large lossless blocks decode UV modes with CFL disabled.
    @Test
    void readsLargeLosslessBlockWithoutCflMode() {
        byte[] payload = new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        int expectedYAngle = expectedYMode.isDirectional()
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedYMode.angleDeltaContextIndex()), 6) - 3
                : 0;
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(false, expectedYMode.symbolIndex()), 12)
        );
        int expectedUvAngle = expectedUvMode.isDirectional()
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedUvMode.angleDeltaContextIndex()), 6) - 3
                : 0;

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_64X64, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertNull(header.filterIntraMode());
        assertTrue(header.hasChroma());
        assertFalse(header.uvMode() == UvIntraPredictionMode.CFL);
        assertEquals(expectedYAngle, header.yAngle());
        assertEquals(expectedUvAngle, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that filter intra is decoded after DC luma and UV syntax.
    @Test
    void readsFilterIntraBlockHeader() {
        byte[] payload = findPayloadForFilterIntraBlock();
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, true);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        assertEquals(LumaIntraPredictionMode.DC, expectedYMode);
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );
        int expectedUvAngle = 0;
        int expectedCflAlphaU = 0;
        int expectedCflAlphaV = 0;
        if (expectedUvMode == UvIntraPredictionMode.CFL) {
            int signSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCflSignCdf(), 7) + 1;
            int signU = signSymbol / 3;
            int signV = signSymbol - signU * 3;
            expectedCflAlphaU = signU == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signU == 2 ? 3 : 0) + signV, signU == 2);
            expectedCflAlphaV = signV == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signV == 2 ? 3 : 0) + signU, signV == 2);
        } else if (expectedUvMode.isDirectional()) {
            expectedUvAngle = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedUvMode.angleDeltaContextIndex()), 6) - 3;
        }
        boolean expectedUseFilterIntra = oracleDecoder.decodeBooleanAdapt(
                oracleCdf.mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex())
        );
        assertTrue(expectedUseFilterIntra);
        FilterIntraMode expectedFilterIntraMode = FilterIntraMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableFilterIntraCdf(), 4)
        );

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(LumaIntraPredictionMode.DC, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertEquals(expectedFilterIntraMode, header.filterIntraMode());
        assertEquals(0, header.yAngle());
        assertEquals(expectedUvAngle, header.uvAngle());
        assertEquals(expectedCflAlphaU, header.cflAlphaU());
        assertEquals(expectedCflAlphaV, header.cflAlphaV());
    }

    /// Verifies that preskip segmentation decodes `seg_id` before the skip short-circuit takes effect.
    @Test
    void readsPreskipSegmentIdBeforeSkipSyntax() {
        byte[] payload = findPayloadForPreskipSegmentOne();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[1] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, true, false);
        FrameHeader.SegmentationInfo segmentation = createSegmentationInfo(true, 1, segments, new boolean[8], new int[8]);
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, segmentation, false, false);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        int expectedSegmentDiff = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableSegmentIdCdf(0), 7);
        assertEquals(1, expectedSegmentDiff);
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        int expectedYAngle = expectedYMode.isDirectional()
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedYMode.angleDeltaContextIndex()), 6) - 3
                : 0;
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );
        int expectedUvAngle = 0;
        int expectedCflAlphaU = 0;
        int expectedCflAlphaV = 0;
        if (expectedUvMode == UvIntraPredictionMode.CFL) {
            int signSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCflSignCdf(), 7) + 1;
            int signU = signSymbol / 3;
            int signV = signSymbol - signU * 3;
            expectedCflAlphaU = signU == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signU == 2 ? 3 : 0) + signV, signU == 2);
            expectedCflAlphaV = signV == 0
                    ? 0
                    : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signV == 2 ? 3 : 0) + signU, signV == 2);
        } else if (expectedUvMode.isDirectional()) {
            expectedUvAngle = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedUvMode.angleDeltaContextIndex()), 6) - 3;
        }

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext);

        assertTrue(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.segmentPredicted());
        assertEquals(1, header.segmentId());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertEquals(expectedYAngle, header.yAngle());
        assertEquals(expectedUvAngle, header.uvAngle());
        assertEquals(expectedCflAlphaU, header.cflAlphaU());
        assertEquals(expectedCflAlphaV, header.cflAlphaV());
    }

    /// Verifies that preskip temporal segmentation prediction can reuse the neighbor-predicted segment id.
    @Test
    void readsTemporallyPredictedPreskipSegmentId() {
        byte[] payload = findPayloadForTemporalPreskipPrediction();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[1] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, true, false);
        FrameHeader.SegmentationInfo segmentation = createSegmentationInfo(true, true, 1, segments, new boolean[8], new int[8]);
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, segmentation, false, false);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedTemporalSegmentPrediction(neighborContext, 1);

        assertEquals(2, neighborContext.segmentPredictionContext(position));
        assertEquals(1, neighborContext.currentSegmentPrediction(position).predictedSegmentId());

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSegmentPredicted = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSegmentPredictionCdf(2));
        assertTrue(expectedSegmentPredicted);

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_8X8, neighborContext);

        assertTrue(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertTrue(header.segmentPredicted());
        assertEquals(1, header.segmentId());
    }

    /// Verifies that postskip segmentation uses the per-segment lossless state to disable CFL.
    @Test
    void readsPostskipSegmentIdBeforeUvCflGating() {
        byte[] payload = findPayloadForPostskipLosslessSegment();
        boolean[] losslessBySegment = new boolean[8];
        losslessBySegment[1] = true;
        int[] qIndexBySegment = new int[8];
        qIndexBySegment[0] = 1;
        FrameHeader.SegmentationInfo segmentation = createSegmentationInfo(false, 1, defaultSegments(), losslessBySegment, qIndexBySegment);
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, segmentation, false, false);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        assertFalse(expectedSkip);
        int expectedSegmentDiff = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableSegmentIdCdf(0), 7);
        assertEquals(1, expectedSegmentDiff);
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        int expectedYAngle = expectedYMode.isDirectional()
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedYMode.angleDeltaContextIndex()), 6) - 3
                : 0;
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(false, expectedYMode.symbolIndex()), 12)
        );
        int expectedUvAngle = expectedUvMode.isDirectional()
                ? oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(expectedUvMode.angleDeltaContextIndex()), 6) - 3
                : 0;

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_16X16, neighborContext);

        assertFalse(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.segmentPredicted());
        assertEquals(1, header.segmentId());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
        assertFalse(header.uvMode() == UvIntraPredictionMode.CFL);
        assertEquals(expectedYAngle, header.yAngle());
        assertEquals(expectedUvAngle, header.uvAngle());
        assertEquals(0, header.cflAlphaU());
        assertEquals(0, header.cflAlphaV());
    }

    /// Verifies that postskip temporal segmentation prediction can reuse the neighbor-predicted segment id.
    @Test
    void readsTemporallyPredictedPostskipSegmentId() {
        byte[] payload = findPayloadForTemporalPostskipPrediction();
        FrameHeader.SegmentationInfo segmentation = createSegmentationInfo(false, true, 1, defaultSegments(), new boolean[8], new int[8]);
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, segmentation, false, false);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedTemporalSegmentPrediction(neighborContext, 1);

        assertEquals(2, neighborContext.segmentPredictionContext(position));
        assertEquals(1, neighborContext.currentSegmentPrediction(position).predictedSegmentId());

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedSegmentPredicted = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSegmentPredictionCdf(2));
        assertFalse(expectedSkip);
        assertTrue(expectedSegmentPredicted);

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_8X8, neighborContext);

        assertFalse(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertTrue(header.segmentPredicted());
        assertEquals(1, header.segmentId());
    }

    /// Verifies that skipped postskip segmentation reuses the predicted segment id without consuming
    /// a temporal prediction flag.
    @Test
    void readsSkippedPostskipTemporalSegmentIdWithoutPredictionFlag() {
        byte[] payload = findPayloadForSkippedPostskipTemporalPrediction();
        FrameHeader.SegmentationInfo segmentation = createSegmentationInfo(false, true, 1, defaultSegments(), new boolean[8], new int[8]);
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, segmentation, false, false);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        BlockPosition position = new BlockPosition(4, 4);
        seedTemporalSegmentPrediction(neighborContext, 1);

        assertEquals(2, neighborContext.segmentPredictionContext(position));
        assertEquals(1, neighborContext.currentSegmentPrediction(position).predictedSegmentId());

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        assertTrue(expectedSkip);
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );

        CdfContext shiftedCdf = CdfContext.createDefault();
        MsacDecoder shiftedDecoder = new MsacDecoder(payload, 0, payload.length, false);
        shiftedDecoder.decodeBooleanAdapt(shiftedCdf.mutableSkipCdf(0));
        shiftedDecoder.decodeBooleanAdapt(shiftedCdf.mutableSegmentPredictionCdf(2));
        LumaIntraPredictionMode shiftedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                shiftedDecoder.decodeSymbolAdapt(
                        shiftedCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        assertFalse(expectedYMode == shiftedYMode);

        TileBlockHeaderReader.BlockHeader header = reader.read(position, BlockSize.SIZE_8X8, neighborContext);

        assertTrue(header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertFalse(header.segmentPredicted());
        assertEquals(1, header.segmentId());
        assertEquals(expectedYMode, header.yMode());
        assertEquals(expectedUvMode, header.uvMode());
    }

    /// Verifies that screen-content palette syntax populates luma/chroma palettes and packed palette indices.
    @Test
    void readsPaletteBlockHeaderSizes() {
        byte[] payload = findPayloadForPaletteBlock();
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload, false, defaultDisabledSegmentation(), true, true);
        TileBlockHeaderReader reader = new TileBlockHeaderReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                        12
                )
        );
        assertEquals(LumaIntraPredictionMode.DC, expectedYMode);
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()), 13)
        );
        assertEquals(UvIntraPredictionMode.DC, expectedUvMode);
        boolean expectedUseLumaPalette = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableLumaPaletteCdf(0, 0));
        assertTrue(expectedUseLumaPalette);
        int expectedLumaPaletteSize = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutablePaletteSizeCdf(0, 0), 6) + 2;
        int[] expectedLumaPalette = decodePalettePlaneWithoutCache(oracleDecoder, 8, 0, expectedLumaPaletteSize);
        boolean expectedUseChromaPalette = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableChromaPaletteCdf(1));
        assertTrue(expectedUseChromaPalette);
        int expectedChromaPaletteSize = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutablePaletteSizeCdf(1, 0), 6) + 2;
        int[] expectedChromaPaletteU = decodePalettePlaneWithoutCache(oracleDecoder, 8, 1, expectedChromaPaletteSize);
        int[] expectedChromaPaletteV = decodeChromaVPaletteWithoutCache(oracleDecoder, 8, expectedChromaPaletteSize);
        byte[] expectedLumaIndices = decodePaletteIndicesWithoutCache(
                oracleDecoder,
                oracleCdf,
                0,
                expectedLumaPaletteSize,
                8,
                8,
                8,
                8
        );
        byte[] expectedChromaIndices = decodePaletteIndicesWithoutCache(
                oracleDecoder,
                oracleCdf,
                1,
                expectedChromaPaletteSize,
                4,
                4,
                4,
                4
        );

        TileBlockHeaderReader.BlockHeader header = reader.read(new BlockPosition(0, 0), BlockSize.SIZE_8X8, neighborContext);

        assertEquals(expectedSkip, header.skip());
        assertTrue(header.intra());
        assertFalse(header.useIntrabc());
        assertEquals(LumaIntraPredictionMode.DC, header.yMode());
        assertEquals(UvIntraPredictionMode.DC, header.uvMode());
        assertEquals(expectedLumaPaletteSize, header.yPaletteSize());
        assertEquals(expectedChromaPaletteSize, header.uvPaletteSize());
        assertArrayEquals(expectedLumaPalette, header.yPaletteColors());
        assertArrayEquals(expectedChromaPaletteU, header.uPaletteColors());
        assertArrayEquals(expectedChromaPaletteV, header.vPaletteColors());
        assertArrayEquals(expectedLumaIndices, header.yPaletteIndices());
        assertArrayEquals(expectedChromaIndices, header.uvPaletteIndices());
        assertNull(header.filterIntraMode());
        assertEquals(expectedLumaPaletteSize, neighborContext.abovePaletteSize(0));
        assertEquals(expectedChromaPaletteSize, neighborContext.aboveChromaPaletteSize(0));
        assertEquals(expectedLumaPalette[0], neighborContext.abovePaletteEntry(0, 0, 0));
        assertEquals(expectedChromaPaletteU[0], neighborContext.abovePaletteEntry(1, 0, 0));
    }

    /// Decodes one signed CFL alpha value with the same sign rules as `TileSyntaxReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param context the CFL-alpha context index
    /// @param positive whether the decoded alpha should be positive
    /// @return the decoded signed CFL alpha value
    private static int decodeSignedCflAlpha(MsacDecoder decoder, CdfContext cdfContext, int context, boolean positive) {
        int alpha = decoder.decodeSymbolAdapt(cdfContext.mutableCflAlphaCdf(context), 15) + 1;
        return positive ? alpha : -alpha;
    }

    /// Decodes one palette plane without any cached entries, matching the first-block palette path.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param bitDepth the decoded bit depth
    /// @param plane the palette plane index, where `0` is Y and `1` is U
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @return the decoded palette entries
    private static int[] decodePalettePlaneWithoutCache(MsacDecoder decoder, int bitDepth, int plane, int paletteSize) {
        int[] palette = new int[paletteSize];
        int max = (1 << bitDepth) - 1;
        int step = plane == 0 ? 1 : 0;
        int previous = decoder.decodeBools(bitDepth);
        palette[0] = previous;
        if (paletteSize == 1) {
            return palette;
        }

        int bits = bitDepth - 3 + decoder.decodeBools(2);
        int index = 1;
        while (index < paletteSize) {
            int delta = decoder.decodeBools(bits);
            previous = Math.min(previous + delta + step, max);
            palette[index++] = previous;
            if (previous + step >= max) {
                for (; index < paletteSize; index++) {
                    palette[index] = max;
                }
                break;
            }
            bits = Math.min(bits, Integer.SIZE - Integer.numberOfLeadingZeros(max - previous - step));
        }
        return palette;
    }

    /// Decodes one V chroma palette without cached entries, matching the first-block palette path.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param bitDepth the decoded bit depth
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @return the decoded V chroma palette entries
    private static int[] decodeChromaVPaletteWithoutCache(MsacDecoder decoder, int bitDepth, int paletteSize) {
        int[] palette = new int[paletteSize];
        int max = (1 << bitDepth) - 1;
        if (decoder.decodeBooleanEqui()) {
            int bits = bitDepth - 4 + decoder.decodeBools(2);
            int previous = decoder.decodeBools(bitDepth);
            palette[0] = previous;
            for (int i = 1; i < paletteSize; i++) {
                int delta = decoder.decodeBools(bits);
                if (delta != 0 && decoder.decodeBooleanEqui()) {
                    delta = -delta;
                }
                previous = (previous + delta) & max;
                palette[i] = previous;
            }
        } else {
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = decoder.decodeBools(bitDepth);
            }
        }
        return palette;
    }

    /// Decodes one packed palette index map without palette-cache reuse, matching the first-block path.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param plane the palette plane index, where `0` is Y and `1` is UV
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param visibleWidth the visible palette width in pixels
    /// @param visibleHeight the visible palette height in pixels
    /// @param fullWidth the coded palette width in pixels
    /// @param fullHeight the coded palette height in pixels
    /// @return the packed palette indices with invisible edges replicated
    private static byte[] decodePaletteIndicesWithoutCache(
            MsacDecoder decoder,
            CdfContext cdfContext,
            int plane,
            int paletteSize,
            int visibleWidth,
            int visibleHeight,
            int fullWidth,
            int fullHeight
    ) {
        byte[] unpacked = new byte[fullWidth * fullHeight];
        unpacked[0] = (byte) decoder.decodeUniform(paletteSize);
        int[] order = new int[8];
        for (int i = 1; i < visibleWidth + visibleHeight - 1; i++) {
            int first = Math.min(i, visibleWidth - 1);
            int last = Math.max(0, i - visibleHeight + 1);
            for (int x = first; x >= last; x--) {
                int y = i - x;
                int context = buildPaletteOrder(unpacked, fullWidth, x, y, order);
                int colorIndex = decoder.decodeSymbolAdapt(cdfContext.mutableColorMapCdf(plane, paletteSize - 2, context), paletteSize - 1);
                unpacked[y * fullWidth + x] = (byte) order[colorIndex];
            }
        }
        return finishPaletteIndices(unpacked, fullWidth, fullHeight, visibleWidth, visibleHeight);
    }

    /// Builds the AV1 palette-order permutation and context for one oracle palette index sample.
    ///
    /// @param indices the unpacked palette indices decoded so far
    /// @param stride the unpacked palette row stride in pixels
    /// @param x the current X coordinate in pixels
    /// @param y the current Y coordinate in pixels
    /// @param order the reusable destination array that receives the palette-order permutation
    /// @return the zero-based palette color-map context
    private static int buildPaletteOrder(byte[] indices, int stride, int x, int y, int[] order) {
        int count = 0;
        int mask = 0;
        int context;
        if (x == 0) {
            int top = indices[(y - 1) * stride] & 0xFF;
            order[count++] = top;
            mask |= 1 << top;
            context = 0;
        } else if (y == 0) {
            int left = indices[x - 1] & 0xFF;
            order[count++] = left;
            mask |= 1 << left;
            context = 0;
        } else {
            int left = indices[y * stride + x - 1] & 0xFF;
            int top = indices[(y - 1) * stride + x] & 0xFF;
            int topLeft = indices[(y - 1) * stride + x - 1] & 0xFF;
            boolean sameTopLeft = top == left;
            boolean sameTopTopLeft = top == topLeft;
            boolean sameLeftTopLeft = left == topLeft;
            if (sameTopLeft && sameTopTopLeft) {
                order[count++] = top;
                mask |= 1 << top;
                context = 4;
            } else if (sameTopLeft) {
                order[count++] = top;
                order[count++] = topLeft;
                mask |= 1 << top;
                mask |= 1 << topLeft;
                context = 3;
            } else if (sameTopTopLeft || sameLeftTopLeft) {
                order[count++] = topLeft;
                order[count++] = sameTopTopLeft ? left : top;
                mask |= 1 << topLeft;
                mask |= 1 << (sameTopTopLeft ? left : top);
                context = 2;
            } else {
                int first = Math.min(top, left);
                int second = Math.max(top, left);
                order[count++] = first;
                order[count++] = second;
                order[count++] = topLeft;
                mask |= 1 << first;
                mask |= 1 << second;
                mask |= 1 << topLeft;
                context = 1;
            }
        }

        for (int value = 0; value < 8; value++) {
            if ((mask & (1 << value)) == 0) {
                order[count++] = value;
            }
        }
        return context;
    }

    /// Packs one oracle palette map to two 4-bit entries per byte and replicates invisible edges.
    ///
    /// @param unpacked the unpacked palette map in raster order
    /// @param fullWidth the coded palette width in pixels
    /// @param fullHeight the coded palette height in pixels
    /// @param visibleWidth the visible palette width in pixels
    /// @param visibleHeight the visible palette height in pixels
    /// @return the packed palette map with invisible edges replicated
    private static byte[] finishPaletteIndices(
            byte[] unpacked,
            int fullWidth,
            int fullHeight,
            int visibleWidth,
            int visibleHeight
    ) {
        int packedStride = fullWidth >> 1;
        int visiblePackedWidth = visibleWidth >> 1;
        byte[] packed = new byte[packedStride * fullHeight];
        for (int y = 0; y < visibleHeight; y++) {
            int sourceRow = y * fullWidth;
            int packedRow = y * packedStride;
            for (int x = 0; x < visiblePackedWidth; x++) {
                packed[packedRow + x] = (byte) ((unpacked[sourceRow + (x << 1)] & 0x0F)
                        | ((unpacked[sourceRow + (x << 1) + 1] & 0x0F) << 4));
            }
            if (visiblePackedWidth < packedStride) {
                for (int x = visiblePackedWidth; x < packedStride; x++) {
                    packed[packedRow + x] = (byte) ((unpacked[sourceRow + visibleWidth - 1] & 0xFF) * 0x11);
                }
            }
        }
        if (visibleHeight < fullHeight) {
            int lastVisibleRow = (visibleHeight - 1) * packedStride;
            for (int y = visibleHeight; y < fullHeight; y++) {
                System.arraycopy(packed, lastVisibleRow, packed, y * packedStride, packedStride);
            }
        }
        return packed;
    }

    /// Finds a small payload whose first `intrabc` decision decodes to `true`.
    ///
    /// @return a small payload whose first `intrabc` decision decodes to `true`
    private static byte[] findPayloadForIntrabc() {
        for (int value = 0; value < 256; value++) {
            byte[] payload = new byte[]{(byte) value, 0x00, 0x00, 0x00, 0x00};
            CdfContext oracleCdf = CdfContext.createDefault();
            MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
            oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
            if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntrabcCdf())) {
                return payload;
            }
        }
        throw new IllegalStateException("No deterministic payload produced intrabc=true");
    }

    /// Finds a small payload whose first block decodes as `DC + use_filter_intra`.
    ///
    /// @return a small payload whose first block decodes as `DC + use_filter_intra`
    private static byte[] findPayloadForFilterIntraBlock() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
                LumaIntraPredictionMode yMode = LumaIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(
                                oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                                12
                        )
                );
                if (yMode != LumaIntraPredictionMode.DC) {
                    continue;
                }

                UvIntraPredictionMode uvMode = UvIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, yMode.symbolIndex()), 13)
                );
                if (uvMode == UvIntraPredictionMode.CFL) {
                    int signSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCflSignCdf(), 7) + 1;
                    int signU = signSymbol / 3;
                    int signV = signSymbol - signU * 3;
                    if (signU != 0) {
                        decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signU == 2 ? 3 : 0) + signV, signU == 2);
                    }
                    if (signV != 0) {
                        decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signV == 2 ? 3 : 0) + signU, signV == 2);
                    }
                } else if (uvMode.isDirectional()) {
                    oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(uvMode.angleDeltaContextIndex()), 6);
                }

                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex()))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced use_filter_intra=true after block syntax");
    }

    /// Finds a small payload whose first preskip segment-id decode resolves to segment `1`.
    ///
    /// @return a small payload whose first preskip segment-id decode resolves to segment `1`
    private static byte[] findPayloadForPreskipSegmentOne() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableSegmentIdCdf(0), 7) == 1) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced seg_id=1 for the preskip path");
    }

    /// Finds a small payload whose first postskip segment-id decode resolves to segment `1` and whose
    /// UV mode would decode as CFL when lossless gating is disabled.
    ///
    /// @return a small payload whose first postskip segment-id decode resolves to segment `1`
    private static byte[] findPayloadForPostskipLosslessSegment() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableSegmentIdCdf(0), 7) != 1) {
                    continue;
                }
                LumaIntraPredictionMode yMode = LumaIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(
                                oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                                12
                        )
                );
                if (yMode.isDirectional()) {
                    oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableAngleDeltaCdf(yMode.angleDeltaContextIndex()), 6);
                }
                UvIntraPredictionMode uvMode = UvIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, yMode.symbolIndex()), 13)
                );
                if (uvMode == UvIntraPredictionMode.CFL) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced seg_id=1 with CFL-eligible UV mode");
    }

    /// Finds a small payload whose first temporal segmentation-prediction flag decodes to `true`.
    ///
    /// @return a small payload whose first temporal segmentation-prediction flag decodes to `true`
    private static byte[] findPayloadForTemporalPreskipPrediction() {
        for (int first = 0; first < 256; first++) {
            byte[] payload = new byte[]{(byte) first, 0x00, 0x00, 0x00, 0x00};
            CdfContext oracleCdf = CdfContext.createDefault();
            MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
            if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSegmentPredictionCdf(2))) {
                return payload;
            }
        }
        throw new IllegalStateException("No deterministic payload produced seg_id_predicted=true for the preskip path");
    }

    /// Finds a small payload whose first block decodes `skip = false` and `seg_id_predicted = true`.
    ///
    /// @return a small payload whose first block decodes `skip = false` and `seg_id_predicted = true`
    private static byte[] findPayloadForTemporalPostskipPrediction() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSegmentPredictionCdf(2))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced seg_id_predicted=true for the postskip path");
    }

    /// Finds a small payload whose first skipped postskip block would produce a different key-frame
    /// Y mode if a temporal prediction flag were consumed incorrectly.
    ///
    /// @return a small payload whose first skipped postskip block exposes the temporal prediction bug
    private static byte[] findPayloadForSkippedPostskipTemporalPrediction() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                            oracleDecoder.decodeSymbolAdapt(
                                    oracleCdf.mutableKeyFrameYModeCdf(
                                            LumaIntraPredictionMode.DC.contextIndex(),
                                            LumaIntraPredictionMode.DC.contextIndex()
                                    ),
                                    12
                            )
                    );

                    CdfContext shiftedCdf = CdfContext.createDefault();
                    MsacDecoder shiftedDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    shiftedDecoder.decodeBooleanAdapt(shiftedCdf.mutableSkipCdf(0));
                    shiftedDecoder.decodeBooleanAdapt(shiftedCdf.mutableSegmentPredictionCdf(2));
                    LumaIntraPredictionMode shiftedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                            shiftedDecoder.decodeSymbolAdapt(
                                    shiftedCdf.mutableKeyFrameYModeCdf(
                                            LumaIntraPredictionMode.DC.contextIndex(),
                                            LumaIntraPredictionMode.DC.contextIndex()
                                    ),
                                    12
                            )
                    );
                    if (expectedYMode != shiftedYMode) {
                        return payload;
                    }
                }
            }
        }
        throw new IllegalStateException("No deterministic payload exposed the skipped postskip temporal prediction bug");
    }

    /// Finds a small payload whose first inter block decodes `skip = true` and whose skipped
    /// intra/inter decision would decode to `intra = true` if it were consumed.
    ///
    /// @return a small payload whose first inter block decodes `skip = true`
    private static byte[] findPayloadForSkippedInterBlock() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced skip=true with a skipped intra=true decision");
    }

    /// Finds a small payload whose first inter block decodes `skip_mode = true`, whose skipped
    /// skip flag would decode to `false`, and whose skipped intra/inter bit would decode to `true`.
    ///
    /// @return a small payload whose first inter block exposes skip-mode short-circuiting
    private static byte[] findPayloadForSkipModeInterBlock() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00};
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipModeCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                        return payload;
                    }
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced skip_mode=true with skipped skip=false and intra=true");
    }

    /// Finds a small payload whose first inter block decodes `skip = false`, `intra = false`,
    /// `compound = true`, and a stable compound reference pair.
    ///
    /// @return a small payload whose first inter block decodes a compound reference pair
    private static byte[] findPayloadForCompoundReferenceBlock() {
        BlockNeighborContext neighborContext = BlockNeighborContext.create(createTileContext(
                FrameType.INTER,
                false,
                new byte[]{0x00},
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        ));
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                        continue;
                    }
                    if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(neighborContext.compoundReferenceContext(position)))) {
                        continue;
                    }
                    decodeCompoundReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a compound-reference inter block");
    }

    /// Finds a small payload whose first inter block decodes `skip = false`, `intra = false`,
    /// and `compound = false` so single-reference syntax is exercised.
    ///
    /// @return a small payload whose first inter block decodes a single-reference selection
    private static byte[] findPayloadForSingleReferenceBlock() {
        BlockNeighborContext neighborContext = BlockNeighborContext.create(createTileContext(
                FrameType.INTER,
                false,
                new byte[]{0x00},
                false,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                true
        ));
        BlockPosition position = new BlockPosition(4, 4);
        seedInterReferenceNeighbors(neighborContext);

        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(neighborContext.compoundReferenceContext(position)))) {
                        continue;
                    }
                    decodeSingleReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a single-reference inter block");
    }

    /// Finds a small payload whose first inter block decodes `skip = false` and whose consumed
    /// intra/inter decision would decode to `false`.
    ///
    /// @return a small payload whose first inter block decodes `skip = false`
    private static byte[] findPayloadForSegmentReferenceForcedIntra() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced skip=false with a skipped intra=false decision");
    }

    /// Finds a small payload whose first inter block decodes `skip = false` and whose consumed
    /// intra/inter decision would decode to `true`.
    ///
    /// @return a small payload whose first inter block decodes `skip = false`
    private static byte[] findPayloadForSegmentReferenceForcedInter() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced skip=false with a skipped intra=true decision");
    }

    /// Finds a small payload whose first inter block decodes `skip = false` and `intra = false`.
    ///
    /// @return a small payload whose first inter block decodes `skip = false` and `intra = false`
    private static byte[] findPayloadForInterBlockWithoutSkipOrIntra() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced skip=false and intra=false");
    }

    /// Finds a small payload whose first inter block decodes a `NEWMV` single-reference path with a non-zero residual.
    ///
    /// @return a small payload whose first inter block decodes a `NEWMV` single-reference path with a non-zero residual
    private static byte[] findPayloadForSingleInterModeBlock() {
        BlockPosition position = new BlockPosition(4, 4);
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(createTileContext(
                            FrameType.INTER,
                            false,
                            new byte[]{0x00},
                            false,
                            defaultDisabledSegmentation(),
                            false,
                            true,
                            false,
                            true
                    ));
                    seedInterReferenceNeighbors(neighborContext);
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2))) {
                        continue;
                    }
                    int referenceFrame0 = decodeSingleReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
                    BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                            neighborContext.provisionalInterModeContext(position, BlockSize.SIZE_16X16, false, referenceFrame0, -1);
                    InterModeExpectation expectation =
                            decodeSingleInterModeExpectation(oracleDecoder, oracleCdf, provisionalContext);
                    if (expectation.singleInterMode() != SingleInterPredictionMode.NEWMV || expectation.drlIndex() <= 0) {
                        continue;
                    }
                    MotionVector predictor = provisionalContext.motionVectorCandidate(expectation.drlIndex()).motionVector0().vector();
                    MotionVector decodedMotionVector = decodeMotionVectorResidual(oracleDecoder, oracleCdf, predictor, false, false);
                    if (!decodedMotionVector.equals(predictor)) {
                        return payload;
                    }
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a NEWMV single-reference block with a non-zero residual");
    }

    /// Finds a small payload whose first inter block decodes a `NEWMV_NEWMV` compound path with non-zero residuals.
    ///
    /// @return a small payload whose first inter block decodes a `NEWMV_NEWMV` compound path with non-zero residuals
    private static byte[] findPayloadForCompoundInterModeBlock() {
        BlockPosition position = new BlockPosition(4, 4);
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                for (int third = 0; third < 256; third++) {
                    byte[] payload = new byte[]{(byte) first, (byte) second, (byte) third, 0x00, 0x00, 0x00};
                    BlockNeighborContext neighborContext = BlockNeighborContext.create(createTileContext(
                            FrameType.INTER,
                            false,
                            new byte[]{0x00},
                            false,
                            defaultDisabledSegmentation(),
                            false,
                            true,
                            false,
                            true
                    ));
                    seedInterReferenceNeighbors(neighborContext);
                    CdfContext oracleCdf = CdfContext.createDefault();
                    MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                        continue;
                    }
                    if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0))) {
                        continue;
                    }
                    if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2))) {
                        continue;
                    }
                    InterReferenceExpectation references =
                            decodeCompoundReferenceExpectation(oracleDecoder, oracleCdf, neighborContext, position);
                    BlockNeighborContext.ProvisionalInterModeContext provisionalContext =
                            neighborContext.provisionalInterModeContext(
                                    position,
                                    BlockSize.SIZE_16X16,
                                    true,
                                    references.referenceFrame0(),
                                    references.referenceFrame1()
                            );
                    InterModeExpectation expectation =
                            decodeCompoundInterModeExpectation(oracleDecoder, oracleCdf, provisionalContext);
                    if (expectation.compoundInterMode() != CompoundInterPredictionMode.NEWMV_NEWMV || expectation.drlIndex() <= 0) {
                        continue;
                    }
                    BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate candidate =
                            provisionalContext.motionVectorCandidate(expectation.drlIndex());
                    MotionVector predictor0 = candidate.motionVector0().vector();
                    @org.jetbrains.annotations.Nullable InterMotionVector predictor1State = candidate.motionVector1();
                    if (predictor1State == null) {
                        throw new IllegalStateException("Compound provisional candidate must carry a secondary motion vector");
                    }
                    MotionVector predictor1 = predictor1State.vector();
                    MotionVector decodedMotionVector0 = decodeMotionVectorResidual(oracleDecoder, oracleCdf, predictor0, false, false);
                    MotionVector decodedMotionVector1 = decodeMotionVectorResidual(oracleDecoder, oracleCdf, predictor1, false, false);
                    if (!decodedMotionVector0.equals(predictor0) && !decodedMotionVector1.equals(predictor1)) {
                        return payload;
                    }
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a NEWMV_NEWMV compound block with non-zero residuals");
    }

    /// Finds a small payload whose first key-frame block decodes as `DC/DC` with both luma and chroma palette enabled.
    ///
    /// @return a small payload whose first key-frame block decodes as `DC/DC` with both luma and chroma palette enabled
    private static byte[] findPayloadForPaletteBlock() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC};
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
                LumaIntraPredictionMode yMode = LumaIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(
                                oracleCdf.mutableKeyFrameYModeCdf(LumaIntraPredictionMode.DC.contextIndex(), LumaIntraPredictionMode.DC.contextIndex()),
                                12
                        )
                );
                if (yMode != LumaIntraPredictionMode.DC) {
                    continue;
                }
                UvIntraPredictionMode uvMode = UvIntraPredictionMode.fromSymbolIndex(
                        oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableUvModeCdf(true, yMode.symbolIndex()), 13)
                );
                if (uvMode != UvIntraPredictionMode.DC) {
                    continue;
                }
                if (!oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableLumaPaletteCdf(0, 0))) {
                    continue;
                }
                int lumaPaletteSize = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutablePaletteSizeCdf(0, 0), 6) + 2;
                decodePalettePlaneWithoutCache(oracleDecoder, 8, 0, lumaPaletteSize);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableChromaPaletteCdf(1))) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced palette-enabled DC/DC block syntax");
    }

    /// Finds a small payload whose first key-frame block decodes non-zero CDEF and delta-q/delta-lf side syntax.
    ///
    /// @return a small payload whose first key-frame block decodes non-zero CDEF and delta-q/delta-lf side syntax
    private static byte[] findPayloadForCdefAndDeltaSyntax() {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{
                        (byte) first,
                        (byte) second,
                        0x45,
                        0x67,
                        (byte) 0x89,
                        (byte) 0xAB,
                        (byte) 0xCD,
                        (byte) 0xEF
                };
                CdfContext oracleCdf = CdfContext.createDefault();
                MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
                if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0))) {
                    continue;
                }
                int cdefIndex = oracleDecoder.decodeBools(2);
                int deltaQ = decodeSignedDeltaValue(oracleDecoder, oracleCdf.mutableDeltaQCdf(), 0);
                int deltaLf = decodeSignedDeltaValue(oracleDecoder, oracleCdf.mutableDeltaLfCdf(0), 0);
                if (cdefIndex > 0 && deltaQ != 0 && deltaLf != 0) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced non-zero CDEF and delta-q/delta-lf syntax");
    }

    /// Decodes one compound-reference expectation with the same contexts as `TileBlockHeaderReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param neighborContext the seeded neighbor context
    /// @param position the block position under test
    /// @return the decoded compound-reference expectation
    private static InterReferenceExpectation decodeCompoundReferenceExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext neighborContext,
            BlockPosition position
    ) {
        if (decoder.decodeBooleanAdapt(cdfContext.mutableCompoundDirectionCdf(neighborContext.compoundDirectionContext(position)))) {
            int referenceFrame0;
            if (decoder.decodeBooleanAdapt(cdfContext.mutableCompoundForwardReferenceCdf(0, neighborContext.forwardReferenceContext(position)))) {
                referenceFrame0 = 2 + (decoder.decodeBooleanAdapt(
                        cdfContext.mutableCompoundForwardReferenceCdf(2, neighborContext.forwardReference2Context(position))
                ) ? 1 : 0);
            } else {
                referenceFrame0 = decoder.decodeBooleanAdapt(
                        cdfContext.mutableCompoundForwardReferenceCdf(1, neighborContext.forwardReference1Context(position))
                ) ? 1 : 0;
            }

            int referenceFrame1;
            if (decoder.decodeBooleanAdapt(cdfContext.mutableCompoundBackwardReferenceCdf(0, neighborContext.backwardReferenceContext(position)))) {
                referenceFrame1 = 6;
            } else {
                referenceFrame1 = 4 + (decoder.decodeBooleanAdapt(
                        cdfContext.mutableCompoundBackwardReferenceCdf(1, neighborContext.backwardReference1Context(position))
                ) ? 1 : 0);
            }
            return new InterReferenceExpectation(referenceFrame0, referenceFrame1);
        }

        if (decoder.decodeBooleanAdapt(cdfContext.mutableCompoundUnidirectionalReferenceCdf(0, neighborContext.singleReferenceContext(position)))) {
            return new InterReferenceExpectation(4, 6);
        }

        int referenceFrame1 = 1 + (decoder.decodeBooleanAdapt(
                cdfContext.mutableCompoundUnidirectionalReferenceCdf(1, neighborContext.unidirectionalReference1Context(position))
        ) ? 1 : 0);
        if (referenceFrame1 == 2) {
            referenceFrame1 += decoder.decodeBooleanAdapt(
                    cdfContext.mutableCompoundUnidirectionalReferenceCdf(2, neighborContext.forwardReference2Context(position))
            ) ? 1 : 0;
        }
        return new InterReferenceExpectation(0, referenceFrame1);
    }

    /// Decodes one single-reference expectation with the same contexts as `TileBlockHeaderReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param neighborContext the seeded neighbor context
    /// @param position the block position under test
    /// @return the decoded single reference in internal LAST..ALTREF order
    private static int decodeSingleReferenceExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext neighborContext,
            BlockPosition position
    ) {
        if (decoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(0, neighborContext.singleReferenceContext(position)))) {
            if (decoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(1, neighborContext.backwardReferenceContext(position)))) {
                return 6;
            }
            return 4 + (decoder.decodeBooleanAdapt(
                    cdfContext.mutableSingleReferenceCdf(5, neighborContext.backwardReference1Context(position))
            ) ? 1 : 0);
        }
        if (decoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(2, neighborContext.forwardReferenceContext(position)))) {
            return 2 + (decoder.decodeBooleanAdapt(
                    cdfContext.mutableSingleReferenceCdf(4, neighborContext.forwardReference2Context(position))
            ) ? 1 : 0);
        }
        return decoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(3, neighborContext.forwardReference1Context(position)))
                ? 1
                : 0;
    }

    /// Decodes one single-reference inter-mode expectation using the same provisional contexts as the reader.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @return the decoded single-reference inter-mode expectation
    private static InterModeExpectation decodeSingleInterModeExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext
    ) {
        SingleInterPredictionMode mode;
        if (decoder.decodeBooleanAdapt(cdfContext.mutableSingleInterNewMvCdf(provisionalContext.singleNewMvContext()))) {
            if (!decoder.decodeBooleanAdapt(cdfContext.mutableSingleInterGlobalMvCdf(provisionalContext.singleGlobalMvContext()))) {
                mode = SingleInterPredictionMode.GLOBALMV;
            } else {
                mode = decoder.decodeBooleanAdapt(cdfContext.mutableSingleInterReferenceMvCdf(provisionalContext.singleReferenceMvContext()))
                        ? SingleInterPredictionMode.NEARMV
                        : SingleInterPredictionMode.NEARESTMV;
            }
        } else {
            mode = SingleInterPredictionMode.NEWMV;
        }

        int drlIndex = switch (mode) {
            case GLOBALMV, NEARESTMV -> 0;
            case NEARMV -> decodeNearDrlExpectation(decoder, cdfContext, provisionalContext);
            case NEWMV -> decodeNewDrlExpectation(decoder, cdfContext, provisionalContext);
        };
        return new InterModeExpectation(mode, null, drlIndex);
    }

    /// Decodes one compound inter-mode expectation using the same provisional contexts as the reader.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @return the decoded compound inter-mode expectation
    private static InterModeExpectation decodeCompoundInterModeExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext
    ) {
        CompoundInterPredictionMode mode = CompoundInterPredictionMode.fromSymbolIndex(
                decoder.decodeSymbolAdapt(cdfContext.mutableCompoundInterModeCdf(provisionalContext.compoundInterModeContext()), 7)
        );
        int drlIndex = 0;
        if (mode == CompoundInterPredictionMode.NEWMV_NEWMV) {
            if (provisionalContext.candidateCount() > 1) {
                drlIndex += decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(0))) ? 1 : 0;
                if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
                    drlIndex += decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(1))) ? 1 : 0;
                }
            }
        } else if (mode.usesNearMotionVector()) {
            drlIndex = decodeNearDrlExpectation(decoder, cdfContext, provisionalContext);
        }
        return new InterModeExpectation(null, mode, drlIndex);
    }

    /// Decodes the provisional DRL expectation for one `NEWMV` block.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @return the decoded provisional DRL index for one `NEWMV` block
    private static int decodeNewDrlExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext
    ) {
        if (provisionalContext.candidateCount() <= 1) {
            return 0;
        }
        int drlIndex = decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(0))) ? 1 : 0;
        if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
            drlIndex += decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(1))) ? 1 : 0;
        }
        return drlIndex;
    }

    /// Decodes the provisional DRL expectation for one `NEARMV`-carrying block.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @return the decoded provisional DRL index for one `NEARMV`-carrying block
    private static int decodeNearDrlExpectation(
            MsacDecoder decoder,
            CdfContext cdfContext,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext
    ) {
        int drlIndex = 1;
        if (provisionalContext.candidateCount() > 2) {
            drlIndex += decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(1))) ? 1 : 0;
            if (drlIndex == 2 && provisionalContext.candidateCount() > 3) {
                drlIndex += decoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(provisionalContext.drlContext(2))) ? 1 : 0;
            }
        }
        return drlIndex;
    }

    /// Resolves the expected single-reference motion vector from one provisional candidate stack.
    ///
    /// @param decoder the oracle arithmetic decoder positioned after the inter-mode syntax
    /// @param cdfContext the oracle CDF context
    /// @param frameHeader the synthetic frame header that supplies motion-vector precision flags
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @param expectation the decoded inter-mode expectation
    /// @return the expected single-reference motion vector
    private static InterMotionVector expectedSingleMotionVector(
            MsacDecoder decoder,
            CdfContext cdfContext,
            FrameHeader frameHeader,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext,
            InterModeExpectation expectation
    ) {
        MsacDecoder nonNullDecoder = java.util.Objects.requireNonNull(decoder, "decoder");
        CdfContext nonNullCdfContext = java.util.Objects.requireNonNull(cdfContext, "cdfContext");
        FrameHeader nonNullFrameHeader = java.util.Objects.requireNonNull(frameHeader, "frameHeader");
        SingleInterPredictionMode mode = expectation.singleInterMode();
        if (mode == null) {
            throw new IllegalArgumentException("Single-reference expectation required");
        }
        return switch (mode) {
            case GLOBALMV -> InterMotionVector.resolved(MotionVector.zero());
            case NEARESTMV -> provisionalContext.motionVectorCandidate(0).motionVector0();
            case NEARMV -> provisionalContext.motionVectorCandidate(expectation.drlIndex()).motionVector0();
            case NEWMV -> InterMotionVector.resolved(decodeMotionVectorResidual(
                    nonNullDecoder,
                    nonNullCdfContext,
                    provisionalContext.motionVectorCandidate(expectation.drlIndex()).motionVector0().vector(),
                    nonNullFrameHeader.allowHighPrecisionMotionVectors(),
                    nonNullFrameHeader.forceIntegerMotionVectors()
            ));
        };
    }

    /// Resolves the expected first compound motion vector from one provisional candidate stack.
    ///
    /// @param decoder the oracle arithmetic decoder positioned after the inter-mode syntax
    /// @param cdfContext the oracle CDF context
    /// @param frameHeader the synthetic frame header that supplies motion-vector precision flags
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @param expectation the decoded inter-mode expectation
    /// @return the expected first compound motion vector
    private static InterMotionVector expectedCompoundMotionVector0(
            MsacDecoder decoder,
            CdfContext cdfContext,
            FrameHeader frameHeader,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext,
            InterModeExpectation expectation
    ) {
        MsacDecoder nonNullDecoder = java.util.Objects.requireNonNull(decoder, "decoder");
        CdfContext nonNullCdfContext = java.util.Objects.requireNonNull(cdfContext, "cdfContext");
        FrameHeader nonNullFrameHeader = java.util.Objects.requireNonNull(frameHeader, "frameHeader");
        CompoundInterPredictionMode mode = expectation.compoundInterMode();
        if (mode == null) {
            throw new IllegalArgumentException("Compound expectation required");
        }
        if (mode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
            return InterMotionVector.resolved(MotionVector.zero());
        }
        int candidateIndex = switch (mode) {
            case NEARESTMV_NEARESTMV, NEARESTMV_NEWMV, NEWMV_NEARESTMV, GLOBALMV_GLOBALMV -> 0;
            case NEARMV_NEARMV, NEARMV_NEWMV, NEWMV_NEARMV, NEWMV_NEWMV -> expectation.drlIndex();
        };
        InterMotionVector candidate = provisionalContext.motionVectorCandidate(candidateIndex).motionVector0();
        return switch (mode) {
            case NEWMV_NEARESTMV, NEWMV_NEARMV, NEWMV_NEWMV -> InterMotionVector.resolved(decodeMotionVectorResidual(
                    nonNullDecoder,
                    nonNullCdfContext,
                    candidate.vector(),
                    nonNullFrameHeader.allowHighPrecisionMotionVectors(),
                    nonNullFrameHeader.forceIntegerMotionVectors()
            ));
            default -> candidate;
        };
    }

    /// Resolves the expected second compound motion vector from one provisional candidate stack.
    ///
    /// @param decoder the oracle arithmetic decoder positioned after the first compound motion-vector syntax
    /// @param cdfContext the oracle CDF context
    /// @param frameHeader the synthetic frame header that supplies motion-vector precision flags
    /// @param provisionalContext the provisional inter-mode context derived from seeded neighbors
    /// @param expectation the decoded inter-mode expectation
    /// @return the expected second compound motion vector
    private static InterMotionVector expectedCompoundMotionVector1(
            MsacDecoder decoder,
            CdfContext cdfContext,
            FrameHeader frameHeader,
            BlockNeighborContext.ProvisionalInterModeContext provisionalContext,
            InterModeExpectation expectation
    ) {
        MsacDecoder nonNullDecoder = java.util.Objects.requireNonNull(decoder, "decoder");
        CdfContext nonNullCdfContext = java.util.Objects.requireNonNull(cdfContext, "cdfContext");
        FrameHeader nonNullFrameHeader = java.util.Objects.requireNonNull(frameHeader, "frameHeader");
        CompoundInterPredictionMode mode = expectation.compoundInterMode();
        if (mode == null) {
            throw new IllegalArgumentException("Compound expectation required");
        }
        if (mode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
            return InterMotionVector.resolved(MotionVector.zero());
        }
        int candidateIndex = switch (mode) {
            case NEARESTMV_NEARESTMV, NEARESTMV_NEWMV, NEWMV_NEARESTMV, GLOBALMV_GLOBALMV -> 0;
            case NEARMV_NEARMV, NEARMV_NEWMV, NEWMV_NEARMV, NEWMV_NEWMV -> expectation.drlIndex();
        };
        @org.jetbrains.annotations.Nullable InterMotionVector candidate = provisionalContext.motionVectorCandidate(candidateIndex).motionVector1();
        if (candidate == null) {
            throw new IllegalStateException("Compound provisional candidate must carry a secondary motion vector");
        }
        return switch (mode) {
            case NEARESTMV_NEWMV, NEARMV_NEWMV, NEWMV_NEWMV -> InterMotionVector.resolved(decodeMotionVectorResidual(
                    nonNullDecoder,
                    nonNullCdfContext,
                    candidate.vector(),
                    nonNullFrameHeader.allowHighPrecisionMotionVectors(),
                    nonNullFrameHeader.forceIntegerMotionVectors()
            ));
            default -> candidate;
        };
    }

    /// Decodes one motion-vector residual with the same syntax as `TileSyntaxReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param predictor the predictor that the residual is added to
    /// @param allowHighPrecisionMotionVectors whether the active frame allows high-precision motion vectors
    /// @param forceIntegerMotionVectors whether the active frame forces integer motion vectors
    /// @return the decoded motion vector in quarter-pel units
    private static MotionVector decodeMotionVectorResidual(
            MsacDecoder decoder,
            CdfContext cdfContext,
            MotionVector predictor,
            boolean allowHighPrecisionMotionVectors,
            boolean forceIntegerMotionVectors
    ) {
        int motionVectorPrecision = (allowHighPrecisionMotionVectors ? 1 : 0) - (forceIntegerMotionVectors ? 1 : 0);
        int motionVectorJoint = decoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorJointCdf(), 3);
        int rowQuarterPel = predictor.rowQuarterPel();
        int columnQuarterPel = predictor.columnQuarterPel();
        if ((motionVectorJoint & 2) != 0) {
            rowQuarterPel += decodeMotionVectorComponentDiff(decoder, cdfContext, 0, motionVectorPrecision);
        }
        if ((motionVectorJoint & 1) != 0) {
            columnQuarterPel += decodeMotionVectorComponentDiff(decoder, cdfContext, 1, motionVectorPrecision);
        }
        return new MotionVector(rowQuarterPel, columnQuarterPel);
    }

    /// Decodes one signed delta value with the same syntax as `TileSyntaxReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdf the oracle inverse CDF used for the delta-magnitude prefix
    /// @param resolutionLog2 the resolution log2 applied to non-zero decoded magnitudes
    /// @return the decoded signed and resolution-scaled delta value
    private static int decodeSignedDeltaValue(MsacDecoder decoder, int[] cdf, int resolutionLog2) {
        int magnitude = decoder.decodeSymbolAdapt(cdf, 3);
        if (magnitude == 3) {
            int bitCount = 1 + decoder.decodeBools(3);
            magnitude = decoder.decodeBools(bitCount) + 1 + (1 << bitCount);
        }
        if (magnitude == 0) {
            return 0;
        }
        if (decoder.decodeBooleanEqui()) {
            magnitude = -magnitude;
        }
        return magnitude << resolutionLog2;
    }

    /// Decodes one signed motion-vector component residual with the same syntax as `TileSyntaxReader`.
    ///
    /// @param decoder the oracle arithmetic decoder
    /// @param cdfContext the oracle CDF context
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @param motionVectorPrecision the active motion-vector precision mode
    /// @return the decoded signed motion-vector component residual in quarter-pel units
    private static int decodeMotionVectorComponentDiff(
            MsacDecoder decoder,
            CdfContext cdfContext,
            int component,
            int motionVectorPrecision
    ) {
        boolean negative = decoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorSignCdf(component));
        int motionVectorClass = decoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorClassCdf(component), 10);
        int integerMagnitude;
        int fractionalPart = 3;
        int highPrecisionBit = 1;
        if (motionVectorClass == 0) {
            integerMagnitude = decoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClass0Cdf(component)) ? 1 : 0;
            if (motionVectorPrecision >= 0) {
                fractionalPart = decoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorClass0FpCdf(component, integerMagnitude), 3);
                if (motionVectorPrecision > 0) {
                    highPrecisionBit = decoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClass0HpCdf(component)) ? 1 : 0;
                }
            }
        } else {
            integerMagnitude = 1 << motionVectorClass;
            for (int bitIndex = 0; bitIndex < motionVectorClass; bitIndex++) {
                if (decoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClassNCdf(component, bitIndex))) {
                    integerMagnitude |= 1 << bitIndex;
                }
            }
            if (motionVectorPrecision >= 0) {
                fractionalPart = decoder.decodeSymbolAdapt(cdfContext.mutableMotionVectorClassNFpCdf(component), 3);
                if (motionVectorPrecision > 0) {
                    highPrecisionBit = decoder.decodeBooleanAdapt(cdfContext.mutableMotionVectorClassNHpCdf(component)) ? 1 : 0;
                }
            }
        }

        int diff = ((integerMagnitude << 3) | (fractionalPart << 1) | highPrecisionBit) + 1;
        return negative ? -diff : diff;
    }

    /// Clips one integer into the supplied inclusive range.
    ///
    /// @param value the value to clip
    /// @param min the inclusive lower bound
    /// @param max the inclusive upper bound
    /// @return the clipped value
    private static int clip(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /// Creates a simple tile context used by block-header tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @return a simple tile context used by block-header tests
    private static TileDecodeContext createTileContext(FrameType frameType, boolean allowIntrabc, byte[] payload) {
        return createTileContext(frameType, allowIntrabc, payload, false);
    }

    /// Creates a simple tile context used by block-header tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @param filterIntra whether the synthetic sequence enables filter intra
    /// @return a simple tile context used by block-header tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            boolean allowIntrabc,
            byte[] payload,
            boolean filterIntra
    ) {
        return createTileContext(
                frameType,
                allowIntrabc,
                payload,
                filterIntra,
                defaultDisabledSegmentation(),
                false,
                true,
                false,
                false
        );
    }

    /// Creates a simple tile context used by block-header tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @param filterIntra whether the synthetic sequence enables filter intra
    /// @param segmentation the synthetic frame segmentation state
    /// @param allLossless whether all segments in the synthetic frame are lossless
    /// @return a simple tile context used by block-header tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            boolean allowIntrabc,
            byte[] payload,
            boolean filterIntra,
            FrameHeader.SegmentationInfo segmentation,
            boolean allowScreenContentTools,
            boolean allLossless
    ) {
        return createTileContext(
                frameType,
                allowIntrabc,
                payload,
                filterIntra,
                segmentation,
                allowScreenContentTools,
                allLossless,
                false,
                false
        );
    }

    /// Creates a simple tile context used by block-header tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @param filterIntra whether the synthetic sequence enables filter intra
    /// @param segmentation the synthetic frame segmentation state
    /// @param allowScreenContentTools whether the synthetic frame enables screen-content tools
    /// @param allLossless whether all segments in the synthetic frame are lossless
    /// @param skipModeEnabled whether skip mode is enabled for the synthetic frame
    /// @param switchableCompoundReferences whether compound-reference mode is switchable for the synthetic frame
    /// @return a simple tile context used by block-header tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            boolean allowIntrabc,
            byte[] payload,
            boolean filterIntra,
            FrameHeader.SegmentationInfo segmentation,
            boolean allowScreenContentTools,
            boolean allLossless,
            boolean skipModeEnabled,
            boolean switchableCompoundReferences
    ) {
        return createTileContext(
                frameType,
                allowIntrabc,
                payload,
                filterIntra,
                segmentation,
                allowScreenContentTools,
                allLossless,
                skipModeEnabled,
                switchableCompoundReferences,
                0,
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0])
        );
    }

    /// Creates a simple tile context used by block-header tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @param filterIntra whether the synthetic sequence enables filter intra
    /// @param segmentation the synthetic frame segmentation state
    /// @param allowScreenContentTools whether the synthetic frame enables screen-content tools
    /// @param allLossless whether all segments in the synthetic frame are lossless
    /// @param skipModeEnabled whether skip mode is enabled for the synthetic frame
    /// @param switchableCompoundReferences whether compound-reference mode is switchable for the synthetic frame
    /// @param baseQIndex the synthetic frame base quantizer index
    /// @param deltaInfo the synthetic frame delta-q/delta-lf state
    /// @param cdefInfo the synthetic frame CDEF state
    /// @return a simple tile context used by block-header tests
    private static TileDecodeContext createTileContext(
            FrameType frameType,
            boolean allowIntrabc,
            byte[] payload,
            boolean filterIntra,
            FrameHeader.SegmentationInfo segmentation,
            boolean allowScreenContentTools,
            boolean allLossless,
            boolean skipModeEnabled,
            boolean switchableCompoundReferences,
            int baseQIndex,
            FrameHeader.DeltaInfo deltaInfo,
            FrameHeader.CdefInfo cdefInfo
    ) {
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
                        filterIntra,
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
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        PixelFormat.I420,
                        0,
                        true,
                        true,
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
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
                new FrameHeader.SuperResolutionInfo(false, 8),
                allowIntrabc,
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
                new FrameHeader.QuantizationInfo(baseQIndex, 0, 0, 0, 0, 0, false, 0, 0, 0),
                segmentation,
                deltaInfo,
                allLossless,
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
                cdefInfo,
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
                switchableCompoundReferences,
                skipModeEnabled,
                skipModeEnabled,
                skipModeEnabled ? new int[]{0, 1} : new int[]{-1, -1},
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

    /// Creates disabled segmentation info used by block-header tests.
    ///
    /// @return disabled segmentation info used by block-header tests
    private static FrameHeader.SegmentationInfo defaultDisabledSegmentation() {
        return new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]);
    }

    /// Creates fixed segmentation info used by block-header tests that do not update per-block segment ids.
    ///
    /// @param segments the synthetic per-segment feature data
    /// @return fixed segmentation info used by block-header tests
    private static FrameHeader.SegmentationInfo createFixedSegmentationInfo(FrameHeader.SegmentData[] segments) {
        return new FrameHeader.SegmentationInfo(
                true,
                false,
                false,
                false,
                false,
                0,
                segments,
                new boolean[8],
                new int[8]
        );
    }

    /// Creates enabled segmentation info used by block-header tests.
    ///
    /// @param preskip whether the synthetic segmentation features require preskip decoding
    /// @param lastActiveSegmentId the highest active segment identifier
    /// @param segments the synthetic per-segment feature data
    /// @param losslessBySegment the synthetic per-segment lossless flags
    /// @param qIndexBySegment the synthetic per-segment qindex values
    /// @return enabled segmentation info used by block-header tests
    private static FrameHeader.SegmentationInfo createSegmentationInfo(
            boolean preskip,
            int lastActiveSegmentId,
            FrameHeader.SegmentData[] segments,
            boolean[] losslessBySegment,
            int[] qIndexBySegment
    ) {
        return createSegmentationInfo(preskip, false, lastActiveSegmentId, segments, losslessBySegment, qIndexBySegment);
    }

    /// Creates enabled segmentation info used by block-header tests.
    ///
    /// @param preskip whether the synthetic segmentation features require preskip decoding
    /// @param temporalUpdate whether the synthetic segmentation map uses temporal prediction
    /// @param lastActiveSegmentId the highest active segment identifier
    /// @param segments the synthetic per-segment feature data
    /// @param losslessBySegment the synthetic per-segment lossless flags
    /// @param qIndexBySegment the synthetic per-segment qindex values
    /// @return enabled segmentation info used by block-header tests
    private static FrameHeader.SegmentationInfo createSegmentationInfo(
            boolean preskip,
            boolean temporalUpdate,
            int lastActiveSegmentId,
            FrameHeader.SegmentData[] segments,
            boolean[] losslessBySegment,
            int[] qIndexBySegment
    ) {
        return new FrameHeader.SegmentationInfo(
                true,
                true,
                temporalUpdate,
                true,
                preskip,
                lastActiveSegmentId,
                segments,
                losslessBySegment,
                qIndexBySegment
        );
    }

    /// Seeds one neighbor context so the block at `(4, 4)` predicts the supplied segment identifier.
    ///
    /// @param neighborContext the mutable neighbor context to seed
    /// @param segmentId the predicted segment identifier to seed
    private static void seedTemporalSegmentPrediction(BlockNeighborContext neighborContext, int segmentId) {
        neighborContext.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                new BlockPosition(4, 0),
                BlockSize.SIZE_8X8,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                true,
                segmentId,
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
        ));
        neighborContext.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                new BlockPosition(0, 4),
                BlockSize.SIZE_8X8,
                true,
                false,
                false,
                true,
                false,
                false,
                -1,
                -1,
                true,
                segmentId,
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
        ));
    }

    /// Seeds one neighbor context so the block at `(4, 4)` observes a single forward reference
    /// above and a compound forward/backward reference on the left.
    ///
    /// @param neighborContext the mutable neighbor context to seed
    private static void seedInterReferenceNeighbors(BlockNeighborContext neighborContext) {
        neighborContext.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                new BlockPosition(4, 2),
                BlockSize.SIZE_16X8,
                true,
                false,
                false,
                false,
                false,
                false,
                0,
                -1,
                null,
                null,
                -1,
                InterMotionVector.resolved(new MotionVector(8, -4)),
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
        ));
        neighborContext.updateFromBlockHeader(new TileBlockHeaderReader.BlockHeader(
                new BlockPosition(2, 4),
                BlockSize.SIZE_8X16,
                true,
                false,
                false,
                false,
                false,
                true,
                0,
                4,
                null,
                null,
                -1,
                InterMotionVector.resolved(new MotionVector(12, 4)),
                InterMotionVector.predicted(new MotionVector(-8, 16)),
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
        ));
    }

    /// Creates default per-segment data with all features disabled.
    ///
    /// @return default per-segment data with all features disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// One decoded inter-reference expectation used by oracle tests.
    @NotNullByDefault
    private static final class InterReferenceExpectation {
        /// The primary inter reference in internal LAST..ALTREF order.
        private final int referenceFrame0;

        /// The secondary inter reference in internal LAST..ALTREF order.
        private final int referenceFrame1;

        /// Creates one decoded inter-reference expectation.
        ///
        /// @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
        /// @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
        private InterReferenceExpectation(int referenceFrame0, int referenceFrame1) {
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
        }

        /// Returns the primary inter reference in internal LAST..ALTREF order.
        ///
        /// @return the primary inter reference in internal LAST..ALTREF order
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the secondary inter reference in internal LAST..ALTREF order.
        ///
        /// @return the secondary inter reference in internal LAST..ALTREF order
        public int referenceFrame1() {
            return referenceFrame1;
        }
    }

    /// One decoded inter-mode expectation used by oracle tests.
    @NotNullByDefault
    private static final class InterModeExpectation {
        /// The decoded single-reference inter mode, or `null` when the block is compound.
        private final @org.jetbrains.annotations.Nullable SingleInterPredictionMode singleInterMode;

        /// The decoded compound inter mode, or `null` when the block is single-reference.
        private final @org.jetbrains.annotations.Nullable CompoundInterPredictionMode compoundInterMode;

        /// The decoded provisional dynamic-reference-list index.
        private final int drlIndex;

        /// Creates one decoded inter-mode expectation.
        ///
        /// @param singleInterMode the decoded single-reference inter mode, or `null`
        /// @param compoundInterMode the decoded compound inter mode, or `null`
        /// @param drlIndex the decoded provisional dynamic-reference-list index
        private InterModeExpectation(
                @org.jetbrains.annotations.Nullable SingleInterPredictionMode singleInterMode,
                @org.jetbrains.annotations.Nullable CompoundInterPredictionMode compoundInterMode,
                int drlIndex
        ) {
            this.singleInterMode = singleInterMode;
            this.compoundInterMode = compoundInterMode;
            this.drlIndex = drlIndex;
        }

        /// Returns the decoded single-reference inter mode, or `null` when the block is compound.
        ///
        /// @return the decoded single-reference inter mode, or `null`
        public @org.jetbrains.annotations.Nullable SingleInterPredictionMode singleInterMode() {
            return singleInterMode;
        }

        /// Returns the decoded compound inter mode, or `null` when the block is single-reference.
        ///
        /// @return the decoded compound inter mode, or `null`
        public @org.jetbrains.annotations.Nullable CompoundInterPredictionMode compoundInterMode() {
            return compoundInterMode;
        }

        /// Returns the decoded provisional dynamic-reference-list index.
        ///
        /// @return the decoded provisional dynamic-reference-list index
        public int drlIndex() {
            return drlIndex;
        }
    }
}
