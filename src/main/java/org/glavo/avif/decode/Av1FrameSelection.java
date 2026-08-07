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

/// Selects the AV1 frame categories decoded by [Av1ImageReader].
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
