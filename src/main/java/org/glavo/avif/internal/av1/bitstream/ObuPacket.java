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
package org.glavo.avif.internal.av1.bitstream;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// A single OBU packet read from a forward-only AV1 OBU stream.
@NotNullByDefault
public final class ObuPacket {
    /// The parsed OBU header.
    private final ObuHeader header;
    /// The raw OBU payload bytes.
    private final byte[] payload;
    /// The zero-based byte offset of the OBU header.
    private final long streamOffset;
    /// The zero-based OBU index within the stream.
    private final int obuIndex;

    /// Creates an OBU packet.
    ///
    /// @param header the parsed OBU header
    /// @param payload the raw OBU payload bytes
    /// @param streamOffset the zero-based byte offset of the OBU header
    /// @param obuIndex the zero-based OBU index within the stream
    public ObuPacket(ObuHeader header, byte[] payload, long streamOffset, int obuIndex) {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset < 0: " + streamOffset);
        }
        if (obuIndex < 0) {
            throw new IllegalArgumentException("obuIndex < 0: " + obuIndex);
        }
        this.header = Objects.requireNonNull(header, "header");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.streamOffset = streamOffset;
        this.obuIndex = obuIndex;
    }

    /// Returns the parsed OBU header.
    ///
    /// @return the parsed OBU header
    public ObuHeader header() {
        return header;
    }

    /// Returns the raw OBU payload bytes.
    ///
    /// @return the raw OBU payload bytes
    public byte[] payload() {
        return payload;
    }

    /// Returns the zero-based byte offset of the OBU header.
    ///
    /// @return the zero-based byte offset
    public long streamOffset() {
        return streamOffset;
    }

    /// Returns the zero-based OBU index within the stream.
    ///
    /// @return the zero-based OBU index
    public int obuIndex() {
        return obuIndex;
    }
}
