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
 * The optimized query half of mechanism M7: emits every distinct stem hypothesis of the current token at one
 * position, exactly like {@link HypothesisStemFilter} over a flat configuration union, but computed by a
 * {@link BranchingStemmer} — one walk that forks only where a fold-ambiguous rule actually matches — instead
 * of hundreds of full stemmer runs deduplicated through strings and a set. The emitted term sets are identical
 * per language (the lexicon-scale equivalence tests pin that); the per-token cost drops from up to 1,025
 * buffer copies, stems and string materializations to a handful of suffix comparisons and **zero allocation**
 * in steady state (Czech; the Romanian walk copies a few small pooled buffers).
 *
 * Steady-state zero allocation rests on two shortcuts the flat prototype does not take:
 *
 * - hypotheses live as `(length, final-two-characters)` triples inside the stemmer and are materialized
 *   directly into the term attribute's buffer over a reusable copy of the original token — no strings, no set;
 * - pending hypotheses are emitted **without** `captureState`/`restoreState`: only the term text and the
 *   position increment differ between the hypotheses of one token, and no other filter runs between the
 *   emissions, so every other attribute still carries the token's values. This makes the filter valid **only
 *   as the last filter of its chain** — a downstream filter that mutates attributes would corrupt the
 *   remaining hypotheses.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class BranchingHypothesisStemFilter extends TokenFilter {

	/**
	 * The branching stemmer holding the hypotheses of the current token — stateful scratch, one per stream.
	 */
	@Nonnull private final BranchingStemmer stemmer;
	/**
	 * Term text of the current token.
	 */
	@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
	/**
	 * Position increment of the current token, set to zero for every hypothesis after the first.
	 */
	@Nonnull private final PositionIncrementAttribute positionIncrementAttribute =
		addAttribute(PositionIncrementAttribute.class);
	/**
	 * Reusable copy of the current token's original text — the buffer every hypothesis is materialized over,
	 * kept because emitting one hypothesis overwrites the term attribute the next one needs.
	 */
	@Nonnull private char[] tokenBuffer = new char[16];
	/**
	 * Length of the current token in {@link #tokenBuffer}.
	 */
	private int tokenLength;
	/**
	 * Index of the next hypothesis of the current token to emit.
	 */
	private int nextHypothesis;
	/**
	 * Number of hypotheses of the current token.
	 */
	private int hypothesisCount;

	/**
	 * Creates the filter.
	 *
	 * @param input   stream to filter, already lowercased and diacritics-folded
	 * @param stemmer the language's branching stemmer — owned by this filter, one instance per stream
	 */
	BranchingHypothesisStemFilter(@Nonnull TokenStream input, @Nonnull BranchingStemmer stemmer) {
		super(input);
		this.stemmer = stemmer;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (this.nextHypothesis < this.hypothesisCount) {
			emit(this.nextHypothesis++);
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
		this.hypothesisCount = this.stemmer.hypothesize(this.tokenBuffer, this.tokenLength);
		this.nextHypothesis = 0;
		// the first hypothesis keeps the incoming position increment
		emit(this.nextHypothesis++);
		return true;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		this.nextHypothesis = 0;
		this.hypothesisCount = 0;
	}

	/**
	 * Materializes the given hypothesis of the current token straight into the term attribute's buffer.
	 *
	 * @param hypothesisIndex index of the hypothesis to emit
	 */
	private void emit(int hypothesisIndex) {
		final int length = this.stemmer.length(hypothesisIndex);
		this.stemmer.materialize(hypothesisIndex, this.tokenBuffer, this.termAttribute.resizeBuffer(length));
		this.termAttribute.setLength(length);
	}

}
