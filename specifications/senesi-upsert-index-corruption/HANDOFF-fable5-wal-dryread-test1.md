# Investigation handoff — WAL "dry-read" Test 1 (bug-03 item 1a)

**To:** a fresh model session (Fable 5). **From:** the fix session working `specifications/senesi-upsert-index-corruption/FIXES.md`.

**What I need back:** a precise root-cause of the single failing test described below, and a concrete,
minimal fix recommendation (which file/method/lines, and why it is safe). If you can, verify the fix and
report the resulting test counts. Do **not** weaken, relax, or delete the failing test. Read-only analysis
plus a proposed patch is enough; applying + verifying is a bonus.

---

## 0. Environment / ground rules

- Repo: `/www/oss/evita/evitaDB-dev`, branch `warmup-upsert-alloc-optimization`. Java 17, Maven.
- Build/test via `rtk mvn ...` (a token proxy). **Never** pipe `rtk mvn` through `grep`/`head` — it hides
  assertion text; use `tail -N` or read the surefire `.txt`/`.xml` reports directly.
- After changing engine/store **source**, the functional-test module resolves the module from `~/.m2`, so
  reinstall it first: `rtk mvn -pl evita_store/evita_store_server install -DskipTests`. (Stale-m2-jar trap.)
- Targeted test runs need `-Dtest.tag.policy=off`.
- Code style: **tabs** for indentation; no `System.out`/`println` debugging (a JDWP MCP is available — the
  `jdwp-debugging:java-debug` skill; connect with `jdwp_wait_for_attach(port=<N>)`, never a fixed 5005).
- Do not touch `/www/oss/evita/evitaDB-dev/data*` dirs or any `backup_senesi_actual_*.zip`.

## 1. The one failing test (everything else is green)

```
rtk mvn -pl evita_test/evita_functional_tests test -o \
  -Dtest='CatalogWriteAheadLogTest,CatalogWriteAheadLogIntegrationTest' -Dtest.tag.policy=off
```
Current result: **21 run, 0 failures, 1 ERROR**. The only failure:

```
CatalogWriteAheadLogTest$DryReadVisibilityRaceTests
  .shouldNotReturnDryStreamForLastAppendedVersionMissingOnlyTrailingChecksum
  » com.esotericsoftware.kryo.KryoException: Encountered unregistered class ID: 11
```

Test file: `evita_test/evita_functional_tests/src/test/java/io/evitadb/store/wal/CatalogWriteAheadLogTest.java`
(nested class `DryReadVisibilityRaceTests`, ~line 378). **The test is correct and must not be changed.**

### What the test does
`@BeforeEach` appends **4 real transactions** through the real `CatalogWriteAheadLog.append()` API.
`txSizes = {55, 152, 199, 46}` → transaction 4 (catalog version 4) has **46 individual mutations**.
The test then strips exactly the **trailing 8-byte cumulative checksum** of transaction 4:

```java
modifyWalFile(raf -> { raf.setLength(raf.length() - AbstractMutationLog.CUMULATIVE_CRC32_SIZE); return null; });
```

`CUMULATIVE_CRC32_SIZE == 8`. So on disk transaction 4 is: `[4-byte content length][TransactionMutation
StorageRecord][mutation StorageRecord ×46]` fully present, and **only** the final 8-byte cumulative CRC is
gone. The test asserts `getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(4, 4)` is **non-empty**
(i.e. it must deliver transaction 4: header + all 46 mutations). `stream.toList()` consumes every element.

### Why this test exists (bug-03)
It is a distilled reproduction of a production commit-progress hang. In production, when the writer has
finished appending version N (`lastWrittenCatalogVersion == N`, checksum and all), a concurrent reader whose
`RandomAccessFile.length()` view momentarily **lags** would call
`getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(N, N)` and get a **dry (empty)** stream; the trunk
incorporation stage then misreads "nothing to read" as "already processed", never completes the commit
progress record, and spins forever. Fix acceptance: that call must **not** go dry for a version whose content
is durably on disk. See `scenarios/bug-03-commit-progress-hang.md` (Test 1 = the `(N,N)` constructor path).

## 2. What I changed for item 1a (and it half-works)

Two supplier files under `evita_store/evita_store_server/src/main/java/io/evitadb/store/wal/supplier/`. Full
current diff saved at: `<scratchpad>/item1-supplier.diff` (also reproduced by `git diff` on those two files).

Design rule (validated earlier): the forward `MutationSupplier` tracks the highest version delivered; a read
failure/incompleteness is a **graceful end** (return null) only when reading greedily *or* when we have
already delivered up to `requestedCatalogVersion`; otherwise it must **throw** (surface the failure). Gating
is on `avoidPartiallyFilledBuffer` (true only for `getCommittedMutationStreamAvoidingPartiallyWrittenBuffer`;
`requestedCatalogVersion == Long.MAX_VALUE` for greedy reads). `ReverseMutationSupplier` always passes
`avoidPartiallyFilledBuffer = false`, so the relaxations below are inert for it.

