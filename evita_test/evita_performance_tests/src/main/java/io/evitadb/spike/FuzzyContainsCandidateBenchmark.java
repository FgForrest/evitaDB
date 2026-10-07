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

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.trigram.TrigramCodec;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.EditDistances;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Measures whether a typo-tolerant `attributeContains` can be served by the existing trigram index, and tests the
 * guarantee it would rest on.
 *
 * # The guarantee
 *
 * A typed pattern has `u` distinct trigrams. One edit touches a character that sits in at most `c` trigram windows:
 * `c = 3` for a substitution, insertion or deletion, `c = 4` for an adjacent transposition, which touches two
 * characters. A value containing a substring within `d` edits of the pattern therefore shares at least `u − c·d` of
 * the pattern's distinct trigrams — so "values with at least `u − c·d` of the pattern's trigrams" is a sound
 * candidate superset, and when `u − c·d ≤ 0` nothing is guaranteed and the only sound candidate set is the whole
 * corpus. The hypothesis under test was that the candidate set degenerates to a scan for realistic pattern lengths;
 * the measurement shows a hard cut-off by pattern length instead, and nearly exact candidates above it.
 *
 * The trial log prints the candidate ratio and asserts the superset property on patterns damaged by **all four**
 * edit kinds over several seeds, under the metric chosen by `metric`: `LEVENSHTEIN` (a swap costs two, `c = 3`) or
 * `DAMERAU` (a swap costs one, `c = 4`). A benchmark that reported fast numbers from an unsound candidate set once
 * slipped through here, which is why the assertion fails the trial rather than logging.
 *
 * # Corpus and patterns
 *
 * The Czech vocabulary in the attribute index's own shape (lowercase, Unicode NFD, diacritics kept). `WORD` makes
 * every word a distinct value (~257 000 short values, a `code`-like attribute); `NAME` builds 100 000 values of
 * three random words (a `name`-like attribute). Patterns are fragments of real values (`patternLength` code
 * points) damaged by `maxEdits` random edits — substitution, insertion, deletion or adjacent swap — never in the
 * first character. The undamaged fragment is kept so that today's exact path can be measured on the same corpus.
 *
 * # Arms
 *
 * - `countedUnion` — candidate generation by a dense per-value counter over every posting of the pattern: linear in
 *   the corpus, the simplest sound implementation;
 * - `prefixFilter` — candidate generation by the pigeonhole bound: a value with at least `u − c·d` of `u` trigrams
 *   contains at least one of the `c·d + 1` smallest postings, so only those are iterated and the count is confirmed
 *   by membership tests against the rest. Touches no large posting;
 * - `swapExpansion` — the alternative way to get Damerau semantics without the `c = 4` constant: the restricted
 *   Damerau neighbourhood of a pattern is the union of its Levenshtein neighbourhood (threshold `u − 3d`) and the
 *   neighbourhoods of its explicitly enumerated swapped variants (`L − 1` single swaps, each at `d − 1`; the
 *   non-overlapping double swaps exactly). Up to `L` index queries per pattern, each with the tighter `c = 3`
 *   threshold. Under `LEVENSHTEIN` it degrades to `countedUnion`, so its rows there carry no information;
 * - `editEnumeration` — the same idea pushed to every edit kind, for `d = 1` only: enumerate the whole restricted
 *   Damerau-1 neighbourhood of the pattern as explicit strings (deletions `L`, substitutions `L·|Σ|`, insertions
 *   `(L + 1)·|Σ|`, swaps `L − 1`, with `Σ` the code points occurring in the corpus) and look each up **exactly**.
 *   No threshold at all, so the cut-off drops to "the variant has a trigram" (L ≥ 4); the price is a few hundred
 *   exact lookups per pattern. For `d = 2` the neighbourhood squares and the arm takes the whole corpus;
 * - `countedUnionThenVerify`, `prefixFilterThenVerify`, `swapExpansionThenVerify`, `editEnumerationThenVerify` —
 *   the above followed by
 *   approximate-substring verification (Sellers), i.e. what a fuzzy `contains` would cost end to end;
 * - `fullScanApproximate` — the same verification over every value, the scan the accelerator has to beat;
 * - `exactIntersectionIntactPattern` — today's `attributeContains` candidate step on the undamaged fragment.
 *
 * Below the cut-off the union arms take the whole corpus and are a scan plus bookkeeping; their numbers there are
 * not a measurement of anything but that. Single-threaded by design: the counter array is per benchmark state.
 *
 * ```shell
 * java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
 *   FuzzyContainsCandidateBenchmark -p valueShape=NAME -p patternLength=8 -p maxEdits=1 -p metric=DAMERAU
 * ```
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
public class FuzzyContainsCandidateBenchmark {
	private static final int NAME_VALUE_COUNT = 100_000;
	private static final int PATTERN_COUNT = 32;
	private static final int SOUNDNESS_SEEDS = 3;
	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

