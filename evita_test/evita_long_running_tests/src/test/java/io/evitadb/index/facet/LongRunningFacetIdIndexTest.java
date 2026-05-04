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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetIdIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetIdIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
class LongRunningFacetIdIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FacetIdIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxEntityId = 50;

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new HashSet<>()),
			(random, testState) -> {
				final Set<Integer> referenceIds = testState.entityIds();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				codeBuffer.append("final FacetIdIndex index = new FacetIdIndex(1);\n");
				final FacetIdIndex index = new FacetIdIndex(1);
				for (int entityId : referenceIds) {
					codeBuffer.append("index.addFacet(").append(entityId).append(");\n");
					index.addFacet(entityId);
				}

				assertStateAfterCommit(
					index,
					original -> {
						final int operationsInTransaction = random.nextInt(5) + 1;
						for (int i = 0; i < operationsInTransaction; i++) {
							if (referenceIds.isEmpty() || (referenceIds.size() < maxEntityId && random.nextBoolean())) {
								// Add a random entityId not already in the reference
								int newEntityId;
								do {
									newEntityId = random.nextInt(maxEntityId) + 1;
								} while (referenceIds.contains(newEntityId));

								codeBuffer.append("index.addFacet(").append(newEntityId).append(");\n");
								try {
									original.addFacet(newEntityId);
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
									original.removeFacet(entityIdToRemove);
									referenceIds.remove(entityIdToRemove);
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							}
						}
					},
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

	private record TestState(
		StringBuilder code,
		Set<Integer> entityIds
	) {}

}
