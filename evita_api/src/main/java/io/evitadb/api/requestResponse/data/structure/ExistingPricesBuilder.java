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
import io.evitadb.api.requestResponse.data.PriceRangeForSale;
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
 *
 * Ambiguous prices are refused when the change set is built ({@link #buildChangeSet()}, which the entity builder
 * calls to produce its mutation), never when a price is set: the decision is made on the state the change set leads
 * to, by the rule the server applies ({@link PriceAmbiguityVerifier}). {@link #build()} does not check it.
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
    @Nonnull
    @Override
	public ExistingPricesBuilder mutateInnerPriceHandling(@Nonnull SetPriceInnerRecordHandlingMutation mutation) {
		assertPricesAllowed(this.entitySchema);
		final PriceInnerRecordHandling updatedValue = mutation.mutateLocal(
			this.entitySchema, this.basePrices
		).getPriceInnerRecordHandling();
		if (updatedValue != this.basePrices.getPriceInnerRecordHandling()) {
			this.priceInnerRecordHandlingEntityMutation = mutation;
		} else {
			this.priceInnerRecordHandlingEntityMutation = null;
		}
        return this;
	}

	/**
	 * Adds a price mutation to the builder.
	 *
	 * Supported mutation types:
	 * - {@link UpsertPriceMutation}: validates schema, stores only effective changes
	 * - {@link RemovePriceMutation}: validates existence, stores only effective removal
	 *
	 * Also marks the price as touched to cooperate with {@link #removeAllNonTouchedPrices()}. Whether the prices are
	 * ambiguous is decided when the change set is built - see {@link #buildChangeSet()}.
	 *
	 * @param mutation mutation to apply (upsert/remove)
	 * @throws InvalidMutationException when removing a non-existing price
	 */
    @Nonnull
    @Override
	public ExistingPricesBuilder mutatePrice(@Nonnull PriceMutation mutation) {
		if (mutation instanceof UpsertPriceMutation upsertPriceMutation) {
			final PriceKey priceKey = upsertPriceMutation.getPriceKey();
			assertPricesAllowed(this.entitySchema, priceKey.currency());
			InitialPricesBuilder.assertInnerRecordIdValid(upsertPriceMutation.getInnerRecordId());
			final PriceContract existingValue = this.basePrices
				.getPriceWithoutSchemaCheck(priceKey)
				.orElse(null);
			final PriceContract updatedValue = mutation.mutateLocal(this.entitySchema, existingValue);
			if (existingValue == null || updatedValue.differsFrom(existingValue)) {
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
        return this;
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
	@Nonnull
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
	@Nonnull
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
	@Nonnull
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
	 * The price is not checked against the other prices here: a price valid at a common moment with another price of
	 * its price list, currency and inner record is refused when the change set is built - see
	 * {@link #buildChangeSet()}.
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
	@Nonnull
    @Override
	public PricesBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		mutatePrice(
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
	@Nonnull
    @Override
	public PricesBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		mutatePrice(new RemovePriceMutation(new PriceKey(priceId, priceList, currency)));
		return this;
	}

	/**
	 * Removes all prices that are currently set on the entity. Method removes only those prices, that are returned
	 * by the method {@link #getPrices()}, so that if the entity prices are limited to specific currency, price list
	 * or validity, only those prices are removed and not all present on the entity actually.
	 *
	 * @return builder instance to allow command chaining
	 */
	@Nonnull
    @Override
	public PricesBuilder removeAllPrices() {
		for (PriceContract price : getPrices()) {
			removePrice(price.priceId(), price.priceList(), price.currency());
		}
		return this;
	}

	/**
	 * Sets {@link PriceInnerRecordHandling} for this price container.
	 *
	 * @param priceInnerRecordHandling handling strategy
	 * @return this builder
	 */
	@Nonnull
    @Override
	public PricesBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		mutateInnerPriceHandling(new SetPriceInnerRecordHandlingMutation(priceInnerRecordHandling));
		return this;
	}

	/**
	 * Resets {@link PriceInnerRecordHandling} to {@link PriceInnerRecordHandling#NONE}.
	 *
	 * @return this builder
	 */
	@Nonnull
    @Override
	public PricesBuilder removePriceInnerRecordHandling() {
		mutateInnerPriceHandling(new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE));
		return this;
	}

	/**
	 * Enables removal of all existing prices that were not touched by this builder.
	 * Requires prices to be fetched with {@link PriceContentMode#ALL}.
	 *
	 * @return this builder
	 * @throws IllegalArgumentException when prices were not fetched with required content mode
	 */
	@Nonnull
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
	 * Returns the price mode of the query the base entity was fetched by, which the predicate carries even when no
	 * price for sale context exists.
	 */
	@Nonnull
	@Override
	public QueryPriceMode getQueryPriceMode() {
		return this.pricePredicate.getQueryPriceMode();
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
	 * Returns the full price range (lowest, highest, selling price) for the active predicate context.
	 *
	 * @throws ContextMissingException when predicate lacks currency or price lists
	 */
	@Nonnull
	@Override
	public Optional<PriceRangeForSale> getPriceRangeForSale() throws ContextMissingException {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
			return getPriceRangeForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	/**
	 * Same as {@link #getPriceRangeForSale()} but never throws, returning empty when context is missing.
	 */
	@Nonnull
	@Override
	public Optional<PriceRangeForSale> getPriceRangeForSaleIfAvailable() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() != null && !ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
			return getPriceRangeForSale(
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
	 * Requires prices to be fetched with at least {@link PriceContentMode#RESPECTING_FILTER}.
	 *
	 * @throws ContextMissingException when predicate lacks currency or price lists
	 */
	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		assertPricesFetched(PriceContentMode.RESPECTING_FILTER);
		if (this.pricePredicate.getCurrency() == null || ArrayUtils.isEmpty(this.pricePredicate.getPriceLists())) {
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
	 *
	 * The prices this builder adds, revives or moves to another validity or inner record are first checked against
	 * the other prices of the state the change set leads to - see {@link #assertPricesNotAmbiguous()}.
	 *
	 * @throws AmbiguousPriceException when such a price is valid at a common moment with another price of its price
	 *                                 list, currency and inner record (of its price list and currency, when the entity
	 *                                 ends up without inner record handling)
	 */
	@Nonnull
	@Override
	public Stream<? extends LocalMutation<?, ?>> buildChangeSet() {
		assertPricesNotAmbiguous();
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
	 * Verifies the state the change set leads to by the rule the server applies to an entity upsert
	 * ({@link PriceAmbiguityVerifier}): no price this builder adds, revives, or moves to another validity or inner
	 * record may be valid at a common moment with another price of its group - its price list, currency and inner
	 * record, or its price list and currency when the entity ends up without inner record handling. When this builder
	 * changes the inner record handling, every price counts as moved. An entity that already holds an ambiguous pair
	 * stays updatable as long as the pair is left alone. The check runs when the change set is built, because a price
	 * may still be removed or moved and the handling may still change after a price is set, and it sees only the
	 * prices the base entity was fetched with - the server checks the rest.
	 *
	 * Costs nothing but a pass over the pending mutations when the builder adds or moves no price and changes no
	 * handling.
	 *
	 * @throws AmbiguousPriceException when a touched price competes with another price of the entity
	 */
	private void assertPricesNotAmbiguous() {
		// the mutation is kept only when it changes the handling, and a change of the handling touches every price
		final SetPriceInnerRecordHandlingMutation handlingMutation = this.priceInnerRecordHandlingEntityMutation;
		final boolean handlingChanged = handlingMutation != null;
		if (!handlingChanged && !isAnyPriceAddedOrMoved()) {
			return;
		}
		// collect the existing prices of the state the change set leads to and those added or moved among them
		final Collection<PriceContract> originalPrices = this.basePrices.pricesAvailable() ?
			this.basePrices.getPrices() : Collections.emptyList();
		final PriceContract[] builtPrices = new PriceContract[originalPrices.size() + this.priceMutations.size()];
		final PriceContract[] addedOrMovedPrices = new PriceContract[builtPrices.length];
		int builtPriceCount = 0;
		int addedOrMovedPriceCount = 0;
		for (final PriceContract originalPrice : originalPrices) {
			final PriceMutation mutation = this.priceMutations.get(originalPrice.priceKey());
			if (mutation == null) {
				if (originalPrice.exists() &&
					!(this.removeAllNonModifiedPrices && !this.touchedPrices.contains(originalPrice.priceKey()))) {
					builtPrices[builtPriceCount++] = originalPrice;
					if (handlingChanged) {
						addedOrMovedPrices[addedOrMovedPriceCount++] = originalPrice;
					}
				}
			} else if (mutation instanceof UpsertPriceMutation) {
				final PriceContract builtPrice = mutation.mutateLocal(this.entitySchema, originalPrice);
				builtPrices[builtPriceCount++] = builtPrice;
				if (handlingChanged ||
					isAddedOrMoved(originalPrice, builtPrice.validity(), builtPrice.innerRecordId())) {
					addedOrMovedPrices[addedOrMovedPriceCount++] = builtPrice;
				}
			}
		}
		for (final PriceMutation mutation : this.priceMutations.values()) {
			if (mutation instanceof UpsertPriceMutation &&
				this.basePrices.getPriceWithoutSchemaCheck(mutation.getPriceKey()).isEmpty()) {
				final PriceContract builtPrice = mutation.mutateLocal(this.entitySchema, null);
				builtPrices[builtPriceCount++] = builtPrice;
				addedOrMovedPrices[addedOrMovedPriceCount++] = builtPrice;
			}
		}
		PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
			builtPrices, builtPriceCount, addedOrMovedPrices, addedOrMovedPriceCount,
			handlingChanged ?
				handlingMutation.getPriceInnerRecordHandling() : this.basePrices.getPriceInnerRecordHandling()
		);
	}

	/**
	 * Tells whether a pending upsert adds, revives, or moves a price to another validity or inner record - the
	 * qualification of a touched price the server applies.
	 *
	 * @return `true` when at least one pending upsert touches a price
	 */
	private boolean isAnyPriceAddedOrMoved() {
		for (final PriceMutation mutation : this.priceMutations.values()) {
			if (mutation instanceof UpsertPriceMutation upsertPriceMutation) {
				final PriceContract basePrice = this.basePrices
					.getPriceWithoutSchemaCheck(upsertPriceMutation.getPriceKey())
					.orElse(null);
				if (basePrice == null || isAddedOrMoved(
					basePrice, upsertPriceMutation.getValidity(), upsertPriceMutation.getInnerRecordId()
				)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Tells whether the upsert of a price the base entity holds revives it or moves it to another validity or inner
	 * record.
	 *
	 * @param basePrice     the price the base entity holds
	 * @param validity      validity of the price after the upsert
	 * @param innerRecordId inner record id of the price after the upsert
	 * @return `true` when the upsert touches the price
	 */
	private static boolean isAddedOrMoved(
		@Nonnull PriceContract basePrice,
		@Nullable DateTimeRange validity,
		@Nullable Integer innerRecordId
	) {
		return !basePrice.exists() ||
			!Objects.equals(basePrice.validity(), validity) ||
			!Objects.equals(basePrice.innerRecordId(), innerRecordId);
	}

	/**
	 * Ensures that price data were fetched with at least the required content mode before operations.
	 *
	 * @param requiredMode minimal acceptable {@link PriceContentMode}
	 * @throws ContextMissingException when prices were not fetched with sufficient content mode
	 */
	private void assertPricesFetched(@Nonnull PriceContentMode requiredMode) {
		Assert.isTrue(
			this.pricePredicate.getPriceContentMode().ordinal() >= requiredMode.ordinal(),
			() -> new ContextMissingException(
				"Prices were not fetched and cannot be updated." +
					" Please enrich the entity first or load it with `" + requiredMode + " ` price requirement."
			)
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