	/**
	 * One pattern: the damaged fragment the user typed, its code points, its distinct trigrams, the sound threshold
	 * for the chosen metric (zero or negative means "no guarantee, whole corpus"), and the intact fragment's trigrams
	 * for the exact arm.
	 */
	record Pattern(
		@Nonnull String typed,
		@Nonnull int[] typedCodePoints,
		@Nonnull long[] typedTrigrams,
		int threshold,
		@Nonnull long[] intactTrigrams
	) {
	}

	/**
	 * The corpus, the index over it, the patterns and the scratch counter. Built once per trial.
	 */
	@State(Scope.Benchmark)
	public static class CorpusState {
		/**
		 * `WORD` or `NAME`, see the class javadoc.
		 */
		@Param({"WORD", "NAME"})
		public String valueShape;
		/**
		 * Length of the typed fragment in code points.
		 */
		@Param({"5", "8", "12"})
		public int patternLength;
		/**
		 * Edits applied to the fragment and the distance bound of the verification.
		 */
		@Param({"1", "2"})
		public int maxEdits;
		/**
		 * `LEVENSHTEIN` (swap costs two, `c = 3`) or `DAMERAU` (swap costs one, `c = 4`).
		 */
		@Param({"LEVENSHTEIN", "DAMERAU"})
		public String metric;

		/**
		 * Values by value id; index 0 unused so that ids are 1-based like the real index's.
		 */
		String[] values;
		int[][] valueCodePoints;
		TrigramIndex index;
		Pattern[] patterns;
		int[] counts;
		boolean[] marks;
		int[] alphabet;
		boolean transpositions;
		int windowsPerEdit;
		int next;

		@Setup(Level.Trial)
		public void setUp() {
			this.transpositions = switch (this.metric) {
				case "LEVENSHTEIN" -> false;
				case "DAMERAU" -> true;
				default -> throw new IllegalStateException("Unknown metric: " + this.metric);
			};
			this.windowsPerEdit = this.transpositions ? 4 : 3;
			final Random random = new Random(42);
			final Set<String> words = CzechLexicon.load(false);
			final String[] wordArray = words.toArray(String[]::new);
			final List<String> valueList = new ArrayList<>(
				"WORD".equals(this.valueShape) ? wordArray.length : NAME_VALUE_COUNT
			);
			if ("WORD".equals(this.valueShape)) {
				valueList.addAll(words);
			} else if ("NAME".equals(this.valueShape)) {
				for (int i = 0; i < NAME_VALUE_COUNT; i++) {
					valueList.add(
						wordArray[random.nextInt(wordArray.length)] + " " +
							wordArray[random.nextInt(wordArray.length)] + " " +
							wordArray[random.nextInt(wordArray.length)]
					);
				}
			} else {
				throw new IllegalStateException("Unknown value shape: " + this.valueShape);
			}
			this.values = new String[valueList.size() + 1];
			this.valueCodePoints = new int[valueList.size() + 1][];
			this.index = new TrigramIndex(new AttributeIndexKey(null, "name", null));
			for (int id = 1; id <= valueList.size(); id++) {
				final String value = valueList.get(id - 1);
				this.values[id] = value;
				this.valueCodePoints[id] = value.codePoints().toArray();
				this.index.valueCreated(id, value);
			}
			this.counts = new int[this.values.length];
			this.marks = new boolean[this.values.length];
			this.alphabet = Arrays.stream(this.valueCodePoints, 1, this.valueCodePoints.length)
				.flatMapToInt(Arrays::stream)
				.distinct()
				.sorted()
				.toArray();
			this.patterns = cutPatterns(random, PATTERN_COUNT);
			this.next = 0;
			assertSoundness();
			reportCandidateRatio();
		}

