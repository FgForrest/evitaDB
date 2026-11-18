---
paths:
  - "**/io/evitadb/core/query/algebra/**/*.java"
  - "**/io/evitadb/index/hierarchy/suppliers/**/*.java"
  - "**/io/evitadb/index/hierarchy/predicate/**/*.java"
  - "**/io/evitadb/core/query/extraResult/translator/histogram/producer/**/*.java"
  - "**/io/evitadb/core/query/filter/translator/**/alternative/**/*.java"
---

# Formula `CLASS_ID` constants

Every concrete formula, bitmap supplier, hierarchy predicate, histogram computer and entity filtering alternative
declares its own `long CLASS_ID`, returned from `getClassId()` and from its identity record's `classId()`.
`FormulaKey` uses this value as the concrete holder discriminator alongside exact detached inputs and ordered
child keys. Each holder declares both `identity()` and `getClassId()` itself.

The contract (`documentation/developer/formula/formula_framework.md`) has three invariants:

- **unique** across every class that declares one,
- **never changes** once the class exists,
- **never inherited** — each concrete class declares its own.

## Minting a new one

```shell
tools/generate-class-id.sh io.evitadb.index.hierarchy.suppliers.HierarchyRootsDownBitmapSupplier
```

The script derives the value the way a `serialVersionUID` is derived — SHA-1, first eight bytes folded
little-endian into a signed long — but seeds it on the **fully qualified class name alone**.

**Do not seed it on the class signature, and never use the IDE's `serialVersionUID` action on a class
that already has a `CLASS_ID`.** A signature-derived value is a different number every time a field or
method is added, which is exactly what invariant #2 forbids; the IDE's generator is built to be
re-run after the class changes, and that habit is the trap. Seeding on the name makes the value
reproducible by anyone, stable across every edit to the class body, and distinct by construction.

Copy-pasting a sibling class is how the one historical clash happened — `HierarchyRootsDownBitmapSupplier`
carried `HierarchyRootsBitmapSupplier`'s id from 2023 until 2026 — so when a new class starts life as a
copy, the `CLASS_ID` is the first line to replace.

## Changing an existing one

Invariant #2 makes this a last resort. A formula key never leaves the process: it is never persisted or sent to
another process. Its 64-bit hash is reported in the query plan telemetry, but only as a process-local diagnostic
value that means nothing to another process or run. Changing a discriminator affects reuse within the running
process; it needs no storage migration.
Fixing a duplicate is worth it; tidying a value is not.

## The check

`FormulaClassIdUniquenessTest` scans the sources for every declaration and fails on any repeated value,
naming both files. It reads sources rather than loading classes — the invariant is a source convention,
and a source hit names the file to fix. It also asserts a floor on the number of declarations found, so
a scan that resolved the wrong root fails instead of passing on an empty result.
