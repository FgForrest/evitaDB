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
import io.evitadb.test.fulltext.TypoExpansion;
import io.evitadb.test.fulltext.TypoExpansion.ExpandedTerm;
import io.evitadb.test.fulltext.TypoExpansion.LengthBase;
import io.evitadb.test.fulltext.TypoExpansion.QueryWord;
import io.evitadb.test.fulltext.TypoExpansion.UnfinishedWordMode;
import io.evitadb.test.fulltext.TypoThresholds;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The **behaviour examples** of the typo-tolerance analysis (`typo-tolerance-fulltext-dictionary.md` §9, step 1):
 * concrete queries over a small product catalog with the expected matches, the expected order and the expected
 * non-matches, so that thresholds, opt-outs and the stemming answer are checked against something.
 *
 * The engine has no fulltext query yet, so the examples run against a **reference** of the proposal: the catalog
 * is indexed through the production `czech` chain, the query through the production `czech-search` chain, every
 * query word is expanded by {@link TypoExpansion} with the thresholds of §7.1, and the result is ranked by the
 * proposed lanes of §8.2 — every query word must match, then fewer typos, then better exactness (exact before
 * prefix before fuzzy), then primary key for determinism. The impact lane is left out: the catalog has no field
 * weights and one field. A failing example means the *proposal* misbehaves, not the engine.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Typo tolerance behaviour examples over a product catalog")
@Tag(ENGINE)
@Tag(FULLTEXT)
class TypoToleranceBehaviourTest {
	/**
	 * The catalog: product titles by primary key.
	 */
	private static final String[] CATALOG = {
		null,
		"Černé pánské kalhoty",          // 1
		"Modré dámské kalhoty",          // 2
		"Pánská zimní bunda",            // 3
		"Dámská bunda s kapucí",         // 4
		"Dětská mikina",                 // 5
		"Pánská mikina s kapucí",        // 6
		"Kožený pásek",                  // 7
		"Pletená čepice",                // 8
		"Vlněný svetr",                  // 9
		"Dámský vlněný kabát",           // 10
		"Kabátek pro panenky",           // 11
		"Nabíječka AB1284",              // 12
		"Nabíječka AB1234",              // 13
		"Plyšový pes",                   // 14
		"Pás na nářadí",                 // 15
		"Černá kožená kabelka",          // 16
		"Bílé tričko",                   // 17
		"Triko s dlouhým rukávem"        // 18
	};
	private static FulltextAnalyzerRegistry registry;
	private static FulltextAnalyzer searchAnalyzer;
	/**
	 * Indexed terms of every product.
	 */
	private static List<Set<String>> documents;
	/**
	 * The catalog's term dictionary.
	 */
	private static TransactionalBucketBPlusTree<String> dictionary;

	@BeforeAll
	static void indexCatalog() {
		registry = new FulltextAnalyzerRegistry();
		final Locale czech = Locale.forLanguageTag("cs");
		searchAnalyzer = registry.getSearchAnalyzer("PRODUCT", czech);
		final FulltextAnalyzer indexAnalyzer = registry.getIndexAnalyzer("PRODUCT", czech);
		documents = new ArrayList<>(CATALOG.length);
		final Set<String> allTerms = new TreeSet<>();
		for (final String title : CATALOG) {
			final Set<String> terms = new TreeSet<>();
			if (title != null) {
				indexAnalyzer.analyze(title, (term, surface, start, end, increment) -> terms.add(term));
			}
			documents.add(terms);
			allTerms.addAll(terms);
		}
		dictionary = new TransactionalBucketBPlusTree<>(63, String.class);
		int pk = 1;
		for (final String term : allTerms) {
			dictionary.addRecord(term, pk++);
		}
	}

	@AfterAll
	static void closeAnalyzers() {
		registry.close();
	}

	/**
	 * One behaviour example.
	 *
	 * @param description what the example pins
	 * @param query       the query text
	 * @param typing      whether the last word is still being typed (search-as-you-type)
	 * @param expected    the expected result, in order
	 * @param mustNot     products that must not be in the result
	 */
	record Example(
		@Nonnull String description,
		@Nonnull String query,
		boolean typing,
		@Nonnull List<Integer> expected,
		@Nonnull List<Integer> mustNot
	) {
		@Override
		public String toString() {
			return this.description + " — `" + this.query + "`" + (this.typing ? " (typing)" : "");
		}
	}

