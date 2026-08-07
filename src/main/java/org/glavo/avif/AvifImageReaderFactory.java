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

import org.glavo.avif.decode.Av1DecoderConfig;
import org.glavo.avif.internal.io.RandomAccessDataSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Creates AVIF image readers with an immutable reusable set of decoding options.
@NotNullByDefault
public final class AvifImageReaderFactory {
    /// The default reader factory.
    public static final AvifImageReaderFactory DEFAULT = new AvifImageReaderFactory(
            Av1DecoderConfig.DEFAULT,
            null,
            0
    );

    /// The chunk size used when accumulating stream and channel inputs.
    private static final int INPUT_READ_BUFFER_SIZE = 8192;
    /// The maximum non-seekable input prefix retained in memory before spooling to a file.
    private static final int MEMORY_SPOOL_THRESHOLD = 8 * 1024 * 1024;
    /// The maximum encoded input size supported by the integer-offset BMFF parser.
    private static final long MAX_SUPPORTED_INPUT_SIZE = Integer.MAX_VALUE - 8L;

    /// The underlying AV1 decoder configuration.
    private final Av1DecoderConfig av1DecoderConfig;
    /// The configured packed ARGB output format, or `null` to select one from the source bit depth.
    private final @Nullable AvifPixelFormat outputPixelFormat;
    /// The maximum accepted encoded AVIF input size in bytes, or `0` for no limit.
    private final long inputSizeLimit;

    /// Creates a reader factory with validated options.
    ///
    /// @param av1DecoderConfig the underlying AV1 decoder configuration
    /// @param outputPixelFormat the packed ARGB output format, or `null` for automatic selection
    /// @param inputSizeLimit the maximum accepted encoded input size in bytes, or `0` for no limit
    private AvifImageReaderFactory(
            Av1DecoderConfig av1DecoderConfig,
            @Nullable AvifPixelFormat outputPixelFormat,
            long inputSizeLimit
    ) {
        this.av1DecoderConfig = Objects.requireNonNull(av1DecoderConfig, "av1DecoderConfig");
        this.outputPixelFormat = outputPixelFormat;
        this.inputSizeLimit = inputSizeLimit;
    }

    /// Returns the underlying AV1 decoder configuration.
    ///
    /// @return the underlying AV1 decoder configuration
    public Av1DecoderConfig av1DecoderConfig() {
        return av1DecoderConfig;
    }

    /// Returns the configured packed ARGB output format.
    ///
    /// A `null` value selects [AvifPixelFormat#defaultFor(AvifBitDepth)] after the source bit depth
    /// is known.
    ///
    /// @return the configured output format, or `null` for automatic selection
    public @Nullable AvifPixelFormat outputPixelFormat() {
        return outputPixelFormat;
    }

    /// Returns the maximum accepted encoded AVIF input size.
    ///
    /// A value of `0` means that readers created by this factory do not apply an additional
    /// configured limit. The parser's implementation limit still applies.
    ///
    /// @return the maximum accepted encoded AVIF input size in bytes, or `0` for no limit
    public long inputSizeLimit() {
        return inputSizeLimit;
    }

    /// Returns a factory using the supplied AV1 decoder configuration.
    ///
    /// @param value the underlying AV1 decoder configuration
    /// @return a factory with the supplied AV1 decoder configuration
    public AvifImageReaderFactory withAv1DecoderConfig(Av1DecoderConfig value) {
        Av1DecoderConfig checkedValue = Objects.requireNonNull(value, "value");
        return checkedValue == av1DecoderConfig
                ? this
                : new AvifImageReaderFactory(checkedValue, outputPixelFormat, inputSizeLimit);
    }

    /// Returns a factory using the supplied packed ARGB output format.
    ///
    /// Passing `null` enables automatic selection: 8-bit sources use `ARGB_8888` and
    /// higher-bit-depth sources use `ARGB_16161616`.
    ///
    /// @param value the requested output format, or `null` for automatic selection
    /// @return a factory with the supplied output format
    public AvifImageReaderFactory withOutputPixelFormat(@Nullable AvifPixelFormat value) {
        return value == outputPixelFormat
                ? this
                : new AvifImageReaderFactory(av1DecoderConfig, value, inputSizeLimit);
    }

