---
title: Every requested scope is planned, checked and counted as if it were queried alone, and the facet summary predicts exactly what facetHaving selects
date: 2026-10-06
updated: 2026-10-07 05:40
status: accepted
kind: fix
issues: [1681, 1686, 1695]
prs: []
areas: [evita_engine/core/query, evita_engine/core/query/indexSelection, evita_engine/core/query/filter, evita_engine/core/query/filter/translator/behavioral, evita_engine/core/query/filter/translator/facet, evita_engine/core/query/filter/translator/hierarchy, evita_engine/core/query/filter/translator/reference, evita_engine/core/query/algebra/facet, evita_engine/core/query/extraResult/translator/reference, evita_engine/core/query/extraResult/translator/hierarchyStatistics, evita_engine/core/query/sort, evita_engine/core/query/fetch, evita_engine/core/collection, evita_engine/index/facet, evita_engine/index/hierarchy/predicate, evita_engine/index/usage, evita_query/api/query, evita_query/api/query/require, evita_api/api/requestResponse/extraResult, evita_external_api/evita_external_api_core, evita_external_api/evita_external_api_grpc, documentation/user/en/query]
supersedes: []
superseded-by: []
relates: [2026-08-19-per-schema-capability-usage-statistics, 2026-09-15-bidirectional-reference-counterpart-rewrite, 2026-09-23-pick-first-reference-ordering-from-selection, 2026-09-25-attribute-is-null-in-reference-having]
---

# Every requested scope is planned, checked and counted as if it were queried alone, and the facet summary predicts exactly what `facetHaving` selects

