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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceForSaleContextWithCachedResult;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleContext;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Serializable predicate that filters entity prices based on query requirements.
 *
 * This predicate controls which prices are visible to clients by filtering based on price content mode, currency,
 * validity timestamp, price lists, and accompanying prices specified in the query. It supports three price content
 * modes:
 * - `NONE`: No prices are visible
 * - `ALL`: All existing prices are visible (no filtering)
 * - `RESPECTING_FILTER`: Prices are filtered by currency, validity, and price lists
 *
 * The predicate also manages the "price for sale" context, which determines the single best price for an entity
 * based on price list priority, currency, and validity constraints. Additional price lists and accompanying prices
 * can be fetched alongside the main filtered prices for enriched price visibility.
 *
 * **Thread-safety**: This class is immutable except for lazy-initialized `priceForSaleContext` field. The context
 * is safely initialized in a thread-safe manner.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete price scope. This pattern is used when creating limited views from fully-fetched entities.
 *
 * **Performance**: Uses `priceListsAsSet` for fast O(1) price list lookups during filtering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceContractSerializablePredicate implements SerializablePredicate<PriceContract> {
	public static final PriceContractSerializablePredicate DEFAULT_INSTANCE = new PriceContractSerializablePredicate();
	@Serial private static final long serialVersionUID = -7100489955953977035L;
	/**
	 * Contains information about price content mode used when entity prices were fetched.
	 */
	@Getter @Nonnull private final PriceContentMode priceContentMode;
	/**
	 * Contains information about price currency used when entity prices were fetched.
	 */
	@Getter @Nullable private final Currency currency;
	/**
	 * Contains information about price validity moment used when entity prices were fetched.
	 */
	@Getter @Nullable private final OffsetDateTime validIn;
	/**
	 * Contains information about price lists used when entity prices were fetched.
	 */
	@Getter @Nullable private final String[] priceLists;
	/**
	 * Contains information about price lists that should be fetched in addition to filtered prices.
	 */
	@Getter @Nullable private final String[] additionalPriceLists;
	/**
	 * Contains information about requested accompanying prices.
	 */
	@Getter @Nullable private final AccompanyingPrice[] accompanyingPrices;
	/**
	 * Contains the same information as {@link #priceLists} but in the form of the set for faster lookups.
	 */
	@Getter @Nonnull private final Set<String> priceListsAsSet;
	/**
	 * Contains information about the type of price that should be used for sorting and filtering.
	 */
	@Getter @Nonnull private final QueryPriceMode queryPriceMode;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(EntityContract, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final PriceContractSerializablePredicate underlyingPredicate;
	/**
	 * Contains information about the context availability for price for sale calculation.
	 */
	private final boolean contextAvailable;
	/**
	 * Contains information about the context for price for sale calculation. Computed lazily.
	 */
	private PriceForSaleContext priceForSaleContext;

	/**
	 * Collects all price lists from the provided sets of price lists, additional price lists, and accompanying prices.
	 * Combines these inputs into a single unified set of unique price lists.
	 *
	 * @param priceListsAsSet             a set of primary price lists that should be included in the result.
	 * @param fetchesAdditionalPriceLists an array of additional price lists that need to be incorporated into the result.
	 * @param accompanyingPrices          an array of accompanying prices, each of which contains a priority list of price lists
	 *                                    that should also be collected.
	 * @return a unified set of all unique price lists collected from the input parameters.
	 */
	@Nonnull
	private static Set<String> collectAllPriceLists(
		@Nonnull Set<String> priceListsAsSet,
		@Nonnull String[] fetchesAdditionalPriceLists,
		@Nonnull AccompanyingPrice[] accompanyingPrices
	) {
		return Stream.concat(
			priceListsAsSet.stream(),
			Stream.concat(
				Arrays.stream(fetchesAdditionalPriceLists),
				Arrays.stream(accompanyingPrices).flatMap(accompanyingPrice -> Arrays.stream(accompanyingPrice.priceListPriority()))
			)
		).collect(Collectors.toSet());
	}

	public PriceContractSerializablePredicate() {
		this.priceContentMode = PriceContentMode.ALL;
		this.currency = null;
		this.validIn = null;
		this.priceLists = null;
		this.additionalPriceLists = null;
		this.priceListsAsSet = Collections.emptySet();
		this.underlyingPredicate = null;
		this.contextAvailable = false;
		this.queryPriceMode = QueryPriceMode.WITH_TAX;
		this.accompanyingPrices = null;
	}

	public PriceContractSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nullable Boolean contextAvailable
	) {
		this.priceContentMode = evitaRequest.getRequiresEntityPrices();
		this.currency = evitaRequest.getRequiresCurrency();
		this.validIn = evitaRequest.getRequiresPriceValidIn();
		this.priceLists = evitaRequest.getRequiresPriceLists();
		this.additionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		this.accompanyingPrices = evitaRequest.getAccompanyingPrices();
		this.priceListsAsSet = CollectionUtils.createLinkedHashSet(this.priceLists.length + this.additionalPriceLists.length);
		Collections.addAll(this.priceListsAsSet, this.priceLists);
		Collections.addAll(this.priceListsAsSet, this.additionalPriceLists);
		for (AccompanyingPrice accompanyingPrice : this.accompanyingPrices) {
			Collections.addAll(this.priceListsAsSet, accompanyingPrice.priceListPriority());
		}
		this.underlyingPredicate = null;
		this.contextAvailable = contextAvailable != null ?
			contextAvailable : this.currency != null && !ArrayUtils.isEmpty(this.priceLists);
		this.queryPriceMode = evitaRequest.getQueryPriceMode();
	}

	public PriceContractSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull PriceForSaleWithAccompanyingPrices priceForSaleWithAccompanyingPrices
	) {
		this(evitaRequest, true);
		this.priceForSaleContext = new PriceForSaleContextWithCachedResult(
			evitaRequest.getRequiresPriceLists(),
			evitaRequest.getRequiresCurrency(),
			evitaRequest.getRequiresPriceValidIn(),
			evitaRequest.getAccompanyingPrices(),
			priceForSaleWithAccompanyingPrices
		);
	}

	public PriceContractSerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull PriceContractSerializablePredicate underlyingPredicate
	) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.priceContentMode = evitaRequest.getRequiresEntityPrices();
		this.currency = underlyingPredicate.currency;
		this.validIn = underlyingPredicate.validIn;
		this.priceLists = underlyingPredicate.priceLists;
		this.additionalPriceLists = underlyingPredicate.additionalPriceLists;
		this.accompanyingPrices = underlyingPredicate.accompanyingPrices;
		this.priceListsAsSet = CollectionUtils.createLinkedHashSet(
			(this.priceLists == null ? 0 : this.priceLists.length) +
				(this.additionalPriceLists == null ? 0 : this.additionalPriceLists.length)
		);
		if (this.priceLists != null) {
			Collections.addAll(this.priceListsAsSet, this.priceLists);
		}
		if (this.additionalPriceLists != null) {
			Collections.addAll(this.priceListsAsSet, this.additionalPriceLists);
		}
		if (this.accompanyingPrices != null) {
			for (AccompanyingPrice accompanyingPrice : this.accompanyingPrices) {
				Collections.addAll(this.priceListsAsSet, accompanyingPrice.priceListPriority());
			}
		}
		this.underlyingPredicate = underlyingPredicate;
		this.contextAvailable = this.currency != null && !ArrayUtils.isEmpty(this.priceLists);
		this.queryPriceMode = evitaRequest.getQueryPriceMode();
	}

	PriceContractSerializablePredicate(
		@Nonnull PriceContentMode priceContentMode,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nullable String[] priceLists,
		@Nullable String[] additionalPriceLists,
		@Nullable AccompanyingPrice[] accompanyingPrices,
		@Nonnull Set<String> priceListsAsSet,
		@Nonnull QueryPriceMode queryPriceMode,
		boolean contextAvailable
	) {
		this.priceContentMode = priceContentMode;
		this.currency = currency;
		this.validIn = validIn;
		this.priceLists = priceLists;
		this.additionalPriceLists = additionalPriceLists;
		this.accompanyingPrices = accompanyingPrices;
		this.priceListsAsSet = priceListsAsSet;
		this.underlyingPredicate = null;
		this.queryPriceMode = queryPriceMode;
		this.contextAvailable = contextAvailable;
	}

	@Override
	public boolean test(PriceContract priceContract) {
		return switch (this.priceContentMode) {
			case NONE -> false;
			case ALL -> priceContract.exists();
			case RESPECTING_FILTER -> priceContract.exists() &&
				(this.currency == null || Objects.equals(this.currency, priceContract.currency())) &&
				(ArrayUtils.isEmpty(this.priceLists) || this.priceListsAsSet.contains(priceContract.priceList())) &&
				(this.validIn == null || ofNullable(priceContract.validity()).map(it -> it.isValidFor(this.validIn)).orElse(true));
		};
	}

	/**
	 * Returns true if the context for price for sale calculation is available.
	 */
	public boolean isContextAvailable() {
		return this.contextAvailable;
	}

	/**
	 * Returns the context for price for sale calculation.
	 *
	 * @return the context for price for sale calculation
	 */
	@Nonnull
	public Optional<PriceForSaleContext> getPriceForSaleContext() {
		if (this.contextAvailable) {
			if (this.priceForSaleContext == null) {
				this.priceForSaleContext = new PriceForSaleContextWithCachedResult(
					this.priceLists, this.currency, this.validIn, this.accompanyingPrices
				);
			}
			return Optional.of(this.priceForSaleContext);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Verifies that the price for particular `currency` and `priceList` combination was fetched along with the entity.
	 *
	 * @throws ContextMissingException if the price was not fetched
	 */
	public void checkFetched(@Nullable Currency currency, @Nonnull String... priceList) throws ContextMissingException {
		switch (this.priceContentMode) {
			case NONE -> throw ContextMissingException.pricesNotFetched();
			case RESPECTING_FILTER -> {
				if (this.currency != null && currency != null && !Objects.equals(this.currency, currency)) {
					throw ContextMissingException.pricesNotFetched(currency, this.currency);
				}
				if (!this.priceListsAsSet.isEmpty()) {
					for (String checkedPriceList : priceList) {
						if (!this.priceListsAsSet.contains(checkedPriceList)) {
							throw ContextMissingException.pricesNotFetched(checkedPriceList, this.priceListsAsSet);
						}
					}
				}
			}
		}
	}

	/**
	 * Returns true if the prices were fetched.
	 */
	public boolean isFetched() {
		return this.priceContentMode != PriceContentMode.NONE;
	}

	/**
	 * Verifies that at least a single price was fetched along with the entity.
	 *
	 * @throws ContextMissingException if no price was fetched with the entity
	 */
	public void checkPricesFetched() throws ContextMissingException {
		if (this.priceContentMode == PriceContentMode.NONE) {
			throw ContextMissingException.pricesNotFetched();
		}
	}

	/**
	 * Creates and returns a richer copy of the current PriceContractSerializablePredicate instance with properties
	 * updated or augmented based on the provided EvitaRequest.
	 *
	 * @param evitaRequest the request containing additional details or constraints such as required entity prices,
	 *                     additional price lists, or accompanying price lists which affect the returned predicate.
	 * @return a new PriceContractSerializablePredicate instance that has been enriched with the provided properties
	 * or the current instance if no changes are required.
	 */
	@Nonnull
	public PriceContractSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final PriceContentMode requiresEntityPrices = evitaRequest.getRequiresEntityPrices();
		final String[] fetchesAdditionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		final AccompanyingPrice[] accompanyingPrices = evitaRequest.getAccompanyingPrices();
		final AccompanyingPrice[] mergedAccompanyingPrices;
		if (ArrayUtils.isEmpty(accompanyingPrices) && ArrayUtils.isEmpty(this.accompanyingPrices)) {
			mergedAccompanyingPrices = accompanyingPrices;
		} else if (ArrayUtils.isEmpty(this.accompanyingPrices)) {
			mergedAccompanyingPrices = accompanyingPrices;
		} else if (ArrayUtils.isEmpty(accompanyingPrices)) {
			mergedAccompanyingPrices = this.accompanyingPrices;
		} else {
			// merge accompanying prices
			final Set<AccompanyingPrice> mergedAccompanyingPriceSet = CollectionUtils.createLinkedHashSet(this.accompanyingPrices.length + accompanyingPrices.length);
			Collections.addAll(mergedAccompanyingPriceSet, this.accompanyingPrices);
			Collections.addAll(mergedAccompanyingPriceSet, accompanyingPrices);
			mergedAccompanyingPrices = mergedAccompanyingPriceSet.toArray(AccompanyingPrice.EMPTY_ARRAY);
		}
		if (this.priceContentMode.ordinal() >= requiresEntityPrices.ordinal()) {
			if (ArrayUtils.isEmpty(fetchesAdditionalPriceLists) && ArrayUtils.isEmpty(accompanyingPrices)) {
				// this predicate cannot change since everything is taken from the filter and this cannot change in time
				return this;
			} else {
				return new PriceContractSerializablePredicate(
					requiresEntityPrices,
					this.currency, this.validIn, this.priceLists,
					this.additionalPriceLists == null ? fetchesAdditionalPriceLists : ArrayUtils.mergeArrays(this.additionalPriceLists, fetchesAdditionalPriceLists),
					mergedAccompanyingPrices,
					collectAllPriceLists(this.priceListsAsSet, fetchesAdditionalPriceLists, accompanyingPrices),
					this.queryPriceMode,
					this.contextAvailable
				);
			}
		} else {
			return new PriceContractSerializablePredicate(
				requiresEntityPrices,
				this.currency, this.validIn,
				this.priceLists,
				this.additionalPriceLists == null ? fetchesAdditionalPriceLists : ArrayUtils.mergeArrays(this.additionalPriceLists, fetchesAdditionalPriceLists),
				mergedAccompanyingPrices,
				collectAllPriceLists(this.priceListsAsSet, fetchesAdditionalPriceLists, accompanyingPrices),
				this.queryPriceMode,
				this.contextAvailable
			);
		}
	}
}
