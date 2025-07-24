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

package io.evitadb.api.functional.attribute;

import com.github.javafaker.Faker;
import io.evitadb.api.functional.attribute.AbstractEntityByAttributeFilteringFunctionalTest.PredicateWithComparatorTuple;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.evitadb.api.functional.attribute.AbstractEntityByAttributeFilteringFunctionalTest.assertSortedResultIs;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_EUR;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies whether entities can be filtered by attributes.
 *
 * TOBEDONE JNO - create functional tests - one run with enabled SelectionFormula, one with disabled
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public abstract class AbstractEntityByReferenceAttributeOrderingFunctionalTest {
	private static final String HUNDRED_PRODUCTS_WITH_REFERENCES = "HundredProductsWithReferences";
	private static final String ATTRIBUTE_BRAND_PRIORITY = "brandPriority";
	private static final String ATTRIBUTE_STORE_PRIORITY = "storePriority";

	private static final int SEED = 40;

	@Nonnull
	private static Comparator<SealedEntity> createBrandReferencePrimaryKeyComparator() {
		return (sealedEntityA, sealedEntityB) -> {
			final ReferenceContract o1 = sealedEntityA.getReferences(Entities.BRAND).stream()
				.findFirst()
				.orElse(null);
			final ReferenceContract o2 = sealedEntityB.getReferences(Entities.BRAND).stream()
				.findFirst()
				.orElse(null);
			if (o1 == null && o2 != null) {
				return 1;
			} else if (o2 == null && o1 != null) {
				return -1;
			} else if (o1 == null) {
				return Integer.compare(sealedEntityA.getPrimaryKeyOrThrowException(), sealedEntityB.getPrimaryKeyOrThrowException());
			} else {
				return Integer.compare(o2.getReferencedPrimaryKey(), o1.getReferencedPrimaryKey());
			}
		};
	}

	private static Comparator<SealedEntity> createBrandReferenceComparator() {
		return (sealedEntityA, sealedEntityB) -> {
			final ReferenceContract o1 = sealedEntityA.getReferences(Entities.BRAND).stream()
				.filter(it -> it.getAttribute(ATTRIBUTE_BRAND_PRIORITY, Long.class) != null)
				.findFirst()
				.orElse(null);
			final ReferenceContract o2 = sealedEntityB.getReferences(Entities.BRAND).stream()
				.filter(it -> it.getAttribute(ATTRIBUTE_BRAND_PRIORITY, Long.class) != null)
				.findFirst()
				.orElse(null);
			if (o1 == null && o2 != null) {
				return 1;
			} else if (o2 == null && o1 != null) {
				return -1;
			} else if (o1 == null) {
				return Integer.compare(sealedEntityA.getPrimaryKeyOrThrowException(), sealedEntityB.getPrimaryKeyOrThrowException());
			} else {
				final Long priorityA = o1.getAttribute(ATTRIBUTE_BRAND_PRIORITY, Long.class);
				final Long priorityB = o2.getAttribute(ATTRIBUTE_BRAND_PRIORITY, Long.class);
				int attrCompare = priorityB.compareTo(priorityA);
				if (attrCompare == 0) {
					return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
				} else {
					return attrCompare;
				}
			}
		};
	}

	private static Comparator<SealedEntity> createCategoryReferenceComparator(int[] depthFirstOrder, Set<Integer> categoryIds) {
		return (sealedEntityA, sealedEntityB) -> {
			final Comparator<ReferenceContract> depthComparator = (o1, o2) -> {
				final int o1CategoryDepthOrder = ArrayUtils.indexOf(o1.getReferencedPrimaryKey(), depthFirstOrder);
				final int o2CategoryDepthOrder = ArrayUtils.indexOf(o2.getReferencedPrimaryKey(), depthFirstOrder);
				if (o1CategoryDepthOrder < o2CategoryDepthOrder) {
					return -1;
				} else if (o1CategoryDepthOrder > o2CategoryDepthOrder) {
					return 1;
				} else {
					return 0;
				}
			};
			final ReferenceContract o1 = sealedEntityA.getReferences(Entities.CATEGORY)
				.stream()
				.filter(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class) != null)
				.filter(it -> categoryIds.contains(it.getReferencedPrimaryKey()))
				.min(depthComparator)
				.orElse(null);
			final ReferenceContract o2 = sealedEntityB.getReferences(Entities.CATEGORY)
				.stream()
				.filter(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class) != null)
				.filter(it -> categoryIds.contains(it.getReferencedPrimaryKey()))
				.min(depthComparator)
				.orElse(null);

			final Long o1priority = ofNullable(o1).map(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class)).orElse(null);
			final Long o2priority = ofNullable(o2).map(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class)).orElse(null);

			if (o1priority == null && o2priority != null) {
				return 1;
			} else if (o2priority == null && o1priority != null) {
				return -1;
			} else if (o1priority == null) {
				return Integer.compare(sealedEntityA.getPrimaryKeyOrThrowException(), sealedEntityB.getPrimaryKeyOrThrowException());
			} else {
				final int o1CategoryDepthOrder = ArrayUtils.indexOf(o1.getReferencedPrimaryKey(), depthFirstOrder);
				final int o2CategoryDepthOrder = ArrayUtils.indexOf(o2.getReferencedPrimaryKey(), depthFirstOrder);
				if (o1CategoryDepthOrder < o2CategoryDepthOrder) {
					return -1;
				} else if (o1CategoryDepthOrder > o2CategoryDepthOrder) {
					return 1;
				} else {
					final int priorityComparison = Long.compare(o2priority, o1priority);
					if (priorityComparison == 0) {
						return Integer.compare(sealedEntityA.getPrimaryKeyOrThrowException(), sealedEntityB.getPrimaryKeyOrThrowException());
					} else {
						return priorityComparison;
					}
				}
			}
		};
	}

	@DataSet(value = HUNDRED_PRODUCTS_WITH_REFERENCES, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator();
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(30)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(20)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs ->
										/* we can specify special attributes on relation */
										makeReferenceIndexed(whichIs)
											.withAttribute(ATTRIBUTE_BRAND_PRIORITY, Long.class, thatIs -> thatIs.sortable())
								)
								.withReferenceToEntity(
									Entities.STORE,
									Entities.STORE,
									Cardinality.ONE_OR_MORE,
									whichIs ->
										/* we can specify special attributes on relation */
										makeReferenceIndexed(whichIs)
											.withAttribute(ATTRIBUTE_STORE_PRIORITY, Long.class, thatIs -> thatIs.filterable().sortable())
								);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			final List<SealedEntity> originalProductEntities = storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.collect(Collectors.toList());

			return new DataCarrier(
				tuple("originalProductEntities", originalProductEntities),
				tuple("categoryHierarchy", dataGenerator.getHierarchy(Entities.CATEGORY))
			);
		});
	}

	/**
	 * Configures the provided ReferenceSchemaBuilder to be indexed.
	 *
	 * @param whichIs the ReferenceSchemaBuilder instance to be configured as indexed
	 * @return the configured ReferenceSchemaBuilder instance
	 */
	@Nonnull
	protected abstract ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs);

	@DisplayName("Should return entities sorted by primary key of referenced entity")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToPrimaryKeyOnReferencedEntity(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> resultA = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								entityProperty(
									entityPrimaryKeyNatural(DESC)
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

				assertSortedResultIs(
					originalProductEntities,
					resultA.getRecordData(),
					sealedEntity -> true,
					createBrandReferencePrimaryKeyComparator()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by primary key of referenced entity (directly)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToPrimaryKeyOnReferencedEntityDirectly(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> resultA = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								entityPrimaryKeyNatural(DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertSortedResultIs(
					originalProductEntities,
					resultA.getRecordData(),
					sealedEntity -> true,
					createBrandReferencePrimaryKeyComparator()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by attribute defined on referenced entity")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToAttributeOnReferencedEntity(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_PRIORITY, DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> true,
					createBrandReferenceComparator()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by attributes defined on different referenced entities")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToAttributesOnReferencedEntity(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final int[] depthFirstOrder = categoryHierarchy.getRootItems()
			.stream()
			.mapToInt(it -> Integer.parseInt(it.getCode()))
			.flatMap(it -> Arrays.stream(depthFirstLookup(categoryHierarchy, it)))
			.toArray();
		final Set<Integer> categoryIds = Arrays.stream(depthFirstOrder)
			.boxed()
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_BRAND_PRIORITY, DESC)
							),
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> true,
					new PredicateWithComparatorTuple(
						sealedEntity -> sealedEntity.getReferences(Entities.BRAND).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_BRAND_PRIORITY) != null),
						createBrandReferenceComparator()
					),
					new PredicateWithComparatorTuple(
						sealedEntity -> sealedEntity.getReferences(Entities.CATEGORY).stream().anyMatch(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY) != null),
						createCategoryReferenceComparator(depthFirstOrder, categoryIds)
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by attribute defined on referenced entity with cardinality zero or more")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToAttributeOnMultipleReferencedEntitiesOfSameType(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.STORE,
								attributeNatural(ATTRIBUTE_STORE_PRIORITY, DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);


				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> true,
					(sealedEntityA, sealedEntityB) -> {
						final ReferenceContract o1 = sealedEntityA.getReferences(Entities.STORE)
							.stream()
							.min(Comparator.comparingInt(ReferenceContract::getReferencedPrimaryKey))
							.orElse(null);
						final ReferenceContract o2 = sealedEntityB.getReferences(Entities.STORE)
							.stream()
							.min(Comparator.comparingInt(ReferenceContract::getReferencedPrimaryKey))
							.orElse(null);

						if (o1 != null && o2 != null) {
							final Long o1priority = o1.getAttribute(ATTRIBUTE_STORE_PRIORITY, Long.class);
							final Long o2priority = o2.getAttribute(ATTRIBUTE_STORE_PRIORITY, Long.class);
							return Long.compare(o2priority, o1priority);
						} else if (o1 == null && o2 != null) {
							return 1;
						} else if (o1 != null) {
							return -1;
						} else {
							return Integer.compare(sealedEntityA.getPrimaryKeyOrThrowException(), sealedEntityB.getPrimaryKeyOrThrowException());
						}
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by attribute defined on referenced hierarchy entity with cardinality zero or more according to depth first search order")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToAttributeOnHierarchyEntitySubTree(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final int[] depthFirstOrder = depthFirstLookup(categoryHierarchy, 7);
		final Set<Integer> categoryIds = Arrays.stream(depthFirstOrder)
			.boxed()
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(7))
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(it -> categoryIds.contains(it.getReferencedPrimaryKey())),
					createCategoryReferenceComparator(depthFirstOrder, categoryIds)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by attribute defined on referenced hierarchy entity with cardinality zero or more according to depth first search order for entire tree")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldSortEntitiesAccordingToAttributeOnHierarchyEntityTree(Evita evita, List<SealedEntity> originalProductEntities, Hierarchy categoryHierarchy) {
		final int[] depthFirstOrder = categoryHierarchy.getRootItems()
			.stream()
			.mapToInt(it -> Integer.parseInt(it.getCode()))
			.flatMap(it -> Arrays.stream(depthFirstLookup(categoryHierarchy, it)))
			.toArray();
		final Set<Integer> categoryIds = Arrays.stream(depthFirstOrder)
			.boxed()
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithinRoot(Entities.CATEGORY)
						),
						orderBy(
							referenceProperty(
								Entities.CATEGORY,
								attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, DESC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getReferences(Entities.CATEGORY)
						.stream()
						.anyMatch(it -> categoryIds.contains(it.getReferencedPrimaryKey())),
					createCategoryReferenceComparator(depthFirstOrder, categoryIds)
				);
				return null;
			}
		);
	}

	@DisplayName("Should allow filtering on non-fetched references")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_REFERENCES)
	@Test
	void shouldFilterOnNonFetchedReferences(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities
			.stream()
			.filter(it -> it.getLocales().contains(Locale.ENGLISH) && !it.getPrices(CURRENCY_EUR, "basic").isEmpty())
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(it2 -> it2.getAttribute(ATTRIBUTE_STORE_PRIORITY, Long.class) != null))
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									and(
										entityLocaleEquals(Locale.ENGLISH),
										attributeEquals(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)),
										priceInPriceLists("basic"),
										priceInCurrency(CURRENCY_EUR)
									)
								),
								require(
									debug(DebugMode.PREFER_PREFETCHING),
									entityFetch(
										referenceContentWithAttributes(
											Entities.STORE,
											filterBy(
												attributeGreaterThanEquals(
													ATTRIBUTE_STORE_PRIORITY,
													entity.getReferences(Entities.STORE)
														.stream()
														.map(it -> it.getAttribute(ATTRIBUTE_STORE_PRIORITY, Long.class))
														.findFirst()
														.orElseThrow()
												)
											),
											attributeContent(ATTRIBUTE_STORE_PRIORITY),
											entityFetch(
												attributeContent(ATTRIBUTE_NAME),
												dataInLocales(Locale.ENGLISH)
											)
										)
									)
								)
							),
							SealedEntity.class
						);
				assertEquals(1, result.getRecordData().size());
				return null;
			}
		);
	}

	private int[] depthFirstLookup(Hierarchy categoryHierarchy, int categoryId) {
		return IntStream.concat(
			IntStream.of(categoryId),
			categoryHierarchy.getChildItems(Integer.toString(categoryId))
				.stream()
				.flatMapToInt(it -> Arrays.stream(depthFirstLookup(categoryHierarchy, Integer.parseInt(it.getCode()))))
		).toArray();
	}

}
