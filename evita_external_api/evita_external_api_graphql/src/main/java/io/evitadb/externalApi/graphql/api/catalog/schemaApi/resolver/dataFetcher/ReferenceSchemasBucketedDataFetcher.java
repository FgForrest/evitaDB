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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.dataType.Scope;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Provides complete list of {@link ReferenceSchemaContract#getAllHistogramIndexDefinitions()}
 * as a list of maps containing scope, nameOfTheIndex and valueExpression for GraphQL schema resolution.
 * Each map entry contains a "scope" key with the {@link Scope} value, a "nameOfTheIndex" key with the
 * histogram index name, and a "valueExpression" key with the expression string obtained
 * via {@link io.evitadb.dataType.expression.Expression#toExpressionString()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceSchemasBucketedDataFetcher implements DataFetcher<List<Map<String, Object>>> {

	@Nullable
	private static ReferenceSchemasBucketedDataFetcher INSTANCE = null;

	/**
	 * Returns the singleton instance of this data fetcher.
	 *
	 * @return the singleton instance
	 */
	@Nonnull
	public static ReferenceSchemasBucketedDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReferenceSchemasBucketedDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<Map<String, Object>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final ReferenceSchemaContract referenceSchema = Objects.requireNonNull(environment.getSource());
		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes =
			referenceSchema.getAllHistogramIndexDefinitions();
		int totalEntries = 0;
		for (final Map<String, HistogramIndexDefinition> inner : bucketedInScopes.values()) {
			totalEntries += inner.size();
		}
		final List<Map<String, Object>> result = new ArrayList<>(totalEntries);
		for (final Map.Entry<Scope, Map<String, HistogramIndexDefinition>> scopeEntry : bucketedInScopes.entrySet()) {
			for (final Map.Entry<String, HistogramIndexDefinition> entry : scopeEntry.getValue().entrySet()) {
				final Map<String, Object> map = createHashMap(3);
				map.put("scope", scopeEntry.getKey());
				map.put("nameOfTheIndex", entry.getValue().nameOfTheIndex());
				map.put(
					"valueExpression",
					entry.getValue().valueExpression() != null
						? entry.getValue().valueExpression().toExpressionString()
						: null
				);
				result.add(map);
			}
		}
		return result;
	}
}
