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
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Tile-local decode state derived from a fully assembled frame and one collected tile bitstream.
///
/// Pixel-space end coordinates are exclusive.
@NotNullByDefault
public final class TileDecodeContext {
    /// The frame assembly that owns the tile.
    private final FrameAssembly assembly;

    /// The active sequence header for the tile.
    private final SequenceHeader sequenceHeader;

    /// The active frame header for the tile.
    private final FrameHeader frameHeader;

    /// The selected tile bitstream.
    private final TileBitstream tileBitstream;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// The zero-based tile index within the frame.
    private final int tileIndex;

    /// The zero-based tile row within the frame.
    private final int tileRow;

    /// The zero-based tile column within the frame.
    private final int tileColumn;

    /// The superblock size in pixels for the active sequence.
    private final int superblockSize;

    /// The inclusive start superblock column for the tile.
    private final int columnStartSuperblock;

    /// The exclusive end superblock column for the tile.
    private final int columnEndSuperblock;

    /// The inclusive start superblock row for the tile.
    private final int rowStartSuperblock;

    /// The exclusive end superblock row for the tile.
    private final int rowEndSuperblock;

    /// The inclusive start X coordinate in pixels.
    private final int startX;

    /// The exclusive end X coordinate in pixels.
    private final int endX;

    /// The inclusive start Y coordinate in pixels.
    private final int startY;

    /// The exclusive end Y coordinate in pixels.
    private final int endY;

