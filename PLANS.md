# AVIF Support Plan

## Current Baseline

The project has a pure-Java AVIF reader backed by the in-tree AV1 decoder. It
currently covers common still images, alpha, grids, progressive still images,
basic AVIS sequences, and embedded ICC, Exif, and XMP metadata exposure with no
runtime dependency beyond `java.base`.

All AVIF files copied from libavif `tests/data` have explicit corpus
expectations. The remaining work is to improve correctness, broaden AVIF
semantics, and replace expected unsupported boundaries with implemented
behavior where the feature is in scope.

## Remaining Work

### AV1 Reconstruction Correctness

- Close the tracked `I444` pixel-accuracy gap with reference-image assertions.
- Implement or remove remaining reconstruction boundaries that can still fail
  as `NOT_IMPLEMENTED`, especially CFL chroma prediction, inter palette blocks,
  unsupported inter-intra block sizes, and any remaining inverse-transform size
  combinations.
- Audit inter prediction, compound prediction, warped motion, restoration,
  super-resolution, CDEF, loop filter, and film grain against dav1d behavior
  using focused reference fixtures.
- Add regression fixtures for real-world AVIF files that currently decode with
  visible corruption even when parsing succeeds.

### AVIF Container Semantics

- Implement full color management: apply ICC profiles, transfer functions,
  nclx primaries/transfer/matrix handling, range conversion, and display-ready
  RGB adaptation across 8/10/12-bit inputs.
- Decide the public API scope for gain maps and depth images. If included,
  parse and expose their metadata and decoded planes instead of only validating
  primary-image decode behavior.
- Improve AVIS support beyond sequential playback: random access, timing
  accuracy, alpha sequences, and multi-sample edge cases.
- Add incremental/streaming input behavior instead of requiring the full source
  to be buffered before parsing.
- Define and test behavior for layered/scalable AVIF and operating-point
  selection instead of relying on broad unsupported-feature failures.

### Public API And Metadata

- Expose remaining image metadata, especially transform properties, timing, and
  auxiliary image relationships.
- Provide explicit output choices for raw planes, display-converted RGB, and
  high-bit-depth buffers without unnecessary copies.
- Review alpha semantics, premultiplication metadata, and buffer immutability
  annotations across all public APIs.

### Validation

- Port the remaining relevant libavif and dav1d tests into Java tests using
  resources under `src/test/resources`, not files under `external`.
- Add reference comparisons using Java ImageIO for images and JavaCPP FFmpeg
  bindings for videos where appropriate.
- Keep unsupported cases covered by explicit tests with stable
  `AvifDecodeException` or `DecodeException` error codes until implemented.
- Run `./gradlew -g .gradle-user-home compileJava compileTestJava test` before
  considering a plan item complete.
