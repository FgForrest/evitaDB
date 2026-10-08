---
title: Restore the content of a trapped storage part on rollback from a pre-image journalled when the part is read inside a savepoint
date: 2026-10-08
updated: 2026-10-08 06:40
status: accepted
kind: fix
issues: [1678]
prs: []
areas: [evita_engine/core/buffer, evita_engine/core/collection, evita_engine/index/mutation/storagePart, evita_engine/spi/store/catalog/persistence/storageParts/entity]
supersedes: []
superseded-by: []
relates: [2026-08-26-warm-up-per-entity-mutation-atomicity]
---

# A trapped storage part is rolled back by content, not by slot

A failed entity mutation could leave its writes behind in a storage part that the data store buffer
held "trapped" (kept in memory until the next flush). The buffer hands such a part out **by reference**,
the storage executor mutates it **in place**, and the savepoint rollback restored only *which instance*
the slot held — the same, already-mutated one. The fix journals a content copy of a trapped part the
first time a savepoint reads it (`EntityStoragePart#createPreImage()`), and the rollback puts that copy
back into the slot. It applies to both savepoint kinds: the WARM_UP savepoint and the per-entity
savepoint inside an ALIVE transaction.

## Why

The `2026-08-26-warm-up-per-entity-mutation-atomicity` record chose each structure's rollback
granularity by the cost of its pre-image, and filed `DataStoreChanges` under "first-touch memento where
the pre-image is an `O(1)` reference grab". For the dirty-index bookkeeping that is true. For the trapped
storage-part cache it is not: the slot journal restores a reference, and a reference to an object its
holder mutates in place is no pre-image at all.

The reported case (a product upsert whose reflected reference into category 7 failed consistency) turned
out to be the narrowest of the shapes the reproduction suite confirmed on dev `096dda1094`:

- **Nested mutations.** The rolled-back reflected reference stayed in category 7's references part. In
  WARM_UP the next flush persisted it, so the stored body and the index disagreed after a reopen.
- **Root mutations.** A failed root upsert of an entity whose parts an earlier nested mutation had
  trapped leaked the same way. This could persist a body that violates its own schema: an
  `EXACTLY_ONE` reference gone, plus a locale the index never saw.
- **Every entity part type**, not only references. The body's locales, version, attribute locales and
  associated-data keys are mutated in place too.
- **ALIVE** committed correctly in every shape, because the trunk replays root mutations from the WAL.
  But every read inside the transaction after the failed mutation saw the rolled-back content.

In bulk loading this is the common case, not a corner case. A hot category's references part stays
trapped from its first reflected insert until the flush, so every failed product upsert that reflects
into an already-touched category leaked.

One correction to the issue text: in the reported scenario the undo journal held **nothing** for
category 7. The failure happens in `verifyConsistency`, before `commit()` re-traps the part, so the
in-place write during `applyMutation` is the whole defect. "The journal restores the same object"
describes a different path: a nested executor that already committed, followed by a failure in a later
executor. The fix covers both.

### Previous state

`DataStoreChanges#getStoragePart` returned the trapped instance itself, and
`ContainerizedLocalMutationExecutor` cached and mutated it. The undo journal had two halves:

- `journalTrappedChange` restored the map slot;
- `journalPersistedChange` (from the 2026-08-26 work) restored a record written through to the
  persistence service.

Neither restored an object's content. The persisted side was never affected: `OffsetIndex#get`
deserializes, so each read from storage hands out a private instance. Only the trapped map shares
instances.

## Options considered

### Option A — content pre-image on the first trapped read inside a savepoint (chosen)

On the first hit of a trapped slot inside a savepoint, journal an inverse that puts a content copy of
the part back into the slot. Executors keep sharing the live instance, so sibling executors of one root
mutation still see each other's writes.

- **Pros:** confined to the buffer and one copy primitive per part type. It restores the content
  regardless of whether a later executor re-trapped the slot. It costs nothing outside a savepoint, so
  WAL replay is not affected.
- **Cons:** one `O(part size)` copy per trapped part per savepoint that reads it — read-only reads
  included. Each of the five part types must copy every field, which is an obligation a new field can
  silently break (hence the completeness tests below).

