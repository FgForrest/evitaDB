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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.exception.EntityHasNoPricesException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceDiscount;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.price.FilteredPricesSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link PriceDiscount} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PriceDiscountTranslator implements OrderingConstraintTranslator<PriceDiscount> {

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

		// if prefetch happens we need to prefetch prices so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(PriceContent.respectingFilter(priceDiscount.getPriceLists()));

		// are filtered prices used in the filtering?
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
			orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);

		final Sorter thisSorter;
		if (!filteredPriceRecordAccessors.isEmpty()) {
			// if so, create filtered prices sorter
			thisSorter = new FilteredPricesSorter(
				priceDiscount.getOrderDirection(),
				orderByVisitor.getQueryPriceMode(),
				filteredPriceRecordAccessors
			);
		} else {
			// otherwise, we cannot sort the entities by price
			thisSorter = NoSorter.INSTANCE;
		}

		final EntityComparator entityComparator;
		final Function<PriceContract, BigDecimal> priceExtractor = orderByVisitor.getQueryPriceMode() == QueryPriceMode.WITH_TAX ?
			PriceContract::priceWithTax : PriceContract::priceWithoutTax;
		if (priceDiscount.getOrderDirection() == OrderDirection.ASC) {
			entityComparator = new PriceDiscountEntityComparator(priceExtractor, Comparator.naturalOrder(), priceDiscount.getPriceLists());
		} else {
			entityComparator = new PriceDiscountEntityComparator(priceExtractor, Comparator.reverseOrder(), priceDiscount.getPriceLists());
		}

		return Stream.of(
			new PrefetchedRecordsSorter(entityComparator),
			thisSorter
		);
	}

	/**
	 * Comparator for entities based on their discounted price. The discounted price is calculated
	 * using a provided function to extract the price and a comparator to compare the extracted prices.
	 * The class supports memoization for performance optimization.
	 */
	@RequiredArgsConstructor
	private static class PriceDiscountEntityComparator implements EntityComparator, Serializable {
		@Serial private static final long serialVersionUID = -788187858524495219L;
		private static final String DISCOUNTED_PRICE = "discountedPrice";
		private static final BigDecimal NEGATIVE_DISCOUNT = BigDecimal.valueOf(-1);
		private final Function<PriceContract, BigDecimal> priceExtractor;
		private final Comparator<BigDecimal> priceComparator;
		private final String[] discountPriceLists;
		private CompositeObjectArray<EntityContract> nonSortedEntities;
		private final IntObjectMap<BigDecimal> memoizedDiscounts = new IntObjectHashMap<>(64);

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
							final BigDecimal discount = priceForSale.subtract(discountedPrice);
							return BigDecimal.ZERO.compareTo(discount) < 0 ? discount : BigDecimal.ZERO;
						})
						.orElse(NEGATIVE_DISCOUNT);
				})
				.orElse(NEGATIVE_DISCOUNT);
			memoizedDiscounts.put(entity.getPrimaryKey(), calculatedResult);
			return calculatedResult;
		}
	}

}
