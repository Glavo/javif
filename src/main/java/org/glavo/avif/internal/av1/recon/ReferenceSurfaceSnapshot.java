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

import org.glavo.avif.decode.DecodedPlanes;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState;
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

    /// The compact syntax state required while the frame remains referenced.
    private final ReferenceFrameSyntaxState frameSyntaxState;

    /// The stored post-filter, post-super-resolution, pre-grain decoded planes.
    private final DecodedPlanes decodedPlanes;

    /// Creates one immutable reference-surface snapshot.
    ///
    /// @param frameHeader the frame header that owns this reference surface
    /// @param frameSyntaxDecodeResult the complete structural decode result to compact for reference use
    /// @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
    public ReferenceSurfaceSnapshot(
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult frameSyntaxDecodeResult,
            DecodedPlanes decodedPlanes
    ) {
        this(
                frameHeader,
                ReferenceFrameSyntaxState.from(frameSyntaxDecodeResult),
                decodedPlanes
        );
    }

    /// Creates one immutable reference-surface snapshot with compact syntax state.
    ///
    /// @param frameHeader the frame header that owns this reference surface
    /// @param frameSyntaxState the compact syntax state associated with the stored surface
    /// @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
    public ReferenceSurfaceSnapshot(
            FrameHeader frameHeader,
            ReferenceFrameSyntaxState frameSyntaxState,
            DecodedPlanes decodedPlanes
    ) {
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.frameSyntaxState = Objects.requireNonNull(frameSyntaxState, "frameSyntaxState");
        this.decodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (frameSyntaxState.frameHeader() != frameHeader) {
            throw new IllegalArgumentException("frameHeader does not match the compact syntax-state header");
        }
    }

    /// Returns the frame header that owns this reference surface.
    ///
    /// @return the frame header that owns this reference surface
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the compact syntax state associated with the stored surface.
    ///
    /// @return the compact stored-frame syntax state
    public ReferenceFrameSyntaxState frameSyntaxState() {
        return frameSyntaxState;
    }

    /// Returns the stored post-filter, post-super-resolution, pre-grain decoded planes.
    ///
    /// @return the stored post-filter, post-super-resolution, pre-grain decoded planes
    public DecodedPlanes decodedPlanes() {
        return decodedPlanes;
    }
}
