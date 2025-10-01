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
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.dataType.set.LazyHashSet;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.data.structure.InitialPricesBuilder.assertPricesAllowed;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link io.evitadb.api.requestResponse.data.structure.Prices}. Prices is immutable object so there is need for another object
 * that would simplify the process of updating its contents. This is why the builder class exists.
 */

/**
 * Mutable builder for updating existing {@link Prices} snapshot.
 *
 * This builder keeps track of price mutations to be applied on top of immutable {@link Prices}.
 * It validates schema constraints, ensures price content has been fetched with required
 * {@link PriceContentMode}, prevents ambiguous price combinations and can optionally remove
 * all non‑touched prices.
 */
public class ExistingPricesBuilder implements PricesBuilder {
	@Serial private static final long serialVersionUID = 5366182867172493114L;

	/**
	 * Predicate representing the fetched price context (content mode, currency, price lists, validity).
	 * It is used to filter prices returned from this builder and to assert whether operations are allowed.
	 */
	@Nonnull @Getter private final PriceContractSerializablePredicate pricePredicate;
	/**
	 * Collected mutations keyed by {@link PriceKey}. Only effective (changed) mutations are stored.
	 */
	private final Map<PriceKey, PriceMutation> priceMutations = new LazyHashMap<>(16);
	/**
	 * Set of price keys that were explicitly touched by this builder (added/removed/updated).
	 * Used when removing non‑touched prices to preserve only modified ones.
	 */
	private final Set<PriceKey> touchedPrices = new LazyHashSet<>(16);
	/**
	 * Entity schema for validation of price operations (allowed currencies, presence of prices etc.).
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Base immutable prices instance that this builder mutates virtually.
	 */
	private final Prices basePrices;
	/**
	 * Pending mutation for {@link PriceInnerRecordHandling} change, if any.
	 */
	@Nullable private SetPriceInnerRecordHandlingMutation priceInnerRecordHandlingEntityMutation;
	/**
	 * When enabled, all existing prices that were not touched by this builder are removed
	 * in the resulting change set/build result.
	 */
	private boolean removeAllNonModifiedPrices;

