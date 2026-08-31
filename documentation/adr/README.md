# Architectural decision records

Every substantial change to evitaDB — feature, optimization, non-trivial fix, refactor — leaves one
record here explaining **what was done, when, why, and what it relates to**. A record replaces the
assignment/plan it grew out of: plans describe intent that may never have shipped, records describe
what actually happened.

- **Writing one:** [`TEMPLATE.md`](TEMPLATE.md) — two shapes, point decision and campaign.
- **Rules** — when a record is required, which date it carries, what to keep and what to delete:
  `.claude/rules/adr.md`.
- **Starting new work?** Search here first. `rg -l "<area>" documentation/adr/` and read the
  **Consequences & open follow-ups** section of every hit — that is where the known traps and the
  deliberately-deferred work live.

## Conventions in one paragraph

A record is `YYYY-MM-DD-<slug>.md` in this folder — the date is when the decision was **accepted**,
normally the merge date of the implementing PR, so `ls` reads chronologically. When a line of work
produced several decisions *and* evidence worth keeping, it becomes `YYYY-MM-DD-<slug>/` with the
record at `README.md` and the evidence beside it — never more files than earn their place. Every
record opens with YAML front matter (`status`, `kind`, `issues`, `prs`, `areas`, `relates`,
`supersedes`), which is what makes this folder greppable — `rg -l "status: proposed"
documentation/adr/` — and the index below generated rather than hand-maintained.

## Index

Newest first. **Generated** by `tools/generate-adr-index.sh` from each record's front matter — edit
the records, then re-run it; edits made directly to the table are overwritten. `--check` fails when
the table is stale, and the script also reports records with missing front-matter fields or a
filename date that disagrees with `date:`.

<!-- ADR-INDEX:START -->

