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

import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Reader for block-level transform layouts that sit between block headers and coefficient syntax.
///
/// The current implementation supports the uniform intra-like transform-size path, including
/// switchable transform-depth symbols, explicit chroma transform-unit tiling, and lossless 4x4
/// tiling, and it also covers switchable inter var-tx trees. Coefficient syntax is still out of
/// scope.
@NotNullByDefault
public final class TileTransformLayoutReader {
    /// The tile-local decode state that owns the active frame and sequence headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used for transform-size symbols.
    private final TileSyntaxReader syntaxReader;

    /// Creates one block-level transform-layout reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active frame and sequence headers
    public TileTransformLayoutReader(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.tileContext = nonNullTileContext;
        this.syntaxReader = new TileSyntaxReader(nonNullTileContext);
    }

    /// Returns the tile-local decode state that owns this transform-layout reader.
    ///
    /// @return the tile-local decode state that owns this transform-layout reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes the transform layout for one already-decoded leaf block header.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies transform-size contexts
    /// @return the decoded transform layout for the supplied leaf block
    public TransformLayout read(TileBlockHeaderReader.BlockHeader header, BlockNeighborContext neighborContext) {
        TileBlockHeaderReader.BlockHeader nonNullHeader = Objects.requireNonNull(header, "header");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        BlockPosition position = nonNullHeader.position();
        BlockSize size = nonNullHeader.size();
        int visibleWidth4 = Math.min(size.width4(), ((tileContext.width() + 3) >> 2) - position.x4());
        int visibleHeight4 = Math.min(size.height4(), ((tileContext.height() + 3) >> 2) - position.y4());
        int visibleWidthPixels = Math.min(size.widthPixels(), tileContext.width() - (position.x4() << 2));
        int visibleHeightPixels = Math.min(size.heightPixels(), tileContext.height() - (position.y4() << 2));

        boolean lossless = tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId());
        TransformSize maxLumaTransformSize = size.maxLumaTransformSize();
        @Nullable TransformSize chromaTransformSize = nonNullHeader.hasChroma()
                ? size.maxChromaTransformSize(tileContext.sequenceHeader().colorConfig().pixelFormat())
                : null;
        TransformSize effectiveMaxLumaTransformSize = lossless ? TransformSize.TX_4X4 : maxLumaTransformSize;
        if (lossless) {
            chromaTransformSize = TransformSize.TX_4X4;
        }
        TransformUnit[] chromaUnits = chromaTransformSize != null
                ? tileChromaUnits(
                        position,
                        visibleWidthPixels,
                        visibleHeightPixels,
                        chromaTransformSize,
                        tileContext.sequenceHeader().colorConfig().pixelFormat()
                )
                : new TransformUnit[0];

        TransformUnit[] lumaUnits;
        boolean variableLumaTransformTree;
        if (nonNullHeader.intra() && !nonNullHeader.useIntrabc()) {
            TransformSize uniformLumaTransformSize = selectUniformIntraTransformSize(
                    nonNullHeader,
                    nonNullNeighborContext,
                    lossless,
                    effectiveMaxLumaTransformSize
            );
            lumaUnits = tileUniformUnits(position, visibleWidth4, visibleHeight4, uniformLumaTransformSize);
            variableLumaTransformTree = false;
        } else {
            InterTransformResult interTransformResult = readInterTransformLayout(
                    nonNullHeader,
                    nonNullNeighborContext,
                    lossless,
                    effectiveMaxLumaTransformSize,
                    visibleWidth4,
                    visibleHeight4
            );
            lumaUnits = interTransformResult.lumaUnits();
            variableLumaTransformTree = interTransformResult.variableTransformTree();
        }

