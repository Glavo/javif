# AVIF Support Plan

## Current State

The project has a pure-Java AVIF reader backed by the in-tree AV1 decoder and no
runtime dependency beyond `java.base`.

Implemented container/API coverage:

- still images, grids, progressive still images, and basic AVIS sequences
- embedded ICC, Exif, and XMP metadata
- structured auxiliary-image metadata and gain-map descriptors
- random indexed AVIS reads
- AVIS `stsc`, `stco`, `co64`, `stts`, media-duration reconciliation, and
  edit-list repetition metadata
- AVIS media handler filtering for image, auxiliary-image, and unrelated tracks
- AVIS `tref/auxl` matching for alpha/depth auxiliary tracks
- explicit rejection for ambiguous AVIS color tracks and unsupported AVIS
  track-reference types
- AVIS/still/grid premultiplied-alpha metadata and straight-alpha frame output
- sequence alpha frame composition for `readFrame()`
- raw plane access for color, alpha, depth, and gain-map images
- typed public descriptors for AVIS sequence timing/repetition and AVIF image
  transforms
- explicit RGB output-mode controls for automatic, ARGB_8888, and
  ARGB_16161616 frame buffers across still, sequence, grid, alpha, and
  high-bit-depth paths
- bounded buffered input ingestion for byte arrays, `ByteBuffer`, `InputStream`,
  `ReadableByteChannel`, and `Path`, controlled by `AvifDecoderConfig.inputSizeLimit()`
- alpha edge-case validation for still images, grids, AVIS auxiliary tracks,
  premultiplied-alpha references, decoded alpha luma planes, and alpha-grid layout
- default AVIF operating-point semantics: `a1op` value `0` is accepted, non-default
  still/progressive/grid/auxiliary/gain-map operating points are rejected explicitly,
  and raw AV1 OBU streams honor `Av1DecoderConfig.operatingPoint()` layer filtering

## Remaining Work

### Decode Correctness

- Close the remaining `I444` pixel-accuracy gap with broader reference-image
  assertions.
- Finish or explicitly reject AV1 reconstruction paths that can still fail as
  `NOT_IMPLEMENTED`, especially CFL chroma prediction, inter palette blocks,
  unsupported inter-intra block sizes, and remaining inverse-transform sizes.
- Audit inter prediction, compound prediction, warped motion, restoration,
  super-resolution, CDEF, loop filter, and film grain against dav1d behavior.
- Add regression fixtures for real-world AVIF files that parse but still decode
  with visible corruption.

### Display Semantics

- Implement display-ready color management beyond the current nclx matrix/range
  conversion: ICC application, primaries/transfer adaptation, and RGB color-space
  conversion for 8/10/12-bit inputs.
- Implement gain-map tone mapping and display adaptation.
- Implement real layered/scalable AVIF output for selectable non-default operating
  points instead of the current explicit unsupported boundary.

### Validation

- Port remaining relevant libavif and dav1d tests into Java tests using
  resources under `src/test/resources`, not files under `external`.
- Add reference comparisons using Java ImageIO for images and JavaCPP FFmpeg
  bindings for videos where appropriate.
- Keep unsupported cases covered by stable `AvifDecodeException` or
  `DecodeException` error-code assertions until implemented.

## Completion Rule

Run `./gradlew -g .gradle-user-home compileJava compileTestJava test` before
considering a plan item complete.
