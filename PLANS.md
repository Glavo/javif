# AV1 Remaining Work Execution Plan

## Summary

This document replaces the original full-port blueprint with a status-aware execution plan for the remaining AV1 decoder work.

The repository already has:

- `BufferedInput`-based source handling
- public reader/config/error/result skeletons
- OBU reading and AV1 header parsing
- frame assembly across standalone and combined frame OBUs
- tile/block structural syntax decoding
- transform layout decoding
- partial residual syntax decoding
- frame-level structural snapshots for CDF and temporal motion state
- a minimal reconstruction core for a narrow first-pixel path
- an internal ARGB output layer for `DecodedPlanes`

`Av1ImageReader.readFrame()` no longer always stops before pixel reconstruction. The reader can now return a real `ArgbIntFrame` for a deliberately narrow subset:

- raw AV1 OBU input only
- serial execution only
- single-tile frames only
- visible `KEY` / `INTRA` frames
- `8-bit`
- `I400` and `I420`
- non-directional and directional intra prediction, filter-intra luma prediction, and `I420` CFL chroma prediction
- minimal luma `DCT_DCT` residual support for the current `4/8/16` square and rectangular transform subset
- minimal bitstream-to-reconstruction `I420` chroma `DCT_DCT` residual support for the current uniform visible-grid `4/8/16` transform subset, including clipped, fringe, multi-unit footprints, and a deterministic `TX_4X4` multi-coefficient path

Everything outside that subset still fails explicitly with a stable `NOT_IMPLEMENTED` boundary instead of silently producing incorrect output.

The first end-to-end delivery target is intentionally narrow:

- raw AV1 OBU input only
- serial execution only
- visible `KEY` / `INTRA` frames first
- `8-bit`
- `I400` and `I420`
- `ArgbIntFrame` output

Everything else expands from that baseline after correctness is stable.

## Current Baseline

### Already Implemented

- Input abstraction is already unified around `BufferedInput`.
- Public API types already exist: `Av1ImageReader`, `Av1DecoderConfig`, `DecodeException`, `DecodedFrame`, `ArgbIntFrame`, and `ArgbLongFrame`.
- `SequenceHeaderParser` and `FrameHeaderParser` already cover most frame-level syntax, including tiling, quantization, segmentation, loop filter, CDEF, restoration, skip mode, and reference-state inheritance.
- Film grain parameters are already parsed and normalized into `FrameHeader`; synthesis is still deferred.
- Tile parsing is already connected through `TileGroupHeaderParser`, `TileDataParser`, and `TileBitstreamParser`.
- Structural decoding already exists through `FrameSyntaxDecoder`, `TilePartitionTreeReader`, `TileBlockHeaderReader`, `TileTransformLayoutReader`, and `TileResidualSyntaxReader`.
- Reference slots already persist structural decode state, final tile CDF snapshots, and decoded temporal motion-field snapshots.
- `DecodedPlane`, `DecodedPlanes`, and `ReferenceSurfaceSnapshot` already exist as the reconstruction/output boundary contracts.
- `ArgbOutput` already converts `DecodedPlanes` into `ArgbIntFrame` for `8-bit I400/I420`.
- A minimal reconstruction path already exists through `FrameReconstructor`, `IntraPredictor`, and `MutablePlaneBuffer`.
- `LumaDequantizer` and `InverseTransformer` already support the current minimal non-zero luma residual path.
- Transform and residual modeling now preserve exact visible pixel footprints, not just 4x4 visibility.
- `Av1ImageReader` already connects structural decode -> reconstruction -> ARGB output for the current supported subset.

### Remaining Decode Boundary

- Multi-tile frames are still rejected by the reconstruction/output path.
- Non-zero reconstruction currently covers the current luma/chroma `DCT_DCT` subset whose transform axes stay within `4`, `8`, and `16` samples, including the first rectangular sizes needed by `I400/I420` key/intra reconstruction, but does not yet cover the broader transform-type and coefficient space.
- The public reader now consumes a minimal real bitstream-derived `I420` chroma residual path for uniform visible-grid U/V layouts, including clipped, fringe, multi-unit footprints, and the first deterministic `TX_4X4` multi-coefficient case when the current transform layout exposes smaller chroma units.
- Full chroma transform-layout modeling and broader chroma token coverage are still incomplete.
- Palette, `intrabc`, inter prediction, and motion compensation remain unsupported.
- Only `8-bit I400/I420 -> ArgbIntFrame` is wired through the public reader.
- `show_existing_frame` now reuses one stored reconstructed reference surface for the current minimal output path when the referenced slot has a `ReferenceSurfaceSnapshot` and grain is not required.
- Reference surfaces are not yet consumed by a real inter-frame pixel path.
- Postfiltering and film grain synthesis are still not implemented.
- `ArgbLongFrame`, `I422`, `I444`, and high bit-depth output paths are not implemented.
- The legacy reduced still-picture directional fixture now decodes successfully through the public reader.
- The next practical public decode gaps are no longer directional intra itself, but broader unreconstructed features such as richer chroma residual coverage, palette, `intrabc`, inter prediction, and postfilter stages.

