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
package org.glavo.avif.internal.bmff;

import org.glavo.avif.internal.io.BufferedInput;
import org.glavo.avif.internal.io.RandomAccessDataSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests logical AV1 payload ranges and external sample boundaries.
@NotNullByDefault
final class AvifPayloadTest {
    /// Verifies that non-contiguous extents are exposed as one logical payload.
    ///
    /// @throws IOException if the payload cannot be read
    @Test
    void readsMultipleExtentsWithoutChangingLogicalOrder() throws IOException {
        RandomAccessDataSource source = RandomAccessDataSource.ofOwnedBytes(
                new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}
        );
        AvifPayload payload = AvifPayload.ofRanges(source, new long[]{1, 6}, new int[]{2, 3});

        assertEquals(5, payload.length());
        assertArrayEquals(new byte[]{1, 2, 6, 7, 8}, payload.readBytes());
        try (BufferedInput input = payload.openInput()) {
            assertEquals(5L, input.currentUnitRemaining());
            assertArrayEquals(new byte[]{1, 2, 6, 7, 8}, input.readByteArray(5));
            assertEquals(0L, input.currentUnitRemaining());
        }
    }

    /// Verifies that concatenated samples preserve boundaries through reads and skips.
    ///
    /// @throws IOException if the payload input cannot be read
    @Test
    void preservesPayloadUnitBoundaries() throws IOException {
        RandomAccessDataSource source = RandomAccessDataSource.ofOwnedBytes(
                new byte[]{10, 11, 12, 13, 14, 15, 16}
        );
        AvifPayload first = AvifPayload.ofRanges(source, new long[]{0}, new int[]{3});
        AvifPayload second = AvifPayload.ofRanges(source, new long[]{4}, new int[]{3});

        try (BufferedInput input = AvifPayload.openInput(new AvifPayload[]{first, second})) {
            assertEquals(3L, input.currentUnitRemaining());
            assertEquals(10, input.readUnsignedByte());
            assertEquals(2L, input.currentUnitRemaining());
            input.skip(2);
            assertEquals(3L, input.currentUnitRemaining());
            assertArrayEquals(new byte[]{14, 15, 16}, input.readByteArray(3));
            assertEquals(0L, input.currentUnitRemaining());
        }
    }
}
