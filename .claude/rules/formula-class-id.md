---
paths:
  - "**/io/evitadb/core/query/algebra/**/*.java"
  - "**/io/evitadb/index/hierarchy/suppliers/**/*.java"
---

# Formula `CLASS_ID` constants

Every concrete formula and bitmap supplier returns a `long` constant from `getClassId()`. It is
`hashArray[0]` of the structural hash built in `AbstractFormula#initFields`, which keys the in-memory
result cache (`CacheEden`, `CacheAnteroom`). Two classes sharing a value become indistinguishable to
that cache whenever their remaining hash inputs agree, and the symptom is one formula answering with
another's bitmap — a wrong result, arriving through a cache hit, that no read-time check will notice.

The contract (`AbstractFormula#getClassId`, and `documentation/developer/formula/formula_framework.md`)
is three invariants and nothing else:

- **unique** across every class that declares one,
- **never changes** once the class exists,
- **never inherited** — each leaf class declares its own.

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

Invariant #2 makes this a last resort, but the blast radius is worth knowing precisely: the structural
hash never reaches `evita_store`, so there is nothing persisted to invalidate and no migration to write.
The cost is confined to the running process's warm cache. That makes a change free for a class that has
not yet been released, and a needless cache flush for one that has. Fixing a duplicate is worth it;
tidying a value is not.

## The check

`FormulaClassIdUniquenessTest` scans the sources for every declaration and fails on any repeated value,
naming both files. It reads sources rather than loading classes — the invariant is a source convention,
and a source hit names the file to fix. It also asserts a floor on the number of declarations found, so
a scan that resolved the wrong root fails instead of passing on an empty result.
