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
package org.glavo.avif.internal.av1.postfilter;

import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/// Applies the current CDEF stage of the postfilter pipeline.
///
/// This implementation applies a deterministic CDEF subset from decoded frame-level strengths and
/// block-level `cdefIndex` syntax. It keeps inactive CDEF as an identity transform and rejects
/// active CDEF when block syntax is unavailable instead of silently preserving filtered frames.
@NotNullByDefault
public final class CdefApplier {
    /// The luma CDEF unit size in samples.
    private static final int CDEF_UNIT_SIZE = 8;

    /// Direction vectors used for primary CDEF filtering.
    private static final int[][] DIRECTIONS = {
            {1, 0},
            {1, -1},
            {0, -1},
            {-1, -1},
            {-1, 0},
            {-1, 1},
            {0, 1},
            {1, 1}
    };

    /// Primary CDEF taps selected by primary-strength parity.
    private static final int[][] PRIMARY_TAPS = {
            {4, 2},
            {3, 3}
    };

    /// Secondary CDEF taps.
    private static final int[] SECONDARY_TAPS = {2, 1};

    /// Applies CDEF to one decoded frame.
    ///
    /// @param decodedPlanes the decoded planes after loop filtering
    /// @param cdef the normalized frame-level CDEF state
    /// @return the post-CDEF planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.CdefInfo cdef) {
        return apply(decodedPlanes, cdef, null);
    }

    /// Applies CDEF to one decoded frame using block-level syntax for CDEF index selection.
    ///
    /// @param decodedPlanes the decoded planes after loop filtering
    /// @param cdef the normalized frame-level CDEF state
    /// @param syntaxDecodeResult the decoded block syntax that carries CDEF indices, or `null`
    /// @return the post-CDEF planes
    public DecodedPlanes apply(
            DecodedPlanes decodedPlanes,
            FrameHeader.CdefInfo cdef,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.CdefInfo checkedCdef = Objects.requireNonNull(cdef, "cdef");
        if (!hasActiveStrengths(checkedCdef, decodedPlanes.hasChroma())) {
            return decodedPlanes;
        }
        if (syntaxDecodeResult == null) {
            throw new IllegalStateException("Active AV1 CDEF filtering requires decoded block CDEF indices");
        }

        int unitColumns = cdefUnitCount(decodedPlanes.codedWidth());
        int unitRows = cdefUnitCount(decodedPlanes.codedHeight());
        int[] cdefIndices = buildCdefIndexMap(syntaxDecodeResult, checkedCdef, unitColumns, unitRows);

        DecodedPlane lumaPlane = applyPlane(
                decodedPlanes.lumaPlane(),
                decodedPlanes.bitDepth(),
                checkedCdef.damping(),
                checkedCdef.yStrengths(),
                cdefIndices,
                unitColumns,
                unitRows,
                CDEF_UNIT_SIZE,
                CDEF_UNIT_SIZE
        );
        @Nullable DecodedPlane chromaUPlane = decodedPlanes.chromaUPlane();
        @Nullable DecodedPlane chromaVPlane = decodedPlanes.chromaVPlane();
        if (decodedPlanes.hasChroma()) {
            int chromaUnitWidth = Math.max(1, CDEF_UNIT_SIZE >> chromaSubsamplingX(decodedPlanes.pixelFormat()));
            int chromaUnitHeight = Math.max(1, CDEF_UNIT_SIZE >> chromaSubsamplingY(decodedPlanes.pixelFormat()));
            chromaUPlane = applyPlane(
                    Objects.requireNonNull(chromaUPlane, "chromaUPlane"),
                    decodedPlanes.bitDepth(),
                    checkedCdef.damping(),
                    checkedCdef.uvStrengths(),
                    cdefIndices,
                    unitColumns,
                    unitRows,
                    chromaUnitWidth,
                    chromaUnitHeight
            );
            chromaVPlane = applyPlane(
                    Objects.requireNonNull(chromaVPlane, "chromaVPlane"),
                    decodedPlanes.bitDepth(),
                    checkedCdef.damping(),
                    checkedCdef.uvStrengths(),
                    cdefIndices,
                    unitColumns,
                    unitRows,
                    chromaUnitWidth,
                    chromaUnitHeight
            );
        }

        if (lumaPlane == decodedPlanes.lumaPlane()
                && chromaUPlane == decodedPlanes.chromaUPlane()
                && chromaVPlane == decodedPlanes.chromaVPlane()) {
            return decodedPlanes;
        }
        return new DecodedPlanes(
                decodedPlanes.bitDepth(),
                decodedPlanes.pixelFormat(),
                decodedPlanes.codedWidth(),
                decodedPlanes.codedHeight(),
                decodedPlanes.renderWidth(),
                decodedPlanes.renderHeight(),
                lumaPlane,
                chromaUPlane,
                chromaVPlane
        );
    }

    /// Returns whether any CDEF strength can change visible samples.
    ///
    /// @param cdef the normalized frame-level CDEF state
    /// @param hasChroma whether the frame has chroma planes
    /// @return whether any CDEF strength can change visible samples
    private static boolean hasActiveStrengths(FrameHeader.CdefInfo cdef, boolean hasChroma) {
        return hasActiveStrength(cdef.yStrengths()) || (hasChroma && hasActiveStrength(cdef.uvStrengths()));
    }

    /// Returns whether any encoded strength contains a non-zero primary or secondary component.
    ///
    /// @param strengths the encoded CDEF strength table
    /// @return whether any encoded strength is non-zero
    private static boolean hasActiveStrength(int[] strengths) {
        for (int strength : strengths) {
            if (strength != 0) {
                return true;
            }
        }
        return false;
    }

    /// Builds the luma CDEF-unit index map from decoded partition-tree leaves.
    ///
    /// @param syntaxDecodeResult the decoded block syntax that carries CDEF indices
    /// @param cdef the normalized frame-level CDEF state
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    /// @return a row-major luma CDEF-unit index map
    private static int[] buildCdefIndexMap(
            FrameSyntaxDecodeResult syntaxDecodeResult,
            FrameHeader.CdefInfo cdef,
            int unitColumns,
            int unitRows
    ) {
        int defaultIndex = cdef.bits() == 0 ? 0 : -1;
        int[] cdefIndices = new int[unitColumns * unitRows];
        Arrays.fill(cdefIndices, defaultIndex);
        for (int tileIndex = 0; tileIndex < syntaxDecodeResult.tileCount(); tileIndex++) {
            for (TilePartitionTreeReader.Node root : syntaxDecodeResult.tileRoots(tileIndex)) {
                fillCdefIndexMap(root, cdefIndices, defaultIndex, unitColumns, unitRows);
            }
        }
        return cdefIndices;
    }

    /// Fills CDEF-unit indices covered by one partition-tree node.
    ///
    /// @param node the partition-tree node to visit
    /// @param cdefIndices the mutable row-major CDEF index map
    /// @param defaultIndex the default CDEF index when block syntax omitted it
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    private static void fillCdefIndexMap(
            TilePartitionTreeReader.Node node,
            int[] cdefIndices,
            int defaultIndex,
            int unitColumns,
            int unitRows
    ) {
        if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
            int blockCdefIndex = leafNode.header().cdefIndex();
            if (blockCdefIndex < 0) {
                blockCdefIndex = defaultIndex;
            }
            if (blockCdefIndex < 0) {
                return;
            }
            int startX = leafNode.header().position().x4() << 2;
            int startY = leafNode.header().position().y4() << 2;
            int endX = startX + leafNode.transformLayout().visibleWidthPixels();
            int endY = startY + leafNode.transformLayout().visibleHeightPixels();
            int startUnitX = clamp(startX / CDEF_UNIT_SIZE, 0, unitColumns - 1);
            int startUnitY = clamp(startY / CDEF_UNIT_SIZE, 0, unitRows - 1);
            int endUnitX = clamp((endX + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitColumns);
            int endUnitY = clamp((endY + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitRows);
            for (int unitY = startUnitY; unitY < endUnitY; unitY++) {
                for (int unitX = startUnitX; unitX < endUnitX; unitX++) {
                    cdefIndices[unitY * unitColumns + unitX] = blockCdefIndex;
                }
            }
            return;
        }

        TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
        for (TilePartitionTreeReader.Node child : partitionNode.children()) {
            fillCdefIndexMap(child, cdefIndices, defaultIndex, unitColumns, unitRows);
        }
    }

    /// Applies CDEF to one plane.
    ///
    /// @param plane the source plane to filter
    /// @param bitDepth the decoded bit depth
    /// @param damping the frame-level CDEF damping value
    /// @param strengths the encoded strength table for the plane
    /// @param cdefIndices the row-major luma CDEF-unit index map
    /// @param unitColumns the luma CDEF-unit column count
    /// @param unitRows the luma CDEF-unit row count
    /// @param unitWidth the plane-local CDEF-unit width
    /// @param unitHeight the plane-local CDEF-unit height
    /// @return the filtered plane, or the original plane when all selected strengths are inactive
    private static DecodedPlane applyPlane(
            DecodedPlane plane,
            int bitDepth,
            int damping,
            int[] strengths,
            int[] cdefIndices,
            int unitColumns,
            int unitRows,
            int unitWidth,
            int unitHeight
    ) {
        if (!hasActiveStrength(strengths)) {
            return plane;
        }
        short[] sourceSamples = plane.samples();
        short[] outputSamples = Arrays.copyOf(sourceSamples, sourceSamples.length);
        int maximumSample = (1 << bitDepth) - 1;
        boolean changed = false;
        for (int unitY = 0; unitY < unitRows; unitY++) {
            int planeStartY = unitY * unitHeight;
            if (planeStartY >= plane.height()) {
                continue;
            }
            int planeEndY = Math.min(plane.height(), planeStartY + unitHeight);
            for (int unitX = 0; unitX < unitColumns; unitX++) {
                int planeStartX = unitX * unitWidth;
                if (planeStartX >= plane.width()) {
                    continue;
                }
                int strength = strengthForIndex(strengths, cdefIndices[unitY * unitColumns + unitX]);
                CdefStrength decodedStrength = decodeStrength(strength);
                if (!decodedStrength.active()) {
                    continue;
                }
                filterUnit(
                        plane,
                        sourceSamples,
                        outputSamples,
                        planeStartX,
                        planeStartY,
                        Math.min(plane.width(), planeStartX + unitWidth),
                        planeEndY,
                        damping,
                        decodedStrength,
                        maximumSample
                );
                changed = true;
            }
        }
        return changed ? new DecodedPlane(plane.width(), plane.height(), plane.stride(), outputSamples) : plane;
    }

    /// Returns one encoded CDEF strength selected by the decoded block CDEF index.
    ///
    /// @param strengths the encoded strength table
    /// @param cdefIndex the decoded block CDEF index
    /// @return the selected encoded CDEF strength
    private static int strengthForIndex(int[] strengths, int cdefIndex) {
        if (cdefIndex < 0) {
            throw new IllegalStateException("Active AV1 CDEF filtering requires a decoded CDEF index for every filtered unit");
        }
        if (cdefIndex >= strengths.length) {
            throw new IllegalStateException("Decoded AV1 CDEF index exceeds the frame strength table: " + cdefIndex);
        }
        return strengths[cdefIndex];
    }

    /// Decodes one packed CDEF strength into primary and secondary components.
    ///
    /// @param strength the packed AV1 CDEF strength
    /// @return the decoded primary and secondary CDEF strengths
    private static CdefStrength decodeStrength(int strength) {
        int primary = strength >> 2;
        int secondary = strength & 3;
        if (secondary == 3) {
            secondary++;
        }
        return new CdefStrength(primary, secondary);
    }

    /// Applies CDEF to one rectangular unit.
    ///
    /// @param plane the source plane metadata
    /// @param sourceSamples the immutable source samples for this CDEF pass
    /// @param outputSamples the mutable output samples
    /// @param startX the inclusive unit start X coordinate
    /// @param startY the inclusive unit start Y coordinate
    /// @param endX the exclusive unit end X coordinate
    /// @param endY the exclusive unit end Y coordinate
    /// @param damping the frame-level CDEF damping value
    /// @param strength the decoded primary and secondary CDEF strengths
    /// @param maximumSample the maximum legal output sample value
    private static void filterUnit(
            DecodedPlane plane,
            short[] sourceSamples,
            short[] outputSamples,
            int startX,
            int startY,
            int endX,
            int endY,
            int damping,
            CdefStrength strength,
            int maximumSample
    ) {
        int direction = detectDirection(plane, sourceSamples, startX, startY, endX, endY);
        int[] primaryDirection = DIRECTIONS[direction];
        int[] secondaryDirection0 = DIRECTIONS[(direction + 2) & 7];
        int[] secondaryDirection1 = DIRECTIONS[(direction + 6) & 7];
        int[] primaryTaps = PRIMARY_TAPS[strength.primary() & 1];
        int primaryDamping = Math.max(0, damping - floorLog2(Math.max(1, strength.primary())));
        int secondaryDamping = Math.max(0, damping - floorLog2(Math.max(1, strength.secondary())));

        for (int y = startY; y < endY; y++) {
            int rowOffset = y * plane.stride();
            for (int x = startX; x < endX; x++) {
                int center = sourceSamples[rowOffset + x] & 0xFFFF;
                int sum = 0;
                if (strength.primary() > 0) {
                    sum += directionalContribution(
                            plane,
                            sourceSamples,
                            x,
                            y,
                            center,
                            primaryDirection,
                            primaryTaps,
                            strength.primary(),
                            primaryDamping
                    );
                }
                if (strength.secondary() > 0) {
                    sum += directionalContribution(
                            plane,
                            sourceSamples,
                            x,
                            y,
                            center,
                            secondaryDirection0,
                            SECONDARY_TAPS,
                            strength.secondary(),
                            secondaryDamping
                    );
                    sum += directionalContribution(
                            plane,
                            sourceSamples,
                            x,
                            y,
                            center,
                            secondaryDirection1,
                            SECONDARY_TAPS,
                            strength.secondary(),
                            secondaryDamping
                    );
                }
                int filtered = center + ((sum - (sum < 0 ? 1 : 0) + 8) >> 4);
                outputSamples[rowOffset + x] = (short) clamp(filtered, 0, maximumSample);
            }
        }
    }

    /// Returns one directional CDEF contribution for a pair of symmetric taps.
    ///
    /// @param plane the source plane metadata
    /// @param sourceSamples the immutable source samples for this CDEF pass
    /// @param x the current sample X coordinate
    /// @param y the current sample Y coordinate
    /// @param center the current center sample
    /// @param direction the unit step direction
    /// @param taps the distance-one and distance-two taps
    /// @param strength the active CDEF strength
    /// @param damping the strength-adjusted damping value
    /// @return one directional CDEF contribution
    private static int directionalContribution(
            DecodedPlane plane,
            short[] sourceSamples,
            int x,
            int y,
            int center,
            int[] direction,
            int[] taps,
            int strength,
            int damping
    ) {
        int contribution = 0;
        for (int distance = 1; distance <= taps.length; distance++) {
            int tap = taps[distance - 1];
            int positive = sampleClamped(
                    plane,
                    sourceSamples,
                    x + direction[0] * distance,
                    y + direction[1] * distance
            );
            int negative = sampleClamped(
                    plane,
                    sourceSamples,
                    x - direction[0] * distance,
                    y - direction[1] * distance
            );
            contribution += tap * constrain(positive - center, strength, damping);
            contribution += tap * constrain(negative - center, strength, damping);
        }
        return contribution;
    }

    /// Detects a stable dominant direction for one CDEF unit.
    ///
    /// @param plane the source plane metadata
    /// @param sourceSamples the immutable source samples for this CDEF pass
    /// @param startX the inclusive unit start X coordinate
    /// @param startY the inclusive unit start Y coordinate
    /// @param endX the exclusive unit end X coordinate
    /// @param endY the exclusive unit end Y coordinate
    /// @return the selected direction index in `0..7`
    private static int detectDirection(
            DecodedPlane plane,
            short[] sourceSamples,
            int startX,
            int startY,
            int endX,
            int endY
    ) {
        int bestDirection = 0;
        long bestScore = Long.MAX_VALUE;
        for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++) {
            int[] direction = DIRECTIONS[directionIndex];
            long score = 0;
            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    int center = sourceSamples[y * plane.stride() + x] & 0xFFFF;
                    score += Math.abs(center - sampleClamped(plane, sourceSamples, x + direction[0], y + direction[1]));
                    score += Math.abs(center - sampleClamped(plane, sourceSamples, x - direction[0], y - direction[1]));
                }
            }
            if (score < bestScore) {
                bestScore = score;
                bestDirection = directionIndex;
            }
        }
        return bestDirection;
    }

    /// Applies the AV1 CDEF constraint function to one neighbor delta.
    ///
    /// @param difference the signed neighbor-center delta
    /// @param threshold the active CDEF threshold
    /// @param damping the strength-adjusted CDEF damping value
    /// @return the constrained signed delta
    private static int constrain(int difference, int threshold, int damping) {
        if (threshold == 0) {
            return 0;
        }
        int magnitude = Math.abs(difference);
        int constrainedMagnitude = Math.min(magnitude, Math.max(0, threshold - (magnitude >> damping)));
        return difference < 0 ? -constrainedMagnitude : constrainedMagnitude;
    }

    /// Returns one source sample with edge extension.
    ///
    /// @param plane the source plane metadata
    /// @param sourceSamples the immutable source samples for this CDEF pass
    /// @param x the unclamped X coordinate
    /// @param y the unclamped Y coordinate
    /// @return one edge-extended source sample
    private static int sampleClamped(DecodedPlane plane, short[] sourceSamples, int x, int y) {
        int clampedX = clamp(x, 0, plane.width() - 1);
        int clampedY = clamp(y, 0, plane.height() - 1);
        return sourceSamples[clampedY * plane.stride() + clampedX] & 0xFFFF;
    }

    /// Returns the number of luma CDEF units needed to cover one plane extent.
    ///
    /// @param extent the luma plane extent in samples
    /// @return the number of luma CDEF units
    private static int cdefUnitCount(int extent) {
        return (extent + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE;
    }

    /// Returns the horizontal chroma subsampling shift for one pixel format.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @return the horizontal chroma subsampling shift
    private static int chromaSubsamplingX(PixelFormat pixelFormat) {
        return switch (Objects.requireNonNull(pixelFormat, "pixelFormat")) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the vertical chroma subsampling shift for one pixel format.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @return the vertical chroma subsampling shift
    private static int chromaSubsamplingY(PixelFormat pixelFormat) {
        return switch (Objects.requireNonNull(pixelFormat, "pixelFormat")) {
            case I400, I422, I444 -> 0;
            case I420 -> 1;
        };
    }

    /// Returns the base-2 floor logarithm of a positive integer.
    ///
    /// @param value the positive integer
    /// @return the base-2 floor logarithm
    private static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    /// Clamps one signed value into one inclusive integer range.
    ///
    /// @param value the candidate value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clamped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /// Decoded primary and secondary CDEF strengths.
    @NotNullByDefault
    private static final class CdefStrength {
        /// The decoded primary CDEF strength.
        private final int primary;

        /// The decoded secondary CDEF strength.
        private final int secondary;

        /// Creates one decoded CDEF strength pair.
        ///
        /// @param primary the decoded primary CDEF strength
        /// @param secondary the decoded secondary CDEF strength
        private CdefStrength(int primary, int secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        /// Returns the decoded primary CDEF strength.
        ///
        /// @return the decoded primary CDEF strength
        private int primary() {
            return primary;
        }

        /// Returns the decoded secondary CDEF strength.
        ///
        /// @return the decoded secondary CDEF strength
        private int secondary() {
            return secondary;
        }

        /// Returns whether either strength component can change a sample.
        ///
        /// @return whether either strength component can change a sample
        private boolean active() {
            return primary != 0 || secondary != 0;
        }
    }
}