- **`AbstractMutationSupplier.readAndRecordTransactionMutation`** (the room check): in
  `avoidPartiallyFilledBuffer` mode, require only the **content** to be on disk (`4 + contentLength`), not the
  trailing 8-byte checksum. Greedy mode keeps the full-record requirement.
- **`AbstractMutationSupplier` constructor scan loop**: same relaxation via a new helper
  `requiredEndPosition(startPos, tx)` = full end, minus `CUMULATIVE_CRC32_SIZE` in avoid mode.
- **`MutationSupplier.get()` Phase 3**: computed `mayEndGracefully`; content-aware `canProceed`; the
  "no room for another transaction / can't move to next file" branch throws when `!mayEndGracefully`
  (this fixed the sibling Test 2 — see below); a `WriteAheadLogCorruptedException` (cumulative-checksum
  mismatch) always rethrows; any other read exception → graceful when `mayEndGracefully` else throw.

**Result of these changes:**
- The **sibling** test `CatalogWriteAheadLogIntegrationTest$MisalignedReadSwallowTests
  .shouldNotSilentlyEndStreamWhenAdvancingIntoAGenuinelyUnderflowingTransaction` now **PASSES** (it wanted a
  throw when advancing into a genuinely underflowing *next* transaction before the requested version — done).
- Test 1 now gets **past the constructor** (my relaxations make the constructor deliver transaction 4's
  header instead of returning an empty stream — progress!), but then **fails while reading transaction 4's
  mutations** in Phase 2. **This is the wall.**

## 3. The exact wall (Test 1)

Failing stack (Phase 2, `readMutation`, which I did **not** change):

```
com.esotericsoftware.kryo.KryoException: Encountered unregistered class ID: 11
  at com.esotericsoftware.kryo.util.DefaultClassResolver.readClass(DefaultClassResolver.java:159)
  at com.esotericsoftware.kryo.Kryo.readClassAndObject(Kryo.java:869)
  at io.evitadb.store.wal.supplier.MutationSupplier.lambda$readMutation$2(MutationSupplier.java:262)
  at io.evitadb.store.offsetIndex.model.StorageRecord.lambda$read$6(StorageRecord.java:537)
  at io.evitadb.store.kryo.ObservableInput.doWithOnBufferOverflowHandler(ObservableInput.java:926)   <-- note
  at io.evitadb.store.offsetIndex.model.StorageRecord.doReadStorageRecord(StorageRecord.java:740)
  at io.evitadb.store.offsetIndex.model.StorageRecord.read(StorageRecord.java:532)
  at io.evitadb.store.offsetIndex.model.StorageRecord.readWithChecksum(StorageRecord.java:280)
  at io.evitadb.store.wal.supplier.MutationSupplier.readMutation(MutationSupplier.java:261)
  at io.evitadb.store.wal.supplier.MutationSupplier.get(MutationSupplier.java:139)                   <-- Phase 2
```

"Encountered unregistered class ID: 11" means the Kryo stream is being read at a **wrong position** (it reads
a data byte as a class id). It fires **inside `doWithOnBufferOverflowHandler`** — the buffer-refill path.

## 4. My analysis — and the contradiction I cannot resolve

Facts I am fairly confident of (please re-verify, I may be wrong):
- All 46 mutation StorageRecords of transaction 4 are **fully present** on disk. Each individual mutation is
  its own `StorageRecord` = `[len][payload][8-byte record CRC]`; those per-record CRCs are part of the
  content and are **present**. Only transaction 4's **cumulative** 8-byte checksum (read in Phase 3, *after*
  all 46 mutations) was stripped.
