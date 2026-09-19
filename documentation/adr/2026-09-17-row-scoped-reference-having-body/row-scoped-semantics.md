# The semantics a `referenceHaving` body obeys

Supporting material for `README.md`. This is the model the implementation was derived from and the one a
future change to this area has to keep satisfying. Two independent advisory reviews checked it; every
correction they produced is folded in.

## 1. The data model

An entity collection `E` holds *owner* entities. A reference named `R` declared on `E` associates owners with
entities of a target collection `T`, and each association is a **row** carrying attributes of its own —
attributes on the relation, not on either entity:

```
Rows_R ⊆ E × T × A₁ × … × Aₙ
```

where each `Aᵢ` is a reference-attribute domain extended with a distinguished absent value `⊥`. Write
`Rows_R(o)` for the rows owned by `o`, and `target(r)` for a row's target.

- Ordinary reference: `(owner, target)` is a key — an owner holds at most one row per target.
- Duplicate-allowing cardinality: `(owner, target, representative-attribute-values)` is the key.

**That key is enforced at write time, not merely conventional.** `BuilderReferenceBundle#upsertDuplicateReference`
refuses the mutation with `InvalidMutationException` — *"Cannot add duplicate reference … with the same
representative attributes … as it would be indistinguishable from existing reference with internal id N"*.
§4 rests on this.

**`Rows_R` is relative to the queried scope, and one query uses one `Rows_R` throughout.** The scope
constraint selects which owners *and which rows* are in play, and `referenceHaving(R)`,
`referenceHaving(R, φ)` and `referenceHaving(R, not(φ))` must all range over the same set. Not pedantry: a
live owner whose only row targets an archived entity would otherwise be counted by the empty-body path and
missed by the body paths, and identity I1 below would fail with the semantics perfectly correct. This
interacts with issue #1583.

**Open modelling question.** `Rows_R ⊆ E × T` admits no row with a dangling target. If a row may name a
target that does not exist, `not(entityHaving(ψ))` has no value on it and the model needs extending. Answer
this before relying on §5's identities.

## 2. Two-valued, with `⊥` an ordinary value

evitaDB has **no three-valued logic**. Absence is the value `⊥`; every predicate is total and two-valued over
rows. This was measured against the unmodified engine, not assumed.

```
attributeEquals(a, v)(r)  ≡  att(r, a) = v          (false when att(r, a) = ⊥)
attributeIsNull(a)(r)     ≡  att(r, a) = ⊥
attributeIsNotNull(a)(r)  ≡  att(r, a) ≠ ⊥
¬φ(r)                     ≡  Boolean complement of φ(r)
```

Consequences:

- `¬attributeEquals(a, v)(r) ≡ att(r, a) ≠ v ∨ att(r, a) = ⊥` — a row not carrying `a` **satisfies** the
  negation.
- `¬attributeIsNotNull(a) ≡ attributeIsNull(a)`, and the two partition the rows.
- `RH(attributeIsNull(a)) ⊆ RH(not(attributeEquals(a, v)))`.

**What two-valuedness buys is not De Morgan** — that holds in Kleene three-valued logic too. It buys **I1**,
`RH(φ) ∪ RH(¬φ) = RH()`, which fails the moment any row evaluates to neither true nor false. I1 is therefore
the identity that tests this section.

**For the user documentation:** `not(attributeEquals(a, v))` is *not* "has a different value", and
`not(attributeLessThan(a, v))` is *not* `attributeGreaterThanEquals(a, v)`. The spelling for "present and
different" is `and(attributeIsNotNull(a), not(attributeEquals(a, v)))`. This diverges from SQL, where
`NOT (a = 'x')` is `UNKNOWN` for a `NULL` `a` and excludes the row. The divergence is pre-existing, applies
uniformly at the entity level, and is kept.

## 3. `referenceHaving` is an existential quantifier that binds a row

The owner correlation is part of the join condition — without it the theta-semijoin would let any owner join
any matching row:

```
referenceHaving(R, φ)  ≡  { o ∈ E | ∃ r ∈ Rows_R(o) : φ(r) }
referenceHaving(R)     ≡  { o ∈ E | Rows_R(o) ≠ ∅ }
```

The body is a formula in the bound row variable `r`; every connective inside it, negation included, is a
predicate about that one row:

```
referenceHaving(R, and(φ, ψ))  ≡  { o | ∃r : φ(r) ∧ ψ(r) }
referenceHaving(R, or(φ, ψ))   ≡  { o | ∃r : φ(r) ∨ ψ(r) }
referenceHaving(R, not(φ))     ≡  { o | ∃r : ¬φ(r) }
```

