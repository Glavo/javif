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
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /// Verifies that one synthetic `I420` leaf with only a chroma-U DC residual changes only the U plane.
    @Test
    void reconstructsSyntheticI420LeafWithChromaUResidualOnly() {
        FrameSyntaxDecodeResult baselineSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, 0)
        );
        FrameSyntaxDecodeResult residualSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(64, 0)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes residual = reconstructor.reconstruct(residualSyntax);

        assertPlanesEqual(baseline.lumaPlane(), residual.lumaPlane());
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaUPlane()),
                requirePlane(residual.chromaUPlane()),
                1
        );
        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(residual.chromaVPlane()));
    }

    /// Verifies that one synthetic `I420` leaf with only a chroma-V DC residual changes only the V plane.
    @Test
    void reconstructsSyntheticI420LeafWithChromaVResidualOnly() {
        FrameSyntaxDecodeResult baselineSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, 0)
        );
        FrameSyntaxDecodeResult residualSyntax = createSyntheticResult(
                PixelFormat.I420,
                createI420LeafWithChromaDcResiduals(0, -64)
        );

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes residual = reconstructor.reconstruct(residualSyntax);

        assertPlanesEqual(baseline.lumaPlane(), residual.lumaPlane());
        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(residual.chromaUPlane()));
        assertPlaneDiffersFromBaselineByUniformSignedOffset(
                requirePlane(baseline.chromaVPlane()),
                requirePlane(residual.chromaVPlane()),
                -1
        );
    }

    /// Verifies that one bitstream-derived clipped `I420` chroma residual survives the integration
    /// path and updates only the visible chroma footprint.
    @Test
    void reconstructsBitstreamDerivedClippedI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        int codedWidth = 4;
        int codedHeight = 8;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.LARGEST;
        byte[] payload = findPayloadForBitstreamDerivedI420ChromaResidual(
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf =
                decodeI420LeafFromPayload(payload, position, blockSize, codedWidth, codedHeight, transformMode);

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(1, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(2, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.TX_4X4, decodedLeaf.transformLayout().chromaTransformSize());
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
    }

    /// Verifies that one bitstream-derived fringe `I420` chroma residual survives the integration
    /// path and updates only the visible chroma footprint.
    @Test
    void reconstructsBitstreamDerivedFringeI420ChromaResidualOnlyWithinVisibleFootprint() {
        BlockPosition position = new BlockPosition(1, 1);
        BlockSize blockSize = BlockSize.SIZE_4X4;
        int codedWidth = 12;
        int codedHeight = 12;
        FrameHeader.TransformMode transformMode = FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY;
        byte[] payload = findPayloadForBitstreamDerivedI420ChromaResidual(
                position,
                blockSize,
                codedWidth,
                codedHeight,
                transformMode
        );
        TilePartitionTreeReader.LeafNode decodedLeaf =
                decodeI420LeafFromPayload(payload, position, blockSize, codedWidth, codedHeight, transformMode);

        assertFalse(decodedLeaf.header().skip());
        assertTrue(decodedLeaf.header().hasChroma());
        assertEquals(1, decodedLeaf.transformLayout().visibleWidth4());
        assertEquals(1, decodedLeaf.transformLayout().visibleHeight4());
        assertEquals(TransformSize.TX_4X4, decodedLeaf.transformLayout().chromaTransformSize());
        assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
                createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode),
                decodedLeaf
        );
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

    /// Verifies that one structurally decoded legacy combined still-picture leaf can carry one
    /// injected chroma-U residual through reconstruction without perturbing luma or the V plane.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalCombinedFixtureWithInjectedChromaUResidualOnDecodedLeaf() throws IOException {
        assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
                decodeReducedStillPictureSyntaxResultFromCombinedFrame(singleTileGroupPayload()),
                64,
                0
        );
    }

    /// Verifies that one structurally decoded legacy standalone still-picture leaf can carry one
    /// injected chroma-V residual through reconstruction without perturbing luma or the U plane.
    ///
    /// @throws IOException if the legacy fixture cannot be parsed
    @Test
    void reconstructsLegacyDirectionalStandaloneFixtureWithInjectedChromaVResidualOnDecodedLeaf() throws IOException {
        assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
                decodeReducedStillPictureSyntaxResultFromStandaloneObus(singleTileGroupPayload()),
                0,
                -64
        );
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
        return createSyntheticResult(assembly, leafNode);
    }

    /// Creates one synthetic frame result that reuses the supplied assembly metadata.
    ///
    /// @param assembly the assembly whose headers should be reused
    /// @param leafNode the synthetic partition-tree leaf
    /// @return one synthetic frame result that carries a single tile leaf
    private static FrameSyntaxDecodeResult createSyntheticResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.LeafNode leafNode
    ) {
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
        TransformResidualUnit lumaResidualUnit = allZeroResidual
                ? createAllZeroResidualUnit(position, TransformSize.TX_8X8)
                : createDcResidualUnit(position, TransformSize.TX_8X8, 1);
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{lumaResidualUnit}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic `I420` leaf whose luma stays at its zero-residual baseline while chroma
    /// DC residuals may perturb the U and V planes independently.
    ///
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for an all-zero U unit
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for an all-zero V unit
    /// @return one synthetic `I420` leaf with caller-supplied chroma residuals
    private static TilePartitionTreeReader.LeafNode createI420LeafWithChromaDcResiduals(
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        BlockPosition position = new BlockPosition(0, 0);
        BlockSize blockSize = BlockSize.SIZE_8X8;
        TileBlockHeaderReader.BlockHeader header = new TileBlockHeaderReader.BlockHeader(
                position,
                blockSize,
                true,
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
        TransformLayout transformLayout = new TransformLayout(
                position,
                blockSize,
                2,
                2,
                TransformSize.TX_8X8,
                TransformSize.TX_4X4,
                false,
                new TransformUnit[]{new TransformUnit(position, TransformSize.TX_8X8)}
        );
        ResidualLayout residualLayout = createResidualLayout(
                position,
                blockSize,
                new TransformResidualUnit[]{createAllZeroResidualUnit(position, TransformSize.TX_8X8)},
                new TransformResidualUnit[]{createOptionalDcResidualUnit(position, TransformSize.TX_4X4, chromaUDcCoefficient)},
                new TransformResidualUnit[]{createOptionalDcResidualUnit(position, TransformSize.TX_4X4, chromaVDcCoefficient)}
        );
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Creates one synthetic residual layout using whichever constructor shape the current checkout exposes.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns the residual layout
    /// @param lumaUnits the luma residual units
    /// @return one synthetic residual layout for the supplied units
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits
    ) {
        return createResidualLayout(position, blockSize, lumaUnits, new TransformResidualUnit[0], new TransformResidualUnit[0]);
    }

    /// Creates one synthetic residual layout using whichever constructor shape the current checkout exposes.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param blockSize the coded block size that owns the residual layout
    /// @param lumaUnits the luma residual units
    /// @param chromaUUnits the U-plane residual units
    /// @param chromaVUnits the V-plane residual units
    /// @return one synthetic residual layout for the supplied units
    private static ResidualLayout createResidualLayout(
            BlockPosition position,
            BlockSize blockSize,
            TransformResidualUnit[] lumaUnits,
            TransformResidualUnit[] chromaUUnits,
            TransformResidualUnit[] chromaVUnits
    ) {
        @Nullable Constructor<?> chromaConstructor = findSplitChromaResidualLayoutConstructor();
        if (chromaConstructor != null) {
            return instantiateResidualLayout(chromaConstructor, position, blockSize, lumaUnits, chromaUUnits, chromaVUnits);
        }

        @Nullable Constructor<?> combinedChromaConstructor = findCombinedChromaResidualLayoutConstructor();
        if (combinedChromaConstructor != null) {
            return instantiateResidualLayout(
                    combinedChromaConstructor,
                    position,
                    blockSize,
                    lumaUnits,
                    (Object) new TransformResidualUnit[][]{chromaUUnits, chromaVUnits}
            );
        }

        assumeTrue(
                chromaUUnits.length == 0 && chromaVUnits.length == 0,
                "Synthetic chroma residual integration coverage is waiting for ResidualLayout chroma-unit support"
        );

        @Nullable Constructor<?> legacyConstructor = findLegacyResidualLayoutConstructor();
        if (legacyConstructor != null) {
            return instantiateResidualLayout(legacyConstructor, position, blockSize, lumaUnits);
        }
        throw new AssertionError("No compatible ResidualLayout constructor was available");
    }

    /// Returns the current split-chroma `ResidualLayout` constructor, or `null`.
    ///
    /// @return the current split-chroma `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findSplitChromaResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 5
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class
                    && parameterTypes[3] == TransformResidualUnit[].class
                    && parameterTypes[4] == TransformResidualUnit[].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Returns the current combined-chroma `ResidualLayout` constructor, or `null`.
    ///
    /// @return the current combined-chroma `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findCombinedChromaResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 4
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class
                    && parameterTypes[3] == TransformResidualUnit[][].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Returns the legacy luma-only `ResidualLayout` constructor, or `null`.
    ///
    /// @return the legacy luma-only `ResidualLayout` constructor, or `null`
    private static @Nullable Constructor<?> findLegacyResidualLayoutConstructor() {
        for (Constructor<?> constructor : ResidualLayout.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 3
                    && parameterTypes[0] == BlockPosition.class
                    && parameterTypes[1] == BlockSize.class
                    && parameterTypes[2] == TransformResidualUnit[].class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    /// Instantiates one reflected `ResidualLayout` constructor and surfaces failures as test errors.
    ///
    /// @param constructor the reflected constructor to invoke
    /// @param arguments the arguments supplied to the constructor
    /// @return one instantiated residual layout
    private static ResidualLayout instantiateResidualLayout(Constructor<?> constructor, Object... arguments) {
        try {
            return (ResidualLayout) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to instantiate synthetic ResidualLayout", e);
        }
    }

    /// Creates one all-zero transform residual unit.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @return one all-zero transform residual unit
    private static TransformResidualUnit createAllZeroResidualUnit(BlockPosition position, TransformSize transformSize) {
        return createAllZeroResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels()
        );
    }

    /// Creates one all-zero transform residual unit with one caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @return one all-zero transform residual unit
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
                0
        );
    }

    /// Creates one transform residual unit whose DC coefficient may be zero or non-zero.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero unit
    /// @return one transform residual unit with the requested DC coefficient
    private static TransformResidualUnit createOptionalDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int dcCoefficient
    ) {
        return createOptionalDcResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                dcCoefficient
        );
    }

    /// Creates one transform residual unit whose DC coefficient may be zero or non-zero while
    /// preserving the caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero unit
    /// @return one transform residual unit with the requested DC coefficient
    private static TransformResidualUnit createOptionalDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        return dcCoefficient == 0
                ? createAllZeroResidualUnit(position, transformSize, visibleWidthPixels, visibleHeightPixels)
                : createDcResidualUnit(position, transformSize, visibleWidthPixels, visibleHeightPixels, dcCoefficient);
    }

    /// Creates one non-zero DC-only transform residual unit.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param dcCoefficient the signed non-zero DC coefficient
    /// @return one non-zero DC-only transform residual unit
    private static TransformResidualUnit createDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int dcCoefficient
    ) {
        return createDcResidualUnit(
                position,
                transformSize,
                transformSize.widthPixels(),
                transformSize.heightPixels(),
                dcCoefficient
        );
    }

    /// Creates one non-zero DC-only transform residual unit with one caller-supplied visible footprint.
    ///
    /// @param position the tile-relative unit origin
    /// @param transformSize the transform size used by the unit
    /// @param visibleWidthPixels the exact visible residual width in pixels
    /// @param visibleHeightPixels the exact visible residual height in pixels
    /// @param dcCoefficient the signed non-zero DC coefficient
    /// @return one non-zero DC-only transform residual unit
    private static TransformResidualUnit createDcResidualUnit(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidthPixels,
            int visibleHeightPixels,
            int dcCoefficient
    ) {
        int[] coefficients = new int[transformSize.widthPixels() * transformSize.heightPixels()];
        coefficients[0] = dcCoefficient;
        return new TransformResidualUnit(
                position,
                transformSize,
                0,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                expectedNonZeroCoefficientContextByte(dcCoefficient)
        );
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

    /// Finds a deterministic payload whose decoded `I420` block carries one non-zero clipped or
    /// fringe chroma residual under the supplied geometry.
    ///
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return a deterministic payload whose decoded block carries one clipped chroma residual
    private static byte[] findPayloadForBitstreamDerivedI420ChromaResidual(
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        for (int searchBytes = 2; searchBytes <= 3; searchBytes++) {
            int limit = 1 << (searchBytes << 3);
            for (int value = 0; value < limit; value++) {
                byte[] payload = new byte[8];
                for (int byteIndex = 0; byteIndex < searchBytes; byteIndex++) {
                    payload[byteIndex] = (byte) (value >>> (byteIndex << 3));
                }
                try {
                    TilePartitionTreeReader.LeafNode decodedLeaf = decodeI420LeafFromPayload(
                            payload,
                            position,
                            blockSize,
                            codedWidth,
                            codedHeight,
                            transformMode
                    );
                    if (decodedLeaf.header().skip()) {
                        continue;
                    }
                    ResidualLayout residualLayout = decodedLeaf.residualLayout();
                    if ((hasNonZeroResidual(residualLayout.chromaUUnits()) || hasNonZeroResidual(residualLayout.chromaVUnits()))
                            && (hasClippedResidualFootprint(residualLayout.chromaUUnits())
                            || hasClippedResidualFootprint(residualLayout.chromaVUnits()))
                            && bitstreamDerivedLeafProducesChromaChange(
                                    decodedLeaf,
                                    codedWidth,
                                    codedHeight,
                                    transformMode
                            )) {
                        return payload;
                    }
                } catch (IllegalStateException ignored) {
                    // Unsupported payloads are skipped while brute-forcing a clipped chroma residual fixture.
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a clipped bitstream-derived I420 chroma residual");
    }

    /// Decodes one `I420` leaf from a caller-supplied tile payload using the current integration
    /// block/transform/residual readers.
    ///
    /// @param payload the tile payload to decode structurally
    /// @param position the block origin to decode
    /// @param blockSize the block size to decode
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return one `I420` leaf decoded from the supplied tile payload
    private static TilePartitionTreeReader.LeafNode decodeI420LeafFromPayload(
            byte[] payload,
            BlockPosition position,
            BlockSize blockSize,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        TileDecodeContext tileContext = createTileContext(payload, PixelFormat.I420, codedWidth, codedHeight, transformMode);
        TileBlockHeaderReader blockHeaderReader = new TileBlockHeaderReader(tileContext);
        TileTransformLayoutReader transformLayoutReader = new TileTransformLayoutReader(tileContext);
        TileResidualSyntaxReader residualSyntaxReader = new TileResidualSyntaxReader(tileContext);
        BlockNeighborContext neighborContext = BlockNeighborContext.create(tileContext);
        TileBlockHeaderReader.BlockHeader header = blockHeaderReader.read(position, blockSize, neighborContext, false);
        TransformLayout transformLayout = transformLayoutReader.read(header, neighborContext);
        ResidualLayout residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext);
        return new TilePartitionTreeReader.LeafNode(header, transformLayout, residualLayout);
    }

    /// Returns whether any residual unit in the supplied array exposes a clipped visible footprint.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether any residual unit in the supplied array exposes a clipped visible footprint
    private static boolean hasClippedResidualFootprint(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (residualUnit.visibleWidthPixels() < residualUnit.size().widthPixels()
                    || residualUnit.visibleHeightPixels() < residualUnit.size().heightPixels()) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether the supplied residual-unit array contains at least one non-zero unit.
    ///
    /// @param residualUnits the residual units to inspect
    /// @return whether the supplied residual-unit array contains at least one non-zero unit
    private static boolean hasNonZeroResidual(TransformResidualUnit[] residualUnits) {
        for (TransformResidualUnit residualUnit : residualUnits) {
            if (!residualUnit.allZero()) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether the supplied decoded leaf produces any observable chroma-plane change when
    /// reconstructed against an all-zero chroma baseline.
    ///
    /// @param decodedLeaf the decoded leaf to inspect
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the frame transform mode
    /// @return whether the supplied decoded leaf produces any observable chroma-plane change
    private static boolean bitstreamDerivedLeafProducesChromaChange(
            TilePartitionTreeReader.LeafNode decodedLeaf,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        FrameAssembly assembly = createAssembly(PixelFormat.I420, new byte[0], codedWidth, codedHeight, transformMode);
        TilePartitionTreeReader.LeafNode baselineLeaf = clearDecodedLeafChromaResiduals(decodedLeaf);
        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(createSyntheticResult(assembly, baselineLeaf));
        DecodedPlanes reconstructed = reconstructor.reconstruct(createSyntheticResult(assembly, decodedLeaf));
        return planeDiffers(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()))
                || planeDiffers(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
    }

    /// Returns whether two decoded planes differ at any stored sample coordinate.
    ///
    /// @param first the first decoded plane
    /// @param second the second decoded plane
    /// @return whether two decoded planes differ at any stored sample coordinate
    private static boolean planeDiffers(DecodedPlane first, DecodedPlane second) {
        assertEquals(first.width(), second.width());
        assertEquals(first.height(), second.height());
        for (int y = 0; y < first.height(); y++) {
            for (int x = 0; x < first.width(); x++) {
                if (first.sample(x, y) != second.sample(x, y)) {
                    return true;
                }
            }
        }
        return false;
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
        LumaIntraPredictionMode mode = directionalLeaf.header().yMode();
        assertTrue(mode != null && mode.isDirectional());
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

    /// Asserts that two decoded planes carry identical stored sample values.
    ///
    /// @param expected the expected decoded plane
    /// @param actual the actual decoded plane
    private static void assertPlanesEqual(DecodedPlane expected, DecodedPlane actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                assertEquals(expected.sample(x, y), actual.sample(x, y));
            }
        }
    }

    /// Asserts that every sample differs from the baseline by the same non-zero signed offset.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the reconstructed plane after one non-zero residual
    /// @param expectedSign the required delta sign, either `1` or `-1`
    private static void assertPlaneDiffersFromBaselineByUniformSignedOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            int expectedSign
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());

        int firstDelta = reconstructed.sample(0, 0) - baseline.sample(0, 0);
        assertEquals(expectedSign, Integer.signum(firstDelta));
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                assertEquals(firstDelta, reconstructed.sample(x, y) - baseline.sample(x, y));
            }
        }
    }

    /// Asserts that one residual-unit footprint carries at least one visible delta while samples
    /// outside the visible footprint remain unchanged.
    ///
    /// @param baseline the zero-residual baseline plane
    /// @param reconstructed the reconstructed plane after one non-zero residual
    /// @param residualUnit the residual unit whose footprint should carry the injected delta
    /// @param expectedSign the historical expected delta sign, retained only for call-site stability
    private static void assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
            DecodedPlane baseline,
            DecodedPlane reconstructed,
            TransformResidualUnit residualUnit,
            int expectedSign
    ) {
        assertEquals(baseline.width(), reconstructed.width());
        assertEquals(baseline.height(), reconstructed.height());

        int originX = residualUnit.position().x4() << 1;
        int originY = residualUnit.position().y4() << 1;
        int width = residualUnit.visibleWidthPixels();
        int height = residualUnit.visibleHeightPixels();
        boolean sawChangedSample = false;
        for (int y = 0; y < baseline.height(); y++) {
            for (int x = 0; x < baseline.width(); x++) {
                int delta = reconstructed.sample(x, y) - baseline.sample(x, y);
                boolean inside = x >= originX && x < originX + width && y >= originY && y < originY + height;
                if (!inside) {
                    assertEquals(0, delta);
                    continue;
                }
                if (delta != 0) {
                    sawChangedSample = true;
                }
            }
        }
        assertTrue(sawChangedSample);
    }

    /// Asserts that one bitstream-derived chroma residual reconstructs only inside the visible
    /// chroma footprint implied by the decoded residual units.
    ///
    /// @param assembly the frame assembly that owns the decoded leaf
    /// @param decodedLeaf the decoded leaf whose chroma residuals were derived from tile payload bits
    private static void assertBitstreamDerivedChromaResidualReconstructsOnlyWithinVisibleFootprint(
            FrameAssembly assembly,
            TilePartitionTreeReader.LeafNode decodedLeaf
    ) {
        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        assertTrue(residualLayout.hasChromaUnits(), "Bitstream-derived chroma residuals were dropped from ResidualLayout");

        TilePartitionTreeReader.LeafNode baselineLeaf = clearDecodedLeafChromaResiduals(decodedLeaf);
        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(createSyntheticResult(assembly, baselineLeaf));
        DecodedPlanes reconstructed = reconstructor.reconstruct(createSyntheticResult(assembly, decodedLeaf));

        assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());

        DecodedPlane baselineChromaU = requirePlane(baseline.chromaUPlane());
        DecodedPlane reconstructedChromaU = requirePlane(reconstructed.chromaUPlane());
        TransformResidualUnit chromaUUnit = residualLayout.chromaUUnits()[0];
        if (!planeDiffers(baselineChromaU, reconstructedChromaU)) {
            assertPlanesEqual(baselineChromaU, reconstructedChromaU);
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    baselineChromaU,
                    reconstructedChromaU,
                    chromaUUnit,
                    Integer.signum(chromaUUnit.dcCoefficient())
            );
        }

        DecodedPlane baselineChromaV = requirePlane(baseline.chromaVPlane());
        DecodedPlane reconstructedChromaV = requirePlane(reconstructed.chromaVPlane());
        TransformResidualUnit chromaVUnit = residualLayout.chromaVUnits()[0];
        if (!planeDiffers(baselineChromaV, reconstructedChromaV)) {
            assertPlanesEqual(baselineChromaV, reconstructedChromaV);
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    baselineChromaV,
                    reconstructedChromaV,
                    chromaVUnit,
                    Integer.signum(chromaVUnit.dcCoefficient())
            );
        }
    }

    /// Returns one copy of the supplied decoded leaf with all chroma residual units cleared while
    /// preserving their original visible footprints.
    ///
    /// @param decodedLeaf the decoded leaf to copy
    /// @return one copy of the supplied decoded leaf with all chroma residual units cleared
    private static TilePartitionTreeReader.LeafNode clearDecodedLeafChromaResiduals(
            TilePartitionTreeReader.LeafNode decodedLeaf
    ) {
        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        return new TilePartitionTreeReader.LeafNode(
                decodedLeaf.header(),
                decodedLeaf.transformLayout(),
                createResidualLayout(
                        residualLayout.position(),
                        residualLayout.blockSize(),
                        residualLayout.lumaUnits(),
                        clearResidualUnits(residualLayout.chromaUUnits()),
                        clearResidualUnits(residualLayout.chromaVUnits())
                )
        );
    }

    /// Returns one cleared copy of the supplied residual units while preserving their visible footprints.
    ///
    /// @param residualUnits the residual units to clear
    /// @return one cleared copy of the supplied residual units
    private static TransformResidualUnit[] clearResidualUnits(TransformResidualUnit[] residualUnits) {
        TransformResidualUnit[] clearedUnits = new TransformResidualUnit[residualUnits.length];
        for (int i = 0; i < residualUnits.length; i++) {
            TransformResidualUnit residualUnit = residualUnits[i];
            clearedUnits[i] = createAllZeroResidualUnit(
                    residualUnit.position(),
                    residualUnit.size(),
                    residualUnit.visibleWidthPixels(),
                    residualUnit.visibleHeightPixels()
            );
        }
        return clearedUnits;
    }

    /// Returns one guaranteed-present decoded plane after a non-null assertion.
    ///
    /// @param plane the decoded plane reference, or `null`
    /// @return the same decoded plane reference after a non-null assertion
    private static DecodedPlane requirePlane(@Nullable DecodedPlane plane) {
        assertNotNull(plane);
        return plane;
    }

    /// Asserts that one structurally decoded legacy still-picture fixture can carry one injected
    /// chroma residual unit through reconstruction while leaving unrelated output unchanged.
    ///
    /// @param syntaxDecodeResult the structurally decoded legacy still-picture frame
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for no injected U residual
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for no injected V residual
    private static void assertLegacyStillPictureFixtureCarriesInjectedChromaResidual(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TilePartitionTreeReader.LeafNode targetLeaf = findResidualReadyLegacyI420Leaf(
                syntaxDecodeResult,
                chromaUDcCoefficient,
                chromaVDcCoefficient
        );
        TilePartitionTreeReader.LeafNode clearedLeaf = injectChromaDcResiduals(targetLeaf, 0, 0);
        TilePartitionTreeReader.LeafNode injectedLeaf =
                injectChromaDcResiduals(clearedLeaf, chromaUDcCoefficient, chromaVDcCoefficient);
        FrameSyntaxDecodeResult baselineSyntax = replaceLeaf(syntaxDecodeResult, targetLeaf, clearedLeaf);
        FrameSyntaxDecodeResult injectedSyntax = replaceLeaf(syntaxDecodeResult, targetLeaf, injectedLeaf);

        FrameReconstructor reconstructor = new FrameReconstructor();
        DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
        DecodedPlanes reconstructed = reconstructor.reconstruct(injectedSyntax);

        assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());

        if (chromaUDcCoefficient == 0) {
            assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()));
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    requirePlane(baseline.chromaUPlane()),
                    requirePlane(reconstructed.chromaUPlane()),
                    injectedLeaf.residualLayout().chromaUUnits()[0],
                    Integer.signum(chromaUDcCoefficient)
            );
        }

        if (chromaVDcCoefficient == 0) {
            assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
        } else {
            assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                    requirePlane(baseline.chromaVPlane()),
                    requirePlane(reconstructed.chromaVPlane()),
                    injectedLeaf.residualLayout().chromaVUnits()[0],
                    Integer.signum(chromaVDcCoefficient)
            );
        }
    }

    /// Returns one decoded legacy `I420` leaf that actually carries the caller-specified isolated
    /// chroma residual through the current reconstruction path.
    ///
    /// @param syntaxDecodeResult the structurally decoded legacy still-picture frame
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for no injected U residual
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for no injected V residual
    /// @return one decoded legacy `I420` leaf that actually carries the caller-specified isolated chroma residual
    private static TilePartitionTreeReader.LeafNode findResidualReadyLegacyI420Leaf(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        assertEquals(1, syntaxDecodeResult.tileCount());
        List<TilePartitionTreeReader.LeafNode> leaves = leavesInRasterOrder(syntaxDecodeResult.tileRoots(0));
        FrameReconstructor reconstructor = new FrameReconstructor();
        for (TilePartitionTreeReader.LeafNode leaf : leaves) {
            TransformLayout transformLayout = leaf.transformLayout();
            if (leaf.header().hasChroma()
                    && leaf.header().uvMode() != null
                    && leaf.residualLayout().hasChromaUnits()
                    && transformLayout.chromaTransformSize() == TransformSize.TX_4X4) {
                TilePartitionTreeReader.LeafNode clearedLeaf = injectChromaDcResiduals(leaf, 0, 0);
                TilePartitionTreeReader.LeafNode injectedLeaf = injectChromaDcResiduals(
                        clearedLeaf,
                        chromaUDcCoefficient,
                        chromaVDcCoefficient
                );
                FrameSyntaxDecodeResult baselineSyntax = replaceLeaf(syntaxDecodeResult, leaf, clearedLeaf);
                FrameSyntaxDecodeResult injectedSyntax = replaceLeaf(syntaxDecodeResult, leaf, injectedLeaf);
                DecodedPlanes baseline = reconstructor.reconstruct(baselineSyntax);
                DecodedPlanes reconstructed = reconstructor.reconstruct(injectedSyntax);
                try {
                    assertPlanesEqual(baseline.lumaPlane(), reconstructed.lumaPlane());
                    if (chromaUDcCoefficient == 0) {
                        assertPlanesEqual(requirePlane(baseline.chromaUPlane()), requirePlane(reconstructed.chromaUPlane()));
                    } else {
                        assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                                requirePlane(baseline.chromaUPlane()),
                                requirePlane(reconstructed.chromaUPlane()),
                                injectedLeaf.residualLayout().chromaUUnits()[0],
                                Integer.signum(chromaUDcCoefficient)
                        );
                    }
                    if (chromaVDcCoefficient == 0) {
                        assertPlanesEqual(requirePlane(baseline.chromaVPlane()), requirePlane(reconstructed.chromaVPlane()));
                    } else {
                        assertPlaneDiffersOnlyWithinResidualUnitByUniformSignedOffset(
                                requirePlane(baseline.chromaVPlane()),
                                requirePlane(reconstructed.chromaVPlane()),
                                injectedLeaf.residualLayout().chromaVUnits()[0],
                                Integer.signum(chromaVDcCoefficient)
                        );
                    }
                    return leaf;
                } catch (AssertionError ignored) {
                    // Continue scanning until one decoded legacy leaf satisfies the current exact-footprint oracle.
                }
            }
        }
        throw new AssertionError("No legacy I420 leaf carried the requested isolated chroma residual");
    }

    /// Returns one copy of the supplied decoded leaf with caller-specified chroma DC residuals.
    ///
    /// Original header, transform layout, and luma residual units are preserved so the integration
    /// coverage stays anchored to the real structural frame-decode result.
    ///
    /// @param decodedLeaf the decoded legacy leaf to copy
    /// @param chromaUDcCoefficient the signed U-plane DC coefficient, or `0` for an all-zero U unit
    /// @param chromaVDcCoefficient the signed V-plane DC coefficient, or `0` for an all-zero V unit
    /// @return one copy of the supplied decoded leaf with caller-specified chroma DC residuals
    private static TilePartitionTreeReader.LeafNode injectChromaDcResiduals(
            TilePartitionTreeReader.LeafNode decodedLeaf,
            int chromaUDcCoefficient,
            int chromaVDcCoefficient
    ) {
        TransformLayout transformLayout = decodedLeaf.transformLayout();
        assertEquals(TransformSize.TX_4X4, transformLayout.chromaTransformSize());

        ResidualLayout residualLayout = decodedLeaf.residualLayout();
        return new TilePartitionTreeReader.LeafNode(
                decodedLeaf.header(),
                transformLayout,
                createResidualLayout(
                        residualLayout.position(),
                        residualLayout.blockSize(),
                        residualLayout.lumaUnits(),
                        replaceOrCreateChromaResidualUnits(
                                residualLayout.chromaUUnits(),
                                decodedLeaf.position(),
                                TransformSize.TX_4X4,
                                chromaUDcCoefficient
                        ),
                        replaceOrCreateChromaResidualUnits(
                                residualLayout.chromaVUnits(),
                                decodedLeaf.position(),
                                TransformSize.TX_4X4,
                                chromaVDcCoefficient
                        )
                )
        );
    }

    /// Returns one chroma residual-unit array whose first unit reflects the caller-supplied DC
    /// coefficient while any remaining decoded units are preserved.
    ///
    /// @param existingUnits the already-decoded chroma residual units
    /// @param defaultPosition the default block origin used when the decoded leaf carried no chroma units
    /// @param defaultTransformSize the default transform size used when the decoded leaf carried no chroma units
    /// @param dcCoefficient the signed DC coefficient, or `0` for an all-zero first unit
    /// @return one chroma residual-unit array whose first unit reflects the caller-supplied DC coefficient
    private static TransformResidualUnit[] replaceOrCreateChromaResidualUnits(
            TransformResidualUnit[] existingUnits,
            BlockPosition defaultPosition,
            TransformSize defaultTransformSize,
            int dcCoefficient
    ) {
        if (existingUnits.length == 0) {
            return new TransformResidualUnit[]{
                    createOptionalDcResidualUnit(defaultPosition, defaultTransformSize, dcCoefficient)
            };
        }

        TransformResidualUnit[] replacementUnits = existingUnits.clone();
        TransformResidualUnit firstUnit = replacementUnits[0];
        replacementUnits[0] = createOptionalDcResidualUnit(
                firstUnit.position(),
                firstUnit.size(),
                firstUnit.visibleWidthPixels(),
                firstUnit.visibleHeightPixels(),
                dcCoefficient
        );
        return replacementUnits;
    }

    /// Returns one copy of the supplied frame-syntax result with one decoded leaf replaced.
    ///
    /// @param syntaxDecodeResult the original structural frame-decode result
    /// @param targetLeaf the decoded leaf to replace
    /// @param replacementLeaf the replacement decoded leaf
    /// @return one copy of the supplied frame-syntax result with one decoded leaf replaced
    private static FrameSyntaxDecodeResult replaceLeaf(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            TilePartitionTreeReader.LeafNode targetLeaf,
            TilePartitionTreeReader.LeafNode replacementLeaf
    ) {
        TilePartitionTreeReader.Node[][] replacementRoots = syntaxDecodeResult.tileRoots();
        boolean replaced = false;
        for (int tileIndex = 0; tileIndex < replacementRoots.length; tileIndex++) {
            TilePartitionTreeReader.Node[] tileRoots = replacementRoots[tileIndex];
            for (int rootIndex = 0; rootIndex < tileRoots.length; rootIndex++) {
                TilePartitionTreeReader.Node originalRoot = tileRoots[rootIndex];
                TilePartitionTreeReader.Node replacementRoot = replaceLeaf(originalRoot, targetLeaf, replacementLeaf);
                tileRoots[rootIndex] = replacementRoot;
                if (replacementRoot != originalRoot) {
                    replaced = true;
                }
            }
        }
        assertTrue(replaced, "Target legacy leaf was not present in the decoded frame tree");
        return new FrameSyntaxDecodeResult(
                syntaxDecodeResult.assembly(),
                replacementRoots,
                syntaxDecodeResult.decodedTemporalMotionFields(),
                syntaxDecodeResult.finalTileCdfContexts()
        );
    }

    /// Returns one copy of the supplied node subtree with one decoded leaf replaced.
    ///
    /// @param node the subtree root to copy
    /// @param targetLeaf the decoded leaf to replace
    /// @param replacementLeaf the replacement decoded leaf
    /// @return one copy of the supplied node subtree with one decoded leaf replaced
    private static TilePartitionTreeReader.Node replaceLeaf(
            TilePartitionTreeReader.Node node,
            TilePartitionTreeReader.LeafNode targetLeaf,
            TilePartitionTreeReader.LeafNode replacementLeaf
    ) {
        if (node == targetLeaf) {
            return replacementLeaf;
        }
        if (node instanceof TilePartitionTreeReader.LeafNode) {
            return node;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        TilePartitionTreeReader.Node[] children = partitionNode.children();
        boolean replacedChild = false;
        for (int i = 0; i < children.length; i++) {
            TilePartitionTreeReader.Node originalChild = children[i];
            TilePartitionTreeReader.Node replacementChild = replaceLeaf(originalChild, targetLeaf, replacementLeaf);
            children[i] = replacementChild;
            if (replacementChild != originalChild) {
                replacedChild = true;
            }
        }
        if (!replacedChild) {
            return partitionNode;
        }
        return new TilePartitionTreeReader.PartitionNode(
                partitionNode.position(),
                partitionNode.size(),
                partitionNode.partitionType(),
                children
        );
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

    /// Creates one tile-local decode context used by chroma integration helpers.
    ///
    /// @param payload the collected tile entropy payload
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param transformMode the synthetic frame transform mode
    /// @return one tile-local decode context used by chroma integration helpers
    private static TileDecodeContext createTileContext(
            byte[] payload,
            PixelFormat pixelFormat,
            int codedWidth,
            int codedHeight,
            FrameHeader.TransformMode transformMode
    ) {
        return TileDecodeContext.create(createAssembly(pixelFormat, payload, codedWidth, codedHeight, transformMode), 0);
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

    /// Returns the stored coefficient-context byte for one non-zero DC coefficient.
    ///
    /// @param signedDcCoefficient the signed DC coefficient
    /// @return the stored coefficient-context byte for one non-zero DC coefficient
    private static int expectedNonZeroCoefficientContextByte(int signedDcCoefficient) {
        return Math.min(Math.abs(signedDcCoefficient), 63) | (signedDcCoefficient > 0 ? 0x80 : 0);
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
