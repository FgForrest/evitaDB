---
title: Keep IDEA and Claude formatting in step with a shared .editorconfig and a diff-scoped hook, not Spotless
date: 2026-08-02
updated: 2026-08-02 14:25
status: accepted
kind: infrastructure
issues: [1119]
prs: []
areas: [.editorconfig, .claude/hooks, .claude/rules, .claude/settings.json]
supersedes: []
superseded-by: []
relates: [2026-03-15-javadoc-summarizer, 2026-07-07-roaring-bitmap-vendoring]
---

# Keep IDEA and Claude formatting in step with a shared `.editorconfig`, not a formatter plugin

Formatting consistency between IntelliJ IDEA and Claude's edits is now carried by a single
`.editorconfig` at the repository root — exported from IDEA, so the IDE and the agent resolve the
same values — plus a `PostToolUse` hook that checks the lines each edit changed against that file.
Spotless was evaluated for this role first and rejected outright; no formatter runs over the tree.

## Why

Claude's edits and IDEA's *Reformat Code* were free to disagree, and the disagreement only surfaced
in review as whitespace noise mixed into real diffs. The obvious fix — a formatter plugin in the
build — is the one thing this repository cannot take, for reasons none of which are visible in the
code:

- **`QueryConstraints.java` carries 514 `@SourceHash` annotations**, MD5s over constraint JavaDoc
  plus method signature. Stale hashes make `JavaDocSummarizer` regenerate the summaries **through
  the paid OpenAI API**. Any formatter that touches JavaDoc turns a whitespace change into an API
  bill. See [2026-03-15-javadoc-summarizer](2026-03-15-javadoc-summarizer.md).
- **Two subtrees are vendored Apache-2.0 code** whose whitespace must stay byte-identical to
  upstream or every replay becomes a merge conflict — `evita_roaring_bitmap` (see
  [2026-07-07-roaring-bitmap-vendoring](2026-07-07-roaring-bitmap-vendoring.md)) and the Undertow
  routing tree under `externalApi/utils/path`.
- **The tree does not satisfy its own style.** Measured over the 5,766 product/test `.java` files
  (generated, vendored and documentation trees excluded): 243 files (4.2%, 11,140 lines) are
  space-indented against the tab standard, 3,118 (54.1%) contain a line over 120 columns, 55 carry
  trailing whitespace. Any whole-file gate blocks on day one; any whole-file formatter produces a
  reformat-the-world commit.

### Previous state

`.idea/codeStyles/Project.xml` was the only style definition, and it is tracked — so IDEA users were
consistent with each other, and nothing at all constrained Claude. `.claude/rules/code-style.md`
carried a prose summary that had drifted: it said **100 characters** where the real hard wrap is
**120**, and being the file loaded as instructions for every Java edit, it was the limit actually
being applied.

A caution for anyone reading `Project.xml` to re-derive the style: its **root-level `<option>`
entries are dead legacy** from scheme `version="173"` and IDEA no longer applies them to Java. Taken
at face value they describe a style the codebase inverts — they imply `if(x)` and `else` on its own
line, against measured counts of `if (` 18,009 vs `if(` 55, and `} else {` 7,306 vs 389. Read
`.editorconfig` instead.

## Options considered

### Option A — export `.editorconfig` from IDEA, enforce a mechanical subset per edit (chosen)

IDEA writes `.editorconfig` from *Settings | Editor | Code Style | Export*, and applies it **on top
of** `Project.xml`, so it wins wherever the two disagree. A `PostToolUse` hook resolves the same
file at run time and checks the lines an edit changed.

- **Pros:** one file both sides read; no formatter ever rewrites a byte, so `@SourceHash` and the
  vendored trees are untouched; enforcement is scoped to new lines, so the 3,118 pre-existing
  violations neither block work nor demand a mass reformat.
- **Cons:** only a mechanical subset is enforceable — of the file's 1,143 properties, **1,111 are
  `ij_*`** that only IntelliJ can apply, leaving `Project.xml` the real formatter for wrapping,
  alignment, blank lines and member arrangement. The hook detects; it does not fix.

### Option B — Spotless with the `idea` step (declined)

Spotless's `idea` step delegates to a local IDEA binary reading `Project.xml`, which is exact parity
by construction.

- **Pros:** the only option that guarantees byte-identical output to *Reformat Code*.
- **Rejected because:** it requires a local IDEA install and **fails headless** —
  diffplug/spotless#2544, "No valid license found", open since 2025-07. It cannot run in CI, so the
  formatter would exist on developer machines only, which is the situation it was meant to fix.

