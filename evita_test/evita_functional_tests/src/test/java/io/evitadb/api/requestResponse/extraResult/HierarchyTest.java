/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link Hierarchy} contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Hierarchy")
public class HierarchyTest implements EvitaTestSupport {
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

	@Nested
	@DisplayName("LevelInfo collectAll")
	class LevelInfoCollectAll {

		@Test
		@DisplayName("should collect all hierarchy level infos")
		void shouldCollectAllHierarchyLevelInfos() {
			final Set<Integer> allIds = levelInfo.collectAll(li -> true)
				.map(it -> it.entity().getPrimaryKey())
				.collect(Collectors.toSet());
			assertEquals(9, allIds.size());
		}

		@Test
		@DisplayName("should collect certain hierarchy level infos")
		void shouldCollectCertainHierarchyLevelInfos() {
			final Set<Integer> allIds = levelInfo.collectAll(li -> li.entity().getPrimaryKey() % 2 == 0)
				.map(it -> it.entity().getPrimaryKey())
				.collect(Collectors.toSet());
			assertEquals(4, allIds.size());
		}
	}

	@Nested
	@DisplayName("Hierarchy getters")
	class HierarchyGetters {

		@Test
		@DisplayName("should return self hierarchy")
		void shouldReturnSelfHierarchy() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final Map<String, List<LevelInfo>> selfHierarchy = hierarchy.getSelfHierarchy();
			assertNotNull(selfHierarchy);
			assertEquals(1, selfHierarchy.size());
			assertTrue(selfHierarchy.containsKey("megaMenu"));
		}

		@Test
		@DisplayName("should return self hierarchy by output name")
		void shouldReturnSelfHierarchyByOutputName() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final List<LevelInfo> result = hierarchy.getSelfHierarchy("megaMenu");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should return empty list for unknown output name")
		void shouldReturnEmptyListForUnknownOutputName() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final List<LevelInfo> result = hierarchy.getSelfHierarchy("nonExistent");
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return empty map when self statistics is null")
		void shouldReturnEmptyMapWhenSelfStatisticsIsNull() {
			final Hierarchy hierarchy = new Hierarchy(null, Collections.emptyMap());
			assertTrue(hierarchy.getSelfHierarchy().isEmpty());
		}

		@Test
		@DisplayName("should return reference hierarchy")
		void shouldReturnReferenceHierarchy() {
			final Hierarchy hierarchy = new Hierarchy(
				null,
				Map.of("categories", Map.of("megaMenu", List.of(levelInfo)))
			);
			final List<LevelInfo> result = hierarchy.getReferenceHierarchy("categories", "megaMenu");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should return empty list for unknown reference hierarchy")
		void shouldReturnEmptyListForUnknownReferenceHierarchy() {
			final Hierarchy hierarchy = new Hierarchy(null, Collections.emptyMap());
			assertTrue(hierarchy.getReferenceHierarchy("nonExistent", "megaMenu").isEmpty());
		}

		@Test
		@DisplayName("should return reference hierarchies map")
		void shouldReturnReferenceHierarchiesMap() {
			final Hierarchy hierarchy = new Hierarchy(
				null,
				Map.of("categories", Map.of("megaMenu", List.of(levelInfo)))
			);
			final Map<String, Map<String, List<LevelInfo>>> refs = hierarchy.getReferenceHierarchies();
			assertEquals(1, refs.size());
			assertTrue(refs.containsKey("categories"));
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final Hierarchy one = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final Hierarchy two = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should be equal to itself")
		void shouldBeEqualToItself() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			assertEquals(hierarchy, hierarchy);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			assertNotEquals(null, hierarchy);
		}

		@Test
		@DisplayName("should not be equal when different self statistics")
		void shouldNotBeEqualWhenDifferentSelfStatistics() {
			final Hierarchy one = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final LevelInfo otherLevelInfo = new LevelInfo(
				new EntityReference("category", 99),
				false, 1, 0, List.of()
			);
			final Hierarchy two = new Hierarchy(
				Map.of("megaMenu", List.of(otherLevelInfo)),
				Collections.emptyMap()
			);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("should handle null vs non-null self statistics")
		void shouldHandleNullVsNonNullSelfStatistics() {
			final Hierarchy withNull = new Hierarchy(null, Collections.emptyMap());
			final Hierarchy withData = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			assertNotEquals(withNull, withData);
			assertNotEquals(withData, withNull);
		}

		@Test
		@DisplayName("should not be equal when different number of reference hierarchies")
		void shouldNotBeEqualWhenDifferentNumberOfReferenceHierarchies() {
			final Hierarchy fewer = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Map.of("categories", Map.of("megaMenu", List.of(levelInfo)))
			);
			final Hierarchy more = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Map.of(
					"categories", Map.of("megaMenu", List.of(levelInfo)),
					"brands", Map.of("megaMenu", List.of(levelInfo))
				)
			);
			assertNotEquals(fewer, more);
			assertNotEquals(more, fewer);
		}

