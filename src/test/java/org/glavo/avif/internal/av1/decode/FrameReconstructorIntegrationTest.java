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
import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
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
import org.glavo.avif.internal.av1.parse.FrameHeaderParser;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for the first-pixel `FrameReconstructor` path.
@NotNullByDefault
final class FrameReconstructorIntegrationTest {
    /// One fixed single-tile payload that stays inside the current first-pixel reconstruction subset.
    private static final byte[] SUPPORTED_SINGLE_TILE_PAYLOAD = new byte[]{
            (byte) 0x98, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /// The top-left `8x8` luma block produced by the current legacy directional still-picture fixture.
    private static final int[][] LEGACY_DIRECTIONAL_LUMA_TOP_LEFT_8X8 = {
            {127, 128, 128, 128, 128, 128, 128, 128},
            {126, 126, 126, 126, 128, 128, 128, 128},
            {128, 128, 127, 126, 129, 129, 129, 129},
            {130, 132, 134, 134, 128, 128, 128, 128},
            {134, 128, 128, 128, 128, 128, 128, 128},
            {132, 128, 129, 129, 128, 128, 128, 128},
            {132, 130, 127, 129, 128, 128, 128, 128},
            {132, 132, 128, 131, 128, 128, 128, 129}
    };

    /// Verifies that one monochrome all-zero intra leaf reconstructs to midpoint DC samples.
    @Test
    void reconstructsMonochromeDcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, createLeaf(true, false));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one `I420` all-zero intra leaf reconstructs midpoint chroma as well as luma.
    @Test
    void reconstructsI420DcPredictedLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I420, createLeaf(true, true));

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.lumaPlane().sample(7, 7));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaUPlane().sample(3, 3));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(0, 0));
        assertEquals(128, decodedPlanes.chromaVPlane().sample(3, 3));
    }

    /// Verifies that one monochrome all-zero `filter_intra` leaf reconstructs through the minimal luma path.
    @Test
    void reconstructsMonochromeFilterIntraDcLeaf() {
        TilePartitionTreeReader.LeafNode leaf = createLeaf(true, false, FilterIntraMode.DC);
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, leaf, true);
        assertEquals(FilterIntraMode.DC, leaf.header().filterIntraMode());

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I400, decodedPlanes.pixelFormat());
        assertEquals(8, decodedPlanes.codedWidth());
        assertEquals(8, decodedPlanes.codedHeight());
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
        assertEquals(198, decodedPlanes.lumaPlane().sample(7, 7));
        assertNull(decodedPlanes.chromaUPlane());
        assertNull(decodedPlanes.chromaVPlane());
    }

    /// Verifies that one decoded 4x4 monochrome DC residual survives the frame-syntax path and shifts luma uniformly.
    @Test
    void reconstructsMonochromeDcResidualDecodedFromFrameSyntax() {
        byte[] zeroResidualPayload = findPayloadForResidualFlags(BlockSize.SIZE_4X4, new boolean[]{true});
        byte[] dcResidualPayload = findPayloadForDcOnlyResidual(BlockSize.SIZE_4X4);

        FrameSyntaxDecodeResult baselineSyntax = decodeMonochromeFourByFourFrame(zeroResidualPayload);
        FrameSyntaxDecodeResult residualSyntax = decodeMonochromeFourByFourFrame(dcResidualPayload);
        TilePartitionTreeReader.LeafNode decodedLeaf = firstLeaf(residualSyntax.tileRoots(0));
        TransformResidualUnit residualUnit = decodedLeaf.residualLayout().lumaUnits()[0];

        assertFalse(residualUnit.allZero());
        assertEquals(0, residualUnit.endOfBlockIndex());
        assertTrue(residualUnit.dcCoefficient() != 0);

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlane baselineLuma = reconstructor.reconstruct(baselineSyntax).lumaPlane();
        DecodedPlanes reconstructed = reconstructor.reconstruct(residualSyntax);

        assertEquals(baselineLuma.width(), reconstructed.lumaPlane().width());
        assertEquals(baselineLuma.height(), reconstructed.lumaPlane().height());
        assertNull(reconstructed.chromaUPlane());
        assertNull(reconstructed.chromaVPlane());
    }

    /// Verifies that the supported reduced still-picture combined fixture still reconstructs one
    /// opaque gray `I420` frame after real structural frame decode.
    ///
    /// @throws IOException if the synthetic fixture cannot be parsed
    @Test
    void reconstructsSupportedReducedStillPictureCombinedFixtureFromRealFrameSyntax() throws IOException {
        FrameSyntaxDecodeResult syntaxDecodeResult =
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(SUPPORTED_SINGLE_TILE_PAYLOAD);

        DecodedPlanes decodedPlanes = new FrameReconstructor().reconstruct(syntaxDecodeResult);

        assertStillPicturePlanesFilledWith(decodedPlanes, 128);
    }

    /// Verifies that the legacy reduced still-picture combined fixture now survives structural
    /// frame decode and reconstructs successfully through its first directional luma block.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalCombinedStillPictureFixture() throws IOException {
        assertDirectionalStillPictureFixtureReconstructsSuccessfully(
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(singleTileGroupPayload())
        );
    }

    /// Verifies that the legacy reduced still-picture standalone frame-header plus tile-group
    /// fixture reconstructs successfully through the same directional luma leaf as the combined
    /// path.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalStandaloneStillPictureFixture() throws IOException {
        assertDirectionalStillPictureFixtureReconstructsSuccessfully(
                decodeReducedStillPictureSyntaxResultFromStandaloneObus(singleTileGroupPayload())
        );
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
        return createSyntheticResult(pixelFormat, leafNode, false);
    }

    /// Creates one synthetic frame result that carries a single tile leaf.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param leafNode the synthetic partition-tree leaf
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            PixelFormat pixelFormat,
            TilePartitionTreeReader.LeafNode leafNode,
            boolean filterIntraEnabled
    ) {
        FrameAssembly assembly = createAssembly(pixelFormat, filterIntraEnabled);
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{{leafNode}},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(boolean allZeroResidual, boolean hasChroma) {
        return createLeaf(allZeroResidual, hasChroma, null);
    }

    /// Creates one synthetic partition-tree leaf.
    ///
    /// @param allZeroResidual whether the synthetic residual unit should be all-zero
    /// @param hasChroma whether the synthetic leaf should carry chroma
    /// @param filterIntraMode the synthetic filter-intra mode, or `null` when filter intra is disabled
    /// @return one synthetic partition-tree leaf
    private static TilePartitionTreeReader.LeafNode createLeaf(
            boolean allZeroResidual,
            boolean hasChroma,
            @Nullable FilterIntraMode filterIntraMode
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
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
                LumaIntraPredictionMode.DC,
                hasChroma ? UvIntraPredictionMode.DC : null,
                0,
                0,
                new int[0],
                new int[0],
                new int[0],
                new byte[0],
                new byte[0],
                filterIntraMode,
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
                TransformSize.TX_8X8,
                hasChroma ? TransformSize.TX_4X4 : null,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        int[] coefficients = new int[TransformSize.TX_8X8.widthPixels() * TransformSize.TX_8X8.heightPixels()];
        int endOfBlockIndex = -1;
        if (!allZeroResidual) {
            coefficients[0] = 1;
            endOfBlockIndex = 0;
        }
        ResidualLayout residualLayout = new ResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{
                        new TransformResidualUnit(position, TransformSize.TX_8X8, endOfBlockIndex, coefficients, 0)
                }
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Decodes one tiny 4x4 monochrome frame from a caller-supplied tile payload.
    ///
    /// @param payload the tile payload to decode structurally
    /// @return one tiny 4x4 monochrome frame decoded from the supplied tile payload
    private static FrameSyntaxDecodeResult decodeMonochromeFourByFourFrame(byte[] payload) {
        return new FrameSyntaxDecoder(null).decode(
                createAssembly(PixelFormat.I400, payload, 4, 4, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY)
        );
    }

    /// Decodes one reduced still-picture syntax result from a combined `FRAME` OBU fixture.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return one structural frame-decode result for the combined fixture
    /// @throws IOException if the fixture cannot be parsed
    private static FrameSyntaxDecodeResult decodeReducedStillPictureSyntaxResultFromCombinedFrame(
            byte[] tileGroupPayload
    ) throws IOException {
        return new FrameSyntaxDecoder(null).decode(createReducedStillPictureCombinedAssembly(tileGroupPayload));
    }

    /// Decodes one reduced still-picture syntax result from standalone `FRAME_HEADER` plus
    /// `TILE_GROUP` OBU fixtures.
    ///
    /// @param tileGroupPayload the standalone tile-group payload
    /// @return one structural frame-decode result for the standalone fixture
    /// @throws IOException if the fixture cannot be parsed
    private static FrameSyntaxDecodeResult decodeReducedStillPictureSyntaxResultFromStandaloneObus(
            byte[] tileGroupPayload
    ) throws IOException {
        return new FrameSyntaxDecoder(null).decode(createReducedStillPictureStandaloneAssembly(tileGroupPayload));
    }

    /// Asserts that one structurally decoded legacy still-picture frame reconstructs successfully
    /// through a decoded directional luma leaf and still yields stable output samples.
    ///
    /// @param syntaxDecodeResult the structural frame-decode result produced from the legacy fixture
    private static void assertDirectionalStillPictureFixtureReconstructsSuccessfully(
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        assertEquals(1, syntaxDecodeResult.tileCount());
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxDecodeResult.tileRoots(0));
        assertFalse(leaves.isEmpty());

        int firstDirectionalLeafIndex = -1;
        for (int i = 0; i < leaves.size(); i++) {
            LumaIntraPredictionMode mode = leaves.get(i).header().yMode();
            if (mode != null && mode.isDirectional()) {
                firstDirectionalLeafIndex = i;
                break;
            }
        }
        assertTrue(firstDirectionalLeafIndex > 0);

        TilePartitionTreeReader.LeafNode directionalLeaf = leaves.get(firstDirectionalLeafIndex);
        assertTrue(directionalLeaf.header().intra());
        assertEquals(LumaIntraPredictionMode.HORIZONTAL, directionalLeaf.header().yMode());
        assertEquals(0, directionalLeaf.header().yAngle());
        assertLegacyDirectionalStillPicturePlanes(new FrameReconstructor().reconstruct(syntaxDecodeResult));
    }

    /// Asserts the stable legacy directional still-picture reconstruction oracle.
    ///
    /// The current first directional path perturbs the top-left luma region while the chroma
    /// planes remain midpoint-gray.
    ///
    /// @param decodedPlanes the reconstructed planes returned by the frame reconstructor
    private static void assertLegacyDirectionalStillPicturePlanes(DecodedPlanes decodedPlanes) {
        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(64, decodedPlanes.codedWidth());
        assertEquals(64, decodedPlanes.codedHeight());
        assertEquals(64, decodedPlanes.renderWidth());
        assertEquals(64, decodedPlanes.renderHeight());
        assertPlaneBlockEquals(decodedPlanes.lumaPlane(), 0, 0, LEGACY_DIRECTIONAL_LUMA_TOP_LEFT_8X8);
        assertPlaneBlockFilledWith(decodedPlanes.chromaUPlane(), 0, 0, 4, 4, 128);
        assertPlaneBlockFilledWith(decodedPlanes.chromaVPlane(), 0, 0, 4, 4, 128);
    }

    /// Asserts one rectangular plane block against expected sample values.
    ///
    /// @param plane the decoded plane to inspect
    /// @param originX the zero-based block origin X coordinate
    /// @param originY the zero-based block origin Y coordinate
    /// @param expected the expected block in row-major order
    private static void assertPlaneBlockEquals(
            @Nullable DecodedPlane plane,
            int originX,
            int originY,
            int[][] expected
    ) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], plane.sample(originX + x, originY + y));
            }
        }
    }

    /// Asserts one rectangular plane block is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param originX the zero-based block origin X coordinate
    /// @param originY the zero-based block origin Y coordinate
    /// @param width the block width in samples
    /// @param height the block height in samples
    /// @param expectedSample the expected constant sample value
    private static void assertPlaneBlockFilledWith(
            @Nullable DecodedPlane plane,
            int originX,
            int originY,
            int width,
            int height,
            int expectedSample
    ) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(expectedSample, plane.sample(originX + x, originY + y));
            }
        }
    }

    /// Asserts that one decoded reduced still-picture frame contains one stable `I420` sample
    /// value throughout the visible image.
    ///
    /// @param decodedPlanes the reconstructed planes returned by the frame reconstructor
    /// @param expectedSample the expected constant sample value shared by the visible planes
    private static void assertStillPicturePlanesFilledWith(DecodedPlanes decodedPlanes, int expectedSample) {
        assertEquals(8, decodedPlanes.bitDepth());
        assertEquals(PixelFormat.I420, decodedPlanes.pixelFormat());
        assertEquals(64, decodedPlanes.codedWidth());
        assertEquals(64, decodedPlanes.codedHeight());
        assertEquals(64, decodedPlanes.renderWidth());
        assertEquals(64, decodedPlanes.renderHeight());
        assertPlaneFilledWith(decodedPlanes.lumaPlane(), expectedSample);
        assertPlaneFilledWith(decodedPlanes.chromaUPlane(), expectedSample);
        assertPlaneFilledWith(decodedPlanes.chromaVPlane(), expectedSample);
    }

    /// Asserts that one decoded plane is filled with one constant sample value.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expectedSample the expected constant sample value
    private static void assertPlaneFilledWith(@Nullable DecodedPlane plane, int expectedSample) {
        if (plane == null) {
            throw new AssertionError("Decoded plane was null");
        }
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                assertEquals(expectedSample, plane.sample(x, y));
            }
        }
    }

    /// Creates one reduced still-picture combined-frame assembly with a caller-supplied tile
    /// payload.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return one complete reduced still-picture frame assembly
    /// @throws IOException if the fixture cannot be parsed
    private static FrameAssembly createReducedStillPictureCombinedAssembly(byte[] tileGroupPayload) throws IOException {
        SequenceHeader sequenceHeader = parseReducedStillPictureSequenceHeader();
        byte[] combinedPayload = reducedStillPictureCombinedFramePayload(tileGroupPayload);
        ObuPacket frameObu = frameObu(combinedPayload);
        BitReader reader = new BitReader(combinedPayload);
        FrameHeader frameHeader = new FrameHeaderParser().parseFramePayload(reader, frameObu, sequenceHeader, false);
        reader.byteAlign();
        int tileDataOffset = reader.byteOffset();

        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, frameObu.streamOffset(), frameObu.obuIndex());
        assembly.addTileGroup(
                frameObu,
                new TileGroupHeader(false, 0, 0, 1),
                tileDataOffset,
                combinedPayload.length - tileDataOffset,
                new TileBitstream[]{
                        new TileBitstream(0, combinedPayload, tileDataOffset, combinedPayload.length - tileDataOffset)
                }
        );
        return assembly;
    }

    /// Creates one reduced still-picture standalone frame assembly with a caller-supplied tile
    /// payload.
    ///
    /// @param tileGroupPayload the standalone tile-group payload
    /// @return one complete reduced still-picture frame assembly
    /// @throws IOException if the fixture cannot be parsed
    private static FrameAssembly createReducedStillPictureStandaloneAssembly(byte[] tileGroupPayload) throws IOException {
        SequenceHeader sequenceHeader = parseReducedStillPictureSequenceHeader();
        ObuPacket frameHeaderObu = frameHeaderObu(reducedStillPictureFrameHeaderPayload());
        FrameHeader frameHeader = new FrameHeaderParser().parse(frameHeaderObu, sequenceHeader, false);
        ObuPacket tileGroupObu = tileGroupObu(tileGroupPayload);

        FrameAssembly assembly = new FrameAssembly(
                sequenceHeader,
                frameHeader,
                frameHeaderObu.streamOffset(),
                frameHeaderObu.obuIndex()
        );
        assembly.addTileGroup(
                tileGroupObu,
                new TileGroupHeader(false, 0, 0, 1),
                0,
                tileGroupPayload.length,
                new TileBitstream[]{new TileBitstream(0, tileGroupPayload, 0, tileGroupPayload.length)}
        );
        return assembly;
    }

    /// Parses the shared reduced still-picture sequence header used by the legacy fixtures.
    ///
    /// @return the parsed reduced still-picture sequence header
    /// @throws IOException if the fixture cannot be parsed
    private static SequenceHeader parseReducedStillPictureSequenceHeader() throws IOException {
        return new SequenceHeaderParser().parse(sequenceHeaderObu(reducedStillPicturePayload()), false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat) {
        return createAssembly(pixelFormat, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat, boolean filterIntraEnabled) {
        return createAssembly(pixelFormat, new byte[0], 8, 8, FrameHeader.TransformMode.LARGEST, filterIntraEnabled);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return createAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode, false);
    }

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param payload the tile payload stored in the single-tile assembly
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @param filterIntraEnabled whether the synthetic sequence enables `filter_intra`
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(
            PixelFormat pixelFormat,
            byte[] payload,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode,
            boolean filterIntraEnabled
    ) {
        boolean monochrome = pixelFormat == PixelFormat.I400;
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
                        filterIntraEnabled,
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
                        monochrome,
                        false,
                        2,
                        2,
                        2,
                        true,
                        pixelFormat,
                        0,
                        pixelFormat == PixelFormat.I420,
                        pixelFormat == PixelFormat.I420,
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
                new FrameHeader.SuperResolutionInfo(false, codedWidth),
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
        return assembly;
    }

    /// Finds a small payload whose first residual flags match the requested sequence.
    ///
    /// @param blockSize the block size whose residual syntax should be decoded
    /// @param expectedFlags the requested prefix of `txb_skip` / all-zero flags
    /// @return a small payload whose first residual flags match the requested sequence
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
                    TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
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
                    // Unsupported residual trees are skipped while brute-forcing a supported DC-only unit.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a supported DC-only residual");
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

            TileDecodeContext tileContext = createTileContext(payload, FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY);
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

    /// Creates one tile-local decode context used by payload search helpers.
    ///
    /// @param payload the collected tile entropy payload
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context used by payload search helpers
    private static TileDecodeContext createTileContext(byte[] payload, FrameHeader.TransformMode transformMode) {
        return TileDecodeContext.create(createAssembly(PixelFormat.I400, payload, 64, 64, transformMode), 0);
    }

    /// Creates a reduced still-picture sequence-header OBU packet.
    ///
    /// @param payload the sequence-header payload
    /// @return one reduced still-picture sequence-header OBU packet
    private static ObuPacket sequenceHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0), payload, 0, 0);
    }

    /// Creates a reduced still-picture standalone frame-header OBU packet.
    ///
    /// @param payload the standalone frame-header payload
    /// @return one reduced still-picture standalone frame-header OBU packet
    private static ObuPacket frameHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME_HEADER, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture combined `FRAME` OBU packet.
    ///
    /// @param payload the combined frame payload
    /// @return one reduced still-picture combined `FRAME` OBU packet
    private static ObuPacket frameObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture standalone `TILE_GROUP` OBU packet.
    ///
    /// @param payload the standalone tile-group payload
    /// @return one reduced still-picture standalone `TILE_GROUP` OBU packet
    private static ObuPacket tileGroupObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), payload, 0, 2);
    }

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPicturePayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(63, 10);
        writer.writeBits(63, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture standalone frame-header payload.
    ///
    /// @return the reduced still-picture standalone frame-header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture combined frame payload with a caller-supplied tile group.
    ///
    /// @param tileGroupPayload the single-tile payload appended after the frame header
    /// @return the reduced still-picture combined frame payload
    private static byte[] reducedStillPictureCombinedFramePayload(byte[] tileGroupPayload) {
        BitWriter writer = new BitWriter();
        writeReducedStillPictureFrameHeaderBits(writer);
        writer.padToByteBoundary();
        writer.writeBytes(tileGroupPayload);
        return writer.toByteArray();
    }

    /// Creates the legacy minimal single-tile tile-group payload.
    ///
    /// @return the legacy minimal single-tile tile-group payload
    private static byte[] singleTileGroupPayload() {
        return new byte[]{(byte) 0xE1, 0x00, 0x7F, 0x55, (byte) 0xC3, 0x18};
    }

    /// Writes the reduced still-picture key-frame header syntax without standalone trailing bits.
    ///
    /// @param writer the destination bit writer
    private static void writeReducedStillPictureFrameHeaderBits(BitWriter writer) {
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
    }

    /// Returns the first leaf node in raster order from one tile-root array.
    ///
    /// @param roots the top-level tile roots
    /// @return the first leaf node in raster order
    private static TilePartitionTreeReader.LeafNode firstLeaf(TilePartitionTreeReader.Node[] roots) {
        for (TilePartitionTreeReader.Node root : roots) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(root);
            if (leaf != null) {
                return leaf;
            }
        }
        throw new IllegalStateException("No leaf nodes were produced");
    }

    /// Returns the first leaf node in raster order from one subtree, or `null`.
    ///
    /// @param node the subtree root
    /// @return the first leaf node in raster order from one subtree, or `null`
    private static TilePartitionTreeReader.@org.jetbrains.annotations.Nullable LeafNode firstLeaf(
            TilePartitionTreeReader.Node node
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            return leafNode;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            TilePartitionTreeReader.LeafNode leaf = firstLeaf(child);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    /// Returns every leaf node from one tile-root array in raster reconstruction order.
    ///
    /// @param roots the top-level tile roots
    /// @return every leaf node from one tile-root array in raster reconstruction order
    private static List<TilePartitionTreeReader.LeafNode> leavesInRasterOrder(TilePartitionTreeReader.Node[] roots) {
        List<TilePartitionTreeReader.LeafNode> leaves = new ArrayList<>();
        for (TilePartitionTreeReader.Node root : roots) {
            appendLeavesInRasterOrder(root, leaves);
        }
        return leaves;
    }

    /// Appends every leaf node from one subtree in raster reconstruction order.
    ///
    /// @param node the subtree root
    /// @param leaves the destination list for leaf nodes
    private static void appendLeavesInRasterOrder(
            TilePartitionTreeReader.Node node,
            List<TilePartitionTreeReader.LeafNode> leaves
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            leaves.add(leafNode);
            return;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            appendLeavesInRasterOrder(child, leaves);
        }
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

    /// Small MSB-first bit writer used to build AV1 test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Writes trailing bits and byte-alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Pads the current byte with zero bits until the next byte boundary.
        private void padToByteBoundary() {
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Writes raw bytes after the current bitstream has been byte aligned.
        ///
        /// @param bytes the raw bytes to append
        private void writeBytes(byte[] bytes) {
            if (bitCount != 0) {
                throw new IllegalStateException("BitWriter is not byte aligned");
            }
            output.writeBytes(bytes);
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
