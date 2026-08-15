// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.testutil;

import org.jetbrains.annotations.NotNullByDefault;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Loads classpath resources used by tests.
@NotNullByDefault
public final class TestResources {
    /// Prevents instantiation.
    private TestResources() {
    }

    /// Reads one classpath resource into a byte array.
    ///
    /// @param resourceName the classpath resource name
    /// @return the resource bytes
    /// @throws IOException if the resource cannot be read
    public static byte[] readBytes(String resourceName) throws IOException {
        Objects.requireNonNull(resourceName, "resourceName");
        try (InputStream input = TestResources.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /// Reads one ImageIO-supported classpath image resource.
    ///
    /// @param resourceName the classpath resource name
    /// @return the decoded image
    /// @throws IOException if the resource cannot be read
    public static BufferedImage readImage(String resourceName) throws IOException {
        Objects.requireNonNull(resourceName, "resourceName");
        try (InputStream input = TestResources.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + resourceName);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new AssertionError("ImageIO could not decode: " + resourceName);
            }
            return image;
        }
    }
}
