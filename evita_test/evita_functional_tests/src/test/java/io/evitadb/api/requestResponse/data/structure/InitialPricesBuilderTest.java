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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InitialPricesBuilder} verifying price
 * setting, removal, querying, price-for-sale computation,
 * and change set generation on a freshly created entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("InitialPricesBuilder")
class InitialPricesBuilderTest extends AbstractBuilderTest {
	public static final Currency CZK =
		Currency.getInstance("CZK");
	public static final Currency EUR =
		Currency.getInstance("EUR");
	private final InitialPricesBuilder builder =
		new InitialPricesBuilder(PRODUCT_SCHEMA);

	@Nested
	@DisplayName("Setting prices")
	class SettingPricesTest {

		@Test
		@DisplayName("should create entity with prices")
		void shouldCreateEntityWithPrices() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPriceInnerRecordHandling(
						PriceInnerRecordHandling.LOWEST_PRICE
					)
					.setPrice(
						1, "basic", CZK,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "reference", CZK,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, false
					)
					.setPrice(
						3, "basic", EUR,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						4, "reference", EUR,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, false
					)
					.build();

			assertEquals(
				PriceInnerRecordHandling.LOWEST_PRICE,
				prices.getPriceInnerRecordHandling()
			);
			assertEquals(4, prices.getPrices().size());
			assertPrice(
				prices.getPrice(1, "basic", CZK),
				BigDecimal.ONE, BigDecimal.ZERO,
				BigDecimal.ONE, true
			);
			assertPrice(
				prices.getPrice(2, "reference", CZK),
				BigDecimal.ONE, BigDecimal.ZERO,
				BigDecimal.ONE, false
			);
			assertPrice(
				prices.getPrice(3, "basic", EUR),
				BigDecimal.ONE, BigDecimal.ZERO,
				BigDecimal.ONE, true
			);
			assertPrice(
				prices.getPrice(4, "reference", EUR),
				BigDecimal.ONE, BigDecimal.ZERO,
				BigDecimal.ONE, false
			);
		}

		@Test
		@DisplayName("should overwrite identical price")
		void shouldOverwriteIdenticalPrice() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPrice(
						1, "basic", CZK,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						1, "basic", CZK,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.build();

			assertEquals(1, prices.getPrices().size());
			assertPrice(
				prices.getPrice(1, "basic", CZK),
				BigDecimal.TEN, BigDecimal.ZERO,
				BigDecimal.TEN, true
			);
		}

		@Test
		@DisplayName("should refuse adding conflicting price")
		void shouldRefuseAddingConflictingPrice() {
			assertThrows(
				AmbiguousPriceException.class,
				() -> {
					final PricesContract prices =
						InitialPricesBuilderTest.this.builder
							.setPrice(
								1, "basic", CZK,
								BigDecimal.ONE,
								BigDecimal.ZERO,
								BigDecimal.ONE, true
							)
							.setPrice(
								2, "basic", CZK,
								BigDecimal.TEN,
								BigDecimal.ZERO,
								BigDecimal.TEN, true
							)
							.build();
				}
			);
		}

