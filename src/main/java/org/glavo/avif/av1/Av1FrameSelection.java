// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the AV1 frame categories decoded by [Av1Decoder].
@NotNullByDefault
public enum Av1FrameSelection {
    /// Decodes every frame that becomes available.
    ALL,
    /// Decodes frames retained as references for other frames.
    REFERENCE,
    /// Decodes intra frames, including key frames.
    INTRA,
    /// Decodes key frames only.
    KEY
}
