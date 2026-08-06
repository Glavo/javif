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

import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration tests for AVIF Sample Transform derived images.
@NotNullByDefault
final class SampleTransformIntegrationTest {
    /// The libavif fixture containing a 16-bit Sample Transform over 12-bit and 8-bit AV1 inputs.
    private static final String WELD_SAMPLE_TRANSFORM = "libavif-test-data/weld_sato_12B_8B_q0.avif";
    /// SHA-256 of the tightly packed little-endian Y, U, and V samples decoded by pinned libavif.
    private static final String WELD_PLANES_SHA256 =
            "CD4F2B85CC0C8DE1664CAC9F66E8526580CB309934D92D24DEF0067C099995CB";

    /// Verifies the complete reconstructed planes and high-bit-depth rendered output.
    ///
    /// @throws IOException if the fixture cannot be read or decoded
    /// @throws NoSuchAlgorithmException if the required SHA-256 implementation is unavailable
    @Test
    void decodesSixteenBitSampleTransformExactly() throws IOException, NoSuchAlgorithmException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(WELD_SAMPLE_TRANSFORM))) {
            AvifImageInfo info = reader.info();
            assertEquals(1024, info.width());
            assertEquals(684, info.height());
            assertEquals(AvifBitDepth.SIXTEEN_BITS, info.bitDepth());
            assertEquals(Av1ChromaFormat.YUV444, info.chromaFormat());
            assertFalse(info.alphaPresent());
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());

            AvifPlanes planes = reader.readRawColorPlanes(0);
            assertEquals(AvifBitDepth.SIXTEEN_BITS, planes.bitDepth());
            assertEquals(Av1ChromaFormat.YUV444, planes.chromaFormat());
            assertEquals(1024, planes.codedWidth());
            assertEquals(684, planes.codedHeight());
            assertEquals(11519, planes.lumaPlane().sample(0, 0));
            AvifPlane chromaU = planes.chromaUPlane();
            AvifPlane chromaV = planes.chromaVPlane();
            assertNotNull(chromaU);
            assertNotNull(chromaV);
            assertEquals(32643, chromaU.sample(0, 0));
            assertEquals(32643, chromaV.sample(0, 0));
            assertEquals(WELD_PLANES_SHA256, hashPlanes(planes, chromaU, chromaV));

            AvifFrame frame = reader.readFrame();
            assertNotNull(frame);
            assertEquals(AvifBitDepth.SIXTEEN_BITS, frame.bitDepth());
            assertEquals(AvifRgbOutputMode.ARGB_16161616, frame.rgbOutputMode());
            assertTrue(frame.hasLongPixelBuffer());
            assertFalse(frame.hasIntPixelBuffer());
            assertEquals(1024 * 684, frame.longPixelBuffer().remaining());
            assertNull(reader.readFrame());
        }
    }

    /// Hashes tightly packed little-endian Y, U, and V samples.
    ///
    /// @param planes the decoded color planes
    /// @param chromaU the decoded U plane
    /// @param chromaV the decoded V plane
    /// @return the uppercase SHA-256 digest
    /// @throws NoSuchAlgorithmException if the required SHA-256 implementation is unavailable
    private static String hashPlanes(AvifPlanes planes, AvifPlane chromaU, AvifPlane chromaV)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateDigest(digest, planes.lumaPlane());
        updateDigest(digest, chromaU);
        updateDigest(digest, chromaV);
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    /// Adds one tightly packed plane to a little-endian sample digest.
    ///
    /// @param digest the digest to update
    /// @param plane the plane to hash
    private static void updateDigest(MessageDigest digest, AvifPlane plane) {
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                int sample = plane.sample(x, y);
                digest.update((byte) sample);
                digest.update((byte) (sample >>> 8));
            }
        }
    }
}
