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

/// Stable AVIF container-level error categories.
@NotNullByDefault
public enum AvifErrorCode {
    /// The input does not contain an AVIF-compatible `ftyp` box.
    INVALID_FTYP,
    /// The BMFF box structure is malformed.
    BMFF_PARSE_FAILED,
    /// The input ended before a required byte range was available.
    TRUNCATED_DATA,
    /// The AVIF file does not contain a usable primary image item.
    MISSING_IMAGE_ITEM,
    /// The input uses a valid feature that this reader does not support yet.
    UNSUPPORTED_FEATURE,
    /// The embedded AV1 payload could not be decoded.
    AV1_DECODE_FAILED,
    /// The reader was used after being closed.
    CLOSED
}
