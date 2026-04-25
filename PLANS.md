# AVIF Support Plan

## Status

The primary still-image, alpha, grid, and progressive image paths are implemented.
`org.glavo.avif` is exported and provides a reader API that parses an AVIF container,
extracts AV1 item payloads, and decodes them through the migrated
`org.glavo.avif.decode` AV1 reader. Layered/scalable images with operating points are
detected and rejected with stable diagnostics.

Implemented:

- Public reader entry points for `byte[]`, `ByteBuffer`, `InputStream`, `ReadableByteChannel`, and
  `Path`.
- Immutable public models for decoder configuration, image info, color info, decoded frames, and
  decode errors.
- A bounded big-endian BMFF parser for `ftyp`, root `meta`, `hdlr`, `pitm`, `iloc`, `iinf/infe`,
  `iprp/ipco/ipma`, `iref` (with `auxl`, `dimg`, and `prog` reference types), `idat`,
  `ispe`, `av1C`, `colr nclx`, and `auxC`.
- Property parsing for `pixi`, `pasp`, `clap`, `irot`, `imir`, and `a1op`.
- Transform application for `clap` (cropping), `irot` (rotation), and `imir` (mirror) applied
  post-decode to the output canvas (8-bit frames only).
- Primary `av01` item extraction from file-backed extents and `idat` construction-method extents.
- A simple still-image decode path that returns `AvifIntFrame` or `AvifLongFrame`.
- Alpha auxiliary image support: resolves `auxl` references, decodes alpha AV1 payloads
  independently, and combines color and alpha planes into non-premultiplied ARGB output.
- Grid derived image support: resolves `dimg` references, decodes grid cell AV1 payloads, and
  composes the final canvas from row-major ordered cells (8-bit output only; 10/12-bit grid
  composition is deferred).
- Parser robustness tests covering truncation, overflow, duplicate unique boxes, invalid references,
  missing required properties, and essential unknown property rejection.
- Synthetic AVIF tests covering minimal still-image metadata parsing, primary AV1 item decoding,
  alpha decoding, parser error paths, grid composition, and irot rotation transforms.
- Real libavif fixture tests for `white_1x1.avif`, `extended_pixi.avif`, `colors_sdr_srgb.avif`,
   `paris_icc_exif_xmp.avif`, `abc_color_irot_alpha_NOirot.avif`, `sofa_grid1x5_420.avif`,
   and `draw_points_idat_progressive.avif`.
- Known gap: AV1 `I444` pixel-accuracy is tracked separately from AVIF container coverage.

## Remaining Work

1. Add AVIS image sequences.
   - Parse `moov`, tracks, sample tables, sync samples, durations, and sample dependencies.
   - Decode sequential and indexed frames using nearest-keyframe state.
   - Expose frame count, animation status, and timing metadata in the public API.

2. Improve AV1 integration for AVIF composition.
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
