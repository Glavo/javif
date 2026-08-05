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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.AvifPixelFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Builds one Large Scale Tile output frame from decoded camera-frame tile regions.
@NotNullByDefault
public final class LargeScaleTileOutputBuilder {
    /// The output sample bit depth.
    private final int bitDepth;
    /// The output chroma layout.
    private final AvifPixelFormat pixelFormat;
    /// The source and destination tile width in luma samples.
    private final int tileWidth;
    /// The source and destination tile height in luma samples.
    private final int tileHeight;
    /// The output tile-column count.
    private final int outputTileColumns;
    /// The output tile-row count.
    private final int outputTileRows;
    /// The output luma width.
    private final int outputWidth;
    /// The output luma height.
    private final int outputHeight;
    /// The mutable output luma samples.
    private final short[] lumaSamples;
    /// The mutable output U samples, or `null` for monochrome output.
    private final short @Nullable [] chromaUSamples;
    /// The mutable output V samples, or `null` for monochrome output.
    private final short @Nullable [] chromaVSamples;
    /// Whether ownership of the output arrays has been transferred.
    private boolean built;

    /// Creates an empty zero-initialized output tile grid.
    ///
    /// @param bitDepth the output sample bit depth
    /// @param pixelFormat the output chroma layout
    /// @param tileWidth the tile width in luma samples
    /// @param tileHeight the tile height in luma samples
    /// @param outputTileColumns the output width in tiles
    /// @param outputTileRows the output height in tiles
    public LargeScaleTileOutputBuilder(
            int bitDepth,
            AvifPixelFormat pixelFormat,
            int tileWidth,
            int tileHeight,
            int outputTileColumns,
            int outputTileRows
    ) {
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("Tile dimensions must be positive");
        }
        if (outputTileColumns <= 0 || outputTileRows <= 0) {
            throw new IllegalArgumentException("Output tile dimensions must be positive");
        }
        this.bitDepth = bitDepth;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.outputTileColumns = outputTileColumns;
        this.outputTileRows = outputTileRows;
        this.outputWidth = Math.multiplyExact(tileWidth, outputTileColumns);
        this.outputHeight = Math.multiplyExact(tileHeight, outputTileRows);
        this.lumaSamples = new short[Math.multiplyExact(outputWidth, outputHeight)];

