# Advisory verdict: warm-up page-baseline defect — corruption + leaks

*Response to `BRIEF-warmup-page-baseline-for-advisor.md`. Repo `evitaDB`, branch `dev` @ `2fac0b066` + working
tree, 2026-07-17. Every claim below was verified against the live source by a 4-track code audit (flush-pipeline
ordering, OffsetIndex removal semantics, transactional failure windows, baseline-capture re-entry); citations are
Read-confirmed file:line. Analysis only — no code was changed.*

---

## 0. Executive verdict

1. **Keep seam A.** No alternative in §7 dominates it. Its durability argument is **half-right**: the
   *sequentiality* leg is airtight (verified, §1.1), but the *"a flush failure is fatal"* leg is **false on both
   paths** (§1.2, §1.3) — and on the transactional path that falsity means **the pilot introduces a narrow NEW
   corruption window that did not exist before it** (§2). A is currently *better*, not *right*.
2. **Make "flush failure is fatal" an enforced invariant instead of a comment** — that single hardening makes A
   *right* and makes E unnecessary. Concretely: (i) **poison** a warm-up collection's persistence on any flush
   failure (independently mandated by a pre-existing data-loss defect the audit found, §1.2); (ii) **fatalize a
   trunk-incorporation flush/merge failure for the catalog** (kill + reload from disk), replacing today's
   unbounded in-place retry — which the audit shows is *either* a livelock (deterministic failure) *or* a
   corruption vector (transient failure); it has no good case (§2.3).
   *(SUPERSEDED on the (ii) mechanism by the owner decision in `DECISION-failure-boundary.md`: SUSPEND the
   catalog's transaction processing instead of fatalizing — same guarantee ("no next flush after a failed
   flush"), readers keep serving; signed off by the advisory with the gate placed at the WAL-drain entry.)*
3. **Close L2 with option B** (collapse-reclaim from `pendingLivePageSequences()`): verified correct, one accessor
   per index, composes with the guards. **L3 then dissolves** — and the audit independently verified that even
   today's allocator reset is content-safe and time-travel-safe (§3, Q5).
4. **Adopt G in its strong form, once, in the shared skeleton**: compute `pageListChanged` by directly comparing
   the ordered page list against the last-published list inside `PageStreamRegistry.collectChangedPages`,
   demoting the two proxies (`anyFreshLeaf`, `freed≠∅`) to a cross-check assertion. Lands in one place, benefits
   all 7 indexes, makes the root-skip unable to be silently wrong under any future baseline bug.
5. **D's rejection stands, but for corrected reasons; C dominated; E not worth it; F unnecessary** (§4).
6. **Q6: fail-fast stays; build a repair tool** for catalogs already corrupted pre-fix (they will keep failing
   boot *after* the fix ships — the fix prevents, it does not heal). A sound salvage rule exists (§3, Q6).

---

## 1. What the audit verified / falsified

### 1.1 Sequentiality: VERIFIED, and stronger than the brief assumed

The write is **asynchronous** (collect runs synchronously inside `Catalog.flush()`; the byte-write runs on the
transaction executor — `EntityCollection.java:1656-1663` inside `CompletableFuture.runAsync`,
`ProgressingFuture.java:408-418`, executed at `EvitaSession.java:1876`) — yet two flushes of one collection can
**never** be in flight concurrently, structurally:

- The closing session is removed from the registry **only after the flush future completes**:
  `flushFuture.whenComplete` (`EvitaSession.java:1851`) → `commitProgress.complete*` → termination sequence
  (`CommitProgressRecord:501/255`) → `executeTerminationSteps` → `sessionRegistry.removeSession`
  (`Evita.java:1770-1774`, `SessionRegistry.java:333`).
- Warm-up allows a single active session: `SessionRegistry.addSession` throws `ConcurrentInitializationException`
  while `activeSessions` is non-empty (`SessionRegistry.java:296-302`). A fire-and-forget close therefore cannot
  be followed by a second session (and its flush) until the previous write is durable.
- Lifecycle flushes join synchronously (`Catalog.java:2237-2238`, `:2287-2288`); the goLive flush completes before
  `goLive()` runs (`MakeCatalogAliveMutationOperator:105-107`, composite-future semantics
  `ProgressingFuture.java:254-268`), and session creation is rejected during `GOING_ALIVE`
  (`Evita.java:1766-1768`).

So *"by the time flush N collects, flush N−1's bytes are on disk"* holds — **when flush N−1 succeeded**.
(Minor orthogonal note: `addSession`'s empty-check/put is not under one lock — a theoretical TOCTOU for two
threads racing to create the *first* warm-up session; no flush is pending at that point, so it does not affect
this analysis, but it is worth a one-line hardening someday.)

### 1.2 "A flush failure is fatal": FALSE in warm-up — and the failure is worse than the registry

No poison exists: `OffsetIndex.operative` flips only in `close()` (`OffsetIndex.java:252`, `:1090`);
`WarmUpFlushFailureCloseTest` pins survive-and-serve, and the same collection can flush again
(`Catalog.java:2236-2288` lifecycle paths).

Worse — and this is a **pre-existing data-loss defect independent of the page baseline**:
`popTrappedUpdates` is destructive (`DataStoreChanges.java:209-214`) and advances every index baseline
(`notifyFlushed`, `:221`) *before* the write. A failed write therefore **permanently loses the popped storage
parts**: leaf dirty flags are cleared, trapped changes drained — the next flush re-emits none of it. After a
failed warm-up flush the disk is silently missing data *regardless* of what the registry believes.

**Consequence:** the warm-up poison flag is not merely a support for A's argument — it is required to fix a
standing silent-data-loss bug. Any flush failure must render the collection's persistence unusable (subsequent
flush/pop throws deterministically), matching the project's fail-fast doctrine.

