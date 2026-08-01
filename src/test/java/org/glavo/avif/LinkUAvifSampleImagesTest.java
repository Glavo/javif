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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Corpus tests for the AVIF files published by `link-u/avif-sample-images`.
@NotNullByDefault
final class LinkUAvifSampleImagesTest {
    /// The copied sample-image resource root.
    private static final String TEST_DATA_ROOT = "link-u-avif-sample-images";

    /// Sequence samples whose dependent frames require unsupported reference-frame MV projection.
    private static final @Unmodifiable Set<String> UNSUPPORTED_REFERENCE_FRAME_MV_RESOURCES = Set.of(
            "link-u-avif-sample-images/star-8bpc.avifs",
            "link-u-avif-sample-images/star-8bpc-with-alpha.avifs",
            "link-u-avif-sample-images/star-10bpc.avifs",
            "link-u-avif-sample-images/star-10bpc-with-alpha.avifs",
            "link-u-avif-sample-images/star-12bpc.avifs",
            "link-u-avif-sample-images/star-12bpc-with-alpha.avifs"
    );

    /// Creates one decode-behavior test for every copied AVIF or AVIFS resource.
    ///
    /// @return the dynamic sample-image tests
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    @TestFactory
    Stream<DynamicTest> sampleImagesMatchExpectedDecodeBehavior() throws IOException, URISyntaxException {
        return sampleResourceNames().stream()
                .map(resourceName -> DynamicTest.dynamicTest(resourceName, () -> assertDecodeBehavior(resourceName)));
    }

    /// Verifies successful full decoding or the explicit unsupported-feature boundary for one sample.
    ///
    /// @param resourceName the classpath sample resource name
    /// @throws IOException if the sample cannot be read or decoded
    private static void assertDecodeBehavior(String resourceName) throws IOException {
        try (AvifImageReader reader = AvifImageReader.open(TestResources.readBytes(resourceName))) {
            AvifImageInfo info = reader.info();
            assertTrue(info.width() > 0, "width");
            assertTrue(info.height() > 0, "height");

            if (UNSUPPORTED_REFERENCE_FRAME_MV_RESOURCES.contains(resourceName)) {
                AvifFrame firstFrame = Objects.requireNonNull(reader.readFrame(), "firstFrame");
                assertFrameLayout(firstFrame, 0);

                AvifDecodeException exception = assertThrows(AvifDecodeException.class, reader::readAllFrames);
                assertEquals(AvifErrorCode.UNSUPPORTED_FEATURE, exception.code());
                assertEquals("Reference-frame motion-vector projection is not implemented", exception.getMessage());
                return;
            }

            @Unmodifiable List<AvifFrame> frames = reader.readAllFrames();
            assertEquals(info.frameCount(), frames.size(), "frame count");
            for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
                assertFrameLayout(frames.get(frameIndex), frameIndex);
            }
        }
    }

    /// Verifies the observable layout of one decoded frame.
    ///
    /// @param frame the decoded frame
    /// @param frameIndex the expected zero-based frame index
    private static void assertFrameLayout(AvifFrame frame, int frameIndex) {
        assertEquals(frameIndex, frame.frameIndex(), "frame index");
        assertTrue(frame.width() > 0, "frame width");
        assertTrue(frame.height() > 0, "frame height");
        int pixelCount = Math.multiplyExact(frame.width(), frame.height());
        if (frame.bitDepth().isEightBit()) {
            assertEquals(pixelCount, frame.intPixelBuffer().remaining(), "pixel count");
        } else {
            assertEquals(pixelCount, frame.longPixelBuffer().remaining(), "pixel count");
        }
    }

    /// Returns the sorted classpath names of all copied AVIF and AVIFS samples.
    ///
    /// @return the copied sample resource names
    /// @throws IOException if the resource directory cannot be walked
    /// @throws URISyntaxException if the resource root URL is invalid
    private static @Unmodifiable List<String> sampleResourceNames() throws IOException, URISyntaxException {
        URL rootUrl = Objects.requireNonNull(
                LinkUAvifSampleImagesTest.class.getClassLoader().getResource(TEST_DATA_ROOT),
                "Missing test resource root: " + TEST_DATA_ROOT
        );
        Path root = Path.of(rootUrl.toURI());
        try (Stream<Path> paths = Files.walk(root)) {
            @Unmodifiable List<String> resourceNames = paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(LinkUAvifSampleImagesTest::isAvifResource)
                    .map(relativeName -> TEST_DATA_ROOT + "/" + relativeName.replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            assertEquals(156, resourceNames.size(), "sample resource count");
            return resourceNames;
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
