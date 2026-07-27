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

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Query-path counterpart to {@link FrontCodedFindKeyBenchmark} (which measures {@code findKeyPosition} in isolation
 * on a reproduced column). This one drives the REAL production {@link TransactionalBucketBPlusTree} — the exact
 * class {@link io.evitadb.index.attribute.GlobalUniqueIndex} uses for its value-&gt;entity tree — through repeated
 * {@code getLongRecordEqualTo} equality lookups, matching a unique String attribute filter
 * ({@code attributeEquals("code", "AB-00042")}) at query time. Block sizes mirror
 * {@code UniqueIndexBPlusTreeSupport}'s constants exactly ({@code VALUE_BLOCK_SIZE=256},
 * {@code MIN_VALUE_BLOCK_SIZE=127}, {@code MIN_INTERNAL_NODE_BLOCK_SIZE=63}) so the tree shape (leaf fan-out, descent
 * depth) matches production, not an arbitrary microbenchmark size.
 *
 * Answers a question the write-path ALIVE-churn alloc profile cannot: that profile's dominant
 * {@code FrontCodedStringColumn} allocator turned out to be {@code keyAt} via {@code SingleLeafBucketCursor.value()}
 * (a cursor read used during mutation), not {@code findKeyPosition} at all - {@code findKeyPosition} showed zero
 * String allocation there already. This benchmark isolates the actual read-only query path (equality lookup only,
 * no mutation, no cursor value materialization) to see whether the byte-compare fast path moves it.
 *
 * Run identically against a baseline without the byte-compare fast path and against a checkout with it to get a
 * clean before/after; run with {@code -prof gc} for B/op alongside ns/op.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class FrontCodedTreeQueryBenchmark {

	/**
	 * Matches {@code UniqueIndexBPlusTreeSupport.VALUE_BLOCK_SIZE} - the actual production leaf block size for
	 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} / {@link io.evitadb.index.attribute.OwnerUniqueIndex}.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	private static final int RECORD_COUNT = 100_000;
	private static final long RANDOM_SEED = 42L;

	@SuppressWarnings({"unchecked", "rawtypes"})
	private TransactionalBucketBPlusTree<String> tree;
	private String[] hitProbes;
	private String[] missProbes;
	private int cursor;

	@Setup(Level.Trial)
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void setUp() {
		final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
		this.tree = TransactionalBucketBPlusTree.withLongPayload(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			String.class, (Comparator) null, factory
		);
		final String[] insertOrder = new String[RECORD_COUNT];
		for (int i = 0; i < RECORD_COUNT; i++) {
			insertOrder[i] = "/category/sub/product-slug-" + String.format("%06d", i);
		}
		for (int i = 0; i < RECORD_COUNT; i++) {
			this.tree.addLongRecord(insertOrder[i], i + 1L);
		}

		// shuffle the lookup sequence so probe order is uncorrelated with insertion/physical order, matching a real
		// query access pattern (not always hitting the same restart block / tree region repeatedly)
		final Random random = new Random(RANDOM_SEED);
		this.hitProbes = insertOrder.clone();
		for (int i = this.hitProbes.length - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final String tmp = this.hitProbes[i];
			this.hitProbes[i] = this.hitProbes[j];
			this.hitProbes[j] = tmp;
		}
		this.missProbes = new String[RECORD_COUNT];
		for (int i = 0; i < RECORD_COUNT; i++) {
			// shares the full prefix with a real key up to the last digit, forcing the same restart-walk depth as a
			// hit before diverging - not a cheap short-circuit miss
			this.missProbes[i] = this.hitProbes[i] + "-x";
		}
		this.cursor = 0;
	}

	@Benchmark
	public void queryEqualTo_hit(@Nonnull Blackhole bh) {
		final String probe = this.hitProbes[this.cursor % this.hitProbes.length];
		this.cursor++;
		bh.consume(this.tree.getLongRecordEqualTo(probe));
	}

	@Benchmark
	public void queryEqualTo_miss(@Nonnull Blackhole bh) {
		final String probe = this.missProbes[this.cursor % this.missProbes.length];
		this.cursor++;
		bh.consume(this.tree.getLongRecordEqualTo(probe));
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
