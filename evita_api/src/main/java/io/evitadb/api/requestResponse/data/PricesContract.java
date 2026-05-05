/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.query.require.DefaultAccompanyingPriceLists;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.structure.CumulatedPrice;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Contract for classes that allow reading information about prices in {@link Entity} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PricesContract extends Versioned, Serializable {
	AccompanyingPrice[] NO_ACCOMPANYING_PRICES = AccompanyingPrice.EMPTY_ARRAY;

	/**
	 * Computes a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 */
	@Nonnull
	static Optional<PriceContract> computePriceForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate
	) {
		return computePriceForSale(
			entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority,
			filterPredicate, NO_ACCOMPANYING_PRICES
		)
			.map(PriceForSaleWithAccompanyingPrices::priceForSale);
	}

	/**
	 * Computes a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned. Accompanying prices
	 * are computed alongside the selling price and follow the same currency / valid-in / inner-record rules as
	 * the selling price itself.
	 */
	@Nonnull
	static Optional<PriceForSaleWithAccompanyingPrices> computePriceForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		final PriceForSaleComputationResult computation = computeInternal(
			entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority, filterPredicate
		);
		if (computation == null) {
			return empty();
		}
		return of(
			new PriceForSaleWithAccompanyingPrices(
				computation.priceForSale(),
				computation.calculateAccompanyingPrices(accompanyingPrices)
			)
		);
	}

	/**
	 * Internal entry point that exposes the rich {@link PriceForSaleComputationResult} produced by
	 * {@link #computeInternal} so that {@link PriceForSaleContextWithCachedResult} can cache it once and
	 * derive both the selling-price view and the range view from a single computation pass. External
	 * callers must use the public {@code computePriceForSale} / {@code computePriceRangeForSale} entry
	 * points instead — this method is part of the cache plumbing.
	 *
	 * @apiNote internal — not part of the public API; subject to change without notice.
	 */
	@Nullable
	static PriceForSaleComputationResult computePriceForSaleResult(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate
	) {
		return computeInternal(entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority, filterPredicate);
	}

	/**
	 * Computes the full price range for the entity together with the resolved selling price. Only indexed prices
	 * in the requested currency, valid at the passed moment, and contained in the passed price lists are
	 * considered. Range bounds are real, indexed prices that satisfy the same filter rules as the selling price.
	 *
	 * Strategy semantics — see {@link PriceRangeForSale}.
	 */
	@Nonnull
	static Optional<PriceRangeForSale> computePriceRangeForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate
	) {
		return computePriceRangeForSale(
			entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority,
			filterPredicate, NO_ACCOMPANYING_PRICES
		)
			.map(PriceRangeForSaleWithAccompanyingPrices::priceRange);
	}

	/**
	 * Computes the full price range for the entity together with the resolved selling price and accompanying
	 * prices.
	 *
	 * Strategy semantics — see {@link PriceRangeForSale}.
	 */
	@Nonnull
	static Optional<PriceRangeForSaleWithAccompanyingPrices> computePriceRangeForSale(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		final PriceForSaleComputationResult computation = computeInternal(
			entityPrices, innerRecordHandling, currency, atTheMoment, priceListPriority, filterPredicate
		);
		if (computation == null) {
			return empty();
		}
		return of(
			new PriceRangeForSaleWithAccompanyingPrices(
				new PriceRangeForSale(
					computation.lowestPrice(),
					computation.highestPrice(),
					computation.priceForSale()
				),
				computation.calculateAccompanyingPrices(accompanyingPrices)
			)
		);
	}

	/**
	 * Returns true if single price differs between first and second instance.
	 */
	static boolean anyPriceOrStrategyDifferBetween(@Nonnull PricesContract first, @Nonnull PricesContract second) {
		final PriceInnerRecordHandling thisStrategy;
		final Collection<PriceContract> thisValues;
		final PriceInnerRecordHandling otherStrategy;
		final Collection<PriceContract> otherValues;

		if (first.pricesAvailable()) {
			thisStrategy = first.getPriceInnerRecordHandling();
			thisValues = first.getPrices();
		} else {
			thisStrategy = PriceInnerRecordHandling.NONE;
			thisValues = Collections.emptyList();
		}
		if (second.pricesAvailable()) {
			otherStrategy = second.getPriceInnerRecordHandling();
			otherValues = second.getPrices();
		} else {
			otherStrategy = PriceInnerRecordHandling.NONE;
			otherValues = Collections.emptyList();
		}

		if (thisStrategy != otherStrategy) {
			return true;
		} else if (thisValues.size() != otherValues.size()) {
			return true;
		} else {
			return thisValues
				.stream()
				.anyMatch(it -> it.differsFrom(second.getPrice(it.priceId(), it.priceList(), it.currency()).orElse(null)));
		}
	}

	/**
	 * Calculates a map of accompanying prices based on the provided price, collection of entity prices,
	 * inner record handling, currency, moment in time, and accompanying price configuration.
	 *
	 * @param priceForSale        the primary price to reference for calculating accompanying prices
	 * @param entityPrices        a collection of price contracts related to the entity
	 * @param innerRecordHandling the inner-record handling strategy
	 * @param currency            currency in which accompanying prices are expected
	 * @param atTheMoment         moment in time the accompanying prices should be valid for; {@code null} ignores time
	 * @param accompanyingPrices  array of accompanying-price requirements
	 * @return a map keyed by accompanying-price name with optional matched prices
	 */
	@Nonnull
	static Map<String, Optional<PriceContract>> calculateAccompanyingPrices(
		@Nonnull PriceContract priceForSale,
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length == 0) {
			return Collections.emptyMap();
		}
		return switch (innerRecordHandling) {
			case NONE -> selectAccompanyingPrices(
				filterCandidatesByCurrencyAndValidity(entityPrices, currency, atTheMoment),
				accompanyingPrices
			);
			case LOWEST_PRICE -> selectAccompanyingPrices(
				filterCandidatesByInnerRecordRelation(entityPrices, priceForSale, currency, atTheMoment),
				accompanyingPrices
			);
			case SUM -> calculateAccompanyingPricesForSumInnerRecordHandling(
				priceForSale, entityPrices, currency, atTheMoment, accompanyingPrices
			);
			case UNKNOWN ->
				throw new GenericEvitaInternalError("Cannot compute accompanying prices for UNKNOWN inner record handling.");
		};
	}

	/**
	 * Single allocation-light pass over `entityPrices` that produces the per-strategy selling price together
	 * with the lowest / highest range bounds and the bookkeeping needed to compute accompanying prices later.
	 * The method is the single source of truth for the price-for-sale logic — the public `computePriceForSale*`
	 * and `computePriceRangeForSale*` overloads are thin wrappers over its result.
	 *
	 * Returns `null` when no selling price exists for the given filter (e.g. predicate eliminated everything,
	 * currency / validity rejected all candidates, or no inner record yielded a valid candidate).
	 */
	@Nullable
	private static PriceForSaleComputationResult computeInternal(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull Predicate<PriceContract> filterPredicate
	) {
		if (entityPrices.isEmpty()) {
			return null;
		}

		// hoisted out of the hot loop
		final Map<String, Integer> priorityIndex = getPriceListPriorityIndex(priceListPriority);
		// presize estimates: assume each inner record carries roughly one price per priority list, so the
		// number of distinct inner records ≈ N/L. A small reserve absorbs noise (entities with extra price
		// lists outside the priority, or sparse inner records).
		final int innerBucketEstimate = Math.max(2, priceListPriority.length + 2);
		final int distinctInnerEstimate = Math.max(
			2, entityPrices.size() / Math.max(1, priceListPriority.length) + 2
		);

		return switch (innerRecordHandling) {
			case NONE -> {
				PriceContract bestForSale = null;
				int bestPriority = Integer.MAX_VALUE;
				// candidates collected during the first pass — reused for accompanying-price calc
				final List<PriceContract> candidates = new ArrayList<>(entityPrices.size());
				for (final PriceContract price : entityPrices) {
					if (isNotCandidate(price, currency, atTheMoment)) {
						continue;
					}
					candidates.add(price);
					if (!price.indexed()) {
						continue;
					}
					final Integer priorityBoxed = priorityIndex.get(price.priceList());
					if (priorityBoxed == null) {
						continue;
					}
					final int priority = priorityBoxed;
					if (priority < bestPriority) {
						bestPriority = priority;
						bestForSale = price;
					}
				}
				if (bestForSale == null || !filterPredicate.test(bestForSale)) {
					yield null;
				}
				// NONE collapses the range to a single point
				yield new PriceForSaleComputationResult(
					innerRecordHandling, bestForSale, bestForSale, bestForSale,
					candidates, null
				);
			}
			case LOWEST_PRICE -> {
				// per-inner-record state — outer maps sized for the estimated number of distinct inner records;
				// per-inner-record raw price list presized for one price per priority entry plus reserve
				final Map<Integer, PriceContract> bestPerInner = CollectionUtils.createLinkedHashMap(distinctInnerEstimate);
				final Map<Integer, Integer> bestPriorityPerInner = CollectionUtils.createHashMap(distinctInnerEstimate);
				final Map<Integer, List<PriceContract>> rawByInner = CollectionUtils.createLinkedHashMap(distinctInnerEstimate);
				for (final PriceContract price : entityPrices) {
					if (isNotCandidate(price, currency, atTheMoment)) {
						continue;
					}
					final int innerKey = innerRecordKey(price);
					rawByInner.computeIfAbsent(innerKey, k -> new ArrayList<>(innerBucketEstimate)).add(price);
					updateBestPerInner(price, innerKey, priorityIndex, bestPerInner, bestPriorityPerInner);
				}
				if (bestPerInner.isEmpty()) {
					yield null;
				}
				// pick lowest and highest by priceWithTax in a single pass.
				// `lowest` (== selling price under LOWEST_PRICE) must satisfy `filterPredicate` — the predicate
				// is a selling-price scoped filter inherited from the legacy `getPriceForSale*` API.
				// `highest` is the most expensive per-inner-record candidate regardless of `filterPredicate`,
				// because the range bounds describe the real indexed catalogue spread for the resolved currency
				// / valid-in / price-list filters and must NOT be clipped by the selling-price predicate.
				// Tie-break is deterministic: lower priceWithTax wins, then lower priceId, then lower
				// innerRecordId (null sorts before non-null).
				PriceContract lowest = null;
				PriceContract highest = null;
				for (final PriceContract candidate : bestPerInner.values()) {
					if (isBetterPriceForSaleCandidate(highest, candidate, false)) {
						highest = candidate;
					}
					if (filterPredicate.test(candidate)
						&& isBetterPriceForSaleCandidate(lowest, candidate, true)) {
						lowest = candidate;
					}
				}
				if (lowest == null) {
					yield null;
				}
				// for LOWEST_PRICE, accompanying prices are sourced from raw prices that share innerRecordId
				// with the selling price
				final int innerKey = innerRecordKey(lowest);
				final List<PriceContract> accompanyingCandidates = rawByInner.getOrDefault(innerKey, Collections.emptyList());
				yield new PriceForSaleComputationResult(
					innerRecordHandling, lowest, lowest, highest,
					accompanyingCandidates, null
				);
			}
			case SUM -> {
				// per-inner-record state — outer maps sized for the estimated number of distinct inner records;
				// per-inner-record price-list maps presized for one price per priority entry plus reserve.
				// byInnerByListAll is built for ALL inner records during the first pass; later filtered to only
				// those that contributed to the cumulated selling price (mirrors the original behaviour: only
				// inner records that produced a selling-price component participate in SUM accompanying-price).
				final Map<Integer, PriceContract> bestPerInner = CollectionUtils.createLinkedHashMap(distinctInnerEstimate);
				final Map<Integer, Integer> bestPriorityPerInner = CollectionUtils.createHashMap(distinctInnerEstimate);
				final Map<Integer, Map<String, PriceContract>> byInnerByListAll = CollectionUtils
					.createLinkedHashMap(distinctInnerEstimate);
				for (final PriceContract price : entityPrices) {
					if (isNotCandidate(price, currency, atTheMoment)) {
						continue;
					}
					final int innerKey = innerRecordKey(price);
					// merge with deterministic tie-break: when two prices share `(innerRecord, priceList)` —
					// possible when `atTheMoment == null` and several prices have non-overlapping but currently
					// valid validity ranges — the winner is decided by `isBetterByPriceIdThenInner` (lower
					// priceId then lower innerRecordId), matching the per-bucket selection used everywhere else.
					byInnerByListAll
						.computeIfAbsent(innerKey, k -> CollectionUtils.createHashMap(innerBucketEstimate))
						.merge(
							price.priceList(),
							price,
							(existing, candidate) -> isBetterByPriceIdThenInner(candidate, existing) ? candidate : existing
						);
					updateBestPerInner(price, innerKey, priorityIndex, bestPerInner, bestPriorityPerInner);
				}
				if (bestPerInner.isEmpty()) {
					yield null;
				}
				// gather components, plus min/max in a single pass
				final List<PriceContract> components = new ArrayList<>(bestPerInner.size());
				PriceContract lowest = null;
				PriceContract highest = null;
				// share the deterministic tie-break with LOWEST_PRICE so that ties on `priceWithTax` resolve by
				// `priceId` (then `innerRecordId`) instead of leaving the running incumbent untouched. Keeps the
				// SUM range bounds independent of input iteration order — same invariant as the LOWEST_PRICE
				// branch above.
				for (final PriceContract candidate : bestPerInner.values()) {
					components.add(candidate);
					if (isBetterPriceForSaleCandidate(lowest, candidate, true)) {
						lowest = candidate;
					}
					if (isBetterPriceForSaleCandidate(highest, candidate, false)) {
						highest = candidate;
					}
				}
				final PriceContract sumPrice = calculateSumPrice(components);
				if (!filterPredicate.test(sumPrice)) {
					yield null;
				}
				// trim byInnerByList to only inner records that contributed to the cumulated price (matches the
				// `priceForSale.relatesTo(it)` semantics in the original SUM accompanying-price path)
				final Map<Integer, Map<String, PriceContract>> byInnerByList = CollectionUtils
					.createHashMap(bestPerInner.size());
				for (final Integer key : bestPerInner.keySet()) {
					final Map<String, PriceContract> entry = byInnerByListAll.get(key);
					if (entry != null) {
						byInnerByList.put(key, entry);
					}
				}
				yield new PriceForSaleComputationResult(
					innerRecordHandling, sumPrice, lowest, highest,
					Collections.emptyList(), byInnerByList
				);
			}
			case UNKNOWN ->
				throw new GenericEvitaInternalError("Cannot compute price for sale for UNKNOWN inner record handling.");
		};
	}

	/**
	 * Single, shared accompanying-price selection helper. Each accompanying-price request resolves to the price
	 * with the highest price-list priority among the supplied `candidates` (already filtered to whatever scope the
	 * caller wants). This collapses the previously divergent NONE / LOWEST_PRICE / SUM accompanying-price code
	 * paths into one routine — the strategy-specific logic lives only in choosing the candidate collection.
	 *
	 * Absent matches are returned as present `Optional.empty()` keys — every requested accompanying
	 * price always appears in the resulting map (with an empty optional when no candidate matched).
	 *
	 * @param candidates         pre-filtered candidate prices (already constrained by currency / valid-in / inner
	 *                           record relation, depending on strategy)
	 * @param accompanyingPrices array of accompanying-price requirements
	 * @return a map keyed by accompanying-price name with the matched price (or empty optional)
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> selectAccompanyingPrices(
		@Nonnull Collection<PriceContract> candidates,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length == 0) {
			return Collections.emptyMap();
		}
		final Map<String, Optional<PriceContract>> result = CollectionUtils.createHashMap(accompanyingPrices.length);
		for (final AccompanyingPrice accompanyingPrice : accompanyingPrices) {
			final Map<String, Integer> accompanyingPriorityIndex = getPriceListPriorityIndex(
				accompanyingPrice.priceListPriority()
			);
			final PriceContract best = pickBestByPriority(candidates, accompanyingPriorityIndex, null);
			result.put(accompanyingPrice.priceName(), ofNullable(best));
		}
		return result;
	}

	/**
	 * Filters the supplied entity prices by currency / validity (no inner-record / price-list / indexed checks).
	 * Used as the candidate basis for accompanying-price calculation under the `NONE` strategy.
	 */
	@Nonnull
	private static List<PriceContract> filterCandidatesByCurrencyAndValidity(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment
	) {
		final List<PriceContract> result = new ArrayList<>(entityPrices.size());
		for (final PriceContract price : entityPrices) {
			if (isNotCandidate(price, currency, atTheMoment)) {
				continue;
			}
			result.add(price);
		}
		return result;
	}

	/**
	 * Filters the supplied prices to those that (a) exist, (b) match the requested `currency`, (c) are valid
	 * at `atTheMoment`, and (d) share an inner record with the supplied selling price. Used as the candidate
	 * basis for accompanying-price calculation under `LOWEST_PRICE`. The currency / validity gate keeps this
	 * helper consistent with the NONE branch (which calls
	 * {@link #filterCandidatesByCurrencyAndValidity}) and with the {@link #computeInternal} fast path that
	 * already applies {@link #isNotCandidate} before bucketing — the `entityPrices` collection passed by
	 * external callers may contain prices in other currencies / dropped or out-of-validity prices.
	 */
	@Nonnull
	private static List<PriceContract> filterCandidatesByInnerRecordRelation(
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull PriceContract priceForSale,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment
	) {
		final List<PriceContract> result = new ArrayList<>(entityPrices.size());
		for (final PriceContract price : entityPrices) {
			if (isNotCandidate(price, currency, atTheMoment)) {
				continue;
			}
			if (Objects.equals(priceForSale.innerRecordId(), price.innerRecordId())) {
				result.add(price);
			}
		}
		return result;
	}

	/**
	 * Calculates accompanying prices under `SUM` strategy — for every accompanying-price requirement we sum
	 * the per-inner-record component prices that match the requirement's price-list priority. When the
	 * accompanying-price selection misses some inner records (their price list isn't part of the priority), we
	 * fall back to the components from the cumulated selling price for those records, mirroring the original
	 * behaviour.
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> calculateAccompanyingPricesForSumInnerRecordHandling(
		@Nonnull PriceContract priceForSale,
		@Nonnull Collection<PriceContract> entityPrices,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		if (accompanyingPrices.length == 0) {
			return Collections.emptyMap();
		}
		// build per-inner-record price-list -> price map filtered down to records relating to the selling price.
		// When priceForSale is a CumulatedPrice we know the exact distinct-inner-record count; otherwise a
		// conservative estimate based on entityPrices.size() applies. Inner maps are sized for the entity's
		// price-list cardinality (we don't have a single priorityIndex here, so a small constant + reserve).
		final int distinctInnerEstimate = priceForSale instanceof CumulatedPrice cumulatedPrice
			? Math.max(2, cumulatedPrice.innerRecordPrices().size())
			: Math.max(2, entityPrices.size() / 4 + 2);
		final Map<Integer, Map<String, PriceContract>> byInnerByList = CollectionUtils.createLinkedHashMap(
			distinctInnerEstimate
		);
		for (final PriceContract price : entityPrices) {
			if (isNotCandidate(price, currency, atTheMoment)) {
				continue;
			}
			if (price.innerRecordId() != null && !priceForSale.relatesTo(price)) {
				continue;
			}
			final int innerKey = innerRecordKey(price);
			// see byInnerByListAll merge comment in computeInternal — same deterministic tie-break for
			// `(innerRecord, priceList)` collisions that arise from multiple non-overlapping validities
			// when `atTheMoment` is `null`.
			byInnerByList
				.computeIfAbsent(innerKey, k -> CollectionUtils.createHashMap(4))
				.merge(
					price.priceList(),
					price,
					(existing, candidate) -> isBetterByPriceIdThenInner(candidate, existing) ? candidate : existing
				);
		}
		return resolveSumAccompanyingPrices(priceForSale, byInnerByList, accompanyingPrices);
	}

	/**
	 * Computes a sum-style accompanying-price map from a pre-built per-inner-record / per-price-list lookup
	 * structure. Shared between the public `calculateAccompanyingPrices` entry point and the cached
	 * computation result so the candidate map is built only once per selling-price computation.
	 */
	@Nonnull
	private static Map<String, Optional<PriceContract>> resolveSumAccompanyingPrices(
		@Nonnull PriceContract priceForSale,
		@Nonnull Map<Integer, Map<String, PriceContract>> byInnerByList,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		final Map<String, Optional<PriceContract>> result = CollectionUtils.createHashMap(accompanyingPrices.length);
		for (final AccompanyingPrice accompanyingPrice : accompanyingPrices) {
			final Map<String, Integer> accompanyingPriorityIndex = getPriceListPriorityIndex(
				accompanyingPrice.priceListPriority()
			);
			final List<PriceContract> pricesToSum = new ArrayList<>(byInnerByList.size());
			for (final Map<String, PriceContract> byList : byInnerByList.values()) {
				final PriceContract best = pickBestByPriority(byList.values(), accompanyingPriorityIndex, null);
				if (best != null) {
					pricesToSum.add(best);
				}
			}
			if (pricesToSum.isEmpty()) {
				result.put(accompanyingPrice.priceName(), empty());
				continue;
			}
			// if the components from the priority list don't cover all the inner records that participate in
			// the selling cumulated price, complete the missing records from the original cumulated price so the
			// returned accompanying price still represents the same product configuration
			if (priceForSale instanceof CumulatedPrice cumulatedPrice
				&& pricesToSum.size() < cumulatedPrice.innerRecordPrices().size()) {
				final Set<Integer> covered = CollectionUtils.createHashSet(pricesToSum.size());
				for (final PriceContract p : pricesToSum) {
					covered.add(p.innerRecordId());
				}
				for (final Map.Entry<Integer, PriceContract> entry : cumulatedPrice.innerRecordPrices().entrySet()) {
					if (!covered.contains(entry.getKey())) {
						pricesToSum.add(entry.getValue());
					}
				}
			}
			result.put(accompanyingPrice.priceName(), of(calculateSumPrice(pricesToSum)));
		}
		return result;
	}

	/**
	 * Checks if the given moment is valid based on the validity range of the provided price contract.
	 *
	 * @param atTheMoment the specific moment to evaluate, which can be null. If null, this method defaults to true.
	 * @param price the price contract containing validity information, must not be null.
	 * @return true if the moment is valid based on the price contract validity range, or if no moment is provided; false otherwise.
	 */
	private static boolean isValidAtTheMoment(@Nullable OffsetDateTime atTheMoment, @Nonnull PriceContract price) {
		if (atTheMoment == null) {
			return true;
		}
		final DateTimeRange validity = price.validity();
		return validity == null || validity.isValidFor(atTheMoment);
	}

	/**
	 * Returns true when the given price is a viable candidate for selling-price / accompanying-price
	 * computation — i.e. it exists, matches the requested `currency`, and is valid at `atTheMoment`.
	 * Centralises the triple-predicate that previously appeared inline in every per-strategy loop.
	 */
	private static boolean isNotCandidate(
		@Nonnull PriceContract price,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment
	) {
		return !price.exists() || !currency.equals(price.currency()) || !isValidAtTheMoment(atTheMoment, price);
	}

	/**
	 * Returns the inner-record bucketing key for the given price — `0` when no inner record is set, otherwise
	 * the raw `innerRecordId`. Note: this conflates `null` with an explicit `0` inner record, mirroring the
	 * previous inline expression; the deferred behavioural fix lives in a separate task.
	 */
	private static int innerRecordKey(@Nonnull PriceContract price) {
		final Integer ird = price.innerRecordId();
		return ird == null ? 0 : ird;
	}

	/**
	 * Decides whether `candidate` should replace `current` as the running best price-for-sale candidate
	 * under LOWEST_PRICE strategy. The comparison is total and deterministic, independent of input
	 * iteration order:
	 *
	 * 1. lower (or higher, depending on `preferLower`) `priceWithTax` wins,
	 * 2. on ties, lower `priceId` wins,
	 * 3. on further ties, lower `innerRecordId` wins (`null` sorts before any non-null id).
	 *
	 * Steps 2 and 3 are the deterministic tie-break — they fire only on exact `priceWithTax` equality,
	 * so they have zero behavioural impact when prices differ. The pre-existing `< 0` / `> 0` semantics
	 * for `priceWithTax` is preserved (no `<=` flip) — the tie-break only takes effect when the previous
	 * implementation would have left `current` untouched.
	 *
	 * @param current     running best candidate, or `null` before the first comparison
	 * @param candidate   prospective candidate
	 * @param preferLower `true` when picking the cheapest (lowest selling price), `false` when picking
	 *                    the most expensive (highest range bound)
	 * @return `true` when `candidate` strictly beats `current`
	 */
	private static boolean isBetterPriceForSaleCandidate(
		@Nullable PriceContract current,
		@Nonnull PriceContract candidate,
		boolean preferLower
	) {
		if (current == null) {
			return true;
		}
		final int byAmount = candidate.priceWithTax().compareTo(current.priceWithTax());
		if (byAmount != 0) {
			return preferLower ? byAmount < 0 : byAmount > 0;
		}
		// amounts tie — fall back to the priceId / innerRecordId tie-break shared with priority-based picks
		return isBetterByPriceIdThenInner(candidate, current);
	}

	/**
	 * Deterministic tie-break helper used when two candidates already share the same effective priority
	 * (e.g. price-list priority): lower `priceId` wins, then lower `innerRecordId` (`null` sorts before
	 * any non-null id). Returns `true` when `candidate` strictly beats `current` under this rule, and
	 * also when `current` is `null` (no incumbent).
	 */
	private static boolean isBetterByPriceIdThenInner(
		@Nonnull PriceContract candidate,
		@Nullable PriceContract current
	) {
		if (current == null) {
			return true;
		}
		final int byPriceId = Integer.compare(candidate.priceId(), current.priceId());
		if (byPriceId != 0) {
			return byPriceId < 0;
		}
		final Integer candidateInner = candidate.innerRecordId();
		final Integer currentInner = current.innerRecordId();
		if (candidateInner == null && currentInner != null) {
			return true;
		}
		if (candidateInner != null && currentInner == null) {
			return false;
		}
		if (candidateInner != null) {
			return candidateInner < currentInner;
		}
		return false;
	}

	/**
	 * Updates the running per-inner-record best-selling-price maps for `price`, skipping non-indexed
	 * prices and price lists missing from `priorityIndex`. On equal priority a deterministic tie-break is
	 * applied (lower {@code priceId}, then lower {@code innerRecordId} with {@code null} first) so the
	 * winner is independent of input iteration order. Shared by the per-inner-record loops of the
	 * `LOWEST_PRICE` and `SUM` strategies in {@link #computeInternal}.
	 *
	 * @param price                 candidate price under consideration
	 * @param innerKey              inner-record bucket key for {@code price} (already resolved by the caller)
	 * @param priorityIndex         price-list to priority-rank lookup; lower rank wins
	 * @param bestPerInner          mutable map of inner-record key to current best price
	 * @param bestPriorityPerInner  mutable map of inner-record key to current best price's priority
	 */
	private static void updateBestPerInner(
		@Nonnull PriceContract price,
		int innerKey,
		@Nonnull Map<String, Integer> priorityIndex,
		@Nonnull Map<Integer, PriceContract> bestPerInner,
		@Nonnull Map<Integer, Integer> bestPriorityPerInner
	) {
		if (!price.indexed()) {
			return;
		}
		final Integer priorityBoxed = priorityIndex.get(price.priceList());
		if (priorityBoxed == null) {
			return;
		}
		final int priority = priorityBoxed;
		final Integer existing = bestPriorityPerInner.get(innerKey);
		if (existing == null || priority < existing) {
			bestPriorityPerInner.put(innerKey, priority);
			bestPerInner.put(innerKey, price);
		} else if (priority == existing
			&& isBetterByPriceIdThenInner(price, bestPerInner.get(innerKey))) {
			bestPerInner.put(innerKey, price);
		}
	}

	/**
	 * Picks the candidate whose price list has the lowest priority in {@code priorityIndex} — i.e. the
	 * winner of the standard "first matching price list wins" rule. On equal priority a deterministic
	 * tie-break is applied (lower {@code priceId}, then lower {@code innerRecordId} with {@code null}
	 * first) so the result is independent of input iteration order. Returns {@code null} when no
	 * candidate matches the priority index.
	 *
	 * @param candidates    candidates to consider; iteration order need not be deterministic
	 * @param priorityIndex price-list to priority-rank lookup; lower rank wins
	 * @param filter        optional pre-filter applied before the priority lookup; pass {@code null} to
	 *                      consider every candidate
	 */
	@Nullable
	private static PriceContract pickBestByPriority(
		@Nonnull Iterable<PriceContract> candidates,
		@Nonnull Map<String, Integer> priorityIndex,
		@Nullable Predicate<PriceContract> filter
	) {
		PriceContract best = null;
		int bestPriority = Integer.MAX_VALUE;
		for (final PriceContract candidate : candidates) {
			if (filter != null && !filter.test(candidate)) {
				continue;
			}
			final Integer priorityBoxed = priorityIndex.get(candidate.priceList());
			if (priorityBoxed == null) {
				continue;
			}
			final int priority = priorityBoxed;
			if (priority < bestPriority) {
				bestPriority = priority;
				best = candidate;
			} else if (priority == bestPriority && isBetterByPriceIdThenInner(candidate, best)) {
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Calculates a virtual price that is a sum of all prices in the list.
	 *
	 * @param pricesToSum list of prices to sum
	 * @return virtual price that is a sum of all prices in the list
	 */
	@Nonnull
	private static PriceContract calculateSumPrice(@Nonnull List<PriceContract> pricesToSum) {
		final PriceContract firstPrice = pricesToSum.get(0);
		// allocation-light reduce: avoid creating an intermediate stream pipeline; the seed values are taken from
		// the first element so the resulting BigDecimal scale matches the original Stream::reduce(BinaryOperator)
		// behaviour (no identity element, so the seed inherits from the first operand)
		BigDecimal sumWithoutTax = firstPrice.priceWithoutTax();
		BigDecimal sumWithTax = firstPrice.priceWithTax();
		final BigDecimal taxRate = firstPrice.taxRate();
		final Map<Integer, PriceContract> innerRecordPrices = CollectionUtils.createHashMap(pricesToSum.size());
		innerRecordPrices.put(firstPrice.innerRecordId(), firstPrice);
		for (int i = 1; i < pricesToSum.size(); i++) {
			final PriceContract price = pricesToSum.get(i);
			Assert.isTrue(
				taxRate.compareTo(price.taxRate()) == 0,
				"Prices have to have same tax rate in order to compute selling price!"
			);
			sumWithoutTax = sumWithoutTax.add(price.priceWithoutTax());
			sumWithTax = sumWithTax.add(price.priceWithTax());
			innerRecordPrices.put(price.innerRecordId(), price);
		}
		return new CumulatedPrice(
			1, firstPrice.priceKey(), innerRecordPrices, sumWithoutTax, taxRate, sumWithTax
		);
	}

	/**
	 * Creates a map of price list priorities where the key is the price list and the value is the priority.
	 *
	 * @param priceListPriority array of price list priorities
	 * @return map of price list priorities
	 */
	@Nonnull
	private static Map<String, Integer> getPriceListPriorityIndex(@Nonnull String[] priceListPriority) {
		final Map<String, Integer> pLists = createHashMap(priceListPriority.length);
		for (int i = 0; i < priceListPriority.length; i++) {
			final String pList = priceListPriority[i];
			pLists.put(pList, i);
		}
		return pLists;
	}

	/**
	 * Internal method that calculates accompanying prices for {@link PriceInnerRecordHandling#LOWEST_PRICE} strategy.
	 * For each inner record id it calculates price for sale, and for each accompanying price request, it calculates
	 * another price for the very same inner record is using different price lists setting and not taking the sellability
	 * of the price into an account.
	 *
	 * @param prices             collection of all entity prices
	 * @param mapper             transformer function for the result type of the method
	 * @param currency           currency used for price for sale calculation
	 * @param atTheMoment        moment used for price for sale calculation
	 * @param priceListPriority  identification of the price lists (either external or internal) sorted by priority
	 * @param accompanyingPrices array of requirements for accompanying prices
	 * @param <T>                type of the result
	 * @return list of results of the calculation mapped by transformation function
	 */
	@Nonnull
	private static <T> List<T> getAllPricesForSaleForLowestPrice(
		@Nonnull Collection<PriceContract> prices,
		@Nonnull Function<PriceForSaleWithAccompanyingPrices, T> mapper,
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice... accompanyingPrices
	) {
		final Map<String, Integer> priorityIndex = getPriceListPriorityIndex(priceListPriority);
		// group all currency / valid-in matching prices by inner record id (null → 0) in a single pass.
		// Outer map sized for the estimated number of distinct inner records (≈ N/L); inner buckets presized
		// for one price per priority entry plus reserve.
		final int innerBucketEstimate = Math.max(2, priceListPriority.length + 2);
		final int distinctInnerEstimate = Math.max(
			2, prices.size() / Math.max(1, priceListPriority.length) + 2
		);
		final Map<Integer, List<PriceContract>> pricesByInnerId = CollectionUtils.createHashMap(distinctInnerEstimate);
		for (final PriceContract price : prices) {
			if (isNotCandidate(price, currency, atTheMoment)) {
				continue;
			}
			pricesByInnerId.computeIfAbsent(innerRecordKey(price), k -> new ArrayList<>(innerBucketEstimate)).add(price);
		}
		// pick the best indexed candidate per inner record (lowest priority wins). On equal priority — which
		// can happen if the same price-list contains multiple matching prices for the same inner record — the
		// deterministic tie-break inside pickBestByPriority ensures the winner is independent of input
		// iteration order.
		final List<PriceContract> winners = new ArrayList<>(pricesByInnerId.size());
		for (final List<PriceContract> innerGroup : pricesByInnerId.values()) {
			final PriceContract best = pickBestByPriority(innerGroup, priorityIndex, PriceContract::indexed);
			if (best != null) {
				winners.add(best);
			}
		}
		// deterministic ordering of the resulting list — by priceId, then by innerRecordId (null last)
		winners.sort(
			Comparator.comparing(PriceContract::priceId)
				.thenComparing(
					PriceContract::innerRecordId,
					Comparator.nullsLast(Integer::compareTo)
				)
		);
		final List<T> result = new ArrayList<>(winners.size());
		for (final PriceContract winner : winners) {
			final List<PriceContract> innerGroup = pricesByInnerId.getOrDefault(
				innerRecordKey(winner), Collections.emptyList()
			);
			final PriceForSaleWithAccompanyingPrices wrapped = new PriceForSaleWithAccompanyingPrices(
				winner,
				selectAccompanyingPrices(innerGroup, accompanyingPrices)
			);
			result.add(mapper.apply(wrapped));
		}
		return result;
	}

	/**
	 * Returns true if entity prices were fetched along with the entity. Calling this method before calling any
	 * other method that requires prices to be fetched will allow you to avoid {@link ContextMissingException}.
	 *
	 * Method also returns false if the prices are not enabled for the entity by the schema. Checking this method
	 * also allows you to avoid {@link EntityHasNoPricesException} in such case.
	 */
	boolean pricesAvailable();

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceKey business key of the price
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey)
		throws ContextMissingException;

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceId   - identification of the price in the external systems
	 * @param priceList - identification of the price list (either external or internal)
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency)
		throws ContextMissingException;

	/**
	 * Returns price by its business key identification.
	 *
	 * @param priceList - identification of the price list (either external or internal)
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws UnexpectedResultCountException when there is more than one price for the specified price list and currency
	 * @throws ContextMissingException        when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	Optional<PriceContract> getPrice(@Nonnull String priceList, @Nonnull Currency currency)
		throws UnexpectedResultCountException, ContextMissingException;

	/**
	 * Returns all prices from the specified price list.
	 *
	 * @param priceList - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull String priceList) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> priceList.equals(it.priceList()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns all prices from the specified currency.
	 *
	 * @param currency - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull Currency currency) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> currency.equals(it.currency()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns all prices from the specified currency.
	 *
	 * @param currency  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param priceList - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Collection<PriceContract> getPrices(@Nonnull Currency currency, @Nonnull String priceList) throws ContextMissingException {
		return getPrices()
			.stream()
			.filter(it -> currency.equals(it.currency()) && priceList.equals(it.priceList()))
			.collect(Collectors.toList());
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceContract> getPriceForSale(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String... priceListPriority
	) throws ContextMissingException {
		// share work with subsequent getPriceRangeForSale / getAccompanyingPrice calls when context matches —
		// the cache populates the rich computation lazily and every derived view reads from the same slot
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr && pfscwcr.matches(currency, atTheMoment, priceListPriority)) {
			return pfscwcr.computePriceForSale(getPrices(), getPriceInnerRecordHandling());
		}
		return computePriceForSale(getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull);
	}

	/**
	 * Returns a default accompanying price that relates to the current price for sale. The method can be used only
	 * when {@link DefaultAccompanyingPriceLists} requirement is used in the query - so that the ordered price list
	 * sequence is known for the accompanying price. Query must also contain the filters allowing to calculate
	 * the price for sale.
	 *
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	default Optional<PriceContract> getAccompanyingPrice() throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.compute(getPrices(), getPriceInnerRecordHandling())
				.flatMap(it -> it.getAccompanyingPrice(AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE));
		} else {
			return empty();
		}
	}

	/**
	 * Returns a default accompanying price that relates to the current price for sale. The method can be used only
	 * when {@link DefaultAccompanyingPriceLists} requirement is used in the query - so that the ordered price list
	 * sequence is known for the accompanying price. Query must also contain the filters allowing to calculate
	 * the price for sale.
	 *
	 * @param accompanyingPriceName - name of the accompanying price that should be returned
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	default Optional<PriceContract> getAccompanyingPrice(@Nonnull String accompanyingPriceName) throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.compute(getPrices(), getPriceInnerRecordHandling())
				.flatMap(it -> it.getAccompanyingPrice(accompanyingPriceName));
		} else {
			return empty();
		}
	}

	/**
	 * Returns a default accompanying price that relates to the current price for sale. The method can be used only
	 * when {@link DefaultAccompanyingPriceLists} requirement is used in the query - so that the ordered price list
	 * sequence is known for the accompanying price. Query must also contain the filters allowing to calculate
	 * the price for sale.
	 *
	 * Note this method is similar to {@link #getAccompanyingPrice(String)} but it returns an empty
	 * value if the context is not available, instead of throwing an exception.
	 *
	 * @param accompanyingPriceName - name of the accompanying price that should be returned
	 */
	default Optional<PriceContract> getAccompanyingPriceIfAvailable(@Nonnull String accompanyingPriceName) {
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.compute(getPrices(), getPriceInnerRecordHandling())
				.flatMap(it -> it.getAccompanyingPrice(accompanyingPriceName));
		} else {
			return empty();
		}
	}

	/**
	 * Retrieves the price for sale along with accompanying prices based on the current context.
	 * The method computes the price using the prices available and the inner record handling logic,
	 * provided a valid PriceForSaleContext is present.
	 * If the context is missing or invalid, a ContextMissingException is thrown.
	 *
	 * @return an Optional containing the computed PriceForSaleWithAccompanyingPrices if the context is valid.
	 * @throws ContextMissingException if the price for sale context is missing or invalid.
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPrices() throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.compute(getPrices(), getPriceInnerRecordHandling());
		} else {
			throw new ContextMissingException();
		}
	}

	/**
	 * Retrieves the price for sale along with accompanying prices based on the current context.
	 * The method computes the price using the prices available and the inner record handling logic,
	 * provided a valid PriceForSaleContext is present.
	 * If the context is missing or invalid, a ContextMissingException is thrown.
	 *
	 * Note this method is similar to {@link #getPriceForSaleWithAccompanyingPrices()} but it returns an empty
	 * value if the context is not available, instead of throwing an exception.
	 *
	 * @return an Optional containing the computed PriceForSaleWithAccompanyingPrices if the context is valid.
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPricesIfAvailable() {
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.compute(getPrices(), getPriceInnerRecordHandling());
		} else {
			return empty();
		}
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param currency           - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment        - identification of the moment when the entity is about to be sold
	 * @param priceListPriority  - identification of the price list (either external or internal)
	 * @param accompanyingPrices - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPrices(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr && pfscwcr.matches(currency, atTheMoment, priceListPriority)) {
			final Optional<PriceForSaleWithAccompanyingPrices> defaultPriceCalculation = pfscwcr.compute(getPrices(), getPriceInnerRecordHandling());
			if (defaultPriceCalculation.isEmpty()) {
				return empty();
			} else {
				final PriceForSaleWithAccompanyingPrices dpc = defaultPriceCalculation.get();
				// find missing accompanying prices
				final Set<AccompanyingPrice> accompanyingPricesSet = context.accompanyingPrices()
					.map(it -> (Set<AccompanyingPrice>) new HashSet<>(Arrays.asList(it)))
					.orElse(Collections.emptySet());
				final Set<String> includedAccompaniedPrices = CollectionUtils.createHashSet(accompanyingPrices.length);
				final AccompanyingPrice[] missingAccompanyingPrices = Arrays.stream(accompanyingPrices)
					.filter(ap -> {
						if (accompanyingPricesSet.contains(ap)) {
							includedAccompaniedPrices.add(ap.priceName());
							return false; // this price is already included
						} else {
							return true; // this price is missing
						}
					})
					.toArray(AccompanyingPrice[]::new);
				if (missingAccompanyingPrices.length == 0) {
					// limit the included accompanying prices to those that are requested
					if (dpc.accompanyingPrices().size() == includedAccompaniedPrices.size()) {
						// all accompanying prices are already included
						return of(dpc);
					} else {
						return of(
							new PriceForSaleWithAccompanyingPrices(
								dpc.priceForSale(),
								dpc.accompanyingPrices().entrySet()
									.stream()
									.filter(it -> includedAccompaniedPrices.contains(it.getKey()))
									.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
							)
						);
					}
				} else if (includedAccompaniedPrices.isEmpty()) {
					// just calculate the missing accompanying prices
					return of(
						new PriceForSaleWithAccompanyingPrices(
							dpc.priceForSale(),
							calculateAccompanyingPrices(
								dpc.priceForSale(),
								getPrices(), getPriceInnerRecordHandling(),
								currency, atTheMoment, accompanyingPrices
							)
						)
					);
				} else {
					// merge included and missing accompanying prices
					final Map<String, Optional<PriceContract>> accompanyingPricesMap = CollectionUtils.createHashMap(accompanyingPrices.length);
					accompanyingPricesMap.putAll(
						calculateAccompanyingPrices(
							dpc.priceForSale(),
							getPrices(), getPriceInnerRecordHandling(),
							currency, atTheMoment, accompanyingPrices
						)
					);
					for (String includedAccompaniedPriceName : includedAccompaniedPrices) {
						accompanyingPricesMap.put(
							includedAccompaniedPriceName,
							dpc.getAccompanyingPrice(includedAccompaniedPriceName)
						);
					}
					return of(
						new PriceForSaleWithAccompanyingPrices(
							dpc.priceForSale(),
							accompanyingPricesMap
						)
					);
				}
			}
		} else {
			return computePriceForSale(
				getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull,
				accompanyingPrices
			);
		}
	}

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param accompanyingPricesRequest - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	default Optional<PriceForSaleWithAccompanyingPrices> getPriceForSaleWithAccompanyingPrices(
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			final Optional<PriceForSaleWithAccompanyingPrices> priceForSaleWithAccompanyingPrices =
				pfscwcr.compute(getPrices(), getPriceInnerRecordHandling());
			if (accompanyingPricesRequest.length == 0) {
				return priceForSaleWithAccompanyingPrices;
			} else {
				return priceForSaleWithAccompanyingPrices
					.map(
						dpc -> new PriceForSaleWithAccompanyingPrices(
							dpc.priceForSale(),
							calculateAccompanyingPrices(
								dpc.priceForSale(),
								getPrices(), getPriceInnerRecordHandling(),
								context.currency().orElseThrow(ContextMissingException::new),
								context.atTheMoment().orElse(null),
								accompanyingPricesRequest
							)
						)
					);
			}
		} else {
			return computePriceForSale(
				getPrices(), getPriceInnerRecordHandling(),
				context.currency().orElseThrow(ContextMissingException::new),
				context.atTheMoment().orElse(null),
				context.priceListPriority().orElseThrow(ContextMissingException::new),
				Objects::nonNull,
				accompanyingPricesRequest
			);
		}
	}

	/**
	 * Returns the full price range for which the entity could be sold (lowest price, highest price, selling price).
	 * Equivalent to {@link #getPriceForSale()} but additionally exposes the range bounds.
	 *
	 * Strategy semantics — see {@link PriceRangeForSale}.
	 *
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	Optional<PriceRangeForSale> getPriceRangeForSale() throws ContextMissingException;

	/**
	 * Returns the full price range for which the entity could be sold. Behaves like
	 * {@link #getPriceRangeForSale()} but returns an empty optional when the price-for-sale context is not
	 * available (instead of throwing).
	 */
	@Nonnull
	Optional<PriceRangeForSale> getPriceRangeForSaleIfAvailable();

	/**
	 * See {@link #getPriceRangeForSale()}; this overload additionally accepts explicit currency / valid-in
	 * / price-list inputs instead of resolving them from the query context. Mirrors
	 * {@link #getPriceForSale(Currency, OffsetDateTime, String...)}.
	 *
	 * @param currency          identification of the currency
	 * @param atTheMoment       moment when the entity is about to be sold
	 * @param priceListPriority identification of the price list priority (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceRangeForSale> getPriceRangeForSale(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String... priceListPriority
	) throws ContextMissingException {
		// share work with prior / subsequent getPriceForSale / getAccompanyingPrice calls when context matches —
		// fulfils the proposal contract that the range costs ~the same as the selling price
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr && pfscwcr.matches(currency, atTheMoment, priceListPriority)) {
			return pfscwcr.computeRange(getPrices(), getPriceInnerRecordHandling());
		}
		return computePriceRangeForSale(
			getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull
		);
	}

	/**
	 * See {@link #getPriceRangeForSale()}; this overload additionally returns the requested accompanying
	 * prices. The accompanying-price map is computed exactly as for
	 * {@link #getPriceForSaleWithAccompanyingPrices(AccompanyingPrice[])} — the only difference is the
	 * additional range information.
	 *
	 * @param accompanyingPricesRequest array of requirements for accompanying prices
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	default Optional<PriceRangeForSaleWithAccompanyingPrices> getPriceRangeForSaleWithAccompanyingPrices(
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) throws ContextMissingException {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		// share work with prior / subsequent calls — the rich cache holds the per-bucket bookkeeping needed
		// to (re)derive accompanying-price selections without a fresh full pass
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr) {
			return pfscwcr.computeRangeWithAccompanyingPrices(
				getPrices(), getPriceInnerRecordHandling(), accompanyingPricesRequest
			);
		}
		return computePriceRangeForSale(
			getPrices(), getPriceInnerRecordHandling(),
			context.currency().orElseThrow(ContextMissingException::new),
			context.atTheMoment().orElse(null),
			context.priceListPriority().orElseThrow(ContextMissingException::new),
			Objects::nonNull,
			accompanyingPricesRequest
		);
	}

	/**
	 * See {@link #getPriceRangeForSale()}; this overload additionally accepts explicit currency / valid-in
	 * / price-list inputs together with accompanying-price requirements.
	 *
	 * @param currency           identification of the currency
	 * @param atTheMoment        moment when the entity is about to be sold
	 * @param priceListPriority  identification of the price list priority
	 * @param accompanyingPrices array of accompanying-price requirements
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default Optional<PriceRangeForSaleWithAccompanyingPrices> getPriceRangeForSaleWithAccompanyingPrices(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) throws ContextMissingException {
		// share work with prior / subsequent calls when context matches
		final PriceForSaleContext context = getPriceForSaleContext().orElse(null);
		if (context instanceof PriceForSaleContextWithCachedResult pfscwcr && pfscwcr.matches(currency, atTheMoment, priceListPriority)) {
			return pfscwcr.computeRangeWithAccompanyingPrices(
				getPrices(), getPriceInnerRecordHandling(), accompanyingPrices
			);
		}
		return computePriceRangeForSale(
			getPrices(), getPriceInnerRecordHandling(), currency, atTheMoment, priceListPriority, Objects::nonNull,
			accompanyingPrices
		);
	}

	/**
	 * Returns true if the entity has context available so that calling {@link #getPriceForSale()} is possible without
	 * throwing an exception. The exception {@link ContextMissingException} might be still thrown from other methods
	 * when the input arguments refer to the data that might exist but were not fetched along with the entity.
	 *
	 * @see #pricesAvailable() for checking whether any of the prices were fetched
	 */
	boolean isPriceForSaleContextAvailable();

	/**
	 * Returns context used for calculation of the {@link #getPriceForSale()} method. The context is available only
	 * when the entity is related to a {@link Query} with price related constraints so that `currency` and `priceList`
	 * priority can be extracted from the query.
	 *
	 * @return context used for calculation of the {@link #getPriceForSale()} method
	 */
	@Nonnull
	Optional<PriceForSaleContext> getPriceForSaleContext();

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 */
	@Nonnull
	Optional<PriceContract> getPriceForSale() throws ContextMissingException;

	/**
	 * Returns a price for which the entity should be sold. This method can be used only in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * This method is similar to {@link #getPriceForSale()} but doesn't throw an exception when the context is not
	 * present in the input query and just returns NULL result instead.
	 */
	@Nonnull
	Optional<PriceContract> getPriceForSaleIfAvailable();

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default List<PriceContract> getAllPricesForSale(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String... priceListPriority
	)
		throws ContextMissingException {

		final PriceInnerRecordHandling priceInnerRecordHandling = getPriceInnerRecordHandling();
		if (priceInnerRecordHandling == PriceInnerRecordHandling.LOWEST_PRICE) {
			// in case of lowest price inner record handling there might be multiple prices for sale - for each inner record id
			return getAllPricesForSaleForLowestPrice(
				getPrices(), PriceForSaleWithAccompanyingPrices::priceForSale, currency, atTheMoment, priceListPriority,
				NO_ACCOMPANYING_PRICES
			);
		} else {
			// in all other cases there will be always exactly one price - the selling one
			return getPriceForSale(currency, atTheMoment, priceListPriority).map(List::of).orElse(Collections.emptyList());
		}
	}

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default List<PriceForSaleWithAccompanyingPrices> getAllPricesForSaleWithAccompanyingPrices() {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		final PriceInnerRecordHandling priceInnerRecordHandling = getPriceInnerRecordHandling();
		if (priceInnerRecordHandling == PriceInnerRecordHandling.LOWEST_PRICE) {
			// in case of lowest price inner record handling there might be multiple prices for sale - for each inner record id
			return getAllPricesForSaleForLowestPrice(
				getPrices(), Function.identity(),
				context.currency().orElseThrow(ContextMissingException::new),
				context.atTheMoment().orElse(null),
				context.priceListPriority().orElseThrow(ContextMissingException::new),
				context.accompanyingPrices().orElse(NO_ACCOMPANYING_PRICES)
			);
		} else {
			// in all other cases there will be always exactly one price - the selling one
			return getPriceForSaleWithAccompanyingPrices(
				context.currency().orElseThrow(ContextMissingException::new),
				context.atTheMoment().orElse(null),
				context.priceListPriority().orElseThrow(ContextMissingException::new),
				context.accompanyingPrices().orElse(NO_ACCOMPANYING_PRICES)
			)
				.map(List::of)
				.orElse(Collections.emptyList());
		}
	}

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * This method allows to calculate also additional accompanying prices that relate to the selected price for sale
	 * and adhere to particular price inner record handling logic.
	 *
	 * @param currency                  - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param atTheMoment               - identification of the moment when the entity is about to be sold
	 * @param priceListPriority         - identification of the price list (either external or internal)
	 * @param accompanyingPricesRequest - array of requirements for calculation of accompanying prices
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	@Nonnull
	default List<PriceForSaleWithAccompanyingPrices> getAllPricesForSaleWithAccompanyingPrices(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) {
		final PriceInnerRecordHandling priceInnerRecordHandling = getPriceInnerRecordHandling();
		if (priceInnerRecordHandling == PriceInnerRecordHandling.LOWEST_PRICE) {
			// in case of lowest price inner record handling there might be multiple prices for sale - for each inner record id
			return getAllPricesForSaleForLowestPrice(
				getPrices(), Function.identity(), currency, atTheMoment, priceListPriority, accompanyingPricesRequest
			);
		} else {
			// in all other cases there will be always exactly one price - the selling one
			return getPriceForSaleWithAccompanyingPrices(
				currency, atTheMoment, priceListPriority, accompanyingPricesRequest
			)
				.map(List::of)
				.orElse(Collections.emptyList());
		}
	}

	/**
	 * Returns all prices for which the entity could be sold. This method can be used in context of a {@link Query}
	 * with price related constraints so that `currency` and `priceList` priority can be extracted from the query.
	 * The moment is either extracted from the query as well (if present) or current date and time is used.
	 *
	 * The method differs from {@link #getPriceForSale()} in the sense of never returning {@link ContextMissingException}
	 * and returning list of all possibly matching selling prices (not only single one). Returned list may be also
	 * empty if there is no such price.
	 *
	 * @throws ContextMissingException when no prices were fetched along with entity but might exist, but is not thrown
	 *                                 when some (but not all) prices were fetched along with entity
	 */
	@Nonnull
	List<PriceContract> getAllPricesForSale() throws ContextMissingException;

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param from              - lower bound of the price (inclusive)
	 * @param to                - upper bound of the price (inclusive)
	 * @param currency          - identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217)
	 * @param queryPriceMode    - controls whether price with or without tax is used
	 * @param atTheMoment       - identification of the moment when the entity is about to be sold
	 * @param priceListPriority - identification of the price list (either external or internal)
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	default boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode, @Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority)
		throws ContextMissingException {

		final Collection<PriceContract> entityPrices = getPrices();
		if (entityPrices.isEmpty()) {
			return false;
		}

		switch (getPriceInnerRecordHandling()) {
			case NONE, SUM -> {
				return getPriceForSale(currency, atTheMoment, priceListPriority)
					.map(it -> queryPriceMode == QueryPriceMode.WITHOUT_TAX ? it.priceWithoutTax() : it.priceWithTax())
					.map(it -> from.compareTo(it) <= 0 && to.compareTo(it) >= 0)
					.orElse(false);
			}
			case LOWEST_PRICE -> {
				final Map<String, Integer> pLists = getPriceListPriorityIndex(priceListPriority);
				final Map<Integer, List<PriceContract>> pricesByInnerRecordId = entityPrices
					.stream()
					.filter(PriceContract::exists)
					.filter(PriceContract::indexed)
					.filter(it -> currency.equals(it.currency()))
					.filter(it -> isValidAtTheMoment(atTheMoment, it))
					.filter(it -> pLists.containsKey(it.priceList()))
					.collect(Collectors.groupingBy(it -> ofNullable(it.innerRecordId()).orElse(0)));
				return pricesByInnerRecordId
					.values()
					.stream()
					.anyMatch(prices -> {
						final Optional<PriceContract> minPrice = prices.stream()
							.min(Comparator.comparing(o -> pLists.get(o.priceList())));
						return minPrice
							.map(it -> queryPriceMode == QueryPriceMode.WITHOUT_TAX ? it.priceWithoutTax() : it.priceWithTax())
							.map(it -> from.compareTo(it) <= 0 && to.compareTo(it) >= 0)
							.orElse(null);
					});
			}
			default ->
				throw new GenericEvitaInternalError("Unknown price inner record handling mode: " + getPriceInnerRecordHandling());
		}
	}

	/**
	 * Returns a price for which the entity should be sold. Only indexed prices in requested currency, valid
	 * at the passed moment are taken into an account. Prices are also limited by the passed set of price lists and
	 * the first price found in the order of the requested price list ids will be returned.
	 *
	 * @param from           - lower bound of the price (inclusive)
	 * @param to             - upper bound of the price (inclusive)
	 * @param queryPriceMode - controls whether price with or without tax is used
	 * @throws ContextMissingException when entity is not related to any {@link Query} or the query
	 *                                 lacks price related constraints
	 * @throws ContextMissingException when the prices were not fetched along with entity but might exist
	 */
	boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode)
		throws ContextMissingException;

	/**
	 * Returns all prices of the entity.
	 *
	 * @throws ContextMissingException when no prices were not fetched along with entity but might exist, the exception
	 *                                 is not thrown when some (but not all) prices were fetched along with entity
	 */
	@Nonnull
	Collection<PriceContract> getPrices() throws ContextMissingException;

	/**
	 * Returns price inner record handling that controls how prices that share same `inner entity id` will behave during
	 * filtering and sorting.
	 */
	@Nonnull
	PriceInnerRecordHandling getPriceInnerRecordHandling();

	/**
	 * Captures the inputs that drive a price-for-sale computation for a single entity within one query
	 * evaluation: the price-list priority list, the currency, the optional valid-in moment, and the optional
	 * set of accompanying-price requirements. Together these four values fully determine which entity
	 * prices are considered, how they are ranked, and which non-selling prices are exposed alongside the
	 * winner.
	 *
	 * The context is materialised by the engine when a query carries price-related constraints (e.g.
	 * `priceInPriceLists`, `priceInCurrency`, `priceValidIn`, `accompanyingPriceContent`) and is attached to
	 * each fetched entity via {@link PricesContract#getPriceForSaleContext()}. Calls to the no-arg
	 * {@link PricesContract#getPriceForSale()} / {@link PricesContract#getPriceRangeForSale()} family read
	 * their parameters from this context; absence of the context is what triggers
	 * {@link io.evitadb.api.exception.ContextMissingException} on those methods.
	 *
	 * Implementations are expected to be value-like: equal context inputs must produce equal results across
	 * repeated reads. {@link PriceForSaleContextWithCachedResult} is the canonical implementation — it adds
	 * a per-entity cache so that selling-price, range, and accompanying-price views derived from the same
	 * inputs share a single underlying computation pass. Callers wishing to query prices with explicit
	 * inputs (rather than the query-derived ones) bypass the context and use the parameterised overloads
	 * directly.
	 */
	@SuppressWarnings("InterfaceWithOnlyOneDirectInheritor")
	interface PriceForSaleContext {

		/**
		 * Returns the price list priority that is used for the price for sale calculation.
		 *
		 * @return array of price list priorities
		 */
		@Nonnull
		Optional<String[]> priceListPriority();

		/**
		 * Returns the currency used for the price for sale calculation.
		 *
		 * @return currency used for price for sale calculation
		 */
		@Nonnull
		Optional<Currency> currency();

		/**
		 * Returns the moment used for the price for sale calculation.
		 *
		 * @return moment used for price for sale calculation
		 */
		@Nonnull
		Optional<OffsetDateTime> atTheMoment();

		/**
		 * Returns an array of AccompanyingPrice objects that describe the computation of additional prices
		 * along with the price for sale. Each AccompanyingPrice contains the name of the price and the
		 * priority of price lists used for lookup.
		 *
		 * @return an array of AccompanyingPrice objects or null if none are present
		 */
		@Nonnull
		Optional<AccompanyingPrice[]> accompanyingPrices();
	}

	/**
	 * Describes requirement for computation of additional prices along with the price for sale.
	 *
	 * @param priceName         name of the price to distinguish it from other accompanying prices
	 * @param priceListPriority the priority of price lists that will be used to lookup for the price related to price
	 *                          for sale.
	 */
	record AccompanyingPrice(
		@Nonnull String priceName,
		@Nonnull String... priceListPriority
	) implements Serializable {
		public static final AccompanyingPrice[] EMPTY_ARRAY = new AccompanyingPrice[0];

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof final AccompanyingPrice that)) return false;

			return this.priceName.equals(that.priceName) && Arrays.equals(this.priceListPriority, that.priceListPriority);
		}

		@Override
		public int hashCode() {
			int result = this.priceName.hashCode();
			result = 31 * result + Arrays.hashCode(this.priceListPriority);
			return result;
		}

		@Nonnull
		@Override
		public String toString() {
			return "AccompanyingPrice `" + this.priceName + "`: " + Arrays.toString(this.priceListPriority);
		}

	}

	/**
	 * Return type where both the price for sale and accompanying prices are returned.
	 *
	 * @param priceForSale       price for which the entity should be sold
	 * @param accompanyingPrices accompanying prices that were computed along with the price for sale
	 */
	record PriceForSaleWithAccompanyingPrices(
		@Nonnull PriceContract priceForSale,
		@Nonnull Map<String, Optional<PriceContract>> accompanyingPrices
	) implements Serializable {

		/**
		 * Retrieves an accompanying price associated with the given price name if it exists.
		 *
		 * @param priceName the name of the accompanying price to be retrieved; must not be null
		 * @return an {@link Optional} containing the associated {@link PriceContract}, or an empty {@link Optional} if no
		 * accompanying price is associated with the given name
		 */
		@Nonnull
		public Optional<PriceContract> getAccompanyingPrice(@Nonnull String priceName) {
			return this.accompanyingPrices.getOrDefault(priceName, Optional.empty());
		}

		/**
		 * Returns an unmodifiable map of accompanying prices associated with this entity.
		 *
		 * Accompanying prices are additional price entries identified by a string key, e.g., representing
		 * supplementary pricing information like discounts or secondary prices.
		 *
		 * @return a map containing accompanying prices where the key is a string identifier and the value is a {@link PriceContract}
		 */
		@Nonnull
		public Map<String, Optional<PriceContract>> getAccompanyingPrices() {
			return Collections.unmodifiableMap(this.accompanyingPrices);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof final PriceForSaleWithAccompanyingPrices that)) return false;

			return this.priceForSale.equals(that.priceForSale) && this.accompanyingPrices.equals(that.accompanyingPrices);
		}

		@Override
		public int hashCode() {
			int result = this.priceForSale.hashCode();
			result = 31 * result + this.accompanyingPrices.hashCode();
			return result;
		}
	}

	/**
	 * Internal carrier of the per-strategy `priceForSale` / range bounds together with the accompanying-
	 * price bookkeeping needed to compute requested accompanying prices on demand. Stored only inside this
	 * contract; not exposed to callers.
	 *
	 * For NONE / LOWEST_PRICE: `accompanyingCandidates` holds the pre-filtered candidate collection and
	 * `sumAccompanyingByInner` is `null`.
	 *
	 * For SUM: `accompanyingCandidates` is unused (empty list) and `sumAccompanyingByInner` holds the
	 * per-inner-record / per-price-list lookup needed to sum component prices for the accompanying-price
	 * calculation.
	 *
	 * @apiNote internal — not part of the public API; subject to change without notice.
	 */
	record PriceForSaleComputationResult(
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nonnull PriceContract priceForSale,
		@Nonnull PriceContract lowestPrice,
		@Nonnull PriceContract highestPrice,
		@Nonnull List<PriceContract> accompanyingCandidates,
		@Nullable Map<Integer, Map<String, PriceContract>> sumAccompanyingByInner
	) {

		/**
		 * Computes the accompanying-price map for this computation result based on the strategy that produced
		 * it. The candidate collection is reused — for NONE / LOWEST_PRICE the same candidate list also feeds
		 * the shared {@link #selectAccompanyingPrices(Collection, AccompanyingPrice[])} helper; for SUM the
		 * pre-built per-inner-record map is consumed by the SUM-specific resolver.
		 */
		@Nonnull
		Map<String, Optional<PriceContract>> calculateAccompanyingPrices(
			@Nonnull AccompanyingPrice[] accompanyingPrices
		) {
			if (accompanyingPrices.length == 0) {
				return Collections.emptyMap();
			}
			return switch (this.innerRecordHandling) {
				case NONE, LOWEST_PRICE -> selectAccompanyingPrices(this.accompanyingCandidates, accompanyingPrices);
				case SUM -> resolveSumAccompanyingPrices(
					this.priceForSale,
					this.sumAccompanyingByInner == null ? Collections.emptyMap() : this.sumAccompanyingByInner,
					accompanyingPrices
				);
				case UNKNOWN ->
					throw new GenericEvitaInternalError("Cannot compute accompanying prices for UNKNOWN inner record handling.");
			};
		}
	}

}
