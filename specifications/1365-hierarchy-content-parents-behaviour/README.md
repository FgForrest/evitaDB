# hierarchyContent parent-chain behaviour reframing

Plan for https://github.com/FgForrest/evitaDB/issues/1365, milestone **2026.3**.

**Status: analysis complete, implementation deliberately not started.** The design is settled and
recorded in `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md` (`status:
proposed`). This folder holds the implementation intent and survives until the work lands, at which
point the ADR flips to `accepted` and this folder is deleted.

The external-API resolution forces breaking changes to GraphQL and REST, which cannot ship into
2026.2 mid-testing — hence the deferral.

## The agreed semantics

A new enum becomes an optional first argument of `hierarchyContent`. It governs what happens to an
ancestor whose **body cannot be materialized** under the query in effect.

| mode | ancestor that cannot be materialized | chain above it |
|---|---|---|
| **`ANY`** (default) | bodyless `EntityReferenceWithParent` pointer | continues to the root, bodies where possible |
| **`MATCHING`** | cut — the ancestor is gone | gone as well |

Deliberate decisions behind this:

- **`ANY` mirrors `referenceContent(ANY, ...)`.** One rule learned once: you get the node, maybe not
  the body. It is not the current behaviour — today the node either arrives bodyless *or vanishes*,
  depending on its depth.
- **`MATCHING` carries no marker distinguishing a cut from a real root.** Coherent with
  `ManagedReferencesBehaviour.EXISTING`, and `stopAt(distance(N))` already yields a chain
  indistinguishable from a real root. The caller opted in.
- **Mixed chains (body → pointer → body) are first-class** under `ANY`. The current gRPC proto
  cannot express this, so a proto field is required.
- **"Cannot be materialized"** means exactly the rejections in `EntityCollection#fetchEntityDecorator`
  (`EntityCollection.java:1313-1318`). Today that reduces to *localized schema + no data in the
  required locale*: scope cannot reject, because parent traversal is already per-scope. The wording
  is chosen so it still holds if `hierarchyContent` ever accepts a `filterBy`.
- **`dataInLocales` never cuts the chain** under either mode — it widens projection, it does not
  filter.
- **A materialized ancestor exposes global attributes normally**; localized attributes absent in the
  queried locale read as `null`, never as an exception (verified: `Attributes.java:331` falls back to
  the non-localized key).

### When accessing a parent may throw

An exception is owed **only** when the client's own query is at fault: the ancestor exists and would
be readable, the client chose not to fetch it, and then reads it anyway. Every other case returns
`null` / empty. Type and primary key are always readable — they never depend on a body.

| state | client asked for the body? | policy |
|---|---|---|
| no `hierarchyContent` at all | no | **throw** — `EntityDecorator:826` (`hierarchyContextMissing`) |
| `hierarchyContent()` without `entityFetch` | no | **throw** — `GetParentEntityMethodClassifier:187` |
| `ANY`, ancestor unmaterializable | **yes** | **must not throw** — `null` / empty |
| `MATCHING` cut | yes | empty, no throw |

The third row is the change. Today rows 2 and 3 are the *same object* — a bare
`EntityReferenceWithParent` — and the proxy can only see "not a `SealedEntity`", so both throw. The
returned model carries the difference via the distinct pointer type (item 5).

### Broken chains are unified into the same rule

Today three broken-chain situations behave three different ways:

| situation | today |
|---|---|
| immediate parent PK absent from DB | no chain at all; the entity looks like a root, silently |
| ancestor absent two or more levels up | **suspected** internal error — see note below |
| ancestor exists but has no data in the queried locale | issue #1365 |

The second row is a code read, **not reproduced**: the assertion at
`HierarchyIndex.getParentNodeOrThrowException:1056` appears reachable because a removed node is
dropped from `itemIndex` without being added to `orphans`. It must be characterised by a test before
anything is changed.

Under the new rule all three report **the chain up to the break**, under both modes.

This does **not** touch the documented orphan invariant. `documentation/user/en/use/schema.md:335-349`
states orphans "do not participate in the evaluation of queries on hierarchical structures" — that is
about *filtering*, which is served by `levelIndex` and is unchanged. `traverseHierarchyToRoot` has
exactly two callers (`ReferencedEntityFetcher.identifyParents:1532` and
`ParentStatisticsComputer:156`) and both are *reporting* paths, so the orphan guard inside it never
upheld the filtering invariant in the first place.

## Work items

### A. Query model — `evita_query`

