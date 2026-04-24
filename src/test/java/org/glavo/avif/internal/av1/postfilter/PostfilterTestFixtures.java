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
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Shared lightweight fixtures for postfilter-package unit tests.
@NotNullByDefault
final class PostfilterTestFixtures {
    /// Prevents instantiation.
    private PostfilterTestFixtures() {
    }

    /// Creates one decoded-plane snapshot from row-major sample matrices.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @param lumaSamples the luma sample raster
    /// @param chromaUSamples the chroma-U sample raster, or `null`
    /// @param chromaVSamples the chroma-V sample raster, or `null`
    /// @return one immutable decoded-plane snapshot
    static DecodedPlanes createDecodedPlanes(
            PixelFormat pixelFormat,
            int[][] lumaSamples,
            @Nullable int[][] chromaUSamples,
            @Nullable int[][] chromaVSamples
    ) {
        int width = lumaSamples[0].length;
        int height = lumaSamples.length;
        return new DecodedPlanes(
                8,
                pixelFormat,
                width,
                height,
                width,
                height,
                createPlane(lumaSamples),
                chromaUSamples != null ? createPlane(chromaUSamples) : null,
                chromaVSamples != null ? createPlane(chromaVSamples) : null
        );
    }

    /// Creates one minimal frame header with caller-supplied postfilter and grain state.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @param loopFilter the loop-filter state
    /// @param cdef the CDEF state
    /// @param restoration the restoration state
    /// @param filmGrain the normalized film-grain state
    /// @return one minimal frame header with caller-supplied postfilter and grain state
    static FrameHeader createFrameHeader(
            PixelFormat pixelFormat,
            FrameHeader.LoopFilterInfo loopFilter,
            FrameHeader.CdefInfo cdef,
            FrameHeader.RestorationInfo restoration,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.KEY,
                true,
                true,
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
                loopFilter,
                cdef,
                restoration,
                FrameHeader.TransformMode.LARGEST,
                false,
                filmGrain
        );
    }

    /// Creates one normalized film-grain state with visible effect for tests.
    ///
    /// @param grainSeed the deterministic grain seed
    /// @param restrictedRange whether output should be clipped to the restricted range
    /// @return one normalized film-grain state with visible effect for tests
    static FrameHeader.FilmGrainParams createFilmGrainParams(int grainSeed, boolean restrictedRange) {
        return new FrameHeader.FilmGrainParams(
                true,
                grainSeed,
                true,
                -1,
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(0, 32),
                        new FrameHeader.FilmGrainPoint(255, 96)
                },
                true,
                new FrameHeader.FilmGrainPoint[0],
                new FrameHeader.FilmGrainPoint[0],
                8,
                0,
                new int[0],
                new int[]{0},
                new int[]{0},
                6,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                restrictedRange
        );
    }

    /// Creates one disabled normalized film-grain state.
    ///
    /// @return one disabled normalized film-grain state
    static FrameHeader.FilmGrainParams disabledFilmGrain() {
        return FrameHeader.FilmGrainParams.disabled();
    }

    /// Creates one immutable decoded plane from row-major integer samples.
    ///
    /// @param samples the row-major integer samples
    /// @return one immutable decoded plane
    private static DecodedPlane createPlane(int[][] samples) {
        int height = samples.length;
        int width = samples[0].length;
        short[] packed = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                packed[y * width + x] = (short) samples[y][x];
            }
        }
        return new DecodedPlane(width, height, width, packed);
    }

    /// Creates one default segmentation-feature table.
    ///
    /// @return one default segmentation-feature table
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int segment = 0; segment < segments.length; segment++) {
            segments[segment] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }
}
