/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This predicate allows to limit number of prices visible to the client based on query constraints.
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
	 * Contains the same information as {@link #priceLists} but in the form of the set for faster lookups.
	 */
	@Getter @Nonnull private final Set<String> priceListsAsSet;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final PriceContractSerializablePredicate underlyingPredicate;
	private final boolean contextAvailable;

	public PriceContractSerializablePredicate() {
		this.priceContentMode = PriceContentMode.ALL;
		this.currency = null;
		this.validIn = null;
		this.priceLists = null;
		this.additionalPriceLists = null;
		this.priceListsAsSet = Collections.emptySet();
		this.underlyingPredicate = null;
		this.contextAvailable = false;
	}

	public PriceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nullable Boolean contextAvailable) {
		this.priceContentMode = evitaRequest.getRequiresEntityPrices();
		this.currency = evitaRequest.getRequiresCurrency();
		this.validIn = evitaRequest.getRequiresPriceValidIn();
		this.priceLists = evitaRequest.getRequiresPriceLists();
		this.additionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		this.priceListsAsSet = CollectionUtils.createLinkedHashSet(this.priceLists.length + this.additionalPriceLists.length);
		Collections.addAll(this.priceListsAsSet, this.priceLists);
		Collections.addAll(this.priceListsAsSet, this.additionalPriceLists);
		this.underlyingPredicate = null;
		this.contextAvailable = contextAvailable != null ?
			contextAvailable : this.currency != null && !ArrayUtils.isEmpty(this.priceLists);
	}

	public PriceContractSerializablePredicate(@Nonnull EvitaRequest evitaRequest, @Nonnull PriceContractSerializablePredicate underlyingPredicate) {
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
		this.priceListsAsSet = underlyingPredicate.priceListsAsSet;
		this.underlyingPredicate = underlyingPredicate;
		this.contextAvailable = this.currency != null && !ArrayUtils.isEmpty(this.priceLists);
	}

	PriceContractSerializablePredicate(
		@Nonnull PriceContentMode priceContentMode,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nullable String[] priceLists,
		@Nullable String[] additionalPriceLists,
		@Nonnull Set<String> priceListsAsSet,
		boolean contextAvailable
	) {
		this.priceContentMode = priceContentMode;
		this.currency = currency;
		this.validIn = validIn;
		this.priceLists = priceLists;
		this.additionalPriceLists = additionalPriceLists;
		this.priceListsAsSet = priceListsAsSet;
		this.underlyingPredicate = null;
		this.contextAvailable = contextAvailable;
	}

	@Override
	public boolean test(PriceContract priceContract) {
		return switch (priceContentMode) {
			case NONE -> false;
			case ALL -> priceContract.exists();
			case RESPECTING_FILTER -> priceContract.exists() &&
				(currency == null || Objects.equals(currency, priceContract.currency())) &&
				(ArrayUtils.isEmpty(priceLists) || priceListsAsSet.contains(priceContract.priceList())) &&
				(validIn == null || ofNullable(priceContract.validity()).map(it -> it.isValidFor(validIn)).orElse(true));
		};
	}

	/**
	 * Returns true if the context for price for sale calculation is available.
	 */
	public boolean isContextAvailable() {
		return contextAvailable;
	}

	/**
	 * Returns true if the price for particular `currency` and `priceList` combination might exist, but was not fetched
	 * along with the entity.
	 */
	public void checkFetched(@Nullable Currency currency, @Nonnull String... priceList) throws ContextMissingException {
		switch (priceContentMode) {
			case NONE -> throw ContextMissingException.pricesNotFetched();
			case RESPECTING_FILTER -> {
				if (this.currency != null && currency != null && !Objects.equals(this.currency, currency)) {
					throw ContextMissingException.pricesNotFetched(currency, this.currency);
				}
				if (!priceListsAsSet.isEmpty()) {
					for (String checkedPriceList : priceList) {
						if (!priceListsAsSet.contains(checkedPriceList)) {
							throw ContextMissingException.pricesNotFetched(checkedPriceList, priceListsAsSet);
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
		return priceContentMode != PriceContentMode.NONE;
	}

	/**
	 * Returns true if at least single price was fetched along with the entity.
	 *
	 * @throws ContextMissingException if no price was fetched with the entity
	 */
	public void checkPricesFetched() throws ContextMissingException {
		if (priceContentMode == PriceContentMode.NONE) {
			throw ContextMissingException.pricesNotFetched();
		}
	}

	public PriceContractSerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		final PriceContentMode requiresEntityPrices = evitaRequest.getRequiresEntityPrices();
		final String[] fetchesAdditionalPriceLists = evitaRequest.getFetchesAdditionalPriceLists();
		if (this.priceContentMode.ordinal() >= requiresEntityPrices.ordinal()) {
			if (ArrayUtils.isEmpty(fetchesAdditionalPriceLists)) {
				// this predicate cannot change since everything is taken from the filter and this cannot change in time
				return this;
			} else {
				return new PriceContractSerializablePredicate(
					requiresEntityPrices,
					this.currency, this.validIn, this.priceLists,
					this.additionalPriceLists == null ? fetchesAdditionalPriceLists : ArrayUtils.mergeArrays(this.additionalPriceLists, fetchesAdditionalPriceLists),
					this.priceListsAsSet,
					this.contextAvailable
				);
			}
		} else {
			if (ArrayUtils.isEmpty(fetchesAdditionalPriceLists)) {
				return new PriceContractSerializablePredicate(
					requiresEntityPrices,
					this.currency, this.validIn, this.priceLists,
					this.additionalPriceLists,
					this.priceListsAsSet, this.contextAvailable
				);
			} else {
				return new PriceContractSerializablePredicate(
					requiresEntityPrices,
					this.currency, this.validIn,
					this.priceLists,
					this.additionalPriceLists == null ? fetchesAdditionalPriceLists : ArrayUtils.mergeArrays(this.additionalPriceLists, fetchesAdditionalPriceLists),
					this.additionalPriceLists == null ? this.priceListsAsSet : Stream.concat(this.priceListsAsSet.stream(), Arrays.stream(fetchesAdditionalPriceLists)).collect(Collectors.toSet()),
					this.contextAvailable
				);
			}
		}
	}
}
