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
import io.evitadb.test.fulltext.TypoThresholds;
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
import java.util.Random;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the three ways `p3-suggester.md` §4.2.3 proposes to combine search-as-you-type with typo tolerance on the
 * last, unfinished query word — question 2 of the 2026-10-06 round of the typo-tolerance analysis:
 *
 * - **option 3, prefix only** — prefix scan of the typed fragment plus exact stem hypotheses, no typo tolerance;
 * - **option 2, prefix ∪ typo** — the same plus a typo expansion of the fragment's stem hypotheses;
 * - **option 2 + open end** — the same plus an open-ended typo walk of the fragment, which accepts a term when some
 *   prefix of it is within the budget, so a word that is mistyped *and* unfinished is found.
 *
 * Corpus: a deterministic sample of inflected forms from the hunspell `cs_CZ` fixture, each given one synthetic typo
 * after its first letter, then cut at every keystroke from just after the typo to the end. The target is the index
 * stem of the correctly spelt form; the dictionary is {@link CzechStemDictionary}, about 865,000 folded stems.
 * Thresholds are the proposal of §7.1 measured on the typed fragment.
 *
 * Timings are single-threaded JUnit wall-clock after a warm-up pass, indicative only.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prefix × typo for search-as-you-type, measured")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SLOW)
class PrefixTypoMeasurementTest {
	/**
	 * Number of forms in the corpus, spread evenly over the sorted sample.
	 */
	private static final int CORPUS_SIZE = 1_000;
	/**
	 * The shortest form used; shorter ones leave no room for an unfinished keystroke after the typo.
	 */
	private static final int MIN_LENGTH = 6;
	private static final UnfinishedWordMode[] MODES = {
		UnfinishedWordMode.PREFIX_ONLY, UnfinishedWordMode.PREFIX_AND_TYPO, UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO
	};
	private static final String[] BUCKETS = {"3+ letters missing", "1–2 letters missing", "complete word"};
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
	@DisplayName("Recall, candidate count and latency per keystroke for the three options")
	void shouldMeasurePrefixTypoOptions() {
		final CzechStemDictionary stems = CzechStemDictionary.get();
		final TransactionalBucketBPlusTree<String> tree = stems.getTree();
		final List<Case> cases = corpus(stems);
		// warm-up pass over a slice, so the timed pass is not dominated by class loading and JIT
		for (int i = 0; i < Math.min(100, cases.size()); i++) {
			for (final UnfinishedWordMode mode : MODES) {
				expand(tree, cases.get(i).fragment(), mode);
			}
		}
		final Cell[][] cells = new Cell[MODES.length][BUCKETS.length];
		for (int m = 0; m < MODES.length; m++) {
			for (int b = 0; b < BUCKETS.length; b++) {
				cells[m][b] = new Cell();
			}
		}
		for (final Case testCase : cases) {
			for (int m = 0; m < MODES.length; m++) {
				final long start = System.nanoTime();
				final List<ExpandedTerm> terms = expand(tree, testCase.fragment(), MODES[m]);
				final long nanos = System.nanoTime() - start;
				boolean found = false;
				for (final ExpandedTerm term : terms) {
					if (term.term().equals(testCase.target())) {
						found = true;
						break;
					}
				}
				cells[m][testCase.bucket()].add(found, terms.size(), nanos);
			}
		}
		System.out.printf(
			Locale.ROOT, "prefix × typo: %d keystrokes over %d stems%n", cases.size(), stems.getSize()
		);
		System.out.println(
			"| option | keystroke | keystrokes | target found | candidates p50 | candidates p95 | " +
				"latency p50 | latency p99 |"
		);
		System.out.println("|---|---|---|---|---|---|---|---|");
		for (int m = 0; m < MODES.length; m++) {
			for (int b = 0; b < BUCKETS.length; b++) {
				System.out.println(cells[m][b].row(MODES[m].name(), BUCKETS[b]));
			}
		}
		assertTrue(cases.size() > 3_000, "expected thousands of keystrokes, got " + cases.size());
		// the open end is a superset of option 2, which is a superset of option 3
		for (int b = 0; b < BUCKETS.length; b++) {
			assertTrue(cells[1][b].found >= cells[0][b].found, "option 2 must not lose what option 3 finds");
			assertTrue(cells[2][b].found >= cells[1][b].found, "the open end must not lose what option 2 finds");
		}
	}

