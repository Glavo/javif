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
- AVIS/still/grid premultiplied-alpha metadata and straight-alpha frame output
- sequence alpha frame composition for `readFrame()`
- raw plane access for color, alpha, depth, and gain-map images

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

- Implement display-ready color management: ICC application, nclx primaries,
  transfer characteristics, matrix handling, range conversion, and RGB
  adaptation for 8/10/12-bit inputs.
- Implement gain-map tone mapping and display adaptation.
- Define and test layered/scalable AVIF and operating-point selection.

### Container And API

- Finish AVIS multi-track edge cases beyond handler, `auxl`, and `prem`
  handling: multiple compatible color tracks and unsupported non-auxiliary
  track-reference policies.
- Add incremental or streaming input behavior instead of requiring a complete
  buffered source.
- Expose remaining useful metadata through typed public descriptors.
- Complete explicit output controls for display-converted RGB and high-bit-depth
  buffers without unnecessary copies.
- Audit alpha edge cases and public buffer immutability annotations.

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
