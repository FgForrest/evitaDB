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

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Validates the committed-snapshot supplier cache added to {@code SortIndex#getAscendingOrderRecordsSupplier()} /
 * {@code #getDescendingOrderRecordsSupplier()} (see the class javadoc there): every query in a transactional
 * (`ALIVE`) catalog opens and discards its own throwaway {@link Transaction}
 * ({@code EvitaSession.executeInTransactionIfPossible}), which used to defeat the {@code SortIndexChanges} array
 * memoization completely, since a fresh, empty {@code SortIndexChanges} layer was minted per query and never
 * survived to see a second call. The fix moves the memoization onto the {@code SortIndex} instance itself (a
 * committed snapshot is immutable until the next commit produces a new instance — see
 * {@code SortIndex#createCopyWithMergedTransactionalMemory}), reused across every read-only query against that
 * snapshot regardless of how many separate throwaway transactions touch it.
 *
 * Unlike {@link SortIndexResolvePositionsBenchmark#resolvePositions_warmArray} (which forced the array path and
 * pre-warmed the cache once by hand, outside any timed method, as a ceiling estimate), {@link #perQueryThrowawayTransaction}
 * below exercises the REAL production call path end to end: a brand-new {@link Transaction} object per invocation,
 * bound to the thread, then {@code index.getAscendingOrderRecordsSupplier().resolvePositions(...)} exactly as
 * {@code SortedRecordsSupplierFactory}'s callers use it. The FIRST invocation across the whole trial pays the
 * one-time materialization cost; every subsequent invocation — each under a DIFFERENT, never-before-seen
 * {@code Transaction} — must find the arrays already warm, because the cache lives on {@link #index}, not on any
 * transaction. {@link SortIndexResolvePositionsBenchmark#resolvePositions_treeAuto} (measured separately, same
 * fixture) is the valid "before" baseline: the old code never distinguished "transaction open but untouched" from
 * "transaction actively writing", so it was always cold, with or without an open transaction.
 *
 * {@link #perQueryThrowawayTransaction_concurrent4} runs the identical benchmark body under 4 concurrent JMH
 * threads, each minting and discarding its own {@link Transaction} independently, to exercise the new cache
 * fields' only concurrency-relevant property: a benign first-touch race (plain `volatile` fields, immutable
 * `record` payloads — see the field javadoc on {@code SortIndex#cachedAscendingArrays}). Per-invocation scratch
 * buffers ({@code bufferA}/{@code bufferB}) are allocated locally inside the benchmark method (not shared
 * `@State` fields) specifically so the concurrent variant has no cross-thread mutable scratch to race on.
 *
 * Run {@link #main} directly (NOT through JMH) for a one-off JOL retained-memory report instead: how many bytes
 * the new per-snapshot cache holds once warm, since caching the materialized arrays long-term reintroduces
 * exactly the resident cost the lazy tree-backed supplier was built to avoid (see {@code SortedRecordsSupplier}'s
 * class javadoc) — the tradeoff this fix asks the caller to accept, quantified so it can be judged informed rather
 * than assumed free. Run the {@code @Benchmark} methods the same way as the sibling spikes in this package: via
 * {@code org.openjdk.jmh.Main} with this class's simple name as an explicit filter argument, NOT by invoking this
 * class as the JVM main-class (there is deliberately no JMH-forwarding {@code main} here — see above).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class SortIndexCommittedSnapshotCacheBenchmark {

	/**
	 * Matches {@code ArtificialFullDatabaseBenchmarkState.PRODUCT_COUNT} (the real regression's dataset size).
	 */
	private static final int RECORD_COUNT = 100_000;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "order", null);
	private static final long RANDOM_SEED = 42L;

	/**
	 * Selection-to-index-size ratio (K/N), straddling the sparse/dense dispatch threshold - see
	 * {@link SortIndexResolvePositionsBenchmark} for why this sweep matters.
	 */
	@Param({"0.005", "0.01", "0.02", "0.1", "0.5", "1.0"})
	private double selectivity;

	private OwnerSortIndex index;
	private PersistentRoaringBitmap selection;
	private int selectionCount;

	@Setup(Level.Trial)
	public void setUp() {
		// shuffle: record id i+1 is assigned value shuffledValues[i] (a permutation of 0..N-1, fixed seed), so
		// ascending-by-value (walk) order is uncorrelated with ascending-by-id (selection) order - see
		// SortIndexResolvePositionsBenchmark's class javadoc for why an identity mapping is an invalid fixture
		final int[] shuffledValues = new int[RECORD_COUNT];
		for (int i = 0; i < RECORD_COUNT; i++) {
			shuffledValues[i] = i;
		}
		final Random shuffleRandom = new Random(RANDOM_SEED);
		for (int i = RECORD_COUNT - 1; i > 0; i--) {
			final int j = shuffleRandom.nextInt(i + 1);
			final int tmp = shuffledValues[i];
			shuffledValues[i] = shuffledValues[j];
			shuffledValues[j] = tmp;
		}

		this.index = new OwnerSortIndex(Integer.class, ATTRIBUTE_KEY);
		for (int i = 0; i < RECORD_COUNT; i++) {
			this.index.addRecord(shuffledValues[i], i + 1);
		}

		final int k = Math.max(1, (int) Math.round(RECORD_COUNT * this.selectivity));
		final Random random = new Random(RANDOM_SEED);
		final TreeSet<Integer> picked = new TreeSet<>();
		while (picked.size() < k) {
			picked.add(1 + random.nextInt(RECORD_COUNT));
		}
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final int id : picked) {
			writer.add(id);
		}
		this.selection = writer.get();
		this.selectionCount = picked.size();

		// deliberately NO pre-warm here: the whole point is to measure the cache cold, exactly as the first of
		// many production queries against a freshly committed SortIndex would see it
	}

	/**
	 * One simulated query = one throwaway {@link Transaction} rooted at {@link #index}, bound to this thread for
	 * the call and then discarded (never committed - there is nothing to commit, this is a read) - reproducing
	 * {@code EvitaSession.executeInTransactionIfPossible}'s per-query pattern against an ALIVE catalog exactly.
	 */
	@Benchmark
	public void perQueryThrowawayTransaction(@Nonnull Blackhole bh) {
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];
		final Transaction transaction = new Transaction(this.index);
		final PositionResolution resolution = Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				final SortedRecordsProvider provider = this.index.getAscendingOrderRecordsSupplier();
				return provider.resolvePositions(this.selection, this.selectionCount, bufferA, bufferB, null);
			}
		);
		bh.consume(resolution);
	}

	/**
	 * Identical body under 4 concurrent JMH threads - see the class javadoc for what this validates.
	 */
	@Threads(4)
	@Benchmark
	public void perQueryThrowawayTransaction_concurrent4(@Nonnull Blackhole bh) {
		perQueryThrowawayTransaction(bh);
	}

	/**
	 * Isolates the cost of constructing, binding and unbinding one throwaway {@link Transaction} alone - no
	 * {@code SortIndex} call at all - so it can be subtracted from {@link #perQueryThrowawayTransaction} to read
	 * off the actual {@code resolvePositions} cost. Not selectivity-dependent by construction; included in the
	 * sweep only so its flat cost sits next to the K-scaled numbers above for a direct by-eye comparison.
	 */
	@Benchmark
	public void throwawayTransactionOverheadOnly(@Nonnull Blackhole bh) {
		final Transaction transaction = new Transaction(this.index);
		Transaction.executeInTransactionIfProvided(transaction, (Runnable) () -> { });
		bh.consume(transaction);
	}

	/**
	 * One-off JOL retained-memory report (NOT a JMH benchmark - run this class directly). Builds a single
	 * {@link OwnerSortIndex} at production scale, measures its retained size cold, warms the committed-snapshot
	 * cache (both directions) via the real production call path, then measures again - the delta is the resident
	 * cost this fix asks callers to accept per queried direction per live snapshot.
	 */
	public static void main(@Nonnull String[] args) {
		final int[] shuffledValues = new int[RECORD_COUNT];
		for (int i = 0; i < RECORD_COUNT; i++) {
			shuffledValues[i] = i;
		}
		final Random shuffleRandom = new Random(RANDOM_SEED);
		for (int i = RECORD_COUNT - 1; i > 0; i--) {
			final int j = shuffleRandom.nextInt(i + 1);
			final int tmp = shuffledValues[i];
			shuffledValues[i] = shuffledValues[j];
			shuffledValues[j] = tmp;
		}
		final OwnerSortIndex index = new OwnerSortIndex(Integer.class, ATTRIBUTE_KEY);
		for (int i = 0; i < RECORD_COUNT; i++) {
			index.addRecord(shuffledValues[i], i + 1);
		}

		final long coldBytes = GraphLayout.parseInstance(index).totalSize();
		System.out.printf(
			"N=%d cold (no supplier requested yet): %,d bytes retained by the SortIndex%n", RECORD_COUNT, coldBytes
		);

		final Transaction ascendingTxn = new Transaction(index);
		Transaction.executeInTransactionIfProvided(
			ascendingTxn,
			(Runnable) () -> index.getAscendingOrderRecordsSupplier().getRecordPositions()
		);
		final long afterAscendingBytes = GraphLayout.parseInstance(index).totalSize();
		System.out.printf(
			"N=%d after ONE ascending query (cache warm, one direction): %,d bytes retained (+%,d bytes)%n",
			RECORD_COUNT, afterAscendingBytes, afterAscendingBytes - coldBytes
		);

		final Transaction descendingTxn = new Transaction(index);
		Transaction.executeInTransactionIfProvided(
			descendingTxn,
			(Runnable) () -> index.getDescendingOrderRecordsSupplier().getRecordPositions()
		);
		final long afterBothBytes = GraphLayout.parseInstance(index).totalSize();
		System.out.printf(
			"N=%d after BOTH directions queried (cache warm, both directions): %,d bytes retained (+%,d bytes vs cold)%n",
			RECORD_COUNT, afterBothBytes, afterBothBytes - coldBytes
		);
	}
}
