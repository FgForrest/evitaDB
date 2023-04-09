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

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfos;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This test verifies functionalities of methods in {@link GrpcHierarchyStatisticsBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
class GrpcHierarchyStatisticsBuilderTest {

	@Test
	void buildHierarchyStatistics() {
		final String[] types = new String[]{"test1", "test2", "test3"};
		final HierarchyStatistics integerHierarchyStatistics = new HierarchyStatistics(
			null,
			Map.of(
				types[0],
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(new EntityReference(Entities.CATEGORY, 1), 1, 0,
							List.of(
								new LevelInfo(new EntityReference(Entities.CATEGORY, 1), 1, 0, new ArrayList<>(0))
							)
						)
					)
				),
				types[1],
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(new EntityReference(Entities.CATEGORY, 2), 0, 0, new ArrayList<>(0))
					)
				),
				types[2],
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(
							new EntityReference(Entities.CATEGORY, 3), 2, 0,
							List.of(
								new LevelInfo(
									new EntityReference(Entities.CATEGORY, 1), 1, 0,
									List.of(
										new LevelInfo(
											new EntityReference(Entities.CATEGORY, 2), 4, 0,
											List.of(
												new LevelInfo(new EntityReference(Entities.CATEGORY, 5), 0, 0, new ArrayList<>(0))
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
		final HierarchyStatistics entityHierarchyStatistics = new HierarchyStatistics(
			null,
			Map.of(
				types[0],
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(new InitialEntityBuilder(Entities.CATEGORY, 1).toInstance(), 1, 0,
							List.of(
								new LevelInfo(new InitialEntityBuilder(Entities.CATEGORY, 6).toInstance(), 1, 0, new ArrayList<>(0))
							)
						)
					)
				),
				types[1],
				Map.of(
					"megaMenu",
					List.of(new LevelInfo(new InitialEntityBuilder(Entities.CATEGORY, 2).toInstance(), 0, 0, new ArrayList<>(0)))
				),
				types[2],
				Map.of(
					"megaMenu",
					List.of(
						new LevelInfo(
							new InitialEntityBuilder(Entities.CATEGORY, 3).toInstance(), 2, 0,
							List.of(
								new LevelInfo(
									new InitialEntityBuilder(Entities.CATEGORY, 9).toInstance(), 1, 0,
									List.of(
										new LevelInfo(
											new InitialEntityBuilder(Entities.CATEGORY, 4).toInstance(), 4, 0,
											List.of(
												new LevelInfo(new InitialEntityBuilder(Entities.CATEGORY, 7).toInstance(), 0, 0, new ArrayList<>(0))
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

		final Map<String, GrpcLevelInfos> integerGrpcLevelInfos = new HashMap<>(integerHierarchyStatistics.getStatistics().size());
		for (Entry<String, Map<String, List<LevelInfo>>> entry : integerHierarchyStatistics.getStatistics().entrySet()) {
			// TODO LHO - alter structure
			integerGrpcLevelInfos.put(
				entry.getKey(),
				GrpcHierarchyStatisticsBuilder.buildHierarchyStatistics(entry.getValue())
			);
		}

		GrpcAssertions.assertStatistics(integerHierarchyStatistics, integerGrpcLevelInfos, types[2]);

		final Map<String, GrpcLevelInfos> entityGrpcLevelInfos = new HashMap<>(entityHierarchyStatistics.getStatistics().size());
		for (Entry<String, Map<String, List<LevelInfo>>> entry : entityHierarchyStatistics.getStatistics().entrySet()) {
			// TODO LHO - alter structure
			entityGrpcLevelInfos.put(
				entry.getKey(),
				GrpcHierarchyStatisticsBuilder.buildHierarchyStatistics(entry.getValue())
			);
		}

		GrpcAssertions.assertStatistics(entityHierarchyStatistics, entityGrpcLevelInfos, types[2]);
	}
}