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

package io.evitadb.core.query.filter.translator.price.alternative;

import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.CumulatedPrice;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.function.QuadriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.spi.store.catalog.shared.model.PriceInternalIdContainer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of {@link EntityToBitmapFilter} that verifies that the entity has the "selling price" available.
 * The proper "selling price" is derived from the {@link QueryPlanningContext} automatically by specifying
 * {@link PriceContentMode#RESPECTING_FILTER} which in turn retrieves all basic constraints such as price list / currency
 * from the {@link EvitaRequest}. Only the price between must be handled locally.
 *
 * Translators create the filter with {@link #forRequest}, which captures the request's price-for-sale context the
 * prefetched entities resolve their selling price in; the constructors capture no context and keep an
 * instance-unique key.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SellingPriceAvailableBitmapFilter implements EntityToBitmapFilter, FilteredPriceRecordAccessor, FilteredOutPriceRecordAccessor {
	/** Identifier of the selling-price alternative. */
	private static final long CLASS_ID = -8990446315162675198L;
	/** Immutable ordered additional price lists, normalized to an empty list when absent. */
	private final List<String> additionalPriceLists;
	/**
	 * Price lists of the request's price-for-sale context in priority order; the prefetched entities resolve their
	 * selling price from them. Empty when the request names none or the context was not captured.
	 */
	private final List<String> priceLists;
	/** Currency of the request's price-for-sale context, absent when the request names none. */
	@Nullable private final Currency currency;
	/** Moment the selling prices must be valid in, absent when the request names none. */
	@Nullable private final OffsetDateTime validIn;
	/**
	 * Query price mode deciding which total a cumulated selling price record carries, absent when the context was not
	 * captured.
	 */
	@Nullable private final QueryPriceMode queryPriceMode;
	/** Opaque marker of a filter created without the request's price-for-sale context, null when it was captured. */
	@Nullable private final IdentityToken priceContext;

	private static final EntityFetch ENTITY_REQUIRE = new EntityFetch(new PriceContent(PriceContentMode.RESPECTING_FILTER));

	/**
	 * Internal function that converts {@link PriceContract} from the entity to the {@link PriceRecordContract} that is
	 * used in filtration logic.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final QuadriFunction<Integer, Integer, QueryPriceMode, PriceContract, PriceRecordContract> converter;
	/**
	 * The entity fetch to ask for.
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final EntityFetch entityFetch;
	/**
	 * Contains the predicate that must be fulfilled in order selling price is accepted by the filter.
	 */
	private final PriceContractPredicate filter;
	/**
	 * Contains array of price records that links to the price ids produced by {@link EntityToBitmapFilter#filter(QueryExecutionContext)}
	 * method. This object is available once the {@link EntityToBitmapFilter#filter(QueryExecutionContext)}  method has been called.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FilteredPriceRecords filteredPriceRecords;
	/**
	 * Memoized result of the filter.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Bitmap memoizedResult;

	/**
	 * Creates the filter without capturing the request's price-for-sale context. Its key is instance-unique, because
	 * the prefetched entities select by a context this instance does not know; translators use {@link #forRequest}.
	 *
	 * @param additionalPriceLists price lists fetched in addition to those of the price-for-sale context
	 * @param filter predicate the selling price must satisfy
	 */
	public SellingPriceAvailableBitmapFilter(
		@Nullable String[] additionalPriceLists,
		@Nonnull PriceContractPredicate filter
	) {
		this(additionalPriceLists, filter, List.of(), null, null, null, IdentityToken.OPAQUE);
	}

	/**
	 * Creates the filter accepting any selling price, without capturing the request's price-for-sale context.
	 *
	 * @param additionalPriceLists price lists fetched in addition to those of the price-for-sale context
	 * @see #SellingPriceAvailableBitmapFilter(String[], PriceContractPredicate)
	 */
	public SellingPriceAvailableBitmapFilter(@Nullable String... additionalPriceLists) {
		this(additionalPriceLists, PricePredicate.ALL_CONTRACT_FILTER);
	}

	/**
	 * Creates the filter for a query whose prefetched entities resolve their selling price in the request's
	 * price-for-sale context. The filter reads that context through the prefetched entities and the query price mode
	 * through the execution context, so the ordered price lists, the currency, the validity moment and the query
	 * price mode of the request become its identity inputs.
	 *
	 * @param request request of the query the prefetched entities are fetched for
	 * @param filter predicate the selling price must satisfy
	 * @return the filter with a value key
	 */
	@Nonnull
	public static SellingPriceAvailableBitmapFilter forRequest(
		@Nonnull EvitaRequest request,
		@Nonnull PriceContractPredicate filter
	) {
		return new SellingPriceAvailableBitmapFilter(
			request.getFetchesAdditionalPriceLists(), filter, List.of(request.getRequiresPriceLists()),
			request.getRequiresCurrency(), request.getRequiresPriceValidIn(), request.getQueryPriceMode(), null
		);
	}

	/** Builds the execution strategy together with the captured or opaque price-for-sale context. */
	private SellingPriceAvailableBitmapFilter(
		@Nullable String[] additionalPriceLists,
		@Nonnull PriceContractPredicate filter,
		@Nonnull List<String> priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nullable QueryPriceMode queryPriceMode,
		@Nullable IdentityToken priceContext
	) {
		this.additionalPriceLists = ArrayUtils.isEmpty(additionalPriceLists) ? List.of() : List.of(additionalPriceLists);
		this.priceLists = priceLists;
		this.currency = currency;
		this.validIn = validIn;
		this.queryPriceMode = queryPriceMode;
		this.priceContext = priceContext;
		this.entityFetch = ArrayUtils.isEmpty(additionalPriceLists) ? ENTITY_REQUIRE : new EntityFetch(PriceContent.respectingFilter(additionalPriceLists));
		this.converter = (entityPrimaryKey, indexedPricePlaces, priceQueryMode, priceContract) -> {
			if (priceContract instanceof CumulatedPrice cumulatedPrice) {
				final Map<Integer, PriceContract> innerRecordIds = cumulatedPrice.innerRecordPrices();
				final IntObjectMap<PriceRecordContract> intSetInnerRecordIds = new IntObjectHashMap<>(innerRecordIds.size());
				for (Entry<Integer, PriceContract> entry : innerRecordIds.entrySet()) {
					final PriceContract innerRecordPrice = entry.getValue();
					if (entry.getKey() != null) {
						intSetInnerRecordIds.put(
							entry.getKey(),
							new PriceRecordInnerRecordSpecific(
								-1,
								innerRecordPrice.priceId(),
								entityPrimaryKey,
								Objects.requireNonNull(innerRecordPrice.innerRecordId()),
								NumberUtils.convertExternalNumberToInt(innerRecordPrice.priceWithTax(), indexedPricePlaces),
								NumberUtils.convertExternalNumberToInt(innerRecordPrice.priceWithoutTax(), indexedPricePlaces)
							)
						);
					}
				}
				return new CumulatedVirtualPriceRecord(
					entityPrimaryKey,
					priceQueryMode == QueryPriceMode.WITH_TAX ?
						NumberUtils.convertExternalNumberToInt(cumulatedPrice.priceWithTax(), indexedPricePlaces) :
						NumberUtils.convertExternalNumberToInt(cumulatedPrice.priceWithoutTax(), indexedPricePlaces),
					priceQueryMode,
					intSetInnerRecordIds
				);
			} else if (priceContract.innerRecordId() == null) {
				return new PriceRecord(
					priceContract instanceof PriceInternalIdContainer priceWithInternalIds ?
						priceWithInternalIds.getInternalPriceId() : -1,
						priceContract.priceId(),
					entityPrimaryKey,
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithTax(), indexedPricePlaces),
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithoutTax(), indexedPricePlaces)
				);
			} else {
				return new PriceRecordInnerRecordSpecific(
					priceContract instanceof PriceInternalIdContainer priceWithInternalIds ?
						priceWithInternalIds.getInternalPriceId() : -1,
					priceContract.priceId(),
					entityPrimaryKey,
					Objects.requireNonNull(priceContract.innerRecordId()),
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithTax(), indexedPricePlaces),
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithoutTax(), indexedPricePlaces)
				);
			}
		};
		this.filter = filter;
	}

	@Nonnull
	@Override
	public EntityFetch getEntityRequire() {
		return this.entityFetch;
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		return this.filter.getRequestedPredicate();
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		if (this.filteredPriceRecords == null) {
			// init the records first
			filter(context);
		}
		return this.filteredPriceRecords;
	}

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull QueryExecutionContext context) {
		if (this.memoizedResult == null) {
			final CompositeObjectArray<PriceRecordContract> theFilteredPriceRecords = new CompositeObjectArray<>(PriceRecordContract.class);
			final QueryPriceMode queryPriceMode = context.getQueryPriceMode();
			final BaseBitmap result = new BaseBitmap();
			final AtomicInteger indexedPricePlaces = new AtomicInteger();
			String entityType = null;
			final List<ServerEntityDecorator> entities = context.getPrefetchedEntities();
			if (entities == null) {
				this.memoizedResult = EmptyBitmap.INSTANCE;
			} else {
				// iterate over all entities
				for (EntityDecorator entity : entities) {
				/* we can be sure entities are sorted by type because:
				   1. all entities share the same type
				   2. or entities are fetched via {@link QueryPlanningContext#prefetchEntities(EntityReference[], EntityContentRequire[])}
				      that fetches them by entity type in bulk
				*/
					final EntitySchemaContract entitySchema = entity.getSchema();
					if (!Objects.equals(entityType, entitySchema.getName())) {
						entityType = entitySchema.getName();
						indexedPricePlaces.set(entitySchema.getIndexedPricePlaces());
					}
					final int primaryKey = context.translateEntity(entity);
					if (entity.isPriceForSaleContextAvailable()) {
						// check whether they have valid selling price (applying filter on price lists and currency)
						entity.getPriceForSale(this.filter)
							// and if there is still selling price add it to the output result
							.ifPresent(
								it -> {
									theFilteredPriceRecords.add(this.converter.apply(primaryKey, indexedPricePlaces.get(), queryPriceMode, it));
									result.add(primaryKey);
								}
							);
					} else {
						if (entity.getPrices().stream().filter(PriceContract::indexed).anyMatch(this.filter)) {
							result.add(primaryKey);
						}
					}

				}
				// memoize valid selling prices for sorting purposes
				this.filteredPriceRecords = new ResolvedFilteredPriceRecords(theFilteredPriceRecords.toArray(), SortingForm.NOT_SORTED);
				// return entity ids having selling prices
				this.memoizedResult = result;
			}
		}
		return this.memoizedResult;
	}

	/**
	 * Returns the inputs the filter reads: the additional fetched price lists, the selling-price predicate and the
	 * request's price-for-sale context - ordered price lists, currency, validity moment and query price mode. A filter
	 * created by a constructor carries the opaque context marker instead.
	 */
	@Nonnull
	@Override
	public IdentityRecord identity() {
		return new Identity(this.additionalPriceLists, this.filter.identity(), this.priceLists, this.currency,
			this.validIn, this.queryPriceMode, this.priceContext);
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param additionalPriceLists ordered additional fetched price lists, empty when absent
	 * @param filter detached selling-price predicate
	 * @param priceLists price lists of the price-for-sale context in priority order, empty when absent
	 * @param currency currency of the price-for-sale context, absent when the request names none
	 * @param validIn moment the selling prices must be valid in, absent when the request names none
	 * @param queryPriceMode price mode deciding which total a cumulated selling price carries, absent when the context
	 *                       was not captured
	 * @param priceContext opaque marker of a context that was not captured, null when it was
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull List<String> additionalPriceLists,
		@Nonnull PricePredicate.Identity filter,
		@Nonnull List<String> priceLists,
		@Nullable Currency currency,
		@Nullable OffsetDateTime validIn,
		@Nullable QueryPriceMode queryPriceMode,
		@Nullable IdentityToken priceContext
	) implements IdentityRecord {
		/** Copies the price lists so every identity remains detached and immutable. */
		public Identity {
			additionalPriceLists = List.copyOf(additionalPriceLists);
			priceLists = List.copyOf(priceLists);
		}

		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.addStrings(this.additionalPriceLists);
			accumulator.add(this.filter);
			accumulator.addStrings(this.priceLists);
			accumulator.add(this.currency);
			accumulator.add(this.validIn);
			accumulator.add(this.queryPriceMode);
			accumulator.add(this.priceContext);
		}
	}

	/** Returns the declaring alternative identifier. */
	@Override
	public long getClassId() {
		return CLASS_ID;
	}

}