		@Nonnull
		private Pattern[] cutPatterns(@Nonnull Random random, int count) {
			final Pattern[] result = new Pattern[count];
			for (int i = 0; i < count; i++) {
				result[i] = cutPattern(random);
			}
			return result;
		}

		/**
		 * Cuts a fragment of `patternLength` code points from a random value long enough to hold it, then applies
		 * `maxEdits` random edits of all four kinds, never touching the first character. Bounded: gives up after
		 * many attempts rather than looping forever on a corpus with no value long enough.
		 */
		@Nonnull
		private Pattern cutPattern(@Nonnull Random random) {
			for (int attempt = 0; attempt < 100_000; attempt++) {
				final int id = 1 + random.nextInt(this.values.length - 1);
				final int[] value = this.valueCodePoints[id];
				if (value.length < this.patternLength) {
					continue;
				}
				final int start = random.nextInt(value.length - this.patternLength + 1);
				final int[] intact = new int[this.patternLength];
				System.arraycopy(value, start, intact, 0, this.patternLength);
				final int[] typed = damage(random, intact, this.maxEdits);
				final String typedString = new String(typed, 0, typed.length);
				final long[] typedTrigrams = TrigramCodec.extractUniqueTrigrams(typedString);
				final long[] intactTrigrams = TrigramCodec.extractUniqueTrigrams(new String(intact, 0, intact.length));
				// the guarantee: a value within `maxEdits` shares at least u − c·d of the u DISTINCT trigrams; at zero
				// or below nothing is guaranteed and the whole corpus is the only sound candidate set
				final int threshold = typedTrigrams.length - this.windowsPerEdit * this.maxEdits;
				return new Pattern(typedString, typed, typedTrigrams, threshold, intactTrigrams);
			}
			throw new IllegalStateException("No value of at least " + this.patternLength + " code points found.");
		}

		/**
		 * Applies `edits` random edits — substitution, insertion, deletion or adjacent swap — never at position 0.
		 */
		@Nonnull
		private static int[] damage(@Nonnull Random random, @Nonnull int[] intact, int edits) {
			int[] typed = intact.clone();
			int applied = 0;
			while (applied < edits) {
				if (typed.length < 2) {
					break;
				}
				final int position = 1 + random.nextInt(typed.length - 1);
				final int letter = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
				switch (random.nextInt(4)) {
					case 0 -> {
						if (typed[position] == letter) {
							continue;
						}
						typed[position] = letter;
					}
					case 1 -> {
						final int[] longer = new int[typed.length + 1];
						System.arraycopy(typed, 0, longer, 0, position);
						longer[position] = letter;
						System.arraycopy(typed, position, longer, position + 1, typed.length - position);
						typed = longer;
					}
					case 2 -> {
						final int[] shorter = new int[typed.length - 1];
						System.arraycopy(typed, 0, shorter, 0, position);
						System.arraycopy(typed, position + 1, shorter, position, typed.length - position - 1);
						typed = shorter;
					}
					default -> {
						if (position + 1 >= typed.length || typed[position] == typed[position + 1]) {
							continue;
						}
						final int swap = typed[position];
						typed[position] = typed[position + 1];
						typed[position + 1] = swap;
					}
				}
				applied++;
			}
			return typed;
		}

