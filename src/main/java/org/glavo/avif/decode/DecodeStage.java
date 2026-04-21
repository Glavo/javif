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

/// High-level decoder stages used for error reporting.
@NotNullByDefault
public enum DecodeStage {
    /// The decoder is interacting with the underlying byte source.
    INPUT,
    /// The decoder is reading raw OBU packets from the byte stream.
    OBU_READ,
    /// The decoder is parsing a sequence header OBU.
    SEQUENCE_HEADER_PARSE,
    /// The decoder is parsing a frame header OBU.
    FRAME_HEADER_PARSE,
    /// The decoder is assembling multiple OBUs into a frame unit.
    FRAME_ASSEMBLY,
    /// The decoder is reconstructing frame pixels.
    FRAME_DECODE,
    /// The decoder is converting decoded planes into ARGB output.
    OUTPUT_CONVERSION
}