- The stream position after the constructor should be exactly at the first mutation of transaction 4: the
  constructor's `readAndRecordTransactionMutation` reads `[4-byte content length] + [TransactionMutation
  StorageRecord]` and the content-length self-check (`contentLength + 4 == leadBytes + walSizeInBytes`,
  `AbstractMutationSupplier` ~line 459) **passed**, so the header was consumed to the correct boundary.
- Therefore reading the 46 mutations "should" just work — the bytes are there and the start position is right.

Yet Phase 2 derails with a class-id error via the **buffer-overflow handler**. So something in the
`ObservableInput` record-boundary state machine is misaligned specifically because the file now ends 8 bytes
early. Relevant machinery (`evita_store/evita_store_key_value/.../kryo/ObservableInput.java`):
- `TAIL_MANDATORY_SPACE = ObservableOutput.TAIL_MANDATORY_SPACE = LONG_SIZE = 8` — the reader reserves 8
  bytes after each record. `require(int)` uses `reserve = readingTail ? 0 : TAIL_MANDATORY_SPACE` (~line 470).
  **8 == the exact number of bytes the test stripped.** This smells like the crux.
- `markStart()` / `markPayloadStart(int, byte)` / `markEnd(byte)`, `expectedLength`, `actualLimit`,
  `readingTail`, `constraintLimitWithRecordLength()`, `handleOverflow(...)`, `doWithOnBufferOverflowHandler`
  (line 926). `seekWithUnknownLength(pos)` sets `expectedLength = -1` / `actualLimit = -1`.
- `StorageRecord.read` (~line 520) does `input.markStart(); readInt(); readByte(); doReadStorageRecord(...)`.

### The production-vs-test mismatch I suspect matters
In the **real** race the 8 checksum bytes are physically **present** on disk (only `length()` is stale);
`RandomAccessFile` reads actual bytes, so a reader that got past the room check would read all 46 mutations
fine — the stale `length()` only gates the *delivery decision*, not the byte reads. The **test physically
removes** those 8 bytes, which is strictly harsher than production: now the reader genuinely has no
`TAIL_MANDATORY_SPACE` after the last record. So the question is whether the record reader can be made to
read transaction 4's content when the file ends exactly at the content boundary (no 8-byte tail).

## 5. Concrete questions for you

1. **Where exactly does the misalignment originate?** Attach JDWP (skill `jdwp-debugging:java-debug`), set an
   exception breakpoint on `com.esotericsoftware.kryo.KryoException` (or a breakpoint at
   `MutationSupplier.readMutation`), run Test 1, and capture: which mutation index fails (first? 46th?), and
   the `ObservableInput` fields at failure (`position`, `limit`, `total`, `expectedLength`, `actualLimit`,
   `readingTail`, `capacity`) plus `filePosition` vs the on-disk offset of the record it is trying to read.
   Repro for a single method:
   `rtk mvn -pl evita_test/evita_functional_tests test -o -Dtest='CatalogWriteAheadLogTest$DryReadVisibilityRaceTests' -Dtest.tag.policy=off`
   (run it under a debug agent; or add `-Dmaven.surefire.debug` and attach).

2. **Is it the `TAIL_MANDATORY_SPACE=8` reservation** interacting with the truncated EOF, or a `handleOverflow`
   / buffer-shift bug, or does the constructor leave `expectedLength`/`actualLimit`/`readingTail` in a state
   that is wrong for the subsequent Phase-2 reads?

3. **What is the minimal, safe fix** so `getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(4, 4)`
   delivers transaction 4 (header + 46 mutations) when only the trailing cumulative checksum is absent —
   without regressing the other 20 tests in these two classes or the broader WAL suite? Candidate directions
   to evaluate (pick/refute with evidence):
   - relax the reader's mandatory-tail reservation for the last record of a known-written requested
     transaction (a targeted `ObservableInput`/read-path change);
   - or read that transaction's content via a path that does not assume an 8-byte tail;
   - or a different framing entirely that still satisfies the test's assertion (must stay non-empty and read
     all elements). If you conclude the test's premise is genuinely unreadable by design, say so explicitly
     with proof, and propose the smallest change that honors the test's intent.

## 6. Please return
- Root cause in 3–8 sentences (with the JDWP evidence: failing record index + the `ObservableInput` field
  values at the fault).
- A specific patch recommendation: file + method + the change, and one or two sentences on why it is safe for
  greedy reads and `ReverseMutationSupplier` (which use `avoidPartiallyFilledBuffer=false`).
- If you applied it: the output of the two-class run above (want **21 run, 0 fail**) and, ideally, a broader
  sanity run `rtk mvn -pl evita_test/evita_functional_tests test -o -Dgroups="wal | storage" -Dtest.tag.policy=off`.

## 7. Pointers
- Failing test: `.../store/wal/CatalogWriteAheadLogTest.java` → `DryReadVisibilityRaceTests`.
- My changes: `.../store/wal/supplier/MutationSupplier.java` (Phase 3), `.../supplier/AbstractMutationSupplier.java`
  (`readAndRecordTransactionMutation`, constructor scan loop, `requiredEndPosition`).
- Reader machinery: `evita_store/evita_store_key_value/.../kryo/ObservableInput.java` (`require`,
  `handleOverflow`, `doWithOnBufferOverflowHandler:926`, `markStart/markPayloadStart/markEnd`,
  `seekWithUnknownLength`), `.../kryo/ObservableOutput.java` (`TAIL_MANDATORY_SPACE`).
- Record format: `.../offsetIndex/model/StorageRecord.java` (`read`, `readWithChecksum`, `doReadStorageRecord`).
- Constants in `evita_store/evita_store_server/.../wal/AbstractMutationLog.java`:
  `TRANSACTION_PREFIX_SIZE=4`, `CUMULATIVE_CRC32_SIZE=8`, `WAL_TAIL_LENGTH=24`.
- Full narrative: `specifications/senesi-upsert-index-corruption/scenarios/bug-03-commit-progress-hang.md`
  (§"Distilled reproduction", "Test 1") and `FIXES.md` §1 item 1a.
