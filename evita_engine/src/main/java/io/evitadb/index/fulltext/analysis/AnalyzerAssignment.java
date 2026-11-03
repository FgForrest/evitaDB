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

/**
 * The two built-in analyzer names one language uses: one for the stored values, one for the text of a query.
 *
 * The pair is never uniform, because the two sides analyse different things: every index chain strips markup from
 * the stored value while a query text is what a user typed, and Czech, Slovak, Polish and Romanian also stem and
 * fold differently on each side - see {@link BuiltInAnalyzers}. {@link FulltextAnalyzerRegistry} translates the names
 * into shared instances.
 *
 * @param indexAnalyzer  name of the analyzer used while indexing stored values
 * @param searchAnalyzer name of the analyzer used for query text
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public record AnalyzerAssignment(
	@Nonnull String indexAnalyzer,
	@Nonnull String searchAnalyzer
) {

	public AnalyzerAssignment {
		Assert.isTrue(
			!indexAnalyzer.isBlank(),
			() -> new EvitaInvalidUsageException("Index analyzer name must not be empty.")
		);
		Assert.isTrue(
			!searchAnalyzer.isBlank(),
			() -> new EvitaInvalidUsageException("Search analyzer name must not be empty.")
		);
	}

}
