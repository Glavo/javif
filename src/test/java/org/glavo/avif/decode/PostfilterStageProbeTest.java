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
package org.glavo.avif.decode;

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.FrameLocalPartitionTrees;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FilterIntraMode;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode;
import org.glavo.avif.internal.av1.model.ResidualLayout;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformLayout;
import org.glavo.avif.internal.av1.model.TransformResidualUnit;
import org.glavo.avif.internal.av1.model.TransformType;
import org.glavo.avif.internal.av1.postfilter.CdefApplier;
import org.glavo.avif.internal.av1.postfilter.LoopFilterApplier;
import org.glavo.avif.internal.av1.postfilter.RestorationApplier;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.bmff.AvifContainer;
import org.glavo.avif.internal.bmff.AvifContainerParser;
import org.glavo.avif.internal.io.BufferedInput;
import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder;
import org.glavo.avif.testutil.FFmpegAvifReferenceDecoder.SourcePlanes;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Objects;

/// Temporary probe that prints per-stage sample values for remaining 1-LSB FFmpeg mismatches.
@NotNullByDefault
final class PostfilterStageProbeTest {
    /// Prints the decoded sample values at the currently failing FFmpeg reference coordinates.
    @Test
    void probeRemainingFfmpegMismatches() throws IOException, URISyntaxException {
        printFirstMismatch("libavif-test-data/io/kodim03_yuv420_8bpc.avif");
        printFirstMismatch("libavif-test-data/io/kodim23_yuv420_8bpc.avif");
        printFirstMismatch("libavif-test-data/paris_icc_exif_xmp.avif");
        probe(new SampleProbe("libavif-test-data/colors_hdr_p3.avif", Plane.U, 144, 112));
        probe(new SampleProbe("libavif-test-data/colors_hdr_srgb.avif", Plane.Y, 8, 34));
        probe(new SampleProbe("libavif-test-data/io/kodim03_yuv420_8bpc.avif", Plane.Y, 7, 0));
        probe(new SampleProbe("libavif-test-data/io/kodim03_yuv420_8bpc.avif", Plane.Y, 11, 11));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 0, 0));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 1, 0));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 559, 119));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 560, 119));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 559, 120));
        probe(new SampleProbe("libavif-test-data/io/kodim23_yuv420_8bpc.avif", Plane.Y, 567, 126));
        probe(new SampleProbe("libavif-test-data/paris_icc_exif_xmp.avif", Plane.Y, 31, 0));
        probe(new SampleProbe("libavif-test-data/paris_icc_exif_xmp.avif", Plane.Y, 398, 1));
    }

    /// Prints the first source-plane mismatch against FFmpeg for one resource.
    ///
    /// @param resourceName the AVIF fixture resource
    private static void printFirstMismatch(String resourceName) throws IOException, URISyntaxException {
        byte[] bytes = TestResources.readBytes(resourceName);
        AvifContainer container = AvifContainerParser.parse(bytes);
        ByteBuffer primaryPayload = Objects.requireNonNull(container.primaryItemPayload(), "primaryItemPayload");
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(primaryPayload),
                Av1DecoderConfig.DEFAULT
        )) {
            reader.readFrame();
            DecodedPlanes finalPlanes = Objects.requireNonNull(reader.lastPlanes(), "lastPlanes");
            SourcePlanes expected = FFmpegAvifReferenceDecoder.decodeFirstFrameSourcePlanes(resourceName);
            @Nullable SampleProbe mismatch = firstMismatch(resourceName, finalPlanes, expected);
            System.out.println("FIRST-MISMATCH " + resourceName + " -> " + (mismatch == null
                    ? "none"
                    : mismatch.plane() + " (" + mismatch.x() + "," + mismatch.y() + ")"
                    + " expected=" + expectedSample(expected, mismatch)
                    + " actual=" + sample(finalPlanes, mismatch)));
        }
    }

    /// Prints one failing sample across reconstruction and postfilter stages.
    ///
    /// @param sampleProbe the failing sample to inspect
    private static void probe(SampleProbe sampleProbe) throws IOException, URISyntaxException {
        byte[] bytes = TestResources.readBytes(sampleProbe.resourceName());
        AvifContainer container = AvifContainerParser.parse(bytes);
        ByteBuffer primaryPayload = Objects.requireNonNull(container.primaryItemPayload(), "primaryItemPayload");
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(primaryPayload),
                Av1DecoderConfig.DEFAULT
        )) {
            reader.readFrame();
            FrameSyntaxDecodeResult syntaxResult =
                    Objects.requireNonNull(reader.lastFrameSyntaxDecodeResult(), "lastFrameSyntaxDecodeResult");
            FrameHeader frameHeader = syntaxResult.assembly().frameHeader();
            FrameHeader.QuantizationInfo quantization = frameHeader.quantization();

            DecodedPlanes reconstructed = new FrameReconstructor().reconstruct(syntaxResult);
            DecodedPlanes afterLoopFilter = new LoopFilterApplier().apply(reconstructed, frameHeader, syntaxResult);
            DecodedPlanes afterCdef = new CdefApplier().apply(afterLoopFilter, frameHeader.cdef(), syntaxResult);
            DecodedPlanes afterRestoration = new RestorationApplier().apply(
                    afterCdef,
                    afterLoopFilter,
                    frameHeader.restoration(),
                    syntaxResult
            );
            DecodedPlanes finalPlanes = Objects.requireNonNull(reader.lastPlanes(), "lastPlanes");
            SourcePlanes ffmpegPlanes = FFmpegAvifReferenceDecoder.decodeFirstFrameSourcePlanes(sampleProbe.resourceName());

            System.out.println("RESOURCE " + sampleProbe.resourceName());
            System.out.println("PLANE " + sampleProbe.plane() + " AT (" + sampleProbe.x() + "," + sampleProbe.y() + ")");
            System.out.println("EXPECTED " + expectedSample(ffmpegPlanes, sampleProbe));
            printFirstStageMismatch("RECON-FIRST", sampleProbe.resourceName(), reconstructed, ffmpegPlanes);
            printFirstStageMismatch("LOOP-FIRST", sampleProbe.resourceName(), afterLoopFilter, ffmpegPlanes);
            printFirstStageMismatch("CDEF-FIRST", sampleProbe.resourceName(), afterCdef, ffmpegPlanes);
            printFirstStageMismatch("REST-FIRST", sampleProbe.resourceName(), afterRestoration, ffmpegPlanes);
            printFirstStageMismatch("FINAL-FIRST", sampleProbe.resourceName(), finalPlanes, ffmpegPlanes);
            System.out.println("RECON " + sample(reconstructed, sampleProbe));
            System.out.println("LOOP  " + sample(afterLoopFilter, sampleProbe));
            System.out.println("CDEF  " + sample(afterCdef, sampleProbe));
            System.out.println("REST  " + sample(afterRestoration, sampleProbe));
            System.out.println("FINAL " + sample(finalPlanes, sampleProbe));
            int[] levelY = frameHeader.loopFilter().levelY();
            System.out.println("loopFilterLevels="
                    + levelY[0] + ","
                    + levelY[1]
                    + " yDcDelta=" + quantization.yDcDelta()
                    + " uDcDelta=" + quantization.uDcDelta()
                    + " uAcDelta=" + quantization.uAcDelta()
                    + " vDcDelta=" + quantization.vDcDelta()
                    + " vAcDelta=" + quantization.vAcDelta()
                    + " useQM=" + quantization.useQuantizationMatrices()
                    + " qmY=" + quantization.quantizationMatrixY()
                    + " qmU=" + quantization.quantizationMatrixU()
                    + " qmV=" + quantization.quantizationMatrixV()
                    + " cdefBits=" + frameHeader.cdef().bits()
                    + " restoration=" + frameHeader.restoration().types()[0] + ","
                    + frameHeader.restoration().types()[1] + ","
                    + frameHeader.restoration().types()[2]);
            if (sampleProbe.resourceName().contains("paris_icc_exif_xmp")
                    && sampleProbe.plane() == Plane.Y) {
                printLoopEdgeDebug(reconstructed, syntaxResult, sampleProbe.x(), sampleProbe.y());
            }
            if (frameHeader.cdef().bits() > 0 && sampleProbe.plane() == Plane.Y) {
                printCdefUnitDebug(afterLoopFilter, syntaxResult, sampleProbe.x(), sampleProbe.y());
                if (sampleProbe.resourceName().contains("kodim23")) {
                    printCdefTapDebug(afterLoopFilter, syntaxResult, sampleProbe.x(), sampleProbe.y());
                    printRowWindow(ffmpegPlanes, sampleProbe, 5);
                    printRowWindow("RECON", reconstructed, sampleProbe, 5);
                    printRowWindow("LOOP", afterLoopFilter, sampleProbe, 5);
                    printRowWindow("CDEF", afterCdef, sampleProbe, 5);
                }
            }
            printCoveringLeafDebug(
                    syntaxResult,
                    sampleProbe.plane() == Plane.Y ? sampleProbe.x() : sampleProbe.x() << chromaSubsamplingX(finalPlanes),
                    sampleProbe.plane() == Plane.Y ? sampleProbe.y() : sampleProbe.y() << chromaSubsamplingY(finalPlanes)
            );
            printResidualBreakdown(ffmpegPlanes, syntaxResult, reconstructed, sampleProbe);
            printPredictorHypotheses(ffmpegPlanes, syntaxResult, reconstructed, sampleProbe);
            printDirectionalReferenceDebug(ffmpegPlanes, reconstructed, syntaxResult, sampleProbe);
            printNeighborhood("EXPECTED", ffmpegPlanes, sampleProbe);
            printNeighborhood("RECON", reconstructed, sampleProbe);
            printNeighborhood("LOOP", afterLoopFilter, sampleProbe);
            printNeighborhood("CDEF", afterCdef, sampleProbe);
            printNeighborhood("REST", afterRestoration, sampleProbe);
            printNeighborhood("FINAL", finalPlanes, sampleProbe);
        }
    }

    /// Returns one stage sample for the requested plane and coordinate.
    ///
    /// @param planes the decoded planes
    /// @param sampleProbe the requested sample
    /// @return the stored sample value
    private static int sample(DecodedPlanes planes, SampleProbe sampleProbe) {
        return switch (sampleProbe.plane()) {
            case Y -> planes.lumaPlane().sample(sampleProbe.x(), sampleProbe.y());
            case U -> Objects.requireNonNull(planes.chromaUPlane(), "planes.chromaUPlane()").sample(sampleProbe.x(), sampleProbe.y());
            case V -> Objects.requireNonNull(planes.chromaVPlane(), "planes.chromaVPlane()").sample(sampleProbe.x(), sampleProbe.y());
        };
    }

    /// Returns one FFmpeg source-plane sample for the requested plane and coordinate.
    ///
    /// @param planes the FFmpeg source planes
    /// @param sampleProbe the requested sample
    /// @return the stored sample value
    private static int expectedSample(SourcePlanes planes, SampleProbe sampleProbe) {
        return switch (sampleProbe.plane()) {
            case Y -> planes.lumaSample(sampleProbe.x(), sampleProbe.y());
            case U -> planes.chromaUSample(sampleProbe.x(), sampleProbe.y());
            case V -> planes.chromaVSample(sampleProbe.x(), sampleProbe.y());
        };
    }

    /// Returns the first decoded source-plane mismatch against the FFmpeg reference, or `null`.
    ///
    /// @param resourceName the AVIF fixture resource
    /// @param actual the decoded planes
    /// @param expected the FFmpeg reference planes
    /// @return the first decoded source-plane mismatch, or `null`
    private static @Nullable SampleProbe firstMismatch(
            String resourceName,
            DecodedPlanes actual,
            SourcePlanes expected
    ) {
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                SampleProbe sampleProbe = new SampleProbe(resourceName, Plane.Y, x, y);
                if (sample(actual, sampleProbe) != expectedSample(expected, sampleProbe)) {
                    return sampleProbe;
                }
            }
        }
        if (!actual.hasChroma()) {
            return null;
        }
        for (int y = 0; y < expected.chromaHeight(); y++) {
            for (int x = 0; x < expected.chromaWidth(); x++) {
                SampleProbe sampleProbe = new SampleProbe(resourceName, Plane.U, x, y);
                if (sample(actual, sampleProbe) != expectedSample(expected, sampleProbe)) {
                    return sampleProbe;
                }
            }
        }
        for (int y = 0; y < expected.chromaHeight(); y++) {
            for (int x = 0; x < expected.chromaWidth(); x++) {
                SampleProbe sampleProbe = new SampleProbe(resourceName, Plane.V, x, y);
                if (sample(actual, sampleProbe) != expectedSample(expected, sampleProbe)) {
                    return sampleProbe;
                }
            }
        }
        return null;
    }

    /// Prints the first decoded source-plane mismatch against the FFmpeg reference for one stage.
    ///
    /// @param label the printed stage label
    /// @param resourceName the AVIF fixture resource
    /// @param actual the decoded planes for one stage
    /// @param expected the FFmpeg reference planes
    private static void printFirstStageMismatch(
            String label,
            String resourceName,
            DecodedPlanes actual,
            SourcePlanes expected
    ) {
        @Nullable SampleProbe mismatch = firstMismatch(resourceName, actual, expected);
        System.out.println(label + " " + (mismatch == null
                ? "none"
                : mismatch.plane() + " (" + mismatch.x() + "," + mismatch.y() + ")"
                + " expected=" + expectedSample(expected, mismatch)
                + " actual=" + sample(actual, mismatch)));
    }

    /// Prints one 3x3 neighborhood around the requested sample from the FFmpeg source planes.
    ///
    /// @param label the printed stage label
    /// @param planes the FFmpeg source planes
    /// @param sampleProbe the requested sample
    private static void printNeighborhood(String label, SourcePlanes planes, SampleProbe sampleProbe) {
        for (int dy = -1; dy <= 1; dy++) {
            StringBuilder line = new StringBuilder(label).append(' ').append(dy).append(':');
            for (int dx = -1; dx <= 1; dx++) {
                line.append(' ').append(expectedSample(planes, clamp(planes, sampleProbe.offset(dx, dy))));
            }
            System.out.println(line);
        }
    }

    /// Prints one 3x3 neighborhood around the requested sample from one decoded stage.
    ///
    /// @param label the printed stage label
    /// @param planes the decoded planes
    /// @param sampleProbe the requested sample
    private static void printNeighborhood(String label, DecodedPlanes planes, SampleProbe sampleProbe) {
        for (int dy = -1; dy <= 1; dy++) {
            StringBuilder line = new StringBuilder(label).append(' ').append(dy).append(':');
            for (int dx = -1; dx <= 1; dx++) {
                line.append(' ').append(sample(planes, clamp(planes, sampleProbe.offset(dx, dy))));
            }
            System.out.println(line);
        }
    }

    /// Prints one wider horizontal window from the FFmpeg source planes.
    ///
    /// @param planes the FFmpeg source planes
    /// @param centerProbe the center probe
    /// @param radius the inclusive horizontal radius
    private static void printRowWindow(SourcePlanes planes, SampleProbe centerProbe, int radius) {
        StringBuilder line = new StringBuilder("EXPECTED-WIDE:");
        for (int dx = -radius; dx <= radius; dx++) {
            line.append(' ').append(expectedSample(planes, clamp(planes, centerProbe.offset(dx, 0))));
        }
        System.out.println(line);
    }

    /// Prints one wider horizontal window from one decoded stage.
    ///
    /// @param label the printed stage label
    /// @param planes the decoded planes
    /// @param centerProbe the center probe
    /// @param radius the inclusive horizontal radius
    private static void printRowWindow(String label, DecodedPlanes planes, SampleProbe centerProbe, int radius) {
        StringBuilder line = new StringBuilder(label).append("-WIDE:");
        for (int dx = -radius; dx <= radius; dx++) {
            line.append(' ').append(sample(planes, clamp(planes, centerProbe.offset(dx, 0))));
        }
        System.out.println(line);
    }

    /// Clamps one FFmpeg probe coordinate into the requested plane bounds.
    ///
    /// @param planes the FFmpeg source planes
    /// @param sampleProbe the requested probe
    /// @return the clamped probe
    private static SampleProbe clamp(SourcePlanes planes, SampleProbe sampleProbe) {
        int width = sampleProbe.plane() == Plane.Y ? planes.width() : planes.chromaWidth();
        int height = sampleProbe.plane() == Plane.Y ? planes.height() : planes.chromaHeight();
        return new SampleProbe(
                sampleProbe.resourceName(),
                sampleProbe.plane(),
                Math.max(0, Math.min(width - 1, sampleProbe.x())),
                Math.max(0, Math.min(height - 1, sampleProbe.y()))
        );
    }

    /// Clamps one decoded probe coordinate into the requested plane bounds.
    ///
    /// @param planes the decoded planes
    /// @param sampleProbe the requested probe
    /// @return the clamped probe
    private static SampleProbe clamp(DecodedPlanes planes, SampleProbe sampleProbe) {
        DecodedPlane plane = switch (sampleProbe.plane()) {
            case Y -> planes.lumaPlane();
            case U -> Objects.requireNonNull(planes.chromaUPlane(), "planes.chromaUPlane()");
            case V -> Objects.requireNonNull(planes.chromaVPlane(), "planes.chromaVPlane()");
        };
        return new SampleProbe(
                sampleProbe.resourceName(),
                sampleProbe.plane(),
                Math.max(0, Math.min(plane.width() - 1, sampleProbe.x())),
                Math.max(0, Math.min(plane.height() - 1, sampleProbe.y()))
        );
    }

    /// Prints reflected CDEF unit state for one luma sample.
    ///
    /// @param afterLoopFilter the post-loop-filter planes
    /// @param syntaxResult the decoded frame syntax
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    private static void printCdefUnitDebug(
            DecodedPlanes afterLoopFilter,
            FrameSyntaxDecodeResult syntaxResult,
            int x,
            int y
    ) {
        try {
            Class<CdefApplier> applierClass = CdefApplier.class;
            Class<?> unitMapClass = nestedClass(applierClass, "CdefUnitMap");
            Class<?> directionMapClass = nestedClass(applierClass, "CdefDirectionMap");
            Class<?> strengthClass = nestedClass(applierClass, "CdefStrength");

            int unitColumns = (afterLoopFilter.codedWidth() + 7) / 8;
            int unitRows = (afterLoopFilter.codedHeight() + 7) / 8;
            int unitIndex = (y / 8) * unitColumns + (x / 8);

            Method buildUnitMap = applierClass.getDeclaredMethod(
                    "buildCdefUnitMap",
                    FrameSyntaxDecodeResult.class,
                    int.class,
                    int.class
            );
            buildUnitMap.setAccessible(true);
            Object unitMap = buildUnitMap.invoke(null, syntaxResult, unitColumns, unitRows);

            Method buildDirectionMap = applierClass.getDeclaredMethod(
                    "buildDirectionMap",
                    DecodedPlane.class,
                    int.class,
                    FrameHeader.CdefInfo.class,
                    unitMapClass,
                    int.class,
                    int.class,
                    boolean.class
            );
            buildDirectionMap.setAccessible(true);
            Object directionMap = buildDirectionMap.invoke(
                    null,
                    afterLoopFilter.lumaPlane(),
                    afterLoopFilter.bitDepth(),
                    syntaxResult.assembly().frameHeader().cdef(),
                    unitMap,
                    unitColumns,
                    unitRows,
                    afterLoopFilter.hasChroma()
            );

            Method cdefIndexMethod = unitMapClass.getDeclaredMethod("cdefIndex", int.class);
            cdefIndexMethod.setAccessible(true);
            Method nonSkipMethod = unitMapClass.getDeclaredMethod("nonSkip", int.class);
            nonSkipMethod.setAccessible(true);
            Method directionMethod = directionMapClass.getDeclaredMethod("direction", int.class);
            directionMethod.setAccessible(true);
            Method varianceMethod = directionMapClass.getDeclaredMethod("variance", int.class);
            varianceMethod.setAccessible(true);
            Method strengthForIndexMethod = applierClass.getDeclaredMethod("strengthForIndex", int[].class, int.class);
            strengthForIndexMethod.setAccessible(true);
            Method decodeStrengthMethod = applierClass.getDeclaredMethod("decodeStrength", int.class, int.class);
            decodeStrengthMethod.setAccessible(true);
            Method adjustStrengthMethod = applierClass.getDeclaredMethod("adjustStrength", int.class, int.class);
            adjustStrengthMethod.setAccessible(true);

            int cdefIndex = (int) cdefIndexMethod.invoke(unitMap, unitIndex);
            boolean nonSkip = (boolean) nonSkipMethod.invoke(unitMap, unitIndex);
            int direction = (int) directionMethod.invoke(directionMap, unitIndex);
            int variance = (int) varianceMethod.invoke(directionMap, unitIndex);
            int encodedStrength = (int) strengthForIndexMethod.invoke(
                    null,
                    syntaxResult.assembly().frameHeader().cdef().yStrengths(),
                    cdefIndex
            );
            Object decodedStrength = decodeStrengthMethod.invoke(null, encodedStrength, afterLoopFilter.bitDepth() - 8);
            Field primaryField = strengthClass.getDeclaredField("primary");
            primaryField.setAccessible(true);
            Field secondaryField = strengthClass.getDeclaredField("secondary");
            secondaryField.setAccessible(true);
            int primary = (int) primaryField.get(decodedStrength);
            int secondary = (int) secondaryField.get(decodedStrength);
            int adjustedPrimary = (int) adjustStrengthMethod.invoke(null, primary, variance);

            System.out.println("CDEF-UNIT index=" + unitIndex
                    + " cdefIndex=" + cdefIndex
                    + " nonSkip=" + nonSkip
                    + " direction=" + direction
                    + " variance=" + variance
                    + " encodedStrength=" + encodedStrength
                    + " primary=" + primary
                    + " adjustedPrimary=" + adjustedPrimary
                    + " secondary=" + secondary);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect CDEF unit state", exception);
        }
    }

    /// Prints the per-tap CDEF contributions for one luma sample.
    ///
    /// @param afterLoopFilter the post-loop-filter planes
    /// @param syntaxResult the decoded frame syntax
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    private static void printCdefTapDebug(
            DecodedPlanes afterLoopFilter,
            FrameSyntaxDecodeResult syntaxResult,
            int x,
            int y
    ) {
        try {
            Class<CdefApplier> applierClass = CdefApplier.class;
            Class<?> unitMapClass = nestedClass(applierClass, "CdefUnitMap");
            Class<?> directionMapClass = nestedClass(applierClass, "CdefDirectionMap");

            int unitColumns = (afterLoopFilter.codedWidth() + 7) / 8;
            int unitRows = (afterLoopFilter.codedHeight() + 7) / 8;
            int unitIndex = (y / 8) * unitColumns + (x / 8);

            Method buildUnitMap = applierClass.getDeclaredMethod(
                    "buildCdefUnitMap",
                    FrameSyntaxDecodeResult.class,
                    int.class,
                    int.class
            );
            buildUnitMap.setAccessible(true);
            Object unitMap = buildUnitMap.invoke(null, syntaxResult, unitColumns, unitRows);

            Method buildDirectionMap = applierClass.getDeclaredMethod(
                    "buildDirectionMap",
                    DecodedPlane.class,
                    int.class,
                    FrameHeader.CdefInfo.class,
                    unitMapClass,
                    int.class,
                    int.class,
                    boolean.class
            );
            buildDirectionMap.setAccessible(true);
            Object directionMap = buildDirectionMap.invoke(
                    null,
                    afterLoopFilter.lumaPlane(),
                    afterLoopFilter.bitDepth(),
                    syntaxResult.assembly().frameHeader().cdef(),
                    unitMap,
                    unitColumns,
                    unitRows,
                    afterLoopFilter.hasChroma()
            );

            Method cdefIndexMethod = unitMapClass.getDeclaredMethod("cdefIndex", int.class);
            cdefIndexMethod.setAccessible(true);
            Method directionMethod = directionMapClass.getDeclaredMethod("direction", int.class);
            directionMethod.setAccessible(true);
            Method varianceMethod = directionMapClass.getDeclaredMethod("variance", int.class);
            varianceMethod.setAccessible(true);
            Method strengthForIndexMethod = applierClass.getDeclaredMethod("strengthForIndex", int[].class, int.class);
            strengthForIndexMethod.setAccessible(true);
            Method decodeStrengthMethod = applierClass.getDeclaredMethod("decodeStrength", int.class, int.class);
            decodeStrengthMethod.setAccessible(true);
            Method adjustStrengthMethod = applierClass.getDeclaredMethod("adjustStrength", int.class, int.class);
            adjustStrengthMethod.setAccessible(true);

            Class<?> strengthClass = nestedClass(applierClass, "CdefStrength");
            Field primaryField = strengthClass.getDeclaredField("primary");
            primaryField.setAccessible(true);
            Field secondaryField = strengthClass.getDeclaredField("secondary");
            secondaryField.setAccessible(true);

            Field directionsField = applierClass.getDeclaredField("FILTER_DIRECTIONS");
            directionsField.setAccessible(true);
            int[][][] directions = (int[][][]) directionsField.get(null);

            int cdefIndex = (int) cdefIndexMethod.invoke(unitMap, unitIndex);
            int detectedDirection = (int) directionMethod.invoke(directionMap, unitIndex);
            int variance = (int) varianceMethod.invoke(directionMap, unitIndex);
            int encodedStrength = (int) strengthForIndexMethod.invoke(
                    null,
                    syntaxResult.assembly().frameHeader().cdef().yStrengths(),
                    cdefIndex
            );
            Object decodedStrength = decodeStrengthMethod.invoke(null, encodedStrength, afterLoopFilter.bitDepth() - 8);
            int primary = (int) primaryField.get(decodedStrength);
            int secondary = (int) secondaryField.get(decodedStrength);
            int adjustedPrimary = (int) adjustStrengthMethod.invoke(null, primary, variance);
            int damping = syntaxResult.assembly().frameHeader().cdef().damping() + (afterLoopFilter.bitDepth() - 8);
            int primaryDamping = adjustedPrimary > 0 ? Math.max(0, damping - floorLog2(adjustedPrimary)) : 0;
            int secondaryDamping = secondary > 0 ? Math.max(0, damping - floorLog2(secondary)) : 0;
            int center = afterLoopFilter.lumaPlane().sample(x, y);
            int appliedDirection = adjustedPrimary > 0 ? detectedDirection : 0;

            System.out.println("CDEF-TAPS center=" + center
                    + " damping=" + damping
                    + " primary=" + adjustedPrimary
                    + " primaryDamping=" + primaryDamping
                    + " secondary=" + secondary
                    + " secondaryDamping=" + secondaryDamping
                    + " detectedDirection=" + detectedDirection
                    + " appliedDirection=" + appliedDirection);
            for (int direction = 0; direction < directions.length; direction++) {
                int sum = 0;
                int primaryTap = 4 - ((adjustedPrimary >> (afterLoopFilter.bitDepth() - 8)) & 1);
                if (adjustedPrimary > 0) {
                    for (int distanceIndex = 0; distanceIndex < 2; distanceIndex++) {
                        int[] step = directions[direction][distanceIndex];
                        int positive = sampleOrMissing(afterLoopFilter.lumaPlane(), x + step[0], y + step[1]);
                        int negative = sampleOrMissing(afterLoopFilter.lumaPlane(), x - step[0], y - step[1]);
                        int constrainedPositive = constrain(positive, center, adjustedPrimary, primaryDamping);
                        int constrainedNegative = constrain(negative, center, adjustedPrimary, primaryDamping);
                        sum += primaryTap * constrainedPositive;
                        sum += primaryTap * constrainedNegative;
                        System.out.println("CDEF-TAPS dir=" + direction
                                + " distance=" + distanceIndex
                                + " step=(" + step[0] + "," + step[1] + ")"
                                + " tap=" + primaryTap
                                + " positive=" + positive
                                + " negative=" + negative
                                + " constrainedPositive=" + constrainedPositive
                                + " constrainedNegative=" + constrainedNegative);
                        primaryTap = (primaryTap & 3) | 2;
                    }
                }
                if (secondary > 0) {
                    int secondaryDirection0 = (direction + 2) & 7;
                    int secondaryDirection1 = (direction + 6) & 7;
                    for (int distanceIndex = 0; distanceIndex < 2; distanceIndex++) {
                        int tap = 2 - distanceIndex;
                        int[] step0 = directions[secondaryDirection0][distanceIndex];
                        int[] step1 = directions[secondaryDirection1][distanceIndex];
                        int positive0 = sampleOrMissing(afterLoopFilter.lumaPlane(), x + step0[0], y + step0[1]);
                        int negative0 = sampleOrMissing(afterLoopFilter.lumaPlane(), x - step0[0], y - step0[1]);
                        int positive1 = sampleOrMissing(afterLoopFilter.lumaPlane(), x + step1[0], y + step1[1]);
                        int negative1 = sampleOrMissing(afterLoopFilter.lumaPlane(), x - step1[0], y - step1[1]);
                        int constrainedPositive0 = constrain(positive0, center, secondary, secondaryDamping);
                        int constrainedNegative0 = constrain(negative0, center, secondary, secondaryDamping);
                        int constrainedPositive1 = constrain(positive1, center, secondary, secondaryDamping);
                        int constrainedNegative1 = constrain(negative1, center, secondary, secondaryDamping);
                        sum += tap * constrainedPositive0;
                        sum += tap * constrainedNegative0;
                        sum += tap * constrainedPositive1;
                        sum += tap * constrainedNegative1;
                        System.out.println("CDEF-SEC dir=" + direction
                                + " distance=" + distanceIndex
                                + " tap=" + tap
                                + " step0=(" + step0[0] + "," + step0[1] + ")"
                                + " positive0=" + positive0
                                + " negative0=" + negative0
                                + " constrainedPositive0=" + constrainedPositive0
                                + " constrainedNegative0=" + constrainedNegative0
                                + " step1=(" + step1[0] + "," + step1[1] + ")"
                                + " positive1=" + positive1
                                + " negative1=" + negative1
                                + " constrainedPositive1=" + constrainedPositive1
                                + " constrainedNegative1=" + constrainedNegative1);
                    }
                }
                System.out.println("CDEF-TAPS dir=" + direction
                        + " sum=" + sum
                        + " filtered=" + (center + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4)));
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect CDEF tap state", exception);
        }
    }

    /// Prints the loop-filter edges that change one luma sample while replaying the filter passes.
    ///
    /// @param reconstructed the reconstructed planes before loop filtering
    /// @param syntaxResult the decoded frame syntax
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    private static void printLoopEdgeDebug(
            DecodedPlanes reconstructed,
            FrameSyntaxDecodeResult syntaxResult,
            int x,
            int y
    ) {
        try {
            Class<LoopFilterApplier> applierClass = LoopFilterApplier.class;
            Class<?> planeBufferClass = nestedClass(applierClass, "PlaneBuffer");
            Class<?> blockMapClass = nestedClass(applierClass, "LoopFilterBlockMap");

            Method createPlaneBufferMethod = planeBufferClass.getDeclaredMethod(
                    "create",
                    DecodedPlane.class,
                    int.class
            );
            createPlaneBufferMethod.setAccessible(true);
            Object planeBuffer = createPlaneBufferMethod.invoke(
                    null,
                    reconstructed.lumaPlane(),
                    reconstructed.bitDepth()
            );

            Method createBlockMapMethod = blockMapClass.getDeclaredMethod(
                    "create",
                    FrameSyntaxDecodeResult.class,
                    DecodedPlanes.class
            );
            createBlockMapMethod.setAccessible(true);
            Object blockMap = createBlockMapMethod.invoke(null, syntaxResult, reconstructed);

            Method width4Method = blockMapClass.getDeclaredMethod("width4");
            width4Method.setAccessible(true);
            Method height4Method = blockMapClass.getDeclaredMethod("height4");
            height4Method.setAccessible(true);
            Method sampleMethod = planeBufferClass.getDeclaredMethod("sample", int.class, int.class);
            sampleMethod.setAccessible(true);
            Method filterSizeMethod = applierClass.getDeclaredMethod(
                    "filterSize",
                    TransformSize.class,
                    TransformSize.class,
                    int.class,
                    int.class
            );
            filterSizeMethod.setAccessible(true);
            Method filterLevelMethod = applierClass.getDeclaredMethod(
                    "filterLevel",
                    FrameHeader.class,
                    TileBlockHeaderReader.BlockHeader.class,
                    int.class,
                    int.class
            );
            filterLevelMethod.setAccessible(true);
            Method cellAtMethod = blockMapClass.getDeclaredMethod("cellAt", int.class, int.class);
            cellAtMethod.setAccessible(true);
            Method headerMethod = nestedClass(applierClass, "LoopFilterCell").getDeclaredMethod("header");
            headerMethod.setAccessible(true);
            Method transformCellMethod = nestedClass(applierClass, "LoopFilterCell").getDeclaredMethod("transformCell", int.class);
            transformCellMethod.setAccessible(true);
            Method sizeMethod = nestedClass(applierClass, "TransformCell").getDeclaredMethod("size");
            sizeMethod.setAccessible(true);
            Method filterEdgeMethod = applierClass.getDeclaredMethod(
                    "filterEdge",
                    planeBufferClass,
                    FrameHeader.class,
                    blockMapClass,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class
            );
            filterEdgeMethod.setAccessible(true);

            int width4 = (int) width4Method.invoke(blockMap);
            int height4 = (int) height4Method.invoke(blockMap);
            FrameHeader frameHeader = syntaxResult.assembly().frameHeader();
            int beforeAll = (int) sampleMethod.invoke(planeBuffer, x, y);
            System.out.println("LOOP-TRACE startSample=" + beforeAll + " at=(" + x + "," + y + ")");
            for (int pass = 0; pass < 2; pass++) {
                int rowStart = pass == 0 ? 0 : 1;
                int colStart = pass == 0 ? 1 : 0;
                for (int row4 = rowStart; row4 < height4; row4++) {
                    for (int col4 = colStart; col4 < width4; col4++) {
                        Object current = cellAtMethod.invoke(blockMap, col4, row4);
                        Object previous = cellAtMethod.invoke(
                                blockMap,
                                col4 - (pass == 0 ? 1 : 0),
                                row4 - (pass == 1 ? 1 : 0)
                        );
                        if (current == null || previous == null) {
                            continue;
                        }
                        Object currentHeader = headerMethod.invoke(current);
                        Object previousHeader = headerMethod.invoke(previous);
                        Object currentTransformCell = transformCellMethod.invoke(current, 0);
                        Object previousTransformCell = transformCellMethod.invoke(previous, 0);
                        TransformSize currentSize = (TransformSize) sizeMethod.invoke(currentTransformCell);
                        TransformSize previousSize = (TransformSize) sizeMethod.invoke(previousTransformCell);
                        int level = (int) filterLevelMethod.invoke(
                                null,
                                frameHeader,
                                currentHeader,
                                0,
                                pass
                        );
                        if (level == 0) {
                            level = (int) filterLevelMethod.invoke(
                                    null,
                                    frameHeader,
                                    previousHeader,
                                    0,
                                    pass
                            );
                        }
                        int before = (int) sampleMethod.invoke(planeBuffer, x, y);
                        filterEdgeMethod.invoke(null, planeBuffer, frameHeader, blockMap, 0, pass, row4, col4, 0, 0);
                        int after = (int) sampleMethod.invoke(planeBuffer, x, y);
                        if (after != before) {
                            int boundaryX = pass == 0 ? col4 << 2 : x;
                            int boundaryY = pass == 1 ? row4 << 2 : y;
                            int filterSize = (int) filterSizeMethod.invoke(
                                    null,
                                    currentSize,
                                    previousSize,
                                    0,
                                    pass
                            );
                            System.out.println("LOOP-TRACE change"
                                    + " pass=" + pass
                                    + " row4=" + row4
                                    + " col4=" + col4
                                    + " boundary=(" + boundaryX + "," + boundaryY + ")"
                                    + " before=" + before
                                    + " after=" + after
                                    + " level=" + level
                                    + " filterSize=" + filterSize
                                    + " currentTx=" + currentSize
                                    + " previousTx=" + previousSize
                                    + " currentSkip=" + ((TileBlockHeaderReader.BlockHeader) currentHeader).skip()
                                    + " previousSkip=" + ((TileBlockHeaderReader.BlockHeader) previousHeader).skip());
                        }
                    }
                }
            }
            int afterAll = (int) sampleMethod.invoke(planeBuffer, x, y);
            System.out.println("LOOP-TRACE finalSample=" + afterAll + " at=(" + x + "," + y + ")");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to trace loop-filter edges", exception);
        }
    }

    /// Returns one plane sample or `Integer.MIN_VALUE` when the coordinate is outside the plane.
    ///
    /// @param plane the decoded plane
    /// @param x the plane-relative sample X coordinate
    /// @param y the plane-relative sample Y coordinate
    /// @return the sample value, or `Integer.MIN_VALUE`
    private static int sampleOrMissing(DecodedPlane plane, int x, int y) {
        if (x < 0 || x >= plane.width() || y < 0 || y >= plane.height()) {
            return Integer.MIN_VALUE;
        }
        return plane.sample(x, y);
    }

    /// Applies the scalar CDEF constrain function to one neighbor sample.
    ///
    /// @param sample the neighbor sample, or `Integer.MIN_VALUE`
    /// @param center the center sample
    /// @param threshold the active CDEF threshold
    /// @param damping the active CDEF damping
    /// @return the constrained signed delta
    private static int constrain(int sample, int center, int threshold, int damping) {
        if (sample == Integer.MIN_VALUE || threshold == 0) {
            return 0;
        }
        int difference = sample - center;
        int magnitude = Math.abs(difference);
        int constrainedMagnitude = Math.min(magnitude, Math.max(0, threshold - (magnitude >> damping)));
        return difference < 0 ? -constrainedMagnitude : constrainedMagnitude;
    }

    /// Returns `floor(log2(value))` for one positive integer.
    ///
    /// @param value the positive input value
    /// @return `floor(log2(value))`
    private static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    /// Prints one reconstructed-sample breakdown into predictor and residual contributions.
    ///
    /// @param syntaxResult the decoded frame syntax
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param sampleProbe the requested sample
    private static void printResidualBreakdown(
            SourcePlanes expectedPlanes,
            FrameSyntaxDecodeResult syntaxResult,
            DecodedPlanes reconstructed,
            SampleProbe sampleProbe
    ) {
        try {
            TilePartitionTreeReader.Node[][] roots =
                    FrameLocalPartitionTrees.create(syntaxResult.assembly(), syntaxResult.tileRoots());
            int chromaSubsamplingX = chromaSubsamplingX(reconstructed);
            int chromaSubsamplingY = chromaSubsamplingY(reconstructed);
            int lumaX = sampleProbe.plane() == Plane.Y ? sampleProbe.x() : sampleProbe.x() << chromaSubsamplingX;
            int lumaY = sampleProbe.plane() == Plane.Y ? sampleProbe.y() : sampleProbe.y() << chromaSubsamplingY;
            TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(roots, lumaX, lumaY);
            if (leaf == null) {
                System.out.println("BREAKDOWN none");
                return;
            }

            TransformResidualUnit unit = findCoveringResidualUnit(
                    leaf.residualLayout(),
                    sampleProbe,
                    chromaSubsamplingX,
                    chromaSubsamplingY
            );
            if (unit == null || unit.allZero()) {
                System.out.println("BREAKDOWN unit=" + (unit == null ? "none" : "all-zero"));
                return;
            }

            int[] dequantized = dequantizeUnit(
                    unit,
                    leaf.header(),
                    syntaxResult.assembly().frameHeader(),
                    reconstructed.bitDepth(),
                    sampleProbe.plane()
            );
            int[] residual = reconstructResidual(unit, dequantized);
            int unitX = unitPlaneX(unit, sampleProbe.plane(), chromaSubsamplingX);
            int unitY = unitPlaneY(unit, sampleProbe.plane(), chromaSubsamplingY);
            int unitWidth = unit.size().widthPixels();
            int unitHeight = unit.size().heightPixels();
            int relativeX = sampleProbe.x() - unitX;
            int relativeY = sampleProbe.y() - unitY;
            int residualSample = residual[relativeY * unitWidth + relativeX];
            int reconstructedSample = sample(reconstructed, sampleProbe);
            int predictorSample = reconstructedSample - residualSample;
            System.out.println("BREAKDOWN unitPosition=(" + unitX + "," + unitY + ")"
                    + " unitSize=" + unit.size()
                    + " residualSample=" + residualSample
                    + " predictorSample=" + predictorSample
                    + " reconstructedSample=" + reconstructedSample);
            printAlternativeResidualHypotheses(unit, dequantized, relativeX, relativeY);
            int[] predictor = predictorBlock(
                    reconstructed,
                    sampleProbe.plane(),
                    unitX,
                    unitY,
                    unitWidth,
                    unitHeight,
                    residual
            );
            printSingleCoefficientAdjustmentSearch(
                    expectedPlanes,
                    syntaxResult.assembly().frameHeader(),
                    leaf.header(),
                    reconstructed.bitDepth(),
                    sampleProbe,
                    unit,
                    predictor,
                    residual,
                    unitX,
                    unitY
            );
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect predictor/residual breakdown", exception);
        }
    }

    /// Prints the best local improvement reachable by nudging one decoded coefficient by `±1`.
    ///
    /// This helps distinguish inverse-transform drift from coefficient-token drift. If a single
    /// small coefficient adjustment materially reduces the local FFmpeg error, the remaining bug is
    /// more likely in token decoding than in the transform kernel.
    ///
    /// @param expectedPlanes the FFmpeg source planes
    /// @param frameHeader the decoded frame header
    /// @param header the covering block header
    /// @param bitDepth the decoded sample bit depth
    /// @param sampleProbe the requested sample
    /// @param unit the covering transform residual unit
    /// @param predictor the reconstructed predictor block
    /// @param currentResidual the current production residual block
    /// @param unitX the plane-domain unit X origin
    /// @param unitY the plane-domain unit Y origin
    private static void printSingleCoefficientAdjustmentSearch(
            SourcePlanes expectedPlanes,
            FrameHeader frameHeader,
            TileBlockHeaderReader.BlockHeader header,
            int bitDepth,
            SampleProbe sampleProbe,
            TransformResidualUnit unit,
            int[] predictor,
            int[] currentResidual,
            int unitX,
            int unitY
    ) throws ReflectiveOperationException {
        int[] currentCoefficients = unit.coefficients();
        int baselineScore = localWindowScore(
                expectedPlanes,
                sampleProbe.plane(),
                predictor,
                currentResidual,
                unitX,
                unitY,
                unit.size().widthPixels(),
                unit.size().heightPixels(),
                sampleProbe,
                1
        );
        int bestScore = baselineScore;
        int bestIndex = -1;
        int bestDelta = 0;
        int bestSample = Integer.MIN_VALUE;
        int bestCandidateValue = 0;

        for (int coefficientIndex = 0; coefficientIndex < currentCoefficients.length; coefficientIndex++) {
            int currentValue = currentCoefficients[coefficientIndex];
            if (currentValue == 0) {
                continue;
            }
            for (int delta : new int[]{-1, 1}) {
                int[] adjustedCoefficients = currentCoefficients.clone();
                adjustedCoefficients[coefficientIndex] = currentValue + delta;
                TransformResidualUnit adjustedUnit = new TransformResidualUnit(
                        unit.position(),
                        unit.size(),
                        unit.transformType(),
                        unit.endOfBlockIndex(),
                        adjustedCoefficients,
                        unit.visibleWidthPixels(),
                        unit.visibleHeightPixels(),
                        unit.coefficientContextByte()
                );
                int[] dequantized = dequantizeUnit(adjustedUnit, header, frameHeader, bitDepth, sampleProbe.plane());
                int[] adjustedResidual = reconstructResidual(adjustedUnit, dequantized, bitDepth);
                int score = localWindowScore(
                        expectedPlanes,
                        sampleProbe.plane(),
                        predictor,
                        adjustedResidual,
                        unitX,
                        unitY,
                        unit.size().widthPixels(),
                        unit.size().heightPixels(),
                        sampleProbe,
                        1
                );
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = coefficientIndex;
                    bestDelta = delta;
                    bestSample = predictor[(sampleProbe.y() - unitY) * unit.size().widthPixels() + (sampleProbe.x() - unitX)]
                            + adjustedResidual[(sampleProbe.y() - unitY) * unit.size().widthPixels() + (sampleProbe.x() - unitX)];
                    bestCandidateValue = currentValue + delta;
                }
            }
        }

        if (bestIndex < 0) {
            System.out.println("COEFF-ADJUST no-better-single-step baselineScore=" + baselineScore);
            return;
        }

        System.out.println("COEFF-ADJUST best index=" + bestIndex
                + " original=" + currentCoefficients[bestIndex]
                + " adjusted=" + bestCandidateValue
                + " delta=" + bestDelta
                + " sample=" + bestSample
                + " baselineScore=" + baselineScore
                + " improvedScore=" + bestScore);
    }

    /// Returns one local predictor block by subtracting the reconstructed residual from the current
    /// reconstructed samples inside the unit.
    ///
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param plane the requested plane
    /// @param unitX the plane-domain unit X origin
    /// @param unitY the plane-domain unit Y origin
    /// @param unitWidth the unit width in plane samples
    /// @param unitHeight the unit height in plane samples
    /// @param residual the reconstructed residual block
    /// @return the predictor block in natural raster order
    private static int[] predictorBlock(
            DecodedPlanes reconstructed,
            Plane plane,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight,
            int[] residual
    ) {
        int[] predictor = new int[residual.length];
        for (int row = 0; row < unitHeight; row++) {
            for (int column = 0; column < unitWidth; column++) {
                SampleProbe sampleProbe = new SampleProbe("", plane, unitX + column, unitY + row);
                predictor[row * unitWidth + column] = sample(reconstructed, sampleProbe) - residual[row * unitWidth + column];
            }
        }
        return predictor;
    }

    /// Returns the absolute-error score inside one local sample window around the probed sample.
    ///
    /// @param expectedPlanes the FFmpeg source planes
    /// @param plane the requested plane
    /// @param predictor the predictor block
    /// @param residual the residual block
    /// @param unitX the plane-domain unit X origin
    /// @param unitY the plane-domain unit Y origin
    /// @param unitWidth the unit width in plane samples
    /// @param unitHeight the unit height in plane samples
    /// @param sampleProbe the requested sample
    /// @param radius the inclusive local window radius
    /// @return the local absolute-error sum
    private static int localWindowScore(
            SourcePlanes expectedPlanes,
            Plane plane,
            int[] predictor,
            int[] residual,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight,
            SampleProbe sampleProbe,
            int radius
    ) {
        int localX = sampleProbe.x() - unitX;
        int localY = sampleProbe.y() - unitY;
        int startX = Math.max(0, localX - radius);
        int endX = Math.min(unitWidth - 1, localX + radius);
        int startY = Math.max(0, localY - radius);
        int endY = Math.min(unitHeight - 1, localY + radius);
        int score = 0;
        for (int row = startY; row <= endY; row++) {
            for (int column = startX; column <= endX; column++) {
                int actual = predictor[row * unitWidth + column] + residual[row * unitWidth + column];
                int expected = expectedPlaneSample(expectedPlanes, plane, unitX + column, unitY + row);
                score += Math.abs(actual - expected);
            }
        }
        return score;
    }

    /// Returns one FFmpeg source-plane sample for the requested plane and coordinate.
    ///
    /// @param planes the FFmpeg source planes
    /// @param plane the requested plane
    /// @param x the plane-relative sample X coordinate
    /// @param y the plane-relative sample Y coordinate
    /// @return the stored sample value
    private static int expectedPlaneSample(SourcePlanes planes, Plane plane, int x, int y) {
        return switch (plane) {
            case Y -> planes.lumaSample(x, y);
            case U -> planes.chromaUSample(x, y);
            case V -> planes.chromaVSample(x, y);
        };
    }

    /// Prints a small set of alternative residual interpretations for square units.
    ///
    /// @param unit the covering residual unit
    /// @param dequantized the dequantized coefficients in the production layout
    /// @param relativeX the sample X coordinate within the transform unit
    /// @param relativeY the sample Y coordinate within the transform unit
    private static void printAlternativeResidualHypotheses(
            TransformResidualUnit unit,
            int[] dequantized,
            int relativeX,
            int relativeY
    ) throws ReflectiveOperationException {
        int width = unit.size().widthPixels();
        int height = unit.size().heightPixels();
        int sampleIndex = relativeY * width + relativeX;
        int[] currentResidual = reconstructResidual(unit, dequantized);
        String swappedTransformSummary = swappedTransformSummary(unit, dequantized, sampleIndex);
        if (width != height) {
            int[] reorderedCoefficients = reinterpretColumnMajorAsRowMajor(dequantized, width, height);
            int[] reorderedResidual = reconstructResidual(unit, reorderedCoefficients);
            System.out.println("ALT currentResidual=" + currentResidual[sampleIndex]
                    + " columnMajorCoeffResidual=" + reorderedResidual[sampleIndex]
                    + swappedTransformSummary);
            return;
        }
        int[] transposedCoefficients = transposeSquare(dequantized, width);
        int[] transposedResidual = reconstructResidual(unit, transposedCoefficients);
        int[] transposedOutput = transposeSquare(currentResidual, width);
        System.out.println("ALT currentResidual=" + currentResidual[sampleIndex]
                + " transposedCoeffResidual=" + transposedResidual[sampleIndex]
                + " transposedOutputResidual=" + transposedOutput[sampleIndex]
                + swappedTransformSummary);
    }

    /// Returns one debug summary for the same coefficients reconstructed under the axis-swapped
    /// transform type, when the active type is a two-dimensional hybrid transform.
    ///
    /// @param unit the covering residual unit
    /// @param dequantized the dequantized coefficients in the production layout
    /// @param sampleIndex the flattened sample index inside the reconstructed block
    /// @return the formatted debug suffix, or an empty string when no swap applies
    private static String swappedTransformSummary(
            TransformResidualUnit unit,
            int[] dequantized,
            int sampleIndex
    ) throws ReflectiveOperationException {
        TransformType swappedType = swappedTransformType(unit.transformType());
        if (swappedType == unit.transformType()) {
            return "";
        }
        int[] swappedResidual = reconstructResidual(unit.size(), swappedType, dequantized);
        return " swappedType=" + swappedType + " swappedResidual=" + swappedResidual[sampleIndex];
    }

    /// Returns the axis-swapped counterpart for one hybrid transform type.
    ///
    /// @param transformType the decoded transform type
    /// @return the transform type with horizontal and vertical kernels exchanged
    private static TransformType swappedTransformType(TransformType transformType) {
        return switch (transformType) {
            case ADST_DCT -> TransformType.DCT_ADST;
            case DCT_ADST -> TransformType.ADST_DCT;
            case FLIPADST_DCT -> TransformType.DCT_FLIPADST;
            case DCT_FLIPADST -> TransformType.FLIPADST_DCT;
            case ADST_FLIPADST -> TransformType.FLIPADST_ADST;
            case FLIPADST_ADST -> TransformType.ADST_FLIPADST;
            case V_DCT -> TransformType.H_DCT;
            case H_DCT -> TransformType.V_DCT;
            case V_ADST -> TransformType.H_ADST;
            case H_ADST -> TransformType.V_ADST;
            case V_FLIPADST -> TransformType.H_FLIPADST;
            case H_FLIPADST -> TransformType.V_FLIPADST;
            default -> transformType;
        };
    }

    /// Returns one transposed square raster block.
    ///
    /// @param values the square raster block
    /// @param size the square axis size
    /// @return the transposed square raster block
    private static int[] transposeSquare(int[] values, int size) {
        int[] output = new int[values.length];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                output[column * size + row] = values[row * size + column];
            }
        }
        return output;
    }

    /// Prints local predictor-score deltas for a few directional-parameter hypotheses.
    ///
    /// This keeps the residual block fixed and only varies the predictor configuration so the
    /// remaining FFmpeg error can be attributed more confidently to intra prediction or to the
    /// transform / coefficient path.
    ///
    /// @param expectedPlanes the FFmpeg source planes
    /// @param syntaxResult the decoded frame syntax
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param sampleProbe the requested sample
    private static void printPredictorHypotheses(
            SourcePlanes expectedPlanes,
            FrameSyntaxDecodeResult syntaxResult,
            DecodedPlanes reconstructed,
            SampleProbe sampleProbe
    ) {
        if (sampleProbe.plane() != Plane.Y) {
            return;
        }
        try {
            TilePartitionTreeReader.Node[][] roots =
                    FrameLocalPartitionTrees.create(syntaxResult.assembly(), syntaxResult.tileRoots());
            TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(roots, sampleProbe.x(), sampleProbe.y());
            if (leaf == null) {
                return;
            }
            TransformResidualUnit unit = findCoveringResidualUnit(leaf.residualLayout(), sampleProbe, 0, 0);
            if (unit == null || unit.allZero()) {
                return;
            }
            int bitDepth = reconstructed.bitDepth();
            int[] dequantized = dequantizeUnit(
                    unit,
                    leaf.header(),
                    syntaxResult.assembly().frameHeader(),
                    bitDepth,
                    Plane.Y
            );
            int[] residual = reconstructResidual(unit, dequantized, bitDepth);
            int unitX = unit.position().x4() << 2;
            int unitY = unit.position().y4() << 2;
            int unitWidth = unit.size().widthPixels();
            int unitHeight = unit.size().heightPixels();
            int[] baselinePredictor = predictorBlock(reconstructed, Plane.Y, unitX, unitY, unitWidth, unitHeight, residual);
            int baselineScore = localWindowScore(
                    expectedPlanes,
                    Plane.Y,
                    baselinePredictor,
                    residual,
                    unitX,
                    unitY,
                    unitWidth,
                    unitHeight,
                    sampleProbe,
                    1
            );
            System.out.println("PREDICTOR baselineScore=" + baselineScore
                    + " mode=" + leaf.header().yMode()
                    + " filterIntra=" + leaf.header().filterIntraMode());
            if (leaf.header().filterIntraMode() != null) {
                int[] regeneratedPredictor = regeneratePredictor(
                        reconstructed,
                        unitX,
                        unitY,
                        unitWidth,
                        unitHeight,
                        leaf.header()
                );
                int regeneratedScore = localWindowScore(
                        expectedPlanes,
                        Plane.Y,
                        regeneratedPredictor,
                        residual,
                        unitX,
                        unitY,
                        unitWidth,
                        unitHeight,
                        sampleProbe,
                        1
                );
                System.out.println("PREDICTOR-HYPOTHESIS filterIntra-regenerated"
                        + " score=" + regeneratedScore
                        + " sample=" + predictedSample(regeneratedPredictor, residual, unitX, unitY, sampleProbe, unitWidth));
                return;
            }

            boolean[] smoothOptions = {false, true};
            int[] topLengthOptions = {
                    unitWidth,
                    unitWidth + Math.min(unitWidth, unitHeight)
            };
            int[] leftLengthOptions = {
                    unitHeight,
                    unitHeight + Math.min(unitWidth, unitHeight)
            };
            for (boolean smooth : smoothOptions) {
                for (int topLength : topLengthOptions) {
                    for (int leftLength : leftLengthOptions) {
                        int[] predictor = regeneratePredictor(
                                reconstructed,
                                unitX,
                                unitY,
                                unitWidth,
                                unitHeight,
                                leaf.header(),
                                smooth,
                                topLength,
                                leftLength
                        );
                        int score = localWindowScore(
                                expectedPlanes,
                                Plane.Y,
                                predictor,
                                residual,
                                unitX,
                                unitY,
                                unitWidth,
                                unitHeight,
                                sampleProbe,
                                1
                        );
                        System.out.println("PREDICTOR-HYPOTHESIS smooth=" + smooth
                                + " topLength=" + topLength
                                + " leftLength=" + leftLength
                                + " score=" + score
                                + " sample=" + predictedSample(predictor, residual, unitX, unitY, sampleProbe, unitWidth));
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to inspect predictor hypotheses", exception);
        }
    }

    /// Returns one predicted sample value after adding the supplied residual back into the predictor.
    ///
    /// @param predictor the predictor block
    /// @param residual the residual block
    /// @param unitX the unit origin X
    /// @param unitY the unit origin Y
    /// @param sampleProbe the requested sample
    /// @param unitWidth the unit width
    /// @return the reconstructed sample value for the requested probe
    private static int predictedSample(
            int[] predictor,
            int[] residual,
            int unitX,
            int unitY,
            SampleProbe sampleProbe,
            int unitWidth
    ) {
        int localIndex = (sampleProbe.y() - unitY) * unitWidth + (sampleProbe.x() - unitX);
        return predictor[localIndex] + residual[localIndex];
    }

    /// Reinterprets one rectangular block from column-major coefficient storage into row-major storage.
    ///
    /// @param values the current row-major coefficient block
    /// @param width the block width
    /// @param height the block height
    /// @return the coefficients as if the input had originally been stored column-major
    private static int[] reinterpretColumnMajorAsRowMajor(int[] values, int width, int height) {
        int[] output = new int[values.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                output[row * width + column] = values[column * height + row];
            }
        }
        return output;
    }

    /// Prints the top-left, top, and left predictor references for the residual unit that covers
    /// the requested sample.
    ///
    /// @param expectedPlanes the FFmpeg source planes
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param syntaxResult the decoded frame syntax
    /// @param sampleProbe the requested sample
    private static void printDirectionalReferenceDebug(
            SourcePlanes expectedPlanes,
            DecodedPlanes reconstructed,
            FrameSyntaxDecodeResult syntaxResult,
            SampleProbe sampleProbe
    ) {
        TilePartitionTreeReader.Node[][] roots =
                FrameLocalPartitionTrees.create(syntaxResult.assembly(), syntaxResult.tileRoots());
        int chromaSubsamplingX = chromaSubsamplingX(reconstructed);
        int chromaSubsamplingY = chromaSubsamplingY(reconstructed);
        int lumaX = sampleProbe.plane() == Plane.Y ? sampleProbe.x() : sampleProbe.x() << chromaSubsamplingX;
        int lumaY = sampleProbe.plane() == Plane.Y ? sampleProbe.y() : sampleProbe.y() << chromaSubsamplingY;
        TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(roots, lumaX, lumaY);
        if (leaf == null) {
            System.out.println("REFERENCES none");
            return;
        }

        TransformResidualUnit unit = findCoveringResidualUnit(
                leaf.residualLayout(),
                sampleProbe,
                chromaSubsamplingX,
                chromaSubsamplingY
        );
        if (unit == null) {
            System.out.println("REFERENCES unit=none");
            return;
        }

        int unitX = unitPlaneX(unit, sampleProbe.plane(), chromaSubsamplingX);
        int unitY = unitPlaneY(unit, sampleProbe.plane(), chromaSubsamplingY);
        int unitWidth = unit.size().widthPixels();
        int unitHeight = unit.size().heightPixels();
        System.out.println("REFERENCES unitPosition=(" + unitX + "," + unitY + ")"
                + " unitSize=" + unitWidth + "x" + unitHeight);
        printPlaneReferences("EXPECTED", expectedPlanes, sampleProbe.plane(), unitX, unitY, unitWidth, unitHeight);
        printPlaneReferences("RECON", reconstructed, sampleProbe.plane(), unitX, unitY, unitWidth, unitHeight);
    }

    /// Prints the predictor references for one FFmpeg plane.
    ///
    /// @param label the printed stage label
    /// @param planes the FFmpeg source planes
    /// @param plane the requested plane
    /// @param unitX the residual-unit X origin
    /// @param unitY the residual-unit Y origin
    /// @param unitWidth the residual-unit width
    /// @param unitHeight the residual-unit height
    private static void printPlaneReferences(
            String label,
            SourcePlanes planes,
            Plane plane,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight
    ) {
        int topLeft = switch (plane) {
            case Y -> planes.lumaSample(Math.max(0, unitX - 1), Math.max(0, unitY - 1));
            case U -> planes.chromaUSample(Math.max(0, unitX - 1), Math.max(0, unitY - 1));
            case V -> planes.chromaVSample(Math.max(0, unitX - 1), Math.max(0, unitY - 1));
        };
        System.out.println(label + "-REF topLeft=" + topLeft);
        System.out.println(label + "-REF top =" + formatExpectedReferenceRow(planes, plane, unitX, unitY - 1, unitWidth));
        System.out.println(label + "-REF left=" + formatExpectedReferenceColumn(planes, plane, unitX - 1, unitY, unitHeight));
    }

    /// Prints the predictor references for one reconstructed plane.
    ///
    /// @param label the printed stage label
    /// @param planes the reconstructed planes
    /// @param plane the requested plane
    /// @param unitX the residual-unit X origin
    /// @param unitY the residual-unit Y origin
    /// @param unitWidth the residual-unit width
    /// @param unitHeight the residual-unit height
    private static void printPlaneReferences(
            String label,
            DecodedPlanes planes,
            Plane plane,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight
    ) {
        DecodedPlane decodedPlane = switch (plane) {
            case Y -> planes.lumaPlane();
            case U -> Objects.requireNonNull(planes.chromaUPlane(), "planes.chromaUPlane()");
            case V -> Objects.requireNonNull(planes.chromaVPlane(), "planes.chromaVPlane()");
        };
        int topLeft = decodedPlane.sample(Math.max(0, unitX - 1), Math.max(0, unitY - 1));
        System.out.println(label + "-REF topLeft=" + topLeft);
        System.out.println(label + "-REF top =" + formatDecodedReferenceRow(decodedPlane, unitX, unitY - 1, unitWidth));
        System.out.println(label + "-REF left=" + formatDecodedReferenceColumn(decodedPlane, unitX - 1, unitY, unitHeight));
    }

    /// Formats one FFmpeg reference row.
    ///
    /// @param planes the FFmpeg planes
    /// @param plane the requested plane
    /// @param startX the inclusive X origin
    /// @param y the requested row
    /// @param length the number of samples to print
    /// @return the formatted reference row
    private static String formatExpectedReferenceRow(SourcePlanes planes, Plane plane, int startX, int y, int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index != 0) {
                builder.append(' ');
            }
            int x = startX + index;
            int sample = switch (plane) {
                case Y -> planes.lumaSample(Math.max(0, x), Math.max(0, y));
                case U -> planes.chromaUSample(Math.max(0, x), Math.max(0, y));
                case V -> planes.chromaVSample(Math.max(0, x), Math.max(0, y));
            };
            builder.append(sample);
        }
        return builder.toString();
    }

    /// Formats one FFmpeg reference column.
    ///
    /// @param planes the FFmpeg planes
    /// @param plane the requested plane
    /// @param x the requested column
    /// @param startY the inclusive Y origin
    /// @param length the number of samples to print
    /// @return the formatted reference column
    private static String formatExpectedReferenceColumn(SourcePlanes planes, Plane plane, int x, int startY, int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index != 0) {
                builder.append(' ');
            }
            int y = startY + index;
            int sample = switch (plane) {
                case Y -> planes.lumaSample(Math.max(0, x), Math.max(0, y));
                case U -> planes.chromaUSample(Math.max(0, x), Math.max(0, y));
                case V -> planes.chromaVSample(Math.max(0, x), Math.max(0, y));
            };
            builder.append(sample);
        }
        return builder.toString();
    }

    /// Formats one reconstructed reference row.
    ///
    /// @param plane the reconstructed plane
    /// @param startX the inclusive X origin
    /// @param y the requested row
    /// @param length the number of samples to print
    /// @return the formatted reference row
    private static String formatDecodedReferenceRow(DecodedPlane plane, int startX, int y, int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index != 0) {
                builder.append(' ');
            }
            builder.append(plane.sample(Math.max(0, startX + index), Math.max(0, y)));
        }
        return builder.toString();
    }

    /// Formats one reconstructed reference column.
    ///
    /// @param plane the reconstructed plane
    /// @param x the requested column
    /// @param startY the inclusive Y origin
    /// @param length the number of samples to print
    /// @return the formatted reference column
    private static String formatDecodedReferenceColumn(DecodedPlane plane, int x, int startY, int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index != 0) {
                builder.append(' ');
            }
            builder.append(plane.sample(Math.max(0, x), Math.max(0, startY + index)));
        }
        return builder.toString();
    }

    /// Prints the frame-local leaf that covers one luma sample together with its transform and
    /// residual summary.
    ///
    /// @param syntaxResult the decoded frame syntax
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    private static void printCoveringLeafDebug(FrameSyntaxDecodeResult syntaxResult, int x, int y) {
        TilePartitionTreeReader.Node[][] roots =
                FrameLocalPartitionTrees.create(syntaxResult.assembly(), syntaxResult.tileRoots());
        TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(roots, x, y);
        if (leaf == null) {
            System.out.println("LEAF none");
            return;
        }

        TileBlockHeaderReader.BlockHeader header = leaf.header();
        TransformLayout transformLayout = leaf.transformLayout();
        ResidualLayout residualLayout = leaf.residualLayout();
        System.out.println("LEAF position=(" + header.position().x4() * 4 + "," + header.position().y4() * 4 + ")"
                + " size=" + header.size()
                + " intra=" + header.intra()
                + " skip=" + header.skip()
                + " yMode=" + header.yMode()
                + " yAngle=" + header.yAngle()
                + " uvMode=" + header.uvMode()
                + " uvAngle=" + header.uvAngle()
                + " filterIntra=" + header.filterIntraMode()
                + " qIndex=" + header.qIndex()
                + " segmentId=" + header.segmentId()
                + " cdefIndex=" + header.cdefIndex());
        System.out.println("TX visible=" + transformLayout.visibleWidthPixels() + "x" + transformLayout.visibleHeightPixels()
                + " maxLuma=" + transformLayout.maxLumaTransformSize()
                + " uniformLuma=" + transformLayout.uniformLumaTransformSize()
                + " variableTree=" + transformLayout.variableLumaTransformTree()
                + " lumaUnits=" + transformLayout.lumaUnits().length);
        for (TransformResidualUnit unit : residualLayout.lumaUnits()) {
            System.out.println("RESIDUAL position=(" + unit.position().x4() * 4 + "," + unit.position().y4() * 4 + ")"
                    + " size=" + unit.size()
                    + " type=" + unit.transformType()
                    + " eob=" + unit.endOfBlockIndex()
                    + " nonZero=" + countNonZero(unit.coefficients())
                    + " coeffs=" + formatNonZeroCoefficients(unit.coefficients(), 12));
        }
    }

    /// Returns the frame-local leaf that covers one luma sample, or `null`.
    ///
    /// @param roots the frame-local tile roots
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    /// @return the frame-local leaf that covers one luma sample, or `null`
    private static @Nullable TilePartitionTreeReader.LeafNode findCoveringLeaf(
            TilePartitionTreeReader.Node[][] roots,
            int x,
            int y
    ) {
        for (TilePartitionTreeReader.Node[] tileRoots : roots) {
            for (TilePartitionTreeReader.Node root : tileRoots) {
                @Nullable TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(root, x, y);
                if (leaf != null) {
                    return leaf;
                }
            }
        }
        return null;
    }

    /// Returns the leaf under one node that covers one luma sample, or `null`.
    ///
    /// @param node the current frame-local node
    /// @param x the luma sample X coordinate
    /// @param y the luma sample Y coordinate
    /// @return the covering leaf, or `null`
    private static @Nullable TilePartitionTreeReader.LeafNode findCoveringLeaf(
            TilePartitionTreeReader.Node node,
            int x,
            int y
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            int startX = leafNode.header().position().x4() * 4;
            int startY = leafNode.header().position().y4() * 4;
            int endX = startX + leafNode.transformLayout().visibleWidthPixels();
            int endY = startY + leafNode.transformLayout().visibleHeightPixels();
            if (x >= startX && x < endX && y >= startY && y < endY) {
                return leafNode;
            }
            return null;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            @Nullable TilePartitionTreeReader.LeafNode leaf = findCoveringLeaf(child, x, y);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    /// Returns the number of non-zero transform coefficients.
    ///
    /// @param coefficients the transform-domain coefficients
    /// @return the number of non-zero transform coefficients
    private static int countNonZero(int[] coefficients) {
        int count = 0;
        for (int coefficient : coefficients) {
            if (coefficient != 0) {
                count++;
            }
        }
        return count;
    }

    /// Formats up to the first few non-zero transform coefficients.
    ///
    /// @param coefficients the transform-domain coefficients
    /// @param limit the maximum number of non-zero entries to print
    /// @return a compact non-zero coefficient summary
    private static String formatNonZeroCoefficients(int[] coefficients, int limit) {
        StringBuilder builder = new StringBuilder("[");
        int printed = 0;
        for (int i = 0; i < coefficients.length; i++) {
            int coefficient = coefficients[i];
            if (coefficient == 0) {
                continue;
            }
            if (printed != 0) {
                builder.append(", ");
            }
            builder.append(i).append(':').append(coefficient);
            printed++;
            if (printed == limit) {
                break;
            }
        }
        if (printed == 0) {
            builder.append("none");
        } else if (printed < countNonZero(coefficients)) {
            builder.append(", ...");
        }
        builder.append(']');
        return builder.toString();
    }

    /// Returns one declared nested class by simple name.
    ///
    /// @param owner the owner class
    /// @param simpleName the nested simple name
    /// @return the nested class
    private static Class<?> nestedClass(Class<?> owner, String simpleName) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        throw new IllegalArgumentException("Missing nested class: " + owner.getName() + "." + simpleName);
    }

    /// Returns the horizontal chroma subsampling shift derived from the decoded plane sizes.
    ///
    /// @param reconstructed the reconstructed planes
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(DecodedPlanes reconstructed) {
        if (!reconstructed.hasChroma()) {
            return 0;
        }
        DecodedPlane chromaPlane = Objects.requireNonNull(reconstructed.chromaUPlane(), "reconstructed.chromaUPlane()");
        return chromaPlane.width() * 2 == reconstructed.lumaPlane().width() ? 1 : 0;
    }

    /// Returns the vertical chroma subsampling shift derived from the decoded plane sizes.
    ///
    /// @param reconstructed the reconstructed planes
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(DecodedPlanes reconstructed) {
        if (!reconstructed.hasChroma()) {
            return 0;
        }
        DecodedPlane chromaPlane = Objects.requireNonNull(reconstructed.chromaUPlane(), "reconstructed.chromaUPlane()");
        return chromaPlane.height() * 2 == reconstructed.lumaPlane().height() ? 1 : 0;
    }

    /// Returns the residual unit that covers the requested sample on the requested plane.
    ///
    /// @param residualLayout the covering leaf residual layout
    /// @param sampleProbe the requested sample
    /// @param chromaSubsamplingX the horizontal chroma subsampling shift
    /// @param chromaSubsamplingY the vertical chroma subsampling shift
    /// @return the covering residual unit, or `null`
    private static @Nullable TransformResidualUnit findCoveringResidualUnit(
            ResidualLayout residualLayout,
            SampleProbe sampleProbe,
            int chromaSubsamplingX,
            int chromaSubsamplingY
    ) {
        TransformResidualUnit[] units = switch (sampleProbe.plane()) {
            case Y -> residualLayout.lumaUnits();
            case U -> residualLayout.chromaUUnits();
            case V -> residualLayout.chromaVUnits();
        };
        for (TransformResidualUnit unit : units) {
            int startX = unitPlaneX(unit, sampleProbe.plane(), chromaSubsamplingX);
            int startY = unitPlaneY(unit, sampleProbe.plane(), chromaSubsamplingY);
            int endX = startX + unit.visibleWidthPixels();
            int endY = startY + unit.visibleHeightPixels();
            if (sampleProbe.x() >= startX && sampleProbe.x() < endX
                    && sampleProbe.y() >= startY && sampleProbe.y() < endY) {
                return unit;
            }
        }
        return null;
    }

    /// Returns the plane-domain X origin for one residual unit.
    ///
    /// @param unit the residual unit
    /// @param plane the requested plane
    /// @param chromaSubsamplingX the horizontal chroma subsampling shift
    /// @return the plane-domain X origin
    private static int unitPlaneX(TransformResidualUnit unit, Plane plane, int chromaSubsamplingX) {
        return switch (plane) {
            case Y -> unit.position().x4() << 2;
            case U, V -> unit.position().x4() << (2 - chromaSubsamplingX);
        };
    }

    /// Returns the plane-domain Y origin for one residual unit.
    ///
    /// @param unit the residual unit
    /// @param plane the requested plane
    /// @param chromaSubsamplingY the vertical chroma subsampling shift
    /// @return the plane-domain Y origin
    private static int unitPlaneY(TransformResidualUnit unit, Plane plane, int chromaSubsamplingY) {
        return switch (plane) {
            case Y -> unit.position().y4() << 2;
            case U, V -> unit.position().y4() << (2 - chromaSubsamplingY);
        };
    }

    /// Dequantizes one residual unit using the production reconstruction contexts via reflection.
    ///
    /// @param unit the covering residual unit
    /// @param header the covering block header
    /// @param frameHeader the frame header
    /// @param bitDepth the decoded sample bit depth
    /// @param plane the requested plane
    /// @return the dequantized coefficients
    private static int[] dequantizeUnit(
            TransformResidualUnit unit,
            TileBlockHeaderReader.BlockHeader header,
            FrameHeader frameHeader,
            int bitDepth,
            Plane plane
    ) throws ReflectiveOperationException {
        int qIndex = blockQIndex(header, frameHeader);
        FrameHeader.QuantizationInfo quantization = frameHeader.quantization();
        if (plane == Plane.Y) {
            Class<?> contextClass = Class.forName("org.glavo.avif.internal.av1.recon.LumaDequantizer$Context");
            Constructor<?> constructor = contextClass.getDeclaredConstructor(
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    int.class
            );
            constructor.setAccessible(true);
            Object context = constructor.newInstance(
                    qIndex,
                    quantization.yDcDelta(),
                    bitDepth,
                    quantization.useQuantizationMatrices(),
                    quantization.quantizationMatrixY()
            );
            Class<?> dequantizerClass = Class.forName("org.glavo.avif.internal.av1.recon.LumaDequantizer");
            Method method = dequantizerClass.getDeclaredMethod("dequantize", TransformResidualUnit.class, contextClass);
            method.setAccessible(true);
            return (int[]) method.invoke(null, unit, context);
        }

        int dcDelta = plane == Plane.U ? quantization.uDcDelta() : quantization.vDcDelta();
        int acDelta = plane == Plane.U ? quantization.uAcDelta() : quantization.vAcDelta();
        int quantizationMatrix = plane == Plane.U ? quantization.quantizationMatrixU() : quantization.quantizationMatrixV();
        Class<?> contextClass = Class.forName("org.glavo.avif.internal.av1.recon.ChromaDequantizer$Context");
        Constructor<?> constructor = contextClass.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class
        );
        constructor.setAccessible(true);
        Object context = constructor.newInstance(
                qIndex,
                dcDelta,
                acDelta,
                bitDepth,
                quantization.useQuantizationMatrices(),
                quantizationMatrix
        );
        Class<?> dequantizerClass = Class.forName("org.glavo.avif.internal.av1.recon.ChromaDequantizer");
        Method method = dequantizerClass.getDeclaredMethod("dequantize", TransformResidualUnit.class, contextClass);
        method.setAccessible(true);
        return (int[]) method.invoke(null, unit, context);
    }

    /// Reconstructs one residual block via the production inverse transformer using reflection.
    ///
    /// @param unit the covering residual unit
    /// @param dequantized the dequantized coefficients
    /// @return the reconstructed residual block
    private static int[] reconstructResidual(TransformResidualUnit unit, int[] dequantized)
            throws ReflectiveOperationException {
        return reconstructResidual(unit.size(), unit.transformType(), dequantized);
    }

    /// Reconstructs one residual block via the production inverse transformer using reflection and
    /// an explicit transform type.
    ///
    /// @param transformSize the coded transform size
    /// @param transformType the transform type to apply
    /// @param dequantized the dequantized coefficients
    /// @return the reconstructed residual block
    private static int[] reconstructResidual(
            TransformSize transformSize,
            TransformType transformType,
            int[] dequantized
    ) throws ReflectiveOperationException {
        Class<?> transformerClass = Class.forName("org.glavo.avif.internal.av1.recon.InverseTransformer");
        Method method = transformerClass.getDeclaredMethod(
                "reconstructResidualBlock",
                int[].class,
                org.glavo.avif.internal.av1.model.TransformSize.class,
                org.glavo.avif.internal.av1.model.TransformType.class
        );
        method.setAccessible(true);
        return (int[]) method.invoke(null, dequantized, transformSize, transformType);
    }

    /// Reconstructs one residual block via the production inverse transformer using reflection and
    /// the explicit decoded bit depth.
    ///
    /// @param unit the covering residual unit
    /// @param dequantized the dequantized coefficients
    /// @param bitDepth the decoded sample bit depth
    /// @return the reconstructed residual block
    private static int[] reconstructResidual(TransformResidualUnit unit, int[] dequantized, int bitDepth)
            throws ReflectiveOperationException {
        Class<?> transformerClass = Class.forName("org.glavo.avif.internal.av1.recon.InverseTransformer");
        Method method = transformerClass.getDeclaredMethod(
                "reconstructResidualBlock",
                int[].class,
                org.glavo.avif.internal.av1.model.TransformSize.class,
                org.glavo.avif.internal.av1.model.TransformType.class,
                int.class
        );
        method.setAccessible(true);
        return (int[]) method.invoke(null, dequantized, unit.size(), unit.transformType(), bitDepth);
    }

    /// Re-runs the production luma predictor for one unit with the current predictor controls.
    ///
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param unitX the unit origin X
    /// @param unitY the unit origin Y
    /// @param unitWidth the unit width
    /// @param unitHeight the unit height
    /// @param header the covering block header
    /// @return the regenerated predictor block
    private static int[] regeneratePredictor(
            DecodedPlanes reconstructed,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight,
            TileBlockHeaderReader.BlockHeader header
    ) throws ReflectiveOperationException {
        return regeneratePredictor(reconstructed, unitX, unitY, unitWidth, unitHeight, header, false, -1, -1);
    }

    /// Re-runs the production luma predictor for one unit while overriding directional controls.
    ///
    /// @param reconstructed the reconstructed planes before postfilters
    /// @param unitX the unit origin X
    /// @param unitY the unit origin Y
    /// @param unitWidth the unit width
    /// @param unitHeight the unit height
    /// @param header the covering block header
    /// @param smoothEdgeReferences the overridden smooth-edge marker
    /// @param topLength the overridden directional top-reference length, or `-1`
    /// @param leftLength the overridden directional left-reference length, or `-1`
    /// @return the regenerated predictor block
    private static int[] regeneratePredictor(
            DecodedPlanes reconstructed,
            int unitX,
            int unitY,
            int unitWidth,
            int unitHeight,
            TileBlockHeaderReader.BlockHeader header,
            boolean smoothEdgeReferences,
            int topLength,
            int leftLength
    ) throws ReflectiveOperationException {
        Object mutablePlane = mutablePlaneFromDecodedPlane(reconstructed.lumaPlane(), reconstructed.bitDepth());
        clearMutablePlaneRegion(mutablePlane, unitX, unitY, unitWidth, unitHeight, 0);
        Class<?> predictorClass = Class.forName("org.glavo.avif.internal.av1.recon.IntraPredictor");
        FilterIntraMode filterIntraMode = header.filterIntraMode();
        if (filterIntraMode != null) {
            Method method = predictorClass.getDeclaredMethod(
                    "predictFilterIntraLuma",
                    mutablePlane.getClass(),
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    FilterIntraMode.class
            );
            method.setAccessible(true);
            method.invoke(null, mutablePlane, unitX, unitY, unitWidth, unitHeight, filterIntraMode);
        } else {
            Method method = predictorClass.getDeclaredMethod(
                    "predictLuma",
                    mutablePlane.getClass(),
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    LumaIntraPredictionMode.class,
                    int.class,
                    boolean.class,
                    boolean.class,
                    int.class,
                    int.class
            );
            method.setAccessible(true);
            method.invoke(
                    null,
                    mutablePlane,
                    unitX,
                    unitY,
                    unitWidth,
                    unitHeight,
                    Objects.requireNonNull(header.yMode(), "header.yMode()"),
                    header.yAngle(),
                    true,
                    smoothEdgeReferences,
                    topLength,
                    leftLength
            );
        }
        return mutablePlaneRegion(mutablePlane, unitX, unitY, unitWidth, unitHeight);
    }

    /// Creates one mutable luma plane clone from one decoded plane through reflection.
    ///
    /// @param plane the immutable decoded plane
    /// @param bitDepth the decoded sample bit depth
    /// @return the reflected mutable plane instance
    private static Object mutablePlaneFromDecodedPlane(DecodedPlane plane, int bitDepth)
            throws ReflectiveOperationException {
        Class<?> mutablePlaneClass = Class.forName("org.glavo.avif.internal.av1.recon.MutablePlaneBuffer");
        Constructor<?> constructor = mutablePlaneClass.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object mutablePlane = constructor.newInstance(plane.width(), plane.height(), bitDepth);
        Method setSample = mutablePlaneClass.getDeclaredMethod("setSample", int.class, int.class, int.class);
        setSample.setAccessible(true);
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                setSample.invoke(mutablePlane, x, y, plane.sample(x, y));
            }
        }
        return mutablePlane;
    }

    /// Clears one rectangular region inside one reflected mutable plane.
    ///
    /// @param mutablePlane the reflected mutable plane
    /// @param x the region origin X
    /// @param y the region origin Y
    /// @param width the region width
    /// @param height the region height
    /// @param value the replacement sample value
    private static void clearMutablePlaneRegion(
            Object mutablePlane,
            int x,
            int y,
            int width,
            int height,
            int value
    ) throws ReflectiveOperationException {
        Method setSample = mutablePlane.getClass().getDeclaredMethod("setSample", int.class, int.class, int.class);
        setSample.setAccessible(true);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                setSample.invoke(mutablePlane, x + column, y + row, value);
            }
        }
    }

    /// Reads one rectangular region from one reflected mutable plane.
    ///
    /// @param mutablePlane the reflected mutable plane
    /// @param x the region origin X
    /// @param y the region origin Y
    /// @param width the region width
    /// @param height the region height
    /// @return the region samples in raster order
    private static int[] mutablePlaneRegion(
            Object mutablePlane,
            int x,
            int y,
            int width,
            int height
    ) throws ReflectiveOperationException {
        Method sample = mutablePlane.getClass().getDeclaredMethod("sample", int.class, int.class);
        sample.setAccessible(true);
        int[] values = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                values[row * width + column] = (int) sample.invoke(mutablePlane, x + column, y + row);
            }
        }
        return values;
    }

    /// Returns the effective block-local qindex after applying active segment delta-q.
    ///
    /// @param header the covering block header
    /// @param frameHeader the frame header
    /// @return the effective block-local qindex
    private static int blockQIndex(TileBlockHeaderReader.BlockHeader header, FrameHeader frameHeader) {
        FrameHeader.SegmentationInfo segmentation = frameHeader.segmentation();
        if (!segmentation.enabled()) {
            return header.qIndex();
        }
        return Math.max(0, Math.min(255, header.qIndex() + segmentation.segment(header.segmentId()).deltaQ()));
    }

    /// The requested source plane.
    private enum Plane {
        Y,
        U,
        V
    }

    /// One failing sample probe.
    ///
    /// @param resourceName the AVIF fixture resource
    /// @param plane the failing source plane
    /// @param x the plane-relative sample X coordinate
    /// @param y the plane-relative sample Y coordinate
    private record SampleProbe(String resourceName, Plane plane, int x, int y) {
        /// Returns one shifted sample probe for neighborhood printing.
        ///
        /// @param dx the horizontal offset
        /// @param dy the vertical offset
        /// @return the shifted probe
        private SampleProbe offset(int dx, int dy) {
            return new SampleProbe(resourceName, plane, x + dx, y + dy);
        }
    }
}
