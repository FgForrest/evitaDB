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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The three analyzer names one (collection, locale) combination uses — what a schema actually says about
 * analysis, and the only thing it says.
 *
 * A schema never names a Lucene type, never declares an {@link AnalysisMode} and never builds a chain: it points
 * at analyzers **by name** and {@link FulltextAnalyzerRegistry} translates those names into instances. The mode
 * is a property of the registered analyzer, not of the place it is used — see {@link AnalysisMode} for why the
 * declaration belongs to whoever registered the chain rather than to whoever assigns it.
 *
 * **Three slots, not two.** A text field needs an indexing chain, a query chain, and separately a *phrase*
 * chain, because an analyzer that drops stop words destroys the phrase "the who" or "být, či nebýt" — nothing of
 * it survives, or something else does. The distinction is a server-level concept with no counterpart in Lucene,
 * and splitting the slot later is not a refactor: the analysis chain is part of the definition of an index's
 * content, so changing which chain a slot uses is a catalog reindex. Splitting an unused slot today is free;
 * splitting it later costs a catalog.
 *
 * **The two search-side names are nullable on purpose.** Null means *inherited*, not "same value" — the
 * distinction is what a schema round-trip needs, because rewriting an unset slot into an explicit copy of the
 * index analyzer would freeze today's inheritance into stored data. The inheritance rule itself
 * (index &rarr; search &rarr; phrase) is a correctness property rather than a convenience — the query side has
 * to meet what the index side wrote — so it is spelled out exactly once, in {@link #analyzerName(AnalyzerSlot)},
 * instead of being reimplemented by every resolver.
 *
 * @param indexAnalyzer  name of the analyzer used while indexing stored values; the only mandatory one, because
 *                       the other two inherit from it
 * @param searchAnalyzer name of the analyzer used for query text, or null to inherit the indexing one
 * @param phraseAnalyzer name of the analyzer used for phrase-query text, or null to inherit the query one
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public record AnalyzerAssignment(
	@Nonnull String indexAnalyzer,
	@Nullable String searchAnalyzer,
	@Nullable String phraseAnalyzer
) {

	public AnalyzerAssignment {
		Assert.isTrue(
			!indexAnalyzer.isBlank(),
			() -> new EvitaInvalidUsageException("Index analyzer name must not be empty.")
		);
		Assert.isTrue(
			searchAnalyzer == null || !searchAnalyzer.isBlank(),
			() -> new EvitaInvalidUsageException(
				"Search analyzer name must not be empty - use null to inherit the index analyzer."
			)
		);
		Assert.isTrue(
			phraseAnalyzer == null || !phraseAnalyzer.isBlank(),
			() -> new EvitaInvalidUsageException(
				"Phrase analyzer name must not be empty - use null to inherit the search analyzer."
			)
		);
	}

	/**
	 * Creates an assignment putting the same analyzer into all three slots — the shape of every language
	 * default, where nothing distinguishes indexing from querying yet. Only the index slot is set explicitly;
	 * the other two inherit, so the assignment stays readable as "one analyzer everywhere" rather than as three
	 * independent choices that merely happen to agree.
	 *
	 * @param name name of the analyzer to use in every slot
	 * @return the assignment
	 */
	@Nonnull
	public static AnalyzerAssignment uniform(@Nonnull String name) {
		return new AnalyzerAssignment(name, null, null);
	}

	/**
	 * Returns the name of the analyzer this assignment prescribes for `slot`, applying the inheritance rule
	 * index &rarr; search &rarr; phrase to slots left unset.
	 *
	 * @param slot slot an analyzer is needed for
	 * @return name of the analyzer to use, never null
	 */
	@Nonnull
	public String analyzerName(@Nonnull AnalyzerSlot slot) {
		return switch (slot) {
			case INDEX -> this.indexAnalyzer;
			case SEARCH -> searchAnalyzerName();
			case PHRASE -> this.phraseAnalyzer == null ? searchAnalyzerName() : this.phraseAnalyzer;
		};
	}

	/**
	 * Returns the name the query slot resolves to, i.e. the search analyzer or the inherited index one. Kept
	 * separate because the phrase slot inherits through it rather than from the index analyzer directly.
	 *
	 * @return name of the analyzer used for query text
	 */
	@Nonnull
	private String searchAnalyzerName() {
		return this.searchAnalyzer == null ? this.indexAnalyzer : this.searchAnalyzer;
	}

}
