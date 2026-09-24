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
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Applies one {@link FoldedStemmer} to a token stream, honouring {@link KeywordAttribute} exactly as the Lucene
 * language stem filters do so that it can sit behind a `KeywordRepeatFilter` in a two-lane chain. The input
 * stream must already be lowercased and diacritics-folded — that is the {@link FoldedStemmer} contract.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class FoldedStemFilter extends TokenFilter {

	/**
	 * The stemmer applied to every non-keyword token.
	 */
	@Nonnull private final FoldedStemmer stemmer;
	/**
	 * Term text of the current token.
	 */
	@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
	/**
	 * Marks tokens that must not be stemmed.
	 */
	@Nonnull private final KeywordAttribute keywordAttribute = addAttribute(KeywordAttribute.class);

	/**
	 * Creates the filter.
	 *
	 * @param input   stream to filter, already lowercased and diacritics-folded
	 * @param stemmer stemmer to apply
	 */
	FoldedStemFilter(@Nonnull TokenStream input, @Nonnull FoldedStemmer stemmer) {
		super(input);
		this.stemmer = stemmer;
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (this.input.incrementToken()) {
			if (!this.keywordAttribute.isKeyword()) {
				this.termAttribute.setLength(
					this.stemmer.stem(this.termAttribute.buffer(), this.termAttribute.length())
				);
			}
			return true;
		}
		return false;
	}

}
