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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Corpus tests for AVIF files published in AOMedia's `av1-avif` repository.
///
/// The corpus is deliberately kept outside the ordinary test resources because the source archive
/// is large. Run these tests with the Gradle `aomAvifTest` task.
@Tag("aom-corpus")
@NotNullByDefault
final class AomAvifTestFilesCorpusTest {
    /// The root containing the selected files from AOMedia's `testFiles` directory.
    private static final String TEST_DATA_ROOT = "aom-av1-avif-test-data";

    /// A technically non-compliant sample whose incomplete elementary stream has undefined parser behavior.
    private static final String INCOMPLETE_STREAM_RESOURCE = TEST_DATA_ROOT
            + "/Apple/edge_case_testing/non_compliant/truncated_elementary_stream.avif";

    /// A sample whose BT.2020 constant-luminance metadata permits raw decoding but not packed RGB conversion.
    private static final String RAW_PLANES_ONLY_RESOURCE = TEST_DATA_ROOT
            + "/Microsoft/Chimera_10bit_cropped_to_1920x1008_with_HDR_metadata.avif";

    /// Verifies that the pinned AOMedia corpus contains the expected files from every provider.
    ///
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @Test
    void corpusContainsEveryPinnedAvifResource() throws IOException, URISyntaxException {
        @Unmodifiable List<String> resources = avifResourceNames();
        assertEquals(172, resources.size(), "total AVIF resource count");
        assertEquals(10, countProviderResources(resources, "Apple"), "Apple resource count");
        assertEquals(83, countProviderResources(resources, "Link-U"), "Link-U resource count");
        assertEquals(16, countProviderResources(resources, "Microsoft"), "Microsoft resource count");
        assertEquals(58, countProviderResources(resources, "Netflix"), "Netflix resource count");
        assertEquals(5, countProviderResources(resources, "Xiph"), "Xiph resource count");
    }

    /// Creates one decode or robustness test for every AVIF resource in the pinned corpus.
    ///
    /// @return the dynamic AOMedia corpus tests
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @TestFactory
    Stream<DynamicTest> corpusResourcesMatchExpectedDecodeBehavior() throws IOException, URISyntaxException {
        return avifResourceNames().stream()
                .map(resourceName -> DynamicTest.dynamicTest(
                        resourceName,
                        () -> assertExpectedDecodeBehavior(resourceName)
                ));
    }

    /// Verifies the expected behavior for one corpus resource.
    ///
    /// @param resourceName the classpath resource name
    /// @throws IOException if a conforming resource cannot be read or decoded
    private static void assertExpectedDecodeBehavior(String resourceName) throws IOException {
        if (resourceName.equals(RAW_PLANES_ONLY_RESOURCE)) {
            assertRawPlaneDecodeWithUnsupportedPackedConversion(resourceName);
            return;
        }
        if (!resourceName.equals(INCOMPLETE_STREAM_RESOURCE)) {
            assertSuccessfulDecode(resourceName);
            return;
        }

        try {
            assertSuccessfulDecode(resourceName);
        } catch (IOException ignored) {
            // The fixture documentation explicitly leaves handling of the incomplete stream undefined.
        }
    }

    /// Decodes a resource's raw planes and verifies its deliberately unsupported packed RGB conversion.
    ///
    /// @param resourceName the classpath resource name
    /// @throws IOException if the raw planes cannot be read or decoded
    private static void assertRawPlaneDecodeWithUnsupportedPackedConversion(String resourceName) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifImageInfo info = reader.info();
            @Nullable AvifColorInfo colorInfo = info.colorInfo();
            assertNotNull(colorInfo, resourceName + " color info");
            assertEquals(10, colorInfo.matrixCoefficients(), resourceName + " matrix coefficients");

            AvifPlanes planes = reader.readRawColorPlanes(0);
            assertTrue(planes.codedWidth() > 0, resourceName + " coded width");
            assertTrue(planes.codedHeight() > 0, resourceName + " coded height");
            assertEquals(info.bitDepth(), planes.bitDepth(), resourceName + " bit depth");
            assertEquals(info.pixelFormat(), planes.pixelFormat(), resourceName + " pixel format");

            AvifDecodeException exception = assertThrows(AvifDecodeException.class, reader::readFrame);
            assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code(), resourceName + " error code");
        }
    }

    /// Decodes every presented frame and verifies its public layout information.
    ///
    /// @param resourceName the classpath resource name
    /// @throws IOException if the resource cannot be read or decoded
    private static void assertSuccessfulDecode(String resourceName) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.width() > 0, resourceName + " width");
            assertTrue(info.height() > 0, resourceName + " height");
            assertTrue(info.frameCount() > 0, resourceName + " frame count");

            int decodedFrameCount = 0;
            @Nullable AvifFrame frame;
            while ((frame = reader.readFrame()) != null) {
                assertFrameLayout(resourceName, info, decodedFrameCount, frame);
                decodedFrameCount++;
            }
            assertEquals(info.frameCount(), decodedFrameCount, resourceName + " decoded frame count");
        }
    }

    /// Verifies the observable layout of one decoded frame.
    ///
    /// @param resourceName the classpath resource name
    /// @param info the parsed image information
    /// @param frameIndex the expected zero-based frame index
    /// @param frame the decoded frame
    private static void assertFrameLayout(
            String resourceName,
            AvifImageInfo info,
            int frameIndex,
            AvifFrame frame
    ) {
        assertEquals(frameIndex, frame.frameIndex(), resourceName + " frame index");
        assertEquals(info.width(), frame.width(), resourceName + " frame width");
        assertEquals(info.height(), frame.height(), resourceName + " frame height");
        assertEquals(info.bitDepth(), frame.bitDepth(), resourceName + " bit depth");
        assertEquals(info.pixelFormat(), frame.pixelFormat(), resourceName + " pixel format");

        int pixelCount = Math.multiplyExact(frame.width(), frame.height());
        if (frame.bitDepth().isEightBit()) {
            assertEquals(pixelCount, frame.intPixelBuffer().remaining(), resourceName + " pixel count");
        } else {
            assertEquals(pixelCount, frame.longPixelBuffer().remaining(), resourceName + " pixel count");
        }
    }

    /// Counts resources contributed by one provider.
    ///
    /// @param resources the sorted corpus resource names
    /// @param provider the provider directory name
    /// @return the number of matching resources
    private static long countProviderResources(@Unmodifiable List<String> resources, String provider) {
        String prefix = TEST_DATA_ROOT + "/" + provider + "/";
        return resources.stream().filter(resourceName -> resourceName.startsWith(prefix)).count();
    }

    /// Returns the sorted classpath names of all copied AVIF and AVIFS resources.
    ///
    /// @return the copied corpus resource names
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static @Unmodifiable List<String> avifResourceNames() throws IOException, URISyntaxException {
        URL rootUrl = Objects.requireNonNull(
                AomAvifTestFilesCorpusTest.class.getClassLoader().getResource(TEST_DATA_ROOT),
                "Missing test resource root: " + TEST_DATA_ROOT
        );
        Path root = Path.of(rootUrl.toURI());
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(AomAvifTestFilesCorpusTest::isAvifResource)
                    .map(relativeName -> TEST_DATA_ROOT + "/" + relativeName.replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    /// Returns whether one relative resource name is an AVIF still image or sequence.
    ///
    /// @param resourceName the relative resource name
    /// @return whether the resource is an AVIF still image or sequence
    private static boolean isAvifResource(String resourceName) {
        return resourceName.endsWith(".avif") || resourceName.endsWith(".avifs");
    }
}
