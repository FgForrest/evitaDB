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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.FulltextSchemaConverter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Renders the `allLanguages` phases of a create or modify analyzer mutation in typed output; an undefined phase
 * reads as `null`, distinct from a phase declared with no steps.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextAnalyzerMutationAllLanguagesDataFetcher implements DataFetcher<Object> {
	/** Shared stateless field fetcher. */
	@Nonnull
	@Getter private static final FulltextAnalyzerMutationAllLanguagesDataFetcher instance =
		new FulltextAnalyzerMutationAllLanguagesDataFetcher();

	@Nonnull
	@Override
	public Object get(@Nonnull DataFetchingEnvironment environment) {
		final Object source = environment.getSource();
		final FulltextAnalysisPhases value = source instanceof CreateFulltextAnalyzerSchemaMutation mutation ?
			mutation.getAllLanguages() : ((ModifyFulltextAnalyzerSchemaMutation) source).getAllLanguages();
		return FulltextSchemaConverter.phases(value);
	}
}
