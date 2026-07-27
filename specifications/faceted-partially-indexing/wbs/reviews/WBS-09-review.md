# WBS-09 Review: Local Trigger Integration

## Verdict: PASS WITH NOTES

## Summary

WBS-09 is a well-structured and thorough document that covers the integration of expression-based facet indexing triggers into `ReferenceIndexMutator`. The source code research section is particularly strong, with verified line numbers and accurate method signatures. However, there is one internal contradiction regarding mutation ordering that requires clarification, phantom task references in the test section, and a minor accessor type inconsistency in the dependency table. None of these are blockers -- the document self-corrects the mutation ordering issue in its own CRITICAL FINDING section, and the implementation guidance is otherwise clear.

## Issues Found

### Critical (must fix before implementation)

_None._

### Important (should fix)

- **Internal contradiction on mutation ordering.** The Technical Context section (line 26) states: "attribute mutations sort before reference mutations via `LocalMutation#compareTo`". Acceptance Criterion #6 (line 226) repeats this: "Mutation ordering is preserved: attribute mutations are applied before reference mutations within the same entity, so the expression sees updated values." However, the Source Code Research section contains a CRITICAL FINDING (lines 423-431) that directly contradicts both claims: mutations are NOT sorted by the framework -- `compareTo` exists for CDC ordering, not execution ordering. The CRITICAL FINDING correctly explains that the `WritableEntityStorageContainerAccessor` mechanism provides the necessary visibility regardless of mutation order. The Technical Context and Acceptance Criteria sections must be updated to align with the CRITICAL FINDING. As written, a developer reading only the top sections would have a false assumption about ordering guarantees.

> ✅ **Addressed**: Updated four locations to align with the CRITICAL FINDING: (1) In Scope bullet now says expression evaluation works regardless of mutation ordering via `WritableEntityStorageContainerAccessor`; (2) Technical Context "Mutation Ordering" section rewritten to explain that `compareTo` is for CDC ordering, not execution ordering, and that correctness is ensured by the storage accessor reflecting previously applied mutations; (3) Key Interfaces table entry for `LocalMutation#compareTo` updated; (4) Acceptance Criterion #6 rewritten to describe the accessor-based correctness mechanism instead of claiming ordering guarantees.

- **Phantom task references in test case categories.** Multiple test categories reference "Task 13.x" (e.g., Task 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11), but there is no Group 13 in the Detailed Task List (Groups 1-12 only). These references appear to be leftover from a previous iteration of the document. They should either be removed or the referenced tasks should be identified and corrected. This creates confusion about traceability between tests and implementation tasks.

> ✅ **Addressed**: Removed all phantom "13.x" task references from 13 test category headers. Each category now references only the valid task numbers from Groups 1-12.

- **Accessor type inconsistency in dependency table.** The dependency table (line 46) describes the `evaluate()` signature as `evaluate(int entityPK, ReferenceKey refKey, EntityStoragePartAccessor accessor)`, while the parent analysis document and the rest of this WBS (lines 24, 158, 209, 229, 491, 582) consistently use `WritableEntityStorageContainerAccessor`. Although `WritableEntityStorageContainerAccessor extends EntityStoragePartAccessor`, the specific type matters for the method signature contract. This should be reconciled with WBS-03 (which defines the actual interface) to use the correct type.

> ✅ **Addressed**: Updated the dependency table to use `WritableEntityStorageContainerAccessor` in the `evaluate()` method signature, matching the parent analysis document and the rest of the WBS.

### Minor (nice to have)

- **`wasFaceted()` implementation complexity (Task 2.1).** The task description says to "iterate its `getNotGroupedFacets()` and `getGroupedFacets()` to find the `FacetGroupIndex` containing a `FacetIdIndex`." However, the existing `isFacetPresentInGroup()` helper (line 1238-1256 in `ReferenceIndexMutator`) provides a simpler pattern: it checks a specific group bucket rather than iterating all groups. The `wasFaceted()` implementation could be simplified by checking: (a) the not-grouped bucket for `FacetIdIndex(referenceKey.primaryKey()).records.contains(entityPK)`, then (b) all grouped buckets. Alternatively, `EntityIndex.isFacetInGroup()` could be leveraged more directly. The current task description is correct but could be clearer about the navigation strategy.

> ✅ **Addressed**: Updated Task 2.1 to clarify the navigation strategy: check ungrouped bucket first, then grouped buckets. Added a note referencing `isFacetPresentInGroup()` as a similar navigation pattern, while explaining that `wasFaceted()` differs because it must search across all groups (the group is not known a priori).

