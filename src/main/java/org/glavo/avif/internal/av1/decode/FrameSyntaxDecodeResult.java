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

import java.util.Arrays;
import java.util.Objects;

/// Structural frame-decode result produced before pixel reconstruction begins.
@NotNullByDefault
public final class FrameSyntaxDecodeResult {
    /// The fully assembled frame that was structurally decoded.
    private final FrameAssembly assembly;

    /// The decoded top-level partition roots for each tile in frame order.
    private final TilePartitionTreeReader.Node[][] tileRoots;

    /// The tile-local temporal motion fields produced while decoding the current frame.
    private final TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields;

    /// The final tile-local CDF contexts produced while decoding the current frame.
    private final CdfContext[] finalTileCdfContexts;

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
