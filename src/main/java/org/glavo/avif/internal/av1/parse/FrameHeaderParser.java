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

import org.glavo.avif.decode.DecodeErrorCode;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.DecodeStage;
import org.glavo.avif.decode.FrameType;
import org.glavo.avif.decode.PixelFormat;
import org.glavo.avif.internal.av1.bitstream.BitReader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Parser for AV1 frame headers carried by standalone or combined frame OBUs.
@NotNullByDefault
public final class FrameHeaderParser {
    /// The AV1 `primary_ref_none` sentinel value.
    private static final int PRIMARY_REF_NONE = 7;
    /// The AV1 maximum tile column count.
    private static final int MAX_TILE_COLUMNS = 64;
    /// The AV1 maximum tile row count.
    private static final int MAX_TILE_ROWS = 64;
    /// The AV1 segment count.
    private static final int MAX_SEGMENTS = 8;
    /// The AV1 maximum CDEF strength count.
    private static final int MAX_CDEF_STRENGTHS = 8;
    /// The default loop filter reference deltas.
    private static final int[] DEFAULT_REFERENCE_DELTAS = new int[]{1, 0, 0, 0, -1, 0, -1, -1};
    /// The default loop filter mode deltas.
    private static final int[] DEFAULT_MODE_DELTAS = new int[]{0, 0};

