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

package io.evitadb.index.fulltext.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link BranchingFoldedSlovakStemmer} set-equivalent to the flat eight-configuration
 * {@link FoldedSlovakStemmer} union — over the whole sk_SK Hunspell lexicon, over the ending-table boundary
 * words, and per token position at the filter level. The Slovak set carries **no** surface hypothesis, which
 * this equivalence also pins. See {@code BranchingCzechStemmerEquivalenceTest} for why the flat union is the
 * executable specification here.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Branching Slovak stemmer — equivalence with the flat 8-configuration union")
@Tag(ENGINE)
@Tag(FULLTEXT)
class BranchingSlovakStemmerEquivalenceTest {

	/**
	 * Words on the table's guard boundaries: the `om` fork around its length guard, the `ie`-shortening and
	 * epenthesis chain in all four switch combinations (`stoliciek`), each pattern alone, the `c`→`k`
	 * terminal, the `i`-trim, and the fixture words the measurement record discusses.
	 */
	private static final List<String> BOUNDARY_WORDS = List.of(
		"om", "xxom", "xxxom", "adenom", "agronom", "chrom", "trickom",
		"stoliciek", "kosiel", "hodiniek", "barier", "bariera", "atelier", "premiera",
		"bibliotek", "hypotek", "darcek", "naramok", "rok", "vlk", "xxxek", "xxxxek", "xxxiek",
		"ulic", "ulici", "xxxi", "xxxci", "stol", "xxxxov", "xxxov", "iach", "xxiach", "xxxiach",
		"ovia", "xxovia", "a", "e", ""
	);

	/**
	 * The flat union — every switch combination, exactly the list the Slovak M7 chain and lexicon sweep run.
	 */
	@Nonnull
	private static List<FoldedSlovakStemmer> flatUnion() {
		final List<FoldedSlovakStemmer> stemmers = new ArrayList<>(8);
		for (final boolean omEnding : new boolean[]{true, false}) {
			for (final boolean ieShortening : new boolean[]{true, false}) {
				for (final boolean epenthesis : new boolean[]{true, false}) {
					stemmers.add(new FoldedSlovakStemmer(omEnding, ieShortening, epenthesis));
				}
			}
		}
		return stemmers;
	}

	@Test
	@DisplayName("The branching walk equals the flat union for every folded sk_SK headword")
	void shouldMatchFlatUnionOverWholeLexicon() throws IOException {
		final int tested = BranchingEquivalenceSupport.assertEquivalenceOverLexicon(
			"/fulltext/hunspell/sk_SK.dic", flatUnion(), new BranchingFoldedSlovakStemmer(),
			"FoldedSlovakStemmer"
		);
		assertTrue(tested > 100_000, "Expected six figures of headwords; got " + tested + ".");
		System.out.println("sk_SK branching-vs-flat equivalence: " + tested + " folded headwords compared");
	}

	@Test
	@DisplayName("The branching walk equals the flat union on the ending-table boundary words")
	void shouldMatchFlatUnionOnBoundaryWords() {
		final List<FoldedSlovakStemmer> flatUnion = flatUnion();
		final BranchingFoldedSlovakStemmer branching = new BranchingFoldedSlovakStemmer();
		for (final String word : BOUNDARY_WORDS) {
			BranchingEquivalenceSupport.assertSameHypotheses(word, flatUnion, branching, "FoldedSlovakStemmer");
		}
	}

	@Test
	@DisplayName("The branching filter emits the same terms per position as the flat filter")
	void shouldEmitSameTermsPerPositionAsFlatFilter() throws IOException {
		BranchingEquivalenceSupport.assertSameTermsPerPosition(
			flatUnion(), BranchingFoldedSlovakStemmer::new,
			"Stolička so stoličiek, tričkom a darček; adenóm, bibliotéka, hypoték, bariéra, košieľ - "
				+ "stolicka so stoliciek, trickom a darcek; adenom, biblioteka, hypotek, bariera, kosiel"
		);
	}

}
