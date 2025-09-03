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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesEditor.PricesBuilder;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to create new {@link Price} instance.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class InitialPricesBuilder implements PricesBuilder {
	@Serial private static final long serialVersionUID = 4752434728077797252L;

	/**
	 * Entity schema if available.
	 */
	private final EntitySchemaContract entitySchema;
	private final Map<PriceKey, PriceContract> prices;
	@Getter private PriceInnerRecordHandling priceInnerRecordHandling;

	InitialPricesBuilder(EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.prices = CollectionUtils.createHashMap(16);
		this.priceInnerRecordHandling = PriceInnerRecordHandling.NONE;
	}

	InitialPricesBuilder(
		@Nonnull EntitySchemaContract schema,
		@Nullable PriceInnerRecordHandling priceInnerRecordHandling,
		@Nonnull Collection<PriceContract> prices
	) {
		this.entitySchema = schema;
		this.priceInnerRecordHandling = priceInnerRecordHandling == null ?
			PriceInnerRecordHandling.NONE : priceInnerRecordHandling;
		this.prices = new HashMap<>(prices.size());
		for (PriceContract price : prices) {
			this.setPrice(
				price.priceId(),
				price.priceList(),
				price.currency(),
				price.innerRecordId(),
				price.priceWithoutTax(),
				price.taxRate(),
				price.priceWithTax(),
				price.validity(),
				price.indexed()
			);
		}
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed
	) {
		assertPricesAllowed(currency);
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		final Price thePrice = new Price(priceKey, null, priceWithoutTax, taxRate, priceWithTax, null, indexed);
		assertPriceNotAmbiguousBeforeAdding(thePrice);
		this.prices.put(priceKey, thePrice);
		return this;
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		assertPricesAllowed(currency);
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		final Price thePrice = new Price(
			priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, null, indexed);
		assertPriceNotAmbiguousBeforeAdding(thePrice);
		this.prices.put(priceKey, thePrice);
		return this;
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, DateTimeRange validity, boolean indexed
	) {
		assertPricesAllowed(currency);
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		final Price thePrice = new Price(priceKey, null, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		assertPriceNotAmbiguousBeforeAdding(thePrice);
		this.prices.put(priceKey, thePrice);
		return this;
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		assertPricesAllowed(currency);
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		final Price thePrice = new Price(
			priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		assertPriceNotAmbiguousBeforeAdding(thePrice);
		this.prices.put(priceKey, thePrice);
		return this;
	}

	@Override
	public PricesBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		assertPricesAllowed(currency);
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		this.prices.remove(priceKey);
		return this;
	}

	@Override
	public PricesBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		assertPricesAllowed();
		this.priceInnerRecordHandling = priceInnerRecordHandling;
		return this;
	}

	@Override
	public PricesBuilder removePriceInnerRecordHandling() {
		assertPricesAllowed();
		this.priceInnerRecordHandling = PriceInnerRecordHandling.NONE;
		return this;
	}

	@Override
	public PricesBuilder removeAllNonTouchedPrices() {
		assertPricesAllowed();
		// do nothing - every price in initial prices builder is touched
		return this;
	}

	@Override
	public boolean pricesAvailable() {
		return true;
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) {
		return ofNullable(this.prices.get(priceKey));
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return getPrice(new PriceKey(priceId, priceList, currency));
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) throws UnexpectedResultCountException, ContextMissingException {
		final List<PriceContract> matchingPrices = getPrices()
			.stream()
			.filter(it -> it.priceList().equals(priceList) && it.currency().equals(currency))
			.toList();
		if (matchingPrices.size() > 1) {
			throw new UnexpectedResultCountException(
				matchingPrices.size(),
				"More than one price found for price list `" + priceList + "` and currency `" + currency + "`."
			);
		}
		return matchingPrices.isEmpty() ? Optional.empty() : Optional.of(matchingPrices.get(0));
	}

	@Override
	public boolean isPriceForSaleContextAvailable() {
		return false;
	}

	@Nonnull
	@Override
	public Optional<PriceForSaleContext> getPriceForSaleContext() {
		return empty();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		throw new ContextMissingException();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		return empty();
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		throw new ContextMissingException();
	}

	@Override
	public boolean hasPriceInInterval(
		@Nonnull BigDecimal from, @Nonnull BigDecimal to,
		@Nonnull QueryPriceMode queryPriceMode
	) throws ContextMissingException {
		throw new ContextMissingException();
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		return this.prices.values();
	}

	@Override
	public int version() {
		return 1;
	}

	@Nonnull
	@Override
	public Prices build() {
		return new Prices(
			this.entitySchema,
			1,
			this.prices.values(),
			this.priceInnerRecordHandling,
			!this.prices.isEmpty()
		);
	}

	@Nonnull
	@Override
	public Stream<? extends LocalMutation<?, ?>> buildChangeSet() {
		return Stream.concat(
			this.priceInnerRecordHandling == null ?
				Stream.empty() :
				Stream.of(new SetPriceInnerRecordHandlingMutation(this.priceInnerRecordHandling)),
			this.prices.entrySet().stream().map(it -> new UpsertPriceMutation(it.getKey(), it.getValue()))
		);
	}

	/**
	 * Ensures that the entity schema supports prices or permits the addition of prices through schema evolution.
	 *
	 * This method verifies that the entity schema either explicitly includes price information
	 * (as determined by {@link EntitySchemaContract#isWithPrice()}) or allows the addition of prices
	 * (as determined by {@link EntitySchemaContract#allows(EvolutionMode)}). If neither condition is true,
	 * an {@link InvalidMutationException} is thrown, indicating that the current configuration does not
	 * support the addition of price-related data.
	 *
	 * @throws InvalidMutationException if the entity schema does not support prices and does not allow
	 *                                  adding prices.
	 */
	private void assertPricesAllowed() {
		Assert.isTrue(
			this.entitySchema.isWithPrice() || this.entitySchema.allows(EvolutionMode.ADDING_PRICES),
			() -> new InvalidMutationException(
				"Entity `" + this.entitySchema.getName() + "` doesn't support prices, cannot set price inner record handling!"
			)
		);
	}

	/**
	 * Ensures that the provided currency is allowed by the entity schema.
	 *
	 * This method verifies that the specified {@code currency} is either included in the entity schema's
	 * list of supported currencies or that the entity schema allows the addition of new currencies through
	 * schema evolution. If neither condition is met, an {@link InvalidMutationException} is thrown.
	 *
	 * @param currency the currency to validate against the entity schema
	 * @throws InvalidMutationException if the provided currency is not supported by the entity schema and
	 *                                  adding new currencies is not allowed.
	 */
	private void assertPricesAllowed(@Nonnull Currency currency) {
		assertPricesAllowed();
		Assert.isTrue(
			this.entitySchema.getCurrencies().contains(currency) || this.entitySchema.allows(EvolutionMode.ADDING_CURRENCIES),
			() -> new InvalidMutationException(
				"Entity `" + this.entitySchema.getName() + "` doesn't support currency `" + currency + "`, cannot set price in this currency! " +
					"You need to update entity schema to allow adding this currency first."
			)
		);
	}

	/**
	 * Method throws {@link AmbiguousPriceException} when there is conflict in prices.
	 */
	private void assertPriceNotAmbiguousBeforeAdding(@Nonnull Price price) {
		final PriceContract conflictingPrice = getPrices()
			.stream()
			.filter(it -> it.priceList().equals(price.priceList()))
			.filter(it -> it.currency().equals(price.currency()))
			.filter(it -> it.priceId() != price.priceId())
			.filter(it -> Objects.equals(
				it.innerRecordId(),
				price.innerRecordId()
			))
			.filter(it ->
				        price.validity() == null ||
					        ofNullable(it.validity()).map(
						                                 existingValidity -> existingValidity.overlaps(
							                                 price.validity()))
					                                 .orElse(true)
			)
			.findFirst()
			.orElse(null);
		if (conflictingPrice != null) {
			throw new AmbiguousPriceException(conflictingPrice, price);
		}
	}

}
