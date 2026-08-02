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

import java.util.Objects;

/// Structural frame decoder that expands every tile into partition trees and saved motion-vector fields.
@NotNullByDefault
public final class FrameSyntaxDecoder {
    /// The optional reference-frame syntax result that provides the inherited frame CDF context.
    private final @Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult;

    /// The reference-frame syntax snapshots indexed by runtime reference slot.
    private final @Nullable FrameSyntaxDecodeResult @Unmodifiable [] referenceFrameSyntaxResults;

    /// Creates one structural frame decoder.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides the inherited frame CDF context
    public FrameSyntaxDecoder(@Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult) {
        this(referenceCdfFrameSyntaxResult, new FrameSyntaxDecodeResult[8]);
    }

    /// Creates one structural frame decoder with runtime reference syntax snapshots.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides the inherited frame CDF context
    /// @param referenceFrameSyntaxResults the syntax snapshots indexed by runtime reference slot
    public FrameSyntaxDecoder(
            @Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult,
            @Nullable FrameSyntaxDecodeResult[] referenceFrameSyntaxResults
    ) {
        this.referenceCdfFrameSyntaxResult = referenceCdfFrameSyntaxResult;
        @Nullable FrameSyntaxDecodeResult[] nonNullReferenceFrameSyntaxResults =
                Objects.requireNonNull(referenceFrameSyntaxResults, "referenceFrameSyntaxResults");
        if (nonNullReferenceFrameSyntaxResults.length != 8) {
            throw new IllegalArgumentException(
                    "referenceFrameSyntaxResults.length != 8: " + nonNullReferenceFrameSyntaxResults.length
            );
        }
        this.referenceFrameSyntaxResults = nonNullReferenceFrameSyntaxResults.clone();
    }

    /// Structurally decodes every collected tile in one completed frame assembly.
    ///
    /// @param assembly the completed frame assembly to decode
    /// @return the structural frame-decode result
    public FrameSyntaxDecodeResult decode(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        if (!nonNullAssembly.isComplete()) {
            throw new IllegalArgumentException("Frame assembly is incomplete");
        }
        int tileCount = nonNullAssembly.totalTiles();
        ReferenceMotionVectorProjection referenceMotionVectorProjection =
                ReferenceMotionVectorProjection.create(nonNullAssembly, referenceFrameSyntaxResults);
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields =
                new TileDecodeContext.TemporalMotionField[tileCount];
        CdfContext[] finalTileCdfContexts = new CdfContext[tileCount];
        RestorationUnitMap restorationUnitMap = RestorationUnitMap.createEmpty(nonNullAssembly);
        @Nullable CdfContext inheritedCdfContext = referenceCdfContext();
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            TileDecodeContext tileContext = createTileContext(
                    nonNullAssembly,
                    tileIndex,
                    referenceMotionVectorProjection,
                    inheritedCdfContext
            );
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
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param inheritedCdfContext the inherited frame CDF context, or `null` to use defaults
    /// @return one tile-local decode context
    private TileDecodeContext createTileContext(
            FrameAssembly assembly,
            int tileIndex,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            @Nullable CdfContext inheritedCdfContext
    ) {
        @Nullable CdfContext baseCdfContext = inheritedCdfContext;
        if (baseCdfContext == null) {
            baseCdfContext = CdfContext.createDefault(assembly.frameHeader().quantization().baseQIndex());
        }
        return TileDecodeContext.create(assembly, tileIndex, baseCdfContext, referenceMotionVectorProjection);
    }

    /// Returns the single frame CDF context selected by the reference frame, or `null` when no
    /// reference context exists.
    ///
    /// @return the inherited frame CDF context, or `null`
    private @Nullable CdfContext referenceCdfContext() {
        if (referenceCdfFrameSyntaxResult == null) {
            return null;
        }
        return referenceCdfFrameSyntaxResult.contextUpdateTileCdfContext();
    }
}
