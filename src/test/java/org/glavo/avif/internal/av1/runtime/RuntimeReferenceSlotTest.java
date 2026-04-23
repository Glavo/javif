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
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for mutable runtime reference-slot state.
@NotNullByDefault
final class RuntimeReferenceSlotTest {
    /// Verifies that one newly created runtime reference slot starts empty.
    @Test
    void newSlotStartsEmpty() {
        RuntimeReferenceSlot slot = new RuntimeReferenceSlot();

        assertNull(slot.frameHeader());
        assertNull(slot.syntaxResult());
        assertNull(slot.surfaceSnapshot());
        assertFalse(slot.hasSyntaxState());
    }

    /// Verifies that syntax refresh stores header plus structural decode state without requiring a surface.
    @Test
    void refreshSyntaxStoresFrameHeaderAndSyntaxResult() {
        RuntimeReferenceSlot slot = new RuntimeReferenceSlot();
        FrameHeader frameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, true, 0x01);
        FrameSyntaxDecodeResult syntaxResult = RuntimeTestFixtures.createFrameSyntaxDecodeResult(frameHeader);

        slot.refreshSyntax(frameHeader, syntaxResult);

        assertSame(frameHeader, slot.frameHeader());
        assertSame(syntaxResult, slot.syntaxResult());
        assertNull(slot.surfaceSnapshot());
        assertTrue(slot.hasSyntaxState());
    }

    /// Verifies that surface refresh supersedes older syntax state and that `clear()` empties the slot again.
    @Test
    void refreshSurfaceSupersedesOlderSyntaxStateAndClearRemovesAllState() {
        RuntimeReferenceSlot slot = new RuntimeReferenceSlot();
        FrameHeader olderFrameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.KEY, true, 0x01);
        FrameSyntaxDecodeResult olderSyntaxResult = RuntimeTestFixtures.createFrameSyntaxDecodeResult(olderFrameHeader);
        FrameHeader referencedFrameHeader = RuntimeTestFixtures.createFrameHeader(FrameType.INTRA, false, 0x08);
        ReferenceSurfaceSnapshot surfaceSnapshot = RuntimeTestFixtures.createReferenceSurfaceSnapshot(referencedFrameHeader, 8, 144);

        slot.refreshSyntax(olderFrameHeader, olderSyntaxResult);
        slot.refreshSurface(surfaceSnapshot);

        assertSame(referencedFrameHeader, slot.frameHeader());
        assertSame(surfaceSnapshot.frameSyntaxDecodeResult(), slot.syntaxResult());
        assertSame(surfaceSnapshot, slot.surfaceSnapshot());
        assertTrue(slot.hasSyntaxState());

        slot.clear();

        assertNull(slot.frameHeader());
        assertNull(slot.syntaxResult());
        assertNull(slot.surfaceSnapshot());
        assertFalse(slot.hasSyntaxState());
    }
}
