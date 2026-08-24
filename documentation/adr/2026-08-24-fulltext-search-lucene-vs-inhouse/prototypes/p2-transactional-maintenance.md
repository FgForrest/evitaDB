# P2 — transactional maintenance of the fulltext structures

> **Status: a prototype implementation plan, not a decision.** It derives from the research
> [`../research.md`](../research.md), in particular from §4.2 (data structures), §4.5 (transactions and
> visibility), §4.9 (the write-path budget) and §7 (the brief for prototype P2).
>
> Date: 2026-08-12. Verification against evitaDB's source code: 2026-08-12 (branch `dev`, `6a486f0a56`).
> Translated from Czech and moved into this record on 2026-08-24.

---

## 1. Goal and criteria

P2 is to answer a single question: **how much does maintaining the fulltext structures on the real write
path cost, and is that cost acceptable?** The criterion from §7 of the research reads "a drop in commit
throughput ≤ 10 %, otherwise the fallback of §4.5(3) is activated".

That brief has, however, to be refined right at the start, because in the form in which it is written it
cannot be decided. Three reasons:

1. **"Commit throughput" has two different axes in evitaDB.** The client waits by default for the write
   into the WAL (`WAIT_FOR_LOG_PERSISTENCE`), whereas the actual index update runs only in trunk
   incorporation on another thread. A measurement watching only the first axis will see zero impact for
   some design variants — not because the work disappeared, but because it hid behind the pipeline.
2. **The work is done twice on the write path.** Every mutation is applied once in an isolated copy of
   the indexes inside the client session (that is what gives read-your-writes) and a second time during
   replay in trunk incorporation. The fallback of §4.5(3) cancels that first half — it therefore does not
   move the work elsewhere but really erases it. That is a different (and better) property than the way
   the research describes the fallback.
3. **10 % is just above this harness's noise.** Historically WAL replay measurements work with a ~3 %
   noise level on wall time (see the ADR `2026-07-27-write-path-performance-tuning`), so a 10 %
   difference is decidable only with repetition and with the configuration nailed down.

**P2's refined criteria** (the order corresponds to the order of measurement in §9):

| # | What is measured | Threshold |
|---|---|---|
| K0 | fulltext off (schema flag off) against the same build without the structures | within noise (± 3 %) |
| K1 | e-commerce profile, session axis: median `apply mutations (session)` | drop ≤ 10 % |
| K2 | e-commerce, trunk axis: median `-> changes visible` with `waitForVisibility` | drop ≤ 10 % |
| K3 | CMS profile (long texts), both axes | drop ≤ 10 % |
| K4 | allocations: `gc.alloc.rate.norm` | increase ≤ 10 % |

K4 is not superfluous here: the prediction in §7 below says that in the CMS profile the risk concentrates
into the **number of allocations**, not into the bytes copied, and the allocation axis has repeatedly
proved in this repo to be the one that moves commit latency.

When K1 or K3 does not pass, two answers are in order, and in this sequence: first **variant D** (§6),
which leaves the write only the marking of the affected chunks as invalid and postpones the realignment
to the read, and only then the **fallback** (§8), which cancels the session-side maintenance entirely.
The measurement is repeated with whichever of them is chosen. When not even K2 passes with the fallback,
the problem is in the structure itself and it is necessary to return to variant C in §6.

---

## 2. Links to the research and what P2 inherits from P1

P2 does not build the structures — P1 does. For P2 to be runnable at all, it has to receive three things
from P1:

- **the term dictionary** over a transactional B+ tree and **the postings** over `TransactionalBitmap`
  (§4.2 of the research); both are per the research "transactional for free" and §5.1 below confirms it;
- **the impact sidecar** in a concrete physical shape: **rank-aligned `byte[]` chunks on roaring
  container boundaries** (shape S1 in `p1-index-core.md`, §4.3.2). **This is a commitment, not a
  recommendation**, and it is deliberately formulated through the shape, not through the variant: §6 of
  this document recommends variant B, but both B and D aim at the same layout and differ only in **when**
  the realignment happens (at commit-merge, or only at the first read). Were P1 to deliver a different
  shape, P2 would have to rebuild it before it started measuring — i.e. exactly the duplication of work
  the document warns of elsewhere;
- **a schema flag for a fulltext field including a maintenance switch.** The measurement baseline is *the
  same build with the flag switched off* (§10.1), so the flag has to exist before the structures it
  switches off. If P1 does not deliver it, it becomes P2's first step (§9, step 1) and P1 will have to be
  amended retroactively;
- **a design seam for the provenance of a match across a reference** (§1.4 of the research, question
  O10). P2 does not concern itself with it, but should the index-time expansion of content blocks be
  finished later, it will land on exactly the same write path P2 measures — the fan-out of editing a
  block is a multiple of what P2 measures for one document.

One thing goes the opposite way, from P2 to P1: for the escape route of variant C (§6) to stay open, P1
has to measure the cost of an **unaligned** representation on phase 1 too. If P1 does not measure it, the
rejection of variant C stays evaluated forever — see question O-P2-6.

Conversely, P2 **does not depend** on P5 (analyzers) in anything but that tokenization has to exist. Any
deterministic analyzer suffices for the measurement; the quality of stemming has no effect on P2's
result.

---

## 3. What already exists in the code (verified anchors)

This section is the core of the document: most of P2's design questions have an answer right in the repo
and it does not need inventing.

### 3.1 The transactional layer's protocol — the merge is driven by the owner, not by a global sweep

The whole family of transactional types lives in the package
`evita_engine/src/main/java/io/evitadb/core/transaction/memory/`. A transactional object implements
`TransactionalLayerProducer<DIFF, COPY>` (`TransactionalLayerProducer.java:39`) and its method
`createCopyWithMergedTransactionalMemory(layer, transactionalLayer)` receives its own diff layer and has
the task of producing a new, committed instance. The JavaDoc on lines 57–71 moreover explicitly imposes
the duty the whole design of §6 rests on: if the object holds references to further transactional
objects, it **must** request their committed form through
`TransactionalLayerMaintainer.getStateCopyWithCommittedChanges(...)`.