### 1.3 "A flush failure is fatal": FALSE transactionally — the abort-after-stage path is REAL

Trunk incorporation orders: collect+stage (`TransactionTrunkFinalizer.java:108` → `Catalog.flush` →
`popTrappedUpdates` → `collectChangedPages`/`stage`) → durable write (`flushTrappedUpdates` + `storeHeader`,
`Catalog.java:1868/1873`) → merge/publish (`TransactionTrunkFinalizer.java:114` → per-producer
`createCopyWithMergedTransactionalMemory` → `publishStaged`, `InvertedIndex.java:940`).

A throw anywhere between stage and publish is caught at `TransactionManager.java:1302-1309`
(`forgetVolatileData` + `forgetMutationsAfter` + rethrow — comment: *"we will have to re-try the transaction"*),
routed to `retryTransactionProcessing` (`:542-546`) which merely reschedules the WAL drain. **The same live index
instances — and the same owner-resident registry, staged set intact (`forgetVolatileData` touches only OffsetIndex
volatile values) — are reused on every retry, forever, with no attempt cap.** This is precisely the window
`discardStaged()` documents, existing unwired in production.

### 1.4 The baseline-capture pass re-enters `collectChangedPages` — A's *effective* timing is publish-at-collect

`captureOriginalsFromComponents` (`EntityIndex.java:981-994`) re-walks all components into a discarded sink right
after the real pass (`DataStoreChanges.java:216-222`). The gate it meets — `FilterIndex.appendStorageParts`'s
`isDirty()` (`FilterIndex.java:1126`) → `InvertedIndex.dirty` — is **still true** in the second pass: the
index-level flag resets only at the tx merge (`InvertedIndex.java:917/927`), never inside `popTrappedUpdates`
(only per-leaf flags are cleared, `PageStreamRegistry.java:349`). So the capture pass re-executes
`collectChangedPages`, whose new first line **publishes the set the same flush staged moments earlier — before
the write**, then re-stages it. The second emission is provably empty (`freed=∅`, `pageListChanged=false`,
`changedPages=∅`) and nothing consumes it.

