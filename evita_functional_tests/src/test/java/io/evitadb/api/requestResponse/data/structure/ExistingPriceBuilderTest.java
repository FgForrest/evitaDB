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
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;

import static io.evitadb.api.requestResponse.data.structure.InitialPricesBuilderTest.assertPrice;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ExistingEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ExistingPriceBuilderTest extends AbstractBuilderTest {
	public static final Currency CZK = Currency.getInstance("CZK");
	public static final Currency EUR = Currency.getInstance("EUR");
	private Prices initialPrices;
	private ExistingPricesBuilder builder;

	@BeforeEach
	void setUp() {
		this.initialPrices = new InitialPricesBuilder(PRODUCT_SCHEMA)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
			.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
			.setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
			.build();
		this.builder = new ExistingPricesBuilder(PRODUCT_SCHEMA, this.initialPrices);
	}

	@Test
	void shouldRemovePriceInnerRecordHandling() {
		this.builder.removePriceInnerRecordHandling();
		assertEquals(PriceInnerRecordHandling.NONE, this.builder.getPriceInnerRecordHandling());

		final PricesContract updatedPrices = this.builder.build();
		assertEquals(PriceInnerRecordHandling.NONE, updatedPrices.getPriceInnerRecordHandling());
	}

	@Test
	void shouldSkipMutationsThatMeansNoChange() {
		this.builder
			.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(2, "reference", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);

		assertEquals(0, this.builder.buildChangeSet().count());
	}

	@Test
	void shouldAddNewPrice() {
		this.builder.setPrice(
			5, "discount", CZK, new BigDecimal("56"), new BigDecimal("21"), new BigDecimal("65.25"), true);
		assertPrice(
			this.builder.getPrice(5, "discount", CZK), new BigDecimal("56"), new BigDecimal("21"),
			new BigDecimal("65.25"), true
		);
		final PricesContract updatedPrices = this.builder.build();
		assertPrice(
			updatedPrices.getPrice(5, "discount", CZK), new BigDecimal("56"), new BigDecimal("21"),
			new BigDecimal("65.25"), true
		);

		final Collection<PriceContract> prices = updatedPrices.getPrices();
		assertEquals(5, prices.size());
	}

	@Test
	void shouldOverWriteExistingPrice() {
		this.builder.setPrice(
			1, "basic", CZK, new BigDecimal("56"), new BigDecimal("21"), new BigDecimal("65.25"), true);
		assertPrice(
			this.builder.getPrice(1, "basic", CZK), new BigDecimal("56"), new BigDecimal("21"), new BigDecimal("65.25"),
			true
		);
		final PricesContract updatedPrices = this.builder.build();
		assertPrice(
			updatedPrices.getPrice(1, "basic", CZK), new BigDecimal("56"), new BigDecimal("21"),
			new BigDecimal("65.25"), true
		);

		final Collection<PriceContract> prices = updatedPrices.getPrices();
		assertEquals(4, prices.size());
	}

	@Test
	void shouldRemoveExistingPrice() {
		this.builder.removePrice(1, "basic", CZK);
		assertNull(this.builder.getPrice(1, "basic", CZK).filter(Droppable::exists).orElse(null));
		final PricesContract updatedPrices = this.builder.build();
		assertNull(updatedPrices.getPrice(1, "basic", CZK).filter(Droppable::exists).orElse(null));

		final Collection<PriceContract> prices = updatedPrices
			.getPrices()
			.stream()
			.filter(Droppable::exists)
			.toList();
		;
		assertEquals(3, prices.size());
	}

	@Test
	void shouldRemoveAllUntouchedPrices() {
		this.builder.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
		            .setPrice(3, "basic", EUR, new BigDecimal("56"), new BigDecimal("21"), new BigDecimal("65.25"), true)
		            .removeAllNonTouchedPrices();

		final PricesContract updatedPrices = this.builder.build();
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

		final Collection<PriceContract> prices = updatedPrices
			.getPrices()
			.stream()
			.filter(Droppable::exists)
			.toList();
		;
		assertEquals(2, prices.size());
	}

	@Test
	void shouldReturnOriginalPriceInstanceWhenNothingHasChanged() {
		this.builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
		            .setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
		            .setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
		            .setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
		            .setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);

		assertSame(this.initialPrices, this.builder.build());
	}

	@Test
	void shouldRefuseAddingConflictingPriceWithExistingPrice() {
		assertThrows(
			AmbiguousPriceException.class, () -> {
				final PricesContract prices = this.builder
					.setPrice(10, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.build();
			}
		);
	}

	@Test
	void shouldAllowAddingConflictingPriceForDifferentInnerRecordId() {
		final PricesContract prices = this.builder
			.setPrice(10, "basic", CZK, 10, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.build();

		final Collection<PriceContract> basicPrices = prices.getPrices(CZK, "basic");
		assertEquals(2, basicPrices.size());
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 1));
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 10));
	}

	@Test
	void shouldRefuseAddingConflictingPriceWithUpsertedPrice() {
		assertThrows(
			AmbiguousPriceException.class, () -> {
				final PricesContract prices = this.builder
					.setPrice(
						10, "vip", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
						DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), true
					)
					.setPrice(
						11, "vip", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
						DateTimeRange.until(OffsetDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), true
					)
					.build();
			}
		);
	}

	@Test
	void shouldAllowAddingConflictingPriceWithUpsertedPriceForDifferentInnerRecordId() {
		final PricesContract prices = this.builder
			.setPrice(
				10, "vip", CZK, 10, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
				DateTimeRange.since(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), true
			)
			.setPrice(
				11, "vip", CZK, 11, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
				DateTimeRange.until(OffsetDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), true
			)
			.build();

		final Collection<PriceContract> basicPrices = prices.getPrices(CZK, "vip");
		assertEquals(2, basicPrices.size());
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 10));
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 11));
	}

	@Test
	void shouldNotRefuseAddingConflictingPriceWithAlreadyRemovedPrice() {
		final PricesContract prices = this.builder
			.removePrice(1, "basic", CZK)
			.setPrice(10, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.build();
		assertNotNull(prices);
		final Collection<PriceContract> basicPrices = prices
			.getPrices(CZK, "basic")
			.stream()
			.filter(Droppable::exists)
			.toList();
		assertEquals(1, basicPrices.size());
		assertEquals(10, basicPrices.iterator().next().priceId());
	}

}
