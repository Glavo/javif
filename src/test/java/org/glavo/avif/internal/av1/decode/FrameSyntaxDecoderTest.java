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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrameSyntaxDecoder`.
@NotNullByDefault
final class FrameSyntaxDecoderTest {
    /// One fixed inter-tile payload whose first block decodes as `skip = false` and `intra = false`.
    private static final byte[] INTER_BLOCK_PAYLOAD = HexFixtureResources.readBytes("av1/fixtures/all-zero-8.hex");

    /// One fixed payload whose first skip decision changes when the skip CDF is inherited.
    private static final byte[] DIFFERENT_INHERITED_SKIP_PAYLOAD =
            HexFixtureResources.readBytes("av1/fixtures/frame-cdf-different-skip.hex");

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

    /// Verifies that CDF inheritance can be enabled without also inheriting temporal motion fields.
    @Test
    void decodeFrameCanUseSeparateCdfAndTemporalReferenceSnapshots() {
        CdfContext inheritedCdf = CdfContext.createDefault();
        inheritedCdf.mutableSkipCdf(0)[0] = 32000;
        FrameAssembly assembly = createAssembly(FrameType.INTER, DIFFERENT_INHERITED_SKIP_PAYLOAD, false, 8, 8);
        FrameSyntaxDecodeResult cdfReferenceResult = new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{new TilePartitionTreeReader.Node[0]},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)},
                new CdfContext[]{inheritedCdf}
        );

        FrameSyntaxDecodeResult defaultResult = new FrameSyntaxDecoder(null).decode(assembly);
        FrameSyntaxDecodeResult cdfOnlyResult = new FrameSyntaxDecoder(cdfReferenceResult, null).decode(assembly);

        boolean defaultSkip = firstLeaf(defaultResult.tileRoots(0)).header().skip();
        boolean cdfOnlySkip = firstLeaf(cdfOnlyResult.tileRoots(0)).header().skip();
        assertFalse(defaultSkip);
        assertTrue(cdfOnlySkip);
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
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, payload, 0, payload.length)}
        );
        return assembly;
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
