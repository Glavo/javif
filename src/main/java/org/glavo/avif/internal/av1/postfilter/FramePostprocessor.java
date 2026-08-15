// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Runs the decoder postfilter stages in AV1 presentation order.
///
/// Stored reference surfaces are defined as post-filter, pre-grain snapshots. This class enforces
/// that ordering regardless of whether a particular stage currently changes samples.
@NotNullByDefault
public final class FramePostprocessor {
    /// The current loop-filter applier.
    private final LoopFilterApplier loopFilterApplier;

    /// The current CDEF applier.
    private final CdefApplier cdefApplier;

    /// The current super-resolution upscaler.
    private final SuperResolutionUpscaler superResolutionUpscaler;

    /// The current restoration applier.
    private final RestorationApplier restorationApplier;

    /// Creates one frame postprocessor.
    public FramePostprocessor() {
        this.loopFilterApplier = new LoopFilterApplier();
        this.cdefApplier = new CdefApplier();
        this.superResolutionUpscaler = new SuperResolutionUpscaler();
        this.restorationApplier = new RestorationApplier();
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @return the post-filter, pre-grain decoded planes
    public DecodedSurface postprocess(DecodedSurface decodedPlanes, FrameHeader frameHeader) {
        return postprocess(decodedPlanes, frameHeader, null);
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
    /// @return the post-filter, pre-grain decoded planes
    public DecodedSurface postprocess(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        DecodedSurface afterLoopFilter = loopFilterApplier.apply(checkedDecodedPlanes, checkedFrameHeader, syntaxDecodeResult);
        DecodedSurface afterCdef = cdefApplier.apply(afterLoopFilter, checkedFrameHeader.cdef(), syntaxDecodeResult);
        DecodedSurface afterSuperResolution = superResolutionUpscaler.apply(afterCdef, checkedFrameHeader);
        DecodedSurface restorationBoundary = afterSuperResolution;
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                checkedDecodedPlanes.hasChroma()
        ) && afterLoopFilter != afterCdef) {
            restorationBoundary = superResolutionUpscaler.apply(afterLoopFilter, checkedFrameHeader);
        }
        return restorationApplier.apply(
                afterSuperResolution,
                restorationBoundary,
                checkedFrameHeader.restoration(),
                syntaxDecodeResult
        );
    }

}
