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
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Integration tests for the first-pixel `FrameReconstructor` path.
@NotNullByDefault
final class FrameReconstructorIntegrationTest {
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

    /// Verifies that unsupported non-zero residual syntax still fails cleanly during reconstruction.
    @Test
    void rejectsNonZeroResidualLeaf() {
        FrameSyntaxDecodeResult syntaxDecodeResult = createSyntheticResult(PixelFormat.I400, createLeaf(false, false));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FrameReconstructor().reconstruct(syntaxDecodeResult)
        );

        assertEquals("Non-zero residual reconstruction is not implemented yet", exception.getMessage());
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
        FrameAssembly assembly = createAssembly(pixelFormat);
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

    /// Creates one synthetic single-tile frame assembly.
    ///
    /// @param pixelFormat the synthetic decoded chroma layout
    /// @return one synthetic single-tile frame assembly
    private static FrameAssembly createAssembly(PixelFormat pixelFormat) {
        boolean monochrome = pixelFormat == PixelFormat.I400;
        SequenceHeader sequenceHeader = new SequenceHeader(
                0,
                8,
                8,
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
                new FrameHeader.FrameSize(8, 8, 8, 8, 8),
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
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return assembly;
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
