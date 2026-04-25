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
package org.glavo.avif.internal.av1.runtime;

import org.glavo.avif.decode.DecodedFrame;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.glavo.avif.internal.av1.output.ArgbOutput;
import org.glavo.avif.internal.av1.output.YuvToRgbTransform;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Creates public `DecodedFrame` instances from reconstructed decoder state.
@NotNullByDefault
public final class OutputFrameFactory {
    /// Prevents instantiation.
    private OutputFrameFactory() {
    }

    /// Creates one public decoded frame from the supplied reconstructed planes and frame header.
    ///
    /// @param decodedPlanes the reconstructed planes to present
    /// @param frameHeader the frame header that owns the reconstructed planes
    /// @param visible whether the returned frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the output frame
    /// @return one public decoded frame backed by the appropriate ARGB storage type
    public static DecodedFrame createFrame(
            DecodedPlanes decodedPlanes,
            FrameHeader frameHeader,
            boolean visible,
            long presentationIndex
    ) {
        return createFrame(
                decodedPlanes,
                frameHeader,
                visible,
                presentationIndex,
                YuvToRgbTransform.BT601_FULL_RANGE
        );
    }

    /// Creates one public decoded frame using the color transform selected from the sequence header.
    ///
    /// @param decodedPlanes the reconstructed planes to present
    /// @param colorConfig the AV1 sequence color configuration for the decoded planes
    /// @param frameHeader the frame header that owns the reconstructed planes
    /// @param visible whether the returned frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the output frame
    /// @return one public decoded frame backed by the appropriate ARGB storage type
    public static DecodedFrame createFrame(
            DecodedPlanes decodedPlanes,
            SequenceHeader.ColorConfig colorConfig,
            FrameHeader frameHeader,
            boolean visible,
            long presentationIndex
    ) {
        return createFrame(
                decodedPlanes,
                frameHeader,
                visible,
                presentationIndex,
                YuvToRgbTransform.fromColorConfig(colorConfig)
        );
    }

    /// Creates one public decoded frame from the supplied reconstructed planes and transform.
    ///
    /// @param decodedPlanes the reconstructed planes to present
    /// @param frameHeader the frame header that owns the reconstructed planes
    /// @param visible whether the returned frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the output frame
    /// @param transform the selected YUV-to-RGB output transform
    /// @return one public decoded frame backed by the appropriate ARGB storage type
    private static DecodedFrame createFrame(
            DecodedPlanes decodedPlanes,
            FrameHeader frameHeader,
            boolean visible,
            long presentationIndex,
            YuvToRgbTransform transform
    ) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");
        return switch (checkedDecodedPlanes.bitDepth()) {
            case 8 -> ArgbOutput.toOpaqueArgb8Frame(
                    checkedDecodedPlanes,
                    checkedFrameHeader.frameType(),
                    visible,
                    presentationIndex,
                    checkedTransform
            );
            case 10, 12 -> ArgbOutput.toOpaqueArgbHighBitDepthFrame(
                    checkedDecodedPlanes,
                    checkedFrameHeader.frameType(),
                    visible,
                    presentationIndex,
                    checkedTransform
            );
            default -> throw new IllegalArgumentException("Unsupported decoded bit depth: " + checkedDecodedPlanes.bitDepth());
        };
    }

    /// Creates one public `show_existing_frame` output from a stored reference surface.
    ///
    /// @param surfaceSnapshot the stored reference surface to present
    /// @param presentationIndex the zero-based presentation index of the output frame
    /// @return one public decoded frame backed by the appropriate ARGB storage type
    public static DecodedFrame createExistingFrame(ReferenceSurfaceSnapshot surfaceSnapshot, long presentationIndex) {
        ReferenceSurfaceSnapshot checkedSnapshot = Objects.requireNonNull(surfaceSnapshot, "surfaceSnapshot");
        return createFrame(
                checkedSnapshot.decodedPlanes(),
                checkedSnapshot.frameSyntaxDecodeResult().assembly().sequenceHeader().colorConfig(),
                checkedSnapshot.frameHeader(),
                true,
                presentationIndex
        );
    }
}
