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

The decoder still stops before pixel reconstruction. `Av1ImageReader.readFrame()` completes frame assembly and structural syntax decode, then fails with `FRAME_DECODE / NOT_IMPLEMENTED` instead of returning a `DecodedFrame`.

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
- Tile parsing is already connected through `TileGroupHeaderParser`, `TileDataParser`, and `TileBitstreamParser`.
- Structural decoding already exists through `FrameSyntaxDecoder`, `TilePartitionTreeReader`, `TileBlockHeaderReader`, `TileTransformLayoutReader`, and `TileResidualSyntaxReader`.
- Reference slots already persist structural decode state, final tile CDF snapshots, and decoded temporal motion-field snapshots.

### Remaining Decode Boundary

- Valid bitstreams still stop before decoded-plane construction and public frame output.
- `show_existing_frame` only validates slot state and does not yet return a materialized output frame.
- Film grain parameters are not fully parsed and film grain synthesis is not implemented.
- Post-processing packages and runtime output packages do not yet exist.

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

## Remaining Work Tracks

### Track A: Syntax Tail

Goal: finish all syntax that must exist before reconstruction can begin.

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

Write scope:

- Existing `src/main/java/org/glavo/avif/internal/av1/decode/**`
- Residual/transform-related files under `src/main/java/org/glavo/avif/internal/av1/model/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/decode/**`

### Track B: Frame Header and Film Grain Parameters

Goal: finish frame-level parsing so later runtime and postfilter stages do not need parser changes.

Scope:

- Parse and normalize full film grain parameters.
- Tighten frame-level flags that are already parsed but not yet stored in reconstruction-ready form.
- Ensure frame-level state is sufficient for postfilter, runtime policy, and output decisions.

Exit criteria:

- `FrameHeader` fully represents grain-related and postfilter-related frame state.
- No valid grain-bearing frame fails in the parser with `NOT_IMPLEMENTED`.

Write scope:

- `src/main/java/org/glavo/avif/internal/av1/parse/FrameHeaderParser.java`
- `src/main/java/org/glavo/avif/internal/av1/model/FrameHeader.java`
- Related parser tests

### Track C: Reconstruction Core

Goal: create decoded planes from block syntax and residuals.

Scope:

- Add a new `org.glavo.avif.internal.av1.recon` package.
- Implement plane buffers and decoded-surface storage.
- Implement dequantization and inverse transforms.
- Implement intra prediction.
- Implement inter prediction and motion compensation.
- Implement `intrabc`, palette, CFL, and filter-intra pixel application.
- Implement super-resolution upscaling.
- Implement reference-surface refresh and reuse.

Execution order inside the track:

1. `8-bit I400 KEY/INTRA`
2. `8-bit I420 KEY/INTRA`
3. visible inter/reference frame support
4. wider pixel-format and bit-depth coverage

Exit criteria:

- A decoded frame produces stable `DecodedPlanes`.
- Reference surfaces can be refreshed and reused for later frames.

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/recon/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/recon/**`

Dependency:

- Starts only after Track A freezes the residual and transform contracts.

### Track D: Postfilter and Film Grain Application

Goal: implement the full decoder output pipeline after reconstruction.

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

Write scope:

- New `src/main/java/org/glavo/avif/internal/av1/output/**`
- Matching tests under `src/test/java/org/glavo/avif/internal/av1/output/**`

Dependency:

- Starts after Track C freezes the `DecodedPlanes` contract.

### Track F: Public Runtime Integration

Goal: connect the internal decode pipeline to public frame delivery.

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

Write scope:

- Existing `src/main/java/org/glavo/avif/decode/**`
- New `src/main/java/org/glavo/avif/internal/av1/runtime/**`
- Matching public reader tests

Dependencies:

- Starts after Track D and Track E are both stable.

### Track G: Oracle and Regression Harness

Goal: replace existence-only tests with fixed oracle coverage and end-to-end validation.

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

### M2: First-Pixel Output

Tracks:

- C
- E
- partial G pixel goldens

Acceptance:

- `Av1ImageReader` can return `ArgbIntFrame` for representative `8-bit I400/I420` visible key/intra samples.
- Pixel arrays, metadata, and source-equivalence checks are stable across all `BufferedInput` adapters.

### M3: Reference and Inter Frames

Tracks:

- C expansion
- F partial
- G multi-frame regressions

Acceptance:

- Inter/reference decode works for a representative sample set.
- Reference-surface refresh and reuse are stable.
- `show_existing_frame` produces output instead of failing at the old boundary.

### M4: Full Presentation Pipeline

Tracks:

- D
- F completion
- G feature-matrix expansion

Acceptance:

- Loop filter, CDEF, restoration, and film grain work in the expected order.
- `applyFilmGrain`, `outputInvisibleFrames`, and `decodeFrameType` have stable public behavior.
- `readAllFrames()` and `presentationIndex` behavior are locked by tests.

### M5: Coverage Closure

Tracks:

- Remaining format and bit-depth expansion
- Residual unsupported-path removal
- Additional regressions and oracle fixtures

Acceptance:

- Representative coverage exists for `8/10/12-bit`, `I400/I420/I422/I444`, key/intra/inter/switch, visible/invisible frames, `show_existing_frame`, super-resolution, loop filter, CDEF, restoration, and film grain on/off.

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

The file must continue to describe actual remaining work, not an outdated from-scratch port design.
