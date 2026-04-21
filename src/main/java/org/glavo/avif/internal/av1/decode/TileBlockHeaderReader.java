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
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Reader for one leaf block header inside a tile partition tree.
///
/// This implementation intentionally covers only the syntax elements already backed by
/// `TileSyntaxReader`, including palette size signaling, `intrabc`, directional angle deltas,
/// and block-size-aware CFL gating.
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
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        boolean hasChroma = hasChroma(nonNullPosition, nonNullSize);
        int segmentId = 0;
        boolean segmentPredicted = false;
        if (segmentation.enabled() && segmentation.updateMap() && segmentation.preskip()) {
            SegmentReadResult segmentReadResult = readSegmentIdBeforeSkip(nonNullPosition, nonNullNeighborContext);
            segmentId = segmentReadResult.segmentId();
            segmentPredicted = segmentReadResult.segmentPredicted();
        }

        FrameHeader.SegmentData segmentData = segmentation.segment(segmentId);
        boolean skip = segmentData.skip() || syntaxReader.readSkipFlag(nonNullNeighborContext.skipContext(nonNullPosition));
        if (segmentData.skip()) {
            skip = true;
        }
        if (segmentation.enabled() && segmentation.updateMap() && !segmentation.preskip()) {
            SegmentReadResult segmentReadResult = readSegmentIdAfterSkip(nonNullPosition, nonNullNeighborContext, skip);
            segmentId = segmentReadResult.segmentId();
            segmentPredicted = segmentReadResult.segmentPredicted();
            segmentData = segmentation.segment(segmentId);
        }

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
        int yPaletteSize = 0;
        int uvPaletteSize = 0;
        @Nullable FilterIntraMode filterIntraMode = null;
        int yAngle = 0;
        int uvAngle = 0;
        int cflAlphaU = 0;
        int cflAlphaV = 0;
        if (useIntrabc) {
            yMode = LumaIntraPredictionMode.DC;
            if (hasChroma) {
                uvMode = UvIntraPredictionMode.DC;
            }
        } else if (intra) {
            if (frameType == FrameType.INTER || frameType == FrameType.SWITCH) {
                yMode = syntaxReader.readYMode(nonNullSize.yModeSizeContext());
            } else {
                yMode = syntaxReader.readKeyFrameYMode(
                        nonNullNeighborContext.aboveMode(nonNullPosition.x4()),
                        nonNullNeighborContext.leftMode(nonNullPosition.y4())
                );
            }
            if (supportsAngleDelta(nonNullSize) && yMode.isDirectional()) {
                yAngle = syntaxReader.readYAngleDelta(yMode);
            }
            if (hasChroma) {
                boolean cflAllowed = isCflAllowed(nonNullSize, segmentId);
                uvMode = syntaxReader.readUvMode(yMode, cflAllowed);
                if (uvMode == UvIntraPredictionMode.CFL) {
                    TileSyntaxReader.CflAlpha cflAlpha = syntaxReader.readCflAlpha();
                    cflAlphaU = cflAlpha.alphaU();
                    cflAlphaV = cflAlpha.alphaV();
                } else if (supportsAngleDelta(nonNullSize) && uvMode.isDirectional()) {
                    uvAngle = syntaxReader.readUvAngleDelta(uvMode);
                }
            }
            if (allowsPalette(nonNullSize)) {
                int paletteSizeContext = nonNullSize.paletteSizeContext();
                if (yMode == LumaIntraPredictionMode.DC) {
                    int paletteContext = (nonNullNeighborContext.abovePaletteSize(nonNullPosition.x4()) > 0 ? 1 : 0)
                            + (nonNullNeighborContext.leftPaletteSize(nonNullPosition.y4()) > 0 ? 1 : 0);
                    if (syntaxReader.readUseLumaPalette(paletteSizeContext, paletteContext)) {
                        yPaletteSize = syntaxReader.readPaletteSize(0, paletteSizeContext);
                    }
                }
                if (hasChroma && uvMode == UvIntraPredictionMode.DC
                        && syntaxReader.readUseChromaPalette(yPaletteSize > 0 ? 1 : 0)) {
                    uvPaletteSize = syntaxReader.readPaletteSize(1, paletteSizeContext);
                }
            }
            if (yPaletteSize == 0 && allowsFilterIntra(nonNullSize, yMode) && syntaxReader.readUseFilterIntra(nonNullSize)) {
                filterIntraMode = syntaxReader.readFilterIntraMode();
            }
        }

        BlockHeader header = new BlockHeader(
                nonNullPosition,
                nonNullSize,
                hasChroma,
                skip,
                intra,
                useIntrabc,
                segmentPredicted,
                segmentId,
                yMode,
                uvMode,
                yPaletteSize,
                uvPaletteSize,
                filterIntraMode,
                yAngle,
                uvAngle,
                cflAlphaU,
                cflAlphaV
        );
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

    /// Returns whether CFL syntax is available for the supplied block size in the active frame.
    ///
    /// In lossless mode AV1 only allows CFL when the corresponding chroma block is 4x4. For
    /// non-lossless blocks CFL is limited to luma partitions up to 32x32.
    ///
    /// @param size the block size to test
    /// @param segmentId the decoded segment identifier for the current block
    /// @return whether CFL syntax is available for the supplied block size in the active frame
    private boolean isCflAllowed(BlockSize size, int segmentId) {
        if (!tileContext.frameHeader().segmentation().lossless(segmentId)) {
            return size.widthPixels() <= 32 && size.heightPixels() <= 32;
        }

        int subsamplingX = tileContext.sequenceHeader().colorConfig().chromaSubsamplingX() ? 1 : 0;
        int subsamplingY = tileContext.sequenceHeader().colorConfig().chromaSubsamplingY() ? 1 : 0;
        int chromaWidth4 = Math.max(1, size.width4() >> subsamplingX);
        int chromaHeight4 = Math.max(1, size.height4() >> subsamplingY);
        return chromaWidth4 == 1 && chromaHeight4 == 1;
    }

    /// Returns whether palette syntax is available for the supplied block size.
    ///
    /// AV1 palette mode is limited to screen-content frames and blocks up to 64x64, excluding
    /// the smallest 4x4 and 4x8 style blocks whose 4x4 dimensions sum to less than four.
    ///
    /// @param size the block size to test
    /// @return whether palette syntax is available for the supplied block size
    private boolean allowsPalette(BlockSize size) {
        return tileContext.frameHeader().allowScreenContentTools()
                && Math.max(size.width4(), size.height4()) <= 16
                && size.width4() + size.height4() >= 4;
    }

    /// Returns whether filter-intra syntax is available for the supplied block.
    ///
    /// Filter intra is only signaled for DC-predicted luma blocks whose longest side does not
    /// exceed 32 pixels when the active sequence enables the tool.
    ///
    /// @param size the block size to test
    /// @param yMode the decoded luma intra prediction mode
    /// @return whether filter-intra syntax is available for the supplied block
    private boolean allowsFilterIntra(BlockSize size, LumaIntraPredictionMode yMode) {
        return tileContext.sequenceHeader().features().filterIntra()
                && yMode == LumaIntraPredictionMode.DC
                && Math.max(size.widthPixels(), size.heightPixels()) <= 32;
    }

    /// Reads a segment identifier from the preskip portion of the block header.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @return the decoded preskip segment identifier
    private SegmentReadResult readSegmentIdBeforeSkip(BlockPosition position, BlockNeighborContext neighborContext) {
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        if (!segmentation.enabled() || !segmentation.updateMap()) {
            return new SegmentReadResult(false, 0);
        }
        if (segmentation.temporalUpdate()) {
            throw new IllegalStateException("Temporal segmentation prediction is not implemented yet");
        }

        BlockNeighborContext.SegmentPrediction prediction = neighborContext.currentSegmentPrediction(position);
        int segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId());
        return new SegmentReadResult(false, segmentId);
    }

    /// Reads a segment identifier from the postskip portion of the block header.
    ///
    /// @param position the local tile-relative block origin
    /// @param neighborContext the mutable neighbor context that supplies syntax contexts
    /// @param skip whether the current block already decoded as skipped
    /// @return the decoded postskip segment identifier
    private SegmentReadResult readSegmentIdAfterSkip(
            BlockPosition position,
            BlockNeighborContext neighborContext,
            boolean skip
    ) {
        FrameHeader.SegmentationInfo segmentation = tileContext.frameHeader().segmentation();
        if (!segmentation.enabled() || !segmentation.updateMap()) {
            return new SegmentReadResult(false, 0);
        }
        if (segmentation.temporalUpdate()) {
            throw new IllegalStateException("Temporal segmentation prediction is not implemented yet");
        }

        BlockNeighborContext.SegmentPrediction prediction = neighborContext.currentSegmentPrediction(position);
        if (skip) {
            return new SegmentReadResult(false, prediction.predictedSegmentId());
        }
        int segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId());
        return new SegmentReadResult(false, segmentId);
    }

    /// Decodes one segment identifier from a predicted segment and segment-id context.
    ///
    /// @param prediction the current-frame segment prediction derived from already-decoded neighbors
    /// @param lastActiveSegmentId the highest active segment identifier, or `-1`
    /// @return the decoded segment identifier
    private int decodeSegmentId(BlockNeighborContext.SegmentPrediction prediction, int lastActiveSegmentId) {
        int diff = syntaxReader.readSegmentId(prediction.context());
        int segmentId = negDeinterleave(diff, prediction.predictedSegmentId(), lastActiveSegmentId + 1);
        if (segmentId > lastActiveSegmentId || segmentId >= 8) {
            return 0;
        }
        return segmentId;
    }

    /// Applies the AV1 `neg_deinterleave` mapping used by segment-id decoding.
    ///
    /// @param diff the decoded diff symbol
    /// @param reference the predicted segment identifier
    /// @param max the exclusive upper bound passed by the bitstream syntax
    /// @return the deinterleaved segment identifier candidate
    private static int negDeinterleave(int diff, int reference, int max) {
        if (reference == 0) {
            return diff;
        }
        if (reference >= max - 1) {
            return max - diff - 1;
        }
        if (reference * 2 < max) {
            if (diff <= reference * 2) {
                return (diff & 1) != 0
                        ? reference + ((diff + 1) >> 1)
                        : reference - (diff >> 1);
            }
            return diff;
        }
        if (diff <= (max - reference - 1) * 2) {
            return (diff & 1) != 0
                    ? reference + ((diff + 1) >> 1)
                    : reference - (diff >> 1);
        }
        return max - diff - 1;
    }

    /// Returns whether the supplied block size supports directional angle-delta syntax.
    ///
    /// @param size the block size to test
    /// @return whether the supplied block size supports directional angle-delta syntax
    private static boolean supportsAngleDelta(BlockSize size) {
        return size.width4() * size.height4() >= 4;
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

        /// Whether the block used temporal segmentation prediction.
        private final boolean segmentPredicted;

        /// The decoded segment identifier for the block.
        private final int segmentId;

        /// The decoded luma intra prediction mode, or `null` for non-intra blocks.
        private final @Nullable LumaIntraPredictionMode yMode;

        /// The decoded chroma intra prediction mode, or `null` when not present.
        private final @Nullable UvIntraPredictionMode uvMode;

        /// The decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        private final int yPaletteSize;

        /// The decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        private final int uvPaletteSize;

        /// The decoded filter-intra mode, or `null` when filter intra is disabled.
        private final @Nullable FilterIntraMode filterIntraMode;

        /// The decoded signed luma angle delta in `[-3, 3]`.
        private final int yAngle;

        /// The decoded signed chroma angle delta in `[-3, 3]`.
        private final int uvAngle;

        /// The decoded signed CFL alpha for chroma U.
        private final int cflAlphaU;

        /// The decoded signed CFL alpha for chroma V.
        private final int cflAlphaV;

        /// Creates one decoded leaf block header.
        ///
        /// @param position the local tile-relative block origin
        /// @param size the decoded block size
        /// @param hasChroma whether the block has chroma samples in the active frame layout
        /// @param skip the decoded skip flag
        /// @param intra whether the block is intra-coded
        /// @param useIntrabc whether the block uses `intrabc`
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier for the block
        /// @param yMode the decoded luma intra prediction mode, or `null`
        /// @param uvMode the decoded chroma intra prediction mode, or `null`
        /// @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
        /// @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
        /// @param filterIntraMode the decoded filter-intra mode, or `null`
        /// @param yAngle the decoded signed luma angle delta in `[-3, 3]`
        /// @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
        /// @param cflAlphaU the decoded signed CFL alpha for chroma U
        /// @param cflAlphaV the decoded signed CFL alpha for chroma V
        public BlockHeader(
                BlockPosition position,
                BlockSize size,
                boolean hasChroma,
                boolean skip,
                boolean intra,
                boolean useIntrabc,
                boolean segmentPredicted,
                int segmentId,
                @Nullable LumaIntraPredictionMode yMode,
                @Nullable UvIntraPredictionMode uvMode,
                int yPaletteSize,
                int uvPaletteSize,
                @Nullable FilterIntraMode filterIntraMode,
                int yAngle,
                int uvAngle,
                int cflAlphaU,
                int cflAlphaV
        ) {
            this.position = Objects.requireNonNull(position, "position");
            this.size = Objects.requireNonNull(size, "size");
            this.hasChroma = hasChroma;
            this.skip = skip;
            this.intra = intra;
            this.useIntrabc = useIntrabc;
            this.segmentPredicted = segmentPredicted;
            this.segmentId = segmentId;
            this.yMode = yMode;
            this.uvMode = uvMode;
            this.yPaletteSize = yPaletteSize;
            this.uvPaletteSize = uvPaletteSize;
            this.filterIntraMode = filterIntraMode;
            this.yAngle = yAngle;
            this.uvAngle = uvAngle;
            this.cflAlphaU = cflAlphaU;
            this.cflAlphaV = cflAlphaV;
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

        /// Returns whether the block used temporal segmentation prediction.
        ///
        /// @return whether the block used temporal segmentation prediction
        public boolean segmentPredicted() {
            return segmentPredicted;
        }

        /// Returns the decoded segment identifier for the block.
        ///
        /// @return the decoded segment identifier for the block
        public int segmentId() {
            return segmentId;
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

        /// Returns the decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        ///
        /// @return the decoded luma palette size in `[0, 8]`, or `0`
        public int yPaletteSize() {
            return yPaletteSize;
        }

        /// Returns the decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled.
        ///
        /// @return the decoded chroma palette size in `[0, 8]`, or `0`
        public int uvPaletteSize() {
            return uvPaletteSize;
        }

        /// Returns the decoded filter-intra mode, or `null` when filter intra is disabled.
        ///
        /// @return the decoded filter-intra mode, or `null`
        public @Nullable FilterIntraMode filterIntraMode() {
            return filterIntraMode;
        }

        /// Returns the decoded signed luma angle delta in `[-3, 3]`.
        ///
        /// @return the decoded signed luma angle delta in `[-3, 3]`
        public int yAngle() {
            return yAngle;
        }

        /// Returns the decoded signed chroma angle delta in `[-3, 3]`.
        ///
        /// @return the decoded signed chroma angle delta in `[-3, 3]`
        public int uvAngle() {
            return uvAngle;
        }

        /// Returns the decoded signed CFL alpha for chroma U.
        ///
        /// @return the decoded signed CFL alpha for chroma U
        public int cflAlphaU() {
            return cflAlphaU;
        }

        /// Returns the decoded signed CFL alpha for chroma V.
        ///
        /// @return the decoded signed CFL alpha for chroma V
        public int cflAlphaV() {
            return cflAlphaV;
        }
    }

    /// The decoded result of one segment-id read pass.
    @NotNullByDefault
    private static final class SegmentReadResult {
        /// Whether the block used temporal segmentation prediction.
        private final boolean segmentPredicted;

        /// The decoded segment identifier.
        private final int segmentId;

        /// Creates one decoded segment-id read result.
        ///
        /// @param segmentPredicted whether the block used temporal segmentation prediction
        /// @param segmentId the decoded segment identifier
        private SegmentReadResult(boolean segmentPredicted, int segmentId) {
            this.segmentPredicted = segmentPredicted;
            this.segmentId = segmentId;
        }

        /// Returns whether the block used temporal segmentation prediction.
        ///
        /// @return whether the block used temporal segmentation prediction
        public boolean segmentPredicted() {
            return segmentPredicted;
        }

        /// Returns the decoded segment identifier.
        ///
        /// @return the decoded segment identifier
        public int segmentId() {
            return segmentId;
        }
    }
}
