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
package org.glavo.avif;

import org.glavo.avif.decode.Av1DecoderConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable configuration for `AvifImageReader`.
@NotNullByDefault
public final class AvifDecoderConfig {
    /// The default AVIF decoder configuration.
    public static final AvifDecoderConfig DEFAULT = builder().build();

    /// The underlying AV1 decoder configuration.
    private final Av1DecoderConfig av1DecoderConfig;
    /// The requested packed RGB output storage mode.
    private final AvifRgbOutputMode rgbOutputMode;

    /// Creates a configuration from a validated builder.
    ///
    /// @param builder the validated builder state
    private AvifDecoderConfig(Builder builder) {
        this.av1DecoderConfig = builder.av1DecoderConfig;
        this.rgbOutputMode = builder.rgbOutputMode;
    }

    /// Creates a mutable configuration builder.
    ///
    /// @return a mutable configuration builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the underlying AV1 decoder configuration.
    ///
    /// @return the underlying AV1 decoder configuration
    public Av1DecoderConfig av1DecoderConfig() {
        return av1DecoderConfig;
    }

    /// Returns the requested packed RGB output storage mode.
    ///
    /// @return the requested packed RGB output storage mode
    public AvifRgbOutputMode rgbOutputMode() {
        return rgbOutputMode;
    }

    /// Mutable builder for `AvifDecoderConfig`.
    @NotNullByDefault
    public static final class Builder {
        /// The underlying AV1 decoder configuration.
        private Av1DecoderConfig av1DecoderConfig = Av1DecoderConfig.DEFAULT;
        /// The requested packed RGB output storage mode.
        private AvifRgbOutputMode rgbOutputMode = AvifRgbOutputMode.AUTOMATIC;

        /// Creates a builder with default values.
        public Builder() {
        }

        /// Sets the underlying AV1 decoder configuration.
        ///
        /// @param value the underlying AV1 decoder configuration
        /// @return this builder
        public Builder av1DecoderConfig(Av1DecoderConfig value) {
            this.av1DecoderConfig = Objects.requireNonNull(value, "value");
            return this;
        }

        /// Sets the requested packed RGB output storage mode.
        ///
        /// `AUTOMATIC` preserves the legacy behavior: 8-bit inputs expose `IntBuffer` pixels first,
        /// while 10/12-bit inputs expose `LongBuffer` pixels first.
        ///
        /// @param value the requested packed RGB output storage mode
        /// @return this builder
        public Builder rgbOutputMode(AvifRgbOutputMode value) {
            this.rgbOutputMode = Objects.requireNonNull(value, "value");
            return this;
        }

        /// Builds an immutable AVIF decoder configuration.
        ///
        /// @return an immutable AVIF decoder configuration
        public AvifDecoderConfig build() {
            return new AvifDecoderConfig(this);
        }
    }
}
