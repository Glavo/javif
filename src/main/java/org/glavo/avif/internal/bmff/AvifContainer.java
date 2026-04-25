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

import java.util.Arrays;
import java.util.Objects;

/// Parsed AVIF container data needed by the current reader.
@NotNullByDefault
public final class AvifContainer {
    /// The parsed image metadata.
    private final AvifImageInfo info;
    /// The primary image AV1 OBU payload.
    private final byte @Unmodifiable [] primaryItemPayload;
    /// The alpha auxiliary image AV1 OBU payload, or `null` when absent.
    private final byte @Nullable @Unmodifiable [] alphaItemPayload;

    /// Creates parsed AVIF container data without an alpha image.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    public AvifContainer(AvifImageInfo info, byte[] primaryItemPayload) {
        this(info, primaryItemPayload, null);
    }

    /// Creates parsed AVIF container data with an optional alpha image.
    ///
    /// @param info the parsed image metadata
    /// @param primaryItemPayload the primary image AV1 OBU payload
    /// @param alphaItemPayload the alpha auxiliary image AV1 OBU payload, or `null`
    public AvifContainer(AvifImageInfo info, byte[] primaryItemPayload, byte @Nullable [] alphaItemPayload) {
        this.info = Objects.requireNonNull(info, "info");
        this.primaryItemPayload = Arrays.copyOf(
                Objects.requireNonNull(primaryItemPayload, "primaryItemPayload"),
                primaryItemPayload.length
        );
        this.alphaItemPayload = alphaItemPayload != null
                ? Arrays.copyOf(alphaItemPayload, alphaItemPayload.length)
                : null;
    }

    /// Returns the parsed image metadata.
    ///
    /// @return the parsed image metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns the primary image AV1 OBU payload.
    ///
    /// @return the primary image AV1 OBU payload
    public byte[] primaryItemPayload() {
        return Arrays.copyOf(primaryItemPayload, primaryItemPayload.length);
    }

    /// Returns the alpha auxiliary image AV1 OBU payload.
    ///
    /// @return the alpha auxiliary image AV1 OBU payload, or `null`
    public byte @Nullable [] alphaItemPayload() {
        if (alphaItemPayload == null) {
            return null;
        }
        return Arrays.copyOf(alphaItemPayload, alphaItemPayload.length);
    }
}
