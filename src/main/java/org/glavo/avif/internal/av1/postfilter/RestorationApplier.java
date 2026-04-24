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

/// Applies the current loop-restoration stage of the postfilter pipeline.
///
/// Inactive restoration preserves samples exactly. Active restoration is rejected explicitly until
/// restoration unit syntax and filter coefficients are represented in the decoded frame state.
@NotNullByDefault
public final class RestorationApplier {
    /// Applies restoration to one decoded frame.
    ///
    /// @param decodedPlanes the decoded planes after CDEF
    /// @param restoration the normalized frame-level restoration state
    /// @return the post-restoration planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.RestorationInfo restoration) {
        Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.RestorationInfo checkedRestoration = Objects.requireNonNull(restoration, "restoration");
        for (FrameHeader.RestorationType type : checkedRestoration.types()) {
            if (type != FrameHeader.RestorationType.NONE) {
                throw new IllegalStateException("Active AV1 loop restoration is not implemented yet");
            }
        }
        return decodedPlanes;
    }
}
