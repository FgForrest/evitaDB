# WBS-10 Review: Local Mutation Executor Collector Integration

## Verdict: PASS WITH NOTES

## Summary

WBS-10 is a well-structured, highly detailed specification for integrating the Step 5b index-trigger dispatch loop into `LocalMutationExecutorCollector` and adding the thin `applyIndexMutations()` dispatcher to `EntityCollection`. The document demonstrates exceptional attention to the existing codebase -- line numbers, variable scopes, and method signatures match the actual source code. The test plan is comprehensive, covering ordering, nesting, error handling, WAL exclusion, and idempotency. There are a few minor consistency issues and one important gap around rollback semantics for partial Step 5b execution.

## Issues Found

### Critical (must fix before implementation)

None.

### Important (should fix)

- **Incorrect WBS reference for `CatalogExpressionTriggerRegistry` in Out of Scope.** Line 45 of the Out of Scope section states: "`CatalogExpressionTriggerRegistry` trigger registration and lookup -- see WBS-06." However, WBS-06's own Out of Scope explicitly says: "`ExpressionIndexTrigger` and `CatalogExpressionTriggerRegistry` -- covered in their own WBS tasks." The correct WBS is **WBS-04**, which is titled "`CatalogExpressionTriggerRegistry` -- Cross-Schema Wiring and Trigger Lookup." Meanwhile, the Dependencies section correctly references WBS-08 for `popIndexImplicitMutations()` which consults the registry, but the Out of Scope misdirection could confuse someone tracing the dependency chain.

> ✅ **Addressed**: Changed "WBS-06" to "WBS-04" in the Out of Scope reference for `CatalogExpressionTriggerRegistry`.

