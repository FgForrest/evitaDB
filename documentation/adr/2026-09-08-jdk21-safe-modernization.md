---
title: JDK 21 modernization is adopted only where it is provably behaviour-preserving
date: 2026-09-08
updated: 2026-09-10 09:50
status: accepted
kind: refactor
issues: [1518]
prs: [1519]
areas: [pom.xml, evita_common/dataType, evita_common/utils, evita_query/api/query/expression, evita_engine/core/expression, evita_engine/index/map, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_grpc, evita_external_api/evita_external_api_grpc/client, evita_external_api/evita_external_api_rest, evita_store/evita_store_server]
supersedes: []
superseded-by: []
relates: [2026-09-10-jdk21-virtual-threads-and-scoped-values, 2026-09-10-simd-vector-api-feasibility]
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

A day later the pass was **partially reverted**: the Java driver has to keep compiling and running on
JDK 17, which pins the language and API level of every module it is built from. The decision, the
modules it covers and what was taken back are in their own rows and paragraphs below.

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
| Seal the **`LocalMutation`** hierarchy, 16 leaves `final` | Approved as a second deliberate **breaking API change**. Closes `Entity#mutate`'s silent drop: 10 sealed types over 4 levels with **six** diamond members, nothing outside `evita_api` ever implemented it |
| Keep the **Java driver on the JDK 17 language and API level** | `evita_java_driver`, `_observability` and `_all_in_one` are consumed by client applications that move JDKs on their own schedule. The six modules the driver is built from compile with javac's `release` 17 (`java.release` in the root POM), so the pass was taken back there: **14 pattern switches in 8 files, four `getFirst()` sites and one `threadId()`**. The two sealings and every binding pattern (JDK 16/17 syntax) stay |
| Guard that floor **three ways**, each catching what the others cannot | javac's `release` 17 catches the driver's *own* sources; the enforcer's `enforceBytecodeVersion` rule (managed in the root POM, bound in `client` and `client_observability`) catches a *dependency* bump shipping JDK 18+ classes, which javac never sees; and the `driver-jdk17` CI job (`tools/verify-driver-on-jdk.sh`) runs the shaded driver on a real JDK 17 JVM against a JDK 21 server, which catches what no static check can — the Proxycian defect below was found exactly that way |

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
| Sealing a hierarchy on an agent's own initiative | Changing extensibility is an API decision, not a modernization one. Two hierarchies were put to the maintainer and approved (`ObjectOperationStep`, `LocalMutation`); none was sealed without that | Case by case, with the maintainer — never unilaterally |
| Require JDK 21 of driver consumers | The driver is the product's integration surface. A driver that silently needs a newer JVM fails in the consumer's classloader (`UnsupportedClassVersionError`), not in this build, and client applications upgrade on their own schedule | The driver's JDK floor is raised deliberately, as a release-note item |
| Split `evita_common` / `evita_query` / `evita_api` into driver-safe and server-only halves so the server halves could modernize | The three modules are the shared data model and query language; the split is a wide refactor plus a module-boundary redesign, bought for a purely stylistic gain (pattern switches over `if` chains) | The server needs a JDK 18+ *API* in one of them that cannot live in `evita_engine` |
| Compile the driver modules with a real JDK 17 toolchain instead of javac 21 with `release` 17 | Needs a second JDK on every developer machine and in CI. The `release` flag compiles against the JDK 17 API signatures (`ct.sym`), which is the same public-API guarantee, and it holds on a machine with only JDK 21 installed | A compiler-behaviour difference, not an API difference, is found to matter |
| Multi-release driver jar carrying a JDK 21 overlay of the modernized classes | Two builds of the same modules and two copies of the same logic, for no runtime difference at all | Never — there is nothing to overlay |

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

**Two properties, one number each.** `java.version` (21) is the JDK the build runs on and the toolchain
it selects; `java.release` is what javac's `release` flag enforces and defaults to `java.version`. Six
POMs pin `java.release` to `${java.driver.release}` (17): `evita_common`, `evita_query`, `evita_api`,
`evita_external_api_grpc_shared`, `evita_java_driver` and `evita_java_driver_observability`
(`evita_java_driver_all_in_one` has no sources; it shades the driver). Anything JDK 18+ in those modules
fails the build — `cannot find symbol` or "not supported in -source 17" — on every machine, with no JDK 17
installed. `ChangeCaptureConverter#toGrpcHostSystemEvent` had carried a comment saying exactly this; the
pass removed it, which is how the constraint got lost.