        return new TransformLayout(
                position,
                size,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                effectiveMaxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                chromaUnits
        );
    }

    /// Selects the uniform luma transform size used by one intra-like block.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies transform-size contexts
    /// @param lossless whether the current block is lossless through segmentation
    /// @param maxLumaTransformSize the largest luma transform size allowed for this block
    /// @return the uniform luma transform size used by the current block
    private TransformSize selectUniformIntraTransformSize(
            TileBlockHeaderReader.BlockHeader header,
            BlockNeighborContext neighborContext,
            boolean lossless,
            TransformSize maxLumaTransformSize
    ) {
        if (lossless || tileContext.frameHeader().transformMode() == FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY) {
            return TransformSize.TX_4X4;
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE
                || maxLumaTransformSize.maxSquareLevel() <= 0) {
            return maxLumaTransformSize;
        }

        int depth = syntaxReader.readTransformDepth(
                maxLumaTransformSize,
                neighborContext.transformSizeContext(header.position(), maxLumaTransformSize)
        );
        TransformSize selectedSize = maxLumaTransformSize;
        while (depth-- > 0 && selectedSize.hasSubSize()) {
            selectedSize = selectedSize.subSize();
        }
        return selectedSize;
    }

    /// Reads the luma transform layout for one inter-like block.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies inter var-tx contexts
    /// @param lossless whether the current block is lossless through segmentation
    /// @param maxLumaTransformSize the largest luma transform size allowed for this block
    /// @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
    /// @return the decoded inter-like luma transform layout
    private InterTransformResult readInterTransformLayout(
            TileBlockHeaderReader.BlockHeader header,
            BlockNeighborContext neighborContext,
            boolean lossless,
            TransformSize maxLumaTransformSize,
            int visibleWidth4,
            int visibleHeight4
    ) {
        BlockPosition position = header.position();
        BlockSize size = header.size();
        if (!header.skip() && maxLumaTransformSize == TransformSize.TX_4X4) {
            neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4);
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4), false);
        }
        if (lossless) {
            neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4);
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, TransformSize.TX_4X4), false);
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE || header.skip()) {
            if (tileContext.frameHeader().transformMode() == FrameHeader.TransformMode.SWITCHABLE) {
                neighborContext.updateInterTransformContext(
                        position,
                        visibleWidth4,
                        visibleHeight4,
                        size.log2Width4(),
                        size.log2Height4()
                );
            }
            return new InterTransformResult(tileUniformUnits(position, visibleWidth4, visibleHeight4, maxLumaTransformSize), false);
        }

        List<TransformUnit> units = new ArrayList<>();
        for (int offsetY4 = 0; offsetY4 < visibleHeight4; offsetY4 += maxLumaTransformSize.height4()) {
            int regionVisibleHeight4 = Math.min(maxLumaTransformSize.height4(), visibleHeight4 - offsetY4);
            for (int offsetX4 = 0; offsetX4 < visibleWidth4; offsetX4 += maxLumaTransformSize.width4()) {
                int regionVisibleWidth4 = Math.min(maxLumaTransformSize.width4(), visibleWidth4 - offsetX4);
                readInterTransformTree(
                        position.offset(offsetX4, offsetY4),
                        maxLumaTransformSize,
                        regionVisibleWidth4,
                        regionVisibleHeight4,
                        0,
                        neighborContext,
                        units
                );
            }
        }
        return new InterTransformResult(units.toArray(new TransformUnit[0]), true);
    }

    /// Recursively reads one inter var-tx region and appends its visible luma transform units.
    ///
    /// @param position the local tile-relative origin of the current transform region
    /// @param transformSize the transform size currently being considered for splitting
    /// @param visibleWidth4 the visible region width in 4x4 units after clipping against tile bounds
    /// @param visibleHeight4 the visible region height in 4x4 units after clipping against tile bounds
    /// @param depth the recursive var-tx depth in `[0, 2]`
    /// @param neighborContext the mutable neighbor context that supplies and stores inter var-tx state
    /// @param destination the destination list that receives visible luma transform units in bitstream order
    private void readInterTransformTree(
            BlockPosition position,
            TransformSize transformSize,
            int visibleWidth4,
            int visibleHeight4,
            int depth,
            BlockNeighborContext neighborContext,
            List<TransformUnit> destination
    ) {
        if (visibleWidth4 <= 0 || visibleHeight4 <= 0) {
            return;
        }

        boolean split = false;
        if (depth < 2 && transformSize != TransformSize.TX_4X4) {
            int tableIndex = 2 * (TransformSize.TX_64X64.maxSquareLevel() - transformSize.maxSquareLevel()) - depth;
            split = syntaxReader.readTransformSplitFlag(
                    tableIndex,
                    neighborContext.interTransformSplitContext(position, transformSize)
            );
        }

        if (split && transformSize.maxSquareLevel() > TransformSize.TX_8X8.maxSquareLevel()) {
            TransformSize subSize = transformSize.subSize();
            int childWidth4 = subSize.width4();
            int childHeight4 = subSize.height4();
            readInterTransformTree(
                    position,
                    subSize,
                    Math.min(childWidth4, visibleWidth4),
                    Math.min(childHeight4, visibleHeight4),
                    depth + 1,
                    neighborContext,
                    destination
            );
            if (transformSize.width4() >= transformSize.height4()) {
                readInterTransformTree(
                        position.offset(childWidth4, 0),
                        subSize,
                        Math.min(childWidth4, Math.max(0, visibleWidth4 - childWidth4)),
                        Math.min(childHeight4, visibleHeight4),
                        depth + 1,
                        neighborContext,
                        destination
                );
            }
            if (transformSize.height4() >= transformSize.width4()) {
                readInterTransformTree(
                        position.offset(0, childHeight4),
                        subSize,
                        Math.min(childWidth4, visibleWidth4),
                        Math.min(childHeight4, Math.max(0, visibleHeight4 - childHeight4)),
                        depth + 1,
                        neighborContext,
                        destination
                );
                if (transformSize.width4() >= transformSize.height4()) {
                    readInterTransformTree(
                            position.offset(childWidth4, childHeight4),
                            subSize,
                            Math.min(childWidth4, Math.max(0, visibleWidth4 - childWidth4)),
                            Math.min(childHeight4, Math.max(0, visibleHeight4 - childHeight4)),
                            depth + 1,
                            neighborContext,
                            destination
                    );
                }
            }
            return;
        }

        TransformSize finalTransformSize = split ? TransformSize.TX_4X4 : transformSize;
        neighborContext.updateInterTransformContext(position, visibleWidth4, visibleHeight4, finalTransformSize);
        for (TransformUnit unit : tileUniformUnits(position, visibleWidth4, visibleHeight4, finalTransformSize)) {
            destination.add(unit);
        }
    }

    /// Tiles one visible block span with repeated luma transform units of a single transform size.
    ///
    /// @param position the local tile-relative origin of the owning block
    /// @param visibleWidth4 the visible block width in 4x4 units
    /// @param visibleHeight4 the visible block height in 4x4 units
    /// @param transformSize the repeated luma transform size
    /// @return the repeated luma transform units that cover the visible block span
    private static TransformUnit[] tileUniformUnits(
            BlockPosition position,
            int visibleWidth4,
            int visibleHeight4,
            TransformSize transformSize
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        List<TransformUnit> units = new ArrayList<>();
        for (int y4 = 0; y4 < visibleHeight4; y4 += nonNullTransformSize.height4()) {
            for (int x4 = 0; x4 < visibleWidth4; x4 += nonNullTransformSize.width4()) {
                units.add(new TransformUnit(nonNullPosition.offset(x4, y4), nonNullTransformSize));
            }
        }
        return units.toArray(new TransformUnit[0]);
    }

    /// Tiles one visible block span with chroma transform units in chroma bitstream order.
    ///
    /// The returned unit positions are widened back into the shared luma-grid `BlockPosition`
    /// coordinate space so downstream contexts and reconstruction can keep using one position type.
    ///
    /// @param position the local tile-relative luma-grid origin of the owning block
    /// @param visibleWidthPixels the exact visible luma width in pixels
    /// @param visibleHeightPixels the exact visible luma height in pixels
    /// @param transformSize the repeated chroma transform size
    /// @param pixelFormat the active decoded chroma layout
    /// @return the repeated chroma transform units covering the visible chroma span
    private static TransformUnit[] tileChromaUnits(
            BlockPosition position,
            int visibleWidthPixels,
            int visibleHeightPixels,
            TransformSize transformSize,
            PixelFormat pixelFormat
    ) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int subsamplingX = chromaSubsamplingX(pixelFormat);
        int subsamplingY = chromaSubsamplingY(pixelFormat);
        int visibleChromaWidthPixels = chromaDimension(visibleWidthPixels, subsamplingX);
        int visibleChromaHeightPixels = chromaDimension(visibleHeightPixels, subsamplingY);
        List<TransformUnit> units = new ArrayList<>();
        for (int y = 0; y < visibleChromaHeightPixels; y += nonNullTransformSize.heightPixels()) {
            for (int x = 0; x < visibleChromaWidthPixels; x += nonNullTransformSize.widthPixels()) {
                units.add(new TransformUnit(
                        nonNullPosition.offset((x >> 2) << subsamplingX, (y >> 2) << subsamplingY),
                        nonNullTransformSize
                ));
            }
        }
        return units.toArray(new TransformUnit[0]);
    }

    /// Returns the chroma-plane dimension corresponding to one visible luma span.
    ///
    /// @param lumaDimension the visible luma span in pixels
    /// @param subsamplingShift the chroma subsampling shift for the axis
    /// @return the corresponding chroma-plane dimension
    private static int chromaDimension(int lumaDimension, int subsamplingShift) {
        return (lumaDimension + (1 << subsamplingShift) - 1) >> subsamplingShift;
    }

    /// Returns the horizontal chroma subsampling shift for one decoded pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(PixelFormat pixelFormat) {
        return switch (Objects.requireNonNull(pixelFormat, "pixelFormat")) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one decoded pixel format.
    ///
    /// @param pixelFormat the active decoded chroma layout
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(PixelFormat pixelFormat) {
        return switch (Objects.requireNonNull(pixelFormat, "pixelFormat")) {
            case I400, I422, I444 -> 0;
            case I420 -> 1;
        };
    }

    /// One decoded inter-like transform result.
    @NotNullByDefault
    private static final class InterTransformResult {
        /// The decoded luma transform units in bitstream order.
        private final TransformUnit[] lumaUnits;

        /// Whether the layout came from a variable luma transform tree.
        private final boolean variableTransformTree;

        /// Creates one decoded inter-like transform result.
        ///
        /// @param lumaUnits the decoded luma transform units in bitstream order
        /// @param variableTransformTree whether the layout came from a variable luma transform tree
        private InterTransformResult(TransformUnit[] lumaUnits, boolean variableTransformTree) {
            this.lumaUnits = Objects.requireNonNull(lumaUnits, "lumaUnits");
            this.variableTransformTree = variableTransformTree;
        }

        /// Returns the decoded luma transform units in bitstream order.
        ///
        /// @return the decoded luma transform units in bitstream order
        public TransformUnit[] lumaUnits() {
            return lumaUnits;
        }

        /// Returns whether the layout came from a variable luma transform tree.
        ///
        /// @return whether the layout came from a variable luma transform tree
        public boolean variableTransformTree() {
            return variableTransformTree;
        }
    }
}
