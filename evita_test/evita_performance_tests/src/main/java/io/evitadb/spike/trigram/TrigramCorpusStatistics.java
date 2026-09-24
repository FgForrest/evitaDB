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

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * **A2 of the P8 trigram-substring-index plan** - reads the TSV corpus produced by
 * {@link TrigramCorpusExtractor} and reports, for every `entityType / attributeName / locale` group, what a
 * trigram index over it would actually cost. This is the measurement gate G0 is decided on, so everything it
 * prints is measured on a structure that was really built, never estimated from a formula.
 *
 * # What is computed per group
 *
 * - `N` (value occurrences), `V` (distinct normalized values) and their ratio `N/V` - the reuse factor the whole
 *   `valueId` argument rests on (brief §5);
 * - value length percentiles in Unicode code points, measured over the **distinct** values, because it is `V`
 *   values - not `N` occurrences - that the index actually stores;
 * - `U` (average distinct trigrams per value), `K` (distinct trigram keys) and `E` (trigram-to-value
 *   memberships);
 * - the posting-cardinality distribution, which is what decides whether the small-posting representation of
 *   brief §29 is worth having;
 * - **both index variants, really built**: `A` = `trigram → RoaringBitmap<entityPK>` and
 *   `B` = `trigram → RoaringBitmap<valueId>`, each measured for serialized size, Roaring container mix and JOL
 *   deep-retained heap. Their ratio is the number brief §26 asks for, and the prediction it tests is
 *   `A/B ≈ N/V`;
 * - the **case-fold delta** of issue #545: how many distinct values, trigram keys and memberships a
 *   locale-aware fold would merge away.
 *
 * # How the numbers are produced
 *
 * **Bitmaps are the engine's own.** Postings are `PersistentRoaringBitmap` - the vendored, copy-on-write
 * Roaring implementation `evita_engine` really holds - and not upstream `org.roaringbitmap.RoaringBitmap`, so
 * the heap figure includes the per-bitmap `shared[]` flag array a production posting would carry.
 *
 * **Heap is JOL deep-retained, as a delta against the empty structure of the same type**, exactly as
 * `BucketStoreMemorySpike` measures. The measured object is a bare `long[]` key column plus its posting array -
 * the shape Stage 1 will prototype - and deliberately not the `HashMap` used to build it, because brief §10's
 * whole point is that a boxed-key map is not the structure being proposed.
 *
 * **The container mix is read out of the serialized bitmap, not inferred.** The vendored Roaring keeps
 * `ContainerPointer` package-private, so the census parses the portable serialization header - cookie, run-flag
 * bitmap, per-container key and cardinality - which states the container types outright. Nothing here
 * re-implements Roaring's container-selection rules, so nothing here can disagree with them.
 *
 * `runOptimize()` is reported as a **second** serialized size and container census, taken after the heap
 * measurement. The engine does not run-optimize its bitmaps, so the primary numbers are the as-built ones; the
 * optimized pair says what persistence could save if it did.
 *
 * # Percentile convention
 *
 * Nearest-rank: `P(f)` is the smallest observation at or above rank `ceil(f × n)` in ascending order. No
 * interpolation, so every reported percentile is a value that really occurs in the data.
 *
 * # Running it
 *
 * ```shell
 * java -Xmx16g -Djol.magicFieldOffset=true \
 *   -Devita.trigram.corpusFile=/path/to/demo-corpus.tsv \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.spike.trigram.TrigramCorpusStatistics
 * ```
 *
 * The corpus path may also be given as the first command-line argument. `-Devita.trigram.measureHeap=false`
 * skips the JOL walks, which are the slow part on a large corpus.
 *
 * @author Claude (P8 trigram-substring-index spike), FG Forrest a.s. (c) 2026
 */
public class TrigramCorpusStatistics {

	/**
	 * System property naming the TSV corpus to analyze; the first command-line argument overrides it.
	 */
	public static final String CORPUS_FILE_PROPERTY = "evita.trigram.corpusFile";

	/**
	 * System property switching the JOL heap walks off (`false`), leaving only the serialized sizes.
	 */
	public static final String MEASURE_HEAP_PROPERTY = "evita.trigram.measureHeap";

	/**
	 * System property skipping groups with fewer than this many value occurrences, so a report over a big
	 * catalog is not dominated by two-value attributes.
	 */
	public static final String MIN_OCCURRENCES_PROPERTY = "evita.trigram.minOccurrences";

