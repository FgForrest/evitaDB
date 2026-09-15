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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.ArrayUtil;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Replaces every token with the full set of stem variants a {@link VariantStemmer} derives from it, all emitted
 * **at one position**: the first variant keeps the token's incoming position increment, every further variant
 * gets increment `0`. That is the same shape Lucene's synonym filter uses, and it carries the same meaning —
 * the variants are alternatives for one query word, not a sequence of words.
 *
 * **The query pipeline must therefore OR the terms of one position.** A consumer that ANDs them, or that treats
 * them as consecutive words, asks the index for a word that was never written and finds nothing. This filter
 * belongs on the **query** side only; the index side stems the accented text once and folds afterwards (see
 * {@link VariantStemmer} for why the two sides are asymmetric).
 *
 * **It must be the last filter of its chain.** Variants after the first are emitted **without**
 * `captureState`/`restoreState`, because only the term text and the position increment differ between them and
 * every other attribute still carries the token's values — which holds precisely as long as no downstream filter
 * runs between two emissions and mutates them. This is what keeps the filter allocation-free in steady state,
 * and it is a real constraint, not an optimisation note: a filter appended after this one would corrupt the
 * variants still pending.
 *
 * Input must already be lowercased and diacritics-folded, as {@link VariantStemmer} requires.
 *
 * See `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/` for the measurements behind this design.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VariantStemFilter extends TokenFilter {

	/**
	 * The variant stemmer holding the variants of the current token — stateful scratch, one per stream.
	 */
	@Nonnull private final VariantStemmer stemmer;
	/**
	 * Term text of the current token.
	 */
	@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
	/**
	 * Position increment of the current token, set to zero for every variant after the first.
	 */
	@Nonnull private final PositionIncrementAttribute positionIncrementAttribute =
		addAttribute(PositionIncrementAttribute.class);
	/**
	 * Reusable copy of the current token's original text — the buffer every variant is materialized over,
	 * kept because emitting one variant overwrites the term attribute the next one needs.
	 */
	@Nonnull private char[] tokenBuffer = new char[16];
	/**
	 * Length of the current token in {@link #tokenBuffer}.
	 */
	private int tokenLength;
	/**
	 * Index of the next variant of the current token to emit.
	 */
	private int nextVariant;
	/**
	 * Number of variants of the current token.
	 */
	private int variantCount;

	/**
	 * Creates the filter.
	 *
	 * @param input   stream to filter, already lowercased and diacritics-folded
	 * @param stemmer the language's variant stemmer — owned by this filter, one instance per stream
	 */
	VariantStemFilter(@Nonnull TokenStream input, @Nonnull VariantStemmer stemmer) {
		super(input);
		this.stemmer = stemmer;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (this.nextVariant < this.variantCount) {
			emit(this.nextVariant++);
			this.positionIncrementAttribute.setPositionIncrement(0);
			return true;
		}
		if (!this.input.incrementToken()) {
			return false;
		}
		this.tokenLength = this.termAttribute.length();
		if (this.tokenBuffer.length < this.tokenLength) {
			this.tokenBuffer = new char[ArrayUtil.oversize(this.tokenLength, Character.BYTES)];
		}
		System.arraycopy(this.termAttribute.buffer(), 0, this.tokenBuffer, 0, this.tokenLength);
		this.variantCount = this.stemmer.stem(this.tokenBuffer, this.tokenLength);
		this.nextVariant = 0;
		// the first variant keeps the incoming position increment
		emit(this.nextVariant++);
		return true;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		this.nextVariant = 0;
		this.variantCount = 0;
	}

	/**
	 * Materializes the given variant of the current token straight into the term attribute's buffer.
	 *
	 * @param variantIndex index of the variant to emit
	 */
	private void emit(int variantIndex) {
		final int length = this.stemmer.length(variantIndex);
		this.stemmer.materialize(variantIndex, this.tokenBuffer, this.termAttribute.resizeBuffer(length));
		this.termAttribute.setLength(length);
	}

}
