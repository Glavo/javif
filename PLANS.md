# AV1 Remaining Work Execution Plan

## Summary

This document replaces the original full-port blueprint with a status-aware execution plan for the remaining AV1 decoder work.

The repository already has a working AV1 front end, a narrow but real reconstruction path, reference-surface storage, postfilter and film-grain stage ordering, and public ARGB output.

`Av1ImageReader.readFrame()` can already return real frames for a deliberately narrow subset:

- raw AV1 OBU input, serial execution
- visible `KEY` / `INTRA` frames in the current `8-bit I400/I420/I422/I444` still-picture subset
- the current key/intra reconstruction subset: directional and non-directional intra, filter-intra, CFL, minimal palette, and the current `DCT_DCT` residual space whose transform axes stay within `64`
- the current stored-surface `show_existing_frame` path
- the first inter/reference subset: single-reference and average-compound prediction with integer-copy, fixed-filter / `SWITCHABLE` subpel sampling, horizontal super-resolution, and a first parsed-stream inter success path backed by injected stored references

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

- `BufferedInput`, public reader/config/error/result types, and the raw OBU reader pipeline.
- Frame-level and tile/block-level syntax decoding, including stored CDF and temporal-motion snapshots.
- Stable reconstruction/output contracts: `DecodedPlane`, `DecodedPlanes`, and `ReferenceSurfaceSnapshot`.
- A minimal but real reconstruction path through `FrameReconstructor`, `IntraPredictor`, dequantization, and inverse transform.
- Public output wiring through `ArgbOutput`, `ArgbIntFrame`, `ArgbLongFrame`, stored-surface reuse, and the current film-grain-aware presentation contract.

### Remaining Decode Boundary

- Non-zero reconstruction currently covers the current luma/chroma `DCT_DCT` subset whose transform axes stay within `64` samples, including larger square and rectangular sizes needed by the current real inter fixture, but does not yet cover the broader transform-type and coefficient space.
- The public reader now consumes a minimal real bitstream-derived `I420` chroma residual path for uniform visible-grid U/V layouts, including clipped, fringe, multi-unit footprints, the first deterministic `TX_4X4` multi-coefficient case, and the first deterministic larger-transform `TX_8X8` case when the current transform layout exposes smaller chroma units.
- The syntax and integration layers now also cover the first real bitstream-derived `I422` chroma residual path, including deterministic DC-only, clipped-footprint, and larger-transform multi-coefficient coverage.
- The serial reconstruction/output path no longer hard-rejects multi-tile frame-syntax results, and the public reader can already present a stored synthetic multi-tile reference surface through the current `show_existing_frame` output path.
- Stable real bitstream-driven multi-tile first-pixel fixtures are still missing, so multi-tile coverage is currently strongest at the synthetic frame-syntax/runtime level rather than the fixture corpus.
- Full chroma transform-layout modeling and broader chroma token coverage are still incomplete.
- Minimal synthetic palette reconstruction now covers the current `I400` / `I420` / `I422` / `I444` subset, and the first deterministic real bitstream-driven palette fixture now also covers `I422` / `I444` at the reconstruction/integration layer plus stored-surface public reuse; broader palette edge cases, direct parsed wider-chroma palette streams, and wider-chroma real fixture variety are still missing.
- `intrabc` reconstruction now covers a first synthetic same-frame `BILINEAR` subset for `I400/I420/I422/I444`, plus a first generated-header real syntax path with a synthetic resolved-MV reconstruction bridge; broader parsed-stream `intrabc` streams and any richer same-frame clipping/compound behavior are still unsupported.
- Inter/reference reconstruction has now started at a narrow single-reference plus average-compound subset with integer-copy plus the current fixed-filter and block-resolved `SWITCHABLE` subpel prediction path (`BILINEAR` plus fixed `EIGHT_TAP_*` modes), and the current real inter fixture no longer needs zero-residual normalization just to cross the reconstruction boundary; the current minimal subset now also accepts stored post-super-resolution reference surfaces through one endpoint-preserving geometry-remap path, and the public reader now has a first real parsed-stream inter success path backed by injected stored references, but broader parsed-stream inter sample support and richer motion compensation still remain incomplete.
- Direct parsed-stream first-pixel output now covers the current `8-bit I400/I420/I422/I444 -> ArgbIntFrame` still-picture subset, while broader `I422/I444` feature coverage still remains incomplete.
- `show_existing_frame` now reuses one stored reconstructed reference surface for the current minimal output path when the referenced slot has a `ReferenceSurfaceSnapshot` and grain is not required, including the first real parsed-stream `I422/I444` still-picture round-trips and the earlier synthetic wider-chroma stored-surface coverage.
- Reference surfaces are now consumed by a first synthetic-plus-integration inter-frame pixel path, and the current real inter fixture now reconstructs through its native larger residual sizes in integration tests; the public reader also has a first real parsed-stream inter round-trip backed by injected stored references, while broader direct parsed-stream inter output is still blocked by motion-compensation coverage gaps.
- Postfilter stage ordering now exists through `FramePostprocessor`, with the current loop filter / CDEF / restoration subset acting as deterministic no-op shells while preserving the reference-surface contract, and presentation-only film grain synthesis now exists through the current deterministic `FilmGrainSynthesizer` subset.
- Key/intra reconstruction now also covers a minimal horizontal super-resolution subset, and inter/reference reconstruction now includes a first horizontal super-resolution subset that upsamples coded-domain predictions into the stored post-super-resolution domain, including the first geometry-remapped path that consumes stored post-super-resolution reference surfaces; higher-fidelity AV1 resampling behavior and broader parsed-stream inter super-resolution coverage are still incomplete.
- `I422/I444` still lack broader real parsed-stream fixture coverage beyond the current minimal still-picture subset even though direct first-pixel output and stored-surface reuse now exist.
- The legacy reduced still-picture directional fixture now decodes successfully through the public reader.
- The next practical public decode gaps are no longer directional intra itself, but broader unreconstructed features such as richer chroma residual coverage, broader palette coverage, broader `intrabc` / inter motion compensation coverage, and higher-fidelity super-resolution / postfilter behavior.

