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
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Fully decoded AVIF content.
///
/// This type is the eager counterpart of [AvifImageReader]. It materializes every decoded frame
/// and exposes the immutable container metadata needed to interpret still and animated images.
@NotNullByDefault
public final class AvifImage {
    /// The immutable container metadata.
    private final AvifImageInfo info;
    /// The decoded frames in presentation order.
    private final @Unmodifiable List<AvifFrame> frames;

    /// Reads and fully decodes an AVIF file using the default reader factory.
    ///
    /// The file handle is closed before this method returns. The returned image does not retain
    /// the path or any open file resource.
    ///
    /// @param source the AVIF file path
    /// @return the fully decoded image
    /// @throws IOException if the file cannot be opened, parsed, or decoded
    public static AvifImage read(Path source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes an AVIF file using the supplied reader factory.
    ///
    /// The file handle is closed before this method returns. The returned image does not retain
    /// the path or any open file resource.
    ///
    /// @param source the AVIF file path
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the file cannot be opened, parsed, or decoded
    public static AvifImage read(Path source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Reads and fully decodes an AVIF stream using the default reader factory.
    ///
    /// The stream is borrowed and remains open. Decoding consumes input progressively through the
    /// final encoded frame and may read ahead by a bounded amount; trailing top-level boxes may
    /// remain unread. If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte stream
    /// @return the fully decoded image
    /// @throws IOException if the stream cannot be read, parsed, or decoded
    public static AvifImage read(InputStream source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes an AVIF stream using the supplied reader factory.
    ///
    /// The stream is borrowed and remains open. Decoding consumes input progressively through the
    /// final encoded frame and may read ahead by a bounded amount; trailing top-level boxes may
    /// remain unread. If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte stream
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the stream cannot be read, parsed, or decoded
    public static AvifImage read(InputStream source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Creates a fully decoded image from normalized immutable state.
    ///
    /// @param info the immutable container metadata
    /// @param frames the decoded frames in presentation order
    /// @throws IllegalArgumentException if the decoded frame count differs from the metadata
    private AvifImage(AvifImageInfo info, @Unmodifiable List<AvifFrame> frames) {
        this.info = Objects.requireNonNull(info, "info");
        this.frames = List.copyOf(frames);
        if (this.frames.size() != info.frameCount()) {
            throw new IllegalArgumentException(
                    "Decoded frame count does not match image metadata: "
                            + this.frames.size() + " != " + info.frameCount()
            );
        }
    }

    /// Returns the immutable container metadata.
    ///
    /// @return the immutable container metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns all decoded frames in presentation order.
    ///
    /// @return an immutable list containing every decoded frame
    public @Unmodifiable List<AvifFrame> frames() {
        return frames;
    }

    /// Returns the first decoded frame.
    ///
    /// @return the first decoded frame
    public AvifFrame firstFrame() {
        return frames.get(0);
    }

    /// Materializes one open reader as an immutable image.
    ///
    /// Metadata is captured before frame decoding so that progressive inputs need not revisit
    /// discarded container bytes.
    ///
    /// @param reader the open reader
    /// @return the fully decoded image
    /// @throws IOException if a frame cannot be decoded
    private static AvifImage collect(AvifImageReader reader) throws IOException {
        AvifImageInfo info = reader.info();
        return new AvifImage(info, reader.readAllFrames());
    }
}
