// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;

/// Provides the mutable sample operations required by intra prediction.
///
/// Coordinates use the containing plane's sample grid. Implementations may store the whole plane,
/// retain only the reconstruction region supplied to callers, or expose a writable overlay backed
/// by another plane.
@NotNullByDefault
interface MutableSamplePlane {
    /// Returns the plane width in samples.
    ///
    /// @return the plane width in samples
    int width();

    /// Returns the plane height in samples.
    ///
    /// @return the plane height in samples
    int height();

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    int bitDepth();

    /// Returns the sample at one in-range plane coordinate.
    ///
    /// @param x the zero-based horizontal sample coordinate
    /// @param y the zero-based vertical sample coordinate
    /// @return the sample value
    int sample(int x, int y);

    /// Stores one sample at an in-range writable coordinate.
    ///
    /// @param x     the zero-based horizontal sample coordinate
    /// @param y     the zero-based vertical sample coordinate
    /// @param value the sample value, clipped to the legal bit-depth range
    void setSample(int x, int y, int value);
}