	/**
	 * Creates a builder over existing {@link Prices} with an empty price context predicate.
	 *
	 * @param entitySchema schema used to validate price operations
	 * @param prices       base immutable prices snapshot to mutate virtually
	 */
	public ExistingPricesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Prices prices
	) {
		this.basePrices = prices;
		this.entitySchema = entitySchema;
		this.pricePredicate = new PriceContractSerializablePredicate();
	}

	/**
	 * Creates a builder over existing {@link Prices} with a predefined price context predicate.
	 * Use this constructor when prices were fetched with known content requirements.
	 *
	 * @param entitySchema   schema used to validate price operations
	 * @param prices         base immutable prices snapshot to mutate virtually
	 * @param pricePredicate predicate describing fetched prices (content mode, currency, price lists)
	 */
	public ExistingPricesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Prices prices,
		@Nonnull PriceContractSerializablePredicate pricePredicate
	) {
		this.basePrices = prices;
		this.entitySchema = entitySchema;
		this.pricePredicate = pricePredicate;
	}

	/**
	 * Adds a {@link SetPriceInnerRecordHandlingMutation} and keeps it only when it changes
	 * the resulting {@link PriceInnerRecordHandling}.
	 *
	 * - Validates that prices are allowed by the schema
	 * - Computes the prospective value and stores the mutation only when it differs
	 *
	 * @param mutation mutation to set price inner record handling
	 */
	public void addMutation(@Nonnull SetPriceInnerRecordHandlingMutation mutation) {
		assertPricesAllowed(this.entitySchema);
		final PriceInnerRecordHandling updatedValue = mutation.mutateLocal(
			this.entitySchema, this.basePrices
		).getPriceInnerRecordHandling();
		if (updatedValue != this.basePrices.getPriceInnerRecordHandling()) {
			this.priceInnerRecordHandlingEntityMutation = mutation;
		} else {
			this.priceInnerRecordHandlingEntityMutation = null;
		}
	}

	/**
	 * Adds a price mutation to the builder.
	 *
	 * Supported mutation types:
	 * - {@link UpsertPriceMutation}: validates schema and ambiguity, stores only effective changes
	 * - {@link RemovePriceMutation}: validates existence, stores only effective removal
	 *
	 * Also marks the price as touched to cooperate with {@link #removeAllNonTouchedPrices()}.
	 *
	 * @param mutation mutation to apply (upsert/remove)
	 * @throws AmbiguousPriceException  when the resulting combination would be ambiguous
	 * @throws InvalidMutationException when removing a non-existing price
	 */
	public void addMutation(@Nonnull PriceMutation mutation) {
		if (mutation instanceof UpsertPriceMutation upsertPriceMutation) {
			final PriceKey priceKey = upsertPriceMutation.getPriceKey();
			assertPricesAllowed(this.entitySchema, priceKey.currency());
			final PriceContract existingValue = this.basePrices
				.getPriceWithoutSchemaCheck(priceKey)
				.orElse(null);
			final PriceContract updatedValue = mutation.mutateLocal(this.entitySchema, existingValue);
			if (existingValue == null || updatedValue.differsFrom(existingValue)) {
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
			} else {
				this.priceMutations.remove(priceKey);
			}
			this.touchedPrices.add(mutation.getPriceKey());
		} else if (mutation instanceof RemovePriceMutation removePriceMutation) {
			final PriceKey priceKey = removePriceMutation.getPriceKey();
			assertPricesAllowed(this.entitySchema);
			final PriceContract existingValue = this.basePrices
				.getPriceWithoutSchemaCheck(priceKey)
				.orElse(null);
			Assert.isTrue(
				existingValue != null && existingValue.exists(),
				() -> new InvalidMutationException("Price " + priceKey + " doesn't exist!")
			);
			final PriceContract updatedValue = mutation.mutateLocal(this.entitySchema, existingValue);
			if (updatedValue.differsFrom(existingValue)) {
				this.priceMutations.put(priceKey, removePriceMutation);
			}
			this.touchedPrices.add(mutation.getPriceKey());
		} else {
			throw new GenericEvitaInternalError("Unknown Evita price mutation: `" + mutation.getClass() + "`!");
		}
	}

	/**
	 * Upserts a price without inner record id or validity.
	 *
	 * @param priceId         technical price id (unique within price list + currency)
	 * @param priceList       price list identifier
	 * @param currency        currency of the price
	 * @param priceWithoutTax net price
	 * @param taxRate         tax rate
	 * @param priceWithTax    gross price
	 * @param indexed         whether the price should be indexed for filtering/sorting
	 * @return this builder
	 */
	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed
	) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, null, indexed);
	}

	/**
	 * Upserts a price with optional inner record id and no validity.
	 *
	 * Inner record id is used with {@link PriceInnerRecordHandling#SUM} and similar strategies.
	 *
	 * @param priceId         technical price id
	 * @param priceList       price list identifier
	 * @param currency        currency of the price
	 * @param innerRecordId   optional inner record id grouping
	 * @param priceWithoutTax net price
	 * @param taxRate         tax rate
	 * @param priceWithTax    gross price
	 * @param indexed         whether the price should be indexed
	 * @return this builder
	 */
	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		return setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, null, indexed);
	}

	/**
	 * Upserts a price without inner record id but with validity range.
	 *
	 * @param priceId         technical price id
	 * @param priceList       price list identifier
	 * @param currency        currency of the price
	 * @param priceWithoutTax net price
	 * @param taxRate         tax rate
	 * @param priceWithTax    gross price
	 * @param validity        active range of the price or {@code null} for always valid
	 * @param indexed         whether the price should be indexed
	 * @return this builder
	 */
	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean indexed
	) {
		return setPrice(priceId, priceList, currency, null, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
	}

	/**
	 * Upserts a price with optional inner record id and validity.
	 *
	 * Ambiguity is checked against existing and pending prices (overlapping validity, same priceList/currency
	 * and same innerRecordId but different priceId).
	 *
	 * @param priceId         technical price id
	 * @param priceList       price list identifier
	 * @param currency        currency of the price
	 * @param innerRecordId   optional inner record id
	 * @param priceWithoutTax net price
	 * @param taxRate         tax rate
	 * @param priceWithTax    gross price
	 * @param validity        active range or {@code null}
	 * @param indexed         whether indexed
	 * @return this builder
	 */
	@Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		addMutation(
			new UpsertPriceMutation(
				new PriceKey(priceId, priceList, currency),
				innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed
			)
		);
		return this;
	}

	/**
	 * Schedules removal of a specific price identified by id + priceList + currency.
	 *
	 * @param priceId   technical price id
	 * @param priceList price list identifier
	 * @param currency  currency of the price
	 * @return this builder
	 * @throws InvalidMutationException when the price does not exist
	 */
	@Override
	public PricesBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		addMutation(new RemovePriceMutation(new PriceKey(priceId, priceList, currency)));
		return this;
	}

	/**
	 * Sets {@link PriceInnerRecordHandling} for this price container.
	 *
	 * @param priceInnerRecordHandling handling strategy
	 * @return this builder
	 */
	@Override
	public PricesBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		addMutation(new SetPriceInnerRecordHandlingMutation(priceInnerRecordHandling));
		return this;
	}

	/**
	 * Resets {@link PriceInnerRecordHandling} to {@link PriceInnerRecordHandling#NONE}.
	 *
	 * @return this builder
	 */
	@Override
	public PricesBuilder removePriceInnerRecordHandling() {
		addMutation(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE));
		return this;
	}

	/**
	 * Enables removal of all existing prices that were not touched by this builder.
	 * Requires prices to be fetched with {@link PriceContentMode#ALL}.
	 *
	 * @return this builder
	 * @throws IllegalArgumentException when prices were not fetched with required content mode
	 */
	@Override
	public PricesBuilder removeAllNonTouchedPrices() {
		assertPricesAllowed(this.entitySchema);
		assertPricesFetched(PriceContentMode.ALL);
		this.removeAllNonModifiedPrices = true;
		return this;
	}

	/**
	 * Whether price data are available in the underlying {@link Prices} instance.
	 */
	@Override
	public boolean pricesAvailable() {
		return this.basePrices.pricesAvailable();
	}

	/**
	 * Returns a price by its {@link PriceKey} considering pending mutations and the active predicate.
	 *
	 * @param priceKey key composed of price id, price list and currency
	 * @return optionally the price if it exists and matches the predicate
	 * @throws ContextMissingException when required context (content mode) is missing
	 */
	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) throws ContextMissingException {
		return getPriceInternal(priceKey);
	}

	/**
	 * Returns a price by tuple (priceId, priceList, currency) with predicate and mutations applied.
	 */
	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return getPriceInternal(new PriceKey(priceId, priceList, currency));
	}

	/**
	 * Returns a single price for given price list and currency when and only when exactly one exists
	 * after applying the active predicate and pending mutations.
	 *
	 * @throws UnexpectedResultCountException when multiple prices match
	 * @throws ContextMissingException        when required context is missing
	 */
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

	/**
	 * Whether the context required to compute price-for-sale is available in the predicate.
	 */
	@Override
	public boolean isPriceForSaleContextAvailable() {
		return this.pricePredicate.isContextAvailable();
	}

	/**
	 * Returns the active price-for-sale context if available.
	 */
	@Nonnull
	@Override
	public Optional<PriceForSaleContext> getPriceForSaleContext() {
		return this.pricePredicate.getPriceForSaleContext();
	}

	/**
	 * Computes the single price-for-sale according to the predicate (currency, lists, validity).
	 *
	 * @return optionally the price-for-sale
	 * @throws ContextMissingException when predicate does not provide required context
	 */
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

	/**
	 * Same as {@link #getPriceForSale()} but never throws, returning empty when context is missing.
	 */
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

	/**
	 * Returns all prices-for-sale for the active currency and price lists in the predicate.
	 * Requires prices to be fetched with {@link PriceContentMode#ALL}.
	 *
	 * @throws ContextMissingException when predicate lacks currency or price lists
	 */
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

	/**
	 * Checks if any price-for-sale exists within the specified interval using the given mode.
	 *
	 * @param from           lower bound inclusive
	 * @param to             upper bound inclusive
	 * @param queryPriceMode whether to compare net or gross prices
	 * @return true if price exists in interval
	 * @throws ContextMissingException when predicate context is missing
	 */
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

	/**
	 * Returns all prices matching the active predicate with local mutations applied.
	 */
	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		return getPricesWithoutPredicate()
			.filter(PriceContract::exists)
			.filter(this.pricePredicate)
			.collect(Collectors.toList());
	}

	/**
	 * Returns the effective {@link PriceInnerRecordHandling} reflecting pending mutation if present.
	 */
	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		return ofNullable(this.priceInnerRecordHandlingEntityMutation)
			.map(it -> it.mutateLocal(this.entitySchema, this.basePrices).getPriceInnerRecordHandling())
			.orElseGet(this.basePrices::getPriceInnerRecordHandling);
	}

	/**
	 * Returns the version of the base prices container.
	 */
	@Override
	public int version() {
		return this.basePrices.version();
	}

	/**
	 * Builds the stream of local mutations representing changes accumulated in this builder.
	 *
	 * - Emits {@link SetPriceInnerRecordHandlingMutation} when changed
	 * - Emits effective {@link UpsertPriceMutation} and {@link RemovePriceMutation}
	 * - When {@link #removeAllNonTouchedPrices()} was requested, removes all non-touched existing prices
	 */
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
			return Stream
				.concat(
					this.priceInnerRecordHandlingEntityMutation == null || Objects.equals(
						this.basePrices.getPriceInnerRecordHandling(),
						this.priceInnerRecordHandlingEntityMutation.getPriceInnerRecordHandling()
					) ?
						Stream.empty() : Stream.of(this.priceInnerRecordHandlingEntityMutation),
					Stream.concat(
						this.priceMutations
							.values()
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
							.filter(it -> !this.touchedPrices.contains(it.priceKey()))
							.map(it -> new RemovePriceMutation(it.priceKey()))
					)
				)
				.filter(Objects::nonNull);
		} else {
			return Stream
				.concat(
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
						                   final PriceContract newPrice = it.mutateLocal(
							                   this.entitySchema, existingValue);
						                   builtPrices.put(it.getPriceKey(), newPrice);
						                   return existingValue == null || newPrice.version() > existingValue.version();
					                   })
				)
				.filter(Objects::nonNull);
		}
	}

	/**
	 * Builds a new {@link Prices} instance if there are any effective changes, otherwise returns
	 * the original instance to avoid unnecessary allocations.
	 */
	@Nonnull
	@Override
	public Prices build() {
		if (isThereAnyChangeInMutations()) {
			final Collection<PriceContract> newPrices = getPricesWithoutPredicate().collect(Collectors.toList());
			return new Prices(
				this.basePrices.entitySchema,
				this.basePrices.version() + 1,
				newPrices,
				getPriceInnerRecordHandling(),
				!newPrices.isEmpty()
			);
		} else {
			return this.basePrices;
		}
	}

	/**
	 * Checks if there is any change in the current set of price-related mutations.
	 * The method evaluates whether price mutations exist, whether all non-modified prices should be removed,
	 * or whether an internal record handling mutation is present.
	 *
	 * @return {@code true} if there is any change in mutations, otherwise {@code false}.
	 */
	public boolean isThereAnyChangeInMutations() {
		return this.priceInnerRecordHandlingEntityMutation != null ||
			this.removeAllNonModifiedPrices ||
			!this.priceMutations.isEmpty();
	}

	/**
	 * Produces a stream of prices with local mutations applied but without filtering by predicate.
	 * Useful for building the change set and final container.
	 */
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
						     .orElseGet(() -> this.removeAllNonModifiedPrices && !this.touchedPrices.contains(
							     it.priceKey()) ? null : it)
				)
				.filter(Objects::nonNull),
			this.priceMutations
				.values()
				.stream()
				.filter(it -> this.basePrices.getPriceWithoutSchemaCheck(it.getPriceKey()).isEmpty())
				.map(it -> it.mutateLocal(this.entitySchema, null))
		);
	}

	/**
	 * Internal resolver that returns a price by key with pending mutation applied first and
	 * finally filtered by the active predicate.
	 */
	@Nonnull
	private Optional<PriceContract> getPriceInternal(@Nonnull PriceKey priceKey) {
		assertPricesFetchedAndMatchPredicate(priceKey, PriceContentMode.RESPECTING_FILTER);
		final Optional<PriceContract> price = this.basePrices
			.getPriceWithoutSchemaCheck(priceKey)
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
	 * Verifies that adding/upserting the provided price does not lead to an ambiguous state.
	 *
	 * Ambiguity rules:
	 * - Two different priceIds in the same priceList + currency and the same innerRecordId
	 * must not have overlapping validity intervals
	 * - Conflicts are checked against both existing prices and pending upsert mutations
	 * - Conflicts scheduled for removal within this builder are ignored
	 *
	 * @param price transient price being considered
	 * @throws AmbiguousPriceException when a conflict is detected
	 */
	private void assertPriceNotAmbiguousBeforeAdding(@Nonnull PriceContract price) {
		// check whether new price doesn't conflict with original prices
		final PriceContract conflictingPrice = this.basePrices
			.getPrices().stream()
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
		final UpsertPriceMutation conflictingMutation = this.priceMutations
			.values()
			.stream()
			.filter(
				UpsertPriceMutation.class::isInstance)
			.map(UpsertPriceMutation.class::cast)
			.filter(it -> it.getPriceKey().priceList().equals(price.priceList()))
			.filter(it -> it.getPriceKey().currency().equals(price.currency()))
			.filter(it -> it.getPriceKey().priceId() != price.priceId())
			.filter(it -> Objects.equals(it.getInnerRecordId(), price.innerRecordId()))
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
	 * Ensures that price data were fetched with at least the required content mode before operations.
	 *
	 * @param requiredMode minimal acceptable {@link PriceContentMode}
	 * @throws IllegalArgumentException when prices were not fetched with sufficient content mode
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
