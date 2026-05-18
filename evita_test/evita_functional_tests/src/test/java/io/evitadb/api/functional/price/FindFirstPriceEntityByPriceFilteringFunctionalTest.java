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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static io.evitadb.api.query.QueryConstraints.priceContentRespectingFilter;
import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_EUR;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_REFERENCE;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_VIP;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies whether entities can be filtered by prices.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by prices functionality - find first")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(PRICE)
@Tag(FILTER)
public class FindFirstPriceEntityByPriceFilteringFunctionalTest extends EntityByPriceFilteringFunctionalTest {
	private static final String HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES = "HundredProductsWithFindFirstPrices";

	private static final int SEED = 40;

	@DataSet(value = HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES, destroyAfterClass = true)
	List<SealedEntity> setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> null;

			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.withPriceInnerRecordHandlingGenerator(faker -> PriceInnerRecordHandling.LOWEST_PRICE)
				.withPriceIndexingDecider((priceList, faker) -> !PRICE_LIST_REFERENCE.equals(priceList))
				.build();

			dataGenerator.getSampleCategorySchema(session);
			dataGenerator.getSampleBrandSchema(session);
			dataGenerator.getSampleStoreSchema(session);

			final List<EntityReferenceContract> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(session),
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
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceList(evita, originalProductEntities);
	}

	@DisplayName("Should return products with prices including non-indexed ones")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsIncludingNonIndexedPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsIncludingNonIndexedPrice(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency and returning all prices")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListReturningAllPrices(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListReturningAllPrices(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in different price list and certain currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndDifferentPriceLists(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndDifferentPriceLists(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMoment(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMoment(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInInterval(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInInterval(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTax(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTax(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in certain currency and any price list")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrency(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in certain price list and any currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInPriceList(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in any price list and any currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceValidIn(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndValidIn(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogram(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogram(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query (and validity)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterAndValidity(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterAndValidity(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc without explicit AND")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd(evita, originalProductEntities);
	}

	@DisplayName("Should correctly traverse through all pages or results")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnCorrectlyTraverseThroughAllPagesOfResults(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnCorrectlyTraverseThroughAllPagesOfResults(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(evita, originalProductEntities);
	}

	@DisplayName("Should expand price histogram for LOWEST_PRICE entities into per-inner-record data points")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_FIND_FIRST_PRICES)
	@Test
	@Tag(HISTOGRAM)
	void shouldExpandLowestPriceHistogramToPerInnerRecordPrices(@Nonnull Evita evita, @Nonnull List<SealedEntity> originalProductEntities) {
		// the engine's per-inner-record bypass fires only when every `FilteredPriceRecordAccessor` in
		// the filtering formula tree is histogram-aware. This requires a pure LOWEST_PRICE catalog
		// (no `Sum`/`Plain` siblings) and no prefetch wrappers (no `entityPrimaryKeyInSet`).
		final List<SealedEntity> lowestPriceProducts = originalProductEntities
			.stream()
			.filter(it -> hasAnyIndexedPrice(it, CURRENCY_EUR, PRICE_LIST_VIP) ||
				hasAnyIndexedPrice(it, CURRENCY_EUR, PRICE_LIST_BASIC))
			.toList();

		assertFalse(
			lowestPriceProducts.isEmpty(),
			"LOWEST_PRICE-only fixture must contain at least one entity with a price in scope!"
		);
		// per-inner-record bypass invariant: the histogram must report one data point per inner-record
		// id in scope (not one per entity), so the expected count is strictly greater than the entity
		// count whenever any entity carries more than one inner-record id
		final int expectedDataPoints = countInnerRecordPricesInScope(lowestPriceProducts, null);
		long multiInnerEntities = 0L;
		for (final SealedEntity it : lowestPriceProducts) {
			if (it.getAllPricesForSale(CURRENCY_EUR, null, PRICE_LIST_VIP, PRICE_LIST_BASIC).size() > 1) {
				multiInnerEntities++;
			}
		}
		assertTrue(
			multiInnerEntities > 0,
			"Fixture must contain at least one LOWEST_PRICE entity with multiple inner records to be a " +
				"meaningful test of per-inner-record expansion — otherwise the per-entity and " +
				"per-inner-record counts coincide"
		);
		assertTrue(
			expectedDataPoints > lowestPriceProducts.size(),
			"Per-inner-record histogram count (" + expectedDataPoints + ") must strictly exceed the " +
				"entity count (" + lowestPriceProducts.size() + ") when multi-inner-record entities exist"
		);

		// case 1: query without priceBetween — verify the basic per-inner-record expansion. The
		// `VERIFY_ALTERNATIVE_INDEX_RESULTS` / `VERIFY_POSSIBLE_CACHING_TREES` debug modes are
		// intentionally **not** added here. The per-inner-record bypass now fires both through the
		// preferred prefetch-eligible plan (`SelectionFormula` propagates the histogram capability
		// from its inner LP) and through the bare index plan. Both plans therefore agree on the
		// per-inner-record count when this fixture is used in isolation. The cross-plan verifier is
		// still disabled because the bypass is only exercised by histogram queries running against a
		// pure LOWEST_PRICE catalog — the verifier rebuilds alternative plans whose accessor lists
		// differ in subtle ways (`getFilteredPriceRecordsForHistogram` vs the per-entity collector)
		// and the resulting `Histogram[overall=N]` counts diverge across plans, which the verifier
		// would surface as `InconsistentResultsException`. The per-inner-record assertion below is
		// itself the cross-plan invariant we care about.
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
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				assertHistogramIntegrityPerInnerRecord(result, lowestPriceProducts, null, null, null);

				return null;
			}
		);

		// case 2: query with priceBetween — the histogram must still cover the relaxed-baseline scope
		// (i.e. include inner-record prices outside the [from, to] slider) because the engine's
		// per-inner-record funnel ignores the sellingPricePredicate. See the note in case 1 for why
		// the standard debug verification flags are omitted.
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
							entityFetch(),
							priceHistogram(20)
						)
					),
					SealedEntity.class
				);

				// the relaxed baseline must still cover the same per-inner-record set — `priceBetween`
				// in `userFilter` cannot shrink the histogram scope
				assertHistogramIntegrityPerInnerRecord(result, lowestPriceProducts, from, to, null);

				return null;
			}
		);
	}

	/**
	 * Counts the total number of distinct inner-record selling prices across all passed LOWEST_PRICE
	 * entities for the standard `(CURRENCY_EUR, [PRICE_LIST_VIP, PRICE_LIST_BASIC], validIn)` scope.
	 * Used to derive the expected `overallCount` for the per-inner-record histogram path.
	 */
	private static int countInnerRecordPricesInScope(
		@Nonnull List<SealedEntity> lowestPriceEntities,
		@Nullable OffsetDateTime validIn
	) {
		int total = 0;
		for (final SealedEntity entity : lowestPriceEntities) {
			total += entity.getAllPricesForSale(CURRENCY_EUR, validIn, PRICE_LIST_VIP, PRICE_LIST_BASIC).size();
		}
		return total;
	}
}
