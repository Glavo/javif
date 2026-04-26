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
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FilmGrainSynthesizer`.
@NotNullByDefault
final class FilmGrainSynthesizerTest {
    /// Verifies that disabled film grain returns the original pre-grain planes.
    @Test
    void applyReturnsOriginalPlanesWhenFilmGrainIsDisabled() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I400,
                new int[][]{
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128}
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
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );

        DecodedPlanes result = new FilmGrainSynthesizer().apply(decodedPlanes, frameHeader);

        assertSame(decodedPlanes, result);
    }

    /// Verifies that enabled film grain produces one deterministic but separate presentation copy.
    @Test
    void applyProducesDeterministicGrainAppliedCopy() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I420,
                new int[][]{
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128},
                        {128, 128, 128, 128, 128, 128, 128, 128}
                },
                new int[][]{
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128}
                },
                new int[][]{
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128},
                        {128, 128, 128, 128}
                }
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I420,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
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
                PostfilterTestFixtures.createFilmGrainParams(0x1234, false)
        );

        FilmGrainSynthesizer synthesizer = new FilmGrainSynthesizer();
        DecodedPlanes first = synthesizer.apply(decodedPlanes, frameHeader);
        DecodedPlanes second = synthesizer.apply(decodedPlanes, frameHeader);

        assertNotSame(decodedPlanes, first);
        assertEquals(first.lumaPlane().sample(0, 0), second.lumaPlane().sample(0, 0));
        assertEquals(first.lumaPlane().sample(7, 7), second.lumaPlane().sample(7, 7));
        assertEquals(first.chromaUPlane().sample(0, 0), second.chromaUPlane().sample(0, 0));
        assertEquals(first.chromaVPlane().sample(3, 3), second.chromaVPlane().sample(3, 3));
        assertEquals(128, decodedPlanes.lumaPlane().sample(0, 0));
    }

    /// Verifies that restricted-range clipping keeps synthesized samples inside the legal studio
    /// swing bounds while changing at least one unrestricted presentation sample.
    @Test
    void applyClipsSynthesizedSamplesToRestrictedRange() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                AvifPixelFormat.I420,
                new int[][]{
                        {0, 255, 0, 255, 0, 255, 0, 255},
                        {255, 0, 255, 0, 255, 0, 255, 0},
                        {0, 255, 0, 255, 0, 255, 0, 255},
                        {255, 0, 255, 0, 255, 0, 255, 0},
                        {0, 255, 0, 255, 0, 255, 0, 255},
                        {255, 0, 255, 0, 255, 0, 255, 0},
                        {0, 255, 0, 255, 0, 255, 0, 255},
                        {255, 0, 255, 0, 255, 0, 255, 0}
                },
                new int[][]{
                        {0, 255, 0, 255},
                        {255, 0, 255, 0},
                        {0, 255, 0, 255},
                        {255, 0, 255, 0}
                },
                new int[][]{
                        {255, 0, 255, 0},
                        {0, 255, 0, 255},
                        {255, 0, 255, 0},
                        {0, 255, 0, 255}
                }
        );
        FrameHeader unrestrictedFrameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I420,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
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
                PostfilterTestFixtures.createFilmGrainParams(0x5678, false)
        );
        FrameHeader restrictedFrameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I420,
                unrestrictedFrameHeader.loopFilter(),
                unrestrictedFrameHeader.cdef(),
                unrestrictedFrameHeader.restoration(),
                PostfilterTestFixtures.createFilmGrainParams(0x5678, true)
        );

        FilmGrainSynthesizer synthesizer = new FilmGrainSynthesizer();
        DecodedPlanes unrestricted = synthesizer.apply(decodedPlanes, unrestrictedFrameHeader);
        DecodedPlanes restricted = synthesizer.apply(decodedPlanes, restrictedFrameHeader);

        boolean changedByRestrictedClipping = false;
        for (int y = 0; y < restricted.lumaPlane().height(); y++) {
            for (int x = 0; x < restricted.lumaPlane().width(); x++) {
                int restrictedSample = restricted.lumaPlane().sample(x, y);
                assertTrue(restrictedSample >= 16);
                assertTrue(restrictedSample <= 235);
                if (restrictedSample != unrestricted.lumaPlane().sample(x, y)) {
                    changedByRestrictedClipping = true;
                }
            }
        }
        for (int y = 0; y < restricted.chromaUPlane().height(); y++) {
            for (int x = 0; x < restricted.chromaUPlane().width(); x++) {
                int restrictedCbSample = restricted.chromaUPlane().sample(x, y);
                int restrictedCrSample = restricted.chromaVPlane().sample(x, y);
                assertTrue(restrictedCbSample >= 16);
                assertTrue(restrictedCbSample <= 240);
                assertTrue(restrictedCrSample >= 16);
                assertTrue(restrictedCrSample <= 240);
                if (restrictedCbSample != unrestricted.chromaUPlane().sample(x, y)
                        || restrictedCrSample != unrestricted.chromaVPlane().sample(x, y)) {
                    changedByRestrictedClipping = true;
                }
            }
        }

        assertTrue(changedByRestrictedClipping);
    }

    /// Verifies that block-based luma grain covers overlapped block rows and columns while keeping
    /// non-visible stride padding unchanged.
    @Test
    void applyPreservesPlanePaddingAcrossOverlappedBlockRows() {
        int width = 35;
        int height = 34;
        int stride = 40;
        short[] samples = new short[stride * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < stride; x++) {
                samples[y * stride + x] = (short) (x < width ? 128 + ((x * 11 + y * 7) & 0x1FF) : 999);
            }
        }
        DecodedPlanes decodedPlanes = new DecodedPlanes(
                10,
                AvifPixelFormat.I400,
                width,
                height,
                width,
                height,
                new DecodedPlane(width, height, stride, samples),
                null,
                null
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I400,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
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
                new FrameHeader.FilmGrainParams(
                        true,
                        0x3456,
                        true,
                        -1,
                        new FrameHeader.FilmGrainPoint[]{
                                new FrameHeader.FilmGrainPoint(0, 24),
                                new FrameHeader.FilmGrainPoint(128, 96),
                                new FrameHeader.FilmGrainPoint(255, 32)
                        },
                        false,
                        new FrameHeader.FilmGrainPoint[0],
                        new FrameHeader.FilmGrainPoint[0],
                        9,
                        1,
                        new int[]{3, -2, 5, 1},
                        new int[0],
                        new int[0],
                        7,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        true,
                        false
                )
        );

        DecodedPlanes result = new FilmGrainSynthesizer().apply(decodedPlanes, frameHeader);

        assertTrue(countChangedVisibleSamples(decodedPlanes.lumaPlane(), result.lumaPlane()) > 16);
        assertPaddingEquals(999, result.lumaPlane(), width, stride);
    }

    /// Verifies that explicit chroma scaling works without luma scaling points.
    @Test
    void applySupportsExplicitChromaGrainWithoutLumaScalingPoints() {
        DecodedPlanes decodedPlanes = PostfilterTestFixtures.createDecodedPlanes(
                10,
                AvifPixelFormat.I444,
                new int[][]{
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512},
                        {512, 512, 512, 512, 512, 512, 512, 512}
                },
                new int[][]{
                        {256, 320, 384, 448, 512, 576, 640, 704},
                        {264, 328, 392, 456, 520, 584, 648, 712},
                        {272, 336, 400, 464, 528, 592, 656, 720},
                        {280, 344, 408, 472, 536, 600, 664, 728},
                        {288, 352, 416, 480, 544, 608, 672, 736},
                        {296, 360, 424, 488, 552, 616, 680, 744},
                        {304, 368, 432, 496, 560, 624, 688, 752},
                        {312, 376, 440, 504, 568, 632, 696, 760}
                },
                new int[][]{
                        {760, 696, 632, 568, 504, 440, 376, 312},
                        {752, 688, 624, 560, 496, 432, 368, 304},
                        {744, 680, 616, 552, 488, 424, 360, 296},
                        {736, 672, 608, 544, 480, 416, 352, 288},
                        {728, 664, 600, 536, 472, 408, 344, 280},
                        {720, 656, 592, 528, 464, 400, 336, 272},
                        {712, 648, 584, 520, 456, 392, 328, 264},
                        {704, 640, 576, 512, 448, 384, 320, 256}
                }
        );
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                AvifPixelFormat.I444,
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
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
                new FrameHeader.FilmGrainParams(
                        true,
                        0x4567,
                        true,
                        -1,
                        new FrameHeader.FilmGrainPoint[0],
                        false,
                        new FrameHeader.FilmGrainPoint[]{
                                new FrameHeader.FilmGrainPoint(0, 48),
                                new FrameHeader.FilmGrainPoint(255, 96)
                        },
                        new FrameHeader.FilmGrainPoint[]{
                                new FrameHeader.FilmGrainPoint(0, 96),
                                new FrameHeader.FilmGrainPoint(255, 48)
                        },
                        8,
                        1,
                        new int[0],
                        new int[]{2, -1, 3, -2},
                        new int[]{-2, 3, -1, 2},
                        7,
                        1,
                        64,
                        16,
                        0,
                        64,
                        -16,
                        0,
                        false,
                        false
                )
        );

        DecodedPlanes result = new FilmGrainSynthesizer().apply(decodedPlanes, frameHeader);

        assertEquals(0, countChangedVisibleSamples(decodedPlanes.lumaPlane(), result.lumaPlane()));
        assertTrue(countChangedVisibleSamples(decodedPlanes.chromaUPlane(), result.chromaUPlane()) > 0);
        assertTrue(countChangedVisibleSamples(decodedPlanes.chromaVPlane(), result.chromaVPlane()) > 0);
    }

    /// Counts changed visible samples between two planes.
    ///
    /// @param expected the original plane
    /// @param actual the compared plane
    /// @return the changed visible sample count
    private static int countChangedVisibleSamples(DecodedPlane expected, DecodedPlane actual) {
        int changed = 0;
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                if (expected.sample(x, y) != actual.sample(x, y)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /// Asserts that non-visible stride padding keeps the supplied sentinel value.
    ///
    /// @param sentinel the expected padding sentinel
    /// @param plane the output plane to inspect
    /// @param width the visible width
    /// @param stride the physical stride
    private static void assertPaddingEquals(int sentinel, DecodedPlane plane, int width, int stride) {
        short[] samples = plane.samples();
        for (int y = 0; y < plane.height(); y++) {
            for (int x = width; x < stride; x++) {
                assertEquals(sentinel, samples[y * stride + x] & 0xFFFF);
            }
        }
    }
}
