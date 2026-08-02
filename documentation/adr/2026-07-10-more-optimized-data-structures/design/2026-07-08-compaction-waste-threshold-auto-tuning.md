# Compaction Cadence Control — Observed-Interval Gate + Max-Waste Override

Status: IMPLEMENTED (v2). Author dialogue: Johnny + Claude.

> **Implementation note (2026-07-08).** All of §5–§8 is implemented on branch
> `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`,
> uncommitted. Notable deviation from the plan text: the per-file clock is not a
> separately-threaded `LongSupplier nowMillis` parameter (§4) — it reuses the
> pre-existing `DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS` static test
> hook (widened from `private` to package-visible `getNowEpochMillis()`), since
> `DefaultEntityCollectionPersistenceService` lives in the same package and is
> always constructed by `DefaultCatalogPersistenceService`. The trigger condition
> was extracted into two shared static methods, `shouldCompact(...)` and
> `isCompactionIntervalElapsed(...)`, used by both trigger sites — this both
> de-duplicates the identical boolean logic and makes gates 1–5 pure unit tests
> instead of integration tests.
>
> **Correctness fix found during implementation (not in the original plan):**
> `StorageOptions`'s canonical constructor now clamps
> `maxWasteActiveShare = Math.min(maxWasteActiveShare, minimalActiveRecordShare)`.
> Without the clamp, any caller passing a custom `minimalActiveRecordShare`
> below the static `DEFAULT_MAX_WASTE_ACTIVE_SHARE` (0.5) through the builder,
> YAML, or the previous-arity constructor — without also setting
> `maxWasteActiveShare` — would silently get `maxWasteActiveShare` pinned at
> 0.5, which is *higher* than their `A`. Since `T=0` makes `intervalElapsed`
> always `true`, the trigger reduces to `active < max(maxWaste, A)`, so
> compaction would fire far more eagerly than before (e.g. `active < 0.5`
> instead of `active < 0.01`) — a real BWC break, not just a documentation gap.
> Regression-guarded in `StorageOptionsTest` (builder path + previous-arity
> constructor path).
>
> Test gate 8 (re-measuring `EvitaWarmUpInsertionTest` compaction counts under
> an enabled interval) was **not** run — it's a long benchmark, left for
> Johnny to trigger explicitly. All other gates (1–7, 9) are implemented; see
> `CompactionCadenceGateTest`, `StorageOptionsTest`, `EvitaServerTest`, and
> `DefaultCatalogPersistenceServiceTest` in `evita_test/evita_functional_tests`.
> Full `storage`-tagged regression sweep: 1299 tests, 0 failures, 0 errors.

