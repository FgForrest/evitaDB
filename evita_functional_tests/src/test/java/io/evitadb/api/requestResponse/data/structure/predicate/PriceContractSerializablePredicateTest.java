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

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies behaviour of {@link PriceContractSerializablePredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class PriceContractSerializablePredicateTest {

	@Test
	void shouldCreateRicherCopyForNoPrices() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);
		assertNotSame(noPricesRequired, noPricesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForNoPricesAndAdditionalPriceListsInSource() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, new String[] {"A", "B"}, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = noPricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noPricesRequired, richerCopy);
		assertArrayEquals(new String[] {"A", "B"}, richerCopy.getAdditionalPriceLists());
	}

	@Test
	void shouldCreateRicherCopyForNoPricesAndAdditionalPriceListsInMock() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[] {"A", "B"});
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = noPricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noPricesRequired, richerCopy);
		assertArrayEquals(new String[] {"A", "B"}, richerCopy.getAdditionalPriceLists());
	}

	@Test
	void shouldCreateRicherCopyForNoPricesAndAdditionalPriceListsInBoth() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, new String[] {"A", "B"}, null,
			new HashSet<>(Arrays.asList("A", "B")), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[] {"A", "D"});
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = noPricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noPricesRequired, richerCopy);
		assertArrayEquals(new String[] {"A", "B", "A", "D"}, richerCopy.getAdditionalPriceLists());
		assertEquals(new HashSet<>(Arrays.asList("A", "B", "D")), richerCopy.getPriceListsAsSet());
	}

	@Test
	void shouldCreateRicherCopyForNoPricesAndPricesAndAdditionalPriceListsInBoth() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, new String[] {"X", "Z"}, new String[] {"A", "B"}, null,
			new HashSet<>(Arrays.asList("A", "B", "X", "Z")), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(null);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[] {"A", "D"});
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = noPricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noPricesRequired, richerCopy);
		assertArrayEquals(new String[] {"X", "Z"}, richerCopy.getPriceLists());
		assertArrayEquals(new String[] {"A", "B", "A", "D"}, richerCopy.getAdditionalPriceLists());
		assertEquals(new HashSet<>(Arrays.asList("A", "B", "D", "X", "Z")), richerCopy.getPriceListsAsSet());
	}

	@Test
	void shouldNotCreateRicherCopyForNoPrices() {
		final PriceContractSerializablePredicate noAttributesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.NONE);
		assertSame(noAttributesRequired, noAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForNoPricesWhenPricesPresent() {
		final PriceContractSerializablePredicate noAttributesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.RESPECTING_FILTER, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.NONE);
		assertSame(noAttributesRequired, noAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForNoPricesRespectingFilter() {
		final PriceContractSerializablePredicate pricesRespectingFilterRequired = new PriceContractSerializablePredicate(
			PriceContentMode.RESPECTING_FILTER, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.ALL);
		assertNotSame(pricesRespectingFilterRequired, pricesRespectingFilterRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForRespectingFilter() {
		final PriceContractSerializablePredicate pricesRespectingFilterRequired = new PriceContractSerializablePredicate(
			PriceContentMode.NONE, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.NONE);
		assertSame(pricesRespectingFilterRequired, pricesRespectingFilterRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForAllPrices() {
		final PriceContractSerializablePredicate allPrices = new PriceContractSerializablePredicate(
			PriceContentMode.ALL, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(new String[]{"A"});
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(PricesContract.NO_ACCOMPANYING_PRICES);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(Currency.getInstance("CZK"));
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(OffsetDateTime.now());
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);
		assertSame(allPrices, allPrices.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyWithAccompanyingPricesWhenOriginalHasNone() {
		final PriceContractSerializablePredicate noPricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.RESPECTING_FILTER, null, null, null, null, null,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final AccompanyingPrice[] accompanyingPrices = new AccompanyingPrice[] {
			new AccompanyingPrice("price1", "A", "B"),
			new AccompanyingPrice("price2", "C", "D")
		};

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(accompanyingPrices);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = noPricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noPricesRequired, richerCopy);
		assertArrayEquals(accompanyingPrices, richerCopy.getAccompanyingPrices());
	}

	@Test
	void shouldCreateRicherCopyWithAccompanyingPricesWhenOriginalHasSome() {
		final AccompanyingPrice[] originalAccompanyingPrices = new AccompanyingPrice[] {
			new AccompanyingPrice("price1", "A", "B")
		};

		final PriceContractSerializablePredicate pricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.RESPECTING_FILTER, null, null, null, null, originalAccompanyingPrices,
			Collections.emptySet(), QueryPriceMode.WITH_TAX, false
		);

		final AccompanyingPrice[] newAccompanyingPrices = new AccompanyingPrice[] {
			new AccompanyingPrice("price2", "C", "D"),
			new AccompanyingPrice("price3", "E", "F")
		};

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(newAccompanyingPrices);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = pricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(pricesRequired, richerCopy);

		// Check that both original and new accompanying prices are present
		AccompanyingPrice[] expectedAccompanyingPrices = ArrayUtils.mergeArrays(originalAccompanyingPrices, newAccompanyingPrices);
		assertArrayEquals(expectedAccompanyingPrices, richerCopy.getAccompanyingPrices());
	}

	@Test
	void shouldAddPriceListsFromAccompanyingPricesToAdditionalPriceLists() {
		final AccompanyingPrice[] accompanyingPrices = new AccompanyingPrice[] {
			new AccompanyingPrice("price1", "A", "B"),
			new AccompanyingPrice("price2", "C", "D")
		};

		final PriceContractSerializablePredicate pricesRequired = new PriceContractSerializablePredicate(
			PriceContentMode.RESPECTING_FILTER, null, null, new String[] {"X"}, new String[] {"Y"}, null,
			new HashSet<>(Arrays.asList("X", "Y")), QueryPriceMode.WITH_TAX, false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresPriceLists()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiresPriceLists()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
		Mockito.when(evitaRequest.getFetchesAdditionalPriceLists()).thenReturn(new String[] {"Z"});
		Mockito.when(evitaRequest.getAccompanyingPrices()).thenReturn(accompanyingPrices);
		Mockito.when(evitaRequest.getRequiresCurrency()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresPriceValidIn()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiresEntityPrices()).thenReturn(PriceContentMode.RESPECTING_FILTER);

		final PriceContractSerializablePredicate richerCopy = pricesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(pricesRequired, richerCopy);

		// Check that priceListsAsSet contains all price lists from all sources
		Set<String> expectedPriceLists = new HashSet<>(Arrays.asList("X", "Y", "Z", "A", "B", "C", "D"));
		assertEquals(expectedPriceLists, richerCopy.getPriceListsAsSet());
	}
}
