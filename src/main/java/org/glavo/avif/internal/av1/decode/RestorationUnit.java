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
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Decoded AV1 loop-restoration unit syntax for one image plane.
///
/// A unit can disable restoration locally, or carry either Wiener coefficients or self-guided
/// restoration projection coefficients. Frame-level `SWITCHABLE` restoration is resolved to one
/// of these concrete per-unit types before instances of this class are stored.
@NotNullByDefault
public final class RestorationUnit {
    /// A local unit with no loop restoration.
    private static final RestorationUnit NONE = new RestorationUnit(
            FrameHeader.RestorationType.NONE,
            new int[2][3],
            0,
            new int[2]
    );

    /// The concrete restoration type for this unit.
    private final FrameHeader.RestorationType type;

    /// The Wiener coefficients indexed by pass and coefficient.
    private final int @Unmodifiable [] @Unmodifiable [] wienerCoefficients;

    /// The self-guided restoration parameter set index.
    private final int selfGuidedSet;

    /// The self-guided projection coefficients.
    private final int @Unmodifiable [] selfGuidedProjectionCoefficients;

    /// Creates one decoded restoration unit.
    ///
    /// @param type the concrete restoration type for this unit
    /// @param wienerCoefficients the Wiener coefficients indexed by pass and coefficient
    /// @param selfGuidedSet the self-guided restoration parameter set index
    /// @param selfGuidedProjectionCoefficients the self-guided projection coefficients
    private RestorationUnit(
            FrameHeader.RestorationType type,
            int[][] wienerCoefficients,
            int selfGuidedSet,
            int[] selfGuidedProjectionCoefficients
    ) {
        this.type = Objects.requireNonNull(type, "type");
        if (type == FrameHeader.RestorationType.SWITCHABLE) {
            throw new IllegalArgumentException("Restoration units must carry a concrete restoration type");
        }
        this.wienerCoefficients = deepCopyWienerCoefficients(wienerCoefficients);
        if (selfGuidedSet < 0 || selfGuidedSet >= 16) {
            throw new IllegalArgumentException("selfGuidedSet out of range: " + selfGuidedSet);
        }
        this.selfGuidedSet = selfGuidedSet;
        this.selfGuidedProjectionCoefficients = Arrays.copyOf(
                Objects.requireNonNull(selfGuidedProjectionCoefficients, "selfGuidedProjectionCoefficients"),
                selfGuidedProjectionCoefficients.length
        );
        if (this.selfGuidedProjectionCoefficients.length != 2) {
            throw new IllegalArgumentException("selfGuidedProjectionCoefficients length must be 2");
        }
    }

    /// Returns a disabled restoration unit.
    ///
    /// @return a disabled restoration unit
    public static RestorationUnit none() {
        return NONE;
    }

    /// Creates one Wiener restoration unit.
    ///
    /// @param coefficients the two-pass Wiener coefficients
    /// @return one Wiener restoration unit
    public static RestorationUnit wiener(int[][] coefficients) {
        return new RestorationUnit(FrameHeader.RestorationType.WIENER, coefficients, 0, new int[2]);
    }

    /// Creates one self-guided restoration unit.
    ///
    /// @param set the self-guided restoration parameter set index
    /// @param projectionCoefficients the two projection coefficients
    /// @return one self-guided restoration unit
    public static RestorationUnit selfGuided(int set, int[] projectionCoefficients) {
        return new RestorationUnit(
                FrameHeader.RestorationType.SELF_GUIDED,
                new int[2][3],
                set,
                projectionCoefficients
        );
    }

    /// Returns the concrete restoration type for this unit.
    ///
    /// @return the concrete restoration type for this unit
    public FrameHeader.RestorationType type() {
        return type;
    }

    /// Returns the two-pass Wiener coefficients.
    ///
    /// @return the two-pass Wiener coefficients
    public int[][] wienerCoefficients() {
        return deepCopyWienerCoefficients(wienerCoefficients);
    }

    /// Returns the self-guided restoration parameter set index.
    ///
    /// @return the self-guided restoration parameter set index
    public int selfGuidedSet() {
        return selfGuidedSet;
    }

    /// Returns the self-guided projection coefficients.
    ///
    /// @return the self-guided projection coefficients
    public int[] selfGuidedProjectionCoefficients() {
        return Arrays.copyOf(selfGuidedProjectionCoefficients, selfGuidedProjectionCoefficients.length);
    }

    /// Copies one Wiener coefficient table.
    ///
    /// @param coefficients the coefficient table to copy
    /// @return a deep copy of the coefficient table
    private static int[][] deepCopyWienerCoefficients(int[][] coefficients) {
        int[][] source = Objects.requireNonNull(coefficients, "coefficients");
        if (source.length != 2) {
            throw new IllegalArgumentException("Wiener coefficient table must have two passes");
        }
        int[][] copy = new int[2][3];
        for (int pass = 0; pass < copy.length; pass++) {
            if (source[pass].length != 3) {
                throw new IllegalArgumentException("Each Wiener coefficient pass must contain three coefficients");
            }
            copy[pass] = Arrays.copyOf(source[pass], source[pass].length);
        }
        return copy;
    }
}
