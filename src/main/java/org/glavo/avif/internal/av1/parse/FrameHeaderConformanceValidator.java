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
package org.glavo.avif.internal.av1.parse;

import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Validates strict AV1 frame-header constraints that depend on previously parsed state.
@NotNullByDefault
final class FrameHeaderConformanceValidator {
    /// Number of reference slots signaled by one inter frame.
    private static final int REFERENCES_PER_FRAME = 7;

    /// Prevents instantiation of this utility class.
    private FrameHeaderConformanceValidator() {
    }

    /// Validates level limits and reference-frame dimension ratios for one parsed frame.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @throws IOException if the frame violates a strict conformance constraint
    static void validate(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            @Nullable FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(referenceFrameHeaders, "referenceFrameHeaders");

        if (frameHeader.allLossless() && frameHeader.delta().deltaQPresent()) {
            throw new IOException("Coded-lossless frames must not signal delta_q_present");
        }
        validateSequenceFrameDimensions(sequenceHeader, frameHeader);
        validateShortSignaledReferences(sequenceHeader, frameHeader, referenceFrameHeaders);
        validateLevelLimits(sequenceHeader, frameHeader);
        for (int referenceIndex = 0; referenceIndex < REFERENCES_PER_FRAME; referenceIndex++) {
            int slotIndex = frameHeader.referenceFrameIndex(referenceIndex);
            if (slotIndex < 0) {
                continue;
            }
            @Nullable FrameHeader referenceFrameHeader = referenceFrameHeaders[slotIndex];
            if (referenceFrameHeader != null) {
                validateReferenceDimensions(frameHeader.frameSize(), referenceFrameHeader.frameSize());
            }
        }
    }

    /// Validates explicitly signaled frame dimensions against the sequence-header maxima.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if the frame exceeds either declared maximum dimension
    private static void validateSequenceFrameDimensions(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) throws IOException {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        if (frameSize.upscaledWidth() > sequenceHeader.maxWidth()) {
            throw new IOException("Frame width exceeds the sequence-header maximum");
        }
        if (frameSize.height() > sequenceHeader.maxHeight()) {
            throw new IOException("Frame height exceeds the sequence-header maximum");
        }
    }

    /// Validates the LAST and GOLDEN order hints used by short reference signaling.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @throws IOException if either explicitly selected reference is not earlier in output order
    private static void validateShortSignaledReferences(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            @Nullable FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        if (!frameHeader.frameReferenceShortSignaling()) {
            return;
        }
        validateEarlierReferenceOrderHint(sequenceHeader, frameHeader, referenceFrameHeaders, 0, "LAST");
        validateEarlierReferenceOrderHint(sequenceHeader, frameHeader, referenceFrameHeaders, 3, "GOLDEN");
    }

    /// Validates one short-signaled reference against the current frame order hint.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceIndex the LAST..ALTREF reference position
    /// @param referenceName the reference name used in diagnostics
    /// @throws IOException if the reference is unavailable or is not earlier in output order
    private static void validateEarlierReferenceOrderHint(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            @Nullable FrameHeader[] referenceFrameHeaders,
            int referenceIndex,
            String referenceName
    ) throws IOException {
        int slotIndex = frameHeader.referenceFrameIndex(referenceIndex);
        if (slotIndex < 0 || slotIndex >= referenceFrameHeaders.length) {
            throw new IOException(referenceName + " reference does not select a valid frame slot");
        }
        @Nullable FrameHeader referenceFrameHeader = referenceFrameHeaders[slotIndex];
        if (referenceFrameHeader == null) {
            throw new IOException(referenceName + " reference selects an unavailable frame slot");
        }
        int relativeDistance = relativeDistance(
                sequenceHeader.features().orderHintBits(),
                referenceFrameHeader.frameOffset(),
                frameHeader.frameOffset()
        );
        if (relativeDistance >= 0) {
            throw new IOException(referenceName + " reference order hint is not earlier than the current frame");
        }
    }

