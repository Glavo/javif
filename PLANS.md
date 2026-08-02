# AVIF Decoder Status

## Scope

This repository provides a Java 17 decoding library for AV1 low-overhead bitstreams, AVIF still
images, and AVIS image sequences, together with an optional JavaFX image adapter and desktop
viewer. The core decoding API has no mandatory runtime dependencies outside `java.base`; JetBrains
annotations are compile-time-only metadata, while the JavaFX package requires JavaFX when used.

Encoders, command-line tools, native ABI compatibility, fuzzing binaries, and libavif application
parity are not project features.

## Implemented Decode Surface

- AV1 sequence and frame assembly, operating-point filtering, temporal/spatial layer identifiers,
  invisible/reference-frame policy, `show_existing_frame`, and bounded or EOF-delimited size-less
  final OBUs.
- 8-, 10-, and 12-bit `I400`, `I420`, `I422`, and `I444` reconstruction, including tiles,
  segmentation, palettes, `intrabc`, intra/inter/compound/inter-intra prediction, `refmvs`, global
  and local warped motion, OBMC, scaled references, transforms, quantization matrices, and
  super-resolution.
- The AV1 postprocessing pipeline: loop filtering, CDEF, loop restoration, and optional film-grain
  synthesis while preserving pre-grain reference surfaces.
- AVIF and AVIS BMFF parsing for AV1 items and tracks, item extents, progressive/layered images,
  `a1op`/`lsel`, image grids, Sample Transform derived images, alpha and depth auxiliaries, gain
  maps, Exif/XMP/ICC metadata, and `clap`/`irot`/`imir` presentation transforms.
- Raw YUV plane output, 8-bit and 16-bit packed ARGB output, CICP range/matrix/transfer/primary
  conversion, alpha premultiplication handling, and gain-map tone mapping.
- JavaFX adaptation for static and animated decoded frames, plus a desktop viewer available through
  the Gradle `run` task.
- Structural validation for item dimensions, grid syntax and geometry, auxiliary relationships,
  layer selection, sample tables, frame-size limits, and decoded `ispe` conformance.

## Deliberate Boundaries

- ICC profiles are exposed as immutable metadata but are not applied during pixel conversion. A
  standards-compliant ICC engine cannot be provided under the `java.base`-only runtime policy.
- Packed RGB conversion rejects explicit CICP matrix families that require unsupported nonlinear
  component transforms. Raw decoded planes remain available for callers with a specialized color
  pipeline.
- Coded primary, grid-cell, auxiliary, and gain-map image items must use AV1. Other BMFF codecs are
  reported as unsupported instead of being delegated to an external decoder.
- Unknown or reserved BMFF/AVIF versions, flags, essential properties, and CICP identifiers are
  rejected explicitly rather than guessed.

## Verification Baseline

- `processTestResources` downloads pinned libavif and `link-u/avif-sample-images` source archives
  and copies only their test fixtures and attribution files into generated test resources.
- The opt-in `aomAvifTest` task downloads a pinned `AOMediaCodec/av1-avif` source archive and tests
  all 172 AVIF files contributed by Apple, Link-U, Microsoft, Netflix, and Xiph. The task extracts
  only AVIF files and their licenses or construction notes; it does not add the large reference
  PNG set to the ordinary test classpath.
- All 156 AVIF/AVIFS files in `link-u/avif-sample-images` are decoded end to end. FFmpeg source-plane
  comparisons cover 153 of them; the remaining three are alpha-bearing sequences for which the
  test helper selects the auxiliary track instead of the color track.
- Libavif fixtures cover still images, sequences, grids, alpha/depth/gain-map relationships,
  progressive images, Sample Transform, HDR/WCG metadata, and reference pixel/plane comparisons.
- Unit and integration tests cover entropy decoding, syntax contexts, prediction, reconstruction,
  postfilters, output conversion, container validation, public API lifecycle, and input adapters.
- No decoder-correctness test is disabled or retained as an expected failure.
- The release gate is:

  ```text
  ./gradlew -g .gradle-user-home cleanTest test javadoc jar
  ./gradlew -g .gradle-user-home dependencies --configuration runtimeClasspath
  ```

  The second command must continue to show `No dependencies` for `runtimeClasspath`.

- The extended AOMedia corpus gate is:

  ```text
  ./gradlew -g .gradle-user-home aomAvifTest
  ```
