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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetIdIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback (Ref:
 * #1252) is exercised by the sibling {@code LongRunningSavepointFacetIdIndexTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetIdIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
class LongRunningFacetIdIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_ENTITY_ID = 50;

	@ParameterizedTest(name = "FacetIdIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashSet<>()),
			(random, testState) -> {
				final Set<Integer> referenceIds = testState.entityIds();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				final FacetIdIndex index = buildIdIndex(referenceIds, codeBuffer);

				assertStateAfterCommit(
					index,
					original -> applyRandomBatch(random, original, referenceIds, codeBuffer),
					(original, committed) -> {
						assertEquals(referenceIds.size(), committed.size(),
							"Size mismatch after commit!\n" + codeBuffer);
						assertEquals(referenceIds.isEmpty(), committed.isEmpty(),
							"isEmpty mismatch after commit!\n" + codeBuffer);
						for (int id : referenceIds) {
							assertTrue(committed.getRecords().contains(id),
								"Entity ID " + id + " not found in committed index!\n" + codeBuffer);
						}
					}
				);

				return new TestState(new StringBuilder(512), referenceIds);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index from the
	 * (random-walking) reference model, captures a value oracle of that base, applies a random batch of add/remove
	 * mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and no committed
	 * value was published.
	 */
	@ParameterizedTest(name = "FacetIdIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashSet<>()),
			(random, testState) -> {
				final Set<Integer> referenceIds = testState.entityIds();
				final StringBuilder codeBuffer = testState.code();

				final FacetIdIndex index = buildIdIndex(referenceIds, codeBuffer);
				// value oracle of the base state that the rollback must return to
				final List<Integer> beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, referenceIds, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"FacetIdIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(new StringBuilder(512), referenceIds);
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove facet mutations to `index`, mirroring each mutation into the
	 * `referenceIds` reference model so the two stay in lockstep. Shared by the commit and rollback proofs.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull FacetIdIndex index,
		@Nonnull Set<Integer> referenceIds,
		@Nonnull StringBuilder codeBuffer
	) {
		final int operationsInTransaction = random.nextInt(5) + 1;
		for (int i = 0; i < operationsInTransaction; i++) {
			if (referenceIds.isEmpty() || (referenceIds.size() < MAX_ENTITY_ID && random.nextBoolean())) {
				// Add a random entityId not already in the reference
				int newEntityId;
				do {
					newEntityId = random.nextInt(MAX_ENTITY_ID) + 1;
				} while (referenceIds.contains(newEntityId));

				codeBuffer.append("index.addFacet(").append(newEntityId).append(");\n");
				try {
					index.addFacet(newEntityId);
					referenceIds.add(newEntityId);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}
			} else if (!referenceIds.isEmpty()) {
				// Pick and remove a random existing entityId
				final ArrayList<Integer> idList = new ArrayList<>(referenceIds);
				final int entityIdToRemove = idList.get(random.nextInt(idList.size()));

				codeBuffer.append("index.removeFacet(").append(entityIdToRemove).append(");\n");
				try {
					index.removeFacet(entityIdToRemove);
					referenceIds.remove(entityIdToRemove);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}
			}
		}
	}

	/**
	 * Builds a fresh {@link FacetIdIndex} seeded with every entity id in the reference model, echoing each add into the
	 * reproduction code buffer.
	 */
	@Nonnull
	private static FacetIdIndex buildIdIndex(
		@Nonnull Set<Integer> referenceIds,
		@Nonnull StringBuilder codeBuffer
	) {
		codeBuffer.append("final FacetIdIndex index = new FacetIdIndex(1);\n");
		final FacetIdIndex index = new FacetIdIndex(1);
		for (int entityId : referenceIds) {
			codeBuffer.append("index.addFacet(").append(entityId).append(");\n");
			index.addFacet(entityId);
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (ascending entity ids), so two
	 * snapshots taken before and after a rollback can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static List<Integer> snapshot(@Nonnull FacetIdIndex index) {
		return toList(index.getRecords());
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
		Set<Integer> entityIds
	) {}

}
