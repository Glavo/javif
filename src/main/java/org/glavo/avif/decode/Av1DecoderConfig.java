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

import java.util.Objects;

/// Immutable decoder configuration for `Av1ImageReader`.
@NotNullByDefault
public final class Av1DecoderConfig {
    /// The default decoder configuration.
    public static final Av1DecoderConfig DEFAULT = builder().build();

    /// Whether decoded output should include film grain synthesis.
    private final boolean applyFilmGrain;
    /// Whether standard compliance issues should be treated as hard errors.
    private final boolean strictStdCompliance;
    /// Whether invisible frames should be exposed through the public API.
    private final boolean outputInvisibleFrames;
    /// Whether every selected spatial layer should be exposed as a separate output.
    private final boolean outputAllLayers;
    /// Whether frame payloads use the AV1 Large Scale Tile layout.
    private final boolean largeScaleTileMode;
    /// Which frame categories should be decoded.
    private final DecodeFrameType decodeFrameType;
    /// The selected AV1 operating point.
    private final int operatingPoint;
    /// The maximum decoded frame size in pixels, or `0` when unlimited.
    private final long frameSizeLimit;

    /// Creates a configuration from a validated builder.
    ///
    /// @param builder the validated builder state
    private Av1DecoderConfig(Builder builder) {
        this.applyFilmGrain = builder.applyFilmGrain;
        this.strictStdCompliance = builder.strictStdCompliance;
        this.outputInvisibleFrames = builder.outputInvisibleFrames;
        this.outputAllLayers = builder.outputAllLayers;
        this.largeScaleTileMode = builder.largeScaleTileMode;
        this.decodeFrameType = builder.decodeFrameType;
        this.operatingPoint = builder.operatingPoint;
        this.frameSizeLimit = builder.frameSizeLimit;
    }

    /// Creates a new mutable builder.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns whether decoded output should include film grain synthesis.
    ///
    /// @return whether film grain is enabled
    public boolean applyFilmGrain() {
        return applyFilmGrain;
    }

    /// Returns whether standard compliance issues should be treated as hard errors.
    ///
    /// @return whether strict compliance is enabled
    public boolean strictStdCompliance() {
        return strictStdCompliance;
    }

    /// Returns whether invisible frames should be exposed through the public API.
    ///
    /// @return whether invisible frames should be returned
    public boolean outputInvisibleFrames() {
        return outputInvisibleFrames;
    }

    /// Returns whether every selected spatial layer is exposed as a separate output.
    ///
    /// When disabled, only the last selected spatial-layer output in each temporal unit is
    /// returned, while earlier layers remain available for decoding dependencies.
    ///
    /// @return whether all selected spatial layers are returned
    public boolean outputAllLayers() {
        return outputAllLayers;
    }

    /// Returns whether frame payloads use the AV1 Large Scale Tile layout.
    ///
    /// This is an external decoder-mode selection and is not signaled by the bitstream. When
    /// enabled, leading decoded frames are retained as externally indexed anchors, the common
    /// camera frame is not presented, and tile-list OBUs produce the visible outputs.
    ///
    /// @return whether Large Scale Tile mode is enabled
    public boolean largeScaleTileMode() {
        return largeScaleTileMode;
    }

    /// Returns which frame categories should be decoded.
    ///
    /// @return the frame filtering mode
    public DecodeFrameType decodeFrameType() {
        return decodeFrameType;
    }

    /// Returns the selected AV1 operating point.
    ///
    /// @return the selected operating point
    public int operatingPoint() {
        return operatingPoint;
    }

    /// Returns the maximum decoded frame size in pixels.
    ///
    /// @return the frame size limit, or `0` when unlimited
    public long frameSizeLimit() {
        return frameSizeLimit;
    }

    /// Returns an equivalent configuration selecting the supplied operating point.
    ///
    /// This instance is returned when the requested value already matches its selection.
    ///
    /// @param value the operating-point index in `[0, 31]`
    /// @return this configuration or an equivalent configuration with the requested selection
    public Av1DecoderConfig withOperatingPoint(int value) {
        if (value == operatingPoint) {
            return this;
        }
        return builder()
                .applyFilmGrain(applyFilmGrain)
                .strictStdCompliance(strictStdCompliance)
                .outputInvisibleFrames(outputInvisibleFrames)
                .outputAllLayers(outputAllLayers)
                .largeScaleTileMode(largeScaleTileMode)
                .decodeFrameType(decodeFrameType)
                .operatingPoint(value)
                .frameSizeLimit(frameSizeLimit)
                .build();
    }

