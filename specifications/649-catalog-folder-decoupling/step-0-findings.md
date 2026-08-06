# Step 0 — answers to the three open items

Read-only verification pass. All three items from README §5.7 are now answered; two of them
change what later steps must do.

---

## 1. Does `Catalog#terminate()` drain in-flight queries or yank them?

**`terminate()` yanks — but it is never reached with a query in flight, because the drain happens
one layer up.**

* `Catalog#terminate()` (`Catalog.java:1434`) does `terminateInternally()` then
  `persistenceService.close()`. `terminateInternally()` (`:2520`) closes the transaction manager,
  flushes/terminates every entity collection and clears the collection map. Nothing consults
  readers. On its own this is a yank.
* The drain is in `SessionRegistry#closeAllActiveSessionsAndSuspend` (`SessionRegistry.java:179`),
  which is always called *before* `terminate()` in the replace path. It closes each session through
  `proxySession.executeWhenMethodIsNotRunning(...)`, and `EvitaSessionProxy:616-626` defers that
  lambda until `insideInvocation == 0` — i.e. **until the running business method returns**.

### Correction this forces on README §3.7

The table's "in-flight query … force-closed" cells overstate today's behaviour. An in-flight query
already runs to completion today; what is force-closed is the **session**, once the query returns.
The improvement this work delivers is therefore narrower and more precise than the table claims:

| reader | actually today | target |
|---|---|---|
| in-flight query on the source catalog | **completes**, session then closed | completes, **session survives** |
| open session on the source catalog | force-closed, client must reopen | **survives** |
| new session at the commit instant | POSTPONE for the whole folder dance | POSTPONE for one WAL append |
| in-flight query on the replaced catalog | **completes**, session then rejected | unchanged |
| open session on the replaced catalog | REJECT | REJECT — unavoidable |

The claim to make in the ADR is *session survival* and *postpone-window collapse*, not
"queries stop being aborted" — they are not aborted today.

### A hazard this uncovered, which matters for the §3.7 tests

`closeAllActiveSessionsAndSuspend` drains in a `do/while` bounded at **5 seconds**
(`SessionRegistry.java:227`) and then asserts the registry is empty (`:229`). A session whose
business method runs longer than 5 s makes the suspend throw `GenericEvitaInternalError` rather
than wait. So:

* the concurrent-reader tests must not use queries that can exceed 5 s under CI load, or they will
  fail for a reason unrelated to what they assert;
* this is an independent argument for the postpone window being short — the shorter the operation,
  the less likely a reader is mid-method when the drain starts.

---

## 2. Does any test assert `catalogId` stability across a backup/restore round trip?

**No.** Three sites reference a catalog id in tests, none of them asserts stability across a copy:

| site | what it does |
|---|---|
| `EvitaBackwardCompatibilityTest:214-217` | returns `session.getCatalogId()` and asserts only `assertNotNull` |
| `SystemRestEndpointFunctionalTest:322` | compares the API response against the **live** catalog's own id |
| `SystemGraphQLQueriesFunctionalTest:212` | same, GraphQL surface |
| `DefaultCatalogPersistenceServiceTest:169` | mints its own `UUID.randomUUID()` as fixture input |

Minting a fresh id on restore and duplicate (README §3.1.1, decision 12) therefore breaks no
existing test. Step 8 needs to *add* the assertions, not repair any.

---

## 3. Is anything in `doReplaceCatalogInternal`'s completion phase non-idempotent?

**Yes — three things, and the replay contract forbids two of them outright.** Decision 11
(`replayCompletionState` in scope) survives, but only because replay must re-derive the state
rather than re-run the phase.

`EngineMutationOperator#replayCompletionState`'s contract (`:117-157`) says implementations **must
not** open or close catalog instances or emit CDC/metric events, and **may** apply only idempotent
in-memory toggles. Against that contract:

**`completionEngineStateUpdater` → `withCatalog` / `withoutCatalog`.**
A pure state transform. This is the only part replay reproduces.

**`notifyCatalogPresentInLiveView()` (`:182`) — forbidden.**
It ends in `changeObserver.notifyCatalogPresentInLiveView` (`TransactionManager.java:932`), which
emits CDC. It also asserts strict version ordering (`:898-908`), which a replayed catalog would
not satisfy.

**Session-registry swap (`:184-204`) — safe but pointless.**
No sessions exist at replay time.

**`catalogToBeReplaced.terminate()` (`:208`) — non-idempotent *and* forbidden.**
`terminate()` opens with `Assert.isPremiseValid(!isTerminated(), "Catalog is already terminated!")`,
so a second call throws outright; and closing an instance is explicitly outside the replay contract.

### What this means for step 7

`replayCompletionState` must **not** be a re-run of the completion lambda. It must be a pure
re-derivation, in the shape `ModifyCatalogSchemaMutationOperator:134-146` already uses:

* rebuild the mapping — `catalogs[newName] = folder of oldName`, drop `catalogs[oldName]`, and for
  a replace also append the retired folder to the tombstone list;
* load the catalog instance from the folder the engine state now points at (permitted: "may read
  already-persisted state … from a folder known to exist");
* do **not** call `notifyCatalogPresentInLiveView`, do **not** call `terminate`, do **not** touch
  session registries.

This is only achievable because the pointer-only design leaves the folder untouched before the
commit — at replay time the folder is exactly as it was, and the engine state alone says which
name owns it. It is the concrete reason decision 11 becomes possible; worth stating in the ADR in
those terms rather than the vaguer "a pointer swap is idempotent".

### One consequence to carry into step 7

The old `B` instance still has to be terminated and its folder deleted on the *normal* path. Since
replay cannot do it, termination must be driven by the **tombstone drain** on the recovery path,
not by the completion lambda — the drain already has to handle "folder retired but not yet gone",
and an instance still open against a retired folder is the same condition. That unifies the two
paths instead of special-casing replay.
