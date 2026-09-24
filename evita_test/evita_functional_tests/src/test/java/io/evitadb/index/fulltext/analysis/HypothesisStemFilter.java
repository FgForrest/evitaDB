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
import org.apache.lucene.util.AttributeSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits every distinct stem a list of differently-configured {@link FoldedStemmer}s produces for the current
 * token, all at the same position — the query half of mechanism M7. The first hypothesis replaces the token, the
 * rest are emitted as zero-position-increment followers, exactly the shape a synonym filter uses, so downstream
 * consumers treat them as OR'd alternatives of one query word.
 *
 * The stemmers passed in differ only in the switch positions of their fold-ambiguous rules; the union of their
 * outputs is exactly the set of stems a branching stemmer would produce, because every fork point is controlled
 * by one of the switches. The rules that are *not* ambiguous run identically in every hypothesis, so the fan-out
 * stays small: a token yields one term unless an ambiguous rule actually fires on it.
 *
 * This filter is the **specification-shaped** form — trivially correct, one string per configuration, and paid
 * for dearly (~99 % of its work is redundant, JMH-measured in the SK/PL/RO record's §9.9/§9.10). The optimized
 * Czech twin is {@link VariantStemFilter}; {@code BranchingCzechStemmerEquivalenceTest} pins the two
 * to identical output over the whole cs_CZ lexicon.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class HypothesisStemFilter extends TokenFilter {

	/**
	 * The stemmer configurations whose outputs are unioned per token.
	 */
	@Nonnull private final List<? extends FoldedStemmer> stemmers;
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
	 * Hypotheses of the current token still waiting to be emitted.
	 */
	@Nonnull private final ArrayDeque<String> pendingHypotheses = new ArrayDeque<>(4);
	/**
	 * Attribute state of the token the pending hypotheses belong to, restored for each of them so that
	 * offsets and flags stay those of the original token.
	 */
	private AttributeSource.State currentTokenState;

	/**
	 * Creates the filter.
	 *
	 * @param input    stream to filter, already lowercased and diacritics-folded
	 * @param stemmers stemmer configurations to union
	 */
	HypothesisStemFilter(@Nonnull TokenStream input, @Nonnull List<? extends FoldedStemmer> stemmers) {
		super(input);
		this.stemmers = stemmers;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (!this.pendingHypotheses.isEmpty()) {
			restoreState(this.currentTokenState);
			this.termAttribute.setEmpty().append(this.pendingHypotheses.poll());
			this.positionIncrementAttribute.setPositionIncrement(0);
			return true;
		}
		if (!this.input.incrementToken()) {
			return false;
		}
		final int length = this.termAttribute.length();
		final char[] scratch = new char[length];
		final Set<String> hypotheses = new LinkedHashSet<>(4);
		for (final FoldedStemmer stemmer : this.stemmers) {
			System.arraycopy(this.termAttribute.buffer(), 0, scratch, 0, length);
			final int stemmedLength = stemmer.stem(scratch, length);
			hypotheses.add(new String(scratch, 0, stemmedLength));
		}
		final Iterator<String> hypothesisIterator = hypotheses.iterator();
		this.termAttribute.setEmpty().append(hypothesisIterator.next());
		while (hypothesisIterator.hasNext()) {
			this.pendingHypotheses.add(hypothesisIterator.next());
		}
		if (!this.pendingHypotheses.isEmpty()) {
			this.currentTokenState = captureState();
		}
		return true;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		this.pendingHypotheses.clear();
		this.currentTokenState = null;
	}

}