	/**
	 * Expands the fragment as the last query word.
	 */
	@Nonnull
	private static List<ExpandedTerm> expand(
		@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String fragment, @Nonnull UnfinishedWordMode mode
	) {
		final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, fragment);
		if (words.isEmpty()) {
			return List.of();
		}
		return TypoExpansion.expand(tree, words.get(0), TypoThresholds.PROPOSAL, LengthBase.TYPED, mode);
	}

	/**
	 * Builds the keystroke corpus: one typo per form after the first letter, every cut from just past the typo.
	 */
	@Nonnull
	private static List<Case> corpus(@Nonnull CzechStemDictionary stems) {
		final CzechStemmer stemmer = new CzechStemmer();
		final List<String> eligible = new ArrayList<>(4_096);
		for (final String form : stems.getSampledForms()) {
			if (form.length() >= MIN_LENGTH && CzechStemDictionary.indexTerm(form, stemmer) != null) {
				eligible.add(form);
			}
		}
		final Random random = new Random(42);
		final TypoKind[] kinds = TypoKind.values();
		final int stride = Math.max(1, eligible.size() / CORPUS_SIZE);
		final List<Case> cases = new ArrayList<>(CORPUS_SIZE * 6);
		for (int i = 0, n = 0; i < eligible.size() && n < CORPUS_SIZE; i += stride, n++) {
			final String form = eligible.get(i);
			final String target = CzechStemDictionary.indexTerm(form, stemmer);
			final String folded = CzechLexicon.fold(form);
			final int position = 1 + random.nextInt(folded.length() - 2);
			final String mistyped = SyntheticTypos.apply(folded, kinds[n % kinds.length], position, random);
			if (mistyped == null) {
				continue;
			}
			for (int keystroke = Math.max(position + 2, 3); keystroke <= mistyped.length(); keystroke++) {
				final int missing = mistyped.length() - keystroke;
				final int bucket = missing == 0 ? 2 : missing <= 2 ? 1 : 0;
				cases.add(new Case(mistyped.substring(0, keystroke), target, bucket));
			}
		}
		return cases;
	}

	/**
	 * One keystroke: the typed fragment, the stem the user is heading for, and the bucket it is reported in.
	 */
	private record Case(@Nonnull String fragment, @Nonnull String target, int bucket) {
	}

	/**
	 * Accumulates one option × bucket cell.
	 */
	private static final class Cell {
		private int count;
		private int found;
		private int[] candidates;
		private long[] nanos;

		Cell() {
			this.candidates = new int[16];
			this.nanos = new long[16];
		}

		void add(boolean wasFound, int candidateCount, long elapsed) {
			if (this.count == this.candidates.length) {
				this.candidates = Arrays.copyOf(this.candidates, this.count * 2);
				this.nanos = Arrays.copyOf(this.nanos, this.count * 2);
			}
			this.candidates[this.count] = candidateCount;
			this.nanos[this.count] = elapsed;
			this.count++;
			this.found += wasFound ? 1 : 0;
		}

		@Nonnull
		String row(@Nonnull String option, @Nonnull String bucket) {
			final int[] sortedCandidates = Arrays.copyOf(this.candidates, this.count);
			final long[] sortedNanos = Arrays.copyOf(this.nanos, this.count);
			Arrays.sort(sortedCandidates);
			Arrays.sort(sortedNanos);
			return String.format(
				Locale.ROOT, "| %s | %s | %d | %.1f %% | %d | %d | %.2f ms | %.2f ms |",
				option, bucket, this.count, this.found * 100.0 / this.count,
				sortedCandidates[percentile(50)], sortedCandidates[percentile(95)],
				sortedNanos[percentile(50)] / 1e6, sortedNanos[percentile(99)] / 1e6
			);
		}

		private int percentile(int p) {
			return Math.min(this.count - 1, (int) Math.ceil(p / 100.0 * this.count) - 1);
		}
	}
}
