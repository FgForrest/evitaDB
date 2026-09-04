---
title: Size the long-keyed and element-keyed B+ tree leaves to their content, and bound every reader that made torn
date: 2026-09-04
updated: 2026-09-04 20:15
status: accepted
kind: optimization
issues: [1486]
prs: []
areas: [evita_engine/index/bPlusTree, evita_engine/index/range, evita_engine/index/price, evita_test/evita_performance_tests/spike/trigram]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-08-01-bplustree-cursor-free-insert-path, 2026-09-04-millisecond-temporal-precision, 2026-07-10-more-optimized-data-structures]
---

# Size the long-keyed and element-keyed B+ tree leaves to their content, and bound every reader that made torn

`2026-09-03-content-sized-value-tree-columns` gave the bucket-keyed value tree columns whose physical
backing follows their live content. Its long-keyed sibling `TransactionalLongBPlusTree` never got the
same treatment, and it is what every `RangeIndex` is built on: a leaf allocated `long[512]` plus a
`V[512]` of references — 6,176 bytes — however little it held. Measured on the demo dataset, a range
index holds **4.0 points**, so the leaf ran at **0.78 % occupancy**. The leaf now carries its logical
capacity as a field and lets both arrays follow the content, through the same `ColumnSizing` policy.
The measured saving is **≈49.5 MB on the demo dataset**, over two disjoint sets of objects.

The same change was then applied to `TransactionalElementBPlusTree`, but only after it had first been
**declined** on this record and the declination's own reopening trigger had been evaluated. It is worth
**1.7 MB (5.4 %)** — two orders of magnitude less than the long tree, exactly as the declination
predicted — and was kept anyway, as a deliberate cost/benefit call recorded below rather than because
the number justified it.

Content sizing also **turned a benign torn read into an out-of-bounds one** in both trees, which an
adversarial review caught in the long tree after it had already been committed. Both trees now carry a
reader bound, and the flush path that legitimately does *not* need one has had its thread confinement
written down.

## Why

The earlier record left a bullet in its *Consequences* that reads, about the four sibling tree families:
"they exist in the thousands rather than the hundreds of thousands, so the same change there buys tens
of kilobytes." **That is true of the thing it was written about — the boxed bucket count, worth ~56
bytes per tree — and false of leaf arrays, which are worth ~6 KB per tree.** A reader applying it to
leaf sizing would have closed the largest remaining item in the issue without measuring it. Correcting
that inference is most of the reason this record exists.

The prize also had to be found rather than derived. The issue's own accounting looked only at attribute
value trees; range indexes sat outside it, and price indexes sat outside *that*. Three separate
estimates in this campaign had already been retracted for being arithmetic over an unaudited model, so
the number here comes from two probes reading the engine's own accounting on a real catalog, run
against a build of each commit's own source.

### Previous state

`BPlusLeafTreeNode` allocated `new long[blockSize]` and `Array.newInstance(valueType, blockSize)` in its
constructor and never resized either. Capacity and physical length were therefore always the same
number, which made every `keys.length` / `values.length` read ambiguous by construction — 23 of them —
and `isNearlyFull()` carried a javadoc arguing *deliberately* that reading the array length was safer
than reading the tree's configured block size, precisely because nothing enforced the coupling.

## Options considered

### Option A — content-size the leaf arrays behind an explicit logical capacity (chosen)

Give the leaf a `capacity` field holding the block size, park an empty leaf on the shared empty array,
grow geometrically on insert, and trim when the commit merge builds a new committed leaf. Identical in
policy to `ColumnSizing` as the bucket tree already uses it.

- **Pros:** one package, no contract change, no second representation, no persistence question — the
  leaf page format encodes the live run, never the array length. Costs a large tree nothing: a full
  leaf allocates exactly what it always did.
- **Cons:** decouples two numbers that were the same, so every existing read of an array length has to
  be re-pointed at whichever it actually meant. That is where the risk is, and it is real.

### Option B — lower `RangeIndex`'s value block size (declined)

Leave the tree alone and set the range index's block size to something near the observed occupancy.

