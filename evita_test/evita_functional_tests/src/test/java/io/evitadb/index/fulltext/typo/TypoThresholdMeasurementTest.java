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
import io.evitadb.test.fulltext.CzechStemDictionary;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker.Hit;
import io.evitadb.test.fulltext.SyntheticTypos;
import io.evitadb.test.fulltext.SyntheticTypos.TypoKind;
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.LengthBase;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.test.fulltext.TypoThresholds;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the one open fork of the typo-tolerance analysis (`typo-tolerance-fulltext-dictionary.md` §7.3): on a
 * dictionary of **folded stems**, which length should the threshold function read — the typed word, each stem
 * hypothesis, or a combination — and how often does a typo survive stemming, get absorbed by it, or knock the stem
 * off its rule path. It also records how many terms one query word expands to, the input for the expansion cap.
 *
 * Corpus: a deterministic sample of inflected forms of the hunspell `cs_CZ` fixture, each given exactly one
 * synthetic typo (substitution, insertion, deletion, transposition in turn) in one of three position classes
 * relative to its index stem — the **first** letter, inside the **stem** (before the end of the prefix the form
 * shares with its stem), or in the **suffix** — plus the correctly spelt form as a control. The target is the index
 * stem of the correctly spelt form; the dictionary is {@link CzechStemDictionary}, about 865,000 folded stems.
 *
 * For every query word each stem hypothesis is walked once at distance 2; a policy then admits the hits within the
 * budget it assigns to that hypothesis. **Found** means the target is admitted; **noise** counts the other admitted
 * terms — dictionary terms the query would also match, which is a precision proxy, not a judged error.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Typo thresholds on a stemmed dictionary, measured")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class TypoThresholdMeasurementTest {
	/**
	 * Number of forms in the corpus, spread evenly over the sorted sample.
	 */
	private static final int CORPUS_SIZE = 3_000;
	private static final LengthBase[] POLICIES = LengthBase.values();
	private static final String[] CLASSES = {"no typo (control)", "first letter", "stem", "suffix"};
	private static FulltextAnalyzerRegistry registry;
	private static FulltextAnalyzer searchAnalyzer;

	@BeforeAll
	static void setUpAnalyzers() {
		registry = new FulltextAnalyzerRegistry();
		searchAnalyzer = registry.getSearchAnalyzer("PRODUCT", Locale.forLanguageTag("cs"));
	}

	@AfterAll
	static void closeAnalyzers() {
		registry.close();
	}

	@Test
	@DisplayName("Recall and noise per typo position for the three length bases, and the expansion size per word")
	void shouldMeasureThresholdPolicies() {
		final CzechStemDictionary stems = CzechStemDictionary.get();
		final TransactionalBucketBPlusTree<String> tree = stems.getTree();
		final CzechStemmer stemmer = new CzechStemmer();
		final TypoThresholds thresholds = TypoThresholds.PROPOSAL;
		final Random random = new Random(7);
		final TypoKind[] kinds = TypoKind.values();

		final List<String> eligible = new ArrayList<>(8_192);
		for (final String form : stems.getSampledForms()) {
			if (form.length() >= 3 && CzechStemDictionary.indexTerm(form, stemmer) != null) {
				eligible.add(form);
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
		// length of the typed word against its index stem, and how the two bases disagree
		final int[] lengthDifference = new int[12];
		final int[][] editsTypedVersusStem = new int[3][3];
		final Expansion ed1 = new Expansion();
		final Expansion ed2 = new Expansion();
		final List<String> unreachable = new ArrayList<>(16);

		for (int i = 0, n = 0; i < eligible.size() && n < CORPUS_SIZE; i += stride, n++) {
			final String form = eligible.get(i);
			final String target = CzechStemDictionary.indexTerm(form, stemmer);
			final String folded = CzechLexicon.fold(form);
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

		System.out.printf(Locale.ROOT, "thresholds: %d forms, %d stems%n", classCount[0], stems.getSize());
		System.out.println("typed length minus stem length: " + Arrays.toString(lengthDifference));
		System.out.println("edits by typed length (rows 0,1,2) × edits by stem length (columns 0,1,2):");
		for (final int[] row : editsTypedVersusStem) {
			System.out.println("  " + Arrays.toString(row));
		}
		System.out.println("| typo position | words | best distance 0 | 1 | 2 | more than 2 |");
		System.out.println("|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			System.out.printf(
				Locale.ROOT, "| %s | %d | %.1f %% | %.1f %% | %.1f %% | %.1f %% |%n",
				CLASSES[c], classCount[c],
				pct(bestDistance[c][0], classCount[c]), pct(bestDistance[c][1], classCount[c]),
				pct(bestDistance[c][2], classCount[c]), pct(bestDistance[c][3], classCount[c])
			);
		}
		System.out.println("| typo position | length base | found | noise mean | noise p50 | noise p95 |");
		System.out.println("|---|---|---|---|---|---|");
		for (int c = 0; c < CLASSES.length; c++) {
			for (int p = 0; p < POLICIES.length; p++) {
				System.out.println(stats[c][p].row(CLASSES[c], POLICIES[p].name()));
			}
		}
		System.out.println("terms within distance 1 per query word: " + ed1.summary());
		System.out.println("terms within distance 2 per query word: " + ed2.summary());
		System.out.println("examples a typo knocked out of reach: " + unreachable);

		assertTrue(classCount[0] > 2_000, "expected thousands of words, got " + classCount[0]);
		// a correctly spelt word must reach its own stem exactly — the M7 invariant over inflected forms
		assertTrue(
			bestDistance[0][0] * 100L >= classCount[0] * 99L,
			"a correctly typed word must find its own index stem at distance 0"
		);
	}

	private static double pct(int part, int whole) {
		return whole == 0 ? 0.0 : part * 100.0 / whole;
	}

	/**
	 * Length of the common prefix of two strings.
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

		void add(boolean wasFound, int noiseCount) {
			if (this.count == this.noise.length) {
				this.noise = Arrays.copyOf(this.noise, this.count * 2);
			}
			this.noise[this.count++] = noiseCount;
			this.found += wasFound ? 1 : 0;
		}

		@Nonnull
		String row(@Nonnull String typoClass, @Nonnull String policy) {
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

		void add(int size) {
			if (this.count == this.sizes.length) {
				this.sizes = Arrays.copyOf(this.sizes, this.count * 2);
			}
			this.sizes[this.count++] = size;
		}

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

		private static int at(@Nonnull int[] sorted, double quantile) {
			return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
		}
	}
}
