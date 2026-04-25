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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Checked exception thrown when AVIF container parsing or image decoding fails.
@NotNullByDefault
public final class AvifDecodeException extends IOException {
    /// The stable AVIF error code.
    private final AvifErrorCode code;

    /// The byte offset associated with the failure, or `null` when not known.
    private final @Nullable Long byteOffset;

    /// Creates an exception without an underlying cause.
    ///
    /// @param code the stable AVIF error code
    /// @param message the human-readable failure message
    /// @param byteOffset the byte offset associated with the failure, or `null`
    public AvifDecodeException(AvifErrorCode code, String message, @Nullable Long byteOffset) {
        this(code, message, byteOffset, null);
    }

    /// Creates an exception with an underlying cause.
    ///
    /// @param code the stable AVIF error code
    /// @param message the human-readable failure message
    /// @param byteOffset the byte offset associated with the failure, or `null`
    /// @param cause the underlying failure, or `null`
    public AvifDecodeException(
            AvifErrorCode code,
            String message,
            @Nullable Long byteOffset,
            @Nullable Throwable cause
    ) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.byteOffset = byteOffset;
    }

    /// Returns the stable AVIF error code.
    ///
    /// @return the stable AVIF error code
    public AvifErrorCode code() {
        return code;
    }

    /// Returns the byte offset associated with the failure.
    ///
    /// @return the byte offset associated with the failure, or `null`
    public @Nullable Long byteOffset() {
        return byteOffset;
    }
}
