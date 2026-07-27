# Tier B/C implementation review — against TIER_BC_ADVISORY.md

Review of the implementation reported in `RESULTS.md` (§ Tier B / Tier C), 2026-07-15. Verdict up front:
**the implementation is faithful to the advisory — no correctness bug found.** One robustness finding
(the Tier B failure path is not exception-proof the way `rollback()` is) and one coverage gap (that same
path has no direct test) should be addressed; four deviations from the advisory's letter were examined
and are APPROVED as sound.

## Verified conformance

- **Registry** (`TransactionalLayerMaintainer.dirtyLeafScopes`): identity-keyed on both axes
  (`IdentityHashMap` tree → identity `Set<Object>`), lazily built, populated UNCONDITIONALLY (kill switch
  gates only `validatePreCommitDirtyLeafScopes()`; Tier C reads the registry regardless). Warm-up pays a
  `ThreadLocal` read + null check and nothing else.
- **Registration seams — complete on all three trees.** Long: `insert`, `upsert` (insert branch),
  `delete`, split (both halves), consolidate hook. Element: `insert`, `delete`, split (both halves),
  consolidate hook. Bucket (standalone, inlined helper with identical semantics): `addRecord` ×2,
  `removeRecord`, `addLongRecord`, `removeLongRecord`, split (both halves), its own consolidate sites.
  The shared `consolidate` registers BOTH leaves on a steal and the SURVIVING (range-widened) leaf on a
  merge — the merged-away node needs no registration, and any earlier registration of it is harmless
  under the key-source-only rule (its stale key relocates to the survivor, which is then validated).
