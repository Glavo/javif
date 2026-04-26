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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Immutable AVIF item property whose type is not interpreted by the reader.
@NotNullByDefault
public final class AvifImageItemProperty {
    /// The byte length of a UUID box user type.
    private static final int UUID_USER_TYPE_LENGTH = 16;

    /// The four-character property box type.
    private final String type;
    /// The UUID box user type, or `null` for non-UUID properties.
    private final @Nullable @Unmodifiable ByteBuffer userType;
    /// The property box payload after any UUID user type.
    private final @Unmodifiable ByteBuffer payload;

    /// Creates an immutable item property descriptor.
    ///
    /// @param type the four-character property box type
    /// @param userType the UUID box user type, or `null` for non-UUID properties
    /// @param payload the property box payload after any UUID user type
    public AvifImageItemProperty(String type, byte @Nullable [] userType, byte[] payload) {
        this.type = validateType(type);
        if ("uuid".equals(type)) {
            if (userType == null || userType.length != UUID_USER_TYPE_LENGTH) {
                throw new IllegalArgumentException("uuid item properties require a 16-byte userType");
            }
        } else if (userType != null) {
            throw new IllegalArgumentException("Only uuid item properties may carry userType");
        }

        this.userType = immutableBytes(userType);
        this.payload = Objects.requireNonNull(immutableBytes(Objects.requireNonNull(payload, "payload")));
    }

    /// Returns the four-character property box type.
    ///
    /// @return the four-character property box type
    public String type() {
        return type;
    }

    /// Returns the UUID box user type.
    ///
    /// Non-UUID properties return `null`.
    ///
    /// @return a read-only view of the UUID user type, or `null`
    public @Nullable @UnmodifiableView ByteBuffer userType() {
        return byteView(userType);
    }

    /// Returns the property box payload after any UUID user type.
    ///
    /// @return a read-only view of the property payload
    public @UnmodifiableView ByteBuffer payload() {
        return payload.slice();
    }

    /// Validates one four-character box type string.
    ///
    /// @param type the type string
    /// @return the validated type string
    private static String validateType(String type) {
        Objects.requireNonNull(type, "type");
        if (type.length() != 4) {
            throw new IllegalArgumentException("type must contain exactly four characters: " + type);
        }
        for (int i = 0; i < type.length(); i++) {
            if (type.charAt(i) > 0xFF) {
                throw new IllegalArgumentException("type must be an ISO-8859-1 four-character code: " + type);
            }
        }
        return type;
    }

    /// Creates immutable byte-buffer storage for one optional payload.
    ///
    /// @param bytes the source bytes, or `null`
    /// @return immutable byte-buffer storage, or `null`
    private static @Nullable @Unmodifiable ByteBuffer immutableBytes(byte @Nullable [] bytes) {
        if (bytes == null) {
            return null;
        }
        return ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length)).asReadOnlyBuffer();
    }

    /// Returns a read-only view over immutable payload storage.
    ///
    /// @param bytes the immutable payload storage, or `null`
    /// @return a read-only view, or `null`
    private static @Nullable @UnmodifiableView ByteBuffer byteView(@Nullable @Unmodifiable ByteBuffer bytes) {
        return bytes == null ? null : bytes.slice();
    }
}
