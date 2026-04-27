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

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
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

    /// The current restoration applier.
    private final RestorationApplier restorationApplier;

    /// Creates one frame postprocessor.
    public FramePostprocessor() {
        this.loopFilterApplier = new LoopFilterApplier();
        this.cdefApplier = new CdefApplier();
        this.restorationApplier = new RestorationApplier();
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @return the post-filter, pre-grain decoded planes
    public DecodedPlanes postprocess(DecodedPlanes decodedPlanes, FrameHeader frameHeader) {
        return postprocess(decodedPlanes, frameHeader, null);
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
    /// @return the post-filter, pre-grain decoded planes
    public DecodedPlanes postprocess(
            DecodedPlanes decodedPlanes,
            FrameHeader frameHeader,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        DecodedPlanes afterLoopFilter = loopFilterApplier.apply(checkedDecodedPlanes, checkedFrameHeader, syntaxDecodeResult);
        DecodedPlanes afterCdef = cdefApplier.apply(afterLoopFilter, checkedFrameHeader.cdef(), syntaxDecodeResult);
        return restorationApplier.apply(afterCdef, afterLoopFilter, checkedFrameHeader.restoration(), syntaxDecodeResult);
    }
}
