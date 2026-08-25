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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedFilterCapabilities;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Provides complete list of {@link AttributeSchemaContract#getFilterCapabilitiesInScopes()} as a list of
 * {@link ScopedFilterCapabilities} carriers for GraphQL schema resolution.
 *
 * Scopes that declare no capability are not present in the schema's map and therefore are not emitted at all - an
 * attribute that is merely filterable resolves to an empty list, which is exactly what every schema looked like
 * before capabilities existed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AttributeSchemaFilterCapabilitiesDataFetcher implements DataFetcher<List<ScopedFilterCapabilities>> {

	@Nullable
	private static AttributeSchemaFilterCapabilitiesDataFetcher INSTANCE = null;

	@Nonnull
	public static AttributeSchemaFilterCapabilitiesDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new AttributeSchemaFilterCapabilitiesDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	@Nonnull
	public List<ScopedFilterCapabilities> get(DataFetchingEnvironment environment) throws Exception {
		final AttributeSchemaContract attributeSchema = Objects.requireNonNull(environment.getSource());
		return List.of(AttributeSchema.toFilterCapabilitiesArray(attributeSchema.getFilterCapabilitiesInScopes()));
	}
}
