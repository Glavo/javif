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
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.decode.ArgbIntFrame;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Internal entry points for converting `DecodedPlanes` into opaque ARGB output.
///
/// The current implementation supports only 8-bit plane snapshots, `I400`/`I420` chroma layouts,
/// and point-sampled chroma expansion for `I420`. The render rectangle is copied directly from the
/// top-left portion of the coded planes, so render upscaling is intentionally unsupported here.
@NotNullByDefault
public final class ArgbOutput {
    /// The default YUV-to-RGB transform used by convenience overloads.
    private static final YuvToRgbTransform DEFAULT_TRANSFORM = YuvToRgbTransform.BT601_FULL_RANGE;

    /// Prevents instantiation of this utility class.
    private ArgbOutput() {
    }

    /// Converts one decoded-plane snapshot into opaque ARGB pixels.
    ///
    /// This convenience overload uses `BT.601` full-range coefficients.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @return packed opaque non-premultiplied ARGB pixels in presentation order
    public static int[] toOpaqueArgbPixels(DecodedPlanes decodedPlanes) {
        return toOpaqueArgbPixels(decodedPlanes, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into opaque ARGB pixels.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return packed opaque non-premultiplied ARGB pixels in presentation order
    public static int[] toOpaqueArgbPixels(DecodedPlanes decodedPlanes, YuvToRgbTransform transform) {
        DecodedPlanes checkedDecodedPlanes = requireSupportedDecodedPlanes(decodedPlanes);
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");

        int renderWidth = checkedDecodedPlanes.renderWidth();
        int renderHeight = checkedDecodedPlanes.renderHeight();
        int pixelCount = checkedPixelCount(renderWidth, renderHeight);
        int[] pixels = new int[pixelCount];

        PixelFormat pixelFormat = checkedDecodedPlanes.pixelFormat();
        return switch (pixelFormat) {
            case I400 -> convertOpaqueI400(checkedDecodedPlanes.lumaPlane(), renderWidth, renderHeight, pixels, checkedTransform);
            case I420 -> convertOpaqueI420(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    renderWidth,
                    renderHeight,
                    pixels,
                    checkedTransform
            );
            case I422, I444 -> throw new IllegalArgumentException("Unsupported pixelFormat for ARGB int output: " + pixelFormat);
        };
    }

    /// Converts one decoded-plane snapshot into an `ArgbIntFrame`.
    ///
    /// This convenience overload uses `BT.601` full-range coefficients.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `DecodedPlanes`
    /// @return one opaque `ArgbIntFrame`
    public static ArgbIntFrame toOpaqueArgbIntFrame(DecodedPlanes decodedPlanes, OutputFrameMetadata metadata) {
        return toOpaqueArgbIntFrame(decodedPlanes, metadata, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into an `ArgbIntFrame`.
    ///
    /// This overload accepts the public frame metadata directly, which keeps later integration code
    /// simple when it already has those values separately from `DecodedPlanes`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @return one opaque `ArgbIntFrame`
    public static ArgbIntFrame toOpaqueArgbIntFrame(
            DecodedPlanes decodedPlanes,
            FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        return toOpaqueArgbIntFrame(decodedPlanes, frameType, visible, presentationIndex, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into an `ArgbIntFrame`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque `ArgbIntFrame`
    public static ArgbIntFrame toOpaqueArgbIntFrame(
            DecodedPlanes decodedPlanes,
            FrameType frameType,
            boolean visible,
            long presentationIndex,
            YuvToRgbTransform transform
    ) {
        return toOpaqueArgbIntFrame(
                decodedPlanes,
                new OutputFrameMetadata(frameType, visible, presentationIndex),
                transform
        );
    }

    /// Converts one decoded-plane snapshot into an `ArgbIntFrame`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `DecodedPlanes`
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque `ArgbIntFrame`
    public static ArgbIntFrame toOpaqueArgbIntFrame(
            DecodedPlanes decodedPlanes,
            OutputFrameMetadata metadata,
            YuvToRgbTransform transform
    ) {
        DecodedPlanes checkedDecodedPlanes = requireSupportedDecodedPlanes(decodedPlanes);
        OutputFrameMetadata checkedMetadata = Objects.requireNonNull(metadata, "metadata");
        int[] pixels = toOpaqueArgbPixels(checkedDecodedPlanes, transform);
        return new ArgbIntFrame(
                checkedDecodedPlanes.renderWidth(),
                checkedDecodedPlanes.renderHeight(),
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.pixelFormat(),
                checkedMetadata.frameType(),
                checkedMetadata.visible(),
                checkedMetadata.presentationIndex(),
                pixels
        );
    }

    /// Validates that one decoded-plane snapshot is supported by this output layer.
    ///
    /// @param decodedPlanes the decoded planes to validate
    /// @return the validated decoded planes
    private static DecodedPlanes requireSupportedDecodedPlanes(DecodedPlanes decodedPlanes) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (checkedDecodedPlanes.bitDepth() != 8) {
            throw new IllegalArgumentException(
                    "ArgbIntFrame output currently requires 8-bit decoded planes: " + checkedDecodedPlanes.bitDepth()
            );
        }
        if (checkedDecodedPlanes.renderWidth() > checkedDecodedPlanes.codedWidth()) {
            throw new IllegalArgumentException(
                    "renderWidth exceeds codedWidth and would require resampling: "
                            + checkedDecodedPlanes.renderWidth() + " > " + checkedDecodedPlanes.codedWidth()
            );
        }
        if (checkedDecodedPlanes.renderHeight() > checkedDecodedPlanes.codedHeight()) {
            throw new IllegalArgumentException(
                    "renderHeight exceeds codedHeight and would require resampling: "
                            + checkedDecodedPlanes.renderHeight() + " > " + checkedDecodedPlanes.codedHeight()
            );
        }
        return checkedDecodedPlanes;
    }

    /// Validates one render-area size and returns its pixel count.
    ///
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @return the render-area pixel count
    private static int checkedPixelCount(int renderWidth, int renderHeight) {
        try {
            return Math.multiplyExact(renderWidth, renderHeight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("render area is too large", exception);
        }
    }

    /// Returns one required chroma plane or throws an explanatory failure.
    ///
    /// @param plane the candidate chroma plane
    /// @param name the argument name used in the failure message
    /// @return the required chroma plane
    private static DecodedPlane requireChromaPlane(@Nullable DecodedPlane plane, String name) {
        if (plane == null) {
            throw new IllegalArgumentException(name + " is required for I420 ARGB output");
        }
        return plane;
    }

    /// Converts one monochrome plane snapshot into opaque grayscale ARGB pixels.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI400(
            DecodedPlane lumaPlane,
            int renderWidth,
            int renderHeight,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        int lumaStride = lumaPlane.stride();
        for (int y = 0; y < renderHeight; y++) {
            int lumaRow = y * lumaStride;
            int pixelRow = y * renderWidth;
            for (int x = 0; x < renderWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb(lumaSamples[lumaRow + x] & 0xFFFF);
            }
        }
        return pixels;
    }

    /// Converts one `I420` plane snapshot into opaque ARGB pixels with point-sampled chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI420(
            DecodedPlane lumaPlane,
            DecodedPlane chromaUPlane,
            DecodedPlane chromaVPlane,
            int renderWidth,
            int renderHeight,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < renderHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = (y >> 1) * chromaUStride;
            int chromaVRow = (y >> 1) * chromaVStride;
            int pixelRow = y * renderWidth;

            int x = 0;
            for (; x + 1 < renderWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples[chromaIndexU] & 0xFFFF;
                int vSample = chromaVSamples[chromaIndexV] & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb(lumaSamples[lumaRow + x] & 0xFFFF, uSample, vSample);
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x + 1] & 0xFFFF,
                        uSample,
                        vSample
                );
            }

            if (x < renderWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaIndexU] & 0xFFFF,
                        chromaVSamples[chromaIndexV] & 0xFFFF
                );
            }
        }
        return pixels;
    }
}
