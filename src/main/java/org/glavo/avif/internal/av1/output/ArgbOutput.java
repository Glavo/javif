// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output;

import org.glavo.avif.AvifBitDepth;
import org.glavo.avif.av1.Av1DecodedFrame;
import org.glavo.avif.av1.Av1FrameType;
import org.glavo.avif.Av1ChromaFormat;
import org.glavo.avif.internal.av1.image.PaddedPlane;
import org.glavo.avif.internal.av1.image.DecodedSurface;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Internal entry points for converting complete `Av1DecodedPlanes` snapshots into opaque ARGB output.
///
/// Conversion covers every stored luma sample and uses point-sampled chroma expansion for `YUV420`,
/// `YUV422`, and `YUV444`. AV1 render dimensions are display hints and do not crop or resample the
/// decoded planes.
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
    public static int[] toOpaqueArgbPixels(DecodedSurface decodedPlanes) {
        return toOpaqueArgbPixels(decodedPlanes, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into opaque ARGB pixels.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return packed opaque non-premultiplied ARGB pixels in presentation order
    public static int[] toOpaqueArgbPixels(DecodedSurface decodedPlanes, YuvToRgbTransform transform) {
        DecodedSurface checkedDecodedPlanes = requireIntOutputDecodedPlanes(decodedPlanes);
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");

        int outputWidth = checkedDecodedPlanes.codedWidth();
        int outputHeight = checkedDecodedPlanes.codedHeight();
        int pixelCount = checkedPixelCount(outputWidth, outputHeight);
        int[] pixels = new int[pixelCount];

        Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
        int bitDepth = checkedDecodedPlanes.bitDepth();
        return switch (chromaFormat) {
            case MONOCHROME -> convertOpaqueI400(
                    checkedDecodedPlanes.lumaPlane(),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV420 -> convertOpaqueI420(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV422 -> convertOpaqueI422(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV444 -> convertOpaqueI444(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
        };
    }

    /// Converts one decoded-plane snapshot into a `Av1DecodedFrame` backed by ARGB_8888 storage.
    ///
    /// This convenience overload uses `BT.601` full-range coefficients.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `Av1DecodedPlanes`
    /// @return one opaque decoded frame backed by ARGB_8888 storage
    public static Av1DecodedFrame toOpaqueArgb8Frame(DecodedSurface decodedPlanes, OutputFrameMetadata metadata) {
        return toOpaqueArgb8Frame(decodedPlanes, metadata, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into a `Av1DecodedFrame` backed by ARGB_8888 storage.
    ///
    /// This overload accepts the public frame metadata directly, which keeps later integration code
    /// simple when it already has those values separately from `Av1DecodedPlanes`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @return one opaque decoded frame backed by ARGB_8888 storage
    public static Av1DecodedFrame toOpaqueArgb8Frame(
            DecodedSurface decodedPlanes,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        return toOpaqueArgb8Frame(decodedPlanes, frameType, visible, presentationIndex, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into a `Av1DecodedFrame` backed by ARGB_8888 storage.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque decoded frame backed by ARGB_8888 storage
    public static Av1DecodedFrame toOpaqueArgb8Frame(
            DecodedSurface decodedPlanes,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            YuvToRgbTransform transform
    ) {
        return toOpaqueArgb8Frame(
                decodedPlanes,
                new OutputFrameMetadata(frameType, visible, presentationIndex),
                transform
        );
    }

    /// Converts one decoded-plane snapshot into a `Av1DecodedFrame` backed by ARGB_8888 storage.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `Av1DecodedPlanes`
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque decoded frame backed by ARGB_8888 storage
    public static Av1DecodedFrame toOpaqueArgb8Frame(
            DecodedSurface decodedPlanes,
            OutputFrameMetadata metadata,
            YuvToRgbTransform transform
    ) {
        DecodedSurface checkedDecodedPlanes = requireIntOutputDecodedPlanes(decodedPlanes);
        OutputFrameMetadata checkedMetadata = Objects.requireNonNull(metadata, "metadata");
        int[] pixels = toOpaqueArgbPixels(checkedDecodedPlanes, transform);
        return new Av1DecodedFrame(
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                AvifBitDepth.fromBits(checkedDecodedPlanes.bitDepth()),
                checkedDecodedPlanes.chromaFormat(),
                checkedMetadata.frameType(),
                checkedMetadata.visible(),
                checkedMetadata.presentationIndex(),
                checkedMetadata.temporalId(),
                checkedMetadata.spatialId(),
                IntBuffer.wrap(pixels).asReadOnlyBuffer()
        );
    }

    /// Converts one decoded-plane snapshot into opaque 16-bit-per-channel ARGB pixels.
    ///
    /// This convenience overload uses `BT.601` full-range coefficients.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @return packed opaque non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public static long[] toOpaqueArgbLongPixels(DecodedSurface decodedPlanes) {
        return toOpaqueArgbLongPixels(decodedPlanes, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into opaque 16-bit-per-channel ARGB pixels.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return packed opaque non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
    public static long[] toOpaqueArgbLongPixels(DecodedSurface decodedPlanes, YuvToRgbTransform transform) {
        DecodedSurface checkedDecodedPlanes = requireLongOutputDecodedPlanes(decodedPlanes);
        YuvToRgbTransform checkedTransform = Objects.requireNonNull(transform, "transform");

        int outputWidth = checkedDecodedPlanes.codedWidth();
        int outputHeight = checkedDecodedPlanes.codedHeight();
        int pixelCount = checkedPixelCount(outputWidth, outputHeight);
        long[] pixels = new long[pixelCount];

        Av1ChromaFormat chromaFormat = checkedDecodedPlanes.chromaFormat();
        int bitDepth = checkedDecodedPlanes.bitDepth();
        return switch (chromaFormat) {
            case MONOCHROME -> convertOpaqueLongI400(
                    checkedDecodedPlanes.lumaPlane(),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV420 -> convertOpaqueLongI420(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV422 -> convertOpaqueLongI422(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
            case YUV444 -> convertOpaqueLongI444(
                    checkedDecodedPlanes.lumaPlane(),
                    requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                    requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                    outputWidth,
                    outputHeight,
                    bitDepth,
                    pixels,
                    checkedTransform
            );
        };
    }

    /// Converts one decoded-plane snapshot into a high-bit-depth `Av1DecodedFrame`.
    ///
    /// This convenience overload uses `BT.601` full-range coefficients.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `Av1DecodedPlanes`
    /// @return one opaque high-bit-depth decoded frame
    public static Av1DecodedFrame toOpaqueArgbHighBitDepthFrame(DecodedSurface decodedPlanes, OutputFrameMetadata metadata) {
        return toOpaqueArgbHighBitDepthFrame(decodedPlanes, metadata, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into a high-bit-depth `Av1DecodedFrame`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @return one opaque high-bit-depth decoded frame
    public static Av1DecodedFrame toOpaqueArgbHighBitDepthFrame(
            DecodedSurface decodedPlanes,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex
    ) {
        return toOpaqueArgbHighBitDepthFrame(decodedPlanes, frameType, visible, presentationIndex, DEFAULT_TRANSFORM);
    }

    /// Converts one decoded-plane snapshot into a high-bit-depth `Av1DecodedFrame`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param frameType the AV1 frame category
    /// @param visible whether the frame should be exposed as visible output
    /// @param presentationIndex the zero-based presentation index of the frame
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque high-bit-depth decoded frame
    public static Av1DecodedFrame toOpaqueArgbHighBitDepthFrame(
            DecodedSurface decodedPlanes,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            YuvToRgbTransform transform
    ) {
        return toOpaqueArgbHighBitDepthFrame(
                decodedPlanes,
                new OutputFrameMetadata(frameType, visible, presentationIndex),
                transform
        );
    }

    /// Converts one decoded-plane snapshot into a high-bit-depth `Av1DecodedFrame`.
    ///
    /// @param decodedPlanes the decoded planes to convert
    /// @param metadata the decoded-frame metadata that is not stored in `Av1DecodedPlanes`
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return one opaque high-bit-depth decoded frame
    public static Av1DecodedFrame toOpaqueArgbHighBitDepthFrame(
            DecodedSurface decodedPlanes,
            OutputFrameMetadata metadata,
            YuvToRgbTransform transform
    ) {
        DecodedSurface checkedDecodedPlanes = requireLongOutputDecodedPlanes(decodedPlanes);
        OutputFrameMetadata checkedMetadata = Objects.requireNonNull(metadata, "metadata");
        long[] pixels = toOpaqueArgbLongPixels(checkedDecodedPlanes, transform);
        return new Av1DecodedFrame(
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                AvifBitDepth.fromBits(checkedDecodedPlanes.bitDepth()),
                checkedDecodedPlanes.chromaFormat(),
                checkedMetadata.frameType(),
                checkedMetadata.visible(),
                checkedMetadata.presentationIndex(),
                checkedMetadata.temporalId(),
                checkedMetadata.spatialId(),
                LongBuffer.wrap(pixels).asReadOnlyBuffer()
        );
    }

    /// Validates that one decoded-plane snapshot is supported by 8-bit output.
    ///
    /// @param decodedPlanes the decoded planes to validate
    /// @return the validated decoded planes
    private static DecodedSurface requireIntOutputDecodedPlanes(DecodedSurface decodedPlanes) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (checkedDecodedPlanes.bitDepth() != 8
                && checkedDecodedPlanes.bitDepth() != 10
                && checkedDecodedPlanes.bitDepth() != 12
                && checkedDecodedPlanes.bitDepth() != 16) {
            throw new IllegalArgumentException(
                    "8-bit ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                            + checkedDecodedPlanes.bitDepth()
            );
        }
        return checkedDecodedPlanes;
    }

    /// Validates that one decoded-plane snapshot is supported by high-bit-depth output.
    ///
    /// @param decodedPlanes the decoded planes to validate
    /// @return the validated decoded planes
    private static DecodedSurface requireLongOutputDecodedPlanes(DecodedSurface decodedPlanes) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        if (checkedDecodedPlanes.bitDepth() != 8
                && checkedDecodedPlanes.bitDepth() != 10
                && checkedDecodedPlanes.bitDepth() != 12
                && checkedDecodedPlanes.bitDepth() != 16) {
            throw new IllegalArgumentException(
                    "High-bit-depth ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                            + checkedDecodedPlanes.bitDepth()
            );
        }
        return checkedDecodedPlanes;
    }

    /// Validates one output size and returns its pixel count.
    ///
    /// @param outputWidth the output width
    /// @param outputHeight the output height
    /// @return the output pixel count
    private static int checkedPixelCount(int outputWidth, int outputHeight) {
        try {
            return Math.multiplyExact(outputWidth, outputHeight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("output area is too large", exception);
        }
    }

    /// Returns one required chroma plane or throws an explanatory failure.
    ///
    /// @param plane the candidate chroma plane
    /// @param name the argument name used in the failure message
    /// @return the required chroma plane
    private static PaddedPlane requireChromaPlane(@Nullable PaddedPlane plane, String name) {
        if (plane == null) {
            throw new IllegalArgumentException(name + " is required for chroma ARGB output");
        }
        return plane;
    }

    /// Converts one monochrome plane snapshot into opaque grayscale ARGB pixels.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI400(
            PaddedPlane lumaPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        int lumaStride = lumaPlane.stride();
        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb(lumaSamples[lumaRow + x] & 0xFFFF, bitDepth);
            }
        }
        return pixels;
    }

    /// Converts one monochrome plane snapshot into opaque 16-bit-per-channel grayscale ARGB pixels.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI400(
            PaddedPlane lumaPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        int lumaStride = lumaPlane.stride();
        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb64(lumaSamples[lumaRow + x] & 0xFFFF, bitDepth);
            }
        }
        return pixels;
    }

    /// Converts one `YUV420` plane snapshot into opaque ARGB pixels with point-sampled chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI420(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = (y >> 1) * chromaUStride;
            int chromaVRow = (y >> 1) * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples[chromaIndexU] & 0xFFFF;
                int vSample = chromaVSamples[chromaIndexV] & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x + 1] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaIndexU] & 0xFFFF,
                        chromaVSamples[chromaIndexV] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV420` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
    /// point-sampled chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI420(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = (y >> 1) * chromaUStride;
            int chromaVRow = (y >> 1) * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples[chromaIndexU] & 0xFFFF;
                int vSample = chromaVSamples[chromaIndexV] & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb64(lumaSamples[lumaRow + x] & 0xFFFF, uSample, vSample, bitDepth);
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                        lumaSamples[lumaRow + x + 1] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaIndexU] & 0xFFFF,
                        chromaVSamples[chromaIndexV] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV422` plane snapshot into opaque ARGB pixels with horizontally shared chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI422(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples[chromaIndexU] & 0xFFFF;
                int vSample = chromaVSamples[chromaIndexV] & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x + 1] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaIndexU] & 0xFFFF,
                        chromaVSamples[chromaIndexV] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV422` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
    /// horizontally shared chroma.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI422(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;

            int x = 0;
            for (; x + 1 < outputWidth; x += 2) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                int uSample = chromaUSamples[chromaIndexU] & 0xFFFF;
                int vSample = chromaVSamples[chromaIndexV] & 0xFFFF;

                pixels[pixelRow + x] = transform.toOpaqueArgb64(lumaSamples[lumaRow + x] & 0xFFFF, uSample, vSample, bitDepth);
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                        lumaSamples[lumaRow + x + 1] & 0xFFFF,
                        uSample,
                        vSample,
                        bitDepth
                );
            }

            if (x < outputWidth) {
                int chromaIndexU = chromaURow + (x >> 1);
                int chromaIndexV = chromaVRow + (x >> 1);
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaIndexU] & 0xFFFF,
                        chromaVSamples[chromaIndexV] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV444` plane snapshot into opaque ARGB pixels with one chroma sample per pixel.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static int[] convertOpaqueI444(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            int[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaURow + x] & 0xFFFF,
                        chromaVSamples[chromaVRow + x] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }

    /// Converts one `YUV444` plane snapshot into opaque 16-bit-per-channel ARGB pixels with one
    /// chroma sample per luma sample.
    ///
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane
    /// @param chromaVPlane the decoded chroma V plane
    /// @param outputWidth the decoded output width
    /// @param outputHeight the decoded output height
    /// @param bitDepth the decoded sample bit depth
    /// @param pixels the destination ARGB pixel buffer
    /// @param transform the fixed-point YUV-to-RGB transform used for color conversion
    /// @return the filled destination pixel buffer
    private static long[] convertOpaqueLongI444(
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane,
            int outputWidth,
            int outputHeight,
            int bitDepth,
            long[] pixels,
            YuvToRgbTransform transform
    ) {
        short[] lumaSamples = lumaPlane.samples();
        short[] chromaUSamples = chromaUPlane.samples();
        short[] chromaVSamples = chromaVPlane.samples();
        int lumaStride = lumaPlane.stride();
        int chromaUStride = chromaUPlane.stride();
        int chromaVStride = chromaVPlane.stride();

        for (int y = 0; y < outputHeight; y++) {
            int lumaRow = y * lumaStride;
            int chromaURow = y * chromaUStride;
            int chromaVRow = y * chromaVStride;
            int pixelRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                        lumaSamples[lumaRow + x] & 0xFFFF,
                        chromaUSamples[chromaURow + x] & 0xFFFF,
                        chromaVSamples[chromaVRow + x] & 0xFFFF,
                        bitDepth
                );
            }
        }
        return pixels;
    }
}
