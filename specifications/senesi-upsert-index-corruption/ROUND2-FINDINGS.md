# Senesi fuzzing — Round 2 findings (2026-07-16)

## ✅✅ ROOT CAUSE CONFIRMED (JDWP) — `splitInternalNode` uses `valueBlockSize` not `internalNodeBlockSize`

All four B+ tree `splitInternalNode` methods compute `mid = (valueBlockSize+1)/2` (=32) but internal nodes have
capacity `internalNodeBlockSize` (=31). Splitting a full internal node overflows: int-keyed trees crash
(`arraycopy length -1`), the Bucket tree mis-partitions → **stale leaf-page twin**. Sites:
`AbstractIntKeyedBPlusTree:371`, `TransactionalLongBPlusTree:1316`, `TransactionalObjectBPlusTree:990`,
`TransactionalBucketBPlusTree:2329`. Only triggers on trees big enough to split an internal node (~2000+ keys)
→ tests miss it, production senesi hits it. Full evidence + proposed fix (`mid = (internalNodeBlockSize+1)/2`):
**`HYPOTHESIS-leaf-twin-for-fable5.md` → "ROOT CAUSE CONFIRMED"**. Reproduced via WAL replay: T0(v60)+T+X wal_0
crashes deterministically at **v67** in the price super-index Element tree (JDWP-captured).

## 🎯 PRIMARY TARGET — persisted stale leaf-page twin (OffsetDateTime inverted index)

Johnny: restarting the production evitaDB on the T+X snapshot **refuses to load** senesi:
```
Corrupted persisted inverted index for type java.time.OffsetDateTime:
  leaf-page sequence 9 overlaps its successor leaf-page sequence 10 —
  last key (2026-07-16T07:31:30Z) does not sort before first key (2026-07-14T21:59:13Z)
  at TransactionalBucketBPlusTree.assertCrossLeafBoundaries(:1542)
  ... InvertedIndex.fromPersistedPages(:532) ← AttributeIndexLoader.loadInvertedIndex ← readEntityIndex
```

**REPRODUCED LOCALLY** — booting the T+X extract (`data_repro/TX_extract`, via `evita_server/run-server-repro.sh`,
build `2026.2.RC1-SNAPSHOT`) throws the identical error for the **Product** collection's `OffsetDateTime`
inverted index (boot log `scratchpad/repro-boot.log`). ⇒ prod is **format-compatible** with the local build;
deterministic on-disk repro in hand. T+X catalog version = **364**; T0 (= `data/senesi`, pristine baseline).

**This is the round-1 stale-leaf-page-twin class, still being produced by CURRENT code** in the T0→T+X live
window (T0 was freshly reindexed by the fixed engine). Two distinct corruptions coexist in this incident:
- **THIS (primary):** persisted twin, boot-blocker, baked into T+X on disk.
- **KeyCompressor `hreflang`=6509 (secondary):** runtime / self-healing, T+X entity data is clean — see the
  offline-scan section below.

