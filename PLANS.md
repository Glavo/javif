# AV1 Remaining Work Plan

## Status

The public decoder supports a bounded real AV1 subset and must fail unsupported syntax with a
stable `NOT_IMPLEMENTED` boundary rather than producing approximate output.

Current supported areas:

- OBU input, visible `KEY` / `INTRA` still-picture output, `I400/I420/I422/I444` layouts, and
  `8-bit -> ArgbIntFrame` plus `10/12-bit -> ArgbLongFrame` output.
- Key/intra reconstruction, palette paths, transform-type residuals, chroma residuals, clipped
  frame edges, multi-tile still-picture fixtures, and horizontal super-resolution.
- Stored-surface `show_existing_frame`, reference refresh/reuse, reference syntax metadata
  preservation, temporal-motion inheritance/projection, and the current bounded inter subset.
- Self-contained public-reader fixtures for parsed still, inter, `intrabc`, high-bit-depth,
  multi-tile, super-resolution, and reference-reuse paths.
- Postfilter order is reconstruction -> loop filter -> CDEF -> restoration -> reference surface;
  active loop filtering, CDEF, decoded loop-restoration units, WIENER/SGRPROJ restoration, and
  presentation-only film grain are covered.

## Decode Boundary

No active decode boundary is currently listed. Add entries here only for intentional stable
`NOT_IMPLEMENTED` boundaries, not transient bugs.

## Contracts To Preserve

- `DecodedPlanes`: reconstructed Y/U/V planes before ARGB conversion.
- `TransformLayout`: luma units plus shared U/V chroma transform units in bitstream order.
- `ResidualLayout`: luma and chroma residual units with explicit transform types.
- `RestorationUnitMap`: decoded per-plane loop-restoration units and coefficients.
- `ReferenceSurfaceSnapshot`: frame header, syntax result, decoded planes, final tile CDF snapshots,
  and projected temporal-motion state for reference reuse.
- `FilmGrainParams`: normalized film grain parameters inside `FrameHeader`.

## Remaining Work

No planned remaining work is currently listed. Future work should be added only when a stable
decode boundary, missing fixture class, or major unsupported AV1 area is identified.

## Completion Criteria

The current phase is complete: the supported real-stream subset produces stable `DecodedPlanes`,
refreshes and reuses reference surfaces correctly, covers combined postfilter/reference fixtures,
and keeps unsupported syntax explicit instead of silently approximating it.

## Validation

Every decoder capability needs the narrowest stable test that proves it: exact-oracle unit tests
when possible, synthetic integration tests when isolation is required, and real public-reader
fixtures when the feature is intended to work from public input.