    /// Returns the wrapped AV1 relative distance between two order hints.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param first the first order hint
    /// @param second the second order hint
    /// @return the wrapped signed distance `first - second`
    private static int relativeDistance(int orderHintBits, int first, int second) {
        int mask = 1 << (orderHintBits - 1);
        int difference = first - second;
        return (difference & (mask - 1)) - (difference & mask);
    }

    /// Validates the current frame against each operating point that contains its layer.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if the frame exceeds an applicable level limit
    private static void validateLevelLimits(SequenceHeader sequenceHeader, FrameHeader frameHeader) throws IOException {
        boolean croppedTileDimensionsValidated = false;
        for (int index = 0; index < sequenceHeader.operatingPointCount(); index++) {
            SequenceHeader.OperatingPoint operatingPoint = sequenceHeader.operatingPoint(index);
            if (!containsLayer(operatingPoint.idc(), frameHeader.temporalId(), frameHeader.spatialId())) {
                continue;
            }
            @Nullable LevelLimits limits = levelLimits(operatingPoint.majorLevel(), operatingPoint.minorLevel());
            if (limits != null) {
                if (!croppedTileDimensionsValidated) {
                    validateCroppedTileDimensions(sequenceHeader, frameHeader);
                    croppedTileDimensionsValidated = true;
                }
                validateLevelLimits(frameHeader, limits, operatingPoint.majorLevel(), operatingPoint.minorLevel());
            }
        }
    }

    /// Returns whether an operating-point mask contains one temporal and spatial layer pair.
    ///
    /// @param operatingPointIdc the operating-point layer mask
    /// @param temporalId the frame temporal layer identifier
    /// @param spatialId the frame spatial layer identifier
    /// @return whether the frame belongs to the operating point
    private static boolean containsLayer(int operatingPointIdc, int temporalId, int spatialId) {
        if (operatingPointIdc == 0) {
            return true;
        }
        return (operatingPointIdc & (1 << temporalId)) != 0
                && (operatingPointIdc & (1 << (spatialId + 8))) != 0;
    }

    /// Validates frame dimensions and tile counts against one level-limit row.
    ///
    /// @param frameHeader the parsed current frame header
    /// @param limits the applicable level limits
    /// @param majorLevel the declared major level
    /// @param minorLevel the declared minor level
    /// @throws IOException if the frame exceeds a level limit
    private static void validateLevelLimits(
            FrameHeader frameHeader,
            LevelLimits limits,
            int majorLevel,
            int minorLevel
    ) throws IOException {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int upscaledWidth = frameSize.upscaledWidth();
        int frameHeight = frameSize.height();
        long pictureSize = (long) upscaledWidth * frameHeight;
        long tileCount = (long) tiling.columns() * tiling.rows();
        String level = majorLevel + "." + minorLevel;

        if (upscaledWidth < 16) {
            throw new IOException("Frame width is smaller than the AV1 level minimum of 16 pixels");
        }
        if (frameHeight < 16) {
            throw new IOException("Frame height is smaller than the AV1 level minimum of 16 pixels");
        }
        if (pictureSize > limits.maximumPictureSize()) {
            throw new IOException("Frame picture size exceeds AV1 level " + level + " MaxPicSize");
        }
        if (upscaledWidth > limits.maximumHorizontalSize()) {
            throw new IOException("Frame upscaled width exceeds AV1 level " + level + " MaxHSize");
        }
        if (frameHeight > limits.maximumVerticalSize()) {
            throw new IOException("Frame height exceeds AV1 level " + level + " MaxVSize");
        }
        if (tileCount > limits.maximumTileCount()) {
            throw new IOException("Frame tile count exceeds AV1 level " + level + " MaxTiles");
        }
        if (tiling.columns() > limits.maximumTileColumns()) {
            throw new IOException("Frame tile-column count exceeds AV1 level " + level + " MaxTileCols");
        }
    }

