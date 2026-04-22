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

import org.glavo.avif.decode.FrameType;
import org.glavo.avif.internal.av1.bitstream.ObuHeader;
import org.glavo.avif.internal.av1.bitstream.ObuPacket;
import org.glavo.avif.internal.av1.bitstream.ObuType;
import org.glavo.avif.internal.av1.model.FrameHeader;
import org.glavo.avif.internal.av1.model.SequenceHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for `FrameHeaderParser`.
@NotNullByDefault
final class FrameHeaderParserTest {
    /// Verifies that a reduced still-picture key frame header can be parsed.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesReducedStillPictureKeyFrameHeader() throws IOException {
        SequenceHeader sequenceHeader = new SequenceHeaderParser().parse(sequenceHeaderObu(reducedStillPictureSequencePayload()), false);
        FrameHeader header = new FrameHeaderParser().parse(frameHeaderObu(reducedStillPictureFrameHeaderPayload()), sequenceHeader, false);

        assertFalse(header.showExistingFrame());
        assertEquals(FrameType.KEY, header.frameType());
        assertTrue(header.showFrame());
        assertFalse(header.showableFrame());
        assertTrue(header.errorResilientMode());
        assertTrue(header.disableCdfUpdate());
        assertFalse(header.allowScreenContentTools());
        assertTrue(header.forceIntegerMotionVectors());
        assertFalse(header.frameSizeOverride());
        assertEquals(7, header.primaryRefFrame());
        assertEquals(0xFF, header.refreshFrameFlags());
        assertEquals(640, header.frameSize().codedWidth());
        assertEquals(640, header.frameSize().upscaledWidth());
        assertEquals(360, header.frameSize().height());
        assertEquals(640, header.frameSize().renderWidth());
        assertEquals(360, header.frameSize().renderHeight());
        assertFalse(header.superResolution().enabled());
        assertEquals(8, header.superResolution().widthScaleDenominator());
        assertFalse(header.allowIntrabc());
        assertTrue(header.refreshContext());

        assertTrue(header.tiling().uniform());
        assertEquals(1, header.tiling().columns());
        assertEquals(1, header.tiling().rows());
        assertEquals(0, header.tiling().log2Cols());
        assertEquals(0, header.tiling().log2Rows());
        assertEquals(10, header.tiling().columnStartSuperblocks()[1]);
        assertEquals(6, header.tiling().rowStartSuperblocks()[1]);

