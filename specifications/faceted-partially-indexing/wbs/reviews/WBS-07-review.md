# WBS-07 Review: Reevaluate Facet Expression Executor

## Verdict: PASS WITH NOTES

## Summary

WBS-07 is a thorough and well-structured specification for the terminal pipeline stage of the cross-entity facet reevaluation mechanism. The document excels in its detailed source code research, fan-out examples, and comprehensive test plan. However, it contains several internal contradictions between its high-level pseudocode and the detailed source code research findings, an API inaccuracy in the executor pseudocode's bitmap operations, a type mismatch in the `AffectedFacetGroup` record definition, and a missing `scope` parameter on the `resolveTargetIndexes()` method signature.

## Issues Found

### Critical (must fix before implementation)

1. **Contradictory facet PK resolution for `GROUP_ENTITY_ATTRIBUTE`** -- The executor pseudocode in the "Full Executor Code" section (lines 88-91) states that "facetPK [is] recovered from `EntityIndexKey.discriminator` (which is a `ReferenceKey(referenceName, facetPK)`)". The source code research section (lines 522-524) explicitly corrects this: "The discriminator's `primaryKey` is the **group entity PK**. ... This is **incorrect** for `REFERENCED_GROUP_ENTITY` indexes." The research section then identifies that facet PKs must come from `referencedPrimaryKeysIndex.keySet()` instead. While the detailed task breakdown (Group 2, lines 650-662) reflects the corrected understanding, the high-level pseudocode has not been updated to match, meaning a developer reading only the top-level code may implement the wrong resolution logic.

> ✅ **Addressed**: Updated the Full Executor Code comment to correctly describe facet PK resolution: for GROUP_ENTITY_ATTRIBUTE, facetPKs come from `referencedPrimaryKeysIndex.keySet()` (not the discriminator); for REFERENCED_ENTITY_ATTRIBUTE, facetPK = mutatedEntityPK and groupPK is resolved via `FacetReferenceIndex.getGroupIdForFacet()`.

2. **`RoaringBitmapBackedBitmap.andNot()` does not exist** -- The executor pseudocode (line 152) calls `RoaringBitmapBackedBitmap.andNot(allAffectedOwnerPKs, currentlyTruePKs)`. The source code research section (line 575) explicitly notes "No static `andNot` exists on `RoaringBitmapBackedBitmap`" and prescribes using `RoaringBitmap.andNot()` directly with `BaseBitmap` wrapping. The pseudocode should be corrected or clearly annotated as intentionally simplified. The same issue exists in the parent analysis document (line 1475). This could cause a compilation error if a developer copies the pseudocode directly.

> ✅ **Addressed**: Replaced both bitmap operations in the pseudocode with correct API calls: `RoaringBitmapBackedBitmap.and(new RoaringBitmap[]{...})` for AND, and `new BaseBitmap(RoaringBitmap.andNot(...))` for ANDNOT. Also updated the Key Interfaces table to clarify that `andNot()` uses `RoaringBitmap.andNot()` directly. The parent analysis document is out of scope for this WBS fix.

3. **`AffectedFacetGroup.groupPK` is `int` but `FacetIndexContract.addFacet()` expects `@Nullable Integer`** -- The record definition (line 201) uses `int groupPK`, but `FacetIndexContract.addFacet()` (verified at `FacetIndexContract.java:59`) accepts `@Nullable Integer groupId`. Ungrouped facets (where the group is null) cannot be represented with a primitive `int`. The task breakdown (Group 5, line 708) acknowledges this: "Handle `groupPK` as `@Nullable Integer` -- convert `int groupPK` from `AffectedFacetEntry` to `Integer` (or use 0/sentinel for 'no group')." The record definition should use `@Nullable Integer groupPK` from the start, rather than requiring a runtime conversion workaround. This affects `AffectedFacetGroup`, `AffectedFacetEntry`, and all code that constructs or consumes them.

> ✅ **Addressed**: Changed `groupPK` from `int` to `@Nullable Integer` in both `AffectedFacetGroup` and `AffectedFacetEntry` record definitions, the Key Interfaces table, and the Group 1 task breakdown. Updated Group 5 to pass `entry.groupPK()` directly instead of requiring a conversion workaround.

### Important (should fix)

4. **`resolveTargetIndexes()` signature is missing the `scope` parameter** -- The method signature (lines 107-110) takes only `IndexMutationTarget target` and `String referenceName`, but inside `execute()` it needs to construct `EntityIndexKey(GLOBAL, scope)` to find the `GlobalEntityIndex`. The `scope` is available from `mutation.scope()` but is not passed to `resolveTargetIndexes()`. The method either needs the `scope` parameter added, or the mutation itself should be passed in. The task breakdown (Group 6, line 714) implicitly uses `scope` in the implementation details but does not flag the signature mismatch.

