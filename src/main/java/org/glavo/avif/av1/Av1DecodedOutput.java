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
package org.glavo.avif.av1;

import org.glavo.avif.DecodedPlanes;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.glavo.avif.internal.av1.runtime.OutputFrameFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable decoded AV1 presentation output with YUV planes and frame metadata.
///
/// Plane access does not perform color conversion. [#toFrame()] converts the same output to packed
/// ARGB on first use and caches the resulting [Av1DecodedFrame].
@NotNullByDefault
public final class Av1DecodedOutput {
    /// The internal postprocessed presentation surface used for color conversion.
    private final DecodedSurface surface;
    /// The public visible presentation planes.
    private final DecodedPlanes planes;
    /// The color configuration used to interpret the planes.
    private final Av1ColorConfig colorConfig;
    /// The frame category of the presented surface.
    private final Av1FrameType frameType;
    /// Whether the output is visible.
    private final boolean visible;
    /// The zero-based presentation index.
    private final long presentationIndex;
    /// The temporal-layer identifier of the presentation request.
    private final int temporalId;
    /// The spatial-layer identifier of the presentation request.
    private final int spatialId;
    /// The cached packed-pixel representation, or `null` before conversion.
    private volatile @Nullable Av1DecodedFrame frame;

    /// Creates a decoded output from validated decoder state.
    ///
    /// @param planes the postprocessed presentation planes
    /// @param colorConfig the color configuration used to interpret the planes
    /// @param frameType the frame category of the presented surface
    /// @param visible whether the output is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param temporalId the temporal-layer identifier in `[0, 7]`
    /// @param spatialId the spatial-layer identifier in `[0, 3]`
    Av1DecodedOutput(
            DecodedSurface planes,
            Av1ColorConfig colorConfig,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            int temporalId,
            int spatialId
    ) {
        if (presentationIndex < 0) {
            throw new IllegalArgumentException("presentationIndex < 0: " + presentationIndex);
        }
        if (temporalId < 0 || temporalId > 7) {
            throw new IllegalArgumentException("temporalId out of range: " + temporalId);
        }
        if (spatialId < 0 || spatialId > 3) {
            throw new IllegalArgumentException("spatialId out of range: " + spatialId);
        }
        this.surface = Objects.requireNonNull(planes, "planes");
        this.planes = planes.toDecodedPlanes();
        this.colorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
    }

    /// Returns the postprocessed YUV presentation planes.
    ///
    /// @return the immutable presentation planes
    public DecodedPlanes planes() {
        return planes;
    }

    /// Returns the color configuration used to interpret the planes.
    ///
    /// @return the AV1 color configuration
    public Av1ColorConfig colorConfig() {
        return colorConfig;
    }

    /// Returns the frame category of the presented surface.
    ///
    /// For `show_existing_frame`, this is the category of the referenced surface.
    ///
    /// @return the AV1 frame category
    public Av1FrameType frameType() {
        return frameType;
    }

    /// Returns whether the output is visible.
    ///
    /// @return whether the output is visible
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index.
    ///
    /// @return the presentation index
    public long presentationIndex() {
        return presentationIndex;
    }

    /// Returns the temporal-layer identifier of the presentation request.
    ///
    /// @return the temporal-layer identifier in `[0, 7]`
    public int temporalId() {
        return temporalId;
    }

    /// Returns the spatial-layer identifier of the presentation request.
    ///
    /// @return the spatial-layer identifier in `[0, 3]`
    public int spatialId() {
        return spatialId;
    }

    /// Converts this output to packed non-premultiplied ARGB pixels.
    ///
    /// The first call performs color conversion. Later calls return the same immutable frame
    /// instance.
    ///
    /// @return the packed-pixel frame
    /// @throws UnsupportedOperationException if the color configuration cannot be converted
    public Av1DecodedFrame toFrame() {
        Av1DecodedFrame result = frame;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = frame;
            if (result == null) {
                result = OutputFrameFactory.createFrame(
                        surface,
                        colorConfig,
                        frameType,
                        visible,
                        presentationIndex,
                        temporalId,
                        spatialId
                );
                frame = result;
            }
        }
        return result;
    }
}
