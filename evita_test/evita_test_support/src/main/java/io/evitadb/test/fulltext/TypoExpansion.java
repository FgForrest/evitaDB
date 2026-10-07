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

package io.evitadb.test.fulltext;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker.Hit;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reference implementation of the query-side term expansion the typo-tolerance analysis proposes
 * (`typo-tolerance-fulltext-dictionary.md` §3.2, stages Q1–Q3), over a dictionary of folded stems held in
 * evitaDB's {@link TransactionalBucketBPlusTree}. It exists to measure and to pin behaviour; the engine has none of
 * it yet.
 *
 * Three expansion sources produce dictionary terms for one query word:
 *
 * - **exact** — a stem hypothesis of the word is itself a dictionary key;
 * - **prefix** — dictionary keys starting with the typed, unfinished last word (search-as-you-type);
 * - **fuzzy** — dictionary keys within the edit budget of a hypothesis, found by {@link LevenshteinDictionaryWalker}.
 *
 * The output per query word is a set of {@link ExpandedTerm}s, each term once, carrying the best
 * {@link Exactness} and distance any source gave it — the unit P1 §5.3 ranks by.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TypoExpansion {

	private TypoExpansion() {
	}

	/**
	 * Which expansion produced a term — the classes of the `EXACTNESS` lane, best first.
	 */
	public enum Exactness {
		/**
		 * A stem hypothesis of the query word is the term itself.
		 */
		EXACT,
		/**
		 * The term starts with the unfinished query word.
		 */
		PREFIX,
		/**
		 * The term is within the edit budget of a hypothesis.
		 */
		FUZZY
	}

	/**
	 * How the last, unfinished query word is expanded while the user types (`p3-suggester.md` §4.2.3).
	 */
	public enum UnfinishedWordMode {
		/**
		 * The word is finished: exact and fuzzy expansion only, no prefix.
		 */
		FINISHED,
		/**
		 * Option 3: the last word gets a prefix scan and exact hypotheses, no typo tolerance.
		 */
		PREFIX_ONLY,
		/**
		 * Option 2: prefix scan plus typo expansion of the whole typed fragment's hypotheses, results unioned.
		 */
		PREFIX_AND_TYPO,
		/**
		 * Option 2 plus an **open-ended** typo expansion of the typed fragment: a term matches when some prefix of
		 * it is within the edit budget — finds a word that is mistyped *and* unfinished.
		 */
		PREFIX_AND_OPEN_END_TYPO
	}

	/**
	 * Which length the threshold function reads (§7.3, the open fork).
	 */
	public enum LengthBase {
		/**
		 * The folded typed word.
		 */
		TYPED,
		/**
		 * Each stem hypothesis on its own.
		 */
		HYPOTHESIS,
		/**
		 * Per hypothesis `min(edits by typed length, edits by hypothesis length + 1)`.
		 */
		COMBINED
	}

	/**
	 * One query word as the query chain emits it: the typed word, folded, and its stem hypotheses (all terms of one
	 * position).
	 *
	 * @param typed      the typed word, lowercased and diacritics-folded
	 * @param hypotheses the stem hypotheses, in emission order, without duplicates
	 */
	public record QueryWord(@Nonnull String typed, @Nonnull List<String> hypotheses) {
	}

	/**
	 * One dictionary term a query word expanded to.
	 *
	 * @param term       the dictionary key
	 * @param exactness  the best class any source gave it
	 * @param distance   the smallest edit distance any source gave it; 0 for exact and prefix hits
	 */
	public record ExpandedTerm(@Nonnull String term, @Nonnull Exactness exactness, int distance) {
	}

	/**
	 * Runs a query through the search-side analyzer and groups the emitted terms by position into query words.
	 *
	 * @param searchAnalyzer the search-slot analyzer (`czech-search` for Czech)
	 * @param query          the query text
	 * @return the query words in order
	 */
	@Nonnull
	public static List<QueryWord> analyze(@Nonnull FulltextAnalyzer searchAnalyzer, @Nonnull String query) {
		final List<QueryWord> words = new ArrayList<>(4);
		final List<String> typed = new ArrayList<>(4);
		final List<Set<String>> hypotheses = new ArrayList<>(4);
		searchAnalyzer.analyze(query, (term, surfaceForm, startOffset, endOffset, positionIncrement) -> {
			if (positionIncrement > 0 || hypotheses.isEmpty()) {
				typed.add(CzechLexicon.fold(surfaceForm.get().toLowerCase(Locale.ROOT)));
				hypotheses.add(new LinkedHashSet<>(4));
			}
			hypotheses.get(hypotheses.size() - 1).add(term);
		});
		for (int i = 0; i < typed.size(); i++) {
			words.add(new QueryWord(typed.get(i), List.copyOf(hypotheses.get(i))));
		}
		return words;
	}

	/**
	 * Decides the edit budget of one hypothesis under a length base.
	 *
	 * @param thresholds the threshold function
	 * @param base       which length it reads
	 * @param typed      the folded typed word
	 * @param hypothesis the hypothesis
	 * @return 0, 1 or 2
	 */
	public static int maxEdits(
		@Nonnull TypoThresholds thresholds,
		@Nonnull LengthBase base,
		@Nonnull String typed,
		@Nonnull String hypothesis
	) {
		return switch (base) {
			case TYPED -> thresholds.maxEdits(typed);
			case HYPOTHESIS -> thresholds.maxEdits(hypothesis);
			case COMBINED -> Math.min(
				thresholds.maxEdits(typed),
				Math.min(2, thresholds.maxEdits(hypothesis) + 1)
			);
		};
	}

	/**
	 * Tells whether the dictionary holds a key.
	 *
	 * @param tree the dictionary
	 * @param term the key
	 * @return whether it is present
	 */
	public static boolean exact(@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String term) {
		return tree.contains(term);
	}

	/**
	 * Returns every dictionary key starting with `prefix`, in dictionary order.
	 *
	 * @param tree   the dictionary
	 * @param prefix the prefix
	 * @return matching keys
	 */
	@Nonnull
	public static List<String> prefix(@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String prefix) {
		final List<String> result = new ArrayList<>(16);
		final BucketCursor<String> cursor = tree.cursor(prefix);
		while (cursor.next()) {
			final String key = cursor.value();
			if (!key.startsWith(prefix)) {
				break;
			}
			result.add(key);
		}
		return result;
	}

	/**
	 * Counts the dictionary keys starting with `prefix` without materializing them.
	 *
	 * @param tree   the dictionary
	 * @param prefix the prefix
	 * @return number of matching keys
	 */
	public static int prefixCount(@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String prefix) {
		int count = 0;
		final BucketCursor<String> cursor = tree.cursor(prefix);
		while (cursor.next()) {
			if (!cursor.value().startsWith(prefix)) {
				break;
			}
			count++;
		}
		return count;
	}

	/**
	 * Expands one query word into dictionary terms.
	 *
	 * @param tree       the dictionary of folded stems
	 * @param word       the query word
	 * @param thresholds the threshold function
	 * @param base       which length the thresholds read
	 * @param mode       how the word is treated as an unfinished last word, or {@link UnfinishedWordMode#FINISHED}
	 * @return the expanded terms, each once with its best exactness and distance, sorted best first and then by term
	 */
	@Nonnull
	public static List<ExpandedTerm> expand(
		@Nonnull TransactionalBucketBPlusTree<String> tree,
		@Nonnull QueryWord word,
		@Nonnull TypoThresholds thresholds,
		@Nonnull LengthBase base,
		@Nonnull UnfinishedWordMode mode
	) {
		final Map<String, ExpandedTerm> best = CollectionUtils.createHashMap(32);
		// exact: every hypothesis that is a dictionary key
		for (final String hypothesis : word.hypotheses()) {
			if (exact(tree, hypothesis)) {
				offer(best, new ExpandedTerm(hypothesis, Exactness.EXACT, 0));
			}
		}
		// prefix: the typed fragment of an unfinished word
		if (mode != UnfinishedWordMode.FINISHED) {
			for (final String term : prefix(tree, word.typed())) {
				offer(best, new ExpandedTerm(term, Exactness.PREFIX, 0));
			}
		}
		// fuzzy: every hypothesis within its budget; skipped for the prefix-only mode
		if (mode != UnfinishedWordMode.PREFIX_ONLY) {
			for (final String hypothesis : word.hypotheses()) {
				final int edits = maxEdits(thresholds, base, word.typed(), hypothesis);
				if (edits > 0) {
					final LevenshteinDictionaryWalker walker =
						new LevenshteinDictionaryWalker(hypothesis, edits, thresholds.frozenPrefix(hypothesis));
					for (final Hit hit : walker.walk(tree)) {
						final Exactness exactness = hit.distance() == 0 ? Exactness.EXACT : Exactness.FUZZY;
						offer(best, new ExpandedTerm(hit.term(), exactness, hit.distance()));
					}
				}
			}
		}
		// open-ended fuzzy over the typed fragment: mistyped and unfinished at once
		if (mode == UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO) {
			final int edits = thresholds.maxEdits(word.typed());
			if (edits > 0) {
				final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker(
					word.typed(), edits, thresholds.frozenPrefix(word.typed()), 0, true
				);
				for (final Hit hit : walker.walk(tree)) {
					final Exactness exactness = hit.distance() == 0 ? Exactness.PREFIX : Exactness.FUZZY;
					offer(best, new ExpandedTerm(hit.term(), exactness, hit.distance()));
				}
			}
		}
		final List<ExpandedTerm> result = new ArrayList<>(best.values());
		result.sort(
			(a, b) -> {
				final int byExactness = a.exactness().compareTo(b.exactness());
				if (byExactness != 0) {
					return byExactness;
				}
				final int byDistance = Integer.compare(a.distance(), b.distance());
				return byDistance != 0 ? byDistance : a.term().compareTo(b.term());
			}
		);
		return Collections.unmodifiableList(result);
	}

	/**
	 * Keeps the better of an already collected term and a newly found one: better exactness first, then smaller
	 * distance.
	 *
	 * @param best      collected terms by key
	 * @param candidate the newly found term
	 */
	private static void offer(@Nonnull Map<String, ExpandedTerm> best, @Nonnull ExpandedTerm candidate) {
		final ExpandedTerm current = best.get(candidate.term());
		if (current == null
			|| candidate.exactness().compareTo(current.exactness()) < 0
			|| (candidate.exactness() == current.exactness() && candidate.distance() < current.distance())) {
			best.put(candidate.term(), candidate);
		}
	}
}