1. **New enum `HierarchyParentsBehaviour`** in `io.evitadb.api.query.require`, values `ANY` (default)
   and `MATCHING`. `MATCHING` rather than `EXISTING` because for references `EXISTING` denotes "the
   target row is really in the database" — and for parents that exact condition (a deleted ancestor)
   behaves *identically in both modes*, so the word would name the one thing this enum does not
   control. `MATCHING` is true today (locale) and stays true if `hierarchyContent` gains a `filterBy`.
   `ManagedReferencesBehaviour` is left untouched — a second enum is purely additive, whereas
   renaming the existing one would break the Java and C# APIs, `GrpcManagedReferencesBehaviour` in
   `GrpcEnums.proto:206`, and probably the generated GraphQL/REST schema, while still leaving two
   types behind (Java cannot alias an enum type).
2. `HierarchyContent`: optional behaviour argument, new constructors, `getParentsBehaviour()`.
   `QueryConstraints.hierarchyContent(...)` overloads.
3. **EvitaQL grammar**: prefix the argument the same way `referenceContent` does —
   `(behaviour = valueToken ARGS_DELIMITER)?` in `EvitaQL.g4`, plus `EvitaQLRequireConstraintVisitor`.
   The enum *type* name never appears in EvitaQL (the grammar uses a generic `valueToken`), only the
   constant.
4. **Kryo**: register the enum for stored-query serialization; extend `QuerySerializationTest`.

### B. Engine — parent chain construction

5. `replaceWithSealedEntities` (`ReferencedEntityFetcher.java:1397`) is the core rewrite. It currently
   returns `Optional<SealedEntity>` and **short-circuits the recursion** the moment a body is missing,
   propagating emptiness upward — that short-circuit, not the missing body itself, is what produces
   both observed failure shapes.
   The behavioural change is therefore twofold, and the second half is easy to miss: substitute a
   pointer for the unmaterializable node **and keep recursing past it** so ancestors above it still
   get bodies. `EntityReferenceWithParent.parentEntity` is already typed `EntityClassifierWithParent`,
   so a pointer carrying a decorated ancestor needs no model change. The bitmap side is already
   correct — `identifyParents` registers the whole ancestor axis before any body is fetched, so
   ancestors above a rejected one are in `allReferencedParents`.
6. **Three distinguishable answers from the fetcher.** `prefetchParents:2418-2422` currently collapses
   everything into `null`, and `EntityDecorator:411-413` then falls back to the raw stored chain —
   which is how a dropped parent becomes a bodyless pointer today, by accident. The three states that
   must be told apart: *fetcher did not run* (fall back to the delegate), *`ANY` pointer* (a real
   `EntityReferenceWithParent`), *`MATCHING` cut* (chain ends, delegate must not resurrect it).
   **Decided:** derive the distinction from `HierarchySerializablePredicate.wasFetched():135-137`,
   which already records whether `hierarchyContent` was requested — hierarchy not requested means
   fall back to the delegate, hierarchy requested plus `null` means cut. No sentinel.
   Audit the `EntityDecorator` constructor's call sites first: anything that builds a decorator with
   hierarchy requested and `parentEntity == null` while *expecting* the delegate fallback would
   silently lose its parent.
7. `EntityDecorator:411-413` keeps the delegate fallback but only for the genuine "fetcher did not
   run" case. Document the three states on the field.
   **Deprecate `EntityClassifierWithParent.CONCEALED_ENTITY`** as part of this — it occupies the
   *chain-ends-here* slot, which the new design fills with a plain absence. Census of the live tree
   is exactly three sites: the declaration (`EntityClassifierWithParent:45`), one consumer
   (`EntityDecorator:832`), one producer (`ReferencedEntityFetcher:1409` — the bug). No tests, no
   external-API module, no proto.
   - delete the **producer** with the rest of the change; nothing replaces it
   - **keep the consumer branch** for the deprecation window — the decorator constructors are public
     and third-party code could still pass the constant in
   - mark the constant `@Deprecated(since = "2026.2")` per `.claude/rules/deprecation-policy.md`
     (re-derive from the reactor pom at commit time if the release train has moved). It is public
     `evita_api`, so it cannot simply be deleted. `@deprecated` JavaDoc: it never had a working
     producer, the chain now ends by absence, and check `getParentEntity().isEmpty()` instead
   - it cannot be *repaired* rather than deprecated: it throws from `getType()`/`getPrimaryKey()`,
     which the exception policy forbids, and being a singleton it has no type or PK to return.
     A future "there are more ancestors you may not see" marker would have to be a per-node object.

### C. Hierarchy index — broken chains

8. **Characterise first.** Write the test for the suspected `Assert` at
   `HierarchyIndex.java:1056` before changing anything: delete a mid-tree node, then query an entity
   two levels below it. When a node `P` is removed, `internalRemoveHierarchy:993` orphans its children
   and drops `P` from `itemIndex` but never adds `P` to `orphans`, so the lookup falls through to the
   assertion.
