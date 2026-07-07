# RoaringBitmap Vendoring — Part 1 (Isolated Module) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stand up a self-contained `evita_roaring_bitmap` Maven module that vendors the minimal RoaringBitmap class closure (32-bit core + `longlong` + `art` + the `CopyOnWriteRoaringBitmapV2`/`FrozenRoaringBitmap` prototypes) under package `io.evitadb.roaringbitmap` (with the two root classes rebranded `RoaringBitmap`→`PersistentRoaringBitmap` and `Roaring64Bitmap`→`PersistentLongRoaringBitmap`), with ported tests and an upstream re-sync skill — **without touching any existing evitaDB module** so it can run in parallel with the #760 branch.

**Architecture:** Full vendor (not a thin subclass): the prototype `CopyOnWriteRoaringBitmapV2 extends RoaringBitmap` and reaches package-private internals (`highLowContainer` ×89, `RoaringArray` methods ×100+), and both upstream RoaringBitmap and `evita_engine` are JPMS modules — so a split-package thin port is illegal on the modulepath. We instead copy the needed classes, rename the package to `io.evitadb.roaringbitmap`, and own them outright. Part 1 builds the module **standalone** (its pom declares the evitaDB root as `<parent>` but is **not** registered in the root `<modules>` reactor), so the only files created are new files under a new directory — zero conflict surface with #760. Integration (call-site migration, `module-info`, pom dependency swap) is deferred to **Part 2**, executed in one coordinated window after #760 merges.

**Tech Stack:** Java 17, Maven (toolchains), JUnit 5 (upstream test framework), RoaringBitmap v1.6.12 source (Apache-2.0), JMH (benchmark, optional).

## REVISION 2026-06-27 — single-class reshape (supersedes conflicting details below)

Johnny refined the target after the initial draft. Where the phases below describe keeping `CopyOnWriteRoaringBitmapV2`/`FrozenRoaringBitmap` as separate classes or vendoring `buffer`, the following overrides them:

1. **No `buffer` package** — do not vendor `MutableRoaringBitmap`, `ImmutableRoaringBitmap`, or any `Mappeable*`. Strip the base class's only buffer coupling (13 lines: `RoaringBitmap(ImmutableRoaringBitmap)` ctor, `toMutableRoaringBitmap()`, 3 imports).
2. **One class per data type.** `PersistentRoaringBitmap` (32-bit) and `PersistentLongRoaringBitmap` (64-bit) are the *only* bitmap classes. No Mutable/Immutable split, no prototype subclasses.
3. **Fold V2 into the single class.** `CopyOnWriteRoaringBitmapV2` already `@Override`s ~25 `RoaringBitmap` methods (add/remove/flip/and/or/xor/andNot/orNot/lazyor/naivelazyor/repairAfterLazy/deserialize/clone/clear/trim/append) and adds static `or/and/andNot/xor` factories + `fromBitmap`. P1 renames base `RoaringBitmap` → `PersistentRoaringBitmap`, replaces those method bodies with V2's, ports V2's added fields/helpers/factories, and deletes V2 as a separate class.
4. **Drop** `FrozenRoaringBitmap` (issue calls it reference-only) and `FastRankRoaringBitmap`. So the substring-trap "keep" list in Task 1.2 shrinks to `RoaringBitmapPrivate`, `RoaringBitmapSupplier`, `RoaringBitmapWriter`, `Roaring64NavigableMap`.
5. **Test oracle:** `TestCopyOnWriteRoaringBitmapV2` is retargeted onto `PersistentRoaringBitmap` and is the proof the fold preserved semantics. `TestFrozenRoaringBitmap` is dropped with its class.

These phases are executed **interactively, one at a time, described before each step** (not handed to a fresh subagent), per Johnny's request.

---

