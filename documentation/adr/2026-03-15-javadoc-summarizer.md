---
title: Replace JavaDocCopy with an LLM-generated JavaDoc summarizer for QueryConstraints
date: 2026-03-15
updated: 2026-08-02 14:25
status: accepted
kind: infrastructure
issues: []
prs: []
areas: [evita_query/api/query, evita_test/evita_documentation_tests, tools]
supersedes: []
superseded-by: []
relates: [2026-08-02-editorconfig-formatting-parity]
---

# Replace JavaDocCopy with an LLM-generated JavaDoc summarizer for QueryConstraints

`JavaDocCopy`, a `@Disabled` JUnit test that copied full constraint-class JavaDoc verbatim onto
`QueryConstraints` factory methods (producing a ~42,000-line file with 40-95 lines of JavaDoc per
method), was replaced with `JavaDocSummarizer`: a Maven-invocable tool that calls the OpenAI API to
generate a concise, single-paragraph summary per method, tracked via an MD5 source hash so
unchanged methods are skipped on regeneration.

## Why

The verbatim-copy approach made `QueryConstraints` — the primary discoverability surface for the
query API — nearly unusable as a place to skim available constraints, since every factory method
carried its constraint class's full documentation (semantics, usage context, examples) rather than
a summary. The tool needed to be incremental, since regenerating all ~450 summaries on every doc
change would be slow and costly against a paid API.

## Options considered

The implementation plan visibly changed its mind once while being written, and the reasoning for
each step is preserved verbatim because it is the only real option comparison in the source
document.

### Option A — store the hash as a custom `@sourceHash` JavaDoc tag (initially drafted, then declined)

Embed the MD5 hash as a JavaDoc tag (`@sourceHash abc123...`) rather than a Java annotation, since
the hash-writing tool (`JavaDocSummarizer`) lives in test scope of one module while
`QueryConstraints` lives in the `evita_query` main-scope module — a cross-module compile-time
annotation dependency looked awkward at first.

- **Pros:** no cross-module compile dependency of any kind; plain text, trivially parsed back out.
- **Rejected because:** Johnny's explicit requirement was "create a Java method annotation for it,"
  which a JavaDoc tag does not satisfy.

### Option B — `@SourceHash` as a real Java annotation, package-private, relocated into `evita_query` (chosen)

Move the annotation itself into `evita_query/src/main/java/io/evitadb/api/query/SourceHash.java` —
the same package as `QueryConstraints` — with `@Retention(RetentionPolicy.SOURCE)` and
package-private (no `public` modifier) visibility, so it is a real annotation usable on the factory
methods without becoming part of the public API surface and without needing an import (same
package) or a cross-module compile dependency (source retention only, read back by the tool via
QDox source parsing rather than reflection).

- **Pros:** satisfies the explicit requirement for a Java annotation; zero bytecode footprint;
  invisible to external consumers; no import needed since it shares `QueryConstraints`'s package.
- **Cons:** the annotation now lives in a production module (`evita_query`) even though only a
  test-scoped tool in a different module ever writes it.

## Decision

**Chosen: Option B.** `SourceHash` is a package-private, source-retained annotation in
`evita_query/src/main/java/io/evitadb/api/query/SourceHash.java`.

## Key technical details

- `evita_query/src/main/java/io/evitadb/api/query/SourceHash.java` — the annotation, exactly as
  designed: `@Target(ElementType.METHOD)`, `@Retention(RetentionPolicy.SOURCE)`, package-private.
- `evita_test/evita_documentation_tests/src/test/java/io/evitadb/documentation/javadoc/JavaDocSummarizer.java`
  (568 lines) and its test `JavaDocSummarizerTest.java` (625 lines) — **not** in
  `evita_functional_tests` as the plan originally specified; both landed in
  `evita_test/evita_documentation_tests`, a module that did not exist when the plan was written and
  was created afterward (`c78ace8fe`, "introduce tag taxonomy and split test modules") to hold
  documentation-generation tooling.
  - `OPENAI_MODEL = "gpt-4.1"`, `MAX_PARALLEL_REQUESTS = 4`, `TEMPERATURE = 0.3`, `MAX_TOKENS = 500`
    — all hardcoded constants, matching the plan.
  - `JavaDocCopy.java` is confirmed deleted (no match anywhere in the tree).