	/**
	 * Roaring's portable-format cookie for a bitmap that may contain run containers.
	 */
	private static final int SERIAL_COOKIE = 12347;

	/**
	 * Roaring's portable-format cookie for a bitmap that contains no run container.
	 */
	private static final int SERIAL_COOKIE_NO_RUNCONTAINER = 12346;

	/**
	 * Highest cardinality Roaring keeps in a sorted-array container; above it the chunk becomes a bitmap.
	 */
	private static final int ARRAY_CONTAINER_MAX_CARDINALITY = 4096;

	/**
	 * Sentinel heap figure meaning "JOL was switched off", so a skipped measurement can never be read as zero.
	 */
	private static final long HEAP_NOT_MEASURED = -1L;

	public static void main(@Nonnull String[] args) throws IOException {
		// guards every number below: a silently broken codec would not fail anything, it would just make the
		// whole report describe an index nobody is proposing
		SpikeTrigramCodec.selfCheck();

		final Path corpusFile = args.length > 0
			? Path.of(args[0])
			: Path.of(requiredProperty(CORPUS_FILE_PROPERTY));
		final boolean measureHeap = Boolean.parseBoolean(System.getProperty(MEASURE_HEAP_PROPERTY, "true"));
		final int minOccurrences = Integer.parseInt(System.getProperty(MIN_OCCURRENCES_PROPERTY, "0"));

		System.out.printf("Trigram corpus statistics - %s%n", corpusFile);
		System.out.printf(
			"Normalization: NFD (FilterIndex contract), case-sensitive. Heap measurement: %s.%n%n",
			measureHeap ? "JOL deep-retained" : "SKIPPED"
		);

		final Map<GroupKey, GroupCorpus> corpus = loadCorpus(corpusFile);
		final List<GroupResult> results = new ArrayList<>(corpus.size());
		for (final Map.Entry<GroupKey, GroupCorpus> entry : corpus.entrySet()) {
			final GroupCorpus group = entry.getValue();
			if (group.occurrenceCount() < minOccurrences || group.distinctValues().isEmpty()) {
				continue;
			}
			results.add(analyzeGroup(entry.getKey(), group, measureHeap));
		}
		printTotals(results, measureHeap);
	}

	/* ======================================== analysis =========================================== */

	/**
	 * Measures one `entityType / attributeName / locale` group and prints its section of the report.
	 *
	 * @param key         identity of the group
	 * @param group       the group's corpus
	 * @param measureHeap whether the JOL walks are performed
	 * @return the aggregatable subset of the group's numbers
	 */
	@Nonnull
	private static GroupResult analyzeGroup(
		@Nonnull GroupKey key, @Nonnull GroupCorpus group, boolean measureHeap
	) {
		final List<String> distinctValues = group.distinctValues();
		final int valueCount = distinctValues.size();
		final int occurrences = group.occurrenceCount();

		// trigram sets are extracted once and shared by both variants - extraction is the dominant CPU cost and
		// re-running it per variant would triple the analyzer's runtime for no additional information
		final long[][] trigramsByValueId = new long[valueCount][];
		final int[] valueLengths = new int[valueCount];
		long memberships = 0L;
		for (int valueId = 0; valueId < valueCount; valueId++) {
			final String value = distinctValues.get(valueId);
			trigramsByValueId[valueId] = SpikeTrigramCodec.extractUniqueTrigrams(value);
			valueLengths[valueId] = SpikeTrigramCodec.codePointCount(value);
			memberships += trigramsByValueId[valueId].length;
		}
		Arrays.sort(valueLengths);

		System.out.printf("=== %s ===%n", key.display());
		System.out.printf(
			"  N (value occurrences)               : %,15d%n" +
				"  V (distinct normalized values)      : %,15d   (N/V = %.2f)%n",
			occurrences, valueCount, (double) occurrences / valueCount
		);
		System.out.printf(
			"  value length in code points         : P50 %,d  P95 %,d  P99 %,d  max %,d%n",
			percentile(valueLengths, 0.50), percentile(valueLengths, 0.95),
			percentile(valueLengths, 0.99), valueLengths[valueLengths.length - 1]
		);
		System.out.printf(
			"  U (distinct trigrams per value)     : %,15.2f%n" +
				"  E (trigram -> valueId memberships)  : %,15d%n",
			(double) memberships / valueCount, memberships
		);

		final TrigramIndex indexByValueId = buildByValueId(trigramsByValueId);
		System.out.printf("  K (distinct trigram keys)           : %,15d%n", indexByValueId.keys().length);
		final VariantMeasurement byValueId = measureVariant(
			indexByValueId, measureHeap, "posting cardinality (variant B)"
		);
		final VariantMeasurement byEntityPk = measureVariant(
			buildByEntityPk(trigramsByValueId, group), measureHeap, null
		);
		printVariant("A) trigram -> RoaringBitmap<entityPK>", byEntityPk);
		printVariant("B) trigram -> RoaringBitmap<valueId>", byValueId);
		printRatio(byEntityPk, byValueId, (double) occurrences / valueCount);

		final FoldedMeasurement folded = measureCaseFold(distinctValues, key.locale());
		System.out.printf(
			"  #545 case fold                      : V %,d -> %,d (%+.1f%%)  K %,d -> %,d (%+.1f%%)  " +
				"E %,d -> %,d (%+.1f%%)%n%n",
			valueCount, folded.distinctValues(), percentChange(valueCount, folded.distinctValues()),
			byValueId.keyCount(), folded.keyCount(), percentChange(byValueId.keyCount(), folded.keyCount()),
			memberships, folded.memberships(), percentChange(memberships, folded.memberships())
		);

		return new GroupResult(
			occurrences, valueCount, memberships, byEntityPk, byValueId,
			folded.distinctValues(), folded.keyCount(), folded.memberships()
		);
	}