### Current Progress Snapshot

- `Track A`: complete
- `Track B`: complete
- `Track C`: in progress, first-pixel baseline widened into the first real parsed-stream plus synthetic `I422/I444` subset with larger `32/64`-axis residual support, the first real inter residual integration coverage, a first single-reference plus average-compound fixed-filter plus block-resolved `SWITCHABLE` inter path, a first key/intra plus inter horizontal super-resolution subset including geometry-remapped stored reference surfaces, a first synthetic same-frame plus generated-header-bridged `intrabc` subset, and a first direct public-reader parsed inter success path backed by injected stored references
- `Track D`: complete for the current supported subset
- `Track E`: complete
- `Track F`: complete
- `Track G`: complete

## Frozen Interfaces and Constraints

- Public API shape stays stable unless a later implementation step proves a strict change is unavoidable.
- `Av1ImageReader` continues to accept only `BufferedInput`.
- Runtime code must continue to have no runtime dependency outside `java.base`.
- The decoder remains correctness-first and serial-first throughout this plan.
- Reference slots store post-filter, post-super-resolution, pre-grain surfaces. Film grain is applied only to final presentation output.
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

Status: complete.

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

- Residual and reconstruction coverage still do not span the full coefficient and transform space.
- Chroma residual coverage is no longer minimal, but it is still far from complete.
- Several block features are still syntax-only or reconstruction-limited, especially richer inter motion compensation, broader parsed-stream `intrabc`, and broader palette coverage.

Write scope:

- Existing `src/main/java/org/glavo/avif/internal/av1/decode/**`
- Residual/transform-related files under `src/main/java/org/glavo/avif/internal/av1/model/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/decode/**`

### Track B: Frame Header and Film Grain Parameters

Goal: finish frame-level parsing so later runtime and postfilter stages do not need parser changes.

Status: complete.

Scope:

- Parse and normalize full film grain parameters.
- Tighten frame-level flags that are already parsed but not yet stored in reconstruction-ready form.
- Ensure frame-level state is sufficient for postfilter, runtime policy, and output decisions.

Exit criteria:

