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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.index.trigram.StringSearchShape;
import io.evitadb.index.trigram.TrigramCodec;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * **The wide arm sweep of the trigram substring index.** Measures the accelerated path against the scan it displaces,
 * over a REAL corpus, at as many points of the selectivity axis as the corpus itself can supply - and writes one TSV
 * row per pattern so the crossover can be read off the data rather than argued from a constant.
 *
 * # What it measures, and at which seam
 *
 * The two arms are taken at the same two seams the engine takes them at, so the ratio this driver reports is the
 * ratio a query would see:
 *
 * - **scan** - {@link FilterIndex#getRecordsWhoseValuesContains(String)} followed by
 *   {@link io.evitadb.core.query.algebra.Formula#compute()},
 *   which is what `AttributeContainsTranslator` resolves through when no accelerator exists;
 * - **trigram** - {@link TrigramSubstringSearch#match} followed by {@link InvertedIndex#toFormula} and
 *   {@link io.evitadb.core.query.algebra.Formula#compute()}, which is the body of
 *   {@link io.evitadb.core.query.filter.translator.attribute.AbstractAttributeStringSearchTranslator}'s
 *   `createGlobalSubstringFormula`, with the exact predicate taken from
 *   {@link AttributeContainsTranslator#createPredicate()}
 *   rather than restated here.
 *
 * ## Where this diverges from the translator, and why
 *
 * Three deliberate differences, each of which would otherwise hide the very thing being measured:
 *
 * 1. **The timed trigram arm forces the gate** by handing `match` a counter that answers `Long.MAX_VALUE`. The
 *    translator lets the gate decline and falls back to the scan; a driver that did the same would produce no
 *    trigram number at all for the declined half of the axis, which is exactly the half that decides whether the
 *    gate is set in the right place. The real verdict is therefore observed **separately**, by a second, untimed
 *    `match` carrying the honest counter, and recorded in the `gate` column.
 * 2. **The honest counter is `sharedValueTree.getBucketCount()`**, not the translator's `sumDistinctValuesUpTo` walk
 *    over a target set. This driver measures a query whose target set IS the global index - the single-index case
 *    the translator's sum reduces to - so the two coincide here and the fan-out amortization the sum exists for is
 *    out of scope.
 * 3. **Nothing is memoised.** The translator folds the matched buckets inside `computeOnlyOnce`; every repetition
 *    here rebuilds the formula from scratch, on both arms, so what is timed is the uncached cost of one query rather
 *    than the cost of a cache hit.
 *
 * # The corpus, and what is done to it
 *
 * The input is the TSV {@link TrigramCorpusExtractor} writes - `#entityType TAB attributeName TAB locale TAB
 * entityPrimaryKey TAB value`, values escaped by {@link TrigramCorpusExtractor#escape(String)} and read back through
 * {@link TrigramCorpusStatistics#unescape(String)}. Values are grouped by **attribute name alone** (locales merged)
 * and **deduplicated**: a real corpus repeats values, both constants of {@link TrigramSubstringSearch} are expressed
 * in distinct values, and the axis this driver sweeps is a share of distinct values.
 *
 * One throwaway embedded catalog is built per measured attribute, holding one entity per distinct value, with the
 * attribute declared `filterable()` and `acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)` so that a
 * single built index serves BOTH arms - the scan reads the shared value tree the trigram postings name value ids in.
 * Unlike {@link io.evitadb.performance.substring.state.SubstringCatalogFixture}, whose generated corpus is asserted
 * all-distinct with `bucketCount == entityCount`, no such assertion is made here: normalization can collapse two raw
 * values onto one tree bucket, so the tree's own {@link InvertedIndex#getBucketCount()} is what every share is
 * expressed against.
 *
 * # The pattern ladder
 *
 * Patterns are **discovered from the corpus**, never planted - nothing is inserted to make a pattern land where the
 * driver wants it. Per attribute, the budget is split roughly 55/28/17 across three families drawn from a large
 * randomly sampled pool of real substrings, plus a handful of guaranteed-absent strings:
 *
 * 1. **length-3 ladder** - three-code-point substrings picked to span the bound share
 *    (the cheapest posting's cardinality over `distinctValues`) evenly on a LOG axis, from very rare to very
 *    common. This is the shape the crossover is read off, so it gets about half the budget;
 * 2. **multi-trigram** - substrings of 4..12 code points, spread round-robin across trigram counts 2..10;
 * 3. **hit-heavy** - the widest-bound substrings the pool produced, where the candidate set covers a large fraction
 *    of the corpus and the scan should win outright;
 * 4. **absent** - random ASCII strings verified to match nothing, exercising the provable-empty path that answers
 *    before the gate is consulted at all.
 *
 * # The correctness gate
 *
 * On real data there is no generated oracle to check either arm against, so **arm parity is the oracle**: before a
 * single measurement is taken, both arms are computed for every pattern and their bitmaps compared element by
 * element. A mismatch prints the pattern's shape and aborts the run by throwing - no TSV is written, because a
 * driver whose two arms disagree is measuring two different questions.
 *
 * # Configuration
 *
 * | Property | Meaning |
 * |---|---|
 * | `evita.sweep.corpusFile` | **required** - the TSV written by {@link TrigramCorpusExtractor} |
 * | `evita.sweep.attributes` | **required** - comma-separated attribute names to measure |
 * | `evita.sweep.outputFile` | **required** - the result TSV to write |
 * | `evita.sweep.storageDir` | **required** - scratch storage root, DELETED on exit |
 * | `evita.sweep.patternsPerAttribute` | pattern budget per attribute (default 60) |
 *
 * `evita.sweep.storageDir` must name a path this driver may own outright: its whole subtree is removed when the run
 * ends, successfully or not.
 *
 * ```shell
 * java -Xmx16g -cp target/benchmarks.jar io.evitadb.spike.trigram.TrigramArmSweep \
 *   -Devita.sweep.corpusFile=/data/corpus.tsv \
 *   -Devita.sweep.attributes=code,catalogNumber,ean \
 *   -Devita.sweep.outputFile=/data/arm-sweep.tsv \
 *   -Devita.sweep.storageDir=/tmp/evita-arm-sweep \
 *   -Devita.sweep.patternsPerAttribute=60
 * ```
 *
 * # Privacy
 *
 * The pattern TEXT is never written anywhere - not to the TSV, not to stdout, not into a failure message. A real
 * corpus is customer data and a substring of it is still customer data; only its SHAPE (lengths, trigram count,
 * cardinalities, gate verdict, timings) leaves this JVM.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TrigramArmSweep {

	/**
	 * System property naming the TSV corpus to read.
	 */
	public static final String CORPUS_FILE_PROPERTY = "evita.sweep.corpusFile";

	/**
	 * System property naming the comma-separated attribute groups to measure.
	 */
	public static final String ATTRIBUTES_PROPERTY = "evita.sweep.attributes";

	/**
	 * System property naming the result TSV to write.
	 */
	public static final String OUTPUT_FILE_PROPERTY = "evita.sweep.outputFile";

	/**
	 * System property naming the scratch storage root, whose whole subtree is removed on exit.
	 */
	public static final String STORAGE_DIR_PROPERTY = "evita.sweep.storageDir";

	/**
	 * System property naming the pattern budget per attribute.
	 */
	public static final String PATTERNS_PER_ATTRIBUTE_PROPERTY = "evita.sweep.patternsPerAttribute";

	/**
	 * Pattern budget used when {@link #PATTERNS_PER_ATTRIBUTE_PROPERTY} is not set.
	 */
	public static final int DEFAULT_PATTERNS_PER_ATTRIBUTE = 60;

	/**
	 * Header line of the produced TSV, written so the file describes itself; readers skip a leading `#`.
	 */
	static final String RESULT_HEADER = "#attribute\tdistinctValues\tpatternLength\tcodePointCount\ttrigramCount"
		+ "\tbound\tboundShare\tcandidates\tmatchedValues\tfalseCandidateRate\tgate"
		+ "\tscanMedianUs\tscanP10Us\tscanP90Us\ttrigramMedianUs\ttrigramP10Us\ttrigramP90Us\tspeedup";

	/**
	 * Name of the throwaway catalog built per measured attribute.
	 */
	private static final String CATALOG_NAME = "trigramArmSweep";

	/**
	 * Entity type of the throwaway collection - one entity per distinct corpus value.
	 */
	private static final String ENTITY_TYPE = "probe";

	/**
	 * The single attribute the throwaway collection carries. A fixed name rather than the corpus attribute's own,
	 * because a name lifted from a foreign schema is not guaranteed to pass evitaDB's own name validation; the
	 * corpus attribute is identified by the result TSV's `attribute` column instead.
	 */
	private static final String ATTRIBUTE_NAME = "probedValue";

	/**
	 * Seed of every sampling decision, so two runs over the same corpus pick the same patterns and their numbers can
	 * be compared directly.
	 */
	private static final long SAMPLING_SEED = 0x5EE_D_5_1EL;

	/**
	 * Lowest trigram count a multi-trigram pattern is accepted at.
	 */
	private static final int MINIMAL_MULTI_TRIGRAM_COUNT = 2;

	/**
	 * Highest trigram count a multi-trigram pattern is accepted at.
	 */
	private static final int MAXIMAL_MULTI_TRIGRAM_COUNT = 10;

	/**
	 * Shortest multi-trigram pattern, in code points.
	 */
	private static final int MINIMAL_MULTI_PATTERN_LENGTH = 4;

	/**
	 * Longest multi-trigram pattern, in code points.
	 */
	private static final int MAXIMAL_MULTI_PATTERN_LENGTH = 12;

	/**
	 * Length, in characters, of a generated absent pattern - long enough that a random draw over
	 * {@link #ABSENT_ALPHABET} is all but certainly absent, and still short enough to be a plausible search term.
	 */
	private static final int ABSENT_PATTERN_LENGTH = 8;

	/**
	 * The characters a generated absent pattern is drawn from.
	 */
	private static final String ABSENT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

	/**
	 * Minimum repetitions of the warm-up phase, per arm per pattern.
	 */
	private static final int WARMUP_MINIMAL_REPETITIONS = 25;

	/**
	 * Minimum duration of the warm-up phase, per arm per pattern.
	 */
	private static final long WARMUP_MINIMAL_NANOS = 500_000_000L;

	/**
	 * Minimum repetitions of the measured phase, per arm per pattern.
	 */
	private static final int MEASUREMENT_MINIMAL_REPETITIONS = 40;

	/**
	 * Minimum duration of the measured phase, per arm per pattern.
	 */
	private static final long MEASUREMENT_MINIMAL_NANOS = 900_000_000L;

	/**
	 * Hard cap on measured repetitions, so a pattern that answers in nanoseconds cannot spend the whole budget.
	 */
	private static final int MEASUREMENT_MAXIMAL_REPETITIONS = 4_000;

	/**
	 * Every computed cardinality is accumulated here so no arm can be optimized away. Written from the single
	 * measuring thread only - `volatile` is what keeps the write observable rather than what makes it atomic.
	 */
	@SuppressWarnings("unused")
	private static volatile long sink;

	private TrigramArmSweep() {
		throw new UnsupportedOperationException("TrigramArmSweep is a driver and must not be instantiated!");
	}

	/**
	 * Runs the sweep: reads the corpus, builds one throwaway catalog per requested attribute, proves the two arms
	 * agree, measures both, writes the TSV and prints the summary.
	 *
	 * @param args ignored - everything is configured through the system properties named in the class javadoc
	 * @throws IOException when the corpus cannot be read or the result cannot be written
	 */
	public static void main(@Nonnull String[] args) throws IOException {
		final Path corpusFile = Path.of(requiredProperty(CORPUS_FILE_PROPERTY));
		final Path outputFile = Path.of(requiredProperty(OUTPUT_FILE_PROPERTY));
		final Path storageRoot = Path.of(requiredProperty(STORAGE_DIR_PROPERTY));
		final List<String> attributes = parseAttributes(requiredProperty(ATTRIBUTES_PROPERTY));
		final int budget = parsePatternBudget();

		final Map<String, List<String>> corpus = loadDistinctValues(corpusFile, Set.copyOf(attributes));
		final List<SweepRow> rows = new ArrayList<>(attributes.size() * budget);
		final Map<String, AttributeSummary> summaries = new LinkedHashMap<>(attributes.size());
		try {
			Files.createDirectories(storageRoot);
			for (final String attribute : attributes) {
				final List<String> values = corpus.get(attribute);
				if (values == null || values.isEmpty()) {
					throw new GenericEvitaInternalError(
						"The corpus `" + corpusFile + "` holds no value for attribute `" + attribute + "` - the "
							+ "requested group cannot be measured!",
						"The corpus holds no value for a requested attribute!"
					);
				}
				final List<SweepRow> attributeRows = sweepAttribute(attribute, values, storageRoot, budget);
				rows.addAll(attributeRows);
				summaries.put(
					attribute,
					new AttributeSummary(
						attributeRows.isEmpty() ? 0 : attributeRows.get(0).distinctValues(),
						attributeRows.size(),
						crossoverBoundShare(attributeRows)
					)
				);
			}
			writeResults(outputFile, rows);
		} finally {
			deleteQuietly(storageRoot);
		}
		printSummary(outputFile, summaries);
	}

	/* ===================================== one attribute ======================================== */

	/**
	 * Builds the throwaway catalog for one attribute, discovers its pattern ladder, proves arm parity and measures
	 * both arms.
	 *
	 * The instance is closed and its storage removed before returning, so a sweep over several attributes never holds
	 * two production-sized catalogs in one heap.
	 *
	 * @param attribute   name of the corpus attribute being measured, reproduced in the result rows
	 * @param values      its distinct values, in first-appearance order
	 * @param storageRoot the scratch storage root this attribute gets a subfolder of
	 * @param budget      how many patterns to measure
	 * @return one row per measured pattern
	 * @throws IOException when the scratch storage cannot be prepared
	 */
	@Nonnull
	private static List<SweepRow> sweepAttribute(
		@Nonnull String attribute,
		@Nonnull List<String> values,
		@Nonnull Path storageRoot,
		int budget
	) throws IOException {
		final Path storageDirectory = storageRoot.resolve(attribute);
		Files.createDirectories(storageDirectory);
		final long startedAt = System.currentTimeMillis();
		final Evita evita = bootInstance(storageDirectory, values);
		try {
			final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(CATALOG_NAME);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(ENTITY_TYPE);
			final GlobalEntityIndex globalIndex = collection.getGlobalIndex();
			final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex filterIndex = globalIndex.getFilterIndex(attributeIndexKey);
			if (filterIndex == null) {
				throw new GenericEvitaInternalError(
					"Attribute `" + attribute + "` has no filter index - the schema was not applied!",
					"The measured attribute has no filter index!"
				);
			}
			final TrigramIndex trigramIndex = globalIndex.getTrigramIndex(attributeIndexKey);
			if (trigramIndex == null) {
				throw new GenericEvitaInternalError(
					"Attribute `" + attribute + "` declared `AttributeFilterAccelerator.SUBSTRING_SEARCH` but carries "
						+ "no trigram index - the trigram arm would silently measure the scan and report a speedup of "
						+ "exactly one!",
					"The measured attribute has no trigram index!"
				);
			}
			final InvertedIndex sharedValueTree = filterIndex.getInvertedIndex();
			final int distinctValues = sharedValueTree.getBucketCount();
			System.out.printf(
				Locale.ROOT,
				"[arm-sweep] %s: built in %,d ms - %,d raw values, %,d tree buckets, %,d trigrams%n",
				attribute, System.currentTimeMillis() - startedAt, values.size(), distinctValues,
				trigramIndex.getTrigramCount()
			);

			final BiPredicate<String, String> exactPredicate = AttributeContainsTranslator.createPredicate();
			final List<PatternShape> shapes = discoverPatterns(
				values, filterIndex, sharedValueTree, trigramIndex, exactPredicate, budget
			);
			System.out.printf(Locale.ROOT, "[arm-sweep] %s: %,d patterns discovered%n", attribute, shapes.size());

			verifyArmParity(attribute, shapes, filterIndex, sharedValueTree, trigramIndex, exactPredicate);
			System.out.printf(Locale.ROOT, "[arm-sweep] %s: arm parity holds for every pattern%n", attribute);

			return measure(
				attribute, distinctValues, shapes, filterIndex, sharedValueTree, trigramIndex, exactPredicate
			);
		} finally {
			evita.close();
			deleteQuietly(storageDirectory);
		}
	}

	/**
	 * Boots one embedded instance holding one entity per supplied value, with the probed attribute declared both
	 * `filterable` and accelerated for substring search - so a single built index serves both arms.
	 *
	 * @param storageDirectory the storage root owned by this instance
	 * @param values           the distinct values to insert, one per entity
	 * @return the booted, live instance
	 */
	@Nonnull
	private static Evita bootInstance(@Nonnull Path storageDirectory, @Nonnull List<String> values) {
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(600_000)
						.transactionTimeoutInMilliseconds(600_000)
						.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(storageDirectory)
						.build()
				)
				.build()
		);
		evita.defineCatalog(CATALOG_NAME);
		evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(ENTITY_TYPE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.filterable()
							.acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)
					)
					.updateVia(session);
				for (int i = 0; i < values.size(); i++) {
					session.createNewEntity(ENTITY_TYPE, i + 1)
						.setAttribute(ATTRIBUTE_NAME, values.get(i))
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
		return evita;
	}

	/* ==================================== pattern discovery ===================================== */

	/**
	 * Discovers the pattern ladder of one attribute from its own values, and profiles every pattern that survives.
	 *
	 * @param values          the distinct values patterns are sampled out of
	 * @param filterIndex     the attribute's filter index, used to prove the absent patterns really are absent
	 * @param sharedValueTree the shared value tree, whose normalizer every pattern goes through
	 * @param trigramIndex    the attribute's accelerator
	 * @param exactPredicate  the exact test both arms apply
	 * @param budget          how many patterns to produce
	 * @return the profiled patterns, deduplicated, none of them yielding zero trigrams
	 */
	@Nonnull
	private static List<PatternShape> discoverPatterns(
		@Nonnull List<String> values,
		@Nonnull FilterIndex filterIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull BiPredicate<String, String> exactPredicate,
		int budget
	) {
		final Random random = new Random(SAMPLING_SEED);
		final int absentCount = Math.max(3, Math.min(6, budget / 10));
		final int remaining = Math.max(3, budget - absentCount);
		final int ladderCount = Math.max(1, (int) Math.round(remaining * 0.55d));
		final int multiCount = Math.max(1, (int) Math.round(remaining * 0.28d));
		final int heavyCount = Math.max(1, remaining - ladderCount - multiCount);
		final int poolSize = Math.min(40_000, Math.max(2_000, budget * 100));

		final List<PoolEntry> shortPool = samplePool(
			values, sharedValueTree, trigramIndex, random, poolSize,
			TrigramCodec.MINIMAL_INDEXABLE_LENGTH, TrigramCodec.MINIMAL_INDEXABLE_LENGTH
		);
		final List<PoolEntry> multiPool = samplePool(
			values, sharedValueTree, trigramIndex, random, poolSize,
			MINIMAL_MULTI_PATTERN_LENGTH, MAXIMAL_MULTI_PATTERN_LENGTH
		);

		final Set<String> selected = new LinkedHashSet<>(budget);
		for (final PoolEntry entry : pickSpanningBound(shortPool, ladderCount)) {
			selected.add(entry.text());
		}
		for (final PoolEntry entry : pickByTrigramCount(multiPool, multiCount)) {
			selected.add(entry.text());
		}
		for (final PoolEntry entry : pickWidestBound(shortPool, multiPool, heavyCount)) {
			selected.add(entry.text());
		}
		selected.addAll(generateAbsentPatterns(filterIndex, absentCount, random));

		final List<PatternShape> shapes = new ArrayList<>(selected.size());
		for (final String pattern : selected) {
			final PatternShape shape = profile(
				pattern, sharedValueTree, trigramIndex, exactPredicate
			);
			if (shape != null) {
				shapes.add(shape);
			}
		}
		return shapes;
	}

	/**
	 * Samples random substrings of the corpus into a deduplicated pool, keeping only those that yield at least one
	 * trigram after normalization.
	 *
	 * Sampling is by value first and offset second, so a pattern's frequency in the pool follows the corpus's own -
	 * which is what makes the pool cover the whole selectivity axis rather than only its rare end.
	 *
	 * @param values           the distinct values to sample out of
	 * @param sharedValueTree  the tree whose normalizer every candidate goes through
	 * @param trigramIndex     the accelerator every candidate's bound is read off
	 * @param random           the sampling source
	 * @param poolSize         how many distinct candidates to collect
	 * @param minimumLength    shortest candidate, in code points
	 * @param maximumLength    longest candidate, in code points
	 * @return the pool, in discovery order
	 */
	@Nonnull
	private static List<PoolEntry> samplePool(
		@Nonnull List<String> values,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull Random random,
		int poolSize,
		int minimumLength,
		int maximumLength
	) {
		final Function<Object, Serializable> normalizer = sharedValueTree.getNormalizer();
		final Map<String, PoolEntry> pool = new LinkedHashMap<>(poolSize);
		final int maximumAttempts = poolSize * 8;
		for (int attempt = 0; attempt < maximumAttempts && pool.size() < poolSize; attempt++) {
			final String value = values.get(random.nextInt(values.size()));
			final int availableCodePoints = value.codePointCount(0, value.length());
			final int length = minimumLength + (
				maximumLength > minimumLength ? random.nextInt(maximumLength - minimumLength + 1) : 0
			);
			if (availableCodePoints < length) {
				continue;
			}
			final int startCodePoint = random.nextInt(availableCodePoints - length + 1);
			final int startIndex = value.offsetByCodePoints(0, startCodePoint);
			final int endIndex = value.offsetByCodePoints(startIndex, length);
			final String candidate = value.substring(startIndex, endIndex);
			if (pool.containsKey(candidate)) {
				continue;
			}
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams((String) normalizer.apply(candidate));
			if (trigrams.length == 0) {
				// normalization can shorten a candidate below the indexable length - such a pattern is declined for a
				// reason that has nothing to do with selectivity, so it has no place on this axis
				continue;
			}
			pool.put(candidate, new PoolEntry(candidate, trigrams, candidateUpperBoundOf(trigramIndex, trigrams)));
		}
		return new ArrayList<>(pool.values());
	}

	/**
	 * Picks entries whose bounds are spread evenly on a LOG axis between the pool's cheapest and dearest - the shape
	 * the crossover is read off.
	 *
	 * A linear spread would spend almost the whole budget on the common end, because a bound ranges over three or
	 * four orders of magnitude while the crossover sits within one of them.
	 *
	 * @param pool  the candidates to pick from
	 * @param count how many to pick
	 * @return the picked entries, at most `count` of them
	 */
	@Nonnull
	private static List<PoolEntry> pickSpanningBound(@Nonnull List<PoolEntry> pool, int count) {
		if (pool.size() <= count) {
			return new ArrayList<>(pool);
		}
		double minimumBound = Double.MAX_VALUE;
		double maximumBound = 1.0d;
		for (final PoolEntry entry : pool) {
			final double bound = Math.max(1.0d, entry.bound());
			minimumBound = Math.min(minimumBound, bound);
			maximumBound = Math.max(maximumBound, bound);
		}
		final boolean[] used = new boolean[pool.size()];
		final List<PoolEntry> picked = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final double fraction = count == 1 ? 0.0d : (double) i / (count - 1);
			final double target = minimumBound * Math.pow(maximumBound / minimumBound, fraction);
			final int nearest = nearestUnusedByBound(pool, used, target);
			if (nearest < 0) {
				break;
			}
			used[nearest] = true;
			picked.add(pool.get(nearest));
		}
		return picked;
	}

	/**
	 * Finds the not-yet-picked entry whose bound is closest to `target` on the log axis.
	 *
	 * @param pool   the candidates
	 * @param used   which of them are already picked
	 * @param target the bound being aimed at
	 * @return the index of the nearest unused entry, or `-1` when every entry is used
	 */
	private static int nearestUnusedByBound(
		@Nonnull List<PoolEntry> pool,
		@Nonnull boolean[] used,
		double target
	) {
		final double logTarget = Math.log(target);
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < pool.size(); i++) {
			if (used[i]) {
				continue;
			}
			final double distance = Math.abs(Math.log(Math.max(1.0d, pool.get(i).bound())) - logTarget);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	/**
	 * Picks entries round-robin across trigram counts 2..10, so no single width can monopolise the multi-trigram
	 * family - the intersection cost is a function of that count and a family concentrated on one value of it would
	 * say nothing about the rest.
	 *
	 * @param pool  the candidates to pick from
	 * @param count how many to pick
	 * @return the picked entries, at most `count` of them
	 */
	@Nonnull
	private static List<PoolEntry> pickByTrigramCount(@Nonnull List<PoolEntry> pool, int count) {
		final Map<Integer, List<PoolEntry>> byTrigramCount = new TreeMap<>();
		for (final PoolEntry entry : pool) {
			final int trigramCount = entry.trigrams().length;
			if (trigramCount < MINIMAL_MULTI_TRIGRAM_COUNT || trigramCount > MAXIMAL_MULTI_TRIGRAM_COUNT) {
				continue;
			}
			byTrigramCount.computeIfAbsent(trigramCount, key -> new ArrayList<>()).add(entry);
		}
		final List<PoolEntry> picked = new ArrayList<>(count);
		int round = 0;
		boolean progressed = true;
		while (picked.size() < count && progressed) {
			progressed = false;
			for (final List<PoolEntry> bucket : byTrigramCount.values()) {
				if (picked.size() >= count) {
					break;
				}
				if (round < bucket.size()) {
					picked.add(bucket.get(round));
					progressed = true;
				}
			}
			round++;
		}
		return picked;
	}

	/**
	 * Picks the widest-bound entries of both pools - the hit-heavy family, where the candidate set covers a large
	 * fraction of the corpus and the scan is expected to win outright.
	 *
	 * @param shortPool the length-3 pool
	 * @param multiPool the multi-trigram pool
	 * @param count     how many to pick
	 * @return the picked entries, at most `count` of them
	 */
	@Nonnull
	private static List<PoolEntry> pickWidestBound(
		@Nonnull List<PoolEntry> shortPool,
		@Nonnull List<PoolEntry> multiPool,
		int count
	) {
		final List<PoolEntry> merged = new ArrayList<>(shortPool.size() + multiPool.size());
		merged.addAll(shortPool);
		merged.addAll(multiPool);
		merged.sort(Comparator.comparingInt(PoolEntry::bound).reversed());
		return merged.subList(0, Math.min(count, merged.size()));
	}

	/**
	 * Generates strings the corpus provably does not contain, proving each one by running the scan arm over it - the
	 * only oracle available on real data.
	 *
	 * @param filterIndex the attribute's filter index, whose scan proves absence
	 * @param count       how many to generate
	 * @param random      the generation source
	 * @return the generated absent patterns
	 */
	@Nonnull
	private static List<String> generateAbsentPatterns(
		@Nonnull FilterIndex filterIndex,
		int count,
		@Nonnull Random random
	) {
		final List<String> absent = new ArrayList<>(count);
		final int maximumAttempts = count * 100;
		for (int attempt = 0; attempt < maximumAttempts && absent.size() < count; attempt++) {
			final StringBuilder candidate = new StringBuilder(ABSENT_PATTERN_LENGTH);
			for (int i = 0; i < ABSENT_PATTERN_LENGTH; i++) {
				candidate.append(ABSENT_ALPHABET.charAt(random.nextInt(ABSENT_ALPHABET.length())));
			}
			final String pattern = candidate.toString();
			if (!absent.contains(pattern)
				&& filterIndex.getRecordsWhoseValuesContains(pattern).compute().isEmpty()) {
				absent.add(pattern);
			}
		}
		if (absent.isEmpty()) {
			throw new GenericEvitaInternalError(
				"No absent pattern could be generated in " + maximumAttempts + " attempts - the provable-empty path "
					+ "would go unmeasured, which is one of the four families this sweep exists to cover!",
				"No absent pattern could be generated!"
			);
		}
		return absent;
	}

	/**
	 * Measures everything about one pattern that does not require a clock: its trigram count, the bound the gate
	 * reads, the candidate set the intersection nominates, how many of those candidates survive the exact predicate,
	 * and the gate's honest verdict.
	 *
	 * The verdict comes from a `match` carrying the counter the translator would have supplied for a single-index
	 * target set - `sharedValueTree.getBucketCount()` - so a `null` here is the decline a real query would take.
	 *
	 * @param pattern         the raw pattern, exactly as a query would supply it
	 * @param sharedValueTree the shared value tree
	 * @param trigramIndex    the accelerator
	 * @param exactPredicate  the exact test both arms apply
	 * @return the pattern's shape, or `null` when it yields no trigram and therefore has no place on this axis
	 */
	/**
	 * What the timed arm tells {@link TrigramSubstringSearch#match} its exact predicate needs from an occurrence.
	 *
	 * The predicate is `contains` either way - this switch changes only whether `match` may SKIP verifying the
	 * candidates when the pattern is exactly one trigram wide, which is the optimization being measured. Both
	 * settings must therefore produce byte-identical bitmaps, and the driver's arm-parity check proves it on every
	 * pattern: `ANCHORED` verifies every candidate exactly as the pre-optimization code did, `CONTAINMENT` skips a
	 * verification it can prove is the identity. Running the same jar twice with this flipped is a cleaner A/B than
	 * two builds, because nothing but the one branch differs.
	 */
	private static final StringSearchShape SHAPE = StringSearchShape.valueOf(
		System.getProperty("evita.sweep.shape", "CONTAINMENT")
	);

	@Nullable
	private static PatternShape profile(
		@Nonnull String pattern,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull BiPredicate<String, String> exactPredicate
	) {
		final String normalizedPattern = (String) sharedValueTree.getNormalizer().apply(pattern);
		final long[] trigrams = TrigramCodec.extractUniqueTrigrams(normalizedPattern);
		if (trigrams.length == 0) {
			return null;
		}
		final int bound = candidateUpperBoundOf(trigramIndex, trigrams);
		final int[] candidateValueIds = trigramIndex.resolveCandidateValueIds(trigrams);
		final MatchedBuckets matched = sharedValueTree.getRecordsOfValueIdsMatching(
			candidateValueIds, candidateValueIds.length,
			normalizedValue -> exactPredicate.test((String) normalizedValue, normalizedPattern)
		);
		final boolean accelerated = TrigramSubstringSearch.match(
			trigramIndex, sharedValueTree, pattern, exactPredicate,
			threshold -> sharedValueTree.getBucketCount(), SHAPE
		) != null;
		return new PatternShape(
			pattern,
			pattern.length(),
			pattern.codePointCount(0, pattern.length()),
			trigrams.length,
			bound,
			candidateValueIds.length,
			matched.recordSets().length,
			accelerated
		);
	}

	/**
	 * The upper bound on how many candidates an intersection over these trigrams could produce - the cheapest
	 * posting's cardinality.
	 *
	 * This is UNTIMED shape metadata: it decides where a pattern sits on the sweep's selectivity axis and what the
	 * `bound` column reports, and it runs once per pattern long before any arm is timed. The engine's own pricing
	 * probe is deliberately out of reach here - it hands back direct references to the index's postings and is
	 * therefore package-private - so this restates the number from the public per-trigram cardinality instead. The
	 * result is identical, so the pattern pool and the selectivity axis are unchanged and rows still pair across an
	 * A/B of two engines.
	 *
	 * @param trigramIndex the accelerator
	 * @param trigrams     the pattern's trigrams
	 * @return the cheapest posting's cardinality, `0` when some trigram posts against nothing
	 */
	private static int candidateUpperBoundOf(@Nonnull TrigramIndex trigramIndex, @Nonnull long[] trigrams) {
		int minimum = Integer.MAX_VALUE;
		for (int i = 0; i < trigrams.length; i++) {
			final int cardinality = trigramIndex.cardinalityOf(trigrams[i]);
			if (cardinality == 0) {
				return 0;
			}
			if (cardinality < minimum) {
				minimum = cardinality;
			}
		}
		return minimum == Integer.MAX_VALUE ? 0 : minimum;
	}

	/* ================================== correctness and timing ================================== */

	/**
	 * Computes both arms for every pattern and compares their bitmaps element by element, aborting the run on the
	 * first disagreement.
	 *
	 * This is the whole oracle. A generated corpus can be checked against the values that produced it; a real one
	 * cannot, so the only statement available is that the accelerated path answers exactly what the scan answers -
	 * and a driver that produced numbers without establishing it would be timing two different questions.
	 *
	 * @param attribute       the attribute being measured, named in the failure message
	 * @param shapes          the patterns to check
	 * @param filterIndex     the scan arm's entry point
	 * @param sharedValueTree the shared value tree
	 * @param trigramIndex    the accelerator
	 * @param exactPredicate  the exact test both arms apply
	 */
	private static void verifyArmParity(
		@Nonnull String attribute,
		@Nonnull List<PatternShape> shapes,
		@Nonnull FilterIndex filterIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull BiPredicate<String, String> exactPredicate
	) {
		for (final PatternShape shape : shapes) {
			final Bitmap scanned = filterIndex.getRecordsWhoseValuesContains(shape.text()).compute();
			final Bitmap accelerated = computeForcedTrigramArm(
				sharedValueTree, trigramIndex, shape.text(), exactPredicate
			);
			final int[] scannedIds = scanned.getArray();
			final int[] acceleratedIds = accelerated.getArray();
			if (!Arrays.equals(scannedIds, acceleratedIds)) {
				System.err.printf(
					Locale.ROOT,
					"[arm-sweep] PARITY MISMATCH on `%s`: patternLength=%d codePointCount=%d trigramCount=%d "
						+ "bound=%d candidates=%d scanCardinality=%d trigramCardinality=%d%n",
					attribute, shape.patternLength(), shape.codePointCount(), shape.trigramCount(), shape.bound(),
					shape.candidates(), scannedIds.length, acceleratedIds.length
				);
				throw new GenericEvitaInternalError(
					"The scan and the trigram arm disagree on attribute `" + attribute + "` - see the shape printed "
						+ "above. No timing may be produced from a fixture whose two arms answer different questions!",
					"The two substring arms disagree!"
				);
			}
		}
	}

	/**
	 * Runs the trigram arm with the gate forced open, so every pattern carries a trigram number even where a real
	 * query would have declined to the scan.
	 *
	 * @param sharedValueTree the shared value tree
	 * @param trigramIndex    the accelerator
	 * @param pattern         the raw pattern
	 * @param exactPredicate  the exact test both arms apply
	 * @return the accelerated answer
	 */
	@Nonnull
	private static Bitmap computeForcedTrigramArm(
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull String pattern,
		@Nonnull BiPredicate<String, String> exactPredicate
	) {
		final MatchedBuckets matched = TrigramSubstringSearch.match(
			trigramIndex, sharedValueTree, pattern, exactPredicate, threshold -> Long.MAX_VALUE, SHAPE
		);
		if (matched == null) {
			throw new GenericEvitaInternalError(
				"The trigram arm declined a pattern whose gate was forced open with `Long.MAX_VALUE` - the only "
					+ "remaining decline conditions are an open transaction and a pattern without trigrams, and this "
					+ "driver has excluded both!",
				"The forced trigram arm declined!"
			);
		}
		return sharedValueTree.toFormula(matched, TrigramSubstringSearch.versionIdsOf(trigramIndex)).compute();
	}

	/**
	 * Times both arms for every pattern and assembles the result rows.
	 *
	 * @param attribute       the attribute being measured
	 * @param distinctValues  the shared value tree's bucket count, which every share is expressed against
	 * @param shapes          the patterns to measure
	 * @param filterIndex     the scan arm's entry point
	 * @param sharedValueTree the shared value tree
	 * @param trigramIndex    the accelerator
	 * @param exactPredicate  the exact test both arms apply
	 * @return one row per pattern
	 */
	@Nonnull
	private static List<SweepRow> measure(
		@Nonnull String attribute,
		int distinctValues,
		@Nonnull List<PatternShape> shapes,
		@Nonnull FilterIndex filterIndex,
		@Nonnull InvertedIndex sharedValueTree,
		@Nonnull TrigramIndex trigramIndex,
		@Nonnull BiPredicate<String, String> exactPredicate
	) {
		final List<SweepRow> rows = new ArrayList<>(shapes.size());
		int measured = 0;
		for (final PatternShape shape : shapes) {
			final String pattern = shape.text();
			final Timing scanTiming = time(
				() -> filterIndex.getRecordsWhoseValuesContains(pattern).compute().size()
			);
			final Timing trigramTiming = time(
				() -> computeForcedTrigramArm(sharedValueTree, trigramIndex, pattern, exactPredicate).size()
			);
			rows.add(
				new SweepRow(
					attribute,
					distinctValues,
					shape.patternLength(),
					shape.codePointCount(),
					shape.trigramCount(),
					shape.bound(),
					distinctValues == 0 ? 0.0d : (double) shape.bound() / distinctValues,
					shape.candidates(),
					shape.matchedValues(),
					shape.candidates() == 0
						? 0.0d
						: (double) (shape.candidates() - shape.matchedValues()) / shape.candidates(),
					shape.accelerated() ? "ACCELERATE" : "DECLINE",
					scanTiming.medianMicros(),
					scanTiming.p10Micros(),
					scanTiming.p90Micros(),
					trigramTiming.medianMicros(),
					trigramTiming.p10Micros(),
					trigramTiming.p90Micros(),
					trigramTiming.medianMicros() == 0.0d
						? Double.NaN
						: scanTiming.medianMicros() / trigramTiming.medianMicros()
				)
			);
			measured++;
			if (measured % 10 == 0) {
				System.out.printf(
					Locale.ROOT, "[arm-sweep] %s: %,d / %,d patterns timed%n", attribute, measured, shapes.size()
				);
			}
		}
		return rows;
	}

	/**
	 * Times one arm: warms up for at least {@link #WARMUP_MINIMAL_REPETITIONS} repetitions and
	 * {@link #WARMUP_MINIMAL_NANOS}, then measures for at least {@link #MEASUREMENT_MINIMAL_REPETITIONS} repetitions
	 * and {@link #MEASUREMENT_MINIMAL_NANOS}, capped at {@link #MEASUREMENT_MAXIMAL_REPETITIONS}.
	 *
	 * Hand-rolled rather than delegated to JMH on purpose: this driver's job is the WIDE sweep - sixty patterns per
	 * attribute, each one a distinct point on the selectivity axis - and JMH's per-parameter forking would turn that
	 * into hundreds of JVM boots over a catalog that costs tens of seconds to build. The narrow shapes, where fork
	 * isolation and blackholes earn their cost, are covered by the JMH suite instead. The median is reported rather
	 * than the mean for the same reason a single JVM can be used at all: it is what makes an occasional GC pause a
	 * feature of the p90 rather than of the headline number.
	 *
	 * @param arm the arm to time, returning the cardinality it computed so the work cannot be elided
	 * @return the arm's median, p10 and p90, in microseconds
	 */
	@Nonnull
	private static Timing time(@Nonnull LongSupplier arm) {
		long accumulator = 0L;
		long startedAt = System.nanoTime();
		for (int repetition = 0;
			 repetition < WARMUP_MINIMAL_REPETITIONS || System.nanoTime() - startedAt < WARMUP_MINIMAL_NANOS;
			 repetition++) {
			accumulator += arm.getAsLong();
		}

		final long[] samples = new long[MEASUREMENT_MAXIMAL_REPETITIONS];
		int count = 0;
		startedAt = System.nanoTime();
		while (count < MEASUREMENT_MAXIMAL_REPETITIONS
			&& (count < MEASUREMENT_MINIMAL_REPETITIONS
			|| System.nanoTime() - startedAt < MEASUREMENT_MINIMAL_NANOS)) {
			final long invokedAt = System.nanoTime();
			accumulator += arm.getAsLong();
			samples[count++] = System.nanoTime() - invokedAt;
		}
		sink += accumulator;

		final long[] sorted = Arrays.copyOf(samples, count);
		Arrays.sort(sorted);
		return new Timing(
			percentileMicros(sorted, 0.50d),
			percentileMicros(sorted, 0.10d),
			percentileMicros(sorted, 0.90d)
		);
	}

	/**
	 * Nearest-rank percentile over an ascending array of nanosecond durations - the reported value always occurs in
	 * the data.
	 *
	 * @param sortedAscending the observations, sorted ascending
	 * @param fraction        the percentile as a fraction, e.g. `0.90`
	 * @return the observation at that rank, converted to microseconds
	 */
	private static double percentileMicros(@Nonnull long[] sortedAscending, double fraction) {
		if (sortedAscending.length == 0) {
			return Double.NaN;
		}
		final int rank = (int) Math.ceil(fraction * sortedAscending.length) - 1;
		return sortedAscending[Math.max(0, Math.min(sortedAscending.length - 1, rank))] / 1_000.0d;
	}

	/* ======================================== reporting ========================================= */

	/**
	 * Writes the result TSV, one line per measured pattern, preceded by the self-describing header.
	 *
	 * The pattern text is deliberately absent: a substring of a customer corpus is still customer data, and every
	 * question this sweep answers is a question about the pattern's SHAPE.
	 *
	 * @param outputFile where to write
	 * @param rows       the rows to write
	 * @throws IOException when the file cannot be written
	 */
	private static void writeResults(@Nonnull Path outputFile, @Nonnull List<SweepRow> rows) throws IOException {
		final Path parent = outputFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (final BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
			writer.write(RESULT_HEADER);
			writer.newLine();
			for (final SweepRow row : rows) {
				writer.write(
					String.format(
						Locale.ROOT,
						"%s\t%d\t%d\t%d\t%d\t%d\t%.8f\t%d\t%d\t%.6f\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.4f",
						row.attribute(), row.distinctValues(), row.patternLength(), row.codePointCount(),
						row.trigramCount(), row.bound(), row.boundShare(), row.candidates(), row.matchedValues(),
						row.falseCandidateRate(), row.gate(), row.scanMedianUs(), row.scanP10Us(), row.scanP90Us(),
						row.trigramMedianUs(), row.trigramP10Us(), row.trigramP90Us(), row.speedup()
					)
				);
				writer.newLine();
			}
		}
	}

	/**
	 * Interpolates the bound share at which the speedup of the length-3 rows crosses 1.0 - the point where the
	 * accelerated path stops being worth taking, and the number
	 * {@link TrigramSubstringSearch#REQUIRED_NARROWING_FACTOR} is the reciprocal of.
	 *
	 * The rows are ordered by bound share and the FIRST descending crossing is reported: past it the scan wins and
	 * everything wider is inside the region the gate must refuse. Interpolation is linear in the share, which is a
	 * fair approximation only because the two bracketing rows are adjacent samples of a curve that is monotone in
	 * width by construction - both arms run the same predicate over `share * n` and `n` values respectively.
	 *
	 * @param rows the rows of one attribute
	 * @return the interpolated crossover share, or `NaN` when the sweep never crossed
	 */
	private static double crossoverBoundShare(@Nonnull List<SweepRow> rows) {
		final List<SweepRow> shortRows = new ArrayList<>(rows.size());
		for (final SweepRow row : rows) {
			if (row.codePointCount() == TrigramCodec.MINIMAL_INDEXABLE_LENGTH && !Double.isNaN(row.speedup())) {
				shortRows.add(row);
			}
		}
		shortRows.sort(Comparator.comparingDouble(SweepRow::boundShare));
		for (int i = 1; i < shortRows.size(); i++) {
			final SweepRow faster = shortRows.get(i - 1);
			final SweepRow slower = shortRows.get(i);
			if (faster.speedup() >= 1.0d && slower.speedup() < 1.0d) {
				final double span = faster.speedup() - slower.speedup();
				final double weight = span == 0.0d ? 0.0d : (faster.speedup() - 1.0d) / span;
				return faster.boundShare() + weight * (slower.boundShare() - faster.boundShare());
			}
		}
		return Double.NaN;
	}

	/**
	 * Prints the human-readable summary: per attribute, the distinct value count, how many patterns were measured,
	 * and where the length-3 speedup crossed 1.0.
	 *
	 * @param outputFile the TSV that was written, named so the run says where its own data went
	 * @param summaries  what to print, per attribute
	 */
	private static void printSummary(
		@Nonnull Path outputFile,
		@Nonnull Map<String, AttributeSummary> summaries
	) {
		System.out.println();
		System.out.println("========================== arm sweep summary ===========================");
		System.out.printf(
			Locale.ROOT, "%-24s %16s %10s %22s%n",
			"attribute", "distinctValues", "patterns", "crossoverBoundShare"
		);
		for (final Map.Entry<String, AttributeSummary> entry : summaries.entrySet()) {
			final AttributeSummary summary = entry.getValue();
			System.out.printf(
				Locale.ROOT, "%-24s %,16d %,10d %22s%n",
				entry.getKey(), summary.distinctValues(), summary.patternCount(),
				Double.isNaN(summary.crossoverBoundShare())
					? "not observed"
					: String.format(Locale.ROOT, "%.4f%%", summary.crossoverBoundShare() * 100.0d)
			);
		}
		System.out.println("========================================================================");
		System.out.println("Result TSV written to " + outputFile.toAbsolutePath());
	}

	/* ========================================= support ========================================== */

	/**
	 * Reads the corpus TSV and groups the values of the requested attributes, keeping distinct values only.
	 *
	 * Locales are merged: a value is a value whichever locale filed it, and both constants of
	 * {@link TrigramSubstringSearch} are expressed in the distinct values of one shared tree.
	 *
	 * @param corpusFile the TSV written by {@link TrigramCorpusExtractor}
	 * @param attributes the attribute names to keep
	 * @return per attribute, its distinct values in first-appearance order
	 * @throws IOException when the corpus cannot be read
	 */
	@Nonnull
	private static Map<String, List<String>> loadDistinctValues(
		@Nonnull Path corpusFile,
		@Nonnull Set<String> attributes
	) throws IOException {
		final Map<String, Set<String>> distinct = new LinkedHashMap<>(attributes.size());
		long lineNumber = 0L;
		long kept = 0L;
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
						"Corpus line " + lineNumber + " of `" + corpusFile + "` has " + columns.length
							+ " columns, expected 5!",
						"Malformed corpus line!"
					);
				}
				if (attributes.contains(columns[1])) {
					final String value = TrigramCorpusStatistics.unescape(columns[4]);
					if (!value.isEmpty()) {
						// an empty value yields no trigram and cannot carry a pattern, so it would only inflate the
						// entity count the whole axis is expressed against
						distinct.computeIfAbsent(columns[1], key -> new LinkedHashSet<>()).add(value);
						kept++;
					}
				}
				line = reader.readLine();
			}
		}
		final Map<String, List<String>> corpus = new LinkedHashMap<>(distinct.size());
		for (final Map.Entry<String, Set<String>> entry : distinct.entrySet()) {
			corpus.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			System.out.printf(
				Locale.ROOT, "[arm-sweep] %s: %,d distinct values%n", entry.getKey(), entry.getValue().size()
			);
		}
		System.out.printf(
			Locale.ROOT, "[arm-sweep] read %,d corpus lines, kept %,d occurrences of %,d attributes%n",
			lineNumber, kept, corpus.size()
		);
		return corpus;
	}

	/**
	 * Splits the comma-separated attribute list, rejecting an empty result rather than sweeping nothing.
	 *
	 * @param property the raw property value
	 * @return the attribute names, in the order they were given
	 */
	@Nonnull
	private static List<String> parseAttributes(@Nonnull String property) {
		final List<String> attributes = new ArrayList<>();
		for (final String candidate : property.split(",")) {
			final String trimmed = candidate.trim();
			if (!trimmed.isEmpty() && !attributes.contains(trimmed)) {
				attributes.add(trimmed);
			}
		}
		if (attributes.isEmpty()) {
			throw new GenericEvitaInternalError(
				"`" + ATTRIBUTES_PROPERTY + "` names no attribute - there is nothing to sweep!",
				"No attribute to sweep!"
			);
		}
		return attributes;
	}

	/**
	 * Reads the pattern budget, defaulting to {@link #DEFAULT_PATTERNS_PER_ATTRIBUTE}.
	 *
	 * @return the budget
	 */
	private static int parsePatternBudget() {
		final String raw = System.getProperty(PATTERNS_PER_ATTRIBUTE_PROPERTY);
		if (raw == null || raw.isBlank()) {
			return DEFAULT_PATTERNS_PER_ATTRIBUTE;
		}
		final int budget = Integer.parseInt(raw.trim());
		if (budget < 4) {
			throw new GenericEvitaInternalError(
				"`" + PATTERNS_PER_ATTRIBUTE_PROPERTY + "` is " + budget + " - the ladder has four families and a "
					+ "budget below four cannot cover them!",
				"The pattern budget is too small!"
			);
		}
		return budget;
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
				"Required system property `" + propertyName + "` is not set - see "
					+ TrigramArmSweep.class.getSimpleName() + " JavaDoc for the full list.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Removes a scratch directory, reporting rather than propagating a failure - a cleanup that throws would replace
	 * whatever the run was actually reporting.
	 *
	 * @param directory the directory to remove
	 */
	private static void deleteQuietly(@Nonnull Path directory) {
		try {
			FileUtils.deleteDirectory(directory.toFile());
		} catch (IOException e) {
			System.err.println("[arm-sweep] " + directory + " was left behind: " + e.getMessage());
		}
	}

	/* ========================================== records ========================================= */

	/**
	 * One candidate substring of the sampling pool, with everything the selection needs to place it on the axis.
	 *
	 * @param text     the raw substring, exactly as it was taken out of a value
	 * @param trigrams its trigrams after normalization, never empty
	 * @param bound    the cheapest posting's cardinality, which is what the selectivity gate reads
	 */
	private record PoolEntry(@Nonnull String text, @Nonnull long[] trigrams, int bound) {
	}

	/**
	 * Everything measured about one pattern that needs no clock.
	 *
	 * @param text           the raw pattern, never written to any output
	 * @param patternLength  its length in `char`s
	 * @param codePointCount its length in code points
	 * @param trigramCount   how many distinct trigrams it yields after normalization
	 * @param bound          the cheapest posting's cardinality
	 * @param candidates     how many value ids the intersection nominated
	 * @param matchedValues  how many of those survived the exact predicate
	 * @param accelerated    whether the gate, priced honestly against this index's own bucket count, accepts
	 */
	private record PatternShape(
		@Nonnull String text,
		int patternLength,
		int codePointCount,
		int trigramCount,
		int bound,
		int candidates,
		int matchedValues,
		boolean accelerated
	) {
	}

	/**
	 * One arm's timing distribution, in microseconds.
	 *
	 * @param medianMicros the median repetition
	 * @param p10Micros    the 10th percentile repetition
	 * @param p90Micros    the 90th percentile repetition
	 */
	private record Timing(double medianMicros, double p10Micros, double p90Micros) {
	}

	/**
	 * One line of the result TSV, in column order.
	 *
	 * @param attribute          the corpus attribute the pattern was drawn from
	 * @param distinctValues     the shared value tree's bucket count
	 * @param patternLength      the pattern's length in `char`s
	 * @param codePointCount     the pattern's length in code points
	 * @param trigramCount       how many distinct trigrams it yields
	 * @param bound              the cheapest posting's cardinality
	 * @param boundShare         `bound / distinctValues`
	 * @param candidates         how many value ids the intersection nominated
	 * @param matchedValues      how many of those survived the exact predicate
	 * @param falseCandidateRate `(candidates - matchedValues) / candidates`, zero when nothing was nominated
	 * @param gate               `ACCELERATE` or `DECLINE`, the honestly-priced verdict
	 * @param scanMedianUs       the scan arm's median
	 * @param scanP10Us          the scan arm's 10th percentile
	 * @param scanP90Us          the scan arm's 90th percentile
	 * @param trigramMedianUs    the forced trigram arm's median
	 * @param trigramP10Us       the forced trigram arm's 10th percentile
	 * @param trigramP90Us       the forced trigram arm's 90th percentile
	 * @param speedup            `scanMedianUs / trigramMedianUs`
	 */
	private record SweepRow(
		@Nonnull String attribute,
		int distinctValues,
		int patternLength,
		int codePointCount,
		int trigramCount,
		int bound,
		double boundShare,
		int candidates,
		int matchedValues,
		double falseCandidateRate,
		@Nonnull String gate,
		double scanMedianUs,
		double scanP10Us,
		double scanP90Us,
		double trigramMedianUs,
		double trigramP10Us,
		double trigramP90Us,
		double speedup
	) {
	}

	/**
	 * What the stdout summary reports about one attribute.
	 *
	 * @param distinctValues      the shared value tree's bucket count
	 * @param patternCount        how many patterns were measured
	 * @param crossoverBoundShare the interpolated bound share where the length-3 speedup crosses 1.0, `NaN` when the
	 *                            sweep never crossed
	 */
	private record AttributeSummary(int distinctValues, int patternCount, double crossoverBoundShare) {
	}

}
