/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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


import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleComputationResult;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleContext;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * Per-entity cache for the price-for-sale computation. Caches the rich
 * {@link PriceForSaleComputationResult} once so that follow-up calls — selling price, range, accompanying
 * prices — can be derived without re-running {@link PricesContract#computePriceForSaleResult} for every view.
 * REST / gRPC / GraphQL serializers materializing both `priceForSale` and `priceForSaleMin` / `priceForSaleMax` for the same
 * entity therefore pay only one full pass.
 *
 * Two independent cache slots are kept:
 *
 * - `cachedComputation` — the full {@link PriceForSaleComputationResult}, populated by the first
 *   {@code compute*} call that needs the per-bucket detail (range or selling price without seed). Range
 *   views and {@link #computePriceForSale} both derive from this slot.
 * - `cachedResult` — the assembled {@link PriceForSaleWithAccompanyingPrices} returned by {@link #compute}.
 *   May be pre-seeded at construction by callers that already hold the value (e.g. the gRPC reverse path
 *   that reads selling price and accompanying prices straight off the wire); otherwise it is populated by
 *   the first {@code compute()} call so that the accompanying-price selection runs only once.
 *
 * The cache is per {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator} (request-scoped) and
 * is intended for short-lived materialization workloads.
 *
 * Filter predicate handling: the cache always assumes the no-op {@code Objects::nonNull} predicate that the
 * default {@code getPriceForSale} / {@code getPriceRangeForSale} overloads use. Callers passing a custom
 * predicate must bypass the cache entirely (the public defaults already do this — they only consult the
 * cache through the contextual entry points).
 */
public class PriceForSaleContextWithCachedResult implements PriceForSaleContext {
	/**
	 * List of price lists sorted by priority.
	 */
	@Nullable private final String[] priceListPriority;
	/**
	 * Currency used for price for sale calculation.
	 */
	@Nullable private final Currency currency;
	/**
	 * Moment used for price for sale calculation.
	 */
	@Nullable private final OffsetDateTime atTheMoment;
	/**
	 * List of accompanying prices that should be computed together with price for sale.
	 */
	@Nullable private final AccompanyingPrice[] accompanyingPrices;
	/**
	 * Rich computation cache populated lazily by the first {@code compute*} call that needs the per-bucket
	 * detail. Drives {@link #computePriceForSale}, {@link #computeRange}, and
	 * {@link #computeRangeWithAccompanyingPrices}. Declared `volatile` so the field reference is safely
	 * published to concurrent readers — multiple GraphQL / REST data fetchers may evaluate fields on the same
	 * {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator} in parallel.
	 */
	private volatile AtomicReference<PriceForSaleComputationResult> cachedComputation;
	/**
	 * Cache for the assembled {@link PriceForSaleWithAccompanyingPrices} returned by {@link #compute}. May be
	 * pre-seeded at construction (gRPC reverse path) or populated on the first {@code compute()} call so the
	 * accompanying-price selection runs at most once. Declared `volatile` for the same safe-publication reason
	 * as {@link #cachedComputation}.
	 */
	private volatile AtomicReference<PriceForSaleWithAccompanyingPrices> cachedResult;

	public PriceForSaleContextWithCachedResult(
		@Nullable String[] priceListPriority,
		@Nullable Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nullable AccompanyingPrice[] accompanyingPrices
	) {
		this.priceListPriority = priceListPriority;
		this.currency = currency;
		this.atTheMoment = atTheMoment;
		this.accompanyingPrices = accompanyingPrices;
	}

	public PriceForSaleContextWithCachedResult(
		@Nullable String[] priceListPriority,
		@Nullable Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nullable AccompanyingPrice[] accompanyingPrices,
		@Nonnull PriceForSaleWithAccompanyingPrices priceForSaleWithAccompanyingPrices
	) {
		this(priceListPriority, currency, atTheMoment, accompanyingPrices);
		this.cachedResult = new AtomicReference<>(priceForSaleWithAccompanyingPrices);
	}

	@Nonnull
	@Override
	public Optional<String[]> priceListPriority() {
		return ofNullable(this.priceListPriority);
	}

	@Nonnull
	@Override
	public Optional<Currency> currency() {
		return ofNullable(this.currency);
	}

	@Nonnull
	@Override
	public Optional<OffsetDateTime> atTheMoment() {
		return ofNullable(this.atTheMoment);
	}

	@Nonnull
	@Override
	public Optional<AccompanyingPrice[]> accompanyingPrices() {
		return ofNullable(this.accompanyingPrices);
	}

	/**
	 * Checks if the provided parameters match the current instance's attributes.
	 *
	 * @param currency the currency to compare with the instance's currency, must not be null
	 * @param atTheMoment the date and time to compare with the instance's date and time, can be null
	 * @param priceListPriority the price list priorities to compare with the instance's price list priorities, must not be null
	 * @return true if all provided parameters match the instance's attributes; false otherwise
	 */
	public boolean matches(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority
	) {
		return Objects.equals(this.currency, currency) &&
			Objects.equals(this.atTheMoment, atTheMoment) &&
			Objects.deepEquals(this.priceListPriority, priceListPriority);
	}

	/**
	 * Computes price for sale with accompanying prices from the provided collection of prices. Returns the
	 * cached result on every subsequent call — whether the cache was pre-seeded at construction (gRPC
	 * reverse-path) or filled by a prior call to this method. The accompanying-price selection therefore
	 * runs at most once. Method doesn't check the input price collection and inner record handling strategy
	 * consistency against cached value — it is expected to be always called with the same parameters as the
	 * first call.
	 *
	 * @return an Optional containing the computed PriceForSaleWithAccompanyingPrices or empty if no valid price is found
	 */
	@Nonnull
	public Optional<PriceForSaleWithAccompanyingPrices> compute(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		if (this.cachedResult != null) {
			return ofNullable(this.cachedResult.get());
		}
		final PriceForSaleComputationResult computation = ensureComputation(prices, innerRecordHandling);
		final PriceForSaleWithAccompanyingPrices result = computation == null
			? null
			: new PriceForSaleWithAccompanyingPrices(
				computation.priceForSale(),
				computation.calculateAccompanyingPrices(
					ofNullable(this.accompanyingPrices)
						.orElse(PricesContract.NO_ACCOMPANYING_PRICES)
				)
			);
		this.cachedResult = new AtomicReference<>(result);
		return ofNullable(result);
	}

	/**
	 * Returns the cached / freshly-computed price-for-sale range. Range queries read the rich
	 * {@code cachedComputation} slot, which is independent of the {@code cachedResult} seed used by
	 * {@link #compute}: a pre-seeded {@code cachedResult} carries no per-bucket data, so the first range
	 * query always runs a full computation. Subsequent range / selling-price / accompanying-price calls
	 * reuse the populated {@code cachedComputation}.
	 *
	 * @return an Optional containing the computed PriceRangeForSale or empty if no valid selling price exists
	 */
	@Nonnull
	public Optional<PriceRangeForSale> computeRange(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		final PriceForSaleComputationResult computation = ensureComputation(prices, innerRecordHandling);
		if (computation == null) {
			return Optional.empty();
		}
		return Optional.of(
			new PriceRangeForSale(
				computation.lowestPrice(),
				computation.highestPrice(),
				computation.priceForSale()
			)
		);
	}

	/**
	 * Returns the cached / freshly-computed price-for-sale range together with the requested accompanying
	 * prices. Behaves like {@link #computeRange} for the range bounds and like {@link #compute} for the
	 * accompanying-price selection — both views share the same underlying {@link PriceForSaleComputationResult}.
	 *
	 * @param requestedAccompanyingPrices array of accompanying-price requirements; may differ from the
	 *                                    requirements stored in the context (the rich cache is independent
	 *                                    of any specific accompanying-price set)
	 * @return an Optional containing the computed PriceRangeForSaleWithAccompanyingPrices or empty
	 */
	@Nonnull
	public Optional<PriceRangeForSaleWithAccompanyingPrices> computeRangeWithAccompanyingPrices(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull AccompanyingPrice[] requestedAccompanyingPrices
	) {
		final PriceForSaleComputationResult computation = ensureComputation(prices, innerRecordHandling);
		if (computation == null) {
			return Optional.empty();
		}
		return Optional.of(
			new PriceRangeForSaleWithAccompanyingPrices(
				new PriceRangeForSale(
					computation.lowestPrice(),
					computation.highestPrice(),
					computation.priceForSale()
				),
				computation.calculateAccompanyingPrices(requestedAccompanyingPrices)
			)
		);
	}

	/**
	 * Returns just the selling price. If {@link #compute} has already been called (or the cache was seeded),
	 * the answer is read from {@code cachedResult}. Otherwise the result is derived from the rich
	 * computation cache (populated on demand) without paying for the accompanying-price selection.
	 */
	@Nonnull
	public Optional<PriceContract> computePriceForSale(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		if (this.cachedResult != null) {
			return ofNullable(this.cachedResult.get())
				.map(PriceForSaleWithAccompanyingPrices::priceForSale);
		}
		final PriceForSaleComputationResult computation = ensureComputation(prices, innerRecordHandling);
		return ofNullable(computation).map(PriceForSaleComputationResult::priceForSale);
	}

	/**
	 * Returns the rich computation result, running the full {@link PricesContract#computePriceForSaleResult}
	 * pass and caching the result on the first call. The cache field is `volatile`, so the value written by
	 * the first completing thread is visible to subsequent readers. Concurrent first-callers may both run
	 * the computation (no compare-and-set on the field); the last writer wins. The cached
	 * {@link PriceForSaleComputationResult} is deterministic for a given input set, so duplicate computation
	 * is wasteful but not incorrect.
	 */
	@Nullable
	private PriceForSaleComputationResult ensureComputation(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling
	) {
		if (this.cachedComputation != null) {
			return this.cachedComputation.get();
		}
		final PriceForSaleComputationResult computation = PricesContract.computePriceForSaleResult(
			prices,
			innerRecordHandling,
			ofNullable(this.currency).orElseThrow(ContextMissingException::new),
			this.atTheMoment,
			ofNullable(this.priceListPriority).orElseThrow(ContextMissingException::new),
			Objects::nonNull
		);
		this.cachedComputation = new AtomicReference<>(computation);
		return computation;
	}
}
