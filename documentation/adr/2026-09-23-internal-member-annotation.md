---
title: Mark members that are public only for cross-module reach with @Internal, and enforce it from bytecode
date: 2026-09-23
updated: 2026-09-23 19:10
status: accepted
kind: infrastructure
issues: [1640]
prs: []
areas:
  - evita_common/src/main/java/io/evitadb/annotation
  - evita_query/src/main/java/io/evitadb/api/query/require
  - evita_api/src/main/java/io/evitadb/api/requestResponse
  - evita_api/src/main/java/io/evitadb/api/SessionTraits.java
  - evita_engine/src/main/java/io/evitadb/core/query
  - evita_test/evita_functional_tests
supersedes: []
superseded-by: []
relates: [2026-09-23-reference-decode-narrowing-by-referenced-key]
---

# `@Internal` marks what JPMS cannot: a member that is public only so a sibling module can reach it

`io.evitadb.annotation.Internal` is a new annotation in `evita_common`. It marks a member whose
contract may change without notice and without a deprecation cycle, and it carries a **required**
string naming the supported alternative. Nine members carry it today — the named reference content
feature and two session flags. `InternalApiUsageTest` reads the compiled class files of every module
and fails the build when a module outside a five-entry allowlist references one.

## Why

Some members are `public` only because Java has no narrower visibility that reaches the sibling
module that needs them. Nothing told a reader — or a user with evitaDB on their classpath — that
the contract may change without notice.

The worked example is **named reference content**. A `referenceContent` requirement may carry an
instance name; the GraphQL layer puts a field alias there and reads the resulting chunk back by that
name. Nothing else can build one: the EvitaQL grammar has no instance-name form, and
`QueryConstraints` exposes no factory for it, so the only way in is to call one constructor
directly. That constructor has said so in prose since #902 —

> `ReferenceContent.java` — *"Internal constructor used in GraphQL API to define multiple reference
> content definitions and for cloning purposes."*

— in the one place nobody looks from the outside. Nothing surfaced it in javadoc, nothing stopped a
call from a new module, and nothing told a reviewer reading `isReferenceRequestedOnlyAsNamed` that
the method is not a contract. Under #1637 the behaviour of the unnamed reference view changed, and
the question *"is this a public contract or an internal detail?"* cost a full adversarial review
round to answer. An annotation would have answered it in a line.

### Previous state

evitaDB already uses JPMS and already uses **qualified exports** — twenty-two of them: eighteen in
the REST module (to the lab), three in the GraphQL module (to the test support module) and one in the
server module (to Logback). `evita_api` and `evita_engine` carry none; what they have is `opens … to`
for reflection (two and three respectively), which is a different directive and grants no
compile-time access. Where a whole *package* is internal, the qualified export is a
compiler-enforced answer that exists today and is strictly stronger than any annotation. What it
cannot express is **member granularity**: a package genuinely exported to everyone that holds one
constructor, one accessor or one nested type the engine reaches across a module boundary. That gap,
and only that gap, is what this annotation is for.

## Options considered

### Option A — a member-level annotation carrying a required alternative (chosen)

`@Internal("use getReferenceEntityFetch() for the requirements a query states")`, retained in the
class file, `@Documented` so it reaches generated javadoc.

- **Pros:** works at the granularity the problem actually has; the required `value()` redirects the
  reader instead of only forbidding; visible both in the source and in published javadoc; a class
  file retention is enough for a bytecode check with no runtime cost and nothing to reflect over.
- **Cons:** weaker than a compiler-enforced export — it is a convention plus a check, and both can
  be edited by whoever wants past them.

### Option B — qualified exports and package moves only (declined)

Move every internal member into a package exported only to the modules that need it.

- **Pros:** compiler-enforced, no new vocabulary, no check to maintain.
- **Cons:** the members in question sit in packages real users depend on — `io.evitadb.api.query.require`
  holds the whole constraint DSL, `io.evitadb.api.requestResponse` holds `EvitaRequest`. Qualifying
  either would break every user of evitaDB.
- **Rejected because:** JPMS is package-granular and this problem is member-granular; splitting the
  constraint DSL across two packages to make the export qualifiable would cost every user an import
  change to hide one constructor. **This stays the first choice wherever a whole package is
  internal** — the annotation is for what is left over, not a replacement.

### Option C — a bare marker, or javadoc prose alone (declined)

`@Internal` with no attributes, or simply a stronger sentence in the javadoc.

- **Pros:** nothing to fill in; no risk of a stale alternative string.
- **Cons:** "don't" without "do this instead" leaves the reader where they started, and prose is
  exactly what already existed and failed.
- **Rejected because:** the constructor's prose warning had been in place since #902 and still cost
  a review round; an unchecked, non-actionable marker would have repeated that outcome with extra
  ceremony. Required `value()` also forces the author to *know* the alternative, which is a useful
  filter on applying the annotation at all.

## Enforcement — options considered

An unchecked marker is a comment with extra ceremony, so the issue was not done until the build
checked it.

### Option E1 — a repository-walking test that reads class files (chosen)