    /// Returns a factory using the supplied maximum encoded input size.
    ///
    /// A value of `0` disables the configured limit. Positive values are applied before retaining
    /// array, buffer, and path inputs and while spooling stream and channel inputs. The parser's
    /// implementation limit still applies when this value is `0`.
    ///
    /// @param value the maximum accepted encoded input size in bytes, or `0` for no limit
    /// @return a factory with the supplied input-size limit
    public AvifImageReaderFactory withInputSizeLimit(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("inputSizeLimit must be non-negative");
        }
        return value == inputSizeLimit
                ? this
                : new AvifImageReaderFactory(av1DecoderConfig, outputPixelFormat, value);
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// The source is copied before this method returns. Subsequent source mutations do not affect
    /// the reader.
    ///
    /// @param source the complete AVIF source bytes
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the input exceeds the configured limit or is not a supported
    ///                              AVIF container
    public AvifImageReader open(byte[] source) throws AvifDecodeException {
        byte[] checkedSource = Objects.requireNonNull(source, "source");
        validateInputSize(checkedSource.length);
        return new AvifImageReader(RandomAccessDataSource.ofOwnedBytes(checkedSource.clone()), this);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// Bytes from the source's current position to its limit are copied without changing the
    /// source position or limit.
    ///
    /// @param source the source byte buffer
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the input exceeds the configured limit or is not a supported
    ///                              AVIF container
    public AvifImageReader open(ByteBuffer source) throws AvifDecodeException {
        ByteBuffer copy = Objects.requireNonNull(source, "source").slice();
        validateInputSize(copy.remaining());
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new AvifImageReader(RandomAccessDataSource.ofOwnedBytes(bytes), this);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// This method reads the stream through end-of-stream but does not close it. Inputs larger than
    /// an internal memory threshold are spooled to a temporary file owned by the returned reader.
    /// If reading fails, the consumed prefix remains consumed.
    ///
    /// @param source the source input stream
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    public AvifImageReader open(InputStream source) throws IOException {
        RandomAccessDataSource retainedSource = spoolInput(Objects.requireNonNull(source, "source"));
        return new AvifImageReader(retainedSource, this);
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// This method reads the channel through end-of-stream but does not close it. Inputs larger
    /// than an internal memory threshold are spooled to a temporary file owned by the returned
    /// reader. If reading fails, the consumed prefix remains consumed.
    ///
    /// @param source the source byte channel
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    public AvifImageReader open(ReadableByteChannel source) throws IOException {
        RandomAccessDataSource retainedSource = spoolInput(
                Channels.newInputStream(Objects.requireNonNull(source, "source"))
        );
        return new AvifImageReader(retainedSource, this);
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// The returned reader owns an open read-only file handle and releases it from
    /// [AvifImageReader#close()]. The file must not be modified until the reader is closed.
    ///
    /// @param source the source file path
    /// @return a new AVIF image reader
    /// @throws IOException if the file cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    public AvifImageReader open(Path source) throws IOException {
        Path checkedSource = Objects.requireNonNull(source, "source");
        RandomAccessDataSource retainedSource = RandomAccessDataSource.open(checkedSource);
        try {
            validateInputSize(retainedSource.size());
            return new AvifImageReader(retainedSource, this);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                retainedSource.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Spools a non-seekable input into bounded memory or an owned temporary file.
    ///
    /// @param source the source input stream
    /// @return an owned random-access source
    /// @throws IOException if the source cannot be read or exceeds the configured limit
    private RandomAccessDataSource spoolInput(InputStream source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[INPUT_READ_BUFFER_SIZE];
        long totalBytes = 0;
        @Nullable Path temporaryFile = null;
        @Nullable OutputStream fileOutput = null;
        try {
            while (true) {
                int read = source.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("InputStream made no progress while reading AVIF input");
                }
                validateAdditionalInputSize(totalBytes, read);
                if (fileOutput == null && totalBytes + read > MEMORY_SPOOL_THRESHOLD) {
                    temporaryFile = Files.createTempFile("javif-input-", ".avif");
                    fileOutput = Files.newOutputStream(temporaryFile);
                    output.writeTo(fileOutput);
                }
                if (fileOutput != null) {
                    fileOutput.write(buffer, 0, read);
                } else {
                    output.write(buffer, 0, read);
                }
                totalBytes += read;
            }

            if (fileOutput == null) {
                return RandomAccessDataSource.ofOwnedBytes(output.toByteArray());
            }
            fileOutput.close();
            fileOutput = null;
            return RandomAccessDataSource.openTemporary(
                    Objects.requireNonNull(temporaryFile, "temporaryFile")
            );
        } catch (IOException | RuntimeException | Error exception) {
            if (fileOutput != null) {
                try {
                    fileOutput.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException deleteException) {
                    exception.addSuppressed(deleteException);
                }
            }
            throw exception;
        }
    }

    /// Validates a complete input size against this factory's limit.
    ///
    /// @param inputSize the complete input size in bytes
    /// @throws AvifDecodeException if the size exceeds the configured limit
    private void validateInputSize(long inputSize) throws AvifDecodeException {
        if (inputSize > maximumInputSize()) {
            throw inputTooLarge(maximumInputSize());
        }
    }

    /// Validates an incremental input-size increase against this factory's limit.
    ///
    /// @param currentSize the number of bytes already accepted
    /// @param additionalSize the number of additional bytes about to be accepted
    /// @throws AvifDecodeException if the new size would exceed the configured limit
    private void validateAdditionalInputSize(long currentSize, long additionalSize) throws AvifDecodeException {
        long maximumInputSize = maximumInputSize();
        if (additionalSize > maximumInputSize - currentSize) {
            throw inputTooLarge(maximumInputSize);
        }
    }

    /// Returns the effective configured and implementation input limit.
    ///
    /// @return the maximum supported input size in bytes
    private long maximumInputSize() {
        return inputSizeLimit == 0
                ? MAX_SUPPORTED_INPUT_SIZE
                : Math.min(inputSizeLimit, MAX_SUPPORTED_INPUT_SIZE);
    }

    /// Creates an input-too-large exception for an effective limit.
    ///
    /// @param maximumInputSize the effective maximum input size
    /// @return an input-too-large exception
    private static AvifDecodeException inputTooLarge(long maximumInputSize) {
        return new AvifDecodeException(
                AvifErrorCode.INPUT_TOO_LARGE,
                "AVIF input exceeds supported size limit: " + maximumInputSize + " bytes",
                null
        );
    }
}
