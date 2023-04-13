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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
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
public class EntityByHierarchyFilteringFunctionalTest extends AbstractHierarchyTest {
	private static final String THOUSAND_CATEGORIES = "ThousandCategories";
	private static final String ATTRIBUTE_SHORTCUT = "shortcut";
	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator();

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
						filterBy(hierarchyWithinRootSelf(excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))),
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
						filterBy(hierarchyWithinSelf(1, excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))),
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
								hierarchyWithinSelf(1, excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))
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
	void shouldReturnCardinalitiesForCategoriesWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							null, false, false
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

	@DisplayName("Should return children for categories without statistics for category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForRequestedNodeWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
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
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							null, false, false
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

	@DisplayName("Should return children for categories without statistics for requested category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							null, false, false
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

	@DisplayName("Should return parents for categories without statistics for requested category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							null, null, false,false
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

	@DisplayName("Should return parents with siblings for categories without statistics for requested category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsWithSiblingsWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent()),
									siblings(entityFetch(attributeContent()))
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.of(1, 6, 21, 30).collect(Collectors.toSet());
				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) ->
					{
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("30"));
						return entity.getLocales().contains(CZECH_LOCALE) &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfRequestedCategory);
					};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, entity -> true,
							false, false
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

	@DisplayName("Should return parents for categories with statistics for requested category 30")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsWithStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.of(1, 6, 21, 30).collect(Collectors.toSet());
				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) ->
						entity.getLocales().contains(CZECH_LOCALE) &&
							(allowedParents.contains(entity.getPrimaryKey()) || parentItems.stream().anyMatch(it -> it.getCode().equals("30")));
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, null, true,true
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

	@DisplayName("Should return parents with siblings for categories with statistics for requested category 30")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsAndSiblingsWithStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent()),
									siblings(),
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.of(1, 6, 21, 30).collect(Collectors.toSet());
				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) ->
					{
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("30"));
						return entity.getLocales().contains(CZECH_LOCALE) &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfRequestedCategory);
					};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, entity -> true, true, true
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

	@DisplayName("Should return parents with filtered siblings for categories with statistics for requested category 30")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsAndFilteredSiblingsWithStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> allowedParents = Stream.of(1, 6, 21, 30).collect(Collectors.toSet());
				final Set<Integer> disallowedParents = Stream.of(2, 5, 8).collect(Collectors.toSet());

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30, excluding(entityPrimaryKeyInSet(disallowedParents.toArray(Integer[]::new))))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent()),
									siblings(),
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) -> {
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isNotInDisallowedParents = !disallowedParents.contains(entity.getPrimaryKey()) && parentItems.stream().noneMatch(it -> disallowedParents.contains(Integer.parseInt(it.getCode())));
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("30"));
						return entity.getLocales().contains(CZECH_LOCALE) && isNotInDisallowedParents &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfRequestedCategory);
					};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, entity -> !disallowedParents.contains(entity.getPrimaryKey()),
							true, true
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

	@DisplayName("Should return parents with siblings with level below for categories with statistics for requested category 30")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForParentsAndSiblingsWithLevelBelowWithStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(30)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								parents(
									"megaMenu",
									entityFetch(attributeContent()),
									siblings(stopAt(distance(1))),
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.of(1, 6, 21, 30).collect(Collectors.toSet());
				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) -> {
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfAnySibling = parentItems.size() == 1 || (parentItems.size() > 1 && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 2).getCode())));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("30"));
						return entity.getLocales().contains(CZECH_LOCALE) &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfAnySibling || isChildOfRequestedCategory);
					};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, entity -> true,
							true, true
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

	@DisplayName("Should return siblings children for categories without statistics for requested root category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForSiblingCategoriesWhenRootSubTreeIsRequestedWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (entity, parentItems) -> parentItems.isEmpty() && languagePredicate.test(entity, parentItems);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate,
					scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, false
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

	@DisplayName("Should return siblings children for categories without statistics for requested category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForSiblingCategoriesWhenSubTreeIsRequestedWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
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
								siblings(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (entity, parentItems) -> parentItems.isEmpty() && languagePredicate.test(entity, parentItems);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 2, categoryHierarchy,
							categoryCardinalities, false, false
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

	@DisplayName("Should return siblings for requested category 1 without statistics")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForSiblingsWithoutStatistics(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
									"megaMenu",
									entityFetch(attributeContent())
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (entity, parentItems) -> parentItems.isEmpty() && languagePredicate.test(entity, parentItems);
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, false
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

	@DisplayName("Should return children for categories with all statistics within requested category 2")
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
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate categoryPredicate = (sealedEntity, parentItems) -> {
					// has parent node 2
					return sealedEntity.getPrimaryKey() == 2 || (
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(2), it.getCode()))
					);
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for sibling categories with all statistics within requested category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategorySiblingsWhenSubTreeIsRequested(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate categoryPredicate = (sealedEntity, parentItems) -> {
					// has parent node 1
					return parentItems.size() == 1 && "1".equals(parentItems.get(0).getCode());
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
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

	@DisplayName("Should return children for categories with all statistics within category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesOfCertainCategory(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(2))),
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate categoryPredicate = (sealedEntity, parentItems) -> {
					final Integer categoryId = sealedEntity.getPrimaryKey();
					// has parent node 2
					return (
						Objects.equals(2, categoryId) ||
							parentItems
								.stream()
								.anyMatch(it -> Objects.equals(String.valueOf(2), it.getCode()))
					);
				};
				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for root categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForRootCategoriesInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && (level <= 1);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for requested categories with all statistics in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForRequestedCategoriesInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) &&
						(level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for requested category siblings with all statistics in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForRequestedCategorySiblingsInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) &&
						(level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
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

	@DisplayName("Should return children for specified category with all statistics in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForSpecifiedCategoryInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(distance(1)),
									statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					// has parent node 1
					final boolean hasParentNode = parentItems
						.stream()
						.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getPrimaryKey() == 1 || hasParentNode) && (level <= 2);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return root children for categories with all statistics in distance 1 within when no filtering constraint is specified")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForWithinCategoryInDistanceOne(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && (level <= 1);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true, true
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

	@DisplayName("Should return children for root categories with all statistics until shortcut category is reached")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForRootCategoriesAndStopAtShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> languagePredicate.test(sealedEntity, parentItems) && !sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedAndStopAtShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(node(filterBy(attributeEqualsFalse(ATTRIBUTE_SHORTCUT)))),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics until shortcut category is reached within requested category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForRequestedCategoryAndStopAtShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class) &&
						level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return siblings for categories with all statistics until shortcut category is reached within requested category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnSiblingsCardinalitiesForRequestedCategoryAndStopAtShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class) &&
						level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
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

	@DisplayName("Should return children for categories with all statistics except shortcut categories")
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
								hierarchyWithinRootSelf(
									excluding(
										attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
									)
								)
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

				final TestHierarchyPredicate languagePredicate =
					(entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE) &&
						!entity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics except shortcut categories within category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1, excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children categories with all statistics except shortcut categories for siblings of category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnSiblingCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6, excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								siblings(
									"megaMenu",
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities, true, true
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

	@DisplayName("Should return children for categories with all statistics except shortcut categories for category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForRequestedSubTreeExceptShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6, excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics until level 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesUntilLevelTwo(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedUntilLevelTwo(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(1)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								children(
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return children for categories with all statistics in category 1 until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesForSelectedSubTreeUntilLevelTwo(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(6)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContent()),
									stopAt(level(2)),
									statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT)
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					final boolean withinCategory1 = Objects.equals(1, sealedEntity.getPrimaryKey()) ||
						parentItems
							.stream()
							.anyMatch(it -> Objects.equals(String.valueOf(1), it.getCode()));
					return languagePredicate.test(sealedEntity, parentItems) && withinCategory1 && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false, true
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

	@DisplayName("Should return sorted children for categories with all statistics until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnSortedCardinalitiesUntilLevelTwo(Evita evita, List<SealedEntity> originalCategoryEntities, Hierarchy categoryHierarchy) {
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
								orderBy(
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
								),
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

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return languagePredicate.test(sealedEntity, parentItems) && level <= 2;
				};

				final HierarchyStatistics expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryEntities,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false, true,
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class))
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
	private Cardinalities computeCardinalities(
		Hierarchy categoryHierarchy,
		List<SealedEntity> allCategories,
		TestHierarchyPredicate filterPredicate,
		TestHierarchyPredicate treePredicate
	) {
		final Map<Integer, SealedEntity> categoryIndex = allCategories
			.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));

		final Set<Integer> categoriesWithValidPath = new HashSet<>();
		for (SealedEntity category : allCategories) {
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(category.getPrimaryKey()));
			if (treePredicate.test(category, parentItems)) {
				categoriesWithValidPath.add(category.getPrimaryKey());
			}
		}
		final Cardinalities categoryCardinalities = new Cardinalities();
		for (SealedEntity category : allCategories) {
			final int categoryId = category.getPrimaryKey();
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(categoryId));
			final List<Integer> categoryPath = Stream.concat(
				parentItems
					.stream()
					.map(it -> Integer.parseInt(it.getCode())),
				Stream.of(categoryId)
			).toList();
			if (categoryPath.stream().allMatch(cid -> filterPredicate.test(categoryIndex.get(cid), parentItems))) {
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
				}
			}
		}
		return categoryCardinalities;
	}

	@Nonnull
	private HierarchyStatistics computeExpectedStatistics(
		Hierarchy categoryHierarchy,
		List<SealedEntity> allCategories,
		TestHierarchyPredicate filterPredicate,
		TestHierarchyPredicate treePredicate,
		Function<Cardinalities, HierarchyStatisticsTuple> statisticsComputer
	) {
		final Cardinalities categoryCardinalities = computeCardinalities(categoryHierarchy, allCategories, filterPredicate, treePredicate);
		final HierarchyStatisticsTuple result = statisticsComputer.apply(categoryCardinalities);
		final Map<String, List<LevelInfo>> theResults = Map.of(
			result.name(), result.levelInfos()
		);
		return new HierarchyStatistics(
			theResults,
			Collections.emptyMap()
		);
	}

	interface TestHierarchyPredicate {

		boolean test(SealedEntity entity, List<HierarchyItem> parentItems);

		@Nonnull
		default TestHierarchyPredicate and(@Nonnull TestHierarchyPredicate other) {
			Objects.requireNonNull(other);
			return (hierarchyNodeId, level) ->
				test(hierarchyNodeId, level) && other.test(hierarchyNodeId, level);
		}

	}

	private static class Cardinalities implements CardinalityProvider {
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

		@Override
		public int getCardinality(int categoryId) {
			return itemCardinality.get(categoryId);
		}

		@Override
		public int getChildrenCount(int categoryId) {
			return childrenItemCount.get(categoryId);
		}

	}

}