		/**
		 * Fails the trial if, for any pattern over several seeds, a value within `maxEdits` of the pattern is not
		 * among the candidates of either generator. This is the property the whole scheme rests on, and its failure
		 * is silent in production — missing results, no exception — so it is asserted here, not logged.
		 */
		private void assertSoundness() {
			final boolean[] candidate = new boolean[this.values.length];
			for (int seed = 1; seed <= SOUNDNESS_SEEDS; seed++) {
				for (final Pattern pattern : cutPatterns(new Random(seed), PATTERN_COUNT)) {
					final List<Integer> counted = new ArrayList<>(4096);
					countedUnionCandidates(pattern, counted);
					final List<Integer> filtered = new ArrayList<>(4096);
					prefixFilterCandidates(pattern, filtered);
					final List<Integer> expanded = new ArrayList<>(4096);
					swapExpansionCandidates(pattern, expanded);
					final List<Integer> enumerated = new ArrayList<>(4096);
					editEnumerationCandidates(pattern, enumerated);
					checkSuperset(pattern, counted, candidate, "counted union");
					checkSuperset(pattern, filtered, candidate, "prefix filter");
					checkSuperset(pattern, expanded, candidate, "swap expansion");
					checkSuperset(pattern, enumerated, candidate, "edit enumeration");
				}
			}
		}

		private void checkSuperset(
			@Nonnull Pattern pattern, @Nonnull List<Integer> ids, @Nonnull boolean[] candidate, @Nonnull String arm
		) {
			Arrays.fill(candidate, false);
			for (final int id : ids) {
				candidate[id] = true;
			}
			for (int id = 1; id < this.values.length; id++) {
				if (!candidate[id] && matches(id, pattern)) {
					throw new IllegalStateException(
						arm + " missed a true match under " + this.metric + ": pattern `" + pattern.typed() +
							"`, value `" + this.values[id] + "`, threshold " + pattern.threshold()
					);
				}
			}
		}

		/**
		 * Prints the candidate ratio the hypothesis is about, averaged over the timing patterns.
		 */
		private void reportCandidateRatio() {
			long unionCandidates = 0;
			long filterCandidates = 0;
			long expansionCandidates = 0;
			long expansionScanOnly = 0;
			long enumerationCandidates = 0;
			long enumerationQueries = 0;
			long exactCandidates = 0;
			long trueMatches = 0;
			int scanOnlyPatterns = 0;
			for (final Pattern pattern : this.patterns) {
				unionCandidates += countedUnionCandidates(pattern, null);
				filterCandidates += prefixFilterCandidates(pattern, null);
				final int expanded = swapExpansionCandidates(pattern, null);
				expansionCandidates += expanded;
				enumerationCandidates += editEnumerationCandidates(pattern, null);
				enumerationQueries += this.lastEnumerationQueries;
				if (expanded == this.values.length - 1) {
					expansionScanOnly++;
				}
				if (pattern.threshold() <= 0) {
					scanOnlyPatterns++;
				}
				exactCandidates += this.index.resolveCandidateValueIds(pattern.intactTrigrams()).length;
				for (int id = 1; id < this.values.length; id++) {
					if (matches(id, pattern)) {
						trueMatches++;
					}
				}
			}
			final int distinct = this.values.length - 1;
			System.out.printf(
				Locale.ROOT,
				"# %s %s: %d distinct values, pattern length %d, %d edits (c=%d): counted-union candidates " +
					"%.2f %% of the corpus (avg %d), prefix-filter candidates avg %d, %d of %d patterns have no " +
					"guaranteed trigram and take the whole corpus; swap-expansion candidates %.2f %% (avg %d), " +
					"%d of %d whole corpus; edit-enumeration candidates %.2f %% (avg %d) from avg %d exact lookups " +
					"over an alphabet of %d; true fuzzy matches avg %d, exact-intersection candidates for the intact " +
					"fragment avg %d%n",
				this.valueShape, this.metric, distinct, this.patternLength, this.maxEdits, this.windowsPerEdit,
				unionCandidates * 100.0 / (PATTERN_COUNT * (double) distinct), unionCandidates / PATTERN_COUNT,
				filterCandidates / PATTERN_COUNT, scanOnlyPatterns, PATTERN_COUNT,
				expansionCandidates * 100.0 / (PATTERN_COUNT * (double) distinct), expansionCandidates / PATTERN_COUNT,
				expansionScanOnly, PATTERN_COUNT,
				enumerationCandidates * 100.0 / (PATTERN_COUNT * (double) distinct), enumerationCandidates / PATTERN_COUNT,
				enumerationQueries / PATTERN_COUNT, this.alphabet.length,
				trueMatches / PATTERN_COUNT, exactCandidates / PATTERN_COUNT
			);
		}

