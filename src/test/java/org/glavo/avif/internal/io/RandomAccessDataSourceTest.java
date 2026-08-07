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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests owned memory and file-backed random-access data sources.
@NotNullByDefault
final class RandomAccessDataSourceTest {
    /// Verifies positional scalar and bulk reads over owned memory.
    ///
    /// @throws IOException if the source cannot be read or closed
    @Test
    void readsOwnedBytesAndRejectsReadsAfterClose() throws IOException {
        RandomAccessDataSource source = RandomAccessDataSource.ofOwnedBytes(new byte[]{1, 2, 3, 4, 5});
        assertEquals(5L, source.size());
        assertEquals(3, source.readByte(2));

        ByteBuffer destination = ByteBuffer.allocate(3);
        source.readFully(1, destination);
        assertArrayEquals(new byte[]{2, 3, 4}, destination.array());

        source.close();
        assertThrows(IOException.class, () -> source.readByte(0));
    }

    /// Verifies file-backed reads and release of the owned file handle.
    ///
    /// @throws IOException if the fixture cannot be created, read, closed, or deleted
    @Test
    void closesPersistentFileHandle() throws IOException {
        Path path = workspaceTempPath("persistent");
        Files.write(path, new byte[]{9, 8, 7, 6});
        RandomAccessDataSource source = RandomAccessDataSource.open(path);
        assertEquals(8, source.readByte(1));
        assertArrayEquals(new byte[]{7, 6}, source.readBytes(2, 2));

        source.close();
        Files.delete(path);
        assertFalse(Files.exists(path));
    }

    /// Verifies that closing a temporary source removes its owned spool file.
    ///
    /// @throws IOException if the fixture cannot be created, opened, or closed
    @Test
    void deletesTemporaryFileOnClose() throws IOException {
        Path path = workspaceTempPath("temporary");
        Files.write(path, new byte[]{4, 3, 2, 1});
        RandomAccessDataSource source = RandomAccessDataSource.openTemporary(path);
        assertTrue(Files.exists(path));
        assertEquals(4, source.readByte(0));

        source.close();
        assertFalse(Files.exists(path));
    }

    /// Creates a unique test path under the workspace-local build directory.
    ///
    /// @param name the logical test name
    /// @return the path, which does not yet exist
    /// @throws IOException if the parent directory cannot be created
    private static Path workspaceTempPath(String name) throws IOException {
        Path directory = Path.of("build", "tmp", "test", "RandomAccessDataSourceTest");
        Files.createDirectories(directory);
        return directory.resolve(name + "-" + System.nanoTime() + ".bin");
    }
}
