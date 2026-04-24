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
/// Inactive loop filtering preserves samples exactly. Active loop filtering is rejected explicitly
/// until the decoder carries the block-edge and transform-edge state needed for AV1 deblocking.
@NotNullByDefault
public final class LoopFilterApplier {
    /// Applies loop filtering to one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param loopFilter the normalized frame-level loop-filter state
    /// @return the post-loop-filter planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.LoopFilterInfo loopFilter) {
        Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.LoopFilterInfo checkedLoopFilter = Objects.requireNonNull(loopFilter, "loopFilter");
        int[] levelY = checkedLoopFilter.levelY();
        if ((levelY.length > 0 && levelY[0] != 0)
                || (levelY.length > 1 && levelY[1] != 0)
                || checkedLoopFilter.levelU() != 0
                || checkedLoopFilter.levelV() != 0) {
            throw new IllegalStateException("Active AV1 loop filtering is not implemented yet");
        }
        return decodedPlanes;
    }
}
