# GraphQL / REST representation of a bodyless ancestor

Discussion document for the one open decision in
[#1365](https://github.com/FgForrest/evitaDB/issues/1365). Engine semantics are settled: under
`hierarchyContent(ANY, entityFetch(...))` an ancestor that cannot be materialized in the queried
locale stays in the chain as a **bodyless pointer**, and the chain continues above it with bodies.
The question is only how that pointer looks on the wire.

## The three APIs do not share a shape

| API | parent chain shape | body → pointer → body? |
|---|---|---|
| Java | nested chain, two types (`SealedEntity` / `EntityReferenceWithParent`) | yes |
| gRPC | nested chain, pointer-only above the first pointer | **no** — needs a proto field |
| REST | `parentEntity`, a **nested recursive object** of the same entity type | yes — same type throughout |
| GraphQL | `parents`, a **flat list** of the non-hierarchical entity object | yes — elements are independent |

So the nesting problem that forces the gRPC proto change does **not** exist in REST or GraphQL. What
does exist in both is the *distinguishability* problem.

## The running example

```
Electronics  PK=1   en only        ← no Czech data
  Fotoaparáty PK=2  cs + en
    Zrcadlovky PK=3 cs + en        ← queried, in cs
```

`code` is global, `name` is localized. Under `ANY`, node 1 is a pointer; node 2 has a body.

## The blocking fact

`EntityDescriptor` declares `primaryKey`, `type`, `scope`, `locales` and `allLocales` as **non-null**
(`EntityDescriptor.java:53-92`).

For a pointer we know `primaryKey` and `type`. We do **not** know `locales` or `allLocales` — the
whole point is that the body was never materialized. `scope` is knowable (traversal is per-scope) but
is not carried by a pointer today.

In GraphQL a non-null field resolving to null **bubbles**: the element becomes null, and depending on
the list's own nullability the entire `parents` list — or more — collapses. A client selecting
`parents { locales }`, which it has every right to do, would destroy its own response.

**This rules out the "just return nulls" option for GraphQL.** It is not a matter of taste.

## GraphQL options

### G1 — element present, data fields null · **not viable**

```graphql
{ parents { primaryKey code name } }
```
```json
"parents": [ { "primaryKey": 1, "code": null, "name": null },
             { "primaryKey": 2, "code": "cameras", "name": "Fotoaparáty" } ]
```

Zero schema change, and fine as long as the client only selects nullable fields. But
`parents { locales }` cannot be answered, and null-bubbling turns that into a collapsed response
rather than a graceful degradation. Also ambiguous: indistinguishable from a materialized entity
whose attributes are genuinely null.

### G2 — element present, plus a discriminator field

```graphql
{ parents { primaryKey bodyAvailable code } }
```
```json
"parents": [ { "primaryKey": 1, "bodyAvailable": false, "code": null },
             { "primaryKey": 2, "bodyAvailable": true,  "code": "cameras" } ]
```

Additive, non-breaking, and the client can branch. **Does not solve the non-null problem** — the
client can still select `locales` on a pointer element. Only viable if those fields are relaxed to
nullable, which is itself a schema-breaking change for consumers relying on non-null.

### G3 — union of entity and reference · **recommended**

```graphql
{ parents {
    ... on Category          { primaryKey code name locales }
    ... on CategoryReference { primaryKey type }
} }
```
```json
"parents": [ { "__typename": "CategoryReference", "primaryKey": 1, "type": "Category" },
             { "__typename": "Category", "primaryKey": 2, "code": "cameras",
               "name": "Fotoaparáty", "locales": ["cs","en"] } ]
```

The reference type declares only the fields it can answer, so the non-null problem disappears by
construction — a client cannot select `locales` on a pointer. It mirrors the Java model exactly
(`SealedEntity` vs pointer), which keeps all four surfaces telling one story.

**Cost: a GraphQL breaking change.** Existing queries selecting `parents { name }` without inline
fragments stop validating. Size it with `tools/diff-graphql-schemas.sh` (the `gql-breaking-changes`
skill) before committing.

## REST options

### R1 — nested object with data keys omitted

```json
{ "primaryKey": 3, "attributes": { "code": "dslr", "name": "Zrcadlovky" },
  "parentEntity": {
      "primaryKey": 2, "attributes": { "code": "cameras", "name": "Fotoaparáty" },
      "parentEntity": { "primaryKey": 1, "type": "Category" } } }
```

No schema change and the nesting works — the parent type is the full entity schema with optional
fields. REST has no null-bubbling, so an under-populated object degrades gracefully rather than
collapsing. But if the OpenAPI schema marks `locales`/`scope` as `required`, the response violates
its own contract, and generated clients with non-nullable fields may fail to deserialize.
**Verify the generated OpenAPI before choosing this.**

### R2 — nested object plus a discriminator flag

As R1 with an explicit `"bodyAvailable": false`. Additive, and removes the ambiguity between "no
body" and "body with null attributes". Same `required`-fields caveat as R1.

### R3 — `oneOf` with a discriminator

```json
"parentEntity": { "type": "Category", "primaryKey": 1 }
```
declared as `oneOf: [Category, EntityReference]` with a discriminator. Most correct, mirrors G3 and
the Java model, breaking for generated clients. Size with `tools/diff-openapi-schemas.sh`.

## The actual question

**Is the pointer/body distinction part of the public contract, or a Java implementation detail?**

In Java the distinct type earns its keep mechanically: it is what lets the proxy layer decide between
throwing and returning empty. Neither GraphQL nor REST has exceptions, so that justification does not
carry over — the question there is purely whether a *client* needs to tell "this ancestor exists but
has no body under your filter" from "this ancestor has null data".

It matters for at least one real case: a breadcrumb renderer deciding between *skip this crumb*,
*fall back to the code*, and *render an empty label*.

- **Contract** → G3 + R3. Consistent across all four surfaces, breaking for both external APIs.
- **Implementation detail** → G2 + R1/R2, or G1 + R1 if the non-null fields are relaxed. Cheap,
  ships inside 2026.2, but the external APIs then carry strictly less information than the Java
  driver.

**Recommendation: G3 + R3 if a breaking change is acceptable in this release**, because a
half-answer here is expensive to revisit — the shape is what clients code against. If it is not
acceptable, G2 + R2 is the honest fallback: it keeps the information, at the cost of a flag that will
look vestigial once the APIs are eventually unified.

Not verified: whether the generated OpenAPI marks the affected fields `required`, and the exact
null-bubbling blast radius in GraphQL (depends on the `parents` list wrapper). Both are quick checks
that should precede a final decision.
