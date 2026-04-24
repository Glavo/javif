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

/// The AV1 inter-intra prediction modes in bitstream order.
@NotNullByDefault
public enum InterIntraPredictionMode {
    /// DC inter-intra prediction.
    DC,
    /// Vertical inter-intra prediction.
    VERTICAL,
    /// Horizontal inter-intra prediction.
    HORIZONTAL,
    /// Smooth inter-intra prediction.
    SMOOTH;

    /// Maps one decoded bitstream symbol index back to an inter-intra prediction mode.
    ///
    /// @param symbolIndex the decoded bitstream symbol index
    /// @return the inter-intra prediction mode for the supplied symbol index
    public static InterIntraPredictionMode fromSymbolIndex(int symbolIndex) {
        InterIntraPredictionMode[] values = values();
        if (symbolIndex < 0 || symbolIndex >= values.length) {
            throw new IllegalArgumentException("symbolIndex out of range: " + symbolIndex);
        }
        return values[symbolIndex];
    }

    /// Returns the matching luma intra prediction mode used to build the secondary predictor.
    ///
    /// @return the matching luma intra prediction mode used to build the secondary predictor
    public LumaIntraPredictionMode toLumaPredictionMode() {
        return switch (this) {
            case DC -> LumaIntraPredictionMode.DC;
            case VERTICAL -> LumaIntraPredictionMode.VERTICAL;
            case HORIZONTAL -> LumaIntraPredictionMode.HORIZONTAL;
            case SMOOTH -> LumaIntraPredictionMode.SMOOTH;
        };
    }

    /// Returns the matching chroma intra prediction mode used to build the secondary predictor.
    ///
    /// @return the matching chroma intra prediction mode used to build the secondary predictor
    public UvIntraPredictionMode toUvPredictionMode() {
        return switch (this) {
            case DC -> UvIntraPredictionMode.DC;
            case VERTICAL -> UvIntraPredictionMode.VERTICAL;
            case HORIZONTAL -> UvIntraPredictionMode.HORIZONTAL;
            case SMOOTH -> UvIntraPredictionMode.SMOOTH;
        };
    }
}
