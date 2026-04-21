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
import org.glavo.avif.internal.av1.entropy.CdfContext;
import org.glavo.avif.internal.av1.entropy.MsacDecoder;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.PartitionType;
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Typed reader for the first block-level AV1 syntax elements inside one tile bitstream.
///
/// This reader is intentionally small and currently covers only syntax elements already backed by
/// `CdfContext`: partitioning, skip, intra/inter, `intrabc`, and Y/UV intra prediction modes.
@NotNullByDefault
public final class TileSyntaxReader {
    /// The tile-local decode state that owns the mutable decoder and CDF context.
    private final TileDecodeContext tileContext;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// Creates a typed syntax reader over one tile-local decode context.
    ///
    /// @param tileContext the tile-local decode context that owns the mutable decoder and CDF context
    public TileSyntaxReader(TileDecodeContext tileContext) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.msacDecoder = tileContext.msacDecoder();
        this.cdfContext = tileContext.cdfContext();
    }

    /// Returns the tile-local decode context that owns this syntax reader.
    ///
    /// @return the tile-local decode context that owns this syntax reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes one skip flag using the supplied skip-context index.
    ///
    /// @param context the zero-based skip-context index in `[0, 3)`
    /// @return the decoded skip flag
    public boolean readSkipFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipCdf(context));
    }

    /// Decodes one intra/inter decision for inter and switch frames.
    ///
    /// For key and intra-only frames the bitstream does not signal this decision, so this method
    /// returns `true` without consuming entropy-coded bits.
    ///
    /// @param context the zero-based intra-context index in `[0, 4)`
    /// @return `true` when the block is intra-coded, otherwise `false`
    public boolean readIntraBlockFlag(int context) {
        FrameType frameType = tileContext.frameHeader().frameType();
        if (frameType == FrameType.KEY || frameType == FrameType.INTRA) {
            return true;
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntraCdf(context));
    }

    /// Decodes one `use_intrabc` flag when the active frame allows it.
    ///
    /// When the frame does not allow `intrabc`, this method returns `false` without consuming
    /// entropy-coded bits.
    ///
    /// @return the decoded `use_intrabc` flag
    public boolean readUseIntrabcFlag() {
        if (!tileContext.frameHeader().allowIntrabc()) {
            return false;
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntrabcCdf());
    }

    /// Decodes one inter-frame Y intra prediction mode using the supplied size-context index.
    ///
    /// @param sizeContext the zero-based Y-mode size context in `[0, 4)`
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readYMode(int sizeContext) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableYModeCdf(sizeContext), 12);
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one key-frame Y intra prediction mode using the supplied above and left luma modes.
    ///
    /// @param aboveMode the already-decoded luma mode above the current block
    /// @param leftMode the already-decoded luma mode left of the current block
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readKeyFrameYMode(
            LumaIntraPredictionMode aboveMode,
            LumaIntraPredictionMode leftMode
    ) {
        LumaIntraPredictionMode nonNullAboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        LumaIntraPredictionMode nonNullLeftMode = Objects.requireNonNull(leftMode, "leftMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableKeyFrameYModeCdf(nonNullAboveMode.contextIndex(), nonNullLeftMode.contextIndex()),
                12
        );
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one UV intra prediction mode using the supplied Y mode and CFL availability.
    ///
    /// @param yMode the already-decoded luma intra prediction mode for the current block
    /// @param cflAllowed whether CFL is allowed for this block
    /// @return the decoded chroma intra prediction mode
    public UvIntraPredictionMode readUvMode(LumaIntraPredictionMode yMode, boolean cflAllowed) {
        LumaIntraPredictionMode nonNullYMode = Objects.requireNonNull(yMode, "yMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableUvModeCdf(cflAllowed, nonNullYMode.symbolIndex()),
                cflAllowed ? 13 : 12
        );
        return UvIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one partition symbol using the supplied partition block level and context index.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return the decoded partition type
    public PartitionType readPartition(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context),
                nonNullBlockLevel.symbolLimit()
        );
        return PartitionType.fromSymbolIndex(symbol);
    }

    /// Partition block levels that select one of the five AV1 partition CDF groups.
    @NotNullByDefault
    public enum PartitionBlockLevel {
        /// Partition decisions for 128x128 blocks.
        BLOCK_128X128(0, 7),
        /// Partition decisions for 64x64 blocks.
        BLOCK_64X64(1, 9),
        /// Partition decisions for 32x32 blocks.
        BLOCK_32X32(2, 9),
        /// Partition decisions for 16x16 blocks.
        BLOCK_16X16(3, 9),
        /// Partition decisions for 8x8 blocks.
        BLOCK_8X8(4, 3);

        /// The CDF-table index inside `CdfContext`.
        private final int cdfIndex;

        /// The maximum decoded symbol value for this partition level.
        private final int symbolLimit;

        /// Creates one partition block level entry.
        ///
        /// @param cdfIndex the CDF-table index inside `CdfContext`
        /// @param symbolLimit the maximum decoded symbol value for this partition level
        PartitionBlockLevel(int cdfIndex, int symbolLimit) {
            this.cdfIndex = cdfIndex;
            this.symbolLimit = symbolLimit;
        }

        /// Returns the CDF-table index inside `CdfContext`.
        ///
        /// @return the CDF-table index inside `CdfContext`
        public int cdfIndex() {
            return cdfIndex;
        }

        /// Returns the maximum decoded symbol value for this partition level.
        ///
        /// @return the maximum decoded symbol value for this partition level
        public int symbolLimit() {
            return symbolLimit;
        }
    }
}
