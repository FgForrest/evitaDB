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
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Extracts {@link Hierarchy} from {@link EvitaResponse}s extra results
 * requested by {@link HierarchyOfSelf} or {@link HierarchyOfReference} into map with key of correct field names
 * representing references.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class HierarchyDataFetcher implements DataFetcher<Map<String,Map<String, List<LevelInfo>>>> {

	@Nonnull
	private final Map<String, String> referenceNameToFieldName;

	public HierarchyDataFetcher(@Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		this.referenceNameToFieldName = referenceSchemas.stream()
			.map(referenceSchema -> new SimpleEntry<>(referenceSchema.getName(), referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION)))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Nullable
	@Override
	public Map<String, Map<String, List<LevelInfo>>> get(DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = Objects.requireNonNull(environment.getSource());
		final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
		if (hierarchy == null) {
			return null;
		}

		final Map<String, Map<String, List<LevelInfo>>> hierarchyDto = createHashMap(hierarchy.getReferenceHierarchies().size() + 1);
		hierarchyDto.put(
			HierarchyDescriptor.SELF.name(),
			hierarchy.getSelfHierarchy()
		);
		hierarchy.getReferenceHierarchies().forEach((key, value) -> hierarchyDto.put(
			this.referenceNameToFieldName.get(key),
			value
		));

		return hierarchyDto;
	}

}
