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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable metadata for one parsed AVIF image.
@NotNullByDefault
public final class AvifImageInfo {
    /// Repetition count value used when an animated sequence has no edit list.
    public static final int REPETITION_COUNT_UNKNOWN = -1;
    /// Repetition count value used when an animated sequence repeats indefinitely.
    public static final int REPETITION_COUNT_INFINITE = -2;

    /// The display width in pixels.
    private final int width;
    /// The display height in pixels.
    private final int height;
    /// The decoded bit depth.
    private final AvifBitDepth bitDepth;
    /// The AV1 chroma sampling layout.
    private final AvifPixelFormat pixelFormat;
    /// Whether an alpha auxiliary image is present.
    private final boolean alphaPresent;
    /// Whether color samples are premultiplied by the alpha auxiliary image.
    private final boolean alphaPremultiplied;
    /// Whether the input is an animated image sequence.
    private final boolean animated;
    /// The number of frames advertised by the container.
    private final int frameCount;
    /// The media timescale for animated sequences, or zero when absent.
    private final int mediaTimescale;
    /// The total media duration in media timescale units, or zero when absent.
    private final long mediaDuration;
    /// The repetition count for animated sequences.
    private final int repetitionCount;
    /// Per-frame durations in media timescale units.
    private final int @Unmodifiable [] frameDurations;
    /// The typed sequence descriptor for animated inputs, or `null` for still images.
    private final @Nullable AvifSequenceInfo sequenceInfo;
    /// The clean-aperture crop x coordinate, or -1 when absent.
    private final int cleanApertureCropX;
    /// The clean-aperture crop y coordinate, or -1 when absent.
    private final int cleanApertureCropY;
    /// The clean-aperture crop width, or -1 when absent.
    private final int cleanApertureCropWidth;
    /// The clean-aperture crop height, or -1 when absent.
    private final int cleanApertureCropHeight;
    /// The clockwise rotation code from the `irot` property, or -1 when absent.
    private final int rotationCode;
    /// The mirror axis from the `imir` property, or -1 when absent.
    private final int mirrorAxis;
    /// The typed image-transform descriptor, or `null` when no image transform is present.
    private final @Nullable AvifImageTransformInfo transformInfo;
    /// Auxiliary image type strings associated with the primary image.
    private final String @Unmodifiable [] auxiliaryImageTypes;
    /// Auxiliary image descriptors associated with the primary image.
    private final AvifAuxiliaryImageInfo @Unmodifiable [] auxiliaryImages;
    /// The gain-map descriptor associated with the primary image, or `null`.
    private final @Nullable AvifGainMapInfo gainMapInfo;
    /// The parsed color information, or `null`.
    private final @Nullable AvifColorInfo colorInfo;
    /// The embedded ICC profile payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer iccProfile;
    /// The embedded Exif metadata payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer exif;
    /// The embedded XMP metadata payload, or `null`.
    private final @Nullable @Unmodifiable ByteBuffer xmp;

