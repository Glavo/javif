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
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.MotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Structural frame decoder that expands every tile into partition trees and temporal motion fields.
@NotNullByDefault
public final class FrameSyntaxDecoder {
    /// The optional reference-frame syntax result that provides tile-local base CDF contexts.
    private final @Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult;

    /// The optional reference-frame syntax result that provides tile-local temporal motion fields.
    private final @Nullable FrameSyntaxDecodeResult referenceTemporalFrameSyntaxResult;

    /// Creates one structural frame decoder.
    ///
    /// The supplied snapshot is used for both CDF inheritance and temporal motion-field sampling.
    ///
    /// @param referenceFrameSyntaxResult the optional reference-frame syntax result used for both inheritance paths
    public FrameSyntaxDecoder(@Nullable FrameSyntaxDecodeResult referenceFrameSyntaxResult) {
        this(referenceFrameSyntaxResult, referenceFrameSyntaxResult);
    }

    /// Creates one structural frame decoder with separate reference snapshots for entropy and motion state.
    ///
    /// @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides tile-local base CDF contexts
    /// @param referenceTemporalFrameSyntaxResult the optional reference-frame syntax result that provides tile-local temporal motion fields
    public FrameSyntaxDecoder(
            @Nullable FrameSyntaxDecodeResult referenceCdfFrameSyntaxResult,
            @Nullable FrameSyntaxDecodeResult referenceTemporalFrameSyntaxResult
    ) {
        this.referenceCdfFrameSyntaxResult = referenceCdfFrameSyntaxResult;
        this.referenceTemporalFrameSyntaxResult = referenceTemporalFrameSyntaxResult;
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
        TilePartitionTreeReader.Node[][] tileRoots = new TilePartitionTreeReader.Node[tileCount][];
        TileDecodeContext.TemporalMotionField[] decodedTemporalMotionFields =
                new TileDecodeContext.TemporalMotionField[tileCount];
        CdfContext[] finalTileCdfContexts = new CdfContext[tileCount];
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            TileDecodeContext tileContext = createTileContext(nonNullAssembly, tileIndex);
            TilePartitionTreeReader treeReader = new TilePartitionTreeReader(tileContext);
            tileRoots[tileIndex] = treeReader.readTile();
            decodedTemporalMotionFields[tileIndex] = tileContext.decodedTemporalMotionField().copy();
            finalTileCdfContexts[tileIndex] = tileContext.cdfContext().copy();
        }
        return new FrameSyntaxDecodeResult(nonNullAssembly, tileRoots, decodedTemporalMotionFields, finalTileCdfContexts);
    }

    /// Creates one tile-local decode context, attaching reference temporal state when it matches.
    ///
    /// @param assembly the completed frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index in frame order
    /// @return one tile-local decode context
    private TileDecodeContext createTileContext(FrameAssembly assembly, int tileIndex) {
        @Nullable CdfContext baseCdfContext = referenceCdfContext(tileIndex);
        @Nullable TileDecodeContext.TemporalMotionField temporalMotionField = referenceTemporalMotionField(assembly, tileIndex);
        if (baseCdfContext == null && temporalMotionField == null) {
            return TileDecodeContext.create(assembly, tileIndex);
        }
        if (baseCdfContext == null) {
            return TileDecodeContext.create(assembly, tileIndex, temporalMotionField);
        }
        if (temporalMotionField == null) {
            return TileDecodeContext.create(assembly, tileIndex, baseCdfContext);
        }
        return TileDecodeContext.create(assembly, tileIndex, baseCdfContext, temporalMotionField);
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

    /// Returns the reference temporal motion field for one tile, projected to the current tile geometry.
    ///
    /// @param assembly the completed frame assembly that owns the current tile geometry
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the reference temporal motion field for one tile, projected to the current tile geometry, or `null`
    private @Nullable TileDecodeContext.TemporalMotionField referenceTemporalMotionField(FrameAssembly assembly, int tileIndex) {
        if (referenceTemporalFrameSyntaxResult == null || tileIndex >= referenceTemporalFrameSyntaxResult.tileCount()) {
            return null;
        }
        return projectTemporalMotionField(
                Objects.requireNonNull(assembly, "assembly"),
                tileIndex,
                referenceTemporalFrameSyntaxResult.decodedTemporalMotionField(tileIndex)
        );
    }

    /// Projects one stored temporal motion field onto the current tile grid.
    ///
    /// Compatible fields are returned unchanged. When the reference tile grid differs, each current
    /// 8x8 cell samples the center-mapped reference cell and scales carried motion vectors by the
    /// tile-grid ratio in each axis.
    ///
    /// @param assembly the completed frame assembly that owns the current tile geometry
    /// @param tileIndex the zero-based tile index in frame order
    /// @param source the reference temporal motion field to project
    /// @return the temporal motion field projected to the current tile grid
    private static TileDecodeContext.TemporalMotionField projectTemporalMotionField(
            FrameAssembly assembly,
            int tileIndex,
            TileDecodeContext.TemporalMotionField source
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        TileDecodeContext.TemporalMotionField nonNullSource = Objects.requireNonNull(source, "source");
        int[] dimensions = temporalMotionFieldDimensions(nonNullAssembly, tileIndex);
        int width8 = dimensions[0];
        int height8 = dimensions[1];
        if (nonNullSource.width8() == width8 && nonNullSource.height8() == height8) {
            return nonNullSource;
        }

        TileDecodeContext.TemporalMotionField projected = new TileDecodeContext.TemporalMotionField(width8, height8);
        if (width8 == 0 || height8 == 0 || nonNullSource.width8() == 0 || nonNullSource.height8() == 0) {
            return projected;
        }

        TileDecodeContext.TemporalMotionBlock[] scaledBlocks =
                new TileDecodeContext.TemporalMotionBlock[nonNullSource.width8() * nonNullSource.height8()];
        for (int y8 = 0; y8 < height8; y8++) {
            int sourceY8 = centerMappedCoordinate(y8, height8, nonNullSource.height8());
            for (int x8 = 0; x8 < width8; x8++) {
                int sourceX8 = centerMappedCoordinate(x8, width8, nonNullSource.width8());
                @Nullable TileDecodeContext.TemporalMotionBlock block = nonNullSource.block(sourceX8, sourceY8);
                if (block != null) {
                    int sourceIndex = sourceY8 * nonNullSource.width8() + sourceX8;
                    TileDecodeContext.TemporalMotionBlock scaledBlock = scaledBlocks[sourceIndex];
                    if (scaledBlock == null) {
                        scaledBlock = scaleTemporalMotionBlock(
                                block,
                                width8,
                                nonNullSource.width8(),
                                height8,
                                nonNullSource.height8()
                        );
                        scaledBlocks[sourceIndex] = scaledBlock;
                    }
                    projected.setBlock(x8, y8, scaledBlock);
                }
            }
        }
        return projected;
    }

    /// Returns the tile-local temporal motion field dimensions for the supplied assembly tile.
    ///
    /// @param assembly the completed frame assembly
    /// @param tileIndex the zero-based tile index in frame order
    /// @return the width and height in 8x8 units
    private static int[] temporalMotionFieldDimensions(FrameAssembly assembly, int tileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        SequenceHeader sequenceHeader = nonNullAssembly.sequenceHeader();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int columns = tiling.columns();
        int tileRow = tileIndex / columns;
        int tileColumn = tileIndex % columns;
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int startX = tiling.columnStartSuperblocks()[tileColumn] * superblockSize;
        int endX = Math.min(frameHeader.frameSize().codedWidth(), tiling.columnStartSuperblocks()[tileColumn + 1] * superblockSize);
        int startY = tiling.rowStartSuperblocks()[tileRow] * superblockSize;
        int endY = Math.min(frameHeader.frameSize().height(), tiling.rowStartSuperblocks()[tileRow + 1] * superblockSize);
        return new int[]{(endX - startX + 7) >> 3, (endY - startY + 7) >> 3};
    }

    /// Returns the source coordinate whose cell center is closest to one destination cell center.
    ///
    /// @param destinationCoordinate the destination coordinate
    /// @param destinationLength the destination axis length
    /// @param sourceLength the source axis length
    /// @return the source coordinate selected for the destination cell
    private static int centerMappedCoordinate(int destinationCoordinate, int destinationLength, int sourceLength) {
        return Math.min(sourceLength - 1, ((destinationCoordinate << 1) + 1) * sourceLength / (destinationLength << 1));
    }

    /// Scales one temporal motion block to a new tile-grid ratio.
    ///
    /// @param block the temporal motion block to scale
    /// @param widthNumerator the destination tile width in 8x8 units
    /// @param widthDenominator the source tile width in 8x8 units
    /// @param heightNumerator the destination tile height in 8x8 units
    /// @param heightDenominator the source tile height in 8x8 units
    /// @return the scaled temporal motion block
    private static TileDecodeContext.TemporalMotionBlock scaleTemporalMotionBlock(
            TileDecodeContext.TemporalMotionBlock block,
            int widthNumerator,
            int widthDenominator,
            int heightNumerator,
            int heightDenominator
    ) {
        TileDecodeContext.TemporalMotionBlock nonNullBlock = Objects.requireNonNull(block, "block");
        InterMotionVector motionVector0 = scaleInterMotionVector(
                nonNullBlock.motionVector0(),
                widthNumerator,
                widthDenominator,
                heightNumerator,
                heightDenominator
        );
        if (!nonNullBlock.compoundReference()) {
            return TileDecodeContext.TemporalMotionBlock.singleReference(nonNullBlock.referenceFrame0(), motionVector0);
        }
        InterMotionVector motionVector1 = scaleInterMotionVector(
                Objects.requireNonNull(nonNullBlock.motionVector1(), "block.motionVector1()"),
                widthNumerator,
                widthDenominator,
                heightNumerator,
                heightDenominator
        );
        return TileDecodeContext.TemporalMotionBlock.compoundReference(
                nonNullBlock.referenceFrame0(),
                nonNullBlock.referenceFrame1(),
                motionVector0,
                motionVector1
        );
    }

    /// Scales one inter motion vector while preserving its resolved/predicted state.
    ///
    /// @param motionVector the inter motion vector to scale
    /// @param widthNumerator the horizontal scale numerator
    /// @param widthDenominator the horizontal scale denominator
    /// @param heightNumerator the vertical scale numerator
    /// @param heightDenominator the vertical scale denominator
    /// @return the scaled inter motion vector
    private static InterMotionVector scaleInterMotionVector(
            InterMotionVector motionVector,
            int widthNumerator,
            int widthDenominator,
            int heightNumerator,
            int heightDenominator
    ) {
        InterMotionVector nonNullMotionVector = Objects.requireNonNull(motionVector, "motionVector");
        MotionVector vector = nonNullMotionVector.vector();
        MotionVector scaledVector = new MotionVector(
                scaledMotionComponent(vector.rowQuarterPel(), heightNumerator, heightDenominator),
                scaledMotionComponent(vector.columnQuarterPel(), widthNumerator, widthDenominator)
        );
        return new InterMotionVector(scaledVector, nonNullMotionVector.resolved());
    }

    /// Scales one signed motion-vector component with symmetric nearest-integer rounding.
    ///
    /// @param value the signed component to scale
    /// @param numerator the scale numerator
    /// @param denominator the scale denominator
    /// @return the scaled signed component
    private static int scaledMotionComponent(int value, int numerator, int denominator) {
        long scaled = (long) value * numerator;
        long half = denominator >> 1;
        if (scaled >= 0) {
            return (int) ((scaled + half) / denominator);
        }
        return (int) -(((-scaled) + half) / denominator);
    }
}
