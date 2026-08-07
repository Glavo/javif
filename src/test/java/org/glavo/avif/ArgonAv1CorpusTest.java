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
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuStreamReader;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Argon reference outputs and strict rejection of selected malformed AV1 streams.
///
/// The corpus remains in its downloaded ZIP. Tests open only the selected entries, compare valid
/// streams with their pre-grain YUV MD5 digests, and fully consume malformed streams in strict mode.
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

    /// Optional system property that selects operating point `0..31`, `distinct`, or `all`.
    private static final String OPERATING_POINT_PROPERTY = "org.glavo.avif.argon.operatingPoint";

    /// Optional system property that selects `pre-grain`, `film-grain`, or `both` reference output.
    private static final String OUTPUT_PROPERTY = "org.glavo.avif.argon.output";

    /// Optional system property that enables per-frame digest diagnostics on corpus failures.
    private static final String TRACE_FRAMES_PROPERTY = "org.glavo.avif.argon.traceFrames";

    /// MD5 of an empty decoder output, used by valid operating points that select no visible frame.
    private static final String EMPTY_OUTPUT_DIGEST = "d41d8cd98f00b204e9800998ecf8427e";

    /// Root directory stored in the Argon Streams 2.1.1 ZIP.
    private static final String ARCHIVE_ROOT =
            "argon_coveragetool_av1_base_and_extended_profiles_v2.1/";

    /// Number of AV1 streams distributed in the pinned Argon Streams archive.
    private static final long EXPECTED_STREAM_COUNT = 3_921L;

    /// Number of gated streams that carry reference YUV digests.
    private static final int EXPECTED_REFERENCE_STREAM_COUNT = 3_586;

    /// Number of operating-point reference configurations in either Argon MD5 tree.
    private static final int EXPECTED_REFERENCE_VARIANT_COUNT = 89_239;

    /// Number of gated malformed streams that strict decoding must reject.
    private static final int EXPECTED_ERROR_STREAM_COUNT = 335;

    /// Number of malformed streams covered by the strict decoder gate.
    private static final int EXPECTED_STRICT_ERROR_STREAM_COUNT = 335;

    /// Archive prefixes for gated low-overhead and Annex B core streams in all profiles.
    private static final @Unmodifiable List<String> REFERENCE_STREAM_PREFIXES = List.of(
            ARCHIVE_ROOT + "profile0_not_annexb/streams/",
            ARCHIVE_ROOT + "profile0_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile0_core/streams/",
            ARCHIVE_ROOT + "profile0_core_special/streams/",
            ARCHIVE_ROOT + "profile0_large_scale_tile/streams/",
            ARCHIVE_ROOT + "profile0_large_scale_tile_special/streams/",
            ARCHIVE_ROOT + "profile0_stress/streams/",
            ARCHIVE_ROOT + "profile1_not_annexb/streams/",
            ARCHIVE_ROOT + "profile1_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile1_core/streams/",
            ARCHIVE_ROOT + "profile1_core_special/streams/",
            ARCHIVE_ROOT + "profile1_large_scale_tile/streams/",
            ARCHIVE_ROOT + "profile1_large_scale_tile_special/streams/",
            ARCHIVE_ROOT + "profile1_stress/streams/",
            ARCHIVE_ROOT + "profile2_not_annexb/streams/",
            ARCHIVE_ROOT + "profile2_not_annexb_special/streams/",
            ARCHIVE_ROOT + "profile2_core/streams/",
            ARCHIVE_ROOT + "profile2_core_special/streams/",
            ARCHIVE_ROOT + "profile2_large_scale_tile/streams/",
            ARCHIVE_ROOT + "profile2_large_scale_tile_special/streams/",
            ARCHIVE_ROOT + "profile2_stress/streams/",
            ARCHIVE_ROOT + "profile_switching/streams/"
    );

    /// Archive prefixes for malformed streams that strict decoding must reject.
    private static final @Unmodifiable List<String> ERROR_STREAM_PREFIXES = List.of(
            ARCHIVE_ROOT + "profile0_error/streams/",
            ARCHIVE_ROOT + "profile1_error/streams/",
            ARCHIVE_ROOT + "profile2_error/streams/"
    );

    /// Malformed streams whose documented constraints apply only in Large Scale Tile decoder mode.
    private static final @Unmodifiable Map<String, @Unmodifiable Set<String>>
            LARGE_SCALE_TILE_ONLY_ERROR_STREAMS = Map.of(
            "profile0_error", Set.of(
                    "test265.obu",
                    "test268.obu",
                    "test269.obu",
                    "test270.obu",
                    "test271.obu",
                    "test272.obu",
                    "test273.obu",
                    "test275.obu",
                    "test276.obu",
                    "test278.obu",
                    "test279.obu",
                    "test280.obu",
                    "test281.obu",
                    "test283.obu",
                    "test285.obu",
                    "test286.obu",
                    "test287.obu",
                    "test288.obu",
                    "test289.obu",
                    "test290.obu",
                    "test291.obu",
                    "test292.obu",
                    "test294.obu",
                    "test295.obu",
                    "test298.obu",
                    "test300.obu"
            ),
            "profile1_error", Set.of(
                    "test413.obu",
                    "test414.obu",
                    "test415.obu",
                    "test417.obu",
                    "test420.obu",
                    "test421.obu",
                    "test424.obu",
                    "test426.obu",
                    "test428.obu",
                    "test429.obu",
                    "test430.obu",
                    "test432.obu",
                    "test433.obu",
                    "test434.obu",
                    "test435.obu",
                    "test436.obu",
                    "test438.obu",
                    "test439.obu",
                    "test442.obu",
                    "test444.obu",
                    "test448.obu",
                    "test452.obu",
                    "test454.obu",
                    "test458.obu",
                    "test459.obu",
                    "test461.obu"
            ),
            "profile2_error", Set.of(
                    "test303.obu",
                    "test304.obu",
                    "test305.obu",
                    "test306.obu",
                    "test307.obu",
                    "test310.obu",
                    "test312.obu",
                    "test315.obu",
                    "test316.obu",
                    "test317.obu",
                    "test319.obu",
                    "test321.obu",
                    "test324.obu",
                    "test325.obu",
                    "test326.obu",
                    "test327.obu",
                    "test328.obu",
                    "test330.obu",
                    "test331.obu",
                    "test334.obu",
                    "test336.obu",
                    "test337.obu",
                    "test338.obu",
                    "test344.obu",
                    "test345.obu",
                    "test350.obu",
                    "test358.obu",
                    "test359.obu"
            )
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
        long preGrainReferenceVariantCount = 0;
        long filmGrainReferenceVariantCount = 0;
        long errorStreamCount = 0;
        long strictErrorStreamCount = 0;
        Enumeration<? extends ZipEntry> entries = openArchive.entries();
        while (entries.hasMoreElements()) {
            String entryName = entries.nextElement().getName();
            if (entryName.endsWith(".md5") && entryName.contains("/md5_no_film_grain/")) {
                preGrainReferenceVariantCount++;
            }
            if (entryName.endsWith(".md5") && entryName.contains("/md5_ref/")) {
                filmGrainReferenceVariantCount++;
            }
            if (entryName.endsWith(".obu")) {
                streamCount++;
                if (REFERENCE_STREAM_PREFIXES.stream().anyMatch(entryName::startsWith)) {
                    referenceStreamCount++;
                }
                if (ERROR_STREAM_PREFIXES.stream().anyMatch(entryName::startsWith)) {
                    errorStreamCount++;
                    strictErrorStreamCount++;
                }
            }
        }
        assertEquals(EXPECTED_STREAM_COUNT, streamCount);
        assertEquals(EXPECTED_REFERENCE_STREAM_COUNT, referenceStreamCount);
        assertEquals(EXPECTED_REFERENCE_VARIANT_COUNT, preGrainReferenceVariantCount);
        assertEquals(EXPECTED_REFERENCE_VARIANT_COUNT, filmGrainReferenceVariantCount);
        assertEquals(EXPECTED_ERROR_STREAM_COUNT, errorStreamCount);
        assertEquals(EXPECTED_STRICT_ERROR_STREAM_COUNT, strictErrorStreamCount);
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

    /// Verifies parsing of the reference-output modes exposed by the Gradle corpus task.
    @Test
    void selectsReferenceOutputModes() {
        assertEquals(List.of(ReferenceOutput.PRE_GRAIN), ReferenceOutput.parseSelection(null));
        assertEquals(List.of(ReferenceOutput.PRE_GRAIN), ReferenceOutput.parseSelection("pre-grain"));
        assertEquals(List.of(ReferenceOutput.FILM_GRAIN), ReferenceOutput.parseSelection("film-grain"));
        assertEquals(
                List.of(ReferenceOutput.PRE_GRAIN, ReferenceOutput.FILM_GRAIN),
                ReferenceOutput.parseSelection("both")
        );
        assertThrows(IllegalArgumentException.class, () -> ReferenceOutput.parseSelection("grain"));
    }

    /// Verifies parsing of single, distinct-output, and exhaustive operating-point selections.
    @Test
    void selectsOperatingPointModes() {
        assertEquals(OperatingPointSelection.single(0), OperatingPointSelection.parse(null));
        assertEquals(OperatingPointSelection.single(0), OperatingPointSelection.parse("0"));
        assertEquals(OperatingPointSelection.single(31), OperatingPointSelection.parse("31"));
        assertEquals(
                new OperatingPointSelection(OperatingPointSelectionMode.DISTINCT, 0),
                OperatingPointSelection.parse("distinct")
        );
        assertEquals(
                new OperatingPointSelection(OperatingPointSelectionMode.ALL, 0),
                OperatingPointSelection.parse("all")
        );
        assertThrows(IllegalArgumentException.class, () -> OperatingPointSelection.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> OperatingPointSelection.parse("32"));
        assertThrows(IllegalArgumentException.class, () -> OperatingPointSelection.parse("first"));
    }

    /// Returns reference-output checks and strict malformed-stream rejection checks.
    ///
    /// @return the dynamic gated-stream tests
    /// @throws IOException if a selected reference digest cannot be read during discovery
    @TestFactory
    Stream<DynamicTest> gatedStreamsMeetExpectedOutcome() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (CorpusCase testCase : selectedGatedCases()) {
            if (testCase.errorStream()) {
                tests.add(DynamicTest.dynamicTest(
                        testCase.selector(),
                        () -> assertMalformedStreamRejected(testCase)
                ));
                continue;
            }
            for (ReferenceCase referenceCase : selectedReferenceCases(testCase)) {
                tests.add(DynamicTest.dynamicTest(
                        referenceCase.displayName(),
                        () -> assertReferenceDigest(referenceCase)
                ));
            }
        }
        return tests.stream();
    }

    /// Returns the complete gate, a diagnostic group, or one explicitly selected case.
    ///
    /// @return the immutable selected cases
    private static @Unmodifiable List<CorpusCase> selectedGatedCases() {
        @Nullable String selectedCase = System.getProperty(CASE_PROPERTY);
        if (selectedCase == null) {
            return selectConfiguredShard(allGatedCases(null));
        }
        if (selectedCase.equals("all")) {
            return selectConfiguredShard(allGatedCases(null));
        }
        if (selectedCase.endsWith("/all")) {
            String category = selectedCase.substring(0, selectedCase.length() - "/all".length());
            if (category.isEmpty() || category.indexOf('/') >= 0) {
                throw new IllegalArgumentException("Invalid Argon AV1 category selector: " + selectedCase);
            }
            @Unmodifiable List<CorpusCase> cases = allGatedCases(category);
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

    /// Returns all gated digest and malformed-stream cases, optionally restricted to one category.
    ///
    /// @param selectedCategory the exact category to select, or `null` for every profile
    /// @return the immutable sorted corpus cases
    private static @Unmodifiable List<CorpusCase> allGatedCases(
            @Nullable String selectedCategory
    ) {
        return archive().stream()
                .map(ZipEntry::getName)
                .filter(name -> name.endsWith(".obu"))
                .filter(name -> REFERENCE_STREAM_PREFIXES.stream().anyMatch(name::startsWith)
                        || ERROR_STREAM_PREFIXES.stream().anyMatch(name::startsWith))
                .map(name -> CorpusCase.parse(name.substring(ARCHIVE_ROOT.length()).replace("/streams/", "/")))
                .filter(testCase -> selectedCategory == null || testCase.category().equals(selectedCategory))
                .sorted((left, right) -> left.selector().compareTo(right.selector()))
                .toList();
    }

    /// Returns the configured reference-output variants for one selected stream.
    ///
    /// @param testCase the reference stream to expand
    /// @return the immutable selected reference cases
    /// @throws IOException if distinct-output selection cannot read a reference digest
    private static @Unmodifiable List<ReferenceCase> selectedReferenceCases(CorpusCase testCase) throws IOException {
        @Unmodifiable List<ReferenceOutput> outputs = ReferenceOutput.parseSelection(
                System.getProperty(OUTPUT_PROPERTY)
        );
        OperatingPointSelection operatingPointSelection = OperatingPointSelection.parse(
                System.getProperty(OPERATING_POINT_PROPERTY)
        );
        @Unmodifiable List<Integer> operatingPoints = operatingPointSelection.select(testCase, outputs);
        List<ReferenceCase> cases = new ArrayList<>(operatingPoints.size() * outputs.size());
        for (int operatingPoint : operatingPoints) {
            for (ReferenceOutput output : outputs) {
                cases.add(new ReferenceCase(testCase, operatingPoint, output));
            }
        }
        return List.copyOf(cases);
    }

    /// Returns the exact archive path of one reference digest.
    ///
    /// @param testCase the reference stream
    /// @param operatingPoint the selected operating-point index
    /// @param output the selected output stage
    /// @return the reference MD5 path
    private static String referenceDigestPath(
            CorpusCase testCase,
            int operatingPoint,
            ReferenceOutput output
    ) {
        String digestName = testCase.baseName();
        String layerDirectory = "";
        if (operatingPoint != 0) {
            layerDirectory = "layers/" + operatingPoint + "/";
            digestName += "_layer" + operatingPoint;
        }
        return ARCHIVE_ROOT + testCase.category() + "/" + output.directory() + "/"
                + layerDirectory + digestName + ".md5";
    }

    /// Returns whether the archive carries a reference for one operating-point selection.
    ///
    /// @param testCase the reference stream
    /// @param operatingPoint the selected operating-point index
    /// @return whether the pre-grain reference exists
    private static boolean hasOperatingPointReference(CorpusCase testCase, int operatingPoint) {
        return archive().getEntry(referenceDigestPath(testCase, operatingPoint, ReferenceOutput.PRE_GRAIN)) != null;
    }

    /// Returns the number of operating-point indices declared by every sequence in one stream.
    ///
    /// A configured reader validates its selection whenever a new sequence header appears, so a
    /// whole-stream reference case can select only indices shared by every sequence.
    ///
    /// @param testCase the reference stream to inspect
    /// @return the positive common operating-point count
    /// @throws IOException if the stream cannot be read or its sequence header is malformed
    private static int commonDeclaredOperatingPointCount(CorpusCase testCase) throws IOException {
        ZipFile archive = archive();
        String streamPath = ARCHIVE_ROOT + testCase.category() + "/streams/" + testCase.streamName();
        ZipEntry streamEntry = requireEntry(archive, streamPath);
        SequenceHeaderParser parser = new SequenceHeaderParser();
        int commonCount = 32;
        boolean foundSequenceHeader = false;
        try (BufferedInput input = new BufferedInput.OfInputStream(archive.getInputStream(streamEntry))) {
            ObuStreamReader reader = testCase.annexB() ? ObuStreamReader.forAnnexB(input) : new ObuStreamReader(input);
            @Nullable ObuPacket packet;
            while ((packet = reader.readObu()) != null) {
                if (packet.header().type() != ObuType.SEQUENCE_HEADER) {
                    continue;
                }
                SequenceHeader sequenceHeader = parser.parse(packet, false);
                commonCount = Math.min(commonCount, sequenceHeader.operatingPoints().length);
                foundSequenceHeader = true;
            }
        }
        if (!foundSequenceHeader) {
            throw new IOException(streamPath + " contains no sequence header");
        }
        return commonCount;
    }

    /// Returns a stable digest signature for one operating point across selected output stages.
    ///
    /// @param testCase the reference stream
    /// @param operatingPoint the selected operating-point index
    /// @param outputs the output stages that participate in uniqueness
    /// @return the combined expected-digest signature
    /// @throws IOException if one reference digest cannot be read
    private static String referenceDigestSignature(
            CorpusCase testCase,
            int operatingPoint,
            @Unmodifiable List<ReferenceOutput> outputs
    ) throws IOException {
        StringBuilder signature = new StringBuilder(outputs.size() * 33);
        for (ReferenceOutput output : outputs) {
            String path = referenceDigestPath(testCase, operatingPoint, output);
            signature.append(readReferenceDigest(archive(), requireEntry(archive(), path))).append(';');
        }
        return signature.toString();
    }

    /// Verifies that strict decoding rejects one malformed Argon stream before clean end of input.
    ///
    /// @param testCase the malformed corpus stream
    private static void assertMalformedStreamRejected(CorpusCase testCase) {
        ZipFile archive = archive();
        String streamPath = ARCHIVE_ROOT + testCase.category() + "/streams/" + testCase.streamName();
        ZipEntry streamEntry = requireEntry(archive, streamPath);
        Av1DecoderConfig config = Av1DecoderConfig.DEFAULT
                .withApplyFilmGrain(false)
                .withStrictStdCompliance(true)
                .withOutputAllLayers(true)
                .withLargeScaleTileMode(testCase.largeScaleTileMode());

        assertThrows(DecodeException.class, () -> {
            BufferedInput input = new BufferedInput.OfInputStream(archive.getInputStream(streamEntry));
            try (Av1ImageReader reader = testCase.annexB()
                    ? Av1ImageReader.openAnnexB(input, config)
                    : Av1ImageReader.open(input, config)) {
                while (reader.readPlanes() != null) {
                    // Continue until strict decoding reaches the malformed portion of the stream.
                }
            }
        }, streamPath);
    }

    /// Decodes one selected stream and compares all visible pre-grain YUV planes with Argon's MD5.
    ///
    /// @param referenceCase the selected stream, operating point, and output stage
    private static void assertReferenceDigest(ReferenceCase referenceCase) throws IOException, NoSuchAlgorithmException {
        CorpusCase testCase = referenceCase.testCase();
        ZipFile archive = archive();
        String streamPath = ARCHIVE_ROOT + testCase.category() + "/streams/" + testCase.streamName();
        String referencePath = referenceDigestPath(
                testCase,
                referenceCase.operatingPoint(),
                referenceCase.output()
        );
        ZipEntry streamEntry = requireEntry(archive, streamPath);
        String expectedDigest = readReferenceDigest(archive, requireEntry(archive, referencePath));
        MessageDigest actualDigest = MessageDigest.getInstance("MD5");
        @Nullable List<LargeScaleTileDigestLayout> tileListLayouts = testCase.largeScaleTileMode()
                ? readLargeScaleTileDigestLayouts(archive, streamEntry, testCase.annexB())
                : null;
        Av1DecoderConfig config = Av1DecoderConfig.DEFAULT
                .withApplyFilmGrain(referenceCase.output().applyFilmGrain())
                .withOutputAllLayers(true)
                .withLargeScaleTileMode(testCase.largeScaleTileMode())
                .withOperatingPoint(referenceCase.operatingPoint());

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
                    if (tileListLayouts == null) {
                        updateYuvDigest(actualDigest, requiredPlanes);
                    } else {
                        assertTrue(
                                frameCount < tileListLayouts.size(),
                                referenceCase.displayName() + " produced too many tile lists"
                        );
                        updateLargeScaleTileDigest(actualDigest, requiredPlanes, tileListLayouts.get(frameCount));
                    }
                    if (frameDiagnostics != null) {
                        frameDiagnostics.add(frameDiagnostic(frameCount, requiredPlanes));
                    }
                    frameCount++;
                }
            }
        } catch (DecodeException exception) {
            throw new AssertionError(
                    referenceCase.displayName() + " failed at OBU " + exception.obuIndex()
                            + " (offset " + exception.streamOffset() + ", stage " + exception.stage() + ")",
                    exception
            );
        }

        if (expectedDigest.equals(EMPTY_OUTPUT_DIGEST)) {
            assertEquals(0, frameCount, referenceCase.displayName() + " expected empty output");
        } else {
            assertTrue(frameCount > 0, () -> referenceCase.displayName() + " produced no visible frames");
        }
        if (tileListLayouts != null) {
            assertEquals(tileListLayouts.size(), frameCount, referenceCase.displayName() + " tile-list output count");
        }
        String actualDigestHex = HexFormat.of().formatHex(actualDigest.digest());
        assertEquals(
                expectedDigest,
                actualDigestHex,
                () -> frameDiagnostics == null
                        ? referenceCase.displayName()
                        : referenceCase.displayName() + System.lineSeparator()
                                + String.join(System.lineSeparator(), frameDiagnostics)
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
                + " format=" + planes.chromaFormat()
                + " bitDepth=" + planes.bitDepth()
                + " md5=" + HexFormat.of().formatHex(frameDigest.digest())
                + " y=" + planeDigest(planes.lumaPlane(), planes.bitDepth().bits())
                + " u=" + planeDigest(planes.chromaUPlane(), planes.bitDepth().bits())
                + " v=" + planeDigest(planes.chromaVPlane(), planes.bitDepth().bits());
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
        updatePlaneDigest(digest, planes.lumaPlane(), planes.bitDepth().bits());
        @Nullable DecodedPlane chromaUPlane = planes.chromaUPlane();
        @Nullable DecodedPlane chromaVPlane = planes.chromaVPlane();
        if (chromaUPlane != null && chromaVPlane != null) {
            updatePlaneDigest(digest, chromaUPlane, planes.bitDepth().bits());
            updatePlaneDigest(digest, chromaVPlane, planes.bitDepth().bits());
        }
    }

    /// Updates one digest in libaom's `YUV1D` Large Scale Tile output order.
    ///
    /// Each populated output tile contributes its complete Y, U, and V planes before the next
    /// tile. Unpopulated cells in the rectangular output canvas are intentionally omitted.
    ///
    /// @param digest the digest to update
    /// @param planes the rectangular decoded tile-list output
    /// @param layout the output grid and populated tile count
    private static void updateLargeScaleTileDigest(
            MessageDigest digest,
            DecodedPlanes planes,
            LargeScaleTileDigestLayout layout
    ) {
        assertEquals(0, planes.codedWidth() % layout.outputColumns(), "tile-list luma width");
        assertEquals(0, planes.codedHeight() % layout.outputRows(), "tile-list luma height");
        int tileWidth = planes.codedWidth() / layout.outputColumns();
        int tileHeight = planes.codedHeight() / layout.outputRows();
        for (int tileIndex = 0; tileIndex < layout.tileCount(); tileIndex++) {
            int tileColumn = tileIndex % layout.outputColumns();
            int tileRow = tileIndex / layout.outputColumns();
            updatePlaneRegionDigest(
                    digest,
                    planes.lumaPlane(),
                    planes.bitDepth().bits(),
                    tileColumn * tileWidth,
                    tileRow * tileHeight,
                    tileWidth,
                    tileHeight
            );
            @Nullable DecodedPlane chromaUPlane = planes.chromaUPlane();
            @Nullable DecodedPlane chromaVPlane = planes.chromaVPlane();
            if (chromaUPlane != null && chromaVPlane != null) {
                assertEquals(0, chromaUPlane.width() % layout.outputColumns(), "tile-list chroma width");
                assertEquals(0, chromaUPlane.height() % layout.outputRows(), "tile-list chroma height");
                int chromaTileWidth = chromaUPlane.width() / layout.outputColumns();
                int chromaTileHeight = chromaUPlane.height() / layout.outputRows();
                updatePlaneRegionDigest(
                        digest,
                        chromaUPlane,
                        planes.bitDepth().bits(),
                        tileColumn * chromaTileWidth,
                        tileRow * chromaTileHeight,
                        chromaTileWidth,
                        chromaTileHeight
                );
                updatePlaneRegionDigest(
                        digest,
                        chromaVPlane,
                        planes.bitDepth().bits(),
                        tileColumn * chromaTileWidth,
                        tileRow * chromaTileHeight,
                        chromaTileWidth,
                        chromaTileHeight
                );
            }
        }
    }

    /// Reads the lightweight output layout carried by every tile-list OBU in one stream.
    ///
    /// @param archive the open Argon archive
    /// @param streamEntry the AV1 stream entry
    /// @param annexB whether the stream uses Annex B framing
    /// @return the tile-list layouts in decoding order
    private static @Unmodifiable List<LargeScaleTileDigestLayout> readLargeScaleTileDigestLayouts(
            ZipFile archive,
            ZipEntry streamEntry,
            boolean annexB
    ) throws IOException {
        List<LargeScaleTileDigestLayout> layouts = new ArrayList<>();
        try (BufferedInput input = new BufferedInput.OfInputStream(archive.getInputStream(streamEntry))) {
            ObuStreamReader obuReader = annexB ? ObuStreamReader.forAnnexB(input) : new ObuStreamReader(input);
            @Nullable ObuPacket packet;
            while ((packet = obuReader.readObu()) != null) {
                if (packet.header().type() != ObuType.TILE_LIST) {
                    continue;
                }
                byte[] payload = packet.payload();
                if (payload.length < 4) {
                    throw new IOException(streamEntry.getName() + " contains a truncated tile-list header");
                }
                layouts.add(new LargeScaleTileDigestLayout(
                        Byte.toUnsignedInt(payload[0]) + 1,
                        Byte.toUnsignedInt(payload[1]) + 1,
                        (Byte.toUnsignedInt(payload[2]) << 8 | Byte.toUnsignedInt(payload[3])) + 1
                ));
            }
        }
        return List.copyOf(layouts);
    }

    /// Updates one digest with the visible samples of one decoded plane.
    ///
    /// @param digest the digest to update
    /// @param plane the decoded plane
    /// @param bitDepth the decoded bit depth
    private static void updatePlaneDigest(MessageDigest digest, DecodedPlane plane, int bitDepth) {
        updatePlaneRegionDigest(digest, plane, bitDepth, 0, 0, plane.width(), plane.height());
    }

    /// Updates one digest with a rectangular region of one decoded plane.
    ///
    /// @param digest the digest to update
    /// @param plane the decoded plane
    /// @param bitDepth the decoded bit depth
    /// @param x the left sample coordinate
    /// @param y the top sample coordinate
    /// @param width the region width in samples
    /// @param height the region height in samples
    private static void updatePlaneRegionDigest(
            MessageDigest digest,
            DecodedPlane plane,
            int bitDepth,
            int x,
            int y,
            int width,
            int height
    ) {
        boolean highBitDepth = bitDepth > 8;
        for (int sampleY = y; sampleY < y + height; sampleY++) {
            for (int sampleX = x; sampleX < x + width; sampleX++) {
                int sample = plane.sample(sampleX, sampleY);
                digest.update((byte) sample);
                if (highBitDepth) {
                    digest.update((byte) (sample >>> 8));
                }
            }
        }
    }

    /// Describes libaom's digest layout for one Large Scale Tile output.
    ///
    /// @param outputColumns the rectangular output width in tiles
    /// @param outputRows the rectangular output height in tiles
    /// @param tileCount the number of populated tiles written in raster order
    @NotNullByDefault
    private record LargeScaleTileDigestLayout(int outputColumns, int outputRows, int tileCount) {
        /// Creates one validated digest layout.
        private LargeScaleTileDigestLayout {
            if (outputColumns <= 0 || outputRows <= 0) {
                throw new IllegalArgumentException("Tile-list output grid must be positive");
            }
            if (tileCount <= 0 || tileCount > outputColumns * outputRows) {
                throw new IllegalArgumentException("Tile-list tile count exceeds its output grid");
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

    /// Identifies one expected output stage and its corresponding Argon digest tree.
    @NotNullByDefault
    private enum ReferenceOutput {
        /// Pre-grain postprocessed output stored under `md5_no_film_grain`.
        PRE_GRAIN("pre-grain", "md5_no_film_grain", false),

        /// Presentation output with normative film grain stored under `md5_ref`.
        FILM_GRAIN("film-grain", "md5_ref", true);

        /// The command-line selector for this output stage.
        private final String selector;

        /// The archive directory containing this stage's reference digests.
        private final String directory;

        /// Whether the decoder must synthesize film grain for this output stage.
        private final boolean applyFilmGrain;

        /// Creates one reference-output descriptor.
        ///
        /// @param selector the command-line selector
        /// @param directory the reference digest directory
        /// @param applyFilmGrain whether film grain synthesis must be enabled
        ReferenceOutput(String selector, String directory, boolean applyFilmGrain) {
            this.selector = selector;
            this.directory = directory;
            this.applyFilmGrain = applyFilmGrain;
        }

        /// Returns the command-line selector for this output stage.
        ///
        /// @return the output selector
        private String selector() {
            return selector;
        }

        /// Returns the archive directory containing this stage's reference digests.
        ///
        /// @return the digest directory
        private String directory() {
            return directory;
        }

        /// Returns whether film grain synthesis must be enabled.
        ///
        /// @return whether film grain must be applied
        private boolean applyFilmGrain() {
            return applyFilmGrain;
        }

        /// Parses one output selection into its deterministic output-stage list.
        ///
        /// @param value `pre-grain`, `film-grain`, `both`, or `null` for the default
        /// @return the immutable selected output stages
        private static @Unmodifiable List<ReferenceOutput> parseSelection(@Nullable String value) {
            if (value == null || value.equals("pre-grain")) {
                return List.of(PRE_GRAIN);
            }
            if (value.equals("film-grain")) {
                return List.of(FILM_GRAIN);
            }
            if (value.equals("both")) {
                return List.of(PRE_GRAIN, FILM_GRAIN);
            }
            throw new IllegalArgumentException("Invalid Argon AV1 output selection: " + value);
        }
    }

    /// Identifies how operating-point variants are selected for each stream.
    @NotNullByDefault
    private enum OperatingPointSelectionMode {
        /// Selects one exact operating-point index.
        SINGLE,

        /// Selects the first operating point for every distinct expected output signature.
        DISTINCT,

        /// Selects every operating point for which the archive carries references.
        ALL
    }

    /// Selects one exact, all distinct, or every available operating-point reference.
    ///
    /// @param mode the selection mode
    /// @param operatingPoint the exact index for `SINGLE`, or `0` for the aggregate modes
    @NotNullByDefault
    private record OperatingPointSelection(OperatingPointSelectionMode mode, int operatingPoint) {
        /// Creates one validated operating-point selection.
        private OperatingPointSelection {
            if (mode == OperatingPointSelectionMode.SINGLE) {
                if (operatingPoint < 0 || operatingPoint > 31) {
                    throw new IllegalArgumentException("Argon AV1 operating point out of range: " + operatingPoint);
                }
            } else if (operatingPoint != 0) {
                throw new IllegalArgumentException("Aggregate Argon AV1 operating-point selections use index 0");
            }
        }

        /// Creates one exact operating-point selection.
        ///
        /// @param operatingPoint the exact operating-point index
        /// @return the validated selection
        private static OperatingPointSelection single(int operatingPoint) {
            return new OperatingPointSelection(OperatingPointSelectionMode.SINGLE, operatingPoint);
        }

        /// Parses an exact index, `distinct`, `all`, or the default operating point.
        ///
        /// @param value the configured property value, or `null` for operating point zero
        /// @return the validated selection
        private static OperatingPointSelection parse(@Nullable String value) {
            if (value == null) {
                return single(0);
            }
            if (value.equals("distinct")) {
                return new OperatingPointSelection(OperatingPointSelectionMode.DISTINCT, 0);
            }
            if (value.equals("all")) {
                return new OperatingPointSelection(OperatingPointSelectionMode.ALL, 0);
            }
            try {
                return single(Integer.parseInt(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid Argon AV1 operating-point selection: " + value, exception);
            }
        }

        /// Selects available operating points for one stream and output-stage combination.
        ///
        /// `DISTINCT` retains the lowest operating-point index for every unique tuple of selected
        /// reference digests. This preserves every distinct expected output while omitting the many
        /// duplicate Argon configurations that decode to the same bytes.
        ///
        /// @param testCase the reference stream
        /// @param outputs the selected output stages
        /// @return the immutable selected operating points in ascending order
        /// @throws IOException if distinct-output selection cannot read a reference digest
        private @Unmodifiable List<Integer> select(
                CorpusCase testCase,
                @Unmodifiable List<ReferenceOutput> outputs
        ) throws IOException {
            if (mode == OperatingPointSelectionMode.SINGLE) {
                if (operatingPoint != 0
                        && operatingPoint >= commonDeclaredOperatingPointCount(testCase)) {
                    throw new IllegalArgumentException(
                            testCase.selector() + " does not declare operating point " + operatingPoint
                    );
                }
                if (!hasOperatingPointReference(testCase, operatingPoint)) {
                    throw new IllegalArgumentException(
                            testCase.selector() + " has no Argon reference for operating point " + operatingPoint
                    );
                }
                return List.of(operatingPoint);
            }

            int declaredOperatingPointCount = commonDeclaredOperatingPointCount(testCase);
            List<Integer> available = new ArrayList<>(32);
            for (int candidate = 0; candidate < declaredOperatingPointCount; candidate++) {
                if (hasOperatingPointReference(testCase, candidate)) {
                    available.add(candidate);
                }
            }
            if (mode == OperatingPointSelectionMode.ALL) {
                return List.copyOf(available);
            }

            LinkedHashSet<String> signatures = new LinkedHashSet<>();
            List<Integer> distinct = new ArrayList<>();
            for (int candidate : available) {
                String signature = referenceDigestSignature(testCase, candidate, outputs);
                if (signatures.add(signature)) {
                    distinct.add(candidate);
                }
            }
            return List.copyOf(distinct);
        }
    }

    /// Identifies one exact Argon reference-output configuration.
    ///
    /// @param testCase the selected reference stream
    /// @param operatingPoint the selected operating-point index
    /// @param output the selected output stage
    @NotNullByDefault
    private record ReferenceCase(CorpusCase testCase, int operatingPoint, ReferenceOutput output) {
        /// Creates one validated reference-output case.
        private ReferenceCase {
            if (testCase.errorStream()) {
                throw new IllegalArgumentException("Malformed streams do not carry reference output: " + testCase);
            }
            if (operatingPoint < 0 || operatingPoint > 31) {
                throw new IllegalArgumentException("operatingPoint out of range: " + operatingPoint);
            }
        }

        /// Returns the stable JUnit display name for this exact configuration.
        ///
        /// @return the stream selector with non-default variant qualifiers
        private String displayName() {
            if (operatingPoint == 0 && output == ReferenceOutput.PRE_GRAIN) {
                return testCase.selector();
            }
            return testCase.selector() + " [op=" + operatingPoint + ", output=" + output.selector() + "]";
        }
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

        /// Returns whether this case belongs to an Argon malformed-stream category.
        ///
        /// @return whether strict decoding must reject this case
        private boolean errorStream() {
            return category.endsWith("_error");
        }

        /// Returns whether this case requires Large Scale Tile decoder mode.
        ///
        /// @return whether Large Scale Tile mode must be enabled while decoding the case
        private boolean largeScaleTileMode() {
            if (category.contains("_large_scale_tile")) {
                return true;
            }
            @Nullable Set<String> streams = LARGE_SCALE_TILE_ONLY_ERROR_STREAMS.get(category);
            return streams != null && streams.contains(streamName);
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
