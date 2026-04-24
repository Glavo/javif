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

import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Applies the current loop-filter stage of the postfilter pipeline.
///
/// The current implementation preserves samples exactly and exists to freeze the decoder ordering
/// contract at "reconstruction -> loop filter -> CDEF -> restoration -> stored reference
/// surface". This keeps reference-surface semantics stable while broader postfilter fidelity is
/// expanded in follow-up work.
@NotNullByDefault
public final class LoopFilterApplier {
    /// Applies loop filtering to one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param loopFilter the normalized frame-level loop-filter state
    /// @return the post-loop-filter planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.LoopFilterInfo loopFilter) {
        Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        Objects.requireNonNull(loopFilter, "loopFilter");
        return decodedPlanes;
    }
}
