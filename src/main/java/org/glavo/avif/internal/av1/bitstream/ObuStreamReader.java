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
package org.glavo.avif.internal.av1.bitstream;

import org.glavo.avif.decode.DecodeErrorCode;
import org.glavo.avif.decode.DecodeException;
import org.glavo.avif.decode.DecodeStage;
import org.glavo.avif.internal.io.BufferedInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/// Sequential OBU reader for raw AV1 low-overhead bitstreams.
@NotNullByDefault
public final class ObuStreamReader {
    /// The largest OBU payload representable by the decoder's byte-array storage.
    private static final int MAX_PAYLOAD_SIZE = Integer.MAX_VALUE - 8;

    /// The forward-only buffered byte source.
    private final BufferedInput input;
    /// The next unread byte offset in the source.
    private long streamOffset;
    /// The next unread OBU index in the source.
    private int obuIndex;
    /// Whether end-of-stream has already been observed.
    private boolean endOfStream;

    /// Creates a sequential OBU reader.
    ///
    /// @param input the forward-only buffered byte source
    public ObuStreamReader(BufferedInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /// Reads the next OBU packet from the source.
    ///
    /// @return the next OBU packet, or `null` at end-of-stream
    /// @throws IOException if the source is truncated or the OBU is malformed
    public @Nullable ObuPacket readObu() throws IOException {
        if (endOfStream) {
            return null;
        }

        long obuOffset = streamOffset;
        int currentObuIndex = obuIndex;
        long unitRemainingAtObuStart = input.currentUnitRemaining();

        final int firstByte;
        try {
            firstByte = input.readUnsignedByte();
        } catch (EOFException ignored) {
            endOfStream = true;
            return null;
        }
        streamOffset++;

        if ((firstByte & 0x80) != 0) {
            throw invalidHeader("OBU forbidden bit must be zero", obuOffset, currentObuIndex);
        }

        int typeId = (firstByte >>> 3) & 0x0F;
        boolean extensionFlag = ((firstByte >>> 2) & 1) != 0;
        boolean hasSizeField = ((firstByte >>> 1) & 1) != 0;
        boolean reservedBit = (firstByte & 1) != 0;

        if (reservedBit) {
            throw invalidHeader("OBU reserved bit must be zero", obuOffset, currentObuIndex);
        }

        ObuType type = ObuType.fromId(typeId);
        if (type == null) {
            throw invalidHeader("Unsupported OBU type: " + typeId, obuOffset, currentObuIndex);
        }

        int temporalId = 0;
        int spatialId = 0;
        if (extensionFlag) {
            int extensionByte = readUnsignedByte(DecodeErrorCode.UNEXPECTED_EOF, "Unexpected end of OBU extension", obuOffset, currentObuIndex);
            streamOffset++;
            temporalId = (extensionByte >>> 5) & 0x07;
            spatialId = (extensionByte >>> 3) & 0x03;
            if ((extensionByte & 0x07) != 0) {
                throw invalidHeader("OBU extension reserved bits must be zero", obuOffset, currentObuIndex);
            }
        }

        long payloadSize = -1L;
        if (hasSizeField) {
            Leb128.ReadResult sizeResult;
            try {
                sizeResult = Leb128.readUnsigned(input, 8);
            } catch (EOFException ex) {
                throw new DecodeException(
                        DecodeErrorCode.UNEXPECTED_EOF,
                        DecodeStage.OBU_READ,
                        "Unexpected end of OBU size field",
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            } catch (IOException ex) {
                throw new DecodeException(
                        DecodeErrorCode.INVALID_LEB128,
                        DecodeStage.OBU_READ,
                        ex.getMessage(),
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            }
            streamOffset += sizeResult.byteCount();
            payloadSize = sizeResult.value();
        }

        long headerSize = streamOffset - obuOffset;
        if (unitRemainingAtObuStart >= 0L && headerSize > unitRemainingAtObuStart) {
            throw new DecodeException(
                    DecodeErrorCode.UNEXPECTED_EOF,
                    DecodeStage.OBU_READ,
                    "OBU header exceeds its externally bounded input unit",
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }
        @Nullable byte[] unboundedPayload = null;
        if (!hasSizeField) {
            if (unitRemainingAtObuStart < 0L) {
                unboundedPayload = readPayloadToEnd(obuOffset, currentObuIndex);
                payloadSize = unboundedPayload.length;
                endOfStream = true;
            } else {
                payloadSize = unitRemainingAtObuStart - headerSize;
            }
        } else if (unitRemainingAtObuStart >= 0L
                && payloadSize > unitRemainingAtObuStart - headerSize) {
            throw new DecodeException(
                    DecodeErrorCode.UNEXPECTED_EOF,
                    DecodeStage.OBU_READ,
                    "OBU payload exceeds its externally bounded input unit",
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }
        if (payloadSize > MAX_PAYLOAD_SIZE) {
            throw new DecodeException(
                    DecodeErrorCode.INVALID_BITSTREAM,
                    DecodeStage.OBU_READ,
                    "OBU payload exceeds the supported allocation size: " + payloadSize,
                    obuOffset,
                    currentObuIndex,
                    null
            );
        }

        byte[] payload;
        if (unboundedPayload != null) {
            payload = unboundedPayload;
        } else {
            try {
                payload = input.readByteArray((int) payloadSize);
            } catch (EOFException ex) {
                throw new DecodeException(
                        DecodeErrorCode.UNEXPECTED_EOF,
                        DecodeStage.OBU_READ,
                        "Unexpected end of OBU payload",
                        obuOffset,
                        currentObuIndex,
                        null,
                        ex
                );
            }
        }
        streamOffset += payloadSize;
        obuIndex++;

        return new ObuPacket(
                new ObuHeader(type, extensionFlag, hasSizeField, temporalId, spatialId),
                payload,
                obuOffset,
                currentObuIndex
        );
    }

    /// Reads a size-less final OBU payload until the backing input reaches EOF.
    ///
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the payload bytes consumed before EOF
    /// @throws IOException if the input fails before reaching EOF or the payload is too large
    private byte[] readPayloadToEnd(long obuOffset, int currentObuIndex) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        while (true) {
            final int value;
            try {
                value = input.readUnsignedByte();
            } catch (EOFException ignored) {
                return payload.toByteArray();
            }
            if (payload.size() == MAX_PAYLOAD_SIZE) {
                throw new DecodeException(
                        DecodeErrorCode.INVALID_BITSTREAM,
                        DecodeStage.OBU_READ,
                        "OBU payload exceeds the supported allocation size: " + (MAX_PAYLOAD_SIZE + 1L),
                        obuOffset,
                        currentObuIndex,
                        null
                );
            }
            payload.write(value);
        }
    }

    /// Reads the next unsigned byte or throws a contextual decode exception.
    ///
    /// @param errorCode the error code to use if EOF is encountered
    /// @param message the contextual error message
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the next unsigned byte
    /// @throws IOException if the source is truncated or unreadable
    private int readUnsignedByte(
            DecodeErrorCode errorCode,
            String message,
            long obuOffset,
            int currentObuIndex
    ) throws IOException {
        try {
            return input.readUnsignedByte();
        } catch (EOFException ex) {
            throw new DecodeException(
                    errorCode,
                    DecodeStage.OBU_READ,
                    message,
                    obuOffset,
                    currentObuIndex,
                    null,
                    ex
            );
        }
    }

    /// Creates a contextual invalid-header exception.
    ///
    /// @param message the detailed error message
    /// @param obuOffset the OBU header offset
    /// @param currentObuIndex the OBU index
    /// @return the contextual invalid-header exception
    private static DecodeException invalidHeader(String message, long obuOffset, int currentObuIndex) {
        return new DecodeException(
                DecodeErrorCode.INVALID_OBU_HEADER,
                DecodeStage.OBU_READ,
                message,
                obuOffset,
                currentObuIndex,
                null
        );
    }
}
