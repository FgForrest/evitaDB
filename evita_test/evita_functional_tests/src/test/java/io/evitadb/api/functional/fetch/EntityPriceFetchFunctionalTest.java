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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for entity price fetch operations including all prices,
 * accompanied prices, filtered prices by currency/price list/validity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity price fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityPriceFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Multiple entities with all prices by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAllPricesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_USD::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								priceContentAll()
							)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInCurrency(product, CURRENCY_GBP, CURRENCY_USD);
					assertHasPriceInPriceList(product, PRICE_LIST_BASIC, PRICE_LIST_INTRODUCTION);
				}
				return null;
			}
		);
	}

	@DisplayName("Should return entity with price for sale and accompanied price")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithPriceForSaleAndAccompanyingPrice(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault()
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				return null;
			}
		);
	}

	@DisplayName("Should return entity with price for sale and two accompanied prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithPriceForSaleAndTwoAccompanyingPrices(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault(),
								accompanyingPriceContent("myPrice", PRICE_LIST_VIP)
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				final Optional<PriceContract> myAccompanyingPrice = product.getAccompanyingPrice("myPrice");
				assertTrue(myAccompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, myAccompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_VIP, myAccompanyingPrice.get().priceList());

				assertArrayEquals(
					new String[]{AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE, "myPrice"},
					product.getPriceForSaleWithAccompanyingPrices()
						.orElseThrow()
						.getAccompanyingPrices()
						.keySet()
						.toArray(String[]::new),
					"Product should have two accompanying prices!"
				);

				return null;
			}
		);
	}

	@DisplayName("Should return entity with different price for sale and accompanied prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithDifferentPriceForSaleAndAccompanyingPrices(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		final Map<Integer, SealedEntity> originalProductsByPk = originalProducts
			.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));
		final Set<Integer> matchingPks = Arrays.stream(entitiesMatchingTheRequirements).collect(Collectors.toSet());

		final SealedEntity selectedProduct = originalProducts
			.stream()
			.filter(it -> matchingPks.contains(it.getPrimaryKey()))
			.filter(
				it -> it.getReferences(Entities.PRODUCT)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.map(originalProductsByPk::get)
					.anyMatch(
						refProd -> {
							final Set<String> refPriceLists = refProd
								.getPrices()
								.stream()
								.filter(refPrice -> CURRENCY_EUR.equals(refPrice.currency()))
								.map(PriceContract::priceList)
								.collect(Collectors.toSet());
							return refPriceLists.size() > 1 &&
								refPriceLists.contains(PRICE_LIST_BASIC);
						}
					)
			)
			.findFirst()
			.orElseThrow();
		final String secondPriceList = selectedProduct.getReferences(Entities.PRODUCT)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.map(originalProductsByPk::get)
			.flatMap(refProd -> refProd.getPrices().stream())
			.filter(price -> CURRENCY_EUR.equals(price.currency()) && !PRICE_LIST_BASIC.equals(price.priceList()))
			.map(PriceContract::priceList)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProduct.getPrimaryKeyOrThrowException()),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault(),
								referenceContent(
									Entities.PRODUCT,
									(FilterBy) null,
									entityFetch(
										priceContent(PriceContentMode.RESPECTING_FILTER),
										accompanyingPriceContent("myPrice", secondPriceList)
									)
								)
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				assertTrue(product.getAccompanyingPrice(secondPriceList).isEmpty());

				final SealedEntity nestedProduct = product.getReferences(Entities.PRODUCT)
					.stream()
					.map(it -> it.getReferencedEntity().orElseThrow())
					.filter(it -> it.getPrice(PRICE_LIST_BASIC, CURRENCY_EUR).isPresent() && it.getPrice(
						secondPriceList, CURRENCY_EUR).isPresent())
					.findFirst()
					.orElseThrow();

				final Optional<PriceContract> myAccompanyingPrice = nestedProduct.getAccompanyingPrice("myPrice");
				assertTrue(myAccompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, myAccompanyingPrice.get().currency());
				assertEquals(secondPriceList, myAccompanyingPrice.get().priceList());

				assertArrayEquals(
					new String[]{"myPrice"},
					nestedProduct.getPriceForSaleWithAccompanyingPrices()
						.orElseThrow()
						.getAccompanyingPrices()
						.keySet()
						.toArray(String[]::new),
					"Product should have only `myPrice`!"
				);

				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices in selected currency by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesInCurrencyByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> {
				final List<PriceContract> filteredPrices = it.getPrices()
					.stream()
					.filter(PriceContract::indexed)
					.filter(price -> Objects.equals(price.priceList(), PRICE_LIST_BASIC))
					.toList();
				return filteredPrices.stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					filteredPrices.stream().map(PriceContract::currency).noneMatch(CURRENCY_USD::equals);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceInCurrency(CURRENCY_EUR)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					)
				);
				assertEquals(Math.min(20, entitiesMatchingTheRequirements.length), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInCurrency(product, CURRENCY_EUR);
					assertHasNotPriceInCurrency(product, CURRENCY_USD);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices in selected price lists by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesInPriceListsByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices(CURRENCY_USD)
				.stream()
				.filter(PriceContract::indexed)
				.map(PriceContract::priceList)
				.anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices(CURRENCY_USD).stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::priceList)
					.noneMatch(pl ->
						           pl.equals(PRICE_LIST_REFERENCE) ||
							           pl.equals(PRICE_LIST_INTRODUCTION) ||
							           pl.equals(PRICE_LIST_B2B) ||
							           pl.equals(PRICE_LIST_VIP)
					)
		);

		assertTrue(
			entitiesMatchingTheRequirements.length > 0,
			"None entity match the filter, test would not work!"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								priceInCurrency(CURRENCY_USD),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					)
				);

				assertEquals(Math.min(entitiesMatchingTheRequirements.length, 20), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInPriceList(product, PRICE_LIST_BASIC);
					assertHasNotPriceInPriceList(
						product, PRICE_LIST_REFERENCE, PRICE_LIST_INTRODUCTION, PRICE_LIST_B2B, PRICE_LIST_VIP);
				}
				return null;
			}
		);
	}

	@DisplayName("Should filter product references by different price criteria and compute nested price for sale independently")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	@Disabled("This test reveals internal issue in price filtering, see issue TOBEDONE JNO #1081")
	void shouldReturnOnlyReferencedProductsMatchingDifferentPriceCriteriaWithOwnPriceForSale(
		Evita evita, List<SealedEntity> originalProducts) {
		final Map<Integer, SealedEntity> originalProductsByPk = originalProducts
			.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));

		// find a product that:
		// - has a price in EUR / basic (parent price context)
		// - has >= 2 PRODUCT self-references
		// - at least one referenced product HAS a price in USD / VIP
		// - at least one referenced product does NOT have a price in USD / VIP
		final SealedEntity selectedProduct = originalProducts
			.stream()
			.filter(it -> it.getPrices().stream()
				.anyMatch(p -> CURRENCY_EUR.equals(p.currency()) && PRICE_LIST_BASIC.equals(p.priceList())))
			.filter(it -> it.getReferences(Entities.PRODUCT).size() >= 2)
			.filter(it -> {
				final List<ReferenceContract> refs = it.getReferences(Entities.PRODUCT)
					.stream().toList();
				final boolean hasMatching = refs.stream()
					.map(ref -> originalProductsByPk.get(ref.getReferencedPrimaryKey()))
					.filter(Objects::nonNull)
					.anyMatch(refProd -> refProd.getPrices().stream()
						.anyMatch(p -> CURRENCY_USD.equals(p.currency()) && PRICE_LIST_VIP.equals(p.priceList())));
				final boolean hasExcluded = refs.stream()
					.map(ref -> originalProductsByPk.get(ref.getReferencedPrimaryKey()))
					.filter(Objects::nonNull)
					.anyMatch(refProd -> refProd.getPrices().stream()
						.noneMatch(p -> CURRENCY_USD.equals(p.currency()) && PRICE_LIST_VIP.equals(p.priceList())));
				return hasMatching && hasExcluded;
			})
			.findFirst()
			.orElseThrow(() -> new IllegalStateException(
				"No product found with >= 2 PRODUCT refs where some match USD/VIP and some do not!"));

		// pre-compute expected matching and excluded reference PKs
		final Set<Integer> expectedMatchingRefPks = selectedProduct.getReferences(Entities.PRODUCT)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.filter(pk -> {
				final SealedEntity refProd = originalProductsByPk.get(pk);
				return refProd != null && refProd.getPrices().stream()
					.anyMatch(p -> CURRENCY_USD.equals(p.currency()) && PRICE_LIST_VIP.equals(p.priceList()));
			})
			.collect(Collectors.toSet());

		final Set<Integer> expectedExcludedRefPks = selectedProduct.getReferences(Entities.PRODUCT)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.filter(pk -> !expectedMatchingRefPks.contains(pk))
			.collect(Collectors.toSet());

		assertFalse(expectedMatchingRefPks.isEmpty(), "There should be at least one matching reference!");
		assertFalse(expectedExcludedRefPks.isEmpty(), "There should be at least one excluded reference!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProduct.getPrimaryKeyOrThrowException()),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								referenceContent(
									Entities.PRODUCT,
									filterBy(
										entityHaving(
											and(
												priceInCurrency(CURRENCY_USD),
												priceInPriceLists(PRICE_LIST_VIP)
											)
										)
									),
									entityFetch(
										priceContentRespectingFilter()
									)
								)
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);

				// parent product has priceForSale in EUR / basic
				final Optional<PriceContract> priceForSale = product.getPriceForSale();
				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				// returned PRODUCT references contain only PKs from expectedMatchingRefPks
				final Set<Integer> returnedRefPks = product.getReferences(Entities.PRODUCT)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(Collectors.toSet());

				assertEquals(
					expectedMatchingRefPks, returnedRefPks,
					"Returned reference PKs should match expected matching PKs!"
				);

				// no PK from expectedExcludedRefPks is present in returned references
				for (Integer excludedPk : expectedExcludedRefPks) {
					assertFalse(
						returnedRefPks.contains(excludedPk),
						"Reference PK " + excludedPk + " should not be present in returned references!"
					);
				}

				// each nested referenced product has priceForSale in USD / VIP
				for (ReferenceContract ref : product.getReferences(Entities.PRODUCT)) {
					final SealedEntity nestedProduct = ref.getReferencedEntity().orElseThrow(
						() -> new IllegalStateException(
							"Referenced product " + ref.getReferencedPrimaryKey() + " should have entity body!")
					);
					final Optional<PriceContract> nestedPriceForSale = nestedProduct.getPriceForSale();
					assertTrue(
						nestedPriceForSale.isPresent(),
						"Nested product " + ref.getReferencedPrimaryKey() + " should have price for sale!"
					);
					assertEquals(
						CURRENCY_USD, nestedPriceForSale.get().currency(),
						"Nested product price for sale should be in USD!"
					);
					assertEquals(
						PRICE_LIST_VIP, nestedPriceForSale.get().priceList(),
						"Nested product price for sale should be in VIP price list!"
					);
				}

				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices valid in specified time by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesValidInTimeByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2015, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices()
				.stream()
				.filter(PriceContract::indexed)
				.map(PriceContract::validity)
				.anyMatch(validity -> validity == null || validity.isValidFor(theMoment))
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								priceValidIn(theMoment)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							),
							page(1, 100)
						)
					)
				);
				assertEquals(Math.min(100, entitiesMatchingTheRequirements.length), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					for (PriceContract price : product.getPrices()) {
						assertTrue(
							price.validity() == null || price.validity().isValidFor(theMoment),
							"Listed price " + price + " which is not valid for the moment!"
						);
					}
				}
				return null;
			}
		);
	}

}
