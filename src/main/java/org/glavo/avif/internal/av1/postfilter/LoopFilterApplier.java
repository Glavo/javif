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

import org.glavo.avif.AvifPixelFormat;
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader;
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader;
import org.glavo.avif.internal.av1.model.BlockPosition;
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode;
import org.glavo.avif.internal.av1.model.TransformSize;
import org.glavo.avif.internal.av1.model.TransformUnit;
import org.glavo.avif.internal.av1.recon.DecodedPlane;
import org.glavo.avif.internal.av1.recon.DecodedPlanes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Applies the loop-filter stage of the AV1 postfilter pipeline.
///
/// Inactive loop filtering preserves samples exactly. Active loop filtering uses decoded block and
/// transform boundaries to run the deblocking sample filters before CDEF sees the frame.
@NotNullByDefault
public final class LoopFilterApplier {
    /// The number of luma samples represented by one AV1 mode-info unit.
    private static final int MI_SIZE = 4;

    /// The maximum AV1 loop-filter level.
    private static final int MAX_LOOP_FILTER_LEVEL = 63;

    /// Applies loop filtering to one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param loopFilter the normalized frame-level loop-filter state
    /// @return the post-loop-filter planes
    public DecodedPlanes apply(DecodedPlanes decodedPlanes, FrameHeader.LoopFilterInfo loopFilter) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader.LoopFilterInfo checkedLoopFilter = Objects.requireNonNull(loopFilter, "loopFilter");
        if (hasActiveLevels(checkedLoopFilter, checkedDecodedPlanes.hasChroma())) {
            throw new IllegalStateException("Active AV1 loop filtering requires decoded block edge state");
        }
        return checkedDecodedPlanes;
    }

    /// Applies loop filtering to one reconstructed frame using decoded block syntax.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block and transform edges
    /// @return the post-loop-filter planes
    public DecodedPlanes apply(
            DecodedPlanes decodedPlanes,
            FrameHeader frameHeader,
            @Nullable FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedPlanes checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        FrameHeader.LoopFilterInfo loopFilter = checkedFrameHeader.loopFilter();
        if (!hasActiveLevels(loopFilter, checkedDecodedPlanes.hasChroma())) {
            return checkedDecodedPlanes;
        }
        if (syntaxDecodeResult == null) {
            throw new IllegalStateException("Active AV1 loop filtering requires decoded block edge state");
        }

        LoopFilterBlockMap blockMap = LoopFilterBlockMap.create(syntaxDecodeResult, checkedDecodedPlanes);
        PlaneBuffer luma = PlaneBuffer.create(checkedDecodedPlanes.lumaPlane(), checkedDecodedPlanes.bitDepth());
        applyPlane(luma, checkedFrameHeader, checkedDecodedPlanes.pixelFormat(), blockMap, 0);

        @Nullable PlaneBuffer chromaU = null;
        @Nullable PlaneBuffer chromaV = null;
        if (checkedDecodedPlanes.hasChroma()) {
            chromaU = PlaneBuffer.create(
                    Objects.requireNonNull(checkedDecodedPlanes.chromaUPlane(), "decodedPlanes.chromaUPlane()"),
                    checkedDecodedPlanes.bitDepth()
            );
            chromaV = PlaneBuffer.create(
                    Objects.requireNonNull(checkedDecodedPlanes.chromaVPlane(), "decodedPlanes.chromaVPlane()"),
                    checkedDecodedPlanes.bitDepth()
            );
            applyPlane(chromaU, checkedFrameHeader, checkedDecodedPlanes.pixelFormat(), blockMap, 1);
            applyPlane(chromaV, checkedFrameHeader, checkedDecodedPlanes.pixelFormat(), blockMap, 2);
        }

        return new DecodedPlanes(
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.pixelFormat(),
                checkedDecodedPlanes.codedWidth(),
                checkedDecodedPlanes.codedHeight(),
                checkedDecodedPlanes.renderWidth(),
                checkedDecodedPlanes.renderHeight(),
                luma.toDecodedPlane(),
                chromaU != null ? chromaU.toDecodedPlane() : null,
                chromaV != null ? chromaV.toDecodedPlane() : null
        );
    }

    /// Applies both loop-filter passes to one plane.
    ///
    /// @param plane the mutable plane buffer
    /// @param frameHeader the normalized frame header that owns the plane
    /// @param pixelFormat the decoded pixel format
    /// @param blockMap the decoded block and transform map
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    private static void applyPlane(
            PlaneBuffer plane,
            FrameHeader frameHeader,
            AvifPixelFormat pixelFormat,
            LoopFilterBlockMap blockMap,
            int planeIndex
    ) {
        if (planeFilterLevel(frameHeader.loopFilter(), planeIndex, 0) == 0
                && planeFilterLevel(frameHeader.loopFilter(), planeIndex, 1) == 0) {
            return;
        }
        applyPass(plane, frameHeader, pixelFormat, blockMap, planeIndex, 0);
        applyPass(plane, frameHeader, pixelFormat, blockMap, planeIndex, 1);
    }

    /// Applies one vertical or horizontal loop-filter pass to one plane.
    ///
    /// @param plane the mutable plane buffer
    /// @param frameHeader the normalized frame header that owns the plane
    /// @param pixelFormat the decoded pixel format
    /// @param blockMap the decoded block and transform map
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    private static void applyPass(
            PlaneBuffer plane,
            FrameHeader frameHeader,
            AvifPixelFormat pixelFormat,
            LoopFilterBlockMap blockMap,
            int planeIndex,
            int pass
    ) {
        int subX = planeIndex == 0 ? 0 : chromaSubsamplingX(pixelFormat);
        int subY = planeIndex == 0 ? 0 : chromaSubsamplingY(pixelFormat);
        int rowStep = planeIndex == 0 ? Math.max(1, 1 << subY) : 1;
        int colStep = planeIndex == 0 ? Math.max(1, 1 << subX) : 1;
        if (pass == 0) {
            for (int row4 = 0; row4 < blockMap.height4(); row4 += rowStep) {
                for (int col4 = colStep; col4 < blockMap.width4(); col4 += colStep) {
                    filterEdge(plane, frameHeader, blockMap, planeIndex, pass, row4, col4, subX, subY);
                }
            }
        } else {
            for (int row4 = rowStep; row4 < blockMap.height4(); row4 += rowStep) {
                for (int col4 = 0; col4 < blockMap.width4(); col4 += colStep) {
                    filterEdge(plane, frameHeader, blockMap, planeIndex, pass, row4, col4, subX, subY);
                }
            }
        }
    }

    /// Applies loop filtering along one 4x4-grid edge when the decoded syntax permits it.
    ///
    /// @param plane the mutable plane buffer
    /// @param frameHeader the normalized frame header that owns the plane
    /// @param blockMap the decoded block and transform map
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @param row4 the luma 4x4 row coordinate of the edge
    /// @param col4 the luma 4x4 column coordinate of the edge
    /// @param subX the plane horizontal subsampling shift
    /// @param subY the plane vertical subsampling shift
    private static void filterEdge(
            PlaneBuffer plane,
            FrameHeader frameHeader,
            LoopFilterBlockMap blockMap,
            int planeIndex,
            int pass,
            int row4,
            int col4,
            int subX,
            int subY
    ) {
        int prevCol4 = col4 - (pass == 0 ? Math.max(1, 1 << subX) : 0);
        int prevRow4 = row4 - (pass == 1 ? Math.max(1, 1 << subY) : 0);
        @Nullable LoopFilterCell current = blockMap.cellAt(col4, row4);
        @Nullable LoopFilterCell previous = blockMap.cellAt(prevCol4, prevRow4);
        if (current == null || previous == null) {
            return;
        }

        boolean blockEdge = current.header() != previous.header();
        boolean transformEdge = current.transformCell(planeIndex).unit() != previous.transformCell(planeIndex).unit();
        if (!blockEdge && !transformEdge && current.header().skip() && previous.header().skip()
                && !current.header().intra() && !previous.header().intra()) {
            return;
        }

        int x = (col4 * MI_SIZE) >> subX;
        int y = (row4 * MI_SIZE) >> subY;
        if (pass == 0) {
            if (x <= 0 || x >= plane.width() || y < 0 || y >= plane.height()) {
                return;
            }
        } else {
            if (y <= 0 || y >= plane.height() || x < 0 || x >= plane.width()) {
                return;
            }
        }

        int dx = pass == 0 ? 1 : 0;
        int dy = pass == 0 ? 0 : 1;
        int filterSize = filterSize(current.transformCell(planeIndex).size(), previous.transformCell(planeIndex).size(), planeIndex);
        int level = filterLevel(frameHeader, current.header(), planeIndex, pass);
        FilterStrength strength = filterStrength(level, frameHeader.loopFilter().sharpness());
        if (strength.level() == 0) {
            strength = filterStrength(filterLevel(frameHeader, previous.header(), planeIndex, pass), frameHeader.loopFilter().sharpness());
        }
        if (strength.level() == 0) {
            return;
        }

        int samples = Math.min(MI_SIZE >> (pass == 0 ? subY : subX), pass == 0 ? plane.height() - y : plane.width() - x);
        for (int i = 0; i < samples; i++) {
            int sampleX = x + (pass == 0 ? 0 : i);
            int sampleY = y + (pass == 0 ? i : 0);
            applySampleFilter(plane, sampleX, sampleY, dx, dy, filterSize, strength);
        }
    }

    /// Applies one sample filter at a block edge.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param filterSize the selected maximum filter size in samples
    /// @param strength the derived filter strength parameters
    private static void applySampleFilter(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int filterSize,
            FilterStrength strength
    ) {
        int boundedFilterSize = boundedFilterSize(plane, x, y, dx, dy, filterSize);
        if (boundedFilterSize < 4) {
            return;
        }
        FilterMask mask = filterMask(plane, x, y, dx, dy, boundedFilterSize, strength);
        if (!mask.filter()) {
            return;
        }
        if (boundedFilterSize == 4 || !mask.flat()) {
            narrowFilter(plane, x, y, dx, dy, mask.highEdgeVariance());
        } else if (boundedFilterSize == 8 || !mask.flat2()) {
            wideFilter(plane, x, y, dx, dy, 3);
        } else {
            wideFilter(plane, x, y, dx, dy, 4);
        }
    }

    /// Returns a filter size that has all samples required around one boundary.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param requestedFilterSize the requested maximum filter size
    /// @return a filter size that has all samples required around one boundary
    private static int boundedFilterSize(PlaneBuffer plane, int x, int y, int dx, int dy, int requestedFilterSize) {
        int size = requestedFilterSize;
        while (size >= 4) {
            int required = size == 16 ? 7 : 4;
            if (hasSamplesAroundEdge(plane, x, y, dx, dy, required)) {
                return size;
            }
            size >>= 1;
        }
        return 0;
    }

    /// Returns whether all samples required by one filter are inside the plane.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param requiredSamples the number of samples required on each side of the edge
    /// @return whether all samples required by one filter are inside the plane
    private static boolean hasSamplesAroundEdge(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int requiredSamples
    ) {
        return plane.contains(x - dx * requiredSamples, y - dy * requiredSamples)
                && plane.contains(x + dx * (requiredSamples - 1), y + dy * (requiredSamples - 1));
    }

    /// Computes the filter masks for one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param filterSize the selected maximum filter size
    /// @param strength the derived filter strength parameters
    /// @return the filter masks for one boundary sample
    private static FilterMask filterMask(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            int filterSize,
            FilterStrength strength
    ) {
        int bitDepthShift = plane.bitDepth() - 8;
        int thresholdBd = 1 << bitDepthShift;
        int limitBd = strength.limit() << bitDepthShift;
        int blimitBd = strength.blimit() << bitDepthShift;
        int threshBd = strength.threshold() << bitDepthShift;
        int p0 = sampleRelative(plane, x, y, dx, dy, -1);
        int p1 = sampleRelative(plane, x, y, dx, dy, -2);
        int p2 = sampleRelative(plane, x, y, dx, dy, -3);
        int p3 = sampleRelative(plane, x, y, dx, dy, -4);
        int q0 = sampleRelative(plane, x, y, dx, dy, 0);
        int q1 = sampleRelative(plane, x, y, dx, dy, 1);
        int q2 = sampleRelative(plane, x, y, dx, dy, 2);
        int q3 = sampleRelative(plane, x, y, dx, dy, 3);

        boolean highEdgeVariance = Math.abs(p1 - p0) > threshBd || Math.abs(q1 - q0) > threshBd;
        boolean filtered = Math.abs(p3 - p2) <= limitBd
                && Math.abs(p2 - p1) <= limitBd
                && Math.abs(p1 - p0) <= limitBd
                && Math.abs(q1 - q0) <= limitBd
                && Math.abs(q2 - q1) <= limitBd
                && Math.abs(q3 - q2) <= limitBd
                && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= blimitBd;
        boolean flat = false;
        boolean flat2 = false;
        if (filterSize >= 8) {
            flat = Math.abs(p1 - p0) <= thresholdBd
                    && Math.abs(q1 - q0) <= thresholdBd
                    && Math.abs(p2 - p0) <= thresholdBd
                    && Math.abs(q2 - q0) <= thresholdBd
                    && Math.abs(p3 - p0) <= thresholdBd
                    && Math.abs(q3 - q0) <= thresholdBd;
        }
        if (filterSize >= 16 && flat) {
            flat2 = Math.abs(sampleRelative(plane, x, y, dx, dy, -7) - p0) <= thresholdBd
                    && Math.abs(sampleRelative(plane, x, y, dx, dy, 6) - q0) <= thresholdBd
                    && Math.abs(sampleRelative(plane, x, y, dx, dy, -6) - p0) <= thresholdBd
                    && Math.abs(sampleRelative(plane, x, y, dx, dy, 5) - q0) <= thresholdBd
                    && Math.abs(sampleRelative(plane, x, y, dx, dy, -5) - p0) <= thresholdBd
                    && Math.abs(sampleRelative(plane, x, y, dx, dy, 4) - q0) <= thresholdBd;
        }
        return new FilterMask(highEdgeVariance, filtered, flat, flat2);
    }

    /// Applies the AV1 narrow loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param highEdgeVariance whether high edge variance was detected
    private static void narrowFilter(
            PlaneBuffer plane,
            int x,
            int y,
            int dx,
            int dy,
            boolean highEdgeVariance
    ) {
        int offset = 0x80 << (plane.bitDepth() - 8);
        int qs0 = sampleRelative(plane, x, y, dx, dy, 0) - offset;
        int qs1 = sampleRelative(plane, x, y, dx, dy, 1) - offset;
        int ps0 = sampleRelative(plane, x, y, dx, dy, -1) - offset;
        int ps1 = sampleRelative(plane, x, y, dx, dy, -2) - offset;
        int filter = highEdgeVariance ? filter4Clamp(ps1 - qs1, plane.bitDepth()) : 0;
        filter = filter4Clamp(filter + 3 * (qs0 - ps0), plane.bitDepth());
        int filter1 = filter4Clamp(filter + 4, plane.bitDepth()) >> 3;
        int filter2 = filter4Clamp(filter + 3, plane.bitDepth()) >> 3;
        setSampleRelative(plane, x, y, dx, dy, 0, filter4Clamp(qs0 - filter1, plane.bitDepth()) + offset);
        setSampleRelative(plane, x, y, dx, dy, -1, filter4Clamp(ps0 + filter2, plane.bitDepth()) + offset);
        if (!highEdgeVariance) {
            int secondaryFilter = round2(filter1, 1);
            setSampleRelative(plane, x, y, dx, dy, 1, filter4Clamp(qs1 - secondaryFilter, plane.bitDepth()) + offset);
            setSampleRelative(plane, x, y, dx, dy, -2, filter4Clamp(ps1 + secondaryFilter, plane.bitDepth()) + offset);
        }
    }

    /// Applies the AV1 wide loop filter to one boundary sample.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param log2Size the base-2 logarithm of the filter tap normalization size
    private static void wideFilter(PlaneBuffer plane, int x, int y, int dx, int dy, int log2Size) {
        int n = log2Size == 4 ? 6 : 3;
        int n2 = log2Size == 3 ? 0 : 1;
        int[] filtered = new int[n * 2];
        for (int i = -n; i < n; i++) {
            int sum = 0;
            for (int j = -n; j <= n; j++) {
                int relative = clamp(i + j, -(n + 1), n);
                int tap = Math.abs(j) <= n2 ? 2 : 1;
                sum += sampleRelative(plane, x, y, dx, dy, relative) * tap;
            }
            filtered[i + n] = round2(sum, log2Size);
        }
        for (int i = -n; i < n; i++) {
            setSampleRelative(plane, x, y, dx, dy, i, filtered[i + n]);
        }
    }

    /// Returns the loop-filter level for one block, plane, and pass.
    ///
    /// @param frameHeader the normalized frame header that owns the block
    /// @param header the decoded block header
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @return the loop-filter level for one block, plane, and pass
    private static int filterLevel(
            FrameHeader frameHeader,
            TileBlockHeaderReader.BlockHeader header,
            int planeIndex,
            int pass
    ) {
        int componentIndex = planeIndex == 0 ? pass : planeIndex + 1;
        int baseLevel = planeFilterLevel(frameHeader.loopFilter(), planeIndex, pass);
        int[] deltaLfValues = header.deltaLfValues();
        int deltaLf = frameHeader.delta().deltaLfMulti()
                ? deltaLfValues[componentIndex]
                : deltaLfValues[0];
        int level = clamp(baseLevel + deltaLf, 0, MAX_LOOP_FILTER_LEVEL);
        if (frameHeader.segmentation().enabled()) {
            FrameHeader.SegmentData segment = frameHeader.segmentation().segment(header.segmentId());
            level = clamp(level + segmentLoopFilterDelta(segment, componentIndex), 0, MAX_LOOP_FILTER_LEVEL);
        }
        if (frameHeader.loopFilter().modeRefDeltaEnabled()) {
            int shift = level >> 5;
            int[] referenceDeltas = frameHeader.loopFilter().referenceDeltas();
            int[] modeDeltas = frameHeader.loopFilter().modeDeltas();
            int referenceIndex = header.intra() ? 0 : header.referenceFrame0() + 1;
            if (referenceIndex >= 0 && referenceIndex < referenceDeltas.length) {
                level += referenceDeltas[referenceIndex] << shift;
            }
            int modeIndex = loopFilterModeIndex(header);
            if (modeIndex >= 0 && modeIndex < modeDeltas.length) {
                level += modeDeltas[modeIndex] << shift;
            }
            level = clamp(level, 0, MAX_LOOP_FILTER_LEVEL);
        }
        return level;
    }

    /// Returns the component-specific frame loop-filter level.
    ///
    /// @param loopFilter the normalized loop-filter state
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
    /// @return the component-specific frame loop-filter level
    private static int planeFilterLevel(FrameHeader.LoopFilterInfo loopFilter, int planeIndex, int pass) {
        if (planeIndex == 0) {
            int[] levelY = loopFilter.levelY();
            return pass < levelY.length ? levelY[pass] : 0;
        }
        return planeIndex == 1 ? loopFilter.levelU() : loopFilter.levelV();
    }

    /// Returns the segment-level loop-filter delta for one component index.
    ///
    /// @param segment the segment feature data
    /// @param componentIndex the loop-filter component index
    /// @return the segment-level loop-filter delta for one component index
    private static int segmentLoopFilterDelta(FrameHeader.SegmentData segment, int componentIndex) {
        return switch (componentIndex) {
            case 0 -> segment.deltaLfYVertical();
            case 1 -> segment.deltaLfYHorizontal();
            case 2 -> segment.deltaLfU();
            case 3 -> segment.deltaLfV();
            default -> 0;
        };
    }

    /// Returns the two-entry loop-filter mode-delta index for one block.
    ///
    /// @param header the decoded block header
    /// @return the two-entry loop-filter mode-delta index for one block
    private static int loopFilterModeIndex(TileBlockHeaderReader.BlockHeader header) {
        if (header.intra() || header.useIntrabc()) {
            return 0;
        }
        @Nullable SingleInterPredictionMode singleMode = header.singleInterMode();
        if (singleMode == SingleInterPredictionMode.NEWMV) {
            return 1;
        }
        @Nullable CompoundInterPredictionMode compoundMode = header.compoundInterMode();
        if (compoundMode == null) {
            return 0;
        }
        return switch (compoundMode) {
            case NEARESTMV_NEARESTMV, NEARMV_NEARMV, GLOBALMV_GLOBALMV -> 0;
            case NEARESTMV_NEWMV, NEWMV_NEARESTMV, NEARMV_NEWMV, NEWMV_NEARMV, NEWMV_NEWMV -> 1;
        };
    }

    /// Derives AV1 loop-filter strength parameters from one filter level.
    ///
    /// @param level the effective loop-filter level
    /// @param sharpness the frame loop-filter sharpness
    /// @return AV1 loop-filter strength parameters
    private static FilterStrength filterStrength(int level, int sharpness) {
        if (level <= 0) {
            return new FilterStrength(0, 0, 0, 0);
        }
        int shift = sharpness > 4 ? 2 : (sharpness > 0 ? 1 : 0);
        int limit = level >> shift;
        if (sharpness > 0) {
            limit = clamp(limit, 1, 9 - sharpness);
        } else {
            limit = Math.max(1, limit);
        }
        return new FilterStrength(level, limit, 2 * (level + 2) + limit, level >> 4);
    }

    /// Returns the maximum filter size allowed by the transform sizes across one edge.
    ///
    /// @param txSize the current transform size
    /// @param previousTxSize the transform size on the other side of the edge
    /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
    /// @return the maximum filter size allowed by the transform sizes across one edge
    private static int filterSize(TransformSize txSize, TransformSize previousTxSize, int planeIndex) {
        int baseSize = Math.min(
                Math.min(txSize.widthPixels(), txSize.heightPixels()),
                Math.min(previousTxSize.widthPixels(), previousTxSize.heightPixels())
        );
        return Math.min(planeIndex == 0 ? 16 : 8, baseSize);
    }

    /// Returns whether any loop-filter level can affect the decoded frame.
    ///
    /// @param loopFilter the normalized loop-filter state
    /// @param hasChroma whether the decoded frame has chroma planes
    /// @return whether any loop-filter level can affect the decoded frame
    private static boolean hasActiveLevels(FrameHeader.LoopFilterInfo loopFilter, boolean hasChroma) {
        int[] levelY = loopFilter.levelY();
        return (levelY.length > 0 && levelY[0] != 0)
                || (levelY.length > 1 && levelY[1] != 0)
                || (hasChroma && (loopFilter.levelU() != 0 || loopFilter.levelV() != 0));
    }

    /// Returns one sample at a boundary-relative offset.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param offset the signed boundary-relative sample offset
    /// @return one sample at a boundary-relative offset
    private static int sampleRelative(PlaneBuffer plane, int x, int y, int dx, int dy, int offset) {
        return plane.sample(x + dx * offset, y + dy * offset);
    }

    /// Stores one sample at a boundary-relative offset.
    ///
    /// @param plane the mutable plane buffer
    /// @param x the first sample on the right or lower side of the boundary
    /// @param y the first sample on the right or lower side of the boundary
    /// @param dx the horizontal offset across the boundary
    /// @param dy the vertical offset across the boundary
    /// @param offset the signed boundary-relative sample offset
    /// @param value the replacement sample value
    private static void setSampleRelative(PlaneBuffer plane, int x, int y, int dx, int dy, int offset, int value) {
        plane.setSample(x + dx * offset, y + dy * offset, value);
    }

    /// Returns the chroma horizontal subsampling shift for one pixel format.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @return the chroma horizontal subsampling shift for one pixel format
    private static int chromaSubsamplingX(AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I444 -> 0;
            case I420, I422 -> 1;
        };
    }

    /// Returns the chroma vertical subsampling shift for one pixel format.
    ///
    /// @param pixelFormat the decoded pixel format
    /// @return the chroma vertical subsampling shift for one pixel format
    private static int chromaSubsamplingY(AvifPixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case I400, I422, I444 -> 0;
            case I420 -> 1;
        };
    }

    /// Clips one integer into inclusive bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clipped value
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Rounds one integer right shift by adding half the divisor.
    ///
    /// @param value the input value
    /// @param bits the number of low bits to discard
    /// @return the rounded shifted value
    private static int round2(int value, int bits) {
        return (value + (1 << (bits - 1))) >> bits;
    }

    /// Clips one signed narrow-filter intermediate to the decoded bit-depth range.
    ///
    /// @param value the input value
    /// @param bitDepth the decoded bit depth
    /// @return the clipped narrow-filter intermediate
    private static int filter4Clamp(int value, int bitDepth) {
        int limit = 1 << (bitDepth - 1);
        return clamp(value, -limit, limit - 1);
    }

    /// Mutable plane storage used by loop filtering.
    @NotNullByDefault
    private static final class PlaneBuffer {
        /// The plane width in samples.
        private final int width;

        /// The plane height in samples.
        private final int height;

        /// The decoded sample bit depth.
        private final int bitDepth;

        /// The maximum legal sample value for this bit depth.
        private final int maxSampleValue;

        /// The mutable sample storage in row-major order.
        private final short[] samples;

        /// Creates one mutable plane buffer.
        ///
        /// @param width the plane width in samples
        /// @param height the plane height in samples
        /// @param bitDepth the decoded sample bit depth
        /// @param samples the mutable sample storage in row-major order
        private PlaneBuffer(int width, int height, int bitDepth, short[] samples) {
            this.width = width;
            this.height = height;
            this.bitDepth = bitDepth;
            this.maxSampleValue = (1 << bitDepth) - 1;
            this.samples = Objects.requireNonNull(samples, "samples");
        }

        /// Creates a mutable copy of one decoded plane.
        ///
        /// @param plane the immutable decoded plane
        /// @param bitDepth the decoded sample bit depth
        /// @return a mutable copy of one decoded plane
        public static PlaneBuffer create(DecodedPlane plane, int bitDepth) {
            DecodedPlane checkedPlane = Objects.requireNonNull(plane, "plane");
            return new PlaneBuffer(checkedPlane.width(), checkedPlane.height(), bitDepth, checkedPlane.samples());
        }

        /// Returns the plane width in samples.
        ///
        /// @return the plane width in samples
        public int width() {
            return width;
        }

        /// Returns the plane height in samples.
        ///
        /// @return the plane height in samples
        public int height() {
            return height;
        }

        /// Returns the decoded sample bit depth.
        ///
        /// @return the decoded sample bit depth
        public int bitDepth() {
            return bitDepth;
        }

        /// Returns whether one sample coordinate is inside this plane.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return whether one sample coordinate is inside this plane
        public boolean contains(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        /// Returns one mutable sample.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @return one mutable sample
        public int sample(int x, int y) {
            return samples[y * width + x] & 0xFFFF;
        }

        /// Stores one sample after clipping it to this plane bit depth.
        ///
        /// @param x the sample X coordinate
        /// @param y the sample Y coordinate
        /// @param value the replacement sample value
        public void setSample(int x, int y, int value) {
            samples[y * width + x] = (short) clamp(value, 0, maxSampleValue);
        }

        /// Returns one immutable decoded-plane snapshot from the current samples.
        ///
        /// @return one immutable decoded-plane snapshot from the current samples
        public DecodedPlane toDecodedPlane() {
            return new DecodedPlane(width, height, width, samples);
        }
    }

    /// Decoded block and transform lookup state indexed in luma 4x4 units.
    @NotNullByDefault
    private static final class LoopFilterBlockMap {
        /// The frame width rounded up to 4x4 units.
        private final int width4;

        /// The frame height rounded up to 4x4 units.
        private final int height4;

        /// The per-4x4 decoded block and transform cells.
        private final @Nullable LoopFilterCell[] cells;

        /// Creates one decoded block and transform lookup map.
        ///
        /// @param width4 the frame width rounded up to 4x4 units
        /// @param height4 the frame height rounded up to 4x4 units
        private LoopFilterBlockMap(int width4, int height4) {
            this.width4 = width4;
            this.height4 = height4;
            this.cells = new LoopFilterCell[width4 * height4];
        }

        /// Creates one decoded block and transform lookup map.
        ///
        /// @param syntaxDecodeResult the decoded frame syntax
        /// @param decodedPlanes the reconstructed planes to post-process
        /// @return one decoded block and transform lookup map
        public static LoopFilterBlockMap create(
                FrameSyntaxDecodeResult syntaxDecodeResult,
                DecodedPlanes decodedPlanes
        ) {
            LoopFilterBlockMap map = new LoopFilterBlockMap(
                    (decodedPlanes.codedWidth() + MI_SIZE - 1) / MI_SIZE,
                    (decodedPlanes.codedHeight() + MI_SIZE - 1) / MI_SIZE
            );
            for (TilePartitionTreeReader.Node[] tileRoots : syntaxDecodeResult.tileRoots()) {
                for (TilePartitionTreeReader.Node root : tileRoots) {
                    map.addNode(root);
                }
            }
            return map;
        }

        /// Returns the frame width rounded up to 4x4 units.
        ///
        /// @return the frame width rounded up to 4x4 units
        public int width4() {
            return width4;
        }

        /// Returns the frame height rounded up to 4x4 units.
        ///
        /// @return the frame height rounded up to 4x4 units
        public int height4() {
            return height4;
        }

        /// Returns the cell at one luma 4x4 coordinate.
        ///
        /// @param x4 the luma 4x4 X coordinate
        /// @param y4 the luma 4x4 Y coordinate
        /// @return the cell at one luma 4x4 coordinate, or `null`
        public @Nullable LoopFilterCell cellAt(int x4, int y4) {
            if (x4 < 0 || y4 < 0 || x4 >= width4 || y4 >= height4) {
                return null;
            }
            return cells[y4 * width4 + x4];
        }

        /// Adds one partition node and all descendant leaves to this map.
        ///
        /// @param node the decoded partition node
        private void addNode(TilePartitionTreeReader.Node node) {
            if (node instanceof TilePartitionTreeReader.LeafNode leafNode) {
                addLeaf(leafNode);
                return;
            }
            TilePartitionTreeReader.PartitionNode partitionNode = (TilePartitionTreeReader.PartitionNode) node;
            for (TilePartitionTreeReader.Node child : partitionNode.children()) {
                addNode(child);
            }
        }

        /// Adds one leaf to every 4x4 cell that it covers.
        ///
        /// @param leafNode the decoded partition leaf
        private void addLeaf(TilePartitionTreeReader.LeafNode leafNode) {
            TileBlockHeaderReader.BlockHeader header = leafNode.header();
            int startX4 = Math.max(0, header.position().x4());
            int startY4 = Math.max(0, header.position().y4());
            int endX4 = Math.min(width4, startX4 + leafNode.transformLayout().visibleWidth4());
            int endY4 = Math.min(height4, startY4 + leafNode.transformLayout().visibleHeight4());
            TransformCell[] lumaCells = transformCells(
                    startX4,
                    startY4,
                    endX4,
                    endY4,
                    leafNode.transformLayout().lumaUnits(),
                    leafNode.transformLayout().maxLumaTransformSize()
            );
            TransformCell[] chromaCells = transformCells(
                    startX4,
                    startY4,
                    endX4,
                    endY4,
                    leafNode.transformLayout().chromaUnits(),
                    leafNode.transformLayout().chromaTransformSize() != null
                            ? leafNode.transformLayout().chromaTransformSize()
                            : leafNode.transformLayout().maxLumaTransformSize()
            );
            for (int y4 = startY4; y4 < endY4; y4++) {
                for (int x4 = startX4; x4 < endX4; x4++) {
                    int localIndex = (y4 - startY4) * (endX4 - startX4) + (x4 - startX4);
                    cells[y4 * width4 + x4] = new LoopFilterCell(
                            header,
                            lumaCells[localIndex],
                            chromaCells[localIndex]
                    );
                }
            }
        }

        /// Builds transform-cell coverage for one leaf.
        ///
        /// @param startX4 the leaf start X in luma 4x4 units
        /// @param startY4 the leaf start Y in luma 4x4 units
        /// @param endX4 the exclusive leaf end X in luma 4x4 units
        /// @param endY4 the exclusive leaf end Y in luma 4x4 units
        /// @param transformUnits the decoded transform units
        /// @param fallbackSize the fallback transform size
        /// @return transform-cell coverage for one leaf
        private static TransformCell[] transformCells(
                int startX4,
                int startY4,
                int endX4,
                int endY4,
                TransformUnit[] transformUnits,
                TransformSize fallbackSize
        ) {
            int width = endX4 - startX4;
            int height = endY4 - startY4;
            TransformCell[] result = new TransformCell[width * height];
            if (transformUnits.length == 0) {
                TransformUnit fallbackUnit = new TransformUnit(new BlockPosition(startX4, startY4), fallbackSize);
                fillTransformCells(result, width, height, startX4, startY4, endX4, endY4, fallbackUnit);
                return result;
            }
            for (TransformUnit transformUnit : transformUnits) {
                fillTransformCells(result, width, height, startX4, startY4, endX4, endY4, transformUnit);
            }
            for (int i = 0; i < result.length; i++) {
                if (result[i] == null) {
                    result[i] = new TransformCell(transformUnits[0], transformUnits[0].size());
                }
            }
            return result;
        }

        /// Fills transform-cell coverage for one transform unit.
        ///
        /// @param result the mutable result coverage array
        /// @param width the local coverage width in 4x4 units
        /// @param height the local coverage height in 4x4 units
        /// @param startX4 the leaf start X in luma 4x4 units
        /// @param startY4 the leaf start Y in luma 4x4 units
        /// @param endX4 the exclusive leaf end X in luma 4x4 units
        /// @param endY4 the exclusive leaf end Y in luma 4x4 units
        /// @param transformUnit the transform unit to fill
        private static void fillTransformCells(
                TransformCell[] result,
                int width,
                int height,
                int startX4,
                int startY4,
                int endX4,
                int endY4,
                TransformUnit transformUnit
        ) {
            int unitStartX4 = Math.max(startX4, transformUnit.position().x4());
            int unitStartY4 = Math.max(startY4, transformUnit.position().y4());
            int unitEndX4 = Math.min(endX4, transformUnit.position().x4() + transformUnit.size().width4());
            int unitEndY4 = Math.min(endY4, transformUnit.position().y4() + transformUnit.size().height4());
            for (int y4 = unitStartY4; y4 < unitEndY4; y4++) {
                for (int x4 = unitStartX4; x4 < unitEndX4; x4++) {
                    result[(y4 - startY4) * width + (x4 - startX4)] =
                            new TransformCell(transformUnit, transformUnit.size());
                }
            }
        }
    }

    /// One decoded block and transform lookup cell.
    @NotNullByDefault
    private static final class LoopFilterCell {
        /// The decoded block header covering this cell.
        private final TileBlockHeaderReader.BlockHeader header;

        /// The decoded luma transform cell covering this cell.
        private final TransformCell lumaTransformCell;

        /// The decoded chroma transform cell covering this cell.
        private final TransformCell chromaTransformCell;

        /// Creates one decoded block and transform lookup cell.
        ///
        /// @param header the decoded block header covering this cell
        /// @param lumaTransformCell the decoded luma transform cell covering this cell
        /// @param chromaTransformCell the decoded chroma transform cell covering this cell
        private LoopFilterCell(
                TileBlockHeaderReader.BlockHeader header,
                TransformCell lumaTransformCell,
                TransformCell chromaTransformCell
        ) {
            this.header = Objects.requireNonNull(header, "header");
            this.lumaTransformCell = Objects.requireNonNull(lumaTransformCell, "lumaTransformCell");
            this.chromaTransformCell = Objects.requireNonNull(chromaTransformCell, "chromaTransformCell");
        }

        /// Returns the decoded block header covering this cell.
        ///
        /// @return the decoded block header covering this cell
        public TileBlockHeaderReader.BlockHeader header() {
            return header;
        }

        /// Returns the transform cell for one plane.
        ///
        /// @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
        /// @return the transform cell for one plane
        public TransformCell transformCell(int planeIndex) {
            return planeIndex == 0 ? lumaTransformCell : chromaTransformCell;
        }
    }

    /// One transform-unit lookup cell.
    @NotNullByDefault
    private static final class TransformCell {
        /// The transform unit covering this cell.
        private final TransformUnit unit;

        /// The transform size covering this cell.
        private final TransformSize size;

        /// Creates one transform-unit lookup cell.
        ///
        /// @param unit the transform unit covering this cell
        /// @param size the transform size covering this cell
        private TransformCell(TransformUnit unit, TransformSize size) {
            this.unit = Objects.requireNonNull(unit, "unit");
            this.size = Objects.requireNonNull(size, "size");
        }

        /// Returns the transform unit covering this cell.
        ///
        /// @return the transform unit covering this cell
        public TransformUnit unit() {
            return unit;
        }

        /// Returns the transform size covering this cell.
        ///
        /// @return the transform size covering this cell
        public TransformSize size() {
            return size;
        }
    }

    /// Derived AV1 loop-filter strength parameters.
    @NotNullByDefault
    private static final class FilterStrength {
        /// The effective loop-filter level.
        private final int level;

        /// The limit parameter used by the filter mask.
        private final int limit;

        /// The boundary limit parameter used by the filter mask.
        private final int blimit;

        /// The high-edge-variance threshold.
        private final int threshold;

        /// Creates derived AV1 loop-filter strength parameters.
        ///
        /// @param level the effective loop-filter level
        /// @param limit the limit parameter used by the filter mask
        /// @param blimit the boundary limit parameter used by the filter mask
        /// @param threshold the high-edge-variance threshold
        private FilterStrength(int level, int limit, int blimit, int threshold) {
            this.level = level;
            this.limit = limit;
            this.blimit = blimit;
            this.threshold = threshold;
        }

        /// Returns the effective loop-filter level.
        ///
        /// @return the effective loop-filter level
        public int level() {
            return level;
        }

        /// Returns the limit parameter used by the filter mask.
        ///
        /// @return the limit parameter used by the filter mask
        public int limit() {
            return limit;
        }

        /// Returns the boundary limit parameter used by the filter mask.
        ///
        /// @return the boundary limit parameter used by the filter mask
        public int blimit() {
            return blimit;
        }

        /// Returns the high-edge-variance threshold.
        ///
        /// @return the high-edge-variance threshold
        public int threshold() {
            return threshold;
        }
    }

    /// Filter masks derived around one boundary sample.
    @NotNullByDefault
    private static final class FilterMask {
        /// Whether high edge variance was detected.
        private final boolean highEdgeVariance;

        /// Whether filtering should be applied.
        private final boolean filter;

        /// Whether the immediate samples around the boundary are flat.
        private final boolean flat;

        /// Whether the wider samples around the boundary are flat.
        private final boolean flat2;

        /// Creates filter masks derived around one boundary sample.
        ///
        /// @param highEdgeVariance whether high edge variance was detected
        /// @param filter whether filtering should be applied
        /// @param flat whether the immediate samples around the boundary are flat
        /// @param flat2 whether the wider samples around the boundary are flat
        private FilterMask(boolean highEdgeVariance, boolean filter, boolean flat, boolean flat2) {
            this.highEdgeVariance = highEdgeVariance;
            this.filter = filter;
            this.flat = flat;
            this.flat2 = flat2;
        }

        /// Returns whether high edge variance was detected.
        ///
        /// @return whether high edge variance was detected
        public boolean highEdgeVariance() {
            return highEdgeVariance;
        }

        /// Returns whether filtering should be applied.
        ///
        /// @return whether filtering should be applied
        public boolean filter() {
            return filter;
        }

        /// Returns whether the immediate samples around the boundary are flat.
        ///
        /// @return whether the immediate samples around the boundary are flat
        public boolean flat() {
            return flat;
        }

        /// Returns whether the wider samples around the boundary are flat.
        ///
        /// @return whether the wider samples around the boundary are flat
        public boolean flat2() {
            return flat2;
        }
    }
}
