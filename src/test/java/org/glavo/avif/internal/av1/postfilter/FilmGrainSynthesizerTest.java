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
}
