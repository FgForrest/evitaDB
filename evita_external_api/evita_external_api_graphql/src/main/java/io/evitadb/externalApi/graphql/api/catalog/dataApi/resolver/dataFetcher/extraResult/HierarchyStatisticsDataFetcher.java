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

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.query.require.HierarchyStatisticsOfSelf;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Extracts {@link io.evitadb.api.requestResponse.extraResult.HierarchyStatistics} from {@link EvitaResponse}s extra results
 * requested by {@link HierarchyStatisticsOfSelf}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class HierarchyStatisticsDataFetcher implements DataFetcher<DataFetcherResult<Map<String, List<LevelInfo>>>> {

	@Nonnull
	private final Map<String, String> referenceNameToFieldName;

	public HierarchyStatisticsDataFetcher(@Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		this.referenceNameToFieldName = referenceSchemas.stream()
			.map(referenceSchema -> new SimpleEntry<>(referenceSchema.getName(), referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION)))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Nonnull
	@Override
	public DataFetcherResult<Map<String, List<LevelInfo>>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = environment.getSource();
		final HierarchyStatistics hierarchyStatistics = response.getExtraResult(HierarchyStatistics.class);
		if (hierarchyStatistics == null) {
			return DataFetcherResult.<Map<String, List<LevelInfo>>>newResult().build();
		}

		final Map<String, List<LevelInfo>> statisticsDto = createHashMap(hierarchyStatistics.getStatistics().size() + 1);

		statisticsDto.put(
			HierarchyStatisticsDescriptor.SELF.name(),
			hierarchyStatistics.getSelfStatistics()
		);
		hierarchyStatistics.getStatistics().forEach((key, value) -> statisticsDto.put(
			referenceNameToFieldName.get(key),
			value
		));

		return DataFetcherResult.<Map<String, List<LevelInfo>>>newResult()
			.data(statisticsDto)
			.build();
	}

}
