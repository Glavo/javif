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
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;

/// Structural frame decoder that expands every tile into partition trees and saved motion-vector fields.
@NotNullByDefault
public final class FrameSyntaxDecoder {
    /// The optional compact reference state that provides the inherited frame CDF context.
    private final @Nullable ReferenceFrameSyntaxState referenceCdfFrameSyntaxState;

    /// The compact reference-frame syntax states indexed by runtime reference slot.
    private final @Nullable ReferenceFrameSyntaxState @Unmodifiable [] referenceFrameSyntaxStates;

    /// Whether decoded conformance values outside their specified ranges must be rejected.
    private final boolean strictStdCompliance;

    /// Creates one structural frame decoder.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides the inherited frame CDF context
    public FrameSyntaxDecoder(@Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult) {
        this(
                referenceCdfFrameSyntaxResult == null
                        ? null
                        : ReferenceFrameSyntaxState.from(referenceCdfFrameSyntaxResult),
                new ReferenceFrameSyntaxState[8]
        );
    }

    /// Creates one structural frame decoder with compact runtime reference syntax states.
    ///
    /// @param referenceCdfFrameSyntaxState the optional compact reference state that provides the inherited frame CDF context
    /// @param referenceFrameSyntaxStates compact syntax states indexed by runtime reference slot
    public FrameSyntaxDecoder(
            @Nullable ReferenceFrameSyntaxState referenceCdfFrameSyntaxState,
            @Nullable ReferenceFrameSyntaxState[] referenceFrameSyntaxStates
    ) {
        this(referenceCdfFrameSyntaxState, referenceFrameSyntaxStates, false);
    }

    /// Creates one structural frame decoder with runtime references and strict conformance policy.
    ///
    /// @param referenceCdfFrameSyntaxState the optional compact reference state that provides the inherited frame CDF context
    /// @param referenceFrameSyntaxStates compact syntax states indexed by runtime reference slot
    /// @param strictStdCompliance whether decoded conformance values outside their ranges must be rejected
    public FrameSyntaxDecoder(
            @Nullable ReferenceFrameSyntaxState referenceCdfFrameSyntaxState,
            @Nullable ReferenceFrameSyntaxState[] referenceFrameSyntaxStates,
            boolean strictStdCompliance
    ) {
        this.referenceCdfFrameSyntaxState = referenceCdfFrameSyntaxState;
        @Nullable ReferenceFrameSyntaxState[] nonNullReferenceFrameSyntaxStates =
                Objects.requireNonNull(referenceFrameSyntaxStates, "referenceFrameSyntaxStates");
        if (nonNullReferenceFrameSyntaxStates.length != 8) {
            throw new IllegalArgumentException(
                    "referenceFrameSyntaxStates.length != 8: " + nonNullReferenceFrameSyntaxStates.length
            );
        }
        this.referenceFrameSyntaxStates = nonNullReferenceFrameSyntaxStates.clone();
        this.strictStdCompliance = strictStdCompliance;
    }