### Option C — Spotless with the `eclipse` step (declined)

The CI-safe alternative: Spotless formats with the Eclipse engine from a checked-in config.

- **Pros:** runs anywhere, no IDE dependency, gates in CI.
- **Rejected because:** it is a *different engine* from IDEA's, so *Reformat Code* and
  `spotless:apply` ping-pong on the same file forever. IDEA's `SMART_TABS` (tabs to indent, spaces
  to align) has no exact Eclipse analogue, and `ratchetFrom` selects **files, not lines** — the
  first touch of any of the ~6,070 in-scope files rewrites it whole, in the same commit as a
  one-line edit.

A variant — point IDEA itself at the Eclipse engine via the krasa adapter plugin, so one engine
serves both sides — was **declined by Johnny**: it means changing the IDE's formatter for every
developer to accommodate a build plugin.

### Option D — Spotless `licenseHeader` step only (declined)

Adopt Spotless for BSL header enforcement alone, skipping reformatting entirely.

- **Pros:** the cheapest slice of Spotless; no formatter engine involved.
- **Rejected because:** it solves nothing and would do harm. Header coverage on files that should
  have one is already **100%**. All 419 of 6,203 tracked non-generated `.java` files lacking a
  header are intentional: 259 documentation example snippets (which must not carry it), 130 vendored
  RoaringBitmap and 20 vendored Undertow (both Apache-2.0), 6 ANTLR-generated, 4 `module-info.java`.
  The step would stamp BSL onto Apache-licensed third-party code.

### Option E — `editorconfig-checker` as the per-edit gate (declined for that role, kept for audits)

An off-the-shelf checker that reads `.editorconfig` directly and therefore cannot drift from it.

- **Pros:** no hand-maintained mirror of the config; the obvious tool for the job.
- **Rejected because:** it has **no concept of `SMART_TABS`** and reports every aligned multi-catch
  and parameter list as "spaces instead of tabs", forcing `--disable-indentation`; 54.1% of files
  trip `max_line_length`, forcing `--disable-max-line-length`. With both disabled it checks only
  trailing whitespace, end-of-line and charset — **strictly less than the hook** — and it has no
  line or diff scoping, so it cannot tell a new line from the 2,479 pre-existing violations it
  reports repo-wide. **Revisit if** it grows SMART_TABS support or a changed-lines mode.

## Decision

**Chosen: Option A.** Parity and CI-safety are mutually exclusive under Spotless — the step that
guarantees parity cannot run headless, and the step that runs headless guarantees drift. Removing
the formatter from the equation entirely dissolves the conflict: `.editorconfig` gives both sides
the same numbers, and detection replaces rewriting, which is what keeps `@SourceHash` and the
vendored trees safe.

The trade accepted is that **`ij_*` wrapping and alignment remain unenforced outside IDEA**. That is
tolerable because those rules produce *equivalent* code that merely looks different, whereas the
enforced subset — indentation style, line width, trailing whitespace, line endings — is what
actually generates diff noise between the two authors.

Spotless becomes worth revisiting only if **both** diffplug/spotless#2544 is fixed *and*
`@SourceHash` stops being coupled to a paid API call.

## Key technical details

- **`.editorconfig`** — the IDEA export, followed by hand-maintained sections the export cannot
  produce. **They must stay last**: EditorConfig resolves last-matching-section-wins.
  - `root = true`, absent from the export — without it resolution walks up out of the project.
  - GraphQL and `*.cs`: the export is extension-scoped and omits plugin languages, so 229
    `.graphql` files silently fell back to the `[*]` 4-space default although 225 of 227 measure
    2-space.
  - Carve-outs setting `max_line_length = off` / `trim_trailing_whitespace = false` for
    `evita_roaring_bitmap/**`, the Undertow routing tree, and `documentation/**` (whose examples
    must not carry the BSL header and are measurably space-indented — 406 of 680 files).
- **`.claude/hooks/editorconfig-check-on-edit.sh`** — registered in `.claude/settings.json` beside
  the existing proto linter on the same `Edit|Write` matcher.
  - Every threshold is **resolved from `.editorconfig` at run time** by a small awk EditorConfig
    engine (glob→ERE: `**` spans `/`, `*` does not, `{a,b}` alternates; last match wins), so the
    carve-outs are honoured automatically and a re-export with different values changes what is
    enforced with no edit to the script.
  - Line length is measured in **columns with tabs expanded to `tab_width`**, as IDEA applies the
    hard wrap. Character counting is not equivalent: 3,118 files exceed 120 columns against 2,731
    by characters.
  - Scope is **lines changed since HEAD**, not lines written by the call — the tool input carries no
    line range. Untracked files are checked whole.
  - Indentation is checked **for Java only**, the one language where every legitimate exemption is
    known (JavaDoc `*` continuation, SMART_TABS alignment).
  - **Internal failures exit 2 loudly.** Any non-zero status other than 2 is a *non-blocking* hook
    error, so a failing `git diff` under `set -e` would switch enforcement off in silence.
