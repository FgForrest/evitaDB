/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provides complete list of {@link ReferenceSchemaContract#getIndexedComponentsInScopes()}
 * as a list of {@link ScopedReferenceIndexedComponents} for GraphQL schema resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceSchemaIndexedComponentsDataFetcher implements DataFetcher<List<ScopedReferenceIndexedComponents>> {

	@Nullable
	private static ReferenceSchemaIndexedComponentsDataFetcher INSTANCE = null;

	@Nonnull
	public static ReferenceSchemaIndexedComponentsDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ReferenceSchemaIndexedComponentsDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<ScopedReferenceIndexedComponents> get(DataFetchingEnvironment environment) throws Exception {
		final ReferenceSchemaContract referenceSchema = Objects.requireNonNull(environment.getSource());
		final Map<Scope, Set<ReferenceIndexedComponents>> components = referenceSchema.getIndexedComponentsInScopes();
		return components.entrySet()
			.stream()
			.map(
				it -> new ScopedReferenceIndexedComponents(
					it.getKey(),
					it.getValue().toArray(ReferenceIndexedComponents[]::new)
				)
			)
			.toList();
	}
}
