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

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookupTree.PositionCursor;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Pinpointed isolation of {@link SortedRecordsProvider#resolvePositions} on a plain scalar
 * {@link OwnerSortIndex} attribute, built directly (no {@code EvitaSession}/{@code Catalog}/{@code Transaction}
 * overhead — see {@code io.evitadb.spike.SortIndexTimingBenchmark} for the sibling pattern this mirrors).
 *
 * Investigates the {@code attributeFiltering}/{@code attributeAndHierarchyFiltering} JMH regression
 * (docs/reports/2026-07-09-write-and-query-throughput-remeasure.md, &#167;4.1): a CPU profile of that
 * benchmark showed 37-41% of query time inside {@code SortedRecordsSupplier.resolvePositionsByDenseWalk} /
 * {@code RoaringArray.<init>}. Code reading confirmed the underlying mechanism: every query obtains a
 * brand-new {@code SortedRecordsSupplier} wrapper (correctly, since its seeker is a stateful cursor), and
 * that wrapper's own warm-check (whether its {@code recordPositions}/{@code allRecords} fields are non-null)
 * is always false at check time, since nothing populates them before {@code resolvePositions} runs. Whether a
 * fix can help depends on whether the underlying {@code SortIndexChanges} cache those fields would otherwise
 * read from can itself survive across queries — in a live/transactional catalog (confirmed the mode this
 * benchmark's dataset uses, via {@code goLiveAndClose()}), it cannot: {@code EvitaSession.executeInTransactionIfPossible}
 * opens and discards a fresh {@code Transaction} (and therefore a fresh {@code SortIndexChanges}) per query.
 *
 * Three benchmark methods give two independent fix ceilings from one run:
 * <ul>
 *     <li>{@link #resolvePositions_warmArray} — a fresh per-call wrapper (matching production), but
 *     {@link ForcedSortResolution#ARRAY} forces the array merge-walk, and the underlying {@code SortIndexChanges}
 *     cache is pre-warmed once in {@code @Setup} and never invalidated (no mutation happens after setup) — the
 *     ceiling if the array cache could survive across queries.</li>
 *     <li>{@link #resolvePositions_treeAuto} — unforced, cost-based dispatch: exactly today's production
 *     behavior (always the cold walk for a dense selection, since the wrapper is always fresh).</li>
 *     <li>{@link #notFoundCloneBaseline} — isolates the {@code selectedRecordIds.clone()} cost alone (the
 *     not-found hand-off seed inside {@code resolvePositionsByDenseWalk}), so its share of
 *     {@link #resolvePositions_treeAuto}'s cost can be read off by subtraction — the ceiling of a smaller,
 *     streaming-aligned fix that avoids the clone instead of caching the arrays.</li>
 * </ul>
 *
 * The selectivity sweep straddles {@code SortedRecordsSupplier.TREE_PATH_SELECTIVITY_DIVISOR} (64): 0.005 and
 * 0.01 stay under the {@code K <= N/64} sparse threshold at N=100k (sparse tree probe), 0.02 crosses just
 * above it, 0.1/0.5/1.0 are unambiguously dense.
 *
 * A first run confirmed {@link #resolvePositions_warmArray} beats {@link #resolvePositions_treeAuto} 2-5x once
 * dense, but {@link #notFoundCloneBaseline} came back at ~0.006us/op flat across every K — the not-found clone
 * (already fixed by the RoaringBitmap frozen-array COW work) is NOT where the dense-walk cost lives. The four
 * {@code denseWalk_*} methods below decompose {@code resolvePositionsByDenseWalk}'s remaining primitives
 * directly (reproduced here since the method itself is private) to find which O(N)/O(K) piece dominates:
 * <ul>
 *     <li>{@link #denseWalk_cursorOnly} — just the O(N) position-cursor walk, nothing else.</li>
 *     <li>{@link #denseWalk_cursorPlusContains} — the walk plus the O(N) selection-membership check paid on
 *     every position (not just matches) — the second O(N) primitive the dense walk pays.</li>
 *     <li>{@link #denseWalk_maskWriterOnly} — the O(K) result-mask construction alone, walk excluded.</li>
 *     <li>{@link #denseWalk_notFoundRemoveOnly} — the O(K) not-found shrink alone (K {@code remove()} calls
 *     against a pre-cloned selection), walk excluded.</li>
 * </ul>
 * Their sum should reproduce {@link #resolvePositions_treeAuto}'s dense-regime cost; whichever term dominates
 * is the actual target for a streaming-aligned fix (one that avoids materializing the arrays) versus caching.
 *
 * A second run found the walk emitted record ids in strictly ascending order (the fixture assigned value `i` to
 * record id `i+1`, so value order == id order) — the best case for {@code contains()} and invalid as a stand-in
 * for production, where the two orders are unrelated. The fixture below instead assigns each record id a
 * <b>shuffled</b> value (fixed seed), so the position-cursor walk emits record ids in an order uncorrelated with
 * {@link #selection}'s own ascending-id order, matching a real attribute. That run found {@code contains()}
 * dominates at low-mid selectivity and {@code notFound.remove()} dominates at high selectivity — two distinct,
 * streaming-compatible (no array materialization) fix candidates, each isolated below against the corrected
 * fixture:
 * <ul>
 *     <li>{@link #denseWalk_bulkAndNot} — replaces the O(K) {@code remove()} loop
 *     ({@link #denseWalk_notFoundRemoveOnly}) with K cheap writer appends into a matched-ids bitmap, followed by
 *     one bulk {@link PersistentRoaringBitmap#andNot} — a container-level pass instead of K individual,
 *     COW-mutating removes.</li>
 *     <li>{@link #containsLoop_optimiseForArrays} / {@link #containsLoop_optimiseForRuns} — the same walk+contains
 *     loop as {@link #denseWalk_cursorPlusContains}, but against copies of {@link #selection} built via
 *     {@code RoaringBitmapWriter}'s {@code optimiseForArrays()}/{@code optimiseForRuns()} wizard hints instead of
 *     the production {@code buildWriter()}'s {@code constantMemory()} default — an isolated A/B testing whether
 *     forcing a different container representation at construction time changes {@code contains()}'s per-call
 *     cost, without reimplementing the walk.</li>
 * </ul>
 *
 * Run with {@code -prof gc} to capture the normalized allocation rate alongside the average time.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class SortIndexResolvePositionsBenchmark {

	/**
	 * Matches {@code ArtificialFullDatabaseBenchmarkState.PRODUCT_COUNT} (the real regression's dataset size).
	 */
	private static final int RECORD_COUNT = 100_000;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "order", null);
	private static final long RANDOM_SEED = 42L;

	/**
	 * Selection-to-index-size ratio (K/N), straddling the sparse/dense dispatch threshold.
	 */
	@Param({"0.005", "0.01", "0.02", "0.1", "0.5", "1.0"})
	private double selectivity;

	private OwnerSortIndex index;
	private PersistentRoaringBitmap selection;
	private int selectionCount;
	private int[] bufferA;
	private int[] bufferB;

	/**
	 * A standalone array reproducing the same position &lt;-&gt; record-id mapping as {@link #index}'s internal
	 * {@code sortedRecords} — built directly because {@code SortIndex.sortedRecords} is package-private to
	 * {@code io.evitadb.index.attribute} and inaccessible from this module. Record ids are shuffled across
	 * positions (see class javadoc) so the walk order is uncorrelated with {@link #selection}'s ascending-id
	 * order, matching production.
	 */
	private TransactionalUnorderedIntArray plainArray;
	/**
	 * The selection's record ids, in ascending ID order (matching {@link #selection}'s own iteration order) —
	 * for {@link #denseWalk_notFoundRemoveOnly}, whose {@code remove()} calls are order-independent.
	 */
	private int[] matchedRecordIds;
	/**
	 * The selection's record ids, in ascending WALK order (position / sort-value order — scrambled relative to
	 * {@link #matchedRecordIds}) — for {@link #denseWalk_bulkAndNot}, whose bitmap-construction step is
	 * order-sensitive for performance (and, on a writer without radix-sort fallback, potentially correctness).
	 */
	private int[] matchedRecordIdsInWalkOrder;
	/**
	 * Each selected record id's position in {@link #plainArray}'s (shuffled) order — for
	 * {@link #denseWalk_maskWriterOnly}.
	 */
	private int[] matchedPositions;
	/**
	 * A copy of {@link #selection} built via {@code optimiseForArrays()} — forces {@code ArrayContainer}
	 * representation regardless of density, for the {@link #containsLoop_optimiseForArrays} A/B.
	 */
	private PersistentRoaringBitmap selectionOptimiseArrays;
	/**
	 * A copy of {@link #selection} built via {@code optimiseForRuns()} — biases toward run/bitmap representation,
	 * for the {@link #containsLoop_optimiseForRuns} A/B.
	 */
	private PersistentRoaringBitmap selectionOptimiseRuns;

	@Setup(Level.Trial)
	public void setUp() {
		// shuffle: record id i+1 is assigned value shuffledValues[i] (a permutation of 0..N-1, fixed seed), so
		// ascending-by-value order (the walk order) is uncorrelated with ascending-by-id order (the selection's
		// own order) — see class javadoc on why the identity mapping used in the first pass was invalid
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

		// derive the (position -> recordId) and (recordId -> position) mappings by sorting on the same shuffled
		// values, so `plainArray` reproduces exactly the position order `index` establishes internally
		final Integer[] recordIndexByValue = new Integer[RECORD_COUNT];
		for (int i = 0; i < RECORD_COUNT; i++) {
			recordIndexByValue[i] = i;
		}
		Arrays.sort(recordIndexByValue, Comparator.comparingInt(i -> shuffledValues[i]));
		final int[] delegate = new int[RECORD_COUNT];
		final int[] positionOfRecordId = new int[RECORD_COUNT];
		for (int position = 0; position < RECORD_COUNT; position++) {
			final int i = recordIndexByValue[position];
			delegate[position] = i + 1;
			positionOfRecordId[i] = position;
		}
		this.plainArray = new TransactionalUnorderedIntArray(delegate);

		final int k = Math.max(1, (int) Math.round(RECORD_COUNT * this.selectivity));
		final Random random = new Random(RANDOM_SEED);
		final TreeSet<Integer> picked = new TreeSet<>();
		while (picked.size() < k) {
			picked.add(1 + random.nextInt(RECORD_COUNT));
		}
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final RoaringBitmapWriter<PersistentRoaringBitmap> arrayWriter = RoaringBitmapWriter.writer().optimiseForArrays().get();
		final RoaringBitmapWriter<PersistentRoaringBitmap> runWriter = RoaringBitmapWriter.writer().optimiseForRuns().get();
		this.matchedRecordIds = new int[picked.size()];
		this.matchedPositions = new int[picked.size()];
		int idx = 0;
		for (final int id : picked) {
			writer.add(id);
			arrayWriter.add(id);
			runWriter.add(id);
			this.matchedRecordIds[idx] = id;
			this.matchedPositions[idx] = positionOfRecordId[id - 1];
			idx++;
		}
		this.selection = writer.get();
		this.selectionOptimiseArrays = arrayWriter.get();
		this.selectionOptimiseRuns = runWriter.get();
		this.selectionCount = picked.size();

		// the matched record ids in WALK order (ascending position, i.e. ascending sort-VALUE order) —
		// scrambled relative to ascending id order, exactly as `resolvePositionsByDenseWalk` would discover them;
		// `matchedRecordIds` above (ascending id order, from the TreeSet) is NOT a valid stand-in for this: an
		// ascending-only `RoaringBitmapWriter` fed from `matchedRecordIds` would hide the real-world cost (or,
		// for a wider id-range production index than this 100k-record fixture, could hit the writer's
		// out-of-order fallback repeatedly) — see denseWalk_bulkAndNot
		this.matchedRecordIdsInWalkOrder = new int[picked.size()];
		int walkOrderIdx = 0;
		for (int position = 0; position < RECORD_COUNT; position++) {
			final int recordId = delegate[position];
			if (this.selection.contains(recordId)) {
				this.matchedRecordIdsInWalkOrder[walkOrderIdx++] = recordId;
			}
		}

		this.bufferA = new int[512];
		this.bufferB = new int[512];

		// pre-warm the SHARED SortIndexChanges-level materialized-array cache once, outside any timed method;
		// `this.index` is never mutated afterwards, so the cache is never invalidated for the rest of the trial
		final SortedRecordsProvider warmup = this.index.getAscendingOrderRecordsSupplier();
		warmup.getRecordPositions();
		warmup.getAllRecords();
	}

	/**
	 * Ceiling 1 (caching-fix ceiling): cost if the materialized-array cache survived across queries.
	 */
	@Benchmark
	public void resolvePositions_warmArray(@Nonnull Blackhole bh) {
		final SortedRecordsProvider provider = this.index.getAscendingOrderRecordsSupplier();
		final PositionResolution resolution = provider.resolvePositions(
			this.selection, this.selectionCount, this.bufferA, this.bufferB, ForcedSortResolution.ARRAY
		);
		bh.consume(resolution);
	}

	/**
	 * Today's production cost: a fresh per-call wrapper, unforced cost-based dispatch.
	 */
	@Benchmark
	public void resolvePositions_treeAuto(@Nonnull Blackhole bh) {
		final SortedRecordsProvider provider = this.index.getAscendingOrderRecordsSupplier();
		final PositionResolution resolution = provider.resolvePositions(
			this.selection, this.selectionCount, this.bufferA, this.bufferB, null
		);
		bh.consume(resolution);
	}

	/**
	 * Ceiling 2 (streaming-fix ceiling) input: the not-found hand-off seed clone alone, isolated.
	 */
	@Benchmark
	public void notFoundCloneBaseline(@Nonnull Blackhole bh) {
		bh.consume(this.selection.clone());
	}

	/**
	 * Decomposition term 1: the O(N) position-cursor walk alone, nothing else. Independent of {@code selectivity}
	 * by construction (walks every position regardless of K) — included in the sweep only so its flat cost is
	 * directly visible next to the K-scaled terms below.
	 */
	@Benchmark
	public void denseWalk_cursorOnly(@Nonnull Blackhole bh) {
		final PositionCursor cursor = this.plainArray.forwardPositionCursor();
		long acc = 0L;
		for (int position = 0; position < RECORD_COUNT; position++) {
			acc += cursor.recordAt(position);
		}
		bh.consume(acc);
	}

	/**
	 * Decomposition term 2: the walk plus the O(N) selection-membership check paid on every position (the second
	 * O(N) primitive {@code resolvePositionsByDenseWalk} pays, not gated by K). Uses {@link #selection} exactly
	 * as production's {@code buildWriter()} constructs it.
	 */
	@Benchmark
	public void denseWalk_cursorPlusContains(@Nonnull Blackhole bh) {
		bh.consume(walkAndCountContains(this.selection));
	}

	/**
	 * Container-representation A/B, variant 1: the same walk+contains loop against a copy of {@link #selection}
	 * built with {@code optimiseForArrays()} instead of production's {@code constantMemory()} default.
	 */
	@Benchmark
	public void containsLoop_optimiseForArrays(@Nonnull Blackhole bh) {
		bh.consume(walkAndCountContains(this.selectionOptimiseArrays));
	}

	/**
	 * Container-representation A/B, variant 2: the same walk+contains loop against a copy of {@link #selection}
	 * built with {@code optimiseForRuns()} instead of production's {@code constantMemory()} default.
	 */
	@Benchmark
	public void containsLoop_optimiseForRuns(@Nonnull Blackhole bh) {
		bh.consume(walkAndCountContains(this.selectionOptimiseRuns));
	}

	private int walkAndCountContains(@Nonnull PersistentRoaringBitmap sel) {
		final PositionCursor cursor = this.plainArray.forwardPositionCursor();
		int matched = 0;
		for (int position = 0; position < RECORD_COUNT; position++) {
			final int recordId = cursor.recordAt(position);
			if (sel.contains(recordId)) {
				matched++;
			}
		}
		return matched;
	}

	/**
	 * Decomposition term 3: the O(K) result-mask construction alone (the walk's matches are precomputed in
	 * {@code @Setup}, so no walk/contains cost is included).
	 */
	@Benchmark
	public void denseWalk_maskWriterOnly(@Nonnull Blackhole bh) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
		for (final int position : this.matchedPositions) {
			mask.add(position);
		}
		bh.consume(mask.get());
	}

	/**
	 * Decomposition term 4: the O(K) not-found shrink alone (K {@code remove()} calls against a pre-cloned
	 * selection), walk excluded.
	 */
	@Benchmark
	public void denseWalk_notFoundRemoveOnly(@Nonnull Blackhole bh) {
		final PersistentRoaringBitmap notFound = this.selection.clone();
		for (final int recordId : this.matchedRecordIds) {
			notFound.remove(recordId);
		}
		bh.consume(notFound);
	}

	/**
	 * Streaming-aligned fix candidate for {@link #denseWalk_notFoundRemoveOnly}: build a matched-ids bitmap from
	 * the matched record ids in WALK order (see {@link #matchedRecordIdsInWalkOrder} — NOT ascending id order)
	 * via {@link PersistentRoaringBitmap#bitmapOfUnordered}, then compute the not-found set via ONE bulk
	 * {@link PersistentRoaringBitmap#andNot} against {@link #selection} — a container-level pass instead of K
	 * individual, COW-mutating {@code remove()} calls.
	 *
	 * An earlier version of this benchmark built {@code matchedIds} with a plain ascending
	 * {@code RoaringBitmapWriter} fed from {@link #matchedRecordIds} (ascending id order) instead — that hid the
	 * real cost, since the real dense walk discovers matches in WALK (sort-value) order, not id order, and a
	 * plain writer is an ascending-input appender (falls back to a slower direct add per out-of-order key,
	 * documented on {@code ConstantMemoryContainerAppender}). {@code bitmapOfUnordered} is the correct,
	 * order-safe construction (internally a partial radix sort by high-16-bits, then the same appender fed
	 * pre-grouped, so the fallback never fires) — this is what {@link #matchedRecordIdsInWalkOrder} plus this
	 * method actually measure.
	 */
	@Benchmark
	public void denseWalk_bulkAndNot(@Nonnull Blackhole bh) {
		final PersistentRoaringBitmap matchedIds = PersistentRoaringBitmap.bitmapOfUnordered(this.matchedRecordIdsInWalkOrder);
		bh.consume(PersistentRoaringBitmap.andNot(this.selection, matchedIds));
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