**Source of truth for the vendor:** `documentation/RoaringBitmap/` — a separate git repo, Johnny's fork `github.com/novoj/RoaringBitmap`, HEAD `f27cd538`, which is pristine upstream **v1.6.12** (merge-base `952f8ce7`) **plus** the two prototype files and their tests. The fork did not modify the core engine.

**Licensing:** Vendored files are **Apache-2.0** and MUST keep their original Apache headers + retain `LICENSE`/`AUTHORS` + a `NOTICE` stating evitaDB modified them, with the synced upstream commit hash. Do **NOT** apply evitaDB's BSL-1.1 header to vendored third-party files.

**Out of scope for Part 1 (do NOT do these — they belong to Part 2):**
- Editing the root `pom.xml` `<modules>` list.
- Editing `evita_engine/module-info.java`, `evita_engine/pom.xml`, or the root `roaringbitmap.version` property.
- Migrating any `org.roaringbitmap` call site in `evita_*` modules.

---

## Phase P0 — Scaffold + Compute Closure

### Task 0.1: Create the standalone module skeleton

**Files:**
- Create: `evita_roaring_bitmap/pom.xml`
- Create: `evita_roaring_bitmap/src/main/java/.gitkeep`
- Create: `evita_roaring_bitmap/src/test/java/.gitkeep`

**Step 1: Write `evita_roaring_bitmap/pom.xml`**

The pom declares the evitaDB root as parent (for version/dependency management) but the root reactor will **not** list it yet — Maven builds a child directly regardless. Keep it minimal; test/closure deps get added in later tasks as compile reveals them.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<artifactId>evita_roaring_bitmap</artifactId>
	<packaging>jar</packaging>
	<name>evitaDB - Vendored RoaringBitmap (persistent/structure-sharing)</name>
	<description>Vendored subset of RoaringBitmap (Apache-2.0) plus evitaDB's persistent CopyOnWriteRoaringBitmapV2 / FrozenRoaringBitmap. See NOTICE for attribution and synced upstream commit.</description>
	<parent>
		<groupId>io.evitadb</groupId>
		<artifactId>evita_root</artifactId>
		<version>2026.2-SNAPSHOT</version>
	</parent>
	<dependencies>
		<!-- test dependencies added in Task 2.2 -->
	</dependencies>
</project>
```

**Step 2: Verify the module resolves its parent and builds empty**

Run: `cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap && mvn -q validate`
Expected: BUILD SUCCESS (parent `evita_root:2026.2-SNAPSHOT` resolves from the local reactor/`~/.m2`). If parent is unresolved, run a one-time `mvn -q -N install` at repo root first (installs the parent pom only; does not build other modules).

**Step 3: Commit**

```bash
git add evita_roaring_bitmap/pom.xml evita_roaring_bitmap/src
git commit -m "feat: scaffold standalone evita_roaring_bitmap module

Empty module wired to the evita_root parent but intentionally NOT added
to the root reactor <modules> yet, so it builds in isolation and does
not collide with the #760 branch.

Ref: #1252"
```

### Task 0.2: Compute the exact vendor closure with jdeps

**Files:**
- Create: `evita_roaring_bitmap/CLOSURE.md` (working note — the authoritative class list; may be deleted before final PR)

**Step 1: Build the upstream source jar (in the fork repo, untouched)**

The fork is a Gradle project. Produce compiled classes to feed `jdeps`:

Run: `cd /www/oss/evita/1252-optimizing-stm-deltas/documentation/RoaringBitmap && ./gradlew :roaringbitmap:jar -q`
Expected: a jar under `roaringbitmap/build/libs/`. If Gradle/toolchain is unavailable, fall back to compiling `roaringbitmap/src/main/java` with `javac -d /tmp/rbm-classes $(find roaringbitmap/src/main/java -name '*.java')`.

**Step 2: Seed the closure from the two entrypoints + evita's API surface**

The closure must cover everything reachable from the classes evitaDB actually touches. Seeds:
- `org.roaringbitmap.CopyOnWriteRoaringBitmapV2`, `org.roaringbitmap.FrozenRoaringBitmap`
- evita's public surface: `RoaringBitmap`, `RoaringBitmapWriter`, `PeekableIntIterator`, `BatchIterator`, `RoaringBatchIterator`, `IntIterator`, `ImmutableBitmapDataProvider`, `org.roaringbitmap.longlong.Roaring64Bitmap`, `org.roaringbitmap.longlong.ImmutableLongBitmapDataProvider`

Run jdeps to list per-class dependencies (filtered to the `org.roaringbitmap` namespace), then transitively close:
```
jdeps -verbose:class -filter:none \
  -e 'org\.roaringbitmap\..*' \
  roaringbitmap/build/libs/RoaringBitmap-*.jar > /tmp/rbm-deps.txt
