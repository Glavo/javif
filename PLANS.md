# AV1 Remaining Work Plan

## Summary

Most of the decoder foundation is already complete. The remaining work is concentrated in the
reconstruction core and the real-stream coverage that proves it.

`Av1ImageReader.readFrame()` already returns real frames for a narrow supported subset:

- raw AV1 OBU input
- serial execution
- visible `KEY` / `INTRA` still-picture paths
- `8-bit I400/I420/I422/I444 -> ArgbIntFrame`
- first direct high-bit-depth still-picture subset:
  - `10-bit I420 -> ArgbLongFrame`
  - `12-bit I444 -> ArgbLongFrame`
- current key/intra reconstruction subset:
  - directional and non-directional intra
  - filter intra
  - CFL
  - minimal palette
  - current `DCT_DCT` residual space with transform axes up to `64`
- current stored-surface `show_existing_frame` path
- the first inter/reference subset:
  - single-reference prediction
  - average-compound prediction
  - integer-copy prediction
  - fixed-filter and block-resolved `SWITCHABLE` subpel prediction
  - first horizontal super-resolution subset
  - first hybrid parsed-stream inter success path that still relies on injected parser metadata for
    the broader fixture

Everything outside that subset must continue to fail explicitly with a stable
`NOT_IMPLEMENTED` boundary rather than silently producing incorrect output.

## Implemented

- `BufferedInput`, public reader/config/error/result types, and the raw OBU pipeline
- frame-level and tile/block-level syntax decoding, including stored CDF and temporal-motion
  snapshots
- stable reconstruction/output contracts:
  - `DecodedPlane`
  - `DecodedPlanes`
  - `ReferenceSurfaceSnapshot`
- a real reconstruction path through:
  - `FrameReconstructor`
  - `IntraPredictor`
  - dequantization
  - inverse transform
- public output wiring through:
  - `ArgbOutput`
  - `ArgbIntFrame`
  - `ArgbLongFrame`
  - stored-surface reuse
  - film-grain-aware presentation ordering

## Remaining Decode Boundary

- Non-zero reconstruction currently covers the current luma/chroma `DCT_DCT` subset whose
  transform axes stay within `64` samples, including the larger square and rectangular sizes needed
  by the current real inter fixture, but not the broader transform-type and coefficient space.
- The public reader already consumes:
  - a minimal real bitstream-derived `I420` chroma residual path
  - a first real bitstream-derived `I422` chroma residual path
  - a first direct parsed high-bit-depth still-picture subset
- Stable real bitstream-driven multi-tile first-pixel fixtures are still missing. Multi-tile
  support is stronger at the synthetic frame-syntax/runtime level than in the real fixture corpus.
- Full chroma transform-layout modeling and broader chroma token coverage are still incomplete.
- Palette reconstruction now covers the current synthetic `I400/I420/I422/I444` subset plus the
  first deterministic real wider-chroma palette fixture, but broader edge cases and direct parsed
  wider-chroma palette streams are still missing.
- `intrabc` reconstruction now covers:
  - a first synthetic same-frame `BILINEAR` subset
  - a first generated-header real syntax-and-reconstruction integration path

  Broader parsed-stream `intrabc` coverage is still missing.
- Inter/reference reconstruction now covers:
  - single-reference and average-compound prediction
  - integer-copy prediction
  - fixed-filter and block-resolved `SWITCHABLE` subpel sampling
  - a first geometry-remapped stored-reference path for post-super-resolution surfaces
  - a first hybrid parsed-stream public inter success path

  Broader self-contained parsed-stream inter support and richer motion compensation are still
  incomplete.
- Key/intra reconstruction already covers a minimal horizontal super-resolution subset, and
  inter/reference reconstruction already covers a first horizontal super-resolution subset, but
  higher-fidelity AV1 resampling behavior and broader parsed-stream inter super-resolution coverage
  are still incomplete.
