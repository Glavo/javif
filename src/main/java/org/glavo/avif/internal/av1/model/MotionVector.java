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

/// One AV1 motion vector stored in quarter-pel units.
@NotNullByDefault
public final class MotionVector {
    /// The shared zero motion-vector instance.
    private static final MotionVector ZERO = new MotionVector(0, 0);

    /// The signed vertical component in quarter-pel units.
    private final int rowQuarterPel;

    /// The signed horizontal component in quarter-pel units.
    private final int columnQuarterPel;

    /// Creates one motion vector in quarter-pel units.
    ///
    /// @param rowQuarterPel the signed vertical component in quarter-pel units
    /// @param columnQuarterPel the signed horizontal component in quarter-pel units
    public MotionVector(int rowQuarterPel, int columnQuarterPel) {
        this.rowQuarterPel = rowQuarterPel;
        this.columnQuarterPel = columnQuarterPel;
    }

    /// Returns the shared zero motion-vector instance.
    ///
    /// @return the shared zero motion-vector instance
    public static MotionVector zero() {
        return ZERO;
    }

    /// Returns the signed vertical component in quarter-pel units.
    ///
    /// @return the signed vertical component in quarter-pel units
    public int rowQuarterPel() {
        return rowQuarterPel;
    }

    /// Returns the signed horizontal component in quarter-pel units.
    ///
    /// @return the signed horizontal component in quarter-pel units
    public int columnQuarterPel() {
        return columnQuarterPel;
    }

    /// Returns whether this motion vector equals the supplied object.
    ///
    /// @param obj the object to compare
    /// @return whether this motion vector equals the supplied object
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MotionVector other)) {
            return false;
        }
        return rowQuarterPel == other.rowQuarterPel && columnQuarterPel == other.columnQuarterPel;
    }

    /// Returns the hash code of this motion vector.
    ///
    /// @return the hash code of this motion vector
    @Override
    public int hashCode() {
        return rowQuarterPel * 31 + columnQuarterPel;
    }
}
