/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import com.google.protobuf.Int32Value;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.externalApi.grpc.generated.GrpcEntityReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchy;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfo;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfos;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static java.util.Optional.ofNullable;

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
	 * @param extraResults the builder where the built result should be placed in
	 * @param hierarchy    {@link Hierarchy} returned by evita response
	 * @param clientVersion version of the client so that the server can adjust the response
	 */
	public static void buildHierarchy(@Nonnull Builder extraResults, @Nonnull Hierarchy hierarchy, @Nullable SemVer clientVersion) {
		final Map<String, List<LevelInfo>> hierarchyIndex = hierarchy.getSelfHierarchy();
		if (!hierarchyIndex.isEmpty()) {
			extraResults.setSelfHierarchy(buildHierarchy(hierarchy.getSelfHierarchy(), clientVersion));
		}

		for (Map.Entry<String, Map<String, List<LevelInfo>>> hierarchyIndexOfReference : hierarchy.getReferenceHierarchies().entrySet()) {
			extraResults.putHierarchy(
				hierarchyIndexOfReference.getKey(),
				buildHierarchy(hierarchyIndexOfReference.getValue(), clientVersion)
			);
		}
	}

	/**
	 * This method is used to build {@link GrpcHierarchy} from provided {@link Hierarchy}.
	 *
	 * @param hierarchies {@link Hierarchy} returned by evita response
	 * @param clientVersion version of the client so that the server can adjust the response
	 * @return map of all hierarchy statistics specified by their entity type
	 */
	@Nonnull
	public static GrpcHierarchy buildHierarchy(@Nonnull Map<String, List<LevelInfo>> hierarchies, @Nullable SemVer clientVersion) {
		final GrpcHierarchy.Builder builder = GrpcHierarchy.newBuilder();
		if (!hierarchies.isEmpty()) {
			for (Entry<String, List<LevelInfo>> entry : hierarchies.entrySet()) {
				builder.putHierarchy(
					entry.getKey(),
					buildLevelInfos(entry.getValue(), clientVersion)
				);
			}
		}
		return builder.build();
	}

	/**
	 * This method is used to build {@link GrpcLevelInfos} from provided {@link List} of {@link LevelInfo}.
	 * @param levelInfos {@link List} of {@link LevelInfo} to be converted to {@link GrpcLevelInfos}
	 * @param clientVersion version of the client so that the server can adjust the response
	 * @return {@link GrpcLevelInfos} consisting of all children of the given {@link LevelInfo}
	 */
	@Nonnull
	public static GrpcLevelInfos buildLevelInfos(@Nonnull List<LevelInfo> levelInfos, @Nullable SemVer clientVersion) {
		final GrpcLevelInfos.Builder builder = GrpcLevelInfos.newBuilder();
		for (LevelInfo levelInfo : levelInfos) {
			builder.addLevelInfos(buildLevelInfo(levelInfo, clientVersion));
		}
		return builder.build();
	}


	/**
	 * Method used to recursively find and build all {@link GrpcLevelInfo}. Entities on each level could be either represented by {@link Integer} or
	 * by {@link SealedEntity}, those entities are here converted to {@link GrpcSealedEntity} with richness specified in query.
	 *
	 * @param levelInfo to be converted to {@link GrpcLevelInfo}
	 * @param clientVersion version of the client so that the server can adjust the response
	 * @return list of built {@link GrpcLevelInfo} consisting af all children (and recursively found their progeny) of the given {@link LevelInfo}
	 */
	@Nonnull
	private static GrpcLevelInfo buildLevelInfo(@Nonnull LevelInfo levelInfo, @Nullable SemVer clientVersion) {
		final List<GrpcLevelInfo> children = new LinkedList<>();
		if (!levelInfo.children().isEmpty()) {
			for (LevelInfo child : levelInfo.children()) {
				children.add(buildLevelInfo(child, clientVersion));
			}
		}

		final GrpcLevelInfo.Builder grpcLevelInfoBuilder = GrpcLevelInfo.newBuilder();
		ofNullable(levelInfo.queriedEntityCount())
			.ifPresent(it -> grpcLevelInfoBuilder.setQueriedEntityCount(Int32Value.of(it)));
		ofNullable(levelInfo.childrenCount())
			.ifPresent(it -> grpcLevelInfoBuilder.setChildrenCount(Int32Value.of(it)));
		grpcLevelInfoBuilder.setRequested(levelInfo.requested());
		grpcLevelInfoBuilder.addAllItems(children);

		if (levelInfo.entity() instanceof SealedEntity entity) {
			grpcLevelInfoBuilder.setEntity(
				EntityConverter.toGrpcSealedEntity(entity, clientVersion)
			);
		} else if (levelInfo.entity() instanceof EntityReference entityReference) {
			grpcLevelInfoBuilder.setEntityReference(
				GrpcEntityReference.newBuilder()
					.setPrimaryKey(entityReference.getPrimaryKey())
					.setEntityType(entityReference.getType())
					.build()
			);
		}

		return grpcLevelInfoBuilder.build();
	}
}
