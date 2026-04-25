# AVIF Support Plan

## Baseline

The project has a pure-Java AVIF reader backed by the in-tree AV1 decoder and no
runtime dependency beyond `java.base`.

Supported public behavior now includes still images, grids, progressive still
images, basic AVIS sequences, embedded ICC/Exif/XMP metadata, structured
auxiliary-image metadata, gain-map descriptors, random indexed AVIS reads, and
AVIS sample tables with `stsc`, `stco`, and `co64` chunk layouts plus basic
`stts` sample coverage validation, media-duration reconciliation, and edit-list
repetition metadata. Public raw plane access is available for:

- color still images, grids, and AVIS frames
- alpha single items, grids, and AVIS auxiliary tracks
- depth single items, grids, and AVIS auxiliary tracks
- standalone and grid-derived gain-map images

The remaining work is focused on decoder correctness, AVIF display semantics,
and validation breadth.

## Remaining Work

### Decoder Correctness

- Close the remaining `I444` pixel-accuracy gap with broader reference-image
  assertions.
- Implement or remove AV1 reconstruction boundaries that can still fail as
  `NOT_IMPLEMENTED`, especially CFL chroma prediction, inter palette blocks,
  unsupported inter-intra block sizes, and remaining inverse-transform sizes.
- Audit inter prediction, compound prediction, warped motion, restoration,
  super-resolution, CDEF, loop filter, and film grain against dav1d behavior.
- Add regression fixtures for real-world AVIF files that parse successfully but
  still decode with visible corruption.

### Container And Display Semantics

- Implement full color management: ICC application, nclx primaries/transfer/
  matrix handling, range conversion, and display-ready RGB adaptation for
  8/10/12-bit inputs.
- Implement gain-map tone mapping and display adaptation on top of the existing
  gain-map descriptors and raw gain-map plane decoding.
- Improve AVIS sequence handling for remaining multi-track edge cases.
- Define and test layered/scalable AVIF and operating-point selection.
- Add incremental or streaming input behavior instead of requiring the complete
  source to be buffered before parsing.

### API And Validation

- Expose remaining metadata through typed public descriptors where useful.
- Complete explicit output controls for display-converted RGB and high-bit-depth
  buffers without unnecessary copies.
- Audit alpha semantics, premultiplication metadata, and public buffer
  immutability annotations.
- Port remaining relevant libavif and dav1d tests into Java tests using
  resources under `src/test/resources`, not files under `external`.
- Add reference comparisons using Java ImageIO for images and JavaCPP FFmpeg
  bindings for videos where appropriate.
- Keep unsupported cases covered by stable `AvifDecodeException` or
  `DecodeException` error-code assertions until implemented.
- Run `./gradlew -g .gradle-user-home compileJava compileTestJava test` before
  considering a plan item complete.
