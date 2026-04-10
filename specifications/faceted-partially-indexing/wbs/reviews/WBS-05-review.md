# WBS-05 Review: Mutation Type Hierarchy

## Verdict: PASS WITH NOTES

## Summary

WBS-05 is a well-structured document that clearly defines a mutation type hierarchy separating engine-internal index mutations from external API-facing mutations. The design rationale is thorough, the code examples are accurate against the existing codebase, and the test coverage is comprehensive. There are a few cross-reference inconsistencies with other WBS documents and minor issues that should be corrected before implementation, but none that would block a developer from proceeding.

## Issues Found

### Critical (must fix before implementation)

None.

### Important (should fix)

- **`DependencyType` dependency incorrectly attributed to WBS-01.** The Dependencies section (line 42) states: "`DependencyType` enum must be defined before ... can reference it" and references WBS-01. The Key Interfaces table (line 289) also says "(TBD, see WBS-01)". However, `DependencyType` is actually defined in **WBS-03** (`ExpressionIndexTrigger` and `FacetExpressionTrigger` — Trigger Construction and Evaluation), which explicitly lists "`DependencyType` enum with `REFERENCED_ENTITY_ATTRIBUTE` and `GROUP_ENTITY_ATTRIBUTE` values" in its scope. Confusingly, the WBS-05 Test Class 6 section (line 659) correctly states "The enum itself is defined in WBS-03." This inconsistency within the same document could mislead a developer about task ordering. All references to WBS-01 regarding `DependencyType` should be corrected to WBS-03.

> ✅ **Addressed**: Changed all WBS-01 references for `DependencyType` to WBS-03 — in the Dependencies section, Key Interfaces table (now shows `io.evitadb.index.mutation` package with WBS-03 reference), Group 5 task description, and Source Code Research Results section.

- **Out of Scope cross-references are imprecise.** Line 30-31 states: "Executor implementations (`IndexMutationExecutor`, `ReevaluateFacetExpressionExecutor`) -- see WBS-07/WBS-08." This conflates two distinct things: `IndexMutationExecutor` (the strategy interface) is defined in **WBS-06** (Target-Side Dispatch Infrastructure), while `ReevaluateFacetExpressionExecutor` (the concrete executor) is in **WBS-07**. WBS-08 is about source-side detection (`popIndexImplicitMutations`), not executor implementations. Similarly, line 33 says "Dispatch loop changes in `MutationCollector`/`LocalMutationExecutorCollector` -- see WBS-09", but the dispatch loop integration is actually in **WBS-10** (`LocalMutationExecutorCollector` Integration -- Two-Loop Dispatch). WBS-09 is about local trigger integration in `ReferenceIndexMutator`.

> ✅ **Addressed**: Rewrote the Out of Scope section with precise WBS references — WBS-06 for executor strategy interface and dispatch registry, WBS-07 for concrete executor, WBS-08 for source-side detection, WBS-04 for trigger registry (was incorrectly WBS-06), WBS-10 for dispatch loop changes (was incorrectly WBS-09). Also fixed the Depended On By section with the same corrections, and fixed a WBS-09 reference to WBS-10 in the Source Code Research Results section.

- **`ReevaluateFacetExpressionMutation` visibility modifier.** The code example at line 231 declares `record ReevaluateFacetExpressionMutation(...)` without the `public` modifier. Acceptance criterion 5 (line 302) states it should be "a public record." The Group 5 task (line 451) also says "public record." The code example should include `public` to match the acceptance criteria and task description.

> ✅ **Addressed**: Added `public` modifier to the `ReevaluateFacetExpressionMutation` code example. Also updated acceptance criterion 5 to explicitly say "public record" for full consistency.

### Minor (nice to have)

- **`IndexImplicitMutations` placement ambiguity.** Group 6 task (line 455) states the record can be "either as a nested record inside `EntityIndexLocalMutationExecutor` ... or as a standalone type in `io.evitadb.index.mutation`." While flexibility is reasonable, this leaves the decision to the implementer without a recommended default. WBS-08 (Source-Side Detection, line 19) explicitly states "`IndexImplicitMutations` record that carries `EntityIndexMutation[]`" is in its scope, which could create ownership confusion between WBS-05 and WBS-08. A clear recommendation (e.g., "prefer standalone type in `io.evitadb.index.mutation`") would reduce ambiguity.

