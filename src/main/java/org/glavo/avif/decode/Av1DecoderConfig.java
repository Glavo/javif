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
    /// Which frame categories should be decoded.
    private final DecodeFrameType decodeFrameType;
    /// The selected AV1 operating point.
    private final int operatingPoint;
    /// Whether all spatial layers should be exposed when available.
    private final boolean allLayers;
    /// The maximum decoded frame size in pixels, or `0` when unlimited.
    private final long frameSizeLimit;
    /// The requested decoder worker count.
    private final int threadCount;

    /// Creates a configuration from a validated builder.
    ///
    /// @param builder the validated builder state
    private Av1DecoderConfig(Builder builder) {
        this.applyFilmGrain = builder.applyFilmGrain;
        this.strictStdCompliance = builder.strictStdCompliance;
        this.outputInvisibleFrames = builder.outputInvisibleFrames;
        this.decodeFrameType = builder.decodeFrameType;
        this.operatingPoint = builder.operatingPoint;
        this.allLayers = builder.allLayers;
        this.frameSizeLimit = builder.frameSizeLimit;
        this.threadCount = builder.threadCount;
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

    /// Returns whether all spatial layers should be exposed when available.
    ///
    /// @return whether all layers are enabled
    public boolean allLayers() {
        return allLayers;
    }

    /// Returns the maximum decoded frame size in pixels.
    ///
    /// @return the frame size limit, or `0` when unlimited
    public long frameSizeLimit() {
        return frameSizeLimit;
    }

    /// Returns the requested decoder worker count.
    ///
    /// @return the requested worker count
    public int threadCount() {
        return threadCount;
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
        /// Which frame categories should be decoded.
        private DecodeFrameType decodeFrameType = DecodeFrameType.ALL;
        /// The selected AV1 operating point.
        private int operatingPoint = 0;
        /// Whether all spatial layers should be exposed when available.
        private boolean allLayers = true;
        /// The maximum decoded frame size in pixels, or `0` when unlimited.
        private long frameSizeLimit = 0;
        /// The requested decoder worker count.
        private int threadCount = 1;

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

        /// Sets whether all spatial layers should be exposed when available.
        ///
        /// @param value whether all spatial layers should be returned
        /// @return this builder
        public Builder allLayers(boolean value) {
            this.allLayers = value;
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

        /// Sets the requested decoder worker count.
        ///
        /// @param value the requested worker count
        /// @return this builder
        public Builder threadCount(int value) {
            this.threadCount = value;
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
            if (threadCount <= 0) {
                throw new IllegalArgumentException("threadCount <= 0: " + threadCount);
            }
            return new Av1DecoderConfig(this);
        }
    }
}
