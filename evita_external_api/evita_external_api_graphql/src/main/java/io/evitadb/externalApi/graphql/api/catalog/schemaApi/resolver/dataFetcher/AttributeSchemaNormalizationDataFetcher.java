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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.AnalysisStepConverter;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the declared normalization chain in order, independently of the policy retained by populated indexes.
 * Each entry contains its step name and a free-form parameter object.
 */
public final class AttributeSchemaNormalizationDataFetcher implements DataFetcher<List<Map<String, Object>>> {
	private static final AttributeSchemaNormalizationDataFetcher INSTANCE = new AttributeSchemaNormalizationDataFetcher();

	/** Creates the stateless shared fetcher. */
	private AttributeSchemaNormalizationDataFetcher() {
	}

	/**
	 * Returns the shared fetcher.
	 *
	 * @return stateless normalization fetcher
	 */
	@Nonnull
	public static AttributeSchemaNormalizationDataFetcher getInstance() {
		return INSTANCE;
	}

	/**
	 * Reads the schema declaration without consulting index state.
	 *
	 * @param environment GraphQL environment containing an attribute schema
	 * @return ordered step descriptions, or an empty list when no normalization is declared
	 */
	@Override
	@Nonnull
	public List<Map<String, Object>> get(@Nonnull DataFetchingEnvironment environment) {
		final AttributeSchemaContract schema = Objects.requireNonNull(environment.getSource());
		return AnalysisStepConverter.toOutput(schema.getNormalization());
	}

}
