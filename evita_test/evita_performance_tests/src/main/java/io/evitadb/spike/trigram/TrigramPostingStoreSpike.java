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

package io.evitadb.spike.trigram;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * **B2 of the P8 trigram-substring-index plan** - decides the posting-store fork the plan leaves open: which
 * key-map structure holds `trigram → postings`, and at which posting cardinality a sorted `int[]` beats a
 * `RoaringBitmap` (brief §10 and §29). Everything it prints is measured on a structure that was really built
 * from a real corpus, exactly as {@link TrigramCorpusStatistics} does for Stage 0.
 *
 * # The two independent measurements
 *
 * **Key maps.** Four variants hold the *same* posting objects, so the difference between them is the key
 * structure and nothing else:
 *
 * - `HashMap<Long, posting>` - the naive baseline brief §10 warns about, built with the no-argument constructor
 *   so it grows the way a naive implementation's would (a pre-sized constructor would land on a table twice as
 *   large and slander the baseline);
 * - sorted `long[]` keys + parallel `Object[]` postings, looked up by binary search - the shape Stage 0 measured
 *   and the one Stage 2 would persist;
 * - an open-addressing long-keyed map with linear probing and power-of-two capacity, at two target load factors
 *   (`0.50` and `0.75`). Both are reported even when they collapse onto the same power of two, because *that*
 *   is the finding: the achievable load factor is decided by where `K` sits between two powers of two, not by
 *   the target.
 *
 * **Posting representations.** Under the sorted-key map (the reference shape), every posting whose cardinality
 * is at most a threshold `T` is stored as a sorted `int[]` and everything above it as a `PersistentRoaringBitmap`
 * (brief §29). `T` is swept; `T = 0` degenerates to pure Roaring, because a posting always holds at least one
 * value id. Both representations describe the same set - the `int[]` is exactly the sorted value-id array the
 * bitmap was built from - so the semantics are identical at every threshold and only the encoding changes.
 *
 * Two columns exist to explain the sweep rather than merely record it. `cont/key` is how many Roaring containers
 * the converted postings held, and it is the mechanism behind the whole curve: a bitmap costs one container per
 * 65,536-wide chunk of the value-id space it touches, so a *sparse* posting over a wide space carries container
 * overhead that has nothing to do with its cardinality, and the threshold at which an `int[]` stops winning is
 * therefore a function of `V` and not a constant. `B / switch` is the heap the keys that changed representation
 * in that band cost or saved, one number per key: the band where it turns positive **is** the crossover.
 *
 * # The empty-slot sentinel
 *
 * The open-addressing map needs a value that can never be a key. `0` is **not** safe: a packed trigram of three
 * `NUL` code points is `0`, and `NUL` is a perfectly legal character in a Java `String`, so an attribute value
 * could produce it. `-1` is safe by construction: {@link TrigramCodec#pack(int, int, int)} fills bits `0..62`
 * only, so every packed trigram is non-negative and no negative `long` can ever be one. That costs nothing,
 * where an occupancy bitset would cost one bit per slot and a branch per probe. The invariant is asserted on
 * insertion rather than assumed.
 *
 * # Accounting
 *
 * - **Heap** is JOL deep-retained, as a delta against an empty structure of the same type - the discipline of
 *   `BucketStoreMemorySpike` and of Stage 0's analyzer.
 * - **Key overhead** is a variant's heap minus the heap of the posting objects themselves (the deep size of the
 *   `Object[]` holding them, minus the deep size of an equally long array of nulls). Every variant therefore
 *   pays for its own reference spine, which is the only way the four numbers are comparable.
 * - **Serialized** size counts `8 · K` bytes for the key column, `PersistentRoaringBitmap#serializedSizeInBytes`
 *   for a bitmap posting and `4 · cardinality` bytes for an `int[]` posting. No per-posting length prefix or
 *   representation tag is counted in either representation; a real format needs one, it would cost about one
 *   byte per key, and it would cost the same in every row of the sweep.
 * - `runOptimize()` is deliberately **not** called: it mutates the bitmaps, and Stage 0 already reported what it
 *   would save.
 * - **Build order matters and is reported separately.** Every posting here is bulk-loaded from an already sorted
 *   array, which is the leanest way to build a bitmap; a live index learns its value ids one upsert at a time and
 *   keeps whatever slack the last container doubling left. The two builds serialize to identical bytes, so only
 *   the heap column is build-order dependent - the `Roaring build order` line says by how much, and the engine
 *   is the incremental one.
 *
 * # The lookup probe
 *
 * A coarse ordering signal only - **not** a benchmark. It times a batch of lookups of randomly drawn existing
 * keys with `System.nanoTime` around the whole batch, takes the best of several rounds after a warm-up, and
 * reports nanoseconds per lookup. There is no JMH harness, no fork, no dead-code-elimination guard beyond
 * counting the hits, and the machine may be running other measurements at the same time. Treat a difference
 * below roughly a factor of two as noise; B4 is where the real benchmark lives. Each variant gets its own
 * probe loop so that its call site stays monomorphic - a shared loop behind an interface would put a
 * megamorphic call in front of all four and measure the dispatch instead of the structure.
 *
 * # Running it
 *
 * ```shell
 * java -Xmx12g -Djol.magicFieldOffset=true \
 *   -Devita.trigram.corpusFile=/path/to/cms-corpus.tsv \
 *   -Devita.trigram.attributes=title,keywords,authors,url \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.TrigramPostingStoreSpike
 * ```
 *
 * The corpus path may also be given as the first command-line argument. Groups are analyzed largest first;
 * `evita.trigram.topGroups` keeps only the largest few, `evita.trigram.minValues` drops the tiny ones and
 * `evita.trigram.measureHeap=false` skips the JOL walks, which are the slow part.
 *
 * @author Claude (P8 trigram-substring-index spike), FG Forrest a.s. (c) 2026
 */
public class TrigramPostingStoreSpike {

	/**
	 * System property naming the TSV corpus to analyze; the first command-line argument overrides it.
	 */
	public static final String CORPUS_FILE_PROPERTY = "evita.trigram.corpusFile";

	/**
	 * System property listing the attribute names to keep, comma separated; empty keeps every attribute.
	 */
	public static final String ATTRIBUTES_PROPERTY = "evita.trigram.attributes";

	/**
	 * System property skipping groups with fewer than this many distinct values.
	 */
	public static final String MIN_VALUES_PROPERTY = "evita.trigram.minValues";

	/**
	 * System property keeping only the given number of largest groups; `0` keeps all of them.
	 */
	public static final String TOP_GROUPS_PROPERTY = "evita.trigram.topGroups";

	/**
	 * System property listing the swept small-posting thresholds, comma separated and ascending.
	 */
	public static final String THRESHOLDS_PROPERTY = "evita.trigram.thresholds";

