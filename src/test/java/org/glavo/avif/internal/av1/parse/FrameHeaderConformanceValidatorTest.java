// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse;

import org.glavo.avif.internal.av1.model.FrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests strict frame-header conformance checks that depend on stored reference dimensions.
@NotNullByDefault
final class FrameHeaderConformanceValidatorTest {
    /// Verifies both inclusive reference-dimension ratio boundaries.
    @Test
    void acceptsReferenceDimensionRatioBoundaries() {
        FrameHeader.FrameSize current = frameSize(1_600, 64);
        FrameHeader.FrameSize reference = frameSize(100, 128);

        assertDoesNotThrow(() -> FrameHeaderConformanceValidator.validateReferenceDimensions(current, reference));
    }

    /// Verifies that a reference wider than twice the current coded frame is rejected.
    @Test
    void rejectsOversizedReferenceWidth() {
        FrameHeader.FrameSize current = frameSize(64, 64);
        FrameHeader.FrameSize reference = frameSize(129, 64);

        assertThrows(
                IOException.class,
                () -> FrameHeaderConformanceValidator.validateReferenceDimensions(current, reference)
        );
    }

    /// Verifies that the current height cannot exceed sixteen times its reference height.
    @Test
    void rejectsOversizedCurrentHeight() {
        FrameHeader.FrameSize current = frameSize(64, 1_601);
        FrameHeader.FrameSize reference = frameSize(64, 100);

        assertThrows(
                IOException.class,
                () -> FrameHeaderConformanceValidator.validateReferenceDimensions(current, reference)
        );
    }

    /// Creates square-free frame dimensions for one ratio-validation test.
    ///
    /// @param width the coded and upscaled width
    /// @param height the coded frame height
    /// @return the requested frame dimensions
    private static FrameHeader.FrameSize frameSize(int width, int height) {
        return new FrameHeader.FrameSize(width, width, height, width, height);
    }
}
