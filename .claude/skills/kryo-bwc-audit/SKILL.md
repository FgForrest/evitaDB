---
name: kryo-bwc-audit
description: Audit Kryo persistence backward compatibility before releasing a SNAPSHOT. Finds every class serialized through SerialVersionBasedSerializer whose on-disk format changed since the previous released version, and verifies each has its serialVersionUID bumped exactly once with a matching backward-compatible (BWC) reader registered in every Kryo configurer that registers it. Invoke when preparing a release, after adding/removing a persisted field, or when asked to check that stored catalogs / WAL data will still load.
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(python3 *), Bash(git *), Bash(rg *), Bash(batcat *), Bash(cd *), Bash(ls *), Bash(mvn *), Bash(jar *)
---

# Kryo Backward-Compatibility Audit

evitaDB persists schemas (catalog storage) and mutations (WAL) through **Kryo**, wrapped in
`io.evitadb.store.entity.serializer.SerialVersionBasedSerializer`. Every persisted class that
changed its serialized layout since the last release must keep old data readable. This skill
verifies that discipline mechanically, so a format change can't silently orphan on-disk data
and crash catalog-open / WAL-replay with `StoredVersionNotSupportedException`.

## The mechanism (know this before trusting any verdict)

`SerialVersionBasedSerializer<T>` (constructed with a target class `T`) works like this:

- **Write** stamps `ObjectStreamClass.lookup(T).getSerialVersionUID()` — **T's own
  `serialVersionUID`** — as a leading `long`, then the current serializer's payload.
