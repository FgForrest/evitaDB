# WBS-01 Review: ExpressionToQueryTranslator

## Verdict: PASS WITH NOTES

## Summary

This is a high-quality WBS document that is well-researched, clearly structured, and thoroughly cross-referenced against the parent analysis document. The source code research section is particularly strong -- it identifies actual class names, package locations, and method signatures verified against the codebase. There are a few consistency issues (primarily the `entityGroupHaving` vs `groupHaving` naming already caught and flagged in the WBS itself), one missing comparison operator mapping, and some minor test coverage gaps, but nothing that would block implementation.

## Issues Found

### Critical (must fix before implementation)

None.

### Important (should fix)

1. **Missing `attributeLessThanEquals` mapping in the translation mapping table (Section: "Translation Mapping -- Full Expression Paths")** -- The table lists `==`, `>`, `>=`, `<` but omits `<=` (`attributeLessThanEquals`). It is mentioned in the In Scope section and the factory methods table, and there are test cases for it, but the main translation mapping table is incomplete. This table is likely to be used as the primary reference during implementation, so the omission could cause confusion.

> ✅ **Addressed**: Added `$entity.attributes['x'] <= v` -> `attributeLessThanEquals("x", v)` row to the translation mapping table. Also updated the In Scope section to explicitly list `attributeLessThanEquals`.

2. **Missing `$entity.parent == null` mapping in the translation mapping table** -- The table only shows `$entity.parent != null` -> `hierarchyWithinRoot("self")`. The inverse case (`$entity.parent == null` -> `not(hierarchyWithinRootSelf())`) is covered in the test cases but not in the mapping table. Since the table serves as the definitive specification, this should be included.

> ✅ **Addressed**: Added `$entity.parent == null` -> `not(hierarchyWithinRootSelf())` row to the mapping table. Also added it to the In Scope section and Acceptance Criteria.

3. **`referenceHaving` merging may conflict with mixed boolean contexts** -- Task "Implement `referenceHaving` merging" says to merge inner constraints under a single `referenceHaving` when multiple comparisons reference the same reference name within the same boolean context. However, the WBS does not specify behavior when the same reference's attributes appear in different branches of an OR expression, or when one is negated. For example: `$reference.attributes['a'] == 1 || $reference.attributes['b'] == 2` -- should this produce `referenceHaving("ref", or(attrEq("a",1), attrEq("b",2)))` or `or(referenceHaving("ref", attrEq("a",1)), referenceHaving("ref", attrEq("b",2)))`? The distinction matters for downstream parameterization. The WBS should clarify the merging rules for non-AND contexts, or explicitly state that merging only happens within AND contexts.

> ✅ **Addressed**: Updated the merging task to explicitly state that merging is limited to AND contexts only. Added rationale explaining that merging inside OR would change FilterBy semantics and break PK-scoping parameterization. Added a new test case `does_not_merge_reference_having_in_or_context` to verify the non-merging behavior.

4. **`$entity.parent != null` is not a standard comparison pattern** -- The WBS describes comparison operators as "always binary with ObjectAccessOperator on one side and ConstantOperand on the other." However, `$entity.parent != null` has a `null` literal on the right side. The WBS should clarify how `null` is represented in the AST -- is it a `ConstantOperand` with a null value? The translator needs to distinguish between `$entity.parent != null` (hierarchy check) and `$entity.attributes['x'] != null` (null check -> `attributeIsNotNull`). The test cases cover the parent case but do not cover the `$entity.attributes['x'] != null` -> `attributeIsNotNull("x")` or `$entity.attributes['x'] == null` -> `attributeIsNull("x")` patterns.

> ✅ **Addressed**: Added detailed null-literal handling documentation to Key Design Observation #2, explaining that null is represented as a `ConstantOperand` with null value, and specifying the dispatch rules based on path type (parent path -> hierarchy check, attribute path -> `attributeIsNull`/`attributeIsNotNull`). Also updated Observation #5 (`NotEqualsOperator`) with null-specific behavior. Added a dedicated null-check translation task to the task list.

5. **No test cases for `attributeIsNull` / `attributeIsNotNull`** -- The expression `$entity.attributes['x'] == null` should translate to `filterBy(attributeIsNull("x"))` and `$entity.attributes['x'] != null` to `filterBy(attributeIsNotNull("x"))`. These null-check patterns are valid in expressions and have direct FilterBy equivalents (`QueryConstraints.attributeIsNull()` / `attributeIsNotNull()`), but neither the mapping table, the task list, nor the test cases mention them. This is a real gap -- a developer writing an expression like `$entity.attributes['status'] != null && ...` would get an incorrect translation (it would produce `not(attributeEquals("status", null))` instead of `attributeIsNotNull("status")`).

> ✅ **Addressed**: Added `attributeIsNull`/`attributeIsNotNull` to the translation mapping table, In Scope section, Acceptance Criteria, task list (new null-check translation task), and five new test cases covering entity attribute null checks, reference attribute null checks, and combined null-check with value comparison.

### Minor (nice to have)

1. **Parent analysis uses `entityGroupHaving`, WBS correctly flags this** -- The WBS (line 379) explicitly calls out that the parent analysis document uses `entityGroupHaving(...)` but the actual constraint is `groupHaving(...)` / `GroupHaving`. This is properly handled and flagged. This is noted here only as a confirmation that this inconsistency between parent and WBS is intentionally documented.

