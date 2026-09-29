---
title: A referenceHaving body is a predicate about one reference row, evaluated by transposing the planned formula per reduced index
date: 2026-09-17
updated: 2026-09-29 15:10
status: partially-implemented
kind: fix
issues: [1585, 1644]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference, evita_engine/src/main/java/io/evitadb/core/query/algebra/reference, evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/bool, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/entity, evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java]
supersedes: []
superseded-by: []
relates: [2026-09-15-bidirectional-reference-counterpart-rewrite, 2026-09-08-conditional-histogram-per-contribution-verdicts, 2026-09-15-non-collapsible-formula-marker,
  2026-09-18-reference-planning-from-owner-membership, 2026-09-25-attribute-is-null-in-reference-having, 2026-09-28-indexed-reference-scope-requires-entity-component]
---

# A `referenceHaving` body binds one reference row, and is evaluated by transposing the planned formula per reduced index

`referenceHaving(R, body)` selects owners that hold **at least one row of `R` satisfying `body`** — the body
is a predicate about a single reference row, negation included. The engine used to evaluate the body once,
against the type-level index that pools every row of every owner, and only then intersect with owner sets.
That is a different question, and for four constraint shapes it returned a different answer; for a nested
`not` it returned the whole collection. The body is now rebuilt once per reduced entity index and evaluated
there, so every connective inside it — `and`, `or`, `not`, `entityHaving`, `groupHaving`,
`entityPrimaryKeyInSet` — speaks about the row that index holds.

## Why

Issue #1585 reported that a `not(...)` nested inside `referenceHaving(...)` is silently ignored. It is worse
than ignored: measured on a production retail corpus, `referenceHaving(Product.media, not(...))` returns
119,447 owners where at most 53,430 hold the reference at all — **2.2× more owners than possess it** — and
for three other references it returns the entire collection. No exception is raised. The caller gets a
plausible-looking answer.

The negation was only the visible end of it. One root cause — the body evaluated outside the per-index loop,
against structures that pool values across rows and across owners — produced six distinct defects, all
reachable from public evitaQL, and one of them needs no negation at all:

