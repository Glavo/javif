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

/// Packed non-premultiplied ARGB formats exposed by decoded AVIF frames.
@NotNullByDefault
public enum AvifPixelFormat {
    /// Stores each pixel in an `IntBuffer` element as `0xAARRGGBB`.
    ARGB_8888,
    /// Stores each pixel in a `LongBuffer` element as `0xAAAA_RRRR_GGGG_BBBB`.
    ARGB_16161616;

    /// Returns the default output format for a decoded source bit depth.
    ///
    /// @param bitDepth the decoded source bit depth
    /// @return `ARGB_8888` for 8-bit sources, otherwise `ARGB_16161616`
    public static AvifPixelFormat defaultFor(AvifBitDepth bitDepth) {
        return bitDepth.isEightBit() ? ARGB_8888 : ARGB_16161616;
    }
}
