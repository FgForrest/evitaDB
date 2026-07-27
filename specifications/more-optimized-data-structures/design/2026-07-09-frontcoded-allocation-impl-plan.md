# FrontCodedStringColumn allocation — implementation plan (H1+H3, then JMH, then H2)

Companion to `2026-07-09-frontcoded-stringcolumn-allocation-attack.md` (the hypothesis doc).
Sequencing agreed with Johnny: **(1) H1+H3 together → (2) JMH microbenchmark for H2 → (3) H2 only if
the JMH shows it pays.**

**Status (2026-07-09): Phase 1 (H1+H3) IMPLEMENTED, tested, and measured — uncommitted.**
Measurement: `docs/reports/2026-07-09-frontcoded-phase1-h1h3-remeasure.md`. Evidence is tiered by
robustness there: `insertKeyAt`/`removeKeyAt` measured near-zero allocation (workload-independent,
strongest evidence); FrontCoded category −37.1% (33.63→21.14 GiB, cross-session but against a
deterministic workload); total allocation −19.2% (79.75→64.41 GiB) is directionally real but carries
an unexplained small systematic drift in untouched categories — read the report before quoting the
total figure as a clean isolated effect. Under-shot the plan's ~55 GiB Step-1.4 gate because
`copyRangeTo`'s "owned slice" compromise (§1.1.5) turned out to be ~58% of remaining FrontCoded
allocation under a churn-heavy workload — B+ tree split/merge/steal fires far more often than the
"cold and rare" assumption anticipated. Test verification: 4005 indexing tests + 1367 transaction
tests green (1 pre-existing, unrelated failure — `shouldRemoveOldDataFilesAndVerifyTimeTravel`, a
catalog-file-GC test with zero references to this code). Committed as `55ed58adb`.

