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

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesEditor.PricesBuilder;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Prices}. Prices is immutable object so there is need for another object
 * that would simplify the process of updating its contents. This is why the builder class exists.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingPricesBuilder implements PricesBuilder {
	@Serial private static final long serialVersionUID = 5366182867172493114L;

	/**
	 * This predicate filters out prices that were not fetched in query.
	 */
	@Getter private final PriceContractSerializablePredicate pricePredicate;
	private final Map<PriceKey, PriceMutation> priceMutations;
	private final EntitySchemaContract entitySchema;
	private final Prices basePrices;
	@Nullable private SetPriceInnerRecordHandlingMutation priceInnerRecordHandlingEntityMutation;
	private boolean removeAllNonModifiedPrices;

	public ExistingPricesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Prices prices
	) {
		this.basePrices = prices;
		this.entitySchema = entitySchema;
		this.priceMutations = new HashMap<>(16);
		this.pricePredicate = new PriceContractSerializablePredicate();
	}

	public ExistingPricesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Prices prices,
		@Nonnull PriceContractSerializablePredicate pricePredicate
	) {
		this.basePrices = prices;
		this.entitySchema = entitySchema;
		this.priceMutations = new HashMap<>(16);
		this.pricePredicate = pricePredicate;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	public void addMutation(@Nonnull SetPriceInnerRecordHandlingMutation setPriceInnerRecordHandlingMutation) {
		this.priceInnerRecordHandlingEntityMutation = setPriceInnerRecordHandlingMutation;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	public void addMutation(@Nonnull PriceMutation localMutation) {
		if (localMutation instanceof UpsertPriceMutation upsertPriceMutation) {
			final PriceKey priceKey = upsertPriceMutation.getPriceKey();
			assertPriceNotAmbiguousBeforeAdding(
				new Price(
					priceKey,
					upsertPriceMutation.getInnerRecordId(),
					upsertPriceMutation.getPriceWithoutTax(),
					upsertPriceMutation.getTaxRate(),
					upsertPriceMutation.getPriceWithTax(),
					upsertPriceMutation.getValidity(),
					upsertPriceMutation.isSellable()
				)
			);
			this.priceMutations.put(priceKey, upsertPriceMutation);
		} else if (localMutation instanceof RemovePriceMutation removePriceMutation) {
			final PriceKey priceKey = removePriceMutation.getPriceKey();
			Assert.notNull(basePrices.getPriceWithoutSchemaCheck(priceKey), "Price " + priceKey + " doesn't exist!");
			final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
			this.priceMutations.put(priceKey, mutation);
		} else {
			throw new EvitaInternalError("Unknown Evita price mutation: `" + localMutation.getClass() + "`!");
		}
	}

	@Override
	public PricesBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, null, sellable);
	}

	@Override
	public PricesBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean sellable) {
		return setPrice(priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, null, sellable);
	}

	@Override
	public PricesBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean sellable) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
	}

	@Override
	public PricesBuilder setPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId, @Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean sellable) {
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		final UpsertPriceMutation mutation = new UpsertPriceMutation(priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, sellable);
		assertPriceNotAmbiguousBeforeAdding(new Price(priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, sellable));
		this.priceMutations.put(priceKey, mutation);
		return this;
	}

	@Override
	public PricesBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		Assert.notNull(basePrices.getPriceWithoutSchemaCheck(priceKey), "Price " + priceKey + " doesn't exist!");
		final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
		this.priceMutations.put(priceKey, mutation);
		return this;
	}

	@Override
	public PricesBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.priceInnerRecordHandlingEntityMutation = new SetPriceInnerRecordHandlingMutation(priceInnerRecordHandling);
		return this;
	}

	@Override
	public PricesBuilder removePriceInnerRecordHandling() {
		Assert.isTrue(
			basePrices.getPriceInnerRecordHandling() != PriceInnerRecordHandling.NONE,
			"Price inner record handling is already set to NONE!"
		);
		this.priceInnerRecordHandlingEntityMutation = new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE);
		return this;
	}

	@Override
	public PricesBuilder removeAllNonTouchedPrices() {
		this.removeAllNonModifiedPrices = true;
		return this;
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return getPriceInternal(new PriceKey(priceId, priceList, currency));
	}

	@Nullable
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) throws ContextMissingException {
		return getPriceInternal(priceKey);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull String priceList, @Nonnull Currency currency) throws UnexpectedResultCountException, ContextMissingException {
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
		return pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(pricePredicate.getPriceLists());
	}

	@Override
	public boolean pricesAvailable() {
		return basePrices.pricesAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		if (pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(pricePredicate.getPriceLists())) {
			return getPriceForSale(
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		if (pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(pricePredicate.getPriceLists())) {
			return getPriceForSale(
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			return empty();
		}
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		return getAllPricesForSale(
			pricePredicate.getCurrency(),
			pricePredicate.getValidIn(),
			pricePredicate.getPriceLists()
		);
	}

	@Override
	public boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode) throws ContextMissingException {
		if (pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(pricePredicate.getPriceLists())) {
			return hasPriceInInterval(
				from, to, queryPriceMode,
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		return getPricesWithoutPredicate()
			.filter(pricePredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		return ofNullable(priceInnerRecordHandlingEntityMutation)
			.map(it -> it.mutateLocal(entitySchema, basePrices).getPriceInnerRecordHandling())
			.orElseGet(basePrices::getPriceInnerRecordHandling);
	}

	@Override
	public int version() {
		return basePrices.version();
	}

	@Nonnull
	@Override
	public Stream<? extends LocalMutation<?, ?>> buildChangeSet() {
		final Collection<PriceContract> originalPrices = basePrices.pricesAvailable() ? basePrices.getPrices() : Collections.emptyList();
		final Map<PriceKey, PriceContract> builtPrices = CollectionUtils.createHashMap(originalPrices.size());
		for (PriceContract originalPrice : originalPrices) {
			builtPrices.put(originalPrice.priceKey(), originalPrice);
		}
		if (removeAllNonModifiedPrices) {
			return Stream.concat(
					priceInnerRecordHandlingEntityMutation == null || Objects.equals(basePrices.getPriceInnerRecordHandling(), priceInnerRecordHandlingEntityMutation.getPriceInnerRecordHandling()) ?
						Stream.empty() : Stream.of(priceInnerRecordHandlingEntityMutation),
					Stream.concat(
						priceMutations.values()
							.stream()
							.filter(it -> {
								final PriceContract existingValue = builtPrices.get(it.getPriceKey());
								final PriceContract newPrice = it.mutateLocal(entitySchema, existingValue);
								builtPrices.put(it.getPriceKey(), newPrice);
								return existingValue == null || newPrice.version() > existingValue.version();
							}),
						originalPrices
							.stream()
							.filter(it -> priceMutations.get(it.priceKey()) == null)
							.map(it -> new RemovePriceMutation(it.priceKey()))
					)
				)
				.filter(Objects::nonNull);
		} else {
			return Stream.concat(
					Objects.equals(basePrices.getPriceInnerRecordHandling(), ofNullable(priceInnerRecordHandlingEntityMutation).map(SetPriceInnerRecordHandlingMutation::getPriceInnerRecordHandling).orElse(null)) ?
						Stream.empty() : Stream.of(priceInnerRecordHandlingEntityMutation),
					priceMutations.values()
						.stream()
						.filter(it -> {
							final PriceContract existingValue = builtPrices.get(it.getPriceKey());
							final PriceContract newPrice = it.mutateLocal(entitySchema, existingValue);
							builtPrices.put(it.getPriceKey(), newPrice);
							return existingValue == null || newPrice.version() > existingValue.version();
						})
				)
				.filter(Objects::nonNull);
		}
	}

	@Nonnull
	@Override
	public Prices build() {
		final Collection<PriceContract> newPrices = getPricesWithoutPredicate().collect(Collectors.toList());
		final Map<PriceKey, PriceContract> newPriceIndex = newPrices
			.stream()
			.collect(
				Collectors.toMap(
					PriceContract::priceKey,
					Function.identity()
				)
			);
		final PriceInnerRecordHandling newPriceInnerRecordHandling = getPriceInnerRecordHandling();
		if (basePrices.getPriceInnerRecordHandling() != newPriceInnerRecordHandling ||
			basePrices.getPrices().size() != newPrices.size() ||
			basePrices.getPrices()
				.stream()
				.anyMatch(
					it -> ofNullable(newPriceIndex.get(it.priceKey()))
						.map(price -> price.differsFrom(it))
						.orElse(true)
				)
			) {
			return new Prices(
				basePrices.entitySchema,
				basePrices.version() + 1,
				newPrices,
				newPriceInnerRecordHandling,
				!newPrices.isEmpty()
			);
		} else {
			return basePrices;
		}
	}

	@Nonnull
	private Stream<PriceContract> getPricesWithoutPredicate() {
		return Stream.concat(
			basePrices
				.getPrices()
				.stream()
				.map(it ->
					ofNullable(priceMutations.get(it.priceKey()))
						.map(mut -> {
							final PriceContract mutatedValue = mut.mutateLocal(entitySchema, it);
							return mutatedValue.differsFrom(it) ? mutatedValue : it;
						})
						.orElseGet(() -> removeAllNonModifiedPrices ? null : it)
				)
				.filter(Objects::nonNull)
				.filter(PriceContract::exists),
			priceMutations
				.values()
				.stream()
				.filter(it -> basePrices.getPriceWithoutSchemaCheck(it.getPriceKey()).isEmpty())
				.map(it -> it.mutateLocal(entitySchema, null))
		);
	}

	@Nonnull
	private Optional<PriceContract> getPriceInternal(@Nonnull PriceKey priceKey) {
		final Optional<PriceContract> price = basePrices.getPriceWithoutSchemaCheck(priceKey)
			.map(it -> ofNullable(priceMutations.get(priceKey))
				.map(x -> x.mutateLocal(entitySchema, it))
				.orElse(it)
			)
			.or(
				() -> ofNullable(priceMutations.get(priceKey))
					.map(it -> it.mutateLocal(entitySchema, null))
			);
		return price.filter(pricePredicate);
	}

	/**
	 * Method throws {@link AmbiguousPriceException} when there is conflict in prices.
	 */
	private void assertPriceNotAmbiguousBeforeAdding(@Nonnull PriceContract price) {
		// check whether new price doesn't conflict with original prices
		final PriceContract conflictingPrice = basePrices.getPrices().stream()
			.filter(Droppable::exists)
			.filter(it -> it.priceList().equals(price.priceList()))
			.filter(it -> it.currency().equals(price.currency()))
			.filter(it -> it.priceId() != price.priceId())
			.filter(it -> Objects.equals(it.innerRecordId(), price.innerRecordId()))
			.filter(it ->
				price.validity() == null ||
					ofNullable(it.validity()).map(existingValidity -> existingValidity.overlaps(price.validity()))
						.orElse(true)
			)
			// the conflicting prices don't play role if they're going to be removed in the same update
			.filter(it -> !(priceMutations.get(it.priceKey()) instanceof RemovePriceMutation))
			.findFirst()
			.orElse(null);
		if (conflictingPrice != null) {
			throw new AmbiguousPriceException(conflictingPrice, price);
		}
		// check whether there is no conflicting update
		final UpsertPriceMutation conflictingMutation = priceMutations.values().stream()
			.filter(it -> it instanceof UpsertPriceMutation)
			.map(UpsertPriceMutation.class::cast)
			.filter(it -> it.getPriceKey().priceList().equals(price.priceList()))
			.filter(it -> it.getPriceKey().currency().equals(price.currency()))
			.filter(it -> it.getPriceKey().priceId() != price.priceId())
			.filter(it -> Objects.equals(it.getInnerRecordId(), price.innerRecordId()))
			.filter(it ->
				price.validity() == null ||
					ofNullable(it.getValidity()).map(existingValidity -> existingValidity.overlaps(price.validity()))
						.orElse(true)
			)
			.findFirst()
			.orElse(null);
		if (conflictingMutation != null) {
			throw new AmbiguousPriceException(
				conflictingMutation.mutateLocal(
					entitySchema,
					basePrices.getPriceWithoutSchemaCheck(conflictingMutation.getPriceKey()).orElse(null)
				),
				price
			);
		}
	}

}
