// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Parsed byte-range metadata for one tile payload inside a tile group.
@NotNullByDefault
public final class TileDataEntry {
    /// The zero-based tile index within the frame.
    private final int tileIndex;
    /// The byte offset of the tile payload inside the source OBU payload.
    private final int dataOffset;
    /// The byte length of the tile payload.
    private final int dataLength;

    /// Creates parsed tile payload metadata.
    ///
    /// @param tileIndex the zero-based tile index within the frame
    /// @param dataOffset the byte offset of the tile payload inside the source OBU payload
    /// @param dataLength the byte length of the tile payload
    public TileDataEntry(int tileIndex, int dataOffset, int dataLength) {
        if (tileIndex < 0) {
            throw new IllegalArgumentException("tileIndex < 0: " + tileIndex);
        }
        if (dataOffset < 0) {
            throw new IllegalArgumentException("dataOffset < 0: " + dataOffset);
        }
        if (dataLength < 0) {
            throw new IllegalArgumentException("dataLength < 0: " + dataLength);
        }
        this.tileIndex = tileIndex;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    /// Returns the zero-based tile index within the frame.
    ///
    /// @return the zero-based tile index within the frame
    public int tileIndex() {
        return tileIndex;
    }

    /// Returns the byte offset of the tile payload inside the source OBU payload.
    ///
    /// @return the byte offset of the tile payload inside the source OBU payload
    public int dataOffset() {
        return dataOffset;
    }

    /// Returns the byte length of the tile payload.
    ///
    /// @return the byte length of the tile payload
    public int dataLength() {
        return dataLength;
    }

    /// Creates a read-only tile payload view over the supplied backing bytes.
    ///
    /// @param payload the backing bytes that contain the tile payload
    /// @return a read-only tile payload view over the supplied backing bytes
    public TileBitstream toBitstream(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return new TileBitstream(tileIndex, payload, dataOffset, dataLength);
    }
}
