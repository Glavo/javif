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
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.FrameAssembly;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.InterMotionVector;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.model.TileBitstream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Tile-local decode state derived from a fully assembled frame and one collected tile bitstream.
///
/// Pixel-space end coordinates are exclusive.
@NotNullByDefault
public final class TileDecodeContext {
    /// The frame assembly that owns the tile.
    private final FrameAssembly assembly;

    /// The active sequence header for the tile.
    private final SequenceHeader sequenceHeader;

    /// The active frame header for the tile.
    private final FrameHeader frameHeader;

    /// The selected tile bitstream.
    private final TileBitstream tileBitstream;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// The tile-local temporal motion field sampled from refreshed reference frames.
    private final TemporalMotionField temporalMotionField;

    /// The tile-local temporal motion field being produced while decoding the current frame.
    private final TemporalMotionField decodedTemporalMotionField;

    /// The mutable tile-local block syntax state shared across superblocks.
    private final BlockSyntaxState blockSyntaxState;

    /// The tile-local loop-restoration units decoded from this tile.
    private final RestorationUnitMap restorationUnitMap;

    /// The zero-based tile index within the frame.
    private final int tileIndex;

    /// The zero-based tile row within the frame.
    private final int tileRow;

    /// The zero-based tile column within the frame.
    private final int tileColumn;

    /// The superblock size in pixels for the active sequence.
    private final int superblockSize;

    /// The inclusive start superblock column for the tile.
    private final int columnStartSuperblock;

    /// The exclusive end superblock column for the tile.
    private final int columnEndSuperblock;

    /// The inclusive start superblock row for the tile.
    private final int rowStartSuperblock;

    /// The exclusive end superblock row for the tile.
    private final int rowEndSuperblock;

    /// The inclusive start X coordinate in pixels.
    private final int startX;

    /// The exclusive end X coordinate in pixels.
    private final int endX;

    /// The inclusive start Y coordinate in pixels.
    private final int startY;

    /// The exclusive end Y coordinate in pixels.
    private final int endY;

