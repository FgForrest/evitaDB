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

package io.evitadb.index;

import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational property-based stress test for {@link ReferencedTypeEntityIndex}. Runs randomized PK
 * insert/remove pairs with cardinality tracking and filter attributes, comparing committed state
 * against a JDK reference implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferencedTypeEntityIndex (generational proof)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(REFERENCE)
class LongRunningReferencedTypeEntityIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "BRAND";

	@Nonnull
	private static ReferencedTypeEntityIndex createInstance() {
		return new ReferencedTypeEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
	}

	/**
	 * Runs the generational proof test with randomized insert/remove operations.
	 */
	@DisplayName("survives generational randomized test")
	@ParameterizedTest(name = "ReferencedTypeEntityIndex should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashMap<>(32),
				createInstance()
			),
			(random, state) -> {
				final ReferencedTypeEntityIndex tested = state.index();
				// deep copy the reference model
				final Map<Integer, Set<Integer>> referenceModel =
					deepCopyModel(state.expectedPkToRefs());
				final AtomicReference<ReferencedTypeEntityIndex> committedRef =
					new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> {
						final int ops = random.nextInt(5) + 1;
						for (int i = 0; i < ops; i++) {
							executeRandomOperation(
								random, original, referenceModel
							);
						}
					},
					(original, committed) -> {
						assertNotNull(committed);
						verifyState(committed, referenceModel);
						committedRef.set(committed);
					}
				);

				return new GenerationalState(
					referenceModel, committedRef.get()
				);
			},
			(state, exc) -> {
				System.out.println(
					"Failed state - PK->refs: " +
						state.expectedPkToRefs()
				);
			}
		);
	}

	/**
	 * Executes a random insert or remove operation on the index and the reference model.
	 *
	 * @param random         the random generator
	 * @param index          the index under test
	 * @param referenceModel the reference model tracking expected state
	 */
	private static void executeRandomOperation(
		@Nonnull Random random,
		@Nonnull ReferencedTypeEntityIndex index,
		@Nonnull Map<Integer, Set<Integer>> referenceModel
	) {
		final int indexPk = random.nextInt(30) + 1;
		final int refPk = random.nextInt(20) + 1;

		if (random.nextBoolean()) {
			// insert -- the production index tracks multiset cardinality; to keep the Set-based
			// model in sync we skip duplicate inserts (they would increment the production
			// cardinality without changing the Set, causing a diverging remove later)
			final Set<Integer> refs =
				referenceModel.computeIfAbsent(indexPk, k -> new HashSet<>(4));
			if (refs.add(refPk)) {
				index.insertPrimaryKeyIfMissing(indexPk, refPk);
			}
		} else {
			// remove -- only if the exact (indexPk, refPk) pair exists
			final Set<Integer> refs = referenceModel.get(indexPk);
			if (refs != null && refs.contains(refPk)) {
				index.removePrimaryKey(indexPk, refPk);
				refs.remove(refPk);
				if (refs.isEmpty()) {
					referenceModel.remove(indexPk);
				}
			}
		}
	}

	/**
	 * Verifies the committed index state matches the reference model.
	 *
	 * @param committed     the committed index instance
	 * @param expectedModel the expected PK-to-referenced-entity mapping
	 */
	private static void verifyState(
		@Nonnull ReferencedTypeEntityIndex committed,
		@Nonnull Map<Integer, Set<Integer>> expectedModel
	) {
		final Bitmap allPks = committed.getAllPrimaryKeys();
		assertEquals(
			expectedModel.size(), allPks.size(),
			"PK count mismatch. Expected: " + expectedModel.keySet() +
				", got bitmap size: " + allPks.size()
		);

		for (Map.Entry<Integer, Set<Integer>> entry :
			expectedModel.entrySet()) {
			final int indexPk = entry.getKey();
			assertTrue(
				allPks.contains(indexPk),
				"Missing index PK: " + indexPk
			);
			// verify referenced entity lookup for each ref PK
			for (int refPk : entry.getValue()) {
				final int[] refIndexes =
					committed.getAllReferenceIndexes(refPk);
				boolean found = false;
				for (int refIndex : refIndexes) {
					if (refIndex == indexPk) {
						found = true;
						break;
					}
				}
				assertTrue(found,
					"Index PK " + indexPk +
						" not found in reference indexes " +
						"for referenced entity PK " + refPk
				);
			}
		}
	}

	/**
	 * Creates a deep copy of the PK-to-references model.
	 *
	 * @param original the original model to copy
	 * @return a deep copy with independent mutable sets
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> deepCopyModel(
		@Nonnull Map<Integer, Set<Integer>> original
	) {
		final Map<Integer, Set<Integer>> copy =
			CollectionUtils.createHashMap(original.size());
		for (Map.Entry<Integer, Set<Integer>> entry :
			original.entrySet()) {
			copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPkToRefs mapping of index PK to its set of referenced entity PKs
	 * @param index            the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Map<Integer, Set<Integer>> expectedPkToRefs,
		@Nonnull ReferencedTypeEntityIndex index
	) {}
}
