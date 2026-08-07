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
package org.glavo.avif.javafx;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;
import org.glavo.avif.AvifFrame;
import org.glavo.avif.AvifImageInfo;
import org.glavo.avif.AvifSequenceInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// JavaFX image adapter for decoded AVIF content.
///
/// The adapter writes packed non-premultiplied `ARGB` pixels from decoded [AvifFrame]
/// instances into a `WritableImage`. When constructed from a list of frames, it can also
/// play animated AVIF content with frame-accurate timing.
@NotNullByDefault
public final class AvifFXImage extends WritableImage {
    /// The decoded frames in presentation order.
    private final @Unmodifiable List<AvifFrame> frames;
    /// Whether the image contains more than one frame.
    private final boolean animated;
    /// Container timing and repetition metadata, or `null` for fixed-rate playback.
    private final @Nullable AvifSequenceInfo sequenceInfo;
    /// The lazily created playback timeline.
    private @Nullable Timeline timeline;
    /// The index of the frame currently stored in this image.
    private int renderedFrameIndex = -1;

    /// Creates a JavaFX image from one decoded frame.
    ///
    /// @param frame the decoded frame to display
    public AvifFXImage(AvifFrame frame) {
        this(List.of(frame), null);
    }

    /// Creates a JavaFX image from a list of decoded frames.
    ///
    /// The input list is snapshotted and the first frame is written immediately. All frames must
    /// have the same dimensions. Call [#getAnimation()] to control playback.
    ///
    /// @param frames  the decoded frames in presentation order
    /// @param autoPlay whether to start playing the animation automatically
    /// @throws IllegalArgumentException if the frame list is empty or its dimensions differ
    public AvifFXImage(List<AvifFrame> frames, boolean autoPlay) {
        this(frames, autoPlay, 30);
    }

    /// Creates a JavaFX image from a list of decoded frames with an explicit frame rate.
    ///
    /// The input list is snapshotted. All frames must have the same dimensions. Values of `fps`
    /// below one are treated as one frame per second.
    ///
    /// @param frames  the decoded frames in presentation order
    /// @param autoPlay whether to start playing the animation automatically
    /// @param fps      the frames per second for playback timing
    /// @throws IllegalArgumentException if the frame list is empty or its dimensions differ
    public AvifFXImage(List<AvifFrame> frames, boolean autoPlay, int fps) {
        this(frames, null);

        if (autoPlay && isAnimated()) {
            Timeline animation = Objects.requireNonNull(getAnimation(fps));
            animation.play();
        }
    }

    /// Creates a JavaFX image using the frame timing and repetition metadata from an AVIS sequence.
    ///
    /// The input list is snapshotted. When timing metadata is absent, playback falls back to 30
    /// frames per second. An unknown or infinite repetition count produces indefinite playback; a
    /// non-negative repetition count is interpreted as the number of repetitions after the first
    /// playback.
    ///
    /// @param frames the decoded frames in presentation order
    /// @param sequenceInfo the timing and repetition metadata for the frames
    /// @param autoPlay whether to start playing the animation automatically
    /// @throws IllegalArgumentException if the frame list is empty, its dimensions differ, or its
    /// size does not match `sequenceInfo.frameCount()`
    public AvifFXImage(List<AvifFrame> frames, AvifSequenceInfo sequenceInfo, boolean autoPlay) {
        this(frames, Objects.requireNonNull(sequenceInfo, "sequenceInfo"));

        if (autoPlay && isAnimated()) {
            Timeline animation = Objects.requireNonNull(getAnimation());
            animation.play();
        }
    }

    /// Creates a JavaFX image with optional container sequence metadata.
    ///
    /// @param frames the decoded frames in presentation order
    /// @param sequenceInfo the timing and repetition metadata, or `null`
    /// @throws IllegalArgumentException if the frame list is empty, its dimensions differ, or its
    /// size does not match `sequenceInfo.frameCount()`
    private AvifFXImage(List<AvifFrame> frames, @Nullable AvifSequenceInfo sequenceInfo) {
        super(firstFrame(frames).width(), firstFrame(frames).height());
        this.frames = List.copyOf(frames);
        this.animated = this.frames.size() > 1;
        this.sequenceInfo = sequenceInfo;

        validateFrameLayout();
        if (sequenceInfo != null && sequenceInfo.frameCount() != this.frames.size()) {
            throw new IllegalArgumentException(
                    "Sequence frame count does not match decoded frames: "
                            + sequenceInfo.frameCount() + " != " + this.frames.size()
            );
        }
        renderFrame(0);
    }

    /// Returns whether this image is animated.
    ///
    /// @return `true` if this image contains multiple frames.
    public boolean isAnimated() {
        return animated;
    }

    /// Returns the JavaFX timeline that drives this image's animation.
    ///
    /// Container frame durations and repetition metadata are used when supplied at construction;
    /// otherwise playback uses 30 frames per second and repeats indefinitely. The timeline is
    /// created lazily; repeated calls return the same mutable JavaFX timeline.
    ///
    /// @return the timeline, or `null` if not animated
    public @Nullable Timeline getAnimation() {
        if (!animated) {
            return null;
        }
        if (timeline == null) {
            @Nullable AvifSequenceInfo info = sequenceInfo;
            if (hasUsableSequenceTiming(info)) {
                timeline = createTimeline(createSequenceKeyFrames(info), sequenceCycleCount(info));
            } else {
                int cycleCount = info == null ? Animation.INDEFINITE : sequenceCycleCount(info);
                timeline = createTimeline(createUniformKeyFrames(30), cycleCount);
            }
        }
        return timeline;
    }

