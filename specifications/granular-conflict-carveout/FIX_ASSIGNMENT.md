# Fix: per-item GRANULAR must be a real carve-out against coarse writers

**Scope:** evitaDB conflict-resolution (#503 feature). Behavioral fix only — no schema API changes,
no persistence format changes, no external API (gRPC/GraphQL/REST) changes.

**Working directory:** `/www/oss/evita/evitaDB-carveout-wt` (a dedicated git worktree, branch
`503-granular-conflict-carveout`). Do ALL work here with absolute paths. NEVER touch
`/www/oss/evita/evitaDB-dev`.

---

## 1. The bug

A schema item declared `ConflictResolutionOverride.GRANULAR` (or covered by a dimension in the
entity's granularity set) is documented to isolate concurrent writes touching only that item from
writes touching other parts of the same entity. It does not deliver that when the other writer runs
under coarse `ConflictPolicy.ENTITY` (the default):

- The coarse writer's transaction contains at least one non-granular mutation, which produces no
  conflict key, so `EntityMutation.getConflictKeyStream` (`evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java:143-156`)
  adds the monolithic `EntityConflictKey(type, pk)` as a catch-all.
- Every granular key's `parentConflictKey()` chain unconditionally passes through
  `EntityConflictKey(type, pk)` (e.g. `AssociatedDataConflictKey.java:49-51`).
- `IncomingConflictScope` (`evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/conflict/IncomingConflictScope.java`)
  matches by containment along that chain, so the coarse writer's entity key always matches the
  granular writer's key → false conflict on disjoint items.

Concrete repro: `Product` coarse `ENTITY`, associated data `feed-heureka` per-item `GRANULAR`.
Txn A upserts attribute `name` (non-granular → emits `EntityConflictKey(Product,100)`). Txn B
upserts `feed-heureka` (emits `AssociatedDataConflictKey(Product,100,"feed-heureka")`). They
falsely conflict in both commit orderings. The same flaw applies to the granularity-set flavor: a
transaction mixing one granular item with one non-granular item emits granular key + entity
catch-all, and the catch-all falsely conflicts with every granular sibling writer.

## 2. The design

Root cause: `EntityConflictKey` does two jobs — (a) "I changed the entity's existence/identity"
(remove, forced create, scope change; must conflict with everything) and (b) "I touched some
ordinary non-granular fields" (the policy-coarseness catch-all; must conflict only with the shared
surface). Split them:

**New key: `EntityResidualConflictKey(String entityType, int entityPrimaryKey)`** — a record in
`io.evitadb.api.requestResponse.mutation.conflict`, meaning "this transaction touched the entity's
shared (non-carved-out) surface".

- `parentConflictKey()` returns `EntityConflictKey(entityType, entityPrimaryKey)` — so whole-entity
  operations still contain it.
- `conflictScope()` returns `ConflictScope.ENTITY`.
- NO other payload. Deliberately no schema-derived data (no set of excluded items): matching is by
  hash equality, and any schema-derived payload would break residual-vs-residual matching across
  transactions. See §3 for why no payload is needed.
- JavaDoc must explain the residual/full split and the containment semantics (sibling of granular
  keys, child of the full entity key), in the style of the existing key records.

**Change 1 — `EntityMutation.getConflictKeyStream` fallback split.** In the fallback block
(currently lines 143-156), under `coarsePolicy == ENTITY` with non-null pk:

- `expects == MUST_NOT_EXIST` (forced creation) → emit full `EntityConflictKey` (as today). The full
  key alone suffices even when `atLeastOneKeyMissing` is also true — it bidirectionally contains the
  residual, so do not emit both.
- otherwise (`atLeastOneKeyMissing` only) → emit `EntityResidualConflictKey` instead.

`COLLECTION` and `NONE` policy branches and the null-pk branch stay exactly as they are. Update the
method's explanatory comment accordingly.

**Change 2 — `SetEntityScopeMutation.collectConflictKeys`** (`evita_api/.../data/mutation/scope/SetEntityScopeMutation.java:111-115`).
Today it returns `Stream.empty()` and free-rides on the catch-all — after Change 1 that would demote
a scope change (archive/restore) to the residual key, but a scope change is a whole-entity operation
that must conflict with carved-out items too. Make it emit keys explicitly, mirroring
`EntityRemoveMutation.collectConflictKeys` (`EntityRemoveMutation.java:184-197`): under `ENTITY`
policy emit full `EntityConflictKey(context.getEntityType(), context.getEntityPrimaryKey())`, under
`COLLECTION` emit `CollectionConflictKey`, otherwise empty. If `context.getEntityPrimaryKey()` is
null under `ENTITY` policy, return empty (the entity is brand new; matches today's effective
behavior). Note this mutation is a `LocalMutation`, so entity type/pk come from the context, not
fields.

**Change 3 — range-constrained delta keys route through the residual when not carved out.**
`ApplyDeltaAttributeMutation.collectConflictKeys` (`evita_api/.../data/mutation/attribute/ApplyDeltaAttributeMutation.java:189-207`)
emits `AttributeDeltaConflictKey` even for a non-granular attribute when
`requiredRangeAfterApplication != null` (deliberate: range invariants are not opt-out). Today such
a delta collides with a coarse absolute writer via the `... → EntityConflictKey` chain; after
Change 1 the coarse writer holds only the residual, which is NOT on that chain — a silent
regression (absolute-set-vs-delta race). Fix:

- Add a boolean record component `sharedSurface` to `AttributeDeltaConflictKey` (append as last
  component). Its `parentConflictKey()` becomes:
  - `entityPrimaryKey == null` → `CollectionConflictKey` (unchanged);
  - `sharedSurface == true` → `EntityResidualConflictKey(entityType, entityPrimaryKey)`;
  - `sharedSurface == false` → `AttributeConflictKey(...)` (unchanged current behavior).
- In `ApplyDeltaAttributeMutation.collectConflictKeys`, compute
  `shouldEmit = context.shouldEmitEntityAttributeKey(name)` once; emit the key when
  `requiredRangeAfterApplication != null || shouldEmit`, passing `sharedSurface = !shouldEmit`.
- Apply the identical treatment to `ReferenceAttributeDeltaConflictKey` and its emission site in
  `ReferenceAttributeMutation.collectConflictKeys` (`sharedSurface == false` keeps the current
  `ReferenceAttributeConflictKey` parent; `sharedSurface == true` → residual; null pk →
  `CollectionConflictKey` unchanged), using `context.shouldEmitReferenceAttributeKey(...)`.
- `aggregationKey()` / `DeltaAggregationKey` must NOT include the new flag (deltas on the same
  attribute must keep aggregating together regardless of it). Verify this explicitly.

**No other changes.** In particular: `IncomingConflictScope` (the matcher) is NOT modified;
`EntityRemoveMutation` already emits the full key; `EntityRemoveMutationWithConflictKeys` (engine
wrapper) needs no change — verify by reading it; `ParentMutation`, `PriceMutation`,
`SetPriceInnerRecordHandlingMutation`, `AttributeMutation`, `AssociatedDataMutation`,
`ReferenceMutation` absolute-key emission stays as is; `TransactionManager` stays as is; no Kryo /
WAL serializer work (conflict keys are never persisted — `EntityRemoveMutationWithConflictKeysSerializer`
drops them by design).

## 3. Why the residual key needs no payload (read before coding)

An absolute granular key (`AttributeConflictKey`, `AssociatedDataConflictKey`, `PriceConflictKey`,
`ReferenceConflictKey`, `ReferenceAttributeConflictKey`, `HierarchyConflictKey`) is only ever
emitted when its item IS carved out — every `ConflictGenerationContext.shouldEmit*` gate requires
it. Non-carved items never get their own keys; they are represented BY the residual. So the
residual never needs to act as an ancestor of any granular key — the answer to "does the residual
contain this granular key" is always no, by construction. Its only containment relationships are
equality with itself and being contained by full-entity/collection/catalog keys. The single
exception to "non-carved items never emit keys" is the range-constrained delta, which is exactly
what Change 3 handles by baking the emit-time decision into the delta key's parent chain.

Schema-transition safety (no action needed, just don't "fix" it): schema mutations emit
`CollectionConflictKey` (`ModifyEntitySchemaMutation.java:174`), which contains every key of the
collection, and the ring buffer only examines keys committed after the incoming transaction's
snapshot — so two data transactions are never containment-compared across a schema change of their
collection, and emit-time carve-out decisions are consistent within every examined window.

## 4. Invariants that MUST hold (verify each with a test)

1. Two writers of the same granular item still conflict (equal granular keys).
2. Two coarse writers touching different non-granular items still conflict (equal residual keys).
3. Entity removal, forced creation (`MUST_NOT_EXIST`) and scope change conflict with EVERYTHING on
   the entity, including carved-out items (full key is on every chain, and is the residual's
   parent).
4. Write path and recompute path agree (both run the same `collectConflictKeys` code through a
   schema-aware `ConflictGenerationContext`; nothing schema-derived is baked into any key except
   the delta `sharedSurface` flag, which both paths compute identically).
5. `NONE` still emits nothing; `CATALOG`/`COLLECTION` coarser scopes still contain everything.
6. Commutative deltas: same-attribute deltas still commute (the commutative probe starts at the
   parent, skipping self); a shared-surface delta conflicts with a coarse absolute writer
   (residual on its chain); a carved-out delta does NOT conflict with a coarse absolute writer;
   a delta still conflicts with an absolute writer of the same carved-out attribute.

## 5. Test plan (TDD: write these first, watch the carve-out rows fail, then implement)

Existing homes — extend these, follow their local style and helpers:

- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/mutation/conflict/IncomingConflictScopeTest.java`
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/requestResponse/data/mutation/DataMutationConflictKeyEmissionTest.java`
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/core/transaction/TransactionManagerConflictWindowTest.java`
- `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/EvitaTransactionalFunctionalTest.java` (end-to-end rows; check how existing granular-conflict tests there set up schemas and concurrent sessions)

Consult `.claude/rules/testing.md` for tags/profiles before running anything.

### 5a. Emission tests (`DataMutationConflictKeyEmissionTest`)

| # | Setup (coarse `ENTITY` unless said) | Expected keys |
|---|---|---|
| E1 | non-granular attribute upsert | `EntityResidualConflictKey` only (NOT `EntityConflictKey`) |
| E2 | creation `MUST_NOT_EXIST`, mixed mutations | full `EntityConflictKey` (+ granular keys), NO residual |
| E3 | `EntityRemoveMutation` | full `EntityConflictKey` (unchanged) |
| E4 | `SetEntityScopeMutation` under ENTITY / COLLECTION / NONE | full `EntityConflictKey` / `CollectionConflictKey` / empty |
| E5 | granular adata + non-granular attr in one txn | granular adata key + residual |
| E6 | all mutations granular, `MAY_EXIST` | granular keys only, no entity-level key at all |
| E7 | range delta on non-granular attr | `AttributeDeltaConflictKey(sharedSurface=true)`, parent is residual |
| E8 | range delta on granular attr | `AttributeDeltaConflictKey(sharedSurface=false)`, parent is `AttributeConflictKey` |
| E9 | range delta on non-granular reference attribute | `ReferenceAttributeDeltaConflictKey(sharedSurface=true)`, parent is residual |

### 5b. Matcher tests (`IncomingConflictScopeTest`) — each in BOTH directions (key incoming vs key committed)

| # | Key 1 | Key 2 | Expected |
|---|---|---|---|
| M1 | residual(pk) | granular adata(pk, feed) | no conflict |
| M2 | residual(pk) | residual(pk) | conflict |
| M3 | residual(pk) | full entity(pk) | conflict |
| M4 | full entity(pk) | granular adata(pk, feed) | conflict |
| M5 | residual(pk) | granular attr(other pk) | no conflict |
| M6 | collection key | residual(pk) | conflict |
| M7 | catalog key | residual(pk) | conflict |
| M8 | shared delta(pk, attr) committed | incoming residual(pk) | conflict (commutative path) |
| M9 | carved delta(pk, attr) committed | incoming residual(pk) | no conflict |
| M10 | delta(pk, attr) committed | incoming identical delta | no conflict (commutes) |
| M11 | carved delta(pk, attr) committed | incoming absolute `AttributeConflictKey(pk, attr)` | conflict |
| M12 | shared delta(pk, attr) committed | incoming full entity(pk) (remove) | conflict |

### 5c. End-to-end rows (`EvitaTransactionalFunctionalTest`; schema: coarse ENTITY, adata `feed-heureka` + attr `snippetExpiration` per-item GRANULAR)

| # | Txn A | Txn B | Expected |
|---|---|---|---|
| F1 | attr `name` | adata `feed-heureka` | both commit (THE FIX) |
| F2 | adata `feed-heureka` | adata `feed-heureka` | second aborts |
| F3 | attr `name` | a price | second aborts |
| F4 | entity removal | adata `feed-heureka` | second aborts |
| F5 | scope change LIVE→ARCHIVED | adata `feed-heureka` | second aborts |
| F6 | creation of pk (MUST_NOT_EXIST) | adata `feed-heureka` on same pk | second aborts |
| F7 | attr `name` | attr `snippetExpiration` + adata `feed-heureka` | both commit |
| F8 | granularity-set flavor: entity declares `ASSOCIATED_DATA` in its granularity set (no per-item overrides); attr writer vs adata writer | | both commit |

### 5d. Recompute path (`TransactionManagerConflictWindowTest`)

Extend with: an aged-out coarse writer (residual recomputed) vs incoming granular feed write → no
conflict; an aged-out removal vs incoming granular feed write → conflict.

## 6. Out of scope — do NOT touch

- The `SetPriceInnerRecordHandlingMutation` containment gap under `PRICE` granularity (IRH key is a
  sibling, not an ancestor, of price keys). Known, tracked separately.
- Any per-dimension schema API additions.
- Javadoc of `ConflictResolutionOverride.GRANULAR` — its promise becomes TRUE with this fix; leave
  the contract text as is. DO update JavaDoc where the split makes existing text wrong or
  incomplete: `EntityConflictKey` (now: whole-entity operations), `EntityMutation.getConflictKeyStream`
  javadoc/comments, `IncomingConflictScope` class doc if its examples mention the old fallback.

## 7. Project rules (non-negotiable)

- Read `/www/oss/evita/evitaDB-carveout-wt/CLAUDE.md` and `.claude/rules/code-style.md` first.
  Highlights: TABS for indentation (Edit tool old/new strings need literal tabs), JavaDoc in
  Markdown (no HTML), `final` locals, `this.` for fields, no `var`, `@Nonnull`/`@Nullable`
  everywhere, no TODOs, no commented-out code, unreachable branches throw
  `GenericEvitaInternalError`.
- Comments/JavaDoc must NOT reference this assignment, "Change N", "constraint #N", issue numbers,
  or any transient artifact. Write them for the future reader of the code only.
- NEVER run `git add`, `git commit`, `git push`, or any other state-changing git command. The
  reviewer commits. Read-only git (status/diff/log) is fine and encouraged.

## 8. Build & verification protocol (concurrent-agent safe)

Another agent may be building the same project version elsewhere on this machine. Therefore:

1. FIRST, before any `install`: change the project version in the worktree:
   `rtk mvn versions:set -DnewVersion=2026.2.RC1-CARVEOUT-SNAPSHOT` (keep the default backup poms).
2. Build: `rtk mvn install -DskipTests` from the worktree root (first build compiles everything;
   allow up to 10 minutes — set Bash timeout 600000, or use run_in_background and detect completion
   by BUILD SUCCESS/FAILURE in the output file, never by PID liveness).
3. Iterate tests per module, e.g.
   `rtk mvn test -pl evita_test/evita_functional_tests -Dtest='IncomingConflictScopeTest,DataMutationConflictKeyEmissionTest'`
   then the transaction-manager and functional classes. Never pipe `rtk mvn` output through
   grep/head — read the tail or the surefire `.txt` reports.
4. When everything is green: `rtk mvn versions:revert` and confirm `git status --short` lists ONLY
   intended source/test files — no `pom.xml`, no `*.versionsBackup`, no stray files.
5. If the build fails on unreachable `nexus.fg.cz`, STOP and report it (VPN issue) instead of
   retrying.

## 9. Definition of done / final report

- All tests in §5 implemented and green; pre-existing conflict tests still green
  (`IncomingConflictScopeTest`, `DataMutationConflictKeyEmissionTest`,
  `TransactionManagerConflictWindowTest`, `ConflictRingBufferTest`,
  `ConflictResolutionAndWalAppendingTransactionStageTest`, and the transactional functional test
  class you extended).
- `git status` clean except intended changes; `git diff --stat` reviewed by you.
- Final report: list of changed/added files with one-line purpose each; test-run summary
  (counts per class, failures if any); any deviation from this assignment with justification;
  any place where reality contradicted this assignment (say so explicitly rather than forcing it).
