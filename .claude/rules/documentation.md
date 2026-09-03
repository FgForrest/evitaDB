---
paths:
  - "documentation/user/**"
---

# Documentation Workflow

## English is the only hand-written source

User documentation lives under `documentation/user/en/**`. Edit only the English files.

## Czech is machine-translated, never hand-edited

`documentation/user/cs/**` is generated from the English source by the
[Comenius Maven plugin](https://github.com/FgForrest/comenius-maven-plugin) (see
`documentation/blog/en/23-maven-comenius-plugin.md`), configured in the root `pom.xml`
(`one.edee.oss:comenius-maven-plugin`). Run it with `tools/translate.sh`
(`mvn -N comenius:run -Dcomenius.action=translate`); it needs an `OPENAI_API_KEY` env var and, per
run, re-translates every English file whose tracked source commit is stale — not just the one you
just edited — so only run it when a Czech sync is actually wanted, not after every English edit.

Do not write or edit a Czech mirror file by hand when adding/changing English documentation — run
the translation step instead (or leave it for Johnny/CI) to regenerate it from the English source.

### Exception: a translation bug that reproduces across independent runs

Comenius can mistranslate the same source passage identically on two independent runs (e.g. an
internal `#anchor` link resolving to a real but wrong heading) — a reproducible model failure mode,
not translation drift a re-run will fix. If a translation defect survives a second independent
translation attempt, a narrow hand-fix of just the broken span is allowed instead of a third
automated retry. Scope the edit to the minimum needed to correct the defect, and call out in the
commit message that it's a hand-fix and why (bug class, that it reproduced twice).

### A rejected file is a tool bug, not a hand-fix case

Comenius validates each translation against the structure of its source and **throws away** one whose
blank-line or heading count drifted, reporting `Translation failed for <file>: blank line count changed
(N -> M)`. This is a different situation from the mistranslation above, and it has a different answer.

The cause observed so far is the model normalizing blank lines the source carries — dropping the trailing
blank line of a chunk, or collapsing a doubled blank line. Both are deterministic: the same file fails
with byte-identical numbers on every run, and the plugin's own repair pass (`after-repair.md`) leaves
blank lines untouched. **Do not spend a second run on it, and do not edit the English source to appease
the validator** — a blank line the source legitimately carries is not a defect, and the file is the
source of truth for every other consumer.

A hand-fix is usually not even possible: for a chunked file only the failed unit is preserved under
`target/comenius-failures/<locale>/<file>/<unit>/`, so the rest of the translation is gone and the Czech
page cannot be assembled. The correct response is to leave that page one revision behind, say so
explicitly in the commit message, and report the plugin bug — the mojo exposes no strictness or
tolerance parameter to work around it.

`tools/translate.sh` exits non-zero and names the rejected files when this happens; the plugin itself
ends the build successfully, so never read a green Maven run as a complete sync.
