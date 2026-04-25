# AVIF Support Plan

## Status

The first AVIF still-image slice is implemented. `org.glavo.avif` is exported and provides a
reader API that parses a primary AV1 image item from a BMFF AVIF container, extracts its payload,
and decodes it through the migrated `org.glavo.avif.decode` AV1 reader.

Implemented:

- Public reader entry points for `byte[]`, `ByteBuffer`, `InputStream`, `ReadableByteChannel`, and
  `Path`.
- Immutable public models for decoder configuration, image info, color info, decoded frames, and
  decode errors.
- A bounded big-endian BMFF parser for `ftyp`, root `meta`, `hdlr`, `pitm`, `iloc`, `iinf/infe`,
  `iprp/ipco/ipma`, `iref`, `idat`, `ispe`, `av1C`, `colr nclx`, and `auxC`.
- Primary `av01` item extraction from file-backed extents and `idat` construction-method extents.
- A simple still-image decode path that returns `AvifIntFrame` or `AvifLongFrame`.
- Synthetic AVIF tests covering minimal still-image metadata parsing and primary AV1 item decoding.
- A real libavif `white_1x1.avif` fixture test covering metadata parsing and the public read-frame
  path.

## Remaining Work

1. Harden the primary still-image path against real libavif fixtures.
   - Add parser tests for truncation, overflow, duplicate unique boxes, invalid references, missing
     required properties, and unsupported essential properties.
   - Expand real still-image fixture coverage beyond `white_1x1.avif` and fix compatibility gaps in
     currently parsed boxes.
   - Add support for `pixi`, `pasp`, `clap`, `irot`, and `imir` metadata needed by common files.
   - Track the current AV1 `I444` pixel-accuracy gap separately from AVIF container coverage.

2. Add alpha auxiliary images.
   - Resolve `auxl` references from the primary image to alpha items.
   - Decode alpha AV1 payloads independently.
   - Combine color and alpha planes into non-premultiplied ARGB output.

3. Add derived image support.
   - Parse and validate `grid` item payloads.
   - Decode grid cell items and compose the final canvas.
   - Apply clean-aperture, pixel-aspect-ratio, rotation, and mirror transforms in the final output.

4. Add progressive and layered still images.
   - Parse item references and properties that describe progressive/layered AVIF still images.
   - Expose deterministic frame ordering for layers while keeping `info()` accurate.
   - Reject unsupported layering modes with stable diagnostics.

5. Add AVIS image sequences.
   - Parse `moov`, tracks, sample tables, sync samples, durations, and sample dependencies.
   - Decode sequential and indexed frames using nearest-keyframe state.
   - Expose frame count, animation status, and timing metadata in the public API.

6. Improve AV1 integration for AVIF composition.
   - Add an internal path that exposes postprocessed planes before ARGB packing.
   - Reuse that plane path for alpha, grid, and transform composition.
   - Keep public AV1 reader behavior unchanged.

## Validation

- Focused checks:
  `./gradlew -g .gradle-user-home test --tests org.glavo.avif.AvifImageReaderTest`
- Full checks:
  `./gradlew -g .gradle-user-home compileJava compileTestJava test`

## Completion Criteria

- Common AVIF still, alpha, grid, progressive, and animated AV1 files decode through the Java AV1
  engine.
- Unsupported AVIF features fail predictably with `AvifDecodeException` and stable error codes.
- Runtime code keeps depending only on `java.base`.
- The full Gradle validation command passes with workspace-local Gradle user home.
