# WBS-11 Review: Initial Catalog Load and Cold Start Wiring

## Verdict: PASS WITH NOTES

## Summary

WBS-11 is a thorough and well-researched document that covers the cold start registry wiring with impressive depth. The source code research is detailed and accurate -- line references to `Catalog.java`, `EntityCollection.java`, `Evita.java`, and `ReflectedReferenceSchema.java` have been verified against the codebase. The three initialization code paths (cold start, goLive, transaction commit) are correctly identified and the insertion points for registry population are sound. The test plan is comprehensive with 9 test classes covering the major scenarios. A few issues around task overlap with WBS-04, missing error handling specification, and minor test gaps are identified below.

## Issues Found

### Critical (must fix before implementation)

None.

### Important (should fix)

1. **Significant task overlap with WBS-04 -- ownership boundaries are blurred.** Task 6.1 specifies implementing `buildInitialExpressionTriggerRegistry()` on `Catalog`, but WBS-04 task 8.1 specifies the same method. Task 1.1 adds the call to this method in `loadCatalog()`, but WBS-04 task 8.1 also specifies the same insertion point (after `initSchema()` loop, before `onSuccess.accept()`). The WBS should explicitly state that Group 6 tasks are "verification of WBS-04 deliverables" rather than implementation tasks, or cross-reference them clearly as "implemented by WBS-04 task 8.1, verified here." Currently, a developer picking up WBS-11 might implement code that WBS-04 already delivers.

> ✅ **Addressed**: Renamed Group 6 to "Verification of `buildInitialExpressionTriggerRegistry()` method on `Catalog`" and added a note clarifying that the method and its JavaDoc are implemented by WBS-04 tasks 8.1-8.2. Tasks 6.1 and 6.2 reworded from "Implement"/"Add" to "Verify" to make ownership boundaries explicit.

2. **No error handling specification for `buildInitialExpressionTriggerRegistry()` failure.** If `buildFromSchemas()` throws an exception (e.g., due to a corrupt schema with an unparseable expression, or an `AccessedDataFinder` failure on a malformed expression), the document does not specify what should happen. Should the catalog fail to load entirely? Should it log a warning and continue with an empty/partial registry? This is particularly important for Path 1 (cold start from disk), where the exception would propagate through the `ProgressingFuture` completion handler. The existing error handler (line 499) terminates all collections and the catalog, but it is unclear if a registry build failure should trigger the same catastrophic shutdown or be handled more gracefully.

> ✅ **Addressed**: Added a new "Error handling for registry build failures" section to Technical Context. Specifies that failures should propagate as catalog load failures (not be silently swallowed), with per-path behavior: Path 1 uses the existing error handler to terminate and call `onFailure`, Path 2 propagates out of `goLive()` leaving the WARMING_UP catalog intact.

3. **Task 5.4 contains an incorrect claim about `TransactionalReference` propagation in `createCopyWithMergedTransactionalMemory()`.** The task states: "The registry `TransactionalReference` is initialized from `previousCatalogVersion.getExpressionTriggerRegistry()`" and "the 6-parameter constructor copies the value directly." However, the 6-parameter constructor at line 691 delegates to the 8-parameter constructor with `initSchemas=false`. In the 8-parameter constructor (line 711-784), there is no existing code that copies the `expressionTriggerRegistry` field from `previousCatalogVersion` -- this field does not exist yet. The WBS correctly notes this is specified in WBS-04 task 6.3, but the language in task 5.4 reads as if this propagation already works automatically. The description should clarify that this propagation depends on WBS-04 task 6.3 being implemented first.

> ✅ **Addressed**: Added explicit prerequisite note to task 5.4: "Prerequisite: WBS-04 task 6.3 must be implemented first" with description of what it adds. Reworded "copies the value directly" to "initializes it from the previous version's committed value" to avoid implying the code already exists.

4. **Missing specification for the `goLive()` path when `previousCatalogVersion` has an empty registry.** In the `goLive()` flow, the copy constructor initializes the registry from `previousCatalogVersion` (which is the WARMING_UP catalog). If the WARMING_UP catalog never had `buildInitialExpressionTriggerRegistry()` called (e.g., schemas were added directly in WARMING_UP state without going through the cold start path), the initial value is `EMPTY`. The subsequent `buildInitialExpressionTriggerRegistry()` call in the constructor overwrites this, so it works correctly. However, the document does not explicitly trace this scenario. Task 2.3 verifies the field is initialized before `initSchema()`, but does not address what happens if `previousCatalogVersion` itself was never populated.

> ✅ **Addressed**: Added explicit paragraph in the "Copy constructor registry propagation" section tracing the `goLive()` scenario where `previousCatalogVersion` has an `EMPTY` registry. Explains that the initial value is a placeholder overwritten by `buildInitialExpressionTriggerRegistry()` when `initSchemas=true`.

### Minor (nice to have)

1. **Line number references may become stale.** The document extensively references specific line numbers (e.g., "line 489-497 of `Catalog.java`", "line 1739-1741 of `ReflectedReferenceSchema.java`"). While currently accurate, these will drift as other WBS tasks modify these files. Consider using method/block references (e.g., "in the `loadCatalog()` completion handler, after the `initSchema()` loop") as the primary locator, with line numbers as parenthetical supplements.

> ❌ **Declined**: The document already uses method/block references alongside line numbers in most cases (e.g., "the `loadCatalog()` completion handler (line 489-497 of `Catalog.java`)", "the 8-parameter copy constructor (line 711-784)"). Line numbers are supplementary context that aid implementation but do not replace the structural references. Wholesale reformatting would be disruptive for minimal benefit, and developers are expected to use the method names as primary locators.

