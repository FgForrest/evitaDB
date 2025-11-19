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

package io.evitadb.api.functional.hierarchy;

import com.github.javafaker.Faker;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies whether entities can be filtered by hierarchy constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by hierarchy functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
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

			final List<EntityReferenceContract> storedCategories = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleCategorySchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_SHORTCUT, Boolean.class, AttributeSchemaEditor::filterable);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			final List<SealedEntity> categoriesAvailable = storedCategories.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), hierarchyContent(), attributeContentAll(), referenceContentAll(), dataInLocalesAll()).orElseThrow())
				.collect(Collectors.toList());
			return new DataCarrier(
				tuple("originalCategoryEntities", categoriesAvailable),
				tuple("originalCategoryIndex", categoriesAvailable.stream().collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()))),
				tuple("categoryHierarchy", this.dataGenerator.getHierarchy(Entities.CATEGORY))
			);
		});
	}

	@DisplayName("Should return root categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnRootCategories(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
	void shouldReturnAllCategoriesExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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

	@DisplayName("Should return all categories having specified subtrees")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllCategoriesHavingSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		final Predicate<SealedEntity> includedPredicate = sealedEntity ->
			// is not directly excluded node
			!excluded.contains(sealedEntity.getPrimaryKey()) &&
				// has no parent node that is in excluded set
				categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
					.stream()
					.map(it -> Integer.parseInt(it.getCode()))
					.noneMatch(excluded::contains);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinRootSelf(
								having(
									entityPrimaryKeyInSet(
										originalCategoryEntities.stream()
											.filter(includedPredicate)
											.map(EntityContract::getPrimaryKey).distinct().toArray(Integer[]::new)
									)
								)
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
					includedPredicate,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all categories having specific child")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllCategoriesAnyHavingMatchingChild(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> requestedChildren = new HashSet<>(Arrays.asList(1, 7, 13, 16, 40, 55));
		final Set<Integer> parentsOfRequestedChildren = requestedChildren
			.stream()
			.flatMap(
				pk -> categoryHierarchy.getParentItems(String.valueOf(pk))
					.stream()
					.map(HierarchyItem::getCode)
					.map(Integer::parseInt)
			)
			.collect(Collectors.toSet());
		final Predicate<SealedEntity> includedPredicate = sealedEntity ->
			// is requested child
			requestedChildren.contains(sealedEntity.getPrimaryKey()) ||
				// is parent of requested children
				parentsOfRequestedChildren.contains(sealedEntity.getPrimaryKey());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinRootSelf(
								anyHaving(
									entityPrimaryKeyInSet(
										originalCategoryEntities.stream()
											.filter(includedPredicate)
											.map(EntityContract::getPrimaryKey).distinct().toArray(Integer[]::new)
									)
								)
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
					includedPredicate,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subcategories of lower category")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnLowerLevelCategories(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(7), directRelation())),
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
						// is directly parent
						sealedEntity.getPrimaryKey() == 7 ||
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

	@DisplayName("Should return all subcategories of shortcuts")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnSubCategoriesInAllShortCuts(Evita evita, List<SealedEntity> originalCategoryEntities, Map<Integer, SealedEntity> originalCategoryIndex) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								attributeEqualsTrue(ATTRIBUTE_SHORTCUT),
								directRelation()
							)
						),
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
					sealedEntity -> Stream.concat(
							Stream.of(sealedEntity),
							sealedEntity.getParentEntity()
								.stream()
								.map(EntityClassifier::getPrimaryKey)
								.map(originalCategoryIndex::get)
						)
						.anyMatch(it -> it.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return all subcategories of shortcuts and their children")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnSubCategoriesInAllShortCutsAndTheirChildren(Evita evita, List<SealedEntity> originalCategoryEntities, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								attributeEqualsTrue(ATTRIBUTE_SHORTCUT)
							)
						),
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
					sealedEntity -> Stream.concat(
							Stream.of(sealedEntity),
							categoryHierarchy.getParentItems(String.valueOf(sealedEntity.getPrimaryKey()))
								.stream()
								.map(it -> Integer.parseInt(it.getCode()))
								.map(originalCategoryIndex::get)
						)
						.anyMatch(it -> it.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subcategories in selected subtree")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnAllChildCategories(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(7))),
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
	void shouldReturnOnlyChildCategories(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final int categoryId = 9;
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(categoryId), excludingRoot())),
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
						// is not exactly node 9
						!Objects.equals(sealedEntity.getPrimaryKey().toString(), String.valueOf(categoryId)) &&
							// but has parent node 9
							categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString())
								.stream()
								.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(categoryId))),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subtree categories only of specified subtrees")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeOnlyOfSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> included = new HashSet<>(Arrays.asList(6, 13, 20, 25));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								entityPrimaryKeyInSet(1),
								having(entityPrimaryKeyInSet(included.toArray(new Integer[0])))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				final Set<Integer> includedAndRoot = Stream.concat(
						Stream.of(1),
						included.stream()
					)
					.collect(Collectors.toSet());

				assertResultIs(
					originalCategoryEntities,
					sealedEntity -> {
						final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(sealedEntity.getPrimaryKey().toString());
						return
							// is directly excluded node
							includedAndRoot.contains(sealedEntity.getPrimaryKey()) &&
								// has included parent node
								parentItems
									.stream()
									.map(it -> Integer.parseInt(it.getCode()))
									.allMatch(it -> includedAndRoot.contains(it) || Objects.equals(1, it));
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subtree categories only of specified subtrees except root")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeOnlyOfSpecifiedSubtreesExceptRoot(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> included = new HashSet<>(Arrays.asList(6, 13, 20, 25));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								entityPrimaryKeyInSet(1),
								having(entityPrimaryKeyInSet(included.toArray(new Integer[0]))),
								excludingRoot()
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
							// is directly excluded node
							included.contains(sealedEntity.getPrimaryKey()) &&
								// has included parent node
								parentItems
									.stream()
									.map(it -> Integer.parseInt(it.getCode()))
									.allMatch(it -> included.contains(it) || Objects.equals(1, it));
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return subtree categories except specified subtrees")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeExceptSpecifiedSubtrees(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))))),
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

	@DisplayName("Should return subtree categories except specified subtrees without affecting root node")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCategorySubtreeExceptSpecifiedSubtreesWithoutAffectingRootNode(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(2, 43, 34, 53));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								entityPrimaryKeyInSet(1),
								excluding(
									entityPrimaryKeyInSet(excluded.toArray(new Integer[0]))
								)
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
	void shouldReturnCategorySubtreeExceptSpecifiedSubtreesAndMatchingCertainConstraint(Evita evita, List<SealedEntity> originalCategoryEntities, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		final Set<Integer> excluded = new HashSet<>(Arrays.asList(14, 26));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								or(
									attributeLessThan(ATTRIBUTE_PRIORITY, 300000),
									attributeStartsWith(ATTRIBUTE_CODE, "B")
								),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(1),
									excluding(entityPrimaryKeyInSet(excluded.toArray(new Integer[0])))
								)
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
								ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE, String.class)).map(it -> !it.isEmpty() && it.charAt(0) == 'B').orElse(false) ||
									ofNullable(sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)).map(it -> ((Long) it) < 300000).orElse(false)
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

	@DisplayName("Should fail to return hierarchical entities with localize attribute without specifying locale")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldFailToRetrieveEntitiesWithLocalizedAttributesWithoutSpecifyingLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.query(
						query(
							collection(Entities.CATEGORY),
							filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1))),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
								hierarchyOfSelf(
									fromRoot(
										"megaMenu",
										entityFetch(
											attributeContent(
												ATTRIBUTE_NAME
											)
										)
									)
								)
							)
						),
						EntityReference.class
					)
				);

				return null;
			}
		);
	}

	@DisplayName("Should return cardinalities for categories when filter constraint is eliminated")
	@UseDataSet(THOUSAND_CATEGORIES)
	@Test
	void shouldReturnCardinalitiesForCategoriesWhenFilterConstraintIsEliminated(Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinSelf(entityPrimaryKeyInSet(1))),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							hierarchyOfSelf(
								fromRoot(
									"megaMenu",
									entityFetch(attributeContentAll(), dataInLocales(CZECH_LOCALE))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate,
					languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy, categoryCardinalities,
							false,
							false, false,
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategories(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories for category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForRequestedNode(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return parents for categories for requested category 53")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForParents(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(53))
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.concat(
						categoryHierarchy.getParentItems("53").stream().map(it -> Integer.parseInt(it.getCode())),
						Stream.of(53)
					)
					.collect(Collectors.toSet());
				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) ->
						entity.getLocales().contains(CZECH_LOCALE) &&
							(allowedParents.contains(entity.getPrimaryKey()) || parentItems.stream().anyMatch(it -> it.getCode().equals("53")));
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 53, categoryHierarchy,
							categoryCardinalities, null,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							53
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return parents with siblings for categories for requested category 53")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForParentsAndSiblings(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(53))
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
									entityFetch(attributeContentAll()),
									siblings(),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.concat(
						categoryHierarchy.getParentItems("53").stream().map(it -> Integer.parseInt(it.getCode())),
						Stream.of(53)
					)
					.collect(Collectors.toSet());
				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) ->
					{
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("53"));
						return entity.getLocales().contains(CZECH_LOCALE) &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfRequestedCategory);
					};
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 53, categoryHierarchy,
							categoryCardinalities, entity -> true,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							53
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return parents with filtered siblings for categories for requested category 43")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForParentsAndFilteredSiblings(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> allowedParents = Stream.concat(
						categoryHierarchy.getParentItems("43").stream().map(it -> Integer.parseInt(it.getCode())),
						Stream.of(53)
					)
					.collect(Collectors.toSet());

				final AtomicInteger cnt = new AtomicInteger(1);
				final Set<Integer> disallowedParents =
					categoryHierarchy.getChildItems(
							categoryHierarchy.getParentItem(
								categoryHierarchy.getParentItem("43").getCode()
							).getCode()
						)
						.stream()
						.map(it -> Integer.parseInt(it.getCode()))
						.filter(it -> !allowedParents.contains(it) && cnt.getAndIncrement() % 2 == 0)
						.collect(Collectors.toSet());

				assertTrue(disallowedParents.size() > 1);

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(43),
									excluding(entityPrimaryKeyInSet(disallowedParents.toArray(Integer[]::new)))
								)
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
									entityFetch(attributeContentAll()),
									siblings(),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate filterPredicate = (entity, parentItems) -> {
					final boolean isNotInDisallowedParents = !disallowedParents.contains(entity.getPrimaryKey()) && parentItems.stream().noneMatch(it -> disallowedParents.contains(Integer.parseInt(it.getCode())));
					return entity.getLocales().contains(CZECH_LOCALE) && isNotInDisallowedParents;
				};
				final TestHierarchyPredicate scopePredicate =
					(entity, parentItems) -> {
						final boolean isDirectParent = allowedParents.contains(entity.getPrimaryKey());
						final boolean isRoot = parentItems.isEmpty();
						final boolean isNotInDisallowedParents = !disallowedParents.contains(entity.getPrimaryKey()) && parentItems.stream().noneMatch(it -> disallowedParents.contains(Integer.parseInt(it.getCode())));
						final boolean isChildOfAnyParent = !isRoot && allowedParents.contains(Integer.parseInt(parentItems.get(parentItems.size() - 1).getCode()));
						final boolean isChildOfRequestedCategory = parentItems.stream().anyMatch(it -> it.getCode().equals("53"));
						return entity.getLocales().contains(CZECH_LOCALE) && isNotInDisallowedParents &&
							(isDirectParent || isRoot || isChildOfAnyParent || isChildOfRequestedCategory);
					};
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 43, categoryHierarchy,
							categoryCardinalities, entity -> !disallowedParents.contains(entity.getPrimaryKey()),
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							43
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return parents with siblings with level below for categories for requested category 30")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForParentsAndSiblingsWithLevelBelow(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(30)
								)
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
									entityFetch(attributeContentAll()),
									siblings(stopAt(distance(1))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Set<Integer> allowedParents = Stream.concat(
						categoryHierarchy.getParentItems("30").stream().map(it -> Integer.parseInt(it.getCode())),
						Stream.of(30)
					)
					.collect(Collectors.toSet());
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeParents(
							session, 30, categoryHierarchy,
							categoryCardinalities, entity -> true,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							30
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return root siblings for categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForSiblingCategoriesWhenRootIsRequested(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
								siblings(
									"megaMenu",
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate scopePredicate = (entity, parentItems) -> parentItems.isEmpty() && languagePredicate.test(entity, parentItems);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate,
					scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, null, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT), statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories within requested category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequested(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(2)
								)
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							2
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for sibling categories within requested category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategorySiblingsWhenSubTreeIsRequested(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(6)
								)
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories within category 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesOfCertainCategory(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate,
					(entity, parentItems) -> categoryPredicate.test(entity, parentItems) && languagePredicate.test(entity, parentItems),
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 2, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRootCategoriesInDistanceOne(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(2))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							2
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested categories in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategoriesInDistanceOne(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(1))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for requested category siblings in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategorySiblingsInDistanceOne(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(level <= 3);
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for specified category in distance 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForSpecifiedCategoryInDistanceOne(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return root children for categories in distance 1 within when no filtering constraint is specified")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForWithinCategoryInDistanceOne(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									entityFetch(attributeContentAll()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, true,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for root categories until shortcut category is reached")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRootCategoriesAndStopAtShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) ->
					languagePredicate.test(sealedEntity, parentItems) &&
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories until shortcut category is reached within category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedAndStopAtShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories until shortcut category is reached within requested category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForRequestedCategoryAndStopAtShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(1))
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
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return siblings for categories until shortcut category is reached within requested category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSiblingsCardinalitiesForRequestedCategoryAndStopAtShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
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
									stopAt(node(filterBy(attributeEqualsTrue(ATTRIBUTE_SHORTCUT)))),
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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
						(sealedEntity.getParentEntity().isEmpty() ||
							!originalCategoryIndex.get(sealedEntity.getParentEntity().get().getPrimaryKey()).getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class));
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT), statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories except shortcut categories")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesExceptShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate languagePredicate =
					(entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE) &&
						!entity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, languagePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories except shortcut categories within category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(1),
									excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT))
								)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return sibling categories except shortcut categories for siblings of category 6")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSiblingCardinalitiesForCategoriesWhenSubTreeIsRequestedExceptShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(6),
									excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT))
								)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate filterPredicate =
					(sealedEntity, parentItems) -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						!sealedEntity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				final TestHierarchyPredicate scopePredicate = (sealedEntity, parentItems) -> {
					final boolean childOfCategory1 = parentItems.size() == 1 && Objects.equals(1, Integer.parseInt(parentItems.get(0).getCode()));
					return filterPredicate.test(sealedEntity, parentItems) && childOfCategory1;
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, scopePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeSiblings(
							session, 6, categoryHierarchy,
							categoryCardinalities,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT), statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories except shortcut categories for category 1")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForRequestedSubTreeExceptShortCuts(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(6),
									excluding(attributeEqualsTrue(ATTRIBUTE_SHORTCUT))
								)
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories until level 2")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesUntilLevelTwo(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories in category 1 until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesWhenSubTreeIsRequestedUntilLevelTwo(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(1))
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							1
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for categories in category 1 until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnCardinalitiesForCategoriesForSelectedSubTreeUntilLevelTwo(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, 1, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							6
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return sorted children for categories until level two")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource("statisticTypeVariants")
	void shouldReturnSortedCardinalitiesUntilLevelTwo(EnumSet<StatisticsType> statisticsType, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
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
									statisticsType.isEmpty() ? null : statistics(statisticsType.toArray(StatisticsType[]::new))
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


				final Collator collator = Collator.getInstance(CZECH_LOCALE);
				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					languagePredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT),
							(o1, o2) -> {
								final String name1 = o1.getAttribute(ATTRIBUTE_NAME, String.class);
								final String name2 = o2.getAttribute(ATTRIBUTE_NAME, String.class);
								return collator.compare(name1, name2);
							}
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@DisplayName("Should return children for all categories until level three on different filter base")
	@UseDataSet(THOUSAND_CATEGORIES)
	@ParameterizedTest
	@MethodSource({"statisticTypeAndBaseVariants"})
	void shouldReturnChildrenToLevelThreeForDifferentFilterBase(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, Map<Integer, SealedEntity> originalCategoryIndex, one.edee.oss.pmptt.model.Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								userFilter(
									attributeEqualsFalse(ATTRIBUTE_SHORTCUT)
								),
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
									stopAt(level(3)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final TestHierarchyPredicate filterPredicate;
				if (base == StatisticsBase.COMPLETE_FILTER) {
					filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE)
						&& !entity.getAttribute(ATTRIBUTE_SHORTCUT, Boolean.class);
				} else {
					filterPredicate = (entity, parentItems) -> entity.getLocales().contains(CZECH_LOCALE);
				}
				final TestHierarchyPredicate treePredicate = (sealedEntity, parentItems) -> {
					final int level = parentItems.size() + 1;
					return filterPredicate.test(sealedEntity, parentItems) && level <= 3;
				};

				final Hierarchy expectedStatistics = computeExpectedStatistics(
					categoryHierarchy, originalCategoryIndex,
					filterPredicate, treePredicate,
					categoryCardinalities -> new HierarchyStatisticsTuple(
						"megaMenu",
						computeChildren(
							session, null, categoryHierarchy,
							categoryCardinalities, false,
							statisticsType.contains(StatisticsType.CHILDREN_COUNT),
							statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)
						)
					)
				);

				final Hierarchy statistics = result.getExtraResult(Hierarchy.class);
				assertNotNull(statistics);
				assertEquals(expectedStatistics, statistics);

				return null;
			}
		);
	}

	@Nonnull
	private static Cardinalities computeCardinalities(
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		TestHierarchyPredicate filterPredicate,
		TestHierarchyPredicate scopePredicate
	) {
		final Set<Integer> categoriesWithValidPath = new HashSet<>();
		for (SealedEntity category : categoryIndex.values()) {
			final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(category.getPrimaryKey()));
			if (scopePredicate.test(category, parentItems)) {
				categoriesWithValidPath.add(category.getPrimaryKey());
			}
		}
		final Cardinalities categoryCardinalities = new Cardinalities();
		for (SealedEntity category : categoryIndex.values()) {
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
	private static Hierarchy computeExpectedStatistics(
		one.edee.oss.pmptt.model.Hierarchy categoryHierarchy,
		Map<Integer, SealedEntity> categoryIndex,
		TestHierarchyPredicate filterPredicate,
		TestHierarchyPredicate treePredicate,
		Function<Cardinalities, HierarchyStatisticsTuple> statisticsComputer
	) {
		final Cardinalities categoryCardinalities = computeCardinalities(categoryHierarchy, categoryIndex, filterPredicate, treePredicate);
		final HierarchyStatisticsTuple result = statisticsComputer.apply(categoryCardinalities);
		final Map<String, List<LevelInfo>> theResults = Map.of(
			result.name(), result.levelInfos()
		);
		return new Hierarchy(
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
		private final EmptyHierarchicalEntityBehaviour emptyBehaviour = REMOVE_EMPTY;

		public void record(int categoryId) {
			this.itemCardinality.put(categoryId, 0);
			this.childrenItemCount.put(categoryId, 0);
		}

		public void recordChild(int categoryId) {
			this.itemCardinality.merge(categoryId, 1, Integer::sum);
			this.childrenItemCount.merge(categoryId, 1, Integer::sum);
		}

		public void recordDistantChild(int categoryId) {
			this.itemCardinality.merge(categoryId, 1, Integer::sum);
		}

		public boolean isValid(int categoryId) {
			return this.emptyBehaviour == LEAVE_EMPTY || this.itemCardinality.containsKey(categoryId);
		}

		@Override
		public int getCardinality(int categoryId) {
			return ofNullable(this.itemCardinality.get(categoryId)).orElse(0);
		}

		@Override
		public int getChildrenCount(int categoryId) {
			return ofNullable(this.childrenItemCount.get(categoryId)).orElse(0);
		}

	}

}
