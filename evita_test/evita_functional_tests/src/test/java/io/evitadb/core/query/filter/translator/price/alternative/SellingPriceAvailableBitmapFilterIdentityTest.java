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

package io.evitadb.core.query.filter.translator.price.alternative;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.dataType.Scope;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached identities of the selling-price prefetch alternative.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Selling price prefetch alternative identity")
@Tag(ENGINE)
@Tag(PRICE)
class SellingPriceAvailableBitmapFilterIdentityTest {
	/** Currency used by the value-identity requests. */
	private static final Currency CZK = Currency.getInstance("CZK");

	/** Scoped pricing contexts do not have a single request-wide value identity. */
	@Test
	@DisplayName("Defers ambiguous scoped price lists and keeps their key opaque")
	void shouldDeferAmbiguousScopedPriceListsAndKeepKeyOpaque() {
		final EvitaRequest request = new EvitaRequest(
			Query.query(
				collection("product"),
				filterBy(
					scope(Scope.LIVE, Scope.ARCHIVED),
					inScope(Scope.LIVE, priceInPriceLists("basic")),
					inScope(Scope.ARCHIVED, priceInPriceLists("vip"))
				)
			),
			OffsetDateTime.parse("2026-01-01T00:00:00Z"), EntityReference.class, null
		);
		final SellingPriceAvailableBitmapFilter filter = assertDoesNotThrow(
			() -> SellingPriceAvailableBitmapFilter.forRequest(request, PricePredicate.ALL_CONTRACT_FILTER)
		);

		final IdentityRecord identity = assertDoesNotThrow(filter::identity);
		assertSame(identity, filter.identity());
		assertEquals(IdentityToken.OPAQUE, ((SellingPriceAvailableBitmapFilter.Identity) identity).priceContext());
		final FormulaKey firstKey = assertDoesNotThrow(() -> key(filter));
		assertTrue(firstKey.isOpaque());
		assertEquals(firstKey, key(filter));
	}

	/** Additional price lists contribute with their order and content. */
	@Test
	@DisplayName("Captures the order and content of additional price lists")
	void shouldCaptureAdditionalPriceListsInOrder() {
		final FormulaKey captured = key(additional("first", "second"));
		assertEquals(captured, key(additional("first", "second")));
		assertNotEquals(captured, key(additional("second", "first")));
		assertNotEquals(captured, key(additional("first")));
	}

	/** Each price predicate value contributes to the alternative's value identity and structural hash. */
	@Test
	@DisplayName("Distinguishes every price predicate value input")
	void shouldDistinguishEveryPricePredicateInput() {
		final FormulaKey base = predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2);
		final FormulaKey[] variants = {
			predicateKey(BigDecimal.valueOf(2), BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.valueOf(20), QueryPriceMode.WITH_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 3)
		};
		for (final FormulaKey variant : variants) {
			assertNotEquals(base, variant);
			assertNotEquals(base.getHash64(), variant.getHash64());
		}
		assertEquals(base, predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2));
	}

	/** String content reaches the structural hash independently of Java's String hash. */
	@Test
	@DisplayName("Separates price-list names with equal Java string hashes")
	void shouldHashPriceListNamesByContent() {
		final FormulaKey first = key(additional("Aa"));
		final FormulaKey second = key(additional("BB"));
		assertNotEquals(first, second);
		assertNotEquals(first.getHash64(), second.getHash64());
	}

	/** Builds a real contract predicate and its owning alternative. */
	@Nonnull
	private static FormulaKey predicateKey(
		@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode mode, int places
	) {
		final PriceContractPredicate predicate = new PricePredicate.PriceContractPredicate(from, to, mode, places);
		return key(SellingPriceAvailableBitmapFilter.forRequest(
			SellingPriceAvailableBitmapFilterIdentityFixture.request(
				CZK, null, QueryPriceMode.WITH_TAX, new String[]{"basic"}, "basic"
			),
			predicate
		));
	}

	/** Builds the alternative of a request fetching the passed additional price lists. */
	@Nonnull
	private static SellingPriceAvailableBitmapFilter additional(@Nonnull String... additionalPriceLists) {
		return SellingPriceAvailableBitmapFilter.forRequest(
			SellingPriceAvailableBitmapFilterIdentityFixture.request(
				CZK, null, QueryPriceMode.WITH_TAX, additionalPriceLists, "basic"
			),
			PricePredicate.ALL_CONTRACT_FILTER
		);
	}

	/** Builds the same leaf side key that a selection builds for its alternative. */
	@Nonnull
	private static FormulaKey key(@Nonnull SellingPriceAvailableBitmapFilter filter) {
		return FormulaKey.of(filter.identity(), filter.getClassId(), FormulaKey.NO_CHILDREN, filter);
	}
}
