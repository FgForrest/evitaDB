# senesi upsert index-corruption investigation — master plan & runbook

**Status:** active investigation
**Owner branch context:** reproduced against the built `2026.2.RC1-SNAPSHOT` server jar (canary/SNAPSHOT code) with the `senesi` dataset.
**Goal:** find, isolate, and fix every index-corruption bug that surfaces when a client applies real-world entity upserts to the warm-up-loaded senesi catalog, then prove the catalog is clean.

---

## 1. Background — the incident

A client (`com.fg.eshop.evita.publishing.EvitaIncrementalIndexJob`) re-publishes senesi PRODUCTs in
bulk (many entity upserts per transaction, per-entity skip-on-fail). Several entities fail on the
server with:

```
INVALID_ARGUMENT: ...:117: Sanity check - record not found!
```

thrown at **`FilterIndex.removeRecordFromHistogramAndValueIndex`** (`evita_engine/.../index/attribute/FilterIndex.java:1398`):
a `Remove reference` / attribute-value removal cascade tries to drop a value→record association that
is **not present** in the backing `InvertedIndex` bucket.

Reported failing entities: PRODUCT `18368`, `33786`, `33808` (and likely more). Their mutations mix:
reference removals (`stocks`, `tags`, `relatedProducts`, `bonusVisibilities`, `stockVisibilities`),
`relatedProducts` reorder (remove-all + reinsert with new internal ids), entity-attribute upserts
(`urlInactive`, `changed`, `published`, `relatedFiles`, `senesiOrdering`), and reference-attribute
upserts (`assignmentValidity`).

**The user's specific fear:** the savepoint snapshot/restore mechanism
(`documentation/developer/stm/savepoints.md`, `#569`/`#1252`) has a bug that *damages memory
internals* on rollback, causing follow-up errors on later entities in the same batch.

---

## 2. Findings so far (2026-07-14)

### 2a. Single isolated mutations all COMMIT cleanly

Every one-operation probe against a warm-up-indexed value succeeded (no corruption):

| Probe (single op) | Index touched | Result |
|---|---|---|
| Remove `stocks` refs → drop `quantityOnStock` (BigDecimal, filterable ref-attr) | FilterIndex | ✅ commit |
| Change `assignmentValidity` (DateTimeRange[], filterable ref-attr) | FilterIndex/RangeIndex | ✅ commit |
| Change `changed` (OffsetDateTime, filterable) | FilterIndex + SortIndex | ✅ commit |
| Change `senesiOrdering` (String, sortable-only) | SortIndex | ✅ commit |

**Conclusion: the bugs are combination/volume-dependent, not single-op.** This is *why* the
randomized bulk fuzzer (section 5) is the right tool.

### 2b. Confirmed reproduction — a *second* signature (sort index)

Changing **~29 entity attributes at once** on PRODUCT 18368 in a single upsert throws:

```
GenericEvitaInternalError: INTERNAL: ...: Key is already present in the tree!
```

at **`CumulativeWeightBPlusTree.insert`** (`evita_common/.../dataType/bPlusTree/CumulativeWeightBPlusTree.java:187`)
— the order-statistic (sort) tree. The transaction rolls back. This is the **insertion mirror** of
the incident's removal-side "record not found": a warm-up-indexed value whose live
remove-then-insert fails to find/remove the record, so the reinsert collides.

> Note: the sort throw is `GenericEvitaInternalError`, the filter throw is `EvitaInvalidUsageException`
> — arm JDWP exception breakpoints on **both**.

### 2c. Fuzzer results (seed=1, batch=500, from pristine) — 3 signatures

- **Bug 02 sort-tree "Key is already present"** — 7×/3× per run. **ROOT-CAUSED & isolated** (see
  `scenarios/bug-02`). Isolation via `onlyPk` proved entity `1340209` **fails alone from pristine**
  (`ok=0, perEntityFail=1`), i.e. before any rollback → **warm-up/structural bug, savepoints
  exonerated for this signature.** JDWP root cause: `SortIndexChanges.getValueTree()` builds an
  `Instant`-keyed `CumulativeWeightBPlusTree` from a shared `InvertedIndex` whose buckets store **raw
  `OffsetDateTime`** ordered by `NaturalOrderComparator`; two `published` values with the same instant
  but different offset are distinct buckets that **collapse to one `Instant` key** → duplicate.
