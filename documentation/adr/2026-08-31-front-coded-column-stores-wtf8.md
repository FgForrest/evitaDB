---
title: Store front-coded String keys as WTF-8 rather than refusing values UTF-8 cannot carry
date: 2026-08-31
updated: 2026-09-03 10:45
status: accepted
kind: fix
issues: [1454]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/bPlusTree, evita_engine/src/main/java/io/evitadb/index/trigram]
supersedes: []
superseded-by: []
relates: [2026-08-31-trigram-query-path-optimization, 2026-08-24-fulltext-search-lucene-vs-inhouse, 2026-09-03-content-sized-value-tree-columns]
---

# Store front-coded String keys as WTF-8 rather than refusing values UTF-8 cannot carry

`FrontCodedStringColumn` — the B+ tree value column selected for every `String` attribute — stored
its keys as `String#getBytes(UTF_8)`. An unpaired UTF-16 surrogate has no UTF-8 representation, so
the JDK silently substituted `0x3F` (`'?'`) and the column handed back a **different string** from
the one it was given. The column now encodes with WTF-8, a superset of UTF-8 that differs from it
on exactly that input and on nothing else.

## Why

A Java `String` is a sequence of UTF-16 *code units*, so a lone surrogate in `0xD800-0xDFFF` is a
legal `String` that no Java-level validation rejects. It is not text — it is the debris left when
something upstream cuts a string through the middle of a supplementary character (a fixed-width
column, a naive `substring`, a broken importer). Whole emoji and CJK extensions were never affected
and never are; only halves of them.

The engine already stored such a value faithfully **everywhere except the index**. Kryo round-trips
lone surrogates (verified against 5.6.2, the pinned version), and `AttributeValueSerializer:52`
writes attribute values through `kryo.writeClassAndObject`. So the entity body on disk carried the
true value while the index carried `a?c` — a persisted divergence between two halves of the same
database, with three symptoms:

- **Filtering could not find a value the entity visibly contained.** `FilterIndex:1345` persists
  `bucket.histogramPoints()`, read back out of the corrupted tree, so the corruption outlived a
  restart.
- **Two distinct values collided.** `"a\uD800"` and `"a\uDC00"` both encoded to `a?`, which a unique
  index would report as a duplicate of values that are not equal.
- **A hard write-path crash on substring-accelerated attributes.** `InvertedIndex:1124` resolves the
  id of a bucket it has just created by re-probing the tree with the *original* value; the tree held
  `a?c`, the probe asked for `a\uD800c`, the lookup missed and `Assert.isPremiseValid` threw a
  `GenericEvitaInternalError` naming the value-id machinery rather than the encoding.

Reachability is wider than it first appears. Jackson parses a `\uD800` escape into a real lone
surrogate and re-emits it on the UTF-8 stream path, so **REST and GraphQL carry one in both
directions**; the embedded API obviously does. Only gRPC cannot — protobuf substitutes `'?'`
client-side before the request is sent — which is a property of that wire, not of the engine.

### Previous state

The column's javadoc discussed lone surrogates at length, which made the defect easy to misread as
handled. That discussion is about `isBmpSafe(String)` (`:1089`), which keeps a surrogate-bearing
*probe* off the byte-compare fast path. It protects **comparison ordering** and says nothing about
**storage fidelity**. The two are separate concerns and only the first was ever addressed.

The defect predates the trigram work: `FrontCodedStringColumn` landed 2026-06-17. What the trigram
index added was the first production caller of `attachValueIdConsumer`, which made the third symptom
above reachable and therefore visible.

## Options considered

Two advisory passes were run over this and **they disagreed**, which is most of why the record
exists.

### Option A — WTF-8 in the column (chosen)

Encode keys with WTF-8: UTF-8 extended to admit the surrogate range as its own three-byte sequence,
while still combining a well-formed pair into the single four-byte supplementary form.

- **Pros:** fixes the defect where it is, so every path is covered — live write, internal mutation,
  WAL replay, page restore — with no per-path exemption argument. Byte-for-byte identical to UTF-8
  for every string UTF-8 can represent, so blob size, prefix sharing, restart spacing and the
  supplementary-lead-byte threshold are all unchanged. Non-breaking: a feed that imports today keeps
  importing, and starts being indexed correctly. Removes the body/index divergence rather than
  hiding it.
- **Cons:** a hand-written codec in the column's hottest path, on a branch already carrying a large
  optimization campaign. Requires a per-column flag so the decode side does not pay a byte scan.

### Option B — refuse unpaired surrogates at the attribute-value boundary (declined)

Reject the value in `AttributeSchemaEvolvingMutation#verifyOrEvolveSchema`, with a throw inside the
column as a backstop.

- **Pros:** smallest change, no hot-path risk. Converts one silent corruption and one misleading
  internal error into a single honest validation failure. The boundary is recovery-safe *by
  construction*: `TransactionManager:2052` wraps replayed mutations in `ServerEntityUpsertMutation`,
  whose `verifyOrEvolveSchema` (`:118-126`) returns `Optional.empty()` without verifying, so a
  refusal hosted there cannot fire during WAL replay.