```
Expected: a list of `class -> dependency` edges. Build the transitive closure starting from the seeds above.

**Step 3: Record the closure**

Write `CLOSURE.md` listing every class to vendor, grouped by subpackage (`org.roaringbitmap`, `org.roaringbitmap.longlong`, `org.roaringbitmap.art`, and any `buffer` classes pulled in transitively — several core classes import `buffer.*`). Note explicitly which subpackages are **excluded** (`insights`, and any `buffer` class not transitively required). This file is the checklist for Task 1.1.

**Step 4: Commit**

```bash
git add evita_roaring_bitmap/CLOSURE.md
git commit -m "docs: record RoaringBitmap vendor closure (jdeps)

Ref: #1252"
```

---

## Phase P1 — Vendor + Rename + Attribution

### Task 1.1: Copy the closure's main sources into the module

**Files:**
- Create: `evita_roaring_bitmap/src/main/java/org/roaringbitmap/**` (every class listed in `CLOSURE.md`, copied verbatim **before** rename)
- Copy attribution roots: `evita_roaring_bitmap/LICENSE` (← `documentation/RoaringBitmap/LICENSE`), `evita_roaring_bitmap/AUTHORS` (← `documentation/RoaringBitmap/AUTHORS`)

**Step 1: Copy each closure class verbatim** preserving directory structure under `src/main/java/`. Include the two prototypes (`CopyOnWriteRoaringBitmapV2.java`, `FrozenRoaringBitmap.java`).

**Step 2: Copy `LICENSE` and `AUTHORS`** into the module root.

**Step 3: Sanity check — verify counts match the closure**

Run: `find evita_roaring_bitmap/src/main/java -name '*.java' | wc -l`
Expected: equals the class count recorded in `CLOSURE.md`.

**Step 4: Commit** (verbatim copy, pre-rename — keeps the rename diff clean)

```bash
git add evita_roaring_bitmap/src/main/java evita_roaring_bitmap/LICENSE evita_roaring_bitmap/AUTHORS
git commit -m "chore: vendor RoaringBitmap v1.6.12 closure verbatim (pre-rename)

Apache-2.0 sources copied unchanged from fork commit f27cd538
(upstream merge-base 952f8ce7 = v1.6.12).

Ref: #1252"
```

### Task 1.2: Rename package + the two root classes

**Files:** all of `evita_roaring_bitmap/src/main/java/**`

This task does **two** renames:
1. **Package** (all classes): `org.roaringbitmap` → `io.evitadb.roaringbitmap`.
2. **Root classes only** (per Johnny): `RoaringBitmap` → `PersistentRoaringBitmap`, and `org.roaringbitmap.longlong.Roaring64Bitmap` → `PersistentLongRoaringBitmap`.

> **CRITICAL — substring traps.** `RoaringBitmap` is a substring of sibling classes that MUST keep their names: `CopyOnWriteRoaringBitmapV2`, `FastRankRoaringBitmap`, `FrozenRoaringBitmap`, `RoaringBitmapPrivate`, `RoaringBitmapSupplier`, `RoaringBitmapWriter`. Likewise `Roaring64Bitmap` sits beside `Roaring64NavigableMap`. Use **word-boundary** matching (`\bRoaringBitmap\b`, `\bRoaring64Bitmap\b`) so only the standalone identifiers are rewritten. A naïve `s/RoaringBitmap/.../g` would corrupt all six siblings — do NOT do that.

> **Note on serialization safety:** RoaringBitmap's persistent format is a custom binary layout (magic `SERIAL_COOKIE`, not Java class-name serialization), and evitaDB persists via Kryo (registers by class, custom format). Renaming the package and the two class identifiers therefore does **not** change any on-disk binary format. The only Java-`Serializable` surface (`FrozenRoaringBitmap`, unaffected name) is not used for persistence. Safe to rename.

**Step 1: Move the source tree** from `.../java/org/roaringbitmap/` to `.../java/io/evitadb/roaringbitmap/`:

```bash
cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap/src/main/java
mkdir -p io/evitadb && git mv org/roaringbitmap io/evitadb/roaringbitmap && rmdir org 2>/dev/null || true
```

**Step 2: Rewrite the package coordinate in every file** (`org.roaringbitmap` → `io.evitadb.roaringbitmap`) in `package`, `import`, and any fully-qualified references:

```bash
cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap/src/main/java
grep -rl 'org\.roaringbitmap' . | xargs sed -i 's/org\.roaringbitmap/io.evitadb.roaringbitmap/g'
```

**Step 3: Verify no stray `org.roaringbitmap` references remain**

Run: `grep -rn 'org\.roaringbitmap' evita_roaring_bitmap/src/main/java | grep -v 'Apache\|copyright\|http' || echo CLEAN`
Expected: `CLEAN` (matches only in license/credit comment text are acceptable and handled in Task 1.3).

**Step 4: Rename the two root class files**

```bash
cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap
git mv RoaringBitmap.java PersistentRoaringBitmap.java
git mv longlong/Roaring64Bitmap.java longlong/PersistentLongRoaringBitmap.java
```

**Step 5: Rewrite the two root identifiers everywhere, word-boundary matched**

```bash
cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap/src/main/java
# Roaring64Bitmap FIRST (it is the more specific token), then RoaringBitmap
grep -rl '\bRoaring64Bitmap\b' . | xargs sed -i 's/\bRoaring64Bitmap\b/PersistentLongRoaringBitmap/g'
grep -rl '\bRoaringBitmap\b'   . | xargs sed -i 's/\bRoaringBitmap\b/PersistentRoaringBitmap/g'
```

**Step 6: Verify the substring-trap siblings survived intact**

Run:
```bash
cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap/src/main/java
for t in CopyOnWriteRoaringBitmapV2 FastRankRoaringBitmap FrozenRoaringBitmap RoaringBitmapPrivate RoaringBitmapSupplier RoaringBitmapWriter Roaring64NavigableMap; do
  grep -rq "class $t\|$t<\|$t(\|$t\." . && echo "OK $t" || echo "MISSING $t"
done
```
Expected: `OK` for every sibling (none mangled into `Persistent...`). Also confirm the renamed files compile-reference correctly: `grep -rn 'extends PersistentRoaringBitmap' .` should show `CopyOnWriteRoaringBitmapV2` and `FastRankRoaringBitmap` now extending the renamed root.

**Step 7: Review string literals** (cosmetic): `grep -rn '"PersistentRoaringBitmap"\|"PersistentLongRoaringBitmap"' .` — the word-boundary sed also rewrote any in-quote `"RoaringBitmap"` literals (exception/JMX/toString text). This is harmless and consistent; just eyeball that nothing serialization-critical depends on the literal string (it does not — format keys are numeric cookies).

**Step 8: Commit**

```bash
git add -A evita_roaring_bitmap/src/main/java
git commit -m "refactor: rename package to io.evitadb.roaringbitmap; root classes to Persistent*

Package org.roaringbitmap -> io.evitadb.roaringbitmap (all classes).
Root classes RoaringBitmap -> PersistentRoaringBitmap and
Roaring64Bitmap -> PersistentLongRoaringBitmap (word-boundary matched;
siblings like RoaringBitmapWriter/MutableRoaringBitmap untouched).

Ref: #1252"
```

### Task 1.3: Add NOTICE, attribution headers, and synced-commit stamp

**Files:**
- Create: `evita_roaring_bitmap/NOTICE`
- Create: `evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/package-info.java`
- Modify: header comment of `CopyOnWriteRoaringBitmapV2.java` and `FrozenRoaringBitmap.java` (note these are evitaDB-authored on top of Apache code)

**Step 1: Write `evita_roaring_bitmap/NOTICE`**

```
evitaDB - Vendored RoaringBitmap
================================

This module contains source code derived from the RoaringBitmap project
(https://github.com/RoaringBitmap/RoaringBitmap), licensed under the
Apache License, Version 2.0. See the LICENSE and AUTHORS files in this
module for the original copyright and contributor list.

Synced from fork github.com/novoj/RoaringBitmap, commit f27cd538,
which corresponds to upstream RoaringBitmap v1.6.12 (merge-base 952f8ce7).

Modifications by FG Forrest, a.s. for evitaDB:
  - Package renamed from org.roaringbitmap to io.evitadb.roaringbitmap.
  - Root classes renamed: RoaringBitmap -> PersistentRoaringBitmap and
    Roaring64Bitmap -> PersistentLongRoaringBitmap (other classes keep
    their original names).
  - Added CopyOnWriteRoaringBitmapV2 and FrozenRoaringBitmap: persistent
    (immutable, structure-sharing) variants that avoid copying unaffected
    containers during AND/OR/XOR/NOT operations.
  - Vendored only the subset of classes required by evitaDB (see CLOSURE).

This NOTICE file is provided in compliance with section 4(d) of the
Apache License, Version 2.0.
```

**Step 2: Verify each vendored Apache file still carries its original Apache header.** The verbatim copy in Task 1.1 preserved them; confirm none were stripped:

Run: `grep -rL 'Licensed under the Apache License' evita_roaring_bitmap/src/main/java | grep -v package-info || echo ALL_HEADED`
Expected: `ALL_HEADED` (every vendored file retains the Apache header). If a file is missing one, restore it from the fork source.

**Step 3: Write `package-info.java`** documenting the vendor origin, sync commit, and that the package is third-party Apache-2.0 code under evitaDB stewardship. Add a short note to the two prototype class JavaDocs crediting the approach to RoaringBitmap issue #826.

**Step 4: Commit**

```bash
git add evita_roaring_bitmap/NOTICE evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/package-info.java evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/CopyOnWriteRoaringBitmapV2.java evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/FrozenRoaringBitmap.java
git commit -m "docs: add NOTICE + attribution + synced-commit stamp for vendored RoaringBitmap

Ref: #1252"
```

### Task 1.4: Add module-info and compile the mains standalone

**Files:**
- Create: `evita_roaring_bitmap/src/main/java/module-info.java`
- Possibly Modify: `evita_roaring_bitmap/pom.xml` (add transitive runtime deps if the closure needs any — upstream core is dependency-free, so expect none)

**Step 1: Write `module-info.java`** for the vendored module, exporting the packages evitaDB will consume:

```java
module io.evitadb.roaring_bitmap {
	exports io.evitadb.roaringbitmap;
	exports io.evitadb.roaringbitmap.longlong;
	exports io.evitadb.roaringbitmap.art;
	// add exports io.evitadb.roaringbitmap.buffer; only if buffer classes were vendored
}
```

**Step 2: Compile**

Run: `cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap && mvn -q compile`
Expected: BUILD SUCCESS. If compile fails on a missing class, the closure (Task 0.2) under-counted — add the missing class from the fork, re-run Task 1.2's rename on just that file, and recompile. Iterate until green. Record any closure additions in `CLOSURE.md`.

**Step 3: Commit**

```bash
git add evita_roaring_bitmap/src/main/java/module-info.java evita_roaring_bitmap/pom.xml evita_roaring_bitmap/CLOSURE.md
git commit -m "feat: compile vendored RoaringBitmap module standalone

Ref: #1252"
```

---

## Phase P2 — Port Tests + Gap-Fill

### Task 2.1: Copy and rename the relevant test closure

**Files:**
- Create: `evita_roaring_bitmap/src/test/java/io/evitadb/roaringbitmap/**`

**Step 1: Identify the test closure.** Must include the two prototype tests (`TestCopyOnWriteRoaringBitmapV2.java` 3495 LOC, `TestFrozenRoaringBitmap.java` 474 LOC) plus the upstream tests covering every vendored main class (the task says *no test left out* for what we vendor). Use the same closure discipline as P0: a vendored class with an upstream test → port that test. List them in `CLOSURE.md` under a "Tests" heading.

**Step 2: Copy verbatim**, preserving structure, into `src/test/java/org/roaringbitmap/` first.

**Step 3: Rename** — apply the **same two renames as Task 1.2** to `src/test/java`: package `org.roaringbitmap` → `io.evitadb.roaringbitmap`, then the word-boundary root-class renames (`\bRoaring64Bitmap\b` → `PersistentLongRoaringBitmap` first, then `\bRoaringBitmap\b` → `PersistentRoaringBitmap`). Rename any test files named after the roots (e.g. `TestRoaringBitmap.java` → `TestPersistentRoaringBitmap.java`, `TestRoaring64Bitmap.java` → `TestPersistentLongRoaringBitmap.java`). Re-run the Task 1.2 Step 6 substring-trap check against `src/test/java`. Verify CLEAN.

**Step 4: Commit**

```bash
git add evita_roaring_bitmap/src/test
git commit -m "test: port + rename vendored RoaringBitmap test closure

Ref: #1252"
```

### Task 2.2: Wire test dependencies and run the suite green

**Files:**
- Modify: `evita_roaring_bitmap/pom.xml`

**Step 1: Add the test deps the ported tests require** (surface them by compiling tests). Upstream uses JUnit 5; expect `junit-jupiter`, possibly `assertj-core` and `org.apache.commons:commons-lang3`/Guava for test data. Add each as `<scope>test</scope>`, preferring versions already managed by the root pom's `<dependencyManagement>`; pin explicitly only where unmanaged.

**Step 2: Compile tests**

Run: `cd /www/oss/evita/1252-optimizing-stm-deltas/evita_roaring_bitmap && mvn -q test-compile`
Expected: BUILD SUCCESS. Add deps until it compiles.

**Step 3: Run the suite**

Run: `mvn -q test`
Expected: all ported tests PASS. Investigate any failure as a real port defect (not a flake) before proceeding — a green suite is the correctness oracle proving the rename + closure are faithful.

**Step 4: Commit**

```bash
git add evita_roaring_bitmap/pom.xml
git commit -m "test: wire test deps; full vendored suite green

Ref: #1252"
```

### Task 2.3: Fill test gaps with bug-hunter-tdd

**Step 1:** Run the `dev-tools:bug-hunter-tdd` agent against the two prototype classes (`CopyOnWriteRoaringBitmapV2`, `FrozenRoaringBitmap`) — they were "agent-written POCs" per the issue and are the highest-risk surface. Target: edge cases in structure-sharing during AND/OR/XOR/NOT (overlap vs disjoint containers, run/array/bitmap container-type transitions, empty/full bitmaps, serialization round-trips).

**Step 2:** Add the discovered failing tests, fix any real defects (minimal change), re-run `mvn -q test` green.

**Step 3:** Confirm coverage meets the project's ≥70% line bar for the new classes.

**Step 4: Commit**

```bash
git add evita_roaring_bitmap/src
git commit -m "test: close coverage gaps on persistent bitmap prototypes (bug-hunter-tdd)

Ref: #1252"
```

---

## Phase P3 — Upstream Re-Sync Skill

### Task 3.1: Author the re-sync skill

**Files:**
- Create: `.claude/skills/roaring-bitmap-sync/SKILL.md`
- Create: `.claude/skills/roaring-bitmap-sync/` supporting notes as needed

**Step 1: Write the skill** so a future session can replay upstream changes onto the vendored copy. It must:
1. Read the currently-synced commit from `evita_roaring_bitmap/NOTICE`.
2. In `documentation/RoaringBitmap/` (or a fresh upstream clone), enumerate commits between the synced commit and the chosen target (`git log <synced>..<target>`), filtering to commits that touch any vendored class (cross-reference `CLOSURE.md`) — with special attention to the package-private internals the prototypes depend on (`RoaringArray.getKeyAtIndex/getContainerAtIndex/setContainerAtIndex/removeAtIndex`, `highLowContainer`, `Util`, container subtypes).
3. For each relevant commit, apply the change to the renamed copy (the skill explains the `org.roaringbitmap`→`io.evitadb.roaringbitmap` translation when applying patches/diffs).
4. Re-run `mvn -q test` in the module and require green.
5. Update the synced commit hash + the modifications list in `NOTICE`.

**Step 2: Document the limits** in the skill: it surfaces and guides; it does not blindly auto-merge. New upstream classes that become part of the closure must be vendored + renamed + tested.

**Step 3: Validate the skill end-to-end as a dry run** — point it at the current synced commit as both base and target and confirm it correctly reports "nothing to sync" without error.

**Step 4: Commit**

```bash
git add .claude/skills/roaring-bitmap-sync
git commit -m "feat: add roaring-bitmap-sync skill for upstream re-sync

Ref: #1252"
```

---

## Part 1 Done — Definition of Done

- `cd evita_roaring_bitmap && mvn clean install` is green in isolation.
- Vendored files retain Apache headers; `LICENSE`/`AUTHORS`/`NOTICE` present; synced commit recorded.
- Full ported test suite + gap-fill tests pass; new-class line coverage ≥70%.
- `roaring-bitmap-sync` skill exists and dry-runs clean.
- **No file outside `evita_roaring_bitmap/` and `.claude/skills/roaring-bitmap-sync/` was modified** → zero conflict surface with #760.

## Part 2 — Integration (GATED: do not start until #760 merges)

Tracked separately; one coordinated window. Steps: register `evita_roaring_bitmap` in root `pom.xml` `<modules>`; migrate ~100 evita call sites from `org.roaringbitmap` → `io.evitadb.roaringbitmap`, **also applying the root-class renames**: `RoaringBitmap` ×63 → `PersistentRoaringBitmap`, `Roaring64Bitmap` (3 files: `WritableEntityStorageContainerAccessor`, `PriceInternalIdContainer`, `evita_traffic_engine/TrafficRecordingIndex`) → `PersistentLongRoaringBitmap`, while `RoaringBitmapWriter` ×29 and the iterator types keep their names (only their package import changes). Note `RoaringBitmapWriter<RoaringBitmap>` generic args become `<PersistentRoaringBitmap>`. Update `evita_engine/module-info.java:130` (`requires roaringbitmap` → `requires io.evitadb.roaring_bitmap`); remove the dependency from `evita_engine/pom.xml`; drop `roaringbitmap.version` from root `pom.xml`; mirror the Kryo serializer pattern from `evita_store/.../index/serializer/TransactionalIntegerBitmapSerializer.java`; full-reactor `mvn clean install` green.
