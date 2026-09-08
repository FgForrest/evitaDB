---
title: JDK 21 modernization is adopted only where it is provably behaviour-preserving
date: 2026-09-08
updated: 2026-09-08 12:00
status: accepted
kind: refactor
issues: [1518]
prs: [1519]
areas: [evita_common/dataType, evita_common/utils, evita_query/api/query/expression, evita_engine/core/expression, evita_engine/index/map, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_grpc, evita_external_api/evita_external_api_rest, evita_store/evita_store_server]
supersedes: []
superseded-by: []
relates: []
---

# JDK 21 modernization is adopted only where it is provably behaviour-preserving

## Why

The move from JDK 17 to 21 (#1518) landed with **zero source changes** — the platform bump alone.
This record covers the follow-on pass that adopted final JDK 18-21 language and API features where
they improve readability, type safety and compile-time exhaustiveness, and **declined them
everywhere else**.

The declines are the reason this record exists. Seven candidate areas were surveyed; two produced
nothing, and several attractive-looking conversions are wrong here for reasons that are invisible
from the code. Without a record, each will be re-proposed.

The pass also surfaced **three latent defects** that had nothing to do with JDK 21 syntax. Those are
described in their own commits; only the one caused by the platform bump is discussed here.

### Previous state

Multi-branch runtime type dispatch was written as `if / else if instanceof` chains throughout the
converters, visitors and serializers. Several already-`sealed` hierarchies carried defensive
`default -> throw` branches that the compiler could have proven dead. `MapHeapSize` replayed JDK 17's
`HashMap` sizing arithmetic.

## Decisions taken

| Decision | Why |
|---|---|
| Convert multi-branch type dispatch to pattern `switch` in dispatch-shaped code only | ~110 branches across 14 files became exhaustive-by-shape and lost their casts; hot paths excluded (see below) |
| Delete `default` branches over already-`sealed` hierarchies | 8 branches across 5 hierarchies; javac's acceptance of the switch **is** the proof, and a new permitted subtype now breaks the build instead of throwing at runtime |
| Seal `ObjectOperationStep`, leaves `final` | Approved as a deliberate **breaking API change**; the expression grammar is closed in fact, and sealing converts one runtime throw into a compile-time guarantee. `final` rather than `non-sealed` follows every existing sealed hierarchy here |
| Replace `instanceof` + redundant cast with binding patterns | 85 sites / 26 files, from a convertible population of 176. JDK 16 syntax — residue the 17 migration left, not something 21 unlocked |
| Size Kryo's collection factories with `HashMap.newHashMap` and friends | `(int) Math.ceil(count / .75f)` **is** the JDK's `calculateHashMapCapacity`; removes a duplicated copy of the load-factor rule |
| Correct `MapHeapSize` to JDK 19+'s copy-constructor arithmetic | The platform bump silently invalidated the model — see *Key technical details* |

## Rejected outright

| Option | Rejected because | Revisit if |
|---|---|---|
| Record patterns, anywhere | The project bans `var`, so every component type must be named positionally. Of 189 record type-test sites, **5** have genuine nesting and all 5 exceed the 120-column limit (best case 165 cols) or bind components the code does not use. The best flat cases measure 133/123/136 cols | The `var` prohibition is relaxed, or a record with ≤2 short-typed components becomes a dispatch target |
| Delegate `CollectionUtils` to the JDK's `newHashMap`/`newHashSet` | Measured over n ∈ [1,100000]: the capacity *argument* differs at all 33,333 multiples of 3 but the allocated **table** differs at only **16** values (n = 3·2^k). The win is ~2000× narrower than the argument difference suggests, and it trades retained memory for an earlier resize. `.claude/rules/code-style.md` also mandates `CollectionUtils` as the project factory | A profile shows those 16 sizes dominating a real workload |
| Pattern `switch` in `core/query/**`, `index/**`, `evita_store/**` | A pattern switch links through an `invokedynamic` `SwitchBootstraps.typeSwitch` call site rather than a straight `instanceof` chain — not provably one-for-one in per-mutation and per-record paths | A benchmark shows the indy call site is free on these paths |
| `Math.clamp` as a general replacement for `Math.min(Math.max(...))` | `clamp` **throws** `IllegalArgumentException` when `min > max`; the nested form silently returns `max`. 1 of 15 sites adopted. Two were **refuted, not merely deferred**: `ColumnSizingTest:331` already asserts `trimmedLength(0, 3, 3) == 3` where `capacity < MIN_PHYSICAL_LENGTH`, so `clamp` would throw on an existing test; and `TransactionalBitmap:779`'s own javadoc documents `maxCount` capping below the filled count | Only per-site, where both bounds are constants or provably ordered |
| `list.reversed()` for `Collections.reverse(list)` | `Collections.reverse` **mutates in place**; `reversed()` returns a lazy view and mutates nothing. Both call sites use the mutated list afterwards | The caller is refactored to want a view |
| `getLast()` on the B+ tree cursor path | `Cursor#path` is declared `List<CursorLevel>`, so `getLast()` dispatches to `List`'s **default** method (`isEmpty()` + indexed get) rather than `ArrayList`'s override — an extra virtual call the current code does not make | The field is declared `ArrayList`, or the default is inlined away provably |
| `getFirst()` where `size() == 1` is enforced | A **naming** objection, not a risk one: `get(0)` under an enforced single-element invariant denotes *the sole element*, and `getFirst()` implies an ordering the code does not rely on. Six sites (`FinderVisitor`, `Prices`, `ConstraintResolver`, …) | Never — the invariant is the point |
| Sealing any further hierarchy to enable exhaustiveness | Changing extensibility is an API decision, not a modernization one. `ObjectOperationStep` was put to the maintainer and approved; nothing else was | Case by case, with the maintainer |

## Key technical details

**The platform bump silently invalidated a heap model.** JDK-8281631 (JDK 19) changed
`HashMap#putMapEntries` pre-sizing from `(int)(s / loadFactor + 1.0F)` to
`(int) Math.ceil(s / (double) loadFactor)`. `MapHeapSize#tableCapacityFor` replayed the old form.
Measured on both JDKs: `new HashMap<>(source)` with 12 entries allocates **32** slots on 17 and
**16** on 21; the two construction paths that the model existed to distinguish now agree at every
size at or above the 16-slot floor. The reconstruction is therefore **exact** from 7 entries upwards
rather than an upper bound. Below 7 the floor still over-reports deliberately.

The lesson generalises: **`MapHeapSize` is coupled to a specific JDK's internal arithmetic**, and the
coupling is invisible until a platform bump breaks it. That is stated at `tableCapacityFor`.

**Deleting a `default` is only legitimate when javac proves exhaustiveness.** Four trailing branches
that looked deletable were kept, because each threw for a *state* reason or did real work rather than
rejecting an unknown subtype — see `dec653b6f`'s message for the four.

**A pattern `switch` throws NPE on `null` regardless of exhaustiveness**, where the `if/else` chain it
replaces routed `null` to the trailing `else`. Every converted selector was checked; those that can
be null carry an explicit `case null ->`.

**`ArrayList.getLast()` is cheaper than `get(size() - 1)`, not merely equal.** `javap` on this JDK
shows the override compiles to `getfield size; isub 1; ifge; elementData(i)` and skips the
`Objects.checkIndex` call that `get(int)` makes. The allocation worry that usually attaches to
`SequencedCollection` is unfounded: `List`'s defaults are `isEmpty()` plus an indexed get, and
`List.of(...)`, `Arrays.asList(...)` and `Collections.unmodifiableList(...)` all inherit them
allocation-free. The real hazard is the **exception type** — `getFirst()`/`getLast()` throw
`NoSuchElementException` where `get(0)` throws `IndexOutOfBoundsException` — so every one of the nine
conversions is guarded by an `isEmpty()` check or an established non-empty invariant.

## Verification

**Full functional suite at the final commit: 23,743 tests, 0 failures, 2 errors** — both
environmental, neither related to this work:

* `ExportS3ServiceTest` — "Could not find a valid Docker environment"; the known Docker-less error.
* `EvitaGrpcTrafficRecordingExportIntegrationTest#shouldExportTrafficRecordingOverGrpcAndFetchTheResultingZip`
  — an Armeria `SessionProtocolNegotiationException` ("connection established, but session creation
  timed out") raised at `createReadWriteSession`, before any recording or serialization code runs.
  **Re-run in isolation: 2/2 pass in 9.2 s.** A load-sensitive TLS-handshake flake under 8-way
  parallelism, the same class as the `CollationKeyCacheSweeperTest` flake already known on `dev`.

Run with fixed parallelism 8 and a 12 GB fork heap; the default dynamic factor OOMs on this box.

Every step additionally ran the full reactor (`mvn -T1C clean install -DskipTests`, BUILD SUCCESS)
plus targeted tagged suites — 11,689 tests at step 2, 7,718 at step 4, 14,928 across three groups at
step 6.

**Three fixes were each proved by counterfactual, which is what makes their tests load-bearing:**

* `MapHeapSize` — reverting the formula alone reintroduced the two original failures **plus** a third
  from the rewritten test.
* Byte/Short coercing — reverting gave `Tests run: 22, Errors: 4`, all
  `ClassCastException: class java.lang.Byte cannot be cast to class java.lang.Integer`.
* Kryo unmodifiable — reverting gave `Tests run: 9, Errors: 6`, all `UnsupportedOperationException`
  inside the three `read` methods, while the **3 empty-case tests still passed** — which is precisely
  why the bug survived undetected.

The sealing was proved the same way: deleting one `case` arm makes javac reject the switch with
"the switch statement does not cover all possible input values".

## Consequences & open follow-ups

* **`Entity#mutate` silently drops an unrecognized `LocalMutation`** on the write path, with no
  trailing `else`. `LocalMutation` is `non-sealed`, so the compiler offers no guarantee the seven
  arms are all of them. Its sibling `InitialEntityBuilder#mutate` throws. Reported, not fixed —
  adding a throw is a behaviour change and the silence may be deliberate.
* **`ExtraResultsJsonSerializer#serialize` has the same shape.** Currently unreachable —
  `CacheableAttributeHistogram` and `CacheablePriceHistogram` are never constructed anywhere, which
  also makes those two classes look like dead code.
* **`InitialEntityBuilder` and the GraphQL `coercing` package are space-indented**, against
  `.claude/rules/code-style.md`. A whitespace-only fix, deliberately not mixed into a semantic diff.
* **91 binding-pattern sites remain**, a bounded low-risk remainder, mostly in the hot zones.
* **`QueryConverter` (59 arms) and `EvitaDataTypesConverter` (51 arms)** are genuine low-risk pattern
  switch candidates deferred only because converting both is ~800 lines of mechanical diff. Good
  standalone follow-ups, one file per commit.
* **`new URL(...)` — 5 sites, 2 in production** (`ClientCertificateManager`), deprecated since JDK 20.
  Declined: `URI.create(...).toURL()` swaps a declared `IOException` for an unchecked
  `IllegalArgumentException` and parses more strictly — a behaviour change, not a rename.
* **`new Locale(...)` — 130 sites**, deprecated since JDK 19, all test and benchmark code. Declined
  as a 52-file mechanical sweep with no clarity gain.
* **`StringBuilder.repeat` (~20 sites)** declined: an allocation win rather than a legibility one, so
  its motivation falls outside a pass whose test is "materially clearer".
* **Three records declare non-trivial accessors** (`ObjectDescriptor#name()` throws unless the name is
  static; `EndpointDescriptor#operation()` builds a string; `CatalogWrapper#catalog()` is an
  `AtomicReference#get()`). None is dispatched on today, but a record pattern invokes every accessor
  it binds — a hazard if record patterns are ever adopted.

## Related work

The JDK 17 -> 21 platform bump itself (#1518, PR #1519) deliberately has no record: per
`.claude/rules/adr.md` a platform bump is not one, and its two pieces of durable reasoning live at
their sites (the `toolchains` activation-range invariant in `pom.xml`, the `ExecutorService#close`
trap in `ProgressingFutureTest.tearDown()`).

## Timeline

* **2026-09-08** — modernization pass executed as seven steps; record accepted.