Two consequences:

- The pilot javadoc's *"still staged when the next flush begins ⇒ on disk"* story is not what actually runs:
  in every `EntityIndex`-hosted flush (warm-up **and** transactional), the live baseline advances to the current
  flush's set **pre-durability**. Harmless when flushes cannot fail-and-continue; load-bearing when they can (§2).
- The brief's D-rejection reason (ii) — *"A holds no durability disadvantage that D has"* — inverts: **A holds no
  durability advantage over D.** Both are publish-at-collect in practice. A still wins, but on self-containment
  grounds only (§4).

### 1.5 OffsetIndex semantics: the "harmless?" sub-questions resolve favorably

- **Removal of a non-existent record is a silent no-op** (`OffsetIndex.doRemove:1652-1659` returns false; no
  tombstone). Created-then-removed is defended at promotion (`:1525-1536`). So a phantom-baseline's *spurious*
  removals can never crash; only *missed* removals (a bounded leak) matter. (The historical "removal marker
  crashed OffsetIndex" incident was an unregistered marker type routed through `put` — fixed by the
  `DeferredRemovalStoragePart` drain; different mechanism.)
- **Supersede-at-same-key is real and time-travel-safe**: leaf-page part PK = `pack(streamId, pageSequence)`
  (`AbstractLeafPagePart:83`); OffsetIndex keeps per-catalog-version roots (`Roots.floorRoot`), so retained
  versions resolve old bytes and compaction copies only live entries. Caveat: this holds while `streamId` (a
  KeyCompressor id) is stable across forget/regrow — it is (the dictionary never un-registers), but the
  separately-open KeyCompressor-desync incident is a reminder not to lean on it silently.

---

## 2. The centerpiece: a NEW transactional corruption window opened by the pilot (Q1 + Q7)

### 2.1 The scenario

Preconditions: a **transient** flush failure (disk-full, I/O hiccup) during trunk incorporation of a transaction
whose only *structural* page change is a **leaf merge** (no split — common in re-publish-heavy workloads), and a
retry that then succeeds.

1. Attempt 1 collects: freed diff vs the correct published set emits the donor-page removal, stages
   `S = C − {X}`; the capture re-run **publishes S** (live ← S) and re-stages it. The write then fails before
   `storeHeader`; `forgetVolatileData` reverts the OffsetIndex bytes; **the registry keeps live = staged = S**
   (phantom — disk still holds set `C`, old root listing donor `X`).
2. The WAL drain retries. A fresh transactional layer replays the same mutations; the merge is redone in-layer;
   the survivor is dirty again (correct).
3. Retry collect: `publishPreviousFlush()` publishes the identical phantom `S` (no-op change);
   `freed = live(S) − nextLive(S) = ∅` — **the donor removal is never re-emitted**; `anyFreshLeaf = false`
   (merge allocates nothing) ⇒ `pageListChanged = false` ⇒ **the root is skipped**. The survivor page *is*
   re-written (dirty), `storeHeader` lands.
4. Disk end-state: **the pre-transaction root still lists donor `X`; the survivor's bytes now hold the absorbed
   keys covering `X`'s range** — the exact overlap corruption, minted on the *transactional* path.

**Pre-pilot the same retry self-heals**: live stayed at `C` (nothing ever published it early), so the retry
recomputes `freed = {X}` and re-emits removal + root. The brief's calibration claim — *"today's behaviour is
guaranteed corruption on a merge, so A is strictly better regardless"* — is therefore **falsified in the strict
sense**: A trades a broad, near-guaranteed warm-up corruption for a narrow transactional one. An excellent trade,
but not a free one — and the residue is cheap to eliminate.

If the transaction also contains a split, `anyFreshLeaf` forces the root re-emit and the failure degrades to a
permanent donor-page **leak** (missed removal; spurious removals no-op per §1.5). The collapse branch has its own
variant: a failed collapse flush has already `forgetPageStream()`-ed, so the retry finds an empty registry and
leaks **every** prior leaf page.

