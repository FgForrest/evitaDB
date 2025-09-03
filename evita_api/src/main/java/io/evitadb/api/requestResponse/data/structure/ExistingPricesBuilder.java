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
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.require.PriceContentMode;
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
import io.evitadb.exception.GenericEvitaInternalError;
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

import static io.evitadb.api.requestResponse.data.structure.InitialPricesBuilder.assertPricesAllowed;
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
	@Nonnull @Getter private final PriceContractSerializablePredicate pricePredicate;
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
		assertPricesAllowed(this.entitySchema);
		this.priceInnerRecordHandlingEntityMutation = setPriceInnerRecordHandlingMutation;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	public void addMutation(@Nonnull PriceMutation localMutation) {
		if (localMutation instanceof UpsertPriceMutation upsertPriceMutation) {
			final PriceKey priceKey = upsertPriceMutation.getPriceKey();
			assertPricesAllowed(this.entitySchema, priceKey.currency());
			assertPriceNotAmbiguousBeforeAdding(
				new Price(
					priceKey,
					upsertPriceMutation.getInnerRecordId(),
					upsertPriceMutation.getPriceWithoutTax(),
					upsertPriceMutation.getTaxRate(),
					upsertPriceMutation.getPriceWithTax(),
					upsertPriceMutation.getValidity(),
					upsertPriceMutation.isIndexed()
				)
			);
			this.priceMutations.put(priceKey, upsertPriceMutation);
		} else if (localMutation instanceof RemovePriceMutation removePriceMutation) {
			final PriceKey priceKey = removePriceMutation.getPriceKey();
			assertPricesAllowed(this.entitySchema);
			Assert.notNull(this.basePrices.getPriceWithoutSchemaCheck(priceKey), "Price " + priceKey + " doesn't exist!");
			final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
			this.priceMutations.put(priceKey, mutation);
		} else {
			throw new GenericEvitaInternalError("Unknown Evita price mutation: `" + localMutation.getClass() + "`!");
		}
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed
	) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, null, indexed);
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		return setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, null, indexed);
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean indexed
	) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
	}

	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		assertPricesAllowed(this.entitySchema, currency);
		assertPricesFetchedAndMatchPredicate(priceKey, PriceContentMode.ALL);
		final UpsertPriceMutation mutation = new UpsertPriceMutation(
			priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		assertPriceNotAmbiguousBeforeAdding(
			new Price(priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed));
		this.priceMutations.put(priceKey, mutation);
		return this;
	}

	@Override
	public PricesBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		final PriceKey priceKey = new PriceKey(priceId, priceList, currency);
		assertPricesAllowed(this.entitySchema, currency);
		assertPricesFetchedAndMatchPredicate(priceKey, PriceContentMode.ALL);
		Assert.notNull(
			this.basePrices.getPriceWithoutSchemaCheck(priceKey).filter(Droppable::exists),
			"Price " + priceKey + " doesn't exist!"
		);
		final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
		this.priceMutations.put(priceKey, mutation);
		return this;
	}

	@Override
	public PricesBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		assertPricesAllowed(this.entitySchema);
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		this.priceInnerRecordHandlingEntityMutation = new SetPriceInnerRecordHandlingMutation(priceInnerRecordHandling);
		return this;
	}

	@Override
	public PricesBuilder removePriceInnerRecordHandling() {
		assertPricesAllowed(this.entitySchema);
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		Assert.isTrue(
			this.basePrices.getPriceInnerRecordHandling() != PriceInnerRecordHandling.NONE,
			"Price inner record handling is already set to NONE!"
		);
		this.priceInnerRecordHandlingEntityMutation = new SetPriceInnerRecordHandlingMutation(
			PriceInnerRecordHandling.NONE);
		return this;
	}

	@Override
	public PricesBuilder removeAllNonTouchedPrices() {
		assertPricesAllowed(this.entitySchema);
		assertPricesFetched(PriceContentMode.ALL);
		this.removeAllNonModifiedPrices = true;
		return this;
	}

	@Override
	public boolean pricesAvailable() {
		return this.basePrices.pricesAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) throws ContextMissingException {
		return getPriceInternal(priceKey);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return getPriceInternal(new PriceKey(priceId, priceList, currency));
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) throws UnexpectedResultCountException, ContextMissingException {
		assertPricesFetchedAndMatchPredicate(new PriceKey(-1, priceList, currency), PriceContentMode.RESPECTING_FILTER);
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
		return this.pricePredicate.isContextAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceForSaleContext> getPriceForSaleContext() {
		return this.pricePredicate.getPriceForSaleContext();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
			return getPriceForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
			return getPriceForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			return empty();
		}
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		assertPricesFetched(PriceContentMode.ALL);
		if (this.pricePredicate.getCurrency() == null || this.pricePredicate.getPriceLists() == null) {
			throw new ContextMissingException();
		}
		return getAllPricesForSale(
			this.pricePredicate.getCurrency(),
			this.pricePredicate.getValidIn(),
			this.pricePredicate.getPriceLists()
		);
	}

	@Override
	public boolean hasPriceInInterval(
		@Nonnull BigDecimal from,
		@Nonnull BigDecimal to,
		@Nonnull QueryPriceMode queryPriceMode
	) throws ContextMissingException {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
			return hasPriceInInterval(
				from, to, queryPriceMode,
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		return getPricesWithoutPredicate()
			.filter(this.pricePredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		return ofNullable(this.priceInnerRecordHandlingEntityMutation)
			.map(it -> it.mutateLocal(this.entitySchema, this.basePrices).getPriceInnerRecordHandling())
			.orElseGet(this.basePrices::getPriceInnerRecordHandling);
	}

	@Override
	public int version() {
		return this.basePrices.version();
	}

	@Nonnull
	@Override
	public Stream<? extends LocalMutation<?, ?>> buildChangeSet() {
		final Collection<PriceContract> originalPrices = this.basePrices.pricesAvailable() ?
			this.basePrices.getPrices() :
			Collections.emptyList();
		final Map<PriceKey, PriceContract> builtPrices = CollectionUtils.createHashMap(originalPrices.size());
		for (PriceContract originalPrice : originalPrices) {
			builtPrices.put(originalPrice.priceKey(), originalPrice);
		}
		if (this.removeAllNonModifiedPrices) {
			return Stream.concat(
				             this.priceInnerRecordHandlingEntityMutation == null || Objects.equals(
					             this.basePrices.getPriceInnerRecordHandling(),
					             this.priceInnerRecordHandlingEntityMutation.getPriceInnerRecordHandling()
				             ) ?
					             Stream.empty() : Stream.of(this.priceInnerRecordHandlingEntityMutation),
				             Stream.concat(
					             this.priceMutations.values()
					                                .stream()
					                                .filter(it -> {
						                                final PriceContract existingValue = builtPrices.get(it.getPriceKey());
						                                final PriceContract newPrice = it.mutateLocal(
							                                this.entitySchema, existingValue);
						                                builtPrices.put(it.getPriceKey(), newPrice);
						                                return existingValue == null || newPrice.version() > existingValue.version();
					                                }),
					             originalPrices
						             .stream()
						             .filter(Droppable::exists)
						             .filter(it -> this.priceMutations.get(it.priceKey()) == null)
						             .map(it -> new RemovePriceMutation(it.priceKey()))
				             )
			             )
			             .filter(Objects::nonNull);
		} else {
			return Stream.concat(
				             Objects.equals(
					             this.basePrices.getPriceInnerRecordHandling(), ofNullable(
						             this.priceInnerRecordHandlingEntityMutation).map(
						             SetPriceInnerRecordHandlingMutation::getPriceInnerRecordHandling).orElse(null)
				             ) ?
					             Stream.empty() : Stream.of(this.priceInnerRecordHandlingEntityMutation),
				             this.priceMutations.values()
				                                .stream()
				                                .filter(it -> {
					                                final PriceContract existingValue = builtPrices.get(it.getPriceKey());
					                                final PriceContract newPrice = it.mutateLocal(this.entitySchema, existingValue);
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
		if (this.basePrices.getPriceInnerRecordHandling() != newPriceInnerRecordHandling ||
			this.basePrices.getPrices().size() != newPrices.size() ||
			this.basePrices.getPrices()
			               .stream()
			               .anyMatch(
				               it -> ofNullable(newPriceIndex.get(it.priceKey()))
					               .map(price -> price.differsFrom(it))
					               .orElse(true)
			               )
		) {
			return new Prices(
				this.basePrices.entitySchema,
				this.basePrices.version() + 1,
				newPrices,
				newPriceInnerRecordHandling,
				!newPrices.isEmpty()
			);
		} else {
			return this.basePrices;
		}
	}

	@Nonnull
	private Stream<PriceContract> getPricesWithoutPredicate() {
		return Stream.concat(
			this.basePrices
				.getPrices()
				.stream()
				.map(it ->
					     ofNullable(this.priceMutations.get(it.priceKey()))
						     .map(mut -> {
							     final PriceContract mutatedValue = mut.mutateLocal(this.entitySchema, it);
							     return mutatedValue.differsFrom(it) ? mutatedValue : it;
						     })
						     .orElseGet(() -> this.removeAllNonModifiedPrices ? null : it)
				)
				.filter(Objects::nonNull)
				.filter(PriceContract::exists),
			this.priceMutations
				.values()
				.stream()
				.filter(it -> this.basePrices.getPriceWithoutSchemaCheck(it.getPriceKey()).isEmpty())
				.map(it -> it.mutateLocal(this.entitySchema, null))
		);
	}

	@Nonnull
	private Optional<PriceContract> getPriceInternal(@Nonnull PriceKey priceKey) {
		assertPricesFetchedAndMatchPredicate(priceKey, PriceContentMode.RESPECTING_FILTER);
		final Optional<PriceContract> price = this.basePrices.getPriceWithoutSchemaCheck(priceKey)
		                                                     .map(it -> ofNullable(this.priceMutations.get(priceKey))
			                                                     .map(x -> x.mutateLocal(this.entitySchema, it))
			                                                     .orElse(it)
		                                                     )
		                                                     .or(
			                                                     () -> ofNullable(this.priceMutations.get(priceKey))
				                                                     .map(it -> it.mutateLocal(this.entitySchema, null))
		                                                     );
		return price.filter(this.pricePredicate);
	}

	/**
	 * Method throws {@link AmbiguousPriceException} when there is conflict in prices.
	 */
	private void assertPriceNotAmbiguousBeforeAdding(@Nonnull PriceContract price) {
		// check whether new price doesn't conflict with original prices
		final PriceContract conflictingPrice = this.basePrices.getPrices().stream()
		                                                      .filter(Droppable::exists)
		                                                      .filter(it -> it.priceList().equals(price.priceList()))
		                                                      .filter(it -> it.currency().equals(price.currency()))
		                                                      .filter(it -> it.priceId() != price.priceId())
		                                                      .filter(it -> Objects.equals(
			                                                      it.innerRecordId(),
			                                                      price.innerRecordId()
		                                                      ))
		                                                      .filter(it ->
			                                                              price.validity() == null ||
				                                                              ofNullable(it.validity())
					                                                              // price.validity() cannot be null, but IntelliJ doesn't know that there :(
					                                                              .map(
						                                                              existingValidity -> existingValidity.overlaps(
							                                                              Objects.requireNonNull(
								                                                              price.validity())))
					                                                              .orElse(true)
		                                                      )
		                                                      // the conflicting prices don't play role if they're going to be removed in the same update
		                                                      .filter(it -> !(this.priceMutations.get(
			                                                      it.priceKey()) instanceof RemovePriceMutation))
		                                                      .findFirst()
		                                                      .orElse(null);
		if (conflictingPrice != null && !this.removeAllNonModifiedPrices) {
			throw new AmbiguousPriceException(conflictingPrice, price);
		}
		// check whether there is no conflicting update
		final UpsertPriceMutation conflictingMutation = this.priceMutations.values().stream()
		                                                                   .filter(
			                                                                   UpsertPriceMutation.class::isInstance)
		                                                                   .map(UpsertPriceMutation.class::cast)
		                                                                   .filter(it -> it.getPriceKey()
		                                                                                   .priceList()
		                                                                                   .equals(price.priceList()))
		                                                                   .filter(it -> it.getPriceKey()
		                                                                                   .currency()
		                                                                                   .equals(price.currency()))
		                                                                   .filter(it -> it.getPriceKey()
		                                                                                   .priceId() != price.priceId())
		                                                                   .filter(it -> Objects.equals(
			                                                                   it.getInnerRecordId(),
			                                                                   price.innerRecordId()
		                                                                   ))
		                                                                   .filter(it ->
			                                                                           price.validity() == null ||
				                                                                           ofNullable(it.getValidity())
					                                                                           // price.validity() cannot be null, but IntelliJ doesn't know that there :(
					                                                                           .map(
						                                                                           existingValidity -> existingValidity.overlaps(
							                                                                           Objects.requireNonNull(
								                                                                           price.validity())))
					                                                                           .orElse(true)
		                                                                   )
		                                                                   .findFirst()
		                                                                   .orElse(null);
		if (conflictingMutation != null) {
			throw new AmbiguousPriceException(
				conflictingMutation.mutateLocal(
					this.entitySchema,
					this.basePrices.getPriceWithoutSchemaCheck(conflictingMutation.getPriceKey()).orElse(null)
				),
				price
			);
		}
	}

	/**
	 * Ensures that price data were fetched with the required content-mode before any mutation is allowed.
	 *
	 * This method validates that the provided price predicate requires at least passed {@link PriceContentMode}. If it does
	 * not, any price update operation would operate on incomplete data and is therefore rejected.
	 *
	 * @throws IllegalArgumentException when prices were not fetched with ALL content mode
	 */
	private void assertPricesFetched(@Nonnull PriceContentMode requiredMode) {
		Assert.isTrue(
			this.pricePredicate.getPriceContentMode().ordinal() >= requiredMode.ordinal(),
			"Prices were not fetched and cannot be updated." +
				" Please enrich the entity first or load it with `" + requiredMode + " ` price requirement."
		);
	}

	/**
	 * Asserts that the necessary prices have been fetched and match the provided predicate.
	 * If the price does not exist or does not satisfy the predicate, an exception is thrown.
	 *
	 * @param priceKey     the primary identifier of the price, consisting of the price ID, price list, and currency
	 * @param requiredMode the minimum required content mode for the prices
	 */
	private void assertPricesFetchedAndMatchPredicate(
		@Nonnull PriceKey priceKey,
		@Nonnull PriceContentMode requiredMode
	) {
		assertPricesFetched(requiredMode);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					priceKey, 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price `" + priceKey + "` was not fetched and cannot be updated. " +
				"Please enrich the entity first or load it with the prices."
		);
	}

}
