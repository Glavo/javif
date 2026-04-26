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

/// Immutable AVIF image transform metadata from `clap`, `irot`, and `imir` item properties.
@NotNullByDefault
public final class AvifImageTransformInfo {
    /// The clean-aperture crop x coordinate, or -1 when absent.
    private final int cleanApertureCropX;
    /// The clean-aperture crop y coordinate, or -1 when absent.
    private final int cleanApertureCropY;
    /// The clean-aperture crop width, or -1 when absent.
    private final int cleanApertureCropWidth;
    /// The clean-aperture crop height, or -1 when absent.
    private final int cleanApertureCropHeight;
    /// The clockwise rotation code from `irot`, or -1 when absent.
    private final int rotationCode;
    /// The mirror axis from `imir`, or -1 when absent.
    private final int mirrorAxis;

    /// Creates immutable image transform metadata.
    ///
    /// @param cleanApertureCropX the clean-aperture crop x coordinate, or -1 when absent
    /// @param cleanApertureCropY the clean-aperture crop y coordinate, or -1 when absent
    /// @param cleanApertureCropWidth the clean-aperture crop width, or -1 when absent
    /// @param cleanApertureCropHeight the clean-aperture crop height, or -1 when absent
    /// @param rotationCode the clockwise rotation code from `irot`, or -1 when absent
    /// @param mirrorAxis the mirror axis from `imir`, or -1 when absent
    public AvifImageTransformInfo(
            int cleanApertureCropX,
            int cleanApertureCropY,
            int cleanApertureCropWidth,
            int cleanApertureCropHeight,
            int rotationCode,
            int mirrorAxis
    ) {
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
        if (isAbsentCleanAperture(cleanApertureCropX, cleanApertureCropY, cleanApertureCropWidth, cleanApertureCropHeight)
                && rotationCode == -1 && mirrorAxis == -1) {
            throw new IllegalArgumentException("At least one image transform must be present");
        }

        this.cleanApertureCropX = cleanApertureCropX;
        this.cleanApertureCropY = cleanApertureCropY;
        this.cleanApertureCropWidth = cleanApertureCropWidth;
        this.cleanApertureCropHeight = cleanApertureCropHeight;
        this.rotationCode = rotationCode;
        this.mirrorAxis = mirrorAxis;
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

    /// Returns whether an `irot` rotation transform is present.
    ///
    /// @return whether an `irot` rotation transform is present
    public boolean hasRotation() {
        return rotationCode >= 0;
    }

    /// Returns the clockwise rotation code from `irot`.
    ///
    /// Values 0 through 3 represent 0, 90, 180, and 270 degrees clockwise.
    /// A value of -1 means the property is absent.
    ///
    /// @return the rotation code, or -1 when absent
    public int rotationCode() {
        return rotationCode;
    }

    /// Returns the clockwise rotation in degrees.
    ///
    /// A value of -1 means the `irot` property is absent.
    ///
    /// @return the clockwise rotation in degrees, or -1 when absent
    public int rotationDegreesClockwise() {
        return rotationCode < 0 ? -1 : rotationCode * 90;
    }

    /// Returns whether an `imir` mirror transform is present.
    ///
    /// @return whether an `imir` mirror transform is present
    public boolean hasMirror() {
        return mirrorAxis >= 0;
    }

    /// Returns the mirror axis from `imir`.
    ///
    /// A value of 0 mirrors over the vertical axis, 1 mirrors over the horizontal axis,
    /// and -1 means the property is absent.
    ///
    /// @return the mirror axis, or -1 when absent
    public int mirrorAxis() {
        return mirrorAxis;
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
}