- **Read** reads the stamp. If it equals the current UID → current serializer. Otherwise it looks
  up a **backward-compatible reader** registered via
  `.addBackwardCompatibleSerializer(oldUID, new OldSerializer())`. If none matches → it throws
  `StoredVersionNotSupportedException` (data won't load).

So the rule per persisted class: **if its `serialVersionUID` changed since data was last written
with the old value, a BWC reader keyed at that old UID must be registered.** Two old values can be
in the wild:

1. **prev** — the previously *released* UID (e.g. from `release_2026-1`). Released customer data.
2. **fork** — the UID at the *dev fork point* the current work branched from. If a class churned in
   dev *after* the release, its dev UID differs from the released one; dev/nightly data carries it.
   Bumping again without a reader for this value **orphans** it. Register the reader for the
   **immediate-pre-change** UID, whichever it was — not only the released one.

### The multi-configurer trap (subtle, caused a real High-severity bug)

Kryo's `DefaultClassResolver.register` stores registrations **by class with last-write-wins**
(`classToRegistration.put(type, registration)`). When two configurers are composed into one kryo
(`A.andThen(B)`) and **both register the same class**, B's registration wins for by-class
read/write — so a BWC reader present in only A is **silently overridden and dead**.

Real example: `CatalogSchema` is registered in both `SchemaKryoConfigurer` (with its `_2026_1`
reader) and `CatalogHeaderKryoConfigurer` (reader-less). The catalog-storage kryo is
`SchemaKryoConfigurer.andThen(CatalogHeaderKryoConfigurer)`, so the reader-less header registration
wins and pre-bump catalogs crash on open — even though the "right" reader exists elsewhere. The fix
was to give the header registration the same reader. **A reader must be present in every configurer
that registers the class, not just once globally.**

## Policy (evitaDB convention)

- A class's `serialVersionUID` is bumped **exactly once per release** (`2026.2-SNAPSHOT`). If an
  earlier feature in the same SNAPSHOT already bumped it (fork UID != released UID), **do not bump
  again** — extend the *current* serializer to write the new field and keep the already-registered
  released-UID reader. Bumping twice would orphan the intermediate value.
- New fields are typically appended and read with a strictly-appending layout, but **do not assume
  append** — some serializers write mid-stream (e.g. `CatalogSchemaSerializer` writes the conflict
  field before evolution modes and attributes). Byte-fixture tests must match the real layout.
- Snapshot readers (`<Class>Serializer_YYYY_M`) are read-only; their `write` throws.

## Procedure

1. **Run the analyzer** from the repo root:
   ```bash
   python3 .claude/skills/kryo-bwc-audit/audit.py
   ```
   It auto-detects the newest `release_*` branch as **prev** and `merge-base HEAD origin/dev` as
   **fork**. Override with `--prev <ref>` / `--fork <ref>` when needed (e.g. audit against a tag).
   It discovers **all** configurers that register via `SerialVersionBasedSerializer` (not a fixed
   list), so a class registered in several is checked in each.

2. **Read the table + FINDINGS.** Exit code 0 = clean, 1 = at least one `***` problem. Every `***`
   verdict is a blocking problem; the tool **fails loud when it cannot verify** rather than passing
   silently. Verdicts:
   - `OK` — bumped where needed, readers present.
   - `NEW (no persisted history)` — class absent at prev and fork; needs no reader.
   - `*** MISSING READER: <uid>` — bumped (or churned) but no BWC reader for that old UID. **Fix.**
   - `*** MULTI-CONFIGURER: reader ... absent here` — the override trap above. **Fix** by adding the
     reader to *this* configurer's registration too.
   - `*** SERIALIZER CHANGED WITHOUT UID BUMP` — the current serializer body changed on this branch
     but the UID equals the released value → silent-corruption risk. Either the change is
     byte-neutral (confirm by reading the diff) or a bump + reader is missing. **Investigate.**
   - `*** UNVERIFIED: ...` — the tool could not resolve the class to a `serialVersionUID` **and** the
     class's serializer changed on this branch, so a real change may be hiding. **Resolve by hand**
     (nested class? ambiguous name? no explicit UID?) before shipping.
   - Non-blocking **NOTES** (printed separately, exit stays 0): classes with no explicit
     `serialVersionUID`, or nested/unresolvable classes **whose serializer did not change** — very
     likely fine, but eyeball them. A class here that you *did* change is a red flag the tool
     under-resolved: verify it manually.

3. **Verify every finding by hand before acting** — the analyzer parses source, it doesn't run Kryo:
   - Confirm the class is actually *serialized* through the kryo in question (e.g. via
     `kryo.writeObject`/`readObject`), not just registered defensively. `git grep` the write path.
   - For a MULTI-CONFIGURER hit, confirm the composition order and that the trap kryo is real
     (`.andThen` chains in the `*PersistenceService` classes).

4. **Fix pattern.** Add the existing snapshot reader to the deficient registration — do **not** mint
   a new UID or serializer if the released-format reader already exists:
   ```java
   kryo.register(
       Foo.class,
       new SerialVersionBasedSerializer<>(new FooSerializer(), Foo.class)
           .addBackwardCompatibleSerializer(<releasedUID>L, new FooSerializer_2026_1()),
       index++
   );
   ```
   If a genuine new bump is required (first change this release): bump the class's `serialVersionUID`,
   snapshot the old serializer as `FooSerializer_<YYYY>_<M>` (read-only, `write` throws), and register
   it at the **old** UID in every configurer that registers `Foo`.

5. **Prove it with a byte-fixture regression** in
   `evita_test/.../store/schema/ConflictResolutionBackwardCompatibilityTest` (or a sibling):
   compose the **real** kryo (reproduce the `.andThen` order that triggers the trap — using only the
   reader-ful configurer would pass even while the bug is live), render a pre-change record in the
   **actual** old layout, stamp it with the old UID, and read it back through `kryo.readObject`.
   Confirm the test is **RED before the fix** and green after.

## Building / testing (respect the isolation rule)

Before any `mvn install`, ensure the poms are on the isolated feature version (e.g.
`2026.2.503-SNAPSHOT`), never the shared `RC*-SNAPSHOT`, so you don't clobber other agents' `.m2`:
```bash
mvn -q versions:set -DnewVersion=<feature-version> -DprocessAllModules=true -DgenerateBackupPoms=false
```
Then a full-reactor `install -DskipTests` makes `.m2` coherent at that version, after which
`mvn test -pl evita_test/evita_functional_tests -Dtest=<Test>` exercises the change. Use
`-DskipTests`, never `-Dmaven.test.skip` (the latter suppresses the functional-tests test-jar).

## Limitations

- Static analysis: it reports *candidate* problems from source; you must confirm the class is truly
  on a persisted path and reproduce the trap kryo. It cannot detect a double-bump whose intermediate
  UID was never persisted, nor a layout change hidden inside an unchanged-signature serializer body
  (limb 2 flags the file-changed case; a byte-neutral refactor there is a false positive to dismiss).
- It keys registrations off the literal `new SerialVersionBasedSerializer<>(new XSerializer(), Y.class)`
  shape. A registration written differently won't be parsed — grep for `SerialVersionBasedSerializer`
  to sanity-check the scanned set matches reality.
- **Running the audit *on* `dev` at release time degenerates the `--fork` anchor** (`merge-base HEAD
  origin/dev` becomes HEAD, so the orphan-UID check is a no-op). When auditing a release branch or dev
  itself, pass `--fork` explicitly to the dev tip *before* the release work, or omit it and rely on
  `--prev` alone. The default fork detection is aimed at auditing a feature branch that forked from dev.
- Nested serialized classes (e.g. `AttributesStoragePart.AttributesSetKey`) have no standalone file, so
  their UID isn't extracted; they surface as NOTES when stable and block only when their serializer
  changed. Verify a changed nested class's UID by hand in its enclosing file.
