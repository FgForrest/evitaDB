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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that {@link BranchingFoldedCzechStemmer} produces **exactly** the hypothesis set of the flat union —
 * {@link FoldedCzechStemmer#allHypotheses()}, 1,024 switch configurations plus the folded surface — word by
 * word over the whole `cs_CZ` Hunspell lexicon, over hand-picked ending-table boundary words, and at the
 * filter level per token position. This is the equivalence the flat prototype's javadoc asserted structurally
 * ("the union of configurations is one branching stemmer"); the branching implementation turns the assertion
 * into something a test can falsify, and this test is that falsifier.
 *
 * The equivalence is what licenses the JMH benchmark ({@code CzechAnalysisPipelineBenchmark} in the
 * performance-tests module) to attribute its measured speedup to implementation alone: same set in, same set
 * out, only the number of stemmer runs and allocations differs.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Branching Czech stemmer — equivalence with the flat 1,025-configuration union")
@Tag(ENGINE)
@Tag(FULLTEXT)
class BranchingCzechStemmerEquivalenceTest {

	/**
	 * Words that sit on the ending-table's guard boundaries: every guarded entry at, just below and just above
	 * its length guard, every `normalize` pattern with and without a competing pattern, and the fixture words
	 * the measurement records discuss.
	 */
	private static final List<String> BOUNDARY_WORDS = List.of(
		// the `atech`/`atum`/`ata`/`aty`/`at` family across its length guards
		"atech", "xatech", "xxatech", "xxxatech", "atum", "xatum", "xxatum", "ata", "xxata", "xxxata",
		"aty", "xxaty", "xxxaty", "at", "xxat", "xxxat",
		// the `emi`/`imi`/`ymi` family and its two-letter `mi` fallback
		"emi", "xxemi", "xxxemi", "xximi", "xxximi", "xxymi", "xxxymi", "xxxmi",
		// unguarded strips that terminate a fork's continuation
		"xxxetem", "xxxxech", "xxxxum",
		// possessives at and around their length guard
		"ov", "xxxov", "xxxxov", "xxxxin", "xxxxuv", "xxxxxov",
		// every normalize pattern, alone and in combination
		"ct", "st", "xxct", "xxst", "xxxc", "xxxz", "xxec", "xxxec", "xxez", "xxxuc", "xxxuz",
		"xxeu", "xxue", "xxxe", "xxxu", "e", "u", "c", "z", "a", "",
		// the measurement records' example words, folded and bare-typed
		"panskych", "pansti", "formaty", "dreveny", "sluchatek", "rajcata", "otec", "stul",
		"cerna", "kozena", "sedacka", "cesta", "cesky", "detsti", "balerina", "docouvat", "surimi"
	);

	@Test
	@DisplayName("The branching walk equals the flat union for every folded cs_CZ headword")
	void shouldMatchFlatUnionOverWholeLexicon() throws IOException {
		final List<FoldedStemmer> flatUnion = FoldedCzechStemmer.allHypotheses();
		final BranchingFoldedCzechStemmer branching = new BranchingFoldedCzechStemmer();
		int tested = 0;

		try (
			final InputStream stream =
				BranchingCzechStemmerEquivalenceTest.class.getResourceAsStream("/fulltext/hunspell/cs_CZ.dic");
			final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
		) {
			// the first line of a .dic file is the entry count
			reader.readLine();
			String line;
			while ((line = reader.readLine()) != null) {
				final int flagsSeparator = line.indexOf('/');
				final String word = (flagsSeparator < 0 ? line : line.substring(0, flagsSeparator))
					.trim()
					.toLowerCase(Locale.ROOT);
				if (word.isEmpty() || !word.chars().allMatch(Character::isLetter)) {
					continue;
				}
				assertSameHypotheses(fold(word), flatUnion, branching);
				tested++;
			}
		}
		System.out.println("cs_CZ branching-vs-flat equivalence: " + tested + " folded headwords compared");
	}

	@Test
	@DisplayName("The branching walk equals the flat union on the ending-table boundary words")
	void shouldMatchFlatUnionOnBoundaryWords() {
		final List<FoldedStemmer> flatUnion = FoldedCzechStemmer.allHypotheses();
		final BranchingFoldedCzechStemmer branching = new BranchingFoldedCzechStemmer();
		for (final String word : BOUNDARY_WORDS) {
			assertSameHypotheses(word, flatUnion, branching);
		}
	}

	@Test
	@DisplayName("The branching filter emits the same terms per position as the flat filter")
	void shouldEmitSameTermsPerPositionAsFlatFilter() throws IOException {
		final String text = "Černá kožená sedačka, dřevěný stůl, sluchátka a rajčata pro otce; "
			+ "cerna kozena sedacka, dreveny stul, sluchatka a rajcata pro otce - formaty pansti panskych";
		try (
			final Analyzer flat = hypothesisAnalyzer(true);
			final Analyzer branching = hypothesisAnalyzer(false)
		) {
			assertEquals(
				termsPerPosition(flat, text),
				termsPerPosition(branching, text),
				"Both filters must emit the same term set at every token position."
			);
		}
	}

	/**
	 * Asserts the branching walk and the flat union produce the same hypothesis set for one folded word.
	 *
	 * @param word      folded, lowercased word
	 * @param flatUnion all flat-union stemmer configurations, surface hypothesis included
	 * @param branching the branching stemmer, reused across words
	 */
	private static void assertSameHypotheses(
		@Nonnull String word,
		@Nonnull List<FoldedStemmer> flatUnion,
		@Nonnull BranchingFoldedCzechStemmer branching
	) {
		final Set<String> flatSet = new LinkedHashSet<>(4);
		for (final FoldedStemmer stemmer : flatUnion) {
			flatSet.add(LexiconCoverageSweep.stem(word, stemmer));
		}

		final char[] buffer = word.toCharArray();
		final int hypothesisCount = branching.hypothesize(buffer, buffer.length);
		final Set<String> branchingSet = new LinkedHashSet<>(4);
		final char[] scratch = new char[buffer.length];
		for (int i = 0; i < hypothesisCount; i++) {
			branchingSet.add(new String(scratch, 0, branching.materialize(i, buffer, scratch)));
		}

		assertEquals(
			flatSet, branchingSet,
			"Hypothesis sets diverge for `" + word + "` - the branching walk no longer mirrors "
				+ "FoldedCzechStemmer."
		);
	}

	/**
	 * Builds the minimal M7 query chain — tokenize, lowercase, fold, hypothesize — with one of the two
	 * hypothesis filter implementations at its end.
	 *
	 * @param flatUnion `true` for {@link HypothesisStemFilter} over the flat union, `false` for
	 *                  {@link BranchingHypothesisStemFilter}
	 * @return the chain
	 */
	@Nonnull
	private static Analyzer hypothesisAnalyzer(boolean flatUnion) {
		final List<FoldedStemmer> stemmers = flatUnion ? FoldedCzechStemmer.allHypotheses() : List.of();
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream folded = new ASCIIFoldingFilter(new LowerCaseFilter(source));
				return new TokenStreamComponents(
					source,
					flatUnion
						? new HypothesisStemFilter(folded, stemmers)
						: new BranchingHypothesisStemFilter(folded)
				);
			}
		};
	}

	/**
	 * Runs the analyzer over the text and collects the emitted terms grouped by token position — hypotheses of
	 * one token share a position via zero position increments, and only the per-position *set* is contractual,
	 * not the emission order.
	 *
	 * @param analyzer chain to run
	 * @param text     text to analyze
	 * @return one term set per token position, in position order
	 * @throws IOException when the stream fails
	 */
	@Nonnull
	private static List<Set<String>> termsPerPosition(
		@Nonnull Analyzer analyzer,
		@Nonnull String text
	) throws IOException {
		final List<Set<String>> positions = new ArrayList<>(32);
		try (final TokenStream stream = analyzer.tokenStream("text", text)) {
			final CharTermAttribute term = stream.getAttribute(CharTermAttribute.class);
			final PositionIncrementAttribute positionIncrement =
				stream.getAttribute(PositionIncrementAttribute.class);
			stream.reset();
			while (stream.incrementToken()) {
				if (positionIncrement.getPositionIncrement() > 0 || positions.isEmpty()) {
					positions.add(new LinkedHashSet<>(4));
				}
				positions.get(positions.size() - 1).add(term.toString());
			}
			stream.end();
		}
		return positions;
	}

	/**
	 * Folds diacritics the way the production `ASCIIFoldingFilter` does for the Czech alphabet — NFD
	 * decomposition covers every Czech letter.
	 *
	 * @param text text to fold
	 * @return folded text
	 */
	@Nonnull
	private static String fold(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

}
