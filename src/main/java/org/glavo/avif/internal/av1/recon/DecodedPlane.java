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

import java.util.Arrays;
import java.util.Objects;

/// Immutable snapshot of one decoded image plane.
///
/// Samples are stored as unsigned values in the low bits of each `short`. `stride` is measured in
/// samples, not bytes.
@NotNullByDefault
public final class DecodedPlane {
    /// The plane width in samples.
    private final int width;

    /// The plane height in samples.
    private final int height;

    /// The sample stride of one plane row.
    private final int stride;

    /// The stored unsigned sample values in row-major order.
    private final short[] samples;

    /// Creates one immutable decoded-plane snapshot.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the stored unsigned sample values in row-major order
    public DecodedPlane(int width, int height, int stride, short[] samples) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (stride < width) {
            throw new IllegalArgumentException("stride < width: " + stride);
        }
        long requiredLength = (long) stride * height;
        if (requiredLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("plane storage is too large");
        }

        this.width = width;
        this.height = height;
        this.stride = stride;
        this.samples = Arrays.copyOf(Objects.requireNonNull(samples, "samples"), samples.length);
        if (this.samples.length != (int) requiredLength) {
            throw new IllegalArgumentException("samples length does not match stride * height");
        }
    }

    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    public int width() {
        return width;
    }

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    public int height() {
        return height;
    }

    /// Returns the sample stride of one plane row.
    ///
    /// @return the sample stride of one plane row
    public int stride() {
        return stride;
    }

    /// Returns the stored unsigned sample values in row-major order.
    ///
    /// @return the stored unsigned sample values in row-major order
    public short[] samples() {
        return Arrays.copyOf(samples, samples.length);
    }

    /// Returns one unsigned sample value.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return one unsigned sample value
    public int sample(int x, int y) {
        if (x < 0 || x >= width) {
            throw new IndexOutOfBoundsException("x out of range: " + x);
        }
        if (y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("y out of range: " + y);
        }
        return samples[y * stride + x] & 0xFFFF;
    }
}