- **Partial Step 5b execution and rollback semantics are underspecified.** Test case J (Category J, `exception_in_one_entityIndexMutation_skips_remaining_envelopes`) correctly identifies that if the second `EntityIndexMutation` dispatch fails, the third is skipped. However, the specification does not discuss what happens to the index changes already applied by the first envelope's executor. The Implementation Notes section says "Error handling: If `getCollectionForEntityOrThrowException()` throws... the exception should propagate and fail the transaction." This is correct at the transaction level, but the document should clarify that the first envelope's index modifications (bitmap add/remove on the target collection's transactional indexes) are reverted by the STM rollback at transaction level, not by any Step 5b-specific rollback logic. Without this clarification, an implementer might wonder whether they need to track and undo partial Step 5b side effects. The rollback/commit section (Group 6, tasks 6.2 and 6.3) only verifies the collector's own rollback is unaffected, but does not address transactional consistency of the target collection's index modifications.

> ✅ **Addressed**: Added a "Partial Step 5b rollback" bullet to Implementation Notes clarifying that STM transaction-level rollback handles reverting partial index modifications — no Step 5b-specific undo logic is needed.

- **Dependency on WBS-07 is stated but WBS-06 is the actual provider.** The Dependencies section lists "WBS-07 (IndexMutationExecutorRegistry + IndexMutationExecutor)" as a dependency. However, WBS-06 is titled "`IndexMutationTarget`, `IndexMutationExecutor`, `IndexMutationExecutorRegistry` -- Target-Side Dispatch Infrastructure." WBS-07 covers `ReevaluateFacetExpressionExecutor` (the concrete executor implementation), not the dispatch infrastructure itself. The Key Interfaces table also says `IndexMutationExecutorRegistry` is from WBS-06 implicitly (by its scope). This WBS reference mismatch (WBS-07 vs WBS-06) should be corrected to avoid dependency tracking confusion.

> ✅ **Addressed**: Changed the dependency from "WBS-07" to "WBS-06" for the dispatch infrastructure (IndexMutationTarget, IndexMutationExecutorRegistry, IndexMutationExecutor). Also corrected all downstream WBS-07 references throughout the document (Out of Scope, source code research, task groups 2 and 3, integration test prerequisites) to consistently use WBS-06 for the dispatch infrastructure and WBS-07 for the concrete executor.

- **`applyIndexMutations()` visibility claim says "package-private or protected" but `EntityCollection` is `final`.** Implementation Notes state the method can be "package-private within `io.evitadb.core.collection` or protected if the method needs to be accessible from `EntityCollection` subclasses." Since `EntityCollection` is declared `public final class` (confirmed at line 180), it cannot have subclasses, making the "protected" option nonsensical. The document should simply state "package-private" without the subclass caveat.

> ✅ **Addressed**: Removed the "or protected" option and the subclass caveat. The Implementation Notes now state "package-private" only, with an explicit note that `EntityCollection` is `final`.

### Minor (nice to have)

- **Inconsistent method accessor name: `entityType()` vs `getEntityType()`.** The Step 5b code example (line 126) uses `indexMutation.getEntityType()` while the source code research section (line 556) uses `indexMutation.entityType()`. Since `EntityIndexMutation` is defined as a record (per WBS-05), the accessor is `entityType()` (record-style, no `get` prefix). The Step 5b code example should use `entityType()` for consistency with Java record conventions.

> ✅ **Addressed**: Replaced all occurrences of `indexMutation.getEntityType()` with `indexMutation.entityType()` throughout the document (In Scope, code example, and Acceptance Criteria).

- **Redundant explanation of `ServerEntityMutation` pipeline.** The Technical Context section provides detailed coverage of `ServerEntityMutation`, `ServerEntityUpsertMutation`, and `ServerEntityRemoveMutation` internals (lines 442-463). While useful context, this information is not directly actionable for the implementation tasks in this WBS. It duplicates knowledge better owned by the WBS that introduced these types. Consider trimming to a one-paragraph summary.

> ❌ **Declined**: The `ServerEntityMutation` pipeline section provides context that is directly relevant to understanding Step 5b's interaction with WAL replay (the "Step 5b impact on WAL replay" paragraph is actionable). It also explains why `ServerEntityRemoveMutation.getImplicitMutationsBehavior()` is non-empty, which supports the design decision that Step 5b runs unconditionally. Trimming this section would lose necessary context for implementers.

- **Test Category I (`step5b_runs_within_same_transaction_as_step5a`) is somewhat vacuous.** Step 5b runs inside the same `execute()` call as Step 5a, in the same thread, with no transaction boundary manipulation. There is no mechanism by which they could run in different transactions. The test adds little value unless it also verifies a concrete observable property (e.g., `DataStoreMemoryBuffer` instance identity).

> ✅ **Addressed**: Added a concrete observable property check to the test description: the test now verifies `DataStoreMemoryBuffer` instance identity between Step 5a and Step 5b.

- **The stub strategy paragraph (line 485) acknowledges WBS-07 might not be ready.** This is good pragmatic advice but introduces temporal coupling into the WBS document. If the WBS tasks are implemented in dependency order (as the dependency graph implies), this paragraph is unnecessary. Consider marking it as an optional implementation note rather than a main research finding.

> ✅ **Addressed**: Marked the stub strategy paragraph as "Optional implementation note" and added a trailing sentence noting it is unnecessary if WBS tasks are implemented in dependency order. Also corrected the WBS-07 references in this paragraph to WBS-06.

- **Group 3, Task 3.2 has imprecise method signatures.** It says "`EntityIndex getEntityIndex(EntityIndexKey)` -- wraps existing `this.indexes.get(key)`" but WBS-06 defines the interface method as `getIndexIfExists(EntityIndexKey)` returning `@Nullable EntityIndex` and `getOrCreateIndex(EntityIndexKey)` returning `@Nonnull EntityIndex`. The method name and nullability semantics should match WBS-06's interface definition to avoid confusion.

> ✅ **Addressed**: Replaced the single `getEntityIndex(EntityIndexKey)` entry with two methods matching WBS-06's interface: `@Nullable EntityIndex getIndexIfExists(EntityIndexKey)` and `@Nonnull EntityIndex getOrCreateIndex(EntityIndexKey)`.

- **No explicit mention of thread safety for `applyIndexMutations()`.** The document correctly states that the method is called within a transaction's mutation processing, which is single-threaded. However, a brief note confirming that `applyIndexMutations()` does not need synchronization (because it runs within the same transactional scope as `applyMutations()`) would be helpful for implementers.

> ✅ **Addressed**: Added a "Thread safety" bullet to Implementation Notes confirming that `applyIndexMutations()` does not need synchronization because it runs within the same single-threaded transactional scope as `applyMutations()`.

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | ✅ | Task descriptions are exceptionally detailed. Line numbers, variable names, and exact insertion points are specified. Code examples are clear and correct (modulo the minor `entityType()` vs `getEntityType()` inconsistency). The "should Step 5b be inside the guard" analysis is particularly well-reasoned. |
| Consistency | ⚠️ | Two WBS cross-references are incorrect: WBS-06 cited for CatalogExpressionTriggerRegistry (should be WBS-04), and WBS-07 cited for IndexMutationExecutorRegistry/IndexMutationExecutor (should be WBS-06). Record accessor naming is inconsistent between code examples. These are not blocking but could cause confusion during implementation. |
| Coherence | ✅ | The document flows logically: Objective -> Scope -> Dependencies -> Technical Context (existing flow, new Step 5b, ordering justification, nesting) -> Research Results (exact code analysis) -> Detailed Tasks (grouped by concern) -> Test Cases (grouped by category). Task groups follow a sensible implementation order. |
| Completeness | ✅ | All necessary implementation steps are identified. The 10 task groups cover the dispatch loop, `applyIndexMutations()`, `IndexMutationTarget` implementation, nesting verification, routing correctness, WAL exclusion, trapChanges interaction, traffic recording, error handling, and documentation. Edge cases (empty arrays, entity removal path, nested levels) are well-covered. The only gap is the partial Step 5b rollback semantics noted above, which is important but not critical since the STM layer handles this implicitly. |
| Test Coverage | ✅ | 37+ test cases across 18 categories covering dispatch loop insertion, container-first ordering, routing, nesting at multiple levels, empty results, thin dispatcher verification, local-vs-cross trigger ordering, WAL exclusion, transaction boundaries, error propagation (including nested exception suppression), guard independence, entity removal, traffic recording, cross-transaction visibility, idempotent de-duplication, and WAL replay. Both unit tests (with mocks) and integration tests are specified. No significant implementation path lacks a corresponding test case. |
