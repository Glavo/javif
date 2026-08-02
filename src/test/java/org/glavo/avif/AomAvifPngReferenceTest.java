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

import org.glavo.avif.testutil.ImagePixelAssertions;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelTolerance;
import org.glavo.avif.testutil.ImagePixelAssertions.PixelTransform;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.awt.image.BufferedImage;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compares AOMedia AVIF decoding results with the PNG references shipped beside the corpus.
///
/// The Netflix references were produced by `avifdec`, the Link-U references describe the final
/// display result after item transforms, and the Xiph references describe selected scalable layers.
@Tag("aom-corpus")
@NotNullByDefault
final class AomAvifPngReferenceTest {
    /// The root containing the selected files from AOMedia's `testFiles` directory.
    private static final String TEST_DATA_ROOT = "aom-av1-avif-test-data";

    /// The Netflix directory containing adjacent AVIF/PNG decode pairs.
    private static final String NETFLIX_ROOT = TEST_DATA_ROOT + "/Netflix/avif";

    /// Tolerance for Netflix PNGs produced by libavif's decoder and RGB conversion pipeline.
    private static final PixelTolerance NETFLIX_REFERENCE_TOLERANCE =
            PixelTolerance.bounded(16, 0.006, 2.5, 4.0);

    /// Tolerance for Xiph layer PNGs after decoder-specific YUV-to-RGB conversion.
    private static final PixelTolerance XIPH_REFERENCE_TOLERANCE =
            PixelTolerance.bounded(4, 0.02, 0.5, 1.0);

    /// Tolerance for lossy Link-U images compared with their documented source appearance.
    private static final PixelTolerance LINK_U_DISPLAY_TOLERANCE =
            PixelTolerance.bounded(24, 0.01, 6.0, 8.0);

    /// Link-U final-display references after clean-aperture, rotation, and mirror transforms.
    private static final PngReference @Unmodifiable [] LINK_U_DISPLAY_REFERENCES = new PngReference[]{
            reference("Link-U/kimono.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.rotate90.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.rotate270.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.mirror-horizontal.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.mirror-vertical.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.mirror-vertical.rotate270.avif", "Link-U/kimono.png", LINK_U_DISPLAY_TOLERANCE),
            reference("Link-U/kimono.crop.avif", "Link-U/kimono.crop.png", LINK_U_DISPLAY_TOLERANCE),
            reference(
                    "Link-U/kimono.mirror-vertical.rotate270.crop.avif",
                    "Link-U/kimono.crop.png",
                    LINK_U_DISPLAY_TOLERANCE
            ),
    };

    /// Xiph AVIF resources and the PNG corresponding to each container-selected output layer.
    private static final PngReference @Unmodifiable [] XIPH_SELECTED_LAYER_REFERENCES = new PngReference[]{
            reference(
                    "Xiph/abandoned_filmgrain.avif",
                    "Xiph/abandoned_filmgrain_layer1.png",
                    XIPH_REFERENCE_TOLERANCE
            ),
            reference(
                    "Xiph/fruits_2layer_thumbsize.avif",
                    "Xiph/fruits_2layer_thumbsize_layer1.png",
                    XIPH_REFERENCE_TOLERANCE
            ),
            reference(
                    "Xiph/quebec_3layer_op2.avif",
                    "Xiph/quebec_3layer_op2_layer0.png",
                    XIPH_REFERENCE_TOLERANCE
            ),
            reference(
                    "Xiph/tiger_3layer_1res.avif",
                    "Xiph/tiger_3layer_1res_layer2.png",
                    XIPH_REFERENCE_TOLERANCE
            ),
            reference(
                    "Xiph/tiger_3layer_3res.avif",
                    "Xiph/tiger_3layer_3res_layer2.png",
                    XIPH_REFERENCE_TOLERANCE
            ),
    };

    /// Verifies that the resource extraction retains every selected PNG reference and omits source originals.
    ///
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @Test
    void corpusContainsExpectedPngReferences() throws IOException, URISyntaxException {
        @Unmodifiable List<String> resources = pngResourceNames();
        assertEquals(82, resources.size(), "total PNG reference count");
        assertEquals(13, countProviderResources(resources, "Link-U"), "Link-U PNG reference count");
        assertEquals(56, countProviderResources(resources, "Netflix"), "Netflix PNG reference count");
        assertEquals(13, countProviderResources(resources, "Xiph"), "Xiph PNG reference count");
        assertTrue(
                resources.stream().noneMatch(resourceName -> resourceName.contains("/original_")),
                "Netflix source originals must not be extracted"
        );
    }

    /// Creates one comparison for each adjacent Netflix AVIF/PNG decode pair.
    ///
    /// @return the dynamic Netflix PNG reference tests
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @TestFactory
    Stream<DynamicTest> netflixAvifsMatchAdjacentDecodedPngs() throws IOException, URISyntaxException {
        return pngResourceNames().stream()
                .filter(resourceName -> resourceName.startsWith(NETFLIX_ROOT + "/"))
                .map(resourceName -> new PngReference(
                        replaceExtension(resourceName, ".avif"),
                        resourceName,
                        NETFLIX_REFERENCE_TOLERANCE
                ))
                .map(AomAvifPngReferenceTest::dynamicReferenceTest);
    }

