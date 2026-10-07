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
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How prefix and exact expansion of the last, unfinished query word should work over a dictionary of **folded
 * stems** — question 1 of the 2026-10-06 round of the typo-tolerance analysis. Nothing of this exists in the engine
 * yet; the test drives {@link TypoExpansion}, the reference implementation of the proposed expansion, against the
 * real Czech chains.
 *
 * The question is what string the prefix scan should start from while the user types. The dictionary holds stems,
 * and the user types a word that is longer than its stem once the suffix begins, and that the stemmer may rewrite at
 * its end (`muž` → `muh`). Four inputs are compared per keystroke:
 *
 * - **S1 prefix(fragment)** — keys starting with the folded typed fragment;
 * - **S2 exact(hypotheses)** — the stem hypotheses of the fragment that are keys;
 * - **S3 prefix(hypotheses)** — keys starting with any stem hypothesis of the fragment;
 * - **S4 = S1 ∪ S2** — the proposal: prefix of what was typed, plus exact stems of it.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prefix versus exact expansion of the unfinished last word")
@Tag(ENGINE)
@Tag(FULLTEXT)
class PrefixVersusExactExpansionTest {
	/**
	 * Entity type the registry resolves analyzers for; any name works, Czech is chosen by the locale.
	 */
	private static final String ENTITY_TYPE = "PRODUCT";
	/**
	 * The Czech locale.
	 */
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static FulltextAnalyzerRegistry registry;
	private static FulltextAnalyzer searchAnalyzer;
	private static FulltextAnalyzer indexAnalyzer;

	@BeforeAll
	static void setUpAnalyzers() {
		registry = new FulltextAnalyzerRegistry();
		searchAnalyzer = registry.getSearchAnalyzer(ENTITY_TYPE, CZECH);
		indexAnalyzer = registry.getIndexAnalyzer(ENTITY_TYPE, CZECH);
	}

	@AfterAll
	static void closeAnalyzers() {
		registry.close();
	}

