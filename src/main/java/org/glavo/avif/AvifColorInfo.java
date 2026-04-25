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

import org.jetbrains.annotations.NotNullByDefault;

/// AVIF `nclx` color information from a `colr` item property.
@NotNullByDefault
public final class AvifColorInfo {
    /// The CICP color primaries value.
    private final int colorPrimaries;
    /// The CICP transfer characteristics value.
    private final int transferCharacteristics;
    /// The CICP matrix coefficients value.
    private final int matrixCoefficients;
    /// Whether samples are full range.
    private final boolean fullRange;

    /// Creates color information.
    ///
    /// @param colorPrimaries the CICP color primaries value
    /// @param transferCharacteristics the CICP transfer characteristics value
    /// @param matrixCoefficients the CICP matrix coefficients value
    /// @param fullRange whether samples are full range
    public AvifColorInfo(int colorPrimaries, int transferCharacteristics, int matrixCoefficients, boolean fullRange) {
        this.colorPrimaries = colorPrimaries;
        this.transferCharacteristics = transferCharacteristics;
        this.matrixCoefficients = matrixCoefficients;
        this.fullRange = fullRange;
    }

    /// Returns the CICP color primaries value.
    ///
    /// @return the CICP color primaries value
    public int colorPrimaries() {
        return colorPrimaries;
    }

    /// Returns the CICP transfer characteristics value.
    ///
    /// @return the CICP transfer characteristics value
    public int transferCharacteristics() {
        return transferCharacteristics;
    }

    /// Returns the CICP matrix coefficients value.
    ///
    /// @return the CICP matrix coefficients value
    public int matrixCoefficients() {
        return matrixCoefficients;
    }

    /// Returns whether samples are full range.
    ///
    /// @return whether samples are full range
    public boolean fullRange() {
        return fullRange;
    }
}
