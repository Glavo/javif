// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.av1.Av1FrameType;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Frame metadata needed when internal output conversion materializes one decoded frame object.
///
/// Output dimensions, bit depth, and chroma layout come from `Av1DecodedPlanes`; this metadata only
/// carries the public frame attributes that do not belong to the plane snapshot itself.
@NotNullByDefault
public final class OutputFrameMetadata {
    /// The AV1 frame category.
    private final Av1FrameType frameType;

    /// Whether the frame should be exposed as visible output.
    private final boolean visible;

    /// The zero-based presentation index of the frame.
    private final long presentationIndex;

    /// The AV1 temporal-layer identifier.
    private final int temporalId;

    /// The AV1 spatial-layer identifier.
    private final int spatialId;

    /// Creates one frame-metadata descriptor for internal output conversion.
    ///
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    public OutputFrameMetadata(Av1FrameType frameType, boolean visible, long presentationIndex) {
        this(frameType, visible, presentationIndex, 0, 0);
    }

    /// Creates one frame-metadata descriptor with AV1 layer identifiers.
    ///
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @param temporalId the AV1 temporal-layer identifier in `[0, 7]`
    /// @param spatialId the AV1 spatial-layer identifier in `[0, 3]`
    public OutputFrameMetadata(
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
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
    }

    /// Returns the AV1 frame category.
    ///
    /// @return the AV1 frame category
    public Av1FrameType frameType() {
        return frameType;
    }

    /// Returns whether the frame should be exposed as visible output.
    ///
    /// @return whether the frame should be exposed as visible output
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index of the frame.
    ///
    /// @return the zero-based presentation index of the frame
    public long presentationIndex() {
        return presentationIndex;
    }

    /// Returns the AV1 temporal-layer identifier.
    ///
    /// @return the temporal-layer identifier in `[0, 7]`
    public int temporalId() {
        return temporalId;
    }

    /// Returns the AV1 spatial-layer identifier.
    ///
    /// @return the spatial-layer identifier in `[0, 3]`
    public int spatialId() {
        return spatialId;
    }
}
