# Removal plan — two test-only flags leaked into production code

Removes two flags that leaked test/rollout concerns into production code:

1. `io.evitadb.api.configuration.ServerOptions#directExecutor` — a test-only executor
   switch that, because `ServerOptions` is a public YAML-bound record, is exposed as a
   user-settable `server.directExecutor` config key.
2. `io.evitadb.core.transaction.memory.TransactionalLayerMaintainer#PRE_COMMIT_DIRTY_LEAF_VALIDATION`
   — a B+ tree rollout kill switch (`evita.bPlusTree.preCommitValidation`) that is no longer needed
   now that the tree implementation is stable.

Decision (owner): clean removal, **no deprecation** — this is entirely new code.

---

## Part A — Remove `ServerOptions.directExecutor`

### The seam

The value cannot simply disappear: the engine genuinely needs to choose "immediate vs real
executor", and it cannot derive that itself — `EvitaConfiguration` (engine layer) carries no
API-enablement info; `ApiOptions` lives one layer up in `evita_server`. So the value moves from a
**public YAML-bound config field** to a **constructor-time decision on `Evita`**.

The `DevelopmentConstants.isTestRun()` default is preserved, but in the engine constructor rather
than in the public config record. `DevelopmentConstants` already lives in `evita_api` and is used
pervasively by the engine, so this introduces no new leak; the leak being removed is specifically
the user-settable `server.directExecutor` YAML key.

Only ~3 sites ever override to `false` (force real pools); the other ~133 `new Evita(...)` sites
rely on the default and do not change.

### File-by-file

1. **`evita_api/.../configuration/ServerOptions.java`**
   - Remove the `directExecutor` record component.
   - Remove the `DEFAULT_DIRECT_EXECUTOR` constant and the `DevelopmentConstants` import.
   - Remove the builder field, setter, copy-constructor assignment and `build()` argument.
   - Remove the `@param directExecutor` JavaDoc.
   - Record drops 11 -> 10 components (canonical-constructor arity change = the accepted BWC break).

2. **`evita_engine/.../core/Evita.java`**
   - Add a **public** 5-arg constructor
     `Evita(config, scheduleCatalogLoading, onCreate, onTerminate, boolean directExecutor)` holding
     the real body.
   - Existing 1/2/3/4-arg constructors delegate to it, passing `DevelopmentConstants.isTestRun()`.
   - Replace both `configuration.server().directExecutor()` reads (currently lines ~372 and ~379)
     with the new parameter.
   - Add the `DevelopmentConstants` import.
   - Net effect: current behavior preserved for all embedded callers.

3. **`evita_server/.../server/EvitaServer.java`** (line ~649)
   - `new Evita(config, false)` -> `new Evita(config, false, null, null, false)`.
   - EvitaServer is always networked, so it forces real pools. This is what lets `EvitaServerTest`
     drop its property (below), and it makes test-run EvitaServer behavior match production
     (previously it did not — in a test JVM it defaulted to the direct executor).

4. **`evita_test/evita_test_support/.../extension/EvitaParameterResolver.java`** (lines ~324-332)
   - Delete the `if (useRealThreadPools) { serverOptionsBuilder.directExecutor(false); }` block.
   - Change `new Evita(config)` -> `new Evita(config, true, null, null, !useRealThreadPools)`.
   - In the resolver `isTestRun()` is always true, so `!useRealThreadPools` reproduces today's logic
     exactly. Keep the explanatory comment, reworded.

5. **`evita_test/evita_functional_tests/.../server/EvitaServerTest.java`** (line ~1103)
   - Delete `property("server.directExecutor", "false")` (now redundant); fix the trailing comma.

6. **`evita_test/evita_functional_tests/.../core/EvitaTest.java`**
   (`reinstantiateEvitaWithEnabledAsynchronousExecutors`, lines ~5362-5383)
   - Drop the trailing `false` from the `new ServerOptions(...)` copy (10 args now).
   - Pass the async choice through the engine: `new Evita(config, true, null, null, false)`.

7. **`evita_test/evita_functional_tests/.../configuration/ServerOptionsTest.java`** (lines ~129, ~152)
   - Each `new ServerOptions(..., false, false, false)` -> `..., false, false` (drop the
     `directExecutor` arg). These assert only null-handling; there is no `directExecutor` assertion.

### Unaffected (verified)

- `EvitaConfiguration.java` uses the **no-arg** `new ServerOptions()` — unaffected.
- `ExternalApiServer`'s `new Evita(config, false)` appears only in a JavaDoc comment — unaffected.
- No YAML default files or documentation reference `directExecutor`.

---

## Part B — Remove `PRE_COMMIT_DIRTY_LEAF_VALIDATION` kill switch

The flag defaults to `true`, so removing it **changes no normal-run behavior** — it only deletes the
escape hatch that could turn the Tier B pre-WAL validation off. Tier C (post-replay merge) was never
gated by it.

1. **`evita_engine/.../core/transaction/memory/TransactionalLayerMaintainer.java`**
   - Delete the `PRE_COMMIT_DIRTY_LEAF_VALIDATION` field (and its JavaDoc), currently lines ~83-90.
   - Change the guard at line ~159 from
     `if (!PRE_COMMIT_DIRTY_LEAF_VALIDATION || this.dirtyLeafScopes == null)` to
     `if (this.dirtyLeafScopes == null)`.
   - Reword the two JavaDoc blocks that mention the kill switch (the `dirtyLeafScopes` field doc
     at ~104 and `validatePreCommitDirtyLeafScopes()` at ~151) to state Tier B now runs
     unconditionally.

2. **`evita_test/evita_functional_tests/.../index/bPlusTree/TierBKillSwitchTest.java`**
   - **Delete the whole file.** It is `@EnabledIfSystemProperty(... matches="false")` and exists
     only to prove the switch can gate Tier B off. Its own JavaDoc notes that the switch-*on*
     behavior is already pinned by
     `TransactionalLongBPlusTreeTest.DirtyLeafScopeValidationTest#shouldRejectCorruptedScopeInPreCommitPass`,
     which stays valid. No retained coverage is lost.

---

## Verification

Compile `evita_api -> evita_engine -> evita_server -> evita_external_api -> evita_test_support ->
evita_functional_tests`, then run:

- `ServerOptionsTest`
- `EvitaServerTest`
- `EvitaTransactionalFunctionalTest`
- `TransactionalLongBPlusTreeTest` (Tier B on-behavior still pinned)
- a `useRealThreadPools` consumer (the CSAE / CDC datasets) to confirm the resolver seam still
  forces real pools.

## Risks / notes

- **BWC:** `ServerOptions` canonical-constructor arity change (11 -> 10) is a source/binary break for
  external embedders — accepted (new code, no deprecation).
- **EvitaServer test behavior:** any EvitaServer-based test that did not set the property previously
  ran on the direct executor in test runs; it now runs on real pools (production-like). More correct,
  but the reason to run `EvitaServerTest`.
- **Part B is behavior-neutral by default** — only the disable path is removed.
