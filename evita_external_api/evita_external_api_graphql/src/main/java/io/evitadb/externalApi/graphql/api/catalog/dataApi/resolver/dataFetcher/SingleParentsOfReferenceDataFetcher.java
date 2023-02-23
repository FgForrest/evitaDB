/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.ParentsOfEntity;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.ParentsOfReference;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.HierarchyParentsDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Shortcut fetcher for getting parent entities of entity if there is single reference present.
 * Equivalent to {@link ParentsByReference#getParentsFor(int)}.
 * Uses DTOs build by {@link HierarchyParentsDataFetcher}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SingleParentsOfReferenceDataFetcher implements DataFetcher<DataFetcherResult<List<EntityClassifier>>> {

	@Nonnull
	@Override
	public DataFetcherResult<List<EntityClassifier>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final ParentsOfEntity parentsOfEntity = environment.getSource();
		final List<ParentsOfReference> references = parentsOfEntity.references();

		final List<EntityClassifier> parentEntities;
		if (references.isEmpty()) {
			parentEntities = List.of();
		} else if (references.size() == 1) {
			parentEntities = references.get(0).parentEntities();
		} else {
			throw new GraphQLInvalidResponseUsageException(
				"There are " + references.size() + " relations but single wanted. Cannot determine which one."
			);
		}

		return DataFetcherResult.<List<EntityClassifier>>newResult()
			.data(parentEntities)
			.build();
	}
}
