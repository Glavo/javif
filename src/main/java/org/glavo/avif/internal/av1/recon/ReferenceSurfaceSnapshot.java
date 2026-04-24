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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable reference-surface snapshot stored for later frame reuse.
///
/// The stored planes represent post-filter, post-super-resolution, pre-grain image state.
@NotNullByDefault
public final class ReferenceSurfaceSnapshot {
    /// The frame header that owns this reference surface.
    private final FrameHeader frameHeader;

    /// The structural frame-decode result associated with the stored surface.
    private final FrameSyntaxDecodeResult frameSyntaxDecodeResult;

    /// The stored post-filter, post-super-resolution, pre-grain decoded planes.
    private final DecodedPlanes decodedPlanes;

    /// Creates one immutable reference-surface snapshot.
    ///
    /// @param frameHeader the frame header that owns this reference surface
    /// @param frameSyntaxDecodeResult the structural frame-decode result associated with the stored surface
    /// @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
    public ReferenceSurfaceSnapshot(
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult frameSyntaxDecodeResult,
            DecodedPlanes decodedPlanes
    ) {
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.frameSyntaxDecodeResult = Objects.requireNonNull(frameSyntaxDecodeResult, "frameSyntaxDecodeResult");
        this.decodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (frameSyntaxDecodeResult.assembly().frameHeader() != frameHeader) {
            throw new IllegalArgumentException("frameHeader does not match the decode-result assembly header");
        }
    }

    /// Returns the frame header that owns this reference surface.
    ///
    /// @return the frame header that owns this reference surface
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the structural frame-decode result associated with the stored surface.
    ///
    /// @return the structural frame-decode result associated with the stored surface
    public FrameSyntaxDecodeResult frameSyntaxDecodeResult() {
        return frameSyntaxDecodeResult;
    }

    /// Returns the stored post-filter, post-super-resolution, pre-grain decoded planes.
    ///
    /// @return the stored post-filter, post-super-resolution, pre-grain decoded planes
    public DecodedPlanes decodedPlanes() {
        return decodedPlanes;
    }
}
