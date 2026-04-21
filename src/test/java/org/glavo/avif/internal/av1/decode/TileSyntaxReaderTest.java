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
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileSyntaxReader`.
@NotNullByDefault
final class TileSyntaxReaderTest {
    /// Verifies that inter-frame skip, intra, and partition symbols use the expected tile-local CDF tables.
    @Test
    void readsInterFrameBlockSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedIntra = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntraCdf(2));
        PartitionType expectedPartition = PartitionType.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutablePartitionCdf(TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64.cdfIndex(), 1),
                        TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64.symbolLimit()
                )
        );

        assertEquals(expectedSkip, reader.readSkipFlag(0));
        assertArrayEquals(oracleCdf.mutableSkipCdf(0), tileContext.cdfContext().mutableSkipCdf(0));

        assertEquals(expectedIntra, reader.readIntraBlockFlag(2));
        assertArrayEquals(oracleCdf.mutableIntraCdf(2), tileContext.cdfContext().mutableIntraCdf(2));

        assertEquals(expectedPartition, reader.readPartition(TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64, 1));
        assertArrayEquals(
                oracleCdf.mutablePartitionCdf(TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64.cdfIndex(), 1),
                tileContext.cdfContext().mutablePartitionCdf(TileSyntaxReader.PartitionBlockLevel.BLOCK_64X64.cdfIndex(), 1)
        );
    }

    /// Verifies that key-frame intra decisions do not consume entropy-coded bits and that Y/UV modes use the expected CDFs.
    @Test
    void readsKeyFrameYAndUvModes() {
        byte[] payload = new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        LumaIntraPredictionMode aboveMode = LumaIntraPredictionMode.VERTICAL;
        LumaIntraPredictionMode leftMode = LumaIntraPredictionMode.HORIZONTAL;
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableKeyFrameYModeCdf(aboveMode.contextIndex(), leftMode.contextIndex()),
                        12
                )
        );
        UvIntraPredictionMode expectedUvMode = UvIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(
                        oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()),
                        13
                )
        );

        assertTrue(reader.readIntraBlockFlag(1));
        assertEquals(expectedYMode, reader.readKeyFrameYMode(aboveMode, leftMode));
        assertEquals(expectedUvMode, reader.readUvMode(expectedYMode, true));
        assertArrayEquals(
                oracleCdf.mutableKeyFrameYModeCdf(aboveMode.contextIndex(), leftMode.contextIndex()),
                tileContext.cdfContext().mutableKeyFrameYModeCdf(aboveMode.contextIndex(), leftMode.contextIndex())
        );
        assertArrayEquals(
                oracleCdf.mutableUvModeCdf(true, expectedYMode.symbolIndex()),
                tileContext.cdfContext().mutableUvModeCdf(true, expectedYMode.symbolIndex())
        );
    }

    /// Verifies that `intrabc` and inter-frame Y-mode symbols use the expected tile-local CDF tables.
    @Test
    void readsIntrabcAndInterFrameYMode() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, true, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedUseIntrabc = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntrabcCdf());
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableYModeCdf(3), 12)
        );

        assertEquals(expectedUseIntrabc, reader.readUseIntrabcFlag());
        assertArrayEquals(oracleCdf.mutableIntrabcCdf(), tileContext.cdfContext().mutableIntrabcCdf());
        assertEquals(expectedYMode, reader.readYMode(3));
        assertArrayEquals(oracleCdf.mutableYModeCdf(3), tileContext.cdfContext().mutableYModeCdf(3));
    }

    /// Verifies that `intrabc` decoding is skipped when the active frame does not allow it.
    @Test
    void disallowedIntrabcReturnsFalseWithoutConsumingBits() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        LumaIntraPredictionMode expectedYMode = LumaIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableYModeCdf(2), 12)
        );

        assertFalse(reader.readUseIntrabcFlag());
        assertEquals(expectedYMode, reader.readYMode(2));
        assertArrayEquals(new int[]{2237, 0}, tileContext.cdfContext().mutableIntrabcCdf());
    }

    /// Verifies that directional angle deltas use the expected tile-local CDF tables.
    @Test
    void readsDirectionalAngleDeltas() {
        byte[] payload = new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        int expectedYAngle = oracleDecoder.decodeSymbolAdapt(
                oracleCdf.mutableAngleDeltaCdf(LumaIntraPredictionMode.VERTICAL.angleDeltaContextIndex()),
                6
        ) - 3;
        int expectedUvAngle = oracleDecoder.decodeSymbolAdapt(
                oracleCdf.mutableAngleDeltaCdf(UvIntraPredictionMode.VERTICAL_LEFT.angleDeltaContextIndex()),
                6
        ) - 3;

        assertEquals(expectedYAngle, reader.readYAngleDelta(LumaIntraPredictionMode.VERTICAL));
        assertEquals(expectedUvAngle, reader.readUvAngleDelta(UvIntraPredictionMode.VERTICAL_LEFT));
        assertArrayEquals(
                oracleCdf.mutableAngleDeltaCdf(LumaIntraPredictionMode.VERTICAL.angleDeltaContextIndex()),
                tileContext.cdfContext().mutableAngleDeltaCdf(LumaIntraPredictionMode.VERTICAL.angleDeltaContextIndex())
        );
        assertArrayEquals(
                oracleCdf.mutableAngleDeltaCdf(UvIntraPredictionMode.VERTICAL_LEFT.angleDeltaContextIndex()),
                tileContext.cdfContext().mutableAngleDeltaCdf(UvIntraPredictionMode.VERTICAL_LEFT.angleDeltaContextIndex())
        );
    }

    /// Verifies that CFL alpha decoding uses the expected tile-local sign and alpha CDF tables.
    @Test
    void readsCflAlpha() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        int signSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCflSignCdf(), 7) + 1;
        int signU = signSymbol / 3;
        int signV = signSymbol - signU * 3;
        int expectedAlphaU = signU == 0
                ? 0
                : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signU == 2 ? 3 : 0) + signV, signU == 2);
        int expectedAlphaV = signV == 0
                ? 0
                : decodeSignedCflAlpha(oracleDecoder, oracleCdf, (signV == 2 ? 3 : 0) + signU, signV == 2);

        TileSyntaxReader.CflAlpha cflAlpha = reader.readCflAlpha();

        assertEquals(expectedAlphaU, cflAlpha.alphaU());
        assertEquals(expectedAlphaV, cflAlpha.alphaV());
        assertArrayEquals(oracleCdf.mutableCflSignCdf(), tileContext.cdfContext().mutableCflSignCdf());
    }

    /// Verifies that filter-intra syntax uses the expected tile-local CDF tables.
    @Test
    void readsFilterIntraSyntax() {
        byte[] payload = findPayloadForFilterIntra();
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedUseFilterIntra = oracleDecoder.decodeBooleanAdapt(
                oracleCdf.mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex())
        );
        assertTrue(expectedUseFilterIntra);
        FilterIntraMode expectedMode = FilterIntraMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableFilterIntraCdf(), 4)
        );

        assertTrue(reader.readUseFilterIntra(BlockSize.SIZE_8X8));
        assertEquals(expectedMode, reader.readFilterIntraMode());
        assertArrayEquals(
                oracleCdf.mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex()),
                tileContext.cdfContext().mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex())
        );
        assertArrayEquals(oracleCdf.mutableFilterIntraCdf(), tileContext.cdfContext().mutableFilterIntraCdf());
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

    /// Finds a small payload whose first `use_filter_intra` decision decodes to `true`.
    ///
    /// @return a small payload whose first `use_filter_intra` decision decodes to `true`
    private static byte[] findPayloadForFilterIntra() {
        for (int value = 0; value < 256; value++) {
            byte[] payload = new byte[]{(byte) value, 0x00, 0x00, 0x00, 0x00};
            CdfContext oracleCdf = CdfContext.createDefault();
            MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
            if (oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableUseFilterIntraCdf(BlockSize.SIZE_8X8.cdfIndex()))) {
                return payload;
            }
        }
        throw new IllegalStateException("No deterministic payload produced use_filter_intra=true");
    }

    /// Creates a synthetic tile-local decode context backed by one collected tile payload.
    ///
    /// @param frameType the synthetic frame type
    /// @param allowIntrabc whether the synthetic frame allows `intrabc`
    /// @param payload the collected tile entropy payload
    /// @return a synthetic tile-local decode context backed by one collected tile payload
    private static TileDecodeContext createTileContext(FrameType frameType, boolean allowIntrabc, byte[] payload) {
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
