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
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies raw low-overhead and Annex B AV1 decoding against selected Argon reference outputs.
///
/// The corpus remains in its downloaded ZIP. Tests open only the selected entries and compare the
/// decoded pre-grain YUV planes with the MD5 digests distributed alongside the streams.
@Tag("argon-corpus")
@NotNullByDefault
final class ArgonAv1CorpusTest {
    /// System property that identifies the downloaded Argon Streams ZIP.
    private static final String ARCHIVE_PROPERTY = "org.glavo.avif.argon.archive";

    /// Optional system property that selects one `category/stream.obu` case, one `category/all`
    /// group, or `all` for diagnosis.
    private static final String CASE_PROPERTY = "org.glavo.avif.argon.case";

    /// Optional system property that selects one one-based deterministic test shard as `index/count`.
    private static final String SHARD_PROPERTY = "org.glavo.avif.argon.shard";

    /// Optional system property that enables per-frame digest diagnostics on corpus failures.
    private static final String TRACE_FRAMES_PROPERTY = "org.glavo.avif.argon.traceFrames";

    /// Root directory stored in the Argon Streams 2.1.1 ZIP.
    private static final String ARCHIVE_ROOT =
            "argon_coveragetool_av1_base_and_extended_profiles_v2.1/";

    /// Number of AV1 streams distributed in the pinned Argon Streams archive.
    private static final long EXPECTED_STREAM_COUNT = 3_921L;

    /// Number of gated low-overhead and Annex B core streams across all three AV1 profiles.
    private static final int EXPECTED_REFERENCE_STREAM_COUNT = 2_756;

    /// Archive prefixes for gated low-overhead and Annex B core streams in all profiles.
    private static final @Unmodifiable List<String> REFERENCE_STREAM_PREFIXES = List.of(
            ARCHIVE_ROOT + "profile0_not_annexb/streams/",
            ARCHIVE_ROOT + "profile0_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile0_core/streams/",
            ARCHIVE_ROOT + "profile0_core_special/streams/",
            ARCHIVE_ROOT + "profile1_not_annexb/streams/",
            ARCHIVE_ROOT + "profile1_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile1_core/streams/",
            ARCHIVE_ROOT + "profile1_core_special/streams/",
            ARCHIVE_ROOT + "profile2_not_annexb/streams/",
            ARCHIVE_ROOT + "profile2_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile2_core/streams/",
            ARCHIVE_ROOT + "profile2_core_special/streams/"
    );

    /// The archive shared by inventory, discovery, and selected dynamic cases for this test class.
    private static @Nullable ZipFile archive;

    /// Creates an Argon corpus test instance.
    ArgonAv1CorpusTest() {
    }

    /// Opens the configured Argon archive once for this test class.
    ///
    /// @throws IOException if the archive cannot be opened
    @BeforeAll
    static void openArchive() throws IOException {
        Path archivePath = archivePath();
        assertTrue(Files.isRegularFile(archivePath), () -> "Missing Argon Streams archive: " + archivePath);
        archive = new ZipFile(archivePath.toFile());
    }

    /// Closes the shared Argon archive after all dynamic cases finish.
    ///
    /// @throws IOException if the archive cannot be closed
    @AfterAll
    static void closeArchive() throws IOException {
        @Nullable ZipFile openArchive = archive;
        archive = null;
        if (openArchive != null) {
            openArchive.close();
        }
    }

    /// Verifies that the configured ZIP has the complete pinned stream inventory.
    @Test
    void archiveContainsExpectedStreamInventory() {
        ZipFile openArchive = archive();
        long streamCount = 0;
        long referenceStreamCount = 0;
        Enumeration<? extends ZipEntry> entries = openArchive.entries();
        while (entries.hasMoreElements()) {
            String entryName = entries.nextElement().getName();
            if (entryName.endsWith(".obu")) {
                streamCount++;
                if (REFERENCE_STREAM_PREFIXES.stream().anyMatch(entryName::startsWith)) {
                    referenceStreamCount++;
                }
            }
        }
        assertEquals(EXPECTED_STREAM_COUNT, streamCount);
        assertEquals(EXPECTED_REFERENCE_STREAM_COUNT, referenceStreamCount);
        assertNotNull(openArchive.getEntry(ARCHIVE_ROOT + "P8005-R-005h (Argon Streams AV1 User Manual).pdf"));
    }

