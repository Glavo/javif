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

import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Mutable runtime reference-slot state used by `Av1ImageReader`.
///
/// A slot may carry structural syntax state, a reconstructed surface, or both. Structural state is
/// refreshed independently from reconstructed surfaces so unsupported pixel paths can still preserve
/// parser/runtime inheritance state.
@NotNullByDefault
public final class RuntimeReferenceSlot {
    /// The frame header currently stored in this reference slot, or `null` when empty.
    private @Nullable FrameHeader frameHeader;

    /// The structural frame-syntax result currently stored in this reference slot, or `null`.
    private @Nullable FrameSyntaxDecodeResult syntaxResult;

    /// The reconstructed reference surface currently stored in this reference slot, or `null`.
    private @Nullable ReferenceSurfaceSnapshot surfaceSnapshot;

    /// Clears the slot completely.
    public void clear() {
        frameHeader = null;
        syntaxResult = null;
        surfaceSnapshot = null;
    }

    /// Stores refreshed structural syntax state into this slot.
    ///
    /// @param frameHeader the frame header that owns the stored syntax state
    /// @param syntaxResult the structural syntax state to store
    public void refreshSyntax(FrameHeader frameHeader, FrameSyntaxDecodeResult syntaxResult) {
        this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        this.syntaxResult = Objects.requireNonNull(syntaxResult, "syntaxResult");
    }

    /// Stores one reconstructed reference surface into this slot.
    ///
    /// The slot may already contain structural state from an earlier unsupported pixel path. The
    /// stored surface always supersedes any older surface snapshot.
    ///
    /// @param surfaceSnapshot the reconstructed reference surface to store
    public void refreshSurface(ReferenceSurfaceSnapshot surfaceSnapshot) {
        ReferenceSurfaceSnapshot checkedSnapshot = Objects.requireNonNull(surfaceSnapshot, "surfaceSnapshot");
        frameHeader = checkedSnapshot.frameHeader();
        syntaxResult = checkedSnapshot.frameSyntaxDecodeResult();
        this.surfaceSnapshot = checkedSnapshot;
    }

    /// Returns the frame header currently stored in this slot, or `null` when empty.
    ///
    /// @return the frame header currently stored in this slot, or `null`
    public @Nullable FrameHeader frameHeader() {
        return frameHeader;
    }

    /// Returns the structural frame-syntax result currently stored in this slot, or `null`.
    ///
    /// @return the structural frame-syntax result currently stored in this slot, or `null`
    public @Nullable FrameSyntaxDecodeResult syntaxResult() {
        return syntaxResult;
    }

    /// Returns the reconstructed reference surface currently stored in this slot, or `null`.
    ///
    /// @return the reconstructed reference surface currently stored in this slot, or `null`
    public @Nullable ReferenceSurfaceSnapshot surfaceSnapshot() {
        return surfaceSnapshot;
    }

    /// Returns whether this slot currently carries structural syntax state.
    ///
    /// @return whether this slot currently carries structural syntax state
    public boolean hasSyntaxState() {
        return frameHeader != null && syntaxResult != null;
    }
}
