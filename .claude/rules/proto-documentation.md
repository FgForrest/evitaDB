---
paths:
  - "**/*.proto"
---

# Proto Documentation Convention

`.proto` comments are the *only* documentation client implementers see — they are the source for
generated Javadoc, and (via `csharp_namespace`) for the C# client's IDE tooltips, and for whatever
TypeScript/other generators run downstream. A weak or wrong comment here ships to every language
binding. Write for a client implementer who has never opened the Java engine source, not for a
reader who already knows what the field does.

## Every message and every field needs a real comment

Not just present — informative. `// The X value.` on a field named `x` is not documentation, it's
punctuation. If the field name already says everything a comment would say, the field is either
well-named enough to need only a one-line confirmation of units/nullability/range (see below), or
the field is one of the mechanical `oneof` dispatch wrappers exempted below.

## Nullability of wrapper types

evitaDB proto messages carry optional/nullable values almost exclusively as
`google.protobuf.{Int32,Int64,String,Bool,...}Value` wrappers rather than proto3 `optional` scalars.
An unset wrapper is indistinguishable from a wrapper set to the zero value unless the comment says
what "unset" means. Every wrapper-typed field's comment must state the unset case explicitly.
Model:

```proto
// Deprecation notice contains information about planned removal of this entity from the model / client API.
// This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
//
// If notice is `null`, this schema is considered not deprecated.
google.protobuf.StringValue deprecationNotice = 4;
```
(`GrpcEntitySchema.proto:36`)

Don't write "optional" and stop — say what absence *means* in domain terms (falls back to a
default, root of a hierarchy, feature disabled, etc.), not just that it can be absent.

## Units

Any numeric field carrying a physical quantity (time, size, count-with-a-scale) states its unit
inline, in parentheses, at the end of the summary line. Model:

```proto
// Duration of time since the server was started (seconds)
int64 uptime = 3;
```
(`GrpcEvitaManagementAPI.proto:17-18`)

## Paging base index

evitaDB has two distinct pagination models in the gRPC surface — say which one a field belongs to,
every time:

- **Page-based** (`pageNumber`/`pageSize`, e.g. `GrpcPaginatedList`): `pageNumber` is **1-indexed**
  (page 1 is the first page) — see `io.evitadb.dataType.PaginatedList#getPageNumber`. State this
  explicitly on both the request field that sets a desired page and the response field that reports
  the current one; don't assume the reader knows evitaDB's convention.
- **Strip/offset-based** (`offset`/`limit`, e.g. `GrpcStripList`): `offset` is a **0-indexed** count
  of records to skip from the beginning, not a page number.

When documenting a paging field, also state (or link to a message-level comment stating) the
server-side maximum, if one is enforced, and what happens when the request exceeds it (clamped?
rejected?) — verify against the handler, don't guess.

## `oneof` exclusivity

Every `oneof` block's comment states that exactly one member may be set (or, if the semantics
differ — e.g. "at most one" vs "exactly one" — which one it actually is). State it once on the
`oneof` keyword's comment, not on every member.

## Deprecation

- Every `[deprecated = true]` field or `option deprecated = true` message states *why* and, if
  applicable, *what to use instead*, in prose — not just the compiler flag. Existing convention,
  keep using it: `// deprecated in favor of \`replacementField\`` or `// RENAMED TO "newName"`.
  A bare `[deprecated = true]` with no reason is not acceptable.
- State the evitaDB release the element became deprecated in, in `YYYY.MAJOR` form, leading the
  comment: `// Deprecated since 2024.12 - deprecated in favor of \`replacementField\``. This is the
  same `since` convention as `@Deprecated(since = "X")` on the Java side (see
  `.claude/rules/deprecation-policy.md`) - the version whose release first shipped the deprecation
  notice, never the last version the element was still valid, nor the version it was introduced.
  Proto has no structured `since` attribute to hold this, so it belongs in the comment prose itself.
  Find it the same way the Java side does: walk the field/message line's git history to the commit
  that actually added `[deprecated = true]` (not one that merely reformatted an already-deprecated
  element), then resolve the first release tag that contains that commit.
- `// TOBEDONE: <what> (<issue URL>)` is this repo's accepted marker for deprecated-and-scheduled-
  for-removal proto elements, matching the same convention already used on the Java side (see
  `.claude/rules/deprecation-policy.md` and 60+ existing `TOBEDONE` occurrences under
  `evita_api/`, `evita_query/`, etc.). It requires a linked GitHub issue — an un-linked `TOBEDONE`
  or bare `TODO` is not acceptable in committed `.proto` files, same as `.java`.

## No Javadoc markup

`.proto` comments are read by non-Java client generators too. Never use Javadoc syntax
(`{@link #FOO}`, `{@code ...}`) — it leaks into the generated C#/TypeScript/other-language comments
verbatim and means nothing there. Use plain Markdown-ish prose or backtick code spans instead.

## Field name typos

If a field name itself is wrong (a shipped typo, e.g. `queryPriceModelValue` for "query price
*mode*"), document the mismatch in the comment. **Never rename the field** to fix it — the field
number is stable but the name drives generated accessor methods and JSON field mapping in every
client language; renaming is a breaking change requiring its own deliberate migration, not a
comment-quality fix.
