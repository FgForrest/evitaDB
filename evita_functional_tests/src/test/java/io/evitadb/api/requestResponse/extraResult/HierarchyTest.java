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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This test class verifies contract of the {@link Hierarchy} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class HierarchyTest {
	private LevelInfo levelInfo;

	@BeforeEach
	void setUp() {
		// create hierarchy of level infos to be used in the tests
		this.levelInfo = new LevelInfo(
			new EntityReference("category", 1),
			false,
			5, 2,
			List.of(
				new LevelInfo(
					new EntityReference("category", 2),
					true,
					4, 1,
					List.of(
						new LevelInfo(
							new EntityReference("category", 3),
							false,
							3, 0,
							List.of(
								new LevelInfo(
									new EntityReference("category", 4),
									false,
									2, 0,
									List.of(
										new LevelInfo(
											new EntityReference("category", 5),
											false,
											1, 0,
											List.of()
										)
									)
								)
							)
						)
					)
				),
				new LevelInfo(
					new EntityReference("category", 6),
					false,
					4, 1,
					List.of(
						new LevelInfo(
							new EntityReference("category", 7),
							false,
							3, 0,
							List.of(
								new LevelInfo(
									new EntityReference("category", 8),
									false,
									2, 0,
									List.of(
										new LevelInfo(
											new EntityReference("category", 9),
											false,
											1, 0,
											List.of()
										)
									)
								)
							)
						)
					)
				)
			)
		);
	}

	@Test
	void shouldCollectAllHierarchyLevelInfos() {
		final Set<Integer> allIds = this.levelInfo.collectAll(li -> true)
			.map(it -> it.entity().getPrimaryKey())
			.collect(Collectors.toSet());
		Assertions.assertEquals(9, allIds.size());
	}

	@Test
	void shouldCollectCertainHierarchyLevelInfos() {
		final Set<Integer> allIds = this.levelInfo.collectAll(li -> li.entity().getPrimaryKey() % 2 == 0)
			.map(it -> it.entity().getPrimaryKey())
			.collect(Collectors.toSet());
		Assertions.assertEquals(4, allIds.size());
	}

}
