// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.av1.Av1DecoderConfig;
import org.glavo.avif.av1.Av1FrameSelection;
import org.glavo.avif.av1.Av1FrameType;
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
        FrameHeader invisibleKeyFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.KEY, false, 0x01);

        assertFalse(FrameOutputPolicy.shouldOutputFrame(invisibleKeyFrame, config(false, Av1FrameSelection.ALL)));
        assertTrue(FrameOutputPolicy.shouldOutputFrame(invisibleKeyFrame, config(true, Av1FrameSelection.KEY)));
    }

    /// Verifies that current-frame output obeys the configured decode-frame-type filter.
    @Test
    void shouldOutputFrameMatchesFrameSelectionFilter() {
        FrameHeader keyFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.KEY, true, 0x00);
        FrameHeader intraFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.INTRA, true, 0x00);
        FrameHeader interFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.INTER, true, 0x00);
        FrameHeader switchReferenceFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.SWITCH, true, 0x02);

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, Av1FrameSelection.ALL)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(switchReferenceFrame, config(false, Av1FrameSelection.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(interFrame, config(false, Av1FrameSelection.REFERENCE)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, Av1FrameSelection.INTRA)));
        assertTrue(FrameOutputPolicy.shouldOutputFrame(intraFrame, config(false, Av1FrameSelection.INTRA)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(interFrame, config(false, Av1FrameSelection.INTRA)));

        assertTrue(FrameOutputPolicy.shouldOutputFrame(keyFrame, config(false, Av1FrameSelection.KEY)));
        assertFalse(FrameOutputPolicy.shouldOutputFrame(intraFrame, config(false, Av1FrameSelection.KEY)));
    }

    /// Verifies that existing-frame output ignores `showFrame` visibility but still applies frame-type filters.
    @Test
    void shouldOutputExistingFrameIgnoresVisibilityButMatchesFrameSelectionFilter() {
        FrameHeader hiddenKeyFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.KEY, false, 0x00);
        FrameHeader hiddenInterFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.INTER, false, 0x00);
        FrameHeader hiddenReferenceSwitchFrame = RuntimeTestFixtures.createFrameHeader(Av1FrameType.SWITCH, false, 0x04);

        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenKeyFrame, config(false, Av1FrameSelection.ALL)));
        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenReferenceSwitchFrame, config(false, Av1FrameSelection.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputExistingFrame(hiddenInterFrame, config(false, Av1FrameSelection.REFERENCE)));
        assertFalse(FrameOutputPolicy.shouldOutputExistingFrame(hiddenInterFrame, config(false, Av1FrameSelection.INTRA)));
        assertTrue(FrameOutputPolicy.shouldOutputExistingFrame(hiddenKeyFrame, config(false, Av1FrameSelection.KEY)));
    }

    /// Verifies that film-grain synthesis is required only when both the configuration and the
    /// normalized frame-header state request it.
    @Test
    void requiresFilmGrainSynthesisMatchesConfigurationAndNormalizedFrameState() {
        FrameHeader grainFrame = RuntimeTestFixtures.createFrameHeaderWithFilmGrain(Av1FrameType.KEY, true, 0x01, true);
        FrameHeader plainFrame = RuntimeTestFixtures.createFrameHeaderWithFilmGrain(Av1FrameType.KEY, true, 0x01, false);

        assertTrue(FrameOutputPolicy.requiresFilmGrainSynthesis(grainFrame, config(false, Av1FrameSelection.ALL)));
        assertFalse(FrameOutputPolicy.requiresFilmGrainSynthesis(
                grainFrame,
                Av1DecoderConfig.DEFAULT.withApplyFilmGrain(false)
        ));
        assertFalse(FrameOutputPolicy.requiresFilmGrainSynthesis(plainFrame, config(false, Av1FrameSelection.ALL)));
    }

    /// Creates one immutable decoder configuration for runtime output-policy checks.
    ///
    /// @param outputInvisibleFrames whether invisible current frames should be exposed
    /// @param frameSelection the configured frame selection
    /// @return one immutable decoder configuration for runtime output-policy checks
    private static Av1DecoderConfig config(boolean outputInvisibleFrames, Av1FrameSelection frameSelection) {
        return Av1DecoderConfig.DEFAULT
                .withOutputInvisibleFrames(outputInvisibleFrames)
                .withFrameSelection(frameSelection);
    }
}
