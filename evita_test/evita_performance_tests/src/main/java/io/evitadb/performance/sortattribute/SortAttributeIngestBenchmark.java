/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.performance.sortattribute;

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import io.evitadb.performance.sortattribute.state.SortAttributeIngestBenchmarkState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end WARM_UP bulk ingest of the sort-attribute shape the issue #1332 profile was taken from.
 *
 * The existing benchmarks reach the sort-attribute insert path only in isolation: `SortIndexTimingBenchmark` drives a
 * bare `SortIndex` and `UnorderedLookupTreeBenchmark` a bare position tree. Both answer "did this data structure get
 * cheaper"; neither answers "did an ingest get cheaper", because neither carries the rest of the write pipeline -
 * entity building, price indexing, facet indexing, storage - that the sort index competes with for wall time. This
 * benchmark exists to size the win against that whole. The attribute shape is the one that produced the profile -
 * 40 sortable `Integer` attributes over 1000 distinct values, 5 near-unique sortable `OffsetDateTime` attributes as
 * the narrow-block control, 30 faceted references - with the block widths scaled down to what a single invocation can
 * afford: at `entityCount = 20 000` the two `distinctValues` settings give blocks 20 and 1000 records wide, against
 * the profiled ~10 000. See
 * {@link SortAttributeIngestBenchmarkState} for the shape and, importantly, for why the batch is built directly
 * rather than through `DataGenerator`.
 *
 * **What this benchmark can and cannot measure.** Its honest deliverable is *allocation*: how much of a full ingest's
 * allocation the branch's allocation-side changes remove - the dropped `InsertionPosition` record in the int-keyed
 * internal-node search, the lazily captured B+ tree cursor path, the allocation-free unordered-array guards -
 * measured cross-jar against the base engine. It is {@link Mode#SingleShotTime} so entities per second is
 * `entityCount / score`, but at the iteration counts it can afford the wall-clock number is far too noisy to trust
 * (this issue saw ±258 % on `ns/op` against ±0.75 % on allocation in the same run), so **read `gc.alloc.rate.norm`,
 * not the score**, and run with `-prof gc`. That figure still includes the per-invocation fixture, so run
 * {@link #fixtureOnlyControl} at the same parameters and subtract it before quoting any percentage - the protocol is
 * spelled out on {@link SortAttributeIngestBenchmarkState}. Note that the CPU-only changes
 * are invisible here: the retained-leaf block search alters no allocation, and resolving the thread's transaction
 * once per positional read removes a single duplicate `ThreadLocal` read that no end-to-end wall-clock measurement
 * can separate from noise - it is kept on strictly-fewer-operations grounds, not on a measured end-to-end delta.
 *
 * Heap is sized explicitly in the fork arguments on purpose. The profiled process ran without `-Xmx`, so JVM
 * ergonomics handed it 23.4 GiB and it sat at 92 % old-gen occupancy with the young generation squeezed to 320 MB;
 * an unsized run here would measure GC pressure rather than insert cost.
 *
 * **It is sized at 16 GiB and not less, because 8 GiB cannot run half this benchmark.** At
 * `distinctValues = 1000` the 40 low-cardinality attributes carry ~1000 value blocks each - roughly 40 000 blocks,
 * each with its own bitmap, against ~800 at `distinctValues = 20`. Under `-Xmx8g` that combination died with
 * `OutOfMemoryError` while building index state (seen in both `FacetIndex.getModifiedStorageParts` and
 * `RoaringBitmapBackedBitmap.fromArray`), so **one of the two declared `distinctValues` values simply did not run**.
 * Note how that failure presents: JMH exits 0, prints the surviving rows, and silently omits the dead combination -
 * a missing row, not an error. Always check the log for `<failure>` before trusting a result set from this class.
 *
 * Raising the heap changes GC behaviour, which is exactly what sizing it explicitly was meant to control - the same
 * `distinctValues = 20` workload reports 136 collections at 8 GiB against 80 at 16 GiB. The A/B pair stays valid
 * because both sides run the same fork arguments, but **figures taken at 8 GiB are not comparable to figures taken
 * at 16 GiB**; do not mix them across runs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(
	value = 1,
	// see BenchmarkForkArgs for why a benchmark booting Evita has to declare these itself
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL,
		// sized explicitly - see the class JavaDoc
		"-Xmx16g"
	}
)
// pinned, not merely defaulted: the state is Scope.Benchmark and its Level.Invocation fixture creates and closes a
// whole Evita instance, so a multi-threaded run would have threads booting and closing the same catalog concurrently
@Threads(1)
public class SortAttributeIngestBenchmark implements EvitaCatalogSetup {

	/**
	 * Bulk-loads `entityCount` products into a freshly created catalog in WARM_UP state.
	 *
	 * This is the profiled path exactly: a catalog that has not yet gone live indexes in place rather than through
	 * per-transaction diff layers, so every `upsertEntity` drives `SortIndex.addRecord` - and therefore
	 * `SortIndexChanges.computePreviousRecord` - once per sortable attribute, 45 times per entity here.
	 *
	 * @param state the freshly booted catalog and its product source
	 * @param bh    sink for the ingested count, so the loop cannot be optimised away
	 */
	@Benchmark
	public void warmUpIngest(SortAttributeIngestBenchmarkState state, Blackhole bh) {
		final List<EntityBuilder> products = state.productBatch();
		state.getEvita().updateCatalog(
			state.getCatalogNameForBenchmark(),
			session -> {
				for (int i = 0; i < products.size(); i++) {
					bh.consume(session.upsertEntity(products.get(i)));
				}
			}
		);
	}

	/**
	 * Ingests **nothing**: it takes the same state as {@link #warmUpIngest}, so it pays the identical
	 * `Level.Invocation` fixture - Evita boot, schema definition, teardown - and then stops.
	 *
	 * That makes its `gc.alloc.rate.norm` a direct measurement of the fixture allocation that `GCProfiler` folds into
	 * every figure this benchmark reports. Run it at the same `entityCount` / `distinctValues` and subtract:
	 * `ingest B/op = warmUpIngest B/op - fixtureOnlyControl B/op`. Without this subtraction the ingest's own
	 * allocation has no honest denominator and any percentage quoted from `warmUpIngest` alone understates the change.
	 *
	 * The batch is touched (not ingested) so the reference cannot be optimised away and the control keeps the same
	 * state-access shape as the measured method.
	 *
	 * @param state the freshly booted catalog and its product source - the subject of this measurement
	 * @param bh    sink keeping the batch reference live
	 */
	@Benchmark
	public void fixtureOnlyControl(SortAttributeIngestBenchmarkState state, Blackhole bh) {
		bh.consume(state.productBatch().size());
	}

}
