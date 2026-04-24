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
package org.glavo.avif.internal.av1.model;

import org.jetbrains.annotations.NotNullByDefault;

/// The AV1 compound prediction blend types stored by the block syntax.
@NotNullByDefault
public enum CompoundPredictionType {
    /// Joint-distance weighted compound averaging.
    WEIGHTED_AVERAGE(1),
    /// Simple compound averaging.
    AVERAGE(2),
    /// Difference-derived segment mask compound blending.
    SEGMENT(3),
    /// Wedge-mask compound blending.
    WEDGE(4);

    /// The AV1 neighbor-context value corresponding to this compound prediction type.
    private final int contextValue;

    /// Creates one compound prediction type.
    ///
    /// @param contextValue the AV1 neighbor-context value corresponding to this type
    CompoundPredictionType(int contextValue) {
        this.contextValue = contextValue;
    }

    /// Returns the AV1 neighbor-context value corresponding to this compound prediction type.
    ///
    /// @return the AV1 neighbor-context value corresponding to this compound prediction type
    public int contextValue() {
        return contextValue;
    }
}
