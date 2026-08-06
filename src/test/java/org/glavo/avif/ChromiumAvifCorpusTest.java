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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compatibility tests derived from Chromium's Blink AVIF image-decoder tests.
///
/// Browser-specific incremental loading, histogram, Skia color-management, and task-pool behavior
/// are intentionally excluded. The pinned resources are downloaded and verified by the Gradle
/// `chromiumAvifTest` task.
@Tag("chromium-corpus")
@NotNullByDefault
final class ChromiumAvifCorpusTest {
    /// The classpath root containing the selected Chromium fixtures.
    private static final String TEST_DATA_ROOT = "chromium-avif-test-data/";

    /// Bit shifts for the alpha, red, green, and blue channels in packed ARGB pixels.
    private static final int @Unmodifiable [] CHANNEL_SHIFTS = {24, 16, 8, 0};

    /// Chromium's valid static fixtures that primarily exercise AV1 layering and AVIF grids.
    private static final @Unmodifiable List<String> VALID_COMPLEX_FIXTURES = List.of(
            "red-at-12-oclock-with-color-profile-lossy.avif",
            "red-at-12-oclock-with-color-profile-8bpc.avif",
            "red-at-12-oclock-with-color-profile-10bpc.avif",
            "red-at-12-oclock-with-color-profile-12bpc.avif",
            "tiger_3layer_1res.avif",
            "tiger_3layer_3res.avif",
            "tiger_420_8b_grid1x13.avif",
            "dice_444_10b_grid4x3.avif",
            "gracehopper_422_12b_grid2x4.avif"
    );

