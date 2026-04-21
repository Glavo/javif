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
package org.glavo.avif.internal.av1.entropy;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/// Tests for `CdfContext`.
@NotNullByDefault
final class CdfContextTest {
    /// Verifies that the default context matches the transformed `dav1d` tables for supported syntax elements.
    @Test
    void createsDefaultContextFromDav1dTables() {
        CdfContext context = CdfContext.createDefault();

        assertArrayEquals(new int[]{1097, 0}, context.mutableSkipCdf(0));
        assertArrayEquals(new int[]{16106, 0}, context.mutableIntraCdf(1));
        assertArrayEquals(new int[]{2237, 0}, context.mutableIntrabcCdf());

        assertEquals(13, context.mutableYModeCdf(0).length);
        assertEquals(9967, context.mutableYModeCdf(0)[0]);
        assertEquals(2419, context.mutableYModeCdf(0)[11]);

        assertEquals(13, context.mutableKeyFrameYModeCdf(0, 0).length);
        assertEquals(17180, context.mutableKeyFrameYModeCdf(0, 0)[0]);
        assertEquals(2302, context.mutableKeyFrameYModeCdf(0, 0)[11]);

        assertEquals(13, context.mutableUvModeCdf(false, 0).length);
        assertEquals(10137, context.mutableUvModeCdf(false, 0)[0]);
        assertEquals(14, context.mutableUvModeCdf(true, 12).length);
        assertEquals(3720, context.mutableUvModeCdf(true, 12)[12]);

        assertEquals(8, context.mutablePartitionCdf(0, 0).length);
        assertEquals(4869, context.mutablePartitionCdf(0, 0)[0]);
        assertEquals(10, context.mutablePartitionCdf(1, 0).length);
        assertEquals(12631, context.mutablePartitionCdf(1, 0)[0]);
        assertEquals(4, context.mutablePartitionCdf(4, 3).length);
        assertEquals(22872, context.mutablePartitionCdf(4, 3)[0]);
    }

    /// Verifies that copying the context deep-copies all mutable tables.
    @Test
    void copyDeepCopiesMutableTables() {
        CdfContext original = CdfContext.createDefault();
        CdfContext copy = original.copy();

        assertNotSame(original.mutableSkipCdf(0), copy.mutableSkipCdf(0));
        assertNotSame(original.mutableUvModeCdf(true, 0), copy.mutableUvModeCdf(true, 0));
        assertNotSame(original.mutablePartitionCdf(0, 0), copy.mutablePartitionCdf(0, 0));
        assertNotSame(original.mutableKeyFrameYModeCdf(0, 0), copy.mutableKeyFrameYModeCdf(0, 0));

        copy.mutableSkipCdf(0)[0] = 77;
        copy.mutableUvModeCdf(true, 0)[0] = 88;
        copy.mutablePartitionCdf(0, 0)[0] = 99;
        copy.mutableKeyFrameYModeCdf(0, 0)[0] = 111;

        assertEquals(1097, original.mutableSkipCdf(0)[0]);
        assertEquals(22361, original.mutableUvModeCdf(true, 0)[0]);
        assertEquals(4869, original.mutablePartitionCdf(0, 0)[0]);
        assertEquals(17180, original.mutableKeyFrameYModeCdf(0, 0)[0]);
    }
}
