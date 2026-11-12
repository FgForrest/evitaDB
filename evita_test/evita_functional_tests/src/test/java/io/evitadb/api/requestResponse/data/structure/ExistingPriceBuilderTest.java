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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.data.structure.InitialPricesBuilderTest.assertPrice;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * This test verifies contract of {@link ExistingPricesBuilder}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ExistingPricesBuilder")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(PRICE)
class ExistingPriceBuilderTest extends AbstractBuilderTest {
	public static final Currency CZK =
		Currency.getInstance("CZK");
	public static final Currency EUR =
		Currency.getInstance("EUR");
	private Prices initialPrices;
	private ExistingPricesBuilder builder;

	@BeforeEach
	void setUp() {
		this.initialPrices = new InitialPricesBuilder(
			PRODUCT_SCHEMA
		)
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
		this.builder = new ExistingPricesBuilder(
			PRODUCT_SCHEMA, this.initialPrices
		);
	}

	@Nested
	@DisplayName("Setting and overwriting prices")
	class SettingAndOverwritingPricesTest {

		@Test
		@DisplayName("should add new price")
		void shouldAddNewPrice() {
			ExistingPriceBuilderTest.this.builder.setPrice(
				5, "discount", CZK,
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);
			assertPrice(
				ExistingPriceBuilderTest.this.builder
					.getPrice(5, "discount", CZK),
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);
			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertPrice(
				updatedPrices.getPrice(
					5, "discount", CZK
				),
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);

			final Collection<PriceContract> prices =
				updatedPrices.getPrices();
			assertEquals(5, prices.size());
		}

		@Test
		@DisplayName("should overwrite existing price")
		void shouldOverWriteExistingPrice() {
			ExistingPriceBuilderTest.this.builder.setPrice(
				1, "basic", CZK,
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);
			assertPrice(
				ExistingPriceBuilderTest.this.builder
					.getPrice(1, "basic", CZK),
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);
			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertPrice(
				updatedPrices.getPrice(1, "basic", CZK),
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);

			final Collection<PriceContract> prices =
				updatedPrices.getPrices();
			assertEquals(4, prices.size());
		}

		@Test
		@DisplayName(
			"should set price with validity range"
		)
		void shouldSetPriceWithValidity() {
			final DateTimeRange validity =
				DateTimeRange.since(
					OffsetDateTime.of(
						2025, 1, 1, 0, 0, 0, 0,
						ZoneOffset.UTC
					)
				);
			ExistingPriceBuilderTest.this.builder.setPrice(
				5, "seasonal", CZK,
				new BigDecimal("100"),
				new BigDecimal("21"),
				new BigDecimal("121"),
				validity,
				true
			);

			final Optional<PriceContract> price =
				ExistingPriceBuilderTest.this.builder
					.getPrice(5, "seasonal", CZK);
			assertTrue(price.isPresent());
			assertEquals(
				validity, price.get().validity()
			);
			assertEquals(
				new BigDecimal("100"),
				price.get().priceWithoutTax()
			);
		}

		@Test
		@DisplayName(
			"should set price with inner record id"
		)
		void shouldSetPriceWithInnerRecordId() {
			ExistingPriceBuilderTest.this.builder.setPrice(
				5, "basic", CZK, 42,
				new BigDecimal("50"),
				new BigDecimal("10"),
				new BigDecimal("55"),
				true
			);

			final Optional<PriceContract> price =
				ExistingPriceBuilderTest.this.builder
					.getPrice(5, "basic", CZK);
			assertTrue(price.isPresent());
			assertEquals(
				42, price.get().innerRecordId()
			);
		}
	}

	@Nested
	@DisplayName("No-op and deduplication")
	class NoOpAndDeduplicationTest {

		@Test
		@DisplayName(
			"should skip mutations that mean no change"
		)
		void shouldSkipMutationsThatMeansNoChange() {
			ExistingPriceBuilderTest.this.builder
				.setPrice(
					1, "basic", CZK,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, true
				)
				.setPrice(
					2, "reference", CZK,
					BigDecimal.TEN, BigDecimal.ZERO,
					BigDecimal.ONE, true
				)
				.setPrice(
					2, "reference", CZK,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, false
				)
				.setPriceInnerRecordHandling(
					PriceInnerRecordHandling.LOWEST_PRICE
				);

			assertEquals(
				0,
				ExistingPriceBuilderTest.this.builder
					.buildChangeSet().count()
			);
		}
	}

