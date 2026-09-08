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
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import javax.annotation.Nonnull;

/**
 * Appends diacritics folding to the **end** of a wrapped analysis chain, i.e. after its stemmer.
 *
 * **Why fold at all.** With diacritics kept, a user typing `cerna` is two edits away from the stored `černá`
 * (`e`↔`ě`, `a`↔`á`). Two is the entire budget Lucene's Levenshtein automaton can express
 * (`MAXIMUM_SUPPORTED_DISTANCE = 2`), so a keyboard without Czech accents consumes the whole typo tolerance
 * before a single real typo is accounted for, and a longer word carrying three accents becomes unreachable.
 * Typing without accents is the norm in a Czech e-shop rather than an edge case, so the folding is on by
 * default for Czech and Slovak. German deliberately does **not** get it — `GermanAnalyzer` already runs
 * `GermanNormalizationFilter`, which folds umlauts and maps `ß` to `ss` its own way, and a second folding pass
 * would fight it.
 *
 * **Why after the stemmer, never before.** Folding first would hand the stemmer text without diacritics, which
 * drops it into exactly the same silent degradation as feeding it NFD (see
 * {@link FulltextAnalyzer#analyze(String, AnalyzedTermConsumer)}) — a Czech stemmer switching on `'á'` never
 * matches an `'a'`. Folding last keeps the term dictionary and the edit-distance metric in the same space: the
 * dictionary holds folded stems, distances are measured over folded stems, and the query text travels through
 * the identical chain.
 *
 * The cost is that the distinction is gone for good: a folded term cannot be used to rank an accented exact
 * match above an unaccented one. Preserving that would mean carrying the surface form as a second key, which is
 * why {@link AnalyzedTerm#surfaceForm()} is captured before the chain folds anything.
 *
 * **Known gap.** Lucene's {@link ASCIIFoldingFilter} does not honour `KeywordAttribute`, so a token marked as
 * protected from stemming is still folded. It does not matter yet — nothing marks tokens — but when protection
 * of individual terms arrives (a schema-supplied list of expressions exempt from stemming, translated to
 * `SetKeywordMarkerFilter`), the protection would silently end at diacritics folding unless this filter is
 * replaced by a keyword-aware equivalent.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class DiacriticsFoldingAnalyzerWrapper extends AnalyzerWrapper {

	/**
	 * The wrapped chain whose output gets folded.
	 */
	@Nonnull private final Analyzer delegate;

	/**
	 * Wraps `delegate` so that its output is diacritics-folded.
	 *
	 * The delegate's own reuse strategy is adopted, which is what Lucene recommends when a wrapper wraps a
	 * single analyzer; this wrapper never chooses a different delegate per field.
	 *
	 * @param delegate chain to wrap; this wrapper takes over its lifecycle and closes it in {@link #close()}
	 */
	public DiacriticsFoldingAnalyzerWrapper(@Nonnull Analyzer delegate) {
		super(delegate.getReuseStrategy());
		this.delegate = delegate;
	}

	@Override
	protected Analyzer getWrappedAnalyzer(String fieldName) {
		return this.delegate;
	}

	@Override
	protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
		// the filter is appended to the END of the delegate's chain - i.e. after its stemmer, see the class
		// javadoc on why the opposite order silently breaks stemming
		return new TokenStreamComponents(
			components.getSource(),
			new ASCIIFoldingFilter(components.getTokenStream())
		);
	}

	@Override
	protected TokenStream wrapTokenStreamForNormalization(String fieldName, TokenStream in) {
		// `Analyzer.normalize` is the separate single-term path used by prefix and fuzzy queries, into which
		// language analyzers wire only length-preserving filters (`CzechAnalyzer.normalize` is literally just a
		// lowercase filter). Folding has to be applied here too: the term dictionary holds folded terms, so a
		// prefix or fuzzy probe that was not folded would be looking in a different space than the one it
		// searches.
		return new ASCIIFoldingFilter(in);
	}

	@Override
	public void close() {
		super.close();
		this.delegate.close();
	}

}
