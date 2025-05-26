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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.LevelInfoDto;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.HierarchyRequireOutputNameResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Returns specific hierarchy as flattened list of nodes from hierarchical structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SpecificHierarchyDataFetcher implements DataFetcher<List<LevelInfoDto>> {

	@Nullable
	private static SpecificHierarchyDataFetcher INSTANCE;

	@Nonnull
	public static SpecificHierarchyDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new SpecificHierarchyDataFetcher();
		}
		return INSTANCE;
	}

	@Nonnull
	@Override
	public List<LevelInfoDto> get(DataFetchingEnvironment environment) throws Exception {
		final Map<String, List<LevelInfo>> hierarchiesOfReference = environment.getSource();

		final String outputName = HierarchyRequireOutputNameResolver.resolve(environment.getField());
		final List<LevelInfo> hierarchy = hierarchiesOfReference.get(outputName);
		Assert.isPremiseValid(
			hierarchy != null,
			() -> new GraphQLQueryResolvingInternalError("Missing hierarchy for name `" + outputName + "`")
		);

		final List<LevelInfoDto> flattenedHierarchy = new LinkedList<>();
		hierarchy.forEach(rootLevelInfo -> createLevelInfoDto(flattenedHierarchy, rootLevelInfo, 1));

		return flattenedHierarchy;
	}

	private void createLevelInfoDto(@Nonnull List<LevelInfoDto> flattenedHierarchy,
	                                @Nonnull LevelInfo levelInfo,
	                                int currentLevel) {
		final LevelInfoDto currentLevelInfoDto = new LevelInfoDto(
			currentLevel,
			levelInfo.entity(),
			levelInfo.requested(),
			levelInfo.queriedEntityCount(),
			levelInfo.childrenCount()
		);
		flattenedHierarchy.add(currentLevelInfoDto);

		levelInfo.children()
			.forEach(childLevelInfo -> createLevelInfoDto(flattenedHierarchy, childLevelInfo, currentLevel + 1));
	}

}
