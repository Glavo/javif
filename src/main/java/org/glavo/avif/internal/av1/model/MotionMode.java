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

/// Block-level AV1 inter motion compensation mode.
@NotNullByDefault
public enum MotionMode {
    /// Plain translational or global-motion prediction.
    SIMPLE,
    /// Overlapped block motion compensation using causal neighbor predictors.
    OBMC,
    /// Local warped motion compensation.
    LOCAL_WARPED;

    /// Maps one entropy-coded AV1 motion-mode symbol to the internal mode.
    ///
    /// @param symbol the zero-based motion-mode symbol
    /// @return the matching motion mode
    public static MotionMode fromSymbolIndex(int symbol) {
        return switch (symbol) {
            case 0 -> SIMPLE;
            case 1 -> OBMC;
            case 2 -> LOCAL_WARPED;
            default -> throw new IllegalArgumentException("Motion mode symbol out of range: " + symbol);
        };
    }
}