### Option B — copy-on-read: hand out a private copy, never the trapped instance

- **Pros:** the existing slot journal would suffice, since a trapped instance would never be mutated.
- **Cons:** a copy on every trapped read, and a "savepoint open" gate would be needed to spare WAL
  replay.
- **Rejected because:** two executors of one root mutation that touch the same entity would each mutate
  their own copy, and the last `trapPutStoragePart` would silently drop the other's write — a lost
  update. It would also flip the deliberate aliasing assertion in #1615's in-flight registry seam
  (`InFlightEntityAccessorLookup`, unmerged at the time). Revisit only if executors stop sharing parts
  within one root mutation.

### Option C — part-level copy-on-first-write inside each part's mutators

The `TransactionalBitmap` pattern: each mutator of the five entity part types journals its own
pre-image before its first write.

- **Pros:** the copy is paid only on a real write, never on a read.
- **Cons:** it couples every mutator of five `spi.store` data carriers to the engine's savepoint
  machinery, where option A adds one self-contained copy method per type.
- **Rejected because:** `WarmUpSavepoint` is thread-local and WARM_UP-only, so the mutators would need
  a second hook for the ALIVE savepoint, which has no equivalent the parts can reach. Revisit if the
  read-side copy ever shows on a profile; the measurement below says it does not today.

## Decision

**Chosen: Option A.** It is the only option that keeps executor sharing intact (B breaks it) without
making the SPI data carriers savepoint-aware (C). Its cost — a copy on read rather than on write — was
the open question, and it measured below the noise on a corpus built to stress it (see Verification).
C would win only if that copy became measurable; B would win only if executors no longer needed to see
each other's writes.

## Key technical details

- **Entry points:**
  - `DataStoreChanges#journalTrappedContent`, called by `#getStoragePart` for every trapped part it
    hands out.
  - `EntityStoragePart#createPreImage()`, implemented by all five entity part types.
  - `ContainerizedLocalMutationExecutor#journalPartsHeldBeforeSavepoint`, called by
    `LocalMutationExecutorCollector` right after the root savepoint opens.
- **The pre-bracket hole.** The root executor fetches the entity body in its constructor, before the
  collector opens the savepoint, so that read journals nothing. `journalPartsHeldBeforeSavepoint`
  journals those parts once the bracket is open. It runs inside the `try`, because the 2026-08-26
  invariant forbids anything throwable between `open()` and the `try`/`finally`.
- **Once per slot per savepoint** (`slotsWithPreImage`, reset by `snapshot()`). The earliest pre-image
  runs last in strict-reverse replay and wins, so later copies of the same slot are dead weight. Copying
  on every read retained `K` full copies of a part read `K` times — `Θ(K·N)`, quadratic in a lookup loop
  that re-reads a large references part. An adversarial review found this before merge.
- **A non-null undo journal means a savepoint is open.** `restore()` and `releaseMemento()` both drop a
  drained journal (`dropUndoJournalWhenDrained`). An empty journal left behind would make every later
  trapped read outside a savepoint pay for a copy nothing will replay.
- **ALIVE needs the write-variant layer accessor.** `TransactionalDataStoreMemoryBuffer#fetch` resolves
  its layer with `getTransactionalMemoryLayerForWriteIfExists`, so the layer's snapshot is recorded into
  the open savepoint *before* the read pushes its inverse. With the read-variant accessor the push lands
  before the mark and is never replayed. This looks like a read using a write accessor by mistake; it is
  deliberate.
- **`createPreImage()` copies bookkeeping that never reaches disk**: the `dirty` flag and pending key
  reassignments. `ReferencesStoragePart`'s existing copy constructor is *not* a pre-image: it produces
  a non-dirty part and drops that state.

## Verification

- **Reproduction first, red on dev `096dda1094`:**
  - `TrappedStoragePartRollbackFunctionalTest` (15 tests, both modes): the reported case, the
    root-mutation shape and the body-locale shape, each in session, after close and after reopen, with
    a control per shape.
  - `TrappedStoragePartContentRollbackTest` (11 unit tests on `DataStoreChanges`): the later-executor
    re-trap path, the transactional memento path and the before-commit path.
  - 9 of the original 19 tests failed on dev. Every control passed.
  - One hypothesis was **not** reproduced: a `SealedEntity` fetched earlier being changed by a later
    in-place write. Its tests stay as a guard (`ReturnedEntitySnapshot`).
