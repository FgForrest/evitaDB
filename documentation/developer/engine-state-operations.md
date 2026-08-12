# Engine-level operations: the completion log and folder identity

This document describes the protocol every **engine-level operation** follows — creating, dropping, renaming,
replacing, duplicating and restoring a catalog, taking it live, changing its state, upgrading its format. These
operations do not go through the catalog transaction path; they mutate the *engine state*, the table that says
which catalogs exist and where their data lives.

The protocol is deliberate and load-bearing: it is what makes an engine crash cost you *nothing but the operation
in flight*, and it is what stops a filesystem error from turning into a database that cannot start. It is
implemented by
<SourceClass>evita_engine/src/main/java/io/evitadb/core/transaction/engine/EngineTransactionManager.java</SourceClass>
and the operators in
<SourceClass>evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators</SourceClass>.

**Read this before adding or changing an engine mutation.** The rules below are not stylistic; breaking one
produces a failure that surfaces days later, at someone else's boot.

## 1. The engine log records what happened, not what is about to

There are two classic ways to build a write-ahead log:

- **An intent log** writes down what it is *going to* do, then does it. After a crash it replays the log to finish
  the job.
- **A completion log** does the work first and writes down what it *did*. After a crash there is nothing to
  finish — the log only contains operations that already succeeded.

**evitaDB's engine log is a completion log.** An engine mutation runs its filesystem work first, and only if that
work succeeded is the mutation appended to the log and the engine state advanced.

The reason is the nature of the work. Engine mutations do coarse, failable things to a non-transactional medium:
copy a catalog folder that may be 50 GB, unpack a restore archive, rewrite a header. With an intent log, an
operation that fails *deterministically* — out of disk space, permission denied, a directory the OS refuses to
remove — would be replayed at every boot, fail the same way every time, and the database would never start again.
Escaping that needs abort records and compensating undo logic layered on top of redo.

A completion log dissolves the problem instead of managing it: **work that fails is never in history, so history
can never demand the impossible.** A second benefit falls out for free — every record in the log corresponds to
something that really took effect, which is exactly what a CDC or replication consumer needs.

The commit itself is `appendWalAndStoreState` in
<SourceClass>evita_store/evita_store_server/src/main/java/io/evitadb/store/engine/DefaultEnginePersistenceService.java</SourceClass>
— it appends the log record and rewrites the engine bootstrap file as one critical section, WAL first.

## 2. Work out of place, publish with one small write

The shape every engine operation takes:

1. **Build out of place.** Whatever the operation needs — a new folder, a rewritten header, an unpacked archive —
   is produced *beside* the live data, never on top of it. Nothing the engine currently serves is modified.
2. **Publish with one commit.** A single engine-state commit repoints names at folders. Before it, the old world
   is intact and the operation simply did not happen; after it, the new world is durable. There is no third state.
3. **Clean up afterwards, and allow the cleanup to fail.** Anything that must be *destroyed* happens after the
   commit, guarded by a durable instruction so a failure can be retried later.

This is shadow paging — the same shape as a copy-on-write filesystem, or the everyday write-to-tmp-then-rename.
It is why a rename or a replace has no rollback logic: there is nothing to roll back, because nothing was
overwritten.

## 3. Deletion is deferred, never load-bearing

Step 3 above is where most designs go wrong, so it has its own mechanism. An operation that supersedes a folder
does **not** delete it and then commit. It commits a **tombstone** — a durable standing order to delete one
specific folder — *in the same commit that repoints the name*. Only then is the delete attempted.

If the delete fails — an open handle on Windows, a transient I/O error, a read-only mount — nothing bad happens.
The operation has already succeeded, and it reports success. The tombstone survives, and the next boot's drain
retries the deletion. A folder confirmed gone is noted, and the next engine mutation prunes its tombstone from
persisted state.

The rule this encodes: **a failure to destroy something must never fail the operation that superseded it, and
must never be forgotten either.**

## 4. Folder names, and why they look like `products_7`

A catalog is *not* stored in a folder named after the catalog. It is stored in a folder whose name is an opaque
token — normally `<catalogName>_<generation>` — and the engine state holds the table that maps names to tokens:

```
products  →  products_1
temp      →  temp_3
```

Only the engine state knows which name lives where. The folder name is **never parsed and never trusted**; code
outside `evita_store_server` may not even resolve a token to a path.

**Why not just name the folder after the catalog?** Because catalog names move between folders and folder names
do not:

- Replace `products` with `temp`, and the catalog now called `products` lives in `temp_3`, while `products_1` is
  tombstoned and may still be on disk if the delete was refused. Create a new `products` in that window and a
  name-based scheme would want a folder that already exists and is under orders to be destroyed.