- **Pros:** a one-constant change, no new field, no audit of length reads.
- **Cons:** the block size is structural, not a tuning knob. It sets the split fan-out and the persisted
  leaf page granularity (`RangeIndexLeafPagePart`), so lowering it changes page boundaries for every
  existing catalog and multiplies the page count for the indexes that legitimately hold many points.
- **Rejected because:** it trades a fixed cost on small indexes for a fixed cost on large ones and
  perturbs the on-disk paging, whereas content sizing is free for both. **Revisit if** a workload ever
  shows range indexes clustered at a size where a smaller *page* — not merely a smaller array — is the
  win.

### Option C — leave the sibling trees alone, per the earlier record's bullet (declined)

- **Rejected because:** measured. 4,219 range indexes at 0.78 % leaf occupancy are 24.5 MB on the demo
  dataset alone, and the price indexes another 25.0 MB. The "tens of kilobytes" estimate was about a
  different structure.

### Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Port it to `TransactionalObjectBPlusTree` (traffic recording index) | Same defect, but it backs one diagnostic structure rather than an index every catalog carries, and nothing has measured it | The traffic recorder ever shows up in a heap reading |
| Trim on the commit merge's `return this` fast path | Identical to the row in the earlier record: every commit would rebuild every untouched leaf and dirty its page. Trimming happens only in the branches that were already constructing a node | Never |
| Decompose the probes' figure into "useful" and "wasted" bytes | Needs a model of the leaf's internals, and a model is the thing three retracted numbers in this campaign came from. Run the probe on two commits and subtract instead | Never |
| Charge the shared empty key array in `getHeapSizeInBytes` | It is a JVM-wide instance the leaf points at and does not own; the `ValueColumn` family already excludes it and the JOL cross-checks subtract it | Never |

## Decision

**Chosen: Option A.** The fork was not really "which design" — it was whether the sibling tree was worth
touching at all, and that question was answered by measurement rather than by argument. The port itself
is the policy the bucket tree already proved, applied to a leaf that holds raw arrays instead of column
objects.

**The element tree was declined, then reopened, measured, and kept — and that sequence is the most
useful thing here.** The declination was right about the magnitude: the port is worth **1.7 MB (5.4 %)**,
against the long tree's 77 %. What settled it was not the per-index occupancy histogram the reopening
trigger asked for, but the cheaper move of *building the port and measuring the difference directly* —
the histogram would have cost about as much and still needed interpreting, whereas the port produces
the number itself and is revertible.

It was kept on a judgement, not on the number, and the judgement is recorded so it can be disagreed
with: 1.7 MB is real and free at runtime, all four trees now behave the same way so nobody has to ask
why one is different, and the saving comes from *sparse* leaves — the demo's ~101 records per index is
a mean, and a catalog carrying many price lists x currencies with few products each would be sparser.
**That last point is a hypothesis, not a measurement.** The counter-case is honest too: 5.4 % of a
structure whose real weight is the ~15-17 MB of `PriceRecord` bodies, not its spine.

## Key technical details

- **`TransactionalLongBPlusTree.BPlusLeafTreeNode`** gains `private final int capacity`. Everything
  asking "may one more key go in here" — `isFull()`, `isNearlyFull()`, the insert premise — reads it.
  Everything indexing an array reads that array's own length. The `isNearlyFull()` javadoc arguing the
  opposite is rewritten rather than left to contradict the code.
- **`ensurePhysicalLength` grows each array against its own length**, not against the other's. The two
  are equal in every state the class produces, but a guard reading only one would leave the other short
  if they ever diverged, and the write that follows indexes both. Call it on the object about to be
  written — the committed node outside a transaction, the layer inside one — and inside a transaction
  only after `decoupleTransactionalArrays()`.
- **The split's `end` for the right half must be the logical capacity.** It used to be the left half's
  array length, which was harmless only while every leaf array was exactly the block size. Once the
  halves are content-sized that expression collapses to `mid`, the right leaf copies the empty range
  `[mid, mid)`, and half the leaf disappears **with no exception and no failing assert**. The bucket
  tree's split already carries a comment about this exact trap; this is the same hazard in the sibling.
