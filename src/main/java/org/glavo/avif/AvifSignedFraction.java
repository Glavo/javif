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

/// A signed rational value carried by AVIF gain-map metadata.
///
/// @param numerator the signed 32-bit numerator
/// @param denominator the unsigned 32-bit denominator
@NotNullByDefault
public record AvifSignedFraction(int numerator, long denominator) {
    /// The maximum unsigned 32-bit value representable in a Java `long`.
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    /// Creates a signed rational value.
    ///
    /// @param numerator the signed 32-bit numerator
    /// @param denominator the unsigned 32-bit denominator
    public AvifSignedFraction {
        if (denominator <= 0 || denominator > UINT32_MAX) {
            throw new IllegalArgumentException("denominator must be in 1..2^32-1: " + denominator);
        }
    }

    /// Returns the signed numerator.
    ///
    /// @return the signed numerator
    @Override
    public int numerator() {
        return numerator;
    }

    /// Returns the unsigned denominator.
    ///
    /// @return the unsigned denominator
    @Override
    public long denominator() {
        return denominator;
    }

    /// Returns this rational value as a finite `double`.
    ///
    /// @return this rational value as a finite `double`
    public double toDouble() {
        return (double) numerator / (double) denominator;
    }
}
