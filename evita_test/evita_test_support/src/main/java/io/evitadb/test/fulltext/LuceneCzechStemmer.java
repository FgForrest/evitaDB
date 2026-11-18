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

package io.evitadb.test.fulltext;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.cz.CzechStemFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Lucene's Czech stemmer with the buffer-in-place contract of `CzechStemmer#stem(char[], int)`.
 *
 * Lucene 10 made `org.apache.lucene.analysis.cz.CzechStemmer` package-private; its only public entry is
 * `CzechStemFilter`. This class drives that filter over a one-token stream, so the measurement tests that apply the
 * stemmer to millions of single words keep calling the same algorithm, without the tokenizer, lower-casing and
 * stop-word stages of the full `czech` analyzer. The token stream is reused between calls.
 *
 * Not thread-safe: use one instance per thread.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class LuceneCzechStemmer {
	/** Field name passed to the analyzer; the chain does not depend on it. */
	private static final String FIELD = "";
	/** A keyword tokenizer followed by the Czech stem filter, reused between calls. */
	private final Analyzer analyzer = new Analyzer() {
		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			final Tokenizer tokenizer = new KeywordTokenizer();
			return new TokenStreamComponents(tokenizer, new CzechStemFilter(tokenizer));
		}
	};

	/**
	 * Stems one lowercase accented word in place.
	 *
	 * @param buffer the word's characters; receives the stem
	 * @param length number of valid characters in `buffer`
	 * @return length of the stem written to the start of `buffer`
	 */
	public int stem(@Nonnull char[] buffer, int length) {
		try (final TokenStream stream = this.analyzer.tokenStream(FIELD, new String(buffer, 0, length))) {
			final CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			int result = 0;
			if (stream.incrementToken()) {
				result = term.length();
				if (result > buffer.length) {
					throw new IllegalStateException(
						"Czech stem of `" + new String(buffer, 0, length) + "` is longer than its input buffer."
					);
				}
				System.arraycopy(term.buffer(), 0, buffer, 0, result);
			}
			stream.end();
			return result;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
