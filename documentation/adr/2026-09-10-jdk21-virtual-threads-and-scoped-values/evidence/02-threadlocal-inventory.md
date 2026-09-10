# 02 — ThreadLocal inventory and classification

Scope: every `ThreadLocal` (and ThreadLocal-like per-thread state) in the evitaDB main code, plus the
framework per-thread state evitaDB leans on, classified for two decisions: (1) which bindings can become a
JDK `ScopedValue`, (2) which per-thread caches change their economics when a request runs on a virtual
thread instead of a pooled platform thread. Analysis only; every claim cites `file:line` of the current
worktree (`1518-upgrade-to-jdk-21`, HEAD `16e782c63`). Nothing was built or run; jar internals were read
with `javap` from the local Maven repository.

Categories: **A** execution context (a value the callee looks up), **B** per-thread performance scratch,
**C** mutable per-thread state (guards, counters, stacks the callee mutates), **D** framework-owned.

---

## 1. Executive summary

1. **21 ThreadLocals exist in main code** (7 `evita_engine`, 2 `evita_common`, 1 `evita_api`, 1 `evita_query`,
   3 `evita_store`, 6 `evita_external_api`, 1 benchmark harness). No `InheritableThreadLocal`, no Netty
   `FastThreadLocal`, no `ThreadGroup` use beyond one thread-factory capture.
2. **Every site is set and cleared on the same thread; none reads a value bound by another thread.** The
   only cross-executor movements are *explicit re-establishments*: `TracingContext.captureContext()` →
   `setContext()` in `ObservableThreadExecutor` (MDC + labels), `grpcContext.run(...)` in
   `EvitaSessionService`, and `ModifyCatalogSchemaMutationOperator` capturing a `Transaction` reference on
   the caller and re-binding it on the executor thread through `executeInTransactionIfProvided`.
3. **13 of 21 are clean `ScopedValue` candidates on design grounds** (lexical `try/finally` around a
   `Supplier`/`Runnable`, read only inside that dynamic scope, including from Kryo/ANTLR/gRPC-interceptor
   callbacks on the same thread). Three need a small restructure first (`Transaction`, `WarmUpSavepoint`,
   `Catalog.PENDING_TRIGGER_REBUILDS`), three are re-entrancy/toggle flags that gain nothing, two are
   scratch buffers that must stay per-thread, and one is a public non-lexical API on the Java client.
4. **The blocker is not design but JDK level:** `ScopedValue` is a *preview* API in JDK 21 (JEP 446) and is
   only final in JDK 25 (JEP 506). Shipping it on 21 means `--enable-preview` at compile *and* run time and
   class files that refuse to load on any other feature release. Every "yes" below therefore means
   "design-ready; ship when the runtime baseline is 25+ or preview is accepted".