2. **Task 8.2 (entity schema rename) is a verification-only task with no clear test case.** The task describes verifying that rename correctly re-indexes triggers, but none of the test classes include a rename scenario. Consider adding a test case to `CatalogCollectionRemovalRegistryTest` (or a new class) that renames an entity collection and verifies the registry updates triggers under the new entity type name.

> ✅ **Addressed**: Added "entity collection rename (task 8.2)" test category to Test Class 7 (`CatalogCollectionRemovalRegistryTest`) with two test cases: one for renaming the owner entity type and one for renaming the dependency entity type used as a registry key.

3. **Test Class 1, test `cold_start_registry_should_be_populated_before_wal_replay` relies on internal mutation observation.** The test description says to "verify the catalog loads successfully and WAL replay produces the expected `ReevaluateFacetExpressionMutation` instances." However, `ReevaluateFacetExpressionMutation` is an internal, engine-only mutation that is never externalized. The test would need to intercept or instrument the `applyIndexMutations()` path, but the document does not specify how. Consider specifying the observation mechanism (e.g., a spy/mock on `EntityCollection.applyIndexMutations()`, or verifying the end state of facet indexes instead of mutation generation).

> ✅ **Addressed**: Rewrote three test descriptions (`cold_start_registry_should_be_populated_before_wal_replay`, `wal_replay_upsert_mutation_should_generate_cross_entity_reevaluation`, `wal_replay_remove_mutation_should_generate_cross_entity_reevaluation`) to verify end-state of facet indexes via facet query results instead of observing internal `ReevaluateFacetExpressionMutation` instances.

4. **Test Class 3, test `init_schema_ordering_should_not_affect_final_registry_content` proposes controlling collection iteration order.** The test says "repeat with a different entity type load ordering (by mocking or controlling the collection iteration order)." Since `initBulk.collections()` is a `HashMap`, iteration order is already nondeterministic. The test may need a more deterministic approach to prove ordering independence -- for example, explicitly calling `initSchema()` in two different known orders in the test.

> ✅ **Addressed**: Rewrote the test description to explicitly call `initSchema()` in two known orderings (A-B-C-D-E and E-D-C-B-A) followed by `buildInitialExpressionTriggerRegistry()`, rather than relying on HashMap non-determinism.

5. **Group 7 analysis reaches a "final recommendation" within the task list.** Tasks 7.1-7.3 contain analysis and a recommendation ("Do NOT guard the hook... AND call `buildFromSchemas()` after the loop as a definitive reset") that belongs in the Technical Context or Implementation Notes sections, not in the task list. The task list should contain actionable items, not analysis conclusions.

> ✅ **Addressed**: Rewrote task 7.3 as an actionable verification task (verify necessity, confirm via specific test case) rather than an analysis block with a "Conclusion" statement. The analysis and recommendation ("Do NOT guard the hook... AND call `buildFromSchemas()` after the loop as a definitive reset") already exists in the Source Code Research section and does not need to be repeated in the task list.

6. **Missing test for multi-scope expressions during cold start.** While test `cold_start_should_produce_two_triggers_for_reference_with_expressions_in_both_scopes` covers multiple scopes, there is no test verifying that after cold start, a mutation triggers re-evaluation in the correct scope only (not in both scopes when only one is relevant). This is arguably covered by WBS-08 (source-side detection), but a cold-start-specific round-trip test would increase confidence.

> ✅ **Addressed**: Added test `cold_start_multi_scope_mutation_should_trigger_reevaluation_in_correct_scope_only` to Test Class 1 under a new "multi-scope correctness after cold start" category. The test verifies that after cold start with dual-scope expressions, a mutation only updates the facet index for the relevant scope.

7. **Acceptance Criterion 6 ("without ordering issues") could be more specific.** The criterion says "handles the case where multiple entity collections reference each other (mutual cross-schema triggers) without ordering issues." It would benefit from specifying what "without ordering issues" means concretely: (a) all triggers are registered regardless of iteration order, (b) no exceptions are thrown, (c) the final registry state is identical across different orderings.

> ✅ **Addressed**: Expanded Acceptance Criterion 6 to explicitly define "without ordering issues" with three concrete sub-criteria: (a) all triggers registered regardless of iteration order, (b) no exceptions thrown, (c) final registry state identical across orderings.

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | ✅ | Task descriptions are specific, with exact code locations and method signatures. Technical terms are consistently used. The three initialization paths are clearly delineated. |
| Consistency | ✅ | Consistent with the parent analysis and dependent WBS documents. WBS-04 ownership boundaries for Group 6 tasks are now explicitly clarified. The `(mutatedEntityType, dependencyType)` key notation is used in the Scope section but `(targetEntityType, dependencyType)` appears in the Key Interfaces section -- these refer to different things but could confuse a reader. |
| Coherence | ✅ | The document flows logically from objective through scope, dependencies, technical context, source code research, task groups, and test cases. Task groups are ordered sensibly: cold start path first, goLive second, reflected references third, WAL replay fourth, state transitions fifth, then the implementation and interaction tasks. |
| Completeness | ✅ | The implementation tasks and code paths are thoroughly covered. Error handling for registry build failures is now specified. Entity rename test gap has been filled. The document does not address what happens if a catalog is restored from a backup where the schema and indexes are inconsistent (edge case, arguably out of scope). |
| Test Coverage | ✅ | Nine test classes with 40+ test cases covering cold start, goLive, reflected references, mutual cross-references, empty catalogs, registry equivalence, collection removal, transaction propagation, and initSchema interaction. Test categories map to task groups and acceptance criteria. Negative cases (empty catalogs, non-conditional references) are included. Rename tests and multi-scope correctness tests have been added. Observation mechanism for WAL replay tests now uses end-state facet index verification. |
