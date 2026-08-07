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

import org.glavo.avif.decode.DecodedPlane;
import org.jetbrains.annotations.NotNullByDefault;

/// Mutable decoded-plane buffer used while reconstruction is still in progress.
///
/// Samples are stored as unsigned values in the low bits of each `short`. The mutable buffer uses
/// tightly packed storage for either the complete plane or one coordinate-preserving subregion.
@NotNullByDefault
final class MutablePlaneBuffer implements MutableSamplePlane {
    /// The containing plane width in samples.
    private final int width;

    /// The containing plane height in samples.
    private final int height;

    /// The horizontal origin of the retained storage region.
    private final int originX;

    /// The vertical origin of the retained storage region.
    private final int originY;

    /// The retained storage width in samples.
    private final int storageWidth;

    /// The retained storage height in samples.
    private final int storageHeight;

    /// The decoded sample bit depth.
    private final int bitDepth;

    /// The maximum legal sample value for this bit depth.
    private final int maxSampleValue;

    /// The tightly packed mutable sample buffer.
    private final short[] samples;

    /// Whether each sample position has been written by reconstruction.
    private final boolean[] writtenSamples;

    /// Creates one mutable decoded-plane buffer.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param bitDepth the decoded sample bit depth
    MutablePlaneBuffer(int width, int height, int bitDepth) {
        this(width, height, bitDepth, 0, 0, width, height);
    }

    /// Creates a mutable buffer that retains one subregion in containing-plane coordinates.
    ///
    /// @param width the containing plane width in samples
    /// @param height the containing plane height in samples
    /// @param bitDepth the decoded sample bit depth
    /// @param originX the horizontal storage origin in containing-plane coordinates
    /// @param originY the vertical storage origin in containing-plane coordinates
    /// @param storageWidth the retained storage width in samples
    /// @param storageHeight the retained storage height in samples
    MutablePlaneBuffer(
            int width,
            int height,
            int bitDepth,
            int originX,
            int originY,
            int storageWidth,
            int storageHeight
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (bitDepth <= 0 || bitDepth > 15) {
            throw new IllegalArgumentException("bitDepth out of range: " + bitDepth);
        }
        if (originX < 0 || storageWidth <= 0 || originX + storageWidth > width) {
            throw new IllegalArgumentException("Horizontal storage region is outside the plane");
        }
        if (originY < 0 || storageHeight <= 0 || originY + storageHeight > height) {
            throw new IllegalArgumentException("Vertical storage region is outside the plane");
        }
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.storageWidth = storageWidth;
        this.storageHeight = storageHeight;
        this.bitDepth = bitDepth;
        this.maxSampleValue = (1 << bitDepth) - 1;
        int sampleCount = Math.multiplyExact(storageWidth, storageHeight);
        this.samples = new short[sampleCount];
        this.writtenSamples = new boolean[sampleCount];
    }

    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    @Override
    public int width() {
        return width;
    }

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    @Override
    public int height() {
        return height;
    }

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    @Override
    public int bitDepth() {
        return bitDepth;
    }

    /// Returns the maximum legal sample value for this bit depth.
    ///
    /// @return the maximum legal sample value for this bit depth
    int maxSampleValue() {
        return maxSampleValue;
    }

    /// Returns one already reconstructed sample.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return one already reconstructed sample
    @Override
    public int sample(int x, int y) {
        return samples[storageIndex(x, y)] & 0xFFFF;
    }

    /// Stores one reconstructed sample after clipping it into the legal bit-depth range.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param value the reconstructed sample value
    @Override
    public void setSample(int x, int y, int value) {
        int index = storageIndex(x, y);
        samples[index] = (short) clipped(value);
        writtenSamples[index] = true;
    }

    /// Returns whether one retained-region sample has been written by reconstruction.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return whether the retained sample has been written, or `false` outside retained storage
    boolean hasWrittenSample(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        return localX >= 0
                && localX < storageWidth
                && localY >= 0
                && localY < storageHeight
                && writtenSamples[localY * storageWidth + localX];
    }

