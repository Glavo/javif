// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff;

import org.jetbrains.annotations.NotNullByDefault;

/// Header for one BMFF box.
@NotNullByDefault
public final class BoxHeader {
    /// The four-character box type.
    private final String type;
    /// The absolute byte offset of the box header.
    private final int offset;
    /// The absolute byte offset of the box payload.
    private final int payloadOffset;
    /// The byte length of the box payload.
    private final int payloadSize;
    /// Whether the serialized box used a size field of zero.
    private final boolean sizeZero;

    /// Creates a BMFF box header.
    ///
    /// @param type the four-character box type
    /// @param offset the absolute byte offset of the box header
    /// @param payloadOffset the absolute byte offset of the box payload
    /// @param payloadSize the byte length of the box payload
    public BoxHeader(String type, int offset, int payloadOffset, int payloadSize) {
        this(type, offset, payloadOffset, payloadSize, false);
    }

    /// Creates a BMFF box header.
    ///
    /// @param type the four-character box type
    /// @param offset the absolute byte offset of the box header
    /// @param payloadOffset the absolute byte offset of the box payload
    /// @param payloadSize the byte length of the box payload
    /// @param sizeZero whether the serialized box used a size field of zero
    public BoxHeader(String type, int offset, int payloadOffset, int payloadSize, boolean sizeZero) {
        this.type = type;
        this.offset = offset;
        this.payloadOffset = payloadOffset;
        this.payloadSize = payloadSize;
        this.sizeZero = sizeZero;
    }

    /// Returns the four-character box type.
    ///
    /// @return the four-character box type
    public String type() {
        return type;
    }

    /// Returns the absolute byte offset of the box header.
    ///
    /// @return the absolute byte offset of the box header
    public int offset() {
        return offset;
    }

    /// Returns the absolute byte offset of the box payload.
    ///
    /// @return the absolute byte offset of the box payload
    public int payloadOffset() {
        return payloadOffset;
    }

    /// Returns the byte length of the box payload.
    ///
    /// @return the byte length of the box payload
    public int payloadSize() {
        return payloadSize;
    }

    /// Returns whether the serialized box used a size field of zero.
    ///
    /// @return whether the serialized box used a size field of zero
    public boolean sizeZero() {
        return sizeZero;
    }

    /// Returns the absolute byte offset immediately after this box.
    ///
    /// @return the absolute byte offset immediately after this box
    public int endOffset() {
        return payloadOffset + payloadSize;
    }
}