		@Test
		@DisplayName("should not be equal when different number of self statistics entries")
		void shouldNotBeEqualWhenDifferentNumberOfSelfStatisticsEntries() {
			final LevelInfo otherLevelInfo = new LevelInfo(
				new EntityReference("category", 99),
				false, 1, 0, List.of()
			);
			final Hierarchy fewer = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final Hierarchy more = new Hierarchy(
				Map.of(
					"megaMenu", List.of(levelInfo),
					"sideMenu", List.of(otherLevelInfo)
				),
				Collections.emptyMap()
			);
			assertNotEquals(fewer, more);
			assertNotEquals(more, fewer);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should produce non-empty string for self hierarchy")
		void shouldProduceNonEmptyStringForSelfHierarchy() {
			final Hierarchy hierarchy = new Hierarchy(
				Map.of("megaMenu", List.of(levelInfo)),
				Collections.emptyMap()
			);
			final String result = hierarchy.toString();
			assertNotNull(result);
			assertFalse(result.isEmpty());
			assertTrue(result.contains("megaMenu"));
		}

		@Test
		@DisplayName("should produce string for reference hierarchies")
		void shouldProduceStringForReferenceHierarchies() {
			final Hierarchy hierarchy = new Hierarchy(
				null,
				Map.of("categories", Map.of("megaMenu", List.of(levelInfo)))
			);
			final String result = hierarchy.toString();
			assertTrue(result.contains("categories"));
			assertTrue(result.contains("megaMenu"));
		}

		@Test
		@DisplayName("should produce empty string for empty hierarchy")
		void shouldProduceEmptyStringForEmptyHierarchy() {
			final Hierarchy hierarchy = new Hierarchy(null, Collections.emptyMap());
			final String result = hierarchy.toString();
			assertNotNull(result);
		}
	}

	@Nested
	@DisplayName("LevelInfo record")
	class LevelInfoTests {

		@Test
		@DisplayName("should store all fields correctly")
		void shouldStoreAllFieldsCorrectly() {
			final LevelInfo info = new LevelInfo(
				new EntityReference("category", 1),
				true, 10, 3, List.of()
			);
			assertEquals(new EntityReference("category", 1), info.entity());
			assertTrue(info.requested());
			assertEquals(10, info.queriedEntityCount());
			assertEquals(3, info.childrenCount());
			assertTrue(info.children().isEmpty());
		}

		@Test
		@DisplayName("should create from copy constructor")
		void shouldCreateFromCopyConstructor() {
			final LevelInfo original = new LevelInfo(
				new EntityReference("category", 1),
				true, 10, 3, List.of()
			);
			final LevelInfo child = new LevelInfo(
				new EntityReference("category", 2),
				false, 5, 0, List.of()
			);
			final LevelInfo copy = new LevelInfo(original, List.of(child));
			assertEquals(original.entity(), copy.entity());
			assertEquals(original.requested(), copy.requested());
			assertEquals(1, copy.children().size());
		}

		@Test
		@DisplayName("should produce toString with counts")
		void shouldProduceToStringWithCounts() {
			final LevelInfo info = new LevelInfo(
				new EntityReference("category", 1),
				false, 10, 3, List.of()
			);
			final String result = info.toString();
			assertTrue(result.contains("10"));
			assertTrue(result.contains("3"));
		}

		@Test
		@DisplayName("should produce toString without counts when null")
		void shouldProduceToStringWithoutCountsWhenNull() {
			final LevelInfo info = new LevelInfo(
				new EntityReference("category", 1),
				false, null, null, List.of()
			);
			final String result = info.toString();
			assertFalse(result.contains("["));
		}

		@Test
		@DisplayName("should show requested in toString")
		void shouldShowRequestedInToString() {
			final LevelInfo info = new LevelInfo(
				new EntityReference("category", 1),
				true, null, null, List.of()
			);
			assertTrue(info.toString().contains("(requested)"));
		}

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final LevelInfo one = new LevelInfo(
				new EntityReference("category", 1),
				true, 10, 3, List.of()
			);
			final LevelInfo two = new LevelInfo(
				new EntityReference("category", 1),
				true, 10, 3, List.of()
			);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}
	}
}