	@Nested
	@DisplayName("Removing prices")
	class RemovingPricesTest {

		@Test
		@DisplayName("should remove existing price")
		void shouldRemoveExistingPrice() {
			ExistingPriceBuilderTest.this.builder
				.removePrice(1, "basic", CZK);
			assertNull(
				ExistingPriceBuilderTest.this.builder
					.getPrice(1, "basic", CZK)
					.filter(Droppable::exists)
					.orElse(null)
			);
			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertNull(
				updatedPrices.getPrice(1, "basic", CZK)
					.filter(Droppable::exists)
					.orElse(null)
			);

			final Collection<PriceContract> prices =
				updatedPrices
					.getPrices()
					.stream()
					.filter(Droppable::exists)
					.toList();
			assertEquals(3, prices.size());
		}

		@Test
		@DisplayName(
			"should remove all untouched prices"
		)
		void shouldRemoveAllUntouchedPrices() {
			ExistingPriceBuilderTest.this.builder
				.setPrice(
					1, "basic", CZK,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, true
				)
				.setPrice(
					3, "basic", EUR,
					new BigDecimal("56"),
					new BigDecimal("21"),
					new BigDecimal("65.25"),
					true
				)
				.removeAllNonTouchedPrices();

			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertPrice(
				updatedPrices.getPrice(1, "basic", CZK),
				BigDecimal.ONE,
				BigDecimal.ZERO,
				BigDecimal.ONE,
				true
			);
			assertPrice(
				updatedPrices.getPrice(3, "basic", EUR),
				new BigDecimal("56"),
				new BigDecimal("21"),
				new BigDecimal("65.25"),
				true
			);

			final Collection<PriceContract> prices =
				updatedPrices
					.getPrices()
					.stream()
					.filter(Droppable::exists)
					.toList();
			assertEquals(2, prices.size());
		}

		@Test
		@DisplayName(
			"should handle remove and re-add price"
		)
		void shouldHandleRemoveAndReAddPrice() {
			ExistingPriceBuilderTest.this.builder
				.removePrice(1, "basic", CZK)
				.setPrice(
					1, "basic", CZK,
					new BigDecimal("99"),
					BigDecimal.ZERO,
					new BigDecimal("99"),
					true
				);

			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertPrice(
				updatedPrices.getPrice(1, "basic", CZK),
				new BigDecimal("99"),
				BigDecimal.ZERO,
				new BigDecimal("99"),
				true
			);
		}
	}

	@Nested
	@DisplayName("Price inner record handling")
	class PriceInnerRecordHandlingTest {

		@Test
		@DisplayName(
			"should remove price inner record handling"
		)
		void shouldRemovePriceInnerRecordHandling() {
			ExistingPriceBuilderTest.this.builder
				.removePriceInnerRecordHandling();
			assertEquals(
				PriceInnerRecordHandling.NONE,
				ExistingPriceBuilderTest.this.builder
					.getPriceInnerRecordHandling()
			);

			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertEquals(
				PriceInnerRecordHandling.NONE,
				updatedPrices
					.getPriceInnerRecordHandling()
			);
		}

		@Test
		@DisplayName(
			"should override price inner record handling"
		)
		void shouldOverridePriceInnerRecordHandling() {
			ExistingPriceBuilderTest.this.builder
				.setPriceInnerRecordHandling(
					PriceInnerRecordHandling.SUM
				);
			assertEquals(
				PriceInnerRecordHandling.SUM,
				ExistingPriceBuilderTest.this.builder
					.getPriceInnerRecordHandling()
			);

			final PricesContract updatedPrices =
				ExistingPriceBuilderTest.this.builder
					.build();
			assertEquals(
				PriceInnerRecordHandling.SUM,
				updatedPrices
					.getPriceInnerRecordHandling()
			);
		}
	}

	@Nested
	@DisplayName("Ambiguous price validation")
	class AmbiguousPriceValidationTest {

