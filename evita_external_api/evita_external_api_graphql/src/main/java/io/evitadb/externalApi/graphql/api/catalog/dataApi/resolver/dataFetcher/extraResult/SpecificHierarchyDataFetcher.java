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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.HierarchyRequireOutputNameResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.SpecificHierarchyDataFetcher.LevelInfoDto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class SpecificHierarchyDataFetcher implements DataFetcher<List<LevelInfoDto>> {

	@Nonnull
	@Override
	public List<LevelInfoDto> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final Map<String, List<LevelInfo>> hierarchiesOfReference = environment.getSource();

//		HierarchyRequireOutputNameResolver.resolve(environment.getSelectionSet())
//		environment.
		return null;
	}

	protected record LevelInfoDto(@Nullable Integer parentId,
	                              @Nonnull EntityClassifier entity,
	                              @Nullable Integer queriedEntityCount,
	                              @Nullable Integer childrenCount,
	                              boolean hasChildren) {}
}
