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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.facet.LongRunningFacetReferenceIndexTest.FacetSnapshot;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link FacetIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback (Ref:
 * #1252) is exercised by the sibling {@code LongRunningSavepointFacetIndexTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(FACET)
class LongRunningFacetIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_ENTITY_TYPES = 3;
	private static final int MAX_NODES = 50;

	private final ReferenceSchema brandReferenceSchema = ReferenceSchema._internalBuild(
		Entities.BRAND, Entities.BRAND, true, Cardinality.ZERO_OR_MORE,
		null, false,
		new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)},
		Scope.NO_SCOPE
	);

	@ParameterizedTest(name = "FacetIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(512),
				new LinkedHashMap<>()
			),
			(random, testState) -> {
				final Map<ReferenceKey, int[][]> baseStructure = testState.initialSet();
				final StringBuilder codeBuffer = testState.code();

				// Rebuild index from reference model each iteration
				final FacetIndex facetIndex = buildFacetIndex(this.brandReferenceSchema, baseStructure, codeBuffer);

				assertStateAfterCommit(
					facetIndex,
					original -> applyRandomBatch(random, original, this.brandReferenceSchema, baseStructure, codeBuffer),
					(original, committed) -> {
						final String realToString = committed.toString();
						final String expectedToString = toString(baseStructure);
						assertEquals(
							expectedToString, realToString,
							"\nExpected: " + expectedToString + "\n" +
								"Actual:   " + committed + "\n\n" +
								codeBuffer
						);

						for (Entry<ReferenceKey, int[][]> entry : baseStructure.entrySet()) {
							final int[][] value = entry.getValue();
							for (int i = 0; i < value[1].length; i++) {
								int expectedFacetGroup = value[1][i];
								final ReferenceKey facetRef = entry.getKey();
								assertTrue(
									committed.isFacetInGroup(
										facetRef.referenceName(),
										expectedFacetGroup,
										facetRef.primaryKey()
									),
									"Facet " + facetRef.primaryKey() + " is not present in group " + expectedFacetGroup +
										" for facet entity type " + facetRef.referenceName() + "!"
								);
							}
						}
					});

				return new TestState(
					new StringBuilder(512),
					baseStructure
				);
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
	@ParameterizedTest(name = "FacetIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(512),
				new LinkedHashMap<>()
			),
			(random, testState) -> {
				final Map<ReferenceKey, int[][]> baseStructure = testState.initialSet();
				final StringBuilder codeBuffer = testState.code();

				final FacetIndex facetIndex = buildFacetIndex(this.brandReferenceSchema, baseStructure, codeBuffer);
				// value oracle of the base state that the rollback must return to
				final Map<String, FacetSnapshot> beforeRollback = snapshot(facetIndex);

				assertStateAfterRollback(
					facetIndex,
					original -> applyRandomBatch(random, original, this.brandReferenceSchema, baseStructure, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"FacetIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(
					new StringBuilder(512),
					baseStructure
				);
			}
		);
	}

	/**
	 * Applies a random batch of up to nine add/remove facet mutations to `facetIndex`, mirroring each mutation into the
	 * `baseStructure` reference model (reference key → parallel entity/group arrays) so the two stay in lockstep. Shared
	 * by the commit and rollback proofs.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull FacetIndex facetIndex,
		@Nonnull ReferenceSchema brandReferenceSchema,
		@Nonnull Map<ReferenceKey, int[][]> baseStructure,
		@Nonnull StringBuilder codeBuffer
	) {
		final int operationsInTransaction = random.nextInt(10);
		for (int i = 0; i < operationsInTransaction; i++) {
			final String[] entityTypes = baseStructure.keySet().stream().map(ReferenceKey::referenceName).distinct().toArray(String[]::new);

			final int entityTypesLength = facetIndex.getReferencedEntities().size();
			final int totalCount = facetIndex.getSize();
			final int operation = random.nextInt(2);
			if (totalCount < MAX_NODES && (operation == 0 || totalCount < 10)) {
				final String entityType = entityTypesLength < MAX_ENTITY_TYPES ?
					Entities.values()[random.nextInt(Entities.values().length)] :
					entityTypes[random.nextInt(entityTypes.length)];
				final int groupId = random.nextInt(10) + 1;
				// insert new item
				int newEntityId;
				int newReferencedId;
				boolean retry;
				do {
					newReferencedId = random.nextInt(MAX_NODES / 2);
					newEntityId = random.nextInt(MAX_NODES * 2);
					int finalNewEntityId = newEntityId;
					retry = ofNullable(baseStructure.get(new ReferenceKey(entityType, newReferencedId)))
						.map(it -> ArrayUtils.contains(it[0], finalNewEntityId))
						.orElse(false);
				} while (retry);

				codeBuffer.append("facetIndex.addFacet(new EntityReference(\"")
					.append(entityType).append("\", ")
					.append(newReferencedId).append("), ")
					.append(newEntityId)
					.append(");\n");

				try {
					final ReferenceKey referenceKey = new ReferenceKey(entityType, newReferencedId);
					facetIndex.addFacet(brandReferenceSchema, referenceKey, groupId, newEntityId);
					baseStructure.merge(
						referenceKey,
						new int[][]{{newEntityId}, {groupId}},
						(oldOnes, newOnes) -> {
							final int addedEntityId = newOnes[0][0];
							final int addedGroupId = newOnes[1][0];
							final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(addedEntityId, oldOnes[0]);
							Assert.isTrue(!insertionPosition.alreadyPresent(), "Record should not be present!");
							final int[] newEntityIds = ArrayUtils.insertIntIntoArrayOnIndex(addedEntityId, oldOnes[0], insertionPosition.position());
							final int[] newGroupIds = ArrayUtils.insertIntIntoArrayOnIndex(addedGroupId, oldOnes[1], insertionPosition.position());
							return new int[][]{newEntityIds, newGroupIds};
						}
					);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}
			} else {
				// remove existing item
				final ReferenceKey entityReference = new ArrayList<>(baseStructure.keySet()).get(random.nextInt(baseStructure.size()));
				final int[][] entityIds = baseStructure.get(entityReference);
				final int rndNo = random.nextInt(entityIds[0].length);
				final int entityIdToRemove = entityIds[0][rndNo];
				final int groupIdToRemove = entityIds[1][rndNo];

				codeBuffer.append("facetIndex.removeFacet(\"")
					.append(entityReference.referenceName()).append("\", ")
					.append(entityReference.primaryKey()).append("), ")
					.append(entityIdToRemove)
					.append(");\n");

				try {
					facetIndex.removeFacet(brandReferenceSchema, entityReference, groupIdToRemove, entityIdToRemove);
					final int[] newEntityIds = ArrayUtils.removeIntFromArrayOnIndex(entityIds[0], rndNo);
					final int[] newGroupIds = ArrayUtils.removeIntFromArrayOnIndex(entityIds[1], rndNo);
					if (ArrayUtils.isEmpty(newEntityIds)) {
						baseStructure.remove(entityReference);
					} else {
						baseStructure.put(entityReference, new int[][]{newEntityIds, newGroupIds});
					}
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}
			}
		}
	}

	/**
	 * Builds a fresh {@link FacetIndex} seeded with every (reference, group, entity) triple in the reference model,
	 * echoing each add into the reproduction code buffer.
	 */
	@Nonnull
	private static FacetIndex buildFacetIndex(
		@Nonnull ReferenceSchema brandReferenceSchema,
		@Nonnull Map<ReferenceKey, int[][]> initialSet,
		@Nonnull StringBuilder codeBuffer
	) {
		codeBuffer.append("final FacetIndex facetIndex = new FacetIndex();\n")
			.append(
				initialSet
					.keySet()
					.stream()
					.sorted(ReferenceKey.GENERIC_COMPARATOR)
					.map(it -> {
							final StringBuilder innerSb = new StringBuilder(64);
							final int[][] entityIds = initialSet.get(it);
							for (int i = 0; i < entityIds[0].length; i++) {
								int entityId = entityIds[0][i];
								int groupId = entityIds[1][i];
								innerSb.append("facetIndex.addFacet(new EntityReference(\"")
									.append(it.referenceName()).append("\",").append(it.primaryKey()).append("), ")
									.append(groupId).append(", ").append(entityId).append(");\n");
							}
							return innerSb.toString();
						}
					)
					.collect(Collectors.joining())
			);
		final FacetIndex facetIndex = new FacetIndex();
		for (Entry<ReferenceKey, int[][]> entry : initialSet.entrySet()) {
			final int[] recordIds = entry.getValue()[0];
			final int[] groupIds = entry.getValue()[1];
			for (int i = 0; i < recordIds.length; i++) {
				facetIndex.addFacet(brandReferenceSchema, entry.getKey(), groupIds[i], recordIds[i]);
			}
		}
		return facetIndex;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (reference name → per-reference
	 * facet snapshot), so two snapshots taken before and after a rollback can be compared with `.equals` to prove exact
	 * restoration. Reuses the proven per-reference snapshot of {@link LongRunningFacetReferenceIndexTest}.
	 */
	@Nonnull
	static Map<String, FacetSnapshot> snapshot(@Nonnull FacetIndex index) {
		final Map<String, FacetSnapshot> result = new HashMap<>();
		for (final Entry<String, FacetReferenceIndex> entry : index.getFacetingEntities().entrySet()) {
			result.put(entry.getKey(), LongRunningFacetReferenceIndexTest.snapshot(entry.getValue()));
		}
		return result;
	}

	private String toString(Map<ReferenceKey, int[][]> baseStructure) {
		final StringBuilder sb = new StringBuilder();
		final Map<Serializable, List<ReferenceKey>> references = baseStructure.keySet().stream().collect(Collectors.groupingBy(ReferenceKey::referenceName));
		references.keySet().stream().sorted().forEach(it -> {
			sb.append(it).append(":\n");
			final List<ReferenceKey> entityReferences = references.get(it);
			final Map<Integer, Map<Integer, int[]>> groupsFacetsIx = new TreeMap<>();
			for (ReferenceKey ref : entityReferences) {
				final int[][] data = baseStructure.get(ref);
				for (int i = 0; i < data[0].length; i++) {
					final int entityId = data[0][i];
					final Map<Integer, int[]> groupIndex = groupsFacetsIx.computeIfAbsent(data[1][i], gId -> new TreeMap<>());
					groupIndex.merge(
						ref.primaryKey(),
						new int[]{entityId},
						(oldOnes, newOnes) -> ArrayUtils.insertIntIntoOrderedArray(newOnes[0], oldOnes)
					);
				}
			}

			groupsFacetsIx
				.forEach((key, value) -> {
					sb.append("\t").append("GROUP ").append(key).append(":\n");
					value.forEach((fct, eId) -> sb.append("\t\t").append(fct).append(": ")
						.append(Arrays.toString(eId)).append("\n"));

				});
		});
		if (sb.length() > 0) {
			while (sb.charAt(sb.length() - 1) == '\n') {
				sb.deleteCharAt(sb.length() - 1);
			}
		}
		return sb.toString();
	}

	private record TestState(
		StringBuilder code,
		Map<ReferenceKey, int[][]> initialSet
	) {}

}