- `I422/I444` now have direct parsed still-picture output and stored-surface reuse, but still lack
  broader real parsed-stream fixture coverage beyond the current minimal subset.
- The next practical public decode gaps are:
  - broader chroma residual coverage
  - broader palette coverage
  - broader `intrabc`
  - richer inter motion compensation
  - higher-fidelity super-resolution and postfilter behavior

## Frozen Internal Contracts

The following contracts are already stable and should be preserved:

- `DecodedPlanes`
  - Y/U/V planes after reconstruction and in-loop/post-filter ordering, before ARGB conversion
- `ReferenceSurfaceSnapshot`
  - `FrameHeader`
  - `FrameSyntaxDecodeResult`
  - decoded planes
  - final tile CDF snapshots
  - temporal-motion state for reference reuse
- `ResidualLayout`
  - carries luma and chroma residual units
- `FilmGrainParams`
  - normalized inside `FrameHeader`

The following contract still needs broader semantic coverage, but its shape should remain stable:

- `TransformResidualUnit`
  - transform size
  - transform type
  - end-of-block index
  - dense coefficient storage
  - context bytes needed by later stages

## Remaining Main Work Area: Reconstruction Core

This is now the only major unfinished area.

### Goal

Finish the reconstruction core until the decoder can handle a substantially broader set of real AV1
streams without falling back to `NOT_IMPLEMENTED`.

The currently implemented reconstruction scope is summarized in `Implemented` and
`Remaining Decode Boundary` above. The remaining work below is the part that still blocks this area
from being considered complete.

### Remaining Gaps

- richer AC coverage beyond the current `64`-axis `DCT_DCT` subset
- broader transform-type support
- fuller chroma transform-layout and coefficient coverage beyond the current `I420/I422` uniform
  visible-grid path
- broader real bitstream-driven palette coverage and palette edge-case coverage
- one dedicated direct parsed wider-chroma palette stream
- broader inter motion compensation beyond the current:
  - single-reference
  - average-compound
  - integer-copy
  - fixed-filter / block-resolved-`SWITCHABLE`
  subpel subset
- broader self-contained public-stream coverage for real parsed inter fixtures without injected
  parser metadata
- broader `intrabc` coverage beyond the current synthetic and generated-header subset
- broader super-resolution support beyond the current minimal horizontal subset
- stable real bitstream multi-tile first-pixel fixtures
- broader real parsed-stream `I422/I444` fixtures with richer residual and non-gray paths
- broader direct parsed high-bit-depth fixtures beyond the current still-picture subset

### Execution Order From Here

1. Broaden real parsed-stream inter coverage without injected parser metadata.
2. Broaden motion compensation fidelity and feature coverage.
3. Broaden parsed-stream `intrabc`.
4. Broaden palette and wider-chroma real fixture coverage.
5. Broaden transform/coefficient coverage beyond the current `DCT_DCT <= 64-axis` subset.
6. Broaden super-resolution fidelity and parsed-stream coverage.
7. Add real multi-tile first-pixel fixtures that exercise the already-open serial path.

### Exit Criteria

This remaining work area is complete when:

- decoded frames produce stable `DecodedPlanes` across a materially broader real-stream subset
- reference surfaces can be refreshed and reused across that broader subset
- the public reader no longer depends on injected parser metadata for the current real parsed inter
  fixtures
- the remaining `NOT_IMPLEMENTED` boundary has moved past the current reconstruction-core gaps into
  clearly narrower, non-core feature gaps

## Validation Policy

Every new decoder capability must ship with the narrowest stable test that proves it:

- exact-oracle unit test when possible
- synthetic integration when unit isolation is required
- real parsed-stream fixture whenever the capability is supposed to work from public input

If a real bug is fixed, add a fixture-backed regression immediately.

## Maintenance Rule

This file should stay short and status-oriented.

It should describe:

- what already works
- what is still blocked
- what the next highest-value work is

It should not drift into a changelog or speculative redesign document.