**Why the round-1 validators didn't stop it (the gap).** PR #1284 built a full IN-MEMORY validation net that
all validate the **tree**: Tier A (op-time boundary asserts on every insert/upsert), Tier B (pre-WAL
`validatePreCommitDirtyLeafScopes`, kill-switch `evita.bPlusTree.preCommitValidation` default on), Tier C
(post-trunk-merge). Item **1.4 (validate cross-leaf order at FLUSH, before pages reach disk) was deferred**
on an induction: "a tree that only mutated through asserted ops cannot emit an overlapping page list at flush."
But this twin was caught **only at load** (Phase 1 assembler check) — it bypassed A/B/C. ⇒ it forms in the
**paging/flush layer** (`InvertedIndex.collectChangedPages` / `PageStreamRegistry` pageSequence→nodeId change
detection + the root's `orderedPageSequences` list rebuilt by `fromPersistedPages`), emitting a corrupt page
list from a SOUND in-memory tree. The 1.4 induction assumed the paging faithfully mirrors the tree; a paging
bug (stale leaf page persisted / wrong page-sequence ordering) violates that. **Precedent:** the ChainIndex
`forgetPageStream` flush-layer bug (PAGED→SINGLE collapse persisted stale layers).

**Geometry:** seq 9's last key = `2026-07-16T07:31:30Z` (recent), seq 10's first key = `2026-07-14T21:59:13Z`
(2 days older). seq 9 (position i in ordered list) holds a NEWER key than seq 10 (position i+1) — a stale/
mis-sequenced page pair.

**→ Self-contained hypothesis for Fable 5: `HYPOTHESIS-leaf-twin-for-fable5.md` (this dir).** Ranked
flush-layer candidates + a falsification test (replay with tiers ON) + candidate 6 (non-transactional
producer). T0-clean VERIFIED (Johnny, test system).

**Plan:** (1) WAL-replay T0→T+X to pin the producing flush/transaction (Johnny's WAL-replay idea; oracle =
reload-and-assert or a flush-time check); (2) implement the deferred **1.4 flush-time `assertCrossLeafOrder`**
as diagnostic (fires at the producing flush) → likely part of the fix; (3) root-cause + fix the underlying
`InvertedIndex` paged-persistence bug. Repro assets: `data_repro/TX_extract` (bootable, throws),
`data_repro/tx_wal_probe/senesi/` (T+X wal_0+wal_1), `data_repro/T0_boot`, `spike/CompressorDesyncScanner`.

---

## ✅ PIVOTAL RESULT — offline scan of T+X prod backup (2026-07-16 PM)

Johnny provided prod backups (read-only, in `~/Downloads`): **T0** =
`backup_senesi_actual_2026-07-16T09-37-51.zip` (== current `data/senesi`, which he restored to this T0),
**T+X** = `backup_senesi_actual_2026-07-16T10-54-54.zip` (~77 min later, WITH the WAL files spanning the
window). T+X catalog **version=364**. Between T0→T+X: `product-6_0.collection` grew **+8.27 MB** (a real
product flush happened in-window); `wal_0` grew 4.5→15.97 MB + new `wal_1`; `parameterValue` *shrank* 91 KB
(a compaction rewrote it).

**Offline compressor-desync scan** — `evita_test/evita_performance_tests/.../spike/CompressorDesyncScanner.java`
(read-only OffsetIndex scan of T+X `product-6_0.collection`, **no boot / no WAL replay** so nothing masks a
persisted mismatch; extract at `data_repro/TX_extract`; run log
`scratchpad/desync-scan-TX.log`):
- Persisted product read compressor **peakId=6509, keyCount=6509**. Boundary keys:
  6506=`feed-facebookSk`, 6507=`feed-heurekaSk`, 6508=`feed-googleSk`, **6509=`hreflang`**, 6510+=null.
- Compressor-encoded entity parts read **100% CLEAN**: EntityBodyStoragePart 153785/0,
  AttributesStoragePart 461355/0, AssociatedDataStoragePart 893008/0, PricesStoragePart 153711/0.
- ReferencesStoragePart shows 153785/153785 "failures" — a **scan-harness artifact**
  (`Entity schema was not initialized in EntitySchemaContext!`; references need a schema ThreadLocal the
  offline harness doesn't set, and `ReferencesStoragePartSerializer` takes NO keyCompressor so it CANNOT hit
  the desync). Ignore it. The "8/8 prod PK overlap" is likewise meaningless (100% of products fail identically).

**Conclusion: the desync is NOT baked into T+X on disk.** The prod error `no key for id 6509 … size=6508`
was the LIVE read compressor momentarily frozen one key behind while a record referenced **id 6509 =
`hreflang`** (a newly-minted key, alongside the `feed-*` keys 6506–6508). By the T+X flush the persisted
compressor had caught up to 6509 → self-consistent on disk.

**Reclassification:** runtime / live-commit-path race around **minting a NEW compressor key**, NOT an
on-disk/reindex-baked corruption. ⇒ sequential boot-replay may come up clean; a faithful repro must exercise
key-minting through the live flush path. Anchor = boundary key `hreflang` (id 6509) — next: find the WAL
transaction that first introduces it (version correlation vs T0), then JDWP the post-flush read-compressor
rebuild vs the write-compressor mint.

---

## ⏸ RESUME HERE (paused for compaction 2026-07-16 ~12:55) — SUPERSEDED by the pivotal result above

**Current live state**
- Local diag server RUNNING: pid 3117877, `localhost:5555`, JDWP `:8005`, launched via
  `evita_server/run-server-diag.sh` (DEBUG `io.evitadb.core.transaction`/`Catalog` via `logback-diag.xml`
  + `transaction.waitForTransactionAcceptanceInMillis=120000`). Catalog at **v65** (fuzzed twice with
  seed=2 batch=2000). Pristine clean copy at `data_snapshot_pristine`. `data/` is NOT pristine.
- Fuzzer `SenesiUpsertFuzzer` patched with stop-on-first-corruption (compiled). `VersionProbe` added.

**Two real problems found this round**
1. Commit-watchdog false-negative (my fuzzer, seed=2 batch=2000) — documented below. SEPARATE from prod.
2. **THE production incident** (Johnny's Loki pointer): KeyCompressor `post-flush` desync — "There is no
   key for id 6509! Compressor size=6508 … source=post-flush". 27k+ server-side + 13k client errors on
   `senesi-ks01-htz`. See the "PRODUCTION PIVOT" section. This is the target.

**Immediate next step (cheapest discriminator, advisor-endorsed) — NOT yet done**
- Read the exact prod-failing PKs on the local server: `1270015 500717 474693 471043 426291 356020
  153611 152508` via `SanityCheckRepro localhost 5555 senesi <pk> dump`.
  - GOTCHA: the client JVM must set **`-Xmx1g`** (or similar) — default ~23 GB heap collides with the 25 GB
    server → "Could not create the Java Virtual Machine". The failed attempt was a heap issue, NOT a data
    result.
  - Fail locally ⇒ on-disk/reindex-baked desync ⇒ deterministic local repro, no backup needed, fix is
    write/flush-side. Read clean ⇒ desync came from live republish/concurrency ⇒ backup+WAL essential, and
    single-threaded WAL replay may come up clean (have a fallback).

**Backup path (Johnny is providing backup + WALs)**
- Frame the first load explicitly as the on-disk-vs-runtime A/B test above. Restore into a SEPARATE dir via
  the restore API (never manual fs surgery; never touch `data/`).
- Free questions for Johnny: (a) does restarting the `evita` pod clear the errors? (survives = on-disk;
  clears = runtime-stale read compressor). (b) confirm prod build == local `2026.2.RC1-SNAPSHOT`.

**Root-cause hypotheses (keep BOTH live until JDWP discriminates)**
- (a) Flush header/record atomicity: the collection header's `compressedKeys` (from
  `OffsetIndexDescriptor.compressedKeys()`, the LIVE write map) is captured out of sync with the
  entity-body writes that minted id 6509.
- (b) Transactional/MVCC seed: id 6509 minted in a committing tx, written into shared records, but not
  durably merged into the trunk read compressor at the version transition (`ReadWriteKeyCompressor`'s
  `getAtomicSnapshot`/trunk-seed "must start strictly above every id in seed" is the delicate spot).
- "Always exactly 6509 / size 6508" across thousands of errors ⇒ a **single boundary event**, read side
  frozen exactly one key behind (NOT a per-flush random race). The invariant a fix must assert:
  **header seed ⊇ every compressor id referenced by records written in the same commit.**
- JDWP targets on replay: `ReadWriteKeyCompressor.getId` (6509 mint); `EntityCollectionHeader`/descriptor
  `compressedKeys` capture at flush; the `OffsetIndexDescriptor` "post-flush" `ReadOnlyKeyCompressor` rebuild.

**Fuzzer improvement (Johnny asked; task #8)**
- Must introduce NEW compressor keys (new locales / attribute names / associated-data / reference targets /
  price lists) — value-only fuzzing can't hit this class. Add a **post-commit read-back verification** pass
  (re-read every upserted entity full-content) — would have caught "no key for id N" directly. Vary tx
  sizes; optional multi-session concurrency for the transactional-merge seed path.

---


Second fuzzing round against the **freshly-reindexed** senesi dataset (reindexed 2026-07-16 09:37,
current code = `2026.2.RC1-SNAPSHOT`, after PR #1284 shipped the stale-twin fail-fast + WAL-race +
session-guard fixes). Goal: does the reindexed dataset + fixed engine still corrupt under fuzzed
bulk upserts within larger transactions?

## TL;DR

- **No index corruption reproduced.** The round-1 corruption families (sort-tree "Key is already
  present", filter "Sanity check - record not found") did **not** recur.
- **One real problem found** (deterministic, seed=2, batch=2000): a **false-negative commit** — a
  large transaction that takes ≈60 s of server-side processing races the 60 s dangling-record
  watchdog; the watchdog fails the client's `CommitProgressRecord` **even though the transaction
  actually commits and goes live**.

## Method

- Server: `evita_server/run-server.sh` (all APIs on `localhost:5555`, JDWP `:8005`, catalog loaded in
  56 s, no migration). Pristine copy of the fresh dataset saved to `data_snapshot_pristine`.
- Fuzzer: `SenesiUpsertFuzzer` (seeded, op-logged, per-entity `(seed,pk)` determinism + `onlyPk`
  isolation). Patched with **stop-on-first-corruption**: benign per-entity business-validation errors
  (the deliberate `INVALID_dropMandatory` → `MandatoryAttributesNotProvidedException`) continue;
  a genuine corruption (`GenericEvitaInternalError`/`DataStructureCorruptedException` family or a
  known structural-invariant guard message) or a non-transport TX-level failure halts the run.

## Phase 1 — regression census (seed=1, batch=500, unmodified, from pristine)

Exact round-1 config that previously produced bug-02 3–7×. Result: **CLEAN.**
`batch 0: ok=467 perEntityFail=33`. The only two signatures are the intended `INVALID_dropMandatory`
validation errors (`Entity Product reference … requires these attributes to be provided`, 32× on
`categories`, 1× on `bundles`). 33 per-entity savepoint rollbacks did **not** poison the 500-op
transaction → atomic-rollback path healthy; round-1 corruption gone on this config.

## Phase 2 — larger transactions + fixed-seed sweep (batch=2000)

Seed sweep 2,3,4. Halted on **seed=2, batch 0** with a TX-level failure:

```
StatusRuntimeException | INTERNAL: …:84: Commit progress for catalog version 64 has been pending for
more than 60000ms. The transaction pipeline dropped this record; failing it to unblock waiters.
```

Client stack: `EvitaClient.updateCatalog` → `EvitaClientSession.closeWhen` → `join()` (session close =
commit). Per-entity census before the halt: only the benign `INVALID_dropMandatory` signatures.

## Root cause — 60 s dangling-record watchdog races a slow large-transaction commit

Evidence (server side):

```
12:16:25.770 WARN  PendingCommitProgressRegistry - Sweeping dangling CommitProgressRecord for catalog
version 64 — the transaction pipeline did not complete it within 60000ms of the commit start time.
This indicates a missed completion path in the pipeline and should be investigated.
```

- **Version 64 actually committed and is LIVE** — `VersionProbe` on the still-running server returns
  `LIVE catalogVersion=64`. On-disk `product-6_0.collection` / `senesi_0.catalog` / `senesi.boot`
  were all written at **12:16:23**, i.e. the trunk incorporation *finished* — 2 s before the sweep at
  12:16:25. So this is **not** a lost transaction and **not** the old bug-03 infinite spin.
- **Pipeline is idle** at capture time — every `Evita-service-*` thread parked "waiting on condition",
  0 % CPU, no thread in `waitUntilLiveVersionReaches`. Nothing is stuck; the incorporation completed.
- The completion path is **not missing**: `TrunkIncorporationTransactionStage.handleNext` calls
  `commitProgress.complete(WAIT_FOR_CHANGES_VISIBLE, …)` in all three branches
  (already-processed :89; result-present via `propagateCatalogToSharedView` :164; empty :132). Because
  v64 propagated, `complete()` at :164 *was* reached — but only **after** the sweep had already
  `completeExceptionally`'d the record, so it was a no-op (double-completion guard). The record was
  reported failed to the waiting client; the transaction succeeded anyway.

Watchdog mechanics:

- `PendingCommitProgressRegistry.sweepRecordsOlderThan(maxAge)` (`:179`) fails any record whose
  `CommitProgressRecord.getCommitStartTime()` is older than `maxAge` and not yet done.
- `maxAge = TransactionManager.safetyDeadlineMs()` = `max(60_000, transactionAcceptanceTimeout × 5)`
  (`:1448`) → effective **60 s** floor here.
- `commitStartTime = OffsetDateTime.now()` at server-side record construction (`CommitProgressRecord:119`),
  i.e. at version assignment — it measures pure server-side commit-processing latency.

The watchdog's documented premise is "pending longer than the worst-case pipeline latency ⇒ almost
certainly dangling." **That premise is violated:** a 2000-entity senesi commit's *legitimate*
server-side latency (~60 s) meets/exceeds the assumed 60 s worst-case. The watchdog therefore fires on
a healthy, still-progressing commit and fails it to the client — a **false-negative commit**.

### Why the commit is ~60 s (timing breakdown, seed=2 batch=2000)

- commit start (version assigned) ≈ 12:15:25 (client applied 2000 in-memory upserts in ~2–3 s).
- WAL fully written 12:16:05 → **~40 s in the conflict-resolution / WAL-append phase** (abnormally long
  for a sequential append of 2000 entities).
- catalog/collection/boot flushed 12:16:23 → +~18 s trunk incorporation (WAL read + transactional-memory
  merge + flush).
- sweep 12:16:25 → completion lost the race by ≈2 s.

The ~40 s WAL-append phase is the prime suspect for inflation. `run-server.sh` enables **diagnostic**
traffic recording: `server.trafficRecording.enabled=true`,
`server.trafficRecording.trafficFlushIntervalInMilliseconds=0` (flush every record),
`server.trafficRecording.sourceQueryTracking=true`; the server log shows repeated
`OffHeapTrafficRecorder - Failed to record traffic data … BufferOverflowException`. Open question
(isolation experiment): re-run with traffic recording off / a sane flush interval → does the 2000-commit
finish well under 60 s (⇒ watchdog would not misfire in a production config)?

## Severity / impact

- **Not corruption.** The reindex + PR #1284 fixes hold for these fuzz patterns; even the failing case
  committed cleanly (v64 live, indexes intact).
- **False-negative commit**: a client submitting a transaction large/slow enough to take ≥60 s
  server-side is told it FAILED (INTERNAL) while the data actually persisted. A client that retries on
  failure would **re-apply** (double mutation); a client that reports failure upstream misreports a
  success. This mirrors the shape of the original production incident (FG `EvitaIncrementalIndexJob`
  bulk re-publish seeing commit errors) along a *different* axis than the round-1 corruption.

## Candidate remediations (for discussion — not yet applied)

1. **Liveness-aware sweep** (most correct): only fail records that are genuinely abandoned, not
   slow-but-progressing. E.g. reset the age clock on pipeline progress, or check that no pipeline stage
   is actively processing the record's version before sweeping. Preserves the anti-hang safety net
   (bug-03) without failing healthy slow commits.
2. **Raise / rescale the deadline**: the 60 s floor is below the real worst-case for large transactions
   on large datasets. Make it configurable and/or scale with atomic-mutation count.
3. **Attack the ~60 s incorporation latency** (separate perf track): the ~40 s WAL-append phase under
   the diagnostic traffic-recording config; verify production config is far faster.

## PRODUCTION PIVOT (Johnny, 2026-07-16) — the real incident is a KeyCompressor post-flush desync

Loki (`{app="evita",cluster="senesi-ks01-htz",level="error"}`) on the live senesi test cluster shows a
**different, dominant** corruption — NOT my watchdog finding:

```
GenericEvitaInternalError: INTERNAL: There is no key for id 6509! Compressor size=6508,
id range=[1,6508], source=post-flush
```

- Volume: **27,300** on `evita` (server) + **13,627** on `edee-admin` (client `EvitaIncrementalIndexJob.getEntity`)
  in 12h. Ongoing. My watchdog signature (`"pending for more than"`) → **zero** on this cluster ⇒ the two are
  unrelated; the production incident is purely the KeyCompressor desync.
- **Always the same id/size** (6509 vs 6508): one key id 6509 was assigned by the write compressor and
  referenced by many persisted PRODUCT records, but the persisted/read `ReadOnlyKeyCompressor` (source
  `post-flush`, rebuilt in `OffsetIndexDescriptor` ctor `:154-196`) tops out at 6508. Every `getEntity`
  touching a record that references key 6509 fails ⇒ thousands of unreadable entities.
- Mechanism: a record's compressor key id **outran** the persisted read compressor — a write/flush
  atomicity/ordering desync. The existing asserts (`OffsetIndexDescriptor:176-185`, "seed loss / regression
  during post-flush rebuild") guard only the *rebuild* step, not "a record was written with an id the
  rebuilt read compressor never received." Keys are monotonic + append-only (`ReadWriteKeyCompressor.getId`),
  so id 6509 was definitely assigned — it just never reached the persisted header the readers use.
- My round-2 fuzzer did NOT reproduce it: it mutates existing attribute/reference *values* (reusing existing
  compressor keys), so it never stresses the new-key-registration-during-flush path. Reproducing by guessing
  the interleaving is low-probability ⇒ Johnny is providing a **backup + WALs** containing the problematic
  transaction sequence for a deterministic replay.

### Additional evidence narrowing the mechanism (server-side `evita` logs)

- Failing site: `EntityBodyStoragePartSerializer.read` → `ReadOnlyKeyCompressor.getKeyForId(6509)` during a
  query prefetch (`getEntityById` → `readEntity` → `toEntity` → OffsetIndex.get → deserialize
  `EntityBodyStoragePart`). So key 6509 is referenced by a **Product entity body** (an attribute /
  associated-data / reference-name / locale key persisted in `EntityBodyStoragePart`), and the read uses the
  `post-flush` `ReadOnlyKeyCompressor` seeded from the persisted **Product-collection header** `compressedKeys`.
- **The seed-regression / seed-loss guards did NOT fire** (`OffsetIndexDescriptor:176-185`,
  `CatalogOffsetIndexStoragePartPersistenceService:165-170`) anywhere in 48h. So at every *rebuild* checkpoint
  the read compressor matched the write compressor — yet a persisted record still references id 6509 while the
  live read compressor tops at 6508. ⇒ the desync is a **header/record atomicity or missing-rebuild gap**, not
  a caught rebuild regression: the entity-body record persisted with id 6509 while the collection header that
  seeds readers persisted with `compressedKeys` at 6508.
- Onset appears **recent** (oldest server-side match ~11:58 CEST 2026-07-16), consistent with today's reindex +
  incremental-index republish introducing a new Product-collection key.
- Suspect capture point: `OffsetIndexDescriptor.compressedKeys()` returns the **live** write-compressor map;
  the collection header is persisted from it. If the header's `compressedKeys` is captured at a flush point
  out of sync with the entity-body writes that registered id 6509 (or the read compressor is not rebuilt after
  that write), readers seeded from the header never learn id 6509. JDWP targets for the replay: `getId` when
  6509 is minted; the `EntityCollectionHeader`/descriptor `compressedKeys` capture at flush; the post-flush
  `ReadOnlyKeyCompressor` rebuild.

## Reproduction

- `data_snapshot_pristine` holds the clean fresh dataset. Reset: stop server, `rm -rf data && cp -a
  data_snapshot_pristine data`, reboot.
- Deterministic: `SenesiUpsertFuzzer localhost 5555 senesi 2 2000 2 <oplog>` (patched build) halts on
  batch 0 with the watchdog failure. Op-log: `scratchpad/oplog-seed2-b2000.txt`.
- Live-version probe: `VersionProbe localhost 5555 senesi`.