	/**
	 * Builds variant `B`: every distinct value contributes its own id to each of its trigrams. This is the index
	 * the design proposes, and its posting population is a function of `V` alone.
	 *
	 * @param trigramsByValueId per-value trigram sets, indexed by value id
	 * @return the built index in its measured shape
	 */
	@Nonnull
	private static TrigramIndex buildByValueId(@Nonnull long[][] trigramsByValueId) {
		final Map<Long, PersistentRoaringBitmap> postings = new HashMap<>(trigramsByValueId.length * 2);
		for (int valueId = 0; valueId < trigramsByValueId.length; valueId++) {
			final long[] trigrams = trigramsByValueId[valueId];
			for (int i = 0; i < trigrams.length; i++) {
				postings.computeIfAbsent(trigrams[i], key -> new PersistentRoaringBitmap()).add(valueId);
			}
		}
		return TrigramIndex.of(postings);
	}

	/**
	 * Builds variant `A`: every *occurrence* contributes its entity primary key to each trigram of the value it
	 * carries. This is the conventional entity-keyed trigram index, and its posting population is a function of
	 * `N`. Postings are still added value by value rather than trigram by trigram, so an entity that carries the
	 * same value twice - an array attribute with a repeated element - contributes the primary key once, exactly
	 * as a set-valued posting list would.
	 *
	 * @param trigramsByValueId per-value trigram sets, indexed by value id
	 * @param group             the group's corpus, supplying the occurrence-to-value mapping
	 * @return the built index in its measured shape
	 */
	@Nonnull
	private static TrigramIndex buildByEntityPk(@Nonnull long[][] trigramsByValueId, @Nonnull GroupCorpus group) {
		final Map<Long, PersistentRoaringBitmap> postings = new HashMap<>(trigramsByValueId.length * 2);
		final int[] valueIds = group.valueIdPerOccurrence();
		final int[] primaryKeys = group.primaryKeyPerOccurrence();
		for (int occurrence = 0; occurrence < valueIds.length; occurrence++) {
			final long[] trigrams = trigramsByValueId[valueIds[occurrence]];
			final int primaryKey = primaryKeys[occurrence];
			for (int i = 0; i < trigrams.length; i++) {
				postings.computeIfAbsent(trigrams[i], key -> new PersistentRoaringBitmap()).add(primaryKey);
			}
		}
		return TrigramIndex.of(postings);
	}