### Current Progress Snapshot

- `Track A`: in progress
- `Track B`: parser-side work largely complete
- `Track C`: in progress, first-pixel baseline reached
- `Track D`: not started
- `Track E`: partially complete for `8-bit I400/I420 -> ArgbIntFrame`
- `Track F`: in progress, minimal public output path exists
- `Track G`: in progress

## Frozen Interfaces and Constraints

- Public API shape stays stable unless a later implementation step proves a strict change is unavoidable.
- `Av1ImageReader` continues to accept only `BufferedInput`.
- Runtime code must continue to have no runtime dependency outside `java.base`.
- The decoder remains correctness-first and serial-first throughout this plan.
- Reference slots store post-filter, pre-grain surfaces. Film grain is applied only to final presentation output.
- The upstream dav1d baseline commit remains fixed until the first end-to-end decoder is complete.
- Parallel decoding is deferred until after the serial output path is correct.

## Internal Contracts To Freeze

The following internal contracts must be introduced or finalized early and treated as stable boundaries between tracks:

- `DecodedPlanes`
  - Represents Y/U/V planes after reconstruction and in-loop/post-filtering, but before ARGB conversion.
- `ReferenceSurfaceSnapshot`
  - Stores `FrameHeader`, `FrameSyntaxDecodeResult`, decoded planes, final tile CDF snapshots, and decoded temporal motion state for reference reuse.
- `ResidualLayout`
  - Must cover both luma and chroma residual units.
- `TransformResidualUnit`
  - Must store transform size, transform type, end-of-block index, dense coefficient storage, and context bytes needed by later stages.
- `FilmGrainParams`
  - Must be normalized inside `FrameHeader` and not left as partially parsed raw state.

Current status:

- `DecodedPlanes` exists and is already consumed by the output layer.
- `ReferenceSurfaceSnapshot` exists and is already used as the reference-surface storage contract.
- `FilmGrainParams` already exists in normalized form inside `FrameHeader`.
- `ResidualLayout` now carries split U/V residual-unit arrays and supports the current uniform visible-grid bitstream-side chroma population path, including clipped and multi-unit visible footprints.
- `TransformResidualUnit` still needs expansion before full reconstruction, especially for richer transform and coefficient coverage.

## Remaining Work Tracks

### Track A: Syntax Tail

Goal: finish all syntax that must exist before reconstruction can begin.

Status: in progress.

Scope:

- Complete remaining block-level syntax that is still placeholder-only or partially modeled.
- Finish `motion_mode`, masked compound, wedge, inter-intra, and non-placeholder `GLOBALMV` inputs.
- Finish residual/token coverage for valid paths still rejected today.
- Complete chroma residual decoding.
- Complete non-`TX_4X4` multi-coefficient decoding.
- Extend transform/residual models so reconstruction never has to infer missing syntax.

Exit criteria:

- Every decoded leaf block has a complete `BlockHeader`, `TransformLayout`, and `ResidualLayout`.
- No supported valid path depends on fallback exceptions or unsupported-path shortcuts inside the syntax layer.

Current gap after the first-pixel milestone:

- Non-zero residual decode and reconstruction still do not cover the full reconstruction-ready coefficient space.
- Chroma residual syntax now covers the minimal uniform visible-grid path, including clipped/fringe and multi-unit visible footprints plus the first deterministic `TX_4X4` multi-coefficient path, but still does not cover the full chroma transform and coefficient space.
- Several block features remain syntax-only or rejected during reconstruction: palette, inter prediction, and motion compensation-related paths.

Write scope:

- Existing `src/main/java/org/glavo/avif/internal/av1/decode/**`
- Residual/transform-related files under `src/main/java/org/glavo/avif/internal/av1/model/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/decode/**`

### Track B: Frame Header and Film Grain Parameters

Goal: finish frame-level parsing so later runtime and postfilter stages do not need parser changes.

Status: mostly complete on the parser/model side.

Scope:

