// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests atomic runtime reference-slot state.
@NotNullByDefault
final class RuntimeReferenceSlotTest {
    /// Verifies that one newly created runtime reference slot starts empty.
    @Test
    void newSlotStartsEmpty() {
        RuntimeReferenceSlot slot = new RuntimeReferenceSlot();

        assertNull(slot.frameHeader());
        assertNull(slot.syntaxState());
        assertNull(slot.surfaceSnapshot());
        assertFalse(slot.isPopulated());
    }

    /// Verifies that refresh replaces every component atomically and clear removes the snapshot.
    @Test
    void refreshReplacesCompleteStateAndClearEmptiesSlot() {
        RuntimeReferenceSlot slot = new RuntimeReferenceSlot();
        ReferenceSurfaceSnapshot first = RuntimeTestFixtures.createReferenceSurfaceSnapshot(
                RuntimeTestFixtures.createFrameHeader(Av1FrameType.KEY, true, 0x01),
                8,
                96
        );
        ReferenceSurfaceSnapshot second = RuntimeTestFixtures.createReferenceSurfaceSnapshot(
                RuntimeTestFixtures.createFrameHeader(Av1FrameType.INTRA, false, 0x08),
                8,
                144
        );

        slot.refresh(first);
        slot.refresh(second);

        assertSame(second.frameHeader(), slot.frameHeader());
        assertSame(second.frameSyntaxState(), slot.syntaxState());
        assertSame(second, slot.surfaceSnapshot());
        assertTrue(slot.isPopulated());

        slot.clear();
        assertNull(slot.frameHeader());
        assertNull(slot.syntaxState());
        assertNull(slot.surfaceSnapshot());
        assertFalse(slot.isPopulated());
    }
}
