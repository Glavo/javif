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
