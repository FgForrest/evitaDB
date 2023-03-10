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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entity prices container allows defining set of prices of the entity.
 * Attributes may be indexed for fast filtering ({@link Price#isSellable()}). Prices are not automatically indexed
 * in order not to waste precious memory space for data that will never be used in search queries.
 * <p>
 * Filtering in prices is executed by using constraints like {@link io.evitadb.api.query.filter.PriceBetween},
 * {@link io.evitadb.api.query.filter.PriceValidIn}, {@link PriceInPriceLists} or
 * {@link QueryPriceMode}.
 * <p>
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link InitialPricesBuilder}.
 *
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = "version")
public class Prices implements PricesContract, Versioned, ContentComparator<Prices> {
	@Serial private static final long serialVersionUID = -2717054691549391374L;

	/**
	 * Contains version of this object and gets increased with any (direct) entity update. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 */
	@Getter final int version;
	/**
	 * Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 * <p>
	 * Specifying prices on entity allows usage of {@link io.evitadb.api.query.filter.PriceValidIn},
	 * {@link io.evitadb.api.query.filter.PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural},
	 * ordering of the entities. Additional requirements
	 * {@link PriceHistogram}, {@link PriceContent}
	 * can be used in query as well.
	 */
	final Map<PriceKey, PriceContract> priceIndex;
	/**
	 * Price inner record handling controls how prices that share same `inner entity id` will behave during filtering and sorting.
	 */
	@Getter final PriceInnerRecordHandling priceInnerRecordHandling;

	public Prices(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.version = 1;
		this.priceIndex = new HashMap<>();
		this.priceInnerRecordHandling = priceInnerRecordHandling;
	}

	public Prices(@Nonnull Collection<PriceContract> prices, @Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.version = 1;
		this.priceIndex = prices.stream().collect(Collectors.toUnmodifiableMap(PriceContract::getPriceKey, Function.identity()));
		this.priceInnerRecordHandling = priceInnerRecordHandling;
	}

	public Prices(int version, @Nonnull Collection<PriceContract> prices, @Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.version = version;
		this.priceIndex = prices.stream().collect(Collectors.toUnmodifiableMap(PriceContract::getPriceKey, Function.identity()));
		this.priceInnerRecordHandling = priceInnerRecordHandling;
	}

	/**
	 * Returns version of this object.
	 */
	@Override
	public int getPricesVersion() {
		return version;
	}

	/**
	 * Returns price by its business key identification.
	 */
	@Nullable
	public PriceContract getPrice(PriceKey priceKey) {
		return priceIndex.get(priceKey);
	}

	/**
	 * Returns price by its business key identification.
	 */
	@Nonnull
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return Optional.ofNullable(priceIndex.get(new PriceKey(priceId, priceList, currency)));
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		throw new ContextMissingException();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		return Optional.empty();
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		return getAllPricesForSale(null, null, (String)null);
	}

	@Override
	public boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode) throws ContextMissingException {
		throw new ContextMissingException();
	}

	/**
	 * Returns all prices of the entity.
	 */
	@Nonnull
	public Collection<PriceContract> getPrices() {
		return priceIndex.values();
	}

	/**
	 * Returns all prices indexed by key.
	 */
	@Nonnull
	public Map<PriceKey, PriceContract> getPriceIndex() {
		return priceIndex;
	}

	/**
	 * Returns true when there is no single price defined.
	 */
	public boolean isEmpty() {
		return priceIndex.isEmpty();
	}

	/**
	 * Method returns true if any prices inner data differs from other prices object.
	 */
	@Override
	public boolean differsFrom(@Nullable Prices otherPrices) {
		if (this == otherPrices) return false;
		if (otherPrices == null) return true;

		if (version != otherPrices.version) return true;
		if (priceInnerRecordHandling != otherPrices.priceInnerRecordHandling) return true;
		if (priceIndex.size() != otherPrices.priceIndex.size()) return true;

		for (Entry<PriceKey, PriceContract> entry : priceIndex.entrySet()) {
			final PriceContract otherPrice = otherPrices.getPrice(entry.getKey());
			if (otherPrice == null || entry.getValue().differsFrom(otherPrice)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		final Collection<PriceContract> prices = getPrices();
		return "selects " + priceInnerRecordHandling + " from: " +
			(
				prices.isEmpty() ?
					"no price" :
					prices
						.stream()
						.map(Object::toString)
						.collect(Collectors.joining(", "))
			);
	}
}
