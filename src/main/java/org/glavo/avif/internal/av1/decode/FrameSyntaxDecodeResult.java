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
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Structural frame-decode result produced before pixel reconstruction begins.
@NotNullByDefault
public final class FrameSyntaxDecodeResult {
    /// The fully assembled frame that was structurally decoded.
    private final FrameAssembly assembly;

    /// The decoded top-level partition roots for each tile in frame order.
    private final TilePartitionTreeReader.Node @Unmodifiable [] @Unmodifiable [] tileRoots;

    /// The tile-local temporal motion fields produced while decoding the current frame.
    private final TileDecodeContext.TemporalMotionField @Unmodifiable [] decodedTemporalMotionFields;

    /// The decoded frame-level loop-restoration unit syntax.
    private final RestorationUnitMap restorationUnitMap;

    /// The final tile-local CDF contexts produced while decoding the current frame.
    private final CdfContext @Unmodifiable [] finalTileCdfContexts;

    /// Creates one structural frame-decode result.
    ///
    /// Final tile CDF contexts default to fresh `CdfContext.createDefault()` copies.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                RestorationUnitMap.createEmpty(Objects.requireNonNull(assembly, "assembly")),
                createDefaultTileCdfContexts(Objects.requireNonNull(assembly, "assembly").totalTiles())
        );
    }

    /// Creates one structural frame-decode result.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            CdfContext[] finalTileCdfContexts
    ) {
        this(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                RestorationUnitMap.createEmpty(Objects.requireNonNull(assembly, "assembly")),
                finalTileCdfContexts
        );
    }

    /// Creates one structural frame-decode result.
    ///
    /// @param assembly the fully assembled frame that was structurally decoded
    /// @param tileRoots the decoded top-level partition roots for each tile in frame order
    /// @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
    /// @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
    /// @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
    public FrameSyntaxDecodeResult(
            FrameAssembly assembly,
            TilePartitionTreeReader.Node[][] tileRoots,
            TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields,
            RestorationUnitMap restorationUnitMap,
            CdfContext[] finalTileCdfContexts
    ) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(tileRoots, "tileRoots");
        Objects.requireNonNull(decodedTemporalMotionFields, "decodedTemporalMotionFields");
        Objects.requireNonNull(finalTileCdfContexts, "finalTileCdfContexts");
        if (tileRoots.length != assembly.totalTiles()) {
            throw new IllegalArgumentException("tileRoots.length != totalTiles: " + tileRoots.length);
        }
        if (decodedTemporalMotionFields.length != assembly.totalTiles()) {
            throw new IllegalArgumentException(
                    "decodedTemporalMotionFields.length != totalTiles: " + decodedTemporalMotionFields.length
            );
        }
        if (finalTileCdfContexts.length != assembly.totalTiles()) {
            throw new IllegalArgumentException("finalTileCdfContexts.length != totalTiles: " + finalTileCdfContexts.length);
        }

        this.tileRoots = new TilePartitionTreeReader.Node[tileRoots.length][];
        for (int i = 0; i < tileRoots.length; i++) {
            this.tileRoots[i] = Arrays.copyOf(Objects.requireNonNull(tileRoots[i], "tileRoots[" + i + "]"), tileRoots[i].length);
        }
        this.decodedTemporalMotionFields = new TileDecodeContext.TemporalMotionField[decodedTemporalMotionFields.length];
        for (int i = 0; i < decodedTemporalMotionFields.length; i++) {
            this.decodedTemporalMotionFields[i] = Objects.requireNonNull(
                    decodedTemporalMotionFields[i],
                    "decodedTemporalMotionFields[" + i + "]"
            ).copy();
        }
        this.restorationUnitMap = Objects.requireNonNull(restorationUnitMap, "restorationUnitMap").copy();
        this.finalTileCdfContexts = new CdfContext[finalTileCdfContexts.length];
        for (int i = 0; i < finalTileCdfContexts.length; i++) {
            this.finalTileCdfContexts[i] = Objects.requireNonNull(finalTileCdfContexts[i], "finalTileCdfContexts[" + i + "]").copy();
        }
    }

    /// Returns the fully assembled frame that was structurally decoded.
    ///
    /// @return the fully assembled frame that was structurally decoded
    public FrameAssembly assembly() {
        return assembly;
    }

    /// Returns the number of decoded tiles in this frame result.
    ///
    /// @return the number of decoded tiles in this frame result
    public int tileCount() {
        return tileRoots.length;
    }

    /// Returns a snapshot of the decoded top-level partition roots for every tile.
    ///
    /// @return a snapshot of the decoded top-level partition roots for every tile
    public TilePartitionTreeReader.Node[][] tileRoots() {
        TilePartitionTreeReader.Node[][] copy = new TilePartitionTreeReader.Node[tileRoots.length][];
        for (int i = 0; i < tileRoots.length; i++) {
            copy[i] = Arrays.copyOf(tileRoots[i], tileRoots[i].length);
        }
        return copy;
    }

    /// Returns a snapshot of the decoded top-level partition roots for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the decoded top-level partition roots for one tile
    public TilePartitionTreeReader.Node[] tileRoots(int tileIndex) {
        int checkedTileIndex = checkedTileIndex(tileIndex);
        return Arrays.copyOf(tileRoots[checkedTileIndex], tileRoots[checkedTileIndex].length);
    }

    /// Returns a snapshot of the tile-local temporal motion fields for every tile.
    ///
    /// @return a snapshot of the tile-local temporal motion fields for every tile
    public TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields() {
        TileDecodeContext.TemporalMotionField[] copy = new TileDecodeContext.TemporalMotionField[decodedTemporalMotionFields.length];
        for (int i = 0; i < decodedTemporalMotionFields.length; i++) {
            copy[i] = decodedTemporalMotionFields[i].copy();
        }
        return copy;
    }

    /// Returns a snapshot of the tile-local temporal motion field for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the tile-local temporal motion field for one tile
    public TileDecodeContext.TemporalMotionField decodedTemporalMotionField(int tileIndex) {
        return decodedTemporalMotionFields[checkedTileIndex(tileIndex)].copy();
    }

    /// Returns the temporal motion block stored at one frame-relative 8x8 coordinate, or `null`.
    ///
    /// The returned block is immutable and remains valid for the lifetime of this result. Coordinates
    /// outside the coded frame return `null`.
    ///
    /// @param x8 the frame-relative X coordinate in 8x8 units
    /// @param y8 the frame-relative Y coordinate in 8x8 units
    /// @return the temporal motion block at the supplied coordinate, or `null`
    @Nullable TileDecodeContext.TemporalMotionBlock decodedTemporalMotionBlockAt(int x8, int y8) {
        if (x8 < 0 || y8 < 0) {
            return null;
        }
        FrameAssembly resultAssembly = assembly;
        int frameWidth8 = (resultAssembly.frameHeader().frameSize().codedWidth() + 7) >> 3;
        int frameHeight8 = (resultAssembly.frameHeader().frameSize().height() + 7) >> 3;
        if (x8 >= frameWidth8 || y8 >= frameHeight8) {
            return null;
        }

        int superblockSize8 = resultAssembly.sequenceHeader().features().use128x128Superblocks() ? 16 : 8;
        int[] columnStarts = resultAssembly.frameHeader().tiling().columnStartSuperblocks();
        int[] rowStarts = resultAssembly.frameHeader().tiling().rowStartSuperblocks();
        int tileColumn = containingTileAxis(columnStarts, x8 / superblockSize8);
        int tileRow = containingTileAxis(rowStarts, y8 / superblockSize8);
        int tileIndex = tileRow * resultAssembly.frameHeader().tiling().columns() + tileColumn;
        int localX8 = x8 - columnStarts[tileColumn] * superblockSize8;
        int localY8 = y8 - rowStarts[tileRow] * superblockSize8;
        TileDecodeContext.TemporalMotionField field = decodedTemporalMotionFields[tileIndex];
        if (localX8 >= field.width8() || localY8 >= field.height8()) {
            return null;
        }
        return field.block(localX8, localY8);
    }

    /// Returns a snapshot of the decoded loop-restoration unit syntax.
    ///
    /// @return a snapshot of the decoded loop-restoration unit syntax
    public RestorationUnitMap restorationUnitMap() {
        return restorationUnitMap.copy();
    }

    /// Returns a snapshot of the final tile-local CDF contexts for every tile.
    ///
    /// @return a snapshot of the final tile-local CDF contexts for every tile
    public CdfContext[] finalTileCdfContexts() {
        CdfContext[] copy = new CdfContext[finalTileCdfContexts.length];
        for (int i = 0; i < finalTileCdfContexts.length; i++) {
            copy[i] = finalTileCdfContexts[i].copy();
        }
        return copy;
    }

    /// Returns a snapshot of the final tile-local CDF context for one tile.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return a snapshot of the final tile-local CDF context for one tile
    public CdfContext finalTileCdfContext(int tileIndex) {
        return finalTileCdfContexts[checkedTileIndex(tileIndex)].copy();
    }

    /// Returns the final CDF context selected by `context_update_tile_id`.
    ///
    /// This is the single frame context saved for later `primary_ref_frame` inheritance when the
    /// frame-end CDF update is enabled.
    ///
    /// @return a snapshot of the selected frame-end CDF context
    public CdfContext contextUpdateTileCdfContext() {
        return finalTileCdfContext(assembly.frameHeader().tiling().updateTileIndex());
    }

    /// Returns a copy of this structural frame-decode result with replaced final tile-local CDF contexts.
    ///
    /// Partition trees and decoded temporal motion fields are preserved while the supplied tile-local
    /// CDF snapshot becomes the new stored entropy state.
    ///
    /// @param replacementTileCdfContexts the replacement final tile-local CDF contexts
    /// @return a copy of this structural frame-decode result with replaced final tile-local CDF contexts
    public FrameSyntaxDecodeResult withFinalTileCdfContexts(CdfContext[] replacementTileCdfContexts) {
        return new FrameSyntaxDecodeResult(
                assembly,
                tileRoots(),
                decodedTemporalMotionFields(),
                restorationUnitMap,
                replacementTileCdfContexts
        );
    }

    /// Validates and returns one tile index.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the validated tile index
    private int checkedTileIndex(int tileIndex) {
        if (tileIndex < 0 || tileIndex >= tileRoots.length) {
            throw new IndexOutOfBoundsException("tileIndex out of range: " + tileIndex);
        }
        return tileIndex;
    }

    /// Returns the tile-axis index containing one superblock coordinate.
    ///
    /// @param starts the monotonically increasing tile-axis boundary array
    /// @param superblockCoordinate the frame-relative superblock coordinate
    /// @return the zero-based tile-axis index containing the coordinate
    private static int containingTileAxis(int[] starts, int superblockCoordinate) {
        int low = 0;
        int high = starts.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (starts[middle] <= superblockCoordinate) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /// Creates default tile-local CDF contexts for the supplied tile count.
    ///
    /// @param tileCount the number of tiles in the frame
    /// @return default tile-local CDF contexts for the supplied tile count
    private static CdfContext[] createDefaultTileCdfContexts(int tileCount) {
        CdfContext[] contexts = new CdfContext[tileCount];
        for (int i = 0; i < tileCount; i++) {
            contexts[i] = CdfContext.createDefault();
        }
        return contexts;
    }
}
