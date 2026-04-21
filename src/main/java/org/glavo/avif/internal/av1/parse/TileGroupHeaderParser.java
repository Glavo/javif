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
import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Parser for AV1 tile-group headers embedded inside `TILE_GROUP` and `FRAME` OBUs.
@NotNullByDefault
public final class TileGroupHeaderParser {
    /// Parses a tile-group header from the supplied bit reader.
    ///
    /// @param reader the payload bit reader positioned at the tile-group header
    /// @param obu the source OBU packet
    /// @param frameHeader the active frame header
    /// @return the parsed tile-group header
    /// @throws IOException if the payload is truncated or invalid
    public TileGroupHeader parse(BitReader reader, ObuPacket obu, FrameHeader frameHeader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(frameHeader, "frameHeader");

        int totalTiles = frameHeader.tiling().columns() * frameHeader.tiling().rows();
        if (totalTiles <= 0) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.FRAME_ASSEMBLY,
                    "Frame header does not describe any tiles",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null
            );
        }

        try {
            boolean explicitTilePositions = totalTiles > 1 && reader.readFlag();
            int startTileIndex;
            int endTileIndex;
            if (explicitTilePositions) {
                int indexBitCount = frameHeader.tiling().log2Cols() + frameHeader.tiling().log2Rows();
                startTileIndex = Math.toIntExact(reader.readBits(indexBitCount));
                endTileIndex = Math.toIntExact(reader.readBits(indexBitCount));
            } else {
                startTileIndex = 0;
                endTileIndex = totalTiles - 1;
            }

            if (startTileIndex > endTileIndex) {
                throw invalidBitstream(
                        obu,
                        "Tile-group start index exceeds end index: " + startTileIndex + " > " + endTileIndex
                );
            }
            if (endTileIndex >= totalTiles) {
                throw invalidBitstream(
                        obu,
                        "Tile-group end index exceeds the frame tile count: " + endTileIndex + " >= " + totalTiles
                );
            }

            return new TileGroupHeader(explicitTilePositions, startTileIndex, endTileIndex, totalTiles);
        } catch (EOFException ex) {
            throw new DecodeException(
                    DecodeErrorCode.UNEXPECTED_EOF,
                    DecodeStage.FRAME_ASSEMBLY,
                    "Unexpected end of tile-group header",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Creates a contextual invalid-bitstream exception for tile-group assembly errors.
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