9. `traverseHierarchyToRoot` (`HierarchyIndex.java:661`): report the chain up to the break rather than
   returning without visiting anything. The current pre-walk exists only to prove reachability to a
   root.
10. **`level` semantics on a broken chain** — the pre-walk computes `nodeLevel` by counting to the
    root, which is undefined once the chain breaks. Only `stopAt(level(N))` is affected:
    `AbstractHierarchyTranslator:127-129` evaluates it as `level >= requiredLevel` under `BOTTOM_UP`,
    with level 1 meaning "root". `stopAt(distance(N))` is safe — distance is relative to the starting
    node. Today the question never arises because a broken chain yields no traversal at all. Needs a
    decision; see Open decisions.

### D. Extra-result parity

11. `ParentStatisticsComputer:156` and `AbstractHierarchyTranslator.createEntityFetcher:153-183` must
    follow the same rule. `createEntityFetcher` currently does `.orElse(null)`, which lands in
    `Accumulator.getEntity()` as a null node.

### E. gRPC

12. **Proto change.** `GrpcEntityReferenceWithParent.parent` (`GrpcEntity.proto:49`) is recursively
    pointer-only, so `body → pointer → body` cannot be transmitted. Add a `GrpcSealedEntity` field to
    `GrpcEntityReferenceWithParent` and **populate both** it and the existing pointer field, so old
    clients still receive a complete chain (degraded to pointers) instead of a truncated one.
13. `EntityConverter:136-176` (deserialize) must reconstruct mixed chains;
    `EntityConverter:361-376` / `toGrpcEntityReferenceWithParent:703` (serialize) currently casts
    every ancestor to `EntityReferenceWithParent`.
14. New `Grpc…Behaviour` enum + `GrpcQueryParam` field for query-parameter binding.

### F. GraphQL / REST

15. Constraint descriptor for the new argument and the enum in both generated schemas; check
    `EntityFetchRequireResolver`.

### G. Exception surface

16. `ContextMissingException.hierarchyEntityContextMissing()` needs **no rewording**. It reads
    "you need to use `hierarchyContent` with `entityFetch`", which was misleading only because an
    unmaterializable ancestor was reaching it. Once that case stops throwing (see the exception
    policy above), the sole remaining case is a client that genuinely did not request bodies — for
    which the message is exactly right. The work here is to make `GetParentEntityMethodClassifier:187`
    stop firing for a requested-but-unmaterializable ancestor, and to prove it by test.
    `SetParentEntityMethodClassifier:391` throws the same exception on the write path — verify it is
    unaffected.

### H. Documentation

17. `documentation/user/en/query/requirements/fetching.md` — the `hierarchyContent` section, both
    modes, and the fact that a chain may legitimately contain bodyless nodes.
18. `documentation/user/en/use/schema.md` orphan section — reporting now shows chain fragments while
    filtering still ignores orphans entirely.
19. Czech mirror is machine-translated — never hand-edited.

### I. Tests

Project rule: failing tests first, then the fix. Full catalogue in the next section.

### J. ADR

Required at the end of the session — this carries several genuine forks (second enum vs. rename,
`ANY` as pointer vs. body, no marker for `MATCHING`, unifying broken chains) whose reasoning does not
survive in the diff. It replaces this plan folder in the same commit.

## Scenario catalogue

Notation for an expected chain, written from the queried entity upward:
`B(pk)` = present with body, `P(pk)` = present as a bodyless pointer, `—` = chain ends here.

### Fixtures

One hierarchical, localized `Category` collection (locales `cs`, `en`; `name` localized + nullable,
`code` global). `F8` needs a second, non-localized hierarchical collection.

| id | chain read **leaf → root**, same direction as the expected results | purpose |
|---|---|---|
| F1 | **23**(cs,en) → 22(cs,en) → 21(cs,en) | control — nothing to drop |
| F2 | **12**(cs,en) → 11(en) | locale-less **immediate parent** |
| F3 | **3**(cs,en) → 2(cs,en) → 1(en) | locale-less **root** |
| F4 | **33**(cs,en) → 32(en) → 31(cs,en) | **mixed chain** — body above a pointer |
| F5 | **44**(cs,en) → 43(en) → 42(en) → 41(cs,en) | two **consecutive** locale-less |
| F6 | **54**(cs,en) → 53(en) → 52(cs,en) → 51(en) | two **separated** locale-less |
| F7 | **63**(cs,en) → 62(en) → 61(en) | every ancestor locale-less |
| F8 | non-localized schema, 3 levels | schema exemption |
| F9 | **72**(cs,en) → 71(cs,en), then delete 71 | break at the **immediate** parent |
| F10 | **83**(cs,en) → 82(cs,en) → 81(cs,en), then delete 81 | break **two levels up** |
| F11 | **93**(cs,en) → 92(en) → 91(cs,en), then delete 91 | break **plus** a locale-less node |
| F12 | create 102 with parent 101, then create 101 | orphan reattachment |