- **Cons:** narrows the accepted-value contract to suit one compact index implementation. It is a
  **breaking change on upgrade** — a nightly feed carrying a truncated emoji into a plain filterable
  attribute imports "successfully" today and would hard-fail after the change. It also needs the
  in-column backstop regardless, because `EntityMutation:82-93` dedupes verification by skip token
  (just the attribute key, `AttributeSchemaEvolvingMutation:66-68`), so a mutation list
  `[upsert("code","ok"), upsert("code","a\uD800c")]` never gets the second value inspected — and
  that hole is remotely reachable through REST/GraphQL.
- **Rejected because:** it removes a capability the engine already has rather than fixing the
  component that lacks it, and it breaks working imports on a version bump. The argument advanced
  for it — that gRPC cannot carry a lone surrogate, so storing one faithfully is theatre — does not
  hold: that lossiness already exists, applies equally to non-indexed attributes this column never
  sees, and is not made worse by storing the value correctly. **Revisit if** evitaDB ever decides to
  define its accepted-value contract as the intersection of what all its wire protocols can express,
  which would be a much larger decision than this one.

### Option C — CESU-8 (declined)

- **Rejected because:** it encodes surrogate *pairs* as two three-byte sequences, costing a byte per
  supplementary character and — worse — putting them below the `0xF0` threshold that the byte-compare
  fast path uses to exclude exactly the operands whose UTF-8 byte order disagrees with
  `String#compareTo`. WTF-8 keeps the four-byte form and therefore keeps that threshold exact.

### Option D — store UTF-16 code units (declined)

- **Rejected because:** roughly doubles the blob for ASCII-dominant identifier data, which is the
  workload this column exists to compress.

### Option E — leave the index lossy, fix only the crash (declined)

Make the value-id re-probe tolerate the substitution so `SUBSTRING_SEARCH` stops throwing, and accept
that such a value is unfindable by filter and mis-sorted.

- **Pros:** about ten lines, no hot-path risk.
- **Rejected because:** it fixes the loudest symptom and keeps the two quiet ones — the permanent
  body/index divergence and the false unique-violation between values that are not equal. Since it
  addresses the same crash as the chosen option, its only advantage is code volume, and that is not
  worth a defect class left standing.

## Decision

**Chosen: Option A.** The driver that separated it from B is that the engine's own storage layer
already keeps these values faithfully; the index was the sole component that did not, and the honest
fix for one lossy component is to stop it losing, not to forbid the input everywhere else accepts.
Refusal would also have been a breaking change on upgrade, and — because of the skip-token hole — not
even a single-site one.

For B to win instead, the accepted-value contract would have to be redefined as the intersection of
what every wire protocol can express. That is a product decision about evitaDB's API surface, not
about this column, and it would supersede this record.

## Key technical details

- **`Wtf8`** (`evita_engine/.../index/bPlusTree/Wtf8.java`) is the codec. The class is public but
  only `hasUnpairedSurrogate` is exported; encode/decode are package-private and the column is their
  only caller.
- **Both directions delegate to the JDK unless a surrogate is actually involved.** `encode` encodes
  through `String#getBytes` and re-encodes by hand only when the output holds `0x3F` *and* the value
  really carries an unpaired surrogate — the first test is necessary but not sufficient (a genuine
  `'?'` produces it too), which is why there are two.
- **Decoding is gated on a per-column flag**, `FrontCodedStringColumn#hasEncodedSurrogate`, computed
  in the same suffix-byte scan that already computes `bmpSafe` and carried through `duplicate()`. It
  is `false` for essentially every column, so the common decode is still `new String(bytes, UTF_8)`.
  A stale `true` is merely slower; a stale `false` would be wrong, which is why the flag is
  recomputed by every `encode` rather than being sticky.
- **One scan answers both corpus flags.** `Wtf8.classify(byte[], int, int)` returns `NOT_BMP_SAFE` and
  `HAS_ENCODED_SURROGATE` as a bitmask in a single pass, early-exiting once both bits are set;
  `SUPPLEMENTARY_LEAD_BYTE` lives in `Wtf8` with the surrogate constants rather than in the column. That
  is cheaper than the two scans it replaced (`keyLen` instead of `suffixLen + keyLen` per entry) and puts
  every UTF-8 encoding fact in the one class that owns them.
- **That scan covers each WHOLE key, not just its suffix, and the difference is load-bearing.**
  `bmpSafe` tests a single byte (`>= 0xF0`) and a shared-prefix boundary cannot hide a single byte
  from every suffix. "Is this an encoded surrogate" is a **joint** condition on a lead byte *and* its
  successor, and a boundary can fall exactly between them: `"a\uD7FF"` and `"a\uD800"` are adjacent
  sorted keys encoding to `61 ED 9F BF` and `61 ED A0 80`, sharing `61 ED`, so the second key's suffix
  is `A0 80` with no lead byte and the first key's suffix carries a lead byte that is legitimately not
  a surrogate. Both suffix scans answer "no". A future optimizer tempted back toward a suffix-local
  scan needs to handle that straddle explicitly — scanning from `shared - 2` would be sufficient,
  since a straddling sequence starts at most two bytes before the boundary — and should measure
  first, because the whole-key form was chosen deliberately over it. The fusion makes this sharper, not
  safer: the range is now a single call argument that reads like a free parameter, so `@param
  hasEncodedSurrogate` on `finishEncode` states explicitly why it may not be narrowed, and
  `shouldRoundTripWhenAPrefixBoundarySplitsASurrogateSequence` plus
  `shouldRoundTripASurrogateCorpusAcrossARestartBoundary` are what catch the edit if someone tries.