    /// Returns the JavaFX timeline that drives this image's animation.
    ///
    /// If a timeline has already been created, this method returns it without changing its timing.
    /// Values below one are treated as one frame per second.
    ///
    /// @param fps the frames per second for fixed-rate playback timing
    /// @return the timeline, or `null` if not animated
    public @Nullable Timeline getAnimation(int fps) {
        if (!animated) {
            return null;
        }
        if (timeline == null) {
            timeline = createTimeline(createUniformKeyFrames(Math.max(1, fps)), Animation.INDEFINITE);
        }
        return timeline;
    }

    /// Writes one decoded frame into this JavaFX image.
    ///
    /// @param frameIndex the zero-based frame index
    private void renderFrame(int frameIndex) {
        if (frameIndex != renderedFrameIndex) {
            AvifFrame frame = frames.get(frameIndex);
            getPixelWriter().setPixels(
                    0,
                    0,
                    frame.width(),
                    frame.height(),
                    PixelFormat.getIntArgbInstance(),
                    frame.intPixelBuffer(),
                    frame.width()
            );
            renderedFrameIndex = frameIndex;
        }
    }

    /// Creates fixed-rate keyframes for every decoded frame and the animation endpoint.
    ///
    /// @param fps the positive frame rate
    /// @return the immutable keyframes in chronological order
    private @Unmodifiable List<KeyFrame> createUniformKeyFrames(int fps) {
        double frameDurationMs = 1000.0 / fps;
        ArrayList<KeyFrame> keyFrames = new ArrayList<>(frames.size() + 1);
        double currentMs = 0.0;
        for (int i = 0; i < frames.size(); i++) {
            final int frameIndex = i;
            keyFrames.add(new KeyFrame(Duration.millis(currentMs), event -> renderFrame(frameIndex)));
            currentMs += frameDurationMs;
        }
        keyFrames.add(new KeyFrame(Duration.millis(currentMs)));
        return List.copyOf(keyFrames);
    }

    /// Creates variable-rate keyframes from AVIS media timescale units.
    ///
    /// @param info the usable sequence timing metadata
    /// @return the immutable keyframes in chronological order
    private @Unmodifiable List<KeyFrame> createSequenceKeyFrames(AvifSequenceInfo info) {
        int @Unmodifiable [] frameDurations = info.frameDurations();
        ArrayList<KeyFrame> keyFrames = new ArrayList<>(frames.size() + 1);
        double currentMs = 0.0;
        for (int i = 0; i < frames.size(); i++) {
            final int frameIndex = i;
            keyFrames.add(new KeyFrame(Duration.millis(currentMs), event -> renderFrame(frameIndex)));
            currentMs += frameDurations[i] * 1000.0 / info.mediaTimescale();
        }
        keyFrames.add(new KeyFrame(Duration.millis(currentMs)));
        return List.copyOf(keyFrames);
    }

    /// Creates a playback timeline from immutable keyframe data.
    ///
    /// @param keyFrames the keyframes in chronological order
    /// @param cycleCount the JavaFX animation cycle count
    /// @return the configured timeline
    private static Timeline createTimeline(@Unmodifiable List<KeyFrame> keyFrames, int cycleCount) {
        Timeline result = new Timeline();
        result.setCycleCount(cycleCount);
        result.getKeyFrames().setAll(keyFrames);
        return result;
    }

    /// Returns whether sequence metadata supplies complete timing with a positive total duration.
    ///
    /// @param info the sequence metadata, or `null`
    /// @return whether the sequence timing can drive playback
    private boolean hasUsableSequenceTiming(@Nullable AvifSequenceInfo info) {
        if (info == null || info.mediaTimescale() <= 0) {
            return false;
        }
        int @Unmodifiable [] frameDurations = info.frameDurations();
        if (frameDurations.length != frames.size()) {
            return false;
        }
        for (int frameDuration : frameDurations) {
            if (frameDuration > 0) {
                return true;
            }
        }
        return false;
    }

    /// Converts an AVIS repetition count to a JavaFX timeline cycle count.
    ///
    /// @param info the sequence repetition metadata
    /// @return the JavaFX cycle count
    private static int sequenceCycleCount(AvifSequenceInfo info) {
        int repetitionCount = info.repetitionCount();
        if (repetitionCount == AvifSequenceInfo.REPETITION_COUNT_UNKNOWN
                || repetitionCount == AvifSequenceInfo.REPETITION_COUNT_INFINITE) {
            return Animation.INDEFINITE;
        }
        return repetitionCount == Integer.MAX_VALUE ? Integer.MAX_VALUE : repetitionCount + 1;
    }

    /// Returns the first frame after validating that the source list is non-empty.
    ///
    /// @param frames the source frames
    /// @return the first frame
    /// @throws IllegalArgumentException if the list is empty
    private static AvifFrame firstFrame(List<AvifFrame> frames) {
        Objects.requireNonNull(frames, "frames");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames is empty");
        }
        return Objects.requireNonNull(frames.get(0), "frames[0]");
    }

    /// Verifies that every decoded frame fits the JavaFX image allocated from the first frame.
    ///
    /// @throws IllegalArgumentException if a frame has different dimensions
    private void validateFrameLayout() {
        AvifFrame firstFrame = frames.get(0);
        for (int i = 1; i < frames.size(); i++) {
            AvifFrame frame = frames.get(i);
            if (frame.width() != firstFrame.width() || frame.height() != firstFrame.height()) {
                throw new IllegalArgumentException(
                        "Frame " + i + " dimensions do not match the first frame: "
                                + frame.width() + "x" + frame.height() + " != "
                                + firstFrame.width() + "x" + firstFrame.height()
                );
            }
        }
    }
}