- **Key-source-only relocation** implemented exactly as specified in all three
  `validateDirtyLeafScope(...)` bodies: skip empty key sources (`getPeek() < 0`), probe by the source's
  first key, `createCursor` descent (read path — resolves diff layers live for Tier B, plain reads on the
  merged tree for Tier C), skip an empty landing leaf, then re-derive BOTH half-invariants on the landed
  leaf via the Tier A machinery (`assertTailBoundary` against the successor fence, `assertHeadBoundary`
  against the predecessor's actual last key). The boundary asserts were checked for write-path calls —
  none (no layer creation, no `markDirty`, no writable acquisition), so the pass is safe under
  `allowTransactionalLayerCreation = false`.
- **Tier B hook**: first statement of `TransactionWalFinalizer.commit(...)`, before
  `closeRegisteredCloseables()` and `commitWal` — the shared WAL verifiably never receives a rejected
  transaction. Structurally absent from the trunk path (the hook lives only in the WAL finalizer), so
  "no Tier B on trunk replay" holds by construction.
- **Tier C**: post-build, last step of each tree's `createCopyWithMergedTransactionalMemory`, consuming
  `getRegisteredDirtyLeaves(this)` (registration keyed the OLD tree instance; validation runs on the
  MERGED instance — correct on both sides), guarded by `!dirtyScope.isEmpty()`. Tree-level message is
  neutral; `TransactionTrunkFinalizer.commitCatalogChanges` catches `BPlusTreeCorruptedException` around
  the merge only (the flush at the top of the method precedes it) and wraps via
  `wrapPostReplayBoundaryCorruption`, whose wording names WAL durability, the possibly-already-flushed
  data files, and restore/rebuild remediation. All advisory requirements met.
- **Tests are honest**: the reject tests corrupt the write LAYER upstream and let the real pre-commit
  pass / real commit merge throw; the Tier C reject runs the genuine
  `createCopyWithMergedTransactionalMemory`; the savepoint test performs the advisory's exact
  register-inside-savepoint → rollback → no-false-positive sequence; the split-hygiene assertion
  (`size() >= 2` on an identity set) is self-checking against double-registration collapse.

## Finding 1 (fix requested) — Tier B failure path is not exception-proof

`TransactionWalFinalizer.commit`'s catch block runs sequentially:

```java
closeRegisteredCloseables();
if (this.walPersistenceService != null) { this.walPersistenceService.close(); ... }
this.commitProgress.completeExceptionally(new RollbackException(...));
return;
```

`rollback(...)` — the model the advisory said to mirror — is shaped differently:
`closeRegisteredCloseables()` sits in a `try`, and the WAL close + `completeExceptionally` sit in the
`finally`, so a throwing closeable can never prevent the commit future from completing. In the new catch
block, if `closeRegisteredCloseables()` throws (an `OffsetIndex`/off-heap close failure), the raw
throwable escapes `commit(...)` — reintroducing exactly the two hazards the mirror-rollback prescription
exists to prevent: a permanently hanging `commitProgress` and a leaked isolated WAL. Unlikely path, but
it is the one code path whose entire purpose is behaving well when things go wrong.

**Fix**: adopt rollback's shape inside the catch —

```java
} catch (Throwable ex) {
    try {
        closeRegisteredCloseables();
    } finally {
        if (this.walPersistenceService != null) {
            this.walPersistenceService.close();
            this.walPersistenceService = null;
        }
        this.commitProgress.completeExceptionally(new RollbackException(..., ex));
    }
    return;
}
```

(Keeping `catch (Throwable)` broad is correct here and deliberate — an unexpected error inside the
validation pass must also produce a clean rejection rather than a hang; the cause is chained, so it
stays loud.)

## Finding 2 (test requested) — the Tier B failure path has zero direct coverage

The advisory's test 1 asked that a Tier B rejection be pinned end to end: isolated WAL released, commit
future completed exceptionally, no raw throw. The implemented reject tests drive
`validatePreCommitDirtyLeafScopes()` (directly or via the isolated-transaction commit), which proves the
DETECTION pipeline — but nothing exercises the `TransactionWalFinalizer` catch block itself, which is
precisely where Finding 1 lives. No production corruption-injection hook is needed: construct a real
`TransactionalLayerMaintainer` carrying a corrupt registered scope (the same fixture the unit reject
tests already build), a `TransactionWalFinalizer` whose `walPersistenceServiceFactory` yielded a
recording stub, and a registered closeable; invoke `commit(maintainer)` and assert (a) it returns
normally (no throw), (b) the stub's `close()` was called, (c) the closeable was closed, (d)
`commitProgress` completed exceptionally with `RollbackException` chaining
`BPlusTreeCorruptedException`. A second case with a throwing closeable pins the Finding-1 fix.

## Examined deviations — APPROVED, no action

1. **`upsert`'s existing-value branch does not register** (documented in code). Sound: replacing a value
   at an existing key leaves the leaf's key set identical in both the layered and reverted states, so no
   layer-lifecycle bug involving only this op can move a boundary; any later key-mutating touch of the
   same leaf registers it. Same reasoning approves `insert`'s value-overwrite (returns-false) path.
2. **Merged-away leaves are not registered** in `consolidate` — the survivor is, and a dead node's
   earlier registration degrades gracefully to validating the survivor (key-source-only rule working as
   designed).
3. **Bucket inlines its own `registerDirtyLeafInScope`** rather than reusing the base static helper — a
   type-system necessity (standalone tree, different nested leaf type); semantics verified identical.
4. **Kill-switch test is fork-gated** (`@EnabledIfSystemProperty` + dedicated property-set run) because
   the switch is a `static final` — the advisory anticipated exactly this constraint; the fork run
   proving the property reached the JVM (test RAN, not skipped) closes the loop.

## Scope notes (no action)

- The trunk path's propagation of the wrapped poison pill to the commit futures rides the pre-existing
  trunk failure machinery (same route as any merge-time premise failure) — relied upon by the advisory,
  not re-verified here.
- `getRegisteredDirtyLeaves` returns the live internal set; current consumers only iterate. Acceptable;
  do not add defensive copying on this hot-adjacent path.
