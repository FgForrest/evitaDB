# #1257 — repro & verification playbook

> Compaction-safe handoff. Anything the fix session needs to verify the bug
> reappears here, exactly enough so it can be re-run without re-discovering it.

GitHub issue: https://github.com/FgForrest/evitaDB/issues/1257
Originating ticket (Czech, project view): https://gitlab.fg.cz/prj/p_mila.eshop/-/work_items/938

## The bug in one line

Sort by `referenceProperty` on a multi-valued reference returns duplicate PKs
in the result page when at least one entity on that page has the sortable
reference-attribute marked as soft-deleted (`AttributeValue.dropped() == true`).
With `entityFetch(referenceContent(...))`, the duplicate trips
`Collectors.toMap` in `EntityIndexSupplier.get` → `IllegalStateException:
Duplicate key <PK>`.

## Files involved

- Root: `evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/Attributes.java:183` and `:225`
  → `getAttribute` does NOT consult `AttributeValue.dropped()`. Comparator
    `attributeValueFetcher` reads through this and treats soft-deleted as live.
- Symptom site (sort): `evita_engine/src/main/java/io/evitadb/core/query/sort/attribute/comparator/`
    - `AbstractReferenceAttributeComparator.java:131` — `it.getAttribute(attributeName)`
    - `AbstractReferenceCompoundAttributeComparator.java:125-127` — `AttributesContract::getAttribute` reference
    - `TraverseReferencePredecessorAttributeComparator.java:172-272` — the `compare`
      that, when `o1FoundInProvider == -1`, adds to `nonSortedEntities` while
      returning `0` from default `result` — keeps the entity in place AND flags
      it as non-sortable.
- Slice that trusts the contract: `evita_engine/src/main/java/io/evitadb/core/query/sort/generic/PrefetchedRecordsSorter.java:97-107`
  → `entityContracts = entities.subList(0, selectedRecordIds.size() - notFoundRecordsCnt)`.
- Final crash site: `evita_engine/src/main/java/io/evitadb/core/query/fetch/EntityIndexSupplier.java:58`
  → `Collectors.toMap(EntityContract::getPrimaryKey, …)` — KEEP AS-IS, this is
    the policy-correct fail-loud.

## Fix plan (agreed in conversation)

1. **Single-class change in `Attributes.java`**: drop the `Optional` chain in
   both overloads of `getAttribute`, return `null` when `av == null || av.dropped()`.
   - `getAttribute(name)` (line 183)
   - `getAttribute(name, locale)` (line 225)
   - Performance: removes one Optional allocation per fetch on the comparator
     hot path. Don't switch to a `.filter(...).map(...).orElse(...)` chain — go
     to direct branching for zero alloc.
2. **Add assertion in `PrefetchedRecordsSorter.sortAndSlice`** that every entity
   reported by `entityComparator.getNonSortedEntities()` is in the trailing
   slice (NOT in `entities[0..entitiesCount)`). Fail loud if a future comparator
   violates the contract. Only iterate when `notFoundRecordsCnt > 0`.
3. **Do NOT add a merge function to `EntityIndexSupplier.get`**. Per project
   rule (manifest broken assumptions early), keep the loud
   `Collectors.duplicateKeyException`. It is what surfaced this defect.

## Production snapshot reproduction (slow but authoritative)

### Pre-conditions

- Snapshot at `/www/oss/evita/release_2026-1/data/milagro_cz`
  (`catalogVersion=89758`, `schemaVersion=83`). Don't run a write workload
  against it — it should remain unchanged for re-verification.
- Server jar built: `evita_server/target/evita-server.jar` (already there).

### Start the server

From `/www/oss/evita/release_2026-1/evita_server`:

```bash
bash run-server.sh
```

It loads `milagro_cz` (≈ 6 s) and exposes APIs on port 5555 (REST/GraphQL TLS,
gRPC TLS RELAXED). JDWP is on 8005.

To run the JUnit driver against it the EvitaClient needs **plaintext h2c**
(not TLS) — `tlsEnabled=false`. See `evita_test/evita_functional_tests/src/test/java/io/evitadb/store/catalog/MilagroCzReproductionTest.java`
for the working config.

### Run the 8 reproduction queries

Test is gated by a system property so it doesn't run in CI:

```bash
cd /www/oss/evita/release_2026-1/evita_test/evita_functional_tests
mvn test -Dtest=MilagroCzReproductionTest#runAllReproQueries \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmilagro.repro=true \
  -Dsurefire.useFile=false
```

### Expected outcomes BEFORE the fix

