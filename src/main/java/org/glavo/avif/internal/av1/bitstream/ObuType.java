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
import org.jetbrains.annotations.Nullable;

/// AV1 OBU types defined by the bitstream specification.
@NotNullByDefault
public enum ObuType {
    /// Sequence header OBU.
    SEQUENCE_HEADER(1),
    /// Temporal delimiter OBU.
    TEMPORAL_DELIMITER(2),
    /// Frame header OBU.
    FRAME_HEADER(3),
    /// Tile group OBU.
    TILE_GROUP(4),
    /// Metadata OBU.
    METADATA(5),
    /// Combined frame OBU.
    FRAME(6),
    /// Redundant frame header OBU.
    REDUNDANT_FRAME_HEADER(7),
    /// Padding OBU.
    PADDING(15);

    /// The numeric OBU type identifier.
    private final int id;

    /// Creates an OBU type with a numeric identifier.
    ///
    /// @param id the numeric OBU type identifier
    ObuType(int id) {
        this.id = id;
    }

    /// Returns the numeric OBU type identifier.
    ///
    /// @return the numeric OBU type identifier
    public int id() {
        return id;
    }

    /// Maps a numeric OBU type identifier to an enum constant.
    ///
    /// @param id the numeric OBU type identifier
    /// @return the matching OBU type, or `null`
    public static @Nullable ObuType fromId(int id) {
        for (ObuType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