One honest caveat the audit could not fully discharge from reading alone: the precise survival of
staged/published registry state across each concrete failure point. Test T3 (§5) is designed as the executable
proof either way — it must be observed RED before any guard ships.

### 2.2 Why `discardStaged()` in the catch is NOT sufficient

Because of §1.4, by the time the write fails the phantom set is already **published**, not merely staged.
`discardStaged()` drops the staged copy and leaves live = S. Closing the window via the discard route requires
**two** changes: (a) make the capture pass side-effect-free on page streams (it violates its own "discardable"
contract today — `EntityIndex.java:983-984`), **and** (b) wire `discardStaged()` into the
`TransactionManager.java:1302` catch. Either alone fails.

### 2.3 The recommended single guard: fatalize trunk flush/merge failure
*(SUPERSEDED by `DECISION-failure-boundary.md`: suspend, do not fatalize — the "no good case for the retry"
finding below stands and drives the decision; only the recovery mechanism changed.)*

Observe what the in-place retry loop actually offers for flush-side failures:

- **Deterministic failure** (e.g. `DataStructureCorruptedException` from the dirty-scope validators): the retry
  re-fails forever — an unbounded reschedule livelock (no attempt counter; cf. the prior trunk-incorporation-spin
  history).
- **Transient failure**: the retry succeeds against corrupted in-memory baselines — §2.1, plus the popped-state
  problems shared with §1.2.

There is **no good case**. Making a flush/merge failure inside `commitCatalogChanges` fatal for the catalog
(mark corrupted, reload from last durable version, WAL-replay forward) restores every baseline — registry
live+staged, dirty flags, forgotten streams — from disk truth via the exact crash-restart path the pilot's own
javadoc already trusts (`fromPersistedPages`), and which the production WAL replay has validated end-to-end.
It makes *"a flush failure is fatal"* true by construction on both paths (with the warm-up poison covering bulk
mode), it converts the deterministic-failure livelock into a loud bounded failure, and it needs **one change at
one boundary** — no per-index work, so the in-flight seam-A rollout is untouched.

Fallback if catalog-fatality is judged too aggressive: the two-change discard route of §2.2. It keeps the retry
loop for transient failures but must also solve §1.2's popped-state loss for the warm-up side — which is most of
the work fatality gives for free.

---

## 3. The seven open questions, adjudicated

**Q1 — Is A's durability argument airtight?** No — exactly where the brief suspected. Sequentiality: verified
airtight (§1.1). Fatality: a comment, false on both paths (§1.2, §1.3), and on the tx path the gap is not
"harmless or a leak" but a genuine (narrow) corruption window that A itself opens (§2.1). **A is *better* today
and becomes *right* once fatality is enforced.** E is then unnecessary: a post-durable hook would add real
plumbing and a new forgettable seam to deliver a guarantee the failure-boundary hardening provides wholesale.
The pilot javadoc must be amended either way: it currently asserts an invariant the runtime does not enforce, and
its "still staged ⇒ on disk" narrative is already falsified by the capture re-entry (§1.4).

