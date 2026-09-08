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

import io.evitadb.index.fulltext.analysis.CzechAnalysisFixture.MatchStrategy;
import io.evitadb.index.fulltext.analysis.CzechAnalysisFixture.Measurement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the properties of the **production** Czech chain — the one a schema actually gets from
 * {@link BuiltInAnalyzers} — against the vocabulary and metrics of {@link CzechAnalysisFixture}.
 *
 * The chain is `StandardTokenizer → LowerCaseFilter → StopFilter → CzechStemFilter → ASCIIFoldingFilter`:
 * folding is appended **after** the stemmer, because `CzechStemmer`'s ending tables are spelled with accented
 * characters and a stemmer fed folded text silently stops stemming.
 *
 * The consequence is the mirror image on the query side, and making it visible is what
 * {@link #shouldMatchAccentStrippedTyping()} is for: a query typed without accents never gets its ending
 * stripped, so it cannot meet the stem the value produced. **That test is expected to fail**, and failing is
 * its job — the limitation is pinned here rather than discovered in production, and its assertion message names
 * exactly which morphological classes are lost.
 *
 * Alternatives to this chain are not measured here. They live in {@link CzechAnalysisApproachMatrixTest}, which
 * compares every mechanism the prior-art survey proposed over the same vocabulary and the same metrics.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Czech accent-typed recall of the production chain")
@Tag(ENGINE)
@Tag(FULLTEXT)
class CzechAccentTypingTest {

	private static final String ENTITY_TYPE = "PRODUCT";
	private static final Locale CZECH = new Locale("cs", "CZ");

	private FulltextAnalyzerRegistry registry;
	private Measurement measurement;

	@BeforeEach
	void setUp() {
		this.registry = new FulltextAnalyzerRegistry();
		final FulltextAnalyzer productionChain = this.registry.getIndexAnalyzer(ENTITY_TYPE, CZECH);
		this.measurement = CzechAnalysisFixture.measure(
			"czech (production)", productionChain, productionChain, MatchStrategy.EXACT
		);
	}

	@AfterEach
	void tearDown() {
		this.registry.close();
	}

	@Test
	@DisplayName("Converges every inflected form of a lemma")
	void shouldConvergeInflectionForms() {
		assertEquals(
			List.of(), this.measurement.strictDivergentLemmas(),
			"Lemmas whose inflected forms produced no common term:\n" + this.measurement.detail(40)
		);
	}

	@Test
	@DisplayName("Finds every accented form when it is typed without accents")
	void shouldMatchAccentStrippedTyping() {
		assertEquals(
			List.of(), this.measurement.accentTypingMisses(),
			"Forms unreachable when typed without diacritics:\n" + this.measurement.detail(40)
		);
	}

	@Test
	@DisplayName("Merges no two unrelated lemmas onto one term")
	void shouldNotMergeUnrelatedLemmas() {
		// the counterweight to the two tests above: any chain can be made to score perfectly on recall by
		// collapsing the whole vocabulary onto one term, so precision has to be pinned alongside recall
		assertEquals(
			List.of(), this.measurement.falseMerges(),
			"Unrelated lemmas that collapsed onto a shared term:\n" + this.measurement.detail(40)
		);
	}

}