	/**
	 * System property setting how many keys one lookup-probe batch looks up.
	 */
	public static final String LOOKUP_PROBES_PROPERTY = "evita.trigram.lookupProbes";

	/**
	 * System property setting how many measured lookup-probe rounds are run; the best one is reported.
	 */
	public static final String LOOKUP_ROUNDS_PROPERTY = "evita.trigram.lookupRounds";

	/**
	 * System property switching the JOL heap walks off (`false`), leaving only the serialized sizes.
	 */
	public static final String MEASURE_HEAP_PROPERTY = "evita.trigram.measureHeap";

	/**
	 * Small-posting thresholds swept when the property does not override them.
	 */
	private static final String DEFAULT_THRESHOLDS = "0,4,8,16,32,64,128,256";

	/**
	 * Labels of the measured key-map variants, in the order they are measured and reported.
	 */
	private static final String[] VARIANT_LABELS = {
		"HashMap<Long,posting>",
		"sorted long[] + Object[]",
		"open-addressing (load <= 0.50)",
		"open-addressing (load <= 0.75)"
	};

	/**
	 * Target load factors of the two open-addressing variants.
	 */
	private static final double[] OPEN_ADDRESSING_LOADS = {0.50, 0.75};

	/**
	 * The empty {@link TrigramKeyIndex}, subtracted from every open-addressing heap measurement. It holds the
	 * smallest legal table rather than none at all, so the lookup path needs no emptiness branch.
	 */
	private static final TrigramKeyIndex EMPTY_KEY_INDEX = new TrigramKeyIndex(0, OPEN_ADDRESSING_LOADS[0]);

	/**
	 * Sentinel heap figure meaning "JOL was switched off", so a skipped measurement can never be read as zero.
	 */
	private static final long HEAP_NOT_MEASURED = -1L;

	/**
	 * Seed of the lookup probe's key sampler, fixed so two runs probe the same keys in the same order.
	 */
	private static final long PROBE_SEED = 0x5EED_7819_2C0FL;

	/**
	 * Lookup-probe rounds run before the timed ones, to let the JIT settle. Generous on purpose: with three
	 * rounds the first analyzed group reported lookups 1.5x slower than the third one did for the same code,
	 * which is compilation state and not structure. A warm-up round costs about a millisecond.
	 */
	private static final int PROBE_WARMUP_ROUNDS = 20;

