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

import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Prices} verifying construction, price
 * retrieval by various keys, differsFrom comparison,
 * and schema enforcement.
 *
 * @author evitaDB
 */
@DisplayName("Prices")
class PricesTest extends AbstractBuilderTest {
	private static final Currency CZK =
		Currency.getInstance("CZK");
	private static final Currency EUR =
		Currency.getInstance("EUR");

	/**
	 * Builds an entity schema that allows prices.
	 *
	 * @return entity schema with price support
	 */
	@Nonnull
	private static EntitySchemaContract schemaWithPrices() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withPrice()
			.toInstance();
	}

	/**
	 * Builds an entity schema that does NOT allow prices.
	 *
	 * @return entity schema without price support
	 */
	@Nonnull
	private static EntitySchemaContract schemaWithoutPrices() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.verifySchemaStrictly()
			.toInstance();
	}

	/**
	 * Creates a single {@link Price} instance for testing.
	 *
	 * @param priceId price identifier
	 * @param priceList price list name
	 * @param currency currency
	 * @param amount price without tax
	 * @return new price instance
	 */
	@Nonnull
	private static Price createPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal amount
	) {
		return new Price(
			priceId, priceList, currency,
			null, amount, BigDecimal.ZERO, amount,
			null, true
		);
	}

	/**
	 * Creates a populated {@link Prices} container with
	 * three prices for testing.
	 *
	 * @return pre-populated prices container
	 */
	@Nonnull
	private static Prices createPopulated() {
		final EntitySchemaContract schema =
			schemaWithPrices();
		final Price p1 = createPrice(
			1, "basic", CZK, BigDecimal.TEN
		);
		final Price p2 = createPrice(
			2, "basic", EUR, BigDecimal.ONE
		);
		final Price p3 = createPrice(
			3, "vip", CZK, new BigDecimal("5")
		);
		return new Prices(
			schema, List.of(p1, p2, p3),
			PriceInnerRecordHandling.NONE
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create empty prices from schema"
		)
		void shouldCreateEmptyPrices() {
			final EntitySchemaContract schema =
				schemaWithPrices();

			final Prices prices = new Prices(
				schema,
				PriceInnerRecordHandling.NONE
			);

			assertTrue(prices.isEmpty());
			assertEquals(1, prices.version());
			assertEquals(
				PriceInnerRecordHandling.NONE,
				prices.getPriceInnerRecordHandling()
			);
		}

		@Test
		@DisplayName(
			"should store prices from collection"
		)
		void shouldStorePrices() {
			final Prices prices = createPopulated();

			assertFalse(prices.isEmpty());
			assertEquals(3, prices.getPrices().size());
		}

		@Test
		@DisplayName(
			"should respect explicit version"
		)
		void shouldRespectVersion() {
			final EntitySchemaContract schema =
				schemaWithPrices();

			final Prices prices = new Prices(
				schema, 42,
				Collections.emptyList(),
				PriceInnerRecordHandling.NONE
			);

			assertEquals(42, prices.version());
		}
	}

	@Nested
	@DisplayName("Price retrieval")
	class RetrievalTest {

		@Test
		@DisplayName(
			"should return price by PriceKey"
		)
		void shouldReturnByPriceKey() {
			final Prices prices = createPopulated();
			final PriceKey key =
				new PriceKey(1, "basic", CZK);

			final Optional<PriceContract> result =
				prices.getPrice(key);

			assertTrue(result.isPresent());
			assertEquals(1, result.get().priceId());
		}

		@Test
		@DisplayName(
			"should return price by id, list, currency"
		)
		void shouldReturnByIdListCurrency() {
			final Prices prices = createPopulated();

			final Optional<PriceContract> result =
				prices.getPrice(2, "basic", EUR);

			assertTrue(result.isPresent());
			assertEquals(2, result.get().priceId());
		}

		@Test
		@DisplayName(
			"should return single matching price by "
				+ "list and currency"
		)
		void shouldReturnByListAndCurrency() {
			final Prices prices = createPopulated();

			final Optional<PriceContract> result =
				prices.getPrice("vip", CZK);

			assertTrue(result.isPresent());
			assertEquals(3, result.get().priceId());
		}

		@Test
		@DisplayName(
			"should throw when multiple prices match "
				+ "list and currency"
		)
		void shouldThrowForMultipleMatches() {
			final EntitySchemaContract schema =
				schemaWithPrices();
			final Price p1 = createPrice(
				1, "basic", CZK, BigDecimal.TEN
			);
			final Price p2 = createPrice(
				2, "basic", CZK, BigDecimal.ONE
			);
			final Prices prices = new Prices(
				schema, 1,
				List.of(p1, p2),
				PriceInnerRecordHandling.NONE
			);

			assertThrows(
				UnexpectedResultCountException.class,
				() -> prices.getPrice("basic", CZK)
			);
		}

		@Test
		@DisplayName(
			"should return empty for non-existent key"
		)
		void shouldReturnEmptyForMissing() {
			final Prices prices = createPopulated();

			final Optional<PriceContract> result =
				prices.getPrice(999, "basic", CZK);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName(
			"should return all prices"
		)
		void shouldReturnAllPrices() {
			final Prices prices = createPopulated();

			final Collection<PriceContract> all =
				prices.getPrices();

			assertEquals(3, all.size());
		}

		@Test
		@DisplayName(
			"should return price index map"
		)
		void shouldReturnPriceIndex() {
			final Prices prices = createPopulated();

			final Map<PriceKey, PriceContract> index =
				prices.getPriceIndex();

			assertEquals(3, index.size());
		}

		@Test
		@DisplayName(
			"should return price without schema check"
		)
		void shouldReturnWithoutSchemaCheck() {
			final Prices prices = createPopulated();
			final PriceKey key =
				new PriceKey(1, "basic", CZK);

			final Optional<PriceContract> result =
				prices.getPriceWithoutSchemaCheck(key);

			assertTrue(result.isPresent());
		}
	}

	@Nested
	@DisplayName("differsFrom")
	class DiffersFromTest {

		@Test
		@DisplayName("should return true when null")
		void shouldReturnTrueForNull() {
			final Prices prices = createPopulated();

			assertTrue(prices.differsFrom(null));
		}

		@Test
		@DisplayName(
			"should return false for same instance"
		)
		void shouldReturnFalseForSame() {
			final Prices prices = createPopulated();

			assertFalse(prices.differsFrom(prices));
		}

		@Test
		@DisplayName(
			"should return false for equal prices"
		)
		void shouldReturnFalseForEqual() {
			final Prices prices1 = createPopulated();
			final Prices prices2 = createPopulated();

			assertFalse(prices1.differsFrom(prices2));
		}

		@Test
		@DisplayName(
			"should return true for different version"
		)
		void shouldReturnTrueForDifferentVersion() {
			final EntitySchemaContract schema =
				schemaWithPrices();
			final Prices v1 = new Prices(
				schema, 1, Collections.emptyList(),
				PriceInnerRecordHandling.NONE
			);
			final Prices v2 = new Prices(
				schema, 2, Collections.emptyList(),
				PriceInnerRecordHandling.NONE
			);

			assertTrue(v1.differsFrom(v2));
		}

		@Test
		@DisplayName(
			"should return true when other prices have "
				+ "withPrice=false but same size"
		)
		void shouldReturnTrueWhenOtherHasNoPriceSupport() {
			final EntitySchemaContract schema =
				schemaWithPrices();
			final Price p1 = createPrice(
				1, "basic", CZK, BigDecimal.TEN
			);
			final Prices pricesWithSupport = new Prices(
				schema, List.of(p1),
				PriceInnerRecordHandling.NONE
			);
			final EntitySchemaContract noSchema =
				schemaWithoutPrices();
			final Price p2 = createPrice(
				1, "basic", CZK, BigDecimal.ONE
			);
			final Prices pricesWithout = new Prices(
				noSchema, 1,
				List.of(p2),
				PriceInnerRecordHandling.NONE,
				false
			);

			assertTrue(
				pricesWithSupport.differsFrom(pricesWithout)
			);
		}
	}

	@Nested
	@DisplayName("Schema enforcement")
	class SchemaEnforcementTest {

		@Test
		@DisplayName(
			"should throw when prices not allowed"
		)
		void shouldThrowWhenNoPricesAllowed() {
			final EntitySchemaContract schema =
				schemaWithoutPrices();
			final Prices prices = new Prices(
				schema, 1,
				Collections.emptyList(),
				PriceInnerRecordHandling.NONE,
				false
			);

			assertThrows(
				EntityHasNoPricesException.class,
				() -> prices.getPrices()
			);
		}

		@Test
		@DisplayName(
			"should throw for getPrice when no prices"
		)
		void shouldThrowForGetPriceWhenNoPrices() {
			final EntitySchemaContract schema =
				schemaWithoutPrices();
			final Prices prices = new Prices(
				schema, 1,
				Collections.emptyList(),
				PriceInnerRecordHandling.NONE,
				false
			);
			final PriceKey key =
				new PriceKey(1, "basic", CZK);

			assertThrows(
				EntityHasNoPricesException.class,
				() -> prices.getPrice(key)
			);
		}
	}

	@Nested
	@DisplayName("State and toString")
	class StateTest {

		@Test
		@DisplayName(
			"should return true for isEmpty on empty"
		)
		void shouldReturnTrueForIsEmpty() {
			final EntitySchemaContract schema =
				schemaWithPrices();
			final Prices prices = new Prices(
				schema,
				PriceInnerRecordHandling.NONE
			);

			assertTrue(prices.isEmpty());
		}

		@Test
		@DisplayName(
			"should return readable toString"
		)
		void shouldReturnReadableToString() {
			final Prices prices = createPopulated();

			final String result = prices.toString();

			assertNotNull(result);
			assertTrue(result.contains("NONE"));
		}

		@Test
		@DisplayName(
			"should return no-price toString for "
				+ "schema without prices"
		)
		void shouldReturnNoPriceToString() {
			final EntitySchemaContract schema =
				schemaWithoutPrices();
			final Prices prices = new Prices(
				schema, 1,
				Collections.emptyList(),
				PriceInnerRecordHandling.NONE,
				false
			);

			final String result = prices.toString();

			assertEquals(
				"entity has no prices", result
			);
		}
	}
}
