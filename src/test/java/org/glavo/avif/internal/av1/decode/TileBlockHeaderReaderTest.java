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
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

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
        boolean expectedIntra = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(0));
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
                false,
                true,
                false,
                7,
                0,
                0xFF,
                new FrameHeader.FrameSize(64, 64, 64, 64, 64),
                new FrameHeader.SuperResolutionInfo(false, 8),
                allowIntrabc,
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
}