	public static void main(@Nonnull String[] args) {
		try {
			run(args);
		} catch (final Throwable e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Performs the whole B2 measurement as described in the class JavaDoc.
	 *
	 * @param args optional single argument overriding {@link #CORPUS_FILE_PROPERTY}
	 */
	private static void run(@Nonnull String[] args) throws IOException {
		// guards every number below: a silently broken codec would not fail anything, it would just make the
		// whole report describe an index nobody is proposing
		TrigramCodec.selfCheck();

		final Path corpusFile = args.length > 0
			? Path.of(args[0])
			: Path.of(requiredProperty(CORPUS_FILE_PROPERTY));
		final Set<String> attributeFilter = parseListProperty(ATTRIBUTES_PROPERTY);
		final int minValues = Integer.parseInt(System.getProperty(MIN_VALUES_PROPERTY, "1"));
		final int topGroups = Integer.parseInt(System.getProperty(TOP_GROUPS_PROPERTY, "0"));
		final int[] thresholds = parseThresholds();
		final int lookupProbes = Integer.parseInt(System.getProperty(LOOKUP_PROBES_PROPERTY, "10000"));
		final int lookupRounds = Integer.parseInt(System.getProperty(LOOKUP_ROUNDS_PROPERTY, "7"));
		final boolean measureHeap = Boolean.parseBoolean(System.getProperty(MEASURE_HEAP_PROPERTY, "true"));

		System.out.printf(Locale.ROOT, "Trigram posting-store spike (B2) - %s%n", corpusFile);
		System.out.printf(
			Locale.ROOT,
			"Attributes: %s.  Thresholds: %s.  Heap: %s.  Lookup probe: %,d keys x %d rounds (best reported).%n",
			attributeFilter.isEmpty() ? "(all)" : String.join(",", attributeFilter),
			Arrays.toString(thresholds), measureHeap ? "JOL deep-retained" : "SKIPPED",
			lookupProbes, lookupRounds
		);
		System.out.println(
			"Serialized accounting: 8 B per key + Roaring serializedSizeInBytes() + 4 B per int[] posting entry;"
		);
		System.out.println("no per-posting length prefix or representation tag is counted in any variant.");
		System.out.println();

		final Map<GroupKey, Set<String>> corpus = loadDistinctValues(corpusFile, attributeFilter);
		final List<GroupKey> ordered = orderGroups(corpus, minValues, topGroups);

		final List<GroupResult> results = new ArrayList<>(ordered.size());
		for (int i = 0; i < ordered.size(); i++) {
			final GroupKey key = ordered.get(i);
			// the group is removed from the corpus before it is analyzed, so its values become collectible the
			// moment extraction has consumed them - on the CMS corpus's `title` that is hundreds of megabytes
			final Set<String> values = corpus.remove(key);
			final GroupResult result = analyzeGroup(
				key, values, thresholds, lookupProbes, lookupRounds, measureHeap
			);
			if (result != null) {
				results.add(result);
			}
		}
		corpus.clear();
		printTotals(results, thresholds, measureHeap);
		System.out.println("=== DONE ===");
	}

	/* ======================================== analysis =========================================== */

	/**
	 * Measures one `entityType / attributeName / locale` group and prints its section of the report.
	 *
	 * @param key            identity of the group
	 * @param distinctValues the group's distinct normalized values; **consumed** - cleared once extracted
	 * @param thresholds     the swept small-posting thresholds
	 * @param lookupProbes   how many keys one probe batch looks up
	 * @param lookupRounds   how many measured probe rounds are run
	 * @param measureHeap    whether the JOL walks are performed
	 * @return the aggregatable subset of the group's numbers, or `null` when the group carries no trigram at all
	 */
	@Nullable
	private static GroupResult analyzeGroup(
		@Nonnull GroupKey key, @Nonnull Set<String> distinctValues, @Nonnull int[] thresholds,
		int lookupProbes, int lookupRounds, boolean measureHeap
	) {
		final long startedAt = System.nanoTime();
		final int valueCount = distinctValues.size();
		final PostingColumns columns = extractAndBuild(distinctValues);
		final int keyCount = columns.keys().length;

		System.out.printf(Locale.ROOT, "=== %s ===%n", key.display());
		System.out.printf(
			Locale.ROOT,
			"  V (distinct values)                 : %,15d%n" +
				"  K (distinct trigram keys)           : %,15d%n" +
				"  E (trigram -> valueId memberships)  : %,15d%n",
			valueCount, keyCount, columns.memberships()
		);
		if (keyCount == 0) {
			System.out.println("  no trigram at all - every value is shorter than three code points; skipped.");
			System.out.println();
			return null;
		}

		final int[][] postings = columns.postings();
		final int[] sortedCardinalities = new int[keyCount];
		for (int i = 0; i < keyCount; i++) {
			sortedCardinalities[i] = postings[i].length;
		}
		Arrays.sort(sortedCardinalities);
		System.out.printf(
			Locale.ROOT,
			"  posting cardinality                 : P50 %,d  P90 %,d  P95 %,d  P99 %,d  max %,d%n",
			percentile(sortedCardinalities, 0.50), percentile(sortedCardinalities, 0.90),
			percentile(sortedCardinalities, 0.95), percentile(sortedCardinalities, 0.99),
			sortedCardinalities[keyCount - 1]
		);

		final PersistentRoaringBitmap[] bitmaps = new PersistentRoaringBitmap[keyCount];
		final int[] bitmapSerialized = new int[keyCount];
		final int[] bitmapContainers = new int[keyCount];
		long roaringSerializedTotal = 0L;
		long containerTotal = 0L;
		for (int i = 0; i < keyCount; i++) {
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			bitmap.addN(postings[i], 0, postings[i].length);
			bitmaps[i] = bitmap;
			bitmapSerialized[i] = bitmap.serializedSizeInBytes();
			bitmapContainers[i] = bitmap.getContainerCount();
			roaringSerializedTotal += bitmapSerialized[i];
			containerTotal += bitmapContainers[i];
		}
		// how wide the value-id space is, in Roaring's 65,536-entry chunks: a posting of a given cardinality
		// costs one container per chunk it touches, so a *sparse* posting over a wide id space carries container
		// overhead that has nothing to do with how many values it holds. This is what makes the small-posting
		// threshold a function of `V` rather than a constant
		System.out.printf(
			Locale.ROOT,
			"  valueId chunks (65,536 wide)        : %,15d   (%,d Roaring containers, %.2f per posting)%n",
			(valueCount + 0xFFFF) / 0x10000, containerTotal, (double) containerTotal / keyCount
		);

		printIncrementalBuildDelta(columns.keys(), postings, measureHeap);

		final long[] probeKeys = sampleProbeKeys(columns.keys(), lookupProbes);
		final VariantMeasurement[] variants = measureKeyMaps(
			columns.keys(), bitmaps, probeKeys, lookupRounds, measureHeap
		);
		printKeyMaps(variants, measureHeap);

		final SweepMeasurement[] sweep = measureThresholdSweep(
			columns.keys(), postings, bitmaps, bitmapSerialized, bitmapContainers, columns.memberships(),
			thresholds, measureHeap
		);
		printSweep(thresholds, sweep, measureHeap);

		System.out.printf(
			Locale.ROOT, "  Roaring serialized total (T=0)      : %,15d B   (analyzed in %.1f s)%n%n",
			roaringSerializedTotal, (System.nanoTime() - startedAt) / 1_000_000_000.0
		);
		return new GroupResult(valueCount, keyCount, columns.memberships(), variants, sweep);
	}

	/**
	 * Extracts every value's distinct trigrams and inverts them into the `trigram → sorted valueId[]` columns the
	 * whole measurement is built on. Extraction and inversion share one method so that the per-value trigram
	 * arrays - eight bytes per membership, the largest transient structure in the run - go out of scope before
	 * the caller starts walking heaps.
	 *
	 * Value ids are dense `0..V-1` in the corpus's first-occurrence order, and values are visited in ascending id
	 * order, so every posting comes out sorted ascending without a sort.
	 *
	 * @param distinctValues the group's distinct normalized values; cleared as soon as it has been consumed
	 * @return the inverted columns, keys ascending
	 */
	@Nonnull
	private static PostingColumns extractAndBuild(@Nonnull Set<String> distinctValues) {
		final int valueCount = distinctValues.size();
		final long[][] trigramsByValueId = new long[valueCount][];
		long memberships = 0L;
		int valueId = 0;
		for (final String value : distinctValues) {
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(value);
			trigramsByValueId[valueId++] = trigrams;
			memberships += trigrams.length;
		}
		distinctValues.clear();

		// first pass: assign every distinct key an ordinal and count how many value ids it will hold
		final TrigramOrdinalMap ordinals = new TrigramOrdinalMap(1024);
		long[] keysByOrdinal = new long[1024];
		int[] cardinalities = new int[1024];
		int keyCount = 0;
		for (int id = 0; id < valueCount; id++) {
			final long[] trigrams = trigramsByValueId[id];
			for (int i = 0; i < trigrams.length; i++) {
				final int ordinal = ordinals.putIfAbsent(trigrams[i], keyCount);
				if (ordinal == keyCount) {
					if (keyCount == keysByOrdinal.length) {
						keysByOrdinal = Arrays.copyOf(keysByOrdinal, keyCount * 2);
						cardinalities = Arrays.copyOf(cardinalities, keyCount * 2);
					}
					keysByOrdinal[keyCount] = trigrams[i];
					keyCount++;
				}
				cardinalities[ordinal]++;
			}
		}

		// second pass: fill exactly sized postings, ascending by construction
		final int[][] postingsByOrdinal = new int[keyCount][];
		for (int ordinal = 0; ordinal < keyCount; ordinal++) {
			postingsByOrdinal[ordinal] = new int[cardinalities[ordinal]];
		}
		final int[] cursors = new int[keyCount];
		for (int id = 0; id < valueCount; id++) {
			final long[] trigrams = trigramsByValueId[id];
			for (int i = 0; i < trigrams.length; i++) {
				final int ordinal = ordinals.get(trigrams[i]);
				postingsByOrdinal[ordinal][cursors[ordinal]++] = id;
			}
		}
		for (int ordinal = 0; ordinal < keyCount; ordinal++) {
			if (cursors[ordinal] != cardinalities[ordinal]) {
				throw new GenericEvitaInternalError(
					"Posting of ordinal " + ordinal + " was filled " + cursors[ordinal] + " times but counted " +
						cardinalities[ordinal] + " times - the two inversion passes disagree!",
					"The two inversion passes disagree!"
				);
			}
		}

		// finally order the keys ascending, which is what the sorted-key variant needs and what a persisted key
		// column would hold anyway
		final long[] keys = Arrays.copyOf(keysByOrdinal, keyCount);
		Arrays.sort(keys);
		final int[][] postings = new int[keyCount][];
		for (int i = 0; i < keyCount; i++) {
			postings[i] = postingsByOrdinal[ordinals.get(keys[i])];
		}
		return new PostingColumns(keys, postings, memberships);
	}

	/**
	 * Reports what the *order* the postings were built in costs. Every other figure in this report comes from
	 * bitmaps bulk-loaded from an already sorted array, which is the cheapest way to build one and the wrong
	 * model of the engine: a live index learns its value ids one upsert at a time, and a container that grew by
	 * doubling keeps whatever slack the last doubling left. The two builds hold identical sets and serialize to
	 * identical bytes - only the heap differs, and only the heap figure is therefore build-order dependent.
	 *
	 * Both sets are alive at once for the duration of this measurement, so it doubles the group's peak posting
	 * heap; it is reported before the key-map variants so that the transient set is gone before those run.
	 *
	 * @param keys        ascending trigram keys
	 * @param postings    sorted value-id arrays, parallel to `keys`
	 * @param measureHeap whether the JOL walks are performed at all
	 */
	private static void printIncrementalBuildDelta(
		@Nonnull long[] keys, @Nonnull int[][] postings, boolean measureHeap
	) {
		if (!measureHeap) {
			return;
		}
		final int keyCount = keys.length;
		final Object[] incremental = new Object[keyCount];
		for (int i = 0; i < keyCount; i++) {
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			final int[] posting = postings[i];
			for (int j = 0; j < posting.length; j++) {
				bitmap.add(posting[j]);
			}
			incremental[i] = bitmap;
		}
		final Object[] bulk = new Object[keyCount];
		for (int i = 0; i < keyCount; i++) {
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			bitmap.addN(postings[i], 0, postings[i].length);
			bulk[i] = bitmap;
		}
		final long incrementalHeap = deepSize(incremental);
		final long bulkHeap = deepSize(bulk);
		System.out.printf(
			Locale.ROOT,
			"  Roaring build order                 : bulk %,d B, incremental %,d B (%+.1f%% - the engine builds " +
				"incrementally)%n",
			bulkHeap, incrementalHeap, percentChange(bulkHeap, incrementalHeap)
		);
	}

	/**
	 * Builds all four key-map variants over the *same* posting objects and measures each one's heap and lookup
	 * cost. Variants are built and measured one at a time and dropped immediately, so only one of them is alive
	 * during its own JOL walk.
	 *
	 * @param keys          ascending trigram keys
	 * @param bitmaps       the shared posting objects, parallel to `keys`
	 * @param probeKeys     keys the lookup probe looks up
	 * @param lookupRounds  how many measured probe rounds are run
	 * @param measureHeap   whether the JOL walks are performed
	 * @return one measurement per {@link #VARIANT_LABELS} entry, in that order
	 */
	@Nonnull
	private static VariantMeasurement[] measureKeyMaps(
		@Nonnull long[] keys, @Nonnull PersistentRoaringBitmap[] bitmaps, @Nonnull long[] probeKeys,
		int lookupRounds, boolean measureHeap
	) {
		final int keyCount = keys.length;
		final Object[] postingObjects = new Object[keyCount];
		System.arraycopy(bitmaps, 0, postingObjects, 0, keyCount);
		// the posting objects on their own - the array of references that carries them is excluded, so that every
		// variant below is charged for its own reference spine and the four overhead figures stay comparable
		final long postingObjectsHeap = measureHeap
			? deepSize(postingObjects) - deepSize(new Object[keyCount])
			: HEAP_NOT_MEASURED;

		final VariantMeasurement[] measurements = new VariantMeasurement[VARIANT_LABELS.length];

		final HashMap<Long, Object> boxedMap = new HashMap<>();
		for (int i = 0; i < keyCount; i++) {
			boxedMap.put(keys[i], postingObjects[i]);
		}
		measurements[0] = new VariantMeasurement(
			heapDelta(boxedMap, new HashMap<Long, Object>(), measureHeap), postingObjectsHeap,
			probeBoxedMap(boxedMap, probeKeys, lookupRounds), keyCount,
			VariantMeasurement.CAPACITY_NOT_APPLICABLE
		);

		final SortedKeyIndex sortedIndex = new SortedKeyIndex(keys, postingObjects);
		measurements[1] = new VariantMeasurement(
			heapDelta(sortedIndex, SortedKeyIndex.EMPTY, measureHeap), postingObjectsHeap,
			probeSortedIndex(sortedIndex, probeKeys, lookupRounds), keyCount,
			VariantMeasurement.CAPACITY_NOT_APPLICABLE
		);

		for (int variant = 0; variant < OPEN_ADDRESSING_LOADS.length; variant++) {
			final TrigramKeyIndex openIndex = new TrigramKeyIndex(keyCount, OPEN_ADDRESSING_LOADS[variant]);
			for (int i = 0; i < keyCount; i++) {
				openIndex.put(keys[i], postingObjects[i]);
			}
			measurements[2 + variant] = new VariantMeasurement(
				heapDelta(openIndex, EMPTY_KEY_INDEX, measureHeap), postingObjectsHeap,
				probeOpenIndex(openIndex, probeKeys, lookupRounds), keyCount, openIndex.capacity()
			);
		}
		return measurements;
	}

	/**
	 * Sweeps the small-posting threshold under the sorted-key map: every posting of cardinality at most `T`
	 * becomes a sorted `int[]`, everything above it stays a `PersistentRoaringBitmap`. The two representations
	 * hold identical sets, so only the encoding changes across the sweep.
	 *
	 * @param keys             ascending trigram keys
	 * @param postings         the sorted value-id arrays, parallel to `keys`
	 * @param bitmaps          the same postings as bitmaps, parallel to `keys`
	 * @param bitmapSerialized each bitmap's serialized size, parallel to `keys`
	 * @param bitmapContainers each bitmap's container count, parallel to `keys`
	 * @param memberships      the group's `E`, so the sweep rows can report their share of it
	 * @param thresholds       the swept thresholds, ascending
	 * @param measureHeap      whether the JOL walks are performed
	 * @return one measurement per threshold, in the swept order
	 */
	@Nonnull
	private static SweepMeasurement[] measureThresholdSweep(
		@Nonnull long[] keys, @Nonnull int[][] postings, @Nonnull PersistentRoaringBitmap[] bitmaps,
		@Nonnull int[] bitmapSerialized, @Nonnull int[] bitmapContainers, long memberships,
		@Nonnull int[] thresholds, boolean measureHeap
	) {
		final int keyCount = keys.length;
		final long keyColumnBytes = (long) keyCount * Long.BYTES;
		final SweepMeasurement[] measurements = new SweepMeasurement[thresholds.length];
		for (int t = 0; t < thresholds.length; t++) {
			final int threshold = thresholds[t];
			final Object[] mixed = new Object[keyCount];
			int smallKeys = 0;
			long smallMemberships = 0L;
			long smallContainers = 0L;
			long serialized = keyColumnBytes;
			for (int i = 0; i < keyCount; i++) {
				if (postings[i].length <= threshold) {
					mixed[i] = postings[i];
					smallKeys++;
					smallMemberships += postings[i].length;
					smallContainers += bitmapContainers[i];
					serialized += (long) postings[i].length * Integer.BYTES;
				} else {
					mixed[i] = bitmaps[i];
					serialized += bitmapSerialized[i];
				}
			}
			final SortedKeyIndex index = new SortedKeyIndex(keys, mixed);
			measurements[t] = new SweepMeasurement(
				heapDelta(index, SortedKeyIndex.EMPTY, measureHeap), serialized, smallKeys, smallMemberships,
				smallContainers, keyCount, memberships
			);
		}
		return measurements;
	}

	/* ====================================== lookup probes ======================================== */

	/**
	 * Draws the keys one probe batch looks up - uniformly at random from the existing keys, with repetition, from
	 * a fixed seed. Drawing uniformly over *keys* means rare trigrams dominate the sample, because most keys are
	 * rare; that is the key distribution and deliberately not a query distribution, which B4 will supply.
	 *
	 * @param keys         ascending trigram keys
	 * @param lookupProbes how many keys to draw
	 * @return the drawn keys, in draw order
	 */
	@Nonnull
	private static long[] sampleProbeKeys(@Nonnull long[] keys, int lookupProbes) {
		final Random random = new Random(PROBE_SEED);
		final long[] probeKeys = new long[Math.min(lookupProbes, Math.max(1, keys.length))];
		for (int i = 0; i < probeKeys.length; i++) {
			probeKeys[i] = keys[random.nextInt(keys.length)];
		}
		return probeKeys;
	}

	/**
	 * Times batched lookups against the boxed-key baseline. Kept separate from the other two probes so that its
	 * call site stays monomorphic - see the class JavaDoc.
	 *
	 * @param index        the structure to probe
	 * @param probeKeys    keys to look up, all of which exist
	 * @param lookupRounds how many measured rounds are run
	 * @return nanoseconds per lookup in the best measured round
	 */
	private static double probeBoxedMap(
		@Nonnull HashMap<Long, Object> index, @Nonnull long[] probeKeys, int lookupRounds
	) {
		long best = Long.MAX_VALUE;
		for (int round = 0; round < PROBE_WARMUP_ROUNDS + lookupRounds; round++) {
			final long startedAt = System.nanoTime();
			int hits = 0;
			for (int i = 0; i < probeKeys.length; i++) {
				if (index.get(probeKeys[i]) != null) {
					hits++;
				}
			}
			final long elapsed = System.nanoTime() - startedAt;
			assertAllFound(hits, probeKeys.length);
			if (round >= PROBE_WARMUP_ROUNDS) {
				best = Math.min(best, elapsed);
			}
		}
		return (double) best / probeKeys.length;
	}

	/**
	 * Times batched lookups against the sorted-key binary-search variant.
	 *
	 * @param index        the structure to probe
	 * @param probeKeys    keys to look up, all of which exist
	 * @param lookupRounds how many measured rounds are run
	 * @return nanoseconds per lookup in the best measured round
	 */
	private static double probeSortedIndex(
		@Nonnull SortedKeyIndex index, @Nonnull long[] probeKeys, int lookupRounds
	) {
		long best = Long.MAX_VALUE;
		for (int round = 0; round < PROBE_WARMUP_ROUNDS + lookupRounds; round++) {
			final long startedAt = System.nanoTime();
			int hits = 0;
			for (int i = 0; i < probeKeys.length; i++) {
				if (index.get(probeKeys[i]) != null) {
					hits++;
				}
			}
			final long elapsed = System.nanoTime() - startedAt;
			assertAllFound(hits, probeKeys.length);
			if (round >= PROBE_WARMUP_ROUNDS) {
				best = Math.min(best, elapsed);
			}
		}
		return (double) best / probeKeys.length;
	}

	/**
	 * Times batched lookups against an open-addressing variant. Both load factors share this method - they share
	 * an implementation class, so the call site stays monomorphic across them.
	 *
	 * @param index        the structure to probe
	 * @param probeKeys    keys to look up, all of which exist
	 * @param lookupRounds how many measured rounds are run
	 * @return nanoseconds per lookup in the best measured round
	 */
	private static double probeOpenIndex(
		@Nonnull TrigramKeyIndex index, @Nonnull long[] probeKeys, int lookupRounds
	) {
		long best = Long.MAX_VALUE;
		for (int round = 0; round < PROBE_WARMUP_ROUNDS + lookupRounds; round++) {
			final long startedAt = System.nanoTime();
			int hits = 0;
			for (int i = 0; i < probeKeys.length; i++) {
				if (index.get(probeKeys[i]) != null) {
					hits++;
				}
			}
			final long elapsed = System.nanoTime() - startedAt;
			assertAllFound(hits, probeKeys.length);
			if (round >= PROBE_WARMUP_ROUNDS) {
				best = Math.min(best, elapsed);
			}
		}
		return (double) best / probeKeys.length;
	}

	/**
	 * Guards the probe: every drawn key exists, so a miss means the structure under test lost a key and every
	 * number it produced describes something other than the index that was built.
	 *
	 * @param hits     keys the structure returned a posting for
	 * @param expected keys that were looked up
	 */
	private static void assertAllFound(int hits, int expected) {
		if (hits != expected) {
			throw new GenericEvitaInternalError(
				"Lookup probe found " + hits + " of " + expected + " keys that all exist - the key map lost keys!",
				"The key map lost keys!"
			);
		}
	}

	/* ======================================== reporting ========================================== */

	/**
	 * Prints the key-map comparison table.
	 *
	 * @param variants    the measurements, in {@link #VARIANT_LABELS} order
	 * @param measureHeap whether heap figures are present
	 */
	private static void printKeyMaps(@Nonnull VariantMeasurement[] variants, boolean measureHeap) {
		System.out.printf(
			Locale.ROOT, "  key-map variants (identical posting objects%s)%n",
			measureHeap
				? String.format(Locale.ROOT, "; %,d B of them", variants[0].postingObjectsHeap())
				: ""
		);
		System.out.printf(
			Locale.ROOT, "  %-31s %16s %16s %9s %12s %6s %10s%n",
			"variant", "total heap B", "key overhead B", "B / key", "capacity", "load", "ns/lookup"
		);
		for (int i = 0; i < variants.length; i++) {
			final VariantMeasurement variant = variants[i];
			final boolean hasHeap = variant.heapBytes() != HEAP_NOT_MEASURED;
			final boolean hasCapacity = variant.capacity() != VariantMeasurement.CAPACITY_NOT_APPLICABLE;
			System.out.printf(
				Locale.ROOT, "  %-31s %16s %16s %9s %12s %6s %10.1f%n",
				VARIANT_LABELS[i],
				hasHeap ? String.format(Locale.ROOT, "%,d", variant.heapBytes()) : "n/a",
				hasHeap ? String.format(Locale.ROOT, "%,d", variant.keyOverheadBytes()) : "n/a",
				hasHeap ? String.format(Locale.ROOT, "%.1f", variant.bytesPerKey()) : "n/a",
				hasCapacity ? String.format(Locale.ROOT, "%,d", variant.capacity()) : "-",
				hasCapacity ? String.format(Locale.ROOT, "%.3f", variant.loadFactor()) : "-",
				variant.nanosPerLookup()
			);
		}
	}

	/**
	 * Prints the small-posting threshold sweep and names the threshold with the smallest heap.
	 *
	 * @param thresholds  the swept thresholds
	 * @param sweep       the measurements, parallel to `thresholds`
	 * @param measureHeap whether heap figures are present
	 */
	private static void printSweep(
		@Nonnull int[] thresholds, @Nonnull SweepMeasurement[] sweep, boolean measureHeap
	) {
		System.out.println("  small-posting threshold sweep (key map: sorted long[] + Object[])");
		System.out.printf(
			Locale.ROOT, "  %5s %12s %8s %9s %8s %16s %16s %12s %11s%n",
			"T", "int[] keys", "% keys", "% memb.", "cont/key", "total heap B", "serialized B", "heap vs T=0",
			"B / switch"
		);
		final long baseHeap = sweep[0].heapBytes();
		final long baseSerialized = sweep[0].serializedBytes();
		int bestThreshold = thresholds[0];
		long bestHeap = baseHeap;
		int leanestThreshold = thresholds[0];
		long leanestSerialized = baseSerialized;
		for (int t = 0; t < thresholds.length; t++) {
			final SweepMeasurement measurement = sweep[t];
			final boolean hasHeap = measurement.heapBytes() != HEAP_NOT_MEASURED;
			if (hasHeap && measurement.heapBytes() < bestHeap) {
				bestHeap = measurement.heapBytes();
				bestThreshold = thresholds[t];
			}
			if (measurement.serializedBytes() < leanestSerialized) {
				leanestSerialized = measurement.serializedBytes();
				leanestThreshold = thresholds[t];
			}
			// what the keys that switched representation in THIS band cost or saved, one number per key: the band
			// where it turns positive is the cardinality at which a sorted int[] stops beating a Roaring bitmap,
			// which is the actual crossover the threshold is trying to sit at
			final int switched = t == 0 ? 0 : measurement.smallKeys() - sweep[t - 1].smallKeys();
			System.out.printf(
				Locale.ROOT, "  %5d %,12d %7.1f%% %8.1f%% %8.2f %16s %,16d %11s %11s%n",
				thresholds[t], measurement.smallKeys(),
				measurement.smallKeyShare(), measurement.smallMembershipShare(),
				measurement.containersPerSmallKey(),
				hasHeap ? String.format(Locale.ROOT, "%,d", measurement.heapBytes()) : "n/a",
				measurement.serializedBytes(),
				hasHeap ? String.format(Locale.ROOT, "%+.1f%%", percentChange(baseHeap, measurement.heapBytes()))
					: "n/a",
				hasHeap && switched > 0
					? String.format(
						Locale.ROOT, "%+.1f", (double) (measurement.heapBytes() - sweep[t - 1].heapBytes()) / switched
					)
					: "-"
			);
		}
		if (measureHeap) {
			System.out.printf(
				Locale.ROOT, "  smallest heap at T = %d (%,d B, %+.1f%% against pure Roaring; serialized %+.1f%%)%n",
				bestThreshold, bestHeap, percentChange(baseHeap, bestHeap),
				percentChange(baseSerialized, serializedAt(thresholds, sweep, bestThreshold))
			);
		}
		System.out.printf(
			Locale.ROOT, "  smallest serialized at T = %d (%,d B, %+.1f%% against pure Roaring)%n",
			leanestThreshold, leanestSerialized, percentChange(baseSerialized, leanestSerialized)
		);
	}

	/**
	 * Looks the serialized size of one swept threshold up.
	 *
	 * @param thresholds the swept thresholds
	 * @param sweep      the measurements, parallel to `thresholds`
	 * @param threshold  the threshold to look up
	 * @return its serialized size
	 */
	private static long serializedAt(
		@Nonnull int[] thresholds, @Nonnull SweepMeasurement[] sweep, int threshold
	) {
		for (int t = 0; t < thresholds.length; t++) {
			if (thresholds[t] == threshold) {
				return sweep[t].serializedBytes();
			}
		}
		throw new GenericEvitaInternalError(
			"Threshold " + threshold + " was reported as best but is not in the swept set!",
			"Threshold reported as best is not in the swept set!"
		);
	}

	/**
	 * Prints the corpus-wide roll-up. Sizes are additive because every group is a separate index; `K` and `E` are
	 * sums of per-group values and are not the cardinality of any single structure.
	 *
	 * @param results     per-group results
	 * @param thresholds  the swept thresholds
	 * @param measureHeap whether heap figures are present
	 */
	private static void printTotals(
		@Nonnull List<GroupResult> results, @Nonnull int[] thresholds, boolean measureHeap
	) {
		if (results.isEmpty()) {
			System.out.println("=== TOTAL === no group carried a single trigram.");
			return;
		}
		long valueCount = 0L;
		long keyCount = 0L;
		long memberships = 0L;
		final long[] variantHeap = new long[VARIANT_LABELS.length];
		final long[] sweepHeap = new long[thresholds.length];
		final long[] sweepSerialized = new long[thresholds.length];
		final long[] sweepSmallKeys = new long[thresholds.length];
		for (int i = 0; i < results.size(); i++) {
			final GroupResult result = results.get(i);
			valueCount += result.valueCount();
			keyCount += result.keyCount();
			memberships += result.memberships();
			for (int variant = 0; variant < VARIANT_LABELS.length; variant++) {
				variantHeap[variant] += Math.max(0L, result.variants()[variant].heapBytes());
			}
			for (int t = 0; t < thresholds.length; t++) {
				sweepHeap[t] += Math.max(0L, result.sweep()[t].heapBytes());
				sweepSerialized[t] += result.sweep()[t].serializedBytes();
				sweepSmallKeys[t] += result.sweep()[t].smallKeys();
			}
		}
		System.out.printf(Locale.ROOT, "=== TOTAL over %d groups ===%n", results.size());
		System.out.printf(
			Locale.ROOT, "  V %,d   K %,d   E %,d%n", valueCount, keyCount, memberships
		);
		if (measureHeap) {
			System.out.printf(Locale.ROOT, "  %-31s %16s %9s%n", "variant", "total heap B", "vs sorted");
			for (int variant = 0; variant < VARIANT_LABELS.length; variant++) {
				System.out.printf(
					Locale.ROOT, "  %-31s %,16d %8.2fx%n",
					VARIANT_LABELS[variant], variantHeap[variant],
					variantHeap[1] == 0L ? 0.0 : (double) variantHeap[variant] / variantHeap[1]
				);
			}
		}
		System.out.printf(
			Locale.ROOT, "  %5s %12s %8s %16s %16s %12s%n",
			"T", "int[] keys", "% keys", "total heap B", "serialized B", "heap vs T=0"
		);
		for (int t = 0; t < thresholds.length; t++) {
			System.out.printf(
				Locale.ROOT, "  %5d %,12d %7.1f%% %16s %,16d %11s%n",
				thresholds[t], sweepSmallKeys[t], keyCount == 0L ? 0.0 : 100.0 * sweepSmallKeys[t] / keyCount,
				measureHeap ? String.format(Locale.ROOT, "%,d", sweepHeap[t]) : "n/a",
				sweepSerialized[t],
				measureHeap ? String.format(Locale.ROOT, "%+.1f%%", percentChange(sweepHeap[0], sweepHeap[t]))
					: "n/a"
			);
		}
	}

	/* ========================================= loading =========================================== */

	/**
	 * Reads the TSV corpus into per-group sets of distinct normalized values. Only the distinct values are kept -
	 * unlike Stage 0's analyzer this spike never builds the entity-primary-key variant, so the occurrence stream
	 * is not needed and is dropped as it is read.
	 *
	 * @param corpusFile      the TSV written by {@link TrigramCorpusExtractor}
	 * @param attributeFilter attribute names to keep; empty keeps every attribute
	 * @return per-group distinct values, in first-occurrence order within a group
	 */
	@Nonnull
	private static Map<GroupKey, Set<String>> loadDistinctValues(
		@Nonnull Path corpusFile, @Nonnull Set<String> attributeFilter
	) throws IOException {
		final Map<GroupKey, Set<String>> corpus = new TreeMap<>();
		long lineNumber = 0L;
		long acceptedLines = 0L;
		try (final BufferedReader reader = Files.newBufferedReader(corpusFile, StandardCharsets.UTF_8)) {
			String line = reader.readLine();
			while (line != null) {
				lineNumber++;
				if (line.isEmpty() || line.charAt(0) == '#') {
					line = reader.readLine();
					continue;
				}
				final String[] columns = line.split("\t", -1);
				if (columns.length != 5) {
					throw new GenericEvitaInternalError(
						"Corpus line " + lineNumber + " of `" + corpusFile + "` has " + columns.length +
							" columns, expected 5!",
						"Malformed corpus line!"
					);
				}
				if (attributeFilter.isEmpty() || attributeFilter.contains(columns[1])) {
					final GroupKey key = new GroupKey(columns[0], columns[1], columns[2]);
					corpus.computeIfAbsent(key, groupKey -> new LinkedHashSet<>())
						.add(TrigramCodec.normalize(TrigramCorpusStatistics.unescape(columns[4])));
					acceptedLines++;
				}
				line = reader.readLine();
			}
		}
		System.out.printf(
			Locale.ROOT, "Read %,d corpus lines, kept %,d in %,d groups.%n%n",
			lineNumber, acceptedLines, corpus.size()
		);
		return corpus;
	}

	/**
	 * Orders the groups largest first and applies the size cut-offs, so a report over a big catalog spends its
	 * time on the attributes whose cost the decision actually turns on.
	 *
	 * @param corpus    the loaded corpus
	 * @param minValues groups with fewer distinct values are dropped
	 * @param topGroups how many groups to keep; `0` keeps all of them
	 * @return the groups to analyze, largest first
	 */
	@Nonnull
	private static List<GroupKey> orderGroups(
		@Nonnull Map<GroupKey, Set<String>> corpus, int minValues, int topGroups
	) {
		final List<GroupKey> ordered = new ArrayList<>(corpus.size());
		for (final Map.Entry<GroupKey, Set<String>> entry : corpus.entrySet()) {
			if (entry.getValue().size() >= minValues) {
				ordered.add(entry.getKey());
			}
		}
		ordered.sort((left, right) -> Integer.compare(corpus.get(right).size(), corpus.get(left).size()));
		return topGroups > 0 && topGroups < ordered.size()
			? new ArrayList<>(ordered.subList(0, topGroups))
			: ordered;
	}

	/* ========================================= support =========================================== */

	/**
	 * Measures one structure's deep-retained heap as a delta against an empty structure of the same type, so the
	 * constant framework graph of the structure itself does not count as index cost.
	 *
	 * @param structure   the populated structure
	 * @param empty       an empty structure of the same type
	 * @param measureHeap whether the JOL walk is performed at all
	 * @return the delta in bytes, or {@link #HEAP_NOT_MEASURED}
	 */
	private static long heapDelta(@Nonnull Object structure, @Nonnull Object empty, boolean measureHeap) {
		return measureHeap ? deepSize(structure) - deepSize(empty) : HEAP_NOT_MEASURED;
	}

	/**
	 * Deep-retained size of everything reachable from one root.
	 *
	 * @param root the object graph's root
	 * @return its total size in bytes
	 */
	private static long deepSize(@Nonnull Object root) {
		return GraphLayout.parseInstance(root).totalSize();
	}

	/**
	 * Nearest-rank percentile over an ascending array - the reported value always occurs in the data.
	 *
	 * @param sortedAscending observations, sorted ascending
	 * @param fraction        the percentile as a fraction, e.g. `0.95`
	 * @return the observation at that rank, or `0` for an empty input
	 */
	private static int percentile(@Nonnull int[] sortedAscending, double fraction) {
		if (sortedAscending.length == 0) {
			return 0;
		}
		final int rank = (int) Math.ceil(fraction * sortedAscending.length);
		return sortedAscending[Math.min(sortedAscending.length, Math.max(1, rank)) - 1];
	}

	/**
	 * Relative change between two counts, in percent, negative when the second is smaller.
	 *
	 * @param before original count
	 * @param after  new count
	 * @return the signed relative change
	 */
	private static double percentChange(long before, long after) {
		return before == 0L ? 0.0 : 100.0 * (after - before) / before;
	}

	/**
	 * Parses the swept thresholds and checks they are usable - ascending, non-negative and starting at the pure
	 * -Roaring baseline every relative figure is expressed against.
	 *
	 * @return the thresholds, ascending
	 */
	@Nonnull
	private static int[] parseThresholds() {
		final String[] parts = System.getProperty(THRESHOLDS_PROPERTY, DEFAULT_THRESHOLDS).split(",");
		final int[] thresholds = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			thresholds[i] = Integer.parseInt(parts[i].trim());
			if (thresholds[i] < 0 || (i > 0 && thresholds[i] <= thresholds[i - 1])) {
				throw new GenericEvitaInternalError(
					"Thresholds must be non-negative and strictly ascending, got `" +
						System.getProperty(THRESHOLDS_PROPERTY) + "`!",
					"Thresholds must be non-negative and strictly ascending!"
				);
			}
		}
		if (thresholds.length == 0 || thresholds[0] != 0) {
			throw new GenericEvitaInternalError(
				"The swept thresholds must start at 0 - the pure-Roaring baseline every relative figure is " +
					"expressed against!",
				"The swept thresholds must start at 0!"
			);
		}
		return thresholds;
	}

	/**
	 * Reads a comma-separated list property into a set.
	 *
	 * @param propertyName name of the property
	 * @return its entries, empty when the property is not set
	 */
	@Nonnull
	private static Set<String> parseListProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		final Set<String> entries = new TreeSet<>();
		final String[] parts = value.split(",");
		for (int i = 0; i < parts.length; i++) {
			final String trimmed = parts[i].trim();
			if (!trimmed.isEmpty()) {
				entries.add(trimmed);
			}
		}
		return entries;
	}

	/**
	 * Reads a required system property.
	 *
	 * @param propertyName name of the property
	 * @return its value
	 */
	@Nonnull
	private static String requiredProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set (or pass the corpus path as the " +
					"first command-line argument).",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/* ==================================== measured structures ==================================== */

	/**
	 * The sorted-key variant: an ascending primitive key column and a parallel posting column, looked up by
	 * binary search. This is the shape Stage 0 measured and the one a persisted key column would hold.
	 *
	 * @param keys     ascending trigram keys
	 * @param postings postings, parallel to `keys`
	 */
	private record SortedKeyIndex(@Nonnull long[] keys, @Nonnull Object[] postings) {

		/**
		 * The empty structure of the same type, subtracted from every measurement.
		 */
		static final SortedKeyIndex EMPTY = new SortedKeyIndex(new long[0], new Object[0]);

		/**
		 * @param key packed trigram to look up
		 * @return its posting, or `null` when the key is absent
		 */
		@Nullable
		Object get(long key) {
			final int index = Arrays.binarySearch(this.keys, key);
			return index < 0 ? null : this.postings[index];
		}
	}

	/* ======================================= data carriers ======================================= */

	/**
	 * Identity of one measured group: the index a trigram structure would be built for is per attribute and per
	 * locale, so these are the units the whole report is expressed in.
	 *
	 * @param entityType    collection the attribute belongs to
	 * @param attributeName the attribute
	 * @param localeTag     BCP-47 tag, empty for a non-localized attribute
	 */
	private record GroupKey(
		@Nonnull String entityType, @Nonnull String attributeName, @Nonnull String localeTag
	) implements Comparable<GroupKey> {

		/**
		 * @return the group's heading line
		 */
		@Nonnull
		String display() {
			return this.entityType + " / " + this.attributeName +
				(this.localeTag.isEmpty() ? "" : " / " + this.localeTag);
		}

		@Override
		public int compareTo(@Nonnull GroupKey other) {
			final int byType = this.entityType.compareTo(other.entityType);
			if (byType != 0) {
				return byType;
			}
			final int byName = this.attributeName.compareTo(other.attributeName);
			return byName != 0 ? byName : this.localeTag.compareTo(other.localeTag);
		}
	}

	/**
	 * The inverted corpus of one group, in ascending key order.
	 *
	 * @param keys        ascending trigram keys
	 * @param postings    sorted value-id arrays, parallel to `keys`
	 * @param memberships total posting cardinality across all keys - `E`
	 */
	private record PostingColumns(@Nonnull long[] keys, @Nonnull int[][] postings, long memberships) {
	}

	/**
	 * Everything measured about one key-map variant.
	 *
	 * @param heapBytes          JOL deep-retained heap, or {@link #HEAP_NOT_MEASURED}
	 * @param postingObjectsHeap heap of the posting objects alone, excluding any reference spine
	 * @param nanosPerLookup     coarse lookup probe result
	 * @param keyCount           how many keys the structure holds
	 * @param capacity           how many slots it holds them in, or {@link #CAPACITY_NOT_APPLICABLE}
	 */
	private record VariantMeasurement(
		long heapBytes, long postingObjectsHeap, double nanosPerLookup, int keyCount, int capacity
	) {

		/**
		 * Reported by a variant whose slot count is not a property of the design under test - the sorted column
		 * has exactly one slot per key, and the boxed map's table size is an implementation detail of `HashMap`.
		 */
		static final int CAPACITY_NOT_APPLICABLE = -1;

		/**
		 * @return everything the variant holds beyond the posting objects themselves
		 */
		long keyOverheadBytes() {
			return this.heapBytes - this.postingObjectsHeap;
		}

		/**
		 * @return the key overhead per stored key
		 */
		double bytesPerKey() {
			return this.keyCount == 0 ? 0.0 : (double) keyOverheadBytes() / this.keyCount;
		}

		/**
		 * @return key count over slot count, or `0` when the variant reports no slot count
		 */
		double loadFactor() {
			return this.capacity <= 0 ? 0.0 : (double) this.keyCount / this.capacity;
		}
	}

	/**
	 * Everything measured about one swept small-posting threshold.
	 *
	 * @param heapBytes        JOL deep-retained heap, or {@link #HEAP_NOT_MEASURED}
	 * @param serializedBytes  key column plus every posting, per the class JavaDoc's accounting
	 * @param smallKeys        keys whose posting is stored as a sorted `int[]`
	 * @param smallMemberships memberships held in those `int[]` postings
	 * @param smallContainers  Roaring containers those postings held before they were converted
	 * @param totalKeys        the group's `K`, so the shares below need no second argument
	 * @param totalMemberships the group's `E`
	 */
	private record SweepMeasurement(
		long heapBytes, long serializedBytes, int smallKeys, long smallMemberships, long smallContainers,
		int totalKeys, long totalMemberships
	) {

		/**
		 * @return Roaring containers per converted posting - the overhead the conversion actually removed
		 */
		double containersPerSmallKey() {
			return this.smallKeys == 0 ? 0.0 : (double) this.smallContainers / this.smallKeys;
		}

		/**
		 * @return share of keys whose posting is stored as a sorted `int[]`, in percent
		 */
		double smallKeyShare() {
			return this.totalKeys == 0 ? 0.0 : 100.0 * this.smallKeys / this.totalKeys;
		}

		/**
		 * @return share of memberships held in `int[]` postings, in percent
		 */
		double smallMembershipShare() {
			return this.totalMemberships == 0L ? 0.0 : 100.0 * this.smallMemberships / this.totalMemberships;
		}
	}

	/**
	 * The aggregatable subset of one group's numbers.
	 *
	 * @param valueCount  `V`
	 * @param keyCount    `K`
	 * @param memberships `E`
	 * @param variants    the key-map measurements, in {@link #VARIANT_LABELS} order
	 * @param sweep       the threshold measurements, in the swept order
	 */
	private record GroupResult(
		int valueCount, int keyCount, long memberships,
		@Nonnull VariantMeasurement[] variants, @Nonnull SweepMeasurement[] sweep
	) {
	}
}