	@Nonnull
	static Stream<Example> examples() {
		return Stream.of(
			new Example("U3: typing without diacritics is not a typo",
				"cerne kalhoty", false, List.of(1), List.of(2, 16)),
			new Example("U3: wrong diacritics are not a typo either",
				"čérne kalhóty", false, List.of(1), List.of(2)),
			new Example("U1: one substitution in an ordinary word",
				"kalhoti", false, List.of(1, 2), List.of()),
			new Example("U2: an adjacent transposition costs one edit",
				"kalhtoy", false, List.of(1, 2), List.of()),
			new Example("U2: a transposition inside the stem",
				"bnuda", false, List.of(3, 4), List.of()),
			new Example("U3 + U2: missing diacritics and a typo together",
				"cerne kalhtoy", false, List.of(1), List.of(2)),
			new Example("U4: a three-letter word gets no typo",
				"pes", false, List.of(14), List.of(15, 7)),
			new Example("U5: a typo in the first letter is not tolerated",
				"vunda", false, List.of(), List.of(3, 4)),
			new Example("§7.5: a word containing a digit matches exactly",
				"ab1234", false, List.of(13), List.of(12)),
			new Example("U7: an exact match ranks above a typo match",
				"triko", false, List.of(18, 17), List.of()),
			new Example("must-match: every query word has to match",
				"panska mikina", false, List.of(6), List.of(5, 3)),
			// the typo expansion of the unfinished word also admits `pásek` (stem `pask`, one deletion from `pansk`);
			// it is ranked below every prefix match, which is the lane order working as proposed
			new Example("search-as-you-type: prefix matches first, a one-edit neighbour trails",
				"pansk", true, List.of(1, 3, 6, 7), List.of(2, 4)),
			new Example("U7: while typing, exact ranks above prefix",
				"kabat", true, List.of(10, 11), List.of()),
			new Example("U6: an unfinished word with a typo is still found while typing",
				"mikna", true, List.of(5, 6), List.of())
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("examples")
	@DisplayName("The proposal returns the expected products in the expected order")
	void shouldBehaveAsSpecified(@Nonnull Example example) {
		final List<Integer> result = search(example.query(), example.typing());
		for (final Integer forbidden : example.mustNot()) {
			assertTrue(
				!result.contains(forbidden),
				"product " + forbidden + " (`" + CATALOG[forbidden] + "`) must not match; result " + describe(result)
			);
		}
		assertEquals(example.expected(), result, "result " + describe(result));
	}

	/**
	 * Runs a query through the reference pipeline.
	 *
	 * @param query  the query text
	 * @param typing whether the last word is unfinished
	 * @return matching products, best first
	 */
	@Nonnull
	private static List<Integer> search(@Nonnull String query, boolean typing) {
		final List<QueryWord> words = TypoExpansion.analyze(searchAnalyzer, query);
		final List<Map<String, ExpandedTerm>> expansions = new ArrayList<>(words.size());
		for (int i = 0; i < words.size(); i++) {
			final UnfinishedWordMode mode = typing && i == words.size() - 1
				? UnfinishedWordMode.PREFIX_AND_OPEN_END_TYPO : UnfinishedWordMode.FINISHED;
			final List<ExpandedTerm> terms =
				TypoExpansion.expand(dictionary, words.get(i), TypoThresholds.PROPOSAL, LengthBase.TYPED, mode);
			final Map<String, ExpandedTerm> byTerm = CollectionUtils.createHashMap(terms.size());
			for (final ExpandedTerm term : terms) {
				byTerm.put(term.term(), term);
			}
			expansions.add(byTerm);
		}
		final List<long[]> ranked = new ArrayList<>(8);
		for (int pk = 1; pk < documents.size(); pk++) {
			int typos = 0;
			int exactness = 0;
			boolean allMatched = !expansions.isEmpty();
			for (final Map<String, ExpandedTerm> expansion : expansions) {
				// the query word counts once, with the best expansion the document contains (P1 §5.3)
				ExpandedTerm best = null;
				for (final String term : documents.get(pk)) {
					final ExpandedTerm candidate = expansion.get(term);
					if (candidate != null && (best == null || isBetter(candidate, best))) {
						best = candidate;
					}
				}
				if (best == null) {
					allMatched = false;
					break;
				}
				typos += best.distance();
				exactness += best.exactness().ordinal();
			}
			if (allMatched) {
				ranked.add(new long[]{typos, exactness, pk});
			}
		}
		ranked.sort((a, b) -> {
			for (int i = 0; i < a.length; i++) {
				final int byLane = Long.compare(a[i], b[i]);
				if (byLane != 0) {
					return byLane;
				}
			}
			return 0;
		});
		final List<Integer> result = new ArrayList<>(ranked.size());
		for (final long[] row : ranked) {
			result.add((int) row[2]);
		}
		return result;
	}

	/**
	 * Fewer typos first, then better exactness — the order of the TYPO and EXACTNESS lanes.
	 */
	private static boolean isBetter(@Nonnull ExpandedTerm candidate, @Nonnull ExpandedTerm current) {
		if (candidate.distance() != current.distance()) {
			return candidate.distance() < current.distance();
		}
		return candidate.exactness().compareTo(current.exactness()) < 0;
	}

	@Nonnull
	private static String describe(@Nonnull List<Integer> result) {
		final List<String> titles = new ArrayList<>(result.size());
		for (final Integer pk : result) {
			titles.add(pk + ":" + CATALOG[pk] + " " + documents.get(pk));
		}
		return titles.toString();
	}
}