> **v2 note — supersedes the append-rate auto-tuner.** The original plan
> auto-tuned the waste threshold `A` per collection by *estimating* the append
> rate (EMA, oscillation mitigation, per-collection `A` recompute). Johnny asked
> for two things: (1) let the operator set an **optimal compaction interval** to
> aim for ("no earlier than 10 minutes unless you exceed the maximal allowed
> waste"), and (2) keep the configuration **backward compatible**. Those two
> asks replace the estimator with a simpler, exact **observed clock gate**: the
> compaction decision already runs on every flush, so we can *observe* elapsed
> time + current active share directly instead of *predicting* them. The old
> estimator design and its critique are preserved at the end (§12) for
> provenance.

**Decision (Johnny, v2):**
1. Drive cadence with a wall-clock **minimum interval `T`** (observed clock
   gate), not an append-rate estimator.
2. Add an explicit **max-waste override** knob that forces compaction before `T`
   when waste gets dangerous.
3. Apply the interval to **both** compaction trigger sites (entity-collection
   *and* catalog-file).
4. All configuration changes must be **backward compatible** — defaults
   reproduce today's behavior exactly.

---

## 1. Problem restated

The append-only offset index (`OffsetIndex`) orphans dead bytes on every record
rewrite. Compaction rewrites the whole file when **both** conditions hold
(`DefaultCatalogPersistenceService.java:1792-1794`, entity-collection flush
path):

```java
newDescriptor.getActiveRecordShare() < this.storageSettings.minimalActiveRecordShare()   // active < A
&& newDescriptor.getFileSize() > this.storageSettings.fileSizeCompactionThresholdBytes()  // file > F
```

where `active = L / file`, `L` = live (active) bytes, `A` =
`minimalActiveRecordShare` (default `0.5`), `F` =
`fileSizeCompactionThresholdBytes` (default `104_857_600`, 100 MB). Both are
**global** (one `StorageOptions` instance, `DefaultCatalogPersistenceService.java:280`).

The pain is **over-compaction**: an over-aggressive `A`/`F` triggers full-file
rewrites far too often. Measured baseline (`EvitaWarmUpInsertionTest`
unique/ALIVE, L≈90 MB, total appends ≈4.85 GB, 969 KB/commit of which
`FilterIndexLeafPagePart` is 91.5 % — see `alive-compaction-per-type-bytes`):

| config | waste | compactions | rewritten |
|---|---:|---:|---:|
| F=100 MB, A=0.8 (old) | 20 % | 194 | ~18 GB |
| F=1 GB, A=0.4 (Johnny's "correct") | 91 % (F binds) | 5 | ~0.45 GB |

The 18 GB was needless churn. The operator today has only two blunt, size-based
knobs and no way to say *"compact this collection roughly every N minutes, but
never let waste run away."* This plan adds exactly that, cheaply.

Tuning cadence amortizes compaction; it does **not** touch the per-commit
866 KB `FilterIndexLeafPagePart` append floor (that needs granularity levers, not
cadence control).

---

## 2. The model (validated — do not re-derive)

Derived from the trigger condition and validated against both runs above
(`compaction-auto-tuner-model` memory):

- Compaction fires at `file* = max(F, L/A)`.
- Steady-state waste `waste* = 1 − min(L/F, A)`.
- Bytes appended between compactions (the waste buffer) = `L · waste/(1−waste)`.
- Wall-clock interval between compactions `t = buffer / r_time = L·waste/((1−waste)·r_time)`,
  where `r_time` = append bytes/second.
- `compaction_I/O = N · L = total_appends · (1−waste)/waste`.
- `peak_file = L / (1−waste)`.

Key consequence: **the existing `A` already implies a per-collection interval**
`t = L / (ρ · r_time)` with `ρ = A/(1−A)`. The operator just can't express it in
time units. This plan lets them set `t` (as `T`, a floor) directly, and reads
the "how much waste" question from the live `active` share at decision time.

### 2.1 The F-binds vs A-binds mechanic (unchanged, still relevant)

| regime | condition | fires at | waste |
|---|---|---|---|
| **F binds** (small collection) | `F > L/A` | `file = F` | `1 − L/F` |
| **A binds** (large collection) | `L/A > F` | `file = L/A` | `1 − A` |

`F` remains a fixed floor that protects small collections (compact at `F`
regardless of anything else). The interval `T` and the max-waste override
described below operate **on top of** the existing `active < A && file > F`
condition — they can only *defer* or *force* within it, never bypass `F`.

---

## 3. Design — observed clock gate + max-waste override

The compaction decision runs on **every flush**, so it can observe wall-clock
time and the live active share directly. No append-rate estimation, no EMA, no
per-collection threshold computation.

### 3.1 The trigger (both sites)

```java
final long   now            = System.currentTimeMillis();
final boolean intervalElapsed = (now - lastCompactionAtMillis) >= minCompactionIntervalMillis;
final double active         = newDescriptor.getActiveRecordShare();
final boolean bigEnough     = newDescriptor.getFileSize() > F;              // F unchanged

final boolean compact = bigEnough && (
        active < maxWasteActiveShare                          // (a) HARD override: waste too high → compact NOW, ignore interval
        || (active < minimalActiveRecordShare && intervalElapsed) // (b) normal: worthwhile waste AND min interval respected
);
```

The three knobs and their roles:

| knob | meaning | default |
|---|---|---|
| `minimalActiveRecordShare` (`A`, **existing**) | "worthwhile waste" threshold — below this active share, compaction is worth doing (once the interval is respected). Meaning **unchanged**. | `0.5` |
| `minCompactionIntervalSeconds` (`T`, **new**) | minimum wall-clock time between compactions of one file. `0` = disabled (compact as soon as worthwhile, i.e. today's behavior). | `0` |
| `maxWasteActiveShare` (**new**) | hard override — when active share drops below this, compact immediately regardless of `T`. This is the "**unless you exceed maximal allowed waste threshold**". | `= minimalActiveRecordShare` |

`lastCompactionAtMillis` is per-file runtime state (§4).

### 3.2 Why this satisfies the request

- **"aim for compaction no earlier than 10 minutes"** → branch (b)'s
  `intervalElapsed` gate. A collection that reaches worthwhile waste (`active < A`)
  after only 2 minutes now waits until `T` before compacting → fewer
  compactions.
- **"unless you exceed maximal allowed waste threshold"** → branch (a). If waste
  blows past `1 − maxWasteActiveShare` before `T` elapses, compact immediately.
- **Genuinely per-collection with global config** — each file carries its own
  `lastCompactionAtMillis` and its own live `active`, so the same global
  `T`/`A`/`maxWaste` produces different real intervals per collection (hot files
  hit the override; cold files wait past `T` until worthwhile). No per-collection
  tuning state, no rate model.
- **Cold-collection over-compaction avoided** — branch (b) still requires
  `active < A`, so a nearly-clean cold file is *not* rewritten at `T` just
  because the clock elapsed. `T` is a floor on the interval, not a fixed period.

### 3.3 Behavior envelope (per file)

Let `t_A` = time for `active` to fall to `A`, `t_max` = time to fall to
`maxWaste` (`t_A ≤ t_max`). Effective compaction interval:

| workload | interval | which branch |
|---|---|---|
| hot (`t_A < T` and `t_max ≤ T`) | `t_max` (< T) | (a) override — waste ran away before `T` |
| warm (`t_A ≤ T < t_max`) | `T` | (b) — waited for the interval |
| cold (`T < t_A`) | `t_A` (> T) | (b) — worthwhile waste reached after `T` |

So `T` is a *lower bound*; the max-waste override is an *upper bound* on waste;
`A` decides worthwhileness in between. Enabling the feature meaningfully requires
`maxWasteActiveShare < minimalActiveRecordShare` (else the window in which `T`
can defer is empty — see §5).

### 3.4 Both trigger sites

- **Entity-collection** (`DefaultCatalogPersistenceService.java:1792-1794`):
  replace the two-term condition with §3.1. `lastCompactionAtMillis` lives on the
  per-collection `DefaultEntityCollectionPersistenceService`.
- **Catalog-file** (`DefaultCatalogPersistenceService.java:2970-2971`): same gate.
  The catalog file is a single file with no per-collection dimension, but the
  wall-clock interval applies to it perfectly well (unlike the old append-rate
  tuner, which was rightly excluded here). `lastCatalogCompactionAtMillis` lives
  on `DefaultCatalogPersistenceService`.

---

## 4. Per-file runtime state (no persistence, no BWC surface)

| owner | field | init | reset |
|---|---|---|---|
| `DefaultEntityCollectionPersistenceService` | `lastCompactionAtMillis` (`long`) | construction time (`System.currentTimeMillis()`) | naturally — a **new** service instance is created after each compaction (`DefaultCatalogPersistenceService.java:1809-1826`), so its field is `now` = compaction time |
| `DefaultCatalogPersistenceService` | `lastCatalogCompactionAtMillis` (`long`) | construction / catalog load time | set to `now` after catalog-file `compact()` |

Notes:
- **No serialized state.** The timestamp is runtime-only. On restart, the first
  post-startup interval is measured from load time — acceptable: a just-loaded
  file either compacts on its first worthwhile flush (if already wasteful) or
  waits `T`. No `serialVersionUID` bump, no header/descriptor change, no BWC
  reader.
- **Bulk-load interaction** — during warm-up bulk insert the file grows fast and
  hits the max-waste override quickly, so `T` correctly stays out of the way; the
  interval binds only in steady state. Matches the old plan's workload-shift note
  without any special handling.
- **No clock injection needed for production**, but tests should be able to
  supply a time source — pass a `LongSupplier nowMillis` (default
  `System::currentTimeMillis`) into the two services so gate behavior is
  deterministic under test (see §7).

---

## 5. Configuration + backward compatibility

Two independent BWC layers — both already exist in the codebase.

### 5.1 Record / binary level — `StorageOptions`

`StorageOptions` (`evita_api/.../configuration/StorageOptions.java`) is a
`record` + builder. Add two components with behavior-preserving defaults:

```java
public static final long   DEFAULT_MIN_COMPACTION_INTERVAL_SECONDS = 0L;   // disabled → today's cadence
public static final double DEFAULT_MAX_WASTE_ACTIVE_SHARE          = DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE; // override == worthwhile → no early-compaction change
```

Touch points (all must default the new fields):
1. New record components `minCompactionIntervalSeconds` (`long`),
   `maxWasteActiveShare` (`double`).
2. `temporary()` factory, the no-arg `StorageOptions()` constructor, the
   `@Nullable`-params constructor.
3. `Builder`: fields + setters + `build()`.
4. Constant + Javadoc for each new `@param`.

**Binary-compatibility catch:** appending record components changes the canonical
constructor signature, breaking any external caller of `new StorageOptions(...)`.
The record already advertises the builder as the compatible path ("Recommended
to use to avoid binary compatibility problems"). To not break existing
positional callers, **keep the previous-arity constructor as an overload** that
delegates to the new canonical one with defaults, rather than only editing the
canonical signature in place. Audit call sites:
`rg -n "new StorageOptions\(" --glob '*.java'` (several tests + `EvitaCatalogSetup`).

### 5.2 YAML level — `EvitaServer`

New keys need **no migration**:
- `mergeYamlFiles` starts from the bundled `evita-configuration.yaml` and merges
  user override files on top, so the final map always carries every key that the
  bundled default carries.
- `UnknownPropertyProblemHandler` (non-strict mode) ignores unknown keys, so a
  new key seen by an *older* binary is harmless (forward compat).

Steps:
1. Add the two keys with defaults to
   `evita_server/src/main/resources/evita-configuration.yaml` under `storage:`:
   ```yaml
   storage:
     minimalActiveRecordShare: 0.5
     fileSizeCompactionThresholdBytes: 100MB
     minCompactionIntervalSeconds: 0      # 0 = compact as soon as worthwhile (default)
     maxWasteActiveShare: 0.5             # active share below which compaction is forced regardless of interval
   ```
2. Old user override files (which merge onto the bundled default) inherit the new
   keys automatically — nothing to change on their side.

The `replaceDeprecatedSettings` / `migrateExportSettings` hooks in `EvitaServer`
are only for **renamed / relocated** keys. We deliberately **do not rename**
`minimalActiveRecordShare` (its meaning is preserved as "worthwhile waste"), so
no migration entry is needed.

**Time-format note:** the YAML `SpecialConfigInputFormatsHandler` parses `10m` →
**600 (seconds)** for an `int`/`long` target (its time base unit is seconds).
Hence the field is named `…Seconds`: `minCompactionIntervalSeconds: 10m` → `600`.
Do **not** name it `…Millis` — that would inherit the existing
`queryTimeoutInMilliseconds: 5s` unit quirk (parses to `5`, not `5000`).

### 5.3 Default behavior is byte-for-byte today's behavior

With `T = 0` and `maxWasteActiveShare = minimalActiveRecordShare`, §3.1 reduces
to `active < A && file > F` — identical to the current trigger. The feature is
strictly opt-in: an operator enables it by setting `minCompactionIntervalSeconds
> 0` **and** `maxWasteActiveShare < minimalActiveRecordShare`. Document this
coupling in `configure.md` (both new keys, with the "unless you exceed max waste"
semantics and the note that the override must be looser than the worthwhile
threshold for the interval to bind).

---

## 6. Documentation changes (`configure.md`)

Add two `<dt>` entries in the storage section after
`fileSizeCompactionThresholdBytes`:

- **`minCompactionIntervalSeconds`** — Default `0`. Minimum wall-clock time
  between two compactions of the same data file. `0` disables the gate
  (compaction happens as soon as the active-record share drops below
  `minimalActiveRecordShare` and the file exceeds
  `fileSizeCompactionThresholdBytes`, as before). When set (e.g. `10m`),
  compaction of a file happens no earlier than this interval **unless** the
  active-record share drops below `maxWasteActiveShare` (runaway waste), in which
  case it compacts immediately. Use it to cap compaction I/O on hot collections.
- **`maxWasteActiveShare`** — Default equals `minimalActiveRecordShare`. The
  active-record share below which compaction is forced **regardless** of
  `minCompactionIntervalSeconds`. Must be set lower than `minimalActiveRecordShare`
  for the interval to have any effect (it defines the "emergency" waste ceiling
  that overrides the interval).

Also refresh the `storage:` snippet at the top of `configure.md`.

---

## 7. Test gates

1. **Trigger truth table (unit)** — for `(active, elapsed, file)` combinations
   across the three branches: override fires before `T`; normal fires at/after
   `T`; nothing fires when `file ≤ F`; nothing fires when `active ≥ A` even after
   `T`. Inject a `LongSupplier` clock.
2. **BWC-default equivalence** — with `T = 0`, `maxWaste = A`, the new trigger is
   equivalent to the old `active < A && file > F` for random `(active, file)`
   (property test). This is the load-bearing BWC gate.
3. **Interval deferral** — hot file reaches `active < A` before `T`: verify no
   compaction until `T` (or until `active < maxWaste`, whichever first).
4. **Override precedence** — `active < maxWaste` before `T`: verify immediate
   compaction (interval ignored).
5. **Cold non-over-compaction** — `T` elapsed but `active ≥ A`: verify **no**
   compaction (guards against rewriting near-clean files).
6. **Timestamp lifecycle** — post-compaction the new
   `DefaultEntityCollectionPersistenceService` has `lastCompactionAtMillis ≈ now`;
   catalog path updates `lastCatalogCompactionAtMillis`.
7. **Config parsing** — `minCompactionIntervalSeconds: 10m` deserializes to
   `600`; omitted keys fall back to defaults; unknown-key tolerance intact.
8. **Regression: `EvitaWarmUpInsertionTest`** — with an enabled interval (e.g.
   `T=10m`, `maxWaste=0.1`), compaction count and rewritten bytes drop toward the
   5-compaction / 0.45 GB envelope; with defaults, results are unchanged from
   today.
9. **Both sites covered** — an integration test that the catalog-file path
   (line 2970) honors the interval too.

---

## 8. Step-by-step

1. **`StorageOptions`** (§5.1): add `minCompactionIntervalSeconds`,
   `maxWasteActiveShare` with defaults; update factory / constructors / builder;
   add a previous-arity delegating constructor for binary compat; fix call sites.
2. **Bundled YAML + docs** (§5.2, §6): add keys to `evita-configuration.yaml`;
   document both in `configure.md` + refresh the snippet.
3. **Runtime state** (§4): add `lastCompactionAtMillis` to
   `DefaultEntityCollectionPersistenceService` and
   `lastCatalogCompactionAtMillis` to `DefaultCatalogPersistenceService`;
   thread a `LongSupplier nowMillis` (default `System::currentTimeMillis`) for
   testability; set on construction and after each `compact()`.
4. **Entity-collection trigger** (`:1792-1794`): replace with the §3.1 gate.
5. **Catalog-file trigger** (`:2970-2971`): replace with the §3.1 gate using the
   catalog timestamp.
6. **Tests** (§7 gates 1–9).
7. **Re-measure** — run `EvitaWarmUpInsertionTest` with defaults (expect no
   change) and with an enabled interval (expect fewer compactions); log per-file
   compaction interval + branch taken for diagnostics.

---

## 9. Risks

| risk | severity | mitigation |
|---|---|---|
| **BWC regression** — new trigger diverges from old under defaults | **high** | gate 2 (property-based equivalence with `T=0`, `maxWaste=A`); defaults chosen to collapse §3.1 to the old condition exactly |
| **Binary break** — added record components break positional `new StorageOptions(...)` callers | med | keep previous-arity delegating constructor; audit + fix call sites (§5.1) |
| **Runaway waste if operator sets huge `T`** | med (data-size, not integrity) | max-waste override (branch a) always bounds waste at `1 − maxWasteActiveShare` regardless of `T`; document that `maxWasteActiveShare` is the real safety cap |
| **Misconfiguration** — `maxWasteActiveShare ≥ minimalActiveRecordShare` silently disables the interval | low | validate at config load: if `T > 0` and `maxWaste ≥ A`, log a warning that the interval will not bind; do **not** throw (BWC default is exactly this and must stay valid) |
| **Clock skew / non-monotonic wall clock** | low | interval is a coarse minutes-scale floor; `System.currentTimeMillis()` jumps are tolerable. If paranoid, use `System.nanoTime()` deltas — but nanoTime has no cross-restart meaning; the runtime-only, reset-on-compaction field makes wall-clock fine |
| **Restart resets the interval clock** | low | by design (§4); first post-restart interval measured from load; bounded by the max-waste override anyway |
| **`getActiveRecordShare` under compression** | low | the gate reads exactly the quantity the trigger already used (`OffsetIndex.java:1211`); self-consistent, and the gate never needs absolute `L` |

---

## 10. Why this is simpler than the v1 estimator (summary)

| concern | v1 append-rate tuner | v2 observed gate |
|---|---|---|
| append-rate estimation | required (EMA over N compactions) | **none** — observe elapsed time directly |
| per-collection `A` computation | `A = clamp(ρ/(1+ρ)/…)` per compaction | **none** — global thresholds, per-file timestamp |
| oscillation / hysteresis | needed (§v1-10) | **none** — no computed threshold to oscillate |
| post-compaction rate re-convergence | one bad cycle (§v1-7.4) | **none** — new service's timestamp is `now` |
| catalog-file path | excluded (no per-collection dim) | **included** — interval has no such requirement |
| BWC config surface | claimed "no `StorageOptions` change" (false once an interval is configurable) | two additive fields, defaults reproduce today |

---

## 11. Appendix — provenance

Model derived and validated in `compaction-auto-tuner-model` memory (session
`4c7793f7`). Per-type append breakdown in `alive-compaction-per-type-bytes`.
Code references verified against `760-more-optimized-data-structures`
(commit `764e74442`): entity-collection trigger
`DefaultCatalogPersistenceService.java:1792-1794`; catalog-file trigger
`:2970-2971`; post-compaction service creation `:1809-1826`; `StorageOptions`
defaults `DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE=0.5`,
`DEFAULT_MINIMAL_FILE_SIZE_COMPACTION_THRESHOLD=104_857_600`; YAML time parsing
`SpecialConfigInputFormatsHandler` (base unit = seconds); config BWC machinery
`EvitaServer.mergeYamlFiles` + `UnknownPropertyProblemHandler`.

---

## 12. Appendix — v1 append-rate auto-tuner (superseded) + its critique

The v1 design auto-tuned `A` per collection from an estimated append rate. It is
kept here because its *model* (§2) is still used, and because the critique
motivated the v2 pivot.

**v1 core:** `A_target = ρ/(1+ρ)` (I/O-ratio target) or `A_freq = L/(C·r + L)`
(frequency target), clamped to `[A_min, configured_A]`, "only loosen", recomputed
each compaction with EMA smoothing, entity-collection-only.

**Problems found (drove the v2 redesign):**

1. **§v1-5.1's headline target isn't per-collection.** `A = ρ/(1+ρ)` is a global
   constant; the plan admits it and rationalizes it. Under it, the entire
   per-collection state machine is dead weight — a single global `A` behaves
   identically. Only the frequency variant is genuinely per-collection.
2. **`A` as "ceiling, only loosen" contradicts "maximal allowed waste."** v1
   treats configured `A` as the *most aggressive* bound; Johnny wants it as a
   *worthwhile* bound with a separate hard cap. Opposite roles → operator
   surprise.
3. **A single threshold can't express "aim for `T` unless waste exceeds max."**
   With one waste level an interval can only *defer*, pushing waste past the cap.
   Two waste levels are required — v2 adds `maxWasteActiveShare`.
4. **No wall-clock tracking.** v1 counted commits, not time.
5. **Estimator complexity is unnecessary.** EMA, oscillation mitigation, and
   post-compaction rate re-convergence all exist to avoid a two-line clock check.
6. **Catalog-file path excluded** — justified for the estimator (no
   per-collection dimension) but a needless exclusion for a wall-clock interval.
7. **"No `StorageOptions` change" BWC claim is false** once the interval is
   operator-configurable.
8. **Cold-collection over-compaction** if implemented naively — v2 keeps the
   `active < A` worthwhile floor to prevent it.