- Rename `products` to `archive`, and the folder is still `products_1`. Create a new `products` and, again, the
  obvious folder name is taken by someone else's live data.
- A failed allocation may leave a directory behind. If the retry could draw the same number it would collide with
  exactly the debris its predecessor produced.

So what is needed is a folder identity that is unique **independently of the catalog name**, and that is what the
generation counter provides. It is drawn from an engine-scoped sequence, **burned per attempt** — a number is
consumed whether or not the folder was successfully created — and a name's counter is only reclaimed once the
last tombstone naming it has drained, i.e. once nothing on disk can still be holding that number.

The `<catalogName>_` prefix carries **no meaning to the engine**. It exists so a human doing disaster recovery on
a bare storage directory can tell at a glance what they are looking at. Opaque UUIDs would work identically and
were rejected only for that reason.

**This is what makes section 1 sound.** A completion log is only safe if the debris of a crashed attempt is
*inert* — if crash residue could occupy a name that new work needs, a persistent filesystem error on stale data
would block future operations indefinitely, which is precisely the boot loop the completion log exists to
prevent. Generation-suffixed folders make residue harmless: an unreferenced folder is reported and never touched,
an undeletable one is retried at every boot drain and blocks nothing.

Boot classifies every directory it finds:

| Classification | Meaning | What the engine does |
|---|---|---|
| `REFERENCED` | the engine state binds a catalog to it | uses it; the binding wins over the folder's own claim |
| `PROVISIONAL` | allocation created it but the work never completed | removes it at the boot drain |
| `RETIRED` | a tombstone names it | retries the deletion |
| `FOREIGN` | bare name, unreferenced, holds catalog data | offers it for adoption; the **folder name** becomes the name |
| `UNCLAIMED` | shaped like an engine folder but nothing claims it | leaves it alone and warns |
| `JUNK` | no bootstrap file, unreferenced | leaves it alone and warns |

`REFERENCED` is decided first, and through the binding rather than the folder's name — so a folder the engine
already knows about is never re-interpreted from its contents.

## 5. The engine state is the authority; the folder is corrected to match