| Date | Record | Kind | Status | Refs |
|------|--------|------|--------|------|
| 2026-08-31 | [Gate cross-entity histogram removal on a pre-mutation condition pre-pass, not bucket membership](2026-08-31-cross-entity-histogram-removal-pre-pass.md) | fix | accepted | #1467, PR #1468, PR #1469 |
| 2026-08-24 | [Price histogram granularity is decided per accessor, not all-or-nothing across the query](2026-08-24-price-histogram-per-accessor-granularity.md) | fix | accepted | #1433, PR #1435, PR #1436 |
| 2026-08-24 | [Pace gRPC server-streaming producers with a readiness gate, and unblock large file transfers](2026-08-24-grpc-streaming-backpressure-readiness-gate.md) | fix | accepted | #1441, PR #1450, PR #1451 |
| 2026-08-24 | [Keep the reference bundle in step with the reference collection](2026-08-24-refresh-provisional-representative-key.md) | fix | accepted | #1438, #1444, PR #1442, PR #1443 |
| 2026-08-10 | [LocalDateTime is a first-class schema type, and its UTC-anchored Instant encoding lives in the index normalizer](2026-08-10-stored-value-normalization-split.md) | fix | accepted | #1403, PR #1404, PR #1405 |
| 2026-08-05 | [Share schema-derived attribute keys and resolve reference schemas once per run instead of per mutation](2026-08-05-schema-handling-write-path-optimizations.md) | optimization | accepted | #1390, PR #1395 |
| 2026-08-05 | [Never decorate a streaming gRPC channel with RetryingClient](2026-08-05-streaming-calls-must-not-be-retry-decorated.md) | fix | accepted | #1388, PR #1389 |
| 2026-08-04 | [Report HTTP/2 RST_STREAM floods instead of enforcing against them, and turn the Rapid-Reset defence off by default](2026-08-04-http2-connection-teardown-observability.md) | fix | accepted | #1369, PR #1383 |
| 2026-08-04 | [Fail fast on client pool saturation and never run consumer callbacks on the submitting thread](2026-08-04-client-pool-fail-fast-and-cdc-channel-isolation.md) | fix | accepted | #1387, PR #1389 |
| 2026-08-03 | [Readiness discovery-phase probe failures log at DEBUG; only a known-good endpoint failing logs ERROR](2026-08-03-readiness-discovery-log-level.md) | fix | proposed | #1364, PR #1366 |
| 2026-08-03 | [Enforce the test-tag policy from a JUnit PostDiscoveryFilter, because listener exceptions are swallowed](2026-08-03-test-tag-policy-gate-via-post-discovery-filter.md) | fix | accepted | #1374, PR #1382 |
| 2026-08-03 | [Align client/server keep-alive timing and always retry provably-unprocessed gRPC calls](2026-08-03-driver-connection-resilience.md) | fix | accepted | #1367, #1368, PR #1371 |
| 2026-08-02 | [Route release cuts through workflow_dispatch on the release_* branch, not workflow_run from master](2026-08-02-ci-release-pipeline-patch-versioning-fix.md) | infrastructure | accepted | #1359, #1362 |
| 2026-08-02 | [Keep IDEA and Claude formatting in step with a shared .editorconfig and a diff-scoped hook, not Spotless](2026-08-02-editorconfig-formatting-parity.md) | infrastructure | accepted | #1119 |
| 2026-08-01 | [Answer the B+ tree insert-boundary asserts from the descent instead of a captured cursor path](2026-08-01-bplustree-cursor-free-insert-path.md) | optimization | accepted | #1333, PR #1356 |
| 2026-07-31 | [Take the four contained bulk-ingest wins, reject the two that trade an invariant or add complexity, and defer the one worth more than all of them](2026-07-31-bulk-ingest-write-path.md) | optimization | accepted | #1342, PR #1348 |
| 2026-07-27 | [Cut commit-merge latency and write-path allocation by pruning the trunk merge, not inverting it](2026-07-27-write-path-performance-tuning/) | optimization | accepted | #760, PR #1317, PR #1298 |
| 2026-07-24 | [Attribute post-discard traffic to the real discard reason via a side map, not a session tombstone](2026-07-24-traffic-discard-reason-attribution.md) | fix | accepted | #1314, PR #1315 |
| 2026-07-23 | [Export operator-named query labels as Prometheus dimensions via a runtime-configured bag, not fixed compile-time fields](2026-07-23-query-label-prometheus-metrics.md) | feature | accepted | PR #1312 |
| 2026-07-18 | [Publish the previous flush's page baseline before collecting; fail fast on stale twins and suspend the catalog rather than retrying a failed flush](2026-07-18-paged-index-corruption-and-flush-failure-boundary/) | fix | accepted | PR #1293, PR #1284 |
| 2026-07-18 | [On-demand export of the buffered traffic-recording window via a bounded snapshot walk](2026-07-18-traffic-recording-on-demand-export.md) | feature | accepted | #1282, PR #1292 |
| 2026-07-16 | [Fix the gRPC session-cancellation cascade, and move the test-only executor switch off public config into a per-dataset real-pool opt-in](2026-07-16-client-session-cancellation-cascade.md) | fix | accepted | PR #1284 |
| 2026-07-16 | [Carve out granular conflict items from the coarse entity conflict scope](2026-07-16-granular-conflict-carveout.md) | fix | accepted | #503, PR #1287 |
| 2026-07-10 | [Decompose index storage into granular paged parts and slim the index data structures](2026-07-10-more-optimized-data-structures/) | optimization | accepted | #760, #1252, PR #1268 |
| 2026-07-07 | [Vendor RoaringBitmap as a full, renamed copy instead of a thin JPMS subclass](2026-07-07-roaring-bitmap-vendoring.md) | infrastructure | accepted | #1252, PR #1267, PR #1316 |
| 2026-05-27 | [Range-typed source attributes and an array-shaped bucketed() annotation for reference histograms](2026-05-27-range-and-multi-histogram-schema.md) | feature | accepted | #1161, PR #1192, PR #1247, PR #1248, PR #1249 |
| 2026-05-06 | [Expose reference histograms via ReferenceSummary plus a first-class histogramHaving constraint, with group-scoped baseline relaxation](2026-05-06-reference-histogram-statistics.md) | feature | accepted | #8, PR #1136, PR #1150 |
| 2026-04-23 | [Conditionally index per-reference attribute histograms via a dedicated HistogramIndex family](2026-04-23-bucketed-histogram-indexing.md) | feature | accepted | #8, PR #1136 |
| 2026-04-23 | [Conditional (partial) facet indexing via schema-compiled expression triggers, not per-mutation full-entity evaluation](2026-04-23-conditional-facet-indexing.md) | feature | accepted | #8, PR #1136 |
| 2026-03-15 | [Replace JavaDocCopy with an LLM-generated JavaDoc summarizer for QueryConstraints](2026-03-15-javadoc-summarizer.md) | infrastructure | accepted | — |

<!-- ADR-INDEX:END -->
