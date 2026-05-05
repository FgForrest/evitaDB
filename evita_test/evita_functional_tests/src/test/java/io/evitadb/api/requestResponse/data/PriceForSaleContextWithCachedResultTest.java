/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.AbstractBuilderTest;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cache contract of {@link PriceForSaleContextWithCachedResult}: the underlying
 * {@link PricesContract#computePriceForSaleResult} pass — which is the expensive part of resolving a
 * selling price — runs at most once across mixed selling-price / range / accompanying-price calls. This
 * is the contract promised by issue #1086 ("the range costs ~the same as the selling price").
 *
 * Most checks rely on {@code assertSame} reference equality between successive call results — the
 * underlying computation allocates fresh `PriceContract` instances per call, so getting the same
 * reference back is direct evidence of cache reuse. One belt-and-suspenders test uses
 * {@link MockedStatic} with {@link Mockito#CALLS_REAL_METHODS} as a spy / counter on the static entry
 * point, to guard against future regressions where the impl may begin returning equal-but-distinct
 * instances and the assertSame coupling would silently break.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PriceForSaleContextWithCachedResult")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(PRICE)
class PriceForSaleContextWithCachedResultTest extends AbstractBuilderTest {
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final String BASIC = "basic";
	private static final String[] PRICE_LIST_PRIORITY = {BASIC};

	private static final List<PriceContract> SAMPLE_PRICES = Arrays.asList(
		new Price(new PriceKey(1, BASIC, CZK), 1,
			new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), null, true),
		new Price(new PriceKey(2, BASIC, CZK), 2,
			new BigDecimal("200"), new BigDecimal("21"), new BigDecimal("242"), null, true),
		new Price(new PriceKey(3, BASIC, CZK), 3,
			new BigDecimal("300"), new BigDecimal("21"), new BigDecimal("363"), null, true)
	);

	@Test
	@DisplayName("PricesContract#computePriceForSaleResult runs exactly once across all four entry points")
	void shouldRunUnderlyingComputationExactlyOnceAcrossAllEntryPoints() {
		final PriceForSaleContextWithCachedResult cache = new PriceForSaleContextWithCachedResult(
			PRICE_LIST_PRIORITY, CZK, null, null
		);

		try (MockedStatic<PricesContract> mocked = Mockito.mockStatic(PricesContract.class, Mockito.CALLS_REAL_METHODS)) {
			// touch every entry point — none should trigger a second underlying pass
			cache.compute(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
			cache.computePriceForSale(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
			cache.computeRange(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
			cache.computeRangeWithAccompanyingPrices(
				SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE,
				PricesContract.NO_ACCOMPANYING_PRICES
			);
			cache.compute(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);

			mocked.verify(
				() -> PricesContract.computePriceForSaleResult(
					Mockito.eq(SAMPLE_PRICES),
					Mockito.eq(PriceInnerRecordHandling.LOWEST_PRICE),
					Mockito.eq(CZK),
					Mockito.isNull(),
					Mockito.eq(PRICE_LIST_PRIORITY),
					Mockito.any()
				),
				Mockito.times(1)
			);
		}
	}

	@Test
	@DisplayName("Repeated computeRange calls return the same PriceContract instances")
	void shouldReturnIdenticalComputationInstanceOnRepeatedCalls() {
		final PriceForSaleContextWithCachedResult cache = new PriceForSaleContextWithCachedResult(
			PRICE_LIST_PRIORITY, CZK, null, null
		);

		// the first call populates the rich cache; the second must read it back unchanged
		final Optional<PriceRangeForSale> firstRange = cache.computeRange(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
		final Optional<PriceRangeForSale> secondRange = cache.computeRange(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);

		assertTrue(firstRange.isPresent());
		assertTrue(secondRange.isPresent());
		// the underlying allocates fresh PriceContract instances per pass, so reference equality
		// here proves the second call hit the cache rather than recomputing
		assertSame(firstRange.get().lowestPrice(), secondRange.get().lowestPrice());
		assertSame(firstRange.get().highestPrice(), secondRange.get().highestPrice());
		assertSame(firstRange.get().priceForSale(), secondRange.get().priceForSale());
	}

	@Test
	@DisplayName("computePriceForSale and computeRange agree on the priceForSale reference")
	void shouldExposeIdenticalPriceForSaleAcrossViews() {
		final PriceForSaleContextWithCachedResult cache = new PriceForSaleContextWithCachedResult(
			PRICE_LIST_PRIORITY, CZK, null, null
		);

		// regardless of which entry point fills the cache first, both views must surface
		// the same selling-price instance — proves the rich computation slot is shared
		final Optional<PriceContract> direct = cache.computePriceForSale(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
		final Optional<PriceRangeForSale> range = cache.computeRange(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);

		assertTrue(direct.isPresent());
		assertTrue(range.isPresent());
		assertEquals(1, direct.get().priceId(), "LOWEST_PRICE → cheapest priceId in SAMPLE_PRICES");
		assertEquals(1, range.get().lowestPrice().priceId());
		assertEquals(3, range.get().highestPrice().priceId());
		assertSame(direct.get(), range.get().priceForSale(),
			"priceForSale exposed by computePriceForSale and computeRange must be the same instance");
	}

	@Test
	@DisplayName("Pre-seeded result is returned by compute() / computePriceForSale() independently of any prior range queries")
	void shouldHonourPreSeededResultIndependentlyOfRangeQueries() {
		// gRPC reverse-path scenario: the wire already carries the resolved selling price + accompanying
		// prices, so the deserializer pre-seeds `cachedResult`. The two cache slots are independent —
		// `cachedResult` (read by compute / computePriceForSale) and `cachedComputation` (read by range
		// queries) do not interact. A range query therefore runs a fresh computation against the local
		// price list (which knows nothing about the seeded selling price), yet the seed must still surface
		// unchanged from compute() / computePriceForSale().
		final PriceContract seedPrice = new Price(
			new PriceKey(99, BASIC, CZK), 1,
			new BigDecimal("999"), new BigDecimal("21"), new BigDecimal("1208.79"), null, true
		);
		final PriceForSaleWithAccompanyingPrices seed =
			new PriceForSaleWithAccompanyingPrices(seedPrice, Collections.emptyMap());

		final PriceForSaleContextWithCachedResult cache = new PriceForSaleContextWithCachedResult(
			PRICE_LIST_PRIORITY, CZK, null, new AccompanyingPrice[0], seed
		);

		// range query fills `cachedComputation` from SAMPLE_PRICES — entirely separate from the seed
		final Optional<PriceRangeForSale> range = cache.computeRange(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
		final Optional<PriceForSaleWithAccompanyingPrices> sellingView =
			cache.compute(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);
		final Optional<PriceContract> sellingPrice =
			cache.computePriceForSale(SAMPLE_PRICES, PriceInnerRecordHandling.LOWEST_PRICE);

		assertTrue(range.isPresent());
		// the range bounds reflect SAMPLE_PRICES, not the seed (whose priceId is 99)
		assertEquals(1, range.get().lowestPrice().priceId());
		assertEquals(3, range.get().highestPrice().priceId());

		assertTrue(sellingView.isPresent());
		assertSame(seed, sellingView.get(), "compute() must return the seed instance untouched");
		assertTrue(sellingPrice.isPresent());
		assertSame(seedPrice, sellingPrice.get(), "computePriceForSale() must return the seeded price untouched");
	}
}
