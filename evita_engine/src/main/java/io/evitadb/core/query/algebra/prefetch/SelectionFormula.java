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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.EntityRequire;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Selection formula is an optimization opportunity that can compute its results in two different ways, and it chooses
 * the one promising better results.
 *
 * 1. standard way of computing results is via {@link #getDelegate()} formula - but it may require quite a lot of computations
 * 2. alternative way of computing results is via {@link #alternative} filter that can operate only when explicit IDs
 * are present in request in conjunction form (AND)
 *
 * For very small set of entities known upfront it's beneficial to fetch their bodies from the datastore and apply
 * filtering on real data instead of operating on large bitmaps present in index. This form of filtering is even better
 * in case the entity is also required in the response. This approach is targeted especially on methods that retrieve
 * the entity with its contents by primary key or unique attribute. In such case entity would be fetched from
 * the underlying data store anyway, so when we prefetch it - we may avoid expensive bitmap joins to check additional
 * constraints of the request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SelectionFormula extends AbstractFormula implements FilteredPriceRecordAccessor, FilteredOutPriceRecordAccessor, RequirementsDefiner {
	private static final long CLASS_ID = 3311110127363103780L;
	/**
	 * Contains reference to a visitor that was used for creating this formula instance.
	 */
	private final FilterByVisitor filterByVisitor;
	/**
	 * Contains the alternative computation based on entity contents filtering.
	 */
	private final EntityToBitmapFilter alternative;
	/**
	 * Memoized predicate stored upon first calculation to lower computational resources.
	 */
	private PriceAmountPredicate memoizedPredicate;
	/**
	 * Memoized clone stored upon first calculation to lower computational resources.
	 */
	private Formula memoizedClone;

	public SelectionFormula(@Nonnull FilterByVisitor filterByVisitor, @Nonnull Formula delegate, @Nonnull EntityToBitmapFilter alternative) {
		super(delegate);
		Assert.notNull(!(delegate instanceof SkipFormula), "The delegate formula cannot be a skip formula!");
		this.filterByVisitor = filterByVisitor;
		this.alternative = alternative;
	}

	@Nullable
	@Override
	public EntityRequire getEntityRequire() {
		return alternative.getEntityRequire();
	}

	/**
	 * Returns delegate formula that computes the result in a standard way.
	 */
	public Formula getDelegate() {
		return innerFormulas[0];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, "Exactly one inner formula is expected!");
		return new SelectionFormula(
			filterByVisitor, innerFormulas[0], alternative
		);
	}

	@Override
	public int getEstimatedCardinality() {
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			.map(List::size)
			.orElseGet(getDelegate()::getEstimatedCardinality);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public String toString() {
		return "APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE";
	}

	@Nullable
	@Override
	public PriceAmountPredicate getRequestedPredicate() {
		if (this.memoizedPredicate == null) {
			// if the entities were prefetched we passed the "is it worthwhile" check
			this.memoizedPredicate = Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
				// ask the alternative solution for filtered price records
				.map(it ->
					alternative instanceof FilteredOutPriceRecordAccessor ?
						((FilteredOutPriceRecordAccessor) alternative).getRequestedPredicate() :
						PriceAmountPredicate.ALL
				)
				// otherwise collect the filtered records from the delegate
				.orElseGet(() -> {
					// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
					final Collection<FilteredOutPriceRecordAccessor> filteredOutPriceRecordAccessors = FormulaFinder.findAmongChildren(
						this, FilteredOutPriceRecordAccessor.class, LookUp.SHALLOW
					);
					// all accessors must have the same predicate
					PriceAmountPredicate predicate = null;
					for (FilteredOutPriceRecordAccessor filteredOutPriceRecordAccessor : filteredOutPriceRecordAccessors) {
						if (predicate == null) {
							predicate = filteredOutPriceRecordAccessor.getRequestedPredicate();
						} else {
							Assert.isPremiseValid(
								predicate.equals(filteredOutPriceRecordAccessor.getRequestedPredicate()),
								"All filtered out price record accessors must have the same predicate!"
							);
						}
					}
					return predicate;
				});
		}
		return this.memoizedPredicate;
	}

	@Nonnull
	@Override
	public Formula getCloneWithPricePredicateFilteredOutResults() {
		if (this.memoizedClone == null) {
			// if the entities were prefetched we passed the "is it worthwhile" check
			this.memoizedClone = Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
				// ask the alternative solution for filtered price records
				.map(it ->
					alternative instanceof FilteredOutPriceRecordAccessor ?
						((FilteredOutPriceRecordAccessor) alternative).getCloneWithPricePredicateFilteredOutResults() :
						EmptyFormula.INSTANCE
				)
				// otherwise collect the filtered records from the delegate
				.orElseGet(() -> {
					// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
					final Formula[] filteredOutRecords = FormulaFinder.findAmongChildren(
							this, FilteredOutPriceRecordAccessor.class, LookUp.SHALLOW
						)
						.stream()
						.map(FilteredOutPriceRecordAccessor::getCloneWithPricePredicateFilteredOutResults)
						.toArray(Formula[]::new);

					return FormulaFactory.or(filteredOutRecords);
				});
		}
		return this.memoizedClone;
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords() {
		// if the entities were prefetched we passed the "is it worthwhile" check
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			// ask the alternative solution for filtered price records
			.map(it ->
				alternative instanceof FilteredPriceRecordAccessor ?
					((FilteredPriceRecordAccessor) alternative).getFilteredPriceRecords() :
					new ResolvedFilteredPriceRecords()
			)
			// otherwise collect the filtered records from the delegate
			.orElseGet(() -> FilteredPriceRecords.createFromFormulas(this, this.compute()));
	}

	@Override
	protected long getEstimatedCostInternal() {
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			.map(it -> {
				if (alternative.getEntityRequire() == null) {
					return 0L;
				}
				return (1 + alternative.getEntityRequire().getRequirements().length) * 148L;
			})
			.orElseGet(getDelegate()::getEstimatedCost);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/*
	 * We need to override this method so that sorting logic will communicate with our implementation and doesn't ask
	 * for filtered price records from this formula {@link #getDelegate()} children which would require computation executed
	 * by the {@link #getDelegate()} which we try to avoid by alternative solution.
	 */

	@Override
	protected long getCostInternal() {
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			.map(it -> {
				if (alternative.getEntityRequire() == null) {
					return 0L;
				}

				return (1 + alternative.getEntityRequire().getRequirements().length) * 148L;
			})
			.orElseGet(getDelegate()::getCost);
	}

	@Override
	protected long getCostToPerformanceInternal() {
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			.map(it -> getCost() / Math.max(1, compute().size()))
			.orElseGet(getDelegate()::getCostToPerformanceRatio);
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// if the entities were prefetched we passed the "is it worthwhile" check
		return Optional.ofNullable(filterByVisitor.getPrefetchedEntities())
			.map(it -> alternative.filter(filterByVisitor))
			.orElseGet(getDelegate()::compute);
	}

}
