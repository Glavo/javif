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

import org.glavo.avif.decode.Av1DecoderConfig;
import org.glavo.avif.decode.Av1ImageReader;
import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw AV1 decoding against selected Argon Streams reference outputs.
///
/// The corpus remains in its downloaded ZIP. Tests open only the selected entries and compare the
/// decoded pre-grain YUV planes with the MD5 digests distributed alongside the streams.
@Tag("argon-corpus")
@NotNullByDefault
final class ArgonAv1CorpusTest {
    /// System property that identifies the downloaded Argon Streams ZIP.
    private static final String ARCHIVE_PROPERTY = "org.glavo.avif.argon.archive";

    /// Root directory stored in the Argon Streams 2.1.1 ZIP.
    private static final String ARCHIVE_ROOT =
            "argon_coveragetool_av1_base_and_extended_profiles_v2.1/";

    /// Number of AV1 streams distributed in the pinned Argon Streams archive.
    private static final long EXPECTED_STREAM_COUNT = 3_921L;

    /// Number of regular low-overhead streams across all three AV1 profiles.
    private static final int EXPECTED_LOW_OVERHEAD_STREAM_COUNT = 37;

    /// Archive prefixes for regular low-overhead streams in all three profiles.
    private static final @Unmodifiable List<String> LOW_OVERHEAD_STREAM_PREFIXES = List.of(
            ARCHIVE_ROOT + "profile0_not_annexb/streams/",
            ARCHIVE_ROOT + "profile1_not_annexb/streams/",
            ARCHIVE_ROOT + "profile2_not_annexb/streams/"
    );

    /// Low-overhead streams currently known to match their reference output across all profiles.
    private static final @Unmodifiable List<CorpusCase> REFERENCE_CASES = List.of(
            new CorpusCase("profile0_not_annexb", "test12144.obu"),
            new CorpusCase("profile0_not_annexb", "test12153.obu"),
            new CorpusCase("profile0_not_annexb", "test12184.obu"),
            new CorpusCase("profile0_not_annexb", "test12259.obu"),
            new CorpusCase("profile1_not_annexb", "test8650.obu"),
            new CorpusCase("profile1_not_annexb", "test8660.obu"),
            new CorpusCase("profile1_not_annexb", "test8810.obu"),
            new CorpusCase("profile2_not_annexb", "test17154.obu")
    );

    /// Creates an Argon corpus test instance.
    ArgonAv1CorpusTest() {
    }

