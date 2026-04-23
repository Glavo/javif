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

import java.util.Objects;

/// Public-output policy helpers used by `Av1ImageReader`.
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
        return matchesDecodeFrameType(checkedFrameHeader, checkedConfig.decodeFrameType());
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
        return matchesDecodeFrameType(checkedFrameHeader, checkedConfig.decodeFrameType());
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

    /// Returns whether one frame header matches the requested decode-frame-type filter.
    ///
    /// @param frameHeader the frame header to evaluate
    /// @param decodeFrameType the configured frame-type filter
    /// @return whether the frame header matches the requested decode-frame-type filter
    private static boolean matchesDecodeFrameType(FrameHeader frameHeader, DecodeFrameType decodeFrameType) {
        return switch (Objects.requireNonNull(decodeFrameType, "decodeFrameType")) {
            case ALL -> true;
            case REFERENCE -> frameHeader.refreshFrameFlags() != 0;
            case INTRA -> frameHeader.frameType() == FrameType.KEY || frameHeader.frameType() == FrameType.INTRA;
            case KEY -> frameHeader.frameType() == FrameType.KEY;
        };
    }
}
