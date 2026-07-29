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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * B3 of issue #1332 — the end-to-end WARM_UP bulk ingest the profile was taken from.
 *
 * The existing benchmarks reach the sort-attribute insert path only in isolation: `SortIndexTimingBenchmark` drives a
 * bare `SortIndex` and `UnorderedLookupTreeBenchmark` a bare position tree. Both answer "did this data structure get
 * cheaper"; neither answers "did an ingest get cheaper", because neither carries the rest of the write pipeline -
 * entity building, price indexing, facet indexing, storage - that the sort index competes with for wall time. This
 * benchmark exists to size the win against that whole, and its schema is the one that produced the profile:
 * 40 sortable `Integer` attributes over 1000 distinct values, 5 near-unique sortable `OffsetDateTime` attributes as
 * the narrow-block control, 30 faceted references. See {@link SortAttributeIngestBenchmarkState} for the shape and,
 * importantly, for why the batch is built directly rather than through `DataGenerator`.
 *
 * **What this benchmark can and cannot measure.** Its honest deliverable is *allocation*: how much of a full ingest's
 * allocation the allocation-side findings (F3/F4/F6/F7) remove, measured cross-jar against the base engine. It is
 * {@link Mode#SingleShotTime} so entities per second is `entityCount / score`, but at the iteration counts it can
 * afford the wall-clock number is far too noisy to trust (this issue saw ±258 % on `ns/op` against ±0.75 % on
 * allocation in the same run), so **read `gc.alloc.rate.norm`, not the score**, and run with `-prof gc`. Note that the
 * CPU-only findings are invisible here: F1 changes no allocation, and F5 removes a single duplicate ThreadLocal read
 * that no end-to-end wall-clock measurement can separate from noise - F5 is kept as strictly-fewer-operations, not on
 * a measured end-to-end delta.
 *
 * Heap is sized explicitly in the fork arguments on purpose. The profiled process ran without `-Xmx`, so JVM
 * ergonomics handed it 23.4 GiB and it sat at 92 % old-gen occupancy with the young generation squeezed to 320 MB;
 * an unsized run here would measure GC pressure rather than insert cost.
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
		"-Xmx8g"
	}
)
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

}
