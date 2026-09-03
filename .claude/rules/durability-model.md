# Durability Model

`module-boundaries.md` says **who** may touch storage. This says **what reaching the disk means** — the guarantees
the engine may assume when it reasons about failure, recovery and refusal. Read it before writing any code that
decides whether something is safe to persist, or any message that tells an operator their data is damaged.

## The invariant

**Nothing is durable until the bootstrap record publishes it.**

- Every data file — the catalog file and every entity collection file — is **strictly append-only**. Bytes once
  written are never overwritten.
- A record is reachable only through a pointer chain: bootstrap record → offset index in the catalog data file →
  catalog header → offset index in each collection data file. Nothing walks a data file looking for records.
- **The bootstrap record is written last**, after every byte it references is already on disk. It is the single
  act that makes a new state the catalog's state.
- Until that write happens, the previous bootstrap record still points at the previous, complete, correct state.

The full model, with record layouts and the reasoning behind them, is
`documentation/user/en/deep-dive/storage-model.md` — read it once; it is the authority, and it is a reference to
consult, not merely a user-facing file to maintain. `documentation/developer/indexes/offset-index.md` covers the
index that the pointers address.

## What follows, and it is not intuitive

**Bytes nothing points at are inert.** A half-written record, a record from a mutation that later failed, a
collection flushed out of step with its siblings — none of it is corruption. It is dead space in an append-only
file, indistinguishable from the stale versions that compaction exists to reclaim. It costs disk, never
correctness.

**"Refuse to persist" therefore always means "refuse to publish", never "refuse to write bytes".** A failure
handler that blocks writes is protecting against nothing and paying for it; a failure handler that blocks the
bootstrap write protects everything, because everything upstream of it is unreachable by construction.

**A torn set of collection headers cannot reach disk on its own.** It becomes real only if a bootstrap record is
written naming a catalog file whose header references it. That single write is the whole exposure, and it is one
call: `DefaultCatalogPersistenceService#writeCatalogBootstrap`, at the end of `storeHeader`.

**Appends are inert, but DELETES are not — and compaction deletes.** Everything above is about bytes being
*added*. Compaction is the other direction: it rewrites a data file into a new one and retires the old. The old
file is still named by the **currently published** bootstrap record until the round that supersedes it publishes,
so unlinking it inside that round removes a file the pointer chain a reload follows still reaches — turning a
crash into an unloadable catalog with no bootstrap write involved at all. Hence the companion invariant:

> **No file reachable from the currently published bootstrap record may be deleted before the record that
> supersedes it is published.** Retirement is a confirm-phase action, not part of the write.

`DefaultCatalogPersistenceService#retireDataFile` parks warm-up retirements and `#writeCatalogBootstrap` releases
them once the superseding record is durable; `#close()` drops whatever is still parked, because a round that
never published must not take the files the last published record still names.

**In `WARM_UP` every flush publishes.** Bulk load writes a bootstrap record on every flush, so the on-disk state
advances repeatedly during a load; there is no single end-of-load publication. In `ALIVE`, publication may be
deferred to a checkpoint (`checkpointCoordinator`), so bytes can sit unpublished for a while by design.

**A catalog whose *in-memory* state is broken is recovered completely by reload.** The disk was never damaged, so
reload lands on the last published state — a full recovery, not damage limitation. Say that to the operator.
Telling them "the persisted state is incomplete" when it is merely *older* sends them looking for corruption
that does not exist.

## Naming failures honestly

The vocabulary matters because it drives what the next reader builds.

| Situation | What it is | What it is not |
|---|---|---|
| Bytes written, no bootstrap record followed | unreferenced dead space | corruption, data loss, a torn checkpoint |
| Flush failed mid-way | the popped changes are gone from memory; disk unchanged | damaged storage |
| Index left half-mutated by a failed rollback | an unusable **in-memory** catalog | an unusable **on-disk** one |
| A file deleted while the published record names it | **real damage**, no write needed | reclaimable dead space |
| A bootstrap record published from broken in-memory state | the real hazard on the write side | — |

Only the last two rows are real damage, and only the last justifies refusing *work*. If a design refuses work
to protect storage from a situation in any other row, it is buying nothing and its cost is real — the version
of this that shipped briefly on the `#1432` branch aborted `Catalog#terminateInternally`'s loop at its first
collection, so none was marked terminated, the collection map was never cleared, and the failure was swallowed
into a log.

## Checking

Before adding a guard, an abort or a refusal in the name of protecting stored data, answer:

1. **Which write would damage the on-disk catalog?** If the answer is not a bootstrap record, there is no
   durability hazard and the guard belongs somewhere else — or nowhere. The one exception is a **deletion**: see
   the companion invariant above, which no amount of reasoning about appends will get you to.
2. **Does the guard block publication, or does it block work upstream of publication?** Blocking upstream costs
   availability and buys nothing.
3. **Does anything the guard breaks need to keep working?** Shutdown must still close resources; a refusal to
   publish is never a licence to leak.

To find every place that publishes, and every message that claims stored data is damaged:

```shell
rg -n "writeCatalogBootstrap|recordBootstrap|storeHeader\(" --glob '*.java' evita_store evita_engine
rg -n "persisted state is incomplete|data (is|are) (lost|corrupt)" --glob '*.java'
```

And every place that removes one, which must be a confirm-phase action:

```shell
rg -n "retireDataFile|removeFileWhenNotUsed|\.delete\(\)" --glob '*.java' evita_store
```

Every hit in the second query must describe a bootstrap record published from untrustworthy state. Anything else
is a message that will teach its next reader the wrong model — which is exactly how this rule came to be written.