| Q | Sort | entityFetch | Expected outcome |
|---|---|---|---|
| Q1 | `referenceProperty('groups', orderInGroup, traverseBy(145197))` | `referenceContent('groups')` | OK page=200 total=3493 dups=0 |
| Q2 | segments + `attributeNatural('orderedQuantity')` | full | OK page=20 total=325 dups=0 |
| **Q3** | `referenceProperty('groups', orderInGroup, assignmentPriority)` no traverse | full | **FAIL: `IllegalStateException: Duplicate key 759850`** (Collectors.toMap in EntityIndexSupplier) |
| **Q4** | `referenceProperty('groups', orderInGroup, assignmentPriority, traverseBy(21798))` | `attributeContent('code')` only | **OK page=20 total=325 with `dups=3` — PKs 755567, 759850, 760244 each appear twice** |
| Q5 | `attributeNatural('code', ASC)` | full | OK page=20 total=325 dups=0 |
| **Q6** | as Q4 with full fetch | full | **FAIL: `Duplicate key 759850`** |
| **Q7** | `segments(... refprop traverse ...)` | full | **FAIL: `Duplicate key 759850`** |
| Q8 | segments + simpler filter | `referenceContent('groups', 'relatedProducts')` | OK page=326 total=326 dups=0 |

The duplicate-key crash also dumps a payload that prints the broken entity's
references — the `❌ 🔑 orderInGroup` marker on `References groups 21798/…`
confirms the dropped state.

### Expected outcomes AFTER the fix

All eight queries finish successfully. The Q4 page must contain **20 unique
PKs** (no `dups=3`). The full 8-query suite output should end with all queries
listed as `OK`. The crash stack at `Collectors.duplicateKeyException` must not
appear in the server log.

Sanity comparison: the single-attribute query

```evitaql
query(
  collection('Product'),
  filterBy(referenceHaving('groups', entityPrimaryKeyInSet(21798)), entityLocaleEquals('cs')),
  orderBy(referenceProperty('groups', attributeNatural('orderInGroup', ASC))),
  require(page(1, 1000))
)
```

returns 955 unique PKs both BEFORE and AFTER the fix, with PKs `759850, 760244,
755567` at the trailing end (positions ~948..955). This is the "should-have-been"
behaviour the compound path needs to match.

## Synthetic minimal regression (still TODO — write on the fix branch)

Build a self-contained functional test in a fresh catalog that reproduces the
duplicate without any production data:

- Schema: `Group` (no attrs needed), `Product` with `groups` reference
  (`ZERO_OR_MORE` → `Group`, indexed) with a sortable attribute
  `orderInGroup` (Integer or Predecessor, sortable + filterable) and a second
  sortable reference-attribute `assignmentPriority` (Long, sortable) so the
  sort uses the COMPOUND path.
- Data:
  - `Group#1`, `Group#2`
  - `Product#100` with `groups → 1 {orderInGroup=1, assignmentPriority=100L}`
    and `groups → 2 {orderInGroup=1, assignmentPriority=100L}`
  - `Product#101` with `groups → 1 {orderInGroup=2, assignmentPriority=200L}`
  - `goLiveAndClose`
- **Trigger the dropped state**: open a transactional session and apply a
  `RemoveAttributeMutation` on `Product#100`'s reference to `Group#1`,
  attribute `orderInGroup`. (Or use the builder API equivalent.) Commit.
- Query (compound + traverse):
  ```evitaql
  query(
    collection('Product'),
    filterBy(referenceHaving('groups', entityPrimaryKeyInSet(1))),
    orderBy(referenceProperty('groups', attributeNatural('orderInGroup', ASC),
                              attributeNatural('assignmentPriority', ASC),
                              traverseByEntityProperty(entityPrimaryKeyExact(1)))),
    require(strip(0, 10), entityFetch(referenceContent('groups')))
  )
  ```
- **Pre-fix expected:** `IllegalStateException: Duplicate key 100` (or `100`
  appears twice in result if `entityFetch` is dropped to attribute-only).
- **Post-fix expected:** clean result of two entries (`101`, `100`), no
  exception.

Place the test next to existing reference-sort functional tests under
`evita_test/evita_functional_tests/src/test/java/io/evitadb/api/.../sort/` — match
the naming/location of `AttributeNaturalReferenceSortTest` or similar.
Caveat from earlier attempt: a previous synthetic test (mentioned in
prj/p_mila.eshop#938 note 17.6.) didn't reproduce because it never applied
the `RemoveAttributeMutation`. The dropped-on-reference state is the trigger.

## Things NOT to change

- `EntityIndexSupplier.get` (`evita_engine/.../fetch/EntityIndexSupplier.java`) —
  no merge function. The loud crash is on purpose.
- `Attributes.anyAttributeDifferBetween` and any audit/diff code that walks
  `attributeValues` directly — these are correct to see dropped values.
- `getAttributeValue(name)` (Optional<AttributeValue>) — keep its current
  semantics; it is the path callers use when they want to inspect `dropped()`.

## Session-state checkpoints

- `MilagroCzReproductionTest.java` is on disk under
  `evita_test/evita_functional_tests/src/test/java/io/evitadb/store/catalog/`,
  gated by `-Dmilagro.repro=true`. EvitaClient config uses port 5555 plaintext
  (`tlsEnabled=false`) — necessary because `run-server.sh` has gRPC in TLS
  RELAXED mode and the auto-cert path is finicky.
- `run-server.sh` is unchanged (JDWP on 8005); during diagnosis I briefly
  flipped it to 5005 for the MCP JDWP inspector and restored it before
  branching.
- The synthetic regression test is **not yet written**. Write it AS PART OF the
  fix commit, asserting both pre-fix failure and post-fix success.
