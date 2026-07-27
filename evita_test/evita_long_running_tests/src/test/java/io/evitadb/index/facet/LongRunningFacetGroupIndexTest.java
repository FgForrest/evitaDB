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
 * Generational randomized proof test for {@link FacetGroupIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback (Ref:
 * #1252) is exercised by the sibling {@code LongRunningSavepointFacetGroupIndexTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetGroupIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
class LongRunningFacetGroupIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_FACET_ID = 10;
	private static final int MAX_ENTITY_ID = 50;

	@ParameterizedTest(name = "FacetGroupIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashMap<>()),
			(random, testState) -> {
				final Map<Integer, Set<Integer>> reference = testState.facetToEntities();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				final FacetGroupIndex index = buildGroupIndex(reference, codeBuffer);

				assertStateAfterCommit(
					index,
					original -> applyRandomBatch(random, original, reference, codeBuffer),
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

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index from the
	 * (random-walking) reference model, captures a value oracle of that base, applies a random batch of add/remove
	 * mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and no committed
	 * value was published.
	 */
	@ParameterizedTest(name = "FacetGroupIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashMap<>()),
			(random, testState) -> {
				final Map<Integer, Set<Integer>> reference = testState.facetToEntities();
				final StringBuilder codeBuffer = testState.code();

				final FacetGroupIndex index = buildGroupIndex(reference, codeBuffer);
				// value oracle of the base state that the rollback must return to
				final Map<Integer, List<Integer>> beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, reference, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"FacetGroupIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(new StringBuilder(512), reference);
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove facet mutations to `index`, mirroring each mutation into the `reference`
	 * model (facetId → entity ids) so the two stay in lockstep. Shared by the commit and rollback proofs.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull FacetGroupIndex index,
		@Nonnull Map<Integer, Set<Integer>> reference,
		@Nonnull StringBuilder codeBuffer
	) {
		final int operationsInTransaction = random.nextInt(5) + 1;
		for (int i = 0; i < operationsInTransaction; i++) {
			final int totalSize = reference.values().stream().mapToInt(Set::size).sum();
			if (reference.isEmpty() || (totalSize < MAX_FACET_ID * 5 && random.nextBoolean())) {
				// Add: pick (facetId, entityId) not already in reference
				final int facetId = random.nextInt(MAX_FACET_ID) + 1;
				final Set<Integer> existing = reference.getOrDefault(facetId, Set.of());
				int entityId;
				do {
					entityId = random.nextInt(MAX_ENTITY_ID) + 1;
				} while (existing.contains(entityId));

				codeBuffer.append("index.addFacet(").append(facetId).append(", ").append(entityId).append(");\n");
				try {
					index.addFacet(facetId, entityId);
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
					index.removeFacet(facetId, entityId);
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
	}

	@Nonnull
	private static FacetGroupIndex buildGroupIndex(
		@Nonnull Map<Integer, Set<Integer>> reference,
		@Nonnull StringBuilder codeBuffer
	) {
		codeBuffer.append("final FacetGroupIndex index = new FacetGroupIndex();\n");
		final FacetGroupIndex index = new FacetGroupIndex();
		for (Map.Entry<Integer, Set<Integer>> entry : reference.entrySet()) {
			for (int entityId : entry.getValue()) {
				codeBuffer.append("index.addFacet(").append(entry.getKey()).append(", ").append(entityId).append(");\n");
				index.addFacet(entry.getKey(), entityId);
			}
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (facetId → ascending entity ids), so
	 * two snapshots taken before and after a rollback can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static Map<Integer, List<Integer>> snapshot(@Nonnull FacetGroupIndex index) {
		final Map<Integer, List<Integer>> result = new HashMap<>();
		for (final Entry<Integer, Bitmap> entry : index.getAsMap().entrySet()) {
			result.put(entry.getKey(), toList(entry.getValue()));
		}
		return result;
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
		Map<Integer, Set<Integer>> facetToEntities
	) {}

}
