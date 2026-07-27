# FrontCodedStringColumn keyAt/cursor.value() flush-path allocation — plan

## Origin

The production remeasure of the BMP-safe byte-compare fast path
(`docs/reports/2026-07-09-frontcoded-h2-production-remeasure.md`) found that `FrontCodedStringColumn`'s
remaining write-path allocation (~4.75 GiB in the profiled workload, ~98% of what's left in the
`FrontCoded` ALIVE-churn category) traces entirely through `keyAt(int)`, reached via
`TransactionalBucketBPlusTree.SingleLeafBucketCursor.value()` — not through `findKeyPosition`, which the
byte-compare fast path already made allocation-free. This document plans the follow-up: can that
remaining allocation be removed too?

## Where the allocation actually happens

`SingleLeafBucketCursor` is constructed **only** by `LeafPageHandleImpl.cursor()`
(`TransactionalBucketBPlusTree.java:1190`), reached through `tree.leafPageHandles()` — the **granular
leaf-page emission path**, not the per-mutation path. Concretely, it fires from each index's
`collectChangedPages()`:

- `GlobalUniqueIndex.java:790-807`
- `OwnerUniqueIndex.java` (mirrors GlobalUniqueIndex)
- `InvertedIndex.java:979-999`

gated by `PageStreamRegistry.collectChangedPages`'s `if (freshLeaf || handle.isDirty())`
(`PageStreamRegistry.java:347`) — **once per changed leaf, per flush**, walking **every live entry in
that leaf**, not just the mutated one. That's the whole story: a single-record update to a 256-entry
leaf (the production `GlobalUniqueIndex`/`OwnerUniqueIndex` block size) re-decodes all 256 keys back to
`String` at the next flush, only one of which changed. This is why it dwarfs `findKeyPosition`
(O(log n) decodes per lookup, now byte-only) in a write-churn allocation profile: it's O(entries-per-leaf)
String materialization per dirty leaf, every flush cycle.

Per-mutation `keyAt` calls elsewhere (head-removal checks, split separators) are O(1) and not the driver.

## Why H2's technique does not transfer

H2 worked because `findKeyPosition`'s decoded `String` was a **discardable comparison operand** — once
compared, it could be thrown away, so comparing raw UTF-8 bytes instead skipped the allocation entirely.

Here the decoded `String` is not discardable. All three call sites store it into `pageValues`/`pageBuckets`,
which become part of a persisted `LeafPage` (`GlobalUniqueIndex.java:598`, mirrored in OwnerUniqueIndex and
InvertedIndex), passed as `Serializable[]`/`ValueToRecord[]` to a Kryo-serialized storage part
(`GlobalUniqueIndexLeafPagePart`, etc.). The `String` **is the required output** — it's what gets written
to disk. A byte-compare-style fast path has nothing to compare against; there's no shortcut that skips
materializing the value and still produces the same persisted bytes, **under today's storage contract**.

That qualifier matters: the `String` is load-bearing *because* the current pipeline is
`FrontCoded bytes → new String → Kryo → UTF-8 bytes on disk` — a round-trip through an object that gets
converted straight back into the same bytes it started as. If the pipeline changed to skip the
intermediate `String`, the allocation stops being load-bearing. That's the one approach worth pursuing;
see Option B below.

## Confirmed: no BWC constraint

The granular leaf-page-part format (`GlobalUniqueIndexLeafPagePart`, `InvertedIndexLeafPagePart`, and
siblings) was introduced entirely on this feature branch (commits `e5f57f7a0`, `8baa4e572`, `64d0ffe48`,
`9996bf9ea`, `f6d4994d1`, `fa01ba65f`, ...) targeting `2026.2-SNAPSHOT`. **None of it has shipped** — the
latest release tag is `v2026.1.17`, which predates all of them. Per the project's
serialVersionUID/BWC policy (intra-dev format changes need no compatibility shim; BWC is owed only across
released minors), the on-disk leaf-page payload format is free to change with no migration cost.

## Options

### Option A — per-entry String cache in FrontCodedStringColumn (dispreferred)

Cache the decoded `String` per entry so a repeat `keyAt` call across the same commit's read-mostly window
doesn't re-decode. Rejected as the primary approach:

- Collides with `duplicate()`'s structural sharing of `data`/`restartOffsets` (H3) — a cache would need
  the same copy-on-write semantics, or it silently goes stale across a shared duplicate.
- Collides with `encode()`'s whole-blob replacement on every mutation (H1) — any cache needs an
  invalidation story, and "invalidate everything on any encode() call" defeats the purpose for a leaf that
  mutates once then flushes (the common case).
