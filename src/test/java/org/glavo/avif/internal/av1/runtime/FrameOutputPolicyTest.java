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

import org.glavo.avif.decode.Av1DecoderConfig;
import org.glavo.avif.decode.DecodeFrameType;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for runtime output-policy helpers.
@NotNullByDefault
final class FrameOutputPolicyTest {
    /// Verifies that invisible current frames stay suppressed unless the decoder explicitly exposes them.
    @Test
    void shouldOutputFrameSuppressesInvisibleFramesUnlessConfiguredOtherwise() {
        FrameHeader invisibleKeyFrame = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, false, 0x01);

        assertFalse(FrameOutputPolicy.shouldOutputFrame(invisibleKeyFrame, config(false, DecodeFrameType.ALL)));
        assertTrue(FrameOutputPolicy.shouldOutputFrame(invisibleKeyFrame, config(true, DecodeFrameType.KEY)));
    }

    /// Verifies that current-frame output obeys the configured decode-frame-type filter.
    @Test
    void shouldOutputFrameMatchesDecodeFrameTypeFilter() {
        FrameHeader keyFrame = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, true, 0x00);
        FrameHeader intraFrame = RuntimeTestFixtures.createFrameHeader(FrameType.INTRA, true, 0x00);
        FrameHeader interFrame = RuntimeTestFixtures.createFrameHeader(FrameType.INTER, true, 0x00);
        FrameHeader switchReferenceFrame = RuntimeTestFixtures.createFrameHeader(FrameType.SWITCH, true, 0x02);

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, DecodeFrameType.ALL)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(switchReferenceFrame, config(false, DecodeFrameType.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(interFrame, config(false, DecodeFrameType.REFERENCE)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, DecodeFrameType.INTRA)));
        assertTrue(FrameOutputPolicy.shouldOutputFrame(intraFrame, config(false, DecodeFrameType.INTRA)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(interFrame, config(false, DecodeFrameType.INTRA)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, DecodeFrameType.KEY)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(intraFrame, config(false, DecodeFrameType.KEY)));
    }

    /// Verifies that existing-frame output ignores `showFrame` visibility but still applies frame-type filters.
    @Test
    void shouldOutputExistingFrameIgnoresVisibilityButMatchesDecodeFrameTypeFilter() {
        FrameHeader hiddenKeyFrame = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, false, 0x00);
        FrameHeader hiddenInterFrame = RuntimeTestFixtures.createFrameHeader(FrameType.INTER, false, 0x00);
        FrameHeader hiddenReferenceSwitchFrame = RuntimeTestFixtures.createFrameHeader(FrameType.SWITCH, false, 0x04);

        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenKeyFrame, config(false, DecodeFrameType.ALL)));
        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenReferenceSwitchFrame, config(false, DecodeFrameType.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputExistingFrame(hiddenInterFrame, config(false, DecodeFrameType.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputExistingFrame(hiddenInterFrame, config(false, DecodeFrameType.INTRA)));
        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenKeyFrame, config(false, DecodeFrameType.KEY)));
    }

    /// Verifies that film-grain synthesis is required only when both the configuration and the
    /// normalized frame-header state request it.
    @Test
    void requiresFilmGrainSynthesisMatchesConfigurationAndNormalizedFrameState() {
        FrameHeader grainFrame = RuntimeTestFixtures.createFrameHeaderWithFilmGrain(FrameType.KEY, true, 0x01, true);
        FrameHeader plainFrame = RuntimeTestFixtures.createFrameHeaderWithFilmGrain(FrameType.KEY, true, 0x01, false);

        assertTrue(FrameOutputPolicy.requiresFilmGrainSynthesis(grainFrame, config(false, DecodeFrameType.ALL)));
        assertFalse(FrameOutputPolicy.requiresFilmGrainSynthesis(grainFrame, Av1DecoderConfig.builder()
                .applyFilmGrain(false)
                .build()));
        assertFalse(FrameOutputPolicy.requiresFilmGrainSynthesis(plainFrame, config(false, DecodeFrameType.ALL)));
    }

    /// Creates one immutable decoder configuration for runtime output-policy checks.
    ///
    /// @param outputInvisibleFrames whether invisible current frames should be exposed
    /// @param decodeFrameType the configured frame-type filter
    /// @return one immutable decoder configuration for runtime output-policy checks
    private static Av1DecoderConfig config(boolean outputInvisibleFrames, DecodeFrameType decodeFrameType) {
        return Av1DecoderConfig.builder()
                .outputInvisibleFrames(outputInvisibleFrames)
                .decodeFrameType(decodeFrameType)
                .build();
    }
}