Negating the container is the other thing, and it is vacuously true for an owner with no rows, exactly as
SQL's `NOT EXISTS`:

```
not(referenceHaving(R, φ))  ≡  E \ referenceHaving(R, φ)  =  { o | ∀ r ∈ Rows_R(o) : ¬φ(r) }
```

Same scoping rule as SQL's `EXISTS`, whose subquery `FROM` binds a row variable that the `WHERE` — negations
included — is evaluated against. MongoDB's `$elemMatch` and Elasticsearch's `nested` query scope the same
way. The analogy is **supporting precedent, not proof**: it justifies the choice, it does not determine
evitaQL's language design. The decisive argument is §5's expressiveness result.

## 4. Why per-index evaluation is licensed, and where it stops

`Rows_R = ⊎ₜ Rows_t`, and both selection and projection distribute over a disjoint union, so for **any**
body:

```
RH(body) = ⋃ₜ π_owner(σ_body(Rows_t))
```

The union is outermost, which is what allows the engine to evaluate one reduced index at a time.

**The caveat that matters**: projection does *not* distribute over set difference. Computing the negated case
as `π_owner(Rows_t) \ π_owner(σ_φ(Rows_t))` equals `π_owner(σ_¬φ(Rows_t))` **iff `π_owner` restricted to
`Rows_t` is injective** — iff no owner holds two rows inside one reduced index. Where it is not, the
expression silently computes a third semantics, `∃t : ∀ rows to t : ¬φ`.

**The precondition holds by construction for every reference.** A reduced index is keyed by
`(referenceName, targetPk, representativeAttributeValues[])`, and §1's write-time invariant forbids two rows
of one owner sharing that key. `EntityIndexKey`'s constructor rejects any discriminator that is not a
`RepresentativeReferenceKey` when the type is `REFERENCED_ENTITY`, and
`ReferenceIndexMutator#getOrCreateReferencedEntityIndex` is the only path that creates one — so the premise
cannot be defeated by an indexing route the write-time invariant does not cover.

**Group reduced indexes are not injective.** `ReferenceIndexMutator#getOrCreateReferencedGroupEntityIndex`
keys them `(referenceName, groupPk, representativeAttributeValues[])`, dropping the target primary key, so
one owner can appear in one group index from two distinct rows. Nothing may complement across them.

**Nested-query constraints are not row-local by default, as conjuncts or as subtrahends.** `entityHaving` and
`groupHaving` yield owner sets pooled across *all* of an owner's rows, resolved from the collection rather
than from the index currently being evaluated. Both witnesses below have owner `o` holding rows `(t1, a=1)`
and `(t2, a=2)`:

```
referenceHaving(R, and(attributeEquals(a,1), entityHaving(ψ))) , ψ matching t2 only
  row-scoped: o excluded.  naive loop at t1: owners_t1 ∩ owners(t2) = {o} → admitted, wrong.

referenceHaving(R, not(groupHaving(ψ))) , rows (t1,g1) and (t2,g2), ψ matching g2
  row-scoped: o included (the t1 row is in no matching group).
  naive loop at t1: owners_t1 \ GH = {o} \ {o} = ∅ → excluded, wrong.
```

This is why both constraints get an index-local adapter rather than being evaluated once and intersected.
For `groupHaving` over a **non-faceted** reference the per-`(target, group)` → owners mapping does not exist
in the target-keyed family at all, which bounds what any adapter there can do.

## 5. The partition, and every shape expressible

Four blocks, with respect to a body `φ`:

```
N   = { o | Rows_R(o) = ∅ }                            no rows at all
M⁺  = { o | Rows_R(o) ≠ ∅ ∧ ∀r : φ(r) }                has rows, all match
X   = { o | (∃r : φ(r)) ∧ (∃r : ¬φ(r)) }               mixed rows
C   = { o | Rows_R(o) ≠ ∅ ∧ ∀r : ¬φ(r) }               has rows, none match
```

`E = N ⊎ M⁺ ⊎ X ⊎ C` is a partition, and `M = M⁺ ⊎ X`.

**Translation rule to SQL**: evitaQL `not(x)` inside the body maps to SQL `(x) IS NOT TRUE`, never `NOT (x)`
— under three-valued logic those differ for exactly the `⊥` rows §2 admits.