**Q2 — Is B correct?** Yes. At a warm-up collapse, `staged` holds exactly the last PAGED flush's set (published
or not, `pendingLivePageSequences()` returns it — `PageStreamRegistry.java:196-211`); on the tx path `staged` is
null after the merge so it falls back to `live`, correct there. It rests on the *same* invariant as A ("what the
last flush staged is what disk holds"), so it inherits the same dependency on the §2.3 guard — after which
B and the `AttributeIndex` empty-drop precedent are **both right**. Optional refinement (not load-bearing once
fatality lands): replace `forgetPageStream()` in the collapse branch with reclaim-from-pending + `stage(∅)`,
preserving the advance-only allocator and keeping the stream restorable; with fatality adopted, plain B matches
the in-flight rollout shape and suffices.

**Q3 — Does the double pass diverge?** Not in content — the second emission is provably empty and discarded
(§1.4). It diverges in **timing**: it makes A publish-at-collect on every flush, which is precisely what arms
§2.1. With the fatality guard the timing is benign and the double pass can stay; if the discard route is chosen
instead, the capture pass must be gated out of page emission (it contributes nothing to the manifest — the page
walk is pure waste there). Either way, pin the chosen behavior with a test (T5).

**Q4 — `discardStaged()`: wire or delete?** Its existence was prescient, not vestigial — the audit found the
anticipated abort-after-stage path live in production (§1.3). The resolution follows the §2.3 decision:
**fatality ⇒ delete it** (the handshake becomes stage → publish, with abort = death; a documented-but-unwired
abort half is a standing trap, as this episode proves); **discard route ⇒ wire it** in the
`TransactionManager.java:1302` catch alongside its twin `forgetVolatileData` — together with the capture gating
per §2.2.

**Q5 — Allocator reset / L3.** Verified safe content-wise: supersede-at-same-key + per-version roots + no-op
missing-removals (§1.5) mean a re-issued page id can never resurrect stale bytes for any reader, retained
versions included. L3 is purely a consequence of L2's missed removals; **close L2 and L3 dissolves**. The
advance-only javadoc violation by `forget()` is real but consequence-free today; the Q2 `stage(∅)` refinement
removes it if desired. Keep the streamId-stability caveat (§1.5) in view.

**Q6 — Load-side guard?** Fail-fast is right; do not silently self-heal (an overlap has other conceivable causes,
and silent repair would have hidden this bug for another year). But note the operational gap: **catalogs already
corrupted pre-fix will still fail boot after the fix ships.** Build an explicit, opt-in repair path (offline tool
or boot flag) on the containment rule: live leaves partition the key space, so a listed page whose key range is
**fully contained** in another listed page's range is provably the stale donor (both merge directions produce
exactly this signature) — drop it from the list, emit its removal, rewrite the root, log loudly. The same tool
can sweep L1/L2 orphans (leaf-page records referenced by no root list), which are otherwise permanent cruft
copied forward by every compaction.

**Q7 — Missed transactional exposure?** Yes — §2.1 *is* transactional exposure, hiding exactly where the brief
predicted a blind spot ("that same reasoning previously led us to exonerate the write path"). The 273-tx replay
was clean because it contained no failure-and-retry. Second-order finding: a mid-merge-walk failure leaves a
**partial publish** (producers merged before the throw published; later ones still staged) — benign for the
durable-flush case and mooted entirely by fatality, but further evidence that the current catch-and-retry
boundary mixes inconsistent in-memory states.

---

## 4. Options A–G, final scoring

| Option | Verdict | Reason |
|---|---|---|
| **A** publish at top of `collectChangedPages` | **Keep; roll out to all 7** | Self-contained (impossible to forget), per-index, closes corruption + L1. Sound *given* the §2.3 guard; amend its javadoc to the enforced invariant. |
| **B** pending-aware collapse reclaim | **Adopt** | Closes L2 (⇒ L3) with one accessor per index; same invariant as A; `stage(∅)` refinement optional. |
| **C** publish in each owner's flush method | Reject | Per-owner, forgettable (InvertedIndex alone has two owners); dominated by A+B. |
| **D** `notifyFlushed` cascade | Reject — corrected rationale | *Not* for durability (A has no timing advantage over D — §1.4) and the per-index-rollout objection is overstated (an SPI default no-op would allow incremental implementation). The real reason: it moves publish responsibility outside the index into a hook whose current implementation *already* demonstrates the hazard of side-effectful re-runs, and it would need the same failure guards anyway. |
| **E** post-durable hook | Not worth it | Real plumbing + a new forgettable seam to deliver what §2.3 provides wholesale at one boundary. Revisit only if flush failures are ever made survivable-by-design. |
| **F** last-persisted-list on the index | Unnecessary | The strong form of G inside the registry achieves the safety property with less machinery. |
| **G** direct page-list comparison | **Adopt, strong form, once** | Compute `pageListChanged` by comparing the ordered list to the last-published list inside `PageStreamRegistry.collectChangedPages`; keep the proxies as a cross-check assertion (throw on mismatch, per project doctrine). Would have converted this entire bug into a loud flush-time failure. Do *not* take the "drop the root-skip" variant — the O(1) steady state is worth keeping once the skip cannot be wrong. |