    /// Verifies one-based shard parsing and deterministic round-robin selection.
    @Test
    void selectsDeterministicCorpusShards() {
        @Unmodifiable List<CorpusCase> cases = List.of(
                new CorpusCase("category", "test1.obu"),
                new CorpusCase("category", "test2.obu"),
                new CorpusCase("category", "test3.obu"),
                new CorpusCase("category", "test4.obu"),
                new CorpusCase("category", "test5.obu")
        );

        assertEquals(new CorpusShard(2, 3), CorpusShard.parse("2/3"));
        assertEquals(List.of(cases.get(0), cases.get(3)), new CorpusShard(1, 3).select(cases));
        assertEquals(List.of(cases.get(1), cases.get(4)), new CorpusShard(2, 3).select(cases));
        assertEquals(List.of(cases.get(2)), new CorpusShard(3, 3).select(cases));
        assertThrows(IllegalArgumentException.class, () -> CorpusShard.parse("0/3"));
        assertThrows(IllegalArgumentException.class, () -> CorpusShard.parse("4/3"));
        assertThrows(IllegalArgumentException.class, () -> CorpusShard.parse("1/0"));
        assertThrows(IllegalArgumentException.class, () -> CorpusShard.parse("one/three"));
        assertThrows(IllegalArgumentException.class, () -> new CorpusShard(4, 4).select(cases.subList(0, 3)));
    }

    /// Returns reference-output checks for gated low-overhead and Annex B streams in all profiles.
    ///
    /// @return the dynamic reference-output tests
    @TestFactory
    Stream<DynamicTest> supportedStreamsMatchReferenceYuvDigests() {
        return selectedReferenceCases().stream()
                .map(testCase -> DynamicTest.dynamicTest(
                        testCase.category() + "/" + testCase.streamName(),
                        () -> assertReferenceDigest(testCase)
                ));
    }

