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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// JavaFX image adapter for decoded AVIF content.
///
/// The adapter writes packed non-premultiplied `ARGB` pixels from decoded [AvifFrame]
/// instances into a `WritableImage`. When constructed from a list of frames, it can also
/// play animated AVIF content with frame-accurate timing.
@NotNullByDefault
public final class AvifFXImage extends WritableImage {

    private final List<AvifFrame> frames;
    private final boolean animated;
    private @Nullable Timeline timeline;
    private int renderedFrameIndex = -1;

    /// Creates a JavaFX image from one decoded frame.
    ///
    /// @param frame the decoded frame to display
    public AvifFXImage(AvifFrame frame) {
        super(frame.width(), frame.height());
        this.frames = List.of(frame);
        this.animated = false;
        renderFrame(0);
    }

    /// Creates a JavaFX image from a list of decoded frames.
    ///
    /// The first frame is written immediately. Call [#getAnimation()] to control playback.
    ///
    /// @param frames  the decoded frames in presentation order
    /// @param autoPlay whether to start playing the animation automatically
    public AvifFXImage(List<AvifFrame> frames, boolean autoPlay) {
        this(frames, autoPlay, 30);
    }

    /// Creates a JavaFX image from a list of decoded frames with an explicit frame rate.
    ///
    /// @param frames  the decoded frames in presentation order
    /// @param autoPlay whether to start playing the animation automatically
    /// @param fps      the frames per second for playback timing
    public AvifFXImage(List<AvifFrame> frames, boolean autoPlay, int fps) {
        super(frames.get(0).width(), frames.get(0).height());
        this.frames = List.copyOf(frames);
        this.animated = frames.size() > 1;

        renderFrame(0);

        if (autoPlay && isAnimated()) {
            getAnimation(fps).play();
        }
    }

    /// Returns whether this image is animated.
    ///
    /// @return `true` if this image contains multiple frames.
    public boolean isAnimated() {
        return animated;
    }

    /// Returns the JavaFX timeline that drives this image's animation using a default 30 fps.
    ///
    /// @return the timeline, or `null` if not animated
    public @Nullable Timeline getAnimation() {
        return getAnimation(30);
    }

    /// Returns the JavaFX timeline that drives this image's animation.
    ///
    /// @param fps the frames per second for playback timing
    /// @return the timeline, or `null` if not animated
    public @Nullable Timeline getAnimation(int fps) {
        if (animated) {
            if (timeline == null) {
                double frameDurationMs = 1000.0 / Math.max(1, fps);
                timeline = new Timeline();
                timeline.setCycleCount(Animation.INDEFINITE);
                timeline.getKeyFrames().setAll(createKeyFrames(frameDurationMs));
            }
            return timeline;
        }
        return null;
    }

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

    private KeyFrame[] createKeyFrames(double frameDurationMs) {
        KeyFrame[] keyFrames = new KeyFrame[frames.size() + 1];
        double currentMs = 0.0;
        for (int i = 0; i < frames.size(); i++) {
            final int frameIndex = i;
            keyFrames[i] = new KeyFrame(Duration.millis(currentMs), event -> renderFrame(frameIndex));
            currentMs += frameDurationMs;
        }
        keyFrames[frames.size()] = new KeyFrame(Duration.millis(currentMs));
        return keyFrames;
    }
}
