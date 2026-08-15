// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;

/// Signals that reconstructed values violate an AV1 bitstream conformance constraint.
@NotNullByDefault
public final class InvalidFrameReconstructionException extends IllegalStateException {
    /// Creates one invalid-frame-reconstruction exception.
    ///
    /// @param message the diagnostic message
    InvalidFrameReconstructionException(String message) {
        super(message);
    }
}
