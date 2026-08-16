// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable reference-surface snapshot stored for later frame reuse.
///
/// The stored planes represent post-filter, post-super-resolution, pre-grain image state.
///
/// @param frameHeader the frame header that owns this reference surface
/// @param frameSyntaxState the compact syntax state associated with the stored surface
/// @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
@NotNullByDefault
public record ReferenceSurfaceSnapshot(
        FrameHeader frameHeader,
        ReferenceFrameSyntaxState frameSyntaxState,
        DecodedSurface decodedPlanes
) {
    /// Creates one immutable reference-surface snapshot.
    ///
    /// @param frameHeader the frame header that owns this reference surface
    /// @param frameSyntaxDecodeResult the complete structural decode result to compact for reference use
    /// @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
    public ReferenceSurfaceSnapshot(
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult frameSyntaxDecodeResult,
            DecodedSurface decodedPlanes
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
    public ReferenceSurfaceSnapshot {
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(frameSyntaxState, "frameSyntaxState");
        Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (frameSyntaxState.frameHeader() != frameHeader) {
            throw new IllegalArgumentException("frameHeader does not match the compact syntax-state header");
        }
    }
}
