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

package io.evitadb.spike;

import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spike.SortIndexBenchSupport.SerializedFull;
import io.evitadb.spike.SortIndexBenchSupport.SerializerBundle;
import io.evitadb.spike.SortIndexBenchSupport.ValueBlock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH timing benchmark for the OWNER-mode {@link OwnerSortIndex} granular persistence story on branch #760. It mirrors
 * the structure of {@link SortIndexChurnReport} (shared via {@link SortIndexBenchSupport}) so a `dev`-branch mirror can
 * be lined up cell-for-cell. Four operations are timed per scenario:
 *
 * - `loadDeserialize` (P5, the new load cost): deserialize the root + every leaf page from the pre-serialized bytes and
 *   rebuild a live {@link OwnerSortIndex} via {@link OwnerSortIndex#fromPersistedPages} (+ `reconstructSortedRecords`).
 * - `readOrderBy` (P3): obtain the ascending sorted record ids from the live index.
 * - `churnSerialize` (P1, serialization time): serialize the captured incremental-commit parts to bytes.
 * - `insertRecord`: add fresh records into an already-populated index - the sort-attribute INSERT path. It carries no
 *   P-number because the P numbering belongs to an earlier report that has no slot for it, and it is the one
 *   measurement here that runs `Mode.SingleShotTime` in milliseconds instead of `Mode.AverageTime` in microseconds.
 *   Its `gc.alloc.rate.norm` is fixture-dominated and must not be read as insert allocation - see its own JavaDoc.
 *
 * Scenarios are the real `anchor` ean distribution, the `synth_100k` shape replica, and the `uniform_1k_100k` /
 * `uniform_1k_1m` pair. The uniform pair exists because `anchor` and `synth_*` are singleton-dominated: nearly every
 * insert into them lands in a width-1 block, takes the single-probe `else` branch and never enters the block binary
 * search at all (see `SortIndexBenchSupport.uniform`), whereas the uniform shapes give evenly-sized wide blocks that
 * do exercise it. Run with `-prof gc` to capture the normalized allocation rate alongside the average time.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class SortIndexTimingBenchmark {

	/**
	 * The measured scenarios: the real `anchor` ean distribution, the `synth_100k` shape replica, and the
	 * `uniform_1k_100k` / `uniform_1k_1m` pair whose evenly-sized wide blocks are the only ones that actually enter
	 * the block binary search.
	 */
	@Param({"anchor", "synth_100k", "uniform_1k_100k", "uniform_1k_1m"})
	private String scenario;

	/**
	 * The live built owner used by `readOrderBy`.
	 */
	private OwnerSortIndex liveOwner;
	/**
	 * Pre-serialized full persisted parts (root + leaf pages) deserialized by `loadDeserialize`.
	 */
	private SerializedFull serializedFull;
	/**
	 * The captured incremental-commit parts serialized by `churnSerialize`.
	 */
	private List<StoragePart> churnParts;
	/**
	 * A reusable serializer bundle for `churnSerialize` (key compressor primed with the churn parts' stream ids).
	 */
	private SerializerBundle churnBundle;

	@Setup(Level.Trial)
	public void setUp() {
		final List<ValueBlock> blocks = SortIndexBenchSupport.blocksFor(this.scenario);
		this.liveOwner = SortIndexBenchSupport.buildOwner(blocks);

		// pre-serialize the FULL persisted emission so loadDeserialize consumes pure bytes -> live index
		final List<StoragePart> fullEmission = SortIndexBenchSupport.emit(this.liveOwner);
		this.serializedFull = SortIndexBenchSupport.serializeFull(fullEmission);

		// capture ONE incremental-commit emission (persist + reload steady state, grow one value 1->2); the parts are
		// pristine (no primary key assigned yet), so the first churnSerialize invocation resolves their ids against this
		// dedicated bundle and every later invocation re-uses the cached, identical ids — churnSerialize then measures pure
		// serialization (no Kryo / compressor construction per invocation)
		this.churnParts = SortIndexBenchSupport.incrementalChurnParts(blocks);
		this.churnBundle = new SerializerBundle();
	}

	/**
	 * P5 — the new cold-load cost: deserialize the root + leaf pages and rebuild the live PAGED owner.
	 */
	@Benchmark
	public void loadDeserialize(@Nonnull Blackhole bh) {
		bh.consume(this.serializedFull.deserialize());
	}

	/**
	 * P3 — the read cost: the ascending sorted record ids supplied to a sorter.
	 */
	@Benchmark
	public void readOrderBy(@Nonnull Blackhole bh) {
		bh.consume(this.liveOwner.getAscendingOrderRecordsSupplier().getSortedRecordIds());
	}

	/**
	 * P1 (time) — serialize the captured incremental-commit parts to bytes.
	 */
	@Benchmark
	public void churnSerialize(@Nonnull Blackhole bh) {
		long total = 0L;
		for (final StoragePart part : this.churnParts) {
			if (part instanceof SortIndexLeafPagePart leaf) {
				leaf.computeUniquePartIdAndSet(this.churnBundle.keyCompressor);
				total += writeBytes(this.churnBundle.leafSerializer, leaf);
			} else if (part instanceof SortIndexStoragePart root) {
				root.computeUniquePartIdAndSet(this.churnBundle.keyCompressor);
				total += writeBytes(this.churnBundle.rootSerializer, root);
			}
		}
		bh.consume(total);
	}

	/**
	 * The INSERT cost: add fresh records into an already-populated sort index. This is
	 * `SortIndex.addRecordInternal`, 57.5 % of busy-thread wall and 18.9 % of allocation during bulk ingest
	 * (issue #1332), and no benchmark in this suite measured it before — the three benchmarks above measure
	 * deserialize / read / serialize, and the inserts that build their fixtures happen in `@Setup`.
	 *
	 * Under a `uniform_*` scenario the inserted records land mid-block and drive the block binary search in
	 * `SortIndexChanges.computePreviousRecord`; under `anchor` / `synth_*` they land in a width-1 block and take the
	 * single-probe `else` branch. Keep both in the param set — they are the guard that a fix tuned for wide blocks
	 * does not regress the narrow-block case that dominates real `ean`-style attributes.
	 *
	 * The trustworthy output here is `ns/op`: {@link Mode#SingleShotTime} times the benchmark method only, so the
	 * per-invocation rebuild stays out of the wall clock. The `gc.alloc.rate.norm` figure is NOT trustworthy -
	 * `InsertState.setUp` is `@Setup(Level.Invocation)` and rebuilds the whole owner before every measured batch
	 * (100 000 records for `uniform_1k_100k`, ~130 000 for `synth_100k`, 1 000 000 for `uniform_1k_1m`) against a
	 * measured `BATCH_SIZE` of 10 000, and JMH's `GCProfiler` snapshots its counters in `beforeIteration` /
	 * `afterIteration`, bracketing the whole iteration including per-invocation fixtures. The reported allocation is
	 * therefore 10:1 to 100:1 fixture and must not be read as the insert path's allocation.
	 */
	@Benchmark
	@BenchmarkMode(Mode.SingleShotTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	public void insertRecord(@Nonnull InsertState state, @Nonnull Blackhole bh) {
		bh.consume(state.insertBatch());
	}

	/**
	 * Per-INVOCATION insert state for {@link #insertRecord}: inserts mutate the index, so the trial-scoped
	 * {@link #liveOwner} cannot be reused and the owner is rebuilt from scratch before every measured batch.
	 *
	 * Two design choices deserve their reasons recorded, because the obvious alternatives silently measure the wrong
	 * thing:
	 *
	 * 1. **The batch spreads across ALL distinct values, not one block.** Hammering a single value would grow that
	 *    one block by the whole batch, so late inserts in a batch would search a far wider block than early ones and
	 *    the benchmark would drift into measuring block growth. Spreading `batchSize` inserts over `distinctValues`
	 *    blocks grows each by `batchSize / distinctValues`, which is noise. It is also exactly what the profiled
	 *    workload does — 40 sortable attributes filled from `numberBetween(0, 1000)`.
	 * 2. **`SingleShotTime` + `Level.Invocation`, not `AverageTime` + `Level.Iteration`.** Under `AverageTime` JMH
	 *    packs as many invocations as fit the time window, so a mutating benchmark accumulates hundreds of thousands
	 *    of inserts within one iteration and the block widths run away from the scenario's definition. One shot per
	 *    iteration keeps every measurement anchored to the scenario's actual shape; the rebuild is paid in `@Setup`
	 *    and is not measured.
	 */
	@State(Scope.Thread)
	public static class InsertState {
		/**
		 * Records inserted per measured batch - large enough to sit well above the timer's resolution, small enough
		 * that spread across the distinct values it barely widens any block.
		 */
		private static final int BATCH_SIZE = 10_000;

		private OwnerSortIndex owner;
		/** The value each batch slot inserts under, pre-picked so no RNG cost lands inside the measured loop. */
		private Serializable[] batchValues;
		private int nextRecordId;

		@Setup(Level.Invocation)
		public void setUp(@Nonnull SortIndexTimingBenchmark benchmark) {
			final List<ValueBlock> blocks = SortIndexBenchSupport.blocksFor(benchmark.scenario);
			this.owner = SortIndexBenchSupport.buildOwner(blocks);
			this.nextRecordId = SortIndexBenchSupport.maxRecordId(blocks) + 1;
			// pre-pick the values OUTSIDE the measured region; a fixed seed keeps every fork comparable
			final Random random = new Random(42);
			this.batchValues = new Serializable[BATCH_SIZE];
			for (int i = 0; i < BATCH_SIZE; i++) {
				this.batchValues[i] = blocks.get(random.nextInt(blocks.size())).value();
			}
		}

		/**
		 * Inserts {@link #BATCH_SIZE} fresh records spread across the scenario's distinct values and returns the
		 * resulting index size, so the whole batch is observable to the blackhole.
		 *
		 * @return the sort index size after the batch
		 */
		int insertBatch() {
			final OwnerSortIndex theOwner = this.owner;
			final Serializable[] values = this.batchValues;
			int recordId = this.nextRecordId;
			for (int i = 0; i < values.length; i++) {
				theOwner.addRecord(values[i], recordId++);
			}
			this.nextRecordId = recordId;
			return theOwner.size();
		}
	}

	private <T extends StoragePart> int writeBytes(
		@Nonnull Serializer<T> serializer, @Nonnull T part
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			serializer.write(this.churnBundle.kryo, output, part);
		}
		return os.size();
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