- `FrameHeader` fully represents grain-related and postfilter-related frame state.
- No valid grain-bearing frame fails in the parser with `NOT_IMPLEMENTED`.

Completed in this track:

- normalized frame-header film-grain parsing and storage
- inheritance validation and parser regressions
- public reader runtime-policy coverage for `applyFilmGrain`

This track is now closed. Remaining grain work belongs to Track D, which covers synthesis and application.

Write scope:

- `src/main/java/org/glavo/avif/internal/av1/parse/FrameHeaderParser.java`
- `src/main/java/org/glavo/avif/internal/av1/model/FrameHeader.java`
- Related parser tests

### Track C: Reconstruction Core

Goal: create decoded planes from block syntax and residuals.

Status: in progress. First-pixel baseline, broader `32/64`-axis `DCT_DCT` residual support, the first real inter residual integration path, the first block-resolved `SWITCHABLE` inter path, the first key/intra plus inter horizontal super-resolution subset with geometry-remapped stored references, the first synthetic same-frame plus generated-header-bridged `intrabc` subset, and the first direct public-reader parsed inter success path backed by injected stored references are in place.

Scope:

- Add a new `org.glavo.avif.internal.av1.recon` package.
- Implement plane buffers and decoded-surface storage.
- Implement dequantization and inverse transforms.
- Implement intra prediction.
- Implement inter prediction and motion compensation.
- Expand `intrabc` beyond the current synthetic same-frame plus generated-header-bridged subset, richer palette coverage and real palette fixtures, richer intra prediction, and inter pixel application.
- Expand super-resolution upscaling beyond the current minimal horizontal key/intra plus inter subset.
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

- decoded-plane storage, minimal intra reconstruction, and the original first-pixel key/intra path
- non-zero luma/chroma `DCT_DCT` reconstruction for the current square and rectangular transform subset whose axes stay within `64`, plus the current `I420/I422/I444` CFL subset
- current bitstream-driven chroma residual coverage for `I420/I422`, including clipped/fringe, multi-unit, deterministic multi-coefficient, and first larger-transform paths
- serial multi-tile reconstruction inside the current supported subset, plus synthetic and stored-surface `show_existing_frame` coverage
- minimal synthetic and first real wider-chroma palette reconstruction coverage, including public stored-surface reuse
- current wider-chroma key/intra first-pixel subset, including direct parsed-stream `I422/I444` still-picture output
- the first inter/reference subset: single-reference and average-compound prediction with integer-copy, fixed-filter and block-resolved `SWITCHABLE` subpel sampling, one larger-residual real inter integration path, the first parsed-stream fixed-filter integration coverage, and a first public-reader parsed inter success path backed by injected stored references
- the first key/intra plus inter horizontal super-resolution subset, including geometry-remapped stored reference surfaces
- the first synthetic same-frame `intrabc` bilinear subset for `I400/I420/I422/I444`, plus a first generated-header real syntax path with a synthetic resolved-MV bridge

Immediate next steps inside this track:

