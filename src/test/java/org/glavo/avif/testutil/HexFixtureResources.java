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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Loads small deterministic test fixtures stored as ASCII hex resources.
@NotNullByDefault
public final class HexFixtureResources {
    /// Prevents instantiation.
    private HexFixtureResources() {
    }

    /// Reads one hex-encoded resource into a new byte array.
    ///
    /// Non-hex characters are ignored so fixtures can use whitespace for readability.
    ///
    /// @param resourcePath the classpath-relative resource path
    /// @return the decoded fixture bytes
    public static byte[] readBytes(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream input = HexFixtureResources.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + resourcePath);
            }

            String text = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            StringBuilder hex = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (Character.digit(ch, 16) >= 0) {
                    hex.append(ch);
                }
            }
            if ((hex.length() & 1) != 0) {
                throw new IllegalArgumentException("Odd number of hex digits in test resource: " + resourcePath);
            }

            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int high = Character.digit(hex.charAt(i * 2), 16);
                int low = Character.digit(hex.charAt(i * 2 + 1), 16);
                bytes[i] = (byte) ((high << 4) | low);
            }
            return bytes;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read test resource: " + resourcePath, ex);
        }
    }
}
