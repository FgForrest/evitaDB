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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.price.FilteredOutPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
public class SelectionFormula extends AbstractFormula implements ChildrenDependentFormula, FilteredPriceRecordAccessor, FilteredOutPriceRecordAccessor, RequirementsDefiner {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 3311110127363103780L;
	/**
	 * Contains the alternative computation based on entity contents filtering.
	 */
	private final EntityToBitmapFilter alternative;
	/**
	 * Memoized predicate stored upon first calculation to lower computational resources.
	 */
	@Nullable private PriceAmountPredicate memoizedPredicate;
	/**
	 * Memoized clone stored upon first calculation to lower computational resources.
	 */
	private Formula memoizedClone;
	/**
	 * Memoized SHALLOW probe over the delegate sub-tree for inner {@link FilteredPriceRecordAccessor}
	 * instances. The tree shape is fixed at construction so the result is stable for the lifetime of
	 * this formula and can be safely cached after the first walk.
	 */
	@Nullable private Collection<FilteredPriceRecordAccessor> memoizedInnerAccessors;
	/**
	 * Memoized result of {@link #exposesPerInnerRecordHistogramRecords()}. Both inputs to the answer —
	 * the delegate tree shape and each inner LP's final `collectPerInnerRecordPrices` flag — are
	 * immutable after construction, so the boolean is computed at most once per instance.
	 */
	@Nullable private Boolean memoizedExposesPerInnerRecordHistogramRecords;
	/**
	 * Updated cardinality based on current execution context.
	 */
	@Nullable private Integer prefetchEstimatedCardinality;
	/**
	 * Updated cost based on current execution context.
	 */
	@Nullable private Long prefetchEstimatedCost;

	public SelectionFormula(@Nonnull Formula delegate, @Nonnull EntityToBitmapFilter alternative) {
		Assert.isTrue(!(delegate instanceof SkipFormula), "The delegate formula cannot be a skip formula!");
		this.alternative = alternative;
		this.initFields(delegate);
	}

	@Nullable
	@Override
	public EntityFetchRequire getEntityRequire() {
		return this.alternative.getEntityRequire();
	}

