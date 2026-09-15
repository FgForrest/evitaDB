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

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The lexicon-scale verifier of the M7 correctness invariant, shared by the per-language
 * `*VariantStemmerLexiconTest`s: for **every headword of a Hunspell `.dic` file**, the folded image of the
 * index-side term must be contained in the variant set the production {@link VariantStemmer} emits for the
 * bare-typed word — exactly the term set the language's `*-search` built-in emits. A violating word is a
 * fold-ambiguity no fork covers, i.e. a bare-typed query that would silently fail to find its own document.
 *
 * This runs against the **production** stemmer rather than the flat `Folded*Stemmer` specification, so it is a
 * direct test of shipped logic; the flat ports stay covered through the `Branching*StemmerEquivalenceTest`s,
 * which pin them and the production walk to each other.
 *
 * This instrument exists because fixtures produce false positives: the Slovak matrix reported its folded port
 * as switch-free for three runs, and the first ~265k-word sweep found five fold-hazard classes the fixture
 * could not commit (see the SK/PL/RO measurement record, §9.4). A fixture can only falsify what it carries;
 * a lexicon carries everything the language writes down.
 *
 * **Scope.** A `.dic` file holds dictionary headwords (lemmas), not inflected forms — Lucene's Hunspell support
 * cannot expand affixes — so the sweep proves the *coverage invariant* over the full lexicon while the
 * recall/precision numbers of the matrices remain bound to their fixtures' inflected forms. Entries containing
 * non-letters (hyphenated compounds, abbreviations) are skipped and counted; the tokenizer would split them and
 * the per-word comparison would compare apples to token soup.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LexiconCoverageSweep {

	/**
	 * How many uncovered words are carried verbatim in the result — enough to see every class, few enough not
	 * to bury the report.
	 */
	private static final int UNCOVERED_EXAMPLE_LIMIT = 40;

	private LexiconCoverageSweep() {
	}

	/**
	 * Sweeps one language's lexicon.
	 *
	 * @param dictionaryResource classpath location of the Hunspell `.dic` file
	 * @param bareTyper          what a user typing without the language's letters enters for a word — the
	 *                           query side's input, and the space the index term is compared in
	 * @param indexTerm          the index side: the accented stemmer chain reduced to one word-to-term function,
	 *                           **including** the trailing fold
	 * @param queryStemmer       the query side: the language's production variant stemmer, reused across words
	 *                           (it is stateful scratch, so exactly one instance is used for the whole sweep)
	 * @return the sweep result
	 * @throws IOException when the dictionary cannot be read
	 */
	@Nonnull
	static Result sweep(
		@Nonnull String dictionaryResource,
		@Nonnull UnaryOperator<String> bareTyper,
		@Nonnull UnaryOperator<String> indexTerm,
		@Nonnull VariantStemmer queryStemmer
	) throws IOException {
		int tested = 0;
		int skipped = 0;
		int forkedWordCount = 0;
		int uncoveredCount = 0;
		final List<String> uncovered = new ArrayList<>(16);

		try (
			final InputStream stream = LexiconCoverageSweep.class.getResourceAsStream(dictionaryResource);
			final BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8)
			)
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
					skipped++;
					continue;
				}
				tested++;

				final String indexSideTerm = indexTerm.apply(word);
				final String bareTyped = bareTyper.apply(word);
				final Set<String> variantTerms = variants(bareTyped, queryStemmer);
				if (variantTerms.size() > 1) {
					forkedWordCount++;
				}
				if (!variantTerms.contains(indexSideTerm)) {
					uncoveredCount++;
					if (uncovered.size() < UNCOVERED_EXAMPLE_LIMIT) {
						uncovered.add(
							word + ": index term `" + indexSideTerm + "` not among variants " + variantTerms
						);
					}
				}
			}
		}
		return new Result(tested, skipped, forkedWordCount, uncoveredCount, uncovered);
	}

	/**
	 * Applies a buffer-in-place stemmer to a word — the adapter every char-array stemmer shares.
	 *
	 * @param word    word to stem
	 * @param stemmer stemmer to apply
	 * @return the stemmed word
	 */
	@Nonnull
	static String stem(@Nonnull String word, @Nonnull FoldedStemmer stemmer) {
		final char[] buffer = word.toCharArray();
		final int length = stemmer.stem(buffer, buffer.length);
		return new String(buffer, 0, length);
	}

	/**
	 * Materializes the whole variant set a production {@link VariantStemmer} emits for one folded word.
	 *
	 * @param word    folded, lowercased word
	 * @param stemmer the language's variant stemmer
	 * @return the distinct variants, in emission order
	 */
	@Nonnull
	static Set<String> variants(@Nonnull String word, @Nonnull VariantStemmer stemmer) {
		final char[] buffer = word.toCharArray();
		final int count = stemmer.stem(buffer, buffer.length);
		final Set<String> variants = new LinkedHashSet<>(count);
		final char[] scratch = new char[buffer.length];
		for (int i = 0; i < count; i++) {
			variants.add(new String(scratch, 0, stemmer.materialize(i, buffer, scratch)));
		}
		return variants;
	}

	/**
	 * Result of one sweep.
	 *
	 * @param tested          number of headwords tested
	 * @param skipped         number of non-letter entries skipped
	 * @param forkedWordCount number of words whose variant set held more than one term
	 * @param uncoveredCount  number of uncovered words in total
	 * @param uncovered       formatted examples of uncovered words, capped at {@link #UNCOVERED_EXAMPLE_LIMIT}
	 */
	record Result(
		int tested,
		int skipped,
		int forkedWordCount,
		int uncoveredCount,
		@Nonnull List<String> uncovered
	) {

		/**
		 * Renders the one-line summary of this sweep.
		 *
		 * @param lexiconName name the sweep is reported under
		 * @return summary line
		 */
		@Nonnull
		String summary(@Nonnull String lexiconName) {
			return String.format(
				"%s lexicon M7 coverage: %d words tested, %d skipped (non-letter entries), %d words with a "
					+ "genuine fork (%.2f %%), %d uncovered",
				lexiconName, this.tested, this.skipped, this.forkedWordCount,
				100.0 * this.forkedWordCount / this.tested,
				this.uncoveredCount
			);
		}

	}

}
