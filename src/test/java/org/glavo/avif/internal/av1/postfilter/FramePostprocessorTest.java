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

import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FramePostprocessor`.
@NotNullByDefault
final class FramePostprocessorTest {
    /// Verifies that inactive in-loop filters preserve samples while freezing stage order.
    @Test
    void postprocessPreservesDecodedSamplesWhenInLoopFiltersAreInactive() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                PixelFormat.I420,
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
                PixelFormat.I420,
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
                PixelFormat.I400,
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
                PixelFormat.I400,
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

    /// Verifies that active CDEF fails explicitly when decoded block indices are unavailable.
    @Test
    void postprocessRejectsActiveCdefWithoutDecodedBlockIndex() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                PixelFormat.I400,
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
                PixelFormat.I400,
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
                PixelFormat.I400,
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
                PixelFormat.I400,
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

    /// Verifies that active loop filtering fails explicitly when decoded block edges are unavailable.
    @Test
    void postprocessRejectsActiveLoopFilter() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                PixelFormat.I400,
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
                PixelFormat.I400,
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

    /// Verifies that active loop restoration remains a stable explicit unsupported boundary.
    @Test
    void postprocessRejectsActiveRestoration() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                PixelFormat.I400,
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
                PixelFormat.I400,
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
}