| # | shape | before | correct |
|---|---|---|---|
| D1 (#1585) | `referenceHaving(R, not(attributeEquals(a, v)))` | whole collection | row-scoped complement |
| D2 (#1584) | `referenceHaving(R, attributeIsNull(a))` | 0 | 154 |
| D3 | `referenceHaving(R, or(entityPrimaryKeyInSet(5), attributeEquals(rel, 1)))` | 46 | 69 |
| D4 | `referenceHaving(R, not(entityPrimaryKeyInSet(5)))` | internal error | 217 |
| D5 | `referenceHaving(R, not(groupHaving(g)))` | 0 | 13 |
| D6 | `referenceHaving(R, and(attrA = 1, attrB = 2))` — **no negation** | `[1, 2]` | `[2]` |

D6 also made one response self-contradicting: the owner came back as a match while its list of matching
references was empty, because the *fetch* side was already per-row while the *filter* side was not.

The constraint that made this non-obvious is that `referenceHaving`'s semantics had never been written down.
The issue text itself asserts the owner-scoped reading, and a `@Disabled` test encoded it. Choosing between
the two readings had to come before any code.

### Previous state

A `referenceHaving(R, φ)` is answered from the **reduced entity index** family of `R` — one index per
`(referenceName, targetPk, representativeAttributeValues[])`, each holding the owners that reference that
target. Index *selection* narrowed the family by evaluating `φ` against `ReferencedTypeEntityIndex`, the
type-level index that pools the rows of the whole reference, and execution then worked with whole owner
sets. Two consequences followed, and both were invisible on ordinary fixtures:

- A conjunction of two reference attributes was satisfied by an owner holding each conjunct **on a different
  row**, because the type-level index says only "some row of this reference carries `a = 1`".
- A negation was complemented against the owner collection rather than inside a row. `ReferencedTypeEntityIndex`
  can answer "which reduced indexes hold at least one row matching `x`"; it cannot answer the negation of
  that soundly, because `EXISTS(¬x)` is not `¬EXISTS(x)`.

Ordinary fixtures hide this: for most references every row of one owner carries the same attribute values,
derived from the owning entity, so the pooled reading and the row reading coincide. It takes a reference
whose row attributes vary **within one owner** to tell them apart, and the test fixture had none.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-09-16 | **Row-scoped semantics**: `referenceHaving(R, not(φ))` is `∃r : ¬φ(r)`, not `¬∃r : φ(r)` | The only reading under which the body is a formula at all, and the only one that can express a universal quantifier over an owner's rows | `row-scoped-semantics.md` §3, §6 |
| 2026-09-17 | **Transpose the planned formula tree per index**, rather than write a second, index-local translator set | The body admits any filter constraint; a parallel translator family would have to mirror the whole registry and would drift from it | `ReferenceBodyTransposer` |
| 2026-09-17 | **A negation widens to the super set only when its consumer re-resolves it per row**, carried on the processing scope | The index type says what is being read, never whether anyone re-evaluates the result — deriving it from the type broke facet filtering | `ProcessingScope#negationResolvedPerRow` |
| 2026-09-17 | **Every per-index formula carries an explicit per-index identity** | Three separate memoization layers otherwise collapse N per-index nodes into one, silently and with no error | C1 below |
| 2026-09-17 | **An untagged subtree is index-independent and is kept for every index**, never treated as `∅` | Several producers legitimately emit index-independent leaves; treating them as empty would delete them | `ReferenceBodyTransposer#project` |
| 2026-09-17 | **A body combined only by union skips the per-index rebuild entirely** | The rebuild is quadratic in the index family, and the projection of such a body reproduces it exactly — `∃` distributes over `∨` | `ReferenceBodyTransposer#combinedOnlyByUnion` |
| 2026-09-17 | **The fetch path translates `entityPrimaryKeyInSet` rather than suppressing it** | Suppression leaves a nested `not` nothing to negate; discovery cannot answer a negated leaf, which widens rather than narrows | `ReferencedEntityFetcher#computeResultWithPassedIndex` |
| 2026-09-17 | **The `⊤` residue is supplied by (b) persisted per-owner row counts **and** (c) the counterpart rewrite**, with (a) as fallback | Priced on production data; (c) lands first because it needs no format change | table below |
| 2026-09-17 | **(c), first half: a negated reference attribute is answered from the counterpart end** | The counterpart's per-owner index is row-exact, so the complement is taken inside a set the rewrite already builds — no new structure | `BidirectionalReferenceRewriter#createPerOwnerFormulas` |
| 2026-09-29 | **An `entityHaving` verdict is settled per index at planning time**, as membership of the index's target in the nested result computed once; a matching index contributes its own owner bitmap, a non-matching one nothing | A per-index formula embedding the shared nested filter cost N·M at execution and copied the filter's transactional ids into every one of N formulas — 16–18 s and an `OutOfMemoryError` on a 32 GiB heap in production | "Per-index cost" below |
| 2026-09-29 | **In candidate mode, `groupHaving` narrows the candidate indexes** to those of the targets the matching groups' reduced group indexes hold; strict mode keeps the whole family | The value condition alone selects every group sharing the attribute (9,358 indexes where 833 are heights), and every later per-index step multiplies that count | `HavingTranslatorHelper#groupNarrowedReducedIndexPksFormula` |
| 2026-09-29 | **Each `or` node's children are split by tag once per rebuild**, and a projection reads only its own index's children | Visiting every child of every `or` once per index made the rebuild quadratic — 25.6 s at 32,000 indexes; the split keeps the survivors and their order exactly as before | `ReferenceBodyTransposer#projectDisjunction` |
| 2026-09-29 | **A `groupHaving` row is answered through a per-plan lookup of the matching groups' reduced group indexes, bucketed by (scope, representative values)**, keeping the per-index direction | The fetch path evaluates the branch once per index with a single-index scope, and a broad group filter can match 100k groups — enumerating the groups' targets instead would be worse than the scan it replaces | `HavingTranslatorHelper.GroupRowLookup` |

### Row-scoped over owner-scoped

Under the owner-scoped reading, `referenceHaving(R, not(φ))` would be exactly `not(referenceHaving(R, φ))`
and the nested `not` would be redundant — the language would offer two spellings of one query and none of
the other. Four arguments settled it, in increasing order of weight:

1. `documentation/user/en/query/filtering/references.md` already states the contract: the constraints must
   be *"satisfied by **one of** the entity references"*, and `referenceHaving` *"is similar to SQL `EXISTS`"*.
   `EXISTS (... WHERE NOT x)` is `∃r : ¬x(r)`.
2. The container demonstrably binds a row for `and`. If `not` were owner-scoped, `and(φ, not(ψ))` would mix
   two quantifier scopes inside one connective and would have no coherent meaning.
3. Row-scoping is strictly **more expressive**. Of the four owner shapes an owner can take with respect to
   `φ` — no rows, all rows match, mixed, none match — the owner-scoped reading cannot separate "all rows
   match" from "mixed" by any stack of connectives outside the container.
4. The engine already answers the row-scoped question on the fetch side, which is what made D6's response
   contradict itself.

**Consequence accepted deliberately:** the issue's own expectations were rewritten rather than re-enabled,
and `not(attributeEquals(a, v))` is *not* "has a different value" — a row not carrying `a` satisfies the
negation, because evitaDB has no three-valued logic and absence is an ordinary value. The spelling for
"present and different" is `and(attributeIsNotNull(a), not(attributeEquals(a, v)))`. This diverges from SQL
and is pre-existing at the entity level; it is kept, and it needs to reach the user documentation.

### The `⊤` residue — priced, not settled

When a body cannot narrow index discovery — a bare `referenceHaving(R)`, or a negation — the candidate set
is the whole index family, and the identity needs a residue term for owners whose only rows lie outside it.
Three ways to supply it, all measured on the production corpus rather than argued:

| option | cost | consequence |
|---|---|---|
| (a) `⊤` = load the family | 204 ms per negation on `Product.media`, 222 ms on `ParameterValue.products` | a negation costs what a bare `referenceHaving(R)` costs today |
| (b) per-(owner, reference) row counts + owner bitmap | new persisted structure: write path, Kryo, backward compatibility — but **0.12–0.21 %** of the 4.92 GB of reduced-index heap it accelerates | restores the original cost story |
| (c) extend `BidirectionalReferenceRewriter` to `not`/`and` | reuses a per-owner counterpart index that is already row-exact; ≈60× where it fires | bounded path for the large-catalogue case, narrow applicability |

**Decided: (b) and (c) together**, with (a) as the honest fallback. Correctness was never at stake in this
choice — that comes from the per-index loop, which is implemented; what (a) alone would cost is only the
claim that a negation is as cheap as its positive.

(c) is **half done**. A negated reference *attribute* is now answered from the counterpart end
(`BidirectionalReferenceRewriter#createPerOwnerFormulas`): the counterpart's reduced indexes for one owner
hold only that owner's rows and a referenced entity appears in exactly one of them, so `∃r ¬A(r)` is
`rowsOf(o) \ matching(A) ≠ ∅` — a complement inside a set the rewrite already materialises as its
no-constraint answer. `not(not(…))`, `not(and(…))` and `not(entityHaving(…))` still decline; the last of
those is a complement against the referenced collection rather than against one owner's rows, and is the
part of (c) still outstanding. Widening the rewrite to a conjunction of attribute siblings is a separate
question — see the follow-ups.

**Where it can fire matters.** `preparePlanInternal` declines any body carrying an attribute constraint once
an index outside the requested scopes has announced an owner, so the extension is reachable on
default-scope (`{LIVE}`) schemas and declines on dual-scope ones. That guard is pre-existing and was not
touched; it is why the `BIDI_REWRITE` fixture needed a LIVE-only reference before the new path could be
observed at all.

(b), the persisted per-(owner, reference) row counter, is not built. Its `⊤` and its `rowCount_R(o)` are one
structure rather than two, because the counter's key set **is** the owner bitmap.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Reuse `suppressedConstraints` to suppress `Not` during index discovery | Suppression **drops** the branch rather than widening it, so `or(a, not(b))` would narrow to `a` where it must widen to everything — a wrong answer, not a slow one | Never; the mechanism is the wrong shape for the job |
| Derive the widening decision from the index type (`ReferencedTypeEntityIndex`) | The index type says what is being read, not whether the reader re-evaluates it. `facetHaving` reads a type-level formula as its **final answer**, so it needs a real subtraction; the type-based test dropped the negation there and returned 230 products where 210 are correct | Never; the decision belongs to the consumer and is now carried by the scope |
| Count rows per owner from `ReferenceTypeCardinalityIndex` | `ReferenceIndexMutator:778-780` passes the **reduced index's** primary key, not the owner's, so `pack(pk, 0)` counts rows per reduced index — the per-target count discovery is already built on, and the opposite of what a counting complement needs. No per-(owner, reference) counter exists anywhere | Only if such a counter is introduced deliberately — that is option (b), with its own write-path and format cost |
| Use `ReducedIndexMembership` as that row count | It maps owner → index primary keys only for *covered* indexes, is derived rather than persisted, and declares itself "accelerator, never authority". Its permitted omissions would become wrong answers | Never; it would convert a documented approximation into a correctness dependency |
| A lazy per-index membership formula (`compute = nested.compute().contains(target) ? A_i : ∅`) instead of deciding at planning time | Fixes the N·M scan but not the memory: to keep cache invalidation sound the leaf must declare the shared nested filter as an inner formula, so each of N leaves still copies its k transactional ids, and every per-index `And_i` the transposer builds copies them again | If transactional-id aggregation stops copying per node |
| De-duplicating child id arrays in `AbstractFormula#gatherBitmapIdsInternal` | Removes only the concatenation spike; the N retained k-sized arrays and the N·M scan stay. Treats in a class every formula shares a shape one translator creates | Never as the fix for this; possibly on its own merit |
| A dedicated evaluator for `and(entityHaving, groupHaving)` (the `histogramHaving` rewrite): matching groups → their targets ∩ range → owners | Fastest for that one shape, but a second implementation of row semantics that must agree with the transposer forever; group narrowing gets the same shape to 30–70 ms through the generic path | If a shape the narrowing cannot shrink shows up in production at scale |

## Key technical details

**The transpose.** `ReferenceHavingTranslator` hands the planned body to `ReferenceBodyTransposer#transpose`,
which rebuilds it once per reduced index. Leaves that are index-specific are wrapped in `IndexTaggedFormula`
at the moment they are produced — `FilterByVisitor#tagWithProducingIndex`, attached in `applyOnIndexes`,
`applyStreamOnIndexes` and `applyOnFirstUniqueIndex`, which are the complete set of per-entity-index helpers
because the filter-index variants delegate to the first two. The catalog-level global unique lookups are not
among them: a global attribute is never resolved inside a reference body. `project` keeps each index's own tagged leaf and drops its siblings.

**Tagging is mandatory, and its absence is silent.** `project` returns any untagged node whole, for every
index — the deliberate conservatism above. An index-local formula that forgets its tag is therefore not
dropped, it is *shared*, which reads as a correct answer on any fixture where the rows of one owner agree.

**A negation is resolved inside the index**, against that index's own super set, via
`FutureNotFormula.postProcess(formulas, CONJUNCTION, …)`. `CONJUNCTION` is load-bearing: under `DISJUNCTION`
the third case re-emits a `FutureNotFormula` even when a super-set supplier is present, and the placeholder
then reaches `compute()` and throws.

**Per-index identity (C1).** A per-index formula needs per-index identity in *every* memoization it passes
through, and every failure is silent — it collapses N nodes into one. The `IndexTaggedFormula` hash carries the
index primary key. The `entityHaving` and `groupHaving` contributions used to also need it in the
`computeOnlyOnce` key and in a `ReferenceOwnerTranslatingFormula` expander discriminator; since 2026-09-29 both
are a `ConstantFormula` over a bitmap owned by the index itself, whose hash is that bitmap's transactional id,
so the discriminator was removed.

**Per-index cost: a per-index leaf must never embed a formula every index shares.** Each per-index formula
declaring the shared nested filter as its inner formula made the body cost N·M at execution (every leaf scans
the whole nested result for its one target) and N·k in memory (every leaf, and every enclosing `or`, copies the
filter's k transactional ids). Where the verdict is constant within an index — `entityHaving`, keyed by the
index's target — it is settled during planning against the nested result computed once
(`planNestedQuery` hands the filter out initialised through `computeOnlyOnce`), and the membership decision
lives in the *shape* of the tree: a changed nested result yields a different tree and can never be served a
stale cache hit. An index whose target does not match is not tagged at all; `ReferenceBodyTransposer` reads an
absent tag exactly as an empty one — dropped under `or`, emptying the row under `and`, and the index's whole
owner set under a negation.

**Candidate narrowing by group reads the type index first.** `groupNarrowedReducedIndexPksFormula` calls
`getAllReferencedPrimaryKeys()` on the discovery index unconditionally, because a reference not indexed in the
scope is represented by a throwing stub and that read is what raises its `ReferenceNotIndexedException`. It
returns `EmptyFormula` for an empty set — `ConstantFormula` refuses an empty bitmap — and falls back to the whole
family when the matching groups hold at least as many targets as the family, so a broad group filter never costs
more than before. A `groupHaving` matching no group now empties the candidate set already in index selection:
same answer, earlier.

**The reduced index holds at most one row per owner, and the per-index complement is exact only because of
it.** Projection does not distribute over set difference, so `owners(index) \ owners(σ_φ(index))` equals
`owners(σ_¬φ(index))` *iff* no owner holds two rows inside one index. That is enforced at write time —
`BuilderReferenceBundle#upsertDuplicateReference` refuses a duplicate with the same representative
attributes, and `EntityIndexKey`'s constructor rejects any discriminator that is not a
`RepresentativeReferenceKey` for `REFERENCED_ENTITY`. **Group** reduced indexes drop the target primary key
from the key and are therefore *not* injective; nothing in this work complements across them.

**`ReferencedTypeEntityIndex` stores reduced index primary keys, not referenced entity primary keys.** Its
javadoc claimed the opposite and cost two reviewers an afternoon each. `FilterByVisitor#getReferencedRecordIdFormula`
translates them through `AbstractReducedEntityIndex#getReferenceKey()` before handing them to a caller
that wants referenced entity primary keys, which is the step that looks pointless under the wrong reading.

**`isAssignableFrom` reads backwards from the intuition.** `ReducedEntityIndex.class.isAssignableFrom(AbstractReducedEntityIndex.class)`
is **false** — the argument is the superclass. `HavingTranslatorHelper`'s guard names
`AbstractReducedEntityIndex` deliberately: `ReferenceHavingTranslator` declares its scope as
`ReducedEntityIndex`, but `ReferencedEntityFetcher#computeResultWithPassedIndex` declares the superclass, and
narrowing the guard reopens the defect on the fetch path only.

**`not(not(x))` is collapsed in `NotTranslator`**, at the only level where both placeholders are still
visible. Nothing above unwraps a pair, so the inner one used to reach `compute()` and throw — at the top
level too, with no reference constraint involved.

## Verification

Two functional classes over the shared `BIDI_REWRITE` dataset:

- `ReferenceHavingRowSemanticsFunctionalTest` — 20 tests, one per analyzed shape, no bundling.
- `ReferenceHavingRowSemanticsSweepFunctionalTest` — 34 body shapes × 5 plan configurations, asserting each
  against an oracle derived from entity bodies rather than from the queries under test.

The fixture gained two references whose row attributes vary **within one owner**, which no pre-existing
reference did: `PRODUCT.crossRowCategories` (non-representative `tier`, `mark`) and `PRODUCT.groupedCategories`
(reference groups plus a non-representative `grade`). Each carries a decoy row that keeps a second index in
scope through the type-level pass — without it the candidate set narrows to one index, the body is answered
inside it, and the two readings cannot differ.

**Every guard was proven capable of failing.** One counterfactual run broke three of them at once and
produced exactly the three predicted failures:

| guard broken | test that went red | symptom |
|---|---|---|
| widening moved back onto the index type | `shouldKeepTheNegationInsideFacetHaving` | 230 products where 210 are correct — every product carrying any brand |
| double-negation collapse removed | `shouldResolveADoublyNegatedBody` and the sweep's top-level row | `FutureNotFormula is only temporary placeholder!` |
| per-index `entityHaving` branch disabled | `shouldComplementEntityHavingAgainstTheReferenceRow` | `[1, 3]` → `[3]` |
| `IndexTaggedFormula` removed from the `groupHaving` contribution | `shouldBindGroupHavingAndAttributeToTheSameRow` | `[1]` where `[]` is correct |
| `IndexTaggedFormula` removed from the `groupHaving` contribution | `shouldComplementGroupHavingAgainstTheReferenceRow` | `[3]` where `[1, 3]` is correct |
| counterpart rewrite's complement disabled | `shouldRewriteANegatedReferenceAttribute` | `3, 6, 9` — the *positive* query's answer — where `1, 2, 4, 5, 7, 8, 10` is correct |
| fetch-path dispatch reverted | `shouldComplementEntityHavingPerRowWhenFilteringReferenceContent` | `{1=[], 2=[], 3=[2]}` where `{1=[2], 2=[], 3=[2]}` is correct |

Four rows pin the fetch path, which reaches its per-index evaluation by a different route than the filter
path does: `shouldComplementEntityHavingPerRowWhenFilteringReferenceContent` (the table row above),
`shouldComplementEntityPrimaryKeyInSetPerRowWhenFilteringReferenceContent` (which fails with an internal error
rather than a wrong answer when the fetcher suppresses the constraint) and the two
`...GroupHavingPerRowWhenFilteringReferenceContent` rows.

Suite state on the combined tree: `-Dgroups="reference | facet"` → **3,376 run, 0 failures, 0 errors**; full
`unitAndFunctional` → **24,349 run, 0 failures, 1 error** (`ExportS3ServiceTest`, which needs Docker and
cannot pass in this environment).

Production-corpus numbers, and the harness traps that produced them, are in
`production-corpus-measurements.md`.

**Per-index cost (2026-09-29).** Restored production catalog (2026-09-09 backup), 20 GiB heap, query cache off,
five runs each, no debugger attached:

| query | v2026.2.16 (pooled, wrong) | v2026.2.17 | fixed |
|---|---|---|---|
| two sliders under a category — `sirka` 59–150 ∧ `vyska` 3.2–121.2 | 0.10–0.20 s, 898 | 16–18 s, 170 | **0.050–0.069 s, 170** |
| one slider, `vyska` 0–1000 | 0.11 s | `OutOfMemoryError` | **0.035–0.045 s** |
| the #1644 reporter's `referenceHaving` | 0.40 s | 0.03 s | 0.007–0.013 s |

Candidate indexes after group narrowing (JDWP logpoint on the transposer): 1,920 → 291, 9,358 → 833,
21,229 → 1,055, 239 → 31; transactional ids per per-index formula 9,894 → 2. `dev` before the fix answered the
same queries in 1.6–1.9 s and 4.2 s — it escaped the `OutOfMemoryError` only because its range formulas report
one transactional id per sorted-array page (#1486), not because the N·M scan was absent. With the fix `dev`
answers them in 0.054–0.107 s and 0.030–0.056 s.

`HistogramHavingMultiGroupFunctionalTest` (19 tests) pins the shape: several groups sharing one value
attribute, two sliders at once, ranges dominated by other groups, group sets, negations in every position,
archived owners and targets, duplicate rows told apart by a representative attribute, and the fetch path —
each against an oracle derived from the fixture's row model, on every alternative plan and caching tree. It
passes on v2026.2.17 and on the fix, and fails 13 of 19 on v2026.2.16, which is the pooled reading.

**Linear rebuild (2026-09-29).** `ReferenceBodyTransposerTest` rebuilds a conjunctive body over 32,000 indexes
in about 0.2 s under a 5 s bound; the previous algorithm took 25.6 s there and fails it. On the restored catalog,
warm: `entityHaving(0..1000)` with `not(groupHaving(vyska))` 0.54 s → 0.12 s, and two `entityHaving` value
conditions 0.72 s → 0.09 s, same answers.

## Consequences & open follow-ups

**A negated body walks the whole index family, and the rebuild was quadratic in it.** `transpose` walked the
entire body once per index, and the body carries one contribution per index. Measured on a synthetic body:
5.42 ms at 1,000 indexes, 223.69 ms at 8,000, and 10.9 s at 32,000 for a conjunctive shape - quadratic over a
32x range. `Product.media` has 169,102 indexes.

A **fast path** now removes it for the shapes that need no rebuild at all. A body whose per-index contributions
meet only under `or` answers the same question before and after the transpose, because the existential
distributes over disjunction - so it is returned as the visitor built it. That is a single leaf or a flat `or`
of leaves: the overwhelmingly common reference body, and the only shape `BidirectionalReferenceRewriter`
accepts. Measured at the same sizes: 0.27 ms, 0.87 ms, 4.45 ms - linear, and 257x faster at 8,000 indexes.
See `ReferenceBodyTransposer#combinedOnlyByUnion`. **Correction (2026-09-25):** in the engine this held for no
attribute leaf until `2026-09-25-attribute-is-null-in-reference-having` taught the check to look through
`AttributeFormula` - before that a plain `referenceHaving(Product.media, attributeIsNotNull(a))` took the
quadratic rebuild and measured 281 s per query.

What the fast path does **not** remove is the rebuild for a conjunctive or negated body. Each projection used
to walk the whole body, visiting every child of the `or` nodes that hold one contribution per index, so the
rebuild was quadratic; since 2026-09-29 each `or` node's children are split by tag once and a projection reads
only its own index's children, which makes it linear (see the per-index cost item below). A negated body still
projects once per index of the whole family, because an index contributing nothing to a negated leaf
contributes all its owners. Removing *that* needs a **compositional candidate set plus the residue term**, so
the loop visits only the indexes that can contribute. **Blocked on the residue decision above** - as written it
would be implemented against a per-owner row count the engine does not have.

**The per-index `entityHaving` branch did N·M expander calls where N membership tests would do — resolved
2026-09-29 (#1644).** It was counted the hard way: v2026.2.17 shipped it to a production storefront, where a
category listing with two `histogramHaving` sliders took 16–18 s against a 2 s budget and a wide slider threw
`OutOfMemoryError` on a 32 GiB heap. `basicUnitValue` is shared by every parameter type, so the value range
selected 9,358 candidate indexes (11,448 matching values, 9,893 transactional ids in the range filter) for a
group holding 833 of them. Fixed by the three 2026-09-29 decisions above; measured on the restored catalog it
is now faster than before the row-scoped reading existed (see Verification). What is **still open**:

The transposer's rebuild of a conjunctive body **was quadratic in N** wherever the group cannot narrow the
candidates — a negated group, or two conditions on the referenced entity, keep N at the whole range. Fixed the
same day: each `or` node's children are split by tag once per rebuild (`ReferenceBodyTransposer#projectDisjunction`,
`OrChildren`), so a projection reads its own index's children plus the untagged ones and the rebuild is
O(tagged leaves + N × untagged nodes). The survivors, their order and their combination are exactly what visiting
every child produced; `ReferenceBodyTransposerTest` keeps the previous algorithm as the oracle of a differential
test over 2,000 random bodies. What is **still open**:

- **The `groupHaving` lookup walks per index, never from the groups' side.** Enumerating the matching groups'
  targets is cheaper when N and the group count are both large, which group narrowing cannot shrink either;
  it was declined because it is worse for the fetch path (N = 1 per call) and for broad group filters.

**The fetch side now runs the same adapters, and doing so fixed a crash.** `ReferencedEntityFetcher
#computeResultWithPassedIndex` used to suppress `EntityPrimaryKeyInSet` while translating a reference body,
on the premise that index discovery had already applied it. That premise fails under a negation, which widens
the candidate set rather than narrowing it: `referenceContent(R, filterBy(not(entityPrimaryKeyInSet(X))))`
handed `NotTranslator` nothing to negate and failed its premise check outright - a pre-existing internal error
on a plain public query. The constraint is no longer suppressed, and
`EntityPrimaryKeyInSetTranslator`'s guard names `AbstractReducedEntityIndex` so it actually fires in that
scope. Still not done: making a *composite* body row-scoped on the fetch path.

**The counterpart rewrite now answers a negated reference attribute, and the fixture had to earn it.** Every
reference in `BIDI_REWRITE` bar one is indexed in both scopes, and `preparePlanInternal` declines any body
carrying an attribute constraint once an index outside the requested scopes has announced an owner - so no
attribute-bearing body reached the rewrite there at all, negated or not, and the obvious witness
(`shouldNotRewriteWhenNotIsNestedInsideReferenceHaving`) could not observe the change: it passed unaltered
because it never reached the code. `CATEGORY.scopedProducts` is the one reference whose owner end is LIVE-only,
so `counterpartScopes` collapses to the requested scope and the guard cannot fire; it gained a filterable,
non-representative `scopedGrade`, constant across a category's whole product block except for one row carrying
a value nothing else carries. With the complement disabled, `not(scopedGrade == 0)` answers `3, 6, 9` - the
*positive* query's answer - where `1, 2, 4, 5, 7, 8, 10` is correct.

Two general lessons, both paid for here. A precondition matrix over `isApplicable` cannot see whether the
formula it admits is *computed* correctly: the four unit rows would have passed unchanged with the complement
never applied. And before nominating an existing test as a witness, check what its **positive sibling**
asserts - had that been done, the cross-scope guard would have been obvious an hour earlier.

**A double negation inside a discovery scope widens twice.** The inner `not` has already widened to the
super set, so the pair is not visible to the collapse and the outer `not` widens again. Sound — widening a
candidate set never loses a row — but imprecise.

**Extending the rewrite to a conjunction of attribute siblings is open, and looks reachable.** `splitChildren`
declines two attribute children, and the exclusion is marked in its own javadoc as *conservative rather than
proven necessary* - its originally recorded reason was measured wrong in 2026-09-14. The argument for lifting
it: a referenced entity appears in exactly one of an owner's counterpart reduced indexes, because the index key
carries the representative-value tuple and a row has exactly one, so `or_i(A_i) ∧ or_j(B_j)` can only be
satisfied on a single row. Not implemented, and it wants its own witness rather than riding on the negation's.

**`not(entityHaving(…))` is the outstanding half of (c).** It is a complement against the referenced
collection rather than against one owner's rows: `∃r : target(r) ∉ S` is `allTargets(o) ∩ (allInScope \ S)
≠ ∅`, which `ReferencedOwnerExistenceFormula` can express by complementing the *shared* narrowing formula
instead of the per-owner one. The scope question is the awkward part - the bare branch deliberately spans
every scope a counterpart row can live in, and a complement has to be taken against exactly that set.

**#1584 was not fixed by this work; `2026-09-25-attribute-is-null-in-reference-having` fixed it.** At type level
`attributeIsNull(a)` means "no row in this index carries `a`", a strict *subset* of the indexes that can
contribute. It now widens during discovery the way `not` does, without waiting for the compositional candidate
set, and its per-index leaves are tagged. That record also found that the fast path below never applied to an
attribute leaf, because every attribute translator wraps its contributions in an `AttributeFormula`; it now
looks through the wrapper.

**Three planning levers, adjacent to this issue rather than part of it.** The measurement found that the
dominant cost is index *selection*, not execution — every probed query walked the whole family only to reject
the reduced-index plan and run the global one. Memoizing the target-index list, keeping one `long` per
(reference, scope) for the eligibility sum, and keeping a per-(reference, scope) owner bitmap remove that
cost. They are a query-planning fix with their own scope and should ride as separate commits or a separate
issue.

**A fixture can be blind in a way a green suite cannot show you, and this work has the worked example.**
`groupHaving`'s index-local contributions were missing their `IndexTaggedFormula`, so the group conjunct did
not constrain the row it belonged to. The first fixture built to catch it declared a group *type* but not the
indexed *component* `REFERENCED_GROUP_ENTITY`, so no `ReducedGroupEntityIndex` existed at all, every
`groupHaving` answered empty, and `not` of empty is everything -- which happens to equal the correct answer for
both shapes that fixture could express. Both guards passed, and a counterfactual on them moved, because
disabling the branch routes to a *different* wrong answer. The defect was real all along and was found only by
printing what the expander actually resolved. Two things worth carrying: a `groupHaving` against a reference
whose schema omits `REFERENCED_GROUP_ENTITY` silently answers empty rather than failing -- the schema layer
already throws for the analogous bucketed-histogram case (`ReferenceSchema:1163`), so there is precedent for
making it loud -- and a guard whose expected value coincides with the degenerate answer proves nothing, however
convincingly its counterfactual moves.

**That silence is now a refusal.** `GroupHavingTranslator` asks
`HavingTranslatorHelper#assertGroupComponentIndexed` whether any queried scope carries
`REFERENCED_GROUP_ENTITY`, and raises `EvitaInvalidUsageException` naming the reference, the queried scopes and
the schema setting when none does. Measured on a two-product fixture before the guard existed:
`not(groupHaving(entityPrimaryKeyInSet(g)))` answered `[1, 2]` -- every live product -- where `[2]` is correct.
The check passed as soon as **one** queried scope carried the component; `2026-09-28-indexed-reference-scope-requires-entity-component`
tightened it to **every** queried scope that indexes the reference, because a union silently missing one scope's
rows looks exactly like a complete answer. It stays
silent when the reference is indexed in no queried scope, deferring to the `ReferenceNotIndexedException` the
throwing stub from `ReferencedTypeEntityIndex#createThrowingStub` already raises with a better message.

**There is deliberately no `entityHaving` counterpart, and the reason is a second defect.** Such a guard could
never fire: when no queried scope carries `REFERENCED_ENTITY` there is no reduced entity index in any of them,
and `referenceHaving` resolves to an empty result *before* its body is translated. The short-circuit itself is
wrong -- a reference indexed for `REFERENCED_GROUP_ENTITY` alone answers even `groupHaving` with nothing, the
one constraint that component exists to serve (measured: schema `LIVE=[REFERENCED_GROUP_ENTITY]`, an owner
carrying the matching group, result `[]` where `[1]` is correct). The cut is
`IndexSelectionResult#isEmpty`, which treats an empty candidate index set as proof that the reference has no
rows -- true when nothing matches, false when the rows are indexed in a family index selection never consults.
It lives in index selection rather than in translation, it is not a small fix, and it was left untouched
rather than papered over by a guard that cannot be reached. Filed as **#1601**, which shares that shortcut
with **#1583** from the opposite side: there the index is genuinely never built, here it exists and is not
looked at. **Resolved** by `2026-09-28-indexed-reference-scope-requires-entity-component`: the group family was not
made to answer - every indexed scope must carry `REFERENCED_ENTITY`, and a stored catalog lacking it refuses every
query that needs it - and #1583 turned out to be the reflected-reference builder producing the same shape.

**User documentation is not yet updated.** It must state the row-scoped rule and that `⊥` is an ordinary
value for reference attributes — `not(attributeEquals(a, v))` matching a row that does not carry `a` is
surprising enough to be worth saying explicitly.

## Related work

- `2026-09-15-bidirectional-reference-counterpart-rewrite` — the work that filed #1585 as pre-existing and
  pinned it with a `@Disabled` row; this record closes that follow-up. Its rewriter is also the mechanism
  option (c) would extend, and the `BIDI_REWRITE` fixture both records share is where the row-varying
  references were added.
- `2026-09-08-conditional-histogram-per-contribution-verdicts` — settled the same question one layer down: a
  cross-entity condition is answered per `(referencedEntity, owner)` contribution, not per owner. The reading
  this record makes the filter side obey is the one that record already established for indexing.
- `2026-09-15-non-collapsible-formula-marker` — shares the formula-tree area, and is the reason a marker on a
  formula is the accepted way to carry information a later pass reads from the tree's *shape*.

## Supporting material

- `row-scoped-semantics.md` — the formal model: what a reference row is, why absence is an ordinary value,
  why per-index evaluation is licensed, the four-block partition that proves row-scoping is more expressive,
  and the twelve identities a future change to this area must still satisfy.
- `production-corpus-measurements.md` — family sizes, latencies and reduced-index heap measured on a restored
  production retail catalog, which is what prices the open `⊤` decision. It cannot be regenerated without
  that catalog.

## Timeline

- **2026-09-15** — #1585 filed as pre-existing during the bidirectional-rewrite work, pinned by a `@Disabled`
  test asserting the owner-scoped reading
- **2026-09-16** — semantics written down and reviewed by two independent advisors; both endorsed the
  row-scoped reading; the `@Disabled` test's expectations rewritten rather than re-enabled
- **2026-09-17** — implemented; measured on the production retail corpus; two adversarial review rounds
  produced nine findings, seven fixed, one refuted by execution, one left open and named above
- **2026-09-25** — backported to 2026.2 as v2026.2.17 (#1646, for #1644)
- **2026-09-29** — v2026.2.17 timed out and ran out of memory on a production storefront; the per-index cost
  was fixed (plan-time `entityHaving` membership, group-narrowed candidates, per-plan group lookup, a linear
  transposer rebuild) in the 2026.2.18 hotfix and on `dev`; the design was reviewed by two independent reviewers before implementation,
  who found one blocker (an empty `ConstantFormula`) and one lost `ReferenceNotIndexedException`, both fixed