		/**
		 * The conflict is decided on the state the change set leads to, so setting the price and building the prices
		 * container are lenient and only building the change set refuses it.
		 */
		@Test
		@DisplayName(
			"should refuse conflicting with existing when building the change set"
		)
		void shouldRefuseAddingConflictingPriceWithExistingPrice() {
			ExistingPriceBuilderTest.this.builder
				.setPrice(
					10, "basic", CZK,
					BigDecimal.ONE,
					BigDecimal.ZERO,
					BigDecimal.ONE, true
				);

			assertEquals(
				2,
				ExistingPriceBuilderTest.this.builder.build().getPrices(CZK, "basic").size()
			);
			assertThrows(
				AmbiguousPriceException.class,
				ExistingPriceBuilderTest.this.builder::buildChangeSet
			);
		}

		@Test
		@DisplayName(
			"should allow conflicting for different inner record id"
		)
		void shouldAllowAddingConflictingPriceForDifferentInnerRecordId() {
			final PricesContract prices =
				ExistingPriceBuilderTest.this.builder
					.setPrice(
						10, "basic", CZK, 10,
						BigDecimal.ONE,
						BigDecimal.ZERO,
						BigDecimal.ONE, true
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
					.anyMatch(it -> it.priceId() == 10)
			);
		}

		@Test
		@DisplayName(
			"should refuse conflicting with upserted when building the change set"
		)
		void shouldRefuseAddingConflictingPriceWithUpsertedPrice() {
			ExistingPriceBuilderTest.this.builder
				.setPrice(
					10, "vip", CZK,
					BigDecimal.ONE,
					BigDecimal.ZERO,
					BigDecimal.ONE,
					DateTimeRange.since(
						OffsetDateTime.of(
							2021, 1, 1, 0, 0,
							0, 0, ZoneOffset.UTC
						)
					),
					true
				)
				.setPrice(
					11, "vip", CZK,
					BigDecimal.ONE,
					BigDecimal.ZERO,
					BigDecimal.ONE,
					DateTimeRange.until(
						OffsetDateTime.of(
							2022, 1, 1, 0, 0,
							0, 0, ZoneOffset.UTC
						)
					),
					true
				);

			assertThrows(
				AmbiguousPriceException.class,
				ExistingPriceBuilderTest.this.builder::buildChangeSet
			);
		}

		@Test
		@DisplayName(
			"should allow conflicting upserted for different inner record id"
		)
		void shouldAllowAddingConflictingPriceWithUpsertedPriceForDifferentInnerRecordId() {
			final PricesContract prices =
				ExistingPriceBuilderTest.this.builder
					.setPrice(
						10, "vip", CZK, 10,
						BigDecimal.ONE,
						BigDecimal.ZERO,
						BigDecimal.ONE,
						DateTimeRange.since(
							OffsetDateTime.of(
								2021, 1, 1, 0, 0,
								0, 0, ZoneOffset.UTC
							)
						),
						true
					)
					.setPrice(
						11, "vip", CZK, 11,
						BigDecimal.ONE,
						BigDecimal.ZERO,
						BigDecimal.ONE,
						DateTimeRange.until(
							OffsetDateTime.of(
								2022, 1, 1, 0, 0,
								0, 0, ZoneOffset.UTC
							)
						),
						true
					)
					.build();

			final Collection<PriceContract> basicPrices =
				prices.getPrices(CZK, "vip");
			assertEquals(2, basicPrices.size());
			assertTrue(
				basicPrices.stream()
					.anyMatch(it -> it.priceId() == 10)
			);
			assertTrue(
				basicPrices.stream()
					.anyMatch(it -> it.priceId() == 11)
			);
		}

		@Test
		@DisplayName(
			"should not refuse after removing conflicting"
		)
		void shouldNotRefuseAddingConflictingPriceWithAlreadyRemovedPrice() {
			final PricesContract prices =
				ExistingPriceBuilderTest.this.builder
					.removePrice(1, "basic", CZK)
					.setPrice(
						10, "basic", CZK,
						BigDecimal.ONE,
						BigDecimal.ZERO,
						BigDecimal.ONE,
						true
					)
					.build();
			assertNotNull(prices);
			final Collection<PriceContract> basicPrices =
				prices
					.getPrices(CZK, "basic")
					.stream()
					.filter(Droppable::exists)
					.toList();
			assertEquals(1, basicPrices.size());
			assertEquals(
				10,
				basicPrices.iterator().next().priceId()
			);
		}

