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
package org.glavo.avif.decode;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ShortBuffer;
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

    /// The number of stored rows, including internal bottom padding.
    private final int storageHeight;

    /// The stored unsigned sample values in row-major order.
    private final short @Unmodifiable [] samples;

    /// Creates one immutable decoded-plane snapshot.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the stored unsigned sample values in row-major order; the array may include
    ///                complete padded rows below the visible plane
    public DecodedPlane(int width, int height, int stride, short[] samples) {
        this(width, height, stride, samples, true);
    }

    /// Creates one immutable decoded plane by taking exclusive ownership of its sample storage.
    ///
    /// The caller must not access or modify `samples` after this method returns. This method exists
    /// for decoder stages that have finished producing an otherwise unaliased output buffer;
    /// callers that retain their array must use [#DecodedPlane(int, int, int, short[])].
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the exclusively owned stored samples, including any complete padded rows
    /// @return one immutable decoded plane backed by `samples`
    public static DecodedPlane fromOwnedSamples(int width, int height, int stride, short[] samples) {
        return new DecodedPlane(width, height, stride, samples, false);
    }

    /// Creates one immutable decoded plane with copied or exclusively transferred sample storage.
    ///
    /// @param width the plane width in samples
    /// @param height the plane height in samples
    /// @param stride the sample stride of one plane row
    /// @param samples the stored unsigned sample values in row-major order
    /// @param copySamples whether to copy the supplied storage
    private DecodedPlane(int width, int height, int stride, short[] samples, boolean copySamples) {
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

        short[] checkedSamples = Objects.requireNonNull(samples, "samples");
        if (checkedSamples.length < (int) requiredLength || checkedSamples.length % stride != 0) {
            throw new IllegalArgumentException("samples length does not contain complete stored rows");
        }

        this.width = width;
        this.height = height;
        this.stride = stride;
        this.storageHeight = checkedSamples.length / stride;
        this.samples = copySamples ? Arrays.copyOf(checkedSamples, checkedSamples.length) : checkedSamples;
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

    /// Returns the number of stored rows, including internal bottom padding.
    ///
    /// @return the stored row count, which is at least [#height()]
    public int storageHeight() {
        return storageHeight;
    }

    /// Returns the stored unsigned sample values in row-major order, including internal padding.
    ///
    /// @return the stored unsigned sample values in row-major order
    public short @Unmodifiable [] samples() {
        return Arrays.copyOf(samples, samples.length);
    }

    /// Returns a read-only view of the visible stored rows.
    ///
    /// The view includes right-hand row padding described by [#stride()] but excludes rows below
    /// [#height()].
    ///
    /// @return a read-only view of the stored unsigned sample values
    public @UnmodifiableView ShortBuffer sampleBuffer() {
        return ShortBuffer.wrap(samples, 0, stride * height).slice().asReadOnlyBuffer();
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

    /// Returns one stored sample, including right or bottom padding outside the visible plane.
    ///
    /// @param x the zero-based stored sample coordinate in `[0, stride)`
    /// @param y the zero-based stored row coordinate in `[0, storageHeight)`
    /// @return one unsigned stored sample value
    public int storedSample(int x, int y) {
        if (x < 0 || x >= stride) {
            throw new IndexOutOfBoundsException("x out of stored range: " + x);
        }
        if (y < 0 || y >= storageHeight) {
            throw new IndexOutOfBoundsException("y out of stored range: " + y);
        }
        return samples[y * stride + x] & 0xFFFF;
    }
}
