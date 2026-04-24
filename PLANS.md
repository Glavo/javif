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
  - smooth intra with clipped right/bottom visible footprints
  - filter intra, including clipped right/bottom visible footprints
  - CFL
  - minimal palette, including chroma palette plus chroma residual overlays
  - current `DCT_DCT` residual space with transform axes up to `64`
  - first parsed chroma residual fixture paths for `I420/I422/I444`
- current stored-surface `show_existing_frame` path
- real bitstream-driven multi-tile first-pixel still-picture paths for `I420/I422/I444`,
  including horizontal, vertical, `2x2`, combined-frame, standalone, and split tile-group variants
- the first inter/reference subset:
  - single-reference prediction
  - average-compound prediction
  - integer-copy prediction
  - fixed-filter and block-resolved `SWITCHABLE` subpel prediction
  - first horizontal super-resolution subset
  - first hybrid parsed-stream inter success path with `I420/I422/I444` public-layout coverage that
    still relies on injected parser metadata for the broader fixture

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

- Transform/coefficient coverage still needs broader transform types and coefficient patterns beyond
  the current `DCT_DCT <= 64-axis` reconstruction subset.
- Chroma residual coverage still needs full chroma transform-layout modeling and broader real
  parsed chroma token coverage beyond the current minimal `I420/I422/I444` fixture paths.
- Palette coverage still needs direct parsed wider-chroma palette still-picture streams and broader
  palette edge cases.
- `intrabc` coverage still needs broader parsed-stream syntax and reconstruction fixtures.
- Inter/reference coverage still needs self-contained parsed-stream inter support without injected
  parser metadata, plus richer motion compensation.
- Super-resolution still needs higher-fidelity AV1 resampling behavior and broader parsed-stream
  inter super-resolution coverage.
- `I422/I444` still need broader real parsed-stream coverage beyond direct still-picture output and
  stored-surface reuse.
- The next practical public decode gaps are:
  - parsed-stream inter without injected parser metadata
  - richer motion compensation
  - broader parsed-stream `intrabc`
  - direct parsed wider-chroma palette streams
  - broader parsed chroma residual/token coverage
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

The concrete missing behavior is listed in `Remaining Decode Boundary` above. This section only
records execution priority and completion conditions.

### Priority Order

1. Broaden real parsed-stream inter coverage without injected parser metadata.
2. Broaden motion compensation fidelity and feature coverage.
3. Broaden parsed-stream `intrabc`.
4. Broaden palette and wider-chroma real fixture coverage.
5. Broaden transform/coefficient coverage beyond the current `DCT_DCT <= 64-axis` subset.
6. Broaden super-resolution fidelity and parsed-stream coverage.

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
