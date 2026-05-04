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
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetGroupIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetGroupIndex (generational proof)")
@Tag(INDEXING)
@Tag(FACET)
class LongRunningFacetGroupIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FacetGroupIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
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

}
