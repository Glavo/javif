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
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

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
}
