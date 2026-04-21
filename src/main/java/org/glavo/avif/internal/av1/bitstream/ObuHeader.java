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
package org.glavo.avif.internal.av1.bitstream;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Parsed AV1 OBU header metadata.
@NotNullByDefault
public final class ObuHeader {
    /// The parsed OBU type.
    private final ObuType type;
    /// Whether the OBU carries an extension header.
    private final boolean extensionFlag;
    /// Whether the OBU carries a size field.
    private final boolean hasSizeField;
    /// The temporal layer identifier.
    private final int temporalId;
    /// The spatial layer identifier.
    private final int spatialId;

    /// Creates a parsed OBU header.
    ///
    /// @param type the OBU type
    /// @param extensionFlag whether the OBU carries an extension header
    /// @param hasSizeField whether the OBU carries a size field
    /// @param temporalId the temporal layer identifier
    /// @param spatialId the spatial layer identifier
    public ObuHeader(
            ObuType type,
            boolean extensionFlag,
            boolean hasSizeField,
            int temporalId,
            int spatialId
    ) {
        if (temporalId < 0 || temporalId > 7) {
            throw new IllegalArgumentException("temporalId out of range: " + temporalId);
        }
        if (spatialId < 0 || spatialId > 3) {
            throw new IllegalArgumentException("spatialId out of range: " + spatialId);
        }
        if (!extensionFlag && (temporalId != 0 || spatialId != 0)) {
            throw new IllegalArgumentException("temporalId and spatialId must be zero when extensionFlag is false");
        }
        this.type = Objects.requireNonNull(type, "type");
        this.extensionFlag = extensionFlag;
        this.hasSizeField = hasSizeField;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
    }

    /// Returns the OBU type.
    ///
    /// @return the OBU type
    public ObuType type() {
        return type;
    }

    /// Returns whether the OBU carries an extension header.
    ///
    /// @return whether the OBU carries an extension header
    public boolean extensionFlag() {
        return extensionFlag;
    }

    /// Returns whether the OBU carries a size field.
    ///
    /// @return whether the OBU carries a size field
    public boolean hasSizeField() {
        return hasSizeField;
    }

    /// Returns the temporal layer identifier.
    ///
    /// @return the temporal layer identifier
    public int temporalId() {
        return temporalId;
    }

    /// Returns the spatial layer identifier.
    ///
    /// @return the spatial layer identifier
    public int spatialId() {
        return spatialId;
    }
}
