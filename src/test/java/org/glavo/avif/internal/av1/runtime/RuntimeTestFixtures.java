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
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.AvifPixelFormat;
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
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;

/// Shared lightweight fixtures for runtime-package unit tests.
@NotNullByDefault
final class RuntimeTestFixtures {
    /// Prevents instantiation.
    private RuntimeTestFixtures() {
    }

    /// Creates one minimal frame header with caller-controlled output-policy fields.
    ///
    /// @param frameType the AV1 frame type to expose through the header
    /// @param showFrame whether the frame is shown immediately
    /// @param refreshFrameFlags the refresh-frame bitset carried by the header
    /// @return one minimal frame header with caller-controlled output-policy fields
    static FrameHeader createFrameHeader(FrameType frameType, boolean showFrame, int refreshFrameFlags) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                frameType,
                showFrame,
                true,
                true,
                false,
                false,
                false,
                false,
                7,
                0,
                refreshFrameFlags,
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

    /// Creates one minimal frame header with caller-controlled output-policy fields and normalized
    /// film-grain state.
    ///
    /// @param frameType the AV1 frame type to expose through the header
    /// @param showFrame whether the frame is shown immediately
    /// @param refreshFrameFlags the refresh-frame bitset carried by the header
    /// @param applyGrain whether normalized film-grain state should request synthesis
    /// @return one minimal frame header with caller-controlled output-policy fields and film grain
    static FrameHeader createFrameHeaderWithFilmGrain(
            FrameType frameType,
            boolean showFrame,
            int refreshFrameFlags,
            boolean applyGrain
    ) {
        FrameHeader baseFrameHeader = createFrameHeader(frameType, showFrame, refreshFrameFlags);
        return new FrameHeader(
                baseFrameHeader.temporalId(),
                baseFrameHeader.spatialId(),
                baseFrameHeader.showExistingFrame(),
                baseFrameHeader.existingFrameIndex(),
                baseFrameHeader.frameId(),
                baseFrameHeader.framePresentationDelay(),
                baseFrameHeader.frameType(),
                baseFrameHeader.showFrame(),
                baseFrameHeader.showableFrame(),
                baseFrameHeader.errorResilientMode(),
                baseFrameHeader.disableCdfUpdate(),
                baseFrameHeader.allowScreenContentTools(),
                baseFrameHeader.forceIntegerMotionVectors(),
                baseFrameHeader.frameSizeOverride(),
                baseFrameHeader.primaryRefFrame(),
                baseFrameHeader.frameOffset(),
                baseFrameHeader.refreshFrameFlags(),
                baseFrameHeader.frameSize(),
                baseFrameHeader.superResolution(),
                baseFrameHeader.allowIntrabc(),
                baseFrameHeader.refreshContext(),
                baseFrameHeader.tiling(),
                baseFrameHeader.quantization(),
                baseFrameHeader.segmentation(),
                baseFrameHeader.delta(),
                baseFrameHeader.allLossless(),
                baseFrameHeader.loopFilter(),
                baseFrameHeader.cdef(),
                baseFrameHeader.restoration(),
                baseFrameHeader.transformMode(),
                baseFrameHeader.reducedTransformSet(),
                applyGrain
                        ? new FrameHeader.FilmGrainParams(
                        true,
                        123,
                        true,
                        -1,
                        new FrameHeader.FilmGrainPoint[0],
                        false,
                        new FrameHeader.FilmGrainPoint[0],
                        new FrameHeader.FilmGrainPoint[0],
                        8,
                        0,
                        new int[0],
                        new int[0],
                        new int[0],
                        6,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false
                )
                        : FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates one minimal single-pixel monochrome decoded-plane snapshot.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param sample the stored unsigned luma sample value
    /// @return one minimal single-pixel monochrome decoded-plane snapshot
    static DecodedPlanes createDecodedPlanes(int bitDepth, int sample) {
        return new DecodedPlanes(
                bitDepth,
                AvifPixelFormat.I400,
                1,
                1,
                1,
                1,
                new DecodedPlane(1, 1, 1, new short[]{(short) sample}),
                null,
                null
        );
    }

    /// Creates one minimal structural frame-decode result bound to the supplied frame header.
    ///
    /// @param frameHeader the frame header to embed in the assembly
    /// @return one minimal structural frame-decode result bound to the supplied frame header
    static FrameSyntaxDecodeResult createFrameSyntaxDecodeResult(FrameHeader frameHeader) {
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

    /// Creates one minimal stored reference-surface snapshot.
    ///
    /// @param frameHeader the frame header that owns the stored surface
    /// @param bitDepth the decoded bit depth of the stored surface
    /// @param sample the stored unsigned luma sample value
    /// @return one minimal stored reference-surface snapshot
    static ReferenceSurfaceSnapshot createReferenceSurfaceSnapshot(FrameHeader frameHeader, int bitDepth, int sample) {
        FrameSyntaxDecodeResult syntaxDecodeResult = createFrameSyntaxDecodeResult(frameHeader);
        return new ReferenceSurfaceSnapshot(frameHeader, syntaxDecodeResult, createDecodedPlanes(bitDepth, sample));
    }

    /// Creates one minimal reduced-still-picture sequence header for fixture assemblies.
    ///
    /// @return one minimal reduced-still-picture sequence header for fixture assemblies
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
                        AvifPixelFormat.I400,
                        0,
                        true,
                        true,
                        false
                )
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
