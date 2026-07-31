# FrontCodedStringColumn H2 — Phase 2 JMH decision gate

Per `docs/design/2026-07-09-frontcoded-allocation-impl-plan.md` Phase 2: before touching production code,
measure whether `findKeyPosition`'s cost is the per-hop `new String(...)` or the restart-walk decode
itself, and whether an unsigned byte-lexicographic compare actually beats `String.compareTo`.

**Benchmark:** `evita_test/evita_performance_tests/.../spike/FrontCodedFindKeyBenchmark.java` (new, this
session). `FrontCodedStringColumn` is package-private, so the benchmark reproduces its encode/decode/
restart-walk algorithm directly — verified byte-for-byte against the real class's source, and self-checked
at `@Setup` time (decode fidelity vs. the original keys, plus string-compare vs. byte-compare agreement on
both a hit and a miss probe, for all four key shapes including BMP-accented) before any timing number was
trusted. Reviewed by `advisor` before implementation; two of its requested fixes are load-bearing: the
probe is encoded to UTF-8 **inside** the timed byte-compare method (not pre-encoded in `@Setup`, which
would have hidden that real per-call cost), and a third `decodeOnly` variant replays the exact binary-search
hop sequence with no comparison, isolating the walk's cost from the compare's cost.

Full matrix: 4 key shapes (product codes, EAN-13, URLs, BMP-accented Latin) × 3 leaf fill sizes (16 / 48 /
64) × 6 methods (`stringCompare`/`byteCompare`/`decodeOnly`, each × hit/miss), `-prof gc`, `@Warmup(3,1s)
@Measurement(5,1s) @Fork(1)`. ~10 minutes, 0 self-check failures, raw results in
`/tmp/claude-1003/-www-oss-evita-760-more-optimized-data-structures/32a39082-9246-45b4-97c7-cf143bedd59e/scratchpad/frontcoded-findkey/results.json`.

## Result

| shape | size | strCompare hit (ns) | byteCompare hit (ns) | decodeOnly hit (ns) | strCompare B/op | byteCompare B/op | decodeOnly B/op |
|---|--:|--:|--:|--:|--:|--:|--:|
| CODE | 16 | 190.3 | 124.1 | 106.0 | 192.0 | 24.0 | 0.0 |
| CODE | 64 | 241.4 | 166.8 | 138.4 | 288.0 | 24.0 | 0.0 |
| EAN13 | 64 | 249.6 | 168.2 | 137.9 | 336.0 | 32.0 | 0.0 |
| URL | 64 | 241.8 | 182.7 | 140.9 | 432.0 | 48.0 | 0.0 |
| ACCENTED | 16 | 283.7 | 147.9 | 104.8 | 832.0 | 128.0 | 0.0 |
| ACCENTED | 64 | 382.1 | 200.9 | 137.4 | 1248.0 | 128.0 | 0.0 |

(full 12-row × 6-metric table in the raw JSON; every one of the 12 shape×size combinations shows the same
pattern, hit and miss alike.)

**Aggregate, across all 12 shape×size combinations:**
- string-compare / byte-compare time ratio: **1.58× (hit), 1.32× (miss)** — byte-compare wins in every
  single row, no exceptions.
- decode-only / byte-compare time ratio: **0.80** — the walk is ~80% of byte-compare's own cost, so
  byte-compare's overhead over the bare walk (probe UTF-8 encode + the compare loop) is small; string-compare
  sits well above *both*.
- byte-compare B/op: **24–128 B/op** (scales with probe UTF-8 length — this is entirely the one
  `probe.getBytes(UTF_8)` call per `findKeyPosition` invocation, not the walk).
- decode-only B/op: **exactly 0.0 in every row.** The restart-walk itself allocates nothing once the scratch
  buffer is warm — every byte of string-compare's 192–1248 B/op is the `new String(...)` object header +
  `char[]`, confirming H2's mechanism directly rather than by inference.
- string-compare B/op: **192–1248 B/op**, scaling with key length *and* with UTF-8 byte-width (ACCENTED,
  whose glyphs are 2-byte UTF-8, allocates ~4–6× more per op than CODE at the same leaf size — the `String`
  constructor pays for the UTF-16 `char[]` decode on top of the raw bytes).

## Decision gate

Plan's gate: *"Proceed to H2 if byte_compare shows a meaningful ns/op win and the B/op drop confirms the
allocation is the `new String`, not the walk. Stop if the restart-walk dominates and String removal barely
moves either metric."*

**Gate passes, clearly, on both conditions:**
1. Meaningful ns/op win — 1.3–1.9× faster, consistently, across every shape and size, hit and miss.
2. The B/op drop is the `String`, not the walk — decode-only allocates **zero**, so 100% of string-compare's
   192–1248 B/op is directly attributable to `new String(...)`, and byte-compare's small residual (24–128
   B/op) is fully accounted for by the one probe-encode call, not the walk.

The **ACCENTED** shape (the BMP-safe predicate's actual target) shows the *largest* win of the four shapes
(1.92× at size 16, 1.90× at size 64) — multi-byte UTF-8 glyphs make the `String` constructor's UTF-16
decode proportionally more expensive, which is exactly the case H2's BMP predicate (not just ASCII) was
designed to also cover.

**Caveat (per the plan's own framing, §Phase 2):** this micro confirms the *mechanism* and the *per-call
direction* — it does not reproduce the ~6.5 GB absolute figure the ALIVE-churn alloc profile attributed to
`decodeAt` (that attribution already stands on its own, from
`docs/reports/2026-07-09-frontcoded-copyrangeto-flatbuffer-remeasure.md` §3). Nothing here contradicts it;
this benchmark answers a different, narrower question ("is it really the String") and the answer is yes.

## Next step

Gate passed → proceeding to Phase 3 (H2 implementation) per the plan's §3, in a worktree, per Johnny's
go-ahead. See `docs/design/2026-07-09-frontcoded-allocation-impl-plan.md` for the update.
