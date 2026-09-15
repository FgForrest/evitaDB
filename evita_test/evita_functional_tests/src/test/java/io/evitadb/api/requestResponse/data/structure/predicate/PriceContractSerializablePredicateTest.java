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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceContractSerializablePredicate} verifying
 * price filtering, fetch status, context availability, exception
 * throwing, and richer copy creation logic.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Price contract predicate")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(PRICE)
class PriceContractSerializablePredicateTest {

	@Nested
	@DisplayName("Fetch status and context checks")
	class FetchStatusTest {

		@Test
		@DisplayName("isFetched returns false when mode is NONE")
		void shouldReturnFalseWhenModeIsNone() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertFalse(predicate.isFetched());
		}

		@Test
		@DisplayName(
			"isFetched returns true when mode is RESPECTING_FILTER"
		)
		void shouldReturnTrueWhenModeIsRespectingFilter() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertTrue(predicate.isFetched());
		}

		@Test
		@DisplayName("isFetched returns true when mode is ALL")
		void shouldReturnTrueWhenModeIsAll() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertTrue(predicate.isFetched());
		}

		@Test
		@DisplayName(
			"checkPricesFetched throws when mode is NONE"
		)
		void shouldThrowOnCheckPricesFetchedWhenNone() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertThrows(
				ContextMissingException.class,
				predicate::checkPricesFetched
			);
		}

		@Test
		@DisplayName(
			"checkPricesFetched does not throw when mode is ALL"
		)
		void shouldNotThrowOnCheckPricesFetchedWhenAll() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			predicate.checkPricesFetched();
		}

		@Test
		@DisplayName(
			"isContextAvailable returns false when not available"
		)
		void shouldReturnFalseWhenContextNotAvailable() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertFalse(predicate.isContextAvailable());
		}

		@Test
		@DisplayName(
			"isContextAvailable returns true when set"
		)
		void shouldReturnTrueWhenContextAvailable() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL,
					Currency.getInstance("CZK"), null,
					new String[]{"basic"}, null, null,
					Set.of("basic"),
					QueryPriceMode.WITH_TAX, true
				);

			assertTrue(predicate.isContextAvailable());
		}

		@Test
		@DisplayName(
			"getPriceForSaleContext returns empty when not available"
		)
		void shouldReturnEmptyContextWhenNotAvailable() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertEquals(
				Optional.empty(),
				predicate.getPriceForSaleContext()
			);
		}

		@Test
		@DisplayName(
			"getPriceForSaleContext returns context when available"
		)
		void shouldReturnContextWhenAvailable() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER,
					Currency.getInstance("CZK"), null,
					new String[]{"basic"}, null, null,
					Set.of("basic"),
					QueryPriceMode.WITH_TAX, true
				);

			assertTrue(predicate.getPriceForSaleContext().isPresent());
		}
	}

	@Nested
	@DisplayName("Check fetched with currency and price list")
	class CheckFetchedTest {

		@Test
		@DisplayName("throws when mode is NONE")
		void shouldThrowWhenModeIsNone() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(null, "basic")
			);
		}

		@Test
		@DisplayName(
			"throws when currency does not match in "
				+ "RESPECTING_FILTER mode"
		)
		void shouldThrowWhenCurrencyDoesNotMatch() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER,
					Currency.getInstance("CZK"), null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(
					Currency.getInstance("USD"), "basic"
				)
			);
		}

		@Test
		@DisplayName(
			"throws when price list not in set in "
				+ "RESPECTING_FILTER mode"
		)
		void shouldThrowWhenPriceListNotInSet() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, null, Set.of("basic"),
					QueryPriceMode.WITH_TAX, false
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(null, "premium")
			);
		}

		@Test
		@DisplayName("does not throw in ALL mode")
		void shouldNotThrowInAllMode() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			predicate.checkFetched(
				Currency.getInstance("CZK"), "basic"
			);
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName("returns false when mode is NONE")
		void shouldReturnFalseWhenModeIsNone() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContract price =
				Mockito.mock(PriceContract.class);
			Mockito.when(price.exists()).thenReturn(true);

			assertFalse(predicate.test(price));
		}

		@Test
		@DisplayName(
			"returns true for existing price when mode is ALL"
		)
		void shouldReturnTrueForExistingPriceInAllMode() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContract price =
				Mockito.mock(PriceContract.class);
			Mockito.when(price.exists()).thenReturn(true);

			assertTrue(predicate.test(price));
		}

		@Test
		@DisplayName(
			"returns false for dropped price when mode is ALL"
		)
		void shouldReturnFalseForDroppedPriceInAllMode() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContract price =
				Mockito.mock(PriceContract.class);
			Mockito.when(price.exists()).thenReturn(false);

			assertFalse(predicate.test(price));
		}

		@Test
		@DisplayName(
			"filters by currency in RESPECTING_FILTER mode"
		)
		void shouldFilterByCurrencyInRespectingFilterMode() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER,
					Currency.getInstance("CZK"), null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContract matchingPrice =
				Mockito.mock(PriceContract.class);
			Mockito.when(matchingPrice.exists()).thenReturn(true);
			Mockito.when(matchingPrice.currency())
				.thenReturn(Currency.getInstance("CZK"));
			Mockito.when(matchingPrice.priceList())
				.thenReturn("basic");
			Mockito.when(matchingPrice.validity())
				.thenReturn(null);

			final PriceContract nonMatchingPrice =
				Mockito.mock(PriceContract.class);
			Mockito.when(nonMatchingPrice.exists()).thenReturn(true);
			Mockito.when(nonMatchingPrice.currency())
				.thenReturn(Currency.getInstance("USD"));

			assertTrue(predicate.test(matchingPrice));
			assertFalse(predicate.test(nonMatchingPrice));
		}

		@Test
		@DisplayName(
			"filters by price list in RESPECTING_FILTER mode"
		)
		void shouldFilterByPriceListInRespectingFilterMode() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER,
					null, null, new String[]{"basic"},
					null, null, Set.of("basic"),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContract matchingPrice =
				Mockito.mock(PriceContract.class);
			Mockito.when(matchingPrice.exists()).thenReturn(true);
			Mockito.when(matchingPrice.priceList())
				.thenReturn("basic");
			Mockito.when(matchingPrice.validity())
				.thenReturn(null);

			final PriceContract nonMatchingPrice =
				Mockito.mock(PriceContract.class);
			Mockito.when(nonMatchingPrice.exists()).thenReturn(true);
			Mockito.when(nonMatchingPrice.priceList())
				.thenReturn("premium");

			assertTrue(predicate.test(matchingPrice));
			assertFalse(predicate.test(nonMatchingPrice));
		}
	}

	@Nested
	@DisplayName("Richer copy creation")
	class RicherCopyTest {

		@Test
		@DisplayName(
			"creates richer copy when upgrading from NONE "
				+ "to RESPECTING_FILTER"
		)
		void shouldCreateRicherCopyForNoPrices() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"preserves additional price lists from source"
		)
		void shouldCreateRicherCopyWithAdditionalPriceListsFromSource() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					new String[]{"A", "B"}, null,
					Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertArrayEquals(
				new String[]{"A", "B"},
				richerCopy.getAdditionalPriceLists()
			);
		}

		@Test
		@DisplayName(
			"takes additional price lists from request"
		)
		void shouldCreateRicherCopyWithAdditionalPriceListsFromRequest() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(new String[]{"A", "B"});
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertArrayEquals(
				new String[]{"A", "B"},
				richerCopy.getAdditionalPriceLists()
			);
		}

		@Test
		@DisplayName(
			"merges additional price lists from both sources"
		)
		void shouldMergeAdditionalPriceListsFromBoth() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					new String[]{"A", "B"}, null,
					new HashSet<>(Arrays.asList("A", "B")),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(new String[]{"A", "D"});
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertArrayEquals(
				new String[]{"A", "B", "A", "D"},
				richerCopy.getAdditionalPriceLists()
			);
			assertEquals(
				new HashSet<>(Arrays.asList("A", "B", "D")),
				richerCopy.getPriceListsAsSet()
			);
		}

		@Test
		@DisplayName(
			"merges price lists and additional price lists"
		)
		void shouldMergePriceListsAndAdditionalPriceLists() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null,
					new String[]{"X", "Z"},
					new String[]{"A", "B"}, null,
					new HashSet<>(
						Arrays.asList("A", "B", "X", "Z")
					),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(null);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(new String[]{"A", "D"});
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertArrayEquals(
				new String[]{"X", "Z"},
				richerCopy.getPriceLists()
			);
			assertArrayEquals(
				new String[]{"A", "B", "A", "D"},
				richerCopy.getAdditionalPriceLists()
			);
			assertEquals(
				new HashSet<>(
					Arrays.asList("A", "B", "D", "X", "Z")
				),
				richerCopy.getPriceListsAsSet()
			);
		}

		@Test
		@DisplayName(
			"returns same instance when mode is NONE and request "
				+ "is also NONE"
		)
		void shouldNotCreateRicherCopyForNoPrices() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.NONE);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when already RESPECTING_FILTER and "
				+ "request is NONE"
		)
		void shouldNotCreateRicherCopyWhenAlreadyFetched() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.NONE);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy upgrading from RESPECTING_FILTER "
				+ "to ALL"
		)
		void shouldCreateRicherCopyUpgradingToAll() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.ALL);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same for NONE mode and NONE request"
		)
		void shouldNotCreateRicherCopyForRespectingFilter() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.NONE, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.NONE);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same for ALL mode even with additional "
				+ "constraints"
		)
		void shouldNotCreateRicherCopyForAllPrices() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, null,
					null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(new String[]{"A"});
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(Currency.getInstance("CZK"));
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(OffsetDateTime.now());
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy with new accompanying prices"
		)
		void shouldCreateRicherCopyWithAccompanyingPrices() {
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, null, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final AccompanyingPrice[] accompanyingPrices =
				new AccompanyingPrice[]{
					new AccompanyingPrice("price1", "A", "B"),
					new AccompanyingPrice("price2", "C", "D")
				};

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(accompanyingPrices);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertArrayEquals(
				accompanyingPrices,
				richerCopy.getAccompanyingPrices()
			);
		}

		@Test
		@DisplayName(
			"merges accompanying prices from both sources"
		)
		void shouldMergeAccompanyingPrices() {
			final AccompanyingPrice[] original =
				new AccompanyingPrice[]{
					new AccompanyingPrice("price1", "A", "B")
				};

			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					null, null, original, Collections.emptySet(),
					QueryPriceMode.WITH_TAX, false
				);

			final AccompanyingPrice[] newPrices =
				new AccompanyingPrice[]{
					new AccompanyingPrice("price2", "C", "D"),
					new AccompanyingPrice("price3", "E", "F")
				};

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(newPrices);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			final AccompanyingPrice[] expected =
				ArrayUtils.mergeArrays(original, newPrices);
			assertArrayEquals(
				expected, richerCopy.getAccompanyingPrices()
			);
		}

		@Test
		@DisplayName(
			"collects price lists from accompanying prices"
		)
		void shouldCollectPriceListsFromAccompanyingPrices() {
			final AccompanyingPrice[] accompanyingPrices =
				new AccompanyingPrice[]{
					new AccompanyingPrice("price1", "A", "B"),
					new AccompanyingPrice("price2", "C", "D")
				};

			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.RESPECTING_FILTER, null, null,
					new String[]{"X"}, new String[]{"Y"}, null,
					new HashSet<>(Arrays.asList("X", "Y")),
					QueryPriceMode.WITH_TAX, false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(new String[]{"Z"});
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(accompanyingPrices);
			Mockito.when(evitaRequest.getRequiresCurrency())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices())
				.thenReturn(PriceContentMode.RESPECTING_FILTER);

			final PriceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			final Set<String> expectedPriceLists = new HashSet<>(
				Arrays.asList(
					"X", "Y", "Z", "A", "B", "C", "D"
				)
			);
			assertEquals(
				expectedPriceLists,
				richerCopy.getPriceListsAsSet()
			);
		}

		/**
		 * Builds a request asking for the passed content mode, additional price lists and accompanying prices, and
		 * nothing else - the three inputs the enrichment decision is made from.
		 *
		 * @param requiresEntityPrices       price content mode the request asks for
		 * @param fetchesAdditionalPriceLists additional price lists the request asks for
		 * @param accompanyingPrices         accompanying prices the request asks for, NULL for none
		 * @return the request stub
		 */
		@Nonnull
		private static EvitaRequest enrichmentRequest(
			@Nonnull PriceContentMode requiresEntityPrices,
			@Nonnull String[] fetchesAdditionalPriceLists,
			@Nullable AccompanyingPrice[] accompanyingPrices
		) {
			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
			Mockito.when(evitaRequest.getRequiresPriceLists())
				.thenReturn(new String[]{"basic"});
			Mockito.when(evitaRequest.getFetchesAdditionalPriceLists())
				.thenReturn(fetchesAdditionalPriceLists);
			Mockito.when(evitaRequest.getAccompanyingPrices())
				.thenReturn(
					accompanyingPrices == null ? PricesContract.NO_ACCOMPANYING_PRICES : accompanyingPrices
				);
			Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(requiresEntityPrices);
			return evitaRequest;
		}

		/**
		 * Builds a predicate already carrying the passed additional price lists in RESPECTING_FILTER mode.
		 *
		 * @param additionalPriceLists additional price lists the predicate already carries, NULL when it carries none
		 * @return the predicate
		 */
		@Nonnull
		private static PriceContractSerializablePredicate predicateCarrying(@Nullable String[] additionalPriceLists) {
			final Set<String> priceListsAsSet = new HashSet<>();
			priceListsAsSet.add("basic");
			if (additionalPriceLists != null) {
				priceListsAsSet.addAll(Arrays.asList(additionalPriceLists));
			}
			return new PriceContractSerializablePredicate(
				PriceContentMode.RESPECTING_FILTER, null, null, new String[]{"basic"},
				additionalPriceLists, null,
				priceListsAsSet,
				QueryPriceMode.WITH_TAX, false
			);
		}

		@Test
		@DisplayName(
			"returns same when the requested price lists are already carried"
		)
		void shouldReturnSameInstanceWhenRequestedPriceListsAlreadyCovered() {
			// a non-identical predicate is what tells the fetch pipeline the entity has to be enriched, so building
			// an equal copy here would trigger a storage round trip that provably fetches nothing
			final PriceContractSerializablePredicate predicate =
				predicateCarrying(new String[]{"basic", "reference"});

			assertSame(
				predicate,
				predicate.createRicherCopyWith(
					enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"basic"}, null)
				)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when a requested price list is missing"
		)
		void shouldCreateRicherCopyWhenSomeRequestedPriceListsAreMissing() {
			final PriceContractSerializablePredicate predicate =
				predicateCarrying(new String[]{"basic"});

			final PriceContractSerializablePredicate richerCopy = predicate.createRicherCopyWith(
				enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"basic", "vip"}, null)
			);

			assertNotSame(predicate, richerCopy);
			assertTrue(richerCopy.getPriceListsAsSet().contains("vip"));
		}

		@Test
		@DisplayName(
			"creates richer copy when accompanying prices are requested"
		)
		void shouldCreateRicherCopyWhenAccompanyingPricesAreRequested() {
			// accompanying prices always widen the predicate, even when every price list they need is carried
			final PriceContractSerializablePredicate predicate =
				predicateCarrying(new String[]{"basic", "reference"});

			final PriceContractSerializablePredicate richerCopy = predicate.createRicherCopyWith(
				enrichmentRequest(
					PriceContentMode.RESPECTING_FILTER,
					new String[]{"basic"},
					new AccompanyingPrice[]{new AccompanyingPrice("discount", "reference")}
				)
			);

			assertNotSame(predicate, richerCopy);
		}

		@Test
		@DisplayName(
			"repeated enrichment does not accumulate duplicate price lists"
		)
		void shouldNotAccumulateDuplicatePriceListsAcrossRepeatedEnrichment() {
			PriceContractSerializablePredicate predicate =
				predicateCarrying(new String[]{"basic", "reference"});

			for (int i = 0; i < 3; i++) {
				predicate = predicate.createRicherCopyWith(
					enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"reference"}, null)
				);
			}

			assertArrayEquals(
				new String[]{"basic", "reference"},
				predicate.getAdditionalPriceLists()
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when no additional price lists were carried"
		)
		void shouldCreateRicherCopyWhenNoAdditionalPriceListsWereCarried() {
			// nothing carried means nothing covered, so even a single requested price list widens the predicate
			final PriceContractSerializablePredicate predicate = predicateCarrying(null);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(
					enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"reference"}, null)
				)
			);
		}

		@Test
		@DisplayName(
			"a narrower request never narrows the price content mode"
		)
		void shouldKeepWiderContentModeWhenNothingWidens() {
			// createRicherCopyWith only ever widens: a request for a narrower content mode that adds nothing else
			// leaves the predicate exactly as it was, prices included
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, new String[]{"basic"},
					new String[]{"basic", "reference"}, null,
					new HashSet<>(Arrays.asList("basic", "reference")),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContractSerializablePredicate richerCopy = predicate.createRicherCopyWith(
				enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"basic"}, null)
			);

			assertSame(predicate, richerCopy);
			assertEquals(PriceContentMode.ALL, richerCopy.getPriceContentMode());
		}

		@Test
		@DisplayName(
			"a narrower request that widens something else still keeps the wider content mode"
		)
		void shouldKeepWiderContentModeWhenSomethingElseWidens() {
			// all three ways out of "this predicate is already at least as wide" have to agree: enrichment never
			// takes prices away, so adding a price list must not also drop the content mode back to the request's
			final PriceContractSerializablePredicate predicate =
				new PriceContractSerializablePredicate(
					PriceContentMode.ALL, null, null, new String[]{"basic"},
					new String[]{"basic"}, null,
					new HashSet<>(List.of("basic")),
					QueryPriceMode.WITH_TAX, false
				);

			final PriceContractSerializablePredicate richerCopy = predicate.createRicherCopyWith(
				enrichmentRequest(PriceContentMode.RESPECTING_FILTER, new String[]{"basic", "vip"}, null)
			);

			assertNotSame(predicate, richerCopy);
			assertEquals(PriceContentMode.ALL, richerCopy.getPriceContentMode());
			assertTrue(richerCopy.getPriceListsAsSet().contains("vip"));
		}
	}
}