- **An empty leaf parks its keys on `ArrayUtils.EMPTY_LONG_ARRAY` and is not charged for it**, and no
  path clones a zero-length array into a private one — that would cost a header and break the identity
  every heap walk subtracts. The value array has no JVM-wide shared empty of an arbitrary component
  type, so it is privately owned at length zero and charged.
- **Trimming happens only where a node was already being built** (`trimmedCommittedCopy`), never on the
  merge's `return this` path.

## The reader bound content sizing made necessary

**Content sizing changed what a torn read costs.** While every leaf array was allocated at the full
block size and never replaced, an unsynchronized reader pairing a freshly-read `peek` with the array
that preceded a growth landed *inside* that array and returned a stale element — the documented,
accepted staleness. A content-sized array can be shorter than the peek, so the same read now runs off
the end. **This was missed at review and caught by an adversarial review after the long-tree change was
already committed.**

The premise it removed was recorded in `2026-09-03-content-sized-value-tree-columns`, which built the
`observableLiveRun` / `observableLeafPeek` guard family for the bucket tree. The long tree was never in
that sweep, correctly, because at fixed block size it did not need one. Nothing re-read that reasoning
when the fixed block size went away — which is the process lesson, and it is the same shape as the
sweep gap that record already describes.

Reachability is asserted by the code itself rather than inferred: `EntityCollection#describeIndex`
states that **no snapshot is taken** and hands a live index to `IndexDetailProjection`, which walks it
for its heap size from a request thread while a warm-up bulk load may be mutating it. For the element
tree the exposed reader is different and worse — a query thread resolving prices, since the
transactional layer is resolved through a `ThreadLocal` that a query thread does not share with a
warm-up writer.

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Publish `(array, peek)` together through one immutable volatile state holder | The correct fix in the abstract, and what a green-field design would do. It changes the layout and the write path of every leaf in three trees to close a hazard whose observable form is a *stale read* that these callers already accept; the clamp closes the crash with no write-path cost at all | The leaf ever needs a genuinely consistent multi-field read, rather than merely an in-bounds one |
| Make the fields `volatile` | Costs every writer a fence on the hottest path in the index to fix a reader that is documented to tolerate staleness, and still would not make the *pair* atomic | Never |
| Apply the clamp to the `peek >= values.length` corruption guard as well | It is not asking a capacity question. It is what turns a torn pair into a typed `BPlusTreeCorruptedException` naming the leaf and slot; clamping it would let a genuinely corrupt leaf through to a bare `ArrayIndexOutOfBoundsException`. `peek < values.length` also remains a true invariant, since `ensurePhysicalLength` grows before any path advances `peek`, so the check doubles as a self-test of the new invariant | Never |
| Guard `LeafPageHandleImpl` in the shared base class too | It has the identical two-read shape, but the path is thread-confined to the writer and a clamp there would imply a hazard that does not exist. See below | Page emission ever moves off the writing thread |

**`LeafPageHandleImpl` is the interesting non-fix.** It captures a leaf's array and occupancy as two
independent reads and `collectChangedPages` then walks `size()` indexing `valueAt(int)` — exactly the
crashing shape. It cannot tear, and the proof is an observable consequence rather than an assertion:
both reads resolve through a `ThreadLocal` transaction binding, so a flush on any other thread would
resolve every leaf to its committed instance, read `dirty == false` on each leaf that transaction
touched, and **emit no pages at all**. The paging tests passing is therefore itself the evidence that
emission runs on the writing transaction's thread. That also explains why the earlier sweep left the
identical code alone for the already content-sized bucket tree.

**The hazard worth naming is that this holds by consequence, not by an asserted invariant.** Moving
page emission to a background thread would not fail by crashing — it would emit zero pages long before
it could index out of bounds, so the first symptom would be silently lost writes. That reasoning now
sits in a comment at the site, which is where it is useful; anything moving that work off the writer's
thread must give the leaf a combined publish first.

## Verification

- **The reader bound is proven by counterfactual, one test per guarded reader.** Sharing a single test
  across readers would let the first one to throw stand in for the rest, so each has its own:
  `TransactionalLongBPlusTreeTest.TornLeafReaderBoundTest` (4) and
  `TransactionalElementBPlusTreeTest.TornLeafReaderBoundTest` (6). With the clamp removed **all ten
  fail** with `ArrayIndexOutOfBoundsException`; with it in place the long tree passes 1,030 tests and
  the element tree 2,044.
