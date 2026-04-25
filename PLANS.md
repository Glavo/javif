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

**BMFF features supported:**
- Boxes: `ftyp`, `meta` (`hdlr`, `pitm`, `iloc`, `iinf/infe`, `iprp/ipco/ipma`, `iref`, `idat`)
- Reference types: `auxl`, `dimg`, `prog`
- Properties: `ispe`, `av1C`, `colr nclx`, `auxC`, `pixi`, `pasp`, `clap`, `irot`, `imir`, `a1op`
- Transforms applied post-decode: `clap` (crop), `irot` (rotate), `imir` (mirror) — 8-bit only

**Parsing robustness:** parser tests cover truncation, overflow, duplicate unique boxes, invalid
references, missing required properties, and essential unknown property rejection.

**Test fixtures:** `white_1x1.avif`, `extended_pixi.avif`, `colors_sdr_srgb.avif`,
`paris_icc_exif_xmp.avif`, `abc_color_irot_alpha_NOirot.avif`, `sofa_grid1x5_420.avif`,
`draw_points_idat_progressive.avif`, plus synthetic still-image, alpha, grid, and irot tests.

**Known gaps:**
- AV1 `I444` pixel-accuracy is tracked separately from AVIF container coverage.
- AVIS image sequences (avis brand + `moov` box) are detected and rejected with a clear
  diagnostic. Full `moov`/track/sample-table parsing is deferred.

## Remaining Work

1. **AVIS image sequences**
   - Parse `moov`, tracks, sample tables, sync samples, durations, and sample dependencies.
   - Decode sequential and indexed frames using nearest-keyframe state.
   - Expose frame count, animation status, and timing metadata in the public API.

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