- The flush walk visits each entry exactly once per flush anyway — a cache only pays off if populated
  *at mutation time* and correctly re-indexed across `insertKeyAt`/`removeKeyAt`'s slot shifts, which is
  real, fiddly work for an uncertain win.

Documented for completeness; not recommended.

### Option B — bypass String materialization in the flush/load serialization path (preferred direction)

Since the format is unreleased, restructure the granular leaf-page payload so a FrontCoded-backed page
writes/reads raw UTF-8 bytes straight from `FrontCodedStringColumn`'s internal buffer, never materializing
a `String` on either side:

- **Write side**: add an additive `ValueColumn` SPI method, something like
  `boolean writeValueAt(int index, Kryo kryo, Output output)`, with a **default** implementation that falls
  back to `kryo.writeClassAndObject(output, keyAt(index))` (today's behavior — zero change for
  `BoxedObjectColumn`/`LongValueColumn`/`IntValueColumn`/`InstantValueColumn`). `FrontCodedStringColumn`
  overrides it to write a length-prefixed raw byte run straight from `decodeAtBytes`'s scratch buffer —
  reusing the existing zero-allocation decode path `findKeyPosition`'s fast path already established.
  `collectChangedPages`'s three call sites switch from `cursor.value()` + `kryo.writeClassAndObject` to
  this SPI call.
- **Load side (the other half — easy to plan only the write side and forget this)**: the page reader
  currently reconstructs `Serializable[] values` via `kryo.readClassAndObject`, then presumably calls
  `insertKeyAt`/an equivalent bulk-load path on the column, which today takes `String`/boxed values. A
  symmetric raw-bytes read path would let `FrontCodedStringColumn` bulk-load directly from the on-disk byte
  run into its own encoding, skipping `String` materialization on load too — a second win, not just parity.
- **Format change scope**: today's per-entry `kryo.writeClassAndObject` also repeats a class tag per
  element even though a page's entries are homogeneously typed. Since the format is free to change, this
  is a natural point to also write the value type once per page instead of once per entry — a small
  additional win, bundled into the same change.

This is a real, cross-cutting change: it touches `LeafPage`'s payload shape, the `BucketCursor`/
`ValueColumn` SPI, and both the write and read payload methods of (at least) `GlobalUniqueIndexLeafPagePartSerializer`,
`OwnerUniqueIndexLeafPagePartSerializer`, and `InvertedIndexLeafPagePartSerializer` (or their equivalents),
plus each of those three indexes' `collectChangedPages`/load-reconstruction code.

## Sizing the prize against the cost

- **Prize**: eliminates the ~4.75 GiB (in the profiled workload) `keyAt`/`cursor.value()` allocation —
  GC pressure reduction on the flush path for String-attribute `GlobalUniqueIndex`/`OwnerUniqueIndex`/
  `InvertedIndex` leaves.
- **Important caveat from the tuned-config remeasure**: under a config with compaction/fsync/flush
  overhead removed, `FrontCoded` allocation was already **flat** (−0.5%) and did not move ALIVE wall-clock
  time — `OffsetIndex` (compaction) was the dominant wall-clock cost center (−31.1%), not FrontCoded
  allocation. This suggests the keyAt/cursor.value() allocation is currently a GC-CPU-percentage cost, not
  a demonstrated wall-clock throughput bottleneck, in the workload measured so far. Whether that holds
  under higher concurrency / larger heaps / GC-pressure-sensitive deployments is untested.
- **Cost**: touches persistence-critical code (three indexes' write AND read paths) in an area that only
  recently landed and is not yet released — real correctness risk if the write/read symmetry isn't exact,
  though caught early (dev-only, pre-release) rather than as a field bug.

## Recommendation

Don't bundle this with H2's ship decision — H2 is a clean, contained, already-measured win; this is a
separate, larger initiative with a real but currently-unquantified-at-wall-clock prize. Recommended next
step, mirroring the H1/H2/H3 methodology used so far: **measure before building**. A small isolated JMH
benchmark (Kryo-serialize N front-coded String keys via today's `keyAt`+`writeClassAndObject` path vs. a
prototype raw-bytes write) would establish the actual serialization-time win (not just B/op) before
committing to the three-index refactor. If Johnny wants to proceed, that JMH spike is Phase 1; the SPI +
three-index refactor is Phase 2, gated on the spike's result the same way H2's Phase 3 was gated on its
JMH decision gate.

## Status

Plan only — not started. Awaiting Johnny's go/no-go, and if go, awaiting the Phase 1 JMH spike result
before any production code changes.
