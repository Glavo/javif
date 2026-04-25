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

import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Incrementally assembled AV1 frame state before pixel decoding begins.
@NotNullByDefault
public final class FrameAssembly {
    /// The active sequence header for this frame.
    private final SequenceHeader sequenceHeader;
    /// The parsed frame header for this frame.
    private final FrameHeader frameHeader;
    /// The refreshed reference-frame headers indexed by runtime reference slot.
    private final @Nullable FrameHeader @Unmodifiable [] referenceFrameHeaders;
    /// The byte offset of the first OBU that belongs to this frame.
    private final long streamOffset;
    /// The zero-based index of the first OBU that belongs to this frame.
    private final int obuIndex;
    /// The total number of tiles declared by the frame header.
    private final int totalTiles;
    /// The tile groups accumulated for this frame so far.
    private final List<TileGroup> tileGroups = new ArrayList<>();
    /// The next tile index that must be covered by the next tile group.
    private int nextTileIndex;

    /// Creates a new in-progress frame assembly.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed frame header
    /// @param streamOffset the byte offset of the first frame OBU
    /// @param obuIndex the zero-based index of the first frame OBU
    public FrameAssembly(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            long streamOffset,
            int obuIndex
    ) {
        this(sequenceHeader, frameHeader, new FrameHeader[8], streamOffset, obuIndex);
    }

    /// Creates a new in-progress frame assembly with the current reference-frame headers.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by runtime reference slot
    /// @param streamOffset the byte offset of the first frame OBU
    /// @param obuIndex the zero-based index of the first frame OBU
    public FrameAssembly(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            @Nullable FrameHeader[] referenceFrameHeaders,
            long streamOffset,
            int obuIndex
    ) {
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        @Nullable FrameHeader[] nonNullReferenceFrameHeaders =
                Objects.requireNonNull(referenceFrameHeaders, "referenceFrameHeaders");
        if (nonNullReferenceFrameHeaders.length != 8) {
            throw new IllegalArgumentException("referenceFrameHeaders.length != 8: " + nonNullReferenceFrameHeaders.length);
        }
        this.referenceFrameHeaders = Arrays.copyOf(nonNullReferenceFrameHeaders, nonNullReferenceFrameHeaders.length);
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset < 0: " + streamOffset);
        }
        if (obuIndex < 0) {
            throw new IllegalArgumentException("obuIndex < 0: " + obuIndex);
        }

        int tileCount = frameHeader.tiling().columns() * frameHeader.tiling().rows();
        if (tileCount <= 0) {
            throw new IllegalArgumentException("Frame header does not describe any tiles");
        }