    /// Returns the complete reference gate, a diagnostic group, or one explicitly selected case.
    ///
    /// @return the immutable selected cases
    private static @Unmodifiable List<CorpusCase> selectedReferenceCases() {
        @Nullable String selectedCase = System.getProperty(CASE_PROPERTY);
        if (selectedCase == null) {
            return selectConfiguredShard(allReferenceCases(null));
        }
        if (selectedCase.equals("all")) {
            return selectConfiguredShard(allReferenceCases(null));
        }
        if (selectedCase.endsWith("/all")) {
            String category = selectedCase.substring(0, selectedCase.length() - "/all".length());
            if (category.isEmpty() || category.indexOf('/') >= 0) {
                throw new IllegalArgumentException("Invalid Argon AV1 category selector: " + selectedCase);
            }
            @Unmodifiable List<CorpusCase> cases = allReferenceCases(category);
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("Unknown Argon AV1 category: " + category);
            }
            return selectConfiguredShard(cases);
        }
        if (System.getProperty(SHARD_PROPERTY) != null) {
            throw new IllegalArgumentException("Argon AV1 sharding cannot be combined with a single-case selector");
        }
        return List.of(CorpusCase.parse(selectedCase));
    }

    /// Applies the configured deterministic shard to one sorted corpus selection.
    ///
    /// @param cases the complete sorted selection
    /// @return the immutable selected shard, or `cases` when no shard is configured
    private static @Unmodifiable List<CorpusCase> selectConfiguredShard(
            @Unmodifiable List<CorpusCase> cases
    ) {
        @Nullable String selectedShard = System.getProperty(SHARD_PROPERTY);
        if (selectedShard == null) {
            return cases;
        }
        return CorpusShard.parse(selectedShard).select(cases);
    }

    /// Returns all gated reference cases, optionally restricted to one category.
    ///
    /// @param selectedCategory the exact category to select, or `null` for every profile
    /// @return the immutable sorted corpus cases
    private static @Unmodifiable List<CorpusCase> allReferenceCases(
            @Nullable String selectedCategory
    ) {
        return archive().stream()
                .map(ZipEntry::getName)
                .filter(name -> name.endsWith(".obu"))
                .filter(name -> REFERENCE_STREAM_PREFIXES.stream().anyMatch(name::startsWith))
                .map(name -> CorpusCase.parse(name.substring(ARCHIVE_ROOT.length()).replace("/streams/", "/")))
                .filter(testCase -> selectedCategory == null || testCase.category().equals(selectedCategory))
                .sorted((left, right) -> left.selector().compareTo(right.selector()))
                .toList();
    }

    /// Decodes one selected stream and compares all visible pre-grain YUV planes with Argon's MD5.
    ///
    /// @param testCase the selected corpus stream
    private static void assertReferenceDigest(CorpusCase testCase) throws IOException, NoSuchAlgorithmException {
        ZipFile archive = archive();
        String streamPath = ARCHIVE_ROOT + testCase.category() + "/streams/" + testCase.streamName();
        String referencePath = ARCHIVE_ROOT + testCase.category() + "/md5_no_film_grain/"
                + testCase.baseName() + ".md5";
        ZipEntry streamEntry = requireEntry(archive, streamPath);
        String expectedDigest = readReferenceDigest(archive, requireEntry(archive, referencePath));
        MessageDigest actualDigest = MessageDigest.getInstance("MD5");
        Av1DecoderConfig config = Av1DecoderConfig.builder()
                .applyFilmGrain(false)
                .outputAllLayers(true)
                .build();

        int frameCount = 0;
        @Nullable List<String> frameDiagnostics = Boolean.getBoolean(TRACE_FRAMES_PROPERTY)
                ? new ArrayList<>()
                : null;
        try {
            BufferedInput input = new BufferedInput.OfInputStream(archive.getInputStream(streamEntry));
            try (Av1ImageReader reader = testCase.annexB()
                    ? Av1ImageReader.openAnnexB(input, config)
                    : Av1ImageReader.open(input, config)) {
                @Nullable DecodedPlanes decodedPlanes;
                while ((decodedPlanes = reader.readPlanes()) != null) {
                    DecodedPlanes requiredPlanes = decodedPlanes;
                    updateYuvDigest(actualDigest, requiredPlanes);
                    if (frameDiagnostics != null) {
                        frameDiagnostics.add(frameDiagnostic(frameCount, requiredPlanes));
                    }
                    frameCount++;
                }
            }
        } catch (DecodeException exception) {
            throw new AssertionError(
                    streamPath + " failed at OBU " + exception.obuIndex()
                            + " (offset " + exception.streamOffset() + ", stage " + exception.stage() + ")",
                    exception
            );
        }

        assertTrue(frameCount > 0, () -> streamPath + " produced no visible frames");
        String actualDigestHex = HexFormat.of().formatHex(actualDigest.digest());
        assertEquals(
                expectedDigest,
                actualDigestHex,
                () -> frameDiagnostics == null
                        ? streamPath
                        : streamPath + System.lineSeparator() + String.join(System.lineSeparator(), frameDiagnostics)
        );
    }

    /// Returns one diagnostic summary containing the frame layout and complete YUV digest.
    ///
    /// @param frameIndex the zero-based output frame index
    /// @param planes the decoded pre-grain planes
    /// @return the frame diagnostic summary
    private static String frameDiagnostic(int frameIndex, DecodedPlanes planes) throws NoSuchAlgorithmException {
        MessageDigest frameDigest = MessageDigest.getInstance("MD5");
        updateYuvDigest(frameDigest, planes);
        return "frame=" + frameIndex
                + " dimensions=" + planes.codedWidth() + "x" + planes.codedHeight()
                + " format=" + planes.pixelFormat()
                + " bitDepth=" + planes.bitDepth()
                + " md5=" + HexFormat.of().formatHex(frameDigest.digest())
                + " y=" + planeDigest(planes.lumaPlane(), planes.bitDepth())
                + " u=" + planeDigest(planes.chromaUPlane(), planes.bitDepth())
                + " v=" + planeDigest(planes.chromaVPlane(), planes.bitDepth());
    }

    /// Returns the visible-sample digest of one decoded plane, or `none` for an absent plane.
    ///
    /// @param plane the decoded plane, or `null`
    /// @param bitDepth the decoded bit depth
    /// @return the plane digest, or `none`
    private static String planeDigest(@Nullable DecodedPlane plane, int bitDepth) throws NoSuchAlgorithmException {
        if (plane == null) {
            return "none";
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        updatePlaneDigest(digest, plane, bitDepth);
        return HexFormat.of().formatHex(digest.digest());
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

    /// Returns the archive opened for the current test-class lifecycle.
    ///
    /// @return the shared open Argon archive
    private static ZipFile archive() {
        return Objects.requireNonNull(archive, "Argon archive has not been opened");
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
        /// Parses one `category/stream.obu` diagnostic selector.
        ///
        /// @param value the selector to parse
        /// @return the parsed corpus case
        private static CorpusCase parse(String value) {
            int separator = value.indexOf('/');
            if (separator <= 0
                    || separator != value.lastIndexOf('/')
                    || !value.endsWith(".obu")
                    || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid Argon AV1 case: " + value);
            }
            return new CorpusCase(value.substring(0, separator), value.substring(separator + 1));
        }

        /// Returns the stream file name without its `.obu` suffix.
        ///
        /// @return the stream base name
        private String baseName() {
            return streamName.substring(0, streamName.length() - ".obu".length());
        }

        /// Returns the canonical diagnostic selector for this case.
        ///
        /// @return the `category/stream.obu` selector
        private String selector() {
            return category + "/" + streamName;
        }

        /// Returns whether this case uses Annex B external unit framing.
        ///
        /// @return whether the stream is Annex B rather than low-overhead
        private boolean annexB() {
            return !category.contains("_not_annexb");
        }
    }

    /// Identifies one deterministic one-based shard of a sorted corpus selection.
    ///
    /// @param index the one-based shard index
    /// @param count the total number of shards
    @NotNullByDefault
    private record CorpusShard(int index, int count) {
        /// Creates one validated corpus shard.
        private CorpusShard {
            if (count <= 0) {
                throw new IllegalArgumentException("Argon AV1 shard count must be positive: " + count);
            }
            if (index <= 0 || index > count) {
                throw new IllegalArgumentException("Argon AV1 shard index must be in [1, " + count + "]: " + index);
            }
        }

        /// Parses one `index/count` shard selector.
        ///
        /// @param value the shard selector to parse
        /// @return the parsed shard
        private static CorpusShard parse(String value) {
            int separator = value.indexOf('/');
            if (separator <= 0 || separator != value.lastIndexOf('/') || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid Argon AV1 shard: " + value);
            }
            try {
                return new CorpusShard(
                        Integer.parseInt(value.substring(0, separator)),
                        Integer.parseInt(value.substring(separator + 1))
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid Argon AV1 shard: " + value, exception);
            }
        }

        /// Selects this shard from one deterministically sorted corpus list.
        ///
        /// @param cases the complete sorted corpus list
        /// @return the immutable non-empty shard selection
        private @Unmodifiable List<CorpusCase> select(@Unmodifiable List<CorpusCase> cases) {
            List<CorpusCase> selectedCases = new ArrayList<>((cases.size() + count - 1) / count);
            for (int caseIndex = index - 1; caseIndex < cases.size(); caseIndex += count) {
                selectedCases.add(cases.get(caseIndex));
            }
            if (selectedCases.isEmpty()) {
                throw new IllegalArgumentException(
                        "Argon AV1 shard " + index + "/" + count + " is empty for " + cases.size() + " cases"
                );
            }
            return List.copyOf(selectedCases);
        }
    }
}