    /// Creates tile-local decode state.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param sequenceHeader the active sequence header for the tile
    /// @param frameHeader the active frame header for the tile
    /// @param tileBitstream the selected tile bitstream
    /// @param msacDecoder the tile-local arithmetic decoder
    /// @param cdfContext the tile-local mutable CDF context
    /// @param temporalMotionField the tile-local temporal motion field sampled from refreshed reference frames
    /// @param decodedTemporalMotionField the tile-local temporal motion field produced while decoding the current frame
    /// @param blockSyntaxState the mutable tile-local block syntax state shared across superblocks
    /// @param restorationUnitMap the tile-local loop-restoration units decoded from this tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param tileRow the zero-based tile row within the frame
    /// @param tileColumn the zero-based tile column within the frame
    /// @param superblockSize the superblock size in pixels
    /// @param columnStartSuperblock the inclusive start superblock column
    /// @param columnEndSuperblock the exclusive end superblock column
    /// @param rowStartSuperblock the inclusive start superblock row
    /// @param rowEndSuperblock the exclusive end superblock row
    /// @param startX the inclusive start X coordinate in pixels
    /// @param endX the exclusive end X coordinate in pixels
    /// @param startY the inclusive start Y coordinate in pixels
    /// @param endY the exclusive end Y coordinate in pixels
    private TileDecodeContext(
            FrameAssembly assembly,
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            TileBitstream tileBitstream,
            MsacDecoder msacDecoder,
            CdfContext cdfContext,
            TemporalMotionField temporalMotionField,
            TemporalMotionField decodedTemporalMotionField,
            BlockSyntaxState blockSyntaxState,
            RestorationUnitMap restorationUnitMap,
            int tileIndex,
            int tileRow,
            int tileColumn,
            int superblockSize,
            int columnStartSuperblock,
            int columnEndSuperblock,
            int rowStartSuperblock,
            int rowEndSuperblock,
            int startX,
            int endX,
            int startY,
            int endY
    ) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.sequenceHeader = Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.tileBitstream = Objects.requireNonNull(tileBitstream, "tileBitstream");
        this.msacDecoder = Objects.requireNonNull(msacDecoder, "msacDecoder");
        this.cdfContext = Objects.requireNonNull(cdfContext, "cdfContext");
        this.temporalMotionField = Objects.requireNonNull(temporalMotionField, "temporalMotionField");
        this.decodedTemporalMotionField = Objects.requireNonNull(decodedTemporalMotionField, "decodedTemporalMotionField");
        this.blockSyntaxState = Objects.requireNonNull(blockSyntaxState, "blockSyntaxState");
        this.restorationUnitMap = Objects.requireNonNull(restorationUnitMap, "restorationUnitMap");
        this.tileIndex = tileIndex;
        this.tileRow = tileRow;
        this.tileColumn = tileColumn;
        this.superblockSize = superblockSize;
        this.columnStartSuperblock = columnStartSuperblock;
        this.columnEndSuperblock = columnEndSuperblock;
        this.rowStartSuperblock = rowStartSuperblock;
        this.rowEndSuperblock = rowEndSuperblock;
        this.startX = startX;
        this.endX = endX;
        this.startY = startY;
        this.endY = endY;
    }

    /// Creates tile-local decode state with a fresh default CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        return create(
                nonNullAssembly,
                tileIndex,
                CdfContext.createDefault(nonNullAssembly.frameHeader().quantization().baseQIndex())
        );
    }

    /// Creates tile-local decode state with a copy of the supplied base CDF context.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base CDF context template to copy for this tile
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(FrameAssembly assembly, int tileIndex, CdfContext baseCdfContext) {
        return create(assembly, tileIndex, baseCdfContext, null);
    }

    /// Creates tile-local decode state with a fresh default CDF context and a supplied temporal motion field.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param temporalMotionField the tile-local temporal motion field to attach
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(
            FrameAssembly assembly,
            int tileIndex,
            TemporalMotionField temporalMotionField
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        return create(
                nonNullAssembly,
                tileIndex,
                CdfContext.createDefault(nonNullAssembly.frameHeader().quantization().baseQIndex()),
                temporalMotionField
        );
    }

    /// Creates tile-local decode state with a copy of the supplied base CDF context and a supplied temporal motion field.
    ///
    /// @param assembly the frame assembly that owns the tile
    /// @param tileIndex the zero-based tile index within the frame
    /// @param baseCdfContext the base CDF context template to copy for this tile
    /// @param temporalMotionField the tile-local temporal motion field to attach, or `null` for an empty field
    /// @return tile-local decode state for the selected tile
    public static TileDecodeContext create(
            FrameAssembly assembly,
            int tileIndex,
            CdfContext baseCdfContext,
            @org.jetbrains.annotations.Nullable TemporalMotionField temporalMotionField
    ) {
        FrameAssembly nonNullAssembly = Objects.requireNonNull(assembly, "assembly");
        CdfContext copiedCdfContext = Objects.requireNonNull(baseCdfContext, "baseCdfContext").copy();
        TileBitstream tileBitstream = nonNullAssembly.tileBitstream(tileIndex);
        SequenceHeader sequenceHeader = nonNullAssembly.sequenceHeader();
        FrameHeader frameHeader = nonNullAssembly.frameHeader();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();

        int columns = tiling.columns();
        int tileRow = tileIndex / columns;
        int tileColumn = tileIndex % columns;
        int[] columnStarts = tiling.columnStartSuperblocks();
        int[] rowStarts = tiling.rowStartSuperblocks();
        int columnStartSuperblock = columnStarts[tileColumn];
        int columnEndSuperblock = columnStarts[tileColumn + 1];
        int rowStartSuperblock = rowStarts[tileRow];
        int rowEndSuperblock = rowStarts[tileRow + 1];
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int startX = columnStartSuperblock * superblockSize;
        int endX = Math.min(frameHeader.frameSize().codedWidth(), columnEndSuperblock * superblockSize);
        int startY = rowStartSuperblock * superblockSize;
        int endY = Math.min(frameHeader.frameSize().height(), rowEndSuperblock * superblockSize);
        int width8 = (endX - startX + 7) >> 3;
        int height8 = (endY - startY + 7) >> 3;
        TemporalMotionField effectiveTemporalMotionField = temporalMotionField == null
                ? new TemporalMotionField(width8, height8)
                : temporalMotionField;
        if (effectiveTemporalMotionField.width8() != width8 || effectiveTemporalMotionField.height8() != height8) {
            throw new IllegalArgumentException(
                    "Temporal motion field dimensions do not match tile geometry: expected "
                            + width8 + "x" + height8
                            + " but got "
                            + effectiveTemporalMotionField.width8() + "x" + effectiveTemporalMotionField.height8()
            );
        }

        return new TileDecodeContext(
                nonNullAssembly,
                sequenceHeader,
                frameHeader,
                tileBitstream,
                tileBitstream.openMsacDecoder(frameHeader.disableCdfUpdate()),
                copiedCdfContext,
                effectiveTemporalMotionField,
                new TemporalMotionField(width8, height8),
                new BlockSyntaxState(frameHeader.quantization().baseQIndex()),
                RestorationUnitMap.createEmpty(nonNullAssembly),
                tileIndex,
                tileRow,
                tileColumn,
                superblockSize,
                columnStartSuperblock,
                columnEndSuperblock,
                rowStartSuperblock,
                rowEndSuperblock,
                startX,
                endX,
                startY,
                endY
        );
    }

    /// Returns the frame assembly that owns the tile.
    ///
    /// @return the frame assembly that owns the tile
    public FrameAssembly assembly() {
        return assembly;
    }

    /// Returns the active sequence header for the tile.
    ///
    /// @return the active sequence header for the tile
    public SequenceHeader sequenceHeader() {
        return sequenceHeader;
    }

    /// Returns the active frame header for the tile.
    ///
    /// @return the active frame header for the tile
    public FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
    ///
    /// @param referenceFrame the internal LAST..ALTREF reference index
    /// @return the refreshed reference-frame header for the supplied reference, or `null`
    public @Nullable FrameHeader referenceFrameHeader(int referenceFrame) {
        return assembly.referenceFrameHeader(referenceFrame);
    }

    /// Returns the selected tile bitstream.
    ///
    /// @return the selected tile bitstream
    public TileBitstream tileBitstream() {
        return tileBitstream;
    }

    /// Returns the tile-local arithmetic decoder.
    ///
    /// @return the tile-local arithmetic decoder
    public MsacDecoder msacDecoder() {
        return msacDecoder;
    }

    /// Returns the tile-local mutable CDF context.
    ///
    /// @return the tile-local mutable CDF context
    public CdfContext cdfContext() {
        return cdfContext;
    }

    /// Returns the tile-local temporal motion field sampled from refreshed reference frames.
    ///
    /// @return the tile-local temporal motion field sampled from refreshed reference frames
    public TemporalMotionField temporalMotionField() {
        return temporalMotionField;
    }

    /// Returns the tile-local temporal motion field produced while decoding the current frame.
    ///
    /// @return the tile-local temporal motion field produced while decoding the current frame
    public TemporalMotionField decodedTemporalMotionField() {
        return decodedTemporalMotionField;
    }

    /// Returns the mutable tile-local block syntax state shared across superblocks.
    ///
    /// @return the mutable tile-local block syntax state shared across superblocks
    public BlockSyntaxState blockSyntaxState() {
        return blockSyntaxState;
    }

    /// Returns the tile-local loop-restoration units decoded from this tile.
    ///
    /// @return the tile-local loop-restoration units decoded from this tile
    public RestorationUnitMap restorationUnitMap() {
        return restorationUnitMap;
    }

    /// Returns the zero-based tile index within the frame.
    ///
    /// @return the zero-based tile index within the frame
    public int tileIndex() {
        return tileIndex;
    }

    /// Returns the zero-based tile row within the frame.
    ///
    /// @return the zero-based tile row within the frame
    public int tileRow() {
        return tileRow;
    }

    /// Returns the zero-based tile column within the frame.
    ///
    /// @return the zero-based tile column within the frame
    public int tileColumn() {
        return tileColumn;
    }

    /// Returns the superblock size in pixels for the active sequence.
    ///
    /// @return the superblock size in pixels for the active sequence
    public int superblockSize() {
        return superblockSize;
    }

    /// Returns the inclusive start superblock column for the tile.
    ///
    /// @return the inclusive start superblock column for the tile
    public int columnStartSuperblock() {
        return columnStartSuperblock;
    }

    /// Returns the exclusive end superblock column for the tile.
    ///
    /// @return the exclusive end superblock column for the tile
    public int columnEndSuperblock() {
        return columnEndSuperblock;
    }

    /// Returns the inclusive start superblock row for the tile.
    ///
    /// @return the inclusive start superblock row for the tile
    public int rowStartSuperblock() {
        return rowStartSuperblock;
    }

    /// Returns the exclusive end superblock row for the tile.
    ///
    /// @return the exclusive end superblock row for the tile
    public int rowEndSuperblock() {
        return rowEndSuperblock;
    }

    /// Returns the inclusive start X coordinate in pixels.
    ///
    /// @return the inclusive start X coordinate in pixels
    public int startX() {
        return startX;
    }

    /// Returns the exclusive end X coordinate in pixels.
    ///
    /// @return the exclusive end X coordinate in pixels
    public int endX() {
        return endX;
    }

    /// Returns the inclusive start Y coordinate in pixels.
    ///
    /// @return the inclusive start Y coordinate in pixels
    public int startY() {
        return startY;
    }

    /// Returns the exclusive end Y coordinate in pixels.
    ///
    /// @return the exclusive end Y coordinate in pixels
    public int endY() {
        return endY;
    }

    /// Returns the tile width in pixels.
    ///
    /// @return the tile width in pixels
    public int width() {
        return endX - startX;
    }

    /// Returns the tile height in pixels.
    ///
    /// @return the tile height in pixels
    public int height() {
        return endY - startY;
    }

    /// A tile-local temporal motion field sampled in 8x8 units.
    @NotNullByDefault
    public static final class TemporalMotionField {
        /// The tile width rounded up to 8x8 units.
        private final int width8;

        /// The tile height rounded up to 8x8 units.
        private final int height8;

        /// The temporal motion blocks indexed in tile-relative 8x8 units.
        private final @org.jetbrains.annotations.Nullable TemporalMotionBlock[] blocks;

        /// Creates an empty tile-local temporal motion field.
        ///
        /// @param width8 the tile width rounded up to 8x8 units
        /// @param height8 the tile height rounded up to 8x8 units
        public TemporalMotionField(int width8, int height8) {
            if (width8 < 0) {
                throw new IllegalArgumentException("width8 < 0: " + width8);
            }
            if (height8 < 0) {
                throw new IllegalArgumentException("height8 < 0: " + height8);
            }
            this.width8 = width8;
            this.height8 = height8;
            this.blocks = new TemporalMotionBlock[width8 * height8];
        }

        /// Returns the tile width rounded up to 8x8 units.
        ///
        /// @return the tile width rounded up to 8x8 units
        public int width8() {
            return width8;
        }

        /// Returns the tile height rounded up to 8x8 units.
        ///
        /// @return the tile height rounded up to 8x8 units
        public int height8() {
            return height8;
        }

        /// Stores one temporal motion block at the supplied tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @param block the temporal motion block to store
        public void setBlock(int x8, int y8, TemporalMotionBlock block) {
            blocks[index(x8, y8)] = Objects.requireNonNull(block, "block");
        }

        /// Clears the temporal motion block stored at the supplied tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        public void clearBlock(int x8, int y8) {
            blocks[index(x8, y8)] = null;
        }

        /// Returns the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @return the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`
        public @org.jetbrains.annotations.Nullable TemporalMotionBlock block(int x8, int y8) {
            return blocks[index(x8, y8)];
        }

        /// Returns a shallow copy of this temporal motion field.
        ///
        /// The copied field owns a new storage array while reusing immutable temporal-motion block
        /// entries.
        ///
        /// @return a shallow copy of this temporal motion field
        public TemporalMotionField copy() {
            TemporalMotionField copy = new TemporalMotionField(width8, height8);
            System.arraycopy(blocks, 0, copy.blocks, 0, blocks.length);
            return copy;
        }

        /// Returns the flat array index for one tile-relative 8x8 coordinate.
        ///
        /// @param x8 the tile-relative X coordinate in 8x8 units
        /// @param y8 the tile-relative Y coordinate in 8x8 units
        /// @return the flat array index for one tile-relative 8x8 coordinate
        private int index(int x8, int y8) {
            if (x8 < 0 || x8 >= width8) {
                throw new IndexOutOfBoundsException("x8 out of range: " + x8);
            }
            if (y8 < 0 || y8 >= height8) {
                throw new IndexOutOfBoundsException("y8 out of range: " + y8);
            }
            return y8 * width8 + x8;
        }
    }

    /// One temporal motion-field sample projected into the current tile.
    @NotNullByDefault
    public static final class TemporalMotionBlock {
        /// Whether the temporal sample carries compound references.
        private final boolean compoundReference;

        /// The primary reference frame in internal LAST..ALTREF order.
        private final int referenceFrame0;

        /// The secondary reference frame in internal LAST..ALTREF order, or `-1`.
        private final int referenceFrame1;

        /// The primary temporal motion-vector state.
        private final InterMotionVector motionVector0;

        /// The secondary temporal motion-vector state, or `null`.
        private final @org.jetbrains.annotations.Nullable InterMotionVector motionVector1;

        /// Creates one single-reference temporal motion-field sample.
        ///
        /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
        /// @param motionVector0 the primary temporal motion-vector state
        /// @return one single-reference temporal motion-field sample
        public static TemporalMotionBlock singleReference(int referenceFrame0, InterMotionVector motionVector0) {
            return new TemporalMotionBlock(false, referenceFrame0, -1, motionVector0, null);
        }

        /// Creates one compound-reference temporal motion-field sample.
        ///
        /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
        /// @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order
        /// @param motionVector0 the primary temporal motion-vector state
        /// @param motionVector1 the secondary temporal motion-vector state
        /// @return one compound-reference temporal motion-field sample
        public static TemporalMotionBlock compoundReference(
                int referenceFrame0,
                int referenceFrame1,
                InterMotionVector motionVector0,
                InterMotionVector motionVector1
        ) {
            return new TemporalMotionBlock(true, referenceFrame0, referenceFrame1, motionVector0, motionVector1);
        }

        /// Creates one temporal motion-field sample.
        ///
        /// @param compoundReference whether the temporal sample carries compound references
        /// @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
        /// @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order, or `-1`
        /// @param motionVector0 the primary temporal motion-vector state
        /// @param motionVector1 the secondary temporal motion-vector state, or `null`
        public TemporalMotionBlock(
                boolean compoundReference,
                int referenceFrame0,
                int referenceFrame1,
                InterMotionVector motionVector0,
                @org.jetbrains.annotations.Nullable InterMotionVector motionVector1
        ) {
            if (referenceFrame0 < 0) {
                throw new IllegalArgumentException("referenceFrame0 < 0: " + referenceFrame0);
            }
            if (compoundReference) {
                if (referenceFrame1 < 0) {
                    throw new IllegalArgumentException("Compound temporal motion blocks must carry referenceFrame1");
                }
                if (motionVector1 == null) {
                    throw new IllegalArgumentException("Compound temporal motion blocks must carry motionVector1");
                }
            } else {
                if (referenceFrame1 >= 0) {
                    throw new IllegalArgumentException("Single-reference temporal motion blocks must not carry referenceFrame1");
                }
                if (motionVector1 != null) {
                    throw new IllegalArgumentException("Single-reference temporal motion blocks must not carry motionVector1");
                }
            }
            this.compoundReference = compoundReference;
            this.referenceFrame0 = referenceFrame0;
            this.referenceFrame1 = referenceFrame1;
            this.motionVector0 = Objects.requireNonNull(motionVector0, "motionVector0");
            this.motionVector1 = motionVector1;
        }

        /// Returns whether the temporal sample carries compound references.
        ///
        /// @return whether the temporal sample carries compound references
        public boolean compoundReference() {
            return compoundReference;
        }

        /// Returns the primary reference frame in internal LAST..ALTREF order.
        ///
        /// @return the primary reference frame in internal LAST..ALTREF order
        public int referenceFrame0() {
            return referenceFrame0;
        }

        /// Returns the secondary reference frame in internal LAST..ALTREF order, or `-1`.
        ///
        /// @return the secondary reference frame in internal LAST..ALTREF order, or `-1`
        public int referenceFrame1() {
            return referenceFrame1;
        }

        /// Returns the primary temporal motion-vector state.
        ///
        /// @return the primary temporal motion-vector state
        public InterMotionVector motionVector0() {
            return motionVector0;
        }

        /// Returns the secondary temporal motion-vector state, or `null`.
        ///
        /// @return the secondary temporal motion-vector state, or `null`
        public @org.jetbrains.annotations.Nullable InterMotionVector motionVector1() {
            return motionVector1;
        }
    }

    /// Mutable tile-local block syntax state shared across superblocks.
    @NotNullByDefault
    public static final class BlockSyntaxState {
        /// The current luma AC quantizer index carried across superblocks.
        private int currentQIndex;

        /// The current delta-lf state carried across superblocks.
        private final int[] currentDeltaLfValues;

        /// The current superblock origin X coordinate in tile-relative 4x4 units, or `-1`.
        private int currentSuperblockOriginX4;

        /// The current superblock origin Y coordinate in tile-relative 4x4 units, or `-1`.
        private int currentSuperblockOriginY4;

        /// The current superblock CDEF indices in raster quadrant order.
        private final int[] cdefIndices;

        /// Creates one tile-local block syntax state.
        ///
        /// @param baseQIndex the initial frame-level base quantizer index
        public BlockSyntaxState(int baseQIndex) {
            this.currentQIndex = baseQIndex;
            this.currentDeltaLfValues = new int[4];
            this.currentSuperblockOriginX4 = -1;
            this.currentSuperblockOriginY4 = -1;
            this.cdefIndices = new int[]{-1, -1, -1, -1};
        }

        /// Returns the current luma AC quantizer index carried across superblocks.
        ///
        /// @return the current luma AC quantizer index carried across superblocks
        public int currentQIndex() {
            return currentQIndex;
        }

        /// Sets the current luma AC quantizer index carried across superblocks.
        ///
        /// @param currentQIndex the replacement current luma AC quantizer index
        public void setCurrentQIndex(int currentQIndex) {
            this.currentQIndex = currentQIndex;
        }

        /// Returns the current delta-lf value for one runtime slot.
        ///
        /// @param index the zero-based delta-lf runtime slot index in `[0, 4)`
        /// @return the current delta-lf value for the supplied runtime slot
        public int currentDeltaLfValue(int index) {
            return currentDeltaLfValues[Objects.checkIndex(index, currentDeltaLfValues.length)];
        }

        /// Updates the current delta-lf value for one runtime slot.
        ///
        /// @param index the zero-based delta-lf runtime slot index in `[0, 4)`
        /// @param value the replacement delta-lf value
        public void setCurrentDeltaLfValue(int index, int value) {
            currentDeltaLfValues[Objects.checkIndex(index, currentDeltaLfValues.length)] = value;
        }

        /// Returns a copy of the current delta-lf runtime slots.
        ///
        /// @return a copy of the current delta-lf runtime slots
        public int[] currentDeltaLfValues() {
            return java.util.Arrays.copyOf(currentDeltaLfValues, currentDeltaLfValues.length);
        }

        /// Resets the current-superblock CDEF cache when decoding enters a different superblock.
        ///
        /// @param position the current block origin in tile-relative 4x4 units
        /// @param superblockSize the active superblock size in pixels
        public void enterSuperblock(BlockPosition position, int superblockSize) {
            BlockPosition nonNullPosition = Objects.requireNonNull(position, "position");
            int superblockSize4 = superblockSize >> 2;
            int superblockOriginX4 = (nonNullPosition.x4() / superblockSize4) * superblockSize4;
            int superblockOriginY4 = (nonNullPosition.y4() / superblockSize4) * superblockSize4;
            if (superblockOriginX4 != currentSuperblockOriginX4 || superblockOriginY4 != currentSuperblockOriginY4) {
                currentSuperblockOriginX4 = superblockOriginX4;
                currentSuperblockOriginY4 = superblockOriginY4;
                java.util.Arrays.fill(cdefIndices, -1);
            }
        }

        /// Returns the current cached CDEF index for one superblock quadrant, or `-1`.
        ///
        /// @param index the zero-based quadrant index in `[0, 4)`
        /// @return the current cached CDEF index for one superblock quadrant, or `-1`
        public int cdefIndex(int index) {
            return cdefIndices[Objects.checkIndex(index, cdefIndices.length)];
        }

        /// Updates the cached CDEF index for one superblock quadrant.
        ///
        /// @param index the zero-based quadrant index in `[0, 4)`
        /// @param value the replacement cached CDEF index, or `-1`
        public void setCdefIndex(int index, int value) {
            cdefIndices[Objects.checkIndex(index, cdefIndices.length)] = value;
        }
    }
}
