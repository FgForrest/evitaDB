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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
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
 * Tests for {@link FacetGroupIndex} covering construction,
 * non-transactional operations, STM commit/rollback,
 * and toString formatting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetGroupIndex")
class FacetGroupIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FacetGroupIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxFacetId = 10;
		final int maxEntityId = 50;

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashMap<>()),
			(random, testState) -> {
				final Map<Integer, Set<Integer>> reference = testState.facetToEntities();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				codeBuffer.append("final FacetGroupIndex index = new FacetGroupIndex();\n");
				final FacetGroupIndex index = buildGroupIndex(reference, codeBuffer);

				assertStateAfterCommit(
					index,
					original -> {
						final int operationsInTransaction = random.nextInt(5) + 1;
						for (int i = 0; i < operationsInTransaction; i++) {
							final int totalSize = reference.values().stream().mapToInt(Set::size).sum();
							if (reference.isEmpty() || (totalSize < maxFacetId * 5 && random.nextBoolean())) {
								// Add: pick (facetId, entityId) not already in reference
								final int facetId = random.nextInt(maxFacetId) + 1;
								final Set<Integer> existing = reference.getOrDefault(facetId, Set.of());
								int entityId;
								do {
									entityId = random.nextInt(maxEntityId) + 1;
								} while (existing.contains(entityId));

								codeBuffer.append("index.addFacet(").append(facetId).append(", ").append(entityId).append(");\n");
								try {
									original.addFacet(facetId, entityId);
									reference.computeIfAbsent(facetId, k -> new HashSet<>()).add(entityId);
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							} else if (!reference.isEmpty()) {
								// Remove: pick existing (facetId, entityId)
								final ArrayList<Integer> facetIds = new ArrayList<>(reference.keySet());
								final int facetId = facetIds.get(random.nextInt(facetIds.size()));
								final ArrayList<Integer> entityIds = new ArrayList<>(reference.get(facetId));
								final int entityId = entityIds.get(random.nextInt(entityIds.size()));

								codeBuffer.append("index.removeFacet(").append(facetId).append(", ").append(entityId).append(");\n");
								try {
									original.removeFacet(facetId, entityId);
									final Set<Integer> facetEntities = reference.get(facetId);
									facetEntities.remove(entityId);
									if (facetEntities.isEmpty()) {
										reference.remove(facetId);
									}
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							}
						}
					},
					(original, committed) -> {
						final int expectedTotal = reference.values().stream().mapToInt(Set::size).sum();
						assertEquals(expectedTotal, committed.size(),
							"Size mismatch after commit!\n" + codeBuffer);
						assertEquals(reference.isEmpty(), committed.isEmpty(),
							"isEmpty mismatch after commit!\n" + codeBuffer);
						for (Map.Entry<Integer, Set<Integer>> entry : reference.entrySet()) {
							final FacetIdIndex fi = committed.getFacetIdIndex(entry.getKey());
							assertNotNull(fi,
								"FacetIdIndex for facetId " + entry.getKey() + " not found!\n" + codeBuffer);
							for (int entityId : entry.getValue()) {
								assertTrue(fi.getRecords().contains(entityId),
									"Entity ID " + entityId + " not found in FacetIdIndex for facetId " + entry.getKey() + "!\n" + codeBuffer);
							}
						}
					}
				);

				return new TestState(new StringBuilder(512), reference);
			}
		);
	}

	@Nonnull
	private static FacetGroupIndex buildGroupIndex(
		@Nonnull Map<Integer, Set<Integer>> reference,
		@Nonnull StringBuilder codeBuffer
	) {
		final FacetGroupIndex index = new FacetGroupIndex();
		for (Map.Entry<Integer, Set<Integer>> entry : reference.entrySet()) {
			for (int entityId : entry.getValue()) {
				codeBuffer.append("index.addFacet(").append(entry.getKey()).append(", ").append(entityId).append(");\n");
				index.addFacet(entry.getKey(), entityId);
			}
		}
		return index;
	}

	private record TestState(
		StringBuilder code,
		Map<Integer, Set<Integer>> facetToEntities
	) {}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("no-arg constructor: groupId is null")
		void shouldCreateWithNullGroupId() {
			final FacetGroupIndex index = new FacetGroupIndex();
			assertNull(index.getGroupId());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("single-groupId constructor")
		void shouldCreateWithGroupId() {
			final FacetGroupIndex index =
				new FacetGroupIndex(42);
			assertEquals(42, index.getGroupId());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"collection constructor with no-group variant"
		)
		void shouldCreateWithCollectionNoGroup() {
			final FacetIdIndex fi =
				new FacetIdIndex(1, new BaseBitmap(10, 20));
			final FacetGroupIndex index =
				new FacetGroupIndex(List.of(fi));
			assertNull(index.getGroupId());
			assertFalse(index.isEmpty());
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName(
			"collection constructor with groupId + FacetIdIndexes"
		)
		void shouldCreateWithGroupIdAndCollection() {
			final FacetIdIndex fi1 =
				new FacetIdIndex(1, new BaseBitmap(10));
			final FacetIdIndex fi2 =
				new FacetIdIndex(2, new BaseBitmap(20, 30));
			final FacetGroupIndex index =
				new FacetGroupIndex(5, List.of(fi1, fi2));
			assertEquals(5, index.getGroupId());
			assertEquals(3, index.size());
		}
	}

	@Nested
	@DisplayName("Non-transactional operations (T8)")
	class NonTransactionalTest {

		@Test
		@DisplayName(
			"addFacet creates new FacetIdIndex for unknown facet"
		)
		void shouldCreateNewFacetIdIndex() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			final FacetIdIndex fi = index.getFacetIdIndex(10);
			assertNotNull(fi);
			assertEquals(1, fi.size());
		}

		@Test
		@DisplayName("addFacet returns true for new entity ID")
		void shouldReturnTrueForNewEntityId() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			assertTrue(index.addFacet(10, 100));
		}

		@Test
		@DisplayName(
			"addFacet returns false for duplicate entity ID"
		)
		void shouldReturnFalseForDuplicateEntityId() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			assertFalse(index.addFacet(10, 100));
		}

		@Test
		@DisplayName(
			"removeFacet when FacetIdIndex not found throws"
		)
		void shouldThrowWhenFacetNotFound() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			assertThrows(
				Exception.class,
				() -> index.removeFacet(999, 100)
			);
		}

		@Test
		@DisplayName(
			"removeFacet removes FacetIdIndex when empty"
		)
		void shouldRemoveFacetIdIndexWhenEmpty() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			index.removeFacet(10, 100);
			assertNull(index.getFacetIdIndex(10));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("isEmpty and size tracking")
		void shouldTrackEmptinessAndSize() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			assertTrue(index.isEmpty());
			assertEquals(0, index.size());

			index.addFacet(10, 100);
			assertFalse(index.isEmpty());
			assertEquals(1, index.size());

			index.addFacet(10, 200);
			assertEquals(2, index.size());

			index.addFacet(20, 300);
			assertEquals(3, index.size());
		}

		@Test
		@DisplayName(
			"getFacetIdIndex returns null for unknown facet"
		)
		void shouldReturnNullForUnknownFacet() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			assertNull(index.getFacetIdIndex(999));
		}

		@Test
		@DisplayName(
			"getFacetIdIndex returns correct index for known"
		)
		void shouldReturnCorrectFacetIdIndex() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			final FacetIdIndex fi = index.getFacetIdIndex(10);
			assertNotNull(fi);
			assertTrue(fi.getRecords().contains(100));
		}

		@Test
		@DisplayName(
			"getFacetIdIndexesAsArray returns EmptyBitmap for unknown"
		)
		void shouldReturnEmptyBitmapForUnknownPks() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			final Bitmap[] result =
				index.getFacetIdIndexesAsArray(
					new BaseBitmap(999)
				);
			assertEquals(1, result.length);
			assertSame(EmptyBitmap.INSTANCE, result[0]);
		}

		@Test
		@DisplayName("getAsMap returns correct map view")
		void shouldReturnCorrectMapView() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);
			index.addFacet(10, 100);
			index.addFacet(20, 200);
			final Map<Integer, Bitmap> map = index.getAsMap();
			assertEquals(2, map.size());
			assertTrue(map.containsKey(10));
			assertTrue(map.containsKey(20));
		}
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit with addFacet: new data visible (T1)"
		)
		void shouldCommitAddFacet() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(
						1, committed.size()
					);
					assertNotNull(
						committed.getFacetIdIndex(10)
					);
				}
			);
		}

		@Test
		@DisplayName(
			"commit with removeFacet draining last entity (INV-12)"
		)
		void shouldCommitRemoveFacet() {
			final FacetIdIndex fi =
				new FacetIdIndex(10, new BaseBitmap(100));
			final FacetGroupIndex index =
				new FacetGroupIndex(1, List.of(fi));

			assertStateAfterCommit(
				index,
				original -> original.removeFacet(10, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName(
			"committed result is NEW instance (INV-7)"
		)
		void shouldReturnNewInstanceOnCommit() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, 100),
				(original, committed) ->
					assertNotSame(original, committed)
			);
		}

		@Test
		@DisplayName("T2: original unchanged after commit")
		void shouldPreserveOriginalAfterCommit() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, 100),
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName(
			"T5: add + modify in same tx → both committed"
		)
		void shouldCommitMultipleChanges() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(10, 100);
					original.addFacet(10, 200);
					original.addFacet(20, 300);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertEquals(3, committed.size());
					final FacetIdIndex fi10 =
						committed.getFacetIdIndex(10);
					assertNotNull(fi10);
					assertEquals(2, fi10.size());
					assertNotNull(
						committed.getFacetIdIndex(20)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — Rollback")
	class RollbackTest {

		@Test
		@DisplayName(
			"mutations do not survive rollback (T7)"
		)
		void shouldRollbackMutations() {
			final FacetGroupIndex index =
				new FacetGroupIndex(1);

			assertStateAfterRollback(
				index,
				original -> {
					original.addFacet(10, 100);
					original.addFacet(20, 200);
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
		@DisplayName(
			"toString for null groupId → [NO_GROUP]: prefix"
		)
		void shouldFormatToStringNoGroup() {
			final FacetGroupIndex index =
				new FacetGroupIndex();
			final String result = index.toString();
			assertTrue(
				result.startsWith("[NO_GROUP]:"),
				"Expected [NO_GROUP]: prefix but was: "
					+ result
			);
		}

		@Test
		@DisplayName(
			"toString for numeric groupId → GROUP <id>: prefix"
		)
		void shouldFormatToStringWithGroup() {
			final FacetGroupIndex index =
				new FacetGroupIndex(42);
			final String result = index.toString();
			assertTrue(
				result.startsWith("GROUP 42:"),
				"Expected GROUP 42: prefix but was: "
					+ result
			);
		}
	}
}
