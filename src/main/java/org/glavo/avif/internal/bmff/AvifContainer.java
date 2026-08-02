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
package org.glavo.avif.internal.bmff;

import org.glavo.avif.AvifImageInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Parsed AVIF container data required to decode still images and image sequences.
///
/// Still images expose normalized standalone or grid-derived [AvifImageSource] instances. Image
/// sequences instead expose one AV1 payload per sample. Display metadata, timing, and item
/// transforms are retained by [#info()].
@NotNullByDefault
public final class AvifContainer {
    /// The parsed image metadata.
    private final AvifImageInfo info;
    /// The Sample Transform selected as a preferred alternative, or `null`.
    private final @Nullable SampleTransform sampleTransform;
    /// The primary standalone or grid-derived image source, or `null` for a sequence.
    private final @Nullable AvifImageSource primarySource;
    /// The alpha auxiliary image source, or `null` when absent or sequence-based.
    private final @Nullable AvifImageSource alphaSource;
    /// The depth auxiliary image source, or `null` when absent or sequence-based.
    private final @Nullable AvifImageSource depthSource;
    /// The gain-map image source, or `null` when absent or descriptor-only.
    private final @Nullable AvifImageSource gainMapSource;
    /// The AV1 OBU payloads for sequence color samples, or `null` for a still image.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] samplePayloads;
    /// The AV1 OBU payloads for sequence alpha samples, or `null` when absent.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] sequenceAlphaSamplePayloads;
    /// The AV1 OBU payloads for sequence depth samples, or `null` when absent.
    private final @Unmodifiable ByteBuffer @Nullable @Unmodifiable [] sequenceDepthSamplePayloads;

    /// Creates parsed still-image container data from normalized image sources.
    ///
    /// @param info the parsed still-image metadata
    /// @param primarySource the primary standalone or grid-derived image source
    /// @param alphaSource the alpha auxiliary image source, or `null`
    /// @param depthSource the depth auxiliary image source, or `null`
    /// @param gainMapSource the gain-map image source, or `null`
    /// @param sampleTransform the preferred Sample Transform, or `null`
    public AvifContainer(
            AvifImageInfo info,
            AvifImageSource primarySource,
            @Nullable AvifImageSource alphaSource,
            @Nullable AvifImageSource depthSource,
            @Nullable AvifImageSource gainMapSource,
            @Nullable SampleTransform sampleTransform
    ) {
        this.info = Objects.requireNonNull(info, "info");
        if (info.animated()) {
            throw new IllegalArgumentException("Still-image container metadata must not be animated");
        }
        this.sampleTransform = sampleTransform;
        this.primarySource = Objects.requireNonNull(primarySource, "primarySource");
        this.alphaSource = alphaSource;
        this.depthSource = depthSource;
        this.gainMapSource = gainMapSource;
        this.samplePayloads = null;
        this.sequenceAlphaSamplePayloads = null;
        this.sequenceDepthSamplePayloads = null;
    }