**Notation.** `(cs,en)` means the entity holds data in *both* locales; `(en)` means English only —
that is the unmaterializable case. Every node is annotated; nothing is implied by omission. F8 is the
separate case of a schema with **no locales at all**. The queried leaf always holds `cs`, otherwise
the top-level `entityLocaleEquals(cs)` would not return it in the first place. The **bold** node is
the one queried, and chains are written leaf → root throughout — fixtures and results alike.

All queries are `entityPrimaryKeyInSet(leaf) + entityLocaleEquals(cs)` with
`entityFetch(attributeContentAll(), hierarchyContent(<mode>, entityFetch(attributeContentAll())))`
unless stated otherwise.

### Chain shape × mode

`standard` = `hierarchyContent(MODE, entityFetch(attributeContentAll()))`, wrapped in
`entityFetch(attributeContentAll(), …)`, with `filterBy(entityPrimaryKeyInSet(leaf),
entityLocaleEquals(cs))`. Chains read leaf → root.

| id | fx | requirement | `ANY` / unspecified | `MATCHING` |
|---|---|---|---|---|
| S1 | F1 | standard | `B(22) → B(21)` | `B(22) → B(21)` |
| S2 | F2 | standard | `P(11)` | `—` |
| S3 | F3 | standard | `B(2) → P(1)` | `B(2) → —` |
| S4 | F4 | standard | `P(32) → B(31)` | `—` |
| S5 | F5 | standard | `P(43) → P(42) → B(41)` | `—` |
| S6 | F6 | standard | `P(53) → B(52) → P(51)` | `—` |
| S7 | F7 | standard | `P(62) → P(61)` | `—` |
| S8 | F8 | standard | `B → B` | `B → B` |
| K1 | F9 | standard | `—` | `—` |
| K2 | F10 | standard | `B(82) → —` | `B(82) → —` |
| K3 | F11 | standard | `P(92) → —` | `—` |
| I1 | F3 | no `entityFetch` | PK chain `2 → 1` | identical |
| I2 | F4 | `+ stopAt(distance(1))` | `P(32)` | `—` |
| I4 | F10 | `+ stopAt(level(2))` | `—` | `—` |
| I5 | F3 | `+ dataInLocales(en)` | `B(2) → P(1)` | `B(2) → —` |
| I6 | F3 | no query locale | `B(2) → B(1)` | `B(2) → B(1)` |


Why each row comes out that way:

- **S1** — No ancestor lacks `cs`, so nothing is gated: both modes return the full chain with bodies.
- **S2** — Parent 11 lacks `cs`. `ANY` keeps it as a bodyless pointer; `MATCHING` removes it, leaving
  no parent at all.
- **S3** — Root 1 lacks `cs`. `ANY` keeps it as a pointer above the materialized 2; `MATCHING` cuts
  at 1 and keeps 2.
- **S4** — Middle ancestor 32 lacks `cs`. `ANY` replaces it with a pointer and still fetches 31 above
  it; `MATCHING` cuts at 32 and loses 31 with it.
- **S5** — 43 and 42 both lack `cs`. `ANY` makes both pointers and still reaches 41; `MATCHING` cuts
  at 43, the nearest one, losing 42 and 41 too.
- **S6** — 53 and 51 lack `cs`, 52 does not. `ANY` judges each node on its own; `MATCHING` cuts at 53
  and discards the perfectly materializable 52 with it.
- **S7** — Every ancestor lacks `cs`. `ANY` returns the complete chain, all pointers; `MATCHING`
  returns nothing.
- **S8** — The schema is not localized, so the locale gate never applies: both modes return full
  bodies.
- **K1** — Parent 71 no longer exists. The chain ends at the break in both modes; locale is never
  consulted.
- **K2** — 82 materializes, 81 no longer exists. Both modes return 82 with a body and stop at the
  break.
- **K3** — 92 lacks `cs` and 91 no longer exists. `ANY` makes 92 a pointer, then stops at the break;
  `MATCHING` cuts at 92 before the break is ever reached.
- **I1** — No bodies were requested, so nothing can fail to materialize. `MATCHING` has nothing to
  cut, and both modes return the full PK chain.
- **I2** — `stopAt` limits the walk to 32, which lacks `cs`. `ANY` returns it as a pointer;
  `MATCHING` removes it, leaving nothing.
- **I4** — The chain is broken, so `level` cannot be counted from a real root. Traversal stops
  immediately in both modes and no parent is returned.
