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
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests for immutable gain-map association metadata.
@NotNullByDefault
final class AvifGainMapInfoTest {
    /// Verifies optional image descriptors and payload copying through `withXxx` methods.
    @Test
    void withMethodsComposeValidatedMetadata() {
        byte[] profile = new byte[]{1, 2, 3};
        AvifGainMapInfo info = baseInfo()
                .withToneMappedSize(8, 6)
                .withGainMapImage(4, 3, AvifBitDepth.TEN_BITS, Av1ChromaFormat.YUV420)
                .withToneMappedIccProfile(profile);
        profile[0] = 9;

        assertEquals(8, info.toneMappedWidth());
        assertEquals(6, info.toneMappedHeight());
        assertEquals(4, info.gainMapWidth());
        assertEquals(3, info.gainMapHeight());
        assertEquals(AvifBitDepth.TEN_BITS, info.gainMapBitDepth());
        assertEquals(Av1ChromaFormat.YUV420, info.gainMapChromaFormat());
        assertFalse(info.metadataSupported());
        ByteBuffer returnedProfile = info.toneMappedIccProfile();
        assertNotNull(returnedProfile);
        byte[] actual = new byte[returnedProfile.remaining()];
        returnedProfile.get(actual);
        assertArrayEquals(new byte[]{1, 2, 3}, actual);
    }

    /// Verifies that paired dimensions and AV1 image descriptors remain internally consistent.
    @Test
    void withMethodsRejectInconsistentImageState() {
        assertThrows(IllegalArgumentException.class, () -> baseInfo().withToneMappedSize(8, -1));
        assertThrows(IllegalArgumentException.class, () -> baseInfo().withGainMapImage(
                4, 3, AvifBitDepth.EIGHT_BITS, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AvifGainMapInfo(0, 2, 3, "tmap", "av01"));
    }

    /// Creates minimal gain-map association metadata.
    ///
    /// @return the created metadata
    private static AvifGainMapInfo baseInfo() {
        return new AvifGainMapInfo(1, 2, 3, "tmap", "av01");
    }
}