    /// Parses a standalone AV1 frame header OBU.
    ///
    /// @param obu the standalone frame header OBU
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the OBU is truncated, unreadable, or invalid
    public FrameHeader parse(ObuPacket obu, SequenceHeader sequenceHeader, boolean strictStdCompliance) throws IOException {
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        if (obu.header().type() != ObuType.FRAME_HEADER) {
            throw new IllegalArgumentException("OBU type is not a standalone frame header: " + obu.header().type());
        }

        BitReader reader = new BitReader(obu.payload());
        FrameHeader header = parseFramePayload(reader, obu, sequenceHeader, strictStdCompliance);
        try {
            checkTrailingBits(reader, strictStdCompliance);
            return header;
        } catch (EOFException ex) {
            throw new DecodeException(
                    DecodeErrorCode.UNEXPECTED_EOF,
                    DecodeStage.FRAME_HEADER_PARSE,
                    "Unexpected end of frame header payload",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        } catch (DecodeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.FRAME_HEADER_PARSE,
                    ex.getMessage(),
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Parses a frame header payload from a `FRAME_HEADER` or `FRAME` OBU.
    ///
    /// @param reader the payload bit reader positioned at the frame header
    /// @param obu the source OBU packet
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the payload is truncated, unreadable, or invalid
    public FrameHeader parseFramePayload(
            BitReader reader,
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(obu, "obu");
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        ObuType type = obu.header().type();
        if (type != ObuType.FRAME_HEADER && type != ObuType.FRAME) {
            throw new IllegalArgumentException("OBU type does not carry a frame header payload: " + type);
        }

        try {
            return parse(reader, obu, sequenceHeader, strictStdCompliance);
        } catch (EOFException ex) {
            throw new DecodeException(
                    DecodeErrorCode.UNEXPECTED_EOF,
                    DecodeStage.FRAME_HEADER_PARSE,
                    "Unexpected end of frame header payload",
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        } catch (DecodeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.FRAME_HEADER_PARSE,
                    ex.getMessage(),
                    obu.streamOffset(),
                    obu.obuIndex(),
                    null,
                    ex
            );
        }
    }

    /// Parses the frame header payload.
    ///
    /// @param reader the payload bit reader
    /// @param obu the source OBU packet
    /// @param sequenceHeader the active sequence header
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @return the parsed frame header
    /// @throws IOException if the payload is truncated or invalid
    private FrameHeader parse(
            BitReader reader,
            ObuPacket obu,
            SequenceHeader sequenceHeader,
            boolean strictStdCompliance
    ) throws IOException {
        int temporalId = obu.header().temporalId();
        int spatialId = obu.header().spatialId();

        boolean showExistingFrame = false;
        int existingFrameIndex = 0;
        long frameId = 0;
        long framePresentationDelay = 0;
        FrameType frameType;
        boolean showFrame;
        boolean showableFrame;
        boolean errorResilientMode;

        if (!sequenceHeader.reducedStillPictureHeader()) {
            showExistingFrame = reader.readFlag();
        }
        if (showExistingFrame) {
            existingFrameIndex = readInt(reader, 3);
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                    && !sequenceHeader.timingInfo().equalPictureInterval()) {
                framePresentationDelay = readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength());
            }
            if (sequenceHeader.frameIdNumbersPresent()) {
                frameId = readLong(reader, sequenceHeader.frameIdBits());
            }

            return new FrameHeader(
                    temporalId,
                    spatialId,
                    true,
                    existingFrameIndex,
                    frameId,
                    framePresentationDelay,
                    FrameType.INTER,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    PRIMARY_REF_NONE,
                    0,
                    0,
                    new FrameHeader.FrameSize(0, 0, 0, 0, 0),
                    new FrameHeader.SuperResolutionInfo(false, 8),
                    false,
                    false,
                    new FrameHeader.TilingInfo(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, new int[0], new int[0], 0),
                    new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                    new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[MAX_SEGMENTS], new int[MAX_SEGMENTS]),
                    new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                    false,
                    new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, DEFAULT_REFERENCE_DELTAS, DEFAULT_MODE_DELTAS),
                    new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]),
                    new FrameHeader.RestorationInfo(
                            new FrameHeader.RestorationType[]{
                                    FrameHeader.RestorationType.NONE,
                                    FrameHeader.RestorationType.NONE,
                                    FrameHeader.RestorationType.NONE
                            },
                            0,
                            0
                    ),
                    FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                    false,
                    false
            );
        }

        if (sequenceHeader.reducedStillPictureHeader()) {
            frameType = FrameType.KEY;
            showFrame = true;
        } else {
            frameType = decodeFrameType(readInt(reader, 2));
            showFrame = reader.readFlag();
        }

        if (showFrame) {
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                    && !sequenceHeader.timingInfo().equalPictureInterval()) {
                framePresentationDelay = readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength());
            }
            showableFrame = frameType != FrameType.KEY;
        } else {
            showableFrame = reader.readFlag();
        }

        errorResilientMode = (frameType == FrameType.KEY && showFrame)
                || frameType == FrameType.SWITCH
                || sequenceHeader.reducedStillPictureHeader()
                || reader.readFlag();

        boolean disableCdfUpdate = reader.readFlag();
        boolean allowScreenContentTools = readFrameScreenContentTools(reader, sequenceHeader);
        boolean forceIntegerMotionVectors = false;
        if (allowScreenContentTools) {
            forceIntegerMotionVectors = readFrameForceIntegerMotionVectors(reader, sequenceHeader);
        }

        if (frameType == FrameType.KEY || frameType == FrameType.INTRA) {
            forceIntegerMotionVectors = true;
        }

        if (sequenceHeader.frameIdNumbersPresent()) {
            frameId = readLong(reader, sequenceHeader.frameIdBits());
        }

        boolean frameSizeOverride = false;
        if (!sequenceHeader.reducedStillPictureHeader()) {
            frameSizeOverride = frameType == FrameType.SWITCH || reader.readFlag();
        }

        int frameOffset = 0;
        if (sequenceHeader.features().orderHint()) {
            frameOffset = readInt(reader, sequenceHeader.features().orderHintBits());
        }

        int primaryRefFrame = (!errorResilientMode && isInterOrSwitch(frameType))
                ? readInt(reader, 3)
                : PRIMARY_REF_NONE;

        if (sequenceHeader.timingInfo().decoderModelInfoPresent()) {
            boolean bufferRemovalTimePresent = reader.readFlag();
            if (bufferRemovalTimePresent) {
                for (int i = 0; i < sequenceHeader.operatingPointCount(); i++) {
                    SequenceHeader.OperatingPoint operatingPoint = sequenceHeader.operatingPoint(i);
                    if (operatingPoint.decoderModelParamPresent()) {
                        int idc = operatingPoint.idc();
                        boolean inTemporalLayer = ((idc >> temporalId) & 1) != 0;
                        boolean inSpatialLayer = ((idc >> (spatialId + 8)) & 1) != 0;
                        if (idc == 0 || (inTemporalLayer && inSpatialLayer)) {
                            readLong(reader, sequenceHeader.timingInfo().bufferRemovalDelayLength());
                        }
                    }
                }
            }
        }

        if (isInterOrSwitch(frameType)) {
            throw unsupported("Inter and switch frame headers are not implemented yet");
        }

        int refreshFrameFlags = (frameType == FrameType.KEY && showFrame) ? 0xFF : readInt(reader, 8);
        if (strictStdCompliance && frameType == FrameType.INTRA && refreshFrameFlags == 0xFF) {
            fail("Intra frame headers must not refresh all reference frames in strict mode");
        }

        FrameSizeResult frameSizeResult = readFrameSize(reader, sequenceHeader, frameSizeOverride);
        boolean allowIntrabc = false;
        if (allowScreenContentTools && !frameSizeResult.superResolution.enabled()) {
            allowIntrabc = reader.readFlag();
        }

        boolean refreshContext = true;
        if (!sequenceHeader.reducedStillPictureHeader() && !disableCdfUpdate) {
            refreshContext = !reader.readFlag();
        }

        FrameHeader.TilingInfo tiling = parseTiling(reader, sequenceHeader, frameSizeResult.frameSize);
        FrameHeader.QuantizationInfo quantization = parseQuantization(reader, sequenceHeader);
        FrameHeader.SegmentationInfo segmentation = parseSegmentation(reader, primaryRefFrame);
        FrameHeader.DeltaInfo delta = parseDelta(reader, quantization.baseQIndex(), allowIntrabc);
        DerivedLosslessState losslessState = deriveLosslessState(quantization, segmentation);
        FrameHeader.LoopFilterInfo loopFilter = parseLoopFilter(reader, sequenceHeader, allowIntrabc, losslessState.allLossless);
        FrameHeader.CdefInfo cdef = parseCdef(reader, sequenceHeader, allowIntrabc, losslessState.allLossless);
        FrameHeader.RestorationInfo restoration = parseRestoration(reader, sequenceHeader, frameSizeResult.superResolution, allowIntrabc, losslessState.allLossless);
        FrameHeader.TransformMode transformMode = losslessState.allLossless
                ? FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY
                : (reader.readFlag() ? FrameHeader.TransformMode.SWITCHABLE : FrameHeader.TransformMode.LARGEST);
        boolean reducedTransformSet = reader.readFlag();
        boolean filmGrainPresent = false;
        if (sequenceHeader.features().filmGrainPresent() && (showFrame || showableFrame)) {
            filmGrainPresent = reader.readFlag();
            if (filmGrainPresent) {
                throw unsupported("Frame headers with film grain parameters are not implemented yet");
            }
        }

        return new FrameHeader(
                temporalId,
                spatialId,
                false,
                0,
                frameId,
                framePresentationDelay,
                frameType,
                showFrame,
                showableFrame,
                errorResilientMode,
                disableCdfUpdate,
                allowScreenContentTools,
                forceIntegerMotionVectors,
                frameSizeOverride,
                primaryRefFrame,
                frameOffset,
                refreshFrameFlags,
                frameSizeResult.frameSize,
                frameSizeResult.superResolution,
                allowIntrabc,
                refreshContext,
                tiling,
                quantization,
                losslessState.segmentation,
                delta,
                losslessState.allLossless,
                loopFilter,
                cdef,
                restoration,
                transformMode,
                reducedTransformSet,
                filmGrainPresent
        );
    }

    /// Parses the frame size and render size fields for non-reference-sized intra frames.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameSizeOverride whether frame size override signaling is enabled
    /// @return the parsed frame size and super-resolution state
    /// @throws IOException if the payload is truncated or invalid
    private static FrameSizeResult readFrameSize(BitReader reader, SequenceHeader sequenceHeader, boolean frameSizeOverride) throws IOException {
        int upscaledWidth;
        int height;
        if (frameSizeOverride) {
            upscaledWidth = readInt(reader, sequenceHeader.widthBits()) + 1;
            height = readInt(reader, sequenceHeader.heightBits()) + 1;
        } else {
            upscaledWidth = sequenceHeader.maxWidth();
            height = sequenceHeader.maxHeight();
        }

        boolean superResolutionEnabled = sequenceHeader.features().superResolution() && reader.readFlag();
        int widthScaleDenominator;
        int codedWidth;
        if (superResolutionEnabled) {
            widthScaleDenominator = 9 + readInt(reader, 3);
            codedWidth = Math.max((upscaledWidth * 8 + (widthScaleDenominator >> 1)) / widthScaleDenominator, Math.min(16, upscaledWidth));
        } else {
            widthScaleDenominator = 8;
            codedWidth = upscaledWidth;
        }

        boolean haveRenderSize = reader.readFlag();
        int renderWidth;
        int renderHeight;
        if (haveRenderSize) {
            renderWidth = readInt(reader, 16) + 1;
            renderHeight = readInt(reader, 16) + 1;
        } else {
            renderWidth = upscaledWidth;
            renderHeight = height;
        }

        return new FrameSizeResult(
                new FrameHeader.FrameSize(codedWidth, upscaledWidth, height, renderWidth, renderHeight),
                new FrameHeader.SuperResolutionInfo(superResolutionEnabled, widthScaleDenominator)
        );
    }

    /// Parses tile layout information.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param frameSize the parsed frame dimensions
    /// @return the parsed tile layout information
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.TilingInfo parseTiling(
            BitReader reader,
            SequenceHeader sequenceHeader,
            FrameHeader.FrameSize frameSize
    ) throws IOException {
        boolean uniform = reader.readFlag();
        int superblockSizeMinusOne = (64 << (sequenceHeader.features().use128x128Superblocks() ? 1 : 0)) - 1;
        int superblockLog2 = 6 + (sequenceHeader.features().use128x128Superblocks() ? 1 : 0);
        int superblockWidth = (frameSize.codedWidth() + superblockSizeMinusOne) >> superblockLog2;
        int superblockHeight = (frameSize.height() + superblockSizeMinusOne) >> superblockLog2;
        int maxTileWidthSuperblocks = 4096 >> superblockLog2;
        int maxTileAreaSuperblocks = (4096 * 2304) >> (2 * superblockLog2);
        int minLog2Cols = tileLog2(maxTileWidthSuperblocks, superblockWidth);
        int maxLog2Cols = tileLog2(1, Math.min(superblockWidth, MAX_TILE_COLUMNS));
        int maxLog2Rows = tileLog2(1, Math.min(superblockHeight, MAX_TILE_ROWS));
        int minLog2Tiles = Math.max(tileLog2(maxTileAreaSuperblocks, superblockWidth * superblockHeight), minLog2Cols);

        int log2Cols;
        int columns = 0;
        int[] columnStarts = new int[MAX_TILE_COLUMNS + 1];
        int minLog2Rows;
        int log2Rows;
        int rows = 0;
        int[] rowStarts = new int[MAX_TILE_ROWS + 1];

        if (uniform) {
            log2Cols = minLog2Cols;
            while (log2Cols < maxLog2Cols && reader.readFlag()) {
                log2Cols++;
            }
            int tileWidth = 1 + ((superblockWidth - 1) >> log2Cols);
            for (int superblockX = 0; superblockX < superblockWidth; superblockX += tileWidth) {
                columnStarts[columns++] = superblockX;
            }
            minLog2Rows = Math.max(minLog2Tiles - log2Cols, 0);
            log2Rows = minLog2Rows;
            while (log2Rows < maxLog2Rows && reader.readFlag()) {
                log2Rows++;
            }
            int tileHeight = 1 + ((superblockHeight - 1) >> log2Rows);
            for (int superblockY = 0; superblockY < superblockHeight; superblockY += tileHeight) {
                rowStarts[rows++] = superblockY;
            }
        } else {
            int widestTile = 0;
            int remainingMaxTileArea = superblockWidth * superblockHeight;

            int superblockX = 0;
            while (superblockX < superblockWidth && columns < MAX_TILE_COLUMNS) {
                int tileWidthSuperblocks = Math.min(superblockWidth - superblockX, maxTileWidthSuperblocks);
                int tileWidth = tileWidthSuperblocks > 1
                        ? 1 + readUniform(reader, tileWidthSuperblocks)
                        : 1;
                columnStarts[columns++] = superblockX;
                superblockX += tileWidth;
                widestTile = Math.max(widestTile, tileWidth);
            }

            log2Cols = tileLog2(1, columns);
            minLog2Rows = 0;
            if (minLog2Tiles > 0) {
                remainingMaxTileArea >>= minLog2Tiles + 1;
            }
            int maxTileHeightSuperblocks = Math.max(remainingMaxTileArea / widestTile, 1);

            int superblockY = 0;
            while (superblockY < superblockHeight && rows < MAX_TILE_ROWS) {
                int tileHeightSuperblocks = Math.min(superblockHeight - superblockY, maxTileHeightSuperblocks);
                int tileHeight = tileHeightSuperblocks > 1
                        ? 1 + readUniform(reader, tileHeightSuperblocks)
                        : 1;
                rowStarts[rows++] = superblockY;
                superblockY += tileHeight;
            }

            log2Rows = tileLog2(1, rows);
        }

        columnStarts[columns] = superblockWidth;
        rowStarts[rows] = superblockHeight;

        int updateTileIndex = 0;
        int sizeBytes = 0;
        if (log2Cols != 0 || log2Rows != 0) {
            updateTileIndex = readInt(reader, log2Cols + log2Rows);
            if (updateTileIndex >= columns * rows) {
                fail("Tile update index exceeds the number of tiles");
            }
            sizeBytes = readInt(reader, 2) + 1;
        }

        return new FrameHeader.TilingInfo(
                uniform,
                sizeBytes,
                minLog2Cols,
                maxLog2Cols,
                log2Cols,
                columns,
                minLog2Rows,
                maxLog2Rows,
                log2Rows,
                rows,
                Arrays.copyOf(columnStarts, columns + 1),
                Arrays.copyOf(rowStarts, rows + 1),
                updateTileIndex
        );
    }

    /// Parses quantization parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return the parsed quantization parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.QuantizationInfo parseQuantization(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        int baseQIndex = readInt(reader, 8);
        int yDcDelta = reader.readFlag() ? reader.readSignedBits(7) : 0;

        int uDcDelta = 0;
        int uAcDelta = 0;
        int vDcDelta = 0;
        int vAcDelta = 0;
        if (sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I400) {
            boolean diffUvDelta = sequenceHeader.colorConfig().separateUvDeltaQ() && reader.readFlag();
            if (reader.readFlag()) {
                uDcDelta = reader.readSignedBits(7);
            }
            if (reader.readFlag()) {
                uAcDelta = reader.readSignedBits(7);
            }
            if (diffUvDelta) {
                if (reader.readFlag()) {
                    vDcDelta = reader.readSignedBits(7);
                }
                if (reader.readFlag()) {
                    vAcDelta = reader.readSignedBits(7);
                }
            } else {
                vDcDelta = uDcDelta;
                vAcDelta = uAcDelta;
            }
        }

        boolean useQuantizationMatrices = reader.readFlag();
        int qmY = 0;
        int qmU = 0;
        int qmV = 0;
        if (useQuantizationMatrices) {
            qmY = readInt(reader, 4);
            qmU = readInt(reader, 4);
            qmV = sequenceHeader.colorConfig().separateUvDeltaQ() ? readInt(reader, 4) : qmU;
        }

        return new FrameHeader.QuantizationInfo(
                baseQIndex,
                yDcDelta,
                uDcDelta,
                uAcDelta,
                vDcDelta,
                vAcDelta,
                useQuantizationMatrices,
                qmY,
                qmU,
                qmV
        );
    }

    /// Parses segmentation parameters for currently supported primary-reference modes.
    ///
    /// @param reader the payload bit reader
    /// @param primaryRefFrame the primary reference frame index
    /// @return the parsed segmentation parameters
    /// @throws IOException if the payload is truncated or invalid
    private static FrameHeader.SegmentationInfo parseSegmentation(BitReader reader, int primaryRefFrame) throws IOException {
        boolean enabled = reader.readFlag();
        FrameHeader.SegmentData[] segments = defaultSegments();
        boolean[] losslessBySegment = new boolean[MAX_SEGMENTS];
        int[] qIndexBySegment = new int[MAX_SEGMENTS];

        if (!enabled) {
            return new FrameHeader.SegmentationInfo(false, false, false, false, segments, losslessBySegment, qIndexBySegment);
        }

        boolean updateMap;
        boolean temporalUpdate = false;
        boolean updateData;
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            updateMap = true;
            updateData = true;
        } else {
            throw unsupported("Frame headers that copy segmentation state from reference frames are not implemented yet");
        }

        if (updateData) {
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                int deltaQ = reader.readFlag() ? reader.readSignedBits(9) : 0;
                int deltaLfYVertical = reader.readFlag() ? reader.readSignedBits(7) : 0;
                int deltaLfYHorizontal = reader.readFlag() ? reader.readSignedBits(7) : 0;
                int deltaLfU = reader.readFlag() ? reader.readSignedBits(7) : 0;
                int deltaLfV = reader.readFlag() ? reader.readSignedBits(7) : 0;
                int referenceFrame = reader.readFlag() ? readInt(reader, 3) : -1;
                boolean skip = reader.readFlag();
                boolean globalMotion = reader.readFlag();

                segments[i] = new FrameHeader.SegmentData(
                        deltaQ,
                        deltaLfYVertical,
                        deltaLfYHorizontal,
                        deltaLfU,
                        deltaLfV,
                        referenceFrame,
                        skip,
                        globalMotion
                );
            }
        }

        return new FrameHeader.SegmentationInfo(enabled, updateMap, temporalUpdate, updateData, segments, losslessBySegment, qIndexBySegment);
    }

    /// Parses delta-q and delta-lf signaling flags.
    ///
    /// @param reader the payload bit reader
    /// @param baseQIndex the base luma AC quantizer index
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @return the parsed delta-q and delta-lf signaling flags
    /// @throws IOException if the payload is truncated
    private static FrameHeader.DeltaInfo parseDelta(BitReader reader, int baseQIndex, boolean allowIntrabc) throws IOException {
        if (baseQIndex == 0) {
            return new FrameHeader.DeltaInfo(false, 0, false, 0, false);
        }

        boolean deltaQPresent = reader.readFlag();
        if (!deltaQPresent) {
            return new FrameHeader.DeltaInfo(false, 0, false, 0, false);
        }

        int deltaQResLog2 = readInt(reader, 2);
        boolean deltaLfPresent = false;
        int deltaLfResLog2 = 0;
        boolean deltaLfMulti = false;
        if (!allowIntrabc) {
            deltaLfPresent = reader.readFlag();
            if (deltaLfPresent) {
                deltaLfResLog2 = readInt(reader, 2);
                deltaLfMulti = reader.readFlag();
            }
        }
        return new FrameHeader.DeltaInfo(deltaQPresent, deltaQResLog2, deltaLfPresent, deltaLfResLog2, deltaLfMulti);
    }

    /// Derives per-segment lossless flags and qindex values.
    ///
    /// @param quantization the parsed quantization parameters
    /// @param segmentation the parsed segmentation parameters
    /// @return the derived lossless state
    private static DerivedLosslessState deriveLosslessState(
            FrameHeader.QuantizationInfo quantization,
            FrameHeader.SegmentationInfo segmentation
    ) {
        boolean deltaLossless = quantization.yDcDelta() == 0
                && quantization.uDcDelta() == 0
                && quantization.uAcDelta() == 0
                && quantization.vDcDelta() == 0
                && quantization.vAcDelta() == 0;

        boolean[] losslessBySegment = new boolean[MAX_SEGMENTS];
        int[] qIndexBySegment = new int[MAX_SEGMENTS];
        boolean allLossless = true;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            int qIndex = segmentation.enabled()
                    ? clipUnsignedByte(quantization.baseQIndex() + segmentation.segment(i).deltaQ())
                    : quantization.baseQIndex();
            qIndexBySegment[i] = qIndex;
            boolean lossless = qIndex == 0 && deltaLossless;
            losslessBySegment[i] = lossless;
            allLossless &= lossless;
        }

        FrameHeader.SegmentationInfo updatedSegmentation = new FrameHeader.SegmentationInfo(
                segmentation.enabled(),
                segmentation.updateMap(),
                segmentation.temporalUpdate(),
                segmentation.updateData(),
                segmentation.segments(),
                losslessBySegment,
                qIndexBySegment
        );
        return new DerivedLosslessState(updatedSegmentation, allLossless);
    }

    /// Parses loop filter parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed loop filter parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.LoopFilterInfo parseLoopFilter(
            BitReader reader,
            SequenceHeader sequenceHeader,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        int[] levelY = new int[]{0, 0};
        int levelU = 0;
        int levelV = 0;
        int sharpness = 0;
        boolean modeRefDeltaEnabled = true;
        boolean modeRefDeltaUpdate = true;
        int[] referenceDeltas = Arrays.copyOf(DEFAULT_REFERENCE_DELTAS, DEFAULT_REFERENCE_DELTAS.length);
        int[] modeDeltas = Arrays.copyOf(DEFAULT_MODE_DELTAS, DEFAULT_MODE_DELTAS.length);

        if (!allLossless && !allowIntrabc) {
            levelY[0] = readInt(reader, 6);
            levelY[1] = readInt(reader, 6);
            if (sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I400
                    && (levelY[0] != 0 || levelY[1] != 0)) {
                levelU = readInt(reader, 6);
                levelV = readInt(reader, 6);
            }
            sharpness = readInt(reader, 3);

            modeRefDeltaEnabled = reader.readFlag();
            modeRefDeltaUpdate = false;
            if (modeRefDeltaEnabled) {
                modeRefDeltaUpdate = reader.readFlag();
                if (modeRefDeltaUpdate) {
                    for (int i = 0; i < referenceDeltas.length; i++) {
                        if (reader.readFlag()) {
                            referenceDeltas[i] = reader.readSignedBits(7);
                        }
                    }
                    for (int i = 0; i < modeDeltas.length; i++) {
                        if (reader.readFlag()) {
                            modeDeltas[i] = reader.readSignedBits(7);
                        }
                    }
                }
            }
        }

        return new FrameHeader.LoopFilterInfo(levelY, levelU, levelV, sharpness, modeRefDeltaEnabled, modeRefDeltaUpdate, referenceDeltas, modeDeltas);
    }

    /// Parses CDEF parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed CDEF parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.CdefInfo parseCdef(
            BitReader reader,
            SequenceHeader sequenceHeader,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        if (allLossless || !sequenceHeader.features().cdef() || allowIntrabc) {
            return new FrameHeader.CdefInfo(0, 0, new int[0], new int[0]);
        }

        int damping = readInt(reader, 2) + 3;
        int bits = readInt(reader, 2);
        int count = 1 << bits;
        if (count > MAX_CDEF_STRENGTHS) {
            fail("CDEF strength count exceeds the supported maximum");
        }

        int[] yStrengths = new int[count];
        int[] uvStrengths = new int[count];
        for (int i = 0; i < count; i++) {
            yStrengths[i] = readInt(reader, 6);
            if (sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I400) {
                uvStrengths[i] = readInt(reader, 6);
            }
        }
        return new FrameHeader.CdefInfo(damping, bits, yStrengths, uvStrengths);
    }

    /// Parses loop restoration parameters.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @param superResolution the parsed super-resolution settings
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allLossless whether all segments are lossless
    /// @return the parsed loop restoration parameters
    /// @throws IOException if the payload is truncated
    private static FrameHeader.RestorationInfo parseRestoration(
            BitReader reader,
            SequenceHeader sequenceHeader,
            FrameHeader.SuperResolutionInfo superResolution,
            boolean allowIntrabc,
            boolean allLossless
    ) throws IOException {
        FrameHeader.RestorationType[] types = new FrameHeader.RestorationType[]{
                FrameHeader.RestorationType.NONE,
                FrameHeader.RestorationType.NONE,
                FrameHeader.RestorationType.NONE
        };
        if ((allLossless && !superResolution.enabled()) || !sequenceHeader.features().restoration() || allowIntrabc) {
            return new FrameHeader.RestorationInfo(types, 0, 0);
        }

        types[0] = decodeRestorationType(readInt(reader, 2));
        if (sequenceHeader.colorConfig().pixelFormat() != PixelFormat.I400) {
            types[1] = decodeRestorationType(readInt(reader, 2));
            types[2] = decodeRestorationType(readInt(reader, 2));
        }

        int unitSizeLog2Y = 8;
        int unitSizeLog2Uv = 8;
        if (types[0] != FrameHeader.RestorationType.NONE
                || types[1] != FrameHeader.RestorationType.NONE
                || types[2] != FrameHeader.RestorationType.NONE) {
            unitSizeLog2Y = 6 + (sequenceHeader.features().use128x128Superblocks() ? 1 : 0);
            if (reader.readFlag()) {
                unitSizeLog2Y++;
                if (!sequenceHeader.features().use128x128Superblocks()) {
                    unitSizeLog2Y += reader.readFlag() ? 1 : 0;
                }
            }
            unitSizeLog2Uv = unitSizeLog2Y;
            if ((types[1] != FrameHeader.RestorationType.NONE || types[2] != FrameHeader.RestorationType.NONE)
                    && sequenceHeader.colorConfig().chromaSubsamplingX()
                    && sequenceHeader.colorConfig().chromaSubsamplingY()) {
                unitSizeLog2Uv -= reader.readFlag() ? 1 : 0;
            }
        }

        return new FrameHeader.RestorationInfo(types, unitSizeLog2Y, unitSizeLog2Uv);
    }

    /// Reads the frame-level screen content tools flag.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return whether screen content tools are enabled for the frame
    /// @throws IOException if the payload is truncated
    private static boolean readFrameScreenContentTools(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        return switch (sequenceHeader.features().screenContentTools()) {
            case OFF -> false;
            case ON -> true;
            case ADAPTIVE -> reader.readFlag();
        };
    }

    /// Reads the frame-level force-integer-motion-vectors flag.
    ///
    /// @param reader the payload bit reader
    /// @param sequenceHeader the active sequence header
    /// @return whether integer motion vectors are forced for the frame
    /// @throws IOException if the payload is truncated
    private static boolean readFrameForceIntegerMotionVectors(BitReader reader, SequenceHeader sequenceHeader) throws IOException {
        return switch (sequenceHeader.features().forceIntegerMotionVectors()) {
            case OFF -> false;
            case ON -> true;
            case ADAPTIVE -> reader.readFlag();
        };
    }

    /// Maps an AV1 frame type code to the public enum.
    ///
    /// @param value the AV1 frame type code
    /// @return the mapped public enum
    /// @throws IOException if the frame type code is invalid
    private static FrameType decodeFrameType(int value) throws IOException {
        return switch (value) {
            case 0 -> FrameType.KEY;
            case 1 -> FrameType.INTER;
            case 2 -> FrameType.INTRA;
            case 3 -> FrameType.SWITCH;
            default -> throw new IllegalStateException("Unexpected frame type code: " + value);
        };
    }

    /// Maps an AV1 loop restoration type code to the internal enum.
    ///
    /// @param value the AV1 loop restoration type code
    /// @return the mapped internal enum
    /// @throws IOException if the restoration type code is invalid
    private static FrameHeader.RestorationType decodeRestorationType(int value) throws IOException {
        return switch (value) {
            case 0 -> FrameHeader.RestorationType.NONE;
            case 1 -> FrameHeader.RestorationType.SWITCHABLE;
            case 2 -> FrameHeader.RestorationType.WIENER;
            case 3 -> FrameHeader.RestorationType.SELF_GUIDED;
            default -> throw new IllegalStateException("Unexpected restoration type code: " + value);
        };
    }

    /// Returns whether the frame type is inter or switch.
    ///
    /// @param frameType the AV1 frame type
    /// @return whether the frame type is inter or switch
    private static boolean isInterOrSwitch(FrameType frameType) {
        return frameType == FrameType.INTER || frameType == FrameType.SWITCH;
    }

    /// Reads an AV1 uniform integer.
    ///
    /// @param reader the payload bit reader
    /// @param maxExclusive the exclusive upper bound
    /// @return the decoded uniform integer
    /// @throws IOException if the payload is truncated
    private static int readUniform(BitReader reader, int maxExclusive) throws IOException {
        if (maxExclusive <= 1) {
            return 0;
        }

        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(maxExclusive);
        int m = (1 << bits) - maxExclusive;
        int value = readInt(reader, bits - 1);
        return value < m ? value : (value << 1) - m + readInt(reader, 1);
    }

    /// Computes the smallest `k` such that `size << k >= target`.
    ///
    /// @param size the tile unit size
    /// @param target the target size
    /// @return the smallest `k` such that `size << k >= target`
    private static int tileLog2(int size, int target) {
        int k = 0;
        while ((size << k) < target) {
            k++;
        }
        return k;
    }

    /// Reads and validates the trailing bits at the end of the payload.
    ///
    /// @param reader the payload bit reader
    /// @param strictStdCompliance whether strict standards compliance should be enforced
    /// @throws IOException if the payload is truncated or invalid
    private static void checkTrailingBits(BitReader reader, boolean strictStdCompliance) throws IOException {
        boolean trailingOneBit = reader.readFlag();
        if (!strictStdCompliance) {
            return;
        }
        if (!trailingOneBit) {
            fail("Trailing one bit was not present");
        }
        while (reader.bitsRemaining() > 0) {
            if (reader.readBit() != 0) {
                fail("Non-zero trailing padding bits are not allowed in strict mode");
            }
        }
    }

    /// Clips a value to the unsigned byte range.
    ///
    /// @param value the unclipped value
    /// @return the value clipped to the unsigned byte range
    private static int clipUnsignedByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /// Returns default per-segment data with `referenceFrame = -1`.
    ///
    /// @return default per-segment data
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[MAX_SEGMENTS];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// Reads a bounded unsigned integer and narrows it to `int`.
    ///
    /// @param reader the payload bit reader
    /// @param bitCount the number of bits to read
    /// @return the decoded integer value
    /// @throws IOException if the payload is truncated
    private static int readInt(BitReader reader, int bitCount) throws IOException {
        return Math.toIntExact(readLong(reader, bitCount));
    }

    /// Reads a bounded unsigned integer and returns it as `long`.
    ///
    /// @param reader the payload bit reader
    /// @param bitCount the number of bits to read
    /// @return the decoded integer value
    /// @throws IOException if the payload is truncated
    private static long readLong(BitReader reader, int bitCount) throws IOException {
        return reader.readBits(bitCount);
    }

    /// Throws an `IOException` with a validation message.
    ///
    /// @param message the validation message
    /// @throws IOException always
    private static void fail(String message) throws IOException {
        throw new IOException(message);
    }

    /// Throws an `IOException` describing an unsupported feature.
    ///
    /// @param message the unsupported feature description
    /// @return this method never returns normally
    /// @throws IOException always
    private static IOException unsupported(String message) throws IOException {
        throw new IOException(message);
    }

    /// Parsed frame size and super-resolution state.
    @NotNullByDefault
    private static final class FrameSizeResult {
        /// The parsed frame dimensions.
        private final FrameHeader.FrameSize frameSize;
        /// The parsed super-resolution settings.
        private final FrameHeader.SuperResolutionInfo superResolution;

        /// Creates parsed frame size and super-resolution state.
        ///
        /// @param frameSize the parsed frame dimensions
        /// @param superResolution the parsed super-resolution settings
        private FrameSizeResult(FrameHeader.FrameSize frameSize, FrameHeader.SuperResolutionInfo superResolution) {
            this.frameSize = Objects.requireNonNull(frameSize, "frameSize");
            this.superResolution = Objects.requireNonNull(superResolution, "superResolution");
        }
    }

    /// Derived per-segment lossless state.
    @NotNullByDefault
    private static final class DerivedLosslessState {
        /// The segmentation state with derived qindex and lossless arrays.
        private final FrameHeader.SegmentationInfo segmentation;
        /// Whether all segments are lossless.
        private final boolean allLossless;

        /// Creates derived per-segment lossless state.
        ///
        /// @param segmentation the segmentation state with derived arrays
        /// @param allLossless whether all segments are lossless
        private DerivedLosslessState(FrameHeader.SegmentationInfo segmentation, boolean allLossless) {
            this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
            this.allLossless = allLossless;
        }
    }
}
