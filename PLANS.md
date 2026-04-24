# AV1 Remaining Work Plan

## Summary

Most of the decoder foundation is already complete. The remaining work is now concentrated in one
main area: the reconstruction core and the real-stream coverage that proves it.

The repository already has:

- a working AV1 syntax front end
- stable decoded-plane and reference-surface contracts
- a narrow but real pixel-producing decode path
- public `ArgbIntFrame` and `ArgbLongFrame` output
- stored-surface `show_existing_frame` reuse
- deterministic regression fixtures and oracle coverage

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

## Current Baseline

### Already Implemented

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

### Remaining Decode Boundary

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

## Completed Areas

### Syntax Front End

The syntax front end is effectively complete for the current rollout:

- OBU reader
- sequence header parsing
- frame header parsing
- tile/block syntax decoding
- stored CDF state
- stored temporal-motion state

Future syntax fixes should be driven by specific remaining reconstruction needs.

### Frame Header and Film Grain Parameters

Frame-header grain parsing and normalized storage are complete.

Remaining grain work belongs to presentation fidelity and synthesis behavior.

### Postfilter and Grain Ordering

The current supported subset already has the correct stage ordering:

- reconstruction
- postfilter shell
- stored postfilter/post-super-resolution/pre-grain reference surface
- optional presentation-time grain application

Loop filter, CDEF, restoration, and grain synthesis still need broader fidelity over time, but the
contract and ownership are settled.

### Output Conversion

Public output conversion is complete for the current supported subset:

- `8-bit I400/I420/I422/I444 -> ArgbIntFrame`
- `10-bit` / `12-bit I400/I420/I422/I444 -> ArgbLongFrame`

Later changes here should be incremental fidelity or feature coverage work, not a structural
rewrite.

### Public Runtime Integration

Public runtime integration is complete for the current supported subset:

- runtime reference slots
- output policy
- public frame materialization
- stored-surface `show_existing_frame`
- current `decodeFrameType` filtering
- current grain-aware presentation contract

Future public behavior changes should only happen when the reconstruction core widens.

### Oracle and Regression Harness

The regression harness is already in the desired shape:

- named fixed fixtures
- exact-oracle syntax tests
- first-pixel public-reader tests
- reconstruction/output unit tests
- deterministic wider-chroma and palette regressions

New tests should now be added as part of the owning implementation change, not as a separate
planning area.

## Remaining Main Work Area: Reconstruction Core

This is now the only major unfinished area.

### Goal

Finish the reconstruction core until the decoder can handle a substantially broader set of real AV1
streams without falling back to `NOT_IMPLEMENTED`.

### Already In Place

- decoded-plane storage and the first-pixel key/intra path
- direct parsed high-bit-depth still-picture subset:
  - `10-bit I420`
  - `12-bit I444`
- high-bit-depth dequantization through current `8/10/12-bit` QTX tables
- non-zero luma/chroma `DCT_DCT` reconstruction for the current square and rectangular subset whose
  axes stay within `64`
- current `I420/I422/I444` CFL subset
- current bitstream-driven chroma residual coverage for the first `I420/I422` paths
- serial multi-tile reconstruction inside the current supported subset
- minimal synthetic and first real wider-chroma palette reconstruction coverage
- current wider-chroma key/intra first-pixel subset
- first inter/reference subset:
  - single-reference prediction
  - average-compound prediction
  - integer-copy prediction
  - fixed-filter and block-resolved `SWITCHABLE` subpel sampling
  - one larger-residual real inter integration path
  - first parsed-stream fixed-filter integration coverage
  - first hybrid public-reader parsed inter success path
- first key/intra plus inter horizontal super-resolution subset, including geometry-remapped stored
  reference surfaces
- first synthetic same-frame `intrabc` bilinear subset and first generated-header real
  syntax-and-reconstruction integration path

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