- **`EntityStoragePartPreImageTest`:**
  - a reflective completeness walk per part type, which also fails when a field holds its default in
    every fixture — a walk over defaults could not tell a copied field from a forgotten one;
  - independence tests: mutate the source, and the pre-image must not move.
- **Counterfactuals, each run and watched fail:**

  | Disabled | Result |
  |---|---|
  | the read-time pre-image | 9 reproduction tests red |
  | the pre-bracket hook | 2 root-mutation tests red |
  | the write-variant fetch | the ALIVE body test red |
  | pre-image independence | the guard test red |
  | the per-slot dedup | `expected 1 but was 4` |
  | the `dirty` copy | `PricesStoragePart.dirty is not carried over` |

- **Full functional suite:** 25,146 tests, 0 failures (one Docker-dependent error, unrelated).
- **Performance** — `WarmUpAtomicityIngestBenchmark`, the end-to-end harness of the 2026-08-26 record:
  - **Setup:** a cross-revision A/B (A = dev `096dda1094`, B = the same plus this fix). 3 rounds with
    arm order alternated (AB, BA, AB), 5 measured passes each after a discarded warm-up pass, 50,000
    products, heap `-Xmx16g -Xms16g`, a quiet machine (load 1.2) on 2026-10-08.
  - **Two corpora:** the default one, and `--categories=20`, which concentrates about 2,500 reflected
    references per category references part, so the copied parts are as large as the generator makes
    them.

  | corpus | ingest-thread CPU, A → B | B vs A | per-round B vs A | alloc B vs A |
  |---|---|---|---|---|
  | default | 29.23 → 28.85 s | −1.30 % | −1.30 / −1.36 / −0.28 % | −0.53 % |
  | 20 categories | 62.32 → 62.28 s | −0.06 % | −0.94 / −2.01 / +1.00 % | −1.70 % |

  - **No measurable cost:** every delta sits inside the round-to-round spread.
  - **The null result is not vacuous.** async-profiler call counting on `createPreImage` in arm B
    counted, per pass:
    - default corpus: 185,436 pre-images for 53,232 mutations;
    - 20-category corpus: 186,976 pre-images for 51,252 mutations, 93,488 of them references parts.

    The path is hot; the copy is simply small next to the rest of an upsert.
  - **What is not covered:** the production-corpus WARM_UP reindex (`warmup-reindex-benchmark`) could
    not be measured on the benchmark machine. Its writer exhausted the 23 GiB heap in both arms,
    baseline included, so that failure says nothing about this change.

## Consequences & open follow-ups

- **The 2026-08-26 granularity table was wrong about one family.** `DataStoreChanges` is a first-touch
  memento for its own bookkeeping *plus* a per-read content inverse for the trapped parts it hands out.
  `documentation/developer/stm/savepoints.md` now says so. Any future participant that hands out a
  mutable instance by reference has the same obligation: a slot or reference journal restores nothing
  its holder later changes in place.
- **A new field on any entity part type must be copied by its `createPreImage()`.** The completeness
  walk in `EntityStoragePartPreImageTest` fails when one is not.
- **#1615's in-flight registry seam is unaffected:** option A keeps the registry and the trapped map
  pointing at the same live instance. That branch's pre-PR checklist should still say so.

## Related work

- **`2026-08-26-warm-up-per-entity-mutation-atomicity`** — the savepoint mechanism this repairs. Its
  decision table filed `DataStoreChanges` as a reference-grab memento; this record corrects that premise
  for the trapped parts and reuses its benchmark and A/B protocol.

## Timeline

- **2026-10-07** — reproduced wider than reported (root mutations, body content, ALIVE in-transaction
  reads); option A implemented; adversarial review found the `Θ(K·N)` retention of repeated copies,
  fixed with per-slot dedup
- **2026-10-08** — interleaved A/B on both synthetic corpora: no measurable cost; engagement confirmed
  by call counting
