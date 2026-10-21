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

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.LanguageStemDictionary;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker.Hit;
import io.evitadb.test.fulltext.SyntheticTypos;
import io.evitadb.test.fulltext.SyntheticTypos.TypoKind;
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.LengthBase;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.test.fulltext.TypoThresholds;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeats the threshold measurement of {@link TypoThresholdMeasurementTest} (`typo-tolerance-fulltext-dictionary.md`
 * §10.3) for every language with a folding analyzer pair — Czech as the reproduction check, then Slovak, Polish and
 * Romanian (§10.6). Typo tolerance itself is language-independent; what this measures is how typos interact with
 * each language's **stemmer and stem variants**: how many the stemmer absorbs, how many it knocks out of reach, how
 * much noise each length base admits, and how many stem variants a query word fans out to.
 *
 * Corpus: a deterministic sample of inflected forms of the language's hunspell fixture, each given exactly one
 * synthetic typo (substitution, insertion, deletion, transposition in turn) in one of three position classes
 * relative to its index stem — the **first** letter, inside the **stem**, or in the **suffix** — plus the correctly
 * spelt form as a control. The target is the index term the production index analyzer emits for the correctly spelt
 * form; the dictionary is {@link LanguageStemDictionary}, built through the same analyzer. Queries go through the
 * production search analyzer of the language, so the stem hypotheses are those of its variant stemmer.
 *
 * The method body is the Czech test's, unchanged in its sampling, seeding and policies, so the Czech row
 * reproduces §10.3 through the language-neutral harness.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Typo thresholds on a stemmed dictionary, measured per language")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class LanguageTypoThresholdMeasurementTest {
	/**
	 * Number of forms in the corpus, spread evenly over the sorted sample.
	 */
	private static final int CORPUS_SIZE = 3_000;
	/**
	 * Entity type the analyzers are resolved for; the built-in table does not distinguish entity types.
	 */
	private static final String ENTITY_TYPE = "PRODUCT";
	private static final LengthBase[] POLICIES = LengthBase.values();
	private static final String[] CLASSES = {"no typo (control)", "first letter", "stem", "suffix"};

	/**
	 * Measures one language. The sample modulus is the Czech one (1 form in 500) wherever that yields at least
	 * {@link #CORPUS_SIZE} eligible forms; the Romanian fixture generates about a quarter as many forms as the
	 * Czech one, so it samples 1 form in 250 to reach the same corpus size.
	 *
	 * @param languageTag   the locale with language and country, naming the fixture and the analyzers
	 * @param sampleModulus one distinct form in this many is sampled
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({"cs-CZ, 500", "sk-SK, 500", "pl-PL, 500", "ro-RO, 250"})
	@DisplayName("Recall and noise per typo position for the length bases, and the expansion size per word")
	void shouldMeasureThresholdPoliciesForLanguage(@Nonnull String languageTag, int sampleModulus) {
		final Locale locale = Locale.forLanguageTag(languageTag);
		final long buildStart = System.nanoTime();
		final LanguageStemDictionary stems = LanguageStemDictionary.build(locale, sampleModulus);
		final long buildMillis = (System.nanoTime() - buildStart) / 1_000_000L;
		try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry()) {
			measure(stems, registry.getSearchAnalyzer(ENTITY_TYPE, locale), sampleModulus, buildMillis);
		}
	}

	/**
	 * Runs the measurement over one language's dictionary and prints the tables of §10.3.
	 *
	 * @param stems          the language's dictionary with its sample
	 * @param searchAnalyzer the language's production search analyzer
	 * @param sampleModulus  one distinct form in this many was sampled
	 * @param buildMillis    how long the dictionary took to build
	 */
	private static void measure(
		@Nonnull LanguageStemDictionary stems,
		@Nonnull FulltextAnalyzer searchAnalyzer,
		int sampleModulus,
		long buildMillis
	) {
		final String language = stems.getLocale().toLanguageTag();
		final TransactionalBucketBPlusTree<String> tree = stems.getTree();
		final TypoThresholds thresholds = TypoThresholds.PROPOSAL;
		final Random random = new Random(7);
		final TypoKind[] kinds = TypoKind.values();

		final List<Map.Entry<String, String>> eligible = new ArrayList<>(16_384);
		for (final Map.Entry<String, String> entry : stems.getSampledForms().entrySet()) {
			if (entry.getKey().length() >= 3) {
				eligible.add(entry);
			}
		}
		final int stride = Math.max(1, eligible.size() / CORPUS_SIZE);

		final Stat[][] stats = new Stat[CLASSES.length][POLICIES.length];
		for (final Stat[] row : stats) {
			for (int p = 0; p < row.length; p++) {
				row[p] = new Stat();
			}
		}
		// best distance between the target and any hypothesis: 0, 1, 2, more than 2
		final int[][] bestDistance = new int[CLASSES.length][4];
		final int[] classCount = new int[CLASSES.length];
		final long[] hypothesisCount = new long[CLASSES.length];
		// length of the typed word against its index stem, and how the two bases disagree
		final int[] lengthDifference = new int[12];
		final int[][] editsTypedVersusStem = new int[3][3];
		final Expansion ed1 = new Expansion();
		final Expansion ed2 = new Expansion();
		final List<String> unreachable = new ArrayList<>(16);
		final List<String> controlMisses = new ArrayList<>(16);
		int unfolded = 0;

		for (int i = 0, n = 0; i < eligible.size() && n < CORPUS_SIZE; i += stride, n++) {
			final String form = eligible.get(i).getKey();
			final String target = eligible.get(i).getValue();
			final String folded = CzechLexicon.fold(form);
			unfolded += isAscii(folded) ? 0 : 1;
			lengthDifference[Math.min(lengthDifference.length - 1, Math.max(0, folded.length() - target.length()))]++;
			editsTypedVersusStem[thresholds.maxEdits(folded)][thresholds.maxEdits(target)]++;
			final int shared = commonPrefix(folded, target);
			for (int typoClass = 0; typoClass < CLASSES.length; typoClass++) {
				final String typed;
				if (typoClass == 0) {
					typed = folded;
				} else {
					final int position = switch (typoClass) {
						case 1 -> 0;
						case 2 -> shared < 2 ? -1 : 1 + random.nextInt(shared - 1);
						case 3 -> shared >= folded.length() ? -1 : shared + random.nextInt(folded.length() - shared);
						default -> throw new IllegalStateException("Unexpected typo class " + typoClass);
					};
					typed = position < 0
						? null : SyntheticTypos.apply(folded, kinds[n % kinds.length], position, random);
				}
				if (typed == null) {
					continue;
				}
				final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, typed);
				if (words.isEmpty()) {
					continue;
				}
				classCount[typoClass]++;
				final QueryWord word = words.get(0);
				hypothesisCount[typoClass] += word.hypotheses().size();
				// one walk per hypothesis at the widest budget; policies filter the hits by their own budget
				final Map<String, List<Hit>> hitsByHypothesis = CollectionUtils.createHashMap(word.hypotheses().size());
				int best = Integer.MAX_VALUE;
				for (final String hypothesis : word.hypotheses()) {
					final List<Hit> hits =
						new LevenshteinDictionaryWalker(hypothesis, 2, thresholds.frozenPrefix(hypothesis)).walk(tree);
					hitsByHypothesis.put(hypothesis, hits);
					for (final Hit hit : hits) {
						if (hit.term().equals(target)) {
							best = Math.min(best, hit.distance());
						}
					}
				}
				bestDistance[typoClass][best == Integer.MAX_VALUE ? 3 : best]++;
				if (typoClass == 0 && best != 0 && controlMisses.size() < 12) {
					controlMisses.add(form + " → " + word.hypotheses() + ", index `" + target + "`");
				}
				if (best == Integer.MAX_VALUE && typoClass > 1 && unreachable.size() < 12) {
					unreachable.add(
						form + " typed `" + typed + "` → " + word.hypotheses() + ", index `" + target + "`"
					);
				}
				for (int p = 0; p < POLICIES.length; p++) {
					final Set<String> admitted = CollectionUtils.createHashSet(32);
					for (final Map.Entry<String, List<Hit>> entry : hitsByHypothesis.entrySet()) {
						final int budget =
							TypoExpansion.maxEdits(thresholds, POLICIES[p], word.typed(), entry.getKey());
						for (final Hit hit : entry.getValue()) {
							if (hit.distance() <= budget) {
								admitted.add(hit.term());
							}
						}
					}
					final boolean found = admitted.contains(target);
					stats[typoClass][p].add(found, admitted.size() - (found ? 1 : 0));
				}
				// the expansion size a cap would have to bound: all hypotheses, everything within 1 and within 2
				final Set<String> within1 = CollectionUtils.createHashSet(32);
				final Set<String> within2 = CollectionUtils.createHashSet(64);
				for (final List<Hit> hits : hitsByHypothesis.values()) {
					for (final Hit hit : hits) {
						within2.add(hit.term());
						if (hit.distance() <= 1) {
							within1.add(hit.term());
						}
					}
				}
				ed1.add(within1.size());
				ed2.add(within2.size());
			}
		}

		final String prefix = "[" + language + "] ";
		System.out.printf(
			Locale.ROOT,
			"%sdictionary: %d forms generated, %d dropped by the index chain, %d split, %d stems; built in %d ms%n",
			prefix, stems.getFormCount(), stems.getDroppedFormCount(), stems.getSplitFormCount(), stems.getSize(),
			buildMillis
		);
		System.out.printf(
			Locale.ROOT, "%ssample (1 form in %d): %d forms, %d eligible (3+ letters), stride %d, corpus %d forms%n",
			prefix, sampleModulus, stems.getSampledForms().size(), eligible.size(), stride, classCount[0]
		);
		System.out.printf(Locale.ROOT, "%sfolded corpus forms with a non-ASCII letter: %d%n", prefix, unfolded);
		System.out.println(prefix + "typed length minus stem length: " + Arrays.toString(lengthDifference));
		System.out.println(prefix + "edits by typed length (rows 0,1,2) × edits by stem length (columns 0,1,2):");
		int disagreement = 0;
		for (int row = 0; row < editsTypedVersusStem.length; row++) {
			System.out.println(prefix + "  " + Arrays.toString(editsTypedVersusStem[row]));
			for (int column = 0; column < editsTypedVersusStem[row].length; column++) {
				disagreement += row == column ? 0 : editsTypedVersusStem[row][column];
			}
		}
		System.out.printf(
			Locale.ROOT, "%sbudget disagreement typed vs stem: %d of %d (%.1f %%)%n",
			prefix, disagreement, classCount[0], pct(disagreement, classCount[0])
		);
		for (int c = 0; c < CLASSES.length; c++) {
			System.out.printf(
				Locale.ROOT, "%sstem variants per word, %s: %.2f%n",
				prefix, CLASSES[c], classCount[c] == 0 ? 0.0 : hypothesisCount[c] / (double) classCount[c]
			);
		}
		final long mistypedWords = (long) classCount[1] + classCount[2] + classCount[3];
		System.out.printf(
			Locale.ROOT, "%sstem variants per mistyped word: %.2f%n",
			prefix, (hypothesisCount[1] + hypothesisCount[2] + hypothesisCount[3]) / (double) mistypedWords
		);
		System.out.println(prefix + "| typo position | words | best distance 0 | 1 | 2 | more than 2 |");
		System.out.println(prefix + "|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			System.out.printf(
				Locale.ROOT, "%s| %s | %d | %.1f %% | %.1f %% | %.1f %% | %.1f %% |%n",
				prefix, CLASSES[c], classCount[c],
				pct(bestDistance[c][0], classCount[c]), pct(bestDistance[c][1], classCount[c]),
				pct(bestDistance[c][2], classCount[c]), pct(bestDistance[c][3], classCount[c])
			);
		}
		final int stemAndSuffix = classCount[2] + classCount[3];
		final int outOfReach = bestDistance[2][3] + bestDistance[3][3];
		System.out.printf(
			Locale.ROOT, "%sout of reach over stem and suffix typos: %d of %d (%.1f %%)%n",
			prefix, outOfReach, stemAndSuffix, pct(outOfReach, stemAndSuffix)
		);
		System.out.println(prefix + "| typo position | length base | found | noise mean | noise p50 | noise p95 |");
		System.out.println(prefix + "|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			for (int p = 0; p < POLICIES.length; p++) {
				System.out.println(prefix + stats[c][p].row(CLASSES[c], POLICIES[p].name()));
			}
		}
		System.out.println(prefix + "terms within distance 1 per query word: " + ed1.summary());
		System.out.println(prefix + "terms within distance 2 per query word: " + ed2.summary());
		System.out.println(prefix + "examples a typo knocked out of reach: " + unreachable);
		System.out.println(prefix + "correctly typed words missing their own stem at distance 0: " + controlMisses);

		assertTrue(classCount[0] > 2_000, "expected thousands of words, got " + classCount[0]);
		assertEquals(0, unfolded, "the folded query word must be ASCII, as the search chain's folding makes it");
		// a correctly spelt word must reach its own stem exactly — the M7 invariant over inflected forms
		assertTrue(
			bestDistance[0][0] * 100L >= classCount[0] * 99L,
			prefix + "a correctly typed word must find its own index stem at distance 0, measured "
				+ bestDistance[0][0] + " of " + classCount[0] + "; examples: " + controlMisses
		);
	}

	/**
	 * Computes a percentage, zero for an empty whole.
	 *
	 * @param part  the part
	 * @param whole the whole
	 * @return the share in percent
	 */
	private static double pct(int part, int whole) {
		return whole == 0 ? 0.0 : part * 100.0 / whole;
	}

	/**
	 * Tells whether a string holds only ASCII characters.
	 *
	 * @param word the string
	 * @return whether every character is below 128
	 */
	private static boolean isAscii(@Nonnull String word) {
		for (int i = 0; i < word.length(); i++) {
			if (word.charAt(i) > 127) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Length of the common prefix of two strings.
	 *
	 * @param a the first string
	 * @param b the second string
	 * @return number of leading characters the two share
	 */
	private static int commonPrefix(@Nonnull String a, @Nonnull String b) {
		final int limit = Math.min(a.length(), b.length());
		int i = 0;
		while (i < limit && a.charAt(i) == b.charAt(i)) {
			i++;
		}
		return i;
	}

	/**
	 * Accumulates found and noise for one position class × policy.
	 */
	private static final class Stat {
		private int count;
		private int found;
		private int[] noise = new int[64];

		/**
		 * Records one query word.
		 *
		 * @param wasFound   whether the target was admitted
		 * @param noiseCount how many other terms were admitted
		 */
		void add(boolean wasFound, int noiseCount) {
			if (this.count == this.noise.length) {
				this.noise = Arrays.copyOf(this.noise, this.count * 2);
			}
			this.noise[this.count++] = noiseCount;
			this.found += wasFound ? 1 : 0;
		}

		/**
		 * Formats the accumulated values as one Markdown table row.
		 *
		 * @param typoClass the position class
		 * @param policy    the length base
		 * @return the row
		 */
		@Nonnull
		String row(@Nonnull String typoClass, @Nonnull String policy) {
			if (this.count == 0) {
				return "| " + typoClass + " | " + policy + " | - | - | - | - |";
			}
			final int[] sorted = Arrays.copyOf(this.noise, this.count);
			Arrays.sort(sorted);
			long sum = 0;
			for (final int value : sorted) {
				sum += value;
			}
			return String.format(
				Locale.ROOT, "| %s | %s | %.1f %% | %.1f | %d | %d |",
				typoClass, policy, pct(this.found, this.count), sum / (double) this.count,
				sorted[Math.max(0, (int) Math.ceil(0.5 * this.count) - 1)],
				sorted[Math.max(0, (int) Math.ceil(0.95 * this.count) - 1)]
			);
		}
	}

	/**
	 * Distribution of expansion sizes per query word.
	 */
	private static final class Expansion {
		private int count;
		private int[] sizes = new int[64];

		/**
		 * Records the expansion size of one query word.
		 *
		 * @param size number of distinct terms
		 */
		void add(int size) {
			if (this.count == this.sizes.length) {
				this.sizes = Arrays.copyOf(this.sizes, this.count * 2);
			}
			this.sizes[this.count++] = size;
		}

		/**
		 * Formats the distribution: quantiles, maximum and the shares over 10 and over 50.
		 *
		 * @return the summary line
		 */
		@Nonnull
		String summary() {
			final int[] sorted = Arrays.copyOf(this.sizes, this.count);
			Arrays.sort(sorted);
			int over10 = 0;
			int over50 = 0;
			for (final int size : sorted) {
				over10 += size > 10 ? 1 : 0;
				over50 += size > 50 ? 1 : 0;
			}
			return String.format(
				Locale.ROOT, "p50 %d, p90 %d, p99 %d, max %d, over 10: %.1f %%, over 50: %.1f %%",
				at(sorted, 0.5), at(sorted, 0.9), at(sorted, 0.99), sorted[sorted.length - 1],
				pct(over10, this.count), pct(over50, this.count)
			);
		}

		/**
		 * Reads a quantile of a sorted array.
		 *
		 * @param sorted   sorted values
		 * @param quantile the quantile, between 0 and 1
		 * @return the value at that quantile
		 */
		private static int at(@Nonnull int[] sorted, double quantile) {
			return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
		}
	}
}
