// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.av1.Av1DecoderConfig;
import org.glavo.avif.av1.Av1FrameSelection;
import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Public-output policy helpers used by `Av1Decoder`.
@NotNullByDefault
public final class FrameOutputPolicy {
    /// Prevents instantiation.
    private FrameOutputPolicy() {
    }

    /// Returns whether one decoded frame should be exposed through the public API.
    ///
    /// @param frameHeader the parsed frame header for the current frame
    /// @param config the immutable decoder configuration
    /// @return whether one decoded frame should be exposed through the public API
    public static boolean shouldOutputFrame(FrameHeader frameHeader, Av1DecoderConfig config) {
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        Av1DecoderConfig checkedConfig = Objects.requireNonNull(config, "config");
        if (!checkedFrameHeader.showFrame() && !checkedConfig.outputInvisibleFrames()) {
            return false;
        }
        return matchesFrameSelection(checkedFrameHeader, checkedConfig.frameSelection());
    }

    /// Returns whether one referenced `show_existing_frame` surface should be exposed publicly.
    ///
    /// `show_existing_frame` is always visible presentation, so only the frame-type filter remains
    /// relevant here.
    ///
    /// @param referencedFrameHeader the frame header stored alongside the referenced surface
    /// @param config the immutable decoder configuration
    /// @return whether one referenced `show_existing_frame` surface should be exposed publicly
    public static boolean shouldOutputExistingFrame(FrameHeader referencedFrameHeader, Av1DecoderConfig config) {
        FrameHeader checkedFrameHeader = Objects.requireNonNull(referencedFrameHeader, "referencedFrameHeader");
        Av1DecoderConfig checkedConfig = Objects.requireNonNull(config, "config");
        return matchesFrameSelection(checkedFrameHeader, checkedConfig.frameSelection());
    }

    /// Returns whether the current output request would require film-grain synthesis.
    ///
    /// @param frameHeader the frame header whose normalized film-grain parameters should be checked
    /// @param config the immutable decoder configuration
    /// @return whether the current output request would require film-grain synthesis
    public static boolean requiresFilmGrainSynthesis(FrameHeader frameHeader, Av1DecoderConfig config) {
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        Av1DecoderConfig checkedConfig = Objects.requireNonNull(config, "config");
        return checkedConfig.applyFilmGrain() && checkedFrameHeader.filmGrain().applyGrain();
    }

    /// Returns whether one frame header matches the requested frame selection.
    ///
    /// @param frameHeader the frame header to evaluate
    /// @param frameSelection the configured frame selection
    /// @return whether the frame header matches the requested selection
    private static boolean matchesFrameSelection(FrameHeader frameHeader, Av1FrameSelection frameSelection) {
        return switch (Objects.requireNonNull(frameSelection, "frameSelection")) {
            case ALL -> true;
            case REFERENCE -> frameHeader.refreshFrameFlags() != 0;
            case INTRA -> frameHeader.frameType() == Av1FrameType.KEY || frameHeader.frameType() == Av1FrameType.INTRA;
            case KEY -> frameHeader.frameType() == Av1FrameType.KEY;
        };
    }
}