    /// Verifies that the configured ZIP has the complete pinned stream inventory.
    @Test
    void archiveContainsExpectedStreamInventory() throws IOException {
        Path archivePath = archivePath();
        assertTrue(Files.isRegularFile(archivePath), () -> "Missing Argon Streams archive: " + archivePath);

        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            List<String> entryNames = archive.stream().map(ZipEntry::getName).toList();
            long streamCount = entryNames.stream().filter(name -> name.endsWith(".obu")).count();
            long lowOverheadStreamCount = entryNames.stream()
                    .filter(name -> name.endsWith(".obu"))
                    .filter(name -> LOW_OVERHEAD_STREAM_PREFIXES.stream().anyMatch(name::startsWith))
                    .count();
            assertEquals(EXPECTED_STREAM_COUNT, streamCount);
            assertEquals(EXPECTED_LOW_OVERHEAD_STREAM_COUNT, lowOverheadStreamCount);
            assertNotNull(archive.getEntry(ARCHIVE_ROOT + "P8005-R-005h (Argon Streams AV1 User Manual).pdf"));
        }
    }

    /// Returns reference-output checks for the supported low-overhead streams in all AV1 profiles.
    ///
    /// @return the dynamic reference-output tests
    @TestFactory
    Stream<DynamicTest> supportedLowOverheadStreamsMatchReferenceYuvDigests() {
        return REFERENCE_CASES.stream()
                .map(testCase -> DynamicTest.dynamicTest(
                        testCase.category() + "/" + testCase.streamName(),
                        () -> assertReferenceDigest(testCase)
                ));
    }

    /// Decodes one selected stream and compares all visible pre-grain YUV planes with Argon's MD5.
    ///
    /// @param testCase the selected corpus stream
    private static void assertReferenceDigest(CorpusCase testCase) throws IOException, NoSuchAlgorithmException {
        try (ZipFile archive = new ZipFile(archivePath().toFile())) {
            String streamPath = ARCHIVE_ROOT + testCase.category() + "/streams/" + testCase.streamName();
            String referencePath = ARCHIVE_ROOT + testCase.category() + "/md5_no_film_grain/"
                    + testCase.baseName() + ".md5";
            ZipEntry streamEntry = requireEntry(archive, streamPath);
            String expectedDigest = readReferenceDigest(archive, requireEntry(archive, referencePath));
            MessageDigest actualDigest = MessageDigest.getInstance("MD5");
            Av1DecoderConfig config = Av1DecoderConfig.builder()
                    .applyFilmGrain(false)
                    .build();

            int frameCount = 0;
            try (Av1ImageReader reader = Av1ImageReader.open(
                    new BufferedInput.OfInputStream(archive.getInputStream(streamEntry)),
                    config
            )) {
                @Nullable DecodedFrame frame;
                while ((frame = reader.readFrame()) != null) {
                    @Nullable DecodedPlanes decodedPlanes = reader.lastPlanes();
                    assertNotNull(decodedPlanes, streamPath + " frame " + frameCount);
                    updateYuvDigest(actualDigest, Objects.requireNonNull(decodedPlanes));
                    frameCount++;
                }
            }

            assertTrue(frameCount > 0, () -> streamPath + " produced no visible frames");
            assertEquals(expectedDigest, HexFormat.of().formatHex(actualDigest.digest()), streamPath);
        }
    }

    /// Reads the reference digest from one Argon MD5 file.
    ///
    /// @param archive the open Argon archive
    /// @param digestEntry the MD5 entry to read
    /// @return the lowercase hexadecimal digest
    private static String readReferenceDigest(ZipFile archive, ZipEntry digestEntry) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                archive.getInputStream(digestEntry),
                StandardCharsets.US_ASCII
        ))) {
            @Nullable String line = reader.readLine();
            assertNotNull(line, digestEntry.getName());
            String digest = Objects.requireNonNull(line).strip().split("\\s+", 2)[0];
            assertEquals(32, digest.length(), digestEntry.getName());
            return digest.toLowerCase(Locale.ROOT);
        }
    }

    /// Updates one digest with all visible rows of the Y, U, and V planes.
    ///
    /// Samples wider than eight bits use the little-endian 16-bit representation emitted by
    /// `aomdec --rawvideo`.
    ///
    /// @param digest the digest to update
    /// @param planes the decoded pre-grain planes
    private static void updateYuvDigest(MessageDigest digest, DecodedPlanes planes) {
        updatePlaneDigest(digest, planes.lumaPlane(), planes.bitDepth());
        @Nullable DecodedPlane chromaUPlane = planes.chromaUPlane();
        @Nullable DecodedPlane chromaVPlane = planes.chromaVPlane();
        if (chromaUPlane != null && chromaVPlane != null) {
            updatePlaneDigest(digest, chromaUPlane, planes.bitDepth());
            updatePlaneDigest(digest, chromaVPlane, planes.bitDepth());
        }
    }

    /// Updates one digest with the visible samples of one decoded plane.
    ///
    /// @param digest the digest to update
    /// @param plane the decoded plane
    /// @param bitDepth the decoded bit depth
    private static void updatePlaneDigest(MessageDigest digest, DecodedPlane plane, int bitDepth) {
        boolean highBitDepth = bitDepth > 8;
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                int sample = plane.sample(x, y);
                digest.update((byte) sample);
                if (highBitDepth) {
                    digest.update((byte) (sample >>> 8));
                }
            }
        }
    }

    /// Returns the configured Argon archive path.
    ///
    /// @return the configured archive path
    private static Path archivePath() {
        return Path.of(Objects.requireNonNull(
                System.getProperty(ARCHIVE_PROPERTY),
                () -> "Missing system property: " + ARCHIVE_PROPERTY
        ));
    }

    /// Returns a required ZIP entry.
    ///
    /// @param archive the open Argon archive
    /// @param entryName the exact entry name
    /// @return the matching entry
    private static ZipEntry requireEntry(ZipFile archive, String entryName) {
        @Nullable ZipEntry entry = archive.getEntry(entryName);
        assertNotNull(entry, entryName);
        return Objects.requireNonNull(entry);
    }

    /// Identifies one selected Argon stream and its category.
    ///
    /// @param category the category directory in the archive
    /// @param streamName the stream file name
    @NotNullByDefault
    private record CorpusCase(String category, String streamName) {
        /// Returns the stream file name without its `.obu` suffix.
        ///
        /// @return the stream base name
        private String baseName() {
            return streamName.substring(0, streamName.length() - ".obu".length());
        }
    }
}
