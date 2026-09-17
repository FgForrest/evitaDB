---
title: A referenceHaving body is a predicate about one reference row, evaluated by transposing the planned formula per reduced index
date: 2026-09-17
updated: 2026-09-17 13:55
status: partially-implemented
kind: fix
issues: [1585]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/query/filter/translator/reference, evita_engine/src/main/java/io/evitadb/core/query/algebra/reference, evita_engine/src/main/java/io/evitadb/core/query/filter/FilterByVisitor.java, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/bool, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/entity, evita_engine/src/main/java/io/evitadb/index/ReferencedTypeEntityIndex.java]
supersedes: []
superseded-by: []
relates: [2026-09-15-bidirectional-reference-counterpart-rewrite, 2026-09-08-conditional-histogram-per-contribution-verdicts, 2026-09-15-non-collapsible-formula-marker]
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
| — **open** | How the `⊤` residue is supplied when a body cannot narrow index discovery | All three options are now priced on production data; the choice gates the cost work, not the correctness | `production-corpus-measurements.md` §5 |

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

The recommendation is **(c) with (b)**, and (a) as the honest fallback — but this is an open decision, and
the cost work cannot start without it. What is **not** at stake is correctness: that comes from the per-index
loop, which is implemented. What option (a) costs is only the claim that a negation is as cheap as its
positive.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Reuse `suppressedConstraints` to suppress `Not` during index discovery | Suppression **drops** the branch rather than widening it, so `or(a, not(b))` would narrow to `a` where it must widen to everything — a wrong answer, not a slow one | Never; the mechanism is the wrong shape for the job |
| Derive the widening decision from the index type (`ReferencedTypeEntityIndex`) | The index type says what is being read, not whether the reader re-evaluates it. `facetHaving` reads a type-level formula as its **final answer**, so it needs a real subtraction; the type-based test dropped the negation there and returned 230 products where 210 are correct | Never; the decision belongs to the consumer and is now carried by the scope |
| Count rows per owner from `ReferenceTypeCardinalityIndex` | `ReferenceIndexMutator:778-780` passes the **reduced index's** primary key, not the owner's, so `pack(pk, 0)` counts rows per reduced index — the per-target count discovery is already built on, and the opposite of what a counting complement needs. No per-(owner, reference) counter exists anywhere | Only if such a counter is introduced deliberately — that is option (b), with its own write-path and format cost |
| Use `ReducedIndexMembership` as that row count | It maps owner → index primary keys only for *covered* indexes, is derived rather than persisted, and declares itself "accelerator, never authority". Its permitted omissions would become wrong answers | Never; it would convert a documented approximation into a correctness dependency |

## Key technical details

**The transpose.** `ReferenceHavingTranslator` hands the planned body to `ReferenceBodyTransposer#transpose`,
which rebuilds it once per reduced index. Leaves that are index-specific are wrapped in `IndexTaggedFormula`
at the moment they are produced — `FilterByVisitor#tagWithProducingIndex`, attached in `applyOnIndexes`,
`applyStreamOnIndexes` and `applyOnUniqueIndexes`, which are the complete set because the filter-index
variants delegate to them. `project` keeps each index's own tagged leaf and drops its siblings.

**Tagging is mandatory, and its absence is silent.** `project` returns any untagged node whole, for every
index — the deliberate conservatism above. An index-local formula that forgets its tag is therefore not
dropped, it is *shared*, which reads as a correct answer on any fixture where the rows of one owner agree.

**A negation is resolved inside the index**, against that index's own super set, via
`FutureNotFormula.postProcess(formulas, CONJUNCTION, …)`. `CONJUNCTION` is load-bearing: under `DISJUNCTION`
the third case re-emits a `FutureNotFormula` even when a super-set supplier is present, and the placeholder
then reaches `compute()` and throws.

**Per-index identity, in all three places it is memoized (C1).** A per-index formula needs per-index identity
in *every* memoization it passes through, and all three failures are silent — they collapse N nodes into one:

- the `computeOnlyOnce` cache key, which must include the index primary key;
- `ReferenceOwnerTranslatingFormula#expanderDiscriminator`, added for this;
- the `IndexTaggedFormula` hash.

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
| fetch-path dispatch reverted | `shouldComplementEntityHavingPerRowWhenFilteringReferenceContent` | `{1=[], 2=[], 3=[2]}` where `{1=[2], 2=[], 3=[2]}` is correct |

Four rows pin the fetch path, which reaches its per-index evaluation by a different route than the filter
path does: `shouldComplementEntityHavingPerRowWhenFilteringReferenceContent` (the table row above),
`shouldComplementEntityPrimaryKeyInSetPerRowWhenFilteringReferenceContent` (which fails with an internal error
rather than a wrong answer when the fetcher suppresses the constraint) and the two
`...GroupHavingPerRowWhenFilteringReferenceContent` rows.

Suite state on the combined tree: `-Dgroups="reference | facet"` → **3,371 run, 0 failures, 0 errors**; full
`unitAndFunctional` → **24,349 run, 0 failures, 1 error** (`ExportS3ServiceTest`, which needs Docker and
cannot pass in this environment).

Production-corpus numbers, and the harness traps that produced them, are in
`production-corpus-measurements.md`.

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
See `ReferenceBodyTransposer#combinedOnlyByUnion`.

What the fast path does **not** remove is the rebuild for a conjunctive or negated body, which still walks the
family once per index. Removing that needs a **compositional candidate set plus the residue term**, so the
loop visits only the indexes that can contribute. **Blocked on the residue decision above** - as written it
would be implemented against a per-owner row count the engine does not have.

**The per-index `entityHaving` branch does N·M expander calls where N membership tests would do**, worst
under `not(entityHaving(...))` where discovery widens N to the whole family. Introduced here, **unmeasured**,
and it compounds the O(family) cost above — it belongs with that work, not with a blind optimisation. The
per-call cost is trivial; the call count is the point, and nobody has counted it.

**The fetch side now runs the same adapters, and doing so fixed a crash.** `ReferencedEntityFetcher
#computeResultWithPassedIndex` used to suppress `EntityPrimaryKeyInSet` while translating a reference body,
on the premise that index discovery had already applied it. That premise fails under a negation, which widens
the candidate set rather than narrowing it: `referenceContent(R, filterBy(not(entityPrimaryKeyInSet(X))))`
handed `NotTranslator` nothing to negate and failed its premise check outright - a pre-existing internal error
on a plain public query. The constraint is no longer suppressed, and
`EntityPrimaryKeyInSetTranslator`'s guard names `AbstractReducedEntityIndex` so it actually fires in that
scope. Still not done: making a *composite* body row-scoped on the fetch path.

**A double negation inside a discovery scope widens twice.** The inner `not` has already widened to the
super set, so the pair is not visible to the collapse and the outer `not` widens again. Sound — widening a
candidate set never loses a row — but imprecise.

**#1584 is not fixed by this work.** `attributeIsNull` on a reference attribute still returns empty. Its
repair is to treat it as `not(attributeIsNotNull(a))` rather than as a plain leaf: evaluated at type level it
means "no row in this index carries `a`", which is a strict *subset* of the indexes that can contribute. It
belongs with the compositional candidate set.

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
