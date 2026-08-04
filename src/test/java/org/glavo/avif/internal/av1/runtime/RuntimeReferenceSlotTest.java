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
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.decode.FrameType;
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
                RuntimeTestFixtures.createFrameHeader(FrameType.KEY, true, 0x01),
                8,
                96
        );
        ReferenceSurfaceSnapshot second = RuntimeTestFixtures.createReferenceSurfaceSnapshot(
                RuntimeTestFixtures.createFrameHeader(FrameType.INTRA, false, 0x08),
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
