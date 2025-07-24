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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchy;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This test verifies functionalities of methods in {@link GrpcHierarchyStatisticsBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcHierarchyBuilderTest {

	@Test
	void buildHierarchyWithEntityReferences() {
		final Hierarchy hierarchy = new Hierarchy(
			Map.of(
				"megaMenu",
				List.of(
					new LevelInfo(createHierachyEntityReference(2), false, 0, 0, new ArrayList<>(0))
				)
			),
			Map.of(
				Entities.CATEGORY,
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(createHierachyEntityReference(1), false, 1, 0,
							List.of(
								new LevelInfo(createHierachyEntityReference(1), true, 1, 0, new ArrayList<>(0))
							)
						)
					)
				),
				Entities.BRAND,
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(
							createHierachyEntityReference(3), false, 2, 0,
							List.of(
								new LevelInfo(
									createHierachyEntityReference(1), true, 1, 0,
									List.of(
										new LevelInfo(
											createHierachyEntityReference(2), false, 4, 0,
											List.of(
												new LevelInfo(createHierachyEntityReference(5), false, 0, 0, new ArrayList<>(0))
											)
										)
									)
								)
							)
						)
					)
				)
			)
		);

		final GrpcHierarchy referenceSelfHierarchy = GrpcHierarchyStatisticsBuilder.buildHierarchy(hierarchy.getSelfHierarchy(), null);

		final Map<String, GrpcHierarchy> referenceHierarchies = new HashMap<>(hierarchy.getReferenceHierarchies().size());
		for (Entry<String, Map<String, List<LevelInfo>>> entry : hierarchy.getReferenceHierarchies().entrySet()) {
			referenceHierarchies.put(
				entry.getKey(),
				GrpcHierarchyStatisticsBuilder.buildHierarchy(entry.getValue(), null)
			);
		}

		GrpcAssertions.assertHierarchy(hierarchy, referenceSelfHierarchy, referenceHierarchies);
	}

	@Test
	void buildHierarchyWithEntities() {
		final Hierarchy entityHierarchy = new Hierarchy(
			Map.of(
				"megaMenu",
				List.of(new LevelInfo(createHierarchyEntity(2), false, 0, 0, new ArrayList<>(0)))
			),
			Map.of(
				Entities.CATEGORY,
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(createHierarchyEntity(1), true, 1, 0,
							List.of(
								new LevelInfo(createHierarchyEntity(6), false, 1, 0, new ArrayList<>(0))
							)
						)
					)
				),
				Entities.BRAND,
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(
							createHierarchyEntity(3), false, 2, 0,
							List.of(
								new LevelInfo(
									createHierarchyEntity(9), false, 1, 0,
									List.of(
										new LevelInfo(
											createHierarchyEntity(4), true, 4, 0,
											List.of(
												new LevelInfo(createHierarchyEntity(7), false, 0, 0, new ArrayList<>(0))
											)
										)
									)
								)
							)
						)
					)
				)
			)
		);

		final GrpcHierarchy entitySelfHierarchy = GrpcHierarchyStatisticsBuilder.buildHierarchy(entityHierarchy.getSelfHierarchy(), null);

		final Map<String, GrpcHierarchy> entityHierarchies = new HashMap<>(entityHierarchy.getReferenceHierarchies().size());
		for (Entry<String, Map<String, List<LevelInfo>>> entry : entityHierarchy.getReferenceHierarchies().entrySet()) {
			entityHierarchies.put(
				entry.getKey(),
				GrpcHierarchyStatisticsBuilder.buildHierarchy(entry.getValue(), null)
			);
		}

		GrpcAssertions.assertHierarchy(entityHierarchy, entitySelfHierarchy, entityHierarchies);
	}

	@Nonnull
	private static Entity createHierarchyEntity(int primaryKey) {
		return new InitialEntityBuilder(Entities.CATEGORY, primaryKey).toInstance();
	}

	@Nonnull
	private static EntityReference createHierachyEntityReference(int primaryKey) {
		return new EntityReference(Entities.CATEGORY, primaryKey);
	}

	@Nonnull
	public static SealedEntity createHierarchyEntity(@Nonnull String type, int pk, @Nonnull String code) {
		return new InitialEntityBuilder(
			new InternalEntitySchemaBuilder(
				CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, Map.of(), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
				EntitySchema._internalBuild(type)
			)
				.withAttribute("code", String.class)
				.toInstance(),
			pk
		)
			.setAttribute("code", code)
			.toInstance();
	}

}
