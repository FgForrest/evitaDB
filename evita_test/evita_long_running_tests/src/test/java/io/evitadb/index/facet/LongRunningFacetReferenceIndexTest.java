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

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetReferenceIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetReferenceIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
@Tag(REFERENCE)
class LongRunningFacetReferenceIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FacetReferenceIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
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

}
