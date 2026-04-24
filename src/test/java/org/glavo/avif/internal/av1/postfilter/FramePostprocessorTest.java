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
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests for `FramePostprocessor`.
@NotNullByDefault
final class FramePostprocessorTest {
    /// Verifies that the current postfilter pipeline preserves samples while freezing stage order.
    @Test
    void postprocessPreservesCurrentDecodedSamples() {
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
                        new int[]{8, 4},
                        2,
                        1,
                        3,
                        true,
                        true,
                        new int[]{1, 0, 0, 0, -1, 0, -1, -1},
                        new int[]{0, 0}
                ),
                new FrameHeader.CdefInfo(3, 1, new int[]{4, 8}, new int[]{2, 6}),
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.SWITCHABLE,
                                FrameHeader.RestorationType.WIENER,
                                FrameHeader.RestorationType.SELF_GUIDED
                        },
                        8,
                        7
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
}
