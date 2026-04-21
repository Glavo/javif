# AV1 Decoder Port Plan

## Summary

Port `external/dav1d` into `org.glavo.avif.decode` as a pure Java AV1 decoder, using upstream commit `c5726277ffa8764665ea08f865e46912a41f2309` as the fixed baseline.

The port should preserve dav1d's main decoding behavior, but expose only high-level Java APIs. Decoder input must reuse the existing `org.glavo.avif.internal.io.BufferedInput` abstraction. Internal parsers and decoder subsystems must not depend on raw `InputStream`.

The decoder should output non-premultiplied ARGB pixels:

- 8-bit frames use `int[]` pixels in `0xAARRGGBB` format.
- 10-bit and 12-bit frames use `long[]` pixels in `0xAAAA_RRRR_GGGG_BBBB` format.

## Public API

The public API should be source-oriented rather than packet-oriented.

- `Av1ImageReader`
  - `static Av1ImageReader open(BufferedInput source)`
  - `@Nullable DecodedFrame readFrame()`
  - `List<DecodedFrame> readAllFrames()`
  - `void close()`
- `Av1DecoderConfig`
  - `applyFilmGrain`
  - `strictStdCompliance`
  - `outputInvisibleFrames`
  - `decodeFrameType`
  - `operatingPoint`
  - `allLayers`
  - `frameSizeLimit`
  - `threadCount`
- `DecodedFrame`
  - shared metadata: `width`, `height`, `bitDepth`, `pixelFormat`, `frameType`, `visible`, `presentationIndex`, `sequenceInfo`, `frameInfo`
- `ArgbIntFrame`
  - `int[] pixels`
- `ArgbLongFrame`
  - `long[] pixels`
- `DecodeException`
  - stable error code
  - decode stage
  - source location or packet/frame context when available

Constraints:

- `Av1ImageReader` only accepts `BufferedInput`.
- Any `InputStream`, `ReadableByteChannel`, or `ByteBuffer` adaptation happens before constructing the reader.
- Internal decoder code must only work with `BufferedInput` or higher-level wrappers built on top of it.
- Raw `InputStream` must not appear in internal parser or decoder logic.
- The first public version only targets raw AV1 OBU input, not AVIF container parsing.

## Internal Architecture

### Input Layer

Reuse the existing `org.glavo.avif.internal.io.BufferedInput` as the only internal source abstraction:

- `BufferedInput`
- `BufferedInput.OfInputStream`
- `BufferedInput.OfByteChannel`
- `BufferedInput.OfByteBuffer`

Responsibilities:

- keep a reusable staging buffer
- provide exact reads and exact skips
- hide short-read and EOF behavior of underlying sources
- support forward-only parsing without exposing transport details

This layer must stay generic and codec-agnostic. AV1-specific logic belongs in higher layers.

### Bitstream Layer

Implement AV1-specific readers on top of `BufferedInput`:

- `ObuStreamReader`
  - sequentially reads OBU headers, sizes, and payloads
- `BitReader`
  - bit-level reading within an OBU payload
- `MsacDecoder`
  - arithmetic decoding
- `CdfContext`
  - probability state

Nested payload parsing should use sliced buffers wrapped by `BufferedInput.OfByteBuffer` when that keeps the implementation simpler and more uniform.

### Model and Runtime Layer

The internal model should remain close to dav1d naming and structure so that source files can be ported with minimal semantic drift.

Subsystem split:

- `internal.model`
  - sequence, frame, tile, reference, metadata, and task state
- `internal.decode`
  - OBU parsing, frame init, block decode, reconstruction, reference updates
- `internal.postfilter`
  - deblock, CDEF, restoration, film grain
- `internal.runtime`
  - reader state machine, frame queue, drain/flush behavior, serial executor, future parallel executor

No architecture-specific SIMD or native code should be ported.

All bit-depth template logic from dav1d should be unified into parameterized Java implementations rather than duplicated code paths.

## Functional Targets

The first functional milestone must include:

- multi-frame AV1 OBU stream decoding
- 8-bit, 10-bit, and 12-bit input
- I400, I420, I422, and I444
- key, intra, inter, and switch frames
- reference frame management
- show-existing-frame handling
- invisible frame handling
- super-resolution
- loopfilter
- CDEF
- restoration
- film grain
- non-premultiplied ARGB output
- full serial execution path

Threading strategy:

- design the runtime around task/executor abstractions from the beginning
- make the first stable implementation serial
- preserve a compatible path for future tile/frame parallel execution

## Implementation Order

1. Reuse the existing `BufferedInput` abstraction and align all decoder input code to it.
2. Add the public reader skeleton and configuration/error/result models.
3. Implement sequential OBU reading on top of `BufferedInput`.
4. Port sequence header, frame header, tile header, and metadata parsing.
5. Port the core frame decode path, including reference management and reconstruction.
6. Port post-processing: deblock, CDEF, restoration, and film grain.
7. Add ARGB output conversion for both `int[]` and `long[]`.
8. Integrate the reader state machine, drain logic, and frame production.
9. Add the serial task executor and leave stable extension points for parallel execution.
10. Expand regression and oracle-based validation against dav1d behavior.

## Test Plan

### Buffered Input Tests

- `OfInputStream`, `OfByteChannel`, and `OfByteBuffer` behave consistently for reads, skips, EOF, and close behavior
- boundary conditions for `ensureBufferRemaining`, `readByteArray`, and `skip`

### Source Equivalence Tests

- the same AV1 OBU stream produces identical decode results when `Av1ImageReader` is given `BufferedInput.OfInputStream`, `BufferedInput.OfByteChannel`, or `BufferedInput.OfByteBuffer`

### Bitstream Tests

- OBU header parsing
- size parsing
- sequence header parsing
- frame header parsing
- metadata parsing
- operating point handling

### Decode Correctness Tests

- 8-bit, 10-bit, and 12-bit content
- I400, I420, I422, and I444 content
- visible and invisible frames
- show-existing-frame
- super-resolution
- loopfilter
- CDEF
- restoration
- film grain on and off

### Output Tests

- `ArgbIntFrame` uses packed `0xAARRGGBB`
- `ArgbLongFrame` uses packed `0xAAAA_RRRR_GGGG_BBBB`
- alpha is opaque
- output is non-premultiplied

### State Machine Tests

- repeated `readFrame()` until EOS
- `readAllFrames()` matches incremental reading
- truncated and malformed streams report stable errors

### Executor Tests

- serial execution produces stable output
- future parallel execution must match serial output bit-for-bit at the public result level

## Assumptions

- The upstream baseline remains fixed at `c5726277ffa8764665ea08f865e46912a41f2309` during the initial port.
- The initial scope is raw AV1 OBU input only.
- AVIF container parsing is a separate follow-up task.
- Runtime code must not depend on any module outside `java.base`.
- `org.jetbrains:annotations` remains compile-time only.
- All new Java code must follow repository rules:
  - `@NotNullByDefault`
  - explicit `@Nullable`
  - `///` Markdown-style Javadoc
  - English-only code, comments, names, logs, and configuration text
