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

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Rewrites the Romanian comma-below letters `ș`/`ț` (U+0219/U+021B) to the legacy cedilla ones `ş`/`ţ`
 * (U+015F/U+0163), so that both spellings of the same word reach the rest of the chain as one.
 *
 * Modern Romanian text is written with comma-below; a lot of older text, and a lot of software that predates
 * Unicode 3.0, writes cedilla. Lucene 10 reconciles the two with a `RomanianNormalizationFilter` that maps
 * **cedilla → comma-below**, together with a stop list and Snowball tables rewritten in comma-below. The Lucene
 * release this project pins (9.12.3) has neither: its `RomanianAnalyzer` stop list and its Snowball
 * `RomanianStemmer` tables are written in **cedilla** throughout. This filter therefore backports the
 * reconciliation in the direction 9.12.3 can consume — the inverse of Lucene 10's — and becomes removable, in
 * favour of the upstream filter, once the pinned Lucene version carries it.
 *
 * **Placement matters.** It must sit *after* the lowercase filter — uppercase spellings would otherwise survive
 * it — and *before* the stop filter and the stemmer, which are the components whose cedilla-written data it
 * exists to feed. Appended after the stemmer it would do nothing useful: the stemmer would already have failed
 * to recognise the comma-below endings.
 *
 * **Both Romanian chains run it, and in both it sits ahead of the stop filter.** That filter is the single
 * component of either chain that reads the raw spelling before folding flattens it, and the pinned Lucene's
 * Romanian stop list is cedilla-written throughout — 24 of its 230 entries carry `ş`/`ţ`, none carries `ș`/`ț`.
 * Without this filter the query side would let a modern-spelt `și` through the stop filter, fold it to `si`,
 * and ask the index for a word the index side dropped. Everything downstream of the stop filter genuinely does
 * not care, because `ASCIIFoldingFilter` collapses `ș` and `ş` to the same `s` before the stemmer reads them.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CommaBelowNormalizationFilter extends TokenFilter {

	/**
	 * Term text of the current token.
	 */
	@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);

	/**
	 * Creates the filter.
	 *
	 * @param input stream to filter, already lowercased
	 */
	CommaBelowNormalizationFilter(@Nonnull TokenStream input) {
		super(input);
	}

	@Override
	public boolean incrementToken() throws IOException {
		if (!this.input.incrementToken()) {
			return false;
		}
		final char[] buffer = this.termAttribute.buffer();
		final int length = this.termAttribute.length();
		for (int i = 0; i < length; i++) {
			if (buffer[i] == 'ș') {
				buffer[i] = 'ş';
			} else if (buffer[i] == 'ț') {
				buffer[i] = 'ţ';
			}
		}
		return true;
	}

}