- Parse and normalize full film grain parameters.
- Tighten frame-level flags that are already parsed but not yet stored in reconstruction-ready form.
- Ensure frame-level state is sufficient for postfilter, runtime policy, and output decisions.

Exit criteria:

- `FrameHeader` fully represents grain-related and postfilter-related frame state.
- No valid grain-bearing frame fails in the parser with `NOT_IMPLEMENTED`.

Remaining work inside this track is now mostly validation and downstream consumption, not core parser bring-up.

Write scope:

- `src/main/java/org/glavo/avif/internal/av1/parse/FrameHeaderParser.java`
- `src/main/java/org/glavo/avif/internal/av1/model/FrameHeader.java`
- Related parser tests

### Track C: Reconstruction Core

Goal: create decoded planes from block syntax and residuals.

Status: in progress. First-pixel baseline and minimal non-zero luma residual support reached.

Scope:

- Add a new `org.glavo.avif.internal.av1.recon` package.
- Implement plane buffers and decoded-surface storage.
- Implement dequantization and inverse transforms.
- Implement intra prediction.
- Implement inter prediction and motion compensation.
- Implement `intrabc`, palette, richer intra prediction, and inter pixel application.
- Implement super-resolution upscaling.
- Implement reference-surface refresh and reuse.

Execution order inside the track:

1. `8-bit I400 KEY/INTRA`
2. `8-bit I420 KEY/INTRA`
3. non-zero luma residual support for the current key/intra path
4. chroma residual and richer intra feature support
5. visible inter/reference frame support
6. wider pixel-format and bit-depth coverage

Exit criteria:

- A decoded frame produces stable `DecodedPlanes`.
- Reference surfaces can be refreshed and reused for later frames.

Completed within this track already:

- mutable plane storage
- minimal intra prediction
- first public first-pixel path for single-tile `8-bit I400/I420` key/intra frames with all-zero residual
- luma/chroma dequantization and inverse transform for the current `4/8/16` square and rectangular `DCT_DCT` subset
- minimal non-zero luma residual reconstruction inside the current key/intra subset
- luma filter-intra reconstruction
- directional intra reconstruction
- `I420` CFL chroma reconstruction
- minimal reconstruction-side `I420` chroma residual application for split U/V residual units
- minimal bitstream-side `I420` chroma residual syntax for uniform visible-grid U/V layouts, including clipped/fringe and multi-unit visible footprints
- deterministic `TX_4X4` multi-coefficient bitstream-derived `I420` chroma residual coverage, with corrected `TX_4X4` coefficient-context coordinate handling
- non-zero rectangular `DCT_DCT` reconstruction for the current `4/8/16` luma/chroma key/intra subset

Immediate next steps inside this track:

