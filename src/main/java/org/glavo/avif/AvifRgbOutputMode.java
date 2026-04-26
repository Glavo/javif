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
package org.glavo.avif;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects the packed ARGB storage used for decoded AVIF frames.
@NotNullByDefault
public enum AvifRgbOutputMode {
    /// Uses `IntBuffer` ARGB_8888 for 8-bit images and `LongBuffer` ARGB_16161616 for high-bit-depth images.
    AUTOMATIC,
    /// Stores decoded frames primarily as `IntBuffer` pixels in `0xAARRGGBB` format.
    ARGB_8888,
    /// Stores decoded frames primarily as `LongBuffer` pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
    ARGB_16161616;

    /// Resolves this requested mode for one decoded source bit depth.
    ///
    /// @param bitDepth the decoded source bit depth
    /// @return the concrete frame storage mode
    public AvifRgbOutputMode resolve(AvifBitDepth bitDepth) {
        if (this != AUTOMATIC) {
            return this;
        }
        return bitDepth.isEightBit() ? ARGB_8888 : ARGB_16161616;
    }
}