    /// Structurally decodes every collected tile in one completed frame assembly.
    ///
    /// @param assembly the completed frame assembly to decode
    /// @return the structural frame-decode result
    /// @throws InvalidFrameSyntaxException if decoded tile syntax violates AV1 structural constraints
    public FrameSyntaxDecodeResult decode(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        if (!nonNullAssembly.isComplete()) {
            throw new IllegalArgumentException("Frame assembly is incomplete");
        }
        int tileCount = nonNullAssembly.totalTiles();
        ReferenceMotionVectorProjection referenceMotionVectorProjection =
                ReferenceMotionVectorProjection.create(nonNullAssembly, referenceFrameSyntaxStates);
        @Nullable SegmentIdMap referenceSegmentIdMap = referenceSegmentIdMap(nonNullAssembly);
        SegmentIdMap currentSegmentIdMap = SegmentIdMap.create(nonNullAssembly);
        FrameHeader.SegmentationInfo segmentation = nonNullAssembly.frameHeader().segmentation();
        if (segmentation.enabled() && !segmentation.updateMap() && referenceSegmentIdMap != null) {
            currentSegmentIdMap.copyFrom(referenceSegmentIdMap);
        }
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
                    currentSegmentIdMap,
                    referenceSegmentIdMap,
                    inheritedCdfContext
            );
            TilePartitionTreeReader treeReader = new TilePartitionTreeReader(tileContext, strictStdCompliance);
            try {
                tileRoots[tileIndex] = treeReader.readTile();
            } catch (IntrabcDisplacementValidator.InvalidDisplacementVectorException exception) {
                throw new InvalidFrameSyntaxException(exception.getMessage(), exception);
            }
            restorationUnitMap.mergeFrom(tileContext.restorationUnitMap());
            decodedTemporalMotionFields[tileIndex] = tileContext.decodedTemporalMotionField().copy();
            finalTileCdfContexts[tileIndex] = tileContext.cdfContext().copy();
        }
        return new FrameSyntaxDecodeResult(
                nonNullAssembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                currentSegmentIdMap,
                true
        );
    }

    /// Creates one tile-local decode context with compatible inherited entropy state.
    ///
    /// @param assembly the completed frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index in frame order
    /// @param referenceMotionVectorProjection the immutable current-frame temporal projection
    /// @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
    /// @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
    /// @param inheritedCdfContext the inherited frame CDF context, or `null` to use defaults
    /// @return one tile-local decode context
    private TileDecodeContext createTileContext(
            FrameAssembly assembly,
            int tileIndex,
            ReferenceMotionVectorProjection referenceMotionVectorProjection,
            SegmentIdMap currentSegmentIdMap,
            @Nullable SegmentIdMap referenceSegmentIdMap,
            @Nullable CdfContext inheritedCdfContext
    ) {
        @Nullable CdfContext baseCdfContext = inheritedCdfContext;
        if (baseCdfContext == null) {
            baseCdfContext = CdfContext.createDefault(assembly.frameHeader().quantization().baseQIndex());
        }
        return TileDecodeContext.create(
                assembly,
                tileIndex,
                baseCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap
        );
    }

    /// Returns the compatible primary-reference segment-id map required by the current frame.
    ///
    /// AV1 temporal segmentation prediction and non-updating segmentation maps use the syntax
    /// snapshot selected by `primary_ref_frame`. A map from differently sized coded dimensions is
    /// not compatible and is treated as absent.
    ///
    /// @param assembly the current completed frame assembly
    /// @return an independent compatible reference map, or `null`
    private @Nullable SegmentIdMap referenceSegmentIdMap(FrameAssembly assembly) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        FrameHeader.SegmentationInfo segmentation = frameHeader.segmentation();
        if (!segmentation.enabled() || (segmentation.updateMap() && !segmentation.temporalUpdate())) {
            return null;
        }

        int primaryRefFrame = frameHeader.primaryRefFrame();
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null;
        }
        int primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame);
        if (primarySlot < 0 || primarySlot >= referenceFrameSyntaxStates.length) {
            return null;
        }
        @Nullable ReferenceFrameSyntaxState referenceState = referenceFrameSyntaxStates[primarySlot];
        if (referenceState == null) {
            return null;
        }

        SegmentIdMap referenceMap = referenceState.segmentIdMap();
        int width4 = ((frameHeader.frameSize().codedWidth() + 7) >> 3) << 1;
        int height4 = ((frameHeader.frameSize().height() + 7) >> 3) << 1;
        return referenceMap.hasDimensions(width4, height4) ? referenceMap : null;
    }

    /// Returns the single frame CDF context selected by the reference frame, or `null` when no
    /// reference context exists.
    ///
    /// @return the inherited frame CDF context, or `null`
    private @Nullable CdfContext referenceCdfContext() {
        if (referenceCdfFrameSyntaxState == null) {
            return null;
        }
        return referenceCdfFrameSyntaxState.savedFrameCdfContext();
    }
}