		boolean matches(int id, @Nonnull Pattern pattern) {
			return EditDistances.approximatelyContains(
				this.valueCodePoints[id], pattern.typedCodePoints(), this.maxEdits, this.transpositions
			);
		}

		@Nonnull
		Pattern nextPattern() {
			final Pattern pattern = this.patterns[this.next];
			this.next = (this.next + 1) % PATTERN_COUNT;
			return pattern;
		}

		/**
		 * Candidate generation by a dense counter: per value id, count the pattern's trigrams it contains; keep ids
		 * reaching the threshold. Linear in the corpus (the counter is filled and scanned whole). When `sink` is
		 * given the ids are appended to it, otherwise only counted.
		 *
		 * @return the number of candidates
		 */
		int countedUnionCandidates(@Nonnull Pattern pattern, @Nullable List<Integer> sink) {
			final int distinct = this.values.length - 1;
			if (pattern.threshold() <= 0) {
				return wholeCorpus(sink, distinct);
			}
			final int[] counter = this.counts;
			Arrays.fill(counter, 0);
			for (final long trigram : pattern.typedTrigrams()) {
				final OfInt ids = this.index.getValueIdsOf(trigram).iterator();
				while (ids.hasNext()) {
					counter[ids.nextInt()]++;
				}
			}
			int candidates = 0;
			final int threshold = pattern.threshold();
			for (int id = 1; id < counter.length; id++) {
				if (counter[id] >= threshold) {
					candidates++;
					if (sink != null) {
						sink.add(id);
					}
				}
			}
			return candidates;
		}

		/**
		 * Candidate generation by the pigeonhole bound: a value holding at least `u − c·d` of `u` trigrams holds at
		 * least one of any `c·d + 1` of them, so the `c·d + 1` smallest postings are iterated and each id's count is
		 * confirmed by membership tests against the remaining postings. Proportional to the small postings, never
		 * to the corpus; the large postings are only probed.
		 *
		 * @return the number of candidates
		 */
		int prefixFilterCandidates(@Nonnull Pattern pattern, @Nullable List<Integer> sink) {
			final int distinct = this.values.length - 1;
			if (pattern.threshold() <= 0) {
				return wholeCorpus(sink, distinct);
			}
			final long[] trigrams = pattern.typedTrigrams();
			final Bitmap[] postings = new Bitmap[trigrams.length];
			final Integer[] order = new Integer[trigrams.length];
			for (int i = 0; i < trigrams.length; i++) {
				postings[i] = this.index.getValueIdsOf(trigrams[i]);
				order[i] = i;
			}
			Arrays.sort(order, (a, b) -> Integer.compare(postings[a].size(), postings[b].size()));
			final int probeCount = trigrams.length - pattern.threshold() + 1;
			final int threshold = pattern.threshold();
			int candidates = 0;
			// the union of the smallest postings; an id already seen in an earlier small posting is skipped through
			// the membership test, so each candidate is counted once
			for (int p = 0; p < probeCount; p++) {
				final OfInt ids = postings[order[p]].iterator();
				while (ids.hasNext()) {
					final int id = ids.nextInt();
					boolean seenInEarlierProbe = false;
					for (int q = 0; q < p; q++) {
						if (postings[order[q]].contains(id)) {
							seenInEarlierProbe = true;
							break;
						}
					}
					if (seenInEarlierProbe) {
						continue;
					}
					int count = 1;
					for (int q = p + 1; q < trigrams.length && count < threshold; q++) {
						if (postings[order[q]].contains(id)) {
							count++;
						}
					}
					if (count >= threshold) {
						candidates++;
						if (sink != null) {
							sink.add(id);
						}
					}
				}
			}
			return candidates;
		}

