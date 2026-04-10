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

package io.evitadb.index.range;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionalRangePoint} covering construction,
 * non-transactional operations, dirty tracking, STM commit/rollback,
 * deepEquals, makeClone, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionalRangePoint")
class TransactionalRangePointTest implements TimeBoundedTestSupport {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("single-arg constructor: threshold set, starts/ends empty")
		void shouldInitializeWithThresholdOnly() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(100L);
			assertEquals(100L, point.getThreshold());
			assertTrue(point.getStarts().isEmpty());
			assertTrue(point.getEnds().isEmpty());
		}

		@Test
		@DisplayName("int-array constructor: all three fields set")
		void shouldInitializeWithIntArrays() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					50L, new int[]{1, 2, 3}, new int[]{4, 5}
				);
			assertEquals(50L, point.getThreshold());
			assertEquals(3, point.getStarts().size());
			assertTrue(point.getStarts().contains(1));
			assertTrue(point.getStarts().contains(2));
			assertTrue(point.getStarts().contains(3));
			assertEquals(2, point.getEnds().size());
			assertTrue(point.getEnds().contains(4));
			assertTrue(point.getEnds().contains(5));
		}

		@Test
		@DisplayName("bitmap constructor: starts/ends mirror input bitmaps")
		void shouldInitializeWithBitmaps() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L,
					new BaseBitmap(10, 20),
					new BaseBitmap(30, 40)
				);
			assertEquals(10L, point.getThreshold());
			assertEquals(2, point.getStarts().size());
			assertTrue(point.getStarts().contains(10));
			assertTrue(point.getStarts().contains(20));
			assertEquals(2, point.getEnds().size());
			assertTrue(point.getEnds().contains(30));
			assertTrue(point.getEnds().contains(40));
		}
	}

	@Nested
	@DisplayName("Dirty-tracking behavior")
	class DirtyTrackingTest {

		@Test
		@DisplayName("addStart marks dirty → new instance on commit")
		void shouldMarkDirtyOnAddStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addStart(10),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.getStarts().contains(10));
				}
			);
		}

		@Test
		@DisplayName("addEnd marks dirty → new instance on commit")
		void shouldMarkDirtyOnAddEnd() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addEnd(20),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.getEnds().contains(20));
				}
			);
		}

		@Test
		@DisplayName("addStarts marks dirty")
		void shouldMarkDirtyOnAddStarts() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addStarts(new int[]{1, 2, 3}),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(3, committed.getStarts().size());
				}
			);
		}

		@Test
		@DisplayName("addEnds marks dirty")
		void shouldMarkDirtyOnAddEnds() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			assertStateAfterCommit(
				point,
				original -> original.addEnds(new int[]{4, 5}),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(2, committed.getEnds().size());
				}
			);
		}

		@Test
		@DisplayName("removeStarts marks dirty")
		void shouldMarkDirtyOnRemoveStarts() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1, 2, 3}, new int[]{}
				);

			assertStateAfterCommit(
				point,
				original -> original.removeStarts(new int[]{2}),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(2, committed.getStarts().size());
					assertFalse(committed.getStarts().contains(2));
				}
			);
		}

		@Test
		@DisplayName("removeEnds marks dirty")
		void shouldMarkDirtyOnRemoveEnds() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{}, new int[]{10, 20}
				);

			assertStateAfterCommit(
				point,
				original -> original.removeEnds(new int[]{10}),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(1, committed.getEnds().size());
					assertFalse(committed.getEnds().contains(10));
				}
			);
		}

		@Test
		@DisplayName(
			"no mutations → same instance returned (INV-8)"
		)
		void shouldReturnSameInstanceWhenNotDirty() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);

			assertStateAfterCommit(
				point,
				original -> {
					// no mutations
				},
				Assertions::assertSame
			);
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName(
			"INV-6/INV-7: commit with mutations returns new instance"
		)
		void shouldReturnNewInstanceWithCorrectData() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					5L, new int[]{1, 2}, new int[]{3}
				);

			assertStateAfterCommit(
				point,
				original -> {
					original.addStart(10);
					original.addEnd(20);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(5L, committed.getThreshold());
					assertEquals(3, committed.getStarts().size());
					assertTrue(committed.getStarts().contains(10));
					assertEquals(2, committed.getEnds().size());
					assertTrue(committed.getEnds().contains(20));
				}
			);
		}

		@Test
		@DisplayName(
			"getMaintainedTransactionalCreators returns 3 elements"
		)
		void shouldReturnThreeMaintainedCreators() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			assertEquals(
				3,
				point.getMaintainedTransactionalCreators().size()
			);
		}
	}

	@Nested
	@DisplayName("Commit and rollback")
	class CommitAndRollbackTest {

		@Test
		@DisplayName(
			"commit: original unchanged, committed reflects mutations (T1, T2)"
		)
		void shouldPreserveOriginalOnCommit() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1}, new int[]{2}
				);

			assertStateAfterCommit(
				point,
				original -> {
					original.addStart(5);
					original.addEnd(6);
					original.removeStarts(new int[]{1});
				},
				(original, committed) -> {
					// original unchanged (T2)
					assertEquals(1, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					assertEquals(1, original.getEnds().size());
					assertTrue(original.getEnds().contains(2));
					// committed has all mutations
					assertEquals(1, committed.getStarts().size());
					assertTrue(committed.getStarts().contains(5));
					assertFalse(committed.getStarts().contains(1));
					assertEquals(2, committed.getEnds().size());
					assertTrue(committed.getEnds().contains(2));
					assertTrue(committed.getEnds().contains(6));
				}
			);
		}

		@Test
		@DisplayName(
			"rollback: mutations to starts/ends fully undone (T7)"
		)
		void shouldRollbackMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1, 2}, new int[]{3}
				);

			assertStateAfterRollback(
				point,
				original -> {
					original.addStart(99);
					original.addEnd(88);
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(2, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					assertTrue(original.getStarts().contains(2));
					assertFalse(original.getStarts().contains(99));
					assertEquals(1, original.getEnds().size());
					assertTrue(original.getEnds().contains(3));
					assertFalse(original.getEnds().contains(88));
				}
			);
		}

		@Test
		@DisplayName(
			"rollback after mixed mutations (addStart + removeEnds)"
		)
		void shouldRollbackMixedMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					10L, new int[]{1}, new int[]{2, 3}
				);

			assertStateAfterRollback(
				point,
				original -> {
					original.addStart(50);
					original.removeEnds(new int[]{2});
				},
				(original, committed) -> {
					assertNull(committed);
					// starts unchanged
					assertEquals(1, original.getStarts().size());
					assertTrue(original.getStarts().contains(1));
					// ends unchanged
					assertEquals(2, original.getEnds().size());
					assertTrue(original.getEnds().contains(2));
					assertTrue(original.getEnds().contains(3));
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mode (T8)")
	class NonTransactionalTest {

		@Test
		@DisplayName("all mutation methods work outside transaction")
		void shouldSupportNonTransactionalMutations() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);

			point.addStart(1);
			assertTrue(point.getStarts().contains(1));

			point.addEnd(2);
			assertTrue(point.getEnds().contains(2));

			point.addStarts(new int[]{3, 4});
			assertTrue(point.getStarts().contains(3));
			assertTrue(point.getStarts().contains(4));

			point.addEnds(new int[]{5, 6});
			assertTrue(point.getEnds().contains(5));
			assertTrue(point.getEnds().contains(6));

			point.removeStarts(new int[]{3});
			assertFalse(point.getStarts().contains(3));

			point.removeEnds(new int[]{5});
			assertFalse(point.getEnds().contains(5));
		}
	}

	@Nested
	@DisplayName("deepEquals / equals / hashCode")
	class EqualityTest {

		@Test
		@DisplayName("deepEquals(this) returns true")
		void shouldBeDeepEqualToSelf() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			assertTrue(point.deepEquals(point));
		}

		@Test
		@DisplayName("deepEquals(null) returns false")
		void shouldNotBeDeepEqualToNull() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			assertFalse(point.deepEquals(null));
		}

		@Test
		@DisplayName(
			"same threshold, different starts → deepEquals false"
		)
		void shouldNotBeDeepEqualWithDifferentStarts() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{99}, new int[]{2}
				);
			assertFalse(p1.deepEquals(p2));
		}

		@Test
		@DisplayName(
			"same threshold, different ends → deepEquals false"
		)
		void shouldNotBeDeepEqualWithDifferentEnds() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{99}
				);
			assertFalse(p1.deepEquals(p2));
		}

		@Test
		@DisplayName(
			"equals uses only threshold (Lombok @EqualsAndHashCode)"
		)
		void shouldBeEqualByThresholdOnly() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(
					1L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(
					1L, new int[]{99}, new int[]{88}
				);
			assertEquals(p1, p2);
		}

		@Test
		@DisplayName("hashCode consistent for same threshold")
		void shouldHaveConsistentHashCode() {
			final TransactionalRangePoint p1 =
				new TransactionalRangePoint(42L);
			final TransactionalRangePoint p2 =
				new TransactionalRangePoint(42L);
			assertEquals(p1.hashCode(), p2.hashCode());
		}
	}

	@Nested
	@DisplayName("makeClone")
	class MakeCloneTest {

		@Test
		@DisplayName("clone is new instance with same data")
		void shouldCloneWithSameData() {
			final TransactionalRangePoint original =
				new TransactionalRangePoint(
					5L, new int[]{1, 2}, new int[]{3, 4}
				);
			final TransactionalRangePoint clone = original.makeClone();
			assertNotSame(original, clone);
			assertEquals(5L, clone.getThreshold());
			assertTrue(clone.deepEquals(original));
		}

		@Test
		@DisplayName("mutating clone does not affect original")
		void shouldNotAffectOriginalOnCloneMutation() {
			final TransactionalRangePoint original =
				new TransactionalRangePoint(
					5L, new int[]{1}, new int[]{2}
				);
			final TransactionalRangePoint clone = original.makeClone();
			clone.addStart(99);
			clone.addEnd(88);
			assertFalse(original.getStarts().contains(99));
			assertFalse(original.getEnds().contains(88));
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("addStart same ID twice (bitmap deduplication)")
		void shouldDeduplicateOnAddStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			point.addStart(10);
			point.addStart(10);
			assertEquals(1, point.getStarts().size());
		}

		@Test
		@DisplayName("removeStarts on never-added ID (graceful no-op)")
		void shouldHandleRemoveOfAbsentStart() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			// should not throw
			point.removeStarts(new int[]{999});
			assertTrue(point.getStarts().isEmpty());
		}

		@Test
		@DisplayName("removeEnds on empty bitmap (graceful no-op)")
		void shouldHandleRemoveOnEmptyEnds() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(1L);
			// should not throw
			point.removeEnds(new int[]{1, 2, 3});
			assertTrue(point.getEnds().isEmpty());
		}

		@Test
		@DisplayName("extreme threshold values")
		void shouldHandleExtremeThresholds() {
			final TransactionalRangePoint minPoint =
				new TransactionalRangePoint(Long.MIN_VALUE);
			assertEquals(Long.MIN_VALUE, minPoint.getThreshold());

			final TransactionalRangePoint maxPoint =
				new TransactionalRangePoint(Long.MAX_VALUE);
			assertEquals(Long.MAX_VALUE, maxPoint.getThreshold());
		}
	}

	@Nested
	@DisplayName("Other")
	class OtherTest {

		@Test
		@DisplayName("toString output format verification")
		void shouldFormatToString() {
			final TransactionalRangePoint point =
				new TransactionalRangePoint(
					42L, new int[]{1}, new int[]{2}
				);
			final String result = point.toString();
			assertTrue(
				result.contains("42"),
				"toString should contain threshold"
			);
			assertTrue(
				result.contains("TransactionalRangePoint"),
				"toString should contain class name"
			);
		}
	}

	/**
	 * Generational randomized proof test that applies random bitmap mutations within
	 * transactions and verifies the committed state matches a reference model tracking
	 * the same operations.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@DisplayName("survives generational randomized test applying modifications on it")
		@ParameterizedTest(
			name = "TransactionalRangePoint should survive generational randomized test"
		)
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int maxId = 50;
			final Random seedRandom = new Random(input.randomSeed());
			final long initialThreshold = seedRandom.nextLong(1000);
			final Set<Integer> initialStarts =
				generateRandomIdSet(seedRandom, maxId);
			final Set<Integer> initialEnds =
				generateRandomIdSet(seedRandom, maxId);

			runFor(
				input,
				1_000,
				new TestState(
					new StringBuilder(256),
					initialThreshold,
					initialStarts,
					initialEnds
				),
				(random, testState) -> {
					final TransactionalRangePoint rangePoint =
						new TransactionalRangePoint(
							testState.threshold(),
							toSortedIntArray(testState.starts()),
							toSortedIntArray(testState.ends())
						);
					final Set<Integer> referenceStarts =
						new HashSet<>(testState.starts());
					final Set<Integer> referenceEnds =
						new HashSet<>(testState.ends());

					final StringBuilder codeBuffer = testState.code();
					codeBuffer.setLength(0);
					codeBuffer.append("\nSTART threshold=")
						.append(testState.threshold())
						.append(" starts=")
						.append(referenceStarts)
						.append(" ends=")
						.append(referenceEnds)
						.append("\n");

					assertStateAfterCommit(
						rangePoint,
						original -> {
							final int operationsInTransaction =
								1 + random.nextInt(10);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int operation = random.nextInt(6);
								switch (operation) {
									case 0 -> {
										// addStart — single ID
										final int id = 1 + random.nextInt(maxId);
										original.addStart(id);
										referenceStarts.add(id);
										codeBuffer.append("+S")
											.append(id).append(" ");
									}
									case 1 -> {
										// addEnd — single ID
										final int id = 1 + random.nextInt(maxId);
										original.addEnd(id);
										referenceEnds.add(id);
										codeBuffer.append("+E")
											.append(id).append(" ");
									}
									case 2 -> {
										// addStarts — bulk add
										final int[] ids =
											generateRandomIds(random, maxId);
										original.addStarts(ids);
										for (int id : ids) {
											referenceStarts.add(id);
										}
										codeBuffer.append("+SS")
											.append(java.util.Arrays.toString(ids))
											.append(" ");
									}
									case 3 -> {
										// addEnds — bulk add
										final int[] ids =
											generateRandomIds(random, maxId);
										original.addEnds(ids);
										for (int id : ids) {
											referenceEnds.add(id);
										}
										codeBuffer.append("+EE")
											.append(java.util.Arrays.toString(ids))
											.append(" ");
									}
									case 4 -> {
										// removeStarts — bulk remove
										final int[] ids =
											generateRandomIds(random, maxId);
										original.removeStarts(ids);
										for (int id : ids) {
											referenceStarts.remove(id);
										}
										codeBuffer.append("-SS")
											.append(java.util.Arrays.toString(ids))
											.append(" ");
									}
									case 5 -> {
										// removeEnds — bulk remove
										final int[] ids =
											generateRandomIds(random, maxId);
										original.removeEnds(ids);
										for (int id : ids) {
											referenceEnds.remove(id);
										}
										codeBuffer.append("-EE")
											.append(java.util.Arrays.toString(ids))
											.append(" ");
									}
								}
							}
							codeBuffer.append("\n");
						},
						(original, committed) -> {
							assertBitmapMatchesSet(
								committed.getStarts(),
								referenceStarts,
								"starts mismatch\n" + codeBuffer
							);
							assertBitmapMatchesSet(
								committed.getEnds(),
								referenceEnds,
								"ends mismatch\n" + codeBuffer
							);
							assertEquals(
								testState.threshold(),
								committed.getThreshold(),
								"threshold changed unexpectedly"
							);
						}
					);

					return new TestState(
						new StringBuilder(256),
						testState.threshold(),
						referenceStarts,
						referenceEnds
					);
				}
			);
		}
	}

	// -----------------------------------------------------------------------
	// Shared helpers
	// -----------------------------------------------------------------------

	/**
	 * Carries the state between generational test iterations.
	 *
	 * @param code      accumulated operation log for diagnosing failures
	 * @param threshold the immutable threshold value
	 * @param starts    the expected starts set at the start of the next iteration
	 * @param ends      the expected ends set at the start of the next iteration
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		long threshold,
		@Nonnull Set<Integer> starts,
		@Nonnull Set<Integer> ends
	) {}

	/**
	 * Generates a random set of positive record IDs (1..maxId) with random size (0..maxId/2).
	 *
	 * @param random the random source for reproducibility
	 * @param maxId  the upper bound (exclusive) for generated IDs
	 * @return a set of random positive record IDs
	 */
	@Nonnull
	private static Set<Integer> generateRandomIdSet(
		@Nonnull Random random,
		int maxId
	) {
		final int count = random.nextInt(maxId / 2);
		final Set<Integer> result = new HashSet<>(count);
		for (int i = 0; i < count; i++) {
			result.add(1 + random.nextInt(maxId));
		}
		return result;
	}

	/**
	 * Generates a small array of random positive IDs (1..maxId) for bulk operations.
	 * Array length is between 1 and 5.
	 *
	 * @param random the random source
	 * @param maxId  the upper bound (exclusive) for generated IDs
	 * @return an array of random positive record IDs
	 */
	@Nonnull
	private static int[] generateRandomIds(
		@Nonnull Random random,
		int maxId
	) {
		final int count = 1 + random.nextInt(5);
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = 1 + random.nextInt(maxId);
		}
		return ids;
	}

	/**
	 * Converts a set of integers to a sorted int array suitable for
	 * {@link TransactionalRangePoint} constructors.
	 *
	 * @param idSet the set of IDs to convert
	 * @return a sorted int array containing all IDs from the set
	 */
	@Nonnull
	private static int[] toSortedIntArray(@Nonnull Set<Integer> idSet) {
		return idSet.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/**
	 * Asserts that a {@link Bitmap} contains exactly the same IDs as the given reference set,
	 * no more and no fewer.
	 *
	 * @param bitmap       the bitmap under test
	 * @param referenceSet the expected set of IDs
	 * @param message      context message for assertion failures
	 */
	private static void assertBitmapMatchesSet(
		@Nonnull Bitmap bitmap,
		@Nonnull Set<Integer> referenceSet,
		@Nonnull String message
	) {
		assertEquals(
			referenceSet.size(), bitmap.size(),
			"size mismatch: " + message
		);
		for (final int id : referenceSet) {
			assertTrue(
				bitmap.contains(id),
				"bitmap missing ID " + id + ": " + message
			);
		}
		// verify no extra IDs exist in the bitmap
		final int[] bitmapArray = bitmap.getArray();
		for (final int id : bitmapArray) {
			assertTrue(
				referenceSet.contains(id),
				"bitmap has unexpected ID " + id + ": " + message
			);
		}
	}
}
