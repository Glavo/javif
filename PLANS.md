# AVIF Support Plan

## Current Baseline

The project has a pure-Java AVIF reader backed by the in-tree AV1 decoder. It
currently covers common still images, alpha, grids, progressive still images,
basic AVIS sequences, and embedded ICC, Exif, and XMP metadata exposure with no
runtime dependency beyond `java.base`. Reconstruction now uses frame-local
partition-tree views for full-frame output and postfilters, and has a regression
fixture for a left-edge Paeth corruption case in libavif `draw_points_idat.avif`.
Residual reconstruction applies AV1 luma/chroma quantization matrices, handles
extended coefficient token magnitudes, and has focused coverage for larger
`I420` and `I444` chroma-transform fixtures.
AVIS sequence support now includes random indexed frame reads that do not disturb
sequential playback state, and public metadata exposes sequence timescale,
duration, per-frame durations, primary-image transform properties, and auxiliary
image type strings. Auxiliary image metadata is also exposed through structured
descriptors that include item id, auxiliary type, item type, dimensions, bit
depth, and pixel format when available. The public reader can also expose raw
decoded color planes for still images, grid-derived still images, and AVIS
frames, plus raw luma planes for single alpha auxiliary items and alpha grids.
Image metadata now includes `tmap` gain-map descriptors when the `tmap` brand
and preferred `altr` relationship are present.

All AVIF files copied from libavif `tests/data` have explicit corpus
expectations. The remaining work is to improve correctness, broaden AVIF
semantics, and replace expected unsupported boundaries with implemented
behavior where the feature is in scope.

## Remaining Work

### AV1 Reconstruction Correctness

- Close the remaining `I444` pixel-accuracy gap with broader reference-image
  assertions; the current coverage only protects the `draw_points_idat.avif`
  left-edge Paeth regression.
- Implement or remove remaining reconstruction boundaries that can still fail
  as `NOT_IMPLEMENTED`, especially CFL chroma prediction, inter palette blocks,
  unsupported inter-intra block sizes, and any remaining inverse-transform size
  combinations.
- Audit inter prediction, compound prediction, warped motion, restoration,
  super-resolution, CDEF, loop filter, and film grain against dav1d behavior
  using focused reference fixtures.
- Add more regression fixtures for real-world AVIF files that currently decode
  with visible corruption even when parsing succeeds, especially larger still
  images where residual, transform, or postfilter behavior is still incomplete.

### AVIF Container Semantics

- Implement full color management: apply ICC profiles, transfer functions,
  nclx primaries/transfer/matrix handling, range conversion, and display-ready
  RGB adaptation across 8/10/12-bit inputs.
- Decode gain-map and depth-image planes through public APIs. Gain-map container
  descriptors are exposed, but gain-map pixel decoding, tone mapping, and depth
  image descriptors are still pending.
- Improve AVIS support beyond random access: timing accuracy validation, alpha
  sequences, and multi-sample edge cases.
- Add incremental/streaming input behavior instead of requiring the full source
  to be buffered before parsing.
- Define and test behavior for layered/scalable AVIF and operating-point
  selection instead of relying on broad unsupported-feature failures.

### Public API And Metadata

- Expose remaining image metadata, especially depth descriptors and decoded
  gain-map planes where they belong in the public API.
- Complete explicit output choices for sequence alpha planes, display-converted
  RGB, and high-bit-depth buffers without unnecessary copies.
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