    /// Creates parsed image-sequence container data.
    ///
    /// @param info the parsed image-sequence metadata
    /// @param samplePayloads the AV1 OBU payloads for color samples in presentation order
    /// @param sequenceAlphaSamplePayloads the AV1 OBU payloads for alpha samples, or `null`
    /// @param sequenceDepthSamplePayloads the AV1 OBU payloads for depth samples, or `null`
    public AvifContainer(
            AvifImageInfo info,
            byte @Unmodifiable [] @Unmodifiable [] samplePayloads,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] sequenceAlphaSamplePayloads,
            byte @Unmodifiable [] @Nullable @Unmodifiable [] sequenceDepthSamplePayloads
    ) {
        this.info = Objects.requireNonNull(info, "info");
        if (!info.animated()) {
            throw new IllegalArgumentException("Image-sequence container metadata must be animated");
        }
        Objects.requireNonNull(samplePayloads, "samplePayloads");
        if (samplePayloads.length != info.frameCount()) {
            throw new IllegalArgumentException("samplePayloads length must match the advertised frame count");
        }
        if (sequenceAlphaSamplePayloads != null && sequenceAlphaSamplePayloads.length != samplePayloads.length) {
            throw new IllegalArgumentException("sequenceAlphaSamplePayloads length must match samplePayloads length");
        }
        if (sequenceDepthSamplePayloads != null && sequenceDepthSamplePayloads.length != samplePayloads.length) {
            throw new IllegalArgumentException("sequenceDepthSamplePayloads length must match samplePayloads length");
        }
        this.sampleTransform = null;
        this.primarySource = null;
        this.alphaSource = null;
        this.depthSource = null;
        this.gainMapSource = null;
        this.samplePayloads = immutablePayloads(samplePayloads);
        this.sequenceAlphaSamplePayloads = sequenceAlphaSamplePayloads != null
                ? immutablePayloads(sequenceAlphaSamplePayloads)
                : null;
        this.sequenceDepthSamplePayloads = sequenceDepthSamplePayloads != null
                ? immutablePayloads(sequenceDepthSamplePayloads)
                : null;
    }

    /// Returns the parsed image metadata.
    ///
    /// @return the parsed image metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns the preferred Sample Transform selected for this still image.
    ///
    /// @return the Sample Transform, or `null` when no preferred `sato` alternative is present
    public @Nullable SampleTransform sampleTransform() {
        return sampleTransform;
    }

    /// Returns the primary standalone or grid-derived image source.
    ///
    /// @return the primary image source, or `null` for a sequence
    public @Nullable AvifImageSource primarySource() {
        return primarySource;
    }

    /// Returns the alpha auxiliary image source.
    ///
    /// @return the alpha image source, or `null`
    public @Nullable AvifImageSource alphaSource() {
        return alphaSource;
    }

    /// Returns the depth auxiliary image source.
    ///
    /// @return the depth image source, or `null`
    public @Nullable AvifImageSource depthSource() {
        return depthSource;
    }

    /// Returns the gain-map image source.
    ///
    /// @return the gain-map image source, or `null`
    public @Nullable AvifImageSource gainMapSource() {
        return gainMapSource;
    }

    /// Returns whether this container represents an AVIS image sequence.
    ///
    /// @return whether sequence sample payloads are present
    public boolean isSequence() {
        return samplePayloads != null;
    }

    /// Returns the AV1 OBU payloads for color samples in presentation order.
    ///
    /// @return read-only payload views, or `null` for a still image
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] samplePayloads() {
        return samplePayloads != null ? payloadViews(samplePayloads) : null;
    }

    /// Returns the AV1 OBU payloads for alpha samples in presentation order.
    ///
    /// @return read-only payload views, or `null` when no sequence alpha track is present
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] sequenceAlphaSamplePayloads() {
        return sequenceAlphaSamplePayloads != null ? payloadViews(sequenceAlphaSamplePayloads) : null;
    }

    /// Returns the AV1 OBU payloads for depth samples in presentation order.
    ///
    /// @return read-only payload views, or `null` when no sequence depth track is present
    public @UnmodifiableView ByteBuffer @Nullable @Unmodifiable [] sequenceDepthSamplePayloads() {
        return sequenceDepthSamplePayloads != null ? payloadViews(sequenceDepthSamplePayloads) : null;
    }

    /// Copies payload byte arrays into immutable little-endian buffers.
    ///
    /// @param payloads the source payload arrays
    /// @return immutable payload buffers
    private static @Unmodifiable ByteBuffer @Unmodifiable [] immutablePayloads(
            byte @Unmodifiable [] @Unmodifiable [] payloads
    ) {
        ByteBuffer[] result = new ByteBuffer[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            byte[] payload = Objects.requireNonNull(payloads[i], "payloads[" + i + "]");
            result[i] = ByteBuffer.wrap(Arrays.copyOf(payload, payload.length))
                    .asReadOnlyBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
        return result;
    }

    /// Returns read-only views over stored payloads.
    ///
    /// @param payloads the stored payload buffers
    /// @return independent little-endian payload views
    private static @UnmodifiableView ByteBuffer @Unmodifiable [] payloadViews(
            @Unmodifiable ByteBuffer @Unmodifiable [] payloads
    ) {
        ByteBuffer[] result = new ByteBuffer[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            result[i] = payloads[i].slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        return result;
    }
}