`InternalApiUsageTest` walks every module's `target/classes`, parses the class files directly,
collects the members carrying the annotation, and scans every other module's constant pool for a
reference to one.

- **Pros:** exact — a constant pool records owner, member name *and* descriptor, so an overload that
  is not marked stays unaffected by one that is; runs in the ordinary `mvn test` with no CI wiring;
  follows the existing `FormulaClassIdUniquenessTest` precedent for a repository-wide invariant that
  fails the build; no new dependency.
- **Cons:** needs the whole reactor compiled before it runs, and carries ~150 lines of class-file
  parsing the project now owns.

### Option E2 — a `tools/*.sh` script over `javap` (declined)

The shape `tools/audit-deprecated-since.sh` and `tools/lint-proto.sh` (wired into `ci-dev.yml`) use.

- **Pros:** matches the existing tools directory; no Java to maintain.
- **Cons:** `javap -v` prints a member's annotations in an indented block under the member's
  declaration line, so attaching an annotation to the right member — and recovering its descriptor
  — means an awk state machine over presentation output.
- **Rejected because:** the check has to be descriptor-precise (see Verification: `ReferenceContentSerializer`
  calls three `ReferenceContent` constructors and only one is marked), and reconstructing a descriptor
  from javap's rendered Java signature is strictly harder and more fragile than reading the class
  file that javap is itself reading. It would also be a separate CI step rather than part of the
  build.

### Option E3 — an `extra-enforcer-rules` bytecode rule (declined)

The root POM already configures `extra-enforcer-rules` 1.12.0 for `enforceBytecodeVersion` on the
driver modules.

- **Pros:** already on the classpath; fails the build in the module that offends, naming it precisely.
- **Rejected because:** no rule in that set inspects *member references*. `enforceBytecodeVersion`
  reads class-file versions, `banDuplicateClasses` reads names; none walks a constant pool against a
  set of annotated members. Using it would mean writing a custom enforcer rule — a new Maven plugin
  module built before the reactor, which is more machinery than the test for the same answer.
  Revisit if evitaDB ever grows a build-tooling module for other reasons.

### Option E4 — a source-level scan (declined)

Grep the sources for `@Internal`, then grep every other module for the member's name.

- **Pros:** no build required, works from a clean checkout, and it is what
  `FormulaClassIdUniquenessTest` does for its own invariant.
- **Rejected because:** a bare member name is ambiguous. `getInstanceName` exists on more than one
  type, `getReferenceChunkTransformer` exists on three, and a constructor cannot be told from its
  own unmarked overloads at all. The result would be a check that cries wolf until someone turns it
  off. (Sources are the right medium for `CLASS_ID` because there the invariant *is* a source
  convention — a literal in a declaration — with no reference side to resolve.)

### Option E5 — ArchUnit (declined)

- **Pros:** purpose-built for exactly this, member-level, readable rules.
- **Rejected because:** a new test dependency for one rule, against `CLAUDE.md`'s standing
  preference for the libraries already in use. Revisit if a second or third architectural rule
  wants enforcing — at that point the dependency starts paying for itself.

## Decision

**Chosen: Option A, enforced by Option E1.** A qualified export remains the first choice and the
annotation's javadoc says so; `@Internal` exists only for the member-granular remainder. Enforcement
reads bytecode because the check must be descriptor-precise, and it lives in a test because that is
where this repository already puts build-failing invariants — and because it then needs no separate
CI step to be part of the build.

The other options win under changed conditions that are worth naming: **E3** if evitaDB grows a
build-tooling module for other reasons, and **E5** once a second architectural rule wants enforcing.
**B** wins outright for any *package* that is internal, and the rule stays "qualify-export it or
move it, and write no annotation".

## Key technical details

- `evita_common/src/main/java/io/evitadb/annotation/Internal.java` — `@Target({TYPE, METHOD, FIELD,
  CONSTRUCTOR})`, `@Retention(CLASS)`, `@Documented`, required `String value()`. The package is
  exported unqualified from `evita_common`, so every module can apply it.
- **The scoping rule is load-bearing**: `@Internal` marks a member whose contract may change without
  notice and without a deprecation cycle. Not "low-level", not "awkward to call". Without that line
  it spreads to everything `public` in `evita_engine` and stops carrying information. It is also the
  exact opposite promise to `@Deprecated`, which guarantees a notice period.
- `evita_test/evita_functional_tests/.../io/evitadb/annotation/InternalApiUsageTest.java` — the
  check. Two rules decide a reference: **the declaring module may always reach its own members**
  (the annotation governs cross-module reach), and every other module must be in
  `MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS`, whose entries each carry their reason.
- **The allowlist is module-level, not per-member.** A per-member allowlist would be more precise
  and is the honest way to mark the `_internalBuild` family (see below), but it needs an annotation
  attribute and a policy for who may edit it. The module-level list buys the property that matters
  today: the published Java driver (`.../grpc/client`) is *not* on it, so nothing internal is
  reachable from the module users actually put on their classpath.
- `target/test-classes` is deliberately not scanned — tests are not a published surface, and
  exercising an internal member is what many of them are for.