        int chromaWidth = chromaWidth(pixelFormat, outputWidth);
        int chromaHeight = chromaHeight(pixelFormat, outputHeight);
        if (pixelFormat == AvifPixelFormat.I400) {
            this.chromaUSamples = null;
            this.chromaVSamples = null;
        } else {
            int chromaLength = Math.multiplyExact(chromaWidth, chromaHeight);
            this.chromaUSamples = new short[chromaLength];
            this.chromaVSamples = new short[chromaLength];
        }
    }

    /// Copies one decoded source tile into an output raster position.
    ///
    /// @param source the reconstructed common camera frame containing the decoded tile
    /// @param sourceTileColumn the source tile column
    /// @param sourceTileRow the source tile row
    /// @param outputTileIndex the zero-based destination tile index in raster order
    public void copyTile(
            DecodedPlanes source,
            int sourceTileColumn,
            int sourceTileRow,
            int outputTileIndex
    ) {
        ensureMutable();
        DecodedPlanes checkedSource = Objects.requireNonNull(source, "source");
        if (checkedSource.bitDepth() != bitDepth || checkedSource.pixelFormat() != pixelFormat) {
            throw new IllegalArgumentException("Source tile color configuration differs from the output");
        }
        if (sourceTileColumn < 0 || sourceTileRow < 0) {
            throw new IllegalArgumentException("Source tile coordinates must be non-negative");
        }
        int outputTileCount = Math.multiplyExact(outputTileColumns, outputTileRows);
        if (outputTileIndex < 0 || outputTileIndex >= outputTileCount) {
            throw new IllegalArgumentException("outputTileIndex out of range: " + outputTileIndex);
        }

        int sourceX = Math.multiplyExact(sourceTileColumn, tileWidth);
        int sourceY = Math.multiplyExact(sourceTileRow, tileHeight);
        if (sourceX + tileWidth > checkedSource.codedWidth()
                || sourceY + tileHeight > checkedSource.codedHeight()) {
            throw new IllegalArgumentException("Source tile exceeds the reconstructed camera frame");
        }
        int destinationX = outputTileIndex % outputTileColumns * tileWidth;
        int destinationY = outputTileIndex / outputTileColumns * tileHeight;
        copyPlane(
                checkedSource.lumaPlane(),
                sourceX,
                sourceY,
                tileWidth,
                tileHeight,
                lumaSamples,
                outputWidth,
                destinationX,
                destinationY
        );

        if (pixelFormat != AvifPixelFormat.I400) {
            int chromaTileWidth = chromaWidth(pixelFormat, tileWidth);
            int chromaTileHeight = chromaHeight(pixelFormat, tileHeight);
            int sourceChromaX = chromaWidth(pixelFormat, sourceX);
            int sourceChromaY = chromaHeight(pixelFormat, sourceY);
            int destinationChromaX = chromaWidth(pixelFormat, destinationX);
            int destinationChromaY = chromaHeight(pixelFormat, destinationY);
            int outputChromaWidth = chromaWidth(pixelFormat, outputWidth);
            copyPlane(
                    Objects.requireNonNull(checkedSource.chromaUPlane(), "source.chromaUPlane"),
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    Objects.requireNonNull(chromaUSamples, "chromaUSamples"),
                    outputChromaWidth,
                    destinationChromaX,
                    destinationChromaY
            );
            copyPlane(
                    Objects.requireNonNull(checkedSource.chromaVPlane(), "source.chromaVPlane"),
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    Objects.requireNonNull(chromaVSamples, "chromaVSamples"),
                    outputChromaWidth,
                    destinationChromaX,
                    destinationChromaY
            );
        }
    }

    /// Transfers the accumulated samples into one immutable decoded-plane snapshot.
    ///
    /// This builder must not be used after this method returns.
    ///
    /// @return the assembled output frame
    public DecodedPlanes build() {
        ensureMutable();
        built = true;
        DecodedPlane luma = DecodedPlane.fromOwnedSamples(outputWidth, outputHeight, outputWidth, lumaSamples);
        @Nullable DecodedPlane chromaU = null;
        @Nullable DecodedPlane chromaV = null;
        if (pixelFormat != AvifPixelFormat.I400) {
            int width = chromaWidth(pixelFormat, outputWidth);
            int height = chromaHeight(pixelFormat, outputHeight);
            chromaU = DecodedPlane.fromOwnedSamples(
                    width,
                    height,
                    width,
                    Objects.requireNonNull(chromaUSamples, "chromaUSamples")
            );
            chromaV = DecodedPlane.fromOwnedSamples(
                    width,
                    height,
                    width,
                    Objects.requireNonNull(chromaVSamples, "chromaVSamples")
            );
        }
        return new DecodedPlanes(
                bitDepth,
                pixelFormat,
                outputWidth,
                outputHeight,
                outputWidth,
                outputHeight,
                luma,
                chromaU,
                chromaV
        );
    }

    /// Copies one rectangular plane region into tightly packed destination storage.
    ///
    /// @param source the immutable source plane
    /// @param sourceX the source-region X coordinate
    /// @param sourceY the source-region Y coordinate
    /// @param width the copied width
    /// @param height the copied height
    /// @param destination the destination sample storage
    /// @param destinationStride the destination row stride
    /// @param destinationX the destination-region X coordinate
    /// @param destinationY the destination-region Y coordinate
    private static void copyPlane(
            DecodedPlane source,
            int sourceX,
            int sourceY,
            int width,
            int height,
            short[] destination,
            int destinationStride,
            int destinationX,
            int destinationY
    ) {
        for (int y = 0; y < height; y++) {
            int destinationOffset = (destinationY + y) * destinationStride + destinationX;
            for (int x = 0; x < width; x++) {
                destination[destinationOffset + x] = (short) source.sample(sourceX + x, sourceY + y);
            }
        }
    }

    /// Returns the chroma-plane width for one luma width.
    ///
    /// @param format the chroma layout
    /// @param lumaWidth the luma width
    /// @return the chroma width
    private static int chromaWidth(AvifPixelFormat format, int lumaWidth) {
        return switch (format) {
            case I400 -> 0;
            case I420, I422 -> lumaWidth >> 1;
            case I444 -> lumaWidth;
        };
    }

    /// Returns the chroma-plane height for one luma height.
    ///
    /// @param format the chroma layout
    /// @param lumaHeight the luma height
    /// @return the chroma height
    private static int chromaHeight(AvifPixelFormat format, int lumaHeight) {
        return switch (format) {
            case I400 -> 0;
            case I420 -> lumaHeight >> 1;
            case I422, I444 -> lumaHeight;
        };
    }

    /// Ensures that sample ownership has not already been transferred.
    private void ensureMutable() {
        if (built) {
            throw new IllegalStateException("Large Scale Tile output has already been built");
        }
    }
}
