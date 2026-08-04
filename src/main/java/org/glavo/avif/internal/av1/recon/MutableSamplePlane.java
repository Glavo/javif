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
package org.glavo.avif.internal.av1.recon;

import org.jetbrains.annotations.NotNullByDefault;

/// Provides the mutable sample operations required by intra prediction.
///
/// Coordinates use the containing plane's sample grid. Implementations may store the whole plane
/// or expose a writable overlay backed by another plane.
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
