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

import java.util.Objects;

/// Stores predicted samples for one block while reading all other samples through a base plane.
///
/// Unwritten positions inside the block also read through to the base plane. This preserves the
/// initial predictor state while allowing recursive intra kernels to observe samples they have
/// already written without copying the containing frame.
@NotNullByDefault
final class BlockOverlayPlane implements MutableSamplePlane {
    /// The plane that supplies samples outside the overlay and unwritten samples inside it.
    private final MutableSamplePlane basePlane;

    /// The inclusive horizontal origin of the writable overlay.
    private final int originX;

    /// The inclusive vertical origin of the writable overlay.
    private final int originY;

    /// The writable overlay width in samples.
    private final int overlayWidth;

    /// The writable overlay height in samples.
    private final int overlayHeight;

    /// The maximum legal sample value for the plane bit depth.
    private final int maximumSampleValue;

    /// The compact overlay sample storage.
    private final short[] samples;

    /// Whether each compact overlay position has been written.
    private final boolean[] writtenSamples;

    /// Creates one writable block overlay over the supplied base plane.
    ///
    /// A requested block that reaches beyond the containing plane is clipped at the right and
    /// bottom edges, matching intra prediction's in-plane write behavior.
    ///
    /// @param basePlane     the plane that supplies read-through samples
    /// @param originX       the inclusive horizontal overlay origin
    /// @param originY       the inclusive vertical overlay origin
    /// @param overlayWidth  the overlay width in samples
    /// @param overlayHeight the overlay height in samples
    BlockOverlayPlane(
            MutableSamplePlane basePlane,
            int originX,
            int originY,
            int overlayWidth,
            int overlayHeight
    ) {
        this.basePlane = Objects.requireNonNull(basePlane, "basePlane");
        if (originX < 0 || originX >= basePlane.width()) {
            throw new IllegalArgumentException("originX out of range: " + originX);
        }
        if (originY < 0 || originY >= basePlane.height()) {
            throw new IllegalArgumentException("originY out of range: " + originY);
        }
        if (overlayWidth <= 0) {
            throw new IllegalArgumentException("overlayWidth out of range: " + overlayWidth);
        }
        if (overlayHeight <= 0) {
            throw new IllegalArgumentException("overlayHeight out of range: " + overlayHeight);
        }
        this.originX = originX;
        this.originY = originY;
        this.overlayWidth = Math.min(overlayWidth, basePlane.width() - originX);
        this.overlayHeight = Math.min(overlayHeight, basePlane.height() - originY);
        this.maximumSampleValue = (1 << basePlane.bitDepth()) - 1;
        int sampleCount = Math.multiplyExact(this.overlayWidth, this.overlayHeight);
        this.samples = new short[sampleCount];
        this.writtenSamples = new boolean[sampleCount];
    }

    /// Returns the width of the containing base plane.
    ///
    /// @return the containing plane width in samples
    @Override
    public int width() {
        return basePlane.width();
    }

    /// Returns the height of the containing base plane.
    ///
    /// @return the containing plane height in samples
    @Override
    public int height() {
        return basePlane.height();
    }

    /// Returns the base plane's decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    @Override
    public int bitDepth() {
        return basePlane.bitDepth();
    }

    /// Returns an overlay sample when written, or reads through to the base plane otherwise.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the overlay or base-plane sample
    @Override
    public int sample(int x, int y) {
        int index = overlayIndex(x, y);
        if (index >= 0 && writtenSamples[index]) {
            return samples[index] & 0xFFFF;
        }
        return basePlane.sample(x, y);
    }

    /// Stores one clipped sample inside the writable overlay.
    ///
    /// @param x     the zero-based horizontal sample coordinate
    /// @param y     the zero-based vertical sample coordinate
    /// @param value the sample value
    @Override
    public void setSample(int x, int y, int value) {
        int index = overlayIndex(x, y);
        if (index < 0) {
            throw new IndexOutOfBoundsException("coordinate outside block overlay: " + x + ", " + y);
        }
        samples[index] = (short) Math.max(0, Math.min(value, maximumSampleValue));
        writtenSamples[index] = true;
    }

    /// Returns the compact storage index for one coordinate, or `-1` when it lies outside the overlay.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the compact storage index, or `-1`
    private int overlayIndex(int x, int y) {
        int localX = x - originX;
        int localY = y - originY;
        if (localX < 0 || localX >= overlayWidth || localY < 0 || localY >= overlayHeight) {
            return -1;
        }
        return localY * overlayWidth + localX;
    }
}