	/**
	 * Measures one built variant: serialized size, container mix, deep-retained heap, and - after all of those -
	 * what `runOptimize()` would change. Optionally prints the posting-cardinality distribution, which is a
	 * property of the corpus rather than of the variant and is therefore reported only once.
	 *
	 * @param index                the built index
	 * @param measureHeap          whether the JOL walk is performed
	 * @param cardinalityLabel     heading of the posting-cardinality line, or `null` to omit it
	 * @return the measurement
	 */
	@Nonnull
	private static VariantMeasurement measureVariant(
		@Nonnull TrigramIndex index, boolean measureHeap, @Nullable String cardinalityLabel
	) {
		final PersistentRoaringBitmap[] postings = index.postings();
		final int[] cardinalities = new int[postings.length];
		long serialized = 0L;
		long storedMemberships = 0L;
		int scratchCapacity = 1024;
		for (int i = 0; i < postings.length; i++) {
			cardinalities[i] = postings[i].getCardinality();
			storedMemberships += cardinalities[i];
			final int size = postings[i].serializedSizeInBytes();
			serialized += size;
			scratchCapacity = Math.max(scratchCapacity, size);
		}
		final ByteBuffer scratch = ByteBuffer.allocate(scratchCapacity).order(ByteOrder.LITTLE_ENDIAN);
		final int[] census = new int[4];
		for (int i = 0; i < postings.length; i++) {
			censusContainers(postings[i], scratch, census);
		}

		if (cardinalityLabel != null) {
			final int[] sorted = cardinalities.clone();
			Arrays.sort(sorted);
			System.out.printf(
				"  %-35s : P50 %,d  P90 %,d  P95 %,d  P99 %,d  max %,d%n",
				cardinalityLabel,
				percentile(sorted, 0.50), percentile(sorted, 0.90), percentile(sorted, 0.95),
				percentile(sorted, 0.99), sorted.length == 0 ? 0 : sorted[sorted.length - 1]
			);
		}

		// heap is measured on the as-built structure, because that is what the engine would hold; runOptimize
		// afterwards would silently change what the number describes
		final long heap = measureHeap
			? GraphLayout.parseInstance(index).totalSize() - GraphLayout.parseInstance(TrigramIndex.EMPTY).totalSize()
			: HEAP_NOT_MEASURED;

		long optimizedSerialized = 0L;
		final int[] optimizedCensus = new int[4];
		for (int i = 0; i < postings.length; i++) {
			postings[i].runOptimize();
			optimizedSerialized += postings[i].serializedSizeInBytes();
			censusContainers(postings[i], scratch, optimizedCensus);
		}

		return new VariantMeasurement(
			postings.length, (long) index.keys().length * Long.BYTES, serialized, optimizedSerialized, heap,
			new ContainerCensus(census[0], census[1], census[2], census[3]),
			new ContainerCensus(optimizedCensus[0], optimizedCensus[1], optimizedCensus[2], optimizedCensus[3]),
			storedMemberships
		);
	}

	/**
	 * Recomputes `V`, `K` and `E` over the case-folded corpus, quantifying what issue #545 would merge.
	 *
	 * @param distinctValues the group's distinct normalized values
	 * @param locale         locale whose casing rules apply, `Locale.ROOT` for a non-localized attribute
	 * @return the folded counts
	 */
	@Nonnull
	private static FoldedMeasurement measureCaseFold(@Nonnull List<String> distinctValues, @Nonnull Locale locale) {
		final Set<String> foldedValues = new HashSet<>(distinctValues.size());
		for (int i = 0; i < distinctValues.size(); i++) {
			foldedValues.add(SpikeTrigramCodec.foldCase(distinctValues.get(i), locale));
		}
		// keys are collected into a primitive column and deduplicated by sorting rather than into a `Set<Long>`,
		// which would box every one of what can be tens of millions of keys on a production-sized corpus
		final CompositeLongArray foldedKeys = new CompositeLongArray();
		long foldedMemberships = 0L;
		for (final String foldedValue : foldedValues) {
			final long[] trigrams = SpikeTrigramCodec.extractUniqueTrigrams(foldedValue);
			foldedMemberships += trigrams.length;
			for (int i = 0; i < trigrams.length; i++) {
				foldedKeys.add(trigrams[i]);
			}
		}
		return new FoldedMeasurement(foldedValues.size(), countDistinct(foldedKeys.toArray()), foldedMemberships);
	}

	/* ==================================== Roaring census ========================================= */