    /// Mutable builder for `Av1DecoderConfig`.
    @NotNullByDefault
    public static final class Builder {
        /// Whether decoded output should include film grain synthesis.
        private boolean applyFilmGrain = true;
        /// Whether standard compliance issues should be treated as hard errors.
        private boolean strictStdCompliance = false;
        /// Whether invisible frames should be exposed through the public API.
        private boolean outputInvisibleFrames = false;
        /// Whether every selected spatial layer should be exposed as a separate output.
        private boolean outputAllLayers = false;
        /// Whether frame payloads use the AV1 Large Scale Tile layout.
        private boolean largeScaleTileMode = false;
        /// Which frame categories should be decoded.
        private DecodeFrameType decodeFrameType = DecodeFrameType.ALL;
        /// The selected AV1 operating point.
        private int operatingPoint = 0;
        /// The maximum decoded frame size in pixels, or `0` when unlimited.
        private long frameSizeLimit = 0;

        /// Creates a builder with default settings.
        public Builder() {
        }

        /// Sets whether decoded output should include film grain synthesis.
        ///
        /// @param value whether film grain should be applied
        /// @return this builder
        public Builder applyFilmGrain(boolean value) {
            this.applyFilmGrain = value;
            return this;
        }

        /// Sets whether standard compliance issues should be treated as hard errors.
        ///
        /// @param value whether strict compliance should be enabled
        /// @return this builder
        public Builder strictStdCompliance(boolean value) {
            this.strictStdCompliance = value;
            return this;
        }

        /// Sets whether invisible frames should be exposed through the public API.
        ///
        /// @param value whether invisible frames should be returned
        /// @return this builder
        public Builder outputInvisibleFrames(boolean value) {
            this.outputInvisibleFrames = value;
            return this;
        }

        /// Sets whether every selected spatial layer is exposed as a separate output.
        ///
        /// When `false`, the reader returns only the last selected spatial-layer output in each
        /// temporal unit. All selected layers are still decoded for reference dependencies.
        ///
        /// @param value whether all selected spatial layers should be returned
        /// @return this builder
        public Builder outputAllLayers(boolean value) {
            this.outputAllLayers = value;
            return this;
        }

    /// Sets whether frame payloads use the AV1 Large Scale Tile layout.
    ///
    /// The caller must select this mode only for streams packaged for Large Scale Tile decoder
    /// operation. The mode determines how leading anchor frames and the common camera header are
    /// consumed before tile-list outputs are exposed.
        ///
        /// @param value whether Large Scale Tile mode should be enabled
        /// @return this builder
        public Builder largeScaleTileMode(boolean value) {
            this.largeScaleTileMode = value;
            return this;
        }

        /// Sets which frame categories should be decoded.
        ///
        /// @param value the frame filtering mode
        /// @return this builder
        public Builder decodeFrameType(DecodeFrameType value) {
            this.decodeFrameType = Objects.requireNonNull(value, "value");
            return this;
        }

        /// Sets the selected AV1 operating point.
        ///
        /// @param value the operating point, in the range `0..31`
        /// @return this builder
        public Builder operatingPoint(int value) {
            this.operatingPoint = value;
            return this;
        }

        /// Sets the maximum decoded frame size in pixels.
        ///
        /// @param value the frame size limit, or `0` when unlimited
        /// @return this builder
        public Builder frameSizeLimit(long value) {
            this.frameSizeLimit = value;
            return this;
        }

        /// Builds an immutable decoder configuration.
        ///
        /// @return the immutable decoder configuration
        public Av1DecoderConfig build() {
            if (operatingPoint < 0 || operatingPoint > 31) {
                throw new IllegalArgumentException("operatingPoint out of range: " + operatingPoint);
            }
            if (frameSizeLimit < 0) {
                throw new IllegalArgumentException("frameSizeLimit < 0: " + frameSizeLimit);
            }
            return new Av1DecoderConfig(this);
        }
    }
}
