/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Ancestor for fetching references from entities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
abstract class AbstractReferenceDataFetcher<T> implements DataFetcher<T> {

	@Override
	public T get(DataFetchingEnvironment environment) throws Exception {
		final DataChunk<ReferenceContract> references = retrieveReferences(environment);
		return doGet(Objects.requireNonNull(environment), references);
	}

	protected abstract T doGet(@Nonnull DataFetchingEnvironment environment, @Nonnull DataChunk<ReferenceContract> references);

	@Nonnull
	private static DataChunk<ReferenceContract> retrieveReferences(@Nonnull DataFetchingEnvironment environment) {
		final ServerEntityDecorator entity = Objects.requireNonNull(environment.getSource());
		final String instanceName = resolveInstanceName(environment);
		return entity.getReferencesForReferenceContentInstance(instanceName);
	}

	private static String resolveInstanceName(@Nonnull DataFetchingEnvironment environment) {
		final String fieldAlias = environment.getField().getAlias();
		final String fieldName = environment.getField().getName();
		return fieldAlias != null
			? fieldAlias
			: fieldName;
	}
}
