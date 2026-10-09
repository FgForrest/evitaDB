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
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;

import javax.annotation.Nonnull;
import java.io.Reader;

/**
 * Puts Lucene's {@link HTMLStripCharFilter} in front of a finished analysis chain, so that tag names, attribute names,
 * attribute values (a JSON payload in a `data-` attribute included), comments, `script` and `style` bodies never reach
 * the tokenizer, and character entities are decoded into the characters they stand for.
 *
 * **Only the index chains are wrapped.** Every built-in indexing analyzer strips markup, every `*-search` chain does
 * not: a stored value may be HTML, a query text is what a user typed. On markup-free text the filter is a no-op -
 * terms, offsets and positions included - which is what lets it run over every searchable field of a
 * (collection, locale) rather than needing a per-field switch. The few prose shapes it does change (`S<M<L` loses its
 * last size, `a<b>c` joins into `ac`) are pinned by `HtmlMarkupStrippingAnalysisTest`.
 *
 * **Why a wrapper.** A character filter decorates the `Reader`, so the only place it can go is in front of the
 * tokenizer, and `AnalyzerWrapper#wrapReader` is exactly that seam. Lucene's language analyzers are final classes, so
 * the seam is also the only way in for them; the hand-built chains are wrapped the same way to keep one mechanism.
 *
 * **Offsets point into the original text.** The filter corrects offsets back through the runs it removed, so a term's
 * offsets - and the surface form cut from them - address the stored, markup-bearing value, which is what highlighting
 * needs. A term directly followed by a tag ends after that tag (`pánská</b>`); a consumer of the surface form has to
 * trim it.
 *
 * **What is not stripped.** The single-term normalization path (`Analyzer#normalize`, used by prefix and fuzzy probes)
 * is left alone: a typed prefix is never markup, and a `&lt;` typed there is a character the user means. An entity
 * decoding to a combining mark (`c&#780;`) bypasses the NFC normalization {@link FulltextAnalyzer} applies, because
 * the filter runs after it inside the chain; such a word is neither stemmed nor folded - a recall loss accepted until a
 * corpus shows it matters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HtmlStrippingAnalyzerWrapper extends AnalyzerWrapper {

	/**
	 * The wrapped chain that receives the stripped text.
	 */
	@Nonnull private final Analyzer delegate;

	/**
	 * Wraps `delegate` so that markup is stripped from its input.
	 *
	 * The delegate's own reuse strategy is adopted, which is what Lucene recommends when a wrapper wraps a single
	 * analyzer; this wrapper never chooses a different delegate per field.
	 *
	 * @param delegate chain to wrap; this wrapper takes over its lifecycle and closes it in {@link #close()}
	 */
	public HtmlStrippingAnalyzerWrapper(@Nonnull Analyzer delegate) {
		super(delegate.getReuseStrategy());
		this.delegate = delegate;
	}

	@Override
	protected Analyzer getWrappedAnalyzer(String fieldName) {
		return this.delegate;
	}

	@Override
	protected Reader wrapReader(String fieldName, Reader reader) {
		return new HTMLStripCharFilter(reader);
	}

	@Override
	public void close() {
		super.close();
		this.delegate.close();
	}

}