        assertEquals(0, header.quantization().baseQIndex());
        assertFalse(header.segmentation().enabled());
        assertFalse(header.delta().deltaQPresent());
        assertTrue(header.allLossless());
        assertTrue(header.loopFilter().modeRefDeltaEnabled());
        assertEquals(FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY, header.transformMode());
        assertFalse(header.reducedTransformSet());
        assertFalse(header.filmGrainPresent());
    }

    /// Verifies that an inter frame header can reuse a reference frame size and derive skip-mode state.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesInterFrameHeaderWithReferenceSizedFrame() throws IOException {
        SequenceHeader sequenceHeader = fullInterSequenceHeader();
        FrameHeader[] references = createInterReferenceFrames();

        FrameHeader header = new FrameHeaderParser().parse(
                frameHeaderObu(interFrameHeaderPayload()),
                sequenceHeader,
                false,
                references
        );

        assertFalse(header.showExistingFrame());
        assertEquals(FrameType.INTER, header.frameType());
        assertTrue(header.showFrame());
        assertTrue(header.showableFrame());
        assertFalse(header.errorResilientMode());
        assertTrue(header.disableCdfUpdate());
        assertFalse(header.allowScreenContentTools());
        assertFalse(header.forceIntegerMotionVectors());
        assertTrue(header.frameSizeOverride());
        assertEquals(7, header.primaryRefFrame());
        assertEquals(10, header.frameOffset());
        assertEquals(0x12, header.refreshFrameFlags());
        assertFalse(header.frameReferenceShortSignaling());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, header.referenceFrameIndices());
        assertEquals(64, header.frameSize().codedWidth());
        assertEquals(64, header.frameSize().upscaledWidth());
        assertEquals(64, header.frameSize().height());
        assertEquals(66, header.frameSize().renderWidth());
        assertEquals(68, header.frameSize().renderHeight());
        assertFalse(header.superResolution().enabled());
        assertEquals(8, header.superResolution().widthScaleDenominator());
        assertFalse(header.allowIntrabc());
        assertTrue(header.allowHighPrecisionMotionVectors());
        assertEquals(FrameHeader.InterpolationFilter.SWITCHABLE, header.subpelFilterMode());
        assertTrue(header.switchableMotionMode());
        assertTrue(header.useReferenceFrameMotionVectors());
        assertTrue(header.refreshContext());
        assertTrue(header.switchableCompoundReferences());
        assertTrue(header.skipModeAllowed());
        assertTrue(header.skipModeEnabled());
        assertArrayEquals(new int[]{0, 3}, header.skipModeReferenceIndices());
        assertTrue(header.warpedMotion());
        assertFalse(header.reducedTransformSet());
        assertFalse(header.filmGrainPresent());
    }

    /// Verifies that segmentation and loop-filter delta state can be inherited from the primary reference frame.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesInterFrameHeaderWithPrimaryReferenceStateCopy() throws IOException {
        SequenceHeader sequenceHeader = fullInterSequenceHeader();
        FrameHeader[] references = createInterReferenceFramesWithInheritedState();

        FrameHeader header = new FrameHeaderParser().parse(
                frameHeaderObu(interFrameHeaderPayloadWithPrimaryReferenceCopy()),
                sequenceHeader,
                false,
                references
        );

        assertEquals(FrameType.INTER, header.frameType());
        assertEquals(0, header.primaryRefFrame());
        assertTrue(header.segmentation().enabled());
        assertFalse(header.segmentation().updateMap());
        assertFalse(header.segmentation().temporalUpdate());
        assertFalse(header.segmentation().updateData());
        assertTrue(header.segmentation().preskip());
        assertEquals(2, header.segmentation().lastActiveSegmentId());
        assertEquals(11, header.segmentation().segment(2).deltaQ());
        assertEquals(3, header.segmentation().segment(2).referenceFrame());
        assertTrue(header.segmentation().segment(2).skip());
        assertEquals(31, header.segmentation().qIndex(2));
        assertTrue(header.loopFilter().modeRefDeltaEnabled());
        assertFalse(header.loopFilter().modeRefDeltaUpdate());
        assertArrayEquals(new int[]{2, 1, 0, -1, -2, 0, 1, 2}, header.loopFilter().referenceDeltas());
        assertArrayEquals(new int[]{3, -3}, header.loopFilter().modeDeltas());
        assertFalse(header.switchableCompoundReferences());
        assertFalse(header.skipModeAllowed());
        assertFalse(header.skipModeEnabled());
        assertFalse(header.warpedMotion());
    }

    /// Verifies that explicit film grain parameters are parsed and normalized for an inter frame.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesInterFrameHeaderWithExplicitFilmGrainParameters() throws IOException {
        SequenceHeader sequenceHeader = fullInterSequenceHeader(true);
        FrameHeader header = new FrameHeaderParser().parse(
                frameHeaderObu(interFrameHeaderPayloadWithFilmGrain()),
                sequenceHeader,
                false,
                createInterReferenceFrames()
        );

        assertTrue(header.filmGrainPresent());
        FrameHeader.FilmGrainParams filmGrain = header.filmGrain();
        assertTrue(filmGrain.applyGrain());
        assertEquals(0x1234, filmGrain.grainSeed());
        assertTrue(filmGrain.updated());
        assertEquals(-1, filmGrain.referenceFrameIndex());
        assertFalse(filmGrain.chromaScalingFromLuma());

        FrameHeader.FilmGrainPoint[] yPoints = filmGrain.yPoints();
        assertEquals(2, yPoints.length);
        assertEquals(16, yPoints[0].value());
        assertEquals(32, yPoints[0].scaling());
        assertEquals(128, yPoints[1].value());
        assertEquals(200, yPoints[1].scaling());

        FrameHeader.FilmGrainPoint[] cbPoints = filmGrain.cbPoints();
        assertEquals(1, cbPoints.length);
        assertEquals(64, cbPoints[0].value());
        assertEquals(80, cbPoints[0].scaling());

        FrameHeader.FilmGrainPoint[] crPoints = filmGrain.crPoints();
        assertEquals(1, crPoints.length);
        assertEquals(96, crPoints[0].value());
        assertEquals(112, crPoints[0].scaling());

        assertEquals(10, filmGrain.scalingShift());
        assertEquals(1, filmGrain.arCoeffLag());
        assertArrayEquals(new int[]{1, -1, 12, -8}, filmGrain.arCoefficientsY());
        assertArrayEquals(new int[]{2, 0, -1, -2, 12}, filmGrain.arCoefficientsCb());
        assertArrayEquals(new int[]{0, 1, 2, 3, -1}, filmGrain.arCoefficientsCr());
        assertEquals(7, filmGrain.arCoeffShift());
        assertEquals(3, filmGrain.grainScaleShift());
        assertEquals(77, filmGrain.cbMult());
        assertEquals(88, filmGrain.cbLumaMult());
        assertEquals(300, filmGrain.cbOffset());
        assertEquals(66, filmGrain.crMult());
        assertEquals(55, filmGrain.crLumaMult());
        assertEquals(123, filmGrain.crOffset());
        assertTrue(filmGrain.overlapEnabled());
        assertFalse(filmGrain.clipToRestrictedRange());
    }

    /// Verifies that film grain parameters can be inherited from a referenced frame while preserving the new seed.
    ///
    /// @throws IOException if the test payload cannot be parsed
    @Test
    void parsesInterFrameHeaderWithReferencedFilmGrainParameters() throws IOException {
        SequenceHeader sequenceHeader = fullInterSequenceHeader(true);
        FrameHeader.FilmGrainParams referencedFilmGrain = sampleFilmGrainParams();
        FrameHeader[] references = createInterReferenceFramesWithFilmGrainSource(referencedFilmGrain);

        FrameHeader header = new FrameHeaderParser().parse(
                frameHeaderObu(interFrameHeaderPayloadWithReferencedFilmGrain()),
                sequenceHeader,
                false,
                references
        );

        assertTrue(header.filmGrainPresent());
        FrameHeader.FilmGrainParams filmGrain = header.filmGrain();
        assertTrue(filmGrain.applyGrain());
        assertEquals(0x4567, filmGrain.grainSeed());
        assertFalse(filmGrain.updated());
        assertEquals(3, filmGrain.referenceFrameIndex());
        assertEquals(referencedFilmGrain.chromaScalingFromLuma(), filmGrain.chromaScalingFromLuma());
        assertEquals(referencedFilmGrain.scalingShift(), filmGrain.scalingShift());
        assertEquals(referencedFilmGrain.arCoeffLag(), filmGrain.arCoeffLag());
        assertArrayEquals(referencedFilmGrain.arCoefficientsY(), filmGrain.arCoefficientsY());
        assertArrayEquals(referencedFilmGrain.arCoefficientsCb(), filmGrain.arCoefficientsCb());
        assertArrayEquals(referencedFilmGrain.arCoefficientsCr(), filmGrain.arCoefficientsCr());
        assertEquals(referencedFilmGrain.arCoeffShift(), filmGrain.arCoeffShift());
        assertEquals(referencedFilmGrain.grainScaleShift(), filmGrain.grainScaleShift());
        assertEquals(referencedFilmGrain.cbMult(), filmGrain.cbMult());
        assertEquals(referencedFilmGrain.cbLumaMult(), filmGrain.cbLumaMult());
        assertEquals(referencedFilmGrain.cbOffset(), filmGrain.cbOffset());
        assertEquals(referencedFilmGrain.crMult(), filmGrain.crMult());
        assertEquals(referencedFilmGrain.crLumaMult(), filmGrain.crLumaMult());
        assertEquals(referencedFilmGrain.crOffset(), filmGrain.crOffset());
        assertEquals(referencedFilmGrain.overlapEnabled(), filmGrain.overlapEnabled());
        assertEquals(referencedFilmGrain.clipToRestrictedRange(), filmGrain.clipToRestrictedRange());

        FrameHeader.FilmGrainPoint[] yPoints = filmGrain.yPoints();
        assertEquals(2, yPoints.length);
        assertEquals(32, yPoints[0].value());
        assertEquals(48, yPoints[0].scaling());
        assertEquals(160, yPoints[1].value());
        assertEquals(200, yPoints[1].scaling());
    }

    /// Wraps a payload in a synthetic sequence header OBU packet.
    ///
    /// @param payload the raw sequence header payload
    /// @return the synthetic sequence header OBU packet
    private static ObuPacket sequenceHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.SEQUENCE_HEADER, false, true, 0, 0), payload, 0, 0);
    }

    /// Wraps a payload in a synthetic frame header OBU packet.
    ///
    /// @param payload the raw frame header payload
    /// @return the synthetic frame header OBU packet
    private static ObuPacket frameHeaderObu(byte[] payload) {
        return new ObuPacket(new ObuHeader(ObuType.FRAME_HEADER, false, true, 0, 0), payload, 0, 1);
    }

    /// Creates a reduced still-picture sequence header payload.
    ///
    /// @return the reduced still-picture sequence header payload
    private static byte[] reducedStillPictureSequencePayload() {
        BitWriter writer = new BitWriter();
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(5, 3);
        writer.writeBits(1, 2);
        writer.writeBits(9, 4);
        writer.writeBits(8, 4);
        writer.writeBits(639, 10);
        writer.writeBits(359, 9);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a reduced still-picture key frame header payload.
    ///
    /// @return the reduced still-picture key frame header payload
    private static byte[] reducedStillPictureFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a synthetic non-reduced sequence header configuration suitable for inter-frame parser tests.
    ///
    /// @return a synthetic non-reduced sequence header configuration
    private static SequenceHeader fullInterSequenceHeader() {
        return fullInterSequenceHeader(false);
    }

    /// Creates a synthetic non-reduced sequence header configuration suitable for inter-frame parser tests.
    ///
    /// @param filmGrainPresent whether frame headers may carry film grain parameters
    /// @return a synthetic non-reduced sequence header configuration
    private static SequenceHeader fullInterSequenceHeader(boolean filmGrainPresent) {
        return new SequenceHeader(
                0,
                1024,
                512,
                new SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false),
                new SequenceHeader.OperatingPoint[]{
                        new SequenceHeader.OperatingPoint(2, 0, 10, 0, false, false, false, null)
                },
                false,
                false,
                10,
                9,
                false,
                0,
                0,
                new SequenceHeader.FeatureConfig(
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        SequenceHeader.AdaptiveBoolean.OFF,
                        4,
                        true,
                        false,
                        false,
                        filmGrainPresent
                ),
                new SequenceHeader.ColorConfig(
                        8,
                        false,
                        false,
                        2,
                        2,
                        2,
                        false,
                        org.glavo.avif.decode.PixelFormat.I420,
                        0,
                        true,
                        true,
                        false
                )
        );
    }

    /// Creates refreshed reference-frame headers with order hints on both sides of the current frame.
    ///
    /// @return refreshed reference-frame headers with deterministic sizes and order hints
    private static FrameHeader[] createInterReferenceFrames() {
        FrameHeader[] references = new FrameHeader[8];
        references[0] = createReferenceFrameHeader(9, 64, 64, 66, 68);
        references[1] = createReferenceFrameHeader(8, 640, 360, 640, 360);
        references[2] = createReferenceFrameHeader(7, 640, 360, 640, 360);
        references[3] = createReferenceFrameHeader(11, 640, 360, 640, 360);
        references[4] = createReferenceFrameHeader(12, 640, 360, 640, 360);
        references[5] = createReferenceFrameHeader(13, 640, 360, 640, 360);
        references[6] = createReferenceFrameHeader(14, 640, 360, 640, 360);
        return references;
    }

    /// Creates refreshed reference-frame headers where slot `3` carries film grain parameters for inheritance tests.
    ///
    /// @param filmGrain the film grain parameters stored in reference slot `3`
    /// @return refreshed reference-frame headers with deterministic sizes and film grain state
    private static FrameHeader[] createInterReferenceFramesWithFilmGrainSource(FrameHeader.FilmGrainParams filmGrain) {
        FrameHeader[] references = createInterReferenceFrames();
        references[3] = createReferenceFrameHeader(
                11,
                640,
                360,
                640,
                360,
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                filmGrain
        );
        return references;
    }

    /// Creates refreshed reference-frame headers where slot `0` carries non-default inherited parser state.
    ///
    /// @return refreshed reference-frame headers with inherited segmentation and loop-filter state
    private static FrameHeader[] createInterReferenceFramesWithInheritedState() {
        FrameHeader[] references = createInterReferenceFrames();
        FrameHeader.SegmentData[] segments = defaultSegments();
        segments[2] = new FrameHeader.SegmentData(11, 0, 0, 0, 0, 3, true, false);
        references[0] = createReferenceFrameHeader(
                9,
                64,
                64,
                66,
                68,
                new FrameHeader.SegmentationInfo(
                        true,
                        true,
                        false,
                        true,
                        true,
                        2,
                        segments,
                        new boolean[8],
                        new int[8]
                ),
                new FrameHeader.LoopFilterInfo(
                        new int[]{0, 0},
                        0,
                        0,
                        0,
                        true,
                        false,
                        new int[]{2, 1, 0, -1, -2, 0, 1, 2},
                        new int[]{3, -3}
                )
        );
        return references;
    }

    /// Creates a refreshed reference-frame header with deterministic geometry and order hint.
    ///
    /// @param frameOffset the order hint stored in the refreshed reference
    /// @param upscaledWidth the frame width before super-resolution downscaling
    /// @param height the frame height
    /// @param renderWidth the render width
    /// @param renderHeight the render height
    /// @return a refreshed reference-frame header
    private static FrameHeader createReferenceFrameHeader(
            int frameOffset,
            int upscaledWidth,
            int height,
            int renderWidth,
            int renderHeight
    ) {
        return createReferenceFrameHeader(
                frameOffset,
                upscaledWidth,
                height,
                renderWidth,
                renderHeight,
                new FrameHeader.SegmentationInfo(false, false, false, false, defaultSegments(), new boolean[8], new int[8]),
                new FrameHeader.LoopFilterInfo(new int[]{0, 0}, 0, 0, 0, true, true, new int[]{1, 0, 0, 0, -1, 0, -1, -1}, new int[]{0, 0}),
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates a refreshed reference-frame header with deterministic geometry and explicit inherited parser state.
    ///
    /// @param frameOffset the order hint stored in the refreshed reference
    /// @param upscaledWidth the frame width before super-resolution downscaling
    /// @param height the frame height
    /// @param renderWidth the render width
    /// @param renderHeight the render height
    /// @param segmentation the segmentation state stored in the refreshed reference
    /// @param loopFilter the loop-filter state stored in the refreshed reference
    /// @return a refreshed reference-frame header
    private static FrameHeader createReferenceFrameHeader(
            int frameOffset,
            int upscaledWidth,
            int height,
            int renderWidth,
            int renderHeight,
            FrameHeader.SegmentationInfo segmentation,
            FrameHeader.LoopFilterInfo loopFilter
    ) {
        return createReferenceFrameHeader(
                frameOffset,
                upscaledWidth,
                height,
                renderWidth,
                renderHeight,
                segmentation,
                loopFilter,
                FrameHeader.FilmGrainParams.disabled()
        );
    }

    /// Creates a refreshed reference-frame header with deterministic geometry, explicit inherited parser state, and film grain.
    ///
    /// @param frameOffset the order hint stored in the refreshed reference
    /// @param upscaledWidth the frame width before super-resolution downscaling
    /// @param height the frame height
    /// @param renderWidth the render width
    /// @param renderHeight the render height
    /// @param segmentation the segmentation state stored in the refreshed reference
    /// @param loopFilter the loop-filter state stored in the refreshed reference
    /// @param filmGrain the normalized film grain state stored in the refreshed reference
    /// @return a refreshed reference-frame header
    private static FrameHeader createReferenceFrameHeader(
            int frameOffset,
            int upscaledWidth,
            int height,
            int renderWidth,
            int renderHeight,
            FrameHeader.SegmentationInfo segmentation,
            FrameHeader.LoopFilterInfo loopFilter,
            FrameHeader.FilmGrainParams filmGrain
    ) {
        return new FrameHeader(
                0,
                0,
                false,
                0,
                0,
                0,
                FrameType.INTER,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                7,
                frameOffset,
                0,
                new FrameHeader.FrameSize(upscaledWidth, upscaledWidth, height, renderWidth, renderHeight),
                new FrameHeader.SuperResolutionInfo(false, 8),
                false,
                true,
                new FrameHeader.TilingInfo(false, 0, 0, 0, 0, 1, 0, 0, 0, 1, new int[]{0, 1}, new int[]{0, 1}, 0),
                new FrameHeader.QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                segmentation,
                new FrameHeader.DeltaInfo(false, 0, false, 0, false),
                true,
                loopFilter,
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
                filmGrain
        );
    }

    /// Returns default per-segment data with all features disabled.
    ///
    /// @return default per-segment data with all features disabled
    private static FrameHeader.SegmentData[] defaultSegments() {
        FrameHeader.SegmentData[] segments = new FrameHeader.SegmentData[8];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new FrameHeader.SegmentData(0, 0, 0, 0, 0, -1, false, false);
        }
        return segments;
    }

    /// Creates a standalone inter frame header payload that uses the first reference for frame size.
    ///
    /// @return a standalone inter frame header payload
    private static byte[] interFrameHeaderPayload() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(false);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(10, 4);
        writer.writeBits(7, 3);
        writer.writeBits(0x12, 8);
        writer.writeFlag(false);
        writer.writeBits(0, 3);
        writer.writeBits(1, 3);
        writer.writeBits(2, 3);
        writer.writeBits(3, 3);
        writer.writeBits(4, 3);
        writer.writeBits(5, 3);
        writer.writeBits(6, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a standalone inter frame header payload that inherits segmentation and loop-filter state from `primary_ref_frame = 0`.
    ///
    /// @return a standalone inter frame header payload that copies primary-reference state
    private static byte[] interFrameHeaderPayloadWithPrimaryReferenceCopy() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(false);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(10, 4);
        writer.writeBits(0, 3);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeBits(0, 3);
        writer.writeBits(1, 3);
        writer.writeBits(2, 3);
        writer.writeBits(3, 3);
        writer.writeBits(4, 3);
        writer.writeBits(5, 3);
        writer.writeBits(6, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(20, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 6);
        writer.writeBits(0, 6);
        writer.writeBits(0, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeBits(0, 2);
        writer.writeBits(0, 2);
        writer.writeBits(0, 6);
        writer.writeBits(0, 6);
        writer.writeBits(0, 2);
        writer.writeBits(0, 2);
        writer.writeBits(0, 2);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a standalone inter frame header payload with explicit film grain parameters.
    ///
    /// @return a standalone inter frame header payload with explicit film grain parameters
    private static byte[] interFrameHeaderPayloadWithFilmGrain() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(false);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(10, 4);
        writer.writeBits(7, 3);
        writer.writeBits(0x12, 8);
        writer.writeFlag(false);
        writer.writeBits(0, 3);
        writer.writeBits(1, 3);
        writer.writeBits(2, 3);
        writer.writeBits(3, 3);
        writer.writeBits(4, 3);
        writer.writeBits(5, 3);
        writer.writeBits(6, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(0x1234, 16);
        writer.writeFlag(true);
        writer.writeBits(2, 4);
        writer.writeBits(16, 8);
        writer.writeBits(32, 8);
        writer.writeBits(128, 8);
        writer.writeBits(200, 8);
        writer.writeFlag(false);
        writer.writeBits(1, 4);
        writer.writeBits(64, 8);
        writer.writeBits(80, 8);
        writer.writeBits(1, 4);
        writer.writeBits(96, 8);
        writer.writeBits(112, 8);
        writer.writeBits(2, 2);
        writer.writeBits(1, 2);
        writer.writeBits(129, 8);
        writer.writeBits(127, 8);
        writer.writeBits(140, 8);
        writer.writeBits(120, 8);
        writer.writeBits(130, 8);
        writer.writeBits(128, 8);
        writer.writeBits(127, 8);
        writer.writeBits(126, 8);
        writer.writeBits(140, 8);
        writer.writeBits(128, 8);
        writer.writeBits(129, 8);
        writer.writeBits(130, 8);
        writer.writeBits(131, 8);
        writer.writeBits(127, 8);
        writer.writeBits(1, 2);
        writer.writeBits(3, 2);
        writer.writeBits(77, 8);
        writer.writeBits(88, 8);
        writer.writeBits(300, 9);
        writer.writeBits(66, 8);
        writer.writeBits(55, 8);
        writer.writeBits(123, 9);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates a standalone inter frame header payload that reuses film grain parameters from reference slot `3`.
    ///
    /// @return a standalone inter frame header payload with referenced film grain parameters
    private static byte[] interFrameHeaderPayloadWithReferencedFilmGrain() {
        BitWriter writer = new BitWriter();
        writer.writeFlag(false);
        writer.writeBits(1, 2);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(10, 4);
        writer.writeBits(7, 3);
        writer.writeBits(0x12, 8);
        writer.writeFlag(false);
        writer.writeBits(0, 3);
        writer.writeBits(1, 3);
        writer.writeBits(2, 3);
        writer.writeBits(3, 3);
        writer.writeBits(4, 3);
        writer.writeBits(5, 3);
        writer.writeBits(6, 3);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeBits(0, 8);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(true);
        writer.writeFlag(false);
        writer.writeFlag(true);
        writer.writeBits(0x4567, 16);
        writer.writeFlag(false);
        writer.writeBits(3, 3);
        writer.writeTrailingBits();
        return writer.toByteArray();
    }

    /// Creates normalized film grain parameters for reference-copy tests.
    ///
    /// @return normalized film grain parameters for reference-copy tests
    private static FrameHeader.FilmGrainParams sampleFilmGrainParams() {
        return new FrameHeader.FilmGrainParams(
                true,
                0x2222,
                true,
                -1,
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(32, 48),
                        new FrameHeader.FilmGrainPoint(160, 200)
                },
                false,
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(64, 90)
                },
                new FrameHeader.FilmGrainPoint[]{
                        new FrameHeader.FilmGrainPoint(96, 140)
                },
                11,
                1,
                new int[]{3, -3, 10, -10},
                new int[]{4, 3, 2, 1, 0},
                new int[]{-4, -3, -2, -1, 0},
                8,
                2,
                21,
                34,
                123,
                55,
                89,
                321,
                false,
                true
        );
    }

    /// Small MSB-first bit writer used to build AV1 test payloads.
    @NotNullByDefault
    private static final class BitWriter {
        /// The destination byte stream.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /// The in-progress byte.
        private int currentByte;
        /// The number of bits already written into the in-progress byte.
        private int bitCount;

        /// Writes a boolean flag.
        ///
        /// @param value the boolean flag to write
        private void writeFlag(boolean value) {
            writeBit(value ? 1 : 0);
        }

        /// Writes an unsigned literal with the requested bit width.
        ///
        /// @param value the unsigned literal value
        /// @param width the number of bits to write
        private void writeBits(long value, int width) {
            for (int bit = width - 1; bit >= 0; bit--) {
                writeBit((int) ((value >>> bit) & 1L));
            }
        }

        /// Writes trailing bits and byte alignment padding.
        private void writeTrailingBits() {
            writeBit(1);
            while (bitCount != 0) {
                writeBit(0);
            }
        }

        /// Returns the written bytes.
        ///
        /// @return the written bytes
        private byte[] toByteArray() {
            return output.toByteArray();
        }

        /// Writes a single bit.
        ///
        /// @param bit the bit value to write
        private void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
