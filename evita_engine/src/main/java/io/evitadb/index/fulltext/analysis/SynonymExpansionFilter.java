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

import org.apache.lucene.analysis.CharArrayMap;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Expands a single word to its synonyms at the word's own position: the word passes through untouched - its position
 * increment included, so the gap a removed stop word left before it survives - and each synonym follows with the
 * word's offsets, position increment zero, Lucene's `SYNONYM` token type and no keyword mark, so a stemmer after it
 * stems the synonyms. A token already standing at the position of another (a synonym of an earlier step, a variant)
 * is expanded the same way and stays at that position.
 *
 * Lucene's `SynonymGraphFilter` is not used: it moves a word it matches after a gap to the next position, and it
 * cannot consume tokens stacked at one position, so a second synonyms step would spread the alternatives over several
 * positions. Multi-word synonyms, which are what it is for, are not supported here.
 */
final class SynonymExpansionFilter extends TokenFilter {
	/**
	 * The synonyms of each word, keyed by the word as it reaches the filter.
	 */
	@Nonnull private final CharArrayMap<String[]> synonyms;
	/**
	 * The term of the current token.
	 */
	@Nonnull private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
	/**
	 * The position increment of the current token.
	 */
	@Nonnull private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
	/**
	 * The token type, `SYNONYM` for an emitted synonym.
	 */
	@Nonnull private final TypeAttribute type = addAttribute(TypeAttribute.class);
	/**
	 * The keyword mark, cleared on an emitted synonym.
	 */
	@Nonnull private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);
	/**
	 * The synonyms of the word last read still to be emitted, `null` when there are none.
	 */
	@Nullable private String[] pending;
	/**
	 * The index of the next synonym in {@link #pending} to emit.
	 */
	private int next;
	/**
	 * The state of the word last read, which each of its synonyms starts from.
	 */
	@Nullable private State word;

	/**
	 * Creates the filter over a stream of words.
	 *
	 * @param input    the words
	 * @param synonyms the synonyms of each word, keyed by the word; a word has none when it is not a key
	 */
	SynonymExpansionFilter(@Nonnull TokenStream input, @Nonnull CharArrayMap<String[]> synonyms) {
		super(input);
		this.synonyms = synonyms;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (this.pending != null) {
			restoreState(this.word);
			this.term.setEmpty().append(this.pending[this.next++]);
			this.increment.setPositionIncrement(0);
			this.type.setType(SynonymGraphFilter.TYPE_SYNONYM);
			this.keyword.setKeyword(false);
			if (this.next == this.pending.length) {
				this.pending = null;
				this.word = null;
			}
			return true;
		}
		if (!this.input.incrementToken()) {
			return false;
		}
		final String[] expansion = this.synonyms.get(this.term.buffer(), 0, this.term.length());
		if (expansion != null) {
			this.pending = expansion;
			this.next = 0;
			this.word = captureState();
		}
		return true;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		this.pending = null;
		this.next = 0;
		this.word = null;
	}
}
