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
import io.evitadb.test.fulltext.SyntheticTypos;
import io.evitadb.test.fulltext.SyntheticTypos.TypoKind;
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.ExpandedTerm;
import io.evitadb.test.fulltext.TypoExpansion.LengthBase;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.test.fulltext.TypoExpansion.UnfinishedWordMode;
import io.evitadb.test.fulltext.TypoExpansion.UnfinishedWordRules;
import io.evitadb.test.fulltext.TypoThresholds;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * Measures the term-expansion cost of the two query types the typo-tolerance analysis distinguishes
 * (`typo-tolerance-fulltext-dictionary.md` §7.8):
 *
 * - a **submitted search** — the user presses Enter, the whole query is expanded once, every word is finished;
 *   measured without and with a prefix leg on the last word, to see what the prefix leg costs and what it adds;
 * - an **as-you-type query** — the suggester, or instant results; the query is re-run on every keystroke, finished
 *   words are expanded once and reused from a cache, the last word is unfinished and follows the §7.8 rules
 *   (prefix from three characters, prefix of fragment and hypotheses, open-ended typo walk, at most one typo).
 *   Measured twice: finished words at the full thresholds (two edits from eight characters) and every word capped
 *   at one typo, because a finished word is expanded on the keystroke that finishes it.
 *
 * Corpus: queries of one to three words drawn from a deterministic sample of inflected forms of the hunspell
 * `cs_CZ` fixture, typed without diacritics, half of them with one synthetic typo after the first letter of one
 * word. Dictionary: {@link CzechStemDictionary}, about 865,000 folded stems. The cost measured is query analysis
 * plus term expansion only — the postings union that fetches products is not part of it, because the dictionary
 * has no real postings; the number of candidate terms per query is reported as its proxy. Single-threaded JUnit
 * wall-clock after a warm-up pass, indicative only.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Term-expansion cost of a submitted search and of an as-you-type query")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class QueryTypeLatencyMeasurementTest {
	/**
	 * Number of queries in the corpus.
	 */
	private static final int QUERY_COUNT = 1_000;
	/**
	 * The proposal's thresholds with the two-typo band removed — every word at most one typo.
	 */
	private static final TypoThresholds ONE_TYPO = new TypoThresholds(4, Integer.MAX_VALUE, 1, true);
	/**
	 * The rules of a submitted search with prefix enabled on the last word: the word is complete, so it keeps the
	 * full thresholds; prefix from three characters, of the fragment and of the hypotheses.
	 */
	private static final UnfinishedWordRules SUBMITTED_PREFIX = new UnfinishedWordRules(3, 2, true);
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
	@DisplayName("Cost per submitted query and per keystroke, with candidate terms as the entity-leg proxy")
	void shouldMeasureBothQueryTypes() {
		final CzechStemDictionary stems = CzechStemDictionary.get();
		final TransactionalBucketBPlusTree<String> tree = stems.getTree();
		final List<Query> queries = corpus(stems);

		// warm-up over a slice of both simulations
		for (int i = 0; i < 100; i++) {
			submitted(tree, queries.get(i).text(), false);
			submitted(tree, queries.get(i).text(), true);
			asYouType(tree, queries.get(i), TypoThresholds.PROPOSAL, new Series(), new Series(), null, null);
		}

		final Series plain = new Series();
		final Series plainTerms = new Series();
		final Series withPrefix = new Series();
		final Series withPrefixTerms = new Series();
		final Series addedByPrefix = new Series();
		for (final Query query : queries) {
			long start = System.nanoTime();
			final Set<String> withoutTerms = submitted(tree, query.text(), false);
			plain.add(System.nanoTime() - start);
			plainTerms.add(withoutTerms.size());
			start = System.nanoTime();
			final Set<String> withTerms = submitted(tree, query.text(), true);
			withPrefix.add(System.nanoTime() - start);
			withPrefixTerms.add(withTerms.size());
			int added = 0;
			for (final String term : withTerms) {
				added += withoutTerms.contains(term) ? 0 : 1;
			}
			addedByPrefix.add(added);
		}

		final Series fullTyping = new Series();
		final Series fullSpace = new Series();
		final Series cappedTyping = new Series();
		final Series cappedSpace = new Series();
		final Recall recall = new Recall();
		final ByLength byLength = new ByLength();
		for (final Query query : queries) {
			asYouType(tree, query, TypoThresholds.PROPOSAL, fullTyping, fullSpace, null, null);
			asYouType(tree, query, ONE_TYPO, cappedTyping, cappedSpace, recall, byLength);
		}

		System.out.printf(
			Locale.ROOT, "query types: %d queries, %d keystrokes, %d stems%n",
			queries.size(), fullTyping.count + fullSpace.count, stems.getSize()
		);
		System.out.println("| query type | measured unit | units | p50 | p95 | p99 | max | over 1.5 ms | over 5 ms |");
		System.out.println("|---|---|---|---|---|---|---|---|---|");
		System.out.println(plain.timeRow("submitted, no prefix", "query"));
		System.out.println(withPrefix.timeRow("submitted, prefix on last word", "query"));
		System.out.println(fullTyping.timeRow("as-you-type, finished words 2 typos", "keystroke inside a word"));
		System.out.println(fullSpace.timeRow("as-you-type, finished words 2 typos", "keystroke finishing a word"));
		System.out.println(cappedTyping.timeRow("as-you-type, every word 1 typo", "keystroke inside a word"));
		System.out.println(cappedSpace.timeRow("as-you-type, every word 1 typo", "keystroke finishing a word"));
		System.out.println("| query type | candidate terms per query p50 | p95 | p99 | max |");
		System.out.println("|---|---|---|---|---|");
		System.out.println(plainTerms.countRow("submitted, no prefix"));
		System.out.println(withPrefixTerms.countRow("submitted, prefix on last word"));
		System.out.println(addedByPrefix.countRow("terms added by the prefix leg"));
		System.out.println(recall.report());
		System.out.println(byLength.report());

		assertTrue(queries.size() == QUERY_COUNT, "expected the full corpus");
		assertTrue(cappedSpace.count > 0 && cappedTyping.count > 0, "both keystroke classes must be measured");
	}

	/**
	 * Expands a submitted query: every word finished, optionally the last one with a prefix leg.
	 *
	 * @return the union of the candidate terms of all words
	 */
	@Nonnull
	private static Set<String> submitted(
		@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String text, boolean prefixOnLastWord
	) {
		final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, text);
		final Set<String> terms = CollectionUtils.createHashSet(64);
		for (int i = 0; i < words.size(); i++) {
			final boolean last = i == words.size() - 1;
			final UnfinishedWordMode mode = last && prefixOnLastWord
				? UnfinishedWordMode.PREFIX_AND_TYPO : UnfinishedWordMode.FINISHED;
			for (final ExpandedTerm term : TypoExpansion.expand(
				tree, words.get(i), TypoThresholds.PROPOSAL, LengthBase.TYPED, mode, SUBMITTED_PREFIX
			)) {
				terms.add(term.term());
			}
		}
		return terms;
	}

	/**
	 * Replays a query keystroke by keystroke. Finished words are expanded once, on the keystroke that finishes them,
	 * and reused afterwards; the last word, while unfinished, is expanded on every keystroke under the §7.8 rules.
	 *
	 * @param finishedThresholds thresholds for finished words
	 * @param typing             receives the cost of keystrokes inside a word
	 * @param finishing          receives the cost of keystrokes that finish a word (the space)
	 * @param recall             receives whether the last word's target was found, or null
	 * @param byLength           receives the keystroke cost and the last word's term count by fragment length, or
	 *                           null
	 */
	private static void asYouType(
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		@Nonnull Query query,
		@Nonnull TypoThresholds finishedThresholds,
		@Nonnull Series typing,
		@Nonnull Series finishing,
		@Nullable Recall recall,
		@Nullable ByLength byLength
	) {
		final Map<String, List<ExpandedTerm>> cache = CollectionUtils.createHashMap(8);
		final String text = query.text();
		for (int keystroke = 1; keystroke <= text.length(); keystroke++) {
			final String typed = text.substring(0, keystroke);
			final int lastSpace = typed.lastIndexOf(' ');
			final boolean endsWithSeparator = lastSpace == typed.length() - 1;
			final long start = System.nanoTime();
			// finished words: everything up to the last separator, expanded once and reused
			if (lastSpace > 0) {
				for (final QueryWord word : TypoExpansion.analyze(searchAnalyzer, typed.substring(0, lastSpace))) {
					if (!cache.containsKey(word.typed())) {
						cache.put(
							word.typed(),
							TypoExpansion.expand(
								tree, word, finishedThresholds, LengthBase.TYPED, UnfinishedWordMode.FINISHED,
								UnfinishedWordRules.PROPOSAL
							)
						);
					}
				}
			}
			// the unfinished last word, taken from the raw input so a stop-word fragment is not lost (§7.8 rule 6)
			List<ExpandedTerm> lastExpansion = null;
			if (!endsWithSeparator) {
				final String fragment = typed.substring(lastSpace + 1);
				final List<QueryWord> analyzed = TypoExpansion.analyze(searchAnalyzer, fragment);
				final QueryWord word = analyzed.isEmpty() ? new QueryWord(fragment, List.of()) : analyzed.get(0);
				lastExpansion = TypoExpansion.expand(
					tree, word, TypoThresholds.PROPOSAL, LengthBase.TYPED,
					UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO, UnfinishedWordRules.PROPOSAL
				);
			}
			final long elapsed = System.nanoTime() - start;
			(endsWithSeparator ? finishing : typing).add(elapsed);
			if (byLength != null && lastExpansion != null) {
				byLength.add(typed.length() - lastSpace - 1, elapsed, lastExpansion.size());
			}
			if (recall != null && lastExpansion != null) {
				final int wordIndex = typed.split(" ", -1).length - 1;
				final String fragment = typed.substring(lastSpace + 1);
				final String target = query.targets()[wordIndex];
				final int missing = query.words()[wordIndex].length() - fragment.length();
				boolean found = false;
				for (final ExpandedTerm term : lastExpansion) {
					if (term.term().equals(target)) {
						found = true;
						break;
					}
				}
				recall.add(fragment.length(), missing, query.typoWord() == wordIndex, found);
			}
		}
	}

	/**
	 * Builds the query corpus.
	 */
	@Nonnull
	private static List<Query> corpus(@Nonnull CzechStemDictionary stems) {
		final CzechStemmer stemmer = new CzechStemmer();
		final List<String> eligible = new ArrayList<>(8_192);
		for (final String form : stems.getSampledForms()) {
			if (form.length() >= 3 && CzechStemDictionary.indexTerm(form, stemmer) != null) {
				eligible.add(form);
			}
		}
		final Random random = new Random(11);
		final TypoKind[] kinds = TypoKind.values();
		final List<Query> queries = new ArrayList<>(QUERY_COUNT);
		while (queries.size() < QUERY_COUNT) {
			final int wordCount = 1 + random.nextInt(3);
			final String[] typed = new String[wordCount];
			final String[] targets = new String[wordCount];
			for (int w = 0; w < wordCount; w++) {
				final String form = eligible.get(random.nextInt(eligible.size()));
				typed[w] = CzechLexicon.fold(form);
				targets[w] = CzechStemDictionary.indexTerm(form, stemmer);
			}
			int typoWord = -1;
			if (random.nextBoolean()) {
				typoWord = random.nextInt(wordCount);
				final String word = typed[typoWord];
				if (word.length() >= 3) {
					final String mistyped = SyntheticTypos.apply(
						word, kinds[queries.size() % kinds.length], 1 + random.nextInt(word.length() - 1), random
					);
					if (mistyped != null) {
						typed[typoWord] = mistyped;
					} else {
						typoWord = -1;
					}
				} else {
					typoWord = -1;
				}
			}
			queries.add(new Query(String.join(" ", typed), typed, targets, typoWord));
		}
		return queries;
	}

	/**
	 * One query of the corpus.
	 *
	 * @param text     the query text, folded
	 * @param words    the typed words
	 * @param targets  the index stem each word is heading for
	 * @param typoWord index of the mistyped word, or -1
	 */
	private record Query(@Nonnull String text, @Nonnull String[] words, @Nonnull String[] targets, int typoWord) {
	}

	/**
	 * A series of measurements with percentiles.
	 */
	private static final class Series {
		private int count;
		private long[] values = new long[1_024];

		void add(long value) {
			if (this.count == this.values.length) {
				this.values = Arrays.copyOf(this.values, this.count * 2);
			}
			this.values[this.count++] = value;
		}

		@Nonnull
		private long[] sorted() {
			final long[] sorted = Arrays.copyOf(this.values, this.count);
			Arrays.sort(sorted);
			return sorted;
		}

		private static long at(@Nonnull long[] sorted, double quantile) {
			return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
		}

		@Nonnull
		String timeRow(@Nonnull String type, @Nonnull String unit) {
			final long[] sorted = sorted();
			int over15 = 0;
			int over5 = 0;
			for (final long value : sorted) {
				over15 += value > 1_500_000L ? 1 : 0;
				over5 += value > 5_000_000L ? 1 : 0;
			}
			return String.format(
				Locale.ROOT, "| %s | %s | %d | %.2f ms | %.2f ms | %.2f ms | %.2f ms | %.1f %% | %.1f %% |",
				type, unit, this.count, at(sorted, 0.5) / 1e6, at(sorted, 0.95) / 1e6, at(sorted, 0.99) / 1e6,
				sorted[sorted.length - 1] / 1e6, over15 * 100.0 / this.count, over5 * 100.0 / this.count
			);
		}

		@Nonnull
		String countRow(@Nonnull String type) {
			final long[] sorted = sorted();
			return String.format(
				Locale.ROOT, "| %s | %d | %d | %d | %d |",
				type, at(sorted, 0.5), at(sorted, 0.95), at(sorted, 0.99), sorted[sorted.length - 1]
			);
		}
	}

	/**
	 * Keystroke cost and candidate terms of the unfinished last word, by the length of the typed fragment.
	 */
	private static final class ByLength {
		private static final String[] LABELS = {"1–2 (no prefix leg)", "3", "4", "5", "6–7", "8+"};
		private final Series[] times = series(LABELS.length);
		private final Series[] terms = series(LABELS.length);

		@Nonnull
		private static Series[] series(int count) {
			final Series[] result = new Series[count];
			for (int i = 0; i < count; i++) {
				result[i] = new Series();
			}
			return result;
		}

		void add(int fragmentLength, long elapsed, int termCount) {
			final int bucket = fragmentLength <= 2 ? 0 : fragmentLength <= 5 ? fragmentLength - 2
				: fragmentLength <= 7 ? 4 : 5;
			this.times[bucket].add(elapsed);
			this.terms[bucket].add(termCount);
		}

		@Nonnull
		String report() {
			final StringBuilder sb = new StringBuilder(1_024);
			sb.append("| fragment length | keystrokes | latency p50 | p95 | p99 | over 1.5 ms | over 5 ms | ")
				.append("last-word terms p50 | p95 | p99 |\n|---|---|---|---|---|---|---|---|---|---|\n");
			for (int b = 0; b < LABELS.length; b++) {
				final long[] t = this.times[b].sorted();
				final long[] c = this.terms[b].sorted();
				int over15 = 0;
				int over5 = 0;
				for (final long value : t) {
					over15 += value > 1_500_000L ? 1 : 0;
					over5 += value > 5_000_000L ? 1 : 0;
				}
				sb.append(String.format(
					Locale.ROOT, "| %s | %d | %.2f ms | %.2f ms | %.2f ms | %.1f %% | %.1f %% | %d | %d | %d |%n",
					LABELS[b], t.length, Series.at(t, 0.5) / 1e6, Series.at(t, 0.95) / 1e6, Series.at(t, 0.99) / 1e6,
					over15 * 100.0 / t.length, over5 * 100.0 / t.length,
					Series.at(c, 0.5), Series.at(c, 0.95), Series.at(c, 0.99)
				));
			}
			return sb.toString();
		}
	}

	/**
	 * Whether the unfinished last word reaches its target, by letters missing and by whether it carries the typo.
	 */
	private static final class Recall {
		private static final String[] BUCKETS = {"3+ letters missing", "1–2 letters missing", "complete word"};
		private final int[][] counts = new int[2][3];
		private final int[][] found = new int[2][3];

		void add(int fragmentLength, int missing, boolean mistyped, boolean wasFound) {
			if (fragmentLength < 3) {
				return;
			}
			final int bucket = missing <= 0 ? 2 : missing <= 2 ? 1 : 0;
			final int row = mistyped ? 1 : 0;
			this.counts[row][bucket]++;
			this.found[row][bucket] += wasFound ? 1 : 0;
		}

		@Nonnull
		String report() {
			final StringBuilder sb = new StringBuilder(512);
			sb.append("| last word | keystroke | keystrokes | target found |\n|---|---|---|---|\n");
			for (int row = 0; row < 2; row++) {
				for (int b = 0; b < BUCKETS.length; b++) {
					sb.append(String.format(
						Locale.ROOT, "| %s | %s | %d | %.1f %% |%n",
						row == 0 ? "correctly typed" : "with a typo", BUCKETS[b], this.counts[row][b],
						this.counts[row][b] == 0 ? 0.0 : this.found[row][b] * 100.0 / this.counts[row][b]
					));
				}
			}
			return sb.toString();
		}
	}
}
