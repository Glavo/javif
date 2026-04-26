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