		/**
		 * Candidate generation by swap expansion (javadoc of the class, arm `swapExpansion`). Marks are set in
		 * `marks`; every contributing query uses the Levenshtein constant `c = 3` on its own distinct trigram count,
		 * and a query whose threshold drops to zero marks the whole corpus — the same honesty rule as the other arms.
		 *
		 * @return the number of candidates
		 */
		int swapExpansionCandidates(@Nonnull Pattern pattern, @Nullable List<Integer> sink) {
			final int distinct = this.values.length - 1;
			if (!this.transpositions) {
				// without transpositions in the metric there is nothing to expand
				return countedUnionCandidates(pattern, sink);
			}
			final boolean[] mark = this.marks;
			Arrays.fill(mark, false);
			final int[] typed = pattern.typedCodePoints();
			if (this.maxEdits == 1) {
				markLevenshteinNeighbourhood(typed, 1, mark);
				for (int i = 0; i + 1 < typed.length; i++) {
					markExact(swapped(typed, i), mark);
				}
			} else if (this.maxEdits == 2) {
				markLevenshteinNeighbourhood(typed, 2, mark);
				for (int i = 0; i + 1 < typed.length; i++) {
					final int[] once = swapped(typed, i);
					markLevenshteinNeighbourhood(once, 1, mark);
					// restricted Damerau never edits a swapped pair again, so double swaps do not overlap
					for (int j = i + 2; j + 1 < typed.length; j++) {
						markExact(swapped(once, j), mark);
					}
				}
			} else {
				throw new IllegalStateException("Swap expansion is written for one or two edits, got " + this.maxEdits);
			}
			int candidates = 0;
			for (int id = 1; id <= distinct; id++) {
				if (mark[id]) {
					candidates++;
					if (sink != null) {
						sink.add(id);
					}
				}
			}
			return candidates;
		}

		int lastEnumerationQueries;

		/**
		 * Candidate generation by full enumeration of the restricted Damerau-1 neighbourhood with exact lookups
		 * (javadoc of the class, arm `editEnumeration`). Only for one edit; for two the arm takes the whole corpus,
		 * which is the honest statement that it does not apply.
		 *
		 * @return the number of candidates
		 */
		int editEnumerationCandidates(@Nonnull Pattern pattern, @Nullable List<Integer> sink) {
			final int distinct = this.values.length - 1;
			this.lastEnumerationQueries = 0;
			if (this.maxEdits != 1) {
				return wholeCorpus(sink, distinct);
			}
			final boolean[] mark = this.marks;
			Arrays.fill(mark, false);
			final int[] typed = pattern.typedCodePoints();
			final int length = typed.length;
			// deletions
			for (int i = 0; i < length; i++) {
				final int[] variant = new int[length - 1];
				System.arraycopy(typed, 0, variant, 0, i);
				System.arraycopy(typed, i + 1, variant, i, length - i - 1);
				markExact(variant, mark);
				this.lastEnumerationQueries++;
			}
			// substitutions
			for (int i = 0; i < length; i++) {
				for (final int letter : this.alphabet) {
					if (letter == typed[i]) {
						continue;
					}
					final int[] variant = typed.clone();
					variant[i] = letter;
					markExact(variant, mark);
					this.lastEnumerationQueries++;
				}
			}
			// insertions
			for (int i = 0; i <= length; i++) {
				for (final int letter : this.alphabet) {
					final int[] variant = new int[length + 1];
					System.arraycopy(typed, 0, variant, 0, i);
					variant[i] = letter;
					System.arraycopy(typed, i, variant, i + 1, length - i);
					markExact(variant, mark);
					this.lastEnumerationQueries++;
				}
			}
			// swaps, when the metric counts them as one edit
			if (this.transpositions) {
				for (int i = 0; i + 1 < length; i++) {
					markExact(swapped(typed, i), mark);
					this.lastEnumerationQueries++;
				}
			}
			// the pattern itself: distance zero is inside the neighbourhood
			markExact(typed, mark);
			this.lastEnumerationQueries++;
			int candidates = 0;
			for (int id = 1; id <= distinct; id++) {
				if (mark[id]) {
					candidates++;
					if (sink != null) {
						sink.add(id);
					}
				}
			}
			return candidates;
		}

		@Nonnull
		private static int[] swapped(@Nonnull int[] source, int position) {
			final int[] copy = source.clone();
			final int swap = copy[position];
			copy[position] = copy[position + 1];
			copy[position + 1] = swap;
			return copy;
		}

