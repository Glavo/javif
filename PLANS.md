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

## Remaining Decode Boundary

- `intrabc` coverage still needs broader parsed-stream syntax and reconstruction fixtures.
- Motion-compensation coverage still needs richer inter/reference features beyond the current
  bit-depth-preserving single-reference, average-compound, fixed-filter, switchable-filter, and
  super-resolution subset.
- Postfilter behavior still needs higher-fidelity in-loop and presentation-path coverage.

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
2. Broaden parsed-stream `intrabc`.
3. Broaden postfilter behavior once reconstruction has enough stream coverage to validate it.

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
