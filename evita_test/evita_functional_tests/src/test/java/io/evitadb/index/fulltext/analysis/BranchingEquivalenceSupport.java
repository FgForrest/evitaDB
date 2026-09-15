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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared machinery of the per-language `Branching*StemmerEquivalenceTest`s: proves that a language's
 * {@link VariantStemmer} produces **exactly** the hypothesis set of its flat {@link FoldedStemmer} union —
 * word by word over the language's whole Hunspell lexicon, over hand-picked boundary words, and per token
 * position at the filter level. The flat union acts as the executable specification; any divergence fails with
 * the offending word in the message. The Czech test predates this class and carries its own copy of the same
 * machinery.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class BranchingEquivalenceSupport {

	private BranchingEquivalenceSupport() {
	}

	/**
	 * Asserts the branching walk and the flat union produce the same hypothesis set for one folded word.
	 *
	 * @param word      folded, lowercased word
	 * @param flatUnion the language's flat configuration union — the specification
	 * @param branching the language's branching stemmer, reused across words
	 * @param stemmerName name of the flat stemmer, for the failure message
	 */
	static void assertSameHypotheses(
		@Nonnull String word,
		@Nonnull List<? extends FoldedStemmer> flatUnion,
		@Nonnull VariantStemmer branching,
		@Nonnull String stemmerName
	) {
		final Set<String> flatSet = new LinkedHashSet<>(4);
		for (final FoldedStemmer stemmer : flatUnion) {
			flatSet.add(LexiconCoverageSweep.stem(word, stemmer));
		}

		final char[] buffer = word.toCharArray();
		final int hypothesisCount = branching.stem(buffer, buffer.length);
		final Set<String> branchingSet = new LinkedHashSet<>(4);
		final char[] scratch = new char[buffer.length];
		for (int i = 0; i < hypothesisCount; i++) {
			branchingSet.add(new String(scratch, 0, branching.materialize(i, buffer, scratch)));
		}

		assertEquals(
			flatSet, branchingSet,
			"Hypothesis sets diverge for `" + word + "` - the branching walk no longer mirrors " + stemmerName
				+ "."
		);
	}

	/**
	 * Asserts branching-vs-flat set equality for every folded headword of a Hunspell lexicon.
	 *
	 * @param dictionaryResource classpath location of the `.dic` file
	 * @param flatUnion          the language's flat configuration union
	 * @param branching          the language's branching stemmer
	 * @param stemmerName        name of the flat stemmer, for the failure message
	 * @return number of headwords compared
	 * @throws IOException when the dictionary cannot be read
	 */
	static int assertEquivalenceOverLexicon(
		@Nonnull String dictionaryResource,
		@Nonnull List<? extends FoldedStemmer> flatUnion,
		@Nonnull VariantStemmer branching,
		@Nonnull String stemmerName
	) throws IOException {
		int tested = 0;
		try (
			final InputStream stream =
				BranchingEquivalenceSupport.class.getResourceAsStream(dictionaryResource);
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
				assertSameHypotheses(fold(word), flatUnion, branching, stemmerName);
				tested++;
			}
		}
		return tested;
	}

	/**
	 * Asserts that the flat and the branching filter emit the same term set at every token position of the
	 * given text, both run through the minimal M7 query chain (tokenize, lowercase, fold, hypothesize).
	 *
	 * @param flatUnion the language's flat configuration union
	 * @param branching supplier of fresh branching stemmers — the chain may build components more than once
	 * @param text      text to analyze
	 * @throws IOException when a stream fails
	 */
	static void assertSameTermsPerPosition(
		@Nonnull List<? extends FoldedStemmer> flatUnion,
		@Nonnull Supplier<? extends VariantStemmer> branching,
		@Nonnull String text
	) throws IOException {
		try (
			final Analyzer flat = hypothesisAnalyzer(flatUnion, null);
			final Analyzer branchingChain = hypothesisAnalyzer(null, branching)
		) {
			assertEquals(
				termsPerPosition(flat, text),
				termsPerPosition(branchingChain, text),
				"Both filters must emit the same term set at every token position."
			);
		}
	}

	/**
	 * Folds diacritics the way the production `ASCIIFoldingFilter` does: NFD decomposition plus the stroked
	 * `ł`, which NFD alone cannot reach — Polish needs it, the other languages are unaffected.
	 *
	 * @param text text to fold
	 * @return folded text
	 */
	@Nonnull
	static String fold(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.replace('ł', 'l')
			.replace('Ł', 'L');
	}

	/**
	 * Builds the minimal M7 query chain with one of the two hypothesis filter implementations at its end.
	 *
	 * @param flatUnion the flat union, or `null` to build the branching chain
	 * @param branching the branching stemmer supplier, or `null` to build the flat chain
	 * @return the chain
	 */
	@Nonnull
	private static Analyzer hypothesisAnalyzer(
		final List<? extends FoldedStemmer> flatUnion,
		final Supplier<? extends VariantStemmer> branching
	) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream folded = new ASCIIFoldingFilter(new LowerCaseFilter(source));
				return new TokenStreamComponents(
					source,
					flatUnion != null
						? new HypothesisStemFilter(folded, flatUnion)
						: new VariantStemFilter(folded, branching.get())
				);
			}
		};
	}

	/**
	 * Runs the analyzer over the text and collects the emitted terms grouped by token position — only the
	 * per-position *set* is contractual, not the emission order.
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

}