		@Test
		@DisplayName("should reject explicit zero inner record id")
		void shouldRejectExplicitZeroInnerRecordId() {
			// `0` is reserved as the null sentinel inside per-inner-record bookkeeping; allowing it would
			// silently merge the price into the `null` bucket and corrupt LOWEST_PRICE/SUM aggregations
			assertThrows(
				InvalidMutationException.class,
				() -> ExistingPriceBuilderTest.this.builder.setPrice(
					20, "basic", CZK, 0,
					BigDecimal.ONE,
					BigDecimal.ZERO,
					BigDecimal.ONE,
					true
				)
			);
		}
	}

	/**
	 * The builder decides the ambiguity on the state its change set leads to, by the rule the server applies: only
	 * pairs with a price the change set adds or moves to another validity or inner record are checked, and the group of
	 * prices that compete is one price list, currency and inner record - one price list and currency for an entity
	 * without inner record handling.
	 */
	@Nested
	@DisplayName("Ambiguity of the state the change set leads to")
	class EndStateAmbiguityTest {

		/**
		 * Returns the validity spanning the passed year.
		 *
		 * @param year the year
		 * @return the validity
		 */
		@Nonnull
		private static DateTimeRange wholeYear(int year) {
			return DateTimeRange.between(
				OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
				OffsetDateTime.of(year, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
			);
		}

		/**
		 * Creates an indexed price in CZK selling for one without tax.
		 *
		 * @param priceId       id of the price
		 * @param priceList     price list of the price
		 * @param innerRecordId inner record of the price
		 * @param validity      validity of the price, `null` for a price valid at any moment
		 * @return the price
		 */
		@Nonnull
		private static PriceContract price(
			int priceId,
			@Nonnull String priceList,
			int innerRecordId,
			@Nullable DateTimeRange validity
		) {
			return new Price(
				priceId, priceList, CZK, innerRecordId,
				BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true
			);
		}

		/**
		 * Creates a builder over stored prices with the passed handling, which may hold prices the builders would
		 * refuse today.
		 *
		 * @param handling inner record handling of the stored prices
		 * @param prices   the stored prices
		 * @return the builder
		 */
		@Nonnull
		private static ExistingPricesBuilder builderOver(
			@Nonnull PriceInnerRecordHandling handling,
			@Nonnull PriceContract... prices
		) {
			return new ExistingPricesBuilder(
				PRODUCT_SCHEMA, new Prices(PRODUCT_SCHEMA, 1, List.of(prices), handling, true)
			);
		}

		/**
		 * Creates a builder whose stored prices contain only the entries fetched by the requested content mode.
		 *
		 * @param mode price content mode of the fetched entity
		 * @return the builder over the fetched prices
		 */
		@Nonnull
		private static ExistingPricesBuilder builderWithFetchedPrices(@Nonnull PriceContentMode mode) {
			final PriceContract first = price(1, "basic", 1, wholeYear(2020));
			final PriceContract second = price(2, "basic", 1, null);
			final OffsetDateTime validIn = OffsetDateTime.of(2021, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC);
			final EvitaRequest request = new EvitaRequest(
				query(
					collection("product"),
					filterBy(priceInCurrency(CZK), priceInPriceLists("basic"), priceValidIn(validIn)),
					require(entityFetch(priceContent(mode)))
				),
				validIn, SealedEntity.class, null
			);
			return new ExistingPricesBuilder(
				PRODUCT_SCHEMA,
				new Prices(
					PRODUCT_SCHEMA, 1, mode == PriceContentMode.ALL ? List.of(first, second) : List.of(second),
					PriceInnerRecordHandling.LOWEST_PRICE, true
				),
				new PriceContractSerializablePredicate(request, (Boolean) null)
			);
		}

		@ParameterizedTest
		@EnumSource(value = PriceContentMode.class, names = {"ALL", "RESPECTING_FILTER"})
		@DisplayName("should accept an amount change of a stored price with complete or partial fetched prices")
		void shouldAcceptAmountChangeOfStoredPriceWithCompleteOrPartialFetchedPrices(@Nonnull PriceContentMode mode) {
			final ExistingPricesBuilder builder = builderWithFetchedPrices(mode);
			builder.setPrice(
				1, "basic", CZK, 1, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, wholeYear(2020), true
			);

			assertEquals(1, builder.buildChangeSet().count());
		}

		@ParameterizedTest
		@EnumSource(value = PriceContentMode.class, names = {"ALL", "RESPECTING_FILTER"})
		@DisplayName("should verify a new conflicting price only with complete fetched prices")
		void shouldVerifyNewConflictingPriceOnlyWithCompleteFetchedPrices(@Nonnull PriceContentMode mode) {
			final ExistingPricesBuilder builder = builderWithFetchedPrices(mode);
			builder.setPrice(
				3, "basic", CZK, 1, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
			);

			if (mode == PriceContentMode.ALL) {
				assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
			} else {
				assertEquals(1, builder.buildChangeSet().count());
			}
		}

		@Test
		@DisplayName("should accept an amount change of a stored ambiguous pair of one inner record")
		void shouldAcceptAmountChangeOfStoredAmbiguousPairOfOneInnerRecord() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.LOWEST_PRICE,
				price(1, "basic", 1, null),
				price(2, "basic", 1, wholeYear(2020))
			);
			builder.setPrice(
				1, "basic", CZK, 1,
				BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
			);

