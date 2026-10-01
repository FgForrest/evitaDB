/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
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
 * This implementation of {@link OrderingConstraintTranslator} converts {@link PriceNatural} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceNaturalTranslator implements OrderingConstraintTranslator<PriceNatural> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull PriceNatural priceNatural, @Nonnull OrderByVisitor orderByVisitor) {
		if (orderByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = orderByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new EntityHasNoPricesException(schema.getName())
			);
		}

		// if prefetch happens we need to prefetch prices so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(PriceContent.respectingFilter());

		// are filtered prices used in the filtering?
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
			orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);

		final Sorter thisSorter;
		if (!filteredPriceRecordAccessors.isEmpty()) {
			// if so, create filtered prices sorter
			thisSorter = new FilteredPricesSorter(
					priceNatural.getOrderDirection(),
					orderByVisitor.getQueryPriceMode(),
					filteredPriceRecordAccessors
			);
		} else if (orderByVisitor.isWithinSegment()) {
			// otherwise, we cannot sort the entities by price - and a segment takes no entity it cannot sort
			thisSorter = null;
		} else {
			// otherwise, we cannot sort the entities by price
			thisSorter = NoSorter.INSTANCE;
		}

		final EntityComparator entityComparator;
		final Function<PriceContract, BigDecimal> priceExtractor = orderByVisitor.getQueryPriceMode() == QueryPriceMode.WITH_TAX ?
			PriceContract::priceWithTax : PriceContract::priceWithoutTax;
		final boolean withinSegment = orderByVisitor.isWithinSegment();
		if (priceNatural.getOrderDirection() == OrderDirection.ASC) {
			entityComparator = new PriceForSaleEntityComparator(
				priceExtractor, Comparator.naturalOrder(), withinSegment
			);
		} else {
			entityComparator = new PriceForSaleEntityComparator(
				priceExtractor, Comparator.reverseOrder(), withinSegment
			);
		}

		final PrefetchedRecordsSorter prefetchedRecordsSorter = new PrefetchedRecordsSorter(entityComparator);
		return thisSorter == null ?
			Stream.of(prefetchedRecordsSorter) : Stream.of(prefetchedRecordsSorter, thisSorter);
	}

	/**
	 * The PriceForSaleEntityComparator class is responsible for comparing two EntityContract objects based on their prices.
	 * It implements the EntityComparator and Serializable interfaces.
	 * It uses a provided priceExtractor function to extract BigDecimal prices from PriceContract objects and a provided
	 * priceComparator to compare these prices. If an entity doesn't have a price for sale, it falls back to
	 * BigDecimal.ZERO - except within a segment, where such an entity is classified as not sortable and left out of the
	 * segment.
	 */
	@RequiredArgsConstructor
	private static class PriceForSaleEntityComparator implements EntityComparator, Serializable {
		@Serial private static final long serialVersionUID = 5350421028034052368L;
		private final Function<PriceContract, BigDecimal> priceExtractor;
		private final Comparator<BigDecimal> priceComparator;
		/**
		 * `true` when the comparator orders a segment, which takes only the entities with a price for sale.
		 */
		private final boolean withinSegment;
		private final IntObjectMap<BigDecimal> memoizedPrices = new IntObjectHashMap<>(64);

		/**
		 * Within a segment, classifies the entity as sortable when it has a price for sale, memoizing the price for the
		 * comparisons that follow; outside a segment every entity is sortable and one without a price for sale is
		 * compared as zero.
		 *
		 * @param entity the entity to classify
		 * @return `true` when the entity is to be sorted by this comparator
		 */
		@Override
		public boolean canSort(@Nonnull EntityContract entity) {
			if (!this.withinSegment) {
				return true;
			}
			final Optional<PriceContract> priceForSale = entity.getPriceForSaleIfAvailable();
			if (priceForSale.isPresent()) {
				this.memoizedPrices.put(entity.getPrimaryKey(), this.priceExtractor.apply(priceForSale.get()));
				return true;
			} else {
				return false;
			}
		}

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return List.of();
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			return this.priceComparator.compare(
				getPriceForSaleOrElseZero(o1),
				getPriceForSaleOrElseZero(o2)
			);
		}

		/**
		 * Retrieves the price for sale of the given entity or returns zero if no price is available.
		 * The result is memoized to improve performance for subsequent calls with the same entity.
		 *
		 * @param o1 The entity from which to retrieve the price for sale.
		 * @return The price for sale of the entity or zero if no price is available.
		 */
		@Nonnull
		private BigDecimal getPriceForSaleOrElseZero(@Nonnull EntityContract o1) {
			final BigDecimal memoizedResult = this.memoizedPrices.get(o1.getPrimaryKey());
			if (memoizedResult != null) {
				return memoizedResult;
			}
			final BigDecimal calculatedResult = o1.getPriceForSale().map(this.priceExtractor).orElse(BigDecimal.ZERO);
			this.memoizedPrices.put(o1.getPrimaryKey(), calculatedResult);
			return calculatedResult;
		}
	}
}