> ✅ **Addressed**: Added `@Nonnull Scope scope` parameter to the `resolveTargetIndexes()` method signature in the Full Executor Code, updated the call site in `execute()` to pass `mutation.scope()`, and updated the Group 6 task description to match.

5. **Constructor signatures for `GroupHaving` and `EntityHaving` are incorrectly stated** -- The source code research (line 584) states: "constructor `GroupHaving(@Nonnull FilterConstraint... children)`". The actual constructor (verified at `GroupHaving.java:135`) is `GroupHaving(@Nonnull FilterConstraint child)` -- a single child, not varargs. Same for `EntityHaving` (`EntityHaving.java:136`). The code example `new GroupHaving(new EntityPrimaryKeyInSet(mutatedPK))` (line 589) happens to work because it passes exactly one child, so it compiles. However, the documentation is misleading. If the parameterization ever needs to pass multiple children to `GroupHaving`, the current approach would fail.

> ✅ **Addressed**: Corrected the constructor signatures in the source code research section from `FilterConstraint... children` (varargs) to `FilterConstraint child` (single child) for both `EntityHaving` and `GroupHaving`, with a note emphasizing the single-child constraint.

6. **`RoaringBitmapBackedBitmap.and()` accepts `RoaringBitmap[]`, not `Bitmap` instances** -- The pseudocode (line 150) calls `RoaringBitmapBackedBitmap.and(allAffectedOwnerPKs, currentlyTruePKs)` where both arguments are `Bitmap` types. The actual method signature (verified at `RoaringBitmapBackedBitmap.java:131`) is `static Bitmap and(@Nonnull RoaringBitmap[] theBitmaps)` -- it takes a `RoaringBitmap[]` array, not individual `Bitmap` arguments. The source code research section (lines 574, 577) correctly documents this, but the pseudocode does not reflect it.

> ✅ **Addressed**: Fixed as part of issue #2 -- the pseudocode now uses `RoaringBitmapBackedBitmap.and(new RoaringBitmap[]{getRoaringBitmap(...), getRoaringBitmap(...)})` with the correct array parameter and `getRoaringBitmap()` extraction.

7. **Group PK resolution for `REFERENCED_ENTITY_ATTRIBUTE` is left as an open investigation** -- Group 3 (lines 664-681) and the source code research section (lines 615-622) discuss multiple approaches for determining the group PK when resolving `REFERENCED_ENTITY_ATTRIBUTE` dependencies, but does not settle on a definitive approach. The text explores looking up the `FacetIndex` on `GlobalEntityIndex`, scanning `FacetGroupIndex` instances, and adding a new `getGroupIdForFacet()` method. Group 10 (lines 741-746) adds the investigative task. While this is understandable given the complexity, a developer would need to make a design decision during implementation that should ideally be resolved in the spec. The tentative approach (add `getGroupIdForFacet()` to `FacetReferenceIndex`) seems sound, but it should be stated as the chosen approach rather than left as "investigate."

> ✅ **Addressed**: Replaced all investigative language with definitive statements. The chosen approach is now clearly stated: add `@Nullable Integer getGroupIdForFacet(int facetPK)` to `FacetReferenceIndex`. Updated the REFERENCED_ENTITY_ATTRIBUTE Resolution Path section, the source code research section, Group 3 task, and Group 10 task to all reflect this as the decided approach.

8. **Target index routing simplification may miss `ReducedEntityIndex` facet state** -- Group 6, step 4 (line 717) proposes an MVP simplification: "Target only `GlobalEntityIndex` for the MVP." However, acceptance criterion 11 (line 474) states: "Target index routing: Operations target `GlobalEntityIndex` always, plus `ReducedEntityIndex` only when it exists with `FOR_FILTERING_AND_PARTITIONING` level." This internal contradiction means either the acceptance criteria or the task breakdown needs to be aligned. If the MVP omits `ReducedEntityIndex` targeting, the acceptance criterion should be updated, or the divergence explicitly documented as technical debt.

> ✅ **Addressed**: Removed the MVP simplification from Group 6. The task breakdown now aligns with acceptance criterion 11: target `GlobalEntityIndex` always, plus `ReducedEntityIndex` instances when the reference schema has `FOR_FILTERING_AND_PARTITIONING` level.

### Minor (nice to have)