> ✅ **Addressed**: Additionally, all remaining `entityGroupHaving` references within the WBS itself (translation examples, dependency list, parameterization descriptions, acceptance criteria, ExpressionIndexTrigger JavaDoc) have been replaced with the correct `groupHaving`. The IMPORTANT note has been updated to clarify it now only references the parent analysis document's usage.

2. **`hierarchyWithinRoot("self")` vs `hierarchyWithinRootSelf()`** -- The parent analysis translation mapping table uses `hierarchyWithinRoot("self")` while the WBS task list and test cases correctly use `hierarchyWithinRootSelf()`. The translation mapping table in the WBS itself (line 66) still says `hierarchyWithinRoot("self")` which is inconsistent with the task list (line 444) which correctly says `hierarchyWithinRootSelf()`. The table should be updated for consistency.

> ✅ **Addressed**: Updated the translation mapping table to use `hierarchyWithinRootSelf()` instead of `hierarchyWithinRoot("self")`. Also updated the In Scope section, Acceptance Criteria, and dependency list to consistently use `hierarchyWithinRootSelf`.

3. **No explicit handling of `PositiveOperator`** -- The validation section (Group 2) lists `PositiveOperator` for rejection, but `PositiveOperator` is essentially a no-op in expressions (`+x == x`). While rejecting it is safe, it could also be transparently unwrapped. This is a minor design choice that does not affect correctness.

> ❌ **Declined**: Rejecting `PositiveOperator` is the safer choice. While it is semantically a no-op, transparently unwrapping it would add complexity to the translator for a construct that has no practical use in boolean filter expressions. Rejecting with a clear error message keeps the translator simple and surfaces any unintended use of the operator. The current behavior is intentional.

4. **`referenceHaving` merging test coverage is thin** -- Only two test cases cover `referenceHaving` merging (same reference attributes under AND, and same group entity attributes under AND). There is no test for merging referenced entity attributes under a single `referenceHaving`, or for verifying that merging does NOT happen across different reference names (if the expression language supported that -- though given the single-reference-schema context, this may not be possible).

> ✅ **Addressed**: Added two new test cases: `merges_referenced_entity_attributes_under_single_reference_having` (verifies merging for referenced entity attributes under AND) and `does_not_merge_reference_having_in_or_context` (verifies that merging does NOT happen in OR contexts). The "different reference names" case is not applicable since the translator operates within a single reference schema context.

5. **No explicit test case for `$reference.referencedPrimaryKey == v`** -- The In Scope section mentions reference attribute paths but the mapping table does not include `$reference.referencedPrimaryKey`. This path is less common and may be out of scope for FilterBy translation (since the executor handles PK scoping), but it should be explicitly noted as unsupported-in-translator if that is the intent.

> ✅ **Addressed**: Added explicit Out of Scope entry for `$reference.referencedPrimaryKey` translation, explaining that PK scoping is handled at trigger time by the executor. Added a rejection test case `rejects_reference_primary_key_comparison` to verify the translator rejects this path with a clear error message.

6. **Integration test for `AccessedDataFinder` collaboration is a single test case** -- Group 3 (AccessedDataFinder collaboration) has only one task and one test case. This is minimal but probably sufficient given that `AccessedDataFinder` is tested separately.

> ❌ **Declined**: As the review itself notes, a single integration test is sufficient since `AccessedDataFinder` has its own dedicated test suite. The collaboration test verifies the wiring between the two components, not the path analysis logic itself. Adding more tests here would duplicate `AccessedDataFinder`'s own coverage.

7. **Test class location uses `evita_test/evita_functional_tests` module** -- This is consistent with other test patterns in the codebase (e.g., `AccessedDataFinderTest` lives at `evita_test/evita_functional_tests/src/test/java/io/evitadb/api/query/expression/visitor/AccessedDataFinderTest.java`), so this is appropriate.

> ❌ **Declined**: This was noted as a confirmation, not an issue to address. The test class location is consistent with existing codebase patterns.

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | ✅ | Task descriptions are specific, code examples are correct, and the source code research section provides concrete class/method/package references verified against the codebase. The "IMPORTANT" note about `entityGroupHaving` vs `groupHaving` is a good example of proactive clarification. |
| Consistency | ⚠️ | Minor inconsistency between the translation mapping table (uses `hierarchyWithinRoot("self")`) and the task list (correctly uses `hierarchyWithinRootSelf()`). The `entityGroupHaving` vs `groupHaving` discrepancy with the parent document is properly flagged. The `<=` operator omission from the mapping table is a consistency gap. |
| Coherence | ✅ | The document flows logically: Objective -> Scope -> Dependencies -> Technical Context -> Key Interfaces -> Acceptance Criteria -> Implementation Notes -> Source Code Research -> Task List -> Test Cases. The task groups are ordered sensibly (core translator -> validation -> AccessedDataFinder collaboration). |
| Completeness | ⚠️ | Missing null-check patterns (`attributeIsNull` / `attributeIsNotNull`) in mapping, tasks, and tests. Missing `<=` from the mapping table. Missing clarification of `referenceHaving` merging rules in non-AND boolean contexts. The `$entity.parent` null-literal handling needs more explicit specification. |
| Test Coverage | ⚠️ | Strong coverage overall with 50+ test cases covering happy paths, rejections, edge cases, and error messages. Gaps: no tests for attribute null checks (`== null` / `!= null`), thin coverage of `referenceHaving` merging edge cases, no test for reversed operand order with comparison operators other than equality and less-than. Integration test section is appropriately scoped. |
