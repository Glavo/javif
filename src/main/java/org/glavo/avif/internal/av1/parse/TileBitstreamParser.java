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

import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.glavo.avif.internal.av1.model.TileDataEntry;
import org.glavo.avif.internal.av1.model.TileGroupHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Parser that converts AV1 tile-data layout into per-tile bitstream views.
@NotNullByDefault
public final class TileBitstreamParser {
    /// The parser used to derive raw tile byte ranges.
    private final TileDataParser tileDataParser = new TileDataParser();

    /// Parses per-tile bitstream views from a tile-group payload.
    ///
    /// @param obu the source OBU packet
    /// @param frameHeader the active frame header
    /// @param tileGroupHeader the parsed tile-group header
    /// @param tileDataOffset the byte offset of the tile-data section inside the OBU payload
    /// @return the parsed per-tile bitstream views
    /// @throws DecodeException if the tile-data layout is malformed
    public TileBitstream[] parse(
            ObuPacket obu,
            FrameHeader frameHeader,
            TileGroupHeader tileGroupHeader,
            int tileDataOffset
    ) throws DecodeException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(tileGroupHeader, "tileGroupHeader");

        TileDataEntry[] entries = tileDataParser.parse(obu, frameHeader, tileGroupHeader, tileDataOffset);
        TileBitstream[] bitstreams = new TileBitstream[entries.length];
        byte[] payload = obu.payload();
        for (int i = 0; i < entries.length; i++) {
            bitstreams[i] = entries[i].toBitstream(payload);
        }
        return bitstreams;
    }
}
