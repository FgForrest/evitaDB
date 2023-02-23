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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.externalApi.grpc.generated.GrpcEntityReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentEntities;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentEntity;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentsByReference;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This class builds is used for building gRPC representation in gRPC message types of {@link HierarchyParents}.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcHierarchyParentsBuilder {

	/**
	 * This method is used to build {@link GrpcHierarchyParentsByReference} from provided {@link HierarchyParents}.
	 *
	 * @param extraResults the builder where the built result should be placed in
	 * @param hierarchyParents      {@link HierarchyParents} returned by evita response
	 */
	public static void buildParents(@Nonnull Builder extraResults, @Nonnull HierarchyParents hierarchyParents) {
		ofNullable(hierarchyParents.ofSelf()).ifPresent(it -> extraResults.setSelfHierarchyParents(
			GrpcHierarchyParentsByReference.newBuilder()
				.putAllHierarchyParentsByReference(GrpcHierarchyParentsBuilder.buildParentsByReference(it))
				.build()
		));

		if (!hierarchyParents.getParents().isEmpty()) {
			final Map<String, GrpcHierarchyParentsByReference> parentsResult = createHashMap(hierarchyParents.getParents().size());
			for (Map.Entry<String, ParentsByReference> parentsByReference : hierarchyParents.getParents().entrySet()) {
				final Map<Integer, GrpcHierarchyParentEntities> parentsIndex = GrpcHierarchyParentsBuilder.buildParentsByReference(
					parentsByReference.getValue()
				);
				parentsResult.put(
					parentsByReference.getKey(),
					GrpcHierarchyParentsByReference.newBuilder().putAllHierarchyParentsByReference(parentsIndex).build()
				);
			}
			extraResults.putAllHierarchyParents(
				parentsResult
			);
		}
	}

	/**
	 * This method is used to build {@link GrpcHierarchyParentsByReference} from provided {@link HierarchyParents}.
	 *
	 * @param parentsByReference         {@link ParentsByReference} returned by evita response
	 * @return map of all {@link GrpcHierarchyParentsByReference} specified by their entity type
	 */
	@Nonnull
	public static Map<Integer, GrpcHierarchyParentEntities> buildParentsByReference(
		@Nonnull ParentsByReference parentsByReference
	) {
		final Map<Integer, Map<Integer, EntityClassifier[]>> originalParentEntities = parentsByReference.getParents();
		final Map<Integer, GrpcHierarchyParentEntities> grpcHierarchyParentsByReference = createHashMap(originalParentEntities.size());
		for (Entry<Integer, Map<Integer, EntityClassifier[]>> parentEntities : originalParentEntities.entrySet()) {
			final GrpcHierarchyParentEntities.Builder parentEntitiesBuilder = GrpcHierarchyParentEntities.newBuilder();
			if (parentEntities.getValue().values().isEmpty()) {
				grpcHierarchyParentsByReference.put(parentEntities.getKey(),
					GrpcHierarchyParentEntities.newBuilder().putHierarchyParentEntities(
						parentEntities.getKey(),
						GrpcHierarchyParentEntity.newBuilder()
							.addAllEntities(Collections.emptyList())
							.build()
					).build()
				);
			} else {
				for (Entry<Integer, EntityClassifier[]> parentEntity : parentEntities.getValue().entrySet()) {
					final List<GrpcSealedEntity> parentEntityList = new ArrayList<>(parentEntity.getValue().length);
					final List<GrpcEntityReference> parentEntityReferenceList = new ArrayList<>(parentEntity.getValue().length);
					for (EntityClassifier entity : parentEntity.getValue()) {
						if (entity instanceof SealedEntity sealedEntity) {
							parentEntityList.add(EntityConverter.toGrpcSealedEntity(sealedEntity));
						} else if (entity instanceof EntityReference entityReference) {
							parentEntityReferenceList.add(GrpcEntityReference.newBuilder()
								.setPrimaryKey(entityReference.getPrimaryKey())
								.setEntityType(entityReference.getType())
								.build());
						}

					}
					parentEntitiesBuilder.putHierarchyParentEntities(
						parentEntity.getKey(),
						GrpcHierarchyParentEntity.newBuilder()
							.addAllEntityReferences(parentEntityReferenceList)
							.addAllEntities(parentEntityList)
							.build()
					);
				}
				grpcHierarchyParentsByReference.put(parentEntities.getKey(), parentEntitiesBuilder.build());
			}
		}
		return grpcHierarchyParentsByReference;
	}

}
