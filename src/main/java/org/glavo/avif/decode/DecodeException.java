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
package org.glavo.avif.decode;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Checked exception thrown when AV1 input cannot be decoded.
@NotNullByDefault
public final class DecodeException extends IOException {
    /// The stable error code describing the failure category.
    private final DecodeErrorCode code;
    /// The high-level decode stage where the failure occurred.
    private final DecodeStage stage;
    /// The byte offset of the relevant OBU or payload location when known.
    private final @Nullable Long streamOffset;
    /// The zero-based OBU index when known.
    private final @Nullable Integer obuIndex;
    /// The zero-based frame index when known.
    private final @Nullable Integer frameIndex;

    /// Creates an exception without an underlying cause.
    ///
    /// @param code the stable error code
    /// @param stage the decode stage
    /// @param message the human-readable error message
    /// @param streamOffset the byte offset when known
    /// @param obuIndex the OBU index when known
    /// @param frameIndex the frame index when known
    public DecodeException(
            DecodeErrorCode code,
            DecodeStage stage,
            String message,
            @Nullable Long streamOffset,
            @Nullable Integer obuIndex,
            @Nullable Integer frameIndex
    ) {
        this(code, stage, message, streamOffset, obuIndex, frameIndex, null);
    }

    /// Creates an exception with an underlying cause.
    ///
    /// @param code the stable error code
    /// @param stage the decode stage
    /// @param message the human-readable error message
    /// @param streamOffset the byte offset when known
    /// @param obuIndex the OBU index when known
    /// @param frameIndex the frame index when known
    /// @param cause the underlying cause, if any
    public DecodeException(
            DecodeErrorCode code,
            DecodeStage stage,
            String message,
            @Nullable Long streamOffset,
            @Nullable Integer obuIndex,
            @Nullable Integer frameIndex,
            @Nullable Throwable cause
    ) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.streamOffset = streamOffset;
        this.obuIndex = obuIndex;
        this.frameIndex = frameIndex;
    }

    /// Returns the stable error code.
    ///
    /// @return the stable error code
    public DecodeErrorCode code() {
        return code;
    }

    /// Returns the high-level decode stage where the failure occurred.
    ///
    /// @return the failing decode stage
    public DecodeStage stage() {
        return stage;
    }

    /// Returns the relevant byte offset when known.
    ///
    /// @return the relevant byte offset, or `null`
    public @Nullable Long streamOffset() {
        return streamOffset;
    }

    /// Returns the relevant OBU index when known.
    ///
    /// @return the relevant OBU index, or `null`
    public @Nullable Integer obuIndex() {
        return obuIndex;
    }

    /// Returns the relevant frame index when known.
    ///
    /// @return the relevant frame index, or `null`
    public @Nullable Integer frameIndex() {
        return frameIndex;
    }
}
