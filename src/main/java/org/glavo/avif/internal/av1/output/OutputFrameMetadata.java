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
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.decode.FrameType;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Frame metadata needed when internal output conversion materializes one decoded frame object.
///
/// Output dimensions, bit depth, and chroma layout come from `DecodedPlanes`; this metadata only
/// carries the public frame attributes that do not belong to the plane snapshot itself.
@NotNullByDefault
public final class OutputFrameMetadata {
    /// The AV1 frame category.
    private final FrameType frameType;

    /// Whether the frame should be exposed as visible output.
    private final boolean visible;

    /// The zero-based presentation index of the frame.
    private final long presentationIndex;

    /// Creates one frame-metadata descriptor for internal output conversion.
    ///
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    public OutputFrameMetadata(FrameType frameType, boolean visible, long presentationIndex) {
        if (presentationIndex < 0) {
            throw new IllegalArgumentException("presentationIndex < 0: " + presentationIndex);
        }
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
    }

    /// Returns the AV1 frame category.
    ///
    /// @return the AV1 frame category
    public FrameType frameType() {
        return frameType;
    }

    /// Returns whether the frame should be exposed as visible output.
    ///
    /// @return whether the frame should be exposed as visible output
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index of the frame.
    ///
    /// @return the zero-based presentation index of the frame
    public long presentationIndex() {
        return presentationIndex;
    }
}
