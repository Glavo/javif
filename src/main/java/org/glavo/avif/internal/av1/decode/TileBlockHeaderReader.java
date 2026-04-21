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

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.BlockSize;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Reader for one leaf block header inside a tile partition tree.
///
/// This implementation intentionally covers only the syntax elements already backed by
/// `TileSyntaxReader` and uses a provisional CFL-availability rule of `hasChroma`.
@NotNullByDefault
public final class TileBlockHeaderReader {
    /// The tile-local decode state that owns the active frame and sequence headers.
    private final TileDecodeContext tileContext;

    /// The typed syntax reader used to consume entropy-coded syntax elements.
    private final TileSyntaxReader syntaxReader;

    /// Creates one leaf block header reader.
    ///
    /// @param tileContext the tile-local decode state that owns the active frame and sequence headers
    public TileBlockHeaderReader(TileDecodeContext tileContext) {
        TileDecodeContext nonNullTileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.tileContext = nonNullTileContext;
        this.syntaxReader = new TileSyntaxReader(nonNullTileContext);
    }

    /// Returns the tile-local decode state that owns this block header reader.
    ///
    /// @return the tile-local decode state that owns this block header reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes one leaf block header and updates the supplied neighbor context.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to decode
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @return the decoded leaf block header
    public BlockHeader read(BlockPosition position, BlockSize size, BlockNeighborContext neighborContext) {
        BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        BlockNeighborContext nonNullNeighborContext = Objects.requireNonNull(neighborContext, "neighborContext");
        boolean hasChroma = hasChroma(nonNullPosition, nonNullSize);
        boolean skip = syntaxReader.readSkipFlag(nonNullNeighborContext.skipContext(nonNullPosition));
        boolean useIntrabc = false;
        boolean intra;
        FrameType frameType = tileContext.frameHeader().frameType();
        if (frameType == FrameType.INTER || frameType == FrameType.SWITCH) {
            intra = syntaxReader.readIntraBlockFlag(nonNullNeighborContext.intraContext(nonNullPosition));
        } else if (tileContext.frameHeader().allowIntrabc()) {
            useIntrabc = syntaxReader.readUseIntrabcFlag();
            intra = !useIntrabc;
        } else {
            intra = true;
        }

        @Nullable LumaIntraPredictionMode yMode = null;
        @Nullable UvIntraPredictionMode uvMode = null;
        if (intra) {
            if (frameType == FrameType.INTER || frameType == FrameType.SWITCH) {
                yMode = syntaxReader.readYMode(nonNullSize.yModeSizeContext());
            } else {
                yMode = syntaxReader.readKeyFrameYMode(
                        nonNullNeighborContext.aboveMode(nonNullPosition.x4()),
                        nonNullNeighborContext.leftMode(nonNullPosition.y4())
                );
            }
            if (hasChroma) {
                uvMode = syntaxReader.readUvMode(yMode, true);
            }
        }

        BlockHeader header = new BlockHeader(nonNullPosition, nonNullSize, hasChroma, skip, intra, useIntrabc, yMode, uvMode);
        nonNullNeighborContext.updateFromBlockHeader(header);
        return header;
    }

    /// Returns whether the supplied block has chroma samples in the active frame layout.
    ///
    /// @param position the local tile-relative block origin
    /// @param size the block size to test
    /// @return whether the supplied block has chroma samples in the active frame layout
    private boolean hasChroma(BlockPosition position, BlockSize size) {
        PixelFormat pixelFormat = tileContext.sequenceHeader().colorConfig().pixelFormat();
        if (pixelFormat == PixelFormat.I400) {
            return false;
        }
        int subsamplingX = tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
        int subsamplingY = tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
        return (size.width4() > subsamplingX || (position.x4() & 1) != 0)
                && (size.height4() > subsamplingY || (position.y4() & 1) != 0);
    }

    /// One decoded leaf block header.
    @NotNullByDefault
    public static final class BlockHeader {
        /// The local tile-relative block origin.
        private final BlockPosition position;

        /// The decoded block size.
        private final BlockSize size;

        /// Whether the block has chroma samples in the active frame layout.
        private final boolean hasChroma;

        /// The decoded skip flag.
        private final boolean skip;

        /// Whether the block is intra-coded.
        private final boolean intra;

        /// Whether the block uses `intrabc`.
        private final boolean useIntrabc;

        /// The decoded luma intra prediction mode, or `null` for non-intra blocks.
        private final @Nullable LumaIntraPredictionMode yMode;

        /// The decoded chroma intra prediction mode, or `null` when not present.
        private final @Nullable UvIntraPredictionMode uvMode;

        /// Creates one decoded leaf block header.
        ///
        /// @param position the local tile-relative block origin
        /// @param size the decoded block size
        /// @param hasChroma whether the block has chroma samples in the active frame layout
        /// @param skip the decoded skip flag
        /// @param intra whether the block is intra-coded
        /// @param useIntrabc whether the block uses `intrabc`
        /// @param yMode the decoded luma intra prediction mode, or `null`
        /// @param uvMode the decoded chroma intra prediction mode, or `null`
        public BlockHeader(
                BlockPosition position,
                BlockSize size,
                boolean hasChroma,
                boolean skip,
                boolean intra,
                boolean useIntrabc,
                @Nullable LumaIntraPredictionMode yMode,
                @Nullable UvIntraPredictionMode uvMode
        ) {
            this.position = Objects.requireNonNull(position, "position");
            this.size = Objects.requireNonNull(size, "size");
            this.hasChroma = hasChroma;
            this.skip = skip;
            this.intra = intra;
            this.useIntrabc = useIntrabc;
            this.yMode = yMode;
            this.uvMode = uvMode;
        }

        /// Returns the local tile-relative block origin.
        ///
        /// @return the local tile-relative block origin
        public BlockPosition position() {
            return position;
        }

        /// Returns the decoded block size.
        ///
        /// @return the decoded block size
        public BlockSize size() {
            return size;
        }

        /// Returns whether the block has chroma samples in the active frame layout.
        ///
        /// @return whether the block has chroma samples in the active frame layout
        public boolean hasChroma() {
            return hasChroma;
        }

        /// Returns the decoded skip flag.
        ///
        /// @return the decoded skip flag
        public boolean skip() {
            return skip;
        }

        /// Returns whether the block is intra-coded.
        ///
        /// @return whether the block is intra-coded
        public boolean intra() {
            return intra;
        }

        /// Returns whether the block uses `intrabc`.
        ///
        /// @return whether the block uses `intrabc`
        public boolean useIntrabc() {
            return useIntrabc;
        }

        /// Returns the decoded luma intra prediction mode, or `null` for non-intra blocks.
        ///
        /// @return the decoded luma intra prediction mode, or `null`
        public @Nullable LumaIntraPredictionMode yMode() {
            return yMode;
        }

        /// Returns the decoded chroma intra prediction mode, or `null` when not present.
        ///
        /// @return the decoded chroma intra prediction mode, or `null`
        public @Nullable UvIntraPredictionMode uvMode() {
            return uvMode;
        }
    }
}
