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
package org.glavo.avif.internal.av1.parse;

import org.glavo.avif.decode.DecodeErrorCode;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.DecodeStage;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TileDataEntry;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Parser for the per-tile payload layout stored inside AV1 tile groups.
@NotNullByDefault
public final class TileDataParser {
    /// Parses per-tile payload ranges from a tile-group payload.
    ///
    /// @param obu the source OBU packet
    /// @param frameHeader the active frame header
    /// @param tileGroupHeader the parsed tile-group header
    /// @param tileDataOffset the byte offset of the tile-data section inside the OBU payload
    /// @return the parsed per-tile payload ranges
    /// @throws DecodeException if the tile-data layout is malformed
    public TileDataEntry[] parse(
            ObuPacket obu,
            FrameHeader frameHeader,
            TileGroupHeader tileGroupHeader,
            int tileDataOffset
    ) throws DecodeException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(tileGroupHeader, "tileGroupHeader");

        byte[] payload = obu.payload();
        if (tileDataOffset < 0 || tileDataOffset > payload.length) {
            throw invalidBitstream(obu, "Tile-data offset exceeds the OBU payload length");
        }

        int tileCount = tileGroupHeader.tileCount();
        int sizeBytes = frameHeader.tiling().sizeBytes();
        if (tileCount > 1 && sizeBytes <= 0) {
            throw invalidBitstream(obu, "Multi-tile groups require size bytes in the active frame header");
        }

        TileDataEntry[] entries = new TileDataEntry[tileCount];
        int cursor = tileDataOffset;
        for (int i = 0; i < tileCount; i++) {
            int tileIndex = tileGroupHeader.startTileIndex() + i;
            int tileDataLength;
            if (i == tileCount - 1) {
                tileDataLength = payload.length - cursor;
            } else {
                if (cursor + sizeBytes > payload.length) {
                    throw invalidBitstream(obu, "Tile size table exceeds the OBU payload length");
                }

                int tileDataLengthMinusOne = 0;
                for (int byteIndex = 0; byteIndex < sizeBytes; byteIndex++) {
                    tileDataLengthMinusOne |= (payload[cursor + byteIndex] & 0xFF) << (byteIndex * Byte.SIZE);
                }
                cursor += sizeBytes;
                tileDataLength = tileDataLengthMinusOne + 1;
                if (cursor + tileDataLength > payload.length) {
                    throw invalidBitstream(obu, "Tile payload exceeds the OBU payload length");
                }
            }

            entries[i] = new TileDataEntry(tileIndex, cursor, tileDataLength);
            cursor += tileDataLength;
        }

        return entries;
    }

    /// Creates a contextual invalid-bitstream exception for tile-data layout errors.
    ///
    /// @param obu the source OBU packet
    /// @param message the detailed validation message
    /// @return the contextual invalid-bitstream exception
    private static DecodeException invalidBitstream(ObuPacket obu, String message) {
        return new DecodeException(
                DecodeErrorCode.INVALID_BITSTREAM,
                DecodeStage.FRAME_ASSEMBLY,
                message,
                obu.streamOffset(),
                obu.obuIndex(),
                null
        );
    }
}
