// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.av1.Av1DecoderConfig;
import org.glavo.avif.av1.Av1Decoder;
import org.glavo.avif.internal.av1.decode.FrameLocalPartitionTrees;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.image.PaddedPlane;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.bmff.AvifContainer;
import org.glavo.avif.internal.bmff.AvifContainerParser;
import org.glavo.avif.internal.bmff.AvifImageSource;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `CdefApplier`.
@NotNullByDefault
final class CdefApplierTest {
    /// The `kodim23` regression resource that exposes a secondary-only CDEF unit.
    private static final String KODIM23_RESOURCE = "libavif-test-data/io/kodim23_yuv420_8bpc.avif";

    /// Verifies that secondary-only CDEF units use direction zero instead of the detected direction.
    @Test
    void applyUsesZeroDirectionForSecondaryOnlyStrength() throws IOException, URISyntaxException {
        byte[] bytes = TestResources.readBytes(KODIM23_RESOURCE);
        AvifContainer container = AvifContainerParser.parse(bytes);
        AvifImageSource primarySource = Objects.requireNonNull(container.primarySource(), "primarySource");
        try (Av1Decoder reader = Av1Decoder.open(
                primarySource.payload(0).openInput(),
                Av1DecoderConfig.DEFAULT
        )) {
            retainFrameSyntaxDecodeResultsForInspection(reader);
            reader.readFrame();
            FrameSyntaxDecodeResult syntaxDecodeResult =
                    Objects.requireNonNull(lastFrameSyntaxDecodeResult(reader), "lastFrameSyntaxDecodeResult");
            FrameHeader frameHeader = syntaxDecodeResult.assembly().frameHeader();
            DecodedSurface reconstructed = new FrameReconstructor().reconstruct(syntaxDecodeResult);
            DecodedSurface afterLoopFilter = new LoopFilterApplier().apply(reconstructed, frameHeader, syntaxDecodeResult);
            DecodedSurface filtered = new CdefApplier().apply(afterLoopFilter, frameHeader.cdef(), syntaxDecodeResult);

            PaddedPlane lumaPlane = afterLoopFilter.lumaPlane();
            int startX = 560;
            int startY = 120;
            int detectedDirection = detectDirection(lumaPlane, startX, startY);
            short[] expectedDetected = filterUnit(
                    lumaPlane,
                    startX,
                    startY,
                    startX + 8,
                    startY + 8,
                    frameHeader.cdef().damping(),
                    2,
                    detectedDirection
            );
            short[] expectedZeroDirection = filterUnit(
                    lumaPlane,
                    startX,
                    startY,
                    startX + 8,
                    startY + 8,
                    frameHeader.cdef().damping(),
                    2,
                    0
            );
            int targetIndex = 126 * filtered.lumaPlane().stride() + 567;
            assertEquals(3, selectedCdefIndex(syntaxDecodeResult, frameHeader, startX, startY));
            assertNotEquals(0, detectedDirection);
            assertEquals(111, filtered.lumaPlane().sample(567, 126));
            assertRegionEquals(filtered.lumaPlane(), expectedZeroDirection, startX, startY, startX + 8, startY + 8);
            assertFalse(regionEquals(filtered.lumaPlane(), expectedDetected, startX, startY, startX + 8, startY + 8));
            assertEquals(expectedZeroDirection[targetIndex], filtered.lumaPlane().samples()[targetIndex]);
        }
    }

    /// Verifies that CDEF reads reconstructed padding inside the aligned 8x8 processing grid.
    @Test
    void applyUsesStoredPaddingAtPartialFrameEdge() {
        short[] baselineSamples = new short[8 * 8];
        Arrays.fill(baselineSamples, (short) 100);
        short[] paddedSamples = Arrays.copyOf(baselineSamples, baselineSamples.length);
        for (int y = 0; y < 8; y++) {
            paddedSamples[y * 8 + 7] = 104;
        }
        DecodedSurface baselinePlanes = new DecodedSurface(
                8,
                Av1ChromaFormat.MONOCHROME,
                7,
                8,
                7,
                8,
                new PaddedPlane(7, 8, 8, baselineSamples),
                null,
                null
        );
        DecodedSurface paddedPlanes = new DecodedSurface(
                8,
                Av1ChromaFormat.MONOCHROME,
                7,
                8,
                7,
                8,
                new PaddedPlane(7, 8, 8, paddedSamples),
                null,
                null
        );
        FrameHeader.CdefInfo cdef = new FrameHeader.CdefInfo(6, 0, new int[]{3}, new int[0]);
        FrameHeader frameHeader = PostfilterTestFixtures.createFrameHeader(
                Av1ChromaFormat.MONOCHROME,
                7,
                8,
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        false,
                        false,
                        new int[8],
                        new int[2]
                ),
                cdef,
                new FrameHeader.RestorationInfo(
                        new FrameHeader.RestorationType[]{
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE,
                                FrameHeader.RestorationType.NONE
                        },
                        0,
                        0
                ),
                PostfilterTestFixtures.disabledFilmGrain()
        );
        FrameSyntaxDecodeResult syntaxDecodeResult =
                PostfilterTestFixtures.createSingleLeafSyntaxResult(frameHeader, 0);