    /// Creates image metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo
    ) {
        this(width, height, bitDepth, pixelFormat, alphaPresent, animated, frameCount, colorInfo, null, null, null);
    }

    /// Creates image metadata with embedded ICC, Exif, and XMP payloads.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                0,
                0,
                null
        );
    }

    /// Creates image metadata with embedded metadata payloads and sequence timing.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                mediaTimescale,
                mediaDuration,
                frameDurations,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                null
        );
    }

    /// Creates image metadata with embedded metadata payloads, sequence timing, transforms, and auxiliary types.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from the `irot` property, or -1 when absent
    /// @param mirrorAxis the mirror axis from the `imir` property, or -1 when absent
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null`
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations,
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis,
            String @Nullable [] auxiliaryImageTypes
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                mediaTimescale,
                mediaDuration,
                frameDurations,
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis,
                auxiliaryImageTypes,
                null
        );
    }

    /// Creates image metadata with embedded metadata payloads, sequence timing, transforms, and auxiliary metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from the `irot` property, or -1 when absent
    /// @param mirrorAxis the mirror axis from the `imir` property, or -1 when absent
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null`
    /// @param auxiliaryImages auxiliary image descriptors associated with the primary image, or `null`
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations,
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis,
            String @Nullable [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                mediaTimescale,
                mediaDuration,
                frameDurations,
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis,
                auxiliaryImageTypes,
                auxiliaryImages,
                null
        );
    }

    /// Creates image metadata with embedded metadata payloads, transforms, auxiliary metadata, and gain-map metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from the `irot` property, or -1 when absent
    /// @param mirrorAxis the mirror axis from the `imir` property, or -1 when absent
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null`
    /// @param auxiliaryImages auxiliary image descriptors associated with the primary image, or `null`
    /// @param gainMapInfo the gain-map descriptor associated with the primary image, or `null`
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations,
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis,
            String @Nullable [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages,
            @Nullable AvifGainMapInfo gainMapInfo
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                mediaTimescale,
                mediaDuration,
                frameDurations,
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis,
                auxiliaryImageTypes,
                auxiliaryImages,
                gainMapInfo,
                REPETITION_COUNT_UNKNOWN
        );
    }

    /// Creates image metadata with embedded metadata payloads, transforms, auxiliary metadata, gain-map metadata,
    /// and sequence repetition metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from the `irot` property, or -1 when absent
    /// @param mirrorAxis the mirror axis from the `imir` property, or -1 when absent
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null`
    /// @param auxiliaryImages auxiliary image descriptors associated with the primary image, or `null`
    /// @param gainMapInfo the gain-map descriptor associated with the primary image, or `null`
    /// @param repetitionCount the animated-sequence repetition count, `REPETITION_COUNT_UNKNOWN`, or
    /// `REPETITION_COUNT_INFINITE`
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations,
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis,
            String @Nullable [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages,
            @Nullable AvifGainMapInfo gainMapInfo,
            int repetitionCount
    ) {
        this(
                width,
                height,
                bitDepth,
                pixelFormat,
                alphaPresent,
                animated,
                frameCount,
                colorInfo,
                iccProfile,
                exif,
                xmp,
                mediaTimescale,
                mediaDuration,
                frameDurations,
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis,
                auxiliaryImageTypes,
                auxiliaryImages,
                gainMapInfo,
                repetitionCount,
                false
        );
    }

    /// Creates image metadata with embedded metadata payloads, transforms, auxiliary metadata, gain-map metadata,
    /// sequence repetition metadata, and alpha premultiplication metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param pixelFormat the AV1 chroma sampling layout
    /// @param alphaPresent whether an alpha auxiliary image is present
    /// @param animated whether the input is an animated image sequence
    /// @param frameCount the number of frames advertised by the container
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`
    /// @param xmp the embedded XMP metadata payload, or `null`
    /// @param mediaTimescale the media timescale for animated sequences, or zero when absent
    /// @param mediaDuration the total media duration in media timescale units, or zero when absent
    /// @param frameDurations per-frame durations in media timescale units, or `null` when absent
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from the `irot` property, or -1 when absent
    /// @param mirrorAxis the mirror axis from the `imir` property, or -1 when absent
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null`
    /// @param auxiliaryImages auxiliary image descriptors associated with the primary image, or `null`
    /// @param gainMapInfo the gain-map descriptor associated with the primary image, or `null`
    /// @param repetitionCount the animated-sequence repetition count, `REPETITION_COUNT_UNKNOWN`, or
    /// `REPETITION_COUNT_INFINITE`
    /// @param alphaPremultiplied whether color samples are premultiplied by the alpha auxiliary image
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            AvifPixelFormat pixelFormat,
            boolean alphaPresent,
            boolean animated,
            int frameCount,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            int mediaTimescale,
            long mediaDuration,
            int @Nullable [] frameDurations,
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis,
            String @Nullable [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages,
            @Nullable AvifGainMapInfo gainMapInfo,
            int repetitionCount,
            boolean alphaPremultiplied
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount <= 0: " + frameCount);
        }
        if (mediaTimescale < 0) {
            throw new IllegalArgumentException("mediaTimescale < 0: " + mediaTimescale);
        }
        if (mediaDuration < 0) {
            throw new IllegalArgumentException("mediaDuration < 0: " + mediaDuration);
        }
        if (!isAbsentCleanAperture(cleanApertureCropX, cleanApertureCropY, cleanApertureCropWidth, cleanApertureCropHeight)
                && (cleanApertureCropX < 0 || cleanApertureCropY < 0
                || cleanApertureCropWidth <= 0 || cleanApertureCropHeight <= 0)) {
            throw new IllegalArgumentException("Invalid clean-aperture crop parameters");
        }
        if (rotationCode < -1 || rotationCode > 3) {
            throw new IllegalArgumentException("rotationCode must be -1 or between 0 and 3: " + rotationCode);
        }
        if (mirrorAxis < -1 || mirrorAxis > 1) {
            throw new IllegalArgumentException("mirrorAxis must be -1, 0, or 1: " + mirrorAxis);
        }
        if (repetitionCount < 0
                && repetitionCount != REPETITION_COUNT_UNKNOWN
                && repetitionCount != REPETITION_COUNT_INFINITE) {
            throw new IllegalArgumentException("Invalid repetition count: " + repetitionCount);
        }

        int @Unmodifiable [] checkedFrameDurations = immutableFrameDurations(frameDurations);
        if (checkedFrameDurations.length != 0 && checkedFrameDurations.length != frameCount) {
            throw new IllegalArgumentException(
                    "frameDurations length must match frameCount: " + checkedFrameDurations.length
            );
        }

        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.alphaPresent = alphaPresent;
        this.alphaPremultiplied = alphaPresent && alphaPremultiplied;
        this.animated = animated;
        this.frameCount = frameCount;
        this.mediaTimescale = mediaTimescale;
        this.mediaDuration = mediaDuration;
        this.repetitionCount = repetitionCount;
        this.frameDurations = checkedFrameDurations;
        this.sequenceInfo = animated ? new AvifSequenceInfo(
                frameCount,
                mediaTimescale,
                mediaDuration,
                repetitionCount,
                checkedFrameDurations
        ) : null;
        this.cleanApertureCropX = cleanApertureCropX;
        this.cleanApertureCropY = cleanApertureCropY;
        this.cleanApertureCropWidth = cleanApertureCropWidth;
        this.cleanApertureCropHeight = cleanApertureCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
        this.transformInfo = imageTransformInfo(
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis
        );
        AvifAuxiliaryImageInfo @Unmodifiable [] checkedAuxiliaryImages = immutableAuxiliaryImages(auxiliaryImages);
        this.auxiliaryImageTypes = auxiliaryImageTypes != null
                ? immutableAuxiliaryImageTypes(auxiliaryImageTypes)
                : auxiliaryImageTypesFromDescriptors(checkedAuxiliaryImages);
        this.auxiliaryImages = checkedAuxiliaryImages;
        this.gainMapInfo = gainMapInfo;
        this.colorInfo = colorInfo;
        this.iccProfile = immutableBytes(iccProfile);
        this.exif = immutableBytes(exif);
        this.xmp = immutableBytes(xmp);
    }

    /// Returns the display width in pixels.
    ///
    /// @return the display width in pixels
    public int width() {
        return width;
    }

    /// Returns the display height in pixels.
    ///
    /// @return the display height in pixels
    public int height() {
        return height;
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the AV1 chroma sampling layout.
    ///
    /// @return the AV1 chroma sampling layout
    public AvifPixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// Returns whether an alpha auxiliary image is present.
    ///
    /// @return whether an alpha auxiliary image is present
    public boolean alphaPresent() {
        return alphaPresent;
    }

    /// Returns whether color samples are premultiplied by the alpha auxiliary image.
    ///
    /// Decoded [AvifFrame] pixels are still exposed as non-premultiplied ARGB. This flag reports the
    /// source container semantics before conversion.
    ///
    /// @return whether the source color samples are premultiplied by alpha
    public boolean alphaPremultiplied() {
        return alphaPremultiplied;
    }

    /// Returns whether the input is an animated image sequence.
    ///
    /// @return whether the input is an animated image sequence
    public boolean animated() {
        return animated;
    }

    /// Returns the number of frames advertised by the container.
    ///
    /// @return the number of frames advertised by the container
    public int frameCount() {
        return frameCount;
    }

    /// Returns the media timescale for animated sequences.
    ///
    /// A value of zero means the container did not expose sequence timing.
    ///
    /// @return the media timescale, or zero when absent
    public int mediaTimescale() {
        return mediaTimescale;
    }

    /// Returns the total media duration for animated sequences.
    ///
    /// The value is expressed in `mediaTimescale()` units. A value of zero means the container did
    /// not expose sequence timing.
    ///
    /// @return the total media duration, or zero when absent
    public long mediaDuration() {
        return mediaDuration;
    }

    /// Returns the animated-sequence repetition count.
    ///
    /// A non-negative value is the number of repetitions after the first playback. Zero means the
    /// sequence should play once. `REPETITION_COUNT_UNKNOWN` means the container did not expose an
    /// edit list, and `REPETITION_COUNT_INFINITE` means the sequence repeats indefinitely.
    ///
    /// @return the repetition count, `REPETITION_COUNT_UNKNOWN`, or `REPETITION_COUNT_INFINITE`
    public int repetitionCount() {
        return repetitionCount;
    }

    /// Returns the typed animated-sequence descriptor.
    ///
    /// Still images return `null`.
    ///
    /// @return the sequence descriptor, or `null`
    public @Nullable AvifSequenceInfo sequenceInfo() {
        return sequenceInfo;
    }

    /// Returns per-frame durations for animated sequences.
    ///
    /// Values are expressed in `mediaTimescale()` units. Still images and inputs without timing
    /// metadata return an empty array.
    ///
    /// @return per-frame durations in media timescale units
    public int @Unmodifiable [] frameDurations() {
        return frameDurations.clone();
    }

    /// Returns whether a clean-aperture crop is present.
    ///
    /// @return whether a clean-aperture crop is present
    public boolean hasCleanApertureCrop() {
        return cleanApertureCropX >= 0;
    }

    /// Returns the clean-aperture crop x coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop x coordinate, or -1 when absent
    public int cleanApertureCropX() {
        return cleanApertureCropX;
    }

    /// Returns the clean-aperture crop y coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop y coordinate, or -1 when absent
    public int cleanApertureCropY() {
        return cleanApertureCropY;
    }

    /// Returns the clean-aperture crop width.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop width, or -1 when absent
    public int cleanApertureCropWidth() {
        return cleanApertureCropWidth;
    }

    /// Returns the clean-aperture crop height.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop height, or -1 when absent
    public int cleanApertureCropHeight() {
        return cleanApertureCropHeight;
    }

    /// Returns the clockwise rotation code from the `irot` property.
    ///
    /// Values 0 through 3 represent 0, 90, 180, and 270 degrees clockwise.
    /// A value of -1 means the property is absent.
    ///
    /// @return the rotation code, or -1 when absent
    public int rotationCode() {
        return rotationCode;
    }

    /// Returns the mirror axis from the `imir` property.
    ///
    /// A value of 0 mirrors over the vertical axis, 1 mirrors over the horizontal axis,
    /// and -1 means the property is absent.
    ///
    /// @return the mirror axis, or -1 when absent
    public int mirrorAxis() {
        return mirrorAxis;
    }

    /// Returns the typed AVIF image-transform descriptor.
    ///
    /// Inputs without `clap`, `irot`, or `imir` item properties return `null`.
    ///
    /// @return the image-transform descriptor, or `null`
    public @Nullable AvifImageTransformInfo transformInfo() {
        return transformInfo;
    }

    /// Returns auxiliary image type strings associated with the primary image.
    ///
    /// The returned array includes alpha auxiliary image types when present.
    ///
    /// @return auxiliary image type strings associated with the primary image
    public String @Unmodifiable [] auxiliaryImageTypes() {
        return auxiliaryImageTypes.clone();
    }

    /// Returns auxiliary image descriptors associated with the primary image.
    ///
    /// The returned array includes alpha auxiliary images when present.
    ///
    /// @return auxiliary image descriptors associated with the primary image
    public AvifAuxiliaryImageInfo @Unmodifiable [] auxiliaryImages() {
        return auxiliaryImages.clone();
    }

    /// Returns the gain-map descriptor associated with the primary image.
    ///
    /// The returned descriptor is present only when the file advertises the `tmap` brand and the
    /// `tmap` item is the preferred alternative to the primary image item. The descriptor does not
    /// imply that gain-map pixels have been tone-mapped or applied to the base image.
    ///
    /// @return the gain-map descriptor, or `null`
    public @Nullable AvifGainMapInfo gainMapInfo() {
        return gainMapInfo;
    }

    /// Returns the parsed color information.
    ///
    /// @return the parsed color information, or `null`
    public @Nullable AvifColorInfo colorInfo() {
        return colorInfo;
    }

    /// Returns the embedded ICC profile payload.
    ///
    /// @return a read-only view of the embedded ICC profile payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer iccProfile() {
        return byteView(iccProfile);
    }

    /// Returns the embedded Exif metadata payload.
    ///
    /// The returned payload excludes the AVIF `exif_tiff_header_offset` field
    /// and matches the Exif byte sequence exposed by libavif.
    ///
    /// @return a read-only view of the embedded Exif metadata payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer exif() {
        return byteView(exif);
    }

    /// Returns the embedded XMP metadata payload.
    ///
    /// @return a read-only view of the embedded XMP metadata payload, or `null`
    public @Nullable @UnmodifiableView ByteBuffer xmp() {
        return byteView(xmp);
    }

    /// Creates immutable byte-buffer storage for one optional payload.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte-buffer storage, or `null`
    private static @Nullable @Unmodifiable ByteBuffer immutableBytes(byte @Nullable [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length)).asReadOnlyBuffer();
    }

    /// Creates immutable storage for optional frame-duration data.
    ///
    /// @param frameDurations the source frame durations, or `null`
    /// @return immutable frame-duration storage
    private static int @Unmodifiable [] immutableFrameDurations(int @Nullable [] frameDurations) {
        if (frameDurations == null || frameDurations.length == 0) {
            return new int[0];
        }
        int[] result = frameDurations.clone();
        for (int duration : result) {
            if (duration < 0) {
                throw new IllegalArgumentException("frameDurations contains a negative duration: " + duration);
            }
        }
        return result;
    }

    /// Creates immutable storage for auxiliary image type strings.
    ///
    /// @param auxiliaryImageTypes the source auxiliary image type strings, or `null`
    /// @return immutable auxiliary image type storage
    private static String @Unmodifiable [] immutableAuxiliaryImageTypes(String @Nullable [] auxiliaryImageTypes) {
        if (auxiliaryImageTypes == null || auxiliaryImageTypes.length == 0) {
            return new String[0];
        }
        String[] result = auxiliaryImageTypes.clone();
        for (String auxiliaryImageType : result) {
            Objects.requireNonNull(auxiliaryImageType, "auxiliaryImageTypes element");
        }
        return result;
    }

    /// Creates immutable storage for auxiliary image descriptors.
    ///
    /// @param auxiliaryImages the source auxiliary image descriptors, or `null`
    /// @return immutable auxiliary image descriptor storage
    private static AvifAuxiliaryImageInfo @Unmodifiable [] immutableAuxiliaryImages(
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages
    ) {
        if (auxiliaryImages == null || auxiliaryImages.length == 0) {
            return new AvifAuxiliaryImageInfo[0];
        }
        AvifAuxiliaryImageInfo[] result = auxiliaryImages.clone();
        for (AvifAuxiliaryImageInfo auxiliaryImage : result) {
            Objects.requireNonNull(auxiliaryImage, "auxiliaryImages element");
        }
        return result;
    }

    /// Creates auxiliary image type storage from auxiliary image descriptors.
    ///
    /// @param auxiliaryImages the auxiliary image descriptors
    /// @return auxiliary image type storage
    private static String @Unmodifiable [] auxiliaryImageTypesFromDescriptors(
            AvifAuxiliaryImageInfo @Unmodifiable [] auxiliaryImages
    ) {
        if (auxiliaryImages.length == 0) {
            return new String[0];
        }
        String[] result = new String[auxiliaryImages.length];
        int size = 0;
        for (AvifAuxiliaryImageInfo auxiliaryImage : auxiliaryImages) {
            String type = auxiliaryImage.auxiliaryType();
            boolean seen = false;
            for (int i = 0; i < size; i++) {
                if (result[i].equals(type)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result[size++] = type;
            }
        }
        return Arrays.copyOf(result, size);
    }

    /// Returns whether clean-aperture parameters represent an absent property.
    ///
    /// @param cleanApertureCropX the clean-aperture crop x coordinate
    /// @param cleanApertureCropY the clean-aperture crop y coordinate
    /// @param cleanApertureCropWidth the clean-aperture crop width
    /// @param cleanApertureCropHeight the clean-aperture crop height
    /// @return whether the clean-aperture property is absent
    private static boolean isAbsentCleanAperture(
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight
    ) {
        return cleanApertureCropX == -1
                && cleanApertureCropY == -1
                && cleanApertureCropWidth == -1
                && cleanApertureCropHeight == -1;
    }

    /// Creates typed image-transform metadata when any transform property is present.
    ///
    /// @param cleanApertureCropX the clean-aperture crop x coordinate
    /// @param cleanApertureCropY the clean-aperture crop y coordinate
    /// @param cleanApertureCropWidth the clean-aperture crop width
    /// @param cleanApertureCropHeight the clean-aperture crop height
    /// @param rotationCode the clockwise rotation code
    /// @param mirrorAxis the mirror axis
    /// @return typed image-transform metadata, or `null`
    private static @Nullable AvifImageTransformInfo imageTransformInfo(
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis
    ) {
        if (isAbsentCleanAperture(cleanApertureCropX, cleanApertureCropY, cleanApertureCropWidth, cleanApertureCropHeight)
                && rotationCode == -1 && mirrorAxis == -1) {
            return null;
        }
        return new AvifImageTransformInfo(
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight,
                rotationCode,
                mirrorAxis
        );
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static @Nullable @UnmodifiableView ByteBuffer byteView(@Nullable @Unmodifiable ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