- **I5** — `dataInLocales` widens what is projected, not what is filtered. 1 still lacks `cs`, so
  both modes behave exactly as S3.
- **I6** — With no query locale the gate is inactive and nothing is unmaterializable: both modes
  return the full chain with bodies.

S2/S3 are the two shapes reported in #1365. **S4 is the load-bearing one** — it is the only cell
that proves the recursion no longer short-circuits, and the only one that cannot be transmitted over
gRPC today. S6 proves a *second* pointer does not collapse the chain above it. `unspecified` must
assert byte-identical results to explicit `ANY`, since that is the default-behaviour contract.

Every `P(pk)` assertion must check **both** that the node is present with the right primary key
**and** that it is not a `SealedEntity`. Every `—` must assert `getParentEntity().isEmpty()`.

### Interactions

| id | scenario | expectation | guards against |
|---|---|---|---|
| I1 | F3, `hierarchyContent()` alone | PK chain `1 ← 2`, same in all modes | bodies costing you ancestors |
| I2 | F4, `stopAt(distance(1))` × modes | `ANY`: `P(32)`; `MATCHING`: `—` | distance regressions |
| I3 | F1, `stopAt(level(2))` × modes | unchanged from today | level regressions on intact chains |
| I4 | F10, `stopAt(level(2))` × modes | **no parents at all**; identical to today | the fragment-level decision |
| I5 | F3 + `dataInLocales(en)` inside the inner fetch | chain not cut in either mode | projection acting as a filter |
| I6 | F3, query carries no `entityLocaleEquals` | `B(2) → B(1)` both modes | the issue's own control |
| I7 | F2/F3 queried in `en` | everything materializes, modes identical | over-eager cutting |

I1 also pins a corollary worth stating explicitly: with no `entityFetch`, nothing can fail to
materialize, so `MATCHING` must be a **no-op**.

Note a scenario that is deliberately *absent*: "ancestor body present but carrying only global
attributes" does not exist under the adopted `ANY` — an unmaterializable ancestor has no body at all.
That shape belonged to the rejected alternative.

### Broken chains

| id | fixture | `ANY` | `MATCHING` | note |
|---|---|---|---|---|
| K0 | F10 | *characterisation* | *characterisation* | **run first, before any change** |
| K1 | F9 | `—` | `—` | identical to today; assert no exception |
| K2 | F10 | `B(82) → —` | `B(82) → —` | fragment now reported |
| K3 | F11 | `P(92) → —` | `—` | break above a locale-less node |
| K4 | F12 | `—`, then `B(101)` after 101 is created | same | documented eventual consistency |

K0 exists to record what the suspected assertion at `HierarchyIndex:1056` actually does today. It is
a throwaway test — delete it once K2 is green — but without it we are guessing at what we fixed.

### Extra-result parity

| id | fixture | via |
|---|---|---|
| E1 | F3 | `hierarchyOfSelf(parents(entityFetch(attributeContentAll())))` |
| E2 | F4 | as above — mixed chain through the extra result |
| E3 | F10 | as above — fragment through the extra result |

Expected values mirror S3/S4/K2. `parents` gets no behaviour argument of its own and follows the
same rule, so these assert the `ANY` column. Write them parameterised over the mode anyway, so the
shape survives if a knob is ever added.

### Java-level expectations for every shape

Shared query, `MODE` substituted per column, `LEAF` per fixture:

```java
query(
    collection(Entities.CATEGORY),
    filterBy(entityPrimaryKeyInSet(LEAF), entityLocaleEquals(LOCALE_CZECH)),
    require(entityFetch(attributeContentAll(),
        hierarchyContent(MODE, entityFetch(attributeContentAll()))))
)
```

**S1 · F1 control — identical in both modes**

```java
SealedEntity parent = (SealedEntity) leaf.getParentEntity().orElseThrow();
assertEquals(22, parent.getPrimaryKey());
assertEquals("Kontrola rodič", parent.getAttribute(ATTRIBUTE_NAME));
SealedEntity grandParent = (SealedEntity) parent.getParentEntity().orElseThrow();
assertEquals(21, grandParent.getPrimaryKey());
assertTrue(grandParent.getParentEntity().isEmpty());          // genuine root
```

**S2 · F2 — locale-less immediate parent**

```java
// ANY
EntityClassifierWithParent parent = child.getParentEntity().orElseThrow();
assertEquals(11, parent.getPrimaryKey());
assertEquals(Entities.CATEGORY, parent.getType());            // type always readable
assertFalse(parent instanceof SealedEntity);                  // pointer, no body
assertTrue(parent.getParentEntity().isEmpty());               // 11 is a root

// MATCHING
assertTrue(child.getParentEntity().isEmpty());
```