One line of work, three issues, one PR. It started as a planner defect: a query over both scopes lost every
archived entity when an `inScope(LIVE, …)` reference constraint produced a reduced-index plan that the planner then
used for the whole query (#1681). It also covers the same constraint instance reused in two `inScope` containers
(#1686). The scope work exposed a disagreement between `facetHaving` and the facet summary (#1695). Fixing that
disagreement forced the facet selection semantics to be written down. Its review rounds then turned up two more
classes of defect: constraints whose validity depended on whether the scope held data, and schema-capability
counts that depended on it too. This record keeps the forks of all of it: the planner rule, the per-scope
hierarchy semantics, the facet-composition and summary rules the owner decided, the "valid regardless of the data"
rule, the counting unit, and the performance regression the facet changes caused and how far its repair measured.

Upgrade notes with the user-visible consequences are on the issues themselves (`[!IMPORTANT]` blocks of #1681 and
#1695). This record does not repeat them line by line.

## Why

- **#1681 — wrong results, no error.** On a randomized fixture (122 matching entities, 42 archived), a query over
  `scope(LIVE, ARCHIVED)` with `entityLocaleEquals` and `inScope(LIVE, hierarchyWithin(...))` returned 80 entities,
  none of them archived. The cheaper candidate plan (estimated cost 8,624 against 8,722) was built from the live
  partitions only, and every translator that reads the plan's index set answered from LIVE alone: locale, entity
  attributes, prices, facets, negation and primary-key ranges.
- **#1686 — wrong results, no error.** One `referenceHaving` instance placed in `inScope(LIVE, …)` and
  `inScope(ARCHIVED, …)` was translated with the live partitions in both containers, because the candidate lookup
  matched by object identity and took the first match.
- **#1695 — the summary and the result disagreed.** `facetHaving` ignored `facetGroupsNegation` and
  `facetGroupsDisjunction` for facets without a group and for unmanaged group types. A shopper saw "6" next to a
  negated brand and got 66 products on clicking it. Behind that one gate stood a family of disagreements: relation
  precedence, defaults, the composition across references, exclusivity, scopes, negated user filters, and facets
  referenced under several groups. A facet summary exists to predict a click, so every one of them is a wrong
  number on a UI.
- **Validity and statistics depended on the data.** The work turned up constraints the schema refuses that failed
  over a scope with data and passed silently over a scope without it. It also turned up capability counts that
  differed with and without data. An operator learns about an invalid query only after the data arrive. A flag
  used only by such queries reads as unused, and dropping it breaks them.

### Previous state

- **Index selection.** `IndexSelectionVisitor#addHierarchyIndexOption` and `#addReferenceIndexOption` registered a
  candidate built under `inScope(S)` from scope-S partitions only, with no obstacle. `QueryPlanner` evaluated the
  whole multi-scope query on it if it was cheapest. An empty narrowed candidate short-circuited the whole query to
  an empty result through `IndexSelectionResult#isEmpty`. The code had been like this since #677 (2024-11). It is
  identical on `master`, `release_2026-2`, `release_2026-1` and `release_2025-8`.
- **Hierarchy filters over several scopes.** They read only the first scope's tree that yielded a node, which is
  the live one whenever it has any (`.findFirst()` in `HierarchyWithinTranslator` /
  `HierarchyWithinRootTranslator`). This was deliberate since #677 but undocumented.
- **Nested `inScope`.** The documentation said it was refused. Nothing enforced it, and such queries executed with
  undefined results.
- **`FacetHavingTranslator`.** It had two gates from its initial commit (2023-02). A facet without a group, and a
  facet of an unmanaged group type, were always AND-ed. The summary asked negation before disjunction; the result
  asked disjunction first. A `facetCalculationRules` default could beat a declared relation in the result but not
  in the summary.
- **The impact prediction.** It OR-ed or AND-ed an option with the whole user filter. It inserted the option into
  any `OrFormula` it found, including an `or(...)` the user wrote and another reference's disjunctive bucket. It
  predicted an exclusive option alone.
- **The empty plan.** When index selection proved the result empty, `QueryPlanner` returned `QueryPlanBuilder.empty`
  before translating anything. Many nested filters, orderings and summary filters were translated only where data
  existed, or only lazily at execution.
- **Capability counting.** It counted once per plan that built over data, nothing in contexts that never built a
  plan, nothing for requests on another collection's schema, nothing for an empty plan, and one count per write
  that re-evaluated a conditional facet expression.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-10-01 | A candidate built for fewer scopes than the query requests carries the correctness obstacle `PARTIAL_SCOPE_COVERAGE`, stays registered, and is never registered empty ("expanded A") | The disjunction of its indexes is the answer of the container's branch, not of the query. The constraint's own translator still finds it, and single-scope queries keep the reduced plan | `TargetIndexes.EligibilityObstacle#PARTIAL_SCOPE_COVERAGE`, `IndexSelectionVisitor` |
| 2026-10-01 | Nested `inScope` of one kind within one evaluation context is refused at planning time, on the final query tree | A different scope is contradictory and the same scope redundant. Checking the final tree catches copies made by `getCopyWithNewChildren`, and stored traffic recordings of the shape still deserialize | `QueryUtils#assertNoNestedScopeContainers` from `QueryPlanner#planQuery`, `EntityCollection#getEntity` / `#enrichEntity` and the mutations that return the entity (checked before the mutation) |
| 2026-10-01 | A candidate is found by constraint identity **and** the processing scopes it was built for (#1686). Hierarchy roots are recorded per occurrence and read for a scope as exact, then covering, never non-covering | Identity alone returns the first of several per-scope candidates | `TargetIndexes#represents`, `QueryPlanningContext#getHierarchyFilterForScope` |
| 2026-10-01 | Hierarchy statistics per scope: a scope no `hierarchyWithin` occurrence covers is computed as if unfiltered (its `parents` is empty). A covering occurrence that selects no node empties `children` / `parents` / `siblings`, while `fromRoot` / `fromNode` keep computing | The pivot-describing statistics have nothing to describe. `fromRoot` / `fromNode` are pivot-independent by contract (`hierarchy.md`), as a single-scope own-hierarchy query already computes them | Hierarchy statistics computers |
| 2026-10-01 | A hierarchy filter over several scopes resolves its parent nodes in each scope's own tree, exactly as a query over that scope alone. The nodes of all scopes are united and paired with the owners of every queried scope ("semantics B", owner) | An archived product under a live category must keep matching over both scopes (`EvitaArchivingTest`) | `HierarchyWithinTranslator`, `HierarchyWithinRootTranslator`, `AbstractHierarchyTranslator` |
| 2026-10-02 | A nested filter evaluated against one scope's index runs with that scope as its processing scope. An emptied `ScopeContainerFormula` built by `restrictingScope` stands for its whole scope, and the branch is intersected with that scope's entities | Without this, nested `referenceHaving` / unique lookups reached the other scope, `not(inScope(LIVE, X))` selected everything, and the statistics strip lost a scope | `FilterInScopeTranslator` (and its `InScopeFormulaPostProcessor`), `ScopeContainerFormula` |
| 2026-10-02 | `facetHaving` applies the relation settings to facets without a group and to unmanaged group types, as the summary always did | The gates added nothing but the disagreement | `FacetHavingTranslator` |
| 2026-10-02 | A group filter of a facet relation constraint that cannot be evaluated fails the query with a client error, in the result and the summary alike, whatever is selected (owner) | An invalid query must not pass just because no summary was asked for, or because only ungrouped facets were selected | `QueryPlanningContext#assertFacetGroupFiltersEvaluable` |
| 2026-10-03 | Relation precedence is negation > disjunction > exclusivity > conjunction. A declared relation beats the `facetCalculationRules` default of its level. One resolution serves the result and the summary | The order the summary already used. Two resolutions that disagree are the defect | `QueryPlanningContext#getFacetRelationType`, `DECLARED_RELATION_PRECEDENCE` |
| 2026-10-03 / 05 | A `NEGATION` default within groups also negates between groups while the other level is `CONJUNCTION` or `NEGATION` (De Morgan). `(NEGATION, DISJUNCTION)` and `(NEGATION, EXCLUSIVITY)` are refused. Negations declared at the two levels with different filters apply as a union (owner) | The documentation promised the level does not matter. The two refused pairs could not take effect and were silently ignored | `QueryPlanningContext#assertDefaultFacetRelationsEffective` |
| 2026-10-05 | A `facetGroups*` constraint naming a reference the entity type does not have fails with `ReferenceNotFoundException` (owner) | It was silently ignored, unlike `referenceContent` and `referenceSummaryOfReference` naming such a reference | `QueryPlanner` up-front validation |
| 2026-10-05 | **The result decides, and references are always AND-ed.** Within one reference, a selection composes as `(conjunctive groups OR disjunctive groups) AND NOT negated groups`. The relation between groups applies between the groups of one reference only (owner) | `userFilter` combines its constraints the way `and` does. The documentation's "groups or references" was the outlier, and a cross-reference OR is a feature, not a fix (#1698) | `FacetHavingTranslator#composeFacetSelectionFormula`, shared by the impact generator |
| 2026-10-05 | The summary's impact (`matchCount`, `difference`) of an option equals the result of adding it to the user filter, wherever the user filter sits: per scope copy, inside `not(...)`, inside an `or`, next to a negated constraint. An exclusive option replaces the selection of its own reference only | Same rule as above, applied to every shape the planner produces | `AbstractFacetFormulaGenerator#handleUserFilter`, `#replaceFacetSelection` |
| 2026-10-05 | **Facet groups belong to the reference.** A facet referenced under several groups (or with and without a group) takes part in every one of them, composed by their relations ("R", owner) | "The simplest option". It follows from the data model and needs no invented relation | `FacetHavingTranslator`, `FacetReferenceIndex` |
| 2026-10-05 | A selected facet the searched scope does not reference is a facet without a group there: an empty term following the relations of ungrouped facets ("d", owner). Only a facet filter that is exactly `entityPrimaryKeyInSet` selects such a facet ("A") | There is no reference in that scope to take a group from. Every other filter shape reads data of a reference the scope does not have | `FacetHavingTranslator` |
| 2026-10-05 | The **count** of a summary entry (group, facet) is the number of entities of the result without the user filter that reference the facet under that entry's group ("b", owner). In a group the query negates, it is the number that do **not**, as single-group facets always counted (confirmed 2026-10-06). **Impact and `hasSense`** are shared by all entries of a facet and predict selecting it | A count says what is there; an impact says what a click returns (owner's wording) | `ReferenceSummaryProducer.FacetAccumulator#getCount`, `FacetGroupOccurrences` |
| 2026-10-05 | `hasSense` stays "selecting the option alone in each of its groups returns something". The conjunctive shortcut is skipped for multi-group facets, negated groups and negated user filters | The pre-existing contract (`ImpactFormulaGenerator#hasSenseAlone`). The shortcut assumed that widening a term widens the result, which a subtraction inverts | `ImpactFormulaGenerator` |
| 2026-10-05 | **A constraint the schema refuses fails the query whether or not the scope holds data.** A query proven to match nothing is planned over empty stand-in indexes to check it, and the plan is thrown away. Nested filters, orderings, hierarchy constraints and fetched-reference filters / orderings are checked in every requested scope (owner: "they must throw") | Validity that depends on the data surfaces in production after a data load, not in development | `QueryPlanner#planOverEmptyIndexes`, `GlobalEntityIndex#createEmptyIndex`, `FilterByVisitor#createConstraintCheckVisitor` |
| 2026-10-05 / 06 | Capability statistics count a **valid query that matches nothing** like the same query over data (owner) | Otherwise a flag only such queries use looks dead, and dropping it breaks them | `QueryPlanBuilder.empty` drains |
| 2026-10-06 | **The counting unit is once per logical query**, across nested queries, checks, sorter contexts and fetch-time plans. The root context keeps the record of what was counted | ADR 2026-08-19 defines that unit and nothing makes a nested plan a unit of its own. Per-plan counting differed with and without data (2 / 1) | `QueryPlanningContext#drainRequestedCapabilitiesToCount`, `#countedCapabilities` |
| 2026-10-06 | A context that builds no plan hands its requests to the context it serves. A request about another collection's element counts on that collection's registry. A write that re-evaluates an expression records nothing | Each of these was a place where a request was recorded but never counted, or counted on a write | `OrderByVisitor#createSorter`, `HavingTranslatorHelper#checkNestedFilter`, `QueryPlanningContext#recordRequestedCapability`, `#recordingRequestedCapabilities` |
| 2026-10-06 | The facet-summary regression is reduced by memoizing relations per query, creating occurrences only on a cache miss, placing a conjunctive option next to the selection where that is algebraically equal, and a non-allocating single-group check (H1–H5). The selection shapes time at parity with dev; the two no-selection shapes remain about 5 % slower | Counted, not guessed. See *Performance* | `QueryPlanningContext#facetGroupRelations`, `FacetFormulaGenerator`, `AbstractFacetFormulaGenerator#isFacetSelectionNarrowedByConjunction`, `FacetReferenceIndex#isReferencedOnlyUnder`, `QueryPlanningContext#facetGroupPredicates` |

### Facet semantics in one place

This is the rule set the code implements. The user documentation (`reference.md`, `references.md`,
`behavioral.md`) states it as current behaviour.

1. **Within one reference**, the selected facets compose as `(C OR D) AND NOT N`. `C` is the AND of conjunctive
   groups; exclusivity composes as AND. `D` is the OR of disjunctive groups. `N` is the OR of negated groups.
   Inside a group the "within" relation applies. **Across references** the selections are AND-ed.
2. **Relation of a group at a level.** The first declared relation matching the group wins, in the order negation,
   disjunction, exclusivity, conjunction. Only when none matches does the `facetCalculationRules` default of that
   level apply. A negation declared at either level negates the groups its filter matches, at both levels. An
   explicitly exclusive group with several selected options is combined in the result by the system default.
3. **Groups are a property of the reference.** A facet takes part in every group it is referenced under, the
   facets without a group included. A facet unreferenced in the searched scope is ungrouped there and matches
   nothing. One selection over several scopes gives a facet the groups of every scope. A selection placed in
   `inScope` containers composes it per scope, so the two can differ under group-specific relations. This is
   documented and accepted.
4. **The summary.** The count of an entry counts the facet under that entry's group over the result without the
   user filter. In a negated group it counts the entities that do not reference the facet. Impact and `hasSense`
   are what the result returns for the selection, shared by all entries of a facet.
5. **Validity.** A relation constraint whose filter cannot be evaluated fails, as does one naming a missing
   reference. So do the two ineffective default pairs. All of these fail in every requested scope, regardless of
   data and selection.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| #1681: add the other scopes' global indexes to the narrowed candidate ("B") | `TargetIndexes` would then mix "indexes representing the constraint" with "the plan's indexes". The hierarchy and `referenceHaving` translators look the set up by identity and would leak archived owners into the LIVE branch. Mixed index types would also break translator type checks (`EntityPrimaryKeyInSetTranslator`) | `TargetIndexes` gains a split between the represented set and the plan set for another reason |
| #1681: scope-aware fallback in `FilterByVisitor#getEntityIndexStream` ("C") | It must tell "scope never covered" from "covered with zero partitions", or the hierarchy optimizer drops the predicate. It needs scope-coverage metadata, an audit of the optimizing and superset post-processors, and must not apply inside `referenceHaving` bodies. All this to win back a cost difference of about 1 % in the one measured query | A real multi-scope workload measures the reduced plan as material |
| Refuse nested `inScope` in the constraint constructors | Kryo deserializes stored queries through the public constructors, so one such query would make a whole traffic recording unreadable. `getCopyWithNewChildren` also bypasses a constructor check | — |
| Fix the opposite-nesting rewrite (`inScope(LIVE, inScope(ARCHIVED, P))`) instead of refusing it | The owner chose refusal: the shape is contradictory by definition, and `FormulaCloner` does not recurse into a replacement, so a fix would define semantics for a query that has none | — |
| Hierarchy over several scopes as the strict union of single-scope queries, owners paired per scope ("A") | It loses an archived product under a live category over both scopes. The full module run showed exactly that: 2 failures, `EvitaArchivingTest#shouldArchiveEntityAndMoveToArchivedIndexes` and its transactional twin | — |
| Empty `fromRoot` / `fromNode` for a scope whose covering `hierarchyWithin` selects no node | It would change single-scope own-hierarchy behaviour, where `fromRoot` with an unmatched `hierarchyWithinSelf` is non-empty today. It would also contradict the documented pivot independence | The documentation redefines `fromRoot` as pivot-dependent |
| Route every hierarchy filter inside `inScope` to the constraint-level fallback | It fixes the hierarchy shape only. Making an emptied scope container stand for its scope also fixes the user-filter and facet strips of the other statistics bases | — |
| Cross-reference OR by `facetCalculationRules(_, DISJUNCTION)` (the documentation's old wording) | The result has always AND-ed `facetHaving` constraints of different references, and the summary predicted something no query returned. Making the result OR them is a new feature with open questions (precedence, interaction with negation). Filed as #1698 | #1698 is designed |
| Unreferenced facet as an empty conjunct of an unknown group ("a") | Wrong whenever the facet's real group is disjunctive: the result empties where it should not, and nothing can detect it | — |
| Look the unreferenced facet's group up in another scope's index ("b") | The owner decided grouping lives on the reference. In a scope without a reference there is no group to take, and the scope's answer would depend on another scope's data. It also leaves a facet referenced nowhere unhandled | — |
| Leave an unreferenced facet ignored as a known limitation ("c") | The result contradicted itself: `facetHaving(tag, 20, 13)` returned tag 20's products, while `facetHaving(tag, 13)` returned nothing | — |
| Select named primary keys in any filter position ("A+", e.g. `or(entityPrimaryKeyInSet(13), attributeEquals(...))`) | It needs an evaluator of every constraint for an absent reference: is an absent reference attribute NULL, and what does `not()` complement against? Nothing in the engine does that | Such an evaluator exists |
| The referenced entities as the universe of a facet filter ("B") | A pk with no entity (tag 99) would stay unselected, contradicting the decided result. Unmanaged types have no entity universe at all | — |
| One option per facet, all its references united into one term ("U") | A facet in two groups would need a single relation, and there is no natural answer (group 100 OR, group 200 AND?) | — |
| A selected facet takes part only in the groups under which the queried entities reference it | Weighed again on 2026-10-06, when the demo dataset showed the consequence of "R": Red, Gold and Pink are referenced under two parameter groups named *Color*, so selected alone they match no smartwatch (dev returned 1, 17 and 9). The owner kept "R": the meaning of `facetHaving` would otherwise depend on the rest of the filter, and the impact must equal exactly what `facetHaving` with the facet id returns | Shops report that sharing values across groups is common and the data cannot be changed |
| List a multi-group facet by "someone carries it" with count 0 and the shared impact ("a") | The owner wants the count to carry the entities having the facet under that group. An entry showing 0 next to a facet products carry reads as broken | — |
| Leave multi-group facets hidden when selecting them alone returns nothing ("c") | The shopper cannot select an option that would match in combination (tag 50 with tag 10 selected returns product 8) | — |
| Plain count (entities having the facet) for an entry in a negated group | A single-group facet of a negated group has always counted the entities *without* it. A multi-group entry and a single-group facet of the same group would count differently | — |
| `hasSense` following the "add the option" prediction under `not(userFilter(...))` (Codex r11 #1) | The established contract is "selecting it alone returns something". Count and difference already follow the add-option rule | The `hasSense` contract is redefined |
| Leave validity data-dependent (empty plan before translation) | The same query passed over an empty scope and failed once data arrived, which is the worst time to learn it. The cost of the check falls only on queries already proven empty | — |
| Hierarchy statistics of a scope without nodes as an empty tree | It changes the `Hierarchy` extra result of valid queries, and `EvitaArchivingTest` pins an absent `archiveMenu`. A check-only mode refuses the same constraints and keeps the output | — |
| Count once per nested plan | Plans over data counted per site while checks without data counted once, so the same query counted 2 with data and 1 without. ADR 2026-08-19 states "once per logical query" without qualification | — |
| Split a nested filter check by data: count only scopes without data and leave the rest to the fetch | The fetch plans its nested query only when a fetched entity holds a reference, so the count depended on what the query returned | — |
| Drop requests about another collection's elements (the 2026-08-19 "known gap") | The right owner is known: the collection whose schema declares the element. Dropping made a used flag look dead. The catalog `SORTABLE` drop is a different case (a collection-less query has no right owner) and stays | — |
| Per-call-site "record / don't record" flags on the query path | Every new site would have to choose, and the drops this work fixed were exactly such choices. A single flag on the write-path root context, inherited by every derived context, covers the one path that must record nothing | — |
| Leave the option composed inside the reference's `FacetHavingFormula` in every case | It un-memoized `FacetHaving` / `NOT` / `AND` for every option: 1,761 extra `andNot` per query in the negated shape. Where the sets are equal, the dev placement is free | — |

## Key technical details

- **Narrowed candidates.** `IndexSelectionVisitor` marks a candidate whose construction scopes differ from the
  query's. The predicate is the one the `referenceHaving` branch already used for its empty skip. A marked
  candidate is never selected as the plan, but reference ordering still reads it
  (`ReferencePropertyTranslator#selectReducedEntityIndexSet`); #1670 owns that read. Under
  `VERIFY_ALTERNATIVE_INDEX_RESULTS` such queries compare fewer plans.
- **`FormulaDeduplicator` identifies a formula by `(hash, transactionalIdHash)`.** The hash describes the
  computation, not the data. The hierarchy bitmap suppliers keep their transactional ids out of the hash on
  purpose, so the live and archived "nodes from the roots" share one hash. Keying by hash alone replaced the
  archived formula with the live one.
- **Unique attributes in a hierarchy parent filter** resolve once per scope, because each scope's tree is searched
  as that scope alone. Within one scope the rule of `2026-09-25-attribute-is-null-in-reference-having` (first
  listed scope wins) does not come into play. Over several scopes a unique `code` may select one node per scope.
- **`ScopeContainerFormula.restrictingScope`** builds a container that stands for its whole scope once a clone
  strips its children. The public constructors build one without that substitute. `Formula#getCloneWithInnerFormulas`
  documents the difference. A `not()` directly inside `inScope` is resolved by the container against its scope's
  superset. A `FutureNotFormula` must never reach a level that only sees the container.
- **One relation resolution.** `QueryPlanningContext#getFacetRelationType` / `#isFacetGroupRelationType` is the only
  place that decides a group's relation. It is memoized per context in `facetGroupRelations`, keyed by
  (reference name, group id with a separate NULL slot, level, relation type for the boolean questions). The key was
  proven component by component, with one counterfactual per dropped component:

  | dropped key component | failures |
  |---|---:|
  | reference name | 31 |
  | group id, NULL included | 276 |
  | group id, NULL kept apart | 240 |
  | the NULL slot | 217 + 6 errors |
  | level, in the resolved relation | 274 |
  | level, in the boolean decisions | 1 |
  | relation type | 52 |

  The single failure for the boolean-decision level is the unit test alone: no engine caller asks those questions at
  `WITH_DIFFERENT_GROUPS`, but the public methods take the level. A resolution that throws memoizes nothing. Group predicates are memoized by the **identity** of the `FacetFilterBy`
  declaration (`facetGroupPredicates`). Equality would let two references with equal filters over different group
  types share one evaluated filter.
- **Composition is shared.** `FacetHavingTranslator#composeFacetSelectionFormula` is called by the result and by the
  impact generator. Never re-derive the composition in the summary.
- **H3 placement condition.** `AbstractFacetFormulaGenerator#isFacetSelectionNarrowedByConjunction` places a
  positive option as a conjunct of the user filter instead of recomposing it into its reference's selection.
  Four conditions must hold:
  - **G1:** every positive group of the option is conjunctive between groups.
  - **G2:** no selection of the reference it would recompose has a disjunctive group.
  - **G3a:** every such selection is reached from the user filter through `AndFormula` children and NOT superset
    parts only.
  - **G3b:** the same holds for every NOT whose subtracted part is the reference's NOT-only selection.

  Then `(C ∩ g) \ N = (C \ N) ∩ g`, and the identity lifts through AND and the superset part of NOT. With a
  disjunctive part, a disjunctive option, or a selection under an `or`, the sets differ (`((C ∩ g) ∪ D) \ N ≠
  ((C ∪ D) \ N) ∩ g`), and recomposition stays. Exclusive options take `replaceFacetSelection` and never reach the
  shortcut.
- **Summary counts.** `FacetCalculator#createCountFormula` counts an entry as a single-group facet of the entry's
  group (`FacetGroupOccurrences.singleGroup`). `FacetFormulaGenerator` asserts a single-group occurrence. The
  multi-group composition lives only on the impact side (`FacetGroupOccurrences#computeImpactIfAbsent`), and the
  occurrences are resolved only when an impact is requested.
- **Constraint checks over empty indexes.** `QueryPlanner#planOverEmptyIndexes` (also `planNestedQuery`) plans
  filter, ordering and the top-level extra results over `GlobalEntityIndex#createEmptyIndex` per requested scope,
  then throws the plan away. It appears in telemetry as `QueryPlanner.CONSTRAINT_CHECK_INDEX_DESCRIPTION`.
  `FilterByVisitor#createConstraintCheckVisitor` evaluates nothing at any depth: nested reference constraints
  read empty stand-ins, not real type indexes. `ReferencePropertyTranslator#checkChildConstraintsWithoutRows`
  translates the children over one empty reduced index and discards sorters and prefetch requirements.
  `HierarchyContentTranslator` checks `stopAt(node(...))` while planning. Hierarchy statistics of a scope without
  a tree run in a check-only mode that claims output names and order but registers no computer.
- **Nested filters are planned in the target's context.** A nested filter is planned in a context derived from the
  enclosing one for the target collection: `HavingTranslatorHelper#createNestedQueryContext`, and
  `FilteringFormulaPredicate#createTargetQueryContext` for summary and relation-constraint filters. A constraint
  that resolves against the context's entity type (`hierarchyWithinSelf`) then resolves against the right one.
- **Counting invariants** (with ADR 2026-08-19):
  - Only `QueryPlanBuilder#build` / `.empty` count, through `drainRequestedCapabilitiesToCount`, which skips by
    identity what the root's `countedCapabilities` holds.
  - Every other context that records hands its holders over (`registerRequestedCapability`):
    - sorter contexts in `OrderByVisitor#createSorter`, at any depth;
    - nested filter checks in `HavingTranslatorHelper#checkNestedFilter`, always, never split by data;
    - summary and relation filters in `FilteringFormulaPredicate`;
    - the `referenceContent` checks in `ReferencedEntityFetcher#verifyReferenceContentConstraints`.
  - Fetch-time comparators and nested queries plan in contexts derived from the query's, so the root skips what the
    planning check counted.
  - The generic `referenceSummary` / `facetSummary` creates its predicates and sorters while planning, for every
    reference it reaches, faceted or carrying a requested histogram (`ReferenceSummaryTranslator#createForSummarizedReferences`).
    An unattached reflected reference is stepped over.
  - `BidirectionalReferenceRewriter#checkAttributeConstraintsOnOwnerSide` records the owner-side flags when the
    counterpart answers.
  - `QueryPlanningContext#recordingRequestedCapabilities` is `false` only for the session-optional constructor used by
    `EntityCollection#evaluateFilter`, and every context derived from it inherits the value.
- **Telemetry shape.** With `queryTelemetry`, the `PLANNING_NESTED_QUERY` step of a fetched-reference comparator
  appears under the fetch step, because the comparator plans in a context derived from the query's. This is a
  change of shape: the step used to be planned into a detached root and was lost.
- **No catalog index as a side effect.** A globally unique lookup in the archived scope reads the catalog index only
  when it exists and never creates it. A query therefore leaves a catalog without archived data without an archive
  catalog index.

## Verification

- **Full functional module** on `ef50708e84` (run497, `-am -P unitAndFunctional`, parallelism 8, 12 GB fork heap):
  surefire XML 26,425 tests, 0 failures, 1 error, 39 skipped. The error is `ExportS3ServiceTest`, which needs Docker
  and has none in the sandbox. HEAD `d6ed52d3cb` changes only gRPC proto comments and their regenerated copies on
  top of it.
- **Red first, counterfactual always.** Every fix was written against a witness that failed for the stated reason.
  Every engine hunk was then reverted alone, and its own rows had to fail. Restores were proven by checksum
  (`cmp` / `sha256sum`) and by `.class` mtimes after a `cp -p` restore once kept a counterfactual class alive.
  Examples:
  - #1695 precedence: 6 rows.
  - The cross-reference AND: 6 rows and 8 of 17 generic setups.
  - Data-independent validity at the top level: 7 rows. The nested check: 1 row. The extra results: 4 rows.
  - H3 forced on: 196 failures. G1 dropped: 103. G2 dropped: 75. G3a and G3b each: 1 dedicated row.
  - H3 disabled: only the 3 shape tests fail, which is what the algebra predicts.
  - H5 keyed by equality: 2. Both negation levels merged: 6.
- **Tests that pin the decisions:**
  - `InScopeReducedIndexPlanFunctionalTest`: nested classes `NarrowedReferencePlan` (telemetry-pinned obstacle and
    the single-scope reduced plan), `ReusedConstraintInstance`, `NestedInScopeRejection`, `NegatedScopeContainer`,
    `HierarchyStatisticsPerScope`, `HierarchyWithinOverBothScopes`, `UserFilterStrippedFromScope` and
    `FacetSummaryOverScopeContainers`. Also `ScopeContainerFormulaTest`, `FormulaDeduplicatorTest` and
    `EvitaArchivingTest`.
  - `AbstractEntityByFacetFilteringFunctionalTest`, run by `EntityByFacetFilteringFunctionalTest`,
    `EntityByFacetFilteringAndPartitioningFunctionalTest` and `FacetSummaryBackwardCompatibilityTest`:
    - `shouldLetDeclaredRelationTakePrecedenceOverDefault`, `shouldApplyDefaultNegationAtEitherLevel`,
      `shouldRefuseDefaultNegationWithinGroupsThatCannotTakeEffect`,
      `shouldNegateGroupMatchingNegationDeclaredAtEitherLevel` and `shouldRefuseRelationConstraintOfMissingReference`;
    - `shouldCombineOptionOfAnotherReferenceByConjunction` and
      `shouldPredictExclusiveOptionAsReplacingSelectionOfItsReferenceOnly`;
    - `shouldTreatSelectedFacetUnreferencedInScopeAsFacetWithoutGroup`, `shouldSelectFacetInEveryGroupItIsReferencedUnder`
      and `shouldCountEachEntryOfFacetInSeveralGroupsByReferencesOfItsGroup`;
    - the generic consistency checks `shouldPredictResultOfEveryExtendedSelection` and
      `…OfUserFilterInNotContainer`. Over 17 relation setups, these assert that every option's impact equals the size
      of the executed extended selection;
    - the "fails regardless of the data" rows: `shouldFailQueryOverScopesWithoutDataWhoseConstraintCannotBeEvaluated`
      and its `shouldAcceptEvaluable…` twins.
  - `FacetUnderNegatedAndConjunctiveGroupFunctionalTest`: the owner's example. A tag under a negated group, referenced
    by 2 of 6 products, counts 4 there. Under a conjunctive group, referenced by 1, it counts 1. Both entries predict
    `matchCount` 1 and `hasSense` true.
  - `ImpactFormulaGeneratorTest`: the three H3 shape tests. `QueryPlanningContextFacetRelationTest`: the memo key.
    `FacetGroupOccurrencesResolverTest` and `FacetReferenceIndexTest`.
  - `NestedConstraintCheckFunctionalTest`: scope-restricted nested filters, pick-first and traversal orderings
    without rows, and prefetch isolation.
  - `RequestedCapabilityAccumulationTest`: nested classes `Flush`, `SorterContexts`, `ConstraintsOfAnotherSchema`,
    `SummaryOfAllReferencesOrderings`, `TraversalWithoutRows`, `UnattachedReflection`, `WriteEvaluation` and
    `CounterpartRewrite`. Each asserts one count per logical query with data, without data, and after data is added.
    Also `ReferenceAndEntityCapabilityRequestTest#shouldRecordHierarchyIndexedOnTheCollectionOwningTheTree`.
- **Documentation examples** (doc rig, server jar against the base jar from merge base `07d462ffa`): arms on
  `b47592893` and `110634f140` were identical to base, with 1,329 test cases and the same 4 failing examples in
  both. The final arm (server jar of `d6ed52d3cb`, documentation of `d9e6a2c1c2`) matches base exactly: 1,329 test
  cases, the same 4 failing examples, no example only failing in either. It first moved one example,
  `faceted-search.evitaql` of `solve/render-products-in-category.md`, where three demo colors referenced under two
  groups predict and return no product; its output was updated after the owner kept "R".
- **Adversarial review.** Codex reviewed the design, then the implementation in four rounds, then the branch
  adversarially in rounds 5 to 16. Together with three code-quality passes, these reviews found most of the folded
  defects. The last, narrow round over the performance and final-fix commits (`8858fd1495..ef50708e84`) approved
  with no findings.

### Performance

**Regression measured.** A JMH A/B ran on 2026-10-06 from 05:26 to 06:24 CEST: dev `71e2823e77` against branch
`6f8d056082`, before any fix below.

Conditions:

- **Data:** the production retail corpus, 130,033 products.
- **Query:** a category listing of 21,204 products with `facetSummary(COUNTS | IMPACT)` over six faceted references:
  72 groups and 3,524 facets.
- **Harness:** `FacetSummaryBenchmark.listing`, AverageTime, warm-up 3 × 5 s, measurement 5 × 5 s, 24 GB heap,
  result cache off.
- **Runs:** two interleaved rounds (dev then branch, then branch then dev), 2 forks per arm per round, 0 failures.
- **Machine:** load 1.2 at start, about 8 externally blocked IO tasks, CPU pressure about 0.

Pooled means, µs/op:

| shape | dev | branch | ratio |
|---|---:|---:|---:|
| COUNT_NO_SELECTION | 61,814 (sd 4,427) | 65,688 (sd 3,309) | 1.063 |
| COUNT_OR_GROUP_SELECTION | 50,161 | 51,540 | 1.027 |
| COUNT_NEGATED_OR_EXCLUSIVE_GROUP | 51,937 | 52,792 | 1.016 |
| IMPACT_NO_SELECTION | 72,727 (sd 5,228) | 77,346 (sd 4,471) | 1.064 |
| IMPACT_OR_GROUP_SELECTION | 74,014 | 76,511 | 1.034 |
| IMPACT_NEGATED_OR_EXCLUSIVE_GROUP | 75,800 (sd 1,044) | 80,513 (sd 1,502) | 1.062 |

The branch was slower in every shape and both rounds. IMPACT_NEGATED was the clearest: +4.7 ms against an sd of
1–1.5 ms.

**Explained by counting, not by timing.** A Byte Buddy agent counted method invocations, and the query thread's
allocation was read through `ThreadMXBean`. Both arms returned identical summaries (SHAPE digests). Query planning
was *cheaper* on the branch: 13,831 → 5,572 planning invocations in the selection shapes.

- **H1.** Facet relations were resolved about 15 times per count formula instead of 4, each probe allocating a key.
  The NO_SELECTION shapes build 16,487 count formulas per query and keep 3,524. One cost of about 240 ns per count
  formula fits every COUNT delta.
- **H2.** A throw-away `FacetGroupOccurrences` (with a `HashMap`) and a cache key per count formula: +11–14 MB per
  query in NO_SELECTION.
- **H3.** The option was composed *inside* the reference's `FacetHavingFormula`, so each option's swap cleared the
  memo of `FacetHaving` / `NOT` / `AND`. In IMPACT_NEGATED, `andNot` went from 128 to 1,889 per query.
- **H4.** List allocation when resolving each facet's groups.
- **H5.** A negation group filter planned twice, once per asking level.

**Fixed:**

- H1: `ce489bb2b7`, a relation memo per context.
- H2: `f011f52e8b`, occurrences only on a cache miss and an array cache per reference.
- H3: `8030f36977`, the conjunct placement under the condition above.
- H4: `27ad04873c`, `FacetReferenceIndex#isReferencedOnlyUnder`.
- H5: `e9e0d3a14d`, predicates keyed by declaration identity.

Counted on a jar of `e9e0d3a14d`, with summaries identical to dev:

| metric (per query) | dev | branch `6f8d056082` | fixed `e9e0d3a14d` |
|---|---:|---:|---:|
| relation look-ups, COUNT_NO_SELECTION | 65,958 | 247,391 | 1,082 |
| `FacetGroupOccurrences` created, COUNT_NO_SELECTION | 0 | 16,487 | 6 |
| `andNot`, IMPACT_NEGATED | 128 | 1,889 | 128 |
| `FacetHaving` / `Not` computes, IMPACT_NEGATED | 83 / 128 | 2,485 / 2,530 | 83 / 128 |
| group-predicate tests, IMPACT_NEGATED | 4,957 | 17,179 | 309 |
| planning contexts, NEGATED shapes | 2 | 8 | 6 |
| allocation, COUNT_NO_SELECTION (MB) | 213.02 | 224.09 | 213.82 |
| allocation, IMPACT_NO_SELECTION (MB) | 256.09 | 269.78 | 258.17 |
| allocation, IMPACT_NEGATED (MB) | 169.25 | 172.99 | 166.74 |

The other three shapes allocate 1.3–2.2 % less than dev.

The counts hold on the final tree: a jar of `ef50708e84` (after the `or`-nested prediction fix `8626e5a31c`, which
touches the impact composition) counts the same in all six shapes as `e9e0d3a14d`, within 14 invocations per query,
and answers every shape identically to dev.

**Timed A/B of the final tree against dev.** Run on 2026-10-07 from 03:59 to 05:26 CEST in a quiet window: dev
`096dda1094` against branch `1562eecdca`, the merge of that dev into this branch, so the arms differ by this work
only. Same corpus, query and harness as above, with `@Fork(3)`: two interleaved rounds (dev then branch, then branch
then dev), 6 forks per arm in total, 0 failures. Load 1.6 at start, no other build or test process. Before the run,
both jars answered all six shapes with identical summary fingerprints.

Means of the 6 fork means per arm, µs/op, with the sd across forks and a Welch t over the fork means:

| shape | dev | branch | delta | t |
|---|---:|---:|---:|---:|
| COUNT_NO_SELECTION | 57,629 (sd 1,814) | 60,674 (sd 1,419) | **+5.3 %** | 3.2 |
| COUNT_OR_GROUP_SELECTION | 50,165 (sd 839) | 49,009 (sd 1,140) | −2.3 % | −2.0 |
| COUNT_NEGATED_OR_EXCLUSIVE_GROUP | 50,517 (sd 789) | 49,696 (sd 1,051) | −1.6 % | −1.5 |
| IMPACT_NO_SELECTION | 67,809 (sd 2,086) | 71,487 (sd 2,135) | **+5.4 %** | 3.0 |
| IMPACT_OR_GROUP_SELECTION | 73,045 (sd 437) | 72,635 (sd 1,060) | −0.6 % | −0.9 |
| IMPACT_NEGATED_OR_EXCLUSIVE_GROUP | 73,379 (sd 1,583) | 73,462 (sd 739) | +0.1 % | 0.1 |

H1–H5 removed the regression of the four selection shapes, which now time at parity with dev or slightly below it
(IMPACT_NEGATED went from +6.2 % to +0.1 %). **The two NO_SELECTION shapes are still about 5 % (3.0–3.7 ms) slower,
beyond the noise**, against +6.3 / +6.4 % before the fixes. The counts above do not explain it:
COUNT_NO_SELECTION does 1,082 relation look-ups against dev's 65,958, and the two shapes allocate +0.4 % / +0.8 %.
The remaining time is not yet attributed; see *Consequences*.

**Costs this work adds deliberately, none benchmarked:**

- A query proven empty pays one planning pass over empty indexes.
- A scope without data pays one empty stand-in index and one translation per nested constraint.
- A `referenceContent` filter or ordering pays one check-only translation per scope per query.
- The nested-`inScope` check is one allocation-free walk per query, fetch and enrichment.
- Capability recording costs tens of calls per query.

## Consequences & open follow-ups

- **#1670 waits for this merge.** Reference ordering still reads a `PARTIAL_SCOPE_COVERAGE` candidate
  (`ReferencePropertyTranslator#selectReducedEntityIndexSet`), so archived owners are not sorted by the narrowed
  reference. #1670's per-scope resolution owns that. The "traversal ordering unchecked without rows" item first
  carried over to #1670 was closed here (`effd007768`, `a747d7ef1a`).
- **#1685 (filed, separate).** Reduced indexes of references allowing duplicates receive owner data of variants
  the owner does not belong to. It was found while analysing #1681. Its read-side symptom on the reduced plan is
  hidden by the obstacle, but the polluted bitmaps remain.
- **#1711 (filed, separate).** A reflected reference declared after the original holds data is never backfilled.
  It was found by a capability-test premise.
- **Data that references one value under several groups loses that filter option.** The demo dataset is an
  example: Red, Gold and Pink sit under two *Color* groups, are rendered disabled in the smartwatches example of
  `solve/render-products-in-category.md`, and select nothing alone. The fix is in the data (reference a value under a
  single group); the upgrade note of #1695 says so.
- **#1698 (filed, enhancement).** `WITH_ALL_DIFFERENT_GROUPS`, an OR across references, is the place for the
  semantics rejected above.
- **Not backported.** The owner decided on 2026-10-01 not to backport #1681. The release lines keep the defect,
  with the same code since #677. The line of work later grew into result-changing behaviour and new refusals (see
  the upgrade notes), so any backport would have to be cut from the first #1681 commits alone.
- **The write path still records an `IndexActivity` query.** A conditional-expression re-evaluation's nested
  build calls `IndexActivity#recordQuery` in `QueryPlanBuilder#build`. This is pre-existing on dev. It could gate
  on `recordingRequestedCapabilities`, and that is a decision about index statistics, not capabilities.
- **Exclusive options inside an `or` were not probed.** `replaceFacetSelection` leaves a NOT-only selection outside
  its own NOT untouched and appends the exclusive option as a conjunct. That is the same placement question the
  `or`-nested fix answered for positive and negated options.
- **Pre-existing summary costs, not addressed:**
  - The NO_SELECTION summary builds a count formula for every facet of the global index and drops the zero ones:
    16,487 built for 3,524 listed. One `intersects` prefilter per facet would skip about 12,963 builds per query and
    shrink any per-count-formula cost 4.7×.
  - `MutableFormulaFinderAndReplacer` walks the whole cached tree for every option: about 1.35 M visits per
    COUNT_NO query. Caching the path to the `MutableFormula`s at cache-miss time would reduce that to a few nodes.
- **The NO_SELECTION shapes remain about 5 % slower than dev** (+3.0 ms COUNT, +3.7 ms IMPACT on the production
  retail corpus; see *Performance*). Invocation counts and allocation do not explain it, so the next step is a
  profile of both arms, not another counting pass. These are the shapes that build 16,487 count formulas for 3,524
  listed facets, so a remaining per-count-formula cost of about 200 ns would account for it; the `intersects`
  prefilter above would shrink any such cost 4.7×. They also allocate +0.8 MB (COUNT) and +2.1 MB (IMPACT) per
  query over dev.
- **Checks that still read data, or resolve in the wrong context** (reported, no witness):
  - A `hierarchyWithin` inside a checked nested filter still reads the real hierarchy while planning. The result
    is correct, but the check is not evaluation-free.
  - `HierarchyContentTranslator#verifyStopAtNode` translates a referenced entity's `stopAt(node(...))` in the
    enclosing query's context. A context-resolving constraint there (`hierarchyWithinSelf`) would resolve against
    the wrong type.
  - The `referenceContent` filter of a reference not indexed in a scope may go unchecked while planning: a missing
    reduced index answers `EmptyFormula`. Reported mid-work and not re-verified on the final tree.
  - `IndexSelectionVisitor`'s catalog branch still creates the archive catalog index for a catalog query over
    `ARCHIVED`.
- **Telemetry wording.** `HierarchyStatisticsProducer#getDescription` still names a reference whose set holds only
  check-only claims.
- **Accepted by design:**
  - The count of an option over a scoped or negated user filter is the option selected once for the whole query.
    A per-scope count would need a definition of "the option alone" per scope.
  - A generic `referenceSummary` filter must be evaluable on every summarized target type, or it fails. Split it into
    `referenceSummaryOfReference` constraints.

## Related work

- `2026-08-19-per-schema-capability-usage-statistics`: defines the counting unit this record enforces across
  nested, check and fetch-time plans. Its attribution rule for another collection's elements and its empty-plan
  sentence were corrected on this branch.
- `2026-09-15-bidirectional-reference-counterpart-rewrite`: the counterpart route of `referenceHaving`. When it
  answers, it records the owner-side capabilities the owner-side translation would have recorded.
- `2026-09-23-pick-first-reference-ordering-from-selection`: the pick-first ordering whose no-row path checks its
  children here (and whose check discards the prefetch it would register). It also fixed `inScope` admission on
  the prefetch route for pick-first only.
- `2026-09-25-attribute-is-null-in-reference-having`: the "first listed scope wins" rule for unique values. A
  hierarchy parent filter over several scopes never applies it across scopes, because each tree is searched as its
  scope alone.
- #677 introduced the scope handling this record corrects. #1670 (traverse ordering route parity) depends on it.

## Timeline

- **2026-09-30** — #1681 found by #1670's witness tests. Root cause traced. Design reviewed by an advisor and Codex
  ("expanded A").
- **2026-10-01** — The owner approved expanded A, the refusal of nested `inScope`, and no backport. The pollution
  defect was split out as #1685. #1686 was filed and folded in. Hierarchy semantics B decided.
- **2026-10-02** — Rebased onto dev. Adversarial rounds fixed the per-scope nested evaluation, the negated scope
  container and the `FutureNotFormula` in `inScope`. #1695 filed and folded in. The group-filter rule decided.
- **2026-10-03** — `facetHaving` follows the relation settings. Precedence and defaults unified. Unmanaged
  referenced types selectable.
- **2026-10-05** — Default negation and union decided. References AND-ed (#1698 filed). Unreferenced facet
  ungrouped ("d", "A"). Groups belong to the reference ("R"). Per-entry counts ("b"). Validity made independent of
  data. Capability counting for queries matching nothing.
- **2026-10-06** — Inverse count in negated groups confirmed. Counting unit enforced across contexts and owners.
  JMH A/B 05:26–06:24 CEST; regression explained by counting and reduced (H1–H5). #1711 filed. Final narrow review
  approved. The documentation run on the demo dataset showed "R" disabling three colors shared by two groups; the
  owner kept "R" and the example output was updated. PR #1720 opened; dev merged in.
- **2026-10-07** — Timed A/B of the final tree against dev, 03:59–05:26 CEST: selection shapes at parity, the two
  NO_SELECTION shapes still about 5 % slower.
