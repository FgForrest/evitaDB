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

package io.evitadb.api.functional.price;

import com.github.javafaker.Faker;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.Evita;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.summingInt;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies whether entities can be filtered by prices.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by prices functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityByPriceFilteringFunctionalTest {
	private static final String HUNDRED_PRODUCTS_WITH_PRICES = "HundredProductsWithPrices";

	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator.Builder()
		.withPriceIndexingDecider((priceList, faker) -> !PRICE_LIST_REFERENCE.equals(priceList))
		.build();

	/**
	 * Verifies histogram integrity against source entities.
	 */
	protected static void assertHistogramIntegrity(
		EvitaResponse<SealedEntity> result,
		List<SealedEntity> filteredProducts,
		BigDecimal from, BigDecimal to, OffsetDateTime validIn
	) {
		final PriceHistogram priceHistogram = result.getExtraResult(PriceHistogram.class);
		assertNotNull(priceHistogram);
		assertTrue(priceHistogram.getBuckets().length <= 20);

		assertEquals(filteredProducts.size(), priceHistogram.getOverallCount());
		final List<BigDecimal> pricesForSale = filteredProducts
			.stream()
			.map(it -> it.getPriceForSale(CURRENCY_EUR, validIn, PRICE_LIST_VIP, PRICE_LIST_BASIC))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(PriceContract::priceWithTax)
			.toList();

		assertEquals(pricesForSale.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO), priceHistogram.getMin());
		assertEquals(pricesForSale.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO), priceHistogram.getMax());

		// verify bucket occurrences
		final Map<Integer, Integer> expectedOccurrences = filteredProducts
			.stream()
			.collect(
				Collectors.groupingBy(
					it -> findIndexInHistogram(it, priceHistogram, validIn),
					summingInt(entity -> 1)
				)
			);

		final Bucket[] buckets = priceHistogram.getBuckets();
		for (int i = 0; i < buckets.length; i++) {
			final Bucket bucket = priceHistogram.getBuckets()[i];
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
				ofNullable(expectedOccurrences.get(i)).orElse(0),
				bucket.occurrences()
			);
		}
	}

	/**
	 * Returns true if there is any indexed price for passed currency.
	 */
	protected static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull Currency currency) {
		return entity.getPrices(currency).stream().anyMatch(PriceContract::indexed);
	}

	/**
	 * Returns true if there is any indexed price for passed price list.
	 */
	protected static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull String priceList) {
		return entity.getPrices(priceList).stream().anyMatch(PriceContract::indexed);
	}

	/**
	 * Returns true if there is any indexed price for passed currency and price list.
	 */
	protected static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull OffsetDateTime atTheMoment) {
		return entity.getPrices().stream().filter(PriceContract::indexed).anyMatch(it -> it.validity() == null || it.validity().isValidFor(atTheMoment));
	}

	/**
	 * Returns true if there is any indexed price for passed currency and price list.
	 */
	protected static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull Currency currency, @Nonnull String priceList) {
		return entity.getPrices(currency, priceList).stream().anyMatch(PriceContract::indexed);
	}

	/**
	 * Returns true if there is any indexed price for passed currency and price list.
	 */
	protected static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull Currency currency, @Nonnull String priceList, @Nonnull OffsetDateTime atTheMoment) {
		return entity.getPrices(currency, priceList).stream().filter(PriceContract::indexed).anyMatch(it -> it.validAt(atTheMoment));
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids. Both lists are sorted naturally before
	 * the comparison is executed.
	 */
	protected static void assertResultEquals(@Nonnull List<SealedEntity> records, @Nonnull int... reference) {
		final List<Integer> recordsCopy = records.stream().map(SealedEntity::getPrimaryKey).sorted().collect(Collectors.toList());
		Arrays.sort(reference);

		assertSortedResultEquals(recordsCopy, reference);
	}

	/**
	 * Verifies that result contains at least one product with non-indexed price from passed price list.
	 */
	protected static void assertResultContainProductWithNonIndexedPriceFrom(@Nonnull List<SealedEntity> recordData, @Nonnull String... priceLists) {
		final Set<String> allowedPriceLists = Arrays.stream(priceLists).collect(Collectors.toSet());
		for (SealedEntity entity : recordData) {
			if (entity.getPrices().stream().anyMatch(price -> allowedPriceLists.contains(price.priceList()) && !price.indexed())) {
				return;
			}
		}
		fail("There is product that contains price from price lists: " + Arrays.stream(priceLists).map(Object::toString).collect(Collectors.joining(", ")));
	}

	/**
	 * Finds appropriate index in the histogram according to histogram thresholds.
	 */
	private static int findIndexInHistogram(SealedEntity entity, HistogramContract histogram, OffsetDateTime validIn) {
		final BigDecimal entityPrice = entity.getPriceForSale(CURRENCY_EUR, validIn, PRICE_LIST_VIP, PRICE_LIST_BASIC)
			.orElseThrow()
			.priceWithTax();
		final Bucket[] buckets = histogram.getBuckets();
		for (int i = buckets.length - 1; i >= 0; i--) {
			final Bucket bucket = buckets[i];
			final int priceCompared = entityPrice.compareTo(bucket.threshold());
			if (priceCompared >= 0) {
				return i;
			}
		}
		fail("Histogram span doesn't match current entity price: " + entityPrice);
		return -1;
	}

	/**
	 * Returns true if there is any indexed price for passed currency and price list.
	 */
	private static boolean hasAnyIndexedPrice(@Nonnull SealedEntity entity, @Nonnull Currency currency, @Nonnull OffsetDateTime atTheMoment) {
		return entity.getPrices(currency).stream().filter(PriceContract::indexed).anyMatch(it -> it.validity() == null || it.validity().isValidFor(atTheMoment));
	}

	/**
	 * Calculates the discount based on the selling price and a reference price from accompanying prices.
	 *
	 * @param completePrice        The complete price structure including the price for sale and any accompanying prices.
	 * @param resultPriceExtractor A function to extract the BigDecimal value from a given PriceContract.
	 * @return The calculated discount as a BigDecimal, or null if the reference price is not present.
	 */
	@Nullable
	private static BigDecimal toDiscount(
		@Nonnull PriceForSaleWithAccompanyingPrices completePrice,
		@Nonnull Function<PriceContract, BigDecimal> resultPriceExtractor
	) {
		final BigDecimal sellingPrice = resultPriceExtractor.apply(completePrice.priceForSale());
		final BigDecimal referencePrice = completePrice.accompanyingPrices().get("reference")
			.map(resultPriceExtractor)
			.orElse(null);
		if (referencePrice == null) {
			return null;
		} else {
			return referencePrice.compareTo(sellingPrice) > 0 ?
				referencePrice.subtract(sellingPrice) : BigDecimal.ZERO;
		}
	}

	/**
	 * Creates a comparator for SealedEntity that compares entities based on their discount prices.
	 *
	 * @param accompanyingPrices array of accompanying prices, can be null
	 * @param priceComparator    comparator for prices
	 * @return a comparator for SealedEntity
	 */
	@Nonnull
	private static Comparator<SealedEntity> createDiscountComparator(
		@Nullable AccompanyingPrice[] accompanyingPrices,
		@Nonnull Comparator<BigDecimal> priceComparator
	) {
		final Map<Integer, BigDecimal> memoizedDiscounts = new HashMap<>();
		final BigDecimal NEGATIVE = new BigDecimal(-1);
		final Function<SealedEntity, BigDecimal> discountCalculator = entity -> {
			final BigDecimal result = memoizedDiscounts.computeIfAbsent(
				entity.getPrimaryKey(),
				epk -> {
					final BigDecimal discount = entity.getPriceForSaleWithAccompanyingPrices(
							CURRENCY_EUR, null, new String[]{PRICE_LIST_VIP, PRICE_LIST_BASIC},
							accompanyingPrices
						)
						.map(it -> toDiscount(it, PriceContract::priceWithTax))
						.orElse(NEGATIVE);
					return discount;
				}
			);
			return result == NEGATIVE ? null : result;
		};
		return (o1, o2) -> {
			final BigDecimal priceDiscount1 = discountCalculator.apply(o1);
			final BigDecimal priceDiscount2 = discountCalculator.apply(o2);
			if (priceDiscount1 == null && priceDiscount2 == null) {
				return Integer.compare(o1.getPrimaryKey(), o2.getPrimaryKey());
			} else if (priceDiscount1 == null) {
				return 1;
			} else if (priceDiscount2 == null) {
				return -1;
			} else {
				return priceComparator.compare(priceDiscount1, priceDiscount2);
			}
		};
	}

	/**
	 * Verifies that result contains only prices in specified price lists.
	 */
	protected void assertResultContainOnlyPricesFrom(@Nonnull List<SealedEntity> recordData, @Nonnull Currency currency, @Nonnull String... priceLists) {
		final Set<String> allowedPriceLists = Arrays.stream(priceLists).collect(Collectors.toSet());
		for (SealedEntity entity : recordData) {
			assertTrue(
				entity.getPrices().stream()
					.allMatch(price -> allowedPriceLists.contains(price.priceList()) && currency.equals(price.currency()))
			);
		}
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	protected void assertResultIs(List<SealedEntity> originalEntities, Predicate<SealedEntity> predicate, List<SealedEntity> resultToVerify, PriceContentMode priceContentMode, Currency currency, OffsetDateTime validIn, String... priceLists) {
		@SuppressWarnings("ConstantConditions") final int[] expectedResult = originalEntities.stream().filter(predicate).mapToInt(EntityContract::getPrimaryKey).toArray();
		assertFalse(ArrayUtils.isEmpty(expectedResult), "Expected result should never be empty - this would cause false positive tests!");
		assertResultEquals(
			resultToVerify,
			expectedResult
		);

		if (priceLists.length > 0) {
			assertPricesForSaleAreAsExpected(resultToVerify, priceContentMode, currency, validIn, priceLists);
		}
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	protected void assertSortedResultIs(
		@Nonnull List<SealedEntity> originalEntities,
		@Nonnull Predicate<SealedEntity> predicate,
		@Nonnull List<SealedEntity> resultToVerify,
		@Nonnull Comparator<PriceContract> priceComparator,
		@Nonnull Page page,
		@Nonnull PriceContentMode priceContentMode,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull String... priceLists
	) {
		final String[] priceListClassifiers = Arrays.stream(priceLists).toArray(String[]::new);
		@SuppressWarnings("ConstantConditions") final int[] expectedResult = originalEntities
			.stream()
			.filter(predicate)
			// consider only entities that has valid selling price
			.filter(it -> it.getPriceForSale(currency, validIn, priceListClassifiers).isPresent())
			.sorted(
				(o1, o2) -> priceComparator.compare(
					o1.getPriceForSale(currency, validIn, priceListClassifiers).orElseThrow(),
					o2.getPriceForSale(currency, validIn, priceListClassifiers).orElseThrow()
				)
			)
			.mapToInt(EntityContract::getPrimaryKey)
			.skip(PaginatedList.getFirstItemNumberForPage(page.getPageNumber(), page.getPageSize()))
			.limit(page.getPageSize())
			.toArray();

		assertFalse(ArrayUtils.isEmpty(expectedResult), "Expected result should never be empty - this would cause false positive tests!");
		final List<Integer> recordsCopy = resultToVerify
			.stream()
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toList());

		assertSortedResultEquals(
			recordsCopy,
			expectedResult
		);

		assertPricesForSaleAreAsExpected(resultToVerify, priceContentMode, currency, validIn, priceLists);
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	protected void assertSortedResultByEntityIs(
		@Nonnull List<SealedEntity> originalEntities,
		@Nonnull Predicate<SealedEntity> predicate,
		@Nonnull List<SealedEntity> resultToVerify,
		@Nonnull Comparator<SealedEntity> entityComparator,
		@Nonnull Page page,
		@Nonnull PriceContentMode priceContentMode,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull String... priceLists
	) {
		final String[] priceListClassifiers = Arrays.stream(priceLists).toArray(String[]::new);
		@SuppressWarnings("ConstantConditions") final int[] expectedResult = originalEntities
			.stream()
			.filter(predicate)
			// consider only entities that has valid selling price
			.filter(it -> it.getPriceForSale(currency, validIn, priceListClassifiers).isPresent())
			.sorted(entityComparator)
			.mapToInt(EntityContract::getPrimaryKey)
			.skip(PaginatedList.getFirstItemNumberForPage(page.getPageNumber(), page.getPageSize()))
			.limit(page.getPageSize())
			.toArray();

		assertFalse(ArrayUtils.isEmpty(expectedResult), "Expected result should never be empty - this would cause false positive tests!");
		final List<Integer> recordsCopy = resultToVerify
			.stream()
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toList());

		assertSortedResultEquals(
			recordsCopy,
			expectedResult
		);

		assertPricesForSaleAreAsExpected(resultToVerify, priceContentMode, currency, validIn, priceLists);
	}

	@DataSet(value = HUNDRED_PRODUCTS_WITH_PRICES, destroyAfterClass = true)
	List<SealedEntity> setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> null;

			this.dataGenerator.getSampleCategorySchema(session);
			this.dataGenerator.getSampleBrandSchema(session);
			this.dataGenerator.getSampleStoreSchema(session);

			final List<EntityReferenceContract> storedProducts = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleProductSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			return storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), attributeContentAll(), priceContentRespectingFilter()).orElseThrow())
				.collect(Collectors.toList());
		});
	}

	@DisplayName("Should return products with price in price list and certain currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_SELLOUT) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_INTRODUCTION) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_BASIC),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_CZK,
					null,
					PRICE_LIST_VIP, PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC
				);
				assertResultContainOnlyPricesFrom(
					result.getRecordData(),
					CURRENCY_CZK,
					PRICE_LIST_VIP, PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with prices including non-indexed ones")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsIncludingNonIndexedPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter(PRICE_LIST_REFERENCE)
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_BASIC) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_REFERENCE),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_CZK,
					null,
					PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
				);
				assertResultContainProductWithNonIndexedPriceFrom(
					result.getRecordData(),
					PRICE_LIST_REFERENCE
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and certain currency and returning all prices")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListReturningAllPrices(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentAll()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_BASIC),
					result.getRecordData(),
					PriceContentMode.ALL,
					CURRENCY_CZK,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);
				final Set<Serializable> priceListsReturned = result.getRecordData()
					.stream()
					.flatMap(it -> it.getPrices().stream())
					.map(PriceContract::priceList)
					.collect(Collectors.toSet());
				assertTrue(priceListsReturned.size() > 2);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in different price list and certain currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndDifferentPriceLists(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_B2B, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_B2B) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_BASIC),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_CZK,
					null,
					PRICE_LIST_VIP, PRICE_LIST_B2B, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMoment(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_B2B, PRICE_LIST_BASIC),
								priceValidIn(theMoment)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);
				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_VIP, theMoment) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_B2B, theMoment) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_CZK, PRICE_LIST_BASIC, theMoment),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_CZK,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_B2B, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInInterval(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_CZK, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_CZK,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTax(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							),
							priceType(QueryPriceMode.WITHOUT_TAX)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);
				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);
				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax).reversed(),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final Set<Integer> productsWithSellingPrice = originalProductEntities
			.stream()
			.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
				hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
			.map(SealedEntity::getPrimaryKey)
			.limit(10)
			.collect(Collectors.toSet());
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productsWithSellingPrice.stream().mapToInt(Integer::intValue).toArray()),
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);
				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> productsWithSellingPrice.contains(sealedEntity.getPrimaryKey()),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final Set<Integer> productsWithSellingPrice = originalProductEntities
			.stream()
			.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
				hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
			.map(SealedEntity::getPrimaryKey)
			.limit(10)
			.collect(Collectors.toSet());
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productsWithSellingPrice.stream().mapToInt(Integer::intValue).toArray()),
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);
				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> productsWithSellingPrice.contains(sealedEntity.getPrimaryKey()),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax).reversed(),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceBetween(from, to)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceBetween(from, to)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							),
							priceType(QueryPriceMode.WITHOUT_TAX)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithoutTax),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceBetween(from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax).reversed(),
					page(1, Integer.MAX_VALUE),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceBetween(from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							),
							priceType(QueryPriceMode.WITHOUT_TAX)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithoutTax).reversed(),
					page(1, Integer.MAX_VALUE),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							),
							priceType(QueryPriceMode.WITHOUT_TAX)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithoutTax),
					page(1, Integer.MAX_VALUE),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax).reversed(),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								priceValidIn(theMoment),
								priceBetween(from, to)
							)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							),
							priceType(QueryPriceMode.WITHOUT_TAX)
						),
						orderBy(
							priceNatural(DESC)
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithoutTax).reversed(),
					page(1, 10),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					theMoment,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in certain currency and any price list")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInCurrency(CURRENCY_EUR)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in certain price list and any currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(PRICE_LIST_SELLOUT)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, PRICE_LIST_SELLOUT),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in any price list and any currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceValidIn(theMoment)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, theMoment),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					null,
					null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2010, 5, 5, 0, 0, 0, 0, ZoneOffset.UTC);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceValidIn(theMoment),
								priceInCurrency(CURRENCY_EUR)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					),
					SealedEntity.class
				);

				assertResultIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, theMoment),
					result.getRecordData(),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null
				);

				return null;
			}
		);
	}

	@DisplayName("Should return price histogram for returned products")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPriceHistogram(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
					.collect(Collectors.toList());

				assertHistogramIntegrity(result, filteredProducts, null, null, null);

				return null;
			}
		);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								userFilter(
									priceBetween(from, to)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
					.collect(Collectors.toList());

				// verify our test works
				final Predicate<SealedEntity> priceForSaleBetweenPredicate = it -> {
					final BigDecimal price = it.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC)
						.orElseThrow()
						.priceWithTax();
					return price.compareTo(from) >= 0 && price.compareTo(to) <= 0;
				};
				assertTrue(
					filteredProducts.size() > filteredProducts.stream().filter(priceForSaleBetweenPredicate).count(),
					"Price between query didn't filter out any products. Test is not testing anything!"
				);

				// the price between query must be ignored while computing price histogram
				assertHistogramIntegrity(result, filteredProducts, from, to, null);

				return null;
			}
		);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query (and validity)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterAndValidity(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final OffsetDateTime theMoment = OffsetDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								priceBetween(from, to)
							),
							priceValidIn(theMoment),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				final List<SealedEntity> filteredProducts = originalProductEntities
					.stream()
					.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP, theMoment) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC, theMoment))
					.collect(Collectors.toList());

				// verify our test works
				final Predicate<SealedEntity> priceForSaleBetweenPredicate = it -> {
					final Optional<PriceContract> priceForSale = it.getPriceForSale(CURRENCY_EUR, theMoment, PRICE_LIST_VIP, PRICE_LIST_BASIC);
					if (priceForSale.isEmpty()) {
						return false;
					} else {
						final BigDecimal price = priceForSale.get().priceWithTax();
						return price.compareTo(from) >= 0 && price.compareTo(to) <= 0;
					}
				};
				assertTrue(
					filteredProducts.size() > filteredProducts.stream().filter(priceForSaleBetweenPredicate).count(),
					"Price between query didn't filter out any products. Test is not testing anything!"
				);

				// the price between query must be ignored while computing price histogram
				assertHistogramIntegrity(result, filteredProducts, from, to, theMoment);

				return null;
			}
		);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("50");
		final BigDecimal to = new BigDecimal("150");
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Predicate<PriceContract> betweenPredicate = it -> it.priceWithTax().compareTo(from) >= 0 && it.priceWithTax().compareTo(to) <= 0;
				final List<SealedEntity> filteredProducts = Stream.concat(
					originalProductEntities
						.stream()
						.filter(
							sealedEntity ->
								sealedEntity.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_VIP).map(betweenPredicate::test).orElse(false) ||
									sealedEntity.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_BASIC).map(betweenPredicate::test).orElse(false)
						)
						.limit(3),
					originalProductEntities
						.stream()
						.filter(
							sealedEntity ->
							{
								final Optional<PriceContract> vipPrice = sealedEntity.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_VIP);
								final Optional<PriceContract> basicPrice = sealedEntity.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_BASIC);
								return (vipPrice.isPresent() && vipPrice.map(it -> !betweenPredicate.test(it)).orElse(true)) &&
									(basicPrice.map(it -> !betweenPredicate.test(it)).orElse(true));
							}
						)
						.limit(3)
				).collect(Collectors.toList());

				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(filteredProducts.stream().map(SealedEntity::getPrimaryKey).toArray(Integer[]::new)),
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								userFilter(
									priceBetween(from, to)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				// verify our test works
				final Predicate<SealedEntity> priceForSaleBetweenPredicate = it -> {
					final BigDecimal price = it.getPriceForSale(CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC)
						.orElseThrow()
						.priceWithTax();
					return price.compareTo(from) >= 0 && price.compareTo(to) <= 0;
				};
				assertEquals(3, result.getTotalRecordCount());
				assertTrue(
					filteredProducts.size() > filteredProducts.stream().filter(priceForSaleBetweenPredicate).count(),
					"Price between query didn't filter out any products. Test is not testing anything!"
				);

				// the price between query must be ignored while computing price histogram
				assertHistogramIntegrity(result, filteredProducts, from, to, null);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc without explicit AND")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd(Evita evita, List<SealedEntity> originalProductEntities) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("60");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
							priceBetween(from, to)
						),
						require(
							page(1, 10),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentAll()
							)
						),
						orderBy(
							priceNatural()
						)
					),
					SealedEntity.class
				);

				assertSortedResultIs(
					originalProductEntities,
					sealedEntity -> sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC),
					result.getRecordData(),
					Comparator.comparing(PriceContract::priceWithTax),
					page(1, 10),
					PriceContentMode.ALL,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should correctly traverse through all pages or results")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnCorrectlyTraverseThroughAllPagesOfResults(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				int currentPage = 1;
				int lastPage;
				do {
					final EvitaResponse<SealedEntity> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							),
							orderBy(
								priceNatural()
							),
							require(
								page(currentPage, 3),
								entityFetch(
									priceContent(PriceContentMode.RESPECTING_FILTER)
								)
							)
						),
						SealedEntity.class
					);

					lastPage = ((PaginatedList<SealedEntity>) result.getRecordPage()).getLastPageNumber();

					assertSortedResultIs(
						originalProductEntities,
						sealedEntity -> true,
						result.getRecordData(),
						Comparator.comparing(PriceContract::priceWithTax),
						page(currentPage, 3),
						PriceContentMode.RESPECTING_FILTER,
						CURRENCY_CZK,
						null,
						PRICE_LIST_BASIC
					);
				} while (++currentPage <= lastPage);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceDiscount(ASC, PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC)
						)
					),
					SealedEntity.class
				);

				final AccompanyingPrice[] accompanyingPrices = {
					new AccompanyingPrice("reference", PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC),
				};
				assertSortedResultByEntityIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC),
					result.getRecordData(),
					createDiscountComparator(accompanyingPrices, Comparator.naturalOrder()),
					page(1, Integer.MAX_VALUE),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceDiscount(PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC)
						)
					),
					SealedEntity.class
				);

				final AccompanyingPrice[] accompanyingPrices = {
					new AccompanyingPrice("reference", PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC),
				};
				assertSortedResultByEntityIs(
					originalProductEntities,
					sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
						hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC),
					result.getRecordData(),
					createDiscountComparator(accompanyingPrices, Comparator.reverseOrder()),
					page(1, Integer.MAX_VALUE),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		final Set<Integer> productsWithSellingPrice = originalProductEntities
			.stream()
			.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
				hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
			.map(SealedEntity::getPrimaryKey)
			.limit(10)
			.collect(Collectors.toSet());
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productsWithSellingPrice.stream().mapToInt(Integer::intValue).toArray()),
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceDiscount(ASC, PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC)
						)
					),
					SealedEntity.class
				);

				final AccompanyingPrice[] accompanyingPrices = {
					new AccompanyingPrice("reference", PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC),
				};
				assertSortedResultByEntityIs(
					originalProductEntities,
					sealedEntity -> productsWithSellingPrice.contains(sealedEntity.getPrimaryKey()),
					result.getRecordData(),
					createDiscountComparator(accompanyingPrices, Comparator.naturalOrder()),
					page(1, 20),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_PRICES)
	@Test
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		final Set<Integer> productsWithSellingPrice = originalProductEntities
			.stream()
			.filter(sealedEntity -> hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_VIP) ||
				hasAnyIndexedPrice(sealedEntity, CURRENCY_EUR, PRICE_LIST_BASIC))
			.map(SealedEntity::getPrimaryKey)
			.limit(10)
			.collect(Collectors.toSet());
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productsWithSellingPrice.stream().mapToInt(Integer::intValue).toArray()),
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 20),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.PREFER_PREFETCHING),
							entityFetch(
								priceContentRespectingFilter()
							)
						),
						orderBy(
							priceDiscount(PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC)
						)
					),
					SealedEntity.class
				);

				final AccompanyingPrice[] accompanyingPrices = {
					new AccompanyingPrice("reference", PRICE_LIST_SELLOUT, PRICE_LIST_INTRODUCTION, PRICE_LIST_BASIC),
				};
				assertSortedResultByEntityIs(
					originalProductEntities,
					sealedEntity -> productsWithSellingPrice.contains(sealedEntity.getPrimaryKey()),
					result.getRecordData(),
					createDiscountComparator(accompanyingPrices, Comparator.reverseOrder()),
					page(1, 20),
					PriceContentMode.RESPECTING_FILTER,
					CURRENCY_EUR,
					null,
					PRICE_LIST_VIP, PRICE_LIST_BASIC
				);

				return null;
			}
		);
	}

	void assertPricesForSaleAreAsExpected(
		@Nonnull List<SealedEntity> resultToVerify,
		@Nonnull PriceContentMode priceContentMode,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull String[] priceLists
	) {
		final Set<String> priceListsSet = Arrays.stream(priceLists).collect(Collectors.toSet());

		for (SealedEntity sealedEntity : resultToVerify) {
			if (sealedEntity.getPriceInnerRecordHandling() == PriceInnerRecordHandling.NONE) {
				final PriceContract priceForSale = sealedEntity.getPriceForSale()
					.orElseThrow();

				for (String priceList : priceLists) {
					if (priceList.equals(priceForSale.priceList())) {
						break;
					} else {
						assertTrue(
							sealedEntity.getPrices(currency, priceList)
								.stream()
								.filter(PriceContract::indexed)
								// for first occurrence strategy the price with more prioritized list might be found but is skipped, because is bigger than other inner record price
								.filter(it -> Objects.equals(it.innerRecordId(), priceForSale.innerRecordId()) || it.priceWithTax().compareTo(priceForSale.priceWithTax()) <= 0)
								.noneMatch(it -> it.validity() == null || validIn == null || it.validity().isValidFor(validIn)),
							() -> "There must be no price for more prioritized price lists! But is for: " + priceList
						);
					}
				}
			} else if (sealedEntity.getPriceInnerRecordHandling() == PriceInnerRecordHandling.SUM) {
				assertTrue(sealedEntity.getPriceForSale().isPresent());
			} else if (sealedEntity.getPriceInnerRecordHandling() == PriceInnerRecordHandling.LOWEST_PRICE) {
				final PriceContract priceForSale = sealedEntity.getPriceForSale()
					.orElseThrow();

				final Map<Integer, List<PriceContract>> pricesByInnerRecordId = sealedEntity.getPrices()
					.stream()
					.collect(Collectors.groupingBy(PriceContract::innerRecordId));

				for (List<PriceContract> pricesPerVariant : pricesByInnerRecordId.values()) {
					// we need to eagerly skip prices for inner record records for which the price is already found
					for (String priceList : priceLists) {
						final List<PriceContract> pricesSubSet = pricesPerVariant
							.stream()
							.filter(it -> Objects.equals(it.priceList(), priceList))
							.filter(it -> Objects.equals(it.currency(), currency))
							.filter(PriceContract::indexed)
							.filter(it -> it.validity() == null || validIn == null || it.validity().isValidFor(validIn))
							.toList();
						// for first occurrence strategy the price with more prioritized list might be found but is skipped, because is bigger than other inner record price
						assertTrue(
							pricesSubSet.isEmpty() ||
								priceForSale.equals(pricesSubSet.get(0)) ||
								priceForSale.priceWithTax().compareTo(pricesSubSet.get(0).priceWithTax()) <= 0,
							() -> "There must be no price for more prioritized price lists! But is for: " + priceList
						);
						if (!pricesSubSet.isEmpty()) {
							break;
						}
					}
				}
			}
			checkReturnedPrices(priceContentMode, currency, validIn, priceListsSet, sealedEntity);
		}
	}

	/**
	 * Method checks whether the returned prices conform to the requested fetch mode.
	 */
	void checkReturnedPrices(@Nonnull PriceContentMode priceContentMode, @Nonnull Currency currency, OffsetDateTime validIn, Set<String> priceListsSet, SealedEntity sealedEntity) {
		if (priceContentMode == PriceContentMode.NONE) {
			// no prices should be returned at all
			assertTrue(sealedEntity.getPrices().isEmpty());
		} else if (priceContentMode == PriceContentMode.RESPECTING_FILTER) {
			// only prices that match input filter can be returned
			assertTrue(
				sealedEntity
					.getPrices()
					.stream()
					.allMatch(
						price -> Objects.equals(price.currency(), currency) &&
							ofNullable(price.validity()).map(it -> validIn == null || it.isValidFor(validIn)).orElse(true) &&
							priceListsSet.contains(price.priceList())
					)
			);
		} else {
			// all - also not matching prices can be returned
			assertFalse(sealedEntity.getPrices().isEmpty());
		}
	}

}
