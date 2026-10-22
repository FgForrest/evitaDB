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

package io.evitadb.index.fulltext.typo;

import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.CzechStemDictionary;
import io.evitadb.test.fulltext.CzechWordForms;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares **precomputed short-prefix postings** with the **query-time prefix scan** for search-as-you-type
 * (`typo-tolerance-fulltext-dictionary.md` §12). Every arm answers the same question — the set of products having a
 * word whose indexed term starts with the typed fragment — and the test asserts that they answer it identically:
 *
 * - **A, the scan** (the current design): {@link FulltextIndex#forEachTerm} over the fragment, then the visited
 *   postings OR-ed by the production {@link OrFormula};
 * - **B, full precomputation of 1–4 characters** (Lucene `AnalyzingInfixSuggester`, Elasticsearch
 *   `search_as_you_type`): one bitmap per prefix of length 1–4 of every term; a fragment of up to four characters is
 *   one lookup, a longer one falls back to the scan;
 * - **C, selective precomputation** (Meilisearch: `max_prefix_length` 4, `prefix_count_threshold` 100): only prefixes
 *   of length 1–4 shared by at least 100 terms get a bitmap; everything else falls back to the scan;
 * - **D, precomputation of 3–4 characters only**, matching the proposed minimum fragment length of three.
 *
 * The catalog is synthetic and deterministic: product titles of 3–8 words drawn with a Zipf distribution from the
 * inflected forms of the hunspell `cs_CZ` fixture ({@link CzechWordForms}), indexed through the production `czech`
 * index analyzer into a real {@link FulltextIndex}, so the postings are real. Fragments are prefixes of a
 * deterministic sample of forms ({@link CzechStemDictionary#SAMPLE_MODULUS}, the §10 sample), folded.
 *
 * Measured: read latency per fragment length, size of each structure, its bulk build time, and the per-document
 * maintenance an add and a remove cause. Not measured: transactional (MVCC) maintenance of the precomputed bitmaps,
 * the AND with the rest of the query, and ranking. Single-threaded JUnit wall-clock after a warm-up pass, indicative
 * only.
 *
 * The catalog size defaults to one million products; `-DshortPrefix.products=<n>` overrides it.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Precomputed short-prefix postings versus the query-time prefix scan")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class ShortPrefixPrecomputationMeasurementTest {
	/**
	 * Number of products in the synthetic catalog.
	 */
	private static final int PRODUCT_COUNT = Integer.getInteger("shortPrefix.products", 1_000_000);
	/**
	 * Seed of every random choice in the test.
	 */
	private static final long SEED = 258L;
	/**
	 * Exponent of the Zipf distribution words are drawn with: the word of rank `r` (from 1) has weight `1 / r^s`.
	 */
	private static final double ZIPF_EXPONENT = 1.0;
	/**
	 * Fewest words in a title.
	 */
	private static final int MIN_TITLE_WORDS = 3;
	/**
	 * Most words in a title.
	 */
	private static final int MAX_TITLE_WORDS = 8;
	/**
	 * The one indexed field.
	 */
	private static final String FIELD = "name";
	/**
	 * Number of sampled forms whose prefixes are the measured fragments.
	 */
	private static final int FRAGMENT_FORMS = 1_000;
	/**
	 * Number of further sampled forms whose prefixes warm the code paths up before measuring.
	 */
	private static final int WARM_UP_FORMS = 200;
	/**
	 * Longest measured fragment.
	 */
	private static final int MAX_FRAGMENT_LENGTH = 8;
	/**
	 * Number of products whose removal and re-addition measure the per-document maintenance.
	 */
	private static final int MAINTENANCE_SAMPLE = 2_000;
	/**
	 * The entity-leg budget of the suggester (P3 §1.1).
	 */
	private static final long ENTITY_LEG_BUDGET_NANOS = 3_500_000L;
	/**
	 * The whole per-keystroke budget (P3 §1.1).
	 */
	private static final long KEYSTROKE_BUDGET_NANOS = 5_000_000L;
	/**
	 * Labels of the fragment-length buckets of the latency table.
	 */
	private static final String[] LENGTH_LABELS = {"1", "2", "3", "4", "5-8"};
	private static FulltextAnalyzerRegistry registry;
	private static FulltextAnalyzer indexAnalyzer;

	/**
	 * Creates the production analyzers.
	 */
	@BeforeAll
	static void setUpAnalyzers() {
		registry = new FulltextAnalyzerRegistry();
		indexAnalyzer = registry.getIndexAnalyzer("PRODUCT", Locale.forLanguageTag("cs"));
	}

	/**
	 * Releases the analyzers.
	 */
	@AfterAll
	static void closeAnalyzers() {
		registry.close();
	}

	/**
	 * Builds the catalog and the three precomputed structures, then measures reads, size and maintenance.
	 */
	@Test
	@DisplayName("Read latency, size and write cost of precomputed short prefixes against the scan")
	void shouldComparePrecomputedPrefixesWithScan() {
		final Vocabulary vocabulary = loadVocabulary();
		final long titleStart = System.nanoTime();
		final String[] titles = generateTitles(vocabulary.ranked());
		final long titleNanos = System.nanoTime() - titleStart;

		final FulltextIndex index = new FulltextIndex(indexAnalyzer);
		final long indexStart = System.nanoTime();
		for (int i = 0; i < titles.length; i++) {
			index.addValue(FIELD, i + 1, titles[i]);
		}
		final long indexNanos = System.nanoTime() - indexStart;
		final int fieldId = index.getFieldId(FIELD);
		final BaseStatistics base = baseStatistics(index, fieldId);

		final PrefixArm armB = new PrefixArm("B: all prefixes 1-4", 1, 4, 1);
		final PrefixArm armC = new PrefixArm("C: prefixes 1-4 shared by >= 100 terms", 1, 4, 100);
		final PrefixArm armD = new PrefixArm("D: all prefixes 3-4", 3, 4, 1);
		final PrefixArm[] arms = {armB, armC, armD};
		for (final PrefixArm arm : arms) {
			arm.build(index, fieldId);
		}

		System.out.printf(
			Locale.ROOT,
			"catalog: %,d products, %,d distinct forms in the Zipf vocabulary (s = %.1f), %,d sampled forms, " +
				"titles generated in %.1f s, indexed in %.1f s%n",
			titles.length, vocabulary.ranked().length, ZIPF_EXPONENT, vocabulary.sampled().size(),
			titleNanos / 1e9, indexNanos / 1e9
		);
		System.out.printf(
			Locale.ROOT, "index: %,d terms, %,d postings, longest posting list %,d, terms with one posting %.1f %%%n",
			base.terms(), base.postings(), base.longestPosting(), base.singletonTerms() * 100.0 / base.terms()
		);

		// sizes: JOL ownership walk plus a roaring-serialized figure computed the same way for every structure
		final long baseHeap = JolHeapSize.ownedSize(index, indexAnalyzer);
		System.out.println();
		System.out.println(
			"| structure | keys | serialized bytes | heap bytes (JOL) | serialized / base | build time |"
		);
		System.out.println("|---|---|---|---|---|---|");
		System.out.printf(
			Locale.ROOT, "| base FulltextIndex (terms, postings) | %,d | %,d | %,d | 1.00x | %.1f s (indexing) |%n",
			base.terms(), base.serializedBytes(), baseHeap, indexNanos / 1e9
		);
		for (final PrefixArm arm : arms) {
			final long heap = JolHeapSize.ownedSize(arm.bitmaps);
			System.out.printf(
				Locale.ROOT, "| %s | %,d | %,d | %,d | %.2fx | %.2f s |%n",
				arm.name, arm.bitmaps.size(), arm.serializedBytes(), heap,
				arm.serializedBytes() / (double) base.serializedBytes(), arm.buildNanos / 1e9
			);
		}
		System.out.println();
		System.out.println(
			"| structure | keys by prefix length 1 | 2 | 3 | 4 | precomputed postings (sum of cardinalities) |"
		);
		System.out.println("|---|---|---|---|---|---|");
		for (final PrefixArm arm : arms) {
			System.out.printf(
				Locale.ROOT, "| %s | %,d | %,d | %,d | %,d | %,d |%n",
				arm.name, arm.keysByLength[1], arm.keysByLength[2], arm.keysByLength[3], arm.keysByLength[4],
				arm.precomputedPostings()
			);
		}

		// reads
		final List<String> sample = new ArrayList<>(vocabulary.sampled());
		Collections.shuffle(sample, new Random(SEED));
		final List<String> measured = sample.subList(0, FRAGMENT_FORMS);
		final List<String> warmUp = sample.subList(FRAGMENT_FORMS, FRAGMENT_FORMS + WARM_UP_FORMS);
		for (final String form : warmUp) {
			final String folded = CzechLexicon.fold(form);
			for (int length = 1; length <= Math.min(MAX_FRAGMENT_LENGTH, folded.length()); length++) {
				final String fragment = folded.substring(0, length);
				scan(index, fieldId, fragment, null);
				for (final PrefixArm arm : arms) {
					arm.lookup(index, fieldId, fragment);
				}
			}
		}
		final ReadStatistics reads = new ReadStatistics(arms.length + 1);
		int verified = 0;
		for (final String form : measured) {
			final String folded = CzechLexicon.fold(form);
			for (int length = 1; length <= Math.min(MAX_FRAGMENT_LENGTH, folded.length()); length++) {
				final String fragment = folded.substring(0, length);
				final int bucket = Math.min(length, 5) - 1;
				final int[] visited = new int[1];
				long start = System.nanoTime();
				final Bitmap scanned = scan(index, fieldId, fragment, visited);
				reads.time(bucket, 0, System.nanoTime() - start, false);
				final PersistentRoaringBitmap expected = RoaringBitmapBackedBitmap.getRoaringBitmap(scanned);
				reads.shape(bucket, visited[0], expected.getCardinality());
				for (int a = 0; a < arms.length; a++) {
					start = System.nanoTime();
					final Answer answer = arms[a].lookup(index, fieldId, fragment);
					reads.time(bucket, a + 1, System.nanoTime() - start, answer.precomputed());
					assertSame(expected, answer.records(), arms[a].name, fragment);
				}
				verified++;
			}
		}
		System.out.println();
		System.out.println(reads.shapeReport());
		System.out.println(reads.latencyReport(arms));

		// writes: remove every sampled product and add it back, in the base index and in each arm
		final Random random = new Random(SEED + 1);
		final Series analysisTime = new Series();
		final Series baseAdd = new Series();
		final Series baseRemove = new Series();
		final Series baseTouched = new Series();
		final Series baseBytes = new Series();
		final Series[] armBytes = series(arms.length);
		final Series[] armAdd = series(arms.length);
		final Series[] armRemove = series(arms.length);
		final Series[] armTouched = series(arms.length);
		for (int s = 0; s < MAINTENANCE_SAMPLE; s++) {
			final int pk = 1 + random.nextInt(titles.length);
			final String title = titles[pk - 1];
			long start = System.nanoTime();
			final Set<String> terms = analyze(title);
			analysisTime.add(System.nanoTime() - start);
			baseTouched.add(terms.size());
			// what a persistence that rewrites whole postings would write for this document (P8's question)
			long touchedBytes = 0L;
			for (final String term : terms) {
				touchedBytes += serializedSize(index.getPostings(fieldId, term));
			}
			baseBytes.add(touchedBytes);
			for (int a = 0; a < arms.length; a++) {
				armBytes[a].add(arms[a].touchedBytes(terms));
			}

			start = System.nanoTime();
			index.removeValue(FIELD, pk, title);
			baseRemove.add(System.nanoTime() - start);
			for (int a = 0; a < arms.length; a++) {
				start = System.nanoTime();
				final int touched = arms[a].remove(terms, pk);
				armRemove[a].add(System.nanoTime() - start);
				armTouched[a].add(touched);
			}
			start = System.nanoTime();
			index.addValue(FIELD, pk, title);
			baseAdd.add(System.nanoTime() - start);
			for (int a = 0; a < arms.length; a++) {
				start = System.nanoTime();
				final int touched = arms[a].add(terms, pk);
				armAdd[a].add(System.nanoTime() - start);
				assertEquals(armTouched[a].last(), touched, "an add must touch what the remove touched");
			}
		}
		System.out.println();
		System.out.println(
			"| structure | postings / bitmaps touched per document p50 | p95 | max | add p50 | p95 | p99 | " +
				"remove p50 | p95 | p99 |"
		);
		System.out.println("|---|---|---|---|---|---|---|---|---|---|");
		System.out.println(maintenanceRow("base FulltextIndex (analysis included)", baseTouched, baseAdd, baseRemove));
		System.out.println(maintenanceRow("analysis of the title alone", baseTouched, analysisTime, analysisTime));
		for (int a = 0; a < arms.length; a++) {
			System.out.println(maintenanceRow(arms[a].name, armTouched[a], armAdd[a], armRemove[a]));
		}
		System.out.println();
		System.out.println(
			"| structure | serialized bytes of the postings / bitmaps one document touches p50 | p95 | mean | " +
				"mean bytes per touched key | mean bytes per key of the structure | ratio |"
		);
		System.out.println("|---|---|---|---|---|---|---|");
		System.out.println(
			bytesRow("base FulltextIndex", baseBytes, baseTouched, base.serializedBytes(), base.terms())
		);
		for (int a = 0; a < arms.length; a++) {
			System.out.println(
				bytesRow(arms[a].name, armBytes[a], armTouched[a], arms[a].serializedBytes(), arms[a].bitmaps.size())
			);
		}

		// after the maintenance cycle every arm must still answer exactly as the scan does
		int reverified = 0;
		for (final String form : measured.subList(0, 300)) {
			final String folded = CzechLexicon.fold(form);
			for (int length = 1; length <= Math.min(4, folded.length()); length++) {
				final String fragment = folded.substring(0, length);
				final PersistentRoaringBitmap expected =
					RoaringBitmapBackedBitmap.getRoaringBitmap(scan(index, fieldId, fragment, null));
				for (final PrefixArm arm : arms) {
					assertSame(expected, arm.lookup(index, fieldId, fragment).records(), arm.name, fragment);
				}
				reverified++;
			}
		}
		System.out.printf(
			Locale.ROOT, "%nverified: %,d fragments against the scan before maintenance, %,d after%n",
			verified, reverified
		);

		assertEquals(PRODUCT_COUNT, titles.length, "the catalog must have the configured size");
		assertTrue(verified > FRAGMENT_FORMS * 4, "every sampled form must contribute fragments");
		assertTrue(armC.bitmaps.size() < armB.bitmaps.size(), "the threshold must leave some prefixes out");
	}

	/**
	 * Streams every inflected form of the fixture once, keeping the distinct ones as the Zipf vocabulary and the
	 * §10 sample of one distinct form in {@link CzechStemDictionary#SAMPLE_MODULUS} as the query sample.
	 *
	 * @return the vocabulary, ranked in a seeded random order, and the sample
	 */
	@Nonnull
	private static Vocabulary loadVocabulary() {
		final Set<String> distinct = CollectionUtils.createHashSet(4_500_000);
		final TreeSet<String> sampled = new TreeSet<>();
		CzechWordForms.load().forEachForm(form -> {
			if (distinct.add(form) && Math.floorMod(form.hashCode(), CzechStemDictionary.SAMPLE_MODULUS) == 0) {
				sampled.add(form);
			}
		});
		final String[] ranked = distinct.toArray(new String[0]);
		// sorted first so that the ranking depends on the seed alone, not on the set's iteration order
		Arrays.sort(ranked);
		Collections.shuffle(Arrays.asList(ranked), new Random(SEED));
		return new Vocabulary(ranked, new ArrayList<>(sampled));
	}

	/**
	 * Generates the product titles: 3–8 words each, uniformly, every word drawn from the vocabulary with the Zipf
	 * distribution of {@link #ZIPF_EXPONENT}.
	 *
	 * @param ranked the vocabulary, most frequent word first
	 * @return the titles, the i-th belonging to primary key `i + 1`
	 */
	@Nonnull
	private static String[] generateTitles(@Nonnull String[] ranked) {
		final double[] cumulative = new double[ranked.length];
		double total = 0.0;
		for (int r = 0; r < ranked.length; r++) {
			total += 1.0 / Math.pow(r + 1, ZIPF_EXPONENT);
			cumulative[r] = total;
		}
		final Random random = new Random(SEED + 2);
		final String[] titles = new String[PRODUCT_COUNT];
		final StringBuilder sb = new StringBuilder(128);
		for (int i = 0; i < PRODUCT_COUNT; i++) {
			sb.setLength(0);
			final int words = MIN_TITLE_WORDS + random.nextInt(MAX_TITLE_WORDS - MIN_TITLE_WORDS + 1);
			for (int w = 0; w < words; w++) {
				final int found = Arrays.binarySearch(cumulative, random.nextDouble() * total);
				final int rank = Math.min(found >= 0 ? found : -found - 1, ranked.length - 1);
				if (w > 0) {
					sb.append(' ');
				}
				sb.append(ranked[rank]);
			}
			titles[i] = sb.toString();
		}
		return titles;
	}

	/**
	 * Reads the shape of the base index: term and posting counts, and the roaring-serialized size of its postings
	 * plus the UTF-8 bytes of its terms — the same method {@link PrefixArm#serializedBytes()} applies.
	 *
	 * @return the statistics
	 */
	@Nonnull
	private static BaseStatistics baseStatistics(@Nonnull FulltextIndex index, int fieldId) {
		final long[] counters = new long[5];
		index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
			counters[0]++;
			counters[1] += postings.size();
			counters[2] = Math.max(counters[2], postings.size());
			counters[3] += postings.size() == 1 ? 1 : 0;
			counters[4] += serializedSize(postings) + term.getBytes(StandardCharsets.UTF_8).length;
			return true;
		});
		return new BaseStatistics((int) counters[0], counters[1], (int) counters[2], counters[3], counters[4]);
	}

	/**
	 * Arm A: walks the terms with the fragment as prefix and ORs their postings with the production formula.
	 *
	 * @param visited receives the number of visited terms in its first slot, or null
	 * @return the union of the visited postings
	 */
	@Nonnull
	private static Bitmap scan(
		@Nonnull FulltextIndex index, int fieldId, @Nonnull String fragment, @Nullable int[] visited
	) {
		final List<Bitmap> postings = new ArrayList<>(64);
		index.forEachTerm(fieldId, fragment, (term, records, impacts) -> {
			postings.add(records);
			return true;
		});
		if (visited != null) {
			visited[0] = postings.size();
		}
		if (postings.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		} else if (postings.size() == 1) {
			return postings.get(0);
		} else {
			return new OrFormula(new long[]{fieldId}, postings.toArray(new Bitmap[0])).compute();
		}
	}

	/**
	 * Analyzes a title with the production index analyzer.
	 *
	 * @return the distinct terms of the title
	 */
	@Nonnull
	private static Set<String> analyze(@Nonnull String title) {
		final Set<String> terms = CollectionUtils.createHashSet(16);
		indexAnalyzer.analyze(
			title, (term, surfaceForm, startOffset, endOffset, positionIncrement) -> terms.add(term)
		);
		return terms;
	}

	/**
	 * Asserts that an arm returned exactly the products the scan returned.
	 */
	private static void assertSame(
		@Nonnull PersistentRoaringBitmap expected,
		@Nonnull PersistentRoaringBitmap actual,
		@Nonnull String arm,
		@Nonnull String fragment
	) {
		final int cardinality = expected.getCardinality();
		assertTrue(
			cardinality == actual.getCardinality() &&
				PersistentRoaringBitmap.andCardinality(expected, actual) == cardinality,
			() -> arm + " differs from the scan for fragment `" + fragment + "`"
		);
	}

	/**
	 * Formats one row of the maintenance table.
	 */
	@Nonnull
	private static String maintenanceRow(
		@Nonnull String name, @Nonnull Series touched, @Nonnull Series add, @Nonnull Series remove
	) {
		final long[] t = touched.sorted();
		final long[] a = add.sorted();
		final long[] r = remove.sorted();
		return String.format(
			Locale.ROOT, "| %s | %d | %d | %d | %.1f us | %.1f us | %.1f us | %.1f us | %.1f us | %.1f us |",
			name, Series.at(t, 0.5), Series.at(t, 0.95), t[t.length - 1],
			Series.at(a, 0.5) / 1e3, Series.at(a, 0.95) / 1e3, Series.at(a, 0.99) / 1e3,
			Series.at(r, 0.5) / 1e3, Series.at(r, 0.95) / 1e3, Series.at(r, 0.99) / 1e3
		);
	}

	/**
	 * Formats one row of the touched-bytes table.
	 *
	 * @param bytes      serialized bytes one document touches, per document
	 * @param touched    keys one document touches, per document
	 * @param totalBytes serialized bytes of the whole structure
	 * @param keys       number of keys of the whole structure
	 */
	@Nonnull
	private static String bytesRow(
		@Nonnull String name, @Nonnull Series bytes, @Nonnull Series touched, long totalBytes, int keys
	) {
		final long[] b = bytes.sorted();
		final double perTouchedKey = bytes.sum() / (double) touched.sum();
		final double perKey = totalBytes / (double) keys;
		return String.format(
			Locale.ROOT, "| %s | %,d | %,d | %,.0f | %,.0f | %,.0f | %.1fx |",
			name, Series.at(b, 0.5), Series.at(b, 0.95), bytes.sum() / (double) b.length, perTouchedKey, perKey,
			perTouchedKey / perKey
		);
	}

	/**
	 * Returns the roaring-serialized size of a posting list after run optimization, the measure every structure in
	 * this test is sized with.
	 */
	private static long serializedSize(@Nonnull Bitmap postings) {
		final PersistentRoaringBitmap copy = RoaringBitmapBackedBitmap.getRoaringBitmapClone(postings);
		copy.runOptimize();
		return copy.serializedSizeInBytes();
	}

	/**
	 * Creates an array of empty series.
	 */
	@Nonnull
	private static Series[] series(int count) {
		final Series[] result = new Series[count];
		for (int i = 0; i < count; i++) {
			result[i] = new Series();
		}
		return result;
	}

	/**
	 * The word source of the catalog and of the queries.
	 *
	 * @param ranked  every distinct form, most frequent first under the Zipf distribution
	 * @param sampled the §10 sample of distinct forms, sorted
	 */
	private record Vocabulary(@Nonnull String[] ranked, @Nonnull List<String> sampled) {
	}

	/**
	 * Shape and size of the base index.
	 *
	 * @param terms           number of terms of the field
	 * @param postings        number of postings
	 * @param longestPosting  length of the longest posting list
	 * @param singletonTerms  number of terms with exactly one posting
	 * @param serializedBytes roaring-serialized postings plus UTF-8 term bytes
	 */
	private record BaseStatistics(
		int terms, long postings, int longestPosting, long singletonTerms, long serializedBytes
	) {
	}

	/**
	 * The answer of an arm to one fragment.
	 *
	 * @param records     the matching products
	 * @param precomputed whether the answer came from a precomputed bitmap rather than from the scan
	 */
	private record Answer(@Nonnull PersistentRoaringBitmap records, boolean precomputed) {
	}

	/**
	 * One precomputed arm: a bitmap per prefix of the terms, for prefix lengths `minLength..maxLength`, kept only
	 * when at least `minTermCount` distinct terms share the prefix. A fragment the arm holds no bitmap for falls back
	 * to the scan, except where the arm is complete (`minTermCount == 1`) and the missing key therefore proves that
	 * no term has the prefix.
	 */
	private static final class PrefixArm {
		/**
		 * Label of the arm in the tables.
		 */
		@Nonnull private final String name;
		/**
		 * Shortest precomputed prefix.
		 */
		private final int minLength;
		/**
		 * Longest precomputed prefix.
		 */
		private final int maxLength;
		/**
		 * Fewest distinct terms a prefix must be shared by to be precomputed.
		 */
		private final int minTermCount;
		/**
		 * Prefix to the union of the postings of every term with that prefix.
		 */
		@Nonnull private Map<String, PersistentRoaringBitmap> bitmaps = Map.of();
		/**
		 * Number of precomputed keys per prefix length.
		 */
		private final int[] keysByLength = new int[5];
		/**
		 * Wall-clock time of the bulk build.
		 */
		private long buildNanos;

		/**
		 * @param name         label of the arm
		 * @param minLength    shortest precomputed prefix
		 * @param maxLength    longest precomputed prefix
		 * @param minTermCount fewest terms a prefix must be shared by
		 */
		PrefixArm(@Nonnull String name, int minLength, int maxLength, int minTermCount) {
			this.name = name;
			this.minLength = minLength;
			this.maxLength = maxLength;
			this.minTermCount = minTermCount;
		}

		/**
		 * Builds the bitmaps from the index in one walk over the field's terms (two when a threshold applies: the
		 * first counts the terms per prefix), and run-optimizes them.
		 */
		void build(@Nonnull FulltextIndex index, int fieldId) {
			final long start = System.nanoTime();
			final Map<String, int[]> termCounts;
			if (this.minTermCount > 1) {
				termCounts = CollectionUtils.createHashMap(262_144);
				index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
					final int longest = Math.min(this.maxLength, term.length());
					for (int length = this.minLength; length <= longest; length++) {
						termCounts.computeIfAbsent(term.substring(0, length), p -> new int[1])[0]++;
					}
					return true;
				});
			} else {
				termCounts = null;
			}
			final Map<String, PersistentRoaringBitmap> result = CollectionUtils.createHashMap(262_144);
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				final int longest = Math.min(this.maxLength, term.length());
				for (int length = this.minLength; length <= longest; length++) {
					final String prefix = term.substring(0, length);
					if (termCounts == null || termCounts.get(prefix)[0] >= this.minTermCount) {
						addAll(result.computeIfAbsent(prefix, p -> new PersistentRoaringBitmap()), postings);
					}
				}
				return true;
			});
			for (final Map.Entry<String, PersistentRoaringBitmap> entry : result.entrySet()) {
				entry.getValue().runOptimize();
				this.keysByLength[entry.getKey().length()]++;
			}
			this.bitmaps = result;
			this.buildNanos = System.nanoTime() - start;
		}

		/**
		 * Answers one fragment: a precomputed bitmap when the arm holds one, otherwise the scan.
		 */
		@Nonnull
		Answer lookup(@Nonnull FulltextIndex index, int fieldId, @Nonnull String fragment) {
			final int length = fragment.length();
			if (length >= this.minLength && length <= this.maxLength) {
				final PersistentRoaringBitmap precomputed = this.bitmaps.get(fragment);
				if (precomputed != null) {
					return new Answer(precomputed, true);
				} else if (this.minTermCount == 1) {
					// the arm is complete in this length range, so no key means no term has the prefix
					return new Answer(new PersistentRoaringBitmap(), true);
				}
			}
			return new Answer(RoaringBitmapBackedBitmap.getRoaringBitmap(scan(index, fieldId, fragment, null)), false);
		}

		/**
		 * Removes a product from the bitmap of every distinct prefix of its terms the arm holds.
		 *
		 * @param terms the distinct terms of the product's whole value
		 * @return number of bitmaps touched
		 */
		int remove(@Nonnull Set<String> terms, int pk) {
			int touched = 0;
			for (final String prefix : prefixesOf(terms)) {
				final PersistentRoaringBitmap bitmap = this.bitmaps.get(prefix);
				if (bitmap != null) {
					bitmap.remove(pk);
					touched++;
				}
			}
			return touched;
		}

		/**
		 * Adds a product to the bitmap of every distinct prefix of its terms the arm holds. A prefix the arm does not
		 * hold is not created, even when the new terms would carry it over the threshold: re-evaluating the threshold
		 * on write is not part of this measurement.
		 *
		 * @param terms the distinct terms of the product's whole value
		 * @return number of bitmaps touched
		 */
		int add(@Nonnull Set<String> terms, int pk) {
			int touched = 0;
			for (final String prefix : prefixesOf(terms)) {
				final PersistentRoaringBitmap bitmap = this.bitmaps.get(prefix);
				if (bitmap != null) {
					bitmap.add(pk);
					touched++;
				}
			}
			return touched;
		}

		/**
		 * Collects the distinct prefixes of the terms within the arm's length range.
		 */
		@Nonnull
		private Set<String> prefixesOf(@Nonnull Set<String> terms) {
			final Set<String> prefixes = CollectionUtils.createHashSet(terms.size() * 4);
			for (final String term : terms) {
				final int longest = Math.min(this.maxLength, term.length());
				for (int length = this.minLength; length <= longest; length++) {
					prefixes.add(term.substring(0, length));
				}
			}
			return prefixes;
		}

		/**
		 * @return roaring-serialized size of every bitmap plus the UTF-8 bytes of every key
		 */
		long serializedBytes() {
			long bytes = 0L;
			for (final Map.Entry<String, PersistentRoaringBitmap> entry : this.bitmaps.entrySet()) {
				bytes += entry.getValue().serializedSizeInBytes() +
					entry.getKey().getBytes(StandardCharsets.UTF_8).length;
			}
			return bytes;
		}

		/**
		 * Sums the serialized size of every bitmap the arm holds for a prefix of the passed terms.
		 *
		 * @param terms the distinct terms of one product's value
		 * @return the serialized bytes a whole-bitmap rewrite of the touched keys would write
		 */
		long touchedBytes(@Nonnull Set<String> terms) {
			long bytes = 0L;
			for (final String prefix : prefixesOf(terms)) {
				final PersistentRoaringBitmap bitmap = this.bitmaps.get(prefix);
				if (bitmap != null) {
					bytes += bitmap.serializedSizeInBytes();
				}
			}
			return bytes;
		}

		/**
		 * @return sum of the cardinalities of every precomputed bitmap
		 */
		long precomputedPostings() {
			long postings = 0L;
			for (final PersistentRoaringBitmap bitmap : this.bitmaps.values()) {
				postings += bitmap.getCardinality();
			}
			return postings;
		}

		/**
		 * ORs a posting list into an accumulator, whichever representation the dictionary keeps it in.
		 */
		private static void addAll(@Nonnull PersistentRoaringBitmap accumulator, @Nonnull Bitmap postings) {
			if (postings instanceof final SingleRecordBitmap single) {
				accumulator.add(single.getRecordId());
			} else if (postings instanceof final RoaringBitmapBackedBitmap roaring) {
				accumulator.or(roaring.getRoaringBitmap());
			} else {
				accumulator.add(postings.getArray());
			}
		}
	}

	/**
	 * Read latency per fragment-length bucket and arm, plus the shape of the scan per bucket.
	 */
	private static final class ReadStatistics {
		/**
		 * Latency per bucket and arm (arm 0 is the scan).
		 */
		private final Series[][] times;
		/**
		 * Answers served from a precomputed bitmap, per bucket and arm.
		 */
		private final int[][] precomputed;
		/**
		 * Terms the scan visits, per bucket.
		 */
		private final Series[] visited = series(LENGTH_LABELS.length);
		/**
		 * Result cardinality, per bucket.
		 */
		private final Series[] cardinality = series(LENGTH_LABELS.length);

		/**
		 * @param armCount number of arms, the scan included
		 */
		ReadStatistics(int armCount) {
			this.times = new Series[LENGTH_LABELS.length][];
			this.precomputed = new int[LENGTH_LABELS.length][armCount];
			for (int b = 0; b < LENGTH_LABELS.length; b++) {
				this.times[b] = series(armCount);
			}
		}

		/**
		 * Records one timed answer.
		 */
		void time(int bucket, int arm, long nanos, boolean fromPrecomputed) {
			this.times[bucket][arm].add(nanos);
			this.precomputed[bucket][arm] += fromPrecomputed ? 1 : 0;
		}

		/**
		 * Records the shape of one scan.
		 */
		void shape(int bucket, int visitedTerms, int resultCardinality) {
			this.visited[bucket].add(visitedTerms);
			this.cardinality[bucket].add(resultCardinality);
		}

		/**
		 * @return the table of visited terms and result cardinalities per bucket
		 */
		@Nonnull
		String shapeReport() {
			final StringBuilder sb = new StringBuilder(1_024);
			sb.append("| fragment length | fragments | terms the scan visits p50 | p95 | p99 | max | ")
				.append("products in the result p50 | p95 | p99 | max |\n")
				.append("|---|---|---|---|---|---|---|---|---|---|\n");
			for (int b = 0; b < LENGTH_LABELS.length; b++) {
				final long[] v = this.visited[b].sorted();
				final long[] c = this.cardinality[b].sorted();
				sb.append(String.format(
					Locale.ROOT, "| %s | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %,d |%n",
					LENGTH_LABELS[b], v.length, Series.at(v, 0.5), Series.at(v, 0.95), Series.at(v, 0.99),
					v[v.length - 1], Series.at(c, 0.5), Series.at(c, 0.95), Series.at(c, 0.99), c[c.length - 1]
				));
			}
			return sb.toString();
		}

		/**
		 * @return the latency table per bucket and arm
		 */
		@Nonnull
		String latencyReport(@Nonnull PrefixArm[] arms) {
			final StringBuilder sb = new StringBuilder(2_048);
			sb.append("| fragment length | arm | p50 | p95 | p99 | max | over 3.5 ms | over 5 ms | ")
				.append("served precomputed |\n|---|---|---|---|---|---|---|---|---|\n");
			for (int b = 0; b < LENGTH_LABELS.length; b++) {
				for (int a = 0; a <= arms.length; a++) {
					final long[] t = this.times[b][a].sorted();
					int overEntity = 0;
					int overKeystroke = 0;
					for (final long value : t) {
						overEntity += value > ENTITY_LEG_BUDGET_NANOS ? 1 : 0;
						overKeystroke += value > KEYSTROKE_BUDGET_NANOS ? 1 : 0;
					}
					sb.append(String.format(
						Locale.ROOT,
						"| %s | %s | %.3f ms | %.3f ms | %.3f ms | %.3f ms | %.1f %% | %.1f %% | %.0f %% |%n",
						LENGTH_LABELS[b], a == 0 ? "A: scan" : arms[a - 1].name,
						Series.at(t, 0.5) / 1e6, Series.at(t, 0.95) / 1e6, Series.at(t, 0.99) / 1e6,
						t[t.length - 1] / 1e6, overEntity * 100.0 / t.length, overKeystroke * 100.0 / t.length,
						this.precomputed[b][a] * 100.0 / t.length
					));
				}
			}
			return sb.toString();
		}
	}

	/**
	 * A series of measurements with percentiles.
	 */
	private static final class Series {
		/**
		 * Number of recorded values.
		 */
		private int count;
		/**
		 * The recorded values, the first {@link #count} of them valid.
		 */
		private long[] values = new long[1_024];

		/**
		 * Records one value.
		 */
		void add(long value) {
			if (this.count == this.values.length) {
				this.values = Arrays.copyOf(this.values, this.count * 2);
			}
			this.values[this.count++] = value;
		}

		/**
		 * @return sum of the recorded values
		 */
		long sum() {
			long sum = 0L;
			for (int i = 0; i < this.count; i++) {
				sum += this.values[i];
			}
			return sum;
		}

		/**
		 * @return the value recorded last
		 */
		long last() {
			return this.values[this.count - 1];
		}

		/**
		 * @return a sorted copy of the recorded values
		 */
		@Nonnull
		long[] sorted() {
			final long[] sorted = Arrays.copyOf(this.values, this.count);
			Arrays.sort(sorted);
			return sorted;
		}

		/**
		 * Returns the nearest-rank quantile of a sorted array.
		 */
		static long at(@Nonnull long[] sorted, double quantile) {
			return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
		}
	}
}