> ✅ **Addressed**: Reworded Group 6 task to recommend standalone type in `io.evitadb.index.mutation` as the preferred option, with nested record as the alternative. Also updated the Key Interfaces table to reflect the preferred standalone placement.

- **Test Class 7 location differs from others.** Test Classes 1-6 are located under `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/mutation/`, but Test Class 7 (`MutationBackwardCompatibilityTest`) at line 681 is in `.../io/evitadb/api/requestResponse/mutation/`. While the different location is justified (it tests `evita_api` types), this is not explicitly called out in the test class description, which could cause confusion during implementation.

> ✅ **Addressed**: Added a note to the Test Class 7 description explaining that it is placed in `io.evitadb.api.requestResponse.mutation` (unlike Test Classes 1-6) because it tests `evita_api` types.

- **Test Class 7, test case `all_files_importing_mutation_should_compile_after_extends_mutation_contract` (line 702).** This is described as a test case, but it is actually a build verification step (`mvn compile`). It would be more accurate to describe this as a build-time verification rather than a unit test, or to note that it does not belong in a JUnit test class. A developer might attempt to write a JUnit test that invokes Maven, which is not a standard pattern.

> ✅ **Addressed**: Changed the category label to "build verification (manual step, not a JUnit test)" and added a note explicitly stating this is a build-time verification step, not a JUnit test case, and that developers should not attempt to write a JUnit test invoking Maven.

- **`EntityIndexMutation` array-based `equals` behavior.** Test Class 3, category "record equality" (line 607) documents that Java records use `Object.equals()` for array fields, meaning two envelopes with identical contents but different array references will not be equal. While this is correctly documented as expected behavior, it is worth noting that this could be surprising if `EntityIndexMutation` instances are ever used in collections or maps. If this is intentionally by-design (and it appears to be), a brief note in the `EntityIndexMutation` JavaDoc would make this explicit.

> ✅ **Addressed**: Added a `<p><b>Note on equality:</b>` paragraph to the `EntityIndexMutation` JavaDoc in the code example, documenting that array-based identity equality is intentional and that envelope equality is not needed for correctness.

- **`ConsistencyCheckingLocalMutationExecutor` package reference in Key Interfaces table.** Line 287 lists the package for `ImplicitMutations` as "(existing, in `ConsistencyCheckingLocalMutationExecutor`)" rather than the actual package `io.evitadb.index.mutation`. While technically correct (it is a nested record), it is inconsistent with how other entries in the table specify packages.

> ✅ **Addressed**: Updated the Key Interfaces table to show `io.evitadb.index.mutation` as the package for both `ImplicitMutations` (with parenthetical noting it is nested in `ConsistencyCheckingLocalMutationExecutor`) and `IndexImplicitMutations` (with preferred standalone placement noted).

## Checklist

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Clearness | ✅ | Task descriptions are specific and unambiguous. Code examples include full JavaDoc. Technical terms are used consistently. The "Design Principle" sections provide excellent rationale for architectural decisions. |
| Consistency | ⚠️ | The `DependencyType` dependency is attributed to WBS-01 in the Dependencies and Key Interfaces sections but correctly to WBS-03 in the Test Cases section. Out of Scope cross-references to WBS-07/WBS-08/WBS-09 are imprecise. The `public` modifier is missing from the `ReevaluateFacetExpressionMutation` code example but required by acceptance criteria. |
| Coherence | ✅ | The document flows logically from objectives through design rationale, technical context, key interfaces, acceptance criteria, implementation notes, research results, task breakdown, and test cases. Task groups are ordered by dependency (MutationContract first, then Mutation modification, then IndexMutation, etc.). |
| Completeness | ✅ | All types to be created or modified are listed with exact packages and modules. The source code research section (lines 342-428) provides verified paths and declarations for all existing types. Edge cases (empty arrays, stub methods, module boundary considerations) are covered. The "Out of Scope" section clearly delineates what belongs to other WBS items. |
| Test Coverage | ✅ | Test coverage is thorough: 8 test classes covering type assignability, record field accessors, equality/hashCode, interface properties (sealed/unsealed, no declared methods), backward compatibility of existing types, `DependencyType` completeness, stub behavior, and structural independence between executor output records. Both positive and negative assignability tests are included. The `should_not_be_serializable` test (line 565) is a good catch for enforcing the "never serialized" invariant. |