	/**
	 * Counts one bitmap's containers by type, straight out of its portable serialization header.
	 *
	 * The header states everything needed: the cookie says whether any run container is present, an optional
	 * run-flag bitmap says which chunks are run containers, and each remaining chunk is an array container up to
	 * {@link #ARRAY_CONTAINER_MAX_CARDINALITY} and a bitmap container above it. Reading it beats re-implementing
	 * Roaring's container-selection rules, which could drift from the vendored implementation without anything
	 * noticing.
	 *
	 * @param bitmap   the posting to inspect
	 * @param scratch  reusable buffer, at least `serializedSizeInBytes()` large and little-endian
	 * @param counters four-slot accumulator of `{total, array, bitmap, run}`
	 */
	private static void censusContainers(
		@Nonnull PersistentRoaringBitmap bitmap, @Nonnull ByteBuffer scratch, @Nonnull int[] counters
	) {
		scratch.clear();
		bitmap.serialize(scratch);
		scratch.rewind();
		final int cookie = scratch.getInt();
		final int chunks;
		final boolean hasRunContainers;
		if ((cookie & 0xFFFF) == SERIAL_COOKIE) {
			chunks = (cookie >>> 16) + 1;
			hasRunContainers = true;
		} else if (cookie == SERIAL_COOKIE_NO_RUNCONTAINER) {
			chunks = scratch.getInt();
			hasRunContainers = false;
		} else {
			throw new GenericEvitaInternalError(
				"Unexpected Roaring serialization cookie `" + cookie + "` - the vendored format changed!",
				"Unexpected Roaring serialization cookie!"
			);
		}
		final int runFlagOffset = scratch.position();
		if (hasRunContainers) {
			scratch.position(runFlagOffset + (chunks + 7) / 8);
		}
		for (int chunk = 0; chunk < chunks; chunk++) {
			scratch.getChar(); // the chunk's high 16 bits, not needed for a census
			final int cardinality = (scratch.getChar() & 0xFFFF) + 1;
			counters[0]++;
			if (hasRunContainers && (scratch.get(runFlagOffset + (chunk >> 3)) & (1 << (chunk & 7))) != 0) {
				counters[3]++;
			} else if (cardinality > ARRAY_CONTAINER_MAX_CARDINALITY) {
				counters[2]++;
			} else {
				counters[1]++;
			}
		}
	}

	/* ======================================== reporting ========================================== */

	/**
	 * Prints one variant's measured block.
	 *
	 * @param label       which variant this is
	 * @param measurement the numbers
	 */
	private static void printVariant(@Nonnull String label, @Nonnull VariantMeasurement measurement) {
		System.out.printf("  %s%n", label);
		System.out.printf(
			"     keys                             : %,15d   (long[] %,d B)%n",
			measurement.keyCount(), measurement.keyArrayBytes()
		);
		System.out.printf(
			"     postings serialized              : %,15d B  (runOptimize: %,d B)%n",
			measurement.serializedBytes(), measurement.serializedOptimizedBytes()
		);
		System.out.printf(
			"     serialized total (keys+postings) : %,15d B%n",
			measurement.keyArrayBytes() + measurement.serializedBytes()
		);
		System.out.printf(
			"     containers                       : %s%n",
			measurement.census().display()
		);
		System.out.printf(
			"     containers after runOptimize     : %s%n",
			measurement.optimizedCensus().display()
		);
		if (measurement.heapBytes() == HEAP_NOT_MEASURED) {
			System.out.println("     JOL deep-retained heap           :        (not measured)");
		} else {
			final long storedMemberships = measurement.storedMemberships();
			System.out.printf(
				"     JOL deep-retained heap           : %,15d B  (%.1f B / membership)%n",
				measurement.heapBytes(),
				storedMemberships == 0L ? 0.0 : (double) measurement.heapBytes() / storedMemberships
			);
		}
	}

	/**
	 * Prints the A-versus-B comparison next to the reuse ratio it is predicted to track.
	 *
	 * @param byEntityPk  variant A
	 * @param byValueId   variant B
	 * @param reuseRatio  `N/V` for the group
	 */
	private static void printRatio(
		@Nonnull VariantMeasurement byEntityPk, @Nonnull VariantMeasurement byValueId, double reuseRatio
	) {
		final long serializedA = byEntityPk.keyArrayBytes() + byEntityPk.serializedBytes();
		final long serializedB = byValueId.keyArrayBytes() + byValueId.serializedBytes();
		final String heapRatio = byEntityPk.heapBytes() == HEAP_NOT_MEASURED
			? "n/a"
			: ratio(byEntityPk.heapBytes(), byValueId.heapBytes());
		System.out.printf(
			"  A/B ratio                           : serialized %s   heap %s   (N/V = %.2f)%n",
			ratio(serializedA, serializedB), heapRatio, reuseRatio
		);
	}

