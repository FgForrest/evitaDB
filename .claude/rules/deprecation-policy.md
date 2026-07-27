---
paths:
  - "**/*.java"
---

# Deprecation Policy

## What `since` means

`@Deprecated(since = "X")` follows the JDK's own convention: **`X` is the version in which the element became deprecated** — the first release that ships the deprecation notice.

It is **not**:
- the last version the element was still valid/recommended (a common mistake — this reads one or more releases too old)
- the version the element was originally introduced
- for backward-compatibility shim classes (see below), the on-disk format vintage they still support

When adding a new `@Deprecated`, use the version currently in development (the reactor `pom.xml` version, stripped of `-SNAPSHOT`/`.RCx`) — that's the release this deprecation will actually ship in.

## No exception for backward-compatibility shims

This applies uniformly, including to Kryo backward-compatibility reader classes (`*Serializer_20XX_Y.java`, `Migration_20XX_Y.java`) and other born-deprecated compat shims. Even though these classes' names already encode a format vintage (e.g. `AttributeSchemaSerializer_2024_11` reads the format as of release `2024.11`), `since` must still be the version *this class* became deprecated — i.e. normally the release right after the vintage in its name, when the superseding format/serializer landed. Do not repurpose `since` to restate the vintage; that information belongs in the class name and the `@deprecated` JavaDoc prose (e.g. "removed once no version prior to X is used"), which is a *separate* removal-safety threshold and may legitimately differ from `since`.

## Verifying values

Run `tools/audit-deprecated-since.sh` (no arguments audits the whole repo; pass one or more file/directory paths to scope it) after adding or auditing `@Deprecated` annotations. It walks each annotation line's full git history to find the commit that actually introduced the deprecation, and cross-checks the declared `since` against the first release tag that actually contains that commit.

Known tool limitations (verify flagged mismatches by hand before "fixing" them):
- Doesn't follow renames across files, and can lose tracking across very large same-file diffs — a relocated (not newly deprecated) member can be misflagged.
- Can conflate two separate, near-identical boilerplate sibling classes (common in the Kryo serializer family) as if one were a rename of the other.
- For not-yet-released commits it falls back to the reactor `pom.xml` SNAPSHOT version, which is a best-effort guess, not authoritative — a release train can close before a commit lands, pushing it into the next one.

## History

`tools/process_deprecated.sh` / `tools/update_deprecated.sh` previously backfilled `since=` on bare `@Deprecated` annotations using `git tag --merged <commit>` (the newest tag already released *before* the commit) — this is the "last valid version" mistake described above, baked into a tool. Both scripts now use `git describe --tags --contains` (the first tag that actually *contains* the commit) instead. If you find an already-annotated `since=` value that predates this fix, don't assume it's correct — run the audit tool.