Each catalog folder also stores the name of the catalog inside it, in its header and schema. That copy is **not**
authoritative. When the two disagree — which happens if the engine dies after the folder's header was rewritten
but before the commit that would have repointed the name — the load path rewrites the folder's stored name to
match what the engine binds (`reconcileStoredCatalogIdentity`, logging *"Folder X stores catalog A but the engine
binds it to B — adapting the stored name"*).

The `.catalogname` label file is a **different thing, reconciled at the same point but on different terms.** It is
a note for a human reading a storage root with no server to ask; nothing in the engine ever reads it back, so it is
written after the commit and on a best-effort basis. That made a crash — or an ordinary I/O failure — leave it
naming the folder's previous occupant for good, which is a bad trade for an artefact whose entire purpose is
disaster recovery. The load path now converges it too, in `reconcileStoredCatalogIdentity`, reading before it
writes so an already-correct label is untouched.

Two things about it are deliberately *unlike* the header reconcile. It is **outside** the `name differs` guard:
the header is rewritten during a rename's work phase and so is already correct after a crash in the commit window,
which is exactly when the label is not — testing the header's disagreement would skip the case the repair exists
for. And it converges **on load**, so a catalog nobody opens keeps a stale label until something opens it. Do not
add a call that "fixes" the label inside an operation to close that gap: nothing after the commit may report a
failure, so a repair placed there could only log, and a repair that cannot report is not a repair.

The direction is deliberate. Only the engine state knows the *whole* mapping; a folder knows only what it
believes about itself, and beliefs collide — a copied folder believes it is the catalog it was copied from. The
one exception is adoption of a `FOREIGN` folder, where nothing references the folder and its name is all there
is to go on.

## 6. Rules for writing a new engine mutation

1. **Do the failable work before the commit, out of place.** If your operation must modify something the engine
   is currently serving *before* it commits, this protocol does not fit it — stop and redesign, do not work
   around it.
2. **Never derive a folder path from a catalog name.** Ask the engine state for the binding. In tests too: a test
   that resolves `storage/<catalogName>` reads a directory that does not exist.
3. **Put everything the commit must record into the single state update** — the binding, the unbinding, and the
   tombstone for anything superseded. A crash on either side of it must leave one of two consistent worlds.
4. **Nothing after the commit may throw.** By then the operation has succeeded and been made durable; reporting a
   failure would be a lie about what happened. Log it and carry on. This applies to every post-commit step, not
   just deletion.
5. **Every post-commit side effect must be retryable from durable state.** Deletion has the tombstone. If you add
   a different kind of post-commit work, it needs its own durable record of the obligation and something that
   drains it — otherwise the log says the operation completed and the residue says otherwise, with nothing to
   reconcile them.
6. **Implement `replayCompletionState`.** After a crash between the log append and the state write, the engine
   re-derives the state your mutation would have produced. An operator that leaves this at its `Optional.empty()`
   default **wedges the whole engine** — every mutation is refused until a human intervenes. Replay must be
   idempotent: it may run when the work phase already completed in full, and it may run twice.
7. **Suspend the sessions of every catalog you touch** — and remember that a catalog nobody has opened a session
   on since boot has *no registry to suspend*, so a name-keyed `ifPresent` quietly suspends nothing.
8. **If the catalog survives under a different name, its session registry moves with it.** A rename and a replace
   both leave a live catalog under the target name; the registry is re-registered there with a supplier resolving
   the new name, and resumed. It is not rebuilt from scratch, because it carries state that belongs to the
   catalog rather than to the name — active sessions, the FIFO queue, and the consumed-version census that
   backups pin against. A registry left behind under a name that no longer exists is a bug: that name will answer
   "busy" forever instead of "not found".

## 7. Invariants

- **E1 — The log never lies.** Every record in the engine log corresponds to an operation that took effect.
  Failed work is never recorded.
- **E2 — One commit decides.** An engine operation has exactly two outcomes on disk: fully before, fully after.
- **E3 — The engine state is the sole authority for `name → folder`.** No component derives, parses or infers a
  folder from a catalog name.
- **E4 — A generation is never reused while anything could still be holding it.** Counters are burned per
  attempt and reclaimed only at the tombstone drain.
- **E5 — Crash residue is inert.** Anything left behind by an interrupted operation is unreferenced, and an
  unreferenced folder is never destroyed without positive evidence of ownership.
- **E6 — Destruction never fails an operation.** A refused deletion leaves a durable order that the next boot
  retries.
- **E7 — Nothing after the commit reports failure.** Post-commit steps log; they do not propagate.
- **E8 — A surviving catalog keeps its session registry.** When a catalog continues to exist under a new name,
  its registry moves to that name. A name that stops naming a catalog holds no registry afterwards, so it answers
  "not found" rather than "busy".
- **E9 — A boot that recovers must leave the storage in a shape the next boot accepts.** Recovery is not finished
  when the engine is up; it is finished when a restart with *nothing committed in between* also comes up. The
  version of the log and the version in the bootstrap have to agree at shutdown, and a rebuilt state carries no
  WAL position — an operator rebuilds *state*, not log offsets — so nothing downstream of a replay may take a WAL
  position from it. This is not hypothetical: truncating the log against a replayed snapshot's stale reference cut
  away the record the replay had just recovered, and the following boot refused to start.
- **E10 — A replay's *standing orders* are a boot's to carry out, even though its log positions are worthless.**
  The two must not be confused: a rebuilt state's WAL positions are stale and may never be used, but what that
  state durably *records* is as binding as anything else in it. A tombstone is the case that matters. The boot
  drain runs a layer below the replay and against the state the crash left, so a tombstone the replay commits
  arrives after the drain has already been and gone. A second pass, gated on a replay having succeeded and
  narrowed to tombstoned folders, runs after the reconciled bootstrap is durable — never before, since a folder
  deleted ahead of the state that unbinds it is data the engine still points at. The drain's own ordering stays as
  it is: running it against an already-healed state would cost the ability to diagnose a drifted boot from the disk
  it left behind.

## References

- Commit protocol: <SourceClass>evita_store/evita_store_server/src/main/java/io/evitadb/store/engine/DefaultEnginePersistenceService.java</SourceClass>
- Mutation dispatch, forward replay, the wedge: <SourceClass>evita_engine/src/main/java/io/evitadb/core/transaction/engine/EngineTransactionManager.java</SourceClass>
- Folder identity and tombstones: <SourceClass>evita_engine/src/main/java/io/evitadb/core/engine/CatalogFolderContext.java</SourceClass>, <SourceClass>evita_engine/src/main/java/io/evitadb/spi/store/engine/model/CatalogFolderId.java</SourceClass>
- Allocation and boot classification: <SourceClass>evita_store/evita_store_server/src/main/java/io/evitadb/store/engine/CatalogFolderAllocator.java</SourceClass>, <SourceClass>evita_store/evita_store_server/src/main/java/io/evitadb/store/engine/CatalogFolderClassifier.java</SourceClass>
- Worked example of the whole protocol: <SourceClass>evita_engine/src/main/java/io/evitadb/core/transaction/engine/operators/ModifyCatalogSchemaNameMutationOperator.java</SourceClass>
- Decision record: [Bind catalogs to opaque folder tokens](../adr/2026-08-06-catalog-folder-decoupling.md)
