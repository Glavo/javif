// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode;

import org.jetbrains.annotations.NotNullByDefault;

/// Signals that entropy-decoded frame syntax violates an AV1 structural constraint.
///
/// The public reader translates this internal failure into its contextual checked decode error.
@NotNullByDefault
public final class InvalidFrameSyntaxException extends IllegalStateException {
    /// Creates one invalid-frame-syntax exception.
    ///
    /// @param message the diagnostic message
    /// @param cause   the internal validation failure
    InvalidFrameSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
