// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for compact loop-restoration unit state.
@NotNullByDefault
final class RestorationUnitTest {
    /// Verifies that Wiener factories retain scalar values rather than caller-owned arrays.
    @Test
    void wienerFactoryDoesNotRetainInputArrays() {
        int[][] coefficients = {{1, 2, 3}, {4, 5, 6}};
        RestorationUnit unit = RestorationUnit.wiener(coefficients);

        coefficients[0][0] = 99;
        coefficients[1][2] = 99;

        assertEquals(1, unit.wienerCoefficient(0, 0));
        assertEquals(3, unit.wienerCoefficient(0, 2));
        assertEquals(4, unit.wienerCoefficient(1, 0));
        assertEquals(6, unit.wienerCoefficient(1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> unit.wienerCoefficient(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> unit.wienerCoefficient(0, 3));
    }

    /// Verifies that self-guided factories retain scalar values rather than caller-owned arrays.
    @Test
    void selfGuidedFactoryDoesNotRetainInputArray() {
        int[] coefficients = {7, 8};
        RestorationUnit unit = RestorationUnit.selfGuided(3, coefficients);

        coefficients[0] = 99;
        coefficients[1] = 99;

        assertEquals(3, unit.selfGuidedSet());
        assertEquals(7, unit.selfGuidedProjectionCoefficient(0));
        assertEquals(8, unit.selfGuidedProjectionCoefficient(1));
        assertThrows(IndexOutOfBoundsException.class, () -> unit.selfGuidedProjectionCoefficient(2));
    }
}