    /// Creates comparisons for Link-U's documented final display results.
    ///
    /// @return the dynamic Link-U transform reference tests
    @TestFactory
    Stream<DynamicTest> linkUTransformsMatchDocumentedDisplayPngs() {
        return Stream.of(LINK_U_DISPLAY_REFERENCES).map(AomAvifPngReferenceTest::dynamicReferenceTest);
    }

    /// Creates comparisons for the output layer selected by each Xiph AVIF container.
    ///
    /// @return the dynamic Xiph selected-layer reference tests
    @TestFactory
    Stream<DynamicTest> xiphAvifsMatchSelectedLayerPngs() {
        return Stream.of(XIPH_SELECTED_LAYER_REFERENCES).map(AomAvifPngReferenceTest::dynamicReferenceTest);
    }

    /// Creates one dynamic PNG reference test.
    ///
    /// @param reference the AVIF/PNG reference relationship
    /// @return the dynamic test
    private static DynamicTest dynamicReferenceTest(PngReference reference) {
        return DynamicTest.dynamicTest(
                reference.avifResource() + " -> " + reference.pngResource(),
                () -> assertMatchesPngReference(reference)
        );
    }

    /// Decodes one AVIF and compares its only presented frame with a PNG reference.
    ///
    /// @param reference the AVIF/PNG reference relationship
    /// @throws IOException if either resource cannot be read or decoded
    private static void assertMatchesPngReference(PngReference reference) throws IOException {
        BufferedImage expected = TestResources.readImage(reference.pngResource());
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(reference.avifResource()))) {
            AvifFrame actual = reader.readFrame();
            assertNotNull(actual, reference.avifResource());
            assertEquals(expected.getWidth(), actual.width(), reference.avifResource() + " width");
            assertEquals(expected.getHeight(), actual.height(), reference.avifResource() + " height");

            if (actual.bitDepth().isEightBit()) {
                ImagePixelAssertions.assertIntPixelsMatch(
                        reference.avifResource(),
                        expected,
                        actual.intPixelBuffer(),
                        actual.width(),
                        actual.height(),
                        PixelTransform.IDENTITY,
                        reference.tolerance()
                );
            } else {
                ImagePixelAssertions.assertLongPixelsMatch8BitReference(
                        reference.avifResource(),
                        expected,
                        actual.longPixelBuffer(),
                        actual.width(),
                        actual.height(),
                        PixelTransform.IDENTITY,
                        reference.tolerance()
                );
            }
            assertNull(reader.readFrame(), reference.avifResource() + " additional frame");
        }
    }

    /// Creates a classpath-relative AVIF/PNG reference relationship.
    ///
    /// @param avifRelativeName the AVIF path relative to the AOM test-data root
    /// @param pngRelativeName the PNG path relative to the AOM test-data root
    /// @param tolerance the accepted pixel tolerance
    /// @return the reference relationship
    private static PngReference reference(
            String avifRelativeName,
            String pngRelativeName,
            PixelTolerance tolerance
    ) {
        return new PngReference(
                TEST_DATA_ROOT + "/" + avifRelativeName,
                TEST_DATA_ROOT + "/" + pngRelativeName,
                tolerance
        );
    }

    /// Replaces the extension of one resource name.
    ///
    /// @param resourceName the resource name with an extension
    /// @param replacementExtension the replacement extension including its leading period
    /// @return the resource name with the replacement extension
    private static String replaceExtension(String resourceName, String replacementExtension) {
        int extensionIndex = resourceName.lastIndexOf('.');
        if (extensionIndex < 0) {
            throw new IllegalArgumentException("Resource has no extension: " + resourceName);
        }
        return resourceName.substring(0, extensionIndex) + replacementExtension;
    }

    /// Counts resources contributed by one provider.
    ///
    /// @param resources the sorted PNG resource names
    /// @param provider the provider directory name
    /// @return the number of matching resources
    private static long countProviderResources(@Unmodifiable List<String> resources, String provider) {
        String prefix = TEST_DATA_ROOT + "/" + provider + "/";
        return resources.stream().filter(resourceName -> resourceName.startsWith(prefix)).count();
    }

    /// Returns the sorted classpath names of all selected PNG references.
    ///
    /// @return the copied PNG resource names
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static @Unmodifiable List<String> pngResourceNames() throws IOException, URISyntaxException {
        URL resource = AomAvifPngReferenceTest.class.getClassLoader().getResource(TEST_DATA_ROOT);
        if (resource == null) {
            throw new AssertionError("Missing test resource directory: " + TEST_DATA_ROOT);
        }
        Path root = Path.of(resource.toURI());
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .map(path -> TEST_DATA_ROOT + "/" + root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /// One AVIF and PNG relationship with its accepted comparison tolerance.
    ///
    /// @param avifResource the AVIF classpath resource name
    /// @param pngResource the PNG classpath resource name
    /// @param tolerance the accepted pixel tolerance
    @NotNullByDefault
    private record PngReference(String avifResource, String pngResource, PixelTolerance tolerance) {
        /// Creates a validated AVIF/PNG relationship.
        private PngReference {
            Objects.requireNonNull(avifResource, "avifResource");
            Objects.requireNonNull(pngResource, "pngResource");
            Objects.requireNonNull(tolerance, "tolerance");
        }
    }
}
