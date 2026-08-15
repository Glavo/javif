// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.RestorationUnitMap;
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
        return finish(prepare(decodedPlanes, frameHeader, syntaxDecodeResult));
    }

    /// Extracts syntax-dependent state needed by the postfilter stages.
    ///
    /// The returned object retains only compact loop-filter, CDEF, and restoration state. It does
    /// not retain `syntaxDecodeResult`, so callers may release a large frame syntax tree before
    /// [#finish(PreparedFrame)] allocates any destination planes.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
    /// @return the prepared postfilter operation
    public PreparedFrame prepare(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        LoopFilterApplier.PreparedApplication preparedLoopFilter = loopFilterApplier.prepare(
                checkedDecodedPlanes,
                checkedFrameHeader,
                syntaxDecodeResult
        );
        CdefApplier.PreparedApplication preparedCdef = cdefApplier.prepare(
                checkedDecodedPlanes,
                checkedFrameHeader.cdef(),
                syntaxDecodeResult
        );
        @Nullable RestorationUnitMap restorationUnitMap = null;
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                checkedDecodedPlanes.hasChroma()
        )) {
            if (syntaxDecodeResult == null) {
                throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
            }
            restorationUnitMap = syntaxDecodeResult.restorationUnitMap();
        }
        return new PreparedFrame(
                checkedDecodedPlanes,
                checkedFrameHeader,
                preparedLoopFilter,
                preparedCdef,
                restorationUnitMap
        );
    }

    /// Runs loop filtering, CDEF, super-resolution, and restoration for one prepared frame.
    ///
    /// @param preparedFrame the syntax-independent prepared postfilter operation
    /// @return the post-filter, pre-grain decoded planes
    public DecodedSurface finish(PreparedFrame preparedFrame) {
        PreparedFrame prepared = Objects.requireNonNull(preparedFrame, "preparedFrame");
        DecodedSurface afterLoopFilter = loopFilterApplier.applyPrepared(
                prepared.reconstructedPlanes,
                prepared.preparedLoopFilter
        );
        FrameHeader checkedFrameHeader = prepared.frameHeader;
        DecodedSurface afterCdef = cdefApplier.applyPrepared(afterLoopFilter, prepared.preparedCdef);
        DecodedSurface afterSuperResolution = superResolutionUpscaler.apply(afterCdef, checkedFrameHeader);
        DecodedSurface restorationBoundary = afterSuperResolution;
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                afterLoopFilter.hasChroma()
        ) && afterLoopFilter != afterCdef) {
            restorationBoundary = superResolutionUpscaler.apply(afterLoopFilter, checkedFrameHeader);
        }
        return restorationApplier.applyPrepared(
                afterSuperResolution,
                restorationBoundary,
                checkedFrameHeader.restoration(),
                prepared.restorationUnitMap
        );
    }

    /// Syntax-independent state needed to finish postfiltering one decoded frame.
    ///
    /// Instances are created by [#prepare(DecodedSurface, FrameHeader, FrameSyntaxDecodeResult)] and
    /// deliberately do not retain the complete block syntax tree.
    @NotNullByDefault
    public static final class PreparedFrame {
        /// The reconstructed surface before pixel-domain postfiltering.
        private final DecodedSurface reconstructedPlanes;

        /// The normalized frame header.
        private final FrameHeader frameHeader;

        /// The compact prepared loop-filter state.
        private final LoopFilterApplier.PreparedApplication preparedLoopFilter;

        /// The compact prepared CDEF state.
        private final CdefApplier.PreparedApplication preparedCdef;

        /// The compact restoration-unit state, or `null` when restoration is inactive.
        private final @Nullable RestorationUnitMap restorationUnitMap;

        /// Creates one syntax-independent prepared postfilter operation.
        ///
        /// @param reconstructedPlanes the reconstructed surface before pixel-domain postfiltering
        /// @param frameHeader the normalized frame header
        /// @param preparedLoopFilter the compact prepared loop-filter state
        /// @param preparedCdef the compact prepared CDEF state
        /// @param restorationUnitMap the compact restoration-unit state, or `null`
        private PreparedFrame(
                DecodedSurface reconstructedPlanes,
                FrameHeader frameHeader,
                LoopFilterApplier.PreparedApplication preparedLoopFilter,
                CdefApplier.PreparedApplication preparedCdef,
                @Nullable RestorationUnitMap restorationUnitMap
        ) {
            this.reconstructedPlanes = Objects.requireNonNull(reconstructedPlanes, "reconstructedPlanes");
            this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
            this.preparedLoopFilter = Objects.requireNonNull(preparedLoopFilter, "preparedLoopFilter");
            this.preparedCdef = Objects.requireNonNull(preparedCdef, "preparedCdef");
            this.restorationUnitMap = restorationUnitMap;
        }
    }
}