The only place where a diff layer is dissolved and discarded is the method `copyWithOwnLayer` in
`TransactionalLayerMaintainer.java:336` (the same package). An unprocessed layer brings the commit down
via `verifyLayerWasFullySwept` (same place, `:357`) — in this architecture one therefore cannot "forget"
and silently lose part of the changes.

**Consequence for P2:** the invariant "a chunk and a bitmap must commit atomically together" need not be
policed at runtime. It suffices that the sidecar is **not** a standalone `TransactionalLayerProducer`
with an arbitrary merge order, but that it is merged by its owner (the holder of the postings) inside its
own `createCopyWithMergedTransactionalMemory`, from the **same** instance of the merged bitmap it fetches
there. An invariant nobody can violate, because there is no seam for it.

### 3.2 `TransactionalBitmap` and its diff layer — and one trap

`TransactionalBitmap` (`.../index/bitmap/TransactionalBitmap.java:57`) is a
`TransactionalLayerProducer<BitmapChanges, Bitmap>`. The diff layer `BitmapChanges`
(`.../index/bitmap/BitmapChanges.java:41`) holds three roaring bitmaps: the immutable `originalBitmap`,
`insertions` and `removals`. The merged view is produced by `getMergedBitmap()` (same place, `:171`).

**A trap that cost one round of work on SortIndex and that applies here too:** the memoized result
`memoizedMergedBitmap` (`:58`) is nulled **on every** modification (`:142`, `:158`). Any design that asks
for the rank over the merged view during an open transaction pays the whole bitmap merge **on every
write**, and worst of all precisely for terms of large cardinality — i.e. where the sidecar ought to be
cheapest. A naive implementation thereby does not trade "a rebuild per transaction" for anything; it
merely crumbles it into "a merge per write".

The repo already knows the right reaction too: answer from the diff layer instead of from the merged
view. The precedent is `BitmapChanges.signedPreviousValue` (`:96`), which composes the answer from
`originalBitmap`, `insertions` and `removals` separately. An analogous `rank` from the diff layer is a
straightforward counterpart.

### 3.3 What "mechanism D5" actually did — a correction of the precedent

The research in §4.5(2) gives as a precedent for chunk COW "the bucket-anchored rebuild of `InvertedIndex`
value trees (mechanism D5)". **That precedent says something different from what it is cited for**, and
it is better to admit it right away, because a whole additional design variant follows from it.