    /// Returns one sample when it lies inside retained storage, or the fallback value otherwise.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param fallbackValue the fallback value returned outside the plane bounds
    /// @return one retained sample, or the supplied fallback value
    int sampleOrFallback(int x, int y, int fallbackValue) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= storageWidth || localY < 0 || localY >= storageHeight) {
            return fallbackValue;
        }
        return samples[localY * storageWidth + localX] & 0xFFFF;
    }

    /// Converts this mutable reconstruction buffer into one immutable decoded-plane snapshot.
    ///
    /// @return one immutable decoded-plane snapshot
    /// @throws IllegalStateException if this buffer retains only a plane subregion
    DecodedPlane toDecodedPlane() {
        requireCompletePlaneStorage();
        return new DecodedPlane(width, height, width, samples);
    }

    /// Converts the top-left crop of this mutable buffer into one immutable decoded-plane snapshot.
    ///
    /// @param croppedWidth the cropped plane width in samples
    /// @param croppedHeight the cropped plane height in samples
    /// @return one immutable decoded-plane snapshot containing the requested top-left crop and
    ///         retaining internal right and bottom padding
    /// @throws IllegalStateException if this buffer retains only a plane subregion
    DecodedPlane toDecodedPlane(int croppedWidth, int croppedHeight) {
        requireCompletePlaneStorage();
        if (croppedWidth <= 0 || croppedWidth > width) {
            throw new IllegalArgumentException("croppedWidth out of range: " + croppedWidth);
        }
        if (croppedHeight <= 0 || croppedHeight > height) {
            throw new IllegalArgumentException("croppedHeight out of range: " + croppedHeight);
        }
        if (croppedWidth == width && croppedHeight == height) {
            return toDecodedPlane();
        }
        return new DecodedPlane(croppedWidth, croppedHeight, width, samples);
    }

    /// Transfers this buffer's sample storage into one immutable decoded plane.
    ///
    /// The caller must permanently discard this mutable buffer after the transfer.
    ///
    /// @param croppedWidth the visible plane width in samples
    /// @param croppedHeight the visible plane height in samples
    /// @return one immutable plane that owns this buffer's sample storage
    /// @throws IllegalStateException if this buffer retains only a plane subregion
    DecodedPlane takeDecodedPlane(int croppedWidth, int croppedHeight) {
        requireCompletePlaneStorage();
        if (croppedWidth <= 0 || croppedWidth > width) {
            throw new IllegalArgumentException("croppedWidth out of range: " + croppedWidth);
        }
        if (croppedHeight <= 0 || croppedHeight > height) {
            throw new IllegalArgumentException("croppedHeight out of range: " + croppedHeight);
        }
        return DecodedPlane.fromOwnedSamples(croppedWidth, croppedHeight, width, samples);
    }

    /// Transfers the retained storage region into one immutable coordinate-local decoded plane.
    ///
    /// The caller must permanently discard this mutable buffer after the transfer. The requested
    /// dimensions are relative to the retained region and may omit its right or bottom padding.
    ///
    /// @param croppedWidth the visible retained-region width in samples
    /// @param croppedHeight the visible retained-region height in samples
    /// @return one immutable plane that owns the retained sample storage
    DecodedPlane takeStoredDecodedPlane(int croppedWidth, int croppedHeight) {
        if (croppedWidth <= 0 || croppedWidth > storageWidth) {
            throw new IllegalArgumentException("croppedWidth out of range: " + croppedWidth);
        }
        if (croppedHeight <= 0 || croppedHeight > storageHeight) {
            throw new IllegalArgumentException("croppedHeight out of range: " + croppedHeight);
        }
        return DecodedPlane.fromOwnedSamples(croppedWidth, croppedHeight, storageWidth, samples);
    }

    /// Creates an independent mutable copy of this plane buffer.
    ///
    /// @return an independent mutable copy of this plane buffer
    MutablePlaneBuffer copy() {
        MutablePlaneBuffer copy = new MutablePlaneBuffer(
                width,
                height,
                bitDepth,
                originX,
                originY,
                storageWidth,
                storageHeight
        );
        System.arraycopy(samples, 0, copy.samples, 0, samples.length);
        System.arraycopy(writtenSamples, 0, copy.writtenSamples, 0, writtenSamples.length);
        return copy;
    }

    /// Returns the compact storage index for one containing-plane coordinate.
    ///
    /// @param x the horizontal containing-plane coordinate
    /// @param y the vertical containing-plane coordinate
    /// @return the compact storage index
    private int storageIndex(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= storageWidth) {
            throw new IndexOutOfBoundsException("x outside retained storage: " + x);
        }
        if (localY < 0 || localY >= storageHeight) {
            throw new IndexOutOfBoundsException("y outside retained storage: " + y);
        }
        return localY * storageWidth + localX;
    }

    /// Ensures that snapshot operations requiring a top-left plane own complete storage.
    private void requireCompletePlaneStorage() {
        if (originX != 0 || originY != 0 || storageWidth != width || storageHeight != height) {
            throw new IllegalStateException("Operation requires complete plane storage");
        }
    }

    /// Clips one sample value into the legal bit-depth range.
    ///
    /// @param value the sample value to clip
    /// @return the clipped sample value
    private int clipped(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= maxSampleValue) {
            return maxSampleValue;
        }
        return value;
    }
}
