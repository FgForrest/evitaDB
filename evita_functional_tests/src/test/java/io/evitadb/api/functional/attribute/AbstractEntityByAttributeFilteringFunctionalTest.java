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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertArrayAreDifferent;
import static io.evitadb.utils.AssertionUtils.assertResultIs;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.summingInt;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies whether entities can be filtered by attributes.
 *
 * TOBEDONE JNO - create functional tests - one run with enabled SelectionFormula, one with disabled
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public abstract class AbstractEntityByAttributeFilteringFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProductsForAttributeTesting";
	private static final String REFERENCE_BRAND_PRODUCTS = "products";
	private static final String ATTRIBUTE_SIZE = "size";
	private static final String ATTRIBUTE_CREATED = "created";
	private static final String ATTRIBUTE_CURRENCY = "currency";
	private static final String ATTRIBUTE_UUID = "uuid";
	private static final String ATTRIBUTE_LOCALE = "localizedInfo";
	private static final String ATTRIBUTE_MANUFACTURED = "manufactured";
	private static final String ATTRIBUTE_COMBINED_PRIORITY = "combinedPriority";
	private static final String ATTRIBUTE_VISIBLE = "visible";
	private static final String ATTRIBUTE_BRAND_VISIBLE_FOR_B2C = "brandVisibleForB2C";
	private static final String ATTRIBUTE_STORE_VISIBLE_FOR_B2C = "storeVisibleForB2C";
	private static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	private static final String ATTRIBUTE_FOUNDED = "founded";
	private static final String ATTRIBUTE_CAPACITY = "capacity";
	private static final String ATTRIBUTE_RELATIVE_URL = "relativeUrl";
	private static final String ATTRIBUTE_NATIONAL_CODE = "nationalCode";

	private static final int SEED = 40;

	static void assertSortedAndPagedResultIs(List<SealedEntity> originalProductEntities, List<EntityReference> records, Predicate<SealedEntity> predicate, Comparator<SealedEntity> comparator, int skip, int limit) {
		assertSortedResultEquals(
			records.stream().map(EntityReference::getPrimaryKeyOrThrowException).collect(Collectors.toList()),
			originalProductEntities.stream()
				.filter(predicate)
				.sorted(comparator)
				.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
				.skip(Math.max(skip, 0))
				.limit(skip >= 0 ? limit : limit + skip)
				.toArray()
		);
	}

	static void assertSortedResultIs(List<SealedEntity> originalProductEntities, List<EntityReference> records, Predicate<SealedEntity> predicate, Comparator<SealedEntity> comparator) {
		assertSortedResultIs(originalProductEntities, records, predicate, new PredicateWithComparatorTuple(predicate, comparator));
	}

	static void assertSortedResultIs(List<SealedEntity> originalProductEntities, List<EntityReference> records, Predicate<SealedEntity> filteringPredicate, PredicateWithComparatorTuple... sortVector) {
		final List<Predicate<SealedEntity>> previousPredicateAcc = new ArrayList<>();
		final List<SealedEntity> expectedSortedRecords = Stream.concat(
				Arrays.stream(sortVector)
					.flatMap(it -> {
						final List<SealedEntity> subResult = originalProductEntities
							.stream()
							.filter(filteringPredicate)
							.filter(entity -> previousPredicateAcc.stream().noneMatch(predicate -> predicate.test(entity)))
							.filter(it.predicate())
							.sorted(it.comparator()).toList();
						previousPredicateAcc.add(it.predicate());
						return subResult.stream();
					}),
				// append entities that don't match any predicate
				originalProductEntities
					.stream()
					.filter(filteringPredicate)
					.filter(entity -> previousPredicateAcc.stream().noneMatch(predicate -> predicate.test(entity)))
			)
			.toList();

		assertSortedResultEquals(
			records.stream().map(EntityReference::getPrimaryKeyOrThrowException).collect(Collectors.toList()),
			expectedSortedRecords.stream().mapToInt(EntityContract::getPrimaryKeyOrThrowException).toArray()
		);
	}

	/**
	 * Verifies histogram integrity against source entities.
	 */
	private static void assertHistogramIntegrity(EvitaResponse<SealedEntity> result, List<SealedEntity> filteredProducts, String attributeName, BigDecimal from, BigDecimal to) {
		final AttributeHistogram histogramPacket = result.getExtraResult(AttributeHistogram.class);
		assertNotNull(histogramPacket);
		final HistogramContract histogram = histogramPacket.getHistogram(attributeName);
		assertTrue(histogram.getBuckets().length <= 20);

		assertEquals(
			filteredProducts.stream().filter(it -> it.getAttribute(attributeName) != null).count(),
			histogram.getOverallCount()
		);

		final List<BigDecimal> attributeValues = filteredProducts
			.stream()
			.map(it -> convertToBigDecimal(attributeName, it))
			.filter(Objects::nonNull)
			.toList();

		//noinspection SimplifiableAssertion
		assertTrue(
			attributeValues.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO).compareTo(histogram.getMin()) == 0,
			"Min value is not equal."
		);
		//noinspection SimplifiableAssertion
		assertTrue(
			attributeValues.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO).compareTo(histogram.getMax()) == 0,
			"Max value is not equal."
		);

		// verify bucket occurrences
		final Map<Integer, Integer> expectedOccurrences = filteredProducts
			.stream()
			.map(it -> convertToBigDecimal(attributeName, it))
			.filter(Objects::nonNull)
			.collect(
				Collectors.groupingBy(
					it -> findIndexInHistogram(it, histogram),
					summingInt(entity -> 1)
				)
			);

		final Bucket[] buckets = histogram.getBuckets();
		for (int i = 0; i < buckets.length; i++) {
			final Bucket bucket = histogram.getBuckets()[i];
			if (from == null && to == null) {
				assertTrue(bucket.requested());
			} else if (
				(from == null || from.compareTo(bucket.threshold()) <= 0) &&
					(to == null || to.compareTo(bucket.threshold()) >= 0)) {
				assertTrue(bucket.requested());
			} else {
				assertFalse(bucket.requested());
			}
			assertEquals(
				ofNullable(expectedOccurrences.get(i)).orElse(0), bucket.occurrences(),
				"Expected " + expectedOccurrences.get(i) + " occurrences in bucket " + i + ", but got " + bucket.occurrences() + "!"
			);
		}
	}

	/**
	 * Converts numeric value to BigDecimal.
	 */
	private static BigDecimal convertToBigDecimal(String attributeName, SealedEntity it) {
		final Object value = it.getAttribute(attributeName);
		if (value == null) {
			return null;
		}

		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		} else {
			return BigDecimal.valueOf((long) value);
		}
	}

	/**
	 * Finds appropriate index in the histogram according to histogram thresholds.
	 */
	private static int findIndexInHistogram(BigDecimal attributeValue, HistogramContract histogram) {
		final Bucket[] buckets = histogram.getBuckets();
		for (int i = buckets.length - 1; i >= 0; i--) {
			final Bucket bucket = buckets[i];
			final int valueCompared = attributeValue.compareTo(bucket.threshold());
			if (valueCompared >= 0) {
				return i;
			}
		}
		fail("Histogram span doesn't match current entity attribute value: " + attributeValue);
		return -1;
	}

	@DataSet(value = HUNDRED_PRODUCTS, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_RELATIVE_URL, String.class, whichIs -> whichIs.localized().uniqueGloballyWithinLocale().nullable())
			);

			final DataGenerator dataGenerator = new DataGenerator();
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};
			final List<EntityReference> storedBrands = dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(
						session,
						schemaBuilder -> schemaBuilder
							.withReflectedReferenceToEntity(
								REFERENCE_BRAND_PRODUCTS,
								Entities.PRODUCT,
								Entities.BRAND,
								whichIs -> whichIs
									.withCardinality(Cardinality.ZERO_OR_MORE)
									.withAttributesInherited()
							).updateAndFetchVia(session)
					),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.map(session::upsertEntity)
				.toList();

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
						schemaBuilder -> {
							return schemaBuilder
								.withGlobalAttribute(ATTRIBUTE_RELATIVE_URL)
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().indexDecimalPlaces(2))
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable().filterable())
								.withAttribute(ATTRIBUTE_SIZE, IntegerNumberRange[].class, whichIs -> whichIs.filterable().nullable())
								.withAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_MANUFACTURED, LocalDate.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_CURRENCY, Currency.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_UUID, UUID.class, whichIs -> whichIs.unique().sortable())
								.withAttribute(ATTRIBUTE_NATIONAL_CODE, String.class, whichIs -> whichIs.localized().uniqueWithinLocale())
								.withAttribute(ATTRIBUTE_LOCALE, Locale.class, whichIs -> whichIs.filterable().sortable())
								.withSortableAttributeCompound(
									ATTRIBUTE_COMBINED_PRIORITY,
									attributeElement(ATTRIBUTE_PRIORITY, DESC, OrderBehaviour.NULLS_LAST),
									attributeElement(ATTRIBUTE_CREATED, ASC, OrderBehaviour.NULLS_FIRST)
								)
								.withAttribute(ATTRIBUTE_VISIBLE, Boolean.class, whichIs -> whichIs.filterable())
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs
										.withAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C, Boolean.class, thatIs -> thatIs.filterable())
										.withAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class, thatIs -> thatIs.filterable().sortable())
										.withAttribute(ATTRIBUTE_FOUNDED, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
								)
								.withReferenceToEntity(
									Entities.STORE,
									Entities.STORE,
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs
										.withAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, Boolean.class, thatIs -> thatIs.filterable())
										.withAttribute(ATTRIBUTE_CAPACITY, Long.class, thatIs -> thatIs.filterable().nullable().sortable())
								).updateAndFetchVia(session);
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
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKeyOrThrowException(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList()),
				"originalBrandEntities",
				storedBrands.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKeyOrThrowException(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList())
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

	@DisplayName("Should return no entities when set is empty")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoEntitiesWhenSetIsEmpty(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet()
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(0, result.getTotalRecordCount());
				return null;
			}
		);
	}

	@DisplayName("Should return entities by matching locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByMatchingLocale(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityLocaleEquals(Locale.ENGLISH)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getLocales().contains(Locale.ENGLISH),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to global attribute (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalAttributeEqualToString(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> codeAttribute.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return no entity for empty price list constraint")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoEntityForEmptyPriceListConstraint(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							priceInPriceLists()
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(0, result.getTotalRecordCount());
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to global attribute (String) and price query")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByGlobalAttributeEqualToStringAndPriceConstraint(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity selectedEntity = originalProductEntities.stream()
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.skip(10)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("There is no entity with a indexed price!"));
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final String codeAttribute = selectedEntity.getAttribute(ATTRIBUTE_CODE);
				final PriceContract firstPrice = selectedEntity.getPrices()
					.stream()
					.filter(PriceContract::indexed)
					.findFirst()
					.orElse(null);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_CODE, codeAttribute),
								priceInCurrency(firstPrice.currency()),
								priceInPriceLists(firstPrice.priceList())
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
					originalProductEntities,
					sealedEntity -> Objects.equals(codeAttribute, sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to global attribute (String) and filtering by additional attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalAttributeEqualToStringAndAdditionalConstraints(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final SealedEntity selectedEntity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_CODE) != null && it.getAttribute(ATTRIBUTE_QUANTITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_CODE, selectedEntity.getAttribute(ATTRIBUTE_CODE)),
								ofNullable(selectedEntity.getAttribute(ATTRIBUTE_ALIAS))
									.map(it -> attributeIsNotNull(ATTRIBUTE_ALIAS))
									.orElse(attributeIsNull(ATTRIBUTE_ALIAS)),
								or(
									// this cannot be ever true
									ofNullable(selectedEntity.getAttribute(ATTRIBUTE_ALIAS))
										.map(it -> attributeIsNull(ATTRIBUTE_ALIAS))
										.orElse(attributeIsNotNull(ATTRIBUTE_ALIAS)),
									// but this will
									attributeGreaterThanEquals(
										ATTRIBUTE_QUANTITY,
										selectedEntity.getAttribute(ATTRIBUTE_QUANTITY)
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
					originalProductEntities,
					sealedEntity -> Objects.equals(selectedEntity.getPrimaryKeyOrThrowException(), sealedEntity.getPrimaryKeyOrThrowException()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities of explicit type by equals to global attribute (String) and filtering by additional attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesOfExplicitTypeByGlobalAttributeEqualToStringAndAdditionalConstraints(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final SealedEntity selectedEntity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_CODE) != null && it.getAttribute(ATTRIBUTE_QUANTITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_CODE, selectedEntity.getAttribute(ATTRIBUTE_CODE)),
								ofNullable(selectedEntity.getAttribute(ATTRIBUTE_ALIAS))
									.map(it -> attributeIsNotNull(ATTRIBUTE_ALIAS))
									.orElse(attributeIsNull(ATTRIBUTE_ALIAS)),
								or(
									// this cannot be ever true
									ofNullable(selectedEntity.getAttribute(ATTRIBUTE_ALIAS))
										.map(it -> attributeIsNull(ATTRIBUTE_ALIAS))
										.orElse(attributeIsNotNull(ATTRIBUTE_ALIAS)),
									// but this will
									attributeGreaterThanEquals(
										ATTRIBUTE_QUANTITY,
										selectedEntity.getAttribute(ATTRIBUTE_QUANTITY)
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
					originalProductEntities,
					sealedEntity -> Objects.equals(selectedEntity.getPrimaryKeyOrThrowException(), sealedEntity.getPrimaryKeyOrThrowException()),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return no entities for empty attribute in set constraint")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoEntitiesForEmptyAttributeInSetConstraint(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							attributeInSet(ATTRIBUTE_CODE)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertEquals(0, result.getTotalRecordCount());

				return null;
			}
		);
	}

	@DisplayName("Should return entities by value present in global attribute (String), ordered")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalAttributeEqualToStringsWithOrder(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 8);
		final String codeAttribute3 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 11);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							attributeInSet(ATTRIBUTE_CODE, codeAttribute1, codeAttribute2, codeAttribute3)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> codeAttribute1.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)) ||
						codeAttribute2.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)) ||
						codeAttribute3.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					Comparator.comparing((SealedEntity o) -> (String) o.getAttribute(ATTRIBUTE_CODE)),
					0, Integer.MAX_VALUE
				);

				return null;
			}
		);
	}

	@DisplayName("Should return entities by value present in Currency attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalAttributeEqualToCurrenciesWithOrder(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CURRENCY, CURRENCY_CZK, CURRENCY_EUR)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)) ||
						CURRENCY_EUR.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)),
					result.getRecordData()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return entities by global attribute (String) and filter by attribute on reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByGlobalAttributeEqualToStringAndSortByAttributeOnReference(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> selectedEntities = originalProductEntities
			.stream()
			.filter(it -> it.getReferences(Entities.BRAND).stream().anyMatch(x -> x.getAttribute(ATTRIBUTE_MARKET_SHARE) != null))
			.limit(10)
			.toList();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CODE, selectedEntities.stream().map(it -> (String) it.getAttribute(ATTRIBUTE_CODE)).toArray(String[]::new))
						),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_MARKET_SHARE, ASC)
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
					originalProductEntities,
					sealedEntity -> selectedEntities.stream().anyMatch(it -> Objects.equals(sealedEntity.getPrimaryKeyOrThrowException(), it.getPrimaryKeyOrThrowException())),
					result.getRecordData()
				);

				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> selectedEntities.stream().anyMatch(it -> Objects.equals(sealedEntity.getPrimaryKeyOrThrowException(), it.getPrimaryKeyOrThrowException())),
					new Comparator<SealedEntity>() {
						@Override
						public int compare(SealedEntity o1, SealedEntity o2) {
							final BigDecimal a1 = o1.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class)).findFirst().orElse(null);
							final BigDecimal a2 = o2.getReferences(Entities.BRAND).stream().map(it -> it.getAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class)).findFirst().orElse(null);
							if (a1 != null && a2 != null) {
								return a1.compareTo(a2);
							} else if (a1 != null) {
								return -1;
							} else {
								return 1;
							}
						}
					},
					0, Integer.MAX_VALUE
				);

				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to global attribute (String) and price query and sort by price")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByGlobalAttributeEqualToStringAndPriceConstraintAndSortByPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity selectedEntity = originalProductEntities
			.stream()
			.filter(it -> it.getPrices().stream().anyMatch(PriceContract::indexed))
			.findFirst()
			.orElseThrow();
		final PriceContract firstPrice = selectedEntity.getPrices()
			.stream()
			.filter(PriceContract::indexed)
			.findFirst()
			.orElseThrow();
		final List<SealedEntity> selectedEntities = originalProductEntities.stream()
			.filter(it -> it.getPriceForSale(firstPrice.currency(), OffsetDateTime.now(), firstPrice.priceList()).isPresent())
			.limit(20)
			.toList();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							and(
								attributeInSet(ATTRIBUTE_CODE, selectedEntities.stream().map(it -> (String) it.getAttribute(ATTRIBUTE_CODE)).toArray(String[]::new)),
								priceInCurrency(firstPrice.currency()),
								priceInPriceLists(firstPrice.priceList())
							)
						),
						orderBy(priceNatural(ASC)),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> selectedEntities.stream().anyMatch(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), sealedEntity.getAttribute(ATTRIBUTE_CODE))),
					Comparator.comparing(o -> o.getPriceForSale(firstPrice.currency(), OffsetDateTime.now(), firstPrice.priceList()).orElseThrow().priceWithTax()),
					0, Integer.MAX_VALUE
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToString(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> codeAttribute.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by currency")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByCurrencyAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CURRENCY, CURRENCY_EUR, CURRENCY_CZK)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CURRENCY, ASC)
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
					sealedEntity -> CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)) ||
						CURRENCY_EUR.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)),
					(o1, o2) -> {
						final Currency c1 = o1.getAttribute(ATTRIBUTE_CURRENCY);
						final Currency c2 = o2.getAttribute(ATTRIBUTE_CURRENCY);
						final int cmpResult = c1.getCurrencyCode().compareTo(c2.getCurrencyCode());
						return cmpResult == 0 ? o1.getPrimaryKey().compareTo(o2.getPrimaryKeyOrThrowException()) : cmpResult;
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by currency in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByCurrencyAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[] pks = originalProductEntities.stream()
			.filter(it -> CURRENCY_EUR.equals(it.getAttribute(ATTRIBUTE_CURRENCY)) || CURRENCY_CZK.equals(it.getAttribute(ATTRIBUTE_CURRENCY)))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.limit(10)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(pks),
							attributeInSet(ATTRIBUTE_CURRENCY, CURRENCY_EUR, CURRENCY_CZK)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CURRENCY, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				Set<Integer> pksAsSet = Arrays.stream(pks).boxed().collect(Collectors.toSet());
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> pksAsSet.contains(sealedEntity.getPrimaryKeyOrThrowException()) && (
						CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)) ||
							CURRENCY_EUR.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY))
					),
					(o1, o2) -> {
						final Currency c1 = o1.getAttribute(ATTRIBUTE_CURRENCY);
						final Currency c2 = o2.getAttribute(ATTRIBUTE_CURRENCY);
						final int cmpResult = c1.getCurrencyCode().compareTo(c2.getCurrencyCode());
						return cmpResult == 0 ? o1.getPrimaryKey().compareTo(o2.getPrimaryKeyOrThrowException()) : cmpResult;
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by currency (greater than)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByGreaterThanCurrencyAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_CURRENCY, CURRENCY_EUR)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> !CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by currency (greater by) in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByCurrencyGreaterThanAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[] pks = originalProductEntities.stream()
			.filter(it -> CURRENCY_EUR.equals(it.getAttribute(ATTRIBUTE_CURRENCY)) || CURRENCY_CZK.equals(it.getAttribute(ATTRIBUTE_CURRENCY)))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.limit(10)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(pks),
							attributeGreaterThanEquals(ATTRIBUTE_CURRENCY, CURRENCY_EUR)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CURRENCY, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				Set<Integer> pksAsSet = Arrays.stream(pks).boxed().collect(Collectors.toSet());
				assertResultIs(
					originalProductEntities,
					sealedEntity -> pksAsSet.contains(sealedEntity.getPrimaryKeyOrThrowException()) && (
						!CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY))
					),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by currency (greater than) in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByGreaterThanCurrencyAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[] pks = originalProductEntities.stream()
			.filter(it -> CURRENCY_EUR.equals(it.getAttribute(ATTRIBUTE_CURRENCY)) || CURRENCY_USD.equals(it.getAttribute(ATTRIBUTE_CURRENCY)))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.limit(10)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(pks),
							attributeGreaterThanEquals(ATTRIBUTE_CURRENCY, CURRENCY_EUR)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				Set<Integer> pksAsSet = Arrays.stream(pks).boxed().collect(Collectors.toSet());
				assertResultIs(
					originalProductEntities,
					sealedEntity -> pksAsSet.contains(sealedEntity.getPrimaryKeyOrThrowException()) &&
						!CURRENCY_CZK.equals(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByLocaleAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_LOCALE, Locale.ENGLISH, CZECH_LOCALE)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_LOCALE, ASC)
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
					sealedEntity -> CZECH_LOCALE.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE)) ||
						Locale.ENGLISH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE)),
					(o1, o2) -> {
						final Locale l1 = o1.getAttribute(ATTRIBUTE_LOCALE);
						final Locale l2 = o2.getAttribute(ATTRIBUTE_LOCALE);
						final int cmpResult = l1.toLanguageTag().compareTo(l2.toLanguageTag());
						return cmpResult == 0 ? o1.getPrimaryKey().compareTo(o2.getPrimaryKeyOrThrowException()) : cmpResult;
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by locale in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByLocaleAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[] pks = originalProductEntities.stream()
			.filter(it -> CZECH_LOCALE.equals(it.getAttribute(ATTRIBUTE_LOCALE)) || Locale.ENGLISH.equals(it.getAttribute(ATTRIBUTE_LOCALE)))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.limit(10)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(pks),
							attributeInSet(ATTRIBUTE_LOCALE, Locale.ENGLISH, CZECH_LOCALE)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_LOCALE, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				Set<Integer> pksAsSet = Arrays.stream(pks).boxed().collect(Collectors.toSet());
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> pksAsSet.contains(sealedEntity.getPrimaryKeyOrThrowException()) && (
						Locale.ENGLISH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE)) ||
							CZECH_LOCALE.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE))
					),
					(o1, o2) -> {
						final Locale l1 = o1.getAttribute(ATTRIBUTE_LOCALE);
						final Locale l2 = o2.getAttribute(ATTRIBUTE_LOCALE);
						final int cmpResult = l1.toLanguageTag().compareTo(l2.toLanguageTag());
						return cmpResult == 0 ? o1.getPrimaryKey().compareTo(o2.getPrimaryKeyOrThrowException()) : cmpResult;
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered (greater than) by locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByLocaleGreaterThanAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_LOCALE, Locale.ENGLISH)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> Locale.ENGLISH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE))
						|| Locale.FRENCH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered (greater than) by locale in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByLocaleGreaterThanAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[] pks = originalProductEntities.stream()
			.filter(it -> CZECH_LOCALE.equals(it.getAttribute(ATTRIBUTE_LOCALE)) || Locale.ENGLISH.equals(it.getAttribute(ATTRIBUTE_LOCALE)))
			.mapToInt(EntityContract::getPrimaryKeyOrThrowException)
			.limit(10)
			.toArray();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(pks),
							attributeGreaterThanEquals(ATTRIBUTE_LOCALE, Locale.ENGLISH)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				Set<Integer> pksAsSet = Arrays.stream(pks).boxed().collect(Collectors.toSet());
				assertResultIs(
					originalProductEntities,
					sealedEntity -> pksAsSet.contains(sealedEntity.getPrimaryKeyOrThrowException()) &&
						(Locale.ENGLISH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE))
							|| Locale.FRENCH.equals(sealedEntity.getAttribute(ATTRIBUTE_LOCALE))),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by UUID")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByUuidAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final UUID uuidAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 5);
		final UUID uuidAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 10);
		final UUID uuidAttribute3 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 15);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_UUID, uuidAttribute1, uuidAttribute2, uuidAttribute3)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_UUID, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final Set<UUID> uuidsAsSet = Set.of(
					uuidAttribute1,
					uuidAttribute2,
					uuidAttribute3
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> uuidsAsSet.contains(sealedEntity.getAttribute(ATTRIBUTE_UUID, UUID.class)),
					Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_UUID, UUID.class))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted and filtered by UUID in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterAndSortByUUIDAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final UUID uuidAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 5);
		final UUID uuidAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 10);
		final UUID uuidAttribute3 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 15);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_UUID, uuidAttribute1, uuidAttribute2, uuidAttribute3)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_UUID, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final Set<UUID> uuidsAsSet = Set.of(
					uuidAttribute1,
					uuidAttribute2,
					uuidAttribute3
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> uuidsAsSet.contains(sealedEntity.getAttribute(ATTRIBUTE_UUID, UUID.class)),
					Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_UUID, UUID.class))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by UUID greater than in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByGreaterThanByUUIDAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final UUID uuidAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_UUID, uuidAttribute)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_UUID, ASC)
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
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_UUID, UUID.class).compareTo(uuidAttribute) >= 0,
					Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_UUID, UUID.class))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities filtered by UUID greater than in prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterByGreaterThanByUUIDAttributeInPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final UUID uuidAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 5);
		final UUID uuidAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 10);
		final UUID uuidAttribute3 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID, 15);
		final UUID[] sortedUuids = Arrays.stream(new UUID[]{uuidAttribute1, uuidAttribute2, uuidAttribute3})
			.sorted()
			.toArray(UUID[]::new);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_UUID, uuidAttribute1, uuidAttribute2, uuidAttribute3),
							attributeGreaterThanEquals(ATTRIBUTE_UUID, sortedUuids[1])
						),
						orderBy(
							attributeNatural(ATTRIBUTE_UUID, ASC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					),
					EntityReference.class
				);

				final Set<UUID> uuidsAsSet = Set.of(
					Arrays.copyOfRange(sortedUuids, 1, sortedUuids.length)
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> uuidsAsSet.contains(sealedEntity.getAttribute(ATTRIBUTE_UUID, UUID.class)),
					Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_UUID, UUID.class))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entity by equals to unique attribute (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByUniqueAttributeWithoutSpecifyingCollection(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							attributeEquals(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> codeAttribute.equals(sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entity by equals to unique locale specific attribute (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByUniqueLocaleSpecificAttributeWithoutSpecifyingCollection(Evita evita, List<SealedEntity> originalProductEntities) {
		final AttributeValue codeAttribute = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_NATIONAL_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_NATIONAL_CODE, codeAttribute.value()),
							entityLocaleEquals(codeAttribute.key().locale())
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getAttributeValue(ATTRIBUTE_NATIONAL_CODE, codeAttribute.key().locale())
						.map(it -> !codeAttribute.differsFrom(it))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when filtering entity by equals to unique locale specific attribute without Locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenFilteringByUniqueLocalSpecificAttributeWithoutLocale(Evita evita, List<SealedEntity> originalProductEntities) {
		assertThrows(
			EntityLocaleMissingException.class,
			() -> {
				final AttributeValue nationalCode = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_NATIONAL_CODE);
				evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final EvitaResponse<EntityReference> result = session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									attributeEquals(ATTRIBUTE_NATIONAL_CODE, nationalCode.value())
								),
								require(
									page(1, Integer.MAX_VALUE),
									debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
								)
							),
							EntityReference.class
						);
						return null;
					}
				);
			}
		);
	}

	@DisplayName("Should return entity by equals to globally unique locale specific attribute (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByGloballyUniqueLocaleSpecificCodeWithoutSpecifyingCollection(Evita evita, List<SealedEntity> originalProductEntities) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						filterBy(
							attributeEquals(ATTRIBUTE_RELATIVE_URL, relativeUrl.value()),
							entityLocaleEquals(relativeUrl.key().locale())
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getAttributeValue(ATTRIBUTE_RELATIVE_URL, relativeUrl.key().locale())
						.map(it -> !relativeUrl.differsFrom(it))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when filtering entity by equals to globally unique locale specific attribute without Locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenFilteringByGloballyUniqueLocalSpecificAttributeWithoutLocale(Evita evita, List<SealedEntity> originalProductEntities) {
		assertThrows(
			EntityLocaleMissingException.class,
			() -> {
				final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);
				evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final EvitaResponse<EntityReference> result = session.query(
							query(
								filterBy(
									attributeEquals(ATTRIBUTE_RELATIVE_URL, relativeUrl.value())
								),
								require(
									page(1, Integer.MAX_VALUE),
									debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
								)
							),
							EntityReference.class
						);
						return null;
					}
				);
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (Long)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToNumber(Evita evita, List<SealedEntity> originalProductEntities) {
		final Long priorityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_PRIORITY, priorityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> priorityAttribute.equals(sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (BigDecimal)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToBigDecimal(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal quantityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_QUANTITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_QUANTITY, quantityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((BigDecimal) sealedEntity.getAttribute(ATTRIBUTE_QUANTITY))
						.map(it -> quantityAttribute.compareTo(it) == 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (BigDecimal)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToBoolean(Evita evita, List<SealedEntity> originalProductEntities) {
		final Boolean aliasAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_ALIAS);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_ALIAS, aliasAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> Objects.equals(aliasAttribute, sealedEntity.getAttribute(ATTRIBUTE_ALIAS)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (OffsetDateTime)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToOffsetDateTime(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime createdAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_CREATED, createdAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(createdAttribute::isEqual)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);

		// transform the time to different offset but exact the same moment
		final OffsetDateTime createdAttributeInDifferentTimeZone = OffsetDateTime.ofInstant(createdAttribute.toInstant(), ZoneOffset.ofHours(3));
		assertTrue(createdAttribute.isEqual(createdAttributeInDifferentTimeZone));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_CREATED, createdAttributeInDifferentTimeZone)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(it -> it.isEqual(createdAttribute))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (LocalDate)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToLocalDate(Evita evita, List<SealedEntity> originalProductEntities) {
		final LocalDate manufacturedAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_MANUFACTURED);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_MANUFACTURED, manufacturedAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable(sealedEntity.getAttribute(ATTRIBUTE_MANUFACTURED))
							.map(manufacturedAttribute::equals)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (NumberRange)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToNumberRange(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_SIZE, sizeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> Arrays.asList(it).contains(sizeAttribute))
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (DateTimeRange)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToDateTimeRange(Evita evita, List<SealedEntity> originalProductEntities) {
		final DateTimeRange validityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_VALIDITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_VALIDITY, validityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable(sealedEntity.getAttribute(ATTRIBUTE_VALIDITY))
							.map(validityAttribute::equals)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (Currency)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		final Currency currencyAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CURRENCY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_CURRENCY, currencyAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CURRENCY))
							.map(currencyAttribute::equals)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (UUID)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToUuid(Evita evita, List<SealedEntity> originalProductEntities) {
		final UUID uuidAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_UUID);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_UUID, uuidAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable(sealedEntity.getAttribute(ATTRIBUTE_UUID))
							.map(uuidAttribute::equals)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute (Locale)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualToLocale(Evita evita, List<SealedEntity> originalProductEntities) {
		final Locale localeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_LOCALE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_LOCALE, localeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable(sealedEntity.getAttribute(ATTRIBUTE_LOCALE))
							.map(localeAttribute::equals)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by localized attribute equals to")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByLocalizedAttributeEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final String randomName = originalProductEntities.stream()
			.filter(it -> rnd.nextInt(100) > 85)
			.map(it -> (String) it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
			.filter(Objects::nonNull)
			.findFirst()
			.get();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_NAME, randomName),
								entityLocaleEquals(CZECH_LOCALE)
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
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
						.map(randomName::equals)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by global attribute equals to with language")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalAttributeEqualsToWithLanguage(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// code attribute is not localized attribute
				final SealedEntity firstEntityWithSomeCzechAttribute = originalProductEntities
					.stream()
					.filter(e -> e.getLocales().contains(CZECH_LOCALE) && e.getAttribute(ATTRIBUTE_EAN) != null)
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("There is no entity with attribute localized to Czech!"));
				// now retrieve non-localized attribute for this entity
				final String firstEntityEan = firstEntityWithSomeCzechAttribute.getAttribute(ATTRIBUTE_EAN);
				// and filter the entity by both language and non-localized attribute - it should match
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_EAN, firstEntityEan),
								entityLocaleEquals(CZECH_LOCALE)
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
					originalProductEntities,
					sealedEntity -> sealedEntity.getLocales().contains(CZECH_LOCALE) &&
						Objects.equals(sealedEntity.getAttribute(ATTRIBUTE_EAN), firstEntityEan),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute joined by or")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByAttributeEqualToJoinedByOr(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final Set<String> randomCodes = originalProductEntities.stream()
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(2)
			.map(it -> (String) it.getAttribute(ATTRIBUTE_CODE))
			.collect(Collectors.toSet());
		final Iterator<String> it = randomCodes.iterator();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							or(
								attributeEquals(ATTRIBUTE_CODE, it.next()),
								attributeEquals(ATTRIBUTE_CODE, it.next())
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
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE, String.class))
						.map(randomCodes::contains)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute joined by and")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityByAttributeEqualToJoinedByAnd(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final SealedEntity randomEntity = originalProductEntities.stream()
			.filter(it -> rnd.nextInt(100) > 85)
			.filter(it -> it.getAttribute(ATTRIBUTE_PRIORITY) != null && it.getAttribute(ATTRIBUTE_ALIAS) != null)
			.findFirst()
			.get();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_PRIORITY, randomEntity.getAttribute(ATTRIBUTE_PRIORITY)),
								attributeEquals(ATTRIBUTE_ALIAS, randomEntity.getAttribute(ATTRIBUTE_ALIAS))
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
					originalProductEntities,
					sealedEntity -> Objects.equals(randomEntity.getAttribute(ATTRIBUTE_PRIORITY), sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)) &&
						Objects.equals(randomEntity.getAttribute(ATTRIBUTE_ALIAS), sealedEntity.getAttribute(ATTRIBUTE_ALIAS)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to attribute wrapped in negated container")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeNotEqualTo(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(
								attributeEquals(ATTRIBUTE_CODE, "Practical-Silk-Hat-11")
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
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> !"Practical-Silk-Hat-11".equals(it))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by complex and/not/or container composition")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByComplexAndOrNotComposition(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final List<SealedEntity> withTrueAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.TRUE, it.getAttribute(ATTRIBUTE_ALIAS)) && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(2)
			.toList();
		final Set<Long> truePriorities = withTrueAlias
			.stream()
			.map(it -> (Long) it.getAttribute(ATTRIBUTE_PRIORITY))
			.collect(Collectors.toSet());
		final List<SealedEntity> withFalseAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.FALSE, it.getAttribute(ATTRIBUTE_ALIAS)) && it.getAttribute(ATTRIBUTE_CODE) != null && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(5)
			.toList();
		final Set<Long> falsePriorities = withFalseAlias
			.stream()
			.map(it -> (Long) it.getAttribute(ATTRIBUTE_PRIORITY))
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								or(
									and(
										attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS)),
										attributeEquals(ATTRIBUTE_PRIORITY, withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY))
									),
									and(
										attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS)),
										attributeEquals(ATTRIBUTE_PRIORITY, withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY))
									),
									and(
										attributeEquals(ATTRIBUTE_ALIAS, false),
										attributeInSet(
											ATTRIBUTE_PRIORITY,
											withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
											withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
											withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
											(Long) withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY)
										)
									)
								),
								not(
									attributeEquals(ATTRIBUTE_CODE, withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE))
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
					originalProductEntities,
					sealedEntity -> {
						final boolean isAlias = ofNullable((Boolean) sealedEntity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false);
						final long priority = ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY)).orElse(Long.MIN_VALUE);
						final String code = sealedEntity.getAttribute(ATTRIBUTE_CODE);
						return ((isAlias && truePriorities.contains(priority)) || (!isAlias && falsePriorities.contains(priority)))
							&& !(Objects.equals(code, withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE)));
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by attribute in set of values")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeIn(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final Set<String> randomCodes = originalProductEntities.stream()
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(5)
			.map(it -> (String) it.getAttribute(ATTRIBUTE_CODE))
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CODE, randomCodes.toArray(String[]::new))
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final String code = sealedEntity.getAttribute(ATTRIBUTE_CODE);
						return randomCodes.contains(code);
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals true attribute (Boolean)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualsTrue(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEqualsTrue(ATTRIBUTE_ALIAS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Boolean) sealedEntity.getAttribute(ATTRIBUTE_ALIAS)).orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals false attribute (Boolean)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEqualsFalse(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEqualsFalse(ATTRIBUTE_ALIAS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Boolean) sealedEntity.getAttribute(ATTRIBUTE_ALIAS))
						.map(it -> !it)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute between (Number)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeBetween(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		Long one;
		Long two;
		do {
			one = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY, rnd.nextInt(originalProductEntities.size()));
			two = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY, rnd.nextInt(originalProductEntities.size()));
		} while (Objects.equals(one, two));
		final Long from = one.compareTo(two) < 0 ? one : two;
		final Long to = one.compareTo(two) < 0 ? two : one;

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeBetween(ATTRIBUTE_PRIORITY, from, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final Long priority = sealedEntity.getAttribute(ATTRIBUTE_PRIORITY);
						return priority != null && priority >= from && priority <= to;
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute between (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByStringAttributeBetween(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		String one;
		String two;
		do {
			one = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, rnd.nextInt(originalProductEntities.size()));
			two = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, rnd.nextInt(originalProductEntities.size()));
		} while (Objects.equals(one, two));
		final String from = one.compareTo(two) < 0 ? one : two;
		final String to = one.compareTo(two) < 0 ? two : one;

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeBetween(ATTRIBUTE_CODE, from, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final String code = sealedEntity.getAttribute(ATTRIBUTE_CODE);
						return code != null && (code.compareTo(from) >= 0 && code.compareTo(to) <= 0);
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute between (OffsetDateTime)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeBetweenOffsetDateTime(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		OffsetDateTime one;
		OffsetDateTime two;
		do {
			one = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED, rnd.nextInt(originalProductEntities.size()));
			two = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED, rnd.nextInt(originalProductEntities.size()));
		} while (Objects.equals(one, two));
		final OffsetDateTime from = one.isBefore(two) ? one : two;
		final OffsetDateTime to = one.isBefore(two) ? two : one;

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeBetween(ATTRIBUTE_CREATED, from, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final OffsetDateTime created = sealedEntity.getAttribute(ATTRIBUTE_CREATED);
						return created != null && (created.isAfter(from) || created.equals(from)) && (created.isBefore(to) || created.equals(to));
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute between (DateTimeRange) - overlap")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeBetweenDateTimeRange(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		OffsetDateTime one;
		OffsetDateTime two;
		do {
			one = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED, rnd.nextInt(originalProductEntities.size()));
			two = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED, rnd.nextInt(originalProductEntities.size()));
		} while (Objects.equals(one, two));
		final OffsetDateTime from = one.isBefore(two) ? one : two;
		final OffsetDateTime to = one.isBefore(two) ? two : one;

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final DateTimeRange lookedUpRange = DateTimeRange.between(from, to);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeBetween(ATTRIBUTE_VALIDITY, from, to)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final DateTimeRange validity = sealedEntity.getAttribute(ATTRIBUTE_VALIDITY);
						return validity != null && validity.overlaps(lookedUpRange);
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute between (NumberRange) - overlap")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeBetweenNumberRange(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final IntegerNumberRange lookedUpRange = IntegerNumberRange.between(4, 6);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeBetween(ATTRIBUTE_SIZE, 4, 6)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> {
						final IntegerNumberRange[] validity = sealedEntity.getAttributeArray(ATTRIBUTE_SIZE);
						return validity != null && Arrays.stream(validity).anyMatch(it -> it.overlaps(lookedUpRange));
					},
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute contains")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeContains(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeContains(ATTRIBUTE_CODE, "Hat")
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.contains("Hat"))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute starts with")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeStartsWith(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeStartsWith(ATTRIBUTE_CODE, "Practical")
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.startsWith("Practical"))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute ends with")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeEndsWith(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEndsWith(ATTRIBUTE_CODE, "8")
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.endsWith("8"))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute lessThan")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLessThan(Evita evita, List<SealedEntity> originalProductEntities) {
		final Long priorityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_PRIORITY, priorityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it < priorityAttribute)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by lesser or equals than attribute (NumberRange)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLesserThanEqualsNumberRange(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThanEquals(ATTRIBUTE_SIZE, sizeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> Arrays.stream(it).anyMatch(attr -> attr.compareTo(sizeAttribute) <= 0))
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by offset date time attribute lesserThanOrEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByOffsetDateTimeAttributeLesserThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime createdAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThanEquals(ATTRIBUTE_CREATED, createdAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(it -> it.isEqual(createdAttribute) || it.isBefore(createdAttribute))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);

		// transform the time to different offset but exact the same moment
		final OffsetDateTime createdAttributeInDifferentTimeZone = OffsetDateTime.ofInstant(createdAttribute.toInstant(), ZoneOffset.ofHours(3));
		assertTrue(createdAttribute.isEqual(createdAttributeInDifferentTimeZone));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThanEquals(ATTRIBUTE_CREATED, createdAttributeInDifferentTimeZone)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(it -> it.isEqual(createdAttribute) || it.isBefore(createdAttribute))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by lesser than attribute (NumberRange) with plain value")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLesserThanNumberRangeWithPlainValue(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_SIZE, sizeAttribute.getPreciseTo())
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final IntegerNumberRange implicitRange = IntegerNumberRange.between(
					sizeAttribute.getPreciseTo(),
					sizeAttribute.getPreciseTo()
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> Arrays.stream(it)
								.anyMatch(attr -> attr.compareTo(implicitRange) < 0)
							)
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute lessThan")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByStringAttributeLessThan(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.compareTo(codeAttribute) < 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by attribute lessThan (OffsetDateTime)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLessThanOffsetDateTime(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final OffsetDateTime theMoment = OffsetDateTime.of(2003, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_CREATED, theMoment)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((OffsetDateTime) sealedEntity.getAttribute(ATTRIBUTE_CREATED))
						.map(it -> it.isBefore(theMoment))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by attribute lessThan (LocalDate)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLessThanLocalDate(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final LocalDate theMoment = LocalDate.of(2003, 1, 1);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_MANUFACTURED, theMoment)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((LocalDate) sealedEntity.getAttribute(ATTRIBUTE_MANUFACTURED))
						.map(it -> it.isBefore(theMoment))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute lessThanEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeLessThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final Long priorityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThanEquals(ATTRIBUTE_PRIORITY, priorityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it <= priorityAttribute)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute lessThanEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByStringAttributeLessThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThanEquals(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.compareTo(codeAttribute) <= 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute greaterThan")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeGreaterThan(Evita evita, List<SealedEntity> originalProductEntities) {
		final Long priorityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThan(ATTRIBUTE_PRIORITY, priorityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it > priorityAttribute)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by greater than or equals attribute (NumberRange)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeGreaterThanEqualsNumberRange(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_SIZE, sizeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> Arrays.stream(it).anyMatch(attr -> attr.compareTo(sizeAttribute) >= 0))
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by greater than attribute (NumberRange) by plain value")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeGreaterThanNumberRangeWithPlainValue(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThan(ATTRIBUTE_SIZE, sizeAttribute.getPreciseFrom())
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final IntegerNumberRange implicitRange = IntegerNumberRange.between(sizeAttribute.getPreciseFrom(), sizeAttribute.getPreciseFrom());
				assertResultIs(
					originalProductEntities,
					sealedEntity ->
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> Arrays.stream(it).anyMatch(attr -> attr.compareTo(implicitRange) > 0))
							.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute greaterThan")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByStringAttributeGreaterThan(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThan(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.compareTo(codeAttribute) > 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number attribute greaterThanEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeGreaterThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final Long priorityAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_PRIORITY);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_PRIORITY, priorityAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it >= priorityAttribute)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by offset date time attribute greaterThanOrEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByOffsetDateTimeAttributeGreaterThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime createdAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CREATED);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_CREATED, createdAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(it -> it.isEqual(createdAttribute) || it.isAfter(createdAttribute))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);

		// transform the time to different offset but exact the same moment
		final OffsetDateTime createdAttributeInDifferentTimeZone = OffsetDateTime.ofInstant(createdAttribute.toInstant(), ZoneOffset.ofHours(3));
		assertTrue(createdAttribute.isEqual(createdAttributeInDifferentTimeZone));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_CREATED, createdAttributeInDifferentTimeZone)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable(sealedEntity.getAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class))
						.map(it -> it.isEqual(createdAttribute) || it.isAfter(createdAttribute))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by string attribute greaterThanEquals")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByStringAttributeGreaterThanOrEqualsTo(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThanEquals(ATTRIBUTE_CODE, codeAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((String) sealedEntity.getAttribute(ATTRIBUTE_CODE))
						.map(it -> it.compareTo(codeAttribute) >= 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by date time range")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeDateTimeInRange(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final OffsetDateTime theMoment = OffsetDateTime.of(LocalDateTime.of(2015, 1, 1, 0, 0), ZoneOffset.UTC);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInRange(ATTRIBUTE_VALIDITY, theMoment)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((DateTimeRange) sealedEntity.getAttribute(ATTRIBUTE_VALIDITY))
						.map(it -> it.isValidFor(theMoment))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities greater than date time range")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeDateTimeRangeGreaterThanMoment(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<OffsetDateTime> allValidities = originalProductEntities.stream()
					.map(it -> it.getAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class))
					.filter(Objects::nonNull)
					.map(DateTimeRange::getPreciseFrom)
					.distinct()
					.sorted()
					.toList();
				final OffsetDateTime theMoment = allValidities.get(allValidities.size() / 2);
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeGreaterThan(ATTRIBUTE_VALIDITY, theMoment)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((DateTimeRange) sealedEntity.getAttribute(ATTRIBUTE_VALIDITY))
						.map(it -> it.compareTo(DateTimeRange.between(theMoment, theMoment)) > 0)
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by date time range (now)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeDateTimeInRangeNow(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final OffsetDateTime theMoment = OffsetDateTime.now();
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInRangeNow(ATTRIBUTE_VALIDITY)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((DateTimeRange) sealedEntity.getAttribute(ATTRIBUTE_VALIDITY))
						.map(it -> it.isValidFor(theMoment))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by number range")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeNumberInRange(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = getByAttributeSize(session, 43);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
						.map(it -> Arrays.stream(it).anyMatch(x -> x.isWithin(43)))
						.orElse(false),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities that has by number ranges")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeNumberInRangeUsingRanges(Evita evita, List<SealedEntity> originalProductEntities) {
		final AtomicInteger updatedProductId = new AtomicInteger();
		final AtomicReference<IntegerNumberRange[]> formerAttribute = new AtomicReference<>();
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity alteredProduct = originalProductEntities.stream()
						.filter(it -> it.getAttributeArray(ATTRIBUTE_SIZE) != null)
						.findFirst()
						.orElseThrow();
					formerAttribute.set(alteredProduct.getAttributeArray(ATTRIBUTE_SIZE));
					updatedProductId.set(alteredProduct.getPrimaryKeyOrThrowException());
					session.upsertEntity(
						alteredProduct.openForWrite().setAttribute(
							ATTRIBUTE_SIZE,
							new IntegerNumberRange[]{
								IntegerNumberRange.between(879, 926),
								IntegerNumberRange.between(1250, 1780)
							}
						)
					);
				}
			);
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReference> result = getByAttributeSize(session, 900);
					assertEquals(1, result.getTotalRecordCount());
					assertEquals(updatedProductId.get(), result.getRecordData().get(0).getPrimaryKeyOrThrowException());

					final EvitaResponse<EntityReference> anotherResult = getByAttributeSize(session, 1400);
					assertEquals(1, anotherResult.getTotalRecordCount());
					assertEquals(updatedProductId.get(), anotherResult.getRecordData().get(0).getPrimaryKeyOrThrowException());
					return null;
				}
			);
		} catch (Exception ex) {
			fail(ex);
		} finally {
			// revert changes
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity alteredProduct = originalProductEntities.get(0);
					updatedProductId.set(alteredProduct.getPrimaryKeyOrThrowException());
					session.upsertEntity(
						alteredProduct.openForWrite().setAttribute(
							ATTRIBUTE_SIZE,
							formerAttribute.get()
						)
					);
				}
			);
		}
	}

	@DisplayName("Should return entities by having attribute null (not defined)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeIsNull(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNull(ATTRIBUTE_QUANTITY)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_QUANTITY) == null,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute not null")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeIsNotNull(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_PRIORITY)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_PRIORITY) != null,
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities having a reference of particular name")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByHavingAReferenceOfParticularName(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND
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
					originalProductEntities,
					sealedEntity -> !sealedEntity.getReferences(Entities.BRAND).isEmpty(),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities having a reflected reference of particular name")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByHavingAReflectedReferenceOfParticularName(Evita evita, List<SealedEntity> originalBrandEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.BRAND),
						filterBy(
							referenceHaving(REFERENCE_BRAND_PRODUCTS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertResultIs(
					originalBrandEntities,
					sealedEntity -> !sealedEntity.getReferences(REFERENCE_BRAND_PRODUCTS).isEmpty(),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should fail to process query targeting non existing attribute on reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFailToProcessQueryTargetingNonExistingAttributeOnReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					AttributeNotFoundException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.BRAND,
									attributeEquals(ATTRIBUTE_CODE, "apple")
								)
							)
						),
						EntityReference.class
					)
				);
			}
		);
	}

	@DisplayName("Should fail to order by localized attribute when locale is not known")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFailToOrderByLocalizedAttributeWhenLocaleIsNotKnown(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								attributeNatural(ATTRIBUTE_NAME)
							)
						),
						EntityReference.class
					)
				);
			}
		);
	}

	@DisplayName("Should return entities by referenced entity of particular id")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByReferencedEntityId(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								entityPrimaryKeyInSet(1)
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
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.BRAND)
						.stream()
						.anyMatch(brand -> brand.getReferencedPrimaryKey() == 1),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by reflected reference entity of particular id")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByReflectedReferencedEntityId(Evita evita, List<SealedEntity> originalBrandEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity brand = originalBrandEntities
					.stream()
					.filter(it -> it.getPrimaryKeyOrThrowException() == 1)
					.findFirst()
					.orElseThrow();
				final int referencedProduct = brand.getReferences(REFERENCE_BRAND_PRODUCTS)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.findFirst()
					.orElseThrow();

				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.BRAND),
						filterBy(
							referenceHaving(
								REFERENCE_BRAND_PRODUCTS,
								entityPrimaryKeyInSet(referencedProduct)
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
					originalBrandEntities,
					sealedEntity -> sealedEntity
						.getReferences(REFERENCE_BRAND_PRODUCTS)
						.stream()
						.anyMatch(product -> product.getReferencedPrimaryKey() == referencedProduct),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by referenced entity using prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByReferencedUsingPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity lookedUpProduct = originalProductEntities
			.stream()
			.filter(
				it -> it.getReferences(Entities.BRAND)
					.stream()
					.anyMatch(ref -> ref.getAttribute(ATTRIBUTE_FOUNDED) != null)
			)
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
								entityPrimaryKeyInSet(lookedUpProduct.getPrimaryKeyOrThrowException()),
								referenceHaving(
									Entities.BRAND,
									attributeIsNotNull(ATTRIBUTE_FOUNDED)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetchAll(),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());

				assertEquals(
					lookedUpProduct,
					result.getRecordData().get(0)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by reflected reference entity using prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByReflectedReferenceUsingPrefetch(Evita evita, List<SealedEntity> originalBrandEntities) {
		final SealedEntity lookedUpProduct = originalBrandEntities
			.stream()
			.filter(
				it -> it.getReferences(REFERENCE_BRAND_PRODUCTS)
					.stream()
					.anyMatch(ref -> ref.getAttribute(ATTRIBUTE_FOUNDED) != null)
			)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.BRAND),
						filterBy(
							and(
								entityPrimaryKeyInSet(lookedUpProduct.getPrimaryKeyOrThrowException()),
								referenceHaving(
									REFERENCE_BRAND_PRODUCTS,
									attributeIsNotNull(ATTRIBUTE_FOUNDED)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetchAll(),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, result.getRecordData().size());

				assertEquals(
					lookedUpProduct,
					result.getRecordData().get(0)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute set on referenced entity")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeSetOnReferencedEntity(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								and(
									attributeEqualsTrue(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C),
									attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, new BigDecimal("150.45"))
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
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.BRAND)
						.stream()
						.anyMatch(brand -> {
							final boolean marketMatch = ofNullable(brand.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C))
								.map(Boolean.TRUE::equals)
								.orElse(false);
							final boolean shareMatch = ofNullable((BigDecimal) brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
								.map(it -> new BigDecimal("150.45").compareTo(it) < 0)
								.orElse(false);
							return marketMatch && shareMatch;
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute set on reflected reference entity")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeSetOnReflectedReferenceEntity(Evita evita, List<SealedEntity> originalBrandEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.BRAND),
						filterBy(
							referenceHaving(
								REFERENCE_BRAND_PRODUCTS,
								and(
									attributeEqualsTrue(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C),
									attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, new BigDecimal("150.45"))
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
					originalBrandEntities,
					sealedEntity -> sealedEntity
						.getReferences(REFERENCE_BRAND_PRODUCTS)
						.stream()
						.anyMatch(brand -> {
							final boolean marketMatch = ofNullable(brand.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C))
								.map(Boolean.TRUE::equals)
								.orElse(false);
							final boolean shareMatch = ofNullable((BigDecimal) brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
								.map(it -> new BigDecimal("150.45").compareTo(it) < 0)
								.orElse(false);
							return marketMatch && shareMatch;
						}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute set on referenced entity and also global attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeSetOnReferencedEntityAndAlsoGlobalAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeIsNotNull(ATTRIBUTE_PRIORITY),
								referenceHaving(
									Entities.BRAND,
									and(
										attributeEqualsTrue(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C),
										attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, new BigDecimal("150.45"))
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
					originalProductEntities,
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_PRIORITY) != null &&
						sealedEntity
							.getReferences(Entities.BRAND)
							.stream()
							.anyMatch(brand -> {
								final boolean marketMatch = ofNullable(brand.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C))
									.map(Boolean.TRUE::equals)
									.orElse(false);
								final boolean shareMatch = ofNullable((BigDecimal) brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
									.map(it -> new BigDecimal("150.45").compareTo(it) < 0)
									.orElse(false);
								return marketMatch && shareMatch;
							}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by equals to global localized attribute and return implicit and additional locale (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByGlobalLocalizedAttributeEqualToStringAndReturnImplicitAndAdditionalLocale(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final SealedEntity selectedEntity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null && it.getLocales().contains(Locale.ENGLISH) && it.getLocales().contains(CZECH_LOCALE))
			.filter(it -> rnd.nextInt(100) > 85)
			.findFirst()
			.get();
		final String urlAttribute = selectedEntity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						filterBy(
							attributeEquals(ATTRIBUTE_URL, urlAttribute)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								attributeContent(ATTRIBUTE_URL),
								dataInLocales(CZECH_LOCALE)
							)
						)
					),
					SealedEntity.class
				);
				assertEquals(1, result.getRecordData().size());
				final SealedEntity sealedEntity = result.getRecordData().get(0);
				assertEquals(2, sealedEntity.getLocales().size());
				assertTrue(sealedEntity.getLocales().contains(Locale.ENGLISH));
				assertTrue(sealedEntity.getLocales().contains(CZECH_LOCALE));
				return null;
			}
		);
	}

	@DisplayName("Should return entities from different collections by equals to global localized attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesFromDifferentCollectionsByEqualsToGlobalLocalizedAttribute(Evita evita) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityLocaleEquals(Locale.ENGLISH)),
						require(
							page(1, 1),
							entityFetch(
								attributeContent(ATTRIBUTE_URL),
								dataInLocales(Locale.ENGLISH)
							)
						)
					)
				).get(0);
			}
		);
		final String productUrl = product.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.CATEGORY),
						filterBy(entityLocaleEquals(Locale.ENGLISH)),
						require(
							page(1, 1),
							entityFetch(
								attributeContent(ATTRIBUTE_URL)
							)
						)
					)
				).get(0);
			}
		);
		final String categoryUrl = category.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						filterBy(
							attributeInSet(ATTRIBUTE_URL, productUrl, categoryUrl),
							entityLocaleEquals(Locale.ENGLISH)
						),
						require(
							page(1, Integer.MAX_VALUE),
							//debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								attributeContent(ATTRIBUTE_URL)
							)
						)
					),
					SealedEntity.class
				);
				assertEquals(2, result.getRecordData().size());
				assertEquals(productUrl, result.getRecordData().get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH));
				assertEquals(categoryUrl, result.getRecordData().get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH));
				return null;
			}
		);
	}

	@DisplayName("Should not return entities by equals to global localized attribute when locale doesn't match (String)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldNotReturnEntitiesByGlobalLocalizedAttributeEqualToStringWhenLocaleDoesNotMatch(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final SealedEntity selectedEntity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null && it.getLocales().contains(Locale.ENGLISH) && it.getLocales().contains(CZECH_LOCALE))
			.filter(it -> rnd.nextInt(100) > 85)
			.findFirst()
			.get();
		final String urlAttribute = selectedEntity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						filterBy(
							attributeEquals(ATTRIBUTE_URL, urlAttribute),
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								attributeContent(ATTRIBUTE_URL),
								dataInLocales(CZECH_LOCALE)
							)
						)
					),
					SealedEntity.class
				);
				assertEquals(0, result.getRecordData().size());
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute set on two referenced entities (AND)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeSetOnTwoReferencedEntitiesByAndRelation(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								referenceHaving(
									Entities.BRAND,
									and(
										attributeEqualsTrue(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C),
										attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, new BigDecimal("150.45"))
									)
								),
								referenceHaving(
									Entities.STORE,
									attributeEqualsTrue(ATTRIBUTE_STORE_VISIBLE_FOR_B2C)
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
					originalProductEntities,
					sealedEntity -> sealedEntity
						.getReferences(Entities.BRAND)
						.stream()
						.anyMatch(brand -> {
							final boolean marketMatch = ofNullable(brand.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C))
								.map(Boolean.TRUE::equals)
								.orElse(false);
							final boolean shareMatch = ofNullable((BigDecimal) brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
								.map(it -> new BigDecimal("150.45").compareTo(it) < 0)
								.orElse(false);
							return marketMatch && shareMatch;
						}) &&
						sealedEntity
							.getReferences(Entities.STORE)
							.stream()
							.anyMatch(store -> ofNullable(store.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
								.map(Boolean.TRUE::equals)
								.orElse(false)),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by having attribute set on two referenced entities (OR)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByAttributeSetOnTwoReferencedEntitiesByOrRelation(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							or(
								referenceHaving(
									Entities.BRAND,
									and(
										attributeEqualsTrue(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C),
										attributeGreaterThan(ATTRIBUTE_MARKET_SHARE, new BigDecimal("600"))
									)
								),
								referenceHaving(
									Entities.STORE,
									and(
										attributeEqualsTrue(ATTRIBUTE_STORE_VISIBLE_FOR_B2C),
										attributeLessThan(ATTRIBUTE_CAPACITY, 25000L)
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
					originalProductEntities,
					sealedEntity ->
						sealedEntity.getReferences(Entities.BRAND)
							.stream()
							.anyMatch(brand -> {
								final boolean marketMatch = ofNullable(brand.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C))
									.map(Boolean.TRUE::equals)
									.orElse(false);
								final boolean shareMatch = ofNullable((BigDecimal) brand.getAttribute(ATTRIBUTE_MARKET_SHARE))
									.map(it -> new BigDecimal("600").compareTo(it) < 0)
									.orElse(false);
								return marketMatch && shareMatch;
							}) ||
							sealedEntity
								.getReferences(Entities.STORE)
								.stream()
								.anyMatch(store -> {
									final boolean marketMatch = ofNullable(store.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
										.map(Boolean.TRUE::equals)
										.orElse(false);
									final boolean capacityMath = ofNullable((Long) store.getAttribute(ATTRIBUTE_CAPACITY))
										.map(it -> 25000L > it)
										.orElse(false);
									return marketMatch && capacityMath;
								}),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities randomly")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesRandomly(Evita evita, List<SealedEntity> originalProductEntities) {
		final int[][] results = new int[2][];
		final Long limit = originalProductEntities.stream()
			.map(it -> it.getAttribute(ATTRIBUTE_PRIORITY, Long.class))
			.sorted().limit(75)
			.max(Long::compareTo)
			.orElseThrow();
		for (int i = 0; i < 2; i++) {
			results[i] = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeLessThan(ATTRIBUTE_PRIORITY, limit)
							),
							orderBy(
								random()
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);
					return result
						.getRecordData()
						.stream()
						.mapToInt(EntityReference::getPrimaryKeyOrThrowException)
						.toArray();
				}
			);
		}
		assertArrayAreDifferent(results[0], results[1]);
		Arrays.sort(results[0]);
		Arrays.sort(results[1]);
		assertArrayEquals(results[0], results[1], "After sorting arrays should be equal.");
	}

	@DisplayName("Should return entities randomly in consistent way using seed")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesRandomlyUsingProvidedSeed(Evita evita) {
		final int[][] results = new int[2][];
		for (int i = 0; i < 2; i++) {
			results[i] = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								randomWithSeed(42)
							),
							require(
								page(1, Integer.MAX_VALUE),
								debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
							)
						),
						EntityReference.class
					);
					return result
						.getRecordData()
						.stream()
						.mapToInt(EntityReference::getPrimaryKeyOrThrowException)
						.toArray();
				}
			);
		}
		assertArrayEquals(results[0], results[1]);
		Arrays.sort(results[0]);
		// sorted array must differ from random one
		assertArrayAreDifferent(results[0], results[1]);
	}

	@DisplayName("Should return entities sorted by String attribute (combined with filtering)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToStringAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE, DESC)
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
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it < 35000L)
						.orElse(false),
					(sealedEntityA, sealedEntityB) -> {
						final String attribute = sealedEntityB.getAttribute(ATTRIBUTE_CODE);
						return attribute.compareTo(sealedEntityA.getAttribute(ATTRIBUTE_CODE));
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by two attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToTwoAttributes(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CREATED, DESC),
							attributeNatural(ATTRIBUTE_MANUFACTURED)
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
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it < 35000L)
						.orElse(false),
					new PredicateWithComparatorTuple(
						(sealedEntity) -> sealedEntity.getAttribute(ATTRIBUTE_CREATED) != null,
						(sealedEntityA, sealedEntityB) -> {
							final OffsetDateTime created = sealedEntityB.getAttribute(ATTRIBUTE_CREATED);
							return created.compareTo(sealedEntityA.getAttribute(ATTRIBUTE_CREATED));
						}
					),
					new PredicateWithComparatorTuple(
						(sealedEntity) -> sealedEntity.getAttribute(ATTRIBUTE_MANUFACTURED) != null,
						Comparator.comparing(sealedEntity -> (LocalDate) sealedEntity.getAttribute(ATTRIBUTE_MANUFACTURED))
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by Number attribute (combined with filtering)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToNumberAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_PRIORITY, 14000L)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_PRIORITY, DESC)
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
					sealedEntity -> ofNullable((Long) sealedEntity.getAttribute(ATTRIBUTE_PRIORITY))
						.map(it -> it < 14000L)
						.orElse(false),
					(sealedEntityA, sealedEntityB) -> {
						final Long priority = sealedEntityB.getAttribute(ATTRIBUTE_PRIORITY);
						return priority.compareTo(sealedEntityA.getAttribute(ATTRIBUTE_PRIORITY));
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by BigDecimal attribute ")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToBigDecimalAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_QUANTITY, new BigDecimal("250"))
						),
						orderBy(
							attributeNatural(ATTRIBUTE_QUANTITY)
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
					sealedEntity -> ofNullable((BigDecimal) sealedEntity.getAttribute(ATTRIBUTE_QUANTITY))
						.map(it -> it.compareTo(new BigDecimal("250")) < 0)
						.orElse(false),
					Comparator.comparing(sealedEntityA -> (BigDecimal) sealedEntityA.getAttribute(ATTRIBUTE_QUANTITY))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by OffsetDateTime attribute ")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToOffsetDateTimeAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2003, 6, 10, 14, 24, 32, 0, ZoneOffset.UTC);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeLessThan(ATTRIBUTE_CREATED, theMoment)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CREATED)
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
					sealedEntity -> ofNullable((OffsetDateTime) sealedEntity.getAttribute(ATTRIBUTE_CREATED))
						.map(theMoment::isAfter)
						.orElse(false),
					Comparator.comparing(sealedEntityA -> (OffsetDateTime) sealedEntityA.getAttribute(ATTRIBUTE_CREATED))
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities sorted by sortable attribute compound")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldSortEntitiesAccordingToSortableAttributeCompound(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							attributeNatural(ATTRIBUTE_COMBINED_PRIORITY)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				final NullsLastComparatorWrapper<Long> priorityComparator = new NullsLastComparatorWrapper<Long>(
					Comparator.reverseOrder()
				);
				final NullsFirstComparatorWrapper<OffsetDateTime> createdComparator = new NullsFirstComparatorWrapper<OffsetDateTime>(
					Comparator.naturalOrder()
				);
				assertSortedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> true,
					(sealedEntityA, sealedEntityB) -> {
						final Long priorityA = sealedEntityA.getAttribute(ATTRIBUTE_PRIORITY);
						final OffsetDateTime createdA = sealedEntityA.getAttribute(ATTRIBUTE_CREATED);
						final Long priorityB = sealedEntityB.getAttribute(ATTRIBUTE_PRIORITY);
						final OffsetDateTime createdB = sealedEntityB.getAttribute(ATTRIBUTE_CREATED);
						final int priorityResult = priorityComparator.compare(priorityA, priorityB);
						return priorityResult == 0 ?
							createdComparator.compare(createdA, createdB) :
							priorityResult;
					}
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order of the attribute in the filter constraint")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderInFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random random = new Random();
				final String[] randomCodes = originalProductEntities
					.stream()
					.filter(it -> random.nextInt(10) == 1)
					.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
					.toArray(String[]::new);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CODE, randomCodes)
						),
						orderBy(
							attributeSetInFilter(ATTRIBUTE_CODE)
						),
						require(
							entityFetch(
								attributeContentAll()
							),
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					)
				);
				assertEquals(randomCodes.length, products.getRecordData().size());
				assertEquals(randomCodes.length, products.getTotalRecordCount());

				assertArrayEquals(
					randomCodes,
					products.getRecordData().stream()
						.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
						.toArray(String[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order of the attribute in the filter constraint with duplicate attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderInFilterWithDuplicateAttributes(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random random = new Random();
				final String[] randomCodes = originalProductEntities
					.stream()
					.filter(it -> random.nextInt(10) == 1)
					.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
					.toArray(String[]::new);
				final String[] randomCodesWithDuplicates = new String[randomCodes.length + 1];
				for (int i = -1; i < randomCodes.length; i++) {
					if (i == -1) {
						randomCodesWithDuplicates[i + 1] = randomCodes[0];
					} else {
						randomCodesWithDuplicates[i + 1] = randomCodes[i];
					}
				}

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeInSet(ATTRIBUTE_CODE, randomCodesWithDuplicates)
						),
						orderBy(
							attributeSetInFilter(ATTRIBUTE_CODE)
						),
						require(
							entityFetch(
								attributeContentAll()
							),
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					)
				);
				assertEquals(randomCodes.length, products.getRecordData().size());
				assertEquals(randomCodes.length, products.getTotalRecordCount());

				assertArrayEquals(
					randomCodes,
					products.getRecordData().stream()
						.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
						.toArray(String[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order of the attribute with prefetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderWithPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random random = new Random();
				final AttributeTuple[] randomData = originalProductEntities
					.stream()
					.filter(it -> random.nextInt(10) == 1)
					.map(it -> new AttributeTuple(
						it.getPrimaryKeyOrThrowException(),
						it.getAttribute(ATTRIBUTE_CODE, String.class)
					))
					.toArray(AttributeTuple[]::new);
				final Integer[] randomProductIds = Arrays.stream(randomData)
					.map(AttributeTuple::primaryKey)
					.toArray(Integer[]::new);
				final String[] randomCodes = Arrays.stream(randomData)
					.map(AttributeTuple::attributeValue)
					.toArray(String[]::new);
				ArrayUtils.shuffleArray(random, randomCodes);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(randomProductIds)
						),
						orderBy(
							attributeSetExact(ATTRIBUTE_CODE, randomCodes)
						),
						require(
							page(1, randomCodes.length),
							entityFetch(
								attributeContent(ATTRIBUTE_CODE)
							),
							debug(DebugMode.PREFER_PREFETCHING)
						)
					)
				);
				assertEquals(randomCodes.length, products.getRecordData().size());
				assertEquals(randomCodes.length, products.getTotalRecordCount());

				assertArrayEquals(
					randomCodes,
					products.getRecordData().stream()
						.map(it -> it.getAttribute(ATTRIBUTE_CODE, String.class))
						.toArray(String[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order of the attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrder(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random random = new Random();
				final AttributeTuple[] randomData = originalProductEntities
					.stream()
					.filter(it -> random.nextInt(10) == 1)
					.map(it -> new AttributeTuple(
						it.getPrimaryKeyOrThrowException(),
						it.getAttribute(ATTRIBUTE_CODE, String.class)
					))
					.toArray(AttributeTuple[]::new);
				final Integer[] randomProductIds = Arrays.stream(randomData)
					.map(AttributeTuple::primaryKey)
					.toArray(Integer[]::new);
				final String[] randomCodes = Arrays.stream(randomData)
					.map(AttributeTuple::attributeValue)
					.toArray(String[]::new);
				ArrayUtils.shuffleArray(random, randomCodes);
				final Integer[] randomSortedProductIds = Arrays.stream(randomCodes)
					.map(
						it -> Arrays.stream(randomData)
							.filter(att -> it.equals(att.attributeValue()))
							.map(AttributeTuple::primaryKey)
							.findFirst()
							.orElseThrow()
					)
					.toArray(Integer[]::new);


				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(randomProductIds)
						),
						orderBy(
							attributeSetExact(ATTRIBUTE_CODE, randomCodes)
						),
						require(
							entityFetch(
								attributeContent(ATTRIBUTE_CODE)
							)
						)
					)
				);
				assertEquals(randomCodes.length, products.getRecordData().size());
				assertEquals(randomCodes.length, products.getTotalRecordCount());

				assertArrayEquals(
					randomSortedProductIds,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKeyOrThrowException)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order of the attribute and append unknown")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderAndAppendUnknown(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final AttributeTuple[] randomData = originalProductEntities
					.stream()
					.map(it -> new AttributeTuple(
						it.getPrimaryKeyOrThrowException(),
						it.getAttribute(ATTRIBUTE_CODE, String.class)
					))
					.toArray(AttributeTuple[]::new);

				final String[] randomCodesStartingWithE = Arrays.stream(randomData)
					.map(AttributeTuple::attributeValue)
					.filter(it -> it.startsWith("E"))
					.toArray(String[]::new);
				Assert.isTrue(randomCodesStartingWithE.length >= 5, "Not enough products starting with E found");

				final Integer[] randomProductIdsStartingWithE = Arrays.stream(randomCodesStartingWithE)
					.map(
						it -> Arrays.stream(randomData)
							.filter(att -> it.equals(att.attributeValue()))
							.map(AttributeTuple::primaryKey)
							.findFirst()
							.orElseThrow()
					)
					.toArray(Integer[]::new);

				final String[] exactCodeOrder = Arrays.copyOfRange(randomCodesStartingWithE, 0, (int) (randomCodesStartingWithE.length * 0.5));
				final Integer[] exactOrder = Arrays.copyOfRange(randomProductIdsStartingWithE, 0, (int) (randomProductIdsStartingWithE.length * 0.5));
				final Integer[] theRest = Arrays.copyOfRange(randomProductIdsStartingWithE, (int) (randomProductIdsStartingWithE.length * 0.5), randomProductIdsStartingWithE.length);
				ArrayUtils.reverse(exactOrder);
				ArrayUtils.reverse(exactCodeOrder);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeStartsWith(ATTRIBUTE_CODE, "E")
						),
						orderBy(
							attributeSetExact(ATTRIBUTE_CODE, exactCodeOrder)
						),
						require(
							entityFetch(
								attributeContent(ATTRIBUTE_CODE)
							),
							page(1, randomCodesStartingWithE.length)
						)
					)
				);
				assertEquals(randomCodesStartingWithE.length, products.getRecordData().size());
				assertEquals(randomCodesStartingWithE.length, products.getTotalRecordCount());

				assertArrayEquals(
					ArrayUtils.mergeArrays(
						exactOrder, theRest
					),
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKeyOrThrowException)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order with duplicate attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderWithDuplicateAttributes(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Random random = new Random();
				final AttributeTuple[] randomData = originalProductEntities
					.stream()
					.filter(it -> random.nextInt(10) == 1)
					.map(it -> new AttributeTuple(
						it.getPrimaryKeyOrThrowException(),
						it.getAttribute(ATTRIBUTE_CODE, String.class)
					))
					.toArray(AttributeTuple[]::new);
				final Integer[] randomProductIds = Arrays.stream(randomData)
					.map(AttributeTuple::primaryKey)
					.toArray(Integer[]::new);
				final String[] randomCodes = Arrays.stream(randomData)
					.map(AttributeTuple::attributeValue)
					.toArray(String[]::new);
				ArrayUtils.shuffleArray(random, randomCodes);
				final String[] randomCodesWithDuplicates = new String[randomCodes.length + 1];
				for (int i = -1; i < randomCodes.length; i++) {
					if (i == -1) {
						randomCodesWithDuplicates[i + 1] = randomCodes[0];
					} else {
						randomCodesWithDuplicates[i + 1] = randomCodes[i];
					}
				}
				final Integer[] randomSortedProductIds = Arrays.stream(randomCodes)
					.map(
						it -> Arrays.stream(randomData)
							.filter(att -> it.equals(att.attributeValue()))
							.map(AttributeTuple::primaryKey)
							.findFirst()
							.orElseThrow()
					)
					.toArray(Integer[]::new);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(randomProductIds)
						),
						orderBy(
							attributeSetExact(ATTRIBUTE_CODE, randomCodesWithDuplicates)
						),
						require(
							entityFetch(
								attributeContent(ATTRIBUTE_CODE)
							)
						)
					)
				);
				assertEquals(randomCodes.length, products.getRecordData().size());
				assertEquals(randomCodes.length, products.getTotalRecordCount());

				assertArrayEquals(
					randomSortedProductIds,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKeyOrThrowException)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return only first page of filtered and sorted entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFirstPageOfEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE)
						),
						require(
							page(1, 3),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(
					originalProductEntities.stream().filter(it -> it.getAttribute(ATTRIBUTE_ALIAS) != null).count(),
					result.getTotalRecordCount()
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null,
					Comparator.comparing(sealedEntity -> (String) sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					0, 3
				);
				return null;
			}
		);
	}

	@DisplayName("Should return only fifth page of filtered and sorted entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFifthPageOfEntities(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE)
						),
						require(
							page(5, 3),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(
					originalProductEntities.stream().filter(it -> it.getAttribute(ATTRIBUTE_ALIAS) != null).count(),
					result.getTotalRecordCount()
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null,
					Comparator.comparing(sealedEntity -> (String) sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					4 * 3, 3
				);
				return null;
			}
		);
	}

	@DisplayName("Should return only first page with offset of filtered and sorted entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFirstPageOfEntitiesWithOffset(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE)
						),
						require(
							strip(0, 2),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(
					originalProductEntities.stream().filter(it -> it.getAttribute(ATTRIBUTE_ALIAS) != null).count(),
					result.getTotalRecordCount()
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null,
					Comparator.comparing(sealedEntity -> (String) sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					0, 2
				);
				return null;
			}
		);
	}

	@DisplayName("Should return only fifth page with offset of filtered and sorted entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFifthPageOfEntitiesWithOffset(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE)
						),
						require(
							strip(11, 30),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(
					originalProductEntities.stream().filter(it -> it.getAttribute(ATTRIBUTE_ALIAS) != null).count(),
					result.getTotalRecordCount()
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null,
					Comparator.comparing(sealedEntity -> (String) sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					11, 30
				);
				return null;
			}
		);
	}

	@DisplayName("Should return first page when there is not enough results")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnFirstPageOfEntitiesWhenThereIsNoRequiredPageAfterFiltering(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						orderBy(
							attributeNatural(ATTRIBUTE_CODE)
						),
						require(
							page(11, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);
				assertEquals(
					originalProductEntities.stream().filter(it -> it.getAttribute(ATTRIBUTE_ALIAS) != null).count(),
					result.getTotalRecordCount()
				);
				assertSortedAndPagedResultIs(
					originalProductEntities,
					result.getRecordData(),
					sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null,
					Comparator.comparing(sealedEntity -> (String) sealedEntity.getAttribute(ATTRIBUTE_CODE)),
					0, 10
				);
				return null;
			}
		);
	}

	@DisplayName("Should return attribute histogram for returned products")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnAttributeHistogram(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							attributeHistogram(20, ATTRIBUTE_QUANTITY, ATTRIBUTE_PRIORITY)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null)
					.collect(Collectors.toList());

				assertHistogramIntegrity(result, filteredProducts, ATTRIBUTE_QUANTITY, null, null);
				assertHistogramIntegrity(result, filteredProducts, ATTRIBUTE_PRIORITY, null, null);

				return null;
			}
		);
	}

	@DisplayName("Should return attribute histogram with attribute between")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnAttributeHistogramWithAttributeBetweenIsUsed(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<BigDecimal> sortedQuantities = originalProductEntities.stream()
					.map(it -> it.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
					.filter(Objects::nonNull)
					.sorted()
					.toList();
				final BigDecimal from = sortedQuantities.get(sortedQuantities.size() / 3);
				final BigDecimal to = sortedQuantities.get(sortedQuantities.size() / 3 * 2);

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								attributeBetween(ATTRIBUTE_QUANTITY, from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							attributeHistogram(20, ATTRIBUTE_QUANTITY)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class) != null)
					.collect(Collectors.toList());

				assertHistogramIntegrity(result, filteredProducts, ATTRIBUTE_QUANTITY, from, to);

				return null;
			}
		);
	}

	@DisplayName("Should return attribute histogram for returned products excluding constraints targeting that attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnSingleAttributeHistogramWithoutBeingAffectedByAttributeFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		final Predicate<SealedEntity> quantityAttributePredicate = it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
			.map(attr -> attr.compareTo(new BigDecimal("100")) >= 0 && attr.compareTo(new BigDecimal("900")) <= 0)
			.orElse(false);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeIsNotNull(ATTRIBUTE_ALIAS),
								userFilter(
									attributeBetween(ATTRIBUTE_QUANTITY, 100, 900)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							attributeHistogram(20, ATTRIBUTE_QUANTITY)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null).toList();

				// verify our test works
				final Predicate<SealedEntity> attributePredicate = quantityAttributePredicate;
				assertTrue(
					filteredProducts.size() > filteredProducts.stream().filter(attributePredicate).count(),
					"Price between query didn't filter out any products. Test is not testing anything!"
				);

				// the attribute `between(ATTRIBUTE_QUANTITY, 100, 900)` query must be ignored while computing its histogram
				assertHistogramIntegrity(
					result,
					filteredProducts,
					ATTRIBUTE_QUANTITY, new BigDecimal("100"), new BigDecimal("900")
				);

				return null;
			}
		);
	}

	@DisplayName("Should return attribute histogram for returned products excluding constraints targeting that attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnAttributeHistogramWithoutBeingAffectedByAttributeFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		final Predicate<SealedEntity> priorityAttributePredicate = it -> ofNullable((Long) it.getAttribute(ATTRIBUTE_PRIORITY))
			.map(attr -> attr >= 15000L && attr <= 90000L)
			.orElse(false);
		final Predicate<SealedEntity> quantityAttributePredicate = it -> ofNullable((BigDecimal) it.getAttribute(ATTRIBUTE_QUANTITY))
			.map(attr -> attr.compareTo(new BigDecimal("100")) >= 0 && attr.compareTo(new BigDecimal("900")) <= 0)
			.orElse(false);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeIsNotNull(ATTRIBUTE_ALIAS),
								userFilter(
									attributeBetween(ATTRIBUTE_QUANTITY, 100, 900),
									attributeBetween(ATTRIBUTE_PRIORITY, 15000, 90000)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							attributeHistogram(20, ATTRIBUTE_QUANTITY, ATTRIBUTE_PRIORITY)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> sealedEntity.getAttribute(ATTRIBUTE_ALIAS) != null).toList();

				// verify our test works
				final Predicate<SealedEntity> attributePredicate = priorityAttributePredicate.or(quantityAttributePredicate);
				assertTrue(
					filteredProducts.size() > filteredProducts.stream().filter(attributePredicate).count(),
					"Price between query didn't filter out any products. Test is not testing anything!"
				);

				// the attribute `between(ATTRIBUTE_QUANTITY, 100, 900)` query must be ignored while computing its histogram
				assertHistogramIntegrity(
					result,
					filteredProducts.stream().filter(priorityAttributePredicate).collect(Collectors.toList()),
					ATTRIBUTE_QUANTITY, new BigDecimal("100"), new BigDecimal("900")
				);

				// the attribute `between(ATTRIBUTE_PRIORITY, 15000, 90000)` query must be ignored while computing its histogram
				assertHistogramIntegrity(
					result,
					filteredProducts.stream().filter(quantityAttributePredicate).collect(Collectors.toList()),
					ATTRIBUTE_PRIORITY, new BigDecimal("15000"), new BigDecimal("90000")
				);

				return null;
			}
		);
	}

	/*
		HELPER METHODS AND ASSERTIONS
	 */

	@DisplayName("Should return entities by complex OR / NOT query")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByComplexOrNotQuery(Evita evita, List<SealedEntity> originalProductEntities) {
		final IntegerNumberRange[] sizeAttributes = getRandomAttributeValueArray(originalProductEntities, ATTRIBUTE_SIZE);
		final IntegerNumberRange sizeAttribute = sizeAttributes[0];

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, true),
								or(
									attributeInRange(ATTRIBUTE_SIZE, sizeAttribute.getPreciseFrom()),
									attributeIsNull(ATTRIBUTE_SIZE)
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
					originalProductEntities,
					sealedEntity -> Boolean.TRUE.equals(sealedEntity.getAttribute(ATTRIBUTE_ALIAS)) &&
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> it.length == 0 || Arrays.stream(it).anyMatch(x -> x.isWithin(sizeAttribute.getPreciseFrom())))
							.orElse(true),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return entities by NULL attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntitiesByNullAttributeQuery(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity matchingProduct = originalProductEntities.stream()
			.filter(it -> Boolean.TRUE.equals(it.getAttribute(ATTRIBUTE_ALIAS)))
			.filter(it -> ofNullable((IntegerNumberRange[]) it.getAttributeArray(ATTRIBUTE_SIZE))
				.map(attr -> attr.length == 0)
				.orElse(true))
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty())
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No matching product found!"));
		final int referencedBrandId = matchingProduct.getReferences(Entities.BRAND)
			.stream()
			.findFirst()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								referenceHaving(
									Entities.BRAND,
									entityPrimaryKeyInSet(referencedBrandId)
								),
								attributeEquals(ATTRIBUTE_ALIAS, true),
								attributeIsNull(ATTRIBUTE_SIZE)
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
					originalProductEntities,
					sealedEntity -> Boolean.TRUE.equals(sealedEntity.getAttribute(ATTRIBUTE_ALIAS)) &&
						ofNullable((IntegerNumberRange[]) sealedEntity.getAttributeArray(ATTRIBUTE_SIZE))
							.map(it -> it.length == 0)
							.orElse(true) &&
						sealedEntity.getReference(Entities.BRAND, referencedBrandId).isPresent(),
					result.getRecordData()
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when querying by PKs without specifying collection")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenCollectionIsMissing(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityCollectionRequiredException.class,
					() -> {
						session.query(
							query(
								filterBy(
									entityPrimaryKeyInSet(1)
								)
							),
							EntityReference.class
						);
					}
				);
				return null;
			}
		);
	}

	private EvitaResponse<EntityReference> getByAttributeSize(EvitaSessionContract session, int size) {
		return session.query(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeInRange(ATTRIBUTE_SIZE, size)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	private <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValue(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	private <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
		return originalProductEntities
			.stream()
			.map(it -> (T) it.getAttribute(attributeName))
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	private AttributeValue getRandomAttributeValueObject(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValueObject(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	private AttributeValue getRandomAttributeValueObject(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
		return originalProductEntities
			.stream()
			.flatMap(it -> it.getAttributeValues(attributeName).stream())
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	private <T extends Serializable> T[] getRandomAttributeValueArray(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return originalProductEntities
			.stream()
			.map(it -> (T[]) it.getAttributeArray(attributeName))
			.filter(it -> !ArrayUtils.isEmpty(it))
			.skip(10)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	public record PredicateWithComparatorTuple(Predicate<SealedEntity> predicate, Comparator<SealedEntity> comparator) {
	}

	private record AttributeTuple(Integer primaryKey, String attributeValue) {
	}

}
