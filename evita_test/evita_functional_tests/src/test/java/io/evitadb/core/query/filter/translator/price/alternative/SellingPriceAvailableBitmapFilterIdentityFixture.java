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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityFixture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Real construction inputs of {@link SellingPriceAvailableBitmapFilter}: the factory capturing the request's
 * price-for-sale context, and the constructors that capture none and stay opaque.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SellingPriceAvailableBitmapFilterIdentityFixture
	implements IdentityFixture<SellingPriceAvailableBitmapFilter> {
	/** Moment the fixture requests are created at. */
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");
	/** Czech crown, the currency of the base context. */
	private static final Currency CZK = Currency.getInstance("CZK");

	/** Covers every captured context input independently, and the opaque constructors. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<SellingPriceAvailableBitmapFilter>> modes() {
		final PriceContractPredicate between = new PricePredicate.PriceContractPredicate(
			BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2);
		return Map.of(
			"request context", new ConstructionMode<>(
				() -> captured("reference", PricePredicate.ALL_CONTRACT_FILTER, CZK, null, QueryPriceMode.WITH_TAX,
					"basic"),
				Map.of(
					"additional lists", () -> captured("other", PricePredicate.ALL_CONTRACT_FILTER, CZK, null,
						QueryPriceMode.WITH_TAX, "basic"),
					"filter", () -> captured("reference", between, CZK, null, QueryPriceMode.WITH_TAX, "basic"),
					"price lists", () -> captured("reference", PricePredicate.ALL_CONTRACT_FILTER, CZK, null,
						QueryPriceMode.WITH_TAX, "vip", "basic"),
					"currency", () -> captured("reference", PricePredicate.ALL_CONTRACT_FILTER,
						Currency.getInstance("EUR"), null, QueryPriceMode.WITH_TAX, "basic"),
					"validity", () -> captured("reference", PricePredicate.ALL_CONTRACT_FILTER, CZK, NOW,
						QueryPriceMode.WITH_TAX, "basic"),
					"price mode", () -> captured("reference", PricePredicate.ALL_CONTRACT_FILTER, CZK, null,
						QueryPriceMode.WITHOUT_TAX, "basic")
				),
				Map.of(), Map.of(), Map.of(), Set.of("priceContext"), Set.of("priceContext"), Set.of()),
			"uncaptured context", new ConstructionMode<>(
				null, Map.of(),
				Map.of(
					"additional lists", () -> new SellingPriceAvailableBitmapFilter(new String[]{"basic"},
						PricePredicate.ALL_CONTRACT_FILTER),
					"no additional lists", () -> new SellingPriceAvailableBitmapFilter(),
					"filter", () -> new SellingPriceAvailableBitmapFilter(null, between)
				),
				Map.of(), Map.of(), Set.of(), Set.of(), Set.of())
		);
	}

	/**
	 * Creates the filter the way the translators do, from a request filtering by the given price-for-sale context.
	 */
	@Nonnull
	static SellingPriceAvailableBitmapFilter captured(
		@Nonnull String additionalPriceList,
		@Nonnull PriceContractPredicate filter,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull QueryPriceMode priceMode,
		@Nonnull String... priceLists
	) {
		return SellingPriceAvailableBitmapFilter.forRequest(
			request(currency, validIn, priceMode, new String[]{additionalPriceList}, priceLists), filter
		);
	}

	/**
	 * Creates a request filtering by the given price-for-sale context and fetching prices that respect it.
	 */
	@Nonnull
	static EvitaRequest request(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nonnull QueryPriceMode priceMode,
		@Nonnull String[] additionalPriceLists,
		@Nonnull String... priceLists
	) {
		final FilterConstraint[] constraints = validIn == null ?
			new FilterConstraint[]{priceInCurrency(currency), priceInPriceLists(priceLists)} :
			new FilterConstraint[]{priceInCurrency(currency), priceInPriceLists(priceLists), priceValidIn(validIn)};
		return new EvitaRequest(
			Query.query(
				collection("product"),
				filterBy(and(constraints)),
				require(entityFetch(priceContentRespectingFilter(additionalPriceLists)), priceType(priceMode))
			),
			NOW, EntityReference.class, null
		);
	}
}
