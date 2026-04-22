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
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrameSyntaxDecoder`.
@NotNullByDefault
final class FrameSyntaxDecoderTest {
    /// Verifies that structural frame decoding expands tile syntax and produces a temporal motion field.
    @Test
    void decodeFrameProducesTileRootsAndTemporalMotionField() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, findPayloadForInterBlockWithoutSkipOrIntra(), false);

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);

        assertEquals(1, result.tileCount());
        assertTrue(result.tileRoots(0).length > 0);
        TileDecodeContext.TemporalMotionBlock temporalBlock = result.decodedTemporalMotionField(0).block(0, 0);
        assertNotNull(temporalBlock);
        assertEquals(0, temporalBlock.referenceFrame0());
        assertEquals(InterMotionVector.resolved(MotionVector.zero()), temporalBlock.motionVector0());
    }

    /// Verifies that structural frame decoding captures the final tile-local CDF state.
    @Test
    void decodeFrameCapturesFinalTileCdfState() {
        FrameAssembly assembly = createAssembly(FrameType.INTER, findPayloadForInterBlockWithoutSkipOrIntra(), false);

        FrameSyntaxDecodeResult result = new FrameSyntaxDecoder(null).decode(assembly);

        assertNotEquals(
                CdfContext.createDefault().mutableSkipCdf(0)[0],
                result.finalTileCdfContext(0).mutableSkipCdf(0)[0]
        );
    }

    /// Verifies that reference-frame CDF snapshots seed subsequent tile syntax decoding.
    @Test
    void decodeFrameSeedsTileSyntaxFromReferenceCdfState() {
        CdfContext inheritedCdf = CdfContext.createDefault();
        inheritedCdf.mutableSkipCdf(0)[0] = 32000;
        byte[] payload = findPayloadWithDifferentSkipDecision(inheritedCdf);
        FrameAssembly assembly = createAssembly(FrameType.INTER, payload, false, 8, 8);
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
        assertNotEquals(defaultSkip, seededSkip);
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

    /// Finds a small payload whose first skip decision differs between the default and supplied CDF states.
    ///
    /// @param overriddenCdf the overridden skip CDF to test against the default state
    /// @return a small payload whose first skip decision differs between the default and supplied CDF states
    private static byte[] findPayloadWithDifferentSkipDecision(CdfContext overriddenCdf) {
        for (int first = 0; first < 256; first++) {
            for (int second = 0; second < 256; second++) {
                byte[] payload = new byte[]{(byte) first, (byte) second, 0x00, 0x00, 0x00};
                CdfContext defaultCdf = CdfContext.createDefault();
                MsacDecoder defaultDecoder = new MsacDecoder(payload, 0, payload.length, false);
                boolean defaultSkip = defaultDecoder.decodeBooleanAdapt(defaultCdf.mutableSkipCdf(0));

                CdfContext inherited = overriddenCdf.copy();
                MsacDecoder inheritedDecoder = new MsacDecoder(payload, 0, payload.length, false);
                boolean inheritedSkip = inheritedDecoder.decodeBooleanAdapt(inherited.mutableSkipCdf(0));
                if (defaultSkip != inheritedSkip) {
                    return payload;
                }
            }
        }
        throw new IllegalStateException("No deterministic payload produced a different inherited skip decision");
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
