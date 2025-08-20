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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies contract of {@link FacetIndex} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FacetIndexTest implements TimeBoundedTestSupport {
	public static final String BRAND_ENTITY = Entities.BRAND;
	public static final String STORE_ENTITY = Entities.STORE;
	public static final String CATEGORY_ENTITY = Entities.CATEGORY;
	private final Function<String, TriFunction<Integer, Bitmap, Bitmap[], FacetGroupFormula>> fct =
		entityType -> (groupId, facetIds, bitmaps) -> new FacetGroupOrFormula(entityType, groupId, facetIds, bitmaps);
	private FacetIndex facetIndex;

	@BeforeEach
	void setUp() {
		this.facetIndex = new FacetIndex();
		this.facetIndex.addFacet(new ReferenceKey(Entities.BRAND, 1), 1, 1);
		this.facetIndex.addFacet(new ReferenceKey(Entities.BRAND, 2), 2, 2);
		this.facetIndex.addFacet(new ReferenceKey(Entities.BRAND, 1), 1, 3);
		this.facetIndex.addFacet(new ReferenceKey(Entities.BRAND, 3), 3, 4);
		this.facetIndex.addFacet(new ReferenceKey(Entities.STORE, 1), 1, 5);
		this.facetIndex.addFacet(new ReferenceKey(Entities.PARAMETER, 1), null, 100);
		this.facetIndex.addFacet(new ReferenceKey(Entities.PARAMETER, 1), null, 101);
		this.facetIndex.addFacet(new ReferenceKey(Entities.PARAMETER, 2), null, 102);
	}

	@Test
	void shouldReturnFacetingEntityTypes() {
		final Set<String> referencedEntities = this.facetIndex.getReferencedEntities();
		assertEquals(3, referencedEntities.size());
		assertTrue(referencedEntities.contains(BRAND_ENTITY));
		assertTrue(referencedEntities.contains(STORE_ENTITY));
		assertTrue(referencedEntities.contains(Entities.PARAMETER));
	}

	@Test
	void shouldReturnFacetingEntityIds() {
		final List<FacetGroupFormula> brandReferencingEntityIds = this.facetIndex.getFacetReferencingEntityIdsFormula(
			BRAND_ENTITY, this.fct.apply(BRAND_ENTITY), new ArrayBitmap(1)
		);

		assertEquals(1, brandReferencingEntityIds.size());
		assertArrayEquals(new int[]{1, 3}, brandReferencingEntityIds.get(0).compute().getArray());

		final List<FacetGroupFormula> storeReferencingEntityIds = this.facetIndex.getFacetReferencingEntityIdsFormula(
			STORE_ENTITY, this.fct.apply(STORE_ENTITY), new ArrayBitmap(1)
		);

		assertEquals(1, storeReferencingEntityIds.size());
		assertArrayEquals(new int[]{5}, storeReferencingEntityIds.get(0).compute().getArray());

		assertEquals(0, this.facetIndex.getFacetReferencingEntityIdsFormula(BRAND_ENTITY, this.fct.apply(BRAND_ENTITY), new ArrayBitmap(8)).size());
		assertEquals(0, this.facetIndex.getFacetReferencingEntityIdsFormula(STORE_ENTITY, this.fct.apply(STORE_ENTITY), new ArrayBitmap(8)).size());
		assertEquals(0, this.facetIndex.getFacetReferencingEntityIdsFormula(CATEGORY_ENTITY, this.fct.apply(CATEGORY_ENTITY), new ArrayBitmap(1)).size());
	}

	@Test
	void shouldMaintainCorrectGroups() {
		assertEquals(
			"BRAND:\n" +
				"\tGROUP 1:\n" +
				"\t\t1: [1, 3]\n" +
				"\tGROUP 2:\n" +
				"\t\t2: [2]\n" +
				"\tGROUP 3:\n" +
				"\t\t3: [4]\n" +
				"PARAMETER:\n" +
				"\t[NO_GROUP]:\n" +
				"\t\t1: [100, 101]\n" +
				"\t\t2: [102]\n" +
				"STORE:\n" +
				"\tGROUP 1:\n" +
				"\t\t1: [5]",
			this.facetIndex.toString()
		);
	}

	@Test
	void shouldInsertNewFacetingEntityId() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		facetIndex.resetDirty();
		facetIndex.getModifiedStorageParts(1, trappedChanges);
		assertEquals(0, trappedChanges.getTrappedChangesCount());

		this.facetIndex.addFacet(new ReferenceKey(Entities.BRAND, 2), 2, 8);

		final List<FacetGroupFormula> brandReferencingEntityIds = this.facetIndex.getFacetReferencingEntityIdsFormula(
			BRAND_ENTITY, this.fct.apply(BRAND_ENTITY), new ArrayBitmap(2)
		);
		assertArrayEquals(new int[]{2, 8}, FormulaFactory.and(brandReferencingEntityIds.toArray(Formula[]::new)).compute().getArray());

		facetIndex.getModifiedStorageParts(1, trappedChanges);
		assertEquals(1, trappedChanges.getTrappedChangesCount());
	}

	@Test
	void shouldRemoveExistingFacetingEntityId() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		facetIndex.resetDirty();
		facetIndex.getModifiedStorageParts(1, trappedChanges);
		assertEquals(0, trappedChanges.getTrappedChangesCount());

		this.facetIndex.removeFacet(new ReferenceKey(Entities.BRAND, 2), 2, 2);

		final List<FacetGroupFormula> brandReferencingEntityIds = this.facetIndex.getFacetReferencingEntityIdsFormula(
			BRAND_ENTITY, this.fct.apply(BRAND_ENTITY), new ArrayBitmap(1)
		);
		assertArrayEquals(new int[]{1, 3}, FormulaFactory.and(brandReferencingEntityIds.toArray(Formula[]::new)).compute().getArray());
		facetIndex.getModifiedStorageParts(1, trappedChanges);
		assertEquals(1, trappedChanges.getTrappedChangesCount());
	}

	@ParameterizedTest(name = "FacetIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxEntityTypes = 3;
		final int maxNodes = 50;

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(),
				new LinkedHashMap<>()
			),
			(random, testState) -> {
				final Map<ReferenceKey, int[][]> initialSet = testState.initialSet();

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final FacetIndex facetIndex = new FacetIndex();\n")
					.append(
						initialSet
							.keySet()
							.stream()
							.sorted()
							.map(it -> {
									final StringBuilder innerSb = new StringBuilder();
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
						facetIndex.addFacet(entry.getKey(), groupIds[i], recordIds[i]);
					}
				}
				final Map<ReferenceKey, int[][]> baseStructure = testState.initialSet();

				assertStateAfterCommit(
					facetIndex,
					original -> {
						final int operationsInTransaction = random.nextInt(10);
						for (int i = 0; i < operationsInTransaction; i++) {
							final String[] entityTypes = baseStructure.keySet().stream().map(ReferenceKey::referenceName).distinct().toArray(String[]::new);

							final int entityTypesLength = facetIndex.getReferencedEntities().size();
							final int totalCount = facetIndex.getSize();
							final int operation = random.nextInt(2);
							if (totalCount < maxNodes && (operation == 0 || totalCount < 10)) {
								final String entityType = entityTypesLength < maxEntityTypes ?
									Entities.values()[random.nextInt(Entities.values().length)] :
									entityTypes[random.nextInt(entityTypes.length)];
								final int groupId = random.nextInt(10) + 1;
								// insert new item
								int newEntityId;
								int newReferencedId;
								boolean retry;
								do {
									newReferencedId = random.nextInt(maxNodes / 2);
									newEntityId = random.nextInt(maxNodes * 2);
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
									facetIndex.addFacet(referenceKey, groupId, newEntityId);
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
									facetIndex.removeFacet(entityReference, groupIdToRemove, entityIdToRemove);
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
					},
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
					new StringBuilder(),
					baseStructure
				);
			}
		);
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