**The driver's dependencies are not guarded by javac.** A dependency bump that ships JDK 18+ bytecode
would pass compilation and fail the consumer, so `maven-enforcer-plugin` with `extra-enforcer-rules`'
`enforceBytecodeVersion` (`maxJdkVersion` = `${java.driver.release}`, test scope ignored) runs at
`validate` in the two driver modules. It understands multi-release jars: the `META-INF/versions/21`
overlay in `jackson-core` does not trip it, a base-level class above 61 does.

**The CI smoke is the only check on a real JDK 17 JVM.** `tools/verify-driver-on-jdk.sh` starts
`evita-server.jar` on one JDK, compiles `tools/driver-smoke/DriverSmoke.java` with the other JDK's
`javac` against the shaded all-in-one jar alone, and runs it there; the smoke refuses to run on any
feature version other than the expected one, so it cannot silently pass on the build JDK. The
`driver-jdk17` job in `ci-dev.yml` and `pr-review.yml` runs it on the artifacts the build job uploads.
`javac` 17 must be given `-encoding UTF-8` — UTF-8 became the default only in JDK 18.

**The JDK 17 runtime probe found a fourth latent defect, unrelated to the JDK version.**
`References.DUPLICATE_REFERENCE` — the identity marker for duplicate references, in a static
initializer that runs on the first entity any client reads — was generated with Proxycian/Byte Buddy,
while `proxycian_bytebuddy` is an *optional* dependency of `evita_api` (needed only by the
custom-contract proxies). Neither the plain driver's transitive classpath nor the shaded all-in-one jar
carries it, so every entity read failed with `NoClassDefFoundError` since `4874816d7` (2025-09-02); on
the server the same initializer needed `--add-opens java.base/java.lang` for Byte Buddy's reflective
class injection, which `run-server.sh` passes and the Docker entrypoint does not. The marker is only
ever compared with `==`, so it is now a `java.lang.reflect.Proxy` — no dependency, no opens, identical
identity and `toString` semantics. The reason is stated on `createThrowingStub`.

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

The `LocalMutation` sealing, which lands in `evita_api` and therefore recompiles every downstream
module, added a further **26,240 tests across four tag groups, 0 failures** (the only error being the
same Docker-less `ExportS3ServiceTest`).

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

**The driver's JDK 17 floor (2026-09-09):**