- **The scan re-reads a class file once before believing it is broken.** CI builds with `mvn -T 1C`,
  and `evita_java_driver_observability`, `evita_test/evita_performance_tests` and the shaded
  all-in-one driver have *no dependents at all*, so they can still be compiling while the functional
  tests run. A class file javac is mid-way through writing is transient; one that fails to parse
  twice is genuinely broken, and the test then names it instead of reporting a clean scan.
- The class-file reader interprets only the structures that carry a reference or an annotation and
  skips everything else by its declared length. A constant pool `CONSTANT_Class` entry stands for a
  type reference; `CONSTANT_Fieldref` / `CONSTANT_Methodref` / `CONSTANT_InterfaceMethodref` stand
  for a member reference. Lambdas and method references are covered for free, because the target of
  an `invokedynamic` is still a `Methodref` in the pool.

## Verification

`InternalApiUsageTest#shouldNotReachInternalMembersFromDisallowedModules` passes on a full reactor
build (24 module output directories, 9 annotated members, 5 allowed type references and 12 allowed
member references, 0 violations). Four counterfactuals prove it is not passing vacuously:

- **The rule fires.** Emptying `MODULES_ALLOWED_TO_REACH_INTERNAL_MEMBERS` fails the test with all
  17 cross-module references listed by module, class, member and alternative. That run is also what
  established the allowlist: it is exactly the modules that reach one, no more.
- **It is descriptor-precise.** `ReferenceContentSerializer` (`evita_store_server`) calls three
  `ReferenceContent` constructors at lines 111, 115 and 126; only the marked five-argument one at
  126 is reported. An overload-blind check would have reported all three.
- **The positive control detects a blind scan.** Removing `CONSTANT_METHODREF` from the constant
  pool walk drops allowed member references from 12 to 0 while type references stay at 5, and the
  test fails on the member-reference floor — which is why the two kinds are counted apart rather
  than summed.
- **A broken class file is not swallowed by the torn-file retry.** Truncating
  `evita_roaring_bitmap`'s `AppendableStorage.class` to 120 bytes fails the test with that file's
  path, so the retry that absorbs a concurrent compilation does not also absorb real damage.

Three further floors fail loudly rather than silently passing: fewer than 15 compiled modules found
(wrong root, or the reactor was not built), fewer than 4 annotated members found (the scan is not
reading the current build), and the two reference-kind floors above.

The annotations themselves change no behaviour, and the functional slice covering the areas they sit
in (`(require | reference | session | serialization | contract) & !slow`) confirms it: **12,750
tests, 0 failures, 8 skipped**. The single error is `ExportS3ServiceTest`, which needs a Docker
socket Testcontainers could not find on the machine that ran it and touches none of the changed
types.

## Consequences & open follow-ups

The first application is deliberately narrow — the seven named reference content members from the
issue plus two session flags. A `evita_api` survey (every javadoc block carrying an internal-use
marker, paired with the declaration that follows it: 80 raw hits, most of them prose about "client
code" in unrelated exception classes) found four families that were examined and **not** marked:

1. **`getReferenceChunkTransformer()` — the warning is on the wrong overload.**
   `References#getReferenceChunkTransformer()` carries the clearest statement in the survey ("part
   of the internal API and is not meant to be used by the client code"), but every cross-module
   caller reaches it through `Entity#getReferenceChunkTransformer()`, whose javadoc carries no
   warning at all. Marking only `References#` would produce a green check over the surface nobody
   calls; marking `Entity#` is a decision about the API rather than a transfer of an existing
   warning. Needs `.../grpc/shared` and `evita_store/evita_store_entity` allowlisted.
2. **The `_internalBuild` family — about 100 members across 26 classes**, uniformly documented "Do
   not use this method from in the client code!". Deferred because the allowlist would have to take
   the published Java driver and `evita_common`; once the driver is in, every later `@Internal`
   member is reachable from it without review, which is the one property the allowlist buys. A
   per-member allowlist is the honest way to sweep this family. The `_` prefix already signals the
   same thing locally, which makes it the least urgent despite being the largest.
3. **`CommitProgressRecord`** — its javadoc already draws the line ("**Public API**
   (`CommitProgress`) … **Internal API** (this class)") and the type never appears in a public
   signature, but `EvitaClientSession` constructs it in three places. Same driver-in-the-allowlist
   objection as (2).
4. **`DevelopmentConstants`** — "meant only for internal development purposes", reached by five
   modules. Same objection, and it arguably belongs in a test-support module rather than
   `evita_api`.

Three candidates were examined and rejected *against the scoping rule*, which is worth recording so
they are not re-proposed: `Reference#isAttributeValuePresentAndExists` (its javadoc explains why the
method is declared rather than that it is unsupported), the `ReferenceAttributes` constructors (no
cross-module caller at all — they are public for the tests, which an annotation does not describe),
and `InvalidMutationException(String, String)` (its "internal" refers to the log message, not the
API).

## Related work

- `2026-09-23-reference-decode-narrowing-by-referenced-key` — the #1637 work whose review round
  turned on whether the named reference members were a public contract; that question is what this
  record's annotation exists to answer in a line.

## Timeline

- **2026-09-23** — issue #1640 filed, annotation, check and first application implemented
