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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.ParentsOfEntity;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.ParentsOfReference;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Tries to extract {@link HierarchyParents} from {@link EvitaResponse} and transform its maps to map of lists so that GraphQL
 * can return it while keeping schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class HierarchyParentsDataFetcher implements DataFetcher<DataFetcherResult<Map<String, List<ParentsOfEntity>>>> {

	@Nonnull
	private final Map<String, String> referenceNameToFieldName;

	public HierarchyParentsDataFetcher(@Nonnull Collection<ReferenceSchemaContract> referenceSchemas) {
		this.referenceNameToFieldName = referenceSchemas.stream()
			.map(referenceSchema -> new SimpleEntry<>(referenceSchema.getName(), referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION)))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Nonnull
	@Override
	public DataFetcherResult<Map<String, List<ParentsOfEntity>>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final EvitaResponse<?> response = environment.getSource();
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		if (hierarchyParents == null) {
			return DataFetcherResult.<Map<String, List<ParentsOfEntity>>>newResult().build();
		}

		final Map<String, List<ParentsOfEntity>> parentsDto = createParentsDto(hierarchyParents);
		return DataFetcherResult.<Map<String, List<ParentsOfEntity>>>newResult()
			.data(parentsDto)
			.build();
	}

	@Nonnull
	private Map<String, List<ParentsOfEntity>> createParentsDto(@Nonnull HierarchyParents hierarchyParents) {
		final Map<String, List<ParentsOfEntity>> parentsDto = createHashMap(hierarchyParents.getParents().size() + 1);

		final ParentsByReference selfParents = hierarchyParents.ofSelf();
		if (selfParents != null) {
			parentsDto.put(
				HierarchyParentsDescriptor.SELF.name(),
				createParentsByReferenceDto(selfParents)
			);
		}

		hierarchyParents.getParents().forEach((referenceName, parentsByReference) ->
			parentsDto.put(
				referenceNameToFieldName.get(referenceName),
				createParentsByReferenceDto(parentsByReference)
			)
		);

		return parentsDto;
	}

	@Nonnull
	private static List<ParentsOfEntity> createParentsByReferenceDto(@Nonnull ParentsByReference parentsByReference) {
		return parentsByReference.getParents()
			.entrySet()
			.stream()
			.map(HierarchyParentsDataFetcher::createParentsOfEntityDto)
			.toList();
	}

	@Nonnull
	private static ParentsOfEntity createParentsOfEntityDto(@Nonnull Entry<Integer, Map<Integer, EntityClassifier[]>> parentsOfEntity) {
		return new ParentsOfEntity(
			parentsOfEntity.getKey(),
			parentsOfEntity.getValue()
				.entrySet()
				.stream()
				.map(HierarchyParentsDataFetcher::createParentsOfReferenceDto)
				.toList()
		);
	}

	@Nonnull
	private static ParentsOfReference createParentsOfReferenceDto(@Nonnull Entry<Integer, EntityClassifier[]> parentsOfReference) {
		final List<EntityClassifier> parentEntities = Arrays.stream(parentsOfReference.getValue()).toList();
		return new ParentsOfReference(parentsOfReference.getKey(), parentEntities);
	}
}
