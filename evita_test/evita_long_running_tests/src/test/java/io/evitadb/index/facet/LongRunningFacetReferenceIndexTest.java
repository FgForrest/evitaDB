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

import io.evitadb.index.bitmap.Bitmap;
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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetReferenceIndex}. Besides the forward commit proof it also drives
 * the two atomic-rollback paths against an oracle: the transactional-discard rollback (Ref: #569) and the per-entity
 * savepoint rollback (Ref: #1252, exercised by the sibling {@code LongRunningSavepointFacetReferenceIndexTest}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetReferenceIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
@Tag(REFERENCE)
class LongRunningFacetReferenceIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_FACET_ID = 5;
	private static final int MAX_GROUP_ID = 3;
	private static final int MAX_ENTITY_ID = 30;

	@ParameterizedTest(name = "FacetReferenceIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
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
					original -> applyRandomBatch(random, original, noGroupRef, groupedRef, codeBuffer),
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

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * add/remove mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and no
	 * committed value was published.
	 */
	@ParameterizedTest(name = "FacetReferenceIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashMap<>(), new HashMap<>()),
			(random, testState) -> {
				final Map<Integer, Set<Integer>> noGroupRef = testState.noGroupFacets();
				final Map<Integer, Map<Integer, Set<Integer>>> groupedRef = testState.groupedFacets();
				final StringBuilder codeBuffer = testState.code();

				codeBuffer.append("final FacetReferenceIndex index = new FacetReferenceIndex(\"ref\");\n");
				final FacetReferenceIndex index = buildReferenceIndex(noGroupRef, groupedRef, codeBuffer);
				// value oracle of the base state that the rollback must return to
				final FacetSnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, noGroupRef, groupedRef, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"FacetReferenceIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(new StringBuilder(512), noGroupRef, groupedRef);
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove facet mutations to `index`, mirroring each mutation into the
	 * `noGroupRef` / `groupedRef` reference model so the two stay in lockstep. Shared by the commit and rollback proofs.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull FacetReferenceIndex index,
		@Nonnull Map<Integer, Set<Integer>> noGroupRef,
		@Nonnull Map<Integer, Map<Integer, Set<Integer>>> groupedRef,
		@Nonnull StringBuilder codeBuffer
	) {
		final int operationsInTransaction = random.nextInt(5) + 1;
		for (int i = 0; i < operationsInTransaction; i++) {
			final int noGroupSize = noGroupRef.values().stream().mapToInt(Set::size).sum();
			final int groupedSize = groupedRef.values().stream()
				.flatMap(m -> m.values().stream())
				.mapToInt(Set::size).sum();
			final int totalSize = noGroupSize + groupedSize;

			if (totalSize == 0 || (totalSize < MAX_FACET_ID * MAX_ENTITY_ID && random.nextBoolean())) {
				// Add operation
				if (random.nextBoolean()) {
					// Add null-group facet — retry facetId until a slot with capacity is found.
					// Without this outer retry a full facet (existing.size() == MAX_ENTITY_ID)
					// would cause the entityId do-while to spin forever even when totalSize < limit.
					int facetId;
					Set<Integer> existing;
					do {
						facetId = random.nextInt(MAX_FACET_ID) + 1;
						existing = noGroupRef.getOrDefault(facetId, Set.of());
					} while (existing.size() >= MAX_ENTITY_ID);
					int entityId;
					do {
						entityId = random.nextInt(MAX_ENTITY_ID) + 1;
					} while (existing.contains(entityId));

					codeBuffer.append("index.addFacet(").append(facetId).append(", null, ").append(entityId).append(");\n");
					try {
						index.addFacet(facetId, null, entityId);
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
						facetId = random.nextInt(MAX_FACET_ID) + 1;
						groupId = random.nextInt(MAX_GROUP_ID) + 1;
						existing = groupedRef
							.getOrDefault(groupId, Map.of())
							.getOrDefault(facetId, Set.of());
					} while (existing.size() >= MAX_ENTITY_ID);
					int entityId;
					do {
						entityId = random.nextInt(MAX_ENTITY_ID) + 1;
					} while (existing.contains(entityId));

					codeBuffer.append("index.addFacet(").append(facetId).append(", ").append(groupId).append(", ").append(entityId).append(");\n");
					try {
						index.addFacet(facetId, groupId, entityId);
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
						index.removeFacet(facetId, null, entityId);
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
						index.removeFacet(facetId, groupId, entityId);
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

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (facet → sorted entity ids), so two
	 * snapshots taken before and after a rollback can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static FacetSnapshot snapshot(@Nonnull FacetReferenceIndex index) {
		final Map<Integer, List<Integer>> noGroup = new HashMap<>();
		index.getNotGroupedFacetsAsMap().ifPresent(map -> {
			for (final Entry<Integer, Bitmap> entry : map.entrySet()) {
				noGroup.put(entry.getKey(), toList(entry.getValue()));
			}
		});
		final Map<Integer, Map<Integer, List<Integer>>> grouped = new HashMap<>();
		for (final Entry<Integer, Map<Integer, Bitmap>> groupEntry : index.getGroupsAsMap().entrySet()) {
			final Map<Integer, List<Integer>> facets = new HashMap<>();
			for (final Entry<Integer, Bitmap> facetEntry : groupEntry.getValue().entrySet()) {
				facets.put(facetEntry.getKey(), toList(facetEntry.getValue()));
			}
			grouped.put(groupEntry.getKey(), facets);
		}
		return new FacetSnapshot(noGroup, grouped);
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull Bitmap bitmap) {
		final int[] array = bitmap.getArray();
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	private record TestState(
		StringBuilder code,
		Map<Integer, Set<Integer>> noGroupFacets,
		Map<Integer, Map<Integer, Set<Integer>>> groupedFacets
	) {}

	/**
	 * Value-comparable snapshot of a {@link FacetReferenceIndex}: no-group facets (facetId → sorted entity ids) and
	 * grouped facets (groupId → facetId → sorted entity ids). Record equality gives deep structural comparison.
	 */
	record FacetSnapshot(
		@Nonnull Map<Integer, List<Integer>> noGroup,
		@Nonnull Map<Integer, Map<Integer, List<Integer>>> grouped
	) {}

}