			assertEquals(1, builder.buildChangeSet().count());
		}

		@Test
		@DisplayName("should accept a price added where the same builder moved a stored price away from")
		void shouldAcceptPriceAddedWhereSameBuilderMovedStoredPriceAwayFrom() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.LOWEST_PRICE, price(1, "basic", 1, wholeYear(2020))
			);
			builder
				.setPrice(
					1, "basic", CZK, 1,
					BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, wholeYear(2021), true
				)
				.setPrice(
					2, "basic", CZK, 1,
					BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, wholeYear(2020), true
				);

			assertEquals(2, builder.buildChangeSet().count());
		}

		@Test
		@DisplayName("should reject a price of one inner record valid at a moment a stored price is")
		void shouldRejectPriceOfOneInnerRecordOverlappingStoredPrice() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.LOWEST_PRICE, price(1, "basic", 1, wholeYear(2020))
			);
			builder.setPrice(
				2, "basic", CZK, 1,
				BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
			);

			assertNotNull(builder.build());
			assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
		}

		@Test
		@DisplayName("should reject a change between two inner record handlings that keeps stored prices overlapping")
		void shouldRejectHandlingChangeThatKeepsStoredPricesOfOneInnerRecordOverlapping() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.LOWEST_PRICE,
				price(1, "basic", 1, null),
				price(2, "basic", 1, wholeYear(2020))
			);
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);

			assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
		}

		@Test
		@DisplayName("should reject a price of another inner record valid at a moment a stored price is")
		void shouldRejectPriceOfAnotherInnerRecordOverlappingStoredPrice() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.NONE, price(1, "basic", 1, null)
			);
			builder.setPrice(
				10, "basic", CZK, 2,
				BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, wholeYear(2020), true
			);

			assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
		}

		@Test
		@DisplayName("should reject a handling change that makes the stored prices overlap")
		void shouldRejectHandlingChangeToNoneThatMakesStoredPricesOverlap() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.LOWEST_PRICE,
				price(1, "basic", 1, null),
				price(2, "basic", 2, null)
			);
			builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);

			assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
		}

		@Test
		@DisplayName("should reject moving a stored price to a validity that still overlaps another stored price")
		void shouldRejectValidityChangeThatKeepsStoredAmbiguousPairOverlapping() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.NONE,
				price(1, "basic", 1, null),
				price(2, "basic", 2, null)
			);
			builder.setPrice(
				1, "basic", CZK, 1,
				BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, wholeYear(2020), true
			);

			assertThrows(AmbiguousPriceException.class, builder::buildChangeSet);
		}

		@Test
		@DisplayName("should accept an amount change of a stored ambiguous pair and a price in another price list")
		void shouldAcceptAmountChangeOfStoredAmbiguousPairAndPriceInAnotherPriceList() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.NONE,
				price(1, "basic", 1, null),
				price(2, "basic", 2, null)
			);
			builder
				.setPrice(
					1, "basic", CZK, 1,
					BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
				)
				.setPrice(
					3, "reference", CZK, 1,
					BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
				);

			assertEquals(2, builder.buildChangeSet().count());
		}

		@Test
		@DisplayName("should accept a price of another inner record when the same builder leaves the handling")
		void shouldAcceptOverlappingPriceWhenHandlingChangesFromNoneInSameBuilder() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.NONE, price(1, "basic", 1, null)
			);
			builder
				.setPrice(
					10, "basic", CZK, 2,
					BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
				)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);

			assertEquals(2, builder.buildChangeSet().count());
		}

		@Test
		@DisplayName("should accept an overlapping price when the stored price it overlaps is removed")
		void shouldAcceptOverlappingPriceWhenStoredPriceItOverlapsIsRemoved() {
			final ExistingPricesBuilder builder = builderOver(
				PriceInnerRecordHandling.NONE, price(1, "basic", 1, null)
			);
			builder
				.removePrice(1, "basic", CZK)
				.setPrice(
					10, "basic", CZK, 2,
					BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, true
				);

			assertEquals(2, builder.buildChangeSet().count());
		}
	}

	@Nested
	@DisplayName("Querying prices")
	class QueryingPricesTest {

		@Test
		@DisplayName("should return price by key")
		void shouldReturnPriceByKey() {
			final Optional<PriceContract> price =
				ExistingPriceBuilderTest.this.builder
					.getPrice(1, "basic", CZK);

			assertTrue(price.isPresent());
			assertEquals(1, price.get().priceId());
			assertEquals("basic", price.get().priceList());
			assertSame(CZK, price.get().currency());
		}

		@Test
		@DisplayName(
			"should return prices by price list and currency"
		)
		void shouldReturnPricesByPriceListAndCurrency() {
			final Collection<PriceContract> prices =
				ExistingPriceBuilderTest.this.builder
					.getPrices(CZK, "basic");

			assertEquals(1, prices.size());
			final PriceContract price =
				prices.iterator().next();
			assertEquals(1, price.priceId());
		}

		@Test
		@DisplayName("should return all prices")
		void shouldReturnAllPrices() {
			final Collection<PriceContract> prices =
				ExistingPriceBuilderTest.this.builder
					.getPrices();

			assertEquals(4, prices.size());
		}
	}

	@Nested
	@DisplayName("Change set and identity")
	class ChangeSetAndIdentityTest {

		@Test
		@DisplayName(
			"should return original when nothing changed"
		)
		void shouldReturnOriginalPriceInstanceWhenNothingHasChanged() {
			ExistingPriceBuilderTest.this.builder
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
				);

			assertSame(
				ExistingPriceBuilderTest.this
					.initialPrices,
				ExistingPriceBuilderTest.this.builder
					.build()
			);
		}

		@Test
		@DisplayName(
			"should build empty change set when no changes"
		)
		void shouldBuildEmptyChangeSetWhenNoPriceChanges() {
			assertEquals(
				0,
				ExistingPriceBuilderTest.this.builder
					.buildChangeSet().count()
			);
		}

		@Test
		@DisplayName(
			"should build change set for mixed operations"
		)
		void shouldBuildChangeSetForMixedOperations() {
			ExistingPriceBuilderTest.this.builder
				.setPrice(
					1, "basic", CZK,
					BigDecimal.TEN, BigDecimal.ZERO,
					BigDecimal.TEN, true
				)
				.removePrice(2, "reference", CZK)
				.setPrice(
					5, "vip", EUR,
					BigDecimal.ONE, BigDecimal.ZERO,
					BigDecimal.ONE, true
				);

			assertEquals(
				3,
				ExistingPriceBuilderTest.this.builder
					.buildChangeSet().count()
			);
		}
	}

}
