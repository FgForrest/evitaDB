/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _  *             |  __/\ V /| | || (_| | |_| | |_) |
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
import java.util.function.UnaryOperator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Polish instance of the {@link LexiconCoverageSweep}: verifies the M7 correctness invariant of the
 * {@link FoldedPolishStemmer} hypothesis set against the whole `pl_PL` Hunspell lexicon.
 *
 * The index side is the P0s shape of {@link PolishAnalysisApproachMatrixTest} — the official Snowball Polish
 * stemmer ({@link PolishSnowballStemmer}) followed by folding. The query side forks all four fold-ambiguous
 * ending groups of the port (`ł`, nasal, soft, `ów`) — sixteen hypotheses, exactly what the P20 chain
 * emits. Both sides fold through {@link PolishAnalysisFixture#bareType(String)}, because Polish folding must
 * map the stroked `ł` NFD leaves alone.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Polish folded stemmer — M7 coverage over the whole pl_PL lexicon")
@Tag(ENGINE)
@Tag(FULLTEXT)
class PolishFoldedStemmerLexiconTest {

	@Test
	@DisplayName("The hypothesis set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final PolishSnowballStemmer accented = new PolishSnowballStemmer();
		final List<UnaryOperator<String>> hypotheses = new ArrayList<>(65);
		for (final FoldedStemmer stemmer : FoldedPolishStemmer.allHypotheses()) {
			hypotheses.add(word -> LexiconCoverageSweep.stem(word, stemmer));
		}

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/pl_PL.dic",
			PolishAnalysisFixture::bareType,
			word -> {
				accented.setCurrent(word);
				accented.stem();
				return PolishAnalysisFixture.bareType(accented.getCurrent());
			},
			hypotheses
		);

		System.out.println(result.summary("pl_PL"));
		assertTrue(
			result.tested() > 100_000,
			"The lexicon is supposed to provide six figures of test words; got " + result.tested() + "."
		);
		assertTrue(
			result.uncoveredCount() == 0,
			"Every uncovered word is a fold-ambiguity no current fork covers - the folded port needs a new "
				+ "switch for it:\n" + String.join("\n", result.uncovered())
		);
	}

}
