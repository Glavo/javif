# AVIF Support Plan

## Status

The AV1 decoder is the baseline decode engine. The next phase is to add an AVIF container layer in
`org.glavo.avif`, using the existing `org.glavo.avif.decode` implementation for AV1 OBU decoding.

## Scope

- Support AV1-backed AVIF still images, alpha auxiliary images, image grids, progressive/layered
  still images, and AVIS image sequences.
- Expose a reader-style public API from `org.glavo.avif`.
- Keep runtime dependencies limited to `java.base`.
- Explicitly reject unsupported features with stable errors instead of producing partial or
  approximate output.

Out of scope for this phase:

- AV2 decoding.
- AVIF encoding.
- Java ImageIO integration.
- Experimental minimized image boxes (`mif3` / `mini`).

## Public API

- Add `AvifImageReader` with factory methods for `byte[]`, `ByteBuffer`, `InputStream`,
  `ReadableByteChannel`, and `Path`.
- Add sequential and indexed frame access: `info()`, `readFrame()`, `readFrame(int index)`, and
  `readAllFrames()`.
- Add immutable output models for image info, frame timing, color metadata, transforms, and ARGB
  frame data.
- Reuse `Av1DecoderConfig` internally through an AVIF-level configuration object.

## Implementation Work

- Add a big-endian BMFF parser for box headers, bounds checks, top-level `ftyp`, `meta`, `moov`,
  `mdat`, and nested AVIF/AVIS boxes.
- Port libavif's primary item path for `pitm`, `iloc`, `iinf/infe`, `iprp/ipco/ipma`, `iref`,
  `idat`, `ispe`, `pixi`, `av1C`, `auxC`, `colr`, `pasp`, `clap`, `irot`, `imir`, and `grid`.
- Extract item extents and track samples into self-delimited AV1 OBU samples consumable by
  `Av1ImageReader`.
- Refactor the AV1 reader internally so AVIF can obtain postprocessed `DecodedPlanes`, not only
  public opaque ARGB frames.
- Compose color, alpha, grids, and transforms into final non-premultiplied ARGB output.
- Parse AVIS tracks, sample tables, frame timing, sync samples, and nearest-keyframe seek state.
- Preserve metadata needed by callers: dimensions, bit depth, pixel format, color information,
  alpha presence, frame count, timing, and transform flags.

## Validation

- Add BMFF parser unit tests for valid boxes, truncation, overflow, duplicate unique boxes, and
  unsupported essential properties.
- Add synthetic AVIF fixtures that wrap existing raw AV1 OBU streams for still image, alpha, grid,
  progressive, and malformed metadata paths.
- Add integration tests with selected `external/libavif/tests/data/*.avif` fixtures covering real
  still images, alpha, grid, and animation.
- Verify with `./gradlew -g .gradle-user-home compileJava compileTestJava test`.

## Completion Criteria

- `org.glavo.avif` is exported and provides a stable public reader API.
- Common AVIF still, alpha, grid, progressive, and animated AV1 files decode through the Java AV1
  engine.
- Unsupported container or codec features fail predictably with clear diagnostics.
- The full Gradle validation command passes with workspace-local Gradle user home.