- **Ordering invariant, and its exact bound.** Byte order agrees with `String#compareTo` only while
  *both* operands consist solely of code points at or below `U+FFFF`. A lone surrogate satisfies
  that and stays on the fast path; a well-formed **pair** does not, and is excluded by the existing
  `>= 0xF0` threshold. Anyone widening that threshold must re-derive this.
- **`TrigramSubstringSearch#encodesWithoutLoss` now delegates to `Wtf8.hasUnpairedSurrogate`** rather
  than re-walking the same surrogate-pairing rule, and **the guard became live**: it used to be
  unreachable because a surrogate-bearing value could not be indexed on an accelerated attribute at
  all. It can now, so the guard is the only thing keeping a surrogate-bearing pattern off the byte
  path, where its `'?'` bytes would match values containing a literal question mark.

## Verification

- `Wtf8Test` — 11 tests over three properties: byte-identity with UTF-8 across 13 well-formed shapes
  (ASCII, a genuine `'?'`, Latin-1, NFD combining marks, CJK, emoji, `U+D7FF` and `U+E000` either
  side of the surrogate block); round-tripping 11 unpaired shapes; and order agreement, including
  5,000 randomized rounds.
- `FrontCodedStringColumnTest.UnpairedSurrogates` — 8 tests. The two that reproduced the defect are
  green unchanged in intent; added coverage for `bulkLoad`, prefix-shared surrogate keys, lookup and
  ordering against BMP neighbours, distinctness from `"a?c"`, and the flag surviving `duplicate()`.
- **Adversarial review found this wrong the first time, and the fix is pinned.** The flag originally
  scanned suffixes only, on an argument that looked sound and was not; the counterexample above
  returned `U+FFFD` for a stored key — the same class of corruption this record exists to remove,
  through a narrower door. `shouldRoundTripWhenAPrefixBoundarySplitsASurrogateSequence` fails without
  the fix. A sibling test covers the genuinely different case where the sequence sits wholly inside
  the shared prefix.
- **Counterfactual run, not just a green one.** With the codec neutered back to plain UTF-8,
  **7 of the 8** surrogate tests fail; the eighth is the normalization pin, which asserts a property
  of `Normalizer` and correctly does not depend on the column. The file was restored byte-identical
  and re-verified afterwards.
- **The randomized ordering test caught a real bound on the design.** An early version asserted that
  byte order agrees with `String#compareTo` for all BMP input; it failed on `[DAFB B629 C3F5 830A]`
  vs `[D99E DC59]`, where the second operand is a well-formed pair — one code point *above* the BMP.
  The assertion now states the column's own admission rule instead, and
  `shouldExcludeSupplementaryCharactersFromTheAgreement` pins that exception explicitly.
- Regression surface: 601 tests green across the column, codec, trigram, inverted-index, filter,
  sort, unique and B+ tree suites.

## Consequences & open follow-ups

- **Existing corruption cannot be repaired, by this or any other option.** Indexes already holding
  `'?'`-substituted keys stay that way; a stored `'?'` is indistinguishable from a genuine question
  mark, and `EntityCollection:2542` records that there is no reindex-from-bodies machinery. The fix
  is new-writes-only. A catalog whose entity bodies hold lone surrogates would need its affected
  filter/sort/unique indexes rebuilt to benefit — no such tooling exists.
- **No storage-format change.** The front-coded blob is in-memory only: `FilterIndexStoragePart`
  persists `ValueToRecordBitmap[]` through Kryo and the tree is rebuilt on load. No migration, no
  version bump.
- **gRPC remains lossy and this does not change that** — protobuf substitutes `'?'` client-side. A
  value written via REST and read via gRPC will differ. That is a property of the wire, was true
  before this change, and is now the only place the substitution still happens.
- `documentation/developer/front-coded-column-surrogate-defect.md` described the defect while it was
  open and is deleted by this change; everything in it that outlived the fix is above.

## Related work

- `2026-08-31-trigram-query-path-optimization` — the campaign whose adversarial review surfaced this
  defect, and whose byte-containment path is the reason `encodesWithoutLoss` exists at all.

## Timeline

- **2026-08-31 13:00** — defect found during adversarial review of the trigram query-path work;
  written up and pinned by two `@Disabled` reproductions
- **2026-08-31 14:05** — reproductions enabled, confirmed red
- **2026-08-31 15:10** — WTF-8 implemented; reproductions green, counterfactual confirms 7 of 8 fail
  without it