9. **Inconsistent naming: `entityGroupHaving` vs `GroupHaving`** -- The document switches between the query DSL method name `entityGroupHaving(...)` (used in FilterBy constraint examples, e.g., lines 133, 252, 357) and the Java class name `GroupHaving` (used in code examples, e.g., line 589). While both are correct in their respective contexts, a note clarifying this distinction would help implementers unfamiliar with the query DSL.

> ✅ **Addressed**: Added a naming note in the FilterBy Parameterization section explaining that `entityGroupHaving(...)` / `entityHaving(...)` are the query DSL method names while `GroupHaving` / `EntityHaving` are the corresponding Java class names.

10. **Lazy vs cached computation of `allOwnerPKs()` not fully specified** -- The `AffectedEntityResolution.allOwnerPKs()` method (line 196) says "compute lazily or cached" in implementation notes (line 487), and the task breakdown (line 644) says "Cache or compute lazily." A recommendation should be made for the typical use case (the method is called once, then `entriesForOwnerPKs()` is called twice), where lazy computation without caching would be sufficient.

> ✅ **Addressed**: Updated the implementation notes and Group 1 task to recommend eager computation without caching, since `allOwnerPKs()` is called only once. Clarified the call frequency rationale.

11. **`ReferenceHaving` scope filter injection strategy could be more explicit** -- The `parameterize()` method description (Group 4, lines 684-693) discusses injecting PK-scoping constraints into the `referenceHaving` node. It mentions the strategy of extracting children and reconstructing the tree but does not provide a complete code example for the tree manipulation. Given the complexity of constraint tree manipulation (finding the right `ReferenceHaving` node, handling existing `And` wrappers), a more complete pseudocode example would reduce implementation ambiguity.

> ✅ **Addressed**: Added a complete pseudocode example in Group 4 showing the full constraint tree manipulation: walking `FilterBy` children, finding the matching `ReferenceHaving` node, injecting the PK-scoping constraint via `And`, and reconstructing the `FilterBy`.

12. **Test location inconsistency** -- The tests are placed in `evita_test/evita_functional_tests` but the supporting records (`AffectedEntityResolution`, etc.) and the executor class are in `evita_engine`. This is consistent with the project's existing test organization pattern, but it's worth noting that unit tests for purely internal engine records could also be placed closer to the source.

> ❌ **Declined**: The test location follows the project's existing test organization pattern where engine tests are placed in `evita_test/evita_functional_tests`. Deviating from the established pattern for this one WBS would introduce inconsistency across the project.

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | Warning | The high-level executor pseudocode contradicts the detailed source code research findings in two important areas (facet PK resolution and `andNot` API). Constructor signatures are incorrectly documented. A developer reading only the pseudocode would implement incorrectly. The detailed task breakdown is clear and actionable, but the contradictions with the top-level code create confusion. |
| Consistency | Warning | Three internal contradictions: (1) pseudocode says facetPK comes from discriminator, research says it does not; (2) pseudocode uses non-existent `RoaringBitmapBackedBitmap.andNot()`, research section corrects this; (3) Group 6 MVP simplification contradicts acceptance criterion 11 on target index routing. Naming between WBS and parent analysis is consistent. Class/package paths are consistent with WBS-05 and WBS-06. |
| Coherence | Pass | The document flows logically: objective, scope, dependencies, technical context with full executor code, source code research with corrections, detailed task groups, then test cases. The task groups are well-ordered (supporting records first, then resolution methods, then parameterization, then the main execute method, then registration). Dependencies between groups are clear. |
| Completeness | Warning | Group PK resolution for `REFERENCED_ENTITY_ATTRIBUTE` is left as an investigation rather than a decided approach. The `scope` parameter is missing from `resolveTargetIndexes()`. The `groupPK` type mismatch (`int` vs `@Nullable Integer`) is acknowledged but not resolved in the record definitions. All other implementation steps are well-covered. Error handling for missing indexes (null checks) is addressed. The "Why No Other Mutation Types Are Needed" table provides good coverage of scenario completeness. |
| Test Coverage | Pass | Excellent test coverage across all aspects: supporting records (13 tests), PK resolution for both dependency types (11 tests), filter parameterization (4 tests), bitmap operations (5 tests), add/remove operations (6 tests), target index routing (3 tests), null handling (2 tests), idempotency (3 tests), full pipeline fan-out scenarios (4 tests), edge cases (6 tests), and additions to existing test classes for new accessor methods (10 tests). Total: 67 test cases. Negative cases (empty sets, null triggers, missing schemas) are covered. The only gap is that there are no tests for the `parameterize()` method's behavior when the trigger's `FilterBy` has NO `referenceHaving` node (a malformed trigger), but this scenario should be prevented by construction in WBS-03. |