- `evita_test/evita_documentation_tests/pom.xml:247` — the `generate-javadoc` Maven profile
  (`exec-maven-plugin`, `mainClass=io.evitadb.documentation.javadoc.JavaDocSummarizer`,
  `classpathScope=test`), in the module the tool actually lives in.
- `tools/generate-query-constraints-javadoc.sh` — the shell entry point. It additionally sources
  `OPENAI_API_KEY` from the GNOME Keyring via `secret-tool` when the environment variable is unset,
  which the plan did not specify.
- `evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java` — now 14,174 lines (down
  from the ~42,000-line verbatim-copy baseline the plan describes) and carries 514 `@SourceHash`
  annotations, confirming the tool was run against the real file and not just built and shelved.

## Verification

The tool's own regression suite, `JavaDocSummarizerTest`, was run fresh as part of this conversion
(`mvn -pl evita_test/evita_documentation_tests test -P documentation -Dtest=JavaDocSummarizerTest`
— the `documentation` profile is required, since this module's tests are skipped by default): **29
tests, 0 failures, 0 errors**, across six nested classes — MD5 hash computation (6), source file
rewriting (6), user-docs-link extraction (3), JavaDoc formatting (8), source-hash extraction (2),
method-signature building (4). These are pure parsing/formatting/hashing unit tests; none call the
live OpenAI API. The end-to-end effect (514 `@SourceHash` entries, `QueryConstraints.java` reduced
to a third of its former size) is directly observable in the tree and is the strongest evidence the
full pipeline, including the API call, ran successfully at least once, in whatever environment had a
valid `OPENAI_API_KEY`.

## Consequences & open follow-ups

- **The packaged entry point is broken.** `tools/generate-query-constraints-javadoc.sh`'s last line
  invokes `mvn -pl evita_test/evita_functional_tests test-compile exec:java -Pgenerate-javadoc -q`,
  but the `generate-javadoc` profile lives in `evita_test/evita_documentation_tests/pom.xml`, not
  `evita_functional_tests` — confirmed by `rg -n "generate-javadoc"` against both POMs, which finds
  it only in the latter. The `-pl` module was never updated when the class moved to the new
  `evita_documentation_tests` module. Running the script as written will fail to find the profile.
  This is a one-line fix (`-pl evita_test/evita_documentation_tests`) but is recorded here rather
  than silently corrected, since the tool's history of actually working (514 generated hashes) must
  predate this drift — someone ran it directly against the correct module, not through this script.
- **This is why evitaDB has no code formatter.** Because a stale hash triggers a paid API call, any
  tool that reformats JavaDoc turns a whitespace change into an OpenAI bill. That is the decisive
  argument against Spotless recorded in
  [2026-08-02-editorconfig-formatting-parity](2026-08-02-editorconfig-formatting-parity.md).
- No CI or scheduled job runs this tool; it is invoked manually against a paid external API key, so
  `QueryConstraints.java`'s summaries can silently go stale relative to the constraint classes'
  JavaDoc without anyone noticing, beyond the incremental MD5 check catching drift the next time
  someone does run it.

## Timeline

- **2026-03-12** — implementation plan written, commit `f7348f961`
- **2026-03-15** — implemented and applied to `QueryConstraints.java`, commit `b309c9fc4`. That
  commit's message cites "Ref: #8" — issue #8 is "Compute 'dynamic' set of attribute histogram for
  references" (an unrelated histogram feature, closed 2026-04-23); the reference in the commit
  message is a mislabel, not a real link to this work. No pull request was found for this commit via
  the GitHub API.
- **2026-07-31** — implementation plan retired, replaced by this record
