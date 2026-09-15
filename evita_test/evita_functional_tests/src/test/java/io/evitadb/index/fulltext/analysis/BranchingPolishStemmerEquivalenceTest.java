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
 * Proves {@link PolishVariantStemmer} set-equivalent to the flat 129-hypothesis
 * {@link FoldedPolishStemmer#allHypotheses()} union — over the whole pl_PL Hunspell lexicon, over the
 * table's boundary words, and per token position at the filter level. Polish is the constraint-scan walk
 * (switches gate table *entries*, not code branches), so the boundary list leans on the multi-reading strings
 * (`sza`/`sze`, `ie`/`acie`/`ecie`/`cie`, `ales`) whose variants partition the configuration space.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Branching Polish stemmer — equivalence with the flat 129-hypothesis union")
@Tag(ENGINE)
@Tag(FULLTEXT)
class BranchingPolishStemmerEquivalenceTest {

	/**
	 * Words on the merged table's decision boundaries: the three-way `sza`/`sze` readings, the
	 * nasal-homograph strings, the `ł`- and `ś`/`ć`-spelled families against their plain twins, `ów`, the
	 * action-5 second layer, the R1 gate and the two-character floor, and the `by`-particle pre-pass.
	 */
	private static final List<String> BOUNDARY_WORDS = List.of(
		// three-way sza/sze readings and the nasal-homograph family
		"pasza", "kasze", "lepsza", "lepsze", "arabie", "ziemie", "okecie", "kopcie", "dziecie",
		"bladnales", "xxales", "xxiales",
		// folded ł past tenses vs genuine -al/-il words
		"metal", "spal", "spala", "spalo", "bralyscie", "robilismy",
		// nasal and soft endings vs genuine -ac/-iec/-ic words
		"palac", "kupiec", "splacic", "wlasc", "bojac", "grajac",
		// genitive-plural ów
		"krakow", "sklepow", "xxow",
		// action 5 + the second layer (ego/ych/ymi -> sz/iejsz/ac)
		"najlepszego", "najlepszych", "lepszymi", "gorajacego", "bolszego",
		// R1 gate and the two-character floor
		"to", "ta", "te", "oda", "idea", "ie", "cie", "e", "a", "",
		// the by-particle pre-pass
		"chcialbym", "robilibyscie", "abysmy"
	);

	@Test
	@DisplayName("The branching walk equals the flat union for every folded pl_PL headword")
	void shouldMatchFlatUnionOverWholeLexicon() throws IOException {
		final int tested = BranchingEquivalenceSupport.assertEquivalenceOverLexicon(
			"/fulltext/hunspell/pl_PL.dic", FoldedPolishStemmer.allHypotheses(),
			new PolishVariantStemmer(), "FoldedPolishStemmer"
		);
		assertTrue(tested > 100_000, "Expected six figures of headwords; got " + tested + ".");
		System.out.println("pl_PL branching-vs-flat equivalence: " + tested + " folded headwords compared");
	}

	@Test
	@DisplayName("The branching walk equals the flat union on the table's boundary words")
	void shouldMatchFlatUnionOnBoundaryWords() {
		final List<FoldedStemmer> flatUnion = FoldedPolishStemmer.allHypotheses();
		final PolishVariantStemmer branching = new PolishVariantStemmer();
		for (final String word : BOUNDARY_WORDS) {
			BranchingEquivalenceSupport.assertSameHypotheses(word, flatUnion, branching, "FoldedPolishStemmer");
		}
	}

	@Test
	@DisplayName("The branching filter emits the same terms per position as the flat filter")
	void shouldEmitSameTermsPerPositionAsFlatFilter() throws IOException {
		BranchingEquivalenceSupport.assertSameTermsPerPosition(
			FoldedPolishStemmer.allHypotheses(), PolishVariantStemmer::new,
			"Czarna skórzana sofa do każdego pałacu; paszą, kaszę i ziemię kupiec przyniósł - "
				+ "czarna skorzana sofa do kazdego palacu; pasza, kasze i ziemie kupiec przyniosl"
		);
	}

}