    /// Verifies Chromium's red YUV fixtures across sample depths, ranges, and chroma layouts.
    ///
    /// @return one dynamic test for every selected red fixture
    @TestFactory
    Stream<DynamicTest> redYuvFixturesDecodeWithExpectedLayoutAndColor() {
        return redFixtures().stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertRedFixture(testCase)
        ));
    }

    /// Verifies Chromium's alpha-compositing inputs without premultiplying their RGB channels.
    ///
    /// @return one dynamic test for every supported source bit depth
    @TestFactory
    Stream<DynamicTest> redAlphaFixturesPreserveStraightAlpha() {
        return Stream.of(8, 10, 12).map(bitDepth -> DynamicTest.dynamicTest(
                bitDepth + " bit",
                () -> assertRedAlphaFixture(bitDepth)
        ));
    }

    /// Verifies Chromium's standalone monochrome alpha-mask fixtures.
    ///
    /// @return one dynamic test for each source bit depth and range
    @TestFactory
    Stream<DynamicTest> alphaMaskFixturesDecodeAsMonochrome() {
        return Stream.of(8, 10, 12).flatMap(bitDepth -> Stream.of("limited", "full")
                .map(range -> DynamicTest.dynamicTest(
                        bitDepth + " bit " + range,
                        () -> assertAlphaMaskFixture(bitDepth, range)
                )));
    }

    /// Decodes Chromium's valid grid and scalable-image fixtures completely.
    ///
    /// @return one dynamic test for each selected complex fixture
    @TestFactory
    Stream<DynamicTest> validComplexFixturesDecodeCompletely() {
        return VALID_COMPLEX_FIXTURES.stream().map(resourceName -> DynamicTest.dynamicTest(
                resourceName,
                () -> assertStaticFixtureDecodes(resourceName)
        ));
    }

    /// Verifies Chromium's AVIF sequence frame counts, repetition counts, and timing metadata.
    ///
    /// @return one dynamic test for every selected animated fixture
    @TestFactory
    Stream<DynamicTest> animatedFixturesExposeFramesRepetitionAndTiming() {
        return animatedFixtures().stream().map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertAnimatedFixture(testCase)
        ));
    }

    /// Verifies the raw `irot` and `imir` properties used by Chromium's orientation cases.
    ///
    /// @return one dynamic test for every orientation-property combination
    @TestFactory
    Stream<DynamicTest> orientationFixturesExposeContainerTransforms() {
        return Stream.of(
                new OrientationFixture("red-full-range-angle-1-420-8bpc.avif", 1, -1),
                new OrientationFixture("red-full-range-mode-0-420-8bpc.avif", -1, 0),
                new OrientationFixture("red-full-range-mode-1-420-8bpc.avif", -1, 1),
                new OrientationFixture("red-full-range-angle-2-mode-0-420-8bpc.avif", 2, 0),
                new OrientationFixture("red-full-range-angle-3-mode-1-420-8bpc.avif", 3, 1)
        ).map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertOrientationFixture(testCase)
        ));
    }

    /// Verifies Chromium's zero-origin clean-aperture crop against its uncropped source image.
    ///
    /// @throws IOException if either fixture cannot be decoded
    @Test
    void zeroOriginCleanApertureProducesExpectedCrop() throws IOException {
        try (AvifImageReader croppedReader = open("red-and-purple-crop.avif");
             AvifImageReader sourceReader = open("red-and-purple-and-blue.avif")) {
            AvifImageInfo croppedInfo = croppedReader.info();
            AvifImageInfo sourceInfo = sourceReader.info();
            assertEquals(200, croppedInfo.width());
            assertEquals(50, croppedInfo.height());
            assertTrue(croppedInfo.hasCleanApertureCrop());
            assertEquals(300, sourceInfo.width());
            assertEquals(100, sourceInfo.height());

            AvifFrame cropped = croppedReader.readFrame(0);
            AvifFrame source = sourceReader.readFrame(0);
            int[] croppedPixels = cropped.intPixels();
            int[] sourcePixels = source.intPixels();
            for (int y = 0; y < cropped.height(); y++) {
                for (int x = 0; x < cropped.width(); x++) {
                    assertEquals(sourcePixels[y * source.width() + x], croppedPixels[y * cropped.width() + x],
                            "pixel (" + x + ", " + y + ")");
                }
            }
        }
    }

    /// Verifies the valid nonzero-origin crop and checked rejection of a malformed denominator.
    ///
    /// Chromium ignores both properties as a browser policy. This reader exposes valid arbitrary
    /// clean apertures through its public transform model and rejects the malformed property.
    ///
    /// @throws IOException if the valid fixture cannot be decoded
    @Test
    void nonzeroOriginCleanApertureIsExposedAndMalformedPropertyIsRejected() throws IOException {
        try (AvifImageReader reader = open("blue-and-magenta-crop.avif")) {
            AvifImageInfo info = reader.info();
            assertEquals(180, info.width());
            assertEquals(100, info.height());
            assertEquals(40, info.cleanApertureCropX());
            assertEquals(80, info.cleanApertureCropY());
            assertEquals(180, reader.readFrame(0).width());
        }
        assertDecodeFails("blue-and-magenta-crop-invalid.avif");
    }

    /// Verifies Chromium's valid ISO gain-map fixtures and their decodable auxiliary planes.
    ///
    /// @return one dynamic test for each valid selected gain-map fixture
    @TestFactory
    Stream<DynamicTest> gainMapFixturesExposeMetadataAndPlanes() {
        return Stream.of(
                new GainMapFixture("small-with-gainmap-iso.avif", 134, 100, 33, 25),
                new GainMapFixture("small-with-gainmap-iso-hdrbase.avif", 134, 100, 33, 25),
                new GainMapFixture("small-with-gainmap-iso-usealtcolorspace.avif", 134, 100, 33, 25),
                new GainMapFixture("small-with-gainmap-iso-usealtcolorspace-differenticc.avif", 134, 100, 33, 25),
                new GainMapFixture("hdr-base-with-yuv400-gainmap.avif", 400, 200, 400, 200),
                new GainMapFixture("gainmap-sdr-srgb-to-hdr-wcg-rec2020.avif", 200, 200, 200, 200)
        ).map(testCase -> DynamicTest.dynamicTest(
                testCase.resourceName(),
                () -> assertGainMapFixture(testCase)
        ));
    }

    /// Verifies that a zero-version ICC profile remains available to callers.
    ///
    /// @throws IOException if the fixture cannot be decoded
    @Test
    void zeroVersionIccProfileStillDecodes() throws IOException {
        try (AvifImageReader reader = open("red-icc-version-zero.avif")) {
            assertNotNull(reader.info().iccProfile());
            assertEquals(1, reader.readAllFrames().size());
        }
    }

    /// Verifies Chromium's synthetic monochrome and unspecified-color fixtures.
    ///
    /// @throws IOException if a fixture cannot be decoded
    @Test
    void additionalStaticColorFixturesMatchExpectedPixels() throws IOException {
        assertSamplePixelsNear("silver-full-range-srgb-420-8bpc.avif", 0xffc0c0c0, 1);
        assertSamplePixelsNear("silver-400-matrix-6.avif", 0xffc0c0c0, 1);
        assertSamplePixelsNear("silver-400-matrix-0.avif", 0xffc0c0c0, 1);
        assertSamplePixelsNear("red-full-range-unspecified-420-8bpc.avif", 0xffff0000, 3);
    }

    /// Verifies that unsupported browser color-management policy does not hide source metadata.
    ///
    /// This library returns straight sRGB-shaped sample conversion and does not apply transfer
    /// functions, so transfer characteristic 11 remains observable instead of rejecting the image.
    ///
    /// @throws IOException if the fixture cannot be decoded
    @Test
    void unsupportedBrowserTransferFunctionRemainsObservable() throws IOException {
        try (AvifImageReader reader = open("red-unsupported-transfer.avif")) {
            AvifColorInfo colorInfo = reader.info().colorInfo();
            assertNotNull(colorInfo);
            assertEquals(11, colorInfo.transferCharacteristics());
            assertPixelNear(0xffff0000, pixel(reader.readFrame(0), 1, 1), 3, "unsupported transfer");
        }
    }

    /// Verifies that invalid gain-map gamma metadata does not prevent base-image decoding.
    ///
    /// @throws IOException if the base image cannot be decoded
    @Test
    void invalidGainMapGammaIsNotExposed() throws IOException {
        try (AvifImageReader reader = open("small-with-gainmap-iso-gammazero.avif")) {
            assertNull(reader.info().gainMapInfo());
            assertEquals(1, reader.readAllFrames().size());
        }
    }

    /// Verifies the compatibility fallback for an alpha item that omits mandatory spatial extents.
    ///
    /// Chromium rejects this legacy input. This reader deliberately uses the associated master
    /// image dimensions, matching the compatibility behavior already covered by libavif fixtures.
    ///
    /// @throws IOException if the fixture cannot be decoded
    @Test
    void alphaWithoutIspeUsesMasterImageDimensions() throws IOException {
        try (AvifImageReader reader = open("green-no-alpha-ispe.avif")) {
            AvifImageInfo info = reader.info();
            assertTrue(info.alphaPresent());
            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            AvifFrame frame = reader.readFrame(0);
            assertEquals(info.width(), frame.width());
            assertEquals(info.height(), frame.height());
        }
    }

    /// Verifies Chromium's malformed container and AV1 payload fixtures are rejected.
    @Test
    void malformedFixturesAreRejected() {
        assertDecodeFails("red-at-12-oclock-with-color-profile-truncated.avif");
        assertDecodeFails("red-at-12-oclock-with-color-profile-with-wrong-frame-header.avif");
    }

    /// Verifies one red YUV fixture.
    ///
    /// @param testCase the expected fixture properties
    /// @throws IOException if the fixture cannot be decoded
    private static void assertRedFixture(RedFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(3, info.width());
            assertEquals(3, info.height());
            assertEquals(AvifBitDepth.fromBits(testCase.bitDepth()), info.bitDepth());
            assertEquals(testCase.chromaFormat(), info.chromaFormat());
            assertFalse(info.alphaPresent());
            AvifFrame frame = reader.readFrame(0);
            assertPixelNear(0xffff0000, pixel(frame, 0, 0), 3, testCase.resourceName());
            assertPixelNear(0xffff0000, pixel(frame, 1, 1), 3, testCase.resourceName());
            assertPixelNear(0xffff0000, pixel(frame, 2, 2), 3, testCase.resourceName());
        }
    }

    /// Verifies one straight-alpha red fixture.
    ///
    /// @param bitDepth the source sample bit depth
    /// @throws IOException if the fixture cannot be decoded
    private static void assertRedAlphaFixture(int bitDepth) throws IOException {
        String resourceName = "red-with-alpha-" + bitDepth + "bpc.avif";
        try (AvifImageReader reader = open(resourceName)) {
            AvifImageInfo info = reader.info();
            assertTrue(info.alphaPresent());
            assertFalse(info.alphaPremultiplied());
            assertEquals(AvifBitDepth.fromBits(bitDepth), info.bitDepth());
            AvifFrame frame = reader.readFrame(0);
            int middleAlpha = bitDepth == 8 ? 127 : 128;
            assertPixelNear(0x00ff0000, pixel(frame, 0, 0), 3, resourceName);
            assertPixelNear(argb(middleAlpha, 255, 0, 0), pixel(frame, 1, 1), 3, resourceName);
            assertPixelNear(0xffff0000, pixel(frame, 2, 2), 3, resourceName);
        }
    }

    /// Verifies one standalone monochrome alpha-mask fixture.
    ///
    /// @param bitDepth the source sample bit depth
    /// @param range the fixture range label
    /// @throws IOException if the fixture cannot be decoded
    private static void assertAlphaMaskFixture(int bitDepth, String range) throws IOException {
        String resourceName = "alpha-mask-" + range + "-range-" + bitDepth + "bpc.avif";
        try (AvifImageReader reader = open(resourceName)) {
            AvifImageInfo info = reader.info();
            assertEquals(Av1ChromaFormat.MONOCHROME, info.chromaFormat());
            assertFalse(info.alphaPresent());
            AvifFrame frame = reader.readFrame(0);
            assertPixelNear(0xff000000, pixel(frame, 0, 0), 2, resourceName);
            assertPixelNear(0xff808080, pixel(frame, 1, 1), 2, resourceName);
            assertPixelNear(0xffffffff, pixel(frame, 2, 2), 2, resourceName);
        }
    }

    /// Decodes one valid static fixture and verifies its public frame layout.
    ///
    /// @param resourceName the fixture name
    /// @throws IOException if the fixture cannot be decoded
    private static void assertStaticFixtureDecodes(String resourceName) throws IOException {
        try (AvifImageReader reader = open(resourceName)) {
            AvifImageInfo info = reader.info();
            assertFalse(info.animated());
            assertEquals(1, info.frameCount());
            AvifFrame frame = reader.readFrame(0);
            assertEquals(info.width(), frame.width());
            assertEquals(info.height(), frame.height());
            assertEquals(Math.multiplyExact(frame.width(), frame.height()), frame.intPixelBuffer().remaining());
        }
    }

    /// Verifies one animated Chromium fixture.
    ///
    /// @param testCase the expected sequence properties
    /// @throws IOException if the fixture cannot be decoded
    private static void assertAnimatedFixture(AnimatedFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertTrue(info.animated());
            assertEquals(5, info.frameCount());
            assertEquals(testCase.repetitionCount(), info.repetitionCount());
            assertEquals(testCase.alphaPresent(), info.alphaPresent());
            assertEquals(5, info.frameDurations().length);
            for (int duration : info.frameDurations()) {
                assertTrue(duration > 0);
            }
            @Unmodifiable List<AvifFrame> frames = reader.readAllFrames();
            assertEquals(5, frames.size());
            if (testCase.alphaPresent()) {
                assertTrue(hasNonOpaquePixel(frames.get(0)), testCase.resourceName());
            }
        }
    }

    /// Returns whether one frame contains a pixel whose alpha is not fully opaque.
    ///
    /// @param frame the decoded frame
    /// @return whether a non-opaque pixel is present
    private static boolean hasNonOpaquePixel(AvifFrame frame) {
        for (int pixel : frame.intPixels()) {
            if ((pixel >>> 24) != 0xff) {
                return true;
            }
        }
        return false;
    }

    /// Verifies one orientation fixture's raw item properties.
    ///
    /// @param testCase the expected transform codes
    /// @throws IOException if the fixture cannot be decoded
    private static void assertOrientationFixture(OrientationFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(testCase.rotationCode(), info.rotationCode());
            assertEquals(testCase.mirrorAxis(), info.mirrorAxis());
            assertNotNull(info.transformInfo());
            assertEquals(1, reader.readAllFrames().size());
        }
    }

    /// Verifies one gain-map fixture's metadata and decoded plane layout.
    ///
    /// @param testCase the expected gain-map layout
    /// @throws IOException if the fixture cannot be decoded
    private static void assertGainMapFixture(GainMapFixture testCase) throws IOException {
        try (AvifImageReader reader = open(testCase.resourceName())) {
            AvifImageInfo info = reader.info();
            assertEquals(testCase.baseWidth(), info.width());
            assertEquals(testCase.baseHeight(), info.height());
            AvifGainMapInfo gainMapInfo = info.gainMapInfo();
            assertNotNull(gainMapInfo);
            assertTrue(gainMapInfo.metadataSupported());
            assertNotNull(gainMapInfo.metadata());
            assertEquals(testCase.gainMapWidth(), gainMapInfo.gainMapWidth());
            assertEquals(testCase.gainMapHeight(), gainMapInfo.gainMapHeight());
            AvifPlanes gainMap = reader.readRawGainMapPlanes(0);
            assertNotNull(gainMap);
            assertEquals(testCase.gainMapWidth(), gainMap.codedWidth());
            assertEquals(testCase.gainMapHeight(), gainMap.codedHeight());
        }
    }

    /// Asserts that parsing or decoding one malformed fixture fails through the checked API.
    ///
    /// @param resourceName the malformed fixture name
    private static void assertDecodeFails(String resourceName) {
        assertThrows(IOException.class, () -> {
            try (AvifImageReader reader = open(resourceName)) {
                reader.readAllFrames();
            }
        });
    }

    /// Returns one pixel from a decoded frame.
    ///
    /// @param frame the decoded frame
    /// @param x the pixel x coordinate
    /// @param y the pixel y coordinate
    /// @return the packed straight-alpha ARGB pixel
    private static int pixel(AvifFrame frame, int x, int y) {
        return frame.intPixelBuffer().get(y * frame.width() + x);
    }

    /// Verifies three diagonal pixels in one 3-by-3 synthetic fixture.
    ///
    /// @param resourceName the fixture name
    /// @param expectedArgb the expected packed ARGB color
    /// @param tolerance the permitted difference in each channel
    /// @throws IOException if the fixture cannot be decoded
    private static void assertSamplePixelsNear(String resourceName, int expectedArgb, int tolerance)
            throws IOException {
        try (AvifImageReader reader = open(resourceName)) {
            AvifFrame frame = reader.readFrame(0);
            assertEquals(3, frame.width());
            assertEquals(3, frame.height());
            assertPixelNear(expectedArgb, pixel(frame, 0, 0), tolerance, resourceName);
            assertPixelNear(expectedArgb, pixel(frame, 1, 1), tolerance, resourceName);
            assertPixelNear(expectedArgb, pixel(frame, 2, 2), tolerance, resourceName);
        }
    }

    /// Verifies each channel of one packed pixel.
    ///
    /// @param expectedArgb the expected packed ARGB color
    /// @param actualArgb the actual packed ARGB color
    /// @param tolerance the permitted difference in each channel
    /// @param message the assertion context
    private static void assertPixelNear(int expectedArgb, int actualArgb, int tolerance, String message) {
        for (int shift : CHANNEL_SHIFTS) {
            int expected = expectedArgb >>> shift & 0xff;
            int actual = actualArgb >>> shift & 0xff;
            assertTrue(Math.abs(expected - actual) <= tolerance,
                    () -> message + " channel " + shift + ": expected " + expected + " but was " + actual);
        }
    }

    /// Opens one Chromium AVIF fixture.
    ///
    /// @param resourceName the fixture name
    /// @return a reader for the fixture
    /// @throws IOException if the fixture cannot be read or parsed
    private static AvifImageReader open(String resourceName) throws IOException {
        return AvifImageReader.open(TestResources.readBytes(TEST_DATA_ROOT + resourceName));
    }

    /// Creates Chromium's red YUV fixture matrix.
    ///
    /// @return the immutable red-fixture matrix
    private static @Unmodifiable List<RedFixture> redFixtures() {
        List<RedFixture> fixtures = new ArrayList<>();
        for (int bitDepth : new int[]{8, 10, 12}) {
            fixtures.add(new RedFixture(
                    "red-full-range-420-" + bitDepth + "bpc.avif",
                    bitDepth,
                    Av1ChromaFormat.YUV420
            ));
            for (Av1ChromaFormat chromaFormat : List.of(
                    Av1ChromaFormat.YUV420,
                    Av1ChromaFormat.YUV422,
                    Av1ChromaFormat.YUV444
            )) {
                String chroma = chromaFormat.name().substring(3);
                fixtures.add(new RedFixture(
                        "red-limited-range-" + chroma + "-" + bitDepth + "bpc.avif",
                        bitDepth,
                        chromaFormat
                ));
            }
        }
        fixtures.add(new RedFixture("red-full-range-bt709-444-8bpc.avif", 8, Av1ChromaFormat.YUV444));
        fixtures.add(new RedFixture("red-full-range-bt2020-pq-444-10bpc.avif", 10, Av1ChromaFormat.YUV444));
        fixtures.add(new RedFixture("red-full-range-bt2020-pq-444-12bpc.avif", 12, Av1ChromaFormat.YUV444));
        fixtures.add(new RedFixture("red-full-range-bt2020-hlg-444-10bpc.avif", 10, Av1ChromaFormat.YUV444));
        fixtures.add(new RedFixture("red-full-range-bt2020-hlg-444-12bpc.avif", 12, Av1ChromaFormat.YUV444));
        return List.copyOf(fixtures);
    }

    /// Creates Chromium's animated fixture matrix.
    ///
    /// @return the immutable animated-fixture matrix
    private static @Unmodifiable List<AnimatedFixture> animatedFixtures() {
        List<AnimatedFixture> fixtures = new ArrayList<>();
        for (int bitDepth : new int[]{8, 10, 12}) {
            fixtures.add(new AnimatedFixture("star-animated-" + bitDepth + "bpc.avif", 0, false));
            fixtures.add(new AnimatedFixture(
                    "star-animated-" + bitDepth + "bpc-with-alpha.avif",
                    AvifImageInfo.REPETITION_COUNT_UNKNOWN,
                    true
            ));
        }
        fixtures.add(new AnimatedFixture("star-animated-8bpc-1-repetition.avif", 1, false));
        fixtures.add(new AnimatedFixture("star-animated-8bpc-10-repetition.avif", 10, false));
        fixtures.add(new AnimatedFixture(
                "star-animated-8bpc-infinite-repetition.avif",
                AvifImageInfo.REPETITION_COUNT_INFINITE,
                false
        ));
        return List.copyOf(fixtures);
    }

    /// Packs four unsigned channels into an ARGB pixel.
    ///
    /// @param alpha the alpha channel
    /// @param red the red channel
    /// @param green the green channel
    /// @param blue the blue channel
    /// @return the packed ARGB pixel
    private static int argb(int alpha, int red, int green, int blue) {
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /// Describes one Chromium red YUV fixture.
    ///
    /// @param resourceName the fixture name
    /// @param bitDepth the expected source bit depth
    /// @param chromaFormat the expected chroma layout
    private record RedFixture(String resourceName, int bitDepth, Av1ChromaFormat chromaFormat) {
    }

    /// Describes one Chromium animated fixture.
    ///
    /// @param resourceName the fixture name
    /// @param repetitionCount the expected repetition count
    /// @param alphaPresent whether the sequence has an alpha auxiliary track
    private record AnimatedFixture(String resourceName, int repetitionCount, boolean alphaPresent) {
    }

    /// Describes one Chromium orientation fixture.
    ///
    /// @param resourceName the fixture name
    /// @param rotationCode the expected raw `irot` code, or -1
    /// @param mirrorAxis the expected raw `imir` axis, or -1
    private record OrientationFixture(String resourceName, int rotationCode, int mirrorAxis) {
    }

    /// Describes one Chromium ISO gain-map fixture.
    ///
    /// @param resourceName the fixture name
    /// @param baseWidth the expected base-image width
    /// @param baseHeight the expected base-image height
    /// @param gainMapWidth the expected gain-map width
    /// @param gainMapHeight the expected gain-map height
    private record GainMapFixture(
            String resourceName,
            int baseWidth,
            int baseHeight,
            int gainMapWidth,
            int gainMapHeight
    ) {
    }
}
