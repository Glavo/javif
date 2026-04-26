# Complete AVIF Decode Plan

## Summary

The current test baseline is stable: `./gradlew -g .gradle-user-home compileJava compileTestJava test`
passes, libavif test data is copied through `processTestResources`, and tests do not read `external`
directly.

The remaining work is to make the pure-Java AVIF reader match dav1d/libavif decode behavior for
still images, sequences, AV1 reconstruction, postfiltering, color output, gain maps, and
reference-style tests. This plan targets decoding support only; libavif encoders, command-line
tools, native ABI compatibility, fuzzers, and apps are out of scope.

## Implementation Work

- Replace approximate AV1 paths with normative behavior: full `refmvs` temporal projection,
  remaining inter, compound, warped, intrabc, and inter-palette behavior, plus `show_existing_frame`
  cases that currently require reconstructed reference surfaces.
- Finish postfilter parity with dav1d through cross-stage verification of loop filter, CDEF,
  Wiener restoration, self-guided restoration, super-resolution, and film grain in the correct
  pipeline order.
- Improve display semantics while preserving raw-plane APIs: validate the pure-Java CICP transfer
  and RGB-primary conversion paths against reference pixels, define selectable output color-space
  behavior where needed, and keep ICC application metadata-only unless the dependency policy
  changes.
- Complete AVIF-level feature handling covered by libavif data: reference-grade gain-map tone
  mapping pixel parity, ICC-profile gain-map policy, AVIS gain-map/depth/alpha sequencing gaps,
  non-default operating-point policy, auxiliary grid/layout edge cases, and stricter
  `clap`/`irot`/`imir` validation against libavif behavior.
- Keep public APIs conservative. Add configuration only where behavior must be selectable, such as
  color-management mode, film-grain enablement, operating-point selection, and gain-map application
  mode.

## Test Plan

- Build a coverage ledger mapping relevant libavif gtest/cmd tests and dav1d checkasm categories to
  Java tests, expected pass/fail status, and fixture sources.
- Port dav1d checkasm-style coverage into deterministic Java tests for `msac`, palette, `refmvs`,
  intra prediction, inverse transforms, motion compensation, loop filter, CDEF, loop restoration,
  and film grain.
- Expand libavif corpus coverage from metadata and dimension checks into pixel comparisons using
  copied test resources only: ImageIO for PNG/JPEG references and JavaCPP FFmpeg for Y4M/video
  references.
- Add whole-image comparisons for `I420`, `I422`, `I444`, 8/10/12-bit, alpha, grids, progressive
  stills, AVIS sequences, HDR/WCG fixtures, and gain-map fixtures.
- Keep unsupported boundaries covered by explicit `AvifDecodeException` or `DecodeException`
  assertions until implemented, then remove those expected-failure cases when support lands.

## Execution Order

1. Establish the coverage ledger so missing dav1d/libavif parity is tracked explicitly.
2. Fix AV1 core correctness first: syntax, CDFs, `refmvs`, inter prediction, compound prediction,
   warped prediction, and intrabc.
3. Fix postfilter pipeline interactions with whole-frame reference comparisons.
4. Fix AVIF-level display and auxiliary semantics: gain maps, color handling, sequence auxiliary
   planes, transforms, and operating points.
5. Tighten validation last by replacing broad corpus smoke checks with pixel/reference comparisons.

## Completion Rule

Run `./gradlew -g .gradle-user-home compileJava compileTestJava test` before considering an
implementation item complete.