**Follow-up (2026-07-09, uncommitted): `copyRangeTo` flat-buffer rewrite — DONE, verified by `advisor`
before and after.** Extended H1's flat-buffer technique to `copyRangeTo`'s slice snapshot and splice
assembly via two more thread-local buffer pairs (`decodeRangeToFlat` → `DecodeScratch.flat2`/
`offsets2`; assembly → `flat3`/`offsets3`, safe because `encode()` only ever reads them). Measurement:
`docs/reports/2026-07-09-frontcoded-copyrangeto-flatbuffer-remeasure.md` — FrontCoded 21.14→8.72 GiB
(−58.7%), total 64.41→51.53 GiB (−20.0%), landing almost exactly on advisor's predicted ~12 GiB. This
comparison is tighter/more trustworthy than the Phase 1 report's (same-session, exactly-scoped diff,
compaction count matches, other categories move both directions ≈noise — see report §2). Cumulative
vs the original pre-Phase-1 baseline: total −35.4%, FrontCoded −74.1%, with H2 still not attempted.
Added the multi-restart-block `copyRangeTo` test advisor required (closed a pre-existing coverage
gap — the whole suite ran at `BLOCK_SIZE=8`, below `RESTART_INTERVAL=16`, so no test had ever
exercised a range straddling a restart boundary). Remaining FrontCoded allocation is now dominated by
the search path (`decodeAt`, 69% of what's left) — exactly what H2 targets. Post-measurement code
review (Johnny) found and fixed two remaining duplications (`encode(byte[][],int)` now delegates to
`encode(byte[],int[],int)`; `decodeAllToFlat`/`decodeRangeToFlat` unified into a shared
`decodeRangeToFlatCore`, zero hot-path cost) plus a `@Nonnull` definite-assignment warning
(constructor reverted to inline field assignment) — see report §6, re-verified but not re-profiled
(behavior-preserving). **Committed as `202a335da`.**

**Phase 2 (JMH decision gate) — DONE 2026-07-09, gate PASSED.** New benchmark
`evita_test/evita_performance_tests/.../spike/FrontCodedFindKeyBenchmark.java` reproduces the encode/decode/
restart-walk algorithm directly (package-private production class), self-checked for decode fidelity and
string-vs-byte-compare agreement (incl. BMP-accented) before trusting any timing number, reviewed by
`advisor` before implementation. Full 4-shape × 3-size × 6-method matrix, `-prof gc`: byte-compare beats
string-compare 1.3–1.9× across every combination (largest win on the BMP-accented shape, H2's actual
target); the decode-only variant (walk, no compare, no `String`) allocates **exactly 0 B/op** in every row,
so 100% of string-compare's 192–1248 B/op is directly attributable to `new String(...)`, not the walk.
Report: `docs/reports/2026-07-09-frontcoded-h2-jmh-phase2.md`. **Proceeding to Phase 3.**

**Phase 3 (H2 itself) — IMPLEMENTED 2026-07-09 in worktree `760-frontcoded-h2-bmp-bytecompare`
(branched from `202a335da`), NOT committed to `dev`.** Advisor-reviewed design before implementation
(confirmed the "no byte ≥ 0xF0" BMP predicate is exact, flagged 3 fixes: thread real `from`/`to` and
return `InsertionPosition` — not the JMH spike's packed-int/0..n shortcut; build the disagreement test
first; verify the fast path actually reaches production before trusting the mechanism). Implementation:
`bmpSafe` (mutable, recomputed once per `encode()` call over the suffix bytes already being scanned —
zero extra pass) + `naturalOrderSafe` (final, captured once at construction from
`ValueColumnFactory.forKey`'s `isNaturalOrder(comparator)`, now package-visible for reuse) fields;
`decodeAt` split into `decodeAtBytes` (core, no `String`) + `decodeAtString` (wraps it) — mirrors this
session's `decodeRangeToFlatCore` dedup precedent; `findKeyPosition` is a single binary-search loop with
the compare strategy (byte vs `String`) resolved once before the loop, not duplicated. 5 new tests in
`FrontCodedStringColumnTest` (`BmpSafeByteCompareTest`), including advisor's mandated disagreement case
(U+E000 private-use vs U+10000 supplementary — String order and byte order disagree; column must return
String-order results) — 30/30 column tests green, 207/207 broader tree/unique-index tests green, 4046/4046
full `indexing & !slow` sweep green.

**Production re-measure — mechanism confirmed 3 independent ways, write-churn category number
misleading.** Full detail: `docs/reports/2026-07-09-frontcoded-h2-production-remeasure.md`.
- Write-churn ALIVE alloc profile (same methodology as before): FrontCoded only 8.72→7.89 GiB (−9.6%,
  far short of H1/copyRangeTo's 58-74% swings). **Root cause, found by tracing the caller chain**:
  `findKeyPosition` allocates **zero** `String` (only its own small probe-encode + `InsertionPosition`)
  — the fast path fires cleanly. The remaining 4.75 GiB (60% of FrontCoded) traces 100% through
  `keyAt()` ← `SingleLeafBucketCursor.value()`, a mutation-path cursor read H2 was never scoped to
  touch — not a flaw in H2, a mismatch between what this workload exercises and what H2 targets. The
  original ~6.5 GB `decodeAt` attribution (which the plan assumed was `findKeyPosition`'s cost) was
  misattributed by leaf-frame-only categorization.
- **Query-path (new `FrontCodedTreeQueryBenchmark`, real production `TransactionalBucketBPlusTree`
  shaped like `GlobalUniqueIndex`, 100k equality lookups, pre/post-H2 A/B verified via `javap`)**: real,
  clean win — hit/miss ns/op −11%, B/op −55%/−66%. This is where H2's benefit is directly visible:
  any `attributeEquals` lookup against a natural-order BMP-safe String tree.
  **Methodology gotcha caught mid-session**: `~/.m2`'s `evita_engine` jar still held H2's build from an
  earlier fix-up install, which would have silently made the "pre-H2" baseline also run H2 code — caught
  via `javap` before trusting the numbers, reinstalled the correct jar afterward.
- **Tuned-config re-measure** (Johnny's request: `fileSizeCompactionThresholdBytes=50GB`,
  `minimalActiveRecordShare≈0`, `syncWrites=false`, `flushFrequencyInMillis=10s`, temp-edited into the
  worktree's `EvitaWarmUpInsertionTest`, not committed): ALIVE duration 4m04s→2m24s (−41%), 0
  compactions. FrontCoded allocation essentially unchanged (−0.5%) — confirms its cost is orthogonal to
  compaction, ruling out "compaction was masking the win" as an alternative explanation. `OffsetIndex`
  dropped −31.1% — that's where compaction's cost actually lives.

**Not yet committed.** Awaiting Johnny's decision on whether to ship (real, verified, but narrower
value than originally projected — a query-path win, not a write-churn category win) — see the
production remeasure report §4 for the full net assessment.

All work is confined to one file unless noted:
`evita_engine/src/main/java/io/evitadb/index/bPlusTree/FrontCodedStringColumn.java` (586 lines).
Target reduction: **~24.8 GB (H1+H3) now, +~6.5 GB (H2) if justified**, of 79.75 GB ALIVE heap churn.

---

## Phase 1 — H1 (flat decode buffer) + H3 (share on `duplicate()`), one PR

**Why together:** both touch only `FrontCodedStringColumn`; both are MVCC-safe *because* every mutator
still ends in `this.data = Arrays.copyOf(buf, len)` (whole-reference replacement). H3's safety proof
*depends* on that invariant, and pure-H1 preserves it exactly. Ship as two commits in one PR so they are
measured together but can be reverted independently.

### Step 1.1 — H1: flat decode buffer (commit `perf: flat-buffer decode for FrontCodedStringColumn slot mutations`)

Replace the per-mutation `byte[][]` of `size` fresh arrays with **one reused thread-local flat `byte[]`
(keys concatenated) + one reused `int[]` offset table** (`offsets[i]..offsets[i+1]` delimits key `i`).

1. **`DecodeScratch` (line 138)** — add two grow-on-demand fields:
   - `byte[] flat = EMPTY_BYTES;` — concatenated live keys.
   - `int[] offsets = EMPTY_INT_ARRAY;` — `size+1` boundaries.
   (Optionally a second `flat2/offsets2` pair for the cross-leaf `copyRangeTo`; see 1.1.5 for the cheaper
   owned-slice alternative that avoids it.)

2. **New private helper `decodeAllToFlat()`** — mirror `decodeAllBytes` (line 408) but write each decoded
   key contiguously into `scratch.flat` (grow via `ensureCapacity`) and record `scratch.offsets[i]`; return
   the live count. **No per-entry `Arrays.copyOf` (kills 20.28 GB), no outer `byte[][]` (kills 1.82 GB).**

3. **New `encode(byte[] flat, int[] offsets, int n)` overload** — same restart/varint logic as the current
   `encode` (line 476) but reads key `i` as `flat[offsets[i]..offsets[i+1]]` instead of `keys[i]`. Keep the
   final `this.data = Arrays.copyOf(buf, len)` **unchanged** (the load-bearing MVCC write; residual 2.02 GB
   stays — reclaimed only by the deferred in-place follow-up, NOT here).

4. **Rewrite the slot mutators** (kills the 1.65 GB grown `byte[][]`):
   - `insertKeyAt(i, v)` (line 234): `decodeAllToFlat()` → `System.arraycopy` the flat tail right by
     `newKey.length`, shift `offsets` right by one and rebase, splice `((String)v).getBytes(UTF_8)` (the one
     unavoidable per-insert alloc, unchanged) into the gap → `encode(flat, offsets, size+1)`.
   - `removeKeyAt(i)` (line 245): `decodeAllToFlat()` → memmove flat tail left, shift/rebase `offsets` →
     `encode(flat, offsets, size-1)`.
   - `clearAt` (line 254) / `fillEmpty` (line 287): these only ever *truncate* the live tail — keep the
     current "re-encode first-n" semantics but source from `decodeAllToFlat()` (or, simplest, leave them
     re-encoding via the existing byte-path since they are cold and rare — decide during impl, either is
     correct).
   - `copyRangeTo` (line 262): **the one fiddly method — see 1.1.5.**

5. **`copyRangeTo` (both overlap cases must preserve `System.arraycopy` semantics):**
   - `dst == this` (overlapping right-shift, `stealFromLeft` — `TransactionalBucketBPlusTree` :3309/:3329/
     :3412/:3427): snapshot the moved slice (`length` entries, ≤ block size = 64) into a **small owned
     temp** first (as the current code does at line 265), then `decodeAllToFlat()` on `this`, splice, encode.
   - `dst != this` (cross-leaf split/merge/steal — :3314/:3365/:3417/:3454): decode only the src slice into
     the small owned temp, then `dst.decodeAllToFlat()` into the shared scratch, splice, `dst.encode(...)`.
     **Rationale for the owned slice over a second scratch buffer:** `this` and `dst` share the same
     thread-local scratch, so decoding both into `flat` would clobber; the slice is small and this path is
     split/merge (far colder than per-record insert/remove), so a tiny temp is acceptable and avoids adding
     a second scratch pair. (Reduces but does not zero the 0.666 GB `copyRangeTo` line — acceptable.)

6. **Untouched:** `findKeyPosition`/`keyAt`/`appendKey`/`asBoxedArray`/`decodeAt`/`decodeAll`/`duplicate`/
   `allocate`/`capacity` — read path and blob-level ops. No format change, no serializer, no BWC bump.

### Step 1.2 — H3: share the blob on `duplicate()` (commit `perf: structural-share FrontCodedStringColumn blob on duplicate`)

- `duplicate()` (line 217): replace
  `new FrontCodedStringColumn<>(capacity, size, dataLength, Arrays.copyOf(data, dataLength), restartOffsets.clone())`
  with `new FrontCodedStringColumn<>(capacity, size, dataLength, this.data, this.restartOffsets)`
  (share both references). **Kills ~1.04 GB.**
- Add a **load-bearing class comment**: the share is safe *only* while every mutator replaces `data`/
  `restartOffsets` by whole reference via `encode()` (never edits bytes in place). If the deferred in-place
  follow-up ever lands, this must become a `shared`-flag copy-on-first-write.
- **Soften `ValueColumn.duplicate()` JavaDoc** (`ValueColumn.java:83-90`): "deep copy with new backing
  array(s)" → "an independent, non-aliasing copy; implementations may structurally share immutable backing
  state as long as every mutation reallocates rather than editing in place." Do **not** change the sibling
  columns (`BoxedObjectColumn`/`Long`/`Int`/`Instant`/`RecordColumn` genuinely mutate in place and keep
  their `clone()`).

### Step 1.3 — Tests (`FrontCodedStringColumnTest`, functional module, tags `@Tag(INDEXING) @Tag(ATTRIBUTE)`)

- Flat-buffer slot correctness: insert/remove at head/mid/tail across a leaf that spans ≥2 restart blocks
  (size > 16) so restart re-encoding is exercised.
- `copyRangeTo` overlap: `dst==this` right-shift (mimic `stealFromLeft`) and `dst!=this` cross-leaf move;
  assert decoded keys match a boxed-array oracle.
- H3 isolation: `duplicate()` then mutate one instance, assert the other is byte-identical to its pre-mutation
  snapshot (proves the reallocation-not-in-place invariant).
- Run the mirrored transactional B+ tree correctness suites (the `TransactionalObjectBPlusTree` /
  bucket-tree suites) — the split/merge/steal paths drive `copyRangeTo` end to end.

### Step 1.4 — Verify + measure (needs the quiet box)

- `mvn -pl evita_test/evita_functional_tests test -Dgroups="indexing & !slow"` + the bucket-tree suites green.
- Re-run `EvitaWarmUpInsertionTest#shouldGenerateLoadOfDataInWarmUpPhase` under async-profiler `-e alloc`
  (same real config as `docs/reports/2026-07-09-invertedindex-bucket-flyweight-remeasure.md`). **Gate:**
  `decodeAllBytes` byte[] (20.28) + byte[][] (1.82) + insert/remove/copyRange byte[][] (1.65) + `duplicate`
  byte[] (0.96) all collapse; expect total ALIVE churn ~79.75 → ~55 GB.

---

## Phase 2 — JMH microbenchmark for H2 (measure need + cost BEFORE implementing)

**Goal:** answer two questions the profile can't — (a) how much of `findKeyPosition`'s time+alloc is the
`new String(...)` per hop vs the restart-chain decode walk itself (if the walk dominates, removing the
String barely helps); (b) does raw-byte compare actually beat `String.compareTo` on realistic keys.

**Where:** `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/FrontCodedFindKeyBenchmark.java`.
`FrontCodedStringColumn` is package-private in `evita_engine`, so — exactly as
`SortIndexResolvePositionsBenchmark` did for a private method — the benchmark **reproduces a minimal
front-coded column** (decode + restart index) in the spike package, with two `findKeyPosition` variants:
- `findKey_stringCompare` — decode candidate to `String`, `String.compareTo` (today's path).
- `findKey_byteCompare` — decode candidate to scratch bytes, unsigned byte-lexicographic compare vs the
  probe's pre-encoded UTF-8 bytes (the H2 prototype), **no `String`**.

**Fixtures** (`@Param`): realistic key shapes — product codes (`"AB-12345"`), EAN-13 (`"8590000123456"`),
URLs (`"/category/sub/product-slug-1234"`), and one accented-Latin set (BMP, natural order) to exercise the
BMP predicate. Vary leaf fill (size 16 / 48 / 64) and probe hit vs miss.

**Harness:** mirror `SortIndexResolvePositionsBenchmark` — `@BenchmarkMode(AverageTime)`,
`@OutputTimeUnit(NANOSECONDS)`, `@Fork(1)`, `@Warmup(3)`, `@Measurement(5)`, `Blackhole` the result.
**Run with `-prof gc`** to capture normalized B/op alongside ns/op.

**Decision gate (end of Phase 2):**
- **Proceed to H2** if `byte_compare` shows a meaningful ns/op win *and* the B/op drop confirms the ~6.5 GB
  the alloc profile attributes to `decodeAt` (i.e. the `new String` really is the cost, not the walk).
- **Stop / rethink** if the restart-walk decode dominates and String removal barely moves either metric —
  in that case the real read-path lever is caching decoded keys, not byte-compare, and H2 is not worth its
  correctness surface.

---

## Phase 3 — H2 (BMP/ASCII byte-compare on the search path), conditional on Phase 2

Only if Phase 2 clears the gate. Commit `perf: byte-compare search path for BMP-safe FrontCodedStringColumn`.

### 3.1 — The predicate (refinement over the hypothesis doc: prefer **BMP-safe**, not just ASCII)
Raw-byte compare == `String.compareTo` order **iff both operands are BMP-only** (no supplementary/surrogate
char). In UTF-8 a supplementary char is exactly a 4-byte sequence whose lead byte is ≥ 0xF0 (continuation
bytes are ≤ 0xBF, so **"no byte ≥ 0xF0"** detects BMP-only at the same single-threshold cost as the ASCII
`< 0x80` check). **Recommend the BMP predicate** — it also covers accented Latin (é, ü) in natural order at
zero extra cost; ASCII (`< 0x80`) is the trivially-correct conservative fallback if we want to be maximally
safe in v1. (Localized attributes use a collation comparator and are excluded regardless — see gate below.)

### 3.2 — Where the flags live
- **`boolean bmpSafe`** (per column) — recomputed in `encode(...)` while it already iterates every suffix
  byte (each distinct byte is a suffix byte of exactly one entry, so scanning suffixes covers the whole
  corpus once): `bmpSafe &= (b < 0xF0)` for each suffix byte. No threading through mutators or split/merge —
  it self-heals on every re-encode. Empty column: `true`.
- **`boolean naturalOrderSafe`** (per column, construction-time) — thread from the factory. In
  `ValueColumnFactory.forKey` (line 87) the String branch currently returns `FrontCodedStringColumn::new`;
  change to `capacity -> new FrontCodedStringColumn(capacity, isNaturalOrder(comparator))`. **This is the
  critical gate the read-path agent found:** the hot trees pass `Comparator.naturalOrder()` (a singleton),
  never `null`, so a `comparator == null` check would be dead. Using the construction-time flag (rather than
  an identity check per query) also survives a future caller that wraps naturalOrder.
- Both flags are copied by the adopt-state constructor (line 195) so **`duplicate()`/`allocate()` carry
  them**. `allocate` (empty) sets `bmpSafe = true`, inherits `naturalOrderSafe`.
- **No persistence:** both are derived state; trees rebuild by re-insertion on load → `encode` recomputes
  `bmpSafe`, factory recomputes `naturalOrderSafe`. No wire change, no serialVersionUID concern.

### 3.3 — The fast path in `findKeyPosition` (line 297)
- At method entry, when `naturalOrderSafe && bmpSafe && comparator is natural`: encode the probe to UTF-8
  once and check it is BMP-safe (`no byte ≥ 0xF0`, one linear scan). If the probe fails, fall through to the
  String path (the probe must satisfy the predicate too).
- In the binary-search loop, add `decodeAtBytes(mid)` (returns the scratch `cur[0..curLen]` — the bytes
  `decodeAt` already produces at line 393 *before* `new String`) and compare against the probe bytes with an
  unsigned byte-lexicographic comparator. **No `String` allocated.** The restart-walk, varint decode, scratch
  reuse, and corrupt-blob check (line 379) are unchanged.
- Everything else (`keyAt`/`appendKey`/`asBoxedArray`) keeps decoding to `String` — they fire on splits/cold
  paths, not the hot descent.

### 3.4 — Tests (`FrontCodedStringColumnTest`, `@Tag(INDEXING) @Tag(ATTRIBUTE) @Tag(COMPARATOR)`)
- Mixed corpus with a supplementary char (emoji, 4-byte) → `bmpSafe == false` → slow path; assert ordering
  matches `String.compareTo` exactly (this is the correctness-critical case).
- Accented-Latin BMP corpus, natural order → fast path fires, ordering correct.
- Localized comparator (collation) → fast path never fires; ordering follows the collator.
- Probe contains a supplementary char against a BMP-safe column → falls through to String path, correct.
- Fuzz: random BMP + occasional supplementary keys, assert `findKeyPosition` agrees with a `String.compareTo`
  oracle across both paths.

### 3.5 — Measure
- Re-run the warmup alloc profile: confirm `decodeAt` byte[] (4.38) + String (2.15) ≈ 6.5 GB drop.
- Re-run the Phase-2 JMH to confirm the predicted ns/op + B/op win in situ.
- **Query-side (speculative):** the attributeFiltering −60 % regression was root-caused to `RoaringArray`
  cold-walk in `SortedRecordsSupplier`, *not* FrontCoded decode — so only claim a query win if a *read-path*
  alloc profile (or the attributeFiltering JMH) actually shows `decodeAt` on the hot path. Do not assume it.

---

## Deferred (explicitly NOT in this plan)
- **H1 in-place-`data` follow-up (+2.02 GB):** reclaims the `encode` trim copy by editing the retained blob
  in place with geometric slack. Gated on auditing that the steal/merge/`copyRangeTo` rebalance paths
  (`TransactionalBucketBPlusTree` :3309–:3465) all `decoupleTransactionalArrays()` before mutating.
  **Mutually exclusive with plain H3** — if it lands, H3's outright share must upgrade to a `shared`-flag
  COW. Revisit only if the residual 2.02 GB proves worth it after Phase 1 is measured.

## Commit / PR shape (per `.claude/rules/git-workflow.md`)
- Phase 1: one PR, two commits (H1, then H3), target `dev`, `Ref: #760`.
- Phase 2: one commit (`test: JMH microbenchmark ...`) — spike module, no production change.
- Phase 3: one PR (conditional), commits for the byte-compare path + tests, `Ref: #760`.
