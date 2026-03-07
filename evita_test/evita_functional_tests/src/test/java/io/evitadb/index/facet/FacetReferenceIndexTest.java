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

package io.evitadb.index.facet;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetReferenceIndex} covering construction,
 * non-transactional operations, STM commit/rollback, and toString.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetReferenceIndex")
class FacetReferenceIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FacetReferenceIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxFacetId = 5;
		final int maxGroupId = 3;
		final int maxEntityId = 30;

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashMap<>(), new HashMap<>()),
			(random, testState) -> {
				final Map<Integer, Set<Integer>> noGroupRef = testState.noGroupFacets();
				final Map<Integer, Map<Integer, Set<Integer>>> groupedRef = testState.groupedFacets();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				codeBuffer.append("final FacetReferenceIndex index = new FacetReferenceIndex(\"ref\");\n");
				final FacetReferenceIndex index = buildReferenceIndex(noGroupRef, groupedRef, codeBuffer);

				assertStateAfterCommit(
					index,
					original -> {
						final int operationsInTransaction = random.nextInt(5) + 1;
						for (int i = 0; i < operationsInTransaction; i++) {
							final int noGroupSize = noGroupRef.values().stream().mapToInt(Set::size).sum();
							final int groupedSize = groupedRef.values().stream()
								.flatMap(m -> m.values().stream())
								.mapToInt(Set::size).sum();
							final int totalSize = noGroupSize + groupedSize;

							if (totalSize == 0 || (totalSize < maxFacetId * maxEntityId && random.nextBoolean())) {
								// Add operation
								if (random.nextBoolean()) {
									// Add null-group facet — retry facetId until a slot with capacity is found.
									// Without this outer retry a full facet (existing.size() == maxEntityId)
									// would cause the entityId do-while to spin forever even when totalSize < limit.
									int facetId;
									Set<Integer> existing;
									do {
										facetId = random.nextInt(maxFacetId) + 1;
										existing = noGroupRef.getOrDefault(facetId, Set.of());
									} while (existing.size() >= maxEntityId);
									int entityId;
									do {
										entityId = random.nextInt(maxEntityId) + 1;
									} while (existing.contains(entityId));

									codeBuffer.append("index.addFacet(").append(facetId).append(", null, ").append(entityId).append(");\n");
									try {
										original.addFacet(facetId, null, entityId);
										noGroupRef.computeIfAbsent(facetId, k -> new HashSet<>()).add(entityId);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								} else {
									// Add grouped facet — same retry logic for (groupId, facetId) pairs.
									int facetId;
									int groupId;
									Set<Integer> existing;
									do {
										facetId = random.nextInt(maxFacetId) + 1;
										groupId = random.nextInt(maxGroupId) + 1;
										existing = groupedRef
											.getOrDefault(groupId, Map.of())
											.getOrDefault(facetId, Set.of());
									} while (existing.size() >= maxEntityId);
									int entityId;
									do {
										entityId = random.nextInt(maxEntityId) + 1;
									} while (existing.contains(entityId));

									codeBuffer.append("index.addFacet(").append(facetId).append(", ").append(groupId).append(", ").append(entityId).append(");\n");
									try {
										original.addFacet(facetId, groupId, entityId);
										groupedRef.computeIfAbsent(groupId, k -> new HashMap<>())
											.computeIfAbsent(facetId, k -> new HashSet<>())
											.add(entityId);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								}
							} else {
								// Remove operation
								if (!noGroupRef.isEmpty() && (groupedRef.isEmpty() || random.nextBoolean())) {
									// Remove null-group facet
									final ArrayList<Integer> facetIds = new ArrayList<>(noGroupRef.keySet());
									final int facetId = facetIds.get(random.nextInt(facetIds.size()));
									final ArrayList<Integer> entityIds = new ArrayList<>(noGroupRef.get(facetId));
									final int entityId = entityIds.get(random.nextInt(entityIds.size()));

									codeBuffer.append("index.removeFacet(").append(facetId).append(", null, ").append(entityId).append(");\n");
									try {
										original.removeFacet(facetId, null, entityId);
										final Set<Integer> facetEntities = noGroupRef.get(facetId);
										facetEntities.remove(entityId);
										if (facetEntities.isEmpty()) {
											noGroupRef.remove(facetId);
										}
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								} else if (!groupedRef.isEmpty()) {
									// Remove grouped facet
									final ArrayList<Integer> groupIds = new ArrayList<>(groupedRef.keySet());
									final int groupId = groupIds.get(random.nextInt(groupIds.size()));
									final Map<Integer, Set<Integer>> facetMap = groupedRef.get(groupId);
									final ArrayList<Integer> facetIds = new ArrayList<>(facetMap.keySet());
									final int facetId = facetIds.get(random.nextInt(facetIds.size()));
									final ArrayList<Integer> entityIds = new ArrayList<>(facetMap.get(facetId));
									final int entityId = entityIds.get(random.nextInt(entityIds.size()));

									codeBuffer.append("index.removeFacet(").append(facetId).append(", ").append(groupId).append(", ").append(entityId).append(");\n");
									try {
										original.removeFacet(facetId, groupId, entityId);
										final Set<Integer> facetEntities = facetMap.get(facetId);
										facetEntities.remove(entityId);
										if (facetEntities.isEmpty()) {
											facetMap.remove(facetId);
											if (facetMap.isEmpty()) {
												groupedRef.remove(groupId);
											}
										}
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								}
							}
						}
					},
					(original, committed) -> {
						final int noGroupSize = noGroupRef.values().stream().mapToInt(Set::size).sum();
						final int groupedSize = groupedRef.values().stream()
							.flatMap(m -> m.values().stream())
							.mapToInt(Set::size).sum();
						assertEquals(noGroupSize + groupedSize, committed.size(),
							"Size mismatch after commit!\n" + codeBuffer);
						assertEquals(noGroupRef.isEmpty() && groupedRef.isEmpty(), committed.isEmpty(),
							"isEmpty mismatch after commit!\n" + codeBuffer);

						// Verify no-group facets
						if (noGroupRef.isEmpty()) {
							assertNull(committed.getNotGroupedFacets(),
								"Expected null notGroupedFacets but was non-null!\n" + codeBuffer);
						} else {
							assertNotNull(committed.getNotGroupedFacets(),
								"Expected non-null notGroupedFacets!\n" + codeBuffer);
							for (Map.Entry<Integer, Set<Integer>> entry : noGroupRef.entrySet()) {
								final FacetIdIndex fi = committed.getNotGroupedFacets().getFacetIdIndex(entry.getKey());
								assertNotNull(fi,
									"FacetIdIndex for no-group facetId " + entry.getKey() + " not found!\n" + codeBuffer);
								for (int entityId : entry.getValue()) {
									assertTrue(fi.getRecords().contains(entityId),
										"Entity ID " + entityId + " not found in no-group facetId " + entry.getKey() + "!\n" + codeBuffer);
								}
							}
						}

						// Verify grouped facets
						for (Map.Entry<Integer, Map<Integer, Set<Integer>>> groupEntry : groupedRef.entrySet()) {
							final int groupId = groupEntry.getKey();
							final FacetGroupIndex facetGroupIndex = committed.getFacetsInGroup(groupId);
							assertNotNull(facetGroupIndex,
								"FacetGroupIndex for groupId " + groupId + " not found!\n" + codeBuffer);
							for (Map.Entry<Integer, Set<Integer>> facetEntry : groupEntry.getValue().entrySet()) {
								final int facetId = facetEntry.getKey();
								assertTrue(committed.isFacetInGroup(groupId, facetId),
									"Facet " + facetId + " not found in group " + groupId + "!\n" + codeBuffer);
								final FacetIdIndex fi = facetGroupIndex.getFacetIdIndex(facetId);
								assertNotNull(fi,
									"FacetIdIndex for grouped facetId " + facetId + " in group " + groupId + " not found!\n" + codeBuffer);
								for (int entityId : facetEntry.getValue()) {
									assertTrue(fi.getRecords().contains(entityId),
										"Entity ID " + entityId + " not found in grouped facetId " + facetId + " in group " + groupId + "!\n" + codeBuffer);
								}
							}
						}
					}
				);

				return new TestState(new StringBuilder(512), noGroupRef, groupedRef);
			}
		);
	}

	@Nonnull
	private static FacetReferenceIndex buildReferenceIndex(
		@Nonnull Map<Integer, Set<Integer>> noGroupRef,
		@Nonnull Map<Integer, Map<Integer, Set<Integer>>> groupedRef,
		@Nonnull StringBuilder codeBuffer
	) {
		final FacetReferenceIndex index = new FacetReferenceIndex("ref");
		for (Map.Entry<Integer, Set<Integer>> entry : noGroupRef.entrySet()) {
			for (int entityId : entry.getValue()) {
				codeBuffer.append("index.addFacet(").append(entry.getKey()).append(", null, ").append(entityId).append(");\n");
				index.addFacet(entry.getKey(), null, entityId);
			}
		}
		for (Map.Entry<Integer, Map<Integer, Set<Integer>>> groupEntry : groupedRef.entrySet()) {
			for (Map.Entry<Integer, Set<Integer>> facetEntry : groupEntry.getValue().entrySet()) {
				for (int entityId : facetEntry.getValue()) {
					codeBuffer.append("index.addFacet(").append(facetEntry.getKey()).append(", ").append(groupEntry.getKey()).append(", ").append(entityId).append(");\n");
					index.addFacet(facetEntry.getKey(), groupEntry.getKey(), entityId);
				}
			}
		}
		return index;
	}

	private record TestState(
		StringBuilder code,
		Map<Integer, Set<Integer>> noGroupFacets,
		Map<Integer, Map<Integer, Set<Integer>>> groupedFacets
	) {}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"empty constructor: no-group null, grouped empty"
		)
		void shouldCreateEmpty() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertNull(index.getNotGroupedFacets());
			assertTrue(index.getGroupedFacets().isEmpty());
			assertTrue(index.isEmpty());
			assertEquals("ref", index.getReferenceName());
		}

		@Test
		@DisplayName(
			"collection constructor with no-group index"
		)
		void shouldCreateWithNoGroupIndex() {
			final FacetIdIndex fi =
				new FacetIdIndex(1, new BaseBitmap(10));
			final FacetGroupIndex noGroup =
				new FacetGroupIndex(List.of(fi));
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref", List.of(noGroup));
			assertNotNull(index.getNotGroupedFacets());
			assertEquals(1, index.size());
		}

		@Test
		@DisplayName(
			"collection constructor with multiple groups"
		)
		void shouldCreateWithMultipleGroups() {
			final FacetIdIndex fi1 =
				new FacetIdIndex(1, new BaseBitmap(10));
			final FacetGroupIndex group1 =
				new FacetGroupIndex(100, List.of(fi1));
			final FacetIdIndex fi2 =
				new FacetIdIndex(2, new BaseBitmap(20));
			final FacetGroupIndex group2 =
				new FacetGroupIndex(200, List.of(fi2));
			final FacetReferenceIndex index =
				new FacetReferenceIndex(
					"ref", List.of(group1, group2)
				);
			assertNull(index.getNotGroupedFacets());
			assertEquals(2, index.getGroupedFacets().size());
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName(
			"two no-group indexes → assertion error"
		)
		void shouldRejectTwoNoGroupIndexes() {
			final FacetGroupIndex noGroup1 =
				new FacetGroupIndex();
			final FacetGroupIndex noGroup2 =
				new FacetGroupIndex();
			assertThrows(
				Exception.class,
				() -> new FacetReferenceIndex(
					"ref", List.of(noGroup1, noGroup2)
				)
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional operations (T8)")
	class NonTransactionalTest {

		@Test
		@DisplayName(
			"addFacet with null group creates no-group index"
		)
		void shouldCreateNoGroupOnAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.addFacet(10, null, 100));
			assertNotNull(index.getNotGroupedFacets());
			assertEquals(1, index.size());
		}

		@Test
		@DisplayName(
			"addFacet null group reuses existing no-group"
		)
		void shouldReuseExistingNoGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.addFacet(20, null, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName("addFacet with groupId creates group")
		void shouldCreateGroupOnAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.addFacet(10, 1, 100));
			assertNotNull(index.getFacetsInGroup(1));
		}

		@Test
		@DisplayName(
			"addFacet with groupId reuses existing group"
		)
		void shouldReuseExistingGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 1, 100);
			index.addFacet(20, 1, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName(
			"removeFacet when notGroupedFacets is null → throws"
		)
		void shouldThrowWhenNoGroupNull() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertThrows(
				Exception.class,
				() -> index.removeFacet(10, null, 100)
			);
		}

		@Test
		@DisplayName(
			"removeFacet when group not found → throws"
		)
		void shouldThrowWhenGroupNotFound() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertThrows(
				Exception.class,
				() -> index.removeFacet(10, 999, 100)
			);
		}

		@Test
		@DisplayName(
			"removeFacet drains no-group → set to null"
		)
		void shouldSetNoGroupToNullWhenDrained() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.removeFacet(10, null, 100);
			assertNull(index.getNotGroupedFacets());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"removeFacet drains group → removed from map"
		)
		void shouldRemoveGroupWhenDrained() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 1, 100);
			index.removeFacet(10, 1, 100);
			assertNull(index.getFacetsInGroup(1));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("isEmpty and size tracking")
		void shouldTrackEmptinessAndSize() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.isEmpty());
			assertEquals(0, index.size());
			index.addFacet(10, null, 100);
			assertFalse(index.isEmpty());
			assertEquals(1, index.size());
			index.addFacet(20, 1, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName("getFacetsInGroup(null) returns no-group")
		void shouldReturnNoGroupFacets() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			assertNotNull(index.getFacetsInGroup(null));
		}

		@Test
		@DisplayName(
			"getFacetsInGroup(Integer) returns specific group"
		)
		void shouldReturnSpecificGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			assertNotNull(index.getFacetsInGroup(5));
			assertNull(index.getFacetsInGroup(999));
		}

		@Test
		@DisplayName("isFacetInGroup true/false paths")
		void shouldCheckFacetInGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			assertTrue(index.isFacetInGroup(5, 10));
			assertFalse(index.isFacetInGroup(5, 999));
			assertFalse(index.isFacetInGroup(999, 10));
		}
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit grouped addFacet: new group in committed (T1)"
		)
		void shouldCommitGroupedAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, 1, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
					assertNotNull(committed.getFacetsInGroup(1));
				}
			);
		}

		@Test
		@DisplayName(
			"commit no-group addFacet (T1)"
		)
		void shouldCommitNoGroupAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, null, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertNull(original.getNotGroupedFacets());
					assertNotNull(
						committed.getNotGroupedFacets()
					);
				}
			);
		}

		@Test
		@DisplayName(
			"commit removeFacet draining group (INV-12)"
		)
		void shouldCommitRemoveDrainingGroup() {
			final FacetIdIndex fi =
				new FacetIdIndex(10, new BaseBitmap(100));
			final FacetGroupIndex group =
				new FacetGroupIndex(1, List.of(fi));
			final FacetReferenceIndex index =
				new FacetReferenceIndex(
					"ref", List.of(group)
				);

			assertStateAfterCommit(
				index,
				original -> original.removeFacet(10, 1, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("T2: Original unchanged after commit")
		void shouldPreserveOriginalAfterCommit() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 1, 200);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertEquals(2, committed.size());
				}
			);
		}

		@Test
		@DisplayName(
			"T5: Add in no-group AND group in same tx"
		)
		void shouldCommitMixedChanges() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 5, 200);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertNotNull(
						committed.getNotGroupedFacets()
					);
					assertNotNull(committed.getFacetsInGroup(5));
					assertEquals(2, committed.size());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — Rollback")
	class RollbackTest {

		@Test
		@DisplayName(
			"no-group and grouped mutations discarded (T7)"
		)
		void shouldRollbackAllMutations() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterRollback(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 1, 200);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("Other")
	class OtherTest {

		@Test
		@DisplayName("toString with only no-group facets")
		void shouldFormatToStringNoGroupOnly() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			final String result = index.toString();
			assertTrue(
				result.contains("[NO_GROUP]"),
				"Expected [NO_GROUP] in toString but was: "
					+ result
			);
		}

		@Test
		@DisplayName("toString with only grouped facets")
		void shouldFormatToStringGroupedOnly() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			final String result = index.toString();
			assertTrue(
				result.contains("GROUP 5"),
				"Expected GROUP 5 in toString but was: "
					+ result
			);
		}

		@Test
		@DisplayName("toString with both no-group and grouped")
		void shouldFormatToStringBoth() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.addFacet(20, 5, 200);
			final String result = index.toString();
			assertTrue(result.contains("[NO_GROUP]"));
			assertTrue(result.contains("GROUP 5"));
		}
	}
}