	/**
	 * Returns the hypotheses of the first query word of `text`, or an empty list when the chain dropped it.
	 *
	 * @param text the typed text
	 * @return the stem hypotheses
	 */
	@Nonnull
	static List<String> hypotheses(@Nonnull String text) {
		final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, text);
		return words.isEmpty() ? List.of() : words.get(0).hypotheses();
	}

	/**
	 * Returns the single term the index chain emits for one word, or null when it emits none.
	 *
	 * @param word the word
	 * @return the indexed term
	 */
	static String indexed(@Nonnull String word) {
		final List<String> terms = new ArrayList<>(1);
		indexAnalyzer.analyze(word, (term, surface, start, end, increment) -> terms.add(term));
		return terms.isEmpty() ? null : terms.get(0);
	}

	@Nested
	@DisplayName("Sweep over the inflected forms of the cs_CZ lexicon")
	@Tag(SLOW)
	class Sweep {

		@Test
		@DisplayName("Every keystroke of every sampled form, four prefix inputs compared")
		void shouldMeasurePrefixInputsPerKeystroke() {
			final CzechStemDictionary stems = CzechStemDictionary.get();
			final TransactionalBucketBPlusTree<String> tree = stems.getTree();
			final CzechStemmer stemmer = new CzechStemmer();
			final Map<String, Integer> prefixCounts = CollectionUtils.createHashMap(65_536);
			final Bucket[] buckets = {
				new Bucket("inside the stem"), new Bucket("past the stem"), new Bucket("complete word")
			};
			final List<String> completeMisses = new ArrayList<>(16);
			int forms = 0;
			int shortcutMismatches = 0;
			int completeWordsNotCovered = 0;
			for (final String form : stems.getSampledForms()) {
				final String target = CzechStemDictionary.indexTerm(form, stemmer);
				if (target == null || form.length() < 3) {
					continue;
				}
				forms++;
				// the dictionary was built with a shortcut of the index chain; it must agree with the real one
				if (!target.equals(indexed(form))) {
					shortcutMismatches++;
				}
				final String folded = CzechLexicon.fold(form);
				final int commonPrefix = commonPrefix(folded, target);
				for (int keystroke = 2; keystroke <= folded.length(); keystroke++) {
					final String fragment = folded.substring(0, keystroke);
					final List<String> hypotheses = hypotheses(fragment);
					final Bucket bucket = keystroke == folded.length()
						? buckets[2] : keystroke <= commonPrefix ? buckets[0] : buckets[1];
					final boolean s1 = target.startsWith(fragment);
					final boolean s2 = hypotheses.contains(target);
					boolean s3 = false;
					int s2Count = 0;
					int s3Count = 0;
					for (final String hypothesis : hypotheses) {
						s3 |= target.startsWith(hypothesis);
						if (TypoExpansion.exact(tree, hypothesis)) {
							s2Count++;
						}
						s3Count += prefixCounts.computeIfAbsent(hypothesis, h -> TypoExpansion.prefixCount(tree, h));
					}
					final int s1Count = prefixCounts.computeIfAbsent(fragment, f -> TypoExpansion.prefixCount(tree, f));
					bucket.add(s1, s2, s3, s1 || s2, s1Count, s2Count, s3Count, hypotheses.isEmpty());
					if (bucket == buckets[2] && !s2) {
						completeWordsNotCovered++;
						if (completeMisses.size() < 15) {
							completeMisses.add(form + " → index `" + target + "`, query hypotheses " + hypotheses);
						}
					}
				}
			}
			System.out.printf(
				Locale.ROOT, "prefix vs exact: %d sampled forms, %d stems in the dictionary%n", forms, stems.getSize()
			);
			System.out.println(
				"| keystroke position | keystrokes | S1 prefix(fragment) | S2 exact(hyp.) | S3 prefix(hyp.) | " +
					"S4 = S1 ∪ S2 | mean keys S1 | mean keys S2 | mean keys S3 | fragment dropped |"
			);
			System.out.println("|---|---|---|---|---|---|---|---|---|---|");
			for (final Bucket bucket : buckets) {
				System.out.println(bucket.row());
			}
			System.out.println(
				"complete words whose own index stem is not among their query hypotheses: " +
					completeWordsNotCovered + " " + completeMisses
			);
			assertEquals(0, shortcutMismatches, "the dictionary shortcut must agree with the production index chain");
			assertTrue(forms > 5_000, "expected a sample of several thousand forms, got " + forms);
			// by construction: a fragment no longer than the shared prefix of word and stem is a prefix of the stem
			assertEquals(buckets[0].count, buckets[0].s1, "S1 must find every stem while typing inside it");
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
	}

	/**
	 * Accumulates the measurement of one class of keystrokes.
	 */
	private static final class Bucket {
		private final String name;
		private int count;
		private int s1;
		private int s2;
		private int s3;
		private int s4;
		private long s1Keys;
		private long s2Keys;
		private long s3Keys;
		private int dropped;

		Bucket(@Nonnull String name) {
			this.name = name;
		}

		void add(
			boolean s1, boolean s2, boolean s3, boolean s4, int s1Keys, int s2Keys, int s3Keys, boolean dropped
		) {
			this.count++;
			this.s1 += s1 ? 1 : 0;
			this.s2 += s2 ? 1 : 0;
			this.s3 += s3 ? 1 : 0;
			this.s4 += s4 ? 1 : 0;
			this.s1Keys += s1Keys;
			this.s2Keys += s2Keys;
			this.s3Keys += s3Keys;
			this.dropped += dropped ? 1 : 0;
		}

		@Nonnull
		String row() {
			return String.format(
				Locale.ROOT, "| %s | %d | %.1f %% | %.1f %% | %.1f %% | %.1f %% | %.0f | %.1f | %.0f | %d |",
				this.name, this.count, pct(this.s1), pct(this.s2), pct(this.s3), pct(this.s4),
				this.s1Keys / (double) this.count, this.s2Keys / (double) this.count,
				this.s3Keys / (double) this.count, this.dropped
			);
		}

		private double pct(int hits) {
			return hits * 100.0 / this.count;
		}
	}
}
