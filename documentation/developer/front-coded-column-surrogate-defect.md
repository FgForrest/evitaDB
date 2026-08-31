# `FrontCodedStringColumn` does not round-trip unpaired surrogates

**Status:** open. Reproduced by two `@Disabled` tests in
`FrontCodedStringColumnTest.UnpairedSurrogates` — enable them when the fix lands.

## The defect in one line

The column stores every key as `String#getBytes(UTF_8)`. An unpaired surrogate has no UTF-8
representation, so the JDK silently substitutes `0x3F` (`'?'`), and the key that comes back out is a
**different string** from the one that went in.

```
"a\uD800c".getBytes(UTF_8)   →  [97, 63, 99]
new String([97, 63, 99])     →  "a?c"          // not equal to the original
```

Verified empirically, not inferred: `FrontCodedStringColumn:333` (`insertKeyAt`), `:358` (`encode`)
and `:581` (`decodeAt`).

## Why an unpaired surrogate reaches us at all

A Java `String` is a sequence of UTF-16 **code units**, not of Unicode scalar values, so a lone
surrogate in `0xD800..0xDFFF` is a perfectly legal `String` that no Java-level validation rejects. The
usual way one arrives in practice is a UTF-16 string truncated mid-pair by an upstream system — a
fixed-width column, a naive substring, a broken importer — and the value then flows through the API,
the schema layer and the mutation pipeline without anything objecting.

`TrigramCodec` accepts them **deliberately** (`TrigramCodec:128-133`): its contract is to see exactly
the text the exact predicate sees, and refusing one there would turn a value the predicate matches
happily into a hard write-path failure. That reasoning is sound and is not the problem.

## Two distinct failures, with two distinct fixes

### 1. Silent corruption — the general case

An attribute value containing a lone surrogate is stored as a *different value*. No error is raised,
and afterwards it is indistinguishable from a value that genuinely contained `'?'`. Filtering,
sorting and equality all subsequently operate on the substituted string.

This is the older and broader of the two: it applies to any `String` attribute in a B+tree, with or
without a substring accelerator, and it predates the trigram index entirely.

### 2. A hard write-path failure — when a value-id sink is attached

`InvertedIndex#notifyValueCreated` (`:1124`) resolves the id of a bucket it has just created by
**re-probing the tree with the original value**:

```java
final int valueId = this.buckets.valueIdOf(normalizedValue);
Assert.isPremiseValid(valueId != ValueIdAllocator.UNASSIGNED_VALUE_ID, …);
```

The tree now holds `"a?c"`; the probe asks for `"a\uD800c"`; the lookup misses and the premise throws
`GenericEvitaInternalError`. The message says the freshly created bucket carries no value id, which is
true but points at the value-id machinery rather than at the encoding that actually broke.

Value-id sinks are attached for attributes declaring the `SUBSTRING_SEARCH` accelerator, so **this is
the shape a user hits**: indexing an entity whose accelerated `String` attribute contains a truncated
surrogate fails with an internal error naming the wrong subsystem.

Note this failure is *louder* than case 1 and therefore, perversely, better.

## What is already guarded, and why it does not help

The column knows about lone surrogates in one specific place. `findKeyPosition` has a
byte-comparison fast path that is only valid for BMP-only operands, and the probe-side check scans the
original `String`'s UTF-16 units rather than its encoded bytes — precisely because
`getBytes` would hide a lone surrogate behind `0x3F` and let a non-BMP probe take a path that assumes
BMP (`FrontCodedStringColumn:104`, `:1032-1040`).

That guard protects **comparison ordering**. It says nothing about **storage fidelity**, which is the
defect here. The two are easy to conflate when reading the class: the javadoc discusses lone
surrogates at length and a reader may reasonably conclude they are handled.

## Normalization is the wrong lever

Changing the tree's Unicode normalization form (it currently uses **NFD** — `FilterIndex:310`,
`SortIndex:440`, `AbstractAttributeStringSearchTranslator:165`) does **not** fix this and is not
related to it. An unpaired surrogate participates in no canonical composition or decomposition, so
both forms leave it exactly as it is. Confirmed in
`FrontCodedStringColumnTest.UnpairedSurrogates#shouldShowNormalizationDoesNotAffectTheDefect`, which
passes today:

```
NFD("a\uD800c") == "a\uD800c"   →  true
NFC("a\uD800c") == "a\uD800c"   →  true
```

The loss happens strictly below normalization, at the `String → byte[]` step. Any fix has to be there.

## Options for a fix

| Option | Round-trips? | Cost | Notes |
|---|---|---|---|
| **Reject unpaired surrogates at the write boundary** | n/a — refuses | none | Cheapest and arguably most correct: the input is malformed text. Turns silent corruption and a misleading internal error into one clear validation failure. **Contradicts `TrigramCodec`'s deliberate acceptance**, so the refusal must live at the attribute-value boundary, not in the codec |
| **WTF-8** | yes | none for valid text | A strict superset of UTF-8 that encodes lone surrogates as 3-byte sequences. Byte length and byte order are unchanged for all well-formed input, so front-coding, prefix sharing and the BMP fast-path threshold keep working. Requires a hand-rolled encoder/decoder |
| **CESU-8** | yes | +1 byte per supplementary char | Encodes surrogate *pairs* as two 3-byte sequences. Also breaks UTF-8 byte order for supplementary characters — though the column already falls back to `String` comparison for those |
| **Store UTF-16 code units** | yes | ~2x for ASCII | Rejected: this column exists to compress ASCII-dominant identifier data, and doubling every key defeats its purpose |

**Recommendation: refuse at the boundary.** A lone surrogate is not text a catalog should carry, the
refusal is O(length) and allocation-free (scan for a `char` in `0xD800..0xDFFF` without a valid
partner), and it converts two confusing failure modes into one honest one. WTF-8 is the right answer
only if some workload genuinely needs to *store* such values.

Whichever is chosen, the boundary must be a place the value passes **before** any index sees it — the
current asymmetry, where `TrigramCodec` accepts what the column cannot store, is what makes the
failure surface so far from its cause.

## Reproduction

`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/bPlusTree/FrontCodedStringColumnTest.java`,
nested class `UnpairedSurrogates`:

- `shouldRoundTripAnUnpairedSurrogate` — `@Disabled`, red today. Asserts the column returns the key it
  was given.
- `shouldNotSilentlyAlterAStoredKey` — `@Disabled`, red today. Pinned separately because the two have
  different fixes: the round trip needs an encoding that can carry the value, whereas this one is
  satisfied by refusing it.
- `shouldShowNormalizationDoesNotAffectTheDefect` — **enabled and passing**, so the wrong lever stays
  ruled out rather than being re-proposed.

Found during an adversarial review of the trigram query-path optimization work; see
`documentation/adr/2026-08-31-trigram-query-path-optimization.md`.