**S3 · F3 — locale-less root**

```java
// ANY
SealedEntity parent = (SealedEntity) leaf.getParentEntity().orElseThrow();
assertEquals(2, parent.getPrimaryKey());
EntityClassifierWithParent grandParent = parent.getParentEntity().orElseThrow();
assertEquals(1, grandParent.getPrimaryKey());
assertFalse(grandParent instanceof SealedEntity);

// MATCHING
SealedEntity parent = (SealedEntity) leaf.getParentEntity().orElseThrow();
assertTrue(parent.getParentEntity().isEmpty());               // cut at 1
```

**S4 · F4 — the load-bearing one: a body ABOVE a pointer**

```java
// ANY
EntityClassifierWithParent parent = leaf.getParentEntity().orElseThrow();
assertEquals(32, parent.getPrimaryKey());
assertFalse(parent instanceof SealedEntity);                  // pointer
SealedEntity grandParent = (SealedEntity) parent.getParentEntity().orElseThrow();
assertEquals(31, grandParent.getPrimaryKey());
assertNotNull(grandParent.getAttribute(ATTRIBUTE_NAME));      // recursion did NOT stop

// MATCHING
assertTrue(leaf.getParentEntity().isEmpty());                 // cut at 32
```

**S5 / S6 / S7 — multiple locale-less nodes, `ANY`**

```java
// S5 · F5 → P(43) → P(42) → B(41): consecutive pointers do not collapse the chain
// S6 · F6 → P(53) → B(52) → P(51): a body BETWEEN two pointers
// S7 · F7 → P(62) → P(61):         every ancestor a pointer, chain still complete
```

All three are `Optional.empty()` at the first step under `MATCHING`.

**S8 · F8 — non-localized schema, identical in both modes**

```java
assertInstanceOf(SealedEntity.class, leaf.getParentEntity().orElseThrow());
```

**K1 / K2 / K3 — broken chains, identical in both modes except where noted**

```java
// K1 · F9, immediate parent deleted
assertTrue(child.getParentEntity().isEmpty());                // and NO exception

// K2 · F10, ancestor deleted two levels up
SealedEntity parent = (SealedEntity) leaf.getParentEntity().orElseThrow();
assertEquals(82, parent.getPrimaryKey());
assertTrue(parent.getParentEntity().isEmpty());               // fragment ends at the break

// K3 · F11, break above a locale-less node — ANY
EntityClassifierWithParent parent = leaf.getParentEntity().orElseThrow();
assertEquals(92, parent.getPrimaryKey());
assertFalse(parent instanceof SealedEntity);
assertTrue(parent.getParentEntity().isEmpty());               // break at 91
// K3 · MATCHING
assertTrue(leaf.getParentEntity().isEmpty());
```

**Exception policy — assert on the raw API or a `throws`-declaring proxy method**

```java
// no hierarchyContent at all
assertThrows(ContextMissingException.class, () -> leaf.getParentEntity());

// hierarchyContent() without entityFetch — parent readable as a classifier, body is an error
EntityClassifierWithParent parent = leaf.getParentEntity().orElseThrow();
assertEquals(2, parent.getPrimaryKey());
assertThrows(ContextMissingException.class, () -> proxy.getParentEntity());

// ANY with an unmaterializable ancestor — MUST NOT throw
assertDoesNotThrow(() -> proxy.getParentEntity());
assertNull(proxy.getParentEntity());
```

### API surface

Do not duplicate the matrix per API.

- **Embedded / engine** — everything above.
- **gRPC** — S4 (mandatory: the mixed chain the current proto cannot express), S2, S3 and its
  `MATCHING` counterpart. Plus **BWC**: assert both the legacy pointer field and the new body field
  are populated, and that a reader consulting only the legacy field still sees the *complete* chain
  as pointers rather than a truncated one.
- **GraphQL / REST** — one `ANY` case containing a pointer, and one `MATCHING` cut. **Open
  question:** how a bodyless mid-chain ancestor is represented in the JSON parent field — this needs
  answering before the scenarios can be written, and it may itself constrain the design.

### Regression guards

| id | assertion |
|---|---|
| R1 | `ManagedReferenceLocaleFunctionalTest` stays green, untouched — #1343 behaviour is unchanged |
| R2 | `hierarchyWithin` still ignores orphans entirely (build F10, assert the orphan is not returned) |
| R3 | existing hierarchy and deep-fetch functional suites stay green |

R2 is the important one: the orphan change must be provably confined to *reporting*.

### Placement and tags

- Supersede the issue's `HierarchyContentLocaleFunctionalTest` (not yet in the repo) with
  `HierarchyContentParentsBehaviourFunctionalTest` in
  `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/functional/fetch/` — functional
  tests live in that module for JPMS reasons. The issue's dataset covers F1–F3; extend it.
