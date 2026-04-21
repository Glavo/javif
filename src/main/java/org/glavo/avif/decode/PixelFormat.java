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
package org.glavo.avif.decode;

import org.jetbrains.annotations.NotNullByDefault;

/// AV1 chroma sampling layouts exposed by decoded frame metadata.
@NotNullByDefault
public enum PixelFormat {
    /// Monochrome output with no chroma planes.
    I400,
    /// 4:2:0 subsampled chroma output.
    I420,
    /// 4:2:2 subsampled chroma output.
    I422,
    /// 4:4:4 full-resolution chroma output.
    I444
}