	/**
	 * Returns delegate formula that computes the result in a standard way.
	 */
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, "Exactly one inner formula is expected!");
		return new SelectionFormula(innerFormulas[0], this.alternative);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		super.initialize(executionContext);
		this.prefetchEstimatedCardinality = Optional.ofNullable(executionContext.getPrefetchedEntities())
			.map(List::size)
			.orElse(null);
		this.prefetchEstimatedCost = Optional.ofNullable(executionContext.getPrefetchedEntities())
			.map(it -> {
				if (this.alternative.getEntityRequire() == null) {
					return 0L;
				}
				return (1 + this.alternative.getEntityRequire().getRequirements().length) * 148L;
			})
			.orElse(null);
	}

	@Override
	public int getEstimatedCardinality() {
		return Optional.ofNullable(this.prefetchEstimatedCardinality)
			.orElseGet(() -> getDelegate().getEstimatedCardinality());
	}

	@Override
	public long getEstimatedCost() {
		return Optional.ofNullable(this.prefetchEstimatedCost)
			.orElseGet(() -> getDelegate().getEstimatedCost());
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
			Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
			// if the entities were prefetched we passed the "is it worthwhile" check
			this.memoizedPredicate = Optional.ofNullable(this.executionContext.getPrefetchedEntities())
				// ask the alternative solution for filtered price records
				.map(it ->
					this.alternative instanceof FilteredOutPriceRecordAccessor ?
						((FilteredOutPriceRecordAccessor) this.alternative).getRequestedPredicate() :
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
								Objects.equals(predicate, filteredOutPriceRecordAccessor.getRequestedPredicate()),
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
			Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
			// if the entities were prefetched we passed the "is it worthwhile" check
			this.memoizedClone = Optional.ofNullable(this.executionContext.getPrefetchedEntities())
				// ask the alternative solution for filtered price records
				.map(it ->
					this.alternative instanceof FilteredOutPriceRecordAccessor ?
						((FilteredOutPriceRecordAccessor) this.alternative).getCloneWithPricePredicateFilteredOutResults() :
						EmptyFormula.INSTANCE
				)
				// otherwise collect the filtered records from the delegate
				.orElseGet(() -> {
					// collect all FilteredPriceRecordAccessor that were involved in computing delegate result
					final Collection<FilteredOutPriceRecordAccessor> accessors = FormulaFinder.findAmongChildren(
						this, FilteredOutPriceRecordAccessor.class, LookUp.SHALLOW
					);
					final Formula[] filteredOutRecords = new Formula[accessors.size()];
					int index = 0;
					for (FilteredOutPriceRecordAccessor accessor : accessors) {
						filteredOutRecords[index++] = accessor.getCloneWithPricePredicateFilteredOutResults();
					}
					return FormulaFactory.or(filteredOutRecords);
				});
		}
		return this.memoizedClone;
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		// if the entities were prefetched we passed the "is it worthwhile" check
		return Optional.ofNullable(this.executionContext.getPrefetchedEntities())
			// ask the alternative solution for filtered price records
			.map(it -> {
					if (this.alternative instanceof FilteredPriceRecordAccessor fpra) {
						return fpra.getFilteredPriceRecords(context);
					} else {
						return new ResolvedFilteredPriceRecords();
					}
				}
			)
			// otherwise collect the filtered records from the delegate
			.orElseGet(() -> FilteredPriceRecords.createFromFormulas(this, this.compute(), this.executionContext));
	}

	/**
	 * Propagates the histogram side-output capability from inner accessors so {@link PriceHistogramComputer}'s
	 * bypass continues to fire even when prefetch wiring (`PriceInPriceListsTranslator`) inserts a
	 * {@link SelectionFormula} between the histogram and the histogram-aware
	 * `io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula`. A naive
	 * top-level-only probe would miss wrapped LPs and incorrectly disable the bypass.
	 *
	 * Semantics are "all-true": this wrapper exposes the side-output only if **every** inner accessor
	 * does. The histogram producer collects the accessor list from a `withoutUserFilter` view of the
	 * filtering tree, so wrappers above un-flagged inner LPs (e.g. price-between LPs under
	 * `UserFilterFormula`) never end up in the histogram accessor list in the first place.
	 *
	 * **Prefetch parity**: the probe ignores `isPrefetchExecution()` — both alternative plans (index and
	 * prefetch) must return the same histogram or `VERIFY_ALTERNATIVE_INDEX_RESULTS` fails. The cost of
	 * running the inner LP's `computeInternal()` on the prefetch path to populate the side-output is
	 * accepted because histogram queries are inherently expensive; the alternative is silently wrong
	 * histograms whenever prefetch is enabled.
	 */
	@Override
	public boolean exposesPerInnerRecordHistogramRecords() {
		if (this.memoizedExposesPerInnerRecordHistogramRecords == null) {
			this.memoizedExposesPerInnerRecordHistogramRecords = computeExposesPerInnerRecordHistogramRecords();
		}
		return this.memoizedExposesPerInnerRecordHistogramRecords;
	}

	/**
	 * One-shot computation backing {@link #exposesPerInnerRecordHistogramRecords()}. Delegates to the
	 * shared {@link FilteredPriceRecords#allAccessorsExposePerInnerRecordHistogram(Collection)} probe
	 * so the "all-or-nothing" rule cannot drift between the wrapper and the histogram producer. An
	 * empty inner-accessor set returns `false` because there is no histogram-aware LP to forward to.
	 *
	 * @return `true` iff every inner {@link FilteredPriceRecordAccessor} exposes the per-inner-record
	 *         histogram side-output
	 */
	private boolean computeExposesPerInnerRecordHistogramRecords() {
		return FilteredPriceRecords.allAccessorsExposePerInnerRecordHistogram(findInnerAccessors());
	}

	/**
	 * Walks the delegate's inner accessors and merges their per-inner-record histogram records into a
	 * single flat array. Called by {@link PriceHistogramComputer} only when
	 * {@link #exposesPerInnerRecordHistogramRecords()} returned `true`, so every inner accessor is
	 * guaranteed to populate its side-output without throwing.
	 *
	 * Always delegates to the inner accessors — even on the prefetch path — so both alternative plans
	 * produce the same histogram (see the JavaDoc on
	 * {@link #exposesPerInnerRecordHistogramRecords()}). The inner LP's `compute()` is triggered
	 * lazily by its own override and populates the side-output regardless of whether
	 * `SelectionFormula.computeInternal()` short-circuited via the prefetch alternative.
	 *
	 * Behaviour matrix:
	 *
	 * - no inner accessors → fall back to {@link #getFilteredPriceRecords(QueryExecutionContext)};
	 * - any inner accessor missing the capability → fall back to per-entity records (the wrapper
	 *   honours the default interface contract for non-histogram-aware accessors rather than tripping
	 *   the merge guard mid-loop on a flag-off LP);
	 * - single histogram-aware inner accessor → size-1 fast path returns its records directly without
	 *   the intermediate array and `System.arraycopy` round-trip;
	 * - two or more histogram-aware inner accessors → the actual concatenation is delegated to
	 *   {@link FilteredPriceRecords#mergePerInnerRecordHistogramRecords(Collection, QueryExecutionContext)}
	 *   so the algorithm stays in one place.
	 *
	 * The capability-mismatch fallbacks differ intentionally between size-1 and size≥2:
	 *
	 * - size-1 falls back to the *inner* accessor's per-entity records because the wrapper's view
	 *   over a single delegate is, by construction, that single delegate's view — there is nothing to
	 *   merge and routing through the wrapper would just add a no-op `createFromFormulas` round-trip;
	 * - size≥2 falls back to the *wrapper's* per-entity records because per-inner-record arrays from
	 *   multiple accessors can overlap on the same entity primary key, and only the wrapper's own
	 *   `getFilteredPriceRecords` knows how to reduce them (via `createFromFormulas`) into a single
	 *   coherent per-entity view.
	 *
	 * Today both fallbacks are dead in production because the caller (`PriceHistogramComputer`)
	 * always probes `exposesPerInnerRecordHistogramRecords()` first; the asymmetry is kept as a
	 * defensive safety net in case that contract ever loosens.
	 */
	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecordsForHistogram(@Nonnull QueryExecutionContext context) {
		final Collection<FilteredPriceRecordAccessor> innerAccessors = findInnerAccessors();
		if (innerAccessors.isEmpty()) {
			return getFilteredPriceRecords(context);
		}
		if (innerAccessors.size() == 1) {
			// size-1 fast path — common case where the delegate wraps a single histogram-aware LP; avoids the
			// intermediate array and `System.arraycopy` round-trip below. Mirror the default interface
			// contract: when the inner accessor is not histogram-aware, fall back to its per-entity records
			// rather than trip the LP's histogram-collection guard.
			final FilteredPriceRecordAccessor inner = innerAccessors.iterator().next();
			if (!inner.exposesPerInnerRecordHistogramRecords()) {
				return inner.getFilteredPriceRecords(context);
			}
			return inner.getFilteredPriceRecordsForHistogram(context);
		}
		if (!exposesPerInnerRecordHistogramRecords()) {
			// size>=2 capability mismatch — at least one inner accessor lacks the side-output, so entering
			// the merge loop would trip the flag-off LP's guard. Defer to the wrapper's per-entity output
			// (which the production planner always initialises with a non-null context) instead. Mirrors
			// the default interface contract for non-histogram-aware accessors.
			return getFilteredPriceRecords(context);
		}
		final PriceRecordContract[] merged = FilteredPriceRecords.mergePerInnerRecordHistogramRecords(
			innerAccessors, context
		);
		if (merged.length == 0) {
			return FilteredPriceRecords.EMPTY;
		}
		return new ResolvedFilteredPriceRecords(merged, SortingForm.NOT_SORTED);
	}

	/**
	 * Shared SHALLOW probe over the delegate sub-tree used by both
	 * {@link #exposesPerInnerRecordHistogramRecords()} and
	 * {@link #getFilteredPriceRecordsForHistogram(QueryExecutionContext)} so the two methods cannot
	 * accidentally diverge on the accessor set they reason about.
	 *
	 * @return inner {@link FilteredPriceRecordAccessor} instances reachable from the delegate at SHALLOW depth
	 */
	@Nonnull
	private Collection<FilteredPriceRecordAccessor> findInnerAccessors() {
		if (this.memoizedInnerAccessors == null) {
			this.memoizedInnerAccessors = FormulaFinder.find(
				getDelegate(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
			);
		}
		return this.memoizedInnerAccessors;
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
		Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
		return Optional.ofNullable(this.executionContext.getPrefetchedEntities())
			.map(it -> {
				if (this.alternative.getEntityRequire() == null) {
					return 0L;
				}

				return (1 + this.alternative.getEntityRequire().getRequirements().length) * 148L;
			})
			.orElseGet(() -> getDelegate().getCost());
	}

	@Override
	protected long getCostToPerformanceInternal() {
		Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
		return Optional.ofNullable(this.executionContext.getPrefetchedEntities())
			.map(it -> getCost() / Math.max(1, compute().size()))
			.orElseGet(() -> getDelegate().getCostToPerformanceRatio());
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		Assert.isPremiseValid(this.executionContext != null, "The formula hasn't been initialized!");
		// if the entities were prefetched we passed the "is it worthwhile" check
		if (this.executionContext.isPrefetchExecution()) {
			return this.alternative.filter(this.executionContext);
		} else {
			return getDelegate().compute();
		}
	}

}
