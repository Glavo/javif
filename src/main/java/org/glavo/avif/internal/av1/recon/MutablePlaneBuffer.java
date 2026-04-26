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

import org.jetbrains.annotations.NotNullByDefault;

/// Mutable decoded-plane buffer used while reconstruction is still in progress.
///
/// Samples are stored as unsigned values in the low bits of each `short`. The mutable buffer uses
/// a tightly packed sample stride equal to `width`.
@NotNullByDefault
final class MutablePlaneBuffer {
    /// The plane width in samples.
    private final int width;

    /// The plane height in samples.
    private final int height;

    /// The decoded sample bit depth.
    private final int bitDepth;

    /// The maximum legal sample value for this bit depth.
    private final int maxSampleValue;

    /// The tightly packed mutable sample buffer.
    private final short[] samples;

    /// Creates one mutable decoded-plane buffer.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param bitDepth the decoded sample bit depth
    MutablePlaneBuffer(int width, int height, int bitDepth) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (bitDepth <= 0 || bitDepth > 15) {
            throw new IllegalArgumentException("bitDepth out of range: " + bitDepth);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.maxSampleValue = (1 << bitDepth) - 1;
        this.samples = new short[width * height];
    }

    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    int width() {
        return width;
    }

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    int height() {
        return height;
    }

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    int bitDepth() {
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
    int sample(int x, int y) {
        if (x < 0 || x >= width) {
            throw new IndexOutOfBoundsException("x out of range: " + x);
        }
        if (y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        return samples[y * width + x] & 0xFFFF;
    }

    /// Stores one reconstructed sample after clipping it into the legal bit-depth range.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param value the reconstructed sample value
    void setSample(int x, int y, int value) {
        if (x < 0 || x >= width) {
            throw new IndexOutOfBoundsException("x out of range: " + x);
        }
        if (y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        samples[y * width + x] = (short) clipped(value);
    }

    /// Returns one sample when it lies inside the plane, or the supplied fallback value otherwise.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @param fallbackValue the fallback value returned outside the plane bounds
    /// @return one in-range sample, or the supplied fallback value
    int sampleOrFallback(int x, int y, int fallbackValue) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return fallbackValue;
        }
        return samples[y * width + x] & 0xFFFF;
    }

    /// Converts this mutable reconstruction buffer into one immutable decoded-plane snapshot.
    ///
    /// @return one immutable decoded-plane snapshot
    DecodedPlane toDecodedPlane() {
        return new DecodedPlane(width, height, width, samples);
    }

    /// Converts the top-left crop of this mutable buffer into one immutable decoded-plane snapshot.
    ///
    /// @param croppedWidth the cropped plane width in samples
    /// @param croppedHeight the cropped plane height in samples
    /// @return one immutable decoded-plane snapshot containing the requested top-left crop
    DecodedPlane toDecodedPlane(int croppedWidth, int croppedHeight) {
        if (croppedWidth <= 0 || croppedWidth > width) {
            throw new IllegalArgumentException("croppedWidth out of range: " + croppedWidth);
        }
        if (croppedHeight <= 0 || croppedHeight > height) {
            throw new IllegalArgumentException("croppedHeight out of range: " + croppedHeight);
        }
        if (croppedWidth == width && croppedHeight == height) {
            return toDecodedPlane();
        }
        short[] croppedSamples = new short[croppedWidth * croppedHeight];
        for (int y = 0; y < croppedHeight; y++) {
            System.arraycopy(samples, y * width, croppedSamples, y * croppedWidth, croppedWidth);
        }
        return new DecodedPlane(croppedWidth, croppedHeight, croppedWidth, croppedSamples);
    }

    /// Creates an independent mutable copy of this plane buffer.
    ///
    /// @return an independent mutable copy of this plane buffer
    MutablePlaneBuffer copy() {
        MutablePlaneBuffer copy = new MutablePlaneBuffer(width, height, bitDepth);
        System.arraycopy(samples, 0, copy.samples, 0, samples.length);
        return copy;
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
