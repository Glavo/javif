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
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.glavo.avif.testutil.HexFixtureResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `TileSyntaxReader`.
@NotNullByDefault
final class TileSyntaxReaderTest {
    /// Classpath resource containing named entropy payload fixtures for block-header syntax tests.
    private static final String TILE_BLOCK_HEADER_FIXTURE_RESOURCE =
            "av1/fixtures/generated/tile-block-header-fixtures.txt";

    /// Classpath resource that stores precomputed tile-syntax payload fixtures.
    private static final String TILE_SYNTAX_FIXTURES_RESOURCE = "av1/fixtures/generated/tile-syntax-fixtures.txt";

    /// Fixed payload whose first `use_filter_intra` decision decodes to `true`.
    private static final byte[] FILTER_INTRA_PAYLOAD = readTileSyntaxFixture("filter-intra");

    /// Fixed payload whose first palette decisions decode to luma and chroma palette syntax.
    private static final byte[] PALETTE_PAYLOAD = readTileSyntaxFixture("palette");

    /// Fixed payload whose first motion-vector residual changes both motion-vector components.
    private static final byte[] MOTION_VECTOR_RESIDUAL_PAYLOAD = readTileSyntaxFixture("motion-vector-residual");

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

    /// Verifies that inter-frame skip-mode symbols use the expected tile-local CDF tables.
    @Test
    void readsInterFrameSkipModeSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkipMode = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipModeCdf(1));

        assertEquals(expectedSkipMode, reader.readSkipModeFlag(1));
        assertArrayEquals(oracleCdf.mutableSkipModeCdf(1), tileContext.cdfContext().mutableSkipModeCdf(1));
    }

    /// Verifies that inter-frame reference syntax uses the expected tile-local CDF tables.
    @Test
    void readsInterFrameReferenceSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedCompound = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundReferenceCdf(2));
        boolean expectedCompoundDirection = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundDirectionCdf(1));
        boolean expectedSingleReference = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSingleReferenceCdf(0, 2));
        boolean expectedCompoundForwardReference = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundForwardReferenceCdf(0, 2));
        boolean expectedCompoundBackwardReference = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableCompoundBackwardReferenceCdf(1, 0));
        boolean expectedCompoundUnidirectionalReference = oracleDecoder.decodeBooleanAdapt(
                oracleCdf.mutableCompoundUnidirectionalReferenceCdf(2, 1)
        );

        assertEquals(expectedCompound, reader.readCompoundReferenceFlag(2));
        assertArrayEquals(oracleCdf.mutableCompoundReferenceCdf(2), tileContext.cdfContext().mutableCompoundReferenceCdf(2));
        assertEquals(expectedCompoundDirection, reader.readCompoundDirectionFlag(1));
        assertArrayEquals(oracleCdf.mutableCompoundDirectionCdf(1), tileContext.cdfContext().mutableCompoundDirectionCdf(1));
        assertEquals(expectedSingleReference, reader.readSingleReferenceFlag(0, 2));
        assertArrayEquals(oracleCdf.mutableSingleReferenceCdf(0, 2), tileContext.cdfContext().mutableSingleReferenceCdf(0, 2));
        assertEquals(expectedCompoundForwardReference, reader.readCompoundForwardReferenceFlag(0, 2));
        assertArrayEquals(
                oracleCdf.mutableCompoundForwardReferenceCdf(0, 2),
                tileContext.cdfContext().mutableCompoundForwardReferenceCdf(0, 2)
        );
        assertEquals(expectedCompoundBackwardReference, reader.readCompoundBackwardReferenceFlag(1, 0));
        assertArrayEquals(
                oracleCdf.mutableCompoundBackwardReferenceCdf(1, 0),
                tileContext.cdfContext().mutableCompoundBackwardReferenceCdf(1, 0)
        );
        assertEquals(expectedCompoundUnidirectionalReference, reader.readCompoundUnidirectionalReferenceFlag(2, 1));
        assertArrayEquals(
                oracleCdf.mutableCompoundUnidirectionalReferenceCdf(2, 1),
                tileContext.cdfContext().mutableCompoundUnidirectionalReferenceCdf(2, 1)
        );
    }

    /// Verifies that inter prediction-mode syntax uses the expected tile-local CDF tables.
    @Test
    void readsInterPredictionModeSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedNewMv = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSingleInterNewMvCdf(4));
        boolean expectedGlobalMv = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSingleInterGlobalMvCdf(1));
        boolean expectedReferenceMv = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSingleInterReferenceMvCdf(3));
        boolean expectedDrlBit = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableDrlCdf(2));
        CompoundInterPredictionMode expectedCompoundInterMode = CompoundInterPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableCompoundInterModeCdf(5), 7)
        );
        SingleInterPredictionMode expectedSingleInterMode = expectedNewMv
                ? (!expectedGlobalMv ? SingleInterPredictionMode.GLOBALMV
                : expectedReferenceMv ? SingleInterPredictionMode.NEARMV : SingleInterPredictionMode.NEARESTMV)
                : SingleInterPredictionMode.NEWMV;

        assertEquals(expectedNewMv, reader.readSingleInterNewMvFlag(4));
        assertArrayEquals(oracleCdf.mutableSingleInterNewMvCdf(4), tileContext.cdfContext().mutableSingleInterNewMvCdf(4));
        assertEquals(expectedGlobalMv, reader.readSingleInterGlobalMvFlag(1));
        assertArrayEquals(oracleCdf.mutableSingleInterGlobalMvCdf(1), tileContext.cdfContext().mutableSingleInterGlobalMvCdf(1));
        assertEquals(expectedReferenceMv, reader.readSingleInterReferenceMvFlag(3));
        assertArrayEquals(oracleCdf.mutableSingleInterReferenceMvCdf(3), tileContext.cdfContext().mutableSingleInterReferenceMvCdf(3));
        assertEquals(expectedDrlBit, reader.readDrlBit(2));
        assertArrayEquals(oracleCdf.mutableDrlCdf(2), tileContext.cdfContext().mutableDrlCdf(2));
        assertEquals(expectedCompoundInterMode, reader.readCompoundInterMode(5));
        assertArrayEquals(oracleCdf.mutableCompoundInterModeCdf(5), tileContext.cdfContext().mutableCompoundInterModeCdf(5));

        TileDecodeContext singleModeTileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader singleModeReader = new TileSyntaxReader(singleModeTileContext);
        assertEquals(expectedSingleInterMode, singleModeReader.readSingleInterMode(4, 1, 3, false, false));
    }

    /// Verifies that inter-intra syntax uses the expected tile-local CDF tables.
    @Test
    void readsInterIntraSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedUseInterIntra = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableInterIntraCdf(2));
        InterIntraPredictionMode expectedMode = InterIntraPredictionMode.fromSymbolIndex(
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableInterIntraModeCdf(2), 3)
        );
        boolean expectedUseWedge = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableInterIntraWedgeCdf(4));
        int expectedWedgeIndex = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableWedgeIndexCdf(4), 15);

        assertEquals(expectedUseInterIntra, reader.readUseInterIntra(2));
        assertArrayEquals(oracleCdf.mutableInterIntraCdf(2), tileContext.cdfContext().mutableInterIntraCdf(2));
        assertEquals(expectedMode, reader.readInterIntraMode(2));
        assertArrayEquals(oracleCdf.mutableInterIntraModeCdf(2), tileContext.cdfContext().mutableInterIntraModeCdf(2));
        assertEquals(expectedUseWedge, reader.readUseInterIntraWedge(4));
        assertArrayEquals(oracleCdf.mutableInterIntraWedgeCdf(4), tileContext.cdfContext().mutableInterIntraWedgeCdf(4));
        assertEquals(expectedWedgeIndex, reader.readWedgeIndex(4));
        assertArrayEquals(oracleCdf.mutableWedgeIndexCdf(4), tileContext.cdfContext().mutableWedgeIndexCdf(4));
    }

    /// Verifies that switchable interpolation-filter syntax uses the expected tile-local CDF tables.
    @Test
    void readsInterpolationFilterSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        FrameHeader.InterpolationFilter expectedHorizontal = switch (
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableInterpolationFilterCdf(0, 3), 2)
        ) {
            case 0 -> FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
            case 1 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH;
            case 2 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
            default -> throw new IllegalStateException("Unexpected interpolation-filter symbol");
        };
        FrameHeader.InterpolationFilter expectedVertical = switch (
                oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableInterpolationFilterCdf(1, 6), 2)
        ) {
            case 0 -> FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
            case 1 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH;
            case 2 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
            default -> throw new IllegalStateException("Unexpected interpolation-filter symbol");
        };

        assertEquals(expectedHorizontal, reader.readInterpolationFilter(0, 3));
        assertArrayEquals(oracleCdf.mutableInterpolationFilterCdf(0, 3), tileContext.cdfContext().mutableInterpolationFilterCdf(0, 3));
        assertEquals(expectedVertical, reader.readInterpolationFilter(1, 6));
        assertArrayEquals(oracleCdf.mutableInterpolationFilterCdf(1, 6), tileContext.cdfContext().mutableInterpolationFilterCdf(1, 6));
    }

    /// Verifies that motion-vector residual syntax uses the expected tile-local CDF tables.
    @Test
    void readsMotionVectorResidualSyntax() {
        byte[] payload = MOTION_VECTOR_RESIDUAL_PAYLOAD;
        TileDecodeContext tileContext = createTileContext(FrameType.INTER, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);
        MotionVector predictor = new MotionVector(8, -4);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        MotionVector expectedMotionVector = decodeMotionVectorResidual(
                oracleDecoder,
                oracleCdf,
                predictor,
                tileContext.frameHeader().allowHighPrecisionMotionVectors(),
                tileContext.frameHeader().forceIntegerMotionVectors()
        );

        assertEquals(expectedMotionVector, reader.readMotionVectorResidual(predictor));
        assertArrayEquals(oracleCdf.mutableMotionVectorJointCdf(), tileContext.cdfContext().mutableMotionVectorJointCdf());
        assertArrayEquals(oracleCdf.mutableMotionVectorClassCdf(0), tileContext.cdfContext().mutableMotionVectorClassCdf(0));
        assertArrayEquals(oracleCdf.mutableMotionVectorClassCdf(1), tileContext.cdfContext().mutableMotionVectorClassCdf(1));
        assertArrayEquals(oracleCdf.mutableMotionVectorSignCdf(0), tileContext.cdfContext().mutableMotionVectorSignCdf(0));
        assertArrayEquals(oracleCdf.mutableMotionVectorSignCdf(1), tileContext.cdfContext().mutableMotionVectorSignCdf(1));
    }

    /// Verifies that `intrabc`-enabled key frames can consume one same-frame motion-vector
    /// residual with the current tile-local motion-vector syntax tables.
    @Test
    void readsIntrabcMotionVectorResidualSyntax() {
        byte[] payload = HexFixtureResources.readNamedBytes(TILE_BLOCK_HEADER_FIXTURE_RESOURCE, "intrabc");
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, true, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);
        MotionVector predictor = MotionVector.zero();

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSkip = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSkipCdf(0));
        boolean expectedUseIntrabc = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableIntrabcCdf());
        MotionVector expectedMotionVector = decodeMotionVectorResidual(
                oracleDecoder,
                oracleCdf,
                predictor,
                tileContext.frameHeader().allowHighPrecisionMotionVectors(),
                tileContext.frameHeader().forceIntegerMotionVectors()
        );

        assertEquals(expectedSkip, reader.readSkipFlag(0));
        assertEquals(expectedUseIntrabc, reader.readUseIntrabcFlag());
        assertEquals(expectedMotionVector, reader.readMotionVectorResidual(predictor));
        assertArrayEquals(oracleCdf.mutableSkipCdf(0), tileContext.cdfContext().mutableSkipCdf(0));
        assertArrayEquals(oracleCdf.mutableMotionVectorJointCdf(), tileContext.cdfContext().mutableMotionVectorJointCdf());
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
        byte[] payload = FILTER_INTRA_PAYLOAD;
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

    /// Verifies that segmentation prediction and segment-id symbols use the expected tile-local CDF tables.
    @Test
    void readsSegmentationSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedSegmentPrediction = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableSegmentPredictionCdf(1));
        int expectedSegmentId = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableSegmentIdCdf(2), 7);

        assertEquals(expectedSegmentPrediction, reader.readSegmentPredictionFlag(1));
        assertArrayEquals(
                oracleCdf.mutableSegmentPredictionCdf(1),
                tileContext.cdfContext().mutableSegmentPredictionCdf(1)
        );
        assertEquals(expectedSegmentId, reader.readSegmentId(2));
        assertArrayEquals(oracleCdf.mutableSegmentIdCdf(2), tileContext.cdfContext().mutableSegmentIdCdf(2));
    }

    /// Verifies that palette presence and size syntax use the expected tile-local CDF tables.
    @Test
    void readsPaletteSyntax() {
        byte[] payload = PALETTE_PAYLOAD;
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        boolean expectedUseLumaPalette = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableLumaPaletteCdf(0, 0));
        assertTrue(expectedUseLumaPalette);
        int expectedLumaPaletteSize = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutablePaletteSizeCdf(0, 0), 6) + 2;
        boolean expectedUseChromaPalette = oracleDecoder.decodeBooleanAdapt(oracleCdf.mutableChromaPaletteCdf(1));
        assertTrue(expectedUseChromaPalette);
        int expectedChromaPaletteSize = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutablePaletteSizeCdf(1, 0), 6) + 2;

        assertTrue(reader.readUseLumaPalette(0, 0));
        assertArrayEquals(oracleCdf.mutableLumaPaletteCdf(0, 0), tileContext.cdfContext().mutableLumaPaletteCdf(0, 0));
        assertEquals(expectedLumaPaletteSize, reader.readPaletteSize(0, 0));
        assertArrayEquals(oracleCdf.mutablePaletteSizeCdf(0, 0), tileContext.cdfContext().mutablePaletteSizeCdf(0, 0));
        assertTrue(reader.readUseChromaPalette(1));
        assertArrayEquals(oracleCdf.mutableChromaPaletteCdf(1), tileContext.cdfContext().mutableChromaPaletteCdf(1));
        assertEquals(expectedChromaPaletteSize, reader.readPaletteSize(1, 0));
        assertArrayEquals(oracleCdf.mutablePaletteSizeCdf(1, 0), tileContext.cdfContext().mutablePaletteSizeCdf(1, 0));
    }

    /// Verifies that palette color-map syntax uses the expected uniform and adaptive CDF paths.
    @Test
    void readsPaletteColorMapSyntax() {
        byte[] payload = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A};
        TileDecodeContext tileContext = createTileContext(FrameType.KEY, false, payload);
        TileSyntaxReader reader = new TileSyntaxReader(tileContext);

        CdfContext oracleCdf = CdfContext.createDefault();
        MsacDecoder oracleDecoder = new MsacDecoder(payload, 0, payload.length, false);
        int expectedInitialIndex = oracleDecoder.decodeUniform(5);
        int expectedColorMapSymbol = oracleDecoder.decodeSymbolAdapt(oracleCdf.mutableColorMapCdf(1, 3, 2), 4);

        assertEquals(expectedInitialIndex, reader.readPaletteInitialIndex(5));
        assertEquals(expectedColorMapSymbol, reader.readPaletteColorMapSymbol(1, 5, 2));
        assertArrayEquals(oracleCdf.mutableColorMapCdf(1, 3, 2), tileContext.cdfContext().mutableColorMapCdf(1, 3, 2));
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

    /// Reads one named precomputed tile-syntax fixture payload.
    ///
    /// @param fixtureName the logical fixture name in `TILE_SYNTAX_FIXTURES_RESOURCE`
    /// @return the decoded tile-syntax payload bytes
    private static byte[] readTileSyntaxFixture(String fixtureName) {
        return HexFixtureResources.readNamedBytes(TILE_SYNTAX_FIXTURES_RESOURCE, fixtureName);
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
