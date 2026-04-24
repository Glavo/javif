# AV1 Remaining Work Plan

## Current Baseline

The public decode path produces real frames for a bounded AV1 subset. Unsupported syntax must keep
failing explicitly with a stable `NOT_IMPLEMENTED` boundary instead of producing approximate output.

Supported end-to-end behavior:

- Raw AV1 OBU input with serial execution.
- Visible `KEY` / `INTRA` still-picture paths.
- `8-bit I400/I420/I422/I444 -> ArgbIntFrame`.
- `10-bit/12-bit I420/I422/I444 -> ArgbLongFrame`, including standalone and combined frame
  assembly coverage.
- Key/intra reconstruction for directional, non-directional, smooth, filter-intra, and CFL
  prediction.
- Luma/chroma palette reconstruction for synthetic fixtures and direct parsed `I420/I422/I444`
  still-picture streams, including standalone frame-header/tile-group input, combined-frame input,
  chroma palette output, chroma residual overlays, and clipped right/bottom frame-edge footprints.
- Explicit transform-type residual reconstruction for modeled residual units, including `DCT_DCT`,
  `ADST`, `FLIPADST`, `IDTX`, and horizontal/vertical one-dimensional transform classes across
  the supported transform sizes whose axes stay within `64` samples.
- Parsed chroma residual fixture paths for `I420/I422/I444`, backed by explicit chroma transform
  units in `TransformLayout`, including clipped fringe footprints, wider-chroma `I422`,
  unsubsampled `I444`, multi-unit `I420`, and larger-transform chroma token coverage.
- Stored-surface `show_existing_frame` output and reference-surface reuse.
- Real bitstream-driven multi-tile first-pixel still-picture paths for `I420/I422/I444`, including
  horizontal, vertical, `2x2`, combined-frame, standalone, and split tile-group variants.
- Direct real parsed `I422/I444` public-layout paths for still output, high-bit-depth output,
  stored-surface reuse, multi-tile first-pixel streams, and the current self-contained inter subset.
- First inter/reference subset: single-reference prediction, average-compound prediction,
  integer-copy prediction, bit-depth-preserving fixed-filter and block-resolved `SWITCHABLE`
  subpel prediction, and normative horizontal super-resolution for key/intra, public
  still-picture, synthetic inter, and bitstream-derived inter reconstruction paths.
- Self-contained parsed-stream inter public-reader paths for standalone and combined `FRAME`
  inputs across `I420/I422/I444`, backed by a preceding parsed reference frame rather than injected
  parser metadata.
- Parsed-stream `intrabc` public-reader paths for standalone frame-header/tile-group input and
  combined `FRAME` input, including `allow_intrabc` frame-header parsing, decoded same-frame-copy
  leaf coverage, and luma/chroma copy validation against a pre-copy same-frame oracle.
- Postfilter ordering now runs reconstruction -> loop filter -> CDEF -> restoration -> stored
  reference surface, with inactive loop filter/restoration preserved exactly, block-indexed CDEF
  applied from decoded `cdefIndex` syntax and frame strengths, active loop filter/restoration
  rejected as stable `NOT_IMPLEMENTED`, and film grain kept as presentation-only output synthesis.

## Remaining Decode Boundary

- Motion-compensation coverage still needs richer inter/reference features beyond the current
  bit-depth-preserving single-reference, average-compound, fixed-filter, switchable-filter, and
  super-resolution subset.
- Active loop-filter and loop-restoration pixel filtering still need full block-edge masks,
  restoration-unit syntax, and coefficient-driven filtering before those frame features can decode
  instead of failing at the stable `NOT_IMPLEMENTED` boundary.

## Stable Contracts

The following contracts are stable and should be preserved:

- `DecodedPlanes`: reconstructed Y/U/V planes before ARGB conversion.
- `TransformLayout`: luma units plus shared U/V chroma transform units in bitstream order.
- `ReferenceSurfaceSnapshot`: frame header, syntax result, decoded planes, final tile CDF snapshots,
  and temporal-motion state for reference reuse.
- `ResidualLayout`: luma and chroma residual units with explicit transform types.
- `FilmGrainParams`: normalized film grain parameters inside `FrameHeader`.

## Main Work Priority

1. Broaden motion compensation fidelity and feature coverage.
2. Implement active loop-filter and loop-restoration pixel filtering once the required syntax
   state is represented.

## Exit Criteria

This remaining work is complete when decoded frames produce stable `DecodedPlanes` across a
materially broader real-stream subset and reference surfaces refresh and reuse correctly across
that subset.

## Validation And Maintenance

Every new decoder capability must ship with the narrowest stable test that proves it: exact-oracle
unit tests when possible, synthetic integration tests when isolation is required, and real
parsed-stream fixtures whenever the capability is intended to work from public input.

Keep this file short and status-oriented. It should describe what works, what remains blocked, and
the next highest-value work; it should not become a changelog or speculative design document.