    /// Validates that every cropped tile retains at least eight pixels in each dimension.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if a cropped edge tile is narrower or shorter than eight pixels
    private static void validateCroppedTileDimensions(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) throws IOException {
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int[] columnStarts = tiling.columnStartSuperblocks();
        int[] rowStarts = tiling.rowStartSuperblocks();
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int frameWidth = frameHeader.frameSize().codedWidth();
        int frameHeight = frameHeader.frameSize().height();

        for (int column = 0; column < tiling.columns(); column++) {
            int start = Math.min(frameWidth, columnStarts[column] * superblockSize);
            int end = Math.min(frameWidth, columnStarts[column + 1] * superblockSize);
            if (end - start < 8) {
                throw new IOException("Cropped tile width is smaller than eight pixels");
            }
        }
        for (int row = 0; row < tiling.rows(); row++) {
            int start = Math.min(frameHeight, rowStarts[row] * superblockSize);
            int end = Math.min(frameHeight, rowStarts[row + 1] * superblockSize);
            if (end - start < 8) {
                throw new IOException("Cropped tile height is smaller than eight pixels");
            }
        }
    }

    /// Validates the AV1 dimension-ratio limits between a frame and one referenced frame.
    ///
    /// @param currentSize the current coded frame dimensions
    /// @param referenceSize the referenced frame dimensions
    /// @throws IOException if either dimension ratio is outside the permitted range
    static void validateReferenceDimensions(
            FrameHeader.FrameSize currentSize,
            FrameHeader.FrameSize referenceSize
    ) throws IOException {
        Objects.requireNonNull(currentSize, "currentSize");
        Objects.requireNonNull(referenceSize, "referenceSize");
        int frameWidth = currentSize.codedWidth();
        int frameHeight = currentSize.height();
        int referenceWidth = referenceSize.upscaledWidth();
        int referenceHeight = referenceSize.height();

        if (2L * frameWidth < referenceWidth || 2L * frameHeight < referenceHeight) {
            throw new IOException("Referenced frame dimensions exceed twice the current frame dimensions");
        }
        if (frameWidth > 16L * referenceWidth || frameHeight > 16L * referenceHeight) {
            throw new IOException("Current frame dimensions exceed sixteen times the referenced frame dimensions");
        }
    }

    /// Returns the AV1 level-limit row for a defined level.
    ///
    /// Undefined level indices intentionally return `null`; the specification does not map them to
    /// a parameter-limit row.
    ///
    /// @param majorLevel the declared major level
    /// @param minorLevel the declared minor level
    /// @return the corresponding level limits, or `null` for an undefined level index
    private static @Nullable LevelLimits levelLimits(int majorLevel, int minorLevel) {
        return switch (majorLevel * 10 + minorLevel) {
            case 20 -> new LevelLimits(147_456, 2_048, 1_152, 8, 4);
            case 21 -> new LevelLimits(278_784, 2_816, 1_584, 8, 4);
            case 30 -> new LevelLimits(665_856, 4_352, 2_448, 16, 6);
            case 31 -> new LevelLimits(1_065_024, 5_504, 3_096, 16, 6);
            case 40, 41 -> new LevelLimits(2_359_296, 6_144, 3_456, 32, 8);
            case 50, 51, 52, 53 -> new LevelLimits(8_912_896, 8_192, 4_352, 64, 8);
            case 60, 61, 62, 63 -> new LevelLimits(35_651_584, 16_384, 8_704, 128, 16);
            default -> null;
        };
    }

    /// Defines the frame-size and tile-count limits for one AV1 level row.
    ///
    /// @param maximumPictureSize the maximum luma picture area
    /// @param maximumHorizontalSize the maximum upscaled frame width
    /// @param maximumVerticalSize the maximum frame height
    /// @param maximumTileCount the maximum number of tiles
    /// @param maximumTileColumns the maximum number of tile columns
    private record LevelLimits(
            int maximumPictureSize,
            int maximumHorizontalSize,
            int maximumVerticalSize,
            int maximumTileCount,
            int maximumTileColumns
    ) {
    }
}