        this.streamOffset = streamOffset;
        this.obuIndex = obuIndex;
        this.totalTiles = tileCount;
    }

    /// Returns the active sequence header for this frame.
    ///
    /// @return the active sequence header for this frame
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the parsed frame header for this frame.
    ///
    /// @return the parsed frame header for this frame
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
    ///
    /// @param referenceFrame the internal LAST..ALTREF reference index
    /// @return the refreshed reference-frame header for the supplied reference, or `null`
    public @Nullable FrameHeader referenceFrameHeader(int referenceFrame) {
        int referenceSlot = frameHeader.referenceFrameIndex(referenceFrame);
        if (referenceSlot < 0 || referenceSlot >= referenceFrameHeaders.length) {
            return null;
        }
        return referenceFrameHeaders[referenceSlot];
    }

    /// Returns the byte offset of the first OBU that belongs to this frame.
    ///
    /// @return the byte offset of the first frame OBU
    public long streamOffset() {
        return streamOffset;
    }

    /// Returns the zero-based index of the first OBU that belongs to this frame.
    ///
    /// @return the zero-based index of the first frame OBU
    public int obuIndex() {
        return obuIndex;
    }

    /// Returns the total number of tiles declared by the frame header.
    ///
    /// @return the total number of tiles declared by the frame header
    public int totalTiles() {
        return totalTiles;
    }

    /// Returns the next tile index that must be covered by the next tile group.
    ///
    /// @return the next tile index that must be covered
    public int nextTileIndex() {
        return nextTileIndex;
    }

    /// Returns whether the tile groups collected so far cover the whole frame.
    ///
    /// @return whether the tile groups collected so far cover the whole frame
    public boolean isComplete() {
        return nextTileIndex >= totalTiles;
    }

    /// Returns the number of tile groups collected so far.
    ///
    /// @return the number of tile groups collected so far
    public int tileGroupCount() {
        return tileGroups.size();
    }

    /// Returns the number of tiles collected so far across all tile groups.
    ///
    /// @return the number of tiles collected so far across all tile groups
    public int collectedTileCount() {
        return nextTileIndex;
    }

    /// Returns a snapshot of the collected tile groups.
    ///
    /// @return a snapshot of the collected tile groups
    public TileGroup[] tileGroups() {
        return tileGroups.toArray(new TileGroup[0]);
    }

    /// Returns the collected tile bitstreams in frame order.
    ///
    /// @return the collected tile bitstreams in frame order
    public TileBitstream[] collectedTiles() {
        TileBitstream[] tiles = new TileBitstream[nextTileIndex];
        int outputIndex = 0;
        for (TileGroup tileGroup : tileGroups) {
            for (TileBitstream tile : tileGroup.tiles) {
                tiles[outputIndex++] = tile;
            }
        }
        return tiles;
    }

    /// Returns a collected tile bitstream by tile index.
    ///
    /// @param tileIndex the zero-based tile index within the frame
    /// @return the collected tile bitstream
    public TileBitstream tileBitstream(int tileIndex) {
        if (tileIndex < 0 || tileIndex >= nextTileIndex) {
            throw new IllegalArgumentException("Tile index out of collected range: " + tileIndex);
        }
        for (TileGroup tileGroup : tileGroups) {
            for (TileBitstream tile : tileGroup.tiles) {
                if (tile.tileIndex() == tileIndex) {
                    return tile;
                }
            }
        }
        throw new IllegalStateException("Collected tile index was not found: " + tileIndex);
    }

    /// Appends tile-group metadata and advances the expected tile cursor.
    ///
    /// @param sourceObu the source OBU that carried the tile group
    /// @param header the parsed tile-group header
    /// @param tileDataOffset the byte offset of the tile data inside the OBU payload
    /// @param tileDataLength the byte length of the tile data inside the OBU payload
    /// @param tiles the parsed per-tile bitstream views
    public void addTileGroup(
            ObuPacket sourceObu,
            TileGroupHeader header,
            int tileDataOffset,
            int tileDataLength,
            TileBitstream[] tiles
    ) {
        if (tileDataOffset < 0) {
            throw new IllegalArgumentException("tileDataOffset < 0: " + tileDataOffset);
        }
        if (tileDataLength < 0) {
            throw new IllegalArgumentException("tileDataLength < 0: " + tileDataLength);
        }
        if (header.totalTileCount() != totalTiles) {
            throw new IllegalArgumentException("Tile-group header belongs to a different frame layout");
        }
        Objects.requireNonNull(tiles, "tiles");
        if (tiles.length != header.tileCount()) {
            throw new IllegalArgumentException("Tile entry count does not match the tile-group header");
        }

        tileGroups.add(new TileGroup(sourceObu, header, tileDataOffset, tileDataLength, tiles));
        nextTileIndex = header.endTileIndex() + 1;
    }

    /// Tile-group payload metadata collected while assembling a frame.
    @NotNullByDefault
    public static final class TileGroup {
        /// The source OBU that carried the tile-group payload.
        private final ObuPacket sourceObu;
        /// The parsed tile-group header.
        private final TileGroupHeader header;
        /// The byte offset of the tile data inside the source OBU payload.
        private final int tileDataOffset;
        /// The byte length of the tile data inside the source OBU payload.
        private final int tileDataLength;
        /// The parsed per-tile bitstream views inside this tile group.
        private final TileBitstream @Unmodifiable [] tiles;

        /// Creates tile-group payload metadata.
        ///
        /// @param sourceObu the source OBU that carried the tile-group payload
        /// @param header the parsed tile-group header
        /// @param tileDataOffset the byte offset of the tile data inside the OBU payload
        /// @param tileDataLength the byte length of the tile data inside the OBU payload
        /// @param tiles the parsed per-tile bitstream views inside this tile group
        public TileGroup(
                ObuPacket sourceObu,
                TileGroupHeader header,
                int tileDataOffset,
                int tileDataLength,
                TileBitstream[] tiles
        ) {
            this.sourceObu = Objects.requireNonNull(sourceObu, "sourceObu");
            this.header = Objects.requireNonNull(header, "header");
            if (tileDataOffset < 0 || tileDataOffset > sourceObu.payload().length) {
                throw new IllegalArgumentException("tileDataOffset out of range: " + tileDataOffset);
            }
            if (tileDataLength < 0 || tileDataOffset + tileDataLength > sourceObu.payload().length) {
                throw new IllegalArgumentException("tileDataLength out of range: " + tileDataLength);
            }
            Objects.requireNonNull(tiles, "tiles");
            if (tiles.length != header.tileCount()) {
                throw new IllegalArgumentException("Tile entry count does not match the tile-group header");
            }
            this.tileDataOffset = tileDataOffset;
            this.tileDataLength = tileDataLength;
            this.tiles = Arrays.copyOf(tiles, tiles.length);
        }

        /// Returns the source OBU that carried the tile-group payload.
        ///
        /// @return the source OBU that carried the tile-group payload
        public ObuPacket sourceObu() {
            return sourceObu;
        }

        /// Returns the parsed tile-group header.
        ///
        /// @return the parsed tile-group header
        public TileGroupHeader header() {
            return header;
        }

        /// Returns the byte offset of the tile data inside the OBU payload.
        ///
        /// @return the byte offset of the tile data inside the OBU payload
        public int tileDataOffset() {
            return tileDataOffset;
        }

        /// Returns the byte length of the tile data inside the OBU payload.
        ///
        /// @return the byte length of the tile data inside the OBU payload
        public int tileDataLength() {
            return tileDataLength;
        }

        /// Returns the parsed per-tile bitstream views inside this tile group.
        ///
        /// @return the parsed per-tile bitstream views inside this tile group
        public TileBitstream[] tiles() {
            return Arrays.copyOf(tiles, tiles.length);
        }
    }
}