- richer AC coverage beyond the current `4/8/16` square-and-rectangular `DCT_DCT` subset and broader transform-type support
- fuller chroma transform-layout and coefficient coverage beyond the current uniform visible-grid path
- palette pixel application
- inter reconstruction and reference-surface consumption

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/recon/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/recon/**`

Dependency:

- Starts only after Track A freezes the residual and transform contracts.

### Track D: Postfilter and Film Grain Application

Goal: implement the full decoder output pipeline after reconstruction.

Status: not started.

Scope:

- Add a new `org.glavo.avif.internal.av1.postfilter` package.
- Implement loop filter.
- Implement CDEF.
- Implement loop restoration.
- Implement film grain synthesis and application.

Rules:

- Reference surfaces are stored after restoration and before film grain.
- Film grain is applied only when producing presentation output.

Exit criteria:

- Postfilter stages run in decoder order.
- Grain application is controlled by `Av1DecoderConfig.applyFilmGrain`.

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/postfilter/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/postfilter/**`

Dependencies:

- Requires Track B and Track C to be complete enough for stable plane and grain inputs.

### Track E: Output Conversion

Goal: convert decoded planes into public frame payloads.

Status: partially complete.

Scope:

- Add a new `org.glavo.avif.internal.av1.output` package.
- Convert `DecodedPlanes` into non-premultiplied opaque ARGB.
- Produce `ArgbIntFrame` for `8-bit`.
- Produce `ArgbLongFrame` for `10-bit` and `12-bit`.
- Support `I400`, `I420`, `I422`, and `I444`.

Execution order inside the track:

1. `8-bit I400/I420 -> ArgbIntFrame`
2. `I422/I444 -> ArgbIntFrame`
3. `10-bit` and `12-bit -> ArgbLongFrame`

Exit criteria:

- Output conversion no longer depends on decoder-internal storage details beyond `DecodedPlanes`.

Already complete in this track:

- `ArgbOutput`
- `YuvToRgbTransform`
- `OutputFrameMetadata`
- `8-bit I400/I420 -> ArgbIntFrame`

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/output/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/output/**`

Dependency:

- Starts after Track C freezes the `DecodedPlanes` contract.

### Track F: Public Runtime Integration

Goal: connect the internal decode pipeline to public frame delivery.

Status: in progress.

Scope:

- Add a new `org.glavo.avif.internal.av1.runtime` package.
- Turn structural reference snapshots into full runtime reference slots.
- Implement visible and invisible frame handling.
- Implement `show_existing_frame` output reuse.
- Connect `outputInvisibleFrames`, `decodeFrameType`, `strictStdCompliance`, and `frameSizeLimit` to final reader behavior.
- Keep `threadCount` serial in the first end-to-end implementation while preserving a future executor seam.
- Materialize `DecodedFrame`, `ArgbIntFrame`, and `ArgbLongFrame` instances from the final decode pipeline.

Exit criteria:

- `readFrame()` returns public frames for supported streams.
- `readAllFrames()` matches incremental reading.
- Valid supported streams no longer fail with the current placeholder `NOT_IMPLEMENTED` boundary.

Already complete in this track:

- `readFrame()` returns `ArgbIntFrame` for the current narrow first-pixel subset
- frame filtering for `decodeFrameType` is applied at the current public output boundary
- syntax reference state and reconstructed reference surfaces are stored separately
- `show_existing_frame` reuses an already reconstructed stored surface for the current minimal output path
- the current public boundary has already moved past `non-zero residual`, `filter_intra`, `CFL`, and directional intra on the legacy reduced still-picture path

Still missing in this track:

- `show_existing_frame` output reuse when the referenced frame still needs unsupported presentation work such as grain synthesis
- invisible-frame output policy beyond the current narrow path
- inter/reference-frame output lifecycle
- film-grain-aware final output

Write scope:

- Existing `src/main/java/org/glavo/avif/decode/**`
- New `src/main/java/org/glavo/avif/internal/av1/runtime/**`
- Matching public reader tests

Dependencies:

- Starts after Track D and Track E are both stable.

### Track G: Oracle and Regression Harness

Goal: replace existence-only tests with fixed oracle coverage and end-to-end validation.

Status: in progress.

Scope:

- Add fixed AV1 fixtures under `src/test/resources/av1/`.
- Add exact-oracle tests for partition topology, transform layouts, residual coefficients, temporal motion fields, and tile CDF snapshots.
- Add reader contract tests for malformed streams and stable error codes.
- Add source-equivalence tests across all `BufferedInput` adapters.
- Add dav1d-backed oracle comparisons for representative samples.
- Add end-to-end output golden tests once public frame output exists.

Execution order inside the track:

1. Fixed structural fixtures and reader contract tests
2. Multi-frame reference-state fixtures
3. Pixel golden corpus for first successful output path
4. Feature-matrix expansion

Exit criteria:

- Newly fixed bugs always add a stable sample-backed regression test.
- The suite no longer depends primarily on brute-force payload search to prove a path is reachable.

Already complete in this track:

- fixed structural fixtures and exact-oracle syntax tests
- reader contract tests for malformed streams and stable error codes
- first-pixel reader success tests
- output-layer and reconstruction-layer unit tests

Write scope:

- `src/test/java/**`
- `src/test/resources/**`
- `build.gradle.kts` only if strictly needed for fixture or oracle tooling

## Parallel Subagent Execution Strategy

All implementation subagents must use:

- model: `gpt-5.4`
- reasoning effort: `xhigh`

Concurrent work is allowed only when write scopes do not overlap.

### Wave 1

- Worker A: Track A
- Worker B: Track B
- Worker C: Track G phase 1

Rules:

- Only Worker A may modify existing files under `internal/av1/decode/**`.
- Only Worker B may modify `FrameHeaderParser` and `FrameHeader`.
- Worker C remains test-only and does not modify main sources.

### Wave 2

After Track A merges and residual/model contracts are frozen:

- Worker D: Track C
- Worker C continues Track G

After Track C freezes `DecodedPlanes`:

- Worker E: Track E

### Wave 3

After Track B and Track C are stable:

- Worker F: Track D
- Worker C continues Track G pixel-golden work

### Wave 4

After Track D and Track E are merged:

- Worker G: Track F

### Merge Order

The required merge order is:

- `A || B || G(phase 1)`
- `C`
- `E || D || G(phase 2)`
- `F`

Constraints:

- Do not let two workers modify existing `internal/av1/decode/**`.
- Do not let two workers modify `org/glavo/avif/decode/**`.
- `external/**` stays read-only.
- Keep at most 3 active implementation workers at the same time. A temporary peak of 4 is allowed only after dependencies are frozen and write scopes are fully disjoint.

## Milestones and Acceptance

### M1: Syntax-Complete Front End

Tracks:

- A
- B
- G phase 1

Acceptance:

- Structural decode is exact-oracle tested with fixed fixtures.
- No supported syntax path still depends on placeholder-only decode branches.
- The reader still may stop before pixel reconstruction, but the syntax boundary is stable and explicit.

Status:

- partially achieved, but not yet closed

### M2: First-Pixel Output

Tracks:

- C
- E
- partial G pixel goldens

Acceptance:

- `Av1ImageReader` can return `ArgbIntFrame` for representative `8-bit I400/I420` visible key/intra samples.
- Pixel arrays, metadata, and source-equivalence checks are stable across all `BufferedInput` adapters.

Status:

- partially achieved now
- current first-pixel output works for single-tile `8-bit I400/I420` key/intra samples within the current non-directional-plus-directional-plus-filter-intra/CFL subset, including the minimal non-zero `DCT_DCT` luma/chroma residual subset for the current `4/8/16` square and rectangular transforms and a minimal real bitstream-derived `I420` chroma residual path for uniform visible-grid single-unit or multi-unit footprints
- reconstruction-side and integration coverage now both include the current minimal `I420` chroma residual path, but fuller chroma transform/token coverage, palette/inter paths, and a less artificial sample set are still missing
- milestone is not closed until chroma residuals, palette/inter paths, and a less artificial sample set are covered

### M3: Reference and Inter Frames

Tracks:

- C expansion
- F partial
- G multi-frame regressions

Acceptance:

- Inter/reference decode works for a representative sample set.
- Reference-surface refresh and reuse are stable.
- `show_existing_frame` produces output instead of failing at the old boundary.

Status:

- partially started
- minimal `show_existing_frame` output reuse is now wired for already reconstructed stored surfaces
- inter/reference pixel decode itself is still not started

### M4: Full Presentation Pipeline

Tracks:

- D
- F completion
- G feature-matrix expansion

Acceptance:

- Loop filter, CDEF, restoration, and film grain work in the expected order.
- `applyFilmGrain`, `outputInvisibleFrames`, and `decodeFrameType` have stable public behavior.
- `readAllFrames()` and `presentationIndex` behavior are locked by tests.

Status:

- not started

### M5: Coverage Closure

Tracks:

- Remaining format and bit-depth expansion
- Residual unsupported-path removal
- Additional regressions and oracle fixtures

Acceptance:

- Representative coverage exists for `8/10/12-bit`, `I400/I420/I422/I444`, key/intra/inter/switch, visible/invisible frames, `show_existing_frame`, super-resolution, loop filter, CDEF, restoration, and film grain on/off.

Status:

- not started

## Test Strategy

### Immediate Additions

- Fixed exact-oracle fixtures for partition trees, transform layouts, residual coefficients, temporal motion fields, and tile CDF snapshots
- Reader contract tests for:
  - invalid `show_existing_frame` slots
  - tile-count mismatch
  - out-of-order tile groups
  - incomplete frames at EOS
  - `FRAME_SIZE_LIMIT_EXCEEDED`

### First Output Path Tests

- `8-bit I400` and `8-bit I420` pixel goldens
- `DecodedFrame` metadata validation
- `readFrame()` / `readAllFrames()` equivalence
- source-equivalence across `OfInputStream`, `OfByteChannel`, and `OfByteBuffer`

### Multi-Frame Runtime Tests

- `refresh_context=false`
- `primary_ref_frame` inheritance
- temporal fallback paths
- multi-tile frames
- visible/invisible frame combinations
- `show_existing_frame`

### Feature-Matrix Tests

- `8-bit`, `10-bit`, `12-bit`
- `I400`, `I420`, `I422`, `I444`
- key, intra, inter, and switch frames
- super-resolution
- loop filter
- CDEF
- restoration
- film grain enabled and disabled

## Deferred Follow-Ups

- AVIF container parsing
- true tile/frame parallel execution
- performance tuning beyond correctness-first needs
- memory-usage optimization beyond what is required for correctness

## Maintenance Rule

`PLANS.md` is a living execution document. After each milestone merge, update:

- current decoder boundary
- completed tracks
- remaining blocked dependencies
- newly added or removed deferred items
- the exact delta between the last documented reconstruction subset and the current one

The file must continue to describe actual remaining work, not an outdated from-scratch port design.
