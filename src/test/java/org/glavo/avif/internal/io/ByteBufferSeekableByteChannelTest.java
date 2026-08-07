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
package org.glavo.avif.internal.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the internal read-only seekable channel over borrowed buffer memory.
@NotNullByDefault
final class ByteBufferSeekableByteChannelTest {
    /// Verifies captured-region, positioning, read-only, closure, and borrowing behavior.
    ///
    /// @throws IOException if an expected channel operation fails
    @Test
    void implementsReadOnlySeekableChannelContract() throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(6);
        source.put(new byte[]{9, 1, 2, 3, 8, 7});
        source.position(1);
        source.limit(4);
        ByteBufferSeekableByteChannel channel = new ByteBufferSeekableByteChannel(source);

        assertTrue(channel.isOpen());
        assertEquals(3L, channel.size());
        assertEquals(0L, channel.position());

        ByteBuffer destination = ByteBuffer.allocate(2);
        assertEquals(2, channel.read(destination));
        assertArrayEquals(new byte[]{1, 2}, destination.array());
        assertEquals(2L, channel.position());
        assertEquals(1, source.position());
        assertEquals(4, source.limit());

        channel.position(1);
        destination.clear();
        assertEquals(2, channel.read(destination));
        assertArrayEquals(new byte[]{2, 3}, destination.array());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));

        channel.position(10);
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1));
        assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(1));

        channel.close();
        assertFalse(channel.isOpen());
        assertThrows(ClosedChannelException.class, channel::position);
        source.put(1, (byte) 6);
        assertEquals(6, source.get(1));
    }
}