        DecodedSurface baselineFiltered = new CdefApplier().apply(baselinePlanes, cdef, syntaxDecodeResult);
        DecodedSurface paddedFiltered = new CdefApplier().apply(paddedPlanes, cdef, syntaxDecodeResult);

        assertEquals(100, baselineFiltered.lumaPlane().sample(6, 4));
        assertTrue(paddedFiltered.lumaPlane().sample(6, 4) > baselineFiltered.lumaPlane().sample(6, 4));
        assertEquals(100, paddedPlanes.lumaPlane().sample(6, 4));
    }

    /// Returns the detected CDEF direction for one 8x8 luma unit.
    ///
    /// @param plane the source luma plane
    /// @param startX the CDEF-unit start X coordinate
    /// @param startY the CDEF-unit start Y coordinate
    /// @return the detected CDEF direction
    private static int detectDirection(PaddedPlane plane, int startX, int startY) {
        try {
            Method detectDirection = declaredMethod(
                    "detectDirection",
                    PaddedPlane.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class
            );
            Object direction = detectDirection.invoke(
                    null,
                    plane,
                    startX,
                    startY,
                    plane.width(),
                    plane.height(),
                    0
            );
            Method directionAccessor = direction.getClass().getDeclaredMethod("direction");
            directionAccessor.setAccessible(true);
            return (int) directionAccessor.invoke(direction);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Filters one 8x8 luma unit with a fixed direction through the private CDEF kernel.
    ///
    /// @param plane the source luma plane
    /// @param startX the inclusive unit start X coordinate
    /// @param startY the inclusive unit start Y coordinate
    /// @param endX the exclusive unit end X coordinate
    /// @param endY the exclusive unit end Y coordinate
    /// @param damping the selected CDEF damping value
    /// @param encodedStrength the packed CDEF strength
    /// @param direction the CDEF direction to apply
    /// @return the filtered sample raster
    private static short[] filterUnit(
            PaddedPlane plane,
            int startX,
            int startY,
            int endX,
            int endY,
            int damping,
            int encodedStrength,
        int direction
    ) {
        try {
            Method decodePrimaryStrength = declaredMethod("decodePrimaryStrength", int.class, int.class);
            Method decodeSecondaryStrength = declaredMethod("decodeSecondaryStrength", int.class, int.class);
            int primaryStrength = (int) decodePrimaryStrength.invoke(null, encodedStrength, 0);
            int secondaryStrength = (int) decodeSecondaryStrength.invoke(null, encodedStrength, 0);
            Method filterUnit = declaredMethod(
                    "filterUnit",
                    PaddedPlane.class,
                    short[].class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class
            );
            short[] outputSamples = plane.samples();
            filterUnit.invoke(
                    null,
                    plane,
                    outputSamples,
                    startX,
                    startY,
                    endX,
                    endY,
                    plane.width(),
                    plane.height(),
                    damping,
                    primaryStrength,
                    secondaryStrength,
                    direction,
                    0,
                    255
            );
            return outputSamples;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Returns the selected CDEF index for one luma 8x8 unit in the decoded syntax tree.
    ///
    /// @param syntaxDecodeResult the decoded frame syntax
    /// @param frameHeader the decoded frame header
    /// @param startX the luma-unit start X coordinate
    /// @param startY the luma-unit start Y coordinate
    /// @return the selected CDEF index for the unit
    private static int selectedCdefIndex(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            FrameHeader frameHeader,
            int startX,
            int startY
    ) {
        int unitX = startX >> 3;
        int unitY = startY >> 3;
        for (int tileIndex = 0; tileIndex < syntaxDecodeResult.tileCount(); tileIndex++) {
            TilePartitionTreeReader.Node[] roots = FrameLocalPartitionTrees.create(
                    syntaxDecodeResult.assembly(),
                    tileIndex,
                    syntaxDecodeResult.tileRoots(tileIndex)
            );
            for (TilePartitionTreeReader.Node root : roots) {
                @Nullable Integer cdefIndex = selectedCdefIndex(root, unitX, unitY);
                if (cdefIndex != null) {
                    return cdefIndex;
                }
            }
        }
        throw new AssertionError(
                "missing CDEF unit for "
                        + frameHeader.frameSize().codedWidth()
                        + "x"
                        + frameHeader.frameSize().height()
        );
    }

    /// Returns the selected CDEF index for one unit covered by one partition-tree node, or `null`.
    ///
    /// @param node the partition-tree node to inspect
    /// @param unitX the luma-unit X coordinate
    /// @param unitY the luma-unit Y coordinate
    /// @return the selected CDEF index for the unit, or `null`
    private static @Nullable Integer selectedCdefIndex(TilePartitionTreeReader.Node node, int unitX, int unitY) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            int startUnitX = leafNode.header().position().x4() >> 1;
            int startUnitY = leafNode.header().position().y4() >> 1;
            int endUnitX = (leafNode.header().position().x4() + leafNode.transformLayout().visibleWidth4() + 1) >> 1;
            int endUnitY = (leafNode.header().position().y4() + leafNode.transformLayout().visibleHeight4() + 1) >> 1;
            if (unitX >= startUnitX && unitX < endUnitX && unitY >= startUnitY && unitY < endUnitY) {
                return leafNode.header().cdefIndex();
            }
            return null;
        }
        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            @Nullable Integer childIndex = selectedCdefIndex(child, unitX, unitY);
            if (childIndex != null) {
                return childIndex;
            }
        }
        return null;
    }

    /// Returns the last decoded frame syntax result stored on one `Av1Decoder`.
    ///
    /// @param reader the image reader that decoded one frame
    /// @return the stored frame syntax result, or `null`
    private static @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult(Av1Decoder reader) {
        try {
            Field field = Av1Decoder.class.getDeclaredField("lastFrameSyntaxDecodeResult");
            field.setAccessible(true);
            return (FrameSyntaxDecodeResult) field.get(reader);
        } catch (IllegalAccessException | NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Enables retaining decoded frame syntax on one reader for this structural postfilter test.
    ///
    /// @param reader the reader to configure before decoding
    private static void retainFrameSyntaxDecodeResultsForInspection(Av1Decoder reader) {
        try {
            Method method = Av1Decoder.class.getDeclaredMethod("retainFrameSyntaxDecodeResultsForInspection");
            method.setAccessible(true);
            method.invoke(reader);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Verifies that one filtered rectangular region matches one expected sample raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expectedSamples the expected full-plane sample raster
    /// @param startX the inclusive region start X coordinate
    /// @param startY the inclusive region start Y coordinate
    /// @param endX the exclusive region end X coordinate
    /// @param endY the exclusive region end Y coordinate
    private static void assertRegionEquals(
            PaddedPlane plane,
            short[] expectedSamples,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int index = y * plane.stride() + x;
                assertEquals(expectedSamples[index], plane.samples()[index], "sample mismatch at (" + x + "," + y + ")");
            }
        }
    }

    /// Returns whether one filtered rectangular region matches one expected sample raster.
    ///
    /// @param plane the decoded plane to inspect
    /// @param expectedSamples the expected full-plane sample raster
    /// @param startX the inclusive region start X coordinate
    /// @param startY the inclusive region start Y coordinate
    /// @param endX the exclusive region end X coordinate
    /// @param endY the exclusive region end Y coordinate
    /// @return whether the rectangular region matches
    private static boolean regionEquals(
            PaddedPlane plane,
            short[] expectedSamples,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int index = y * plane.stride() + x;
                if (expectedSamples[index] != plane.samples()[index]) {
                    return false;
                }
            }
        }
        return true;
    }

    /// Returns one accessible private static method declared on `CdefApplier`.
    ///
    /// @param name the declared method name
    /// @param parameterTypes the declared parameter types
    /// @return the accessible private static method
    private static Method declaredMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = CdefApplier.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}