- **The element port is worth 1.7 MB**, same probe, same catalog copy, engine built from each commit's
  own source: price indexes **31.4 MB -> 29.7 MB** across 4,292 indexes holding 435,580 records, 75 B
  -> 71 B per record.
- **No measurable read-path cost from the guard.** `PriceRecordBackingBenchmark` on both builds: 17 of
  18 measurements inside their error bars, including every `array*` control — which is what says the
  box behaved rather than that the code did. The exception, `getByIdTree`, reported -31 % throughput
  **and** -17 % average time for the same operation; a 5-fork re-run resolved it as noise
  (0.0545 +/- 0.0067 -> 0.0491 +/- 0.0063 us/op, control +1.0 %). **The `@Fork(1)` error bars understate
  cross-JVM variance by an order of magnitude on a ~50 ns operation** — a single-fork JMH delta is not
  evidence, and the contradiction between two modes of the same benchmark is the tell.
- **Two write-path costs remain unmeasured, and no run here covers them.** `PriceRecordBackingBenchmark`
  mutates outside a transaction, so `trimmedCommittedCopy` on the commit-merge is never reached; and
  the ~4 reallocation-and-copy steps a leaf now performs while filling (4-8-16-32-64) land in JMH's
  `@Setup`, which is not timed. Both are judged unlikely to matter — the demo catalog builds its price
  indexes in about a second — but that is a judgement and is recorded as one.

- **Full functional gate: 22,668 tests, 0 failures.** The single error is `ExportS3ServiceTest`, "Could
  not find a valid Docker environment" — environmental. 4,538 tests under `io.evitadb.index.**`, which
  include the byte-exact JOL heap-accounting cross-checks, pass on their own as well.
- **Both hazards are covered by existing tests, proven by counterfactual** rather than by assumption:
  disabling `ensurePhysicalLength` raises **242 `ArrayIndexOutOfBoundsException`s**; binding the split's
  `end` back to an array length fails **23 tests across five suites**.
- **The heap-accounting error was caught by a test, not by review.**
  `TrigramIndexTest.shouldPriceAnEmptyIndexExactly` reported 304 bytes against a JOL-measured 288 — the
  16 bytes of the shared empty array.
- **Measured on the demo dataset**, same probe and catalog copy on both sides, engine built from each
  commit's own source:

  | | before | after |
  |---|---|---|
  | range indexes (4,219, holding 16,876 points) | 31.8 MB | **7.3 MB** |
  | price indexes (4,292, holding 435,580 records) | 56.4 MB | **31.4 MB** |
  | **total** | **88.2 MB** | **38.7 MB** |

  **≈49.5 MB, over two disjoint sets of objects.** Per range index, 7,906 B → 1,810 B: the 6,096 B saved
  is exactly `long[512]` + `Object[512]` less their four-slot replacements, so mechanism and measurement
  agree with no fitted constant between them.
- **The price half was invisible to the first probe and nearly went unreported.** Every
  `PriceListAndCurrencyPriceIndex` owns a validity `RangeIndex` that no filter index points at, so a
  probe walking filter indexes sees none of it. The first figure for this change was 24.5 MB — less than
  half of what it does.

## Consequences & open follow-ups

- **The earlier record's sibling-tree bullet is refined, not reversed.** Its decision — leaving the
  boxed bucket count in the four sibling families — stands, and for the reason it gives. What does not
  generalise from it is the per-tree cost: a bucket count is ~56 B per tree, a pair of full-block leaf
  arrays is ~6 KB. That record now points here.
- **`TransactionalObjectBPlusTree` remains unported**, with its trigger in the *Rejected outright*
  table. It backs traffic recording — one diagnostic structure rather than an index every catalog
  carries — and nothing has measured it. Dormant rather than open.