- richer AC coverage beyond the current `64`-axis `DCT_DCT` subset and broader transform-type support
- fuller chroma transform-layout and coefficient coverage beyond the current `I420/I422` uniform visible-grid path
- broader real bitstream-driven palette coverage and palette edge-case coverage beyond the current single wider-chroma palette fixture, including one dedicated direct parsed wider-chroma palette stream
- broader inter motion compensation beyond the current single-reference plus average-compound integer-copy and fixed-filter/block-resolved-switchable subpel subset, including richer motion compensation and broader direct public-stream coverage for real parsed inter fixtures
- broader `intrabc` coverage beyond the current synthetic same-frame plus generated-header-bridged subset, including dedicated parsed-stream fixtures and any richer clipping/filtering behavior required by real streams
- broader super-resolution support beyond the current minimal horizontal key/intra plus geometry-remapped inter subset, especially richer parsed-stream inter super-resolution and higher-fidelity AV1 resampling behavior
- stable real bitstream multi-tile first-pixel fixtures, so the widened serial multi-tile path is covered by deterministic corpus samples instead of only synthetic runtime state
- broader real parsed-stream `I422/I444` fixtures, including richer residual and non-gray paths, so the widened wider-chroma subset is covered by deterministic corpus samples instead of only the current minimal still-picture/runtime state

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/recon/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/recon/**`

Dependency:

- Starts only after Track A freezes the residual and transform contracts.

### Track D: Postfilter and Film Grain Application

Goal: implement the full decoder output pipeline after reconstruction.

Status: complete for the current supported subset.

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

Already complete in this track:

- `org.glavo.avif.internal.av1.postfilter` and `FramePostprocessor` freeze decoder stage ordering for the current supported subset.
- The current loop filter / CDEF / restoration subset is intentionally conservative but already locks the storage contract for reference surfaces.
- `FilmGrainSynthesizer` and `Av1ImageReader` already enforce the current presentation-only grain contract with focused regressions.

This track is now closed. Remaining image-quality and spec-fidelity refinement for postfilter and grain behavior belongs to later coverage work, not to the baseline Track D contract.

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/postfilter/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/postfilter/**`

Dependencies:

- Required Track B and enough of Track C to stabilize decoded-plane inputs.

### Track E: Output Conversion

Goal: convert decoded planes into public frame payloads.

Status: complete.

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

- `ArgbOutput`, `YuvToRgbTransform`, and `OutputFrameMetadata`
- the current `8-bit I400/I420/I422/I444 -> ArgbIntFrame` subset
- the current `10-bit` / `12-bit I400/I420/I422/I444 -> ArgbLongFrame` subset

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/output/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/output/**`

Dependency:

- Starts after Track C freezes the `DecodedPlanes` contract.

### Track F: Public Runtime Integration

Goal: connect the internal decode pipeline to public frame delivery.

Status: complete.

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

- `readFrame()` now returns real public frames for the current supported subset.
- Runtime helpers centralize reference slots, output policy, and public-frame materialization.
- `show_existing_frame` already reuses stored reconstructed surfaces for the current path, including high-bit-depth and wider-chroma stored-surface coverage.
- Public runtime filtering already covers `decodeFrameType` and the current grain / stored-surface behavior.

This track is now closed. Remaining output behavior work belongs to other tracks:

- film-grain-aware final presentation is already handled by Track D; any later quality/fidelity expansion should follow that track's ownership
- broader inter/reference pixel decode still belongs to Track C
- future feature-matrix regression expansion should follow the owning implementation track

Write scope:

- Existing `src/main/java/org/glavo/avif/decode/**`
- New `src/main/java/org/glavo/avif/internal/av1/runtime/**`
- Matching public reader tests

Dependencies:

- Starts after Track D and Track E are both stable.

### Track G: Oracle and Regression Harness

Goal: replace existence-only tests with fixed oracle coverage and end-to-end validation.

Status: complete.

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
- reader contract and first-pixel public-reader tests
- output and reconstruction unit tests
- named payload fixtures that replace the old runtime brute-force searches
- deterministic wider-chroma and palette fixture coverage for reconstruction and public-reader reuse

This track is now closed. Future test additions belong to the owning implementation track unless a
new dedicated oracle-harness effort is opened later.

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

- `Av1ImageReader` can return `ArgbIntFrame` for representative `8-bit I400/I420/I422/I444` visible key/intra samples in the current still-picture subset, and the stored-surface `show_existing_frame` path can already surface the widened `I422/I444` subset.
- Pixel arrays, metadata, and source-equivalence checks are stable across all `BufferedInput` adapters.

Status:

- partially achieved
- first-pixel output is stable for the current serial key/intra subset, including wider-chroma still-picture output, the current palette subset, and stored-surface reuse
- the milestone stays open because broader residual coverage, broader real wider-chroma fixtures, broader real palette coverage, and inter/reference output are still incomplete

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

- in progress
- stored-surface reuse is already stable for the current minimal path
- a real inter baseline now exists, but broader parsed-stream inter output still stops at richer motion-compensation gaps
- `intrabc` is no longer absent, but it is still limited to the current synthetic bilinear plus generated-header-bridged subset

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

- partially achieved
- the supported-subset presentation pipeline is already wired and tested
- the milestone remains open only for broader feature-matrix coverage

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
