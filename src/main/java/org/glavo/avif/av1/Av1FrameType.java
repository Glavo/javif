// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1;

import org.jetbrains.annotations.NotNullByDefault;

/// AV1 frame categories exposed by decoded frame metadata.
@NotNullByDefault
public enum Av1FrameType {
    /// A key intra frame.
    KEY,
    /// An inter frame.
    INTER,
    /// A non-key intra frame.
    INTRA,
    /// A switch frame.
    SWITCH
}
