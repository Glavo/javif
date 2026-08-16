// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// A single OBU packet read from a forward-only AV1 OBU stream.
///
/// @param header the parsed OBU header
/// @param payload the raw OBU payload bytes
/// @param streamOffset the zero-based byte offset of the OBU header
/// @param obuIndex the zero-based OBU index within the stream
@NotNullByDefault
public record ObuPacket(
        ObuHeader header,
        byte @Unmodifiable [] payload,
        long streamOffset,
        int obuIndex
) {
    /// Creates an OBU packet.
    public ObuPacket {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset < 0: " + streamOffset);
        }
        if (obuIndex < 0) {
            throw new IllegalArgumentException("obuIndex < 0: " + obuIndex);
        }
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(payload, "payload");
    }
}