- **`.claude/rules/code-style.md`** — the line-length rule corrected from 100 to 120 and pointed at
  its source.
- **`awk` here is mawk 1.3.4, not gawk.** `ENDFILE`/`BEGINFILE` are gawk extensions that mawk
  ignores *without erroring* — a per-file counter built on `ENDFILE` silently reports zero. This
  produced a false "0 files over 120 columns" against a true 3,118 while the figures above were
  being measured.

## Verification

- **17-case hook suite**, all passing: a synthetic throwaway project with its own `.editorconfig`
  proving the thresholds really come from the file (set `max_line_length = 40` there and 40 is
  enforced), `{a,b}` section matching, `dir/**` carve-outs disabling checks, tab-width column
  expansion, and the real-repository cases — SMART_TABS alignment accepted, space indentation
  flagged, JavaDoc continuation accepted, trailing whitespace, CRLF, over/at 120 columns, non-Java
  indent skipped, unlisted extension skipped, and both changed files self-checking clean.
- **Live fire through the agent harness**, both directions: a space-indented `Write` was blocked
  naming `:2: space indentation`; a tab-indented file with SMART_TABS alignment passed.
- **Diff scoping** against `evita_query/.../QueryParser.java`, which holds 18 pre-existing
  space-indented lines: untouched → pass; one clean line appended → pass; one space-indented line
  appended → blocked on that line alone. File restored, `git diff` empty.
- **Fail-loud path** with a stubbed `git` returning 128 on `diff`: exit 2 with the cause visible,
  not a silent pass.

Two defects were found and fixed by this testing rather than by review. A
`while IFS=$'\t' read -r lineno content` loop **strips the content's leading tabs** (tab is IFS
whitespace), turning every tab-indented, space-aligned SMART_TABS line into a false "space
indentation" report — all per-line work moved into awk. And an earlier "zero violations" measurement
was a false negative: `rg` here has no PCRE2, the lookahead pattern errored, and `2>/dev/null`
swallowed it; the portable `^ [^*]` revealed the 243 files.

## Consequences & open follow-ups

- **Re-exporting `.editorconfig` from IDEA destroys the hand-maintained tail** — `root = true`,
  GraphQL, `*.cs` and all three carve-outs. They must be re-applied by hand. The file header carries
  this warning; there is no automation for it.
- **A repo-wide gate is blocked by 2,448 trailing-whitespace lines.** `editorconfig-checker` reports
  2,479 errors today (2,448 trailing whitespace, 1 CRLF, the rest binary-encoding on `.pyc` files
  and images it should not be reading). A `sed -i 's/[ \t]*$//'` sweep over tracked text files is
  the prerequisite for turning it into CI enforcement; it was not done here because it touches
  hundreds of files unrelated to this work.
- **The `ij_*` majority stays IDEA-only.** Nothing outside the IDE enforces wrapping, alignment,
  blank lines or import layout, and `Project.xml` remains the sole owner of the `<arrangement>`
  member-ordering rules, which `.editorconfig` cannot express at all.
- **Non-Java indentation is unenforced**, deliberately: the tree mixes tabs and spaces too freely
  per file type for a per-line rule to be anything but noise. The GraphQL 2-space section therefore
  serves IDEA only.
- **`.editorconfig` carries trailing whitespace on lines 23, 104, 140, 142 and 144**
  (`ij_visual_guides = `) straight from the IDEA export. Do not hand-fix — a re-export restores it.
  It matters only if `editorconfig-checker` ever becomes a gate, since it would flag its own config.

## Related work

- [2026-03-15-javadoc-summarizer](2026-03-15-javadoc-summarizer.md) — the `@SourceHash` mechanism
  whose coupling to a paid API is the single strongest argument against any formatter here.
- [2026-07-07-roaring-bitmap-vendoring](2026-07-07-roaring-bitmap-vendoring.md) — establishes the
  vendored tree whose whitespace must stay byte-identical to upstream, hence its carve-out.

## Timeline

- **2026-08-02** — Spotless evaluated and rejected; `.editorconfig` exported and repaired, hook
  written, tested and committed as `0741fc165`