- **Test class `ReferenceIndexMutatorDecisionMatrixTest` combines concerns.** This test class covers both the `wasFaceted()` helper and the full `InsertReferenceMutation`/`RemoveReferenceMutation` flows. The `InsertReferenceMutation` and `RemoveReferenceMutation` categories test integration-level behavior (multiple indexes, cross-reference propagation) that might be better placed in a dedicated integration test class or in `ReferenceIndexMutatorExpressionGuardTest` alongside the other per-method tests.

> ❌ **Declined**: The current grouping is intentional. The `InsertReferenceMutation` and `RemoveReferenceMutation` test categories in `ReferenceIndexMutatorDecisionMatrixTest` specifically test the decision matrix behavior (was/now faceted combinations) applied through those mutations, not the expression guard itself. They share test fixtures with the `wasFaceted()` and decision matrix tests (pre-populated facet state). The per-method guard tests in `ReferenceIndexMutatorExpressionGuardTest` cover a different concern (whether the guard correctly gates individual method calls). The separation is coherent.

- **Task 10.3 scope is very broad.** Task 10.3 describes a NEW code path in `EntityIndexLocalMutationExecutor.applyMutation()` that must be added after each non-reference mutation type. This is the most architecturally significant change in the WBS (introducing re-evaluation dispatch for entity attributes, associated data, and parent mutations), but its complexity is spread across Tasks 10.3-10.7. Consider whether a shared "re-evaluation dispatch" helper should be a dedicated task in Group 10 (a "10.0" task) to consolidate the pattern before applying it per mutation type.

> ✅ **Addressed**: Added an implementation note to Task 10.3 recommending extraction of a shared re-evaluation dispatch helper (e.g., `dispatchFacetReEvaluation()`) before implementing per-mutation-type dispatch in Tasks 10.4-10.7, to consolidate the common pattern.

- **Line 212 in Key Interfaces table still claims `LocalMutation#compareTo` ensures ordering.** This is part of the same mutation ordering contradiction noted above but appears in a third location (Key Interfaces table). Should be updated along with the other two occurrences.

> ✅ **Addressed**: Already fixed as part of the mutation ordering contradiction resolution above. The Key Interfaces table entry now reads: "Used for CDC ordering; does NOT control execution ordering of mutations within a single entity".

- **No explicit error handling for `evaluate()` returning non-boolean or throwing.** The document mentions `ExpressionEvaluationException` for null-safe access violations, but does not specify what happens if `evaluate()` throws an unexpected exception mid-mutation. Should the mutation processing be rolled back? Should the facet state remain unchanged? The existing undo mechanism (`undoActionConsumer`) may cover this implicitly, but it is worth documenting the expectation explicitly.

> ✅ **Addressed**: Added a new bullet in the Implementation Notes section documenting the error handling expectation: exceptions from `evaluate()` propagate up and abort mutation processing; the `undoActionConsumer` mechanism and transactional infrastructure ensure atomicity; no catch-and-continue logic is needed.

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | ✅ | Task descriptions are specific and actionable. Method signatures, line numbers, and file paths are verified against the actual codebase. The CRITICAL FINDING on mutation ordering is clearly explained. Code examples are accurate. |
| Consistency | ⚠️ | The mutation ordering claim is internally contradictory: the Technical Context and AC #6 state one thing, the CRITICAL FINDING states the opposite. The accessor type (`EntityStoragePartAccessor` vs `WritableEntityStorageContainerAccessor`) is inconsistent between the dependency table and the rest of the document. Phantom Task 13.x references in test categories point to nonexistent task groups. |
| Coherence | ✅ | The document flows logically from objective through scope, dependencies, technical context, source code research, task breakdown, and test cases. Task groups are ordered sensibly (infrastructure first, then per-method guards, then re-evaluation dispatch, then decision matrix, then scope handling). The re-evaluation decision matrix is consistently applied across all relevant paths. |
| Completeness | ✅ | All six facet-modifying methods in `ReferenceIndexMutator` are covered. All 10 local trigger mutations from the parent analysis are addressed. The source code research section is exceptionally thorough -- verified call sites, method signatures, and even discovered and documented the mutation ordering misconception. Edge cases (group changes causing expression flips, references never faceted being removed) are identified. The `wasFaceted()` helper navigation path is fully specified. |
| Test Coverage | ✅ | Test cases comprehensively cover all four decision matrix combinations, all six facet methods, backward compatibility (no expression defined), scope-aware evaluation, null safety, mutation ordering, storage part access, and transactional atomicity. Tests for `indexAllFacets()`/`removeAllFacets()` correctly test per-reference evaluation within loops. The only gap is the phantom Task 13.x references which should be cleaned up for traceability. |