		@Test
		@DisplayName(
			"should allow conflicting price for different "
				+ "inner record id"
		)
		void shouldAllowAddingConflictingPriceForDifferentInnerRecordId() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPrice(
						1, "basic", CZK, 1,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "basic", CZK, 2,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.build();

			final Collection<PriceContract> basicPrices =
				prices.getPrices(CZK, "basic");

			assertEquals(2, basicPrices.size());
			assertTrue(
				basicPrices.stream()
					.anyMatch(it -> it.priceId() == 1)
			);
			assertTrue(
				basicPrices.stream()
					.anyMatch(it -> it.priceId() == 2)
			);
		}
	}

	@Nested
	@DisplayName("Price for sale computation")
	class PriceForSaleComputationTest {

		@Test
		@DisplayName(
			"should compute all prices for NONE strategy"
		)
		void shouldCorrectlyComputeAllPricesForSaleForNoneStrategy() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPriceInnerRecordHandling(
						PriceInnerRecordHandling.NONE
					)
					.setPrice(
						1, "basic", CZK, 1,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "vip", CZK, 1,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.setPrice(
						3, "basic", CZK, 2,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						4, "vip", CZK, 2,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.build();

			final List<PriceContract> allPricesForSale =
				prices.getAllPricesForSale(
					CZK, null, "vip", "basic"
				);

			assertEquals(1, allPricesForSale.size());
			// this is ambiguous situation - we can't decide
			// which price to use
			assertTrue(
				Set.of(2, 4).contains(
					allPricesForSale.get(0).priceId()
				)
			);
		}

		@Test
		@DisplayName(
			"should compute all prices for FIRST_OCCURRENCE"
		)
		void shouldCorrectlyComputeAllPricesForSaleForFirstOccurrenceStrategy() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPriceInnerRecordHandling(
						PriceInnerRecordHandling.LOWEST_PRICE
					)
					.setPrice(
						1, "basic", CZK, 1,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "vip", CZK, 1,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.setPrice(
						3, "basic", CZK, 2,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						4, "vip", CZK, 2,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.setPrice(
						5, "basic", CZK, 3,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						6, "notCalculated", CZK, 3,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.build();

			final List<PriceContract> allPricesForSale =
				prices.getAllPricesForSale(
					CZK, null, "vip", "basic"
				);

			assertEquals(3, allPricesForSale.size());
			for (PriceContract price : allPricesForSale) {
				assertTrue(
					Set.of(2, 4, 5)
						.contains(price.priceId())
				);
			}
		}

		@Test
		@DisplayName(
			"should compute all prices for SUM strategy"
		)
		void shouldCorrectlyComputeAllPricesForSaleForSumStrategy() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPriceInnerRecordHandling(
						PriceInnerRecordHandling.SUM
					)
					.setPrice(
						1, "basic", CZK, 1,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "vip", CZK, 1,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.setPrice(
						3, "basic", CZK, 2,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						4, "vip", CZK, 2,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.setPrice(
						5, "basic", CZK, 3,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						6, "notCalculated", CZK, 3,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.build();

			final List<PriceContract> allPricesForSale =
				prices.getAllPricesForSale(
					CZK, null, "vip", "basic"
				);

			assertEquals(1, allPricesForSale.size());
			final PriceContract priceContract =
				allPricesForSale.get(0);

			assertEquals(
				new CumulatedPrice(
					1,
					new PriceKey(2, "vip", CZK),
					Map.of(
						3,
						prices.getPrice(3, "basic", CZK)
							.orElseThrow()
					),
					new BigDecimal("21"),
					BigDecimal.ZERO,
					new BigDecimal("21")
				),
				priceContract
			);
		}
	}

	@Nested
	@DisplayName("Removing prices")
	class RemovingPricesTest {

		@Test
		@DisplayName("should remove specific price")
		void shouldRemoveSpecificPrice() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPrice(
						1, "basic", CZK,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "basic", EUR,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.removePrice(1, "basic", CZK)
					.build();

			assertEquals(1, prices.getPrices().size());
			assertTrue(
				prices.getPrice(1, "basic", CZK).isEmpty()
			);
			assertTrue(
				prices.getPrice(2, "basic", EUR).isPresent()
			);
		}

		@Test
		@DisplayName("should remove all prices")
		void shouldRemoveAllPrices() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPrice(
						1, "basic", CZK,
						BigDecimal.ONE, BigDecimal.ZERO,
						BigDecimal.ONE, true
					)
					.setPrice(
						2, "basic", EUR,
						BigDecimal.TEN, BigDecimal.ZERO,
						BigDecimal.TEN, true
					)
					.removeAllPrices()
					.build();

			assertTrue(prices.getPrices().isEmpty());
		}

		@Test
		@DisplayName(
			"should remove price inner record handling"
		)
		void shouldRemovePriceInnerRecordHandling() {
			final PricesContract prices =
				InitialPricesBuilderTest.this.builder
					.setPriceInnerRecordHandling(
						PriceInnerRecordHandling.LOWEST_PRICE
					)
					.removePriceInnerRecordHandling()
					.build();

			assertEquals(
				PriceInnerRecordHandling.NONE,
				prices.getPriceInnerRecordHandling()
			);
		}
	}

	@Nested
	@DisplayName("Querying prices")
	class QueryingPricesTest {

		@Test
		@DisplayName("should return price by key")
		void shouldReturnPriceByKey() {
			InitialPricesBuilderTest.this.builder
				.setPrice(
					1, "basic", CZK,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, true
				);

			final PriceKey priceKey =
				new PriceKey(1, "basic", CZK);
			final Optional<PriceContract> price =
				InitialPricesBuilderTest.this.builder
					.getPrice(priceKey);

			assertTrue(price.isPresent());
			assertEquals(1, price.get().priceId());
			assertEquals("basic", price.get().priceList());
			assertEquals(CZK, price.get().currency());
		}

		@Test
		@DisplayName("should report prices available")
		void shouldReportPricesAvailable() {
			final boolean available =
				InitialPricesBuilderTest.this.builder
					.pricesAvailable();

			assertTrue(available);
		}
	}

	@Nested
	@DisplayName("Change set")
	class ChangeSetTest {

		@Test
		@DisplayName("should build change set with mutations")
		void shouldBuildChangeSet() {
			InitialPricesBuilderTest.this.builder
				.setPriceInnerRecordHandling(
					PriceInnerRecordHandling.LOWEST_PRICE
				)
				.setPrice(
					1, "basic", CZK,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, true
				)
				.setPrice(
					2, "basic", EUR,
					BigDecimal.TEN, BigDecimal.ZERO,
					BigDecimal.TEN, true
				);

			final List<? extends LocalMutation<?, ?>> mutations =
				InitialPricesBuilderTest.this.builder
					.buildChangeSet()
					.toList();

			// 1 SetPriceInnerRecordHandlingMutation + 2
			// UpsertPriceMutation
			assertEquals(3, mutations.size());

			final long handlingCount = mutations.stream()
				.filter(
					SetPriceInnerRecordHandlingMutation
						.class::isInstance
				)
				.count();
			final long upsertCount = mutations.stream()
				.filter(
					UpsertPriceMutation.class::isInstance
				)
				.count();

			assertEquals(1, handlingCount);
			assertEquals(2, upsertCount);
		}

		@Test
		@DisplayName("should build empty change set")
		void shouldBuildEmptyChangeSet() {
			final InitialPricesBuilder emptyBuilder =
				new InitialPricesBuilder(PRODUCT_SCHEMA);

			final long count =
				emptyBuilder.buildChangeSet().count();

			// NONE handling still produces a
			// SetPriceInnerRecordHandlingMutation
			assertEquals(1, count);
		}
	}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	public static void assertPrice(
		Optional<PriceContract> price,
		BigDecimal priceWithoutTax,
		BigDecimal taxRate,
		BigDecimal priceWithTax,
		boolean indexed
	) {
		assertTrue(price.isPresent());
		assertPrice(
			price.orElseThrow(),
			priceWithoutTax, taxRate,
			priceWithTax, indexed
		);
	}

	public static void assertPrice(
		PriceContract price,
		BigDecimal priceWithoutTax,
		BigDecimal taxRate,
		BigDecimal priceWithTax,
		boolean indexed
	) {
		assertNotNull(price);
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.indexed());
	}
}