- Traversal-level cases (K0, and fragment reporting in isolation) belong as unit tests in the
  existing `HierarchyIndexTest`, which already has an "absent node skips silently" case to sit
  beside.
- Tags per `.claude/rules/testing.md` — at least one layer tag and one capability tag, enforced in
  strict mode. `CONTRACT` + `QUERY` + `HIERARCHY` for the functional class; add `GRPC` +
  `EXTERNAL_API` on the wire tests, `GRAPHQL` / `REST` on those. `INDEXING` for the `HierarchyIndex`
  unit tests.

## Risks

- **`level` on broken chains** feeding `stopAt(level(N))` (item 10).
- **Stored-query BWC** — a new constraint argument changes query serialization; check traffic
  recordings and `serialVersionUID` policy.
- **Old gRPC clients** — mitigated by populating both proto fields (item 12), which must be tested,
  not assumed.
- **Scope creep into the filtering path** — the orphan change must be provably invisible to
  `hierarchyWithin`.

## Settled decisions

- **Enum**: `HierarchyParentsBehaviour` with `ANY` (default) / `MATCHING`. Argument position is a
  leading optional `valueToken`, exactly like `referenceContent`.
- **Cut representation**: derived from `HierarchySerializablePredicate.wasFetched()`, no sentinel;
  `CONCEALED_ENTITY` retired (item 7).
- **Uniformity**: the chosen mode governs every level of the chain, not just the immediate parent.
  The `parents` extra result gets **no knob of its own** and follows the same rule.
- **`level` on a broken chain**: when `stopAt(level(N))` is in play and the chain is broken,
  traversal stops immediately and **no parents are reported** — rather than counting from a base
  that is not a real root, and rather than throwing. Throwing would blame a reader for an ingester's
  data. This preserves today's behaviour exactly for that combination, so no existing query changes
  its result. Record the preference in the ADR.

- **Unmaterializable ancestor representation**: **option (a)** — a distinct classifier type, a
  variant of `EntityReferenceWithParent` meaning "body requested, unavailable under this filter".
  Coherent with references, where an unmaterializable target yields no `referencedEntity` at all
  rather than an empty one. Keeps the gRPC proto change (item 12).
  The rejected alternative was an empty `SealedEntity` — it would have removed the proto change
  entirely (every chain node becomes a `GrpcSealedEntity`, so the pointer-to-body shape never
  arises), but "empty because unmaterializable" would be indistinguishable from "empty because the
  data is genuinely null", and a client testing `instanceof SealedEntity` would believe it received
  a body.

  Option (a) is what makes the exception policy implementable, and it lands at exactly one site.
  `GetParentEntityMethodClassifier` dispatches on the declared return type, and three of its four
  shapes already work from a bodyless pointer, needing only type and primary key
  (`singleParentIdResult`, `singleParentReferenceResult`, `singleParentClassifierResult`). Only the
  custom-`@Entity`-interface shape throws, at `GetParentEntityMethodClassifier:186-187`, where the
  `else` currently sees only "not a `SealedEntity`" and so cannot tell *body never requested* from
  *body not materializable*. The distinct type is what splits that branch.

### Proxy wrappers can mask the whole thing in tests

`ProxyUtils.createOptionalWrapper:135-157` picks a **swallowing** wrapper when
`method.getExceptionTypes()` is empty and a **rethrowing** one otherwise — independently of whether
the return type is an `Optional`. A rethrowing wrapper rethrows only exceptions assignable to the
declared types; anything else is swallowed.

Consequences:

- Only clients whose proxy method declares `throws ContextMissingException` (or a supertype), or who
  use the raw `SealedEntity` API, ever *see* the exception. That is who the #1365 production failure
  actually hit.
- With an exception-free signature the proxy returns empty for **both** *body never requested* and
  *body unmaterializable* — so the exception policy is already partially opted out of at that layer.
  That is pre-existing and intentional, but it means **assertions must be made against the raw
  `SealedEntity` API or a `throws`-declaring proxy method**. A swallowing wrapper would let a
  completely broken implementation pass.

### External-API representation — decided, and it is what deferred the work

**GraphQL: a union** of the entity object and a reference object. **REST: `oneOf` with a
discriminator.** Both mirror the Java model, so all four surfaces tell one story. Both are breaking
for generated clients.

The cheaper options were not merely worse, they were unworkable. `EntityDescriptor.java:53-92`
declares `primaryKey`, `type`, `scope`, `locales` and `allLocales` **non-null**. A pointer knows only
`primaryKey` and `type`, so it cannot be expressed as an ordinary entity object carrying nulls — in
GraphQL the non-null violation bubbles and collapses the whole `parents` list for a client that
merely selected `parents { locales }`. Relaxing those fields to nullable would be a broader breaking
change, weakening the contract for every entity rather than just for parents.

