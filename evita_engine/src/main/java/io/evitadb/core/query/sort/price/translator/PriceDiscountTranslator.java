/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.sort.price.translator;

import com.carrotsearch.hppc.IntCollection;
import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceDiscount;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.comparator.IntComparator;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceFilteringEnvelopeContainer;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula.SumPredicate;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.filter.translator.price.PriceInPriceListsTranslator;
import io.evitadb.core.query.filter.translator.price.PriceListCompositionTerminationVisitor;
import io.evitadb.core.query.filter.translator.price.PriceValidInTranslator;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.price.FilteredPriceRecordsCollector;
import io.evitadb.core.query.sort.price.FilteredPricesSorter;
import io.evitadb.core.query.sort.price.FilteredPricesSorter.NonSortedRecordsProvider;
import io.evitadb.core.query.sort.price.FilteredPricesSorter.PriceRecordsLookupResultAware;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link PriceDiscount} to {@link Sorter}.
 * It sorts by the amount of the discount - i.e. the difference between the selling price and the reference price
 * specified by the array of price lists in prioritized order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PriceDiscountTranslator implements OrderingConstraintTranslator<PriceDiscount> {

	/**
	 * Creates a formula for calculation and accessing a reference price for entities based on the provided parameters.
	 * Reference price will be used for calculating the discount.
	 *
	 * @param orderByVisitor                  the visitor to handle order by
	 * @param priceFilteringEnvelopeContainer the container with price filtering envelope
	 * @param priceLists                      the array of price lists to be used
	 * @param queryPriceMode                  the mode to query price
	 * @return the created Formula, never null
	 */
	@Nonnull
	private static Formula createReferencePriceFormula(
		@Nonnull OrderByVisitor orderByVisitor,
		@Nonnull PriceFilteringEnvelopeContainer priceFilteringEnvelopeContainer,
		@Nonnull String[] priceLists,
		@Nonnull QueryPriceMode queryPriceMode
	) {
		final Currency currency = priceFilteringEnvelopeContainer.getCurrency();
		final OffsetDateTime theMoment = priceFilteringEnvelopeContainer.getValidIn();
		return PriceListCompositionTerminationVisitor.translate(
			theMoment == null ?
				PriceInPriceListsTranslator.createFormula(orderByVisitor.getFilterByVisitor(), priceLists, currency) :
				PriceValidInTranslator.createFormula(orderByVisitor.getFilterByVisitor(), theMoment, priceLists, currency),
			priceLists, currency, theMoment, queryPriceMode, null
		);
	}

	/**
	 * Creates a comparator for {@link PriceRecordContract} based on the provided order direction and query price mode.
	 *
	 * @param orderDirection the direction of ordering, must not be null
	 * @param queryPriceMode the mode to query price, must not be null
	 * @return a comparator for comparing {@link PriceRecordContract} objects based on price, never null
	 */
	@Nonnull
	private static Comparator<PriceRecordContract> createPriceComparator(
		@Nonnull OrderDirection orderDirection,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull List<Formula> referencePriceFormula
	) {
		final IntComparator priceComparator = orderDirection == OrderDirection.ASC ?
			Integer::compare : (o1, o2) -> Integer.compare(o2, o1);
		final ToIntFunction<PriceRecordContract> priceExtractor = queryPriceMode == QueryPriceMode.WITH_TAX ?
			PriceRecordContract::priceWithTax : PriceRecordContract::priceWithoutTax;
		return new DiscountIndexComparator(priceComparator, priceExtractor, referencePriceFormula);
	}

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull PriceDiscount priceDiscount, @Nonnull OrderByVisitor orderByVisitor) {
		if (orderByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = orderByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new EntityHasNoPricesException(schema.getName())
			);
		}

		final String[] priceLists = priceDiscount.getInPriceLists();
		final QueryPriceMode queryPriceMode = orderByVisitor.getQueryPriceMode();

		// if prefetch happens we need to prefetch prices so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(PriceContent.respectingFilter(priceLists));

		// are filtered prices used in the filtering?
		final Collection<PriceFilteringEnvelopeContainer> sellingPriceFilteringEnvelopeContainers = FormulaFinder.find(
			orderByVisitor.getFilteringFormula(), PriceFilteringEnvelopeContainer.class, LookUp.SHALLOW
		);

		final Sorter thisSorter;
		if (!sellingPriceFilteringEnvelopeContainers.isEmpty()) {
			// collect all the selling price record accessors
			final Collection<FilteredPriceRecordAccessor> sellingPriceRecordAccessors = FormulaFinder.find(
				orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
			);
			// if so, create filtered prices sorter
			thisSorter = new FilteredPricesSorter(
				// with specific kind of comparator
				createPriceComparator(
					priceDiscount.getOrder(),
					queryPriceMode,
					sellingPriceFilteringEnvelopeContainers.stream()
						.map(it -> createReferencePriceFormula(orderByVisitor, it, priceLists, queryPriceMode))
						.toList()
				),
				sellingPriceRecordAccessors
			);
		} else {
			// otherwise, we cannot sort the entities by price
			thisSorter = NoSorter.INSTANCE;
		}

		// and for the scenario where the entities are prefetched instantiate different comparator - for entity objects
		final EntityComparator entityComparator;
		final Function<PriceContract, BigDecimal> priceExtractor = queryPriceMode == QueryPriceMode.WITH_TAX ?
			PriceContract::priceWithTax : PriceContract::priceWithoutTax;
		if (priceDiscount.getOrder() == OrderDirection.ASC) {
			entityComparator = new EntityPriceDiscountEntityComparator(priceExtractor, Comparator.naturalOrder(), priceLists);
		} else {
			entityComparator = new EntityPriceDiscountEntityComparator(priceExtractor, Comparator.reverseOrder(), priceLists);
		}

		// return both sorters
		return Stream.of(
			new PrefetchedRecordsSorter(entityComparator),
			thisSorter
		);
	}

	/**
	 * Comparator for entities based on their discounted price. The discounted price is calculated
	 * using a provided function to extract the price and a comparator to compare the extracted prices.
	 * The class supports memoization for performance optimization.
	 *
	 * This comparator is used for prefetched entities when we can access full price information in the fetched
	 * body of entities.
	 */
	@SuppressWarnings("ComparatorNotSerializable")
	@RequiredArgsConstructor
	private static class EntityPriceDiscountEntityComparator implements EntityComparator {
		/**
		 * The name of the accompanying price that contains the discounted price.
		 */
		private static final String DISCOUNTED_PRICE = "discountedPrice";
		/**
		 * The constant representing a non-calculable discount. We need non-null representation for the memoization
		 * index. Otherwise, this value represent a factual NULL value.
		 */
		private static final BigDecimal NEGATIVE_DISCOUNT = BigDecimal.valueOf(-1);
		/**
		 * The function to extract the price from the price contract (sometimes the price with tax is used, sometimes
		 * the price without tax is used).
		 */
		private final Function<PriceContract, BigDecimal> priceExtractor;
		/**
		 * The comparator to compare the prices (ASC/DESC).
		 */
		private final Comparator<BigDecimal> priceComparator;
		/**
		 * The array of price lists to be used for calculating the discounted price.
		 */
		private final String[] discountPriceLists;
		/**
		 * Memoized discounts for the entities so that for each entity the discount is calculated only once.
		 * NULL values are represented by {@link #NEGATIVE_DISCOUNT}.
		 */
		private final IntObjectMap<BigDecimal> memoizedDiscounts = new IntObjectHashMap<>(64);
		/**
		 * Contains primary keys of entities for which the discounted price could not be calculated.
		 */
		private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return this.nonSortedEntities == null ? List.of() : this.nonSortedEntities;
		}

		@SuppressWarnings({"NumberEquality", "ObjectInstantiationInEqualsHashCode"})
		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			final BigDecimal priceDiscount1 = getPriceDiscount(o1);
			final BigDecimal priceDiscount2 = getPriceDiscount(o2);
			if (NEGATIVE_DISCOUNT == priceDiscount1 && NEGATIVE_DISCOUNT == priceDiscount2) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(o1);
				this.nonSortedEntities.add(o2);
				return Integer.compare(o1.getPrimaryKey(), o2.getPrimaryKey());
			} else if (NEGATIVE_DISCOUNT == priceDiscount1) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(o1);
				return 1;
			} else if (NEGATIVE_DISCOUNT == priceDiscount2) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(o2);
				return -1;
			} else {
				return priceComparator.compare(
					priceDiscount1,
					priceDiscount2
				);
			}
		}

		/**
		 * Calculates the discounted price for a given entity. If the discounted price has been memoized,
		 * it will return the memoized value. Otherwise, it will compute the discounted price based on
		 * the accompanying prices available in the entity.
		 *
		 * @param entity The entity for which the discounted price is to be calculated.
		 * @return The discounted price of the entity as a BigDecimal.
		 */
		@Nonnull
		private BigDecimal getPriceDiscount(@Nonnull EntityContract entity) {
			final BigDecimal memoizedResult = memoizedDiscounts.get(entity.getPrimaryKey());
			if (memoizedResult != null) {
				return memoizedResult;
			}
			final Optional<PriceForSaleWithAccompanyingPrices> calculatedPrices = entity.getPriceForSaleWithAccompanyingPrices(
				new AccompanyingPrice[]{
					new AccompanyingPrice(DISCOUNTED_PRICE, discountPriceLists)
				}
			);

			final BigDecimal calculatedResult = calculatedPrices
				.map(priceCalculation -> {
					final BigDecimal priceForSale = priceExtractor.apply(priceCalculation.priceForSale());
					return priceCalculation.accompanyingPrices()
						.get(DISCOUNTED_PRICE)
						.map(priceExtractor)
						.map(discountedPrice -> {
							final BigDecimal discount = discountedPrice.subtract(priceForSale);
							return BigDecimal.ZERO.compareTo(discount) < 0 ? discount : BigDecimal.ZERO;
						})
						.orElse(NEGATIVE_DISCOUNT);
				})
				.orElse(NEGATIVE_DISCOUNT);
			memoizedDiscounts.put(entity.getPrimaryKey(), calculatedResult);
			return calculatedResult;
		}
	}

	/**
	 * Comparator for entities based on their discounted price. The discounted price is calculated
	 * using a provided function to extract the price and a comparator to compare the extracted prices.
	 * The class supports memoization for performance optimization.
	 *
	 * This comparator is used for calculate the discount based on simplified information stored in the memory indexes.
	 */
	private static class DiscountIndexComparator
		implements Comparator<PriceRecordContract>,
		PriceRecordsLookupResultAware,
		NonSortedRecordsProvider,
		Serializable
	{
		@Serial private static final long serialVersionUID = 3931269515511851166L;
		/**
		 * Constant representing that the discount cannot be calculated.
		 */
		private static final int DISCOUNT_CANNOT_BE_CALCULATED = -1;
		/**
		 * Constant representing that the discount is zero. We cannot use the zero value as a constant because
		 * it is a default value for `int` type - i.e. for not yet calculated discounts.
		 */
		private static final int ZERO_DISCOUNT = -2;
		/**
		 * The formulas for calculating the reference price for entities.
		 */
		@Nonnull private final List<Formula> referencePriceFormulas;
		/**
		 * The function to extract the price from the price record (sometimes the price with tax is used, sometimes
		 * the price without tax is used).
		 */
		@Nonnull private final ToIntFunction<PriceRecordContract> priceExtractor;
		/**
		 * The comparator to compare the prices (ASC/DESC).
		 */
		@Nonnull private final IntComparator priceComparator;
		/**
		 * Memoized discounts for the price records.
		 */
		private final IntIntMap memoizedDiscounts = new IntIntHashMap(128);
		/**
		 * Contains result of the matching algorithm that pairs {@link PriceRecord} of selling prices to entity primary
		 * keys visible in formula result.
		 */
		private PriceRecordContract[] sellingPriceRecords;
		/**
		 * Contains result of the matching algorithm that pairs {@link PriceRecord} of reference prices to entity
		 * primary keys visible in formula result.
		 */
		private PriceRecordContract[] referencePriceRecords;
		/**
		 * Contains array of entity primary keys for which no reference {@link PriceRecord} was found.
		 */
		private int[] nonSortedEntities;

		public DiscountIndexComparator(
			@Nonnull IntComparator priceComparator,
			@Nonnull ToIntFunction<PriceRecordContract> priceExtractor,
			@Nonnull List<Formula> referencePriceFormulas
		) {
			this.priceComparator = priceComparator;
			this.priceExtractor = priceExtractor;
			this.referencePriceFormulas = referencePriceFormulas;
		}

		@Override
		public void setPriceRecordsLookupResult(
			@Nonnull QueryExecutionContext queryContext,
			@Nonnull RoaringBitmap filteredEntityPrimaryKeys,
			@Nonnull FilteredPriceRecordsLookupResult priceRecordsLookupResult
		) {
			// initialize the selling price records
			this.sellingPriceRecords = priceRecordsLookupResult.getPriceRecords();

			// now calculate the reference prices to be used for calculating the discount
			final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = this.referencePriceFormulas
				.stream()
				.map(
					it -> FormulaCloner.clone(
						it,
						formula -> {
							if (formula instanceof SumPriceTerminationFormula sptf) {
								return sptf.withIndividualPricePredicate(
									new InnerRecordInSellingPricesMatchingPredicate(this.sellingPriceRecords, this.priceExtractor)
								);
							} else if (formula instanceof LowestPriceTerminationFormula fvptf) {
								return fvptf.withIndividualPricePredicate(
									new InnerRecordInSellingPricesMatchingPredicate(this.sellingPriceRecords, this.priceExtractor)
								);
							} else {
								return formula;
							}
						}
					)
				)
				.flatMap(it -> FormulaFinder.find(it, FilteredPriceRecordAccessor.class, LookUp.SHALLOW).stream())
				.toList();

			final FilteredPriceRecordsLookupResult referencePriceLookupResult = new FilteredPriceRecordsCollector(
				filteredEntityPrimaryKeys, filteredPriceRecordAccessors, queryContext
			).getResult();

			// initialize the reference prices
			this.referencePriceRecords = referencePriceLookupResult.getPriceRecords();
			// and note that some entities do not have reference prices and thus cannot be sorted
			this.nonSortedEntities = referencePriceLookupResult.getNotFoundEntities();
		}

		@Override
		public int compare(PriceRecordContract o1, PriceRecordContract o2) {
			final int priceDiscount1 = getDiscountFor(o1);
			final int priceDiscount2 = getDiscountFor(o2);
			if (DISCOUNT_CANNOT_BE_CALCULATED == priceDiscount1 && DISCOUNT_CANNOT_BE_CALCULATED == priceDiscount2) {
				return Integer.compare(o1.entityPrimaryKey(), o2.entityPrimaryKey());
			} else if (DISCOUNT_CANNOT_BE_CALCULATED == priceDiscount1) {
				return 1;
			} else if (DISCOUNT_CANNOT_BE_CALCULATED == priceDiscount2) {
				return -1;
			} else {
				return priceComparator.compare(priceDiscount1, priceDiscount2);
			}
		}

		@Nullable
		@Override
		public int[] getNonSortedRecords() {
			return this.nonSortedEntities;
		}

		/**
		 * Calculates the discount for the provided price record.
		 *
		 * @param priceRecord the price record for which the discount is calculated; must not be null
		 * @return the calculated discount or a constant indicating that the discount cannot be calculated;
		 * never null
		 */
		private int getDiscountFor(@Nonnull PriceRecordContract priceRecord) {
			final int discount = this.memoizedDiscounts.get(priceRecord.entityPrimaryKey());
			if (discount != 0) {
				return discount == ZERO_DISCOUNT ? 0 : discount;
			} else {
				final int sellingPriceIndex = ArrayUtils.binarySearch(
					this.sellingPriceRecords,
					priceRecord,
					(p1, p2) -> Integer.compare(p1.entityPrimaryKey(), p2.entityPrimaryKey())
				);
				final int referencePriceIndex = ArrayUtils.binarySearch(
					this.referencePriceRecords,
					priceRecord,
					(p1, p2) -> Integer.compare(p1.entityPrimaryKey(), p2.entityPrimaryKey())
				);
				if (sellingPriceIndex < 0 || referencePriceIndex < 0) {
					this.memoizedDiscounts.put(priceRecord.entityPrimaryKey(), DISCOUNT_CANNOT_BE_CALCULATED);
					return DISCOUNT_CANNOT_BE_CALCULATED;
				} else {
					final int sellingPrice = priceExtractor.applyAsInt(this.sellingPriceRecords[sellingPriceIndex]);
					final int referencePrice = priceExtractor.applyAsInt(this.referencePriceRecords[referencePriceIndex]);
					final int theDiscount = sellingPrice >= referencePrice ? 0 : referencePrice - sellingPrice;
					this.memoizedDiscounts.put(priceRecord.entityPrimaryKey(), theDiscount == 0 ? ZERO_DISCOUNT : theDiscount);
					return theDiscount;
				}
			}
		}

		/**
		 * This predicate is used to filter out the prices that do not relate to the selling price of the entity during
		 * calculation of the reference price. When discount is calculated we always want to calculate the discount as
		 * the difference between the selling price and the reference price of the very same product. This gets complicated
		 * with {@link PriceInnerRecordHandling#LOWEST_PRICE} and {@link PriceInnerRecordHandling#SUM} where the selling
		 * price refers to only one inner product or is a virtual sum of multiple products.
		 *
		 * This predicate uses optimized way for searching the selling price records. It builds on the fact that the
		 * selling price records are sorted by entity primary key and inner record id and that examined records will
		 * be in the same order.
		 */
		private static class InnerRecordInSellingPricesMatchingPredicate implements SumPredicate<PriceRecordContract> {
			/**
			 * The array of selling price records.
			 */
			private final PriceRecordContract[] sellingPriceRecords;
			/**
			 * The function to extract the price from the price record (sometimes the price with tax is used, sometimes
			 * the price without tax is used).
			 */
			private final ToIntFunction<PriceRecordContract> priceExtractor;
			/**
			 * Precalculated length of the selling price records.
			 */
			private final int sellingPriceRecordsLength;
			/**
			 * The index of the last examined selling price record.
			 */
			private int lastIndex = 0;

			public InnerRecordInSellingPricesMatchingPredicate(
				@Nonnull PriceRecordContract[] sellingPriceRecords,
				@Nonnull ToIntFunction<PriceRecordContract> priceExtractor
			) {
				this.sellingPriceRecords = sellingPriceRecords;
				this.sellingPriceRecordsLength = sellingPriceRecords.length;
				this.priceExtractor = priceExtractor;
			}

			@Override
			public boolean test(PriceRecordContract examinedRecord) {
				// if we can progress in the selling price records, we do so
				if (this.lastIndex + 1 < this.sellingPriceRecordsLength) {
					// use the last index to locate the selling price record for comparison
					PriceRecordContract priceRecord = this.sellingPriceRecords[this.lastIndex];
					// progress until we find the record with matching entity primary key or reach the end
					while (priceRecord.entityPrimaryKey() < examinedRecord.entityPrimaryKey() && this.lastIndex + 1 < this.sellingPriceRecordsLength) {
						priceRecord = this.sellingPriceRecords[++this.lastIndex];
					}
					// if we found the record with matching entity primary key, we can now compare the inner record ids
					if (priceRecord.entityPrimaryKey() == examinedRecord.entityPrimaryKey()) {
						// progress until we find the record with matching inner record id or reach the end
						while (
							priceRecord.innerRecordId() < examinedRecord.innerRecordId() &&
							this.lastIndex + 1 < this.sellingPriceRecordsLength &&
							this.sellingPriceRecords[this.lastIndex + 1].entityPrimaryKey() < examinedRecord.entityPrimaryKey()
						) {
							priceRecord = this.sellingPriceRecords[++this.lastIndex];
						}
						// return whether the inner record ids match
						return priceRecord.relatesTo(examinedRecord);
					}
				}
				// if we reached the end of the selling price records, we cannot find the matching record
				return false;
			}

			@Override
			public int getMissingComponentsPrice(@Nonnull IntCollection includedInnerRecordIds) {
				// use the last index to locate the selling price record for comparison
				PriceRecordContract priceRecord = this.sellingPriceRecords[this.lastIndex];
				if (priceRecord instanceof CumulatedVirtualPriceRecord cumulatedPriceRecord) {
					final IntObjectMap<PriceRecordContract> sellingPrices = cumulatedPriceRecord.innerRecordPrices();
					int omittedPrice = 0;
					for (IntObjectCursor<PriceRecordContract> entry : sellingPrices) {
						if (!includedInnerRecordIds.contains(entry.key)) {
							omittedPrice += this.priceExtractor.applyAsInt(entry.value);
						}
					}
					return omittedPrice;
				} else {
					return 0;
				}
			}
		}
	}
}
