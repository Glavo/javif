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

/// Stable error codes exposed by the public decoder API.
@NotNullByDefault
public enum DecodeErrorCode {
    /// The input ended before the expected number of bytes or bits were available.
    UNEXPECTED_EOF,
    /// The OBU header contains invalid or unsupported flag combinations.
    INVALID_OBU_HEADER,
    /// A LEB128 value is malformed or exceeds the supported range.
    INVALID_LEB128,
    /// The AV1 bitstream violates structural constraints.
    INVALID_BITSTREAM,
    /// The input uses a valid AV1 feature that is not implemented yet.
    UNSUPPORTED_FEATURE,
    /// The configured frame size limit was exceeded.
    FRAME_SIZE_LIMIT_EXCEEDED,
    /// The decoder state machine reached an invalid state.
    STATE_VIOLATION,
    /// The requested decode path is not implemented yet.
    NOT_IMPLEMENTED
}