The three APIs do not share a chain shape, which is why only gRPC needs a new field:

| API | parent chain shape | body → pointer → body today? |
|---|---|---|
| Java | nested chain, two types | yes |
| gRPC | nested chain, pointer-only above the first pointer | **no** — needs a proto field |
| REST | `parentEntity`, nested recursive object of the entity type | yes |
| GraphQL | `parents`, flat list of the non-hierarchical entity object | yes — elements are independent |

Rejected, one line each, so none of them gets re-proposed:

- **element/object carrying nulls** (GraphQL G1, REST R1) — cheapest and needs no schema change, but
  a pointer cannot answer `locales`, and in GraphQL that null bubbles and collapses the list. In REST
  it merely degrades, but the response then violates its own `required` fields.
- **a `bodyAvailable` discriminator flag** (G2, R2) — additive and non-breaking, but does **not**
  solve the non-null problem: the client can still select `locales` on a pointer. Only viable if
  those fields are relaxed to nullable, which is a broader break.

## Coarse implementation plan

Six phases. Phases 1–3 are engine-only and independently shippable; 4–6 are the API surfaces and
carry the breaking change.

**Phase 1 — Characterise** *(no dependencies)*
K0 plus current-behaviour tests for every shape in the catalogue. The suspected `HierarchyIndex:1056`
assertion is confirmed or dismissed here.

**Phase 2 — Query model** *(needs 1)* — items 1–4
`HierarchyParentsBehaviour`, the `HierarchyContent` argument, `QueryConstraints`, EvitaQL grammar and
visitor, Kryo registration.

**Phase 3 — Engine** *(needs 2)* — items 5–11 and 16
`replaceWithSealedEntities` rewrite, the pointer type, the fetcher/decorator three-state protocol,
`CONCEALED_ENTITY` deprecation, `traverseHierarchyToRoot` fragments, `ParentStatisticsComputer`
parity, the proxy branch split.

**Phase 4 — gRPC** *(needs 3)* — items 12–14
Proto field on `GrpcEntityReferenceWithParent` with both fields populated, converters in both
directions, the enum and its `GrpcQueryParam` binding.

**Phase 5 — GraphQL + REST** *(needs 3)* — item 15
Size the break with `tools/diff-graphql-schemas.sh` and `tools/diff-openapi-schemas.sh` **before**
writing code. Target shapes, on the `3(cs,en) → 2(cs,en) → 1(en)` fixture queried in `cs`:

```graphql
{ parents {
    ... on Category          { primaryKey code name locales }
    ... on CategoryReference { primaryKey type }
} }
```
```json
"parents": [ { "__typename": "CategoryReference", "primaryKey": 1, "type": "Category" },
             { "__typename": "Category", "primaryKey": 2, "code": "cameras",
               "name": "Fotoaparáty", "locales": ["cs","en"] } ]
```

REST — `parentEntity` declared `oneOf: [Category, EntityReference]` with a discriminator:

```json
{ "primaryKey": 3, "attributes": { "code": "dslr", "name": "Zrcadlovky" },
  "parentEntity": {
      "primaryKey": 2, "attributes": { "code": "cameras", "name": "Fotoaparáty" },
      "parentEntity": { "type": "Category", "primaryKey": 1 } } }
```

Verify first, since it shapes how R3 is written: whether the generated OpenAPI marks the affected
fields `required`, and the exact null-bubbling blast radius in GraphQL (depends on the `parents`
list wrapper).

**Phase 6 — Documentation** *(needs 4 and 5)* — items 17–19
`fetching.md`, the orphan section of `schema.md`, release notes for the breaking change.

Sequencing notes:

- **Phase 1 is not optional.** Three of the shapes in the catalogue have never had their current
  behaviour recorded, and one of them may be an internal error rather than the silent drop we assume.
- **Phase 3 is the risk concentration** — the recursion change, the protocol change and the traversal
  change all land together and all touch shared paths. Split into separate commits per item.
- **Phase 5 should start with measurement, not code.** The breaking-change surface determines the
  release-notes work and may surface consumers nobody has counted.
- Phases 4 and 5 are independent of each other and can run in parallel once 3 is green.

## Open decisions

None. The remaining unknowns are verification tasks, not decisions:

- whether the generated OpenAPI marks the affected fields `required`, and the exact null-bubbling
  blast radius in GraphQL — both listed under phase 5, where they are acted on
- whether anything outside the parent path still produces `CONCEALED_ENTITY`