		/**
		 * Marks every value holding at least `u − 3·edits` of the string's distinct trigrams (the Levenshtein
		 * neighbourhood under the trigram bound), or the whole corpus when nothing is guaranteed.
		 */
		private void markLevenshteinNeighbourhood(@Nonnull int[] codePoints, int edits, @Nonnull boolean[] mark) {
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(new String(codePoints, 0, codePoints.length));
			final int threshold = trigrams.length - 3 * edits;
			if (threshold <= 0) {
				Arrays.fill(mark, 1, mark.length, true);
				return;
			}
			final int[] counter = this.counts;
			Arrays.fill(counter, 0);
			for (final long trigram : trigrams) {
				final OfInt ids = this.index.getValueIdsOf(trigram).iterator();
				while (ids.hasNext()) {
					counter[ids.nextInt()]++;
				}
			}
			for (int id = 1; id < counter.length; id++) {
				if (counter[id] >= threshold) {
					mark[id] = true;
				}
			}
		}

		/**
		 * Marks every value containing the string verbatim, through today's all-trigram intersection; a string too
		 * short for a trigram cannot be served and marks the whole corpus.
		 */
		private void markExact(@Nonnull int[] codePoints, @Nonnull boolean[] mark) {
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams(new String(codePoints, 0, codePoints.length));
			if (trigrams.length == 0) {
				Arrays.fill(mark, 1, mark.length, true);
				return;
			}
			for (final int id : this.index.resolveCandidateValueIds(trigrams)) {
				mark[id] = true;
			}
		}

		private static int wholeCorpus(@Nullable List<Integer> sink, int distinct) {
			if (sink != null) {
				for (int id = 1; id <= distinct; id++) {
					sink.add(id);
				}
			}
			return distinct;
		}
	}

	@Benchmark
	public void countedUnion(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.countedUnionCandidates(state.nextPattern(), null));
	}

	@Benchmark
	public void prefixFilter(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.prefixFilterCandidates(state.nextPattern(), null));
	}

	@Benchmark
	public void swapExpansion(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.swapExpansionCandidates(state.nextPattern(), null));
	}

	@Benchmark
	public void swapExpansionThenVerify(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		final Pattern pattern = state.nextPattern();
		final List<Integer> candidates = new ArrayList<>(1024);
		state.swapExpansionCandidates(pattern, candidates);
		blackhole.consume(verify(state, pattern, candidates));
	}

	@Benchmark
	public void editEnumeration(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.editEnumerationCandidates(state.nextPattern(), null));
	}

	@Benchmark
	public void editEnumerationThenVerify(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		final Pattern pattern = state.nextPattern();
		final List<Integer> candidates = new ArrayList<>(1024);
		state.editEnumerationCandidates(pattern, candidates);
		blackhole.consume(verify(state, pattern, candidates));
	}

	@Benchmark
	public void countedUnionThenVerify(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		final Pattern pattern = state.nextPattern();
		final List<Integer> candidates = new ArrayList<>(1024);
		state.countedUnionCandidates(pattern, candidates);
		blackhole.consume(verify(state, pattern, candidates));
	}

	@Benchmark
	public void prefixFilterThenVerify(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		final Pattern pattern = state.nextPattern();
		final List<Integer> candidates = new ArrayList<>(1024);
		state.prefixFilterCandidates(pattern, candidates);
		blackhole.consume(verify(state, pattern, candidates));
	}

	@Benchmark
	public void fullScanApproximate(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		final Pattern pattern = state.nextPattern();
		int matches = 0;
		for (int id = 1; id < state.values.length; id++) {
			if (state.matches(id, pattern)) {
				matches++;
			}
		}
		blackhole.consume(matches);
	}

	@Benchmark
	public void exactIntersectionIntactPattern(@Nonnull CorpusState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(state.index.resolveCandidateValueIds(state.nextPattern().intactTrigrams()));
	}

	private static int verify(@Nonnull CorpusState state, @Nonnull Pattern pattern, @Nonnull List<Integer> candidates) {
		int matches = 0;
		for (final int id : candidates) {
			if (state.matches(id, pattern)) {
				matches++;
			}
		}
		return matches;
	}
}
