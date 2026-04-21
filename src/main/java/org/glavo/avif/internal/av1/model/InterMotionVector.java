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

import java.util.Objects;

/// One inter motion-vector state that may already be final or still provisional.
@NotNullByDefault
public final class InterMotionVector {
    /// The underlying motion-vector value in quarter-pel units.
    private final MotionVector vector;

    /// Whether the vector is final for the current block instead of only a provisional predictor.
    private final boolean resolved;

    /// Creates one inter motion-vector state.
    ///
    /// @param vector the underlying motion-vector value in quarter-pel units
    /// @param resolved whether the vector is final for the current block
    public InterMotionVector(MotionVector vector, boolean resolved) {
        this.vector = Objects.requireNonNull(vector, "vector");
        this.resolved = resolved;
    }

    /// Creates one final inter motion-vector state.
    ///
    /// @param vector the underlying motion-vector value in quarter-pel units
    /// @return one final inter motion-vector state
    public static InterMotionVector resolved(MotionVector vector) {
        return new InterMotionVector(vector, true);
    }

    /// Creates one provisional inter motion-vector state.
    ///
    /// @param vector the underlying motion-vector value in quarter-pel units
    /// @return one provisional inter motion-vector state
    public static InterMotionVector predicted(MotionVector vector) {
        return new InterMotionVector(vector, false);
    }

    /// Returns the underlying motion-vector value in quarter-pel units.
    ///
    /// @return the underlying motion-vector value in quarter-pel units
    public MotionVector vector() {
        return vector;
    }

    /// Returns whether the vector is final for the current block.
    ///
    /// @return whether the vector is final for the current block
    public boolean resolved() {
        return resolved;
    }

    /// Returns this motion vector downgraded to a provisional predictor.
    ///
    /// @return this motion vector downgraded to a provisional predictor
    public InterMotionVector asPredicted() {
        return resolved ? new InterMotionVector(vector, false) : this;
    }

    /// Returns whether this inter motion-vector state equals the supplied object.
    ///
    /// @param obj the object to compare
    /// @return whether this inter motion-vector state equals the supplied object
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof InterMotionVector other)) {
            return false;
        }
        return resolved == other.resolved && vector.equals(other.vector);
    }

    /// Returns the hash code of this inter motion-vector state.
    ///
    /// @return the hash code of this inter motion-vector state
    @Override
    public int hashCode() {
        return vector.hashCode() * 31 + (resolved ? 1 : 0);
    }
}
