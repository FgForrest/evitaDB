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
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * The lower bound of the language table: word-break tokenization plus lowercasing, no stop words and no stemmer.
 *
 * Used by two built-in analyzers for two different reasons. It is the **generic fallback** for a language
 * evitaDB has no analyzer for, where guessing a stemmer would be worse than not stemming. And it is the Slovak analyzer, because
 * Lucene ships no `SlovakAnalyzer` and no Slovak stop-word list: recall suffers (word forms do not converge)
 * but precision never does — nothing is collapsed by mistake, which in an e-shop is the failure that matters.
 * Substituting the Czech stemmer would be free and might work, but its false-merge rate on Slovak is unmeasured,
 * and a dictionary-based Slovak stemmer (Hunspell) needs a dictionary whose provenance and licence are not
 * settled yet.
 *
 * Note that the chain is composed **by type**, not through Lucene's `CustomAnalyzer` name-based SPI lookup.
 * Factories resolved by name go through `ServiceLoader`, which on the module path only finds providers whose
 * module is actually resolved in the graph — a whole class of "the factory was not found" failures that simply
 * cannot occur here. When schema-driven `custom:…` chains arrive, `CustomAnalyzer`'s type-taking overloads are
 * the intended vehicle, for the same reason.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TokenizingAnalyzer extends Analyzer {

	@Override
	protected TokenStreamComponents createComponents(String fieldName) {
		final Tokenizer source = new StandardTokenizer();
		final TokenStream result = new LowerCaseFilter(source);
		return new TokenStreamComponents(source, result);
	}

	@Override
	protected TokenStream normalize(String fieldName, TokenStream in) {
		// lowercasing preserves token length and never splits, so it belongs on the single-term
		// (prefix / fuzzy) path as well - the same choice every Lucene language analyzer makes
		return new LowerCaseFilter(in);
	}

}