| set | evitaQL | SQL |
|---|---|---|
| `M⁺ ⊎ X ⊎ C` | `referenceHaving(R)` | `EXISTS (SELECT 1 FROM r WHERE r.o = e.id)` |
| `N` | `not(referenceHaving(R))` | `NOT EXISTS (…)` |
| `M` | `referenceHaving(R, φ)` | `EXISTS (… AND φ)` |
| `N ⊎ C` | `not(referenceHaving(R, φ))` | `NOT EXISTS (… AND φ)` |
| `C` | `and(referenceHaving(R), not(referenceHaving(R, φ)))` | `EXISTS (…) AND NOT EXISTS (… AND φ)` |
| `C ⊎ X` | `referenceHaving(R, not(φ))` | `EXISTS (… AND φ IS NOT TRUE)` |
| `N ⊎ M⁺` | `not(referenceHaving(R, not(φ)))` | `NOT EXISTS (… AND φ IS NOT TRUE)` |
| `M⁺` | `and(referenceHaving(R), not(referenceHaving(R, not(φ))))` | `EXISTS (…) AND NOT EXISTS (… AND φ IS NOT TRUE)` |
| `X` | `and(referenceHaving(R, φ), referenceHaving(R, not(φ)))` | `EXISTS (… AND φ) AND EXISTS (… AND φ IS NOT TRUE)` |

**The universal quantifier is the decisive expressiveness argument.** Rows 7 and 8 — "every row satisfies
`φ`", with and without vacuous truth — exist only under row-scoping. Under either owner-scoped reading,
`not(referenceHaving(R, not(φ)))` collapses to `M` or to `N ⊎ M`, both of which admit a mixed owner. Proof of
inexpressibility: `o₁` holding `{a=1}` and `o₂` holding `{a=1, a=2}` agree on every owner-level fact
derivable from `φ`, so no stack of connectives outside the container can separate them. The only escape is to
hand-write `¬φ` as a positive atom, which is impossible on an open domain and itself depends on #1584.

## 6. Identities a future change must still satisfy

`RH(φ) = referenceHaving(R, φ)`, `RH() = referenceHaving(R)`.

| # | claim |
|---|---|
| I1 | `RH(φ) ∪ RH(¬φ) = RH()` — tests §2's two-valuedness; fails if any row is neither true nor false |
| I2 | `RH(φ) ∩ RH(¬φ) = X` |
| I3 | `RH(¬φ) ⊆ RH()` — a row-scoped negation never admits a row-less owner |
| I4 | `not(RH(φ)) = N ∪ C` |
| I5 | `and(RH(), not(RH(φ))) = C` |
| I6 | `C ⊆ RH(¬φ)`, equality iff `X = ∅` |
| I7 | `RH(¬¬φ) = RH(φ)` |
| I8 | `RH(not(and(φ,ψ))) = RH(or(not φ, not ψ))` — De Morgan inside the body |
| I9 | `RH(or(φ,ψ)) = RH(φ) ∪ RH(ψ)` |
| I10 | `RH(and(φ,ψ)) ⊆ RH(φ) ∩ RH(ψ)`, strict when some owner has a φ-row and a ψ-row but no φ∧ψ-row |
| I11 | `RH(¬φ) \ not(RH(φ)) = X` **and** `not(RH(φ)) \ RH(¬φ) = N` |
| I12 | `not(RH(¬φ)) = N ∪ M⁺` and `and(RH(), not(RH(¬φ))) = M⁺` — the universal quantifier |

**I11 has to be two-sided.** As a bare `≠` it is false on any fixture with `N = X = ∅`, and worse, it is
*satisfied* by the partial fix the next row warns about.

**I2, I5 and I6 are tautologies unless the oracle enumerates rows.** `X` is *defined* as `M ∩ RH(¬φ)`, so
computing the expected set from the two queries under test asserts `A ∩ B = A ∩ B`. Expected sets must be
derived from entity bodies, which is what the suite's oracles do.

**Which wrong implementation each assertion kills**, and what the fixture must contain for it to bite:

| wrong implementation | caught by | needs in the fixture |
|---|---|---|
| nested `not` complements in `E` (the state before this work) | I3 | a row-less owner (`N ≠ ∅`) |
| complement the *union* at the container boundary | I2 / I6 | a mixed owner (`X ≠ ∅`) |
| three-valued slip in the per-index super set | I1, and `RH(attributeIsNull(a))` | a `⊥`-only owner |
| `∃` distributed over `∧` | I10 strict, or `RH(and(φ, not φ)) = ∅` | a mixed owner |
| per-index complement over co-targeted rows | unreachable — guarded at write time, §4 | assert the mutation is refused |

The second row matters most: "complement the union at the boundary" is what a *partial* fix produces. It
satisfies I1, I3, I4, I5, I7, I9 and I10, and only I2/I6 with a mixed owner catch it.

**Every identity should run under both index options and on both the index and the prefetch path.** The plan
choice changed the answer on the unmodified engine — 30 against 230 on one measured shape — so a suite that
pins only one path pins half the engine.