5. **`Transaction.CURRENT_TRANSACTION` is a candidate with three preconditions:** drop the unconditional
   `remove()` in `Transaction#close()` (`Transaction.java:517`, the one non-lexical unbind, reached inside the
   proxy's bound scope via `EvitaSession.java:1970`); express the two *suspend* sites in
   `TransactionalUnorderedIntArray` (`:174-178`, `:217-221`) as a nested rebinding to `null`; and retire the
   public `bindTransactionToThread`/`unbindTransactionFromThread` pair, whose only non-test callers are those
   two suspend sites. The measured motivation exists already: `ThreadLocal` machinery is 5.25 % of busy-thread
   wall time on the sort-attribute insert path (`Transaction.java:331-350`, issue #1332), and `ScopedValue.get()`
   is a cheaper lookup than `ThreadLocalMap` probing.
6. **`WarmUpSavepoint.CURRENT` is a candidate** (single `open()` site, nesting rejected, ~70 same-thread
   `getIfOpen()` readers) but `open()` at `LocalMutationExecutorCollector.java:443` and the close in
   `finish()` (`:718-725`, `:785-792`, reached from the level-0 `finally` at `:573-578`) are not one lexical
   block; the collector's `execute` must be restructured so the `where(...).run(...)` encloses both.
7. **Category B is small and already benchmark-driven.** The only real per-thread cache is
   `FrontCodedStringColumn.SCRATCH` (kilobytes per thread, doubling growth, never shrunk). Under
   virtual-thread-per-request it is re-initialised per request on the *read* path only; the write path stays
   on a stable transaction/warm-up thread. `Crc32CWrapper`'s two scratch locals cost ~50 bytes per thread.
   `CollationKeyCache` already rejected `ThreadLocal` for a 16-way striped pool because the `get()` cost more
   than the collation work (`CollationKeyCache.java:147-157`).
8. **Kryo is pooled, not per-thread.** All `Kryo` instances go through `com.esotericsoftware.kryo.util.Pool`
   (thread-safe variant backed by `LinkedBlockingQueue`, verified by `javap`), bounded at 16 for WAL/engine
   pools and `20 × cores` for offset-index read pools. Virtual threads do not multiply Kryo instances; they
   can only exhaust the pool, which then creates and discards instances beyond capacity.
9. **Framework state is used correctly for virtual threads.** logback 1.6.1's `LogbackMDCAdapter` holds two
   plain `ThreadLocal` maps (no inheritance), gRPC 1.83.1 and OpenTelemetry 1.65.0 both store `Context` in a
   plain `ThreadLocal`; evitaDB never relies on inheritance, always re-propagates explicitly, and closes OTel
   scopes lexically except one `Scope` that is closed on the same thread by `MutationApplicationRecord.finish()`.
10. **Two thread-identity assumptions deserve attention:** `EvitaClient.java:1593` tests
    `Thread.currentThread() instanceof CdcCallbackThread`, which is unsatisfiable on a virtual thread (they
    cannot be subclassed), and `EvitaSessionProxy.java:636-641` reports concurrent access by *thread name*,
    which is empty for virtual threads by default.

---

## 2. Master table

The master table is split into three tables keyed by the same row number `#`, because the repository's
120-column rule does not allow a single 11-column row. Section 3 carries the full paths and evidence.

Legend for table 2c:

- **Set/clear** — `TF`: set and cleared in `try/finally` around a lambda on the same thread. `NL`: a
  non-lexical set or clear exists (named in §3). `init`: `withInitial`, never cleared by design.
- **Cross-exec** — whether the *binding* is ever read on a thread other than the one that set it.
  `capture`: the value is copied and re-bound on the worker through an explicit mechanism.
- **SV?** — `ScopedValue` candidate: `yes`, `yes*` (after the restructure named in §3/§5), `part` (only
  the lexical half), `no`, `n/a`.
- **VT** — virtual-thread risk: per-virtual-thread initialisation, memory retained per live thread,
  thread-identity assumptions.

### 2a. Identity

| # | ThreadLocal | Location (module/…/Class.java:line) |
|---|---|---|
| 1 | `Transaction.CURRENT_TRANSACTION` | `evita_engine/…/core/transaction/Transaction.java:75` |
| 2 | `WarmUpSavepoint.CURRENT` | `evita_engine/…/core/transaction/memory/WarmUpSavepoint.java:129` |
| 3 | `Catalog.PENDING_TRIGGER_REBUILDS` | `evita_engine/…/core/catalog/Catalog.java:244-245` |
| 4 | `EntitySchemaContext.ENTITY_SCHEMA_SUPPLIER` | `evita_engine/…/spi/store/…/EntitySchemaContext.java:47` |
| 5 | `CatalogSchemaStoragePart.CATALOG_ACCESSOR` | `evita_engine/…/schema/CatalogSchemaStoragePart.java:51` |
| 6 | `CatalogSchemaStoragePart.CATALOG_NAME_TO_REPLACE_ACCESSOR` | same file `:52` |
| 7 | `FrontCodedStringColumn.SCRATCH` | `evita_engine/…/index/bPlusTree/FrontCodedStringColumn.java:226` |
| 8 | `Crc32CWrapper.COMBINE_PRIMITIVE_SCRATCH` | `evita_common/…/utils/Crc32CWrapper.java:170` |
| 9 | `Crc32CWrapper.COMBINE_PRIMITIVE_BUFFER` | `evita_common/…/utils/Crc32CWrapper.java:175` |
| 10 | `TracingContext.CLIENT_LABELS` (+ 4 MDC keys) | `evita_api/…/api/observability/trace/TracingContext.java:134` |
| 11 | `ParserExecutor.CONTEXT` | `evita_query/…/api/query/parser/ParserExecutor.java:45` |
| 12 | `CurrentSessionRecordContext.SESSION_SEQUENCE_ORDER` | `…/serializer/CurrentSessionRecordContext.java:39` |
| 13 | `TrieNodeSerializer.ARRAY_TYPE` | `evita_store_server/…/shared/serializer/trie/TrieNodeSerializer.java:45` |
| 14 | `DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS` | `…/DefaultCatalogPersistenceService.java:297` |
| 15 | `ObservabilityTracingContext.parentContextAvailable` | `…/trace/ObservabilityTracingContext.java:59` |
| 16 | `ErrorOriginLogger.REPORTING` | `evita_external_api_observability/…/agent/ErrorOriginLogger.java:110` |
| 17 | `ErrorMonitor.REPORTING` | `evita_external_api_observability/…/agent/ErrorMonitor.java:62` |
| 18 | `AssociatedDataMutationConverter.CLIENT_VERSION` | `…grpc/shared/…/AssociatedDataMutationConverter.java:48` |
| 19 | `ClientSessionInterceptor.SessionIdHolder.SESSION_DESCRIPTOR` | `…/ClientSessionInterceptor.java:119` |
| 20 | `EvitaClient.timeout` | `evita_external_api_grpc/client/…/driver/EvitaClient.java:203` (init `:869-873`) |
| 21 | `AbstractArtificialBenchmarkState.session` | `…/artificial/AbstractArtificialBenchmarkState.java:99` |

### 2b. Purpose

| # | Purpose |
|---|---|
| 1 | The at-most-one transaction bound to the executing thread; 56 main-code files resolve it statically |
| 2 | The savepoint bracketing the current warm-up root entity mutation; read on every delegate write branch |
| 3 | Stack of frames collecting entity types whose trigger registry is rebuilt after a schema batch |
| 4 | Hands the `EntitySchema` to Kryo serializers deep in the entity deserialisation chain |
| 5 | Gives `CatalogSchemaSerializer.read` the catalog for entity-schema lookups |
| 6 | Overrides the immutable catalog name while serialising a schema during catalog replace/rename |
| 7 | Decode/encode scratch buffers reused across every string-column hop on the thread |
| 8 | Fresh-state `CRC32C` reused for fixed-width primitive folds |
| 9 | 8-byte scratch for #8 |
| 10 | Client-supplied labels for the request; the MDC keys carry trace id, client id, IP and URI |
| 11 | Parse context (client metadata, positional args) for EvitaQL parsing |
| 12 | Session sequence order and record count for traffic-record deserialisers |
| 13 | Component type of the trie value array during Kryo read |
| 14 | Test-overridable clock, thread-scoped so a pinned clock cannot leak into concurrent tests |
| 15 | "A parent tracing block is open on this thread" flag gating span recording |
| 16 | Re-entrancy guard inside exception constructors (agent-woven) |
| 17 | Same guard one level further out, on the bootstrap-classpath agent class |
| 18 | Client SemVer for backward-compatible associated-data conversion; deprecated (`TOBEDONE #538`) |
| 19 | Session id the client interceptor writes into gRPC metadata |
| 20 | Per-thread stack of caller timeout overrides over the configured default |
| 21 | One open session per JMH worker thread (benchmark harness) |

### 2c. Classification

| # | Cat | Set/clear | Lifetime | Cross-exec | SV? | VT | Recommendation |
|---|---|---|---|---|---|---|---|
| 1 | A | TF; NL: `close()`, 2 suspends | call / replay / commit | no (ref handoff) | yes* | low | migrate (§5.1) |
| 2 | A/C | open/close in two methods, same thread | root entity mutation | no | yes* | low | migrate (§5.2) |
| 3 | C | TF push/pop; callee appends | schema batch (nests) | no | yes | low | redesign: frame per scope |
| 4 | A | TF, deque for nesting | one storage-part fetch | no | yes | low | migrate |
| 5 | A | TF | one schema deserialisation | no | yes | low | migrate |
| 6 | A | TF | one schema serialisation | no | yes | low | migrate |
| 7 | B | init | worker-thread lifetime | no | no | med (read path) | KEEP write path; NEEDS BENCHMARK read path |
| 8 | B | init | worker-thread lifetime | no | no | low | KEEP AS-IS |
| 9 | B | init | worker-thread lifetime | no | no | low | KEEP AS-IS |
| 10 | A | TF + capture/restore | one API request, per task | capture | part | low | keep capture/restore |
| 11 | A | TF | one parse | no | yes | low | migrate |
| 12 | A | TF | one traffic record read | no | yes | low | migrate |
| 13 | A | TF, nesting asserted absent | one trie deserialisation | no | yes | low | migrate |
| 14 | C | init; NL set/remove in tests only | worker-thread lifetime | no | yes | low | keep |
| 15 | C | TF (execute*), NL (createAndActivate*) | one traced block | no | part | low | redesign on `Span.current()` |
| 16 | C | TF (reset to FALSE) | one `report()` | no | yes, no gain | low | keep |
| 17 | C | TF (reset to FALSE) | one consumer dispatch | no | yes, no gain | low | keep |
| 18 | A | TF | one CDC conversion | no | yes | low | REMOVE with #538 |
| 19 | A | TF in driver; NL public set/reset | one client call | no | part | low | migrate after API deprecation |
| 20 | C | TF push/pop | one timeout override | no | yes | low | migrate |
| 21 | harness | set once | benchmark run | no | n/a | n/a | note only |

Not counted: `evita_roaring_bitmap/src/test/.../SeededTestData.java:11-14` (test-only RNG/bit scratch) and the
reflection read of #7 in `FrontCodedStringColumnTest.java:1639-1653`, which pins the *shape* of the scratch
(`ThreadLocal<DecodeScratch>` with a `cur` field) — any change of #7's holder type breaks that test on purpose.

`ThreadLocalRandom` (note only, VT-safe since JDK 19 seeds it per virtual thread lazily):
`UUIDUtil.java:52`, `RandomUtils.java:78`, `RandomFunctionProcessor.java:55,58`.

---

## 3. Per-site detail and evidence

### 3.1 `Transaction.CURRENT_TRANSACTION` (#1)

Full path: `evita_engine/src/main/java/io/evitadb/core/transaction/Transaction.java`.

- Declaration and intent: `:70-75`, javadoc already says it "could move to a `ScopedValue`".
- Binding API: `bindTransactionToThread()` `:527-538` enforces at-most-one and returns `false` on a re-bind of
  the same transaction (asserted by `TransactionTest.java:112-113`), throws on a different one (`:126-130`);
  `unbindTransactionFromThread()` `:543-545`; `close()` `:495-519` removes the binding in `finally` at `:517`.
- Lexical wrappers: `executeInTransactionIfProvided` (Runnable `:129-146`, Supplier `:162-185`): `bound` flag,
  `try/catch/finally`, unbind only when this call bound. This is the single scope-establishing construct.
- Readers: `isTransactionAvailable` `:190`, seven static layer accessors `:201-320`, `getTransaction`
  `:324-327`, `getCurrentTransactionIfAvailable` `:331-353` (allocation-free; the 5.25 % figure and the
  "resolve once, pass down, never store or hand to another thread" retention contract),
  `createTransactionalPersistenceService` `:371`, `registerCloseable` `:391`. 56 main-code files call these.
- Full binding analysis in §5.1.

### 3.2 `WarmUpSavepoint.CURRENT` (#2)

Full path: `evita_engine/src/main/java/io/evitadb/core/transaction/memory/WarmUpSavepoint.java`.

- Declaration `:129`; rationale for a ThreadLocal rather than a parameter `:103-107` (the write path fans out
  through the whole index-mutation machinery; warm-up is single-writer by contract); cost when closed is one
  `ThreadLocal` read returning `null` (`:108-110`); the mechanism is unconditional at ~2 % of bulk-ingest CPU
  (`:113-118`).
- `getIfOpen()` `:237-239`; `open()` `:266-277` rejects nesting; `verifyRollbackSupported` `:507` reads it;
  `detach()` `:634-642` asserts identity then removes.
- Single call site `LocalMutationExecutorCollector.java:443`, guarded by the ordering invariant in the comment
  `:425-436` and mechanically enforced by `WarmUpRollbackConformanceTest.java:289-290` (single-site scan) and
  `:332-361` (nothing throwable between `open()` and the closing `finally`).
- Close path: `finish()` from the level-0 `finally` `:573-578` → `rollback()` `:718-725` or `commit()`
  `:785-792`.
- `DataStoreChanges.java:335-341` shows the pattern at the storage boundary ("costs a single ThreadLocal read
  returning null on the transactional path").
- Full analysis in §5.2.

### 3.3 `Catalog.PENDING_TRIGGER_REBUILDS` (#3)

Full path: `evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java`.

- `withInitial(ArrayDeque::new)` `:244-245`; frame pushed at `:1003-1005` inside `updateSchema` (`:988`),
  popped in `finally` `:1096-1100` with `remove()` when the deque empties; nested batch at `:1075` pushes its
  own frame. `markEntityTypeForTriggerRebuild` `:2936-2943` appends to `peek()` or rebuilds eagerly; called
  from `entitySchemaUpdated`/`entitySchemaRemoved` `:2268-2282`, which `EntityCollection.exchangeSchema`
  invokes synchronously (`EntityCollection.java:2854`).
- Hazard to record: `:2937` calls `get().peek()` unconditionally, so a thread that updates an entity schema
  with no catalog batch open pays the `withInitial` allocation and keeps an empty `ArrayDeque` (16-slot
  backing array) in its map forever. Cosmetic on pooled threads, free on virtual threads (dies with them).
- `ScopedValue` shape: `ScopedValue<Set<String>> FRAME`; `updateSchema` does
  `ScopedValue.where(FRAME, new LazyHashSet<>(4)).call(...)` and drains the frame it created;
  `markEntityTypeForTriggerRebuild` does `FRAME.orElse(null)`. Nesting is natural; no deque, no `remove()`.

### 3.4 `EntitySchemaContext.ENTITY_SCHEMA_SUPPLIER` (#4)

Full path: `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/EntitySchemaContext.java`.

- `:47`; `executeWithSchemaContext` `:53-72` allocates a `LinkedList` on the outermost call, pushes, pops in
  `finally`, removes when it created the deque. `getEntitySchema` `:79-84` throws when absent.
- Writers: `EntityCollection.java:3741,3750,3759,3768` (reader-side storage-part fetches),
  `DataStoreChanges.java:380` (write/commit-path pre-image read; the comment at `:373-377` explains why).
- Readers are Kryo serializers on the same thread: `ReferenceSerializer.java:79`,
  `ReferenceSerializer_2025_6.java:59`, `PricesSerializer.java:67` (all in `evita_store_entity`).
- `ScopedValue` shape: `ScopedValue<EntitySchema>`; nested `where` shadows the outer value, which is exactly
  the deque's `peek()` semantics. Saves the `LinkedList` plus one node per outermost fetch.

### 3.5 `CatalogSchemaStoragePart` accessors (#5, #6)

Full path: `evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/schema/`
`CatalogSchemaStoragePart.java`.

- `:51-52`; `deserializeWithCatalog` `:68-75`, `serializeWithCatalogName` `:103-112`, both `try/finally`.
- Writers: `Catalog.java:823`, `DefaultCatalogPersistenceService.java:2924`, `:5176`. Readers:
  `CatalogSchemaSerializer.java:98`, `CatalogSchemaSerializer_2026_1.java:79` (Kryo, same thread).
- Note: this SPI type lives under `io.evitadb.spi.store.**` and performs no IO; the ThreadLocal is pure
  vocabulary between engine and store (consistent with `.claude/rules/module-boundaries.md`).

### 3.6 `FrontCodedStringColumn.SCRATCH` (#7)

Full path: `evita_engine/src/main/java/io/evitadb/index/bPlusTree/FrontCodedStringColumn.java`.

- `:226`, `withInitial(DecodeScratch::new)`; contract `:86-96` and `:173-191`: nothing thread-local escapes;
  `finishEncode` `:1150-1176` always copies out (`:1176`), so no column state aliases the scratch.
- Holder fields `:192-221`: `cur` (48 B initial, `:156`), `encodeBuf`, three `flat`/`offsets` pairs, all
  starting empty; growth by doubling in `ensureCapacity` `:1295-1307` / `ensureIntCapacity` `:1313-1325`,
  `cur` at `:809`, `:860`; `acquireEncodeBuf` `:1145-1147` sizes to `max(16, 4 × n)`. Never shrunk.
- `get()` sites: `:414`, `:456`, `:492`, `:593` (mutation/search paths), `:685` (`decodeAt`), `:720`, `:837`,
  `:1035`. `decodeAtString` `:689-697` exists precisely to avoid a `ThreadLocal.get()` per binary-search hop.
- Cost model in §4.1.

### 3.7 `Crc32CWrapper` scratch (#8, #9)

Full path: `evita_common/src/main/java/io/evitadb/utils/Crc32CWrapper.java`.

- `:170`, `:175`; `combineLong` `:195-209`, `combineInt` `:219-228`, `combineByte` (1-byte variant) each do
  two `get()`s, `reset()`, `update()`, then `combine`.
- Callers: `Crc32CChecksum.java:110,119,128` in `evita_store_key_value` (offset-index/WAL write path).
  `ObservableOutput.java:537,798` use the pure `combine` and not the scratch.
- Cost model in §4.2.

### 3.8 `TracingContext.CLIENT_LABELS` and the MDC keys (#10)

Full path: `evita_api/src/main/java/io/evitadb/api/observability/trace/TracingContext.java`.

- `:134`; `executeWithClientContext(ip, uri, labels, supplier)` `:158-172` puts two MDC keys and the labels,
  clears all three in `finally`. `captureContext` `:221-231` snapshots four MDC keys + labels into an
  immutable record (`EMPTY` when all null); `setContext`/`clearContext` `:238-256`.
- Cross-executor: `ObservableThreadExecutor.AbstractObservableTask.capturedContext`
  (`evita_engine/…/core/executor/ObservableThreadExecutor.java:698`) captures at *construction* on the
  submitting thread; `ObservableRunnable.run` `:1000-1016` and `ObservableCallable.call` `:1096-1113`
  restore in the worker and clear in `finally`. Class javadoc `:73-76`.
- This is evitaDB's one general-purpose context-propagation mechanism. It is executor-agnostic and keeps
  working unchanged if the executor is virtual-thread-per-task, because it never relies on inheritance.
- `ScopedValue` note: scoped values are inherited only by `StructuredTaskScope` forks, not by
  `ExecutorService.submit`. Migrating `CLIENT_LABELS` would still need this capture/restore; MDC (logback,
  §6.1) cannot be a `ScopedValue` at all. Keep as is.

### 3.9 `ParserExecutor.CONTEXT` (#11)

Full path: `evita_query/src/main/java/io/evitadb/api/query/parser/ParserExecutor.java`.

- `:45`; `execute` `:54-83` sets, runs, maps exceptions, removes in `finally`; `getContext` `:90-94` asserts
  presence. Readers are ANTLR visitor callbacks invoked synchronously inside the parse
  (`DefaultQueryParser` ×6, `EvitaQLParameterVisitor` ×2, `EvitaQLValueTokenVisitor` ×1).

### 3.10 `CurrentSessionRecordContext.SESSION_SEQUENCE_ORDER` (#12)

Full path: `evita_store/evita_traffic_engine/src/main/java/io/evitadb/store/traffic/serializer/`
`CurrentSessionRecordContext.java`.

- `:39`; `fetch` `:52-59` (`try/finally`), `get` `:67-69`. Writers `OffHeapTrafficRecorder.java:966`,
  `InputStreamTrafficRecordReader.java:139`, `:227`; readers: eight traffic-record Kryo serializers
  (`SessionStartContainerSerializer.java:50`, `QueryContainerSerializer.java:82`, …), same thread.

### 3.11 `TrieNodeSerializer.ARRAY_TYPE` (#13)

Full path: `evita_store/evita_store_server/src/main/java/io/evitadb/store/shared/serializer/trie/`
`TrieNodeSerializer.java`.

- `:45`; `deserializeWithArrayType` `:47-55` asserts it is not already set (no nesting), sets, runs, removes
  in `finally`; read at `:86` inside Kryo `read()`. Single caller `TrieSerializer.java:56`.

### 3.12 `DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS` (#14)

Full path: `evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog/`
`DefaultCatalogPersistenceService.java`.

- `:297`, `withInitial(() -> SYSTEM_TIME_MILLIS)` returning the shared constant `:275`; read `:1160`. The
  javadoc `:285-296` records *why* thread scope: one reused surefire fork with `parallel=all`, and a
  process-wide override once stamped unrelated catalogs' bootstrap records with a pinned past instant.
- Tests set/remove non-lexically (`DefaultCatalogPersistenceServiceTest.java:278, 655, 678, 770`).

### 3.13 `ObservabilityTracingContext.parentContextAvailable` (#15)

Full path: `evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/`
`observability/trace/ObservabilityTracingContext.java`.

- Instance field ThreadLocal `:59` (one per tracing-context instance). Set to `TRUE` when the outermost block
  opens, restored to `FALSE` (never removed) when it closes: `executeWithinBlockOpeningParentContext`
  `:433-450`, `executeWithinBlockUsingCustomParentContext` `:478-498` (lexical);
  `createAndActivateBlockOpeningParentContext` `:581-598` and `...UsingCustomParentContext` `:623-644`
  restore inside the returned block's close callback (non-lexical). `isSpanRecorded` `:703-705`.
- The one non-lexical block in main code is `TrafficRecordingEngine.java:499`, closed by
  `MutationApplicationRecord.finish()`/`finishWithException()` `:760-761`, `:775-777`, which the collector
  and `Catalog.updateSchema` call in their own `finally` on the same thread.
- `createAndActivateBlockInternal` `:658-688` also leaves an OTel `Scope` open (`:685`) for the same block.
- Redesign option: OTel already answers "is there a parent" through `Span.current()`; the flag duplicates it
  to short-circuit span-name composition. Replacing the flag with `Span.current().getSpanContext().isValid()`
  keeps the short-circuit and deletes the ThreadLocal.

### 3.14 `ErrorOriginLogger.REPORTING`, `ErrorMonitor.REPORTING` (#16, #17)

Full paths: `evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/`
`observability/agent/ErrorOriginLogger.java` and `.../agent/ErrorMonitor.java`.

- `ErrorOriginLogger.java:110` (`withInitial(FALSE)`), guard `:180-186`, reset `:203`;
  `ErrorMonitor.java:62` (null-initial), guard `:138-141`, reset `:147`. Both run inside exception
  constructors woven by the agent; the javadocs (`:99-108`, `:53-60`) explain the two-level guard.
- `ScopedValue` is expressible (`where(REPORTING, TRUE).run(consumer)`) and would even be safe on the
  bootstrap classpath (it is a JDK type), but it changes nothing measurable.

### 3.15 `AssociatedDataMutationConverter.CLIENT_VERSION` (#18)

Full path: `evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/`
`requestResponse/data/mutation/associatedData/AssociatedDataMutationConverter.java`.

- `:48`; `doWithClientVersion` `:61-68` (`@Deprecated`, `TOBEDONE #538`), `getClientVersion` `:77-79`. Single
  caller `ChangeCaptureConverter.java:342-355`. Scheduled for removal, not migration.

### 3.16 `ClientSessionInterceptor.SessionIdHolder.SESSION_DESCRIPTOR` (#19)

Full path: `evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/interceptor/`
`ClientSessionInterceptor.java`.

- `:119`; `executeInSession` ×2 `:128-148` (`try/finally`), public `setSessionId` `:155-157`, `reset`
  `:162-164`, `getSessionId` `:171-173`. The interceptor reads it in `start()` `:95-97`, which Armeria's gRPC
  client runs synchronously on the calling thread when the stub method is invoked (unary and streaming
  alike).
- Driver call sites use `set` inside `try` and `reset` in `finally` (`EvitaClientSession.java:2483/2529`,
  `:2594/2626`), i.e. lexical in practice; the non-lexical public API is used by documentation examples and
  functional tests only.

### 3.17 `EvitaClient.timeout` (#20)

Full path: `evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java`.

- `:203` (javadoc `:195-202` explains the tier model), seeded per thread `:869-873` with one `Timeout`;
  `executeWithExtendedTimeout` ×2 `:1638-1645`, `:1659-1666` push/pop in `try/finally`; `resolveTimeout`
  `:1682-1688` treats "more than one element" as an override; `:1008`, `:1796` peek.
- `ScopedValue<Timeout> OVERRIDE`: `where(OVERRIDE, t).run(lambda)`; `resolveTimeout` =
  `OVERRIDE.isBound() ? OVERRIDE.get() : tier.resolve(...)`. Deletes the per-thread `LinkedList`.

### 3.18 `AbstractArtificialBenchmarkState.session` (#21)

`evita_test/evita_performance_tests/src/main/java/io/evitadb/performance/artificial/`
`AbstractArtificialBenchmarkState.java:99`, used by `ArtificialBenchmarkState.java:51-54` to lazily open one
session per JMH worker. Harness only; JMH workers are platform threads.

---

## 4. Category B — per-thread cache cost model

Thread population today (defaults, `ThreadPoolOptions.java:85-101`; the brief's "16/16/16" applies only when
configured so): request pool `cores … 4×cores`, transaction pool `cores … 4×cores`, service pool
`cores … 2×cores`, client pool `0 … 4×cores`; plus Armeria/Netty event loops (covered by the Armeria part).
On a 16-core box that is up to 64 + 64 + 32 long-lived platform threads that can each hold one instance of
every Category B holder. Under virtual-thread-per-request the holder count on the *request* path equals the
number of concurrently live requests, and each holder dies with its request.

### 4.1 `FrontCodedStringColumn.SCRATCH` (#7)

- **Initialisation.** `new DecodeScratch()`: one object with 8 fields, `cur = new byte[48]` (`:194`, `:156`),
  everything else the shared empty array. About 64 + 64 bytes, no I/O.
- **Growth.** Doubling on demand (`:1295-1307`, `:1313-1325`, `:809`, `:860`), never shrunk. `encodeBuf`
  grows to the largest blob re-encoded on the thread (`max(16, 4×n)` seed at `:1145-1147`, then to the real
  encoded length); `flat`/`flat2`/`flat3` grow to the decoded byte total of the largest leaf handled
  (`copyRangeTo` needs all three live, `:186-191`); `offsets*` to `size + 1` ints.
- **Ceiling per thread.** Bounded by leaf size × longest keys: "kilobytes each … a 32-thread commit pool
  retains at most 32 holders" (`:189-191`).
- **Identity dependence.** None functionally (nothing escapes); economically it assumes the *same* thread
  decodes again so the grown buffers amortise.
- **Where it runs.** Write path (insert/remove/split/encode) on the warm-up session thread or a
  transaction-pool thread; read path (`findKeyPosition` fallback comparisons, `decodeAt`) on request threads.
- **Instances today.** At most one per platform thread that ever touched a string column: up to ~160 on
  16 cores, kilobytes each, low hundreds of KB in total.
- **Instances under VT-per-request.** Read path: one per concurrently live request that touches a string
  column, allocated and re-grown per request, released with it. The steady-state young-gen churn the holder
  was introduced to remove (`:174-177`) returns *on the read path only*, as one growth series per request
  rather than one `byte[]` per hop. Write path unchanged as long as commits and warm-up stay on stable
  threads.

Recommendation: **KEEP AS-IS for the write path; NEEDS BENCHMARK for the read path.** If the read path shows
the churn, the two alternatives that preserve the design are (a) carry the scratch explicitly in the query
execution context the search already threads through, or (b) a small striped pool keyed like
`CollationKeyCache` (`:263`), which that class chose over `ThreadLocal` because the map probe cost more than
the work (`:147-157`). A JDK 21 virtual thread's `ThreadLocal.get()` is the same `ThreadLocalMap` probe as on
a platform thread; the difference under virtual threads is initialisation frequency and retained-while-live
memory, not lookup cost.

### 4.2 `Crc32CWrapper` scratch (#8, #9)

- **Initialisation.** `new CRC32C()` (one `int` field) + `new byte[8]`; about 48 bytes, trivially cheap.
- **Growth.** None (fixed size). **Identity dependence.** None.
- **Where it runs.** `Crc32CChecksum` (`evita_store_key_value/.../checksum/Crc32CChecksum.java:110-128`) on
  the write/commit path (offset index and WAL output). Not on the query path.
- **Under VT.** Per-request initialisation is ~48 bytes and only if a request thread ever writes a checksum;
  commit threads are stable.

Recommendation: **KEEP AS-IS.** The alternative of allocating a fresh `CRC32C` per fold is plausible (escape
analysis may scalar-replace it since `update(byte[],int,int)` is an intrinsic) but is a benchmark question,
not a virtual-thread question.

### 4.3 Things that look like per-thread caches but are not

- **`CollationKeyCache`** (`evita_common/.../comparator/CollationKeyCache.java`): a 16-way striped pool of
  `Collator`s keyed by `threadId & 15` (`:126`, `:263-270`), chosen *instead of* a ThreadLocal after profiling
  (`:147-157`). Virtual thread ids are unique and dense, so striping still spreads; the pool self-heals on
  collision by cloning a fresh `Collator` (tens of microseconds). With thousands of live virtual threads the
  collision rate on 16 stripes rises. **KEEP; NEEDS BENCHMARK** under the target concurrency; raising
  `COLLATOR_STRIPES` is the knob.
- **Kryo instances** are never per-thread. `com.esotericsoftware.kryo.util.Pool` is used everywhere
  (`DefaultCatalogPersistenceService.java:505` WAL pool, `DefaultEnginePersistenceService.java:134,144`, both
  bounded at 16; `OffsetIndex.java:403-406` read pool bounded at `maxOpenedReadHandles` = `20 × cores` by
  default, `StorageOptions.java:158`; `OffHeapTrafficRecorder.java:122` with soft references;
  `AbstractMutationLog.java:1186-1210`, `:1345-1480` borrow/free in `try/finally`;
  `OffsetIndex.FileOffsetIndexKryoPool.borrowAndExecute` `:2722-2729`). `javap` on kryo 5.6.2 shows the
  thread-safe pool is `Pool$1 extends LinkedBlockingQueue` (lock-based, no virtual-thread pinning), the
  non-thread-safe ones `LinkedList`/`ArrayDeque`. Creating a `Kryo` through `KryoFactory.createKryo`
  registers every serializer and is the expensive step; under virtual threads the exposure is *pool
  exhaustion* (more concurrent borrowers than capacity → create-and-discard), not per-thread duplication.
- **`SharedBufferPool`** (`evita_engine/.../core/query/SharedBufferPool.java:37-45`): global pool of up to
  1000 `int[512]`; **`OffsetIndex.decompressionPool`** `:439`; **`OffHeapTrafficRecorder.copyBufferPool`**
  `:787`. Global, bounded, thread-neutral.
- **`ObservableOutputKeeper`** (`evita_store_key_value/.../kryo/ObservableOutputKeeper.java:112`): a
  `ConcurrentHashMap<Path, OpenedOutputToFile>` — one buffered output per *file*, serialised by
  `WriteOnlyFileHandle`'s `ReentrantLock` (`:150`), which does not pin virtual threads.
- **`ByteBuffer.allocate` sites** (`AbstractMutationLog.java:281`, `DiskRingBuffer.java:111`, migrations)
  are instance-owned buffers guarded by the owning lock, not per-thread state.

---

## 5. Binding analysis

### 5.1 `Transaction.CURRENT_TRANSACTION`

**Where the binding is established** (always through `executeInTransactionIfProvided`):

- Every proxied session call: `EvitaSessionProxy.executeWithinTransactionContext` `:493` binds
  `session.getOpenedTransaction()` or `null`; `rollbackOnException` only at root level.
- Implicit transaction for a write outside an explicit one: `EvitaSession.executeInTransactionIfPossible`
  `:2419` (creates and `try`-closes a new one) and `:2440` (re-enters the existing one).
- Stage 2, trunk incorporation of a committed transaction: `TransactionManager.commitChangesToSharedCatalog`
  `:371` binds the replay `Transaction`.
- WAL replay of mutations onto the catalog: `TransactionManager.replayMutationsOnCatalog` `:2034`.
- Engine-level catalog schema mutation: `ModifyCatalogSchemaMutationOperator` `:78-82` captures the
  transaction **on the caller** (`Transaction.getTransaction().orElse(null)`) and re-binds it inside a
  `ProgressingFuture` lambda that `CompletableFuture.runAsync`s on the executor
  (`ProgressingFuture.java:399-419`).
- Traffic-recording index updates: `DiskRingBuffer.updateIndexTransactionally` `:389` binds a private
  `Transaction` over the traffic index.
- Warm-up: none. `WARMING_UP` sessions have no transaction (`EvitaSession.java:1902-1906` asserts
  `transaction == null`), so `executeInTransactionIfProvided(null, …)` runs the lambda directly.

Benchmarks in `evita_performance_tests` also call it (`SortIndexCommittedSnapshotCacheBenchmark.java:169…`);
they are harness code.

**Nesting.** A single value, not a deque. Re-binding the same transaction returns `false` and is a no-op
(`:527-538`), which is what makes the common proxy → `executeInTransactionIfPossible` nesting work. A
different transaction on the same thread is a premise failure ("cannot mix calling different sessions").
`ScopedValue` expresses nesting natively; the "different transaction" assertion becomes an explicit check
before `where(...)`.

**Sets or removes outside a lexical scope:**

1. `Transaction#close()` `:495-519` removes the binding unconditionally in `finally` (`:517`). In the main
   path this fires *inside* the proxy's bound scope: closing a session goes through the proxy →
   `executeWithinTransactionContext` binds the open transaction → `EvitaSession.closeInternally` reaches
   `try (transaction)` at `:1970` → `close()` → `remove()`. Everything in `closeInternally` after that line
   runs with no bound transaction even though the lexical scope is still open. With a `ScopedValue` the
   binding cannot be removed mid-scope, so the code between `:1970` and scope exit must be audited for reads
   that currently observe "no transaction" (`isTransactionAvailable()`, `getTransaction().isEmpty()`) and
   depend on it. The `remove()` in `close()` then goes.
2. `TransactionalUnorderedIntArray.assembleFromPagesInBase` `:170-183` and `bulkLoadInBase` `:213-226`
   *suspend* the binding (`unbindTransactionFromThread()` … `finally bindTransactionToThread()`) so a bulk
   build lands in the committed BASE rather than a discardable diff layer (javadoc `:161-168`, `:200-211`).
   `ScopedValue` cannot unbind, but `ScopedValue.where(CURRENT, null).run(...)` rebinds to `null` in a nested
   scope, and `get()` then returns `null`; `isTransactionAvailable()` must become
   `CURRENT.orElse(null) != null` rather than `isBound()`. Both sites become a nested rebinding.
3. The public `bindTransactionToThread`/`unbindTransactionFromThread` are otherwise used only by
   `TransactionTest.java:69, 95-101, 112-113, 126-130, 301-348`. They can be removed once (2) is rewritten.

**Reads from a different thread than the one that bound it.** None found.

- The ALIVE flush runs *inside* the bound scope on the transaction thread:
  `TransactionTrunkFinalizer.commitCatalogChanges` `:104-108` calls `catalogToUpdate.flush(...)`
  (`Catalog.java:2772`) from within `TransactionManager.java:371`'s lambda. This is the "confinement by
  consequence" that `AbstractTransactionalBPlusTree.java:714-734` names as a hazard: a flush on another
  thread would resolve every leaf to its committed instance, see `dirty == false`, and emit no pages —
  silently. Nothing enforces it beyond the paging tests passing.
- The warm-up flush pops the trapped changes *synchronously on the session thread*
  (`EvitaSession.java:1920-1929`, "building the warm-up flush future pops the trapped changes SYNCHRONOUSLY")
  and hands detached `DataStoreChanges` to the executor; the persisting task does not consult the
  ThreadLocal.
- `getCurrentTransactionIfAvailable`'s retention contract `:344-350` forbids storing the reference or handing
  it to another thread. `ModifyCatalogSchemaMutationOperator` hands the reference across and re-binds it
  through the sanctioned wrapper, which is compatible with a `ScopedValue` (the value travels as an argument;
  the scope is re-opened on the executor thread).

**Verdict.** Candidate: **yes**, with the three changes above, a `CURRENT.orElse(null)` accessor in place of
`get()` (an unbound `ScopedValue.get()` throws), and the JDK caveat from §1. Expected win: the 5.25 % lookup
share on the insert path shrinks (a `ScopedValue` lookup is a small per-thread cache probe rather than a
`ThreadLocalMap` hash probe), and the "resolve once, pass down" discipline stays valid.

### 5.2 `WarmUpSavepoint.CURRENT`

- **Established:** one site, `LocalMutationExecutorCollector.java:443`, on the branch where
  `Transaction.getTransactionalLayerMaintainer()` is `null` and atomic rollback is requested. Warm-up only in
  practice; the javadoc `:249-261` explains why a second site would invalidate every
  `isTransactionAvailable()` gate in the index mutators, and `WarmUpRollbackConformanceTest` scans the
  sources to keep it single.
- **Closed:** `commit()`/`rollback()` → `detach()` `:634-642`, invoked from `finish()` (`:718-725`,
  `:785-792`) which the level-0 `finally` at `:573-578` calls. The open and the close are in the same
  `execute` invocation but not the same lexical block, and the savepoint must stay open across nested
  (`level > 0`) recursion in the apply loop.
- **Nesting:** rejected (`:266-273`); `TransactionalLayerMaintainer#openSavepoint()` mirrors it.
- **Cross-thread:** never; `WARMING_UP` is contractually single-threaded (`:103-107`).
- **Set without scope:** none besides the cross-method pairing above; the ordering test at `:332-361`
  exists because a throw between `open()` and the `finally` leaks the binding to the next entity on the
  thread. A `ScopedValue` removes that class of leak entirely: the binding ends when the scope ends, whatever
  throws.
- **Verdict:** candidate **yes**; effort is restructuring `execute` so that
  `ScopedValue.where(CURRENT, savepoint).call(() -> { apply loop; finish(); })` encloses both, with
  `commit()` / `rollback()` still choosing the outcome inside the scope. About 70 `getIfOpen()` read sites
  change to `CURRENT.orElse(null)` mechanically. Mutability of the savepoint object is irrelevant to
  `ScopedValue` — the *binding* is immutable, the journal it points at is not.

---

## 6. Framework per-thread state (Category D), from evitaDB's side

Versions (`pom.xml:126-149`): slf4j 2.0.18, logback 1.6.1, kryo 5.6.2, grpc 1.83.1, armeria 1.41.1,
opentelemetry 1.65.0.

### 6.1 logback MDC

- `javap -p` on `logback-classic-1.6.1.jar` `ch.qos.logback.classic.util.LogbackMDCAdapter`: fields
  `readWriteThreadLocalMap`, `readOnlyThreadLocalMap` (both plain `java.lang.ThreadLocal<Map<String,String>>`)
  and `threadLocalMapOfDeques` (`org.slf4j.helpers.ThreadLocalMapOfStacks`). **No `InheritableThreadLocal`**,
  so child threads inherit nothing; the read-only map is a copy handed to appenders per log event.
- evitaDB touches MDC only in `TracingContext` (`:158-256`) and `ObservabilityTracingContext.initMdc/clearMdc`
  (`:64-74`); no other `MDC.*` call exists in main code. It never relies on inheritance and re-propagates
  through `captureContext()` (§3.8). Under virtual threads each request's first `MDC.put` allocates a
  `HashMap` in that thread's map (about 100 bytes) and drops it with the thread. No pinning: the adapter has
  no `synchronized`.

### 6.2 `io.grpc.Context`

- `javap` on `grpc-api-1.83.1.jar`: `io.grpc.ThreadLocalContextStorage.localContext` is a plain
  `ThreadLocal`.
- Server: `ServerSessionInterceptor` puts `SESSION` and `METADATA` keys (`:109-110`, `:173-178`) via
  `Contexts.interceptCall`. `EvitaSessionService.executeWithClientContext` `:194-206` reads the keys and
  `Context.current()` on the gRPC thread and runs the executor task under `grpcContext.run(...)`,
  re-attaching the context on the pool thread and detaching when the runnable returns. Other readers stay on
  the gRPC thread (`ObservabilityInterceptor.java:79`, `EvitaSessionService.java:2627`).
- Client: Armeria's `ClientRequestContext.current()` is used only to reset streaming response timeouts
  (`EvitaClientSession.java:548, 643, 805, 3145, 3301`, `EvitaClient.java:1253`,
  `EvitaClientManagement.java:506`) on the callback thread that Armeria provides.

### 6.3 OpenTelemetry `Context`

- `javap` on `opentelemetry-context-1.65.0.jar`: `ThreadLocalContextStorage.THREAD_LOCAL_STORAGE` is a plain
  `ThreadLocal<Context>`. `StrictContextStorage` and `Context.taskWrapping(...)` are not used.
- evitaDB activates scopes lexically with try-with-resources everywhere but one place:
  `GrpcTracingContext.java:89-176` and `JsonApiTracingContext.java:127-270` extract the parent from the
  carrier headers on whichever thread the block runs (so propagation to executor threads is by
  *re-extraction from the request metadata*, not by ThreadLocal crossing);
  `ObservabilityTracingContext.executeWithinBlockInternal` `:543`; the parent-context variant `:486`. The
  non-lexical `Scope` at `:685` belongs to the block reference returned by `createAndActivateBlockInternal`
  and is closed by `ObservabilityTracingBlockReference.close()` on the same thread (§3.13).
- Virtual threads: a `ThreadLocal`-backed storage is fine on virtual threads; the cost is the same map entry
  per live thread. The only risk is a `Scope` left open when a block is closed on another thread — no such
  site exists today.

### 6.4 Kryo

No per-thread state of Kryo's own is used; instances are pooled (§4.3). `Kryo` itself is not thread-safe,
which is why the pools exist; the pool's `LinkedBlockingQueue` is `ReentrantLock`-based and does not pin.

### 6.5 RoaringBitmap (vendored `evita_roaring_bitmap`)

No `ThreadLocal` in the vendored main sources; the only hits are test helpers
(`src/test/.../SeededTestData.java:11-14`).

---

## 7. Thread-identity assumptions (`Thread.currentThread()` and friends)

Excluding the ~60 `Thread.currentThread().interrupt()` sites, which are standard `InterruptedException`
etiquette and behave identically on virtual threads.

- **`evita_common/…/comparator/CollationKeyCache.java:263`** — `threadId() & 15` selects a collator stripe.
  Works on virtual threads; ids are unique and dense. Collision rate grows with live thread count (§4.3).
- **`evita_engine/…/core/executor/Scheduler.java:1023-1031`** — captures the constructing thread's
  `ThreadGroup` for `Evita-service-N` platform threads. Runs at start-up on a platform thread. If ever
  constructed on a virtual thread the captured group is the JDK's `VirtualThreads` group; harmless for
  platform threads created from it, but worth pinning to start-up only.
- **`evita_engine/…/core/executor/ObservableThreadExecutor.java:854`** (with `:720`, `:795`) — publishes the
  executing thread so `cancel()` can `interrupt()` it. `Thread.interrupt()` works on virtual threads; the
  `executionState` handshake `:870-880` is what makes delivery safe and is thread-kind-agnostic.
- **`evita_engine/…/core/executor/InterruptionTransformer.java:103`** — Byte Buddy advice throwing
  `InterruptedException` when the current thread is interrupted at `Formula#compute` entry. Identical on
  virtual threads.
- **`evita_engine/…/core/session/EvitaSessionProxy.java:119`, `:636-641`** — read-write sessions are owned
  by one thread per outermost invocation; a *different* thread entering concurrently gets
  `ConcurrentSessionAccessException` naming both threads. The identity guard works for virtual threads (each
  request's virtual thread owns the session for its call; sequential calls from different threads are fine).
  The exception message uses `getName()`, which is the empty string for unnamed virtual threads — cosmetic,
  but the diagnostic loses its value; name the virtual threads via the factory or include the thread id.
- **`evita_external_api/…/driver/EvitaClient.java:1593`** — `Thread.currentThread() instanceof
  CdcCallbackThread` detects `close()` called from inside a CDC callback, to skip awaiting the callback
  executor's termination. **Unsatisfiable on a virtual thread** — `Thread.ofVirtual()` threads cannot be
  subclasses. If the CDC callback executor is ever switched to virtual threads, a consumer closing the client
  from its own `onError` will wait on itself. Replace the type test with a marker set by the callback wrapper
  (a `ThreadLocal`/`ScopedValue` flag) or with a thread-id registry.
- **`evita_test/…/spike/radixtrie/RadixTrieMemorySpike.java:377`** — `ThreadMXBean.getThreadAllocatedBytes`
  by thread id; `ThreadMXBean` does not report virtual threads (returns `-1`). Benchmark-only, note.
- **`evita_test/…/spike/WarmUpAtomicityIngestBenchmark.java:671`** — records the ingest thread's name;
  empty on a virtual thread. Benchmark-only, note.
- **`evita_common/…/exception/ErrorCodeResolver.java:43`** — comment only (explains why `getStackTrace()` is
  *not* used).

Not thread identity but thread *kind* sensitive, for completeness: `WriteOnlyFileHandle` and
`ObservableOutputKeeper` synchronise with `ReentrantLock` (`WriteOnlyFileHandle.java:150`), and Kryo's
thread-safe `Pool` with `LinkedBlockingQueue` — none of the hot per-thread caches sits behind a `synchronized`
block that would pin a JDK 21 virtual thread (pinning on monitors is only lifted in JDK 24, JEP 491). A
separate sweep for `synchronized` on I/O paths belongs to the virtual-thread part of this specification, not
to this inventory.

---

## 8. Recommended order of work (if the `ScopedValue` route is taken)

1. Settle the JDK question first: preview on 21 versus waiting for a 25 baseline. Nothing below should be
   merged on `--enable-preview`.
2. Mechanical, zero-risk conversions with lexical scopes and same-thread callback readers: #4, #5, #6, #11,
   #12, #13, #20 (and #19 after the driver's two call sites move to `executeInSession`).
3. `Catalog.PENDING_TRIGGER_REBUILDS` (#3) as a per-frame `ScopedValue`; deletes the deque and the leaked
   empty `ArrayDeque`.
4. `Transaction.CURRENT_TRANSACTION` (#1): remove the `close()` unbind, rewrite the two suspend sites as a
   nested `null` rebinding, retire the public bind/unbind pair, then switch the field. Re-run the #1332
   sort-attribute insert profile to confirm the lookup share drops.
5. `WarmUpSavepoint.CURRENT` (#2): restructure `LocalMutationExecutorCollector.execute` so the scope encloses
   apply loop and `finish()`; re-run the bulk-ingest profile that gated the 2 % figure.
6. Leave #7, #8, #9 as ThreadLocals; benchmark #7's read path under the chosen executor model before deciding
   anything. Leave #10, #14, #16, #17 alone; redesign #15 onto `Span.current()`; delete #18 with #538.
