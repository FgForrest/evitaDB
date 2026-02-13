---
name: code-quality-pipeline
description: Use when dropping a class name or folder path to perform a full quality pass - tests, simplification, and bug hunting - using the test-architect, code-simplifier, and bug-hunter-tdd agents in succession, both for analysis and execution
---

# Code Quality Pipeline

## Overview

A pipeline that runs three specialized agents (test-architect, code-simplifier, bug-hunter-tdd) each used TWICE: first for **analysis/planning** (all three in parallel), then for **execution** (sequentially). Drop a class name or folder and get comprehensive quality improvements.

## When to Use

- You have a class name or folder path and want a full quality pass
- You want tests reorganized/created, code cleaned up, and bugs found+fixed
- Invoked as `/code-quality-pipeline <target>` where target is a class name or folder path

## Pipeline

### Phase 1: PLANNING (all three agents in parallel)

Launch all three agents simultaneously in a single message with three Task tool calls. Each agent does **ANALYSIS ONLY** (no code changes):

**test-architect** (planning):
- Read all source files in the target and find existing tests
- Identify test gaps, missing coverage, convention violations
- Produce detailed test plan: files to create/modify, test methods, `@Nested` groups

**code-simplifier** (planning):
- Read all source files, check JavaDoc, naming, annotations, style
- Identify missing `@Nonnull`/`@Nullable`, HTML in JavaDoc, grammar issues
- Produce file-by-file list of specific changes needed

**bug-hunter-tdd** (planning):
- Read all source files looking for bugs, edge cases, latent issues
- Trace through logic with concrete examples, search for usages
- Produce finding report: severity, file, line, reproduction scenario, recommended fix

#### Phase 1 Checklist:

- [ ] `test-architect` launched with ANALYSIS ONLY, produces test plan
- [ ] `code-simplifier` launched with ANALYSIS ONLY, produces simplification plan
- [ ] `bug-hunter-tdd` launched with ANALYSIS ONLY, produces bug report

### Phase 2: EXECUTION (three agents in sequence)

Run agents one at a time, each building on the previous:

**Step 1: test-architect** (execution):
- Create new test files, reorganize existing ones per the plan
- For known bugs found in planning, write tests asserting current (broken) behavior with `// Known limitation` comments
- Build and run tests to verify all pass

**Step 2: code-simplifier** (execution):
- Apply all cosmetic/documentation improvements per the plan
- NO behavioral changes
- Build and run tests to verify nothing broke

**Step 3: bug-hunter-tdd** (execution):
- Update tests from Step 1 that documented broken behavior to assert correct behavior
- Apply minimal fixes following TDD: update test first, then fix code
- Build and run all tests to verify fixes and no regressions

### Phase 3: VERIFICATION

After all execution steps complete:
- Run the full test suite for the affected package
- Run broader related tests to check for regressions
- Summarize all changes made across all three phases

## Agent Launch Template

For each agent, use the Task tool:

```
Task(
  subagent_type: "<agent-type>",
  description: "<3-5 word summary>",
  prompt: "<detailed instructions with target path, project context, and ANALYSIS ONLY vs EXECUTION>"
)
```

Always pass full context to each agent:
- Project root path
- Target source file paths (resolved from the class name or folder)
- Code style rules from CLAUDE.md
- For execution agents: include the full planning results from Phase 1

## Resolving the Target

When the user provides a target:
- **Folder path**: Use directly (e.g., `evita_common/src/main/java/io/evitadb/dataType/iterator`)
- **Class name**: Search with Glob/Grep to find the source file, then identify its package folder
- **Module:class**: Resolve within the specified module

Always resolve test location as: `evita_test/evita_functional_tests/src/test/java/<matching-package-path>/`

## Common Mistakes

- Launching execution agents without first doing the planning phase - always plan first with all three agents in parallel
- Not passing the planning agent's findings to the execution agent - include full context from planning
- Running execution agents in parallel - they MUST be sequential (test-architect then code-simplifier then bug-hunter)
- Forgetting to build between execution steps - each step needs `mvn install -pl <module> -DskipTests` then test run