* The six pinned modules compile with `javac [forked debug release 17 module-path]` — 2,192 source
  files — and every emitted class is major version 61 (spot-checked `ReflectionLookup`, `Entity`,
  `EvitaClient` and the `shared` module's `module-info`; a JDK 21 module emits 65 for contrast).
  The full reactor (`mvn -T1C clean install -DskipTests`, 30 modules) then built green.
* A class-file scan of `evita_java_driver_all_in_one` (3,956 evitaDB classes at 17, 16,302 shaded
  third-party classes at Java 5–8) and of all 80 jars on the plain driver's runtime classpath found no
  base-level class above 61; the only newer bytecode sits in multi-release overlays
  (`META-INF/versions/9` through `24`), of which a JDK 17 runtime loads nothing beyond 17.
* A throwaway probe compiled with **javac 17.0.20** and run on **OpenJDK 17.0.20** against a server on
  OpenJDK 21.0.12 created a catalog, defined a schema, upserted two entities, went live, read one back by
  primary key and one by an attribute query, and dropped the catalog — on the plain driver classpath and
  on the shaded jar. The same probe on JDK 21 passes as well.
* The probe is also what proved the Proxycian defect: before the `References` fix it failed on JDK 17
  **and** JDK 21 with `ClassNotFoundException: one.edee.oss.proxycian.DispatcherInvocationHandler`
  from `EntityConverter#toEntity`, on both classpaths. After the fix the server side runs from a plain
  `java -jar evita-server.jar` with no `--add-opens` and no agent. Regression check: the nine
  duplicate-reference builder/structure test classes (297 tests, 0 failures) and
  `EvitaClientReadOnlyTest` + `EvitaClientReadWriteTest` + `EntityByDuplicateReferencesFunctionalTest`
  (180 tests, 0 failures, 1 skipped) pass against the installed `evita_api`.
* **Enforcer, proved by counterfactual:** `mvn validate` on `client` and `client_observability` passes
  at the 17 floor; with `-Djava.driver.release=8` it fails naming exactly the four evitaDB jars
  (`evita_common`, `evita_query`, `evita_api`, `evita_external_api_grpc_shared`) — which also shows every
  third-party jar in compile and runtime scope is Java 8 bytecode or older.
* **Smoke script, proved by counterfactual:** `tools/verify-driver-on-jdk.sh` with a JDK 17 client and a
  JDK 21 server prints `SMOKE OK on Java 17` and leaves no server behind; run with a JDK 21 client while
  expecting 17 it fails with "expected to run on Java 17 but this JVM is Java 21".

## Consequences & open follow-ups

* **`Entity#mutate`'s silent drop is FIXED** by the sealing above, with a trailing throw rather than
  an exhaustive `switch`. Compile-time exhaustiveness was available and verified — javac accepts the
  seven arms with no `default` and rejects them when one is removed — but taking it would have
  changed the dispatch mechanism on the write path, so the proof was moved somewhere free instead:
  `LocalMutationDispatchCoverageTest#dispatchBranchOf` repeats the arms as a default-less pattern
  switch, so a new permitted subtype the production chain would drop fails to compile the test module.
* **`ExtraResultsJsonSerializer#serialize` has the same shape.** Currently unreachable —
  `CacheableAttributeHistogram` and `CacheablePriceHistogram` are never constructed anywhere, which
  also makes those two classes look like dead code.
* **`InitialEntityBuilder`, `ExistingEntityBuilder` and the GraphQL `coercing` package are
  space-indented**, against `.claude/rules/code-style.md`. A whitespace-only fix, deliberately not
  mixed into a semantic diff.
* **91 binding-pattern sites remain**, a bounded low-risk remainder, mostly in the hot zones.
* **`QueryConverter` (59 arms) and `EvitaDataTypesConverter` (51 arms)** were low-risk pattern switch
  candidates, but both live in the gRPC `shared` module, which the driver is built from and which
  therefore stays at JDK 17. **Not candidates** until the driver's JDK floor moves.
* **Driver-reachable modules cannot use JDK 18+ language features or APIs.** The build enforces it;
  the module list and the reason are in the `java.release` comment of the root POM. A modernization
  pass over `evita_common`, `evita_query`, `evita_api` or the gRPC `shared` module is limited to what
  JDK 17 offers — binding patterns, sealed types, records, text blocks.
* **The Docker entrypoint passes no `--add-opens` flags** while `run-server.sh` does. The `References`
  fix removed the one initializer known to need them for a plain entity read; whether anything else
  still does (custom-contract proxies, Kryo) has not been checked.
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

### Reported as defects and refuted on inspection

Recorded so they are not re-raised. Both are `if / else if` chains with no trailing branch, which
looks like the `Entity#mutate` defect and is not:

* **`EvitaSessionService` leaving the `DataChunk` oneof unset for `PlainChunk`** is **correct**.
  `PlainChunk` carries no paging metadata at all — no offset, limit, page number or page size — so
  there is nothing to set and an unset protobuf oneof is the right encoding of "no paging".
* **`ChangeCaptureConverter#toGrpcChangeCaptureCriteria`** is **correct**. `CaptureSite` is
  `sealed ... permits SchemaSite, DataSite` and both are covered; the only fall-through is
  `site() == null`, which is `@Nullable` and means "no site filter".

### A survey lesson worth keeping

Three of the six `SchemaEvolvingLocalMutation` diamond members were missed by the initial survey
because `implements ...` sat on a **continuation line**, invisible to a single-line `rg` pattern.
Any future closure survey over this codebase must read declarations across line breaks rather than
grepping one line at a time.

## Related work

The JDK 17 -> 21 platform bump itself (#1518, PR #1519) deliberately has no record: per
`.claude/rules/adr.md` a platform bump is not one, and its two pieces of durable reasoning live at
their sites (the `toolchains` activation-range invariant in `pom.xml`, the `ExecutorService#close`
trap in `ProgressingFutureTest.tearDown()`).
- `2026-09-10-jdk21-virtual-threads-and-scoped-values` — the concurrency-feature question asked right after
  this bump; declined for the same preview-pin reason this record applies to the driver.
- `2026-09-10-simd-vector-api-feasibility` — the Vector API question; pursued because incubator modules do
  not pin class files, and designed so the driver floor recorded here is untouched.

## Timeline

* **2026-09-08** — modernization pass executed as seven steps; record accepted.
* **2026-09-09** — driver-reachable modules pinned to the JDK 17 language level; the pass reverted in
  eight files; `References.DUPLICATE_REFERENCE` moved off Proxycian; enforcer bytecode rule and the
  `driver-jdk17` CI smoke added; record updated.
