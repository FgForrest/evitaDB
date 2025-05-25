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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.CumulatedPrice;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
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
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of {@link EntityToBitmapFilter} that verifies that the entity has the "selling price" available.
 * The proper "selling price" is derived from the {@link QueryPlanningContext} automatically by specifying
 * {@link PriceContentMode#RESPECTING_FILTER} which in turn retrieves all basic constraints such as price list / currency
 * from the {@link io.evitadb.api.requestResponse.EvitaRequest}. Only the price between must be handled locally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SellingPriceAvailableBitmapFilter implements EntityToBitmapFilter, FilteredPriceRecordAccessor, FilteredOutPriceRecordAccessor {
	private static final EntityFetch ENTITY_REQUIRE = new EntityFetch(new PriceContent(PriceContentMode.RESPECTING_FILTER));

	/**
	 * Internal function that converts {@link PriceContract} from the entity to the {@link PriceRecordContract} that is
	 * used in filtration logic.
	 */
	private final QuadriFunction<Integer, Integer, QueryPriceMode, PriceContract, PriceRecordContract> converter;
	/**
	 * The entity fetch to ask for.
	 */
	private final EntityFetch entityFetch;
	/**
	 * Contains the predicate that must be fulfilled in order selling price is accepted by the filter.
	 */
	private final PriceContractPredicate filter;
	/**
	 * Contains array of price records that links to the price ids produced by {@link EntityToBitmapFilter#filter(QueryExecutionContext)}
	 * method. This object is available once the {@link EntityToBitmapFilter#filter(QueryExecutionContext)}  method has been called.
	 */
	private FilteredPriceRecords filteredPriceRecords;
	/**
	 * Contains result containing all entities that were filtered out by {@link #filter} predicate.
	 */
	private Formula filteredOutRecords;
	/**
	 * Memoized result of the filter.
	 */
	private Bitmap memoizedResult;

	public SellingPriceAvailableBitmapFilter(
		@Nullable String[] additionalPriceLists,
		@Nonnull PriceContractPredicate filter
	) {
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
								innerRecordPrice.innerRecordId(),
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
					priceContract.innerRecordId(),
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithTax(), indexedPricePlaces),
					NumberUtils.convertExternalNumberToInt(priceContract.priceWithoutTax(), indexedPricePlaces)
				);
			}
		};
		this.filter = filter;
	}

	public SellingPriceAvailableBitmapFilter(@Nullable String... additionalPriceLists) {
		this(additionalPriceLists, PricePredicate.ALL_CONTRACT_FILTER);
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
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		Assert.isPremiseValid(this.filteredOutRecords != null, "Filter was not yet called on selling price bitmap filter, this is not expected!");
		return this.filteredOutRecords;
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
			final BaseBitmap filterOutResult = new BaseBitmap();
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
							.ifPresentOrElse(
								it -> {
									theFilteredPriceRecords.add(this.converter.apply(primaryKey, indexedPricePlaces.get(), queryPriceMode, it));
									result.add(primaryKey);
								},
								() -> filterOutResult.add(primaryKey)
							);
					} else {
						if (entity.getPrices().stream().filter(PriceContract::indexed).anyMatch(this.filter)) {
							result.add(primaryKey);
						} else {
							filterOutResult.add(primaryKey);
						}
					}

				}
				// memoize valid selling prices for sorting purposes
				this.filteredPriceRecords = new ResolvedFilteredPriceRecords(theFilteredPriceRecords.toArray(), SortingForm.NOT_SORTED);
				this.filteredOutRecords = filterOutResult.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(filterOutResult);
				// return entity ids having selling prices
				this.memoizedResult = result;
			}
		}
		return this.memoizedResult;
	}

}