- **The element port's real weight is elsewhere, and this record should not be read as closing it.**
  1.7 MB came out of the price index *spine*. The bodies are ~15-17 MB: `PriceRecord` is a Java record
  of five `int`s (six with an inner record id), so ~32-40 B per object after header and alignment plus
  a 4 B reference, over 435,580 of them. **Field-level compression cannot touch that** — object layout
  aligns to 8 bytes, so packing two `int`s into an `int` and a `short` leaves a 32-byte object at 32
  bytes; and `priceWithTax` cannot be derived from `priceWithoutTax` because per-currency rounding is
  authoritative. A columnar layout — parallel `int[]` per field inside the leaf, as the `ValueColumn`
  family already does for the bucket tree — removes header, reference and padding at once, on the order
  of 8 MB. **Arithmetic from `VMLayout` constants and a field count, not a measurement.** Its cost is
  the same question `#1455` asks: `PriceRecordContract` is an interface handed to the query path, so a
  columnar store must materialize on read, and `PriceRecordBackingBenchmark` already exists to guard
  exactly that risk.
- **A concurrent sweep cannot prove the reader bound necessary on x86, and never will.** The bound is
  pinned by ten deterministic tests instead. This is the same calibration the bucket tree's
  `observableLeafPeek` javadoc carries, repeated here because the temptation to simplify the bound away
  is what both javadocs exist to resist.
- **The two probes are tracked evidence, deliberately.** `RangeIndexFootprintProbe` and
  `PriceIndexFootprintProbe` are force-added past the `spike/trigram/` ignore rule, on the carve-out that
  rule states for a spike that becomes evidence an issue depends on. An untracked earlier copy of the
  first one was lost with the worktree that held it, silently, because the ignore rule keeps such files
  out of `git status` entirely.
- **Run the functional gate at `-Dsurefire.maxHeapSize=24g` on this suite.** At the default 8 GB the
  fork dies of `OutOfMemoryError`, truncating the run and manufacturing derived failures — including a
  writer thread reported as "hung!" in a *leaf-page split* harness, which is the most misleading possible
  false positive for this change. At 32 GB the VM turns compressed oops off and every absolute-size
  assertion in the suite fails; `MemoryMeasuringConstantsTest.shouldRunUnderCompressedLayout` says so in
  its own failure message.
- **Issue #1455 remains the next item** and is unaffected by this work: it shrinks bucket record bitmaps
  inside the bucket tree's leaves, a different structure from either tree touched here.

## Related work

- `2026-09-03-content-sized-value-tree-columns` — the parent decision. This record applies its policy to
  the long-keyed sibling and refines its sibling-tree consequence.
- `2026-08-01-bplustree-cursor-free-insert-path` — the split and rebalance paths this change had to grow
  arrays inside are the ones that campaign made allocation-free; the growth calls sit on the rebalance
  side, not on the descent.
- `2026-09-04-millisecond-temporal-precision` — the sibling record from the same branch; its epoch-milli
  range keys are the payload the trees measured here carry.
- `2026-07-10-more-optimized-data-structures` — introduced the boundary-stable leaf paging that the
  "never trim on the `return this` path" rule protects.

## Timeline

- **2026-09-03** — content sizing shipped for the bucket-keyed tree; sibling trees explicitly left alone.
- **2026-09-04 15:10** — the array container is declined; the range tree's leaf occupancy is identified
  as the larger remaining prize.
- **2026-09-04 17:35** — implemented; 4,538 index tests pass, both hazards proven by counterfactual.
- **2026-09-04 18:05** — full functional gate green at 24 GB after two heap-related false alarms.
- **2026-09-04 18:20** — price index probe finds a further 25.0 MB and declines the element-tree port.
- **2026-09-04 18:58** — an adversarial review of the committed long-tree change finds the torn read
  that content sizing made fatal; the long tree's reader bound lands with four counterfactual tests.
- **2026-09-04 19:47** — the element port is built anyway to settle its own reopening trigger by
  measurement: 1.7 MB. Kept on a cost/benefit judgement, with its own reader bound and six tests.
- **2026-09-04 20:07** — `PriceRecordBackingBenchmark` on both builds shows no read-path cost; a
  single-fork -31 % outlier is resolved as noise by a 5-fork re-run.
