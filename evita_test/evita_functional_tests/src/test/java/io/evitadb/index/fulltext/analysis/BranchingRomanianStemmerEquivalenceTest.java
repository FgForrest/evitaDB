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

import java.io.IOException;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link RomanianVariantStemmer} set-equivalent to the flat 513-hypothesis
 * {@link FoldedRomanianStemmer#allHypotheses()} union — over the whole ro_RO Hunspell lexicon, over the
 * pipeline's boundary words, and per token position at the filter level. Romanian is the staged-worklist walk
 * (five step gates, buffer-rewriting actions, a constraint scan inside the verb step), so the boundary list
 * leans on the words the ro_RO sweep's correction rounds were fought over.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Branching Romanian stemmer — equivalence with the flat 513-hypothesis union")
@Tag(ENGINE)
@Tag(FULLTEXT)
class BranchingRomanianStemmerEquivalenceTest {

	/**
	 * Words on the pipeline's decision boundaries: the `tiune` fork, the `ş`/`ă`-spelled verb families and
	 * their double-consultation chains (`asesi` → `sesi`), the step gates' sweep exhibits, the combo repeat
	 * loop, the `ist` length-preserving rewrite, the intervocalic markers, and the region guards.
	 */
	private static final List<String> BOUNDARY_WORDS = List.of(
		// the tiune fork and its no-`t` neighbour
		"chestiune", "gravitatiune", "chestiuni", "xxiune",
		// ş/ă-spelled verb endings vs genuine plain-s nouns; the same-flag double consultation
		"veste", "vesti", "cumpara", "lucreaza", "citeasca", "xxasesi", "xxsesi", "mananci",
		// the step-gate sweep exhibits
		"barati", "zgaurati", "descalicator", "picator", "bogdanita", "borsei", "frecatei", "paraul",
		// combo repeat loop and the length-preserving ist rewrite
		"organism", "abilitate", "icivitate", "specificitate", "jurnalista", "artisti",
		// step 0 actions incl. the ab-guard of `ile`
		"copiii", "cartile", "abile", "frumoasele", "statiile", "omului", "corpul",
		// intervocalic markers and region guards
		"ziua", "noua", "oua", "ai", "ea", "ie", "a", ""
	);

	@Test
	@DisplayName("The branching walk equals the flat union for every folded ro_RO headword")
	void shouldMatchFlatUnionOverWholeLexicon() throws IOException {
		final int tested = BranchingEquivalenceSupport.assertEquivalenceOverLexicon(
			"/fulltext/hunspell/ro_RO.dic", FoldedRomanianStemmer.allHypotheses(),
			new RomanianVariantStemmer(), "FoldedRomanianStemmer"
		);
		assertTrue(tested > 100_000, "Expected six figures of headwords; got " + tested + ".");
		System.out.println("ro_RO branching-vs-flat equivalence: " + tested + " folded headwords compared");
	}

	@Test
	@DisplayName("The branching walk equals the flat union on the pipeline's boundary words")
	void shouldMatchFlatUnionOnBoundaryWords() {
		final List<FoldedStemmer> flatUnion = FoldedRomanianStemmer.allHypotheses();
		final RomanianVariantStemmer branching = new RomanianVariantStemmer();
		for (final String word : BOUNDARY_WORDS) {
			BranchingEquivalenceSupport.assertSameHypotheses(
				word, flatUnion, branching, "FoldedRomanianStemmer"
			);
		}
	}

	@Test
	@DisplayName("The branching filter emits the same terms per position as the flat filter")
	void shouldEmitSameTermsPerPositionAsFlatFilter() throws IOException {
		BranchingEquivalenceSupport.assertSameTermsPerPosition(
			FoldedRomanianStemmer.allHypotheses(), RomanianVariantStemmer::new,
			"Canapea neagră din piele pentru gravitaţiune şi chestiune; lucrează, citească, cumpăra - "
				+ "canapea neagra din piele pentru gravitatiune si chestiune; lucreaza, citeasca, cumpara"
		);
	}

}
