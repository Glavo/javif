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
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for mutable reconstruction-plane storage.
@NotNullByDefault
final class MutablePlaneBufferTest {
    /// Verifies that mutable plane storage clips writes, serves fallbacks, and snapshots immutably.
    @Test
    void mutablePlaneBufferClipsWritesAndSnapshotsIntoDecodedPlane() {
        MutablePlaneBuffer plane = new MutablePlaneBuffer(2, 2, 8);

        plane.setSample(0, 0, -5);
        plane.setSample(1, 0, 999);
        plane.setSample(0, 1, 42);
        plane.setSample(1, 1, 13);

        assertEquals(0, plane.sample(0, 0));
        assertEquals(255, plane.sample(1, 0));
        assertEquals(42, plane.sample(0, 1));
        assertEquals(77, plane.sampleOrFallback(-1, 0, 77));
        assertEquals(77, plane.sampleOrFallback(0, 3, 77));

        DecodedPlane snapshot = plane.toDecodedPlane();
        plane.setSample(0, 1, 1);

        assertEquals(2, snapshot.width());
        assertEquals(2, snapshot.height());
        assertEquals(42, snapshot.sample(0, 1));
        assertEquals(13, snapshot.sample(1, 1));
    }
}
