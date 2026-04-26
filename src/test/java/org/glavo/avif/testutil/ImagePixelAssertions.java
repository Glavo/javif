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
package org.glavo.avif.testutil;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Assertions;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;
import java.util.Objects;

/// Assertion helpers for comparing decoded AVIF pixels against ImageIO-readable reference images.
@NotNullByDefault
public final class ImagePixelAssertions {
    /// Prevents instantiation.
    private ImagePixelAssertions() {
    }

    /// Asserts that 8-bit packed ARGB pixels match a reference image.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    public static void assertIntPixelsMatch(
            String label,
            BufferedImage expected,
            @UnmodifiableView IntBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        assertIntPixelsMatch(
                label,
                expected,
                actualPixels,
                actualWidth,
                actualHeight,
                PixelRegion.full(actualWidth, actualHeight),
                transform,
                tolerance
        );
    }

    /// Asserts that one 8-bit packed ARGB pixel region matches a reference image.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    public static void assertIntPixelsMatch(
            String label,
            BufferedImage expected,
            @UnmodifiableView IntBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        PixelComparisonResult result = compareIntPixels(
                label,
                expected,
                actualPixels,
                actualWidth,
                actualHeight,
                actualRegion,
                transform,
                tolerance
        );
        if (!result.matches(tolerance)) {
            Assertions.fail(result.failureMessage(label, tolerance));
        }
    }

    /// Asserts that 16-bit packed ARGB pixels match an 8-bit reference image after narrowing.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed 16-bit ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted 8-bit-domain pixel tolerance
    public static void assertLongPixelsMatch8BitReference(
            String label,
            BufferedImage expected,
            @UnmodifiableView LongBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        assertLongPixelsMatch8BitReference(
                label,
                expected,
                actualPixels,
                actualWidth,
                actualHeight,
                PixelRegion.full(actualWidth, actualHeight),
                transform,
                tolerance
        );
    }

    /// Asserts that one 16-bit packed ARGB pixel region matches an 8-bit reference image after narrowing.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed 16-bit ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted 8-bit-domain pixel tolerance
    public static void assertLongPixelsMatch8BitReference(
            String label,
            BufferedImage expected,
            @UnmodifiableView LongBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        PixelComparisonResult result = compareLongPixelsAs8Bit(
                label,
                expected,
                actualPixels,
                actualWidth,
                actualHeight,
                actualRegion,
                transform,
                tolerance
        );
        if (!result.matches(tolerance)) {
            Assertions.fail(result.failureMessage(label, tolerance));
        }
    }

    /// Compares 8-bit packed ARGB pixels against a reference image.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    /// @return the comparison result
    public static PixelComparisonResult compareIntPixels(
            String label,
            BufferedImage expected,
            @UnmodifiableView IntBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        validateInputs(expected, actualPixels.remaining(), actualWidth, actualHeight, actualRegion, transform, tolerance);
        return comparePixels(label, expected, actualWidth, actualHeight, actualRegion, transform, tolerance,
                index -> actualPixels.get(index));
    }

    /// Compares 16-bit packed ARGB pixels against an 8-bit reference image after narrowing.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixels the actual packed 16-bit ARGB pixels
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted 8-bit-domain pixel tolerance
    /// @return the comparison result
    public static PixelComparisonResult compareLongPixelsAs8Bit(
            String label,
            BufferedImage expected,
            @UnmodifiableView LongBuffer actualPixels,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        validateInputs(expected, actualPixels.remaining(), actualWidth, actualHeight, actualRegion, transform, tolerance);
        return comparePixels(label, expected, actualWidth, actualHeight, actualRegion, transform, tolerance,
                index -> narrowArgb64(actualPixels.get(index)));
    }

    /// Compares packed ARGB pixels supplied by one index-addressable source.
    ///
    /// @param label the diagnostic label for failure messages
    /// @param expected the expected ImageIO-decoded image
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    /// @param actualPixelSource the actual pixel source
    /// @return the comparison result
    private static PixelComparisonResult comparePixels(
            String label,
            BufferedImage expected,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance,
            ActualPixelSource actualPixelSource
    ) {
        int largestChannelDelta = 0;
        int failingPixels = 0;
        long sumDelta = 0L;
        long sumSquaredDelta = 0L;
        @Nullable String firstMismatch = null;

        int maxY = actualRegion.y() + actualRegion.height();
        int maxX = actualRegion.x() + actualRegion.width();
        for (int actualY = actualRegion.y(); actualY < maxY; actualY++) {
            for (int actualX = actualRegion.x(); actualX < maxX; actualX++) {
                int expectedX = transform.expectedX(expected.getWidth(), expected.getHeight(), actualX, actualY);
                int expectedY = transform.expectedY(expected.getWidth(), expected.getHeight(), actualX, actualY);
                int expectedArgb = expected.getRGB(expectedX, expectedY);
                int actualArgb = actualPixelSource.argb(actualY * actualWidth + actualX);
                int delta = displayRelevantChannelDelta(expectedArgb, actualArgb);

                largestChannelDelta = Math.max(largestChannelDelta, delta);
                sumDelta += delta;
                sumSquaredDelta += (long) delta * delta;
                if (delta > tolerance.maxChannelDelta()) {
                    failingPixels++;
                    if (firstMismatch == null) {
                        firstMismatch = "first mismatch at actual (" + actualX + ", " + actualY + "), expected ("
                                + expectedX + ", " + expectedY + "): expected " + argbText(expectedArgb)
                                + ", actual " + argbText(actualArgb)
                                + ", max channel delta " + delta;
                    }
                }
            }
        }

        int pixelCount = actualRegion.width() * actualRegion.height();
        double meanDelta = pixelCount == 0 ? 0.0 : (double) sumDelta / pixelCount;
        double rootMeanSquareDelta = pixelCount == 0 ? 0.0 : Math.sqrt((double) sumSquaredDelta / pixelCount);
        return new PixelComparisonResult(
                pixelCount,
                failingPixels,
                largestChannelDelta,
                meanDelta,
                rootMeanSquareDelta,
                firstMismatch == null ? "all pixels within per-pixel tolerance for " + label : firstMismatch
        );
    }

    /// Validates shared comparison inputs.
    ///
    /// @param expected the expected ImageIO-decoded image
    /// @param actualPixelCount the available actual pixel count
    /// @param actualWidth the actual image width
    /// @param actualHeight the actual image height
    /// @param actualRegion the actual output region to compare
    /// @param transform the coordinate transform from actual output pixels to expected source pixels
    /// @param tolerance the accepted pixel tolerance
    private static void validateInputs(
            BufferedImage expected,
            int actualPixelCount,
            int actualWidth,
            int actualHeight,
            PixelRegion actualRegion,
            PixelTransform transform,
            PixelTolerance tolerance
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actualRegion, "actualRegion");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(tolerance, "tolerance");
        Assertions.assertEquals(transform.expectedImageWidth(actualWidth, actualHeight), expected.getWidth());
        Assertions.assertEquals(transform.expectedImageHeight(actualWidth, actualHeight), expected.getHeight());
        Assertions.assertEquals(actualWidth * actualHeight, actualPixelCount);
        if (!actualRegion.fits(actualWidth, actualHeight)) {
            throw new AssertionError("Pixel comparison region does not fit actual image: " + actualRegion
                    + " inside " + actualWidth + "x" + actualHeight);
        }
    }

    /// Returns the largest display-relevant unsigned 8-bit channel delta between two packed ARGB pixels.
    ///
    /// @param expectedArgb the expected packed ARGB pixel
    /// @param actualArgb the actual packed ARGB pixel
    /// @return the largest display-relevant channel delta
    private static int displayRelevantChannelDelta(int expectedArgb, int actualArgb) {
        int alphaDelta = channelDelta(expectedArgb, actualArgb, 24);
        if (((expectedArgb >>> 24) & 0xFF) == 0 && ((actualArgb >>> 24) & 0xFF) == 0) {
            return alphaDelta;
        }

        return Math.max(
                Math.max(alphaDelta, channelDelta(expectedArgb, actualArgb, 16)),
                Math.max(channelDelta(expectedArgb, actualArgb, 8), channelDelta(expectedArgb, actualArgb, 0))
        );
    }

    /// Returns one unsigned 8-bit channel delta between two packed ARGB pixels.
    ///
    /// @param expectedArgb the expected packed ARGB pixel
    /// @param actualArgb the actual packed ARGB pixel
    /// @param shift the channel bit shift
    /// @return the unsigned 8-bit channel delta
    private static int channelDelta(int expectedArgb, int actualArgb, int shift) {
        return Math.abs(((expectedArgb >>> shift) & 0xFF) - ((actualArgb >>> shift) & 0xFF));
    }

    /// Narrows one packed 16-bit ARGB pixel to 8-bit packed ARGB.
    ///
    /// @param argb64 the packed 16-bit ARGB pixel
    /// @return the narrowed packed 8-bit ARGB pixel
    private static int narrowArgb64(long argb64) {
        return (narrow16To8((int) ((argb64 >>> 48) & 0xFFFFL)) << 24)
                | (narrow16To8((int) ((argb64 >>> 32) & 0xFFFFL)) << 16)
                | (narrow16To8((int) ((argb64 >>> 16) & 0xFFFFL)) << 8)
                | narrow16To8((int) (argb64 & 0xFFFFL));
    }

    /// Narrows one unsigned 16-bit sample to the nearest unsigned 8-bit sample.
    ///
    /// @param sample the unsigned 16-bit sample
    /// @return the narrowed unsigned 8-bit sample
    private static int narrow16To8(int sample) {
        return (sample + 128) / 257;
    }

    /// Formats a packed ARGB pixel for assertion messages.
    ///
    /// @param argb the packed ARGB pixel
    /// @return the formatted pixel text
    private static String argbText(int argb) {
        return String.format(Locale.ROOT, "0x%08X", argb);
    }

    /// Maps an actual output coordinate to the corresponding expected source coordinate.
    @FunctionalInterface
    private interface ActualPixelSource {
        /// Returns one packed 8-bit ARGB pixel.
        ///
        /// @param index the zero-based raster index in the actual image
        /// @return the packed 8-bit ARGB pixel
        int argb(int index);
    }

    /// Pixel coordinate transform from decoded output pixels back to reference image pixels.
    public enum PixelTransform {
        /// No coordinate transform.
        IDENTITY,
        /// Actual output is a 90-degree counter-clockwise rotation of the expected image.
        ROTATE_90_COUNTER_CLOCKWISE;

        /// Returns the expected image width for one actual image size.
        ///
        /// @param actualWidth the actual image width
        /// @param actualHeight the actual image height
        /// @return the expected image width
        public int expectedImageWidth(int actualWidth, int actualHeight) {
            return switch (this) {
                case IDENTITY -> actualWidth;
                case ROTATE_90_COUNTER_CLOCKWISE -> actualHeight;
            };
        }

        /// Returns the expected image height for one actual image size.
        ///
        /// @param actualWidth the actual image width
        /// @param actualHeight the actual image height
        /// @return the expected image height
        public int expectedImageHeight(int actualWidth, int actualHeight) {
            return switch (this) {
                case IDENTITY -> actualHeight;
                case ROTATE_90_COUNTER_CLOCKWISE -> actualWidth;
            };
        }

        /// Maps one actual X coordinate to the expected image X coordinate.
        ///
        /// @param expectedWidth the expected image width
        /// @param expectedHeight the expected image height
        /// @param actualX the actual image X coordinate
        /// @param actualY the actual image Y coordinate
        /// @return the expected image X coordinate
        public int expectedX(int expectedWidth, int expectedHeight, int actualX, int actualY) {
            return switch (this) {
                case IDENTITY -> actualX;
                case ROTATE_90_COUNTER_CLOCKWISE -> expectedWidth - 1 - actualY;
            };
        }

        /// Maps one actual Y coordinate to the expected image Y coordinate.
        ///
        /// @param expectedWidth the expected image width
        /// @param expectedHeight the expected image height
        /// @param actualX the actual image X coordinate
        /// @param actualY the actual image Y coordinate
        /// @return the expected image Y coordinate
        public int expectedY(int expectedWidth, int expectedHeight, int actualX, int actualY) {
            return switch (this) {
                case IDENTITY -> actualY;
                case ROTATE_90_COUNTER_CLOCKWISE -> actualX;
            };
        }
    }

    /// Rectangular actual-output pixel region.
    ///
    /// @param x the left coordinate
    /// @param y the top coordinate
    /// @param width the region width
    /// @param height the region height
    @NotNullByDefault
    public record PixelRegion(int x, int y, int width, int height) {
        /// Creates one actual-output pixel region.
        public PixelRegion {
            if (x < 0 || y < 0 || width < 0 || height < 0) {
                throw new IllegalArgumentException("Pixel region coordinates and dimensions must be non-negative");
            }
        }

        /// Creates a region that covers a whole image.
        ///
        /// @param width the image width
        /// @param height the image height
        /// @return the full-image region
        public static PixelRegion full(int width, int height) {
            return new PixelRegion(0, 0, width, height);
        }

        /// Returns whether this region fits inside one image size.
        ///
        /// @param imageWidth the image width
        /// @param imageHeight the image height
        /// @return whether this region fits inside the image
        public boolean fits(int imageWidth, int imageHeight) {
            return x <= imageWidth
                    && y <= imageHeight
                    && width <= imageWidth - x
                    && height <= imageHeight - y;
        }
    }

    /// Tolerance bounds for one pixel comparison.
    ///
    /// @param maxChannelDelta the per-pixel channel-delta threshold
    /// @param maxFailingPixelRatio the allowed ratio of pixels exceeding `maxChannelDelta`
    /// @param maxMeanChannelDelta the maximum mean per-pixel channel delta
    /// @param maxRootMeanSquareChannelDelta the maximum RMS per-pixel channel delta
    @NotNullByDefault
    public record PixelTolerance(
            int maxChannelDelta,
            double maxFailingPixelRatio,
            double maxMeanChannelDelta,
            double maxRootMeanSquareChannelDelta
    ) {
        /// Creates validated pixel tolerance bounds.
        public PixelTolerance {
            if (maxChannelDelta < 0) {
                throw new IllegalArgumentException("maxChannelDelta must be non-negative");
            }
            if (Double.isNaN(maxFailingPixelRatio) || maxFailingPixelRatio < 0.0 || maxFailingPixelRatio > 1.0) {
                throw new IllegalArgumentException("maxFailingPixelRatio must be in [0, 1]");
            }
            if (Double.isNaN(maxMeanChannelDelta) || maxMeanChannelDelta < 0.0) {
                throw new IllegalArgumentException("maxMeanChannelDelta must be non-negative");
            }
            if (Double.isNaN(maxRootMeanSquareChannelDelta) || maxRootMeanSquareChannelDelta < 0.0) {
                throw new IllegalArgumentException("maxRootMeanSquareChannelDelta must be non-negative");
            }
        }

        /// Creates a tolerance that requires every pixel to fit inside one channel-delta threshold.
        ///
        /// @param maxChannelDelta the per-pixel channel-delta threshold
        /// @return the tolerance
        public static PixelTolerance perPixelDelta(int maxChannelDelta) {
            return new PixelTolerance(maxChannelDelta, 0.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }

        /// Creates a tolerance with all supported aggregate bounds.
        ///
        /// @param maxChannelDelta the per-pixel channel-delta threshold
        /// @param maxFailingPixelRatio the allowed ratio of pixels exceeding `maxChannelDelta`
        /// @param maxMeanChannelDelta the maximum mean per-pixel channel delta
        /// @param maxRootMeanSquareChannelDelta the maximum RMS per-pixel channel delta
        /// @return the tolerance
        public static PixelTolerance bounded(
                int maxChannelDelta,
                double maxFailingPixelRatio,
                double maxMeanChannelDelta,
                double maxRootMeanSquareChannelDelta
        ) {
            return new PixelTolerance(
                    maxChannelDelta,
                    maxFailingPixelRatio,
                    maxMeanChannelDelta,
                    maxRootMeanSquareChannelDelta
            );
        }
    }

    /// Result of one pixel comparison.
    ///
    /// @param pixelCount the compared pixel count
    /// @param failingPixelCount the number of pixels exceeding the per-pixel channel threshold
    /// @param largestChannelDelta the largest observed per-pixel channel delta
    /// @param meanChannelDelta the mean observed per-pixel channel delta
    /// @param rootMeanSquareChannelDelta the RMS observed per-pixel channel delta
    /// @param firstMismatch the first mismatch diagnostic, or a success diagnostic
    @NotNullByDefault
    public record PixelComparisonResult(
            int pixelCount,
            int failingPixelCount,
            int largestChannelDelta,
            double meanChannelDelta,
            double rootMeanSquareChannelDelta,
            String firstMismatch
    ) {
        /// Returns whether this result satisfies one tolerance.
        ///
        /// @param tolerance the tolerance to check
        /// @return whether the comparison result is accepted
        public boolean matches(PixelTolerance tolerance) {
            Objects.requireNonNull(tolerance, "tolerance");
            return failingPixelRatio() <= tolerance.maxFailingPixelRatio()
                    && meanChannelDelta <= tolerance.maxMeanChannelDelta()
                    && rootMeanSquareChannelDelta <= tolerance.maxRootMeanSquareChannelDelta();
        }

        /// Returns the ratio of pixels exceeding the per-pixel channel threshold.
        ///
        /// @return the failing pixel ratio
        public double failingPixelRatio() {
            return pixelCount == 0 ? 0.0 : (double) failingPixelCount / pixelCount;
        }

        /// Formats a failure message for one failed comparison.
        ///
        /// @param label the diagnostic label for failure messages
        /// @param tolerance the tolerance that was not satisfied
        /// @return the formatted failure message
        public String failureMessage(String label, PixelTolerance tolerance) {
            return label + " pixel comparison failed: " + firstMismatch
                    + "; compared=" + pixelCount
                    + ", failing=" + failingPixelCount
                    + String.format(Locale.ROOT, " (%.6f)", failingPixelRatio())
                    + ", largestDelta=" + largestChannelDelta
                    + String.format(Locale.ROOT, ", meanDelta=%.3f", meanChannelDelta)
                    + String.format(Locale.ROOT, ", rmsDelta=%.3f", rootMeanSquareChannelDelta)
                    + ", tolerance=maxDelta " + tolerance.maxChannelDelta()
                    + String.format(Locale.ROOT, ", maxFailingRatio %.6f", tolerance.maxFailingPixelRatio())
                    + String.format(Locale.ROOT, ", maxMean %.3f", tolerance.maxMeanChannelDelta())
                    + String.format(Locale.ROOT, ", maxRms %.3f", tolerance.maxRootMeanSquareChannelDelta());
        }
    }
}
