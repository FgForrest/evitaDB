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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH timing benchmark for the OWNER-mode {@link OwnerSortIndex} granular persistence story on branch #760. It mirrors
 * the structure of {@link SortIndexChurnReport} (shared via {@link SortIndexBenchSupport}) so a `dev`-branch mirror can
 * be lined up cell-for-cell. Three operations are timed per scenario:
 *
 * - `loadDeserialize` (P5, the new load cost): deserialize the root + every leaf page from the pre-serialized bytes and
 *   rebuild a live {@link OwnerSortIndex} via {@link OwnerSortIndex#fromPersistedPages} (+ `reconstructSortedRecords`).
 * - `readOrderBy` (P3): obtain the ascending sorted record ids from the live index.
 * - `churnSerialize` (P1, serialization time): serialize the captured incremental-commit parts to bytes.
 *
 * Scenarios are the real `anchor` ean distribution and the `synth_100k` shape replica. Run with `-prof gc` to capture
 * the normalized allocation rate alongside the average time.
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
	 * The measured scenarios (a real anchor + a synthetic 100k shape replica).
	 */
	@Param({"anchor", "synth_100k"})
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
