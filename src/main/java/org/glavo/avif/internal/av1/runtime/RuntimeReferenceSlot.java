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

import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Mutable runtime storage for one atomically refreshed AV1 reference slot.
///
/// A populated slot always retains its frame header, compact syntax state, and reconstructed
/// post-filter surface in one [ReferenceSurfaceSnapshot].
@NotNullByDefault
public final class RuntimeReferenceSlot {
    /// Creates an empty reference slot.
    public RuntimeReferenceSlot() {
    }

    /// The complete stored reference state, or `null` when the slot is empty.
    private @Nullable ReferenceSurfaceSnapshot snapshot;

    /// Clears the slot completely.
    public void clear() {
        snapshot = null;
    }

    /// Atomically replaces the complete state stored in this slot.
    ///
    /// @param snapshot the reconstructed reference state to store
    public void refresh(ReferenceSurfaceSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /// Returns the frame header stored in this slot.
    ///
    /// @return the stored frame header, or `null` when empty
    public @Nullable FrameHeader frameHeader() {
        ReferenceSurfaceSnapshot current = snapshot;
        return current != null ? current.frameHeader() : null;
    }

    /// Returns the compact frame-syntax state stored in this slot.
    ///
    /// @return the compact stored syntax state, or `null` when empty
    public @Nullable ReferenceFrameSyntaxState syntaxState() {
        ReferenceSurfaceSnapshot current = snapshot;
        return current != null ? current.frameSyntaxState() : null;
    }

    /// Returns the complete reference surface snapshot stored in this slot.
    ///
    /// @return the stored reference state, or `null` when empty
    public @Nullable ReferenceSurfaceSnapshot surfaceSnapshot() {
        return snapshot;
    }

    /// Returns whether this slot contains complete reference state.
    ///
    /// @return whether this slot is populated
    public boolean isPopulated() {
        return snapshot != null;
    }
}