	/**
	 * Prints the catalog-wide roll-up. Sizes are additive because every group is a separate index; `K` and `E`
	 * are sums of per-group values and are not the cardinality of any single structure.
	 *
	 * @param results     per-group results
	 * @param measureHeap whether heap figures are present
	 */
	private static void printTotals(@Nonnull List<GroupResult> results, boolean measureHeap) {
		long occurrences = 0L;
		long distinctValues = 0L;
		long memberships = 0L;
		long keysA = 0L;
		long keysB = 0L;
		long serializedA = 0L;
		long serializedB = 0L;
		long heapA = 0L;
		long heapB = 0L;
		long foldedValues = 0L;
		long foldedKeys = 0L;
		long foldedMemberships = 0L;
		ContainerCensus censusB = ContainerCensus.EMPTY;
		for (int i = 0; i < results.size(); i++) {
			final GroupResult result = results.get(i);
			occurrences += result.occurrences();
			distinctValues += result.distinctValues();
			memberships += result.memberships();
			keysA += result.byEntityPk().keyCount();
			keysB += result.byValueId().keyCount();
			serializedA += result.byEntityPk().keyArrayBytes() + result.byEntityPk().serializedBytes();
			serializedB += result.byValueId().keyArrayBytes() + result.byValueId().serializedBytes();
			heapA += Math.max(0L, result.byEntityPk().heapBytes());
			heapB += Math.max(0L, result.byValueId().heapBytes());
			foldedValues += result.foldedDistinctValues();
			foldedKeys += result.foldedKeyCount();
			foldedMemberships += result.foldedMemberships();
			censusB = censusB.plus(result.byValueId().census());
		}
		System.out.printf("=== TOTAL over %d groups ===%n", results.size());
		System.out.printf(
			"  N %,d   V %,d   (N/V = %.2f)   E %,d%n",
			occurrences, distinctValues, distinctValues == 0 ? 0.0 : (double) occurrences / distinctValues,
			memberships
		);
		System.out.printf(
			"  A) trigram -> entityPK : %,d keys, serialized %,d B%s%n",
			keysA, serializedA, measureHeap ? String.format(Locale.ROOT, ", heap %,d B", heapA) : ""
		);
		System.out.printf(
			"  B) trigram -> valueId  : %,d keys, serialized %,d B%s%n",
			keysB, serializedB, measureHeap ? String.format(Locale.ROOT, ", heap %,d B", heapB) : ""
		);
		System.out.printf(
			"  A/B ratio              : serialized %s%s%n",
			ratio(serializedA, serializedB),
			measureHeap ? ", heap " + ratio(heapA, heapB) : ""
		);
		System.out.printf("  B container mix        : %s%n", censusB.display());
		System.out.printf(
			"  #545 case fold         : V %,d -> %,d (%+.1f%%), K %,d -> %,d (%+.1f%%), E %,d -> %,d (%+.1f%%)%n",
			distinctValues, foldedValues, percentChange(distinctValues, foldedValues),
			keysB, foldedKeys, percentChange(keysB, foldedKeys),
			memberships, foldedMemberships, percentChange(memberships, foldedMemberships)
		);
	}

	/* ========================================= loading =========================================== */

