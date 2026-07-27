# evitaDB conflict-policy flaw: per-item `GRANULAR` is not a carve-out against coarse writers

**Audience:** an agent that will modify the **evitaDB** conflict-resolution logic (#503 feature).
**Author of this note:** analysis done from `evita_api` RC1 sources (`2026.2.RC1-SNAPSHOT`) while
investigating a production contention in EdeeShop. Everything below is source-verified against that
version; class/method names may have drifted — re-verify against `dev`.

---

## 1. TL;DR

A schema item declared with `ConflictResolutionOverride.GRANULAR` (or a dimension in an entity's
`GranularConflictPolicy` set) is documented to *"isolate concurrent writes that touch only this item
from writes touching **other parts of the same entity**."* It does **not** deliver that promise when
the "other parts" are written under the **coarse `ConflictPolicy.ENTITY`** policy (the default). A
coarse writer emits a single monolithic `EntityConflictKey(pk)` that, by the containment rules, is an
**ancestor of every finer key on that entity — including the granular item** — so the two transactions
falsely conflict even though they touched **disjoint** items.

Net effect: per-item `GRANULAR` only actually isolates an item when **all sibling parts the other
writer touches are also granular** (i.e. the whole entity opts into a granularity set). Declaring one
item granular while leaving the entity coarse is a silent no-op. That mismatch between the documented
contract and the behavior is the flaw to fix.

---

## 2. The real-world trigger (why this matters)

EdeeShop writes one evitaDB `Product` entity from **two independent, concurrently-scheduled jobs**:

- **Incremental indexer** — writes the authoritative product data: entity attributes (name/url/…),
  prices + price-inner-record-handling, references (categories/params/brand), localization associated
  data. Edits the existing entity in place → a diff `EntityUpsertMutation`.
- **Feed snippet job** — writes *only* `feed-<code>` **associated data** (a rendered export string,
  one per feed) and a single `snippetExpirationDateTime` **attribute**. Nothing else.

The two touch **disjoint** parts of the same PK, but they serialize against each other on the entity
key and stall ("ENTITY locks"). The eshop team wanted to declare just the feed items granular and keep
`Product` coarse — which is exactly the documented use-case of per-item `GRANULAR` — and found it has
no effect. The only workaround **without an evitaDB change** is to move the *whole* `Product` entity to
a full granularity set (`ENTITY_ATTRIBUTE, ASSOCIATED_DATA, PRICE, REFERENCE, REFERENCE_ATTRIBUTE`),
which needlessly relaxes serialization of the real product data too. We would prefer the surgical
carve-out to work as documented.

---

## 3. Minimal reproduction (pure evitaDB terms)

Schema: entity `Product`, **coarse** `ConflictPolicy.ENTITY`, **empty** granularity set (the default).
Associated data `feed-heureka` declared with `.withConflictResolutionOverride(GRANULAR)`.

Two concurrent transactions on the same PK, touching disjoint items:

| | Txn A (real data) | Txn B (feed data only) |
|---|---|---|
| mutation | `upsert Product#100 { attribute name = "X2" }` | `upsert Product#100 { associatedData feed-heureka = "…" }` |
| `collectConflictKeys` | `name` is non-granular under coarse policy → returns empty → `atLeastOneKeyMissing` → **`EntityConflictKey(Product,100)`** | `feed-heureka` has GRANULAR override → **`AssociatedDataConflictKey(Product,100,"feed-heureka")`** |

**Observed:** A and B conflict.
**Expected (per the `GRANULAR` contract):** no conflict — A touched only `name`, B touched only the
carved-out `feed-heureka`.

Why they conflict, precisely: `IncomingConflictScope.of({AssociatedDataConflictKey(100,"feed-heureka")})`
builds `coveredAncestors = { AssociatedDataConflictKey(100,"feed-heureka"), EntityConflictKey(100),
CollectionConflictKey(Product) }` (walking `parentConflictKey()`). The committed key
`EntityConflictKey(100)` is in that set → `conflictsWithAbsolute` returns true (the "committed contains
incoming" branch).

---

## 4. Current mechanism (exact source sites, `evita_api`)

1. **Per-mutation key emission** — each `LocalMutation.collectConflictKeys(ConflictGenerationContext)`
   returns its granular key **iff** `ctx.shouldEmit*Key(...)` is true, else `Stream.empty()`:
   `AssociatedDataMutation`, `AttributeMutation`, `PriceMutation` /
   `SetPriceInnerRecordHandlingMutation`, `ReferenceMutation` / `ReferenceAttributeMutation`,
   `ParentMutation`; `SetEntityScopeMutation` returns empty unconditionally.

2. **Coarse fallback** — `EntityMutation.getConflictKeyStream(...)`
   (`io.evitadb.api.requestResponse.data.mutation.EntityMutation`):
   ```
   for each local mutation: collect keys; if it produced NONE -> atLeastOneKeyMissing = true
   if (atLeastOneKeyMissing || expects == MUST_NOT_EXIST) and coarsePolicy == ENTITY:
       keys.add(new EntityConflictKey(type, pk))   // <-- the monolithic entity key
   ```
   **This is the heart of the flaw:** any single non-granular mutation makes the whole transaction emit
   one `EntityConflictKey(pk)`, and that key carries *no information about which items were actually
   touched or which items are declared granular*.

3. **Containment ancestry** — `ConflictKey.parentConflictKey()` is a schema-free record method.
   `AssociatedDataConflictKey.parentConflictKey()` (and `AttributeConflictKey`, `PriceConflictKey`,
   `ReferenceConflictKey`, …) **unconditionally** return `EntityConflictKey(type, pk)` as their parent —
   regardless of whether that item is declared granular.

4. **Matcher** — `IncomingConflictScope` (`…mutation.conflict`) does bidirectional containment:
   `coveredAncestors` = self + all `parentConflictKey()` ancestors of every incoming key;
   `conflictsWithAbsolute(committed)` is true if `committed ∈ coveredAncestors` (committed contains
   incoming) or any ancestor of `committed` is in the incoming `exact` set (incoming contains committed).
   Because step 3 makes `EntityConflictKey(pk)` an ancestor of the granular key, the coarse writer's
   entity key always matches.

5. **Effective policy resolution** — `EffectiveConflictResolutionResolver` (entity schema → catalog
   schema → engine default); per-item overrides applied at emit time in `ConflictGenerationContext`
   (`shouldEmitGranular`: item override `GRANULAR`/`ENTITY` wins over the inherited set). The context is
   schema-aware on the write path — **it already knows the per-item overrides and the granularity set**,
   so it *can* compute the carve-out set for the current entity.

---

## 5. Desired semantics (the requirement)

A **schema-declared-granular item** (per-item `GRANULAR` override, or a dimension in the entity's
granularity set) must be **carved out of the entity-wide conflict scope in both directions**:

- A transaction that touches **only** carved-out items must **not** conflict with a concurrent
  transaction that touches only **other** parts of the same entity (and vice versa).
- Equivalently: the coarse `EntityConflictKey(pk)` emitted for *policy coarseness* (some non-granular
  mutation fell back) must represent only the entity's **non-carved-out ("shared") surface** and must
  **not** be treated as an ancestor of a carved-out granular key.

This makes per-item `GRANULAR` behave as its Javadoc already promises, and lets a schema keep the strong
entity-level guarantee for its real data while surgically exempting a few independent items.

---

## 6. Correctness constraints any fix MUST preserve

1. **Same-item writers still serialize.** Two concurrent writers of `feed-heureka` → both emit
   `AssociatedDataConflictKey(pk,"feed-heureka")` → must still conflict. (Carve-out is per item, not
   "no detection".)
2. **The ENTITY guarantee holds for non-carved-out items.** Under coarse `ENTITY`, two writers touching
   *different* real attributes (e.g. `name` vs a price) must still conflict — the "shared surface"
   remains one mutually-conflicting scope.
3. **Genuine whole-entity operations still conflict with everything, including carved-out items.**
   Entity **creation** (`MUST_NOT_EXIST`), **removal** (`EntityRemoveMutation`), **scope change**
   (`SetEntityScopeMutation`), and price-inner-record-handling changes affect the entity's
   existence/identity — a concurrent write to a carved-out item (e.g. writing `feed-heureka` on an
   entity being removed/archived) MUST still conflict. So these need a "full-entity" key that contains
   everything; the carve-out applies only to the *policy-coarseness* residual key.
4. **Write path and recompute/historical path must agree.** Conflict keys are generated at WAL-write
   time and (per `EffectiveConflictResolutionResolver`) must be reproducible on recompute purely from
   schema. If the carve-out set is baked into the emitted key it stays consistent without the matcher
   needing schema access. Keep `IncomingConflictScope`/`parentConflictKey()` decidable without a live
   schema, or thread the schema consistently into both sites.
5. **`NONE` stays an opt-out** (emits nothing) and **`CATALOG`/`COLLECTION`** coarser scopes still
   contain everything (a catalog/collection write genuinely spans all items). Carve-outs apply only
   *within* `ConflictPolicy.ENTITY`.
6. **Commutative/delta keys** (`AttributeDeltaConflictKey`, `ReferenceAttributeDeltaConflictKey`,
   `conflictsWithCommutative`) must remain consistent with the new containment.

---

## 7. Suggested design direction (not prescriptive — you own the engine context)

**Split the single entity key into two distinct notions:**

- **`EntityResidualConflictKey(type, pk, excluded)`** — emitted for the *policy-coarseness* fallback
  (the `atLeastOneKeyMissing` case). It represents the entity's shared surface **minus** the carve-out
  set `excluded` (the granular dimensions + per-item-granular item identities resolved for this entity).
  Containment: it is an ancestor of a finer key **only if that key's item/dimension is NOT in
  `excluded`.** Two residual keys for the same pk still conflict with each other (constraint #2). Because
  `excluded` is derived from schema and identical on both write/recompute paths, baking it into the key
  keeps the matcher schema-free.

- **Full `EntityConflictKey(type, pk)`** — reserved for genuine whole-entity operations
  (creation `MUST_NOT_EXIST`, `EntityRemoveMutation`, `SetEntityScopeMutation`, and — if it must stay
  entity-scoped — inner-record-handling). It stays an ancestor of **every** finer key, carved-out or
  not (constraint #3).

Then a carved-out granular key's `parentConflictKey()` chain still reaches the **full** entity key
(so whole-entity ops match it), but the **residual** key is not on that chain (so a coarse data writer
does not). Work through the `IncomingConflictScope` matcher in both directions with this split.

Alternative, cheaper directions to weigh:
- **Always emit per-item keys** for item-scoped mutations even under coarse policy, and add an explicit
  "shared-surface" key that non-carved-out items contribute to. (This is essentially the residual key
  arrived at from the other side — but naively "always granular" breaks constraint #2, so the shared key
  is still needed.)
- **Documentation-only fix:** if delivering the carve-out is judged too costly, at minimum correct the
  `ConflictResolutionOverride.GRANULAR` / `GranularConflictPolicy.ASSOCIATED_DATA` (etc.) Javadoc to
  state that per-item granularity isolates an item **only against other writers that are themselves
  granular on the parts they touch** — i.e. it requires the sibling parts to opt into granularity too.
  The eshop team would then adopt the full-granularity workaround knowingly. (This is the fallback, not
  the preferred outcome.)

---

## 8. Code sites to touch (`evita_api`, package `io.evitadb.api.requestResponse…`)

- `data.mutation.EntityMutation#getConflictKeyStream(...)` — the coarse fallback; emit the residual vs.
  full key here based on *why* the fallback fired (policy coarseness vs. genuine whole-entity mutation)
  and attach the carve-out `excluded` set.
- `mutation.conflict.EntityConflictKey` (+ a new `EntityResidualConflictKey` or an `excluded` field) and
  the `parentConflictKey()` overrides on `AssociatedDataConflictKey` / `AttributeConflictKey` /
  `PriceConflictKey` / `PriceInnerRecordHandlingStrategyConflictKey` / `ReferenceConflictKey` /
  `ReferenceAttributeConflictKey` — make ancestry aware of carve-outs.
- `mutation.conflict.IncomingConflictScope#conflictsWithAbsolute/Commutative` — verify both containment
  directions under the split.
- `mutation.conflict.ConflictGenerationContext` — expose the resolved carve-out set for the entity in
  scope (it already computes `shouldEmit*`/per-item overrides), so `getConflictKeyStream` can attach it.
- Engine-side commit conflict loop that calls `IncomingConflictScope` (in the engine module, not shown
  in `evita_api` sources) — trace and re-verify.

---

## 9. Test matrix (all on one entity PK, coarse `ENTITY` policy, `feed-heureka` associated data =
per-item `GRANULAR`)

| Txn A touches | Txn B touches | Expected after fix |
|---|---|---|
| `name` attribute (non-granular) | `feed-heureka` (granular) | **no conflict** (the fix) |
| `feed-heureka` | `feed-heureka` | **conflict** (same item) |
| `name` | a price | **conflict** (both shared surface) |
| entity **removal** | `feed-heureka` | **conflict** (whole-entity op) |
| scope change LIVE→ARCHIVED | `feed-heureka` | **conflict** (whole-entity op) |
| entity **creation** (MUST_NOT_EXIST) | `feed-heureka` on same PK | **conflict** (creation is entity-wide) |
| `name` | `snippetExpiration` attr (also per-item GRANULAR) + `feed-heureka` | **no conflict** |
| catalog-wide write | `feed-heureka` | **conflict** (CATALOG spans all) |

Also add recompute/historical-path equivalence tests (write-time keys == recomputed keys) and a
commutative/delta case (a delta attribute vs. a granular associated-data write on the same entity).

---

## 10. Open questions for the evitaDB owner

1. Is per-item `GRANULAR` *intended* to work with coarse siblings (deliver the carve-out), or was it
   only ever meant as finer control **within** an already-granular entity? The Javadoc reads as the
   former; the implementation only supports the latter. Decide which is canonical, then fix code or docs
   accordingly (§7).
2. Should the carve-out also be expressible per-**dimension** at the entity level without moving the
   *whole* entity granular — e.g. "coarse ENTITY, but ASSOCIATED_DATA carved out" — or only via per-item
   overrides? (The eshop case is satisfied by per-item overrides alone.)
3. Confirm inner-record-handling belongs on the "full-entity" side (constraint #3) rather than the
   residual side.
