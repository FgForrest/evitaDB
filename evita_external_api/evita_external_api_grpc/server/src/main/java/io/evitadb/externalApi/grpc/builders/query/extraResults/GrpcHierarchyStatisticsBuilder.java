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

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.externalApi.grpc.generated.GrpcEntityReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfo;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfos;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * This class builds is used for building gRPC representation in gRPC message types of {@link Hierarchy}.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcHierarchyStatisticsBuilder {

	/**
	 * This method is used to build {@link GrpcLevelInfos} from provided {@link Hierarchy}.
	 *
	 * @param extraResults        the builder where the built result should be placed in
	 * @param hierarchy {@link Hierarchy} returned by evita response
	 */
	public static void buildHierarchyStatistics(@Nonnull Builder extraResults,
	                                                                     @Nonnull Hierarchy hierarchy) {
		final Map<String, List<LevelInfo>> statistics = hierarchy.getSelfStatistics();
		if (!statistics.isEmpty()) {
			extraResults.setSelfHierarchyStatistics(
				buildHierarchyStatistics(statistics)
			);
		}

		for (Map.Entry<String, Map<String, List<LevelInfo>>> hierarchyStatisticsOfReference : hierarchy.getStatistics().entrySet()) {
			extraResults.putHierarchyStatistics(
				hierarchyStatisticsOfReference.getKey(),
				buildHierarchyStatistics(hierarchyStatisticsOfReference.getValue())
			);
		}
	}

	/**
	 * This method is used to build {@link GrpcLevelInfos} from provided {@link Hierarchy}.
	 *
	 * @param hierarchyStatistics {@link Hierarchy} returned by evita response
	 * @return map of all hierarchy statistics specified by their entity type
	 */
	@Nonnull
	public static GrpcLevelInfos buildHierarchyStatistics(@Nonnull Map<String, List<LevelInfo>> hierarchyStatistics) {
		final List<GrpcLevelInfo> children = new LinkedList<>();

		/*
		TODO LHO - update structure

		if (hierarchyStatistics.isEmpty()) {
			return GrpcLevelInfos.newBuilder().addAllLevelInfos(children).build();
		}

		for (LevelInfo child : hierarchyStatistics) {
			children.addAll(buildLevelInfoChild(child));
		}
		 */

		return GrpcLevelInfos.newBuilder().addAllLevelInfos(children).build();
	}


	/**
	 * Method used to recursively find and build all {@link GrpcLevelInfo}. Entities on each level could be either represented by {@link Integer} or
	 * by {@link SealedEntity}, those entities are here converted to {@link GrpcSealedEntity} with richness specified in query.
	 *
	 * @param levelInfo          to be converted to {@link GrpcLevelInfo}
	 * @return list of built {@link GrpcLevelInfo} consisting af all children (and recursively found their progeny) of the given {@link LevelInfo}
	 */
	@Nonnull
	private static List<GrpcLevelInfo> buildLevelInfoChild(@Nonnull LevelInfo levelInfo) {
		final List<GrpcLevelInfo> levelInfos = new LinkedList<>();
		final List<GrpcLevelInfo> children = new LinkedList<>();
		if (!levelInfo.children().isEmpty()) {
			for (LevelInfo child : levelInfo.children()) {
				children.addAll(buildLevelInfoChild(child));
			}
		}

		final GrpcLevelInfo.Builder grpcLevelInfoBuilder = GrpcLevelInfo.newBuilder()
			// TODO LHO - RENAME TO queriedEntityCount in gRPC
			.setCardinality(levelInfo.queriedEntityCount())
			.addAllChildrenStatistics(children);

		if (levelInfo.entity() instanceof SealedEntity entity) {
			grpcLevelInfoBuilder.setEntity(
				EntityConverter.toGrpcSealedEntity(entity)
			);
		} else if (levelInfo.entity() instanceof EntityReference entityReference) {
			grpcLevelInfoBuilder.setEntityReference(
				GrpcEntityReference.newBuilder()
					.setPrimaryKey(entityReference.getPrimaryKey())
					.setEntityType(entityReference.getType())
					.build()
			);
		}

		levelInfos.add(grpcLevelInfoBuilder.build());
		return levelInfos;
	}
}