D5 (delivered in PR #1348, described in `documentation/adr/2026-07-31-bulk-ingest-write-path.md`) solved
exactly the analogous problem: `SortIndex` held a derived structure aligned to the order (rank) of
records, and it was rebuilt from scratch in every transaction. The chosen solution **was not** to maintain
that structure more cheaply nor to copy it in parts. It was found that the rank has only two consumers in
the whole repo and both can be served **locally within a bucket** — and the derived structure was
**deleted entirely**. The write anchor has since been the predecessor within the bucket, not a global
position (`SortIndex.java:1121-1126`).

The lesson from the precedent therefore reads: *do not maintain a structure aligned to a global rank*,
not *copy it in chunks*. Two things follow for P2:

- the sidecar's alignment has to be held at **the smallest unit that is self-sufficient** — and that is a
  roaring container, not the whole bitmap (§3.6);
- the comparison of variants must include a variant with **no alignment at all** (variant C in §6),
  because that is exactly the move by which D5 succeeded.

### 3.4 The attribute's write path — the old value is available for free

A token diff needs the old value. It is already on the write path: `AttributeIndexMutator`
(`.../index/mutation/local/AttributeIndexMutator.java:151`) fetches it via
`ExistingAttributeValueSupplier.getAttributeValue(attributeKey)` (`:187`) and immediately afterwards calls
the pair `indexForRemoval.removeAttribute(...)` and `indexForUpsert.upsertAttribute(...)` (`:188-201`).

The source of the value is an `AttributesStoragePart` loaded through the container accessor
(`.../local/dataAccess/EntityStoragePartAccessorAttributeValueSupplier.java:78-86`), i.e. the same storage
part the write path needs for FilterIndex and SortIndex anyway. **The token diff therefore pays no extra
I/O** — it pays only for the tokenization.

The shape of the intervention is obvious from that: fulltext maintenance hangs at the same place as the
filter/sort index, as a third item of the same remove/upsert pair.

### 3.5 Trunk incorporation — every mutation is applied twice

This is the most important fact for the whole document and the research does not mention it.

After a commit the transaction is written into the WAL and **replayed again** against the shared catalog
in trunk incorporation. The loop is in `TransactionManager.processTransactions`
(`.../core/transaction/TransactionManager.java:1443-1500`); the replay itself is done by
`replayMutationsOnCatalog` (`:1893`), which for every `EntityUpsertMutation` calls `applyMutation` again
over the last finalized catalog (`:1914-1923`). A replay transaction is created with a
`TransactionTrunkFinalizer` (`:1469`), whereas a client session has a `TransactionWalFinalizer` — which is
at the same time the seam by which the two paths can be told apart
(`.../core/transaction/TransactionTrunkFinalizer.java:49`,
`.../core/transaction/TransactionWalFinalizer.java:54`).

Two things follow:

**First, all index work is done twice.** The census of 2026-07-27 (in the ADR
`2026-07-27-write-path-performance-tuning`, the file `reports/2026-07-27-wal-replay-rounds.md`)
quantifies it: the trunk re-apply is ~38 % of all application CPU and the trunk phase costs 6–7× as much
as the session apply over the same mutations. Fulltext maintenance therefore pays twice "from the
factory".

**Second, the loop is greedy and batches.** One round of trunk incorporation processes as many
transactions as it manages within the timeout, and only then calls `commitChangesToSharedCatalog` (`:1515`)
once. The batch application of the fulltext delta the research proposes as a fallback therefore **needs no
new mechanics** — batching already exists and the merge is done once per round for the whole batch.

### 3.6 Roaring: a container-local rank exists but is not public

The research in §4.2 writes that "the lookup is `bitmap.rank(pk)`". That is imprecise and harmful in the
design: `PersistentRoaringBitmap.rankLong`
(`evita_roaring_bitmap/.../PersistentRoaringBitmap.java:2321`) is a linear walk over all the containers
before `x`, and above all it is a **global** position that changes on any change anywhere in the bitmap.

For a sidecar aligned per container, however, the global rank is not needed at all. A chunk is addressed
by the container's key (the upper 16 bits of the PK) and the offset inside the chunk is the
**container-local** rank, which roaring provides as a primitive: `Container.rank(char lowbits)`
(`evita_roaring_bitmap/.../Container.java:881`). The alignment thereby becomes a local property: inserting
a PK invalidates only the chunk into whose container the PK falls, and nothing more.

Two practical notes:

- `ContainerPointer` is a package-private interface and `PersistentRoaringBitmap.getContainerPointer()`
  (`:3072`) is `public` but returns a package-private type — so it cannot be used from
  `io.evitadb.index`. **A precondition of realization is a small public access point** in the vendored
  module (the container's key + the container-local rank). The cost of vendoring is recorded in
  `documentation/adr/2026-07-07-roaring-bitmap-vendoring.md`; this is exactly the kind of small extension
  the vendoring was done for.
- The vendored roaring already does copy-on-write at container level itself (`RoaringArray.java:81`,
  `:103`, shared flags). Postings bitmaps therefore share containers between catalog versions — which is
  both a precedent for chunk COW and a warning: the sidecar must have its own sharing, it must not rely
  on the bitmap's.

### 3.7 The `WalReplayBenchmark` harness

The harness exists and is extraordinarily well equipped for P2.

The whole harness lies under `evita_test/evita_performance_tests/src/main/java/io/evitadb/performance/`.
The benchmark itself (`walreplay/WalReplayBenchmark.java:79`) is `SingleShotTime`, because a WAL slice is
one-off and unrepeatable work. The fixture (`walreplay/state/WalReplayState.java:133`) boots an embedded
evitaDB from a "pristine" snapshot and then replays the WAL segments from a later backup as **new**
transactions through the ordinary session/commit path (`:473`, `:581`). What is measured is therefore real
transactional processing, not a boot-time recovery shortcut.

Three properties are key for P2:

| System property | Anchor | What it is for in P2 |
|---|---|---|
| `evita.replay.waitForVisibility` | `WalReplayState.java:188-193` | uncovers the trunk axis (K2) |
| `evita.replay.perTxCsv` | `:194-199` | one line per transaction with the number of mutations |
| `evita.replay.maxTransactions` | `:170-174` | shortening an iteration while tuning |

The report (`ReplayStatistics.format()`, `:922`) decomposes the latency into phases: *apply mutations
(session)*, *conflict resolved*, *WAL persisted*, *changes visible*, *tx start → visible*. That
decomposition is exactly what K1 and K2 need.

The operational recipe (a build with the `full` profile, scripts for a dry/JMH/A-B run, four ways a run
silently lies) is in `.claude/skills/wal-replay-profiling/SKILL.md` — P2 adopts it, it does not reinvent
it.

---

## 4. A recapitulation of the problem in our own words

Both the dictionary and the postings are transactional for free, because they are built from existing
transactional types with diff layers. The problem is made by the **impact sidecar**: one byte per (field,
term, PK) triple that is to carry the precomputed match strength. If that byte lay in an array aligned to
the order of PKs in the postings bitmap, then inserting or removing a single PK shifts the position of all
the PKs after it — and under a diff layer, where the bitmap looks different to a reader inside the
transaction and different to a reader outside it, a single valid alignment ceases to exist.

The research's design answers that with copy-on-write of whole chunks on roaring container boundaries.
P2's question is whether that answer is the best of those on the table, and what it costs.

---

## 5. Data flow and invariants

### 5.1 Which version of the bitmap the position is computed against

The answer is unambiguous and follows from §3.1: **the committed sidecar has to be aligned to the
committed (merged) bitmap**, because precisely that becomes the new baseline. During a transaction,
however, the merged bitmap is expensive to query (§3.2), so a division applies:

- **at the moment of commit-merge** the alignment is against the instance the owner receives from
  `getStateCopyWithCommittedChanges(postings)` — there the merge happens exactly once and the memo holds;
- **during the transaction** the merged bitmap is not queried at all; the needed answer (does the PK exist
  in the bitmap? how many PKs precede it in its container?) is composed from `originalBitmap`,
  `insertions` and `removals` separately, after the pattern of `BitmapChanges.signedPreviousValue` (`:96`).

This is a **hard entry condition**, not an optimization. An implementation violating it will measure the
cost of repeatedly merging the bitmap and attribute it to COW — and P2 will then pronounce a wrong
verdict.

### 5.2 Atomicity of the chunk and the bitmap

Solved structurally (§3.1): the sidecar is not a standalone `TransactionalLayerProducer`. The owner of the
postings first obtains the committed bitmap in its `createCopyWithMergedTransactionalMemory` and then
produces the committed form of the chunks against **that specific instance**. Both leave the method as one
new, consistent object.

### 5.3 Savepoints

`BitmapChanges` implements `Snapshotable` (`:41`, the memento at `:208-242`) and a whole family of
long-running savepoint fuzz tests exists in the repo. Anything P2 adds as a per-transaction delta has to
have a memento too — otherwise a savepoint rollback lets the sidecar and the bitmap diverge. It is little
work (the delta is a plain map), but it is work that gets forgotten.

### 5.4 Dirty tracking

Changed chunks have to be registered as dirty, otherwise a flush will not write them. The signal is the
dirty set snapshotted by `DataStoreChanges.popTrappedUpdates` (the same set is used by the pruned merge
from round 4 in the write-path performance ADR). This is a repeatedly failing place — a forgotten registration manifests
only as data loss after a restart, not as a test failure.

---

## 6. The design of the COW mechanics — variants

### Variant A — COW of a chunk on the first write in a transaction

Exactly what §4.5(2) of the research describes. The sidecar is a map `container key → byte[]`. On the
first write into a given (field, term, container) combination the chunk is copied into the diff layer and
from then on is maintained aligned to the running state of the bitmap; at commit the private copy becomes
the new baseline.

**For:** reading inside a transaction is as cheap as reading outside it — one array index.
Read-your-writes is exact.

**Against:** the cost is paid on **every** insertion, not only the first. Inserting a PK into the middle of
a container shifts all the bytes after it, which is either a new array allocation or a `System.arraycopy`
in place — i.e. O(the term's cardinality in the container) per write. On top of that every write needs a
container-local rank from the diff layer (§5.1). For a transaction editing N documents the cost is
multiplied N times, because every edit touches the same high-frequency terms again.

### Variant B — deferred realignment at merge (recommended)

During a transaction **no alignment is maintained**. The sidecar's diff layer is a plain, unaligned delta:
for every affected (field, term) combination a list of `(PK, impact byte)` pairs for inserted and changed
ones and a set of PKs for removed ones. The delta is small — it is proportional to the size of the
transaction, not to the size of the index.

The aligned chunks arise **once, at commit-merge**, inside the owner's
`createCopyWithMergedTransactionalMemory`: it already has the merged bitmap there (§5.2), walks the
affected containers in a single pass and produces new `byte[]`s by joining the old baseline with the
delta. Unaffected containers are passed **by reference**, they are not copied.

**For:**
- The cost is O(the size of the affected chunks) **once per commit**, not once per write. A transaction
  editing N documents sharing a high-frequency term pays that chunk once, not N times.
- It fits the batching of trunk incorporation (§3.5): one round replays several transactions and the merge
  is done once for the whole batch, so the amortization is automatic and free.
- During a transaction the merged bitmap is not touched at all, so the trap of §3.2 is sidestepped by
  construction, not by discipline.
- That is exactly the move by which D5 succeeded (§3.3): a derived alignment is not maintained
  continuously, it is computed where it is cheap.

**Against:**
- Reading inside an open transaction has to consult the delta before reaching into a chunk — i.e. one
  extra condition and a hash lookup, but **only for the terms that transaction touched**. For a query not
  meeting the transaction in any term the cost is zero.
- The delta has to support a memento (§5.3).

### Variant C — a sidecar with no alignment at all

The impact is not stored in an array aligned to a position, but in an existing transactional type keyed by
the (term, PK) pair — `TransactionalIntToLongBPlusTree`
(`.../index/bPlusTree/TransactionalIntToLongBPlusTree.java`) with a PK key and a `long` value carrying the
packed impacts of several fields suggests itself, or `PersistentTransactionalMap`
(`.../index/map/PersistentTransactionalMap.java:107`).

**For:** transactionality is free, realignment does not exist, savepoints and the merge are handled by
existing and tested mechanics. The write path is thereby practically without risk — which is exactly what
P2 measures.

**Against:** the cost is paid by reading, i.e. the phase 1 ranking, whose goal is ≤ 25 ms per 10⁶
candidates (P1). Instead of an array index, a tree or a hash per candidate is paid. On top of that memory:
a tree node per pair instead of a single byte is an order-of-magnitude difference against the ~20 MB
estimate in §4.8 of the research.

**Rejected as the default choice**, but **not rejected permanently**: it is the escape route if K1–K3 pass
neither with variant B nor with the fallback. The decisive number (by how much it slows phase 1) will come
from P1, not from P2 — until then the rejection is conditional.

### Variant D — invalidate on write, compute lazily on read

Verification over the OpenSearch checkout (`main`, commit `36edc05ac84`, 2026-08-12) brought a variant this
document did not have until now. The star-tree there is a precomputed aggregation structure lying beside
the inverted index — i.e. the nearest existing analogue of our sidecar — and **the query path uses it only
when it passes a gate**. That gate is a three-part condition in `StarTreeQueryHelper.isStarTreeSupported()`
(`server/src/main/java/org/opensearch/search/startree/StarTreeQueryHelper.java:53`); when it does not pass,
nothing crashes — the structure simply is not used and the query is evaluated by the normal path. A side
structure is there **purely an acceleration, never a bearer of correctness**, and precisely that is worth
adopting literally.

Transferred onto the sidecar: a write **marks the affected chunk invalid** and does not perform its
realignment, a read **materializes the chunk only when it first needs it**. The write cost thereby drops to
a write into the delta and one flag, i.e. to a value the tools of §10 probably will not even distinguish
from noise. And because per Z7 fulltext is under 1 % of queries, that asymmetry is markedly in our favour:
one pays for what is actually read, and the vast majority of writes never demand a realignment.

**What variant D is and is not.** It is **variant B with the realignment moved from commit-merge to the
first read**, not a different data layout. It assumes the same chunk shape P1 recommends (rank-aligned
`byte[]`s per container, shape S1 in `p1-index-core.md`, §4.3.2), plus one validity bit per chunk — P1
therefore rebuilds nothing because of it and its §4.3.4 stays valid. The computation is moreover **a
realignment from the remaining delta, not a recomputation from the source text**. The impact byte is a
function of `tf` and the field length; the field length is stored by P1's design in a dense array per
(field, PK) (`p1-index-core.md`, §4.4), but **`tf` is stored nowhere** — its only trace is precisely that
impact byte. Whoever wanted to compute the impact bytes at query time would have to re-tokenize the
attribute values, which is work of the order of indexing performed at read time. Variant D therefore
carries the old values over and takes the new ones from the delta; it recomputes nothing.

**What it is paid with.** A chunk's invalidity is itself transactional state and inherits all the duties of
§5: both the flag and the outstanding delta have to commit atomically with the bitmap (§5.2), have to have
a memento for savepoints (§5.3) and have to be registered as dirty (§5.4). To that is added a duty variants
A to C do not have — **the outstanding delta survives the commit** and grows until somebody reads it.
Continuous writing without a single fulltext query is therefore a state in which the delta grows without
bound, and variant D without an upper limit is unusable. The cheapest limit is "on exceeding a threshold
the realignment is performed at the commit after all", i.e. a return to variant B's behaviour for the
affected chunks.

**The degraded "skip the lane" mode and why it is not free.** The star-tree can afford a silent fall back
to the normal path because that path gives **the same answer**, only more slowly. Skipping the impact lane
for us does not give the same answer — it changes the order of results. It thereby collides with two things
at once: with the testable property P1 is to introduce (a document's score independent of the candidate
set, `p1-index-core.md`, §7.6), and with the argument of §8.3 of this document that an engine changing its
semantics by a runtime heuristic is untestable and non-deterministic. **The recommendation therefore reads:
variant D's primary behaviour is the lazy computation; skipping the lane is not introduced without an
explicit decision** — and were it to be introduced, it must be measured precisely against that property,
not merely on latency.

**What has to be measured before variant D can be recommended:**

1. **The write axis (K1, K2) against variant B.** D is expected to be strictly cheaper; if it is not, the
   error is in the implementation of the marking, not in the variant.
2. **The cost of a cold chunk on read.** By how much phase 1 lengthens for a query that is the first to
   reach for the chunks affected by a preceding batch of writes. Phase 1's budget is ≤ 25 ms per 10⁶
   candidates (P1) and the materialization has to fit into it even in the worst case, not on average.
3. **The growth of the outstanding delta** under writes without reads and the behaviour on reaching the
   limit.
4. **The share of chunks materialized more than once between two writes** — i.e. whether a materialized
   chunk is worth storing, or whether computing it in place and discarding it suffices.

### Recommendation and one refinement

**Variant B is recommended.** It is cheaper than A on every axis P2 measures, it observes the lesson of D5
and it is the only one that gets the batching of trunk incorporation for free. Variant A remains as a
fallback shape should consulting the delta on reads inside a transaction turn out to be a problem (which
would mean that reads and writes overlap strongly in time — with a profile where fulltext is < 1 % of
queries, that is improbable).

**Variant D is a continuation of the same idea one step further and is measured only when B does not
pass.** The reason not to start with it right away is the ratio of certainty to cost: B is finished at the
moment of the commit and carries no state surviving into subsequent transactions, whereas D acquires an
outstanding delta with its own limit, its own memento and its own trap (a cold chunk in the hot read path).
When B meets the criteria, it is work there is nothing to buy it with. When it does not, D is a cheaper
answer than the fallback, because unlike it, it **does not touch the visibility contract** — a reader inside
a transaction still sees its own writes, it merely pays for them the first time with a materialization.

A refinement worth measuring inside variant B: **a cardinality threshold**. Under a Zipf distribution the
vast majority of (term, container) combinations are tiny — single-digit PKs. For them the whole chunk
apparatus is overhead without benefit and storing the pairs directly (as in variant C) is cheaper both on
memory and on writes. The threshold is a configuration number, not architecture; introducing it right at
the start makes no sense, though — first measure whether there is anything to save.

---

## 7. The token diff and the risky case

### 7.1 How to compute the term difference cheaply

The inputs are available (§3.4). A procedure in three steps, ordered by how much they save:

1. **Value equality as the first shortcut.** evitaDB clients commonly send the whole entity even when one
   field changed. When the old and the new value are equal, fulltext maintenance does nothing and no
   tokenization happens at all. This is by far the biggest lever and it has to be the first thing
   implemented.
2. **Tokenizing both values and a multiset difference.** For each side a map `term → tf` is computed; the
   affected terms are only those whose `tf` changed, plus terms that appeared or disappeared.
3. **Length normalization is treacherous — it has to be coarsely quantized.** The impact byte is per §4.2
   `sat(tf) × norm(field length)`. When `norm` is computed finely, adding a single word into a long
   article changes the field's length, thereby `norm`, and thereby the impact byte of **absolutely all**
   ~500–600 terms of that document. A single-word edit turns into a rebuild of the whole document.

   The solution is in the choice of representation itself, it merely has to be stated as an invariant: the
   normalization is quantized into a byte so coarsely that an ordinary edit does not move its value. That
   is exactly what Lucene does (`SmallFloat.intToByte4`, the decoding table in `BM25Similarity`, see §3 and
   the §8/VK notes of the research). **P2's invariant: `norm` must be a function of length that is constant
   over wide intervals of lengths.**

The cost of the tokenization itself: for a CMS document of the order of 10 kB of text a pass through the
analyzer is of the order of hundreds of microseconds to single-digit milliseconds, and it is done twice
(the old and the new value). Against the marginal cost of ~7 ms per mutation measured in the trunk phase on
production e-commerce data it is noticeable but not fatal — and step 1 erases it entirely for most mutations. This is an
estimate, not a measurement; K3 verifies it.

### 7.2 An estimate of the risky case's cost

The parameters from §4.8 of the research: the CMS profile, ~100 thousand long documents, ~500–600 unique
terms per document.

Editing one document touches up to ~600 terms, but **always only one container** — the one the edited
document's PK falls into. The size of a chunk for a (term, container) pair is therefore the term's
cardinality within that container, which with 100 thousand documents is roughly `df/2` (two containers).
Under Zipf that means:

- a few high-frequency terms the document contains (say ten with a `df` in the tens of thousands) carry
  chunks of the order of single to tens of kilobytes;
- the remaining ~590 terms have a `df` in the single digits to hundreds, so chunks of tens of bytes.

The sum of copied bytes per edit comes out at the order of **hundreds of kilobytes** — as a `memcpy` that
is tens of microseconds and utterly uninteresting. What is interesting is the second addend: **~600
separate small `byte[]` allocations per document edit.**

That arithmetic is, however, computed for variant A, where the chunks are realigned on write. The
recommended variant B pays it once per commit-merge, not once per edit, so the ~600 allocations are shared
across all the documents modified in one transaction and across the whole batch trunk incorporation
processes in one round. By how much less that is depends on the shape of the real WAL — on how many
documents one transaction edits and how much their vocabularies overlap — and precisely for that reason it
has to be measured, not estimated. The prediction is therefore formulated as a band between the two
variants:

> **A prediction P2 is to confirm or refute.** The e-commerce profile (~20 tokens per document) will pass
> both K1 and K2 with a margin in both variants; the impact will be within noise. The CMS profile in
> variant A will fail on the allocation axis (K4) before the time one (K3), and the dominant contribution
> will not be copying bytes but the number of allocated arrays. Variant B will pass the same profile,
> because the amortization across the commit and across the trunk's batch reduces the number of allocations
> by a factor equal to the average number of documents modified in one trunk round.

Were the first half of the prediction to be confirmed and the second not — i.e. were the CMS profile to
fail even with variant B — the right reaction is **not** the fallback right away, but the cardinality
threshold (§6): it erases precisely those ~590 allocations with minimal content. The fallback solves a
different thing, namely where the work is done, not how much of it there is.

### 7.3 When it pays to go straight to the fallback

The fallback (§8) is in order when the work itself is acceptable but must not burden the thread the client
is waiting on. Specifically: **for searchable associated data** (long texts, O6 in the research) it makes
sense to switch it on right away, without measuring variant B on the session axis. The reason is that long
texts are precisely the class where the token diff, the tokenization and the number of affected terms all
grow by an order, and read-your-writes over an article during its editing has no real consumer — unlike a
short product attribute, where a validation query within the same transaction may want it.

---

## 8. The fallback: visibility after commit

Before the mechanics are described, a correction of tone belongs here. Both the research and earlier
versions of this document formulate the fallback as an emergency exit — "if P2 shows an unbearable tax, we
relax". Two independent checkouts show it is on the contrary **the market's ordinary answer**, and one of
them even the only answer.

Verification over the Elasticsearch checkout (`main`, commit `9a100e2d0e41`, 2026-08-13): durability and
visibility are **two different things** there. Durability is held by the shard's transaction log and its
default policy is an fsync after every request (`Translog.Durability.REQUEST`,
`server/src/main/java/org/elasticsearch/index/IndexSettings.java:120`), whereas visibility is ensured only
by a refresh, i.e. opening a new reader over the newly created segments, and that has a default period of
**one second** (`IndexSettings.java:311`, the setting at `:320`). A written and fsynced document is
therefore not visible in search until a refresh happens. Our boundary would meanwhile be the commit of a
transaction, i.e. a semantically defined point, whereas for them it is the passing of a second — a point
unrelated to the data at all. **Our fallback is strictly better than the default behaviour of the largest
deployed engine in this category.**

Verification over the OpenSearch checkout (`main`, commit `36edc05ac84`, 2026-08-12) supplies the other
half of the picture. The star-tree, the nearest existing analogue of a side structure beside the inverted
index, **has no incremental update path at all**: `StarTreesBuilder` has exactly two entry points and both
build the structure whole — `build()` at flush and `buildDuringMerge()` at segment merging
(`server/src/main/java/org/opensearch/index/compositeindex/datacube/startree/builder/StarTreesBuilder.java:77`
and `:114`). Adding such a structure to an existing index is moreover impossible; the validator rejects it
with an exception
(`server/src/main/java/org/opensearch/index/compositeindex/CompositeIndexValidator.java:37`).

Honesty demands adding two things, though, so that a stronger conclusion is not drawn than it can bear.
First, **a star-tree is a combinatorially much heavier structure than our flat sidecar**: one new document
contributes into all the nodes corresponding to all the subsets of its dimension values, including the star
ones, so the number of affected places is unknown in advance. Our sidecar has one byte per (field, term,
PK) triple and writing a document touches exactly the terms the document contains — the number of affected
places is bounded by the document's length. From "a star-tree is not maintained incrementally" one
therefore cannot deduce "our sidecar cannot be maintained". Second, and this is more essential, **their
answer presupposes segments we do not have**: "build it again at flush" is cheap only because a Lucene
index is a sequence of immutable segments and the rebuild amortizes across the merge policy. evitaDB has a
live transactional index with diff layers and **no flush at which it could be performed**. OpenSearch does
not solve our problem differently — it does not have it. The right conclusion therefore reads that **P2
measures something for which no comparison point exists in this field**, which is a more honest formulation
than "nobody does it, so it is risky".

### 8.1 What it means in evitaDB's terms

The fulltext structures **do not get a session-side diff layer**. Maintenance is skipped when the
transaction runs under a `TransactionWalFinalizer` (a client session) and is performed only under a
`TransactionTrunkFinalizer` (replay in trunk incorporation) — the seam is described in §3.5.

Because every mutation is applied twice (§3.5), the fallback **does not move the work but erases it**:
instead of twice it is done once, on the trunk thread, which would be doing it anyway. The session thread
is thereby unburdened and the trunk thread is no worse off than before. That is a substantially better
offer than the way the research describes the fallback (§4.5(3) speaks of "batch application", as if it
were additional work).

That the session-side work is really discarded and not carried somewhere is verified in the code, not
merely derived from the profile: `TransactionWalFinalizer.commit`
(`.../core/transaction/TransactionWalFinalizer.java:126-188`) on committing a client transaction validates
the dirty scopes, closes the registered closeables and hands the isolated WAL over to be copied into the
shared one. On the index structures it **does not call `getStateCopyWithCommittedChanges` at all** — their
isolated diff layers simply cease to exist. The only thing surviving a client session is the mutations
themselves in the WAL, and those are then replayed in the trunk.

Batching is moreover obtained for free: the greedy loop in `processTransactions` (`:1443-1500`) processes
several transactions in one round and does the merge once (`:1515`) — so the realignment of chunks from
variant B amortizes across the whole batch.

### 8.2 Where it shows up in the query path — and what it means for the contract

Inside a read-write session that has already performed writes, `attributeMatches` is evaluated against the
last committed version of the catalog. That creates an **asymmetry between two filtering constraints in
one query**: `attributeContains` sees its own uncommitted write (it goes through the ordinary `FilterIndex`
with a diff layer), `attributeMatches` does not.

This is not merely "older results", it is a difference in the API's contract and a reviewer will rightly
reach for it. Three options for handling it:

1. **Document it as a property.** The cheapest, but silent — the user will hit the difference in
   production.
2. **Warn when `relevance()` runs in a session with unwritten changes.** Explicit, non-blocking.
3. **Reject the query** in such a situation. The strictest, but annoying for write-heavy clients.

Recommendation: option 2 as the default, with the possibility of switching to 1. The exact shape belongs
in question O4 of the research (the DSL's shape and `relevance()`'s behaviour), not in P2 — P2 only has to
demand the answer before the fallback is delivered as a production mode.

To all three a cheap complement moreover suggests itself that replaces none of them. Elasticsearch governs
the relation of a write to visibility with the enum `WriteRequest.RefreshPolicy`
(`server/src/main/java/org/elasticsearch/action/support/WriteRequest.java:53`, verified over the `main`
checkout, commit `9a100e2d0e41`, 2026-08-13) with three values: `NONE` (the default, the write does not
care about visibility), `IMMEDIATE` (force visibility right away, expensive) and **`WAIT_UNTIL`** (hold
the response until the change is visible). That third value is a nice design pattern precisely because it
**does not speed visibility up in any way — it merely synchronizes the client with it**. The client gets a
response at the moment when a subsequent search would already see the change, and nobody pays for a forced
speed-up.

For us it is an optional wait by the write API for trunk incorporation to process the transaction in
question. The seam for it exists and is tested: precisely this is done by the harness property
`evita.replay.waitForVisibility` (`WalReplayState.java:188-193`, §3.7) when it uncovers the trunk axis of
measurement. A client that really needs read-your-writes over fulltext can therefore request it at the cost
of its own write's latency, instead of the maintenance mode being changed for everybody because of it. It
is a purely synchronizing element at the API boundary, not a change of the index model.

### 8.3 How the fallback is switched on

Recommendation: **per attribute (or per schema), not globally and definitely not automatically.**

- **Per attribute** because the profiles differ within one catalog: a short product name tolerates
  session-side maintenance unnoticed, a long article in associated data does not. The configuration will
  already be there — the schema flag for a searchable field (O6) can carry the maintenance mode too.
- **Not automatically by measurement**, because an engine that changes its visibility semantics by a
  runtime heuristic is untestable and non-deterministic. Determinism is one of the load-bearing arguments
  of the whole chosen architecture (§2.1 of the research) and there is no sense in sacrificing it for
  configuration convenience.

Were **per catalog** granularity nevertheless ever considered, it is worth noticing how OpenSearch handled
the same question (verified over the `main` checkout, commit `36edc05ac84`, 2026-08-12). The replication
type, i.e. the choice that determines their visibility guarantees on a replica, is the setting
`index.replication.type` with the flag `Property.Final`
(`server/src/main/java/org/opensearch/cluster/metadata/IndexMetadata.java:389`) — **immutable after index
creation**. The reason is obvious: switching it would change the guarantees already-written clients rely
on. If our fallback were therefore configurable per catalog, such a choice belongs **in the configuration
at catalog creation, not among dynamically changeable settings**.

Do not confuse this with the escape route from a rejected schema change, which `schema-design.md` handles
(question S2, `allowingRebuild(...)`). That is about **a one-off permission for one operation** passed as a
mutation parameter; this is about **a visibility mode** that holds permanently and changes the query's
contract. Both look like "a switch at the schema", but they have opposite lifetimes.

---

## 9. The realization procedure

The order is chosen so that each step can be verified independently and so that the measurement baseline
exists before the thing measured.

1. **The schema flag and the switch, before the structures.** So that the baseline for K0 is *the same
   build* with the flag switched off, and not an older commit — otherwise unrelated drift leaks into the
   measurement. Part of it is a test "off = no measurable difference"; this kind of guarantee has already
   once been declared in this repo and did not hold, so it is verified, not assumed.
2. **A public access point in the vendored roaring** — the container's key and the container-local rank
   (§3.6). A small, isolated change, but it blocks everything else.
3. **`rank` from the `BitmapChanges` diff layer** after the pattern of `signedPreviousValue` (`:96`). Even
   variant B needs it for reading inside a transaction.
4. **The sidecar in variant B**: an unaligned per-transaction delta, realignment in the commit-merge driven
   by the postings' owner (§5.2), a memento for savepoints (§5.3), dirty registration (§5.4).
5. **Hanging it on the attribute's write path** beside the filter/sort index (§3.4), including the value
   equality shortcut (§7.1, step 1) — that is implemented first, not as an optimization afterwards.
6. **Parity and fuzz.** A randomized test "the sidecar aligned against the bitmap after an arbitrary
   sequence of insertions, removals, savepoints and rollbacks". The family of long-running savepoint tests
   in the repo is the template; D5 used this set and it caught a real bug the green fast set missed.
7. **Measurement per §10.**
8. **The cardinality threshold**, only if the measurement demands it.
9. **Variant D** (invalidation on write, lazy computation on read), only if the cardinality threshold does
   not suffice. It adds three things to the finished code of step 4 — a validity bit per chunk, the
   survival of the outstanding delta across a commit and its upper limit — so it is done after it, not
   instead of it.
10. **The fallback**, only if K1 or K3 do not pass (or right away for searchable associated data, §7.3).

---

## 10. The harness and measurement methodology

### 10.1 Definition of the baseline

The baseline is **the same build and the same WAL** with the fulltext schema flag switched off. Not an
earlier commit, not a different branch. The reason is recorded in the write-path performance ADR: between two commits drift
gets into the measurement that has nothing to do with the change being measured, and a 10 % difference does
not survive it.

Nailed down between the arms: `evita.replay.syncWrites`, `evita.replay.flushFrequencyInMillis`,
`evita.replay.minimalActiveRecordShare`, `evita.replay.maxTransactions`,
`evita.replay.transactionQueueSize`, `evita.replay.maxPendingVisibility`, `-Xmx`, and the same machine.
(A supplementary note: the storage knobs are measured to be a no-op for this workload, so they are not
tuned — they are merely fixed so that they are not a free variable.)

Before every measurement, a check that the machine is quiet (`uptime`, `ps -eo pcpu,args --sort=-pcpu`).
Wall time and CPU shares from a loaded machine are unusable; allocation shares remain usable.

### 10.2 Two axes, two modes

| Axis | Harness mode | Leading metric | What it answers |
|---|---|---|---|
| session | default (`waitForVisibility=false`) | median `apply mutations (session)` | K1 |
| trunk | `evita.replay.waitForVisibility=true` | median `-> changes visible` | K2 |

Measuring only the default mode is not enough and it is the easiest way to get a wrong verdict out of P2:
the default mode waits for the write into the WAL and lets visibility run asynchronously, so it hides the
trunk-side work behind the pipeline. The fallback (§8) moves precisely the first axis and leaves the second
unchanged — were only the first measured, the fallback would look like a miracle and the full variant like
a failure, while the total work performed would be smaller with the fallback, not larger.

**A pre-registered metric that must NOT move:** `-> conflict resolved`. Fulltext maintenance does not touch
conflict resolution. If it moves, there is an uncontrolled variable in the measurement and the result is
discarded. (That discipline — saying in advance what must not move — cost this repo two rounds of
measurement.)

### 10.3 Per transaction, not in aggregate

The aggregate `tx/s` is a function of the **mix of transaction sizes** in the WAL slice. A WAL with a few
enormous transactions makes the aggregate almost purely a function of them and the difference on small
transactions vanishes in it. This trap was already once overlooked by an ingest measurement.

Procedure: switch on `evita.replay.perTxCsv` on both arms and compare **conditionally on the `mutations`
column**. Concretely, fit through both arms a dependence of the form

```
visible_ms ≈ intercept + slope × mutations
```

and compare `intercept` and `slope` **separately**. The precedent for the shape and for why it makes sense
is in the write-path performance ADR: the measured `visible_ms ≈ 2771 + 6.98 × mutations` revealed that the problem was
fixed per-pass overhead, not the marginal cost of a mutation. For fulltext the opposite is expected —
maintenance is proportional to the number of affected terms, so it should move the **slope**, not the
intercept. If the intercept moves, something else is wrong.

### 10.4 The allocation axis

Besides latency, `-prof gc` is run and `gc.alloc.rate.norm` compared (K4). The reason is the prediction in
§7.2: for the CMS profile a failure on the number of allocations is expected before one on time. When
comparing allocation profiles across runs, compare **absolute sample counts**, not percentages — when the
total allocation drops, the share of every surviving site grows and reads as a regression.

### 10.5 Datasets

- **The e-commerce profile:** the production e-commerce export used as standard in this repo for WAL replay. It exists.
- **The CMS profile:** **it does not exist and it is a blocker for half the gate.** Two paths: either
  extend the schema of the existing export with a long localized text attribute and generate a WAL against
  it with a realistic edit frequency, or obtain an export from a real CMS deployment. The first path is
  faster and sufficient for measuring the write path (the quality of the text does not matter here, only
  its length and the frequency of edits); the second is more faithful. Recommendation: start with the
  first, keep the second for confirmation if the first shows a result close to the threshold.

  A note on the research: §4.2 says the first round indexes attributes only and long texts live in
  associated data without an indexing concept. For the measurement that does not matter — the harness can
  feed long texts in as attributes, as the research itself permits.

### 10.6 Repetition and the rule for the verdict

The arms are interleaved (A, B, A, B, …), at least three repetitions per arm. The verdict is taken from the
median of the individual runs' medians, not from a single run. With a difference below ~3 % the result is
reported as "within noise", not as an improvement or a regression.

---

## 11. Open questions

- **O-P2-1 — the CMS dataset.** How to produce a WAL with long texts (a synthetic extension of the existing
  export's schema vs. a real CMS export) and what a realistic edit frequency of a long document is. It
  blocks K3, i.e. half the gate. Decide before step 4 in §9 begins.
- **O-P2-2 — the exact width and quantization of `norm`.** §7.1 sets the invariant ("constant over wide
  intervals of lengths"), but the concrete quantization scheme stays open and has a direct effect on how
  many terms one edit dirties. Related to question O1 of the research (the default rank profile).
- **O-P2-3 — the cardinality threshold.** If a hybrid representation is to be introduced (§6), at what
  value and is the threshold measured in PKs per (term, container), or in bytes of the chunk? Decide only
  from P2's data.
- **O-P2-4 — the behaviour of `relevance()` in a session with unwritten changes** with the fallback
  switched on (document / warn / reject, §8.2). It belongs in question O4 of the research; P2 merely
  requires it answered before the fallback becomes a production mode.
- **O-P2-5 — the switch's granularity.** §8.3's recommendation is "per attribute", but the concrete shape
  (a separate flag vs. a value in the flag for a searchable field per O6) stays open. Were per-catalog
  granularity to be added to it, per §8.3 the right place is the configuration at catalog creation, not a
  dynamic setting — which is a further input into the choice of shape.
- **O-P2-6 — the cost of variant C on reads.** The rejection of variant C in §6 is conditional and the
  decisive number (the slowdown of phase 1) will come from P1, not from P2. Beware of the silent way of
  leaving this question open forever: P1 will measure that number only if it is briefed to measure phase 1
  **over the unaligned representation too**, not only over the aligned one. Without that the escape route
  stays unevaluated at the moment it is needed. If P1 shows the difference is small, the balance of forces
  between P1 and P2 changes and it is in order to return to C.
- **O-P2-7 — the fan-out of index-time expansion (§1.4, O10 of the research).** Editing a content block
  touches all the referencing pages, i.e. a multiple of what P2 measures for one document. P2 **does not
  measure** this case and deliberately leaves it out of scope, but the number P2 measures for one document
  is an input into the decision about O10.
- **O-P2-8 — the upper limit of the outstanding delta in variant D (§6).** A delta nobody reads grows
  without bound, so variant D without a limit is unusable. Open is what the limit is expressed in (the
  number of chunks, the number of pairs, the memory consumed) and what happens on reaching it — the
  proposed behaviour is a forced realignment at commit, i.e. a return to variant B for the affected chunks.
  Related to it is the cost of a cold chunk in the hot read path, which measurement 2 in §6 has to evaluate
  against phase 1's budget from P1.
- **O-P2-9 — may a degraded "skip the lane" mode come about at all?** With a star-tree a silent fall back
  to the normal path is safe, because that path gives the same answer. Skipping the impact lane for us
  changes the order of results, so it would be **a runtime heuristic changing semantics** — exactly what
  §8.3 prohibits for visibility. It has to be decided before variant D starts being written, because the
  answer determines whether the structure needs a validity bit, or also a "the chunk is missing and will
  not come" path. The evaluation criterion is the property from `p1-index-core.md`, §7.6.