- **Bug 03 commit-progress hang** — 1× (intermittent; did not recur under the deterministic re-run).
  "missed completion path in the pipeline"; swept after 60s. Root not yet pinned (need the hung
  thread's stack). See `scenarios/bug-03`. **Strongest remaining savepoint-damage candidate.**
- Injected `INVALID_dropMandatory` noise (expected) — also confirms the **skip-on-fail + savepoint
  rollback path works**: 34 in-txn rollbacks did not poison the 500-op transaction.

### 2d. Validated isolation workflow (the decisive savepoint test)

`SenesiUpsertFuzzer` per-entity seeding is `Random(seed*0x9E3779B97F4A7C15 + pk)` → the SAME ops+values
for a given `(seed,pk)` in batch OR alone. So: run batch from pristine → note a failing pk → reset to
pristine → `onlyPk=<pk>` replays that entity ALONE. **Fails alone ⇒ warm-up/structural (savepoint
exonerated). Commits alone but failed in batch ⇒ savepoint/cross-entity damage.** Proven on Bug 02.

### 2e. Not yet isolated

The **exact incident signature** (`FilterIndex` "record not found") was not surfaced by seed=1. Run
more seeds. Bug 03's hung-thread stack still needs capture.

---

## 3. Validated runbook (commands that work — reuse verbatim)

All paths relative to repo root `/www/oss/evita/evitaDB-dev` unless absolute.

### 3.1 Boot the server (senesi + JDWP)

```bash
cd /www/oss/evita/evitaDB-dev/evita_server && bash run-server.sh   # run backgrounded
```

- All APIs multiplexed on **`localhost:5555`** (gRPC included); TLS relaxed/disabled for clients.
- **JDWP on `localhost:8005`, `suspend=n`** (attach on demand; setting a BP does not hang the VM).
- senesi loads in ~35–40 s; wait for log line `Catalog senesi fully loaded in: NNs`. No migration
  occurs (catalogVersion=97, schemaVersion=79) — a clean boot must NOT print any migration warning.
- Heap: default (~23 G of the 93 G box) is enough for the 2.4 G catalog.

### 3.2 Pristine snapshot + reset-to-clean (CRITICAL)

Every committed mutation is persisted (WAL) **and rebuilds the touched indexes via the live path**,
which *masks* the warm-up-vs-live bugs. Fuzz and isolate **only from pristine**.

```bash
# one-time snapshot (already taken): copy the whole data dir
cp -a /www/oss/evita/evitaDB-dev/data /www/oss/evita/evitaDB-dev/data_snapshot_pristine

# reset procedure (between fuzz runs and before every isolation):
#   1. stop the server JVM (kill the run-server.sh background task / its java pid)
#   2. rm -rf data && cp -a data_snapshot_pristine data
#   3. re-boot via 3.1, wait for "fully loaded"
```

> Do NOT `rm` inside `data/senesi` selectively — restore the whole `data/` dir. Never delete a
> catalog dir via the FS on a *live* server; here we only swap it while the server is stopped.

### 3.3 Repro / probe harness — `SanityCheckRepro`

`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/SanityCheckRepro.java`
(EvitaClient driver; connects to the running server). Modes:

- `dump`   — read-only: prints every attribute + reference-attribute with runtime type.
- `schema` — read-only: prints each Product attribute's `filterable/sortable/unique/localized` flags
  and each reference's attribute flags. **Use this to route an attribute to its index** (filterable →
  FilterIndex "record not found"; sortable → sort tree "key already present"; unique → UniqueIndex).
- `oneattr <name>` — change a single entity attribute to a new value of the same type.
- `fullattrs` — change every String/String[]/OffsetDateTime entity attribute at once (reproduces 2b).
- `changeval` — change `assignmentValidity` on all `categories`/`groups` refs (preserves siblings).
- `removes` — remove all `stocks`/`tags` references.

Build + run:

```bash
# compile (perf module is outside the default reactor -> -P full)
rtk mvn -pl evita_test/evita_performance_tests -P full compile

# one-time: materialize the runtime classpath
rtk mvn -q -pl evita_test/evita_performance_tests -P full dependency:build-classpath \
  -Dmdep.outputFile=/tmp/cp.txt

# run a mode:  <host> <port> <catalog> <pk> <mode> [attr]
CP="evita_test/evita_performance_tests/target/classes:$(cat /tmp/cp.txt)"
java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.math=ALL-UNNAMED -cp "$CP" \
     io.evitadb.spike.SanityCheckRepro localhost 5555 senesi 18368 schema
```

> RTK caveat: `rtk`/proxied `rg` compresses long Java identifiers in *output* (e.g. shows `ln` for a
> class name). Trust source files / `Read`, not the compressed grep echo. Don't pipe `rtk mvn`
> through grep for assertion text — read the raw tail.

### 3.4 JDWP isolation workflow (the money step)

Load the skill first (`/jdwp-debugging:java-debug`), then:

```
jdwp_wait_for_attach(port=8005)
jdwp_set_exception_breakpoint("io.evitadb.exception.EvitaInvalidUsageException", caught=true, uncaught=true)   # "record not found"
jdwp_set_exception_breakpoint("io.evitadb.exception.GenericEvitaInternalError",  caught=true, uncaught=true)   # "key already present"
# start the (minimal) trigger in the background, then:
jdwp_resume_until_event(timeoutMs=60000)
```

At the catch:
- The throw lands inside `Assert.isTrue` (`EvitaInvalidUsageException`) or at the tree
  (`GenericEvitaInternalError`). **Walk up** to the index frame
  (`FilterIndex.removeRecordFromHistogramAndValueIndex` or `CumulativeWeightBPlusTree.insert`) via
  `jdwp_get_stack` + `jdwp_get_locals(frameIndex=N)`.
