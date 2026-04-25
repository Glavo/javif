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

import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Read-only view over one tile payload inside a tile group.
@NotNullByDefault
public final class TileBitstream {
    /// The zero-based tile index within the frame.
    private final int tileIndex;
    /// The backing payload bytes that contain the tile payload.
    private final byte @Unmodifiable [] data;
    /// The first tile byte inside `data`.
    private final int dataOffset;
    /// The byte length of the tile payload.
    private final int dataLength;

    /// Creates a read-only tile payload view.
    ///
    /// @param tileIndex the zero-based tile index within the frame
    /// @param data the backing payload bytes that contain the tile payload
    /// @param dataOffset the first tile byte inside `data`
    /// @param dataLength the byte length of the tile payload
    public TileBitstream(int tileIndex, byte[] data, int dataOffset, int dataLength) {
        if (tileIndex < 0) {
            throw new IllegalArgumentException("tileIndex < 0: " + tileIndex);
        }
        this.data = Objects.requireNonNull(data, "data");
        if (dataOffset < 0 || dataOffset > data.length) {
            throw new IllegalArgumentException("dataOffset out of range: " + dataOffset);
        }
        if (dataLength < 0 || dataOffset + dataLength > data.length) {
            throw new IllegalArgumentException("dataLength out of range: " + dataLength);
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

    /// Returns the first tile byte inside the backing payload.
    ///
    /// @return the first tile byte inside the backing payload
    public int dataOffset() {
        return dataOffset;
    }

    /// Returns the byte length of the tile payload.
    ///
    /// @return the byte length of the tile payload
    public int dataLength() {
        return dataLength;
    }

    /// Opens a fresh bit reader over the tile payload.
    ///
    /// @return a fresh bit reader over the tile payload
    public BitReader openBitReader() {
        return new BitReader(data, dataOffset, dataLength);
    }

    /// Opens a fresh AV1 multi-symbol arithmetic decoder over the tile payload.
    ///
    /// @param disableCdfUpdate whether CDF updates are disabled for the active frame
    /// @return a fresh arithmetic decoder over the tile payload
    public MsacDecoder openMsacDecoder(boolean disableCdfUpdate) {
        return new MsacDecoder(data, dataOffset, dataLength, disableCdfUpdate);
    }

    /// Returns a copy of the tile payload bytes.
    ///
    /// @return a copy of the tile payload bytes
    public byte[] copyBytes() {
        return Arrays.copyOfRange(data, dataOffset, dataOffset + dataLength);
    }
}
