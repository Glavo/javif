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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileDecodeContext;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for reference-surface snapshots.
@NotNullByDefault
final class ReferenceSurfaceSnapshotTest {
    /// Verifies that a reference-surface snapshot retains one coherent frame snapshot.
    @Test
    void referenceSurfaceSnapshotRetainsFrameState() {
        FrameHeader frameHeader = createFrameHeader();
        FrameSyntaxDecodeResult syntaxResult = createFrameSyntaxDecodeResult(frameHeader);
        DecodedPlanes decodedPlanes = new DecodedPlanes(
                8,
                PixelFormat.I400,
                8,
                8,
                8,
                8,
                new DecodedPlane(8, 8, 8, new short[64]),
                null,
                null
        );

        ReferenceSurfaceSnapshot snapshot = new ReferenceSurfaceSnapshot(frameHeader, syntaxResult, decodedPlanes);

        assertSame(frameHeader, snapshot.frameHeader());
        assertSame(syntaxResult, snapshot.frameSyntaxDecodeResult());
        assertSame(decodedPlanes, snapshot.decodedPlanes());
        assertEquals(1, snapshot.frameSyntaxDecodeResult().tileCount());
    }

    /// Verifies that a reference-surface snapshot rejects mismatched frame headers.
    @Test
    void referenceSurfaceSnapshotRejectsMismatchedFrameHeaders() {
        FrameHeader assemblyHeader = createFrameHeader();
        FrameSyntaxDecodeResult syntaxResult = createFrameSyntaxDecodeResult(assemblyHeader);
        DecodedPlanes decodedPlanes = new DecodedPlanes(
                8,
                PixelFormat.I400,
                8,
                8,
                8,
                8,
                new DecodedPlane(8, 8, 8, new short[64]),
                null,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ReferenceSurfaceSnapshot(createFrameHeader(), syntaxResult, decodedPlanes)
        );
    }

    /// Creates one minimal structural frame-decode result for snapshot tests.
    ///
    /// @param frameHeader the frame header to embed in the assembly
    /// @return one minimal structural frame-decode result for snapshot tests
    private static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(FrameHeader frameHeader) {
        FrameAssembly assembly = new FrameAssembly(createSequenceHeader(), frameHeader, 0, 0);
        assembly.addTileGroup(
                new ObuPacket(new ObuHeader(ObuType.TILE_GROUP, false, true, 0, 0), new byte[0], 0, 0),
                new TileGroupHeader(false, 0, 0, 1),
                0,
                0,
                new TileBitstream[]{new TileBitstream(0, new byte[0], 0, 0)}
        );
        return new FrameSyntaxDecodeResult(
                assembly,
                new TilePartitionTreeReader.Node[][]{new TilePartitionTreeReader.Node[0]},
                new TileDecodeContext.TemporalMotionField[]{new TileDecodeContext.TemporalMotionField(1, 1)}
        );
    }

    /// Creates one minimal reduced-still-picture sequence header for snapshot tests.
    ///
    /// @return one minimal reduced-still-picture sequence header for snapshot tests
    private static SequenceHeader createSequenceHeader() {
        return new SequenceHeader(
                0,
                8,
                8,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(0, 0, 0, 0, false, false, false, null)
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
                        false,
                        false,
                        2,
                        2,
                        2,
                        true,
                        PixelFormat.I400,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates one minimal key-frame header for snapshot tests.
    ///
    /// @return one minimal key-frame header for snapshot tests
    private static FrameHeader createFrameHeader() {
        return new FrameHeader(
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
                false,
                false,
                7,
                0,
                0xFF,
                new FrameHeader.FrameSize(8, 8, 8, 8, 8),
                new FrameHeader.SuperResolutionInfo(false, 8),
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
                false
        );
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
