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
package org.glavo.avif.internal.av1.recon;

import org.glavo.avif.Av1ChromaFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests raster assembly of decoded Large Scale Tile regions.
@NotNullByDefault
final class LargeScaleTileOutputBuilderTest {
    /// Verifies luma and subsampled chroma copies from arbitrary camera-frame tile coordinates.
    @Test
    void copiesSelectedTilesInOutputRasterOrder() {
        DecodedPlanes source = sourcePlanes();
        LargeScaleTileOutputBuilder builder = new LargeScaleTileOutputBuilder(
                10,
                Av1ChromaFormat.YUV420,
                4,
                4,
                2,
                2
        );

        builder.copyTile(source, 1, 0, 0);
        builder.copyTile(source, 0, 1, 1);
        DecodedPlanes output = builder.build();

        assertEquals(8, output.codedWidth());
        assertEquals(8, output.codedHeight());
        assertEquals(source.lumaPlane().sample(4, 0), output.lumaPlane().sample(0, 0));
        assertEquals(source.lumaPlane().sample(7, 3), output.lumaPlane().sample(3, 3));
        assertEquals(source.lumaPlane().sample(0, 4), output.lumaPlane().sample(4, 0));
        assertEquals(source.lumaPlane().sample(3, 7), output.lumaPlane().sample(7, 3));
        assertEquals(0, output.lumaPlane().sample(0, 4));
        assertEquals(0, output.lumaPlane().sample(7, 7));

        DecodedPlane sourceU = Objects.requireNonNull(source.chromaUPlane(), "source.chromaUPlane");
        DecodedPlane sourceV = Objects.requireNonNull(source.chromaVPlane(), "source.chromaVPlane");
        DecodedPlane outputU = Objects.requireNonNull(output.chromaUPlane(), "output.chromaUPlane");
        DecodedPlane outputV = Objects.requireNonNull(output.chromaVPlane(), "output.chromaVPlane");
        assertEquals(sourceU.sample(2, 0), outputU.sample(0, 0));
        assertEquals(sourceV.sample(0, 2), outputV.sample(2, 0));
        assertEquals(0, outputU.sample(0, 2));
        assertEquals(0, outputV.sample(3, 3));
    }

    /// Verifies that a previously assembled tile can be reused without the camera-frame source.
    @Test
    void copiesPreviouslyWrittenOutputTile() {
        DecodedPlanes source = sourcePlanes();
        LargeScaleTileOutputBuilder builder = new LargeScaleTileOutputBuilder(
                10,
                Av1ChromaFormat.YUV420,
                4,
                4,
                2,
                2
        );

        builder.copyTile(source, 1, 0, 0);
        builder.copyOutputTile(0, 3);
        DecodedPlanes output = builder.build();

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(output.lumaPlane().sample(x, y), output.lumaPlane().sample(x + 4, y + 4));
            }
        }
        DecodedPlane outputU = Objects.requireNonNull(output.chromaUPlane(), "output.chromaUPlane");
        DecodedPlane outputV = Objects.requireNonNull(output.chromaVPlane(), "output.chromaVPlane");
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(outputU.sample(x, y), outputU.sample(x + 2, y + 2));
                assertEquals(outputV.sample(x, y), outputV.sample(x + 2, y + 2));
            }
        }
    }

    /// Verifies validation of source and destination output tile indices.
    @Test
    void rejectsInvalidOutputTileCopyIndices() {
        LargeScaleTileOutputBuilder builder = new LargeScaleTileOutputBuilder(
                10,
                Av1ChromaFormat.YUV420,
                4,
                4,
                2,
                2
        );

        assertThrows(IllegalArgumentException.class, () -> builder.copyOutputTile(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.copyOutputTile(0, 4));
    }

    /// Verifies that ownership transfer permanently closes the mutable builder lifecycle.
    @Test
    void rejectsMutationAndRepeatedBuildAfterOwnershipTransfer() {
        DecodedPlanes source = sourcePlanes();
        LargeScaleTileOutputBuilder builder = new LargeScaleTileOutputBuilder(
                10,
                Av1ChromaFormat.YUV420,
                4,
                4,
                1,
                1
        );
        builder.copyTile(source, 0, 0, 0);
        builder.build();

        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalStateException.class, () -> builder.copyTile(source, 0, 0, 0));
        assertThrows(IllegalStateException.class, () -> builder.copyOutputTile(0, 0));
    }

    /// Creates deterministic 8x8 YUV420 camera-frame planes.
    ///
    /// @return the synthetic camera-frame planes
    private static DecodedPlanes sourcePlanes() {
        return new DecodedPlanes(
                10,
                Av1ChromaFormat.YUV420,
                8,
                8,
                8,
                8,
                sequentialPlane(8, 8, 0),
                sequentialPlane(4, 4, 100),
                sequentialPlane(4, 4, 200)
        );
    }

    /// Creates one tightly packed plane containing increasing sample values.
    ///
    /// @param width the plane width
    /// @param height the plane height
    /// @param base the first sample value
    /// @return the deterministic plane
    private static DecodedPlane sequentialPlane(int width, int height, int base) {
        short[] samples = new short[width * height];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (base + i);
        }
        return new DecodedPlane(width, height, width, samples);
    }
}
