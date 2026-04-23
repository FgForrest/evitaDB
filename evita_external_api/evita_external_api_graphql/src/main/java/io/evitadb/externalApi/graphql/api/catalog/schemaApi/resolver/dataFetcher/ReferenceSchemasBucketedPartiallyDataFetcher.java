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
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
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
 * Provides complete list of {@link ReferenceSchemaContract#getBucketedPartiallyInScopes()}
 * as a list of maps containing scope and expression string pairs for GraphQL schema resolution.
 * Each map entry contains a "scope" key with the {@link Scope} value and an "expression" key
 * with the expression string obtained via {@link Expression#toExpressionString()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceSchemasBucketedPartiallyDataFetcher implements DataFetcher<List<Map<String, Object>>> {

	@Nullable
	private static ReferenceSchemasBucketedPartiallyDataFetcher INSTANCE = null;

	/**
	 * Returns the singleton instance of this data fetcher.
	 *
	 * @return the singleton instance
	 */
	@Nonnull
	public static ReferenceSchemasBucketedPartiallyDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReferenceSchemasBucketedPartiallyDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<Map<String, Object>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final ReferenceSchemaContract referenceSchema = Objects.requireNonNull(environment.getSource());
		final Map<Scope, Expression> bucketedPartiallyInScopes = referenceSchema.getBucketedPartiallyInScopes();
		final List<Map<String, Object>> result = new ArrayList<>(bucketedPartiallyInScopes.size());
		for (final Map.Entry<Scope, Expression> entry : bucketedPartiallyInScopes.entrySet()) {
			final Map<String, Object> map = createHashMap(2);
			map.put("scope", entry.getKey());
			map.put(
				"expression",
				entry.getValue() != null ? entry.getValue().toExpressionString() : null
			);
			result.add(map);
		}
		return result;
	}
}
