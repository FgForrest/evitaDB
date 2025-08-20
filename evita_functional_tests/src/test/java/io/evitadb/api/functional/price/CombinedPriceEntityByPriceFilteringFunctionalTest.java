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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.priceContentRespectingFilter;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_REFERENCE;

/**
 * This test verifies whether entities can be filtered by prices.
 *
 * TOBEDONE JNO - create multiple functional tests - one run with enabled SelectionFormula, one with disabled
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by prices functionality - price combination")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class CombinedPriceEntityByPriceFilteringFunctionalTest extends EntityByPriceFilteringFunctionalTest {
	private static final String HUNDRED_PRODUCTS_WITH_COMBINED_PRICES = "HundredProductsWithCombinedPrices";

	private static final int SEED = 40;
	private final DataGenerator dataGenerator = new DataGenerator.Builder()
		.withPriceInnerRecordHandlingGenerator(faker -> PriceInnerRecordHandling.values()[faker.random().nextInt(PriceInnerRecordHandling.values().length - 1)])
		.withPriceIndexingDecider((priceList, faker) -> !PRICE_LIST_REFERENCE.equals(priceList))
		.build();

	@DataSet(value = HUNDRED_PRODUCTS_WITH_COMBINED_PRICES, destroyAfterClass = true)
	List<SealedEntity> setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> null;

			dataGenerator.getSampleCategorySchema(session);
			dataGenerator.getSampleBrandSchema(session);
			dataGenerator.getSampleStoreSchema(session);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
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
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceList(evita, originalProductEntities);
	}

	@DisplayName("Should return products with prices including non-indexed ones")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsIncludingNonIndexedPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsIncludingNonIndexedPrice(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency and returning all prices")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListReturningAllPrices(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListReturningAllPrices(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in different price list and certain currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndDifferentPriceLists(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndDifferentPriceLists(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMoment(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMoment(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInInterval(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInInterval(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTax(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTax(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithoutTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (with tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency in specific moment within interval (without tax) ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListAtCertainMomentInIntervalWithoutTaxOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in certain currency and any price list")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrency(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in certain price list and any currency")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInPriceList(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInPriceList(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in any price list and any currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceValidIn(evita, originalProductEntities);
	}

	@DisplayName("Should return products having price in currency valid in certain moment")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndValidIn(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndValidIn(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogram(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogram(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query (and validity)")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterAndValidity(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterAndValidity(evita, originalProductEntities);
	}

	@DisplayName("Should return price histogram for returned products excluding price between query")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and currency within interval (with tax) ordered by price asc without explicit AND")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd(evita, originalProductEntities);
	}

	@DisplayName("Should correctly traverse through all pages or results")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnCorrectlyTraverseThroughAllPagesOfResults(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnCorrectlyTraverseThroughAllPagesOfResults(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by price desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByPriceDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by biggest discount asc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountAscending(evita, originalProductEntities);
	}

	@DisplayName("Should return prefetched products with price in price list and certain currency ordered by discount desc")
	@UseDataSet(HUNDRED_PRODUCTS_WITH_COMBINED_PRICES)
	@Test
	@Override
	void shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(Evita evita, List<SealedEntity> originalProductEntities) {
		super.shouldReturnPrefetchedProductsHavingPriceInCurrencyAndPriceListOrderByDiscountDescending(evita, originalProductEntities);
	}

}
