# AVIF Support Plan

## Status

`org.glavo.avif` provides a public reader API (`AvifImageReader`) that parses AVIF containers,
decodes AV1 payloads through the pure-Java `org.glavo.avif.decode` reader, and exposes
postprocessed decoded planes for composition. Runtime dependency is `java.base` only.

**Image types supported:**
- Primary still images (`av01`)
- Alpha auxiliary images (non-premultiplied ARGB combining)
- Grid derived images (8-bit and 10/12-bit cell composition)
- Progressive still images (`prog` references)
- AVIS animated sequences (keyframe decode, frame count/dimensions/timing metadata)

**BMFF features supported:**
- Boxes: `ftyp`, `meta` (`hdlr`, `pitm`, `iloc`, `iinf/infe`, `iprp/ipco/ipma`, `iref`, `idat`)
- Reference types: `auxl`, `dimg`, `prog`
- Properties: `ispe`, `av1C`, `colr nclx`, `auxC`, `pixi`, `pasp`, `clap`, `irot`, `imir`, `a1op`
- Transforms applied post-decode: `clap` (crop), `irot` (rotate), `imir` (mirror) — 8-bit only
- AV1 output color conversion uses explicit sequence matrix/range metadata for RGB identity,
  `BT.709`, `BT.601`, `SMPTE 240M`, and `BT.2020` non-constant-luminance signals; unspecified
  matrix coefficients keep the legacy `BT.601` full-range fallback.
- Postprocessed decoded planes exposed via `Av1ImageReader.lastPlanes()` for direct plane access
- AV1 inter-frame warped motion boundary clamp fixed (`horizontalInterpolate`/`verticalInterpolate`)

**Parsing robustness:** parser tests cover truncation, overflow, duplicate unique boxes, invalid
references, missing required properties, and essential unknown property rejection.

**Test fixtures:** all AVIF files copied from libavif `tests/data` have explicit corpus
expectations. The corpus now decodes the libavif IO still-image samples, 10/12-bit grid samples,
and animated alpha/depth/keyframe samples that previously failed. Fixtures that remain expected
failures match libavif strict parsing or documented unsupported behavior.

**Known gaps:**
- AV1 `I444` pixel-accuracy tracked separately from AVIF container coverage.
- ICC profiles, transfer-function conversion, and full color-management/display adaptation are not
  implemented; decoded ARGB output currently applies only AV1/nclx-style matrix and range handling.
- Container-level `clap`/`irot`/`imir` transform composition is still 8-bit only.
- Gain map, depth, audio, encoder, CLI, and incremental IO behaviors from libavif are not exposed
  as public APIs here; tests currently validate primary-image decode behavior for those fixtures.
- External dav1d `checkasm` and fuzz harnesses are not one-to-one Java ports; the Java suite keeps
  equivalent targeted parser, entropy, reconstruction, postfilter, and AV1 reader tests.

## Validation

- Focused: `./gradlew -g .gradle-user-home test --tests org.glavo.avif.AvifImageReaderTest`
- Full: `./gradlew -g .gradle-user-home compileJava compileTestJava test`

## Completion Criteria

- Common AVIF still, alpha, grid, progressive, and animated images decode through the Java AV1 engine.
- Unsupported features fail predictably with `AvifDecodeException` and stable error codes.
- Runtime depends only on `java.base`.
- Full Gradle validation passes with workspace-local Gradle user home.
