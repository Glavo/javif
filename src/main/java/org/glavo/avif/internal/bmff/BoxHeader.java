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
package org.glavo.avif.internal.bmff;

import org.jetbrains.annotations.NotNullByDefault;

/// Header for one BMFF box.
@NotNullByDefault
public final class BoxHeader {
    /// The four-character box type.
    private final String type;
    /// The absolute byte offset of the box header.
    private final int offset;
    /// The absolute byte offset of the box payload.
    private final int payloadOffset;
    /// The byte length of the box payload.
    private final int payloadSize;

    /// Creates a BMFF box header.
    ///
    /// @param type the four-character box type
    /// @param offset the absolute byte offset of the box header
    /// @param payloadOffset the absolute byte offset of the box payload
    /// @param payloadSize the byte length of the box payload
    public BoxHeader(String type, int offset, int payloadOffset, int payloadSize) {
        this.type = type;
        this.offset = offset;
        this.payloadOffset = payloadOffset;
        this.payloadSize = payloadSize;
    }

    /// Returns the four-character box type.
    ///
    /// @return the four-character box type
    public String type() {
        return type;
    }

    /// Returns the absolute byte offset of the box header.
    ///
    /// @return the absolute byte offset of the box header
    public int offset() {
        return offset;
    }

    /// Returns the absolute byte offset of the box payload.
    ///
    /// @return the absolute byte offset of the box payload
    public int payloadOffset() {
        return payloadOffset;
    }

    /// Returns the byte length of the box payload.
    ///
    /// @return the byte length of the box payload
    public int payloadSize() {
        return payloadSize;
    }

    /// Returns the absolute byte offset immediately after this box.
    ///
    /// @return the absolute byte offset immediately after this box
    public int endOffset() {
        return payloadOffset + payloadSize;
    }
}
