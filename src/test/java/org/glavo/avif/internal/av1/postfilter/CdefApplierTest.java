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
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.decode.Av1DecoderConfig;
import org.glavo.avif.decode.Av1ImageReader;
import org.glavo.avif.internal.av1.decode.FrameLocalPartitionTrees;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.FrameReconstructor;
import org.glavo.avif.internal.bmff.AvifContainer;
import org.glavo.avif.internal.bmff.AvifContainerParser;
import org.glavo.avif.internal.io.BufferedInput;
import org.glavo.avif.testutil.TestResources;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Tests for `CdefApplier`.
@NotNullByDefault
final class CdefApplierTest {
    /// The `kodim23` regression resource that exposes a secondary-only CDEF unit.
    private static final String KODIM23_RESOURCE = "libavif-test-data/io/kodim23_yuv420_8bpc.avif";

    /// Verifies that secondary-only CDEF units still honor the detected direction.
    @Test
    void applyUsesDetectedDirectionForSecondaryOnlyStrength() throws IOException, URISyntaxException {
        byte[] bytes = TestResources.readBytes(KODIM23_RESOURCE);
        AvifContainer container = AvifContainerParser.parse(bytes);
        ByteBuffer primaryPayload = Objects.requireNonNull(container.primaryItemPayload(), "primaryItemPayload");
        try (Av1ImageReader reader = Av1ImageReader.open(
                new BufferedInput.OfByteBuffer(primaryPayload),
                Av1DecoderConfig.DEFAULT
        )) {
            reader.readFrame();
            FrameSyntaxDecodeResult syntaxDecodeResult =
                    Objects.requireNonNull(lastFrameSyntaxDecodeResult(reader), "lastFrameSyntaxDecodeResult");
            FrameHeader frameHeader = syntaxDecodeResult.assembly().frameHeader();
            DecodedPlanes reconstructed = new FrameReconstructor().reconstruct(syntaxDecodeResult);
            DecodedPlanes afterLoopFilter = new LoopFilterApplier().apply(reconstructed, frameHeader, syntaxDecodeResult);
            DecodedPlanes filtered = new CdefApplier().apply(afterLoopFilter, frameHeader.cdef(), syntaxDecodeResult);

            DecodedPlane lumaPlane = afterLoopFilter.lumaPlane();
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
            assertEquals(112, filtered.lumaPlane().sample(567, 126));
            assertRegionEquals(filtered.lumaPlane(), expectedDetected, startX, startY, startX + 8, startY + 8);
            assertFalse(regionEquals(filtered.lumaPlane(), expectedZeroDirection, startX, startY, startX + 8, startY + 8));
            assertNotEquals(expectedZeroDirection[targetIndex], filtered.lumaPlane().samples()[targetIndex]);
        }
    }

    /// Returns the detected CDEF direction for one 8x8 luma unit.
    ///
    /// @param plane the source luma plane
    /// @param startX the CDEF-unit start X coordinate
    /// @param startY the CDEF-unit start Y coordinate
    /// @return the detected CDEF direction
    private static int detectDirection(DecodedPlane plane, int startX, int startY) {
        try {
            Method detectDirection = declaredMethod(
                    "detectDirection",
                    DecodedPlane.class,
                    short[].class,
                    int.class,
                    int.class,
                    int.class
            );
            Object direction = detectDirection.invoke(null, plane, plane.samples(), startX, startY, 0);
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
            DecodedPlane plane,
            int startX,
            int startY,
            int endX,
            int endY,
            int damping,
            int encodedStrength,
            int direction
    ) {
        try {
            Method decodeStrength = declaredMethod("decodeStrength", int.class, int.class);
            Object strength = decodeStrength.invoke(null, encodedStrength, 0);
            Method filterUnit = declaredMethod(
                    "filterUnit",
                    DecodedPlane.class,
                    short[].class,
                    short[].class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    strength.getClass(),
                    int.class,
                    int.class,
                    int.class
            );
            short[] outputSamples = Arrays.copyOf(plane.samples(), plane.samples().length);
            filterUnit.invoke(
                    null,
                    plane,
                    plane.samples(),
                    outputSamples,
                    startX,
                    startY,
                    endX,
                    endY,
                    damping,
                    strength,
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

    /// Returns the last decoded frame syntax result stored on one `Av1ImageReader`.
    ///
    /// @param reader the image reader that decoded one frame
    /// @return the stored frame syntax result, or `null`
    private static @Nullable FrameSyntaxDecodeResult lastFrameSyntaxDecodeResult(Av1ImageReader reader) {
        try {
            Field field = Av1ImageReader.class.getDeclaredField("lastFrameSyntaxDecodeResult");
            field.setAccessible(true);
            return (FrameSyntaxDecodeResult) field.get(reader);
        } catch (IllegalAccessException | NoSuchFieldException exception) {
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
            DecodedPlane plane,
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
            DecodedPlane plane,
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
