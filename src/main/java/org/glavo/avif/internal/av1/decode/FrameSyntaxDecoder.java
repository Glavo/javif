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

import java.util.Objects;

/// Structural frame decoder that expands every tile into partition trees and saved motion-vector fields.
@NotNullByDefault
public final class FrameSyntaxDecoder {
    /// The optional reference-frame syntax result that provides tile-local base CDF contexts.
    private final @Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult;

    /// Creates one structural frame decoder.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides tile-local base CDF contexts
    public FrameSyntaxDecoder(@Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult) {
        this.referenceCdfFrameSyntaxResult = referenceCdfFrameSyntaxResult;
    }

    /// Structurally decodes every collected tile in one completed frame assembly.
    ///
    /// @param assembly the completed frame assembly to decode
    /// @return the structural frame-decode result
    /// @throws UnsupportedOperationException if the frame enables reference-frame motion vectors
    public FrameSyntaxDecodeResult decode(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        if (!nonNullAssembly.isComplete()) {
            throw new IllegalArgumentException("Frame assembly is incomplete");
        }
        if (nonNullAssembly.frameHeader().useReferenceFrameMotionVectors()) {
            throw new UnsupportedOperationException("Reference-frame motion-vector projection is not implemented");
        }

        int tileCount = nonNullAssembly.totalTiles();
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields =
                new TileDecodeContext.TemporalMotionField[tileCount];
        CdfContext[] finalTileCdfContexts = new CdfContext[tileCount];
        RestorationUnitMap restorationUnitMap = RestorationUnitMap.createEmpty(nonNullAssembly);
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            TileDecodeContext tileContext = createTileContext(nonNullAssembly, tileIndex);
            TilePartitionTreeReader treeReader = new TilePartitionTreeReader(tileContext);
            tileRoots[tileIndex] = treeReader.readTile();
            restorationUnitMap.mergeFrom(tileContext.restorationUnitMap());
            decodedTemporalMotionFields[tileIndex] = tileContext.decodedTemporalMotionField().copy();
            finalTileCdfContexts[tileIndex] = tileContext.cdfContext().copy();
        }
        return new FrameSyntaxDecodeResult(
                nonNullAssembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts
        );
    }

    /// Creates one tile-local decode context with compatible inherited entropy state.
    ///
    /// @param assembly the completed frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index in frame order
    /// @return one tile-local decode context
    private TileDecodeContext createTileContext(FrameAssembly assembly, int tileIndex) {
        @Nullable CdfContext baseCdfContext = referenceCdfContext(tileIndex);
        if (baseCdfContext == null) {
            return TileDecodeContext.create(assembly, tileIndex);
        }
        return TileDecodeContext.create(assembly, tileIndex, baseCdfContext);
    }

    /// Returns the reference tile-local CDF context for one tile, or `null` when no compatible context exists.
    ///
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the reference tile-local CDF context for one tile, or `null`
    private @Nullable CdfContext referenceCdfContext(int tileIndex) {
        if (referenceCdfFrameSyntaxResult == null || tileIndex >= referenceCdfFrameSyntaxResult.tileCount()) {
            return null;
        }
        return referenceCdfFrameSyntaxResult.finalTileCdfContext(tileIndex);
    }
}