- **Capture the discriminator:** `value`, `recordId`, `normalizedValue` (filter) / `key` (sort).
  Then check whether `recordId` is findable under the RAW value vs the normalized value:
  present under a *different* key ⇒ normalization/scale mismatch (the real bug); genuinely absent
  from an otherwise-consistent tree ⇒ restore/drift corruption.

Gotchas learned:
- `resume_until_event` is state-aware: a stale suspended thread makes it return the *old* event.
  After finishing with a hit, `jdwp_resume` (and/or `jdwp_reset` then re-arm) before the next
  trigger, or you'll re-read a stale frame (this bit us once — a `:401` re-throw from a prior run).
- `LocalMutationExecutorCollector:401` is `throw this.exception;` — the **re-throw** of the stored
  root cause; the real throw site is deeper/earlier. Don't stop your analysis at the re-throw.
- `builder.setReference(name, pk, consumer)` **replaces** the reference's attributes — dropping
  mandatory siblings (e.g. `categoryPriority`) → `MandatoryAttributesNotProvidedException` noise.
  Preserve existing attributes in the consumer when you only mean to change one.

---

## 4. Reproduction-scenario MD format (deliverable for `bug-hunter-tdd`)

One MD per distinct bug under `scenarios/`. Decision (confirmed with user): **synthetic-minimal is
the primary TDD test, plus a senesi-integration cross-check.** Template:

```
# Bug NN — <short title>

## Signature
<exception class + message + throw site file:line>

## Where it fires
<engine subsystem / index / method, and why>

## Trigger (senesi-integration, faithful)
<exact PK(s), mode/op sequence via SanityCheckRepro or the fuzzer op-log, restore-from-pristine note>

## Synthetic-minimal repro (for the TDD test)
<smallest schema + data + mutation that hits the same code path; observed vs expected>

## Root-cause hypothesis
<warm-up-index vs live-path normalization/scale, or savepoint rollback, with evidence>

## Fix acceptance
<what the failing test asserts; how to verify against senesi (replay from pristine → commits clean)>
```

---

## 5. The fuzzer (step 2) — design

`SenesiUpsertFuzzer` (new spike main). Decisions (confirmed): **also inject invalid ops** to stress
the savepoint-rollback path; scenarios target **both** synthetic + senesi.

Requirements:
- **Seeded RNG** (`--seed`), and **append every op to a log file** (`pk`, op-type, exact values) so
  any failure is replayable. This is non-negotiable — a 500-op random txn is otherwise irreproducible.
- **Bulk of 500 entity upserts in a single transaction**, **per-entity skip-on-fail** (try/catch per
  `upsertEntity`, log the failure + the op that caused it, continue) — mirrors the real job and keeps
  the txn running so multiple distinct failures surface per run.
- **Op menu** (weighted): remove random references; reorder a reference collection (remove-all +
  reinsert); change a random filterable attribute; change a random sortable attribute; change a
  unique attribute; change a reference range-attribute; **+ a small fraction of deliberately-invalid
  ops** (drop a mandatory attribute) to force rollbacks even in clean runs.
- **Run WITHOUT JDWP attached** (invalid ops make exception BPs noisy). JDWP is for *isolation*, not
  the bulk run.

### 5.1 Fuzzing loop

```
reset-to-pristine (3.2) ; boot (3.1)
run SenesiUpsertFuzzer --seed S --batch 500   (no JDWP)
  → client logs each per-entity failure with its op-log slice
on failure(s):
  for each distinct failure signature:
    reset-to-pristine ; boot
    replay ONLY that entity's op sequence from the log  → confirm it still fails (rules out
        snapshot/recover relicts & cross-entity contamination)
    if it fails alone  → warm-up-index / live-path bug   (savepoint exonerated for this one)
    if it PASSES alone but failed in the batch → SAVEPOINT DAMAGE (a prior rolled-back entity
        corrupted shared state) — this is the decisive savepoint test
    delta-minimize the op sequence (bisection) to the smallest failing mutation
    JDWP-isolate (3.4), capture discriminator
    write scenarios/bug-NN-*.md (section 4)
repeat with new seeds "a few times" to broaden coverage
```

### 5.2 Then

Fix each bug (TDD via `bug-hunter-tdd` using the scenario MDs) → verify the fix against senesi by
replaying the failing op-log from pristine (must commit clean) → re-run the fuzzer with fresh seeds
until no new signatures appear.

---

## 6. Housekeeping

- Delete `data_snapshot_pristine` and the spike files (`SanityCheckRepro`, `SenesiUpsertFuzzer`) when
  the investigation closes — they are diagnostic, not production code.
- The spike lives in `evita_performance_tests` (outside the default reactor; `-P full` to build).
- Keep op-logs of confirmed failures under `scenarios/` next to their MD so fixes are re-verifiable.
