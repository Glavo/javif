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

import org.glavo.avif.decode.Av1ColorConfig;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.glavo.avif.testutil.HexFixtureResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrameSyntaxDecoder`.
@NotNullByDefault
final class FrameSyntaxDecoderTest {
    /// One fixed inter-tile payload whose first block decodes as `skip = false` and `intra = false`.
    private static final byte @Unmodifiable [] INTER_BLOCK_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/inter-zero-mv-8.hex");

    /// One fixed payload whose first skip decision changes when the skip CDF is inherited.
    private static final byte @Unmodifiable [] DIFFERENT_INHERITED_SKIP_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/frame-cdf-different-skip.hex");

    /// One fixed payload whose leading loop-restoration unit decodes as active Wiener syntax.
    private static final byte @Unmodifiable [] RESTORATION_WIENER_PAYLOAD = new byte[]{
            (byte) 0xCE, 0, 0, 0, 0, 0, 0, 0
    };

    /// Verifies that structural frame decoding expands tile syntax and produces a temporal motion field.
    @Test
    void decodeFrameProducesTileRootsAndTemporalMotionField() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, INTER_BLOCK_PAYLOAD, false);

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);
        TilePartitionTreeReader.Node[] roots = result.tileRoots(0);
        TilePartitionTreeReader.LeafNode leaf = firstLeaf(roots);

        assertEquals(1, result.tileCount());
        assertEquals(1, roots.length);
        assertEquals(BlockSize.SIZE_64X64, roots[0].size());
        assertEquals(0, leaf.header().position().x4());
        assertEquals(0, leaf.header().position().y4());
        assertEquals(BlockSize.SIZE_64X64, leaf.header().size());
        assertFalse(leaf.header().skip());
        assertFalse(leaf.header().intra());
        assertEquals(1, leaf.transformLayout().lumaUnits().length);
        assertEquals(1, leaf.residualLayout().lumaUnits().length);
        TileDecodeContext.TemporalMotionBlock temporalBlock = result.decodedTemporalMotionField(0).block(0, 0);
        assertNotNull(temporalBlock);
        assertEquals(0, temporalBlock.referenceFrame0());
        assertEquals(InterMotionVector.resolved(MotionVector.zero()), temporalBlock.motionVector0());
    }

    /// Verifies that structural frame decoding captures the final tile-local CDF state.
    @Test
    void decodeFrameCapturesFinalTileCdfState() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, INTER_BLOCK_PAYLOAD, false);

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);

        assertEquals(1029, result.finalTileCdfContext(0).mutableSkipCdf(0)[0]);
    }

    /// Verifies that structural frame decoding captures active loop-restoration unit syntax.
    @Test
    void decodeFrameCapturesActiveLoopRestorationUnitSyntax() {
        FrameHeader.RestorationInfo restoration = new FrameHeader.RestorationInfo(
                new FrameHeader.RestorationType[]{
                        FrameHeader.RestorationType.WIENER,
                        FrameHeader.RestorationType.NONE,
                        FrameHeader.RestorationType.NONE
                },
                6,
                6
        );
        FrameAssembly assembly = createAssembly(
                FrameType.KEY,
                RESTORATION_WIENER_PAYLOAD,
                false,
                64,
                64,
                restoration
        );

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);
        RestorationUnitMap restorationUnitMap = result.restorationUnitMap();
        RestorationUnit unit = restorationUnitMap.unit(0, 0, 0);

        assertEquals(1, restorationUnitMap.columns(0));
        assertEquals(1, restorationUnitMap.rows(0));
        assertEquals(0, restorationUnitMap.columns(1));
        assertEquals(0, restorationUnitMap.rows(1));
        assertNotNull(unit);
        assertEquals(FrameHeader.RestorationType.WIENER, unit.type());
        assertArrayEquals(new int[]{4, -7, 7}, unit.wienerCoefficients()[0]);
        assertArrayEquals(new int[]{1, -10, 13}, unit.wienerCoefficients()[1]);
        assertEquals(BlockSize.SIZE_64X64, result.tileRoots(0)[0].size());
    }

    /// Verifies that reference-frame CDF snapshots seed subsequent tile syntax decoding.
    @Test
    void decodeFrameSeedsTileSyntaxFromReferenceCdfState() {
        CdfContext inheritedCdf = CdfContext.createDefault();
        inheritedCdf.mutableSkipCdf(0)[0] = 32000;
        FrameAssembly assembly = createAssembly(FrameType.INTER, DIFFERENT_INHERITED_SKIP_PAYLOAD, false, 8, 8);
        FrameSyntaxDecodeResult referenceResult = new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{new TilePartitionTreeReader.Node[0]},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)},
                new CdfContext[]{inheritedCdf}
        );

        FrameSyntaxDecodeResult defaultResult = new FrameSyntaxDecoder(null).decode(assembly);
        FrameSyntaxDecodeResult seededResult = new FrameSyntaxDecoder(referenceResult).decode(assembly);

        boolean defaultSkip = firstLeaf(defaultResult.tileRoots(0)).header().skip();
        boolean seededSkip = firstLeaf(seededResult.tileRoots(0)).header().skip();
        assertFalse(defaultSkip);
        assertTrue(seededSkip);
    }

    /// Verifies that a current tile inherits the single CDF context selected by the reference
    /// frame's `context_update_tile_id`, even when the two frames have different tile counts.
    @Test
    void decodeFrameSeedsTileSyntaxFromReferenceContextUpdateTile() {
        CdfContext unselectedCdf = CdfContext.createDefault();
        CdfContext selectedCdf = CdfContext.createDefault();
        selectedCdf.mutableSkipCdf(0)[0] = 32000;
        selectedCdf.mutableSkipCdf(0)[1] = 32;
        FrameAssembly referenceAssembly = createAssembly(
                FrameType.INTER,
                new byte[][]{new byte[0], new byte[0]},
                false,
                128,
                64,
                noRestoration(),
                twoColumnTiling(1)
        );
        FrameSyntaxDecodeResult referenceResult = new FrameSyntaxDecodeResult(
                referenceAssembly,
                new TilePartitionTreeReader.Node[][]{
                        new TilePartitionTreeReader.Node[0],
                        new TilePartitionTreeReader.Node[0]
                },
                new TileDecodeContext.TemporalMotionField[]{
                        new TileDecodeContext.TemporalMotionField(1, 1),
                        new TileDecodeContext.TemporalMotionField(1, 1)
                },
                new CdfContext[]{unselectedCdf, selectedCdf}
        );
        FrameAssembly currentAssembly = createAssembly(
                FrameType.INTER,
                DIFFERENT_INHERITED_SKIP_PAYLOAD,
                false,
                8,
                8
        );

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(referenceResult).decode(currentAssembly);

        assertArrayEquals(new int[]{32000, 32}, referenceResult.contextUpdateTileCdfContext().mutableSkipCdf(0));
        assertArrayEquals(new int[]{32000, 0}, referenceResult.savedFrameCdfContext().mutableSkipCdf(0));
        assertTrue(firstLeaf(result.tileRoots(0)).header().skip());
    }

    /// Verifies that a frame enabling reference-frame motion vectors reaches tile decoding even
    /// when no populated runtime temporal source is available.
    @Test
    void decodeFrameAcceptsReferenceFrameMotionVectorsWithoutPopulatedSources() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, INTER_BLOCK_PAYLOAD, true, 8, 8);

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);

        assertEquals(1, result.tileCount());
        assertEquals(BlockSize.SIZE_8X8, firstLeaf(result.tileRoots(0)).header().size());
    }

    /// Verifies that replacing stored tile-local CDF contexts preserves the current frame's temporal results.
    @Test
    void frameSyntaxDecodeResultCanReplaceStoredTileCdfContexts() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, INTER_BLOCK_PAYLOAD, false);
        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);
        CdfContext replacementCdf = CdfContext.createDefault();
        replacementCdf.mutableSkipCdf(0)[0] = 32000;

        FrameSyntaxDecodeResult replaced = result.withFinalTileCdfContexts(new CdfContext[]{replacementCdf});

        assertEquals(32000, replaced.finalTileCdfContext(0).mutableSkipCdf(0)[0]);
        assertEquals(result.decodedTemporalMotionField(0).block(0, 0), replaced.decodedTemporalMotionField(0).block(0, 0));
    }

    /// Verifies that compact reference state retains every later-decoder input while returning
    /// independent mutable snapshots.
    @Test
    void compactReferenceStateRetainsLaterDecoderInputs() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, INTER_BLOCK_PAYLOAD, false);
        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);

        ReferenceFrameSyntaxState state = ReferenceFrameSyntaxState.from(result);

        assertSame(assembly.sequenceHeader(), state.sequenceHeader());
        assertSame(assembly.frameHeader(), state.frameHeader());
        assertNull(state.referenceFrameHeader(0));

        TileDecodeContext.TemporalMotionBlock temporalBlock = state.decodedTemporalMotionBlockAt(0, 0);
        assertNotNull(temporalBlock);
        assertEquals(0, temporalBlock.referenceFrame0());
        assertEquals(InterMotionVector.resolved(MotionVector.zero()), temporalBlock.motionVector0());
        assertNull(state.decodedTemporalMotionBlockAt(-1, 0));
        assertNull(state.decodedTemporalMotionBlockAt(8, 0));

        SegmentIdMap segmentIdMap = state.segmentIdMap();
        segmentIdMap.fill(0, 0, 16, 16, 7);
        assertEquals(0, state.segmentIdMap().getOrZero(0, 0));

        CdfContext savedCdf = state.savedFrameCdfContext();
        int savedSkipThreshold = savedCdf.mutableSkipCdf(0)[0];
        savedCdf.mutableSkipCdf(0)[0] = 32000;
        assertEquals(savedSkipThreshold, state.savedFrameCdfContext().mutableSkipCdf(0)[0]);
    }

    /// Creates a synthetic frame assembly used by structural frame-decoder tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param payload the collected tile entropy payload
    /// @param useReferenceFrameMotionVectors whether temporal motion vectors are enabled
    /// @return a synthetic frame assembly used by structural frame-decoder tests
    private static FrameAssembly createAssembly(
            FrameType frameType,
            byte[] payload,
            boolean useReferenceFrameMotionVectors
    ) {
        return createAssembly(frameType, payload, useReferenceFrameMotionVectors, 64, 64);
    }

    /// Creates a synthetic frame assembly used by structural frame-decoder tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param payload the collected tile entropy payload
    /// @param useReferenceFrameMotionVectors whether temporal motion vectors are enabled
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @return a synthetic frame assembly used by structural frame-decoder tests
    private static FrameAssembly createAssembly(
            FrameType frameType,
            byte[] payload,
            boolean useReferenceFrameMotionVectors,
            int codedWidth,
            int codedHeight
    ) {
        return createAssembly(
                frameType,
                payload,
                useReferenceFrameMotionVectors,
                codedWidth,
                codedHeight,
                noRestoration()
        );
    }

    /// Creates a synthetic frame assembly used by structural frame-decoder tests.
    ///
    /// @param frameType the synthetic frame type
    /// @param payload the collected tile entropy payload
    /// @param useReferenceFrameMotionVectors whether temporal motion vectors are enabled
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param restoration the frame-level loop-restoration configuration
    /// @return a synthetic frame assembly used by structural frame-decoder tests
    private static FrameAssembly createAssembly(
            FrameType frameType,
            byte[] payload,
            boolean useReferenceFrameMotionVectors,
            int codedWidth,
            int codedHeight,
            FrameHeader.RestorationInfo restoration
    ) {
        return createAssembly(
                frameType,
                new byte[][]{payload},
                useReferenceFrameMotionVectors,
                codedWidth,
                codedHeight,
                restoration,
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
                )
        );
    }

    /// Creates a synthetic frame assembly with an explicit tile layout and payload per tile.
    ///
    /// @param frameType the synthetic frame type
    /// @param tilePayloads the collected entropy payload for each tile in raster order
    /// @param useReferenceFrameMotionVectors whether temporal motion vectors are enabled
    /// @param codedWidth the coded frame width
    /// @param codedHeight the coded frame height
    /// @param restoration the frame-level loop-restoration configuration
    /// @param tiling the explicit tile layout
    /// @return a synthetic frame assembly used by structural frame-decoder tests
    private static FrameAssembly createAssembly(
            FrameType frameType,
            byte[][] tilePayloads,
            boolean useReferenceFrameMotionVectors,
            int codedWidth,
            int codedHeight,
            FrameHeader.RestorationInfo restoration,
            FrameHeader.TilingInfo tiling
    ) {
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
                new Av1ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        Av1ChromaFormat.YUV420,
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
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                new FrameHeader.FrameSize(codedWidth, codedWidth, codedHeight, codedWidth, codedHeight),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                false,
                FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                useReferenceFrameMotionVectors,
                true,
                tiling,
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
                restoration,
                FrameHeader.TransformMode.LARGEST,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                false,
                false
        );
        FrameAssembly assembly = new FrameAssembly(sequenceHeader, frameHeader, 0, 0);
        TileBitstream[] tileBitstreams = new TileBitstream[tilePayloads.length];
        for (int tileIndex = 0; tileIndex < tilePayloads.length; tileIndex++) {
            byte[] tilePayload = tilePayloads[tileIndex];
            tileBitstreams[tileIndex] = new TileBitstream(
                    tileIndex,
                    tilePayload,
                    0,
                    tilePayload.length
            );
        }
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, tilePayloads.length - 1, tilePayloads.length),
                0,
                0,
                tileBitstreams
        );
        return assembly;
    }

    /// Creates a two-column, one-row tile layout with an explicit context-update tile.
    ///
    /// @param updateTileIndex the tile whose final CDF state is saved for future frames
    /// @return the synthetic two-column tile layout
    private static FrameHeader.TilingInfo twoColumnTiling(int updateTileIndex) {
        return new FrameHeader.TilingInfo(
                true,
                1,
                0,
                1,
                1,
                2,
                0,
                0,
                0,
                1,
                new int[]{0, 1, 2},
                new int[]{0, 1},
                updateTileIndex
        );
    }

    /// Creates disabled frame-level loop-restoration state.
    ///
    /// @return disabled frame-level loop-restoration state
    private static FrameHeader.RestorationInfo noRestoration() {
        return new FrameHeader.RestorationInfo(
                new FrameHeader.RestorationType[]{
                        FrameHeader.RestorationType.NONE,
                        FrameHeader.RestorationType.NONE,
                        FrameHeader.RestorationType.NONE
                },
                0,
                0
        );
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
