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
    private final Av1ChromaFormat chromaFormat;
    /// Whether an alpha auxiliary image is present.
    private final boolean alphaPresent;
    /// Whether color samples are premultiplied by the alpha auxiliary image.
    private final boolean alphaPremultiplied;
    /// The typed sequence descriptor for animated inputs, or `null` for still images.
    private final @Nullable AvifSequenceInfo sequenceInfo;
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
    /// Opaque item properties associated with the primary image item.
    private final AvifImageItemProperty @Unmodifiable [] itemProperties;

    /// Creates immutable image metadata.
    ///
    /// @param width the display width in pixels
    /// @param height the display height in pixels
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the AV1 chroma sampling layout
    /// @param sequenceInfo the sequence descriptor, or `null` for a still image
    /// @param transformInfo the image-transform descriptor, or `null` when no transform is present
    /// @param auxiliaryImageTypes auxiliary image type strings associated with the primary image, or `null` to derive
    /// them from `auxiliaryImages`
    /// @param auxiliaryImages auxiliary image descriptors associated with the primary image, or `null` when absent
    /// @param alphaPresent whether an alpha image is present; alpha auxiliary metadata also implies this value
    /// @param alphaPremultiplied whether color samples are premultiplied by an alpha auxiliary image; ignored when no
    /// alpha auxiliary image is present
    /// @param gainMapInfo the gain-map descriptor associated with the primary image, or `null`
    /// @param colorInfo the parsed color information, or `null`
    /// @param iccProfile the embedded ICC profile payload, or `null`; the array is copied
    /// @param exif the embedded Exif metadata payload excluding the AVIF Exif header offset field, or `null`; the array
    /// is copied
    /// @param xmp the embedded XMP metadata payload, or `null`; the array is copied
    /// @param itemProperties opaque item properties associated with the primary image item, or `null` when absent; the
    /// array is copied
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AvifImageInfo(
            int width,
            int height,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            @Nullable AvifSequenceInfo sequenceInfo,
            @Nullable AvifImageTransformInfo transformInfo,
            String @Nullable [] auxiliaryImageTypes,
            AvifAuxiliaryImageInfo @Nullable [] auxiliaryImages,
            boolean alphaPresent,
            boolean alphaPremultiplied,
            @Nullable AvifGainMapInfo gainMapInfo,
            @Nullable AvifColorInfo colorInfo,
            byte @Nullable [] iccProfile,
            byte @Nullable [] exif,
            byte @Nullable [] xmp,
            AvifImageItemProperty @Nullable [] itemProperties
    ) {
        if (width <= 0) {
            throw new IllegalArgumentException("width <= 0: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height <= 0: " + height);
        }
        this.width = width;
        this.height = height;
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.sequenceInfo = sequenceInfo;
        this.transformInfo = transformInfo;
        AvifAuxiliaryImageInfo @Unmodifiable [] checkedAuxiliaryImages = immutableAuxiliaryImages(auxiliaryImages);
        this.auxiliaryImageTypes = auxiliaryImageTypes != null
                ? immutableAuxiliaryImageTypes(auxiliaryImageTypes)
                : auxiliaryImageTypesFromDescriptors(checkedAuxiliaryImages);
        this.auxiliaryImages = checkedAuxiliaryImages;
        this.alphaPresent = alphaPresent
                || containsAuxiliaryImageType(this.auxiliaryImageTypes, AvifAuxiliaryImageInfo.ALPHA_TYPE)
                || containsAlphaAuxiliaryImage(checkedAuxiliaryImages);
        this.alphaPremultiplied = this.alphaPresent && alphaPremultiplied;
        this.gainMapInfo = gainMapInfo;
        this.colorInfo = colorInfo;
        this.iccProfile = immutableBytes(iccProfile);
        this.exif = immutableBytes(exif);
        this.xmp = immutableBytes(xmp);
        this.itemProperties = immutableItemProperties(itemProperties);
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
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
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
        return sequenceInfo != null;
    }

    /// Returns the number of frames advertised by the container.
    ///
    /// @return the number of frames advertised by the container
    public int frameCount() {
        return sequenceInfo == null ? 1 : sequenceInfo.frameCount();
    }

    /// Returns the media timescale for animated sequences.
    ///
    /// A value of zero means the container did not expose sequence timing.
    ///
    /// @return the media timescale, or zero when absent
    public int mediaTimescale() {
        return sequenceInfo == null ? 0 : sequenceInfo.mediaTimescale();
    }

    /// Returns the total media duration for animated sequences.
    ///
    /// The value is expressed in `mediaTimescale()` units. A value of zero means the container did
    /// not expose sequence timing.
    ///
    /// @return the total media duration, or zero when absent
    public long mediaDuration() {
        return sequenceInfo == null ? 0 : sequenceInfo.mediaDuration();
    }

    /// Returns the animated-sequence repetition count.
    ///
    /// A non-negative value is the number of repetitions after the first playback. Zero means the
    /// sequence should play once. `REPETITION_COUNT_UNKNOWN` means the container did not expose an
    /// edit list, and `REPETITION_COUNT_INFINITE` means the sequence repeats indefinitely.
    ///
    /// @return the repetition count, `REPETITION_COUNT_UNKNOWN`, or `REPETITION_COUNT_INFINITE`
    public int repetitionCount() {
        return sequenceInfo == null ? REPETITION_COUNT_UNKNOWN : sequenceInfo.repetitionCount();
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
        return sequenceInfo == null ? new int[0] : sequenceInfo.frameDurations();
    }

    /// Returns whether a clean-aperture crop is present.
    ///
    /// @return whether a clean-aperture crop is present
    public boolean hasCleanApertureCrop() {
        return transformInfo != null && transformInfo.hasCleanApertureCrop();
    }

    /// Returns the clean-aperture crop x coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop x coordinate, or -1 when absent
    public int cleanApertureCropX() {
        return transformInfo == null ? -1 : transformInfo.cleanApertureCropX();
    }

    /// Returns the clean-aperture crop y coordinate.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop y coordinate, or -1 when absent
    public int cleanApertureCropY() {
        return transformInfo == null ? -1 : transformInfo.cleanApertureCropY();
    }

    /// Returns the clean-aperture crop width.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop width, or -1 when absent
    public int cleanApertureCropWidth() {
        return transformInfo == null ? -1 : transformInfo.cleanApertureCropWidth();
    }

    /// Returns the clean-aperture crop height.
    ///
    /// A value of -1 means no clean-aperture crop is present.
    ///
    /// @return the clean-aperture crop height, or -1 when absent
    public int cleanApertureCropHeight() {
        return transformInfo == null ? -1 : transformInfo.cleanApertureCropHeight();
    }

    /// Returns the AVIF `irot` rotation code.
    ///
    /// Values 0 through 3 represent 0, 90, 180, and 270 degrees counter-clockwise.
    /// A value of -1 means the property is absent.
    ///
    /// @return the rotation code, or -1 when absent
    public int rotationCode() {
        return transformInfo == null ? -1 : transformInfo.rotationCode();
    }

    /// Returns the AVIF `imir` mirror axis.
    ///
    /// A value of 0 mirrors over the horizontal axis, 1 mirrors over the vertical axis,
    /// and -1 means the property is absent.
    ///
    /// @return the mirror axis, or -1 when absent
    public int mirrorAxis() {
        return transformInfo == null ? -1 : transformInfo.mirrorAxis();
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

    /// Returns opaque item properties associated with the primary image item.
    ///
    /// The reader stores properties whose box type is not interpreted as a typed AVIF feature. The
    /// returned array is empty when no such properties are associated with the primary image item.
    ///
    /// @return opaque item properties associated with the primary image item
    public AvifImageItemProperty @Unmodifiable [] itemProperties() {
        return itemProperties.clone();
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

    /// Creates immutable storage for opaque item property descriptors.
    ///
    /// @param itemProperties the source item property descriptors, or `null`
    /// @return immutable item property descriptor storage
    private static AvifImageItemProperty @Unmodifiable [] immutableItemProperties(
            AvifImageItemProperty @Nullable [] itemProperties
    ) {
        if (itemProperties == null || itemProperties.length == 0) {
            return new AvifImageItemProperty[0];
        }
        AvifImageItemProperty[] result = itemProperties.clone();
        for (AvifImageItemProperty itemProperty : result) {
            Objects.requireNonNull(itemProperty, "itemProperties element");
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

    /// Returns whether an auxiliary image type list contains the requested type.
    ///
    /// @param auxiliaryImageTypes the auxiliary image type strings
    /// @param expectedType the type to find
    /// @return whether the requested type is present
    private static boolean containsAuxiliaryImageType(
            String @Unmodifiable [] auxiliaryImageTypes,
            String expectedType
    ) {
        for (String auxiliaryImageType : auxiliaryImageTypes) {
            if (expectedType.equals(auxiliaryImageType)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether any auxiliary image descriptor represents alpha samples.
    ///
    /// @param auxiliaryImages the auxiliary image descriptors
    /// @return whether an alpha auxiliary image is present
    private static boolean containsAlphaAuxiliaryImage(
            AvifAuxiliaryImageInfo @Unmodifiable [] auxiliaryImages
    ) {
        for (AvifAuxiliaryImageInfo auxiliaryImage : auxiliaryImages) {
            if (auxiliaryImage.isAlpha()) {
                return true;
            }
        }
        return false;
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static @Nullable @UnmodifiableView ByteBuffer byteView(@Nullable @Unmodifiable ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
