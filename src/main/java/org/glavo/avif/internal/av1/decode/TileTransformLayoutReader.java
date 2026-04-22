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
/// The current implementation fully supports the uniform intra-like transform-size path, including
/// switchable transform-depth symbols and lossless 4x4 tiling. Inter blocks currently expose the
/// largest allowed luma transform layout until var-tx tree syntax is migrated.
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

        boolean lossless = tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId());
        TransformSize maxLumaTransformSize = size.maxLumaTransformSize();
        @Nullable TransformSize chromaTransformSize = nonNullHeader.hasChroma()
                ? size.maxChromaTransformSize(tileContext.sequenceHeader().colorConfig().pixelFormat())
                : null;
        TransformSize uniformLumaTransformSize = selectUniformLumaTransformSize(
                nonNullHeader,
                nonNullNeighborContext,
                lossless,
                maxLumaTransformSize
        );
        if (lossless) {
            chromaTransformSize = TransformSize.TX_4X4;
        }

        return new TransformLayout(
                position,
                size,
                visibleWidth4,
                visibleHeight4,
                maxLumaTransformSize,
                chromaTransformSize,
                false,
                tileUniformUnits(position, visibleWidth4, visibleHeight4, uniformLumaTransformSize)
        );
    }

    /// Selects the uniform luma transform size used by the current block.
    ///
    /// @param header the decoded leaf block header
    /// @param neighborContext the mutable neighbor context that supplies transform-size contexts
    /// @param lossless whether the current block is lossless through segmentation
    /// @param maxLumaTransformSize the largest luma transform size allowed for this block
    /// @return the uniform luma transform size used by the current block
    private TransformSize selectUniformLumaTransformSize(
            TileBlockHeaderReader.BlockHeader header,
            BlockNeighborContext neighborContext,
            boolean lossless,
            TransformSize maxLumaTransformSize
    ) {
        if (lossless || tileContext.frameHeader().transformMode() == FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY) {
            return TransformSize.TX_4X4;
        }
        if (!header.intra() && !header.useIntrabc()) {
            return maxLumaTransformSize;
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
}
