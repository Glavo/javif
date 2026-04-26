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

import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.RestorationUnit;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FramePostprocessor`.
@NotNullByDefault
final class FramePostprocessorTest {
    /// The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order.
    private static final int @Unmodifiable [] @Unmodifiable [] SELF_GUIDED_PARAMS = {
            {2, 140, 1, 3236},
            {2, 112, 1, 2158},
            {2, 93, 1, 1618},
            {2, 80, 1, 1438},
            {2, 70, 1, 1295},
            {2, 58, 1, 1177},
            {2, 47, 1, 1079},
            {2, 37, 1, 996},
            {2, 30, 1, 925},
            {2, 25, 1, 863},
            {0, -1, 1, 2589},
            {0, -1, 1, 1618},
            {0, -1, 1, 1177},
            {0, -1, 1, 925},
            {2, 56, 0, -1},
            {2, 22, 0, -1}
    };

    /// The maximum self-guided variance table index.
    private static final int SELF_GUIDED_MAX_Z = 255;

    /// Verifies that inactive in-loop filters preserve samples while freezing stage order.
    @Test
    void postprocessPreservesDecodedSamplesWhenInLoopFiltersAreInactive() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I420,
                new int[][]{
                        {120, 121, 122, 123, 124, 125, 126, 127},
                        {128, 129, 130, 131, 132, 133, 134, 135},
                        {136, 137, 138, 139, 140, 141, 142, 143},
                        {144, 145, 146, 147, 148, 149, 150, 151},
                        {152, 153, 154, 155, 156, 157, 158, 159},
                        {160, 161, 162, 163, 164, 165, 166, 167},
                        {168, 169, 170, 171, 172, 173, 174, 175},
                        {176, 177, 178, 179, 180, 181, 182, 183}
                },
                new int[][]{
                        {90, 91, 92, 93},
                        {94, 95, 96, 97},
                        {98, 99, 100, 101},
                        {102, 103, 104, 105}
                },
                new int[][]{
                        {150, 151, 152, 153},
                        {154, 155, 156, 157},
                        {158, 159, 160, 161},
                        {162, 163, 164, 165}
                }
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I420,
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
                PostfilterTestFixtures.disabledFilmGrain()
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader);

        assertSame(decodedPlanes, postprocessed);
        assertEquals(120, postprocessed.lumaPlane().sample(0, 0));
        assertEquals(183, postprocessed.lumaPlane().sample(7, 7));
        assertEquals(90, postprocessed.chromaUPlane().sample(0, 0));
        assertEquals(165, postprocessed.chromaVPlane().sample(3, 3));
    }

    /// Verifies that active CDEF applies a pixel-changing pass using decoded block CDEF indices.
    @Test
    void postprocessAppliesActiveCdefFromDecodedBlockIndex() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 64, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                new FrameHeader.CdefInfo(6, 0, new int[]{60}, new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, 0);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) < 64);
        assertEquals(64, decodedPlanes.lumaPlane().sample(3, 3));
    }

    /// Verifies that skipped blocks that omit CDEF side syntax use the AV1 default CDEF index.
    @Test
    void postprocessUsesDefaultCdefIndexWhenBlockIndexIsOmitted() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 64, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                new FrameHeader.CdefInfo(6, 2, new int[]{60, 0, 0, 0}, new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, -1);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) < 64);
        assertEquals(64, decodedPlanes.lumaPlane().sample(3, 3));
    }

    /// Verifies that CDEF preserves units that contain no non-skipped blocks.
    @Test
    void postprocessSkipsFullySkippedCdefUnit() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 64, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                new FrameHeader.CdefInfo(6, 0, new int[]{60}, new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult =
                PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, 0, true);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertSame(decodedPlanes, postprocessed);
        assertEquals(64, postprocessed.lumaPlane().sample(3, 3));
    }

    /// Verifies that CDEF strength and output range honor high-bit-depth planes.
    @Test
    void postprocessAppliesCdefToHighBitDepthSamples() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                10,
                AvifPixelFormat.I400,
                new int[][]{
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 768, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                new FrameHeader.CdefInfo(6, 0, new int[]{63}, new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, 0);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        int filteredCenter = postprocessed.lumaPlane().sample(3, 3);
        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(filteredCenter < 768);
        assertTrue(filteredCenter > 255);
        assertEquals(768, decodedPlanes.lumaPlane().sample(3, 3));
    }

    /// Verifies that active CDEF fails explicitly when decoded block indices are unavailable.
    @Test
    void postprocessRejectsActiveCdefWithoutDecodedBlockIndex() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 64, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                new FrameHeader.CdefInfo(6, 0, new int[]{60}, new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FramePostprocessor().postprocess(decodedPlanes, frameHeader)
        );

        assertTrue(exception.getMessage().contains("CDEF"));
    }

    /// Verifies that active loop filtering uses decoded block and transform edges.
    @Test
    void postprocessAppliesActiveLoopFilterFromDecodedEdges() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44},
                        {40, 40, 40, 40, 44, 44, 44, 44}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(
                        new int[]{12, 0},
                        0,
                        0,
                        0,
                        true,
                        true,
                        new int[]{0, 0, 0, 0, 0, 0, 0, 0},
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
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult =
                PostfilterTestFixtures.createVerticalSplitLeafSyntaxResult(frameHeader, 0, 0);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) > decodedPlanes.lumaPlane().sample(3, 3));
        assertTrue(postprocessed.lumaPlane().sample(4, 3) < decodedPlanes.lumaPlane().sample(4, 3));
        assertEquals(40, decodedPlanes.lumaPlane().sample(3, 3));
        assertEquals(44, decodedPlanes.lumaPlane().sample(4, 3));
    }

    /// Verifies that active loop filtering ignores 4x4-grid lines that are neither block nor transform edges.
    @Test
    void postprocessDoesNotApplyLoopFilterAwayFromDecodedEdges() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {40, 40, 40, 40, 40, 40, 40, 40},
                        {40, 40, 40, 40, 40, 40, 40, 40},
                        {40, 40, 40, 40, 40, 40, 40, 40},
                        {40, 40, 40, 40, 40, 40, 40, 40},
                        {48, 48, 48, 48, 48, 48, 48, 48},
                        {48, 48, 48, 48, 48, 48, 48, 48},
                        {48, 48, 48, 48, 48, 48, 48, 48},
                        {48, 48, 48, 48, 48, 48, 48, 48}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(
                        new int[]{16, 16},
                        0,
                        0,
                        0,
                        false,
                        false,
                        new int[]{0, 0, 0, 0, 0, 0, 0, 0},
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
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult =
                PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, 0);

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        for (int y = 0; y < decodedPlanes.codedHeight(); y++) {
            for (int x = 0; x < decodedPlanes.codedWidth(); x++) {
                assertEquals(decodedPlanes.lumaPlane().sample(x, y), postprocessed.lumaPlane().sample(x, y));
            }
        }
    }

    /// Verifies the exact AV1 8-tap luma loop filter on flat 8x8 transform edges.
    @Test
    void postprocessAppliesEightTapLumaLoopFilterOnFlatEdges() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                repeatedRows(new int[]{40, 40, 40, 40, 40, 40, 40, 40, 48, 48, 48, 48, 48, 48, 48, 48}, 8),
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(
                        new int[]{16, 0},
                        0,
                        0,
                        0,
                        false,
                        false,
                        new int[]{0, 0, 0, 0, 0, 0, 0, 0},
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
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createVerticalSplitLeafSyntaxResult(
                frameHeader,
                BlockSize.SIZE_8X8,
                TransformSize.TX_8X8,
                null
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        int[] expectedRow = new int[]{40, 40, 40, 40, 40, 41, 42, 43, 45, 46, 47, 48, 48, 48, 48, 48};
        for (int y = 0; y < decodedPlanes.codedHeight(); y++) {
            for (int x = 0; x < decodedPlanes.codedWidth(); x++) {
                assertEquals(expectedRow[x], postprocessed.lumaPlane().sample(x, y));
            }
        }
    }

    /// Verifies the exact AV1 16-tap luma loop filter on flat 16x16 transform edges.
    @Test
    void postprocessAppliesSixteenTapLumaLoopFilterOnFlatEdges() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                repeatedRows(
                        new int[]{
                                40, 40, 40, 40, 40, 40, 40, 40,
                                40, 40, 40, 40, 40, 40, 40, 40,
                                48, 48, 48, 48, 48, 48, 48, 48,
                                48, 48, 48, 48, 48, 48, 48, 48
                        },
                        16
                ),
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(
                        new int[]{16, 0},
                        0,
                        0,
                        0,
                        false,
                        false,
                        new int[]{0, 0, 0, 0, 0, 0, 0, 0},
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
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createVerticalSplitLeafSyntaxResult(
                frameHeader,
                BlockSize.SIZE_16X16,
                TransformSize.TX_16X16,
                null
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        int[] expectedRow = new int[]{
                40, 40, 40, 40, 40, 40, 40, 40,
                40, 40, 41, 41, 42, 42, 43, 44,
                45, 46, 46, 47, 47, 48, 48, 48,
                48, 48, 48, 48, 48, 48, 48, 48
        };
        for (int y = 0; y < decodedPlanes.codedHeight(); y++) {
            for (int x = 0; x < decodedPlanes.codedWidth(); x++) {
                assertEquals(expectedRow[x], postprocessed.lumaPlane().sample(x, y));
            }
        }
    }

    /// Verifies the exact AV1 6-tap chroma loop filter on flat 8x8 transform edges.
    @Test
    void postprocessAppliesSixTapChromaLoopFilterOnFlatEdges() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I444,
                repeatedRows(new int[]{100, 100, 100, 100, 100, 100, 100, 100,
                        100, 100, 100, 100, 100, 100, 100, 100}, 8),
                repeatedRows(new int[]{60, 60, 60, 60, 60, 60, 60, 60, 68, 68, 68, 68, 68, 68, 68, 68}, 8),
                repeatedRows(new int[]{90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90, 90}, 8)
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I444,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        16,
                        0,
                        0,
                        false,
                        false,
                        new int[]{0, 0, 0, 0, 0, 0, 0, 0},
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
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createVerticalSplitLeafSyntaxResult(
                frameHeader,
                BlockSize.SIZE_8X8,
                TransformSize.TX_8X8,
                TransformSize.TX_8X8
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        int[] expectedChromaRow = new int[]{60, 60, 60, 60, 60, 60, 61, 63, 65, 67, 68, 68, 68, 68, 68, 68};
        for (int y = 0; y < decodedPlanes.codedHeight(); y++) {
            for (int x = 0; x < decodedPlanes.codedWidth(); x++) {
                assertEquals(expectedChromaRow[x], postprocessed.chromaUPlane().sample(x, y));
                assertEquals(decodedPlanes.chromaVPlane().sample(x, y), postprocessed.chromaVPlane().sample(x, y));
            }
        }
    }

    /// Verifies that active loop filtering fails explicitly when decoded block edges are unavailable.
    @Test
    void postprocessRejectsActiveLoopFilter() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32},
                        {32, 32, 32, 32},
                        {32, 32, 32, 32},
                        {32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{4, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
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
                PostfilterTestFixtures.disabledFilmGrain()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FramePostprocessor().postprocess(decodedPlanes, frameHeader)
        );

        assertTrue(exception.getMessage().contains("loop filtering"));
    }

    /// Verifies that active Wiener loop restoration uses decoded restoration-unit coefficients.
    @Test
    void postprocessAppliesActiveWienerRestorationFromDecodedUnit() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 96, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.WIENER,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        6,
                        6
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(
                frameHeader,
                0,
                RestorationUnit.wiener(new int[][]{
                        {0, 0, 20},
                        {0, 0, 0}
                })
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) < 96);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) > 32);
        assertEquals(96, decodedPlanes.lumaPlane().sample(3, 3));
    }

    /// Verifies exact two-stage Wiener restoration while preserving non-tight plane strides.
    @Test
    void postprocessAppliesExactWienerRestorationWithPlaneStride() {
        short[] samples = new short[]{
                20, 24, 28, 32, 36, 777, 777, 777,
                25, 29, 33, 37, 41, 777, 777, 777,
                30, 34, 96, 42, 46, 777, 777, 777,
                35, 39, 43, 47, 51, 777, 777, 777,
                40, 44, 48, 52, 56, 777, 777, 777
        };
        DecodedPlane lumaPlane = new DecodedPlane(5, 5, 8, samples);
        DecodedPlanes decodedPlanes = new DecodedPlanes(
                8,
                AvifPixelFormat.I400,
                5,
                5,
                5,
                5,
                lumaPlane,
                null,
                null
        );
        int @Unmodifiable [] @Unmodifiable [] wienerCoefficients = new int[][]{
                {3, -7, 15},
                {2, -5, 11}
        };
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                                FrameHeader.RestorationType.WIENER,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        3,
                        3
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(
                frameHeader,
                0,
                RestorationUnit.wiener(wienerCoefficients)
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertEquals(8, postprocessed.lumaPlane().stride());
        assertWienerRestoredPlaneEquals(lumaPlane, postprocessed.lumaPlane(), 8, wienerCoefficients);
        short[] restoredSamples = postprocessed.lumaPlane().samples();
        for (int y = 0; y < postprocessed.lumaPlane().height(); y++) {
            for (int x = postprocessed.lumaPlane().width(); x < postprocessed.lumaPlane().stride(); x++) {
                assertEquals(777, restoredSamples[y * postprocessed.lumaPlane().stride() + x] & 0xFFFF);
            }
        }
    }

    /// Verifies that active self-guided loop restoration uses decoded restoration-unit coefficients.
    @Test
    void postprocessAppliesActiveSelfGuidedRestorationFromDecodedUnit() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 96, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32},
                        {32, 32, 32, 32, 32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.SELF_GUIDED,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        6,
                        6
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(
                frameHeader,
                0,
                RestorationUnit.selfGuided(0, new int[]{31, 31})
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) < 96);
        assertTrue(postprocessed.lumaPlane().sample(3, 3) > 32);
        assertEquals(96, decodedPlanes.lumaPlane().sample(3, 3));
    }

    /// Verifies exact AV1 self-guided restoration while preserving non-tight plane strides.
    @Test
    void postprocessAppliesExactSelfGuidedRestorationWithPlaneStride() {
        short[] samples = new short[]{
                120, 128, 136, 144, 152, 777, 777, 777,
                132, 140, 148, 156, 164, 777, 777, 777,
                144, 152, 300, 168, 176, 777, 777, 777,
                156, 164, 172, 180, 188, 777, 777, 777,
                168, 176, 184, 192, 200, 777, 777, 777
        };
        DecodedPlane lumaPlane = new DecodedPlane(5, 5, 8, samples);
        DecodedPlanes decodedPlanes = new DecodedPlanes(
                10,
                AvifPixelFormat.I400,
                5,
                5,
                5,
                5,
                lumaPlane,
                null,
                null
        );
        int @Unmodifiable [] projectionCoefficients = new int[]{31, 31};
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
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
                                FrameHeader.RestorationType.SELF_GUIDED,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        6,
                        6
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult = PostfilterTestFixtures.createSingleLeafSyntaxResult(
                frameHeader,
                0,
                RestorationUnit.selfGuided(0, projectionCoefficients)
        );

        DecodedPlanes postprocessed = new FramePostprocessor().postprocess(decodedPlanes, frameHeader, syntaxDecodeResult);

        assertNotSame(decodedPlanes, postprocessed);
        assertEquals(8, postprocessed.lumaPlane().stride());
        assertSelfGuidedRestoredPlaneEquals(lumaPlane, postprocessed.lumaPlane(), 10, 0, projectionCoefficients);
        short[] restoredSamples = postprocessed.lumaPlane().samples();
        for (int y = 0; y < postprocessed.lumaPlane().height(); y++) {
            for (int x = postprocessed.lumaPlane().width(); x < postprocessed.lumaPlane().stride(); x++) {
                assertEquals(777, restoredSamples[y * postprocessed.lumaPlane().stride() + x] & 0xFFFF);
            }
        }
    }

    /// Verifies that active loop restoration fails explicitly when decoded restoration units are unavailable.
    @Test
    void postprocessRejectsActiveRestoration() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {32, 32, 32, 32},
                        {32, 32, 32, 32},
                        {32, 32, 32, 32},
                        {32, 32, 32, 32}
                },
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.WIENER,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        6,
                        6
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new FramePostprocessor().postprocess(decodedPlanes, frameHeader)
        );

        assertTrue(exception.getMessage().contains("loop restoration"));
    }

    /// Creates a matrix by copying one row multiple times.
    ///
    /// @param row the source row
    /// @param height the target matrix height
    /// @return the repeated-row matrix
    private static int[][] repeatedRows(int[] row, int height) {
        int[][] result = new int[height][row.length];
        for (int y = 0; y < height; y++) {
            System.arraycopy(row, 0, result[y], 0, row.length);
        }
        return result;
    }

    /// Asserts exact AV1 self-guided restoration for every visible plane sample.
    ///
    /// @param source the source plane before restoration
    /// @param actual the restored plane to verify
    /// @param bitDepth the decoded bit depth
    /// @param set the self-guided restoration parameter set
    /// @param projectionCoefficients the decoded self-guided projection coefficients
    private static void assertSelfGuidedRestoredPlaneEquals(
            DecodedPlane source,
            DecodedPlane actual,
            int bitDepth,
            int set,
            int @Unmodifiable [] projectionCoefficients
    ) {
        int maximumSample = (1 << bitDepth) - 1;
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                assertEquals(
                        clamp(
                                expectedSelfGuidedSample(source, bitDepth, set, projectionCoefficients, x, y, y),
                                0,
                                maximumSample
                        ),
                        actual.sample(x, y)
                );
            }
        }
    }

    /// Returns one expected AV1 self-guided-restored sample.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param set the self-guided restoration parameter set
    /// @param projectionCoefficients the decoded self-guided projection coefficients
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @param localY the sample Y coordinate relative to the restoration unit
    /// @return one expected AV1 self-guided-restored sample
    private static int expectedSelfGuidedSample(
            DecodedPlane source,
            int bitDepth,
            int set,
            int @Unmodifiable [] projectionCoefficients,
            int x,
            int y,
            int localY
    ) {
        int @Unmodifiable [] params = SELF_GUIDED_PARAMS[set];
        int base = source.sample(x, y);
        int adjustment = 0;
        if (params[0] != 0 && projectionCoefficients[0] != 0) {
            adjustment += projectionCoefficients[0] * expectedSelfGuidedResidual5(
                    source,
                    bitDepth,
                    params[1],
                    x,
                    y,
                    localY,
                    base
            );
        }
        int radius1Weight = params[2] != 0 ? 128 - projectionCoefficients[0] - projectionCoefficients[1] : 0;
        if (params[2] != 0 && radius1Weight != 0) {
            adjustment += radius1Weight * expectedSelfGuidedResidual3(
                    source,
                    bitDepth,
                    params[3],
                    x,
                    y,
                    base
            );
        }
        return base + round2(adjustment, 11);
    }

    /// Returns one expected 3x3 self-guided residual.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param strength the self-guided strength value
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @param sourceSample the original source sample
    /// @return one expected 3x3 self-guided residual
    private static int expectedSelfGuidedResidual3(
            DecodedPlane source,
            int bitDepth,
            int strength,
            int x,
            int y,
            int sourceSample
    ) {
        int weightedB = eightNeighborProjectionWeight(source, bitDepth, 1, strength, false, x, y);
        int weightedA = eightNeighborProjectionWeight(source, bitDepth, 1, strength, true, x, y);
        return round2(weightedA - weightedB * sourceSample, 9);
    }

    /// Returns one expected 5x5 self-guided residual.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param strength the self-guided strength value
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @param localY the sample Y coordinate relative to the restoration unit
    /// @param sourceSample the original source sample
    /// @return one expected 5x5 self-guided residual
    private static int expectedSelfGuidedResidual5(
            DecodedPlane source,
            int bitDepth,
            int strength,
            int x,
            int y,
            int localY,
            int sourceSample
    ) {
        if ((localY & 1) == 0) {
            int weightedB = sixNeighborPairProjectionWeight(source, bitDepth, strength, false, x, y);
            int weightedA = sixNeighborPairProjectionWeight(source, bitDepth, strength, true, x, y);
            return round2(weightedA - weightedB * sourceSample, 9);
        }
        int weightedB = sixNeighborSingleProjectionWeight(source, bitDepth, strength, false, x, y);
        int weightedA = sixNeighborSingleProjectionWeight(source, bitDepth, strength, true, x, y);
        return round2(weightedA - weightedB * sourceSample, 8);
    }

    /// Returns the dav1d eight-neighbor weighted projection sum.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param radius the self-guided filter radius
    /// @param strength the self-guided strength value
    /// @param aComponent whether to return the A component instead of B
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return the weighted projection sum
    private static int eightNeighborProjectionWeight(
            DecodedPlane source,
            int bitDepth,
            int radius,
            int strength,
            boolean aComponent,
            int x,
            int y
    ) {
        return (expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x, y)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x - 1, y)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x + 1, y)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x, y - 1)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x, y + 1)) * 4
                + (expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x - 1, y - 1)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x + 1, y - 1)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x - 1, y + 1)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, radius, strength, aComponent, x + 1, y + 1)) * 3;
    }

    /// Returns the dav1d paired-row 5x5 weighted projection sum.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param strength the self-guided strength value
    /// @param aComponent whether to return the A component instead of B
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return the weighted projection sum
    private static int sixNeighborPairProjectionWeight(
            DecodedPlane source,
            int bitDepth,
            int strength,
            boolean aComponent,
            int x,
            int y
    ) {
        int topY = y - 1;
        int bottomY = y + 1;
        return (expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x, topY)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x, bottomY)) * 6
                + (expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x - 1, topY)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x + 1, topY)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x - 1, bottomY)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x + 1, bottomY)) * 5;
    }

    /// Returns the dav1d single-row 5x5 weighted projection sum.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param strength the self-guided strength value
    /// @param aComponent whether to return the A component instead of B
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return the weighted projection sum
    private static int sixNeighborSingleProjectionWeight(
            DecodedPlane source,
            int bitDepth,
            int strength,
            boolean aComponent,
            int x,
            int y
    ) {
        return expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x, y) * 6
                + (expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x - 1, y)
                + expectedSelfGuidedProjectionComponent(source, bitDepth, 2, strength, aComponent, x + 1, y)) * 5;
    }

    /// Returns one expected self-guided A or B projection component.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param radius the self-guided filter radius
    /// @param strength the self-guided strength value
    /// @param aComponent whether to return the A component instead of B
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one expected self-guided projection component
    private static int expectedSelfGuidedProjectionComponent(
            DecodedPlane source,
            int bitDepth,
            int radius,
            int strength,
            boolean aComponent,
            int x,
            int y
    ) {
        ExpectedProjection projection = expectedSelfGuidedProjection(source, bitDepth, radius, strength, x, y);
        return aComponent ? projection.a() : projection.b();
    }

    /// Returns one expected self-guided projection.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param radius the self-guided filter radius
    /// @param strength the self-guided strength value
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one expected self-guided projection
    private static ExpectedProjection expectedSelfGuidedProjection(
            DecodedPlane source,
            int bitDepth,
            int radius,
            int strength,
            int x,
            int y
    ) {
        int count = radius == 1 ? 9 : 25;
        int oneByX = radius == 1 ? 455 : 164;
        ExpectedBoxSum box = expectedSelfGuidedBoxSum(source, radius, x, y);
        int bitDepthShift = bitDepth - 8;
        int scaledSumSquares = roundForBitDepth(box.sumSquares(), bitDepthShift * 2);
        int scaledSum = roundForBitDepth(box.sum(), bitDepthShift);
        long variance = Math.max((long) scaledSumSquares * count - (long) scaledSum * scaledSum, 0L);
        int z = (int) Math.min(SELF_GUIDED_MAX_Z, (variance * strength + (1 << 19)) >> 20);
        int xByX = expectedSelfGuidedXByX(z);
        int a = (int) (((long) xByX * box.sum() * oneByX + (1 << 11)) >> 12);
        return new ExpectedProjection(a, xByX);
    }

    /// Returns one expected self-guided box sum.
    ///
    /// @param source the source plane before restoration
    /// @param radius the self-guided filter radius
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one expected self-guided box sum
    private static ExpectedBoxSum expectedSelfGuidedBoxSum(DecodedPlane source, int radius, int x, int y) {
        int sum = 0;
        int sumSquares = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int sampleY = clamp(y + dy, 0, source.height() - 1);
            for (int dx = -radius; dx <= radius; dx++) {
                int sample = source.sample(clamp(x + dx, 0, source.width() - 1), sampleY);
                sum += sample;
                sumSquares += sample * sample;
            }
        }
        return new ExpectedBoxSum(sum, sumSquares);
    }

    /// Rounds one expected self-guided statistic down to the normalized 8-bit domain.
    ///
    /// @param value the source statistic
    /// @param bits the number of bits to remove
    /// @return the rounded statistic
    private static int roundForBitDepth(int value, int bits) {
        if (bits == 0) {
            return value;
        }
        return (value + ((1 << bits) >> 1)) >> bits;
    }

    /// Returns one expected entry from dav1d's `sgr_x_by_x` table.
    ///
    /// @param z the clamped variance index
    /// @return the self-guided reciprocal table value
    private static int expectedSelfGuidedXByX(int z) {
        if (z == SELF_GUIDED_MAX_Z) {
            return 0;
        }
        return Math.min(255, (256 + (z >> 1)) / (z + 1));
    }

    /// Asserts exact AV1 two-stage Wiener restoration for every visible plane sample.
    ///
    /// @param source the source plane before restoration
    /// @param actual the restored plane to verify
    /// @param bitDepth the decoded bit depth
    /// @param coefficients the decoded Wiener coefficients
    private static void assertWienerRestoredPlaneEquals(
            DecodedPlane source,
            DecodedPlane actual,
            int bitDepth,
            int @Unmodifiable [] @Unmodifiable [] coefficients
    ) {
        int @Unmodifiable [] horizontalKernel = wienerKernel(coefficients[0]);
        int @Unmodifiable [] verticalKernel = wienerKernel(coefficients[1]);
        int maximumSample = (1 << bitDepth) - 1;
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                assertEquals(
                        clamp(expectedWienerSample(source, bitDepth, horizontalKernel, verticalKernel, x, y), 0, maximumSample),
                        actual.sample(x, y)
                );
            }
        }
    }

    /// Returns one expected AV1 Wiener-restored sample.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param horizontalKernel the seven-tap horizontal Wiener kernel
    /// @param verticalKernel the seven-tap vertical Wiener kernel
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one expected AV1 Wiener-restored sample
    private static int expectedWienerSample(
            DecodedPlane source,
            int bitDepth,
            int @Unmodifiable [] horizontalKernel,
            int @Unmodifiable [] verticalKernel,
            int x,
            int y
    ) {
        int roundBitsV = 11 - (bitDepth == 12 ? 2 : 0);
        int sum = -(1 << (bitDepth + roundBitsV - 1));
        for (int tap = 0; tap < 7; tap++) {
            int sourceY = clamp(y + tap - 3, 0, source.height() - 1);
            sum += verticalKernel[tap] * expectedWienerHorizontalSample(source, bitDepth, horizontalKernel, x, sourceY);
        }
        return (sum + (1 << (roundBitsV - 1))) >> roundBitsV;
    }

    /// Returns one expected horizontally filtered AV1 Wiener intermediate sample.
    ///
    /// @param source the source plane before restoration
    /// @param bitDepth the decoded bit depth
    /// @param horizontalKernel the seven-tap horizontal Wiener kernel
    /// @param x the sample X coordinate
    /// @param y the sample Y coordinate
    /// @return one expected horizontally filtered AV1 Wiener intermediate sample
    private static int expectedWienerHorizontalSample(
            DecodedPlane source,
            int bitDepth,
            int @Unmodifiable [] horizontalKernel,
            int x,
            int y
    ) {
        int roundBitsH = 3 + (bitDepth == 12 ? 2 : 0);
        int clipLimit = 1 << (bitDepth + 1 + 7 - roundBitsH);
        int sum = 1 << (bitDepth + 6);
        for (int tap = 0; tap < 7; tap++) {
            int sourceX = clamp(x + tap - 3, 0, source.width() - 1);
            sum += horizontalKernel[tap] * source.sample(sourceX, y);
        }
        return clamp((sum + (1 << (roundBitsH - 1))) >> roundBitsH, 0, clipLimit - 1);
    }

    /// Builds one symmetric AV1 seven-tap Wiener filter kernel.
    ///
    /// @param coefficients the three coded Wiener coefficients
    /// @return one symmetric AV1 seven-tap Wiener filter kernel
    private static int @Unmodifiable [] wienerKernel(int @Unmodifiable [] coefficients) {
        int c0 = coefficients[0];
        int c1 = coefficients[1];
        int c2 = coefficients[2];
        return new int[]{
                c0,
                c1,
                c2,
                128 - 2 * (c0 + c1 + c2),
                c2,
                c1,
                c0
        };
    }

    /// Clamps one signed value into one inclusive integer range.
    ///
    /// @param value the candidate value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Rounds one signed value right by the requested number of bits.
    ///
    /// @param value the value to round
    /// @param bits the number of bits to remove
    /// @return the rounded value
    private static int round2(int value, int bits) {
        if (bits == 0) {
            return value;
        }
        return (value + (1 << (bits - 1))) >> bits;
    }

    /// Expected self-guided box sums.
    ///
    /// @param sum the sample sum
    /// @param sumSquares the squared sample sum
    @NotNullByDefault
    private record ExpectedBoxSum(int sum, int sumSquares) {
    }

    /// Expected self-guided projection components.
    ///
    /// @param a the inverted A value
    /// @param b the inverted B value
    @NotNullByDefault
    private record ExpectedProjection(int a, int b) {
    }
}