	/**
	 * Reads the TSV corpus into per-group structures, assigning every distinct normalized value its id in first
	 * -occurrence order. Raw values are not retained: normalization is idempotent for the analyzer's purposes and
	 * holding both forms would double the corpus's heap for nothing.
	 *
	 * @param corpusFile the TSV written by {@link TrigramCorpusExtractor}
	 * @return per-group corpora, ordered by group identity
	 */
	@Nonnull
	private static Map<GroupKey, GroupCorpus> loadCorpus(@Nonnull Path corpusFile) throws IOException {
		final Map<GroupKey, GroupCorpus> corpus = new TreeMap<>();
		long lineNumber = 0L;
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
				final GroupKey key = new GroupKey(columns[0], columns[1], columns[2]);
				corpus.computeIfAbsent(key, groupKey -> new GroupCorpus())
					.add(Integer.parseInt(columns[3]), unescape(columns[4]));
				line = reader.readLine();
			}
		}
		System.out.printf("Loaded %,d corpus lines into %,d groups.%n%n", lineNumber, corpus.size());
		return corpus;
	}

	/**
	 * Reverses {@link TrigramCorpusExtractor#escape(String)}.
	 *
	 * @param value escaped TSV field
	 * @return the original value
	 */
	@Nonnull
	static String unescape(@Nonnull String value) {
		if (value.indexOf('\\') < 0) {
			return value;
		}
		final StringBuilder unescaped = new StringBuilder(value.length());
		int i = 0;
		while (i < value.length()) {
			final char character = value.charAt(i++);
			if (character != '\\' || i >= value.length()) {
				unescaped.append(character);
				continue;
			}
			final char escaped = value.charAt(i++);
			switch (escaped) {
				case '\\' -> unescaped.append('\\');
				case 't' -> unescaped.append('\t');
				case 'n' -> unescaped.append('\n');
				case 'r' -> unescaped.append('\r');
				default -> throw new GenericEvitaInternalError(
					"Unknown escape sequence `\\" + escaped + "` in the corpus!",
					"Unknown escape sequence in the corpus!"
				);
			}
		}
		return unescaped.toString();
	}

	/* ========================================= support =========================================== */

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
	 * Counts distinct entries of an unsorted primitive column by sorting it in place.
	 *
	 * @param values the column, modified in place
	 * @return number of distinct entries
	 */
	private static int countDistinct(@Nonnull long[] values) {
		if (values.length == 0) {
			return 0;
		}
		Arrays.sort(values);
		int distinct = 1;
		for (int i = 1; i < values.length; i++) {
			if (values[i] != values[i - 1]) {
				distinct++;
			}
		}
		return distinct;
	}

	/**
	 * Formats an A-over-B ratio, reporting `n/a` rather than `NaN` when B is empty - an attribute whose values
	 * are all shorter than a trigram has no index at all, and a `NaN` in that row reads like a defect.
	 *
	 * @param numerator   variant A's figure
	 * @param denominator variant B's figure
	 * @return the formatted ratio
	 */
	@Nonnull
	private static String ratio(long numerator, long denominator) {
		return denominator == 0L
			? "n/a"
			: String.format(Locale.ROOT, "%.2fx", (double) numerator / denominator);
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
		 * @return the locale whose casing rules apply, `Locale.ROOT` when the attribute is not localized
		 */
		@Nonnull
		Locale locale() {
			return this.localeTag.isEmpty() ? Locale.ROOT : Locale.forLanguageTag(this.localeTag);
		}

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
	 * One group's corpus: the distinct normalized values in first-occurrence order, and the occurrence stream
	 * expressed as parallel value-id and primary-key columns.
	 */
	private static final class GroupCorpus {
		private final Map<String, Integer> valueIds = new LinkedHashMap<>();
		private final CompositeIntArray valueIdColumn = new CompositeIntArray();
		private final CompositeIntArray primaryKeyColumn = new CompositeIntArray();

		/**
		 * Records one value occurrence, normalizing it into the canonical stored form on the way in.
		 *
		 * @param primaryKey primary key of the owning entity
		 * @param rawValue   the raw value as extracted
		 */
		void add(int primaryKey, @Nonnull String rawValue) {
			final String normalized = SpikeTrigramCodec.normalize(rawValue);
			final Integer valueId = this.valueIds.computeIfAbsent(normalized, value -> this.valueIds.size());
			this.valueIdColumn.add(valueId);
			this.primaryKeyColumn.add(primaryKey);
		}

		/**
		 * @return the distinct normalized values, indexed by value id
		 */
		@Nonnull
		List<String> distinctValues() {
			return new ArrayList<>(this.valueIds.keySet());
		}

		/**
		 * @return number of recorded value occurrences
		 */
		int occurrenceCount() {
			return this.valueIdColumn.getSize();
		}

		/**
		 * @return the value id of every occurrence, in extraction order
		 */
		@Nonnull
		int[] valueIdPerOccurrence() {
			return this.valueIdColumn.toArray();
		}

		/**
		 * @return the entity primary key of every occurrence, in extraction order
		 */
		@Nonnull
		int[] primaryKeyPerOccurrence() {
			return this.primaryKeyColumn.toArray();
		}
	}

	/**
	 * The shape a built variant is measured in: a primitive key column and its postings, and nothing else. This
	 * is deliberately not the `HashMap` the build used - a boxed-key map is not the structure being proposed, and
	 * measuring one would inflate every heap figure with per-entry objects the design does not have.
	 *
	 * @param keys     ascending trigram keys
	 * @param postings postings, parallel to `keys`
	 */
	private record TrigramIndex(@Nonnull long[] keys, @Nonnull PersistentRoaringBitmap[] postings) {

		/**
		 * The empty structure of the same type, subtracted from every measurement so the constant framework
		 * graph of the record itself does not count as index cost.
		 */
		static final TrigramIndex EMPTY = new TrigramIndex(new long[0], new PersistentRoaringBitmap[0]);

		/**
		 * Converts the build-time map into the measured shape, ordering the keys ascending.
		 *
		 * @param postings the built postings, keyed by packed trigram
		 * @return the measured shape
		 */
		@Nonnull
		static TrigramIndex of(@Nonnull Map<Long, PersistentRoaringBitmap> postings) {
			final long[] keys = new long[postings.size()];
			int index = 0;
			for (final Long key : postings.keySet()) {
				keys[index++] = key;
			}
			Arrays.sort(keys);
			final PersistentRoaringBitmap[] ordered = new PersistentRoaringBitmap[keys.length];
			for (int i = 0; i < keys.length; i++) {
				ordered[i] = postings.get(keys[i]);
			}
			return new TrigramIndex(keys, ordered);
		}
	}

	/**
	 * Counts of Roaring containers by type across all of one variant's postings.
	 *
	 * @param total  number of containers
	 * @param array  sorted-array containers
	 * @param bitmap dense bitmap containers
	 * @param run    run-length containers
	 */
	private record ContainerCensus(int total, int array, int bitmap, int run) {

		/**
		 * Neutral element for aggregation.
		 */
		static final ContainerCensus EMPTY = new ContainerCensus(0, 0, 0, 0);

		/**
		 * @param other census to add
		 * @return the element-wise sum
		 */
		@Nonnull
		ContainerCensus plus(@Nonnull ContainerCensus other) {
			return new ContainerCensus(
				this.total + other.total, this.array + other.array,
				this.bitmap + other.bitmap, this.run + other.run
			);
		}

		/**
		 * @return the census as one report line
		 */
		@Nonnull
		String display() {
			return String.format(
				Locale.ROOT, "%,d total = %,d array / %,d bitmap / %,d run",
				this.total, this.array, this.bitmap, this.run
			);
		}
	}

	/**
	 * Everything measured about one built index variant.
	 *
	 * @param keyCount                 distinct trigram keys
	 * @param keyArrayBytes            size of the primitive key column
	 * @param serializedBytes          Roaring serialized size of all postings, as built
	 * @param serializedOptimizedBytes the same after `runOptimize()`
	 * @param heapBytes                JOL deep-retained heap, or `HEAP_NOT_MEASURED` when JOL was switched off
	 * @param census                   container mix as built
	 * @param optimizedCensus          container mix after `runOptimize()`
	 * @param storedMemberships        total posting cardinality across all keys
	 */
	private record VariantMeasurement(
		int keyCount, long keyArrayBytes, long serializedBytes, long serializedOptimizedBytes, long heapBytes,
		@Nonnull ContainerCensus census, @Nonnull ContainerCensus optimizedCensus, long storedMemberships
	) {
	}

	/**
	 * What a locale-aware case fold - issue #545's semantics - would leave of the group.
	 *
	 * @param distinctValues distinct values after folding
	 * @param keyCount       distinct trigram keys after folding
	 * @param memberships    trigram memberships after folding
	 */
	private record FoldedMeasurement(int distinctValues, int keyCount, long memberships) {
	}

	/**
	 * The aggregatable subset of one group's numbers.
	 *
	 * @param occurrences          `N`
	 * @param distinctValues       `V`
	 * @param memberships          `E`
	 * @param byEntityPk           variant A
	 * @param byValueId            variant B
	 * @param foldedDistinctValues `V` after the #545 fold
	 * @param foldedKeyCount       `K` after the #545 fold
	 * @param foldedMemberships    `E` after the #545 fold
	 */
	private record GroupResult(
		int occurrences, int distinctValues, long memberships,
		@Nonnull VariantMeasurement byEntityPk, @Nonnull VariantMeasurement byValueId,
		int foldedDistinctValues, int foldedKeyCount, long foldedMemberships
	) {
	}
}
