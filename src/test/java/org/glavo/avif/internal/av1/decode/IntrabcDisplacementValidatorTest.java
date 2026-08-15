// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode;

import org.glavo.avif.internal.av1.model.MotionVector;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for normative `intrabc` displacement-vector validation.
@NotNullByDefault
final class IntrabcDisplacementValidatorTest {
    /// Verifies legal vertical and delayed same-row references for 64x64 superblocks.
    @Test
    void acceptsAlreadyDecodedPipelineRegions() {
        assertTrue(isValid(new MotionVector(-64 * 8, 0), 512, 512, 16, 16, false, false, false, 64));
        assertTrue(isValid(new MotionVector(0, -320 * 8), 512, 512, 16, 16, false, false, false, 64));
    }

    /// Verifies integer-pixel precision and the four-SB64 pipeline delay.
    @Test
    void rejectsFractionalAndTooRecentReferences() {
        assertFalse(isValid(new MotionVector(-64 * 8 + 1, 0), 512, 512, 16, 16, false, false, false, 64));
        assertFalse(isValid(new MotionVector(0, -256 * 8), 512, 512, 16, 16, false, false, false, 64));
    }

    /// Verifies that the complete source block must remain inside its tile.
    @Test
    void rejectsSourcesOutsideTileBounds() {
        assertFalse(IntrabcDisplacementValidator.isValid(
                new MotionVector(0, -264 * 8),
                512 >> 2,
                512 >> 2,
                16,
                16,
                256 >> 2,
                0,
                1024 >> 2,
                1024 >> 2,
                64,
                false,
                false,
                false
        ));
        assertFalse(isValid(new MotionVector(600 * 8, 0), 512, 512, 16, 16, false, false, false, 64));
    }

    /// Verifies the extra tile-edge exclusion for sub-8x8 chroma reference blocks.
    @Test
    void enforcesSubEightChromaTileEdges() {
        MotionVector horizontal = new MotionVector(0, -320 * 8);
        assertTrue(isValid(horizontal, 320, 64, 4, 4, false, true, false, 64));
        assertFalse(isValid(horizontal, 320, 64, 4, 4, true, true, false, 64));

        MotionVector vertical = new MotionVector(-320 * 8, 0);
        assertTrue(isValid(vertical, 64, 320, 4, 4, false, false, true, 64));
        assertFalse(isValid(vertical, 64, 320, 4, 4, true, false, true, 64));
    }

    /// Verifies the 128x128-superblock wavefront gradient.
    @Test
    void acceptsDecodedRegionWith128Superblocks() {
        assertTrue(isValid(new MotionVector(-128 * 8, 0), 512, 512, 16, 16, false, false, false, 128));
        assertFalse(isValid(new MotionVector(-64 * 8, 128 * 8), 512, 512, 16, 16, false, false, false, 128));
    }

    /// Verifies that malformed validator geometry is rejected before displacement evaluation.
    @Test
    void rejectsInvalidValidatorInputs() {
        MotionVector displacementVector = new MotionVector(-64 * 8, 0);
        assertThrows(IllegalArgumentException.class, () -> IntrabcDisplacementValidator.isValid(
                displacementVector,
                128,
                128,
                0,
                16,
                0,
                0,
                256,
                256,
                64,
                false,
                false,
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> IntrabcDisplacementValidator.isValid(
                displacementVector,
                128,
                128,
                16,
                16,
                0,
                0,
                256,
                256,
                32,
                false,
                false,
                false
        ));
        assertThrows(IllegalArgumentException.class, () -> IntrabcDisplacementValidator.isValid(
                displacementVector,
                128,
                128,
                16,
                16,
                4,
                4,
                4,
                256,
                64,
                false,
                false,
                false
        ));
    }

    /// Validates one block inside a 1024x1024 tile beginning at the frame origin.
    ///
    /// @param displacementVector the displacement vector to validate
    /// @param blockX             the luma block X origin in pixels
    /// @param blockY             the luma block Y origin in pixels
    /// @param blockWidth         the luma block width in pixels
    /// @param blockHeight        the luma block height in pixels
    /// @param chromaReference    whether the block owns chroma samples
    /// @param subsamplingX       whether chroma is horizontally subsampled
    /// @param subsamplingY       whether chroma is vertically subsampled
    /// @param superblockSize     the active superblock size in pixels
    /// @return whether the displacement vector is valid
    private static boolean isValid(
            MotionVector displacementVector,
            int blockX,
            int blockY,
            int blockWidth,
            int blockHeight,
            boolean chromaReference,
            boolean subsamplingX,
            boolean subsamplingY,
            int superblockSize
    ) {
        return IntrabcDisplacementValidator.isValid(
                displacementVector,
                blockX >> 2,
                blockY >> 2,
                blockWidth,
                blockHeight,
                0,
                0,
                1024 >> 2,
                1024 >> 2,
                superblockSize,
                chromaReference,
                subsamplingX,
                subsamplingY
        );
    }
}
