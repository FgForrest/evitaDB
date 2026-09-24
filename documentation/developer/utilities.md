# Shared Utilities (`io.evitadb.utils`)

`evita_common` carries thirty small utility classes that every other module depends on. They exist so the same
problem is not solved thirty different ways, and most of them wrap a subtlety that is easy to get wrong by hand
— a JDK constructor whose parameter does not mean what it looks like, a close that must not mask the original
exception, a heap size that depends on the running VM rather than on a constant.

**Check this list before writing a helper.** The cost of not knowing is not the twenty lines you rewrite; it is
that your version and the shared one drift, and a reader has to work out whether the difference is deliberate.

Every class here is `evita_common`, so it is available from every module including tests.

---

## Collections and arrays

| Class | Reach for it when |
|---|---|
| `CollectionUtils` | **Creating any sized map or set.** `createHashMap(n)` / `createLinkedHashMap(n)` / `createHashSet(n)` / `createLinkedHashSet(n)` / `createConcurrentHashMap(n)` take an *expected element count*; the JDK constructors take a *bucket capacity*, so `new HashMap<>(64)` rehashes past 48 entries. Also `toUnmodifiableMap` / `toUnmodifiableSet` for DTO fields, and `combine(Set, Set)`. |
| `ArrayUtils` | Any primitive-array work — `binarySearch` (including `binarySearchWithDuplicates`), `computeInsertPositionOf{Int,Long,Obj}InOrderedArray`, `insert*IntoOrderedArray`, `contains`, `indexOf`, `copyOf`, and the sort/reorder helpers the indexes are built on. Sorted-array insertion in particular is written once here and is easy to get subtly wrong. |
| `MapBuilder` / `ListBuilder` | Building a literal map or list that needs **insertion order** or more entries than `Map.of()` supports. `map()` and `list()` / `array()` are the entry points; the two are directly compatible so nested structures compose. |
| `CollectorUtils` | A `Collector` the JDK does not ship — currently `toUnmodifiableLinkedHashSet()`, for when a collected set must keep encounter order. |
| `ComparatorUtils` | Comparing `Locale` or `Currency`, which have no natural order — `compareLocale`, `compareCurrency`, and the ready-made `localeComparator` / `currencyComparator`. |
| `Iterators` | `concat(...)` — iterating several sources as one without materializing them. |

## Assertions and errors

| Class | Reach for it when |
|---|---|
| `Assert` | Stating a precondition. `isTrue` for input the caller could plausibly get wrong, **`isPremiseValid` for a condition that is a programming error if false** — the distinction decides which exception the user sees, so pick deliberately. `notNull` for the null case. |
| `ExceptionUtils` | Digging through a wrapped exception — `getRootCause`, `findInCauseChain`, `causeChainContains`, and `unwrapCompletionException` / `unwrapCompletionWrappers` for anything that came back from a `CompletableFuture`. |

## Strings, naming and classifiers

| Class | Reach for it when |
|---|---|
| `StringUtils` | Case conversion (`toCamelCase`, `toKebabCase`, `toPascalCase`, `toSnakeCase`), `removeDiacritics`, padding, and the **human-readable formatters** used across logs and the console: `formatByteSize`, `formatCount`, `formatDuration`, `formatNano`, `formatRequestsPerSec`. Reach for these rather than inventing another byte/duration format. |
| `NamingConvention` | The enum of supported name cases. `NamingConvention.generate(name)` returns all variants of a name as a `Map<NamingConvention, String>` — reach for it when a name must be produced in several conventions at once, as schemas do. |
| `ClassifierUtils` | Validating an entity type, attribute name or reference name — `validateClassifierFormat` and the reserved-keyword list. Any user-supplied identifier goes through here. |
| `PrettyPrintable` | The interface to implement when a type should render itself for human consumption. |

## Numbers, bits and identifiers

| Class | Reach for it when |
|---|---|
| `NumberUtils` | Converting between numeric types (`convertToInt`, `convertToBigDecimal`, `convertToNumericType`), **normalizing a `BigDecimal` for indexing** (`normalizeForIndexing`), and packing two `int`s into a `long` (`pack` / `unpackLow` / `unpackHigh`) — the idiom the indexes use for composite keys. |
| `BitUtils` | Flag bits in a byte or int — `setBit`, `isBitSet`, `copyBitSetFrom`. |
| `Crc32CWrapper` | Checksums on the storage path. Wraps `CRC32C` with **allocation-free** `combineInt` / `combineLong` / `combineByte` overloads; the plain JDK API forces a `byte[]`. |
| `RandomUtils` | Getting a random source. `getRandom()` for normal use, **`getFrozenRandom()` for reproducibility** in tests and generators. |
| `UUIDUtil` | Generating UUIDs — backed by `ThreadLocalRandom`, so cheaper than `UUID.randomUUID()` on hot paths. |

## Reflection and classes

| Class | Reach for it when |
|---|---|
| `ReflectionLookup` | **Any repeated reflection.** It caches annotated fields, methods and getter/setter resolution; raw `Class#getMethods` in a loop is the thing it exists to replace. |
| `ClassUtils` | Cheap class predicates — `isAbstract`, `isFinal`, `isAbstractOrDefault` — and `whenPresentOnClasspath` for optional integrations. |

## Files and streams

Read `.claude/rules/module-boundaries.md` first: these are the primitives the storage-boundary rule is written
in terms of. Calling them from `evita_store` is the intended use; calling them from `evita_engine` to reach
catalog files is the violation that rule exists to catch.

| Class | Reach for it when |
|---|---|
| `IOUtils` | Closing anything. **`closeSafely` / `closeQuietly` / `close` differ in what they do with a failure** — whether it propagates, is swallowed, or is collected and suppressed onto a primary exception. Picking the wrong one is how an original cause gets lost. Also `copy` and `executeSafely`. |
| `FileUtils` | Directory and file manipulation — `deleteDirectory`, `listDirectories`, `getDirectorySize`, `getFileNameWithoutExtension`, `renameOrReplaceFile`, `compressDirectory`, and **`rewriteTargetFileAtomically`** for a write that must not be observed half-done. |
| `FolderLock` | Enforcing exclusive access to a folder across processes. |

## Memory and VM layout

| Class | Reach for it when |
|---|---|
| `VMLayout` | Estimating an object's heap footprint. `VMLayout.current()` reports the **running** VM's layout — header size, reference width, alignment — so estimates track compressed-oops on/off instead of assuming. Heap-size tests compare against JOL, so a hand-rolled constant will disagree with reality. |
| `MemoryMeasuringConstants` | The per-type byte sizes those estimates are built from, derived from `VMLayout` rather than hardcoded. |

See [heap-size-testing.md](heap-size-testing.md) for how footprint estimates are written and verified.

## Infrastructure

| Class | Reach for it when |
|---|---|
| `ConsoleWriter` | Coloured console output from tools and benchmarks. |
| `NetworkUtils` | Host, address and SSL-context work. Note that this and `VersionUtils` are the **only** two classes in the core modules permitted to touch `java.net`. |
| `VersionUtils` | Reading the evitaDB version out of `MANIFEST.MF`, and comparing versions. |
| `CertificateUtils` | Certificate constants and helpers shared by client and server. |
| `Functions` | Reusable functional constants — `alwaysTrue` / `alwaysFalse` and their `int` variants — instead of allocating an identical lambda per call site. |

---

## Related rules

- `.claude/rules/code-style.md` — the collection-sizing rule, the `Optional` restriction, and the
  allocation rules for performance-critical code.
- `.claude/rules/module-boundaries.md` — which module may call the file utilities above, and why.
