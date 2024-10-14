/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api;


import com.github.javafaker.Faker;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeLessThanEquals;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.Segment;
import io.evitadb.api.query.order.Segments;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test verifies segmented output and spacing rules for paginated results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Evita entity view port rules functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityViewPortRulesFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProductsForViewPortTesting";
	private static final int SEED = 42;

	/**
	 * Generates a stream of randomized segments based on specific attributes and order directions.
	 * The method creates segments using a set of sortable attributes and randomized settings.
	 * Each segment can either include or exclude a filtering condition based on a generated set of ASCII character limits.
	 *
	 * @return a stream of Arguments, each containing a randomized segment configuration.
	 */
	@Nonnull
	static Stream<Arguments> randomizedSegments() {
		final Set<String> sortableAttributes = Set.of(ATTRIBUTE_NAME, ATTRIBUTE_EAN, ATTRIBUTE_QUANTITY);
		final java.util.Random random = new java.util.Random(SEED);
		// generate all ASCII characters from B ... T
		final List<String> characters = IntStream.rangeClosed('B', 'T')
			.mapToObj(i -> String.valueOf((char) i))
			.toList();
		return Stream.of(
			IntStream.generate(() -> 1)
				.limit(50)
				.mapToObj(i -> {
					int segmentCount = 1 + random.nextInt(3);
					final Set<String> attributesToChooseFrom = new HashSet<>(sortableAttributes);
					return segments(
						IntStream.range(0, segmentCount)
							.mapToObj(j -> {
								final String attributeName = attributesToChooseFrom.stream().skip(random.nextInt(attributesToChooseFrom.size())).findFirst().orElseThrow();
								// avoid repeating sort for the same attribute
								attributesToChooseFrom.remove(attributeName);
								final OrderDirection orderDirection = random.nextBoolean() ? OrderDirection.ASC : OrderDirection.DESC;
								final int limit = 1 + random.nextInt(30);
								if (random.nextBoolean()) {
									return segment(
										entityHaving(attributeLessThanEquals(ATTRIBUTE_NAME, characters.get(random.nextInt(characters.size())))),
										orderBy(attributeNatural(attributeName, orderDirection)),
										limit(limit)
									);
								} else {
									return segment(
										orderBy(attributeNatural(attributeName, orderDirection)),
										limit(limit)
									);
								}
							})
							.toArray(Segment[]::new)
					);
				})
				.map(Arguments::of)
				.toArray(Arguments[]::new)
		);
	}

	/**
	 * Constructs a segmented query for fetching products from the database.
	 * The query consists of three segments ordered by different attributes with specified limits on each segment.
	 * It also includes pagination and debug settings for trying alternativa calculation paths.
	 *
	 * @param segments   the segments configuration.
	 * @param pageNumber the page number for pagination.
	 * @param pageSize   the number of items per page for pagination.
	 * @return a Query object with the specified configuration.
	 */
	@Nonnull
	private static Query fabricateSegmentedQuery(int pageNumber, int pageSize, @Nonnull Segments segments) {
		return query(
			collection(Entities.PRODUCT),
			orderBy(segments),
			require(
				page(pageNumber, pageSize),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
			)
		);
	}

	@DataSet(value = HUNDRED_PRODUCTS, readOnly = false, destroyAfterClass = true)
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
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						builder -> {
							builder
								.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized(() -> false).filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false));
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				"originalProductEntities",
				storedProducts.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList())
			);
		});
	}

	@DisplayName("Should return entities by matching locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@ParameterizedTest
	@MethodSource("randomizedSegments")
	void shouldTryDifferentVariations(Segments segments, Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// first create expected sorted list
				final Set<Integer> drainedPks = new HashSet<>(100);
				final List<Integer> orderedPks = new ArrayList<>(100);
				for (Segment segment : segments.getSegments()) {
					final AttributeNatural orderingConstraint = (AttributeNatural) segment.getOrderBy().getChild();
					final String attributeName = orderingConstraint.getAttributeName();
					final OrderDirection orderDirection = orderingConstraint.getOrderDirection();
					final Comparator<Comparable> comparator = orderDirection == OrderDirection.ASC ?
						Comparator.naturalOrder() : Comparator.reverseOrder();
					final Predicate<SealedEntity> filter = segment.getEntityHaving()
						.map(entityHaving -> {
							final AttributeLessThanEquals attributeFilter = (AttributeLessThanEquals) entityHaving.getChild();
							final String attrName = attributeFilter.getAttributeName();
							final Serializable value = attributeFilter.getAttributeValue();
							return (Predicate<SealedEntity>) sealedEntity -> sealedEntity.getAttribute(attrName, String.class).compareTo(value.toString()) <= 0;
						})
						.orElseGet(() -> sealedEntity -> true);
					originalProductEntities.stream()
						.filter(it -> filter.test(it) && !drainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> comparator.compare(
								o1.getAttribute(attributeName),
								o2.getAttribute(attributeName)
							)
						)
						.limit(segment.getLimit().orElse(Integer.MAX_VALUE))
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(drainedPks::add)
						.forEach(orderedPks::add);
				}
				// finally append all remaining entities sorted by PK in ascending order
				originalProductEntities.stream()
					.filter(it -> !drainedPks.contains(it.getPrimaryKey()))
					.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
					.mapToInt(SealedEntity::getPrimaryKey)
					.forEach(orderedPks::add);

				// now go through entire product set and verify contents page by page
				final int pageSize = 5;
				for (int i = 0; i < 100 / pageSize; i = i + pageSize) {
					int pageNumber = 1 + (i == 0 ? 0 : i / pageSize);
					final EvitaResponse<EntityReference> queryResponse = session.query(
						fabricateSegmentedQuery(pageNumber, pageSize, segments),
						EntityReference.class
					);
					assertSortedResultEquals(
						"Page " + pageNumber + " contains data sorted incorrectly.",
						queryResponse
							.getRecordData()
							.stream()
							.map(EntityReference::getPrimaryKey)
							.collect(Collectors.toList()),
						orderedPks.subList(i, i + pageSize)
							.stream()
							.mapToInt(Integer::intValue)
							.toArray()
					);
				}
			}
		);
	}

	@DisplayName("Should return entities in manually crafted segmented order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDifferentlySortedSegments(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> nameDrainedPks = new HashSet<>();
				final Set<Integer> eanDrainedPks = new HashSet<>();
				final Set<Integer> quantityDrainedPks = new HashSet<>();

				final Segments segments = segments(
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
						),
						limit(10)
					),
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
						),
						limit(8)
					),
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_QUANTITY, OrderDirection.ASC)
						),
						limit(6)
					)
				);

				assertSortedResultEquals(
					"First page must be sorted by name in descending order.",
					session.query(
							fabricateSegmentedQuery(1, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);
				assertSortedResultEquals(
					"Second page must be sorted by name in descending order.",
					session.query(
							fabricateSegmentedQuery(2, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.skip(5)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session.query(
							fabricateSegmentedQuery(3, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				final List<Integer> fourthPage = session.query(
						fabricateSegmentedQuery(4, 5, segments),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fourth page contains 3 entities sorted according to EAN in descending order.",
					fourthPage.subList(0, 3),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.skip(5)
						.limit(3)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fourth page ends with first 2 entities sorted according to quantity in ascending order.",
					fourthPage.subList(3, 5),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.limit(2)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				final List<Integer> fifthPage = session.query(
						fabricateSegmentedQuery(5, 5, segments),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order.",
					fifthPage.subList(0, 4),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.skip(2)
						.limit(4)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fifth page must end with first entity sorted by PK in ascending order.",
					fifthPage.subList(4, 5),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.limit(1)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(6, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(1)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(7, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(6)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return filtered entities in manually crafted segmented order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDifferentlySortedAndFilteredSegments(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> nameDrainedPks = new HashSet<>();
				final Set<Integer> eanDrainedPks = new HashSet<>();
				final Set<Integer> quantityDrainedPks = new HashSet<>();

				final Segments segments = segments(
					segment(
						entityHaving(
							attributeLessThanEquals(ATTRIBUTE_NAME, "L")
						),
						orderBy(
							attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
						),
						limit(10)
					),
					segment(
						entityHaving(
							attributeLessThanEquals(ATTRIBUTE_NAME, "P")
						),
						orderBy(
							attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
						),
						limit(8)
					),
					segment(
						entityHaving(
							attributeLessThanEquals(ATTRIBUTE_NAME, "T")
						),
						orderBy(
							attributeNatural(ATTRIBUTE_QUANTITY, OrderDirection.ASC)
						),
						limit(6)
					)
				);

				assertSortedResultEquals(
					"First page must be sorted by name in descending order.",
					session.query(
							fabricateSegmentedQuery(1, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("L") <= 0)
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);
				assertSortedResultEquals(
					"Second page must be sorted by name in descending order.",
					session.query(
							fabricateSegmentedQuery(2, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("L") <= 0)
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.skip(5)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session.query(
							fabricateSegmentedQuery(3, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("P") <= 0)
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				final List<Integer> fourthPage = session.query(
						fabricateSegmentedQuery(4, 5, segments),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fourth page contains 3 entities sorted according to EAN in descending order.",
					fourthPage.subList(0, 3),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("P") <= 0)
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.skip(5)
						.limit(3)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fourth page ends with first 2 entities sorted according to quantity in ascending order.",
					fourthPage.subList(3, 5),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("T") <= 0)
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.limit(2)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				final List<Integer> fifthPage = session.query(
						fabricateSegmentedQuery(5, 5, segments),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order.",
					fifthPage.subList(0, 4),
					originalProductEntities.stream()
						.filter(it -> it.getAttribute(ATTRIBUTE_NAME, String.class).compareTo("T") <= 0)
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.skip(2)
						.limit(4)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fifth page must end with first entity sorted by PK in ascending order.",
					fifthPage.subList(4, 5),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.limit(1)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(6, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(1)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(7, 5, segments),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(6)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				return null;
			}
		);
	}

	@DisplayName("Should insert spaces into paginated results")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldInsertSpaces(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HashSet<Integer> drainedPks = new HashSet<>();
				assertEquals(
					8,
					registerPks(
						session.query(
							fabricateSpacingQuery(1, 10),
							EntityReference.class
						),
						drainedPks
					)
				);
				assertEquals(
					9,
					registerPks(
						session.query(
							fabricateSpacingQuery(2, 10),
							EntityReference.class
						),
						drainedPks
					)
				);
				assertEquals(
					8,
					registerPks(
						session.query(
							fabricateSpacingQuery(3, 10),
							EntityReference.class
						),
						drainedPks
					)
				);
				assertEquals(
					9,
					registerPks(
						session.query(
							fabricateSpacingQuery(4, 10),
							EntityReference.class
						),
						drainedPks
					)
				);
				assertEquals(
					8,
					registerPks(
						session.query(
							fabricateSpacingQuery(5, 10),
							EntityReference.class
						),
						drainedPks
					)
				);
				final EvitaResponse<EntityReference> pageSixResponse = session.query(
					fabricateSpacingQuery(6, 10),
					EntityReference.class
				);
				assertEquals(
					9,
					registerPks(
						pageSixResponse,
						drainedPks
					)
				);

				final int lastPageNumber = ((PaginatedList<EntityReference>) pageSixResponse.getRecordPage()).getLastPageNumber();
				for (int i = 7; i < lastPageNumber; i++) {
					assertEquals(
						10,
						registerPks(
							session.query(
								fabricateSpacingQuery(i, 10),
								EntityReference.class
							),
							drainedPks
						),
						"Page " + i + " must contain 10 entities."
					);
				}

				final int lastPageExpectedCount = (3 * 2) + (3 * 1);
				assertEquals(
					lastPageExpectedCount,
					registerPks(
						session.query(
							fabricateSpacingQuery(lastPageNumber, 10),
							EntityReference.class
						),
						drainedPks
					),
					"Page " + lastPageNumber + " must contain " + lastPageExpectedCount + " entities."
				);

				assertEquals(100, drainedPks.size());
			}
		);
	}

	/**
	 * Registers the primary keys from the given EvitaResponse into the drainedPks set.
	 *
	 * @param response the EvitaResponse containing EntityReference objects from which primary keys are extracted
	 * @param drainedPks the set where the extracted primary keys will be added
	 * @return the number of primary keys extracted from the response
	 */
	private static int registerPks(@Nonnull EvitaResponse<EntityReference> response, @Nonnull Set<Integer> drainedPks) {
		response.getRecordData()
			.forEach(it -> {
				assertFalse(drainedPks.contains(it.getPrimaryKey()), "Primary key " + it.getPrimaryKey() + " must not be repeated.");
				drainedPks.add(it.getPrimaryKey());
			});
		return response.getRecordData().size();
	}

	/**
	 * Creates a query for retrieving paginated product entities with specified spacing conditions.
	 *
	 * @param pageNumber the page number to retrieve, must be greater than 0
	 * @param pageSize the number of items per page, must be greater than 0
	 * @return a constructed Query object with the specified pagination and spacing conditions
	 */
	@Nonnull
	private static Query fabricateSpacingQuery(int pageNumber, int pageSize) {
		return query(
			collection(Entities.PRODUCT),
			require(
				page(
					pageNumber, pageSize,
					spacing(
						gap(2, "(($pageNumber - 1) % 2 == 0) && $pageNumber <= 6"),
						gap(1, "($pageNumber % 2 == 0) && $pageNumber <= 6")
					)
				),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
			)
		);
	}

}