    /// Creates tile-local decode state.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param sequenceHeader the active sequence header for the tile
    /// @param frameHeader the active frame header for the tile
    /// @param tileBitstream the selected tile bitstream
    /// @param msacDecoder the tile-local arithmetic decoder
    /// @param cdfContext the tile-local mutable CDF context
    /// @param tileIndex the zero-based tile index within the frame
    /// @param tileRow the zero-based tile row within the frame
    /// @param tileColumn the zero-based tile column within the frame
    /// @param superblockSize the superblock size in pixels
    /// @param columnStartSuperblock the inclusive start superblock column
    /// @param columnEndSuperblock the exclusive end superblock column
    /// @param rowStartSuperblock the inclusive start superblock row
    /// @param rowEndSuperblock the exclusive end superblock row
    /// @param startX the inclusive start X coordinate in pixels
    /// @param endX the exclusive end X coordinate in pixels
    /// @param startY the inclusive start Y coordinate in pixels
    /// @param endY the exclusive end Y coordinate in pixels
    private TileDecodeContext(
            FrameAssembly assembly,
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            TileBitstream tileBitstream,
            MsacDecoder msacDecoder,
            CdfContext cdfContext,
            int tileIndex,
            int tileRow,
            int tileColumn,
            int superblockSize,
            int columnStartSuperblock,
            int columnEndSuperblock,
            int rowStartSuperblock,
            int rowEndSuperblock,
            int startX,
            int endX,
            int startY,
            int endY
    ) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.tileBitstream = Objects.requireNonNull(tileBitstream, "tileBitstream");
        this.msacDecoder = Objects.requireNonNull(msacDecoder, "msacDecoder");
        this.cdfContext = Objects.requireNonNull(cdfContext, "cdfContext");
        this.tileIndex = tileIndex;
        this.tileRow = tileRow;
        this.tileColumn = tileColumn;
        this.superblockSize = superblockSize;
        this.columnStartSuperblock = columnStartSuperblock;
        this.columnEndSuperblock = columnEndSuperblock;
        this.rowStartSuperblock = rowStartSuperblock;
        this.rowEndSuperblock = rowEndSuperblock;
        this.startX = startX;
        this.endX = endX;
        this.startY = startY;
        this.endY = endY;
    }

    /// Creates tile-local decode state with a fresh default CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex) {
        return create(assembly, tileIndex, CdfContext.createDefault());
    }

    /// Creates tile-local decode state with a copy of the supplied base CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base CDF context template to copy for this tile
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex, CdfContext baseCdfContext) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        CdfContext copiedCdfContext = Objects.requireNonNull(baseCdfContext, "baseCdfContext").copy();
        TileBitstream tileBitstream = nonNullAssembly.tileBitstream(tileIndex);
        SequenceHeader sequenceHeader = nonNullAssembly.sequenceHeader();
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();

        int columns = tiling.columns();
        int tileRow = tileIndex / columns;
        int tileColumn = tileIndex % columns;
        int[] columnStarts = tiling.columnStartSuperblocks();
        int[] rowStarts = tiling.rowStartSuperblocks();
        int columnStartSuperblock = columnStarts[tileColumn];
        int columnEndSuperblock = columnStarts[tileColumn + 1];
        int rowStartSuperblock = rowStarts[tileRow];
        int rowEndSuperblock = rowStarts[tileRow + 1];
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int startX = columnStartSuperblock * superblockSize;
        int endX = Math.min(frameHeader.frameSize().codedWidth(), columnEndSuperblock * superblockSize);
        int startY = rowStartSuperblock * superblockSize;
        int endY = Math.min(frameHeader.frameSize().height(), rowEndSuperblock * superblockSize);

        return new TileDecodeContext(
                nonNullAssembly,
                sequenceHeader,
                frameHeader,
                tileBitstream,
                tileBitstream.openMsacDecoder(frameHeader.disableCdfUpdate()),
                copiedCdfContext,
                tileIndex,
                tileRow,
                tileColumn,
                superblockSize,
                columnStartSuperblock,
                columnEndSuperblock,
                rowStartSuperblock,
                rowEndSuperblock,
                startX,
                endX,
                startY,
                endY
        );
    }

    /// Returns the frame assembly that owns the tile.
    ///
    /// @return the frame assembly that owns the tile
    public FrameAssembly assembly() {
        return assembly;
    }

    /// Returns the active sequence header for the tile.
    ///
    /// @return the active sequence header for the tile
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the active frame header for the tile.
    ///
    /// @return the active frame header for the tile
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the selected tile bitstream.
    ///
    /// @return the selected tile bitstream
    public TileBitstream tileBitstream() {
        return tileBitstream;
    }

    /// Returns the tile-local arithmetic decoder.
    ///
    /// @return the tile-local arithmetic decoder
    public MsacDecoder msacDecoder() {
        return msacDecoder;
    }

    /// Returns the tile-local mutable CDF context.
    ///
    /// @return the tile-local mutable CDF context
    public CdfContext cdfContext() {
        return cdfContext;
    }

    /// Returns the zero-based tile index within the frame.
    ///
    /// @return the zero-based tile index within the frame
    public int tileIndex() {
        return tileIndex;
    }

    /// Returns the zero-based tile row within the frame.
    ///
    /// @return the zero-based tile row within the frame
    public int tileRow() {
        return tileRow;
    }

    /// Returns the zero-based tile column within the frame.
    ///
    /// @return the zero-based tile column within the frame
    public int tileColumn() {
        return tileColumn;
    }

    /// Returns the superblock size in pixels for the active sequence.
    ///
    /// @return the superblock size in pixels for the active sequence
    public int superblockSize() {
        return superblockSize;
    }

    /// Returns the inclusive start superblock column for the tile.
    ///
    /// @return the inclusive start superblock column for the tile
    public int columnStartSuperblock() {
        return columnStartSuperblock;
    }

    /// Returns the exclusive end superblock column for the tile.
    ///
    /// @return the exclusive end superblock column for the tile
    public int columnEndSuperblock() {
        return columnEndSuperblock;
    }

    /// Returns the inclusive start superblock row for the tile.
    ///
    /// @return the inclusive start superblock row for the tile
    public int rowStartSuperblock() {
        return rowStartSuperblock;
    }

    /// Returns the exclusive end superblock row for the tile.
    ///
    /// @return the exclusive end superblock row for the tile
    public int rowEndSuperblock() {
        return rowEndSuperblock;
    }

    /// Returns the inclusive start X coordinate in pixels.
    ///
    /// @return the inclusive start X coordinate in pixels
    public int startX() {
        return startX;
    }

    /// Returns the exclusive end X coordinate in pixels.
    ///
    /// @return the exclusive end X coordinate in pixels
    public int endX() {
        return endX;
    }

    /// Returns the inclusive start Y coordinate in pixels.
    ///
    /// @return the inclusive start Y coordinate in pixels
    public int startY() {
        return startY;
    }

    /// Returns the exclusive end Y coordinate in pixels.
    ///
    /// @return the exclusive end Y coordinate in pixels
    public int endY() {
        return endY;
    }

    /// Returns the tile width in pixels.
    ///
    /// @return the tile width in pixels
    public int width() {
        return endX - startX;
    }

    /// Returns the tile height in pixels.
    ///
    /// @return the tile height in pixels
    public int height() {
        return endY - startY;
    }
}
