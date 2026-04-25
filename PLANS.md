# AVIF Support Plan

## Status

`org.glavo.avif` provides a public reader API (`AvifImageReader`) that parses AVIF containers,
extracts AV1 item payloads, and decodes them through the pure-Java `org.glavo.avif.decode` AV1
reader. Runtime dependency is `java.base` only.

**Image types supported:**
- Primary still images (`av01`)
- Alpha auxiliary images (non-premultiplied ARGB combining)
- Grid derived images (cell composition)
- Progressive still images (`prog` references)
- AVIS animated sequences (keyframe decode, frame count/dimensions/timing metadata)

**BMFF features supported:**
- Boxes: `ftyp`, `meta` (`hdlr`, `pitm`, `iloc`, `iinf/infe`, `iprp/ipco/ipma`, `iref`, `idat`)
- Reference types: `auxl`, `dimg`, `prog`
- Properties: `ispe`, `av1C`, `colr nclx`, `auxC`, `pixi`, `pasp`, `clap`, `irot`, `imir`, `a1op`
- Transforms applied post-decode: `clap` (crop), `irot` (rotate), `imir` (mirror) — 8-bit only

**Parsing robustness:** parser tests cover truncation, overflow, duplicate unique boxes, invalid
references, missing required properties, and essential unknown property rejection.

**Test fixtures:** `white_1x1.avif`, `extended_pixi.avif`, `colors_sdr_srgb.avif`,
`paris_icc_exif_xmp.avif`, `abc_color_irot_alpha_NOirot.avif`, `sofa_grid1x5_420.avif`,
`draw_points_idat_progressive.avif`, `colors-animated-8bpc.avif`,
plus synthetic still-image, alpha, grid, and irot tests.

**Known gaps:**
- AV1 `I444` pixel-accuracy tracked separately from AVIF container coverage.
- AVIS inter-frame decoding blocked by AV1 decoder reference-plane boundary bug
  (`DecodedPlane.sample` with `x=150` out of range during warped motion prediction).
  Keyframe samples decode correctly; moov parsing and frame metadata work.
- Grid and transform composition: 8-bit only; 10/12-bit deferred.

## Remaining Work

1. **AVIS inter-frame decoding** — keyframe samples decode correctly; delta frames need
   persistent decoder state or nearest-keyframe reconstruction.

2. **Improve AV1 integration for AVIF composition**
   - Expose postprocessed planes before ARGB packing for reuse in alpha, grid, and transform paths.
   - Keep public AV1 reader behavior unchanged.

## Validation

- Focused: `./gradlew -g .gradle-user-home test --tests org.glavo.avif.AvifImageReaderTest`
- Full: `./gradlew -g .gradle-user-home compileJava compileTestJava test`

## Completion Criteria

- Common AVIF still, alpha, grid, progressive, and animated images decode through the Java AV1 engine.
- Unsupported features fail predictably with `AvifDecodeException` and stable error codes.
- Runtime depends only on `java.base`.
- Full Gradle validation passes with workspace-local Gradle user home.