Rollout shape: three independent tracks, each per-index or one-shot, none blocking another —
(1) the in-flight seam-A rollout (6 indexes, unchanged); (2) B per collapse branch (7 sites, same PR-shape as A);
(3) boundary hardening (warm-up poison + tx fatality + registry-level G) — one-time changes outside the 7 indexes.

---

## 5. Test coverage (TDD: each observed RED before its fix)

| # | Test | Proves | Expected today |
|---|---|---|---|
| **T1** | Per-index merge repro (existing pattern, merge-forcing arithmetic from the brief §3) | Corruption + L1 fix per index | RED per unrolled index |
| **T2** | Warm-up PAGED→SINGLE collapse: after the collapse flush, assert every prior `*LeafPagePart` is absent from storage at the latest version; regrow + reload clean | L2 (and L3 by regrowth) | **RED** (records linger) → GREEN with B |
| **T3** | **The window test**: merge-only tx; inject a transient failure into `flushTrappedUpdates` on attempt 1; let the WAL retry succeed; cold reload | §2.1 exists; the §2.3 guard closes it | **RED with the pilot** (overlap error on reload; pre-pilot it passes — pinning that A introduced it) → GREEN with fatality/discard-route |
| **T4** | Warm-up poison: inject failure into the collection-created flush (`Catalog.java:2236`); attempt a further flush/session | §1.2 — deterministic refusal instead of silent data loss | **RED** (silently proceeds; can also assert the popped parts are gone to pin the data-loss defect) |
| **T5** | Capture-pass pin: after `popTrappedUpdates`, assert the chosen contract (live==staged==nextLive if re-entry stays; exactly one collect per flush if gated) | §1.4 stays a decision, not an accident | new |
| **T6** | Registry G: force a baseline desync via test hook; assert the root is re-emitted anyway and the proxy cross-check throws | G's safety property | new |
| **T7** | goLive boundary: last warm-up flush stages; first transactional commit publishes it and diffs freed correctly across the hand-off | the verified §1.1/Q4-agent path stays pinned | should pass; pins by-reference registry carry |
| **T8** | Repair tool: fixture root with a nested-range stale page → repair → validator clean; orphan sweep removes unreferenced page records | Q6 tooling | new (RED = tool absent) |

T3 doubles as the executable discharge of the one caveat the reading audit could not fully close (§2.1, registry
state survival across the exact failure point) — if T3 cannot be made to go red with the pilot in place, that is
itself decisive evidence and the §2.3 guard can be descoped to the warm-up poison alone.

---

## 6. Corrections to the brief (for the record)

- §2 "publishStaged is called from exactly ONE place" — true per index as written, but the *effective* publish
  count is two once the pilot lands anywhere `EntityIndex` hosts it: the capture re-run publishes at collect time
  (§1.4). The 6-index rollout will make this uniform; today only `InvertedIndex` has the at-collect publish.
- §6 durability argument — sequentiality leg confirmed; fatality leg falsified; the argument's conclusion
  survives only after the §2.3 hardening.
- §8 Q1 calibration ("A is strictly better regardless") — falsified in the strict sense by §2.1; net still
  strongly favorable.
- §5 "Already-safe by comparison" (`AttributeIndex` snapshots) — confirmed, and its refresh runs in *both*
  passes of the double collect, which is why it is idempotent by construction; a useful template for T5.
