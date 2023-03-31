/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api;

import com.github.javafaker.Faker;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This test verifies whether entities can be filtered by hierarchy constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by hierarchy functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public class EntityByHierarchyFilteringFunctionalTest {
	private static final String THOUSAND_CATEGORIES = "ThousandCategories";
	private static final String ATTRIBUTE_SHORTCUT = "shortcut";
	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator();

	@Nonnull
	private static Cardinalities computeCardinalities(
		Integer parentCategoryId,
		Hierarchy categoryHierarchy,
		List<SealedEntity> allCategories,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treePredicate
	) {
		final Set<Integer> categoriesWithValidPath = new HashSet<>();
		for (SealedEntity category : allCategories) {
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(category.getPrimaryKey()));
			if (treePredicate.test(category, parentItems)) {
				categoriesWithValidPath.add(category.getPrimaryKey());
			}
		}
		final Cardinalities categoryCardinalities = new Cardinalities();
		for (SealedEntity category : allCategories) {
			if (filterPredicate.test(category)) {
				final int categoryId = category.getPrimaryKey();
				final List<Integer> categoryPath = Stream.concat(
					categoryHierarchy.getParentItems(String.valueOf(categoryId))
						.stream()
						.map(it -> Integer.parseInt(it.getCode())),
					Stream.of(categoryId)
				).toList();
				final int levels = categoryPath.size() - 1;
				for (int i = levels; i >= 0; i--) {
					int cid = categoryPath.get(i);
					if (categoriesWithValidPath.contains(cid)) {
						if (i == levels) {
							categoryCardinalities.record(cid);
						} else if (i == levels - 1) {
							categoryCardinalities.recordChild(cid);
						} else {
							categoryCardinalities.recordDistantChild(cid);
						}
					}
					if (parentCategoryId != null && cid == parentCategoryId) {
						// we have encountered requested parent
						break;
					}
				}
			}
		}
		return categoryCardinalities;
	}

	@DataSet(value = THOUSAND_CATEGORIES, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			final List<EntityReference> storedCategories = dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_SHORTCUT, Boolean.class, whichIs -> whichIs.filterable());
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			final List<SealedEntity> categoriesAvailable = storedCategories.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContent(), referenceContent(), dataInLocales()).orElseThrow())
				.collect(Collectors.toList());
			return new DataCarrier(
				"originalCategoryEntities",
				categoriesAvailable,
				"categoryHierarchy",
				dataGenerator.getHierarchy(Entities.CATEGORY)
			);
		});
	}

	@DisplayName("Should return root categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnRootCategories(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinRootSelf(directRelation())),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalCategoryEntities,
					sealedEntity ->
						// category has exact level one
						categoryHierarchy.getItem(Objects.requireNonNull(sealedEntity.getPrimaryKey()).toString()).getLevel() == 1,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllCategories(Evita evita, List<SealedEntity> originalCategoryEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinRootSelf()),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalCategoryEntities,
					sealedEntity -> true,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all categories except specified subtrees")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllCategoriesExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinRootSelf(excluding(excluded.toArray(new Integer[0])))),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalCategoryEntities,
					sealedEntity ->
						// is not directly excluded node
						!excluded.contains(sealedEntity.getPrimaryKey()) &&
							// has no parent node that is in excluded set
							categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
								.stream()
								.map(it -> Integer.parseInt(it.getCode()))
								.noneMatch(excluded::contains),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subcategories of lower category")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnLowerLevelCategories(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(7, directRelation())),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				//noinspection ConstantConditions
				assertResultIs(
					originalCategoryEntities,
					sealedEntity ->
						// has direct parent node 7
						ofNullable(categoryHierarchy.getParentItem(sealedEntity.getPrimaryKey().toString()))
							.map(it -> Objects.equals(it.getCode(), String.valueOf(7)))
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subcategories in selected subtree")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllChildCategories(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(7)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				//noinspection ConstantConditions
				assertResultIs(
					originalCategoryEntities,
					sealedEntity ->
						// is either node 7
						Objects.equals(sealedEntity.getPrimaryKey().toString(), String.valueOf(7)) ||
							// or has parent node 7
							categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
								.stream()
								.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(7))),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subcategories in selected subtree excluding root node")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnOnlyChildCategories(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(7, excludingRoot())),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				//noinspection ConstantConditions
				assertResultIs(
					originalCategoryEntities,
					sealedEntity ->
						// is not exactly node 7
						!Objects.equals(sealedEntity.getPrimaryKey().toString(), String.valueOf(7)) &&
							// but has parent node 7
							categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
								.stream()
								.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(7))),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subtree categories except specified subtrees")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(1, excluding(excluded.toArray(new Integer[0])))),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalCategoryEntities,
					sealedEntity -> {
						final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString());
						return
							// is not directly excluded node
							!excluded.contains(sealedEntity.getPrimaryKey()) &&
								// has no excluded parent node
								parentItems
									.stream()
									.map(it -> Integer.parseInt(it.getCode()))
									.noneMatch(excluded::contains) &&
								// has parent node 1
								(
									Objects.equals(1, sealedEntity.getPrimaryKey()) ||
										parentItems
											.stream()
											.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
								);
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subtree categories except specified subtrees and matching attribute filter")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeExceptSpecifiedSubtreesAndMatchingCertainConstraint(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 34));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								or(
									attributeLessThan(ATTRIBUTE_PRIORITY, 25000),
									attributeStartsWith(ATTRIBUTE_CODE, "E")
								),
								hierarchyWithinSelf(1, excluding(excluded.toArray(new Integer[0])))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalCategoryEntities,
					sealedEntity -> {
						final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString());
						return
							// attribute condition matches
							(
								ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE)).map(it -> ((String) it).startsWith("E")).orElse(false) ||
									ofNullable(sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)).map(it -> ((Long) it) < 25000).orElse(false)
							) &&
								// is not directly excluded node
								!excluded.contains(sealedEntity.getPrimaryKey()) &&
								// has no excluded parent node
								parentItems
									.stream()
									.map(it -> Integer.parseInt(it.getCode()))
									.noneMatch(excluded::contains) &&
								// has parent node 1
								(
									Objects.equals(1, sealedEntity.getPrimaryKey()) ||
										parentItems
											.stream()
											.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()))
								);
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return category parents for returned categories when only primary keys are returned")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategoryParentsForReturnedCategoriesWhenOnlyPrimaryKeysAreReturned(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							// wants obviously non-existed page to test evita that returns first page.
							page(94, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							hierarchyParentsOfSelf()
						)
					),
					EntityReference.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofSelf();

				// all results should start with same parents when we query by hierarchy
				for (EntityReference entityReference : result.getRecordData()) {
					final Integer[] relatedParents = Arrays.stream(categoryParents.getParentsFor(entityReference.getPrimaryKey()))
						.map(it -> it.getPrimaryKey())
						.toArray(Integer[]::new);
					final Integer[] parentIds = categoryHierarchy
						.getParentItems(String.valueOf(entityReference.getPrimaryKey()))
						.stream()
						.map(it -> Integer.parseInt(it.getCode()))
						.toArray(Integer[]::new);
					if (relatedParents == null) {
						assertEquals(0, parentIds.length);
					} else {
						assertArrayEquals(
							parentIds,
							relatedParents
						);
					}
				}

				return null;
			}
		);
	}

	@DisplayName("Should return category parents for returned categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategoryParentsForReturnedCategories(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							// wants obviously non-existed page to test evita that returns first page.
							page(94, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							hierarchyParentsOfSelf()
						)
					),
					SealedEntity.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofSelf();

				// all results should start with same parents when we query by hierarchy
				for (SealedEntity entity : result.getRecordData()) {
					final Integer[] relatedParents = Arrays.stream(categoryParents.getParentsFor(entity.getPrimaryKey()))
						.map(it -> it.getPrimaryKey())
						.toArray(Integer[]::new);
					final Integer[] parentIds = categoryHierarchy
						.getParentItems(String.valueOf(entity.getPrimaryKey()))
						.stream()
						.map(it -> Integer.parseInt(it.getCode()))
						.toArray(Integer[]::new);
					if (relatedParents == null) {
						assertEquals(0, parentIds.length);
					} else {
						assertArrayEquals(
							parentIds,
							relatedParents
						);
					}
				}

				return null;
			}
		);
	}

	@DisplayName("Should return category parent bodies for returned categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategoryParentBodiesForReturnedCategories(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							entityFetchAllContentAnd(
								// wants obviously non-existed page to test evita that returns first page.
								page(94, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								hierarchyParentsOfSelf(entityFetch())
							)
						)
					),
					SealedEntity.class
				);

				assertFalse(result.getRecordData().isEmpty());

				final HierarchyParents hierarchyParents = result.getExtraResult(HierarchyParents.class);
				assertNotNull(hierarchyParents, "No parents DTO was returned!");
				final ParentsByReference categoryParents = hierarchyParents.ofSelf();

				// all results should start with same parents when we query by hierarchy
				for (SealedEntity entity : result.getRecordData()) {
					final EntityClassifier[] relatedParents = categoryParents.getParentsFor(entity.getPrimaryKey());
					final Integer[] parentIds = categoryHierarchy
						.getParentItems(String.valueOf(entity.getPrimaryKey()))
						.stream()
						.map(it -> Integer.parseInt(it.getCode()))
						.toArray(Integer[]::new);
					if (relatedParents == null) {
						assertEquals(0, parentIds.length);
					} else {
						final Integer[] relatedParentIds = Arrays.stream(relatedParents)
							.map(EntityClassifier::getPrimaryKey)
							.toArray(Integer[]::new);
						assertArrayEquals(
							parentIds,
							relatedParentIds
						);
					}
				}

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories without statistics")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategories(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> languagePredicate = it -> it.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					null, categoryHierarchy, originalCategoryEntities,
					languagePredicate, (entity, parentItems) -> languagePredicate.test(entity),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy, null, false
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequested(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(2)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> languagePredicate = it -> it.getLocales().contains(CZECH_LOCALE);
				final Predicate<SealedEntity> categoryPredicate = sealedEntity -> {
					final Integer categoryId = sealedEntity.getPrimaryKey();
					final String categoryIdAsString = String.valueOf(categoryId);
					final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(categoryIdAsString);
					// has parent node 2
					return (
						Objects.equals(2, categoryId) ||
							parentItems
								.stream()
								.anyMatch(it -> Objects.equals(String.valueOf(2), it.getCode()))
					);
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					2, categoryHierarchy, originalCategoryEntities,
					languagePredicate.and(categoryPredicate), (entity, parentItems) -> languagePredicate.test(entity),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(2)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> languagePredicate = sealedEntity -> sealedEntity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory2 = Objects.equals(2, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(2), it.getCode()));
					return languagePredicate.test(sealedEntity) && withinCategory2 && (level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					2, categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesExceptShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRootSelf()
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> languagePredicate = sealedEntity -> sealedEntity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					return languagePredicate.test(sealedEntity) && !sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					null, categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories with all statistics until ")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedOnLevelTwo(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRootSelf()
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Predicate<SealedEntity> languagePredicate = sealedEntity -> sealedEntity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity) && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					null, categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true
						)
					)
				);

				final HierarchyStatistics statistics = result.getExtraResult(HierarchyStatistics.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@Nonnull
	private HierarchyStatistics computeExpectedStatistics(
		Integer parentCategoryId,
		Hierarchy categoryHierarchy,
		List<SealedEntity> allCategories,
		Predicate<SealedEntity> filterPredicate,
		TestHierarchyPredicate treePredicate,
		Function<Cardinalities, HierarchyStatisticsTuple> statisticsComputer
	) {
		final Cardinalities categoryCardinalities = computeCardinalities(parentCategoryId, categoryHierarchy, allCategories, filterPredicate, treePredicate);
		final HierarchyStatisticsTuple result = statisticsComputer.apply(categoryCardinalities);
		final Map<String, List<LevelInfo>> theResults = Map.of(
			result.name(), result.levelInfos()
		);
		return new HierarchyStatistics(
			theResults,
			Collections.emptyMap()
		);
	}

	@Nonnull
	private List<LevelInfo> computeChildren(
		@Nonnull EvitaSessionContract session,
		@Nullable Integer parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable Cardinalities categoryCardinalities,
		boolean computeChildrenCount
	) {
		final LinkedList<LevelInfo> levelInfo = new LinkedList<>();
		final List<HierarchyItem> items = parentCategoryId == null ?
			categoryHierarchy.getRootItems() :
			Collections.singletonList(categoryHierarchy.getItem(String.valueOf(parentCategoryId)));

		for (HierarchyItem rootItem : items) {
			final int categoryId = Integer.parseInt(rootItem.getCode());
			if (categoryCardinalities == null || categoryCardinalities.isValid(categoryId)) {
				final List<LevelInfo> childrenStatistics = fetchLevelInfo(
					session, categoryId, categoryHierarchy, categoryCardinalities, computeChildrenCount
				);
				levelInfo.add(
					new LevelInfo(
						fetchHierarchyStatisticsEntity(session, categoryId),
						ofNullable(categoryCardinalities).map(it -> it.getCardinality(categoryId)).orElse(null),
						computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(categoryId)).orElse(null) : null,
						childrenStatistics
					)
				);
			}
		}
		return levelInfo;
	}

	private List<LevelInfo> fetchLevelInfo(
		@Nonnull EvitaSessionContract session,
		int parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable Cardinalities categoryCardinalities,
		boolean computeChildrenCount
	) {
		final LinkedList<LevelInfo> levelInfo = new LinkedList<>();
		for (HierarchyItem item : categoryHierarchy.getChildItems(String.valueOf(parentCategoryId))) {
			final int categoryId = Integer.parseInt(item.getCode());
			if (categoryCardinalities == null || categoryCardinalities.isValid(categoryId)) {
				final List<LevelInfo> childrenStatistics = fetchLevelInfo(
					session, categoryId, categoryHierarchy, categoryCardinalities, computeChildrenCount
				);
				levelInfo.add(
					new LevelInfo(
						fetchHierarchyStatisticsEntity(session, categoryId),
						ofNullable(categoryCardinalities).map(it -> it.getCardinality(categoryId)).orElse(null),
						computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(categoryId)).orElse(null) : null,
						childrenStatistics
					)
				);
			}
		}
		return levelInfo;
	}

	@Nonnull
	private SealedEntity fetchHierarchyStatisticsEntity(@Nonnull EvitaSessionContract session, int categoryId) {
		return session.query(
			query(
				collection(Entities.CATEGORY),
				filterBy(
					and(
						entityLocaleEquals(CZECH_LOCALE),
						entityPrimaryKeyInSet(categoryId)
					)
				),
				require(entityFetch(attributeContent()))
			),
			SealedEntity.class
		).getRecordData().get(0);
	}

	private interface TestHierarchyPredicate {

		boolean test(SealedEntity entity, List<HierarchyItem> parentItems);

		@Nonnull
		default TestHierarchyPredicate and(@Nonnull Predicate<SealedEntity> other) {
			Objects.requireNonNull(other);
			return (hierarchyNodeId, level) ->
				test(hierarchyNodeId, level) && other.test(hierarchyNodeId);
		}

	}

	public static class Cardinalities {
		private final Map<Integer, Integer> itemCardinality = new HashMap<>(32);
		private final Map<Integer, Integer> childrenItemCount = new HashMap<>(32);

		public void record(int categoryId) {
			itemCardinality.put(categoryId, 0);
			childrenItemCount.put(categoryId, 0);
		}

		public void recordChild(int categoryId) {
			itemCardinality.merge(categoryId, 1, Integer::sum);
			childrenItemCount.merge(categoryId, 1, Integer::sum);
		}

		public void recordDistantChild(int categoryId) {
			itemCardinality.merge(categoryId, 1, Integer::sum);
		}

		public boolean isValid(int categoryId) {
			return itemCardinality.containsKey(categoryId);
		}

		public int getCardinality(int categoryId) {
			return itemCardinality.get(categoryId);
		}

		public int getChildrenCount(int categoryId) {
			return childrenItemCount.get(categoryId);
		}

	}

	record HierarchyStatisticsTuple(
		String name,
		List<LevelInfo> levelInfos
	) {
	}

}
